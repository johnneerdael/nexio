package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.ui.screensaver.ScreensaverSlideCandidate
import com.nexio.tv.ui.screensaver.ScreensaverTrailerCandidate
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class IdleScreensaverRepositoryTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @Test
    fun `warmFromCache publishes image slides from screensaver candidates`() = runTest {
        val candidateRepository = mockk<ScreensaverCandidateRepository>()
        val artwork = artworkRef(assetKey = "fight-club-backdrop", imageType = ArtworkType.BACKDROP)
        every { candidateRepository.observeImageCandidates(2) } returns flowOf(
            listOf(
                candidate(
                    preferredImage = artwork,
                    title = "Fight Club",
                    rating = TitleRating(8.8, TitleRatingSource.IMDB)
                )
            )
        )
        every { candidateRepository.observeTrailerCandidates(2) } returns flowOf(emptyList())
        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = candidateRepository,
            activeProfileId = { 2 }
        )

        repository.warmFromCache()

        val slide = repository.slides.value.single()
        assertEquals("tmdb:550", slide.itemId)
        assertEquals("movie", slide.itemType)
        assertEquals("Fight Club", slide.title)
        assertEquals("nexio-artwork://asset/fight-club-backdrop", slide.backgroundUrl)
        assertEquals(listOf("nexio-artwork://asset/fight-club-backdrop"), slide.modeData.image.fallbackArtworkUrls)
        assertEquals(8.8f, slide.imdbRating ?: 0f, 0.0f)
        assertNull(slide.modeData.trailer)
        assertEquals(emptyList<Any>(), repository.trailerCandidates.value)
        verify(exactly = 1) { candidateRepository.observeImageCandidates(2) }
        verify(exactly = 1) { candidateRepository.observeTrailerCandidates(2) }
    }

    @Test
    fun `refreshOnColdBoot refreshes image slides from active profile candidates`() = runTest {
        val candidateRepository = mockk<ScreensaverCandidateRepository>()
        every { candidateRepository.observeImageCandidates(3) } returns flowOf(
            listOf(
                candidate(
                    itemKey = "series:tmdb:1399",
                    contentId = "tmdb:1399",
                    itemType = "series",
                    preferredImage = artworkRef(assetKey = "got-poster", imageType = ArtworkType.POSTER),
                    title = "Game of Thrones",
                    rating = TitleRating(9.2, TitleRatingSource.IMDB)
                )
            )
        )
        every { candidateRepository.observeTrailerCandidates(3) } returns flowOf(emptyList())
        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = candidateRepository,
            activeProfileId = { 3 }
        )

        repository.refreshOnColdBoot()

        val slide = repository.slides.value.single()
        assertEquals("tmdb:1399", slide.itemId)
        assertEquals("series", slide.itemType)
        assertEquals("Game of Thrones", slide.title)
        assertEquals("nexio-artwork://asset/got-poster", slide.backgroundUrl)
        assertEquals(9.2f, slide.imdbRating ?: 0f, 0.0f)
        verify(exactly = 1) { candidateRepository.observeImageCandidates(3) }
        verify(exactly = 1) { candidateRepository.observeTrailerCandidates(3) }
    }

    @Test
    fun `warmFromCache populates trailer candidates from resolved display surface`() = runTest {
        val candidateRepository = mockk<ScreensaverCandidateRepository>()
        every { candidateRepository.observeImageCandidates(profileId = 1) } returns flowOf(emptyList())
        every { candidateRepository.observeTrailerCandidates(profileId = 1) } returns flowOf(
            listOf(
                ScreensaverTrailerCandidate(
                    itemKey = "series:tvdb:81189",
                    contentId = "tvdb:81189",
                    itemType = "series",
                    title = "Breaking Bad",
                    releaseInfo = "2008",
                    overview = "A chemistry teacher...",
                    rating = TitleRating(
                        value = 9.5,
                        source = TitleRatingSource.IMDB
                    ),
                    artwork = ArtworkBundle(backdrop = artworkRef("backdrop-81189", ArtworkType.BACKDROP)),
                    fallbackTrailerYtIds = emptyList(),
                    stableIds = ProviderIds(tvdb = "81189", imdb = "tt0903747")
                )
            )
        )

        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = candidateRepository,
            activeProfileId = { 1 }
        )

        repository.warmFromCache()

        assertEquals(1, repository.trailerCandidates.value.size)
        assertEquals("Breaking Bad", repository.trailerCandidates.value.single().title)
        assertEquals("81189", repository.trailerCandidates.value.single().stableIds.tvdb)
    }

    private fun candidate(
        itemKey: String = "movie:tmdb:550",
        contentId: String = "tmdb:550",
        itemType: String = "movie",
        preferredImage: ArtworkDisplayRef,
        title: String,
        rating: TitleRating?
    ) = ScreensaverSlideCandidate(
        itemKey = itemKey,
        contentId = contentId,
        itemType = itemType,
        title = title,
        subtitle = "1999",
        overview = "Overview",
        rating = rating,
        artwork = ArtworkBundle(backdrop = preferredImage),
        preferredImage = preferredImage,
        stableIds = ProviderIds(tmdb = contentId.removePrefix("tmdb:"), imdb = "tt0137523"),
        trace = emptyList()
    )

    private fun artworkRef(
        assetKey: String,
        imageType: ArtworkType
    ) = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey("decision-$assetKey"),
        assetKey = ArtworkAssetKey(assetKey),
        imageType = imageType,
        selectedProvider = null,
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace(selectedProvider = "TOP_POSTERS", sourceRole = "ARTWORK")
    )
}
