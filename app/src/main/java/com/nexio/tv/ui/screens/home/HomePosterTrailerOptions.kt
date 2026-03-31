package com.nexio.tv.ui.screens.home

data class HomePosterTrailerPlayback(
    val itemId: String,
    val title: String,
    val videoUrl: String,
    val audioUrl: String? = null
)

internal fun playableHomeTrailerFor(
    itemId: String,
    title: String,
    previewUrls: Map<String, String>,
    previewAudioUrls: Map<String, String>
): HomePosterTrailerPlayback? {
    val videoUrl = previewUrls[itemId]?.takeIf { it.isNotBlank() } ?: return null
    return HomePosterTrailerPlayback(
        itemId = itemId,
        title = title,
        videoUrl = videoUrl,
        audioUrl = previewAudioUrls[itemId]?.takeIf { it.isNotBlank() }
    )
}
