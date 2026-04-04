package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.data.trailer.filterCacheableTmdbTrailerVideos
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
import java.time.Duration
import java.time.Instant
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataDiskCacheStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MetadataDiskCacheStore"
        private const val PREFS_NAME = "metadata_disk_cache_v1"
        private const val META_PREFIX = "meta::"
        private const val TMDB_PREFIX = "tmdb::"
        private const val TMDB_TITLE_VIDEOS_PREFIX = "tmdb_videos::"
        private const val TMDB_SEASON_VIDEOS_PREFIX = "tmdb_season_videos::"
        private const val HOME_REF_PREFIX = "home_ref::"
        private const val LANGUAGE_EPOCH_KEY = "metadata_language_epoch"
        private const val META_CACHE_SCHEMA_VERSION = 3
        private const val TMDB_CACHE_SCHEMA_VERSION = 2
        private const val TMDB_VIDEO_CACHE_SCHEMA_VERSION = 1
        private val TMDB_VIDEO_CACHE_TTL: Duration = Duration.ofHours(12)
    }

    private val gson = Gson()

    fun currentLanguageEpoch(): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(LANGUAGE_EPOCH_KEY, 0)
    }

    fun bumpLanguageEpoch(): Int {
        val next = currentLanguageEpoch() + 1
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(LANGUAGE_EPOCH_KEY, next).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to bump metadata language epoch", error)
        }
        return next
    }

    fun readMeta(itemKey: String, languageTag: String, providerToken: String): Meta? {
        val key = buildMetaKey(itemKey = itemKey, languageTag = languageTag, providerToken = providerToken)
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
            val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
            val epoch = root.get("languageEpoch")?.asInt ?: 0
            if (epoch != currentLanguageEpoch()) return null
            val schemaVersion = root.get("metaSchemaVersion")?.asInt ?: 0
            if (schemaVersion != META_CACHE_SCHEMA_VERSION) return null
            decodeMetaSafely(root)
        }.onFailure { error ->
            Log.w(TAG, "Failed to read disk metadata entry", error)
        }.getOrNull()
    }

    fun writeMeta(itemKey: String, languageTag: String, providerToken: String, meta: Meta) {
        val key = buildMetaKey(itemKey = itemKey, languageTag = languageTag, providerToken = providerToken)
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                add("value", gson.toJsonTree(meta))
                addProperty("languageEpoch", currentLanguageEpoch())
                addProperty("metaSchemaVersion", META_CACHE_SCHEMA_VERSION)
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            prefs.edit().putString(key, gson.toJson(payload)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to write disk metadata entry", error)
        }
    }

    fun readTmdbEnrichment(tmdbKey: String, languageTag: String, providerToken: String): TmdbEnrichment? {
        val key = buildTmdbKey(tmdbKey = tmdbKey, languageTag = languageTag, providerToken = providerToken)
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
            val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
            val epoch = root.get("languageEpoch")?.asInt ?: 0
            if (epoch != currentLanguageEpoch()) return null
            val schemaVersion = root.get("tmdbSchemaVersion")?.asInt ?: 0
            if (schemaVersion != TMDB_CACHE_SCHEMA_VERSION) return null
            decodeTmdbEnrichmentSafely(root)
        }.onFailure { error ->
            Log.w(TAG, "Failed to read TMDB enrichment disk cache entry", error)
        }.getOrNull()
    }

    fun writeTmdbEnrichment(
        tmdbKey: String,
        languageTag: String,
        providerToken: String,
        enrichment: TmdbEnrichment
    ) {
        val key = buildTmdbKey(tmdbKey = tmdbKey, languageTag = languageTag, providerToken = providerToken)
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                add("value", gson.toJsonTree(enrichment))
                addProperty("languageEpoch", currentLanguageEpoch())
                addProperty("tmdbSchemaVersion", TMDB_CACHE_SCHEMA_VERSION)
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            prefs.edit().putString(key, gson.toJson(payload)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to write TMDB enrichment disk cache entry", error)
        }
    }

    fun readTmdbTitleVideos(
        tmdbId: Int,
        mediaType: String,
        languageTag: String,
        providerToken: String
    ): List<TmdbVideoResult>? {
        val key = buildTmdbTitleVideosKey(
            tmdbId = tmdbId,
            mediaType = mediaType,
            languageTag = languageTag,
            providerToken = providerToken
        )
        return readTmdbVideosEntry(key)
    }

    fun writeTmdbTitleVideos(
        tmdbId: Int,
        mediaType: String,
        languageTag: String,
        providerToken: String,
        videos: List<TmdbVideoResult>
    ) {
        val key = buildTmdbTitleVideosKey(
            tmdbId = tmdbId,
            mediaType = mediaType,
            languageTag = languageTag,
            providerToken = providerToken
        )
        writeTmdbVideosEntry(key, videos)
    }

    fun readTmdbSeasonVideos(
        tmdbId: Int,
        seasonNumber: Int,
        languageTag: String,
        providerToken: String
    ): List<TmdbVideoResult>? {
        val key = buildTmdbSeasonVideosKey(
            tmdbId = tmdbId,
            seasonNumber = seasonNumber,
            languageTag = languageTag,
            providerToken = providerToken
        )
        return readTmdbVideosEntry(key)
    }

    fun writeTmdbSeasonVideos(
        tmdbId: Int,
        seasonNumber: Int,
        languageTag: String,
        providerToken: String,
        videos: List<TmdbVideoResult>
    ) {
        val key = buildTmdbSeasonVideosKey(
            tmdbId = tmdbId,
            seasonNumber = seasonNumber,
            languageTag = languageTag,
            providerToken = providerToken
        )
        writeTmdbVideosEntry(key, videos)
    }

    fun removeMetaEntriesForItem(itemKey: String): List<String> {
        val removedImageUrls = mutableListOf<String>()
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            prefs.all.entries
                .asSequence()
                .filter { (key, _) -> key.startsWith("$META_PREFIX$itemKey::") }
                .forEach { (key, value) ->
                    val payload = (value as? String).orEmpty()
                    val root = gson.fromJson(payload, JsonObject::class.java)
                    val meta = runCatching { decodeMetaSafely(root ?: JsonObject()) }.getOrNull()
                    meta?.poster?.let(removedImageUrls::add)
                    meta?.background?.let(removedImageUrls::add)
                    meta?.logo?.let(removedImageUrls::add)
                    editor.remove(key)
                }
            editor.apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to remove metadata entries for item=$itemKey", error)
        }
        return removedImageUrls.distinct()
    }

    fun removeMetaEntriesNotIn(activeItemKeys: Set<String>, maxEntries: Int = 400): List<String> {
        if (activeItemKeys.isEmpty()) return emptyList()
        val removedImageUrls = mutableListOf<String>()
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            var removedCount = 0
            prefs.all.entries
                .asSequence()
                .filter { (key, _) -> key.startsWith(META_PREFIX) }
                .forEach { (key, value) ->
                    if (removedCount >= maxEntries) return@forEach
                    val remainder = key.removePrefix(META_PREFIX)
                    val itemKey = remainder.substringBefore("::")
                    if (itemKey in activeItemKeys) return@forEach
                    val payload = (value as? String).orEmpty()
                    val root = gson.fromJson(payload, JsonObject::class.java)
                    val meta = runCatching { decodeMetaSafely(root ?: JsonObject()) }.getOrNull()
                    meta?.poster?.let(removedImageUrls::add)
                    meta?.background?.let(removedImageUrls::add)
                    meta?.logo?.let(removedImageUrls::add)
                    editor.remove(key)
                    removedCount += 1
                }
            editor.apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to remove stale metadata entries", error)
        }
        return removedImageUrls.distinct()
    }

    fun hasCurrentMetaForItem(itemKey: String, languageTag: String): Boolean {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val expectedPrefix = "$META_PREFIX$itemKey::$languageTag::"
            val currentEpoch = currentLanguageEpoch()
            prefs.all.entries
                .asSequence()
                .filter { (key, _) -> key.startsWith(expectedPrefix) }
                .any { (_, value) ->
                    val payload = (value as? String).orEmpty()
                    val root = gson.fromJson(payload, JsonObject::class.java)
                    (root?.get("languageEpoch")?.asInt ?: -1) == currentEpoch
                }
        }.getOrDefault(false)
    }

    fun replaceHomeFeedReferences(feedKey: String, itemKeys: Set<String>) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                add("items", gson.toJsonTree(itemKeys.toList().sorted()))
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            prefs.edit().putString("$HOME_REF_PREFIX$feedKey", gson.toJson(payload)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to update home feed references for $feedKey", error)
        }
    }

    fun readHomeReferencedItemKeys(): Set<String> {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.all.entries
                .asSequence()
                .filter { (key, _) -> key.startsWith(HOME_REF_PREFIX) }
                .flatMap { (_, value) ->
                    val payload = (value as? String).orEmpty()
                    val root = gson.fromJson(payload, JsonObject::class.java)
                    val type = object : TypeToken<List<String>>() {}.type
                    val items = gson.fromJson<List<String>>(root?.get("items"), type).orEmpty()
                    items.asSequence()
                }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    fun removeHomeUnreferencedMetaEntries(maxEntries: Int = 400): List<String> {
        val activeItemKeys = readHomeReferencedItemKeys()
        val removedImageUrls = mutableListOf<String>()
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            var removedCount = 0
            prefs.all.entries
                .asSequence()
                .filter { (key, _) -> key.startsWith(META_PREFIX) }
                .forEach { (key, value) ->
                    if (removedCount >= maxEntries) return@forEach
                    val remainder = key.removePrefix(META_PREFIX)
                    val itemKey = remainder.substringBefore("::")
                    if (itemKey in activeItemKeys) return@forEach
                    val payload = (value as? String).orEmpty()
                    val root = gson.fromJson(payload, JsonObject::class.java)
                    val meta = runCatching { decodeMetaSafely(root ?: JsonObject()) }.getOrNull()
                    meta?.poster?.let(removedImageUrls::add)
                    meta?.background?.let(removedImageUrls::add)
                    meta?.logo?.let(removedImageUrls::add)
                    editor.remove(key)
                    removedCount += 1
                }
            editor.apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to remove home-unreferenced metadata entries", error)
        }
        return removedImageUrls.distinct()
    }

    fun removeEntriesFromStaleEpochs(maxEntries: Int = 800): List<String> {
        val currentEpoch = currentLanguageEpoch()
        val removedImageUrls = mutableListOf<String>()
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            var removedCount = 0
            prefs.all.entries
                .asSequence()
                .filter { (key, _) ->
                    key.startsWith(META_PREFIX) ||
                        key.startsWith(TMDB_PREFIX) ||
                        key.startsWith(TMDB_TITLE_VIDEOS_PREFIX) ||
                        key.startsWith(TMDB_SEASON_VIDEOS_PREFIX)
                }
                .forEach { (key, value) ->
                    if (removedCount >= maxEntries) return@forEach
                    val payload = (value as? String).orEmpty()
                    val root = gson.fromJson(payload, JsonObject::class.java) ?: return@forEach
                    val epoch = root.get("languageEpoch")?.asInt ?: return@forEach
                    if (epoch == currentEpoch) return@forEach
                    if (key.startsWith(META_PREFIX)) {
                        val meta = runCatching { decodeMetaSafely(root) }.getOrNull()
                        meta?.poster?.let(removedImageUrls::add)
                        meta?.background?.let(removedImageUrls::add)
                        meta?.logo?.let(removedImageUrls::add)
                    } else if (key.startsWith(TMDB_PREFIX)) {
                        val enrichment = runCatching { decodeTmdbEnrichmentSafely(root) }.getOrNull()
                        enrichment?.poster?.let(removedImageUrls::add)
                        enrichment?.backdrop?.let(removedImageUrls::add)
                        enrichment?.logo?.let(removedImageUrls::add)
                    }
                    editor.remove(key)
                    removedCount += 1
                }
            editor.apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to remove stale epoch metadata entries", error)
        }
        return removedImageUrls.distinct()
    }

    private fun buildMetaKey(itemKey: String, languageTag: String, providerToken: String): String {
        return "$META_PREFIX$itemKey::$languageTag::$providerToken"
    }

    private fun buildTmdbKey(tmdbKey: String, languageTag: String, providerToken: String): String {
        return "$TMDB_PREFIX$tmdbKey::$languageTag::$providerToken"
    }

    private fun buildTmdbTitleVideosKey(
        tmdbId: Int,
        mediaType: String,
        languageTag: String,
        providerToken: String
    ): String {
        return "$TMDB_TITLE_VIDEOS_PREFIX$tmdbId::${mediaType.trim().lowercase()}::$languageTag::$providerToken"
    }

    private fun buildTmdbSeasonVideosKey(
        tmdbId: Int,
        seasonNumber: Int,
        languageTag: String,
        providerToken: String
    ): String {
        return "$TMDB_SEASON_VIDEOS_PREFIX$tmdbId::$seasonNumber::$languageTag::$providerToken"
    }

    private fun readTmdbVideosEntry(key: String): List<TmdbVideoResult>? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
            val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
            val epoch = root.get("languageEpoch")?.asInt ?: 0
            if (epoch != currentLanguageEpoch()) return null
            val schemaVersion = root.get("tmdbVideoSchemaVersion")?.asInt ?: 0
            if (schemaVersion != TMDB_VIDEO_CACHE_SCHEMA_VERSION) return null
            val updatedAtMs = root.get("updatedAtMs")?.asLong ?: return null
            if (isTmdbVideoCacheEntryExpired(updatedAtMs)) return null
            decodeTmdbVideosSafely(root)
        }.onFailure { error ->
            Log.w(TAG, "Failed to read TMDB videos disk cache entry", error)
        }.getOrNull()
    }

    private fun writeTmdbVideosEntry(key: String, videos: List<TmdbVideoResult>) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val filteredVideos = filterCacheableTmdbTrailerVideos(videos)
            val payload = JsonObject().apply {
                add("value", gson.toJsonTree(filteredVideos))
                addProperty("languageEpoch", currentLanguageEpoch())
                addProperty("tmdbVideoSchemaVersion", TMDB_VIDEO_CACHE_SCHEMA_VERSION)
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            prefs.edit().putString(key, gson.toJson(payload)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to write TMDB videos disk cache entry", error)
        }
    }

    /**
     * R8/minification can erase generic signatures used by Gson for list element typing.
     * Rebuild castMembers from raw JSON so malformed cached entries can't crash UI code.
     */
    private fun decodeMetaSafely(root: JsonObject): Meta? {
        val value = root.get("value") ?: return null
        val parsed = runCatching { gson.fromJson(value, Meta::class.java) }.getOrNull() ?: return null
        val valueObj = runCatching { value.asJsonObject }.getOrNull() ?: return parsed.copy(castMembers = emptyList())
        val castMembersFromJson = readCastMembers(valueObj, "castMembers")
        val castFromJson = readStringList(valueObj, "cast")
        val safeCastMembers = when {
            castMembersFromJson.isNotEmpty() -> castMembersFromJson
            castFromJson.isNotEmpty() -> castFromJson.map { MetaCastMember(name = it) }
            else -> emptyList()
        }
        return parsed.copy(castMembers = safeCastMembers)
    }

    private fun decodeTmdbEnrichmentSafely(root: JsonObject): TmdbEnrichment? {
        val value = root.get("value") ?: return null
        val parsed = runCatching { gson.fromJson(value, TmdbEnrichment::class.java) }.getOrNull() ?: return null
        val valueObj = value.asJsonObject
        return mergeTmdbEnrichmentCollections(parsed, valueObj)
    }

    private fun decodeTmdbVideosSafely(root: JsonObject): List<TmdbVideoResult>? {
        val value = root.get("value") ?: return null
        val type = object : TypeToken<List<TmdbVideoResult>>() {}.type
        return runCatching { gson.fromJson<List<TmdbVideoResult>>(value, type) }.getOrNull()
    }

    private fun isTmdbVideoCacheEntryExpired(updatedAtMs: Long): Boolean {
        val updatedAt = Instant.ofEpochMilli(updatedAtMs)
        return Duration.between(updatedAt, Instant.now()) > TMDB_VIDEO_CACHE_TTL
    }

    private fun readCastMembers(obj: JsonObject, key: String): List<MetaCastMember> {
        return obj.getAsJsonArray(key)
            ?.mapNotNull { element -> runCatching { gson.fromJson(element, MetaCastMember::class.java) }.getOrNull() }
            ?.mapNotNull { member ->
                val name = member.name.trim()
                if (name.isBlank()) null else member.copy(name = name)
            }
            .orEmpty()
    }

    private fun readCompanies(obj: JsonObject, key: String): List<MetaCompany> {
        val fallbackKind = if (key == "networks") {
            MetaCompanyKind.NETWORK
        } else {
            MetaCompanyKind.COMPANY
        }
        return obj.getAsJsonArray(key)
            ?.mapNotNull { element ->
                readCompany(element, fallbackKind)
            }
            ?.mapNotNull { company ->
                val name = company.name.trim()
                if (name.isBlank()) null else company.copy(name = name)
            }
            .orEmpty()
    }

    private fun readCompany(
        element: JsonElement,
        fallbackKind: MetaCompanyKind
    ): MetaCompany? {
        val obj = runCatching { element.asJsonObject }.getOrNull() ?: return null
        val name = obj.get("name")?.asString?.trim().orEmpty()
        if (name.isBlank()) return null

        val logo = obj.get("logo")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val tmdbId = obj.get("tmdbId")
            ?.takeUnless { it.isJsonNull }
            ?.asInt
        val kind = obj.get("kind")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { MetaCompanyKind.valueOf(raw) }.getOrNull() }
            ?: fallbackKind

        return MetaCompany(
            tmdbId = tmdbId,
            name = name,
            logo = logo,
            kind = kind
        )
    }

    private fun readStringList(obj: JsonObject, key: String): List<String> {
        return obj.getAsJsonArray(key)
            ?.mapNotNull { element -> runCatching { element.asString.trim() }.getOrNull() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }
}

internal fun mergeTmdbEnrichmentCollections(
    parsed: TmdbEnrichment,
    valueObj: JsonObject
): TmdbEnrichment {
    val safeDirectorMembers = readCastMembersFromJson(valueObj, "directorMembers")
        .ifEmpty { parsed.directorMembers }
    val safeWriterMembers = readCastMembersFromJson(valueObj, "writerMembers")
        .ifEmpty { parsed.writerMembers }
    val safeCastMembers = readCastMembersFromJson(valueObj, "castMembers")
        .ifEmpty { parsed.castMembers }
    val safeProductionCompanies = readCompaniesFromJson(valueObj, "productionCompanies")
        .ifEmpty { parsed.productionCompanies }
    val safeNetworks = readCompaniesFromJson(valueObj, "networks")
        .ifEmpty { parsed.networks }
    return parsed.copy(
        directorMembers = safeDirectorMembers,
        writerMembers = safeWriterMembers,
        castMembers = safeCastMembers,
        productionCompanies = safeProductionCompanies,
        networks = safeNetworks
    )
}

private fun readCastMembersFromJson(obj: JsonObject, key: String): List<MetaCastMember> {
    return obj.getAsJsonArray(key)
        ?.mapNotNull { element -> runCatching { Gson().fromJson(element, MetaCastMember::class.java) }.getOrNull() }
        ?.mapNotNull { member ->
            val name = member.name.trim()
            if (name.isBlank()) null else member.copy(name = name)
        }
        .orEmpty()
}

private fun readCompaniesFromJson(obj: JsonObject, key: String): List<MetaCompany> {
    val fallbackKind = if (key == "networks") {
        MetaCompanyKind.NETWORK
    } else {
        MetaCompanyKind.COMPANY
    }
    return obj.getAsJsonArray(key)
        ?.mapNotNull { element ->
            readCompanyFromJson(element, fallbackKind)
        }
        ?.mapNotNull { company ->
            val name = company.name.trim()
            if (name.isBlank()) null else company.copy(name = name)
        }
        .orEmpty()
}

private fun readCompanyFromJson(
    element: JsonElement,
    fallbackKind: MetaCompanyKind
): MetaCompany? {
    val obj = runCatching { element.asJsonObject }.getOrNull() ?: return null
    val name = obj.get("name")?.asString?.trim().orEmpty()
    if (name.isBlank()) return null

    val logo = obj.get("logo")
        ?.takeUnless { it.isJsonNull }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val tmdbId = obj.get("tmdbId")
        ?.takeUnless { it.isJsonNull }
        ?.asInt
    val kind = obj.get("kind")
        ?.takeUnless { it.isJsonNull }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { raw -> runCatching { MetaCompanyKind.valueOf(raw) }.getOrNull() }
        ?: fallbackKind

    return MetaCompany(
        tmdbId = tmdbId,
        name = name,
        logo = logo,
        kind = kind
    )
}
