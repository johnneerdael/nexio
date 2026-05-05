package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot

data class ArtworkProviderCapability(
    val supported: Boolean,
    val reason: String?
)

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

    fun evaluate(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        ids: ProviderIds,
        mediaKind: MetadataMediaKind,
        settings: ArtworkProviderSettings
    ): ArtworkProviderCapability {
        val reason = rejectionReason(
            provider = provider,
            imageType = imageType,
            ids = ids,
            mediaKind = mediaKind,
            settings = settings
        )
        return ArtworkProviderCapability(
            supported = reason == null,
            reason = reason
        )
    }

    fun supports(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        ids: ProviderIds,
        mediaKind: MetadataMediaKind,
        settings: ArtworkProviderSettings
    ): Boolean =
        evaluate(provider, imageType, ids, mediaKind, settings).supported

    fun evaluate(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        ids: ProviderIds,
        mediaKind: MetadataMediaKind
    ): ArtworkProviderCapability {
        val reason = rejectionReason(
            provider = provider,
            imageType = imageType,
            ids = ids,
            mediaKind = mediaKind
        )
        return ArtworkProviderCapability(
            supported = reason == null,
            reason = reason
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun rejectionReason(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        ids: ProviderIds,
        mediaKind: MetadataMediaKind,
        settings: ArtworkProviderSettings
    ): String? =
        when (provider) {
            is ArtworkProviderId.RuntimeProvider ->
                provider.settingsAwareRejectionReason(imageType, ids, settings)
            else -> null
        }

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

    private fun ArtworkProviderId.RuntimeProvider.settingsAwareRejectionReason(
        imageType: ArtworkType,
        ids: ProviderIds,
        settings: ArtworkProviderSettings
    ): String? {
        val descriptor = descriptor() ?: return null
        if (settings.selection.providerFor(imageType.toSettingsKey()) != descriptor.choice) {
            return "provider_not_selected_for_artwork_type"
        }

        return when (providerId) {
            IntegrationProvider.RPDB -> rpdbRejectionReason(imageType, ids, settings)
            IntegrationProvider.TOP_POSTERS -> topPostersRejectionReason(imageType, ids, settings)
            else -> null
        }
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

    private fun ArtworkProviderId.RuntimeProvider.descriptor(): ArtworkProviderDescriptor? =
        when (providerId) {
            IntegrationProvider.RPDB -> rpdbDescriptor
            IntegrationProvider.TOP_POSTERS -> topPostersDescriptor
            else -> null
        }

    private fun rpdbRejectionReason(
        imageType: ArtworkType,
        ids: ProviderIds,
        settings: ArtworkProviderSettings
    ): String? {
        if (!settings.hasRpdbKey) return "rpdb_not_configured"
        if (imageType !in rpdbDescriptor.supportedArtworkTypes) {
            return "unsupported_artwork_type_for_provider"
        }
        if (!ids.hasAnyOf(rpdbDescriptor.supportedIdTypes)) return "missing_supported_provider_id"
        return null
    }

    private fun topPostersRejectionReason(
        imageType: ArtworkType,
        ids: ProviderIds,
        settings: ArtworkProviderSettings
    ): String? {
        if (!settings.hasTopPostersKey) return "topposters_not_configured"
        if (imageType !in topPostersDescriptor.supportedArtworkTypes) {
            return "unsupported_artwork_type_for_provider"
        }
        if (imageType == ArtworkType.THUMBNAIL) {
            settings.topPostersEntitlement.thumbnailRejectionReason()?.let { return it }
        }
        if (!ids.hasAnyOf(topPostersDescriptor.supportedIdTypes)) {
            return "missing_supported_provider_id"
        }
        return null
    }

    private fun TopPostersEntitlementSnapshot?.thumbnailRejectionReason(): String? {
        val entitlement = this ?: return "topposters_entitlement_missing"
        if (!entitlement.valid || !entitlement.isActive || !entitlement.isFreshAtNow) {
            return "topposters_entitlement_inactive"
        }
        if (entitlement.tier != TOP_POSTERS_PREMIUM_TIER) {
            return "topposters_tier_not_premium"
        }
        if (!entitlement.episodeThumbnails) {
            return "topposters_episode_thumbnails_not_enabled"
        }
        return null
    }

    private fun ProviderIds.hasAnyOf(types: Set<ArtworkProviderStableIdType>): Boolean =
        types.any { hasId(it) }

    private fun ProviderIds.hasId(type: ArtworkProviderStableIdType): Boolean =
        when (type) {
            ArtworkProviderStableIdType.IMDB -> imdb.isPresent()
            ArtworkProviderStableIdType.TMDB -> tmdb.isPresent()
            ArtworkProviderStableIdType.TVDB -> tvdb.isPresent()
            ArtworkProviderStableIdType.TRAKT -> trakt.isPresent()
            ArtworkProviderStableIdType.MAL -> mal.isPresent()
            ArtworkProviderStableIdType.KITSU -> kitsu.isPresent()
            ArtworkProviderStableIdType.ANILIST -> anilist.isPresent()
            ArtworkProviderStableIdType.ANIDB -> anidb.isPresent()
        }

    private fun ProviderIds.hasSupportedPremiumPosterId(): Boolean =
        imdb.isPresent() || tmdb.isPresent() || tvdb.isPresent()

    private fun String?.isPresent(): Boolean = !isNullOrBlank()

    private companion object {
        const val TOP_POSTERS_PREMIUM_TIER = 1
    }
}
