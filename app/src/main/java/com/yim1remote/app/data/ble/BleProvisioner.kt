package com.yim1remote.app.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Turns the camera's Wi-Fi on and reads its per-session SSID/password over BLE.
 *
 * ## Why this exists
 *
 * The YI M1 does **not** broadcast Wi-Fi by default, and its hotspot password is
 * random for every session. The only way to learn it is this BLE handshake: the
 * official app did exactly that, and with it gone the camera's Wi-Fi is
 * effectively unusable without re-implementing it.
 *
 * ## Usage
 *
 * ```kotlin
 * val provisioner = BleProvisioner(context)
 * val lifecycleScope = ...
 *
 * // 1. Ask for permissions (see [requiredPermissions]), then scan.
 * val cameras = provisioner.findCameras()          // suspend, IO dispatcher inside
 * val camera = cameras.firstOrNull() ?: return
 *
 * // 2. Pair + read credentials + turn Wi-Fi on.
 * provisioner.provision(camera)
 *     .onSuccess { creds -> joinWifi(creds.ssid, creds.password) }
 *     .onFailure { showError(it) }
 *
 * // Progress for the UI (Chinese labels, e.g. ProvisionState.label):
 * provisioner.state.collect { state -> statusText = state.label }
 * ```
 *
 * Call from any coroutine (it never blocks the caller); every public method is
 * mutually exclusive because Android allows only one GATT session per device and
 * the camera only accepts one client at a time. Every session is disconnected and
 * closed in a `finally`, so a cancelled/failed attempt cannot leak a GATT handle.
 *
 * ## Honest limitations
 *
 * * This protocol is **plaintext and unauthenticated** (integer key 0..99998,
 *   CRC-32 "checksum"). The real authorisation is the physical accept prompt on
 *   the camera screen.
 * * The camera stores **one** pairing: pairing from this app silently invalidates
 *   the official app's pairing (and vice versa).
 * * The handshake has only ever been verified on one hardware unit
 *   (firmware `1,3.1-cn,M1CN,0.0`) via the vendored community implementation, plus
 *   the official APK's code. **This module has not been run against a physical
 *   camera by us** — see `docs/BLE-PROVISIONING.md`.
 */
@SuppressLint("MissingPermission")
class BleProvisioner(
    context: Context,
    /** Dispatcher for scanning/GATT work; IO by default so nothing touches the main thread. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val appContext: Context = context.applicationContext ?: context
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    /**
     * Serializes whole operations: one BLE session at a time, because Android only
     * allows one outstanding GATT operation and the camera only serves one client.
     */
    private val operationMutex = Mutex()

    /** Learned write flavour per characteristic UUID (see [BleWriteNegotiation]). */
    private val writeTypeMemory = ConcurrentHashMap<UUID, BleWriteKind>()

    private val _state = MutableStateFlow<ProvisionState>(ProvisionState.Idle)

    /** Current provisioning progress; safe to collect in Compose via `collectAsState()`. */
    val state: StateFlow<ProvisionState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<String>>(emptyList())

    /**
     * Human-readable log of everything the BLE layer did (write-type choices,
     * GATT statuses, optional-characteristic failures). BLE problems with this
     * camera are almost always environmental, so the UI can expose this behind a
     * "terminal" icon to make them diagnosable.
     */
    val diagnostics: StateFlow<List<String>> = _diagnostics.asStateFlow()

    @Volatile
    private var closed = false

    // ----------------------------------------------------------------- scanning

    /**
     * Scans for YI M1 BLE advertisements and returns the camera candidates.
     *
     * Uses an unfiltered LE scan and matches on the advertised service UUID
     * (`41106dd9-…`) **or** the name heuristic (`YI_M1_*` / `M1*`), because the
     * camera's advertisement payload is not guaranteed to carry the service UUID.
     * Already-bonded cameras are returned immediately (a connected camera often
     * disappears from fresh scans).
     *
     * Returns as soon as the first candidate appears; an empty list means nothing
     * was found before [timeoutMs] elapsed.
     *
     * @throws BleException with [BleError.BluetoothUnsupported], [BleError.BluetoothOff],
     *         [BleError.PermissionDenied] or [BleError.ScanFailed].
     */
    suspend fun findCameras(timeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS): List<BleDeviceInfo> =
        withContext(ioDispatcher) {
            operationMutex.withLock {
                ensureNotClosed()
                ensureAdapterReady()
                _state.value = ProvisionState.Scanning
                log("开始扫描 YI M1 蓝牙（${timeoutMs / 1000} 秒）…")
                val found = try {
                    scanForCameras(timeoutMs)
                } finally {
                    if (_state.value is ProvisionState.Scanning) {
                        _state.value = ProvisionState.Idle
                    }
                }
                if (found.isEmpty()) {
                    log("扫描结束：未发现 YI M1 蓝牙信号")
                } else {
                    log(
                        "扫描结束：发现 ${found.size} 个候选 " +
                            found.joinToString(", ") { it.displayName },
                    )
                }
                found
            }
        }

    /**
     * Connects to [device] and reads only `41106da2` (firmware/region/lens),
     * without starting the pairing handshake. Used by the 连接 screen to show
     * body firmware / region without re-pairing the camera.
     */
    suspend fun readIdentityOnly(device: BleDeviceInfo): Result<CameraIdentity> = guarded {
        val session = openSession(device, DEFAULT_CONNECT_TIMEOUT_MS)
        try {
            session.discoverServices(DEFAULT_STEP_TIMEOUT_MS)
            readIdentity(session, DEFAULT_STEP_TIMEOUT_MS)
        } finally {
            session.close()
        }
    }

    private suspend fun scanForCameras(timeoutMs: Long): List<BleDeviceInfo> {
        val localAdapter = adapter ?: throw BleException(BleError.BluetoothUnsupported)
        val scanner = localAdapter.bluetoothLeScanner
            ?: throw BleException(BleError.BluetoothOff, IllegalStateException("bluetoothLeScanner 不可用"))

        val found = LinkedHashMap<String, BleDeviceInfo>()
        // Bonded cameras first: a previously connected camera may not advertise again.
        runCatching { localAdapter.bondedDevices }
            .getOrNull()
            ?.forEach { device ->
                deviceInfo(device, rssi = null, matchedByServiceUuid = false)?.let {
                    found[it.address] = it
                }
            }
        if (found.isNotEmpty()) {
            log("使用已配对的相机记录：${found.keys.joinToString(", ")}")
            return found.values.toList()
        }

        val scanFailureCode = AtomicReference<Int?>(null)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val record = result.scanRecord
                val matchedByService = record?.serviceUuids?.any { it.uuid == BleUuids.SERVICE } == true
                val info = deviceInfo(device, result.rssi, matchedByService) ?: return
                synchronized(found) { found[device.address] = info }
            }

            override fun onScanFailed(errorCode: Int) {
                scanFailureCode.set(errorCode)
            }
        }

        var scanning = false
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                callback,
            )
            scanning = true
            val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(MIN_SCAN_WINDOW_MS)
            while (SystemClock.elapsedRealtime() < deadline) {
                scanFailureCode.get()?.let { throw BleException(BleError.ScanFailed(it)) }
                if (synchronized(found) { found.isNotEmpty() }) break
                delay(SCAN_POLL_INTERVAL_MS)
            }
        } finally {
            if (scanning) {
                runCatching { scanner.stopScan(callback) }
            }
        }
        return synchronized(found) {
            found.values.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
        }
    }

    private fun deviceInfo(
        device: BluetoothDevice,
        rssi: Int?,
        matchedByServiceUuid: Boolean,
    ): BleDeviceInfo? {
        val name = try {
            device.name
        } catch (e: SecurityException) {
            null
        }
        val bonded = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (e: SecurityException) {
            false
        }
        if (!matchedByServiceUuid && !BleProtocol.isCameraName(name)) return null
        return BleDeviceInfo(
            name = name,
            address = device.address,
            rssi = rssi,
            bonded = bonded,
            matchedByServiceUuid = matchedByServiceUuid,
        )
    }

    // -------------------------------------------------------------- provisioning

    /**
     * Runs the full verified handshake against [device]:
     *
     * 1. connect → discover service `41106dd9-…` (camera GATT is slow after connect)
     * 2. read version `41106da2` → [CameraIdentity] (reject protocol > 1)
     * 3. subscribe token notification `41106da7` **before** pairing (otherwise the
     *    token reply races the subscription), plus optional `dae`/`daf`/`dad`
     * 4. write pairing request `41106da0` = `"1,<key>,android"` (runtime write-type
     *    negotiation; no-response preferred)
     * 5. await token notification — **empty payload = camera rejected the pairing**
     *    ([BleError.PairingRejected]; the user must confirm on the camera screen)
     * 6. write session start `41106da4` = `"1,<key>,<crc32>"`, where
     *    `crc32 = zlib CRC32(ASCII("1"+key+token))`
     * 7. read credentials `41106da6` = `"<ssid>,<password>"`
     * 8. optionally write `"ON"` to `41106da5` (turn camera Wi-Fi on) and epoch
     *    seconds to `41106dac` (the camera has no RTC, so this fixes photo dates)
     *
     * @param pairingKey 0..99998; random by default (same range as the official app).
     * @param turnWifiOn write `"ON"` after reading credentials (failure is non-fatal:
     *        the credentials are still returned, with `wifiEnabled = false`).
     * @param syncTime write the current epoch seconds (`41106dac`, non-fatal if refused).
     * @param confirmationTimeoutMs how long to wait for the user to accept the pairing
     *        prompt on the camera screen (default 45 s — longer than the GATT steps).
     *
     * Never throws: failures are `Result.failure(BleException)` and are also
     * published through [state] as [ProvisionState.Failed].
     */
    suspend fun provision(
        device: BleDeviceInfo,
        pairingKey: Int = BleProtocol.generatePairingKey(),
        turnWifiOn: Boolean = true,
        syncTime: Boolean = true,
        connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        stepTimeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
        confirmationTimeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS,
    ): Result<WifiCredentials> = withContext(ioDispatcher) {
        operationMutex.withLock {
            guarded {
                provisionLocked(
                    device = device,
                    pairingKey = pairingKey,
                    turnWifiOn = turnWifiOn,
                    syncTime = syncTime,
                    connectTimeoutMs = connectTimeoutMs,
                    stepTimeoutMs = stepTimeoutMs,
                    confirmationTimeoutMs = confirmationTimeoutMs,
                )
            }
        }
    }

    private suspend fun provisionLocked(
        device: BleDeviceInfo,
        pairingKey: Int,
        turnWifiOn: Boolean,
        syncTime: Boolean,
        connectTimeoutMs: Long,
        stepTimeoutMs: Long,
        confirmationTimeoutMs: Long,
    ): WifiCredentials {
        if (!BleProtocol.isValidPairingKey(pairingKey)) {
            throw BleException(
                BleError.ProtocolError(
                    "配对 key 必须在 ${BleProtocol.PAIRING_KEY_MIN}..${BleProtocol.PAIRING_KEY_MAX} " +
                        "之间（当前 $pairingKey）",
                ),
            )
        }
        ensureAdapterReady()
        log("开始蓝牙配网：${device.displayName}（${device.address}）")

        _state.value = ProvisionState.Connecting(device)
        val session = openSession(device, connectTimeoutMs)
        try {
            _state.value = ProvisionState.Discovering(device)
            session.discoverServices(stepTimeoutMs)

            _state.value = ProvisionState.ReadingIdentity(device)
            val identity = readIdentity(session, stepTimeoutMs)
            _state.value = ProvisionState.IdentityRead(identity)

            // Subscribe BEFORE the pairing write: the camera can answer within one
            // connection interval, and a late subscription loses the token.
            session.enableNotifications(
                uuid = BleUuids.PAIRING_NOTIFY,
                required = true,
                step = "订阅配对通知(${BleUuids.shortName(BleUuids.PAIRING_NOTIFY)})",
                timeoutMs = stepTimeoutMs,
            )
            subscribeOptionalNotifications(session, stepTimeoutMs)

            _state.value = ProvisionState.Pairing(pairingKey)
            session.writeCharacteristic(
                uuid = BleUuids.PAIRING_REQUEST,
                data = BleProtocol.asciiPayload(
                    BleProtocol.buildPairingRequest(identity.protocolVersion, pairingKey),
                ),
                step = "写入配对请求(${BleUuids.shortName(BleUuids.PAIRING_REQUEST)})",
                timeoutMs = stepTimeoutMs,
            )
            log("已写入配对请求（key=$pairingKey），等待相机屏幕确认…")
            _state.value = ProvisionState.AwaitingCameraConfirmation
            val token = when (
                val notification = BleProtocol.parsePairingResult(
                    session.awaitNotification(
                        uuid = BleUuids.PAIRING_NOTIFY,
                        step = "等待相机配对通知",
                        timeoutMs = confirmationTimeoutMs,
                    ),
                )
            ) {
                PairingNotification.Rejected -> throw BleException(BleError.PairingRejected)
                is PairingNotification.Token -> notification.token
            }
            log("收到配对令牌（${token.length} 字符），正在建立会话…")

            _state.value = ProvisionState.StartingSession
            session.writeCharacteristic(
                uuid = BleUuids.START_SESSION,
                data = BleProtocol.asciiPayload(
                    BleProtocol.buildSessionRequest(identity.protocolVersion, pairingKey, token),
                ),
                step = "写入会话请求(${BleUuids.shortName(BleUuids.START_SESSION)})",
                timeoutMs = stepTimeoutMs,
            )

            _state.value = ProvisionState.ReadingCredentials
            val credentialsRaw = session.readCharacteristic(
                uuid = BleUuids.WIFI_AP,
                step = "读取 Wi-Fi 凭据(${BleUuids.shortName(BleUuids.WIFI_AP)})",
                timeoutMs = stepTimeoutMs,
            )
            val credentials = BleProtocol.parseWifiCredentials(credentialsRaw)
                ?: throw BleException(
                    BleError.ProtocolError(
                        "无法解析相机 Wi-Fi 凭据（内容：\"${BleProtocol.trimAscii(credentialsRaw)}\"）",
                    ),
                )
            log("已读取 Wi-Fi 凭据：SSID=${credentials.ssid}（密码已隐藏，长度 ${credentials.password.length}）")

            val wifiEnabled = if (turnWifiOn) {
                enableCameraWifi(session, stepTimeoutMs)
            } else {
                null
            }
            val timeSynced = if (syncTime) syncCameraClock(session, stepTimeoutMs) else false

            val result = credentials.copy(
                cameraIdentity = identity,
                wifiEnabled = wifiEnabled,
                timeSynced = timeSynced,
            )
            log("蓝牙配网完成：SSID=${result.ssid}，Wi-Fi=${wifiEnabled ?: "未尝试"}，对时=$timeSynced")
            _state.value = ProvisionState.Success(result)
            return result
        } finally {
            session.close()
            log("已断开蓝牙连接")
        }
    }

    private suspend fun readIdentity(session: BleGattSession, stepTimeoutMs: Long): CameraIdentity {
        val raw = session.readCharacteristic(
            uuid = BleUuids.FIRMWARE_INFO,
            step = "读取相机信息(${BleUuids.shortName(BleUuids.FIRMWARE_INFO)})",
            timeoutMs = stepTimeoutMs,
        )
        val text = BleProtocol.trimAscii(raw)
        val identity = BleProtocol.parseFirmwareInfo(raw)
            ?: throw BleException(
                BleError.ProtocolError("无法解析相机固件信息（内容：\"$text\"）"),
            )
        if (identity.protocolVersion < 1) {
            throw BleException(
                BleError.CameraUnsupported("相机报告的 BLE 协议版本无效（${identity.protocolVersion}）"),
            )
        }
        if (identity.protocolVersion > BleProtocol.SUPPORTED_PROTOCOL_VERSION) {
            // The official app treats this exactly like this: error 113, incompatible version.
            throw BleException(
                BleError.CameraUnsupported(
                    "BLE 协议版本 ${identity.protocolVersion} 高于本应用支持的 " +
                        "${BleProtocol.SUPPORTED_PROTOCOL_VERSION}（官方 App 错误 113/相机与App版本不兼容）",
                ),
            )
        }
        log("相机信息：\"$text\"（$identity）")
        return identity
    }

    /** Optional `daf`/`dae` notifications and the `dad` `"3"` write — tolerant, never fatal. */
    private suspend fun subscribeOptionalNotifications(
        session: BleGattSession,
        stepTimeoutMs: Long,
    ) {
        for (uuid in listOf(BleUuids.UNKNOWN_NOTIFY_F, BleUuids.UNKNOWN_NOTIFY_E)) {
            val subscribed = try {
                session.enableNotifications(
                    uuid = uuid,
                    required = false,
                    step = "订阅可选通知(${BleUuids.shortName(uuid)})",
                    timeoutMs = stepTimeoutMs,
                )
            } catch (e: BleException) {
                log("订阅可选通知 ${BleUuids.shortName(uuid)} 失败（忽略）：${e.message}")
                false
            }
            if (subscribed) log("已订阅可选通知 ${BleUuids.shortName(uuid)}")
        }
        if (session.hasCharacteristic(BleUuids.RESUME_RELATED)) {
            try {
                session.writeCharacteristic(
                    uuid = BleUuids.RESUME_RELATED,
                    data = BleProtocol.asciiPayload("3"),
                    step = "写入恢复相关(${BleUuids.shortName(BleUuids.RESUME_RELATED)})",
                    timeoutMs = stepTimeoutMs,
                )
            } catch (e: BleException) {
                log("写入 ${BleUuids.shortName(BleUuids.RESUME_RELATED)} \"3\" 失败（忽略）：${e.message}")
            }
        }
    }

    private suspend fun enableCameraWifi(session: BleGattSession, stepTimeoutMs: Long): Boolean {
        _state.value = ProvisionState.EnablingWifi
        return try {
            session.writeCharacteristic(
                uuid = BleUuids.WIFI_SWITCH,
                data = BleProtocol.asciiPayload(BleProtocol.buildWifiToggle(on = true)),
                step = "开启相机 Wi-Fi(${BleUuids.shortName(BleUuids.WIFI_SWITCH)})",
                timeoutMs = stepTimeoutMs,
            )
            log("已写入 Wi-Fi 开关 ON")
            true
        } catch (e: BleException) {
            // Non-fatal: the credentials were already read, and the camera may already
            // have its AP up. The UI can offer [setWifi] as a retry.
            log("开启相机 Wi-Fi 失败（凭据已读取，可稍后重试开关）：${e.message}")
            false
        }
    }

    private suspend fun syncCameraClock(session: BleGattSession, stepTimeoutMs: Long): Boolean {
        _state.value = ProvisionState.SyncingTime
        // Official app convention: local wall clock expressed as epoch seconds
        // (`$SRC/b/p.java:77-81` + `$SRC/common/d.java:1030`). The camera has no
        // timezone concept, so this is what makes its clock display local time.
        val payload = BleProtocol.buildCameraClockPayloadNow()
        return try {
            session.writeCharacteristic(
                uuid = BleUuids.SYNC_TIME,
                data = BleProtocol.asciiPayload(payload),
                step = "相机对时(${BleUuids.shortName(BleUuids.SYNC_TIME)})",
                timeoutMs = stepTimeoutMs,
            )
            log("已同步相机时间：$payload（本地墙钟纪元秒；相机无 RTC，每次开机都需对时）")
            true
        } catch (e: BleException) {
            log("相机对时失败（忽略）：${e.message}")
            false
        }
    }

    // -------------------------------------------------------- standalone actions

    /**
     * Opens a short BLE session and writes `"ON"`/`"OFF"` to `41106da5`.
     *
     * The official app performs exactly this bare write once the phone is bound
     * (no session handshake on that path). It therefore requires the camera to
     * still trust this phone's pairing; if another device re-paired the camera,
     * the camera answers application-level error `0x80` and the typed error tells
     * the user to re-run [provision].
     *
     * Use it e.g. to turn Wi-Fi back on after the app's HTTP `CloseAP`-like flow,
     * or to turn it off from BLE (symmetric, does not depend on the phone being
     * on the camera hotspot).
     */
    suspend fun setWifi(device: BleDeviceInfo, on: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
            operationMutex.withLock {
                guarded {
                    ensureAdapterReady()
                    _state.value = ProvisionState.EnablingWifi
                    val session = openSession(device, DEFAULT_CONNECT_TIMEOUT_MS)
                    try {
                        session.discoverServices(DEFAULT_STEP_TIMEOUT_MS)
                        session.writeCharacteristic(
                            uuid = BleUuids.WIFI_SWITCH,
                            data = BleProtocol.asciiPayload(BleProtocol.buildWifiToggle(on)),
                            step = if (on) {
                                "开启相机 Wi-Fi(${BleUuids.shortName(BleUuids.WIFI_SWITCH)})"
                            } else {
                                "关闭相机 Wi-Fi(${BleUuids.shortName(BleUuids.WIFI_SWITCH)})"
                            },
                            timeoutMs = DEFAULT_STEP_TIMEOUT_MS,
                        )
                        log("已写入 Wi-Fi 开关 ${if (on) "ON" else "OFF"}")
                    } finally {
                        session.close()
                    }
                    _state.value = ProvisionState.Idle
                }
            }
        }

    /**
     * Writes the current Unix epoch seconds (ASCII) to `41106dac`.
     *
     * The camera has **no RTC** (confirmed in firmware: no `rtc`/`settimeofday`
     * references, time is RAM-only and lost on power-off), so every photo's
     * `capture_date` is wrong until this is called. Official app:
     * `(System.currentTimeMillis() / 1000) + ""`. Requires a still-valid pairing.
     */
    suspend fun syncTime(device: BleDeviceInfo): Result<Unit> =
        withContext(ioDispatcher) {
            operationMutex.withLock {
                guarded {
                    ensureAdapterReady()
                    _state.value = ProvisionState.SyncingTime
                    val payload = BleProtocol.buildCameraClockPayloadNow()
                    val session = openSession(device, DEFAULT_CONNECT_TIMEOUT_MS)
                    try {
                        session.discoverServices(DEFAULT_STEP_TIMEOUT_MS)
                        session.writeCharacteristic(
                            uuid = BleUuids.SYNC_TIME,
                            data = BleProtocol.asciiPayload(payload),
                            step = "相机对时(${BleUuids.shortName(BleUuids.SYNC_TIME)})",
                            timeoutMs = DEFAULT_STEP_TIMEOUT_MS,
                        )
                        log("已同步相机时间：$payload")
                    } finally {
                        session.close()
                    }
                    _state.value = ProvisionState.Idle
                }
            }
        }

    /**
     * Writes [pairingKey] to `41106da9` to ask the camera to forget its pairing
     * (the official app calls this its "unpair" action and writes the stored
     * `refId`). Best-effort: the camera refuses this unless the session state
     * allows it, and a stale binding may only be clearable from the camera menu.
     */
    suspend fun unpair(device: BleDeviceInfo, pairingKey: Int): Result<Unit> =
        withContext(ioDispatcher) {
            operationMutex.withLock {
                guarded {
                    ensureAdapterReady()
                    val session = openSession(device, DEFAULT_CONNECT_TIMEOUT_MS)
                    try {
                        session.discoverServices(DEFAULT_STEP_TIMEOUT_MS)
                        session.writeCharacteristic(
                            uuid = BleUuids.PAIRING_FORGET,
                            data = BleProtocol.asciiPayload(pairingKey.toString()),
                            step = "解除配对(${BleUuids.shortName(BleUuids.PAIRING_FORGET)})",
                            timeoutMs = DEFAULT_STEP_TIMEOUT_MS,
                        )
                        log("已请求相机解除配对（key=$pairingKey）")
                    } finally {
                        session.close()
                    }
                    _state.value = ProvisionState.Idle
                }
            }
        }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Releases module state. Sessions are already closed by each call's `finally`,
     * so this only prevents further use; call it from the ViewModel's `onCleared`.
     */
    fun close() {
        closed = true
        writeTypeMemory.clear()
    }

    /** Whether this phone exposes a BLE radio at all. */
    fun isBluetoothSupported(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
            bluetoothManager?.adapter != null

    /** Whether Bluetooth is currently switched on. */
    fun isBluetoothEnabled(): Boolean = bluetoothManager?.adapter?.isEnabled == true

    /**
     * Runtime permissions needed before scanning/pairing, by API level:
     * API 31+ `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`, API 28–30 `ACCESS_FINE_LOCATION`.
     * (`BLUETOOTH`/`BLUETOOTH_ADMIN` are install-time normal permissions.)
     */
    fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** The subset of [requiredPermissions] currently not granted. */
    fun missingPermissions(): List<String> = requiredPermissions().filter { permission ->
        appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
    }

    /** Clears the [diagnostics] log (e.g. from a "clear" button in the diagnostic view). */
    fun clearDiagnostics() {
        _diagnostics.value = emptyList()
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun openSession(
        device: BleDeviceInfo,
        connectTimeoutMs: Long,
    ): BleGattSession {
        val localAdapter = adapter ?: throw BleException(BleError.BluetoothUnsupported)
        val remote = try {
            localAdapter.getRemoteDevice(device.address)
        } catch (e: IllegalArgumentException) {
            throw BleException(BleError.CameraNotFound("无效的蓝牙地址 ${device.address}"), e)
        } catch (e: SecurityException) {
            throw BleException(BleError.PermissionDenied(missingPermissions()), e)
        }

        var lastFailure: Throwable? = null
        repeat(MAX_CONNECT_ATTEMPTS) { attempt ->
            val session = BleGattSession(::log, writeTypeMemory)
            val gatt = try {
                remote.connectGatt(appContext, false, session, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                throw BleException(BleError.PermissionDenied(missingPermissions()), e)
            }
            if (gatt == null) {
                lastFailure = BleException(BleError.ConnectionFailed(null, "connectGatt 返回 null"))
                return@repeat
            }
            session.attach(gatt)
            try {
                session.awaitConnected(connectTimeoutMs)
                // Verified pitfall: the camera's GATT server is slow to publish its
                // characteristic table right after connect (1200 ms in the reference client).
                delay(GATT_PUBLISH_DELAY_MS)
                return session
            } catch (e: BleException) {
                lastFailure = e
                session.close()
                if (attempt < MAX_CONNECT_ATTEMPTS - 1) {
                    log("连接尝试 ${attempt + 1} 失败（${e.message}），${CONNECT_RETRY_DELAY_MS}ms 后重试")
                    delay(CONNECT_RETRY_DELAY_MS)
                }
            } catch (e: CancellationException) {
                session.close()
                throw e
            }
        }
        throw (lastFailure as? BleException)
            ?: BleException(BleError.ConnectionFailed(null, lastFailure?.message))
    }

    /** Runs [block] under the typed-error contract: never throws (except cancellation). */
    private suspend fun <T> guarded(block: suspend () -> T): Result<T> {
        return try {
            ensureNotClosed()
            Result.success(block())
        } catch (e: CancellationException) {
            if (_state.value !is ProvisionState.Success) _state.value = ProvisionState.Idle
            throw e
        } catch (e: BleException) {
            log("蓝牙配网失败：${e.message?.replace('\n', ' ')}")
            _state.value = ProvisionState.Failed(e.error)
            Result.failure(e)
        } catch (e: SecurityException) {
            val error = BleError.PermissionDenied(missingPermissions())
            log("蓝牙权限不足：${e.message}")
            _state.value = ProvisionState.Failed(error)
            Result.failure(BleException(error, e))
        } catch (t: Throwable) {
            val error = BleError.ProtocolError(t.message ?: t.javaClass.simpleName)
            log("未预期的错误：$t")
            _state.value = ProvisionState.Failed(error)
            Result.failure(BleException(error, t))
        }
    }

    private fun ensureNotClosed() {
        if (closed) {
            throw BleException(BleError.ProtocolError("BleProvisioner 已关闭，请重新创建"))
        }
    }

    private fun ensureAdapterReady() {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            throw BleException(BleError.BluetoothUnsupported)
        }
        val localAdapter = adapter ?: throw BleException(BleError.BluetoothUnsupported)
        if (!localAdapter.isEnabled) throw BleException(BleError.BluetoothOff)
        val missing = missingPermissions()
        if (missing.isNotEmpty()) throw BleException(BleError.PermissionDenied(missing))
    }

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private fun log(message: String) {
        val line = "${TIME_FORMAT.format(LocalTime.now())}  $message"
        _diagnostics.update { current ->
            val next = current + line
            if (next.size <= MAX_DIAGNOSTIC_LINES) next else next.takeLast(MAX_DIAGNOSTIC_LINES)
        }
    }

    companion object {
        /** Default scan window; returns earlier as soon as a camera answers. */
        const val DEFAULT_SCAN_TIMEOUT_MS = 12_000L

        /** GATT connect timeout (the camera can be slow to accept the link). */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 20_000L

        /** Per GATT step (read/write/descriptor) timeout. */
        const val DEFAULT_STEP_TIMEOUT_MS = 10_000L

        /** How long to wait for the user to accept the pairing prompt on the camera screen. */
        const val DEFAULT_CONFIRMATION_TIMEOUT_MS = 45_000L

        private const val GATT_PUBLISH_DELAY_MS = 1_200L
        private const val CONNECT_RETRY_DELAY_MS = 600L
        private const val MAX_CONNECT_ATTEMPTS = 2
        private const val SCAN_POLL_INTERVAL_MS = 100L
        private const val MIN_SCAN_WINDOW_MS = 500L
        private const val MAX_DIAGNOSTIC_LINES = 200

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
