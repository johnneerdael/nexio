package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.repository.DebridLibraryService

enum class DebridBenchmarkProvider(
    val storageKey: String,
    val listKey: String
) {
    REAL_DEBRID(
        storageKey = "real_debrid",
        listKey = DebridLibraryService.REAL_DEBRID_LIST_KEY
    ),
    PREMIUMIZE(
        storageKey = "premiumize",
        listKey = DebridLibraryService.PREMIUMIZE_LIST_KEY
    ),
    TORBOX(
        storageKey = "torbox",
        listKey = DebridLibraryService.TORBOX_LIST_KEY
    ),
    EASY_DEBRID(
        storageKey = "easy_debrid",
        listKey = DebridLibraryService.EASY_DEBRID_LIST_KEY
    );

    companion object {
        private val byStorageKey = entries.associateBy { it.storageKey }

        fun fromStorageKey(storageKey: String): DebridBenchmarkProvider? {
            return byStorageKey[storageKey]
        }
    }
}

data class DebridBenchmarkSummary(
    val startupTimeMs: Long? = null,
    val sustainedThroughputMbps: Double? = null,
    val transferredBytes: Long = 0L,
    val elapsedMs: Long = 0L
)

data class DebridBenchmarkTransportFailure(
    val exceptionClass: String? = null,
    val message: String? = null,
    val chunkIndex: Long? = null,
    val rootCauseClass: String? = null,
    val rootCauseMessage: String? = null,
    val recoverableFailureCount: Int? = null,
    val recoverableTimeoutCount: Int? = null
)

data class DebridBenchmarkFailureDetails(
    val candidate: DebridBenchmarkCandidateMetadata? = null,
    val failedTransport: DebridBenchmarkTransportMode? = null,
    val transportFailure: DebridBenchmarkTransportFailure? = null,
    val direct: DebridBenchmarkTransportProfile? = null,
    val optimized: DebridBenchmarkTransportProfile? = null
)

enum class DebridBenchmarkTransportMode {
    DIRECT,
    OPTIMIZED;

    val wireKey: String
        get() = when (this) {
            DIRECT -> "direct"
            OPTIMIZED -> "optimized"
        }

    companion object {
        private val byWireKey = entries.associateBy { it.wireKey }

        fun fromWireKey(wireKey: String): DebridBenchmarkTransportMode? {
            return byWireKey[wireKey]
        }
    }
}

enum class DebridBenchmarkPhase {
    STARTUP,
    SUSTAINED,
    SEEK;

    val wireKey: String
        get() = when (this) {
            STARTUP -> "startup"
            SUSTAINED -> "sustained"
            SEEK -> "seek"
        }

    companion object {
        private val byWireKey = entries.associateBy { it.wireKey }

        fun fromWireKey(wireKey: String): DebridBenchmarkPhase? {
            return byWireKey[wireKey]
        }
    }
}

data class DebridBenchmarkPhaseExecution(
    val phase: DebridBenchmarkPhase,
    val order: List<DebridBenchmarkTransportMode>
)

data class DebridBenchmarkCandidateMetadata(
    val filename: String? = null,
    val sizeBytes: Long? = null,
    val host: String? = null,
    val directUrlFingerprint: String? = null
)

enum class DeviceHdrType {
    DOLBY_VISION,
    HDR10,
    HDR10_PLUS,
    HLG;

    val wireKey: String
        get() = when (this) {
            DOLBY_VISION -> "dolby_vision"
            HDR10 -> "hdr10"
            HDR10_PLUS -> "hdr10_plus"
            HLG -> "hlg"
        }

    companion object {
        private val byWireKey = entries.associateBy { it.wireKey }

        fun fromWireKey(wireKey: String): DeviceHdrType? = byWireKey[wireKey]
    }
}

data class CodecSupport(
    val hardwareAccelerated: Boolean,
    val softwareOnlyAvailable: Boolean,
    val secureSupported: Boolean
)

data class DeviceVideoDecodeCapabilities(
    val h264: CodecSupport? = null,
    val hevc: CodecSupport? = null,
    val av1: CodecSupport? = null,
    val dolbyVision: CodecSupport? = null
)

data class AudioEncodingSupport(
    val supported: Boolean,
    val passthroughLikely: Boolean
)

data class DeviceAudioOutputCapabilities(
    val ac3: AudioEncodingSupport = AudioEncodingSupport(false, false),
    val eac3: AudioEncodingSupport = AudioEncodingSupport(false, false),
    val atmos: AudioEncodingSupport = AudioEncodingSupport(false, false),
    val truehd: AudioEncodingSupport = AudioEncodingSupport(false, false),
    val dts: AudioEncodingSupport = AudioEncodingSupport(false, false),
    val dtshd: AudioEncodingSupport = AudioEncodingSupport(false, false),
    val dtsx: AudioEncodingSupport = AudioEncodingSupport(false, false)
)

data class DeviceHdrCapabilityEvidence(
    val displayId: Int? = null,
    val rawSupportedHdrTypes: List<String> = emptyList()
)

data class AudioDirectProfileEvidence(
    val format: String,
    val channelMasks: List<Int> = emptyList(),
    val sampleRates: List<Int> = emptyList()
)

data class AudioPlaybackProbeEvidence(
    val bucket: String,
    val format: String,
    val channelMask: Int,
    val sampleRateHz: Int,
    val supportMode: String
)

data class AudioOutputDeviceEvidence(
    val id: Int? = null,
    val type: String,
    val productName: String? = null,
    val encodings: List<String> = emptyList()
)

data class DeviceAudioCapabilityEvidence(
    val discoveryMode: String? = null,
    val routedDeviceTypes: List<String> = emptyList(),
    val outputDevices: List<AudioOutputDeviceEvidence> = emptyList(),
    val directProfiles: List<AudioDirectProfileEvidence> = emptyList(),
    val directPlaybackProbes: List<AudioPlaybackProbeEvidence> = emptyList()
)

data class VideoDecoderEvidence(
    val codecName: String,
    val mimeType: String,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val secureSupported: Boolean
)

data class DeviceVideoDecoderEvidence(
    val scannedDecoderCount: Int = 0,
    val decoders: List<VideoDecoderEvidence> = emptyList()
)

data class DeviceCapabilityEvidence(
    val hdr: DeviceHdrCapabilityEvidence? = null,
    val audio: DeviceAudioCapabilityEvidence? = null,
    val video: DeviceVideoDecoderEvidence? = null
)

data class DeviceCapabilitySnapshot(
    val model: String? = null,
    val manufacturer: String? = null,
    val sdkInt: Int,
    val displayHdrTypes: Set<DeviceHdrType> = emptySet(),
    val videoDecode: DeviceVideoDecodeCapabilities = DeviceVideoDecodeCapabilities(),
    val audioOutput: DeviceAudioOutputCapabilities = DeviceAudioOutputCapabilities(),
    val evidence: DeviceCapabilityEvidence? = null,
    val capturedAtMs: Long
)

data class DebridBenchmarkSessionMetadata(
    val benchmarkVersion: Int,
    val executionOrder: List<DebridBenchmarkPhaseExecution> = emptyList(),
    val totalElapsedMs: Long? = null
)

data class DebridBenchmarkStartupMetrics(
    val initialTtfbMs: Long? = null,
    val startupFailureRate: Double? = null
)

data class DebridBenchmarkSustainedMetrics(
    val collectorVersion: Int = 1,
    val samplingMode: String? = null,
    val bucketMs: Long? = null,
    val decisionWindowMs: Long? = null,
    val averageThroughputMbps: Double? = null,
    val derivedAverageThroughputMbps: Double? = null,
    val actionable: Boolean = true,
    val recoverableFailureCount: Int = 0,
    val recoverableTimeoutCount: Int = 0,
    val p10ThroughputMbps: Double? = null,
    val p50ThroughputMbps: Double? = null,
    val peakThroughputMbps: Double? = null,
    val throughputStddevMbps: Double? = null,
    val throughputCv: Double? = null,
    val stallCount: Int? = null,
    val maxReadGapMs: Long? = null,
    val steadyStateReferenceMbps: Double? = null,
    val cacheAbsorbableDeficitCount: Int? = null,
    val cacheDrainingDeficitCount: Int? = null,
    val estimatedMinCacheHeadroomMs: Long? = null,
    val bytesTransferred: Long? = null,
    val elapsedMs: Long? = null,
    val frontierMetrics: FrontierMetrics? = null
)

data class DebridBenchmarkSeekMetrics(
    val seekTtfbP50Ms: Long? = null,
    val seekTtfbP95Ms: Long? = null,
    val seekTtfbP99Ms: Long? = null,
    val seekTtfbStddevMs: Double? = null,
    val seekFailRate: Double? = null
)

data class DebridBenchmarkThroughputBucketSample(
    val startOffsetMs: Long,
    val durationMs: Long,
    val bytesTransferred: Long,
    val throughputMbps: Double,
    val complete: Boolean
)

data class DebridBenchmarkTransportConfigSnapshot(
    val useParallelConnections: Boolean? = null,
    val parallelConnectionCount: Int? = null,
    val parallelChunkSizeMb: Int? = null,
    val chunkWaitTimeoutMs: Long? = null
)

data class DebridBenchmarkTransportDecisionMetrics(
    val safeSustainedBudgetMbps: Double? = null,
    val startupSafeBudgetMbps: Double? = null,
    val steadyStateSafeBudgetMbps: Double? = null,
    val actionable: Boolean = true,
    val shadowPlayerResult: ShadowPlayerSimulationResult? = null,
    val legacyBudgetMbps: Double? = null,
    val budgetDivergenceRatio: Double? = null
)

data class DebridBenchmarkSeekSample(
    val targetOffsetBytes: Long,
    val ttfbMs: Long? = null,
    val succeeded: Boolean
)

data class DebridBenchmarkRawSamples(
    val throughputWindowsMbps: List<Double> = emptyList(),
    val throughputBuckets: List<DebridBenchmarkThroughputBucketSample> = emptyList(),
    val seekSamples: List<DebridBenchmarkSeekSample> = emptyList(),
    val frontierEvents: List<FrontierEvent> = emptyList()
)

data class DebridBenchmarkTransportProfile(
    val startup: DebridBenchmarkStartupMetrics,
    val sustained: DebridBenchmarkSustainedMetrics,
    val seek: DebridBenchmarkSeekMetrics,
    val decision: DebridBenchmarkTransportDecisionMetrics? = null,
    val configSnapshot: DebridBenchmarkTransportConfigSnapshot? = null,
    val rawSamples: DebridBenchmarkRawSamples = DebridBenchmarkRawSamples()
)

data class DebridBenchmarkComparisonSummary(
    val sustainedWinner: DebridBenchmarkTransportMode? = null,
    val seekWinner: DebridBenchmarkTransportMode? = null,
    val stabilityWinner: DebridBenchmarkTransportMode? = null
)

enum class DebridBenchmarkTerminationReason {
    COMPLETED,
    NO_LARGE_DOWNLOAD,
    NO_PLAYABLE_LIBRARY_ITEM,
    CANCELED,
    TIMEOUT,
    FAILED;

    val wireKey: String
        get() = when (this) {
            COMPLETED -> "completed"
            NO_LARGE_DOWNLOAD -> "no_large_download"
            NO_PLAYABLE_LIBRARY_ITEM -> "no_playable_library_item"
            CANCELED -> "canceled"
            TIMEOUT -> "timeout"
            FAILED -> "failed"
        }

    companion object {
        private val byWireKey = entries.associateBy { it.wireKey }

        fun fromWireKey(wireKey: String): DebridBenchmarkTerminationReason? {
            return byWireKey[wireKey]
        }
    }
}

data class DebridBenchmarkResult(
    val provider: DebridBenchmarkProvider,
    val measuredAtMs: Long,
    val summary: DebridBenchmarkSummary,
    val terminationReason: DebridBenchmarkTerminationReason,
    val candidate: DebridBenchmarkCandidateMetadata? = null,
    val device: DeviceCapabilitySnapshot? = null,
    val session: DebridBenchmarkSessionMetadata? = null,
    val direct: DebridBenchmarkTransportProfile? = null,
    val optimized: DebridBenchmarkTransportProfile? = null,
    val comparison: DebridBenchmarkComparisonSummary? = null,
    /**
     * Optional pre-computed [CapabilityEnvelope] attached at benchmark run completion.
     * For RD/PM this is always built via the merge-only [toCapabilityEnvelope] path so the
     * locked shape invariant is guaranteed. Non-locked providers (TorBox, EasyDebrid) leave
     * this null. Legacy stored rows without this field deserialize as null.
     */
    val capabilityEnvelope: CapabilityEnvelope? = null
)

data class DebridBenchmarkOutcome(
    val provider: DebridBenchmarkProvider,
    val summary: DebridBenchmarkSummary,
    val terminationReason: DebridBenchmarkTerminationReason,
    val result: DebridBenchmarkResult? = null,
    val failureDetails: DebridBenchmarkFailureDetails? = null
)

data class DebridBenchmarkCandidate(
    val provider: DebridBenchmarkProvider,
    val directUrl: String,
    val headers: Map<String, String>,
    val filename: String?,
    val sourceSizeBytes: Long?
)

sealed interface DebridBenchmarkCandidateLookupResult {
    data class Candidates(
        val items: List<DebridBenchmarkCandidate>
    ) : DebridBenchmarkCandidateLookupResult

    data object NoLargeDownload : DebridBenchmarkCandidateLookupResult

    data object NoPlayableLibraryItem : DebridBenchmarkCandidateLookupResult
}

sealed interface DebridBenchmarkCandidateResolution {
    data class Candidate(
        val value: DebridBenchmarkCandidate
    ) : DebridBenchmarkCandidateResolution

    data object NoLargeDownload : DebridBenchmarkCandidateResolution

    data object NoPlayableLibraryItem : DebridBenchmarkCandidateResolution
}
