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
    MISSING_RUNTIME,
    UNSUPPORTED_CODEC,
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
        elapsedMs: Long? = null
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

        val ranked = winners.sortedWith(baseDecisionComparator(movieMode, showMode))
        val finalRanked = when {
            movieMode -> preferSaferMovieCandidate(applyMovieCandidatePool(ranked))
            showMode -> applyShowCandidatePool(ranked)
            else -> ranked
        }

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

    private fun evaluateStream(
        item: StreamCardModel,
        provider: DebridBenchmarkProvider,
        benchmarkSession: DebridBenchmarkResult,
        request: ShadowRequestContext,
        activeTransportMode: DebridBenchmarkTransportMode?,
        movieMode: Boolean,
        showMode: Boolean
    ): EitherSuccessOrReject<ShadowStreamDecision> {
        val parsed = item.parsed
        val sizeBytes = parsed.sizeBytes
        if (sizeBytes == null || sizeBytes <= 0L) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.MISSING_SIZE)
        }
        val runtimeMs = item.shadowRuntimeMs(request)
        val hasRuntime = runtimeMs != null && runtimeMs > 0L
        val averageBitrateMbps = runtimeMs?.takeIf { it > 0L }?.let {
            calculateAverageBitrateMbps(sizeBytes, it)
        } ?: 0.0
        val resolutionTier = resolveResolutionTier(parsed.resolution)
        val releaseType = classifyReleaseType(parsed, averageBitrateMbps)
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
        val codecTier = resolveVideoCodecTier(parsed.encode, device)
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

    private fun bestTransportOption(
        provider: DebridBenchmarkProvider,
        benchmarkSession: DebridBenchmarkResult,
        requiredMbps: Double,
        activeTransportMode: DebridBenchmarkTransportMode?,
        movieMode: Boolean,
        showMode: Boolean
    ): ShadowTransportOption? {
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
            option.suitabilityRatio >= when {
                movieMode -> MOVIE_MINIMUM_RATIO
                showMode -> SHOW_MINIMUM_RATIO
                else -> config.viability.minimumRatio
            } &&
                (activeTransportMode == null || option.transport == activeTransportMode)
        }

        return options.sortedWith(shadowTransportOptionComparator(config)).firstOrNull()
    }

    private fun bestTransportOptionWithoutRuntime(
        benchmarkSession: DebridBenchmarkResult,
        activeTransportMode: DebridBenchmarkTransportMode?
    ): ShadowTransportOption? {
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
            activeTransportMode == null || option.transport == activeTransportMode
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
        val audioTier = resolveAudioTier(parsed.audioTags)
        val audioSupportTier = resolveAudioSupportTier(audioTier, device)
        val realismRatio = if (runtimeKnown) {
            bitrateRealismRatio(
                resolutionTier = resolutionTier,
                releaseType = releaseType,
                averageBitrateMbps = averageBitrateMbps
            )
        } else {
            1.0
        }
        val lowQuality4kPenalty = if (
            runtimeKnown &&
            resolutionTier == ShadowResolutionTier.UHD_2160 &&
            averageBitrateMbps < config.penalties.tinyFake4kThresholdMbps
        ) {
            -config.penalties.tinyFake4k
        } else {
            0
        }

        val resolutionPoints = config.contentRewards.resolution.getValue(resolutionTier)
        val releaseTypePoints = config.contentRewards.source.getValue(releaseType)
        val codecPoints = config.contentRewards.codec.getValue(codecTier)
        val hdrPoints = config.supportMultipliers.apply(
            points = config.contentRewards.hdr.getValue(hdrTier),
            supportLevel = hdrSupportTier
        )
        val audioPoints = scoreAudio(audioTier, audioSupportTier)
        val bitrateQualityPoints = if (runtimeKnown) {
            bitrateRealismReward(
                resolutionTier = resolutionTier,
                releaseType = releaseType,
                averageBitrateMbps = averageBitrateMbps
            )
        } else {
            0
        }
        val synergyPoints = synergyPoints(
            releaseType = releaseType,
            codecTier = codecTier,
            hdrTier = hdrTier,
            hdrSupportTier = hdrSupportTier,
            audioTier = audioTier,
            audioSupportTier = audioSupportTier,
            realismRatio = realismRatio,
            runtimeKnown = runtimeKnown
        )
        val penaltyPoints = lowQuality4kPenalty + hdrPolicy.additionalPenalty + additionalPenaltyPoints(
            resolutionTier = resolutionTier,
            releaseType = releaseType,
            codecTier = codecTier,
            hdrTier = hdrTier,
            audioTier = audioTier,
            realismRatio = realismRatio,
            runtimeKnown = runtimeKnown
        )

        return ShadowContentScoreBreakdown(
            resolutionPoints = resolutionPoints,
            audioPoints = audioPoints,
            hdrPoints = hdrPoints,
            codecPoints = codecPoints,
            releaseTypePoints = releaseTypePoints,
            bitrateQualityPoints = bitrateQualityPoints,
            synergyPoints = synergyPoints,
            penaltyPoints = penaltyPoints,
            lowQuality4kPenalty = lowQuality4kPenalty,
            resolutionTier = resolutionTier.name.lowercase(Locale.US),
            releaseTypeTier = releaseType.wireKey,
            codecTier = codecTier.name.lowercase(Locale.US),
            hdrTier = hdrTier.name.lowercase(Locale.US),
            audioTier = audioTier.name.lowercase(Locale.US),
            audioSupportTier = audioSupportTier.name.lowercase(Locale.US),
            hdrSupportTier = hdrSupportTier.name.lowercase(Locale.US),
            realismRatio = realismRatio
        )
    }

    private fun scoreAudio(
        audioTier: ShadowAudioTier,
        supportTier: ShadowAudioSupportTier
    ): Int {
        val base = config.audioScoring.baseRewards.getValue(audioTier)
        val multiplier = config.audioScoring.supportMultipliers.getValue(supportTier)
        val immersiveLoss = config.audioScoring.immersiveLossPenalty[audioTier]?.get(supportTier) ?: 0
        val downgradePenalty = config.audioScoring.downgradePenalty.getValue(supportTier)
        return ((base * multiplier) - immersiveLoss - downgradePenalty).roundToInt()
    }

    private fun synergyPoints(
        releaseType: ShadowReleaseType,
        codecTier: ShadowVideoCodecTier,
        hdrTier: ShadowHdrTier,
        hdrSupportTier: ShadowSupportLevel,
        audioTier: ShadowAudioTier,
        audioSupportTier: ShadowAudioSupportTier,
        realismRatio: Double,
        runtimeKnown: Boolean
    ): Int {
        var reward = 0
        if (runtimeKnown && releaseType == ShadowReleaseType.REMUX && realismRatio >= 0.85) {
            reward += config.synergy.healthyRemux
        }
        if (hdrTier == ShadowHdrTier.DOLBY_VISION &&
            codecTier == ShadowVideoCodecTier.HEVC_HW &&
            hdrSupportTier == ShadowSupportLevel.FULL
        ) {
            reward += config.synergy.dvHevcSupported
        }
        if (audioTier in IMMERSIVE_AUDIO_TIERS &&
            audioSupportTier != ShadowAudioSupportTier.UNSUPPORTED &&
            runtimeKnown &&
            realismRatio >= 0.80
        ) {
            reward += config.synergy.atmosSupportedAndHealthy
        }
        if (releaseType in PREMIUM_RELEASE_TYPES &&
            hdrTier != ShadowHdrTier.SDR &&
            hdrSupportTier != ShadowSupportLevel.UNSUPPORTED &&
            audioTier in PREMIUM_AUDIO_TIERS &&
            runtimeKnown &&
            realismRatio >= 0.85
        ) {
            reward += config.synergy.premiumFeatureStack
        }
        return reward
    }

    private fun additionalPenaltyPoints(
        resolutionTier: ShadowResolutionTier,
        releaseType: ShadowReleaseType,
        codecTier: ShadowVideoCodecTier,
        hdrTier: ShadowHdrTier,
        audioTier: ShadowAudioTier,
        realismRatio: Double,
        runtimeKnown: Boolean
    ): Int {
        var penalty = 0
        val hasPremiumTags =
            releaseType in PREMIUM_RELEASE_TYPES || hdrTier != ShadowHdrTier.SDR || audioTier in PREMIUM_AUDIO_TIERS
        if (runtimeKnown && hasPremiumTags && realismRatio <= config.penalties.premiumTagImplausibleMaxRealismRatio) {
            penalty -= config.penalties.premiumTagImplausible
        }
        if (resolutionTier == ShadowResolutionTier.UHD_2160 &&
            codecTier in SOFTWARE_4K_CODEC_TIERS
        ) {
            penalty -= config.penalties.softwareDecode4k
        }
        if (audioTier in IMMERSIVE_AUDIO_TIERS &&
            runtimeKnown &&
            realismRatio <= config.penalties.atmosTooLowBitrateMaxRealismRatio
        ) {
            penalty -= config.penalties.atmosTooLowBitrate
        }
        return penalty
    }

    private fun bitrateRealismRatio(
        resolutionTier: ShadowResolutionTier,
        releaseType: ShadowReleaseType,
        averageBitrateMbps: Double
    ): Double {
        val targetsByRelease =
            config.bitrateRealism.targetsMbps[resolutionTier]
                ?: config.bitrateRealism.targetsMbps.getValue(ShadowResolutionTier.OTHER)
        val target =
            targetsByRelease[releaseType]
                ?: targetsByRelease[ShadowReleaseType.UNKNOWN]
                ?: 1.0
        if (target <= 0.0 || averageBitrateMbps <= 0.0) return 0.0
        return averageBitrateMbps / target
    }

    private fun bitrateRealismReward(
        resolutionTier: ShadowResolutionTier,
        releaseType: ShadowReleaseType,
        averageBitrateMbps: Double
    ): Int {
        val realismRatio = bitrateRealismRatio(resolutionTier, releaseType, averageBitrateMbps)
        if (realismRatio <= 0.0) return config.bitrateRealism.curve.min.roundToInt()
        val raw = config.bitrateRealism.curve.scale *
            tanh(config.bitrateRealism.curve.slope * ln(realismRatio))
        return raw.coerceIn(
            config.bitrateRealism.curve.min,
            config.bitrateRealism.curve.max
        ).roundToInt()
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
        if (originalHdrTier != ShadowHdrTier.DOLBY_VISION ||
            originalSupport != ShadowSupportLevel.UNSUPPORTED
        ) {
            return ShadowHdrScoringPolicy(
                effectiveHdrTier = originalHdrTier,
                hdrSupportTier = originalSupport
            )
        }

        return ShadowHdrScoringPolicy(
            effectiveHdrTier = ShadowHdrTier.HDR10,
            hdrSupportTier = resolveHdrSupportLevel(ShadowHdrTier.HDR10, device)
        )
    }

    private fun resolveAudioSupportTier(
        audioTier: ShadowAudioTier,
        device: DeviceCapabilitySnapshot?
    ): ShadowAudioSupportTier {
        val output = device?.audioOutput ?: return ShadowAudioSupportTier.FULL_IMMERSIVE_PASSTHROUGH
        return when (audioTier) {
            ShadowAudioTier.TRUEHD_ATMOS -> when {
                output.truehd.passthroughLikely -> ShadowAudioSupportTier.FULL_IMMERSIVE_PASSTHROUGH
                output.truehd.supported -> ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM
                else -> ShadowAudioSupportTier.UNSUPPORTED
            }
            ShadowAudioTier.DTSX -> when {
                output.dtshd.passthroughLikely -> ShadowAudioSupportTier.FULL_IMMERSIVE_PASSTHROUGH
                output.dts.passthroughLikely -> ShadowAudioSupportTier.CORE_FALLBACK
                output.dtshd.supported || output.dts.supported -> ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM
                else -> ShadowAudioSupportTier.UNSUPPORTED
            }
            ShadowAudioTier.TRUEHD -> when {
                output.truehd.passthroughLikely -> ShadowAudioSupportTier.FULL_PASSTHROUGH
                output.truehd.supported -> ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM
                else -> ShadowAudioSupportTier.UNSUPPORTED
            }
            ShadowAudioTier.DTSHD -> when {
                output.dtshd.passthroughLikely -> ShadowAudioSupportTier.FULL_PASSTHROUGH
                output.dts.passthroughLikely -> ShadowAudioSupportTier.CORE_FALLBACK
                output.dtshd.supported || output.dts.supported -> ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM
                else -> ShadowAudioSupportTier.UNSUPPORTED
            }
            ShadowAudioTier.DDP_ATMOS -> when {
                output.eac3.passthroughLikely -> ShadowAudioSupportTier.FULL_IMMERSIVE_PASSTHROUGH
                output.eac3.supported -> ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM
                else -> ShadowAudioSupportTier.UNSUPPORTED
            }
            ShadowAudioTier.DDP -> when {
                output.eac3.passthroughLikely -> ShadowAudioSupportTier.FULL_PASSTHROUGH
                output.eac3.supported -> ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM
                else -> ShadowAudioSupportTier.UNSUPPORTED
            }
            ShadowAudioTier.AC3 -> when {
                output.ac3.passthroughLikely -> ShadowAudioSupportTier.FULL_PASSTHROUGH
                output.ac3.supported -> ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM
                else -> ShadowAudioSupportTier.UNSUPPORTED
            }
            ShadowAudioTier.DTS -> when {
                output.dts.passthroughLikely -> ShadowAudioSupportTier.FULL_PASSTHROUGH
                output.dts.supported -> ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM
                else -> ShadowAudioSupportTier.UNSUPPORTED
            }
            ShadowAudioTier.OTHER -> ShadowAudioSupportTier.UNSUPPORTED
        }
    }
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

private fun applyMovieCandidatePool(
    ranked: List<ShadowStreamDecision>
): List<ShadowStreamDecision> {
    if (ranked.isEmpty()) return ranked
    val highestTier = ranked
        .maxOfOrNull { movieResolutionPoolRank(it.breakdown.content.resolutionTier) }
        ?: return ranked
    val sameTier = ranked.filter { movieResolutionPoolRank(it.breakdown.content.resolutionTier) == highestTier }
    val sourcePool = when {
        sameTier.any { !it.breakdown.lowQuality4k } -> sameTier
        highestTier <= 0 -> sameTier
        else -> ranked.filter { movieResolutionPoolRank(it.breakdown.content.resolutionTier) == highestTier - 1 }
            .ifEmpty { sameTier }
    }
    if (sourcePool.size <= MOVIE_POOL_MIN_CANDIDATES) {
        return sourcePool
    }
    val targetCount = maxOf(MOVIE_POOL_MIN_CANDIDATES, kotlin.math.ceil(sourcePool.size * MOVIE_POOL_PERCENTILE).toInt())
    return sourcePool
        .sortedByDescending { it.breakdown.averageBitrateMbps }
        .take(targetCount.coerceAtMost(sourcePool.size))
        .sortedWith(baseDecisionComparator(movieMode = true, showMode = false))
}

private fun preferSaferMovieCandidate(
    ranked: List<ShadowStreamDecision>
): List<ShadowStreamDecision> {
    val selected = ranked.firstOrNull() ?: return ranked
    if (selected.suitabilityRatio >= MOVIE_SAFETY_OVERRIDE_SELECTED_MAX_RATIO) {
        return ranked
    }
    val selectedHeadroomMbps = selected.safeBudgetMbps - selected.requiredMbps
    val saferCandidate = ranked
        .drop(1)
        .filter { challenger ->
            challenger.finalScore >= selected.finalScore - MOVIE_SAFETY_OVERRIDE_MAX_SCORE_GAP &&
                challenger.suitabilityRatio >= MOVIE_SAFETY_OVERRIDE_MIN_RATIO &&
                (challenger.safeBudgetMbps - challenger.requiredMbps) - selectedHeadroomMbps >= MOVIE_SAFETY_OVERRIDE_MIN_HEADROOM_DELTA_MBPS &&
                challenger.breakdown.averageBitrateMbps >= selected.breakdown.averageBitrateMbps * MOVIE_SAFETY_OVERRIDE_MIN_BITRATE_FRACTION
        }
        .maxWithOrNull(
            compareByDescending<ShadowStreamDecision> { it.finalScore }
                .thenByDescending { it.contentQualityScore }
                .thenByDescending { it.breakdown.averageBitrateMbps }
                .thenByDescending { it.suitabilityRatio }
        )
        ?: return ranked

    return buildList {
        add(saferCandidate)
        addAll(ranked.filterNot { it.streamKey == saferCandidate.streamKey })
    }
}

private fun movieResolutionPoolRank(resolutionTier: String): Int {
    return when (resolutionTier.lowercase(Locale.US)) {
        "uhd_2160" -> 3
        "fhd_1080" -> 2
        "hd_720" -> 1
        else -> 0
    }
}

private fun applyShowCandidatePool(
    ranked: List<ShadowStreamDecision>
): List<ShadowStreamDecision> {
    if (ranked.isEmpty()) return ranked
    val highestTier = ranked
        .maxOfOrNull { movieResolutionPoolRank(it.breakdown.content.resolutionTier) }
        ?: return ranked
    val sameTier = ranked.filter { movieResolutionPoolRank(it.breakdown.content.resolutionTier) == highestTier }
    val pool = if (sameTier.size <= SHOW_POOL_MIN_CANDIDATES) {
        sameTier
    } else {
        val targetCount = maxOf(SHOW_POOL_MIN_CANDIDATES, kotlin.math.ceil(sameTier.size * SHOW_POOL_PERCENTILE).toInt())
        sameTier
            .sortedByDescending { it.breakdown.averageBitrateMbps }
            .take(targetCount.coerceAtMost(sameTier.size))
    }

    val nonProblematicPool = pool.filterNot { candidate ->
        candidate.breakdown.releaseType == ShadowReleaseType.WEBDL.wireKey &&
            candidate.parsed.visualTags.any { tag ->
                val normalized = tag.uppercase(Locale.US)
                normalized == "DV" || normalized.contains("DOLBY VISION") || normalized.contains("DOVI")
            } &&
            candidate.breakdown.content.hdrTier == ShadowHdrTier.SDR.name.lowercase(Locale.US)
    }

    val finalPool = when {
        nonProblematicPool.isNotEmpty() -> nonProblematicPool
        highestTier <= 0 -> pool
        else -> {
            val nextTier = ranked.filter { movieResolutionPoolRank(it.breakdown.content.resolutionTier) == highestTier - 1 }
            if (nextTier.isEmpty()) pool else nextTier
        }
    }

    return finalPool.sortedWith(baseDecisionComparator(movieMode = false, showMode = true))
}

private fun baseDecisionComparator(movieMode: Boolean, showMode: Boolean): Comparator<ShadowStreamDecision> {
    return if (movieMode || showMode) {
        compareByDescending<ShadowStreamDecision> { it.finalScore }
            .thenByDescending { it.contentQualityScore }
            .thenByDescending { it.breakdown.content.bitrateQualityPoints }
            .thenByDescending { it.breakdown.averageBitrateMbps }
            .thenByDescending { it.suitabilityRatio }
            .thenBy { it.breakdown.transport.startupTtfbMs ?: Long.MAX_VALUE }
            .thenBy { it.breakdown.transport.seekTtfbP95Ms ?: Long.MAX_VALUE }
    } else {
        compareByDescending<ShadowStreamDecision> { it.finalScore }
            .thenByDescending { it.contentQualityScore }
            .thenByDescending { it.breakdown.content.bitrateQualityPoints }
            .thenByDescending { it.suitabilityRatio }
            .thenBy { it.breakdown.transport.startupTtfbMs ?: Long.MAX_VALUE }
            .thenBy { it.breakdown.transport.seekTtfbP95Ms ?: Long.MAX_VALUE }
    }
}

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
                startupScore = startupScore(config, profile.startup.initialTtfbMs),
                seekScore = seekScore(
                    config = config,
                    seekP95Ms = profile.seek.seekTtfbP95Ms,
                    failRate = profile.seek.seekFailRate
                ),
                stabilityScore = stabilityScore(
                    config = config,
                    throughputCv = profile.sustained.throughputCv,
                    stallCount = profile.sustained.stallCount,
                    maxReadGapMs = profile.sustained.maxReadGapMs
                ),
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
        penaltyPoints
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
        sizeBytes = parsed.sizeBytes,
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

private fun StreamCardModel.shadowStreamKey(): String {
    return stream.wrappedOriginalStreamKey
        ?: parsed.exactDuplicateKey
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
    if (movieMode) {
        return when {
            !ratio.isFinite() -> MOVIE_TRANSPORT_SCORE_HIGH
            ratio < MOVIE_MINIMUM_RATIO -> 0
            ratio < MOVIE_COMFORTABLE_RATIO -> MOVIE_TRANSPORT_SCORE_LOW
            ratio < MOVIE_SAFE_RATIO -> MOVIE_TRANSPORT_SCORE_MID
            else -> MOVIE_TRANSPORT_SCORE_HIGH
        }
    } else if (showMode) {
        return when {
            !ratio.isFinite() -> SHOW_TRANSPORT_SCORE_MAX
            ratio < SHOW_MINIMUM_RATIO -> 0
            ratio < SHOW_COMFORTABLE_RATIO -> SHOW_TRANSPORT_SCORE_MIN
            else -> SHOW_TRANSPORT_SCORE_MAX
        }
    }
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
    return Comparator { left, right ->
        val leftComfortable = left.suitabilityRatio >= config.viability.preferStartupRatio
        val rightComfortable = right.suitabilityRatio >= config.viability.preferStartupRatio
        if (leftComfortable && rightComfortable) {
            compareValuesBy(
                left,
                right,
                { it.startupTtfbMs ?: Long.MAX_VALUE },
                { it.seekTtfbP95Ms ?: Long.MAX_VALUE },
                { it.seekFailRate ?: Double.MAX_VALUE },
                { -it.totalScore() },
                { -it.suitabilityRatio }
            )
        } else {
            compareValuesBy(
                left,
                right,
                { -it.totalScore() },
                { -it.suitabilityRatio },
                { it.startupTtfbMs ?: Long.MAX_VALUE },
                { it.seekTtfbP95Ms ?: Long.MAX_VALUE },
                { it.seekFailRate ?: Double.MAX_VALUE }
            )
        }
    }
}

private fun startupScore(
    config: BenchmarkAwareStreamScoringConfig,
    initialTtfbMs: Long?
): Int {
    val value = initialTtfbMs ?: return 0
    return config.transportRewards.startupBands.firstOrNull { value <= it.maxMs }?.reward ?: 0
}

private fun seekScore(
    config: BenchmarkAwareStreamScoringConfig,
    seekP95Ms: Long?,
    failRate: Double?
): Int {
    val p95 = seekP95Ms ?: return 0
    val failure = failRate ?: return 0
    return config.transportRewards.seekBands.firstOrNull {
        p95 <= it.maxP95Ms && failure <= it.maxFailRate
    }?.reward ?: 0
}

private fun stabilityScore(
    config: BenchmarkAwareStreamScoringConfig,
    throughputCv: Double?,
    stallCount: Int?,
    maxReadGapMs: Long?
): Int {
    val cv = throughputCv ?: return 0
    return config.transportRewards.stabilityBands.firstOrNull { band ->
        cv <= band.maxCv &&
            (band.maxStalls == null || (stallCount ?: Int.MAX_VALUE) <= band.maxStalls) &&
            (band.maxGapMs == null || (maxReadGapMs ?: Long.MAX_VALUE) <= band.maxGapMs)
    }?.reward ?: 0
}

private val PREMIUM_RELEASE_TYPES = setOf(
    ShadowReleaseType.REMUX,
    ShadowReleaseType.BLURAY_ENCODE
)

private val PREMIUM_AUDIO_TIERS = setOf(
    ShadowAudioTier.TRUEHD_ATMOS,
    ShadowAudioTier.DTSX,
    ShadowAudioTier.TRUEHD,
    ShadowAudioTier.DTSHD,
    ShadowAudioTier.DDP_ATMOS
)

private val IMMERSIVE_AUDIO_TIERS = setOf(
    ShadowAudioTier.TRUEHD_ATMOS,
    ShadowAudioTier.DTSX,
    ShadowAudioTier.DDP_ATMOS
)

private val SOFTWARE_4K_CODEC_TIERS = setOf(
    ShadowVideoCodecTier.AV1_SW,
    ShadowVideoCodecTier.HEVC_SW
)

private val HDR_VISUAL_TAGS = setOf("DV", "HDR", "HDR10", "HDR10+")

private const val MOVIE_POOL_PERCENTILE = 0.30
private const val MOVIE_POOL_MIN_CANDIDATES = 3
private const val MOVIE_MINIMUM_RATIO = 1.15
private const val MOVIE_COMFORTABLE_RATIO = 1.20
private const val MOVIE_SAFE_RATIO = 1.25
private const val MOVIE_TRANSPORT_SCORE_LOW = 5
private const val MOVIE_TRANSPORT_SCORE_MID = 10
private const val MOVIE_TRANSPORT_SCORE_HIGH = 15
private const val MOVIE_SAFETY_OVERRIDE_SELECTED_MAX_RATIO = 1.25
private const val MOVIE_SAFETY_OVERRIDE_MIN_RATIO = 1.50
private const val MOVIE_SAFETY_OVERRIDE_MAX_SCORE_GAP = 3
private const val MOVIE_SAFETY_OVERRIDE_MIN_HEADROOM_DELTA_MBPS = 20.0
private const val MOVIE_SAFETY_OVERRIDE_MIN_BITRATE_FRACTION = 0.75
private const val SHOW_POOL_PERCENTILE = 0.30
private const val SHOW_POOL_MIN_CANDIDATES = 3
private const val SHOW_MINIMUM_RATIO = 1.10
private const val SHOW_COMFORTABLE_RATIO = 1.20
private const val SHOW_TRANSPORT_SCORE_MIN = 8
private const val SHOW_TRANSPORT_SCORE_MAX = 10

private fun ShadowRequestContext.isMovieLike(): Boolean {
    return contentType.equals("movie", ignoreCase = true)
}

private fun ShadowRequestContext.isSeriesLike(): Boolean {
    return contentType.equals("series", ignoreCase = true)
}
