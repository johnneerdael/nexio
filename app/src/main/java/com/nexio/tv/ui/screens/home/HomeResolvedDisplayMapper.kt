package com.nexio.tv.ui.screens.home

import androidx.annotation.VisibleForTesting
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
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RatingValueValidator
import com.nexio.tv.data.repository.toArtworkBundle
import com.nexio.tv.data.repository.toRating
import com.nexio.tv.data.repository.toResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.toHomeDisplayMetadata

internal object HomeResolvedDisplayMapper {

    /**
     * Cache key for memoization. We rely on structural equality of the underlying
     * [MetaPreview] and [HydratedHomeOverlay] (both `data class`-shaped) by hashing
     * them with [Any.hashCode]. Two distinct content tuples could theoretically
     * collide on hash; to defend against that, we store/look up under a key that
     * includes the [itemKey] plus both content hashes — collisions across
     * different itemKeys are isolated, and within an itemKey a hash collision on
     * both meta and overlay is extremely unlikely. If a collision ever occurred,
     * the worst case is a single stale-but-content-shaped projection on a single
     * key — far cheaper than the GC death-spiral the cache prevents.
     */
    private data class MapperCacheKey(
        val itemKey: String,
        val metaContentHash: Int,
        val overlayContentHash: Int
    )

    private val cache = mutableMapOf<MapperCacheKey, ResolvedDisplayItem>()

    @Synchronized
    fun toResolvedDisplayItems(
        rows: List<CatalogRow>,
        overlaysByItemKey: Map<String, HydratedHomeOverlay>,
        nowMs: Long = System.currentTimeMillis(),
        resolveTrailer: ((TrailerResolveRequest) -> TrailerResolution)? = null,
        traceEvents: TraceMetadataEvents? = null
    ): List<ResolvedDisplayItem> {
        val items = rows.flatMap { row -> row.items }
        val activeKeys = HashSet<MapperCacheKey>(items.size)
        val result = items.map { item ->
            val itemKey = homeDisplayItemKey(item.apiType, item.id)
            val overlay = overlaysByItemKey[itemKey]
            val cacheKey = MapperCacheKey(
                itemKey = itemKey,
                metaContentHash = item.hashCode(),
                overlayContentHash = overlay?.hashCode() ?: 0
            )
            activeKeys += cacheKey
            // Cache hit: skip recomputation entirely. We deliberately do NOT emit
            // a projection trace on hit because nothing was resolved — the cached
            // item's slots/updatedAtMs are reused verbatim, so there is no new
            // projection event to record. Trace emission only happens on miss
            // inside [toResolvedDisplayItem].
            val cached = cache[cacheKey]
            if (cached != null) {
                cached
            } else {
                val computed = item.toResolvedDisplayItem(
                    overlaysByItemKey, nowMs, resolveTrailer, traceEvents
                )
                cache[cacheKey] = computed
                computed
            }
        }
        // Bound the cache to entries whose itemKey was in this batch's active set
        // — this keeps cache memory proportional to the live home surface.
        cache.keys.retainAll(activeKeys)
        return result
    }

    @Synchronized
    @VisibleForTesting
    internal fun clearCacheForTest() {
        cache.clear()
    }

    private fun MetaPreview.toResolvedDisplayItem(
        overlaysByItemKey: Map<String, HydratedHomeOverlay>,
        nowMs: Long,
        resolveTrailer: ((TrailerResolveRequest) -> TrailerResolution)?,
        traceEvents: TraceMetadataEvents? = null
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

        if (traceEvents != null) {
            emitProjectionTrace(
                traceEvents = traceEvents,
                itemKey = itemKey,
                sourceRail = null,
                firstPaint = firstPaintSlots,
                overlay = overlaySlots,
                merged = mergedSlots
            )
        }

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
            display = mergedSlots.toResolvedDisplayFields(
                fallbackTitle = name,
                fallbackTomatoesRating = overlay?.fields?.tomatoesRating
            ),
            artwork = mergedSlots.toArtworkBundle(),
            rating = mergedSlots.toRating(),
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

    private fun emitProjectionTrace(
        traceEvents: TraceMetadataEvents,
        itemKey: String,
        sourceRail: String?,
        firstPaint: ResolvedDisplayFieldSlots,
        overlay: ResolvedDisplayFieldSlots?,
        merged: ResolvedDisplayFieldSlots
    ) {
        traceEvents.emitHomeDisplayProjection(
            itemKey = itemKey,
            sourceRail = sourceRail,
            firstPaintSummary = mapOf(
                "poster" to (firstPaint.poster.rank != DisplaySourceRank.EMPTY),
                "backdrop" to (firstPaint.backdrop.rank != DisplaySourceRank.EMPTY),
                "logo" to (firstPaint.logo.rank != DisplaySourceRank.EMPTY),
                "rating" to firstPaint.rating.value?.value
            ),
            overlaySummary = overlay?.let {
                mapOf(
                    "found" to true,
                    "poster" to it.poster.provider,
                    "backdrop" to it.backdrop.provider,
                    "logo" to it.logo.provider,
                    "rating" to it.rating.provider
                )
            },
            existingSummary = null,
            selectedSummary = mapOf(
                "poster" to mapOf("provider" to merged.poster.provider, "rank" to merged.poster.rank.name),
                "backdrop" to mapOf("provider" to merged.backdrop.provider, "rank" to merged.backdrop.rank.name),
                "logo" to mapOf("provider" to merged.logo.provider, "rank" to merged.logo.rank.name),
                "rating" to mapOf("provider" to merged.rating.provider, "rank" to merged.rating.rank.name)
            ),
            firstPaintSuppressedSummary = mapOf(
                "poster" to (merged.poster.rank.ordinal > DisplaySourceRank.FIRST_PAINT.ordinal && firstPaint.poster.rank == DisplaySourceRank.FIRST_PAINT),
                "backdrop" to (merged.backdrop.rank.ordinal > DisplaySourceRank.FIRST_PAINT.ordinal && firstPaint.backdrop.rank == DisplaySourceRank.FIRST_PAINT),
                "logo" to (merged.logo.rank.ordinal > DisplaySourceRank.FIRST_PAINT.ordinal && firstPaint.logo.rank == DisplaySourceRank.FIRST_PAINT)
            )
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

internal fun HydratedHomeOverlay.toResolvedDisplayItem(
    nowMs: Long = System.currentTimeMillis()
): ResolvedDisplayItem {
    val fields = this.fields
    val ratingSource = fields.ratingSource ?: TitleRatingSource.IMDB
    // Populate the typed `slots` bag so the ResolvedDisplaySurfaceRepository
    // boundary's applyNonDowngradeMerge can rank-check this overlay-derived
    // item against the previously-published state. Without slots, the boundary
    // falls through to the legacy verbatim-replace path and drops any prior
    // RESOLVED state — observed 2026-05-11 as a Trakt-rail ping-pong between
    // hydrated and first-paint metadata on this exact code path.
    val overlaySlots = toResolvedSlots(nowMs, isStale = isStale(nowMs))

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
            runtimeText = fields.runtime,
            tomatoesRating = fields.tomatoesRating
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
        updatedAtMs = updatedAtMs,
        slots = overlaySlots
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
