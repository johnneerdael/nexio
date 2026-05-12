package com.nexio.tv.core.locale

/**
 * Normalizes ISO 639-1 (TMDB / YouTube `defaultAudioLanguage`) and ISO 639-2 B/T
 * (TVDB `language` / `originalLanguage`) codes to a common 2-letter base for
 * cross-provider equality checks.
 *
 * Returns null for blank, unknown, or unrecognized codes — callers treat null
 * as "unknown" and apply the strictest reject-unless-explicitly-allowed rule.
 */
object TrailerLanguageMatcher {

    // ISO 639-2 (both bibliographic /B and terminological /T) → ISO 639-1.
    // Restricted to codes TVDB actually emits in trailer.language and
    // series.originalLanguage payloads; extend on demand.
    private val ISO_639_2_TO_1: Map<String, String> = mapOf(
        "eng" to "en",
        "spa" to "es",
        "fra" to "fr", "fre" to "fr",
        "deu" to "de", "ger" to "de",
        "nld" to "nl", "dut" to "nl",
        "ita" to "it",
        "por" to "pt",
        "rus" to "ru",
        "jpn" to "ja",
        "kor" to "ko",
        "zho" to "zh", "chi" to "zh",
        "ara" to "ar",
        "hin" to "hi",
        "tha" to "th",
        "tur" to "tr",
        "swe" to "sv",
        "nor" to "no", "nob" to "no", "nno" to "no",
        "dan" to "da",
        "fin" to "fi",
        "pol" to "pl",
        "ces" to "cs", "cze" to "cs",
        "hun" to "hu",
        "ell" to "el", "gre" to "el",
        "heb" to "he",
        "ind" to "id",
        "vie" to "vi",
        "ukr" to "uk",
        "ron" to "ro", "rum" to "ro",
        "bul" to "bg",
        "hrv" to "hr",
        "srp" to "sr",
        "slk" to "sk", "slo" to "sk",
        "slv" to "sl",
        "est" to "et",
        "lav" to "lv",
        "lit" to "lt",
        "isl" to "is", "ice" to "is",
        "cat" to "ca",
        "fil" to "fil",
        "msa" to "ms", "may" to "ms",
        "yue" to "yue"
    )

    fun normalize(code: String?): String? {
        val trimmed = code?.trim()?.lowercase()?.replace('_', '-')?.takeIf { it.isNotEmpty() }
            ?: return null
        val base = trimmed.substringBefore('-')
        if (base.isEmpty()) return null
        if (base.length == 2) return base
        if (base.length == 3) return ISO_639_2_TO_1[base]
        return null
    }

    fun matches(a: String?, b: String?): Boolean {
        val left = normalize(a) ?: return false
        val right = normalize(b) ?: return false
        return left == right
    }
}
