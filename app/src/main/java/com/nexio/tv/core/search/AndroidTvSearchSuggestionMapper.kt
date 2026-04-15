package com.nexio.tv.core.search

data class AndroidTvSearchSuggestion(
    val rowId: Long,
    val title: String,
    val subtitle: String,
    val itemId: String,
    val contentType: String,
    val addonBaseUrl: String?,
    val productionYear: Int?,
    val durationMs: Long?
)

object AndroidTvSearchSuggestionMapper {
    private val yearPattern = Regex("""^\s*(\d{4})""")
    private val minuteRuntimePattern = Regex("""^\s*(\d{1,4})\s*(min|mins|minute|minutes)\b""", RegexOption.IGNORE_CASE)

    fun toSuggestion(
        result: AndroidTvNativeSearchResult,
        rowId: Long
    ): AndroidTvSearchSuggestion? {
        val itemId = result.id.trim()
        val contentType = result.contentType.trim()
        val title = result.title.trim()
        if (itemId.isEmpty() || contentType.isEmpty() || title.isEmpty()) return null

        return AndroidTvSearchSuggestion(
            rowId = rowId,
            title = title,
            subtitle = contentType.toDisplayLabel(),
            itemId = itemId,
            contentType = contentType,
            addonBaseUrl = result.addonBaseUrl?.trim()?.takeIf { it.isNotEmpty() },
            productionYear = parseProductionYear(result.releaseInfo),
            durationMs = parseDurationMs(result.runtime)
        )
    }

    fun toSuggestions(results: List<AndroidTvNativeSearchResult>): List<AndroidTvSearchSuggestion> {
        return results.mapIndexedNotNull { index, result ->
            toSuggestion(result, rowId = index.toLong())
        }
    }

    fun parseProductionYear(releaseInfo: String?): Int? {
        val value = releaseInfo?.trim().orEmpty()
        return yearPattern.find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    fun parseDurationMs(runtime: String?): Long? {
        val value = runtime?.trim().orEmpty()
        val minutes = minuteRuntimePattern.find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: return null
        return minutes * 60L * 1000L
    }

    private fun String.toDisplayLabel(): String {
        return when (trim().lowercase()) {
            "movie" -> "Movie"
            "series", "tv" -> "Series"
            else -> trim().replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
    }
}
