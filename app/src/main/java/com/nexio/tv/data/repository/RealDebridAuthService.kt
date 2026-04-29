package com.nexio.tv.data.repository

import com.nexio.tv.BuildConfig
import com.nexio.tv.data.integration.debrid.RealDebridAuthIntegrationProvider
import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.local.RealDebridAuthState
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCredentialsResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCodeResponseDto
import kotlinx.coroutines.flow.first
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * F2-C-06 carve-out: exempt from the [IntegrationBoundaryTest] Retrofit-usage check.
 *
 * This service drives the Real-Debrid OAuth device-code flow through
 * [RealDebridAuthIntegrationProvider], which wraps raw Retrofit calls that predate the
 * [IntegrationRuntime]. The multi-step flow (device-code request → credential poll → token
 * exchange) and subsequent token refresh all run before — or independently of — any runtime
 * context, making it impractical to route through the runtime at present.
 *
 * Migration plan: when the runtime gains support for pre-auth or session-less contexts (no current
 * ETA), route these flows through it and remove this file from the boundary allowlist.
 *
 * Tracking: review-dossier-2/09-known-gaps.md F2-C-06.
 */
private const val REAL_DEBRID_DEVICE_GRANT_TYPE = "http://oauth.net/grant_type/device/1.0"

sealed interface RealDebridTokenPollResult {
    data object Pending : RealDebridTokenPollResult
    data object Expired : RealDebridTokenPollResult
    data object Denied : RealDebridTokenPollResult
    data class Approved(val username: String?) : RealDebridTokenPollResult
    data class Failed(val reason: String) : RealDebridTokenPollResult
}

@Singleton
class RealDebridAuthService @Inject constructor(
    private val realDebridAuthIntegrationProvider: RealDebridAuthIntegrationProvider,
    private val realDebridAuthDataStore: RealDebridAuthDataStore
) {
    private val refreshLeewayMs = 60_000L

    fun hasRequiredCredentials(): Boolean = BuildConfig.REAL_DEBRID_CLIENT_ID.isNotBlank()
    private fun hasClientSecret(): Boolean = BuildConfig.REAL_DEBRID_CLIENT_SECRET.isNotBlank()

    suspend fun getCurrentAuthState(): RealDebridAuthState = realDebridAuthDataStore.state.first()

    suspend fun startDeviceAuth(): Result<RealDebridDeviceCodeResponseDto> {
        if (!hasRequiredCredentials()) {
            return Result.failure(IllegalStateException("Missing REAL_DEBRID_CLIENT_ID"))
        }

        val response = runCatching {
            realDebridAuthIntegrationProvider.requestDeviceCode(
                clientId = BuildConfig.REAL_DEBRID_CLIENT_ID,
                newCredentials = if (hasClientSecret()) null else "yes"
            )
        }.getOrElse { error ->
            return Result.failure(IllegalStateException(error.message ?: "Failed to start Real-Debrid auth"))
        } ?: return Result.failure(IllegalStateException("Failed to start Real-Debrid auth"))

        val body = response.body()
        if (!response.isSuccessful || body == null) {
            return Result.failure(IllegalStateException("Failed to start Real-Debrid auth (${response.code()})"))
        }

        realDebridAuthDataStore.saveDeviceFlow(body)
        return Result.success(body)
    }

    suspend fun pollDeviceToken(): RealDebridTokenPollResult {
        if (!hasRequiredCredentials()) {
            return RealDebridTokenPollResult.Failed("Missing REAL_DEBRID_CLIENT_ID")
        }

        val state = getCurrentAuthState()
        val deviceCode = state.deviceCode
        if (deviceCode.isNullOrBlank()) {
            return RealDebridTokenPollResult.Failed("No active Real-Debrid device flow")
        }

        val credentialState = if (hasClientSecret()) {
            val appCredentials = RealDebridDeviceCredentialsResponseDto(
                clientId = BuildConfig.REAL_DEBRID_CLIENT_ID,
                clientSecret = BuildConfig.REAL_DEBRID_CLIENT_SECRET
            )
            realDebridAuthDataStore.saveUserCredentials(appCredentials)
            getCurrentAuthState()
        } else if (state.userClientId.isNullOrBlank() || state.userClientSecret.isNullOrBlank()) {
            val credentialResponse = runCatching {
                realDebridAuthIntegrationProvider.requestDeviceCredentials(
                    clientId = BuildConfig.REAL_DEBRID_CLIENT_ID,
                    deviceCode = deviceCode
                )
            }.getOrElse { error ->
                return RealDebridTokenPollResult.Failed(error.message ?: "Failed to check Real-Debrid approval")
            } ?: return RealDebridTokenPollResult.Failed("Failed to check Real-Debrid approval")

            if (!credentialResponse.isSuccessful) {
                return when (credentialResponse.code()) {
                    400, 403, 404 -> RealDebridTokenPollResult.Pending
                    410 -> {
                        realDebridAuthDataStore.clearDeviceFlow()
                        RealDebridTokenPollResult.Expired
                    }
                    else -> RealDebridTokenPollResult.Failed("Approval check failed (${credentialResponse.code()})")
                }
            }

            val credentials = credentialResponse.body()
                ?: return RealDebridTokenPollResult.Failed("Missing Real-Debrid credentials")
            if (credentials.clientId.isBlank() || credentials.clientSecret.isBlank()) {
                return RealDebridTokenPollResult.Pending
            }
            realDebridAuthDataStore.saveUserCredentials(credentials)
            getCurrentAuthState()
        } else {
            state
        }

        val clientId = credentialState.userClientId
            ?: return RealDebridTokenPollResult.Failed("Missing Real-Debrid client id")
        val clientSecret = credentialState.userClientSecret
            ?: return RealDebridTokenPollResult.Failed("Missing Real-Debrid client secret")

        val tokenResponse = runCatching {
            realDebridAuthIntegrationProvider.requestToken(
                clientId = clientId,
                clientSecret = clientSecret,
                code = deviceCode,
                grantType = REAL_DEBRID_DEVICE_GRANT_TYPE
            )
        }.getOrElse { error ->
            return RealDebridTokenPollResult.Failed(error.message ?: "Failed to fetch Real-Debrid token")
        } ?: return RealDebridTokenPollResult.Failed("Failed to fetch Real-Debrid token")

        val tokenBody = tokenResponse.body()
        if (!tokenResponse.isSuccessful || tokenBody == null) {
            return when (tokenResponse.code()) {
                400, 403, 404 -> RealDebridTokenPollResult.Pending
                410 -> {
                    realDebridAuthDataStore.clearDeviceFlow()
                    RealDebridTokenPollResult.Expired
                }
                else -> RealDebridTokenPollResult.Failed("Token polling failed (${tokenResponse.code()})")
            }
        }

        realDebridAuthDataStore.saveToken(tokenBody)
        realDebridAuthDataStore.clearDeviceFlow()
        val username = fetchCurrentUsername()
        realDebridAuthDataStore.saveUsername(username)
        return RealDebridTokenPollResult.Approved(username)
    }

    suspend fun refreshTokenIfNeeded(force: Boolean = false): Boolean {
        val state = getCurrentAuthState()
        if (!state.isAuthenticated) return false
        if (!force && !isTokenExpiredOrExpiring(state)) return true

        val clientId = state.userClientId ?: return false
        val clientSecret = state.userClientSecret ?: return false
        val refreshToken = state.refreshToken ?: return false

        val response = runCatching {
            realDebridAuthIntegrationProvider.requestToken(
                clientId = clientId,
                clientSecret = clientSecret,
                code = refreshToken,
                grantType = REAL_DEBRID_DEVICE_GRANT_TYPE
            )
        }.getOrNull() ?: return false

        val body = response.body()
        if (!response.isSuccessful || body == null) {
            if (response.code() == 401 || response.code() == 403) {
                realDebridAuthDataStore.clearAuth()
            }
            return false
        }

        realDebridAuthDataStore.saveToken(body)
        return true
    }

    suspend fun revokeAndLogout() {
        getCurrentAuthState().accessToken?.let { accessToken ->
            runCatching {
                realDebridAuthIntegrationProvider.disableCurrentAccessToken("Bearer $accessToken")
            }
        }
        realDebridAuthDataStore.clearAuth()
    }

    suspend fun <T> executeAuthOwnerRequest(
        block: suspend (authorization: String) -> Response<T>
    ): Response<T>? {
        if (!refreshTokenIfNeeded(force = false)) {
            return null
        }
        val accessToken = getCurrentAuthState().accessToken ?: return null
        val response = try {
            block("Bearer $accessToken")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            return null
        }
        if (response.code() == 401 && refreshTokenIfNeeded(force = true)) {
            val refreshedToken = getCurrentAuthState().accessToken ?: return null
            return try {
                block("Bearer $refreshedToken")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                null
            }
        }
        return response
    }

    private suspend fun fetchCurrentUsername(): String? {
        val response = executeAuthOwnerRequest { authHeader ->
            realDebridAuthIntegrationProvider.getCurrentUser(authHeader)
                ?: return@executeAuthOwnerRequest Response.error(500, okhttp3.ResponseBody.create(null, "realdebrid_runtime_missing"))
        } ?: return null
        return response.body()?.username
    }

    private fun isTokenExpiredOrExpiring(state: RealDebridAuthState): Boolean {
        val createdAt = state.createdAt ?: return true
        val expiresIn = state.expiresIn ?: return true
        return System.currentTimeMillis() >= createdAt + (expiresIn * 1000L) - refreshLeewayMs
    }
}
