package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryRemoveRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryRemoveResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktPlaybackItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRefreshTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRevokeRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktScrobbleRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktScrobbleResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowProgressResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktUserEpisodeHistoryItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktUserSettingsResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface TraktApi {

    @POST("oauth/device/code")
    suspend fun requestDeviceCode(
        @Body body: TraktDeviceCodeRequestDto
    ): Response<TraktDeviceCodeResponseDto>

    @POST("oauth/device/token")
    suspend fun requestDeviceToken(
        @Body body: TraktDeviceTokenRequestDto
    ): Response<TraktTokenResponseDto>

    @POST("oauth/token")
    suspend fun refreshToken(
        @Body body: TraktRefreshTokenRequestDto
    ): Response<TraktTokenResponseDto>

    @POST("oauth/revoke")
    suspend fun revokeToken(
        @Body body: TraktRevokeRequestDto
    ): Response<Unit>

    @GET("users/settings")
    suspend fun getUserSettings(
        @Header("Authorization") authorization: String
    ): Response<TraktUserSettingsResponseDto>

    @POST("scrobble/start")
    suspend fun scrobbleStart(
        @Header("Authorization") authorization: String,
        @Body body: TraktScrobbleRequestDto
    ): Response<TraktScrobbleResponseDto>

    @POST("scrobble/stop")
    suspend fun scrobbleStop(
        @Header("Authorization") authorization: String,
        @Body body: TraktScrobbleRequestDto
    ): Response<TraktScrobbleResponseDto>

    @GET("sync/playback/{type}")
    suspend fun getPlayback(
        @Header("Authorization") authorization: String,
        @Path("type") type: String
    ): Response<List<TraktPlaybackItemDto>>

    @GET("sync/history/episodes")
    suspend fun getEpisodeHistory(
        @Header("Authorization") authorization: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): Response<List<TraktUserEpisodeHistoryItemDto>>

    @POST("sync/history")
    suspend fun addHistory(
        @Header("Authorization") authorization: String,
        @Body body: TraktHistoryAddRequestDto
    ): Response<TraktHistoryAddResponseDto>

    @GET("sync/history/{type}/{id}")
    suspend fun getHistoryById(
        @Header("Authorization") authorization: String,
        @Path("type") type: String,
        @Path("id") id: String
    ): Response<List<TraktHistoryItemDto>>

    @GET("shows/{id}/progress/watched")
    suspend fun getShowProgressWatched(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Query("hidden") hidden: Boolean = false,
        @Query("specials") specials: Boolean = false,
        @Query("count_specials") countSpecials: Boolean = false
    ): Response<TraktShowProgressResponseDto>

    @DELETE("sync/playback/{id}")
    suspend fun deletePlayback(
        @Header("Authorization") authorization: String,
        @Path("id") playbackId: Long
    ): Response<Unit>

    @HTTP(method = "POST", path = "sync/history/remove", hasBody = true)
    suspend fun removeHistory(
        @Header("Authorization") authorization: String,
        @Body body: TraktHistoryRemoveRequestDto
    ): Response<TraktHistoryRemoveResponseDto>
}
