package com.yim1remote.app.data

import com.yim1remote.app.ui.util.CameraFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `GetFileInfo` metadata parsing + camera timestamp presentation.
 *
 * The JSON below is a **real reply captured from a physical YI M1** (2026-11-24,
 * `GET http://192.168.0.10/?data={"command":"GetFileInfo","path":"/DCIM/100YICAM/PB230128.DNG"}`),
 * so these tests pin the hardware-verified wire names.
 */
class CameraFileInfoTest {

    private val realRawReply = """
        {"code":200,"data":{"type":"PICRAW","size":"31932416","width":"5200",
        "height":"3902","capture_date":"1795476953","shutter_speed":"1/25s",
        "FNumber":"2.0","WB":"Auto","ISO":"1600","FocalLength":"43",
        "Make":"YI TECHNOLOGY","focus_mode":"S-AF","model":"M1"}}
    """.trimIndent()

    private val realJpegReply = """
        {"code":200,"data":{"type":"PICNORMAL","size":"4286592","width":"5184",
        "height":"3888","capture_date":"1795479160","shutter_speed":"1/50s",
        "FNumber":"2.0","WB":"Auto","ISO":"1600","FocalLength":"43",
        "Make":"YI TECHNOLOGY","focus_mode":"S-AF","model":"M1"}}
    """.trimIndent()

    @Test
    fun `parses the hardware GetFileInfo reply`() {
        val info = CameraProtocol.parseFileInfoResponse(realRawReply)
        requireNotNull(info)
        assertEquals("PICRAW", info.type)
        assertEquals(31_932_416L, info.sizeBytes)
        assertEquals(5200, info.width)
        assertEquals(3902, info.height)
        assertEquals(1_795_476_953L, info.captureDateSeconds)
        assertEquals("1/25s", info.shutterSpeed)
        assertEquals("2.0", info.aperture)
        assertEquals("Auto", info.whiteBalance)
        assertEquals("1600", info.iso)
        assertEquals("43", info.focalLengthMm)
        assertEquals("YI TECHNOLOGY", info.make)
        assertEquals("M1", info.model)
        assertEquals("S-AF", info.focusMode)
        assertEquals("5200x3902", info.dimensions)
        assertNull(info.durationSeconds)
    }

    @Test
    fun `parses a JPEG file reply and keeps dimensions`() {
        val info = CameraProtocol.parseFileInfoResponse(realJpegReply)
        requireNotNull(info)
        assertEquals("PICNORMAL", info.type)
        assertEquals(5_184, info.width)
        assertEquals(3_888, info.height)
        assertEquals(4_286_592L, info.sizeBytes)
    }

    @Test
    fun `tolerates case differences and numeric values`() {
        val info = CameraProtocol.parseFileInfo(
            """{"Type":"MOVIE","SIZE":123,"Width":1920,"height":1080,
               "capture_date":1620000000,"FNumber":4.5,"iso":800,
               "focal_length":"18","video_duration":"00:12"}""",
        )
        requireNotNull(info)
        assertEquals("MOVIE", info.type)
        assertEquals(123L, info.sizeBytes)
        assertEquals(1920, info.width)
        assertEquals(1080, info.height)
        assertEquals("4.5", info.aperture)
        assertEquals("800", info.iso)
        assertEquals("18", info.focalLengthMm)
        assertEquals(12L, info.durationSeconds)
    }

    @Test
    fun `returns null for camera errors and malformed bodies`() {
        assertNull(CameraProtocol.parseFileInfoResponse("""{"code":1000,"data":"no such file"}"""))
        assertNull(CameraProtocol.parseFileInfoResponse("""{"code":1506}"""))
        assertNull(CameraProtocol.parseFileInfoResponse("not json"))
        assertNull(CameraProtocol.parseFileInfoResponse(""))
        assertNull(CameraProtocol.parseFileInfo(null))
        assertNull(CameraProtocol.parseFileInfo("null"))
        assertNull(CameraProtocol.parseFileInfo("""{"other":"x"}"""))
    }

    @Test
    fun `duration parser covers camera spellings`() {
        assertEquals(12L, CameraProtocol.parseDurationSeconds("12"))
        assertEquals(12L, CameraProtocol.parseDurationSeconds("12s"))
        assertEquals(12L, CameraProtocol.parseDurationSeconds("00:12"))
        assertEquals(83L, CameraProtocol.parseDurationSeconds("1:23"))
        assertEquals(3_723L, CameraProtocol.parseDurationSeconds("1:02:03"))
        assertEquals(90L, CameraProtocol.parseDurationSeconds("90"))
        assertNull(CameraProtocol.parseDurationSeconds(null))
        assertNull(CameraProtocol.parseDurationSeconds(""))
        assertNull(CameraProtocol.parseDurationSeconds("abc"))
    }

    /**
     * The camera has no RTC: it stores **wall-clock time as epoch seconds**
     * (BLE time sync writes `(now + zoneOffset)`), and the official app displays
     * those values in **GMT/UTC**. Formatting them in the device timezone shifted
     * every photo by +8 h in China — this test pins the UTC behaviour.
     */
    @Test
    fun `camera timestamps are rendered in UTC`() {
        // Hardware: PB230128.DNG capture_date = 1795476953 → 2026-11-23 23:35 UTC,
        // which is the wall-clock time the photo was actually taken.
        assertEquals("2026-11-23 23:35", CameraFormat.cameraDate(1_795_476_953L))
        assertEquals("2026-11-24 00:12", CameraFormat.cameraDateFromMillis(1_795_479_140_000L))
        assertNull(CameraFormat.cameraDate(0L))
        assertNull(CameraFormat.cameraDate(null))
    }

    @Test
    fun `file size and duration helpers`() {
        assertEquals("1023 B", CameraFormat.fileSize(1_023L))
        assertEquals("1 KB", CameraFormat.fileSize(1_024L))
        assertEquals("31.1 MB", CameraFormat.fileSize(32_616_448L)) // 31.11 MiB → 31.1
        assertEquals("0:12", CameraFormat.duration(12L))
        assertEquals("1:23", CameraFormat.duration(83L))
        assertEquals("1:02:03", CameraFormat.duration(3_723L))
        assertNull(CameraFormat.fileSize(0L))
        assertNull(CameraFormat.fileSize(null))
        assertNull(CameraFormat.duration(null))
    }
}
