package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.ui.screensaver.ScreensaverSlideCandidate
import com.nexio.tv.ui.screensaver.ScreensaverTrailerCandidate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverCandidateRepositoryTest {
    @Test
    fun `image candidates are projected from resolved display surface with artwork refs rating stable ids and trace`() = runTest {
        val surface = testSurface()
        val repository = ScreensaverCandidateRepository(surface)
        val trace = HydratedHomeFieldTrace(
            field = "title",
            selectedProvider = "TMDB",
            sourceRole = "PRIMARY"
        )
        val backdrop = artworkRef(key = "backdrop-550", imageType = ArtworkType.BACKDROP)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "movie:tmdb:550",
                    title = "Fight Club",
                    artwork = ArtworkBundle(
                        backdrop = backdrop,
                        poster = artworkRef(key = "poster-550", imageType = ArtworkType.POSTER)
                    ),
                    sourceTrace = listOf(trace)
                )
            )
        )

        val candidates = repository.observeImageCandidates(profileId = 1).first()

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals("movie:tmdb:550", candidate.itemKey)
        assertEquals("tmdb:550", candidate.contentId)
        assertEquals("movie", candidate.itemType)
        assertEquals("Fight Club", candidate.title)
        assertEquals("1999", candidate.subtitle)
        assertEquals("Overview", candidate.overview)
        assertEquals(8.8, candidate.rating?.value ?: 0.0, 0.0)
        assertSame(backdrop, candidate.preferredImage)
        assertEquals("tt0137523", candidate.stableIds.imdb)
        assertEquals(listOf(trace), candidate.trace)
    }

    @Test
    fun `image candidates fall back to poster when backdrop artwork is absent`() = runTest {
        val surface = testSurface()
        val repository = ScreensaverCandidateRepository(surface)
        val poster = artworkRef(key = "poster-550", imageType = ArtworkType.POSTER)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "movie:tmdb:550",
                    title = "Fight Club",
                    artwork = ArtworkBundle(poster = poster)
                )
            )
        )

        val candidate = repository.observeImageCandidates(profileId = 1).first().single()

        assertSame(poster, candidate.preferredImage)
    }

    @Test
    fun `image candidates exclude items without poster or backdrop artwork`() = runTest {
        val surface = testSurface()
        val repository = ScreensaverCandidateRepository(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "movie:tmdb:551",
                    title = "No Art",
                    artwork = ArtworkBundle()
                )
            )
        )

        assertEquals(emptyList<ScreensaverSlideCandidate>(), repository.observeImageCandidates(1).first())
    }

    @Test
    fun `image candidates exclude items without a display title`() = runTest {
        val surface = testSurface()
        val repository = ScreensaverCandidateRepository(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(itemKey = "movie:tmdb:551", title = null),
                resolvedItem(itemKey = "movie:tmdb:552", title = "   ")
            )
        )

        assertEquals(emptyList<ScreensaverSlideCandidate>(), repository.observeImageCandidates(1).first())
    }

    @Test
    fun `trailer candidates come from resolved items even when trailer ids are empty`() = runTest {
        val surface = testSurface()
        val repository = ScreensaverCandidateRepository(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem("series:tvdb:81189", title = "Breaking Bad").copy(
                    contentId = "tvdb:81189",
                    parentId = "tvdb:81189",
                    itemType = ContentType.SERIES,
                    mediaKind = MetadataMediaKind.SERIES,
                    canonicalProvider = "TVDB",
                    canonicalId = "81189",
                    stableIds = ProviderIds(tvdb = "81189", imdb = "tt0903747")
                )
            )
        )

        val candidates = repository.observeTrailerCandidates(profileId = 1).first()

        assertEquals(1, candidates.size)
        assertEquals("series:tvdb:81189", candidates.single().itemKey)
        assertEquals("Breaking Bad", candidates.single().title)
        assertTrue(candidates.single().fallbackTrailerYtIds.isEmpty())
        assertEquals("81189", candidates.single().stableIds.tvdb)
    }

    @Test
    fun `trailer candidates exclude items without title or artwork`() = runTest {
        val surface = testSurface()
        val repository = ScreensaverCandidateRepository(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(itemKey = "movie:tmdb:551", title = null),
                resolvedItem(itemKey = "movie:tmdb:552", title = "   "),
                resolvedItem(itemKey = "movie:tmdb:553", title = "No Art", artwork = ArtworkBundle())
            )
        )

        assertEquals(emptyList<ScreensaverTrailerCandidate>(), repository.observeTrailerCandidates(1).first())
    }

    private fun resolvedItem(
        itemKey: String,
        title: String?,
        artwork: ArtworkBundle = ArtworkBundle(backdrop = artworkRef("backdrop-550", ArtworkType.BACKDROP)),
        sourceTrace: List<HydratedHomeFieldTrace> = emptyList()
    ) = ResolvedDisplayItem(
        itemKey = itemKey,
        contentId = "tmdb:550",
        parentId = "tmdb:550",
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = "TMDB",
        canonicalId = "550",
        imdbId = "tt0137523",
        stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
        display = ResolvedDisplayFields(
            title = title,
            originalTitle = null,
            year = 1999,
            releaseDate = "1999",
            overview = "Overview",
            genres = listOf("Drama"),
            runtimeText = "139m"
        ),
        artwork = artwork,
        rating = TitleRating(8.8, TitleRatingSource.IMDB),
        trailer = TrailerDisplayState(fallbackTrailerYtIds = emptyList()),
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = sourceTrace,
        updatedAtMs = 1L
    )

    private fun testSurface() = ResolvedDisplaySurfaceRepository(
        activeProfileSession = {
            ActiveProfileSession(
                profileId = 1,
                sessionId = "test-session",
                sessionOrdinal = 1L,
                startedAtMs = 1_000L
            )
        }
    )

    private fun artworkRef(
        key: String,
        imageType: ArtworkType
    ) = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey(key),
        assetKey = null,
        imageType = imageType,
        selectedProvider = null,
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace(selectedProvider = "TOP_POSTERS", sourceRole = "ARTWORK")
    )
}
