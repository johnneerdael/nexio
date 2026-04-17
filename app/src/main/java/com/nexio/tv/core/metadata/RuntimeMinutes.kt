package com.nexio.tv.core.metadata

fun parseRuntimeMinutes(raw: String?): Int? {
    return raw
        ?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1) }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
}

fun episodeRuntimeOrSeriesAverageMinutes(
    episodeRuntimeMinutes: Int?,
    seriesRuntime: String?
): Int? {
    return episodeRuntimeMinutes?.takeIf { it > 0 }
        ?: parseRuntimeMinutes(seriesRuntime)
}
