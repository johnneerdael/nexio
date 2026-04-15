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
    val addonBaseUrl: String?
)
