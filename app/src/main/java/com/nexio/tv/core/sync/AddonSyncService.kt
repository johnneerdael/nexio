package com.nexio.tv.core.sync

import android.util.Log
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.auth.hasLiveFullAccountSyncSession
import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.local.SyncWatermarkDataStore
import com.nexio.tv.data.remote.supabase.V10AccountSnapshotEnvelope
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.data.remote.supabase.AccountSnapshotRpcResponse
import com.nexio.tv.data.remote.supabase.AccountAddonPayload
import com.nexio.tv.data.remote.supabase.AccountAddonSecretPayload
import com.nexio.tv.data.remote.supabase.AccountSyncMutationResult
import com.nexio.tv.data.remote.supabase.V10PushResult
import com.nexio.tv.data.remote.supabase.requireValidV1Secret
import com.nexio.tv.data.remote.supabase.requireValidV2Transport
import dagger.Lazy
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val addonPreferences: AddonPreferences,
    private val startupPushGate: AccountConfigStartupPushGate,
    private val syncWatermarkStore: SyncWatermarkDataStore,
    // dagger.Lazy breaks the StartupSyncService → AddonRepositoryImpl → AddonSyncService cycle.
    // Used to trigger a fresh pull when the server reports our base watermark is stale, so
    // local state is discarded and overwritten by remote per the v10 contract.
    private val startupSyncServiceLazy: Lazy<StartupSyncService>
) {
    /** See `AccountSettingsSyncService.setAccountSecretV10` — same contract. */
    private suspend fun setAccountSecretV10(extraParams: JsonObject) {
        val baseMs = syncWatermarkStore.get(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null)
        val params = JsonObject(extraParams + ("p_base_updated_at_ms" to JsonPrimitive(baseMs)))
        val outcome = runV10Push {
            withJwtRefreshRetry {
                postgrest.rpc("sync_set_account_secret_v10", params).decodeAs<V10PushResult>()
            }
        }
        when (outcome) {
            is V10PushOutcome.Applied ->
                syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null, ms = outcome.currentUpdatedAtMs)
            is V10PushOutcome.StaleBase -> {
                Log.w(TAG, "setAccountSecretV10 stale (server=${outcome.currentUpdatedAtMs}, base=$baseMs); requesting immediate refresh — local secret will be overwritten by remote")
                startupSyncServiceLazy.get().requestSyncNow()
            }
            is V10PushOutcome.Failed -> throw outcome.cause
            is V10PushOutcome.FieldConflict -> Unit
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

    /**
     * Push local addon URLs to Supabase via RPC.
     * Uses a SECURITY DEFINER function to handle RLS for linked devices.
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!hasLiveFullAccountSession()) {
                return@withContext Result.success(Unit)
            }
            val userId = authManager.currentSessionUserId
            if (!startupPushGate.canPush(userId)) {
                Log.d(TAG, "Skipping addon push before startup remote pull completes")
                return@withContext Result.success(Unit)
            }
            val localAddons = addonPreferences.installedAddons.first()
            val parsedAddons = localAddons.mapNotNull { addon ->
                runCatching { Triple(parseStoredAddonInstallUrl(addon.url), addon.parserPreset, addon.isAnime) }
                    .onFailure { error ->
                        Log.w(TAG, "pushToRemote: dropping malformed local addon URL=${addon.url}", error)
                    }
                    .getOrNull()
            }
            Log.d(TAG, "pushToRemote: localAddons count=${localAddons.size} valid=${parsedAddons.size}")

            parsedAddons.forEach { (parsed, _, _) ->
                val secretPayload = parsed.secretPayload
                val secretRef = parsed.secretRef
                if (secretPayload != null && !secretRef.isNullOrBlank()) {
                    setAccountSecretV10(buildJsonObject {
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
                            })
                }
                setAccountSecretV10(buildJsonObject {
                            put("p_secret_type", "addon_credential")
                            put("p_secret_ref", parsed.transportSecretRef)
                            put(
                                "p_secret_payload",
                                Json.encodeToJsonElement(
                                    com.nexio.tv.data.remote.supabase.AccountAddonSecretPayload.serializer(),
                                    parsed.transportSecretPayload
                                )
                            )
                            put(
                                "p_masked_preview",
                                "Transport ••••${parsed.transportSecretPayload.suffix.orEmpty().takeLast(4)}"
                            )
                            put("p_status", "configured")
                            put("p_source", "app")
                        })
            }

            val baseUpdatedAtMs = syncWatermarkStore.get(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null)
            val params = buildJsonObject {
                put("p_base_updated_at_ms", baseUpdatedAtMs)
                put("p_addons", buildJsonArray {
                    parsedAddons.forEachIndexed { index, addon ->
                        val (parsedAddon, parserPreset, isAnime) = addon
                        addJsonObject {
                            put("url", parsedAddon.publicBaseUrl)
                            put("manifest_url", parsedAddon.manifestUrl)
                            put("parser_preset", parserPreset.name)
                            put("is_anime", isAnime)
                            put("public_query_params", Json.encodeToJsonElement(MapSerializer(String.serializer(), String.serializer()), parsedAddon.publicQueryParams))
                            put("install_kind", parsedAddon.installKind)
                            parsedAddon.secretRef?.let { put("secret_ref", it) }
                            put("transport_schema_version", 2)
                            put("transport_base_url", parsedAddon.transportBaseUrl)
                            put("transport_secret_ref", parsedAddon.transportSecretRef)
                            put("sort_order", index)
                        }
                    }
                })
                put("p_source", "app")
            }
            Log.d(TAG, "pushToRemote: calling RPC sync_push_account_addons_v10 with base=$baseUpdatedAtMs")
            val outcome = runV10Push {
                withJwtRefreshRetry {
                    postgrest.rpc("sync_push_account_addons_v10", params).decodeAs<V10PushResult>()
                }
            }
            when (outcome) {
                is V10PushOutcome.Applied -> {
                    syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null, ms = outcome.currentUpdatedAtMs)
                    Log.d(TAG, "Pushed ${localAddons.size} addons to remote, new watermark=${outcome.currentUpdatedAtMs}")
                }
                is V10PushOutcome.StaleBase -> {
                    Log.w(TAG, "Addon push rejected as stale (server=${outcome.currentUpdatedAtMs}, base=$baseUpdatedAtMs); requesting immediate refresh — local addon changes will be overwritten by remote")
                    startupSyncServiceLazy.get().requestSyncNow()
                    return@withContext Result.success(Unit)
                }
                is V10PushOutcome.FieldConflict ->
                    Log.w(TAG, "Unexpected field_conflict on addon push: ${outcome.conflictPaths}")
                is V10PushOutcome.Failed -> throw outcome.cause
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push addons to remote", e)
            Result.failure(e)
        }
    }

    suspend fun getRemoteAddonConfigs(): Result<List<AddonPreferences.AddonInstallConfig>> = withContext(Dispatchers.IO) {
        try {
            val envelope = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_account_snapshot_v10")
                    .decodeAs<V10AccountSnapshotEnvelope>()
            }
            syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null, ms = envelope.addons.updatedAtMs)
            syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null, ms = envelope.secrets.updatedAtMs)
            syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_SETTINGS, profileId = null, ms = envelope.settings.updatedAtMs)

            Result.success(
                envelope.addons.items
                    .sortedBy { it.sortOrder }
                    .mapNotNull { addon ->
                        val resolvedUrl = resolveRemoteAddonUrl(addon)
                            .onFailure { error ->
                                Log.w(TAG, "getRemoteAddonConfigs: failed to resolve addon url=${addon.url}", error)
                            }
                            .getOrNull()
                            ?: return@mapNotNull null
                        AddonPreferences.AddonInstallConfig(
                            url = resolvedUrl,
                            parserPreset = runCatching {
                                enumValueOf<AddonParserPreset>(addon.parserPreset.trim().uppercase())
                            }.getOrDefault(AddonParserPreset.GENERIC),
                            isAnime = addon.isAnime
                        )
                    }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote addon configs", e)
            Result.failure(e)
        }
    }

    private fun hasLiveFullAccountSession(): Boolean {
        return hasLiveFullAccountSyncSession(
            authState = authManager.authState.value,
            sessionUserId = authManager.currentSessionUserId
        )
    }

    private suspend fun resolveRemoteAddonUrl(addon: AccountAddonPayload): Result<String> {
        return runCatching {
            if (addon.transportSchemaVersion == 2 && !addon.transportSecretRef.isNullOrBlank()) {
                val transportPayload = withJwtRefreshRetry {
                    postgrest.rpc(
                        "sync_resolve_account_secret",
                        buildJsonObject {
                            put("p_secret_type", "addon_credential")
                            put("p_secret_ref", addon.transportSecretRef)
                            put("p_source", "app")
                        }
                    ).decodeAs<AccountAddonSecretPayload>()
                }.requireValidV2Transport(
                    secretRef = addon.transportSecretRef,
                    addonUrl = addon.url
                )
                return@runCatching buildResolvedAddonUrl(
                    baseUrl = addon.transportBaseUrl ?: addon.url,
                    manifestUrl = null,
                    publicQueryParams = emptyMap(),
                    secretPayload = transportPayload
                ).let(::normalizeAddonInstallUrl)
            }

            val secretPayload = addon.secretRef
                ?.takeIf { it.isNotBlank() }
                ?.let { secretRef ->
                    withJwtRefreshRetry {
                        postgrest.rpc(
                            "sync_resolve_account_secret",
                            buildJsonObject {
                                put("p_secret_type", "addon_credential")
                                put("p_secret_ref", secretRef)
                                put("p_source", "app")
                            }
                        ).decodeAs<AccountAddonSecretPayload>()
                    }.requireValidV1Secret(
                        secretRef = secretRef,
                        addonUrl = addon.url
                    )
                }

            buildResolvedAddonUrl(
                baseUrl = addon.url,
                manifestUrl = addon.manifestUrl,
                publicQueryParams = addon.publicQueryParams,
                secretPayload = secretPayload
            ).let(::normalizeAddonInstallUrl)
        }
    }
}
