@file:Suppress("DEPRECATION")

package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
import com.nexio.tv.data.local.PlayerSettings
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient

internal class PlayerMediaSourceFactory(
    private val context: Context,
    private val playbackOkHttpClient: OkHttpClient,
) {
    private var customExtractorsFactory: ExtractorsFactory? = null
    private var customSubtitleParserFactory: SubtitleParser.Factory? = null
    private val loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy()
    @Volatile private var currentVodCacheResolvedUrl: String? = null
    private val parallelStartupPrefetchUnlocked = AtomicBoolean(true)
    private val activeReadBytePosition = AtomicLong(0L)
    var useParallelConnections: Boolean = PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS
    var parallelConnectionCount: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT
    var parallelChunkSizeMb: Int = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB

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
        val okHttpFactory = createOkHttpDataSourceFactory(sanitizedHeaders)
        val baseDataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)
        val resolvedMimeType = inferMimeType(
            url = url,
            filename = url.substringBefore('?').substringAfterLast('/', "").takeIf { it.isNotBlank() },
            responseHeaders = null
        )
        val isHls = resolvedMimeType == MimeTypes.APPLICATION_M3U8
        val isDash = resolvedMimeType == MimeTypes.APPLICATION_MPD
        currentVodCacheResolvedUrl = null
        val progressiveUpstreamFactory = selectProgressiveUpstreamFactory(
            url = url,
            isHls = isHls,
            isDash = isDash,
            okHttpFactory = okHttpFactory,
            baseDataSourceFactory = baseDataSourceFactory
        )
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        resolvedMimeType?.let(mediaItemBuilder::setMimeType)
        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }

        val mediaItem = mediaItemBuilder.build()
        val extractorsFactory = customExtractorsFactory ?: createDefaultExtractorsFactory()
        val defaultFactory = DefaultMediaSourceFactory(progressiveUpstreamFactory, extractorsFactory).apply {
            setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            customSubtitleParserFactory?.let(::setSubtitleParserFactory)
        }
        if (subtitleConfigurations.isNotEmpty()) {
            return defaultFactory.createMediaSource(mediaItem)
        }

        return when (resolvedMimeType) {
            MimeTypes.APPLICATION_M3U8 -> HlsMediaSource.Factory(okHttpFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
            MimeTypes.APPLICATION_MPD -> DashMediaSource.Factory(okHttpFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
            else -> defaultFactory.createMediaSource(mediaItem)
        }
    }

    internal fun progressiveUpstreamFactoryForTesting(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): DataSource.Factory {
        val sanitizedHeaders = sanitizeHeaders(headers)
        val okHttpFactory = createOkHttpDataSourceFactory(sanitizedHeaders)
        val baseDataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)
        val resolvedMimeType = inferMimeType(
            url = url,
            filename = url.substringBefore('?').substringAfterLast('/', "").takeIf { it.isNotBlank() },
            responseHeaders = null
        )
        return selectProgressiveUpstreamFactory(
            url = url,
            isHls = resolvedMimeType == MimeTypes.APPLICATION_M3U8,
            isDash = resolvedMimeType == MimeTypes.APPLICATION_MPD,
            okHttpFactory = okHttpFactory,
            baseDataSourceFactory = baseDataSourceFactory
        )
    }

    fun shutdown() = Unit

    private fun createOkHttpDataSourceFactory(headers: Map<String, String>): OkHttpDataSource.Factory {
        return OkHttpDataSource.Factory(playbackOkHttpClient).apply {
            setDefaultRequestProperties(headers)
            if (!headers.containsKey("User-Agent")) {
                setUserAgent(DEFAULT_USER_AGENT)
            }
        }
    }

    private fun selectProgressiveUpstreamFactory(
        url: String,
        isHls: Boolean,
        isDash: Boolean,
        okHttpFactory: OkHttpDataSource.Factory,
        baseDataSourceFactory: DataSource.Factory
    ): DataSource.Factory {
        parallelStartupPrefetchUnlocked.set(!(useParallelConnections && !isHls && !isDash))
        activeReadBytePosition.set(0L)
        return when {
            !usesHttpUpstream(url) -> baseDataSourceFactory
            useParallelConnections && !isHls && !isDash -> ParallelRangeDataSource.Factory(
                okHttpFactory,
                parallelConnectionCount,
                parallelChunkSizeMb.toLong() * 1024L * 1024L,
                shouldAllowBackgroundPrefetch = { parallelStartupPrefetchUnlocked.get() },
                onResolvedUri = { resolved ->
                    currentVodCacheResolvedUrl = resolved?.toString()
                },
                onReadPositionAdvanced = { position ->
                    activeReadBytePosition.accumulateAndGet(position) { current, next ->
                        if (next > current) next else current
                    }
                }
            )
            else -> okHttpFactory
        }
    }

    private fun createDefaultExtractorsFactory(): ExtractorsFactory {
        return DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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
