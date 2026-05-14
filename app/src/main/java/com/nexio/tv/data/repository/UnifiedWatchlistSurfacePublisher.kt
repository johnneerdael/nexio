package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkProviderResolver
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.UnifiedWatchlistMembership
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.ui.screens.home.HomeHydrationCoordinator
import com.nexio.tv.ui.screens.home.HomeHydrationPriority
import com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapper
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val UNIFIED_WATCHLIST_OVERLAY_POLICY_VERSION = 1

@Singleton
class UnifiedWatchlistSurfacePublisher @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileBoundary: ProfileBoundary,
    private val hydrationCoordinator: HomeHydrationCoordinator,
    private val overlayStore: HydratedHomeOverlayStore,
    private val resolvedDisplaySurfaceRepository: ResolvedDisplaySurfaceRepository,
    private val settingsSource: ArtworkProviderSettingsSource,
    private val artworkProviderResolver: ArtworkProviderResolver,
    private val traceEvents: TraceMetadataEvents
) {
    suspend fun publish(
        profileSession: ActiveProfileSession,
        memberships: List<UnifiedWatchlistMembership>
    ): Boolean {
        if (memberships.isEmpty()) {
            return resolvedDisplaySurfaceRepository.publishResolvedItems(
                surfaceKey = ResolvedDisplaySurfaceRepository.UNIFIED_WATCHLIST_SURFACE_KEY,
                profileSession = profileSession,
                items = emptyList(),
                replace = true
            )
        }

        val previews = ArrayList<MetaPreview>(memberships.size)
        for (i in memberships.indices) {
            val preview = memberships[i].toUnifiedWatchlistPreview()
            previews += preview
        }

        val languageTag = profileBoundary.currentLanguageTag()
        val expectedGeneration = profileSession.sessionOrdinal
        val currentSettings = settingsSource.settings.first()
        val previewResolvedItems = resolvedItemsFor(
            previews = previews,
            overlays = emptyMap(),
            currentSettings = currentSettings
        )
        val previewPublished = resolvedDisplaySurfaceRepository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.UNIFIED_WATCHLIST_SURFACE_KEY,
            profileSession = profileSession,
            items = previewResolvedItems,
            replace = true
        )
        for (i in previews.indices) {
            hydrationCoordinator.hydrate(
                item = previews[i],
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                priority = HomeHydrationPriority.VISIBLE,
                languageTag = languageTag,
                expectedGeneration = expectedGeneration,
                currentGeneration = {
                    val active = profileManager.activeProfileSession.value
                    if (active.profileId == profileSession.profileId && active.sessionId == profileSession.sessionId) {
                        expectedGeneration
                    } else {
                        Long.MIN_VALUE
                    }
                },
                onOverlayApplied = { true }
            )
        }

        val itemKeys = HashSet<String>(previews.size)
        for (i in previews.indices) {
            val preview = previews[i]
            itemKeys += homeDisplayItemKey(preview.apiType, preview.id)
        }
        val overlays = overlayStore.readForItemKeys(
            itemKeys = itemKeys,
            languageTag = languageTag,
            policyVersion = UNIFIED_WATCHLIST_OVERLAY_POLICY_VERSION,
            nowMs = System.currentTimeMillis()
        )
        val resolvedItems = resolvedItemsFor(
            previews = previews,
            overlays = overlays,
            currentSettings = currentSettings
        )
        val hydratedPublished = resolvedDisplaySurfaceRepository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.UNIFIED_WATCHLIST_SURFACE_KEY,
            profileSession = profileSession,
            items = resolvedItems,
            replace = true
        )
        return previewPublished || hydratedPublished
    }

    private fun resolvedItemsFor(
        previews: List<MetaPreview>,
        overlays: Map<String, com.nexio.tv.domain.model.HydratedHomeOverlay>,
        currentSettings: com.nexio.tv.domain.model.ArtworkProviderSettings
    ): List<com.nexio.tv.domain.model.ResolvedDisplayItem> {
        val row = CatalogRow(
            addonId = ResolvedDisplaySurfaceRepository.UNIFIED_WATCHLIST_SURFACE_KEY,
            addonName = "Unified Watchlist",
            addonBaseUrl = "",
            catalogId = ResolvedDisplaySurfaceRepository.UNIFIED_WATCHLIST_SURFACE_KEY,
            catalogName = "Unified Watchlist",
            type = ContentType.MOVIE,
            rawType = "movie",
            items = previews,
            hasMore = false
        )
        return HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row),
            overlaysByItemKey = overlays,
            resolver = artworkProviderResolver,
            currentSettings = currentSettings,
            traceEvents = traceEvents
        )
    }

    private fun UnifiedWatchlistMembership.toUnifiedWatchlistPreview(): MetaPreview {
        val stableIds = ProviderIds(
            imdb = imdbId,
            tmdb = tmdbId?.toString() ?: showTmdbId?.toString(),
            tvdb = tvdbId?.toString(),
            trakt = traktId?.toString(),
            simkl = simklId?.toString()
        )
        val typeKey = contentType.toAuthorityTypeKey()
        val contentId = when {
            imdbId != null -> "imdb:${imdbId.lowercase()}"
            tmdbId != null -> "tmdb:$tmdbId"
            showTmdbId != null -> "tmdb:$showTmdbId"
            tvdbId != null -> "tvdb:$tvdbId"
            traktId != null -> "trakt:$traktId"
            simklId != null -> "simkl:$simklId"
            authorityKey.startsWith("$typeKey:") -> authorityKey.removePrefix("$typeKey:")
            else -> authorityKey
        }
        return MetaPreview(
            id = contentId,
            type = contentType,
            rawType = contentType.toApiString(),
            name = title?.takeIf { it.isNotBlank() } ?: authorityKey,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = year?.toString(),
            imdbRating = null,
            genres = emptyList(),
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = null,
            firstPaintStableIds = stableIds,
            firstPaintRailSource = RailSource.UNIFIED_WATCHLIST,
            firstPaintSourceItemId = authorityKey
        )
    }

    private fun ContentType.toAuthorityTypeKey(): String =
        when (this) {
            ContentType.SERIES,
            ContentType.TV -> "series"
            ContentType.MOVIE -> "movie"
            else -> toApiString()
        }
}
