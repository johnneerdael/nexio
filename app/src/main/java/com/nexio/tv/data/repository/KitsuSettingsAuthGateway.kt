package com.nexio.tv.data.repository

import javax.inject.Inject

class KitsuSettingsAuthGateway @Inject constructor(
    private val authService: KitsuAuthService
) {
    suspend fun login(username: String, password: String): Boolean =
        authService.login(username, password)

    suspend fun disconnect() = authService.disconnect()
}
