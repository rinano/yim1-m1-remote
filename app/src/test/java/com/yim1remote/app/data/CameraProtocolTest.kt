package com.yim1remote.app.data

import com.yim1remote.app.data.model.CameraFileType
import com.yim1remote.app.data.model.CameraStatus
import com.yim1remote.app.data.model.ExposureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Protocol-contract tests over fixtures copied from the verified specs
 * (`research/verified/settings-and-status.md`, `gallery-and-download.md`,
 * `http-protocol.md`) and from the APK decompilation.
 */
class CameraProtocolTest {

    /**
     * Regression (real device): Android's org.json escapes '/' inside strings as
     * '\/', which the camera firmware does not unescape; GetFile with such a path
     * answers HTTP 204. buildUrl must normalise the escaped form.
     */
    @Test
    fun `buildUrl removes json-escaped slashes from the query`() {
        val json = "{\"command\":\"GetFile\",\"path\":\"\\/DCIM\\/100YICAM\\/PB230128.DNG\",\"resulotion\":\"Original\"}"
        val url = CameraProtocol.buildUrl("http://192.168.0.10", json)
        val decoded = java.net.URLDecoder.decode(url.substringAfter("?data="), "UTF-8")
        assertTrue("no escaped slash left: " + decoded, !decoded.contains("\\/"))
        assertTrue("path intact: " + decoded, decoded.contains("\"/DCIM/100YICAM/PB230128.DNG\""))
    }



    // ------------------------------------------------------------------ envelope

    @Test
    fun `success envelopes accept 200 and 20000`() {
        val envelope = CameraProtocol.parseEnvelope("""{"code":200,"data":{"batteryLevel":"87"}}""")
        assertTrue(envelope.isSuccess)
        assertEquals(200, envelope.code)
        assertNotNull(envelope.data)
        CameraProtocol.requireSuccess("""{"code":20000,"data":null}""")

        val error = try {
            CameraProtocol.requireSuccess("""{"code":1515,"data":"rc only one"}""")
            null
        } catch (e: CameraApiException) {
            e
        }
        assertNotNull(error)
        assertEquals(1515, error!!.code)
        assertEquals("rc only one", error.cameraMessage)
    }

    @Test
    fun `broken envelopes raise protocol errors with useful messages`() {
        for (body in listOf("", "   ", "<html>", """{"data":1}""")) {
            try {
                CameraProtocol.parseEnvelope(body)
                fail("expected CameraProtocolException for '$body'")
            } catch (e: CameraProtocolException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun `null or json data envelope keeps data null`() {
        assertNull(CameraProtocol.parseEnvelope("""{"code":200}""").data)
        assertNull(CameraProtocol.parseEnvelope("""{"code":204,"data":null}""").data)
        assertNull(CameraProtocol.parseEnvelope("""{"code":200,"data":null}""").data)
    }

    // ----------------------------------------------------------------------- url

    @Test
    fun `url builder percent-encodes the json query parameter`() {
        val url = CameraProtocol.buildUrl(
            "http://192.168.0.10",
            CameraJson.getFile("/DCIM/100YICAM/YI000001.JPG", "1789400001", "MidThumb"),
        )
        assertTrue(url.startsWith("http://192.168.0.10/?data="))
        // Braces/quotes/commas/slashes are encoded, so the raw URL is ASCII-safe.
        assertFalse(url.contains('"'))
        assertFalse(url.contains('{'))
        assertFalse(url.contains(' '))
        assertTrue(url.contains("%7B"))
        assertTrue(url.contains("%22command%22"))
        assertTrue(url.contains("%2FDCIM%2F100YICAM%2FYI000001.JPG"))
    }

    @Test
    fun `url builder keeps base paths and strips trailing slashes`() {
        assertEquals(
            "http://127.0.0.1:18080/mock/?data=%7B%22command%22%3A%22GetCameraStatus%22%7D",
            CameraProtocol.buildUrl(
                "http://127.0.0.1:18080/mock/",
                CameraJson.getCameraStatus(),
            ),
        )
        assertEquals(
            "http://127.0.0.1:18080/?data=%7B%22command%22%3A%22GetCameraStatus%22%7D",
            CameraProtocol.buildUrl(
                "http://127.0.0.1:18080///",
                CameraJson.getCameraStatus(),
            ),
        )
    }

    // -------------------------------------------------------------------- status

    @Test
    fun `health status parses the verified four-field GetCameraStatus shape`() {
        val health = CameraProtocol.parseHealthStatus(
            """{"batteryLevel":"101","SurplusPhotoCnts":"1119","lenVer":"0.0","lenType":""}""",
        )
        assertEquals(101, health.batteryPercent)
        assertTrue(health.isExternalPower)
        assertEquals(1119, health.remainingShots)
        assertEquals("0.0", health.lensVersion)
        assertNull(health.lensType) // empty string -> absent
    }

    @Test
    fun `health status tolerates missing, null and non-json payloads`() {
        assertNull(CameraProtocol.parseHealthStatus(null).batteryPercent)
        assertNull(CameraProtocol.parseHealthStatus("").batteryPercent)
        assertNull(CameraProtocol.parseHealthStatus("not json").batteryPercent)
        assertNull(CameraProtocol.parseHealthStatus("""{"batteryLevel":"?"}""").batteryPercent)
    }

    @Test
    fun `mergeHealth keeps settings from the live state while updating battery`() {
        val live = CameraStatus(
            connected = true,
            batteryPercent = 25,
            remainingShots = 1291,
            exposureMode = ExposureMode.S,
            fnumber = "1.7",
            shutterSpeed = "1s",
        )
        val merged = CameraProtocol.mergeHealth(
            live,
            CameraHealth(batteryPercent = 101, remainingShots = 1119, lensVersion = "1.1"),
        )
        assertEquals(101, merged.batteryPercent)
        assertTrue(merged.isExternalPower)
        assertEquals(1119, merged.remainingShots)
        assertEquals("1.1", merged.lensVersion)
        // Settings only the live-view block reports are preserved.
        assertEquals(ExposureMode.S, merged.exposureMode)
        assertEquals("1.7", merged.fnumber)
        assertEquals("1s", merged.shutterSpeed)
    }

    @Test
    fun `mergeHealth creates a connected status when none existed`() {
        val merged = CameraProtocol.mergeHealth(
            null,
            CameraHealth(batteryPercent = 87, remainingShots = 42, lensType = "YI_12-40_3.5-5.6"),
        )
        assertTrue(merged.connected)
        assertEquals(87, merged.batteryPercent)
        assertEquals(42, merged.remainingShots)
        assertEquals("YI_12-40_3.5-5.6", merged.lensType)
    }

    /** Verbatim state block from the real 3.1-cn capture (`params_000`). */
    private val liveStateJson = """
        {"ExposureMode":"S","MeteringMode":"Multi","ImageQuality":"20","ImageAspect":"4:3",
         "DriveMode":"Single","DelayShootCnt":"1","FileFormat":"RAWJ-L","Fnumber":"1.7",
         "FnumberMin":"1.7","FnumberMax":"16","ShutterSpeed":"1s","EV":"0.0",
         "ISOSetting":"Auto","ISOAutoValue":"2500","WB":"Auto","ColorMode":"Standard",
         "LensStatus":"1","BatteryLevel":"25","FocusMode":"S-AF","FocusSupport":"0",
         "VideoFormat":"VGA_240","VASwitch":"ON","VAVol":"5","VANR":"OFF","VideoEis":"OFF",
         "SurplusPhotoCnts":"1291"}
    """.trimIndent()

    @Test
    fun `full live-view state block maps every documented field`() {
        val status = CameraProtocol.parseStatus(liveStateJson)
        assertTrue(status.connected)
        assertEquals(25, status.batteryPercent)
        assertEquals(1291, status.remainingShots)
        assertEquals(ExposureMode.S, status.exposureMode)
        assertEquals("Multi", status.meteringMode)
        assertEquals("S-AF", status.focusMode)
        assertEquals("20", status.imageQuality)
        assertEquals("4:3", status.imageAspect)
        assertEquals("RAWJ-L", status.fileFormat)
        assertEquals("Single", status.driveMode)
        assertEquals("1.7", status.fnumber)
        assertEquals("1.7", status.fnumberMin)
        assertEquals("16", status.fnumberMax)
        assertEquals("1s", status.shutterSpeed)
        assertEquals("0.0", status.ev)
        assertEquals("Auto", status.iso)
        assertEquals("2500", status.isoAutoValue)
        assertEquals("Auto", status.whiteBalance)
        assertEquals("Standard", status.colorMode)
        assertEquals("1", status.lensStatus)
        assertEquals(1, status.delayShootCount)
        assertFalse(status.focusSupport)
        assertTrue(status.lensReady)
        assertFalse(status.recording)
    }

    @Test
    fun `status parse is defensive for sparse, null or damaged payloads`() {
        // Sparse firmware: only the health keys; settings keep their defaults.
        val sparse = CameraProtocol.parseStatus(
            """{"batteryLevel":"87","SurplusPhotoCnts":"12","lenVer":"0.0","lenType":""}""",
        )
        assertEquals(87, sparse.batteryPercent)
        assertEquals(12, sparse.remainingShots)
        assertEquals("16", sparse.imageQuality)
        assertEquals(ExposureMode.AUTO, sparse.exposureMode)
        assertEquals("1/15s", sparse.shutterSpeed)

        for (bad in listOf(null, "", "   ", "garbage", "{}")) {
            val status = CameraProtocol.parseStatus(bad)
            assertNotNull(status)
        }
    }

    @Test
    fun `scene dial mode maps to C and unknown maps to Auto`() {
        val scene = CameraProtocol.parseStatus("""{"ExposureMode":"Scene"}""")
        assertEquals(ExposureMode.C, scene.exposureMode)
        val unknown = CameraProtocol.parseStatus("""{"ExposureMode":"Moon"}""")
        assertEquals(ExposureMode.AUTO, unknown.exposureMode)
    }

    @Test
    fun `50mp quality pins the image aspect to 4 by 3 like the official app`() {
        val status = CameraProtocol.parseStatus(
            """{"ImageQuality":"50","ImageAspect":"16:9","ShutterSpeed":"1/125s"}""",
        )
        assertEquals("50", status.imageQuality)
        assertEquals("4:3", status.imageAspect)
    }

    @Test
    fun `ev plus sign from the echo is normalized`() {
        assertEquals("1.0", CameraProtocol.parseStatus("""{"EV":"+1.0"}""").ev)
        assertEquals("-1.0", CameraProtocol.parseStatus("""{"EV":"-1.0"}""").ev)
    }

    @Test
    fun `battery 101 is external power and lens status semantics are exposed`() {
        assertEquals("101", CameraProtocol.parseStatus("""{"BatteryLevel":"101"}""").batteryPercent.toString())
        assertTrue(CameraProtocol.parseStatus("""{"BatteryLevel":"101"}""").isExternalPower)
        assertFalse(CameraProtocol.parseStatus("""{"BatteryLevel":"50"}""").isExternalPower)
        for (ready in listOf("1", "2")) {
            assertTrue(CameraProtocol.parseStatus("""{"LensStatus":"$ready"}""").lensReady)
        }
        for (notReady in listOf("0", "3")) {
            assertFalse(CameraProtocol.parseStatus("""{"LensStatus":"$notReady"}""").lensReady)
        }
    }

    // ------------------------------------------------------------------- gallery

    private val galleryJson = """
        {"code":200,"data":[
          {"path":"/DCIM/100YICAM/YI000001.JPG","date":"1789400001",
           "filetype":"picture","protectStatus":false},
          {"path":"/DCIM/100YICAM/P7020465.MP4","date":"1789400002",
           "filetype":"video","protectStatus":true},
          {"path":"/DCIM/100YICAM/YI000002.DNG","date":"1789400003",
           "filetype":"raw","protectStatus":false},
          {"path":"/DCIM/100YICAM/YI000003.JPG","date":"1789400004",
           "filetype":"rawJpeg","protectStatus":false}
        ]}
    """.trimIndent()

    @Test
    fun `gallery page parses verified item schema`() {
        val items = CameraProtocol.parseFileListResponse(galleryJson)
        assertEquals(4, items.size)
        // Newest first by capture time.
        assertEquals("/DCIM/100YICAM/YI000003.JPG", items[0].path)
        assertEquals("/DCIM/100YICAM/YI000001.JPG", items[3].path)

        val picture = items.first { it.path.endsWith("YI000001.JPG") }
        assertEquals(
            "/DCIM/100YICAM/YI000001.JPG",
            picture.path,
        )
        assertEquals("1789400001", picture.dateWire)
        assertEquals(1_789_400_001_000L, picture.capturedAtEpochMillis)
        assertEquals(CameraFileType.PICTURE, picture.fileType)
        assertFalse(picture.isProtected)
        assertEquals("YI000001.JPG", picture.fileName)
        assertEquals("jpg", picture.extension)

        val video = items.first { it.fileType == CameraFileType.VIDEO }
        assertTrue(video.isVideo)
        assertTrue(video.isProtected)

        val raw = items.first { it.fileType == CameraFileType.RAW }
        assertTrue(raw.isRaw)

        val rawJpeg = items.first { it.fileType == CameraFileType.RAW_JPEG }
        assertTrue(rawJpeg.isRaw)
        assertEquals("jpg", rawJpeg.extension)
    }

    @Test
    fun `gallery accepts string protectStatus and legacy jpeg filetype`() {
        val body = """{"code":200,"data":[{"path":"/DCIM/a.JPG","date":"5",
            "filetype":"jpeg","protectStatus":"true"}]}"""
        val item = CameraProtocol.parseFileListResponse(body).single()
        assertEquals(CameraFileType.PICTURE, item.fileType)
        assertTrue(item.isProtected)
    }

    @Test
    fun `end of album codes are treated as an empty page`() {
        assertTrue(
            CameraProtocol.parseFileListResponse("""{"code":1506,"data":"no pic anymore err"}""")
                .isEmpty(),
        )
        assertTrue(
            CameraProtocol.parseFileListResponse("""{"code":200,"data":[]}""").isEmpty(),
        )
        assertTrue(
            CameraProtocol.parseFileListResponse("""{"code":200,"data":null}""").isEmpty(),
        )
    }

    @Test
    fun `nested file list data is tolerated`() {
        val body = """{"code":200,"data":{"file_list":[
            {"path":"/DCIM/a.JPG","date":"1","filetype":"picture","protectStatus":false}]}}"""
        assertEquals(1, CameraProtocol.parseFileListResponse(body).size)
    }

    @Test
    fun `bad file range is an error, not an empty page`() {
        val error = try {
            CameraProtocol.parseFileListResponse("""{"code":1502,"data":"get filelist err"}""")
            null
        } catch (e: CameraApiException) {
            e
        }
        assertNotNull(error)
        assertEquals(1502, error!!.code)
    }

    // --------------------------------------------------------------------- focus

    @Test
    fun `focus mapping for 4 by 3 and 1 by 1 uses the 640 plus 40 convention`() {
        val center43 = CameraProtocol.focusPoint(0.5f, 0.5f, "4:3")
        assertEquals(360, center43.x)
        assertEquals(320, center43.y)

        val topLeft43 = CameraProtocol.focusPoint(0f, 0f, "4:3")
        assertEquals(40, topLeft43.x)
        assertEquals(0, topLeft43.y)

        val bottomRight43 = CameraProtocol.focusPoint(1f, 1f, "4:3")
        assertEquals(680, bottomRight43.x)
        assertEquals(640, bottomRight43.y)

        val square = CameraProtocol.focusPoint(0.25f, 0.75f, "1:1")
        assertEquals(200, square.x)
        assertEquals(480, square.y)
    }

    @Test
    fun `focus mapping for 3 by 2 and 16 by 9 uses the 720 convention with -30 y`() {
        val center169 = CameraProtocol.focusPoint(0.5f, 0.5f, "16:9")
        assertEquals(360, center169.x)
        assertEquals(330, center169.y)

        val center32 = CameraProtocol.focusPoint(0.5f, 0.5f, "3:2")
        assertEquals(360, center32.x)
        assertEquals(330, center32.y)

        val top169 = CameraProtocol.focusPoint(0f, 0f, "16:9")
        assertEquals(0, top169.x)
        assertEquals(-30, top169.y)
    }

    @Test
    fun `focus mapping clamps normalized input and defaults unknown aspects to 4 by 3`() {
        assertEquals(CameraProtocol.focusPoint(0f, 0f, "4:3"), CameraProtocol.focusPoint(-1f, -2f, "4:3"))
        assertEquals(CameraProtocol.focusPoint(1f, 1f, "4:3"), CameraProtocol.focusPoint(2f, 2f, "4:3"))
        assertEquals(CameraProtocol.focusPoint(0.5f, 0.5f, "4:3"), CameraProtocol.focusPoint(0.5f, 0.5f, null))
        assertEquals(CameraProtocol.focusPoint(0.5f, 0.5f, "4:3"), CameraProtocol.focusPoint(0.5f, 0.5f, "weird"))
    }

    // ------------------------------------------------------------------- capture

    @Test
    fun `capture timeout follows the official long-exposure scaling and caps at 90s`() {
        fun timeout(shutter: String, drive: String = "Single") =
            CameraProtocol.captureTimeoutMillis(CameraStatus(shutterSpeed = shutter, driveMode = drive))

        assertEquals(20_000L, timeout("1/125s"))
        assertEquals(20_000L, timeout("BULB"))
        assertEquals(20_000L, timeout("TIME"))
        assertEquals(40_000L, timeout("1s"))
        assertEquals(60_000L, timeout("2s"))
        assertEquals(80_000L, timeout("3s"))
        assertEquals(22_000L, timeout("10s")) // official /10 scaling above 10s
        assertEquals(90_000L, timeout("60s")) // 122 s clamped
        assertEquals(62_000L, timeout("30s")) // 20 s * 31 / 10
        assertEquals(22_000L, timeout("1/125s", "2SDelay"))
        assertEquals(20_000L, timeout("1/125s", "Continuous"))
        assertEquals(20_000L, CameraProtocol.captureTimeoutMillis(null))
    }

    @Test
    fun `long exposure seconds understands the verified display vocabulary`() {
        assertEquals(2L, CameraProtocol.longExposureSeconds("2s"))
        assertEquals(1L, CameraProtocol.longExposureSeconds("1s"))
        assertEquals(1L, CameraProtocol.longExposureSeconds("1.3s"))
        assertEquals(30L, CameraProtocol.longExposureSeconds("30s"))
        assertEquals(0L, CameraProtocol.longExposureSeconds("1/125s"))
        assertEquals(0L, CameraProtocol.longExposureSeconds("BULB"))
        assertEquals(0L, CameraProtocol.longExposureSeconds("TIME"))
        assertEquals(0L, CameraProtocol.longExposureSeconds(null))
        // Legacy enum-style form (old captures) still works.
        assertEquals(2L, CameraProtocol.longExposureSeconds("2\""))
    }

    // ------------------------------------------------------------------ firmware

    @Test
    fun `firmware version parsing handles BLE and HTTP style strings`() {
        assertEquals(3.2, CameraProtocol.parseFirmwareVersion("3.2"))
        assertEquals(3.2, CameraProtocol.parseFirmwareVersion("1,3.2-int,M1CN,0.0"))
        assertEquals(3.1, CameraProtocol.parseFirmwareVersion("3.1-cn"))
        assertNull(CameraProtocol.parseFirmwareVersion(null))
        assertNull(CameraProtocol.parseFirmwareVersion("unknown"))
        assertTrue(CameraProtocol.isMultiPacketFirmware("3.2"))
        assertTrue(CameraProtocol.isMultiPacketFirmware("1,3.2-int,M1CN,0.0"))
        assertFalse(CameraProtocol.isMultiPacketFirmware("3.1-cn"))
        assertFalse(CameraProtocol.isMultiPacketFirmware(null))
    }

    // ------------------------------------------------------------------- helpers

    @Test
    fun `tolerant boolean parser`() {
        assertTrue(CameraProtocol.parseBoolean("true")!!)
        assertTrue(CameraProtocol.parseBoolean("TRUE")!!)
        assertTrue(CameraProtocol.parseBoolean("1")!!)
        assertTrue(CameraProtocol.parseBoolean("ON")!!)
        assertFalse(CameraProtocol.parseBoolean("false")!!)
        assertFalse(CameraProtocol.parseBoolean("0")!!)
        assertNull(CameraProtocol.parseBoolean("maybe"))
        assertNull(CameraProtocol.parseBoolean(null))
    }

    @Test
    fun `epoch seconds parsing is tolerant`() {
        assertEquals(1789400001L, CameraProtocol.parseEpochSeconds("1789400001"))
        assertEquals(0L, CameraProtocol.parseEpochSeconds(""))
        assertEquals(0L, CameraProtocol.parseEpochSeconds("n/a"))
        assertEquals(0L, CameraProtocol.parseEpochSeconds(null))
    }
}
