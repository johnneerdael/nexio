package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.data.repository.MDBListCustomCatalog
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.MDBListListOption
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MDBListDiscoverySnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "MDBListDiscoveryStore"
        private const val PREFS_NAME = "mdblist_discovery_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
    }

    private val gson = Gson()

    fun read(): MDBListDiscoverySnapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            decode(raw)
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore MDBList discovery snapshot", error)
            clear()
        }.getOrNull()
    }

    fun write(snapshot: MDBListDiscoverySnapshot) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(snapshot)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist MDBList discovery snapshot", error)
        }
    }

    fun clear() {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear MDBList discovery snapshot", error)
        }
    }

    private fun decode(raw: String): MDBListDiscoverySnapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        return MDBListDiscoverySnapshot(
            personalLists = decodeArray(root, "personalLists"),
            topLists = decodeArray(root, "topLists"),
            customListCatalogs = decodeArray(root, "customListCatalogs"),
            updatedAtMs = root.get("updatedAtMs")?.asLong ?: 0L
        )
    }

    private inline fun <reified T> decodeArray(root: JsonObject, key: String): List<T> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        val type = object : TypeToken<List<T>>() {}.type
        return gson.fromJson<List<T>>(array, type) ?: emptyList()
    }
}
