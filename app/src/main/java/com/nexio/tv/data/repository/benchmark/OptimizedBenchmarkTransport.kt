package com.nexio.tv.data.repository.benchmark

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.ui.screens.player.ParallelRangeDataSource
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
    private val maxRecoverableFailures: Int
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
        maxRecoverableFailures = 3
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

        try {
            readableSource.open(position = 0L)
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
                    if (failure.isRecoverable() && recoverableFailureCount < maxRecoverableFailures) {
                        recoverableFailureCount += 1
                        if (failure.isTimeoutLike()) {
                            recoverableTimeoutCount += 1
                        }
                        runCatching { readableSource.close() }
                        readableSource = readableSourceFactory.createSource()
                        try {
                            readableSource.open(position = totalBytesRead.toLong())
                        } catch (reopenError: Exception) {
                            val reopenFailure = reopenError.toTransportFailure(
                                recoverableFailureCount = recoverableFailureCount,
                                recoverableTimeoutCount = recoverableTimeoutCount
                            )
                            return StartupAndSustainedPhaseResult(
                                terminationReason = reopenFailure.toTerminationReason(),
                                failure = reopenFailure,
                                recoverableFailureCount = recoverableFailureCount,
                                recoverableTimeoutCount = recoverableTimeoutCount
                            )
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
            rootCauseClass == SocketException::class.java.simpleName ||
            message?.contains("connection reset", ignoreCase = true) == true ||
            rootCauseMessage?.contains("connection reset", ignoreCase = true) == true ||
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
