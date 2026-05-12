package com.nexio.tv.data.local

import com.nexio.tv.domain.model.HomeCatalogRail
import com.nexio.tv.testutil.layoutPreferenceDataStoreForTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutPreferenceDataStoreHomeCatalogRailsTest {
    @Test
    fun `home catalog rails persist as sanitized json`() = runTest {
        val store = layoutPreferenceDataStoreForTest()

        store.setHomeCatalogRails(
            listOf(
                HomeCatalogRail(key = " tmdb_trending_movies ", family = "tmdb", source = "provider_catalog", title = " Trending "),
                HomeCatalogRail(key = "tmdb_trending_movies", family = "tmdb", source = "provider_catalog", title = "Duplicate")
            )
        )

        val rails = store.homeCatalogRails.first()
        assertEquals(1, rails.size)
        assertEquals("tmdb_trending_movies", rails.single().key)
        assertEquals("Trending", rails.single().title)
    }

    @Test
    fun `blank or invalid rails json reads as empty list`() = runTest {
        val store = layoutPreferenceDataStoreForTest()

        assertEquals(emptyList<HomeCatalogRail>(), store.homeCatalogRails.first())
    }
}
