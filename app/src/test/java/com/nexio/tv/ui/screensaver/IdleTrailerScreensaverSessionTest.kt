package com.nexio.tv.ui.screensaver

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrailerDisplayState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleTrailerScreensaverSessionTest {

    @Test
    fun `prepareIdleTrailerScreensaverSession skips unresolved candidates until a source is ready`() = runBlocking {
        val slides = listOf(
            buildSlide("movie-1", trailerIds = listOf("abc123def45")),
            buildSlide("movie-2", trailerIds = listOf("def456ghi78"))
        )

        val session = prepareIdleTrailerScreensaverSession(
            slides = slides,
            shuffleCandidates = { it }
        ) { candidate, playbackRef ->
            if (candidate.slide.itemId == "movie-2" && playbackRef == TrailerPlaybackRef.YouTubeId("def456ghi78")) {
                TrailerPlaybackSource(videoUrl = "https://video.example.com/movie-2.mp4")
            } else {
                null
            }
        }

        requireNotNull(session)
        assertEquals(listOf("movie-1", "movie-2"), session.candidates.map { it.slide.itemId })
        assertEquals("movie-2", session.initialPlayback.candidate.slide.itemId)
        assertEquals(1, session.initialPlayback.index)
    }

    @Test
    fun `prepareIdleTrailerScreensaverSession returns null when no trailer candidate resolves`() = runBlocking {
        val slides = listOf(buildSlide("movie-1", trailerIds = listOf("abc123def45")))

        val session = prepareIdleTrailerScreensaverSession(
            slides = slides,
            shuffleCandidates = { it }
        ) { _, _ -> null }

        assertNull(session)
    }

    @Test
    fun `resolveNextIdleTrailerPlayback wraps around and skips unresolved candidates`() = runBlocking {
        val candidates = listOf(
            buildCandidate("movie-1", listOf("abc123def45")),
            buildCandidate("movie-2", listOf("def456ghi78")),
            buildCandidate("movie-3", listOf("ghi789jkl01"))
        )

        val playback = resolveNextIdleTrailerPlayback(
            candidates = candidates,
            currentIndex = 2
        ) { candidate, playbackRef ->
            if (candidate.slide.itemId == "movie-2") {
                null
            } else {
                TrailerPlaybackSource(videoUrl = "https://video.example.com/${playbackRef.videoIdForTest()}.mp4")
            }
        }

        requireNotNull(playback)
        assertEquals("movie-1", playback.candidate.slide.itemId)
        assertEquals(0, playback.index)
    }

    @Test
    fun `resolveNextIdleTrailerPlayback skips blacklisted trailer ids and advances to next playable candidate`() = runBlocking {
        val candidates = listOf(
            buildCandidate("movie-1", listOf("abc123def45")),
            buildCandidate("movie-2", listOf("def456ghi78")),
            buildCandidate("movie-3", listOf("ghi789jkl01"))
        )

        val playback = resolveNextIdleTrailerPlayback(
            candidates = candidates,
            currentIndex = 0,
            skippedPlaybackKeys = setOf(idleTrailerPlaybackKey(candidates[1], TrailerPlaybackRef.YouTubeId("def456ghi78")))
        ) { _, playbackRef ->
            TrailerPlaybackSource(videoUrl = "https://video.example.com/${playbackRef.videoIdForTest()}.mp4")
        }

        requireNotNull(playback)
        assertEquals("movie-3", playback.candidate.slide.itemId)
        assertEquals(TrailerPlaybackRef.YouTubeId("ghi789jkl01"), playback.playbackRef)
        assertEquals(2, playback.index)
    }

    @Test
    fun `shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout returns true when no first frame and not yet failed`() {
        assertTrue(
            shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout(
                hasRenderedFirstFrame = false,
                playbackKey = "movie:m1:abc",
                failedPlaybackKeys = emptySet()
            )
        )
    }

    @Test
    fun `shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout returns false when first frame rendered`() {
        assertFalse(
            shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout(
                hasRenderedFirstFrame = true,
                playbackKey = "movie:m1:abc",
                failedPlaybackKeys = emptySet()
            )
        )
    }

    @Test
    fun `shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout returns false when already in failed set`() {
        assertFalse(
            shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout(
                hasRenderedFirstFrame = false,
                playbackKey = "movie:m1:abc",
                failedPlaybackKeys = setOf("movie:m1:abc")
            )
        )
    }

    @Test
    fun `resolveNextIdleTrailerPlayback returns null when all candidates are skipped`() = runBlocking {
        val candidates = listOf(
            buildCandidate("movie-1", listOf("abc123def45")),
            buildCandidate("movie-2", listOf("def456ghi78"))
        )
        val allSkipped = candidates.flatMap { c ->
            c.playbackRefs.map { ref -> idleTrailerPlaybackKey(c, ref) }
        }.toSet()

        val playback = resolveNextIdleTrailerPlayback(
            candidates = candidates,
            currentIndex = 0,
            skippedPlaybackKeys = allSkipped
        ) { _, playbackRef ->
            TrailerPlaybackSource(videoUrl = "https://video.example.com/${playbackRef.videoIdForTest()}.mp4")
        }

        assertNull(playback)
    }

    @Test
    fun `prepare trailer screensaver session can resolve candidate without preexisting youtube ids`() = runBlocking {
        val candidates = listOf(
            IdleTrailerScreensaverCandidate(
                itemId = "tvdb:81189",
                itemType = "series",
                addonBaseUrl = "",
                title = "Breaking Bad",
                logoArtwork = null,
                backgroundArtwork = artworkRef("nexio-artwork://decision/backdrop-81189", ArtworkType.BACKDROP),
                fallbackArtwork = listOf(artworkRef("nexio-artwork://decision/backdrop-81189", ArtworkType.BACKDROP)),
                genres = emptyList(),
                description = "A chemistry teacher...",
                releaseInfo = "2008",
                runtime = null,
                imdbRating = 9.5f,
                tomatoesRating = null,
                trailerState = TrailerDisplayState(),
                stableIds = ProviderIds(tvdb = "81189", imdb = "tt0903747")
            )
        )

        val session = prepareIdleTrailerScreensaverSessionFromCandidates(
            candidates = candidates,
            shuffleCandidates = { it }
        ) { candidate, playbackRef ->
            if (candidate.itemId == "tvdb:81189" && playbackRef is TrailerPlaybackRef.ItemLookup) {
                TrailerPlaybackSource(videoUrl = "https://video.example/breaking-bad.m3u8")
            } else {
                null
            }
        }

        requireNotNull(session)
        assertEquals("tvdb:81189", session.initialPlayback.candidate.itemId)
        assertTrue(session.initialPlayback.playbackRef is TrailerPlaybackRef.ItemLookup)
    }

    @Test
    fun `resolveNextIdleTrailerPlayback with empty skip set loops back to first candidate`() = runBlocking {
        val candidates = listOf(
            buildCandidate("movie-1", listOf("abc123def45")),
            buildCandidate("movie-2", listOf("def456ghi78"))
        )

        val playback = resolveNextIdleTrailerPlayback(
            candidates = candidates,
            currentIndex = 1,
            skippedPlaybackKeys = emptySet()
        ) { _, playbackRef ->
            TrailerPlaybackSource(videoUrl = "https://video.example.com/${playbackRef.videoIdForTest()}.mp4")
        }

        requireNotNull(playback)
        assertEquals("movie-1", playback.candidate.slide.itemId)
        assertEquals(0, playback.index)
    }

    @Test
    fun `collectIdleTrailerScreensaverCandidates only keeps slides with trailer ids`() {
        val candidates = collectIdleTrailerScreensaverCandidates(
            listOf(
                buildSlide("movie-1", trailerIds = listOf("abc123def45")),
                buildSlide("movie-2", trailerIds = emptyList()),
                buildSlide("movie-3", trailerIds = listOf("ghi789jkl01", "ghi789jkl01"))
            )
        )

        assertEquals(listOf("movie-1", "movie-3"), candidates.map { it.slide.itemId })
        assertTrue(candidates[1].playbackRefs.distinct() == candidates[1].playbackRefs)
    }

    private fun buildSlide(
        itemId: String,
        trailerIds: List<String>
    ): IdleScreensaverSlide {
        return IdleScreensaverSlide(
            itemId = itemId,
            itemType = "movie",
            addonBaseUrl = "https://api.example.com",
            title = "Example $itemId",
            backgroundArtwork = artworkRef("https://image.example.com/$itemId.jpg", ArtworkType.BACKDROP),
            logoArtwork = null,
            genres = emptyList(),
            description = null,
            releaseInfo = "2024",
            runtime = null,
            imdbRating = null,
            tomatoesRating = null,
            modeData = IdleScreensaverModeData(
                image = IdleScreensaverImageModeData(
                    fallbackArtwork = listOf(artworkRef("https://image.example.com/$itemId.jpg", ArtworkType.BACKDROP))
                ),
                trailer = trailerIds.takeIf { it.isNotEmpty() }?.let {
                    IdleScreensaverTrailerModeData(TrailerDisplayState(fallbackTrailerYtIds = it))
                }
            )
        )
    }

    private fun buildCandidate(
        itemId: String,
        trailerIds: List<String>
    ): IdleTrailerScreensaverCandidate {
        return IdleTrailerScreensaverCandidate(
            itemId = itemId,
            itemType = "movie",
            addonBaseUrl = "https://api.example.com",
            title = "Example $itemId",
            logoArtwork = null,
            backgroundArtwork = artworkRef("https://image.example.com/$itemId.jpg", ArtworkType.BACKDROP),
            fallbackArtwork = listOf(artworkRef("https://image.example.com/$itemId.jpg", ArtworkType.BACKDROP)),
            genres = emptyList(),
            description = null,
            releaseInfo = "2024",
            runtime = null,
            imdbRating = null,
            tomatoesRating = null,
            trailerState = TrailerDisplayState(fallbackTrailerYtIds = trailerIds)
        )
    }

    private fun artworkRef(value: String, imageType: ArtworkType): ArtworkDisplayRef =
        ArtworkDisplayRef.LegacyString(
            value = value,
            imageType = imageType,
            trace = ArtworkTrace.empty()
        )

    private fun TrailerPlaybackRef.videoIdForTest(): String =
        (this as TrailerPlaybackRef.YouTubeId).videoId
}
