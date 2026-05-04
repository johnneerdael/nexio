package com.nexio.tv.integrations.hyperhdr.network

import kotlinx.coroutines.channels.Channel
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class FakeHyperHdrServer : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    private val frames = Channel<ByteArray>(Channel.UNLIMITED)
    private val acceptedSocket = AtomicReference<Socket?>(null)
    @Volatile private var stopped = false

    init {
        thread(name = "FakeHyperHdrServer-accept", isDaemon = true) {
            try {
                while (!stopped) {
                    val sock = server.accept()
                    acceptedSocket.set(sock)
                    thread(name = "FakeHyperHdrServer-read", isDaemon = true) { readFrames(sock) }
                }
            } catch (_: Exception) { /* server closed */ }
        }
    }

    suspend fun receiveFrame(): ByteArray = frames.receive()

    fun forceDisconnect() { acceptedSocket.get()?.close() }

    private fun readFrames(sock: Socket) {
        try {
            val input = DataInputStream(sock.getInputStream())
            while (!stopped) {
                val length = input.readInt()
                require(length in 1..(64 * 1024 * 1024)) { "implausible frame length $length" }
                val payload = ByteArray(length)
                input.readFully(payload)
                frames.trySend(payload)
            }
        } catch (_: Exception) { /* client gone */ }
    }

    override fun close() {
        stopped = true
        runCatching { server.close() }
        runCatching { acceptedSocket.get()?.close() }
        frames.close()
    }
}
