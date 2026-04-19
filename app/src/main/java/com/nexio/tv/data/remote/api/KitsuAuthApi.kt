package com.nexio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface KitsuAuthApi {
    @POST("token")
    suspend fun token(@Body request: KitsuTokenRequest): Response<KitsuTokenResponse>
}

@JsonClass(generateAdapter = true)
data class KitsuTokenRequest(
    @Json(name = "grant_type") val grantType: String,
    @Json(name = "username") val username: String? = null,
    @Json(name = "password") val password: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "client_id") val clientId: String = "",
    @Json(name = "client_secret") val clientSecret: String = ""
)

@JsonClass(generateAdapter = true)
data class KitsuTokenResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "expires_in") val expiresIn: Long? = null,
    @Json(name = "created_at") val createdAt: Long? = null,
    @Json(name = "scope") val scope: String? = null,
    @Json(name = "token_type") val tokenType: String? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null
)
