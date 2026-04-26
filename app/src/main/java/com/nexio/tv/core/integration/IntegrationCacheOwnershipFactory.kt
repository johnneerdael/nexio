package com.nexio.tv.core.integration

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntegrationCacheOwnershipFactory @Inject constructor(
    private val identityResolver: RailMediaIdentityResolver
) {
    fun media(
        mediaType: String,
        rawId: String,
        title: String? = null,
        year: Int? = null,
        imdbId: String? = null,
        tmdbId: String? = null,
        traktId: String? = null
    ): IntegrationCacheOwnership {
        val resolved = identityResolver.fromRawContent(
            mediaType = mediaType,
            rawId = rawId,
            title = title,
            year = year,
            imdbId = imdbId,
            tmdbId = tmdbId,
            traktId = traktId
        )
        return IntegrationCacheOwnership.Media(resolved.mediaIdentity.mediaKey)
    }
}
