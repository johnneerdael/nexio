package com.nexio.tv.data.trailer

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    val userAgent: String? = null,
    val captions: List<YouTubeCaptionTrack> = emptyList(),
    /**
     * Identifier for the YouTube client that signed [videoUrl]. Downstream
     * Media3 data sources use this to pick the matching HTTP wire profile
     * (iOS-signed URLs need iOS-flavored properties, Android-signed need
     * Android). `null` when unknown (e.g., backend-resolved sources).
     */
    val signingClientKey: String? = null,
    /**
     * Content-bound poToken to append as `pot` to every `googlevideo.com`
     * request for this playback source. Non-null only for WEB adaptive
     * sources that were requested with a matching player poToken.
     */
    val streamingDataPoToken: String? = null
)

data class YouTubeCaptionTrack(
    val baseUrl: String,
    val languageCode: String,
    val languageName: String? = null,
    val kind: String? = null,
    val isTranslatable: Boolean = false
)

internal fun selectPreferredTrailerPlaybackSource(
    combinedUrl: String?,
    adaptiveVideoUrl: String?,
    adaptiveAudioUrl: String?,
    userAgent: String? = null,
    streamingDataPoToken: String? = null,
    preferAdaptive: Boolean = !streamingDataPoToken.isNullOrBlank()
): TrailerPlaybackSource? {
    val normalizedAdaptiveVideoUrl = adaptiveVideoUrl?.takeIf { it.isNotBlank() }
    val normalizedAdaptiveAudioUrl = adaptiveAudioUrl?.takeIf { it.isNotBlank() }

    if (
        preferAdaptive &&
        normalizedAdaptiveVideoUrl != null &&
        normalizedAdaptiveAudioUrl != null
    ) {
        return TrailerPlaybackSource(
            videoUrl = normalizedAdaptiveVideoUrl,
            audioUrl = normalizedAdaptiveAudioUrl,
            userAgent = userAgent,
            streamingDataPoToken = streamingDataPoToken
        )
    }

    // Without an explicit adaptive preference, keep the combined source
    // (HLS master playlist or progressive muxed) as the conservative
    // fallback. The extractor only sets preferAdaptive when it has a
    // client path expected to survive direct googlevideo fetches:
    // ANDROID_VR, ANDROID after n= descrambling, or WEB with poToken.
    val normalizedCombinedUrl = combinedUrl?.takeIf { it.isNotBlank() }
    if (normalizedCombinedUrl != null) {
        return TrailerPlaybackSource(videoUrl = normalizedCombinedUrl, userAgent = userAgent)
    }

    if (normalizedAdaptiveVideoUrl == null) return null
    return TrailerPlaybackSource(
        videoUrl = normalizedAdaptiveVideoUrl,
        audioUrl = normalizedAdaptiveAudioUrl,
        userAgent = userAgent
    )
}

sealed interface TrailerResolutionResult {
    data class Playback(val source: TrailerPlaybackSource) : TrailerResolutionResult
    data class External(val url: String) : TrailerResolutionResult
}

internal data class StreailerTrailerCandidate(
    val youtubeId: String? = null,
    val externalUrl: String? = null
)
