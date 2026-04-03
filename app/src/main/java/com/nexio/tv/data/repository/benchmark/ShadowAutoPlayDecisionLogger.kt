package com.nexio.tv.data.repository.benchmark

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val SHADOW_AUTOPLAY_JSON_TAG = "AutoPlayShadowJson"
private const val SHADOW_AUTOPLAY_TAG = "AutoPlayShadow"

@Singleton
class ShadowAutoPlayDecisionLogger internal constructor(
    private val logger: (String, String) -> Unit
) {

    @Inject
    constructor() : this(logger = { tag, message -> Log.i(tag, message) })

    fun log(event: ShadowAutoPlayDecisionEvent) {
        runCatching {
            logger(SHADOW_AUTOPLAY_JSON_TAG, buildEventJson(event))
        }.onFailure {
            logger(SHADOW_AUTOPLAY_TAG, buildSummaryLine(event) + " serialization=failed")
        }.onSuccess {
            logger(SHADOW_AUTOPLAY_TAG, buildSummaryLine(event))
        }
    }

    internal fun buildEventJson(event: ShadowAutoPlayDecisionEvent): String {
        return JsonObject().apply {
            addProperty("event_version", event.eventVersion)
            addProperty("event_type", event.eventType)
            add("request", JsonObject().apply {
                addProperty("requestId", event.request.requestId)
                addProperty("videoId", event.request.videoId)
                addProperty("contentType", event.request.contentType)
                event.request.title?.let { addProperty("title", it) }
                event.request.season?.let { addProperty("season", it) }
                event.request.episode?.let { addProperty("episode", it) }
                event.request.runtimeMinutes?.let { addProperty("runtimeMinutes", it) }
            })
            add("benchmarksUsed", JsonArray().apply {
                event.benchmarksUsed.forEach { benchmark ->
                    add(JsonObject().apply {
                        addProperty("provider", benchmark.provider.storageKey)
                        addProperty("measuredAtMs", benchmark.measuredAtMs)
                        benchmark.benchmarkVersion?.let { addProperty("benchmarkVersion", it) }
                        benchmark.host?.let { addProperty("host", it) }
                        benchmark.directUrlFingerprint?.let { addProperty("directUrlFingerprint", it) }
                    })
                }
            })
            add("winners", JsonArray().apply {
                event.winners.forEach { add(it.toJsonObject()) }
            })
            add("rejected", JsonArray().apply {
                event.rejected.forEach { rejection ->
                    add(JsonObject().apply {
                        addProperty("streamKey", rejection.streamKey)
                        add("parsed", rejection.parsed.toJsonObject())
                        rejection.provider?.let { addProperty("provider", it.storageKey) }
                        add("reasons", JsonArray().apply {
                            rejection.reasons.forEach { add(it.name.lowercase(Locale.US)) }
                        })
                    })
                }
            })
            event.selected?.let { add("selected", it.toJsonObject()) }
            event.selectedNonDolbyVisionFallback?.let {
                add("selectedNonDolbyVisionFallback", it.toJsonObject())
            }
            event.timingsMs?.let { addProperty("timingsMs", it) }
        }.toString()
    }

    internal fun buildSummaryLine(event: ShadowAutoPlayDecisionEvent): String {
        val winner = event.selected
        if (winner == null) {
            return "winner=none eligible=${event.winners.size} rejected=${event.rejected.size}"
        }
        val hdr = winner.hdrTags.firstOrNull() ?: "none"
        val audio = winner.audioTags.firstOrNull() ?: "none"
        return buildString {
            append("winner=")
            append(winner.provider.storageKey)
            append(' ')
            append(winner.transport.wireKey)
            append(" score=")
            append(winner.finalScore)
            append(" ratio=")
            append(String.format(Locale.US, "%.2f", winner.suitabilityRatio))
            winner.resolution?.let {
                append(" resolution=")
                append(it)
            }
            append(" hdr=")
            append(hdr)
            append(" audio=")
            append(audio)
            event.selectedNonDolbyVisionFallback?.let { fallback ->
                append(" fallback=")
                append(fallback.streamKey)
            }
        }
    }
}

private fun ShadowStreamDecision.toJsonObject(): JsonObject {
    return JsonObject().apply {
        addProperty("streamKey", streamKey)
        add("parsed", parsed.toJsonObject())
        addProperty("provider", provider.storageKey)
        addProperty("transport", transport.wireKey)
        addProperty("finalScore", finalScore)
        addProperty("contentQualityScore", contentQualityScore)
        addProperty("transportFitScore", transportFitScore)
        addProperty("suitabilityRatio", suitabilityRatio)
        addProperty("requiredMbps", requiredMbps)
        addProperty("safeBudgetMbps", safeBudgetMbps)
        resolution?.let { addProperty("resolution", it) }
        add("hdrTags", JsonArray().apply { hdrTags.forEach(::add) })
        add("audioTags", JsonArray().apply { audioTags.forEach(::add) })
        add("breakdown", JsonObject().apply {
            addProperty("averageBitrateMbps", breakdown.averageBitrateMbps)
            addProperty("releaseType", breakdown.releaseType)
            addProperty("lowQuality4k", breakdown.lowQuality4k)
            add("content", JsonObject().apply {
                addProperty("resolutionPoints", breakdown.content.resolutionPoints)
                addProperty("audioPoints", breakdown.content.audioPoints)
                addProperty("hdrPoints", breakdown.content.hdrPoints)
                addProperty("codecPoints", breakdown.content.codecPoints)
                addProperty("releaseTypePoints", breakdown.content.releaseTypePoints)
                addProperty("bitrateQualityPoints", breakdown.content.bitrateQualityPoints)
                addProperty("synergyPoints", breakdown.content.synergyPoints)
                addProperty("penaltyPoints", breakdown.content.penaltyPoints)
                addProperty("lowQuality4kPenalty", breakdown.content.lowQuality4kPenalty)
                addProperty("resolutionTier", breakdown.content.resolutionTier)
                addProperty("releaseTypeTier", breakdown.content.releaseTypeTier)
                addProperty("codecTier", breakdown.content.codecTier)
                addProperty("hdrTier", breakdown.content.hdrTier)
                addProperty("audioTier", breakdown.content.audioTier)
                addProperty("audioSupportTier", breakdown.content.audioSupportTier)
                addProperty("hdrSupportTier", breakdown.content.hdrSupportTier)
                addProperty("realismRatio", breakdown.content.realismRatio)
            })
            add("transport", JsonObject().apply {
                addProperty("provider", breakdown.transport.provider.storageKey)
                addProperty("transport", breakdown.transport.transport.wireKey)
                addProperty("safeBudgetMbps", breakdown.transport.safeBudgetMbps)
                addProperty("requiredMbps", breakdown.transport.requiredMbps)
                addProperty("suitabilityRatio", breakdown.transport.suitabilityRatio)
                addProperty("ratioScore", breakdown.transport.ratioScore)
                addProperty("startupScore", breakdown.transport.startupScore)
                addProperty("seekScore", breakdown.transport.seekScore)
                addProperty("stabilityScore", breakdown.transport.stabilityScore)
                breakdown.transport.startupTtfbMs?.let { addProperty("startupTtfbMs", it) }
                breakdown.transport.seekTtfbP95Ms?.let { addProperty("seekTtfbP95Ms", it) }
                breakdown.transport.seekFailRate?.let { addProperty("seekFailRate", it) }
            })
        })
    }
}

private fun ShadowParsedStreamFacts.toJsonObject(): JsonObject {
    return JsonObject().apply {
        serviceId?.let { addProperty("serviceId", it) }
        filename?.let { addProperty("filename", it) }
        sizeBytes?.let { addProperty("sizeBytes", it) }
        durationMs?.let { addProperty("durationMs", it) }
        runtimeSource?.let { addProperty("runtimeSource", it) }
        resolution?.let { addProperty("resolution", it) }
        quality?.let { addProperty("quality", it) }
        videoCodec?.let { addProperty("videoCodec", it) }
        releaseGroup?.let { addProperty("releaseGroup", it) }
        cached?.let { addProperty("cached", it) }
        add("audioTags", JsonArray().apply { audioTags.forEach(::add) })
        add("audioChannels", JsonArray().apply { audioChannels.forEach(::add) })
        add("visualTags", JsonArray().apply { visualTags.forEach(::add) })
        add("languages", JsonArray().apply { languages.forEach(::add) })
    }
}
