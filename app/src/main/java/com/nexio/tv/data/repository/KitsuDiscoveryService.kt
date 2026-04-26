package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.kitsu.KitsuDiscoveryIntegrationProvider
import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

typealias RetrofitKitsuDiscoveryClient = KitsuDiscoveryIntegrationProvider

@Singleton
class KitsuDiscoveryService @Inject constructor(
    private val client: KitsuDiscoveryClient
) {
    private val snapshot = MutableStateFlow(KitsuDiscoverySnapshot())

    fun observeSnapshot(): Flow<KitsuDiscoverySnapshot> = snapshot

    suspend fun refreshCatalogs(
        preferences: KitsuCatalogPreferences,
        force: Boolean,
        catalogIds: Set<String>? = null
    ): KitsuDiscoverySnapshot {
        val sanitized = preferences.sanitized()
        val requestedCatalogIds = catalogIds
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
        val previous = snapshot.value
        val previousCurrentRows = previous.currentRowsFor(sanitized)
        if (!force && requestedCatalogIds != null && requestedCatalogIds.all { it in previousCurrentRows }) {
            return previous
        }

        val enabledCatalogs = sanitized.catalogOrder
            .filter { it in sanitized.enabledCatalogs }
            .filter { requestedCatalogIds == null || it in requestedCatalogIds }

        val refreshedRows = enabledCatalogs.associateWith { catalogId ->
            fetchCatalogRow(catalogId, sanitized)
        }.filterValues { it != null }
            .mapValues { it.value!! }

        val rows = if (catalogIds == null) {
            refreshedRows
        } else {
            previousCurrentRows - requestedCatalogIds.orEmpty() + refreshedRows
        }
        val currentPreferenceCatalogIds = sanitized.enabledCatalogIds()
        val catalogIdsWithCurrentPreferences = if (catalogIds == null) {
            currentPreferenceCatalogIds
        } else {
            (previous.catalogIdsWithCurrentPreferences.intersect(currentPreferenceCatalogIds) - requestedCatalogIds.orEmpty()) +
                enabledCatalogs.toSet()
        }

        return KitsuDiscoverySnapshot(
            rowsByCatalog = rows,
            updatedAtMs = System.currentTimeMillis(),
            catalogIdsWithCurrentPreferences = catalogIdsWithCurrentPreferences
        ).also { snapshot.value = it }
    }

    private suspend fun fetchCatalogRow(
        catalogId: String,
        preferences: KitsuCatalogPreferences
    ): CatalogRow? {
        val title = kitsuCatalogTitle(catalogId) ?: return null
        val results = runCatching { client.fetchCatalog(catalogId, preferences) }
            .getOrDefault(emptyList())
        val items = results.take(MAX_ITEMS_PER_SOURCE).mapNotNull(::mapResult)
        if (items.isEmpty()) return null
        return CatalogRow(
            addonId = ADDON_ID,
            addonName = ADDON_NAME,
            addonBaseUrl = ADDON_BASE_URL,
            catalogId = catalogId,
            catalogName = title,
            type = ContentType.SERIES,
            rawType = ContentType.SERIES.toApiString("catalog"),
            items = items,
            hasMore = false,
            supportsSkip = false
        )
    }

    private fun mapResult(result: KitsuAnimeResource): MetaPreview? {
        val attributes = result.attributes ?: return null
        val title = firstNonBlank(
            attributes.canonicalTitle,
            attributes.titles?.values?.firstOrNull { !it.isNullOrBlank() }
        ) ?: return null
        val backdrop = attributes.coverImage.bestUrl()
        val poster = backdrop ?: attributes.posterImage.bestUrl()
        val contentType = if (attributes.subtype.equals("movie", ignoreCase = true)) {
            ContentType.MOVIE
        } else {
            ContentType.SERIES
        }
        val rating = attributes.averageRating
            ?.toDoubleOrNull()
            ?.div(10.0)
            ?.let { ((it * 100.0).roundToInt() / 100f) }
        return MetaPreview(
            id = "kitsu:${result.id.orEmpty()}",
            type = contentType,
            rawType = contentType.toApiString(),
            name = title,
            poster = poster,
            posterShape = if (backdrop != null) PosterShape.LANDSCAPE else PosterShape.POSTER,
            background = backdrop,
            logo = null,
            description = firstNonBlank(attributes.synopsis, attributes.description),
            releaseInfo = attributes.startDate,
            imdbRating = rating,
            genres = emptyList(),
            language = "ja"
        )
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstNotNullOfOrNull { value ->
            value?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private fun com.nexio.tv.data.remote.api.KitsuImage?.bestUrl(): String? {
        return this?.original ?: this?.large ?: this?.medium ?: this?.small ?: this?.tiny
    }

    companion object {
        private const val ADDON_ID = "kitsu"
        private const val ADDON_NAME = "Kitsu"
        private const val ADDON_BASE_URL = "https://kitsu.io/api/edge"
        private const val MAX_ITEMS_PER_SOURCE = 20
    }
}
