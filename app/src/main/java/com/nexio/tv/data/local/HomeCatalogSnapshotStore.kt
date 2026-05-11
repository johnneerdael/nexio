package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.integration.RailMembership
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.integration.RailCacheEntity
import com.nexio.tv.data.local.integration.RailItemEntity
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.Rail
import com.nexio.tv.domain.model.RailItemKey
import com.nexio.tv.domain.model.toRail
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeCatalogSnapshotStore private constructor(
    @ApplicationContext private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val activeProfileId: () -> Int,
    private val identityResolver: RailMediaIdentityResolver,
    private val traceSink: RuntimeTraceSink
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        posterRatingsUrlResolver: PosterRatingsUrlResolver,
        profileManager: ProfileManager,
        identityResolver: RailMediaIdentityResolver,
        traceSink: RuntimeTraceSink
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        posterRatingsUrlResolver = posterRatingsUrlResolver,
        activeProfileId = { profileManager.activeProfileId.value },
        identityResolver = identityResolver,
        traceSink = traceSink
    )

    constructor(
        context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        posterRatingsUrlResolver: PosterRatingsUrlResolver,
        traceSink: RuntimeTraceSink = NoopRuntimeTraceSink
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        posterRatingsUrlResolver = posterRatingsUrlResolver,
        activeProfileId = { 1 },
        identityResolver = RailMediaIdentityResolver(),
        traceSink = traceSink
    )

    companion object {
        private const val TAG = "HomeCatalogSnapshot"
        private const val PREFS_NAME = "home_catalog_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
        // SCHEMA_VERSION bumped 4 → 5 (Plan B Task 6d) to introduce structure-only
        // persisted fields [Snapshot.rails] + [Snapshot.heroItemKeys] alongside the
        // legacy denormalized [Snapshot.catalogRows]/[Snapshot.heroItems]/
        // [Snapshot.fullCatalogRows] fields. v4 snapshots are discarded on read
        // (no projector); v5 writes carry both shapes for backward compat. Plan B
        // Task 6e gutted the ~920 LOC MetaPreview-content sanitization subsystem
        // (no schema bump needed — the on-disk shape didn't change). Typed item
        // content is persisted separately by [ResolvedDisplaySnapshotStore]
        // (Phase 3.7 narrowed `f705ad049`); the home pipeline reads both stores
        // at cold-start to seed first paint.
        private const val SCHEMA_VERSION = 5
        // Persisted-snapshot bounds. The on-disk JSON is restored at startup as a
        // first-paint cache; the network refresh that follows replaces it. The
        // cache only needs enough rows/items to seed an initial paint, not the
        // entire pagination history. Without bounds, the file grew unbounded
        // across sessions (observed: 145.97 MB on disk after a few sessions,
        // 9,260 CatalogRow + 184,907 MetaPreview live after load, 134 MB/GC churn).
        private const val MAX_PERSISTED_CATALOG_ROWS = 200
        private const val MAX_PERSISTED_ITEMS_PER_ROW = 100
        private const val MAX_HERO_ITEMS = 50
        private const val LOGCAT_ONLY_TRACE_SESSION_ID = "logcat-only"
    }

    private val gson = Gson()
    private val traceSequence = AtomicLong(0L)

    private val catalogRowListType = object : TypeToken<List<CatalogRow>>() {}.type
    private val metaPreviewListType = object : TypeToken<List<MetaPreview>>() {}.type
    private val stringListType = object : TypeToken<List<String>>() {}.type
    private val railListType = object : TypeToken<List<Rail>>() {}.type
    private val railItemKeyListType = object : TypeToken<List<RailItemKey>>() {}.type

    suspend fun currentPosterProviderToken(): String {
        val provider = posterRatingsUrlResolver.getActiveProvider() ?: return "native"
        return "${provider.provider.name}:${provider.apiKey.hashCode()}"
    }

    /**
     * In-memory + persisted snapshot.
     *
     * Plan B Task 6d (schema v5) added the structure-only [rails] and
     * [heroItemKeys] fields. They are populated automatically by [write]
     * (derived from [catalogRows]/[fullCatalogRows] and [heroItems]) and
     * round-trip through the persisted JSON. They have empty defaults so
     * existing in-memory constructor call sites stay source-compatible.
     *
     * Plan B Task 6e gutted the ~920 LOC MetaPreview-content sanitization
     * subsystem that previously ran on every read/write: premium-URL clearing,
     * provider-tag mismatch detection, decision/asset ref repair, artwork-type
     * boundary enforcement. The typed authority
     * ([com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository]) is now
     * the sole owner of MetaPreview content correctness upstream of write.
     * The legacy denormalized [catalogRows]/[fullCatalogRows]/[heroItems]
     * fields remain on this data class as transition support for pipeline
     * filter helpers and the search corpus; a follow-up task will retire them
     * once consumers consume rails + typed surface directly. Persistence still
     * applies bounded caps via [capForPersist] so the on-disk file cannot grow
     * unbounded across sessions.
     */
    data class Snapshot(
        val catalogRows: List<CatalogRow>,
        val fullCatalogRows: List<CatalogRow>,
        val heroItems: List<MetaPreview>,
        val orderedGroupKeys: List<String> = emptyList(),
        val rails: List<Rail> = emptyList(),
        val heroItemKeys: List<RailItemKey> = emptyList()
    )

    fun read(
        posterProviderToken: String,
        profileId: Int = activeProfileId()
    ): Snapshot? {
        return runCatching {
            val file = snapshotFileFor(profileId)
            // Streaming read directly off the file, never materializing the whole
            // payload as a String or JsonObject tree (CLAUDE.md hard rule #3 read
            // clause). Heap dumps showed `file.readText()` + `gson.fromJson(raw,
            // JsonObject::class)` cost a 109 MB transient char[] *plus* a ~100 MB
            // JsonObject tree per cold-start when the snapshot grew with Plan A's
            // larger catalogRows.
            val decoded = if (file.exists()) {
                streamReadSnapshot(file, posterProviderToken)
            } else {
                // One-time legacy migration path: small SharedPreferences-stored payload.
                // The legacy data is at most a few hundred KB (pre-Plan A schema), so
                // the small-allocation decode is acceptable here. After migration
                // returns, future reads will hit the streaming path.
                migrateLegacySnapshotToFile(profileId, file)?.let { migratedRaw ->
                    decodeSnapshot(migratedRaw, posterProviderToken)
                }
            } ?: run {
                val snapshotFound = file.exists()
                traceSnapshot(
                    eventType = "home.snapshot_read",
                    payload = mapOf(
                        "success" to !snapshotFound,
                        "profileId" to profileId,
                        "snapshotFound" to snapshotFound,
                        "reason" to if (snapshotFound) "decode_or_policy_rejected" else "missing_snapshot"
                    )
                )
                return null
            }
            // Plan B Task 6e: MetaPreview-content sanitization subsystem removed.
            // The typed authority ([ResolvedDisplaySurfaceRepository]) is now the
            // sole owner of MetaPreview content correctness — premium URLs, provider
            // tags, decision/asset ref repairs, and poster-type invariants are all
            // enforced upstream of write. The home snapshot persists structure-only
            // [rails] + [heroItemKeys] (schema v5, Task 6d) and the legacy
            // denormalized fields ride along for transition consumers; on read we
            // simply return what was written.
            traceSnapshot(
                eventType = "home.snapshot_read",
                payload = mapOf(
                    "success" to true,
                    "profileId" to profileId,
                    "snapshotFound" to true,
                    "catalogRowCount" to decoded.catalogRows.size,
                    "fullCatalogRowCount" to decoded.fullCatalogRows.size,
                    "heroItemCount" to decoded.heroItems.size,
                    "reason" to null
                )
            )
            decoded
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore home snapshot", error)
            traceSnapshot(
                eventType = "home.snapshot_read",
                payload = mapOf(
                    "success" to false,
                    "profileId" to profileId,
                    "snapshotFound" to true,
                    "reason" to "exception",
                    "errorClass" to error.javaClass.simpleName
                )
            )
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
            // Plan B Task 6e: MetaPreview-content sanitization subsystem removed.
            // The typed authority ([ResolvedDisplaySurfaceRepository]) enforces
            // content correctness upstream of write; the persisted snapshot only
            // needs structure-only [rails] + [heroItemKeys] (schema v5) plus
            // bounded denormalized fields as transition support. Apply hard caps
            // inline so the on-disk file cannot grow unbounded across sessions.
            val capped = snapshot.capForPersist()
            val success = runCatching {
                streamSnapshotToFile(
                    snapshot = capped,
                    schemaVersion = SCHEMA_VERSION,
                    languageEpoch = metadataDiskCacheStore.currentLanguageEpoch(),
                    languageTag = currentLanguageTag(),
                    posterProviderToken = posterProviderToken,
                    target = snapshotFileFor(profileId)
                )
                true
            }.getOrElse { error ->
                Log.w(TAG, "Failed to persist home snapshot to file", error)
                false
            }
            traceSnapshot(
                eventType = "home.snapshot_write",
                payload = mapOf(
                    "success" to success,
                    "profileId" to profileId,
                    "catalogRowCount" to capped.catalogRows.size,
                    "fullCatalogRowCount" to capped.fullCatalogRows.size,
                    "heroItemCount" to capped.heroItems.size
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist home snapshot", error)
            traceSnapshot(
                eventType = "home.snapshot_write",
                payload = mapOf(
                    "success" to false,
                    "profileId" to profileId,
                    "errorClass" to error.javaClass.simpleName
                )
            )
        }
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            val target = snapshotFileFor(profileId)
            if (target.exists()) {
                target.delete()
            }
            // Also remove the legacy SharedPreferences key if present (cleanup)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(snapshotKey(profileId))) {
                prefs.edit().remove(snapshotKey(profileId)).apply()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear home snapshot", error)
        }
    }

    /**
     * Streams the snapshot JSON file directly via [JsonReader] over a [BufferedReader]
     * over a [FileInputStream]. Validates schemaVersion / languageTag /
     * posterProviderToken on the fly so a mismatch can short-circuit before the
     * expensive list parses ever start.
     *
     * Replaces the previous `file.readText()` + `gson.fromJson(raw,
     * JsonObject::class)` path that materialized the full snapshot as a 109 MB
     * `String` *plus* a comparable JsonObject tree per cold-start (heap dump
     * `RootJavaFrame`-pinned char[] of `{"schemaVersion":4,...}`).
     */
    private fun streamReadSnapshot(file: File, posterProviderToken: String): Snapshot? {
        val expectedLanguageTag = currentLanguageTag()
        var schemaVersion: Int = -1
        var languageTag: String? = null
        var cachedPosterToken: String? = null
        var catalogRows: List<CatalogRow> = emptyList()
        var fullCatalogRows: List<CatalogRow> = emptyList()
        var heroItems: List<MetaPreview> = emptyList()
        var orderedGroupKeys: List<String> = emptyList()
        var rails: List<Rail> = emptyList()
        var heroItemKeys: List<RailItemKey> = emptyList()

        return runCatching {
            FileInputStream(file).use { fis ->
                BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                    JsonReader(br).use { reader ->
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            return@runCatching null
                        }
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "schemaVersion" -> {
                                    schemaVersion = reader.nextInt()
                                    if (schemaVersion != SCHEMA_VERSION) return@runCatching null
                                }
                                "languageTag" -> {
                                    languageTag = reader.nextString().trim()
                                    if (languageTag.isNullOrBlank() ||
                                        languageTag != expectedLanguageTag
                                    ) {
                                        return@runCatching null
                                    }
                                }
                                "posterProviderToken" -> {
                                    cachedPosterToken = reader.nextString().trim()
                                    if (cachedPosterToken != posterProviderToken) {
                                        Log.d(
                                            TAG,
                                            "Poster provider changed " +
                                                "($cachedPosterToken -> $posterProviderToken), " +
                                                "invalidating snapshot"
                                        )
                                        return@runCatching null
                                    }
                                }
                                "catalogRows" -> {
                                    catalogRows = gson.fromJson<List<CatalogRow>>(reader, catalogRowListType)
                                        ?: emptyList()
                                }
                                "fullCatalogRows" -> {
                                    fullCatalogRows = gson.fromJson<List<CatalogRow>>(reader, catalogRowListType)
                                        ?: emptyList()
                                }
                                "heroItems" -> {
                                    heroItems = gson.fromJson<List<MetaPreview>>(reader, metaPreviewListType)
                                        ?: emptyList()
                                }
                                "orderedGroupKeys" -> {
                                    orderedGroupKeys = gson.fromJson<List<String>>(reader, stringListType)
                                        ?: emptyList()
                                }
                                "rails" -> {
                                    rails = gson.fromJson<List<Rail>>(reader, railListType)
                                        ?: emptyList()
                                }
                                "heroItemKeys" -> {
                                    heroItemKeys = gson.fromJson<List<RailItemKey>>(reader, railItemKeyListType)
                                        ?: emptyList()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }
            }
            Snapshot(
                catalogRows = catalogRows,
                fullCatalogRows = fullCatalogRows,
                heroItems = heroItems,
                orderedGroupKeys = orderedGroupKeys,
                rails = rails,
                heroItemKeys = heroItemKeys
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to stream-read home snapshot from file", error)
        }.getOrNull()
    }

    /**
     * Migrates a legacy SharedPreferences-stored snapshot blob (pre-file backed
     * Plan-A path) to the new file-backed format and returns the legacy JSON for
     * the caller to decode. Only invoked when [snapshotFileFor] does not exist.
     * Legacy payloads are at most a few hundred KB (pre-Plan A schema), so the
     * small-allocation decode in [decodeSnapshot] is acceptable for them.
     */
    private fun migrateLegacySnapshotToFile(profileId: Int, file: File): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacy = prefs.getString(snapshotKey(profileId), null) ?: return null
        runCatching { writeSnapshotJsonToFile(legacy, file) }
            .onFailure { error ->
                Log.w(TAG, "Failed to migrate legacy snapshot to file", error)
            }
        runCatching { prefs.edit().remove(snapshotKey(profileId)).apply() }
        return legacy
    }

    private fun snapshotFileFor(profileId: Int): File {
        val parent = File(context.filesDir, "home-catalog-snapshot-v1")
        if (!parent.exists()) parent.mkdirs()
        // Mirror the legacy SharedPreferences key shape: profile + language tag, so
        // distinct app languages keep distinct snapshots without overwriting each other.
        val sanitizedTag = currentLanguageTag()
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .ifBlank { "unknown" }
        return File(parent, "p${profileId}_${sanitizedTag}.json")
    }

    private fun streamSnapshotToFile(
        snapshot: Snapshot,
        schemaVersion: Int,
        languageEpoch: Int,
        languageTag: String,
        posterProviderToken: String,
        target: File
    ) {
        var tempFile: File? = null
        try {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            tempFile = File(parent ?: File("."), "${target.name}.tmp")

            // Plan B Task 6d schema v5: persist structure-only [rails] +
            // [heroItemKeys] alongside the legacy denormalized fields. If the
            // in-memory Snapshot was built by a producer that has not yet been
            // updated to populate these (default to empty), derive them from
            // the legacy fields so the on-disk shape is always complete.
            val railsForPersist: List<Rail> = snapshot.rails.ifEmpty {
                // Mirror buildRailMemberships' row dedupe (fullCatalogRows wins; catalogRows fills gaps).
                val merged = linkedMapOf<String, CatalogRow>()
                for (i in snapshot.fullCatalogRows.indices) {
                    val row = snapshot.fullCatalogRows[i]
                    merged[row.catalogId] = row
                }
                for (i in snapshot.catalogRows.indices) {
                    val row = snapshot.catalogRows[i]
                    merged.putIfAbsent(row.catalogId, row)
                }
                val out = ArrayList<Rail>(merged.size)
                for (row in merged.values) out += row.toRail()
                out
            }
            val heroItemKeysForPersist: List<RailItemKey> = snapshot.heroItemKeys.ifEmpty {
                val out = ArrayList<RailItemKey>(snapshot.heroItems.size)
                for (i in snapshot.heroItems.indices) {
                    val item = snapshot.heroItems[i]
                    out += RailItemKey(apiType = item.apiType, contentId = item.id)
                }
                out
            }
            FileOutputStream(tempFile).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        writer.name("schemaVersion").value(schemaVersion)
                        writer.name("languageEpoch").value(languageEpoch)
                        writer.name("languageTag").value(languageTag)
                        writer.name("posterProviderToken").value(posterProviderToken)
                        writer.name("catalogRows")
                        gson.toJson(snapshot.catalogRows, catalogRowListType, writer)
                        writer.name("fullCatalogRows")
                        gson.toJson(snapshot.fullCatalogRows, catalogRowListType, writer)
                        writer.name("heroItems")
                        gson.toJson(snapshot.heroItems, metaPreviewListType, writer)
                        writer.name("orderedGroupKeys")
                        gson.toJson(snapshot.orderedGroupKeys, stringListType, writer)
                        // Plan B Task 6d schema v5: structure-only fields.
                        writer.name("rails")
                        gson.toJson(railsForPersist, railListType, writer)
                        writer.name("heroItemKeys")
                        gson.toJson(heroItemKeysForPersist, railItemKeyListType, writer)
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
        } catch (error: Exception) {
            tempFile?.delete()
            throw error
        }
    }

    private fun writeSnapshotJsonToFile(json: String, target: File) {
        var tempFile: File? = null
        try {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            tempFile = File(parent ?: File("."), "${target.name}.tmp")
            tempFile.writeText(json)
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
        } catch (error: Exception) {
            tempFile?.delete()
            throw error
        }
    }

    internal fun buildRailMemberships(
        snapshot: Snapshot,
        posterProviderToken: String,
        profileId: Int
    ): List<RailMembership> {
        val now = System.currentTimeMillis()
        val rows = linkedMapOf<String, CatalogRow>().apply {
            snapshot.fullCatalogRows.forEach { put(it.catalogId, it) }
            snapshot.catalogRows.forEach { putIfAbsent(it.catalogId, it) }
        }.values.toList()

        return rows.map { row ->
            val railKey = RailKeyFactory.homeCatalog(profileId, row.catalogId)
            val resolvedItems = row.items.map { item ->
                identityResolver.fromPreview(item, updatedAtEpochMs = now)
            }
            RailMembership(
                rail = RailCacheEntity(
                    railKey = railKey,
                    provider = row.catalogId.substringBefore(':').uppercase(),
                    kind = row.type.name,
                    paramsHash = "$posterProviderToken:${currentLanguageTag()}",
                    fetchedAtEpochMs = now,
                    expiresAtEpochMs = now + 30_000L,
                    staleUntilEpochMs = now + 3_600_000L
                ),
                items = resolvedItems.mapIndexed { index, resolved ->
                    RailItemEntity(
                        key = "$railKey#${resolved.mediaIdentity.mediaKey}",
                        railKey = railKey,
                        mediaKey = resolved.mediaIdentity.mediaKey,
                        position = index,
                        updatedAtEpochMs = now
                    )
                },
                mediaIdentities = resolvedItems.map { it.mediaIdentity },
                externalIds = resolvedItems.flatMap { it.externalIds }
            )
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
        // Plan B Task 6d schema v5 includes persisted `rails` + `heroItemKeys`
        // on disk, but no in-memory consumer has been wired yet (Task 6e). Drop
        // them on read here too — see streamReadSnapshot for the same rationale.
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
    /**
     * Plan B Task 6e: applies the hard caps that previously lived inside the
     * sanitization subsystem ([List<CatalogRow>.capRowsAndItems] + [MAX_HERO_ITEMS]
     * subList) directly during write. Keeps the on-disk file bounded across
     * sessions without re-introducing MetaPreview-content sanitization. The
     * structure-only [Snapshot.rails] + [Snapshot.heroItemKeys] pass through
     * unchanged; the legacy denormalized fields are only bounded.
     */
    private fun Snapshot.capForPersist(): Snapshot {
        val cappedCatalogRows = catalogRows.capRowsAndItems()
        val cappedFullCatalogRows = fullCatalogRows.capRowsAndItems()
        val cappedHeroItems = if (heroItems.size > MAX_HERO_ITEMS) {
            heroItems.subList(0, MAX_HERO_ITEMS)
        } else {
            heroItems
        }
        if (
            cappedCatalogRows === catalogRows &&
            cappedFullCatalogRows === fullCatalogRows &&
            cappedHeroItems === heroItems
        ) {
            return this
        }
        Log.w(
            TAG,
            "Capped persisted home snapshot: " +
                "catalogRows ${catalogRows.size} -> ${cappedCatalogRows.size}, " +
                "fullCatalogRows ${fullCatalogRows.size} -> ${cappedFullCatalogRows.size}, " +
                "heroItems ${heroItems.size} -> ${cappedHeroItems.size}"
        )
        return copy(
            catalogRows = cappedCatalogRows,
            fullCatalogRows = cappedFullCatalogRows,
            heroItems = cappedHeroItems
        )
    }

    private fun List<CatalogRow>.capRowsAndItems(): List<CatalogRow> {
        val capped = if (size > MAX_PERSISTED_CATALOG_ROWS) subList(0, MAX_PERSISTED_CATALOG_ROWS) else this
        // Avoid allocating a new list / new CatalogRow if no row exceeds the item cap.
        var anyItemCapped = false
        for (i in capped.indices) {
            if (capped[i].items.size > MAX_PERSISTED_ITEMS_PER_ROW) { anyItemCapped = true; break }
        }
        if (!anyItemCapped) return capped
        val out = ArrayList<CatalogRow>(capped.size)
        for (i in capped.indices) {
            val row = capped[i]
            out += if (row.items.size > MAX_PERSISTED_ITEMS_PER_ROW) {
                row.copy(items = row.items.subList(0, MAX_PERSISTED_ITEMS_PER_ROW))
            } else {
                row
            }
        }
        return out
    }


    private fun traceSnapshot(
        eventType: String,
        payload: Map<String, Any?>
    ) {
        traceSink.emit(
            TraceEventEnvelope(
                traceSessionId = traceSink.activeTraceSessionId() ?: LOGCAT_ONLY_TRACE_SESSION_ID,
                sequence = traceSequence.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = eventType,
                payload = payload
            )
        )
    }
}
