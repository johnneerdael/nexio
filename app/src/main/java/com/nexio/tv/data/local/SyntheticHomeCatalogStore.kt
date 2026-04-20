package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.CatalogRow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PersistedSyntheticCatalogGroup(
    val orderKey: String,
    val rows: List<CatalogRow>
)

@Singleton
class SyntheticHomeCatalogStore private constructor(
    @ApplicationContext private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val activeProfileId: () -> Int
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        profileManager: ProfileManager
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        activeProfileId = { profileManager.activeProfileId.value }
    )

    constructor(
        context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        activeProfileId = { 1 }
    )

    companion object {
        private const val TAG = "SyntheticHomeCatalog"
        private const val PREFS_NAME = "synthetic_home_catalogs"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 4
    }

    private val gson = Gson()

    data class Snapshot(
        val traktGroups: List<PersistedSyntheticCatalogGroup> = emptyList(),
        val simklGroups: List<PersistedSyntheticCatalogGroup> = emptyList(),
        val mdbListGroups: List<PersistedSyntheticCatalogGroup> = emptyList(),
        val tmdbGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
    )

    fun read(profileId: Int = activeProfileId()): Snapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(snapshotKey(profileId), null)?.takeIf { it.isNotBlank() } ?: return null
            decodeSnapshot(raw)
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore synthetic home catalogs", error)
            clear(profileId)
        }.getOrNull()
    }

    fun write(
        snapshot: Snapshot,
        profileId: Int = activeProfileId()
    ) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                addProperty("schemaVersion", SCHEMA_VERSION)
                addProperty("languageEpoch", metadataDiskCacheStore.currentLanguageEpoch())
                addProperty("languageTag", currentLanguageTag())
                add("traktGroups", encodeGroups(snapshot.traktGroups))
                add("simklGroups", encodeGroups(snapshot.simklGroups))
                add("mdbListGroups", encodeGroups(snapshot.mdbListGroups))
                add("tmdbGroups", encodeGroups(snapshot.tmdbGroups))
            }
            prefs.edit().putString(snapshotKey(profileId), gson.toJson(payload)).commit()
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist synthetic home catalogs", error)
        }
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(snapshotKey(profileId)).commit()
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
        val languageTag = root.get("languageTag")?.asString?.trim().orEmpty()
        if (languageTag.isBlank() || languageTag != currentLanguageTag()) {
            return null
        }
        return Snapshot(
            traktGroups = decodeGroups(root.getAsJsonArray("traktGroups")),
            simklGroups = decodeGroups(root.getAsJsonArray("simklGroups")),
            mdbListGroups = decodeGroups(root.getAsJsonArray("mdbListGroups")),
            tmdbGroups = decodeGroups(root.getAsJsonArray("tmdbGroups"))
        )
    }

    private fun currentLanguageTag(): String {
        return AppLocaleResolver.resolveEffectiveAppLanguageTag(context)
    }

    private fun snapshotKey(profileId: Int = activeProfileId()): String {
        return "$SNAPSHOT_KEY:p$profileId:${currentLanguageTag()}"
    }

    private fun decodeGroups(array: JsonArray?): List<PersistedSyntheticCatalogGroup> {
        return array
            ?.mapNotNull(::decodeGroup)
            .orEmpty()
    }

    private fun encodeGroups(groups: List<PersistedSyntheticCatalogGroup>): JsonArray {
        return JsonArray().apply {
            groups.forEach { group ->
                add(
                    JsonObject().apply {
                        addProperty("orderKey", group.orderKey)
                        add("rows", JsonArray().apply {
                            group.rows.forEach { row ->
                                add(gson.toJsonTree(row))
                            }
                        })
                    }
                )
            }
        }
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
                ?.sanitizedForCache()
        }.getOrNull()
    }
}
