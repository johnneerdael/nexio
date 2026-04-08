@file:Suppress("DEPRECATION")

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
import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.instrumentation.EventFamily
import com.nexio.tv.instrumentation.PlaybackRangeContextCallFactory
import com.nexio.tv.instrumentation.PlaybackTracer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import kotlin.io.deleteRecursively

internal class PlayerMediaSourceFactory(
    private val context: Context,
    private val sharedOkHttpClient: OkHttpClient
) {
    // The shared client is injected from DI (NetworkModule @Named("playback")).
    // It already has a bounded callTimeout, maxRequestsPerHost=12, and a ConnectionPool
    // that is shared with the benchmark transports so measurements are comparable to playback.
    private var okHttpClient: OkHttpClient? = sharedOkHttpClient
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

    private fun emitCacheActive(active: Boolean, source: String, maxBytes: Long? = null) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.CACHE, "cache_active") {
            putBool("active", active)
            putString("source", source)
            putString("streamHost", currentVodCacheUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() })
            if (maxBytes != null) putLong("maxBytes", maxBytes)
        }
    }

    private fun emitWarmAheadStart(streamUrl: String, capBytes: Long) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.CACHE, "warm_ahead_start") {
            putString("streamHost", runCatching { Uri.parse(streamUrl).host }.getOrNull())
            putLong("capBytes", capBytes)
            putBool("cacheActive", currentVodCacheActive)
        }
    }

    private fun emitWarmAheadStop(reason: String) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.CACHE, "warm_ahead_stop") {
            putString("reason", reason)
            putBool("cacheActive", currentVodCacheActive)
            putBool("cancelRequested", prefetchStop.get())
        }
    }

    private fun emitWarmAheadLoopIteration(
        durationMs: Long,
        state: String,
        paused: Boolean,
        holeStart: Long,
        writeLength: Long
    ) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.CACHE, "warm_ahead_loop_iteration_ms") {
            putLong("durationMs", durationMs)
            putString("state", state)
            putBool("paused", paused)
            if (holeStart >= 0L) putLong("holeStart", holeStart)
            if (writeLength > 0L) putLong("writeLength", writeLength)
        }
    }

    private fun emitCacheWriteLatency(durationMs: Long, holeStart: Long, writeLength: Long, success: Boolean, error: Throwable? = null) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.CACHE, "cache_write_latency_ms") {
            putLong("durationMs", durationMs)
            putLong("holeStart", holeStart)
            putLong("writeLength", writeLength)
            putBool("success", success)
            putString("errorClass", error?.javaClass?.name)
            putString("errorMessage", error?.message)
        }
    }

    @Volatile private var currentVodCacheUrl: String? = null
    @Volatile private var currentVodCacheResolvedUrl: String? = null
    @Volatile private var currentVodCacheActive: Boolean = false
    @Volatile private var currentProgressiveUpstreamFactory: DataSource.Factory? = null
    @Volatile private var currentWarmAheadUpstreamFactory: DataSource.Factory? = null
    @Volatile private var currentProgressiveIsEligibleForWarmAhead: Boolean = false
    private val parallelStartupPrefetchUnlocked = AtomicBoolean(true)
    // Gate used by the warm-ahead loop and PRDS background prefetch to pause during rebuffer
    // recovery. The TTL matches REBUFFER_PREFETCH_PAUSE_MS so both paths gate on the same
    // recovery window. Notified by the runtime controller via `notifyRebuffer()`.
    internal val rebufferGate = RebufferGate(ttlMs = REBUFFER_PREFETCH_PAUSE_MS)
    private val activeReadBytePosition = AtomicLong(0L)
    private val prefetchStop = AtomicBoolean(false)
    private var prefetchFuture: Future<*>? = null
    private var activePrefetchWriter: CacheWriter? = null
    private val prefetchExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Nexio-vod-prefetch").apply { isDaemon = true }
    }
    private val stableCacheKeyFactory = StableCacheKeyFactory()
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
    @Volatile var capabilityEnvelope: CapabilityEnvelope? = null
    @Volatile var transportPolicyProvider: () -> TransportPolicy? = { null }
    @Volatile var onTransportObservation: (RuntimeTransportObservation) -> Unit = {}

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
        val okHttpFactory = OkHttpDataSource.Factory(
            PlaybackRangeContextCallFactory(getOrCreateOkHttpClient())
        ).apply {
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
            currentWarmAheadUpstreamFactory = null
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
            useParallelConnections && !isHls && !isDash -> {
                val envelope = capabilityEnvelope ?: CapabilityEnvelope.DEFAULT
                ParallelRangeDataSource.Factory(
                    okHttpFactory,
                    envelope,
                    envelope.maxSafeUrgentChunkBytes,
                    shouldAllowBackgroundPrefetch = {
                        parallelStartupPrefetchUnlocked.get() && !rebufferGate.isPaused()
                    },
                    onResolvedUri = { resolved ->
                        currentVodCacheResolvedUrl = resolved?.toString()
                    },
                    onTransportObservation = onTransportObservation,
                    onReadPositionAdvanced = { position ->
                        activeReadBytePosition.accumulateAndGet(position) { current, next ->
                            if (next > current) next else current
                        }
                    },
                    transportPolicyProvider = transportPolicyProvider
                )
            }
            else -> okHttpFactory
        }
        val pmsf_branch = when {
            !usesHttpUpstream(url) -> "base"
            useParallelConnections && !isHls && !isDash -> "prds"
            else -> "okHttp"
        }
        val pmsf_envelope = capabilityEnvelope
        PlayerTransportTelemetry.log("pmsf.create", mapOf(
            "branch" to pmsf_branch,
            "capabilityEnvelopePresent" to (pmsf_envelope != null),
            "urgentChunkBytes" to (if (pmsf_branch == "prds") (pmsf_envelope?.maxSafeUrgentChunkBytes ?: CapabilityEnvelope.DEFAULT.maxSafeUrgentChunkBytes) else null)
        ))
        okHttpClient?.let { client ->
            PlayerTransportTelemetry.logThrottled("okhttp.depth.playback", 1000L, mapOf(
                "maxRequestsPerHost" to client.dispatcher.maxRequestsPerHost,
                "queued" to client.dispatcher.queuedCallsCount(),
                "running" to client.dispatcher.runningCallsCount()
            ))
        }
        val useVodCache = ENABLE_VOD_CACHE &&
            vodCacheSizeMode == VodCacheSizeMode.ON &&
            !isHls &&
            !isDash &&
            shouldUseVodCache(url)
        // WP3 — open a playback-trace MediaSourceSession. Emits
        // `playback_session_started` with the full header and binds the
        // sessionId onto the runtime collector so rebuffer/decode events it
        // emits are correlated with this factory's session.
        openPlaybackTraceSession(
            url = url,
            branch = pmsf_branch,
            envelope = pmsf_envelope,
            cacheActive = useVodCache && !isVodCacheDisabled
        )
        val previousVodCacheActive = currentVodCacheActive
        currentVodCacheUrl = url
        currentVodCacheResolvedUrl = null
        currentVodCacheActive = false
        currentProgressiveUpstreamFactory = progressiveUpstreamFactory
        currentWarmAheadUpstreamFactory = okHttpFactory // single-connection for warm-ahead
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
                    emitCacheActive(active = true, source = "progressive_attach", maxBytes = vodCacheMaxBytes)
                    buildVodCacheDataSourceFactory(progressiveUpstreamFactory, cache)
                }.getOrElse { error ->
                    currentVodCacheActive = false
                    isVodCacheDisabled = true
                    emitCacheActive(active = false, source = "datasource_failure", maxBytes = vodCacheMaxBytes)
                    Log.e(TAG, "Disabling VOD cache after datasource failure", error)
                    progressiveUpstreamFactory
                }
            } else {
                currentVodCacheActive = false
                emitCacheActive(active = false, source = "cache_not_ready", maxBytes = vodCacheMaxBytes)
                if (!hasLoggedVodCacheNotReady) {
                    hasLoggedVodCacheNotReady = true
                    Log.d(TAG, "VOD cache not ready yet, falling back to network datasource")
                }
                progressiveUpstreamFactory
            }
        } else {
            currentVodCacheActive = false
            currentProgressiveIsEligibleForWarmAhead = false
            emitCacheActive(active = false, source = "cache_disabled")
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
                currentWarmAheadUpstreamFactory = null
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
        // The OkHttpClient is a Hilt-managed singleton (NetworkModule @Named("playback"))
        // shared with the benchmark client (same Dispatcher + ConnectionPool). We must NOT
        // call dispatcher.executorService.shutdown() or connectionPool.evictAll() here —
        // doing so kills the singleton for the entire app and the next playback or
        // benchmark request fails with InterruptedIOException("executor rejected") from
        // okhttp3.RealCall wrapping a RejectedExecutionException. The factory no longer
        // owns the client lifecycle; only the prefetchExecutor below.
        okHttpClient = null
        prefetchExecutor.shutdownNow()
    }

    fun clearVodCache() {
        stopVodWarmAhead()
        clearVodCacheInternal(context)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun clearVodCacheForContent(contentKey: String) {
        val cache = getAnySimpleCache() ?: return
        runCatching {
            cache.removeResource(contentKey)
        }.onFailure { Log.w(TAG, "Failed to clear VOD cache for key: $contentKey", it) }
    }

    private fun getOrCreateOkHttpClient(): OkHttpClient {
        // Returns the DI-provided shared client (set at construction time).
        // The redirect-following interceptor, callTimeout, dispatcher, and ConnectionPool
        // all live on the shared client provided by NetworkModule @Named("playback").
        return okHttpClient ?: sharedOkHttpClient
    }

    fun warmupVodCacheAsync() {
        if (!ENABLE_VOD_CACHE || isVodCacheDisabled || vodCacheSizeMode != VodCacheSizeMode.ON) return
        startVodCacheInitialization(context, resolveVodCacheMaxBytes(context))
    }

    fun notifyPlaybackFirstFrameRendered() {
        parallelStartupPrefetchUnlocked.set(true)
        startVodWarmAheadIfEligible()
    }

    /**
     * Called by the runtime controller when the player drops back to STATE_BUFFERING after
     * the first frame has rendered. Pauses background chunk prefetch in [ParallelRangeDataSource]
     * for [REBUFFER_PREFETCH_PAUSE_MS] so urgent connection slots and bandwidth go to the
     * playhead recovery rather than speculative warm-ahead.
     */
    fun notifyRebuffer() {
        rebufferGate.notifyRebuffer()
        // WP3 — emit REBUFFER rebuffer_start. Thread identity: call site is
        // Player.Listener.onPlaybackStateChanged in PlayerRuntimeControllerInitialization
        // which Media3 guarantees to run on the application main thread. The
        // emit is non-blocking (MPSC ring enqueue) so it is hot-path-safe even
        // from the main thread.
        com.nexio.tv.instrumentation.PlaybackTracer.emit(
            com.nexio.tv.instrumentation.EventFamily.REBUFFER,
            "rebuffer_start"
        ) {
            putLong("lastReadPos", activeReadBytePosition.get())
        }
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

        // Respect transport policy warm-ahead gate: disabled during startup/recovery/weak-buffer
        val policy = transportPolicyProvider()
        if (policy != null && !policy.warmAheadEnabled) return

        val streamUrl = currentVodCacheUrl ?: return
        val upstreamFactory = currentWarmAheadUpstreamFactory ?: return
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
        emitWarmAheadStart(streamUrl, capBytes)
    }

    internal fun stopVodWarmAhead() {
        emitWarmAheadStop("stop_requested")
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
            val iterationStartMs = android.os.SystemClock.elapsedRealtime()
            var iterationState = "running"
            var paused = false
            var holeStartForTrace = -1L
            var writeLengthForTrace = 0L
            // Pause warm-ahead during rebuffer recovery so urgent connection slots and
            // bandwidth are fully available for the playhead. Resume automatically once
            // the rebuffer window expires (TTL-based: gate ttlMs = REBUFFER_PREFETCH_PAUSE_MS).
            try {
                val isPaused = rebufferGate.isPaused()
                val ageMs = rebufferGate.ageMs()
                PlayerTransportTelemetry.logThrottled(
                    site = "warmahead.rebuffer",
                    windowMs = 1_000L,
                    pairs = mapOf(
                        "ageMs" to if (ageMs == Long.MAX_VALUE) -1L else ageMs,
                        "paused" to isPaused
                    )
                )
                if (isPaused) {
                    iterationState = "paused_rebuffer"
                    paused = true
                    Thread.sleep(WARM_AHEAD_REBUFFER_POLL_MS)
                    continue
                }
                val liveUrl = currentVodCacheResolvedUrl ?: currentVodCacheUrl ?: streamUrl
                val prefetchUri = runCatching { Uri.parse(liveUrl) }.getOrElse { Uri.parse(streamUrl) }
                val cacheKey = stableCacheKeyFactory.buildCacheKey(
                    DataSpec.Builder().setUri(prefetchUri).build()
                )
                val cachedFrontier = contiguousCachedPrefix(cache, cacheKey, effectiveCapBytes)
                if (cachedFrontier > activeReadBytePosition.get()) {
                    activeReadBytePosition.set(cachedFrontier)
                }
                if (cursor >= effectiveCapBytes) {
                    iterationState = "cap_reached"
                    break
                }
                val hole = findNextUncachedHole(
                    cache = cache,
                    cacheKey = cacheKey,
                    start = cursor,
                    endExclusive = effectiveCapBytes
                )
                if (hole == null) {
                    iterationState = "idle_no_hole"
                    idleCycles++
                    if (idleCycles > PREFETCH_MAX_IDLE_CYCLES) break
                    Thread.sleep(PREFETCH_IDLE_SLEEP_MS)
                    continue
                }
                idleCycles = 0

                val holeStart = hole.first
                holeStartForTrace = holeStart
                val holeLength = hole.second
                val activeGuardEnd = activeReadBytePosition.get().coerceAtLeast(0L) + PREFETCH_ACTIVE_GUARD_BYTES
                if (holeStart < activeGuardEnd) {
                    iterationState = "rebase_active_guard"
                    cursor = activeGuardEnd.coerceAtMost(effectiveCapBytes)
                    Thread.sleep(PREFETCH_REBASE_SLEEP_MS)
                    continue
                }

                val writeLength = minOf(
                    PREFETCH_BLOCK_BYTES,
                    holeLength,
                    effectiveCapBytes - holeStart
                )
                writeLengthForTrace = writeLength
                if (writeLength <= 0L) {
                    iterationState = "advance_zero_length"
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
                val writeStartMs = android.os.SystemClock.elapsedRealtime()
                runCatching {
                    writer.cache()
                    emitCacheWriteLatency(
                        durationMs = android.os.SystemClock.elapsedRealtime() - writeStartMs,
                        holeStart = holeStart,
                        writeLength = writeLength,
                        success = true
                    )
                }.onFailure { error ->
                    emitCacheWriteLatency(
                        durationMs = android.os.SystemClock.elapsedRealtime() - writeStartMs,
                        holeStart = holeStart,
                        writeLength = writeLength,
                        success = false,
                        error = error
                    )
                    if (!prefetchStop.get()) {
                        Log.w(
                            TAG,
                            "VOD warm-ahead failed at offset=${holeStart / 1024L / 1024L}MB len=${writeLength / 1024L / 1024L}MB",
                            error
                        )
                    }
                }
                activePrefetchWriter = null
                iterationState = "cache_write"
                cursor = (holeStart + writeLength).coerceAtMost(effectiveCapBytes)
            } finally {
                emitWarmAheadLoopIteration(
                    durationMs = android.os.SystemClock.elapsedRealtime() - iterationStartMs,
                    state = iterationState,
                    paused = paused,
                    holeStart = holeStartForTrace,
                    writeLength = writeLengthForTrace
                )
            }
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
            .setCacheKeyFactory(stableCacheKeyFactory)
            .setCacheWriteDataSinkFactory(dataSinkFactory)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setEventListener(PlaybackTraceCacheEventListener(if (blockOnCache) "warm_ahead" else "progressive"))
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

    // WP3 — sessionId of the last-opened MediaSourceSession. Stored so
    // `endPlaybackTraceSession()` can close the correct writer on player
    // release without racing a concurrent `createMediaSource()` call.
    @Volatile
    private var currentPlaybackTraceSessionId: String? = null

    // WP3 — optional binder injected by the runtime controller so the
    // collector's rebuffer/decode events can be tagged with the same
    // sessionId as the playback-trace JSONL. Null until set by the caller.
    @Volatile
    var playbackTraceSessionBinder: ((String?) -> Unit)? = null

    /**
     * WP3 — open a playback-trace MediaSourceSession for this [createMediaSource]
     * call. Assembles the full [com.nexio.tv.instrumentation.SessionHeader]
     * (round-3 fields included) and emits `playback_session_started` via
     * [com.nexio.tv.instrumentation.PlaybackTracer.beginSession]. Best-effort:
     * any failure is caught and logged — instrumentation must never crash
     * playback.
     */
    private fun openPlaybackTraceSession(
        url: String,
        branch: String,
        envelope: com.nexio.tv.data.repository.benchmark.CapabilityEnvelope?,
        cacheActive: Boolean
    ) {
        if (!com.nexio.tv.instrumentation.PlaybackTracer.enabled) return
        runCatching {
            val sessionId = java.util.UUID.randomUUID().toString()
            val startedAtNanos = android.os.SystemClock.elapsedRealtimeNanos()
            val assetKeyHash = sha256Hex(url).take(12)
            val envelopeChunk = envelope?.maxSafeUrgentChunkBytes
                ?: com.nexio.tv.data.repository.benchmark.CapabilityEnvelope.DEFAULT.maxSafeUrgentChunkBytes
            val factoryArgs = com.nexio.tv.instrumentation.FactoryArgs(
                activeChunkBytes = envelopeChunk,
                parallelConnections = parallelConnectionCount,
                keepBehindBytes = 0L,
                bootstrapBytes = 0L
            )
            val initialPolicy = com.nexio.tv.instrumentation.PolicySnapshot(
                urgentWorkers = parallelConnectionCount,
                prefetchWorkers = 1,
                urgentChunkBytes = envelopeChunk,
                prefetchChunkBytes = envelopeChunk,
                source = if (envelope != null) "envelope" else "fallback"
            )
            val clientIdentity = buildClientIdentitySnapshot()
            val device = buildDeviceProvenance()
            val header = com.nexio.tv.instrumentation.SessionHeader(
                sessionId = sessionId,
                startedAtNanos = startedAtNanos,
                assetKeyHash = assetKeyHash,
                serviceKey = null,
                provider = null,
                benchmarkResultId = null,
                benchmarkSource = null,
                envelopePresent = envelope != null,
                runtimeHintsPresent = false,
                specializationState = "baseline",
                hintServiceKey = null,
                hintHostScope = null,
                hintTransportClass = null,
                hintAgeMs = null,
                hintFreshnessBand = null,
                specializationMismatchReason = null,
                observedHostScope = null,
                observedTransportClass = null,
                branch = branch,
                cacheActive = cacheActive,
                warmAheadFactory = if (cacheActive) "okHttp" else null,
                factoryArgs = factoryArgs,
                initialPolicy = initialPolicy,
                clientIdentity = clientIdentity,
                device = device
            )
            com.nexio.tv.instrumentation.PlaybackTracer.beginSession(header)
            currentPlaybackTraceSessionId = sessionId
            // WP3 — bind the sessionId on the runtime collector so its
            // rebuffer/decode events can be tagged with the same id.
            playbackTraceSessionBinder?.invoke(sessionId)
        }.onFailure { t ->
            Log.w(TAG, "openPlaybackTraceSession failed", t)
        }
    }

    /** WP3 — close the currently-open playback-trace session, if any. */
    internal fun endPlaybackTraceSession() {
        val sid = currentPlaybackTraceSessionId ?: return
        runCatching {
            com.nexio.tv.instrumentation.PlaybackTracer.endSession(sid)
        }
        currentPlaybackTraceSessionId = null
        playbackTraceSessionBinder?.invoke(null)
    }

    private fun buildClientIdentitySnapshot(): com.nexio.tv.instrumentation.ClientIdentitySnapshot {
        val client = okHttpClient
        return if (client != null) {
            com.nexio.tv.instrumentation.ClientIdentitySnapshot(
                playbackClientHash = Integer.toHexString(System.identityHashCode(client)),
                dispatcherMaxRequests = client.dispatcher.maxRequests,
                dispatcherMaxRequestsPerHost = client.dispatcher.maxRequestsPerHost,
                dispatcherQueuedCalls = client.dispatcher.queuedCallsCount(),
                dispatcherRunningCalls = client.dispatcher.runningCallsCount(),
                connectionPoolIdleCount = client.connectionPool.idleConnectionCount(),
                connectionPoolTotalCount = client.connectionPool.connectionCount(),
                callTimeoutMs = client.callTimeoutMillis.toLong(),
                readTimeoutMs = client.readTimeoutMillis.toLong(),
                writeTimeoutMs = client.writeTimeoutMillis.toLong(),
                connectTimeoutMs = client.connectTimeoutMillis.toLong()
            )
        } else {
            com.nexio.tv.instrumentation.ClientIdentitySnapshot(
                playbackClientHash = "null",
                dispatcherMaxRequests = 0,
                dispatcherMaxRequestsPerHost = 0,
                dispatcherQueuedCalls = 0,
                dispatcherRunningCalls = 0,
                connectionPoolIdleCount = 0,
                connectionPoolTotalCount = 0,
                callTimeoutMs = 0L,
                readTimeoutMs = 0L,
                writeTimeoutMs = 0L,
                connectTimeoutMs = 0L
            )
        }
    }

    private fun buildDeviceProvenance(): com.nexio.tv.instrumentation.DeviceProvenance {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val pm = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        return com.nexio.tv.instrumentation.DeviceProvenance(
            deviceModel = android.os.Build.MODEL ?: "unknown",
            deviceManufacturer = android.os.Build.MANUFACTURER ?: "unknown",
            androidRelease = android.os.Build.VERSION.RELEASE ?: "unknown",
            androidSdkInt = android.os.Build.VERSION.SDK_INT,
            appVersionName = pm?.versionName ?: "unknown",
            appVersionCode = pm?.let { info ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            } ?: 0L,
            gitSha = null,
            memoryClass = am?.memoryClass ?: 0,
            largeMemoryClass = am?.largeMemoryClass ?: 0,
            isLowRamDevice = am?.isLowRamDevice ?: false,
            networkType = "unknown",
            networkTransportHash = null
        )
    }

    private fun sha256Hex(input: String): String {
        return runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString(separator = "") { b -> "%02x".format(b) }
        }.getOrElse { "00000000" }
    }

    companion object {
        private const val TAG = "PlayerMediaSource"
        private const val ENABLE_VOD_CACHE = true
        // v2: migrated from URL-based to stable identity cache keys (StableCacheKeyFactory)
        private const val VOD_CACHE_DIR = "player_vod_cache_v2"
        private const val VOD_CACHE_FREE_SPACE_RESERVE_BYTES = 1024L * 1024L * 1024L
        private const val MIN_RUNTIME_VOD_CACHE_BYTES = 1L * 1024L * 1024L
        private const val PREFETCH_BLOCK_BYTES = 16L * 1024L * 1024L
        private const val PREFETCH_ACTIVE_GUARD_BYTES = 8L * 1024L * 1024L
        private const val PREFETCH_REBASE_SLEEP_MS = 100L
        // After a rebuffer event, pause background chunk prefetch in PRDS for this many ms.
        // Mirrors `RollingHorizonManager.HEALTH_NO_REBUFFER_MS` so the warm-ahead writer and
        // the parallel-range prefetch lane gate on the same recovery window.
        private const val REBUFFER_PREFETCH_PAUSE_MS = 10_000L
        private const val PREFETCH_IDLE_SLEEP_MS = 250L
        private const val PREFETCH_MAX_IDLE_CYCLES = 20
        // How long to sleep between rebuffer-check polls in the warm-ahead loop.
        private const val WARM_AHEAD_REBUFFER_POLL_MS = 250L
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

internal class PlaybackTraceCacheEventListener(
    private val source: String
) : CacheDataSource.EventListener {
    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.CACHE, "cache_event") {
            putString("source", source)
            putString("kind", "cached_bytes_read")
            putLong("cacheSizeBytes", cacheSizeBytes)
            putLong("cachedBytesRead", cachedBytesRead)
        }
    }

    override fun onCacheIgnored(reason: Int) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.CACHE, "cache_event") {
            putString("source", source)
            putString("kind", "cache_ignored")
            putInt("reason", reason)
        }
    }
}
