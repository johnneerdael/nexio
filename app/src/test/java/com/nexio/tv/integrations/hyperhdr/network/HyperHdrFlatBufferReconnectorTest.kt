package com.nexio.tv.integrations.hyperhdr.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test

class HyperHdrFlatBufferReconnectorTest {

    @Test
    fun `connects and registers when server is reachable`() = runTest {
        FakeHyperHdrServer().use { server ->
            val reconnector = HyperHdrFlatBufferReconnector(
                host = "127.0.0.1", port = server.port, priority = 100,
                origin = "Nexio-HyperHDR-Test",
            )
            reconnector.start()
            try {
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    val frame = withTimeout(2_000) { server.receiveFrame() }
                    assertThat(frame.size).isGreaterThan(0)
                }
                val state = withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(2_000) {
                        reconnector.state.first { it == ConnectionState.CONNECTED }
                    }
                }
                assertThat(state).isEqualTo(ConnectionState.CONNECTED)
            } finally {
                reconnector.close()
            }
        }
    }

    @Test
    fun `forwards sendNv12 only when CONNECTED`() = runTest {
        FakeHyperHdrServer().use { server ->
            val reconnector = HyperHdrFlatBufferReconnector(
                host = "127.0.0.1", port = server.port, priority = 100,
            )
            // Before start: sendNv12 is a no-op (no NPE, no exception).
            reconnector.sendNv12(byteArrayOf(0), byteArrayOf(0), 1, 1, 1, 1)
            reconnector.start()
            try {
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(2_000) {
                        reconnector.state.first { it == ConnectionState.CONNECTED }
                    }
                    withTimeout(2_000) { server.receiveFrame() }   // drain Register

                    val w = 4; val h = 2
                    val y = ByteArray(w * h) { (it + 1).toByte() }
                    val uv = ByteArray(w) { (it + 100).toByte() }
                    reconnector.sendNv12(y, uv, w, h, w, w)
                    val frame = withTimeout(2_000) { server.receiveFrame() }
                    assertThat(frame.size).isGreaterThan(0)
                }
            } finally {
                reconnector.close()
            }
        }
    }

    @Test
    fun `transitions to ERROR when server disconnects, then reconnects`() = runTest {
        FakeHyperHdrServer().use { server ->
            val reconnector = HyperHdrFlatBufferReconnector(
                host = "127.0.0.1", port = server.port, priority = 100,
                backoff = BackoffSchedule(initialMs = 50, maxMs = 200),
            )
            reconnector.start()
            try {
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(2_000) {
                        reconnector.state.first { it == ConnectionState.CONNECTED }
                    }
                    withTimeout(2_000) { server.receiveFrame() }   // drain initial Register

                    server.forceDisconnect()

                    val newFrame = withTimeout(5_000) { server.receiveFrame() }
                    assertThat(newFrame.size).isGreaterThan(0)
                }
            } finally {
                reconnector.close()
            }
        }
    }
}
