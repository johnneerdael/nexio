package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.SimklAuthState
import com.nexio.tv.data.remote.api.SimklApi
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
    private val simklAuthDataStore: SimklAuthDataStore
) {
    fun hasRequiredCredentials(): Boolean = BuildConfig.SIMKL_CLIENT_ID.isNotBlank()

    suspend fun getCurrentAuthState(): SimklAuthState = simklAuthDataStore.state.first()

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
            simklAuthDataStore.saveDeviceFlow(body)
            return Result.success(Unit)
        }
        return Result.failure(IllegalStateException("Failed to start SIMKL auth (${response.code()})"))
    }

    suspend fun pollPin(): SimklTokenPollResult {
        if (!hasRequiredCredentials()) return SimklTokenPollResult.Failed("Missing SIMKL_CLIENT_ID")
        val state = getCurrentAuthState()
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
            simklAuthDataStore.saveAccessToken(body.accessToken)
            simklAuthDataStore.clearDeviceFlow()
            val username = fetchUserSettings()
            return SimklTokenPollResult.Approved(username)
        }
        return when (body.message?.trim()?.lowercase()) {
            "authorization pending" -> SimklTokenPollResult.Pending
            "slow down" -> {
                val nextInterval = ((state.pollInterval ?: 5) + 5).coerceAtMost(60)
                simklAuthDataStore.updatePollInterval(nextInterval)
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
            accountType = body?.account?.type
        )
        return username
    }

    suspend fun revokeAndLogout() {
        simklAuthDataStore.clearAuth()
    }

    suspend fun <T> executeAuthorizedRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? {
        val token = getCurrentAuthState().accessToken ?: return null
        return try {
            call("Bearer $token")
        } catch (e: IOException) {
            Log.w("SimklAuthService", "Network error during authorized request", e)
            null
        }
    }

    suspend fun <T> executeAuthorizedWriteRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? = executeAuthorizedRequest(call)
}
