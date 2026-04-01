package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.MDBListListOption
import com.nexio.tv.data.repository.MDBListCustomCatalog
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
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

    @Test
    fun `expected configured addon keys honor remote disable keys even when catalog name differs`() {
        val addons = listOf(
            addonWithCatalog(
                addonId = "cinemeta",
                type = "movie",
                catalogId = "popular",
                catalogName = "Catalog popular"
            )
        )

        val expected = buildExpectedConfiguredAddonOrderKeys(
            addons = addons,
            disabledHomeCatalogKeys = setOf("https://example.com/cinemeta_movie_popular_Popular Movies")
        )

        assertEquals(emptyList<String>(), expected)
    }

    @Test
    fun `publishable configured home keys ignore settled synthetic rails without rows`() {
        val addons = listOf(
            addonWithCatalog("cinemeta", "movie", "popular")
        )
        val traktPrefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(
                TraktCatalogIds.TRENDING_MOVIES,
                TraktCatalogIds.POPULAR_SHOWS
            ),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER,
            selectedPopularListKeys = setOf("popular:custom-list")
        )
        val traktSnapshot = TraktDiscoverySnapshot(updatedAtMs = 123L)
        val mdbPrefs = MDBListCatalogPreferences(
            selectedTopListKeys = setOf("top:top-rated"),
            catalogOrder = listOf("top:top-rated")
        )
        val mdbSnapshot = MDBListDiscoverySnapshot(
            topLists = listOf(listOption("top:top-rated", isPersonal = false)),
            customListCatalogs = listOf(
                customCatalog(key = "top:top-rated", type = ContentType.MOVIE)
            ),
            updatedAtMs = 456L
        )

        val publishable = buildPublishableConfiguredHomeOrderKeys(
            addons = addons,
            disabledHomeCatalogKeys = emptySet(),
            traktPrefs = traktPrefs,
            traktSnapshot = traktSnapshot,
            hasTraktUpNextItems = false,
            mdbPrefs = mdbPrefs,
            mdbSnapshot = mdbSnapshot
        )

        assertEquals(
            listOf(
                "top:top-rated",
                "cinemeta_movie_popular"
            ),
            publishable
        )
        assertTrue(
            isConfiguredHomeSnapshotComplete(
                snapshotOrderedGroupKeys = publishable,
                expectedConfiguredOrderKeys = publishable
            )
        )
    }

    @Test
    fun `publish source readiness depends on source observation not synthetic row emission`() {
        val expectedTraktKeys = listOf(TraktCatalogIds.TRENDING_MOVIES)
        val expectedMdbKeys = listOf("top:top-rated")
        val expectedAddonKeys = listOf("cinemeta_movie_popular")

        assertTrue(
            areConfiguredHomePublishSourcesReady(
                addonExpectedOrderKeys = expectedAddonKeys,
                availableAddonOrderKeys = expectedAddonKeys.toSet(),
                traktExpectedOrderKeys = expectedTraktKeys,
                traktObserved = true,
                mdbExpectedOrderKeys = expectedMdbKeys,
                mdbObserved = true
            )
        )
        assertFalse(
            areConfiguredHomePublishSourcesReady(
                addonExpectedOrderKeys = expectedAddonKeys,
                availableAddonOrderKeys = expectedAddonKeys.toSet(),
                traktExpectedOrderKeys = expectedTraktKeys,
                traktObserved = false,
                mdbExpectedOrderKeys = expectedMdbKeys,
                mdbObserved = true
            )
        )
    }

    @Test
    fun `serialized trakt refresh still checks stale complete discovery rails`() {
        val prefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.TRENDING_SHOWS),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
        )
        val snapshot = TraktDiscoverySnapshot(
            trendingShowItems = listOf(
                MetaPreview(
                    id = "tt1234567",
                    type = ContentType.SERIES,
                    name = "Sample Show",
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = null,
                    imdbRating = null,
                    genres = emptyList()
                )
            ),
            updatedAtMs = 123L
        )

        assertFalse(shouldRefreshTraktDiscoveryForState(prefs, snapshot))
        assertTrue(shouldAttemptSerializedTraktDiscoveryRefresh(prefs))
    }

    @Test
    fun `serialized trakt refresh skips service when only up next is enabled`() {
        val prefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.UP_NEXT),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
        )

        assertFalse(shouldAttemptSerializedTraktDiscoveryRefresh(prefs))
    }

    private fun addonWithCatalog(
        addonId: String,
        type: String,
        catalogId: String,
        catalogName: String = "Catalog $catalogId"
    ): Addon {
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
                    name = catalogName,
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

    private fun customCatalog(key: String, type: ContentType): MDBListCustomCatalog {
        return MDBListCustomCatalog(
            key = key,
            catalogId = "catalog_${key.substringAfter(':')}",
            catalogName = key,
            type = type,
            items = listOf(
                MetaPreview(
                    id = "tt1234567",
                    type = type,
                    name = "Sample",
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = null,
                    imdbRating = null,
                    genres = emptyList()
                )
            )
        )
    }
}
