package com.yim1remote.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UDP live-view receiver for the YI M1.
 *
 * The camera pushes JPEG preview frames to UDP port 54321. Each frame carries a
 * plaintext JSON snapshot of the camera state (the full 26-key state block, see
 * `research/verified/settings-and-status.md` §2) and the JPEG bytes. Two wire
 * formats exist and are detected per datagram by [LiveFrameParser] — the app has
 * no BLE, so it cannot read the firmware version the official app used to choose
 * a path:
 *
 *  * **Format A** (firmware < 3.2): one ~20 KB datagram per frame;
 *  * **Format B** (firmware >= 3.2): ~1.4 KB fragments reassembled by
 *    [MultiPacketAssembler].
 *
 * The socket is bound in [start]; call it **before** sending the camera's
 * start commands (otherwise the first frames are lost — a documented pitfall).
 * A 1 MB `SO_RCVBUF` is requested because a single Format A datagram is ~20 KB
 * and bursts are dropped when the kernel buffer is too small.
 *
 * Threading: [start] opens a background coroutine on [Dispatchers.IO]; datagram
 * reception and JPEG decoding happen off the main thread. Frames are published
 * through the hot [frames] flow sized to keep only the newest frame; the
 * consumer parses the state JSON at its own stride.
 */
class LiveViewReceiver(
    /** UDP port the camera streams to; the official app used 54321. */
    private val port: Int = DEFAULT_UDP_PORT,
    private val socketTimeoutMillis: Int = SOCKET_TIMEOUT_MS,
) {

    /** One decoded live-view frame. */
    data class ReceivedFrame(
        val bitmap: Bitmap,
        /** Raw JSON state block as sent by the camera (NUL padding stripped). */
        val paramsJson: String?,
        val receivedAtMillis: Long = System.currentTimeMillis(),
        /** Wire format that delivered this frame (diagnostics only). */
        val format: LiveFrameParser.LiveViewFormat = LiveFrameParser.LiveViewFormat.FORMAT_A,
        /** Camera frame counter (Format A) for gap diagnostics; -1 when unknown. */
        val frameIndex: Long = -1L,
    )

    private val _frames = MutableSharedFlow<ReceivedFrame>(
        // Keep only the newest frame: live view is a "latest wins" pipeline.
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Decoded frames; hot flow, emits on the IO dispatcher. */
    val frames: SharedFlow<ReceivedFrame> = _frames.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Non-fatal diagnostics (bind failures, undecodable streams, receive buffer). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiveJob: Job? = null

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var framesReceived: Long = 0
        private set

    @Volatile
    var framesDropped: Long = 0
        private set

    @Volatile
    var lastFrameAtMillis: Long = 0L
        private set

    /** Smoothed frame rate over the last 2 seconds (0 when no frames arrive). */
    @Volatile
    var recentFps: Float = 0f
        private set

    /** Kernel receive buffer actually granted (diagnostics; Format A needs ~1 MB). */
    @Volatile
    var receiveBufferBytes: Int = 0
        private set

    /** Count of Format A `frameIndex` jumps (packet-loss diagnostics). */
    @Volatile
    var frameIndexGaps: Long = 0
        private set

    private var lastFrameIndex: Long = -1L
    private val frameTimestamps = ArrayDeque<Long>()

    /** Binds the UDP port and starts receiving; no-op when already running. */
    fun start() {
        if (isRunning) return
        isRunning = true
        receiveJob = scope.launch { receiveLoop() }
    }

    /**
     * Stops reception and releases the port. The camera keeps streaming until the
     * caller sends `RCStopRemoteCtl`; [CameraRepositoryImpl.stopLiveView] does
     * both.
     */
    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {
            // Closing an already-closed socket is harmless.
        }
        socket = null
        receiveJob?.cancel()
        receiveJob = null
        recentFps = 0f
        frameTimestamps.clear()
        lastFrameIndex = -1L
    }

    /** Stops reception and releases the receiver's coroutine scope. */
    fun close() {
        stop()
        scope.cancel()
    }

    // ----------------------------------------------------------------- internals

    private suspend fun receiveLoop() {
        val assembler = MultiPacketAssembler()
        var unknownFormatNotified = false
        var firstDatagramLogged = false
        Log.i(TAG, "receiveLoop start, port=$port")
        // NOTE: capture the port in a local before `apply`: inside the DatagramSocket
        // scope, `port` would resolve to DatagramSocket.port (getPort(), -1 while
        // unbound) and shadow this receiver's field — that produced
        // "IllegalArgumentException: port out of range:-1" on the real device.
        val bindPort = port
        val datagramSocket = try {
            DatagramSocket(null as SocketAddress?).apply {
                reuseAddress = true
                // Request a big kernel buffer before binding: one Format A datagram
                // is ~20 KB and bursts overflow the tiny default.
                try {
                    receiveBufferSize = REQUESTED_RECEIVE_BUFFER_BYTES
                } catch (_: SocketException) {
                    // Not fatal; read back below and report the granted value.
                }
                bind(InetSocketAddress(bindPort))
            }
        } catch (e: Exception) {
            isRunning = false
            Log.e(TAG, "bind port $bindPort failed", e)
            _messages.tryEmit("无法监听直播端口 $bindPort：${e.message ?: e.javaClass.simpleName}")
            return
        }
        socket = datagramSocket
        Log.i(TAG, "bound UDP $port, rcvbuf=${datagramSocket.receiveBufferSize}")
        receiveBufferBytes = try {
            datagramSocket.receiveBufferSize
        } catch (_: SocketException) {
            0
        }
        try {
            datagramSocket.soTimeout = socketTimeoutMillis
        } catch (_: SocketException) {
            // Non-fatal: the receive loop then only exits via socket close.
        }
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (isRunning && currentCoroutineContext().isActive) {
                try {
                    packet.length = buffer.size
                    datagramSocket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: SocketException) {
                    break
                } catch (_: IOException) {
                    continue
                }
                val length = packet.length
                if (!firstDatagramLogged) {
                    firstDatagramLogged = true
                    Log.i(TAG, "first datagram len=$length from ${packet.address}")
                }
                when (LiveFrameParser.classifyDatagram(buffer, length)) {
                    LiveFrameParser.LiveViewFormat.FORMAT_B -> {
                        val payload = assembler.feed(buffer, length) ?: continue
                        val frame = LiveFrameParser.parseFormatBPayload(payload)
                        if (frame == null) {
                            framesDropped++
                            continue
                        }
                        deliver(
                            frame,
                            LiveFrameParser.LiveViewFormat.FORMAT_B,
                            assembler.lastCompletedFrameIndex,
                        )
                    }
                    LiveFrameParser.LiveViewFormat.FORMAT_A -> {
                        val frame = LiveFrameParser.parseFormatADatagram(buffer, length)
                        if (frame == null) {
                            framesDropped++
                            if (framesDropped % 30 == 1L) Log.w(TAG, "formatA parse failed len=$length dropped=$framesDropped")
                            continue
                        }
                        deliver(frame, LiveFrameParser.LiveViewFormat.FORMAT_A, frame.frameIndex)
                    }
                    LiveFrameParser.LiveViewFormat.UNKNOWN -> {
                        framesDropped++
                        if (!unknownFormatNotified) {
                            unknownFormatNotified = true
                            _messages.tryEmit("收到无法识别的直播数据（未知的实时取景封装）")
                        }
                    }
                }
            }
        } finally {
            try {
                datagramSocket.close()
            } catch (_: Exception) {
                // ignore
            }
            if (socket === datagramSocket) socket = null
            isRunning = false
            recentFps = 0f
        }
    }

    private fun deliver(
        frame: ParsedLiveFrame,
        format: LiveFrameParser.LiveViewFormat,
        frameIndex: Long,
    ) {
        val bitmap: Bitmap = try {
            BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
        } catch (e: Exception) {
            null
        } ?: run {
            framesDropped++
            return
        }
        val now = System.currentTimeMillis()
        framesReceived++
        lastFrameAtMillis = now
        trackRate(now)
        if (frameIndex >= 0) {
            if (lastFrameIndex >= 0 && frameIndex > lastFrameIndex + 1) {
                frameIndexGaps += frameIndex - lastFrameIndex - 1
            }
            lastFrameIndex = frameIndex
        }
        _frames.tryEmit(
            ReceivedFrame(
                bitmap = bitmap,
                paramsJson = frame.paramsJson,
                receivedAtMillis = now,
                format = format,
                frameIndex = frameIndex,
            ),
        )
    }

    /** Recent fps over a 2 s sliding window (the reference "link quality" metric). */
    private fun trackRate(now: Long) {
        frameTimestamps.addLast(now)
        while (frameTimestamps.isNotEmpty() && now - frameTimestamps.first() > RATE_WINDOW_MS) {
            frameTimestamps.removeFirst()
        }
        if (frameTimestamps.size >= 2) {
            val span = (now - frameTimestamps.first()).coerceAtLeast(1L)
            recentFps = frameTimestamps.size * 1000f / span
        }
    }

    companion object {
        private const val TAG = "YIM1-LiveView"

        /** Camera UDP port (hard-coded in the official app: `LiveView` bind 54321). */
        const val DEFAULT_UDP_PORT = 54321

        /** Big enough for one Format A whole frame (~20 KB) plus headroom. */
        private const val RECEIVE_BUFFER_BYTES = 64 * 1024

        /** Requested kernel receive buffer (Format A bursts). */
        private const val REQUESTED_RECEIVE_BUFFER_BYTES = 1024 * 1024

        private const val SOCKET_TIMEOUT_MS = 1000
        private const val RATE_WINDOW_MS = 2000L
    }
}
