package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.domain.model.MetaPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklDiscoverySnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SimklDiscoveryStore"
        private const val PREFS_NAME = "simkl_discovery_snapshot_v2"
        private const val LEGACY_PREFS_NAME = "simkl_discovery_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
    }

    private val gson = Gson()
    private val itemsByCatalogType = object : TypeToken<Map<String, List<MetaPreview>>>() {}.type

    fun read(): SimklDiscoverySnapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            decode(raw)
        }.onFailure {
            Log.w(TAG, "Failed to restore SIMKL discovery snapshot", it)
            clear()
        }.getOrNull()
    }

    fun write(snapshot: SimklDiscoverySnapshot) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    fun clear() {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(SNAPSHOT_KEY).commit()
        }
    }

    private fun decode(raw: String): SimklDiscoverySnapshot {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return SimklDiscoverySnapshot()
        val itemsByCatalog = root.getAsJsonObject("itemsByCatalog")
            ?.let { gson.fromJson<Map<String, List<MetaPreview>>>(it, itemsByCatalogType) }
            ?: emptyMap()
        return SimklDiscoverySnapshot(
            itemsByCatalog = itemsByCatalog,
            updatedAtMs = root.get("updatedAtMs")?.asLong ?: 0L
        )
    }
}
