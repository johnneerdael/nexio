package com.nexio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexio.tv.domain.model.HomeCatalogRail
import com.nexio.tv.testutil.layoutPreferenceDataStoreForTest
import com.nexio.tv.testutil.profileDataStoreFactoryForTest
import com.nexio.tv.testutil.testProfileManager
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
        val factory = profileDataStoreFactoryForTest()
        val store = LayoutPreferenceDataStore(
            factory = factory,
            profileManager = testProfileManager()
        )

        assertEquals(emptyList<HomeCatalogRail>(), store.homeCatalogRails.first())

        factory.get(1, "layout_settings").edit { prefs ->
            prefs[stringPreferencesKey("home_catalog_rails_json")] = "{not valid json"
        }

        assertEquals(emptyList<HomeCatalogRail>(), store.homeCatalogRails.first())
    }
}
