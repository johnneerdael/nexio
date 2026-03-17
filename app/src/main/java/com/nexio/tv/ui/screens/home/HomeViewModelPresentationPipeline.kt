package com.nexio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.TmdbSettings
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CoreLayoutPrefs(
    val layout: HomeLayout,
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val posterLabelsEnabled: Boolean,
    val catalogAddonNameEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val hideUnreleasedContent: Boolean
)

private data class FocusedBackdropPrefs(
    val expandEnabled: Boolean,
    val expandDelaySeconds: Int
)

private data class LayoutUiPrefs(
    val layout: HomeLayout,
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val posterLabelsEnabled: Boolean,
    val catalogAddonNameEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val hideUnreleasedContent: Boolean,
    val modernLandscapePostersEnabled: Boolean,
    val focusedBackdropExpandEnabled: Boolean,
    val focusedBackdropExpandDelaySeconds: Int,
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
                catalogTypeSuffixEnabled = true,
                hideUnreleasedContent = false
            )
        },
        layoutPreferenceDataStore.catalogTypeSuffixEnabled,
        layoutPreferenceDataStore.hideUnreleasedContent
    ) { corePrefs, catalogTypeSuffixEnabled, hideUnreleasedContent ->
        corePrefs.copy(
            catalogTypeSuffixEnabled = catalogTypeSuffixEnabled,
            hideUnreleasedContent = hideUnreleasedContent
        )
    }

    val focusedBackdropPrefsFlow = combine(
        layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled,
        layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds
    ) { expandEnabled, expandDelaySeconds ->
        FocusedBackdropPrefs(
            expandEnabled = expandEnabled,
            expandDelaySeconds = expandDelaySeconds
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
            hideUnreleasedContent = corePrefs.hideUnreleasedContent,
            modernLandscapePostersEnabled = false,
            focusedBackdropExpandEnabled = focusedBackdropPrefs.expandEnabled,
            focusedBackdropExpandDelaySeconds = focusedBackdropPrefs.expandDelaySeconds,
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
                        previousState.homeLayout != prefs.layout ||
                        previousState.hideUnreleasedContent != prefs.hideUnreleasedContent
                currentHeroCatalogKeys = prefs.heroCatalogKeys
                _uiState.update {
                    it.copy(
                        homeLayout = prefs.layout,
                        heroCatalogKeys = prefs.heroCatalogKeys,
                        heroSectionEnabled = prefs.heroSectionEnabled,
                        posterLabelsEnabled = effectivePosterLabelsEnabled,
                        catalogAddonNameEnabled = prefs.catalogAddonNameEnabled,
                        catalogTypeSuffixEnabled = prefs.catalogTypeSuffixEnabled,
                        hideUnreleasedContent = prefs.hideUnreleasedContent,
                        modernLandscapePostersEnabled = prefs.modernLandscapePostersEnabled,
                        focusedPosterBackdropExpandEnabled = prefs.focusedBackdropExpandEnabled,
                        focusedPosterBackdropExpandDelaySeconds = prefs.focusedBackdropExpandDelaySeconds,
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

internal fun HomeViewModel.onItemFocusPipeline(item: MetaPreview) {
    if (startupRefreshPending || catalogsLoadInProgress || traktDiscoveryRefreshInProgress || mdbListDiscoveryRefreshInProgress) {
        return
    }
    if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) return
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
        if (pendingTmdbEnrichItemId != item.id) {
            if (_enrichingItemId.value == item.id) {
                setEnrichingItemId(null)
            }
            return@launch
        }
        if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) {
            if (_enrichingItemId.value == item.id) {
                setEnrichingItemId(null)
            }
            return@launch
        }

        try {
            var tmdbEnriched = false
            if (currentTmdbSettings.isActive) {
                val tmdbId = withContext(Dispatchers.IO) {
                    runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                }
                val enrichment = if (tmdbId != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            tmdbMetadataService.fetchEnrichment(
                                tmdbId = tmdbId,
                                contentType = item.type
                            )
                        }.getOrNull()
                    }
                } else {
                    null
                }
                if (enrichment != null && pendingTmdbEnrichItemId == item.id) {
                    prefetchedTmdbIds.add(item.id)
                    prefetchedExternalMetaIds.add(item.id)
                    updateCatalogItemWithTmdb(item.id, enrichment)
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

internal fun HomeViewModel.preloadAdjacentItemPipeline(item: MetaPreview) {
    if (startupRefreshPending || catalogsLoadInProgress || traktDiscoveryRefreshInProgress || mdbListDiscoveryRefreshInProgress) {
        return
    }
    if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) return
    if (pendingTmdbEnrichItemId == item.id || pendingAdjacentPrefetchItemId == item.id) return

    pendingAdjacentPrefetchItemId = item.id
    adjacentItemPrefetchJob?.cancel()
    adjacentItemPrefetchJob = viewModelScope.launch {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS)
        if (pendingAdjacentPrefetchItemId != item.id) return@launch
        if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) return@launch

        try {
            var tmdbEnriched = false
            if (currentTmdbSettings.isActive) {
                val tmdbId = withContext(Dispatchers.IO) {
                    runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                }
                val enrichment = if (tmdbId != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            tmdbMetadataService.fetchEnrichment(
                                tmdbId = tmdbId,
                                contentType = item.type
                            )
                        }.getOrNull()
                    }
                } else {
                    null
                }
                if (enrichment != null) {
                    prefetchedTmdbIds.add(item.id)
                    prefetchedExternalMetaIds.add(item.id)
                    updateCatalogItemWithTmdb(item.id, enrichment)
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

private fun HomeViewModel.updateCatalogItemWithTmdb(itemId: String, enrichment: TmdbEnrichment) {
    pendingTmdbEnrichmentByItemId[itemId] = enrichment
    scheduleMetadataEnrichmentFlushPipeline()
}

private fun HomeViewModel.updateCatalogItemWithMeta(itemId: String, meta: Meta) {
    pendingMetaEnrichmentByItemId[itemId] = meta
    scheduleMetadataEnrichmentFlushPipeline()
}

private fun HomeViewModel.scheduleMetadataEnrichmentFlushPipeline() {
    metadataEnrichmentFlushJob?.cancel()
    metadataEnrichmentFlushJob = viewModelScope.launch {
        delay(HomeViewModel.FOCUS_ENRICHMENT_BATCH_WINDOW_MS)
        flushMetadataEnrichmentPipeline()
    }
}

private fun HomeViewModel.flushMetadataEnrichmentPipeline() {
    if (pendingTmdbEnrichmentByItemId.isEmpty() && pendingMetaEnrichmentByItemId.isEmpty()) {
        return
    }
    val tmdbByItemId = pendingTmdbEnrichmentByItemId.toMap()
    val metaByItemId = pendingMetaEnrichmentByItemId.toMap()
    pendingTmdbEnrichmentByItemId.clear()
    pendingMetaEnrichmentByItemId.clear()

    var changed = false
    val changedCatalogKeys = mutableSetOf<String>()
    catalogsMap.forEach { (key, row) ->
        var mutableItems: MutableList<MetaPreview>? = null
        row.items.forEachIndexed { index, currentItem ->
            val mergedItem = mergeFocusedItemEnrichment(
                currentItem = currentItem,
                tmdbEnrichment = tmdbByItemId[currentItem.id],
                externalMeta = metaByItemId[currentItem.id]
            )
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

private fun HomeViewModel.mergeFocusedItemEnrichment(
    currentItem: MetaPreview,
    tmdbEnrichment: TmdbEnrichment?,
    externalMeta: Meta?
): MetaPreview {
    var merged = currentItem
    if (tmdbEnrichment != null) {
        if (currentTmdbSettings.useArtwork) {
            merged = merged.copy(
                background = tmdbEnrichment.backdrop ?: merged.background,
                logo = tmdbEnrichment.logo ?: merged.logo,
                poster = tmdbEnrichment.poster ?: merged.poster
            )
        }
        if (currentTmdbSettings.useBasicInfo) {
            merged = merged.copy(
                name = tmdbEnrichment.localizedTitle ?: merged.name,
                description = tmdbEnrichment.description ?: merged.description,
                imdbRating = tmdbEnrichment.rating?.toFloat() ?: merged.imdbRating,
                genres = if (tmdbEnrichment.genres.isNotEmpty()) tmdbEnrichment.genres else merged.genres
            )
        }
        if (currentTmdbSettings.useDetails) {
            merged = merged.copy(
                releaseInfo = tmdbEnrichment.releaseInfo ?: merged.releaseInfo
            )
        }
    }
    if (externalMeta != null) {
        merged = merged.copy(
            background = externalMeta.background ?: merged.background,
            logo = externalMeta.logo ?: merged.logo,
            description = externalMeta.description ?: merged.description,
            imdbRating = externalMeta.imdbRating ?: merged.imdbRating,
            genres = if (externalMeta.genres.isNotEmpty()) externalMeta.genres else merged.genres
        )
    }
    return merged
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
                    val tmdbId = tmdbService.ensureTmdbId(item.id, item.apiType) ?: return@async item
                    val enrichment = tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = item.type
                    ) ?: return@async item

                    var enriched = item

                    if (settings.useArtwork) {
                        enriched = enriched.copy(
                            background = enrichment.backdrop ?: enriched.background,
                            logo = enrichment.logo ?: enriched.logo,
                            poster = enrichment.poster ?: enriched.poster
                        )
                    }

                    if (settings.useBasicInfo) {
                        enriched = enriched.copy(
                            name = enrichment.localizedTitle ?: enriched.name,
                            description = enrichment.description ?: enriched.description,
                            genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else enriched.genres,
                            imdbRating = enrichment.rating?.toFloat() ?: enriched.imdbRating
                        )
                    }

                    if (settings.useDetails) {
                        enriched = enriched.copy(
                            releaseInfo = enrichment.releaseInfo ?: enriched.releaseInfo
                        )
                    }

                    enriched
                } catch (e: Exception) {
                    Log.w(HomeViewModel.TAG, "Hero enrichment failed for ${item.id}: ${e.message}")
                    item
                }
            }
        }.awaitAll()
    }
}

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
        append(tmdbMetadataService.currentTmdbLanguageTag())
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
