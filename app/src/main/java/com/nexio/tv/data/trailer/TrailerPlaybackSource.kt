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
    val signingClientKey: String? = null
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
    progressiveUrl: String? = null,
    userAgent: String? = null
): TrailerPlaybackSource? {
    // Prefer HLS/DASH manifests when available, then split adaptive when
    // both video and audio are available. YouTube now often omits HLS but
    // still returns a low 360p progressive format plus high-quality adaptive
    // streams; do not let that progressive fallback mask the adaptive pair.
    val normalizedCombinedUrl = combinedUrl?.takeIf { it.isNotBlank() }
    if (normalizedCombinedUrl != null) {
        return TrailerPlaybackSource(videoUrl = normalizedCombinedUrl, userAgent = userAgent)
    }

    val normalizedAdaptiveVideoUrl = adaptiveVideoUrl?.takeIf { it.isNotBlank() }
    val normalizedAdaptiveAudioUrl = adaptiveAudioUrl?.takeIf { it.isNotBlank() }
    if (normalizedAdaptiveVideoUrl != null && normalizedAdaptiveAudioUrl != null) {
        return TrailerPlaybackSource(
            videoUrl = normalizedAdaptiveVideoUrl,
            audioUrl = normalizedAdaptiveAudioUrl,
            userAgent = userAgent
        )
    }

    val normalizedProgressiveUrl = progressiveUrl?.takeIf { it.isNotBlank() }
    if (normalizedProgressiveUrl != null) {
        return TrailerPlaybackSource(videoUrl = normalizedProgressiveUrl, userAgent = userAgent)
    }

    return normalizedAdaptiveVideoUrl?.let { videoUrl ->
        TrailerPlaybackSource(videoUrl = videoUrl, userAgent = userAgent)
    }
}

sealed interface TrailerResolutionResult {
    data class Playback(val source: TrailerPlaybackSource) : TrailerResolutionResult
    data class External(val url: String) : TrailerResolutionResult
}

internal data class StreailerTrailerCandidate(
    val youtubeId: String? = null,
    val externalUrl: String? = null
)
