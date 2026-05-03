package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.core.stream.ParsedStreamInfo
import com.nexio.tv.core.stream.StreamCardModel
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tanh

data class ShadowRequestContext(
    val requestId: String,
    val videoId: String,
    val contentType: String,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val runtimeMinutes: Int?
)

data class ShadowBenchmarkReference(
    val provider: DebridBenchmarkProvider,
    val measuredAtMs: Long,
    val benchmarkVersion: Int?,
    val host: String?,
    val directUrlFingerprint: String?
)

data class ShadowContentScoreBreakdown(
    val resolutionPoints: Int,
    val audioPoints: Int,
    val hdrPoints: Int,
    val codecPoints: Int,
    val releaseTypePoints: Int,
    val bitrateQualityPoints: Int,
    val synergyPoints: Int,
    val penaltyPoints: Int,
    val lowQuality4kPenalty: Int,
    val resolutionTier: String,
    val releaseTypeTier: String,
    val codecTier: String,
    val hdrTier: String,
    val audioTier: String,
    val audioSupportTier: String,
    val hdrSupportTier: String,
    val realismRatio: Double
)

data class ShadowTransportScoreBreakdown(
    val provider: DebridBenchmarkProvider,
    val transport: DebridBenchmarkTransportMode,
    val safeBudgetMbps: Double,
    val requiredMbps: Double,
    val suitabilityRatio: Double,
    val ratioScore: Int,
    val startupScore: Int,
    val seekScore: Int,
    val stabilityScore: Int,
    val startupTtfbMs: Long?,
    val seekTtfbP95Ms: Long?,
    val seekFailRate: Double?
)

data class ShadowDecisionBreakdown(
    val averageBitrateMbps: Double,
    val releaseType: String,
    val lowQuality4k: Boolean,
    val realismRatio: Double,
    val content: ShadowContentScoreBreakdown,
    val transport: ShadowTransportScoreBreakdown
)

data class ShadowParsedStreamFacts(
    val serviceId: String? = null,
    val filename: String? = null,
    val folderName: String? = null,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    val runtimeSource: String? = null,
    val resolution: String? = null,
    val quality: String? = null,
    val videoCodec: String? = null,
    val audioTags: List<String> = emptyList(),
    val audioChannels: List<String> = emptyList(),
    val visualTags: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val releaseGroup: String? = null,
    val cached: Boolean? = null
)

data class ShadowStreamDecision(
    val streamKey: String,
    val parsed: ShadowParsedStreamFacts = ShadowParsedStreamFacts(),
    val provider: DebridBenchmarkProvider,
    val transport: DebridBenchmarkTransportMode,
    val finalScore: Int,
    val contentQualityScore: Int,
    val transportFitScore: Int,
    val suitabilityRatio: Double,
    val requiredMbps: Double,
    val safeBudgetMbps: Double,
    val resolution: String?,
    val hdrTags: List<String>,
    val audioTags: List<String>,
    val breakdown: ShadowDecisionBreakdown
)

data class ShadowRejectedStream(
    val streamKey: String,
    val parsed: ShadowParsedStreamFacts = ShadowParsedStreamFacts(),
    val provider: DebridBenchmarkProvider? = null,
    val reasons: List<ShadowRejectReason>
)

enum class ShadowRejectReason {
    NOT_DEBRID_WRAPPED,
    MISSING_BENCHMARK,
    MISSING_SIZE,
    LOW_QUALITY_RELEASE,
    MISSING_RUNTIME,
    UNSUPPORTED_CODEC,
    EXCEEDS_AUTOPLAY_CAP,
    INSUFFICIENT_TRANSPORT_BUDGET,
    NO_ELIGIBLE_TRANSPORT
}

data class ShadowAutoPlayDecisionEvent(
    val eventVersion: Int,
    val eventType: String,
    val request: ShadowRequestContext,
    val benchmarksUsed: List<ShadowBenchmarkReference>,
    val winners: List<ShadowStreamDecision>,
    val rejected: List<ShadowRejectedStream>,
    val selected: ShadowStreamDecision?,
    val selectedNonDolbyVisionFallback: ShadowStreamDecision? = null,
    val timingsMs: Long? = null
)

@Singleton
class BenchmarkAwareStreamScorer internal constructor(
    private val config: BenchmarkAwareStreamScoringConfig
) {

    @Inject
    constructor() : this(config = BenchmarkAwareStreamScoringConfig.default())

    fun score(
        request: ShadowRequestContext,
        streams: List<StreamCardModel>,
        benchmarkSessions: Map<DebridBenchmarkProvider, DebridBenchmarkResult>,
        activeTransportMode: DebridBenchmarkTransportMode? = null,
        elapsedMs: Long? = null,
        autoplayMaxBitrateMbps: Double? = null
    ): ShadowAutoPlayDecisionEvent {
        val movieMode = request.isMovieLike()
        val showMode = request.isSeriesLike()
        val benchmarkReferences = benchmarkSessions.values.map { session ->
            ShadowBenchmarkReference(
                provider = session.provider,
                measuredAtMs = session.measuredAtMs,
                benchmarkVersion = session.session?.benchmarkVersion,
                host = session.candidate?.host,
                directUrlFingerprint = session.candidate?.directUrlFingerprint
            )
        }.sortedBy { it.provider.storageKey }

        val winners = mutableListOf<ShadowStreamDecision>()
        val rejected = mutableListOf<ShadowRejectedStream>()

        streams.forEach { item ->
            val provider = item.toBenchmarkProvider()
            val streamKey = item.shadowStreamKey()
            val parsedFacts = item.shadowParsedFacts(request)
            if (provider == null) {
                rejected += ShadowRejectedStream(
                    streamKey = streamKey,
                    parsed = parsedFacts,
                    reasons = listOf(ShadowRejectReason.NOT_DEBRID_WRAPPED)
                )
                return@forEach
            }
            val benchmarkSession = benchmarkSessions[provider]
            if (benchmarkSession == null) {
                rejected += ShadowRejectedStream(
                    streamKey = streamKey,
                    parsed = parsedFacts,
                    provider = provider,
                    reasons = listOf(ShadowRejectReason.MISSING_BENCHMARK)
                )
                return@forEach
            }

            evaluateStream(
                item = item,
                provider = provider,
                benchmarkSession = benchmarkSession,
                request = request,
                activeTransportMode = activeTransportMode,
                autoplayMaxBitrateMbps = autoplayMaxBitrateMbps,
                movieMode = movieMode,
                showMode = showMode
            ).fold(
                onSuccess = { winners += it },
                onFailure = { reasons ->
                    rejected += ShadowRejectedStream(
                        streamKey = streamKey,
                        parsed = parsedFacts,
                        provider = provider,
                        reasons = reasons
                    )
                }
            )
        }

        val ranked = winners.sortedWith(baseDecisionComparator())
        val finalRanked = applyViableBitrateBucket(ranked)

        val selected = finalRanked.firstOrNull()
        val selectedNonDolbyVisionFallback =
            if (selected != null && selected.isDolbyVisionSource()) {
                selectNonDolbyVisionFallback(
                    selected = selected,
                    ranked = ranked,
                    initialPool = finalRanked,
                    movieMode = movieMode,
                    showMode = showMode
                )
            } else {
                null
            }

        return ShadowAutoPlayDecisionEvent(
            eventVersion = 1,
            eventType = "shadow_autoplay_decision",
            request = request,
            benchmarksUsed = benchmarkReferences,
            winners = finalRanked,
            rejected = rejected.sortedBy { it.streamKey },
            selected = selected,
            selectedNonDolbyVisionFallback = selectedNonDolbyVisionFallback,
            timingsMs = elapsedMs
        )
    }

    fun scoreWithManualCap(
        request: ShadowRequestContext,
        streams: List<StreamCardModel>,
        manualBitrateCap: Double,
        elapsedMs: Long? = null,
        device: DeviceCapabilitySnapshot? = null
    ): ShadowAutoPlayDecisionEvent {
        val safeManualCap = manualBitrateCap.takeIf { it.isFinite() && it > 0.0 } ?: 20.0
        val winners = mutableListOf<ShadowStreamDecision>()
        val rejected = mutableListOf<ShadowRejectedStream>()

        streams.forEach { item ->
            val provider = item.toBenchmarkProvider()
            val streamKey = item.shadowStreamKey()
            val parsedFacts = item.shadowParsedFacts(request)
            if (provider == null) {
                rejected += ShadowRejectedStream(
                    streamKey = streamKey,
                    parsed = parsedFacts,
                    reasons = listOf(ShadowRejectReason.NOT_DEBRID_WRAPPED)
                )
                return@forEach
            }

            evaluateStreamWithManualCap(
                item = item,
                provider = provider,
                request = request,
                manualBitrateCap = safeManualCap,
                device = device
            ).fold(
                onSuccess = { winners += it },
                onFailure = { reasons ->
                    rejected += ShadowRejectedStream(
                        streamKey = streamKey,
                        parsed = parsedFacts,
                        provider = provider,
                        reasons = reasons
                    )
                }
            )
        }

        val ranked = winners.sortedWith(baseDecisionComparator())
        val finalRanked = applyViableBitrateBucket(ranked)

        return ShadowAutoPlayDecisionEvent(
            eventVersion = 1,
            eventType = "shadow_autoplay_decision",
            request = request,
            benchmarksUsed = emptyList(),
            winners = finalRanked,
            rejected = rejected.sortedBy { it.streamKey },
            selected = finalRanked.firstOrNull(),
            selectedNonDolbyVisionFallback = null,
            timingsMs = elapsedMs
        )
    }

    private fun evaluateStream(
        item: StreamCardModel,
        provider: DebridBenchmarkProvider,
        benchmarkSession: DebridBenchmarkResult,
        request: ShadowRequestContext,
        activeTransportMode: DebridBenchmarkTransportMode?,
        autoplayMaxBitrateMbps: Double?,
        movieMode: Boolean,
        showMode: Boolean
    ): EitherSuccessOrReject<ShadowStreamDecision> {
        val parsed = item.parsed
        val sizeBytes = item.effectiveSizeBytes()
        if (sizeBytes == null || sizeBytes <= 0L) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.MISSING_SIZE)
        }
        val runtimeMs = item.shadowRuntimeMs(request)
        val hasRuntime = runtimeMs != null && runtimeMs > 0L
        val averageBitrateMbps = runtimeMs?.takeIf { it > 0L }?.let {
            calculateAverageBitrateMbps(sizeBytes, it)
        } ?: 0.0
        if (autoplayMaxBitrateMbps != null &&
            autoplayMaxBitrateMbps > 0.0 &&
            averageBitrateMbps > autoplayMaxBitrateMbps
        ) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.EXCEEDS_AUTOPLAY_CAP)
        }
        val resolutionTier = resolveResolutionTier(parsed.resolution)
        val releaseType = classifyReleaseType(parsed, averageBitrateMbps)
        if (releaseType in HARD_REJECT_RELEASE_TYPES) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.LOW_QUALITY_RELEASE)
        }
        val requiredMbps = if (hasRuntime) {
            averageBitrateMbps * config.burstMargins.getValue(releaseType)
        } else {
            0.0
        }
        val transportOption = if (hasRuntime) {
            bestTransportOption(
                provider = provider,
                benchmarkSession = benchmarkSession,
                requiredMbps = requiredMbps,
                activeTransportMode = activeTransportMode,
                movieMode = movieMode,
                showMode = showMode
            ) ?: return EitherSuccessOrReject.reject(
                ShadowRejectReason.INSUFFICIENT_TRANSPORT_BUDGET,
                ShadowRejectReason.NO_ELIGIBLE_TRANSPORT
            )
        } else {
            bestTransportOptionWithoutRuntime(
                benchmarkSession = benchmarkSession,
                activeTransportMode = activeTransportMode
            ) ?: return EitherSuccessOrReject.reject(ShadowRejectReason.NO_ELIGIBLE_TRANSPORT)
        }

        val device = benchmarkSession.device
        var codecTier = resolveVideoCodecTier(parsed.encode, device)
        if (codecTier == ShadowVideoCodecTier.OTHER &&
            resolutionTier == ShadowResolutionTier.UHD_2160 &&
            releaseType == ShadowReleaseType.REMUX
        ) {
            codecTier = when {
                device == null -> ShadowVideoCodecTier.HEVC_HW
                device.videoDecode.hevc?.hardwareAccelerated == true -> ShadowVideoCodecTier.HEVC_HW
                device.videoDecode.hevc?.softwareOnlyAvailable == true -> ShadowVideoCodecTier.HEVC_SW
                else -> ShadowVideoCodecTier.OTHER
            }
        }
        if (codecTier == ShadowVideoCodecTier.UNSUPPORTED) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.UNSUPPORTED_CODEC)
        }

        val contentBreakdown = buildContentScoreBreakdown(
            parsed = parsed,
            averageBitrateMbps = averageBitrateMbps,
            resolutionTier = resolutionTier,
            releaseType = releaseType,
            codecTier = codecTier,
            device = device,
            runtimeKnown = hasRuntime
        )
        val contentScore = contentBreakdown.total()
        val finalScore = contentScore + transportOption.totalScore()

        return EitherSuccessOrReject.success(
            ShadowStreamDecision(
                streamKey = item.shadowStreamKey(),
                parsed = item.shadowParsedFacts(request),
                provider = provider,
                transport = transportOption.transport,
                finalScore = finalScore,
                contentQualityScore = contentScore,
                transportFitScore = transportOption.totalScore(),
                suitabilityRatio = transportOption.suitabilityRatio,
                requiredMbps = requiredMbps,
                safeBudgetMbps = transportOption.safeBudgetMbps,
                resolution = parsed.resolution,
                hdrTags = parsed.visualTags.filter { it in HDR_VISUAL_TAGS },
                audioTags = parsed.audioTags,
                breakdown = ShadowDecisionBreakdown(
                    averageBitrateMbps = averageBitrateMbps,
                    releaseType = releaseType.wireKey,
                    lowQuality4k = contentBreakdown.lowQuality4kPenalty < 0,
                    realismRatio = contentBreakdown.realismRatio,
                    content = contentBreakdown,
                    transport = transportOption.toBreakdown(provider, requiredMbps)
                )
            )
        )
    }

    private fun evaluateStreamWithManualCap(
        item: StreamCardModel,
        provider: DebridBenchmarkProvider,
        request: ShadowRequestContext,
        manualBitrateCap: Double,
        device: DeviceCapabilitySnapshot?
    ): EitherSuccessOrReject<ShadowStreamDecision> {
        val parsed = item.parsed
        val sizeBytes = item.effectiveSizeBytes()
        if (sizeBytes == null || sizeBytes <= 0L) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.MISSING_SIZE)
        }

        val runtimeMs = item.shadowRuntimeMs(request)
        val hasRuntime = runtimeMs != null && runtimeMs > 0L
        val averageBitrateMbps = runtimeMs?.takeIf { it > 0L }?.let {
            calculateAverageBitrateMbps(sizeBytes, it)
        } ?: return EitherSuccessOrReject.reject(ShadowRejectReason.MISSING_RUNTIME)

        if (averageBitrateMbps > manualBitrateCap) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.EXCEEDS_AUTOPLAY_CAP)
        }

        val resolutionTier = resolveResolutionTier(parsed.resolution)
        val releaseType = classifyReleaseType(parsed, averageBitrateMbps)
        if (releaseType in HARD_REJECT_RELEASE_TYPES) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.LOW_QUALITY_RELEASE)
        }
        var codecTier = resolveVideoCodecTier(parsed.encode, device)
        if (codecTier == ShadowVideoCodecTier.OTHER &&
            resolutionTier == ShadowResolutionTier.UHD_2160 &&
            releaseType == ShadowReleaseType.REMUX
        ) {
            codecTier = when {
                device == null -> ShadowVideoCodecTier.HEVC_HW
                device.videoDecode.hevc?.hardwareAccelerated == true -> ShadowVideoCodecTier.HEVC_HW
                device.videoDecode.hevc?.softwareOnlyAvailable == true -> ShadowVideoCodecTier.HEVC_SW
                else -> ShadowVideoCodecTier.OTHER
            }
        }
        if (codecTier == ShadowVideoCodecTier.UNSUPPORTED) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.UNSUPPORTED_CODEC)
        }

        val contentBreakdown = buildContentScoreBreakdown(
            parsed = parsed,
            averageBitrateMbps = averageBitrateMbps,
            resolutionTier = resolutionTier,
            releaseType = releaseType,
            codecTier = codecTier,
            device = device,
            runtimeKnown = hasRuntime
        )
        val contentScore = contentBreakdown.total()
        val transportBreakdown = manualTransportBreakdown(
            provider = provider,
            safeBudgetMbps = manualBitrateCap,
            requiredMbps = averageBitrateMbps
        )

        return EitherSuccessOrReject.success(
            ShadowStreamDecision(
                streamKey = item.shadowStreamKey(),
                parsed = item.shadowParsedFacts(request),
                provider = provider,
                transport = DebridBenchmarkTransportMode.DIRECT,
                finalScore = contentScore,
                contentQualityScore = contentScore,
                transportFitScore = 0,
                suitabilityRatio = if (averageBitrateMbps <= 0.0) 0.0 else manualBitrateCap / averageBitrateMbps,
                requiredMbps = averageBitrateMbps,
                safeBudgetMbps = manualBitrateCap,
                resolution = parsed.resolution,
                hdrTags = parsed.visualTags.filter { it in HDR_VISUAL_TAGS },
                audioTags = parsed.audioTags,
                breakdown = ShadowDecisionBreakdown(
                    averageBitrateMbps = averageBitrateMbps,
                    releaseType = releaseType.wireKey,
                    lowQuality4k = contentBreakdown.lowQuality4kPenalty < 0,
                    realismRatio = contentBreakdown.realismRatio,
                    content = contentBreakdown,
                    transport = transportBreakdown
                )
            )
        )
    }

    private fun bestTransportOption(
        provider: DebridBenchmarkProvider,
        benchmarkSession: DebridBenchmarkResult,
        requiredMbps: Double,
        activeTransportMode: DebridBenchmarkTransportMode?,
        movieMode: Boolean,
        showMode: Boolean
    ): ShadowTransportOption? {
        val requestedTransport = activeTransportMode
        val options = listOfNotNull(
            benchmarkSession.direct?.let {
                ShadowTransportOption.fromProfile(
                    config = config,
                    transport = DebridBenchmarkTransportMode.DIRECT,
                    profile = it,
                    requiredMbps = requiredMbps,
                    movieMode = movieMode,
                    showMode = showMode
                )
            },
            benchmarkSession.optimized?.let {
                ShadowTransportOption.fromProfile(
                    config = config,
                    transport = DebridBenchmarkTransportMode.OPTIMIZED,
                    profile = it,
                    requiredMbps = requiredMbps,
                    movieMode = movieMode,
                    showMode = showMode
                )
            }
        ).filter { option ->
            option.suitabilityRatio >= config.viability.minimumRatio &&
                (requestedTransport == null || option.transport == requestedTransport)
        }

        return options.sortedWith(shadowTransportOptionComparator(config)).firstOrNull()
    }

    private fun bestTransportOptionWithoutRuntime(
        benchmarkSession: DebridBenchmarkResult,
        activeTransportMode: DebridBenchmarkTransportMode?
    ): ShadowTransportOption? {
        val requestedTransport = activeTransportMode
        val options = listOfNotNull(
            benchmarkSession.direct?.let {
                ShadowTransportOption.fromProfileWithoutRuntime(
                    transport = DebridBenchmarkTransportMode.DIRECT,
                    profile = it
                )
            },
            benchmarkSession.optimized?.let {
                ShadowTransportOption.fromProfileWithoutRuntime(
                    transport = DebridBenchmarkTransportMode.OPTIMIZED,
                    profile = it
                )
            }
        ).filter { option ->
            requestedTransport == null || option.transport == requestedTransport
        }

        return options.sortedWith(shadowTransportOptionComparator(config)).firstOrNull()
    }

    private fun buildContentScoreBreakdown(
        parsed: ParsedStreamInfo,
        averageBitrateMbps: Double,
        resolutionTier: ShadowResolutionTier,
        releaseType: ShadowReleaseType,
        codecTier: ShadowVideoCodecTier,
        device: DeviceCapabilitySnapshot?,
        runtimeKnown: Boolean
    ): ShadowContentScoreBreakdown {
        val hdrPolicy = resolveHdrScoringPolicy(
            parsed = parsed,
            releaseType = releaseType,
            device = device
        )
        val hdrTier = hdrPolicy.effectiveHdrTier
        val hdrSupportTier = hdrPolicy.hdrSupportTier
        val audioDecision = resolveAudioScoringDecision(parsed.audioTags, device, releaseType)
        val audioTier = audioDecision.effectiveTier
        val audioSupportTier = audioDecision.supportStatus
        val codecPoints = config.contentRewards.codec.getValue(codecTier)
        val hdrPoints = if (hdrSupportTier == ShadowSupportLevel.UNSUPPORTED) {
            0
        } else {
            config.contentRewards.hdr.getValue(hdrTier)
        }
        val audioBasePoints = config.contentRewards.audio.getValue(audioDecision.effectiveTier)
        val audioPoints = if (audioDecision.supported) audioBasePoints else -audioBasePoints

        val releaseTypePoints = config.contentRewards.releaseType?.getOrDefault(releaseType, 0) ?: 0

        val resolutionPoints = config.contentRewards.resolution?.getOrDefault(resolutionTier, 0) ?: 0

        val isUhd = resolutionTier == ShadowResolutionTier.UHD_2160
        val hasHdr = hdrTier != ShadowHdrTier.SDR && hdrSupportTier != ShadowSupportLevel.UNSUPPORTED
        val hasPremiumAudio = audioDecision.supported && audioDecision.effectiveTier in PREMIUM_AUDIO_TIERS
        val hasEfficientCodec = codecTier == ShadowVideoCodecTier.HEVC_HW || codecTier == ShadowVideoCodecTier.AV1_HW

        var synergyPoints = 0
        if (isUhd && hasHdr && hasPremiumAudio) synergyPoints += 6
        else if (isUhd && hasHdr) synergyPoints += 3
        if (hasEfficientCodec && hasHdr) synergyPoints += 2

        var penaltyPoints = 0
        if (isUhd && hdrTier == ShadowHdrTier.SDR && !hasEfficientCodec) penaltyPoints -= 4

        val lowQuality4kPenalty = if (isUhd && releaseType in LOW_QUALITY_RELEASE_TYPES) -20 else 0

        return ShadowContentScoreBreakdown(
            resolutionPoints = resolutionPoints,
            audioPoints = audioPoints,
            hdrPoints = hdrPoints,
            codecPoints = codecPoints,
            releaseTypePoints = releaseTypePoints,
            bitrateQualityPoints = 0,
            synergyPoints = synergyPoints,
            penaltyPoints = penaltyPoints,
            lowQuality4kPenalty = lowQuality4kPenalty,
            resolutionTier = resolutionTier.name.lowercase(Locale.US),
            releaseTypeTier = releaseType.wireKey,
            codecTier = codecTier.name.lowercase(Locale.US),
            hdrTier = hdrTier.name.lowercase(Locale.US),
            audioTier = audioTier.name.lowercase(Locale.US),
            audioSupportTier = audioSupportTier,
            hdrSupportTier = hdrSupportTier.name.lowercase(Locale.US),
            realismRatio = if (runtimeKnown) 1.0 else 0.0
        )
    }

    private fun resolveHdrSupportLevel(
        hdrTier: ShadowHdrTier,
        device: DeviceCapabilitySnapshot?
    ): ShadowSupportLevel {
        val hdrTypes = device?.displayHdrTypes ?: return ShadowSupportLevel.FULL
        return when (hdrTier) {
            ShadowHdrTier.DOLBY_VISION -> if (DeviceHdrType.DOLBY_VISION in hdrTypes) ShadowSupportLevel.FULL else ShadowSupportLevel.UNSUPPORTED
            ShadowHdrTier.HDR10_PLUS -> if (DeviceHdrType.HDR10_PLUS in hdrTypes) ShadowSupportLevel.FULL else if (DeviceHdrType.HDR10 in hdrTypes) ShadowSupportLevel.PARTIAL else ShadowSupportLevel.UNSUPPORTED
            ShadowHdrTier.HDR10 -> if (DeviceHdrType.HDR10 in hdrTypes || DeviceHdrType.HDR10_PLUS in hdrTypes) ShadowSupportLevel.FULL else ShadowSupportLevel.UNSUPPORTED
            ShadowHdrTier.HLG -> if (DeviceHdrType.HLG in hdrTypes) ShadowSupportLevel.FULL else ShadowSupportLevel.UNSUPPORTED
            ShadowHdrTier.SDR -> ShadowSupportLevel.FULL
        }
    }

    private fun resolveHdrScoringPolicy(
        parsed: ParsedStreamInfo,
        releaseType: ShadowReleaseType,
        device: DeviceCapabilitySnapshot?
    ): ShadowHdrScoringPolicy {
        val originalHdrTier = resolveHdrTier(parsed.visualTags)
        val originalSupport = resolveHdrSupportLevel(originalHdrTier, device)
        return when (originalHdrTier) {
            ShadowHdrTier.DOLBY_VISION -> when {
                originalSupport == ShadowSupportLevel.FULL -> ShadowHdrScoringPolicy(
                    effectiveHdrTier = ShadowHdrTier.DOLBY_VISION,
                    hdrSupportTier = ShadowSupportLevel.FULL
                )
                resolveHdrSupportLevel(ShadowHdrTier.HDR10, device) == ShadowSupportLevel.FULL -> ShadowHdrScoringPolicy(
                    effectiveHdrTier = ShadowHdrTier.HDR10,
                    hdrSupportTier = ShadowSupportLevel.FALLBACK
                )
                else -> ShadowHdrScoringPolicy(
                    effectiveHdrTier = ShadowHdrTier.DOLBY_VISION,
                    hdrSupportTier = ShadowSupportLevel.UNSUPPORTED
                )
            }
            ShadowHdrTier.HDR10_PLUS -> when {
                originalSupport == ShadowSupportLevel.FULL -> ShadowHdrScoringPolicy(
                    effectiveHdrTier = ShadowHdrTier.HDR10_PLUS,
                    hdrSupportTier = ShadowSupportLevel.FULL
                )
                resolveHdrSupportLevel(ShadowHdrTier.HDR10, device) == ShadowSupportLevel.FULL -> ShadowHdrScoringPolicy(
                    effectiveHdrTier = ShadowHdrTier.HDR10,
                    hdrSupportTier = ShadowSupportLevel.FALLBACK
                )
                else -> ShadowHdrScoringPolicy(
                    effectiveHdrTier = ShadowHdrTier.HDR10_PLUS,
                    hdrSupportTier = ShadowSupportLevel.UNSUPPORTED
                )
            }
            else -> ShadowHdrScoringPolicy(
                effectiveHdrTier = originalHdrTier,
                hdrSupportTier = originalSupport
            )
        }
    }
}

private fun manualTransportBreakdown(
    provider: DebridBenchmarkProvider,
    safeBudgetMbps: Double,
    requiredMbps: Double
): ShadowTransportScoreBreakdown {
    return ShadowTransportScoreBreakdown(
        provider = provider,
        transport = DebridBenchmarkTransportMode.DIRECT,
        safeBudgetMbps = safeBudgetMbps,
        requiredMbps = requiredMbps,
        suitabilityRatio = if (requiredMbps <= 0.0) 0.0 else safeBudgetMbps / requiredMbps,
        ratioScore = 0,
        startupScore = 0,
        seekScore = 0,
        stabilityScore = 0,
        startupTtfbMs = null,
        seekTtfbP95Ms = null,
        seekFailRate = null
    )
}

private fun selectNonDolbyVisionFallback(
    selected: ShadowStreamDecision,
    ranked: List<ShadowStreamDecision>,
    initialPool: List<ShadowStreamDecision>,
    movieMode: Boolean,
    showMode: Boolean
): ShadowStreamDecision? {
    val seen = mutableSetOf<String>()
    val groupSize = when {
        initialPool.isNotEmpty() -> initialPool.size
        movieMode || showMode -> 3
        else -> 1
    }.coerceAtLeast(1)
    val groups = buildList {
        add(initialPool)
        val remaining = ranked.filterNot { candidate ->
            candidate.streamKey == selected.streamKey || initialPool.any { it.streamKey == candidate.streamKey }
        }
        if (movieMode || showMode) {
            addAll(
                remaining
                    .sortedByDescending { it.breakdown.averageBitrateMbps }
                    .chunked(groupSize)
            )
        } else {
            remaining.forEach { add(listOf(it)) }
        }
    }

    groups.forEach { group ->
        val fallback = group
            .filter { candidate ->
                seen.add(candidate.streamKey) &&
                    candidate.streamKey != selected.streamKey &&
                    !candidate.isDolbyVisionSource() &&
                    candidate.breakdown.content.audioSupportTier != "unsupported"
            }
            .maxWithOrNull(
                compareByDescending<ShadowStreamDecision> { it.breakdown.averageBitrateMbps }
                    .thenByDescending { it.finalScore }
                    .thenByDescending { it.suitabilityRatio }
            )
        if (fallback != null) return fallback
    }

    return null
}

private fun ShadowStreamDecision.isDolbyVisionSource(): Boolean {
    return parsed.visualTags.any { tag ->
        val normalized = tag.uppercase(Locale.US)
        normalized == "DV" || normalized.contains("DOLBY VISION") || normalized.contains("DOVI")
    }
}

private fun applyViableBitrateBucket(
    ranked: List<ShadowStreamDecision>
): List<ShadowStreamDecision> {
    if (ranked.isEmpty()) return ranked
    val highestViableBitrate = ranked.maxOfOrNull { it.breakdown.averageBitrateMbps } ?: return ranked
    val bitrateFloor = highestViableBitrate * VIABLE_BUCKET_FRACTION
    val bitrateBucket = ranked.filter { candidate ->
        candidate.breakdown.averageBitrateMbps >= bitrateFloor
    }
    val expandedBucket = if (bitrateBucket.size < VIABLE_BUCKET_MIN_STREAMS && ranked.size > bitrateBucket.size) {
        val sortedByBitrate = ranked.sortedByDescending { it.breakdown.averageBitrateMbps }
        sortedByBitrate.take(VIABLE_BUCKET_MIN_STREAMS.coerceAtMost(ranked.size))
    } else {
        bitrateBucket
    }
    val resolutionCollapsed = collapseResolutionBucket(expandedBucket)
    return resolutionCollapsed.sortedWith(baseDecisionComparator())
}

private fun collapseResolutionBucket(
    bucket: List<ShadowStreamDecision>
): List<ShadowStreamDecision> {
    if (bucket.isEmpty()) return bucket
    val qualifying4k = bucket.count { candidate ->
        movieResolutionPoolRank(candidate.breakdown.content.resolutionTier) == 3 &&
            candidate.breakdown.releaseType in setOf(
                ShadowReleaseType.WEBDL.wireKey,
                ShadowReleaseType.BLURAY_ENCODE.wireKey,
                ShadowReleaseType.REMUX.wireKey
            )
    }
    if (qualifying4k >= RESOLUTION_BUCKET_MIN_COUNT) {
        return bucket.filter { movieResolutionPoolRank(it.breakdown.content.resolutionTier) == 3 }
    }
    val qualifying1080 = bucket.count { movieResolutionPoolRank(it.breakdown.content.resolutionTier) == 2 }
    if (qualifying1080 >= RESOLUTION_BUCKET_MIN_COUNT) {
        return bucket.filter { movieResolutionPoolRank(it.breakdown.content.resolutionTier) >= 2 }
    }
    val qualifying720 = bucket.count { movieResolutionPoolRank(it.breakdown.content.resolutionTier) == 1 }
    if (qualifying720 >= RESOLUTION_BUCKET_MIN_COUNT) {
        return bucket.filter { movieResolutionPoolRank(it.breakdown.content.resolutionTier) >= 1 }
    }
    return bucket
}

private fun movieResolutionPoolRank(resolutionTier: String): Int {
    return when (resolutionTier.lowercase(Locale.US)) {
        "uhd_2160" -> 3
        "fhd_1080" -> 2
        "hd_720" -> 1
        else -> 0
    }
}

private fun baseDecisionComparator(): Comparator<ShadowStreamDecision> {
    return compareByDescending<ShadowStreamDecision> { it.finalScore }
        .thenByDescending { it.contentQualityScore }
        .thenByDescending { it.breakdown.averageBitrateMbps }
        .thenByDescending { it.safeBudgetMbps }
        .thenBy { it.breakdown.transport.startupTtfbMs ?: Long.MAX_VALUE }
        .thenBy { it.breakdown.transport.seekTtfbP95Ms ?: Long.MAX_VALUE }
        .thenBy { autoplaySessionTiebreakHash(it) }
}

private fun autoplaySessionTiebreakHash(decision: ShadowStreamDecision): Int {
    val key = decision.provider.storageKey + "|" + decision.streamKey
    return (key.hashCode().toLong() xor AUTOPLAY_SESSION_TIEBREAK_SEED).toInt()
}

private val AUTOPLAY_SESSION_TIEBREAK_SEED: Long =
    java.util.concurrent.ThreadLocalRandom.current().nextLong()

private data class ShadowTransportOption(
    val transport: DebridBenchmarkTransportMode,
    val safeBudgetMbps: Double,
    val suitabilityRatio: Double,
    val ratioScore: Int,
    val startupScore: Int,
    val seekScore: Int,
    val stabilityScore: Int,
    val startupTtfbMs: Long?,
    val seekTtfbP95Ms: Long?,
    val seekFailRate: Double?
) {
    fun totalScore(): Int = ratioScore + startupScore + seekScore + stabilityScore

    fun toBreakdown(
        provider: DebridBenchmarkProvider,
        requiredMbps: Double
    ): ShadowTransportScoreBreakdown {
        return ShadowTransportScoreBreakdown(
            provider = provider,
            transport = transport,
            safeBudgetMbps = safeBudgetMbps,
            requiredMbps = requiredMbps,
            suitabilityRatio = suitabilityRatio,
            ratioScore = ratioScore,
            startupScore = startupScore,
            seekScore = seekScore,
            stabilityScore = stabilityScore,
            startupTtfbMs = startupTtfbMs,
            seekTtfbP95Ms = seekTtfbP95Ms,
            seekFailRate = seekFailRate
        )
    }

    companion object {
        fun fromProfile(
            config: BenchmarkAwareStreamScoringConfig,
            transport: DebridBenchmarkTransportMode,
            profile: DebridBenchmarkTransportProfile,
            requiredMbps: Double,
            movieMode: Boolean,
            showMode: Boolean
        ): ShadowTransportOption? {
            val safeBudgetMbps = profile.safeSustainedBudgetMbps() ?: return null
            val suitabilityRatio = if (requiredMbps <= 0.0) Double.POSITIVE_INFINITY else safeBudgetMbps / requiredMbps
            return ShadowTransportOption(
                transport = transport,
                safeBudgetMbps = safeBudgetMbps,
                suitabilityRatio = suitabilityRatio,
                ratioScore = ratioScore(config, suitabilityRatio, movieMode, showMode),
                startupScore = 0,
                seekScore = 0,
                stabilityScore = 0,
                startupTtfbMs = profile.startup.initialTtfbMs,
                seekTtfbP95Ms = profile.seek.seekTtfbP95Ms,
                seekFailRate = profile.seek.seekFailRate
            )
        }

        fun fromProfileWithoutRuntime(
            transport: DebridBenchmarkTransportMode,
            profile: DebridBenchmarkTransportProfile
        ): ShadowTransportOption? {
            val safeBudgetMbps = profile.safeSustainedBudgetMbps() ?: return null
            return ShadowTransportOption(
                transport = transport,
                safeBudgetMbps = safeBudgetMbps,
                suitabilityRatio = 0.0,
                ratioScore = 0,
                startupScore = 0,
                seekScore = 0,
                stabilityScore = 0,
                startupTtfbMs = profile.startup.initialTtfbMs,
                seekTtfbP95Ms = profile.seek.seekTtfbP95Ms,
                seekFailRate = profile.seek.seekFailRate
            )
        }
    }
}

private data class ShadowHdrScoringPolicy(
    val effectiveHdrTier: ShadowHdrTier,
    val hdrSupportTier: ShadowSupportLevel,
    val additionalPenalty: Int = 0
)

internal data class ShadowAudioScoringDecision(
    val effectiveTier: ShadowAudioTier,
    val supported: Boolean,
    val supportStatus: String
)

private data class EitherSuccessOrReject<T>(
    val success: T? = null,
    val rejectReasons: List<ShadowRejectReason> = emptyList()
) {
    fun fold(
        onSuccess: (T) -> Unit,
        onFailure: (List<ShadowRejectReason>) -> Unit
    ) {
        success?.let(onSuccess) ?: onFailure(rejectReasons)
    }

    companion object {
        fun <T> success(value: T): EitherSuccessOrReject<T> = EitherSuccessOrReject(success = value)

        fun <T> reject(vararg reasons: ShadowRejectReason): EitherSuccessOrReject<T> {
            return EitherSuccessOrReject(rejectReasons = reasons.toList())
        }
    }
}

private fun ShadowContentScoreBreakdown.total(): Int {
    return resolutionPoints +
        audioPoints +
        hdrPoints +
        codecPoints +
        releaseTypePoints +
        bitrateQualityPoints +
        synergyPoints +
        penaltyPoints +
        lowQuality4kPenalty
}

internal fun resolveAudioScoringDecision(
    tags: List<String>,
    device: DeviceCapabilitySnapshot?,
    releaseType: ShadowReleaseType
): ShadowAudioScoringDecision {
    val candidates = detectAudioTierCandidates(tags, releaseType)
    if (candidates.isEmpty()) {
        return ShadowAudioScoringDecision(
            effectiveTier = ShadowAudioTier.OTHER,
            supported = true,
            supportStatus = "supported"
        )
    }

    val supportedCandidate = candidates.firstOrNull { audioTierSupported(it, device) }
    if (supportedCandidate != null) {
        return ShadowAudioScoringDecision(
            effectiveTier = supportedCandidate,
            supported = true,
            supportStatus = "supported"
        )
    }

    // No supported tier found. Penalize by the lowest base-point tier so the negative
    // score is the least severe viable fallback for this detected audio family.
    return ShadowAudioScoringDecision(
        effectiveTier = candidates.minByOrNull(::audioBasePoints) ?: ShadowAudioTier.OTHER,
        supported = false,
        supportStatus = "unsupported"
    )
}

private fun detectAudioTierCandidates(
    tags: List<String>,
    releaseType: ShadowReleaseType
): List<ShadowAudioTier> {
    val normalized = tags.map { it.lowercase(Locale.US) }
    val hasAtmos = normalized.any { it.contains("atmos") }
    val hasTrueHd = normalized.any { it.contains("truehd") }
    val hasDtsX = normalized.any { it.contains("dts:x") || it.contains("dtsx") }
    val hasDtsHd = normalized.any { it.contains("dts-hd") }
    val hasDdp = normalized.any { it.contains("dd+") || it.contains("eac3") || it.contains("ddp") }
    val hasAc3 = normalized.any { it == "dd" || it.contains("ac3") }
    val hasDts = normalized.any { it == "dts" }

    // Source-format-aware Atmos disambiguation. Explicit container co-tags override the
    // release-type heuristic because parsed title metadata is more specific than source class.
    val isLosslessReleaseType = releaseType == ShadowReleaseType.REMUX ||
        releaseType == ShadowReleaseType.BLURAY_ENCODE

    return buildList {
        // Atmos cases are exhaustive when hasAtmos is true; otherwise control falls
        // through to the DTS and non-Atmos blocks below.
        when {
            hasAtmos && hasTrueHd -> {
                add(ShadowAudioTier.TRUEHD_ATMOS)
                add(ShadowAudioTier.TRUEHD)
            }
            hasAtmos && hasDdp -> {
                add(ShadowAudioTier.DDP_ATMOS)
                add(ShadowAudioTier.DDP)
            }
            hasAtmos && isLosslessReleaseType -> {
                add(ShadowAudioTier.TRUEHD_ATMOS)
                add(ShadowAudioTier.TRUEHD)
            }
            hasAtmos -> {
                // Default WEB-DL/WEBRIP/encode/unknown Atmos to the DDP ladder.
                add(ShadowAudioTier.DDP_ATMOS)
                add(ShadowAudioTier.DDP)
            }
        }

        // DTS family keeps a real fallback ladder without inventing AC3 fallbacks.
        if (hasDtsX) {
            add(ShadowAudioTier.DTSX)
            add(ShadowAudioTier.DTSHD)
            add(ShadowAudioTier.DTS)
        } else if (hasDtsHd) {
            add(ShadowAudioTier.DTSHD)
            add(ShadowAudioTier.DTS)
        }

        if (hasTrueHd && !hasAtmos) add(ShadowAudioTier.TRUEHD)
        if (hasDdp && !hasAtmos) add(ShadowAudioTier.DDP)
        if (hasAc3) add(ShadowAudioTier.AC3)
        if (hasDts && !hasDtsHd && !hasDtsX) add(ShadowAudioTier.DTS)
    }.distinct()
}

private fun audioTierSupported(
    tier: ShadowAudioTier,
    device: DeviceCapabilitySnapshot?
): Boolean {
    val output = device?.audioOutput ?: return true
    return when (tier) {
        ShadowAudioTier.TRUEHD_ATMOS -> output.atmos.passthroughLikely
        ShadowAudioTier.DTSX -> output.dtsx.passthroughLikely
        ShadowAudioTier.TRUEHD -> output.truehd.passthroughLikely
        ShadowAudioTier.DTSHD -> output.dtshd.passthroughLikely
        ShadowAudioTier.DDP_ATMOS -> output.atmos.passthroughLikely
        ShadowAudioTier.DDP -> output.eac3.passthroughLikely
        ShadowAudioTier.AC3 -> output.ac3.passthroughLikely
        ShadowAudioTier.DTS -> output.dts.passthroughLikely
        ShadowAudioTier.OTHER -> true
    }
}

private fun audioBasePoints(tier: ShadowAudioTier): Int {
    return when (tier) {
        ShadowAudioTier.TRUEHD_ATMOS,
        ShadowAudioTier.DTSX,
        ShadowAudioTier.DDP_ATMOS -> 16
        ShadowAudioTier.TRUEHD,
        ShadowAudioTier.DTSHD -> 12
        ShadowAudioTier.DDP -> 10
        ShadowAudioTier.AC3,
        ShadowAudioTier.DTS -> 7
        ShadowAudioTier.OTHER -> 0
    }
}


private fun StreamCardModel.toBenchmarkProvider(): DebridBenchmarkProvider? {
    return when (shadowServiceId()) {
        "RD" -> DebridBenchmarkProvider.REAL_DEBRID
        "PM" -> DebridBenchmarkProvider.PREMIUMIZE
        "TB" -> DebridBenchmarkProvider.TORBOX
        "ED" -> DebridBenchmarkProvider.EASY_DEBRID
        else -> null
    }
}

private fun StreamCardModel.shadowServiceId(): String? {
    return parsed.serviceId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.uppercase(Locale.US)
        ?: stream.wrappedProviderId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.US)
}

private fun StreamCardModel.shadowRuntimeMs(request: ShadowRequestContext): Long? {
    return parsed.durationMs ?: request.runtimeMinutes?.times(60_000L)
}

private fun StreamCardModel.shadowRuntimeSource(request: ShadowRequestContext): String? {
    return when {
        parsed.durationMs != null -> "parser"
        request.runtimeMinutes != null -> "metadata"
        else -> null
    }
}

private fun StreamCardModel.shadowFilename(): String? {
    return parsed.preservedMetadata.filename
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: parsed.filename
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        ?: stream.behaviorHints?.filename
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}

private fun StreamCardModel.shadowParsedFacts(request: ShadowRequestContext): ShadowParsedStreamFacts {
    return ShadowParsedStreamFacts(
        serviceId = shadowServiceId(),
        filename = shadowFilename(),
        folderName = parsed.folderName?.trim()?.takeIf { it.isNotBlank() },
        sizeBytes = effectiveSizeBytes(),
        durationMs = shadowRuntimeMs(request),
        runtimeSource = shadowRuntimeSource(request),
        resolution = parsed.resolution,
        quality = parsed.quality,
        videoCodec = parsed.encode,
        audioTags = parsed.audioTags,
        audioChannels = parsed.audioChannels,
        visualTags = parsed.visualTags,
        languages = parsed.languages,
        releaseGroup = parsed.releaseGroup,
        cached = parsed.isCached
    )
}

private fun StreamCardModel.effectiveSizeBytes(): Long? {
    return parsed.sizeBytes?.takeIf { it > 0L }
        ?: parsed.preservedMetadata.videoSize?.takeIf { it > 0L }
        ?: stream.behaviorHints?.videoSize?.takeIf { it > 0L }
}

private fun StreamCardModel.shadowStreamKey(): String {
    val baseKey = stream.wrappedOriginalStreamKey
        ?: parsed.exactDuplicateKey
    val serviceId = parsed.serviceId ?: return baseKey
    return "$baseKey|$serviceId"
}

private fun calculateAverageBitrateMbps(sizeBytes: Long, runtimeMs: Long): Double {
    val runtimeSeconds = runtimeMs / 1_000.0
    return (sizeBytes * 8.0) / runtimeSeconds / 1_000_000.0
}

private fun classifyReleaseType(
    parsed: ParsedStreamInfo,
    averageBitrateMbps: Double
): ShadowReleaseType {
    val quality = parsed.quality.orEmpty().lowercase(Locale.US)
    return when {
        quality.contains("remux") -> ShadowReleaseType.REMUX
        quality.contains("blu") -> ShadowReleaseType.BLURAY_ENCODE
        quality.contains("web-dl") || quality.contains("webdl") -> ShadowReleaseType.WEBDL
        quality.contains("webrip") || quality.contains("web-rip") -> ShadowReleaseType.WEBRIP
        quality.contains("hdtv") || quality.contains("tv-rip") || quality.contains("dsr") || quality.contains("sat-rip") -> ShadowReleaseType.HDTV
        quality.contains("hdrip") || quality.contains("hd-rip") -> ShadowReleaseType.HDRIP
        quality.contains("scr") -> ShadowReleaseType.SCREENER
        quality == "cam" || quality.contains("camrip") || quality.contains("hdcam") -> ShadowReleaseType.CAM
        quality == "ts" || quality.contains("telesync") || quality.contains("hdts") || quality.contains("pdvd") -> ShadowReleaseType.TELESYNC
        quality == "tc" || quality.contains("telecine") || quality.contains("hdtc") -> ShadowReleaseType.TELECINE
        averageBitrateMbps >= 30.0 -> ShadowReleaseType.HIGH_BITRATE_ENCODE
        averageBitrateMbps >= 10.0 -> ShadowReleaseType.NORMAL_ENCODE
        averageBitrateMbps > 0.0 -> ShadowReleaseType.SMALL_ENCODE
        else -> ShadowReleaseType.UNKNOWN
    }
}

private val ShadowReleaseType.wireKey: String
    get() = name.lowercase(Locale.US)

private fun resolveResolutionTier(resolution: String?): ShadowResolutionTier {
    return when (resolution?.lowercase(Locale.US)) {
        "2160p" -> ShadowResolutionTier.UHD_2160
        "1080p" -> ShadowResolutionTier.FHD_1080
        "720p" -> ShadowResolutionTier.HD_720
        else -> ShadowResolutionTier.OTHER
    }
}

private fun resolveHdrTier(tags: List<String>): ShadowHdrTier {
    val normalized = tags.map { it.uppercase(Locale.US) }
    return when {
        normalized.any { it == "DV" || it.contains("DOLBY VISION") || it.contains("DOVI") } -> ShadowHdrTier.DOLBY_VISION
        normalized.any { it == "HDR10+" } -> ShadowHdrTier.HDR10_PLUS
        normalized.any { it == "HDR10" || it == "HDR" } -> ShadowHdrTier.HDR10
        normalized.any { it == "HLG" } -> ShadowHdrTier.HLG
        else -> ShadowHdrTier.SDR
    }
}

private fun resolveAudioTier(tags: List<String>): ShadowAudioTier {
    val normalized = tags.map { it.lowercase(Locale.US) }
    return when {
        normalized.any { it.contains("atmos") } && normalized.any { it.contains("truehd") } -> ShadowAudioTier.TRUEHD_ATMOS
        normalized.any { it.contains("dts:x") || it.contains("dtsx") } -> ShadowAudioTier.DTSX
        normalized.any { it.contains("truehd") } -> ShadowAudioTier.TRUEHD
        normalized.any { it.contains("dts-hd") } -> ShadowAudioTier.DTSHD
        normalized.any { it.contains("atmos") } && normalized.any { it.contains("dd+") || it.contains("eac3") || it.contains("ddp") } -> ShadowAudioTier.DDP_ATMOS
        normalized.any { it.contains("dd+") || it.contains("eac3") || it.contains("ddp") } -> ShadowAudioTier.DDP
        normalized.any { it == "dts" } -> ShadowAudioTier.DTS
        normalized.any { it == "dd" || it.contains("ac3") } -> ShadowAudioTier.AC3
        else -> ShadowAudioTier.OTHER
    }
}

private fun resolveVideoCodecTier(
    encode: String?,
    device: DeviceCapabilitySnapshot?
): ShadowVideoCodecTier {
    val normalized = encode.orEmpty().lowercase(Locale.US)
    if (normalized.contains("vc1") || normalized.contains("vc-1") || normalized.contains("wvc1")) {
        return ShadowVideoCodecTier.VC1
    }
    if (normalized.contains("mpeg2") || normalized.contains("mpeg-2")) {
        return ShadowVideoCodecTier.MPEG2
    }
    return when {
        normalized.contains("av1") -> when {
            device == null -> ShadowVideoCodecTier.AV1_HW
            device.videoDecode.av1?.hardwareAccelerated == true -> ShadowVideoCodecTier.AV1_HW
            device.videoDecode.av1?.softwareOnlyAvailable == true -> ShadowVideoCodecTier.AV1_SW
            else -> ShadowVideoCodecTier.UNSUPPORTED
        }
        normalized.contains("hevc") || normalized.contains("h265") || normalized.contains("x265") -> when {
            device == null -> ShadowVideoCodecTier.HEVC_HW
            device.videoDecode.hevc?.hardwareAccelerated == true -> ShadowVideoCodecTier.HEVC_HW
            device.videoDecode.hevc?.softwareOnlyAvailable == true -> ShadowVideoCodecTier.HEVC_SW
            else -> ShadowVideoCodecTier.UNSUPPORTED
        }
        normalized.contains("h264") || normalized.contains("x264") || normalized.contains("avc") -> when {
            device == null -> ShadowVideoCodecTier.H264_HW
            device.videoDecode.h264?.hardwareAccelerated == true -> ShadowVideoCodecTier.H264_HW
            else -> ShadowVideoCodecTier.UNSUPPORTED
        }
        normalized.isBlank() -> ShadowVideoCodecTier.OTHER
        else -> ShadowVideoCodecTier.OTHER
    }
}

private fun ratioScore(
    config: BenchmarkAwareStreamScoringConfig,
    ratio: Double,
    movieMode: Boolean,
    showMode: Boolean
): Int {
    val band = config.transportRewards.ratioBands.firstOrNull { ratio >= it.min && ratio < it.max }
        ?: config.transportRewards.ratioBands.lastOrNull()?.takeIf { ratio >= it.min }
        ?: return 0
    if (!ratio.isFinite()) return config.transportRewards.ratioBands.lastOrNull()?.base ?: 0
    if (band.gain == 0) return band.base
    val normalized = ((ratio - band.min) / (band.max - band.min)).coerceIn(0.0, 1.0)
    return band.base + (band.gain * normalized).roundToInt()
}

private fun shadowTransportOptionComparator(
    config: BenchmarkAwareStreamScoringConfig
): Comparator<ShadowTransportOption> {
    return compareByDescending<ShadowTransportOption> { it.totalScore() }
        .thenByDescending { it.safeBudgetMbps }
        .thenBy { it.startupTtfbMs ?: Long.MAX_VALUE }
        .thenBy { it.seekTtfbP95Ms ?: Long.MAX_VALUE }
        .thenBy { it.seekFailRate ?: Double.MAX_VALUE }
}

private val HDR_VISUAL_TAGS = setOf("DV", "HDR", "HDR10", "HDR10+", "HLG")
private const val VIABLE_BUCKET_FRACTION = 0.70
private const val VIABLE_BUCKET_MIN_STREAMS = 10
private const val RESOLUTION_BUCKET_MIN_COUNT = 3

private val PREMIUM_AUDIO_TIERS = setOf(
    ShadowAudioTier.TRUEHD_ATMOS,
    ShadowAudioTier.DTSX,
    ShadowAudioTier.DDP_ATMOS,
    ShadowAudioTier.TRUEHD,
    ShadowAudioTier.DTSHD
)

private val LOW_QUALITY_RELEASE_TYPES = setOf(
    ShadowReleaseType.CAM,
    ShadowReleaseType.TELESYNC,
    ShadowReleaseType.TELECINE,
    ShadowReleaseType.SCREENER
)

private val HARD_REJECT_RELEASE_TYPES = setOf(
    ShadowReleaseType.CAM,
    ShadowReleaseType.TELESYNC,
    ShadowReleaseType.TELECINE
)

private fun ShadowRequestContext.isMovieLike(): Boolean {
    return contentType.equals("movie", ignoreCase = true)
}

private fun ShadowRequestContext.isSeriesLike(): Boolean {
    return contentType.equals("series", ignoreCase = true)
}
