package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.data.repository.SkipInterval
import javax.inject.Inject
import javax.inject.Singleton

enum class SkipProviderRoute {
    THEINTRODB,
    ANIME_PRIMARY
}

data class SkipSegmentRequest(
    val contentId: String?,
    val currentVideoId: String?,
    val season: Int?,
    val episode: Int?,
    val contentType: String?
)

interface SkipIntroRepositoryPort {
    fun clearCachedIntervals()

    suspend fun getSkipIntervals(contentId: String?, season: Int?, episode: Int?): List<SkipInterval>

    suspend fun getAnimePrimarySkipIntervals(imdbId: String?, season: Int, episode: Int): List<SkipInterval>

    suspend fun getSkipIntervalsForMal(malId: String, episode: Int): List<SkipInterval>

    suspend fun getSkipIntervalsForKitsu(kitsuId: String, episode: Int): List<SkipInterval>
}

@Singleton
class SkipSegmentResolver @Inject constructor(
    private val skipIntroRepositoryPort: SkipIntroRepositoryPort
) {
    suspend fun resolveSkipSegments(request: SkipSegmentRequest): List<SkipInterval> {
        val effectiveId = request.effectiveId() ?: return emptyList()

        parseDirectProviderEpisode(effectiveId, request.episode)?.let { direct ->
            return when (direct.provider) {
                DirectProvider.MAL -> skipIntroRepositoryPort.getSkipIntervalsForMal(direct.id, direct.episode)
                DirectProvider.KITSU -> skipIntroRepositoryPort.getSkipIntervalsForKitsu(direct.id, direct.episode)
            }
        }

        val canonicalId = effectiveId.substringBefore(":").takeIf { it.isNotBlank() } ?: return emptyList()
        if (!canonicalId.isSupportedTheIntroDbId()) return emptyList()

        return if (
            resolveRoute(request) == SkipProviderRoute.ANIME_PRIMARY &&
            canonicalId.startsWith("tt") &&
            request.season != null &&
            request.episode != null
        ) {
            skipIntroRepositoryPort.getAnimePrimarySkipIntervals(canonicalId, request.season, request.episode)
        } else {
            skipIntroRepositoryPort.getSkipIntervals(canonicalId, request.season, request.episode)
        }
    }

    fun cacheKeyFor(request: SkipSegmentRequest): String? {
        val effectiveId = request.effectiveId() ?: return null
        parseDirectProviderEpisode(effectiveId, request.episode)?.let { direct ->
            return "${direct.provider.cachePrefix}:${direct.id}:${direct.episode}"
        }

        val canonicalId = effectiveId.substringBefore(":").takeIf { it.isNotBlank() } ?: return null
        if (!canonicalId.isSupportedTheIntroDbId()) return null
        return "$canonicalId:${request.season}:${request.episode}"
    }

    fun resolveRoute(request: SkipSegmentRequest): SkipProviderRoute {
        val normalizedType = request.contentType?.trim()?.lowercase().orEmpty()
        val normalizedEffectiveId = request.effectiveId()?.trim()?.lowercase().orEmpty()
        val normalizedFallbackId = request.contentId?.trim()?.lowercase().orEmpty()

        return when {
            normalizedEffectiveId.startsWith("mal:") || normalizedEffectiveId.startsWith("kitsu:") ->
                SkipProviderRoute.ANIME_PRIMARY
            normalizedType == "anime" -> SkipProviderRoute.ANIME_PRIMARY
            ":anime:" in normalizedEffectiveId || ":anime:" in normalizedFallbackId ->
                SkipProviderRoute.ANIME_PRIMARY
            else -> SkipProviderRoute.THEINTRODB
        }
    }

    fun clearCachedIntervals() {
        skipIntroRepositoryPort.clearCachedIntervals()
    }

    private fun SkipSegmentRequest.effectiveId(): String? =
        currentVideoId?.takeIf { it.isNotBlank() } ?: contentId?.takeIf { it.isNotBlank() }

    private fun parseDirectProviderEpisode(effectiveId: String, fallbackEpisode: Int?): DirectProviderEpisode? {
        val parts = effectiveId.split(":")
        val provider = when (parts.firstOrNull()?.lowercase()) {
            "mal" -> DirectProvider.MAL
            "kitsu" -> DirectProvider.KITSU
            else -> return null
        }
        val id = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val episode = parts.getOrNull(2)?.toIntOrNull() ?: fallbackEpisode ?: return null
        return DirectProviderEpisode(provider, id, episode)
    }

    private fun String.isSupportedTheIntroDbId(): Boolean =
        startsWith("tt") || toIntOrNull() != null

    private enum class DirectProvider(val cachePrefix: String) {
        MAL("mal"),
        KITSU("kitsu")
    }

    private data class DirectProviderEpisode(
        val provider: DirectProvider,
        val id: String,
        val episode: Int
    )
}
