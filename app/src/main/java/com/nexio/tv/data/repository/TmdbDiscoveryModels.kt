package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.data.remote.api.TmdbMultiSearchResult
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.RailPreviewCatalogRowRecord
import com.nexio.tv.domain.model.toLegacyRailItemPreviews

data class TmdbDiscoverySnapshot(
    val rowRecordsByCatalog: Map<String, RailPreviewCatalogRowRecord> = emptyMap(),
    val updatedAtMs: Long = 0L,
    val includeAdult: Boolean? = null,
    val hideUnreleasedDigital: Boolean? = null,
    val catalogIdsWithCurrentPreferences: Set<String> = emptySet()
) {
    constructor(
        rowsByCatalog: Map<String, CatalogRow>,
        updatedAtMs: Long = 0L,
        includeAdult: Boolean? = null,
        hideUnreleasedDigital: Boolean? = null,
        catalogIdsWithCurrentPreferences: Set<String> = emptySet(),
        fromLegacyRows: Boolean = true
    ) : this(
        rowRecordsByCatalog = rowsByCatalog.mapValues { (_, row) -> row.toRailPreviewCatalogRowRecord() },
        updatedAtMs = updatedAtMs,
        includeAdult = includeAdult,
        hideUnreleasedDigital = hideUnreleasedDigital,
        catalogIdsWithCurrentPreferences = catalogIdsWithCurrentPreferences
    )

    val rowsByCatalog get() = rowRecordsByCatalog.mapValues { (_, row) -> row.toCatalogRow() }

    fun matchesPreferences(preferences: TmdbCatalogPreferences): Boolean {
        val sanitized = preferences.sanitized()
        return includeAdult == sanitized.includeAdult &&
            hideUnreleasedDigital == sanitized.hideUnreleasedDigital
    }

    fun currentRowsFor(preferences: TmdbCatalogPreferences): Map<String, CatalogRow> {
        if (!matchesPreferences(preferences)) return emptyMap()
        val enabledCatalogIds = preferences.enabledCatalogIds()
        return rowRecordsByCatalog.filterKeys { key ->
            key in catalogIdsWithCurrentPreferences && key in enabledCatalogIds
        }.mapValues { (_, row) -> row.toCatalogRow() }
    }
}

private fun CatalogRow.toRailPreviewCatalogRowRecord(): RailPreviewCatalogRowRecord {
    return RailPreviewCatalogRowRecord(
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = addonBaseUrl,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        rawType = rawType,
        previews = items.toLegacyRailItemPreviews(railId = catalogId)
    )
}

fun tmdbCatalogTitle(catalogId: String): String? {
    return when (catalogId) {
        TmdbCatalogIds.TRENDING_MOVIES -> "TMDB Trending Movies"
        TmdbCatalogIds.TRENDING_SERIES -> "TMDB Trending Series"
        TmdbCatalogIds.LATEST_RELEASES_MOVIES -> "TMDB Latest Releases Movies"
        TmdbCatalogIds.LATEST_RELEASES_SERIES -> "TMDB Latest Releases Series"
        TmdbCatalogIds.POPULAR_MOVIES -> "TMDB Popular Movies"
        TmdbCatalogIds.POPULAR_SERIES -> "TMDB Popular Series"
        TmdbCatalogIds.YEAR_MOVIES -> "TMDB Movies By Year"
        TmdbCatalogIds.YEAR_SERIES -> "TMDB Series By Year"
        TmdbCatalogIds.LANGUAGE_MOVIES -> "TMDB Movies By Language"
        TmdbCatalogIds.LANGUAGE_SERIES -> "TMDB Series By Language"
        else -> null
    }
}

interface TmdbDiscoveryClient {
    suspend fun credential(): MetadataProviderCredential
    suspend fun searchMulti(
        query: String,
        page: Int,
        preferences: TmdbCatalogPreferences
    ): List<TmdbMultiSearchResult>
    suspend fun fetchCatalog(catalogId: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult>
    suspend fun imdbId(tmdbId: Int, contentType: ContentType): String?
}
