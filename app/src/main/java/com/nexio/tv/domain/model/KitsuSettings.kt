package com.nexio.tv.domain.model

data class KitsuSettings(
    val enabled: Boolean = true,
    val username: String = "",
    val authenticated: Boolean = false,
    val accessTokenSecretRef: String? = null,
    val refreshTokenSecretRef: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val includeNsfw: Boolean = false
)
