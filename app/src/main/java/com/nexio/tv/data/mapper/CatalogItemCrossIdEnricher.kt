package com.nexio.tv.data.mapper

import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.metadata.router.AnimeIdScheme
import com.nexio.tv.core.metadata.router.IdMappingStore
import com.nexio.tv.core.metadata.router.MetadataIdParser
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ParsedMetadataId
import com.nexio.tv.core.metadata.router.StableIdBundleRequest
import com.nexio.tv.core.metadata.router.StableIdBundleResolver
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.homeDisplayItemKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Enriches a [MetaPreview]'s `firstPaintStableIds` with cross-provider IDs
 * resolved via the existing StableIdBundleResolver / IdMappingStore / anime
 * map infrastructure.
 *
 * Per the 2026-05-11 design spec: movies resolve imdb through TMDB
 * external_ids; series resolve imdb through TVDB extended remoteIds; kitsu
 * items resolve via the in-memory AnimeIdMappingService (no network).
 *
 * Two entry points:
 *  - [enrichFromCache] — sync, cache-only (Room lookup + anime map for kitsu).
 *    Safe to call on the producer hot path. Returns the original [MetaPreview]
 *    reference on cache miss for downstream reference stability.
 *  - [enrichResolving] — async. Fires the StableIdBundleResolver, which may
 *    query TMDB/TVDB over the network. On success, the mapping is persisted
 *    via IdMappingStore and [resolutionUpdates] emits the resolved IDs.
 */
@Singleton
class CatalogItemCrossIdEnricher @Inject constructor(
    private val idMappingStore: IdMappingStore,
    private val stableIdBundleResolver: StableIdBundleResolver,
    private val animeIdMappingService: AnimeIdMappingService,
    private val overlayStore: HydratedHomeOverlayStore
) {
    private val _resolutionUpdates = MutableSharedFlow<CrossIdResolutionEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val resolutionUpdates: SharedFlow<CrossIdResolutionEvent> = _resolutionUpdates.asSharedFlow()

    suspend fun enrichFromCache(preview: MetaPreview): MetaPreview {
        if (!preview.firstPaintStableIds.imdb.isNullOrBlank()) return preview
        val mediaKind = preview.type.toMediaKind() ?: return preview
        val parsed = MetadataIdParser.parse(preview.id)

        return when (parsed.scheme) {
            AnimeIdScheme.KITSU -> enrichFromAnimeMap(preview, parsed)
            AnimeIdScheme.TMDB -> enrichTmdbFromStore(preview, parsed, mediaKind)
            AnimeIdScheme.TVDB -> enrichTvdbFromStore(preview, parsed, mediaKind)
            AnimeIdScheme.IMDB -> preview
            else -> preview
        }
    }

    suspend fun enrichResolving(preview: MetaPreview): MetaPreview {
        val cached = enrichFromCache(preview)
        if (cached !== preview) return cached

        val parsed = MetadataIdParser.parse(preview.id)
        val mediaKind = preview.type.toMediaKind() ?: return preview
        if (parsed.scheme == AnimeIdScheme.IMDB || parsed.scheme == AnimeIdScheme.KITSU) return preview

        val routeProvider = when (parsed.scheme) {
            AnimeIdScheme.TMDB -> MetadataPrimaryProvider.TMDB
            AnimeIdScheme.TVDB -> MetadataPrimaryProvider.TVDB
            else -> return preview
        }
        val sourceProvider = when (parsed.scheme) {
            AnimeIdScheme.TMDB -> ProviderId.TMDB
            AnimeIdScheme.TVDB -> ProviderId.TVDB
            else -> null
        }

        // The StableIdResolutionTrigger enum has no RAIL_ITEM_ENRICHMENT value;
        // VISIBLE_HOME_HYDRATION is the closest semantic match (producer-side
        // enrichment from the home hot path). Telemetry-only; not semantic.
        val bundle = stableIdBundleResolver.resolve(
            StableIdBundleRequest(
                itemKey = "${preview.apiType}:${preview.id}",
                itemType = preview.type,
                routeProvider = routeProvider,
                knownIds = preview.firstPaintStableIds,
                sourceProvider = sourceProvider,
                sourceItemId = preview.id,
                railId = null,
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION
            )
        )

        val current = preview.firstPaintStableIds
        val enrichedIds = current.copy(
            imdb = current.imdb ?: bundle.sidecars.imdbId,
            tmdb = current.tmdb ?: bundle.canonical.tmdbMovieId,
            tvdb = current.tvdb ?: bundle.canonical.tvdbSeriesId,
            kitsu = current.kitsu ?: bundle.canonical.kitsuAnimeId
        )
        if (enrichedIds == current) return preview

        val enriched = preview.copy(firstPaintStableIds = enrichedIds)
        overlayStore.markStaleIfWeakerIds(
            itemKey = homeDisplayItemKey(preview.apiType, preview.id),
            currentIds = enrichedIds
        )
        _resolutionUpdates.tryEmit(
            CrossIdResolutionEvent(
                itemKey = "${preview.apiType}:${preview.id}",
                from = current,
                to = enrichedIds
            )
        )
        return enriched
    }

    private suspend fun enrichTmdbFromStore(
        preview: MetaPreview,
        parsed: ParsedMetadataId,
        mediaKind: ContentMediaKind
    ): MetaPreview {
        // IdMappingStore is keyed by (scheme + normalized value). For TMDB,
        // typed-scope values like "movie:202555" / "tv:1399" are the canonical
        // shape — see CatalogItemCrossIdEnricherTest fixture which stores
        // sourceId = ParsedMetadataId(TMDB, "movie:202555", ...).
        val typedValue = "${if (mediaKind == ContentMediaKind.MOVIE) "movie" else "tv"}:${parsed.value}"
        val sourceId = ParsedMetadataId(
            scheme = AnimeIdScheme.TMDB,
            value = typedValue,
            raw = parsed.raw
        )
        val imdbMapping = idMappingStore.lookup(MetadataPrimaryProvider.IMDB, sourceId) ?: return preview
        val tvdbMapping = idMappingStore.lookup(MetadataPrimaryProvider.TVDB, sourceId)
        val current = preview.firstPaintStableIds
        val enrichedIds = current.copy(
            imdb = imdbMapping.providerId,
            tvdb = current.tvdb ?: tvdbMapping?.providerId
        )
        if (enrichedIds == current) return preview
        overlayStore.markStaleIfWeakerIds(
            itemKey = homeDisplayItemKey(preview.apiType, preview.id),
            currentIds = enrichedIds
        )
        return preview.copy(firstPaintStableIds = enrichedIds)
    }

    private suspend fun enrichTvdbFromStore(
        preview: MetaPreview,
        parsed: ParsedMetadataId,
        mediaKind: ContentMediaKind
    ): MetaPreview {
        if (mediaKind != ContentMediaKind.SERIES) return preview
        val sourceId = ParsedMetadataId(AnimeIdScheme.TVDB, parsed.value, parsed.raw)
        val imdbMapping = idMappingStore.lookup(MetadataPrimaryProvider.IMDB, sourceId) ?: return preview
        val current = preview.firstPaintStableIds
        val enrichedIds = current.copy(imdb = imdbMapping.providerId)
        if (enrichedIds == current) return preview
        overlayStore.markStaleIfWeakerIds(
            itemKey = homeDisplayItemKey(preview.apiType, preview.id),
            currentIds = enrichedIds
        )
        return preview.copy(firstPaintStableIds = enrichedIds)
    }

    private suspend fun enrichFromAnimeMap(preview: MetaPreview, parsed: ParsedMetadataId): MetaPreview {
        val record = animeIdMappingService.recordForKitsuId(parsed.value) ?: return preview
        val ids = preview.firstPaintStableIds
        val enriched = ids.copy(
            imdb = ids.imdb ?: record.imdb,
            tmdb = ids.tmdb ?: record.tmdb,
            tvdb = ids.tvdb ?: record.tvdb,
            mal = ids.mal ?: record.mal,
            anilist = ids.anilist ?: record.anilist,
            anidb = ids.anidb ?: record.anidb,
            kitsu = ids.kitsu ?: record.kitsu
        )
        if (enriched == ids) return preview
        overlayStore.markStaleIfWeakerIds(
            itemKey = homeDisplayItemKey(preview.apiType, preview.id),
            currentIds = enriched
        )
        return preview.copy(firstPaintStableIds = enriched)
    }

    private fun ContentType.toMediaKind(): ContentMediaKind? = when (this) {
        ContentType.MOVIE -> ContentMediaKind.MOVIE
        ContentType.SERIES, ContentType.TV -> ContentMediaKind.SERIES
        ContentType.CHANNEL, ContentType.PERSON, ContentType.UNKNOWN -> null
    }
}

data class CrossIdResolutionEvent(
    val itemKey: String,
    val from: ProviderIds,
    val to: ProviderIds
)
