package com.nexio.tv.core.sync

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRoute
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.core.profile.ProfileSettingsDomain
import com.nexio.tv.data.local.ProfileDataStoreFactory
import com.nexio.tv.data.local.SyncWatermarkDataStore
import com.nexio.tv.data.remote.supabase.ProfileSettingsBlobResponse
import com.nexio.tv.data.remote.supabase.V10ProfileSettingsEnvelope
import com.nexio.tv.data.remote.supabase.V10PushResult
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileSettingsSyncService"

@Singleton
class ProfileSettingsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileManager: ProfileManager,
    private val profileDataStoreFactory: ProfileDataStoreFactory,
    private val profileModeRouter: ProfileModeRouter,
    private val profileBoundary: ProfileBoundary,
    private val syncWatermarkStore: SyncWatermarkDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    val syncedFeatures = listOf(
        "trakt_settings",
        "simkl_settings",
        "player_settings",
        "layout_settings",
        "theme_settings"
    )

    @Volatile private var applyingRemoteBlob = false
    private var skipNextPushSignature: String? = null
    private var observerJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun startObserving() {
        if (observerJob != null) return
        observerJob = scope.launch {
            profileManager.activeProfileId
                .map { it }
                .distinctUntilChanged()
                .flatMapLatest { profileId ->
                    flow {
                        val route = profileModeRouter.routeFor(profileId)
                        if (route is ProfileModeRoute.DefaultLegacyRoute || route is ProfileModeRoute.InvalidProfileRoute) {
                            return@flow
                        }
                        val scopedProfileId = profileBoundary.settingsRoute(
                            route as ProfileModeRoute.SecondaryProfileRoute,
                            ProfileSettingsDomain.CATALOGS
                        ).profileId
                        val pullResult = pullBlobForProfile(scopedProfileId)
                        if (pullResult.isFailure) {
                            Log.w(
                                TAG,
                                "Skipping settings observer push for profile $scopedProfileId because hydration failed",
                                pullResult.exceptionOrNull()
                            )
                            return@flow
                        }
                        emitAll(
                            observeProfileSettings(scopedProfileId)
                                .drop(1)
                                .debounce(2000)
                                .map { scopedProfileId }
                        )
                    }
                }
                .collect { profileId ->
                    if (applyingRemoteBlob) return@collect
                    pushBlobForProfile(profileId)
                }
        }
    }

    fun stopObserving() {
        observerJob?.cancel()
        observerJob = null
    }

    suspend fun pushBlobForProfile(profileId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val route = profileModeRouter.routeFor(profileId)
        when (route) {
            ProfileModeRoute.DefaultLegacyRoute -> return@withContext Result.success(Unit)
            is ProfileModeRoute.InvalidProfileRoute -> return@withContext Result.failure(
                IllegalArgumentException("Invalid profile id ${route.profileId}")
            )
            is ProfileModeRoute.SecondaryProfileRoute -> Unit
        }
        val scopedProfileId = profileBoundary.settingsRoute(
            route,
            ProfileSettingsDomain.CATALOGS
        ).profileId

        syncMutex.withLock {
            try {
                val blob = exportSettingsBlob(scopedProfileId)
                val signature = buildSettingsSignature(blob)
                if (signature == skipNextPushSignature) {
                    skipNextPushSignature = null
                    return@withLock Result.success(Unit)
                }

                val baseMs = syncWatermarkStore.get(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = scopedProfileId)
                val outcome = runV10Push {
                    withJwtRefreshRetry {
                        postgrest.rpc(
                            "sync_push_profile_settings_blob_v10",
                            buildJsonObject {
                                put("p_base_updated_at_ms", baseMs)
                                put("p_profile_id", scopedProfileId)
                                put("p_settings_json", blob.toString())
                                put("p_platform", "tv")
                            }
                        ).decodeAs<V10PushResult>()
                    }
                }
                when (outcome) {
                    is V10PushOutcome.Applied -> {
                        syncWatermarkStore.set(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = scopedProfileId, ms = outcome.currentUpdatedAtMs)
                        Log.d(TAG, "Pushed settings blob for profile $scopedProfileId (watermark=${outcome.currentUpdatedAtMs})")
                    }
                    is V10PushOutcome.StaleBase -> {
                        Log.w(TAG, "Profile blob push stale for profile $scopedProfileId (server=${outcome.currentUpdatedAtMs}, base=$baseMs); pulling")
                        pullBlobForProfile(scopedProfileId)
                    }
                    is V10PushOutcome.Failed -> throw outcome.cause
                    is V10PushOutcome.FieldConflict -> Unit
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push settings blob for profile $scopedProfileId", e)
                Result.failure(e)
            }
        }
    }

    suspend fun pullBlobForProfile(profileId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val route = profileModeRouter.routeFor(profileId)
        when (route) {
            ProfileModeRoute.DefaultLegacyRoute -> return@withContext Result.success(Unit)
            is ProfileModeRoute.InvalidProfileRoute -> return@withContext Result.failure(
                IllegalArgumentException("Invalid profile id ${route.profileId}")
            )
            is ProfileModeRoute.SecondaryProfileRoute -> Unit
        }
        val scopedProfileId = profileBoundary.settingsRoute(
            route,
            ProfileSettingsDomain.CATALOGS
        ).profileId

        syncMutex.withLock {
            try {
                val envelope = withJwtRefreshRetry {
                    postgrest.rpc(
                        "sync_pull_profile_settings_blob_v10",
                        buildJsonObject {
                            put("p_profile_id", scopedProfileId)
                            put("p_platform", "tv")
                        }
                    ).decodeAs<V10ProfileSettingsEnvelope>()
                }
                syncWatermarkStore.set(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = scopedProfileId, ms = envelope.updatedAtMs)
                val rawBlob = decodeSettingsJson(envelope.settingsJson)
                val normalizedBlob = normalizeSettingsBlob(rawBlob)

                applyingRemoteBlob = true
                try {
                    importSettingsBlob(scopedProfileId, normalizedBlob)
                    skipNextPushSignature = buildSettingsSignature(normalizedBlob)
                } finally {
                    applyingRemoteBlob = false
                }

                Log.d(TAG, "Pulled settings blob for profile $scopedProfileId")
                Result.success(Unit)
            } catch (e: Exception) {
                applyingRemoteBlob = false
                Log.e(TAG, "Failed to pull settings blob for profile $scopedProfileId", e)
                Result.failure(e)
            }
        }
    }

    private fun observeProfileSettings(profileId: Int): Flow<List<Preferences>> {
        val featureFlows = syncedFeatures.map { feature ->
            profileDataStoreFactory.get(profileId, feature).data
        }
        return combine(featureFlows) { preferences -> preferences.toList() }
    }

    @VisibleForTesting
    internal fun decodeSettingsJson(settingsJson: JsonElement): JsonObject {
        return decodeSettingsJsonElement(settingsJson, depth = 0)
    }

    private fun decodeSettingsJsonElement(settingsJson: JsonElement, depth: Int): JsonObject {
        if (depth > 3) return buildJsonObject {}
        settingsJson.jsonObjectOrNull()?.let { return it }
        val raw = settingsJson.primitiveContentOrNull()?.takeIf { it.isNotBlank() } ?: return buildJsonObject {}
        val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return buildJsonObject {}
        return decodeSettingsJsonElement(parsed, depth + 1)
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? {
        return runCatching { jsonObject }.getOrNull()
    }

    private fun JsonElement.primitiveContentOrNull(): String? {
        return runCatching { jsonPrimitive.contentOrNull }.getOrNull()
    }

    private suspend fun exportSettingsBlob(profileId: Int): JsonObject {
        return buildJsonObject {
            syncedFeatures.forEach { feature ->
                val preferences = profileDataStoreFactory.get(profileId, feature).data.first()
                put(
                    feature,
                    buildJsonObject {
                        preferences.asMap().forEach { (key, value) ->
                            encodePreferenceValue(value)?.let { encoded ->
                                put(key.name, encoded)
                            }
                        }
                    }
                )
            }
        }
    }

    @VisibleForTesting
    internal fun normalizeSettingsBlob(blob: JsonObject): JsonObject {
        return buildJsonObject {
            syncedFeatures.forEach { feature ->
                val featureBlob = blob[feature]?.let { element ->
                    runCatching { element.jsonObject }.getOrNull()
                } ?: buildJsonObject {}
                put(feature, featureBlob)
            }
        }
    }

    @VisibleForTesting
    internal suspend fun importSettingsBlob(profileId: Int, blob: JsonObject) {
        val normalizedBlob = normalizeSettingsBlob(blob)
        syncedFeatures.forEach { feature ->
            val featureBlob = normalizedBlob[feature]?.jsonObject ?: buildJsonObject {}
            val store = profileDataStoreFactory.get(profileId, feature)
            store.edit { preferences ->
                preferences.clear()
                featureBlob.forEach { (key, valueElement) ->
                    val valueObj = runCatching { valueElement.jsonObject }.getOrNull() ?: return@forEach
                    applyEncodedPreference(preferences, key, valueObj)
                }
            }
        }
    }

    @VisibleForTesting
    internal fun encodePreferenceValue(rawValue: Any?): JsonObject? {
        return when (rawValue) {
            is String -> buildJsonObject { put("type", "string"); put("value", rawValue) }
            is Boolean -> buildJsonObject { put("type", "boolean"); put("value", rawValue) }
            is Int -> buildJsonObject { put("type", "int"); put("value", rawValue) }
            is Long -> buildJsonObject { put("type", "long"); put("value", rawValue) }
            is Float -> buildJsonObject { put("type", "float"); put("value", rawValue.toDouble()) }
            is Double -> buildJsonObject { put("type", "double"); put("value", rawValue) }
            is Set<*> -> buildJsonObject {
                put("type", "string_set")
                putJsonArray("value") { rawValue.filterIsInstance<String>().forEach { add(JsonPrimitive(it)) } }
            }
            else -> null
        }
    }

    private fun applyEncodedPreference(prefs: MutablePreferences, key: String, valueObj: JsonObject) {
        val type = valueObj["type"]?.jsonPrimitive?.contentOrNull ?: return
        val value = valueObj["value"] ?: return
        runCatching {
            when (type) {
                "string" -> prefs[stringPreferencesKey(key)] = value.jsonPrimitive.content
                "boolean" -> prefs[booleanPreferencesKey(key)] = value.jsonPrimitive.boolean
                "int" -> prefs[intPreferencesKey(key)] = value.jsonPrimitive.int
                "long" -> prefs[longPreferencesKey(key)] = value.jsonPrimitive.long
                "float" -> prefs[floatPreferencesKey(key)] = value.jsonPrimitive.float
                "double" -> prefs[doublePreferencesKey(key)] = value.jsonPrimitive.double
                "string_set" -> prefs[stringSetPreferencesKey(key)] =
                    value.jsonArray.map { it.jsonPrimitive.content }.toSet()
            }
        }.onFailure { e ->
            Log.w(TAG, "Skipping invalid encoded preference $key of type $type", e)
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

    @VisibleForTesting
    internal fun buildSettingsSignature(blob: JsonObject): String {
        return blob.toString().hashCode().toString()
    }
}
