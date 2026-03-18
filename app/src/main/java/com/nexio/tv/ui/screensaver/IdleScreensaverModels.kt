package com.nexio.tv.ui.screensaver

import androidx.compose.runtime.Immutable

@Immutable
data class IdleScreensaverSlide(
    val itemId: String,
    val itemType: String,
    val addonBaseUrl: String,
    val title: String,
    val backgroundUrl: String,
    val logoUrl: String?,
    val genres: List<String>,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val imdbRating: Float?,
    val tomatoesRating: Double? = null
)
