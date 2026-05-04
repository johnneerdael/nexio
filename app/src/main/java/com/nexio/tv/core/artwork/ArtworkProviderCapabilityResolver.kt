package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds

class ArtworkProviderCapabilityResolver {
    fun supports(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        ids: ProviderIds,
        mediaKind: MetadataMediaKind
    ): Boolean =
        rejectionReason(
            provider = provider,
            imageType = imageType,
            ids = ids,
            mediaKind = mediaKind
        ) == null

    @Suppress("UNUSED_PARAMETER")
    fun rejectionReason(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        ids: ProviderIds,
        mediaKind: MetadataMediaKind
    ): String? =
        when (provider) {
            is ArtworkProviderId.RuntimeProvider -> provider.rejectionReason(imageType, ids)
            else -> null
        }

    private fun ArtworkProviderId.RuntimeProvider.rejectionReason(
        imageType: ArtworkType,
        ids: ProviderIds
    ): String? =
        when (providerId) {
            IntegrationProvider.RPDB,
            IntegrationProvider.TOP_POSTERS -> premiumPosterRejectionReason(imageType, ids)
            else -> null
        }

    private fun premiumPosterRejectionReason(
        imageType: ArtworkType,
        ids: ProviderIds
    ): String? {
        if (imageType != ArtworkType.POSTER) return "UNSUPPORTED_IMAGE_TYPE"
        if (ids.hasSupportedPremiumPosterId()) return null
        return "UNSUPPORTED_ID_TYPE"
    }

    private fun ProviderIds.hasSupportedPremiumPosterId(): Boolean =
        imdb.isPresent() || tmdb.isPresent() || tvdb.isPresent()

    private fun String?.isPresent(): Boolean = !isNullOrBlank()
}
