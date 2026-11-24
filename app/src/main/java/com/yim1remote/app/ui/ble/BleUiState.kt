package com.yim1remote.app.ui.ble

import com.yim1remote.app.data.ble.BleDeviceInfo
import com.yim1remote.app.data.ble.CameraIdentity
import com.yim1remote.app.data.ble.BleError
import com.yim1remote.app.data.ble.WifiCredentials

/**
 * UI state of the 蓝牙自动配网 card on the 连接 tab.
 *
 * This is a plain UI projection of `data/ble` — the BLE module itself stays
 * Android-agnostic and knows nothing about screens. [BleUiStage] is the single
 * source of truth for which branch of the card is rendered, so an illegal
 * combination (e.g. showing a spinner after an error) cannot occur.
 */
data class BleUiState(
    val stage: BleUiStage = BleUiStage.IDLE,
    /** False when the phone has no BLE radio; the manual Wi-Fi flow stays available. */
    val bluetoothSupported: Boolean = true,
    val bluetoothEnabled: Boolean = true,
    /** Runtime permissions still missing (API 31+: SCAN+CONNECT, API 28–30: location). */
    val missingPermissions: List<String> = emptyList(),
    /** True once a permission request came back still missing → offer app-settings hint. */
    val permissionDeniedOnce: Boolean = false,
    val devices: List<BleDeviceInfo> = emptyList(),
    /** Device currently being provisioned (or the failed one, for retry). */
    val selectedDevice: BleDeviceInfo? = null,
    /** Last successfully provisioned camera, offered as a one-tap shortcut. */
    val rememberedDevice: BleDeviceInfo? = null,
    /** Body firmware / region / lens firmware from BLE `41106da2` (cached across launches). */
    val identity: CameraIdentity? = null,
    val identityLoading: Boolean = false,
    val identityError: String? = null,
    /** Chinese progress phrase from `ProvisionState.label`. */
    val progressLabel: String? = null,
    /** True while the camera screen confirmation prompt is expected. */
    val awaitingCameraConfirmation: Boolean = false,
    val credentials: WifiCredentials? = null,
    val passwordRevealed: Boolean = false,
    val error: BleError? = null,
    // ---- camera clock (the camera has no RTC; time is synced over BLE) ----
    /** True while the automatic/manual camera-clock sync is running. */
    val timeSyncing: Boolean = false,
    /** Device wall-clock of the last successful sync, or null when never synced. */
    val lastTimeSyncAtMillis: Long? = null,
    /** Last sync error message (display-only; the auto attempt is silent). */
    val timeSyncError: String? = null,
) {
    val isBusy: Boolean
        get() = stage == BleUiStage.SCANNING || stage == BleUiStage.PROVISIONING
}

/** Which content/controls the 蓝牙自动配网 card shows. */
enum class BleUiStage {
    /** Ready to scan (or scan finished with an empty list handled via [BleUiState.devices]). */
    IDLE,

    /** Runtime BLE/Wi-Fi scan permissions missing. */
    PERMISSIONS,

    /** Bluetooth radio is switched off in system settings. */
    BLUETOOTH_OFF,

    /** LE scan in progress. */
    SCANNING,

    /** Scan finished: [BleUiState.devices] may be empty (= camera not found). */
    DEVICES,

    /** Pairing/credential handshake in progress ([BleUiState.progressLabel]). */
    PROVISIONING,

    /** SSID/password read successfully ([BleUiState.credentials]). */
    SUCCESS,

    /** A typed [BleUiState.error] is shown with recovery actions. */
    FAILED,
}

/** One-shot side effects of the BLE flow that the app shell turns into snackbars. */
sealed interface BleUiEvent {
    /** Hotspot was switched on and credentials read; user still has to join it manually. */
    data class WifiReady(val ssid: String) : BleUiEvent

    /** User copied SSID/password to the clipboard. */
    data object Copied : BleUiEvent

    /** A camera was found/provisioned; the app persists it for later time sync. */
    data class DeviceRemembered(val device: BleDeviceInfo) : BleUiEvent

    /** Camera clock sync finished successfully. */
    data object TimeSynced : BleUiEvent

    /** Camera clock sync failed; [message] is user-displayable. */
    data class TimeSyncFailed(val message: String) : BleUiEvent
}
