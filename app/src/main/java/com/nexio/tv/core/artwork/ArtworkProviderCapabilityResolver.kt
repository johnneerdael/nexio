package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import javax.inject.Inject
import javax.inject.Singleton

data class ArtworkProviderCapability(
    val supported: Boolean,
    val reason: String?
)

@Singleton
class ArtworkProviderCapabilityResolver @Inject constructor() {
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
            is ArtworkProviderId.RuntimeProvider -> provider.compatibilityRejectionReason(imageType, ids)
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

    private fun ArtworkProviderId.RuntimeProvider.compatibilityRejectionReason(
        imageType: ArtworkType,
        ids: ProviderIds
    ): String? {
        val descriptor = descriptor() ?: return null
        if (imageType !in descriptor.supportedArtworkTypes) {
            return "unsupported_artwork_type_for_provider"
        }
        if (providerId == IntegrationProvider.TOP_POSTERS && imageType == ArtworkType.THUMBNAIL) {
            return "topposters_entitlement_missing"
        }
        if (!ids.hasAnyOf(descriptor.supportedIdTypes)) {
            return "missing_supported_provider_id"
        }
        return null
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
        if (!ids.hasValidTopPostersId()) {
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

    private fun ProviderIds.hasValidTopPostersId(): Boolean =
        tvdb.isNumericId("tvdb") ||
            tmdb.isValidTopPostersTmdbId() ||
            imdb.isValidImdbId() ||
            trakt.isNumericId("trakt") ||
            kitsu.isNumericId("kitsu") ||
            mal.isNumericId("mal") ||
            anilist.isNumericId("anilist") ||
            anidb.isNumericId("anidb")

    private fun String?.isNumericId(idType: String): Boolean =
        cleanedProviderValue(idType)?.matches(NUMERIC_ID_REGEX) == true

    private fun String?.isValidImdbId(): Boolean =
        cleanedProviderValue("imdb")?.matches(IMDB_ID_REGEX) == true

    private fun String?.isValidTopPostersTmdbId(): Boolean {
        val value = cleanedProviderValue("tmdb") ?: return false
        val numericPart = value.substringAfter('-', missingDelimiterValue = value)
        return numericPart.matches(NUMERIC_ID_REGEX) &&
            (
                '-' !in value ||
                    value.startsWith("movie-", ignoreCase = true) ||
                    value.startsWith("series-", ignoreCase = true)
                )
    }

    private fun String?.cleanedProviderValue(idType: String): String? {
        val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            raw.startsWith("$idType:", ignoreCase = true) -> raw.substringAfter(':').trim()
            ':' in raw -> null
            else -> raw
        }?.takeIf { it.isNotBlank() }
    }

    private fun String?.isPresent(): Boolean = !isNullOrBlank()

    private companion object {
        const val TOP_POSTERS_PREMIUM_TIER = 1
        val NUMERIC_ID_REGEX = Regex("""\d+""")
        val IMDB_ID_REGEX = Regex("""tt\d+""")
    }
}
