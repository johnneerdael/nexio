package com.nexio.tv.data.repository.benchmark

import androidx.media3.common.C
import com.nexio.tv.ui.screens.player.ParallelRangeDataSource
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizedBenchmarkTransportTest {

    @Test
    fun `optimized transport freezes the player parallel config at benchmark start`() = runTest {
        val clock = FakeBenchmarkClock()
        val builder = RecordingFactoryBuilder(clock)
        val transport = buildTransport(builder, clock)
        val configSnapshot = DebridBenchmarkTransportConfigSnapshot(
            useParallelConnections = true,
            parallelConnectionCount = 4,
            parallelChunkSizeMb = 8
        )

        val result = transport.runProfile(
            candidate = candidate(),
            configSnapshot = configSnapshot
        )

        assertEquals(DebridBenchmarkTerminationReason.COMPLETED, result.terminationReason)
        assertEquals(configSnapshot, builder.recordedConfigSnapshot)
        assertEquals(configSnapshot, result.profile.configSnapshot)
        assertTrue((result.profile.seek.seekTtfbP95Ms ?: 0L) > 0L)
    }

    @Test
    fun `optimized transport disables bootstrap reuse during benchmark seeks`() = runTest {
        val clock = FakeBenchmarkClock()
        val builder = RecordingFactoryBuilder(clock)
        val transport = buildTransport(builder, clock)

        val result = transport.runProfile(
            candidate = candidate(),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 3,
                parallelChunkSizeMb = 16
            ),
            seekTargets = listOf(10L, 20L, 30L)
        )

        assertEquals(DebridBenchmarkTerminationReason.COMPLETED, result.terminationReason)
        assertFalse(builder.allowStartupBootstrapReuse)
        assertEquals(listOf(10L, 20L, 30L), result.profile.rawSamples.seekSamples.map { it.targetOffsetBytes })
    }

    @Test
    fun `optimized transport reports chunk timeout details`() = runTest {
        val clock = FakeBenchmarkClock()
        val builder = object : OptimizedBenchmarkDataSourceFactoryBuilder {
            override fun create(
                candidate: DebridBenchmarkCandidate,
                configSnapshot: DebridBenchmarkTransportConfigSnapshot,
                allowStartupBootstrapReuse: Boolean
            ): BenchmarkReadableSourceFactory {
                return BenchmarkReadableSourceFactory {
                    object : BenchmarkReadableSource {
                        override fun open(position: Long, length: Long): Long {
                            clock.advanceMs(50L)
                            return 1024L * 1024L
                        }

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            throw ParallelRangeDataSource.ChunkWaitTimeoutException(
                                chunkIndex = 8L,
                                timeoutMs = 60_000L,
                                cause = TimeoutException()
                            )
                        }

                        override fun close() = Unit
                    }
                }
            }
        }
        val transport = buildTransport(builder, clock)

        val result = transport.runProfile(
            candidate = candidate(),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 4,
                parallelChunkSizeMb = 8
            )
        )

        assertEquals(DebridBenchmarkTerminationReason.TIMEOUT, result.terminationReason)
        assertNotNull(result.failure)
        assertEquals("ChunkWaitTimeoutException", result.failure?.exceptionClass)
        assertEquals(8L, result.failure?.chunkIndex)
    }

    private fun buildTransport(
        builder: OptimizedBenchmarkDataSourceFactoryBuilder,
        clock: FakeBenchmarkClock
    ): OptimizedBenchmarkTransport {
        return OptimizedBenchmarkTransport(
            factoryBuilder = builder,
            nanoTimeNs = clock::nowNs,
            sustainedThresholdBytes = 64L * 1024L,
            sustainedThresholdElapsedMs = 2_000L,
            seekProbeBytes = 4L * 1024L,
            readBufferSize = 32 * 1024
        )
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

    private class FakeBenchmarkClock {
        private var nowNs = 0L

        fun nowNs(): Long = nowNs

        fun advanceMs(durationMs: Long) {
            nowNs += durationMs * 1_000_000L
        }
    }

    private class RecordingFactoryBuilder(
        private val clock: FakeBenchmarkClock
    ) : OptimizedBenchmarkDataSourceFactoryBuilder {
        var recordedConfigSnapshot: DebridBenchmarkTransportConfigSnapshot? = null
        var allowStartupBootstrapReuse: Boolean = true

        override fun create(
            candidate: DebridBenchmarkCandidate,
            configSnapshot: DebridBenchmarkTransportConfigSnapshot,
            allowStartupBootstrapReuse: Boolean
        ): BenchmarkReadableSourceFactory {
            recordedConfigSnapshot = configSnapshot
            this.allowStartupBootstrapReuse = allowStartupBootstrapReuse
            return BenchmarkReadableSourceFactory {
                FakeBenchmarkDataSource(clock = clock)
            }
        }
    }

    private class FakeBenchmarkDataSource(
        private val clock: FakeBenchmarkClock
    ) : BenchmarkReadableSource {
        private var position = 0
        private var limit = 0

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
            private val CONTENT_BYTES = ByteArray(1024 * 1024) { (it % 251).toByte() }
        }
    }
}
