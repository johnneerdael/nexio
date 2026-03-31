package com.nexio.tv.ui.screens.detail

import android.text.format.DateFormat
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
