package com.nexio.tv.core.tvdb

data class TvdbRemoteId(
    val source: TvdbRemoteIdSource,
    val value: String,
    val sourceName: String? = null
)

data class TvdbSeriesIdentity(
    val tvdbId: Int,
    val name: String? = null,
    val year: String? = null,
    val remoteIds: Map<TvdbRemoteIdSource, Set<String>> = emptyMap()
)
