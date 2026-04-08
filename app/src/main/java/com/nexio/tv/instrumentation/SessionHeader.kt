package com.nexio.tv.instrumentation

/**
 * Immutable session header written exactly once at the start of each playback
 * session as the `playback_session_started` event payload. Mirrors spec §A.2
 * including all round-3 additions (specialization provenance, client identity,
 * device provenance, benchmark source).
 */
data class SessionHeader(
    val sessionId: String,
    val startedAtNanos: Long,
    val assetKeyHash: String,
    val serviceKey: String?,
    val provider: String?,
    val benchmarkResultId: String?,
    val benchmarkSource: String?,
    val envelopePresent: Boolean,
    val runtimeHintsPresent: Boolean,
    val specializationState: String,
    val hintServiceKey: String?,
    val hintHostScope: String?,
    val hintTransportClass: String?,
    val hintAgeMs: Long?,
    val hintFreshnessBand: String?,
    val specializationMismatchReason: String?,
    val observedHostScope: String?,
    val observedTransportClass: String?,
    val branch: String,
    val cacheActive: Boolean,
    val warmAheadFactory: String?,
    val factoryArgs: FactoryArgs,
    val initialPolicy: PolicySnapshot,
    val clientIdentity: ClientIdentitySnapshot,
    val device: DeviceProvenance
)

data class FactoryArgs(
    val activeChunkBytes: Long,
    val parallelConnections: Int,
    val keepBehindBytes: Long,
    val bootstrapBytes: Long
)

data class PolicySnapshot(
    val urgentWorkers: Int,
    val prefetchWorkers: Int,
    val urgentChunkBytes: Long,
    val prefetchChunkBytes: Long,
    val source: String
)

data class ClientIdentitySnapshot(
    val playbackClientHash: String,
    val dispatcherMaxRequests: Int,
    val dispatcherMaxRequestsPerHost: Int,
    val dispatcherQueuedCalls: Int,
    val dispatcherRunningCalls: Int,
    val connectionPoolIdleCount: Int,
    val connectionPoolTotalCount: Int,
    val callTimeoutMs: Long,
    val readTimeoutMs: Long,
    val writeTimeoutMs: Long,
    val connectTimeoutMs: Long
)

data class DeviceProvenance(
    val deviceModel: String,
    val deviceManufacturer: String,
    val androidRelease: String,
    val androidSdkInt: Int,
    val appVersionName: String,
    val appVersionCode: Long,
    val gitSha: String?,
    val memoryClass: Int,
    val largeMemoryClass: Int,
    val isLowRamDevice: Boolean,
    val networkType: String,
    val networkTransportHash: String?
)
