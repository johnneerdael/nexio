package com.nexio.tv.core.sync

import com.nexio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nexio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val BUILTIN_SUBTITLE_ADDON_PUBLIC_BASE_URL = "https://opensubtitlesv3-pro.dexter21767.com"

private const val DEFAULT_BUILTIN_SUBTITLE_LANGUAGE_CODE = "en"

private val subtitleLanguageCodeToAddonId = mapOf(
    "pt" to "portuguese",
    "pt-br" to "portuguese-br",
    "zh" to "chinese-simplified",
    "id" to "indonesian",
    "gu" to "gujarati",
    "pa" to "punjabi"
)

private val subtitleLanguageCodeToName = AVAILABLE_SUBTITLE_LANGUAGES.associate { language ->
    language.code to language.name
}

@Serializable
private data class BuiltinSubtitleAddonPayload(
    val langs: List<String>,
    val source: String = "all",
    val aiTranslated: Boolean = false,
    val autoAdjustment: Boolean = false
)

fun isBuiltinSubtitleAddonUrl(url: String): Boolean {
    return (
        runCatching { normalizePublicAddonBaseUrl(url) }
        .getOrNull()
        ?.equals(BUILTIN_SUBTITLE_ADDON_PUBLIC_BASE_URL, ignoreCase = true)
        == true
    )
}

fun buildBuiltinSubtitleAddonInstallUrl(
    preferredLanguage: String?,
    secondaryPreferredLanguage: String?
): String {
    val payload = BuiltinSubtitleAddonPayload(
        langs = subtitleLanguageCodesForBuiltinAddon(preferredLanguage, secondaryPreferredLanguage)
            .map(::mapSubtitleLanguageCodeToAddonId)
    )
    val encodedPayload = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(Json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8))
    return "$BUILTIN_SUBTITLE_ADDON_PUBLIC_BASE_URL/$encodedPayload/manifest.json"
}

internal fun subtitleLanguageCodesForBuiltinAddon(
    preferredLanguage: String?,
    secondaryPreferredLanguage: String?
): List<String> {
    val codes = buildList {
        preferredLanguage
            ?.takeUnless(::isSpecialSubtitleLanguage)
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
        secondaryPreferredLanguage
            ?.takeUnless(::isSpecialSubtitleLanguage)
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
    }.distinct()

    return if (codes.isEmpty()) {
        listOf(DEFAULT_BUILTIN_SUBTITLE_LANGUAGE_CODE)
    } else {
        codes
    }
}

internal fun mapSubtitleLanguageCodeToAddonId(code: String): String {
    val normalizedCode = code.trim().lowercase()
    subtitleLanguageCodeToAddonId[normalizedCode]?.let { return it }

    val languageName = subtitleLanguageCodeToName[normalizedCode]
        ?: normalizedCode
    return languageName
        .lowercase()
        .replace("&", " and ")
        .replace(Regex("[()]"), " ")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}

private fun isSpecialSubtitleLanguage(code: String): Boolean {
    val normalized = code.trim().lowercase()
    return normalized == "none" || normalized == SUBTITLE_LANGUAGE_FORCED
}
