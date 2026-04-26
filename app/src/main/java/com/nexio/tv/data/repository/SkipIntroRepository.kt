package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.data.integration.skip.AniSkipIntegrationProvider
import com.nexio.tv.data.integration.skip.AnimeSkipIntegrationProvider
import com.nexio.tv.data.integration.skip.ArmIntegrationProvider
import com.nexio.tv.data.integration.skip.IntroDbIntegrationProvider
import com.nexio.tv.data.local.AnimeSkipSettingsDataStore
import com.nexio.tv.data.local.TheIntroDbSettings
import com.nexio.tv.data.local.TheIntroDbSettingsDataStore
import com.nexio.tv.data.remote.api.TheIntroDbMediaResponse
import com.nexio.tv.data.remote.api.TheIntroDbSegmentTimestamp
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.firstOrNull

private const val PROVIDER_THEINTRODB = "theintrodb"

internal enum class SkipProviderRoute {
    THEINTRODB,
    ANIME_PRIMARY
}

internal object SkipProviderArbiter {
    fun resolve(
        contentType: String?,
        effectiveId: String?,
        fallbackId: String?
    ): SkipProviderRoute {
        val normalizedType = contentType?.trim()?.lowercase().orEmpty()
        val normalizedEffectiveId = effectiveId?.trim()?.lowercase().orEmpty()
        val normalizedFallbackId = fallbackId?.trim()?.lowercase().orEmpty()

        return when {
            normalizedEffectiveId.startsWith("mal:") || normalizedEffectiveId.startsWith("kitsu:") -> SkipProviderRoute.ANIME_PRIMARY
            normalizedType == "anime" -> SkipProviderRoute.ANIME_PRIMARY
            ":anime:" in normalizedEffectiveId || ":anime:" in normalizedFallbackId -> SkipProviderRoute.ANIME_PRIMARY
            else -> SkipProviderRoute.THEINTRODB
        }
    }
}

data class SkipInterval(
    val startTime: Double, // seconds
    val endTime: Double,   // seconds
    val type: String,      // "intro", "credits", "preview", "op", "ed", ...
    val provider: String   // "theintrodb", "aniskip", "animeskip"
)

internal data class TheIntroDbSegmentPreferences(
    val showIntroButton: Boolean = true,
    val showRecapButton: Boolean = true,
    val showCreditsButton: Boolean = true,
    val showPreviewButton: Boolean = true
)

internal object TheIntroDbSegmentMapper {
    fun map(
        response: TheIntroDbMediaResponse,
        preferences: TheIntroDbSegmentPreferences
    ): List<SkipInterval> = buildList {
        if (preferences.showIntroButton) {
            addAll(response.intro.mapNotNull { it.toSkipIntervalOrNull(type = "intro", startRequired = false, endRequired = true) })
        }
        if (preferences.showRecapButton) {
            addAll(response.recap.mapNotNull { it.toSkipIntervalOrNull(type = "recap", startRequired = false, endRequired = true) })
        }
        if (preferences.showCreditsButton) {
            addAll(response.credits.mapNotNull { it.toSkipIntervalOrNull(type = "credits", startRequired = true, endRequired = false) })
        }
        if (preferences.showPreviewButton) {
            addAll(response.preview.mapNotNull { it.toSkipIntervalOrNull(type = "preview", startRequired = true, endRequired = false) })
        }
    }.sortedBy { it.startTime }

    private fun TheIntroDbSegmentTimestamp.toSkipIntervalOrNull(
        type: String,
        startRequired: Boolean,
        endRequired: Boolean
    ): SkipInterval? {
        val normalizedStartMs = startMs ?: if (startRequired) return null else 0L
        val start = normalizedStartMs.coerceAtLeast(0L) / 1000.0
        val end = endMs?.takeIf { it > 0L }?.let { it / 1000.0 } ?: Double.MAX_VALUE
        if (endRequired && end == Double.MAX_VALUE) return null
        if (end != Double.MAX_VALUE && end <= start) return null
        return SkipInterval(
            startTime = start,
            endTime = end,
            type = type,
            provider = PROVIDER_THEINTRODB
        )
    }
}

@Singleton
class SkipIntroRepository @Inject constructor(
    private val introDbProvider: IntroDbIntegrationProvider,
    private val aniSkipProvider: AniSkipIntegrationProvider,
    private val animeSkipProvider: AnimeSkipIntegrationProvider,
    private val armProvider: ArmIntegrationProvider,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val theIntroDbSettingsDataStore: TheIntroDbSettingsDataStore
) {
    private val cache = ConcurrentHashMap<String, List<SkipInterval>>()
    private val malIdCache = ConcurrentHashMap<String, String>()
    private val animeSkipShowIdCache = ConcurrentHashMap<String, String>()
    private val introDbConfigured = BuildConfig.INTRODB_API_URL.isNotEmpty()

    fun clearCachedIntervals() {
        cache.clear()
    }

    suspend fun getSkipIntervals(contentId: String?, season: Int?, episode: Int?): List<SkipInterval> {
        if (contentId.isNullOrBlank()) return emptyList()
        val cacheKey = "$contentId:$season:$episode"
        cache[cacheKey]?.let { return it }

        val result = fetchFromTheIntroDb(contentId, season, episode)
        return result.also { cache[cacheKey] = it }
    }

    suspend fun getAnimePrimarySkipIntervals(imdbId: String?, season: Int, episode: Int): List<SkipInterval> {
        if (imdbId.isNullOrBlank()) return emptyList()
        val cacheKey = "anime:$imdbId:$season:$episode"
        cache[cacheKey]?.let { return it }

        val malId = resolveMalId(imdbId)
        if (malId != null) {
            val result = fetchFromAniSkip(malId, episode)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        val anilistIds = resolveAllAnilistIdsFromImdb(imdbId)
        val toTry = listOfNotNull(
            anilistIds.getOrNull(season - 1),
            anilistIds.firstOrNull()
        ).distinct()
        for (anilistId in toTry) {
            val result = fetchFromAnimeSkip(anilistId, episode, season = null)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        return emptyList<SkipInterval>().also { cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForMal(malId: String, episode: Int): List<SkipInterval> {
        val cacheKey = "mal:$malId:$episode"
        cache[cacheKey]?.let { return it }

        val aniSkipResult = fetchFromAniSkip(malId, episode)
        if (aniSkipResult.isNotEmpty()) return aniSkipResult.also { cache[cacheKey] = it }

        val directAnilistId = try {
            armProvider.resolveMalToAnilist(malId = malId)
        } catch (e: Exception) { null }

        if (directAnilistId != null) {
            val result = fetchFromAnimeSkip(directAnilistId, episode, season = null)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        val imdbId = try {
            armProvider.resolveMalToImdb(malId = malId)
        } catch (e: Exception) { null }

        if (imdbId != null) {
            val firstAnilistId = resolveAllAnilistIdsFromImdb(imdbId).firstOrNull()
            if (firstAnilistId != null) {
                val result = fetchFromAnimeSkip(firstAnilistId, episode, season = null)
                if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
            }
        }

        return emptyList<SkipInterval>().also { cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForKitsu(kitsuId: String, episode: Int): List<SkipInterval> {
        val cacheKey = "kitsu:$kitsuId:$episode"
        cache[cacheKey]?.let { return it }

        val malId = try {
            armProvider.resolveKitsuToMal(kitsuId = kitsuId)
        } catch (e: Exception) { null }

        if (malId != null) {
            val result = fetchFromAniSkip(malId, episode)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        // AnimeSkip: try direct AniList ID first (season-specific, no season filter needed)
        val directAnilistId = try {
            armProvider.resolveKitsuToAnilist(kitsuId = kitsuId)
        } catch (e: Exception) { null }

        if (directAnilistId != null) {
            val result = fetchFromAnimeSkip(directAnilistId, episode, season = null)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        // Fallback: Kitsu -> IMDB -> first AniList ID (season 1 show)
        val imdbId = try {
            armProvider.resolveKitsuToImdb(kitsuId = kitsuId)
        } catch (e: Exception) { null }

        if (imdbId != null) {
            val firstAnilistId = resolveAllAnilistIdsFromImdb(imdbId).firstOrNull()
            if (firstAnilistId != null) {
                val result = fetchFromAnimeSkip(firstAnilistId, episode, season = null)
                if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
            }
        }

        return emptyList<SkipInterval>().also { cache[cacheKey] = it }
    }

    private suspend fun fetchFromTheIntroDb(contentId: String, season: Int?, episode: Int?): List<SkipInterval> {
        if (!introDbConfigured) return emptyList()
        val settings = theIntroDbSettingsDataStore.settings.firstOrNull() ?: TheIntroDbSettings()
        if (!settings.enabled) return emptyList()
        val imdbId = contentId.takeIf { it.startsWith("tt") }
        val tmdbId = contentId.toIntOrNull()
        if (imdbId == null && tmdbId == null) return emptyList()

        return try {
            introDbProvider.getIntervals(
                contentId = contentId,
                tmdbId = tmdbId,
                imdbId = imdbId,
                season = season,
                episode = episode,
                preferences = TheIntroDbSegmentPreferences(
                    showIntroButton = settings.showIntroButton,
                    showRecapButton = settings.showRecapButton,
                    showCreditsButton = settings.showCreditsButton,
                    showPreviewButton = settings.showPreviewButton
                )
            )
        } catch (e: Exception) {
            Log.d("SkipIntro", "TheIntroDB: no data for $contentId S${season}E${episode}")
            emptyList()
        }
    }

    private suspend fun fetchFromAniSkip(malId: String, episode: Int): List<SkipInterval> {
        return try {
            aniSkipProvider.getSkipIntervals(malId, episode)
        } catch (e: Exception) {
            Log.d("SkipIntro", "AniSkip: no data for MAL $malId ep $episode")
            emptyList()
        }
    }

    // season: null when anilistId is season-specific; pass season when using season-1 show ID
    private suspend fun fetchFromAnimeSkip(anilistId: String, episode: Int, season: Int?): List<SkipInterval> {
        val clientId = animeSkipSettingsDataStore.clientId.firstOrNull()?.trim()
        if (clientId.isNullOrBlank()) return emptyList()
        val enabled = animeSkipSettingsDataStore.enabled.firstOrNull() ?: false
        if (!enabled) return emptyList()
        return try {
            val showIds = resolveAnimeSkipShowIds(anilistId, clientId)
            if (showIds.isEmpty()) return emptyList()

            for (showId in showIds) {
                val episodes = animeSkipProvider.queryEpisodes(showId, clientId)
                val targetEpisode = episodes.firstOrNull { ep ->
                    ep.number?.toIntOrNull() == episode &&
                        (season == null || ep.season?.toIntOrNull() == season)
                } ?: continue

                val sorted = (targetEpisode.timestamps ?: continue).sortedBy { it.at }
                val result = sorted.mapIndexedNotNull { i, ts ->
                    val endTime = sorted.getOrNull(i + 1)?.at ?: Double.MAX_VALUE
                    val type = when (ts.type.name.lowercase()) {
                        "intro", "new intro" -> "op"
                        "credits" -> "ed"
                        "recap" -> "recap"
                        else -> return@mapIndexedNotNull null
                    }
                    SkipInterval(startTime = ts.at, endTime = endTime, type = type, provider = "animeskip")
                }
                if (result.isNotEmpty()) return result
            }
            emptyList()
        } catch (e: Exception) {
            Log.d("SkipIntro", "AnimeSkip: error for anilist $anilistId ep $episode: ${e.message}")
            emptyList()
        }
    }

    private suspend fun resolveAnimeSkipShowIds(anilistId: String, clientId: String): List<String> {
        animeSkipShowIdCache[anilistId]?.let { cached ->
            return if (cached == NO_ID) emptyList() else listOf(cached)
        }
        val showIds = try {
            animeSkipProvider.resolveShowIds(anilistId, clientId)
        } catch (e: Exception) { emptyList() }
        // cache only if single result; multi-show case skip cache to avoid complexity
        if (showIds.size == 1) animeSkipShowIdCache[anilistId] = showIds[0]
        else if (showIds.isEmpty()) animeSkipShowIdCache[anilistId] = NO_ID
        return showIds
    }

    private suspend fun resolveAllAnilistIdsFromImdb(imdbId: String): List<String> {
        return try {
            armProvider.resolveImdbToAnilist(imdbId)
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun resolveMalId(imdbId: String): String? {
        malIdCache[imdbId]?.let { cached -> return cached.takeIf { it != NO_ID } }
        val malId = try {
            armProvider.resolveImdbToMal(imdbId)
        } catch (e: Exception) { null }
        malIdCache[imdbId] = malId ?: NO_ID
        return malId
    }

    companion object {
        private const val NO_ID = "__none__"
    }
}
