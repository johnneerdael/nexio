package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.data.repository.EpisodeRatingsSelectionRepository
import com.nexio.tv.data.repository.MDBListRepository
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.Video
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetaDetailsTvdbProviderRoutingTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `series detail enrichment uses tvdb router and does not call tmdb`() = runTest(dispatcher) {
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        coEvery { tmdbService.ensureTmdbId(any(), any()) } coAnswers {
            val metadataEnrichmentCall = Throwable().stackTrace.any { frame ->
                frame.className.endsWith("MetaDetailsViewModel") && frame.methodName == "enrichMeta"
            }
            check(!metadataEnrichmentCall) { "Series detail metadata enrichment must not resolve TMDB IDs" }
            null
        }
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = 121361,
                localizedTitle = "TVDB Title",
                description = "TVDB overview",
                genres = listOf("Drama", "Sci-Fi"),
                backdrop = "https://image.tvdb.test/backdrop.jpg",
                logo = "https://image.tvdb.test/logo.png",
                releaseInfo = "2020-01-01",
                rating = 8.7,
                runtimeMinutes = 52,
                ageRating = "TV-MA",
                countries = listOf("United States"),
                language = "en"
            )
        )

        val viewModel = buildMetaDetailsViewModel(
            meta = buildSeriesMeta(),
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            tvMetadataRouter = tvMetadataRouter,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useCredits = false,
                useProductions = false,
                useNetworks = false,
                useEpisodes = false,
                useMoreLikeThis = false,
                useReviews = false,
                useCollections = false
            )
        )

        advanceUntilIdle()

        val meta = viewModel.uiState.value.meta
        assertEquals("TVDB Title", meta?.name)
        assertEquals("TVDB overview", meta?.description)
        assertEquals(listOf("Drama", "Sci-Fi"), meta?.genres)
        assertEquals("https://image.tvdb.test/backdrop.jpg", meta?.background)
        assertEquals("https://image.tvdb.test/logo.png", meta?.logo)
        assertEquals("52", meta?.runtime)
        assertEquals("2020-01-01", meta?.releaseInfo)
        assertEquals("TV-MA", meta?.ageRating)
        assertEquals("United States", meta?.country)
        assertEquals("en", meta?.language)
        assertEquals(false, viewModel.uiState.value.isAnimeDetail)

        val enrichmentRequest = slot<TvMetadataRequest>()
        coVerify(exactly = 1) { tvMetadataRouter.fetchEnrichment(capture(enrichmentRequest)) }
        assertEquals("tt0944947", enrichmentRequest.captured.contentId)
        assertEquals(ContentType.SERIES, enrichmentRequest.captured.contentType)
        assertEquals("en", enrichmentRequest.captured.language)
        coVerify(exactly = 0) { tmdbService.ensureTmdbId("metadata-enrichment", any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
    }

    @Test
    fun `tmdb series detail enters router with tmdb id instead of rewriting to imdb first`() = runTest(dispatcher) {
        val tmdbService = mockk<TmdbService>(relaxed = true)
        coEvery { tmdbService.tmdbToImdb(any(), any()) } returns "tt0944947"
        coEvery { tmdbService.ensureTmdbId(any(), any()) } returns null

        buildMetaDetailsViewModel(
            meta = buildSeriesMeta().copy(id = "tmdb:1399"),
            itemId = "tmdb:1399",
            itemType = "series",
            tmdbService = tmdbService,
            tmdbSettings = TmdbSettings(
                enabled = false,
                useMoreLikeThis = false,
                useReviews = false
            )
        )

        advanceUntilIdle()

        coVerify(exactly = 0) { tmdbService.tmdbToImdb(1399, "series") }
    }

    @Test
    fun `movie detail enrichment stays backed by tmdb`() = runTest(dispatcher) {
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        coEvery { tmdbMetadataService.fetchEnrichment("550", ContentType.MOVIE, any()) } returns TmdbEnrichment(
            localizedTitle = "TMDB Movie",
            description = "TMDB movie overview",
            genres = listOf("Thriller"),
            backdrop = "https://image.tmdb.test/backdrop.jpg",
            logo = "https://image.tmdb.test/logo.png",
            poster = null,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = "1999-10-15",
            rating = 8.4,
            runtimeMinutes = 139,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = "R",
            countries = listOf("United States"),
            language = "en",
            collectionId = null,
            collectionName = null
        )

        val viewModel = buildMetaDetailsViewModel(
            meta = buildMovieMeta().copy(id = "tmdb:550"),
            itemId = "tmdb:550",
            itemType = "movie",
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            tvMetadataRouter = tvMetadataRouter,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useCredits = false,
                useProductions = false,
                useNetworks = false,
                useEpisodes = false,
                useMoreLikeThis = false,
                useReviews = false,
                useCollections = false
            )
        )

        advanceUntilIdle()

        assertEquals("TMDB Movie", viewModel.uiState.value.meta?.name)
        coVerify(atLeast = 1) { tmdbMetadataService.fetchEnrichment("550", ContentType.MOVIE, any()) }
        coVerify(exactly = 0) { tvMetadataRouter.fetchEnrichment(any()) }
    }

    @Test
    fun `updates episode rows from tvdb and does not call tmdb episode enrichment`() = runTest(dispatcher) {
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = null
        )
        coEvery { tvMetadataRouter.fetchEpisodeEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf(
                (1 to 1) to TvEpisodeMetadata(
                    providerEpisodeId = "tvdb:9001",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    title = "TVDB Pilot",
                    overview = "TVDB episode overview",
                    thumbnail = "https://image.tvdb.test/episode.jpg",
                    airDate = "2020-02-03",
                    runtimeMinutes = 57
                )
            )
        )

        val viewModel = buildMetaDetailsViewModel(
            meta = buildSeriesMeta(),
            tmdbMetadataService = tmdbMetadataService,
            tvMetadataRouter = tvMetadataRouter,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useArtwork = false,
                useBasicInfo = false,
                useDetails = false,
                useCredits = false,
                useProductions = false,
                useNetworks = false,
                useEpisodes = true,
                useMoreLikeThis = false,
                useReviews = false,
                useCollections = false
            )
        )

        advanceUntilIdle()

        val episode = viewModel.uiState.value.meta?.videos?.firstOrNull { it.season == 1 && it.episode == 1 }
        assertEquals("TVDB Pilot", episode?.title)
        assertEquals("TVDB episode overview", episode?.overview)
        assertEquals("2020-02-03", episode?.released)
        assertEquals("https://image.tvdb.test/episode.jpg", episode?.thumbnail)
        assertEquals(57, episode?.runtime)

        coVerify(exactly = 1) { tvMetadataRouter.fetchEpisodeEnrichment(any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEpisodeEnrichment(any(), any(), any()) }
    }

    @Test
    fun `series detail remains loading until episode metadata hydration completes`() = runTest(dispatcher) {
        val episodeHydrationStarted = CompletableDeferred<Unit>()
        val allowEpisodeHydration = CompletableDeferred<Unit>()
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = 121361,
                localizedTitle = "TVDB Title"
            )
        )
        coEvery { tvMetadataRouter.fetchEpisodeEnrichment(any()) } coAnswers {
            episodeHydrationStarted.complete(Unit)
            allowEpisodeHydration.await()
            TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (1 to 1) to TvEpisodeMetadata(
                        providerEpisodeId = "tvdb:9001",
                        seasonNumber = 1,
                        episodeNumber = 1,
                        title = "Nederlandse Pilot",
                        overview = "Nederlandse afleveringstekst"
                    )
                )
            )
        }

        val viewModel = buildMetaDetailsViewModel(
            meta = buildSeriesMeta().copy(videos = emptyList()),
            tvMetadataRouter = tvMetadataRouter,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useCredits = false,
                useProductions = false,
                useNetworks = false,
                useEpisodes = true,
                useMoreLikeThis = false,
                useReviews = false,
                useCollections = false
            )
        )

        runCurrent()

        assertEquals(true, episodeHydrationStarted.isCompleted)
        assertEquals(true, viewModel.uiState.value.isLoading)
        assertEquals(null, viewModel.uiState.value.meta)

        allowEpisodeHydration.complete(Unit)
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals("TVDB Title", viewModel.uiState.value.meta?.name)
        assertEquals("Nederlandse Pilot", viewModel.uiState.value.episodesForSeason.single().title)
        assertEquals("Nederlandse afleveringstekst", viewModel.uiState.value.episodesForSeason.single().overview)
    }

    @Test
    fun `tvdb remote imdb id is used for title mdb and episode rating hydration`() = runTest(dispatcher) {
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = 121361,
                localizedTitle = "TVDB Title",
                remoteIds = mapOf(
                    "tvdb" to setOf("121361"),
                    "imdb" to setOf("tt0944947"),
                    "tmdb" to setOf("1399")
                )
            )
        )
        coEvery { tvMetadataRouter.fetchEpisodeEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = emptyMap()
        )

        val titleRatingOverrideRepository = mockk<TitleRatingOverrideRepository>()
        coEvery { titleRatingOverrideRepository.titleRatingCandidates(any<Meta>(), any(), any(), any(), any()) } returns emptyList()
        val mdbListRepository = mockk<MDBListRepository>(relaxed = true)
        val episodeRatingsSelectionRepository = mockk<EpisodeRatingsSelectionRepository>()
        coEvery {
            episodeRatingsSelectionRepository.episodeRatingCandidates(any(), any(), any(), any())
        } returns emptyList()

        buildMetaDetailsViewModel(
            meta = buildSeriesMeta().copy(id = "tvdb:121361"),
            itemId = "tvdb:121361",
            itemType = "series",
            tvMetadataRouter = tvMetadataRouter,
            mdbListRepository = mdbListRepository,
            titleRatingOverrideRepository = titleRatingOverrideRepository,
            episodeRatingsSelectionRepository = episodeRatingsSelectionRepository,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useCredits = false,
                useProductions = false,
                useNetworks = false,
                useEpisodes = false,
                useMoreLikeThis = false,
                useReviews = false,
                useCollections = false
            )
        )

        advanceUntilIdle()

        coVerify {
            titleRatingOverrideRepository.titleRatingCandidates(any<Meta>(), "tt0944947", "series", any(), any())
        }
        coVerify {
            mdbListRepository.getRatingsForMeta(any(), "tt0944947", "series")
        }
        coVerify {
            episodeRatingsSelectionRepository.episodeRatingCandidates(any(), "tt0944947", "series", mapOf(1 to setOf(1)))
        }
    }

    private fun buildSeriesMeta(): Meta {
        return Meta(
            id = "tt0944947",
            type = ContentType.SERIES,
            rawType = "series",
            name = "Original Series",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = listOf(
                Video(
                    id = "tt0944947:1:1",
                    title = "Original Episode",
                    released = null,
                    thumbnail = null,
                    season = 1,
                    episode = 1,
                    overview = null
                )
            ),
            productionCompanies = emptyList(),
            networks = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            trailerYtIds = emptyList()
        )
    }

    private fun buildMovieMeta(): Meta {
        return Meta(
            id = "tt0137523",
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Original Movie",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            trailerYtIds = emptyList()
        )
    }
}
