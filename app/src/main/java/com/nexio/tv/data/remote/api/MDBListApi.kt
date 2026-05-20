package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.mdblist.MDBListCreateListRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListCreateListResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListListItemsResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListListMutationResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListPlaybackResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleClearRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleClearResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListUpdateListRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListUserListDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchedResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchedSyncRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistMutationRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistResponseDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.PUT
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

    @GET("lists/user")
    suspend fun getMyLists(
        @Query("apikey") apiKey: String,
        @Query("sort") sort: String = "ranked",
        @Query("unified") unified: Boolean = true,
    ): Response<List<MDBListUserListDto>>

    @GET("lists/{listid}/items")
    suspend fun getListItems(
        @Path("listid") listId: Long,
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("unified") unified: Boolean = true,
    ): Response<MDBListListItemsResponseDto>

    @POST("lists/user/add")
    suspend fun createStaticList(
        @Query("apikey") apiKey: String,
        @Body body: MDBListCreateListRequestDto,
    ): Response<MDBListCreateListResponseDto>

    @PUT("lists/{listid}")
    suspend fun updateStaticList(
        @Path("listid") listId: Long,
        @Query("apikey") apiKey: String,
        @Body body: MDBListUpdateListRequestDto,
    ): Response<MDBListListMutationResponseDto>

    @DELETE("lists/{listid}")
    suspend fun deleteStaticList(
        @Path("listid") listId: Long,
        @Query("apikey") apiKey: String,
    ): Response<MDBListListMutationResponseDto>

    @POST("lists/{listid}/items/{action}")
    suspend fun mutateStaticListItems(
        @Path("listid") listId: Long,
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

    @GET("sync/playback")
    suspend fun getPlayback(
        @Query("apikey") apiKey: String,
    ): Response<MDBListPlaybackResponseDto>

    @GET("sync/watched")
    suspend fun getWatched(
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int = 1000,
        @Query("offset") offset: Int = 0,
        @Query("since") since: String? = null,
    ): Response<MDBListWatchedResponseDto>

    @POST("sync/watched")
    suspend fun addWatched(
        @Query("apikey") apiKey: String,
        @Body body: MDBListWatchedSyncRequestDto,
    ): Response<Unit>

    @POST("sync/watched/remove")
    suspend fun removeWatched(
        @Query("apikey") apiKey: String,
        @Body body: MDBListWatchedSyncRequestDto,
    ): Response<Unit>

    @POST("scrobble/clear")
    suspend fun clearScrobble(
        @Query("apikey") apiKey: String,
        @Body body: MDBListScrobbleClearRequestDto,
    ): Response<MDBListScrobbleClearResponseDto>

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
