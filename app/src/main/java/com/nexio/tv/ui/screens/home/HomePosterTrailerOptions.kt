package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.RatingDisplayFormatter

internal data class HomePosterTrailerPlayback(
    val itemId: String,
    val title: String,
    val videoUrl: String,
    val audioUrl: String? = null,
    val userAgent: String? = null,
    val captions: List<com.nexio.tv.data.trailer.YouTubeCaptionTrack> = emptyList(),
    val heroPreview: HeroPreview? = null
)

internal fun homeTrailerAvailabilityKey(
    itemId: String,
    apiType: String
): String = "${apiType.trim().lowercase()}:$itemId"

internal fun hasHomeTrailerAction(
    itemId: String,
    apiType: String,
    metadataAvailableKeys: Set<String>,
    previewUrls: Map<String, String>,
    previewExternalUrls: Map<String, String>
): Boolean {
    return homeTrailerAvailabilityKey(itemId, apiType) in metadataAvailableKeys ||
        !previewUrls[itemId].isNullOrBlank() ||
        !previewExternalUrls[itemId].isNullOrBlank()
}

internal fun playableHomeTrailerFor(
    itemId: String,
    title: String,
    item: com.nexio.tv.domain.model.MetaPreview? = null,
    previewUrls: Map<String, String>,
    previewAudioUrls: Map<String, String>,
    previewUserAgents: Map<String, String> = emptyMap(),
    previewCaptions: Map<String, List<com.nexio.tv.data.trailer.YouTubeCaptionTrack>> = emptyMap()
): HomePosterTrailerPlayback? {
    val videoUrl = previewUrls[itemId]?.takeIf { it.isNotBlank() } ?: return null
    return HomePosterTrailerPlayback(
        itemId = itemId,
        title = title,
        videoUrl = videoUrl,
        audioUrl = previewAudioUrls[itemId]?.takeIf { it.isNotBlank() },
        userAgent = previewUserAgents[itemId]?.takeIf { it.isNotBlank() },
        captions = previewCaptions[itemId].orEmpty(),
        heroPreview = item?.toHomeHeroPreview()
    )
}

internal fun com.nexio.tv.domain.model.MetaPreview.toHomeHeroPreview(): HeroPreview {
    return HeroPreview(
        title = name,
        logo = logo,
        description = description,
        contentTypeText = null,
        yearText = releaseInfo,
        imdbText = imdbRating?.let { RatingDisplayFormatter.formatTitleRating(it) },
        ratingSource = ratingSource,
        tomatoesText = tomatoesRating?.let { RatingDisplayFormatter.formatPercentRating(it) },
        genres = genres,
        poster = poster,
        backdrop = background,
        imageUrl = background ?: poster
    )
}
