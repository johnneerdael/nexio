package com.nexio.tv.ui.screens.player.ass

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    fun convertsMedia3MatroskaDialogueSampleToLibassChunk() {
        val native = FakeAssSsaNativeApi()
        val controller = newController(native)
        val format = Format.Builder().setLanguage("en").build()
        val sample = "Dialogue: 0:00:00:00,0:00:02:50,42,0,Default,,0,0,0,,{\\an5}Hello"

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
    fun matchesTrackByExactFormatIdBeforeSharedLanguage() {
        val native = FakeAssSsaNativeApi()
        val controller = newController(native)

        controller.onTrackHeader(
            trackId = 8,
            headerData = "[Script Info]".toByteArray(),
            Format.Builder().setId("subtitle-1").setLanguage("en").build()
        )
        controller.onTrackHeader(
            trackId = 9,
            headerData = "[Script Info]".toByteArray(),
            Format.Builder().setId("subtitle-2").setLanguage("en").build()
        )

        assertEquals(
            9,
            controller.findTrackIdByFormatForTesting(
                Format.Builder().setId("subtitle-2").setLanguage("en").build()
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
    fun timeRendererUpdatesControllerTimeWithoutNativeRender() {
        val native = FakeAssSsaNativeApi()
        val controller = newController(native)
        val renderer = AssSsaTimeRenderer(controller)

        controller.setVideoSize(1280, 720)

        renderer.render(positionUs = 7_000_000L, elapsedRealtimeUs = 1_000L)

        assertEquals(7_000_000L, controller.currentTimeUs)
        assertTrue(native.renders.isEmpty())
    }

    @Test
    fun setPlayerWithoutSelectedTrackDoesNotStartRenderLoop() {
        val native = FakeAssSsaNativeApi()
        val controller = newController(native)

        controller.setVideoSize(1280, 720)
        controller.setPlayer(mockk<ExoPlayer>(relaxed = true))

        assertFalse(controller.isRenderLoopScheduledForTesting())
    }

    @Test
    fun acceptsSamplesBeforeOverlayAttachesAndRendersAfterAttach() {
        val native = FakeAssSsaNativeApi()
        val controller = AssSsaRenderController(
            context = ApplicationProvider.getApplicationContext(),
            overlayView = null,
            subtitleDelayUsProvider = { 0L },
            native = native
        )
        val format = Format.Builder().setLanguage("en").build()

        controller.setVideoSize(1280, 720)
        controller.setPlayer(mockk<ExoPlayer>(relaxed = true))
        controller.onTrackHeader(trackId = 9, headerData = "[Script Info]".toByteArray(), format)
        controller.selectTrackByFormat(format)
        controller.onSubtitleSample(
            trackId = 9,
            timeUs = 1_000_000L,
            data = "Dialogue: 0:00:00.00,0:00:01.00,1,0,Default,,0,0,0,,Early".toByteArray()
        )

        assertFalse(controller.isRenderLoopScheduledForTesting())
        assertEquals(1, native.chunks.size)

        val overlay = newOverlay()
        controller.setOverlayView(overlay)
        controller.renderCurrentFrameForTesting()

        assertTrue(overlay.hasRenderedBitmapForTesting())
    }

    @Test
    fun selectedTrackStartsRenderLoopAndClearOverlayStopsIt() {
        val native = FakeAssSsaNativeApi()
        val controller = newController(native)

        controller.setVideoSize(1280, 720)
        controller.setPlayer(mockk<ExoPlayer>(relaxed = true))
        controller.onTrackHeader(
            trackId = 9,
            headerData = "[Script Info]".toByteArray(),
            Format.Builder().setLanguage("en").build()
        )

        assertTrue(controller.isRenderLoopScheduledForTesting())

        controller.clearOverlay()

        assertFalse(controller.isRenderLoopScheduledForTesting())
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
    fun releasePreventsLaterSampleCallbacksFromInvokingNative() {
        val native = FakeAssSsaNativeApi()
        val controller = newController(native)
        val format = Format.Builder().setLanguage("en").build()

        controller.setVideoSize(640, 360)
        controller.onTrackHeader(trackId = 11, headerData = "[Script Info]".toByteArray(), format)
        controller.selectTrackByFormat(format)
        controller.release()
        native.clearRecordedCalls()

        controller.onTrackHeader(trackId = 11, headerData = "[Script Info]".toByteArray(), format)
        controller.onFontAttachment("font.ttf", byteArrayOf(1, 2, 3))
        controller.onSubtitleSample(
            trackId = 11,
            timeUs = 1_000_000L,
            data = "Dialogue: 0:00:00.00,0:00:01.00,1,0,Default,,0,0,0,,Late".toByteArray()
        )
        controller.renderCurrentFrameForTesting()

        assertTrue(native.loadHeaderCalls.isEmpty())
        assertTrue(native.addFontCalls.isEmpty())
        assertTrue(native.chunks.isEmpty())
        assertTrue(native.renders.isEmpty())
        assertTrue(native.destroyedHandles.isEmpty())
    }

    @Test
    fun serializesSampleIngestionAgainstNativeRender() {
        val native = BlockingRenderNativeApi()
        val controller = newController(native)
        val format = Format.Builder().setLanguage("en").build()

        controller.setVideoSize(640, 360)
        controller.onTrackHeader(trackId = 12, headerData = "[Script Info]".toByteArray(), format)
        controller.selectTrackByFormat(format)
        controller.currentTimeUs = 1_000_000L

        val renderThread = Thread { controller.renderCurrentFrameForTesting() }
        renderThread.start()
        assertTrue(native.renderEntered.await(5, TimeUnit.SECONDS))

        val sampleThread = Thread {
            controller.onSubtitleSample(
                trackId = 12,
                timeUs = 1_000_000L,
                data = "Dialogue: 0:00:00.00,0:00:01.00,1,0,Default,,0,0,0,,During".toByteArray()
            )
        }
        sampleThread.start()
        Thread.sleep(50L)

        native.allowRenderToFinish.countDown()
        renderThread.join(5_000L)
        sampleThread.join(5_000L)

        assertFalse(renderThread.isAlive)
        assertFalse(sampleThread.isAlive)
        assertFalse(native.concurrentNativeAccess.get())
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

    @Test
    fun resetForNewStreamClearsPriorStreamSubtitleState() {
        val native = FakeAssSsaNativeApi()
        val overlay = newOverlay()
        val controller = AssSsaRenderController(
            context = ApplicationProvider.getApplicationContext(),
            overlayView = overlay,
            subtitleDelayUsProvider = { 0L },
            native = native
        )
        val oldFormat = Format.Builder().setLanguage("en").build()

        controller.setVideoSize(640, 360)
        controller.onTrackHeader(trackId = 21, headerData = "[Script Info]".toByteArray(), oldFormat)
        controller.onFontAttachment("old-font.ttf", byteArrayOf(1, 2, 3))
        controller.onSubtitleSample(
            trackId = 21,
            timeUs = 1_000_000L,
            data = "Dialogue: 0:00:00.00,0:00:01.00,1,0,Default,,0,0,0,,Old".toByteArray()
        )
        controller.renderCurrentFrameForTesting()
        assertTrue(controller.eventChunksForTesting().isNotEmpty())
        assertTrue(overlay.hasRenderedBitmapForTesting())

        controller.resetForNewStream()
        native.clearRecordedCalls()

        assertTrue(controller.eventChunksForTesting().isEmpty())
        assertEquals(null, controller.findTrackIdByFormatForTesting(oldFormat))
        assertFalse(overlay.hasRenderedBitmapForTesting())

        val newFormat = Format.Builder().setLanguage("ja").build()
        controller.onTrackHeader(trackId = 22, headerData = "[Script Info]".toByteArray(), newFormat)
        controller.selectTrackByFormat(newFormat)
        controller.renderCurrentFrameForTesting()

        assertTrue(native.chunks.isEmpty())
        assertTrue(native.addFontCalls.isEmpty())
        assertEquals(1, native.loadHeaderCalls.size)
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

    private open class FakeAssSsaNativeApi : AssSsaNativeApi {
        override val nativeAvailable: Boolean = true
        private var nextHandle = 1L
        val chunks = mutableListOf<Chunk>()
        val renders = mutableListOf<Render>()
        val flushedHandles = mutableListOf<Long>()
        val destroyedHandles = mutableListOf<Long>()
        val loadHeaderCalls = mutableListOf<Long>()
        val addFontCalls = mutableListOf<Long>()

        override fun configureFontconfig(context: Context): Boolean = true

        override fun init(width: Int, height: Int, fontScale: Float): Long {
            return nextHandle++
        }

        override fun loadHeader(handle: Long, headerData: ByteArray): Int {
            loadHeaderCalls += handle
            return 0
        }

        override fun addFont(handle: Long, name: String?, fontData: ByteArray) {
            addFontCalls += handle
        }

        open override fun processChunk(
            handle: Long,
            data: ByteArray,
            startMs: Long,
            durationMs: Long
        ) {
            chunks += Chunk(data, startMs, durationMs)
        }

        override fun processData(handle: Long, data: ByteArray) = Unit

        open override fun render(handle: Long, timeMs: Long, bitmap: Bitmap): Boolean {
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

        fun clearRecordedCalls() {
            chunks.clear()
            renders.clear()
            flushedHandles.clear()
            destroyedHandles.clear()
            loadHeaderCalls.clear()
            addFontCalls.clear()
        }
    }

    private data class Chunk(val data: ByteArray, val startMs: Long, val durationMs: Long)
    private data class Render(val handle: Long, val timeMs: Long)

    private class BlockingRenderNativeApi : FakeAssSsaNativeApi() {
        val renderEntered = CountDownLatch(1)
        val allowRenderToFinish = CountDownLatch(1)
        val concurrentNativeAccess = AtomicBoolean(false)
        private val insideNative = AtomicBoolean(false)

        override fun render(handle: Long, timeMs: Long, bitmap: Bitmap): Boolean {
            if (!insideNative.compareAndSet(false, true)) {
                concurrentNativeAccess.set(true)
            }
            try {
                renderEntered.countDown()
                assertTrue(allowRenderToFinish.await(5, TimeUnit.SECONDS))
                return super.render(handle, timeMs, bitmap)
            } finally {
                insideNative.set(false)
            }
        }

        override fun processChunk(
            handle: Long,
            data: ByteArray,
            startMs: Long,
            durationMs: Long
        ) {
            if (!insideNative.compareAndSet(false, true)) {
                concurrentNativeAccess.set(true)
            }
            try {
                super.processChunk(handle, data, startMs, durationMs)
            } finally {
                insideNative.set(false)
            }
        }
    }
}
