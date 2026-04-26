package com.nexio.tv.core.tvdb

import javax.inject.Inject

class TvdbSettingsAuthGateway @Inject constructor(
    private val authService: TvdbAuthService
) {
    suspend fun validateCredentialsResult(apiKey: String, subscriberPin: String): TvdbAuthResult =
        authService.validateCredentialsResult(apiKey, subscriberPin)
}
