package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.data.remote.supabase.SupabasePlugin
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

private const val TAG = "PluginSyncService"

@Singleton
class PluginSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val pluginDataStore: PluginDataStore
) {
    /**
     * Push local plugin repository URLs to Supabase via RPC.
     * Uses a SECURITY DEFINER function to handle RLS for linked devices.
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val localRepos = pluginDataStore.repositories.first()

            val params = buildJsonObject {
                put("p_plugins", buildJsonArray {
                    localRepos.forEachIndexed { index, repo ->
                        addJsonObject {
                            put("url", repo.url)
                            put("name", repo.name)
                            put("enabled", repo.enabled)
                            put("sort_order", index)
                        }
                    }
                })
            }
            postgrest.rpc("sync_push_plugins", params)

            Log.d(TAG, "Pushed ${localRepos.size} plugin repos to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push plugins to remote", e)
            Result.failure(e)
        }
    }

    suspend fun getRemoteRepoUrls(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId()
                ?: return@withContext Result.success(emptyList())

            val remotePlugins = postgrest.from("plugins")
                .select { filter { eq("user_id", effectiveUserId) } }
                .decodeList<SupabasePlugin>()

            Result.success(
                remotePlugins
                .sortedBy { it.sortOrder }
                .map { it.url }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote repo URLs", e)
            Result.failure(e)
        }
    }
}
