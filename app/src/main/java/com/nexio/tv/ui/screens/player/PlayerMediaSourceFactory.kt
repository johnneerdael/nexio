package com.nexio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.VodCacheSizeMode
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import kotlin.io.deleteRecursively

internal class PlayerMediaSourceFactory(private val context: Context) {
    private var okHttpClient: OkHttpClient? = null
    private var customExtractorsFactory: ExtractorsFactory? = null
    private var customSubtitleParserFactory: SubtitleParser.Factory? = null
    private val loadErrorHandlingPolicy = PlayerLoadErrorHandlingPolicy()
    private data class RemoteBlurayResolution(
        val playlistName: String,
        val segmentUris: List<Uri>
    )

    private data class ParsedMplsPlaylist(
        val name: String,
        val clipIds: List<String>,
        val duration90kHz: Long
    )

    @Volatile private var currentVodCacheUrl: String? = null
    @Volatile private var currentVodCacheResolvedUrl: String? = null
    @Volatile private var currentVodCacheActive: Boolean = false
    @Volatile private var currentProgressiveUpstreamFactory: DataSource.Factory? = null
    @Volatile private var currentProgressiveIsEligibleForWarmAhead: Boolean = false
    private val parallelStartupPrefetchUnlocked = AtomicBoolean(true)
    private val activeReadBytePosition = AtomicLong(0L)
    private val prefetchStop = AtomicBoolean(false)
    private var prefetchFuture: Future<*>? = null
    private var activePrefetchWriter: CacheWriter? = null
    private val prefetchExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Nexio-vod-prefetch").apply { isDaemon = true }
    }
    var useParallelConnections: Boolean = PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS
    var parallelConnectionCount: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT
    var parallelChunkSizeMb: Int = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB
    var vodCacheSizeMode: VodCacheSizeMode = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE
        set(value) {
            field = value
            if (value != VodCacheSizeMode.ON) {
                stopVodWarmAhead()
            }
        }
    var vodCacheSizeMb: Int = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MB

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
        stopVodWarmAhead()
        val sanitizedHeaders = sanitizeHeaders(headers)
        val okHttpFactory = OkHttpDataSource.Factory(getOrCreateOkHttpClient()).apply {
            setDefaultRequestProperties(sanitizedHeaders)
            if (!sanitizedHeaders.containsKey("User-Agent")) {
                setUserAgent(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            }
        }
        val baseDataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)
        val lowerPath = extractPath(url).lowercase(Locale.US)

        val isHls = lowerPath.contains(".m3u8") ||
            lowerPath.contains("/playlist") ||
            lowerPath.contains("/hls") ||
            lowerPath.contains("m3u8")

        val isDash = lowerPath.contains(".mpd") ||
            lowerPath.contains("/dash")

        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        when {
            isHls -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            isDash -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }

        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }

        val mediaItem = mediaItemBuilder.build()
        val blurayLocalSource = BlurayPlaylistResolver.resolve(url)
        if (blurayLocalSource != null) {
            currentVodCacheUrl = url
            currentVodCacheResolvedUrl = null
            currentVodCacheActive = false
            currentProgressiveUpstreamFactory = null
            currentProgressiveIsEligibleForWarmAhead = false
            return createBlurayMediaSource(
                source = blurayLocalSource,
                dataSourceFactory = baseDataSourceFactory,
                subtitleConfigurations = subtitleConfigurations
            )
        }

        parallelStartupPrefetchUnlocked.set(!(useParallelConnections && !isHls && !isDash))
        activeReadBytePosition.set(0L)
        val progressiveUpstreamFactory: DataSource.Factory = when {
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
        val useVodCache = ENABLE_VOD_CACHE &&
            vodCacheSizeMode == VodCacheSizeMode.ON &&
            !isHls &&
            !isDash &&
            shouldUseVodCache(url)
        val previousVodCacheActive = currentVodCacheActive
        currentVodCacheUrl = url
        currentVodCacheResolvedUrl = null
        currentVodCacheActive = false
        currentProgressiveUpstreamFactory = progressiveUpstreamFactory
        currentProgressiveIsEligibleForWarmAhead = useVodCache
        val vodCacheMaxBytes = resolveVodCacheMaxBytes(context)
        if (useVodCache && !isVodCacheDisabled) {
            maybeApplyLiveVodCacheCapIncrease(
                context = context,
                requestedMaxBytes = vodCacheMaxBytes,
                allowLiveReconfigure = !previousVodCacheActive
            )
        }
        val progressiveFactory = if (useVodCache && !isVodCacheDisabled) {
            val cache = getReadySimpleCache(vodCacheMaxBytes)
                ?: getAnySimpleCache()?.also {
                    if (configuredVodCacheMaxBytes != vodCacheMaxBytes) {
                        Log.d(
                            TAG,
                            "Using existing VOD cache instance with cap=${configuredVodCacheMaxBytes / 1024L / 1024L}MB " +
                                "while requested cap=${vodCacheMaxBytes / 1024L / 1024L}MB"
                        )
                    }
                }
                ?: runCatching {
                    // Only initialize when there is no active cache instance.
                    startVodCacheInitialization(context, vodCacheMaxBytes)
                    // Ensure first playback can attach cache even if async warmup has not completed yet.
                    getOrCreateSimpleCache(context, vodCacheMaxBytes)
                }.getOrElse { error ->
                    isVodCacheDisabled = true
                    Log.e(TAG, "Disabling VOD cache after synchronous initialization failure", error)
                    null
                }
            if (cache != null) {
                runCatching {
                    Log.d(TAG, "Using VOD cache for host=${Uri.parse(url).host ?: "unknown"}")
                    currentVodCacheActive = true
                    buildVodCacheDataSourceFactory(progressiveUpstreamFactory, cache)
                }.getOrElse { error ->
                    currentVodCacheActive = false
                    isVodCacheDisabled = true
                    Log.e(TAG, "Disabling VOD cache after datasource failure", error)
                    progressiveUpstreamFactory
                }
            } else {
                currentVodCacheActive = false
                if (!hasLoggedVodCacheNotReady) {
                    hasLoggedVodCacheNotReady = true
                    Log.d(TAG, "VOD cache not ready yet, falling back to network datasource")
                }
                progressiveUpstreamFactory
            }
        } else {
            currentVodCacheActive = false
            currentProgressiveIsEligibleForWarmAhead = false
            progressiveUpstreamFactory
        }

        if (!isHls && !isDash) {
            if (isLikelyHttpBdavStream(url = url, headers = sanitizedHeaders)) {
                return createBdavM2tsMediaSource(
                    mediaItem = mediaItem,
                    dataSourceFactory = progressiveFactory
                )
            }

            val remoteBluraySource = resolveHttpBlurayDirectory(
                url = url,
                headers = sanitizedHeaders
            )
            if (remoteBluraySource != null) {
                currentVodCacheActive = false
                currentProgressiveUpstreamFactory = null
                currentProgressiveIsEligibleForWarmAhead = false
                return createBlurayMediaSource(
                    playlistName = remoteBluraySource.playlistName,
                    segmentUris = remoteBluraySource.segmentUris,
                    dataSourceFactory = baseDataSourceFactory,
                    subtitleConfigurations = subtitleConfigurations
                )
            }
        }

        if (BlurayPlaylistResolver.isLikelyBdavM2tsUrl(url)) {
            return createBdavM2tsMediaSource(
                mediaItem = mediaItem,
                dataSourceFactory = progressiveFactory
            )
        }

        val extractorsFactory = customExtractorsFactory ?: createDefaultExtractorsFactory()
        val defaultProgressiveFactory = DefaultMediaSourceFactory(progressiveFactory, extractorsFactory).apply {
            setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            customSubtitleParserFactory?.let { parserFactory ->
                setSubtitleParserFactory(parserFactory)
            }
        }
        val forceDefaultFactory = customExtractorsFactory != null || customSubtitleParserFactory != null
        if (subtitleConfigurations.isNotEmpty()) {
            return defaultProgressiveFactory.createMediaSource(mediaItem)
        }
        return when {
            isHls && !forceDefaultFactory -> HlsMediaSource.Factory(okHttpFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
            isDash && !forceDefaultFactory -> DashMediaSource.Factory(okHttpFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
            else -> defaultProgressiveFactory.createMediaSource(mediaItem)
        }
    }

    private fun createDefaultExtractorsFactory(): ExtractorsFactory {
        return DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
    }

    private fun createBdavExtractorsFactory(): ExtractorsFactory {
        val tsFlags =
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
        return DefaultExtractorsFactory()
            .setTsExtractorFlags(tsFlags)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
    }

    private fun createBlurayMediaSource(
        source: BlurayPlaylistResolver.ResolvedBluraySource,
        dataSourceFactory: DataSource.Factory,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration>
    ): MediaSource {
        return createBlurayMediaSource(
            playlistName = source.playlistFile.name,
            segmentUris = source.segments.map { segment -> Uri.fromFile(segment) },
            dataSourceFactory = dataSourceFactory,
            subtitleConfigurations = subtitleConfigurations
        )
    }

    private fun createBlurayMediaSource(
        playlistName: String,
        segmentUris: List<Uri>,
        dataSourceFactory: DataSource.Factory,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration>
    ): MediaSource {
        val concatenatingMediaSource = ConcatenatingMediaSource()
        segmentUris.forEachIndexed { index, segmentUri ->
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(segmentUri)
                .setMediaId("$playlistName#$index")
            if (segmentUris.size == 1 && subtitleConfigurations.isNotEmpty()) {
                mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
            }
            val segmentMediaItem = mediaItemBuilder.build()
            concatenatingMediaSource.addMediaSource(
                createBdavM2tsMediaSource(
                    mediaItem = segmentMediaItem,
                    dataSourceFactory = dataSourceFactory
                )
            )
        }
        return concatenatingMediaSource
    }

    private fun createBdavM2tsMediaSource(
        mediaItem: MediaItem,
        dataSourceFactory: DataSource.Factory
    ): MediaSource {
        val bdavDataSourceFactory = BdavM2tsDataSourceFactory(dataSourceFactory)
        val extractorsFactory = createBdavExtractorsFactory()
        return ProgressiveMediaSource.Factory(bdavDataSourceFactory, extractorsFactory)
            .createMediaSource(mediaItem)
    }

    private fun isLikelyHttpBdavStream(url: String, headers: Map<String, String>): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        if (httpUrl.scheme !in listOf("http", "https")) return false

        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val request = requestBuilder.build()

        return try {
            getOrCreateOkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false

                val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.US)
                val body = response.body ?: return false
                val sample = body.byteStream().use { input ->
                    val buffer = ByteArray(4096)
                    val read = input.read(buffer)
                    if (read <= 0) ByteArray(0) else buffer.copyOf(read)
                }
                if (sample.isEmpty()) return false

                val likelyTsMime =
                    contentType.contains("video/vnd.dlna.mpeg-tts") ||
                        contentType.contains("video/mp2t") ||
                        contentType.contains("mpeg-tts")
                if (!likelyTsMime) return false

                matchesBdavPacketPattern(sample)
            }
        } catch (error: Exception) {
            Log.w(
                TAG,
                "HTTP BDAV probe failed for ${summarizeUrlForLog(url)}: ${error.javaClass.simpleName}"
            )
            false
        }
    }

    private fun matchesBdavPacketPattern(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        if ((bytes[4].toInt() and 0xFF) != 0x47) return false
        if (bytes.size >= 197 && (bytes[196].toInt() and 0xFF) == 0x47) return true
        return bytes.size < 197
    }

    private fun resolveHttpBlurayDirectory(
        url: String,
        headers: Map<String, String>
    ): RemoteBlurayResolution? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        if (httpUrl.scheme !in listOf("http", "https")) return null

        val rootProbe = fetchText(url = httpUrl.toString(), headers = headers, maxBytes = 256 * 1024)
            ?: return null
        if (!looksLikeBlurayDirectoryHtml(rootProbe)) return null

        val rootUrl = if (httpUrl.encodedPath.endsWith("/")) {
            httpUrl
        } else {
            httpUrl.newBuilder().addPathSegment("").build()
        }
        val playlistDirUrl = rootUrl.resolve("BDMV/PLAYLIST/") ?: return null
        val playlistHtml = fetchText(
            url = playlistDirUrl.toString(),
            headers = headers,
            maxBytes = 512 * 1024
        ) ?: return null
        val playlistNames = extractPlaylistNames(playlistHtml)
        if (playlistNames.isEmpty()) return null

        val parsedPlaylists = playlistNames.mapNotNull { playlistName ->
            val playlistUrl = playlistDirUrl.resolve(playlistName) ?: return@mapNotNull null
            val bytes = fetchBytes(
                url = playlistUrl.toString(),
                headers = headers,
                maxBytes = 1024 * 1024
            ) ?: return@mapNotNull null
            parseMplsPlaylist(name = playlistName, data = bytes)
        }
        if (parsedPlaylists.isEmpty()) return null

        val selected = parsedPlaylists.maxWithOrNull(
            compareBy<ParsedMplsPlaylist> { it.duration90kHz }
                .thenBy { it.clipIds.size }
                .thenBy { it.name }
        ) ?: return null

        val streamDirUrl = rootUrl.resolve("BDMV/STREAM/") ?: return null
        val segmentUris = selected.clipIds.mapNotNull { clipId ->
            streamDirUrl.resolve("$clipId.m2ts")?.let { Uri.parse(it.toString()) }
        }
        if (segmentUris.isEmpty()) return null
        return RemoteBlurayResolution(
            playlistName = selected.name,
            segmentUris = segmentUris
        )
    }

    private fun fetchText(url: String, headers: Map<String, String>, maxBytes: Int): String? {
        val bytes = fetchBytes(url, headers, maxBytes) ?: return null
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
    }

    private fun fetchBytes(url: String, headers: Map<String, String>, maxBytes: Int): ByteArray? {
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val request = requestBuilder.build()
        return try {
            getOrCreateOkHttpClient().newCall(request).execute().use { response ->
                val body = response.body ?: return null
                body.byteStream().use { input ->
                    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (total < maxBytes) {
                        val toRead = minOf(buffer.size, maxBytes - total)
                        val read = input.read(buffer, 0, toRead)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        total += read
                    }
                    if (!response.isSuccessful) return null
                    output.toByteArray()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun looksLikeBlurayDirectoryHtml(content: String): Boolean {
        val lower = content.lowercase(Locale.US)
        return (lower.contains("<html") || lower.contains("<a ")) &&
            lower.contains("bdmv") &&
            (lower.contains("certificate") || lower.contains("playlist"))
    }

    private fun extractPlaylistNames(content: String): List<String> {
        return Regex("""(?i)(\d{5}\.mpls)""")
            .findAll(content)
            .map { match -> match.groupValues[1] }
            .distinct()
            .toList()
    }

    private fun parseMplsPlaylist(name: String, data: ByteArray): ParsedMplsPlaylist? {
        if (data.size < 32) return null
        val header = readAscii(data, 0, 4)
        if (header != "MPLS") return null

        val playlistStart = readUInt32(data, 8).toInt()
        if (playlistStart <= 0 || playlistStart + 10 > data.size) return null

        val sectionStart = playlistStart + 4
        val playItemCount = readUInt16(data, sectionStart + 2)
        var cursor = sectionStart + 6
        val clipIds = ArrayList<String>(playItemCount)
        var duration90kHz = 0L

        repeat(playItemCount) {
            if (cursor + 2 > data.size) return@repeat
            val itemLength = readUInt16(data, cursor)
            if (itemLength <= 0) return@repeat
            val itemStart = cursor + 2
            val itemEnd = itemStart + itemLength
            if (itemEnd > data.size || itemStart + 20 > data.size) return@repeat

            val clipId = readAscii(data, itemStart, 5)
            val codecId = readAscii(data, itemStart + 5, 4)
            if (
                clipId.length == 5 &&
                clipId.all { char -> char.isDigit() } &&
                codecId.equals("M2TS", ignoreCase = true)
            ) {
                clipIds += clipId
            }

            val inTime = readUInt32(data, itemStart + 12)
            val outTime = readUInt32(data, itemStart + 16)
            if (outTime > inTime) {
                duration90kHz += (outTime - inTime)
            }
            cursor = itemEnd
        }

        if (clipIds.isEmpty()) return null
        return ParsedMplsPlaylist(name = name, clipIds = clipIds, duration90kHz = duration90kHz)
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return 0L
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun readAscii(data: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || length <= 0 || offset + length > data.size) return ""
        return String(data, offset, length, Charsets.US_ASCII).trim()
    }

    private fun summarizeUrlForLog(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url.substringBefore('?')
        val scheme = uri.scheme
        val host = uri.host
        val path = uri.path.orEmpty()
        return when {
            !scheme.isNullOrBlank() && !host.isNullOrBlank() -> "$scheme://$host$path"
            !scheme.isNullOrBlank() && path.isNotBlank() -> "$scheme:$path"
            else -> url.substringBefore('?')
        }
    }

    private fun extractPath(url: String): String {
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        if (parsed != null && !parsed.path.isNullOrBlank()) {
            return parsed.path ?: url
        }
        return url.substringBefore('?')
    }

    fun shutdown() {
        stopVodWarmAhead()
        okHttpClient?.let { client ->
            Thread {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }.start()
            okHttpClient = null
        }
        prefetchExecutor.shutdownNow()
    }

    fun clearVodCache() {
        stopVodWarmAhead()
        clearVodCacheInternal(context)
    }

    private fun getOrCreateOkHttpClient(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 12
        }
        return okHttpClient ?: OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val response = chain.proceed(originalRequest)

                if (response.isRedirect) {
                    val location = response.header("Location") ?: return@addInterceptor response
                    val newRequest = originalRequest.newBuilder()
                        .url(location)
                        .build()
                    response.close()
                    return@addInterceptor chain.proceed(newRequest)
                }
                response
            }
            .build()
            .also { okHttpClient = it }
    }

    fun warmupVodCacheAsync() {
        if (!ENABLE_VOD_CACHE || isVodCacheDisabled || vodCacheSizeMode != VodCacheSizeMode.ON) return
        startVodCacheInitialization(context, resolveVodCacheMaxBytes(context))
    }

    fun notifyPlaybackFirstFrameRendered() {
        parallelStartupPrefetchUnlocked.set(true)
        startVodWarmAheadIfEligible()
    }

    fun getVodCacheLogState(currentStreamUrl: String? = null): String {
        if (!ENABLE_VOD_CACHE) return "vod=off"
        if (vodCacheSizeMode != VodCacheSizeMode.ON) return "vod=off"
        if (isVodCacheDisabled) return "vod=disabled"

        val usedBytes = runCatching { getAnySimpleCache()?.cacheSpace ?: 0L }.getOrDefault(0L)
        val streamUrl = currentStreamUrl ?: currentVodCacheUrl
        val streamBytes = runCatching {
            val cache = getAnySimpleCache() ?: return@runCatching 0L
            val keys = linkedSetOf<String>()
            fun addKey(value: String?) {
                if (value.isNullOrBlank()) return
                keys += value
                runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { keys += it }
            }
            addKey(streamUrl)
            addKey(currentVodCacheUrl)
            addKey(currentVodCacheResolvedUrl)
            val bytes = keys.sumOf { key ->
                cache.getCachedSpans(key).sumOf { span -> span.length.coerceAtLeast(0L) }
            }
            bytes.coerceAtMost(usedBytes)
        }.getOrDefault(0L)
        val capBytes = when {
            configuredVodCacheMaxBytes > 0L -> configuredVodCacheMaxBytes
            else -> resolveVodCacheMaxBytes(context)
        }
        val mode = if (vodCacheSizeMode == VodCacheSizeMode.ON) "on" else "off"
        val isActiveForCurrentStream =
            streamUrl != null && streamUrl == currentVodCacheUrl && currentVodCacheActive
        return "vod=$mode total=${bytesToMb(usedBytes)}/${bytesToMb(capBytes)}MB " +
            "stream=${bytesToMb(streamBytes)}MB active=$isActiveForCurrentStream"
    }

    private fun startVodWarmAheadIfEligible() {
        if (!ENABLE_VOD_CACHE || isVodCacheDisabled) return
        if (vodCacheSizeMode != VodCacheSizeMode.ON) return
        if (!currentProgressiveIsEligibleForWarmAhead || !currentVodCacheActive) return
        val streamUrl = currentVodCacheUrl ?: return
        val upstreamFactory = currentProgressiveUpstreamFactory ?: return
        val capBytes = resolveVodCacheMaxBytes(context)
        val cache = getAnySimpleCache() ?: return

        stopVodWarmAhead()
        prefetchStop.set(false)
        prefetchFuture = prefetchExecutor.submit {
            runWarmAheadLoop(
                streamUrl = streamUrl,
                upstreamFactory = upstreamFactory,
                cache = cache,
                capBytes = capBytes
            )
        }
    }

    internal fun stopVodWarmAhead() {
        prefetchStop.set(true)
        activePrefetchWriter?.cancel()
        prefetchFuture?.cancel(true)
        prefetchFuture = null
        activePrefetchWriter = null
    }

    private fun runWarmAheadLoop(
        streamUrl: String,
        upstreamFactory: DataSource.Factory,
        cache: SimpleCache,
        capBytes: Long
    ) {
        val effectiveCapBytes = capBytes.coerceAtLeast(0L)
        if (effectiveCapBytes <= 0L) return
        var cursor = 0L
        var idleCycles = 0
        while (!prefetchStop.get() && !Thread.currentThread().isInterrupted) {
            val liveUrl = currentVodCacheResolvedUrl ?: currentVodCacheUrl ?: streamUrl
            val prefetchUri = runCatching { Uri.parse(liveUrl) }.getOrElse { Uri.parse(streamUrl) }
            val cacheKey = prefetchUri.toString()
            val cachedFrontier = contiguousCachedPrefix(cache, cacheKey, effectiveCapBytes)
            if (cachedFrontier > activeReadBytePosition.get()) {
                activeReadBytePosition.set(cachedFrontier)
            }
            if (cursor >= effectiveCapBytes) {
                break
            }
            val hole = findNextUncachedHole(
                cache = cache,
                cacheKey = cacheKey,
                start = cursor,
                endExclusive = effectiveCapBytes
            )
            if (hole == null) {
                idleCycles++
                if (idleCycles > PREFETCH_MAX_IDLE_CYCLES) break
                Thread.sleep(PREFETCH_IDLE_SLEEP_MS)
                continue
            }
            idleCycles = 0

            var holeStart = hole.first
            val holeLength = hole.second
            val activeGuardEnd = activeReadBytePosition.get().coerceAtLeast(0L) + PREFETCH_ACTIVE_GUARD_BYTES
            if (holeStart < activeGuardEnd) {
                cursor = activeGuardEnd.coerceAtMost(effectiveCapBytes)
                Thread.sleep(PREFETCH_REBASE_SLEEP_MS)
                continue
            }

            val writeLength = minOf(
                PREFETCH_BLOCK_BYTES,
                holeLength,
                effectiveCapBytes - holeStart
            )
            if (writeLength <= 0L) {
                cursor = (holeStart + 1L).coerceAtMost(effectiveCapBytes)
                continue
            }

            val dataSpec = DataSpec.Builder()
                .setUri(prefetchUri)
                .setPosition(holeStart)
                .setLength(writeLength)
                .build()
            val prefetchFactory = buildVodCacheDataSourceFactory(
                upstreamFactory = upstreamFactory,
                cache = cache,
                blockOnCache = true
            )
            val writer = CacheWriter(prefetchFactory.createDataSource() as CacheDataSource, dataSpec, null, null)
            activePrefetchWriter = writer
            runCatching {
                writer.cache()
            }.onFailure { error ->
                if (!prefetchStop.get()) {
                    Log.w(
                        TAG,
                        "VOD warm-ahead failed at offset=${holeStart / 1024L / 1024L}MB len=${writeLength / 1024L / 1024L}MB",
                        error
                    )
                }
            }
            activePrefetchWriter = null
            cursor = (holeStart + writeLength).coerceAtMost(effectiveCapBytes)
        }
    }

    private fun findNextUncachedHole(
        cache: SimpleCache,
        cacheKey: String,
        start: Long,
        endExclusive: Long
    ): Pair<Long, Long>? {
        var position = start.coerceAtLeast(0L)
        val upperBound = endExclusive.coerceAtLeast(position)
        while (position < upperBound) {
            val remaining = upperBound - position
            val cachedLength = cache.getCachedLength(cacheKey, position, remaining)
            if (cachedLength > 0L) {
                position += cachedLength
                continue
            }
            val holeLength = (-cachedLength).coerceAtLeast(1L).coerceAtMost(remaining)
            return position to holeLength
        }
        return null
    }

    private fun contiguousCachedPrefix(cache: SimpleCache, cacheKey: String, limit: Long): Long {
        var position = 0L
        val end = limit.coerceAtLeast(0L)
        while (position < end) {
            val cachedLength = cache.getCachedLength(cacheKey, position, end - position)
            if (cachedLength <= 0L) break
            position += cachedLength
        }
        return position
    }

    private fun buildVodCacheDataSourceFactory(
        upstreamFactory: DataSource.Factory,
        cache: SimpleCache,
        blockOnCache: Boolean = false
    ): DataSource.Factory {
        val dataSinkFactory = CacheDataSink.Factory()
            .setCache(cache)
            .setFragmentSize(2L * 1024L * 1024L)
        var flags = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
        if (blockOnCache) {
            flags = flags or CacheDataSource.FLAG_BLOCK_ON_CACHE
        }
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(dataSinkFactory)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(flags)
    }

    private fun shouldUseVodCache(url: String): Boolean = usesHttpUpstream(url)

    private fun resolveVodCacheMaxBytes(context: Context): Long {
        val maxBytes = PlayerSettings.MAX_VOD_CACHE_SIZE_MB.toLong() * 1024L * 1024L
        val runtimeMaxBytes = resolveRuntimeVodCacheUpperBoundBytes(context, maxBytes)
        val requestedBytes = vodCacheSizeMb
            .coerceIn(PlayerSettings.MIN_VOD_CACHE_SIZE_MB, PlayerSettings.MAX_VOD_CACHE_SIZE_MB)
            .toLong() * 1024L * 1024L
        val resolvedBytes = requestedBytes.coerceAtMost(runtimeMaxBytes)
        if (resolvedBytes < requestedBytes) {
            Log.w(
                TAG,
                "VOD cache cap clamped from ${requestedBytes / 1024L / 1024L}MB " +
                    "to ${resolvedBytes / 1024L / 1024L}MB due to free-space headroom"
            )
        }
        return resolvedBytes
    }

    private fun resolveRuntimeVodCacheUpperBoundBytes(context: Context, hardMaxBytes: Long): Long {
        val freeSpaceBytes = context.cacheDir.usableSpace
        if (freeSpaceBytes <= 0L) return hardMaxBytes
        val headroomAdjusted = if (freeSpaceBytes > VOD_CACHE_FREE_SPACE_RESERVE_BYTES) {
            freeSpaceBytes - VOD_CACHE_FREE_SPACE_RESERVE_BYTES
        } else {
            (freeSpaceBytes * 8L) / 10L
        }
        return headroomAdjusted
            .coerceAtLeast(MIN_RUNTIME_VOD_CACHE_BYTES)
            .coerceAtMost(hardMaxBytes)
    }

    companion object {
        private const val TAG = "PlayerMediaSource"
        private const val ENABLE_VOD_CACHE = true
        private const val VOD_CACHE_DIR = "player_vod_cache"
        private const val VOD_CACHE_FREE_SPACE_RESERVE_BYTES = 1024L * 1024L * 1024L
        private const val MIN_RUNTIME_VOD_CACHE_BYTES = 1L * 1024L * 1024L
        private const val PREFETCH_BLOCK_BYTES = 16L * 1024L * 1024L
        private const val PREFETCH_ACTIVE_GUARD_BYTES = 8L * 1024L * 1024L
        private const val PREFETCH_REBASE_SLEEP_MS = 100L
        private const val PREFETCH_IDLE_SLEEP_MS = 250L
        private const val PREFETCH_MAX_IDLE_CYCLES = 20
        @Volatile private var sharedSimpleCache: SimpleCache? = null
        @Volatile private var cacheDatabaseProvider: DatabaseProvider? = null
        @Volatile private var configuredVodCacheMaxBytes: Long = -1L
        @Volatile private var lastDeferredReconfigureTargetBytes: Long = -1L
        @Volatile private var isVodCacheDisabled: Boolean = false
        @Volatile private var hasLoggedVodCacheNotReady: Boolean = false
        private val cacheInitStarted = AtomicBoolean(false)
        private val cacheInitExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Nexio-vod-cache-init").apply { isDaemon = true }
        }

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
            val scheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.US) }.getOrNull()
            return scheme == "https" ||
                scheme == "http" ||
                url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("http://", ignoreCase = true)
        }

        private fun getReadySimpleCache(expectedMaxBytes: Long): SimpleCache? {
            val cache = sharedSimpleCache ?: return null
            return if (configuredVodCacheMaxBytes == expectedMaxBytes) cache else null
        }

        private fun getAnySimpleCache(): SimpleCache? = sharedSimpleCache

        private fun bytesToMb(bytes: Long): Long = bytes / (1024L * 1024L)

        private fun clearVodCacheInternal(context: Context) {
            synchronized(this) {
                runCatching { sharedSimpleCache?.release() }
                    .onFailure { Log.w(TAG, "Failed to release VOD cache before clear", it) }
                sharedSimpleCache = null
                configuredVodCacheMaxBytes = -1L
                lastDeferredReconfigureTargetBytes = -1L
                hasLoggedVodCacheNotReady = false
                cacheDatabaseProvider = null

                val cacheDir = File(context.cacheDir, VOD_CACHE_DIR)
                runCatching {
                    if (cacheDir.exists()) {
                        cacheDir.deleteRecursively()
                    }
                }.onFailure { Log.w(TAG, "Failed to delete VOD cache directory", it) }
            }
            Log.i(TAG, "Cleared VOD cache contents on playback stop")
        }

        private fun getOrCreateSimpleCache(context: Context, maxBytes: Long): SimpleCache {
            sharedSimpleCache?.let {
                if (configuredVodCacheMaxBytes != maxBytes) {
                    maybeLogDeferredReconfigure(maxBytes)
                }
                return it
            }
            synchronized(this) {
                sharedSimpleCache?.let { existing ->
                    if (configuredVodCacheMaxBytes != maxBytes) {
                        maybeLogDeferredReconfigure(maxBytes)
                    }
                    return existing
                }
                val cacheDir = File(context.cacheDir, VOD_CACHE_DIR).apply { mkdirs() }
                val databaseProvider = cacheDatabaseProvider
                    ?: StandaloneDatabaseProvider(context).also { cacheDatabaseProvider = it }
                return SimpleCache(
                    cacheDir,
                    LeastRecentlyUsedCacheEvictor(maxBytes),
                    databaseProvider
                ).also {
                    configuredVodCacheMaxBytes = maxBytes
                    lastDeferredReconfigureTargetBytes = -1L
                    sharedSimpleCache = it
                }
            }
        }

        private fun maybeApplyLiveVodCacheCapIncrease(
            context: Context,
            requestedMaxBytes: Long,
            allowLiveReconfigure: Boolean
        ) {
            val currentMaxBytes = configuredVodCacheMaxBytes
            if (requestedMaxBytes <= 0L || currentMaxBytes <= 0L) return
            if (requestedMaxBytes <= currentMaxBytes) return
            if (requestedMaxBytes - currentMaxBytes < LIVE_CACHE_RECONFIGURE_MIN_DELTA_BYTES) return
            if (!allowLiveReconfigure) {
                maybeLogDeferredReconfigure(requestedMaxBytes)
                return
            }
            sharedSimpleCache ?: return
            synchronized(this) {
                val liveCache = sharedSimpleCache ?: return
                val liveCurrentMaxBytes = configuredVodCacheMaxBytes
                if (requestedMaxBytes <= liveCurrentMaxBytes) return
                if (requestedMaxBytes - liveCurrentMaxBytes < LIVE_CACHE_RECONFIGURE_MIN_DELTA_BYTES) {
                    return
                }
                runCatching {
                    Log.i(
                        TAG,
                        "Recreating VOD cache live to apply cap increase from " +
                            "${liveCurrentMaxBytes / 1024L / 1024L}MB to " +
                            "${requestedMaxBytes / 1024L / 1024L}MB"
                    )
                    liveCache.release()
                    sharedSimpleCache = null
                    configuredVodCacheMaxBytes = -1L
                    getOrCreateSimpleCache(context, requestedMaxBytes)
                    Log.i(
                        TAG,
                        "Applied VOD cache cap increase live with new cap=" +
                            "${requestedMaxBytes / 1024L / 1024L}MB"
                    )
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "Live VOD cache cap update failed, restoring previous cap=" +
                            "${liveCurrentMaxBytes / 1024L / 1024L}MB",
                        error
                    )
                    runCatching { getOrCreateSimpleCache(context, liveCurrentMaxBytes) }
                        .onFailure { restoreError ->
                            isVodCacheDisabled = true
                            Log.e(TAG, "Disabling VOD cache after live reconfigure restore failure", restoreError)
                        }
                    maybeLogDeferredReconfigure(requestedMaxBytes)
                }
            }
        }

        private fun startVodCacheInitialization(context: Context, maxBytes: Long) {
            if (isVodCacheDisabled) return
            if (getReadySimpleCache(maxBytes) != null) return
            getAnySimpleCache()?.let {
                maybeLogDeferredReconfigure(maxBytes)
                return
            }
            if (!cacheInitStarted.compareAndSet(false, true)) return
            cacheInitExecutor.execute {
                runCatching {
                    getOrCreateSimpleCache(context, maxBytes)
                    Log.i(TAG, "VOD cache initialized successfully with cap=${maxBytes / 1024L / 1024L}MB")
                }.onFailure { error ->
                    isVodCacheDisabled = true
                    Log.e(TAG, "Disabling VOD cache after initialization failure", error)
                }.also {
                    cacheInitStarted.set(false)
                }
            }
        }

        private fun maybeLogDeferredReconfigure(requestedMaxBytes: Long) {
            if (requestedMaxBytes <= 0L) return
            if (requestedMaxBytes == configuredVodCacheMaxBytes) return
            if (lastDeferredReconfigureTargetBytes == requestedMaxBytes) return
            lastDeferredReconfigureTargetBytes = requestedMaxBytes
            Log.i(
                TAG,
                "Deferring VOD cache cap change from ${configuredVodCacheMaxBytes / 1024L / 1024L}MB " +
                "to ${requestedMaxBytes / 1024L / 1024L}MB until app restart to avoid in-use cache reconfiguration."
            )
        }

        private const val LIVE_CACHE_RECONFIGURE_MIN_DELTA_BYTES = 64L * 1024L * 1024L
    }
}

private class PlayerLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(6) {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        return when (dataType) {
            C.DATA_TYPE_MEDIA,
            C.DATA_TYPE_MANIFEST -> 6
            else -> 3
        }
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val timeout = loadErrorInfo.exception.findCause<SocketTimeoutException>() != null
        if (!timeout) return super.getRetryDelayMsFor(loadErrorInfo)

        val retryDelayMs = when (loadErrorInfo.errorCount) {
            1 -> 750L
            2 -> 1_500L
            3 -> 3_000L
            4 -> 5_000L
            5 -> 8_000L
            else -> C.TIME_UNSET
        }
        Log.w(
            "PlayerMediaSource",
            "Timeout load error dataType=${dataTypeName(loadErrorInfo.mediaLoadData.dataType)} " +
                "host=${loadErrorInfo.loadEventInfo.uri.host ?: "unknown"} " +
                "attempt=${loadErrorInfo.errorCount} retryDelayMs=$retryDelayMs " +
                "error=${loadErrorInfo.exception.javaClass.simpleName}:${loadErrorInfo.exception.message}"
        )
        return retryDelayMs
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private fun dataTypeName(dataType: Int): String = when (dataType) {
    C.DATA_TYPE_MEDIA -> "media"
    C.DATA_TYPE_MANIFEST -> "manifest"
    else -> "other($dataType)"
}
