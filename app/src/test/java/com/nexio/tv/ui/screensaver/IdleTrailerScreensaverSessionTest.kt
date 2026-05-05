package com.nexio.tv.ui.screensaver

import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.domain.model.ProviderIds
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
        ) { candidate, trailerId ->
            if (candidate.slide.itemId == "movie-2" && trailerId == "def456ghi78") {
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
        ) { candidate, trailerId ->
            if (candidate.slide.itemId == "movie-2") {
                null
            } else {
                TrailerPlaybackSource(videoUrl = "https://video.example.com/$trailerId.mp4")
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
            skippedPlaybackKeys = setOf(idleTrailerPlaybackKey(candidates[1], "def456ghi78"))
        ) { candidate, trailerId ->
            TrailerPlaybackSource(videoUrl = "https://video.example.com/$trailerId.mp4")
        }

        requireNotNull(playback)
        assertEquals("movie-3", playback.candidate.slide.itemId)
        assertEquals("ghi789jkl01", playback.trailerId)
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
            c.trailerYtIds.map { id -> idleTrailerPlaybackKey(c, id) }
        }.toSet()

        val playback = resolveNextIdleTrailerPlayback(
            candidates = candidates,
            currentIndex = 0,
            skippedPlaybackKeys = allSkipped
        ) { _, trailerId ->
            TrailerPlaybackSource(videoUrl = "https://video.example.com/$trailerId.mp4")
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
                logoUrl = null,
                backgroundUrl = "nexio-artwork://decision/backdrop-81189",
                fallbackArtworkUrls = listOf("nexio-artwork://decision/backdrop-81189"),
                genres = emptyList(),
                description = "A chemistry teacher...",
                releaseInfo = "2008",
                runtime = null,
                imdbRating = 9.5f,
                tomatoesRating = null,
                trailerYtIds = emptyList(),
                stableIds = ProviderIds(tvdb = "81189", imdb = "tt0903747")
            )
        )

        val session = prepareIdleTrailerScreensaverSessionFromCandidates(
            candidates = candidates,
            shuffleCandidates = { it }
        ) { candidate, trailerId ->
            if (candidate.itemId == "tvdb:81189" && trailerId == RESOLVE_TRAILER_BY_ITEM_SENTINEL) {
                TrailerPlaybackSource(videoUrl = "https://video.example/breaking-bad.m3u8")
            } else {
                null
            }
        }

        requireNotNull(session)
        assertEquals("tvdb:81189", session.initialPlayback.candidate.itemId)
        assertEquals(RESOLVE_TRAILER_BY_ITEM_SENTINEL, session.initialPlayback.trailerId)
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
        ) { _, trailerId ->
            TrailerPlaybackSource(videoUrl = "https://video.example.com/$trailerId.mp4")
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
        assertTrue(candidates[1].trailerYtIds.distinct() == candidates[1].trailerYtIds)
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
            backgroundUrl = "https://image.example.com/$itemId.jpg",
            logoUrl = null,
            genres = emptyList(),
            description = null,
            releaseInfo = "2024",
            runtime = null,
            imdbRating = null,
            tomatoesRating = null,
            modeData = IdleScreensaverModeData(
                image = IdleScreensaverImageModeData(
                    fallbackArtworkUrls = listOf("https://image.example.com/$itemId.jpg")
                ),
                trailer = trailerIds.takeIf { it.isNotEmpty() }?.let {
                    IdleScreensaverTrailerModeData(trailerYtIds = it)
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
            logoUrl = null,
            backgroundUrl = "https://image.example.com/$itemId.jpg",
            fallbackArtworkUrls = listOf("https://image.example.com/$itemId.jpg"),
            genres = emptyList(),
            description = null,
            releaseInfo = "2024",
            runtime = null,
            imdbRating = null,
            tomatoesRating = null,
            trailerYtIds = trailerIds
        )
    }
}
