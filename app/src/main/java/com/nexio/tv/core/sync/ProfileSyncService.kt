package com.nexio.tv.core.sync

import android.util.Log
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.ProfileDataStore
import com.nexio.tv.data.remote.supabase.SupabaseProfile
import com.nexio.tv.domain.model.UserProfile
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileSyncService"

@Singleton
class ProfileSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileDataStore: ProfileDataStore,
    private val profileManager: ProfileManager
) {
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!authManager.hasSyncSession) {
                return@withContext Result.success(Unit)
            }

            val profiles = profileManager.profiles.value
            val params = buildJsonObject {
                put(
                    "p_profiles",
                    buildJsonArray {
                        profiles.forEach { profile ->
                            addJsonObject {
                                put("profile_index", profile.id)
                                put("name", profile.name)
                                put("avatar_color_hex", profile.avatarColorHex)
                                put("uses_primary_addons", profile.usesPrimaryAddons)
                                profile.avatarId?.let { put("avatar_id", it) } ?: put("avatar_id", JsonNull)
                                put("pin_enabled", profile.pinEnabled)
                            }
                        }
                    }
                )
            }

            withJwtRefreshRetry {
                postgrest.rpc("sync_push_profiles", params)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push profiles to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        try {
            if (!authManager.hasSyncSession) {
                return@withContext Result.success(emptyList())
            }

            val remoteProfiles = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_profiles").decodeList<SupabaseProfile>()
            }
            val profiles = remoteProfiles
                .filter { it.profileIndex in 1..4 }
                .distinctBy { it.profileIndex }
                .map { profile ->
                    UserProfile(
                        id = profile.profileIndex,
                        name = profile.name,
                        avatarColorHex = profile.avatarColorHex,
                        usesPrimaryAddons = profile.usesPrimaryAddons,
                        avatarId = profile.avatarId,
                        pinEnabled = profile.pinEnabled
                    )
                }

            if (profiles.isNotEmpty()) {
                profileDataStore.replaceAllProfiles(profiles)
            }

            Result.success(profiles)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull profiles from remote", e)
            Result.failure(e)
        }
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }
}
