package com.nexio.tv.data.repository.benchmark

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.ui.screens.player.ParallelRangeDataSource
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.SocketException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal interface BenchmarkReadableSource {
    fun open(position: Long, length: Long = C.LENGTH_UNSET.toLong()): Long
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun close()
}

internal fun interface BenchmarkReadableSourceFactory {
    fun createSource(): BenchmarkReadableSource
}

internal fun interface OptimizedBenchmarkDataSourceFactoryBuilder {
    fun create(
        candidate: DebridBenchmarkCandidate,
        configSnapshot: DebridBenchmarkTransportConfigSnapshot,
        allowStartupBootstrapReuse: Boolean,
        transportSampleTimeMs: () -> Long,
        onTransportBytesDownloaded: (Long, Long) -> Unit
    ): BenchmarkReadableSourceFactory
}

class OptimizedBenchmarkTransport internal constructor(
    private val factoryBuilder: OptimizedBenchmarkDataSourceFactoryBuilder,
    private val nanoTimeNs: () -> Long,
    private val sustainedThresholdBytes: Long,
    private val sustainedThresholdElapsedMs: Long,
    private val seekProbeBytes: Long,
    private val readBufferSize: Int,
    private val maxRecoverableFailures: Int,
    private val sleepMs: (Long) -> Unit
) {

    @Inject
    constructor(
        @Named("benchmark") okHttpClient: OkHttpClient
    ) : this(
        factoryBuilder = DefaultOptimizedBenchmarkDataSourceFactoryBuilder(okHttpClient),
        nanoTimeNs = System::nanoTime,
        sustainedThresholdBytes = 500L * 1024L * 1024L,
        sustainedThresholdElapsedMs = 120_000L,
        seekProbeBytes = 256L * 1024L,
        readBufferSize = 256 * 1024,
        maxRecoverableFailures = 8,
        sleepMs = { durationMs -> Thread.sleep(durationMs) }
    )

    suspend fun runProfile(
        candidate: DebridBenchmarkCandidate,
        configSnapshot: DebridBenchmarkTransportConfigSnapshot,
        observer: DebridBenchmarkObserver = DebridBenchmarkObserver {},
        seekTargets: List<Long> = defaultSeekTargets(candidate.sourceSizeBytes)
    ): DebridBenchmarkTransportProfileResult = withContext(Dispatchers.IO) {
        val collector = DebridBenchmarkMetricsCollector(
            requiredTransferredBytes = sustainedThresholdBytes,
            requiredElapsedMs = sustainedThresholdElapsedMs
        )
        val sustainedReadableSourceFactory = factoryBuilder.create(
            candidate = candidate,
            configSnapshot = configSnapshot,
            allowStartupBootstrapReuse = false,
            transportSampleTimeMs = { nanosToMillis(nanoTimeNs()) },
            onTransportBytesDownloaded = { bytesRead, sampleAtMs ->
                collector.recordTransportBytesRead(bytesRead = bytesRead, sampleAtMs = sampleAtMs)
            }
        )
        val seekReadableSourceFactory = factoryBuilder.create(
            candidate = candidate,
            configSnapshot = configSnapshot,
            allowStartupBootstrapReuse = false,
            transportSampleTimeMs = { nanosToMillis(nanoTimeNs()) },
            onTransportBytesDownloaded = { _, _ -> }
        )
        val sustainedPhase = runStartupAndSustainedPhase(
            readableSourceFactory = sustainedReadableSourceFactory,
            collector = collector,
            observer = observer
        )
        if (sustainedPhase.terminationReason == DebridBenchmarkTerminationReason.COMPLETED) {
            runSeekPhase(
                readableSourceFactory = seekReadableSourceFactory,
                collector = collector,
                seekTargets = seekTargets,
                sourceSizeBytes = candidate.sourceSizeBytes
            )
        }

        DebridBenchmarkTransportProfileResult(
            summary = collector.currentSummary(),
            profile = DebridBenchmarkTransportProfile(
                startup = collector.finishStartup(),
                sustained = collector.finishSustained().copy(
                    recoverableFailureCount = sustainedPhase.recoverableFailureCount,
                    recoverableTimeoutCount = sustainedPhase.recoverableTimeoutCount
                ),
                seek = collector.finishSeek(),
                configSnapshot = configSnapshot,
                rawSamples = collector.rawSamples()
            ),
            terminationReason = sustainedPhase.terminationReason,
            failure = sustainedPhase.failure
        )
    }

    private data class StartupAndSustainedPhaseResult(
        val terminationReason: DebridBenchmarkTerminationReason,
        val failure: DebridBenchmarkTransportFailure? = null,
        val recoverableFailureCount: Int = 0,
        val recoverableTimeoutCount: Int = 0
    )

    private fun runStartupAndSustainedPhase(
        readableSourceFactory: BenchmarkReadableSourceFactory,
        collector: DebridBenchmarkMetricsCollector,
        observer: DebridBenchmarkObserver
    ): StartupAndSustainedPhaseResult {
        var readableSource = readableSourceFactory.createSource()
        val buffer = ByteArray(readBufferSize)
        val requestStartedAtNs = nanoTimeNs()
        var previousReadAtNs: Long? = null
        var totalBytesRead = 0L
        var recoverableFailureCount = 0
        var recoverableTimeoutCount = 0
        var consecutiveRecoverableFailureCount = 0

        try {
            when (val openResult = reopenReadableSource(
                readableSourceFactory = readableSourceFactory,
                existingReadableSource = readableSource,
                position = 0L,
                recoverableFailureCount = recoverableFailureCount,
                recoverableTimeoutCount = recoverableTimeoutCount,
                consecutiveRecoverableFailureCount = consecutiveRecoverableFailureCount
            )) {
                is ReopenResult.Failed -> {
                    return StartupAndSustainedPhaseResult(
                        terminationReason = openResult.failure.toTerminationReason(),
                        failure = openResult.failure,
                        recoverableFailureCount = openResult.recoverableFailureCount,
                        recoverableTimeoutCount = openResult.recoverableTimeoutCount
                    )
                }
                is ReopenResult.Opened -> {
                    readableSource = openResult.readableSource
                    recoverableFailureCount = openResult.recoverableFailureCount
                    recoverableTimeoutCount = openResult.recoverableTimeoutCount
                    consecutiveRecoverableFailureCount = openResult.consecutiveRecoverableFailureCount
                }
            }
            while (true) {
                val read = try {
                    readableSource.read(buffer, 0, buffer.size)
                } catch (_: CancellationException) {
                    return StartupAndSustainedPhaseResult(
                        terminationReason = DebridBenchmarkTerminationReason.CANCELED,
                        recoverableFailureCount = recoverableFailureCount,
                        recoverableTimeoutCount = recoverableTimeoutCount
                    )
                } catch (error: Exception) {
                    val failure = error.toTransportFailure(
                        recoverableFailureCount = recoverableFailureCount,
                        recoverableTimeoutCount = recoverableTimeoutCount
                    )
                    if (failure.isRecoverable() && consecutiveRecoverableFailureCount < maxRecoverableFailures) {
                        recoverableFailureCount += 1
                        consecutiveRecoverableFailureCount += 1
                        if (failure.isTimeoutLike()) {
                            recoverableTimeoutCount += 1
                        }
                        runCatching { sleepMs(recoverableRetryBackoffMs(consecutiveRecoverableFailureCount)) }
                        when (val reopenResult = reopenReadableSource(
                            readableSourceFactory = readableSourceFactory,
                            existingReadableSource = readableSource,
                            position = totalBytesRead,
                            recoverableFailureCount = recoverableFailureCount,
                            recoverableTimeoutCount = recoverableTimeoutCount,
                            consecutiveRecoverableFailureCount = consecutiveRecoverableFailureCount
                        )) {
                            is ReopenResult.Failed -> {
                                return StartupAndSustainedPhaseResult(
                                    terminationReason = reopenResult.failure.toTerminationReason(),
                                    failure = reopenResult.failure,
                                    recoverableFailureCount = reopenResult.recoverableFailureCount,
                                    recoverableTimeoutCount = reopenResult.recoverableTimeoutCount
                                )
                            }
                            is ReopenResult.Opened -> {
                                readableSource = reopenResult.readableSource
                                recoverableFailureCount = reopenResult.recoverableFailureCount
                                recoverableTimeoutCount = reopenResult.recoverableTimeoutCount
                                consecutiveRecoverableFailureCount = reopenResult.consecutiveRecoverableFailureCount
                            }
                        }
                        continue
                    }
                    return StartupAndSustainedPhaseResult(
                        terminationReason = failure.toTerminationReason(),
                        failure = failure.copy(
                            recoverableFailureCount = recoverableFailureCount,
                            recoverableTimeoutCount = recoverableTimeoutCount
                        ),
                        recoverableFailureCount = recoverableFailureCount,
                        recoverableTimeoutCount = recoverableTimeoutCount
                    )
                }
                if (read == C.RESULT_END_OF_INPUT) {
                    return StartupAndSustainedPhaseResult(
                        terminationReason = DebridBenchmarkTerminationReason.FAILED,
                        failure = DebridBenchmarkTransportFailure(
                            exceptionClass = "UnexpectedEndOfInput",
                            message = "Optimized benchmark ended before sustained thresholds were met"
                        ),
                        recoverableFailureCount = recoverableFailureCount,
                        recoverableTimeoutCount = recoverableTimeoutCount
                    )
                }
                if (read <= 0) {
                    continue
                }
                consecutiveRecoverableFailureCount = 0

                val nowNs = nanoTimeNs()
                if (previousReadAtNs == null) {
                    collector.recordStartup(
                        requestStartedAtMs = nanosToMillis(requestStartedAtNs),
                        firstByteAtMs = nanosToMillis(nowNs)
                    )
                } else {
                    collector.recordReadGap(nanosToMillis(nowNs - previousReadAtNs))
                }
                previousReadAtNs = nowNs

                totalBytesRead += read
                collector.recordBytesRead(
                    totalBytesRead = totalBytesRead,
                    sampleAtMs = nanosToMillis(nowNs)
                )
                val summary = collector.currentSummary()
                observer.onSummaryUpdated(summary)

                if (collector.shouldComplete()) {
                    return StartupAndSustainedPhaseResult(
                        terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
                        recoverableFailureCount = recoverableFailureCount,
                        recoverableTimeoutCount = recoverableTimeoutCount
                    )
                }
            }
        } finally {
            runCatching { readableSource.close() }
        }
    }

    private sealed interface ReopenResult {
        data class Opened(
            val readableSource: BenchmarkReadableSource,
            val recoverableFailureCount: Int,
            val recoverableTimeoutCount: Int,
            val consecutiveRecoverableFailureCount: Int
        ) : ReopenResult

        data class Failed(
            val failure: DebridBenchmarkTransportFailure,
            val recoverableFailureCount: Int,
            val recoverableTimeoutCount: Int,
            val consecutiveRecoverableFailureCount: Int
        ) : ReopenResult
    }

    private fun reopenReadableSource(
        readableSourceFactory: BenchmarkReadableSourceFactory,
        existingReadableSource: BenchmarkReadableSource,
        position: Long,
        recoverableFailureCount: Int,
        recoverableTimeoutCount: Int,
        consecutiveRecoverableFailureCount: Int
    ): ReopenResult {
        var currentFailureCount = recoverableFailureCount
        var currentTimeoutCount = recoverableTimeoutCount
        var currentConsecutiveFailureCount = consecutiveRecoverableFailureCount
        var currentReadableSource = existingReadableSource

        while (true) {
            runCatching { currentReadableSource.close() }
            currentReadableSource = readableSourceFactory.createSource()
            try {
                currentReadableSource.open(position = position)
                return ReopenResult.Opened(
                    readableSource = currentReadableSource,
                    recoverableFailureCount = currentFailureCount,
                    recoverableTimeoutCount = currentTimeoutCount,
                    consecutiveRecoverableFailureCount = currentConsecutiveFailureCount
                )
            } catch (reopenError: Exception) {
                val reopenFailure = reopenError.toTransportFailure(
                    recoverableFailureCount = currentFailureCount,
                    recoverableTimeoutCount = currentTimeoutCount
                )
                if (reopenFailure.isRecoverable() && currentConsecutiveFailureCount < maxRecoverableFailures) {
                    currentFailureCount += 1
                    currentConsecutiveFailureCount += 1
                    if (reopenFailure.isTimeoutLike()) {
                        currentTimeoutCount += 1
                    }
                    runCatching { sleepMs(recoverableRetryBackoffMs(currentConsecutiveFailureCount)) }
                    continue
                }
                return ReopenResult.Failed(
                    failure = reopenFailure.copy(
                        recoverableFailureCount = currentFailureCount,
                        recoverableTimeoutCount = currentTimeoutCount
                    ),
                    recoverableFailureCount = currentFailureCount,
                    recoverableTimeoutCount = currentTimeoutCount,
                    consecutiveRecoverableFailureCount = currentConsecutiveFailureCount
                )
            }
        }
    }

    private fun runSeekPhase(
        readableSourceFactory: BenchmarkReadableSourceFactory,
        collector: DebridBenchmarkMetricsCollector,
        seekTargets: List<Long>,
        sourceSizeBytes: Long?
    ) {
        val buffer = ByteArray(minOf(readBufferSize, seekProbeBytes.toInt()))
        seekTargets.forEach { target ->
            val readableSource = readableSourceFactory.createSource()
            val seekStartedAtNs = nanoTimeNs()
            try {
                val probeLength = resolveSeekProbeLength(
                    sourceSizeBytes = sourceSizeBytes,
                    offsetBytes = target
                )
                if (probeLength <= 0L) {
                    collector.recordSeekSample(
                        DebridBenchmarkSeekSample(
                            targetOffsetBytes = target,
                            ttfbMs = null,
                            succeeded = false
                        )
                    )
                    return@forEach
                }
                readableSource.open(position = target, length = probeLength)
                val read = readableSource.read(buffer, 0, buffer.size)
                val ttfbMs = nanosToMillis(nanoTimeNs() - seekStartedAtNs)
                collector.recordSeekSample(
                    DebridBenchmarkSeekSample(
                        targetOffsetBytes = target,
                        ttfbMs = ttfbMs.takeIf { read > 0 },
                        succeeded = read > 0
                    )
                )
            } catch (_: Exception) {
                collector.recordSeekSample(
                    DebridBenchmarkSeekSample(
                        targetOffsetBytes = target,
                        ttfbMs = null,
                        succeeded = false
                    )
                )
            } finally {
                runCatching { readableSource.close() }
            }
        }
    }

    private fun resolveSeekProbeLength(
        sourceSizeBytes: Long?,
        offsetBytes: Long
    ): Long {
        val remainingBytes = sourceSizeBytes?.minus(offsetBytes)
        return when {
            remainingBytes == null -> seekProbeBytes
            remainingBytes <= 0L -> 0L
            else -> minOf(seekProbeBytes, remainingBytes)
        }
    }

    private fun defaultSeekTargets(sourceSizeBytes: Long?): List<Long> {
        val sizeBytes = sourceSizeBytes ?: return emptyList()
        if (sizeBytes <= 0L) return emptyList()
        val lastByte = (sizeBytes - 1L).coerceAtLeast(0L)
        return listOf(sizeBytes / 4L, sizeBytes / 2L, (sizeBytes * 3L) / 4L)
            .map { it.coerceAtMost(lastByte) }
            .distinct()
    }

    private fun recoverableRetryBackoffMs(attemptNumber: Int): Long {
        return when (attemptNumber) {
            1 -> 50L
            2 -> 100L
            3 -> 200L
            4 -> 400L
            else -> 500L
        }
    }

    private fun nanosToMillis(valueNs: Long): Long = valueNs / 1_000_000L

    private fun Throwable.toTransportFailure(
        recoverableFailureCount: Int = 0,
        recoverableTimeoutCount: Int = 0
    ): DebridBenchmarkTransportFailure {
        val wrapper = unwrapTransportCause()
        val root = deepestCause(wrapper)
        val chunkIndex = causesIncludingSelf(wrapper)
            .filterIsInstance<ParallelRangeDataSource.ChunkTransferException>()
            .firstOrNull()
            ?.chunkIndex
        return DebridBenchmarkTransportFailure(
            exceptionClass = wrapper::class.java.simpleName,
            message = wrapper.message ?: root.message,
            chunkIndex = chunkIndex,
            rootCauseClass = root::class.java.simpleName,
            rootCauseMessage = root.message,
            recoverableFailureCount = recoverableFailureCount,
            recoverableTimeoutCount = recoverableTimeoutCount
        )
    }

    private fun Throwable.unwrapTransportCause(): Throwable {
        var current: Throwable = this
        while (true) {
            val next = when {
                current is ExecutionException && current.cause != null -> current.cause
                current.cause != null &&
                    current !is ParallelRangeDataSource.ChunkTransferException &&
                    current !is InterruptedIOException -> current.cause
                else -> null
            } ?: return current
            current = next
        }
    }

    private fun DebridBenchmarkTransportFailure.isRecoverable(): Boolean {
        return chunkIndex != null || isTimeoutLike() || isConnectionResetLike()
    }

    private fun DebridBenchmarkTransportFailure.isTimeoutLike(): Boolean {
        return exceptionClass == "ChunkWaitTimeoutException" ||
            exceptionClass == "InterruptedIOException" ||
            rootCauseClass == "TimeoutException" ||
            rootCauseClass == "SocketTimeoutException" ||
            rootCauseClass == "InterruptedIOException" ||
            rootCauseMessage?.contains("timeout", ignoreCase = true) == true
    }

    private fun DebridBenchmarkTransportFailure.isConnectionResetLike(): Boolean {
        return exceptionClass == SocketException::class.java.simpleName ||
            exceptionClass == EOFException::class.java.simpleName ||
            rootCauseClass == SocketException::class.java.simpleName ||
            rootCauseClass == EOFException::class.java.simpleName ||
            message?.contains("connection reset", ignoreCase = true) == true ||
            rootCauseMessage?.contains("connection reset", ignoreCase = true) == true ||
            message?.contains("connection closed", ignoreCase = true) == true ||
            rootCauseMessage?.contains("connection closed", ignoreCase = true) == true ||
            message?.contains("broken pipe", ignoreCase = true) == true ||
            rootCauseMessage?.contains("broken pipe", ignoreCase = true) == true
    }

    private fun DebridBenchmarkTransportFailure.toTerminationReason(): DebridBenchmarkTerminationReason {
        return if (isTimeoutLike()) {
            DebridBenchmarkTerminationReason.TIMEOUT
        } else {
            DebridBenchmarkTerminationReason.FAILED
        }
    }

    private fun deepestCause(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun causesIncludingSelf(error: Throwable): Sequence<Throwable> = sequence {
        var current: Throwable? = error
        while (current != null) {
            yield(current)
            current = current.cause?.takeUnless { it === current }
        }
    }
}

private class DefaultOptimizedBenchmarkDataSourceFactoryBuilder(
    private val okHttpClient: OkHttpClient
) : OptimizedBenchmarkDataSourceFactoryBuilder {

    override fun create(
        candidate: DebridBenchmarkCandidate,
        configSnapshot: DebridBenchmarkTransportConfigSnapshot,
        allowStartupBootstrapReuse: Boolean,
        transportSampleTimeMs: () -> Long,
        onTransportBytesDownloaded: (Long, Long) -> Unit
    ): BenchmarkReadableSourceFactory {
        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient).apply {
            setDefaultRequestProperties(candidate.headers)
        }
        val dataSourceFactory: DataSource.Factory = if (configSnapshot.useParallelConnections == false) {
            upstreamFactory
        } else {
            ParallelRangeDataSource.Factory(
                upstreamFactory = upstreamFactory,
                parallelConnections = (configSnapshot.parallelConnectionCount
                    ?: PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT).coerceAtLeast(2),
                chunkSize = (configSnapshot.parallelChunkSizeMb
                    ?: PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB).toLong() * 1024L * 1024L,
                transportSampleTimeMs = transportSampleTimeMs,
                onTransportBytesDownloaded = onTransportBytesDownloaded,
                allowStartupBootstrapReuse = allowStartupBootstrapReuse
            )
        }
        return BenchmarkReadableSourceFactory {
            Media3BenchmarkReadableSource(
                dataSource = dataSourceFactory.createDataSource(),
                candidate = candidate
            )
        }
    }
}

internal class Media3BenchmarkReadableSource(
    private val dataSource: DataSource,
    private val candidate: DebridBenchmarkCandidate
) : BenchmarkReadableSource {

    override fun open(position: Long, length: Long): Long {
        val dataSpec = DataSpec.Builder()
            .setUri(candidate.directUrl)
            .setPosition(position)
            .setLength(length)
            .setHttpRequestHeaders(candidate.headers)
            .build()
        return dataSource.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return dataSource.read(buffer, offset, length)
    }

    override fun close() {
        dataSource.close()
    }
}
