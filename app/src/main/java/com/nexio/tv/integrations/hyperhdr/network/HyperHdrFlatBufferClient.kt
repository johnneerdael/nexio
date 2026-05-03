package com.nexio.tv.integrations.hyperhdr.network

import com.google.flatbuffers.FlatBufferBuilder
import hyperhdrnet.Command
import hyperhdrnet.Image
import hyperhdrnet.ImageType
import hyperhdrnet.NV12Image
import hyperhdrnet.P010Image
import hyperhdrnet.Register
import hyperhdrnet.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Minimal HyperHDR FlatBuffer client. Connects, registers, and sends NV12/P010 frames over
 * a length-prefixed FlatBuffer wire protocol. No reconnect/backoff (the calling code owns
 * the lifecycle); no auxiliary commands beyond Register + Image; no JSON API.
 *
 * Ported from eu.hyperhdr.android.flatbuf.HyperHdrFlatBufferClient
 * (commit 3fcee1d — defensive plane copies; commit ab53330 — direct-from-builder writeFrame).
 */
class HyperHdrFlatBufferClient(
    private val host: String,
    private val port: Int,
    private val priority: Int = 100,
    private val origin: String = "Nexio-HyperHDR",
    private val connectTimeoutMs: Int = 1_000,
) : FrameSink, Closeable {

    private sealed interface Outbound {
        data class Nv12(
            val yPlane: ByteArray, val uvPlane: ByteArray,
            val width: Int, val height: Int, val strideY: Int, val strideUv: Int,
        ) : Outbound
        data class P010(
            val yPlane: ByteArray, val uvPlane: ByteArray,
            val width: Int, val height: Int, val strideY: Int, val strideUv: Int,
        ) : Outbound
        object Register : Outbound
    }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private val outbox = Channel<Outbound>(Channel.CONFLATED)
    private var writerJob: Job? = null
    private var readerJob: Job? = null

    /** Owned exclusively by the writer coroutine; safe to reuse without synchronization. */
    private val builder = FlatBufferBuilder(64 * 1024)

    suspend fun connect() {
        withContext(Dispatchers.IO) {
            _state.value = ConnectionState.CONNECTING
            try {
                val targetPort = port   // capture before apply — inside apply, `port` resolves to Socket.getPort()
                val sock = Socket().apply {
                    tcpNoDelay = true
                    sendBufferSize = 32 * 1024
                    receiveBufferSize = 4096
                    connect(InetSocketAddress(host, targetPort), connectTimeoutMs)
                }
                socket = sock
                output = DataOutputStream(sock.getOutputStream())
                writeOutbound(Outbound.Register)   // Synchronous Register
                writerJob = scope.launch { for (msg in outbox) writeOutbound(msg) }
                // Background reader: detect server-side disconnect (EOF or error → ERROR state).
                readerJob = scope.launch(Dispatchers.IO) {
                    try {
                        val input = sock.getInputStream()
                        val buf = ByteArray(256)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break   // EOF — server closed connection
                        }
                    } catch (_: Exception) { /* socket closed */ }
                    if (_state.value == ConnectionState.CONNECTED) {
                        _state.value = ConnectionState.ERROR
                        runCatching { socket?.close() }
                    }
                }
                _state.value = ConnectionState.CONNECTED
            } catch (e: Throwable) {
                _state.value = ConnectionState.ERROR
                runCatching { socket?.close() }
                socket = null; output = null
                throw e
            }
        }
    }

    override fun sendNv12(
        yPlane: ByteArray, uvPlane: ByteArray,
        width: Int, height: Int, strideY: Int, strideUv: Int,
    ) {
        outbox.trySend(Outbound.Nv12(
            yPlane.copyOf(), uvPlane.copyOf(), width, height, strideY, strideUv))
    }

    override fun sendP010(
        yPlane: ByteArray, uvPlane: ByteArray,
        width: Int, height: Int, strideY: Int, strideUv: Int,
    ) {
        outbox.trySend(Outbound.P010(
            yPlane.copyOf(), uvPlane.copyOf(), width, height, strideY, strideUv))
    }

    private fun writeOutbound(msg: Outbound) {
        val out = output ?: return
        builder.clear()
        val reqOff = when (msg) {
            is Outbound.Register -> {
                val originOff = builder.createString(origin)
                val regOff = Register.createRegister(builder, originOff, priority)
                Request.createRequest(builder, Command.Register, regOff)
            }
            is Outbound.Nv12 -> {
                val yOff = NV12Image.createDataYVector(builder, msg.yPlane)
                val uvOff = NV12Image.createDataUvVector(builder, msg.uvPlane)
                val nv12Off = NV12Image.createNV12Image(
                    builder, yOff, uvOff, msg.width, msg.height, msg.strideY, msg.strideUv,
                )
                val imgOff = Image.createImage(builder, ImageType.NV12Image, nv12Off, -1)
                Request.createRequest(builder, Command.Image, imgOff)
            }
            is Outbound.P010 -> {
                val yOff = P010Image.createDataYVector(builder, msg.yPlane)
                val uvOff = P010Image.createDataUvVector(builder, msg.uvPlane)
                val p010Off = P010Image.createP010Image(
                    builder, yOff, uvOff, msg.width, msg.height, msg.strideY, msg.strideUv,
                )
                val imgOff = Image.createImage(builder, ImageType.P010Image, p010Off, -1)
                Request.createRequest(builder, Command.Image, imgOff)
            }
        }
        Request.finishRequestBuffer(builder, reqOff)
        writeFrame(out, builder.dataBuffer())
    }

    private fun writeFrame(out: DataOutputStream, buf: ByteBuffer) {
        try {
            val size = buf.remaining()
            out.writeInt(size)
            out.write(buf.array(), buf.arrayOffset() + buf.position(), size)
            out.flush()
        } catch (_: IOException) {
            _state.value = ConnectionState.ERROR
            runCatching { socket?.close() }
            output = null
        }
    }

    override fun close() {
        writerJob?.cancel(); writerJob = null
        readerJob?.cancel(); readerJob = null
        outbox.close()
        scope.cancel()
        runCatching { socket?.close() }
        socket = null; output = null
        _state.value = ConnectionState.DISCONNECTED
    }
}
