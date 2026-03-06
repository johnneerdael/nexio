package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeCatalogSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "HomeCatalogSnapshot"
        private const val PREFS_NAME = "home_catalog_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
    }

    private val gson = Gson()

    data class Snapshot(
        val catalogRows: List<CatalogRow>,
        val fullCatalogRows: List<CatalogRow>,
        val heroItems: List<MetaPreview>
    )

    fun read(): Snapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            val type = object : TypeToken<Snapshot>() {}.type
            gson.fromJson<Snapshot>(raw, type)
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore home snapshot", error)
        }.getOrNull()
    }

    fun write(snapshot: Snapshot) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(snapshot)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist home snapshot", error)
        }
    }

    fun clear() {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear home snapshot", error)
        }
    }
}
