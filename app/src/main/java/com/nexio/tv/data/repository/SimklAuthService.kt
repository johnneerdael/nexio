package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.credentialHash as integrationCredentialHash
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRoute
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.integration.simkl.SimklAuthIntegrationProvider
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.SimklAuthState
import com.nexio.tv.data.remote.SimklRequestGate
import com.nexio.tv.domain.model.TrackingProvider
import kotlinx.coroutines.flow.first
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F2-C-06 carve-out: exempt from the [IntegrationBoundaryTest] Retrofit-usage check.
 *
 * This service drives the SIMKL PIN-code grant flow through [SimklAuthIntegrationProvider], which
 * wraps raw Retrofit calls that predate the [IntegrationRuntime]. The multi-step flow (PIN-code
 * request → PIN-status poll → token save) runs before any runtime context is established, making
 * it impractical to route through the runtime at present.
 *
 * Migration plan: when the runtime gains support for pre-auth or session-less contexts (no current
 * ETA), route these flows through it and remove this file from the boundary allowlist.
 *
 * Tracking: review-dossier-2/09-known-gaps.md F2-C-06.
 */
sealed interface SimklTokenPollResult {
    data object Pending : SimklTokenPollResult
    data class SlowDown(val pollIntervalSeconds: Int) : SimklTokenPollResult
    data class Approved(val username: String?) : SimklTokenPollResult
    data class Failed(val reason: String) : SimklTokenPollResult
}

@Singleton
class SimklAuthService @Inject constructor(
    private val simklAuthIntegrationProvider: SimklAuthIntegrationProvider,
    private val simklAuthDataStore: SimklAuthDataStore,
    private val requestGate: SimklRequestGate,
    private val profileManager: ProfileManager,
    private val profileModeRouter: ProfileModeRouter,
    private val profileBoundary: ProfileBoundary
) {
    fun hasRequiredCredentials(): Boolean = BuildConfig.SIMKL_CLIENT_ID.isNotBlank()

    suspend fun getCurrentAuthState(): SimklAuthState =
        getCurrentAuthState(currentAuthSession())

    private suspend fun getCurrentAuthState(session: TrackingAuthSession): SimklAuthState =
        simklAuthDataStore.stateForProfile(session.profileId).first()

    suspend fun accountScopedSession(session: TrackingAuthSession = currentAuthSession()): TrackingAuthSession {
        if (!session.credentialHash.isNullOrBlank()) return session
        val state = getCurrentAuthState(session)
        val accountMaterial = state.accountId?.toString()
            ?: state.username?.takeIf { it.isNotBlank() }
        val credentialMaterial = state.accessToken?.takeIf { it.isNotBlank() }
            ?: accountMaterial
            ?: "profile:${session.profileId}:unauthenticated"
        return session.copy(
            credentialHash = integrationCredentialHash(IntegrationProvider.SIMKL, credentialMaterial),
            accountIdHash = accountMaterial?.let { integrationCredentialHash(IntegrationProvider.SIMKL, it) }
        )
    }

    suspend fun startPinAuth(): Result<Unit> {
        if (!hasRequiredCredentials()) {
            return Result.failure(IllegalStateException("Missing SIMKL_CLIENT_ID"))
        }
        val session = currentAuthSession()
        val response = try {
            simklAuthIntegrationProvider.requestPinCode()
        } catch (e: IOException) {
            return Result.failure(IllegalStateException("Network error, please try again"))
        } ?: return Result.failure(IllegalStateException("Network error, please try again"))
        val body = response.body()
        if (response.isSuccessful && body?.deviceCode != null && body.userCode != null) {
            simklAuthDataStore.saveDeviceFlow(body, profileId = session.profileId)
            return Result.success(Unit)
        }
        return Result.failure(IllegalStateException("Failed to start SIMKL auth (${response.code()})"))
    }

    suspend fun pollPin(): SimklTokenPollResult {
        if (!hasRequiredCredentials()) return SimklTokenPollResult.Failed("Missing SIMKL_CLIENT_ID")
        val session = currentAuthSession()
        val state = getCurrentAuthState(session)
        val profileId = session.profileId
        val userCode = state.userCode
        if (userCode.isNullOrBlank()) return SimklTokenPollResult.Failed("No active SIMKL PIN code")

        val response = try {
            simklAuthIntegrationProvider.getPinStatus(userCode)
        } catch (e: IOException) {
            return SimklTokenPollResult.Failed("Network error, will retry")
        } ?: return SimklTokenPollResult.Failed("Network error, will retry")
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            return SimklTokenPollResult.Failed("PIN polling failed (${response.code()})")
        }
        if (body.result.equals("OK", ignoreCase = true) && !body.accessToken.isNullOrBlank()) {
            simklAuthDataStore.saveAccessToken(body.accessToken, profileId = profileId, clearAccountIdentity = true)
            simklAuthDataStore.clearDeviceFlow(profileId)
            val username = fetchUserSettings(session)
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
        return fetchUserSettings(currentAuthSession())
    }

    private suspend fun fetchUserSettings(session: TrackingAuthSession): String? {
        val ownerSession = accountScopedSession(session)
        val response = executeAuthOwnerRequest(session) { authHeader ->
            simklAuthIntegrationProvider.getUserSettings(
                session = ownerSession,
                authorization = authHeader
            )
                ?: return@executeAuthOwnerRequest Response.error(500, okhttp3.ResponseBody.create(null, "simkl_runtime_missing"))
        } ?: return null
        if (!response.isSuccessful) return null
        val body = response.body()
        val username = body?.user?.name
        simklAuthDataStore.saveUser(
            username = username,
            accountId = body?.account?.id,
            accountType = body?.account?.type,
            profileId = session.profileId
        )
        return username
    }

    suspend fun revokeAndLogout() {
        simklAuthDataStore.clearAuth(currentAuthSession().profileId)
    }

    suspend fun <T> executeAuthOwnerRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? = executeAuthOwnerRequest(currentAuthSession(), call)

    suspend fun <T> executeAuthOwnerRequest(
        session: TrackingAuthSession,
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? {
        val token = getCurrentAuthState(session).accessToken ?: return null
        return try {
            requestGate.acquire { call("Bearer $token") }
        } catch (e: IOException) {
            Log.w("SimklAuthService", "Network error during authorized request", e)
            null
        }
    }

    suspend fun <T> executeAuthorizedWriteRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? = executeAuthOwnerRequest(call)

    suspend fun <T> executeAuthorizedWriteRequest(
        session: TrackingAuthSession,
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? = executeAuthOwnerRequest(session, call)

    fun currentAuthSession(): TrackingAuthSession {
        return TrackingAuthSession(
            provider = TrackingProvider.SIMKL,
            profileId = currentRoutedProfileId()
        )
    }

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
