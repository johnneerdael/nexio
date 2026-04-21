package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.anime.KitsuMetadataService
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
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.Video
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
    fun `anime detail uses Kitsu characters related and productions with guarded tmdb person bridge`() = runTest(dispatcher) {
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        val kitsuMetadataService = mockk<KitsuMetadataService>()

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
        coEvery { tmdbMetadataService.findPersonIdByExactName("Trina Nishimura") } returns 1162
        coEvery { tmdbMetadataService.findCompanyIdByExactName("Production I.G") } returns 10

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
        assertEquals(1162, meta.castMembers.single().tmdbId)
        assertEquals("Production I.G", meta.productionCompanies.single().name)
        assertEquals(10, meta.productionCompanies.single().tmdbId)
        assertEquals("10", meta.productionCompanies.single().providerId)
        assertEquals(1, viewModel.uiState.value.relatedItems.size)
        assertEquals("Attack on Titan Picture Drama", viewModel.uiState.value.relatedItems.single().name)
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
        coVerify(exactly = 1) { kitsuMetadataService.fetchAdvancedDetail("kitsu:7442", any(), "ja") }
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
}
