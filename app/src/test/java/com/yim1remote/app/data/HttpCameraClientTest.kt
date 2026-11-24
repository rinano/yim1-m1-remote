package com.yim1remote.app.data

import com.yim1remote.app.data.model.CameraFileType
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * End-to-end transport test: a tiny JDK-socket HTTP/1.1 server emulates the
 * camera's `GET /?data=<json>` endpoint on loopback, including raw `GetFile`
 * bytes, HTTP-level errors, JSON-envelope errors and read timeouts.
 *
 * (`com.sun.net.httpserver` is not on the Android unit-test compile classpath,
 * so the fake server uses plain `java.net.ServerSocket` which is available both
 * to android.jar and to the JVM test runtime.)
 */
class HttpCameraClientTest {

    private lateinit var server: FakeCameraServer
    private lateinit var client: HttpCameraClient

    @Before
    fun setUp() {
        server = FakeCameraServer().also { it.start() }
        client = HttpCameraClient("http://127.0.0.1:${server.port}")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun decodeDataParam(rawQuery: String?): String? {
        val query = rawQuery ?: return null
        for (part in query.split('&')) {
            if (part.startsWith("data=")) {
                return URLDecoder.decode(part.removePrefix("data="), "UTF-8")
            }
        }
        return null
    }

    // -------------------------------------------------------------------- tests

    @Test
    fun `getText fetches and parses the status envelope over loopback`() = runBlocking {
        val body = client.getText(CameraJson.getCameraStatus())
        val envelope = CameraProtocol.requireSuccess(body)
        val health = CameraProtocol.parseHealthStatus(envelope.data)
        assertEquals(87, health.batteryPercent)
        assertEquals(123, health.remainingShots)
        assertEquals("1.0", health.lensVersion)
        assertEquals("YI_12-40_3.5-5.6", health.lensType)
        // The camera API is GET-only and the command travels in the data query param.
        assertEquals("GET", server.lastMethod)
        assertEquals("GetCameraStatus", server.lastCommandName)
    }

    @Test
    fun `command json is percent-encoded into the data query parameter`() = runBlocking {
        client.getText(CameraJson.getFile("/DCIM/100YICAM/YI000001.JPG", "1789400001", "Original"))
        assertEquals("GetFile", server.lastCommandName)
        val rawQuery = server.lastRawQuery
        assertNotNull(rawQuery)
        // Percent-encoded on the wire; decoding yields the exact compact JSON.
        assertTrue(rawQuery!!.contains("%7B"))
        assertTrue(rawQuery.contains("%22command%22"))
        val decoded = decodeDataParam(rawQuery)
        assertNotNull(decoded)
        val parsed = JSONObject(decoded!!)
        assertEquals("GetFile", parsed.getString("command"))
        assertEquals("/DCIM/100YICAM/YI000001.JPG", parsed.getString("path"))
        assertEquals("1789400001", parsed.getString("date"))
        assertEquals("Original", parsed.getString("resulotion"))
    }

    @Test
    fun `getFileList body parses into gallery items`() = runBlocking {
        val body = client.getText(CameraJson.getFileList(1, 60))
        val items = CameraProtocol.parseFileListResponse(body)
        assertEquals(2, items.size)
        assertEquals("/DCIM/100YICAM/YI000001.JPG", items[1].path)
        assertEquals(CameraFileType.PICTURE, items[1].fileType)
        assertEquals(1789400001L, items[1].dateWire.toLong())
    }

    @Test
    fun `getBytes returns raw GetFile bytes for an original`() = runBlocking {
        val bytes = client.getBytes(
            CameraJson.getFile("/DCIM/a.JPG", "1789400001", "Original"),
        )
        assertArrayEquals(FakeCameraServer.originalJpeg, bytes)
    }

    @Test
    fun `http 204 rendition rejection becomes a typed camera error`() = runBlocking {
        try {
            client.getBytes(CameraJson.getFile("/DCIM/a.JPG", "1", "MidThumb"))
            fail("expected CameraApiException")
        } catch (e: CameraApiException) {
            assertEquals(CameraProtocol.CODE_NO_THUMBNAIL, e.code)
        }
    }

    @Test
    fun `downloadTo streams bytes and reports progress`() = runBlocking {
        val sink = ByteArrayOutputStream()
        var lastBytes = 0L
        var lastTotal: Long? = null
        val written = client.downloadTo(
            CameraJson.getFile("/DCIM/large.JPG", "1", "Original"),
            sink,
            progress = { bytes, total ->
                lastBytes = bytes
                lastTotal = total
            },
        )
        assertEquals(FakeCameraServer.largeFile.size.toLong(), written)
        assertArrayEquals(FakeCameraServer.largeFile, sink.toByteArray())
        assertEquals(FakeCameraServer.largeFile.size.toLong(), lastBytes)
        assertEquals(FakeCameraServer.largeFile.size.toLong(), lastTotal)
    }

    @Test
    fun `downloadTo converts a json error envelope into a camera error`() = runBlocking {
        val sink = ByteArrayOutputStream()
        try {
            client.downloadTo(
                CameraJson.getFile("/DCIM/missing.JPG", "1", "Original"),
                sink,
            )
            fail("expected CameraApiException")
        } catch (e: CameraApiException) {
            assertEquals(CameraProtocol.CODE_FILE_NOT_FOUND, e.code)
            assertEquals("no pic anymore err", e.cameraMessage)
        }
        assertEquals(0, sink.size())
    }

    @Test
    fun `http 404 is a transport error with the status in the message`() = runBlocking {
        try {
            client.getText("""{"command":"NoSuchCommand"}""")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
        }
    }

    @Test
    fun `non-json bodies surface as protocol errors in the envelope layer`() = runBlocking {
        val body = client.getText("""{"command":"BrokenJson"}""")
        try {
            CameraProtocol.parseEnvelope(body)
            fail("expected CameraProtocolException")
        } catch (e: CameraProtocolException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }

    @Test
    fun `empty bodies are reported as errors, never decoded`() = runBlocking {
        try {
            client.getText("""{"command":"EmptyBody"}""")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("空响应体"))
        }
    }

    @Test
    fun `read timeout is a SocketTimeoutException`() = runBlocking {
        try {
            client.getText(CameraJson.checkPreUpdate(), timeoutMillis = 200)
            fail("expected SocketTimeoutException")
        } catch (e: java.net.SocketTimeoutException) {
            // expected
        }
    }

    // ------------------------------------------------------------ fake camera

    /** Minimal blocking HTTP/1.1 server that speaks just enough for the client. */
    private class FakeCameraServer : Closeable {

        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val running = AtomicBoolean(true)

        @Volatile
        var lastMethod: String? = null

        @Volatile
        var lastRawQuery: String? = null

        @Volatile
        var lastCommandName: String? = null

        val port: Int get() = serverSocket.localPort

        fun start() {
            executor.execute {
                while (running.get()) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    executor.execute { handleSafely(socket) }
                }
            }
        }

        override fun close() {
            running.set(false)
            try {
                serverSocket.close()
            } catch (_: IOException) {
                // ignore
            }
            executor.shutdownNow()
        }

        private fun handleSafely(socket: Socket) {
            try {
                socket.use { handle(it) }
            } catch (_: Exception) {
                // client aborted (e.g. the timeout test) — expected.
            }
        }

        private fun handle(socket: Socket) {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            lastMethod = parts.getOrNull(0)
            val target = parts.getOrNull(1) ?: ""
            val rawQuery = target.substringAfter('?', missingDelimiterValue = "")
            lastRawQuery = rawQuery.ifEmpty { null }
            // Drain request headers.
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            val decoded = decodeQueryData(lastRawQuery)
            val command = try {
                if (decoded == null) null else JSONObject(decoded)
            } catch (_: Exception) {
                null
            }
            lastCommandName = command?.optString("command")
            val output = socket.getOutputStream()
            when (command?.optString("command")) {
                "GetCameraStatus" -> writeResponse(output, 200, "application/json", statusJson.toByteArray())
                "RCStartRemoteCtl", "StartLiveview", "RCStopRemoteCtl" ->
                    writeResponse(output, 200, "application/json", """{"code":200}""".toByteArray())
                "GetFileList" -> writeResponse(output, 200, "application/json", fileListJson.toByteArray())
                "CheckPreUpdate" -> {
                    Thread.sleep(1_500) // read-timeout test target
                    writeResponse(output, 200, "application/json", """{"code":200}""".toByteArray())
                }
                "GetFile" -> {
                    val path = command.optString("path")
                    val resolution = command.optString("resulotion")
                    when {
                        path.contains("missing") -> writeResponse(
                            output, 200, "application/json",
                            """{"code":1506,"data":"no pic anymore err"}""".toByteArray(),
                        )
                        path.contains("large") -> writeResponse(output, 200, "image/jpeg", largeFile)
                        resolution == "MidThumb" -> writeResponse(output, 204, null, ByteArray(0))
                        else -> writeResponse(output, 200, "image/jpeg", originalJpeg)
                    }
                }
                "BrokenJson" -> writeResponse(output, 200, "text/plain", "<html>oops</html>".toByteArray())
                "EmptyBody" -> writeResponse(output, 200, null, ByteArray(0))
                else -> writeResponse(output, 404, null, ByteArray(0))
            }
            output.flush()
        }

        private fun decodeQueryData(rawQuery: String?): String? {
            val query = rawQuery ?: return null
            for (part in query.split('&')) {
                if (part.startsWith("data=")) {
                    return URLDecoder.decode(part.removePrefix("data="), "UTF-8")
                }
            }
            return null
        }

        private fun writeResponse(output: OutputStream, status: Int, contentType: String?, body: ByteArray) {
            val reason = when (status) {
                200 -> "OK"
                204 -> "No Content"
                404 -> "Not Found"
                else -> "Error"
            }
            val header = StringBuilder("HTTP/1.1 $status $reason\r\n")
            if (contentType != null) header.append("Content-Type: $contentType\r\n")
            if (status == 204) {
                header.append("Connection: close\r\n\r\n")
                output.write(header.toString().toByteArray())
                return
            }
            header.append("Content-Length: ${body.size}\r\n")
            header.append("Connection: close\r\n\r\n")
            output.write(header.toString().toByteArray())
            if (body.isNotEmpty()) output.write(body)
        }

        private fun readLine(input: BufferedInputStream): String? {
            val builder = StringBuilder()
            while (true) {
                val value = input.read()
                if (value < 0) return if (builder.isEmpty()) null else builder.toString()
                if (value == '\n'.code) break
                if (value != '\r'.code) builder.append(value.toChar())
            }
            return builder.toString()
        }

        companion object {
            val statusJson =
                """{"code":200,"data":{"batteryLevel":"87","SurplusPhotoCnts":"123","lenVer":"1.0","lenType":"YI_12-40_3.5-5.6"}}"""

            val fileListJson = """{"code":200,"data":[
                {"path":"/DCIM/100YICAM/YI000001.JPG","date":"1789400001","filetype":"picture","protectStatus":false},
                {"path":"/DCIM/100YICAM/P7020465.MP4","date":"1789400002","filetype":"video","protectStatus":false}
            ]}"""

            val originalJpeg: ByteArray = ByteArray(2048).apply {
                this[0] = 0xFF.toByte()
                this[1] = 0xD8.toByte()
                this[2] = 0xFF.toByte()
                this[3] = 0xDB.toByte()
                for (index in 4 until size - 2) this[index] = 0x40
                this[size - 2] = 0xFF.toByte()
                this[size - 1] = 0xD9.toByte()
            }

            val largeFile: ByteArray = ByteArray(300_000) { index -> (index % 251).toByte() }
        }
    }
}
