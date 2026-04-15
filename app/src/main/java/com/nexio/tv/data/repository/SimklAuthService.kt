package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRoute
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.SimklAuthState
import com.nexio.tv.data.remote.api.SimklApi
import com.nexio.tv.data.remote.SimklRequestGate
import com.nexio.tv.domain.model.TrackingProvider
import kotlinx.coroutines.flow.first
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SimklTokenPollResult {
    data object Pending : SimklTokenPollResult
    data class SlowDown(val pollIntervalSeconds: Int) : SimklTokenPollResult
    data class Approved(val username: String?) : SimklTokenPollResult
    data class Failed(val reason: String) : SimklTokenPollResult
}

@Singleton
class SimklAuthService @Inject constructor(
    private val simklApi: SimklApi,
    private val simklAuthDataStore: SimklAuthDataStore,
    private val requestGate: SimklRequestGate,
    private val profileManager: ProfileManager,
    private val profileModeRouter: ProfileModeRouter,
    private val profileBoundary: ProfileBoundary
) {
    fun hasRequiredCredentials(): Boolean = BuildConfig.SIMKL_CLIENT_ID.isNotBlank()

    suspend fun getCurrentAuthState(): SimklAuthState =
        simklAuthDataStore.stateForProfile(currentRoutedProfileId()).first()

    suspend fun startPinAuth(): Result<Unit> {
        if (!hasRequiredCredentials()) {
            return Result.failure(IllegalStateException("Missing SIMKL_CLIENT_ID"))
        }
        val response = try {
            simklApi.requestPinCode()
        } catch (e: IOException) {
            return Result.failure(IllegalStateException("Network error, please try again"))
        }
        val body = response.body()
        if (response.isSuccessful && body?.deviceCode != null && body.userCode != null) {
            simklAuthDataStore.saveDeviceFlow(body, profileId = currentRoutedProfileId())
            return Result.success(Unit)
        }
        return Result.failure(IllegalStateException("Failed to start SIMKL auth (${response.code()})"))
    }

    suspend fun pollPin(): SimklTokenPollResult {
        if (!hasRequiredCredentials()) return SimklTokenPollResult.Failed("Missing SIMKL_CLIENT_ID")
        val state = getCurrentAuthState()
        val profileId = currentRoutedProfileId()
        val userCode = state.userCode
        if (userCode.isNullOrBlank()) return SimklTokenPollResult.Failed("No active SIMKL PIN code")

        val response = try {
            simklApi.getPinStatus(userCode)
        } catch (e: IOException) {
            return SimklTokenPollResult.Failed("Network error, will retry")
        }
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            return SimklTokenPollResult.Failed("PIN polling failed (${response.code()})")
        }
        if (body.result.equals("OK", ignoreCase = true) && !body.accessToken.isNullOrBlank()) {
            simklAuthDataStore.saveAccessToken(body.accessToken, profileId = profileId)
            simklAuthDataStore.clearDeviceFlow(profileId)
            val username = fetchUserSettings()
            return SimklTokenPollResult.Approved(username)
        }
        return when (body.message?.trim()?.lowercase()) {
            "authorization pending" -> SimklTokenPollResult.Pending
            "slow down" -> {
                val nextInterval = ((state.pollInterval ?: 5) + 5).coerceAtMost(60)
                simklAuthDataStore.updatePollInterval(nextInterval, profileId = profileId)
                SimklTokenPollResult.SlowDown(nextInterval)
            }
            else -> SimklTokenPollResult.Failed(body.message ?: "Authorization failed")
        }
    }

    suspend fun fetchUserSettings(): String? {
        val response = executeAuthorizedRequest { authHeader ->
            simklApi.getUserSettings(authorization = authHeader)
        } ?: return null
        if (!response.isSuccessful) return null
        val body = response.body()
        val username = body?.user?.name
        simklAuthDataStore.saveUser(
            username = username,
            accountId = body?.account?.id,
            accountType = body?.account?.type,
            profileId = currentRoutedProfileId()
        )
        return username
    }

    suspend fun revokeAndLogout() {
        simklAuthDataStore.clearAuth(currentRoutedProfileId())
    }

    suspend fun <T> executeAuthorizedRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? {
        val token = getCurrentAuthState().accessToken ?: return null
        return try {
            requestGate.acquire { call("Bearer $token") }
        } catch (e: IOException) {
            Log.w("SimklAuthService", "Network error during authorized request", e)
            null
        }
    }

    suspend fun <T> executeAuthorizedWriteRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? = executeAuthorizedRequest(call)

    private fun currentRoutedProfileId(): Int {
        return when (val route = profileModeRouter.routeFor(profileManager.activeProfileId.value)) {
            ProfileModeRoute.DefaultLegacyRoute -> profileModeRouter.defaultLegacyProfileId()
            is ProfileModeRoute.SecondaryProfileRoute -> {
                profileBoundary.authRoute(route, TrackingProvider.SIMKL).profileId
            }
            is ProfileModeRoute.InvalidProfileRoute -> error("Invalid active profile id ${route.profileId}")
        }
    }
}
