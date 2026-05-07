package com.nexio.tv.core.recommendations

import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.TmdbDiscoveryService
import com.nexio.tv.data.repository.TmdbDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoveryService
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidTvFeedCatalogServiceTmdbTest {

    @Test
    fun `feed options include enabled TMDB catalog from snapshot`() = runTest {
        val tmdbPrefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES)
        )
        val service = service(
            tmdbSnapshot = currentTmdbSnapshot(
                tmdbPrefs,
                TmdbCatalogIds.TRENDING_MOVIES to tmdbRow(
                    catalogId = TmdbCatalogIds.TRENDING_MOVIES,
                    items = listOf(meta("tmdb:1", "Movie"))
                )
            ),
            tmdbPrefs = tmdbPrefs
        )

        val option = service.observeFeedOptions().first()
            .single { it.key == "tmdb_movie_${TmdbCatalogIds.TRENDING_MOVIES}" }

        assertEquals("TMDB Trending Movies", option.title)
        assertEquals("TMDB", option.sourceLabel)
        assertEquals("Movie", option.typeLabel)
    }

    @Test
    fun `disabled TMDB catalog is not exposed even if snapshot has row`() = runTest {
        val service = service(
            tmdbSnapshot = TmdbDiscoverySnapshot(
                rowsByCatalog = mapOf(
                    TmdbCatalogIds.TRENDING_MOVIES to tmdbRow(
                        catalogId = TmdbCatalogIds.TRENDING_MOVIES,
                        items = listOf(meta("tmdb:1", "Movie"))
                    )
                )
            ),
            tmdbPrefs = TmdbCatalogPreferences(
                enabledCatalogs = emptySet(),
                catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES)
            )
        )

        val keys = service.observeFeedOptions().first().map { it.key }

        assertFalse("tmdb_movie_${TmdbCatalogIds.TRENDING_MOVIES}" in keys)
    }

    @Test
    fun `feed options exclude TMDB rows without current preference provenance`() = runTest {
        val tmdbPrefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            includeAdult = true,
            hideUnreleasedDigital = false
        )
        val service = service(
            tmdbSnapshot = TmdbDiscoverySnapshot(
                rowsByCatalog = mapOf(
                    TmdbCatalogIds.TRENDING_MOVIES to tmdbRow(
                        catalogId = TmdbCatalogIds.TRENDING_MOVIES,
                        items = listOf(meta("tmdb:1", "Trending"))
                    ),
                    TmdbCatalogIds.POPULAR_MOVIES to tmdbRow(
                        catalogId = TmdbCatalogIds.POPULAR_MOVIES,
                        items = listOf(meta("tmdb:2", "Popular"))
                    )
                ),
                updatedAtMs = 123L,
                includeAdult = false,
                hideUnreleasedDigital = false,
                catalogIdsWithCurrentPreferences = setOf(TmdbCatalogIds.TRENDING_MOVIES)
            ),
            tmdbPrefs = tmdbPrefs
        )

        val keys = service.observeFeedOptions().first().map { it.key }

        assertFalse("tmdb_movie_${TmdbCatalogIds.TRENDING_MOVIES}" in keys)
        assertFalse("tmdb_movie_${TmdbCatalogIds.POPULAR_MOVIES}" in keys)
    }

    @Test
    fun `resolving selected TMDB feed returns snapshot items and row base url`() = runTest {
        val tmdbRow = tmdbRow(
            catalogId = TmdbCatalogIds.TRENDING_MOVIES,
            items = listOf(meta("tmdb:1", "Movie"))
        )
        val tmdbPrefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES)
        )
        val fixture = fixture(
            tmdbSnapshot = currentTmdbSnapshot(tmdbPrefs, TmdbCatalogIds.TRENDING_MOVIES to tmdbRow),
            tmdbPrefs = tmdbPrefs
        )

        val row = fixture.service.resolveFeed("tmdb_movie_${TmdbCatalogIds.TRENDING_MOVIES}")

        assertEquals(listOf("tmdb:1"), row?.items?.map { it.id })
        assertEquals("https://api.themoviedb.org/3", row?.addonBaseUrl)
        assertEquals("TMDB", row?.option?.sourceLabel)
        coVerify(exactly = 1) {
            fixture.tmdbDiscoveryService.refreshCatalogs(
                tmdbPrefs,
                force = false,
                catalogIds = setOf(TmdbCatalogIds.TRENDING_MOVIES)
            )
        }
    }

    @Test
    fun `resolving selected TMDB rows refreshes only selected TMDB catalog ids`() = runTest {
        val tmdbPrefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(
                TmdbCatalogIds.TRENDING_MOVIES,
                TmdbCatalogIds.POPULAR_MOVIES,
                TmdbCatalogIds.YEAR_MOVIES
            ),
            catalogOrder = listOf(
                TmdbCatalogIds.TRENDING_MOVIES,
                TmdbCatalogIds.POPULAR_MOVIES,
                TmdbCatalogIds.YEAR_MOVIES
            )
        )
        val fixture = fixture(
            tmdbSnapshot = TmdbDiscoverySnapshot(
                rowsByCatalog = mapOf(
                    TmdbCatalogIds.TRENDING_MOVIES to tmdbRow(
                        catalogId = TmdbCatalogIds.TRENDING_MOVIES,
                        items = listOf(meta("tmdb:1", "Movie"))
                    )
                )
            ),
            tmdbPrefs = tmdbPrefs
        )

        fixture.service.resolveSelectedRows(
            listOf("tmdb_movie_${TmdbCatalogIds.TRENDING_MOVIES}")
        )

        coVerify(exactly = 1) {
            fixture.tmdbDiscoveryService.refreshCatalogs(
                tmdbPrefs,
                force = false,
                catalogIds = setOf(TmdbCatalogIds.TRENDING_MOVIES)
            )
        }
    }

    @Test
    fun `resolving continue watching feed does not refresh default enabled TMDB catalogs`() = runTest {
        val fixture = fixture(
            tmdbSnapshot = TmdbDiscoverySnapshot(),
            tmdbPrefs = TmdbCatalogPreferences()
        )

        fixture.service.resolveFeed(AndroidTvFeedCatalogService.CONTINUE_WATCHING_FEED_KEY)

        coVerify(exactly = 0) {
            fixture.tmdbDiscoveryService.refreshCatalogs(any(), force = false)
        }
    }

    @Test
    fun `resolving only continue watching selected rows does not refresh default enabled TMDB catalogs`() = runTest {
        val fixture = fixture(
            tmdbSnapshot = TmdbDiscoverySnapshot(),
            tmdbPrefs = TmdbCatalogPreferences()
        )

        fixture.service.resolveSelectedRows(
            listOf(AndroidTvFeedCatalogService.CONTINUE_WATCHING_FEED_KEY)
        )

        coVerify(exactly = 0) {
            fixture.tmdbDiscoveryService.refreshCatalogs(any(), force = false)
        }
    }

    @Test
    fun `resolving continue watching feed falls back when profile snapshot is unavailable`() = runTest {
        val fixture = fixture(
            tmdbSnapshot = TmdbDiscoverySnapshot(),
            tmdbPrefs = TmdbCatalogPreferences(),
            continueWatchingSnapshotFlow = emptyFlow()
        )

        val row = withTimeout(1_000) {
            fixture.service.resolveFeed(AndroidTvFeedCatalogService.CONTINUE_WATCHING_FEED_KEY)
        }

        assertEquals(emptyList<MetaPreview>(), row?.items)
    }

    @Test
    fun `resolving selected continue watching rows falls back when profile snapshot is unavailable`() = runTest {
        val fixture = fixture(
            tmdbSnapshot = TmdbDiscoverySnapshot(),
            tmdbPrefs = TmdbCatalogPreferences(),
            continueWatchingSnapshotFlow = emptyFlow()
        )

        val rows = withTimeout(1_000) {
            fixture.service.resolveSelectedRows(
                listOf(AndroidTvFeedCatalogService.CONTINUE_WATCHING_FEED_KEY)
            )
        }

        assertEquals(emptyList<AndroidTvFeedRow>(), rows)
    }

    private fun service(
        tmdbSnapshot: TmdbDiscoverySnapshot,
        tmdbPrefs: TmdbCatalogPreferences
    ): AndroidTvFeedCatalogService {
        return fixture(tmdbSnapshot = tmdbSnapshot, tmdbPrefs = tmdbPrefs).service
    }

    private fun fixture(
        tmdbSnapshot: TmdbDiscoverySnapshot,
        tmdbPrefs: TmdbCatalogPreferences,
        continueWatchingSnapshotFlow: Flow<ContinueWatchingSnapshot> = flowOf(ContinueWatchingSnapshot())
    ): ServiceFixture {
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(emptyList())
        }
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true) {
            every { disabledHomeCatalogKeys } returns flowOf(emptyList())
        }
        val traktDiscoveryService = mockk<TraktDiscoveryService>(relaxed = true) {
            every { observeSnapshot() } returns flowOf(TraktDiscoverySnapshot())
        }
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { catalogPreferences } returns flowOf(
                TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList())
            )
        }
        val mdbListDiscoveryService = mockk<MDBListDiscoveryService>(relaxed = true) {
            every { observeSnapshot() } returns flowOf(MDBListDiscoverySnapshot())
        }
        val mdbListSettingsDataStore = mockk<MDBListSettingsDataStore>(relaxed = true) {
            every { catalogPreferences } returns flowOf(MDBListCatalogPreferences())
        }
        val tmdbDiscoveryService = mockk<TmdbDiscoveryService>(relaxed = true) {
            every { observeSnapshot() } returns flowOf(tmdbSnapshot)
            coEvery { refreshCatalogs(tmdbPrefs, force = false, catalogIds = any()) } returns tmdbSnapshot
            coEvery { refreshCatalogs(tmdbPrefs, force = false) } returns tmdbSnapshot
        }
        val tmdbCatalogSettingsDataStore = mockk<TmdbCatalogSettingsDataStore>(relaxed = true) {
            every { catalogPreferences } returns flowOf(tmdbPrefs)
        }
        val continueWatchingSnapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true) {
            every { observeProfileSnapshot(1) } returns continueWatchingSnapshotFlow
            coEvery { ensureFresh(force = false) } returns Unit
        }

        val profileManager = mockk<ProfileManager> {
            every { activeProfileId.value } returns 1
        }

        return ServiceFixture(
            service = AndroidTvFeedCatalogService(
                addonRepository = addonRepository,
                catalogRepository = mockk<CatalogRepository>(relaxed = true),
                layoutPreferenceDataStore = layoutPreferenceDataStore,
                profileManager = profileManager,
                traktDiscoveryService = traktDiscoveryService,
                traktSettingsDataStore = traktSettingsDataStore,
                mdbListDiscoveryService = mdbListDiscoveryService,
                mdbListSettingsDataStore = mdbListSettingsDataStore,
                tmdbDiscoveryService = tmdbDiscoveryService,
                tmdbCatalogSettingsDataStore = tmdbCatalogSettingsDataStore,
                continueWatchingSnapshotService = continueWatchingSnapshotService
            ),
            tmdbDiscoveryService = tmdbDiscoveryService
        )
    }

    private data class ServiceFixture(
        val service: AndroidTvFeedCatalogService,
        val tmdbDiscoveryService: TmdbDiscoveryService
    )

    private fun tmdbRow(
        catalogId: String,
        items: List<MetaPreview>
    ): CatalogRow {
        return CatalogRow(
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "https://api.themoviedb.org/3",
            catalogId = catalogId,
            catalogName = "TMDB Trending Movies",
            type = ContentType.MOVIE,
            items = items,
            isLoading = false,
            hasMore = false,
            supportsSkip = false
        )
    }

    private fun currentTmdbSnapshot(
        prefs: TmdbCatalogPreferences,
        vararg rows: Pair<String, CatalogRow>
    ): TmdbDiscoverySnapshot {
        val sanitized = prefs.sanitized()
        return TmdbDiscoverySnapshot(
            rowsByCatalog = rows.toMap(),
            updatedAtMs = 123L,
            includeAdult = sanitized.includeAdult,
            hideUnreleasedDigital = sanitized.hideUnreleasedDigital,
            catalogIdsWithCurrentPreferences = rows.map { it.first }.toSet()
        )
    }

    private fun meta(id: String, title: String): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = title,
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
}
