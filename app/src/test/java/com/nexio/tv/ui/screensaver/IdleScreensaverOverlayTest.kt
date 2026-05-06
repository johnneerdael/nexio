package com.nexio.tv.ui.screensaver

import android.view.KeyEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleScreensaverOverlayTest {

    @Test
    fun `buildScreensaverPrimaryMetaSegments uses a single divider between genres and ratings`() {
        val slide = buildSlide(
            genres = listOf("Action", "Drama", "Thriller"),
            imdbRating = 7.8f,
            tomatoesRating = 88.0
        )

        val segments = buildScreensaverPrimaryMetaSegments(slide)

        assertEquals(
            listOf(
                ScreensaverPrimaryMetaSegment.Genres("Action • Drama • Thriller"),
                ScreensaverPrimaryMetaSegment.Divider,
                ScreensaverPrimaryMetaSegment.Rating(ScreensaverPrimaryMetaSegment.Rating.Kind.IMDB, "7.8"),
                ScreensaverPrimaryMetaSegment.Rating(ScreensaverPrimaryMetaSegment.Rating.Kind.TOMATOES, "88")
            ),
            segments
        )
    }

    @Test
    fun `buildScreensaverPrimaryMetaSegments omits dividers when only ratings are present`() {
        val slide = buildSlide(
            genres = emptyList(),
            imdbRating = 7.8f,
            tomatoesRating = 88.0
        )

        val segments = buildScreensaverPrimaryMetaSegments(slide)

        assertEquals(
            listOf(
                ScreensaverPrimaryMetaSegment.Rating(ScreensaverPrimaryMetaSegment.Rating.Kind.IMDB, "7.8"),
                ScreensaverPrimaryMetaSegment.Rating(ScreensaverPrimaryMetaSegment.Rating.Kind.TOMATOES, "88")
            ),
            segments
        )
    }

    @Test
    fun `screensaver motion configuration gives the pan time to complete with a noticeable zoom delta`() {
        assertTrue(SCREENSAVER_MOTION_DURATION_MS <= SCREENSAVER_SLIDE_ADVANCE_MS)
        assertTrue((SCREENSAVER_END_SCALE - SCREENSAVER_START_SCALE) >= 0.12f)
    }

    @Test
    fun `screensaverMotionFrame keeps top left anchored motion within right and bottom overscan`() {
        val viewportSize = IntSize(width = 1920, height = 1080)
        val scale = 1.12f
        val startFrame = screensaverMotionFrame(
            viewportSize = viewportSize,
            progress = 0f,
            motion = screensaverMotionPresetFor(0),
            scale = scale
        )
        val endFrame = screensaverMotionFrame(
            viewportSize = viewportSize,
            progress = 1f,
            motion = screensaverMotionPresetFor(0),
            scale = scale
        )

        val maxTranslationX = (viewportSize.width * scale) - viewportSize.width
        val maxTranslationY = (viewportSize.height * scale) - viewportSize.height

        assertNotEquals(startFrame.translationX, endFrame.translationX)
        assertNotEquals(startFrame.translationY, endFrame.translationY)
        assertTrue(startFrame.translationX <= 0f)
        assertTrue(startFrame.translationX >= -maxTranslationX)
        assertTrue(endFrame.translationX <= 0f)
        assertTrue(endFrame.translationX >= -maxTranslationX)
        assertTrue(startFrame.translationY <= 0f)
        assertTrue(startFrame.translationY >= -maxTranslationY)
        assertTrue(endFrame.translationY <= 0f)
        assertTrue(endFrame.translationY >= -maxTranslationY)
        assertTrue(kotlin.math.abs(endFrame.translationX - startFrame.translationX) >= maxTranslationX * 0.5f)
        assertTrue(kotlin.math.abs(endFrame.translationY - startFrame.translationY) >= maxTranslationY * 0.5f)
    }

    @Test
    fun `screensaverMotionFrame keeps bottom right anchored motion within left and top overscan`() {
        val viewportSize = IntSize(width = 1920, height = 1080)
        val scale = 1.12f
        val startFrame = screensaverMotionFrame(
            viewportSize = viewportSize,
            progress = 0f,
            motion = screensaverMotionPresetFor(1),
            scale = scale
        )
        val endFrame = screensaverMotionFrame(
            viewportSize = viewportSize,
            progress = 1f,
            motion = screensaverMotionPresetFor(1),
            scale = scale
        )

        val maxTranslationX = (viewportSize.width * scale) - viewportSize.width
        val maxTranslationY = (viewportSize.height * scale) - viewportSize.height

        assertNotEquals(startFrame.translationX, endFrame.translationX)
        assertNotEquals(startFrame.translationY, endFrame.translationY)
        assertTrue(startFrame.translationX >= 0f)
        assertTrue(startFrame.translationX <= maxTranslationX)
        assertTrue(endFrame.translationX >= 0f)
        assertTrue(endFrame.translationX <= maxTranslationX)
        assertTrue(startFrame.translationY >= 0f)
        assertTrue(startFrame.translationY <= maxTranslationY)
        assertTrue(endFrame.translationY >= 0f)
        assertTrue(endFrame.translationY <= maxTranslationY)
        assertTrue(kotlin.math.abs(endFrame.translationX - startFrame.translationX) >= maxTranslationX * 0.5f)
        assertTrue(kotlin.math.abs(endFrame.translationY - startFrame.translationY) >= maxTranslationY * 0.5f)
    }

    @Test
    fun `idle trailer remote key action opens details for ok keys`() {
        assertEquals(
            IdleTrailerRemoteKeyAction.OPEN_DETAILS,
            determineIdleTrailerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_DOWN,
                sessionMuted = true
            )
        )
    }

    @Test
    fun `idle trailer remote key action dismisses for back`() {
        assertEquals(
            IdleTrailerRemoteKeyAction.DISMISS,
            determineIdleTrailerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_BACK,
                action = KeyEvent.ACTION_DOWN,
                sessionMuted = true
            )
        )
    }

    @Test
    fun `idle trailer remote key action unmutes session once and keeps consuming later keys`() {
        assertEquals(
            IdleTrailerRemoteKeyAction.UNMUTE_SESSION,
            determineIdleTrailerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                action = KeyEvent.ACTION_DOWN,
                sessionMuted = true
            )
        )
        assertEquals(
            IdleTrailerRemoteKeyAction.CONSUME,
            determineIdleTrailerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                action = KeyEvent.ACTION_DOWN,
                sessionMuted = false
            )
        )
    }

    @Test
    fun `idle trailer branding presentation keeps logo and prompt visible together for twenty seconds and centers prompt`() {
        val spec = idleTrailerBrandingPresentationSpec()

        assertEquals(20_000L, spec.visibleMs)
        assertEquals(1_500, spec.fadeDurationMs)
        assertEquals(Alignment.CenterHorizontally, spec.contentAlignment)
        assertEquals(TextAlign.Center, spec.promptTextAlign)
    }

    @Test
    fun `idle trailer first frame timeout advances only once per failed playback key`() {
        assertTrue(
            shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout(
                hasRenderedFirstFrame = false,
                playbackKey = "movie:tt123:abc123def45",
                failedPlaybackKeys = emptySet()
            )
        )
        assertTrue(
            !shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout(
                hasRenderedFirstFrame = true,
                playbackKey = "movie:tt123:abc123def45",
                failedPlaybackKeys = emptySet()
            )
        )
        assertTrue(
            !shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout(
                hasRenderedFirstFrame = false,
                playbackKey = "movie:tt123:abc123def45",
                failedPlaybackKeys = setOf("movie:tt123:abc123def45")
            )
        )
    }

    private fun buildSlide(
        genres: List<String>,
        imdbRating: Float?,
        tomatoesRating: Double?
    ): IdleScreensaverSlide {
        return IdleScreensaverSlide(
            itemId = "tt123",
            itemType = "movie",
            addonBaseUrl = "https://api.example.com",
            title = "Example",
            backgroundArtwork = ArtworkDisplayRef.LegacyString(
                value = "https://image.example.com/background.jpg",
                imageType = ArtworkType.BACKDROP,
                trace = ArtworkTrace.empty()
            ),
            logoArtwork = null,
            genres = genres,
            description = null,
            releaseInfo = null,
            runtime = null,
            imdbRating = imdbRating,
            tomatoesRating = tomatoesRating
        )
    }
}
