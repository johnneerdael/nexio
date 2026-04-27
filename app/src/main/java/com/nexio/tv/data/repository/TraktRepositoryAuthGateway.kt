package com.nexio.tv.data.repository

import com.nexio.tv.data.local.TraktAuthState
import javax.inject.Inject

class TraktRepositoryAuthGateway @Inject constructor(
    private val authService: TraktAuthService
) {
    fun hasRequiredCredentials(): Boolean = authService.hasRequiredCredentials()

    suspend fun getCurrentAuthState(): TraktAuthState = authService.getCurrentAuthState()

    suspend fun getAuthState(session: TrackingAuthSession): TraktAuthState = authService.getAuthState(session)

    suspend fun currentAuthSession(): TrackingAuthSession = authService.currentAuthSession()

    fun currentTraktProfileId(): Int = authService.currentTraktProfileId()
}
