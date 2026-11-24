package com.yim1remote.app.data.ble

/**
 * One YI M1 camera candidate found by [BleProvisioner.findCameras].
 *
 * Deliberately Android-free (no `BluetoothDevice` reference) so it can be kept in
 * UI state, logged and unit-tested; [BleProvisioner] resolves the address back to
 * a `BluetoothDevice` when it opens the GATT link.
 */
data class BleDeviceInfo(
    val name: String?,
    /** Upper-case MAC, e.g. `AA:BB:CC:DD:EE:FF` (as reported by the OS). */
    val address: String,
    /** Signal strength in dBm when it came from a scan; null for bonded-only entries. */
    val rssi: Int? = null,
    /** Whether Android already has a bond with this device. */
    val bonded: Boolean = false,
    /** True when the camera was matched by its advertised service UUID rather than by name. */
    val matchedByServiceUuid: Boolean = false,
) {

    /** Name for UI lists; falls back to the MAC. */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: address

    /** True when the name heuristic or the service UUID identifies it as a camera. */
    val isLikelyCamera: Boolean get() = matchedByServiceUuid || BleProtocol.isCameraName(name)

    override fun toString(): String =
        "BleDeviceInfo(name=$name, address=$address, rssi=$rssi, bonded=$bonded, " +
            "matchedByServiceUuid=$matchedByServiceUuid)"
}

/**
 * Typed failures of the BLE provisioning module.
 *
 * The UI is expected to show [BleError.userMessage] verbatim (all messages are
 * Chinese) and may use [BleError.isRetryable] to decide whether to offer a retry
 * button. Raw GATT statuses are kept in [BleError.GattError] for diagnostics.
 */
sealed interface BleError {

    /** The phone has no BLE radio / the feature is not exposed. */
    data object BluetoothUnsupported : BleError

    /** Bluetooth is present but switched off in system settings. */
    data object BluetoothOff : BleError

    /** One or more required runtime permissions were denied. */
    data class PermissionDenied(val permissions: List<String>) : BleError

    /** No camera answered a scan; or the saved device address is no longer resolvable. */
    data class CameraNotFound(val detail: String? = null) : BleError

    /** `BluetoothLeScanner.onScanFailed` fired. */
    data class ScanFailed(val errorCode: Int, val detail: String? = null) : BleError

    /** GATT connection could not be established (or dropped while connecting). */
    data class ConnectionFailed(val gattStatus: Int? = null, val detail: String? = null) : BleError

    /**
     * The camera answered the pairing request with an **empty** notification,
     * i.e. it ignored/refused the request — the official app shows
     * `已忽略相机端蓝牙配对` and reminds the user to confirm on the camera screen.
     */
    data object PairingRejected : BleError

    /** A per-step timeout expired. */
    data class Timeout(val step: String, val timeoutMs: Long) : BleError

    /** A GATT read/write/descriptor operation returned a non-zero status. */
    data class GattError(
        val operation: String,
        val characteristic: String,
        val gattStatus: Int? = null,
        val detail: String? = null,
    ) : BleError

    /** The camera answered, but the payload does not match the protocol / is unsupported. */
    data class ProtocolError(val message: String, val causeMessage: String? = null) : BleError

    /** The camera control service (`41106dd9-…`) was not found. */
    data class ServiceMissing(val serviceUuid: String = BleUuids.SERVICE.toString()) : BleError

    /** The camera is not a YI M1 (a required characteristic is absent, or protocol too new). */
    data class CameraUnsupported(val message: String) : BleError
}

/** Chinese user-facing message for [this] error. Safe to show directly in Compose UI. */
fun BleError.userMessage(): String = when (this) {
    BleError.BluetoothUnsupported ->
        "此手机不支持蓝牙低功耗（BLE），无法使用蓝牙配网"

    BleError.BluetoothOff ->
        "蓝牙未开启，请先在系统设置中打开蓝牙"

    is BleError.PermissionDenied ->
        if (permissions.isEmpty()) {
            "缺少蓝牙权限，请在系统设置中授予"
        } else {
            "缺少蓝牙权限（${permissions.joinToString()}），请在系统设置中授予"
        }

    is BleError.CameraNotFound ->
        "未找到相机蓝牙信号，请确认相机已开机、蓝牙可见，且没有被其他设备连接" +
            (detail?.let { "（$it）" } ?: "")

    is BleError.ScanFailed ->
        "蓝牙扫描失败（错误码 $errorCode），请关闭并重新打开蓝牙后重试" +
            (detail?.let { "（$it）" } ?: "")

    is BleError.ConnectionFailed ->
        "无法连接相机蓝牙" +
            (gattStatus?.let { "（GATT 状态 $it）" } ?: "") +
            (detail?.let { "：$it" } ?: "") +
            "，请靠近相机后重试"

    BleError.PairingRejected ->
        "已忽略相机端蓝牙配对/请在相机屏幕确认\n" +
            "若相机仍绑定着旧设备，请在相机菜单中「忘记设备/解除绑定」后重试"

    is BleError.Timeout ->
        "等待超时：$step（${timeoutMs / 1000} 秒）" +
            if (step.contains("配对")) "，请留意相机屏幕上的确认提示" else ""

    is BleError.GattError ->
        "蓝牙通信失败：$operation" +
            (detail?.let { "（$it）" } ?: gattStatus?.let { "（GATT 状态 $it）" } ?: "") +
            if (gattStatus == BleGattStatus.APP_LAYER_ERROR) {
                "\n相机拒绝了该操作（应用层错误 0x80）。可能配对已失效：请在相机菜单" +
                    "「忘记设备/解除绑定」或恢复出厂设置后重新配对"
            } else {
                ""
            }

    is BleError.ProtocolError ->
        "相机返回了无法识别的数据：${message}" +
            (causeMessage?.let { "（$it）" } ?: "")

    is BleError.ServiceMissing ->
        "未找到相机的蓝牙控制服务，这看起来不是 YI M1（或固件不支持 BLE 配网）"

    is BleError.CameraUnsupported ->
        "相机与 App 版本不兼容：$message"
}

/** Whether a retry has a realistic chance of succeeding without user/camera-side action. */
val BleError.isRetryable: Boolean
    get() = when (this) {
        BleError.BluetoothUnsupported,
        BleError.BluetoothOff,
        is BleError.PermissionDenied,
        BleError.PairingRejected,
        is BleError.CameraUnsupported,
        is BleError.ServiceMissing,
        -> false

        is BleError.CameraNotFound,
        is BleError.ScanFailed,
        is BleError.ConnectionFailed,
        is BleError.Timeout,
        is BleError.GattError,
        is BleError.ProtocolError,
        -> true
    }

/** When the camera would normally accept a pairing request again without side effects. */
val BleError.requiresCameraAction: Boolean
    get() = when (this) {
        BleError.PairingRejected -> true
        is BleError.CameraUnsupported -> true
        is BleError.ServiceMissing -> true
        else -> false
    }

/** Exception wrapper so `Result<T>` failures stay type-checkable by the UI. */
class BleException(
    val error: BleError,
    cause: Throwable? = null,
) : Exception(error.userMessage(), cause)

/** Well-known GATT status codes the module reports (framework constants, duplicated as ints). */
object BleGattStatus {

    /** `BluetoothGatt.GATT_SUCCESS`. */
    const val SUCCESS = 0x00

    /** The camera's generic application-level refusal ("current state not allowed"). */
    const val APP_LAYER_ERROR = 0x80

    /** Android's generic connect/state failure (`0x85`, decimal 133). */
    const val GATT_ERROR = 0x85

    /** Write not permitted. */
    const val WRITE_NOT_PERMITTED = 0x03

    /** Insufficient authentication/encryption. */
    const val INSUFFICIENT_AUTHENTICATION = 0x05

    /** Link supervision timeout. */
    const val CONNECTION_TIMEOUT = 0x08

    /** Peer closed the link. */
    const val REMOTE_USER_TERMINATED = 0x13

    /** Local sentinel used by [BleProvisioner] when the link dropped mid-operation. */
    const val DISCONNECTED = -2

    /** Short Chinese description for logs/error details. */
    fun describe(status: Int?): String = when (status) {
        null -> "未知错误"
        SUCCESS -> "成功"
        APP_LAYER_ERROR -> "相机应用层错误 0x80（当前状态不允许该操作）"
        GATT_ERROR -> "GATT 133（Android 常见连接/状态错误，通常可重试）"
        WRITE_NOT_PERMITTED -> "写入不被允许（0x03）"
        INSUFFICIENT_AUTHENTICATION -> "认证/加密不足（0x05）"
        CONNECTION_TIMEOUT -> "链路超时（0x08）"
        REMOTE_USER_TERMINATED -> "对端主动断开（0x13）"
        DISCONNECTED -> "蓝牙连接已断开"
        else -> "GATT 状态 $status"
    }
}

/**
 * Progress stream emitted by [BleProvisioner] while provisioning.
 *
 * The UI can render [label] as-is (Chinese) or switch on the type for progress
 * indicators; [ProvisionState.Success] carries the credentials and
 * [ProvisionState.Failed] carries the typed [BleError].
 */
sealed interface ProvisionState {

    /** Idle / nothing running (also the state right after a scan). */
    data object Idle : ProvisionState

    /** Scanning for the camera advertisement. */
    data object Scanning : ProvisionState

    /** Opening the GATT connection to [device]. */
    data class Connecting(val device: BleDeviceInfo) : ProvisionState

    /** Connected; discovering the camera's GATT services. */
    data class Discovering(val device: BleDeviceInfo) : ProvisionState

    /** Reading the firmware/identity characteristic (`41106da2`). */
    data class ReadingIdentity(val device: BleDeviceInfo) : ProvisionState

    /** Identity parsed; the UI can already show the camera model/region. */
    data class IdentityRead(val identity: CameraIdentity) : ProvisionState

    /** Subscribed to pairing notifications and sent the pairing request (`41106da0`). */
    data class Pairing(val pairingKey: Int) : ProvisionState

    /** Waiting for the user to press "accept" on the camera screen. */
    data object AwaitingCameraConfirmation : ProvisionState

    /** Token received; sending the authenticated-session request (`41106da4`). */
    data object StartingSession : ProvisionState

    /** Reading the per-session SSID/password (`41106da6`). */
    data object ReadingCredentials : ProvisionState

    /** Writing `"ON"`/`"OFF"` to the Wi-Fi switch characteristic (`41106da5`). */
    data object EnablingWifi : ProvisionState

    /** Writing the current epoch seconds to the time-sync characteristic (`41106dac`). */
    data object SyncingTime : ProvisionState

    /** Provisioning finished. */
    data class Success(val credentials: WifiCredentials) : ProvisionState

    /** Provisioning failed with a typed, user-presentable error. */
    data class Failed(val error: BleError) : ProvisionState
}

/** Chinese one-line status label for UI progress rows / snackbars. */
val ProvisionState.label: String
    get() = when (this) {
        ProvisionState.Idle -> "空闲"
        ProvisionState.Scanning -> "正在扫描相机蓝牙…"
        is ProvisionState.Connecting -> "正在连接相机蓝牙（${device.displayName}）…"
        is ProvisionState.Discovering -> "正在发现相机蓝牙服务…"
        is ProvisionState.ReadingIdentity -> "正在读取相机型号/固件…"
        is ProvisionState.IdentityRead -> "相机：${identity.summary}"
        is ProvisionState.Pairing -> "正在发起配对，请在相机屏幕上确认…"
        ProvisionState.AwaitingCameraConfirmation -> "等待相机屏幕确认配对…"
        ProvisionState.StartingSession -> "配对已被接受，正在建立会话…"
        ProvisionState.ReadingCredentials -> "正在读取相机 Wi-Fi 名称与密码…"
        ProvisionState.EnablingWifi -> "正在切换相机 Wi-Fi…"
        ProvisionState.SyncingTime -> "正在同步相机时间…"
        is ProvisionState.Success -> "配对完成：${credentials.ssid}"
        is ProvisionState.Failed -> "配对失败：${error.userMessage()}"
    }
