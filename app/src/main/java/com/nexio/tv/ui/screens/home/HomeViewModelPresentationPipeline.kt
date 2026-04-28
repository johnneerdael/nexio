package com.nexio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nexio.tv.R
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvdbLanguageMapper
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.orDefault
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import com.nexio.tv.data.trailer.TrailerResolutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.sync.withPermit

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
        _uiState
            .map { state ->
                ModernHomePresentationInput(
                    catalogRows = state.catalogRows,
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
            .collectLatest { enabled ->
                externalMetaPrefetchEnabled = enabled
                if (!enabled) {
                    externalMetaPrefetchJob?.cancel()
                    pendingExternalMetaPrefetchItemId = null
                    externalMetaPrefetchInFlightIds.clear()
                    if (!currentTmdbSettings.isActive) {
                        tmdbEnrichFocusJob?.cancel()
                        pendingTmdbEnrichItemId = null
                        setEnrichingItemId(null)
                    }
                }
            }
    }
}

internal fun HomeViewModel.refreshTrailerMetadataAvailabilityPipeline(rows: List<CatalogRow>) {
    if (!isNonPlaybackHomeWorkAllowed()) return

    val catalogItems = rows
        .asSequence()
        .flatMap { it.items.asSequence() }
        .distinctBy { homeTrailerAvailabilityKey(it.id, it.apiType) }
        .toList()

    catalogItems.forEach { item ->
        val availabilityKey = homeTrailerAvailabilityKey(item.id, item.apiType)
        if (trailerMetadataAvailableState.containsKey(availabilityKey)) {
            return@forEach
        }
        if (!trailerMetadataAvailabilityInFlightKeys.add(availabilityKey)) {
            return@forEach
        }

        val job = viewModelScope.launch {
            try {
                trailerMetadataAvailabilitySemaphore.withPermit {
                    if (!isNonPlaybackHomeWorkAllowed()) return@withPermit
                    val isTvContent = item.apiType.trim().lowercase() in setOf("tv", "series", "show", "tvshow")
                    val tmdbId = if (isTvContent) null else withContext(Dispatchers.IO) {
                        runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }
                            .getOrNull()
                    }
                    val available = trailerService.getTitleMediaAvailability(
                        tmdbId = tmdbId,
                        type = item.apiType,
                        contentId = item.id,
                        fallbackYtIds = item.trailerYtIds
                    )
                    trailerMetadataAvailableState[availabilityKey] = available
                }
            } finally {
                trailerMetadataAvailabilityInFlightKeys.remove(availabilityKey)
                trailerMetadataAvailabilityJobs.remove(coroutineContext.job)
            }
        }
        trailerMetadataAvailabilityJobs.add(job)
    }
}

internal fun HomeViewModel.clearTrailerMetadataAvailabilityPipeline() {
    trailerMetadataAvailableState.clear()
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
        fallbackYtId = item.trailerYtIds.firstOrNull()
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

    if (activeTrailerPreviewItemId != itemId) {
        activeTrailerPreviewItemId = itemId
        trailerPreviewRequestVersion++
    }

    if (trailerPreviewNegativeCache.containsKey(itemId)) return
    if (trailerPreviewUrlsState.containsKey(itemId) || trailerPreviewExternalUrlsState.containsKey(itemId)) return
    if (trailerPreviewLoadingIds.put(itemId, true) != null) return

    val requestVersion = trailerPreviewRequestVersion

    trailerPreviewJob?.cancel()
    trailerPreviewJob = viewModelScope.launch {
        try {
            if (!isNonPlaybackHomeWorkAllowed()) return@launch
            val isTvContent = apiType.trim().lowercase() in setOf("tv", "series", "show", "tvshow")
            val tmdbId = if (isTvContent) null else runCatching { tmdbService.ensureTmdbId(itemId, apiType) }.getOrNull()
            if (!isNonPlaybackHomeWorkAllowed()) return@launch
            if (forceRefresh) {
                trailerService.invalidateLookupCache(
                    title = title,
                    year = extractYear(releaseInfo),
                    tmdbId = tmdbId,
                    type = apiType,
                    contentId = itemId,
                    fallbackYtIds = listOfNotNull(fallbackYtId)
                )
            }
            val trailerResult = trailerService.resolveTrailer(
                title = title,
                year = extractYear(releaseInfo),
                tmdbId = tmdbId,
                type = apiType,
                contentId = itemId,
                fallbackYtIds = listOfNotNull(fallbackYtId)
            )

            val isLatestFocusedItem =
                activeTrailerPreviewItemId == itemId && trailerPreviewRequestVersion == requestVersion
            if (!isLatestFocusedItem) {
                trailerPreviewLoadingIds.remove(itemId)
                return@launch
            }

            when (trailerResult) {
                is TrailerResolutionResult.Playback -> {
                    trailerPreviewNegativeCache.remove(itemId)
                    trailerPreviewExternalUrlsState.remove(itemId)
                    trailerPreviewUrlsState[itemId] = trailerResult.source.videoUrl
                    val audioUrl = trailerResult.source.audioUrl
                    if (audioUrl.isNullOrBlank()) {
                        trailerPreviewAudioUrlsState.remove(itemId)
                    } else {
                        trailerPreviewAudioUrlsState[itemId] = audioUrl
                    }
                }

                is TrailerResolutionResult.External -> {
                    trailerPreviewNegativeCache.remove(itemId)
                    trailerPreviewUrlsState.remove(itemId)
                    trailerPreviewAudioUrlsState.remove(itemId)
                    trailerPreviewExternalUrlsState[itemId] = trailerResult.url
                }

                null -> {
                    trailerPreviewNegativeCache[itemId] = true
                    trailerPreviewUrlsState.remove(itemId)
                    trailerPreviewAudioUrlsState.remove(itemId)
                    trailerPreviewExternalUrlsState.remove(itemId)
                }
            }

            trailerPreviewLoadingIds.remove(itemId)
        } finally {
            trailerPreviewLoadingIds.remove(itemId)
            if (trailerPreviewJob?.isActive == false) {
                trailerPreviewJob = null
            }
        }
    }
}

internal fun HomeViewModel.onItemFocusPipeline(item: MetaPreview) {
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
    if (isFocusedPreviewEnrichmentComplete(item)) return
    if (pendingTmdbEnrichItemId == item.id) return

    if (_enrichingItemId.value != null && _enrichingItemId.value != item.id) {
        setEnrichingItemId(null)
    }

    val willEnrich = currentTmdbSettings.isActive || externalMetaPrefetchEnabled
    if (willEnrich) {
        setEnrichingItemId(item.id)
    }

    pendingTmdbEnrichItemId = item.id
    tmdbEnrichFocusJob?.cancel()
    tmdbEnrichFocusJob = viewModelScope.launch {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS)
        if (!isNonPlaybackHomeWorkAllowed()) {
            if (_enrichingItemId.value == item.id) {
                setEnrichingItemId(null)
            }
            return@launch
        }
        if (pendingTmdbEnrichItemId != item.id) {
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
            var tmdbEnriched = false
            if (currentTmdbSettings.isActive || item.type.isHomeTvContent()) {
                val enrichment = withContext(Dispatchers.IO) {
                    runCatching { fetchProviderEnrichmentForPreview(item) }.getOrNull()
                }
                if (enrichment != null && pendingTmdbEnrichItemId == item.id) {
                    prefetchedTmdbIds.add(item.id)
                    prefetchedExternalMetaIds.add(item.id)
                    updateCatalogItemWithProvider(item.id, enrichment)
                    tmdbEnriched = true
                }
            }

            if (!tmdbEnriched &&
                externalMetaPrefetchEnabled &&
                item.id !in prefetchedExternalMetaIds &&
                externalMetaPrefetchInFlightIds.add(item.id)
            ) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        metaRepository.getMetaFromAllAddons(item.apiType, item.id)
                            .first { it is NetworkResult.Success || it is NetworkResult.Error }
                    }

                    if (result is NetworkResult.Success && pendingTmdbEnrichItemId == item.id) {
                        prefetchedExternalMetaIds.add(item.id)
                        updateCatalogItemWithMeta(item.id, result.data)
                    }
                } finally {
                    externalMetaPrefetchInFlightIds.remove(item.id)
                }
            }
        } finally {
            if (pendingTmdbEnrichItemId == item.id) {
                pendingTmdbEnrichItemId = null
            }
            if (_enrichingItemId.value == item.id) {
                setEnrichingItemId(null)
            }
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
    if (isFocusedPreviewEnrichmentComplete(item)) return
    if (pendingTmdbEnrichItemId == item.id || pendingAdjacentPrefetchItemId == item.id) return

    pendingAdjacentPrefetchItemId = item.id
    adjacentItemPrefetchJob?.cancel()
    adjacentItemPrefetchJob = viewModelScope.launch {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS)
        if (!isNonPlaybackHomeWorkAllowed()) return@launch
        if (pendingAdjacentPrefetchItemId != item.id) return@launch
        if (isFocusedPreviewEnrichmentComplete(item)) return@launch

        try {
            var tmdbEnriched = false
            if (currentTmdbSettings.isActive || item.type.isHomeTvContent()) {
                val enrichment = withContext(Dispatchers.IO) {
                    runCatching { fetchProviderEnrichmentForPreview(item) }.getOrNull()
                }
                if (enrichment != null) {
                    prefetchedTmdbIds.add(item.id)
                    prefetchedExternalMetaIds.add(item.id)
                    updateCatalogItemWithProvider(item.id, enrichment)
                    tmdbEnriched = true
                }
            }

            if (!tmdbEnriched &&
                externalMetaPrefetchEnabled &&
                item.id !in prefetchedExternalMetaIds &&
                externalMetaPrefetchInFlightIds.add(item.id)
            ) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        metaRepository.getMetaFromAllAddons(item.apiType, item.id)
                            .first { it is NetworkResult.Success || it is NetworkResult.Error }
                    }
                    if (result is NetworkResult.Success) {
                        prefetchedExternalMetaIds.add(item.id)
                        updateCatalogItemWithMeta(item.id, result.data)
                    }
                } finally {
                    externalMetaPrefetchInFlightIds.remove(item.id)
                }
            }
        } finally {
            if (pendingAdjacentPrefetchItemId == item.id) {
                pendingAdjacentPrefetchItemId = null
            }
        }
    }
}

private fun HomeViewModel.updateCatalogItemWithProvider(itemId: String, enrichment: TvMetadataEnrichment) {
    pendingProviderEnrichmentByItemId[itemId] = enrichment
    scheduleMetadataEnrichmentFlushPipeline()
}

private fun HomeViewModel.updateCatalogItemWithMeta(itemId: String, meta: Meta) {
    pendingMetaEnrichmentByItemId[itemId] = meta
    scheduleMetadataEnrichmentFlushPipeline()
}

private fun HomeViewModel.scheduleMetadataEnrichmentFlushPipeline() {
    if (!isNonPlaybackHomeWorkAllowed()) return

    metadataEnrichmentFlushJob?.cancel()
    metadataEnrichmentFlushJob = viewModelScope.launch {
        delay(HomeViewModel.FOCUS_ENRICHMENT_BATCH_WINDOW_MS)
        if (!isNonPlaybackHomeWorkAllowed()) return@launch
        flushMetadataEnrichmentPipeline()
    }
}

private suspend fun HomeViewModel.flushMetadataEnrichmentPipeline() {
    if (pendingProviderEnrichmentByItemId.isEmpty() &&
        pendingMetaEnrichmentByItemId.isEmpty()
    ) {
        return
    }
    val providerByItemId = pendingProviderEnrichmentByItemId.toMap()
    val metaByItemId = pendingMetaEnrichmentByItemId.toMap()
    pendingProviderEnrichmentByItemId.clear()
    pendingMetaEnrichmentByItemId.clear()

    var changed = false
    val changedCatalogKeys = mutableSetOf<String>()
    catalogsMap.forEach { (key, row) ->
        var mutableItems: MutableList<MetaPreview>? = null
        row.items.forEachIndexed { index, currentItem ->
            val mergedItem = titleRatingOverrideRepository.enrichPreview(mergeFocusedItemEnrichment(
                currentItem = currentItem,
                providerEnrichment = providerByItemId[currentItem.id],
                externalMeta = metaByItemId[currentItem.id]
            ))
            if (mergedItem != currentItem) {
                val updatedItems = mutableItems ?: row.items.toMutableList().also { mutableItems = it }
                updatedItems[index] = mergedItem
            }
        }

        val updatedItems = mutableItems
        if (updatedItems != null) {
            catalogsMap[key] = row.copy(items = updatedItems)
            changedCatalogKeys += key
            changed = true
        }
    }

    if (!changed) return

    changedCatalogKeys.forEach(truncatedRowCache::remove)
    scheduleUpdateCatalogRows()
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
            trailerYtIds = if (externalMeta.trailerYtIds.isNotEmpty()) {
                externalMeta.trailerYtIds
            } else {
                merged.trailerYtIds
            },
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
    if (item.type.isHomeTvContent() || AnimeStremioId.parse(item.id) != null) {
        return metadataRouterFacade.fetchTvEnrichment(
            metadataRequest = MetadataRequest(
                contentId = item.id,
                contentType = item.type,
                sourceContext = item.toHomeMetadataSourceContext(
                    addonMetadata = item.toHomeDisplayMetadata()
                ),
                language = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag()),
                depth = MetadataDepth.DETAIL_CORE
            ),
            tvRequest = TvMetadataRequest(
                contentId = item.id,
                fallbackContentId = null,
                contentType = item.type,
                language = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag())
            )
        ).value
    }

    return metadataRouterFacade.fetchTvEnrichment(
        metadataRequest = MetadataRequest(
            contentId = item.id,
            contentType = item.type,
            sourceContext = item.toHomeMetadataSourceContext(
                addonMetadata = item.toHomeDisplayMetadata()
            ),
            language = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag()),
            depth = MetadataDepth.DETAIL_CORE
        ),
        tvRequest = TvMetadataRequest(
            contentId = item.id,
            fallbackContentId = null,
            contentType = item.type,
            language = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag())
        )
    ).value
}

internal suspend fun HomeViewModel.enrichHeroItemsPipeline(
    items: List<MetaPreview>,
    settings: TmdbSettings
): List<MetaPreview> {
    if (items.isEmpty()) return items

    return coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                try {
                    val enrichment = fetchProviderEnrichmentForPreview(item) ?: return@async item
                    titleRatingOverrideRepository.enrichPreview(mergeFocusedItemEnrichment(
                        currentItem = item,
                        providerEnrichment = enrichment,
                        externalMeta = null,
                        tmdbSettings = settings
                    ))
                } catch (e: Exception) {
                    Log.w(HomeViewModel.TAG, "Hero enrichment failed for ${item.id}: ${e.message}")
                    item
                }
            }
        }.awaitAll()
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
    var changed = false

    fun updateItems(items: List<MetaPreview>): List<MetaPreview> {
        var updatedItems: MutableList<MetaPreview>? = null
        items.forEachIndexed { index, item ->
            if (item.id != itemId || item.tomatoesRating == tomatoesRating) return@forEachIndexed
            val nextItems = updatedItems ?: items.toMutableList().also { updatedItems = it }
            nextItems[index] = item.copy(tomatoesRating = tomatoesRating)
        }
        val result = updatedItems?.toList() ?: items
        if (result !== items) {
            changed = true
        }
        return result
    }

    fun updateCustomCatalogs(
        catalogs: List<com.nexio.tv.data.repository.TraktCustomListCatalog>
    ): List<com.nexio.tv.data.repository.TraktCustomListCatalog> {
        var updatedCatalogs: MutableList<com.nexio.tv.data.repository.TraktCustomListCatalog>? = null
        catalogs.forEachIndexed { index, catalog ->
            val updatedItems = updateItems(catalog.items)
            if (updatedItems === catalog.items) return@forEachIndexed
            val nextCatalogs = updatedCatalogs ?: catalogs.toMutableList().also { updatedCatalogs = it }
            nextCatalogs[index] = catalog.copy(items = updatedItems)
        }
        return updatedCatalogs?.toList() ?: catalogs
    }

    val updatedSnapshot = snapshot.copy(
        calendarItems = updateItems(snapshot.calendarItems),
        recommendationMovieItems = updateItems(snapshot.recommendationMovieItems),
        recommendationShowItems = updateItems(snapshot.recommendationShowItems),
        trendingMovieItems = updateItems(snapshot.trendingMovieItems),
        trendingShowItems = updateItems(snapshot.trendingShowItems),
        popularMovieItems = updateItems(snapshot.popularMovieItems),
        popularShowItems = updateItems(snapshot.popularShowItems),
        customListCatalogs = updateCustomCatalogs(snapshot.customListCatalogs)
    )
    return if (changed) updatedSnapshot else snapshot
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
    var changed = false

    val updatedCatalogs = snapshot.customListCatalogs.map { catalog ->
        var catalogChanged = false
        val updatedItems = catalog.items.map { item ->
            if (item.id != itemId || item.tomatoesRating == tomatoesRating) {
                item
            } else {
                catalogChanged = true
                item.copy(tomatoesRating = tomatoesRating)
            }
        }
        if (catalogChanged) {
            changed = true
            catalog.copy(items = updatedItems)
        } else {
            catalog
        }
    }

    return if (changed) {
        snapshot.copy(customListCatalogs = updatedCatalogs)
    } else {
        snapshot
    }
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
    return (!item.type.isHomeTvContent() && !currentTmdbSettings.isActive && !externalMetaPrefetchEnabled) ||
        item.id in prefetchedTmdbIds ||
        item.id in prefetchedExternalMetaIds
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
