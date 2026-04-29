package com.nexio.tv.ui.screens.home

import android.content.Context
import coil.imageLoader
import coil.annotation.ExperimentalCoilApi
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.nexio.tv.core.image.ArtworkImageCacheKeys
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.player.PlaybackActivityTracker
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.search.AndroidTvSearchRuntimeReadiness
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.tvdb.ProviderLocalizedMetadataResolver
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.applyTo
import com.nexio.tv.domain.model.mergeFallback
import com.nexio.tv.domain.model.orDefault
import com.nexio.tv.domain.model.skipStep
import com.nexio.tv.domain.model.supportsExtra
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import com.nexio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class CatalogItemDiff(
    val addedOrChanged: List<MetaPreview>,
    val removed: List<MetaPreview>
)

internal fun diffCatalogItems(oldItems: List<MetaPreview>, newItems: List<MetaPreview>): CatalogItemDiff {
    val oldByKey = oldItems.associateBy { "${it.apiType}:${it.id}" }
    val newByKey = newItems.associateBy { "${it.apiType}:${it.id}" }

    val addedOrChanged = newItems.filter { item ->
        val key = "${item.apiType}:${item.id}"
        val previous = oldByKey[key] ?: return@filter true
        previous != item
    }
    val removed = oldItems.filter { item ->
        val key = "${item.apiType}:${item.id}"
        key !in newByKey
    }
    return CatalogItemDiff(
        addedOrChanged = addedOrChanged,
        removed = removed
    )
}

@Singleton
class HomeCatalogRefreshCoordinator @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val titleRatingOverrideRepository: TitleRatingOverrideRepository,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val metadataRouterFacade: MetadataRouterFacade,
    private val providerLocalizedMetadataResolver: ProviderLocalizedMetadataResolver,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val profileBoundary: ProfileBoundary,
    private val playbackActivityTracker: PlaybackActivityTracker,
    @ApplicationContext private val appContext: Context
) {
    /**
     * Overlay provider-localized title/description/genres on top of the
     * addon-sourced MetaPreview. This ensures every item on Modern Home
     * respects the app display language (addons ship English-only text).
     * The provider fetch populates a language-keyed disk cache that
     * the Detail screen will later hit, so this does not introduce duplicate
     * metadata API traffic — it reuses the same cache.
     */
    internal suspend fun overlayProviderLocalizedMetadata(
        item: MetaPreview,
        onLog: (String, String?) -> Unit = { _, _ -> }
    ): MetaPreview {
        return overlayProviderLocalizedMetadataForHome(
            item = item,
            providerLocalizedMetadataResolver = providerLocalizedMetadataResolver,
            profileBoundary = profileBoundary,
            onLog = onLog
        )
    }

    private val refreshMutex = Mutex()

    internal suspend fun hydrateAndPrefetchRows(
        rows: List<CatalogRow>,
        existingRowsByKey: Map<String, CatalogRow> = emptyMap(),
        telemetryEnabled: Boolean,
        onLog: (String, String?) -> Unit
    ): List<CatalogRow> {
        if (rows.isEmpty()) return rows
        val activePosterProvider = posterRatingsUrlResolver.getActiveProvider()
        val languageTag = AppLocaleResolver.resolveEffectiveAppLanguageTag(appContext)
        val hydratedRows = rows.map { row ->
            val rowKey = homeCatalogGlobalKey(row)
            val oldItems = existingRowsByKey[rowKey]?.items.orEmpty()
            val diff = diffCatalogItems(oldItems = oldItems, newItems = row.items)
            val changedKeys = diff.addedOrChanged
                .asSequence()
                .map { "${it.apiType}:${it.id}" }
                .toHashSet()
            val oldItemsByKey = oldItems.associateBy { "${it.apiType}:${it.id}" }

            val hydratedItems = row.items.map { item ->
                val itemKey = "${item.apiType}:${item.id}"
                val persistedFallback = oldItemsByKey[itemKey]
                if (shouldReusePersistedHomeItem(
                        itemChanged = itemKey in changedKeys,
                        persistedFallback = persistedFallback
                    )
                ) {
                    return@map persistedFallback!!
                }
                val hasCachedMetadata = metadataDiskCacheStore.hasCurrentMetaForItem(
                    itemKey = itemKey,
                    languageTag = languageTag
                )
                if (telemetryEnabled) {
                    onLog(
                        "item_metadata_overlay",
                        "catalogKey=$rowKey itemKey=$itemKey cache_current=$hasCachedMetadata"
                    )
                }
                val merged = mergePersistedHomeDisplayMetadata(
                    currentItem = item,
                    persistedFallback = persistedFallback,
                    externalMeta = null
                )
                val localized = overlayProviderLocalizedMetadata(merged, onLog)
                val enriched = titleRatingOverrideRepository.enrichPreview(localized)
                posterRatingsUrlResolver.apply(enriched, activePosterProvider)
            }
            row.copy(items = hydratedItems)
        }

        val flattenedItems = hydratedRows.flatMap { it.items }
        val imageTelemetry = buildImagePrefetchTelemetry(flattenedItems)
        onLog(
            "image_prefetch_start",
            "catalogKey=synthetic_home items=${imageTelemetry.itemsConsidered} urls_total=${imageTelemetry.totalUrls} urls_cached=${imageTelemetry.cachedUrls} urls_missing=${imageTelemetry.missingUrls}"
        )
        if (telemetryEnabled) {
            imageTelemetry.itemEvents.forEach { itemEvent ->
                onLog(itemEvent.first, "catalogKey=synthetic_home ${itemEvent.second}")
            }
        }
        prefetchImageEntries(imageTelemetry.entriesToFetch)
        onLog(
            "image_prefetch_end",
            "catalogKey=synthetic_home fetched_urls=${imageTelemetry.entriesToFetch.size} skipped_cached_urls=${imageTelemetry.cachedUrls} " +
                "items_cached=${imageTelemetry.itemsFullyCached} items_fetched=${imageTelemetry.itemsNeedingFetch}"
        )

        return hydratedRows
    }

    internal suspend fun refreshSerially(
        addons: List<Addon>,
        telemetryEnabled: Boolean,
        isCatalogDisabled: (Addon, CatalogDescriptor) -> Boolean,
        getCurrentRow: suspend (String) -> CatalogRow?,
        isItemReferencedElsewhere: suspend (String, String) -> Boolean,
        onCatalogReady: suspend (String, CatalogRow, CatalogItemDiff) -> Unit,
        onLog: (String, String?) -> Unit
    ): Int {
        var refreshedCatalogCount = 0
        val activePosterProvider = posterRatingsUrlResolver.getActiveProvider()
        refreshMutex.withLock {
            addons.forEach { addon ->
                addon.catalogs
                    .asSequence()
                    .filterNot { it.isSearchOnlyCatalog() }
                    .filterNot { isCatalogDisabled(addon, it) }
                    .forEach { catalog ->
                        val supportsSkip = catalog.supportsExtra("skip")
                        val refreshed = catalogRepository.refreshCatalogToDisk(
                            addonBaseUrl = addon.baseUrl,
                            addonId = addon.id,
                            addonName = addon.displayName,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            type = catalog.apiType,
                            skip = 0,
                            skipStep = catalog.skipStep(),
                            supportsSkip = supportsSkip
                        ).getOrNull() ?: return@forEach
                        refreshedCatalogCount += 1

                        val catalogKey = "${addon.id}_${catalog.apiType}_${catalog.id}"
                        val oldItems = getCurrentRow(catalogKey)?.items.orEmpty()
                        val diff = diffCatalogItems(oldItems = oldItems, newItems = refreshed.items)
                        val oldItemKeys = oldItems.asSequence()
                            .map { "${it.apiType}:${it.id}" }
                            .toSet()
                        val retainedCount = refreshed.items.count { "${it.apiType}:${it.id}" in oldItemKeys }
                        val newCount = diff.addedOrChanged.size
                        val removedCount = diff.removed.size

                        onLog(
                            "catalog_refresh_stats",
                            "catalogKey=$catalogKey total=${refreshed.items.size} retained=$retainedCount refreshed=$newCount removed=$removedCount"
                        )

                        var metadataOverlayCount = 0
                        var metadataOverlayCacheCurrentCount = 0
                        var metadataOverlayCacheMissingCount = 0
                        var metadataOverlayNewMissingCount = 0
                        var metadataOverlayRetainedMissingCount = 0
                        val languageTag = AppLocaleResolver.resolveEffectiveAppLanguageTag(appContext)
                        val changedKeys = diff.addedOrChanged
                            .asSequence()
                            .map { "${it.apiType}:${it.id}" }
                            .toHashSet()
                        val oldItemsByKey = oldItems.associateBy { "${it.apiType}:${it.id}" }
                        onLog("metadata_overlay_start", "catalogKey=$catalogKey items=${refreshed.items.size}")
                        val hydratedItems = refreshed.items.map { item ->
                            val itemKey = "${item.apiType}:${item.id}"
                            val persistedFallback = oldItemsByKey[itemKey]
                            if (shouldReusePersistedHomeItem(
                                    itemChanged = itemKey in changedKeys,
                                    persistedFallback = persistedFallback
                                )
                            ) {
                                return@map persistedFallback!!
                            }
                            val hasCachedMetadata = metadataDiskCacheStore.hasCurrentMetaForItem(
                                itemKey = itemKey,
                                languageTag = languageTag
                            )
                            metadataOverlayCount += 1
                            if (hasCachedMetadata) {
                                metadataOverlayCacheCurrentCount += 1
                            } else {
                                metadataOverlayCacheMissingCount += 1
                                if (itemKey in changedKeys) {
                                    metadataOverlayNewMissingCount += 1
                                } else {
                                    metadataOverlayRetainedMissingCount += 1
                                }
                            }
                            if (telemetryEnabled) {
                                onLog(
                                    "item_metadata_overlay",
                                    "catalogKey=$catalogKey itemKey=$itemKey cache_current=$hasCachedMetadata"
                                )
                            }
                            val merged = mergePersistedHomeDisplayMetadata(
                                currentItem = item,
                                persistedFallback = persistedFallback,
                                externalMeta = null
                            )
                            val localized = overlayProviderLocalizedMetadata(merged, onLog)
                            val enriched = titleRatingOverrideRepository.enrichPreview(localized)
                            posterRatingsUrlResolver.apply(enriched, activePosterProvider)
                        }
                        val refreshedHydrated = refreshed.copy(items = hydratedItems)
                        onLog(
                            "metadata_overlay_end",
                            "catalogKey=$catalogKey total=${refreshedHydrated.items.size} overlay=$metadataOverlayCount " +
                                "cache_current=$metadataOverlayCacheCurrentCount cache_missing=$metadataOverlayCacheMissingCount " +
                                "new_missing=$metadataOverlayNewMissingCount retained_missing=$metadataOverlayRetainedMissingCount"
                        )

                        val imageTelemetry = buildImagePrefetchTelemetry(refreshedHydrated.items)
                        onLog(
                            "image_prefetch_start",
                            "catalogKey=$catalogKey items=${imageTelemetry.itemsConsidered} urls_total=${imageTelemetry.totalUrls} urls_cached=${imageTelemetry.cachedUrls} urls_missing=${imageTelemetry.missingUrls}"
                        )
                        if (telemetryEnabled) {
                            imageTelemetry.itemEvents.forEach { itemEvent ->
                                onLog(itemEvent.first, "catalogKey=$catalogKey ${itemEvent.second}")
                            }
                        }
                        prefetchImageEntries(imageTelemetry.entriesToFetch)
                        onLog(
                            "image_prefetch_end",
                            "catalogKey=$catalogKey fetched_urls=${imageTelemetry.entriesToFetch.size} skipped_cached_urls=${imageTelemetry.cachedUrls} " +
                                "items_cached=${imageTelemetry.itemsFullyCached} items_fetched=${imageTelemetry.itemsNeedingFetch}"
                        )

                        diff.removed.forEach { removed ->
                            val itemKey = "${removed.apiType}:${removed.id}"
                            if (!isItemReferencedElsewhere(itemKey, catalogKey)) {
                                val urls = metadataDiskCacheStore.removeMetaEntriesForItem(itemKey)
                                evictImageUrls(urls)
                                onLog("cleanup_removed_item", "itemKey=$itemKey removedUrls=${urls.size}")
                            }
                        }

                        onCatalogReady(catalogKey, refreshedHydrated, diff)
                        onLog("catalog_publish_ready", "catalogKey=$catalogKey")
                    }
            }
        }
        return refreshedCatalogCount
    }

    internal fun evictCachedImageUrls(urls: List<String>) {
        evictImageUrls(urls)
    }

    internal suspend fun hydrateAndPrefetchVisibleItems(
        items: List<MetaPreview>,
        telemetryEnabled: Boolean,
        onLog: (String, String?) -> Unit
    ) {
        val uniqueItems = items.distinctBy { "${it.apiType}:${it.id}" }
        if (uniqueItems.isEmpty()) return

        var metadataCachedCount = 0
        var metadataFetchCount = 0
        val languageTag = AppLocaleResolver.resolveEffectiveAppLanguageTag(appContext)
        val catalogKey = "visible_home"
        val activePosterProvider = posterRatingsUrlResolver.getActiveProvider()
        val runtimeHydrationKeys = AndroidTvSearchRuntimeReadiness
            .prioritizeMissingRuntimeCandidates(uniqueItems)
            .map { "${it.apiType}:${it.id}" }
            .toSet()

        onLog("metadata_hydrate_start", "catalogKey=$catalogKey items=${uniqueItems.size}")
        uniqueItems.forEach { item ->
            val itemKey = "${item.apiType}:${item.id}"
            val hasCachedMetadata = metadataDiskCacheStore.hasCurrentMetaForItem(
                itemKey = itemKey,
                languageTag = languageTag
            ) || metadataDiskCacheStore.hasCurrentHomeDisplayMetadataForItem(
                itemKey = itemKey,
                languageTag = languageTag
            )
            if (hasCachedMetadata) {
                metadataCachedCount += 1
                if (telemetryEnabled) {
                    onLog("item_metadata_cached", "catalogKey=$catalogKey itemKey=$itemKey")
                }
                return@forEach
            }
            if (itemKey !in runtimeHydrationKeys) {
                return@forEach
            }
            metadataFetchCount += 1
            if (telemetryEnabled) {
                onLog("item_metadata_fetch", "catalogKey=$catalogKey itemKey=$itemKey")
            }
            runCatching {
                val hydrated = overlayProviderLocalizedMetadata(item, onLog)
                val applied = posterRatingsUrlResolver.apply(hydrated, activePosterProvider)
                if (applied != item) {
                    metadataDiskCacheStore.writeHomeDisplayMetadata(
                        itemKey = itemKey,
                        languageTag = languageTag,
                        metadata = applied.toHomeDisplayMetadata()
                    )
                }
            }
        }
        onLog(
            "metadata_hydrate_end",
            "catalogKey=$catalogKey total=${uniqueItems.size} cached=$metadataCachedCount fetched=$metadataFetchCount retained_missing=0"
        )

        val imageTelemetry = buildImagePrefetchTelemetry(uniqueItems)
        onLog(
            "image_prefetch_start",
            "catalogKey=$catalogKey items=${imageTelemetry.itemsConsidered} urls_total=${imageTelemetry.totalUrls} urls_cached=${imageTelemetry.cachedUrls} urls_missing=${imageTelemetry.missingUrls}"
        )
        if (telemetryEnabled) {
            imageTelemetry.itemEvents.forEach { itemEvent ->
                onLog(itemEvent.first, "catalogKey=$catalogKey ${itemEvent.second}")
            }
        }
        prefetchImageEntries(imageTelemetry.entriesToFetch)
        onLog(
            "image_prefetch_end",
            "catalogKey=$catalogKey fetched_urls=${imageTelemetry.entriesToFetch.size} skipped_cached_urls=${imageTelemetry.cachedUrls} " +
                "items_cached=${imageTelemetry.itemsFullyCached} items_fetched=${imageTelemetry.itemsNeedingFetch}"
        )
    }

    private data class ImageCacheEntry(
        val url: String,
        val diskCacheKey: String
    )

    private data class ImagePrefetchTelemetry(
        val entriesToFetch: List<ImageCacheEntry>,
        val totalUrls: Int,
        val cachedUrls: Int,
        val missingUrls: Int,
        val itemsConsidered: Int,
        val itemsFullyCached: Int,
        val itemsNeedingFetch: Int,
        val itemEvents: List<Pair<String, String>>
    )

    private fun buildImagePrefetchTelemetry(items: List<MetaPreview>): ImagePrefetchTelemetry {
        val orderedEntries = linkedSetOf<ImageCacheEntry>()
        val itemEvents = mutableListOf<Pair<String, String>>()
        var cachedUrls = 0
        var missingUrls = 0
        var itemsFullyCached = 0
        var itemsNeedingFetch = 0

        items.forEach { item ->
            val itemKey = "${item.apiType}:${item.id}"
            val entries = buildList {
                item.poster?.trim()?.takeIf(String::isNotEmpty)?.let {
                    add(ImageCacheEntry(it, ArtworkImageCacheKeys.poster(item.id, item.posterProviderTag, it)))
                }
                item.background?.trim()?.takeIf(String::isNotEmpty)?.let {
                    add(ImageCacheEntry(it, ArtworkImageCacheKeys.backdrop(item.id)))
                }
                item.logo?.trim()?.takeIf(String::isNotEmpty)?.let {
                    add(ImageCacheEntry(it, ArtworkImageCacheKeys.logo(item.id)))
                }
            }
            if (entries.isEmpty()) {
                itemEvents += "item_image_skipped_no_urls" to "itemKey=$itemKey"
                return@forEach
            }
            val missingForItem = entries.filterNot { hasImageCached(it.diskCacheKey) }
            cachedUrls += (entries.size - missingForItem.size)
            missingUrls += missingForItem.size
            orderedEntries.addAll(missingForItem)
            if (missingForItem.isEmpty()) {
                itemsFullyCached += 1
                itemEvents += "item_image_cached" to "itemKey=$itemKey urls=${entries.size}"
            } else {
                itemsNeedingFetch += 1
                itemEvents += "item_image_fetch" to "itemKey=$itemKey urls=${missingForItem.size}/${entries.size}"
            }
        }

        return ImagePrefetchTelemetry(
            entriesToFetch = orderedEntries.toList(),
            totalUrls = cachedUrls + missingUrls,
            cachedUrls = cachedUrls,
            missingUrls = missingUrls,
            itemsConsidered = items.size,
            itemsFullyCached = itemsFullyCached,
            itemsNeedingFetch = itemsNeedingFetch,
            itemEvents = itemEvents
        )
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun hasImageCached(diskCacheKey: String): Boolean {
        val imageLoader = appContext.imageLoader
        val memoryCached = imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(diskCacheKey)) != null
        if (memoryCached) return true
        val diskCache = imageLoader.diskCache ?: return false
        return runCatching {
            val snapshot = diskCache.openSnapshot(diskCacheKey) ?: return@runCatching false
            snapshot.close()
            true
        }.getOrDefault(false)
    }

    private suspend fun prefetchImageEntries(entries: List<ImageCacheEntry>) {
        if (entries.isEmpty()) return
        if (playbackActivityTracker.isActive.value) return
        val imageLoader = appContext.imageLoader
        entries.forEach { entry ->
            runCatching {
                imageLoader.execute(
                    ImageRequest.Builder(appContext)
                        .data(entry.url)
                        .diskCacheKey(entry.diskCacheKey)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .build()
                )
            }
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun evictImageUrls(urls: List<String>) {
        if (urls.isEmpty()) return
        val imageLoader = appContext.imageLoader
        val diskCache = imageLoader.diskCache
        urls.forEach { url ->
            runCatching {
                diskCache?.remove(url)
                imageLoader.memoryCache?.remove(coil.memory.MemoryCache.Key(url))
            }
        }
    }
}

internal fun mergePersistedHomeDisplayMetadata(
    currentItem: MetaPreview,
    persistedFallback: MetaPreview?,
    externalMeta: Meta?
): MetaPreview {
    val mergedMetadata = (externalMeta?.toHomeDisplayMetadata() ?: currentItem.toHomeDisplayMetadata())
        .mergeFallback(persistedFallback?.toHomeDisplayMetadata())
    return mergedMetadata.applyTo(currentItem)
}

internal fun shouldReusePersistedHomeItem(
    itemChanged: Boolean,
    persistedFallback: MetaPreview?
): Boolean {
    return !itemChanged && persistedFallback?.tomatoesRating != null
}

internal fun MetaPreview.applyTvMetadataEnrichmentForHome(enrichment: TvMetadataEnrichment): MetaPreview {
    return copy(
        name = enrichment.localizedTitle?.takeIf { it.isNotBlank() } ?: name,
        description = enrichment.description?.takeIf { it.isNotBlank() } ?: description,
        genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else genres,
        releaseInfo = enrichment.releaseInfo ?: releaseInfo,
        runtime = (enrichment.runtimeMinutes ?: enrichment.averageRuntimeMinutes)?.let { "$it min" } ?: runtime,
        imdbRating = enrichment.rating?.toFloat() ?: imdbRating,
        ratingSource = if (enrichment.rating != null) enrichment.ratingSource.orDefault() else ratingSource.orDefault(),
        background = enrichment.backdrop ?: background,
        logo = enrichment.logo ?: logo,
        poster = enrichment.poster ?: poster
    )
}
