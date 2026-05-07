package com.nexio.tv.domain.model

import java.util.Locale

enum class TitleRatingSource {
    IMDB,
    TMDB
}

data class TitleRating(
    val value: Double,
    val source: TitleRatingSource
)

fun Float.toTitleRating(source: TitleRatingSource): TitleRating =
    TitleRating(value = toDouble(), source = source)

fun Double.toTitleRating(source: TitleRatingSource): TitleRating =
    TitleRating(value = this, source = source)

fun TitleRatingSource?.orDefault(defaultSource: TitleRatingSource = TitleRatingSource.IMDB): TitleRatingSource =
    this ?: defaultSource

object RatingValueValidator {
    fun validTitleRating(value: Double?): Boolean =
        value != null && value.isFinite() && value in 0.0..10.0

    fun validTitleRating(value: Float?): Boolean =
        value != null && value.isFinite() && value in 0f..10f

    fun validPercentRating(value: Double?): Boolean =
        value != null && value.isFinite() && value in 0.0..100.0

    fun validPercentRating(value: Float?): Boolean =
        value != null && value.isFinite() && value in 0f..100f

    fun sanitizeTitleRating(value: Double?): Double? =
        value?.takeIf(::validTitleRating)

    fun sanitizeTitleRating(value: Float?): Float? =
        value?.takeIf(::validTitleRating)

    fun sanitizePercentRating(value: Double?): Double? =
        value?.takeIf(::validPercentRating)
}

object RatingDisplayFormatter {
    fun formatTitleRating(value: Double): String =
        String.format(Locale.US, "%.1f", value)

    fun formatTitleRating(value: Float): String =
        formatTitleRating(value.toDouble())

    fun formatPercentRating(value: Double): String =
        if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
}
