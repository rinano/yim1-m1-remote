package com.yim1remote.app.data.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the typed error surface, progress states and small models the UI
 * consumes. All strings are the ones shown to users, so they are asserted here to
 * keep the Chinese copy and the "what should the user do now" semantics aligned.
 */
class BleModelsTest {

    // ------------------------------------------------------------------ errors

    @Test
    fun `pairing rejected carries the actionable camera-screen message`() {
        val message = BleError.PairingRejected.userMessage()
        assertTrue(message.contains("已忽略相机端蓝牙配对"))
        assertTrue(message.contains("相机屏幕确认"))
    }

    @Test
    fun `permission denied lists the missing permissions`() {
        val message = BleError.PermissionDenied(
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
        ).userMessage()
        assertTrue(message.contains("BLUETOOTH_SCAN"))
        assertTrue(message.contains("BLUETOOTH_CONNECT"))
        assertTrue(message.contains("蓝牙权限"))
    }

    @Test
    fun `timeout message names the step and the seconds`() {
        val message = BleError.Timeout("等待相机配对通知", 45_000L).userMessage()
        assertTrue(message.contains("等待相机配对通知"))
        assertTrue(message.contains("45 秒"))
        assertTrue(message.contains("相机屏幕"))
    }

    @Test
    fun `gatt app-layer error explains the 0x80 refusal`() {
        val message = BleError.GattError(
            operation = "写入配对请求(41106da0)",
            characteristic = "配对请求 41106da0",
            gattStatus = BleGattStatus.APP_LAYER_ERROR,
        ).userMessage()
        assertTrue(message.contains("蓝牙通信失败"))
        assertTrue(message.contains("0x80"))
        assertTrue(message.contains("重新执行蓝牙配对") || message.contains("配对已失效"))
    }

    @Test
    fun `gatt status descriptions are meaningful`() {
        assertTrue(BleGattStatus.describe(133).contains("133"))
        assertTrue(BleGattStatus.describe(0x80).contains("0x80"))
        assertTrue(BleGattStatus.describe(-2).contains("断开"))
        assertTrue(BleGattStatus.describe(null).contains("未知"))
        assertEquals(133, BleGattStatus.GATT_ERROR)
        assertEquals(0, BleGattStatus.SUCCESS)
    }

    @Test
    fun `bluetooth off and unsupported are not retryable from the app alone`() {
        assertFalse(BleError.BluetoothOff.isRetryable)
        assertFalse(BleError.BluetoothUnsupported.isRetryable)
        assertFalse(BleError.PermissionDenied(listOf("x")).isRetryable)
        assertFalse(BleError.PairingRejected.isRetryable)
        assertTrue(BleError.Timeout("x", 1L).isRetryable)
        assertTrue(BleError.ConnectionFailed(133).isRetryable)
    }

    @Test
    fun `pairing rejection requires a camera-side action`() {
        assertTrue(BleError.PairingRejected.requiresCameraAction)
        assertTrue(BleError.CameraUnsupported("proto 2").requiresCameraAction)
        assertFalse(BleError.BluetoothOff.requiresCameraAction)
        assertFalse(BleError.Timeout("x", 1L).requiresCameraAction)
    }

    @Test
    fun `ble exception message is the user message`() {
        val error = BleError.CameraNotFound("AA:BB")
        val exception = BleException(error)
        assertEquals(error.userMessage(), exception.message)
        assertEquals(error, exception.error)
        assertNull(exception.cause)
    }

    @Test
    fun `service missing message says it is not a yi m1`() {
        assertTrue(BleError.ServiceMissing().userMessage().contains("YI M1"))
    }

    // ------------------------------------------------------------------ states

    @Test
    fun `state labels are chinese progress strings`() {
        assertTrue(ProvisionState.Scanning.label.contains("扫描"))
        assertTrue(ProvisionState.AwaitingCameraConfirmation.label.contains("相机屏幕"))
        assertTrue(ProvisionState.ReadingCredentials.label.contains("Wi-Fi"))
        assertTrue(ProvisionState.Idle.label.isNotEmpty())
    }

    @Test
    fun `identity read state shows the camera summary`() {
        val identity = BleProtocol.parseFirmwareInfo("1,M1,M1CN,1.1")!!
        val label = ProvisionState.IdentityRead(identity).label
        assertTrue(label.contains("M1"))
        assertTrue(label.contains("M1CN"))
    }

    @Test
    fun `success and failure states surface their payload`() {
        val credentials = BleProtocol.parseWifiCredentials("YI_M1_X,12345678")!!
        val successLabel = ProvisionState.Success(credentials).label
        assertTrue(successLabel.contains("YI_M1_X"))

        val failureLabel = ProvisionState.Failed(BleError.PairingRejected).label
        assertTrue(failureLabel.contains("配对失败"))
        assertTrue(failureLabel.contains("已忽略"))
    }

    // ------------------------------------------------------------------ models

    @Test
    fun `device info falls back to the mac address for display`() {
        val named = BleDeviceInfo(name = "YI_M1_123456", address = "AA:BB")
        assertEquals("YI_M1_123456", named.displayName)
        assertTrue(named.isLikelyCamera)

        val anonymous = BleDeviceInfo(name = null, address = "AA:BB")
        assertEquals("AA:BB", anonymous.displayName)
        assertFalse(anonymous.isLikelyCamera)

        val byService = BleDeviceInfo(
            name = "unknown",
            address = "AA:CC",
            matchedByServiceUuid = true,
        )
        assertTrue(byService.isLikelyCamera)
    }

    @Test
    fun `wifi credentials expose a masked password and required flags`() {
        val credentials = WifiCredentials(ssid = "YI_M1_A", password = "12345678")
        assertTrue(credentials.isValid)
        assertEquals("********", credentials.maskedPassword)
        assertNull(credentials.wifiEnabled)
        assertFalse(credentials.timeSynced)
        assertEquals("YI_M1_A", credentials.ssid)
    }

    @Test
    fun `provisioned credentials keep identity and toggle results`() {
        val identity = BleProtocol.parseFirmwareInfo("1,M1,M1INT,1.1")!!
        val credentials = WifiCredentials(
            ssid = "ssid",
            password = "password",
            cameraIdentity = identity,
            wifiEnabled = true,
            timeSynced = true,
        )
        assertEquals(identity, credentials.cameraIdentity)
        assertEquals(true, credentials.wifiEnabled)
        assertTrue(credentials.timeSynced)
        assertFalse(credentials.toString().contains("password,"))
    }
}
