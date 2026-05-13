package com.nexio.tv.data.remote.supabase

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbKitsuCatalogSyncModelsTest {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    @Test
    fun `TmdbCatalogSyncSettings round-trips with non-null fields`() {
        val original = TmdbCatalogSyncSettings(
            catalogEnabledSet = listOf("tmdb_popular_movies", "tmdb_top_rated_movies"),
            catalogOrder = listOf("tmdb_top_rated_movies", "tmdb_popular_movies"),
        )
        val text = json.encodeToString(TmdbCatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(TmdbCatalogSyncSettings.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun `TmdbCatalogSyncSettings round-trips with null fields`() {
        val original = TmdbCatalogSyncSettings()
        val text = json.encodeToString(TmdbCatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(TmdbCatalogSyncSettings.serializer(), text)
        assertNull(decoded.catalogEnabledSet)
        assertNull(decoded.catalogOrder)
    }

    @Test
    fun `KitsuCatalogSyncSettings round-trips with non-null fields`() {
        val original = KitsuCatalogSyncSettings(
            catalogEnabledSet = listOf("kitsu_trending_anime"),
            catalogOrder = listOf("kitsu_trending_anime", "kitsu_popular_anime"),
        )
        val text = json.encodeToString(KitsuCatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(KitsuCatalogSyncSettings.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun `CatalogSyncSettings carries tmdb and kitsu sections`() {
        val original = CatalogSyncSettings(
            tmdb = TmdbCatalogSyncSettings(catalogOrder = listOf("tmdb_popular_movies")),
            kitsu = KitsuCatalogSyncSettings(catalogOrder = listOf("kitsu_trending_anime")),
        )
        val text = json.encodeToString(CatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(CatalogSyncSettings.serializer(), text)
        assertEquals(listOf("tmdb_popular_movies"), decoded.tmdb?.catalogOrder)
        assertEquals(listOf("kitsu_trending_anime"), decoded.kitsu?.catalogOrder)
    }

    @Test
    fun `HomeCatalogSyncSettings carries displayed rails`() {
        val original = HomeCatalogSyncSettings(
            railsVersion = 1,
            rails = listOf(
                com.nexio.tv.domain.model.HomeCatalogRail(
                    key = "tmdb_trending_movies",
                    family = "tmdb",
                    source = "provider_catalog",
                    title = "Trending Movies",
                    addedAtMs = 1778544000000L
                )
            ),
            heroCatalogKeys = listOf("hero-key"),
            homeCatalogOrderKeys = listOf("legacy-key"),
            disabledHomeCatalogKeys = listOf("disabled-key")
        )

        val text = json.encodeToString(HomeCatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(HomeCatalogSyncSettings.serializer(), text)

        assertEquals(1, decoded.railsVersion)
        assertEquals("tmdb_trending_movies", decoded.rails?.single()?.key)
        assertEquals(listOf("hero-key"), decoded.heroCatalogKeys)
        assertEquals(listOf("legacy-key"), decoded.homeCatalogOrderKeys)
        assertEquals(listOf("disabled-key"), decoded.disabledHomeCatalogKeys)
    }
}
