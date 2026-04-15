package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.remote.api.TvdbAirsDays
import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.data.remote.api.TvdbArtworkRecord
import com.nexio.tv.data.remote.api.TvdbEpisodeRecord
import com.nexio.tv.data.remote.api.TvdbRemoteId
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.domain.model.ContentType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val TAG = "TvdbMetadataService"
private const val SERIES_EXTENDED_RECORD_KIND = "series_extended"
private const val DEFAULT_SEASON_TYPE = "default"

@Singleton
class TvdbMetadataService @Inject constructor(
    private val tvdbApi: TvdbApi,
    private val authService: TvdbAuthService,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val metadataDiskCacheStore: MetadataDiskCacheStore
) {
    suspend fun fetchSeriesEnrichment(
        identity: TvdbSeriesIdentity,
        language: String? = null
    ): TvMetadataEnrichment? = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeLanguage(language)
        val activeProvider = posterRatingsUrlResolver.getActiveProvider()
        val providerToken = posterProviderCacheToken(activeProvider)

        metadataDiskCacheStore.readTvdbEnrichment(
            seriesId = identity.tvdbId,
            recordKind = SERIES_EXTENDED_RECORD_KIND,
            languageTag = normalizedLanguage,
            providerToken = providerToken
        )?.let { return@withContext it }

        val authorization = authService.bearerToken() ?: return@withContext null

        val record = runCatching {
            tvdbApi.getSeriesExtended(
                authorization = authorization,
                id = identity.tvdbId,
                meta = "translations",
                short = false
            )
        }.onFailure { error ->
            Log.w(TAG, "TVDB series metadata request failed reason=${error.javaClass.simpleName}")
        }.getOrNull()
            ?.takeIf { response -> response.isSuccessful }
            ?.body()
            ?.data
            ?: return@withContext null

        val enrichment = record.toEnrichment(identity, activeProvider) ?: return@withContext null
        metadataDiskCacheStore.writeTvdbEnrichment(
            seriesId = identity.tvdbId,
            recordKind = SERIES_EXTENDED_RECORD_KIND,
            languageTag = normalizedLanguage,
            providerToken = providerToken,
            enrichment = enrichment
        )
        enrichment
    }

    suspend fun fetchEpisodeEnrichment(
        identity: TvdbSeriesIdentity,
        seasonNumbers: List<Int>,
        language: String? = null
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> = withContext(Dispatchers.IO) {
        val distinctSeasons = seasonNumbers.distinct().sorted()
        if (distinctSeasons.isEmpty()) return@withContext emptyMap()

        val seasonEpisodes = coroutineScope {
            distinctSeasons.map { seasonNumber ->
                async {
                    fetchSeasonEpisodes(
                        identity = identity,
                        seasonNumber = seasonNumber,
                        language = language
                    )
                }
            }.awaitAll().flatten()
        }

        seasonEpisodes
            .map { it.metadata }
            .mapNotNull { metadata ->
                val seasonNumber = metadata.seasonNumber ?: return@mapNotNull null
                val episodeNumber = metadata.episodeNumber ?: return@mapNotNull null
                (seasonNumber to episodeNumber) to metadata
            }
            .toMap()
    }

    suspend fun fetchSeasonEpisodes(
        identity: TvdbSeriesIdentity,
        seasonNumber: Int,
        language: String? = null
    ): List<TvSeasonEpisode> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeLanguage(language)

        metadataDiskCacheStore.readTvdbSeasonEpisodes(
            seriesId = identity.tvdbId,
            seasonType = DEFAULT_SEASON_TYPE,
            seasonNumber = seasonNumber,
            languageTag = normalizedLanguage
        )?.let { cached ->
            return@withContext cached.map { metadata -> metadata.toSeasonEpisode() }
        }

        val authorization = authService.bearerToken() ?: return@withContext emptyList()
        val response = runCatching {
            tvdbApi.getSeriesEpisodes(
                authorization = authorization,
                id = identity.tvdbId,
                seasonType = DEFAULT_SEASON_TYPE,
                page = 0,
                season = seasonNumber
            )
        }.onFailure { error ->
            Log.w(TAG, "TVDB season metadata request failed reason=${error.javaClass.simpleName}")
        }.getOrNull() ?: return@withContext emptyList()

        if (!response.isSuccessful) {
            Log.w(TAG, "TVDB season metadata request failed status=${response.code()}")
            return@withContext emptyList()
        }

        val records = response.body()?.data.orEmpty()

        val mapped = records
            .map { record -> record.toEpisodeMetadata() }
            .filter { metadata -> metadata.seasonNumber == seasonNumber }
            .sortedWith(compareBy<TvEpisodeMetadata> { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.providerEpisodeId })

        metadataDiskCacheStore.writeTvdbSeasonEpisodes(
            seriesId = identity.tvdbId,
            seasonType = DEFAULT_SEASON_TYPE,
            seasonNumber = seasonNumber,
            languageTag = normalizedLanguage,
            episodes = mapped
        )

        mapped.map { metadata -> metadata.toSeasonEpisode() }
    }

    private fun TvdbSeriesExtendedRecord.toEnrichment(
        identity: TvdbSeriesIdentity,
        activeProvider: PosterRatingsUrlResolver.ActiveProvider?
    ): TvMetadataEnrichment? {
        val artwork = selectArtwork(artworks)
        val tvdbPoster = artwork.poster ?: image.trimmed()
        val poster = posterRatingsUrlResolver.resolvePosterUrl(
            originalPosterUrl = tvdbPoster,
            contentId = "tvdb:${identity.tvdbId}",
            contentType = ContentType.SERIES,
            activeProvider = activeProvider
        )
        val remoteIds = mergeRemoteIds(identity.remoteIds, remoteIds)
        val genres = genres.mapNotNull { it.name.trimmed() }
        val contentRatings = contentRatings.mapNotNull { it.name.trimmed() }
        val description = overview.trimmed()
        val title = name.trimmed()
        val countries = listOfNotNull(country.trimmed(), originalCountry.trimmed()).distinct()

        if (
            title == null && description == null && genres.isEmpty() && poster == null &&
            artwork.backdrop == null && artwork.logo == null && firstAired.trimmed() == null &&
            score == null && averageRuntime == null && contentRatings.isEmpty()
        ) {
            return null
        }

        return TvMetadataEnrichment(
            seriesTvdbId = identity.tvdbId,
            localizedTitle = title,
            description = description,
            genres = genres,
            backdrop = artwork.backdrop,
            logo = artwork.logo,
            poster = poster,
            releaseInfo = firstAired.trimmed(),
            rating = score,
            runtimeMinutes = averageRuntime,
            ageRating = contentRatings.firstOrNull(),
            countries = countries.takeIf { it.isNotEmpty() },
            language = originalLanguage.trimmed(),
            airsDays = airsDays.toMap(),
            airsTime = airsTime.trimmed(),
            averageRuntimeMinutes = averageRuntime,
            originalCountry = originalCountry.trimmed(),
            originalLanguage = originalLanguage.trimmed(),
            status = status?.name.trimmed(),
            aliases = aliases.mapNotNull { it.name.trimmed() },
            contentRatings = contentRatings,
            remoteIds = remoteIds
        )
    }

    private fun TvdbEpisodeRecord.toEpisodeMetadata(): TvEpisodeMetadata {
        return TvEpisodeMetadata(
            providerEpisodeId = id?.let { "tvdb:$it" },
            seasonNumber = seasonNumber,
            episodeNumber = number,
            title = name.trimmed(),
            overview = overview.trimmed(),
            thumbnail = image.trimmed(),
            airDate = aired.trimmed(),
            runtimeMinutes = runtime,
            absoluteNumber = absoluteNumber,
            airsAfterSeason = airsAfterSeason,
            airsBeforeSeason = airsBeforeSeason,
            airsBeforeEpisode = airsBeforeEpisode,
            linkedMovieTvdbId = linkedMovie,
            finaleType = finaleType.trimmed()
        )
    }

    private fun TvEpisodeMetadata.toSeasonEpisode(): TvSeasonEpisode {
        return TvSeasonEpisode(
            episodeNumber = episodeNumber,
            airDate = airDate,
            metadata = this
        )
    }

    private fun selectArtwork(artworks: List<TvdbArtworkRecord>): SelectedTvdbArtwork {
        val byQuality = artworks
            .filter { artwork -> artwork.image.trimmed() != null }
            .sortedByDescending { artwork -> artwork.score ?: 0.0 }

        return SelectedTvdbArtwork(
            poster = byQuality.firstImageForType(2),
            backdrop = byQuality.firstImageForType(3),
            logo = byQuality.firstImageForType(23)
        )
    }

    private fun List<TvdbArtworkRecord>.firstImageForType(type: Int): String? {
        return firstOrNull { artwork -> artwork.type == type }?.image.trimmed()
    }

    private fun mergeRemoteIds(
        identityRemoteIds: Map<TvdbRemoteIdSource, Set<String>>,
        recordRemoteIds: List<TvdbRemoteId>
    ): Map<String, Set<String>> {
        val grouped = linkedMapOf<String, MutableSet<String>>()

        identityRemoteIds.forEach { (source, values) ->
            val key = source.toMetadataKey()
            values.mapNotNullTo(grouped.getOrPut(key) { linkedSetOf() }) { it.trimmed() }
        }

        recordRemoteIds.forEach { remoteId ->
            val value = remoteId.id.trimmed() ?: return@forEach
            val source = normalizeTvdbRemoteIdSource(remoteId.sourceName).toMetadataKey()
            grouped.getOrPut(source) { linkedSetOf() }.add(value)
        }

        return grouped.mapValues { (_, values) -> values.toSet() }
    }

    private fun TvdbRemoteIdSource.toMetadataKey(): String = name.lowercase()

    private fun TvdbAirsDays?.toMap(): Map<String, Boolean> {
        if (this == null) return emptyMap()
        return linkedMapOf(
            "monday" to monday,
            "tuesday" to tuesday,
            "wednesday" to wednesday,
            "thursday" to thursday,
            "friday" to friday,
            "saturday" to saturday,
            "sunday" to sunday
        ).mapNotNull { (day, value) -> value?.let { day to it } }.toMap()
    }

    private fun posterProviderCacheToken(
        activeProvider: PosterRatingsUrlResolver.ActiveProvider?
    ): String {
        if (activeProvider == null) return "native"
        return "${activeProvider.provider.name}:${activeProvider.apiKey.hashCode()}"
    }

    private fun normalizeLanguage(language: String?): String {
        return language
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace('_', '-')
            ?: "en-US"
    }

    private fun String?.trimmed(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private data class SelectedTvdbArtwork(
        val poster: String?,
        val backdrop: String?,
        val logo: String?
    )
}
