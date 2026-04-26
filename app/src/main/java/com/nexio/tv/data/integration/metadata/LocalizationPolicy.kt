package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.tvdb.TvdbLanguageMapper

internal data class LocalizationPolicy(
    val requestedLanguage: String,
    val fallbackLanguage: String,
    val provider: MetadataPrimaryProvider,
    val policyVersion: Int,
    val allowProviderFallbackForMissingLocalizedFields: Boolean,
    val maxPerEpisodeTranslationFallbacksPerRequest: Int
) {
    val requestedIsFallback: Boolean
        get() = requestedLanguage == fallbackLanguage

    fun cachePolicyPart(): String = "policy:$policyVersion"

    companion object {
        const val CURRENT_VERSION: Int = 1
        const val DEFAULT_PER_EPISODE_TRANSLATION_FALLBACK_CAP: Int = 8

        fun tvdb(
            requestedLanguage: String?,
            maxPerEpisodeTranslationFallbacksPerRequest: Int = DEFAULT_PER_EPISODE_TRANSLATION_FALLBACK_CAP
        ): LocalizationPolicy =
            LocalizationPolicy(
                requestedLanguage = TvdbLanguageMapper.normalize(requestedLanguage),
                fallbackLanguage = "eng",
                provider = MetadataPrimaryProvider.TVDB,
                policyVersion = CURRENT_VERSION,
                allowProviderFallbackForMissingLocalizedFields = false,
                maxPerEpisodeTranslationFallbacksPerRequest = maxPerEpisodeTranslationFallbacksPerRequest.coerceAtLeast(0)
            )
    }
}
