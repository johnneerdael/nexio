package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Plan B Task 15 (adapter-bridge): IdleScreensaverRepository now sources both legacy
 * `slides` / `trailerCandidates` flows from `ResolvedDisplaySurfaceRepository` via
 * the new `IdleScreensaverDisplayItem` projection. `ScreensaverCandidateRepository`
 * is no longer queried (param retained for the Hilt graph; Task 26 will remove).
 */
class IdleScreensaverRepositoryTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @Test
    fun `warmFromCache publishes image slides from resolved display surface`() = runTest {
        val artwork = artworkRef(assetKey = "fight-club-backdrop", imageType = ArtworkType.BACKDROP)
        val surfaceRepository = mockk<ResolvedDisplaySurfaceRepository>()
        coEvery {
            surfaceRepository.getSnapshot(ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY, 2)
        } returns listOf(
            resolvedItem(
                contentId = "tmdb:550",
                title = "Fight Club",
                artwork = ArtworkBundle(backdrop = artwork),
                rating = TitleRating(8.8, TitleRatingSource.IMDB)
            )
        )
        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = mockk(relaxed = true),
            surfaceRepository = surfaceRepository,
            activeProfileId = { 2 }
        )

        repository.warmFromCache()

        val slide = repository.slides.value.single()
        assertEquals("tmdb:550", slide.itemId)
        assertEquals("movie", slide.itemType)
        assertEquals("Fight Club", slide.title)
        assertEquals(listOf("Drama", "Thriller"), slide.genres)
        assertEquals("139 min", slide.runtime)
        assertEquals(artwork.toLegacyArtworkString(), slide.backgroundArtwork.toLegacyArtworkString())
        assertEquals(
            listOf(artwork.toLegacyArtworkString()),
            slide.modeData.image.fallbackArtwork.map { it.toLegacyArtworkString() }
        )
        assertEquals(8.8f, slide.imdbRating ?: 0f, 0.0f)
        assertNull(slide.modeData.trailer)
        // Trailer candidate produced from same item too (bridge-derived).
        assertEquals(1, repository.trailerCandidates.value.size)
        assertEquals("Fight Club", repository.trailerCandidates.value.single().title)
    }

    @Test
    fun `refreshOnColdBoot refreshes image slides from resolved screensaver surface`() = runTest {
        val artwork = artworkRef(assetKey = "got-poster", imageType = ArtworkType.POSTER)
        val surfaceRepository = mockk<ResolvedDisplaySurfaceRepository>()
        coEvery {
            surfaceRepository.getSnapshot(ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY, 3)
        } returns listOf(
            resolvedItem(
                contentId = "tmdb:1399",
                title = "Game of Thrones",
                itemType = ContentType.SERIES,
                artwork = ArtworkBundle(poster = artwork),
                rating = TitleRating(9.2, TitleRatingSource.IMDB)
            )
        )
        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = mockk(relaxed = true),
            surfaceRepository = surfaceRepository,
            activeProfileId = { 3 }
        )

        repository.refreshOnColdBoot()

        val slide = repository.slides.value.single()
        assertEquals("tmdb:1399", slide.itemId)
        assertEquals("series", slide.itemType)
        assertEquals("Game of Thrones", slide.title)
        assertEquals(artwork.toLegacyArtworkString(), slide.backgroundArtwork.toLegacyArtworkString())
        assertEquals(9.2f, slide.imdbRating ?: 0f, 0.0f)
    }

    @Test
    fun `warmFromCache populates trailer candidates from resolved display surface`() = runTest {
        val backdrop = artworkRef(assetKey = "backdrop-81189", imageType = ArtworkType.BACKDROP)
        val surfaceRepository = mockk<ResolvedDisplaySurfaceRepository>()
        coEvery {
            surfaceRepository.getSnapshot(ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY, 1)
        } returns listOf(
            resolvedItem(
                contentId = "tvdb:81189",
                title = "Breaking Bad",
                itemType = ContentType.SERIES,
                artwork = ArtworkBundle(backdrop = backdrop),
                rating = TitleRating(9.5, TitleRatingSource.IMDB),
                trailer = TrailerDisplayState(fallbackTrailerYtIds = listOf("HhesaQXLuRY"))
            )
        )
        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = mockk(relaxed = true),
            surfaceRepository = surfaceRepository,
            activeProfileId = { 1 }
        )

        repository.warmFromCache()

        assertEquals(1, repository.trailerCandidates.value.size)
        val trailerCandidate = repository.trailerCandidates.value.single()
        assertEquals("Breaking Bad", trailerCandidate.title)
        assertEquals(listOf("Crime", "Drama"), trailerCandidate.genres)
        assertEquals("47 min", trailerCandidate.runtime)
        assertEquals("HhesaQXLuRY", trailerCandidate.trailerState.fallbackTrailerYtIds.single())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `observeResolvedSurface replaces startup placeholder when surface emits items`() = runTest {
        val surfaceItems = MutableStateFlow<List<ResolvedDisplayItem>>(emptyList())
        val surfaceRepository = mockk<ResolvedDisplaySurfaceRepository>()
        coEvery {
            surfaceRepository.getSnapshot(ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY, 1)
        } returns emptyList()
        every { surfaceRepository.observeScreensaverSurface(1) } returns surfaceItems
        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = mockk(relaxed = true),
            surfaceRepository = surfaceRepository,
            activeProfileId = { 1 }
        )

        repository.warmFromCache()
        val job = launch {
            repository.observeResolvedSurface(profileId = 1)
        }
        advanceUntilIdle()

        assertEquals("__placeholder__", repository.slides.value.single().itemId)
        assertEquals(emptyList<Any>(), repository.trailerCandidates.value)

        val backdrop = artworkRef(assetKey = "breaking-bad-backdrop", imageType = ArtworkType.BACKDROP)
        surfaceItems.value = listOf(
            resolvedItem(
                contentId = "tvdb:81189",
                title = "Breaking Bad",
                itemType = ContentType.SERIES,
                artwork = ArtworkBundle(backdrop = backdrop),
                rating = TitleRating(9.5, TitleRatingSource.IMDB),
                trailer = TrailerDisplayState(fallbackTrailerYtIds = listOf("HhesaQXLuRY"))
            )
        )
        advanceUntilIdle()

        assertEquals("tvdb:81189", repository.slides.value.single().itemId)
        assertEquals("Breaking Bad", repository.trailerCandidates.value.single().title)
        assertEquals(
            "HhesaQXLuRY",
            repository.trailerCandidates.value.single().trailerState.fallbackTrailerYtIds.single()
        )
        job.cancel()
    }

    @Test
    fun `warmFromCache publishes placeholder slide when surface has no items`() = runTest {
        val surfaceRepository = mockk<ResolvedDisplaySurfaceRepository>()
        coEvery {
            surfaceRepository.getSnapshot(ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY, 4)
        } returns emptyList()
        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = mockk(relaxed = true),
            surfaceRepository = surfaceRepository,
            activeProfileId = { 4 }
        )

        repository.warmFromCache()

        val slide = repository.slides.value.single()
        assertEquals("__placeholder__", slide.itemId)
        assertEquals("placeholder", slide.itemType)
        assertEquals("nexio-placeholder://backdrop", slide.backgroundArtwork.toLegacyArtworkString())
        assertEquals(
            listOf("nexio-placeholder://backdrop"),
            slide.modeData.image.fallbackArtwork.map { it.toLegacyArtworkString() }
        )
        assertNull(slide.modeData.trailer)
        assertEquals(emptyList<Any>(), repository.trailerCandidates.value)
    }

    @Test
    fun `warmFromCache filters out items missing title or artwork`() = runTest {
        val backdrop = artworkRef(assetKey = "ok-backdrop", imageType = ArtworkType.BACKDROP)
        val surfaceRepository = mockk<ResolvedDisplaySurfaceRepository>()
        coEvery {
            surfaceRepository.getSnapshot(ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY, 1)
        } returns listOf(
            resolvedItem(
                contentId = "tmdb:1",
                title = "",
                artwork = ArtworkBundle(backdrop = backdrop),
                rating = null
            ),
            resolvedItem(
                contentId = "tmdb:2",
                title = "Has Title",
                artwork = ArtworkBundle(),
                rating = null
            ),
            resolvedItem(
                contentId = "tmdb:3",
                title = "Good",
                artwork = ArtworkBundle(backdrop = backdrop),
                rating = null
            )
        )
        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = mockk(relaxed = true),
            surfaceRepository = surfaceRepository,
            activeProfileId = { 1 }
        )

        repository.warmFromCache()

        assertEquals(1, repository.slides.value.size)
        assertEquals("tmdb:3", repository.slides.value.single().itemId)
        assertEquals(1, repository.screensaverDisplayItems.value.size)
    }

    @Test
    fun `warmFromCache carries logo artwork ref through both adapters`() = runTest {
        val backdrop = artworkRef(assetKey = "hotd-backdrop", imageType = ArtworkType.BACKDROP)
        val logo = artworkRef(assetKey = "hotd-logo", imageType = ArtworkType.LOGO)
        val surfaceRepository = mockk<ResolvedDisplaySurfaceRepository>()
        coEvery {
            surfaceRepository.getSnapshot(ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY, 1)
        } returns listOf(
            resolvedItem(
                contentId = "tmdb:94997",
                title = "House of the Dragon",
                itemType = ContentType.SERIES,
                artwork = ArtworkBundle(backdrop = backdrop, logo = logo),
                rating = null
            )
        )
        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = mockk(relaxed = true),
            surfaceRepository = surfaceRepository,
            activeProfileId = { 1 }
        )

        repository.warmFromCache()

        assertSame(logo, repository.slides.value.single().logoArtwork)
        assertSame(logo, repository.trailerCandidates.value.single().logoArtwork)
    }

    @Test
    fun `warmFromCache clears out of range ratings on both adapters`() = runTest {
        val backdrop = artworkRef(assetKey = "hotd-backdrop", imageType = ArtworkType.BACKDROP)
        val surfaceRepository = mockk<ResolvedDisplaySurfaceRepository>()
        coEvery {
            surfaceRepository.getSnapshot(ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY, 1)
        } returns listOf(
            resolvedItem(
                contentId = "tmdb:94997",
                title = "House of the Dragon",
                itemType = ContentType.SERIES,
                artwork = ArtworkBundle(backdrop = backdrop),
                rating = TitleRating(1_767_427.0, TitleRatingSource.TMDB)
            )
        )
        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = mockk(relaxed = true),
            surfaceRepository = surfaceRepository,
            activeProfileId = { 1 }
        )

        repository.warmFromCache()

        assertNull(repository.slides.value.single().imdbRating)
        assertNull(repository.trailerCandidates.value.single().imdbRating)
    }

    private fun resolvedItem(
        contentId: String,
        title: String,
        itemType: ContentType = ContentType.MOVIE,
        artwork: ArtworkBundle,
        rating: TitleRating?,
        trailer: TrailerDisplayState = TrailerDisplayState()
    ): ResolvedDisplayItem {
        val (provider, id) = contentId.split(":", limit = 2).let { it[0] to it[1] }
        val mediaKind = when (itemType) {
            ContentType.MOVIE -> MetadataMediaKind.MOVIE
            ContentType.SERIES -> MetadataMediaKind.SERIES
            else -> MetadataMediaKind.MOVIE
        }
        val runtimeText = if (itemType == ContentType.SERIES) "47 min" else "139 min"
        val genres = if (itemType == ContentType.SERIES) listOf("Crime", "Drama") else listOf("Drama", "Thriller")
        return ResolvedDisplayItem(
            itemKey = "${itemType.toApiString()}:$contentId",
            contentId = contentId,
            parentId = contentId,
            itemType = itemType,
            mediaKind = mediaKind,
            canonicalProvider = provider.uppercase(),
            canonicalId = id,
            imdbId = null,
            stableIds = ProviderIds(),
            display = ResolvedDisplayFields(
                title = title,
                originalTitle = null,
                year = if (itemType == ContentType.SERIES) 2008 else 1999,
                releaseDate = if (itemType == ContentType.SERIES) "2008" else "1999",
                overview = "Overview",
                genres = genres,
                runtimeText = runtimeText
            ),
            artwork = artwork,
            rating = rating,
            trailer = trailer,
            hydrationState = HydrationState.CANONICAL_READY,
            sourceTrace = emptyList(),
            updatedAtMs = 1_000L
        )
    }

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
