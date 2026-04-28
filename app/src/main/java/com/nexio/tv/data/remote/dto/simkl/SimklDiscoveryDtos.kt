package com.nexio.tv.data.remote.dto.simkl

import com.squareup.moshi.Json

data class SimklDiscoveryItemDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "released") val released: String? = null,
    @Json(name = "date") val date: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "runtime") val runtime: Int? = null,
    @Json(name = "genres") val genres: List<String>? = null,
    @Json(name = "poster") val poster: String? = null,
    @Json(name = "fanart") val fanart: String? = null,
    @Json(name = "ids") val ids: SimklIdsDto? = null,
    @Json(name = "ratings") val ratings: SimklDiscoveryRatingsDto? = null,
    @Json(name = "trailer") val trailer: String? = null
)

data class SimklDiscoveryRatingsDto(
    @Json(name = "imdb") val imdb: Double? = null,
    @Json(name = "simkl") val simkl: Double? = null
)
