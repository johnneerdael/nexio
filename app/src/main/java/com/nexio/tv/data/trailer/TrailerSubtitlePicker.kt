package com.nexio.tv.data.trailer

internal data class SelectedTrailerCaptionTrack(
    val baseUrl: String,
    val languageCode: String,
    val translateTo: String? = null
)

/**
 * Picks the YouTube caption track that best matches the user's preferred subtitle language.
 *
 * Special sentinel values for [preferredLanguage]: `null`, blank, `"none"`, `"off"`, and
 * `"forced"` disable trailer subtitles (YouTube has no concept of forced subs). `"original"`
 * picks the first manual track on the video, falling back to ASR.
 *
 * Otherwise we prefer a native track in the requested language (manual over ASR, exact tag
 * before language-base), then fall back to translating from English (or any translatable
 * source track) via YouTube's `&tlang=` parameter.
 */
internal fun pickTrailerCaptionTrack(
    tracks: List<YouTubeCaptionTrack>,
    preferredLanguage: String?
): SelectedTrailerCaptionTrack? {
    if (tracks.isEmpty()) return null
    val normalized = preferredLanguage?.trim()?.lowercase()?.replace('_', '-')
    if (normalized.isNullOrEmpty()) return null
    if (normalized == "none" || normalized == "off" || normalized == "forced") return null

    val nonAsr = tracks.filter { (it.kind ?: "").lowercase() != "asr" }
    val asr = tracks.filter { (it.kind ?: "").lowercase() == "asr" }
    val ordered = nonAsr + asr

    if (normalized == "original") {
        val source = ordered.firstOrNull() ?: return null
        return SelectedTrailerCaptionTrack(
            baseUrl = source.baseUrl,
            languageCode = source.languageCode
        )
    }

    val base = normalized.substringBefore('-')
    val nativeExact = ordered.firstOrNull {
        it.languageCode.lowercase().replace('_', '-') == normalized
    }
    val nativeBase = ordered.firstOrNull {
        it.languageCode.lowercase().substringBefore('-') == base
    }
    val native = nativeExact ?: nativeBase
    if (native != null) {
        return SelectedTrailerCaptionTrack(
            baseUrl = native.baseUrl,
            languageCode = native.languageCode
        )
    }

    val translatable = ordered.filter { it.isTranslatable }
    val sourceTrack = translatable.firstOrNull {
        it.languageCode.lowercase().startsWith("en") && (it.kind ?: "").lowercase() != "asr"
    }
        ?: translatable.firstOrNull { it.languageCode.lowercase().startsWith("en") }
        ?: translatable.firstOrNull { (it.kind ?: "").lowercase() != "asr" }
        ?: translatable.firstOrNull()
        ?: return null

    return SelectedTrailerCaptionTrack(
        baseUrl = sourceTrack.baseUrl,
        languageCode = normalized,
        translateTo = normalized
    )
}

internal fun buildTrailerSubtitleVttUrl(selected: SelectedTrailerCaptionTrack): String {
    val separator = if (selected.baseUrl.contains('?')) "&" else "?"
    val builder = StringBuilder(selected.baseUrl)
    builder.append(separator).append("fmt=vtt")
    selected.translateTo
        ?.takeIf { it.isNotBlank() }
        ?.let { builder.append("&tlang=").append(it) }
    return builder.toString()
}
