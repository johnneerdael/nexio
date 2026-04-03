package com.nexio.tv.data.trailer

import com.nexio.tv.data.remote.api.TmdbVideoResult

internal fun filterCacheableTmdbTrailerVideos(results: List<TmdbVideoResult>): List<TmdbVideoResult> {
    return results
        .asSequence()
        .filter { (it.site ?: "").equals("YouTube", ignoreCase = true) }
        .filter { !it.key.isNullOrBlank() }
        .filter { isEnglishTmdbVideoLanguage(it.iso6391) }
        .filter { isCacheableTmdbTrailerType(it.type) }
        .toList()
}

internal fun isCacheableTmdbTrailerType(type: String?): Boolean {
    return when (type?.trim()?.lowercase()) {
        "trailer", "teaser", "recap" -> true
        else -> false
    }
}
