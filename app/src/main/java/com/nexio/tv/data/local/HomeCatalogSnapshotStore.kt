package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeCatalogSnapshotStore private constructor(
    @ApplicationContext private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val activeProfileId: () -> Int
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        posterRatingsUrlResolver: PosterRatingsUrlResolver,
        profileManager: ProfileManager
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        posterRatingsUrlResolver = posterRatingsUrlResolver,
        activeProfileId = { profileManager.activeProfileId.value }
    )

    constructor(
        context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        posterRatingsUrlResolver: PosterRatingsUrlResolver
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        posterRatingsUrlResolver = posterRatingsUrlResolver,
        activeProfileId = { 1 }
    )

    companion object {
        private const val TAG = "HomeCatalogSnapshot"
        private const val PREFS_NAME = "home_catalog_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 4
    }

    private val gson = Gson()

    suspend fun currentPosterProviderToken(): String {
        val provider = posterRatingsUrlResolver.getActiveProvider() ?: return "native"
        return "${provider.provider.name}:${provider.apiKey.hashCode()}"
    }

    data class Snapshot(
        val catalogRows: List<CatalogRow>,
        val fullCatalogRows: List<CatalogRow>,
        val heroItems: List<MetaPreview>,
        val orderedGroupKeys: List<String> = emptyList()
    )

    fun read(
        posterProviderToken: String,
        profileId: Int = activeProfileId()
    ): Snapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(snapshotKey(profileId), null)?.takeIf { it.isNotBlank() } ?: return null
            decodeSnapshot(raw, posterProviderToken)?.sanitize()
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore home snapshot", error)
            clear(profileId)
        }.getOrNull()
    }

    fun readActiveProfile(posterProviderToken: String): Snapshot? {
        return read(posterProviderToken, activeProfileId())
    }

    fun write(
        snapshot: Snapshot,
        posterProviderToken: String,
        profileId: Int = activeProfileId()
    ) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                addProperty("schemaVersion", SCHEMA_VERSION)
                addProperty("languageEpoch", metadataDiskCacheStore.currentLanguageEpoch())
                addProperty("languageTag", currentLanguageTag())
                addProperty("posterProviderToken", posterProviderToken)
                add("catalogRows", gson.toJsonTree(snapshot.catalogRows))
                add("fullCatalogRows", gson.toJsonTree(snapshot.fullCatalogRows))
                add("heroItems", gson.toJsonTree(snapshot.heroItems))
                add("orderedGroupKeys", gson.toJsonTree(snapshot.orderedGroupKeys))
            }
            prefs.edit().putString(snapshotKey(profileId), gson.toJson(payload)).commit()
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist home snapshot", error)
        }
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(snapshotKey(profileId)).commit()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear home snapshot", error)
        }
    }

    private fun decodeSnapshot(raw: String, posterProviderToken: String): Snapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val schemaVersion = root.get("schemaVersion")?.asInt ?: 0
        if (schemaVersion != SCHEMA_VERSION) {
            return null
        }
        val languageTag = root.get("languageTag")?.asString?.trim().orEmpty()
        if (languageTag.isBlank() || languageTag != currentLanguageTag()) {
            return null
        }
        val cachedPosterToken = root.get("posterProviderToken")?.asString?.trim().orEmpty()
        if (cachedPosterToken != posterProviderToken) {
            Log.d(TAG, "Poster provider changed ($cachedPosterToken -> $posterProviderToken), invalidating snapshot")
            return null
        }
        val canonical = Snapshot(
            catalogRows = decodeArray<CatalogRow>(root, "catalogRows"),
            fullCatalogRows = decodeArray<CatalogRow>(root, "fullCatalogRows"),
            heroItems = decodeArray<MetaPreview>(root, "heroItems"),
            orderedGroupKeys = decodeArray<String>(root, "orderedGroupKeys")
        )
        if (canonical.catalogRows.isNotEmpty() || canonical.fullCatalogRows.isNotEmpty() || canonical.heroItems.isNotEmpty()) {
            return canonical
        }

        // Legacy payloads were stored via direct Gson reflection and may use obfuscated field names.
        return runCatching {
            gson.fromJson(raw, Snapshot::class.java)
        }.getOrNull()
    }

    private inline fun <reified T> decodeArray(root: JsonObject, key: String): List<T> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        val type = object : TypeToken<List<T>>() {}.type
        return gson.fromJson<List<T>>(array, type) ?: emptyList()
    }

    private fun currentLanguageTag(): String {
        return AppLocaleResolver.resolveEffectiveAppLanguageTag(context)
    }

    private fun snapshotKey(profileId: Int = activeProfileId()): String {
        return "$SNAPSHOT_KEY:p$profileId:${currentLanguageTag()}"
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
            heroItems = sanitizedHeroItems,
            orderedGroupKeys = orderedGroupKeys.distinct()
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
