package com.yim1remote.app.data

import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yim1remote.app.data.model.CameraFileInfo
import com.yim1remote.app.data.model.CameraFileType
import com.yim1remote.app.data.model.CameraSetting
import com.yim1remote.app.data.model.CameraStatus
import com.yim1remote.app.data.model.DownloadResolution
import com.yim1remote.app.data.model.ExposureMode
import com.yim1remote.app.data.model.GalleryItem
import com.yim1remote.app.data.model.GalleryPaging
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Default [CameraRepository] implementation: the YI M1 local HTTP API plus the
 * UDP live-view stream.
 *
 * Design notes (all derived from the verified protocol specs in
 * `research/verified/` and the APK decompilation):
 *
 *  * **One request in flight.** The camera firmware processes a single HTTP
 *    request at a time; concurrent requests return `{"code":1515,"data":"rc only
 *    one"}`, which retries transparently once after a short delay. Every camera
 *    command therefore goes through [requestGate].
 *  * **Two state sources.** `GetCameraStatus` is only a small health probe
 *    (battery / remaining shots / lens) and is polled every ~5 s. The full 26-key
 *    state (all settings) is in every live-view frame; the repository parses it
 *    every few frames and publishes it as [status]. After a setter, the UI state
 *    changes when the next frame carries the new value — not merely because the
 *    HTTP call returned 200 (verified hardware behaviour).
 *  * **Threading.** All suspend functions are main-thread safe; network work runs
 *    on [Dispatchers.IO] inside [HttpCameraClient]/[LiveViewReceiver] or in this
 *    repository's IO scope. Flows may be collected from the main thread.
 *  * **Never crash on a bad camera response:** protocol errors become failed
 *    [Result]s and, for the connection/poll path, [ConnectionState.Failed].
 */
class CameraRepositoryImpl(
    context: Context,
    private val client: HttpCameraClient,
    private val live: LiveViewReceiver,
) : CameraRepository {

    private val appContext: Context = context.applicationContext
    private val configStore = CameraConfigStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes camera HTTP requests (firmware processes one at a time). */
    private val requestGate = Mutex()

    @Volatile
    private var sessionBaseUrl: String? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _status = MutableStateFlow<CameraStatus?>(null)
    override val status: StateFlow<CameraStatus?> = _status.asStateFlow()

    private val _liveFrame = MutableStateFlow<LiveFrame?>(null)
    override val liveFrame: StateFlow<LiveFrame?> = _liveFrame.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val messages: SharedFlow<String> = _messages.asSharedFlow()

    override val baseUrl: String
        get() = sessionBaseUrl ?: configStore.load().baseUrl

    private var pollJob: Job? = null
    private var frameJob: Job? = null
    private var liveMessagesJob: Job? = null

    @Volatile
    private var liveViewActive = false

    /** True while this process owns the camera's single remote-control session. */
    @Volatile
    private var remoteControlActive = false

    private var liveFrameCounter = 0L

    /**
     * The camera's `ImageAspect` echo is known to be unreliable (e.g. it keeps
     * reporting `4:3` regardless of the real setting). After a successful
     * `RCImageAspect` we therefore keep the user's chosen value for a while and
     * only accept the echo once it matches (or after a timeout).
     */
    @Volatile
    private var pendingImageAspect: Pair<String, Long>? = null

    // ------------------------------------------------------------------ connect

    override suspend fun connect(baseUrlOverride: String?): Result<Unit> {
        sessionBaseUrl = baseUrlOverride?.let(CameraConfig::normalizeBaseUrl)
        client.baseUrl = baseUrl
        stopPolling()
        stopLiveViewInternals()
        _status.value = null
        _liveFrame.value = null
        _connectionState.value = ConnectionState.Connecting
        return try {
            // A camera waking from sleep routinely drops the very first request
            // (research-verified: timeout + "lenVer 0.0"/empty lens on the first
            // probe). Retry/warm up once before reporting a connection failure.
            val health = probeHealthWithWarmup()
            val initial = CameraProtocol.mergeHealth(null, health)
            publishStatus(initial)
            _connectionState.value = ConnectionState.Connected(initial)
            startPolling()
            Result.success(Unit)
        } catch (e: CancellationException) {
            _connectionState.value = ConnectionState.Disconnected
            throw e
        } catch (e: Exception) {
            val message = friendlyError(e)
            _connectionState.value = ConnectionState.Failed(message, e)
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        // Leave the camera's remote-control/live-view session before tearing the
        // link down (best effort; a stale RC session would block the next app run).
        releaseRemoteControl()
        stopPolling()
        stopLiveViewInternals()
        _status.value = null
        _liveFrame.value = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /** Releases every resource; call from `ViewModel.onCleared()`. */
    fun close() {
        stopPolling()
        stopLiveViewInternals()
        live.close()
        scope.cancel()
    }

    // ------------------------------------------------------------------- status

    override suspend fun refreshStatus(): Result<CameraStatus> = try {
        val envelope = withCameraGate {
            executeWithRetry(CameraJson.getCameraStatus(), CameraProtocol.CONTROL_TIMEOUT_MS)
        }
        val merged = CameraProtocol.mergeHealth(
            _status.value,
            CameraProtocol.parseHealthStatus(envelope.data),
        )
        publishStatus(merged)
        Result.success(merged)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Status probe with sleep/wake handling.
     *
     * Hardware behaviour (YI M1 research + our own testing): after the camera
     * idles, the first HTTP request may time out, and the first successful reply
     * can carry `lenVer:"0.0"` with an empty lens type. Retry briefly and, when
     * the reply looks like a warming-up one, probe once more for complete data.
     */
    private suspend fun probeHealthWithWarmup(): CameraHealth {
        var lastError: Exception? = null
        for (attempt in 0 until STATUS_PROBE_ATTEMPTS) {
            try {
                val envelope = withCameraGate {
                    executeWithRetry(CameraJson.getCameraStatus(), CameraProtocol.CONTROL_TIMEOUT_MS)
                }
                var health = CameraProtocol.parseHealthStatus(envelope.data)
                if (health.lensType.isNullOrBlank() || health.lensVersion.isNullOrBlank()) {
                    delay(STATUS_WARMUP_DELAY_MS)
                    try {
                        val second = withCameraGate {
                            executeWithRetry(CameraJson.getCameraStatus(), CameraProtocol.CONTROL_TIMEOUT_MS)
                        }
                        val richer = CameraProtocol.parseHealthStatus(second.data)
                        if (!richer.lensType.isNullOrBlank() || !richer.lensVersion.isNullOrBlank()) {
                            health = richer
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Keep the first (partial) health; a later poll fills it in.
                    }
                }
                return health
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < STATUS_PROBE_ATTEMPTS - 1) delay(STATUS_PROBE_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IOException("无法读取相机状态")
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                delay(STATUS_POLL_INTERVAL_MS)
                if (_connectionState.value !is ConnectionState.Connected) break
                // Live frames carry the full state (including battery / remaining
                // shots); skip the HTTP probe while they are fresh.
                val lastFrame = live.lastFrameAtMillis
                if (liveViewActive && lastFrame > 0L &&
                    System.currentTimeMillis() - lastFrame < LIVE_STATUS_FRESH_MS
                ) {
                    continue
                }
                // Don't queue behind a long operation (capture/upload/download).
                if (!requestGate.tryLock()) continue
                try {
                    val envelope = executeWithRetry(
                        CameraJson.getCameraStatus(),
                        CameraProtocol.CONTROL_TIMEOUT_MS,
                    )
                    publishStatus(
                        CameraProtocol.mergeHealth(
                            _status.value,
                            CameraProtocol.parseHealthStatus(envelope.data),
                        ),
                    )
                    consecutiveFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_POLL_FAILURES) {
                        _connectionState.value = ConnectionState.Failed(friendlyError(e), e)
                        break
                    }
                } finally {
                    requestGate.unlock()
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun publishStatus(status: CameraStatus) {
        _status.value = status
        val state = _connectionState.value
        if (state is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Connected(status)
        }
    }

    /**
     * Returns the aspect the UI should show given the camera's echoed value.
     *
     * `ImageAspect` is a known-unreliable echo (firmware may keep reporting
     * `4:3`). Right after a successful `RCImageAspect` we keep the requested
     * value; the echo is accepted only once it matches, or after
     * [IMAGE_ASPECT_STICKY_MS] (then reality wins).
     */
    private fun resolveImageAspect(echoed: String): String {
        val pending = pendingImageAspect ?: return echoed
        val now = System.currentTimeMillis()
        if (echoed == pending.first) {
            pendingImageAspect = null
            return echoed
        }
        if (now - pending.second > IMAGE_ASPECT_STICKY_MS) {
            pendingImageAspect = null
            return echoed
        }
        return pending.first
    }

    // ---------------------------------------------------------------- live view

    override suspend fun startLiveView(): Result<Unit> {
        if (_connectionState.value !is ConnectionState.Connected) {
            Log.w(TAG_LV, "startLiveView: not connected (${_connectionState.value})")
            return Result.failure(IllegalStateException("相机未连接"))
        }
        Log.i(TAG_LV, "startLiveView invoked")
        // Wake the camera before the remote-control handshake: a sleeping camera
        // can drop the first request and then RCStartRemoteCtl would time out.
        try {
            withCameraGate {
                executeWithRetry(CameraJson.getCameraStatus(), PREWARM_TIMEOUT_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG_LV, "prewarm status probe failed: ${e.message}")
        }
        return try {
            if (!liveViewActive) {
                liveViewActive = true
                startFrameCollection()
                // Bind the UDP port BEFORE telling the camera to start; otherwise
                // the first frames are lost (documented pitfall).
                live.start()
            }
            var startCommandAccepted = false
            var lastError: Exception? = null
            // Public hardware-tested clients start the stream with
            // RCStartRemoteCtl; the official app also has StartLiveview. Send both
            // and tolerate a failure of either.
            try {
                withCameraGate {
                    executeWithRetry(CameraJson.rcStartRemoteCtl(), CameraProtocol.CONTROL_TIMEOUT_MS)
                }
                startCommandAccepted = true
                remoteControlActive = true
                Log.i(TAG_LV, "RCStartRemoteCtl ok")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG_LV, "RCStartRemoteCtl failed: ${e.message}")
            }
            try {
                withCameraGate {
                    executeWithRetry(CameraJson.startLiveview(), CameraProtocol.CONTROL_TIMEOUT_MS)
                }
                startCommandAccepted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            if (!startCommandAccepted) {
                Log.w(TAG_LV, "startLiveView failed: ${lastError?.message}")
                Result.failure(lastError ?: IOException("无法开启实时取景"))
            } else {
                Log.i(TAG_LV, "startLiveView success")
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun stopLiveView() {
        // Leaving the Remote tab MUST release the camera's remote-control session:
        // until RCStopRemoteCtl is accepted the firmware rejects every non-RC
        // command with {"code":1515,"data":"RC only one"} (verified on hardware),
        // which would also break the gallery. Never queue behind a long capture:
        // wait only briefly for the request gate, then send the stop directly.
        releaseRemoteControl()
        stopLiveViewInternals()
    }

    /**
     * Best-effort `RCStopRemoteCtl`.
     *
     * Waits up to [STOP_CONTROL_WAIT_MS] for the request gate (a capture may hold
     * it for many seconds); if the gate stays busy the stop is sent anyway — the
     * camera tolerates the stop while another command is in flight, and a missed
     * stop leaves the camera in RC mode, which is much worse.
     */
    private suspend fun releaseRemoteControl() {
        if (_connectionState.value !is ConnectionState.Connected) return
        if (!remoteControlActive) {
            Log.i(TAG_CAM, "RCStopRemoteCtl skipped (no active session)")
            return
        }
        val sentWithGate = withTimeoutOrNull(STOP_CONTROL_WAIT_MS) {
            requestGate.withLock {
                try {
                    executeWithRetry(CameraJson.rcStopRemoteCtl(), CameraProtocol.TRANSFER_TIMEOUT_MS)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG_CAM, "RCStopRemoteCtl failed: ${e.message}")
                    false
                }
            }
        }
        if (sentWithGate == true) {
            remoteControlActive = false
            Log.i(TAG_CAM, "RCStopRemoteCtl ok")
            return
        }
        try {
            executeWithRetry(CameraJson.rcStopRemoteCtl(), CameraProtocol.TRANSFER_TIMEOUT_MS)
            remoteControlActive = false
            Log.i(TAG_CAM, "RCStopRemoteCtl ok (direct)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG_CAM, "RCStopRemoteCtl direct attempt failed: ${e.message}")
        }
    }

    private fun stopLiveViewInternals() {
        if (!liveViewActive && frameJob == null) {
            liveViewActive = false
            _liveFrame.value = null
            return
        }
        liveViewActive = false
        live.stop()
        frameJob?.cancel()
        frameJob = null
        liveMessagesJob?.cancel()
        liveMessagesJob = null
        _liveFrame.value = null
    }

    private fun startFrameCollection() {
        frameJob?.cancel()
        liveMessagesJob?.cancel()
        liveFrameCounter = 0L
        frameJob = scope.launch {
            live.frames.collect { received ->
                try {
                    liveFrameCounter++
                    val previous = _status.value
                    // The state JSON only needs human-speed updates (~stride 4);
                    // decoding it every frame would waste CPU at ~30 fps.
                    val shouldParse = received.paramsJson != null &&
                        (liveFrameCounter % LIVE_STATUS_PARSE_STRIDE == 0L || previous == null)
                    val parsed = if (shouldParse) {
                        CameraProtocol.parseStatus(received.paramsJson)
                    } else {
                        null
                    }
                    val effective = when {
                        parsed == null -> previous
                        else -> parsed.copy(
                            connected = true,
                            // Firmware/lens version only comes from the HTTP health
                            // probe (or BLE in the official app); keep it across frames.
                            firmwareVersion = parsed.firmwareVersion ?: previous?.firmwareVersion,
                            firmwareDisplay = parsed.firmwareDisplay ?: previous?.firmwareDisplay,
                            lensType = parsed.lensType ?: previous?.lensType,
                            lensVersion = parsed.lensVersion ?: previous?.lensVersion,
                            shooting = previous?.shooting ?: false,
                            imageAspect = resolveImageAspect(parsed.imageAspect),
                        )
                    }
                    if (parsed != null && effective != null) publishStatus(effective)
                    _liveFrame.value = LiveFrame(
                        bitmap = received.bitmap,
                        status = _status.value,
                        width = received.bitmap.width,
                        height = received.bitmap.height,
                        receivedAtMillis = received.receivedAtMillis,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A malformed parameter block must never kill the frame pump.
                }
            }
        }
        liveMessagesJob = scope.launch {
            live.messages.collect { message -> _messages.tryEmit(message) }
        }
    }

    // ------------------------------------------------------------------ setters

    override suspend fun setExposureMode(mode: ExposureMode): Result<Unit> = try {
        // The firmware value table also accepts "Scene"; the official app sends "C"
        // for the custom position and that is what we keep sending.
        withCameraGate {
            executeWithRetry(CameraJson.rcSwitchDialMode(mode.wire), CameraProtocol.CONTROL_TIMEOUT_MS)
        }
        // No optimistic update: the live-view state echo is the authority.
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun setSetting(setting: CameraSetting, wireValue: String): Result<Unit> = try {
        val command = when (setting) {
            CameraSetting.EXPOSURE_MODE -> CameraJson.rcSwitchDialMode(wireValue)
            CameraSetting.METERING_MODE -> CameraJson.rcMeteringModeSet(wireValue)
            CameraSetting.FOCUS_MODE -> CameraJson.rcFocusModeSet(wireValue)
            CameraSetting.IMAGE_QUALITY -> CameraJson.rcImageQualitySet(wireValue)
            CameraSetting.IMAGE_ASPECT -> CameraJson.rcImageAspect(wireValue)
            CameraSetting.FILE_FORMAT -> CameraJson.rcFileFormatSet(wireValue)
            CameraSetting.DRIVE_MODE -> CameraJson.rcDriveModeSet(wireValue)
            CameraSetting.FNUMBER -> CameraJson.rcFnSet(wireValue)
            CameraSetting.SHUTTER_SPEED -> CameraJson.rcShutterSpeedSet(wireValue)
            CameraSetting.EV -> CameraJson.rcEvSet(wireValue)
            CameraSetting.ISO -> CameraJson.rcIsoSet(wireValue)
            CameraSetting.WHITE_BALANCE -> CameraJson.rcWbSet(wireValue)
            CameraSetting.COLOR_MODE -> CameraJson.rcChooseColorMode(wireValue)
            CameraSetting.DELAY_SHOOT_COUNT -> CameraJson.rcDShootCntSet(wireValue)
            CameraSetting.MF_ADJUST -> CameraJson.rcMfAdjust(wireValue)
        }
        withCameraGate {
            executeWithRetry(command, CameraProtocol.CONTROL_TIMEOUT_MS)
        }
        // The aspect echo is unreliable; remember the requested value until the
        // camera's frames start reporting it (see resolveImageAspect).
        if (setting == CameraSetting.IMAGE_ASPECT) {
            pendingImageAspect = wireValue to System.currentTimeMillis()
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    // ------------------------------------------------------------------- capture

    override suspend fun capture(): Result<Unit> {
        return try {
            val timeout = CameraProtocol.captureTimeoutMillis(_status.value)
            publishShooting(true)
            // The official app runs an auto-area focus before releasing the shutter
            // (skipped in manual-focus mode). Best effort: a failed focus must not
            // prevent the shot.
            if (_status.value?.focusMode != "MF") {
                try {
                    withCameraGate {
                        executeWithRetry(
                            CameraJson.rcDoFocus("Auto", 0, 0),
                            CameraProtocol.CONTROL_TIMEOUT_MS,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // ignore — RCDoShooting below will make the camera focus itself
                }
            }
            withCameraGate {
                executeWithRetry(CameraJson.rcDoShooting(), timeout.toInt())
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            publishShooting(false)
        }
    }

    override suspend fun cancelCapture(): Result<Unit> {
        // Deliberately NOT gated: the RCDoShooting request may still hold the gate
        // for a long exposure, and cancelling means interrupting it.
        return try {
            val cancelled = try {
                CameraProtocol.requireSuccess(
                    CameraProtocol.parseEnvelope(
                        client.getText(CameraJson.rcCancelShooting(), CameraProtocol.CONTROL_TIMEOUT_MS),
                    ),
                )
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!cancelled) {
                // Fallback command the official app used with a 60 s timeout.
                CameraProtocol.requireSuccess(
                    CameraProtocol.parseEnvelope(
                        client.getText(CameraJson.rcCancelShooting1(), CANCEL_FALLBACK_TIMEOUT_MS),
                    ),
                )
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun publishShooting(shooting: Boolean) {
        val current = _status.value ?: return
        if (current.shooting != shooting) publishStatus(current.copy(shooting = shooting))
    }

    // --------------------------------------------------------------------- focus

    override suspend fun focusAt(normalizedX: Float, normalizedY: Float): Result<Unit> = try {
        val current = _status.value
        // The official app disables touch focus while the lens reports manual (2)
        // or unknown/error (3) status (LiveViewFragment#b(int,int)).
        if (current?.lensStatus == "2" || current?.lensStatus == "3") {
            throw IllegalStateException(
                "当前镜头状态不支持触控对焦（LensStatus=${current.lensStatus}）",
            )
        }
        val point = CameraProtocol.focusPoint(normalizedX, normalizedY, current?.imageAspect)
        Log.i(
            TAG_FOCUS,
            "focusAt nx=$normalizedX ny=$normalizedY lensStatus=${current?.lensStatus} " +
                "focusMode=${current?.focusMode} aspect=${current?.imageAspect} point=${point.x},${point.y}",
        )
        withCameraGate {
            executeWithRetry(
                CameraJson.rcDoFocus("Manual", point.x, point.y),
                CameraProtocol.CONTROL_TIMEOUT_MS,
            )
        }
        Log.i(TAG_FOCUS, "focusAt ok point=${point.x},${point.y}")
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG_FOCUS, "focusAt failed: ${e.message}")
        Result.failure(e)
    }

    override suspend fun focusAuto(): Result<FocusPoint> = try {
        val current = _status.value
        if (current?.lensStatus == "2" || current?.lensStatus == "3") {
            throw IllegalStateException(
                "当前镜头状态不支持触控对焦（LensStatus=${current.lensStatus}）",
            )
        }
        val envelope = withCameraGate {
            executeWithRetry(
                CameraJson.rcDoFocus("Auto", 0, 0),
                CameraProtocol.CONTROL_TIMEOUT_MS,
            )
        }
        val point = CameraProtocol.parseFocusPoint(envelope.data)
            ?: FocusPoint(360, 240)
        Log.i(TAG_FOCUS, "focusAuto ok point=${point.x},${point.y}")
        Result.success(point)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG_FOCUS, "focusAuto failed: ${e.message}")
        Result.failure(e)
    }

    override suspend fun adjustManualFocus(operation: Int): Result<Unit> = try {
        // Signed step count -> firmware operation: |>=2| = fast far/near,
        // |1| = single step; 0 is a no-op.
        val command = CameraJson.rcMfAdjust(operation)
        if (command != null) {
            withCameraGate {
                executeWithRetry(command, CameraProtocol.CONTROL_TIMEOUT_MS)
            }
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    // ------------------------------------------------------------------- gallery

    override suspend fun loadGalleryPage(page: Int): Result<List<GalleryItem>> = try {
        val range = GalleryPaging.rangeForPage(page)
        val body = withCameraGate {
            client.getText(
                CameraJson.getFileList(range.first, range.last),
                CameraProtocol.TRANSFER_TIMEOUT_MS,
            )
        }
        Result.success(CameraProtocol.parseFileListResponse(body))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun download(
        item: GalleryItem,
        resolution: DownloadResolution,
        sink: OutputStream,
        progress: (Long, Long?) -> Unit,
    ): Result<Long> = try {
        requireFetchablePath(item.path)
        // The camera ignores the resolution for videos and streams the whole file;
        // callers request ORIGINAL for videos anyway.
        val command = CameraJson.getFile(item.path, item.dateWire, resolution.wire)
        Log.i(TAG_CAM, "download waiting for camera gate: ${item.path}")
        var lastError: Exception? = null
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                val bytes = withCameraGate {
                    Log.i(TAG_CAM, "download gate acquired (attempt=${attempt + 1}): ${item.path}")
                    client.downloadTo(command, sink, CameraProtocol.TRANSFER_TIMEOUT_MS) { copied, total ->
                        // Log roughly once per MiB to keep logcat readable during 30 MB transfers.
                        if (copied / 1_048_576L != (copied - 65_536L) / 1_048_576L || copied < 100_000L) {
                            Log.i(TAG_CAM, "download progress: $copied / $total")
                        }
                        progress(copied, total)
                    }
                }
                Log.i(TAG_CAM, "download completed: ${item.path} bytes=$bytes")
                return Result.success(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG_CAM, "download attempt ${attempt + 1} failed: ${e.message}")
                if (e is CameraApiException) throw e   // camera rejected the request; retrying won't help
                if (attempt < DOWNLOAD_ATTEMPTS - 1) delay(DOWNLOAD_RETRY_DELAY_MS)
            }
        }
        Result.failure(lastError ?: IOException("下载失败"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG_CAM, "download failed: ${item.path}: ${e.message}", e)
        Result.failure(e)
    }

    override suspend fun loadPreview(
        item: GalleryItem,
        resolution: DownloadResolution,
    ): Result<Bitmap> = try {
        requireFetchablePath(item.path)
        if (item.isVideo) {
            // The camera ignores the quality parameter for MP4 and starts streaming
            // the whole clip; there is no cheap video thumbnail.
            throw CameraPlatformException("视频没有可用的缩略图，请下载原片")
        }
        // Hardware-verified (2026-11-24): the camera DOES serve JPEG renditions for
        // .DNG files — Thumbnail ~8.8 KB (0.26 s) and MidThumb ~0.6 MB (1.3 s).
        // Never request Original for a preview: that streams the whole ~32 MB raw.
        val effective = if (item.isRaw && resolution == DownloadResolution.ORIGINAL) {
            DownloadResolution.MID_THUMB
        } else {
            resolution
        }
        val bytes = fetchPreviewBytes(item, effective)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw CameraProtocolException("无法解码相机返回的缩略图")
        Result.success(bitmap)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun loadFileInfo(item: GalleryItem): Result<CameraFileInfo> = try {
        requireFetchablePath(item.path)
        val envelope = withCameraGate {
            executeWithRetry(CameraJson.getFileInfo(item.path), CameraProtocol.CONTROL_TIMEOUT_MS)
        }
        CameraProtocol.parseFileInfo(envelope.data)
            ?.let { Result.success(it) }
            ?: throw CameraProtocolException("相机未返回文件信息")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG_CAM, "GetFileInfo failed for ${item.path}: ${e.message}")
        Result.failure(e)
    }

    /**
     * Fetches a preview rendition, falling back Thumbnail → MidThumb, and treats
     * HTTP 204/404 as "this rendition is unavailable" (expected for RAW DNG
     * entries) instead of a fatal error.
     */
    private suspend fun fetchPreviewBytes(
        item: GalleryItem,
        resolution: DownloadResolution,
    ): ByteArray {
        val requested = resolution
        try {
            return withCameraGate {
                client.getBytes(CameraJson.getFile(item.path, item.dateWire, requested.wire))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requested == DownloadResolution.THUMBNAIL && isRenditionUnavailable(e)) {
                return withCameraGate {
                    client.getBytes(
                        CameraJson.getFile(
                            item.path,
                            item.dateWire,
                            DownloadResolution.MID_THUMB.wire,
                        ),
                        CameraProtocol.TRANSFER_TIMEOUT_MS,
                    )
                }
            }
            throw e
        }
    }

    private fun isRenditionUnavailable(error: Throwable): Boolean = when (error) {
        is CameraApiException -> error.code == CameraProtocol.CODE_NO_THUMBNAIL || error.code == 404
        is IOException -> error.message?.contains("HTTP 404") == true ||
            error.message?.contains("HTTP 204") == true
        else -> false
    }

    override suspend fun delete(items: List<GalleryItem>): Result<Unit> = try {
        val paths = items.map { it.path }.filter { it.isNotBlank() }
        if (paths.isEmpty()) {
            Result.success(Unit)
        } else {
            val tooLong = paths.firstOrNull { it.length > CameraProtocol.MAX_DELETE_PATH_LENGTH }
            if (tooLong != null) {
                throw CameraPlatformException("相机路径过长，无法删除：$tooLong")
            }
            withCameraGate {
                executeWithRetry(CameraJson.deleteFile(paths), CameraProtocol.TRANSFER_TIMEOUT_MS)
            }
            Result.success(Unit)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun checkFirmware(): Result<String?> = try {
        // CheckPreUpdate is a cloud-update probe; the official cloud service is
        // gone, so this is display-only and often returns just {"code":200}.
        val envelope = withCameraGate {
            executeWithRetry(CameraJson.checkPreUpdate(), CameraProtocol.TRANSFER_TIMEOUT_MS)
        }
        Result.success(envelope.dataOrNull)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    // ------------------------------------------------------------------ internals

    private suspend fun <T> withCameraGate(block: suspend () -> T): T {
        requestGate.lock()
        try {
            return block()
        } finally {
            requestGate.unlock()
        }
    }

    /**
     * Runs one command and recovers from the camera's serialization error
     * (`code:1515`, "rc only one").
     *
     * The camera allows a single remote-control client and rejects every non-RC
     * command while an RC session is active. That happens legitimately (we are in
     * Remote mode) and also after a previous app process died mid-session. Retry
     * briefly; for non-RC commands additionally ask the camera to drop the stale
     * session once, then retry.
     */
    private suspend fun executeWithRetry(command: String, timeoutMillis: Int): CameraEnvelope {
        val isRcCommand = command.contains("\"command\":\"RC")
        var attempt = 0
        var staleSessionCleared = false
        while (true) {
            val body = try {
                client.getText(command, timeoutMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG_CAM, "HTTP request failed: cmd=${command.take(160)} error=${e.message}")
                throw e
            }
            val envelope = CameraProtocol.parseEnvelope(body)
            if (envelope.code != CameraProtocol.CODE_BUSY) {
                return CameraProtocol.requireSuccess(envelope)
            }
            Log.w(TAG_CAM, "camera busy(1515) cmd=${command.take(90)} attempt=$attempt")
            if (!isRcCommand && !staleSessionCleared) {
                staleSessionCleared = true
                try {
                    client.getText(CameraJson.rcStopRemoteCtl(), CameraProtocol.CONTROL_TIMEOUT_MS)
                    Log.i(TAG_CAM, "cleared stale RC session")
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // best effort only
                }
            }
            if (attempt >= BUSY_RETRY_LIMIT) {
                return CameraProtocol.requireSuccess(envelope)
            }
            attempt++
            delay(BUSY_RETRY_DELAY_MS)
        }
    }

    private fun requireFetchablePath(path: String) {
        if (path.isBlank()) throw CameraPlatformException("文件路径为空")
        if (path.length > CameraProtocol.MAX_GET_FILE_PATH_LENGTH) {
            throw CameraPlatformException(
                "相机路径超过 ${CameraProtocol.MAX_GET_FILE_PATH_LENGTH} 字符，固件无法获取该文件：$path",
            )
        }
    }

    private fun friendlyError(error: Throwable): String = when (error) {
        is CameraApiException -> error.message ?: "相机错误码 ${error.code}"
        is CameraProtocolException -> error.message ?: "相机响应格式异常"
        is CameraPlatformException -> error.message ?: "相机操作失败"
        is SocketTimeoutException -> "连接相机超时，请确认手机已连接相机 Wi-Fi"
        is UnknownHostException -> "找不到相机地址 $baseUrl"
        is IOException -> "无法连接相机（$baseUrl）：${error.message ?: "网络错误"}"
        else -> "相机操作失败：${error.message ?: error.javaClass.simpleName}"
    }

    companion object {
        /** Logcat tag for live-view startup diagnostics. */
        private const val TAG_LV = "YIM1-LiveView"

        /** Logcat tag for HTTP command / remote-control session diagnostics. */
        private const val TAG_CAM = "YIM1-Camera"

        /** How long stopLiveView waits for the request gate before a direct stop. */
        private const val STOP_CONTROL_WAIT_MS = 2_500L

        /** The camera drops large HTTP connections occasionally; retry the download. */
        private const val DOWNLOAD_ATTEMPTS = 3
        private const val DOWNLOAD_RETRY_DELAY_MS = 800L

        /** Connection-health probe cadence (verified recommendation: ~5 s). */
        private const val STATUS_POLL_INTERVAL_MS = 5_000L

        /** Skip the HTTP status probe while live-view frames are this fresh. */
        private const val LIVE_STATUS_FRESH_MS = 3_000L

        /** Parse the full state JSON every N-th live frame (~30 fps streams). */
        private const val LIVE_STATUS_PARSE_STRIDE = 4L

        /** Three consecutive health-probe failures mark the connection lost. */
        private const val MAX_POLL_FAILURES = 3

        /** Connect-time warm-up: number of status probes and delays between them. */
        private const val STATUS_PROBE_ATTEMPTS = 3
        private const val STATUS_PROBE_RETRY_DELAY_MS = 400L
        private const val STATUS_WARMUP_DELAY_MS = 300L

        /** Short pre-warm request used before entering remote/live-view mode. */
        private const val PREWARM_TIMEOUT_MS = 3_000

        /** Focus diagnostics tag (hardware tap-to-focus troubleshooting). */
        private const val TAG_FOCUS = "YI-M1-Focus"

        private const val BUSY_RETRY_LIMIT = 8
        private const val BUSY_RETRY_DELAY_MS = 200L
        private const val CANCEL_FALLBACK_TIMEOUT_MS = 60_000

        /** How long a user-chosen ImageAspect overrides the unreliable camera echo. */
        private const val IMAGE_ASPECT_STICKY_MS = 10_000L

        /**
         * Builds the production repository with the persisted camera address.
         * This is the factory the ViewModel uses (`CameraRepositoryImpl.create(context)`).
         */
        @JvmStatic
        fun create(context: Context): CameraRepositoryImpl {
            val appContext = context.applicationContext
            // The effective URL is resolved by the repository on every connect()
            // (persisted setting or per-session override) and pushed into the client.
            val client = HttpCameraClient(CameraConfig.DEFAULT_BASE_URL)
            return CameraRepositoryImpl(appContext, client, LiveViewReceiver())
        }
    }
}

/** Error raised by the repository itself (not by the camera protocol). */
class CameraPlatformException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
