package com.nexio.tv.data.remote.model

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
