package com.nexio.tv.integrations.hyperhdr.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Connection-resilient wrapper around [HyperHdrFlatBufferClient]. Opens a fresh client,
 * observes its state, and on ERROR/DISCONNECTED closes the client and reopens after a
 * backoff delay. Exposes its own [state] StateFlow that consumers (e.g. the player-overlay
 * badge) can subscribe to.
 *
 * Ported from HyperHDR-android :common (eu.hyperhdr.android.flatbuf.HyperHdrFlatBufferReconnector).
 * Trimmed: stats collector + Color/Clear commands (capture-only feature).
 */
class HyperHdrFlatBufferReconnector(
    private val host: String,
    private val port: Int,
    private val priority: Int = 100,
    private val origin: String = "Nexio-HyperHDR",
    private val backoff: BackoffSchedule = BackoffSchedule(),
) : FrameSink, Closeable {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    @Volatile private var current: HyperHdrFlatBufferClient? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        while (true) {
            _state.value = ConnectionState.CONNECTING
            val client = HyperHdrFlatBufferClient(host, port, priority, origin)
            try {
                client.connect()
                current = client
                backoff.reset()
                _state.value = ConnectionState.CONNECTED

                client.state.collect { s ->
                    if (s == ConnectionState.ERROR || s == ConnectionState.DISCONNECTED) {
                        throw java.io.IOException("client transitioned to $s")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = ConnectionState.ERROR
                runCatching { current?.close() }
                current = null
                delay(backoff.nextDelayMs())
            }
        }
    }

    override fun sendNv12(yPlane: ByteArray, uvPlane: ByteArray, width: Int, height: Int, strideY: Int, strideUv: Int) {
        current?.sendNv12(yPlane, uvPlane, width, height, strideY, strideUv)
    }

    override fun sendP010(yPlane: ByteArray, uvPlane: ByteArray, width: Int, height: Int, strideY: Int, strideUv: Int) {
        current?.sendP010(yPlane, uvPlane, width, height, strideY, strideUv)
    }

    override fun close() {
        loop?.cancel(); loop = null
        runCatching { current?.close() }
        current = null
        scope.cancel()
        _state.value = ConnectionState.DISCONNECTED
    }
}
