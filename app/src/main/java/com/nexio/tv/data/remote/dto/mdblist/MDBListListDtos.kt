package com.nexio.tv.data.remote.dto.mdblist

import com.squareup.moshi.Json

data class MDBListUserListDto(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String?,
    @Json(name = "slug") val slug: String?,
    @Json(name = "description") val description: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "dynamic") val dynamic: Boolean? = null,
    @Json(name = "private") val private: Boolean? = null,
    @Json(name = "items") val items: Int? = null,
)

data class MDBListListItemsResponseDto(
    @Json(name = "movies") val movies: List<MDBListWatchlistItemDto>? = emptyList(),
    @Json(name = "shows") val shows: List<MDBListWatchlistItemDto>? = emptyList(),
)

data class MDBListCreateListRequestDto(
    @Json(name = "name") val name: String,
    @Json(name = "private") val private: Boolean,
)

data class MDBListUpdateListRequestDto(
    @Json(name = "name") val name: String,
    @Json(name = "private") val private: Boolean,
)

data class MDBListCreateListResponseDto(
    @Json(name = "id") val id: Long,
    @Json(name = "slug") val slug: String?,
    @Json(name = "url") val url: String?,
)

data class MDBListListMutationResponseDto(
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "id") val id: Long? = null,
    @Json(name = "name") val name: String? = null,
)
