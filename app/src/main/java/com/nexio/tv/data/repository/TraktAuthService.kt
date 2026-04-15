package com.nexio.tv.data.repository

import com.nexio.tv.BuildConfig
import com.nexio.tv.core.logging.sanitizeRequestTargetForLogs
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRoute
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktAuthState
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktDeviceCodeRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktDeviceTokenRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktRefreshTokenRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktRevokeRequestDto
import com.nexio.tv.data.remote.TraktRequestGate
import android.util.Log
import com.nexio.tv.domain.model.TrackingProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TraktTokenPollResult {
    data object Pending : TraktTokenPollResult
    data object AlreadyUsed : TraktTokenPollResult
    data object Expired : TraktTokenPollResult
    data object Denied : TraktTokenPollResult
    data class SlowDown(val pollIntervalSeconds: Int) : TraktTokenPollResult
    data class Approved(val username: String?) : TraktTokenPollResult
    data class Failed(val reason: String) : TraktTokenPollResult
}

@Singleton
class TraktAuthService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val requestGate: TraktRequestGate,
    private val profileManager: ProfileManager,
    private val profileModeRouter: ProfileModeRouter,
    private val profileBoundary: ProfileBoundary
) {
    private val refreshLeewaySeconds = 60L
    private val tokenRefreshMutex = Mutex()
    private val transientRetryStatusCodes = setOf(502, 503, 504, 520, 521, 522)
    private val nonRetryableStatusCodes = setOf(400, 403, 404, 405, 409, 412, 420, 422, 423, 426)

    @Volatile private var circuitOpenUntilMs = 0L
    private val circuitFailures = AtomicInteger(0)
    private val circuitBaseCooldownMs = 5 * 60_000L
    private val circuitMaxCooldownMs = 60 * 60_000L

    private inline fun logDebug(message: () -> String) {
        runCatching { Log.d("TraktAuthService", message()) }
    }

    private inline fun logWarn(message: () -> String) {
        runCatching { Log.w("TraktAuthService", message()) }
    }

    private fun isCircuitOpen(): Boolean {
        return System.currentTimeMillis() < circuitOpenUntilMs
    }

    fun isCircuitClosed(): Boolean = !isCircuitOpen()

    private fun tripCircuit(reason: String) {
        val failures = circuitFailures.incrementAndGet()
        val cooldownMs = (circuitBaseCooldownMs shl (failures - 1).coerceAtMost(4))
            .coerceAtMost(circuitMaxCooldownMs)
        circuitOpenUntilMs = System.currentTimeMillis() + cooldownMs
        logWarn {
            "Circuit breaker OPEN: $reason (failures=$failures, cooldown=${cooldownMs / 1000}s)"
        }
    }

    private fun resetCircuit() {
        if (circuitFailures.get() > 0) {
            trace("Circuit breaker reset after successful request")
        }
        circuitFailures.set(0)
        circuitOpenUntilMs = 0L
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) {
            logDebug { message }
        }
    }

    private fun traktRedirectUri(): String {
        return BuildConfig.TRAKT_REDIRECT_URI.ifBlank { "urn:ietf:wg:oauth:2.0:oob" }
    }

    fun hasRequiredCredentials(): Boolean {
        return BuildConfig.TRAKT_CLIENT_ID.isNotBlank() && BuildConfig.TRAKT_CLIENT_SECRET.isNotBlank()
    }

    suspend fun getCurrentAuthState(): TraktAuthState =
        getCurrentAuthState(currentAuthSession())

    private suspend fun getCurrentAuthState(session: TrackingAuthSession): TraktAuthState =
        traktAuthDataStore.stateForProfile(session.profileId).first()

    suspend fun startDeviceAuth(): Result<TraktDeviceCodeResponseDto> {
        if (!hasRequiredCredentials()) {
            return Result.failure(IllegalStateException("Missing TRAKT credentials"))
        }
        val session = currentAuthSession()

        val response = try {
            traktApi.requestDeviceCode(
                TraktDeviceCodeRequestDto(clientId = BuildConfig.TRAKT_CLIENT_ID)
            )
        } catch (e: IOException) {
            return Result.failure(IllegalStateException("Network error, please try again"))
        }

        val body = response.body()
        if (response.isSuccessful && body != null) {
            traktAuthDataStore.saveDeviceFlow(body, profileId = session.profileId)
            return Result.success(body)
        }

        if (response.code() == 429) {
            val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
            val minutes = ((retryAfter ?: 300L) + 59L) / 60L
            return Result.failure(
                IllegalStateException("Trakt is rate limiting requests. Try again in ~${minutes} min")
            )
        }

        return Result.failure(
            IllegalStateException("Failed to start Trakt auth (${response.code()})")
        )
    }

    suspend fun pollDeviceToken(): TraktTokenPollResult {
        if (!hasRequiredCredentials()) {
            return TraktTokenPollResult.Failed("Missing TRAKT credentials")
        }

        val session = currentAuthSession()
        val state = getCurrentAuthState(session)
        val profileId = session.profileId
        val deviceCode = state.deviceCode
        if (deviceCode.isNullOrBlank()) {
            return TraktTokenPollResult.Failed("No active Trakt device code")
        }

        val response = try {
            traktApi.requestDeviceToken(
                TraktDeviceTokenRequestDto(
                    code = deviceCode,
                    clientId = BuildConfig.TRAKT_CLIENT_ID,
                    clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
                )
            )
        } catch (e: IOException) {
            return TraktTokenPollResult.Failed("Network error, will retry")
        }

        val tokenBody = response.body()
        if (response.isSuccessful && tokenBody != null) {
            traktAuthDataStore.saveToken(tokenBody, profileId = profileId)
            traktAuthDataStore.clearDeviceFlow(profileId)
            val user = fetchUserSettings(session)
            return TraktTokenPollResult.Approved(user)
        }

        return when (response.code()) {
            400 -> TraktTokenPollResult.Pending
            409 -> {
                traktAuthDataStore.clearDeviceFlow(profileId)
                TraktTokenPollResult.AlreadyUsed
            }
            404 -> {
                traktAuthDataStore.clearDeviceFlow(profileId)
                TraktTokenPollResult.Failed("Invalid device code")
            }
            410 -> {
                traktAuthDataStore.clearDeviceFlow(profileId)
                TraktTokenPollResult.Expired
            }
            418 -> {
                traktAuthDataStore.clearDeviceFlow(profileId)
                TraktTokenPollResult.Denied
            }
            429 -> {
                val nextInterval = ((state.pollInterval ?: 5) + 5).coerceAtMost(60)
                traktAuthDataStore.updatePollInterval(nextInterval, profileId = profileId)
                TraktTokenPollResult.SlowDown(nextInterval)
            }
            else -> TraktTokenPollResult.Failed("Token polling failed (${response.code()})")
        }
    }

    suspend fun refreshTokenIfNeeded(force: Boolean = false): Boolean {
        if (!hasRequiredCredentials()) return false

        return refreshTokenIfNeeded(currentAuthSession(), force)
    }

    private suspend fun refreshTokenIfNeeded(
        session: TrackingAuthSession,
        force: Boolean = false
    ): Boolean {
        if (!hasRequiredCredentials()) return false

        return tokenRefreshMutex.withLock {
            val profileId = session.profileId
            val state = getCurrentAuthState(session)
            val refreshToken = state.refreshToken ?: return@withLock false
            if (!force && !isTokenExpiredOrExpiring(state)) {
                trace("refreshTokenIfNeeded: token still valid, skip refresh")
                return@withLock true
            }

            trace("refreshTokenIfNeeded: refreshing token (force=$force)")
            val response = try {
                traktApi.refreshToken(
                    TraktRefreshTokenRequestDto(
                        refreshToken = refreshToken,
                        clientId = BuildConfig.TRAKT_CLIENT_ID,
                        clientSecret = BuildConfig.TRAKT_CLIENT_SECRET,
                        redirectUri = traktRedirectUri()
                    )
                )
            } catch (e: IOException) {
                logWarn { "Network error while refreshing token: ${e.message ?: "unknown"}" }
                return@withLock false
            }

            val tokenBody = response.body()
            if (!response.isSuccessful || tokenBody == null) {
                trace("refreshTokenIfNeeded: failed code=${response.code()}")
                if (response.code() == 400 || response.code() == 401 || response.code() == 403) {
                    logWarn {
                        "Token refresh returned ${response.code()}, clearing auth state"
                    }
                    traktAuthDataStore.clearAuth(profileId)
                    tripCircuit("Token refresh returned ${response.code()}")
                }
                return@withLock false
            }

            traktAuthDataStore.saveToken(tokenBody, profileId = profileId)
            trace("refreshTokenIfNeeded: success")
            true
        }
    }

    suspend fun revokeAndLogout(profileId: Int? = null) {
        val session = profileId?.let { TrackingAuthSession(TrackingProvider.TRAKT, it) } ?: currentAuthSession()
        val state = getCurrentAuthState(session)
        if (hasRequiredCredentials()) {
            state.accessToken?.let { accessToken ->
                runCatching {
                    traktApi.revokeToken(
                        TraktRevokeRequestDto(
                            token = accessToken,
                            clientId = BuildConfig.TRAKT_CLIENT_ID,
                            clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
                        )
                    )
                }
            }
        }
        if (profileId != null) {
            traktAuthDataStore.clearAuth(profileId)
        } else {
            traktAuthDataStore.clearAuth(session.profileId)
        }
    }

    suspend fun fetchUserSettings(): String? {
        return fetchUserSettings(currentAuthSession())
    }

    private suspend fun fetchUserSettings(session: TrackingAuthSession): String? {
        val response = executeAuthorizedRequest(session) { authHeader ->
            traktApi.getUserSettings(authorization = authHeader)
        } ?: return null

        if (!response.isSuccessful) return null

        val username = response.body()?.user?.username
        val slug = response.body()?.user?.ids?.slug
        traktAuthDataStore.saveUser(username = username, userSlug = slug, profileId = session.profileId)
        return username
    }

    suspend fun <T> executeAuthorizedRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? = executeAuthorizedRequest(currentAuthSession(), call)

    suspend fun <T> executeAuthorizedRequest(
        session: TrackingAuthSession,
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? {
        if (isCircuitOpen()) {
            trace("authorized request: circuit breaker is OPEN, skipping request")
            return null
        }

        var token = getValidAccessToken(session) ?: return null
        var retriedAuth = false
        var retriedRateLimit = false
        var retriedTransient = false
        var retriedNetwork = false

        while (true) {
            val response = try {
                requestGate.acquire { call("Bearer $token") }
            } catch (e: IOException) {
                if (!retriedNetwork) {
                    trace("authorized request: network error, retrying once")
                    delay(1_000L)
                    retriedNetwork = true
                    continue
                }
                Log.w("TraktAuthService", "Network error during authorized request", e)
                return null
            }

            val code = response.code()

            if (response.isSuccessful) {
                resetCircuit()
                return response
            }

            if (code == 403) {
                tripCircuit("403 Forbidden (invalid API key or unapproved app) for ${responseTarget(response)}")
                return response
            }

            if (code == 423) {
                tripCircuit("423 Locked User Account for ${responseTarget(response)}")
                return response
            }

            if (code in nonRetryableStatusCodes) {
                trace("authorized request: non-retryable $code for ${responseTarget(response)}")
                return response
            }

            if (code == 401 && !retriedAuth) {
                val refreshed = refreshTokenIfNeeded(session, force = true)
                if (refreshed) {
                    trace("authorized request: 401 for ${responseTarget(response)}, retrying after token refresh")
                    token = getCurrentAuthState(session).accessToken ?: return response
                    retriedAuth = true
                    continue
                }
                tripCircuit("401 Unauthorized and token refresh failed for ${responseTarget(response)}")
                return response
            }

            if (code == 429 && !retriedRateLimit) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 2L, maxSeconds = 60L)
                trace("authorized request: 429 for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedRateLimit = true
                continue
            }

            if (code in transientRetryStatusCodes && !retriedTransient) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 30L, maxSeconds = 30L)
                trace("authorized request: transient $code for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedTransient = true
                continue
            }

            return response
        }
    }

    suspend fun <T> executeAuthorizedWriteRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? = executeAuthorizedRequest(call)

    suspend fun <T> executeAuthorizedWriteRequest(
        session: TrackingAuthSession,
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? = executeAuthorizedRequest(session, call)

    private suspend fun getValidAccessToken(session: TrackingAuthSession): String? {
        val state = getCurrentAuthState(session)
        if (state.accessToken.isNullOrBlank()) return null
        if (refreshTokenIfNeeded(session, force = false)) {
            return getCurrentAuthState(session).accessToken
        }
        return null
    }

    fun currentAuthSession(): TrackingAuthSession {
        return TrackingAuthSession(
            provider = TrackingProvider.TRAKT,
            profileId = currentRoutedProfileId()
        )
    }

    private fun currentRoutedProfileId(): Int {
        return when (val route = profileModeRouter.routeFor(profileManager.activeProfileId.value)) {
            ProfileModeRoute.DefaultLegacyRoute -> profileModeRouter.defaultLegacyProfileId()
            is ProfileModeRoute.SecondaryProfileRoute -> {
                profileBoundary.authRoute(route, TrackingProvider.TRAKT).profileId
            }
            is ProfileModeRoute.InvalidProfileRoute -> error("Invalid active profile id ${route.profileId}")
        }
    }

    fun currentTraktProfileId(): Int = currentRoutedProfileId()

    private fun isTokenExpiredOrExpiring(state: TraktAuthState): Boolean {
        val createdAt = state.createdAt ?: return true
        val expiresIn = state.expiresIn ?: return true
        val expiresAt = createdAt + expiresIn
        val nowSeconds = System.currentTimeMillis() / 1000L
        return nowSeconds >= (expiresAt - refreshLeewaySeconds)
    }

    private suspend fun delayForRetryAfter(
        response: Response<*>,
        fallbackSeconds: Long,
        maxSeconds: Long
    ): Long {
        val retryAfterSeconds = response.headers()["Retry-After"]
            ?.toLongOrNull()
            ?.coerceIn(1L, maxSeconds)
            ?: fallbackSeconds
        delay(retryAfterSeconds * 1000L)
        return retryAfterSeconds
    }

    private fun responseTarget(response: Response<*>): String {
        val requestUrl = response.raw().request.url
        return sanitizeRequestTargetForLogs(
            encodedPath = requestUrl.encodedPath,
            encodedQuery = requestUrl.encodedQuery
        )
    }
}
