package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCodeResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCredentialsResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridDownloadDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTokenResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridUserDto
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface RealDebridApi {
    @GET("oauth/v2/device/code")
    suspend fun requestDeviceCode(
        @Query("client_id") clientId: String,
        @Query("new_credentials") newCredentials: String = "yes"
    ): Response<RealDebridDeviceCodeResponseDto>

    @GET("oauth/v2/device/credentials")
    suspend fun requestDeviceCredentials(
        @Query("client_id") clientId: String,
        @Query("code") deviceCode: String
    ): Response<RealDebridDeviceCredentialsResponseDto>

    @FormUrlEncoded
    @POST("oauth/v2/token")
    suspend fun requestToken(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code") code: String,
        @Field("grant_type") grantType: String
    ): Response<RealDebridTokenResponseDto>

    @GET("rest/1.0/user")
    suspend fun getCurrentUser(
        @Header("Authorization") authorization: String
    ): Response<RealDebridUserDto>

    @GET("rest/1.0/downloads")
    suspend fun getDownloads(
        @Header("Authorization") authorization: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 200
    ): Response<List<RealDebridDownloadDto>>

    @GET("rest/1.0/disable_access_token")
    suspend fun disableCurrentAccessToken(
        @Header("Authorization") authorization: String
    ): Response<Unit>
}
