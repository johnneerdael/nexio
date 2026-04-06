package com.nexio.tv.data.repository.benchmark

import androidx.media3.common.C
import com.nexio.tv.ui.screens.player.RuntimeTransportObservation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class NegotiatedProtocolCaptureTest {

    @Test
    fun `transport observation carries negotiated protocol from response headers`() {
        val observation = RuntimeTransportObservation(
            hostScope = "host:cdn.example.com",
            transportClass = "keep_alive",
            negotiatedProtocol = "h2",
            connectionHeader = "keep-alive"
        )
        assertEquals("h2", observation.negotiatedProtocol)
        assertEquals("keep-alive", observation.connectionHeader)
    }

    @Test
    fun `transport observation defaults to null when protocol header absent`() {
        val observation = RuntimeTransportObservation(
            hostScope = "host:cdn.example.com",
            transportClass = "connection_close"
        )
        assertNull(observation.negotiatedProtocol)
        assertNull(observation.connectionHeader)
    }

    @Test
    fun `runConfigProfile propagates protocol and connection header from observation`() = runTest {
        val clock = FakeClock()
        val builder = object : OptimizedBenchmarkDataSourceFactoryBuilder {
            override fun create(
                candidate: DebridBenchmarkCandidate,
                configSnapshot: DebridBenchmarkTransportConfigSnapshot,
                chunkWaitTimeoutMs: Long,
                allowStartupBootstrapReuse: Boolean,
                transportSampleTimeMs: () -> Long,
                onTransportBytesDownloaded: (Long, Long) -> Unit,
                onChunkBytesDownloaded: (Long, Long, Long, Int, Long) -> Unit,
                onTransportObservation: (RuntimeTransportObservation) -> Unit
            ): BenchmarkReadableSourceFactory {
                return BenchmarkReadableSourceFactory {
                    ObservationEmittingDataSource(
                        clock = clock,
                        onFirstRead = {
                            onTransportObservation(
                                RuntimeTransportObservation(
                                    hostScope = "host:real-debrid.com",
                                    transportClass = "connection_close",
                                    negotiatedProtocol = "http/1.1",
                                    connectionHeader = "close"
                                )
                            )
                        }
                    )
                }
            }
        }
        val transport = OptimizedBenchmarkTransport(
            factoryBuilder = builder,
            nanoTimeNs = clock::nowNs,
            sustainedThresholdBytes = 64L * 1024L,
            sustainedThresholdElapsedMs = 2_000L,
            seekProbeBytes = 4L * 1024L,
            readBufferSize = 32 * 1024,
            maxRecoverableFailures = 8,
            sleepMs = clock::advanceMs,
            noProgressFailureTimeoutMs = 20_000L,
            completionGuardBandMs = 0L
        )

        val result = transport.runConfigProfile(
            candidate = candidate(),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 3,
                parallelChunkSizeMb = 8
            ),
            measurementDurationMs = 2_000L
        )

        assertEquals(DebridBenchmarkTerminationReason.COMPLETED, result.terminationReason)
        assertEquals("http/1.1", result.negotiatedProtocol)
        assertEquals("close", result.connectionHeader)
        assertEquals("connection_close", result.observedTransportClass)
        assertEquals("host:real-debrid.com", result.observedHostScope)
    }

    @Test
    fun `runConfigProfile reports null protocol when observation has no protocol`() = runTest {
        val clock = FakeClock()
        val builder = object : OptimizedBenchmarkDataSourceFactoryBuilder {
            override fun create(
                candidate: DebridBenchmarkCandidate,
                configSnapshot: DebridBenchmarkTransportConfigSnapshot,
                chunkWaitTimeoutMs: Long,
                allowStartupBootstrapReuse: Boolean,
                transportSampleTimeMs: () -> Long,
                onTransportBytesDownloaded: (Long, Long) -> Unit,
                onChunkBytesDownloaded: (Long, Long, Long, Int, Long) -> Unit,
                onTransportObservation: (RuntimeTransportObservation) -> Unit
            ): BenchmarkReadableSourceFactory {
                return BenchmarkReadableSourceFactory {
                    ObservationEmittingDataSource(
                        clock = clock,
                        onFirstRead = {
                            onTransportObservation(
                                RuntimeTransportObservation(
                                    hostScope = "host:pm.example.com",
                                    transportClass = "keep_alive"
                                )
                            )
                        }
                    )
                }
            }
        }
        val transport = OptimizedBenchmarkTransport(
            factoryBuilder = builder,
            nanoTimeNs = clock::nowNs,
            sustainedThresholdBytes = 64L * 1024L,
            sustainedThresholdElapsedMs = 2_000L,
            seekProbeBytes = 4L * 1024L,
            readBufferSize = 32 * 1024,
            maxRecoverableFailures = 8,
            sleepMs = clock::advanceMs,
            noProgressFailureTimeoutMs = 20_000L,
            completionGuardBandMs = 0L
        )

        val result = transport.runConfigProfile(
            candidate = candidate(),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 2,
                parallelChunkSizeMb = 8
            ),
            measurementDurationMs = 2_000L
        )

        assertEquals(DebridBenchmarkTerminationReason.COMPLETED, result.terminationReason)
        assertNull(result.negotiatedProtocol)
        assertNull(result.connectionHeader)
        assertEquals("keep_alive", result.observedTransportClass)
    }

    @Test
    fun `BenchmarkTransportCharacteristics detects connection-close behavior`() {
        val chars = BenchmarkTransportCharacteristics(
            negotiatedProtocol = "http/1.1",
            connectionBehavior = "close",
            connectionReuseDetected = false,
            requestsPerConnection = 1.0,
            maxConnectionsPerSecond = 2.5
        )
        assertEquals("http/1.1", chars.negotiatedProtocol)
        assertEquals("close", chars.connectionBehavior)
        assertFalse(chars.connectionReuseDetected!!)
        assertEquals(1.0, chars.requestsPerConnection!!, 0.001)
        assertNotNull(chars.maxConnectionsPerSecond)
    }

    @Test
    fun `BenchmarkTransportCharacteristics detects keep-alive behavior`() {
        val chars = BenchmarkTransportCharacteristics(
            negotiatedProtocol = "h2",
            connectionBehavior = "keep-alive",
            connectionReuseDetected = true,
            requestsPerConnection = 12.0,
            maxConnectionsPerSecond = null
        )
        assertEquals("h2", chars.negotiatedProtocol)
        assertTrue(chars.connectionReuseDetected!!)
        assertEquals(12.0, chars.requestsPerConnection!!, 0.001)
        assertNull(chars.maxConnectionsPerSecond)
    }

    private fun candidate(): DebridBenchmarkCandidate {
        return DebridBenchmarkCandidate(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            directUrl = "https://example.com/file.mkv",
            headers = mapOf("Authorization" to "Bearer token"),
            filename = "Example.mkv",
            sourceSizeBytes = 1024L * 1024L
        )
    }

    private class FakeClock {
        private var nowNs = 0L

        fun nowNs(): Long = nowNs

        fun advanceMs(durationMs: Long) {
            nowNs += durationMs * 1_000_000L
        }
    }

    private class ObservationEmittingDataSource(
        private val clock: FakeClock,
        private val onFirstRead: () -> Unit
    ) : BenchmarkReadableSource {
        private var position = 0
        private var limit = 0
        private var emitted = false

        override fun open(position: Long, length: Long): Long {
            val contentLength = CONTENT_BYTES.size
            this.position = position.toInt().coerceAtMost(contentLength)
            limit = when {
                length == C.LENGTH_UNSET.toLong() -> contentLength
                else -> (this.position + length.toInt()).coerceAtMost(contentLength)
            }
            clock.advanceMs(50L)
            return (limit - this.position).coerceAtLeast(0).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!emitted) {
                emitted = true
                onFirstRead()
            }
            if (position >= limit) {
                return C.RESULT_END_OF_INPUT
            }
            val bytesToRead = minOf(length, limit - position, 32 * 1024)
            System.arraycopy(CONTENT_BYTES, position, buffer, offset, bytesToRead)
            position += bytesToRead
            clock.advanceMs(1_000L)
            return bytesToRead
        }

        override fun close() = Unit

        companion object {
            private val CONTENT_BYTES = ByteArray(512 * 1024)
        }
    }
}
