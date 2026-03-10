package com.nexio.tv.data.remote.dto.debrid

import com.squareup.moshi.Json

data class RealDebridDeviceCodeResponseDto(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "interval") val interval: Int? = null,
    @Json(name = "verification_url") val verificationUrl: String
)

data class RealDebridDeviceCredentialsResponseDto(
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_secret") val clientSecret: String
)

data class RealDebridTokenResponseDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "token_type") val tokenType: String,
    @Json(name = "refresh_token") val refreshToken: String
)

data class RealDebridUserDto(
    @Json(name = "username") val username: String? = null
)

data class RealDebridDownloadDto(
    @Json(name = "id") val id: String,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "mimeType") val mimeType: String? = null,
    @Json(name = "filesize") val fileSize: Long? = null,
    @Json(name = "link") val link: String? = null,
    @Json(name = "host") val host: String? = null,
    @Json(name = "chunks") val chunks: Int? = null,
    @Json(name = "download") val download: String? = null,
    @Json(name = "generated") val generated: String? = null,
    @Json(name = "type") val type: String? = null
)
