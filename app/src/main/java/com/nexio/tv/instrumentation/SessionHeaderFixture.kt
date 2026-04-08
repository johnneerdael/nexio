package com.nexio.tv.instrumentation

import androidx.annotation.VisibleForTesting

/**
 * L7 — shared [SessionHeader] factory for test code.
 *
 * Lives in `main/` (rather than `test/` or `androidTest/`) so it can be
 * referenced from BOTH the JVM unit-test source set
 * (`PlaybackTracerTest`) and the on-device microbenchmark source set
 * (`PlaybackTracerBenchmarkTest`). Marked [VisibleForTesting] so static
 * analysis flags any production-code reference.
 *
 * R8 will dead-code this object out of release builds since the
 * production code path never references it (the keep rule for
 * `com.nexio.tv.instrumentation.**` notwithstanding — R8's tree shaker
 * doesn't strip kept members, so this object will ship in release with
 * the rest of the package; that's an accepted ~1 KB cost given the
 * deduplication win across two test source sets).
 */
@VisibleForTesting
internal object SessionHeaderFixture {

    /**
     * Build a deterministic [SessionHeader] suitable for tests and
     * microbenchmarks. All fields default to fixed sentinel values; pass
     * named arguments to override per-test.
     */
    fun build(
        sessionId: String = "test-session",
        startedAtNanos: Long = 1L,
        assetKeyHash: String = "deadbeef0000",
        serviceKey: String? = "real-debrid",
        provider: String? = "addon-x",
        benchmarkResultId: String? = null,
        benchmarkSource: String? = null,
        envelopePresent: Boolean = false,
        runtimeHintsPresent: Boolean = false,
        specializationState: String = "baseline",
        hintServiceKey: String? = null,
        hintHostScope: String? = null,
        hintTransportClass: String? = null,
        hintAgeMs: Long? = null,
        hintFreshnessBand: String? = null,
        specializationMismatchReason: String? = null,
        observedHostScope: String? = null,
        observedTransportClass: String? = null,
        branch: String = "prds",
        cacheActive: Boolean = false,
        warmAheadFactory: String? = null,
        factoryArgs: FactoryArgs = FactoryArgs(
            activeChunkBytes = 8L * 1024 * 1024,
            parallelConnections = 4,
            keepBehindBytes = 32L * 1024 * 1024,
            bootstrapBytes = 4L * 1024 * 1024,
        ),
        initialPolicy: PolicySnapshot = PolicySnapshot(
            urgentWorkers = 2,
            prefetchWorkers = 16,
            urgentChunkBytes = 4L * 1024 * 1024,
            prefetchChunkBytes = 16L * 1024 * 1024,
            source = "fallback",
        ),
        clientIdentity: ClientIdentitySnapshot = ClientIdentitySnapshot(
            playbackClientHash = "abc123",
            dispatcherMaxRequests = 64,
            dispatcherMaxRequestsPerHost = 12,
            dispatcherQueuedCalls = 0,
            dispatcherRunningCalls = 0,
            connectionPoolIdleCount = 0,
            connectionPoolTotalCount = 0,
            callTimeoutMs = 0L,
            readTimeoutMs = 30_000L,
            writeTimeoutMs = 30_000L,
            connectTimeoutMs = 10_000L,
        ),
        device: DeviceProvenance = DeviceProvenance(
            deviceModel = "TEST",
            deviceManufacturer = "TEST",
            androidRelease = "14",
            androidSdkInt = 34,
            appVersionName = "test",
            appVersionCode = 1L,
            gitSha = null,
            memoryClass = 256,
            largeMemoryClass = 512,
            isLowRamDevice = false,
            networkType = "wifi",
            networkTransportHash = null,
        ),
    ): SessionHeader = SessionHeader(
        sessionId = sessionId,
        startedAtNanos = startedAtNanos,
        assetKeyHash = assetKeyHash,
        serviceKey = serviceKey,
        provider = provider,
        benchmarkResultId = benchmarkResultId,
        benchmarkSource = benchmarkSource,
        envelopePresent = envelopePresent,
        runtimeHintsPresent = runtimeHintsPresent,
        specializationState = specializationState,
        hintServiceKey = hintServiceKey,
        hintHostScope = hintHostScope,
        hintTransportClass = hintTransportClass,
        hintAgeMs = hintAgeMs,
        hintFreshnessBand = hintFreshnessBand,
        specializationMismatchReason = specializationMismatchReason,
        observedHostScope = observedHostScope,
        observedTransportClass = observedTransportClass,
        branch = branch,
        cacheActive = cacheActive,
        warmAheadFactory = warmAheadFactory,
        factoryArgs = factoryArgs,
        initialPolicy = initialPolicy,
        clientIdentity = clientIdentity,
        device = device,
    )
}
