package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.CatalogRow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PersistedSyntheticCatalogGroup(
    val orderKey: String,
    val rows: List<CatalogRow>
)

@Singleton
class SyntheticHomeCatalogStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore
) {

    companion object {
        private const val TAG = "SyntheticHomeCatalog"
        private const val PREFS_NAME = "synthetic_home_catalogs"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 1
    }

    private val gson = Gson()

    data class Snapshot(
        val traktGroups: List<PersistedSyntheticCatalogGroup> = emptyList(),
        val mdbListGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
    )

    fun read(): Snapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            decodeSnapshot(raw)
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore synthetic home catalogs", error)
            clear()
        }.getOrNull()
    }

    fun write(snapshot: Snapshot) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                addProperty("schemaVersion", SCHEMA_VERSION)
                addProperty("languageEpoch", metadataDiskCacheStore.currentLanguageEpoch())
                add("traktGroups", gson.toJsonTree(snapshot.traktGroups))
                add("mdbListGroups", gson.toJsonTree(snapshot.mdbListGroups))
            }
            prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(payload)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist synthetic home catalogs", error)
        }
    }

    fun clear() {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear synthetic home catalogs", error)
        }
    }

    private fun decodeSnapshot(raw: String): Snapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val schemaVersion = root.get("schemaVersion")?.asInt ?: 0
        if (schemaVersion != SCHEMA_VERSION) {
            return null
        }
        val languageEpoch = root.get("languageEpoch")?.asInt ?: metadataDiskCacheStore.currentLanguageEpoch()
        if (languageEpoch != metadataDiskCacheStore.currentLanguageEpoch()) {
            return null
        }
        return Snapshot(
            traktGroups = decodeGroups(root.getAsJsonArray("traktGroups")),
            mdbListGroups = decodeGroups(root.getAsJsonArray("mdbListGroups"))
        )
    }

    private fun decodeGroups(array: JsonArray?): List<PersistedSyntheticCatalogGroup> {
        return array
            ?.mapNotNull(::decodeGroup)
            .orEmpty()
    }

    private fun decodeGroup(element: JsonElement): PersistedSyntheticCatalogGroup? {
        val obj = element.asJsonObject ?: return null
        val orderKey = obj.get("orderKey")?.asString?.trim().orEmpty()
        if (orderKey.isBlank()) return null
        val rows = obj.getAsJsonArray("rows")
            ?.mapNotNull(::decodeRow)
            .orEmpty()
        return PersistedSyntheticCatalogGroup(
            orderKey = orderKey,
            rows = rows
        )
    }

    private fun decodeRow(element: JsonElement): CatalogRow? {
        return runCatching {
            gson.fromJson(element, CatalogRow::class.java)
        }.getOrNull()
    }
}
