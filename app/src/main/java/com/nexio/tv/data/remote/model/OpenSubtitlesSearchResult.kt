package com.nexio.tv.data.remote.model

/**
 * Flat domain row produced by the OpenSubtitles search layer.
 *
 * One per parsed [com.nexio.tv.data.remote.dto.OpenSubtitlesRestSubtitleDto] after
 * the numeric-as-string fields have been normalized. Consumed by
 * `OpenSubtitlesSourceImpl` for filtering, ranking, and conversion to the app's
 * `Subtitle` model.
 *
 * `movieHash` is non-null only when the rest.opensubtitles.org row carried
 * `MatchedBy:"moviehash"` — drives the hash-match indicator in the UI.
 */
data class OpenSubtitlesSearchResult(
    val subtitleId: String,
    val language: String,
    val languageCode: String,
    val downloadUrl: String,
    val filename: String?,
    val movieHash: String?,
    val fps: Double?,
    val downloads: Int?,
    val trusted: Boolean,
    val aiTranslated: Boolean,
    val uploadedAtEpochSeconds: Long?
)
