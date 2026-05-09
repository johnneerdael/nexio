package com.nexio.tv.ui.screens.home

import android.content.Context
import androidx.annotation.VisibleForTesting
import coil.imageLoader
import coil.annotation.ExperimentalCoilApi
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.emptyOrNull
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.core.artwork.takeIfImageType
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.core.image.ArtworkImageCacheKeys
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.player.PlaybackActivityTracker
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.tvdb.ProviderLocalizedMetadataResolver
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.applyTo
import com.nexio.tv.domain.model.mergeFallback
import com.nexio.tv.domain.model.skipStep
import com.nexio.tv.domain.model.supportsExtra
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import com.nexio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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

internal fun mergeHydratedCatalogRowIntoCurrent(
    rawRow: CatalogRow,
    hydratedRow: CatalogRow,
    currentRow: CatalogRow
): CatalogRow {
    val rawItemsByKey = rawRow.items.associateBy { "${it.apiType}:${it.id}" }
    val hydratedItemsByKey = hydratedRow.items.associateBy { "${it.apiType}:${it.id}" }
    val mergedItems = currentRow.items.map { currentItem ->
        val itemKey = "${currentItem.apiType}:${currentItem.id}"
        val rawItem = rawItemsByKey[itemKey] ?: return@map currentItem
        val hydratedItem = hydratedItemsByKey[itemKey] ?: return@map currentItem
        if (currentItem == rawItem) hydratedItem else currentItem
    }
    return currentRow.copy(items = mergedItems)
}

private data class SerialRefreshEntry(
    val catalogKey: String,
    val row: CatalogRow,
    val oldItems: List<MetaPreview>,
    val diff: CatalogItemDiff
)

@Singleton
class HomeCatalogRefreshCoordinator @Inject constructor(
    private val catalogRepository: CatalogRepository,
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
        val artworkProviderSettings = posterRatingsUrlResolver.currentSettings()
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
                posterRatingsUrlResolver.applyArtworkRef(
                    localized.withCompatiblePersistedInternalPoster(
                        persistedFallback = persistedFallback,
                        artworkProviderSettings = artworkProviderSettings
                    ),
                    artworkProviderSettings
                )
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
        onRawCatalogBatchComplete: suspend () -> Unit = {},
        onLog: (String, String?) -> Unit
    ): Int {
        var refreshedCatalogCount = 0
        refreshMutex.withLock {
            val refreshedEntries = mutableListOf<SerialRefreshEntry>()
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

                        onCatalogReady(catalogKey, refreshed, diff)
                        onLog("catalog_publish_ready", "catalogKey=$catalogKey")

                        refreshedEntries += SerialRefreshEntry(
                            catalogKey = catalogKey,
                            row = refreshed,
                            oldItems = oldItems,
                            diff = diff
                        )
                }
            }

            onRawCatalogBatchComplete()

            val hydratedRows = hydrateAndPrefetchRows(
                rows = refreshedEntries.map { it.row },
                existingRowsByKey = refreshedEntries.associate { entry ->
                    entry.catalogKey to entry.row.copy(items = entry.oldItems)
                },
                telemetryEnabled = telemetryEnabled,
                onLog = onLog
            )

            refreshedEntries.zip(hydratedRows).forEach { (entry, hydrated) ->
                if (hydrated != entry.row) {
                    val currentRow = getCurrentRow(entry.catalogKey)
                    val rowToPublish = currentRow?.let { current ->
                        mergeHydratedCatalogRowIntoCurrent(
                            rawRow = entry.row,
                            hydratedRow = hydrated,
                            currentRow = current
                        )
                    } ?: hydrated
                    if (rowToPublish != currentRow) {
                        onCatalogReady(
                            entry.catalogKey,
                            rowToPublish,
                            diffCatalogItems(currentRow?.items ?: entry.row.items, rowToPublish.items)
                        )
                        onLog("catalog_provider_overlay_ready", "catalogKey=${entry.catalogKey}")
                    }
                }

                entry.diff.removed.forEach { removed ->
                    val itemKey = "${removed.apiType}:${removed.id}"
                    if (!isItemReferencedElsewhere(itemKey, entry.catalogKey)) {
                        val urls = metadataDiskCacheStore.removeMetaEntriesForItem(itemKey)
                        evictImageUrls(urls)
                        onLog("cleanup_removed_item", "itemKey=$itemKey removedUrls=${urls.size}")
                    }
                }
            }
        }
        return refreshedCatalogCount
    }

    internal fun evictCachedImageUrls(urls: List<String>) {
        evictImageUrls(urls)
    }

    internal suspend fun prefetchVisibleImagesOnly(
        items: List<MetaPreview>,
        telemetryEnabled: Boolean,
        onLog: (String, String?) -> Unit
    ) {
        val uniqueItems = items.distinctBy { it.homeOverlayItemKey() }
        if (uniqueItems.isEmpty()) return

        val catalogKey = "visible_home"

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
            val metadata = item.toHomeDisplayMetadata()
            val entries = buildList {
                metadata.displayPoster?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.takeUnless(::isInternalArtworkRef)
                    ?.let {
                    add(ImageCacheEntry(it, ArtworkImageCacheKeys.poster(item.id, item.posterProviderTag, it)))
                }
                metadata.displayBackdrop?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.takeUnless(::isInternalArtworkRef)
                    ?.let {
                    add(ImageCacheEntry(it, ArtworkImageCacheKeys.backdrop(item.id)))
                }
                metadata.displayLogo?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.takeUnless(::isInternalArtworkRef)
                    ?.let {
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

    private fun isInternalArtworkRef(value: String): Boolean =
        value.startsWith("nexio-artwork://") || value.startsWith("nexio-placeholder://")

    @Deprecated("redundant after reducer; remove once Plan B (UI consumption migration) lands")
    private fun MetaPreview.withCompatiblePersistedInternalPoster(
        persistedFallback: MetaPreview?,
        artworkProviderSettings: ArtworkProviderSettings
    ): MetaPreview {
        val persistedPoster = persistedFallback?.poster?.takeIf(::isInternalArtworkRef) ?: return this
        val persistedTag = persistedFallback.posterProviderTag
        val activeProvider = artworkProviderSettings.selection.posterProvider
        val preserve = when (activeProvider) {
            ArtworkProviderChoiceKey.DEFAULT -> persistedTag == null
            else -> persistedTag == activeProvider.value
        }
        if (!preserve) return this
        if (poster == persistedPoster) return this
        return copy(
            poster = persistedPoster,
            posterProviderTag = persistedTag
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

    companion object {
        /**
         * Test entry-point: projects [rawRailItem] against [persistedFallback] using the
         * reducer so tests can assert non-downgrade behaviour without constructing a full
         * DI-backed coordinator instance.
         */
        @VisibleForTesting
        internal fun projectRailRowAgainstPersistedForTest(
            rawRailItem: MetaPreview,
            persistedFallback: MetaPreview?,
            externalMeta: MetaPreview?
        ): MetaPreview = projectRailRowAgainstPersisted(rawRailItem, persistedFallback, externalMeta)
    }
}

/**
 * Projects a freshly-emitted rail row against the persisted (overlay-applied) row using
 * [HomeRailProjectionReducer]. The rail row is FIRST_PAINT input; the persisted row
 * carries STALE_RESOLVED (or RESOLVED if a hydration just landed) state. Reducer
 * guarantees first-paint cannot downgrade resolved fields. Replaces the prior
 * `mergeFallback`-with-rail-as-primary inversion that allowed raw rail URLs to overwrite
 * durable artwork refs.
 *
 * [externalMeta] is a [Meta] from an external source (e.g. metadata router response) that
 * can supply RESOLVED fields when available. If neither [persistedFallback] nor
 * [externalMeta] are present the rail item is returned as-is.
 */
internal fun mergePersistedHomeDisplayMetadata(
    currentItem: MetaPreview,
    persistedFallback: MetaPreview?,
    externalMeta: Meta?
): MetaPreview {
    if (persistedFallback == null && externalMeta == null) return currentItem
    val externalAsPreview: MetaPreview? = externalMeta?.let { meta ->
        currentItem.copy(
            name = meta.name ?: currentItem.name,
            description = meta.description,
            genres = meta.genres,
            releaseInfo = meta.releaseInfo,
            runtime = meta.runtime,
            imdbRating = meta.imdbRating,
            ratingSource = meta.ratingSource ?: currentItem.ratingSource,
            poster = meta.poster ?: currentItem.poster,
            background = meta.background ?: currentItem.background,
            logo = meta.logo ?: currentItem.logo,
            posterProviderTag = meta.posterProviderTag ?: currentItem.posterProviderTag,
            artwork = meta.artwork ?: currentItem.artwork
        )
    }
    return projectRailRowAgainstPersisted(
        rawRailItem = currentItem,
        persistedFallback = persistedFallback,
        externalMeta = externalAsPreview
    )
}

/**
 * Core reducer projection: routes [rawRailItem] through [HomeRailProjectionReducer] with
 * [persistedFallback] / [externalMeta] promoted to STALE_RESOLVED rank. The result is
 * applied back onto [rawRailItem] so non-display fields (ids, type, stable ids, etc.)
 * are preserved from the fresh rail row.
 */
private fun projectRailRowAgainstPersisted(
    rawRailItem: MetaPreview,
    persistedFallback: MetaPreview?,
    externalMeta: MetaPreview?
): MetaPreview {
    if (persistedFallback == null && externalMeta == null) return rawRailItem
    val nowMs = System.currentTimeMillis()
    val firstPaintSlots = rawRailItem.toFirstPaintSlots(nowMs)
    val resolvedRow = externalMeta ?: persistedFallback!!
    val resolvedSlots = resolvedRow.toFirstPaintSlots(nowMs).toStaleResolved()
    val merged = HomeRailProjectionReducer.reduce(
        firstPaint = firstPaintSlots,
        overlay = null,
        existing = resolvedSlots,
        profile = null
    )
    return rawRailItem.applyMergedSlotsForRefresh(merged)
}

private fun MetaPreview.applyMergedSlotsForRefresh(
    slots: ResolvedDisplayFieldSlots
): MetaPreview {
    val posterRef = slots.poster.value
    val backdropRef = slots.backdrop.value
    val logoRef = slots.logo.value
    val thumbnailRef = slots.thumbnail.value
    val rating = slots.rating.value
    return copy(
        name = slots.title.value ?: name,
        description = slots.overview.value ?: description,
        genres = slots.genres.value ?: genres,
        releaseInfo = slots.releaseInfo.value ?: releaseInfo,
        runtime = slots.runtime.value ?: runtime,
        imdbRating = rating?.value?.toFloat() ?: imdbRating,
        ratingSource = rating?.source ?: ratingSource,
        poster = posterRef.toLegacyArtworkString() ?: poster,
        background = backdropRef.toLegacyArtworkString() ?: background,
        logo = logoRef.toLegacyArtworkString() ?: logo,
        posterProviderTag = slots.posterProviderTag.value ?: posterProviderTag,
        artwork = ArtworkBundle(
            poster = posterRef.takeIfImageType(com.nexio.tv.core.artwork.ArtworkType.POSTER),
            backdrop = backdropRef.takeIfImageType(com.nexio.tv.core.artwork.ArtworkType.BACKDROP),
            logo = logoRef.takeIfImageType(com.nexio.tv.core.artwork.ArtworkType.LOGO),
            thumbnail = thumbnailRef.takeIfImageType(com.nexio.tv.core.artwork.ArtworkType.THUMBNAIL)
        ).enforceArtworkTypeBoundaries().emptyOrNull()
    )
}

private fun ResolvedDisplayFieldSlots.toStaleResolved(): ResolvedDisplayFieldSlots {
    fun <T> ResolvedSlot<T>.promoted(): ResolvedSlot<T> =
        if (rank == DisplaySourceRank.FIRST_PAINT) copy(rank = DisplaySourceRank.STALE_RESOLVED) else this
    return ResolvedDisplayFieldSlots(
        title = title.promoted(),
        originalTitle = originalTitle.promoted(),
        overview = overview.promoted(),
        genres = genres.promoted(),
        releaseInfo = releaseInfo.promoted(),
        runtime = runtime.promoted(),
        rating = rating.promoted(),
        poster = poster.promoted(),
        backdrop = backdrop.promoted(),
        logo = logo.promoted(),
        thumbnail = thumbnail.promoted(),
        posterProviderTag = posterProviderTag.promoted()
    )
}

internal fun shouldReusePersistedHomeItem(
    itemChanged: Boolean,
    persistedFallback: MetaPreview?
): Boolean {
    return !itemChanged && persistedFallback?.tomatoesRating != null
}
