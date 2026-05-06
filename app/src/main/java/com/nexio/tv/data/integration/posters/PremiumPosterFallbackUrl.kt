package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.metadata.router.MetadataRoute
import java.net.URI
import java.util.Locale

internal fun MetadataRoute.nonPremiumPosterFallbackUrl(): String? {
    val metadata = sourceContext.addonMetadata ?: return null
    return listOf(metadata.displayPoster, metadata.poster)
        .asSequence()
        .mapNotNull { value -> value?.trim()?.takeIf { it.isNotBlank() } }
        .firstOrNull(::isNonPremiumRemoteArtworkUrl)
}

private fun isNonPremiumRemoteArtworkUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "http" && scheme != "https") return false

    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    if (host.endsWith("ratingposterdb.com")) return false
    if (host.endsWith("top-posters.com")) return false

    return true
}
