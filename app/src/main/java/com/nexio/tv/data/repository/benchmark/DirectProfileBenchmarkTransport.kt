package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.integration.debrid.transport.DirectBenchmarkReadableSourceFactoryBuilder
import javax.inject.Inject

class DirectProfileBenchmarkTransport internal constructor(
    private val delegate: OptimizedBenchmarkTransport
) {

    @Inject
    internal constructor(
        factoryBuilder: DirectBenchmarkReadableSourceFactoryBuilder
    ) : this(
        delegate = OptimizedBenchmarkTransport(
            factoryBuilder = factoryBuilder,
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
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(),
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
