package com.yim1remote.app.ui.ble

import android.content.Context
import com.yim1remote.app.data.CameraConfigStore
import com.yim1remote.app.data.ble.BleDeviceInfo
import com.yim1remote.app.data.ble.CameraIdentity
import com.yim1remote.app.data.ble.BleError
import com.yim1remote.app.data.ble.BleException
import com.yim1remote.app.data.ble.BleProvisioner
import com.yim1remote.app.data.ble.ProvisionState
import com.yim1remote.app.data.ble.isRetryable
import com.yim1remote.app.data.ble.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI-side glue between the tested `data/ble` provisioning module and the 连接
 * screen. Owns the [BleProvisioner] instance (one per app process/ViewModel),
 * the scan/provision coroutines and the [BleUiState] the card renders.
 *
 * Deliberately kept in `ui/` so `data/` stays UI-free; the module itself is not
 * modified. All suspend provisioning work runs on the provisioner's IO
 * dispatcher, so nothing here blocks the main thread.
 *
 * Manual Wi-Fi join is intentional for v1: after a success the user is told to
 * join the hotspot in system Wi-Fi settings and then tap 连接相机 — this class
 * never touches the Wi-Fi network stack.
 */
class BleProvisioningController(
    context: Context,
    private val scope: CoroutineScope,
    private val emitEvent: (BleUiEvent) -> Unit,
) {

    private val provisioner = BleProvisioner(context.applicationContext ?: context)
    private val configStore = CameraConfigStore(context.applicationContext ?: context)

    private val _state = MutableStateFlow(
        BleUiState(
            bluetoothSupported = safe {
                provisioner.isBluetoothSupported()
            } ?: false,
            bluetoothEnabled = safe {
                provisioner.isBluetoothEnabled()
            } ?: false,
            missingPermissions = safeMissingPermissions(),
            identity = configStore.loadCameraIdentity(),
        ),
    )
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private var runningJob: Job? = null
    private var progressJob: Job? = null
    private var timeSyncJob: Job? = null
    private var identityJob: Job? = null

    // ------------------------------------------------------------------ queries

    /** Runtime permissions still missing, for the ActivityResult launcher. */
    fun missingPermissions(): List<String> = safeMissingPermissions()

    // ---------------------------------------------------------------- lifecycle

    /** Re-reads BLE availability/permissions (screen entry, resume from settings). */
    fun refreshEnvironment() {
        val supported = safe { provisioner.isBluetoothSupported() } ?: false
        val missing = if (supported) safeMissingPermissions() else emptyList()
        val enabled = supported && (safe { provisioner.isBluetoothEnabled() } ?: false)
        _state.update { current ->
            // Keep the visible content of an active/meaningful state; environment
            // blockers still take precedence so a stale error cannot hide "蓝牙未开".
            val keepStage = current.stage in KEEP_STAGE_ON_ENV_REFRESH
            val stage = when {
                !supported -> BleUiStage.FAILED
                keepStage -> current.stage
                // Permissions first: without BLUETOOTH_CONNECT the adapter state
                // cannot be read reliably, so a "Bluetooth off" guess would be wrong.
                missing.isNotEmpty() -> BleUiStage.PERMISSIONS
                !enabled -> BleUiStage.BLUETOOTH_OFF
                current.stage == BleUiStage.FAILED -> BleUiStage.FAILED
                else -> BleUiStage.IDLE
            }
            current.copy(
                stage = stage,
                bluetoothSupported = supported,
                bluetoothEnabled = enabled,
                missingPermissions = missing,
                error = when {
                    !supported -> BleError.BluetoothUnsupported
                    stage == BleUiStage.FAILED -> current.error
                    else -> null
                },
            )
        }
    }

    /** Called from the permission launcher; auto-starts a scan once everything is granted. */
    fun onPermissionResult() {
        val missing = safeMissingPermissions()
        _state.update {
            it.copy(missingPermissions = missing, permissionDeniedOnce = missing.isNotEmpty())
        }
        if (missing.isEmpty()) {
            startScan()
        } else {
            refreshEnvironment()
        }
    }

    // -------------------------------------------------------------------- scan

    /** Starts an LE scan; no-op while another scan/provision is running. */
    fun startScan() {
        if (_state.value.isBusy) return
        if (!ensureEnvironmentReady()) return
        cancelRunning()
        _state.update {
            it.copy(
                stage = BleUiStage.SCANNING,
                devices = emptyList(),
                selectedDevice = null,
                credentials = null,
                passwordRevealed = false,
                error = null,
                progressLabel = null,
                awaitingCameraConfirmation = false,
            )
        }
        runningJob = scope.launch {
            try {
                val found = provisioner.findCameras()
                _state.update { current ->
                    current.copy(
                        stage = BleUiStage.DEVICES,
                        devices = found,
                        // Remember a camera-looking device for the one-tap shortcut,
                        // and keep pointing at the last provisioned device when found.
                        rememberedDevice = found.firstOrNull { it.isLikelyCamera }
                            ?: current.rememberedDevice,
                        selectedDevice = current.rememberedDevice?.let { remembered ->
                            found.firstOrNull { it.address == remembered.address }
                        },
                    )
                }
                _state.value.rememberedDevice?.let { emitEvent(BleUiEvent.DeviceRemembered(it)) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                handleFailure(t)
            }
        }
    }

    // ------------------------------------------------------------- provisioning

    /** Runs the full pairing handshake for [device] (camera screen confirmation!). */
    fun provision(device: BleDeviceInfo) {
        if (_state.value.isBusy) return
        if (!ensureEnvironmentReady()) return
        cancelRunning()
        _state.update {
            it.copy(
                stage = BleUiStage.PROVISIONING,
                selectedDevice = device,
                rememberedDevice = device,
                credentials = null,
                passwordRevealed = false,
                error = null,
                progressLabel = null,
                awaitingCameraConfirmation = false,
            )
        }
        // Mirror the module's typed progress labels into the card while pairing runs.
        val progress = scope.launch {
            provisioner.state.collect { moduleState ->
                if (moduleState is ProvisionState.IdentityRead) {
                    configStore.saveCameraIdentity(moduleState.identity)
                    _state.update {
                        it.copy(identity = moduleState.identity, identityError = null)
                    }
                }
                _state.update { current ->
                    current.copy(
                        progressLabel = moduleState.label,
                        awaitingCameraConfirmation = moduleState is ProvisionState.AwaitingCameraConfirmation ||
                            moduleState is ProvisionState.Pairing,
                    )
                }
            }
        }
        progressJob = progress
        runningJob = scope.launch {
            try {
                provisioner.provision(device)
                    .onSuccess { credentials ->
                        _state.update { current ->
                            current.copy(
                                stage = BleUiStage.SUCCESS,
                                credentials = credentials,
                                passwordRevealed = false,
                                progressLabel = null,
                                awaitingCameraConfirmation = false,
                                rememberedDevice = device,
                                devices = emptyList(),
                                error = null,
                            )
                        }
                        emitEvent(BleUiEvent.WifiReady(credentials.ssid))
                        emitEvent(BleUiEvent.DeviceRemembered(device))
                    }
                    .onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable
                        handleFailure(throwable)
                    }
            } catch (cancellation: CancellationException) {
                _state.update { it.copy(progressLabel = null, awaitingCameraConfirmation = false) }
                throw cancellation
            } finally {
                progress.cancel()
            }
        }
    }

    // ----------------------------------------------------------------- actions

    /**
     * Reads only the firmware/identity characteristic from the remembered camera
     * (no pairing, no Wi-Fi change). Safe to call while the camera is connected
     * over Wi-Fi: it uses BLE and closes the GATT session afterwards.
     */
    fun refreshIdentity() {
        val target = _state.value.rememberedDevice ?: _state.value.selectedDevice
        if (target == null) {
            _state.update {
                it.copy(identityError = "请先扫描相机并完成一次蓝牙配网")
            }
            return
        }
        if (identityJob?.isActive == true) return
        if (!ensureEnvironmentReady()) return
        _state.update { it.copy(identityLoading = true, identityError = null) }
        identityJob = scope.launch {
            provisioner.readIdentityOnly(target)
                .onSuccess { identity ->
                    configStore.saveCameraIdentity(identity)
                    _state.update {
                        it.copy(
                            identity = identity,
                            identityLoading = false,
                            identityError = null,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _state.update {
                        it.copy(
                            identityLoading = false,
                            identityError = error.message ?: "读取机身信息失败",
                        )
                    }
                }
        }
    }

    /** Cancels an in-flight scan/provision; the camera-side effects are already over. */
    fun cancel() {
        val current = _state.value
        cancelRunning()
        _state.update {
            it.copy(
                stage = when {
                    current.credentials != null -> BleUiStage.SUCCESS
                    current.devices.isNotEmpty() -> BleUiStage.DEVICES
                    else -> BleUiStage.IDLE
                },
                progressLabel = null,
                awaitingCameraConfirmation = false,
            )
        }
    }

    /** Retry/refresh button on the error card: provisioning retry when it can help. */
    fun retry() {
        val current = _state.value
        val error = current.error
        when {
            error is BleError.PermissionDenied || current.stage == BleUiStage.PERMISSIONS ->
                refreshEnvironment()

            current.selectedDevice != null && error?.isRetryable == true ->
                provision(current.selectedDevice)

            else -> startScan()
        }
    }

    fun togglePasswordReveal() {
        _state.update { it.copy(passwordRevealed = !it.passwordRevealed) }
    }

    /** Called from the card's copy buttons; the app shell shows the snackbar. */
    fun notifyCopied() {
        emitEvent(BleUiEvent.Copied)
    }

    // --------------------------------------------------------------- time sync

    /** Seeds the persisted camera (from SharedPreferences) for auto time sync. */
    fun seedRememberedDevice(device: BleDeviceInfo?) {
        if (device == null) return
        _state.update { current ->
            if (current.rememberedDevice != null) current else current.copy(rememberedDevice = device)
        }
    }

    /** The camera currently remembered for time sync (scan result or persisted one). */
    fun rememberedDevice(): BleDeviceInfo? = _state.value.rememberedDevice

    /** True when a manual time-sync action can be attempted right now. */
    fun canSyncTime(): Boolean =
        _state.value.rememberedDevice != null &&
            !_state.value.timeSyncing &&
            !_state.value.isBusy &&
            safeMissingPermissions().isEmpty() &&
            safe { provisioner.isBluetoothEnabled() } == true

    /**
     * True when the automatic post-connect sync is worth attempting: a camera is
     * known, BLE is ready, and the last successful sync is older than
     * [AUTO_TIME_SYNC_INTERVAL_MS] (the camera loses its clock on power-off, so a
     * daily/once-per-app-session sync is plenty).
     */
    fun shouldAutoSyncTime(device: BleDeviceInfo?): Boolean {
        if (device == null || _state.value.timeSyncing || _state.value.isBusy) return false
        if (safeMissingPermissions().isNotEmpty()) return false
        if (safe { provisioner.isBluetoothEnabled() } != true) return false
        val last = _state.value.lastTimeSyncAtMillis
        return last == null || System.currentTimeMillis() - last > AUTO_TIME_SYNC_INTERVAL_MS
    }

    /**
     * Writes the current clock to the camera over BLE (the camera has no RTC, so
     * every photo's timestamp is wrong after a power cycle until this runs).
     *
     * @param device target camera; defaults to the remembered one.
     * @param auto true for the silent attempt after connect (errors only update
     *        state, no snackbar); false for the explicit button (errors surface).
     */
    fun syncTime(device: BleDeviceInfo? = null, auto: Boolean = false) {
        if (_state.value.timeSyncing) return
        if (_state.value.isBusy) {
            if (!auto) emitEvent(BleUiEvent.TimeSyncFailed("蓝牙配网进行中，请稍后再对时"))
            return
        }
        val target = device ?: _state.value.rememberedDevice
            ?: _state.value.selectedDevice
            ?: _state.value.devices.firstOrNull { it.isLikelyCamera }
        if (target == null) {
            if (!auto) emitEvent(BleUiEvent.TimeSyncFailed("还没有配网记录，请先在「蓝牙自动配网」中连接一次相机"))
            return
        }
        if (safeMissingPermissions().isNotEmpty()) {
            if (!auto) emitEvent(BleUiEvent.TimeSyncFailed("缺少蓝牙权限，无法对时"))
            return
        }
        if (safe { provisioner.isBluetoothSupported() } != true) {
            if (!auto) emitEvent(BleUiEvent.TimeSyncFailed("此手机不支持蓝牙，无法对时"))
            return
        }
        if (safe { provisioner.isBluetoothEnabled() } != true) {
            if (!auto) emitEvent(BleUiEvent.TimeSyncFailed("手机蓝牙未开启，无法对时"))
            return
        }
        timeSyncJob?.cancel()
        timeSyncJob = scope.launch {
            _state.update { it.copy(timeSyncing = true, timeSyncError = null) }
            provisioner.syncTime(target)
                .onSuccess {
                    _state.update {
                        it.copy(
                            timeSyncing = false,
                            lastTimeSyncAtMillis = System.currentTimeMillis(),
                            timeSyncError = null,
                        )
                    }
                    emitEvent(BleUiEvent.TimeSynced)
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    val message = error.message ?: "对时失败"
                    _state.update { it.copy(timeSyncing = false, timeSyncError = message) }
                    if (!auto) emitEvent(BleUiEvent.TimeSyncFailed(message))
                }
        }
    }

    /** Releases the provisioner; call from `ViewModel.onCleared()`. */
    fun close() {
        cancelRunning()
        timeSyncJob?.cancel()
        timeSyncJob = null
        identityJob?.cancel()
        identityJob = null
        provisioner.close()
    }

    // ----------------------------------------------------------------- helpers

    /** Returns false and moves the card to the blocking environment state when not ready. */
    private fun ensureEnvironmentReady(): Boolean {
        val supported = safe { provisioner.isBluetoothSupported() } ?: false
        val missing = if (supported) safeMissingPermissions() else emptyList()
        val enabled = supported && (safe { provisioner.isBluetoothEnabled() } ?: false)
        _state.update {
            it.copy(
                bluetoothSupported = supported,
                bluetoothEnabled = enabled,
                missingPermissions = missing,
            )
        }
        when {
            !supported -> {
                _state.update {
                    it.copy(stage = BleUiStage.FAILED, error = BleError.BluetoothUnsupported)
                }
                return false
            }

            // Permissions before enabled-state: on API 31+ reading isEnabled without
            // BLUETOOTH_CONNECT throws/returns false, so ask for permission first.
            missing.isNotEmpty() -> {
                _state.update { it.copy(stage = BleUiStage.PERMISSIONS, error = null) }
                return false
            }

            !enabled -> {
                _state.update { it.copy(stage = BleUiStage.BLUETOOTH_OFF, error = null) }
                return false
            }
        }
        return true
    }

    private fun handleFailure(throwable: Throwable) {
        val typedError = (throwable as? BleException)?.error
        val error = typedError ?: when (throwable) {
            is SecurityException -> BleError.PermissionDenied(safeMissingPermissions())
            else -> BleError.ProtocolError(throwable.message ?: throwable.javaClass.simpleName)
        }
        val stage = when (error) {
            BleError.BluetoothOff -> BleUiStage.BLUETOOTH_OFF
            is BleError.PermissionDenied -> BleUiStage.PERMISSIONS
            else -> BleUiStage.FAILED
        }
        _state.update {
            it.copy(
                stage = stage,
                error = error,
                missingPermissions = if (error is BleError.PermissionDenied) {
                    safeMissingPermissions()
                } else {
                    it.missingPermissions
                },
                progressLabel = null,
                awaitingCameraConfirmation = false,
            )
        }
    }

    private fun cancelRunning() {
        runningJob?.cancel()
        runningJob = null
        progressJob?.cancel()
        progressJob = null
    }

    private fun safeMissingPermissions(): List<String> =
        safe { provisioner.missingPermissions() } ?: emptyList()

    private inline fun <T> safe(block: () -> T): T? =
        try {
            block()
        } catch (t: Throwable) {
            null
        }

    private companion object {
        /**
         * Stages where an environment re-check must not replace the visible content.
         * `FAILED` is intentionally absent: if Bluetooth died or permissions were
         * revoked, showing that blocker is more useful than a stale error card.
         */
        /**
         * Minimum interval between automatic clock syncs. The camera clock is RAM
         * only; one successful sync per several hours is enough and avoids waking
         * the BLE radio on every reconnect.
         */
        const val AUTO_TIME_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L

        val KEEP_STAGE_ON_ENV_REFRESH = setOf(
            BleUiStage.SCANNING,
            BleUiStage.PROVISIONING,
            BleUiStage.DEVICES,
            BleUiStage.SUCCESS,
        )
    }
}
