package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.data.trailer.filterCacheableTmdbTrailerVideos
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.orDefault
import java.lang.reflect.Type
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Singleton
class MetadataDiskCacheStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MetadataDiskCacheStore"
        private const val PREFS_NAME = "metadata_disk_cache_v1"
        private const val META_PREFIX = "meta::"
        private const val HOME_DISPLAY_PREFIX = "home_display::"
        private const val TMDB_PREFIX = "tmdb::"
        private const val TVDB_PREFIX = "tvdb::"
        private const val TVDB_EPISODE_PREFIX = "tvdb_episode::"
        private const val TMDB_TITLE_VIDEOS_PREFIX = "tmdb_videos::"
        private const val TMDB_SEASON_VIDEOS_PREFIX = "tmdb_season_videos::"
        private const val TVDB_REF_PREFIX = "tvdb_ref::"
        private const val TVDB_REFERENCE_PREFIX = "tvdb_ref::"
        private const val TVDB_REFERENCE_SCHEMA_VERSION = 1
        private const val META_CACHE_SCHEMA_VERSION = 4
        private const val HOME_DISPLAY_CACHE_SCHEMA_VERSION = 1
        private const val TMDB_CACHE_SCHEMA_VERSION = 2
        private const val TVDB_CACHE_SCHEMA_VERSION = 2
        private const val TVDB_EPISODE_CACHE_SCHEMA_VERSION = 2
        private const val TMDB_VIDEO_CACHE_SCHEMA_VERSION = 2
        private val TMDB_ENRICHMENT_CACHE_TTL: Duration = Duration.ofDays(7)
        private val TVDB_ENRICHMENT_CACHE_TTL: Duration = Duration.ofDays(7)
        private val TVDB_EPISODE_CACHE_TTL: Duration = Duration.ofHours(24)
        private val TVDB_REFERENCE_CACHE_TTL: Duration = Duration.ofDays(30)
        private val TMDB_VIDEO_CACHE_TTL: Duration = Duration.ofHours(12)
    }

    private val gson = Gson()
    private var ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceMs: Long = 250L
    private val pendingWrites = ConcurrentHashMap<String, String?>()
    private val flushScheduled = AtomicBoolean(false)

    internal constructor(
        context: Context,
        ioScope: CoroutineScope,
        debounceMs: Long,
    ) : this(context) {
        this.ioScope = ioScope
        this.debounceMs = debounceMs
    }

    private fun readPendingEntry(key: String): JsonObject? {
        val pending = pendingWrites[key] ?: return null
        return gson.fromJson(pending, JsonObject::class.java)
    }

    private fun enqueueWrite(key: String, payload: JsonObject) {
        pendingWrites[key] = gson.toJson(payload)
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        ioScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            flushPendingWrites()
        }
    }

    internal fun flushPendingWritesForTest() {
        flushPendingWrites()
    }

    private fun flushPendingWrites() {
        val snapshot = pendingWrites.entries.toList()
        if (snapshot.isEmpty()) {
            flushScheduled.set(false)
            return
        }
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            snapshot.forEach { (key, value) ->
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
            editor.apply()
            snapshot.forEach { (key, value) ->
                pendingWrites.remove(key, value)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to flush pending metadata disk writes", error)
        }
        flushScheduled.set(false)
        if (pendingWrites.isNotEmpty()) {
            scheduleFlush()
        }
    }

    fun currentLanguageEpoch(): Int = 0

    /**
     * Kept for callers/tests compiled against the old epoch API.
     * Locale is now represented by the language tag in each metadata key, so changing
     * one profile's language must not invalidate other profile/language entries.
     */
    fun bumpLanguageEpoch(): Int = currentLanguageEpoch()

    fun readMeta(itemKey: String, languageTag: String, providerToken: String): Meta? {
        val key = buildMetaKey(itemKey = itemKey, languageTag = languageTag, providerToken = providerToken)
        return runCatching {
            val root = readPendingEntry(key) ?: run {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
                gson.fromJson(raw, JsonObject::class.java)
            } ?: return null
            val schemaVersion = root.get("metaSchemaVersion")?.asInt ?: 0
            if (schemaVersion != META_CACHE_SCHEMA_VERSION) return null
            decodeMetaSafely(root)?.takeIf { it.hasValidPosterProviderTag(providerToken) }
        }.onFailure { error ->
            Log.w(TAG, "Failed to read disk metadata entry", error)
        }.getOrNull()
    }

    fun writeMeta(itemKey: String, languageTag: String, providerToken: String, meta: Meta) {
        val key = buildMetaKey(itemKey = itemKey, languageTag = languageTag, providerToken = providerToken)
        runCatching {
            val payload = JsonObject().apply {
                add("value", gson.toJsonTree(meta.sanitizedForCache()))
                addProperty("languageEpoch", currentLanguageEpoch())
                addProperty("metaSchemaVersion", META_CACHE_SCHEMA_VERSION)
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            enqueueWrite(key, payload)
        }.onFailure { error ->
            Log.w(TAG, "Failed to write disk metadata entry", error)
        }
    }

    fun writeHomeDisplayMetadata(itemKey: String, languageTag: String, metadata: HomeDisplayMetadata) {
        val key = buildHomeDisplayKey(itemKey = itemKey, languageTag = languageTag)
        runCatching {
            val payload = JsonObject().apply {
                add("value", gson.toJsonTree(metadata.sanitizedForCache()))
                addProperty("languageEpoch", currentLanguageEpoch())
                addProperty("homeDisplaySchemaVersion", HOME_DISPLAY_CACHE_SCHEMA_VERSION)
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            enqueueWrite(key, payload)
        }.onFailure { error ->
            Log.w(TAG, "Failed to write home display metadata entry", error)
        }
    }

    fun readTmdbEnrichment(tmdbKey: String, languageTag: String, providerToken: String): TmdbEnrichment? {
        val key = buildTmdbKey(tmdbKey = tmdbKey, languageTag = languageTag, providerToken = providerToken)
        return runCatching {
            val root = readPendingEntry(key) ?: run {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
                gson.fromJson(raw, JsonObject::class.java)
            } ?: return null
            val schemaVersion = root.get("tmdbSchemaVersion")?.asInt ?: 0
            if (schemaVersion != TMDB_CACHE_SCHEMA_VERSION) return null
            val updatedAtMs = root.get("updatedAtMs")?.asLong ?: return null
            if (isCacheEntryExpired(updatedAtMs, TMDB_ENRICHMENT_CACHE_TTL)) return null
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
            val payload = JsonObject().apply {
                add("value", gson.toJsonTree(enrichment))
                addProperty("languageEpoch", currentLanguageEpoch())
                addProperty("tmdbSchemaVersion", TMDB_CACHE_SCHEMA_VERSION)
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            enqueueWrite(key, payload)
        }.onFailure { error ->
            Log.w(TAG, "Failed to write TMDB enrichment disk cache entry", error)
        }
    }

    fun readTvdbEnrichment(
        seriesId: Int,
        recordKind: String,
        languageTag: String,
        providerToken: String
    ): TvMetadataEnrichment? {
        val key = buildTvdbKey(
            seriesId = seriesId,
            recordKind = recordKind,
            languageTag = languageTag,
            providerToken = providerToken
        )
        return runCatching {
            val root = readPendingEntry(key) ?: run {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
                gson.fromJson(raw, JsonObject::class.java)
            } ?: return null
            val schemaVersion = root.get("tvdbSchemaVersion")?.asInt ?: 0
            if (schemaVersion != TVDB_CACHE_SCHEMA_VERSION) return null
            val updatedAtMs = root.get("updatedAtMs")?.asLong ?: return null
            if (isCacheEntryExpired(updatedAtMs, TVDB_ENRICHMENT_CACHE_TTL)) return null
            decodeTvdbEnrichmentSafely(root)
        }.onFailure { error ->
            Log.w(TAG, "Failed to read TVDB enrichment disk cache entry", error)
        }.getOrNull()
    }

    fun writeTvdbEnrichment(
        seriesId: Int,
        recordKind: String,
        languageTag: String,
        providerToken: String,
        enrichment: TvMetadataEnrichment
    ) {
        val key = buildTvdbKey(
            seriesId = seriesId,
            recordKind = recordKind,
            languageTag = languageTag,
            providerToken = providerToken
        )
        runCatching {
            val payload = JsonObject().apply {
                add("value", gson.toJsonTree(enrichment))
                addProperty("languageEpoch", currentLanguageEpoch())
                addProperty("tvdbSchemaVersion", TVDB_CACHE_SCHEMA_VERSION)
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            enqueueWrite(key, payload)
        }.onFailure { error ->
            Log.w(TAG, "Failed to write TVDB enrichment disk cache entry", error)
        }
    }

    fun readTvdbSeasonEpisodes(
        seriesId: Int,
        seasonType: String,
        seasonNumber: Int,
        languageTag: String
    ): List<TvEpisodeMetadata>? {
        val key = buildTvdbSeasonEpisodesKey(
            seriesId = seriesId,
            seasonType = seasonType,
            seasonNumber = seasonNumber,
            languageTag = languageTag
        )
        return runCatching {
            val root = readPendingEntry(key) ?: run {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
                gson.fromJson(raw, JsonObject::class.java)
            } ?: return null
            val schemaVersion = root.get("tvdbEpisodeSchemaVersion")?.asInt ?: 0
            if (schemaVersion != TVDB_EPISODE_CACHE_SCHEMA_VERSION) return null
            val updatedAtMs = root.get("updatedAtMs")?.asLong ?: return null
            if (isCacheEntryExpired(updatedAtMs, TVDB_EPISODE_CACHE_TTL)) return null
            decodeTvdbSeasonEpisodesSafely(root)
        }.onFailure { error ->
            Log.w(TAG, "Failed to read TVDB episode disk cache entry", error)
        }.getOrNull()
    }

    fun writeTvdbSeasonEpisodes(
        seriesId: Int,
        seasonType: String,
        seasonNumber: Int,
        languageTag: String,
        episodes: List<TvEpisodeMetadata>
    ) {
        val key = buildTvdbSeasonEpisodesKey(
            seriesId = seriesId,
            seasonType = seasonType,
            seasonNumber = seasonNumber,
            languageTag = languageTag
        )
        runCatching {
            val payload = JsonObject().apply {
                add("value", gson.toJsonTree(episodes))
                addProperty("languageEpoch", currentLanguageEpoch())
                addProperty("tvdbEpisodeSchemaVersion", TVDB_EPISODE_CACHE_SCHEMA_VERSION)
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            enqueueWrite(key, payload)
        }.onFailure { error ->
            Log.w(TAG, "Failed to write TVDB episode disk cache entry", error)
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
        // Metadata and artwork caches are shared across profiles. A catalog diff in
        // one profile cannot prove another profile no longer needs the same item.
        return emptyList()
    }

    fun removeMetaEntriesNotIn(activeItemKeys: Set<String>, maxEntries: Int = 400): List<String> {
        // Active item sets are profile-specific; the metadata cache is shared.
        return emptyList()
    }

    fun hasCurrentMetaForItem(itemKey: String, languageTag: String): Boolean {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val expectedPrefix = "$META_PREFIX$itemKey::$languageTag::"
            prefs.all.entries
                .asSequence()
                .filter { (key, _) -> key.startsWith(expectedPrefix) }
                .any { (_, value) ->
                    val payload = (value as? String).orEmpty()
                    val root = gson.fromJson(payload, JsonObject::class.java)
                    val schemaVersion = root?.get("metaSchemaVersion")?.asInt ?: 0
                    schemaVersion == META_CACHE_SCHEMA_VERSION
                }
        }.getOrDefault(false)
    }

    fun readCurrentMetaForItem(itemKey: String, languageTag: String): Meta? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val expectedPrefix = "$META_PREFIX$itemKey::$languageTag::"
            prefs.all.entries
                .asSequence()
                .filter { (key, _) -> key.startsWith(expectedPrefix) }
                .mapNotNull { (_, value) ->
                    val payload = (value as? String).orEmpty()
                    val root = gson.fromJson(payload, JsonObject::class.java) ?: return@mapNotNull null
                    val schemaVersion = root.get("metaSchemaVersion")?.asInt ?: 0
                    if (schemaVersion != META_CACHE_SCHEMA_VERSION) return@mapNotNull null
                    decodeMetaSafely(root)
                }
                .firstOrNull()
        }.onFailure { error ->
            Log.w(TAG, "Failed to read current metadata for item", error)
        }.getOrNull()
    }

    fun hasCurrentHomeDisplayMetadataForItem(itemKey: String, languageTag: String): Boolean {
        return readCurrentHomeDisplayMetadataForItem(
            itemKey = itemKey,
            languageTag = languageTag
        ) != null
    }

    fun readCurrentHomeDisplayMetadataForItem(itemKey: String, languageTag: String): HomeDisplayMetadata? {
        val key = buildHomeDisplayKey(itemKey = itemKey, languageTag = languageTag)
        return runCatching {
            val root = readPendingEntry(key) ?: run {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
                gson.fromJson(raw, JsonObject::class.java)
            } ?: return null
            val schemaVersion = root.get("homeDisplaySchemaVersion")?.asInt ?: 0
            if (schemaVersion != HOME_DISPLAY_CACHE_SCHEMA_VERSION) return null
            decodeHomeDisplayMetadataSafely(root)
        }.onFailure { error ->
            Log.w(TAG, "Failed to read current home display metadata for item", error)
        }.getOrNull()
    }

    /**
     * Reads TVDB reference data for the given kind.
     * Returns null if the cache entry is absent, malformed, or has a schema mismatch.
     * Never returns raw IDs as labels -- returns null when values are absent.
     */
    fun <T> readTvdbReference(kind: String, type: Type): List<T>? {
        val key = "${TVDB_REFERENCE_PREFIX}${kind.trim().lowercase()}::data"
        return runCatching {
            val root = readPendingEntry(key) ?: run {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
                gson.fromJson(raw, JsonObject::class.java)
            } ?: return null
            val schemaVersion = root.get("tvdbReferenceSchemaVersion")?.asInt ?: 0
            if (schemaVersion != TVDB_REFERENCE_SCHEMA_VERSION) return null
            val updatedAtMs = root.get("updatedAtMs")?.asLong ?: return null
            if (isCacheEntryExpired(updatedAtMs, TVDB_REFERENCE_CACHE_TTL)) return null
            val valuesElement = root.get("values") ?: return null
            val listType = TypeToken.getParameterized(List::class.java, type).type
            val values: List<T> = gson.fromJson(valuesElement, listType) ?: return null
            values
        }.onFailure { error ->
            Log.w(TAG, "Failed to read TVDB reference cache entry for $kind", error)
        }.getOrNull()
    }

    /**
     * Reads TVDB reference data for the given kind with reified type parameter.
     */
    inline fun <reified T> readTvdbReference(kind: String): List<T>? {
        return readTvdbReference(kind, T::class.java)
    }

    /**
     * Writes TVDB reference data for the given kind.
     * Includes schema version and timestamp for freshness and migration safety.
     * Uses write batching for efficient bulk writes.
     */
    fun writeTvdbReference(kind: String, values: List<Any>) {
        val key = "${TVDB_REFERENCE_PREFIX}${kind.trim().lowercase()}::data"
        runCatching {
            val payload = JsonObject().apply {
                add("values", gson.toJsonTree(values))
                addProperty("tvdbReferenceSchemaVersion", TVDB_REFERENCE_SCHEMA_VERSION)
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            enqueueWrite(key, payload)
        }.onFailure { error ->
            Log.w(TAG, "Failed to write TVDB reference cache entry for $kind", error)
        }
    }

    /**
     * Removes TVDB reference data for the given kind.
     */
    fun removeTvdbReference(kind: String) {
        val key = "${TVDB_REFERENCE_PREFIX}${kind.trim().lowercase()}::data"
        pendingWrites.remove(key)
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(key).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to remove TVDB reference cache entry for $kind", error)
        }
    }

    /**
     * Removes all TVDB series metadata cache entries for the given series ID.
     * Matches keys starting with `tvdb::$seriesId::`.
     */
    fun removeTvdbSeriesEntries(seriesId: Int): Int {
        return removePrefixedEntries("$TVDB_PREFIX$seriesId::")
    }

    /**
     * Removes all TVDB episode cache entries for the given series ID.
     * Matches keys starting with `tvdb_episode::$seriesId::`.
     */
    fun removeTvdbEpisodeEntries(seriesId: Int): Int {
        return removePrefixedEntries("$TVDB_EPISODE_PREFIX$seriesId::")
    }

    /**
     * Removes TVDB reference cache entries matching a specific reference type.
     * Matches keys starting with `tvdb_ref::$refType::`.
     */
    fun removeTvdbRefEntries(refType: String): Int {
        return removePrefixedEntries("$TVDB_REF_PREFIX${refType.trim().lowercase()}::")
    }

    fun clearAll() {
        pendingWrites.clear()
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear all metadata cache entries", error)
        }
    }

    private fun removePrefixedEntries(prefix: String): Int {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val keysToRemove = prefs.all.keys.filter { it.startsWith(prefix) }
            if (keysToRemove.isEmpty()) return 0
            val editor = prefs.edit()
            keysToRemove.forEach { key ->
                editor.remove(key)
                pendingWrites.remove(key)
            }
            editor.apply()
            keysToRemove.size
        }.onFailure { error ->
            Log.w(TAG, "Failed to remove prefixed entries for $prefix", error)
        }.getOrDefault(0)
    }

    fun removeEntriesFromStaleEpochs(maxEntries: Int = 800): List<String> {
        // The global language epoch has been retired. Text metadata is selected by
        // languageTag in the key, and image cache entries are language-independent.
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.all.keys
                .asSequence()
                .filter { key -> key.startsWith(TVDB_PREFIX) || key.startsWith(TVDB_EPISODE_PREFIX) }
                .count()
        }
        return emptyList()
    }

    private fun buildMetaKey(itemKey: String, languageTag: String, providerToken: String): String {
        return "$META_PREFIX$itemKey::$languageTag::$providerToken"
    }

    private fun buildHomeDisplayKey(itemKey: String, languageTag: String): String {
        return "$HOME_DISPLAY_PREFIX$itemKey::$languageTag"
    }

    private fun requiredPosterProviderTag(providerToken: String): String? {
        val provider = providerToken.substringBefore(':').trim()
        return provider
            .takeIf { it.isNotBlank() && !it.equals("native", ignoreCase = true) }
            ?.lowercase()
    }

    private fun Meta.hasValidPosterProviderTag(providerToken: String): Boolean {
        val requiredTag = requiredPosterProviderTag(providerToken) ?: return true
        return posterProviderTag == requiredTag
    }

    private fun buildTmdbKey(tmdbKey: String, languageTag: String, providerToken: String): String {
        return "$TMDB_PREFIX$tmdbKey::$languageTag::$providerToken"
    }

    private fun buildTvdbKey(
        seriesId: Int,
        recordKind: String,
        languageTag: String,
        providerToken: String
    ): String {
        return "$TVDB_PREFIX$seriesId::${recordKind.trim().lowercase()}::$languageTag::$providerToken"
    }

    private fun buildTvdbSeasonEpisodesKey(
        seriesId: Int,
        seasonType: String,
        seasonNumber: Int,
        languageTag: String
    ): String {
        return "$TVDB_EPISODE_PREFIX$seriesId::${seasonType.trim().lowercase()}::$seasonNumber::$languageTag"
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
            val root = readPendingEntry(key) ?: run {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
                gson.fromJson(raw, JsonObject::class.java)
            } ?: return null
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
            val filteredVideos = filterCacheableTmdbTrailerVideos(videos)
            val payload = JsonObject().apply {
                add("value", gson.toJsonTree(filteredVideos))
                addProperty("languageEpoch", currentLanguageEpoch())
                addProperty("tmdbVideoSchemaVersion", TMDB_VIDEO_CACHE_SCHEMA_VERSION)
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            enqueueWrite(key, payload)
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
            .sanitizedForCache()
        val castMembersFromJson = readCastMembers(valueObj, "castMembers")
        val castFromJson = readStringList(valueObj, "cast")
        val safeCastMembers = when {
            castMembersFromJson.isNotEmpty() -> castMembersFromJson
            castFromJson.isNotEmpty() -> castFromJson.map { MetaCastMember(name = it) }
            else -> emptyList()
        }
        return parsed.copy(castMembers = safeCastMembers).sanitizedForCache()
    }

    private fun decodeHomeDisplayMetadataSafely(root: JsonObject): HomeDisplayMetadata? {
        val value = root.get("value") ?: return null
        val parsed = runCatching { gson.fromJson(value, HomeDisplayMetadata::class.java) }.getOrNull() ?: return null
        return parsed.sanitizedForCache()
    }

    private fun decodeTmdbEnrichmentSafely(root: JsonObject): TmdbEnrichment? {
        val value = root.get("value") ?: return null
        val parsed = runCatching { gson.fromJson(value, TmdbEnrichment::class.java) }.getOrNull() ?: return null
        val valueObj = value.asJsonObject
        return mergeTmdbEnrichmentCollections(parsed, valueObj).sanitizedForCache()
    }

    private fun decodeTmdbVideosSafely(root: JsonObject): List<TmdbVideoResult>? {
        val value = root.get("value") ?: return null
        val type = object : TypeToken<List<TmdbVideoResult>>() {}.type
        return runCatching { gson.fromJson<List<TmdbVideoResult>>(value, type) }.getOrNull()
    }

    private fun decodeTvdbEnrichmentSafely(root: JsonObject): TvMetadataEnrichment? {
        val value = root.get("value") ?: return null
        val parsed = runCatching { gson.fromJson(value, TvMetadataEnrichment::class.java) }.getOrNull() ?: return null
        val valueObj = runCatching { value.asJsonObject }.getOrNull() ?: return parsed.copy(
            rating = null,
            castMembers = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList()
        ).sanitizedForCache()
        return parsed.copy(
            rating = null,
            castMembers = readCastMembersFromJson(valueObj, "castMembers"),
            productionCompanies = readCompaniesFromJson(valueObj, "productionCompanies"),
            networks = readCompaniesFromJson(valueObj, "networks")
        ).sanitizedForCache()
    }

    private fun decodeTvdbSeasonEpisodesSafely(root: JsonObject): List<TvEpisodeMetadata>? {
        val value = root.get("value") ?: return null
        val type = object : TypeToken<List<TvEpisodeMetadata>>() {}.type
        return runCatching { gson.fromJson<List<TvEpisodeMetadata>>(value, type) }.getOrNull()
    }

    private fun isTmdbVideoCacheEntryExpired(updatedAtMs: Long): Boolean {
        return isCacheEntryExpired(updatedAtMs, TMDB_VIDEO_CACHE_TTL)
    }

    private fun isCacheEntryExpired(updatedAtMs: Long, ttl: Duration): Boolean {
        val updatedAt = Instant.ofEpochMilli(updatedAtMs)
        return Duration.between(updatedAt, Instant.now()) > ttl
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
        val provider = obj.get("provider")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val providerId = obj.get("providerId")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotBlank() }
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
            kind = kind,
            provider = provider,
            providerId = providerId
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
        ratingSource = parsed.ratingSource.orDefault(TitleRatingSource.TMDB),
        directorMembers = safeDirectorMembers,
        writerMembers = safeWriterMembers,
        castMembers = safeCastMembers,
        productionCompanies = safeProductionCompanies,
        networks = safeNetworks
    ).sanitizedForCache()
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
    val provider = obj.get("provider")
        ?.takeUnless { it.isJsonNull }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val providerId = obj.get("providerId")
        ?.takeUnless { it.isJsonNull }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotBlank() }
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
        kind = kind,
        provider = provider,
        providerId = providerId
    )
}
