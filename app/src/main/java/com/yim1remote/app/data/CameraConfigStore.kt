package com.yim1remote.app.data

import android.content.Context
import android.content.SharedPreferences
import com.yim1remote.app.data.ble.BleDeviceInfo
import com.yim1remote.app.data.ble.CameraIdentity

/**
 * Persists [CameraConfig] in the app's private [SharedPreferences].
 *
 * Kept as a plain class (not a singleton) so a ViewModel can own one instance
 * and unit tests can be written against the normalization helpers instead. The
 * store deliberately holds only user-editable connection settings; recovered
 * protocol state is never persisted here.
 */
class CameraConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Reads the stored config, falling back to [CameraConfig.DEFAULT_BASE_URL]. */
    fun load(): CameraConfig {
        val stored = prefs.getString(KEY_BASE_URL, null)
        return if (stored.isNullOrBlank()) {
            CameraConfig()
        } else {
            CameraConfig(CameraConfig.normalizeBaseUrl(stored))
        }
    }

    /** Persists [config], normalizing the URL first so reads are stable. */
    fun save(config: CameraConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, CameraConfig.normalizeBaseUrl(config.baseUrl))
            .apply()
    }

    /** Clears the override so the app returns to the camera's factory address. */
    fun resetToDefault() {
        prefs.edit().remove(KEY_BASE_URL).apply()
    }

    // ------------------------------------------------------------- BLE camera

    /**
     * Last successfully provisioned camera, used for the automatic BLE time sync
     * on connect (the camera has no RTC, see `BleProvisioner.syncTime`).
     * Addresses are stored upper-case; a missing MAC means "never provisioned".
     */
    fun loadRememberedBleDevice(): BleDeviceInfo? {
        val address = prefs.getString(KEY_BLE_ADDRESS, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val name = prefs.getString(KEY_BLE_NAME, null)?.takeIf { it.isNotBlank() }
        return BleDeviceInfo(name = name, address = address)
    }

    /** Remembers the camera for later time syncs (MAC + advertised name). */
    fun saveRememberedBleDevice(device: BleDeviceInfo) {
        prefs.edit()
            .putString(KEY_BLE_ADDRESS, device.address.uppercase())
            .putString(KEY_BLE_NAME, device.name)
            .apply()
    }

    /** Body firmware / region / lens firmware last read from `41106da2`. */
    fun loadCameraIdentity(): CameraIdentity? {
        val body = prefs.getString(KEY_IDENTITY_BODY, null)?.takeIf { it.isNotBlank() } ?: return null
        return CameraIdentity(
            protocolVersion = prefs.getInt(KEY_IDENTITY_PROTOCOL, 1),
            bodyFirmware = body,
            regionMarker = prefs.getString(KEY_IDENTITY_REGION, null).orEmpty(),
            lensFirmware = prefs.getString(KEY_IDENTITY_LENS, null).orEmpty(),
        )
    }

    fun saveCameraIdentity(identity: CameraIdentity) {
        prefs.edit()
            .putInt(KEY_IDENTITY_PROTOCOL, identity.protocolVersion)
            .putString(KEY_IDENTITY_BODY, identity.bodyFirmware)
            .putString(KEY_IDENTITY_REGION, identity.regionMarker)
            .putString(KEY_IDENTITY_LENS, identity.lensFirmware)
            .apply()
    }

    fun clearCameraIdentity() {
        prefs.edit()
            .remove(KEY_IDENTITY_PROTOCOL)
            .remove(KEY_IDENTITY_BODY)
            .remove(KEY_IDENTITY_REGION)
            .remove(KEY_IDENTITY_LENS)
            .apply()
    }

    companion object {
        const val PREFS_NAME: String = "camera_config"
        private const val KEY_BASE_URL: String = "camera_base_url"
        private const val KEY_BLE_ADDRESS: String = "camera_ble_address"
        private const val KEY_BLE_NAME: String = "camera_ble_name"
    private const val KEY_IDENTITY_PROTOCOL: String = "camera_identity_protocol"
    private const val KEY_IDENTITY_BODY: String = "camera_identity_body"
    private const val KEY_IDENTITY_REGION: String = "camera_identity_region"
    private const val KEY_IDENTITY_LENS: String = "camera_identity_lens"
    }
}
