package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.HomeCatalogSnapshotStore
import com.nexio.tv.data.local.PersistedSyntheticCatalogGroup
import com.nexio.tv.data.local.SimklCatalogIds
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.SyntheticHomeCatalogStore
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.MDBListListOption
import com.nexio.tv.data.repository.MDBListCustomCatalog
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.data.repository.TraktPopularListOption
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.data.repository.TmdbDiscoverySnapshot
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogStartupReadinessTest {

    @Test
    fun `trakt catalog preferences contribute no home keys when active profile is unauthenticated`() {
        val prefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES, TraktCatalogIds.RECOMMENDED_SHOWS),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER,
            selectedPopularListKeys = setOf("popular:custom-list")
        )

        assertEquals(
            emptyList<String>(),
            buildExpectedConfiguredTraktOrderKeys(prefs.onlyWhenAuthenticated(authenticated = false))
        )
    }

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
            simklPrefs = SimklCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
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
    fun `expected configured home keys include simkl rails in configured order`() {
        val expected = buildExpectedConfiguredHomeOrderKeys(
            addons = emptyList(),
            disabledHomeCatalogKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
            simklPrefs = SimklCatalogPreferences(
                enabledCatalogs = setOf(SimklCatalogIds.ANIME_TRENDING_MONTH, SimklCatalogIds.TV_TRENDING_TODAY),
                catalogOrder = listOf(SimklCatalogIds.ANIME_TRENDING_MONTH, SimklCatalogIds.TV_TRENDING_TODAY)
            ),
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        assertEquals(
            listOf(SimklCatalogIds.ANIME_TRENDING_MONTH, SimklCatalogIds.TV_TRENDING_TODAY),
            expected
        )
    }

    @Test
    fun `expected configured home keys exclude disabled synthetic rails`() {
        val expected = buildExpectedConfiguredHomeOrderKeys(
            addons = emptyList(),
            disabledHomeCatalogKeys = setOf(
                TraktCatalogIds.TRENDING_MOVIES,
                SimklCatalogIds.TV_TRENDING_TODAY,
                "top:top-rated"
            ),
            traktPrefs = TraktCatalogPreferences(
                enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES),
                catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
            ),
            simklPrefs = SimklCatalogPreferences(
                enabledCatalogs = setOf(SimklCatalogIds.TV_TRENDING_TODAY),
                catalogOrder = listOf(SimklCatalogIds.TV_TRENDING_TODAY)
            ),
            mdbPrefs = MDBListCatalogPreferences(
                selectedTopListKeys = setOf("top:top-rated"),
                catalogOrder = listOf("top:top-rated")
            ),
            mdbSnapshot = MDBListDiscoverySnapshot(
                topLists = listOf(listOption("top:top-rated", isPersonal = false))
            )
        )

        assertEquals(emptyList<String>(), expected)
    }

    @Test
    fun `configured home descriptors use meaningful titles before hydration`() {
        val descriptors = buildConfiguredHomeCatalogDescriptors(
            addons = listOf(addonWithCatalog("cinemeta", "movie", "popular", catalogName = "Popular Movies")),
            disabledHomeCatalogKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(
                enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES),
                catalogOrder = TraktCatalogIds.BUILT_IN_ORDER,
                selectedPopularListKeys = setOf("popular:trakt-list")
            ),
            traktSnapshot = TraktDiscoverySnapshot(
                popularLists = listOf(
                    traktPopularListOption("popular:trakt-list", "Trakt Staff Picks")
                )
            ),
            simklPrefs = SimklCatalogPreferences(
                enabledCatalogs = setOf(SimklCatalogIds.TV_TRENDING_TODAY),
                catalogOrder = listOf(SimklCatalogIds.TV_TRENDING_TODAY)
            ),
            mdbPrefs = MDBListCatalogPreferences(
                selectedTopListKeys = setOf("top:top-rated"),
                catalogOrder = listOf("top:top-rated")
            ),
            mdbSnapshot = MDBListDiscoverySnapshot(
                topLists = listOf(listOption("top:top-rated", isPersonal = false, title = "MDB Top Rated"))
            )
        )

        assertEquals(
            listOf(
                "Trakt Trending Movies",
                "Trakt Staff Picks",
                "SIMKL Trending TV (Today)",
                "MDB Top Rated",
                "Popular Movies"
            ),
            descriptors.map { it.catalogName }
        )
    }

    @Test
    fun `configured home descriptors use cached mdblist custom catalog title when list option is absent`() {
        val descriptors = buildConfiguredHomeCatalogDescriptors(
            addons = emptyList(),
            disabledHomeCatalogKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(),
            traktSnapshot = TraktDiscoverySnapshot(),
            simklPrefs = SimklCatalogPreferences(),
            mdbPrefs = MDBListCatalogPreferences(
                selectedTopListKeys = setOf("top:jordipc/oscars-2026-the-98th-academy-awards"),
                catalogOrder = listOf("top:jordipc/oscars-2026-the-98th-academy-awards")
            ),
            mdbSnapshot = MDBListDiscoverySnapshot(
                customListCatalogs = listOf(
                    customCatalog(
                        key = "top:jordipc/oscars-2026-the-98th-academy-awards",
                        type = ContentType.MOVIE,
                        title = "Oscars 2026 | The 98th Academy Awards (Movies)"
                    )
                )
            )
        )

        assertEquals(
            listOf("Oscars 2026 | The 98th Academy Awards"),
            descriptors.map { it.catalogName }
        )
    }

    @Test
    fun `loading only rows are excluded from persistable home snapshots`() {
        val hydrated = CatalogRow(
            addonId = "cinemeta",
            addonName = "Cinemeta",
            addonBaseUrl = "https://example.com/cinemeta",
            catalogId = "popular",
            catalogName = "Popular Movies",
            type = ContentType.MOVIE,
            items = listOf(samplePreview("tt1234567", ContentType.MOVIE, "Hydrated Movie"))
        )
        val loading = CatalogRow(
            addonId = "simkl",
            addonName = "SIMKL",
            addonBaseUrl = "https://data.simkl.in",
            catalogId = SimklCatalogIds.TV_TRENDING_TODAY,
            catalogName = "SIMKL Trending TV (Today)",
            type = ContentType.SERIES,
            items = emptyList(),
            isLoading = true
        )

        assertEquals(listOf(hydrated), persistableHomeCatalogRows(listOf(hydrated, loading)))
        assertEquals(emptyList<CatalogRow>(), persistableHomeCatalogRows(listOf(loading)))
    }

    @Test
    fun `expected configured home keys preserve combined trakt simkl addon ordering`() {
        val addons = listOf(
            addonWithCatalog("cinemeta", "movie", "popular")
        )
        val expected = buildExpectedConfiguredHomeOrderKeys(
            addons = addons,
            disabledHomeCatalogKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(
                enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES),
                catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
            ),
            simklPrefs = SimklCatalogPreferences(
                enabledCatalogs = setOf(SimklCatalogIds.MOVIE_TRENDING_TODAY, SimklCatalogIds.TV_TRENDING_TODAY),
                catalogOrder = listOf(SimklCatalogIds.TV_TRENDING_TODAY, SimklCatalogIds.MOVIE_TRENDING_TODAY)
            ),
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        assertEquals(
            listOf(
                TraktCatalogIds.TRENDING_MOVIES,
                SimklCatalogIds.TV_TRENDING_TODAY,
                SimklCatalogIds.MOVIE_TRENDING_TODAY,
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
            availableAddonOrderKeys = emptySet(),
            traktPrefs = traktPrefs,
            traktSnapshot = traktSnapshot,
            hasTraktUpNextItems = false,
            simklPrefs = SimklCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
            simklSnapshot = SimklDiscoverySnapshot(),
            mdbPrefs = mdbPrefs,
            mdbSnapshot = mdbSnapshot
        )

        assertEquals(
            listOf(
                "top:top-rated"
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
                simklExpectedOrderKeys = emptyList(),
                simklObserved = true,
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
                simklExpectedOrderKeys = emptyList(),
                simklObserved = true,
                mdbExpectedOrderKeys = expectedMdbKeys,
                mdbObserved = true
            )
        )
    }

    @Test
    fun `publish source readiness blocks until simkl source is observed when simkl rails are configured`() {
        val expectedSimklKeys = listOf(SimklCatalogIds.TV_TRENDING_TODAY, SimklCatalogIds.ANIME_TRENDING_MONTH)

        assertTrue(
            areConfiguredHomePublishSourcesReady(
                addonExpectedOrderKeys = emptyList(),
                availableAddonOrderKeys = emptySet(),
                traktExpectedOrderKeys = emptyList(),
                traktObserved = true,
                simklExpectedOrderKeys = expectedSimklKeys,
                simklObserved = true,
                mdbExpectedOrderKeys = emptyList(),
                mdbObserved = true
            )
        )
        assertFalse(
            areConfiguredHomePublishSourcesReady(
                addonExpectedOrderKeys = emptyList(),
                availableAddonOrderKeys = emptySet(),
                traktExpectedOrderKeys = emptyList(),
                traktObserved = true,
                simklExpectedOrderKeys = expectedSimklKeys,
                simklObserved = false,
                mdbExpectedOrderKeys = emptyList(),
                mdbObserved = true
            )
        )
    }

    @Test
    fun `source cache readiness blocks when simkl configured rails have no cached content`() {
        val simklPrefs = SimklCatalogPreferences(
            enabledCatalogs = setOf(SimklCatalogIds.TV_TRENDING_TODAY, SimklCatalogIds.ANIME_TRENDING_MONTH),
            catalogOrder = listOf(SimklCatalogIds.TV_TRENDING_TODAY, SimklCatalogIds.ANIME_TRENDING_MONTH)
        )

        assertFalse(
            areConfiguredHomeSourceCachesReady(
                addonExpectedOrderKeys = emptyList(),
                availableAddonOrderKeys = emptySet(),
                traktExpectedOrderKeys = emptyList(),
                traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
                traktSnapshot = TraktDiscoverySnapshot(),
                simklExpectedOrderKeys = listOf(SimklCatalogIds.TV_TRENDING_TODAY, SimklCatalogIds.ANIME_TRENDING_MONTH),
                simklPrefs = simklPrefs,
                simklSnapshot = SimklDiscoverySnapshot(updatedAtMs = 123L),
                mdbExpectedOrderKeys = emptyList(),
                mdbPrefs = MDBListCatalogPreferences(),
                mdbSnapshot = MDBListDiscoverySnapshot()
            )
        )
    }

    @Test
    fun `source cache readiness passes when simkl configured rails have cached content`() {
        val simklPrefs = SimklCatalogPreferences(
            enabledCatalogs = setOf(SimklCatalogIds.TV_TRENDING_TODAY, SimklCatalogIds.ANIME_TRENDING_MONTH),
            catalogOrder = listOf(SimklCatalogIds.TV_TRENDING_TODAY, SimklCatalogIds.ANIME_TRENDING_MONTH)
        )
        val simklSnapshot = SimklDiscoverySnapshot(
            itemsByCatalog = mapOf(
                SimklCatalogIds.TV_TRENDING_TODAY to listOf(samplePreview("tt2000001", ContentType.SERIES, "Trending Show")),
                SimklCatalogIds.ANIME_TRENDING_MONTH to listOf(samplePreview("tt2000002", ContentType.SERIES, "Best Anime"))
            ),
            updatedAtMs = 123L
        )

        assertTrue(
            areConfiguredHomeSourceCachesReady(
                addonExpectedOrderKeys = emptyList(),
                availableAddonOrderKeys = emptySet(),
                traktExpectedOrderKeys = emptyList(),
                traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
                traktSnapshot = TraktDiscoverySnapshot(),
                simklExpectedOrderKeys = listOf(SimklCatalogIds.TV_TRENDING_TODAY, SimklCatalogIds.ANIME_TRENDING_MONTH),
                simklPrefs = simklPrefs,
                simklSnapshot = simklSnapshot,
                mdbExpectedOrderKeys = emptyList(),
                mdbPrefs = MDBListCatalogPreferences(),
                mdbSnapshot = MDBListDiscoverySnapshot()
            )
        )
    }

    @Test
    fun `source cache readiness requires both trakt and simkl caches when both are configured`() {
        val traktPrefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
        )
        val traktSnapshot = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(samplePreview("tt3000001", ContentType.MOVIE, "Trending Movie")),
            updatedAtMs = 123L
        )
        val simklPrefs = SimklCatalogPreferences(
            enabledCatalogs = setOf(SimklCatalogIds.TV_TRENDING_TODAY),
            catalogOrder = listOf(SimklCatalogIds.TV_TRENDING_TODAY)
        )
        val simklSnapshot = SimklDiscoverySnapshot(
            updatedAtMs = 123L
        )

        assertFalse(
            areConfiguredHomeSourceCachesReady(
                addonExpectedOrderKeys = emptyList(),
                availableAddonOrderKeys = emptySet(),
                traktExpectedOrderKeys = listOf(TraktCatalogIds.TRENDING_MOVIES),
                traktPrefs = traktPrefs,
                traktSnapshot = traktSnapshot,
                simklExpectedOrderKeys = listOf(SimklCatalogIds.TV_TRENDING_TODAY),
                simklPrefs = simklPrefs,
                simklSnapshot = simklSnapshot,
                mdbExpectedOrderKeys = emptyList(),
                mdbPrefs = MDBListCatalogPreferences(),
                mdbSnapshot = MDBListDiscoverySnapshot()
            )
        )
    }

    @Test
    fun `configured home refresh bookkeeping includes tmdb discovery refresh`() {
        assertTrue(
            isConfiguredHomeRefreshInProgress(
                catalogsLoadInProgress = false,
                traktDiscoveryRefreshInProgress = false,
                simklDiscoveryRefreshInProgress = false,
                mdbListDiscoveryRefreshInProgress = false,
                tmdbDiscoveryRefreshInProgress = true
            )
        )
        assertFalse(
            isConfiguredHomeRefreshInProgress(
                catalogsLoadInProgress = false,
                traktDiscoveryRefreshInProgress = false,
                simklDiscoveryRefreshInProgress = false,
                mdbListDiscoveryRefreshInProgress = false,
                tmdbDiscoveryRefreshInProgress = false
            )
        )
    }

    @Test
    fun `publishable configured home keys include simkl rails only when snapshot has content`() {
        val prefs = SimklCatalogPreferences(
            enabledCatalogs = setOf(SimklCatalogIds.TV_TRENDING_TODAY, SimklCatalogIds.ANIME_TRENDING_MONTH),
            catalogOrder = listOf(SimklCatalogIds.ANIME_TRENDING_MONTH, SimklCatalogIds.TV_TRENDING_TODAY)
        )
        val snapshot = SimklDiscoverySnapshot(
            itemsByCatalog = mapOf(
                SimklCatalogIds.TV_TRENDING_TODAY to listOf(
                    MetaPreview(
                        id = "tt1000001",
                        type = ContentType.SERIES,
                        name = "Trending Show",
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
            ),
            updatedAtMs = 123L
        )

        val publishable = buildPublishableConfiguredHomeOrderKeys(
            addons = emptyList(),
            disabledHomeCatalogKeys = emptySet(),
            availableAddonOrderKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
            traktSnapshot = TraktDiscoverySnapshot(),
            hasTraktUpNextItems = false,
            simklPrefs = prefs,
            simklSnapshot = snapshot,
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        assertEquals(listOf(SimklCatalogIds.TV_TRENDING_TODAY), publishable)
    }

    @Test
    fun `publishable configured home keys include simkl rails even while trakt discovery is still pending`() {
        val traktPrefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
        )
        val traktSnapshot = TraktDiscoverySnapshot()
        val simklPrefs = SimklCatalogPreferences(
            enabledCatalogs = setOf(SimklCatalogIds.TV_TRENDING_TODAY),
            catalogOrder = listOf(SimklCatalogIds.TV_TRENDING_TODAY)
        )
        val simklSnapshot = SimklDiscoverySnapshot(
            itemsByCatalog = mapOf(
                SimklCatalogIds.TV_TRENDING_TODAY to listOf(
                    samplePreview("tt1000001", ContentType.SERIES, "Trending Show")
                )
            ),
            updatedAtMs = 123L
        )

        assertTrue(shouldRefreshTraktDiscoveryForState(traktPrefs, traktSnapshot))

        val publishable = buildPublishableConfiguredHomeOrderKeys(
            addons = emptyList(),
            disabledHomeCatalogKeys = emptySet(),
            availableAddonOrderKeys = emptySet(),
            traktPrefs = traktPrefs,
            traktSnapshot = traktSnapshot,
            hasTraktUpNextItems = false,
            simklPrefs = simklPrefs,
            simklSnapshot = simklSnapshot,
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        assertEquals(listOf(SimklCatalogIds.TV_TRENDING_TODAY), publishable)
    }

    @Test
    fun `publishable configured home keys include addon rails even while trakt discovery is still pending`() {
        val addons = listOf(
            addonWithCatalog("cinemeta", "movie", "popular")
        )
        val traktPrefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
        )
        val traktSnapshot = TraktDiscoverySnapshot()
        val simklPrefs = SimklCatalogPreferences(
            enabledCatalogs = setOf(SimklCatalogIds.TV_TRENDING_TODAY),
            catalogOrder = listOf(SimklCatalogIds.TV_TRENDING_TODAY)
        )
        val simklSnapshot = SimklDiscoverySnapshot(
            itemsByCatalog = mapOf(
                SimklCatalogIds.TV_TRENDING_TODAY to listOf(
                    samplePreview("tt1000001", ContentType.SERIES, "Trending Show")
                )
            ),
            updatedAtMs = 123L
        )

        assertTrue(shouldRefreshTraktDiscoveryForState(traktPrefs, traktSnapshot))

        val publishable = buildPublishableConfiguredHomeOrderKeys(
            addons = addons,
            disabledHomeCatalogKeys = emptySet(),
            availableAddonOrderKeys = setOf("cinemeta_movie_popular"),
            traktPrefs = traktPrefs,
            traktSnapshot = traktSnapshot,
            hasTraktUpNextItems = false,
            simklPrefs = simklPrefs,
            simklSnapshot = simklSnapshot,
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        assertEquals(
            listOf(
                SimklCatalogIds.TV_TRENDING_TODAY,
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
    fun `pending addon rows do not block ready simkl rows on cold startup`() {
        val addons = listOf(
            addonWithCatalog("cinemeta", "movie", "popular")
        )
        val traktPrefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
        )
        val traktSnapshot = TraktDiscoverySnapshot()
        val simklPrefs = SimklCatalogPreferences(
            enabledCatalogs = setOf(SimklCatalogIds.TV_TRENDING_TODAY),
            catalogOrder = listOf(SimklCatalogIds.TV_TRENDING_TODAY)
        )
        val simklSnapshot = SimklDiscoverySnapshot(
            itemsByCatalog = mapOf(
                SimklCatalogIds.TV_TRENDING_TODAY to listOf(
                    samplePreview("tt1000001", ContentType.SERIES, "Trending Show")
                )
            ),
            updatedAtMs = 123L
        )

        assertTrue(shouldRefreshTraktDiscoveryForState(traktPrefs, traktSnapshot))

        val publishable = buildPublishableConfiguredHomeOrderKeys(
            addons = addons,
            disabledHomeCatalogKeys = emptySet(),
            availableAddonOrderKeys = emptySet(),
            traktPrefs = traktPrefs,
            traktSnapshot = traktSnapshot,
            hasTraktUpNextItems = false,
            simklPrefs = simklPrefs,
            simklSnapshot = simklSnapshot,
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        assertEquals(listOf(SimklCatalogIds.TV_TRENDING_TODAY), publishable)
        assertTrue(
            isConfiguredHomeSnapshotComplete(
                snapshotOrderedGroupKeys = publishable,
                expectedConfiguredOrderKeys = publishable
            )
        )
    }

    @Test
    fun `publishable configured home keys preserve simkl configured order across multiple available rails`() {
        val prefs = SimklCatalogPreferences(
            enabledCatalogs = setOf(
                SimklCatalogIds.ANIME_TRENDING_MONTH,
                SimklCatalogIds.TV_TRENDING_TODAY,
                SimklCatalogIds.MOVIE_TRENDING_TODAY
            ),
            catalogOrder = listOf(
                SimklCatalogIds.MOVIE_TRENDING_TODAY,
                SimklCatalogIds.ANIME_TRENDING_MONTH,
                SimklCatalogIds.TV_TRENDING_TODAY
            )
        )
        val snapshot = SimklDiscoverySnapshot(
            itemsByCatalog = mapOf(
                SimklCatalogIds.TV_TRENDING_TODAY to listOf(samplePreview("tt1000001", ContentType.SERIES, "Trending Show")),
                SimklCatalogIds.ANIME_TRENDING_MONTH to listOf(samplePreview("tt1000002", ContentType.SERIES, "Best Anime")),
                SimklCatalogIds.MOVIE_TRENDING_TODAY to listOf(samplePreview("tt1000003", ContentType.MOVIE, "Trending Movie"))
            ),
            updatedAtMs = 123L
        )

        val publishable = buildPublishableConfiguredHomeOrderKeys(
            addons = emptyList(),
            disabledHomeCatalogKeys = emptySet(),
            availableAddonOrderKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
            traktSnapshot = TraktDiscoverySnapshot(),
            hasTraktUpNextItems = false,
            simklPrefs = prefs,
            simklSnapshot = snapshot,
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        assertEquals(
            listOf(
                SimklCatalogIds.MOVIE_TRENDING_TODAY,
                SimklCatalogIds.ANIME_TRENDING_MONTH,
                SimklCatalogIds.TV_TRENDING_TODAY
            ),
            publishable
        )
    }

    @Test
    fun `publishable configured home keys preserve combined trakt simkl addon order`() {
        val addons = listOf(
            addonWithCatalog("cinemeta", "movie", "popular")
        )
        val traktPrefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
        )
        val traktSnapshot = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(samplePreview("tt4000001", ContentType.MOVIE, "Trakt Movie")),
            updatedAtMs = 123L
        )
        val simklPrefs = SimklCatalogPreferences(
            enabledCatalogs = setOf(SimklCatalogIds.TV_TRENDING_TODAY, SimklCatalogIds.MOVIE_TRENDING_TODAY),
            catalogOrder = listOf(SimklCatalogIds.TV_TRENDING_TODAY, SimklCatalogIds.MOVIE_TRENDING_TODAY)
        )
        val simklSnapshot = SimklDiscoverySnapshot(
            itemsByCatalog = mapOf(
                SimklCatalogIds.TV_TRENDING_TODAY to listOf(samplePreview("tt4000002", ContentType.SERIES, "SIMKL Show")),
                SimklCatalogIds.MOVIE_TRENDING_TODAY to listOf(samplePreview("tt4000003", ContentType.MOVIE, "SIMKL Movie"))
            ),
            updatedAtMs = 123L
        )

        val publishable = buildPublishableConfiguredHomeOrderKeys(
            addons = addons,
            disabledHomeCatalogKeys = emptySet(),
            availableAddonOrderKeys = setOf("cinemeta_movie_popular"),
            traktPrefs = traktPrefs,
            traktSnapshot = traktSnapshot,
            hasTraktUpNextItems = false,
            simklPrefs = simklPrefs,
            simklSnapshot = simklSnapshot,
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        assertEquals(
            listOf(
                TraktCatalogIds.TRENDING_MOVIES,
                SimklCatalogIds.TV_TRENDING_TODAY,
                SimklCatalogIds.MOVIE_TRENDING_TODAY,
                "cinemeta_movie_popular"
            ),
            publishable
        )
    }

    @Test
    fun `configured home completeness fails when simkl expected key is missing`() {
        val expectedKeys = listOf(
            SimklCatalogIds.TV_TRENDING_TODAY,
            "cinemeta_movie_popular"
        )

        assertFalse(
            isConfiguredHomeSnapshotComplete(
                snapshotOrderedGroupKeys = listOf("cinemeta_movie_popular"),
                expectedConfiguredOrderKeys = expectedKeys
            )
        )
    }

    @Test
    fun `serialized simkl refresh treats empty built in snapshot as not ready even after a prior timestamp`() {
        val prefs = SimklCatalogPreferences(
            enabledCatalogs = setOf(SimklCatalogIds.TV_TRENDING_WEEK, SimklCatalogIds.ANIME_TRENDING_MONTH),
            catalogOrder = listOf(SimklCatalogIds.TV_TRENDING_WEEK, SimklCatalogIds.ANIME_TRENDING_MONTH)
        )
        val snapshot = SimklDiscoverySnapshot(
            updatedAtMs = 123L
        )

        assertTrue(
            "Configured SIMKL built-in rails should still be considered not ready when the cached snapshot is empty",
            shouldRefreshSimklDiscoveryForState(prefs, snapshot)
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
    fun `serialized trakt refresh treats empty built in snapshot as not ready even after a prior timestamp`() {
        val prefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.RECOMMENDED_MOVIES, TraktCatalogIds.RECOMMENDED_SHOWS),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
        )
        val snapshot = TraktDiscoverySnapshot(
            // Reproduces the bad state seen on device: discovery has a timestamp, but none of the
            // configured built-in rails have content yet, so modern home should keep refreshing
            // rather than treating Trakt as ready and dropping the rails.
            updatedAtMs = 123L
        )

        assertTrue(
            "Configured Trakt built-in rails should still be considered not ready when the cached snapshot is empty",
            shouldRefreshTraktDiscoveryForState(prefs, snapshot)
        )
    }

    @Test
    fun `tmdb refresh treats populated rows without preference provenance as stale`() {
        val prefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES),
            includeAdult = false,
            hideUnreleasedDigital = false
        )
        val snapshot = TmdbDiscoverySnapshot(
            rowsByCatalog = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to tmdbRow(
                    catalogId = TmdbCatalogIds.TRENDING_MOVIES,
                    items = listOf(samplePreview("tmdb:1", ContentType.MOVIE, "Trending Movie"))
                )
            ),
            updatedAtMs = 123L
        )

        assertTrue(shouldRefreshTmdbDiscoveryForState(prefs, snapshot))
    }

    @Test
    fun `tmdb refresh invalidates populated rows when adult preference changes`() {
        val prefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES),
            includeAdult = true,
            hideUnreleasedDigital = false
        )
        val snapshot = TmdbDiscoverySnapshot(
            rowsByCatalog = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to tmdbRow(
                    catalogId = TmdbCatalogIds.TRENDING_MOVIES,
                    items = listOf(samplePreview("tmdb:1", ContentType.MOVIE, "Trending Movie"))
                )
            ),
            updatedAtMs = 123L,
            includeAdult = false,
            hideUnreleasedDigital = false
        )

        assertTrue(shouldRefreshTmdbDiscoveryForState(prefs, snapshot))
    }

    @Test
    fun `tmdb refresh invalidates populated rows when digital release preference changes`() {
        val prefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.LATEST_RELEASES_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.LATEST_RELEASES_MOVIES),
            includeAdult = false,
            hideUnreleasedDigital = true
        )
        val snapshot = TmdbDiscoverySnapshot(
            rowsByCatalog = mapOf(
                TmdbCatalogIds.LATEST_RELEASES_MOVIES to tmdbRow(
                    catalogId = TmdbCatalogIds.LATEST_RELEASES_MOVIES,
                    items = listOf(samplePreview("tmdb:2", ContentType.MOVIE, "Latest Movie"))
                )
            ),
            updatedAtMs = 123L,
            includeAdult = false,
            hideUnreleasedDigital = false
        )

        assertTrue(shouldRefreshTmdbDiscoveryForState(prefs, snapshot))
    }

    @Test
    fun `publishable tmdb keys require current preference provenance and current catalog id`() {
        val prefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            includeAdult = false,
            hideUnreleasedDigital = true
        )
        val snapshot = TmdbDiscoverySnapshot(
            rowsByCatalog = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to tmdbRow(
                    catalogId = TmdbCatalogIds.TRENDING_MOVIES,
                    items = listOf(samplePreview("tmdb:1", ContentType.MOVIE, "Trending Movie"))
                ),
                TmdbCatalogIds.POPULAR_MOVIES to tmdbRow(
                    catalogId = TmdbCatalogIds.POPULAR_MOVIES,
                    items = listOf(samplePreview("tmdb:2", ContentType.MOVIE, "Popular Movie"))
                )
            ),
            updatedAtMs = 123L,
            includeAdult = false,
            hideUnreleasedDigital = true,
            catalogIdsWithCurrentPreferences = setOf(TmdbCatalogIds.TRENDING_MOVIES)
        )

        assertEquals(
            listOf(TmdbCatalogIds.TRENDING_MOVIES),
            buildPublishableConfiguredTmdbOrderKeys(prefs, snapshot)
        )
        assertEquals(
            emptyList<String>(),
            buildPublishableConfiguredTmdbOrderKeys(prefs.copy(includeAdult = true), snapshot)
        )
    }

    @Test
    fun `restored merged home snapshot removes tmdb rows without current source provenance`() {
        val tmdbItem = samplePreview("tmdb:1", ContentType.MOVIE, "Stale TMDB Movie")
        val addonItem = samplePreview("tt0000001", ContentType.MOVIE, "Addon Movie")
        val restored = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(
                tmdbRow(TmdbCatalogIds.TRENDING_MOVIES, listOf(tmdbItem)),
                addonRow("cinemeta", "popular", listOf(addonItem))
            ),
            fullCatalogRows = listOf(
                tmdbRow(TmdbCatalogIds.TRENDING_MOVIES, listOf(tmdbItem)),
                addonRow("cinemeta", "popular", listOf(addonItem))
            ),
            heroItems = listOf(tmdbItem, addonItem),
            orderedGroupKeys = listOf(TmdbCatalogIds.TRENDING_MOVIES, "cinemeta_movie_popular")
        )
        val currentPreferences = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES),
            includeAdult = true,
            hideUnreleasedDigital = false
        )
        val mismatchedSnapshot = TmdbDiscoverySnapshot(
            rowsByCatalog = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to tmdbRow(
                    catalogId = TmdbCatalogIds.TRENDING_MOVIES,
                    items = listOf(samplePreview("tmdb:2", ContentType.MOVIE, "Current TMDB Movie"))
                )
            ),
            updatedAtMs = 123L,
            includeAdult = false,
            hideUnreleasedDigital = false,
            catalogIdsWithCurrentPreferences = setOf(TmdbCatalogIds.TRENDING_MOVIES)
        )

        val filtered = filterRestoredHomeSnapshotTmdbRows(
            snapshot = restored,
            tmdbPrefs = currentPreferences,
            tmdbSnapshot = mismatchedSnapshot
        )

        assertEquals(listOf("cinemeta"), filtered.catalogRows.map { it.addonId })
        assertEquals(listOf("cinemeta"), filtered.fullCatalogRows.map { it.addonId })
        assertEquals(listOf("tt0000001"), filtered.heroItems.map { it.id })
        assertEquals(listOf("cinemeta_movie_popular"), filtered.orderedGroupKeys)
    }

    @Test
    fun `persisted tmdb rows without preference provenance are not current rows`() {
        val prefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES),
            includeAdult = false,
            hideUnreleasedDigital = true
        )
        val persistedSnapshot = SyntheticHomeCatalogStore.Snapshot(
            tmdbGroups = listOf(tmdbGroup(TmdbCatalogIds.TRENDING_MOVIES))
        )

        assertTrue(persistedSnapshot.tmdbGroupsMatchingPreferences(prefs).isEmpty())
    }

    @Test
    fun `current preference empty tmdb refresh clears old persisted tmdb rows`() {
        val prefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES),
            includeAdult = false,
            hideUnreleasedDigital = true
        )
        val existingSnapshot = SyntheticHomeCatalogStore.Snapshot(
            tmdbGroups = listOf(tmdbGroup(TmdbCatalogIds.TRENDING_MOVIES)),
            tmdbIncludeAdult = false,
            tmdbHideUnreleasedDigital = true
        )
        val currentEmptySnapshot = TmdbDiscoverySnapshot(
            rowsByCatalog = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to tmdbRow(
                    catalogId = TmdbCatalogIds.TRENDING_MOVIES,
                    items = emptyList()
                )
            ),
            updatedAtMs = 123L,
            includeAdult = false,
            hideUnreleasedDigital = true,
            catalogIdsWithCurrentPreferences = setOf(TmdbCatalogIds.TRENDING_MOVIES)
        )

        val effectiveGroups = resolveEffectiveTmdbSyntheticGroups(
            renewedTmdbGroups = emptyList(),
            existingSnapshot = existingSnapshot,
            prefs = prefs,
            snapshot = currentEmptySnapshot
        )

        assertTrue(effectiveGroups.isEmpty())
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

    private fun samplePreview(
        id: String,
        type: ContentType,
        name: String
    ): MetaPreview {
        return MetaPreview(
            id = id,
            type = type,
            name = name,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
    }

    private fun tmdbRow(
        catalogId: String,
        items: List<MetaPreview>
    ): CatalogRow {
        return CatalogRow(
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "https://api.themoviedb.org/3",
            catalogId = catalogId,
            catalogName = "TMDB $catalogId",
            type = ContentType.MOVIE,
            items = items,
            hasMore = false,
            supportsSkip = false
        )
    }

    private fun addonRow(
        addonId: String,
        catalogId: String,
        items: List<MetaPreview>
    ): CatalogRow {
        return CatalogRow(
            addonId = addonId,
            addonName = addonId,
            addonBaseUrl = "https://example.com/$addonId",
            catalogId = catalogId,
            catalogName = "Catalog $catalogId",
            type = ContentType.MOVIE,
            items = items,
            hasMore = false,
            supportsSkip = false
        )
    }

    private fun tmdbGroup(catalogId: String): PersistedSyntheticCatalogGroup {
        return PersistedSyntheticCatalogGroup(
            orderKey = catalogId,
            rows = listOf(
                tmdbRow(
                    catalogId = catalogId,
                    items = listOf(samplePreview("tmdb:1", ContentType.MOVIE, "TMDB Movie"))
                )
            )
        )
    }

    private fun listOption(
        key: String,
        isPersonal: Boolean,
        title: String = key
    ): MDBListListOption {
        return MDBListListOption(
            key = key,
            owner = "owner",
            listId = key.substringAfter(':'),
            title = title,
            itemCount = 10,
            isPersonal = isPersonal
        )
    }

    private fun traktPopularListOption(key: String, title: String): TraktPopularListOption {
        return TraktPopularListOption(
            key = key,
            userId = "user",
            listId = key.substringAfter(':'),
            catalogIdBase = "trakt_list_${key.substringAfter(':')}",
            title = title,
            itemCount = 10
        )
    }

    private fun customCatalog(
        key: String,
        type: ContentType,
        title: String = key
    ): MDBListCustomCatalog {
        return MDBListCustomCatalog(
            key = key,
            catalogId = "catalog_${key.substringAfter(':')}",
            catalogName = title,
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
