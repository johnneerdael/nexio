package com.nexio.tv.data.repository.benchmark

import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nexio.tv.ui.screens.player.RuntimeTransportObservation
import javax.inject.Inject
import javax.inject.Named
import okhttp3.OkHttpClient

class DirectProfileBenchmarkTransport internal constructor(
    private val delegate: OptimizedBenchmarkTransport
) {

    @Inject
    constructor(
        @Named("benchmark") okHttpClient: OkHttpClient
    ) : this(
        delegate = OptimizedBenchmarkTransport(
            factoryBuilder = DefaultDirectBenchmarkReadableSourceFactoryBuilder(okHttpClient),
            nanoTimeNs = System::nanoTime,
            sustainedThresholdBytes = 500L * 1024L * 1024L,
            sustainedThresholdElapsedMs = 120_000L,
            seekProbeBytes = 256L * 1024L,
            readBufferSize = 256 * 1024,
            maxRecoverableFailures = 8,
            sleepMs = { durationMs -> Thread.sleep(durationMs) },
            noProgressFailureTimeoutMs = 20_000L,
            completionGuardBandMs = 10_000L
        )
    )

    suspend fun runProfile(
        candidate: DebridBenchmarkCandidate,
        observer: DebridBenchmarkObserver = DebridBenchmarkObserver {},
        seekTargets: List<Long> = emptyList()
    ): DebridBenchmarkTransportProfileResult {
        val result = delegate.runProfile(
            candidate = candidate,
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(useParallelConnections = false),
            observer = observer,
            seekTargets = seekTargets.ifEmpty {
                listOfNotNull(
                    candidate.sourceSizeBytes?.div(4L),
                    candidate.sourceSizeBytes?.div(2L),
                    candidate.sourceSizeBytes?.times(3L)?.div(4L)
                ).distinct()
            }
        )
        return result.copy(
            profile = result.profile.copy(configSnapshot = null)
        )
    }
}

private class DefaultDirectBenchmarkReadableSourceFactoryBuilder(
    private val okHttpClient: OkHttpClient
) : OptimizedBenchmarkDataSourceFactoryBuilder {

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
        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient).apply {
            setDefaultRequestProperties(candidate.headers)
        }
        return BenchmarkReadableSourceFactory {
            Media3BenchmarkReadableSource(
                dataSource = upstreamFactory.createDataSource(),
                candidate = candidate
            )
        }
    }
}
