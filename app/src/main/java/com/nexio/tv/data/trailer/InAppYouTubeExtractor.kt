package com.nexio.tv.data.trailer

import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.youtube.YouTubeTrailerIntegrationProvider
import com.nexio.tv.data.integration.youtube.transport.YouTubeTrailerTransportCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "InAppYouTubeExtractor"
private const val EXTRACTOR_TIMEOUT_MS = 30_000L
private const val PREFERRED_SEPARATE_CLIENT = "ios"
// Cap concurrent in-flight YouTube watch-page extractions. Heap dumps showed up
// to 3 simultaneous 1.26 MiB HTML char[] allocations + 271 KiB InnerTube JSON
// responses (each fetch holds the body String for the duration of the regex
// parse + JSON decode). Multiple consumers (Hero, Screensaver, Detail) can
// race a fetch storm; the semaphore serializes the heaviest portion at a small
// permit count without changing per-call throughput beyond the parallelism cap.
private const val EXTRACTOR_MAX_CONCURRENCY = 1

private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
private val API_KEY_REGEX = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
private val QUALITY_LABEL_REGEX = Regex("(\\d{2,4})p")

internal data class YouTubeClient(
    val key: String,
    val id: String,
    val version: String,
    val userAgent: String,
    val context: Map<String, Any>,
    val priority: Int
)

private data class WatchConfig(
    val apiKey: String?,
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
    val ext: String
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

internal val CLIENTS_FOR_TEST: List<YouTubeClient> get() = CLIENTS

private val CLIENTS = listOf(
    // NewPipe prefers iOS for HLS: non-iOS clients do not reliably return hlsManifestUrl.
    YouTubeClient(
        key = "ios",
        id = "5",
        version = "21.03.2",
        userAgent = "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)",
        // Mirrors NewPipeExtractor's iOS Innertube client info: osName="iOS"
        // (not "iPhone") and the matching 22H124 build, deviceMake="Apple",
        // platform="MOBILE". YouTube cross-checks these context fields against
        // the UA when signing HLS manifest URLs.
        context = mapOf(
            "clientName" to "IOS",
            "clientVersion" to "21.03.2",
            "deviceMake" to "Apple",
            "deviceModel" to "iPhone16,2",
            "osName" to "iOS",
            "osVersion" to "18.7.2.22H124",
            "platform" to "MOBILE",
            "hl" to "en",
            "gl" to "US",
            "utcOffsetMinutes" to 0
        ),
        priority = 0
    ),
    YouTubeClient(
        key = "android",
        id = "3",
        version = "21.03.36",
        userAgent = "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip",
        context = mapOf(
            "clientName" to "ANDROID",
            "clientVersion" to "21.03.36",
            "osName" to "Android",
            "osVersion" to "15",
            "platform" to "MOBILE",
            "androidSdkVersion" to 35,
            "hl" to "en",
            "gl" to "US"
        ),
        priority = 1
    )
)

internal fun lookupClientUserAgent(clientKey: String?): String? =
    clientKey?.let { key -> CLIENTS.firstOrNull { it.key == key }?.userAgent }

internal fun lookupClientUserAgentForTest(clientKey: String?): String? =
    lookupClientUserAgent(clientKey)

@Singleton
class InAppYouTubeExtractor @Inject constructor(
    private val integrationProvider: YouTubeTrailerIntegrationProvider
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
        val apiKey = watchConfig.apiKey
            ?: throw IllegalStateException("Unable to extract INNERTUBE_API_KEY")

        val progressive = mutableListOf<StreamCandidate>()
        val adaptiveVideo = mutableListOf<StreamCandidate>()
        val adaptiveAudio = mutableListOf<StreamCandidate>()
        val manifestUrls = mutableListOf<Triple<String, Int, String>>()
        var resolvedTrailerTitle: String? = null
        var resolvedCaptionTracks: List<YouTubeCaptionTrack> = emptyList()

        for (client in CLIENTS) {
            try {
                val playerResponse = fetchPlayerResponse(
                    apiKey = apiKey,
                    videoId = videoId,
                    client = client,
                    visitorData = watchConfig.visitorData,
                    cookieHeader = null
                )

                if (resolvedTrailerTitle.isNullOrBlank()) {
                    resolvedTrailerTitle = extractYouTubeTrailerTitle(playerResponse)
                }
                if (resolvedCaptionTracks.isEmpty()) {
                    val captions = extractYouTubeCaptionTracks(playerResponse)
                    if (captions.isNotEmpty()) {
                        resolvedCaptionTracks = captions
                    }
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

                val streamingData = playerResponse.mapValue("streamingData") ?: continue
                val hlsManifestUrl = streamingData.stringValue("hlsManifestUrl")
                if (!hlsManifestUrl.isNullOrBlank()) {
                    manifestUrls += Triple(client.key, client.priority, hlsManifestUrl)
                }

                for (format in streamingData.listMapValue("formats")) {
                    val url = format.stringValue("url") ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()
                    if (!mimeType.contains("video/") && mimeType.isNotBlank()) continue

                    val height = (format.numberValue("height")
                        ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble()
                        ?: 0.0).toInt()
                    val fps = (format.numberValue("fps") ?: 0.0).toInt()
                    val bitrate = format.numberValue("bitrate")
                        ?: format.numberValue("averageBitrate")
                        ?: 0.0

                    progressive += StreamCandidate(
                        client = client.key,
                        priority = client.priority,
                        url = url,
                        score = videoScore(height, fps, bitrate),
                        hasN = hasNParam(url),
                        itag = format.stringValue("itag").orEmpty(),
                        height = height,
                        fps = fps,
                        ext = if (mimeType.contains("webm")) "webm" else "mp4"
                    )
                }

                for (format in streamingData.listMapValue("adaptiveFormats")) {
                    val url = format.stringValue("url") ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()
                    val hasVideo = mimeType.contains("video/")
                    val hasAudio = mimeType.contains("audio/") || mimeType.startsWith("audio/")

                    if (hasVideo) {
                        val height = (format.numberValue("height")
                            ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble()
                            ?: 0.0).toInt()
                        val fps = (format.numberValue("fps") ?: 0.0).toInt()
                        val bitrate = format.numberValue("bitrate")
                            ?: format.numberValue("averageBitrate")
                            ?: 0.0

                        adaptiveVideo += StreamCandidate(
                            client = client.key,
                            priority = client.priority,
                            url = url,
                            score = videoScore(height, fps, bitrate),
                            hasN = hasNParam(url),
                            itag = format.stringValue("itag").orEmpty(),
                            height = height,
                            fps = fps,
                            ext = if (mimeType.contains("webm")) "webm" else "mp4"
                        )
                    } else if (hasAudio) {
                        val bitrate = format.numberValue("bitrate")
                            ?: format.numberValue("averageBitrate")
                            ?: 0.0
                        val asr = format.numberValue("audioSampleRate") ?: 0.0

                        adaptiveAudio += StreamCandidate(
                            client = client.key,
                            priority = client.priority,
                            url = url,
                            score = audioScore(bitrate, asr),
                            hasN = hasNParam(url),
                            itag = format.stringValue("itag").orEmpty(),
                            height = 0,
                            fps = 0,
                            ext = if (mimeType.contains("webm")) "webm" else "m4a"
                        )
                    }
                }
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Client ${client.key} failed: ${error.message}")
                }
            }
        }

        if (manifestUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            return null
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

        val bestProgressive = sortTrailerCandidatesForPlayback(progressive).firstOrNull()
        val bestVideo = pickBestForClient(adaptiveVideo, PREFERRED_SEPARATE_CLIENT)
        val bestAudio = pickBestForClient(adaptiveAudio, PREFERRED_SEPARATE_CLIENT)
        val combinedUrl = selectPreferredCombinedTrailerUrl(
            manifestUrl = bestManifest?.manifestUrl,
            progressiveUrl = bestProgressive?.url
        )
        val resolvedClientKey = when {
            combinedUrl != null && combinedUrl == bestManifest?.manifestUrl -> bestManifest.client
            combinedUrl != null && combinedUrl == bestProgressive?.url -> bestProgressive.client
            bestVideo != null -> bestVideo.client
            else -> null
        }
        val resolvedUserAgent = lookupClientUserAgent(resolvedClientKey)

        val playbackSource = selectPreferredTrailerPlaybackSource(
            combinedUrl = combinedUrl?.let { resolveReachableUrl(it) },
            adaptiveVideoUrl = bestVideo?.url?.let { resolveReachableUrl(it) },
            adaptiveAudioUrl = bestAudio?.url?.let { resolveReachableUrl(it) },
            userAgent = resolvedUserAgent
        )?.copy(
            captions = resolvedCaptionTracks,
            signingClientKey = resolvedClientKey
        ) ?: return null

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "Kotlin selection video=${summarizeUrl(playbackSource.videoUrl)} " +
                    "audioPresent=${!playbackSource.audioUrl.isNullOrBlank()} " +
                    "combinedSelected=${!combinedUrl.isNullOrBlank()} " +
                    "manifestAvailable=${bestManifest != null} " +
                    "bestProgressiveExt=${bestProgressive?.ext.orEmpty()} " +
                    "bestAdaptiveVideoExt=${bestVideo?.ext.orEmpty()} " +
                    "progressiveCount=${progressive.size} " +
                    "adaptiveVideoCount=${adaptiveVideo.size} adaptiveAudioCount=${adaptiveAudio.size}"
            )
        }

        return playbackSource
    }

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
        val apiKey = API_KEY_REGEX.find(html)?.groupValues?.getOrNull(1)
        val visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)
        return WatchConfig(apiKey = apiKey, visitorData = visitorData)
    }

    private suspend fun fetchPlayerResponse(
        apiKey: String,
        videoId: String,
        client: YouTubeClient,
        visitorData: String?,
        cookieHeader: String?
    ): Map<*, *> {
        val isMobileClient = client.key == "ios" || client.key == "android"
        val endpoint = if (isMobileClient) {
            // Mobile profile: NewPipeExtractor's `youtubei.googleapis.com`
            // endpoint with prettyPrint=false&t=<random>&id=<videoId>.
            // YouTube has been observed to return HLS manifests with
            // restricted signing for iOS-shaped requests against the web
            // endpoint; aligning with the native iOS app's fingerprint
            // (gapis host + X-Goog-Api-Format-Version: 2 + cpn) yields
            // unrestricted manifests.
            "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false" +
                "&t=${generateContentPlaybackNonce().take(12)}" +
                "&id=${Uri.encode(videoId)}"
        } else {
            "https://www.youtube.com/youtubei/v1/player?key=${Uri.encode(apiKey)}"
        }

        val requestProfile = when (client.key) {
            "ios" -> YouTubeWireProfile.IOS
            "android" -> YouTubeWireProfile.ANDROID
            else -> YouTubeWireProfile.WEB
        }
        val headers = buildMap {
            putAll(
                buildYouTubeWireProperties(
                    profile = requestProfile,
                    userAgent = client.userAgent,
                    cookieHeader = cookieHeader
                )
            )
            put("content-type", "application/json")
            put("x-youtube-client-name", client.id)
            put("x-youtube-client-version", client.version)
            if (!visitorData.isNullOrBlank()) put("x-goog-visitor-id", visitorData)
        }

        val cpn = generateContentPlaybackNonce()
        val payload = buildMap<String, Any> {
            put("videoId", videoId)
            put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", mapOf("client" to client.context))
            put(
                "playbackContext",
                mapOf("contentPlaybackContext" to mapOf("html5Preference" to "HTML5_PREF_WANTS"))
            )
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
        return parsed ?: emptyMap<String, Any>()
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
    return this[key] as? Map<*, *>
}

private fun Map<*, *>.listMapValue(key: String): List<Map<*, *>> {
    val raw = this[key] as? List<*> ?: return emptyList()
    return raw.mapNotNull { it as? Map<*, *> }
}

private fun Map<*, *>.stringValue(key: String): String? {
    val value = this[key] ?: return null
    return value.toString()
}

private fun Map<*, *>.intListValue(key: String): List<Int> {
    val value = this[key] as? List<*> ?: return emptyList()
    return value.mapNotNull {
        when (it) {
            is Number -> it.toInt()
            is String -> it.toIntOrNull()
            else -> null
        }
    }
}

private fun Map<*, *>.numberValue(key: String): Double? {
    val value = this[key] ?: return null
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}
