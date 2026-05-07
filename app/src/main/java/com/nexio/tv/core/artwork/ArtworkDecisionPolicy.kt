package com.nexio.tv.core.artwork

import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSettings
import java.security.MessageDigest

object ArtworkDecisionPolicy {
    const val DECISION_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L
    const val DECISION_STALE_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L

    fun premiumEnabled(settings: ArtworkProviderSettings, imageType: ArtworkType): Boolean =
        settings.selection.providerFor(imageType.toSettingsKey()) != ArtworkProviderChoiceKey.DEFAULT

    fun settingsHash(settings: ArtworkProviderSettings, imageType: ArtworkType): String =
        if (imageType == ArtworkType.THUMBNAIL) {
            ArtworkCacheKeys.providerTemplateSettingsHash(
                imageType = imageType,
                settingsParts = listOf(
                    "thumbnailProvider=${settings.selection.thumbnailProvider.value}",
                    "hasTopPostersKey=${settings.hasTopPostersKey}",
                    "topPostersCanProvideThumbnails=${settings.topPostersCanProvideThumbnails}"
                )
            )
        } else {
            stableHashHex(
                listOf(
                    "${imageType.name.lowercase()}Provider=${settings.selection.providerFor(imageType.toSettingsKey()).value}",
                    "hasRpdbKey=${settings.hasRpdbKey}",
                    "hasTopPostersKey=${settings.hasTopPostersKey}",
                    "topPostersCanProvideThumbnails=${settings.topPostersCanProvideThumbnails}"
                ).joinToString("|")
            )
        }

    fun credentialHash(settings: ArtworkProviderSettings, imageType: ArtworkType): String? =
        when (settings.selection.providerFor(imageType.toSettingsKey())) {
            ArtworkProviderChoiceKey.RPDB -> ArtworkCredentialHash.hashCredential(settings.rpdbApiKey)
            ArtworkProviderChoiceKey.TOP_POSTERS -> ArtworkCredentialHash.hashCredential(settings.topPostersApiKey)
            else -> null
        }

    private fun stableHashHex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }
}
