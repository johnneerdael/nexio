package com.nexio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

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
