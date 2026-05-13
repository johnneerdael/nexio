package com.nexio.tv.ui.screens.player.ass

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

internal interface AssSsaNativeApi {
    val nativeAvailable: Boolean
    fun configureFontconfig(context: Context): Boolean
    fun init(width: Int, height: Int, fontScale: Float): Long
    fun loadHeader(handle: Long, headerData: ByteArray): Int
    fun addFont(handle: Long, name: String?, fontData: ByteArray)
    fun processChunk(handle: Long, data: ByteArray, startMs: Long, durationMs: Long)
    fun processData(handle: Long, data: ByteArray)
    fun render(handle: Long, timeMs: Long, bitmap: Bitmap): Boolean
    fun flush(handle: Long)
    fun destroy(handle: Long)
}

internal object JniAssSsaNativeApi : AssSsaNativeApi {
    override val nativeAvailable: Boolean
        get() = AssSsaNativeBridge.nativeAvailable

    override fun configureFontconfig(context: Context): Boolean {
        return AssSsaNativeBridge.configureFontconfig(context)
    }

    override fun init(width: Int, height: Int, fontScale: Float): Long {
        return AssSsaNativeBridge.nativeInit(width, height, fontScale)
    }

    override fun loadHeader(handle: Long, headerData: ByteArray): Int {
        return AssSsaNativeBridge.nativeLoadHeader(handle, headerData)
    }

    override fun addFont(handle: Long, name: String?, fontData: ByteArray) {
        AssSsaNativeBridge.nativeAddFont(handle, name, fontData)
    }

    override fun processChunk(handle: Long, data: ByteArray, startMs: Long, durationMs: Long) {
        AssSsaNativeBridge.nativeProcessChunk(handle, data, startMs, durationMs)
    }

    override fun processData(handle: Long, data: ByteArray) {
        AssSsaNativeBridge.nativeProcessData(handle, data)
    }

    override fun render(handle: Long, timeMs: Long, bitmap: Bitmap): Boolean {
        return AssSsaNativeBridge.nativeRender(handle, timeMs, bitmap)
    }

    override fun flush(handle: Long) {
        AssSsaNativeBridge.nativeFlush(handle)
    }

    override fun destroy(handle: Long) {
        AssSsaNativeBridge.nativeDestroy(handle)
    }
}

internal data class AssSsaEventChunk(
    val trackId: Int,
    val startMs: Long,
    val durationMs: Long,
    val chunkData: ByteArray
)

internal class AssSsaRenderController(
    private val context: Context,
    overlayView: AssSsaRenderOverlayView?,
    private val subtitleDelayUsProvider: () -> Long,
    private val native: AssSsaNativeApi = JniAssSsaNativeApi
) : AssSsaSampleSink {
    @Volatile var currentTimeUs: Long = 0L

    // Guards all mutable controller state and calls using the native ASS handle. Media3 extractor
    // callbacks arrive on the loading thread while rendering runs on the UI thread.
    private val stateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracks = linkedMapOf<Int, TrackState>()
    private val eventChunks = mutableListOf<AssSsaEventChunk>()
    private val rawSamples = mutableListOf<RawSample>()
    private val fontAttachments = mutableListOf<FontAttachment>()
    private var overlayView: AssSsaRenderOverlayView? = overlayView
    private var player: ExoPlayer? = null
    private var selectedTrackId: Int? = null
    private var loadedTrackId: Int? = null
    private var handle = 0L
    private var renderingEnabled = true
    private var renderBitmap: Bitmap? = null
    private var renderWidth = 0
    private var renderHeight = 0
    private var handleWidth = 0
    private var handleHeight = 0
    private var fontconfigConfigured = false
    private var released = false
    @Volatile private var renderLoopScheduled = false

    private val renderRunnable = object : Runnable {
        override fun run() {
            val shouldRender = synchronized(stateLock) {
                renderLoopScheduled = false
                canRunRenderLoop()
            }
            if (!shouldRender) return
            renderCurrentFrame()
            startRenderLoopIfNeeded()
        }
    }

    fun setOverlayView(overlayView: AssSsaRenderOverlayView?) {
        val previousOverlay = synchronized(stateLock) {
            if (released || this.overlayView === overlayView) return
            val previous = this.overlayView
            this.overlayView = overlayView
            previous
        }
        runOnMainThread {
            previousOverlay?.removeCallbacks(renderRunnable)
            if (overlayView == null || previousOverlay !== overlayView) {
                previousOverlay?.clearOverlay()
            }
        }
        startRenderLoopIfNeeded()
    }

    fun setPlayer(player: ExoPlayer?) {
        synchronized(stateLock) {
            this.player = player
        }
        if (player == null) {
            stopRenderLoop()
        } else {
            startRenderLoopIfNeeded()
        }
    }

    fun setVideoSize(width: Int, height: Int) {
        synchronized(stateLock) {
            if (width <= 0 || height <= 0) return
            if (renderWidth == width && renderHeight == height) return

            renderWidth = width
            renderHeight = height
            renderBitmap?.recycle()
            renderBitmap = null
            destroyNativeHandle()
            ensureNativeInitialized(replayEvents = true)
        }
        startRenderLoopIfNeeded()
    }

    fun onSeekStarted() {
        clearOverlay()
        synchronized(stateLock) {
            val activeHandle = handle
            if (activeHandle == 0L) return

            native.flush(activeHandle)
            loadedTrackId = null
            loadSelectedTrackHeader(activeHandle)
            replayActiveTrackEvents(activeHandle)
            replayActiveRawSamples(activeHandle)
        }
        startRenderLoopIfNeeded()
    }

    fun selectTrackByFormat(format: Format) {
        synchronized(stateLock) {
            renderingEnabled = true
            val trackId = findTrackIdByFormat(format) ?: return
            if (selectedTrackId == trackId) {
                startRenderLoopIfNeeded()
                return
            }

            selectedTrackId = trackId
            loadedTrackId = null
            stopRenderLoop()
            clearOverlayView()
            val activeHandle = handle
            if (activeHandle != 0L) {
                native.flush(activeHandle)
                loadSelectedTrackHeader(activeHandle)
                replayActiveTrackEvents(activeHandle)
                replayActiveRawSamples(activeHandle)
            } else {
                ensureNativeInitialized(replayEvents = true)
            }
        }
        startRenderLoopIfNeeded()
    }

    fun disableRendering() {
        stopRenderLoop()
        synchronized(stateLock) {
            if (released) return
            renderingEnabled = false
            val activeHandle = handle
            if (activeHandle != 0L) {
                native.flush(activeHandle)
            }
            selectedTrackId = null
            loadedTrackId = null
            eventChunks.clear()
            rawSamples.clear()
        }
        clearOverlayView()
    }

    fun clearOverlay() {
        stopRenderLoop()
        clearOverlayView()
    }

    fun resetForNewStream() {
        synchronized(stateLock) {
            if (released) return
            stopRenderLoop()
            destroyNativeHandle()
            tracks.clear()
            eventChunks.clear()
            rawSamples.clear()
            fontAttachments.clear()
            selectedTrackId = null
            loadedTrackId = null
            renderingEnabled = true
            currentTimeUs = 0L
        }
        clearOverlay()
    }

    fun release() {
        synchronized(stateLock) {
            if (released) return
            released = true
            stopRenderLoop()
            player = null
            destroyNativeHandle()
            renderBitmap?.recycle()
            renderBitmap = null
            tracks.clear()
            eventChunks.clear()
            rawSamples.clear()
            fontAttachments.clear()
            selectedTrackId = null
            loadedTrackId = null
            renderingEnabled = false
        }
        clearOverlay()
    }

    override fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) {
        synchronized(stateLock) {
            if (released) return
            tracks[trackId] = TrackState(trackId, headerData, format)
            if (selectedTrackId == trackId && handle != 0L) {
                loadedTrackId = null
                loadSelectedTrackHeader(handle)
            }
        }
    }

    override fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray) {
        val chunk = data.decodeToString()
            .lineSequence()
            .mapNotNull { line -> line.toAssSsaEventChunk(trackId, timeUs) }
            .firstOrNull()

        synchronized(stateLock) {
            if (released) return
            if (!renderingEnabled) return

            if (chunk != null) {
                eventChunks += chunk
                if (trackId == selectedTrackId && ensureNativeInitialized(replayEvents = false)) {
                    native.processChunk(handle, chunk.chunkData, chunk.startMs, chunk.durationMs)
                }
                startRenderLoopIfNeeded()
                return
            }

            val rawSample = RawSample(trackId, data)
            rawSamples += rawSample
            if (trackId == selectedTrackId && ensureNativeInitialized(replayEvents = false)) {
                native.processData(handle, rawSample.data)
            }
            startRenderLoopIfNeeded()
        }
    }

    override fun onFontAttachment(name: String, data: ByteArray) {
        synchronized(stateLock) {
            if (released) return
            val attachment = FontAttachment(name, data)
            fontAttachments += attachment
            if (handle != 0L) {
                native.addFont(handle, attachment.name, attachment.data)
            }
        }
    }

    internal fun eventChunksForTesting(): List<AssSsaEventChunk> = synchronized(stateLock) {
        eventChunks.toList()
    }

    internal fun findTrackIdByFormatForTesting(format: Format): Int? {
        return synchronized(stateLock) {
            findTrackIdByFormat(format)
        }
    }

    internal fun isRenderLoopScheduledForTesting(): Boolean = synchronized(stateLock) {
        renderLoopScheduled
    }

    // Test hook; production rendering is scheduled through renderRunnable on the main thread.
    internal fun renderCurrentFrameForTesting() {
        renderCurrentFrame()
    }

    private fun renderCurrentFrame() {
        synchronized(stateLock) {
            val overlay = overlayView ?: return
            if (!ensureNativeInitialized(replayEvents = true)) {
                clearOverlay()
                return
            }

            val bitmap = renderBitmapForCurrentSize()
            val adjustedPositionMs = (
                (player?.currentPosition ?: (currentTimeUs / 1000L)) -
                    (subtitleDelayUsProvider() / 1000L)
                ).coerceAtLeast(0L)
            if (native.render(handle, adjustedPositionMs, bitmap)) {
                overlay.setRenderedBitmap(bitmap)
            } else {
                clearOverlayView()
            }
        }
    }

    private fun startRenderLoopIfNeeded() {
        synchronized(stateLock) {
            if (!canRunRenderLoop() || renderLoopScheduled) return
            renderLoopScheduled = true
        }
        runOnMainThread {
            val overlay = synchronized(stateLock) {
                if (!canRunRenderLoop() || !renderLoopScheduled) {
                    renderLoopScheduled = false
                    return@runOnMainThread
                }
                overlayView
            }
            overlay?.postOnAnimation(renderRunnable)
        }
    }

    private fun stopRenderLoop() {
        val overlay = synchronized(stateLock) {
            renderLoopScheduled = false
            overlayView
        }
        runOnMainThread {
            overlay?.removeCallbacks(renderRunnable)
        }
    }

    private fun canRunRenderLoop(): Boolean = synchronized(stateLock) {
        val trackId = selectedTrackId
        !released &&
            renderingEnabled &&
            overlayView != null &&
            player != null &&
            trackId != null &&
            tracks.containsKey(trackId) &&
            renderWidth > 0 &&
            renderHeight > 0 &&
            native.nativeAvailable
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun clearOverlayView() {
        val overlay = synchronized(stateLock) {
            overlayView
        }
        runOnMainThread {
            overlay?.clearOverlay()
        }
    }

    private fun ensureNativeInitialized(replayEvents: Boolean): Boolean {
        if (released || renderWidth <= 0 || renderHeight <= 0 || !native.nativeAvailable) {
            return false
        }

        if (handle != 0L && handleWidth == renderWidth && handleHeight == renderHeight) {
            loadSelectedTrackHeader(handle)
            return true
        }

        destroyNativeHandle()
        if (!fontconfigConfigured) {
            native.configureFontconfig(context.applicationContext)
            fontconfigConfigured = true
        }

        val nextHandle = native.init(renderWidth, renderHeight, DEFAULT_FONT_SCALE)
        if (nextHandle == 0L) return false

        handle = nextHandle
        handleWidth = renderWidth
        handleHeight = renderHeight
        fontAttachments.forEach { attachment ->
            native.addFont(nextHandle, attachment.name, attachment.data)
        }
        loadSelectedTrackHeader(nextHandle)
        if (replayEvents) {
            replayActiveTrackEvents(nextHandle)
            replayActiveRawSamples(nextHandle)
        }
        return true
    }

    private fun loadSelectedTrackHeader(activeHandle: Long) {
        val trackId = selectedTrackId ?: return
        if (loadedTrackId == trackId) return
        val track = tracks[trackId] ?: return
        native.loadHeader(activeHandle, track.headerData)
        loadedTrackId = trackId
    }

    private fun replayActiveTrackEvents(activeHandle: Long) {
        val trackId = selectedTrackId ?: return
        eventChunks.asSequence()
            .filter { it.trackId == trackId }
            .forEach { event ->
                native.processChunk(activeHandle, event.chunkData, event.startMs, event.durationMs)
            }
    }

    private fun replayActiveRawSamples(activeHandle: Long) {
        val trackId = selectedTrackId ?: return
        rawSamples.asSequence()
            .filter { it.trackId == trackId }
            .forEach { sample -> native.processData(activeHandle, sample.data) }
    }

    private fun findTrackIdByFormat(format: Format): Int? {
        val id = format.id
        if (!id.isNullOrBlank()) {
            tracks.values.firstOrNull { it.format.id == id }?.let { return it.trackId }
        }

        val label = format.label.normalizedLabel()
        if (label != null) {
            tracks.values
                .filter { it.format.label.normalizedLabel() == label }
                .singleOrNull()
                ?.let { return it.trackId }
        }

        val language = format.language.normalizedLanguage()
        if (language != null) {
            tracks.values
                .filter { it.format.language.normalizedLanguage() == language }
                .singleOrNull()
                ?.let { return it.trackId }
        }

        return tracks.values.singleOrNull()?.trackId
    }

    private fun renderBitmapForCurrentSize(): Bitmap {
        val existing = renderBitmap
        if (existing != null && existing.width == renderWidth && existing.height == renderHeight) {
            return existing
        }
        return Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888).also {
            renderBitmap?.recycle()
            renderBitmap = it
        }
    }

    private fun destroyNativeHandle() {
        val activeHandle = handle
        if (activeHandle != 0L) {
            native.destroy(activeHandle)
        }
        handle = 0L
        handleWidth = 0
        handleHeight = 0
        loadedTrackId = null
    }

    private data class TrackState(
        val trackId: Int,
        val headerData: ByteArray,
        val format: Format
    )

    private data class FontAttachment(val name: String, val data: ByteArray)
    private data class RawSample(val trackId: Int, val data: ByteArray)

    companion object {
        private const val DEFAULT_FONT_SCALE = 1f
    }
}

private fun String.toAssSsaEventChunk(trackId: Int, timeUs: Long): AssSsaEventChunk? {
    val trimmed = trimStart()
    if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) return null

    val content = trimmed.substringAfter(":").trimStart()
    val parts = content.split(",", limit = 11)
    if (parts.size < 11) return null

    val durationMs = parseAssTimeMs(parts[1].trim()) ?: return null
    val startMs = timeUs / 1000L
    val chunkData = (
        "${parts[2].trim()},${parts[3].trim()},${parts[4].trim()}," +
            "${parts[5].trim()},${parts[6].trim()},${parts[7].trim()}," +
            "${parts[8].trim()},${parts[9].trim()},${parts[10]}"
        ).toByteArray()
    return AssSsaEventChunk(trackId, startMs, durationMs, chunkData)
}

private fun parseAssTimeMs(value: String): Long? {
    val timeParts = value.split(":")
    if (timeParts.size != 3 && timeParts.size != 4) return null
    val hours = timeParts[0].toLongOrNull() ?: return null
    val minutes = timeParts[1].toLongOrNull() ?: return null
    val seconds: Long
    val millis: Long
    if (timeParts.size == 4) {
        seconds = timeParts[2].toLongOrNull() ?: return null
        val centiseconds = timeParts[3].toLongOrNull() ?: return null
        millis = centiseconds * 10L
    } else {
        val secondsParts = timeParts[2].split(".", limit = 2)
        seconds = secondsParts[0].toLongOrNull() ?: return null
        millis = secondsParts.getOrNull(1)
            ?.padEnd(3, '0')
            ?.take(3)
            ?.toLongOrNull()
            ?: 0L
    }

    return hours * 3_600_000L + minutes * 60_000L + seconds * 1000L + millis
}

private fun String?.normalizedLabel(): String? {
    return this?.trim()?.takeIf { it.isNotEmpty() }
}

private fun String?.normalizedLanguage(): String? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return value.lowercase(Locale.US)
}
