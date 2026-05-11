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
    userAgent: String? = null
): TrailerPlaybackSource? {
    val normalizedAdaptiveVideoUrl = adaptiveVideoUrl?.takeIf { it.isNotBlank() }
    val normalizedAdaptiveAudioUrl = adaptiveAudioUrl?.takeIf { it.isNotBlank() }
    // Prefer NewPipeExtractor's "DASH" pattern: split adaptive video-only +
    // audio-only streams, merged at playback time. YouTube serves its
    // highest-resolution variants (1080p+, 4K, 60fps) only via the
    // adaptiveFormats path; the combined-format / HLS manifest path caps
    // around 720p muxed and forces ABR ramp-up that never completes for
    // short trailers. Direct selection of the best video + best audio
    // bypasses ABR and gives us explicit quality control.
    if (normalizedAdaptiveVideoUrl != null && normalizedAdaptiveAudioUrl != null) {
        return TrailerPlaybackSource(
            videoUrl = normalizedAdaptiveVideoUrl,
            audioUrl = normalizedAdaptiveAudioUrl,
            userAgent = userAgent
        )
    }
    // Fall back to the combined source (HLS master playlist or muxed
    // progressive) when a split pair isn't available.
    val normalizedCombinedUrl = combinedUrl?.takeIf { it.isNotBlank() }
    if (normalizedCombinedUrl != null) {
        return TrailerPlaybackSource(videoUrl = normalizedCombinedUrl, userAgent = userAgent)
    }
    // Last resort: video-only adaptive without paired audio.
    if (normalizedAdaptiveVideoUrl != null) {
        return TrailerPlaybackSource(
            videoUrl = normalizedAdaptiveVideoUrl,
            audioUrl = null,
            userAgent = userAgent
        )
    }
    return null
}

sealed interface TrailerResolutionResult {
    data class Playback(val source: TrailerPlaybackSource) : TrailerResolutionResult
    data class External(val url: String) : TrailerResolutionResult
}

internal data class StreailerTrailerCandidate(
    val youtubeId: String? = null,
    val externalUrl: String? = null
)
