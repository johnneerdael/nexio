package com.nexio.tv.ui.screens.player.ass

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.Format
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AssSsaRenderControllerTest {
    @Test
    fun convertsMkvDialogueSampleToLibassChunk() {
        val native = FakeAssSsaNativeApi()
        val controller = newController(native)
        val format = Format.Builder().setLanguage("en").build()
        val sample = "Dialogue: 0:00:00.00,0:00:02.50,42,0,Default,,0,0,0,,{\\an5}Hello"

        controller.setVideoSize(1920, 1080)
        controller.onTrackHeader(trackId = 3, headerData = "[Script Info]".toByteArray(), format)
        controller.selectTrackByFormat(format)
        controller.onSubtitleSample(trackId = 3, timeUs = 1_000_000L, sample.toByteArray())

        val event = controller.eventChunksForTesting().single()
        assertEquals(3, event.trackId)
        assertEquals(1000L, event.startMs)
        assertEquals(2500L, event.durationMs)
        assertArrayEquals("42,0,Default,,0,0,0,,{\\an5}Hello".toByteArray(), event.chunkData)

        val nativeChunk = native.chunks.single()
        assertEquals(1000L, nativeChunk.startMs)
        assertEquals(2500L, nativeChunk.durationMs)
        assertArrayEquals("42,0,Default,,0,0,0,,{\\an5}Hello".toByteArray(), nativeChunk.data)
    }

    @Test
    fun matchesTrackByFormatLanguage() {
        val native = FakeAssSsaNativeApi()
        val controller = newController(native)

        controller.onTrackHeader(
            trackId = 8,
            headerData = "[Script Info]".toByteArray(),
            Format.Builder().setLanguage("ja").build()
        )

        assertEquals(
            8,
            controller.findTrackIdByFormatForTesting(
                Format.Builder().setLanguage("ja").build()
            )
        )
    }

    @Test
    fun appliesSubtitleDelayToRenderTime() {
        val native = FakeAssSsaNativeApi()
        val controller = newController(native, subtitleDelayUs = 1_500_000L)
        val format = Format.Builder().setLanguage("en").build()

        controller.currentTimeUs = 5_000_000L
        controller.setVideoSize(1280, 720)
        controller.onTrackHeader(trackId = 4, headerData = "[Script Info]".toByteArray(), format)
        controller.selectTrackByFormat(format)
        controller.renderCurrentFrameForTesting()

        assertEquals(3500L, native.renders.single().timeMs)
    }

    @Test
    fun releaseDestroysNativeHandleAndClearsOverlay() {
        val native = FakeAssSsaNativeApi()
        val overlay = newOverlay()
        val controller = AssSsaRenderController(
            context = ApplicationProvider.getApplicationContext(),
            overlayView = overlay,
            subtitleDelayUsProvider = { 0L },
            native = native
        )

        controller.setVideoSize(640, 360)
        controller.renderCurrentFrameForTesting()
        assertTrue(overlay.hasRenderedBitmapForTesting())

        controller.release()

        assertEquals(listOf(1L), native.destroyedHandles)
        assertFalse(overlay.hasRenderedBitmapForTesting())
    }

    @Test
    fun onSeekStartedFlushesAndClearsOverlay() {
        val native = FakeAssSsaNativeApi()
        val overlay = newOverlay()
        val controller = AssSsaRenderController(
            context = ApplicationProvider.getApplicationContext(),
            overlayView = overlay,
            subtitleDelayUsProvider = { 0L },
            native = native
        )

        controller.setVideoSize(640, 360)
        controller.renderCurrentFrameForTesting()
        assertTrue(overlay.hasRenderedBitmapForTesting())

        controller.onSeekStarted()

        assertEquals(listOf(1L), native.flushedHandles)
        assertFalse(overlay.hasRenderedBitmapForTesting())
    }

    private fun newController(
        native: FakeAssSsaNativeApi,
        subtitleDelayUs: Long = 0L
    ): AssSsaRenderController {
        return AssSsaRenderController(
            context = ApplicationProvider.getApplicationContext(),
            overlayView = newOverlay(),
            subtitleDelayUsProvider = { subtitleDelayUs },
            native = native
        )
    }

    private fun newOverlay(): AssSsaRenderOverlayView {
        return AssSsaRenderOverlayView(ApplicationProvider.getApplicationContext<Context>())
    }

    private class FakeAssSsaNativeApi : AssSsaNativeApi {
        override val nativeAvailable: Boolean = true
        private var nextHandle = 1L
        val chunks = mutableListOf<Chunk>()
        val renders = mutableListOf<Render>()
        val flushedHandles = mutableListOf<Long>()
        val destroyedHandles = mutableListOf<Long>()

        override fun configureFontconfig(context: Context): Boolean = true

        override fun init(width: Int, height: Int, fontScale: Float): Long {
            return nextHandle++
        }

        override fun loadHeader(handle: Long, headerData: ByteArray): Int = 0

        override fun addFont(handle: Long, name: String?, fontData: ByteArray) = Unit

        override fun processChunk(
            handle: Long,
            data: ByteArray,
            startMs: Long,
            durationMs: Long
        ) {
            chunks += Chunk(data, startMs, durationMs)
        }

        override fun processData(handle: Long, data: ByteArray) = Unit

        override fun render(handle: Long, timeMs: Long, bitmap: Bitmap): Boolean {
            renders += Render(handle, timeMs)
            bitmap.eraseColor(0x55FFFFFF)
            return true
        }

        override fun flush(handle: Long) {
            flushedHandles += handle
        }

        override fun destroy(handle: Long) {
            destroyedHandles += handle
        }
    }

    private data class Chunk(val data: ByteArray, val startMs: Long, val durationMs: Long)
    private data class Render(val handle: Long, val timeMs: Long)
}
