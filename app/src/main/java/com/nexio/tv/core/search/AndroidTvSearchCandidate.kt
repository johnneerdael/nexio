package com.nexio.tv.core.search

enum class AndroidTvSearchCandidateSource {
    LOCAL_HOME,
    LIVE_CINEMETA
}

enum class AndroidTvSearchTitleMatch {
    EXACT,
    PREFIX,
    CONTAINS
}

data class AndroidTvSearchCandidate(
    val id: String,
    val contentType: String,
    val title: String,
    val poster: String?,
    val background: String?,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val addonBaseUrl: String?,
    val source: AndroidTvSearchCandidateSource
) {
    val identityKey: String
        get() = "${contentType.trim().lowercase()}:${id.trim()}"
}

data class AndroidTvSearchScoredCandidate(
    val candidate: AndroidTvSearchCandidate,
    val titleMatch: AndroidTvSearchTitleMatch,
    val score: Int,
    val hasProductionYear: Boolean,
    val hasDuration: Boolean,
    val explanation: String
)
