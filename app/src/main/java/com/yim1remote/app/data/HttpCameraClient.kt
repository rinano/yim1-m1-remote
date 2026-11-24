package com.yim1remote.app.data

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal plain-HTTP transport for the YI M1 camera API.
 *
 * The camera serves `GET http://192.168.0.10/?data=<url-encoded JSON>` and has no
 * authentication, no cookies, no redirects and no TLS. That is small enough for
 * `HttpURLConnection`; the project's offline Gradle cache intentionally contains
 * no HTTP client library (no OkHttp/Retrofit), and the JDK client is fully
 * unit-testable against a loopback `HttpServer`.
 *
 * Responsibilities are transport-only: build the URL, send GET, read raw bytes
 * or text, enforce connect/read timeouts and turn non-2xx HTTP responses into
 * [IOException]s with a short body snippet. Camera envelope semantics
 * (`{"code":200,...}`) live in [CameraProtocol]; media bytes are returned raw.
 *
 * Threading: every `suspend` function switches to [Dispatchers.IO]; callers may
 * invoke them from the main thread.
 *
 * The [baseUrl] property is mutable so the repository can switch the endpoint for
 * a session (persisted setting or a loopback mock server) without recreating the
 * client. It is read on every request.
 */
class HttpCameraClient(
    initialBaseUrl: String = CameraConfig.DEFAULT_BASE_URL,
) {

    @Volatile
    var baseUrl: String = normalizeBaseUrl(initialBaseUrl)
        set(value) {
            field = normalizeBaseUrl(value)
        }

    /** One control/JSON request: returns the response body decoded as UTF-8 text. */
    suspend fun getText(
        commandJson: String,
        timeoutMillis: Int = CameraProtocol.CONTROL_TIMEOUT_MS,
    ): String = withContext(Dispatchers.IO) {
        String(executeRequest(commandJson, timeoutMillis), Charsets.UTF_8)
    }

    /** One request whose response is binary (thumbnail preview, `GetFile` body, ...). */
    suspend fun getBytes(
        commandJson: String,
        timeoutMillis: Int = CameraProtocol.TRANSFER_TIMEOUT_MS,
    ): ByteArray = withContext(Dispatchers.IO) {
        executeRequest(commandJson, timeoutMillis)
    }

    /**
     * Streams a `GetFile` response straight into [sink] (large originals never sit
     * in memory) and reports progress.
     *
     * If the camera answers with a JSON envelope instead of file bytes (e.g.
     * `{"code":204,...}` "no thumbnail", `{"code":1506,...}` "no such file") this
     * throws [CameraApiException] so callers can present the camera's reason.
     *
     * @return number of bytes written to [sink].
     */
    suspend fun downloadTo(
        commandJson: String,
        sink: OutputStream,
        timeoutMillis: Int = CameraProtocol.TRANSFER_TIMEOUT_MS,
        progress: (Long, Long?) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        downloadToBlocking(commandJson, sink, timeoutMillis, progress)
    }

    // ----------------------------------------------------------------- transport

    private fun executeRequest(commandJson: String, timeoutMillis: Int): ByteArray {
        val connection = openConnection(commandJson, timeoutMillis)
        try {
            val status = connection.responseCode
            // HTTP 204 is how the camera rejects a rendition it cannot produce
            // (e.g. RAW Thumbnail): not a missing file, an unavailable preview.
            if (status == 204) {
                throw CameraApiException(CameraProtocol.CODE_NO_THUMBNAIL, "无缩略图（HTTP 204）")
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let { readAllLimited(it, MAX_IN_MEMORY_RESPONSE_BYTES) } ?: ByteArray(0)
            if (status !in 200..299) {
                throw IOException("相机返回 HTTP $status${snippetSuffix(body)}")
            }
            if (body.isEmpty()) throw IOException("相机返回了空响应体（HTTP $status）")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToBlocking(
        commandJson: String,
        sink: OutputStream,
        timeoutMillis: Int,
        progress: (Long, Long?) -> Unit,
    ): Long {
        val connection = openConnection(commandJson, timeoutMillis)
        Log.i("YIM1-Camera", "downloadTo start: ${connection.url}")
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = connection.errorStream?.let { readAllLimited(it, 2048) } ?: ByteArray(0)
                throw IOException("相机返回 HTTP $status${snippetSuffix(errorBody)}")
            }
            if (status == 204) {
                throw CameraApiException(CameraProtocol.CODE_NO_THUMBNAIL, "无缩略图")
            }
            Log.i(
                "YIM1-Camera",
                "downloadTo response=$status contentLength=${connection.contentLengthLong} " +
                    "contentType=${connection.contentType}",
            )
            val contentLength = connection.contentLengthLong.takeIf { it > 0 }
            val contentType = connection.contentType
            BufferedInputStream(connection.inputStream, IO_BUFFER_BYTES).use { input ->
                // Sniff enough bytes to tell a JSON error envelope from raw media.
                val sniff = ByteArray(SNIFF_BYTES)
                var sniffLength = 0
                while (sniffLength < sniff.size) {
                    val read = input.read(sniff, sniffLength, sniff.size - sniffLength)
                    if (read < 0) break
                    sniffLength += read
                }
                val firstContent = firstNonWhitespace(sniff, sniffLength)
                val looksLikeJson = firstContent >= 0 &&
                    sniff[firstContent] == '{'.code.toByte() &&
                    contentType?.startsWith("image/") != true &&
                    contentType?.startsWith("video/") != true
                if (looksLikeJson) {
                    val rest = readAllLimited(input, MAX_IN_MEMORY_RESPONSE_BYTES)
                    val body = ByteArray(sniffLength + rest.size)
                    System.arraycopy(sniff, 0, body, 0, sniffLength)
                    System.arraycopy(rest, 0, body, sniffLength, rest.size)
                    val envelope = CameraProtocol.parseEnvelope(String(body, Charsets.UTF_8))
                    if (envelope.isSuccess) {
                        throw CameraProtocolException("相机返回了 JSON 而不是文件内容（可能是空文件）")
                    }
                    throw CameraApiException(envelope.code, envelope.dataOrNull)
                }
                if (sniffLength == 0) {
                    throw CameraProtocolException("相机返回了空文件内容（HTTP $status）")
                }
                var written = 0L
                sink.write(sniff, 0, sniffLength)
                written += sniffLength
                progress(written, contentLength)
                val chunk = ByteArray(IO_BUFFER_BYTES)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    if (read == 0) continue
                    sink.write(chunk, 0, read)
                    written += read
                    progress(written, contentLength)
                }
                sink.flush()
                Log.i("YIM1-Camera", "downloadTo done: $written bytes")
        return written
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(commandJson: String, timeoutMillis: Int): HttpURLConnection {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val url = URL(CameraProtocol.buildUrl(baseUrl, commandJson))
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.doInput = true
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Connection", "close")
        return connection
    }

    // ------------------------------------------------------------------- helpers

    private fun readAllLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(IO_BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) {
                throw IOException("相机响应超过 $maxBytes 字节上限")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun firstNonWhitespace(data: ByteArray, length: Int): Int {
        for (index in 0 until length) {
            when (data[index].toInt() and 0xFF) {
                0, ' '.code, '\n'.code, '\r'.code, '\t'.code -> Unit
                else -> return index
            }
        }
        return -1
    }

    private fun snippetSuffix(body: ByteArray): String {
        if (body.isEmpty()) return ""
        val text = String(body, 0, minOf(body.size, 256), Charsets.UTF_8)
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (text.isEmpty()) "" else "：$text"
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return CameraConfig.DEFAULT_BASE_URL
        return trimmed.trimEnd('/')
    }

    private companion object {
        const val IO_BUFFER_BYTES = 16 * 1024
        const val SNIFF_BYTES = 64
        const val MAX_IN_MEMORY_RESPONSE_BYTES = 32 * 1024 * 1024
    }
}
