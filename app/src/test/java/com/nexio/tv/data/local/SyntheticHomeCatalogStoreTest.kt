package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SyntheticHomeCatalogStoreTest {

    @Test
    fun `read restores persisted synthetic rows for current language epoch`() {
        val prefs = InMemorySharedPreferences()
        var epoch = 7
        val context = mockContext(prefs, "synthetic_home_catalogs")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } answers { epoch }
        val store = SyntheticHomeCatalogStore(context, metadataStore)

        val snapshot = SyntheticHomeCatalogStore.Snapshot(
            traktGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = "trakt_up_next",
                    rows = listOf(sampleRow("trakt", "up_next"))
                )
            ),
            mdbListGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = "top:list",
                    rows = listOf(sampleRow("mdblist", "list_movies"))
                )
            )
        )

        store.write(snapshot)

        assertEquals(snapshot, store.read())

        epoch = 8
        assertNull(store.read())
    }

    @Test
    fun `write persists canonical group keys`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "synthetic_home_catalogs")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 7
        val store = SyntheticHomeCatalogStore(context, metadataStore)

        store.write(
            SyntheticHomeCatalogStore.Snapshot(
                traktGroups = listOf(
                    PersistedSyntheticCatalogGroup(
                        orderKey = "trakt_trending_movies",
                        rows = listOf(sampleRow("trakt", "trending_movies"))
                    )
                )
            )
        )

        val raw = prefs.getString("snapshot", null).orEmpty()
        assertTrue(raw.contains("\"orderKey\":\"trakt_trending_movies\""))
        assertTrue(raw.contains("\"rows\""))
        assertTrue(raw.contains("\"traktGroups\""))
    }

    private fun sampleRow(addonId: String, catalogId: String): CatalogRow {
        return CatalogRow(
            addonId = addonId,
            addonName = addonId,
            addonBaseUrl = "https://example.com/$addonId",
            catalogId = catalogId,
            catalogName = "Catalog $catalogId",
            type = ContentType.MOVIE,
            items = listOf(
                MetaPreview(
                    id = "tt123",
                    type = ContentType.MOVIE,
                    rawType = "movie",
                    name = "Sample",
                    poster = "poster",
                    posterShape = PosterShape.POSTER,
                    background = "background",
                    logo = "logo",
                    description = "description",
                    releaseInfo = "2025",
                    imdbRating = 8.1f,
                    genres = listOf("Drama")
                )
            )
        )
    }

    private fun mockContext(prefs: InMemorySharedPreferences, expectedName: String): Context {
        return mockk {
            every { getSharedPreferences(expectedName, Context.MODE_PRIVATE) } returns prefs
        }
    }
}
