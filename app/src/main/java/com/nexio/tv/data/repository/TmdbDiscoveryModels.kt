package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType

data class TmdbDiscoverySnapshot(
    val rowsByCatalog: Map<String, CatalogRow> = emptyMap(),
    val updatedAtMs: Long = 0L
)

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
    suspend fun searchMovies(query: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult>
    suspend fun searchTv(query: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult>
    suspend fun fetchCatalog(catalogId: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult>
    suspend fun imdbId(tmdbId: Int, contentType: ContentType): String?
}
