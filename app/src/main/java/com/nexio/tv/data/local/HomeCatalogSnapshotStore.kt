package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
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
import com.nexio.tv.domain.model.Rail
import com.nexio.tv.domain.model.RailItemKey
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

    private val stringListType = object : TypeToken<List<String>>() {}.type
    private val railListType = object : TypeToken<List<Rail>>() {}.type
    private val railItemKeyListType = object : TypeToken<List<RailItemKey>>() {}.type

    suspend fun currentPosterProviderToken(): String {
        val provider = posterRatingsUrlResolver.getActiveProvider() ?: return "native"
        return "${provider.provider.name}:${provider.apiKey.hashCode()}"
    }

    /**
     * In-memory + persisted snapshot — structure-only.
     *
     * Plan B Task 6f.5 phase 3 dropped the legacy denormalized
     * `catalogRows`/`fullCatalogRows`/`heroItems` fields. The structure-only
     * [rails] + [heroItemKeys] are now the sole representation; row item
     * content is reconstructed on-demand by consumers via the typed authority
     * ([com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository]) +
     * snapshot content lookup at apply time.
     *
     * Persistence still applies a bounded cap via [capForPersist] so the
     * on-disk file cannot grow unbounded across sessions.
     */
    data class Snapshot(
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
                    "railCount" to decoded.rails.size,
                    "heroItemKeyCount" to decoded.heroItemKeys.size,
                    "orderedGroupKeyCount" to decoded.orderedGroupKeys.size,
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
                    "railCount" to capped.rails.size,
                    "heroItemKeyCount" to capped.heroItemKeys.size,
                    "orderedGroupKeyCount" to capped.orderedGroupKeys.size
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
                                // Legacy schema (<=v5 from pre-Plan B 6f.5 writers) persisted these
                                // denormalized fields. Skip — rails + heroItemKeys (derived from them
                                // during the v4->v5 writer migration) are authoritative on disk now.
                                "catalogRows", "fullCatalogRows", "heroItems" -> reader.skipValue()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }
            }
            Snapshot(
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

            FileOutputStream(tempFile).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        writer.name("schemaVersion").value(schemaVersion)
                        writer.name("languageEpoch").value(languageEpoch)
                        writer.name("languageTag").value(languageTag)
                        writer.name("posterProviderToken").value(posterProviderToken)
                        writer.name("orderedGroupKeys")
                        gson.toJson(snapshot.orderedGroupKeys, stringListType, writer)
                        writer.name("rails")
                        gson.toJson(snapshot.rails, railListType, writer)
                        writer.name("heroItemKeys")
                        gson.toJson(snapshot.heroItemKeys, railItemKeyListType, writer)
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
        // Plan B Task 6f.5: rails are the authoritative structure now. Items
        // carry only [RailItemKey] (apiType + contentId); title/year fuzzy
        // hints are no longer available on this code path. Identity resolution
        // falls back to the raw-content path which is sufficient for the
        // rail-membership ownership index (consumed by
        // [integrationOwnershipService.syncRails]).
        return snapshot.rails.map { rail ->
            val railKey = RailKeyFactory.homeCatalog(profileId, rail.catalogId)
            val resolvedItems = rail.items.map { itemKey ->
                identityResolver.fromRawContent(
                    mediaType = itemKey.apiType,
                    rawId = itemKey.contentId,
                    title = null,
                    year = null,
                    updatedAtEpochMs = now
                )
            }
            RailMembership(
                rail = RailCacheEntity(
                    railKey = railKey,
                    provider = rail.catalogId.substringBefore(':').uppercase(),
                    kind = rail.type.name,
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
        // Plan B Task 6f.5 — the legacy SharedPreferences payload pre-dates the
        // rails representation. Rather than back-derive rails+heroItemKeys here
        // (which would require reading catalogRows/fullCatalogRows/heroItems we
        // no longer model), return null to force a fresh catalog fetch. Migration
        // is one-shot and the user perceives at most one extra refresh.
        return null
    }

    private fun currentLanguageTag(): String {
        return AppLocaleResolver.resolveEffectiveAppLanguageTag(context)
    }

    private fun snapshotKey(profileId: Int = activeProfileId()): String {
        return "$SNAPSHOT_KEY:p$profileId:${currentLanguageTag()}"
    }
    /**
     * Plan B Task 6f.5: applies the hard caps directly during write so the
     * on-disk file cannot grow unbounded across sessions. Now operates on the
     * structure-only [Snapshot.rails] + [Snapshot.heroItemKeys].
     */
    private fun Snapshot.capForPersist(): Snapshot {
        val cappedRails = rails.capRailsAndItems()
        val cappedHeroItemKeys = if (heroItemKeys.size > MAX_HERO_ITEMS) {
            heroItemKeys.subList(0, MAX_HERO_ITEMS)
        } else {
            heroItemKeys
        }
        if (
            cappedRails === rails &&
            cappedHeroItemKeys === heroItemKeys
        ) {
            return this
        }
        Log.w(
            TAG,
            "Capped persisted home snapshot: " +
                "rails ${rails.size} -> ${cappedRails.size}, " +
                "heroItemKeys ${heroItemKeys.size} -> ${cappedHeroItemKeys.size}"
        )
        return copy(
            rails = cappedRails,
            heroItemKeys = cappedHeroItemKeys
        )
    }

    private fun List<Rail>.capRailsAndItems(): List<Rail> {
        val capped = if (size > MAX_PERSISTED_CATALOG_ROWS) subList(0, MAX_PERSISTED_CATALOG_ROWS) else this
        // Avoid allocating a new list / new Rail if no rail exceeds the item cap.
        var anyItemCapped = false
        for (i in capped.indices) {
            if (capped[i].items.size > MAX_PERSISTED_ITEMS_PER_ROW) { anyItemCapped = true; break }
        }
        if (!anyItemCapped) return capped
        val out = ArrayList<Rail>(capped.size)
        for (i in capped.indices) {
            val rail = capped[i]
            out += if (rail.items.size > MAX_PERSISTED_ITEMS_PER_ROW) {
                rail.copy(items = rail.items.subList(0, MAX_PERSISTED_ITEMS_PER_ROW))
            } else {
                rail
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
