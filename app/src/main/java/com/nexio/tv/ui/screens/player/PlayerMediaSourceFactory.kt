@file:Suppress("DEPRECATION")

package com.nexio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
import com.nexio.tv.data.local.VodCacheSizeMode
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
    @Volatile private var currentVodCacheUrl: String? = null
    @Volatile private var currentVodCacheResolvedUrl: ResolvedVodCacheUrl? = null
    @Volatile private var currentVodCacheActive: Boolean = false
    @Volatile private var currentProgressiveUpstreamFactory: DataSource.Factory? = null
    @Volatile private var currentWarmAheadUpstreamFactory: DataSource.Factory? = null
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
    var vodCacheSizeMode: VodCacheSizeMode = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE
        set(value) {
            field = value
            if (value != VodCacheSizeMode.ON) {
                stopVodWarmAhead()
            }
        }
    var vodCacheWarmAheadEnabled: Boolean = PlayerSettings.DEFAULT_VOD_CACHE_WARM_AHEAD_ENABLED
        set(value) {
            field = value
            if (!value) {
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
        if (currentVodCacheUrl != null && currentVodCacheUrl != url) {
            stopVodWarmAhead()
        }
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
        val useVodCache = ENABLE_VOD_CACHE &&
            vodCacheSizeMode == VodCacheSizeMode.ON &&
            !isHls &&
            !isDash &&
            shouldUseVodCache(url)
        val warmAheadEnabledForStream = VodWarmAheadPolicy.shouldStartWarmAhead(
            useVodCache = useVodCache,
            warmAheadEnabled = vodCacheWarmAheadEnabled
        )
        val progressiveUpstreamFactory = selectProgressiveUpstreamFactory(
            url = url,
            isHls = isHls,
            isDash = isDash,
            okHttpFactory = okHttpFactory,
            baseDataSourceFactory = baseDataSourceFactory,
            warmAheadEnabledForProfile = warmAheadEnabledForStream
        )
        val previousVodCacheActive = currentVodCacheActive
        currentVodCacheUrl = url
        currentVodCacheResolvedUrl = null
        currentVodCacheActive = false
        currentProgressiveUpstreamFactory = progressiveUpstreamFactory
        currentProgressiveIsEligibleForWarmAhead = warmAheadEnabledForStream
        if (useVodCache) {
            Log.d(
                TAG,
                "VOD warm-ahead policy enabled=$currentProgressiveIsEligibleForWarmAhead " +
                    "setting=$vodCacheWarmAheadEnabled writeThroughEnabled=true"
            )
        }
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
                    startVodCacheInitialization(context, vodCacheMaxBytes)
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
            currentWarmAheadUpstreamFactory = null
            progressiveUpstreamFactory
        }
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        resolvedMimeType?.let(mediaItemBuilder::setMimeType)
        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }

        val mediaItem = mediaItemBuilder.build()
        val extractorsFactory = customExtractorsFactory ?: createDefaultExtractorsFactory()
        val defaultFactory = DefaultMediaSourceFactory(progressiveFactory, extractorsFactory).apply {
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
        headers: Map<String, String> = emptyMap(),
        warmAheadEnabledForProfile: Boolean = false
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
            baseDataSourceFactory = baseDataSourceFactory,
            warmAheadEnabledForProfile = warmAheadEnabledForProfile
        )
    }

    internal fun createBenchmarkProgressiveDataSourceFactory(
        url: String,
        headers: Map<String, String>,
        parallelConnectionsEnabled: Boolean,
        parallelConnectionCount: Int,
        parallelChunkSizeMb: Int,
        vodCacheEnabled: Boolean,
        allowStartupBootstrapReuse: Boolean,
        transportSampleTimeMs: () -> Long,
        onTransportBytesDownloaded: (Long, Long) -> Unit
    ): DataSource.Factory {
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
        val progressiveUpstreamFactory = selectProgressiveUpstreamFactory(
            url = url,
            isHls = isHls,
            isDash = isDash,
            okHttpFactory = okHttpFactory,
            baseDataSourceFactory = baseDataSourceFactory,
            parallelConnectionsEnabled = parallelConnectionsEnabled,
            fallbackParallelConnectionCount = parallelConnectionCount,
            fallbackParallelChunkSizeMb = parallelChunkSizeMb,
            allowStartupBootstrapReuse = allowStartupBootstrapReuse,
            warmAheadEnabledForProfile = false,
            transportSampleTimeMs = transportSampleTimeMs,
            onTransportBytesDownloaded = onTransportBytesDownloaded
        )
        val useVodCache = ENABLE_VOD_CACHE &&
            vodCacheEnabled &&
            !isHls &&
            !isDash &&
            shouldUseVodCache(url)
        if (!useVodCache || isVodCacheDisabled) {
            return progressiveUpstreamFactory
        }

        val vodCacheMaxBytes = resolveVodCacheMaxBytes(context)
        maybeApplyLiveVodCacheCapIncrease(
            context = context,
            requestedMaxBytes = vodCacheMaxBytes,
            allowLiveReconfigure = true
        )
        val cache = getReadySimpleCache(vodCacheMaxBytes)
            ?: getAnySimpleCache()
            ?: runCatching {
                startVodCacheInitialization(context, vodCacheMaxBytes)
                getOrCreateSimpleCache(context, vodCacheMaxBytes)
            }.getOrElse { error ->
                isVodCacheDisabled = true
                Log.e(TAG, "Disabling VOD cache for benchmark after initialization failure", error)
                null
            }
        return if (cache != null) {
            buildVodCacheDataSourceFactory(progressiveUpstreamFactory, cache)
        } else {
            progressiveUpstreamFactory
        }
    }

    fun shutdown() {
        stopVodWarmAhead()
        prefetchExecutor.shutdownNow()
    }

    fun clearVodCache() {
        stopVodWarmAhead()
        clearVodCacheInternal(context)
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
            addKey(currentVodCacheResolvedUrl?.resolvedUrl)
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
        if (!shouldAttemptVodWarmAheadStart()) return
        val streamUrl = currentVodCacheUrl ?: return
        val upstreamFactory = currentWarmAheadUpstreamFactory ?: currentProgressiveUpstreamFactory ?: return
        val upstreamKind = warmAheadUpstreamKindForTesting(upstreamFactory)
        Log.d(
            TAG,
            "VOD warm-ahead starting: upstream=$upstreamKind writeThroughEnabled=true"
        )
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

    private fun shouldAttemptVodWarmAheadStart(): Boolean {
        if (!ENABLE_VOD_CACHE || isVodCacheDisabled) return false
        if (vodCacheSizeMode != VodCacheSizeMode.ON) return false
        if (!vodCacheWarmAheadEnabled) return false
        return currentProgressiveIsEligibleForWarmAhead && currentVodCacheActive
    }

    private fun resolveWarmAheadRequestUrl(streamUrl: String): String? {
        if (currentVodCacheUrl != streamUrl) return null
        val resolvedUrl = currentVodCacheResolvedUrl?.takeIf { it.playbackUrl == streamUrl }?.resolvedUrl
        if (currentVodCacheUrl != streamUrl) return null
        return resolvedUrl ?: streamUrl
    }

    internal fun setWarmAheadStateForTesting(
        streamUrl: String?,
        resolvedPlaybackUrl: String? = streamUrl,
        resolvedUrl: String?,
        eligible: Boolean,
        active: Boolean
    ) {
        currentVodCacheUrl = streamUrl
        currentVodCacheResolvedUrl = resolvedPlaybackUrl?.let {
            ResolvedVodCacheUrl(
                playbackUrl = it,
                resolvedUrl = resolvedUrl
            )
        }
        currentProgressiveIsEligibleForWarmAhead = eligible
        currentVodCacheActive = active
    }

    internal fun warmAheadRequestUrlForTesting(streamUrl: String): String? {
        return resolveWarmAheadRequestUrl(streamUrl)
    }

    internal fun shouldAttemptVodWarmAheadStartForTesting(): Boolean {
        return shouldAttemptVodWarmAheadStart()
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
            val liveUrl = resolveWarmAheadRequestUrl(streamUrl) ?: break
            val prefetchUri = runCatching { Uri.parse(liveUrl) }.getOrElse { Uri.parse(streamUrl) }
            val cacheKey = VodWarmAheadPolicy.warmAheadCacheKey(
                playbackStreamUrl = streamUrl,
                resolvedRequestUrl = liveUrl
            )
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

            val holeStart = hole.first
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
                .setKey(cacheKey)
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
            .setFragmentSize(VOD_CACHE_FRAGMENT_BYTES_FOR_TESTING)
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
        baseDataSourceFactory: DataSource.Factory,
        parallelConnectionsEnabled: Boolean = useParallelConnections,
        fallbackParallelConnectionCount: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
        fallbackParallelChunkSizeMb: Int = SAFE_DEFAULT_PARALLEL_CHUNK_SIZE_MB,
        allowStartupBootstrapReuse: Boolean = true,
        warmAheadEnabledForProfile: Boolean = false,
        transportSampleTimeMs: () -> Long = { SystemClock.elapsedRealtime() },
        onTransportBytesDownloaded: (Long, Long) -> Unit = { _, _ -> }
    ): DataSource.Factory {
        parallelStartupPrefetchUnlocked.set(!(parallelConnectionsEnabled && !isHls && !isDash))
        activeReadBytePosition.set(0L)
        return when {
            !usesHttpUpstream(url) -> {
                currentWarmAheadUpstreamFactory = null
                baseDataSourceFactory
            }
            parallelConnectionsEnabled && !isHls && !isDash -> {
                val profiles = resolveParallelProviderProfiles(
                    url = url,
                    warmAheadEnabledForStream = warmAheadEnabledForProfile,
                    fallbackConnectionCount = fallbackParallelConnectionCount,
                    fallbackChunkSizeMb = fallbackParallelChunkSizeMb
                )
                val resolvedUriCallback: (Uri?) -> Unit = { resolved ->
                    currentVodCacheResolvedUrl = ResolvedVodCacheUrl(
                        playbackUrl = url,
                        resolvedUrl = resolved?.toString()
                    )
                }
                val readPositionCallback: (Long) -> Unit = { position ->
                    activeReadBytePosition.accumulateAndGet(position) { current, next ->
                        if (next > current) next else current
                    }
                }
                currentWarmAheadUpstreamFactory = profiles.warmAhead?.let { warmAheadProfile ->
                    buildParallelRangeDataSourceFactory(
                        okHttpFactory = okHttpFactory,
                        profile = warmAheadProfile,
                        allowStartupBootstrapReuse = false,
                        transportSampleTimeMs = transportSampleTimeMs,
                        onTransportBytesDownloaded = onTransportBytesDownloaded,
                        onResolvedUri = resolvedUriCallback,
                        onReadPositionAdvanced = { }
                    )
                }
                buildParallelRangeDataSourceFactory(
                    okHttpFactory = okHttpFactory,
                    profile = profiles.playback,
                    allowStartupBootstrapReuse = allowStartupBootstrapReuse,
                    transportSampleTimeMs = transportSampleTimeMs,
                    onTransportBytesDownloaded = onTransportBytesDownloaded,
                    onResolvedUri = resolvedUriCallback,
                    onReadPositionAdvanced = readPositionCallback
                )
            }
            else -> {
                currentWarmAheadUpstreamFactory = null
                okHttpFactory
            }
        }
    }

    private fun buildParallelRangeDataSourceFactory(
        okHttpFactory: OkHttpDataSource.Factory,
        profile: ParallelProviderProfile,
        allowStartupBootstrapReuse: Boolean,
        transportSampleTimeMs: () -> Long,
        onTransportBytesDownloaded: (Long, Long) -> Unit,
        onResolvedUri: (Uri?) -> Unit,
        onReadPositionAdvanced: (Long) -> Unit
    ): ParallelRangeDataSource.Factory {
        return ParallelRangeDataSource.Factory(
            okHttpFactory,
            profile.connectionCount,
            profile.chunkSizeMb.toLong() * 1024L * 1024L,
            shouldAllowBackgroundPrefetch = { parallelStartupPrefetchUnlocked.get() },
            transportSampleTimeMs = transportSampleTimeMs,
            onTransportBytesDownloaded = onTransportBytesDownloaded,
            onResolvedUri = onResolvedUri,
            onReadPositionAdvanced = onReadPositionAdvanced,
            allowStartupBootstrapReuse = allowStartupBootstrapReuse
        )
    }

    internal fun parallelProviderProfileForTesting(url: String): Pair<Int, Int> {
        val profile = resolveParallelProviderProfiles(
            url = url,
            warmAheadEnabledForStream = false
        ).playback
        return profile.connectionCount to profile.chunkSizeMb
    }

    internal fun parallelProviderProfilesForTesting(
        url: String,
        warmAheadEnabledForStream: Boolean
    ): Pair<Pair<Int, Int>, Pair<Int, Int>?> {
        val profiles = resolveParallelProviderProfiles(
            url = url,
            warmAheadEnabledForStream = warmAheadEnabledForStream
        )
        return (profiles.playback.connectionCount to profiles.playback.chunkSizeMb) to
            profiles.warmAhead?.let { it.connectionCount to it.chunkSizeMb }
    }

    internal fun warmAheadProviderProfileForTesting(
        url: String,
        warmAheadEnabledForStream: Boolean
    ): Pair<Int, Int>? {
        val profile = resolveParallelProviderProfiles(
            url = url,
            warmAheadEnabledForStream = warmAheadEnabledForStream
        ).warmAhead
        return profile?.let { it.connectionCount to it.chunkSizeMb }
    }

    internal fun currentWarmAheadUpstreamKindForTesting(): String {
        val factory = currentWarmAheadUpstreamFactory ?: return "single"
        return warmAheadUpstreamKindForTesting(factory)
    }

    private data class ParallelProviderProfile(
        val connectionCount: Int,
        val chunkSizeMb: Int
    )

    private data class ParallelProviderProfiles(
        val playback: ParallelProviderProfile,
        val warmAhead: ParallelProviderProfile?
    )

    private data class ResolvedVodCacheUrl(
        val playbackUrl: String,
        val resolvedUrl: String?
    )

    private fun resolveParallelProviderProfiles(
        url: String,
        warmAheadEnabledForStream: Boolean,
        fallbackConnectionCount: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
        fallbackChunkSizeMb: Int = SAFE_DEFAULT_PARALLEL_CHUNK_SIZE_MB
    ): ParallelProviderProfiles {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.US) }
            .getOrDefault("")
        val isPremiumize =
            host.contains("premiumize") || host.startsWith("pm.") || host.contains(".pm.")
        val isRealDebrid =
            host.contains("real-debrid") ||
                host.contains("realdebrid") ||
                host.startsWith("rd.") ||
                host.contains(".rd.")

        if (warmAheadEnabledForStream) {
            return when {
                isPremiumize -> ParallelProviderProfiles(
                    playback = ParallelProviderProfile(connectionCount = 2, chunkSizeMb = 16),
                    warmAhead = ParallelProviderProfile(connectionCount = 1, chunkSizeMb = 16)
                )
                isRealDebrid -> ParallelProviderProfiles(
                    playback = ParallelProviderProfile(connectionCount = 1, chunkSizeMb = 24),
                    warmAhead = ParallelProviderProfile(connectionCount = 1, chunkSizeMb = 24)
                )
                else -> ParallelProviderProfiles(
                    playback = ParallelProviderProfile(
                        connectionCount = fallbackConnectionCount.coerceIn(
                            PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT,
                            PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
                        ),
                        chunkSizeMb = fallbackChunkSizeMb.coerceIn(
                            PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB,
                            PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB
                        )
                    ),
                    warmAhead = ParallelProviderProfile(connectionCount = 1, chunkSizeMb = 16)
                )
            }
        }

        return when {
            isPremiumize -> ParallelProviderProfiles(
                playback = ParallelProviderProfile(connectionCount = 3, chunkSizeMb = 16),
                warmAhead = null
            )
            isRealDebrid -> ParallelProviderProfiles(
                playback = ParallelProviderProfile(connectionCount = 2, chunkSizeMb = 24),
                warmAhead = null
            )
            else -> ParallelProviderProfiles(
                playback = ParallelProviderProfile(
                    connectionCount = fallbackConnectionCount.coerceIn(
                        PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT,
                        PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
                    ),
                    chunkSizeMb = fallbackChunkSizeMb.coerceIn(
                        PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB,
                        PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB
                    )
                ),
                warmAhead = null
            )
        }
    }

    private fun createDefaultExtractorsFactory(): ExtractorsFactory {
        return DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
    }

    companion object {
        private const val TAG = "PlayerMediaSource"
        private const val ENABLE_VOD_CACHE = true
        private const val VOD_CACHE_DIR = "player_vod_cache"
        private const val VOD_CACHE_FREE_SPACE_RESERVE_BYTES = 1024L * 1024L * 1024L
        private const val MIN_RUNTIME_VOD_CACHE_BYTES = 1L * 1024L * 1024L
        private const val PREFETCH_BLOCK_BYTES = 16L * 1024L * 1024L
        internal const val VOD_CACHE_FRAGMENT_BYTES_FOR_TESTING = 8L * 1024L * 1024L
        private const val PREFETCH_ACTIVE_GUARD_BYTES = 8L * 1024L * 1024L
        private const val PREFETCH_REBASE_SLEEP_MS = 100L
        private const val PREFETCH_IDLE_SLEEP_MS = 250L
        private const val PREFETCH_MAX_IDLE_CYCLES = 20
        private const val LIVE_CACHE_RECONFIGURE_MIN_DELTA_BYTES = 64L * 1024L * 1024L
        private const val SAFE_DEFAULT_PARALLEL_CHUNK_SIZE_MB = 24
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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

        private fun getReadySimpleCache(expectedMaxBytes: Long): SimpleCache? {
            val cache = sharedSimpleCache ?: return null
            return if (configuredVodCacheMaxBytes == expectedMaxBytes) cache else null
        }

        private fun getAnySimpleCache(): SimpleCache? = sharedSimpleCache

        private fun bytesToMb(bytes: Long): Long = bytes / (1024L * 1024L)

        internal fun warmAheadUpstreamKindForTesting(upstreamFactory: DataSource.Factory): String {
            return if (upstreamFactory is ParallelRangeDataSource.Factory) "prds" else "single"
        }

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
