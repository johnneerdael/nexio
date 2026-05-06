package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.toHomeDisplayMetadata

internal object HomeResolvedDisplayMapper {
    fun toResolvedDisplayItems(
        rows: List<CatalogRow>,
        overlaysByItemKey: Map<String, HydratedHomeOverlay>,
        nowMs: Long = System.currentTimeMillis()
    ): List<ResolvedDisplayItem> =
        rows.flatMap { row -> row.items }
            .map { item -> item.toResolvedDisplayItem(overlaysByItemKey, nowMs) }

    private fun MetaPreview.toResolvedDisplayItem(
        overlaysByItemKey: Map<String, HydratedHomeOverlay>,
        nowMs: Long
    ): ResolvedDisplayItem {
        val itemKey = homeDisplayItemKey(apiType, id)
        val overlay = overlaysByItemKey[itemKey]
        val fields = toHomeDisplayMetadata()
        val ratingSource = fields.ratingSource ?: TitleRatingSource.IMDB
        val stableIds = firstPaintStableIds.withOverlayStableId(overlay)

        return ResolvedDisplayItem(
            itemKey = itemKey,
            contentId = id,
            parentId = id,
            itemType = type,
            mediaKind = when (apiType.lowercase()) {
                "movie" -> MetadataMediaKind.MOVIE
                "series", "tv", "show" -> MetadataMediaKind.SERIES
                else -> MetadataMediaKind.UNKNOWN
            },
            canonicalProvider = overlay?.canonicalProvider?.name,
            canonicalId = overlay?.canonicalId,
            imdbId = stableIds.imdb,
            stableIds = stableIds,
            display = ResolvedDisplayFields(
                title = fields.title,
                originalTitle = null,
                year = fields.releaseInfo?.take(4)?.toIntOrNull(),
                releaseDate = fields.releaseInfo,
                overview = fields.description,
                genres = fields.genres,
                runtimeText = fields.runtime
            ),
            artwork = fields.artwork ?: ArtworkBundle(),
            rating = fields.imdbRating?.let { value -> TitleRating(value.toString().toDouble(), ratingSource) },
            trailer = TrailerDisplayState(),
            hydrationState = when {
                overlay == null -> HydrationState.PREVIEW_ONLY
                overlay.isStale(nowMs) -> HydrationState.STALE_READY
                else -> HydrationState.CANONICAL_READY
            },
            sourceTrace = overlay?.fieldTrace.orEmpty(),
            updatedAtMs = overlay?.updatedAtMs ?: nowMs
        )
    }

    private fun ProviderIds.withOverlayStableId(overlay: HydratedHomeOverlay?): ProviderIds {
        if (overlay == null) return this

        return when (overlay.canonicalProvider) {
            ProviderId.TMDB -> copy(
                imdb = overlay.imdbId ?: imdb,
                tmdb = overlay.canonicalId
            )
            ProviderId.TVDB -> copy(
                imdb = overlay.imdbId ?: imdb,
                tvdb = overlay.canonicalId
            )
            ProviderId.IMDB -> copy(imdb = overlay.canonicalId)
            ProviderId.TRAKT -> copy(
                imdb = overlay.imdbId ?: imdb,
                trakt = overlay.canonicalId
            )
            ProviderId.SIMKL -> copy(
                imdb = overlay.imdbId ?: imdb,
                simkl = overlay.canonicalId
            )
            ProviderId.KITSU -> copy(
                imdb = overlay.imdbId ?: imdb,
                kitsu = overlay.canonicalId
            )
            ProviderId.ADDON,
            ProviderId.MDBLIST -> copy(imdb = overlay.imdbId ?: imdb)
        }
    }
}

internal fun HydratedHomeOverlay.toResolvedDisplayItem(): ResolvedDisplayItem {
    val fields = this.fields
    val ratingSource = fields.ratingSource ?: TitleRatingSource.IMDB

    return ResolvedDisplayItem(
        itemKey = itemKey,
        contentId = canonicalId,
        parentId = canonicalId,
        itemType = contentType,
        mediaKind = contentType.toMetadataMediaKind(),
        canonicalProvider = canonicalProvider.name,
        canonicalId = canonicalId,
        imdbId = imdbId,
        stableIds = stableIdsFromCanonical(),
        display = ResolvedDisplayFields(
            title = fields.title,
            originalTitle = null,
            year = fields.releaseInfo?.take(4)?.toIntOrNull(),
            releaseDate = fields.releaseInfo,
            overview = fields.description,
            genres = fields.genres,
            runtimeText = fields.runtime
        ),
        artwork = fields.artwork ?: ArtworkBundle(),
        rating = fields.imdbRating?.let { value -> TitleRating(value.toDouble(), ratingSource) },
        trailer = TrailerDisplayState(),
        hydrationState = if (state == HomeItemHydrationState.STALE_READY) {
            HydrationState.STALE_READY
        } else {
            HydrationState.CANONICAL_READY
        },
        sourceTrace = fieldTrace,
        updatedAtMs = updatedAtMs
    )
}

private fun ContentType.toMetadataMediaKind(): MetadataMediaKind =
    when (this) {
        ContentType.MOVIE -> MetadataMediaKind.MOVIE
        ContentType.SERIES,
        ContentType.TV -> MetadataMediaKind.SERIES
        else -> MetadataMediaKind.UNKNOWN
    }

private fun HydratedHomeOverlay.stableIdsFromCanonical(): ProviderIds =
    when (canonicalProvider) {
        ProviderId.TMDB -> ProviderIds(tmdb = canonicalId, imdb = imdbId)
        ProviderId.TVDB -> ProviderIds(tvdb = canonicalId, imdb = imdbId)
        ProviderId.IMDB -> ProviderIds(imdb = canonicalId)
        ProviderId.TRAKT -> ProviderIds(trakt = canonicalId, imdb = imdbId)
        ProviderId.SIMKL -> ProviderIds(simkl = canonicalId, imdb = imdbId)
        ProviderId.KITSU -> ProviderIds(kitsu = canonicalId, imdb = imdbId)
        ProviderId.ADDON,
        ProviderId.MDBLIST -> ProviderIds(imdb = imdbId)
    }
