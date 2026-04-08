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

/**
 * JSON key constants for the playback-trace `playback_session_started` event.
 *
 * Single source of truth shared between the writer
 * ([PayloadBuilder.putHeader]) and the offline reader
 * ([com.nexio.tv.instrumentation.ParsedSessionHeader.fromJson]) so a key
 * rename only ever needs to happen in one place. (L6 cleanup.)
 *
 * Nested data classes ([FactoryArgs], [PolicySnapshot],
 * [ClientIdentitySnapshot], [DeviceProvenance]) are flattened into the
 * top-level event JSON; entries below indicate the actual JSON key written
 * by the put-call site even when the source field lives on a nested type.
 */
internal object SessionHeaderKeys {
    // Identity / lifecycle
    const val SESSION_ID = "sessionId"
    const val STARTED_AT_NANOS = "startedAtNanos"
    const val ASSET_KEY_HASH = "assetKeyHash"
    const val SERVICE_KEY = "serviceKey"
    const val PROVIDER = "provider"
    const val BENCHMARK_RESULT_ID = "benchmarkResultId"
    const val BENCHMARK_SOURCE = "benchmarkSource"

    // Envelope / runtime hints
    const val ENVELOPE_PRESENT = "envelopePresent"
    const val RUNTIME_HINTS_PRESENT = "runtimeHintsPresent"

    // Specialization provenance
    const val SPECIALIZATION_STATE = "specializationState"
    const val HINT_SERVICE_KEY = "hintServiceKey"
    const val HINT_HOST_SCOPE = "hintHostScope"
    const val HINT_TRANSPORT_CLASS = "hintTransportClass"
    const val HINT_AGE_MS = "hintAgeMs"
    const val HINT_FRESHNESS_BAND = "hintFreshnessBand"
    const val SPECIALIZATION_MISMATCH_REASON = "specializationMismatchReason"
    const val OBSERVED_HOST_SCOPE = "observedHostScope"
    const val OBSERVED_TRANSPORT_CLASS = "observedTransportClass"

    // Branch + cache
    const val BRANCH = "branch"
    const val CACHE_ACTIVE = "cacheActive"
    const val WARM_AHEAD_FACTORY = "warmAheadFactory"

    // FactoryArgs (no prefix)
    const val ACTIVE_CHUNK_BYTES = "activeChunkBytes"
    const val PARALLEL_CONNECTIONS = "parallelConnections"
    const val KEEP_BEHIND_BYTES = "keepBehindBytes"
    const val BOOTSTRAP_BYTES = "bootstrapBytes"

    // PolicySnapshot (prefixed `policy_`)
    const val POLICY_URGENT_WORKERS = "policy_urgentWorkers"
    const val POLICY_PREFETCH_WORKERS = "policy_prefetchWorkers"
    const val POLICY_URGENT_CHUNK_BYTES = "policy_urgentChunkBytes"
    const val POLICY_PREFETCH_CHUNK_BYTES = "policy_prefetchChunkBytes"
    const val POLICY_SOURCE = "policy_source"

    // ClientIdentitySnapshot (no prefix)
    const val PLAYBACK_CLIENT_HASH = "playbackClientHash"
    const val DISPATCHER_MAX_REQUESTS = "dispatcherMaxRequests"
    const val DISPATCHER_MAX_REQUESTS_PER_HOST = "dispatcherMaxRequestsPerHost"
    const val DISPATCHER_QUEUED_CALLS = "dispatcherQueuedCalls"
    const val DISPATCHER_RUNNING_CALLS = "dispatcherRunningCalls"
    const val CONNECTION_POOL_IDLE_COUNT = "connectionPoolIdleCount"
    const val CONNECTION_POOL_TOTAL_COUNT = "connectionPoolTotalCount"
    const val CALL_TIMEOUT_MS = "callTimeoutMs"
    const val READ_TIMEOUT_MS = "readTimeoutMs"
    const val WRITE_TIMEOUT_MS = "writeTimeoutMs"
    const val CONNECT_TIMEOUT_MS = "connectTimeoutMs"

    // DeviceProvenance (no prefix)
    const val DEVICE_MODEL = "deviceModel"
    const val DEVICE_MANUFACTURER = "deviceManufacturer"
    const val ANDROID_RELEASE = "androidRelease"
    const val ANDROID_SDK_INT = "androidSdkInt"
    const val APP_VERSION_NAME = "appVersionName"
    const val APP_VERSION_CODE = "appVersionCode"
    const val GIT_SHA = "gitSha"
    const val MEMORY_CLASS = "memoryClass"
    const val LARGE_MEMORY_CLASS = "largeMemoryClass"
    const val IS_LOW_RAM_DEVICE = "isLowRamDevice"
    const val NETWORK_TYPE = "networkType"
    const val NETWORK_TRANSPORT_HASH = "networkTransportHash"
}
