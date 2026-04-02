package com.nexio.tv.data.repository.benchmark

import androidx.media3.common.C
import com.nexio.tv.ui.screens.player.ParallelRangeDataSource
import java.io.EOFException
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
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
                allowStartupBootstrapReuse: Boolean,
                transportSampleTimeMs: () -> Long,
                onTransportBytesDownloaded: (Long, Long) -> Unit
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

    @Test
    fun `optimized transport recovers from a single chunk failure and completes`() = runTest {
        val clock = FakeBenchmarkClock()
        val failedOnce = AtomicBoolean(false)
        val transport = buildTransport(
            builder = object : OptimizedBenchmarkDataSourceFactoryBuilder {
                override fun create(
                    candidate: DebridBenchmarkCandidate,
                    configSnapshot: DebridBenchmarkTransportConfigSnapshot,
                    allowStartupBootstrapReuse: Boolean,
                    transportSampleTimeMs: () -> Long,
                    onTransportBytesDownloaded: (Long, Long) -> Unit
                ): BenchmarkReadableSourceFactory {
                    return BenchmarkReadableSourceFactory {
                        RecoveringBenchmarkDataSource(clock, failedOnce)
                    }
                }
            },
            clock = clock
        )

        val result = transport.runProfile(
            candidate = candidate(),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 4,
                parallelChunkSizeMb = 8
            )
        )

        assertEquals(DebridBenchmarkTerminationReason.COMPLETED, result.terminationReason)
        assertEquals(1, result.profile.sustained.recoverableFailureCount)
        assertEquals(0, result.profile.sustained.recoverableTimeoutCount)
    }

    @Test
    fun `optimized transport recovers from a single connection reset and completes`() = runTest {
        val clock = FakeBenchmarkClock()
        val failedOnce = AtomicBoolean(false)
        val contentBytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val failureOffsetBytes = 32 * 1024
        val builder = object : OptimizedBenchmarkDataSourceFactoryBuilder {
            override fun create(
                candidate: DebridBenchmarkCandidate,
                configSnapshot: DebridBenchmarkTransportConfigSnapshot,
                allowStartupBootstrapReuse: Boolean,
                transportSampleTimeMs: () -> Long,
                onTransportBytesDownloaded: (Long, Long) -> Unit
            ): BenchmarkReadableSourceFactory {
                return BenchmarkReadableSourceFactory {
                    object : BenchmarkReadableSource {
                        private var position = 0
                        private var limit = 0

                        override fun open(position: Long, length: Long): Long {
                            val contentLength = contentBytes.size
                            this.position = position.toInt().coerceAtMost(contentLength)
                            limit = when {
                                length == C.LENGTH_UNSET.toLong() -> contentLength
                                else -> (this.position + length.toInt()).coerceAtMost(contentLength)
                            }
                            clock.advanceMs(50L)
                            return (limit - this.position).coerceAtLeast(0).toLong()
                        }

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (!failedOnce.get() && position >= failureOffsetBytes) {
                                failedOnce.set(true)
                                throw SocketException("Connection reset")
                            }
                            if (position >= limit) {
                                return C.RESULT_END_OF_INPUT
                            }
                            val bytesToRead = minOf(length, limit - position, 32 * 1024)
                            System.arraycopy(contentBytes, position, buffer, offset, bytesToRead)
                            position += bytesToRead
                            clock.advanceMs(1_000L)
                            return bytesToRead
                        }

                        override fun close() = Unit
                    }
                }
            }
        }
        val transport = buildTransport(
            builder = builder,
            clock = clock,
            sustainedThresholdBytes = 320L * 1024L,
            sustainedThresholdElapsedMs = 10_000L
        )

        val result = transport.runProfile(
            candidate = candidate(),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 4,
                parallelChunkSizeMb = 8
            )
        )

        assertEquals(DebridBenchmarkTerminationReason.COMPLETED, result.terminationReason)
        assertEquals(1, result.profile.sustained.recoverableFailureCount)
        assertEquals(0, result.profile.sustained.recoverableTimeoutCount)
    }

    @Test
    fun `optimized transport recovers from a single connection closed eof and completes`() = runTest {
        val clock = FakeBenchmarkClock()
        val failedOnce = AtomicBoolean(false)
        val contentBytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val failureOffsetBytes = 32 * 1024
        val transport = buildTransport(
            builder = object : OptimizedBenchmarkDataSourceFactoryBuilder {
                override fun create(
                    candidate: DebridBenchmarkCandidate,
                    configSnapshot: DebridBenchmarkTransportConfigSnapshot,
                    allowStartupBootstrapReuse: Boolean,
                    transportSampleTimeMs: () -> Long,
                    onTransportBytesDownloaded: (Long, Long) -> Unit
                ): BenchmarkReadableSourceFactory {
                    return BenchmarkReadableSourceFactory {
                        object : BenchmarkReadableSource {
                            private var position = 0
                            private var limit = 0

                            override fun open(position: Long, length: Long): Long {
                                val contentLength = contentBytes.size
                                this.position = position.toInt().coerceAtMost(contentLength)
                                limit = when {
                                    length == C.LENGTH_UNSET.toLong() -> contentLength
                                    else -> (this.position + length.toInt()).coerceAtMost(contentLength)
                                }
                                clock.advanceMs(50L)
                                return (limit - this.position).coerceAtLeast(0).toLong()
                            }

                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                if (!failedOnce.get() && position >= failureOffsetBytes) {
                                    failedOnce.set(true)
                                    throw EOFException("connection closed")
                                }
                                if (position >= limit) {
                                    return C.RESULT_END_OF_INPUT
                                }
                                val bytesToRead = minOf(length, limit - position, 32 * 1024)
                                System.arraycopy(contentBytes, position, buffer, offset, bytesToRead)
                                position += bytesToRead
                                clock.advanceMs(1_000L)
                                return bytesToRead
                            }

                            override fun close() = Unit
                        }
                    }
                }
            },
            clock = clock
        )

        val result = transport.runProfile(
            candidate = candidate(),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 4,
                parallelChunkSizeMb = 8
            )
        )

        assertEquals(DebridBenchmarkTerminationReason.COMPLETED, result.terminationReason)
        assertEquals(1, result.profile.sustained.recoverableFailureCount)
        assertEquals(0, result.profile.sustained.recoverableTimeoutCount)
    }

    @Test
    fun `optimized transport retries recoverable reopen failures and completes`() = runTest {
        val clock = FakeBenchmarkClock()
        val readFailedOnce = AtomicBoolean(false)
        val reopenFailedOnce = AtomicBoolean(false)
        val contentBytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val failureOffsetBytes = 32 * 1024
        val transport = buildTransport(
            builder = object : OptimizedBenchmarkDataSourceFactoryBuilder {
                override fun create(
                    candidate: DebridBenchmarkCandidate,
                    configSnapshot: DebridBenchmarkTransportConfigSnapshot,
                    allowStartupBootstrapReuse: Boolean,
                    transportSampleTimeMs: () -> Long,
                    onTransportBytesDownloaded: (Long, Long) -> Unit
                ): BenchmarkReadableSourceFactory {
                    return BenchmarkReadableSourceFactory {
                        object : BenchmarkReadableSource {
                            private var position = 0
                            private var limit = 0

                            override fun open(position: Long, length: Long): Long {
                                if (position > 0L && !reopenFailedOnce.get()) {
                                    reopenFailedOnce.set(true)
                                    throw SocketException("Connection reset")
                                }
                                val contentLength = contentBytes.size
                                this.position = position.toInt().coerceAtMost(contentLength)
                                limit = when {
                                    length == C.LENGTH_UNSET.toLong() -> contentLength
                                    else -> (this.position + length.toInt()).coerceAtMost(contentLength)
                                }
                                clock.advanceMs(50L)
                                return (limit - this.position).coerceAtLeast(0).toLong()
                            }

                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                if (!readFailedOnce.get() && position >= failureOffsetBytes) {
                                    readFailedOnce.set(true)
                                    throw SocketException("Connection reset")
                                }
                                if (position >= limit) {
                                    return C.RESULT_END_OF_INPUT
                                }
                                val bytesToRead = minOf(length, limit - position, 32 * 1024)
                                System.arraycopy(contentBytes, position, buffer, offset, bytesToRead)
                                position += bytesToRead
                                clock.advanceMs(1_000L)
                                return bytesToRead
                            }

                            override fun close() = Unit
                        }
                    }
                }
            },
            clock = clock
        )

        val result = transport.runProfile(
            candidate = candidate(),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 4,
                parallelChunkSizeMb = 8
            )
        )

        assertEquals(DebridBenchmarkTerminationReason.COMPLETED, result.terminationReason)
        assertEquals(2, result.profile.sustained.recoverableFailureCount)
        assertEquals(0, result.profile.sustained.recoverableTimeoutCount)
    }

    @Test
    fun `optimized transport tolerates more than three recoverable connection resets during sustained run`() = runTest {
        val clock = FakeBenchmarkClock()
        val contentBytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val failureOffsetsBytes = ArrayDeque(listOf(32 * 1024, 64 * 1024, 96 * 1024, 128 * 1024))
        val builder = object : OptimizedBenchmarkDataSourceFactoryBuilder {
            override fun create(
                candidate: DebridBenchmarkCandidate,
                configSnapshot: DebridBenchmarkTransportConfigSnapshot,
                allowStartupBootstrapReuse: Boolean,
                transportSampleTimeMs: () -> Long,
                onTransportBytesDownloaded: (Long, Long) -> Unit
            ): BenchmarkReadableSourceFactory {
                return BenchmarkReadableSourceFactory {
                    object : BenchmarkReadableSource {
                        private var position = 0
                        private var limit = 0

                        override fun open(position: Long, length: Long): Long {
                            val contentLength = contentBytes.size
                            this.position = position.toInt().coerceAtMost(contentLength)
                            limit = when {
                                length == C.LENGTH_UNSET.toLong() -> contentLength
                                else -> (this.position + length.toInt()).coerceAtMost(contentLength)
                            }
                            clock.advanceMs(50L)
                            return (limit - this.position).coerceAtLeast(0).toLong()
                        }

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (failureOffsetsBytes.isNotEmpty() && position >= failureOffsetsBytes.first()) {
                                failureOffsetsBytes.removeFirst()
                                throw SocketException("Connection reset")
                            }
                            if (position >= limit) {
                                return C.RESULT_END_OF_INPUT
                            }
                            val bytesToRead = minOf(length, limit - position, 32 * 1024)
                            System.arraycopy(contentBytes, position, buffer, offset, bytesToRead)
                            position += bytesToRead
                            clock.advanceMs(1_000L)
                            return bytesToRead
                        }

                        override fun close() = Unit
                    }
                }
            }
        }
        val transport = buildTransport(
            builder = builder,
            clock = clock,
            sustainedThresholdBytes = 160L * 1024L,
            sustainedThresholdElapsedMs = 5_000L
        )

        val result = transport.runProfile(
            candidate = candidate(),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 4,
                parallelChunkSizeMb = 8
            )
        )

        assertEquals(DebridBenchmarkTerminationReason.COMPLETED, result.terminationReason)
        assertEquals(4, result.profile.sustained.recoverableFailureCount)
    }

    private fun buildTransport(
        builder: OptimizedBenchmarkDataSourceFactoryBuilder,
        clock: FakeBenchmarkClock,
        sustainedThresholdBytes: Long = 64L * 1024L,
        sustainedThresholdElapsedMs: Long = 2_000L
    ): OptimizedBenchmarkTransport {
        return OptimizedBenchmarkTransport(
            factoryBuilder = builder,
            nanoTimeNs = clock::nowNs,
            sustainedThresholdBytes = sustainedThresholdBytes,
            sustainedThresholdElapsedMs = sustainedThresholdElapsedMs,
            seekProbeBytes = 4L * 1024L,
            readBufferSize = 32 * 1024,
            maxRecoverableFailures = 3
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
            allowStartupBootstrapReuse: Boolean,
            transportSampleTimeMs: () -> Long,
            onTransportBytesDownloaded: (Long, Long) -> Unit
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

    private class RecoveringBenchmarkDataSource(
        private val clock: FakeBenchmarkClock,
        private val failedOnce: AtomicBoolean
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
            if (!failedOnce.get() && position >= FAILURE_OFFSET_BYTES) {
                failedOnce.set(true)
                throw ParallelRangeDataSource.ChunkDownloadException(
                    chunkIndex = 2L,
                    message = "Failed to download chunk 2",
                    cause = IllegalStateException("connection reset")
                )
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
            private const val FAILURE_OFFSET_BYTES = 32 * 1024
            private val CONTENT_BYTES = ByteArray(1024 * 1024) { (it % 251).toByte() }
        }
    }
}
