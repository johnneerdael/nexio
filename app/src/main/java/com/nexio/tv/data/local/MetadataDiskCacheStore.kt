package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
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
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.reflect.Type
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        // Legacy SharedPreferences file name. Read once on boot for migration, then deleted.
        // See CLAUDE.md hard rule #3 — large blobs in SharedPreferences forced full-XML
        // re-serialization on every putString and a 200+ KB transient char[] per fromJson.
        private const val LEGACY_PREFS_NAME = "metadata_disk_cache_v1"
        // File-backed JSON snapshot. One JSON object whose keys are the cache keys
        // (META_PREFIX/TMDB_PREFIX/etc.) and whose values are the per-entry JsonObject.
        private const val SNAPSHOT_FILE_NAME = "metadata_disk_cache_v1.json"
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

    // Live in-memory cache. Replaces the previous SharedPreferences-backed
    // pendingWrites + prefs.getString(...) round-trip. Entries are kept as JsonObject
    // (not String) so reads never call gson.fromJson(rawString, type) — that overload
    // wraps the String in a StringReader which pins the entire String for the parse,
    // and was the source of 3 × 205 KiB transient char[] copies of the same TVDB
    // {"airsDays":...} JSON observed in heap dumps. See CLAUDE.md hard rule #3
    // (read-side clause).
    private val cache = ConcurrentHashMap<String, JsonObject>()
    private val flushScheduled = AtomicBoolean(false)
    private val loaded = AtomicBoolean(false)
    private val migratedFromLegacy = AtomicBoolean(false)
    private val loadLock = Any()

    internal constructor(
        context: Context,
        ioScope: CoroutineScope,
        debounceMs: Long,
    ) : this(context) {
        this.ioScope = ioScope
        this.debounceMs = debounceMs
    }

    private fun snapshotFile(): File = File(context.filesDir, SNAPSHOT_FILE_NAME)

    /**
     * Lazily loads the snapshot file into [cache] on first access. If the file is
     * absent but the legacy SharedPreferences file exists, migrates entries from
     * SharedPreferences (parse each value String once) and schedules a flush; the
     * legacy prefs file is then deleted.
     */
    private fun ensureLoaded() {
        if (loaded.get()) return
        synchronized(loadLock) {
            if (loaded.get()) return
            try {
                val file = snapshotFile()
                if (file.exists()) {
                    loadSnapshotFromFile(file)
                    clearStaleLegacyPrefsAfterSuccessfulFileLoad()
                } else if (legacyPrefsExists()) {
                    migrateFromLegacyPrefs()
                    scheduleFlush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load metadata disk cache snapshot", e)
            } finally {
                loaded.set(true)
            }
        }
    }

    private fun legacyPrefsExists(): Boolean {
        // SharedPreferences XML files live under /data/data/<pkg>/shared_prefs/.
        // Probing prefs.all is the safer cross-API check.
        val prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all.isNotEmpty()
    }

    private fun loadSnapshotFromFile(file: File) {
        // Streaming read — never materializes the whole file as a String. The hard rule
        // bans `gson.fromJson(rawString, type)` for cache reads >50 KB.
        FileInputStream(file).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        return
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            continue
                        }
                        // gson.fromJson(JsonReader, JsonObject::class.java) consumes one
                        // value at a time; no full-file string materialization happens.
                        val value = gson.fromJson<JsonObject>(reader, JsonObject::class.java)
                        if (value != null) cache[key] = value
                    }
                    reader.endObject()
                }
            }
        }
    }

    private fun migrateFromLegacyPrefs() {
        val prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.all.forEach { (key, value) ->
            if (value is String && value.isNotBlank()) {
                runCatching { gson.fromJson(value, JsonObject::class.java) }
                    .getOrNull()
                    ?.let { cache[key] = it }
            }
        }
        // Do not clear legacy prefs here. They remain the crash-recovery source
        // until the migrated cache has been written to the file snapshot.
        migratedFromLegacy.set(true)
    }

    @androidx.annotation.VisibleForTesting
    internal fun reloadFromLegacyPrefsForTest() {
        // Test-only: re-runs the legacy-prefs migration into [cache] without
        // touching the snapshot file. Lets tests update timestamps in prefs
        // (rewriteUpdatedAt helper) and have the cache reflect the change.
        migrateFromLegacyPrefs()
    }

    @androidx.annotation.VisibleForTesting
    internal fun rewriteUpdatedAtForTest(key: String, updatedAtMs: Long) {
        // Test-only: mutates the in-memory entry's `updatedAtMs` so TTL-expiry
        // tests can simulate a stale entry without going through the legacy prefs
        // surface. Replacement for tests that previously did
        // prefs.edit().putString(key, json-with-old-timestamp).commit().
        ensureLoaded()
        val entry = cache[key] ?: return
        entry.addProperty("updatedAtMs", updatedAtMs)
    }

    @androidx.annotation.VisibleForTesting
    internal fun snapshotFileForTest(): File = snapshotFile()

    private fun readPendingEntry(key: String): JsonObject? {
        ensureLoaded()
        return cache[key]
    }

    private fun enqueueWrite(key: String, payload: JsonObject) {
        ensureLoaded()
        cache[key] = payload
        scheduleFlush()
    }

    private fun removeEntry(key: String) {
        ensureLoaded()
        if (cache.remove(key) != null) scheduleFlush()
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
        // Serialize the entire in-memory map to disk. The whole map is normally <300 KB
        // and the file write happens off-thread on Dispatchers.IO; per CLAUDE.md hard
        // rule #3 we use a streaming JsonWriter so no `gson.toJson(value): String` ever
        // materializes the full snapshot as a char[].
        try {
            // Snapshot the keys so concurrent puts during the flush don't crash the
            // serializer. Late writers schedule another flush via scheduleFlush() in
            // enqueueWrite, so any updates that arrive mid-flush are picked up next pass.
            val snapshot = HashMap<String, JsonObject>(cache.size)
            for ((k, v) in cache) snapshot[k] = v
            writeSnapshotToFile(snapshot, snapshotFile())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to flush metadata disk cache snapshot", e)
        } finally {
            flushScheduled.set(false)
        }
    }

    private fun writeSnapshotToFile(snapshot: Map<String, JsonObject>, target: File) {
        var tempFile: File? = null
        try {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            tempFile = File(parent ?: File("."), "${target.name}.tmp")
            FileOutputStream(tempFile).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        for ((key, value) in snapshot) {
                            writer.name(key)
                            gson.toJson(value, JsonObject::class.java, writer)
                        }
                        writer.endObject()
                    }
                }
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            clearLegacyPrefsAfterSuccessfulMigrationFlush()
        } catch (e: Exception) {
            tempFile?.delete()
            throw e
        }
    }

    private fun clearLegacyPrefsAfterSuccessfulMigrationFlush() {
        if (!migratedFromLegacy.get()) return
        clearLegacyPrefs().onSuccess { cleared ->
            if (cleared) migratedFromLegacy.set(false)
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear legacy metadata prefs after migration", error)
        }
    }

    private fun clearStaleLegacyPrefsAfterSuccessfulFileLoad() {
        val prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.all.isEmpty()) return
        clearLegacyPrefs().onFailure { error ->
            Log.w(TAG, "Failed to clear stale legacy metadata prefs after file load", error)
        }
    }

    private fun clearLegacyPrefs(): Result<Boolean> {
        return runCatching {
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
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
            val root = readPendingEntry(key) ?: return null
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
            val root = readPendingEntry(key) ?: return null
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
            val root = readPendingEntry(key) ?: return null
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
            val root = readPendingEntry(key) ?: return null
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
        ensureLoaded()
        val expectedPrefix = "$META_PREFIX$itemKey::$languageTag::"
        for ((key, root) in cache) {
            if (!key.startsWith(expectedPrefix)) continue
            val schemaVersion = root.get("metaSchemaVersion")?.asInt ?: 0
            if (schemaVersion == META_CACHE_SCHEMA_VERSION) return true
        }
        return false
    }

    fun readCurrentMetaForItem(itemKey: String, languageTag: String): Meta? {
        ensureLoaded()
        val expectedPrefix = "$META_PREFIX$itemKey::$languageTag::"
        for ((key, root) in cache) {
            if (!key.startsWith(expectedPrefix)) continue
            val schemaVersion = root.get("metaSchemaVersion")?.asInt ?: 0
            if (schemaVersion != META_CACHE_SCHEMA_VERSION) continue
            val decoded = runCatching { decodeMetaSafely(root) }.getOrNull()
            if (decoded != null) return decoded
        }
        return null
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
            val root = readPendingEntry(key) ?: return null
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
            val root = readPendingEntry(key) ?: return null
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
        removeEntry(key)
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
        ensureLoaded()
        cache.clear()
        scheduleFlush()
        // Best-effort: also clear any lingering legacy SharedPreferences in case the
        // migration hasn't run yet.
        runCatching {
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }.onFailure { Log.w(TAG, "Failed to clear legacy metadata prefs", it) }
    }

    private fun removePrefixedEntries(prefix: String): Int {
        ensureLoaded()
        var removed = 0
        val keys = ArrayList<String>(cache.size)
        for (key in cache.keys) {
            if (key.startsWith(prefix)) keys += key
        }
        if (keys.isEmpty()) return 0
        for (i in keys.indices) {
            if (cache.remove(keys[i]) != null) removed += 1
        }
        if (removed > 0) scheduleFlush()
        return removed
    }

    fun removeEntriesFromStaleEpochs(maxEntries: Int = 800): List<String> {
        // The global language epoch has been retired. Text metadata is selected by
        // languageTag in the key, and image cache entries are language-independent.
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
            val root = readPendingEntry(key) ?: return null
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
