package com.nexio.tv.data.repository.benchmark

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.ui.screens.player.ParallelRangeDataSource
import java.io.InterruptedIOException
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
        allowStartupBootstrapReuse: Boolean
    ): BenchmarkReadableSourceFactory
}

class OptimizedBenchmarkTransport internal constructor(
    private val factoryBuilder: OptimizedBenchmarkDataSourceFactoryBuilder,
    private val nanoTimeNs: () -> Long,
    private val sustainedThresholdBytes: Long,
    private val sustainedThresholdElapsedMs: Long,
    private val seekProbeBytes: Long,
    private val readBufferSize: Int
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
        readBufferSize = 256 * 1024
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
        val readableSourceFactory = factoryBuilder.create(
            candidate = candidate,
            configSnapshot = configSnapshot,
            allowStartupBootstrapReuse = false
        )
        val terminationReason = runStartupAndSustainedPhase(
            readableSourceFactory = readableSourceFactory,
            collector = collector,
            observer = observer
        )
        if (terminationReason == DebridBenchmarkTerminationReason.COMPLETED) {
            runSeekPhase(
                readableSourceFactory = readableSourceFactory,
                collector = collector,
                seekTargets = seekTargets,
                sourceSizeBytes = candidate.sourceSizeBytes
            )
        }

        DebridBenchmarkTransportProfileResult(
            summary = collector.currentSummary(),
            profile = DebridBenchmarkTransportProfile(
                startup = collector.finishStartup(),
                sustained = collector.finishSustained(),
                seek = collector.finishSeek(),
                configSnapshot = configSnapshot,
                rawSamples = collector.rawSamples()
            ),
            terminationReason = terminationReason
        )
    }

    private fun runStartupAndSustainedPhase(
        readableSourceFactory: BenchmarkReadableSourceFactory,
        collector: DebridBenchmarkMetricsCollector,
        observer: DebridBenchmarkObserver
    ): DebridBenchmarkTerminationReason {
        val readableSource = readableSourceFactory.createSource()
        val buffer = ByteArray(readBufferSize)
        val requestStartedAtNs = nanoTimeNs()
        var previousReadAtNs: Long? = null
        var totalBytesRead = 0L

        try {
            readableSource.open(position = 0L)
            while (true) {
                val read = readableSource.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) {
                    return DebridBenchmarkTerminationReason.FAILED
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
                    return DebridBenchmarkTerminationReason.COMPLETED
                }
            }
        } catch (_: CancellationException) {
            return DebridBenchmarkTerminationReason.CANCELED
        } catch (_: InterruptedIOException) {
            return DebridBenchmarkTerminationReason.TIMEOUT
        } catch (_: Exception) {
            return DebridBenchmarkTerminationReason.FAILED
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
}

private class DefaultOptimizedBenchmarkDataSourceFactoryBuilder(
    private val okHttpClient: OkHttpClient
) : OptimizedBenchmarkDataSourceFactoryBuilder {

    override fun create(
        candidate: DebridBenchmarkCandidate,
        configSnapshot: DebridBenchmarkTransportConfigSnapshot,
        allowStartupBootstrapReuse: Boolean
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
