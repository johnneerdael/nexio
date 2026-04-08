package com.nexio.tv.data.remote.dto.simkl

import com.squareup.moshi.Json

data class SimklPinResponseDto(
    @Json(name = "result") val result: String? = null,
    @Json(name = "device_code") val deviceCode: String? = null,
    @Json(name = "user_code") val userCode: String? = null,
    @Json(name = "verification_url") val verificationUrl: String? = null,
    @Json(name = "expires_in") val expiresIn: Int = 900,
    @Json(name = "interval") val interval: Int = 5
)

data class SimklPinStatusResponseDto(
    @Json(name = "result") val result: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "access_token") val accessToken: String? = null
)

data class SimklUserSettingsResponseDto(
    @Json(name = "user") val user: SimklUserDto? = null,
    @Json(name = "account") val account: SimklAccountDto? = null
)

data class SimklUserDto(
    @Json(name = "name") val name: String? = null
)

data class SimklAccountDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "type") val type: String? = null
)
