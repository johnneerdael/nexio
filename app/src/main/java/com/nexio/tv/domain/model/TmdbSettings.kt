package com.nexio.tv.domain.model

data class TmdbSettings(
    val enabled: Boolean = false,
    val apiKey: String = "",
    // Group: Artwork (logo, backdrop)
    val useArtwork: Boolean = true,
    // Group: Basic Info (description, genres, rating)
    val useBasicInfo: Boolean = true,
    // Group: Details (runtime, release info, country, language)
    val useDetails: Boolean = true,
    // Group: Credits (cast with photos, director, writer)
    val useCredits: Boolean = true,
    // Group: Production companies
    val useProductions: Boolean = true,
    // Group: Networks (logo)
    val useNetworks: Boolean = true,
    // Group: Episodes (episode titles, overviews, thumbnails)
    val useEpisodes: Boolean = true,
    // Group: Recommendations (more like this)
    val useMoreLikeThis: Boolean = true,
    // Group: Reviews
    val useReviews: Boolean = true,
    // Group: Collections
    val useCollections: Boolean = true
) {
    val isActive: Boolean
        get() = enabled && apiKey.isNotBlank()
}
