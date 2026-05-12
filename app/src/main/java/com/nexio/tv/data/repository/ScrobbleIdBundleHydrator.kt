package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdBundleRequest
import com.nexio.tv.core.metadata.router.StableIdBundleResolver
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the full ProviderIds bundle for a playback contentId at scrobble-emit time.
 *
 * The player passes whatever ID surfaced from the catalog producer (often a single TMDB
 * or addon id). This hydrator asks the metadata router for the canonical + sidecar +
 * observed IDs so the outgoing scrobble payload can carry IMDB, TMDB, TVDB, Kitsu, MAL,
 * AniList, AniDB, Trakt, and Simkl together. When the resolver fails, the raw contentId
 * is parsed into a single-ID ProviderIds so the scrobble can still ship with what we
 * already know.
 */
@Singleton
class ScrobbleIdBundleHydrator @Inject constructor(
    private val resolver: StableIdBundleResolver,
) {
    suspend fun hydrate(rawContentId: String, contentType: String?): ProviderIds {
        val itemType = contentTypeFrom(contentType)
        val parsed = parseRawContentId(rawContentId)
        val route = primaryRouteFor(parsed)
        val request = StableIdBundleRequest(
            itemKey = parsed.bestStableItemKey(itemType, sourceItemId = rawContentId),
            itemType = itemType,
            routeProvider = route,
            knownIds = parsed,
            sourceProvider = route.toProviderId(),
            sourceItemId = rawContentId,
            railId = null,
            trigger = StableIdResolutionTrigger.PLAYER_START,
        )
        return runCatching { resolver.resolve(request) }
            .map { it.toProviderIds(parsed) }
            .getOrElse { parsed }
    }

    private fun contentTypeFrom(value: String?): ContentType = when (value?.trim()?.lowercase()) {
        "movie" -> ContentType.MOVIE
        "tv", "series", "show" -> ContentType.SERIES
        else -> ContentType.SERIES
    }

    private fun parseRawContentId(raw: String): ProviderIds {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("tt") -> ProviderIds(imdb = trimmed)
            trimmed.startsWith("tmdb:") -> ProviderIds(tmdb = trimmed.removePrefix("tmdb:"))
            trimmed.startsWith("tvdb:") -> ProviderIds(tvdb = trimmed.removePrefix("tvdb:"))
            trimmed.startsWith("kitsu:") -> ProviderIds(kitsu = trimmed.removePrefix("kitsu:"))
            trimmed.startsWith("mal:") -> ProviderIds(mal = trimmed.removePrefix("mal:"))
            trimmed.startsWith("anilist:") -> ProviderIds(anilist = trimmed.removePrefix("anilist:"))
            trimmed.startsWith("anidb:") -> ProviderIds(anidb = trimmed.removePrefix("anidb:"))
            trimmed.startsWith("trakt:") -> ProviderIds(trakt = trimmed.removePrefix("trakt:"))
            trimmed.startsWith("simkl:") -> ProviderIds(simkl = trimmed.removePrefix("simkl:"))
            trimmed.toIntOrNull() != null -> ProviderIds(trakt = trimmed)
            else -> ProviderIds()
        }
    }

    private fun primaryRouteFor(ids: ProviderIds): MetadataPrimaryProvider = when {
        ids.imdb != null -> MetadataPrimaryProvider.IMDB
        ids.tmdb != null -> MetadataPrimaryProvider.TMDB
        ids.tvdb != null -> MetadataPrimaryProvider.TVDB
        ids.kitsu != null -> MetadataPrimaryProvider.KITSU
        ids.trakt != null -> MetadataPrimaryProvider.TRAKT
        ids.simkl != null -> MetadataPrimaryProvider.SIMKL
        else -> MetadataPrimaryProvider.TMDB
    }

    private fun MetadataPrimaryProvider.toProviderId(): ProviderId? = when (this) {
        MetadataPrimaryProvider.TMDB -> ProviderId.TMDB
        MetadataPrimaryProvider.TVDB -> ProviderId.TVDB
        MetadataPrimaryProvider.IMDB -> ProviderId.IMDB
        MetadataPrimaryProvider.KITSU -> ProviderId.KITSU
        MetadataPrimaryProvider.TRAKT -> ProviderId.TRAKT
        MetadataPrimaryProvider.SIMKL -> ProviderId.SIMKL
        MetadataPrimaryProvider.RPDB,
        MetadataPrimaryProvider.TOP_POSTERS -> null
    }

    private fun StableIdBundle.toProviderIds(seed: ProviderIds): ProviderIds = ProviderIds(
        imdb = sidecars.imdbId ?: seed.imdb,
        tmdb = canonical.tmdbMovieId ?: source.observedIds.tmdb ?: seed.tmdb,
        tvdb = canonical.tvdbSeriesId ?: source.observedIds.tvdb ?: seed.tvdb,
        trakt = source.observedIds.trakt ?: seed.trakt,
        simkl = source.observedIds.simkl ?: seed.simkl,
        kitsu = canonical.kitsuAnimeId ?: source.observedIds.kitsu ?: seed.kitsu,
        slug = source.observedIds.slug ?: seed.slug,
        mal = sidecars.malId ?: seed.mal,
        anilist = sidecars.anilistId ?: seed.anilist,
        anidb = sidecars.anidbId ?: seed.anidb,
    )
}
