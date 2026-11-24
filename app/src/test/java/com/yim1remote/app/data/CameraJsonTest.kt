package com.yim1remote.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact command JSON the official app sent.
 *
 * Every assertion mirrors a `LinkedHashMap` in
 * `analysis/jadx-out/sources/com/xiaoyi/mirrorlesscamera/http/camera/b.java`:
 * compact Gson JSON, exact parameter keys (including the firmware misspelling
 * `resulotion`), numeric ranges as JSON numbers and positions as strings.
 */
class CameraJsonTest {

    private fun assertCompact(json: String) {
        assertTrue("JSON must be compact (no whitespace): $json", !json.contains(' '))
        assertTrue("must be a JSON object: $json", json.trim().startsWith("{"))
        // Must round-trip through a strict JSON parser.
        JSONObject(json)
    }

    /**
     * Key order inside `org.json` is implementation-defined (the JVM test artifact
     * uses a HashMap), so compare structurally: parsing both sides with the same
     * parser normalizes ordering while still checking keys, values and types.
     */
    private fun assertJsonEquals(expected: String, actual: String) {
        assertEquals(JSONObject(expected).toString(), JSONObject(actual).toString())
    }

    @Test
    fun `status and firmware probes`() {
        assertEquals("""{"command":"GetCameraStatus"}""", CameraJson.getCameraStatus())
        assertEquals("""{"command":"CheckPreUpdate"}""", CameraJson.checkPreUpdate())
        assertEquals("""{"command":"StartLiveview"}""", CameraJson.startLiveview())
        assertEquals("""{"command":"RCGetCameraCfg"}""", CameraJson.rcGetCameraCfg())
        assertEquals("""{"command":"RCStartRemoteCtl"}""", CameraJson.rcStartRemoteCtl())
        assertEquals("""{"command":"RCStopRemoteCtl"}""", CameraJson.rcStopRemoteCtl())
    }

    @Test
    fun `camera status probe is valid compact json`() {
        assertCompact(CameraJson.getCameraStatus())
        assertCompact(CameraJson.checkPreUpdate())
    }

    @Test
    fun `file list page is 1 based with numeric range keys and filetype all`() {
        val json = CameraJson.getFileList(1, 60)
        assertJsonEquals(
            """{"command":"GetFileList","range_start":1,"range_end":60,"filetype":"all"}""",
            json,
        )
        val parsed = JSONObject(json)
        assertEquals(1, parsed.getInt("range_start"))
        assertEquals(60, parsed.getInt("range_end"))
        assertEquals("all", parsed.getString("filetype"))
        // Page 2: official app used (page*60)+1 .. (page+1)*60.
        assertTrue(CameraJson.getFileList(61, 120).contains("\"range_start\":61"))
        assertTrue(CameraJson.getFileList(61, 120).contains("\"range_end\":120"))
    }

    @Test
    fun `file info and delete requests`() {
        assertJsonEquals(
            """{"command":"GetFileInfo","path":"/DCIM/100YICAM/YI000001.JPG"}""",
            CameraJson.getFileInfo("/DCIM/100YICAM/YI000001.JPG"),
        )
        assertJsonEquals(
            """{"command":"DeleteFile","file_list":["/DCIM/a.JPG","/DCIM/b.DNG"]}""",
            CameraJson.deleteFile(listOf("/DCIM/a.JPG", "/DCIM/b.DNG")),
        )
        assertCompact(CameraJson.deleteFile(emptyList()))
    }

    @Test
    fun `get file keeps the firmware misspelling resulotion and echoes date`() {
        val json = CameraJson.getFile("/DCIM/100YICAM/YI000001.JPG", "1789400001", "MidThumb")
        val parsed = JSONObject(json)
        assertEquals("GetFile", parsed.getString("command"))
        assertEquals("/DCIM/100YICAM/YI000001.JPG", parsed.getString("path"))
        assertEquals("1789400001", parsed.getString("date"))
        assertEquals("MidThumb", parsed.getString("resulotion"))
        // `resolution` must NOT be sent: firmware rejects the parameter name.
        assertTrue(!parsed.has("resolution"))
        for (resolution in listOf("Thumbnail", "MidThumb", "Original")) {
            assertEquals(
                resolution,
                JSONObject(CameraJson.getFile("/p.JPG", "1", resolution)).getString("resulotion"),
            )
        }
    }

    @Test
    fun `setting setters use the exact firmware parameter keys`() {
        val cases = listOf(
            CameraJson.rcSwitchDialMode("S") to ("DialMode" to "S"),
            CameraJson.rcMeteringModeSet("Spot") to ("MeteringMode" to "Spot"),
            CameraJson.rcFocusModeSet("MF") to ("FocusMode" to "MF"),
            CameraJson.rcImageQualitySet("20") to ("ImageQuality" to "20"),
            CameraJson.rcImageAspect("3:2") to ("ImageAspect" to "3:2"),
            CameraJson.rcFileFormatSet("RAWJ-L") to ("FileFormat" to "RAWJ-L"),
            CameraJson.rcDriveModeSet("10SDelay") to ("DriveMode" to "10SDelay"),
            CameraJson.rcFnSet("1.7") to ("Fnumber" to "1.7"),
            CameraJson.rcShutterSpeedSet("1/125s") to ("ShutterSpeed" to "1/125s"),
            CameraJson.rcEvSet("0.3") to ("EV" to "0.3"),
            CameraJson.rcIsoSet("Auto") to ("ISO" to "Auto"),
            CameraJson.rcWbSet("Sunny") to ("WB" to "Sunny"),
            CameraJson.rcChooseColorMode("Vivid") to ("ColorMode" to "Vivid"),
            CameraJson.rcDShootCntSet("3") to ("RCDShootCntSet" to "3"),
            CameraJson.rcMfAdjust("NearS") to ("Operation" to "NearS"),
        )
        for ((json, pair) in cases) {
            assertCompact(json)
            val parsed = JSONObject(json)
            assertEquals("command must be present", true, parsed.has("command"))
            assertEquals(pair.second, parsed.getString(pair.first))
        }
        // The dial mode command is named RCSwitchDialMode, not RCDialMode.
        assertEquals(
            "RCSwitchDialMode",
            JSONObject(CameraJson.rcSwitchDialMode("Auto")).getString("command"),
        )
    }

    @Test
    fun `dial mode sends C exactly as the official app did`() {
        // Firmware also reports/accepts "Scene"; the official 3.1.6 app used "C".
        assertEquals("C", JSONObject(CameraJson.rcSwitchDialMode("C")).getString("DialMode"))
    }

    @Test
    fun `ev setter normalizes a UI plus sign`() {
        assertEquals("0.3", JSONObject(CameraJson.rcEvSet("+0.3")).getString("EV"))
        assertEquals("-0.3", JSONObject(CameraJson.rcEvSet("-0.3")).getString("EV"))
        assertEquals("0.0", JSONObject(CameraJson.rcEvSet("0.0")).getString("EV"))
    }

    @Test
    fun `do focus sends mode and coordinates as strings`() {
        val parsed = JSONObject(CameraJson.rcDoFocus("Manual", 360, 240))
        assertEquals("RCDoFocus", parsed.getString("command"))
        assertEquals("Manual", parsed.getString("Mode"))
        assertEquals("360", parsed.getString("Posx"))
        assertEquals("240", parsed.getString("Posy"))
    }

    @Test
    fun `manual focus step helper maps to the four firmware operations`() {
        assertEquals("FarF", JSONObject(CameraJson.rcMfAdjust(2)!!).getString("Operation"))
        assertEquals("FarS", JSONObject(CameraJson.rcMfAdjust(1)!!).getString("Operation"))
        assertEquals("NearS", JSONObject(CameraJson.rcMfAdjust(-1)!!).getString("Operation"))
        assertEquals("NearF", JSONObject(CameraJson.rcMfAdjust(-2)!!).getString("Operation"))
        assertNull(CameraJson.rcMfAdjust(0))
    }

    @Test
    fun `shooting and cancel commands`() {
        assertEquals("""{"command":"RCDoShooting"}""", CameraJson.rcDoShooting())
        assertEquals("""{"command":"RCCancelShooting"}""", CameraJson.rcCancelShooting())
        assertEquals("""{"command":"RCCancelShooting1"}""", CameraJson.rcCancelShooting1())
    }
}
