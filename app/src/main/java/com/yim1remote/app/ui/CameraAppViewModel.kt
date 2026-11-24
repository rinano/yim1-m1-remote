package com.yim1remote.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yim1remote.app.data.CameraConfig
import com.yim1remote.app.data.CameraProtocol
import com.yim1remote.app.data.CameraConfigStore
import com.yim1remote.app.data.CameraRepository
import com.yim1remote.app.data.ConnectionState
import com.yim1remote.app.data.LiveFrame
import com.yim1remote.app.data.ble.BleDeviceInfo
import com.yim1remote.app.data.model.CameraFileInfo
import com.yim1remote.app.data.model.CameraOptions
import com.yim1remote.app.data.model.CameraSetting
import com.yim1remote.app.data.model.CameraStatus
import com.yim1remote.app.data.model.DownloadResolution
import com.yim1remote.app.data.model.ExposureMode
import com.yim1remote.app.data.model.GalleryItem
import com.yim1remote.app.data.model.GalleryPaging
import com.yim1remote.app.ui.ble.BleProvisioningController
import com.yim1remote.app.ui.ble.BleUiEvent
import com.yim1remote.app.ui.ble.BleUiState
import com.yim1remote.app.ui.media.DownloadTarget
import com.yim1remote.app.ui.media.DownloadTargets
import com.yim1remote.app.ui.nav.HomeTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

/**
 * Single ViewModel behind all three tabs.
 *
 * It owns the [CameraRepository] session (created through
 * [CameraRepositoryProvider]), the selected tab, the live-view lifecycle, the
 * camera-gallery paging/selection state and the download queue. Composables only
 * collect its `StateFlow`s and call its methods — no composable touches the
 * repository directly, so rotation/backgrounding never loses a running capture
 * or an in-flight download.
 *
 * It is an [AndroidViewModel] only because the repository and the MediaStore
 * download helper need an application `Context`; no `Activity`/`View` reference
 * is held anywhere.
 */
class CameraAppViewModel(application: Application) : AndroidViewModel(application) {

    private val configStore = CameraConfigStore(application)
    private val repository: CameraRepository = CameraRepositoryProvider.create(application)

    // ------------------------------------------------------------------ config

    private val _config = MutableStateFlow(configStore.load())
    val config: StateFlow<CameraConfig> = _config.asStateFlow()

    // ------------------------------------------------------------- camera state

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val cameraStatus: StateFlow<CameraStatus?> = repository.status
    val liveFrame: StateFlow<LiveFrame?> = repository.liveFrame
    val messages: SharedFlow<String> = repository.messages

    // --------------------------------------------------------------------- tabs

    private val _selectedTab = MutableStateFlow(HomeTab.CONNECT)
    val selectedTab: StateFlow<HomeTab> = _selectedTab.asStateFlow()

    // ------------------------------------------------------------------ capture

    private val _capture = MutableStateFlow(CaptureUiState())
    val capture: StateFlow<CaptureUiState> = _capture.asStateFlow()

    // ---------------------------------------------------------------- live view

    private val _liveView = MutableStateFlow(LiveViewUiState())
    val liveView: StateFlow<LiveViewUiState> = _liveView.asStateFlow()
    private val liveRetry = MutableStateFlow(0)
    private val foreground = MutableStateFlow(true)

    // --------------------------------------------------------------- focus (A/F)
    private val _focus = MutableStateFlow(FocusUiState())
    val focus: StateFlow<FocusUiState> = _focus.asStateFlow()
    private var focusJob: Job? = null
    private var focusResetJob: Job? = null

    // ------------------------------------------------------------------- gallery

    private val _gallery = MutableStateFlow(GalleryUiState())
    val gallery: StateFlow<GalleryUiState> = _gallery.asStateFlow()
    private var galleryJob: Job? = null
    private var loadMoreJob: Job? = null
    private var galleryPage = 0

    /** True when the camera SD card changed (e.g. after a capture) and the list needs a reload. */
    private var galleryStale = false

    private val _lastCapture = MutableStateFlow<GalleryItem?>(null)
    val lastCapture: StateFlow<GalleryItem?> = _lastCapture.asStateFlow()

    // ----------------------------------------------------------------- downloads

    private val _downloads = MutableStateFlow(DownloadUiState())
    val downloads: StateFlow<DownloadUiState> = _downloads.asStateFlow()
    private var downloadJob: Job? = null

    // ------------------------------------------------------------- UI messages

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    // ------------------------------------------------------- BLE provisioning UI

    /**
     * BLE pairing/credential glue for the 连接 screen. The tested `data/ble`
     * module does the protocol work; this controller owns it for the app process
     * and is closed in [onCleared].
     */
    private val bleController = BleProvisioningController(
        context = application.applicationContext,
        scope = viewModelScope,
    ) { bleEvent ->
        viewModelScope.launch {
            when (bleEvent) {
                is BleUiEvent.WifiReady -> _events.emit(UiEvent.BleWifiReady(bleEvent.ssid))
                BleUiEvent.Copied -> _events.emit(UiEvent.BleCopied)
                is BleUiEvent.DeviceRemembered -> {
                    configStore.saveRememberedBleDevice(bleEvent.device)
                }
                BleUiEvent.TimeSynced -> _events.emit(UiEvent.CameraTimeSynced)
                is BleUiEvent.TimeSyncFailed -> _events.emit(UiEvent.CameraTimeSyncFailed(bleEvent.message))
            }
        }
    }

    /** 蓝牙自动配网 card state. */
    val bleState: StateFlow<BleUiState> = bleController.state

    // ------------------------------------------------------------- thumbnails

    private val thumbnailCache = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Small cache of `GetFileInfo` metadata (viewer/detail panel re-open). */
    private val fileInfoCache = LruCache<String, CameraFileInfo>(FILE_INFO_CACHE_ENTRIES)
    private val thumbnailMutex = Mutex()
    private val thumbnailInFlight = mutableMapOf<String, Deferred<Bitmap?>>()

    init {
        bleController.seedRememberedDevice(configStore.loadRememberedBleDevice())
        observeLiveViewLifecycle()
        observeLiveViewHealth()
    }

    // =====================================================================
    // BLE auto-provisioning (连接 tab card)
    // =====================================================================

    /** Re-reads BLE availability/permissions (screen entry, resume from system settings). */
    fun bleRefreshEnvironment() = bleController.refreshEnvironment()

    /** Missing runtime permissions for the permission launcher. */
    fun bleMissingPermissions(): List<String> = bleController.missingPermissions()

    /** Result of the runtime permission request; auto-starts a scan when granted. */
    fun bleOnPermissionResult() = bleController.onPermissionResult()

    /** Start an LE scan for the camera. */
    fun bleScan() = bleController.startScan()

    /** Pair + read SSID/password for [device] (camera screen confirmation required). */
    fun bleProvision(device: BleDeviceInfo) = bleController.provision(device)

    /** Cancel an in-flight scan or provisioning attempt. */
    fun bleCancel() = bleController.cancel()

    fun bleRetry() = bleController.retry()

    fun bleTogglePasswordReveal() = bleController.togglePasswordReveal()

    /** Called by the card after copying SSID/password; shows the snackbar. */
    fun bleNotifyCopied() = bleController.notifyCopied()

    /** Manual "sync camera clock" action (camera has no RTC; BLE write 41106dac). */
    fun bleSyncTimeNow() = bleController.syncTime()

    /** True when the time-sync button can be attempted (known camera + BLE ready). */
    fun bleCanSyncTime(): Boolean = bleController.canSyncTime()

    /** Reads the body firmware/region/lens firmware over BLE (41106da2). */
    fun bleRefreshIdentity() = bleController.refreshIdentity()

    // =====================================================================
    // Configuration
    // =====================================================================

    /** Normalizes, persists and publishes a new camera base URL; disconnects if needed. */
    fun updateBaseUrl(raw: String) {
        if (!CameraConfig.isValidBaseUrl(raw)) return
        val updated = CameraConfig(CameraConfig.normalizeBaseUrl(raw))
        configStore.save(updated)
        _config.value = updated
        if (connectionState.value !is ConnectionState.Disconnected) {
            viewModelScope.launch { repository.disconnect() }
        }
        viewModelScope.launch { _events.emit(UiEvent.AddressSaved) }
    }

    /** Drops the persisted override and returns to the camera's factory address. */
    fun resetBaseUrlToDefault() {
        configStore.resetToDefault()
        _config.value = CameraConfig()
        viewModelScope.launch { _events.emit(UiEvent.AddressSaved) }
    }

    // =====================================================================
    // Connection
    // =====================================================================

    fun connect() {
        if (connectionState.value is ConnectionState.Connecting) return
        viewModelScope.launch {
            repository.connect().onSuccess {
                _events.emit(UiEvent.Connected)
                autoSyncCameraTimeIfPossible()
                if (_selectedTab.value == HomeTab.GALLERY) {
                    refreshGallery()
                } else {
                    galleryStale = true
                }
            }
            // Failures are surfaced by the Connect tab through connectionState.
        }
    }

    fun disconnect() {
        stopIntervalShoot()
        focusJob?.cancel()
        focusJob = null
        _focus.update { FocusUiState(areaMode = it.areaMode) }
        viewModelScope.launch {
            repository.disconnect()
            _liveView.value = LiveViewUiState()
            _gallery.value = GalleryUiState()
            _capture.value = CaptureUiState()
            galleryPage = 0
            galleryStale = false
            _events.emit(UiEvent.Disconnected)
        }
    }

    // =====================================================================
    // Tabs & app lifecycle
    // =====================================================================

    fun selectTab(tab: HomeTab) {
        if (_selectedTab.value == tab) return
        _selectedTab.value = tab
        if (tab == HomeTab.GALLERY) ensureGalleryLoaded()
    }

    /** Called from MainActivity's lifecycle observer; stops streaming while backgrounded. */
    fun onAppForegroundChanged(isForeground: Boolean) {
        if (foreground.value == isForeground) return
        foreground.value = isForeground
        // Safety: leave interval/BKT running only while this screen is in front;
        // shots must never be fired from the background without the operator.
        if (!isForeground) stopIntervalShoot()
    }

    /**
     * Starts streaming only while the Remote tab is visible, the camera is
     * connected and the app is foregrounded; stops the UDP receiver otherwise.
     */
    private fun observeLiveViewLifecycle() {
        viewModelScope.launch {
            combine(
                _selectedTab,
                repository.connectionState,
                foreground,
                liveRetry,
            ) { tab, connection, fg, retry ->
                LiveStreamRequest(
                    shouldStream = tab == HomeTab.REMOTE &&
                        connection is ConnectionState.Connected &&
                        fg,
                    retry = retry,
                )
            }
                .distinctUntilChanged()
                .collectLatest { request ->
                    if (request.shouldStream) {
                        streamLiveView()
                    } else {
                        stopLiveViewInternal()
                    }
                }
        }
    }

    private suspend fun streamLiveView() {
        _liveView.value = LiveViewUiState(active = true, starting = true)
        var started = false
        try {
            val result = repository.startLiveView()
            if (result.isFailure) {
                _liveView.value = LiveViewUiState(
                    active = false,
                    starting = false,
                    error = result.exceptionOrNull()?.message,
                )
                return
            }
            started = true
            _liveView.update { it.copy(active = true, starting = false, error = null) }
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                if (started) repository.stopLiveView()
            }
            _liveView.value = LiveViewUiState()
        }
    }

    private suspend fun stopLiveViewInternal() {
        withContext(NonCancellable) { repository.stopLiveView() }
        if (_liveView.value != LiveViewUiState()) _liveView.value = LiveViewUiState()
    }

    /** User pressed the "retry stream" button after a stalled/blank live view. */
    fun retryLiveView() {
        liveRetry.update { it + 1 }
    }

    /** Warns when no live-view frame arrived for [LIVE_STALL_MS] (frozen/blank feed). */
    private fun observeLiveViewHealth() {
        viewModelScope.launch {
            while (isActive) {
                delay(HEALTH_TICK_MS)
                val current = _liveView.value
                if (!current.active) {
                    if (current.stalled || current.lastFrameAtMillis != null) {
                        _liveView.update { it.copy(stalled = false, lastFrameAtMillis = null) }
                    }
                    continue
                }
                val last = repository.liveFrame.value?.receivedAtMillis
                val stalled = last == null || System.currentTimeMillis() - last > LIVE_STALL_MS
                if (current.stalled != stalled || current.lastFrameAtMillis != last) {
                    _liveView.update { it.copy(stalled = stalled, lastFrameAtMillis = last) }
                }
            }
        }
    }

    // =====================================================================
    // Capture & focus
    // =====================================================================

    fun capture() {
        if (_capture.value.capturing) return
        if (connectionState.value !is ConnectionState.Connected) return
        viewModelScope.launch {
            _capture.value = CaptureUiState(capturing = true)
            val result = try {
                repository.capture()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.failure(t)
            }
            _capture.value = CaptureUiState(capturing = false)
            if (result.isSuccess) {
                galleryStale = true
                _events.emit(UiEvent.CaptureSucceeded)
                launch { repository.refreshStatus() }
                launch { reloadLastCapture() }
            } else {
                _events.emit(UiEvent.CaptureFailed(result.exceptionOrNull()?.message))
            }
        }
    }

    /**
     * Captures one photo and returns the newest gallery item (A1 exposure check).
     *
     * Used by the M-mode "试拍检查" panel: the live view does not simulate
     * exposure on this camera, so the app takes a real frame, downloads its
     * MidThumb preview and analyses that instead.
     */
    suspend fun captureForExposureCheck(): Result<GalleryItem?> {
        if (connectionState.value !is ConnectionState.Connected) {
            return Result.failure(IllegalStateException("相机未连接"))
        }
        _capture.value = CaptureUiState(capturing = true)
        val captured = try {
            repository.capture()
        } catch (cancellation: CancellationException) {
            _capture.value = CaptureUiState(capturing = false)
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
        _capture.value = CaptureUiState(capturing = false)
        if (captured.isFailure) return Result.failure(captured.exceptionOrNull()!!)
        galleryStale = true
        repository.refreshStatus()
        val newest = repository.loadGalleryPage(0).getOrNull()?.firstOrNull()
        if (newest != null) {
            _lastCapture.value = newest
            loadThumbnail(newest)
        }
        return Result.success(newest)
    }

    /** Preview bitmap for the exposure-check panel (camera MidThumb thumbnail). */
    suspend fun previewForExposureCheck(item: GalleryItem): Bitmap? =
        loadViewerPreview(item, DownloadResolution.MID_THUMB)

    // =====================================================================
    // Interval shooting / exposure bracketing (A4, app-side timer)
    // =====================================================================

    /** State of the app-side interval / exposure-bracket sequence. */
    private val _interval = MutableStateFlow(IntervalShootUiState())
    val interval: StateFlow<IntervalShootUiState> = _interval.asStateFlow()
    private var intervalJob: Job? = null

    /**
     * Starts an app-side sequence: the camera's own shot-count/interval command
     * (`RCDShootCntSet`) is not accepted on 3.1-CN hardware (HTTP 404), so the
     * timer and the per-shot EV changes live entirely in the phone.
     */
    fun startIntervalShoot(config: IntervalShootConfig) {
        if (intervalJob?.isActive == true) return
        if (connectionState.value !is ConnectionState.Connected) {
            _interval.value = IntervalShootUiState(error = "相机未连接")
            return
        }
        if (_capture.value.capturing) return
        intervalJob = viewModelScope.launch { runIntervalShoot(config) }
    }

    /** Stops the sequence after the current shot (or immediately while waiting). */
    fun stopIntervalShoot() {
        intervalJob?.cancel()
        intervalJob = null
        _interval.update { it.copy(active = false, waitingSeconds = 0) }
    }

    private suspend fun runIntervalShoot(config: IntervalShootConfig) {
        val shots = config.shots.coerceIn(2, 30)
        val intervalMillis = config.intervalSeconds.coerceIn(1, 60) * 1_000L
        val baseEvWire = cameraStatus.value?.ev?.takeIf { it.isNotBlank() } ?: "0.0"
        val baseEv = baseEvWire.toDoubleOrNull() ?: 0.0
        var completed = 0
        var failure: String? = null
        _interval.value = IntervalShootUiState(
            active = true,
            mode = config.mode,
            total = shots,
            shot = 0,
            evStepTenths = config.evStepTenths,
        )
        try {
            for (index in 1..shots) {
                if (connectionState.value !is ConnectionState.Connected) {
                    failure = "相机已断开"
                    break
                }
                if (config.mode == IntervalShootMode.EV_BRACKET) {
                    // The camera only accepts exact EV values from its own option table
                    // (…-0.7, -0.3, 0.0, 0.3, 0.7, 1.0…). Sending an in-between value such as
                    // 0.6 made the web server answer HTTP 404. Bracket in option-index space.
                    val evWires = CameraOptions.ev.map { it.wire }
                    val baseIndex = evWires.indexOf(baseEvWire)
                        .takeIf { it >= 0 }
                        ?: nearestEvIndex(evWires, baseEv)
                    val offsetIndex = bracketOffsetIndex(
                        shot = index,
                        shots = shots,
                        stepOptions = evStepOptionCount(config.evStepTenths),
                    )
                    val evIndex = (baseIndex + offsetIndex).coerceIn(evWires.indices)
                    val wire = evWires[evIndex]
                    val applied = repository.setSetting(CameraSetting.EV, wire)
                    if (applied.isFailure) {
                        failure = "设置 EV($wire) 失败：" +
                            intervalErrorMessage(applied.exceptionOrNull())
                        break
                    }
                    delay(INTERVAL_SETTLE_MS)
                }
                _interval.update { it.copy(shot = index, waitingSeconds = 0, error = null) }
                val captured = repository.capture()
                if (captured.isFailure) {
                    failure = "第 $index 张拍摄失败：" +
                        intervalErrorMessage(captured.exceptionOrNull())
                    break
                }
                completed = index
                galleryStale = true
                runCatching { repository.refreshStatus() }
                repository.loadGalleryPage(0).getOrNull()?.firstOrNull()?.let { newest ->
                    _lastCapture.value = newest
                    loadThumbnail(newest)
                }
                _interval.update { it.copy(shot = completed) }
                if (index < shots) {
                    var remaining = intervalMillis
                    while (remaining > 0) {
                        currentCoroutineContext().ensureActive()
                        val step = minOf(250L, remaining)
                        delay(step)
                        remaining -= step
                        _interval.update {
                            it.copy(waitingSeconds = ((remaining + 999) / 1_000).toInt())
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            failure = "已停止"
            throw cancellation
        } finally {
            if (config.mode == IntervalShootMode.EV_BRACKET) {
                runCatching { repository.setSetting(CameraSetting.EV, baseEvWire) }
            }
            _interval.value = IntervalShootUiState(
                active = false,
                mode = config.mode,
                total = shots,
                shot = completed,
                evStepTenths = config.evStepTenths,
                error = failure,
                finished = completed > 0,
            )
            _capture.value = CaptureUiState(capturing = false)
            intervalJob = null
        }
    }

    /** Config EV step (tenths) → number of entries in the camera's EV option table. */
    private fun evStepOptionCount(stepTenths: Int): Int = when {
        stepTenths <= 3 -> 1       // ~1/3 EV
        stepTenths <= 7 -> 2       // ~2/3 EV
        else -> 3                  // ~1 EV
    }

    private fun bracketOffsetIndex(shot: Int, shots: Int, stepOptions: Int): Int {
        val center = (shots - 1) / 2.0
        return ((shot - 1 - center) * stepOptions).roundToInt()
    }

    private fun nearestEvIndex(wires: List<String>, baseEv: Double): Int {
        if (wires.isEmpty()) return 0
        val base = (baseEv * 10).roundToInt()
        var best = 0
        var bestDistance = Int.MAX_VALUE
        wires.forEachIndexed { index, wire ->
            val tenths = (wire.toDoubleOrNull()?.times(10))?.roundToInt() ?: 0
            val distance = kotlin.math.abs(tenths - base)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /** Keeps the interval strip readable when the camera returns an HTML 404 page. */
    private fun intervalErrorMessage(error: Throwable?): String {
        val raw = error?.message ?: "未知错误"
        return if (raw.contains("<html", ignoreCase = true) || raw.contains("HTTP 404", ignoreCase = true)) {
            "相机拒绝了该参数（HTTP 404）"
        } else {
            raw
        }
    }

    /** Best-effort cancel for a long exposure/burst (RCCancelShooting). */
    fun cancelCapture() {
        if (!_capture.value.capturing) return
        viewModelScope.launch { repository.cancelCapture() }
    }

    fun toggleFocusAreaMode() {
        _focus.update {
            it.copy(
                areaMode = if (it.areaMode == FocusAreaMode.POINT) {
                    FocusAreaMode.AUTO
                } else {
                    FocusAreaMode.POINT
                },
            )
        }
    }

    fun focusAt(normalizedX: Float, normalizedY: Float) {
        if (connectionState.value !is ConnectionState.Connected) return
        val nx = normalizedX.coerceIn(0f, 1f)
        val ny = normalizedY.coerceIn(0f, 1f)
        beginFocusVisual(nx to ny)
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            val result = repository.focusAt(nx, ny)
            if (result.isSuccess) {
                markFocused()
            } else {
                markFocusFailed(result.exceptionOrNull()?.message)
            }
        }
    }

    /** Auto-area focus ("自动区域"): the camera chooses and returns the focus point. */
    fun autoFocus() {
        if (connectionState.value !is ConnectionState.Connected) return
        beginFocusVisual(null)
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            val result = repository.focusAuto()
            result
                .onSuccess { point ->
                    val normalized = CameraProtocol.focusPointToNormalized(
                        point,
                        cameraStatus.value?.imageAspect,
                    )
                    _focus.update { it.copy(point = normalized, state = FocusVisualState.FOCUSED) }
                    scheduleFocusReset()
                }
                .onFailure { error -> markFocusFailed(error.message) }
        }
    }

    private fun beginFocusVisual(point: Pair<Float, Float>?) {
        focusResetJob?.cancel()
        _focus.update { current ->
            current.copy(
                point = point ?: current.point,
                state = FocusVisualState.FOCUSING,
            )
        }
    }

    private fun markFocused() {
        _focus.update { it.copy(state = FocusVisualState.FOCUSED) }
        scheduleFocusReset()
    }

    private fun markFocusFailed(message: String?) {
        // No snackbar here: the red reticle already reports the failure (user request).
        // The message is still available in the YI-M1-Focus logcat line.
        _focus.update { it.copy(state = FocusVisualState.FAILED) }
        scheduleFocusReset()
    }

    private fun scheduleFocusReset() {
        focusResetJob?.cancel()
        focusResetJob = viewModelScope.launch {
            delay(2_200)
            _focus.update { it.copy(state = FocusVisualState.IDLE) }
        }
    }

    private suspend fun reloadLastCapture() {
        repository.loadGalleryPage(0).onSuccess { page ->
            page.firstOrNull()?.let { newest ->
                _lastCapture.value = newest
                loadThumbnail(newest)
            }
        }
    }

    // =====================================================================
    // Settings
    // =====================================================================

    fun setExposureMode(mode: ExposureMode) {
        if (connectionState.value !is ConnectionState.Connected) return
        viewModelScope.launch {
            repository.setExposureMode(mode)
                .onFailure { _events.emit(UiEvent.SettingFailed(it.message)) }
        }
    }

    fun applySetting(setting: CameraSetting, wireValue: String) {
        if (connectionState.value !is ConnectionState.Connected) return
        viewModelScope.launch {
            repository.setSetting(setting, wireValue)
                .onFailure { _events.emit(UiEvent.SettingFailed(it.message)) }
        }
    }

    fun adjustManualFocus(step: Int) {
        if (step == 0) return
        if (connectionState.value !is ConnectionState.Connected) return
        viewModelScope.launch {
            repository.adjustManualFocus(step)
                .onFailure { _events.emit(UiEvent.FocusFailed(it.message)) }
        }
    }

    // =====================================================================
    // Gallery
    // =====================================================================

    /** Loads the first page if the tab is opened and the cache is empty/stale. */
    fun ensureGalleryLoaded() {
        if (connectionState.value !is ConnectionState.Connected) return
        val state = _gallery.value
        if (state.refreshing) return
        if (!state.loadedOnce || galleryStale) refreshGallery()
    }

    fun refreshGallery() {
        galleryJob?.cancel()
        galleryJob = viewModelScope.launch {
            _gallery.update { it.copy(refreshing = true, error = null) }
            repository.loadGalleryPage(0)
                .onSuccess { page ->
                    galleryPage = 0
                    galleryStale = false
                    _gallery.update {
                        it.copy(
                            items = page,
                            refreshing = false,
                            loadingMore = false,
                            loadedOnce = true,
                            endReached = page.size < GalleryPaging.PAGE_SIZE,
                            error = null,
                            selectionMode = false,
                            selectedPaths = emptySet(),
                        )
                    }
                    _lastCapture.value = page.firstOrNull()
                }
                .onFailure { error ->
                    _gallery.update {
                        it.copy(
                            refreshing = false,
                            loadedOnce = true,
                            error = error.message,
                        )
                    }
                    _events.emit(UiEvent.GalleryRefreshFailed(error.message))
                }
        }
    }

    /** Appends the next page; called by the grid when the user nears the bottom. */
    fun loadMoreGallery() {
        val state = _gallery.value
        if (!state.loadedOnce || state.refreshing || state.loadingMore || state.endReached) return
        if (state.error != null) return
        if (connectionState.value !is ConnectionState.Connected) return
        if (loadMoreJob?.isActive == true) return
        loadMoreJob = viewModelScope.launch {
            _gallery.update { it.copy(loadingMore = true) }
            val nextPage = galleryPage + 1
            repository.loadGalleryPage(nextPage)
                .onSuccess { page ->
                    galleryPage = nextPage
                    _gallery.update { current ->
                        val known = current.items.mapTo(HashSet()) { it.path }
                        val fresh = page.filter { it.path !in known }
                        current.copy(
                            items = current.items + fresh,
                            loadingMore = false,
                            endReached = page.size < GalleryPaging.PAGE_SIZE,
                        )
                    }
                }
                .onFailure { error ->
                    _gallery.update { it.copy(loadingMore = false, error = error.message) }
                }
        }
    }

    fun enterSelection(item: GalleryItem) {
        _gallery.update {
            it.copy(selectionMode = true, selectedPaths = it.selectedPaths + item.path)
        }
    }

    fun toggleSelection(item: GalleryItem) {
        _gallery.update { state ->
            val next = if (item.path in state.selectedPaths) {
                state.selectedPaths - item.path
            } else {
                state.selectedPaths + item.path
            }
            state.copy(selectedPaths = next)
        }
    }

    fun selectAll() {
        _gallery.update { state ->
            state.copy(selectedPaths = state.items.mapTo(HashSet()) { it.path })
        }
    }

    fun clearSelection() {
        _gallery.update { it.copy(selectedPaths = emptySet()) }
    }

    fun exitSelection() {
        _gallery.update { it.copy(selectionMode = false, selectedPaths = emptySet()) }
    }

    fun selectedItems(): List<GalleryItem> {
        val state = _gallery.value
        return state.items.filter { it.path in state.selectedPaths }
    }

    // =====================================================================
    // Delete
    // =====================================================================

    fun deleteItems(items: List<GalleryItem>) {
        val deletable = items.filterNot { it.isProtected }
        val skipped = items.size - deletable.size
        if (deletable.isEmpty()) {
            viewModelScope.launch { _events.emit(UiEvent.DeleteFinished(0, skipped)) }
            return
        }
        viewModelScope.launch {
            val result = try {
                repository.delete(deletable)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.failure(t)
            }
            result
                .onSuccess {
                    val deletedPaths = deletable.mapTo(HashSet()) { it.path }
                    _gallery.update { state ->
                        state.copy(
                            items = state.items.filterNot { it.path in deletedPaths },
                            selectionMode = false,
                            selectedPaths = emptySet(),
                        )
                    }
                    galleryStale = true
                    _events.emit(UiEvent.DeleteFinished(deletable.size, skipped))
                }
                .onFailure { _events.emit(UiEvent.DeleteFailed(it.message)) }
        }
    }

    // =====================================================================
    // Downloads
    // =====================================================================

    /**
     * Downloads [items] sequentially into the phone gallery (MediaStore on
     * Android 10+, app-specific pictures dir on API 28) with progress reported
     * through [downloads]. Videos/RAW files are saved as their original bytes.
     */
    fun downloadItems(items: List<GalleryItem>) {
        if (items.isEmpty()) return
        if (downloadJob?.isActive == true) return
        val context = getApplication<Application>()
        downloadJob = viewModelScope.launch {
            var saved = 0
            var failed = 0
            _downloads.value = DownloadUiState(
                active = true,
                totalCount = items.size,
                currentIndex = 0,
                currentName = items.first().fileName,
            )
            try {
                for ((index, item) in items.withIndex()) {
                    if (!isActive) return@launch
                    _downloads.update {
                        it.copy(
                            currentIndex = index,
                            currentName = item.fileName,
                            bytesCopied = 0,
                            totalBytes = null,
                        )
                    }
                    val target: DownloadTarget = try {
                        // MediaStore row creation / file creation does disk+DB work.
                        withContext(Dispatchers.IO) { DownloadTargets.open(context, item) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (t: Throwable) {
                        failed++
                        continue
                    }
                    var success = false
                    try {
                        val result = repository.download(
                            item = item,
                            resolution = DownloadResolution.ORIGINAL,
                            sink = target.sink,
                            progress = { copied, total ->
                                _downloads.update { state ->
                                    state.copy(bytesCopied = copied, totalBytes = total)
                                }
                            },
                        )
                        if (result.isSuccess) {
                            withContext(Dispatchers.IO) { target.commit() }
                            success = true
                            saved++
                        } else {
                            failed++
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (t: Throwable) {
                        failed++
                    } finally {
                        if (!success) withContext(NonCancellable + Dispatchers.IO) { target.abort() }
                    }
                }
                if (items.size == 1 && saved == 1) {
                    _events.emit(UiEvent.DownloadSaved(items.first().fileName))
                } else {
                    _events.emit(UiEvent.DownloadFinished(saved, failed))
                }
            } catch (cancellation: CancellationException) {
                withContext(NonCancellable) { _events.emit(UiEvent.DownloadCancelled) }
                throw cancellation
            } finally {
                _downloads.value = DownloadUiState()
            }
        }
    }

    fun downloadItem(item: GalleryItem) = downloadItems(listOf(item))

    fun downloadSelection() = downloadItems(selectedItems())

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    // =====================================================================
    // Thumbnails / previews
    // =====================================================================

    /** Already-decoded grid thumbnail, if cached (safe to call during composition). */
    fun cachedThumbnail(item: GalleryItem): Bitmap? = thumbnailCache.get(thumbnailKey(item))

    /**
     * Loads (or joins an in-flight load of) one grid thumbnail. Runs on the IO
     * pool inside the repository; a single request is shared by all cells that
     * need the same item.
     */
    suspend fun loadThumbnail(item: GalleryItem): Bitmap? {
        val key = thumbnailKey(item)
        thumbnailCache.get(key)?.let { return it }
        val deferred = thumbnailMutex.withLock {
            thumbnailInFlight[key] ?: viewModelScope.async(Dispatchers.IO) {
                repository.loadPreview(item, DownloadResolution.THUMBNAIL).getOrNull()
            }.also { thumbnailInFlight[key] = it }
        }
        val bitmap = try {
            deferred.await()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            null
        } finally {
            thumbnailMutex.withLock {
                if (thumbnailInFlight[key] === deferred) thumbnailInFlight.remove(key)
            }
        }
        if (bitmap != null) thumbnailCache.put(key, bitmap)
        return bitmap
    }

    /**
     * Loads one preview at the requested resolution for the full-screen viewer.
     * Never throws: a decode/size failure simply leaves the previous preview on
     * screen (e.g. a 50MP original that does not fit in memory).
     */
    suspend fun loadViewerPreview(item: GalleryItem, resolution: DownloadResolution): Bitmap? =
        try {
            withContext(Dispatchers.IO) {
                repository.loadPreview(item, resolution).getOrNull()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            null
        }

    private fun thumbnailKey(item: GalleryItem): String =
        "thumb:${item.path}:${item.dateWire}"

    // =====================================================================
    // Camera file metadata (EXIF panel)
    // =====================================================================

    /**
     * Loads `GetFileInfo` metadata for the viewer/detail panel. Cached per path;
     * failures return null (the panel simply falls back to the listing data).
     */
    suspend fun loadFileInfo(item: GalleryItem): CameraFileInfo? {
        val key = "info:${item.path}"
        fileInfoCache.get(key)?.let { return it }
        val info = try {
            withContext(Dispatchers.IO) { repository.loadFileInfo(item).getOrNull() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            null
        }
        if (info != null) fileInfoCache.put(key, info)
        return info
    }

    /**
     * Silent BLE clock sync after a successful HTTP connect. The camera has no RTC
     * and loses its clock on power-off, so this keeps photo timestamps sane. It is
     * best-effort: no permissions/no remembered camera/no BLE → do nothing.
     */
    private fun autoSyncCameraTimeIfPossible() {
        val device = bleController.rememberedDevice() ?: configStore.loadRememberedBleDevice()
            ?: return
        if (bleController.shouldAutoSyncTime(device)) {
            bleController.syncTime(device, auto = true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        intervalJob?.cancel()
        intervalJob = null
        focusJob?.cancel()
        focusJob = null
        focusResetJob?.cancel()
        focusResetJob = null
        thumbnailCache.evictAll()
        fileInfoCache.evictAll()
        bleController.close()
        CameraRepositoryProvider.close(repository)
    }

    companion object {
        /** Live view is declared stalled after this long without a frame. */
        const val LIVE_STALL_MS = 3_000L

        private const val HEALTH_TICK_MS = 500L
        private const val THUMBNAIL_CACHE_BYTES = 48 * 1024 * 1024
        private const val FILE_INFO_CACHE_ENTRIES = 64

        /** Pause after applying an EV change before firing the shutter (BKT). */
        private const val INTERVAL_SETTLE_MS = 350L
    }
}

// =========================================================================
// UI state
// =========================================================================

/** Transient capture state for the shutter button. */
data class CaptureUiState(
    val capturing: Boolean = false,
)

/** App-side interval / exposure-bracket sequence (A4). */
enum class IntervalShootMode {
    /** Same exposure every shot, fixed interval. */
    INTERVAL,

    /** Shift EV around the current exposure-compensation value. */
    EV_BRACKET,
}

data class IntervalShootConfig(
    val mode: IntervalShootMode = IntervalShootMode.INTERVAL,
    val shots: Int = 3,
    val intervalSeconds: Int = 3,
    /** EV step in tenths: 3 = 0.3 EV, 7 = 0.7 EV, 10 = 1.0 EV. */
    val evStepTenths: Int = 10,
)

data class IntervalShootUiState(
    val active: Boolean = false,
    val mode: IntervalShootMode = IntervalShootMode.INTERVAL,
    val total: Int = 0,
    val shot: Int = 0,
    val evStepTenths: Int = 10,
    val waitingSeconds: Int = 0,
    val error: String? = null,
    val finished: Boolean = false,
)

/** Tap-to-focus / auto-area focus state, rendered by the live-view reticle. */
enum class FocusAreaMode {
    /** Tap chooses a point; `RCDoFocus Mode=Manual`. */
    POINT,
    /** Camera chooses the area; `RCDoFocus Mode=Auto`. */
    AUTO,
}

enum class FocusVisualState { IDLE, FOCUSING, FOCUSED, FAILED }

data class FocusUiState(
    /** Normalized 0..1 point of the last focus target (null before the first focus). */
    val point: Pair<Float, Float>? = null,
    val state: FocusVisualState = FocusVisualState.IDLE,
    val areaMode: FocusAreaMode = FocusAreaMode.POINT,
)

/** Live-view session health, driving the placeholder/warning overlay. */
data class LiveViewUiState(
    val active: Boolean = false,
    val starting: Boolean = false,
    val stalled: Boolean = false,
    val error: String? = null,
    val lastFrameAtMillis: Long? = null,
)

/** Camera gallery: paging + multi-select state. */
data class GalleryUiState(
    val items: List<GalleryItem> = emptyList(),
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val loadedOnce: Boolean = false,
    val error: String? = null,
    val selectionMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
)

/** Progress of the sequential download queue. */
data class DownloadUiState(
    val active: Boolean = false,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val currentName: String? = null,
    val bytesCopied: Long = 0,
    val totalBytes: Long? = null,
) {
    /** 0..1 when the camera reports a content length, null when unknown. */
    val fraction: Float?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0) return null
            return (bytesCopied.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
}

/** One-shot messages for snackbars; the app shell maps them to Chinese strings. */
sealed interface UiEvent {
    data object Connected : UiEvent
    data object Disconnected : UiEvent
    data object AddressSaved : UiEvent
    data object CaptureSucceeded : UiEvent
    data class CaptureFailed(val message: String?) : UiEvent
    data class SettingFailed(val message: String?) : UiEvent
    data class FocusFailed(val message: String?) : UiEvent
    data class GalleryRefreshFailed(val message: String?) : UiEvent
    data class DownloadSaved(val name: String) : UiEvent
    data class DownloadFinished(val saved: Int, val failed: Int) : UiEvent
    data object DownloadCancelled : UiEvent
    data class DeleteFinished(val deleted: Int, val skippedProtected: Int) : UiEvent
    data class DeleteFailed(val message: String?) : UiEvent

    /** BLE provisioning read the camera Wi-Fi credentials; user must still join the hotspot. */
    data class BleWifiReady(val ssid: String) : UiEvent

    /** SSID/password copied to the clipboard. */
    data object BleCopied : UiEvent

    /** Camera clock synced over BLE. */
    data object CameraTimeSynced : UiEvent

    /** Camera clock sync failed; [message] is user-displayable. */
    data class CameraTimeSyncFailed(val message: String?) : UiEvent
}

private data class LiveStreamRequest(
    val shouldStream: Boolean,
    val retry: Int,
)
