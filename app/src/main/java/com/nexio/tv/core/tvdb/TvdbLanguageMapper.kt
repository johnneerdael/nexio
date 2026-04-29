package com.nexio.tv.core.tvdb

import java.util.Locale

/**
 * Result of [TvdbLanguageMapper.normalize].
 *
 * @property code          TVDB ISO-639-2 provider code (e.g. "eng", "spa").
 * @property isCollapsedToFallback  F2-E-01: `true` when the requested locale was not on the TVDB
 *                                  whitelist and was silently collapsed to the English fallback.
 *                                  Propagated to the `metadata.localization_plan` event payload as
 *                                  `localeCollapsedToFallback` so trace bundles surface the gap for
 *                                  Italian, Portuguese, Polish, Russian, Korean, Arabic, Japanese,
 *                                  etc. users.
 */
data class TvdbNormalizedLanguage(
    val code: String,
    val isCollapsedToFallback: Boolean = false
)

object TvdbLanguageMapper {
    /**
     * Maps a BCP-47 / IETF locale tag or raw code to a TVDB provider language code.
     * Returns a [TvdbNormalizedLanguage] that carries whether the locale was collapsed to the
     * English fallback (F2-E-01).
     */
    fun normalize(language: String?): TvdbNormalizedLanguage {
        val normalized = language
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace('_', '-')
            ?.lowercase(Locale.US)
            ?: return TvdbNormalizedLanguage(code = "eng", isCollapsedToFallback = false)

        return when {
            normalized in setOf("eng", "en", "en-us", "en-gb") ->
                TvdbNormalizedLanguage(code = "eng", isCollapsedToFallback = false)
            normalized in setOf("spa", "es", "es-es", "es-mx") ->
                TvdbNormalizedLanguage(code = "spa", isCollapsedToFallback = false)
            normalized in setOf("fra", "fre", "fr", "fr-fr") ->
                TvdbNormalizedLanguage(code = "fra", isCollapsedToFallback = false)
            normalized in setOf("deu", "ger", "de", "de-de") ->
                TvdbNormalizedLanguage(code = "deu", isCollapsedToFallback = false)
            normalized in setOf("nld", "dut", "nl", "nl-nl") ->
                TvdbNormalizedLanguage(code = "nld", isCollapsedToFallback = false)
            normalized in setOf("zho", "chi", "zh", "zh-cn", "zh-hans") ->
                TvdbNormalizedLanguage(code = "zho", isCollapsedToFallback = false)
            normalized in setOf("zhtw", "zh-tw", "zh-hant") ->
                TvdbNormalizedLanguage(code = "zhtw", isCollapsedToFallback = false)
            else ->
                // F2-E-01: unsupported locale collapsed to English — flag for trace diagnostics.
                TvdbNormalizedLanguage(code = "eng", isCollapsedToFallback = true)
        }
    }
}
