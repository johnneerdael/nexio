package com.nexio.tv.domain.model

import kotlin.math.roundToLong

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
    /**
     * "%.1f" without `String.format`. Each `String.format` call allocates a
     * Formatter + FormatSpecifier + FormatSpecifierParser + Flags +
     * FormatString[][] tower (~6 objects). On Home, this is called per card
     * build (catalog row + continue-watching + hero), 1-2× per card; with ~1900
     * cards × every pipeline emission this saturated the Formatter object pool
     * (38K retained instances, ~38 MB + ongoing churn) and contributed to GC
     * pressure that hung Modern Home navigation.
     *
     * Manual rounding via [kotlin.math.roundToLong] avoids Formatter entirely.
     * Output is locale-independent (always uses '.') which matches the prior
     * `Locale.US` choice — these numbers are display chrome, not localised.
     */
    fun formatTitleRating(value: Double): String {
        val rounded = (value * 10.0).roundToLong()
        val whole = rounded / 10
        val tenth = (if (rounded < 0) -rounded else rounded) % 10
        return "$whole.$tenth"
    }

    fun formatTitleRating(value: Float): String =
        formatTitleRating(value.toDouble())

    fun formatPercentRating(value: Double): String =
        if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            formatTitleRating(value)
        }
}
