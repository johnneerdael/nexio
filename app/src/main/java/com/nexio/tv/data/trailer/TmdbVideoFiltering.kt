package com.nexio.tv.data.trailer

import com.nexio.tv.core.locale.TrailerLanguageMatcher
import com.nexio.tv.data.remote.api.TmdbVideoResult

/**
 * Type / site / key filter applied at the cache-write boundary. Language filtering
 * is intentionally NOT done here — the title's `original_language` is not known at
 * cache-write time, so the cache stores every type-eligible video and the language
 * gate is applied at the consumer stage (see [isTmdbVideoLanguageEligible]).
 */
internal fun filterCacheableTmdbTrailerVideos(results: List<TmdbVideoResult>): List<TmdbVideoResult> {
    return results
        .asSequence()
        .filter { (it.site ?: "").equals("YouTube", ignoreCase = true) }
        .filter { !it.key.isNullOrBlank() }
        .filter { isCacheableTmdbTrailerType(it.type) }
        .toList()
}

internal fun isCacheableTmdbTrailerType(type: String?): Boolean {
    return when (type?.trim()?.lowercase()) {
        "trailer", "teaser", "recap" -> true
        else -> false
    }
}

/**
 * Eligibility predicate applied at trailer-selection time. Accepts a video iff its
 * `iso_639_1` normalizes to the same base as the title's `original_language`.
 *
 * Strictness rules (operator decision):
 * - Video with no declared `iso_639_1` → reject.
 * - Title with no `original_language` → fall back to English-only acceptance.
 */
internal fun isTmdbVideoLanguageEligible(
    videoLanguageCode: String?,
    titleOriginalLanguage: String?
): Boolean {
    val videoLang = videoLanguageCode?.trim()?.takeIf { it.isNotBlank() } ?: return false
    val target = titleOriginalLanguage?.trim()?.takeIf { it.isNotBlank() }
    return if (target != null) {
        TrailerLanguageMatcher.matches(videoLang, target)
    } else {
        TrailerLanguageMatcher.matches(videoLang, "en")
    }
}
