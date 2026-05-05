package com.nexio.tv.data.remote.supabase

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogSyncSettingsNullableTest {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    @Test
    fun `null sub-sections round-trip as null`() {
        val original = CatalogSyncSettings(
            home = null, trakt = null, simkl = null, mdblist = null
        )
        val text = json.encodeToString(CatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(CatalogSyncSettings.serializer(), text)
        assertNull(decoded.home)
        assertNull(decoded.trakt)
        assertNull(decoded.simkl)
        assertNull(decoded.mdblist)
    }

    @Test
    fun `present sub-section with null inner fields round-trips with null inner fields`() {
        val original = CatalogSyncSettings(
            home = HomeCatalogSyncSettings(
                heroCatalogKeys = null,
                homeCatalogOrderKeys = null,
                disabledHomeCatalogKeys = null,
            )
        )
        val text = json.encodeToString(CatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(CatalogSyncSettings.serializer(), text)
        assertEquals(
            HomeCatalogSyncSettings(
                heroCatalogKeys = null,
                homeCatalogOrderKeys = null,
                disabledHomeCatalogKeys = null,
            ),
            decoded.home
        )
    }

    @Test
    fun `present sub-section with empty inner field round-trips as empty`() {
        val original = CatalogSyncSettings(
            home = HomeCatalogSyncSettings(homeCatalogOrderKeys = emptyList())
        )
        val text = json.encodeToString(CatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(CatalogSyncSettings.serializer(), text)
        assertEquals(emptyList<String>(), decoded.home?.homeCatalogOrderKeys)
    }

    @Test
    fun `populated sub-sections round-trip with values intact`() {
        val original = CatalogSyncSettings(
            home = HomeCatalogSyncSettings(
                heroCatalogKeys = listOf("hero-a"),
                homeCatalogOrderKeys = listOf("row-a", "row-b"),
                disabledHomeCatalogKeys = listOf("row-c")
            ),
            trakt = TraktCatalogSyncSettings(
                catalogEnabledSet = listOf("trakt_up_next"),
                catalogOrder = listOf("trakt_up_next"),
                selectedPopularListKeys = listOf("popular-a")
            ),
            simkl = SimklCatalogSyncSettings(
                catalogEnabledSet = listOf("simkl_a"),
                catalogOrder = listOf("simkl_a")
            ),
            mdblist = MDBListCatalogSyncSettings(
                hiddenPersonalListKeys = listOf("hidden-a"),
                selectedTopListKeys = listOf("top-a"),
                catalogOrder = listOf("mdb-top")
            )
        )
        val text = json.encodeToString(CatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(CatalogSyncSettings.serializer(), text)
        assertEquals(original, decoded)
    }
}
