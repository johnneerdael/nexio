package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.tvdb.TvdbLanguageMapper
import java.util.Locale

internal data class NormalizedLanguage(
    val requestedTag: String,
    val providerCode: String
)

internal data class LocalizationPolicy(
    val requestedLanguage: NormalizedLanguage,
    val fallbackLanguage: NormalizedLanguage,
    val provider: MetadataPrimaryProvider,
    val policyVersion: Int,
    val allowProviderFallbackForMissingLocalizedFields: Boolean,
    val maxPerEpisodeTranslationFallbacksPerRequest: Int
) {
    val requestedIsFallback: Boolean
        get() = requestedLanguage.providerCode == fallbackLanguage.providerCode

    fun languageChain(): List<NormalizedLanguage> =
        if (requestedIsFallback) listOf(fallbackLanguage) else listOf(requestedLanguage, fallbackLanguage)

    fun cachePolicyPart(): String = "policy:$policyVersion"

    companion object {
        const val CURRENT_VERSION: Int = 2
        const val DEFAULT_PER_EPISODE_TRANSLATION_FALLBACK_CAP: Int = 8

        fun tvdb(
            requestedLanguage: String?,
            maxPerEpisodeTranslationFallbacksPerRequest: Int = DEFAULT_PER_EPISODE_TRANSLATION_FALLBACK_CAP
        ): LocalizationPolicy {
            val normalized = TvdbLanguageMapper.normalize(requestedLanguage)
            return LocalizationPolicy(
                requestedLanguage = NormalizedLanguage(requestedLanguage.orEmpty(), normalized),
                fallbackLanguage = NormalizedLanguage("en", "eng"),
                provider = MetadataPrimaryProvider.TVDB,
                policyVersion = CURRENT_VERSION,
                allowProviderFallbackForMissingLocalizedFields = false,
                maxPerEpisodeTranslationFallbacksPerRequest = maxPerEpisodeTranslationFallbacksPerRequest.coerceAtLeast(0)
            )
        }

        fun tmdb(requestedLanguage: String?): LocalizationPolicy {
            val normalized = normalizeTmdbLanguage(requestedLanguage)
            return LocalizationPolicy(
                requestedLanguage = NormalizedLanguage(requestedLanguage.orEmpty(), normalized),
                fallbackLanguage = NormalizedLanguage("en-US", "en-US"),
                provider = MetadataPrimaryProvider.TMDB,
                policyVersion = CURRENT_VERSION,
                allowProviderFallbackForMissingLocalizedFields = false,
                maxPerEpisodeTranslationFallbacksPerRequest = 0
            )
        }

        fun kitsu(requestedLanguage: String?): LocalizationPolicy {
            val normalized = requestedLanguage
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.replace('_', '-')
                ?.lowercase(Locale.US)
                ?.substringBefore('-')
                ?: "en"
            return LocalizationPolicy(
                requestedLanguage = NormalizedLanguage(requestedLanguage.orEmpty(), normalized),
                fallbackLanguage = NormalizedLanguage("en", "en"),
                provider = MetadataPrimaryProvider.KITSU,
                policyVersion = CURRENT_VERSION,
                allowProviderFallbackForMissingLocalizedFields = false,
                maxPerEpisodeTranslationFallbacksPerRequest = 0
            )
        }

        private fun normalizeTmdbLanguage(language: String?): String {
            val normalized = language
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.replace('_', '-')
                ?: return "en-US"
            val parts = normalized.split("-").filter { it.isNotBlank() }
            val languageCode = parts.firstOrNull()?.lowercase(Locale.US) ?: return "en-US"
            if (languageCode == "en") return "en-US"
            val region = parts.getOrNull(1)?.uppercase(Locale.US)
            return if (region == null) languageCode else "$languageCode-$region"
        }
    }
}
