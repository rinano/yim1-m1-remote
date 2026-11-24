package com.yim1remote.app.data.ble

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure BLE provisioning protocol layer.
 *
 * These mirror the verified handshake in `research/vendor/yi-m1-controller-app`
 * and the official app's `common/d.java`; they are the only part of this module
 * that can be checked without a physical camera.
 */
class BleProtocolTest {

    // ------------------------------------------------------------------- CRC32

    @Test
    fun `crc32 matches zlib known values`() {
        // Reference values from Python's zlib.crc32.
        assertEquals(0L, ZlibCrc32.ofAscii(""))
        assertEquals(3421780262L, ZlibCrc32.ofAscii("123456789"))
        assertEquals(1095738169L, ZlibCrc32.ofAscii("The quick brown fox jumps over the lazy dog"))
    }

    @Test
    fun `crc32 of bytes equals crc32 of the same ascii text`() {
        val bytes = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(ZlibCrc32.ofAscii("123456789"), ZlibCrc32.of(bytes))
    }

    @Test
    fun `crc32 of empty bytes is zero`() {
        assertEquals(0L, ZlibCrc32.of(ByteArray(0)))
    }

    // ---------------------------------------------------------------- UUIDs

    @Test
    fun `uuid constants match the verified hardware table`() {
        assertEquals(
            "41106dd9-25ad-477b-a884-5038b6de4649",
            BleUuids.SERVICE.toString(),
        )
        assertEquals(
            "41106da0-25ad-477b-a884-5038b6de4649",
            BleUuids.PAIRING_REQUEST.toString(),
        )
        assertEquals(
            "41106da2-25ad-477b-a884-5038b6de4649",
            BleUuids.FIRMWARE_INFO.toString(),
        )
        assertEquals(
            "41106da4-25ad-477b-a884-5038b6de4649",
            BleUuids.START_SESSION.toString(),
        )
        assertEquals(
            "41106da5-25ad-477b-a884-5038b6de4649",
            BleUuids.WIFI_SWITCH.toString(),
        )
        assertEquals(
            "41106da6-25ad-477b-a884-5038b6de4649",
            BleUuids.WIFI_AP.toString(),
        )
        assertEquals(
            "41106da7-25ad-477b-a884-5038b6de4649",
            BleUuids.PAIRING_NOTIFY.toString(),
        )
        assertEquals(
            "41106dac-25ad-477b-a884-5038b6de4649",
            BleUuids.SYNC_TIME.toString(),
        )
        assertEquals(
            "00002902-0000-1000-8000-00805f9b34fb",
            BleUuids.CLIENT_CHARACTERISTIC_CONFIG.toString(),
        )
    }

    @Test
    fun `short names are the first uuid segment`() {
        assertEquals("41106da2", BleUuids.shortName(BleUuids.FIRMWARE_INFO))
        assertTrue(BleUuids.label(BleUuids.WIFI_SWITCH).contains("41106da5"))
    }

    // ---------------------------------------------------------- pairing payload

    @Test
    fun `pairing request uses protocol key and literal android client name`() {
        assertEquals("1,12345,android", BleProtocol.buildPairingRequest(1, 12345))
        assertEquals(
            "1,0,android",
            BleProtocol.buildPairingRequest(BleProtocol.SUPPORTED_PROTOCOL_VERSION, 0),
        )
    }

    @Test
    fun `session request embeds zlib crc32 of protocol key and token`() {
        // crc32(b"112345abcdef") == 1738000884 (Python zlib.crc32)
        assertEquals("1,12345,1738000884", BleProtocol.buildSessionRequest(1, 12345, "abcdef"))
        // crc32(b"112345TOKEN-ABC") == 2842566808
        assertEquals("1,12345,2842566808", BleProtocol.buildSessionRequest(1, 12345, "TOKEN-ABC"))
        // crc32(b"100") == 595022058 — key 0 and token "0" concatenate to "100"
        assertEquals("1,0,595022058", BleProtocol.buildSessionRequest(1, 0, "0"))
        // crc32(b"199998xy") == 4154382844 — maximum key
        assertEquals("1,99998,4154382844", BleProtocol.buildSessionRequest(1, 99998, "xy"))
    }

    @Test
    fun `session request crc uses one then key then token order`() {
        val payload = BleProtocol.buildSessionRequest(1, 12345, "token")
        val expectedCrc = ZlibCrc32.ofAscii("112345token")
        assertEquals("1,12345,$expectedCrc", payload)
        // A token-first concatenation would produce a different checksum.
        assertFalse(payload.endsWith(ZlibCrc32.ofAscii("1token12345").toString()))
    }

    @Test
    fun `wifi toggle payloads are ON and OFF`() {
        assertEquals("ON", BleProtocol.buildWifiToggle(true))
        assertEquals("OFF", BleProtocol.buildWifiToggle(false))
    }

    // ------------------------------------------------------------- firmware

    @Test
    fun `parses verified hardware firmware payload`() {
        // The community project verified this shape on real firmware "1,3.1-cn,M1CN,0.0".
        val identity = BleProtocol.parseFirmwareInfo("1,M1,M1INT,1.1")
        assertNotNull(identity)
        identity!!
        assertEquals(1, identity.protocolVersion)
        assertEquals("M1", identity.bodyFirmware)
        assertEquals("M1INT", identity.regionMarker)
        assertEquals("1.1", identity.lensFirmware)
        assertTrue(identity.isGlobalVariant)
        assertFalse(identity.isChinaVariant)
        assertTrue(identity.hasKnownRegion)
        assertEquals(listOf("1", "M1", "M1INT", "1.1"), identity.rawFields)
    }

    @Test
    fun `detects china variant from M1CN and bare M1 markers`() {
        val cn = BleProtocol.parseFirmwareInfo("1,M1,M1CN,1.1")!!
        assertTrue(cn.isChinaVariant)
        assertFalse(cn.isGlobalVariant)

        val bare = BleProtocol.parseFirmwareInfo("1,M1,M1,1.0")!!
        assertTrue(bare.isChinaVariant)
        assertEquals("M1", bare.regionMarker)
    }

    @Test
    fun `tolerates the contradictory firmware layout where field 3 is the region`() {
        // One hardware session reported field 2 as a firmware version and field 3 as
        // the region. The parser must still find the region token.
        val identity = BleProtocol.parseFirmwareInfo("1,M1,1.1,M1CN")!!
        assertEquals("M1CN", identity.regionMarker)
        assertTrue(identity.isChinaVariant)
        assertEquals("1.1", identity.lensFirmware)
        assertEquals(listOf("1", "M1", "1.1", "M1CN"), identity.rawFields)
    }

    @Test
    fun `parses NUL padded characteristic bytes`() {
        val payload = "1,M1,M1INT,1.1".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        val identity = BleProtocol.parseFirmwareInfo(payload)!!
        assertEquals(1, identity.protocolVersion)
        assertEquals("M1INT", identity.regionMarker)
        assertEquals("1.1", identity.lensFirmware)
    }

    @Test
    fun `trims whitespace around firmware fields`() {
        val identity = BleProtocol.parseFirmwareInfo(" 1 , M1 , M1INT , 1.1 ".trim())!!
        assertEquals(1, identity.protocolVersion)
        assertEquals("M1", identity.bodyFirmware)
        assertEquals("M1INT", identity.regionMarker)
        assertEquals("1.1", identity.lensFirmware)
    }

    @Test
    fun `three field firmware payload is valid but has no lens version`() {
        val identity = BleProtocol.parseFirmwareInfo("1,M1,M1CN")!!
        assertEquals("M1CN", identity.regionMarker)
        assertEquals("", identity.lensFirmware)
    }

    @Test
    fun `rejects unusable firmware payloads`() {
        assertNull(BleProtocol.parseFirmwareInfo(""))
        assertNull(BleProtocol.parseFirmwareInfo("   "))
        assertNull(BleProtocol.parseFirmwareInfo("1,M1"))
        assertNull(BleProtocol.parseFirmwareInfo("x,M1,M1INT"))
        assertNull(BleProtocol.parseFirmwareInfo(ByteArray(6))) // all NULs
    }

    @Test
    fun `protocol version above one is still parsed but exposed for the caller to reject`() {
        val identity = BleProtocol.parseFirmwareInfo("2,M1,M1INT,2.0")!!
        assertEquals(2, identity.protocolVersion)
        assertTrue(identity.protocolVersion > BleProtocol.SUPPORTED_PROTOCOL_VERSION)
    }

    @Test
    fun `identity summary is human readable`() {
        val identity = BleProtocol.parseFirmwareInfo("1,M1,M1INT,1.1")!!
        assertEquals("M1 / M1INT / BLE 1 / 镜头 1.1", identity.summary)
    }

    // -------------------------------------------------- pairing notification

    @Test
    fun `empty pairing notification means rejected`() {
        assertEquals(PairingNotification.Rejected, BleProtocol.parsePairingResult(ByteArray(0)))
        assertEquals(
            PairingNotification.Rejected,
            BleProtocol.parsePairingResult(byteArrayOf(0, 0, 0)),
        )
        assertEquals(PairingNotification.Rejected, BleProtocol.parsePairingResult("   "))
    }

    @Test
    fun `non empty pairing notification is the token`() {
        val result = BleProtocol.parsePairingResult("abc123".toByteArray(Charsets.US_ASCII))
        assertTrue(result is PairingNotification.Token)
        assertEquals("abc123", (result as PairingNotification.Token).token)
    }

    @Test
    fun `token payload is trimmed of NUL padding`() {
        val payload = "0a1b2c3d".toByteArray(Charsets.US_ASCII) + ByteArray(12)
        val result = BleProtocol.parsePairingResult(payload)
        assertEquals(PairingNotification.Token("0a1b2c3d"), result)
    }

    // ------------------------------------------------------- wifi credentials

    @Test
    fun `parses ssid and password`() {
        val credentials = BleProtocol.parseWifiCredentials("YI_M1_123456,87654321")!!
        assertEquals("YI_M1_123456", credentials.ssid)
        assertEquals("87654321", credentials.password)
        assertTrue(credentials.isValid)
    }

    @Test
    fun `parses NUL padded credentials from characteristic read`() {
        val payload = "YI_M1_ABC,12345678".toByteArray(Charsets.US_ASCII) + ByteArray(4)
        val credentials = BleProtocol.parseWifiCredentials(payload)!!
        assertEquals("YI_M1_ABC", credentials.ssid)
        assertEquals("12345678", credentials.password)
    }

    @Test
    fun `tolerates a comma inside the password by splitting on the first one`() {
        val credentials = BleProtocol.parseWifiCredentials("ssid,pa,ss")!!
        assertEquals("ssid", credentials.ssid)
        assertEquals("pa,ss", credentials.password)
    }

    @Test
    fun `rejects malformed credential payloads`() {
        assertNull(BleProtocol.parseWifiCredentials(""))
        assertNull(BleProtocol.parseWifiCredentials("ssidonly"))
        assertNull(BleProtocol.parseWifiCredentials("ssid,"))
        assertNull(BleProtocol.parseWifiCredentials(",password"))
        assertNull(BleProtocol.parseWifiCredentials("ssid,   "))
        assertNull(BleProtocol.parseWifiCredentials(ByteArray(0)))
    }

    @Test
    fun `masked password never exposes the secret`() {
        val credentials = BleProtocol.parseWifiCredentials("ssid,secret123")!!
        assertFalse(credentials.maskedPassword.contains("secret"))
        assertFalse(credentials.toString().contains("secret123"))
        assertTrue(credentials.toString().contains("ssid"))
    }

    // ------------------------------------------------------------- time sync

    @Test
    fun `time sync payload is ascii epoch seconds`() {
        assertEquals("1700000000", BleProtocol.buildTimeSyncPayload(1700000000L))
        assertEquals(
            "1700000000",
            BleProtocol.buildTimeSyncPayloadForEpochMillis(1700000000123L),
        )
        assertEquals("1234567890", BleProtocol.buildTimeSyncPayloadNow(1234567890123L))
        // Truncation, not rounding: 999 ms must not roll over to the next second.
        assertEquals("1700000000", BleProtocol.buildTimeSyncPayloadForEpochMillis(1700000000999L))
    }

    @Test
    fun `camera clock payload adds the local zone offset like the official app`() {
        // Official app: (now + ZONE_OFFSET + DST_OFFSET) / 1000 (`b/p.java:77-81`).
        // (1234567890123 + 8h) / 1000 = 1234596690
        assertEquals(
            "1234596690",
            BleProtocol.buildCameraClockPayload(1234567890123L, 8 * 60 * 60 * 1000),
        )
        // (1234567890123 - 5h) / 1000 = 1234549890
        assertEquals(
            "1234549890",
            BleProtocol.buildCameraClockPayload(1234567890123L, -5 * 60 * 60 * 1000),
        )
        // A zero offset degrades to plain epoch seconds.
        assertEquals(
            BleProtocol.buildTimeSyncPayloadForEpochMillis(1234567890123L),
            BleProtocol.buildCameraClockPayload(1234567890123L, 0),
        )
        assertEquals("1234567890", BleProtocol.buildCameraClockPayloadNow(1234567890123L, 0))
    }

    @Test
    fun `camera clock payload truncates milliseconds after applying the offset`() {
        // (1700000000999 + 1h) / 1000 = 1700003600
        assertEquals("1700003600", BleProtocol.buildCameraClockPayload(1700000000999L, 3_600_000))
    }

    // ----------------------------------------------------------- pairing keys

    @Test
    fun `pairing key range is inclusive zero to 99998`() {
        assertTrue(BleProtocol.isValidPairingKey(0))
        assertTrue(BleProtocol.isValidPairingKey(99998))
        assertTrue(BleProtocol.isValidPairingKey(12345))
        assertFalse(BleProtocol.isValidPairingKey(-1))
        assertFalse(BleProtocol.isValidPairingKey(99999))
    }

    @Test
    fun `generated pairing keys always stay in range`() {
        val random = Random(42)
        repeat(2_000) {
            val key = BleProtocol.generatePairingKey(random)
            assertTrue("key out of range: $key", BleProtocol.isValidPairingKey(key))
        }
    }

    // ------------------------------------------------------- scan name filter

    @Test
    fun `camera name heuristic follows the hardware name patterns`() {
        assertTrue(BleProtocol.isCameraName("YI_M1_123456"))
        assertTrue(BleProtocol.isCameraName("yi_m1_abcdef"))
        assertTrue(BleProtocol.isCameraName("M1"))
        assertTrue(BleProtocol.isCameraName("M1CN"))
        assertTrue(BleProtocol.isCameraName("  YI_M1_X  "))
        assertFalse(BleProtocol.isCameraName(null))
        assertFalse(BleProtocol.isCameraName(""))
        assertFalse(BleProtocol.isCameraName("   "))
        assertFalse(BleProtocol.isCameraName("YI_M1"))
        assertFalse(BleProtocol.isCameraName("CANON"))
        assertFalse(BleProtocol.isCameraName("XM1"))
    }

    // ---------------------------------------------- optional notifications

    @Test
    fun `parses lens notification`() {
        val lens = BleProtocol.parseLensNotification(
            "3,0,1.1,10-30mm".toByteArray(Charsets.US_ASCII),
        )!!
        assertEquals("1.1", lens.lensVersion)
        assertEquals("10-30mm", lens.lensType)
    }

    @Test
    fun `ignores payloads that are not lens notifications`() {
        assertNull(BleProtocol.parseLensNotification("3,0,1.1".toByteArray(Charsets.US_ASCII)))
        assertNull(BleProtocol.parseLensNotification("2,0,1.1,10-30mm".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `parses camera state notification`() {
        val state = BleProtocol.parseCameraStateNotification("ac,7".toByteArray(Charsets.US_ASCII))!!
        assertEquals("7", state.stateCode)
        assertFalse(state.isDisconnectLike)
        assertTrue(
            BleProtocol.parseCameraStateNotification("ac,3".toByteArray(Charsets.US_ASCII))!!
                .isDisconnectLike,
        )
    }

    @Test
    fun `ignores malformed camera state notifications`() {
        assertNull(BleProtocol.parseCameraStateNotification("ac".toByteArray(Charsets.US_ASCII)))
        assertNull(BleProtocol.parseCameraStateNotification("zz,7".toByteArray(Charsets.US_ASCII)))
    }

    // --------------------------------------------------------------- helpers

    @Test
    fun `ascii payload encoding round trips`() {
        val text = "1,12345,android"
        assertEquals(text, BleProtocol.trimAscii(BleProtocol.asciiPayload(text)))
        assertEquals("", BleProtocol.trimAscii(ByteArray(0)))
    }
}
