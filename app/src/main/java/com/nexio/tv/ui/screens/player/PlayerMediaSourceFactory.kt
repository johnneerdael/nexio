@file:Suppress("DEPRECATION")

package com.nexio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.nexio.tv.data.local.StreamingCacheDebugMode
import java.net.URLDecoder
import java.util.Locale
import okhttp3.OkHttpClient

internal class PlayerMediaSourceFactory(
    private val context: Context,
    private val playbackOkHttpClient: OkHttpClient,
    private val streamingCacheProvider: StreamingCacheProvider = StreamingCacheProvider(context),
) {
    var streamingCacheEnabled: Boolean = false
    var streamingCacheDebugMode: StreamingCacheDebugMode =
        StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL

    private var customExtractorsFactory: ExtractorsFactory? = null
    private var customSubtitleParserFactory: SubtitleParser.Factory? = null
    private val loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy()
    private val cacheKeyFactory = StableCacheKeyFactory()
    private val rangeCoordinator = StreamingRangeCoordinator()
    private val missCoordinator = StreamingCacheMissCoordinator(rangeCoordinator)
    private val streamingBandwidthMonitor = BandwidthMonitor()
    internal val memoryBudget = MemoryBudget(context)
    private val memoryPressureMonitor = StreamingCacheMemoryPressureMonitor(
        context = context,
        onMemoryPressure = ::onStreamingCacheMemoryWarning
    )
    private var fillSession: StreamingCacheFillSession? = null

    @Volatile
    private var streamingCacheStartup = true

    @VisibleForTesting
    internal val hasActiveFillSession: Boolean
        get() = fillSession?.isActive == true

    @VisibleForTesting
    internal val isStreamingCacheStartupForTesting: Boolean
        get() = streamingCacheStartup

    init {
        memoryPressureMonitor.start()
    }

    fun configureSubtitleParsing(
        extractorsFactory: ExtractorsFactory?,
        subtitleParserFactory: SubtitleParser.Factory?
    ) {
        customExtractorsFactory = extractorsFactory
        customSubtitleParserFactory = subtitleParserFactory
    }

    fun createMediaSource(
        url: String,
        headers: Map<String, String>,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList()
    ): MediaSource {
        val sanitizedHeaders = sanitizeHeaders(headers)
        val useStreamingCache = streamingCacheEnabled && usesHttpUpstream(url)
        val dataSourceFactory = if (useStreamingCache) {
            PlayerPlaybackNetworking.createDataSourceFactory(
                context = context,
                client = playbackOkHttpClient,
                defaultHeaders = sanitizedHeaders,
                streamingCacheProvider = streamingCacheProvider,
                useStreamingCache = true,
                streamingCacheDebugMode = streamingCacheDebugMode,
                cacheKeyFactory = cacheKeyFactory,
                missCoordinator = missCoordinator,
                isStartupProvider = { streamingCacheStartup }
            )
        } else {
            PlayerPlaybackNetworking.createDataSourceFactory(
                context = context,
                client = playbackOkHttpClient,
                defaultHeaders = sanitizedHeaders,
                streamingCacheProvider = streamingCacheProvider,
                useStreamingCache = false
            )
        }
        if (useStreamingCache) {
            streamingCacheStartup = true
        }
        val resolvedMimeType = inferMimeType(
            url = url,
            filename = url.substringBefore('?').substringAfterLast('/', "").takeIf { it.isNotBlank() },
            responseHeaders = null
        )
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        resolvedMimeType?.let(mediaItemBuilder::setMimeType)
        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }

        val mediaItem = mediaItemBuilder.build()
        val extractorsFactory = customExtractorsFactory ?: createDefaultExtractorsFactory()
        val defaultFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory).apply {
            setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            customSubtitleParserFactory?.let(::setSubtitleParserFactory)
        }
        if (subtitleConfigurations.isNotEmpty()) {
            return defaultFactory.createMediaSource(mediaItem)
        }

        return when (resolvedMimeType) {
            MimeTypes.APPLICATION_M3U8 -> HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
            MimeTypes.APPLICATION_MPD -> DashMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
            else -> defaultFactory.createMediaSource(mediaItem)
        }
    }

    fun startStreamingCacheFill(
        url: String,
        headers: Map<String, String>,
        contentLength: Long?,
        playbackByteProvider: () -> Long
    ) {
        if (
            !streamingCacheEnabled ||
            !usesHttpUpstream(url) ||
            contentLength == null ||
            contentLength <= 0L
        ) {
            Log.d(
                TAG,
                "STREAM_CACHE_FILL skip enabled=$streamingCacheEnabled " +
                    "http=${usesHttpUpstream(url)} contentLength=$contentLength"
            )
            stopStreamingCacheFill()
            return
        }
        if (streamingCacheDebugMode == StreamingCacheDebugMode.COVERAGE_ONLY) {
            Log.d(TAG, "STREAM_CACHE_FILL skip mode=$streamingCacheDebugMode")
            stopStreamingCacheFill()
            return
        }

        val resolvedMimeType = inferMimeType(
            url = url,
            filename = url.substringBefore('?').substringAfterLast('/', "").takeIf { it.isNotBlank() },
            responseHeaders = null
        )
        if (resolvedMimeType == MimeTypes.APPLICATION_M3U8 || resolvedMimeType == MimeTypes.APPLICATION_MPD) {
            Log.d(TAG, "STREAM_CACHE_FILL skip adaptive mime=$resolvedMimeType")
            stopStreamingCacheFill()
            return
        }

        val session = fillSession ?: StreamingCacheFillSession(
            cache = streamingCacheProvider.getOrCreateCache(),
            cacheKeyFactory = cacheKeyFactory,
            okHttpClient = playbackOkHttpClient,
            memoryBudget = memoryBudget,
            rangeCoordinator = missCoordinator,
            missCoordinator = missCoordinator,
            bandwidthMonitor = streamingBandwidthMonitor,
            profile = ProviderProfile.forMemoryBudget(memoryBudget)
        ).also { fillSession = it }

        session.start(
            url = url,
            headers = sanitizeHeaders(headers),
            contentLength = contentLength,
            playbackByteProvider = playbackByteProvider
        )
        Log.d(TAG, "STREAM_CACHE_FILL start contentLength=$contentLength mime=$resolvedMimeType")
    }

    fun onStreamingCacheFirstFrameRendered() {
        streamingCacheStartup = false
    }

    fun onStreamingCacheMemoryWarning() {
        fillSession?.onMemoryWarning()
    }

    fun stopStreamingCacheFill(): Boolean {
        val session = fillSession ?: return true
        val stopped = session.stop()
        if (stopped) {
            fillSession = null
        }
        return stopped
    }

    fun shutdown() {
        memoryPressureMonitor.stop()
        if (stopStreamingCacheFill()) {
            streamingCacheProvider.release()
        }
    }

    private fun createDefaultExtractorsFactory(): ExtractorsFactory {
        return DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
    }

    companion object {
        private const val TAG = "PlayerMediaSourceFactory"

        fun sanitizeHeaders(headers: Map<String, String>?): Map<String, String> {
            val raw: Map<*, *> = headers ?: return emptyMap()
            if (raw.isEmpty()) return emptyMap()

            val sanitized = LinkedHashMap<String, String>(raw.size)
            raw.forEach { (rawKey, rawValue) ->
                val key = (rawKey as? String)?.trim().orEmpty()
                val value = (rawValue as? String)?.trim().orEmpty()
                if (key.isEmpty() || value.isEmpty()) return@forEach
                if (key.equals("Range", ignoreCase = true)) return@forEach
                sanitized[key] = value
            }
            return sanitized
        }

        fun parseHeaders(headers: String?): Map<String, String> {
            if (headers.isNullOrEmpty()) return emptyMap()

            return try {
                val parsed = headers.split("&").associate { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) {
                        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                    } else {
                        "" to ""
                    }
                }.filterKeys { it.isNotEmpty() }
                sanitizeHeaders(parsed)
            } catch (_: Exception) {
                emptyMap()
            }
        }

        internal fun usesHttpUpstream(url: String): Boolean {
            val normalized = url.trim().lowercase(Locale.US)
            return normalized.startsWith("http://") || normalized.startsWith("https://")
        }

        internal fun inferMimeType(
            url: String,
            filename: String?,
            responseHeaders: Map<String, String>?
        ): String? {
            return inferMimeTypeFromResponseHeaders(responseHeaders)
                ?: inferMimeTypeFromPath(filename)
                ?: inferMimeTypeFromPath(url)
        }

        internal fun normalizeMimeType(contentType: String?): String? {
            val normalized = contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.US)
                ?: return null

            return when (normalized) {
                "application/vnd.apple.mpegurl",
                "application/x-mpegurl",
                "audio/mpegurl",
                "audio/x-mpegurl" -> MimeTypes.APPLICATION_M3U8
                "application/dash+xml" -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }

        private fun inferMimeTypeFromResponseHeaders(headers: Map<String, String>?): String? {
            if (headers.isNullOrEmpty()) return null
            val contentType = headers.entries.firstOrNull { (key, _) ->
                key.equals("Content-Type", ignoreCase = true)
            }?.value
            return normalizeMimeType(contentType)
        }

        private fun inferMimeTypeFromPath(path: String?): String? {
            val normalized = path
                ?.substringBefore('#')
                ?.substringBefore('?')
                ?.lowercase(Locale.US)
                ?.trim()
                ?: return null

            return when {
                normalized.endsWith(".m3u8") ||
                    normalized.contains("/playlist") ||
                    normalized.contains("/hls") ||
                    normalized.contains("m3u8") -> MimeTypes.APPLICATION_M3U8
                normalized.endsWith(".mpd") ||
                    normalized.contains("/dash") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }
    }
}
