package com.nexio.tv.core.search

data class AndroidTvNativeSearchResult(
    val id: String,
    val contentType: String,
    val title: String,
    val poster: String?,
    val background: String?,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val addonBaseUrl: String?,
    val source: AndroidTvSearchCandidateSource = AndroidTvSearchCandidateSource.LIVE_CINEMETA,
    val score: Int = 0,
    val matchExplanation: String? = null
)
