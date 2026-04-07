package com.nexio.tv.ui.screens.player

import androidx.media3.common.MimeTypes

internal object PlayerSubtitleUtils {
    fun normalizeLanguageCode(lang: String): String {
        val code = lang.trim().lowercase()
        if (code.isBlank()) return ""

        val normalizedCode = code.replace('_', '-')
        val tokenized = normalizedCode
            .replace('-', ' ')
            .replace('.', ' ')
            .replace('/', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        fun containsAny(vararg values: String): Boolean = values.any { value ->
            tokenized.contains(value)
        }

        if (containsAny("portuguese", "portugues")) {
            if (containsAny("brazil", "brasil", "brazilian", "brasileiro", "pt br", "ptbr", "pob")) {
                return "pt-br"
            }
            if (containsAny("portugal", "european", "europeu", "iberian", "pt pt", "ptpt")) {
                return "pt"
            }
            return "pt"
        }

        // Normalize full display-name language strings that some Stremio addons
        // emit in Meta.language (e.g. "English", "Polish") to ISO-639-1 codes
        // so they are usable as startup audio-selection targets.
        when {
            containsAny("english") -> return "en"
            containsAny("spanish", "espanol", "castellano") -> return "es"
            containsAny("french", "francais") -> return "fr"
            containsAny("german", "deutsch") -> return "de"
            containsAny("italian", "italiano") -> return "it"
            containsAny("russian") -> return "ru"
            containsAny("japanese") -> return "ja"
            containsAny("korean") -> return "ko"
            containsAny("chinese", "mandarin") -> return "zh"
            containsAny("arabic") -> return "ar"
            containsAny("hindi") -> return "hi"
            containsAny("dutch", "nederlands") -> return "nl"
            containsAny("polish", "polski") -> return "pl"
            containsAny("turkish", "turkce") -> return "tr"
            containsAny("swedish") -> return "sv"
            containsAny("norwegian") -> return "no"
            containsAny("danish") -> return "da"
            containsAny("finnish") -> return "fi"
            containsAny("czech", "cesky") -> return "cs"
            containsAny("hungarian") -> return "hu"
            containsAny("romanian") -> return "ro"
            containsAny("ukrainian") -> return "uk"
        }

        return when (code) {
            "pt-br", "pt_br", "br", "pob" -> "pt-br"
            "pt", "pt-pt", "pt_pt", "por" -> "pt"
            "eng" -> "en"
            "spa" -> "es"
            "fre", "fra" -> "fr"
            "ger", "deu" -> "de"
            "ita" -> "it"
            "rus" -> "ru"
            "jpn" -> "ja"
            "kor" -> "ko"
            "chi", "zho" -> "zh"
            "ara" -> "ar"
            "hin" -> "hi"
            "nld", "dut" -> "nl"
            "pol" -> "pl"
            "swe" -> "sv"
            "nor" -> "no"
            "dan" -> "da"
            "fin" -> "fi"
            "tur" -> "tr"
            "ell", "gre" -> "el"
            "heb" -> "he"
            "tha" -> "th"
            "vie" -> "vi"
            "ind" -> "id"
            "msa", "may" -> "ms"
            "ces", "cze" -> "cs"
            "hun" -> "hu"
            "ron", "rum" -> "ro"
            "ukr" -> "uk"
            "bul" -> "bg"
            "hrv" -> "hr"
            "srp" -> "sr"
            "slk", "slo" -> "sk"
            "slv" -> "sl"
            else -> normalizedCode
        }
    }

    fun matchesLanguageCode(language: String?, target: String): Boolean {
        if (language.isNullOrBlank()) return false
        val normalizedLanguage = normalizeLanguageCode(language)
        val normalizedTarget = normalizeLanguageCode(target)
        if (matchesNormalizedLanguage(normalizedLanguage, normalizedTarget)) {
            return true
        }

        val subtags = language.trim().lowercase()
            .replace('_', '-')
            .split('-', '.', '/', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (subtags.size <= 1) {
            return false
        }
        for (subtag in subtags.drop(1)) {
            if (subtag.length != 3) continue
            val normalizedSubtag = normalizeLanguageCode(subtag)
            if (matchesNormalizedLanguage(normalizedSubtag, normalizedTarget)) {
                return true
            }
        }
        return false
    }

    private fun matchesNormalizedLanguage(
        normalizedLanguage: String,
        normalizedTarget: String
    ): Boolean {
        if (normalizedTarget == "pt") {
            return normalizedLanguage == "pt"
        }
        return normalizedLanguage == normalizedTarget ||
            normalizedLanguage.startsWith("$normalizedTarget-") ||
            normalizedLanguage.startsWith("${normalizedTarget}_")
    }

    fun mimeTypeFromUrl(url: String): String {
        val lowered = url.lowercase()
        val pathOnly = lowered.substringBefore('#').substringBefore('?')
        // Some addon URLs embed the file extension mid-path with a trailing
        // slash before query args (e.g. ".../sub.vtt/?lang_code=nl"). Inspect
        // every path segment so we don't misclassify VTT as SRT.
        val segments = pathOnly.trim('/').split('/')
        fun hasExtensionIn(haystack: List<String>, vararg exts: String): Boolean {
            return haystack.any { segment -> exts.any { ext -> segment.endsWith(ext) } }
        }

        return when {
            hasExtensionIn(segments, ".srt") -> MimeTypes.APPLICATION_SUBRIP
            hasExtensionIn(segments, ".vtt", ".webvtt") -> MimeTypes.TEXT_VTT
            hasExtensionIn(segments, ".ass", ".ssa") -> MimeTypes.TEXT_SSA
            hasExtensionIn(segments, ".ttml", ".dfxp") -> MimeTypes.APPLICATION_TTML
            // Last resort: scan the full URL (covers query params like
            // ?file=foo.vtt or ?format=vtt that some addons emit).
            lowered.contains(".vtt") || lowered.contains("format=vtt") -> MimeTypes.TEXT_VTT
            lowered.contains(".srt") || lowered.contains("format=srt") -> MimeTypes.APPLICATION_SUBRIP
            lowered.contains(".ass") || lowered.contains(".ssa") -> MimeTypes.TEXT_SSA
            lowered.contains(".ttml") || lowered.contains(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}
