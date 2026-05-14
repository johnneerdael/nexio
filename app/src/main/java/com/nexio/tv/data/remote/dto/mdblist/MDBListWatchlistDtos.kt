package com.nexio.tv.data.remote.dto.mdblist

import com.squareup.moshi.Json

data class MDBListWatchlistResponseDto(
    @Json(name = "movies") val movies: List<MDBListWatchlistItemDto>? = null,
    @Json(name = "shows") val shows: List<MDBListWatchlistItemDto>? = null,
)

data class MDBListWatchlistMutationRequestDto(
    @Json(name = "movies") val movies: List<MDBListWatchlistItemIdsDto>? = null,
    @Json(name = "shows") val shows: List<MDBListWatchlistItemIdsDto>? = null,
)

data class MDBListWatchlistItemDto(
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "tmdb") val tmdb: Int? = null,
    @Json(name = "tvdb") val tvdb: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
)

data class MDBListWatchlistItemIdsDto(
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "tmdb") val tmdb: Int? = null,
    @Json(name = "tvdb") val tvdb: Int? = null,
)
