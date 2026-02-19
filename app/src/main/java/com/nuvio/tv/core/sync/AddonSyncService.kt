package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.supabase.SupabaseAddon
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AddonSyncService"

@Singleton
class AddonSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val addonPreferences: AddonPreferences
) {
    /**
     * Push local addon URLs to Supabase via RPC.
     * Uses a SECURITY DEFINER function to handle RLS for linked devices.
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val localUrls = addonPreferences.installedAddonUrls.first()

            val params = buildJsonObject {
                put("p_addons", buildJsonArray {
                    localUrls.forEachIndexed { index, url ->
                        addJsonObject {
                            put("url", url)
                            put("sort_order", index)
                        }
                    }
                })
            }
            postgrest.rpc("sync_push_addons", params)

            Log.d(TAG, "Pushed ${localUrls.size} addons to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push addons to remote", e)
            Result.failure(e)
        }
    }

    suspend fun getRemoteAddonUrls(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId()
                ?: return@withContext Result.success(emptyList())

            val remoteAddons = postgrest.from("addons")
                .select { filter { eq("user_id", effectiveUserId) } }
                .decodeList<SupabaseAddon>()

            Result.success(
                remoteAddons
                .sortedBy { it.sortOrder }
                .map { it.url }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote addon URLs", e)
            Result.failure(e)
        }
    }
}
