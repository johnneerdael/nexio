package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.debrid.TorBoxCachedTorrentDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxCheckCachedRequestDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxCreateTorrentDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxDeviceCodeDataDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxDeviceTokenDataDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxDeviceTokenRequestDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxEnvelopeDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxTorrentListItemDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxUserDto
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Body

interface TorBoxApi {
    @GET("v1/api/user/me")
    suspend fun getCurrentUser(
        @Header("Authorization") authorization: String
    ): Response<TorBoxEnvelopeDto<TorBoxUserDto>>

    @GET("v1/api/user/auth/device/start")
    suspend fun startDeviceCode(
        @Query("app") app: String = "Nexio"
    ): Response<TorBoxEnvelopeDto<TorBoxDeviceCodeDataDto>>

    @POST("v1/api/user/auth/device/token")
    suspend fun pollDeviceToken(
        @Body body: TorBoxDeviceTokenRequestDto
    ): Response<TorBoxEnvelopeDto<TorBoxDeviceTokenDataDto>>

    @POST("v1/api/torrents/checkcached")
    suspend fun checkCachedTorrents(
        @Header("Authorization") authorization: String,
        @Query("format") format: String = "list",
        @Query("list_files") listFiles: Boolean = true,
        @Body body: TorBoxCheckCachedRequestDto
    ): Response<TorBoxEnvelopeDto<List<TorBoxCachedTorrentDto>>>

    @Multipart
    @POST("v1/api/torrents/createtorrent")
    suspend fun createTorrent(
        @Header("Authorization") authorization: String,
        @Part("magnet") magnet: RequestBody,
        @Part("allow_zip") allowZip: RequestBody,
        @Part("add_only_if_cached") addOnlyIfCached: RequestBody
    ): Response<TorBoxEnvelopeDto<TorBoxCreateTorrentDto>>

    @GET("v1/api/torrents/mylist")
    suspend fun getMyTorrentList(
        @Header("Authorization") authorization: String,
        @Query("id") id: Int? = null,
        @Query("bypass_cache") bypassCache: Boolean? = true,
        @Query("offset") offset: Int? = null,
        @Query("limit") limit: Int? = null
    ): Response<TorBoxEnvelopeDto<List<TorBoxTorrentListItemDto>>>

    @GET("v1/api/torrents/requestdl")
    suspend fun requestDownloadLink(
        @Query("token") token: String,
        @Query("torrent_id") torrentId: Int,
        @Query("file_id") fileId: Int? = 0,
        @Query("zip_link") zipLink: Boolean = false,
        @Query("redirect") redirect: Boolean = false
    ): Response<TorBoxEnvelopeDto<String>>
}
