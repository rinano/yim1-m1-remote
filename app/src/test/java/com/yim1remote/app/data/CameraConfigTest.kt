package com.yim1remote.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the camera base-URL normalization/validation contract.
 *
 * These helpers decide what the app actually connects to, so they get unit
 * coverage before the protocol exists (no device or camera required).
 */
class CameraConfigTest {

    @Test
    fun `empty input falls back to the camera default`() {
        assertEquals(CameraConfig.DEFAULT_BASE_URL, CameraConfig.normalizeBaseUrl(""))
        assertEquals(CameraConfig.DEFAULT_BASE_URL, CameraConfig.normalizeBaseUrl("   "))
    }

    @Test
    fun `scheme is added when the user types a bare host`() {
        assertEquals("http://192.168.0.10", CameraConfig.normalizeBaseUrl("192.168.0.10"))
        assertEquals("http://192.168.0.10:8080", CameraConfig.normalizeBaseUrl("192.168.0.10:8080"))
        assertEquals("http://localhost", CameraConfig.normalizeBaseUrl("localhost"))
    }

    @Test
    fun `existing scheme is preserved and normalized to lowercase`() {
        assertEquals("http://192.168.0.10", CameraConfig.normalizeBaseUrl("http://192.168.0.10"))
        assertEquals("https://camera.local", CameraConfig.normalizeBaseUrl("HTTPS://camera.local"))
    }

    @Test
    fun `whitespace and trailing slashes are trimmed`() {
        assertEquals("http://192.168.0.10", CameraConfig.normalizeBaseUrl("  http://192.168.0.10/  "))
        assertEquals("http://192.168.0.10", CameraConfig.normalizeBaseUrl("http://192.168.0.10///"))
    }

    @Test
    fun `base path is preserved for mock-server testing`() {
        assertEquals("http://192.168.42.1/mock", CameraConfig.normalizeBaseUrl("http://192.168.42.1/mock/"))
    }

    @Test
    fun `default camera address and loopback mock are valid`() {
        assertTrue(CameraConfig.isValidBaseUrl(CameraConfig.DEFAULT_BASE_URL))
        assertTrue(CameraConfig.isValidBaseUrl("192.168.0.10"))
        assertTrue(CameraConfig.isValidBaseUrl("http://127.0.0.1:8080"))
        assertTrue(CameraConfig.isValidBaseUrl("http://192.168.0.10/mock"))
    }

    @Test
    fun `malformed addresses are rejected`() {
        assertFalse(CameraConfig.isValidBaseUrl("http://"))
        assertFalse(CameraConfig.isValidBaseUrl("ftp://192.168.42.1"))
        assertFalse(CameraConfig.isValidBaseUrl("http://192.168.42.1:99999999"))
        assertFalse(CameraConfig.isValidBaseUrl("http://192.168.42.1 with spaces"))
    }
}
