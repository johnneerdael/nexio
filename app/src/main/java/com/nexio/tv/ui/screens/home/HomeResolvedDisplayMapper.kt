package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.metadata.router.resolver.TrailerResolveRequest
import com.nexio.tv.core.metadata.router.resolver.TrailerResolution
import com.nexio.tv.core.metadata.router.resolver.TrailerSurface
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RatingValueValidator
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
        nowMs: Long = System.currentTimeMillis(),
        resolveTrailer: ((TrailerResolveRequest) -> TrailerResolution)? = null
    ): List<ResolvedDisplayItem> =
        rows.flatMap { row -> row.items }
            .map { item -> item.toResolvedDisplayItem(overlaysByItemKey, nowMs, resolveTrailer) }

    private fun MetaPreview.toResolvedDisplayItem(
        overlaysByItemKey: Map<String, HydratedHomeOverlay>,
        nowMs: Long,
        resolveTrailer: ((TrailerResolveRequest) -> TrailerResolution)?
    ): ResolvedDisplayItem {
        val itemKey = homeDisplayItemKey(apiType, id)
        val overlay = overlayFromMap(overlaysByItemKey)

        val firstPaintSlots = toFirstPaintSlots(nowMs)
        val overlaySlots = overlay?.toResolvedSlots(nowMs, isStale = overlay.isStale(nowMs))
        val mergedSlots = HomeRailProjectionReducer.reduce(
            firstPaint = firstPaintSlots,
            overlay = overlaySlots,
            existing = null,
            profile = null
        )

        val stableIds = firstPaintStableIds.withOverlayStableId(overlay)
        val title = mergedSlots.title.value ?: name
        val year = mergedSlots.releaseInfo.value?.take(4)?.takeIf { it.length == 4 }
        val trailerState = resolveHomeTrailerDisplayState(
            itemKey = itemKey,
            title = title,
            year = year,
            stableIds = stableIds,
            fallbackYtIds = trailerYtIds,
            apiType = apiType,
            contentId = id,
            resolveTrailer = resolveTrailer
        )

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
                title = title,
                originalTitle = mergedSlots.originalTitle.value,
                year = year?.toIntOrNull(),
                releaseDate = mergedSlots.releaseInfo.value,
                overview = mergedSlots.overview.value,
                genres = mergedSlots.genres.value.orEmpty(),
                runtimeText = mergedSlots.runtime.value
            ),
            artwork = ArtworkBundle(
                poster = mergedSlots.poster.value,
                backdrop = mergedSlots.backdrop.value,
                logo = mergedSlots.logo.value,
                thumbnail = mergedSlots.thumbnail.value
            ).enforceArtworkTypeBoundaries(),
            rating = mergedSlots.rating.value,
            trailer = trailerState,
            hydrationState = when {
                overlay == null -> HydrationState.PREVIEW_ONLY
                overlay.isStale(nowMs) -> HydrationState.STALE_READY
                else -> HydrationState.CANONICAL_READY
            },
            sourceTrace = overlay?.fieldTrace.orEmpty(),
            updatedAtMs = overlay?.updatedAtMs ?: nowMs,
            slots = mergedSlots
        )
    }

    private fun resolveHomeTrailerDisplayState(
        itemKey: String,
        title: String,
        year: String?,
        stableIds: ProviderIds,
        fallbackYtIds: List<String>,
        apiType: String,
        contentId: String,
        resolveTrailer: ((TrailerResolveRequest) -> TrailerResolution)?
    ): TrailerDisplayState {
        val resolver = resolveTrailer ?: return TrailerDisplayState()
        val resolution = resolver(
            TrailerResolveRequest(
                itemKey = itemKey,
                title = title,
                year = year,
                stableIds = stableIds,
                fallbackYtIds = fallbackYtIds,
                surface = TrailerSurface.HOME,
                type = apiType,
                contentId = contentId
            )
        )
        val selected = resolution.selected ?: return TrailerDisplayState(
            availabilityReason = resolution.availability.reason,
            surface = TrailerSurface.HOME.name.lowercase()
        )
        return TrailerDisplayState(
            fallbackTrailerYtIds = listOfNotNull((selected as? TrailerPlaybackRef.YouTubeId)?.videoId),
            selectedPlaybackRef = selected,
            availabilityReason = resolution.availability.reason,
            surface = TrailerSurface.HOME.name.lowercase()
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
        artwork = fields.toResolvedArtworkBundle(),
        rating = fields.imdbRating
            ?.takeIf { RatingValueValidator.validTitleRating(it) }
            ?.let { value -> TitleRating(value.toDouble(), ratingSource) },
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

private fun HomeDisplayMetadata.toResolvedArtworkBundle(): ArtworkBundle {
    val structured = artwork?.enforceArtworkTypeBoundaries() ?: ArtworkBundle()
    return ArtworkBundle(
        poster = structured.poster ?: poster.toLegacyArtworkRef(ArtworkType.POSTER),
        backdrop = structured.backdrop ?: backdrop.toLegacyArtworkRef(ArtworkType.BACKDROP),
        logo = structured.logo ?: logo.toLegacyArtworkRef(ArtworkType.LOGO),
        thumbnail = structured.thumbnail ?: thumbnail.toLegacyArtworkRef(ArtworkType.THUMBNAIL)
    ).enforceArtworkTypeBoundaries()
}

private fun String?.toLegacyArtworkRef(imageType: ArtworkType): ArtworkDisplayRef.LegacyString? =
    this
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { value ->
            ArtworkDisplayRef.LegacyString(
                value = value,
                imageType = imageType,
                trace = ArtworkTrace.empty()
            )
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
