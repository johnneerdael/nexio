package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.MDBListListOption
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogStartupReadinessTest {

    @Test
    fun `expected configured home keys include addons trakt and mdblist`() {
        val addons = listOf(
            addonWithCatalog("cinemeta", "movie", "popular")
        )
        val traktPrefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES, TraktCatalogIds.RECOMMENDED_SHOWS),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER,
            selectedPopularListKeys = setOf("popular:custom-list")
        )
        val mdbPrefs = MDBListCatalogPreferences(
            hiddenPersonalListKeys = setOf("personal:hidden"),
            selectedTopListKeys = setOf("top:top-rated"),
            catalogOrder = listOf("personal:watchlist", "top:top-rated")
        )
        val mdbSnapshot = MDBListDiscoverySnapshot(
            personalLists = listOf(
                listOption("personal:watchlist", isPersonal = true),
                listOption("personal:hidden", isPersonal = true)
            )
        )

        val expected = buildExpectedConfiguredHomeOrderKeys(
            addons = addons,
            disabledHomeCatalogKeys = emptySet(),
            traktPrefs = traktPrefs,
            mdbPrefs = mdbPrefs,
            mdbSnapshot = mdbSnapshot
        )

        assertEquals(
            listOf(
                TraktCatalogIds.TRENDING_MOVIES,
                TraktCatalogIds.RECOMMENDED_SHOWS,
                "popular:custom-list",
                "personal:watchlist",
                "top:top-rated",
                "cinemeta_movie_popular"
            ),
            expected
        )
    }

    @Test
    fun `configured home completeness fails when enabled feed key is missing`() {
        val expectedKeys = listOf(
            TraktCatalogIds.TRENDING_MOVIES,
            "top:top-rated",
            "cinemeta_movie_popular"
        )

        assertFalse(
            isConfiguredHomeSnapshotComplete(
                snapshotOrderedGroupKeys = listOf("top:top-rated", "cinemeta_movie_popular"),
                expectedConfiguredOrderKeys = expectedKeys
            )
        )
    }

    @Test
    fun `configured home completeness passes when all enabled keys are present`() {
        val expectedKeys = listOf(
            TraktCatalogIds.TRENDING_MOVIES,
            "top:top-rated",
            "cinemeta_movie_popular"
        )

        assertTrue(
            isConfiguredHomeSnapshotComplete(
                snapshotOrderedGroupKeys = listOf(
                    "top:top-rated",
                    TraktCatalogIds.TRENDING_MOVIES,
                    "cinemeta_movie_popular"
                ),
                expectedConfiguredOrderKeys = expectedKeys
            )
        )
    }

    private fun addonWithCatalog(addonId: String, type: String, catalogId: String): Addon {
        return Addon(
            id = addonId,
            name = addonId,
            displayName = addonId,
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://example.com/$addonId",
            catalogs = listOf(
                CatalogDescriptor(
                    type = if (type == "series") ContentType.SERIES else ContentType.MOVIE,
                    id = catalogId,
                    name = "Catalog $catalogId",
                    extra = emptyList()
                )
            ),
            types = emptyList(),
            resources = emptyList<AddonResource>()
        )
    }

    private fun listOption(key: String, isPersonal: Boolean): MDBListListOption {
        return MDBListListOption(
            key = key,
            owner = "owner",
            listId = key.substringAfter(':'),
            title = key,
            itemCount = 10,
            isPersonal = isPersonal
        )
    }
}
