package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
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
            decodeSnapshot(raw)?.sanitize()
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore home snapshot", error)
            clear()
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

    private fun decodeSnapshot(raw: String): Snapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        return Snapshot(
            catalogRows = decodeArray<CatalogRow>(root, "catalogRows"),
            fullCatalogRows = decodeArray<CatalogRow>(root, "fullCatalogRows"),
            heroItems = decodeArray<MetaPreview>(root, "heroItems")
        )
    }

    private inline fun <reified T> decodeArray(root: JsonObject, key: String): List<T> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        val type = object : TypeToken<List<T>>() {}.type
        return gson.fromJson<List<T>>(array, type) ?: emptyList()
    }

    private fun Snapshot.sanitize(): Snapshot {
        val sanitizedCatalogRows = sanitizeCatalogRows(catalogRows as List<*>, "catalogRows")
        val sanitizedFullCatalogRows = sanitizeCatalogRows(fullCatalogRows as List<*>, "fullCatalogRows")
        val sanitizedHeroItems = sanitizeMetaPreviews(heroItems as List<*>, "heroItems")

        val droppedCatalogRows = (catalogRows as List<*>).size - sanitizedCatalogRows.size
        val droppedFullCatalogRows = (fullCatalogRows as List<*>).size - sanitizedFullCatalogRows.size
        val droppedHeroItems = (heroItems as List<*>).size - sanitizedHeroItems.size

        if (droppedCatalogRows > 0 || droppedFullCatalogRows > 0 || droppedHeroItems > 0) {
            Log.w(
                TAG,
                "Discarded malformed cached home snapshot entries: " +
                    "catalogRows=$droppedCatalogRows fullCatalogRows=$droppedFullCatalogRows heroItems=$droppedHeroItems"
            )
        }

        return Snapshot(
            catalogRows = sanitizedCatalogRows,
            fullCatalogRows = sanitizedFullCatalogRows,
            heroItems = sanitizedHeroItems
        )
    }

    private fun sanitizeCatalogRows(values: List<*>, label: String): List<CatalogRow> {
        return values.mapIndexedNotNull { index, value ->
            val row = value as? CatalogRow
            if (row == null) {
                Log.w(TAG, "Dropping malformed cached $label[$index]: ${value?.javaClass?.name}")
                return@mapIndexedNotNull null
            }

            val sanitizedItems = sanitizeMetaPreviews(row.items as List<*>, "$label[$index].items")
            if (sanitizedItems.size != (row.items as List<*>).size) {
                Log.w(
                    TAG,
                    "Dropping malformed cached items from $label[$index] for catalogId=${row.catalogId}"
                )
            }
            row.copy(items = sanitizedItems)
        }
    }

    private fun sanitizeMetaPreviews(values: List<*>, label: String): List<MetaPreview> {
        return values.mapIndexedNotNull { index, value ->
            val item = value as? MetaPreview
            if (item == null) {
                Log.w(TAG, "Dropping malformed cached $label[$index]: ${value?.javaClass?.name}")
            }
            item
        }
    }
}
