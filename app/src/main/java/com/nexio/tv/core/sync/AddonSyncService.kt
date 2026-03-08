package com.nexio.tv.core.sync

import android.util.Log
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.domain.model.AddonParserPreset
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
            val localAddons = addonPreferences.installedAddons.first()
            val parsedAddons = localAddons.mapNotNull { addon ->
                runCatching { parseAddonInstallUrl(addon.url) to addon.parserPreset }
                    .onFailure { error ->
                        Log.w(TAG, "pushToRemote: dropping malformed local addon URL=${addon.url}", error)
                    }
                    .getOrNull()
            }
            Log.d(TAG, "pushToRemote: localAddons count=${localAddons.size} valid=${parsedAddons.size}")

            parsedAddons.forEach { (parsed, _) ->
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
                        val (parsedAddon, parserPreset) = addon
                        addJsonObject {
                            put("url", parsedAddon.publicBaseUrl)
                            put("manifest_url", parsedAddon.manifestUrl)
                            put("parser_preset", parserPreset.name)
                            put("public_query_params", Json.encodeToJsonElement(MapSerializer(String.serializer(), String.serializer()), parsedAddon.publicQueryParams))
                            put("install_kind", parsedAddon.installKind)
                            parsedAddon.secretRef?.let { put("secret_ref", it) }
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

            Log.d(TAG, "Pushed ${localAddons.size} addons to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push addons to remote", e)
            Result.failure(e)
        }
    }

    suspend fun getRemoteAddonConfigs(): Result<List<AddonPreferences.AddonInstallConfig>> = withContext(Dispatchers.IO) {
        try {
            val snapshot = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_account_snapshot").decodeAs<AccountSnapshotRpcResponse>()
            }

            Result.success(
                snapshot.addons
                    .sortedBy { it.sortOrder }
                    .map { addon ->
                        AddonPreferences.AddonInstallConfig(
                            url = addon.url,
                            parserPreset = runCatching {
                                enumValueOf<AddonParserPreset>(addon.parserPreset.trim().uppercase())
                            }.getOrDefault(AddonParserPreset.GENERIC)
                        )
                    }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote addon configs", e)
            Result.failure(e)
        }
    }
}
