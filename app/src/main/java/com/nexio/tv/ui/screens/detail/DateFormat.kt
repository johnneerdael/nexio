package com.nexio.tv.ui.screens.detail

import android.text.format.DateFormat
import com.nexio.tv.domain.model.ContentType
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun formatReleaseDate(
    isoDate: String,
    locale: Locale = Locale.getDefault(),
    patternResolver: (Locale) -> String = ::releaseDatePatternForLocale
): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val outputFormat = SimpleDateFormat(patternResolver(locale), locale)
        val date = inputFormat.parse(isoDate)
        date?.let { outputFormat.format(it) } ?: ""
    } catch (e: Exception) {
        try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outputFormat = SimpleDateFormat(patternResolver(locale), locale)
            val date = inputFormat.parse(isoDate)
            date?.let { outputFormat.format(it) } ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

internal fun releaseDatePatternForLocale(locale: Locale): String {
    return DateFormat.getBestDateTimePattern(locale, "dMMMMy")
}

fun formatPrimaryReleaseInfo(
    contentType: ContentType,
    releaseInfo: String?,
    locale: Locale = Locale.getDefault(),
    patternResolver: (Locale) -> String = ::releaseDatePatternForLocale
): String? {
    val normalizedReleaseInfo = releaseInfo?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (contentType) {
        ContentType.MOVIE -> {
            formatReleaseDate(
                isoDate = normalizedReleaseInfo,
                locale = locale,
                patternResolver = patternResolver
            ).ifBlank {
                extractReleaseYear(normalizedReleaseInfo) ?: normalizedReleaseInfo
            }
        }

        else -> extractReleaseYear(normalizedReleaseInfo) ?: normalizedReleaseInfo
    }
}

internal fun extractReleaseYear(releaseInfo: String): String? {
    return Regex("""\b\d{4}\b""").find(releaseInfo)?.value
}
