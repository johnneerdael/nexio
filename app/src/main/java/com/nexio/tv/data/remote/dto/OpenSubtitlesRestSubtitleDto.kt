package com.nexio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One row of the rest.opensubtitles.org `/search/...` JSON array.
 *
 * Numeric fields arrive as strings ("0", "12909756", "23.980") so they're typed as `String?`
 * here and converted at the [com.nexio.tv.data.repository.OpenSubtitlesSourceImpl] layer.
 *
 * `MatchedBy` is "moviehash" when the row was matched by the file hash and "imdbid"/"fulltext"
 * otherwise — used to drive the hash-match indicator in the UI.
 */
@JsonClass(generateAdapter = true)
data class OpenSubtitlesRestSubtitleDto(
    @Json(name = "MatchedBy") val matchedBy: String?,
    @Json(name = "IDSubtitleFile") val idSubtitleFile: String?,
    @Json(name = "SubFileName") val subFileName: String?,
    @Json(name = "SubLanguageID") val subLanguageID: String?,
    @Json(name = "ISO639") val iso639: String?,
    @Json(name = "LanguageName") val languageName: String?,
    @Json(name = "MovieHash") val movieHash: String?,
    @Json(name = "MovieByteSize") val movieByteSize: String?,
    @Json(name = "MovieFPS") val movieFPS: String?,
    @Json(name = "MovieKind") val movieKind: String?,
    @Json(name = "SubFromTrusted") val subFromTrusted: String?,
    @Json(name = "SubAutoTranslation") val subAutoTranslation: String?,
    @Json(name = "SubHearingImpaired") val subHearingImpaired: String?,
    @Json(name = "SubDownloadsCnt") val subDownloadsCnt: String?,
    @Json(name = "SubDownloadLink") val subDownloadLink: String?,
    @Json(name = "ZipDownloadLink") val zipDownloadLink: String?,
    @Json(name = "SubAddDate") val subAddDate: String?,
    @Json(name = "SubFormat") val subFormat: String?,
    @Json(name = "SubRating") val subRating: String?,
    @Json(name = "MovieReleaseName") val movieReleaseName: String?
)
