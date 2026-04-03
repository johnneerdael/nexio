package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.core.stream.ParsedStreamInfo
import com.nexio.tv.core.stream.StreamCardModel
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

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
    val lowQuality4kPenalty: Int
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
    val content: ShadowContentScoreBreakdown,
    val transport: ShadowTransportScoreBreakdown
)

data class ShadowStreamDecision(
    val streamKey: String,
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
    val timingsMs: Long? = null
)

@Singleton
class BenchmarkAwareStreamScorer @Inject constructor() {

    fun score(
        request: ShadowRequestContext,
        streams: List<StreamCardModel>,
        benchmarkSessions: Map<DebridBenchmarkProvider, DebridBenchmarkResult>,
        elapsedMs: Long? = null
    ): ShadowAutoPlayDecisionEvent {
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
            if (provider == null) {
                rejected += ShadowRejectedStream(
                    streamKey = streamKey,
                    reasons = listOf(ShadowRejectReason.NOT_DEBRID_WRAPPED)
                )
                return@forEach
            }
            val benchmarkSession = benchmarkSessions[provider]
            if (benchmarkSession == null) {
                rejected += ShadowRejectedStream(
                    streamKey = streamKey,
                    provider = provider,
                    reasons = listOf(ShadowRejectReason.MISSING_BENCHMARK)
                )
                return@forEach
            }

            evaluateStream(
                item = item,
                provider = provider,
                benchmarkSession = benchmarkSession,
                request = request
            ).fold(
                onSuccess = { winners += it },
                onFailure = { reasons ->
                    rejected += ShadowRejectedStream(
                        streamKey = streamKey,
                        provider = provider,
                        reasons = reasons
                    )
                }
            )
        }

        val ranked = winners.sortedWith(
            compareByDescending<ShadowStreamDecision> { it.finalScore }
                .thenByDescending { it.transportFitScore }
                .thenByDescending { it.suitabilityRatio }
                .thenBy { it.breakdown.transport.startupTtfbMs ?: Long.MAX_VALUE }
                .thenBy { it.breakdown.transport.seekTtfbP95Ms ?: Long.MAX_VALUE }
        )

        return ShadowAutoPlayDecisionEvent(
            eventVersion = 1,
            eventType = "shadow_autoplay_decision",
            request = request,
            benchmarksUsed = benchmarkReferences,
            winners = ranked,
            rejected = rejected.sortedBy { it.streamKey },
            selected = ranked.firstOrNull(),
            timingsMs = elapsedMs
        )
    }

    private fun evaluateStream(
        item: StreamCardModel,
        provider: DebridBenchmarkProvider,
        benchmarkSession: DebridBenchmarkResult,
        request: ShadowRequestContext
    ): EitherSuccessOrReject<ShadowStreamDecision> {
        val parsed = item.parsed
        val sizeBytes = parsed.sizeBytes
        if (sizeBytes == null || sizeBytes <= 0L) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.MISSING_SIZE)
        }
        val runtimeMs = parsed.durationMs ?: request.runtimeMinutes?.times(60_000L)
        if (runtimeMs == null || runtimeMs <= 0L) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.MISSING_RUNTIME)
        }

        val device = benchmarkSession.device
        val codecFamily = detectCodecFamily(parsed.encode)
        if (device != null && !isCodecSupported(codecFamily, device)) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.UNSUPPORTED_CODEC)
        }

        val averageBitrateMbps = calculateAverageBitrateMbps(sizeBytes, runtimeMs)
        val releaseType = classifyReleaseType(parsed)
        val requiredMbps = averageBitrateMbps * releaseType.burstMargin
        val transportOption = bestTransportOption(
            provider = provider,
            benchmarkSession = benchmarkSession,
            requiredMbps = requiredMbps
        ) ?: return EitherSuccessOrReject.reject(
            ShadowRejectReason.INSUFFICIENT_TRANSPORT_BUDGET,
            ShadowRejectReason.NO_ELIGIBLE_TRANSPORT
        )

        val contentBreakdown = buildContentScoreBreakdown(
            parsed = parsed,
            averageBitrateMbps = averageBitrateMbps,
            codecFamily = codecFamily,
            releaseType = releaseType,
            device = device
        )
        val contentScore = contentBreakdown.total()
        val finalScore = contentScore + transportOption.totalScore()

        return EitherSuccessOrReject.success(
            ShadowStreamDecision(
                streamKey = item.shadowStreamKey(),
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
                    content = contentBreakdown,
                    transport = transportOption.toBreakdown(provider, requiredMbps)
                )
            )
        )
    }

    private fun bestTransportOption(
        provider: DebridBenchmarkProvider,
        benchmarkSession: DebridBenchmarkResult,
        requiredMbps: Double
    ): ShadowTransportOption? {
        val options = listOfNotNull(
            benchmarkSession.direct?.let {
                ShadowTransportOption.fromProfile(
                    transport = DebridBenchmarkTransportMode.DIRECT,
                    profile = it,
                    requiredMbps = requiredMbps
                )
            },
            benchmarkSession.optimized?.let {
                ShadowTransportOption.fromProfile(
                    transport = DebridBenchmarkTransportMode.OPTIMIZED,
                    profile = it,
                    requiredMbps = requiredMbps
                )
            }
        ).filter { it.suitabilityRatio >= 1.0 }

        return options.sortedWith(
            compareByDescending<ShadowTransportOption> { it.totalScore() }
                .thenByDescending { it.suitabilityRatio }
                .thenBy { it.startupTtfbMs ?: Long.MAX_VALUE }
                .thenBy { it.seekTtfbP95Ms ?: Long.MAX_VALUE }
        ).firstOrNull()
    }

    private fun buildContentScoreBreakdown(
        parsed: ParsedStreamInfo,
        averageBitrateMbps: Double,
        codecFamily: ShadowCodecFamily,
        releaseType: ShadowReleaseType,
        device: DeviceCapabilitySnapshot?
    ): ShadowContentScoreBreakdown {
        val lowQuality4kPenalty = if (
            parsed.resolution.equals("2160p", ignoreCase = true) &&
            averageBitrateMbps < 8.0
        ) {
            -40
        } else {
            0
        }
        return ShadowContentScoreBreakdown(
            resolutionPoints = resolutionPoints(parsed.resolution),
            audioPoints = audioPoints(parsed.audioTags, device),
            hdrPoints = hdrPoints(parsed.visualTags, device),
            codecPoints = codecPoints(codecFamily, device),
            releaseTypePoints = releaseType.points,
            bitrateQualityPoints = bitrateQualityPoints(parsed.resolution, averageBitrateMbps),
            lowQuality4kPenalty = lowQuality4kPenalty
        )
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
            transport: DebridBenchmarkTransportMode,
            profile: DebridBenchmarkTransportProfile,
            requiredMbps: Double
        ): ShadowTransportOption? {
            val safeBudgetMbps = profile.safeSustainedBudgetMbps() ?: return null
            val suitabilityRatio = if (requiredMbps <= 0.0) Double.POSITIVE_INFINITY else safeBudgetMbps / requiredMbps
            return ShadowTransportOption(
                transport = transport,
                safeBudgetMbps = safeBudgetMbps,
                suitabilityRatio = suitabilityRatio,
                ratioScore = ratioScore(suitabilityRatio),
                startupScore = startupScore(profile.startup.initialTtfbMs),
                seekScore = seekScore(
                    seekP95Ms = profile.seek.seekTtfbP95Ms,
                    failRate = profile.seek.seekFailRate
                ),
                stabilityScore = stabilityScore(
                    throughputCv = profile.sustained.throughputCv,
                    stallCount = profile.sustained.stallCount,
                    maxReadGapMs = profile.sustained.maxReadGapMs
                ),
                startupTtfbMs = profile.startup.initialTtfbMs,
                seekTtfbP95Ms = profile.seek.seekTtfbP95Ms,
                seekFailRate = profile.seek.seekFailRate
            )
        }
    }
}

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

private enum class ShadowReleaseType(
    val wireKey: String,
    val burstMargin: Double,
    val points: Int
) {
    REMUX("remux", 1.60, 10),
    BLURAY_ENCODE("bluray_encode", 1.35, 6),
    WEBDL("webdl", 1.35, 4),
    WEBRIP("webrip", 1.35, 2),
    UNKNOWN("unknown", 1.35, 0)
}

private enum class ShadowCodecFamily {
    AV1,
    HEVC,
    H264,
    VC1,
    MPEG2,
    OTHER
}

private fun ShadowContentScoreBreakdown.total(): Int {
    return resolutionPoints +
        audioPoints +
        hdrPoints +
        codecPoints +
        releaseTypePoints +
        bitrateQualityPoints +
        lowQuality4kPenalty
}

private fun StreamCardModel.toBenchmarkProvider(): DebridBenchmarkProvider? {
    return when (stream.wrappedProviderId?.uppercase(Locale.US)) {
        "RD" -> DebridBenchmarkProvider.REAL_DEBRID
        "PM" -> DebridBenchmarkProvider.PREMIUMIZE
        else -> null
    }
}

private fun StreamCardModel.shadowStreamKey(): String {
    return stream.wrappedOriginalStreamKey
        ?: parsed.exactDuplicateKey
}

private fun calculateAverageBitrateMbps(sizeBytes: Long, runtimeMs: Long): Double {
    val runtimeSeconds = runtimeMs / 1_000.0
    return (sizeBytes * 8.0) / runtimeSeconds / 1_000_000.0
}

private fun classifyReleaseType(parsed: ParsedStreamInfo): ShadowReleaseType {
    val quality = parsed.quality.orEmpty().lowercase(Locale.US)
    return when {
        quality.contains("remux") -> ShadowReleaseType.REMUX
        quality.contains("blu") -> ShadowReleaseType.BLURAY_ENCODE
        quality.contains("web-dl") || quality.contains("webdl") -> ShadowReleaseType.WEBDL
        quality.contains("webrip") || quality.contains("web-rip") -> ShadowReleaseType.WEBRIP
        else -> ShadowReleaseType.UNKNOWN
    }
}

private fun resolutionPoints(resolution: String?): Int {
    return when (resolution?.lowercase(Locale.US)) {
        "2160p" -> 28
        "1080p" -> 14
        "720p" -> 4
        else -> 0
    }
}

private fun audioPoints(tags: List<String>, device: DeviceCapabilitySnapshot?): Int {
    val normalized = tags.map { it.lowercase(Locale.US) }
    val audioOutput = device?.audioOutput
    val candidates = listOf(
        if (normalized.any { it.contains("atmos") } &&
            (audioOutput == null ||
                audioOutput.truehd.passthroughLikely ||
                audioOutput.eac3.passthroughLikely)
        ) 24 else 0,
        if (normalized.any { it.contains("dts:x") } &&
            (audioOutput == null || audioOutput.dtshd.passthroughLikely)
        ) 12 else 0,
        if (normalized.any { it.contains("dts-hd") } &&
            (audioOutput == null || audioOutput.dtshd.passthroughLikely)
        ) 12 else 0,
        if (normalized.any { it.contains("truehd") } &&
            (audioOutput == null || audioOutput.truehd.passthroughLikely)
        ) 12 else 0,
        if (normalized.any { it == "dts" } &&
            (audioOutput == null || audioOutput.dts.passthroughLikely)
        ) 12 else 0,
        if (normalized.any { it.contains("dd+") || it.contains("eac3") } &&
            (audioOutput == null || audioOutput.eac3.passthroughLikely)
        ) 12 else 0,
        if (normalized.any { it == "dd" || it.contains("ac3") } &&
            (audioOutput == null || audioOutput.ac3.passthroughLikely)
        ) 12 else 0
    )
    return candidates.maxOrNull() ?: 0
}

private fun hdrPoints(tags: List<String>, device: DeviceCapabilitySnapshot?): Int {
    val normalized = tags.map { it.uppercase(Locale.US) }
    val hdrTypes = device?.displayHdrTypes
    return when {
        normalized.any { it == "DV" || it.contains("DOLBY VISION") } &&
            (hdrTypes == null || DeviceHdrType.DOLBY_VISION in hdrTypes) -> 10
        normalized.any { it == "HDR10+" } &&
            (hdrTypes == null || DeviceHdrType.HDR10_PLUS in hdrTypes) -> 8
        normalized.any { it == "HDR10" || it == "HDR" } &&
            (hdrTypes == null || DeviceHdrType.HDR10 in hdrTypes || DeviceHdrType.HDR10_PLUS in hdrTypes) -> 5
        else -> 0
    }
}

private fun detectCodecFamily(encode: String?): ShadowCodecFamily {
    val normalized = encode.orEmpty().lowercase(Locale.US)
    return when {
        normalized.contains("av1") -> ShadowCodecFamily.AV1
        normalized.contains("hevc") || normalized.contains("h265") || normalized.contains("x265") -> ShadowCodecFamily.HEVC
        normalized.contains("h264") || normalized.contains("x264") || normalized.contains("avc") -> ShadowCodecFamily.H264
        normalized.contains("vc1") || normalized.contains("vc-1") || normalized.contains("wvc1") -> ShadowCodecFamily.VC1
        normalized.contains("mpeg2") || normalized.contains("mpeg-2") -> ShadowCodecFamily.MPEG2
        else -> ShadowCodecFamily.OTHER
    }
}

private fun codecPoints(codecFamily: ShadowCodecFamily, device: DeviceCapabilitySnapshot?): Int {
    if (device == null) {
        return when (codecFamily) {
            ShadowCodecFamily.AV1 -> 0
            ShadowCodecFamily.HEVC -> 6
            ShadowCodecFamily.H264 -> 3
            ShadowCodecFamily.VC1,
            ShadowCodecFamily.MPEG2 -> -12
            ShadowCodecFamily.OTHER -> 0
        }
    }
    return when (codecFamily) {
        ShadowCodecFamily.AV1 -> when {
            device.videoDecode.av1?.hardwareAccelerated == true -> 8
            device.videoDecode.av1?.softwareOnlyAvailable == true -> -12
            else -> -12
        }
        ShadowCodecFamily.HEVC -> if (device.videoDecode.hevc?.hardwareAccelerated == true) 6 else 0
        ShadowCodecFamily.H264 -> if (device.videoDecode.h264?.hardwareAccelerated == true) 3 else 0
        ShadowCodecFamily.VC1,
        ShadowCodecFamily.MPEG2 -> -12
        ShadowCodecFamily.OTHER -> 0
    }
}

private fun isCodecSupported(codecFamily: ShadowCodecFamily, device: DeviceCapabilitySnapshot): Boolean {
    return when (codecFamily) {
        ShadowCodecFamily.AV1 -> device.videoDecode.av1 != null
        ShadowCodecFamily.HEVC -> device.videoDecode.hevc != null
        ShadowCodecFamily.H264 -> device.videoDecode.h264 != null
        ShadowCodecFamily.VC1,
        ShadowCodecFamily.MPEG2 -> false
        ShadowCodecFamily.OTHER -> true
    }
}

private fun bitrateQualityPoints(resolution: String?, averageBitrateMbps: Double): Int {
    return when (resolution?.lowercase(Locale.US)) {
        "2160p" -> when {
            averageBitrateMbps <= 8.0 -> -25
            averageBitrateMbps <= 16.0 -> 0
            averageBitrateMbps <= 30.0 -> 8
            averageBitrateMbps <= 50.0 -> 16
            averageBitrateMbps <= 80.0 -> 24
            else -> 28
        }
        "1080p" -> when {
            averageBitrateMbps <= 4.0 -> -20
            averageBitrateMbps <= 8.0 -> 0
            averageBitrateMbps <= 15.0 -> 10
            averageBitrateMbps <= 25.0 -> 18
            else -> 24
        }
        "720p" -> when {
            averageBitrateMbps <= 2.0 -> -10
            averageBitrateMbps <= 5.0 -> 0
            averageBitrateMbps <= 8.0 -> 8
            else -> 14
        }
        else -> 0
    }
}

private fun ratioScore(ratio: Double): Int {
    return when {
        ratio >= 1.60 -> 35
        ratio >= 1.40 -> 28
        ratio >= 1.25 -> 22
        ratio >= 1.10 -> 14
        ratio >= 1.00 -> 8
        else -> 0
    }
}

private fun startupScore(initialTtfbMs: Long?): Int {
    val value = initialTtfbMs ?: return 0
    return when {
        value <= 150L -> 6
        value <= 300L -> 4
        value <= 600L -> 2
        else -> 0
    }
}

private fun seekScore(seekP95Ms: Long?, failRate: Double?): Int {
    val p95 = seekP95Ms ?: return 0
    val failure = failRate ?: return 0
    return when {
        p95 <= 250L && failure <= 0.005 -> 8
        p95 <= 350L && failure <= 0.01 -> 6
        p95 <= 500L && failure <= 0.02 -> 3
        else -> 0
    }
}

private fun stabilityScore(
    throughputCv: Double?,
    stallCount: Int?,
    maxReadGapMs: Long?
): Int {
    val profile = DebridBenchmarkTransportProfile(
        startup = DebridBenchmarkStartupMetrics(),
        sustained = DebridBenchmarkSustainedMetrics(
            actionable = true,
            throughputCv = throughputCv,
            stallCount = stallCount,
            maxReadGapMs = maxReadGapMs
        ),
        seek = DebridBenchmarkSeekMetrics()
    )
    return when (profile.playbackStability()) {
        DebridBenchmarkPlaybackStability.EXCELLENT -> 6
        DebridBenchmarkPlaybackStability.STABLE -> 4
        DebridBenchmarkPlaybackStability.VARIABLE -> 2
        DebridBenchmarkPlaybackStability.UNSTABLE -> 0
    }
}

private val HDR_VISUAL_TAGS = setOf("DV", "HDR", "HDR10", "HDR10+")
