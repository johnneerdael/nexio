package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.core.anime.projection.AnimeDetailResult
import com.nexio.tv.core.anime.projection.AnimeSeasonDetailRepository
import com.nexio.tv.core.anime.projection.AnimeSeasonPresentation
import com.nexio.tv.core.anime.projection.AnimeSeasonTab
import com.nexio.tv.core.anime.projection.AnimeWorkIdentity
import com.nexio.tv.core.anime.projection.AnimeGroupingConfidence
import com.nexio.tv.core.anime.projection.AnimeWorkGroupKey
import com.nexio.tv.core.anime.projection.CoordinateConfidence
import com.nexio.tv.core.anime.projection.SeasonPresentationSource
import com.nexio.tv.core.metadata.router.ReviewsPage
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tvdb.KitsuAdvancedAnimeCharacter
import com.nexio.tv.core.tvdb.KitsuAdvancedAnimeDetail
import com.nexio.tv.core.tvdb.KitsuAdvancedProductionCompany
import com.nexio.tv.core.tvdb.KitsuAdvancedRelatedTitle
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.MetaReviewSource
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.Video
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetaDetailsKitsuAdvancedMetadataTest {
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
    fun `anime detail uses provider plan Kitsu characters related and productions without tmdb bridge`() = runTest(dispatcher) {
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        val kitsuMetadataService = mockk<KitsuMetadataService>()
        coEvery { kitsuMetadataService.fetchReviews(any(), any(), any(), any()) } returns emptyReviewsPage()

        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.KITSU,
            reason = TvMetadataDecisionReason.KITSU_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = null,
                localizedTitle = "Attack on Titan",
                language = "ja"
            )
        )
        coEvery { kitsuMetadataService.fetchAdvancedDetail("kitsu:7442", any(), "ja") } returns KitsuAdvancedAnimeDetail(
            characters = listOf(
                KitsuAdvancedAnimeCharacter(
                    characterId = "38505",
                    characterName = "Mikasa Ackerman",
                    characterImage = "https://kitsu.test/mikasa.jpg",
                    actorId = "1162",
                    actorName = "Trina Nishimura",
                    actorImage = "https://kitsu.test/trina.jpg",
                    language = "English",
                    featured = true
                )
            ),
            relatedTitles = listOf(
                KitsuAdvancedRelatedTitle(
                    mediaId = "7889",
                    mediaType = "anime",
                    title = "Attack on Titan Picture Drama",
                    synopsis = "Related title",
                    poster = "https://kitsu.test/related.jpg",
                    releaseInfo = "2013-01-01",
                    relationKind = "other"
                )
            ),
            productionCompanies = listOf(
                KitsuAdvancedProductionCompany(
                    producerId = "10",
                    producerName = "Production I.G",
                    role = "producer"
                )
            )
        )
        val viewModel = buildMetaDetailsViewModel(
            meta = buildAnimeMeta(),
            itemId = "kitsu:7442",
            itemType = "series",
            tmdbMetadataService = tmdbMetadataService,
            tvMetadataRouter = tvMetadataRouter,
            kitsuMetadataService = kitsuMetadataService,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useBasicInfo = true,
                useDetails = true,
                useCredits = true,
                useProductions = true,
                useNetworks = false,
                useEpisodes = false,
                useMoreLikeThis = true,
                useReviews = true,
                useCollections = false
            )
        )

        advanceUntilIdle()

        val meta = viewModel.uiState.value.meta!!
        assertEquals("Mikasa Ackerman", meta.castMembers.single().name)
        assertEquals("Trina Nishimura", meta.castMembers.single().character)
        assertEquals("https://kitsu.test/mikasa.jpg", meta.castMembers.single().photo)
        assertEquals(null, meta.castMembers.single().tmdbId)
        assertEquals("Production I.G", meta.productionCompanies.single().name)
        assertEquals(null, meta.productionCompanies.single().tmdbId)
        assertEquals("10", meta.productionCompanies.single().providerId)
        assertEquals(1, viewModel.uiState.value.relatedItems.size)
        assertEquals("Attack on Titan Picture Drama", viewModel.uiState.value.relatedItems.single().title)
        assertTrue(viewModel.uiState.value.reviews.isEmpty())
        assertEquals(false, viewModel.uiState.value.isReviewsLoading)
        coVerify(exactly = 0) { tmdbMetadataService.fetchMoreLikeThis(any(), any(), any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchReviews(any(), any(), any(), any()) }
    }

    @Test
    fun `anime detail keeps unmatched actor and company cards visible but without tmdb ids`() = runTest(dispatcher) {
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        val kitsuMetadataService = mockk<KitsuMetadataService>()
        coEvery { kitsuMetadataService.fetchReviews(any(), any(), any(), any()) } returns emptyReviewsPage()

        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.KITSU,
            reason = TvMetadataDecisionReason.KITSU_SUCCESS,
            value = TvMetadataEnrichment(seriesTvdbId = null, localizedTitle = "Attack on Titan", language = "ja")
        )
        coEvery { kitsuMetadataService.fetchAdvancedDetail("kitsu:7442", any(), "ja") } returns KitsuAdvancedAnimeDetail(
            characters = listOf(
                KitsuAdvancedAnimeCharacter(
                    characterId = "38505",
                    characterName = "Mikasa Ackerman",
                    characterImage = "https://kitsu.test/mikasa.jpg",
                    actorId = "1162",
                    actorName = "Trina Nishimura",
                    language = "English",
                    featured = true
                )
            ),
            productionCompanies = listOf(
                KitsuAdvancedProductionCompany(
                    producerId = "10",
                    producerName = "Production I.G"
                )
            )
        )
        coEvery { tmdbMetadataService.findPersonIdByExactName("Trina Nishimura") } returns null
        coEvery { tmdbMetadataService.findCompanyIdByExactName("Production I.G") } returns null

        val viewModel = buildMetaDetailsViewModel(
            meta = buildAnimeMeta(),
            itemId = "kitsu:7442",
            itemType = "series",
            tmdbMetadataService = tmdbMetadataService,
            tvMetadataRouter = tvMetadataRouter,
            kitsuMetadataService = kitsuMetadataService,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useBasicInfo = true,
                useDetails = true,
                useCredits = true,
                useProductions = true,
                useNetworks = false,
                useEpisodes = false,
                useMoreLikeThis = true,
                useReviews = true,
                useCollections = false
            )
        )

        advanceUntilIdle()

        val meta = viewModel.uiState.value.meta!!
        assertEquals("Mikasa Ackerman", meta.castMembers.single().name)
        assertEquals(null, meta.castMembers.single().tmdbId)
        assertEquals("Production I.G", meta.productionCompanies.single().name)
        assertEquals(null, meta.productionCompanies.single().tmdbId)
    }

    @Test
    fun `anime detail uses fallback item id for advanced Kitsu enrichment when meta id is opaque`() = runTest(dispatcher) {
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        val kitsuMetadataService = mockk<KitsuMetadataService>()
        coEvery { kitsuMetadataService.fetchReviews(any(), any(), any(), any()) } returns emptyReviewsPage()

        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.KITSU,
            reason = TvMetadataDecisionReason.KITSU_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = null,
                localizedTitle = "My Hero Academia",
                language = "ja"
            )
        )
        coEvery { kitsuMetadataService.fetchAdvancedDetail("kitsu:7442", any(), "ja") } returns KitsuAdvancedAnimeDetail(
            characters = listOf(
                KitsuAdvancedAnimeCharacter(
                    characterId = "1",
                    characterName = "Izuku Midoriya",
                    actorId = "2",
                    actorName = "Daiki Yamashita",
                    characterImage = "https://kitsu.test/deku.jpg",
                    language = "Japanese",
                    featured = true
                )
            )
        )
        coEvery { tmdbMetadataService.findPersonIdByExactName("Daiki Yamashita") } returns null

        val viewModel = buildMetaDetailsViewModel(
            meta = buildAnimeMeta().copy(id = "hero-academia-opaque"),
            itemId = "kitsu:7442",
            itemType = "series",
            tmdbMetadataService = tmdbMetadataService,
            tvMetadataRouter = tvMetadataRouter,
            kitsuMetadataService = kitsuMetadataService,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useBasicInfo = true,
                useDetails = true,
                useCredits = true,
                useProductions = true,
                useNetworks = false,
                useEpisodes = false,
                useMoreLikeThis = true,
                useReviews = true,
                useCollections = false
            )
        )

        advanceUntilIdle()

        val meta = viewModel.uiState.value.meta!!
        assertEquals("Izuku Midoriya", meta.castMembers.single().name)
        assertEquals("Daiki Yamashita", meta.castMembers.single().character)
        assertEquals(true, viewModel.uiState.value.isAnimeDetail)
        coVerify(atLeast = 1) { kitsuMetadataService.fetchAdvancedDetail("kitsu:7442", any(), "ja") }
    }

    @Test
    fun `anime detail renders characters and related from provider plan without tmdb bridge hydration`() = runTest(dispatcher) {
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        val kitsuMetadataService = mockk<KitsuMetadataService>()
        coEvery { kitsuMetadataService.fetchReviews(any(), any(), any(), any()) } returns emptyReviewsPage()

        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.KITSU,
            reason = TvMetadataDecisionReason.KITSU_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = null,
                localizedTitle = "My Hero Academia",
                language = "ja"
            )
        )
        coEvery { kitsuMetadataService.fetchAdvancedDetail("kitsu:11469", any(), "ja") } returns KitsuAdvancedAnimeDetail(
            characters = listOf(
                KitsuAdvancedAnimeCharacter(
                    characterId = "1",
                    characterName = "Izuku Midoriya",
                    actorId = "2",
                    actorName = "Daiki Yamashita",
                    characterImage = "https://kitsu.test/deku.jpg",
                    language = "Japanese",
                    featured = true
                )
            ),
            relatedTitles = listOf(
                KitsuAdvancedRelatedTitle(
                    mediaId = "12268",
                    mediaType = "anime",
                    title = "Boku no Hero Academia 2",
                    synopsis = "Season 2",
                    poster = "https://kitsu.test/mha2.jpg",
                    relationKind = "sequel"
                )
            )
        )
        coEvery { tmdbMetadataService.findPersonIdByExactName(any()) } coAnswers {
            delay(10_000)
            null
        }

        val viewModel = buildMetaDetailsViewModel(
            meta = buildAnimeMeta().copy(id = "kitsu:11469"),
            itemId = "kitsu:11469",
            itemType = "series",
            tmdbMetadataService = tmdbMetadataService,
            tvMetadataRouter = tvMetadataRouter,
            kitsuMetadataService = kitsuMetadataService,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useBasicInfo = true,
                useDetails = true,
                useCredits = true,
                useProductions = true,
                useNetworks = false,
                useEpisodes = false,
                useMoreLikeThis = true,
                useReviews = true,
                useCollections = false
            )
        )

        runCurrent()

        val meta = viewModel.uiState.value.meta!!
        assertEquals(true, viewModel.uiState.value.isAnimeDetail)
        assertEquals("Izuku Midoriya", meta.castMembers.single().name)
        assertEquals("Daiki Yamashita", meta.castMembers.single().character)
        assertEquals(null, meta.castMembers.single().tmdbId)
        assertEquals(1, viewModel.uiState.value.relatedItems.size)

        advanceTimeBy(10_000)
        advanceUntilIdle()
    }

    @Test
    fun `anime series detail stays loading until Kitsu episode metadata is available`() = runTest(dispatcher) {
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        val kitsuMetadataService = mockk<KitsuMetadataService>()
        val animeSeasonDetailRepository = mockk<AnimeSeasonDetailRepository>()

        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.KITSU,
            reason = TvMetadataDecisionReason.KITSU_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = null,
                localizedTitle = "One Piece",
                language = "ja"
            )
        )
        coEvery { kitsuMetadataService.fetchAdvancedDetail("kitsu:12", any(), "ja") } returns KitsuAdvancedAnimeDetail()
        coEvery { kitsuMetadataService.fetchReviews(any(), any(), any(), any()) } returns emptyReviewsPage()

        val lucidEpisode = Video(
            id = "kitsu:12:1:1",
            title = "I'm Luffy! The Man Who's Gonna Be King of the Pirates!",
            released = null,
            thumbnail = "https://media.kitsu.test/one-piece-e1.jpg",
            streams = emptyList(),
            season = 1,
            episode = 1,
            overview = "Luffy begins his voyage.",
            runtime = 24
        )
        val workIdentity = AnimeWorkIdentity(
            groupKey = AnimeWorkGroupKey("anime-work:kitsu:12"),
            primaryKitsuId = "12",
            memberKitsuIds = setOf("12"),
            providerIds = ProviderIds(kitsu = "12"),
            confidence = AnimeGroupingConfidence.LOW,
            evidence = emptyList()
        )
        val seasonPresentation = AnimeSeasonPresentation(
            work = workIdentity,
            seasons = listOf(AnimeSeasonTab(1, null, 1, "12", false)),
            selectedSeason = 1,
            source = SeasonPresentationSource.CURATED_PER_RESOURCE,
            confidence = CoordinateConfidence.HIGH
        )
        coEvery { animeSeasonDetailRepository.resolveAndHydrateAnimeDetail(any(), any(), any()) } coAnswers {
            delay(10_000)
            val baseMeta = firstArg<Meta>()
            AnimeDetailResult.Success(
                meta = baseMeta.copy(videos = listOf(lucidEpisode)),
                presentation = seasonPresentation
            )
        }

        val viewModel = buildMetaDetailsViewModel(
            meta = buildAnimeMeta().copy(id = "kitsu:12", name = "One Piece"),
            itemId = "kitsu:12",
            itemType = "series",
            tvMetadataRouter = tvMetadataRouter,
            kitsuMetadataService = kitsuMetadataService,
            animeSeasonDetailRepository = animeSeasonDetailRepository,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useBasicInfo = true,
                useDetails = true,
                useCredits = true,
                useProductions = true,
                useNetworks = false,
                useEpisodes = true,
                useMoreLikeThis = true,
                useReviews = false,
                useCollections = false
            )
        )

        runCurrent()

        assertEquals(true, viewModel.uiState.value.isLoading)
        assertEquals(null, viewModel.uiState.value.meta)

        advanceTimeBy(10_000)
        advanceUntilIdle()

        val meta = viewModel.uiState.value.meta!!
        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(1, meta.videos.size)
        assertEquals("I'm Luffy! The Man Who's Gonna Be King of the Pirates!", meta.videos.single().title)
    }

    @Test
    fun `anime detail loads Kitsu reviews through provider plan resolved fields`() = runTest(dispatcher) {
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        val kitsuMetadataService = mockk<KitsuMetadataService>()
        coEvery { kitsuMetadataService.fetchReviews(any(), any(), any(), any()) } returns emptyReviewsPage()

        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.KITSU,
            reason = TvMetadataDecisionReason.KITSU_SUCCESS,
            value = TvMetadataEnrichment(seriesTvdbId = null, localizedTitle = "One Piece", language = "ja")
        )
        coEvery { kitsuMetadataService.fetchAdvancedDetail("kitsu:12", any(), "ja") } returns KitsuAdvancedAnimeDetail()
        coEvery { kitsuMetadataService.fetchReviews("kitsu:12", any(), page = 1, limit = 20) } returns ReviewsPage(
            reviews = listOf(
                MetaReview(
                    id = "2355",
                    author = "Zoro",
                    content = "Before I begin, let me address one thing.",
                    rating = 9.0,
                    source = MetaReviewSource.KITSU
                )
            ),
            hasMore = false,
            nextPage = null
        )

        val viewModel = buildMetaDetailsViewModel(
            meta = buildAnimeMeta().copy(id = "kitsu:12", name = "One Piece"),
            itemId = "kitsu:12",
            itemType = "series",
            tvMetadataRouter = tvMetadataRouter,
            kitsuMetadataService = kitsuMetadataService,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useBasicInfo = true,
                useDetails = true,
                useCredits = true,
                useProductions = true,
                useNetworks = false,
                useEpisodes = false,
                useMoreLikeThis = true,
                useReviews = true,
                useCollections = false
            )
        )

        advanceUntilIdle()

        assertEquals(MetaReviewSource.KITSU, viewModel.uiState.value.reviews.single().source)
        assertEquals("Zoro", viewModel.uiState.value.reviews.single().author)
        assertEquals(false, viewModel.uiState.value.isReviewsLoading)
    }

    @Test
    fun `anime detail loads more Kitsu reviews from resolved pagination state`() = runTest(dispatcher) {
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        val kitsuMetadataService = mockk<KitsuMetadataService>()
        coEvery { kitsuMetadataService.fetchReviews(any(), any(), any(), any()) } returns emptyReviewsPage()

        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.KITSU,
            reason = TvMetadataDecisionReason.KITSU_SUCCESS,
            value = TvMetadataEnrichment(seriesTvdbId = null, localizedTitle = "One Piece", language = "ja")
        )
        coEvery { kitsuMetadataService.fetchAdvancedDetail("kitsu:12", any(), "ja") } returns KitsuAdvancedAnimeDetail()
        coEvery { kitsuMetadataService.fetchReviews("kitsu:12", any(), page = 1, limit = 20) } returns ReviewsPage(
            reviews = listOf(
                MetaReview(
                    id = "2355",
                    author = "Zoro",
                    content = "Before I begin, let me address one thing.",
                    rating = 9.0,
                    source = MetaReviewSource.KITSU
                )
            ),
            hasMore = true,
            nextPage = 2
        )
        coEvery { kitsuMetadataService.fetchReviews("kitsu:12", any(), page = 2, limit = 20) } returns ReviewsPage(
            reviews = listOf(
                MetaReview(
                    id = "2356",
                    author = "Nami",
                    content = "The voyage keeps going.",
                    rating = 8.0,
                    source = MetaReviewSource.KITSU
                )
            ),
            hasMore = false,
            nextPage = null
        )

        val viewModel = buildMetaDetailsViewModel(
            meta = buildAnimeMeta().copy(id = "kitsu:12", name = "One Piece"),
            itemId = "kitsu:12",
            itemType = "series",
            tvMetadataRouter = tvMetadataRouter,
            kitsuMetadataService = kitsuMetadataService,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useBasicInfo = true,
                useDetails = true,
                useCredits = true,
                useProductions = true,
                useNetworks = false,
                useEpisodes = false,
                useMoreLikeThis = true,
                useReviews = true,
                useCollections = false
            )
        )

        advanceUntilIdle()
        viewModel.onEvent(MetaDetailsEvent.OnReviewItemFocused(index = 0))
        advanceUntilIdle()

        assertEquals(listOf("Zoro", "Nami"), viewModel.uiState.value.reviews.map { it.author })
        coVerify(exactly = 1) { kitsuMetadataService.fetchReviews("kitsu:12", any(), page = 2, limit = 20) }
    }

    @Test
    fun `addon franchise links expand anime detail seasons across linked season metas`() = runTest(dispatcher) {
        val metaRepository = mockk<com.nexio.tv.domain.repository.MetaRepository>()
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        val kitsuMetadataService = mockk<KitsuMetadataService>()

        val seasonOneMeta = buildAnimeMeta().copy(
            id = "kitsu:46231",
            name = "Solo Leveling",
            links = listOf(
                MetaLink(
                    name = "Sequel: Solo Leveling Season 2",
                    category = "Franchise",
                    url = "stremio:///detail/series/kitsu:48671"
                )
            ),
            videos = listOf(
                Video(
                    id = "tt21209876:1:1",
                    title = "Season 1 Episode 1",
                    released = null,
                    thumbnail = null,
                    season = 1,
                    episode = 1,
                    overview = null
                )
            )
        )
        val seasonTwoMeta = buildAnimeMeta().copy(
            id = "kitsu:48671",
            name = "Solo Leveling Season 2",
            links = listOf(
                MetaLink(
                    name = "Prequel: Solo Leveling",
                    category = "Franchise",
                    url = "stremio:///detail/series/kitsu:46231"
                )
            ),
            videos = listOf(
                Video(
                    id = "tt21209876:2:1",
                    title = "Season 2 Episode 1",
                    released = null,
                    thumbnail = null,
                    season = 2,
                    episode = 1,
                    overview = null
                )
            )
        )

        every {
            metaRepository.getMeta(
                addonBaseUrl = "https://anime-kitsu.strem.fun/manifest.json",
                type = "series",
                id = "kitsu:48671",
                cacheOnDisk = any(),
                writeToDisk = any(),
                origin = any()
            )
        } returns flowOf(NetworkResult.Success(seasonTwoMeta))
        every {
            metaRepository.getMeta(
                addonBaseUrl = "https://anime-kitsu.strem.fun/manifest.json",
                type = "series",
                id = "kitsu:46231",
                cacheOnDisk = any(),
                writeToDisk = any(),
                origin = any()
            )
        } returns flowOf(NetworkResult.Success(seasonOneMeta))
        every { metaRepository.hydrateAddonOriginItem(any(), any(), any(), any(), any(), any()) } returns flowOf(NetworkResult.Success(seasonTwoMeta))

        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.KITSU,
            reason = TvMetadataDecisionReason.KITSU_SUCCESS,
            value = TvMetadataEnrichment(seriesTvdbId = null, localizedTitle = "Solo Leveling", language = "ja")
        )
        coEvery { kitsuMetadataService.fetchAdvancedDetail("kitsu:48671", any(), "ja") } returns KitsuAdvancedAnimeDetail()

        val viewModel = buildMetaDetailsViewModel(
            meta = seasonTwoMeta,
            itemId = "kitsu:48671",
            itemType = "series",
            addonBaseUrl = "https://anime-kitsu.strem.fun/manifest.json",
            metaRepository = metaRepository,
            tmdbMetadataService = tmdbMetadataService,
            tvMetadataRouter = tvMetadataRouter,
            kitsuMetadataService = kitsuMetadataService
        )

        advanceUntilIdle()

        assertEquals(listOf(1, 2), viewModel.uiState.value.seasons)
        assertEquals(2, viewModel.uiState.value.selectedSeason)
        assertEquals("Season 2 Episode 1", viewModel.uiState.value.episodesForSeason.single().title)
    }

    private fun buildAnimeMeta(): Meta {
        return Meta(
            id = "kitsu:7442",
            type = ContentType.SERIES,
            rawType = "series",
            name = "Attack on Titan",
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
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList<Video>(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )
    }

    private fun emptyReviewsPage(): ReviewsPage = ReviewsPage(
        reviews = emptyList(),
        hasMore = false,
        nextPage = null
    )
}
