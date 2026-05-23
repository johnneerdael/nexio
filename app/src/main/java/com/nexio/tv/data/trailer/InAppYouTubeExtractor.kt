package com.nexio.tv.data.trailer

import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.youtube.YouTubeTrailerIntegrationProvider
import com.nexio.tv.data.integration.youtube.transport.YouTubeTrailerTransport
import com.nexio.tv.data.integration.youtube.transport.YouTubeTrailerTransportCall
import com.nexio.tv.data.local.TrailerSettingsDataStore
import com.nexio.tv.data.trailer.cipher.PlayerSourceCache
import com.nexio.tv.data.trailer.cipher.SignatureCipherDecoder
import com.nexio.tv.data.trailer.jsdecrypt.NsigDescrambler
import com.nexio.tv.data.trailer.potoken.PoTokenProvider
import com.nexio.tv.data.trailer.potoken.PoTokenResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import java.net.URL
import java.util.Base64
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "InAppYouTubeExtractor"
private const val EXTRACTOR_TIMEOUT_MS = 30_000L
// Cap concurrent in-flight YouTube watch-page extractions. Heap dumps showed up
// to 3 simultaneous 1.26 MiB HTML char[] allocations + 271 KiB InnerTube JSON
// responses (each fetch holds the body String for the duration of the regex
// parse + JSON decode). Multiple consumers (Hero, Screensaver, Detail) can
// race a fetch storm; the semaphore serializes the heaviest portion at a small
// permit count without changing per-call throughput beyond the parallelism cap.
private const val EXTRACTOR_MAX_CONCURRENCY = 1
private const val WEB_EMBEDDED_CLIENT_VERSION = "1.20260122.01.00"
private const val YOUTUBEI_WEB_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
private const val YOUTUBEI_GAPIS_V1_URL = "https://youtubei.googleapis.com/youtubei/v1"
private const val YOUTUBEI_GAPIS_PLAYER_URL = "https://youtubei.googleapis.com/youtubei/v1/player"
private const val FETCH_IOS_CLIENT = false
private val NEWPIPE_ADAPTIVE_CLIENTS = listOf("android")
private val CLIENT_SPECIFIC_VISITOR_DATA_KEYS = setOf("android", "ios", "web_embedded")
private const val DEFAULT_TRAILER_MAX_ADAPTIVE_HEIGHT = 1080
private const val ANDROID_REEL_MAX_ATTEMPTS = 8

private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
private val QUALITY_LABEL_REGEX = Regex("(\\d{2,4})p")
private val MIME_CODECS_REGEX = Regex("""codecs="([^"]+)"""")

internal data class YouTubeClient(
    val key: String,
    val id: String,
    val version: String,
    val userAgent: String,
    val context: Map<String, Any>,
    val priority: Int
)

private data class WatchConfig(
    val visitorData: String?
)

internal data class StreamCandidate(
    val client: String,
    val priority: Int,
    val url: String,
    val score: Double,
    val hasN: Boolean,
    val itag: String,
    val height: Int,
    val fps: Int,
    val ext: String,
    val codec: String = "",
    val mimeType: String = "",
    val width: Int = 0,
    val bitrate: Long = 0,
    val audioSampleRate: Int = 0,
    val initStart: Long = -1,
    val initEnd: Long = -1,
    val indexStart: Long = -1,
    val indexEnd: Long = -1,
    val durationMs: Long = -1
)

private data class ManifestBestVariant(
    val url: String,
    val width: Int,
    val height: Int,
    val bandwidth: Long
)

private data class ManifestCandidate(
    val client: String,
    val priority: Int,
    val manifestUrl: String,
    val selectedVariantUrl: String,
    val height: Int,
    val bandwidth: Long
)

private val DEFAULT_YOUTUBE_FIELDS = mapOf(
    "accept-language" to YOUTUBE_STABLE_ACCEPT_LANGUAGE,
    "user-agent" to YOUTUBE_STABLE_WEB_USER_AGENT,
    "origin" to YOUTUBE_STABLE_ORIGIN,
    "referer" to YOUTUBE_STABLE_REFERER
)

internal class NonEnglishYouTubeTrailerException(
    val languageCode: String?,
    val trailerTitle: String?,
    val originalLanguage: String? = null
) : IllegalStateException(
    "Rejected YouTube trailer language=${languageCode.orEmpty()} " +
        "originalLanguage=${originalLanguage.orEmpty()} title=${trailerTitle.orEmpty()}"
)

internal fun sortTrailerCandidatesForPlayback(items: List<StreamCandidate>): List<StreamCandidate> {
    return items.sortedWith(
        compareBy<StreamCandidate> { trailerContainerPreference(it.ext) }
            .thenBy { if (it.hasN) 1 else 0 }
            .thenByDescending { it.score }
            .thenBy { it.priority }
    )
}

internal fun selectPreferredCombinedTrailerUrl(
    manifestUrl: String?,
    progressiveUrl: String?
): String? {
    val normalizedManifestUrl = manifestUrl?.takeIf { it.isNotBlank() }
    if (normalizedManifestUrl != null) {
        return normalizedManifestUrl
    }
    return progressiveUrl?.takeIf { it.isNotBlank() }
}

private fun trailerContainerPreference(ext: String): Int {
    return when (ext.lowercase()) {
        "mp4", "m4a" -> 0
        "webm" -> 1
        else -> 2
    }
}

internal fun preferTrailerCompatibleVideo(
    items: List<StreamCandidate>,
    maxHeight: Int = DEFAULT_TRAILER_MAX_ADAPTIVE_HEIGHT
): List<StreamCandidate> {
    if (items.isEmpty()) return items

    val cappedAny = items.filter { it.height in 1..maxHeight }
    if (cappedAny.isEmpty()) return items

    val highestHeight = cappedAny.maxOf { it.height }
    val highest = cappedAny.filter { it.height == highestHeight }
    return preferLessExpensiveTrailerCodec(highest)
}

private fun preferLessExpensiveTrailerCodec(items: List<StreamCandidate>): List<StreamCandidate> {
    if (items.isEmpty()) return items

    val h264 = items.filter { it.codec.startsWith("avc1", ignoreCase = true) }
    if (h264.isNotEmpty()) return h264

    val vp9 = items.filter { candidate ->
        candidate.codec.equals("vp9", ignoreCase = true) ||
            candidate.codec.startsWith("vp09", ignoreCase = true)
    }
    if (vp9.isNotEmpty()) return vp9

    val nonAv1 = items.filterNot { it.codec.startsWith("av01", ignoreCase = true) }
    if (nonAv1.isNotEmpty()) return nonAv1

    return items
}

internal val CLIENTS_FOR_TEST: List<YouTubeClient> get() = ACTIVE_STREAM_CLIENTS

private val ANDROID_CLIENT = YouTubeClient(
    // NewPipeExtractor's Android track. Without an Android poToken it
    // uses youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch and
    // then unwraps playerResponse from the reel response.
    key = "android",
    id = "3",
    version = "21.03.36",
    userAgent = "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip",
    context = mapOf(
        "clientName" to "ANDROID",
        "clientVersion" to "21.03.36",
        "clientScreen" to "WATCH",
        "osName" to "Android",
        "osVersion" to "16",
        "platform" to "MOBILE",
        "androidSdkVersion" to 36,
        "hl" to "en-US",
        "gl" to "US",
        "utcOffsetMinutes" to 0
    ),
    priority = 0
)

private val IOS_CLIENT = YouTubeClient(
    // NewPipe prefers iOS for HLS: non-iOS clients do not reliably return hlsManifestUrl.
    key = "ios",
    id = "5",
    version = "21.03.2",
    userAgent = "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)",
    context = mapOf(
        "clientName" to "IOS",
        "clientVersion" to "21.03.2",
        "clientScreen" to "WATCH",
        "deviceMake" to "Apple",
        "deviceModel" to "iPhone16,2",
        "osName" to "iOS",
        "osVersion" to "18.7.2.22H124",
        "platform" to "MOBILE",
        "hl" to "en-US",
        "gl" to "US",
        "utcOffsetMinutes" to 0
    ),
    priority = 1
)

private val ACTIVE_STREAM_CLIENTS = buildList {
    add(ANDROID_CLIENT)
    if (FETCH_IOS_CLIENT) add(IOS_CLIENT)
}

internal fun lookupClientUserAgent(clientKey: String?): String? =
    clientKey?.let { key -> ACTIVE_STREAM_CLIENTS.firstOrNull { it.key == key }?.userAgent }

internal fun lookupClientUserAgentForTest(clientKey: String?): String? =
    lookupClientUserAgent(clientKey)

@Singleton
class InAppYouTubeExtractor @Inject constructor(
    private val integrationProvider: YouTubeTrailerIntegrationProvider,
    private val directTransport: YouTubeTrailerTransport,
    private val playerSourceCache: PlayerSourceCache,
    private val poTokenProvider: PoTokenProvider,
    private val nsigDescrambler: NsigDescrambler,
    private val trailerSettingsDataStore: TrailerSettingsDataStore
) {
    private val gson = Gson()
    private val concurrencyLimiter = Semaphore(EXTRACTOR_MAX_CONCURRENCY)

    suspend fun extractPlaybackSource(
        youtubeUrl: String,
        originalLanguage: String? = null
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        if (youtubeUrl.isBlank()) return@withContext null

        Log.d(TAG, "Starting Kotlin extraction for ${summarizeUrl(youtubeUrl)}")
        val source = try {
            withTimeout(EXTRACTOR_TIMEOUT_MS) {
                concurrencyLimiter.withPermit {
                    extractPlaybackSourceInternal(youtubeUrl, originalLanguage)
                }
            }
        } catch (error: NonEnglishYouTubeTrailerException) {
            Log.w(
                TAG,
                "Rejected trailer for ${summarizeUrl(youtubeUrl)} " +
                    "language=${error.languageCode.orEmpty()} " +
                    "originalLanguage=${error.originalLanguage.orEmpty()} " +
                    "title=${error.trailerTitle.orEmpty()}"
            )
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Kotlin extractor failed for $youtubeUrl: ${error.message}")
            null
        }

        if (source == null) {
            Log.w(TAG, "Kotlin extraction returned no playable source for ${summarizeUrl(youtubeUrl)}")
        } else {
            Log.d(
                TAG,
                "Kotlin extraction success for ${summarizeUrl(youtubeUrl)} " +
                    "(video=${summarizeUrl(source.videoUrl)}, audioPresent=${!source.audioUrl.isNullOrBlank()})"
            )
        }

        source
    }

    private suspend fun extractPlaybackSourceInternal(
        youtubeUrl: String,
        originalLanguage: String?
    ): TrailerPlaybackSource? {
        val videoId = extractVideoId(youtubeUrl) ?: return null
        val trailerMaxHeight = trailerSettingsDataStore.settings.first().maxQuality.maxHeight

        val watchUrl = "https://www.youtube.com/watch?v=$videoId&hl=en"
        val watchResponse = fetchTransport(
            url = watchUrl,
            method = "GET",
            headers = DEFAULT_YOUTUBE_FIELDS
        )
        if (!watchResponse.ok) {
            throw IllegalStateException("Failed to fetch watch page (${watchResponse.status})")
        }

        val watchConfig = getWatchConfig(watchResponse.body)
        return coroutineScope {
        // Kick off the player-JS fetch + cipher manifest parse in parallel
        // with the per-client player API calls. If a future client returns
        // signatureCipher fields rather than direct URLs, entries are dropped
        // until the matching player manifest is available.
        val playerJsUrl = playerSourceCache.extractPlayerJsUrl(watchResponse.body)
        val playerJsDeferred = async {
            playerJsUrl?.let { playerSourceCache.getPlayerJs(it) }
        }
        val cipherManifestDeferred = async {
            playerJsUrl?.let { playerSourceCache.getCipherManifest(it) }
        }

        val progressive = mutableListOf<StreamCandidate>()
        val adaptiveVideo = mutableListOf<StreamCandidate>()
        val adaptiveAudio = mutableListOf<StreamCandidate>()
        val manifestUrls = mutableListOf<Triple<String, Int, String>>()
        var resolvedTrailerTitle: String? = null
        val captionsPerClient = mutableMapOf<String, List<YouTubeCaptionTrack>>()
        val poTokensByClient = mutableMapOf<String, PoTokenResult>()
        val visitorDataByClient = mutableMapOf<String, String>()
        val contentPlaybackNonce = generateContentPlaybackNonce()

        for (client in ACTIVE_STREAM_CLIENTS.sortedBy { it.priority }) {
            try {
                val requestClient = client
                val progressiveBefore = progressive.size
                val adaptiveVideoBefore = adaptiveVideo.size
                val adaptiveAudioBefore = adaptiveAudio.size
                val manifestBefore = manifestUrls.size
                var progressiveMissingUrl = 0
                var progressiveCipherDecoded = 0
                var adaptiveVideoMissingUrl = 0
                var adaptiveAudioMissingUrl = 0
                var adaptiveVideoCipherDecoded = 0
                var adaptiveAudioCipherDecoded = 0
                var firstMissingAdaptiveFormat: String? = null
                val embedUrl = if (requestClient.key == "web_embedded") {
                    webEmbedUrl(videoId)
                } else {
                    null
                }
                val poTokenResult = if (requestClient.key == "web_embedded") {
                    poTokenProvider.getWebClientPoToken(
                        videoId = videoId,
                        webClientName = requestClient.context["clientName"].toString(),
                        webClientId = requestClient.id,
                        webClientVersion = requestClient.version,
                        webClientScreen = requestClient.context["clientScreen"]?.toString(),
                        embedUrl = embedUrl
                    )
                } else {
                    null
                }
                if (requestClient.key == "web_embedded" && poTokenResult == null) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Skipping WEB_EMBEDDED client: poToken unavailable")
                    continue
                }
                // WEB_EMBEDDED ships signatureCipher
                // fields — the player API wants the matching
                // signatureTimestamp on the request so the response cipher
                // version aligns with the manifest we already fetched.
                // Non-cipher clients (iOS, ANDROID) omit it.
                val sigTimestampForClient = if (
                    requestClient.key == "web_embedded"
                ) {
                    cipherManifestDeferred.await()?.signatureTimestamp
                } else null
                var resolvedPlayerResponse: Map<*, *>? = null
                val maxAttempts = if (requestClient.key == "android") ANDROID_REEL_MAX_ATTEMPTS else 1
                for (attempt in 1..maxAttempts) {
                    val requestVisitorData = poTokenResult?.visitorData
                        ?: resolveClientVisitorData(
                            client = requestClient,
                            embedUrl = embedUrl,
                            fallbackVisitorData = watchConfig.visitorData,
                            cache = visitorDataByClient,
                            forceRefresh = attempt > 1
                        )
                    if (requestClient.key in CLIENT_SPECIFIC_VISITOR_DATA_KEYS && requestVisitorData.isNullOrBlank()) {
                        if (BuildConfig.DEBUG) {
                            Log.w(TAG, "Skipping ${requestClient.key}: client-specific visitorData unavailable")
                        }
                        break
                    }

                    val candidateResponse = fetchPlayerResponse(
                        videoId = videoId,
                        client = requestClient,
                        visitorData = requestVisitorData,
                        cookieHeader = null,
                        signatureTimestamp = sigTimestampForClient,
                        poTokenResult = poTokenResult,
                        contentPlaybackNonce = contentPlaybackNonce,
                        embedUrl = embedUrl
                    )
                    if (
                        requestClient.key == "android" &&
                        candidateResponse.hasAdaptiveFormatsWithoutPlayableUrl()
                    ) {
                        if (attempt < maxAttempts) {
                            Log.w(
                                TAG,
                                "Android reel response videoId=$videoId attempt=$attempt " +
                                    "has adaptive formats without URLs; retrying with fresh visitorData"
                            )
                        } else {
                            Log.w(
                                TAG,
                                "Android reel response videoId=$videoId attempt=$attempt " +
                                    "still has adaptive formats without URLs; dropping response instead of " +
                                    "publishing progressive fallback"
                            )
                        }
                        continue
                    }
                    resolvedPlayerResponse = candidateResponse
                    break
                }
                val playerResponse = resolvedPlayerResponse ?: continue
                if (poTokenResult != null) {
                    poTokensByClient[requestClient.key] = poTokenResult
                }

                if (resolvedTrailerTitle.isNullOrBlank()) {
                    resolvedTrailerTitle = extractYouTubeTrailerTitle(playerResponse)
                }
                val captions = extractYouTubeCaptionTracks(playerResponse)
                if (captions.isNotEmpty()) {
                    captionsPerClient[requestClient.key] = captions
                }
                extractDefaultYouTubeAudioLanguageCode(playerResponse)
                    ?.takeIf { code -> !isYouTubeTrailerLanguageAcceptable(code, originalLanguage) }
                    ?.let { languageCode ->
                        throw NonEnglishYouTubeTrailerException(
                            languageCode = languageCode,
                            trailerTitle = resolvedTrailerTitle,
                            originalLanguage = originalLanguage
                        )
                    }

                val streamingData = playerResponse.mapValue("streamingData")
                if (streamingData == null) {
                    val playability = playerResponse.mapValue("playabilityStatus")
                    Log.w(
                        TAG,
                        "Client ${requestClient.key} returned no streamingData " +
                            "status=${playability?.stringValue("status").orEmpty()} " +
                            "reason=${playability?.stringValue("reason").orEmpty()}"
                    )
                    continue
                }
                val hlsManifestUrl = streamingData.stringValue("hlsManifestUrl")
                if (!hlsManifestUrl.isNullOrBlank()) {
                    manifestUrls += Triple(requestClient.key, requestClient.priority, hlsManifestUrl)
                }
                // Some clients can return DASH manifests. HLS is preferred
                // when both are present (priority + 10 on DASH).
                // Media3's DefaultMediaSourceFactory dispatches by URL/MIME
                // to DashMediaSource automatically.
                val dashManifestUrl = streamingData.stringValue("dashManifestUrl")
                if (!dashManifestUrl.isNullOrBlank()) {
                    manifestUrls += Triple(requestClient.key, requestClient.priority + 10, dashManifestUrl)
                }

                val formats = streamingData.listMapValue("formats")
                for (i in formats.indices) {
                    val format = formats[i]
                    val directUrl = format.stringValue("url")
                    val signatureCipher = format.stringValue("signatureCipher")
                        ?: format.stringValue("cipher")
                    val url = directUrl ?: run {
                        if (signatureCipher == null) {
                            progressiveMissingUrl++
                            return@run null
                        }
                        val manifest = cipherManifestDeferred.await() ?: return@run null
                        SignatureCipherDecoder.decode(signatureCipher, manifest)
                            ?.also { progressiveCipherDecoded++ }
                    } ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()
                    if (!mimeType.contains("video/") && mimeType.isNotBlank()) continue

                    val height = (format.numberValue("height")
                        ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble()
                        ?: 0.0).toInt()
                    val fps = (format.numberValue("fps") ?: 0.0).toInt()
                    val bitrate = format.numberValue("bitrate")
                        ?: format.numberValue("averageBitrate")
                        ?: 0.0

                    val streamUrl = appendYouTubeQueryParameter(url, "cpn", contentPlaybackNonce)
                    progressive += StreamCandidate(
                        client = requestClient.key,
                        priority = requestClient.priority,
                        url = streamUrl,
                        score = videoScore(height, fps, bitrate),
                        hasN = hasNParam(url),
                        itag = format.stringValue("itag").orEmpty(),
                        height = height,
                        fps = fps,
                        ext = if (mimeType.contains("webm")) "webm" else "mp4",
                        codec = parsePrimaryCodec(mimeType),
                        mimeType = parseContainerMimeType(mimeType),
                        width = (format.numberValue("width") ?: 0.0).toInt(),
                        bitrate = bitrate.toLong(),
                        initStart = format.rangeStart("initRange"),
                        initEnd = format.rangeEnd("initRange"),
                        indexStart = format.rangeStart("indexRange"),
                        indexEnd = format.rangeEnd("indexRange"),
                        durationMs = format.durationMs()
                    )
                }

                val adaptiveFormats = streamingData.listMapValue("adaptiveFormats")
                for (i in adaptiveFormats.indices) {
                    val format = adaptiveFormats[i]
                    val mimeType = format.stringValue("mimeType").orEmpty()
                    val hasVideo = mimeType.contains("video/")
                    val hasAudio = mimeType.contains("audio/") || mimeType.startsWith("audio/")
                    val directUrl = format.stringValue("url")
                    val signatureCipher = format.stringValue("signatureCipher")
                        ?: format.stringValue("cipher")
                    val resolvedUrl = directUrl ?: run {
                        if (signatureCipher == null) {
                            if (hasVideo) {
                                adaptiveVideoMissingUrl++
                            } else if (hasAudio) {
                                adaptiveAudioMissingUrl++
                            }
                            if (firstMissingAdaptiveFormat == null && (hasVideo || hasAudio)) {
                                firstMissingAdaptiveFormat =
                                    "itag=${format.stringValue("itag").orEmpty()} " +
                                        "mime=${mimeType.take(80)} " +
                                        "keys=${format.keysSummary()}"
                            }
                            return@run null
                        }
                        val manifest = cipherManifestDeferred.await() ?: return@run null
                        SignatureCipherDecoder.decode(signatureCipher, manifest)
                            ?.also {
                                if (hasVideo) {
                                    adaptiveVideoCipherDecoded++
                                } else if (hasAudio) {
                                    adaptiveAudioCipherDecoded++
                                }
                            }
                    } ?: continue
                    // Verify cipher-resolved URLs against HEAD + tail byte.
                    // Direct iOS/ANDROID URLs skip the round trip (low
                    // historical 404 rate; the extra latency would hurt
                    // startup with no benefit).
                    val url = if (directUrl == null) {
                        verifyContentLength(resolvedUrl, signedClientUserAgent = client.userAgent)
                            ?.let { resolvedUrl } ?: continue
                    } else {
                        resolvedUrl
                    }
                    if (hasVideo) {
                        val height = (format.numberValue("height")
                            ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble()
                            ?: 0.0).toInt()
                        val fps = (format.numberValue("fps") ?: 0.0).toInt()
                        val bitrate = format.numberValue("bitrate")
                            ?: format.numberValue("averageBitrate")
                            ?: 0.0

                        val streamUrl = appendYouTubeQueryParameter(url, "cpn", contentPlaybackNonce)
                        adaptiveVideo += StreamCandidate(
                            client = requestClient.key,
                            priority = requestClient.priority,
                            url = streamUrl,
                            score = videoScore(height, fps, bitrate),
                            hasN = hasNParam(url),
                            itag = format.stringValue("itag").orEmpty(),
                            height = height,
                            fps = fps,
                            ext = if (mimeType.contains("webm")) "webm" else "mp4",
                            codec = parsePrimaryCodec(mimeType),
                            mimeType = parseContainerMimeType(mimeType),
                            width = (format.numberValue("width") ?: 0.0).toInt(),
                            bitrate = bitrate.toLong(),
                            initStart = format.rangeStart("initRange"),
                            initEnd = format.rangeEnd("initRange"),
                            indexStart = format.rangeStart("indexRange"),
                            indexEnd = format.rangeEnd("indexRange"),
                            durationMs = format.durationMs()
                        )
                    } else if (hasAudio) {
                        val bitrate = format.numberValue("bitrate")
                            ?: format.numberValue("averageBitrate")
                            ?: 0.0
                        val asr = format.numberValue("audioSampleRate") ?: 0.0

                        val streamUrl = appendYouTubeQueryParameter(url, "cpn", contentPlaybackNonce)
                        adaptiveAudio += StreamCandidate(
                            client = requestClient.key,
                            priority = requestClient.priority,
                            url = streamUrl,
                            score = audioScore(bitrate, asr),
                            hasN = hasNParam(url),
                            itag = format.stringValue("itag").orEmpty(),
                            height = 0,
                            fps = 0,
                            ext = if (mimeType.contains("webm")) "webm" else "m4a",
                            codec = parsePrimaryCodec(mimeType),
                            mimeType = parseContainerMimeType(mimeType),
                            bitrate = bitrate.toLong(),
                            audioSampleRate = asr.toInt(),
                            initStart = format.rangeStart("initRange"),
                            initEnd = format.rangeEnd("initRange"),
                            indexStart = format.rangeStart("indexRange"),
                            indexEnd = format.rangeEnd("indexRange"),
                            durationMs = format.durationMs()
                        )
                    }
                }
                Log.d(
                    TAG,
                    "Client ${requestClient.key} videoId=$videoId formats " +
                        "progressive=${progressive.size - progressiveBefore} " +
                        "adaptiveVideo=${adaptiveVideo.size - adaptiveVideoBefore} " +
                        "adaptiveAudio=${adaptiveAudio.size - adaptiveAudioBefore} " +
                        "manifests=${manifestUrls.size - manifestBefore} " +
                        "progressiveMissingUrl=$progressiveMissingUrl " +
                        "progressiveCipherDecoded=$progressiveCipherDecoded " +
                        "adaptiveVideoMissingUrl=$adaptiveVideoMissingUrl " +
                        "adaptiveAudioMissingUrl=$adaptiveAudioMissingUrl " +
                        "adaptiveVideoCipherDecoded=$adaptiveVideoCipherDecoded " +
                        "adaptiveAudioCipherDecoded=$adaptiveAudioCipherDecoded " +
                        "firstMissingAdaptive=${firstMissingAdaptiveFormat.orEmpty()}"
                )
                if (
                    hasPreferredAdaptivePairForClient(
                        requestClient.key,
                        adaptiveVideo,
                        adaptiveAudio,
                        trailerMaxHeight
                    )
                ) {
                    break
                }
            } catch (error: Exception) {
                Log.w(TAG, "Client ${client.key} failed: ${error.message}")
            }
        }

        if (manifestUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            return@coroutineScope null
        }

        var bestManifest: ManifestCandidate? = null
        for ((clientKey, priority, manifestUrl) in manifestUrls) {
            try {
                val variant = parseHlsManifest(manifestUrl) ?: continue
                val candidate = ManifestCandidate(
                    client = clientKey,
                    priority = priority,
                    manifestUrl = manifestUrl,
                    selectedVariantUrl = variant.url,
                    height = variant.height,
                    bandwidth = variant.bandwidth
                )
                if (
                    bestManifest == null ||
                    candidate.height > bestManifest.height ||
                    (candidate.height == bestManifest.height && candidate.bandwidth > bestManifest.bandwidth)
                ) {
                    bestManifest = candidate
                }
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Manifest parse failed: ${error.message}")
                }
            }
        }

        val playerJs = playerJsDeferred.await()
        val bestProgressive = sortTrailerCandidatesForPlayback(progressive).firstOrNull()
            ?.descrambleNsig(playerJs)
        val bestAdaptivePair = pickBestAdaptivePair(adaptiveVideo, adaptiveAudio, trailerMaxHeight)
        val bestVideo = bestAdaptivePair?.first?.descrambleNsig(playerJs)
        val bestAudio = bestAdaptivePair?.second?.descrambleNsig(playerJs)
        val adaptiveClientKey = bestVideo?.client?.takeIf { clientKey ->
            bestAudio != null && clientKey == bestAudio.client
        }
        val useDirectAdaptive = adaptiveClientKey in NEWPIPE_ADAPTIVE_CLIENTS
        val combinedUrl = selectPreferredCombinedTrailerUrl(
            manifestUrl = bestManifest?.manifestUrl,
            progressiveUrl = bestProgressive?.url
        )
        // Mirrors the priority order in selectPreferredTrailerPlaybackSource:
        // combined first (HLS manifest or progressive), split adaptive as
        // fallback.
        val resolvedClientKey = when {
            useDirectAdaptive -> bestVideo?.client
            combinedUrl != null && combinedUrl == bestManifest?.manifestUrl -> bestManifest.client
            combinedUrl != null && combinedUrl == bestProgressive?.url -> bestProgressive.client
            bestVideo != null -> bestVideo.client
            else -> null
        }
        val resolvedUserAgent = lookupClientUserAgent(resolvedClientKey)

        // Prefer Android's caption tracks over iOS — Android responses
        // sometimes carry more languages because iOS filters by platform
        // availability. NewPipeExtractor falls back to iOS captions only
        // when Android's set is empty; we mirror that ordering.
        val resolvedCaptionTracks: List<YouTubeCaptionTrack> = captionsPerClient["android"]
            ?: captionsPerClient["ios"]
            ?: emptyList()

        val playbackSource = selectPreferredTrailerPlaybackSource(
            combinedUrl = combinedUrl?.let { resolveReachableUrl(it) },
            adaptiveVideoUrl = bestVideo?.let {
                if (useDirectAdaptive) it.asDashDataUri() ?: it.url else resolveReachableUrl(it.url)
            },
            adaptiveAudioUrl = bestAudio?.let {
                if (useDirectAdaptive) it.asDashDataUri() ?: it.url else resolveReachableUrl(it.url)
            },
            userAgent = resolvedUserAgent,
            streamingDataPoToken = poTokensByClient[adaptiveClientKey]?.streamingDataPoToken,
            preferAdaptive = useDirectAdaptive
        )?.copy(
            captions = resolvedCaptionTracks,
            signingClientKey = resolvedClientKey
        ) ?: return@coroutineScope null

        Log.d(
            TAG,
            "Kotlin selection videoId=$videoId video=${summarizeUrl(playbackSource.videoUrl)} " +
                "audioPresent=${!playbackSource.audioUrl.isNullOrBlank()} " +
                "sourceType=${if (playbackSource.audioUrl.isNullOrBlank()) "combined" else "adaptive"} " +
                "manifestAvailable=${bestManifest != null} " +
                "webEmbeddedPoToken=${poTokensByClient.containsKey("web_embedded")} " +
                "adaptiveClient=${adaptiveClientKey.orEmpty()} " +
                "maxHeight=${trailerMaxHeight}p " +
                "bestProgressive=${bestProgressive?.height ?: 0}p/${bestProgressive?.client.orEmpty()} " +
                "bestAdaptiveVideo=${bestVideo?.height ?: 0}p/${bestVideo?.client.orEmpty()} " +
                "bestAdaptiveAudio=${bestAudio?.client.orEmpty()} " +
                "bestProgressiveExt=${bestProgressive?.ext.orEmpty()} " +
                "bestAdaptiveVideoExt=${bestVideo?.ext.orEmpty()} " +
                "bestAdaptiveVideoItag=${bestVideo?.itag.orEmpty()} " +
                "bestAdaptiveVideoCodec=${bestVideo?.codec.orEmpty()} " +
                "progressiveCount=${progressive.size} " +
                "adaptiveVideoCount=${adaptiveVideo.size} adaptiveAudioCount=${adaptiveAudio.size}"
        )

        playbackSource
        } // end coroutineScope
    }

    private suspend fun StreamCandidate.descrambleNsig(playerJs: String?): StreamCandidate =
        copy(url = nsigDescrambler.descrambleUrl(url, playerJs))

    private fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (VIDEO_ID_REGEX.matches(trimmed)) return trimmed

        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        return runCatching {
            val uri = Uri.parse(normalized)
            val host = uri.host?.lowercase().orEmpty()
            if (host.endsWith("youtu.be")) {
                val id = uri.pathSegments.firstOrNull()
                if (!id.isNullOrBlank() && VIDEO_ID_REGEX.matches(id)) {
                    return id
                }
            }

            val queryId = uri.getQueryParameter("v")
            if (!queryId.isNullOrBlank() && VIDEO_ID_REGEX.matches(queryId)) {
                return queryId
            }

            val segments = uri.pathSegments
            if (segments.size >= 2) {
                val first = segments[0]
                val second = segments[1]
                if ((first == "embed" || first == "shorts" || first == "live") && VIDEO_ID_REGEX.matches(second)) {
                    return second
                }
            }

            null
        }.getOrNull()
    }

    private fun getWatchConfig(html: String): WatchConfig {
        val visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)
        return WatchConfig(
            visitorData = visitorData
        )
    }

    private suspend fun resolveClientVisitorData(
        client: YouTubeClient,
        embedUrl: String?,
        fallbackVisitorData: String?,
        cache: MutableMap<String, String>,
        forceRefresh: Boolean = false
    ): String? {
        if (client.key !in CLIENT_SPECIFIC_VISITOR_DATA_KEYS) {
            return fallbackVisitorData
        }

        val cacheKey = "${client.key}|${client.version}|${embedUrl.orEmpty()}"
        val cached = cache[cacheKey]
        if (!forceRefresh && !cached.isNullOrBlank()) return cached

        val fetched = try {
            fetchVisitorDataForClient(client, embedUrl)
        } catch (error: Exception) {
            Log.w(TAG, "visitor_id ${client.key} failed: ${error.message}")
            null
        }
        if (!fetched.isNullOrBlank()) {
            cache[cacheKey] = fetched
            return fetched
        }
        return null
    }

    private suspend fun fetchVisitorDataForClient(
        client: YouTubeClient,
        embedUrl: String?
    ): String? {
        val context = buildInnertubeContext(
            client = client,
            visitorData = null,
            embedUrl = if (client.key == "web_embedded") embedUrl else null
        )
        val endpoint = when (client.key) {
            "android" -> "https://youtubei.googleapis.com/youtubei/v1/visitor_id?prettyPrint=false"
            else -> "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"
        }
        val response = fetchTransport(
            url = endpoint,
            method = "POST",
            headers = buildPlayerRequestHeaders(
                client = client,
                visitorData = null,
                cookieHeader = null
            ),
            body = gson.toJson(mapOf("context" to context))
        )
        if (!response.ok) {
            throw IllegalStateException("visitor_id ${client.key} failed (${response.status})")
        }
        val parsed = gson.fromJson(response.body, Map::class.java)
        return parsed
            ?.mapValue("responseContext")
            ?.stringValue("visitorData")
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun fetchPlayerResponse(
        videoId: String,
        client: YouTubeClient,
        visitorData: String?,
        cookieHeader: String?,
        signatureTimestamp: String? = null,
        poTokenResult: PoTokenResult? = null,
        contentPlaybackNonce: String? = null,
        embedUrl: String? = null
    ): Map<*, *> {
        val useAndroidReelRequest = client.key == "android" && poTokenResult == null
        val endpoint = when (client.key) {
            "android" -> {
                if (useAndroidReelRequest) {
                    // NewPipeExtractor's no-poToken Android path uses the Shorts/Reels
                    // endpoint and unwraps playerResponse from it. Calling /player with
                    // the same client currently gives reduced streams for some trailers.
                    "$YOUTUBEI_GAPIS_V1_URL/reel/reel_item_watch?prettyPrint=false" +
                        "&t=${generateTParameter()}&id=${Uri.encode(videoId)}&\$fields=playerResponse"
                } else {
                    "$YOUTUBEI_GAPIS_PLAYER_URL?prettyPrint=false" +
                        "&t=${generateTParameter()}&id=${Uri.encode(videoId)}"
                }
            }
            "ios" -> {
                // NewPipeExtractor routes non-web clients through GAPIS and
                // includes the mobile-only `t` and `id` query parameters.
                "$YOUTUBEI_GAPIS_PLAYER_URL?prettyPrint=false" +
                    "&t=${generateTParameter()}&id=${Uri.encode(videoId)}"
            }
            "web_embedded" -> {
                "$YOUTUBEI_WEB_PLAYER_URL?prettyPrint=false"
            }
            else -> {
                throw IllegalArgumentException("Unsupported YouTube client ${client.key}")
            }
        }

        val headers = buildPlayerRequestHeaders(client, visitorData, cookieHeader)

        val contentPlaybackContext = buildMap<String, Any> {
            put("html5Preference", "HTML5_PREF_WANTS")
            signatureTimestamp?.toIntOrNull()?.let { put("signatureTimestamp", it) }
            if (client.key == "web_embedded") {
                put("referer", embedUrl ?: webEmbedUrl(videoId))
            }
        }
        val context = buildInnertubeContext(client, visitorData, embedUrl)
        val playerRequestPayload = buildMap<String, Any> {
            put("videoId", videoId)
            if (!contentPlaybackNonce.isNullOrBlank()) {
                put("cpn", contentPlaybackNonce)
            }
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", context)
            put("playbackContext", mapOf("contentPlaybackContext" to contentPlaybackContext))
            if (poTokenResult != null) {
                put("serviceIntegrityDimensions", mapOf("poToken" to poTokenResult.playerRequestPoToken))
            }
        }
        val payload = if (useAndroidReelRequest) {
            val reelPlayerRequest = buildMap<String, Any> {
                put("videoId", videoId)
                if (!contentPlaybackNonce.isNullOrBlank()) {
                    put("cpn", contentPlaybackNonce)
                }
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            mapOf(
                "context" to context,
                "playerRequest" to reelPlayerRequest,
                "disablePlayerResponse" to false
            )
        } else {
            playerRequestPayload
        }

        val response = fetchTransport(
            url = endpoint,
            method = "POST",
            headers = headers,
            body = gson.toJson(payload)
        )
        if (!response.ok) {
            val preview = response.body.take(200)
            throw IllegalStateException("player API ${client.key} failed (${response.status}): $preview")
        }

        val parsed = gson.fromJson(response.body, Map::class.java)
        val playerResponse = if (useAndroidReelRequest) {
            parsed?.mapValue("playerResponse") ?: emptyMap<String, Any>()
        } else {
            parsed ?: emptyMap<String, Any>()
        }
        if (isPlayerResponseNotValid(playerResponse, videoId)) {
            throw IllegalStateException("player API ${client.key} returned substituted playerResponse")
        }
        return playerResponse
    }

    private fun isPlayerResponseNotValid(playerResponse: Map<*, *>, videoId: String): Boolean {
        return playerResponse
            .mapValue("videoDetails")
            ?.stringValue("videoId") != videoId
    }

    private fun buildPlayerRequestHeaders(
        client: YouTubeClient,
        visitorData: String?,
        cookieHeader: String?
    ): Map<String, String> = when (client.key) {
        "android", "ios" -> {
            buildMap {
                put("content-type", "application/json")
                put("user-agent", client.userAgent)
                put("x-goog-api-format-version", "2")
                cookieHeader
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { put("Cookie", it) }
            }
        }
        else -> {
            buildMap {
                putAll(
                    buildYouTubeWireProperties(
                        profile = YouTubeWireProfile.WEB,
                        userAgent = client.userAgent,
                        cookieHeader = cookieHeader
                    )
                )
                put("content-type", "application/json")
                put("x-youtube-client-name", client.id)
                put("x-youtube-client-version", client.version)
                if (client.key == "web_embedded") {
                    put("origin", "https://www.youtube.com")
                    put("referer", "https://www.youtube.com")
                }
                if (!visitorData.isNullOrBlank()) put("x-goog-visitor-id", visitorData)
            }
        }
    }

    private fun buildInnertubeContext(
        client: YouTubeClient,
        visitorData: String?,
        embedUrl: String?
    ): Map<String, Any> = buildMap {
        val clientContext = buildMap<String, Any> {
            putAll(client.context)
            if (!visitorData.isNullOrBlank()) {
                put("visitorData", visitorData)
            }
        }
        put("client", clientContext)
        if (client.key == "web_embedded" && !embedUrl.isNullOrBlank()) {
            put("thirdParty", mapOf("embedUrl" to embedUrl))
        }
        put("request", mapOf("internalExperimentFlags" to emptyList<Any>(), "useSsl" to true))
        put("user", mapOf("lockedSafetyMode" to false))
    }

    private suspend fun parseHlsManifest(manifestUrl: String): ManifestBestVariant? {
        val response = fetchTransport(
            url = manifestUrl,
            method = "GET",
            headers = DEFAULT_YOUTUBE_FIELDS
        )
        if (!response.ok) {
            throw IllegalStateException("Failed to fetch HLS manifest (${response.status})")
        }

        val lines = response.body
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        var bestVariant: ManifestBestVariant? = null

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue

            val attrs = parseHlsAttributeList(line)
            val nextLine = lines.getOrNull(i + 1) ?: continue
            if (nextLine.startsWith("#")) continue

            val resolution = attrs["RESOLUTION"].orEmpty()
            val (width, height) = parseResolution(resolution)
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L

            val candidate = ManifestBestVariant(
                url = absolutizeUrl(manifestUrl, nextLine),
                width = width,
                height = height,
                bandwidth = bandwidth
            )

            if (
                bestVariant == null ||
                candidate.height > bestVariant.height ||
                (candidate.height == bestVariant.height && candidate.bandwidth > bestVariant.bandwidth) ||
                (
                    candidate.height == bestVariant.height &&
                        candidate.bandwidth == bestVariant.bandwidth &&
                        candidate.width > bestVariant.width
                    )
            ) {
                bestVariant = candidate
            }
        }

        return bestVariant
    }

    private fun parseHlsAttributeList(line: String): Map<String, String> {
        val index = line.indexOf(':')
        if (index == -1) return emptyMap()

        val raw = line.substring(index + 1)
        val out = LinkedHashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inKey = true
        var inQuote = false

        for (ch in raw) {
            if (inKey) {
                if (ch == '=') {
                    inKey = false
                } else {
                    key.append(ch)
                }
                continue
            }

            if (ch == '"') {
                inQuote = !inQuote
                continue
            }

            if (ch == ',' && !inQuote) {
                val k = key.toString().trim()
                if (k.isNotEmpty()) {
                    out[k] = value.toString().trim()
                }
                key.clear()
                value.clear()
                inKey = true
                continue
            }

            value.append(ch)
        }

        val lastKey = key.toString().trim()
        if (lastKey.isNotEmpty()) {
            out[lastKey] = value.toString().trim()
        }

        return out
    }

    private fun parseResolution(raw: String): Pair<Int, Int> {
        val parts = raw.split('x')
        if (parts.size != 2) return 0 to 0
        val width = parts[0].toIntOrNull() ?: 0
        val height = parts[1].toIntOrNull() ?: 0
        return width to height
    }

    private fun parseQualityLabel(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        val match = QUALITY_LABEL_REGEX.find(label) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun parsePrimaryCodec(mimeType: String): String {
        val codecs = MIME_CODECS_REGEX.find(mimeType)?.groupValues?.getOrNull(1).orEmpty()
        return codecs.substringBefore(',').trim()
    }

    private fun parseContainerMimeType(mimeType: String): String =
        mimeType.substringBefore(';').trim()

    private fun Map<*, *>.rangeStart(key: String): Long =
        mapValue(key)?.stringValue("start")?.toLongOrNull() ?: -1L

    private fun Map<*, *>.rangeEnd(key: String): Long =
        mapValue(key)?.stringValue("end")?.toLongOrNull() ?: -1L

    private fun Map<*, *>.durationMs(): Long =
        stringValue("approxDurationMs")?.toLongOrNull()
            ?: stringValue("durationMs")?.toLongOrNull()
            ?: -1L

    private fun hasNParam(url: String): Boolean {
        return runCatching {
            !Uri.parse(url).getQueryParameter("n").isNullOrBlank()
        }.getOrDefault(false)
    }

    private fun videoScore(height: Int, fps: Int, bitrate: Double): Double {
        return height * 1_000_000_000.0 + fps * 1_000_000.0 + bitrate
    }

    private fun audioScore(bitrate: Double, audioSampleRate: Double): Double {
        return bitrate * 1_000_000.0 + audioSampleRate
    }

    private fun pickBestForClient(items: List<StreamCandidate>, clientKey: String): StreamCandidate? {
        val sameClient = items.filter { it.client == clientKey }
        if (sameClient.isNotEmpty()) {
            return sortTrailerCandidatesForPlayback(sameClient).firstOrNull()
        }
        return sortTrailerCandidatesForPlayback(items).firstOrNull()
    }

    private fun hasPreferredAdaptivePairForClient(
        clientKey: String,
        videoItems: List<StreamCandidate>,
        audioItems: List<StreamCandidate>,
        maxHeight: Int = DEFAULT_TRAILER_MAX_ADAPTIVE_HEIGHT
    ): Boolean {
        if (clientKey !in NEWPIPE_ADAPTIVE_CLIENTS) return false
        val video = preferTrailerCompatibleVideo(videoItems.filter { it.client == clientKey }, maxHeight)
        return video.isNotEmpty() && audioItems.any { it.client == clientKey }
    }

    private fun pickBestAdaptivePair(
        videoItems: List<StreamCandidate>,
        audioItems: List<StreamCandidate>,
        maxHeight: Int = DEFAULT_TRAILER_MAX_ADAPTIVE_HEIGHT
    ): Pair<StreamCandidate, StreamCandidate>? {
        val compatibleVideoItems = preferTrailerCompatibleVideo(videoItems, maxHeight)
        for (i in NEWPIPE_ADAPTIVE_CLIENTS.indices) {
            val clientKey = NEWPIPE_ADAPTIVE_CLIENTS[i]
            val video = pickBestForClient(compatibleVideoItems, clientKey)?.takeIf { it.client == clientKey }
            val audio = pickBestForClient(audioItems, clientKey)?.takeIf { it.client == clientKey }
            if (video != null && audio != null) {
                return video to audio
            }
        }
        return null
    }

    private fun StreamCandidate.asDashDataUri(): String? {
        if (initStart < 0 || initEnd < 0 || indexStart < 0 || indexEnd < 0) return null
        if (durationMs <= 0L || bitrate <= 0L || mimeType.isBlank() || codec.isBlank()) return null
        val manifest = buildSingleSegmentDashManifest(this)
        val encoded = Base64.getEncoder().encodeToString(manifest.toByteArray(Charsets.UTF_8))
        return "data:application/dash+xml;base64,$encoded"
    }

    private suspend fun resolveReachableUrl(url: String): String {
        if (!url.contains("googlevideo.com")) return url
        val uri = Uri.parse(url)
        val mnParam = uri.getQueryParameter("mn") ?: return url
        val servers = mnParam.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (servers.size < 2) return url

        val candidates = mutableListOf(url)
        for (server in servers) {
            val mviIndex = servers.indexOf(server)
            val altHost = uri.host?.replaceFirst(
                Regex("^rr\\d+---"),
                "rr${mviIndex + 1}---"
            )?.replaceFirst(
                Regex("sn-[a-z0-9]+-[a-z0-9]+"),
                server
            ) ?: continue
            if (altHost == uri.host) continue
            candidates += url.replace(uri.host!!, altHost)
        }

        if (candidates.size == 1) return candidates[0]
        return withTimeoutOrNull(2_000L) {
            coroutineScope {
                val result = CompletableDeferred<String>()
                val jobs = candidates.map { candidate ->
                    launch {
                        val reachable = isUrlReachable(candidate)
                        Log.d(TAG, "CDN probe: ${Uri.parse(candidate).host} -> $reachable")
                        if (reachable) result.complete(candidate)
                    }
                }
                val winner = result.await()
                jobs.forEach { it.cancel() }
                winner
            }
        } ?: url
    }

    private suspend fun isUrlReachable(url: String): Boolean {
        return integrationProvider.probe(
            url = url,
            headers = DEFAULT_YOUTUBE_FIELDS + ("Range" to "bytes=0-0")
        ).also { reachable ->
            Log.d(TAG, "CDN probe result: ${Uri.parse(url).host} -> $reachable")
        }
    }

    private fun absolutizeUrl(baseUrl: String, maybeRelative: String): String {
        return runCatching {
            URL(URL(baseUrl), maybeRelative).toString()
        }.getOrElse { maybeRelative }
    }

    private fun summarizeUrl(url: String): String {
        return runCatching {
            val parsed = URL(url)
            val host = parsed.host ?: "unknown-host"
            val path = parsed.path ?: "/"
            "$host$path"
        }.getOrDefault(url.take(80))
    }

    private suspend fun fetchTransport(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String? = null
    ): TrailerHttpResponse {
        if (url.startsWith(YOUTUBEI_GAPIS_V1_URL)) {
            return withContext(Dispatchers.IO) {
                val response = directTransport.execute(
                    YouTubeTrailerTransportCall(
                        url = url,
                        method = method,
                        headers = headers,
                        body = body
                    )
                )
                TrailerHttpResponse(
                    ok = response.isSuccessful,
                    status = response.statusCode,
                    statusText = response.statusText,
                    url = response.url,
                    body = response.body
                )
            }
        }
        return when (
            val result = integrationProvider.fetch(
                YouTubeTrailerTransportCall(
                    url = url,
                    method = method,
                    headers = headers,
                    body = body
                )
            )
        ) {
            is IntegrationCallResult.Success -> TrailerHttpResponse(
                ok = result.value.isSuccessful,
                status = result.value.statusCode,
                statusText = result.value.statusText,
                url = result.value.url,
                body = result.value.body
            )
            is IntegrationCallResult.HttpError -> TrailerHttpResponse(
                ok = false,
                status = result.statusCode,
                statusText = result.reason.orEmpty(),
                url = url,
                body = result.reason.orEmpty()
            )
            is IntegrationCallResult.NetworkError -> throw result.throwable
            IntegrationCallResult.Missing -> TrailerHttpResponse(
                ok = false,
                status = 404,
                statusText = "Missing",
                url = url,
                body = ""
            )
        }
    }
}

private data class TrailerHttpResponse(
    val ok: Boolean,
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String
)

internal fun extractDefaultYouTubeAudioLanguageCode(playerResponse: Map<*, *>): String? {
    val captions = playerResponse.mapValue("captions")
        ?.mapValue("playerCaptionsTracklistRenderer")
        ?: return null
    val captionTracks = captions.listMapValue("captionTracks")
    if (captionTracks.isEmpty()) return null

    val preferredIndices = buildList {
        val audioTracks = captions.listMapValue("audioTracks")
        val defaultAudioTrackIndex = captions.numberValue("defaultAudioTrackIndex")?.toInt()
        defaultAudioTrackIndex
            ?.let { audioTracks.getOrNull(it) }
            ?.let { audioTrack ->
                audioTrack.numberValue("defaultCaptionTrackIndex")?.toInt()?.let(::add)
                audioTrack.intListValue("captionTrackIndices").forEach(::add)
            }
        if (captionTracks.size == 1) {
            add(0)
        }
    }

    preferredIndices.forEach { index ->
        captionTracks.getOrNull(index)
            ?.stringValue("languageCode")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }

    return null
}

internal fun extractYouTubeTrailerTitle(playerResponse: Map<*, *>): String? {
    return playerResponse.mapValue("videoDetails")
        ?.stringValue("title")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

internal fun extractYouTubeCaptionTracks(playerResponse: Map<*, *>): List<YouTubeCaptionTrack> {
    val tracklist = playerResponse.mapValue("captions")
        ?.mapValue("playerCaptionsTracklistRenderer")
        ?: return emptyList()
    val rawTracks = tracklist.listMapValue("captionTracks")
    if (rawTracks.isEmpty()) return emptyList()
    return rawTracks.mapNotNull { track ->
        val baseUrl = track.stringValue("baseUrl")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val languageCode = track.stringValue("languageCode")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // Mirror NewPipe: strip any pre-existing fmt/tlang from the baseUrl so we
        // don't end up with duplicate params after `buildTrailerSubtitleVttUrl`
        // appends its own. Also detect auto-generated tracks via vssId (a.*)
        // rather than the `kind` field — both signal ASR but `vssId` is the
        // more reliable indicator in observed responses.
        val cleanedBaseUrl = baseUrl
            .replace(Regex("&fmt=[^&]*"), "")
            .replace(Regex("&tlang=[^&]*"), "")
        val vssId = track.stringValue("vssId")?.trim().orEmpty()
        val kind = track.stringValue("kind")?.takeIf { it.isNotBlank() }
        val effectiveKind = when {
            kind != null -> kind
            vssId.startsWith("a.") -> "asr"
            else -> null
        }
        val name = track.mapValue("name")?.stringValue("simpleText")
        val isTranslatable = (track["isTranslatable"] as? Boolean) ?: false
        YouTubeCaptionTrack(
            baseUrl = cleanedBaseUrl,
            languageCode = languageCode,
            languageName = name,
            kind = effectiveKind,
            isTranslatable = isTranslatable
        )
    }
}

internal fun isEnglishYouTubeLanguageCode(languageCode: String?): Boolean {
    val normalized = languageCode
        ?.trim()
        ?.lowercase()
        ?.replace('_', '-')
        ?.takeIf { it.isNotEmpty() }
        ?: return false
    return normalized == "en" || normalized.startsWith("en-")
}

private fun appendYouTubeQueryParameter(url: String, key: String, value: String?): String {
    if (value.isNullOrBlank()) return url
    return try {
        val uri = Uri.parse(url)
        if (uri.getQueryParameter(key) != null) {
            url
        } else {
            uri.buildUpon().appendQueryParameter(key, value).build().toString()
        }
    } catch (_: Exception) {
        url
    }
}

private fun webEmbedUrl(videoId: String): String =
    "https://www.youtube.com/watch?v=$videoId"

internal fun buildSingleSegmentDashManifest(stream: StreamCandidate): String {
    val durationSeconds = String.format(Locale.ENGLISH, "%.3f", stream.durationMs / 1000.0)
    val escapedUrl = xmlEscape(stream.url)
    val escapedMime = xmlEscape(stream.mimeType)
    val escapedCodec = xmlEscape(stream.codec)
    val sampleRate = stream.audioSampleRate.takeIf { it > 0 }
    val videoAttributes = if (stream.height > 0) {
        val widthAttr = stream.width.takeIf { it > 0 }?.let { """ width="$it"""" }.orEmpty()
        val frameRateAttr = stream.fps.takeIf { it > 0 }?.let { """ frameRate="$it"""" }.orEmpty()
        """$widthAttr height="${stream.height}"$frameRateAttr"""
    } else {
        ""
    }
    val audioAttributes = sampleRate?.let { """ audioSamplingRate="$it"""" }.orEmpty()
    val audioChannelConfiguration = if (stream.height == 0) {
        """
          <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="2"/>
        """.trimIndent()
    } else {
        ""
    }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xmlns="urn:mpeg:DASH:schema:MPD:2011"
             xsi:schemaLocation="urn:mpeg:DASH:schema:MPD:2011 DASH-MPD.xsd"
             minBufferTime="PT1.500S"
             profiles="urn:mpeg:dash:profile:full:2011"
             type="static"
             mediaPresentationDuration="PT${durationSeconds}S">
          <Period>
            <AdaptationSet id="0" mimeType="$escapedMime" subsegmentAlignment="true">
              <Role schemeIdUri="urn:mpeg:DASH:role:2011" value="main"/>
              <Representation id="${stream.itag.substringBefore('.')}" codecs="$escapedCodec" startWithSAP="1" maxPlayoutRate="1" bandwidth="${stream.bitrate}"$videoAttributes$audioAttributes>
        $audioChannelConfiguration
                <BaseURL>$escapedUrl</BaseURL>
                <SegmentBase indexRange="${stream.indexStart}-${stream.indexEnd}">
                  <Initialization range="${stream.initStart}-${stream.initEnd}"/>
                </SegmentBase>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()
}

private fun xmlEscape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

internal fun isYouTubeTrailerLanguageAcceptable(
    trailerLanguageCode: String?,
    originalLanguage: String?
): Boolean {
    if (isEnglishYouTubeLanguageCode(trailerLanguageCode)) return true
    return youtubeLanguageMatchesOriginalLanguage(trailerLanguageCode, originalLanguage)
}

internal fun youtubeLanguageMatchesOriginalLanguage(
    trailerLanguageCode: String?,
    originalLanguage: String?
): Boolean {
    val trailer = normalizeBaseLanguageCode(trailerLanguageCode) ?: return false
    val original = normalizeBaseLanguageCode(originalLanguage) ?: return false
    return trailer == original
}

private fun normalizeBaseLanguageCode(code: String?): String? {
    return code
        ?.trim()
        ?.lowercase()
        ?.replace('_', '-')
        ?.substringBefore('-')
        ?.takeIf { it.isNotEmpty() }
}

private fun Map<*, *>.mapValue(key: String): Map<*, *>? {
    return valueForJsonKey(key) as? Map<*, *>
}

private fun Map<*, *>.listMapValue(key: String): List<Map<*, *>> {
    val raw = valueForJsonKey(key) as? List<*> ?: return emptyList()
    return raw.mapNotNull { it as? Map<*, *> }
}

private fun Map<*, *>.stringValue(key: String): String? {
    val value = valueForJsonKey(key) ?: return null
    return value.toString()
}

private fun Map<*, *>.intListValue(key: String): List<Int> {
    val value = valueForJsonKey(key) as? List<*> ?: return emptyList()
    return value.mapNotNull {
        when (it) {
            is Number -> it.toInt()
            is String -> it.toIntOrNull()
            else -> null
        }
    }
}

private fun Map<*, *>.numberValue(key: String): Double? {
    val value = valueForJsonKey(key) ?: return null
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}

private fun Map<*, *>.valueForJsonKey(key: String): Any? {
    this[key]?.let { return it }
    return entries.firstOrNull { (entryKey, _) ->
        val normalizedKey = entryKey
            ?.toString()
            ?.filterNot { ch -> Character.isISOControl(ch) }
        normalizedKey == key
    }?.value
}

private fun Map<*, *>.hasAdaptiveFormatsWithoutPlayableUrl(): Boolean {
    val adaptiveFormats = mapValue("streamingData")
        ?.listMapValue("adaptiveFormats")
        ?: return false
    if (adaptiveFormats.isEmpty()) return false

    var mediaFormatCount = 0
    var playableCount = 0
    for (i in adaptiveFormats.indices) {
        val format = adaptiveFormats[i]
        val mimeType = format.stringValue("mimeType").orEmpty()
        val isMedia = mimeType.contains("video/") || mimeType.contains("audio/")
        if (!isMedia) continue
        mediaFormatCount++
        if (
            !format.stringValue("url").isNullOrBlank() ||
            !format.stringValue("signatureCipher").isNullOrBlank() ||
            !format.stringValue("cipher").isNullOrBlank()
        ) {
            playableCount++
        }
    }
    return mediaFormatCount > 0 && playableCount == 0
}

private fun Map<*, *>.keysSummary(): String =
    keys
        .joinToString(separator = ",", limit = 12, truncated = "...") { key ->
            key
                ?.toString()
                ?.filterNot { ch -> Character.isISOControl(ch) }
                .orEmpty()
        }
