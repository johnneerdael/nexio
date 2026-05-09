package com.nexio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nexio.tv.R
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.RailHydrationState
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.orDefault
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CoreLayoutPrefs(
    val layout: HomeLayout,
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val posterLabelsEnabled: Boolean,
    val catalogAddonNameEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean
)

private data class FocusedBackdropPrefs(
    val expandEnabled: Boolean,
    val expandDelaySeconds: Int,
    val trailerEnabled: Boolean,
    val trailerMuted: Boolean,
    val trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget
)

private data class LayoutUiPrefs(
    val layout: HomeLayout,
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val posterLabelsEnabled: Boolean,
    val catalogAddonNameEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val modernLandscapePostersEnabled: Boolean,
    val focusedBackdropExpandEnabled: Boolean,
    val focusedBackdropExpandDelaySeconds: Int,
    val focusedBackdropTrailerEnabled: Boolean,
    val focusedBackdropTrailerMuted: Boolean,
    val focusedBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    val posterCardWidthDp: Int,
    val posterCardHeightDp: Int,
    val posterCardCornerRadiusDp: Int
)

internal data class HomeTrailerSurfaceTraceSummary(
    val itemCount: Int,
    val selectedRefCount: Int,
    val youtubeRefCount: Int,
    val inAppRefCount: Int,
    val externalRefCount: Int,
    val fallbackIdCount: Int
)

internal fun shouldEmitHomeTrailerSurfaceTrace(
    previous: HomeTrailerSurfaceTraceSummary?,
    current: HomeTrailerSurfaceTraceSummary
): Boolean = previous != current

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeLayoutPreferencesPipeline() {
    val coreLayoutPrefsFlow = combine(
        combine(
            layoutPreferenceDataStore.selectedLayout,
            layoutPreferenceDataStore.heroCatalogSelections,
            layoutPreferenceDataStore.heroSectionEnabled,
            layoutPreferenceDataStore.posterLabelsEnabled,
            layoutPreferenceDataStore.catalogAddonNameEnabled
        ) { layout, heroCatalogKeys, heroSectionEnabled, posterLabelsEnabled, catalogAddonNameEnabled ->
            CoreLayoutPrefs(
                layout = layout,
                heroCatalogKeys = heroCatalogKeys,
                heroSectionEnabled = heroSectionEnabled,
                posterLabelsEnabled = posterLabelsEnabled,
                catalogAddonNameEnabled = catalogAddonNameEnabled,
                catalogTypeSuffixEnabled = true
            )
        },
        layoutPreferenceDataStore.catalogTypeSuffixEnabled
    ) { corePrefs, catalogTypeSuffixEnabled ->
        corePrefs.copy(
            catalogTypeSuffixEnabled = catalogTypeSuffixEnabled
        )
    }

    val focusedBackdropPrefsFlow = combine(
        layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled,
        layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds,
        layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled,
        layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted,
        layoutPreferenceDataStore.focusedPosterBackdropTrailerPlaybackTarget
    ) { expandEnabled, expandDelaySeconds, trailerEnabled, trailerMuted, trailerPlaybackTarget ->
        FocusedBackdropPrefs(
            expandEnabled = expandEnabled,
            expandDelaySeconds = expandDelaySeconds,
            trailerEnabled = trailerEnabled,
            trailerMuted = trailerMuted,
            trailerPlaybackTarget = trailerPlaybackTarget
        )
    }

    val modernLayoutPrefsFlow = layoutPreferenceDataStore.modernLandscapePostersEnabled

    val baseLayoutUiPrefsFlow = combine(
        coreLayoutPrefsFlow,
        focusedBackdropPrefsFlow,
        layoutPreferenceDataStore.posterCardWidthDp,
        layoutPreferenceDataStore.posterCardHeightDp,
        layoutPreferenceDataStore.posterCardCornerRadiusDp
    ) { corePrefs, focusedBackdropPrefs, posterCardWidthDp, posterCardHeightDp, posterCardCornerRadiusDp ->
        LayoutUiPrefs(
            layout = corePrefs.layout,
            heroCatalogKeys = corePrefs.heroCatalogKeys,
            heroSectionEnabled = corePrefs.heroSectionEnabled,
            posterLabelsEnabled = corePrefs.posterLabelsEnabled,
            catalogAddonNameEnabled = corePrefs.catalogAddonNameEnabled,
            catalogTypeSuffixEnabled = corePrefs.catalogTypeSuffixEnabled,
            modernLandscapePostersEnabled = false,
            focusedBackdropExpandEnabled = focusedBackdropPrefs.expandEnabled,
            focusedBackdropExpandDelaySeconds = focusedBackdropPrefs.expandDelaySeconds,
            focusedBackdropTrailerEnabled = focusedBackdropPrefs.trailerEnabled,
            focusedBackdropTrailerMuted = focusedBackdropPrefs.trailerMuted,
            focusedBackdropTrailerPlaybackTarget = focusedBackdropPrefs.trailerPlaybackTarget,
            posterCardWidthDp = posterCardWidthDp,
            posterCardHeightDp = posterCardHeightDp,
            posterCardCornerRadiusDp = posterCardCornerRadiusDp
        )
    }

    viewModelScope.launch {
        combine(
            baseLayoutUiPrefsFlow,
            modernLayoutPrefsFlow
        ) { basePrefs, modernPrefs ->
            basePrefs.copy(
                modernLandscapePostersEnabled = modernPrefs
            )
        }
            .distinctUntilChanged()
            .debounce(300)
            .collectLatest { prefs ->
                val effectivePosterLabelsEnabled = if (prefs.layout == HomeLayout.MODERN) {
                    false
                } else {
                    prefs.posterLabelsEnabled
                }
                val previousState = _uiState.value
                val shouldRefreshCatalogPresentation =
                    currentHeroCatalogKeys != prefs.heroCatalogKeys ||
                        previousState.heroSectionEnabled != prefs.heroSectionEnabled ||
                        previousState.homeLayout != prefs.layout
                currentHeroCatalogKeys = prefs.heroCatalogKeys
                _uiState.update {
                    it.copy(
                        homeLayout = prefs.layout,
                        heroCatalogKeys = prefs.heroCatalogKeys,
                        heroSectionEnabled = prefs.heroSectionEnabled,
                        posterLabelsEnabled = effectivePosterLabelsEnabled,
                        catalogAddonNameEnabled = prefs.catalogAddonNameEnabled,
                        catalogTypeSuffixEnabled = prefs.catalogTypeSuffixEnabled,
                        modernLandscapePostersEnabled = prefs.modernLandscapePostersEnabled,
                        focusedPosterBackdropExpandEnabled = prefs.focusedBackdropExpandEnabled,
                        focusedPosterBackdropExpandDelaySeconds = prefs.focusedBackdropExpandDelaySeconds,
                        focusedPosterBackdropTrailerEnabled = prefs.focusedBackdropTrailerEnabled,
                        focusedPosterBackdropTrailerMuted = prefs.focusedBackdropTrailerMuted,
                        focusedPosterBackdropTrailerPlaybackTarget = prefs.focusedBackdropTrailerPlaybackTarget,
                        posterCardWidthDp = prefs.posterCardWidthDp,
                        posterCardHeightDp = prefs.posterCardHeightDp,
                        posterCardCornerRadiusDp = prefs.posterCardCornerRadiusDp
                    )
                }
                if (shouldRefreshCatalogPresentation) {
                    scheduleUpdateCatalogRows()
                }
            }
    }
}

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeModernHomePresentationPipeline() {
    viewModelScope.launch {
        combine(_uiState, _displayCatalogRows) { state, catalogRows ->
            ModernHomePresentationInput(
                catalogRows = catalogRows,
                resolvedRailRows = state.resolvedRailRows,
                continueWatchingItems = state.continueWatchingItems,
                useLandscapePosters = state.modernLandscapePostersEnabled,
                showCatalogTypeSuffix = state.catalogTypeSuffixEnabled,
                continueWatchingTitle = appContext.getString(R.string.continue_watching),
                airsDateTemplate = appContext.getString(R.string.cw_airs_date),
                upcomingLabel = appContext.getString(R.string.cw_upcoming)
            )
        }
            .distinctUntilChanged()
            .debounce(80)
            .collectLatest { input ->
                val capturedGeneration = homeProfileGeneration
                val presentation = withContext(Dispatchers.Default) {
                    buildModernHomePresentation(
                        input = input,
                        cache = modernCarouselRowBuildCache
                    )
                }
                if (!isCurrentHomeProfileGeneration(capturedGeneration)) return@collectLatest
                _uiState.update { state ->
                    if (state.modernHomePresentation == presentation) {
                        state
                    } else {
                        state.copy(modernHomePresentation = presentation)
                    }
                }
            }
    }
}

internal fun HomeViewModel.observeExternalMetaPrefetchPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.preferExternalMetaAddonDetail
            .distinctUntilChanged()
            .collectLatest {
                // External add-on detail prefetch no longer drives Home enrichment.
            }
    }
}

internal fun HomeViewModel.refreshTrailerMetadataAvailabilityPipeline(rows: List<CatalogRow>) {
    val resolvedByItemKey: Map<String, ResolvedDisplayItem> =
        resolvedDisplaySurfaceRepository.snapshotNow(
            profileManager.activeProfileSession.value.profileId
        ).associateBy { it.itemKey }

    val activeKeys = mutableSetOf<String>()
    rows.asSequence()
        .flatMap { it.items.asSequence() }
        .forEach { item ->
            val key = homeTrailerAvailabilityKey(item.id, item.apiType)
            activeKeys += key
            // Prefer resolved trailer state; fall back to MetaPreview.trailerYtIds when
            // the resolved surface has not produced an entry for this item yet.
            val resolved = resolvedByItemKey[key]
            val resolvedYtId =
                (resolved?.trailer?.selectedPlaybackRef as? TrailerPlaybackRef.YouTubeId)?.videoId
            val selectedFallbackId = resolvedYtId
                ?: resolved?.trailer?.fallbackTrailerYtIds?.firstOrNull { it.isNotBlank() }
                ?: item.trailerYtIds.firstOrNull { id -> id.isNotBlank() }
            val hasPublishedPreview = hasPublishedHomeTrailerPreview(
                item.id,
                trailerPreviewUrlsState,
                trailerPreviewExternalUrlsState
            )
            if (selectedFallbackId != null || hasPublishedPreview) {
                trailerMetadataAvailableState[key] = true
                selectedFallbackId
                    ?.let { trailerSelectedFallbackYtIdsState[key] = it }
                    ?: trailerSelectedFallbackYtIdsState.remove(key)
            } else {
                trailerMetadataAvailableState.remove(key)
                trailerSelectedFallbackYtIdsState.remove(key)
            }
        }
    trailerMetadataAvailableState.keys.retainAll(activeKeys)
    trailerSelectedFallbackYtIdsState.keys.retainAll(activeKeys)
}

internal fun HomeViewModel.syncHomeTrailerAvailabilityFromResolvedItems(items: List<ResolvedDisplayItem>) {
    val activeKeys = items.mapTo(mutableSetOf()) { it.itemKey }
    items.forEach { item ->
        val selected = item.trailer.selectedPlaybackRef
        if (selected != null) {
            trailerMetadataAvailableState[item.itemKey] = true
            when (selected) {
                is TrailerPlaybackRef.YouTubeId -> trailerSelectedFallbackYtIdsState[item.itemKey] = selected.videoId
                else -> trailerSelectedFallbackYtIdsState.remove(item.itemKey)
            }
        } else {
            trailerMetadataAvailableState.remove(item.itemKey)
            trailerSelectedFallbackYtIdsState.remove(item.itemKey)
        }
    }
    trailerMetadataAvailableState.keys.retainAll(activeKeys)
    trailerSelectedFallbackYtIdsState.keys.retainAll(activeKeys)
    val traceSummary = HomeTrailerSurfaceTraceSummary(
        itemCount = items.size,
        selectedRefCount = items.count { it.trailer.selectedPlaybackRef != null },
        youtubeRefCount = items.count { it.trailer.selectedPlaybackRef is TrailerPlaybackRef.YouTubeId },
        inAppRefCount = items.count { it.trailer.selectedPlaybackRef is TrailerPlaybackRef.InAppSource },
        externalRefCount = items.count { it.trailer.selectedPlaybackRef is TrailerPlaybackRef.ExternalUrl },
        fallbackIdCount = items.sumOf { it.trailer.normalizedFallbackTrailerIdCount() }
    )
    if (shouldEmitHomeTrailerSurfaceTrace(lastHomeTrailerSurfaceTraceSummary, traceSummary)) {
        lastHomeTrailerSurfaceTraceSummary = traceSummary
        traceEvents.emitTrailerSurfaceSynced(
            surface = "home",
            itemCount = traceSummary.itemCount,
            selectedRefCount = traceSummary.selectedRefCount,
            youtubeRefCount = traceSummary.youtubeRefCount,
            inAppRefCount = traceSummary.inAppRefCount,
            externalRefCount = traceSummary.externalRefCount,
            fallbackIdCount = traceSummary.fallbackIdCount
        )
    }
}

internal fun HomeViewModel.clearTrailerMetadataAvailabilityPipeline() {
    trailerMetadataAvailableState.clear()
    trailerSelectedFallbackYtIdsState.clear()
    lastHomeTrailerSurfaceTraceSummary = null
    trailerMetadataAvailabilityInFlightKeys.clear()
    val jobs = synchronized(trailerMetadataAvailabilityJobs) {
        trailerMetadataAvailabilityJobs.toList().also { trailerMetadataAvailabilityJobs.clear() }
    }
    jobs.forEach { it.cancel() }
}

internal fun HomeViewModel.requestTrailerPreviewPipeline(item: MetaPreview) {
    requestTrailerPreviewPipeline(
        itemId = item.id,
        title = item.name,
        releaseInfo = item.releaseInfo,
        apiType = item.apiType,
        fallbackYtId = trailerSelectedFallbackYtIdsState[homeTrailerAvailabilityKey(item.id, item.apiType)]
    )
}

internal fun HomeViewModel.requestTrailerPreviewPipeline(
    itemId: String,
    title: String,
    releaseInfo: String?,
    apiType: String,
    fallbackYtId: String? = null,
    forceRefresh: Boolean = false
) {
    if (!isNonPlaybackHomeWorkAllowed()) return

    val publishedBefore = hasPublishedHomeTrailerPreview(
        itemId = itemId,
        trailerPreviewUrls = trailerPreviewUrlsState,
        trailerPreviewExternalUrls = trailerPreviewExternalUrlsState
    )
    traceEvents.emitTrailerPreviewRequest(
        itemId = itemId,
        itemType = apiType,
        fallbackRef = fallbackYtId,
        published = publishedBefore,
        negativeCached = trailerPreviewNegativeCache.containsKey(itemId),
        forceRefresh = forceRefresh
    )

    if (activeTrailerPreviewItemId != itemId) {
        activeTrailerPreviewItemId = itemId
        trailerPreviewRequestVersion++
    }
    if (publishedBefore && !forceRefresh) {
        trailerPreviewLoadingIds.remove(itemId)
        traceEvents.emitTrailerPreviewState(
            itemId = itemId,
            stage = "request_completed",
            published = true,
            negativeCached = false,
            loading = false,
            reason = "already_published"
        )
        return
    }
    if (trailerPreviewNegativeCache.containsKey(itemId) && !forceRefresh) {
        trailerPreviewLoadingIds.remove(itemId)
        traceEvents.emitTrailerPreviewState(
            itemId = itemId,
            stage = "request_skipped",
            published = false,
            negativeCached = true,
            loading = false,
            reason = "negative_cached"
        )
        return
    }

    val requestVersion = trailerPreviewRequestVersion
    trailerPreviewJob?.cancel()
    trailerPreviewLoadingIds[itemId] = true
    traceEvents.emitTrailerPreviewState(
        itemId = itemId,
        stage = "resolve_started",
        published = false,
        negativeCached = false,
        loading = true,
        reason = if (fallbackYtId.isNullOrBlank()) "metadata_lookup" else "fallback_ref"
    )
    trailerPreviewJob = viewModelScope.launch {
        val result = try {
            withContext(Dispatchers.IO) {
                metadataRouterFacade.fetchTrailer(
                    metadataRequest = homeTrailerPreviewMetadataRequest(
                        itemId = itemId,
                        title = title,
                        releaseInfo = releaseInfo,
                        apiType = apiType,
                        languageTag = profileBoundary.currentLanguageTag()
                    ),
                    title = title,
                    year = releaseInfo?.take(4)?.takeIf { it.length == 4 },
                    tmdbId = itemId.normalizedTmdbIdOrNull(),
                    type = apiType,
                    contentId = itemId,
                    fallbackYtIds = listOfNotNull(fallbackYtId?.trim()?.takeIf { it.isNotBlank() })
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            traceEvents.emitTrailerPreviewState(
                itemId = itemId,
                stage = "resolve_failed",
                published = false,
                negativeCached = false,
                loading = false,
                reason = error.javaClass.simpleName
            )
            null
        }

        val published = publishHomeTrailerPreviewResolution(
            itemId = itemId,
            result = result,
            trailerPreviewUrls = trailerPreviewUrlsState,
            trailerPreviewAudioUrls = trailerPreviewAudioUrlsState,
            trailerPreviewUserAgents = trailerPreviewUserAgentsState,
            trailerPreviewExternalUrls = trailerPreviewExternalUrlsState,
            trailerPreviewNegativeCache = trailerPreviewNegativeCache
        )
        trailerPreviewLoadingIds.remove(itemId)
        if (!published) {
            trailerPreviewNegativeCache[itemId] = true
        }
        traceEvents.emitTrailerPreviewState(
            itemId = itemId,
            stage = "request_completed",
            published = published,
            negativeCached = trailerPreviewNegativeCache.containsKey(itemId),
            loading = false,
            reason = when {
                requestVersion != trailerPreviewRequestVersion -> "stale_request_cached"
                result is TrailerResolutionResult.Playback -> "playback_source"
                result is TrailerResolutionResult.External -> "external_source"
                else -> "no_source"
            }
        )
    }
}

internal fun hasPublishedHomeTrailerPreview(
    itemId: String,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewExternalUrls: Map<String, String>
): Boolean =
    !trailerPreviewUrls[itemId].isNullOrBlank() ||
        !trailerPreviewExternalUrls[itemId].isNullOrBlank()

internal fun publishHomeTrailerPreviewResolution(
    itemId: String,
    result: TrailerResolutionResult?,
    trailerPreviewUrls: MutableMap<String, String>,
    trailerPreviewAudioUrls: MutableMap<String, String>,
    trailerPreviewUserAgents: MutableMap<String, String>,
    trailerPreviewExternalUrls: MutableMap<String, String>,
    trailerPreviewNegativeCache: MutableMap<String, Boolean>
): Boolean {
    return when (result) {
        is TrailerResolutionResult.Playback -> {
            trailerPreviewUrls[itemId] = result.source.videoUrl
            result.source.audioUrl?.takeIf { it.isNotBlank() }?.let { trailerPreviewAudioUrls[itemId] = it }
                ?: trailerPreviewAudioUrls.remove(itemId)
            result.source.userAgent?.takeIf { it.isNotBlank() }?.let { trailerPreviewUserAgents[itemId] = it }
                ?: trailerPreviewUserAgents.remove(itemId)
            trailerPreviewExternalUrls.remove(itemId)
            trailerPreviewNegativeCache.remove(itemId)
            true
        }
        is TrailerResolutionResult.External -> {
            trailerPreviewUrls.remove(itemId)
            trailerPreviewAudioUrls.remove(itemId)
            trailerPreviewUserAgents.remove(itemId)
            trailerPreviewExternalUrls[itemId] = result.url
            trailerPreviewNegativeCache.remove(itemId)
            true
        }
        null -> false
    }
}

private fun homeTrailerPreviewMetadataRequest(
    itemId: String,
    title: String,
    releaseInfo: String?,
    apiType: String,
    languageTag: String?
): MetadataRequest =
    MetadataRequest(
        contentId = itemId,
        contentType = ContentType.fromString(apiType),
        sourceContext = MetadataSourceContext(
            itemType = apiType,
            addonMetadata = HomeDisplayMetadata(
                title = title,
                releaseInfo = releaseInfo
            )
        ),
        language = languageTag,
        depth = MetadataDepth.DETAIL_MEDIA
    )

private fun String.normalizedTmdbIdOrNull(): String? {
    val trimmed = trim()
    return when {
        trimmed.startsWith("tmdb:", ignoreCase = true) -> trimmed.substringAfter(":").takeIf { it.isNotBlank() }
        trimmed.all { it.isDigit() } -> trimmed
        else -> null
    }
}

private fun TrailerDisplayState.normalizedFallbackTrailerIdCount(): Int =
    fallbackTrailerYtIds.count { it.isNotBlank() }

internal fun HomeViewModel.onItemFocusPipeline(item: MetaPreview) {
    // Rail-preview-first hydration: RAIL_PREVIEW items are routed through the same
    // canonical overlay path used by visible and hero hydration.
    if (item.firstPaintSource == FirstPaintSource.RAIL_PREVIEW) {
        hydrateFocusedRailPreviewWithCoordinator(item)
        return
    }

    if (!isNonPlaybackHomeWorkAllowed()) return

    if (isFocusEnrichmentBlocked(
            startupRefreshPending = startupRefreshPending,
            catalogsLoadInProgress = catalogsLoadInProgress,
            traktDiscoveryRefreshInProgress = traktDiscoveryRefreshInProgress,
            mdbListDiscoveryRefreshInProgress = mdbListDiscoveryRefreshInProgress
        )
    ) {
        pendingFocusedItemForEnrichment = item
        return
    }
    pendingFocusedItemForEnrichment = null
    val itemKey = item.homeOverlayItemKey()
    if (isFocusedPreviewEnrichmentComplete(item)) return
    if (pendingTmdbEnrichItemId == itemKey) return

    if (_enrichingItemId.value != null && _enrichingItemId.value != item.id) {
        setEnrichingItemId(null)
    }

    setEnrichingItemId(item.id)

    pendingTmdbEnrichItemId = itemKey
    val expectedGeneration = homeProfileGeneration
    val expectedLanguageTag = profileBoundary.currentLanguageTag()
    val expectedProfileSession = profileManager.activeProfileSession.value
    tmdbEnrichFocusJob?.cancel()
    tmdbEnrichFocusJob = viewModelScope.launch {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS)
        if (!isNonPlaybackHomeWorkAllowed()) {
            if (_enrichingItemId.value == item.id) {
                setEnrichingItemId(null)
            }
            return@launch
        }
        if (pendingTmdbEnrichItemId != itemKey) {
            if (_enrichingItemId.value == item.id) {
                setEnrichingItemId(null)
            }
            return@launch
        }
        if (isFocusedPreviewEnrichmentComplete(item)) {
            if (_enrichingItemId.value == item.id) {
                setEnrichingItemId(null)
            }
            return@launch
        }

        try {
            val currentHydrationState = focusedItemHydrationStates.getValue(itemKey)
            if (currentHydrationState != RailHydrationState.PREVIEW_ONLY) return@launch

            focusedItemHydrationStates[itemKey] = RailHydrationState.HYDRATING
            var overlayApplied = false
            val overlay = try {
                homeHydrationCoordinator.hydrate(
                    item = item,
                    trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                    priority = HomeHydrationPriority.FOCUSED,
                    languageTag = expectedLanguageTag,
                    expectedGeneration = expectedGeneration,
                    currentGeneration = { homeProfileGeneration },
                    onOverlayApplied = { appliedOverlay ->
                        overlayApplied = applyHydratedHomeOverlayFromCoordinator(
                            overlay = appliedOverlay,
                            expectedGeneration = expectedGeneration,
                            expectedLanguageTag = expectedLanguageTag,
                            expectedProfileSession = expectedProfileSession,
                            trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM
                        )
                        if (overlayApplied) {
                            focusedItemHydrationStates[itemKey] = RailHydrationState.CANONICAL_READY
                            prefetchedExternalMetaIds.add(itemKey)
                        }
                        overlayApplied
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(HomeViewModel.TAG, "onItemFocus hydration failed for ${item.id}: ${e.message}", e)
                null
            }
            if (overlay == null && focusedItemHydrationStates[itemKey] == RailHydrationState.HYDRATING) {
                focusedItemHydrationStates[itemKey] = RailHydrationState.HYDRATION_FAILED_USING_PREVIEW
            }
            if (overlay != null && !overlayApplied && focusedItemHydrationStates[itemKey] == RailHydrationState.HYDRATING) {
                focusedItemHydrationStates[itemKey] = RailHydrationState.PREVIEW_ONLY
            }

        } finally {
            if (pendingTmdbEnrichItemId == itemKey) {
                pendingTmdbEnrichItemId = null
            }
            if (_enrichingItemId.value == item.id) {
                setEnrichingItemId(null)
            }
        }
    }
}

private fun HomeViewModel.hydrateFocusedRailPreviewWithCoordinator(item: MetaPreview) {
    if (!isNonPlaybackHomeWorkAllowed()) return

    val itemKey = item.homeOverlayItemKey()
    val currentHydrationState = focusedItemHydrationStates.getValue(itemKey)
    if (currentHydrationState != RailHydrationState.PREVIEW_ONLY) return

    focusedItemHydrationStates[itemKey] = RailHydrationState.HYDRATING
    val expectedGeneration = homeProfileGeneration
    val expectedLanguageTag = profileBoundary.currentLanguageTag()
    val expectedProfileSession = profileManager.activeProfileSession.value
    viewModelScope.launch {
        var overlayApplied = false
        val overlay = try {
            homeHydrationCoordinator.hydrate(
                item = item,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.FOCUSED,
                languageTag = expectedLanguageTag,
                expectedGeneration = expectedGeneration,
                currentGeneration = { homeProfileGeneration },
                onOverlayApplied = { appliedOverlay ->
                    overlayApplied = applyHydratedHomeOverlayFromCoordinator(
                        overlay = appliedOverlay,
                        expectedGeneration = expectedGeneration,
                        expectedLanguageTag = expectedLanguageTag,
                        expectedProfileSession = expectedProfileSession,
                        trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM
                    )
                    if (overlayApplied) {
                        focusedItemHydrationStates[itemKey] = RailHydrationState.CANONICAL_READY
                        prefetchedExternalMetaIds.add(itemKey)
                    }
                    overlayApplied
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(HomeViewModel.TAG, "onItemFocus hydration failed for ${item.id}: ${e.message}", e)
            null
        }
        if (overlay == null && focusedItemHydrationStates[itemKey] == RailHydrationState.HYDRATING) {
            focusedItemHydrationStates[itemKey] = RailHydrationState.HYDRATION_FAILED_USING_PREVIEW
        }
        if (overlay != null && !overlayApplied && focusedItemHydrationStates[itemKey] == RailHydrationState.HYDRATING) {
            focusedItemHydrationStates[itemKey] = RailHydrationState.PREVIEW_ONLY
        }
    }
}

internal fun HomeViewModel.runDeferredFocusedItemEnrichmentIfReady() {
    pendingFocusedItemForEnrichment = consumeDeferredFocusedItemEnrichment(
        pendingItem = pendingFocusedItemForEnrichment,
        startupRefreshPending = startupRefreshPending,
        catalogsLoadInProgress = catalogsLoadInProgress,
        traktDiscoveryRefreshInProgress = traktDiscoveryRefreshInProgress,
        mdbListDiscoveryRefreshInProgress = mdbListDiscoveryRefreshInProgress,
        onReady = { item ->
            onItemFocusPipeline(item)
        }
    )
}

internal fun HomeViewModel.preloadAdjacentItemPipeline(item: MetaPreview) {
    if (!isNonPlaybackHomeWorkAllowed()) return

    if (startupRefreshPending || catalogsLoadInProgress || traktDiscoveryRefreshInProgress || mdbListDiscoveryRefreshInProgress) {
        return
    }
    val itemKey = item.homeOverlayItemKey()
    if (isFocusedPreviewEnrichmentComplete(item)) return
    if (pendingTmdbEnrichItemId == itemKey || pendingAdjacentPrefetchItemId == itemKey) return

    pendingAdjacentPrefetchItemId = itemKey
    val expectedGeneration = homeProfileGeneration
    val expectedLanguageTag = profileBoundary.currentLanguageTag()
    val expectedProfileSession = profileManager.activeProfileSession.value
    adjacentItemPrefetchJob?.cancel()
    adjacentItemPrefetchJob = viewModelScope.launch {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS)
        if (!isNonPlaybackHomeWorkAllowed()) return@launch
        if (pendingAdjacentPrefetchItemId != itemKey) return@launch
        if (isFocusedPreviewEnrichmentComplete(item)) return@launch

        try {
            if (currentTmdbSettings.isActive || item.type.isHomeTvContent()) {
                homeHydrationCoordinator.hydrate(
                    item = item,
                    trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                    priority = HomeHydrationPriority.ADJACENT,
                    languageTag = expectedLanguageTag,
                    expectedGeneration = expectedGeneration,
                    currentGeneration = { homeProfileGeneration },
                    onOverlayApplied = { appliedOverlay ->
                        val overlayApplied = applyHydratedHomeOverlayFromCoordinator(
                            overlay = appliedOverlay,
                            expectedGeneration = expectedGeneration,
                            expectedLanguageTag = expectedLanguageTag,
                            expectedProfileSession = expectedProfileSession,
                            trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM
                        )
                        if (overlayApplied) {
                            prefetchedExternalMetaIds.add(itemKey)
                        }
                        overlayApplied
                    }
                )
            }

        } finally {
            if (pendingAdjacentPrefetchItemId == itemKey) {
                pendingAdjacentPrefetchItemId = null
            }
        }
    }
}

internal fun mergeFocusedItemEnrichment(
    currentItem: MetaPreview,
    tmdbEnrichment: TmdbEnrichment?,
    externalMeta: Meta?,
    tmdbSettings: TmdbSettings = TmdbSettings(
        useArtwork = true,
        useBasicInfo = true,
        useDetails = true
    )
): MetaPreview {
    return mergeFocusedItemEnrichment(
        currentItem = currentItem,
        providerEnrichment = tmdbEnrichment?.toTvMetadataEnrichment(),
        externalMeta = externalMeta,
        tmdbSettings = tmdbSettings
    )
}

internal fun mergeFocusedItemEnrichment(
    currentItem: MetaPreview,
    providerEnrichment: TvMetadataEnrichment?,
    externalMeta: Meta?,
    tmdbSettings: TmdbSettings = TmdbSettings(
        useArtwork = true,
        useBasicInfo = true,
        useDetails = true
    )
): MetaPreview {
    var merged = currentItem
    if (providerEnrichment != null) {
        if (tmdbSettings.useArtwork) {
            merged = merged.copy(
                background = providerEnrichment.backdrop ?: merged.background,
                logo = providerEnrichment.logo ?: merged.logo
            )
        }
        if (tmdbSettings.useBasicInfo) {
            merged = merged.copy(
                name = providerEnrichment.localizedTitle ?: merged.name,
                description = providerEnrichment.description ?: merged.description,
                imdbRating = providerEnrichment.rating?.toFloat() ?: merged.imdbRating,
                ratingSource = if (providerEnrichment.rating != null) {
                    providerEnrichment.ratingSource.orDefault()
                } else {
                    merged.ratingSource.orDefault()
                },
                genres = if (providerEnrichment.genres.isNotEmpty()) providerEnrichment.genres else merged.genres
            )
        }
        if (tmdbSettings.useDetails) {
            merged = merged.copy(
                releaseInfo = providerEnrichment.releaseInfo ?: merged.releaseInfo,
                language = providerEnrichment.language ?: merged.language
            )
        }
    }
    if (externalMeta != null) {
        merged = merged.copy(
            background = externalMeta.background ?: merged.background,
            logo = externalMeta.logo ?: merged.logo,
            description = externalMeta.description ?: merged.description,
            imdbRating = externalMeta.imdbRating ?: merged.imdbRating,
            genres = if (externalMeta.genres.isNotEmpty()) externalMeta.genres else merged.genres,
            language = externalMeta.language ?: merged.language
        )
    }
    return merged
}

internal fun HomeViewModel.mergeFocusedItemEnrichment(
    currentItem: MetaPreview,
    tmdbEnrichment: TmdbEnrichment?,
    externalMeta: Meta?
): MetaPreview {
    return mergeFocusedItemEnrichment(
        currentItem = currentItem,
        providerEnrichment = tmdbEnrichment?.toTvMetadataEnrichment(),
        externalMeta = externalMeta,
        tmdbSettings = currentTmdbSettings
    )
}

internal suspend fun HomeViewModel.fetchProviderEnrichmentForPreview(item: MetaPreview): TvMetadataEnrichment? {
    return fetchProviderLocalizedMetadataDecisionForHome(
        item = item,
        fallbackContentId = null,
        addonMetadata = item.toHomeDisplayMetadata(),
        providerLocalizedMetadataResolver = providerLocalizedMetadataResolver,
        profileBoundary = profileBoundary
    ).value
}

internal suspend fun HomeViewModel.enrichHeroItemsPipeline(
    items: List<MetaPreview>,
    settings: TmdbSettings,
    expectedGeneration: Long = homeProfileGeneration,
    expectedLanguageTag: String = profileBoundary.currentLanguageTag(),
    expectedProfileSession: ActiveProfileSession? = null
): List<MetaPreview> {
    if (items.isEmpty()) return items

    val profileSessionForPublish = expectedProfileSession ?: runCatching {
        profileManager.activeProfileSession.value
    }.getOrNull()

    if (!isCurrentHomeHydrationScope(expectedGeneration, expectedLanguageTag, profileSessionForPublish)) {
        return items
    }
    return items
        .distinctBy { it.homeOverlayItemKey() }
        .associate { item ->
            val hydrated = try {
                val overlay = homeHydrationCoordinator.hydrate(
                    item = item,
                    trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                    priority = HomeHydrationPriority.HERO,
                    languageTag = expectedLanguageTag,
                    expectedGeneration = expectedGeneration,
                    currentGeneration = { homeProfileGeneration },
                    onOverlayApplied = { appliedOverlay ->
                        if (profileSessionForPublish != null) {
                            applyHydratedHomeOverlayFromCoordinator(
                                overlay = appliedOverlay,
                                expectedGeneration = expectedGeneration,
                                expectedLanguageTag = expectedLanguageTag,
                                expectedProfileSession = profileSessionForPublish,
                                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM
                            )
                        } else {
                            isCurrentHomeHydrationScope(expectedGeneration, expectedLanguageTag)
                        }
                    }
                )
                if (isCurrentHomeHydrationScope(expectedGeneration, expectedLanguageTag, profileSessionForPublish)) {
                    overlay?.fields?.applyToHeroItem(item, settings) ?: item
                } else {
                    item
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(HomeViewModel.TAG, "Hero enrichment failed for ${item.id}: ${e.message}")
                item
            }
            item.homeOverlayItemKey() to hydrated
        }
        .let { hydratedByItemKey ->
            items.map { item -> hydratedByItemKey[item.homeOverlayItemKey()] ?: item }
        }
}

internal fun isFocusEnrichmentBlocked(
    startupRefreshPending: Boolean,
    catalogsLoadInProgress: Boolean,
    traktDiscoveryRefreshInProgress: Boolean,
    mdbListDiscoveryRefreshInProgress: Boolean
): Boolean {
    return startupRefreshPending ||
        catalogsLoadInProgress ||
        traktDiscoveryRefreshInProgress ||
        mdbListDiscoveryRefreshInProgress
}

internal fun shouldSkipTmdbTrailerIdLookup(
    itemId: String?,
    apiType: String?
): Boolean {
    if (apiType?.trim()?.lowercase() in setOf("tv", "series", "show", "tvshow")) return true
    return AnimeStremioId.isExplicitAnimeOnlyId(itemId)
}

internal fun consumeDeferredFocusedItemEnrichment(
    pendingItem: MetaPreview?,
    startupRefreshPending: Boolean,
    catalogsLoadInProgress: Boolean,
    traktDiscoveryRefreshInProgress: Boolean,
    mdbListDiscoveryRefreshInProgress: Boolean,
    onReady: (MetaPreview) -> Unit
): MetaPreview? {
    val item = pendingItem ?: return null
    if (isFocusEnrichmentBlocked(
            startupRefreshPending = startupRefreshPending,
            catalogsLoadInProgress = catalogsLoadInProgress,
            traktDiscoveryRefreshInProgress = traktDiscoveryRefreshInProgress,
            mdbListDiscoveryRefreshInProgress = mdbListDiscoveryRefreshInProgress
        )
    ) {
        return item
    }
    onReady(item)
    return null
}

internal fun applyTomatoesToTraktSnapshot(
    snapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot,
    itemId: String,
    tomatoesRating: Double
): com.nexio.tv.data.repository.TraktDiscoverySnapshot {
    // A record carries the itemId if its sourceItemId or any stableId prefix matches.
    fun com.nexio.tv.domain.model.RailItemPreview.matchesId(id: String): Boolean =
        sourceItemId == id || stableIds.imdb == id ||
            (stableIds.tmdb != null && "tmdb:${stableIds.tmdb}" == id) ||
            (stableIds.tvdb != null && "tvdb:${stableIds.tvdb}" == id) ||
            (stableIds.kitsu != null && "kitsu:${stableIds.kitsu}" == id)

    fun com.nexio.tv.domain.model.RailItemPreview.withTomatoesRating(
        rating: Double
    ): com.nexio.tv.domain.model.RailItemPreview = copy(display = display.copy(tomatoesRating = rating))

    fun List<com.nexio.tv.domain.model.RailItemPreview>.applyTomatoes(
        id: String,
        rating: Double
    ): List<com.nexio.tv.domain.model.RailItemPreview> {
        if (none { it.matchesId(id) }) return this
        return map { if (it.matchesId(id)) it.withTomatoesRating(rating) else it }
    }

    val allRecords = snapshot.trendingMovieItemRecords +
        snapshot.trendingShowItemRecords +
        snapshot.popularMovieItemRecords +
        snapshot.popularShowItemRecords +
        snapshot.recommendationMovieItemRecords +
        snapshot.recommendationShowItemRecords +
        snapshot.calendarItemRecords +
        snapshot.customListCatalogs.flatMap { it.itemRecords }
    if (allRecords.none { it.matchesId(itemId) }) return snapshot

    return snapshot.copy(
        trendingMovieItemRecords = snapshot.trendingMovieItemRecords.applyTomatoes(itemId, tomatoesRating),
        trendingShowItemRecords = snapshot.trendingShowItemRecords.applyTomatoes(itemId, tomatoesRating),
        popularMovieItemRecords = snapshot.popularMovieItemRecords.applyTomatoes(itemId, tomatoesRating),
        popularShowItemRecords = snapshot.popularShowItemRecords.applyTomatoes(itemId, tomatoesRating),
        recommendationMovieItemRecords = snapshot.recommendationMovieItemRecords.applyTomatoes(itemId, tomatoesRating),
        recommendationShowItemRecords = snapshot.recommendationShowItemRecords.applyTomatoes(itemId, tomatoesRating),
        calendarItemRecords = snapshot.calendarItemRecords.applyTomatoes(itemId, tomatoesRating),
        customListCatalogs = snapshot.customListCatalogs.map { catalog ->
            catalog.copy(itemRecords = catalog.itemRecords.applyTomatoes(itemId, tomatoesRating))
        }
    )
}

internal fun applyTomatoesOverridesToTraktSnapshot(
    snapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot,
    tomatoesByItemId: Map<String, Double>
): com.nexio.tv.data.repository.TraktDiscoverySnapshot {
    return tomatoesByItemId.entries.fold(snapshot) { current, (itemId, tomatoesRating) ->
        applyTomatoesToTraktSnapshot(current, itemId, tomatoesRating)
    }
}

private fun applyTomatoesToMDBListSnapshot(
    snapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot,
    itemId: String,
    tomatoesRating: Double
): com.nexio.tv.data.repository.MDBListDiscoverySnapshot {
    return snapshot
}

internal fun applyTomatoesOverridesToMDBListSnapshot(
    snapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot,
    tomatoesByItemId: Map<String, Double>
): com.nexio.tv.data.repository.MDBListDiscoverySnapshot {
    return tomatoesByItemId.entries.fold(snapshot) { current, (itemId, tomatoesRating) ->
        applyTomatoesToMDBListSnapshot(current, itemId, tomatoesRating)
    }
}

private fun HomeViewModel.isFocusedPreviewEnrichmentComplete(item: MetaPreview): Boolean {
    val itemKey = item.homeOverlayItemKey()
    return (!item.type.isHomeTvContent() && !currentTmdbSettings.isActive) ||
        itemKey in prefetchedTmdbIds ||
        itemKey in prefetchedExternalMetaIds
}

internal fun HomeDisplayMetadata.applyToHeroItem(
    base: MetaPreview,
    settings: TmdbSettings
): MetaPreview {
    if (!settings.isActive) return base

    var merged = base
    if (settings.useArtwork) {
        merged = merged.copy(
            background = displayBackdrop ?: merged.background,
            logo = displayLogo ?: merged.logo
        )
    }
    if (settings.useBasicInfo) {
        merged = merged.copy(
            name = title ?: merged.name,
            description = description ?: merged.description,
            imdbRating = imdbRating ?: merged.imdbRating,
            ratingSource = if (imdbRating != null) ratingSource.orDefault() else merged.ratingSource.orDefault(),
            tomatoesRating = tomatoesRating ?: merged.tomatoesRating,
            genres = if (genres.isNotEmpty()) genres else merged.genres
        )
    }
    if (settings.useDetails) {
        merged = merged.copy(
            releaseInfo = releaseInfo ?: merged.releaseInfo,
            runtime = runtime ?: merged.runtime
        )
    }
    return merged
}

private fun TmdbEnrichment.toTvMetadataEnrichment(): TvMetadataEnrichment {
    return TvMetadataEnrichment(
        seriesTvdbId = null,
        localizedTitle = localizedTitle,
        description = description,
        genres = genres,
        backdrop = backdrop,
        logo = logo,
            poster = poster,
            releaseInfo = releaseInfo,
            rating = rating,
            ratingSource = ratingSource.orDefault(com.nexio.tv.domain.model.TitleRatingSource.TMDB),
            runtimeMinutes = runtimeMinutes,
        ageRating = ageRating,
        countries = countries,
        language = language
    )
}

private fun ContentType.isHomeTvContent(): Boolean = this == ContentType.SERIES || this == ContentType.TV

internal fun HomeViewModel.replaceGridHeroItemsPipeline(
    gridItems: List<GridItem>,
    heroItems: List<MetaPreview>
): List<GridItem> {
    if (gridItems.isEmpty()) return gridItems
    return gridItems.map { item ->
        if (item is GridItem.Hero) {
            item.copy(items = heroItems)
        } else {
            item
        }
    }
}

internal fun HomeViewModel.heroEnrichmentSignaturePipeline(
    items: List<MetaPreview>,
    settings: TmdbSettings
): String {
    val itemSignature = items.joinToString(separator = "|") { item ->
        "${item.id}:${item.apiType}:${item.name}:${item.background}:${item.logo}:${item.poster}"
    }
    return buildString {
        append(settings.isActive)
        append(':')
        append(settings.apiKey.hashCode())
        append(':')
        append(AppLocaleResolver.resolveEffectiveAppLanguageTag(appContext))
        append(':')
        append(settings.useArtwork)
        append(':')
        append(settings.useBasicInfo)
        append(':')
        append(settings.useDetails)
        append("::")
        append(itemSignature)
    }
}
