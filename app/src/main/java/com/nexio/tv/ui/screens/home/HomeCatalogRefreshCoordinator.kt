package com.nexio.tv.ui.screens.home

import android.content.Context
import coil.imageLoader
import coil.annotation.ExperimentalCoilApi
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.skipStep
import com.nexio.tv.domain.model.supportsExtra
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.MetaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
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
        previous.poster != item.poster || previous.background != item.background || previous.logo != item.logo
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
    private val metaRepository: MetaRepository,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    @ApplicationContext private val appContext: Context
) {
    private val refreshMutex = Mutex()

    internal suspend fun refreshSerially(
        addons: List<Addon>,
        telemetryEnabled: Boolean,
        isCatalogDisabled: (Addon, CatalogDescriptor) -> Boolean,
        getCurrentRow: (String) -> CatalogRow?,
        isItemReferencedElsewhere: (String, String) -> Boolean,
        onCatalogReady: (String, CatalogRow, CatalogItemDiff) -> Unit,
        onLog: (String, String?) -> Unit
    ): Int {
        var refreshedCatalogCount = 0
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

                        var metadataCachedCount = 0
                        var metadataFetchCount = 0
                        var metadataRetainedMissingCount = 0
                        var metadataFetchedNewCount = 0
                        var metadataFetchedRetainedCount = 0
                        val languageTag = AppLocaleResolver.resolveEffectiveAppLanguageTag(appContext)
                        val changedKeys = diff.addedOrChanged
                            .asSequence()
                            .map { "${it.apiType}:${it.id}" }
                            .toHashSet()
                        onLog("metadata_hydrate_start", "catalogKey=$catalogKey items=${refreshed.items.size}")
                        refreshed.items.forEach { item ->
                            val itemKey = "${item.apiType}:${item.id}"
                            val hasCachedMetadata = metadataDiskCacheStore.hasCurrentMetaForItem(
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
                            if (itemKey !in changedKeys) {
                                metadataRetainedMissingCount += 1
                                metadataFetchedRetainedCount += 1
                            } else {
                                metadataFetchedNewCount += 1
                            }
                            metadataFetchCount += 1
                            if (telemetryEnabled) {
                                onLog("item_metadata_fetch", "catalogKey=$catalogKey itemKey=$itemKey")
                            }
                            runCatching {
                                metaRepository.getMetaFromAllAddons(
                                    type = item.apiType,
                                    id = item.id,
                                    cacheOnDisk = true,
                                    origin = "home_catalog_refresh"
                                )
                                    .first { result -> result !is com.nexio.tv.core.network.NetworkResult.Loading }
                            }
                        }
                        onLog(
                            "metadata_hydrate_end",
                            "catalogKey=$catalogKey total=${refreshed.items.size} cached=$metadataCachedCount fetched=$metadataFetchCount " +
                                "fetched_new=$metadataFetchedNewCount fetched_retained_missing=$metadataFetchedRetainedCount retained_missing=$metadataRetainedMissingCount"
                        )

                        val imageTelemetry = buildImagePrefetchTelemetry(refreshed.items)
                        onLog(
                            "image_prefetch_start",
                            "catalogKey=$catalogKey items=${imageTelemetry.itemsConsidered} urls_total=${imageTelemetry.totalUrls} urls_cached=${imageTelemetry.cachedUrls} urls_missing=${imageTelemetry.missingUrls}"
                        )
                        if (telemetryEnabled) {
                            imageTelemetry.itemEvents.forEach { itemEvent ->
                                onLog(itemEvent.first, "catalogKey=$catalogKey ${itemEvent.second}")
                            }
                        }
                        prefetchImageUrls(imageTelemetry.urlsToFetch)
                        onLog(
                            "image_prefetch_end",
                            "catalogKey=$catalogKey fetched_urls=${imageTelemetry.urlsToFetch.size} skipped_cached_urls=${imageTelemetry.cachedUrls} " +
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

                        onCatalogReady(catalogKey, refreshed, diff)
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

        onLog("metadata_hydrate_start", "catalogKey=$catalogKey items=${uniqueItems.size}")
        uniqueItems.forEach { item ->
            val itemKey = "${item.apiType}:${item.id}"
            val hasCachedMetadata = metadataDiskCacheStore.hasCurrentMetaForItem(
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
            metadataFetchCount += 1
            if (telemetryEnabled) {
                onLog("item_metadata_fetch", "catalogKey=$catalogKey itemKey=$itemKey")
            }
            runCatching {
                metaRepository.getMetaFromAllAddons(
                    type = item.apiType,
                    id = item.id,
                    cacheOnDisk = true,
                    origin = "home_visible_hydration"
                )
                    .first { result -> result !is com.nexio.tv.core.network.NetworkResult.Loading }
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
        prefetchImageUrls(imageTelemetry.urlsToFetch)
        onLog(
            "image_prefetch_end",
            "catalogKey=$catalogKey fetched_urls=${imageTelemetry.urlsToFetch.size} skipped_cached_urls=${imageTelemetry.cachedUrls} " +
                "items_cached=${imageTelemetry.itemsFullyCached} items_fetched=${imageTelemetry.itemsNeedingFetch}"
        )
    }

    private data class ImagePrefetchTelemetry(
        val urlsToFetch: List<String>,
        val totalUrls: Int,
        val cachedUrls: Int,
        val missingUrls: Int,
        val itemsConsidered: Int,
        val itemsFullyCached: Int,
        val itemsNeedingFetch: Int,
        val itemEvents: List<Pair<String, String>>
    )

    private fun buildImagePrefetchTelemetry(items: List<MetaPreview>): ImagePrefetchTelemetry {
        val orderedUrls = linkedSetOf<String>()
        val itemEvents = mutableListOf<Pair<String, String>>()
        var cachedUrls = 0
        var missingUrls = 0
        var itemsFullyCached = 0
        var itemsNeedingFetch = 0

        items.forEach { item ->
            val itemKey = "${item.apiType}:${item.id}"
            val urls = listOf(item.poster, item.background, item.logo)
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
            if (urls.isEmpty()) {
                itemEvents += "item_image_skipped_no_urls" to "itemKey=$itemKey"
                return@forEach
            }
            val missingForItem = urls.filterNot { hasImageCached(it) }
            cachedUrls += (urls.size - missingForItem.size)
            missingUrls += missingForItem.size
            orderedUrls.addAll(missingForItem)
            if (missingForItem.isEmpty()) {
                itemsFullyCached += 1
                itemEvents += "item_image_cached" to "itemKey=$itemKey urls=${urls.size}"
            } else {
                itemsNeedingFetch += 1
                itemEvents += "item_image_fetch" to "itemKey=$itemKey urls=${missingForItem.size}/${urls.size}"
            }
        }

        return ImagePrefetchTelemetry(
            urlsToFetch = orderedUrls.toList(),
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
    private fun hasImageCached(url: String): Boolean {
        val imageLoader = appContext.imageLoader
        val memoryCached = imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(url)) != null
        if (memoryCached) return true
        val diskCache = imageLoader.diskCache ?: return false
        return runCatching {
            val snapshot = diskCache.openSnapshot(url) ?: return@runCatching false
            snapshot.close()
            true
        }.getOrDefault(false)
    }

    private suspend fun prefetchImageUrls(urls: List<String>) {
        if (urls.isEmpty()) return
        val imageLoader = appContext.imageLoader
        urls.forEach { url ->
            runCatching {
                imageLoader.execute(
                    ImageRequest.Builder(appContext)
                        .data(url)
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
