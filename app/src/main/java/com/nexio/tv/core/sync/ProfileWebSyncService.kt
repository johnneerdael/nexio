package com.nexio.tv.core.sync

import android.util.Log
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.auth.hasLiveFullAccountSyncSession
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileModeRoute
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nexio.tv.domain.model.TrackingProvider
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val PROFILE_WEB_SYNC_TAG = "ProfileWebSyncService"

@Serializable
data class ProfileAuthToken(
    @SerialName("profile_index") val profileIndex: Int,
    @SerialName("token_type") val tokenType: String,
    @SerialName("token_payload") val tokenPayload: JsonObject = buildJsonObject {},
    @SerialName("masked_preview") val maskedPreview: String = "",
    val source: String = "",
    val linked: Boolean = true,
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String = ""
)

@Singleton
class ProfileWebSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val simklAuthDataStore: SimklAuthDataStore,
    private val profileModeRouter: ProfileModeRouter,
    private val profileBoundary: ProfileBoundary
) {
    suspend fun syncActiveProfile(profileIndex: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (
                !hasLiveFullAccountSyncSession(
                    authState = authManager.authState.value,
                    sessionUserId = authManager.currentSessionUserId
                )
            ) {
                return@withContext Result.failure(
                    IllegalStateException("Profile web auth sync requires a full account session")
                )
            }
            val route = profileModeRouter.routeFor(profileIndex)
            if (route is ProfileModeRoute.DefaultLegacyRoute) {
                Log.d(
                    PROFILE_WEB_SYNC_TAG,
                    "Skipping profile web auth token sync for primary profile; account sync owns legacy integrations"
                )
                return@withContext Result.success(Unit)
            }
            if (route is ProfileModeRoute.InvalidProfileRoute) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid profile id ${route.profileId}")
                )
            }
            val secondaryRoute = route as ProfileModeRoute.SecondaryProfileRoute

            val remoteTokens = postgrest.from("profile_auth_tokens")
                .select {
                    filter {
                        eq("profile_index", profileIndex)
                    }
                }
                .decodeList<ProfileAuthToken>()

            applyTraktTokens(
                profileBoundary.authRoute(secondaryRoute, TrackingProvider.TRAKT).profileId,
                remoteTokens
            )
            applySimklTokens(
                profileBoundary.authRoute(secondaryRoute, TrackingProvider.SIMKL).profileId,
                remoteTokens
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(PROFILE_WEB_SYNC_TAG, "Failed to sync web auth tokens for profile $profileIndex", e)
            Result.failure(e)
        }
    }

    private suspend fun applyTraktTokens(profileIndex: Int, remoteTokens: List<ProfileAuthToken>) {
        val traktTokens = remoteTokens.filter { it.tokenType.startsWith("trakt_") }
        val localState = traktAuthDataStore.stateForProfile(profileIndex).first()
        val localLinkedAt = normalizeEpochMillis(localState.createdAt)
        if (traktTokens.isEmpty()) {
            if (localState.isAuthenticated) {
                Log.d(PROFILE_WEB_SYNC_TAG, "Clearing stale local Trakt auth for profile $profileIndex; remote has no Trakt tokens")
                traktAuthDataStore.clearAuth(profileIndex)
            }
            return
        }
        if (hasNewerRemoteUnlink(traktTokens, localLinkedAt)) {
            traktAuthDataStore.clearAuth(profileIndex)
            return
        }

        val accessRow = newestLinkedToken(traktTokens, "trakt_access_token")
        val refreshRow = newestLinkedToken(traktTokens, "trakt_refresh_token")
        if (accessRow == null || refreshRow == null) {
            if (localState.isAuthenticated && traktTokens.none { it.linked && it.revokedAt == null }) {
                Log.d(PROFILE_WEB_SYNC_TAG, "Clearing stale local Trakt auth for profile $profileIndex; remote has no linked Trakt pair")
                traktAuthDataStore.clearAuth(profileIndex)
            }
            return
        }
        val remoteUpdatedAt = maxUpdatedAt(accessRow, refreshRow)
        if (!remoteIsNewer(remoteUpdatedAt, localLinkedAt)) return

        val accessToken = accessRow.tokenPayload.stringValue(
            "accessToken",
            "access_token",
            "trakt_access_token"
        ) ?: return
        val refreshToken = refreshRow.tokenPayload.stringValue(
            "refreshToken",
            "refresh_token",
            "trakt_refresh_token"
        ) ?: return

        traktAuthDataStore.saveToken(
            TraktTokenResponseDto(
                accessToken = accessToken,
                tokenType = accessRow.tokenPayload.stringValue("tokenType", "token_type") ?: "Bearer",
                expiresIn = accessRow.tokenPayload.intValue("expiresIn", "expires_in") ?: 0,
                refreshToken = refreshToken,
                createdAt = accessRow.tokenPayload.longValue("createdAt", "created_at")
                    ?: remoteUpdatedAt?.div(1000)
                    ?: System.currentTimeMillis().div(1000)
            ),
            profileId = profileIndex
        )

        val username = accessRow.tokenPayload.stringValue("username")
        val userSlug = accessRow.tokenPayload.stringValue("userSlug", "user_slug")
        if (username != null || userSlug != null) {
            traktAuthDataStore.saveUser(username, userSlug, profileId = profileIndex)
        }
        traktAuthDataStore.clearDeviceFlow(profileIndex)
    }

    private suspend fun applySimklTokens(profileIndex: Int, remoteTokens: List<ProfileAuthToken>) {
        val simklTokens = remoteTokens.filter { it.tokenType == "simkl_access_token" }
        val localState = simklAuthDataStore.stateForProfile(profileIndex).first()
        if (simklTokens.isEmpty()) {
            if (localState.isAuthenticated) {
                Log.d(PROFILE_WEB_SYNC_TAG, "Clearing stale local SIMKL auth for profile $profileIndex; remote has no SIMKL tokens")
                simklAuthDataStore.clearAuth(profileIndex)
            }
            return
        }
        if (hasNewerRemoteUnlink(simklTokens, localLinkedAt = null)) {
            simklAuthDataStore.clearAuth(profileIndex)
            return
        }

        val accessRow = newestLinkedToken(simklTokens, "simkl_access_token") ?: run {
            if (localState.isAuthenticated && simklTokens.none { it.linked && it.revokedAt == null }) {
                Log.d(PROFILE_WEB_SYNC_TAG, "Clearing stale local SIMKL auth for profile $profileIndex; remote has no linked SIMKL token")
                simklAuthDataStore.clearAuth(profileIndex)
            }
            return
        }
        val accessToken = accessRow.tokenPayload.stringValue(
            "accessToken",
            "access_token",
            "simkl_access_token"
        ) ?: return
        if (localState.accessToken == accessToken) return

        simklAuthDataStore.saveAccessToken(accessToken, profileId = profileIndex)
        val username = accessRow.tokenPayload.stringValue("username")
        val accountId = accessRow.tokenPayload.longValue("accountId", "account_id")
        val accountType = accessRow.tokenPayload.stringValue("accountType", "account_type")
        if (username != null || accountId != null || accountType != null) {
            simklAuthDataStore.saveUser(username, accountId, accountType, profileId = profileIndex)
        }
        simklAuthDataStore.clearDeviceFlow(profileIndex)
    }

    private fun newestLinkedToken(tokens: List<ProfileAuthToken>, tokenType: String): ProfileAuthToken? {
        return tokens
            .filter { it.tokenType == tokenType && it.linked && it.revokedAt == null }
            .maxByOrNull { it.updatedAtMillis() ?: Long.MIN_VALUE }
    }

    private fun hasNewerRemoteUnlink(tokens: List<ProfileAuthToken>, localLinkedAt: Long?): Boolean {
        return tokens.any { token ->
            (!token.linked || token.revokedAt != null) && remoteIsNewer(token.updatedAtMillis(), localLinkedAt)
        }
    }

    private fun maxUpdatedAt(vararg tokens: ProfileAuthToken): Long? {
        return tokens.mapNotNull { it.updatedAtMillis() }.maxOrNull()
    }

    private fun remoteIsNewer(remoteUpdatedAt: Long?, localLinkedAt: Long?): Boolean {
        return localLinkedAt == null || remoteUpdatedAt == null || remoteUpdatedAt > localLinkedAt
    }

    private fun ProfileAuthToken.updatedAtMillis(): Long? {
        return parseTimestampMillis(updatedAt)
    }

    private fun parseTimestampMillis(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()
            ?: normalizeEpochMillis(trimmed.toLongOrNull())
    }

    private fun normalizeEpochMillis(value: Long?): Long? {
        if (value == null || value <= 0L) return null
        return if (value >= 1_000_000_000_000L) value else value * 1000L
    }

    private fun JsonObject.stringValue(vararg keys: String): String? {
        return firstPayloadValue(*keys)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.longValue(vararg keys: String): Long? {
        return firstPayloadValue(*keys)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull()
    }

    private fun JsonObject.intValue(vararg keys: String): Int? {
        return firstPayloadValue(*keys)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toIntOrNull()
    }

    private fun JsonObject.firstPayloadValue(vararg keys: String): JsonElement? {
        return keys.firstNotNullOfOrNull { key -> this[key] }
    }
}
