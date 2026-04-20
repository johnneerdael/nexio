package com.nexio.tv.domain.model

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
