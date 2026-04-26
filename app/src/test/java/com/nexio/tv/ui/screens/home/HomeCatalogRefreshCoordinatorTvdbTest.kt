package com.nexio.tv.ui.screens.home

import android.content.Context
import com.nexio.tv.core.player.PlaybackActivityTracker
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.metadata.router.testMetadataRouterFacade
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataDiagnosticEvent
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogRefreshCoordinatorTvdbTest {

    @Test
    fun `catalog refresh tvdb success does not call tmdb`() = runTest {
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = tvdbEnrichment(),
            diagnostics = listOf(
                TvMetadataDiagnosticEvent(
                    reason = TvMetadataDecisionReason.TMDB_TV_SKIPPED,
                    contentId = "tt0944947",
                    provider = TvProvider.TVDB,
                    fallbackProvider = TvProvider.TMDB
                )
            )
        )
        val coordinator = coordinator(
            tvMetadataRouter = tvMetadataRouter,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )
        val logs = mutableListOf<Pair<String, String?>>()

        val result = coordinator.overlayProviderLocalizedMetadata(seriesPreview()) { event, details ->
            logs += event to details
        }

        assertEquals("TVDB title", result.name)
        assertEquals("TVDB description", result.description)
        assertEquals(listOf("Drama"), result.genres)
        assertEquals("2011", result.releaseInfo)
        assertEquals(8.9f, result.imdbRating)
        assertEquals("tvdb-backdrop", result.background)
        assertEquals("tvdb-logo", result.logo)
        assertEquals("tvdb-poster", result.poster)
        assertTrue(logs.contains("tmdb_tv_skipped" to "itemKey=series:tt0944947"))
        coVerify(exactly = 1) {
            tvMetadataRouter.fetchEnrichment(
                TvMetadataRequest(
                    contentId = "tt0944947",
                    fallbackContentId = null,
                    contentType = ContentType.SERIES,
                    language = "eng"
                )
            )
        }
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
    }

    @Test
    fun `catalog refresh movie enrichment remains tmdb backed`() = runTest {
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        coEvery { tmdbService.ensureTmdbId("tt0133093", "movie") } returns "603"
        coEvery {
            tmdbMetadataService.fetchEnrichment(
                tmdbId = "603",
                contentType = ContentType.MOVIE
            )
        } returns tmdbEnrichment(
            localizedTitle = "The Matrix",
            description = "Movie description",
            genres = listOf("Sci-Fi"),
            backdrop = "tmdb-backdrop",
            logo = "tmdb-logo",
            poster = "tmdb-poster",
            releaseInfo = "1999",
            rating = 8.7
        )
        val coordinator = coordinator(
            tvMetadataRouter = tvMetadataRouter,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )

        val result = coordinator.overlayProviderLocalizedMetadata(moviePreview())

        assertEquals("The Matrix", result.name)
        assertEquals("Movie description", result.description)
        assertEquals(listOf("Sci-Fi"), result.genres)
        assertEquals("1999", result.releaseInfo)
        assertEquals(8.7f, result.imdbRating)
        assertEquals("tmdb-backdrop", result.background)
        assertEquals("tmdb-logo", result.logo)
        assertEquals("tmdb-poster", result.poster)
        coVerify(exactly = 0) { tvMetadataRouter.fetchEnrichment(any()) }
    }

    private fun coordinator(
        tvMetadataRouter: TvMetadataRouter,
        tmdbService: TmdbService,
        tmdbMetadataService: TmdbMetadataService
    ): HomeCatalogRefreshCoordinator {
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val profileBoundary = mockk<ProfileBoundary>()
        val titleRatingOverrideRepository = mockk<com.nexio.tv.data.repository.TitleRatingOverrideRepository>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(enabled = true, apiKey = "tmdb-key"))
        every { profileBoundary.currentLanguageTag() } returns "en"
        coEvery { titleRatingOverrideRepository.enrichPreview(any()) } answers { firstArg() }
        return HomeCatalogRefreshCoordinator(
            catalogRepository = mockk<CatalogRepository>(relaxed = true),
            metaRepository = mockk<MetaRepository>(relaxed = true),
            titleRatingOverrideRepository = titleRatingOverrideRepository,
            metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true),
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>(relaxed = true),
            profileBoundary = profileBoundary,
            appContext = mockk<Context>(relaxed = true),
            playbackActivityTracker = mockk<PlaybackActivityTracker>(relaxed = true)
        )
    }

    private fun seriesPreview(): MetaPreview {
        return MetaPreview(
            id = "tt0944947",
            type = ContentType.SERIES,
            name = "Game of Thrones",
            poster = "fallback-poster",
            posterShape = PosterShape.POSTER,
            background = "fallback-backdrop",
            logo = "fallback-logo",
            description = "Fallback description",
            releaseInfo = "2010",
            imdbRating = 7.0f,
            genres = listOf("Fantasy")
        )
    }

    private fun moviePreview(): MetaPreview {
        return MetaPreview(
            id = "tt0133093",
            type = ContentType.MOVIE,
            name = "Matrix",
            poster = "fallback-poster",
            posterShape = PosterShape.POSTER,
            background = "fallback-backdrop",
            logo = "fallback-logo",
            description = "Fallback description",
            releaseInfo = "1998",
            imdbRating = 7.0f,
            genres = listOf("Action")
        )
    }

    private fun tvdbEnrichment(): TvMetadataEnrichment {
        return TvMetadataEnrichment(
            seriesTvdbId = 121361,
            localizedTitle = "TVDB title",
            description = "TVDB description",
            genres = listOf("Drama"),
            backdrop = "tvdb-backdrop",
            logo = "tvdb-logo",
            poster = "tvdb-poster",
            releaseInfo = "2011",
            rating = 8.9
        )
    }

    private fun tmdbEnrichment(
        localizedTitle: String?,
        description: String?,
        genres: List<String>,
        backdrop: String?,
        logo: String?,
        poster: String?,
        releaseInfo: String?,
        rating: Double?
    ): TmdbEnrichment {
        return TmdbEnrichment(
            localizedTitle = localizedTitle,
            description = description,
            genres = genres,
            backdrop = backdrop,
            logo = logo,
            poster = poster,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = releaseInfo,
            rating = rating,
            runtimeMinutes = null,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = null,
            countries = null,
            language = null,
            collectionId = null,
            collectionName = null
        )
    }
}
