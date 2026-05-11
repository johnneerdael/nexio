package com.nexio.tv.ui.screens.home

import androidx.annotation.VisibleForTesting
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderResolver
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.core.metadata.router.IdMappingStore
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.metadata.router.resolver.TrailerResolveRequest
import com.nexio.tv.core.metadata.router.resolver.TrailerResolution
import com.nexio.tv.core.metadata.router.resolver.TrailerSurface
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ArtworkProviderSettings
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
import com.nexio.tv.domain.model.RailSource
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
import com.nexio.tv.domain.model.toMetadataMediaKind
import com.nexio.tv.domain.model.toSettingsSignature

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
        val overlayContentHash: Int,
        /**
         * Settings signature captured at mapper-call time. Different artwork-provider
         * settings select different `preferredArtworkProviders` for the same
         * (meta, overlay) tuple, so two emissions with identical content but
         * different settings must NOT collide on the cache. We use the cheap
         * pre-existing [toSettingsSignature] (4 short fields) rather than the
         * full settings hashCode to avoid pinning credential text in the key.
         */
        val settingsSignature: String
    )

    private val cache = mutableMapOf<MapperCacheKey, ResolvedDisplayItem>()

    @Synchronized
    fun toResolvedDisplayItems(
        rows: List<CatalogRow>,
        overlaysByItemKey: Map<String, HydratedHomeOverlay>,
        // Defaults exist so the large body of existing mapper tests (which
        // pre-date Plan B Task 11) continue to compile without 20+ test-site
        // edits. Production call sites in [HomeViewModelCatalogPipeline]
        // always pass an explicit injected [ArtworkProviderResolver] and the
        // current snapshot from [HomeViewModel.currentArtworkProviderSettings].
        resolver: ArtworkProviderResolver = ArtworkProviderResolver(
            com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolver()
        ),
        currentSettings: ArtworkProviderSettings = ArtworkProviderSettings(),
        nowMs: Long = System.currentTimeMillis(),
        resolveTrailer: ((TrailerResolveRequest) -> TrailerResolution)? = null,
        traceEvents: TraceMetadataEvents? = null
    ): List<ResolvedDisplayItem> {
        val items = rows.flatMap { row -> row.items }
        val activeKeys = HashSet<MapperCacheKey>(items.size)
        val settingsSignature = currentSettings.toSettingsSignature()
        val result = items.map { item ->
            val itemKey = homeDisplayItemKey(item.apiType, item.id)
            val overlay = overlaysByItemKey[itemKey]
            val cacheKey = MapperCacheKey(
                itemKey = itemKey,
                metaContentHash = item.hashCode(),
                overlayContentHash = overlay?.hashCode() ?: 0,
                settingsSignature = settingsSignature
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
                    overlaysByItemKey, nowMs, resolveTrailer, traceEvents,
                    resolver, currentSettings
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

    /**
     * Cross-id-aware variant of [toResolvedDisplayItems]. Consults [idMappingStore] when
     * looking up the hydrated overlay for each row item, enriching tmdb/tvdb-keyed rows
     * with any cached imdb-form alias so they can match overlays stored under a different
     * provider key.
     *
     * This overload is `suspend` because the [IdMappingStore] lookup is async. Callers in
     * coroutine contexts (e.g. [HomeViewModelCatalogPipeline]) should prefer this overload.
     * The existing non-suspend [toResolvedDisplayItems] remains unchanged for compatibility
     * with test helpers and non-coroutine call sites.
     */
    suspend fun toResolvedDisplayItemsEnriched(
        rows: List<CatalogRow>,
        overlaysByItemKey: Map<String, HydratedHomeOverlay>,
        idMappingStore: IdMappingStore,
        // See [toResolvedDisplayItems] for the default-arg rationale.
        resolver: ArtworkProviderResolver = ArtworkProviderResolver(
            com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolver()
        ),
        currentSettings: ArtworkProviderSettings = ArtworkProviderSettings(),
        nowMs: Long = System.currentTimeMillis(),
        resolveTrailer: ((TrailerResolveRequest) -> TrailerResolution)? = null,
        traceEvents: TraceMetadataEvents? = null
    ): List<ResolvedDisplayItem> {
        val items = rows.flatMap { row -> row.items }
        // Collect cache hits synchronously first; misses need suspend overlay lookup.
        val result = mutableListOf<ResolvedDisplayItem>()
        val activeKeys = HashSet<MapperCacheKey>(items.size)
        val settingsSignature = currentSettings.toSettingsSignature()
        for (i in items.indices) {
            val item = items[i]
            val itemKey = homeDisplayItemKey(item.apiType, item.id)
            val overlay = item.overlayFromMapEnriched(overlaysByItemKey, idMappingStore)
            val cacheKey = MapperCacheKey(
                itemKey = itemKey,
                metaContentHash = item.hashCode(),
                overlayContentHash = overlay?.hashCode() ?: 0,
                settingsSignature = settingsSignature
            )
            activeKeys += cacheKey
            val cached = synchronized(this) { cache[cacheKey] }
            if (cached != null) {
                result += cached
            } else {
                val computed = item.toResolvedDisplayItemWithOverlay(
                    overlay, nowMs, resolveTrailer, traceEvents,
                    resolver, currentSettings
                )
                synchronized(this) { cache[cacheKey] = computed }
                result += computed
            }
        }
        synchronized(this) { cache.keys.retainAll(activeKeys) }
        return result
    }

    private fun MetaPreview.toResolvedDisplayItem(
        overlaysByItemKey: Map<String, HydratedHomeOverlay>,
        nowMs: Long,
        resolveTrailer: ((TrailerResolveRequest) -> TrailerResolution)?,
        traceEvents: TraceMetadataEvents? = null,
        resolver: ArtworkProviderResolver,
        currentSettings: ArtworkProviderSettings
    ): ResolvedDisplayItem {
        val overlay = overlayFromMap(overlaysByItemKey)
        return toResolvedDisplayItemWithOverlay(
            overlay, nowMs, resolveTrailer, traceEvents, resolver, currentSettings
        )
    }

    private fun MetaPreview.toResolvedDisplayItemWithOverlay(
        overlay: HydratedHomeOverlay?,
        nowMs: Long,
        resolveTrailer: ((TrailerResolveRequest) -> TrailerResolution)?,
        traceEvents: TraceMetadataEvents? = null,
        resolver: ArtworkProviderResolver,
        currentSettings: ArtworkProviderSettings
    ): ResolvedDisplayItem {
        val itemKey = homeDisplayItemKey(apiType, id)

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

        val isAnime = isAnimeForArtworkRouting()
        val preferred = mapOf(
            ArtworkType.POSTER to resolver.resolve(
                ArtworkType.POSTER, type, isAnime, stableIds, currentSettings
            ),
            ArtworkType.BACKDROP to resolver.resolve(
                ArtworkType.BACKDROP, type, isAnime, stableIds, currentSettings
            ),
            ArtworkType.LOGO to resolver.resolve(
                ArtworkType.LOGO, type, isAnime, stableIds, currentSettings
            ),
            ArtworkType.THUMBNAIL to resolver.resolve(
                ArtworkType.THUMBNAIL, type, isAnime, stableIds, currentSettings
            )
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
            slots = mergedSlots,
            preferredArtworkProviders = preferred
        )
    }

    /**
     * Anime detection at the mapper boundary. Used to drive
     * [ArtworkProviderResolver]'s anime-aware default routing for portrait
     * cards, backdrops, and logos. Two signals are considered equivalent:
     *  1. [MetaPreview.apiType] == `"anime"` (case-insensitive), which is the
     *     Stremio addon-side convention preserved on the producer row.
     *  2. [MetaPreview.firstPaintRailSource] == [RailSource.BUILT_IN_KITSU],
     *     the built-in Kitsu discovery rail used for the anime catalog.
     * No `RailSource.KITSU` exists; the enum's anime entry is named
     * [RailSource.BUILT_IN_KITSU].
     */
    private fun MetaPreview.isAnimeForArtworkRouting(): Boolean =
        apiType.equals("anime", ignoreCase = true) ||
            firstPaintRailSource == RailSource.BUILT_IN_KITSU

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
        slots = overlaySlots,
        // TODO(artwork-routing): overlay-only path lacks the MetaPreview row
        // context (apiType / firstPaintRailSource) needed for anime detection,
        // so we cannot reliably compute preferredArtworkProviders here. The
        // empty map is intentional: this extension is used during cold-start
        // restore of a snapshot before any producer emission has run. The
        // next producer emission flushes through the MetaPreview-aware path
        // ([HomeResolvedDisplayMapper.toResolvedDisplayItems(Enriched)]),
        // which overwrites this projection with the correct preferred-
        // providers map. The surface tie-breaker (T12) falls back to
        // "newer wins" for items still on this projection, which is a safe
        // first-paint default until the producer-aware path takes over.
        preferredArtworkProviders = emptyMap()
    )
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
