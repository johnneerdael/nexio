package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.data.repository.MetadataDisplayRepository
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.LocalizationDisplayState
import com.nexio.tv.domain.model.MDBListRatings
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ResolvedDetailDisplayDocument
import com.nexio.tv.domain.model.ResolvedDetailRatingDisplay
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.TrailerDisplayState
import io.mockk.coEvery
import io.mockk.every
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetaDetailsResolvedDocumentTest {
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
    fun `detail applies resolved document display rating trailer and localization`() = runTest(dispatcher) {
        val document = resolvedDocument()
        val displayRepository = mockk<MetadataDisplayRepository>()
        coEvery {
            displayRepository.resolveDetailDisplay(match<MetadataRequest> { request ->
                request.contentId == "tmdb:1399" &&
                    request.contentType == ContentType.SERIES &&
                    request.depth == MetadataDepth.DETAIL_FULL &&
                    request.language == "nl-NL"
            })
        } returns document
        val viewModel = buildMetaDetailsViewModel(
            meta = minimalSeriesMeta().copy(id = "tmdb:1399"),
            itemId = "tmdb:1399",
            itemType = "series",
            addonBaseUrl = null,
            profileBoundary = mockk {
                every { currentLanguageTag() } returns "nl-NL"
            },
            metadataDisplayRepository = displayRepository,
            tmdbSettings = TmdbSettings(
                enabled = true,
                useBasicInfo = true,
                useDetails = true,
                useArtwork = true,
                useCredits = true,
                useEpisodes = false,
                useMoreLikeThis = false,
                useReviews = false,
                useCollections = false
            )
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertSame(document, state.resolvedDetail)
        assertEquals("Nederlandse titel", state.meta?.name)
        assertEquals("Nederlandse samenvatting", state.meta?.description)
        assertEquals(listOf("Drama", "Fantasy"), state.meta?.genres)
        assertEquals("57", state.meta?.runtime)
        assertEquals("2011-04-17", state.meta?.releaseInfo)
        assertEquals(9.2f, state.meta?.imdbRating)
        assertEquals(TitleRatingSource.TMDB, state.meta?.ratingSource)
        assertEquals(document.trailer, state.trailerState)
        assertEquals("provider_language_fallback", state.localizationFallbackReason)
        assertEquals(MDBListRatings(imdb = 9.2, trakt = 91.0), state.mdbListRatings)
        assertEquals(true, state.showMdbListImdb)
        assertFalse(state.isLoading)
    }

    private fun resolvedDocument(): ResolvedDetailDisplayDocument =
        ResolvedDetailDisplayDocument(
            route = MetadataRoute(
                provider = MetadataPrimaryProvider.TMDB,
                parentId = "tmdb:1399",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
                sourceContext = MetadataSourceContext(),
                targetIds = mapOf(MetadataPrimaryProvider.TMDB to "1399"),
                trace = emptyList()
            ),
            identity = ContentIdentity(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "1399",
                providerIds = ProviderIds(tmdb = "1399", imdb = "tt0944947")
            ),
            fields = ResolvedDisplayFields(
                title = "Nederlandse titel",
                originalTitle = null,
                year = 2011,
                releaseDate = "2011-04-17",
                overview = "Nederlandse samenvatting",
                genres = listOf("Drama", "Fantasy"),
                runtimeText = "57 min"
            ),
            artwork = ArtworkBundle(),
            rating = TitleRating(9.2, TitleRatingSource.TMDB),
            trailer = TrailerDisplayState(
                fallbackTrailerYtIds = listOf("abc123"),
                resolverSource = "shared-resolver",
                lastResolvedAtMs = 1234L
            ),
            seasons = emptyList(),
            people = null,
            reviews = emptyList(),
            recommendations = emptyList(),
            collection = emptyList(),
            sourceTrace = emptyList(),
            localization = LocalizationDisplayState(
                requestedLanguage = "nl-NL",
                selectedLanguage = "en-US",
                fallbackReason = "provider_language_fallback"
            ),
            ratings = ResolvedDetailRatingDisplay(
                mdbListRatings = MDBListRatings(imdb = 9.2, trakt = 91.0),
                showMdbListImdb = true
            )
        )

    private fun minimalSeriesMeta(): Meta =
        Meta(
            id = "tmdb:1399",
            type = ContentType.SERIES,
            rawType = "series",
            name = "Preview title",
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
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )
}
