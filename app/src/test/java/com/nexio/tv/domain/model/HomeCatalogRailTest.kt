package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogRailTest {
    @Test
    fun `sanitize keeps first duplicate and trims fields`() {
        val rails = sanitizeHomeCatalogRails(
            listOf(
                HomeCatalogRail(key = " tmdb_trending_movies ", family = " tmdb ", source = " provider_catalog ", title = " Trending Movies "),
                HomeCatalogRail(key = "tmdb_trending_movies", family = "tmdb", source = "provider_catalog", title = "Duplicate"),
                HomeCatalogRail(key = "   ", family = "tmdb", source = "provider_catalog", title = "Blank")
            )
        )

        assertEquals(1, rails.size)
        assertEquals("tmdb_trending_movies", rails.single().key)
        assertEquals("tmdb", rails.single().family)
        assertEquals("provider_catalog", rails.single().source)
        assertEquals("Trending Movies", rails.single().title)
        assertTrue(rails.single().enabled)
    }

    @Test
    fun `sanitize infers blank family and source while preserving disabled state`() {
        val rails = sanitizeHomeCatalogRails(
            listOf(
                HomeCatalogRail(
                    key = "tmdb_trending_movies",
                    family = "   ",
                    source = "",
                    title = "Trending Movies",
                    enabled = false
                )
            )
        )

        assertEquals(1, rails.size)
        assertEquals("tmdb", rails.single().family)
        assertEquals("provider_catalog", rails.single().source)
        assertFalse(rails.single().enabled)
    }

    @Test
    fun `catalog record family inference supports stock providers and addons`() {
        assertEquals("tmdb", homeCatalogRailFamilyForKey("tmdb_popular_movies"))
        assertEquals("kitsu", homeCatalogRailFamilyForKey("kitsu_trending_anime"))
        assertEquals("trakt", homeCatalogRailFamilyForKey("trakt_up_next"))
        assertEquals("simkl", homeCatalogRailFamilyForKey("simkl_tv_trending_today"))
        assertEquals("mdblist", homeCatalogRailFamilyForKey("mdblist_owner_list"))
        assertEquals("mdblist", homeCatalogRailFamilyForKey("top:owner/list"))
        assertEquals("mdblist", homeCatalogRailFamilyForKey("personal:owner/list"))
        assertEquals("addon", homeCatalogRailFamilyForKey("addon-cinemeta_movie_popular"))
    }

    @Test
    fun `catalog record source inference supports providers lists and addons`() {
        assertEquals("provider_catalog", homeCatalogRailSourceForFamily(homeCatalogRailFamilyForKey("tmdb_popular_movies")))
        assertEquals("provider_catalog", homeCatalogRailSourceForFamily(homeCatalogRailFamilyForKey("kitsu_trending_anime")))
        assertEquals("provider_catalog", homeCatalogRailSourceForFamily(homeCatalogRailFamilyForKey("trakt_up_next")))
        assertEquals("provider_catalog", homeCatalogRailSourceForFamily(homeCatalogRailFamilyForKey("simkl_tv_trending_today")))
        assertEquals("provider_list", homeCatalogRailSourceForFamily(homeCatalogRailFamilyForKey("top:owner/list")))
        assertEquals("addon_catalog", homeCatalogRailSourceForFamily(homeCatalogRailFamilyForKey("addon-cinemeta_movie_popular")))
    }
}
