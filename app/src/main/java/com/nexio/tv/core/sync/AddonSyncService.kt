package com.nexio.tv.core.sync

import android.util.Log
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.remote.supabase.AccountSnapshotRpcResponse
import com.nexio.tv.data.remote.supabase.AccountSyncMutationResult
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AddonSyncService"

@Singleton
class AddonSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val addonPreferences: AddonPreferences
) {
    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    /**
     * Push local addon URLs to Supabase via RPC.
     * Uses a SECURITY DEFINER function to handle RLS for linked devices.
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val localUrls = addonPreferences.installedAddonUrls.first()
            Log.d(TAG, "pushToRemote: localUrls count=${localUrls.size}")
            val parsedAddons = localUrls.map(::parseAddonInstallUrl)

            parsedAddons.forEach { parsed ->
                val secretPayload = parsed.secretPayload
                val secretRef = parsed.secretRef
                if (secretPayload != null && !secretRef.isNullOrBlank()) {
                    withJwtRefreshRetry {
                        postgrest.rpc(
                            "sync_set_account_secret",
                            buildJsonObject {
                                put("p_secret_type", "addon_credential")
                                put("p_secret_ref", secretRef)
                                put(
                                    "p_secret_payload",
                                    Json.encodeToJsonElement(
                                        com.nexio.tv.data.remote.supabase.AccountAddonSecretPayload.serializer(),
                                        secretPayload
                                    )
                                )
                                put(
                                    "p_masked_preview",
                                    "Configured ••••${
                                        (secretPayload.params.values.firstOrNull()?.takeLast(4)
                                            ?: secretPayload.pathSegment?.takeLast(4).orEmpty())
                                    }"
                                )
                                put("p_status", "configured")
                                put("p_source", "app")
                            }
                        )
                    }
                }
            }

            val params = buildJsonObject {
                put("p_addons", buildJsonArray {
                    parsedAddons.forEachIndexed { index, addon ->
                        addJsonObject {
                            put("url", addon.publicBaseUrl)
                            put("manifest_url", addon.manifestUrl)
                            put("public_query_params", Json.encodeToJsonElement(MapSerializer(String.serializer(), String.serializer()), addon.publicQueryParams))
                            put("install_kind", addon.installKind)
                            addon.secretRef?.let { put("secret_ref", it) }
                            put("sort_order", index)
                        }
                    }
                })
                put("p_source", "app")
            }
            Log.d(TAG, "pushToRemote: calling RPC sync_push_account_addons")
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_account_addons", params).decodeList<AccountSyncMutationResult>()
            }

            Log.d(TAG, "Pushed ${localUrls.size} addons to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push addons to remote", e)
            Result.failure(e)
        }
    }

    suspend fun getRemoteAddonUrls(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val snapshot = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_account_snapshot").decodeAs<AccountSnapshotRpcResponse>()
            }

            Result.success(
                snapshot.addons
                    .sortedBy { it.sortOrder }
                    .map { it.url }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote addon URLs", e)
            Result.failure(e)
        }
    }
}
