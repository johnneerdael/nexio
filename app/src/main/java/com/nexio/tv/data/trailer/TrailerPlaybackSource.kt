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
    val normalizedCombinedUrl = combinedUrl?.takeIf { it.isNotBlank() }
    if (normalizedCombinedUrl != null) {
        return TrailerPlaybackSource(videoUrl = normalizedCombinedUrl, userAgent = userAgent)
    }

    val normalizedAdaptiveVideoUrl = adaptiveVideoUrl?.takeIf { it.isNotBlank() } ?: return null
    return TrailerPlaybackSource(
        videoUrl = normalizedAdaptiveVideoUrl,
        audioUrl = adaptiveAudioUrl?.takeIf { it.isNotBlank() },
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
