package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.simkl.SimklPinResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklPinStatusResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryRemoveRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklLastActivitiesResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklLibraryItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklPlaybackItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklUserSettingsResponseDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface SimklApi {
    @GET("oauth/pin")
    suspend fun requestPinCode(
        @Query("redirect") redirect: String? = null
    ): Response<SimklPinResponseDto>

    @GET("oauth/pin/{user_code}")
    suspend fun getPinStatus(
        @Path("user_code") userCode: String
    ): Response<SimklPinStatusResponseDto>

    @POST("users/settings")
    suspend fun getUserSettings(
        @Header("Authorization") authorization: String
    ): Response<SimklUserSettingsResponseDto>

    @POST("sync/activities")
    suspend fun getLastActivities(
        @Header("Authorization") authorization: String
    ): Response<SimklLastActivitiesResponseDto>

    @GET("sync/all-items/{type}/{status}")
    suspend fun getAllItemsByStatusRaw(
        @Header("Authorization") authorization: String,
        @Path("type") type: String,
        @Path("status") status: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = null,
        @Query("episode_watched_at") episodeWatchedAt: String? = null
    ): Response<ResponseBody>

    @GET("sync/all-items/{type}/")
    suspend fun getAllItemsByTypeRaw(
        @Header("Authorization") authorization: String,
        @Path("type") type: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = null,
        @Query("episode_watched_at") episodeWatchedAt: String? = null
    ): Response<ResponseBody>

    @GET("sync/all-items/")
    suspend fun getAllItemsRaw(
        @Header("Authorization") authorization: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = null
    ): Response<ResponseBody>

    @GET("sync/playback/{type}")
    suspend fun getPlayback(
        @Header("Authorization") authorization: String,
        @Path("type") type: String
    ): Response<List<SimklPlaybackItemDto>>

    @DELETE("sync/playback/{id}")
    suspend fun deletePlayback(
        @Header("Authorization") authorization: String,
        @Path("id") playbackId: Long
    ): Response<Unit>

    @POST("sync/add-to-list")
    suspend fun addToList(
        @Header("Authorization") authorization: String,
        @Body body: SimklAddToListRequestDto
    ): Response<SimklAddToListResponseDto>

    @POST("sync/history")
    suspend fun addHistory(
        @Header("Authorization") authorization: String,
        @Body body: SimklHistoryAddRequestDto
    ): Response<Unit>

    @POST("sync/history/remove")
    suspend fun removeFromHistoryAndLists(
        @Header("Authorization") authorization: String,
        @Body body: SimklHistoryRemoveRequestDto
    ): Response<Unit>

    @POST("scrobble/start")
    suspend fun scrobbleStart(
        @Header("Authorization") authorization: String,
        @Body body: SimklScrobbleRequestDto
    ): Response<SimklScrobbleResponseDto>

    @POST("scrobble/pause")
    suspend fun scrobblePause(
        @Header("Authorization") authorization: String,
        @Body body: SimklScrobbleRequestDto
    ): Response<SimklScrobbleResponseDto>

    @POST("scrobble/stop")
    suspend fun scrobbleStop(
        @Header("Authorization") authorization: String,
        @Body body: SimklScrobbleRequestDto
    ): Response<SimklScrobbleResponseDto>

    @POST("scrobble/checkin")
    suspend fun checkin(
        @Header("Authorization") authorization: String,
        @Body body: SimklScrobbleRequestDto
    ): Response<SimklScrobbleResponseDto>
}
