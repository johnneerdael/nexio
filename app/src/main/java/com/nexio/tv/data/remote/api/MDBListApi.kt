package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistMutationRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistResponseDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Url

interface MDBListApi {
    @GET("user")
    suspend fun getUser(
        @Query("apikey") apiKey: String
    ): Response<Unit>

    @POST("rating/{mediaType}/{ratingType}")
    suspend fun getRating(
        @Path("mediaType") mediaType: String,
        @Path("ratingType") ratingType: String,
        @Query("apikey") apiKey: String,
        @Body body: MDBListRatingRequestDto
    ): Response<MDBListRatingResponseDto>

    @GET("watchlist/items")
    suspend fun getWatchlistItems(
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("unified") unified: Boolean = true,
    ): Response<MDBListWatchlistResponseDto>

    @POST("watchlist/items/{action}")
    suspend fun mutateWatchlistItems(
        @Path("action") action: String,
        @Query("apikey") apiKey: String,
        @Body body: MDBListWatchlistMutationRequestDto,
    ): Response<Unit>

    @POST("scrobble/{action}")
    suspend fun scrobble(
        @Path("action") action: String,
        @Query("apikey") apiKey: String,
        @Body body: MDBListScrobbleRequestDto,
    ): Response<Unit>

    @GET
    suspend fun getRaw(
        @Url relativeUrl: String,
        @Query("apikey") apiKey: String
    ): Response<ResponseBody>

    @GET
    suspend fun getRawWithQuery(
        @Url relativeUrl: String,
        @QueryMap query: Map<String, String>
    ): Response<ResponseBody>
}
