package com.yim1remote.app.data

import android.graphics.Bitmap
import com.yim1remote.app.data.model.CameraFileInfo
import com.yim1remote.app.data.model.CameraFileType
import com.yim1remote.app.data.model.CameraSetting
import com.yim1remote.app.data.model.CameraStatus
import com.yim1remote.app.data.model.DownloadResolution
import com.yim1remote.app.data.model.ExposureMode
import com.yim1remote.app.data.model.GalleryItem
import java.io.OutputStream
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single entry point the UI uses to talk to the camera.
 *
 * Implemented by [CameraRepositoryImpl] on top of the YI M1 local HTTP API
 * (`GET http://<camera>/?data={...}`) plus the UDP live-view stream on port
 * 54321. All methods are safe to call from the main thread: they suspend and
 * switch to `Dispatchers.IO` internally.
 */
interface CameraRepository {

    /** Observable connection state, driven by [connect]/[disconnect] and failures. */
    val connectionState: StateFlow<ConnectionState>

    /** Last known full camera state, or null while disconnected. */
    val status: StateFlow<CameraStatus?>

    /** Latest live-view frame (null until the first frame arrives). */
    val liveFrame: StateFlow<LiveFrame?>

    /** Non-fatal messages worth surfacing as a snackbar (e.g. frame drops). */
    val messages: SharedFlow<String>

    /** Base URL currently in use, e.g. `http://192.168.0.10`. */
    val baseUrl: String

    /**
     * Probes the camera and starts the status poller.
     *
     * @param baseUrlOverride optional address to use for this session (mock server);
     *        when null the persisted address from [CameraConfigStore] is used.
     */
    suspend fun connect(baseUrlOverride: String? = null): Result<Unit>

    /** Stops live view and polling, then marks the repository disconnected. */
    suspend fun disconnect()

    /** One-shot status refresh (also used by pull-to-refresh). */
    suspend fun refreshStatus(): Result<CameraStatus>

    /** Sends `RCSwitchDialMode`. */
    suspend fun setExposureMode(mode: ExposureMode): Result<Unit>

    /** Sends the matching `RC*Set` command for [setting]. */
    suspend fun setSetting(setting: CameraSetting, wireValue: String): Result<Unit>

    /** Sends `RCDoShooting` and waits for the camera to finish (long timeout). */
    suspend fun capture(): Result<Unit>

    /** Sends `RCCancelShooting` (best effort, e.g. on back press during a long exposure). */
    suspend fun cancelCapture(): Result<Unit>

    /**
     * Sends `RCDoFocus`. [x]/[y] are normalized 0..1 coordinates of the tapped
     * point inside the live-view image; the camera wants screen pixel strings,
     * which the implementation derives from the last frame size.
     */
    suspend fun focusAt(normalizedX: Float, normalizedY: Float): Result<Unit>

    /** Sends `RCDoFocus` in `Auto` area mode; returns the area the camera picked. */
    suspend fun focusAuto(): Result<FocusPoint>

    /** Sends `RCMFAdjust` with a signed step count. */
    suspend fun adjustManualFocus(operation: Int): Result<Unit>

    /** Sends `StartLiveview` and opens the UDP receiver if it is not running. */
    suspend fun startLiveView(): Result<Unit>

    /** Stops the UDP receiver (camera keeps streaming until next page change). */
    suspend fun stopLiveView()

    /** Loads one page (60 items) of the camera gallery, newest first. */
    suspend fun loadGalleryPage(page: Int): Result<List<GalleryItem>>

    /**
     * Streams a file from the camera into [sink].
     *
     * @param progress callback with (bytesCopied, totalBytesOrNull).
     */
    suspend fun download(
        item: GalleryItem,
        resolution: DownloadResolution = DownloadResolution.ORIGINAL,
        sink: OutputStream,
        progress: (Long, Long?) -> Unit = { _, _ -> },
    ): Result<Long>

    /** Decodes one thumbnail/preview JPEG from the camera into a [Bitmap]. */
    suspend fun loadPreview(item: GalleryItem, resolution: DownloadResolution): Result<Bitmap>

    /**
     * Loads display metadata for one camera file (`GetFileInfo`): size,
     * pixel dimensions, capture date and EXIF-style shooting parameters.
     * Display-only; failures are non-fatal.
     */
    suspend fun loadFileInfo(item: GalleryItem): Result<CameraFileInfo>

    /** Sends `DeleteFile` for all [items] (protected files are filtered by the UI). */
    suspend fun delete(items: List<GalleryItem>): Result<Unit>

    /** Requests firmware information (`CheckPreUpdate`) — display only, updates are out of scope. */
    suspend fun checkFirmware(): Result<String?>
}

/** Connection lifecycle of the camera link. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val status: CameraStatus) : ConnectionState
    data class Failed(val message: String, val cause: Throwable? = null) : ConnectionState
}

/**
 * One decoded live-view frame plus the camera parameters that travelled in the
 * fixed 2048-byte block in front of the JPEG.
 */
data class LiveFrame(
    val bitmap: Bitmap,
    val status: CameraStatus? = null,
    val width: Int = bitmap.width,
    val height: Int = bitmap.height,
    val receivedAtMillis: Long = System.currentTimeMillis(),
)
