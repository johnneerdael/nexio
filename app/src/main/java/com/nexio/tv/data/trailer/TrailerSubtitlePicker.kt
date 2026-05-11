package com.nexio.tv.data.trailer

data class SelectedTrailerCaptionTrack(
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

    // No native match for the user's preferred language. Fall back to a
    // source-language track WITHOUT requesting YouTube's `&tlang=` server-side
    // translation — that endpoint is aggressively rate-limited (429 even for
    // single requests in steady state, confirmed 2026-05-11). Returning the
    // source track unmodified keeps SubripParser fed with reliable captions;
    // future AI translation can read the cached SRT and write a sibling file
    // for the target language.
    val sourceTrack = ordered.firstOrNull {
        it.languageCode.lowercase().startsWith("en") && (it.kind ?: "").lowercase() != "asr"
    }
        ?: ordered.firstOrNull { it.languageCode.lowercase().startsWith("en") }
        ?: ordered.firstOrNull { (it.kind ?: "").lowercase() != "asr" }
        ?: ordered.firstOrNull()
        ?: return null

    return SelectedTrailerCaptionTrack(
        baseUrl = sourceTrack.baseUrl,
        languageCode = sourceTrack.languageCode,
        translateTo = null
    )
}

/**
 * Subtitle formats YouTube serves over its timedtext endpoint. TTML is the
 * native server-side format and works for every video that has captions.
 * WebVTT (`&fmt=vtt`) is a server-side transcode that occasionally fails
 * (5xx or malformed VTT) for videos with complex caption features. Default
 * to TTML; allow WEBVTT for callers that want it explicitly.
 */
internal enum class TrailerSubtitleFormat(val queryValue: String, val mimeType: String) {
    TTML(queryValue = "ttml", mimeType = "application/ttml+xml"),
    WEBVTT(queryValue = "vtt", mimeType = "text/vtt")
}

internal fun buildTrailerSubtitleUrl(
    selected: SelectedTrailerCaptionTrack,
    format: TrailerSubtitleFormat = TrailerSubtitleFormat.TTML
): String {
    val separator = if (selected.baseUrl.contains('?')) "&" else "?"
    val builder = StringBuilder(selected.baseUrl)
    builder.append(separator).append("fmt=").append(format.queryValue)
    selected.translateTo
        ?.takeIf { it.isNotBlank() }
        ?.let { builder.append("&tlang=").append(it) }
    return builder.toString()
}

@Deprecated(
    "Use buildTrailerSubtitleUrl with TrailerSubtitleFormat",
    ReplaceWith("buildTrailerSubtitleUrl(selected, TrailerSubtitleFormat.WEBVTT)")
)
internal fun buildTrailerSubtitleVttUrl(selected: SelectedTrailerCaptionTrack): String =
    buildTrailerSubtitleUrl(selected, TrailerSubtitleFormat.WEBVTT)
