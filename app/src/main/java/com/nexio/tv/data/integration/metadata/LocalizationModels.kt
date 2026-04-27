package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataLocalizationPayloadTrace
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.tvdb.TvEpisodeMetadata

internal enum class FallbackRole {
    LOCALIZED,
    LANGUAGE_FALLBACK,
    CANONICAL,
    ADDON_FALLBACK,
    PROVIDER_FALLBACK
}

internal data class LocalizedFieldCandidate(
    val field: ResolvedField,
    val value: String?,
    val language: NormalizedLanguage,
    val provider: MetadataPrimaryProvider,
    val sourceShape: String,
    val fallbackRole: FallbackRole
)

internal data class SelectedLocalizedField(
    val field: ResolvedField,
    val value: String,
    val language: NormalizedLanguage,
    val provider: MetadataPrimaryProvider,
    val sourceShape: String,
    val fallbackRole: FallbackRole,
    val rejectedCandidates: List<LocalizedFieldRejection>
)

internal data class LocalizedFieldRejection(
    val provider: MetadataPrimaryProvider,
    val language: NormalizedLanguage,
    val fallbackRole: FallbackRole,
    val reason: String
)

internal data class LocalizedEpisodeBundle(
    val episodes: Map<Pair<Int, Int>, LocalizedEpisodeMetadata>,
    val policy: LocalizationPolicy,
    val perEpisodeTranslationFallbacksAttempted: Int,
    val maxPerEpisodeTranslationFallbacksAllowed: Int,
    val providerFallbackUsed: Boolean = false,
    val localizationPayloads: List<MetadataLocalizationPayloadTrace> = emptyList()
)

internal data class LocalizedEpisodeMetadata(
    val metadata: TvEpisodeMetadata,
    val fieldSources: Map<String, LocalizedEpisodeFieldSource>
)

internal data class LocalizedEpisodeFieldSource(
    val selectedLanguage: NormalizedLanguage,
    val sourceShape: String,
    val fallbackRole: FallbackRole,
    val rejectedCandidates: List<LocalizedFieldRejection>
)

internal data class LocalizedPayloadFetch<T>(
    val value: T?,
    val trace: MetadataLocalizationPayloadTrace
)

internal fun String?.cleanLocalizedValue(): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { value ->
            missingLocalizedPlaceholders.any { placeholder ->
                value.equals(placeholder, ignoreCase = true)
            }
        }

private val missingLocalizedPlaceholders = setOf(
    "N/A",
    "No description available.",
    "No overview available.",
    "No synopsis available."
)
