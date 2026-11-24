package com.yim1remote.app.data

import com.yim1remote.app.data.model.CameraFileInfo
import com.yim1remote.app.data.model.CameraFileType
import com.yim1remote.app.data.model.CameraStatus
import com.yim1remote.app.data.model.ExposureMode
import com.yim1remote.app.data.model.GalleryItem
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Typed error for a camera response whose envelope `code` is not 200/20000.
 *
 * The camera reports application errors inside an HTTP 200 body, e.g.
 * `{"code":1506,"data":null}` (no such file). [code] carries the camera code and
 * [cameraMessage] the raw `data` string if the camera supplied one.
 */
class CameraApiException(
    val code: Int,
    val cameraMessage: String? = null,
    message: String = buildCameraErrorMessage(code, cameraMessage),
) : Exception(message) {
    private companion object {
        fun buildCameraErrorMessage(code: Int, cameraMessage: String?): String {
            val detail = cameraMessage?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            return "相机返回错误码 $code$detail"
        }
    }
}

/** Malformed/unexpected camera payload (not JSON, missing envelope, broken array, ...). */
class CameraProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One parsed `{code,data}` response envelope. */
data class CameraEnvelope(
    val code: Int,
    /** Raw JSON of the `data` field (`null` when absent/JSON-null). */
    val data: String?,
    val rawBody: String,
) {
    val isSuccess: Boolean get() = CameraProtocol.isSuccessCode(code)
    val dataOrNull: String? get() = data?.takeIf { it.isNotBlank() && it != "null" }
}

/** Camera pixel coordinates for `RCDoFocus`. */
data class FocusPoint(val x: Int, val y: Int)

/**
 * The small health object returned by `GetCameraStatus`.
 *
 * Verified on firmware 3.1-cn: exactly four string fields
 * (`research/verified/settings-and-status.md` §1). Settings are **not** part of
 * this payload — they come from the live-view JSON state block. All fields are
 * optional so other firmware revisions cannot break the connection probe.
 */
data class CameraHealth(
    val batteryPercent: Int? = null,
    val remainingShots: Int? = null,
    val lensVersion: String? = null,
    val lensType: String? = null,
    val firmwareVersion: String? = null,
    val firmwareDisplay: String? = null,
) {
    /** True for the `"101"` battery value the camera sends while on external power. */
    val isExternalPower: Boolean get() = batteryPercent == CameraHealth.EXTERNAL_POWER_BATTERY

    companion object {
        /** `batteryLevel == "101"` means external power / charging, not 101 %. */
        const val EXTERNAL_POWER_BATTERY = 101
    }
}

/**
 * Pure protocol logic for the YI M1 local API: response envelope, camera status
 * mapping, gallery parsing, URL construction, focus mapping and timeouts.
 *
 * No Android classes are used here on purpose — everything in this object is
 * covered by plain JVM unit tests.
 *
 * Sources: `analysis/jadx-out/sources/com/xiaoyi/mirrorlesscamera/`
 *  * `http/a/{a,b,c,d}.java` — envelope handling (`optInt("code")`, success 200/20000);
 *  * `common/t.java` — live-view parameter mapping (`t.a(CameraParamBean)`);
 *  * `common/e.java` — `GetFileList` items + `GetFile` URL shape + code 1506;
 *  * `http/camera/b.java` — timeouts (`RCDoShooting`, 20 s × (exposure seconds + 1)).
 */
object CameraProtocol {

    // Camera envelope codes (from the official callbacks).
    const val CODE_SUCCESS = 200
    const val CODE_SUCCESS_ALT = 20000

    /** `GetFile` thumbnail/mid-thumbnail unavailable (video files have none). */
    const val CODE_NO_THUMBNAIL = 204

    /** `GetFile`/`GetFileList` — file (or the SD card) has no matching entry. */
    const val CODE_FILE_NOT_FOUND = 1506

    /** Another camera request is in flight; serialize and retry (`rc only one`). */
    const val CODE_BUSY = 1515

    /** `GetFileList` rejected a zero-width/absent range (`get filelist err`). */
    const val CODE_BAD_FILE_RANGE = 1502

    /** Standard control command timeout; matches the official app's 10 s socket timeouts. */
    const val CONTROL_TIMEOUT_MS = 10_000

    /** Upload/download/status timeouts used by the official app (30 s). */
    const val TRANSFER_TIMEOUT_MS = 30_000

    /** `RCDoShooting` minimum timeout (20 s in the official app). */
    const val CAPTURE_MIN_TIMEOUT_MS = 20_000L

    /** Our hard ceiling for one shutter release; UI can cancel earlier. */
    const val CAPTURE_MAX_TIMEOUT_MS = 90_000L

    /**
     * `GetFile` copies `path` into a 50-byte firmware buffer; longer paths can
     * never be fetched (verified in `research/verified/gallery-and-download.md` §4).
     */
    const val MAX_GET_FILE_PATH_LENGTH = 50

    /** `DeleteFile` uses a 56-byte stride, i.e. ~52 usable path characters. */
    const val MAX_DELETE_PATH_LENGTH = 52

    /** Some firmware nests the `GetFileList` array inside the data object. */
    private val NESTED_FILE_LIST_KEYS = listOf("file_list", "files", "list")

    fun isSuccessCode(code: Int): Boolean = code == CODE_SUCCESS || code == CODE_SUCCESS_ALT

    // ------------------------------------------------------------------ envelope

    /**
     * Parses `{"code":...,"data":...}`.
     *
     * @throws CameraProtocolException when the body is empty/not a JSON object or
     *         has no numeric `code` — the caller maps this to a connection error.
     */
    fun parseEnvelope(body: String): CameraEnvelope {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw CameraProtocolException("相机返回了空响应")
        val json = try {
            JSONObject(trimmed)
        } catch (e: JSONException) {
            throw CameraProtocolException(
                "相机响应不是合法的 JSON：${trimmed.take(160)}",
                e,
            )
        }
        if (!json.has("code")) {
            throw CameraProtocolException("相机响应缺少 code 字段：${trimmed.take(160)}")
        }
        val code = json.optInt("code", -1)
        val data = when {
            !json.has("data") || json.isNull("data") -> null
            // JSONObject/JSONArray.toString() re-serializes compactly; plain strings stay raw.
            else -> json.opt("data")?.toString()
        }
        return CameraEnvelope(code, data, body)
    }

    /**
     * Parses the envelope and throws [CameraApiException] unless code is 200/20000.
     */
    fun requireSuccess(body: String): CameraEnvelope = requireSuccess(parseEnvelope(body))

    /** Throws [CameraApiException] unless the envelope reports success. */
    fun requireSuccess(envelope: CameraEnvelope): CameraEnvelope {
        if (!envelope.isSuccess) {
            throw CameraApiException(envelope.code, envelope.dataOrNull)
        }
        return envelope
    }

    // ----------------------------------------------------------------------- url

    /**
     * Builds `GET <baseUrl>/?data=<encode(commandJson)>`.
     *
     * The official app appended compact JSON raw to the query string and let
     * OkHttp percent-encode it; `HttpURLConnection` does not do that for us, so
     * encode with UTF-8 exactly like the official `GetFile` URLs did
     * (`URLEncoder.encode(json, "UTF-8")` in `common/e.java`).
     */
    fun buildUrl(baseUrl: String, commandJson: String): String {
        val base = baseUrl.trim().trimEnd('/')
        // Android's org.json JSONStringer escapes every '/' inside a string as '\/'
        // (the desktop org.json artifact used by unit tests does not). The camera
        // firmware does NOT unescape that, so a file path would arrive as
        // "\/DCIM\/..." and GetFile answers 204 (verified on hardware, 2026-11-23).
        // Our JSON never legitimately contains the two-character sequence '\' '/'.
        val sanitized = commandJson.replace("\\/", "/")
        return "$base/?data=" + URLEncoder.encode(sanitized, StandardCharsets.UTF_8.name())
    }

    // -------------------------------------------------------------------- status

    /**
     * Maps a `GetCameraStatus` response object or a live-view 2048-byte parameter
     * block into [CameraStatus].
     *
     * Fully defensive: a 2026 capture shows some firmware returns only
     * `{"batteryLevel","SurplusPhotoCnts","lenVer","lenType"}`, so every field is
     * optional and missing keys keep the model defaults. Both the rich
     * `CameraParamBean` field names (`BatteryLevel`) and the short status names
     * (`batteryLevel`, `lenVer`, `lenType`) are accepted, case-insensitively.
     */
    fun parseStatus(jsonText: String?): CameraStatus {
        if (jsonText.isNullOrBlank()) return CameraStatus(connected = true)
        val obj = try {
            JSONObject(jsonText.trim())
        } catch (e: JSONException) {
            return CameraStatus(connected = true)
        }
        return parseStatus(obj)
    }

    fun parseStatus(json: JSONObject): CameraStatus {
        return try {
            buildStatus(json)
        } catch (e: Exception) {
            // A damaged live-view param block must never crash a frame.
            CameraStatus(connected = true)
        }
    }

    private fun buildStatus(json: JSONObject): CameraStatus {
        val view = ActiveJsonView(json)
        val imageQuality = view.string("ImageQuality") ?: "16"
        val rawAspect = view.string("ImageAspect") ?: "4:3"
        // Official app pins 4:3 when the sensor is at 50 MP (common/t.java).
        val imageAspect = if (imageQuality == "50") "4:3" else rawAspect
        return CameraStatus(
            connected = true,
            batteryPercent = view.int("BatteryLevel", "BatteryLevelPercent", "Battery"),
            remainingShots = view.int("SurplusPhotoCnts", "SurplusPhotoCounts", "RemainingShots"),
            firmwareVersion = view.string(
                "FirmwareVersion", "firmwareVersion", "Firmware", "FWVersion",
            ),
            firmwareDisplay = view.string(
                "FirmwareVersionDisplay", "FirmwareVersion4Display", "firmwareDisplay",
            ),
            lensType = view.string("LensType", "lenType", "LensName"),
            lensVersion = view.string("LensVersion", "lenVer"),
            lensStatus = view.string("LensStatus", "lenStatus"),
            focusSupport = view.boolean("FocusSupport") ?: false,
            shooting = view.boolean("Shooting") ?: false,
            recording = view.boolean("Recording") ?: false,
            exposureMode = ExposureMode.fromWire(view.string("ExposureMode")),
            meteringMode = view.string("MeteringMode") ?: "Multi",
            focusMode = view.string("FocusMode") ?: "S-AF",
            imageQuality = imageQuality,
            imageAspect = imageAspect,
            fileFormat = view.string("FileFormat") ?: "JPG-M",
            driveMode = view.string("DriveMode") ?: "Single",
            fnumber = view.string("Fnumber", "FNumber") ?: "4.5",
            fnumberMin = view.string("FnumberMin") ?: "1.0",
            fnumberMax = view.string("FnumberMax") ?: "32",
            shutterSpeed = view.string("ShutterSpeed") ?: "1/15s",
            // Some firmware prints the EV echo with a leading '+' (`+1.0`); the
            // option table / setter wire value never has one.
            ev = CameraJson.normalizeEv(view.string("EV") ?: "0.0"),
            iso = view.string("ISOSetting", "ISO") ?: "Auto",
            isoAutoValue = view.string("ISOAutoValue") ?: "",
            whiteBalance = view.string("WB") ?: "Auto",
            colorMode = view.string("ColorMode") ?: "Standard",
            delayShootCount = view.int("DelayShootCnt", "DelayShootCount") ?: 0,
            mfPosition = view.int("MFPosition", "MfPosition", "MFAdjust"),
        )
    }

    /**
     * Parses the small `GetCameraStatus` health object (battery / remaining shots
     * / lens). The rich 26-key state block is parsed by [parseStatus]; this
     * function never fails and never invents settings.
     */
    fun parseHealthStatus(jsonText: String?): CameraHealth {
        if (jsonText.isNullOrBlank()) return CameraHealth()
        val json = try {
            JSONObject(jsonText.trim())
        } catch (e: JSONException) {
            return CameraHealth()
        }
        val view = ActiveJsonView(json)
        return CameraHealth(
            batteryPercent = view.int("batteryLevel", "BatteryLevel", "Battery"),
            remainingShots = view.int("SurplusPhotoCnts", "RemainingShots"),
            lensVersion = view.string("lenVer", "LensVersion"),
            lensType = view.string("lenType", "LensType", "LensName"),
            firmwareVersion = view.string("FirmwareVersion", "firmwareVersion", "Firmware"),
            firmwareDisplay = view.string(
                "FirmwareVersionDisplay", "FirmwareVersion4Display", "firmwareDisplay",
            ),
        )
    }

    /**
     * Merges a [CameraHealth] probe into a full [CameraStatus] without touching
     * the settings that only the live-view state block reports.
     */
    fun mergeHealth(status: CameraStatus?, health: CameraHealth): CameraStatus {
        val base = status?.copy(connected = true) ?: CameraStatus(connected = true)
        return base.copy(
            batteryPercent = health.batteryPercent ?: base.batteryPercent,
            remainingShots = health.remainingShots ?: base.remainingShots,
            lensVersion = health.lensVersion ?: base.lensVersion,
            lensType = health.lensType ?: base.lensType,
            firmwareVersion = health.firmwareVersion ?: base.firmwareVersion,
            firmwareDisplay = health.firmwareDisplay ?: base.firmwareDisplay,
        )
    }

    // ------------------------------------------------------------------- gallery

    /**
     * Parses one `GetFileList` HTTP body.
     *
     * Success body: `{"code":200,"data":[{"path":…,"date":"<unix seconds>",
     * "filetype":"picture|video|raw|rawJpeg|panorama","protectStatus":false}]}`.
     * `code:1506` ("no pic anymore err") means past the end of the album and is
     * treated as an empty page, exactly like the official app.
     */
    fun parseFileListResponse(body: String): List<GalleryItem> {
        val envelope = parseEnvelope(body)
        if (envelope.code == CODE_FILE_NOT_FOUND) return emptyList()
        requireSuccess(envelope)
        return parseGalleryItems(envelope.data)
    }

    /**
     * Parses the `data` value of a `GetFileList` response (already unwrapped).
     *
     * The array is normally the value of `data` itself; some firmware revisions
     * nest it under `file_list` / `files` / `list`, so that is tolerated too.
     */
    fun parseGalleryItems(dataJson: String?): List<GalleryItem> {
        val raw = dataJson?.trim()
        if (raw.isNullOrEmpty() || raw == "null") return emptyList()
        val array = extractFileArray(raw)
        val items = ArrayList<GalleryItem>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            parseGalleryItem(obj)?.let(items::add)
        }
        // The camera normally lists newest first; sort defensively by capture time.
        return items.sortedByDescending { it.capturedAtEpochMillis }
    }

    private fun extractFileArray(raw: String): JSONArray {
        if (raw.startsWith("[")) {
            return try {
                JSONArray(raw)
            } catch (e: JSONException) {
                throw CameraProtocolException("GetFileList 的 data 不是数组：${raw.take(160)}", e)
            }
        }
        if (raw.startsWith("{")) {
            val obj = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                throw CameraProtocolException("GetFileList 的 data 不是 JSON：${raw.take(160)}", e)
            }
            for (key in NESTED_FILE_LIST_KEYS) {
                val nested = obj.optJSONArray(key)
                if (nested != null) return nested
            }
        }
        throw CameraProtocolException("GetFileList 的 data 不是数组：${raw.take(160)}")
    }

    /**
     * Parses one gallery entry.
     *
     * `date` stays a raw string (Unix **seconds**) because `GetFile` must echo the
     * exact value it received; `protectStatus` is a JSON boolean on the verified
     * firmware and a string on some others, so both are accepted.
     */
    fun parseGalleryItem(json: JSONObject): GalleryItem? {
        val view = ActiveJsonView(json)
        val path = view.string("path", "Path") ?: return null
        if (path.isBlank()) return null
        return GalleryItem(
            path = path,
            dateWire = view.string("date", "Date") ?: "",
            isProtected = view.boolean("protectStatus", "isProtected", "protected") ?: false,
            fileType = CameraFileType.fromWire(view.string("filetype", "fileType")),
        )
    }

    /** Epoch seconds tolerant parsing: empty/garbage strings become 0. */
    fun parseEpochSeconds(raw: String?): Long = raw?.trim()?.toLongOrNull() ?: 0L

    // --------------------------------------------------------------------- focus

    /**
     * Maps a normalized point inside the live-view picture (0..1, 0..1) to the
     * camera's `RCDoFocus` pixel space, exactly as the official `FocusView` did:
     *
     *  * 4:3 / 1:1 (and unknown aspect): `x' = round(nx*640 + 40)`, `y' = round(ny*640)`
     *  * 3:2 / 16:9:                    `x' = round(nx*720)`,     `y' = round(ny*720 - 30)`
     *
     * Coordinates are clamped to the normalized range first; the formulas are
     * applied unclamped afterwards because the official app sent e.g. `-30` for
     * a tap at the very top of a 16:9 frame and the camera clamps itself.
     */
    fun focusPoint(normalizedX: Float, normalizedY: Float, imageAspect: String?): FocusPoint {
        val nx = normalizedX.coerceIn(0f, 1f)
        val ny = normalizedY.coerceIn(0f, 1f)
        return when (imageAspect) {
            "3:2", "16:9" -> FocusPoint(
                x = (nx * 720f).roundToInt(),
                y = (ny * 720f - 30f).roundToInt(),
            )
            else -> FocusPoint(
                x = (nx * 640f + 40f).roundToInt(),
                y = (ny * 640f).roundToInt(),
            )
        }
    }

    /** Extracts the `Posx`/`Posy` the camera returns after `RCDoFocus` (auto area). */
    fun parseFocusPoint(dataJson: String?): FocusPoint? {
        val raw = dataJson?.trim()
        if (raw.isNullOrEmpty() || raw == "null") return null
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return null
        }
        val view = ActiveJsonView(json)
        val x = view.string("Posx", "posx", "x")?.trim()?.toIntOrNull() ?: return null
        val y = view.string("Posy", "posy", "y")?.trim()?.toIntOrNull() ?: return null
        return FocusPoint(x, y)
    }

    /** Inverse of [focusPoint]: camera pixel space back to 0..1 image coordinates. */
    fun focusPointToNormalized(point: FocusPoint, imageAspect: String?): Pair<Float, Float> =
        when (imageAspect) {
            "3:2", "16:9" -> Pair(
                (point.x / 720f).coerceIn(0f, 1f),
                ((point.y + 30f) / 720f).coerceIn(0f, 1f),
            )
            else -> Pair(
                ((point.x - 40f) / 640f).coerceIn(0f, 1f),
                (point.y / 640f).coerceIn(0f, 1f),
            )
        }

    // ------------------------------------------------------------------- capture

    /**
     * Timeout for one `RCDoShooting` HTTP call.
     *
     * Official policy (`http/camera/b.java`): `20 s` normally, or
     * `20 s × (longExposureSeconds + 1)` while the shutter speed is a whole-second
     * exposure (`"2s"` → 2 s), divided by 10 for exposures ≥ 10 s. The app's
     * drive-mode delay (2 s / 10 s) also keeps the request open. We keep the
     * official formula, add the pending delay and cap everything at
     * [CAPTURE_MAX_TIMEOUT_MS] (90 s) so a hung camera cannot block the UI
     * forever; `cancelCapture()` is available meanwhile.
     */
    fun captureTimeoutMillis(status: CameraStatus?): Long {
        if (status == null) return CAPTURE_MIN_TIMEOUT_MS
        var timeout = CAPTURE_MIN_TIMEOUT_MS
        val exposureSeconds = longExposureSeconds(status.shutterSpeed)
        if (exposureSeconds >= 1) {
            timeout = 20_000L * (exposureSeconds + 1)
            if (exposureSeconds >= 10) timeout /= 10
        }
        val delaySeconds = when (status.driveMode) {
            "2SDelay" -> 2L
            "10SDelay" -> 10L
            else -> 0L
        }
        timeout += delaySeconds * 1000L
        // A delay shot count (interval shooting) only starts after the shutter
        // release; it does not extend this request in the official app.
        return timeout.coerceIn(CAPTURE_MIN_TIMEOUT_MS, CAPTURE_MAX_TIMEOUT_MS)
    }

    /**
     * Whole-second exposure length encoded in a shutter-speed wire value.
     *
     * The verified wire vocabulary is the display form (`"2s"`, `"1.3s"`,
     * `"30s"`, `"BULB"`, `"1/125s"`). `TIME`/`BULB` and fractions (`"1/125s"`)
     * return 0. The legacy enum form with a trailing quote (`"2\""`) is accepted
     * for robustness with old captures.
     */
    fun longExposureSeconds(shutterSpeedWire: String?): Long {
        val text = shutterSpeedWire?.trim() ?: return 0L
        if (text.isEmpty()) return 0L
        if (text.equals("TIME", true) || text.equals("BULB", true)) return 0L
        if (text.contains('/')) return 0L
        return when {
            text.endsWith("s", ignoreCase = true) ->
                text.dropLast(1).toDoubleOrNull()?.toLong() ?: 0L
            text.endsWith("\"") ->
                text.dropLast(1).toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }
    }

    // ---------------------------------------------------------------- file info

    /**
     * Parses a `GetFileInfo` response envelope into display metadata.
     *
     * Returns null when the camera reports an error (e.g. `code 1000`,
     * "no such file") or when `data` is not a JSON object. Never throws: file
     * metadata is cosmetic, a failure must not break the gallery/viewer.
     */
    fun parseFileInfoResponse(body: String): CameraFileInfo? {
        val envelope = try {
            parseEnvelope(body)
        } catch (e: CameraProtocolException) {
            return null
        }
        if (!isSuccessCode(envelope.code)) return null
        return parseFileInfo(envelope.data)
    }

    /**
     * Parses the `data` object of a `GetFileInfo` reply (already unwrapped JSON).
     * Field names are matched case-insensitively with the verified aliases.
     */
    fun parseFileInfo(dataJson: String?): CameraFileInfo? {
        val raw = dataJson?.trim()
        if (raw.isNullOrEmpty() || raw == "null") return null
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return null
        }
        val view = ActiveJsonView(json)
        return CameraFileInfo(
            type = view.string("type"),
            sizeBytes = view.string("size", "fileSize", "file_size")?.toLongOrNull(),
            width = view.int("width"),
            height = view.int("height"),
            captureDateSeconds = view.string("capture_date", "captureDate")?.toLongOrNull(),
            shutterSpeed = view.string("shutter_speed", "shutterSpeed"),
            aperture = view.string("FNumber", "fnumber", "aperture"),
            iso = view.string("ISO", "iso"),
            whiteBalance = view.string("WB", "wb", "whiteBalance"),
            focalLengthMm = view.string("FocalLength", "focalLength", "focal_length"),
            make = view.string("Make", "make"),
            model = view.string("model"),
            focusMode = view.string("focus_mode", "focusMode"),
            durationSeconds = parseDurationSeconds(
                view.string("duration", "video_duration", "videoDuration", "length"),
            ),
        ).takeUnless { it.isEmpty }
    }

    /** `"12"` / `"12s"` / `"00:12"` / `"1:02:03"` → seconds; null when unparseable. */
    fun parseDurationSeconds(raw: String?): Long? {
        val text = raw?.trim()?.removeSuffix("s")?.removeSuffix("S") ?: return null
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it }
        text.toDoubleOrNull()?.let { return it.toLong() }
        val parts = text.split(':')
        if (parts.size !in 2..3) return null
        var total = 0L
        for (part in parts) {
            val value = part.trim().toLongOrNull() ?: return null
            total = total * 60 + value
        }
        return total
    }

    // ------------------------------------------------------------------ firmware

    /**
     * Extracts a major.minor version out of strings like `"3.2"`, `"1,3.1-cn,M1CN"`.
     * Mirrors the official `[\d][.][\d]` regex but tolerates multi-digit parts.
     */
    fun parseFirmwareVersion(text: String?): Double? {
        val raw = text?.trim() ?: return null
        if (raw.isEmpty()) return null
        val match = Regex("\\d+\\.\\d+").find(raw) ?: return null
        return match.value.toDoubleOrNull()
    }

    /**
     * True when the firmware is >= 3.2, i.e. the native multi-packet live-view
     * path. NOTE: the app no longer relies on this for framing selection (BLE is
     * not implemented, so the firmware string may be unavailable); the UDP
     * receiver chooses by datagram size instead. Kept for diagnostics/tests.
     */
    fun isMultiPacketFirmware(firmwareVersion: String?): Boolean =
        (parseFirmwareVersion(firmwareVersion) ?: 0.0) >= 3.2

    // ------------------------------------------------------------------- helpers

    /** Tolerant boolean parser for camera string fields (`"True"`, `"1"`, ...). */
    fun parseBoolean(raw: String?): Boolean? {
        val text = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (text.isEmpty()) return null
        return when (text) {
            "true", "1", "yes", "on", "enable", "enabled", "open", "ok" -> true
            "false", "0", "no", "off", "disable", "disabled", "close", "ng" -> false
            else -> null
        }
    }

    /**
     * Case-insensitive JSON field accessor.
     *
     * The camera mixes conventions between firmware payloads: the rich live-view
     * block uses `BatteryLevel` / `SurplusPhotoCnts` while a sparse
     * `GetCameraStatus` body uses `batteryLevel` / `lenVer`. Normalizing on the
     * lowercase name and passing priority-ordered aliases handles both.
     */
    internal class ActiveJsonView(private val json: JSONObject) {

        private val keysByLowercase: Map<String, String> by lazy {
            val map = HashMap<String, String>(json.length() * 2 + 4)
            val iterator = json.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                val lower = key.lowercase(Locale.ROOT)
                if (!map.containsKey(lower)) map[lower] = key
            }
            map
        }

        fun string(vararg names: String): String? {
            for (name in names) {
                val key = keysByLowercase[name.lowercase(Locale.ROOT)] ?: continue
                if (json.isNull(key)) continue
                val value = json.opt(key) ?: continue
                val text = when (value) {
                    is String -> value
                    else -> value.toString()
                }.trim()
                if (text.isNotEmpty() && text != "null") return text
            }
            return null
        }

        fun int(vararg names: String): Int? {
            val text = string(*names) ?: return null
            return text.toIntOrNull() ?: text.toDoubleOrNull()?.toInt()
        }

        fun boolean(vararg names: String): Boolean? = parseBoolean(string(*names))
    }
}
