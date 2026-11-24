package com.yim1remote.app.data.ble

import java.util.TimeZone
import java.util.UUID
import kotlin.random.Random

/**
 * Pure-Kotlin encoding/decoding of the YI M1's BLE Wi-Fi provisioning protocol.
 *
 * This file deliberately imports **no Android class**, so all of it is exercised by
 * plain JVM unit tests. The Android transport lives in [BleProvisioner].
 *
 * Sources (see `docs/BLE-PROVISIONING.md` for the full evidence trail):
 *
 * * `research/vendor/yi-m1-controller-app/app/docs/PROTOCOL.md` §2 — hand-written
 *   reverse-engineering notes, **verified on real hardware** (firmware
 *   `1,3.1-cn,M1CN,0.0`) by that project's maintainer.
 * * `analysis/jadx-out/sources/com/xiaoyi/mirrorlesscamera/common/d.java` — the
 *   official app's `BleManager`, which independently matches: it reads
 *   `41106da2`, subscribes `41106da7`, writes `41106da0` with
 *   `protocolVersion + "," + Random().nextInt(99999) + ",android"`, and writes
 *   `41106da4` with `protocolVersion + "," + refId + "," + CRC32("1" + refId + token)`.
 *
 * The protocol is **plaintext and unauthenticated**: the pairing key is an
 * integer in `0..99998`, the "session checksum" is a bare zlib CRC-32, and the
 * only real authorisation is the physical accept prompt on the camera screen.
 * The camera stores exactly one pairing, so a new pairing silently invalidates
 * the official app's pairing.
 */
object BleUuids {

    private const val YI_SUFFIX = "-25ad-477b-a884-5038b6de4649"

    /** Primary GATT service exposing the whole camera control surface. */
    @JvmField
    val SERVICE: UUID = UUID.fromString("41106dd9$YI_SUFFIX")

    /** Write: pairing request `"<protocol>,<key>,android"`. Camera shows a prompt. */
    @JvmField
    val PAIRING_REQUEST: UUID = UUID.fromString("41106da0$YI_SUFFIX")

    /**
     * Declared by the official app but **absent on real hardware** (two
     * independent scans found 12 characteristics, none of them this one). Kept
     * only so diagnostics can say "present/absent" instead of re-deriving it.
     */
    @JvmField
    val RESPONSE_TOKEN: UUID = UUID.fromString("41106da1$YI_SUFFIX")

    /** Read: `"<protocol>,<bodyFw>,<region>,<lensFw>"`, e.g. `"1,M1,M1INT,1.1"`. */
    @JvmField
    val FIRMWARE_INFO: UUID = UUID.fromString("41106da2$YI_SUFFIX")

    /** Read: short status string (camera answers `"OK"`); optional liveness probe. */
    @JvmField
    val MISC_STATUS: UUID = UUID.fromString("41106da3$YI_SUFFIX")

    /** Write: start authenticated session `"<protocol>,<key>,<crc32>"`. */
    @JvmField
    val START_SESSION: UUID = UUID.fromString("41106da4$YI_SUFFIX")

    /** Write: `"ON"` / `"OFF"` — this is how the camera's Wi-Fi is switched. */
    @JvmField
    val WIFI_SWITCH: UUID = UUID.fromString("41106da5$YI_SUFFIX")

    /** Read: `"<ssid>,<password>"`. Only valid once the session is authenticated. */
    @JvmField
    val WIFI_AP: UUID = UUID.fromString("41106da6$YI_SUFFIX")

    /** Notify: pairing result — session token ASCII, **empty payload = rejected**. */
    @JvmField
    val PAIRING_NOTIFY: UUID = UUID.fromString("41106da7$YI_SUFFIX")

    /** Write: forget the stored pairing (payload = pairing key, per official app). */
    @JvmField
    val PAIRING_FORGET: UUID = UUID.fromString("41106da9$YI_SUFFIX")

    /** Write: camera clock, ASCII Unix epoch seconds. The camera has no RTC. */
    @JvmField
    val SYNC_TIME: UUID = UUID.fromString("41106dac$YI_SUFFIX")

    /** Write: `"3"` after discovering [UNKNOWN_NOTIFY_F]; purpose not documented. */
    @JvmField
    val RESUME_RELATED: UUID = UUID.fromString("41106dad$YI_SUFFIX")

    /** Notify (optional): lens info `"3,<...>,<lensType>,<lensVersion>"`. */
    @JvmField
    val UNKNOWN_NOTIFY_E: UUID = UUID.fromString("41106dae$YI_SUFFIX")

    /** Notify (optional): camera-state `"ac,<code>"` (3/7/8/9 = state changes). */
    @JvmField
    val UNKNOWN_NOTIFY_F: UUID = UUID.fromString("41106daf$YI_SUFFIX")

    /** Standard Client Characteristic Configuration descriptor. */
    @JvmField
    val CLIENT_CHARACTERISTIC_CONFIG: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val LABELS: Map<UUID, String> = linkedMapOf(
        SERVICE to "服务 41106dd9",
        PAIRING_REQUEST to "配对请求 41106da0",
        RESPONSE_TOKEN to "配对令牌 41106da1",
        FIRMWARE_INFO to "固件信息 41106da2",
        MISC_STATUS to "状态 41106da3",
        START_SESSION to "开始会话 41106da4",
        WIFI_SWITCH to "Wi-Fi 开关 41106da5",
        WIFI_AP to "Wi-Fi 凭据 41106da6",
        PAIRING_NOTIFY to "配对通知 41106da7",
        PAIRING_FORGET to "解除配对 41106da9",
        SYNC_TIME to "对时 41106dac",
        RESUME_RELATED to "恢复相关 41106dad",
        UNKNOWN_NOTIFY_E to "镜头通知 41106dae",
        UNKNOWN_NOTIFY_F to "相机状态通知 41106daf",
        CLIENT_CHARACTERISTIC_CONFIG to "CCCD 2902",
    )

    /** Human-readable label for logs; falls back to the short UUID for unknown ones. */
    fun label(uuid: UUID): String = LABELS[uuid] ?: shortName(uuid)

    /** Short form used in UI/diagnostics, e.g. `41106da2`. */
    fun shortName(uuid: UUID): String = uuid.toString().substring(0, 8)

    /** All characteristics the provisioning handshake may touch, in handshake order. */
    fun provisioningCharacteristics(): List<UUID> = listOf(
        FIRMWARE_INFO,
        PAIRING_NOTIFY,
        PAIRING_REQUEST,
        START_SESSION,
        WIFI_AP,
        WIFI_SWITCH,
    )
}

/**
 * zlib/IEEE 802.3 CRC-32 (the value Python's `zlib.crc32` returns).
 *
 * Implemented locally because the module must stay dependency-free and JVM
 * testable; the result is returned as an unsigned 32-bit value in a [Long], which
 * is what the camera expects in its decimal session payload.
 */
object ZlibCrc32 {

    private const val POLYNOMIAL = 0xEDB88320

    private val TABLE: IntArray = IntArray(256) { index ->
        var value = index
        repeat(8) {
            value = if (value and 1 != 0) {
                (value ushr 1) xor POLYNOMIAL.toInt()
            } else {
                value ushr 1
            }
        }
        value
    }

    /** Unsigned CRC-32 of [bytes], written as `0..4294967295`. */
    fun of(bytes: ByteArray): Long {
        var crc = -1 // 0xFFFFFFFF
        for (byte in bytes) {
            val index = (crc xor (byte.toInt() and 0xFF)) and 0xFF
            crc = (crc ushr 8) xor TABLE[index]
        }
        return (crc.inv().toLong() and 0xFFFFFFFFL)
    }

    /** Unsigned CRC-32 of the ASCII bytes of [text] (non-ASCII characters degrade to `?`). */
    fun ofAscii(text: String): Long = of(text.toByteArray(Charsets.US_ASCII))
}

/**
 * Camera identity decoded from the `41106da2` read.
 *
 * Field layout is **firmware-dependent** and one source contradicts the other:
 * the community client docs historically described field 2 as the region marker,
 * while a hardware session on `M1CN` reported field 2 as a firmware string and
 * field 3 as the region. The official APK (`common/d.java`) parses
 * `protocol, firmware, field2(region), field3(lens)`.
 *
 * [regionMarker] therefore selects the first field (from index 2 on) that looks
 * like a region token (`M1`, `M1CN`, `M1INT`, …); if none does, it falls back to
 * plain field 2, exactly like the official app. All raw fields are preserved in
 * [rawFields] so a diagnostic screen can show what the camera really sent.
 */
data class CameraIdentity(
    /** BLE protocol revision the camera negotiated (field 0, normally `1`). */
    val protocolVersion: Int,
    /** Body firmware / model id (field 1, e.g. `M1`). */
    val bodyFirmware: String,
    /** Region marker: `M1INT` international, `M1CN`/`M1` China. */
    val regionMarker: String,
    /** Lens firmware string (normally the last field). */
    val lensFirmware: String,
    /** Every comma-separated field exactly as received, trimmed. */
    val rawFields: List<String> = emptyList(),
    /** The trimmed raw ASCII payload. */
    val raw: String = "",
) {

    /** True when a plausible region token was found in the payload. */
    val hasKnownRegion: Boolean get() = regionMarker.isNotEmpty()

    /** China-mainland build (`M1CN`, or the bare `M1` some CN units report). */
    val isChinaVariant: Boolean
        get() = regionMarker.equals("M1CN", ignoreCase = true) ||
            regionMarker.equals("M1", ignoreCase = true)

    /**
     * Anything with a non-China region marker counts as the global build.
     * Empty/unknown markers are neither, so callers can say "unknown" instead of
     * silently treating a blank field as international.
     */
    val isGlobalVariant: Boolean get() = hasKnownRegion && !isChinaVariant

    /** One-line summary for UI/logs, e.g. `M1 / M1INT / BLE 1 / 镜头 1.1`. */
    val summary: String
        get() = buildString {
            append(bodyFirmware.ifEmpty { "?" })
            append(" / ")
            append(regionMarker.ifEmpty { "区域未知" })
            append(" / BLE ")
            append(protocolVersion)
            if (lensFirmware.isNotEmpty()) {
                append(" / 镜头 ")
                append(lensFirmware)
            }
        }

    override fun toString(): String =
        "CameraIdentity(protocol=$protocolVersion, body=$bodyFirmware, region=$regionMarker, " +
            "lens=$lensFirmware, china=$isChinaVariant, global=$isGlobalVariant)"
}

/** Result of the pairing notification (`41106da7`). */
sealed interface PairingNotification {

    /** Non-empty payload: this is the session token. */
    data class Token(val token: String) : PairingNotification

    /**
     * Empty payload: the camera **rejected / ignored** the pairing request.
     * The official app shows `已忽略相机端蓝牙配对` and asks the user to confirm
     * on the camera screen (`请查看相机屏幕提示`).
     */
    data object Rejected : PairingNotification
}

/**
 * Camera Wi-Fi credentials read from `41106da6`.
 *
 * [wifiEnabled] / [timeSynced] are filled in by [BleProvisioner.provision]:
 * `null` = the optional step was not requested, `false` = requested but the
 * camera refused it (credentials are still valid), `true` = write accepted.
 */
data class WifiCredentials(
    val ssid: String,
    val password: String,
    val cameraIdentity: CameraIdentity? = null,
    val wifiEnabled: Boolean? = null,
    val timeSynced: Boolean = false,
) {

    /** `true` when both fields are non-empty (always the case for parsed credentials). */
    val isValid: Boolean get() = ssid.isNotEmpty() && password.isNotEmpty()

    /** Password masked for logs/UI snapshots — never log the real password. */
    val maskedPassword: String get() = "*".repeat(password.length.coerceIn(1, 8))

    override fun toString(): String =
        "WifiCredentials(ssid=$ssid, password=$maskedPassword, camera=${cameraIdentity?.raw ?: "-"}, " +
            "wifiEnabled=$wifiEnabled, timeSynced=$timeSynced)"
}

/** Lens information carried by the optional `41106dae` notification. */
data class LensInfo(
    val lensType: String,
    val lensVersion: String,
)

/**
 * Camera-state event carried by the optional `41106daf` notification:
 * `"ac,<code>"` where the official app treats codes 3/7/8/9 as state changes
 * (disconnect / album / viewfinder transitions). The exact UI meaning is not
 * needed for provisioning, so the code is exposed verbatim.
 */
data class CameraStateNotification(
    val stateCode: String,
) {
    /** `true` for the code the official app handles as "camera disconnected". */
    val isDisconnectLike: Boolean get() = stateCode == "3"
}

/** Which GATT write flavour to use. Mirrors `BluetoothGattCharacteristic` write types. */
enum class BleWriteKind {
    /** `BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE` — fire-and-forget. */
    NO_RESPONSE,

    /** `BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT` — acknowledged. */
    WITH_RESPONSE,
    ;

    /** Chinese label for diagnostics/UI. */
    val label: String
        get() = when (this) {
            NO_RESPONSE -> "无响应写入"
            WITH_RESPONSE -> "带响应写入"
        }
}

/**
 * Runtime write-type negotiation.
 *
 * The camera's characteristics **declare only `write`**, not
 * `write_no_response` — but on Android a with-response write to the pairing /
 * session characteristics is answered with the camera's application-level error
 * `0x80`, while the write-without-response form is accepted. Windows stacks do
 * not police the declared property; Android can, so the type must be chosen from
 * the real `properties` bitmap at runtime, and the other flavour tried if the
 * first one fails.
 *
 * The Android constants are duplicated here as plain ints so this decision logic
 * is unit-testable without `android.jar`; they are stable framework values.
 */
object BleWriteNegotiation {

    /** `BluetoothGattCharacteristic.PROPERTY_WRITE`. */
    const val PROPERTY_WRITE = 0x08

    /** `BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE`. */
    const val PROPERTY_WRITE_NO_RESPONSE = 0x04

    /** The flavour the characteristic actually advertises, or `null` if neither. */
    fun advertisedKind(properties: Int): BleWriteKind? = when {
        properties and PROPERTY_WRITE_NO_RESPONSE != 0 -> BleWriteKind.NO_RESPONSE
        properties and PROPERTY_WRITE != 0 -> BleWriteKind.WITH_RESPONSE
        else -> null
    }

    /**
     * Ordered write attempts for one characteristic.
     *
     * 1. If a flavour already worked for this characteristic this session, use it first.
     * 2. Otherwise prefer the advertised flavour, and prefer
     *    no-response when the camera advertises both (the firmware is friendlier to it).
     * 3. Always offer the other flavour as fallback — even when it is not
     *    advertised, because the camera is known to reject the acknowledged form
     *    on some paths, and Android's framework does not enforce the property
     *    itself (the Flutter plugin that documented the pitfall did the check in
     *    Dart before touching the radio).
     */
    fun plan(properties: Int, learned: BleWriteKind? = null): List<BleWriteKind> {
        val advertised = advertisedKind(properties)
        val first = learned ?: advertised ?: BleWriteKind.WITH_RESPONSE
        val second = if (first == BleWriteKind.NO_RESPONSE) {
            BleWriteKind.WITH_RESPONSE
        } else {
            BleWriteKind.NO_RESPONSE
        }
        return listOf(first, second)
    }
}

/**
 * Encoders/parsers for the BLE provisioning handshake. See the class docs of
 * [BleUuids] for sources.
 */
object BleProtocol {

    /** BLE protocol revision this app implements (the official app rejects `> 1`). */
    const val SUPPORTED_PROTOCOL_VERSION = 1

    /** Pairing key is a plain integer in this inclusive range. */
    const val PAIRING_KEY_MIN = 0
    const val PAIRING_KEY_MAX = 99998

    /** Client name the camera expects in the pairing request. */
    const val CLIENT_NAME = "android"

    /** Inclusive validity check for a pairing key. */
    fun isValidPairingKey(key: Int): Boolean = key in PAIRING_KEY_MIN..PAIRING_KEY_MAX

    /**
     * Random pairing key in `0..99998` inclusive — the same range the official app
     * uses (`new Random().nextInt(99999)`).
     */
    fun generatePairingKey(random: Random = Random.Default): Int =
        random.nextInt(PAIRING_KEY_MAX + 1)

    /**
     * Payload for `41106da0`: `"<protocol>,<key>,android"`, e.g. `"1,12345,android"`.
     * The camera pops a confirmation dialog when this arrives.
     */
    fun buildPairingRequest(
        protocolVersion: Int = SUPPORTED_PROTOCOL_VERSION,
        key: Int,
        clientName: String = CLIENT_NAME,
    ): String = "$protocolVersion,$key,$clientName"

    /**
     * Payload for `41106da4`: `"<protocol>,<key>,<crc32>"` where the checksum is
     * the zlib CRC-32 of ASCII `"1" + key + token`. This is a plain checksum, not
     * a MAC — it proves nothing, it only satisfies the camera's parser.
     */
    fun buildSessionRequest(
        protocolVersion: Int = SUPPORTED_PROTOCOL_VERSION,
        key: Int,
        token: String,
    ): String {
        val material = "1$key$token"
        return "$protocolVersion,$key,${ZlibCrc32.ofAscii(material)}"
    }

    /** Payload for `41106da5`: opens (`"ON"`) or closes (`"OFF"`) the camera Wi-Fi. */
    fun buildWifiToggle(on: Boolean): String = if (on) "ON" else "OFF"

    /**
     * Payload for the **standalone** `41106dac` write: ASCII Unix epoch **seconds**.
     *
     * This is the plain-UTC convention described by the hardware-verified public
     * notes. See [buildCameraClockPayload] for the convention the official app
     * actually used (local wall clock), which is the default in [BleProvisioner].
     */
    fun buildTimeSyncPayload(epochSeconds: Long): String = epochSeconds.toString()

    /** Convenience overload: converts epoch milliseconds to plain epoch seconds first. */
    fun buildTimeSyncPayloadForEpochMillis(epochMillis: Long): String =
        buildTimeSyncPayload(epochMillis / 1000L)

    /**
     * Plain-UTC time-sync payload for "now".
     *
     * The official app does **not** send this value; it sends
     * [buildCameraClockPayloadNow] (wall clock as if it were UTC). Kept as the
     * "plain epoch" convention because the public hardware notes describe it
     * (`research/verified/ble-pairing.md`); the two differ by the device's
     * UTC offset, so pick one and verify on hardware before trusting photo dates.
     */
    fun buildTimeSyncPayloadNow(nowMillis: Long = System.currentTimeMillis()): String =
        buildTimeSyncPayloadForEpochMillis(nowMillis)

    /**
     * Payload for `41106dac` using the **official app's convention**: local wall
     * clock expressed as epoch seconds, i.e. `(nowMillis + zoneOffsetMillis) / 1000`.
     *
     * Evidence: the official app's time base is
     * `Calendar.getInstance().add(MILLISECOND, ZONE_OFFSET + DST_OFFSET)`
     * (`$SRC/b/p.java:77-81`, used by `$SRC/common/d.java:1030`), i.e. it adds the
     * zone/DST offset to the current instant before rendering the decimal string.
     * Rationale: the camera has no timezone concept — the firmware `strtol`s the
     * decimal and splits it into six calendar fields — so sending local wall time
     * makes the camera's clock/EXIF display local time. The community hardware
     * notes claim plain epoch; that discrepancy is flagged as unverified
     * (`analysis/apk-re/OPEN-QUESTIONS.md` Q26).
     *
     * @param zoneOffsetMillis zone offset at [nowMillis] in milliseconds east of UTC
     *        (e.g. `+8h` in China). Defaults to the device's current offset.
     */
    fun buildCameraClockPayload(
        nowMillis: Long,
        zoneOffsetMillis: Int,
    ): String = buildTimeSyncPayload((nowMillis + zoneOffsetMillis) / 1000L)

    /** [buildCameraClockPayload] for "now" in the device's current timezone. */
    fun buildCameraClockPayloadNow(
        nowMillis: Long = System.currentTimeMillis(),
        zoneOffsetMillis: Int = TimeZone.getDefault().getOffset(nowMillis),
    ): String = buildCameraClockPayload(nowMillis, zoneOffsetMillis)

    /** Decodes raw characteristic bytes as trimmed, NUL-padded ASCII. */
    fun trimAscii(raw: ByteArray): String =
        String(raw, Charsets.US_ASCII).replace("\u0000", "").trim()

    /** Encodes an ASCII command payload for a characteristic write. */
    fun asciiPayload(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    /**
     * Parses the `41106da2` payload, e.g. `"1,M1,M1INT,1.1"`.
     *
     * Returns `null` when the payload is unusable (empty, or fewer than three
     * comma-separated fields): such a camera cannot be provisioned safely.
     */
    fun parseFirmwareInfo(raw: ByteArray): CameraIdentity? = parseFirmwareInfo(trimAscii(raw))

    /** Text overload of [parseFirmwareInfo] (already-trimmed payload). */
    fun parseFirmwareInfo(text: String): CameraIdentity? {
        val trimmed = text.replace('\u0000', ' ').trim()
        if (trimmed.isEmpty()) return null
        val fields = trimmed.split(',').map { it.trim() }
        if (fields.size < 3) return null
        val protocolVersion = fields[0].toIntOrNull() ?: return null
        val bodyFirmware = fields[1]
        val tail = fields.drop(2)
        val regionIndex = tail.indexOfFirst { looksLikeRegionMarker(it) }
        val regionMarker = if (regionIndex >= 0) tail[regionIndex] else tail.first()
        val lensFirmware = tail.lastOrNull { it != regionMarker && it.isNotEmpty() }.orEmpty()
        return CameraIdentity(
            protocolVersion = protocolVersion,
            bodyFirmware = bodyFirmware,
            regionMarker = regionMarker,
            lensFirmware = lensFirmware,
            rawFields = fields,
            raw = trimmed,
        )
    }

    /**
     * `"ac,<code>"` — [CameraStateNotification], or null when the payload has
     * another shape (firmware versions differ).
     */
    fun parseCameraStateNotification(raw: ByteArray): CameraStateNotification? {
        val fields = trimAscii(raw).split(',').map { it.trim() }
        if (fields.size < 2 || fields[0] != "ac" || fields[1].isEmpty()) return null
        return CameraStateNotification(stateCode = fields[1])
    }

    /**
     * `"3,<...>,<lensType>,<lensVersion>"` — [LensInfo], or null when the payload
     * is not a lens notification.
     */
    fun parseLensNotification(raw: ByteArray): LensInfo? {
        val fields = trimAscii(raw).split(',').map { it.trim() }
        if (fields.size != 4 || fields[0] != "3") return null
        return LensInfo(lensType = fields[3], lensVersion = fields[2])
    }

    /**
     * Parses the `41106da7` pairing notification.
     *
     * NUL padding and whitespace are stripped; **an empty result means the camera
     * rejected the pairing** (official app error code 110 → `已忽略相机端蓝牙配对`).
     */
    fun parsePairingResult(raw: ByteArray): PairingNotification =
        parsePairingResult(trimAscii(raw))

    /** Text overload of [parsePairingResult]. */
    fun parsePairingResult(text: String): PairingNotification {
        val token = text.replace('\u0000', ' ').trim()
        return if (token.isEmpty()) PairingNotification.Rejected else PairingNotification.Token(token)
    }

    /**
     * Parses `41106da6` credentials: `<ssid>,<password>`.
     *
     * The official app splits on `,` and takes the first two fields; this parser
     * does the same but tolerates commas inside the password by splitting only on
     * the first one. Returns null when either side is empty or the separator is
     * missing (e.g. the camera answered with an error string instead).
     */
    fun parseWifiCredentials(raw: ByteArray): WifiCredentials? =
        parseWifiCredentials(trimAscii(raw))

    /** Text overload of [parseWifiCredentials]. */
    fun parseWifiCredentials(text: String): WifiCredentials? {
        val cleaned = text.replace('\u0000', ' ').trim()
        val separator = cleaned.indexOf(',')
        if (separator <= 0 || separator >= cleaned.length - 1) return null
        val ssid = cleaned.substring(0, separator).trim()
        val password = cleaned.substring(separator + 1).trim()
        if (ssid.isEmpty() || password.isEmpty()) return null
        return WifiCredentials(ssid = ssid, password = password)
    }

    /**
     * Scanned-name heuristic used by [BleProvisioner.findCameras]: the camera
     * advertises as `YI_M1_<serial>` (verified hardware name) or `M1*`.
     */
    fun isCameraName(name: String?): Boolean {
        val upper = name?.trim()?.uppercase() ?: return false
        if (upper.isEmpty()) return false
        return upper.startsWith("YI_M1_") || upper.startsWith("M1")
    }

    /** Region token heuristic: `M1`, `M1CN`, `M1INT`, `M1EU`, … */
    private fun looksLikeRegionMarker(field: String): Boolean {
        if (field.isEmpty()) return false
        val upper = field.uppercase()
        if (!upper.startsWith("M1")) return false
        if (upper.length > 6) return false
        return upper.all { it.isUpperCase() || it.isDigit() }
    }
}
