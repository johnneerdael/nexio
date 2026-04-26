package com.nexio.tv.core.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RailKeyFactoryTest {
    @Test
    fun `rail keys are profile scoped and continue watching is outside home catalog namespace`() {
        assertEquals(
            "profile:7:home:catalog:tmdb:popular:movies",
            RailKeyFactory.homeCatalog(profileId = 7, catalogId = "tmdb:popular:movies")
        )
        assertEquals(
            "profile:7:home:catalog:",
            RailKeyFactory.homeCatalogNamespace(profileId = 7)
        )
        assertEquals(
            "profile:7:home:continue_watching",
            RailKeyFactory.continueWatching(profileId = 7)
        )
        assertFalse(
            RailKeyFactory.continueWatching(profileId = 7)
                .startsWith(RailKeyFactory.homeCatalogNamespace(profileId = 7))
        )
    }

    @Test
    fun `library namespaces stay isolated per provider and profile`() {
        assertEquals(
            "profile:3:library:trakt:",
            RailKeyFactory.traktLibraryNamespace(profileId = 3)
        )
        assertEquals(
            "profile:3:library:trakt:watchlist",
            RailKeyFactory.traktLibrary(profileId = 3, listKey = "watchlist")
        )
        assertEquals(
            "profile:3:library:simkl:",
            RailKeyFactory.simklLibraryNamespace(profileId = 3)
        )
        assertEquals(
            "profile:3:library:simkl:watching",
            RailKeyFactory.simklLibrary(profileId = 3, listKey = "watching")
        )
    }
}
