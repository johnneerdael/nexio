package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.profilePrefsName
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.domain.model.MetaPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklDiscoverySnapshotStore private constructor(
    private val context: Context,
    private val activeProfileId: () -> Int,
    private val injectedProfileManager: ProfileManager?
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileManager: ProfileManager
    ) : this(
        context = context,
        activeProfileId = { profileManager.activeProfileId.value },
        injectedProfileManager = profileManager
    )

    constructor(context: Context) : this(
        context = context,
        activeProfileId = { 1 },
        injectedProfileManager = null
    )

    companion object {
        private const val TAG = "SimklDiscoveryStore"
        internal const val BASE_PREFS_NAME = "simkl_discovery_snapshot_v2"
        private const val LEGACY_PREFS_NAME = "simkl_discovery_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val EXTERNAL_ID_CACHE_KEY = "external_id_cache"
    }

    private val gson = Gson()
    private val itemsByCatalogType = object : TypeToken<Map<String, List<MetaPreview>>>() {}.type

    private fun injectedPrefsName(profileId: Int): String =
        profilePrefsName(BASE_PREFS_NAME, profileId)

    private fun prefsName(profileId: Int = activeProfileId()): String =
        if (injectedProfileManager != null) {
            injectedPrefsName(profileId)
        } else {
            profilePrefsName(BASE_PREFS_NAME, profileId)
        }

    fun read(profileId: Int = activeProfileId()): SimklDiscoverySnapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            decode(raw)
        }.onFailure {
            Log.w(TAG, "Failed to restore SIMKL discovery snapshot", it)
            clear(profileId)
        }.getOrNull()
    }

    fun write(
        snapshot: SimklDiscoverySnapshot,
        profileId: Int = activeProfileId()
    ) {
        runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                add("itemsByCatalog", gson.toJsonTree(snapshot.itemsByCatalog))
                addProperty("updatedAtMs", snapshot.updatedAtMs)
            }
            prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(payload)).commit()
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(SNAPSHOT_KEY)
                .commit()
        }.onFailure { Log.w(TAG, "Failed to persist SIMKL discovery snapshot", it) }
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE).edit().remove(SNAPSHOT_KEY).commit()
        }
    }

    fun readExternalIdCache(profileId: Int = activeProfileId()): Map<String, String?> {
        return runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            val raw = prefs.getString(EXTERNAL_ID_CACHE_KEY, null)?.takeIf { it.isNotBlank() } ?: return emptyMap()
            val type = object : TypeToken<Map<String, String?>>() {}.type
            gson.fromJson<Map<String, String?>>(raw, type) ?: emptyMap()
        }.onFailure { Log.w(TAG, "Failed to restore external ID cache", it) }.getOrDefault(emptyMap())
    }

    fun writeExternalIdCache(cache: Map<String, String?>, profileId: Int = activeProfileId()) {
        runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            prefs.edit().putString(EXTERNAL_ID_CACHE_KEY, gson.toJson(cache)).commit()
        }.onFailure { Log.w(TAG, "Failed to persist external ID cache", it) }
    }

    private fun decode(raw: String): SimklDiscoverySnapshot {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return SimklDiscoverySnapshot()
        val itemsByCatalog = root.getAsJsonObject("itemsByCatalog")
            ?.let { gson.fromJson<Map<String, List<MetaPreview>>>(it, itemsByCatalogType) }
            ?.mapValues { (_, items) -> items.orEmpty().map { item -> item.sanitizedForCache() } }
            ?: emptyMap()
        return SimklDiscoverySnapshot(
            itemsByCatalog = itemsByCatalog,
            updatedAtMs = root.get("updatedAtMs")?.asLong ?: 0L
        )
    }
}
