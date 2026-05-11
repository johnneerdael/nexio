package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionCacheDiagnostics
import com.nexio.tv.core.artwork.ArtworkDecisionCacheSnapshotDiagnostics
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDecisionLookupResult
import com.nexio.tv.core.artwork.ArtworkReferenceIntegrityResult
import com.nexio.tv.core.artwork.ArtworkReferenceIntegrityValidator
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.artwork.NoopArtworkReferenceIntegrityValidator
import com.nexio.tv.core.artwork.emptyOrNull
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
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
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeCatalogSnapshotStore private constructor(
    @ApplicationContext private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val artworkDecisionCache: ArtworkDecisionCache,
    private val artworkReferenceIntegrityValidator: ArtworkReferenceIntegrityValidator,
    private val activeProfileId: () -> Int,
    private val identityResolver: RailMediaIdentityResolver,
    private val traceSink: RuntimeTraceSink
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        posterRatingsUrlResolver: PosterRatingsUrlResolver,
        artworkDecisionCache: ArtworkDecisionCache,
        artworkReferenceIntegrityValidator: ArtworkReferenceIntegrityValidator,
        profileManager: ProfileManager,
        identityResolver: RailMediaIdentityResolver,
        traceSink: RuntimeTraceSink
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        posterRatingsUrlResolver = posterRatingsUrlResolver,
        artworkDecisionCache = artworkDecisionCache,
        artworkReferenceIntegrityValidator = artworkReferenceIntegrityValidator,
        activeProfileId = { profileManager.activeProfileId.value },
        identityResolver = identityResolver,
        traceSink = traceSink
    )

    constructor(
        context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        posterRatingsUrlResolver: PosterRatingsUrlResolver,
        artworkDecisionCache: ArtworkDecisionCache = InMemoryArtworkDecisionCache(),
        artworkReferenceIntegrityValidator: ArtworkReferenceIntegrityValidator = NoopArtworkReferenceIntegrityValidator,
        traceSink: RuntimeTraceSink = NoopRuntimeTraceSink
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        posterRatingsUrlResolver = posterRatingsUrlResolver,
        artworkDecisionCache = artworkDecisionCache,
        artworkReferenceIntegrityValidator = artworkReferenceIntegrityValidator,
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
        // (no projector); next write produces v5 which includes both shapes for
        // backward compat. Task 6e (next) will retire the legacy fields and gut
        // the ~920 LOC MetaPreview-content sanitization subsystem that operates
        // on them. Typed item content is persisted separately by
        // [ResolvedDisplaySnapshotStore] (Phase 3.7 narrowed `f705ad049`); the
        // home pipeline reads both stores at cold-start to seed first paint.
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
        private const val ARTWORK_DECISION_PREFIX = "nexio-artwork://decision/"
        private const val ARTWORK_ASSET_PREFIX = "nexio-artwork://asset/"
        private const val ARTWORK_REF_SCHEME = "nexio-artwork:"
        private const val LEGACY_INTEGRATION_POSTER_PREFIX = "integration-poster://"
        private const val LOGCAT_ONLY_TRACE_SESSION_ID = "logcat-only"
        private const val REDACTED_VALIDATOR_REASON = "validator_reason_redacted"
        private val PREMIUM_PROVIDER_HOSTS = setOf(
            "api.ratingposterdb.com",
            "api.top-posters.com"
        )
        private val SAFE_VALIDATOR_REASONS = setOf(
            "missing_authoritative_no_asset",
            "decision_cache_not_authoritative",
            "lookup_failed",
            "invalid_asset_key",
            "invalid_decision_key",
            "invalid_artwork_key_ref",
            "missing_or_unreadable_asset",
            "asset_lookup_failed",
            "asset_read_failed",
            "unsupported_artwork_ref"
        )
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
     * Task 6e will retire the denormalized [catalogRows]/[fullCatalogRows]/
     * [heroItems] fields and the ~920 LOC sanitization subsystem that
     * operates on them; consumers will move to the [rails]/[heroItemKeys]
     * pair plus typed item lookup via
     * [com.nexio.tv.data.local.ResolvedDisplaySnapshotStore] /
     * [com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository].
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
            val requiredPosterProviderTag = requiredPosterProviderTag(posterProviderToken)
            val sanitizeResult = decoded.sanitizeForSnapshot()
            val sanitized = sanitizeResult.snapshot
            val providerTagMismatches = sanitized.posterProviderTagMismatches(
                requiredTag = requiredPosterProviderTag,
                providerTagMismatchExemptPosterRefs = sanitizeResult.providerTagMismatchExemptPosterRefs
            )
            traceIgnoredPosterProviderTagMismatches(
                mismatches = providerTagMismatches,
                requiredTag = requiredPosterProviderTag
            )
            traceSnapshot(
                eventType = "home.snapshot_read",
                payload = mapOf(
                    "success" to true,
                    "profileId" to profileId,
                    "snapshotFound" to true,
                    "catalogRowCount" to sanitized.catalogRows.size,
                    "fullCatalogRowCount" to sanitized.fullCatalogRows.size,
                    "heroItemCount" to sanitized.heroItems.size,
                    "requiredPosterProviderTag" to requiredPosterProviderTag,
                    "ignoredPosterProviderTagMismatchCount" to providerTagMismatches.size,
                    "reason" to null
                )
            )
            sanitized
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
            val sanitizedSnapshot = snapshot.sanitize().repairArtworkWriteInvariants()
            val success = runCatching {
                streamSnapshotToFile(
                    snapshot = sanitizedSnapshot,
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
                    "catalogRowCount" to sanitizedSnapshot.catalogRows.size,
                    "fullCatalogRowCount" to sanitizedSnapshot.fullCatalogRows.size,
                    "heroItemCount" to sanitizedSnapshot.heroItems.size
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
                                // Plan B Task 6d schema v5 persisted these structure-only
                                // fields, but no in-memory consumer has been wired yet
                                // (Task 6e). Skip them to keep returned Snapshot
                                // shape-equivalent to v4's behavior — see comment below.
                                "rails", "heroItemKeys" -> reader.skipValue()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }
            }
            // All gates passed; return the canonical Snapshot. Plan B Task 6d
            // schema v5 introduces persisted `rails` + `heroItemKeys`, but no
            // in-memory consumer has been wired to them yet — Task 6e moves
            // consumers off the denormalized fields and onto these. Until
            // then, drop them on read so the returned Snapshot stays
            // shape-equivalent to v4's behavior and existing equality
            // assertions/tests remain valid. The on-disk JSON still carries
            // them; the writer always re-derives if absent.
            Snapshot(
                catalogRows = catalogRows,
                fullCatalogRows = fullCatalogRows,
                heroItems = heroItems,
                orderedGroupKeys = orderedGroupKeys
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

    private fun requiredPosterProviderTag(posterProviderToken: String): String? {
        val provider = posterProviderToken.substringBefore(':').trim()
        return provider
            .takeIf { it.isNotBlank() && !it.equals("native", ignoreCase = true) }
            ?.lowercase()
    }

    private fun Snapshot.posterProviderTagMismatches(
        requiredTag: String?,
        providerTagMismatchExemptPosterRefs: Set<String> = emptySet()
    ): List<PosterProviderTagMismatch> {
        if (requiredTag == null) return emptyList()
        return buildList {
            catalogRows.forEachIndexed { rowIndex, row ->
                row.items.forEachIndexed { itemIndex, item ->
                    recordPosterProviderTagMismatch(
                        scope = "catalogRows[$rowIndex].items[$itemIndex]",
                        item = item,
                        requiredTag = requiredTag,
                        providerTagMismatchExemptPosterRefs = providerTagMismatchExemptPosterRefs
                    )
                }
            }
            fullCatalogRows.forEachIndexed { rowIndex, row ->
                row.items.forEachIndexed { itemIndex, item ->
                    recordPosterProviderTagMismatch(
                        scope = "fullCatalogRows[$rowIndex].items[$itemIndex]",
                        item = item,
                        requiredTag = requiredTag,
                        providerTagMismatchExemptPosterRefs = providerTagMismatchExemptPosterRefs
                    )
                }
            }
            heroItems.forEachIndexed { itemIndex, item ->
                recordPosterProviderTagMismatch(
                    scope = "heroItems[$itemIndex]",
                    item = item,
                    requiredTag = requiredTag,
                    providerTagMismatchExemptPosterRefs = providerTagMismatchExemptPosterRefs
                )
            }
        }
    }

    private fun MutableList<PosterProviderTagMismatch>.recordPosterProviderTagMismatch(
        scope: String,
        item: MetaPreview,
        requiredTag: String,
        providerTagMismatchExemptPosterRefs: Set<String>
    ) {
        val providerTag = item.posterProviderTag ?: return
        if (providerTag == requiredTag) return
        val posterRef = item.poster?.trim().orEmpty()
        if (posterRef in providerTagMismatchExemptPosterRefs) return
        add(
            PosterProviderTagMismatch(
                scope = scope,
                providerTag = providerTag,
                posterKind = posterKind(posterRef),
                decisionKeyHash = decisionKeyHashForRef(posterRef)
            )
        )
    }

    private fun traceIgnoredPosterProviderTagMismatches(
        mismatches: List<PosterProviderTagMismatch>,
        requiredTag: String?
    ) {
        if (mismatches.isEmpty()) return
        traceSnapshot(
            eventType = "home.snapshot_provider_tag_mismatch_ignored",
            payload = mapOf(
                "scope" to "snapshot",
                "mismatchCount" to mismatches.size,
                "requiredPosterProviderTag" to requiredTag,
                "providerTags" to mismatches.countSummary { it.providerTag },
                "posterKinds" to mismatches.countSummary { it.posterKind }
            )
        )
        mismatches.forEach { mismatch ->
            traceSnapshot(
                eventType = "home.snapshot_artwork_rehydrate_requested",
                payload = mapOf(
                    "scope" to mismatch.scope,
                    "reason" to "poster_provider_tag_mismatch",
                    "posterKind" to mismatch.posterKind,
                    "providerTag" to mismatch.providerTag,
                    "requiredProviderTag" to requiredTag,
                    "decisionKeyHash" to mismatch.decisionKeyHash
                )
            )
        }
    }

    private fun <T> List<T>.countSummary(selector: (T) -> String): String {
        return groupingBy(selector)
            .eachCount()
            .entries
            .sortedBy { it.key }
            .joinToString("|") { (value, count) -> "$value=$count" }
    }

    private fun Snapshot.sanitize(): Snapshot = sanitizeForSnapshot().snapshot

    private fun Snapshot.repairArtworkWriteInvariants(): Snapshot {
        return Snapshot(
            catalogRows = catalogRows.mapIndexed { rowIndex, row ->
                row.copy(
                    items = row.items.mapIndexed { itemIndex, item ->
                        item.repairArtworkWriteInvariant("catalogRows[$rowIndex].items[$itemIndex]")
                    }
                )
            },
            fullCatalogRows = fullCatalogRows.mapIndexed { rowIndex, row ->
                row.copy(
                    items = row.items.mapIndexed { itemIndex, item ->
                        item.repairArtworkWriteInvariant("fullCatalogRows[$rowIndex].items[$itemIndex]")
                    }
                )
            },
            heroItems = heroItems.mapIndexed { itemIndex, item ->
                item.repairArtworkWriteInvariant("heroItems[$itemIndex]")
            },
            orderedGroupKeys = orderedGroupKeys,
            // Plan B Task 6d schema v5: preserve structure-only fields; the
            // sanitization subsystem operates only on the denormalized
            // MetaPreview content, not on opaque keys.
            rails = rails,
            heroItemKeys = heroItemKeys
        )
    }

    private fun MetaPreview.repairArtworkWriteInvariant(scope: String): MetaPreview {
        val withTypeSafeArtwork = if (artwork == null) {
            this
        } else {
            copy(artwork = artwork.enforceArtworkTypeBoundaries().emptyOrNull())
        }
        return withTypeSafeArtwork
            .repairPosterWriteInvariant(scope)
            .repairScalarArtworkWriteInvariant(scope, fieldName = "background", expectedType = ArtworkType.BACKDROP)
            .repairScalarArtworkWriteInvariant(scope, fieldName = "logo", expectedType = ArtworkType.LOGO)
    }

    private fun MetaPreview.repairPosterWriteInvariant(scope: String): MetaPreview {
        val posterRef = poster?.trim().orEmpty()
        val typeRepair = artworkTypeRepairForRef(
            ref = posterRef,
            expectedType = ArtworkType.POSTER,
            clearReasonForUnknownType = null
        )
        if (typeRepair != null) {
            traceWriteBarrierRepair(
                scope = scope,
                fieldName = "poster",
                action = "clear_poster_ref",
                reason = typeRepair.reason,
                decisionKeyHash = decisionKeyHashForRef(posterRef),
                assetKeyHash = assetKeyHashForRef(posterRef),
                destructive = true,
                posterProviderTagAction = "clear"
            )
            return copy(poster = null, posterProviderTag = null)
        }

        val validation = artworkReferenceIntegrityValidator.validate(posterRef)
        return when (validation) {
            ArtworkReferenceIntegrityResult.Empty ->
                if (posterProviderTag == null) this else copy(posterProviderTag = null)

            is ArtworkReferenceIntegrityResult.ValidDecision,
            is ArtworkReferenceIntegrityResult.ValidAsset ->
                this

            is ArtworkReferenceIntegrityResult.RecoverableAssetForDecision -> {
                val assetRef = "$ARTWORK_ASSET_PREFIX${validation.assetKey.value}"
                traceWriteBarrierRepair(
                    scope = scope,
                    fieldName = "poster",
                    action = "replace_decision_with_asset",
                    reason = "recoverable_asset_for_decision",
                    decisionKeyHash = validation.decisionKey.value.sha256Short(),
                    assetKeyHash = validation.assetKey.value.sha256Short(),
                    destructive = false,
                    posterProviderTagAction = "preserve"
                )
                copy(poster = assetRef)
            }

            is ArtworkReferenceIntegrityResult.OrphanedDecisionRef -> {
                val safeReason = validation.reason.safeValidatorTraceReason()
                traceWriteBarrierRepair(
                    scope = scope,
                    fieldName = "poster",
                    action = "preserve_orphaned_decision_ref",
                    reason = safeReason,
                    decisionKeyHash = validation.decisionKey.value.sha256Short(),
                    assetKeyHash = null,
                    destructive = false,
                    posterProviderTagAction = "preserve"
                )
                traceSnapshot(
                    eventType = "home.snapshot_artwork_rehydrate_requested",
                    payload = mapOf(
                        "scope" to scope,
                        "reason" to safeReason,
                        "posterKind" to "decision",
                        "providerTag" to posterProviderTag,
                        "decisionKeyHash" to validation.decisionKey.value.sha256Short(),
                        "lookupResultType" to "orphaned_decision_ref"
                    )
                )
                this
            }

            is ArtworkReferenceIntegrityResult.UnknownDecisionRef -> {
                traceSnapshot(
                    eventType = "home.snapshot_artwork_rehydrate_requested",
                    payload = mapOf(
                        "scope" to scope,
                        "reason" to validation.reason.safeValidatorTraceReason(),
                        "posterKind" to "decision",
                        "providerTag" to posterProviderTag,
                        "decisionKeyHash" to validation.decisionKey.value.sha256Short(),
                        "lookupResultType" to "unknown_decision_ref"
                    )
                )
                this
            }

            is ArtworkReferenceIntegrityResult.Invalid ->
                if (isInvalidArtworkRefClearedAtWrite(posterRef)) {
                    // Validators may flag a freshly-materialized decision as Invalid
                    // before its asset bytes are linked. Before nulling, ask the
                    // decision cache directly: if it still resolves to Found, the ref
                    // is recoverable and we keep it. This prevents the empty-poster
                    // (Trakt) and addon-poster-flicker (TMDB) regressions where a
                    // transient validator miss caused poster=null to be persisted.
                    if (decisionRefStillResolvesInCache(posterRef)) {
                        traceWriteBarrierRepair(
                            scope = scope,
                            fieldName = "poster",
                            action = "preserve_invalid_decision_with_cache_hit",
                            reason = validation.reason.safeValidatorTraceReason(),
                            decisionKeyHash = decisionKeyHashForRef(posterRef),
                            assetKeyHash = assetKeyHashForRef(posterRef),
                            destructive = false,
                            posterProviderTagAction = "preserve"
                        )
                        this
                    } else {
                        traceWriteBarrierRepair(
                            scope = scope,
                            fieldName = "poster",
                            action = "clear_poster_ref",
                            reason = validation.reason.safeValidatorTraceReason(),
                            decisionKeyHash = decisionKeyHashForRef(posterRef),
                            assetKeyHash = assetKeyHashForRef(posterRef),
                            destructive = true,
                            posterProviderTagAction = "clear"
                        )
                        copy(poster = null, posterProviderTag = null)
                    }
                } else {
                    this
                }
        }
    }

    private fun MetaPreview.repairScalarArtworkWriteInvariant(
        scope: String,
        fieldName: String,
        expectedType: ArtworkType
    ): MetaPreview {
        val ref = when (fieldName) {
            "background" -> background
            "logo" -> logo
            else -> null
        }?.trim().orEmpty()
        val typeRepair = artworkTypeRepairForRef(
            ref = ref,
            expectedType = expectedType,
            clearReasonForUnknownType = "invalid_artwork_ref"
        ) ?: return this

        traceWriteBarrierRepair(
            scope = scope,
            fieldName = fieldName,
            action = "clear_${fieldName}_ref",
            reason = typeRepair.reason,
            decisionKeyHash = decisionKeyHashForRef(ref),
            assetKeyHash = assetKeyHashForRef(ref),
            destructive = true,
            posterProviderTagAction = null
        )
        return when (fieldName) {
            "background" -> copy(background = null)
            "logo" -> copy(logo = null)
            else -> this
        }
    }

    private fun artworkTypeRepairForRef(
        ref: String,
        expectedType: ArtworkType,
        clearReasonForUnknownType: String?
    ): ArtworkTypeRepair? {
        if (!isDurableArtworkRef(ref)) return null
        val inferredType = artworkTypeForDurableRef(ref)
        return when {
            inferredType == null && clearReasonForUnknownType != null ->
                ArtworkTypeRepair(clearReasonForUnknownType)
            inferredType != null && inferredType != expectedType ->
                ArtworkTypeRepair("wrong_artwork_type")
            else ->
                null
        }
    }

    private data class ArtworkTypeRepair(val reason: String)

    private fun String.safeValidatorTraceReason(): String =
        if (this in SAFE_VALIDATOR_REASONS) this else REDACTED_VALIDATOR_REASON

    private fun decisionRefStillResolvesInCache(ref: String): Boolean {
        if (!isDecisionRef(ref)) return false
        val keyValue = ref.removePrefix(ARTWORK_DECISION_PREFIX)
            .takeIf { it.isNotBlank() } ?: return false
        val result = runCatching {
            artworkDecisionCache.lookup(ArtworkDecisionKey(keyValue), requiredContext = null)
        }.getOrNull()
        return result is ArtworkDecisionLookupResult.Found
    }

    private fun isInvalidArtworkRefClearedAtWrite(ref: String): Boolean {
        if (ref.isBlank()) return false
        if (!ref.startsWith(ARTWORK_REF_SCHEME)) return false
        if (artworkReferenceIntegrityValidator !is NoopArtworkReferenceIntegrityValidator) return true
        if (!isDecisionRef(ref)) return true

        val decisionKey = ref.removePrefix(ARTWORK_DECISION_PREFIX)
        return decisionKey.isBlank() ||
            "/" in decisionKey ||
            decisionKey.startsWith("artwork-decision:")
    }

    private fun traceWriteBarrierRepair(
        scope: String,
        fieldName: String,
        action: String,
        reason: String,
        decisionKeyHash: String?,
        assetKeyHash: String?,
        destructive: Boolean,
        posterProviderTagAction: String?
    ) {
        val payload = buildMap<String, Any?> {
            put("scope", scope)
            put("field", fieldName)
            put("action", action)
            put("reason", reason)
            put("decisionKeyHash", decisionKeyHash)
            put("assetKeyHash", assetKeyHash)
            put("destructive", destructive)
            if (posterProviderTagAction != null) {
                put("posterProviderTagAction", posterProviderTagAction)
            }
        }
        traceSnapshot(
            eventType = "home.snapshot_write_barrier_repaired",
            payload = payload
        )
    }

    private fun Snapshot.sanitizeForSnapshot(): SnapshotSanitizeResult {
        val traceState = SnapshotSanitizeTraceState()
        val sanitizedCatalogRows = sanitizeCatalogRows(catalogRows as List<*>, "catalogRows", traceState)
        val sanitizedFullCatalogRows = sanitizeCatalogRows(fullCatalogRows as List<*>, "fullCatalogRows", traceState)
        val sanitizedHeroItems = sanitizeMetaPreviews(heroItems as List<*>, "heroItems", traceState)

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
        // Hard caps to keep the persisted snapshot bounded. Without them the file
        // grows monotonically across sessions: each run inflates whatever was
        // written last time and re-persists at least that much (often more, after
        // pagination loads / discovery refreshes), and the next read inflates the
        // bigger file. Heap dump on PID 29380 caught the file at 145.97 MB on disk
        // — 9,260 CatalogRow + 184,907 MetaPreview live instances after load,
        // driving 134 MB/GC AllocSpace churn. The user-facing rails normally show
        // tens of items per rail; the durable cache only needs enough to seed a
        // first paint while the network refresh runs.
        val cappedCatalogRows = sanitizedCatalogRows.capRowsAndItems()
        val cappedFullCatalogRows = sanitizedFullCatalogRows.capRowsAndItems()
        val cappedHeroItems = if (sanitizedHeroItems.size > MAX_HERO_ITEMS) {
            sanitizedHeroItems.subList(0, MAX_HERO_ITEMS)
        } else {
            sanitizedHeroItems
        }
        if (sanitizedCatalogRows.size != cappedCatalogRows.size ||
            sanitizedFullCatalogRows.size != cappedFullCatalogRows.size ||
            sanitizedHeroItems.size != cappedHeroItems.size
        ) {
            Log.w(
                TAG,
                "Capped persisted home snapshot: " +
                    "catalogRows ${sanitizedCatalogRows.size} -> ${cappedCatalogRows.size}, " +
                    "fullCatalogRows ${sanitizedFullCatalogRows.size} -> ${cappedFullCatalogRows.size}, " +
                    "heroItems ${sanitizedHeroItems.size} -> ${cappedHeroItems.size}"
            )
        }
        traceState.emitIfNeeded()

        return SnapshotSanitizeResult(
            snapshot = Snapshot(
                catalogRows = cappedCatalogRows,
                fullCatalogRows = cappedFullCatalogRows,
                heroItems = cappedHeroItems,
                orderedGroupKeys = orderedGroupKeys.distinct(),
                // Plan B Task 6d schema v5: preserve structure-only fields if
                // already populated upstream. The persisted write path also
                // derives them from the legacy fields when empty, so callers
                // that have not been updated to populate these still produce
                // a valid v5 snapshot on disk.
                rails = rails,
                heroItemKeys = heroItemKeys
            ),
            providerTagMismatchExemptPosterRefs = traceState.providerTagMismatchExemptPosterRefs.toSet()
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

    private fun sanitizeCatalogRows(
        values: List<*>,
        label: String,
        traceState: SnapshotSanitizeTraceState
    ): List<CatalogRow> {
        return values.mapIndexedNotNull { index, value ->
            val row = value as? CatalogRow
            if (row == null) {
                Log.w(TAG, "Dropping malformed cached $label[$index]: ${value?.javaClass?.name}")
                return@mapIndexedNotNull null
            }

            val sanitizedItems = sanitizeMetaPreviews(row.items as List<*>, "$label[$index].items", traceState)
            if (sanitizedItems.size != (row.items as List<*>).size) {
                Log.w(
                    TAG,
                    "Dropping malformed cached items from $label[$index] for catalogId=${row.catalogId}"
                )
            }
            row.copy(items = sanitizedItems)
        }
    }

    private fun sanitizeMetaPreviews(
        values: List<*>,
        label: String,
        traceState: SnapshotSanitizeTraceState
    ): List<MetaPreview> {
        return values.mapIndexedNotNull { index, value ->
            val item = value as? MetaPreview
            if (item == null) {
                Log.w(TAG, "Dropping malformed cached $label[$index]: ${value?.javaClass?.name}")
            }
            item
                ?.sanitizedForSnapshot()
                ?.sanitizePremiumArtworkForSnapshot("$label[$index]", traceState)
        }
    }

    private fun MetaPreview.sanitizedForSnapshot(): MetaPreview {
        val sanitized = sanitizedForCache()
        val originalPoster = poster?.trim()?.takeIf { it.isNotBlank() }
        return if (
            originalPoster != null &&
            sanitized.poster == originalPoster &&
            sanitized.posterProviderTag == null &&
            posterProviderTag != null
        ) {
            sanitized.copy(posterProviderTag = posterProviderTag)
        } else {
            sanitized
        }
    }

    private fun MetaPreview.sanitizePremiumArtworkForSnapshot(
        scope: String,
        traceState: SnapshotSanitizeTraceState
    ): MetaPreview {
        val posterRef = poster?.trim().orEmpty()
        val posterKind = posterKind(posterRef)
        val decisionLookup = if (isDecisionRef(posterRef)) {
            lookupDurableDecision(
                ref = posterRef,
                scope = scope,
                posterKind = posterKind,
                posterProviderTag = posterProviderTag,
                traceState = traceState
            )
        } else {
            null
        }
        if (decisionLookup?.preserveNonAuthoritativeRef == true) {
            traceState.recordProviderTagMismatchExemptPosterRef(posterRef)
        }
        val reason = clearReasonForPosterRef(posterRef, decisionLookup?.clearMissingDecisionRef)
        if (posterRef.isBlank() || reason == null) {
            return this
        }
        traceState.recordSanitized(
            scope = scope,
            reason = reason,
            posterKind = posterKind,
            posterProviderTag = posterProviderTag,
            lookupResultType = decisionLookup?.lookupResultType
        )
        return copy(poster = null, posterProviderTag = null)
    }

    private fun clearReasonForPosterRef(ref: String, clearMissingDecisionRef: Boolean?): String? {
        return when {
            isRawPremiumProviderUrl(ref) -> "raw_premium_url"
            isLegacyIntegrationPosterRef(ref) -> "legacy_integration_ref"
            isMissingDecisionRef(ref, clearMissingDecisionRef) -> "missing_decision"
            else -> null
        }
    }

    private fun isRawPremiumProviderUrl(ref: String): Boolean {
        val uri = runCatching { URI(ref.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return host in PREMIUM_PROVIDER_HOSTS
    }

    private fun isLegacyIntegrationPosterRef(ref: String): Boolean {
        return ref.startsWith(LEGACY_INTEGRATION_POSTER_PREFIX, ignoreCase = true)
    }

    private fun isMissingDecisionRef(ref: String, clearMissingDecisionRef: Boolean?): Boolean {
        return isDecisionRef(ref) && clearMissingDecisionRef == true
    }

    private fun isDecisionRef(ref: String): Boolean {
        return ref.startsWith(ARTWORK_DECISION_PREFIX)
    }

    private fun isAssetRef(ref: String): Boolean {
        return ref.startsWith(ARTWORK_ASSET_PREFIX)
    }

    private fun isDurableArtworkRef(ref: String): Boolean {
        return isDecisionRef(ref) || isAssetRef(ref)
    }

    private fun artworkTypeForDurableRef(ref: String): ArtworkType? {
        val key = when {
            isDecisionRef(ref) -> ref.removePrefix(ARTWORK_DECISION_PREFIX)
            isAssetRef(ref) -> ref.removePrefix(ARTWORK_ASSET_PREFIX)
            else -> return null
        }
        val parts = key.split(":")
        val typeValue = when (parts.firstOrNull()) {
            "artwork-decision" -> parts.getOrNull(1)
            "artwork-asset" -> parts.getOrNull(2)
            else -> null
        } ?: return null
        return ArtworkType.entries.firstOrNull { type ->
            type.name.equals(typeValue, ignoreCase = true)
        }
    }

    private fun decisionKeyHashForRef(ref: String): String? {
        return ref
            .takeIf { isDecisionRef(it) }
            ?.removePrefix(ARTWORK_DECISION_PREFIX)
            ?.takeIf { it.isNotBlank() }
            ?.sha256Short()
    }

    private fun assetKeyHashForRef(ref: String): String? {
        return ref
            .takeIf { isAssetRef(it) }
            ?.removePrefix(ARTWORK_ASSET_PREFIX)
            ?.takeIf { it.isNotBlank() }
            ?.sha256Short()
    }

    private fun posterKind(ref: String): String {
        return when {
            isDecisionRef(ref) -> "decision"
            isRawPremiumProviderUrl(ref) -> "raw_premium"
            isLegacyIntegrationPosterRef(ref) -> "legacy_integration"
            ref.startsWith("http://", ignoreCase = true) || ref.startsWith("https://", ignoreCase = true) -> "remote"
            else -> "other"
        }
    }

    private fun lookupDurableDecision(
        ref: String,
        scope: String,
        posterKind: String,
        posterProviderTag: String?,
        traceState: SnapshotSanitizeTraceState
    ): DecisionLookupProof {
        val keyValue = ref.removePrefix(ARTWORK_DECISION_PREFIX)
            .takeIf { it.isNotBlank() }
            ?: return DecisionLookupProof(
                decisionFound = false,
                clearMissingDecisionRef = true,
                preserveNonAuthoritativeRef = false,
                decisionKeyHash = null,
                lookupResultType = "blank_decision_key",
                diagnostics = cacheDiagnostics()
            ).also { proof ->
                traceState.recordDecisionLookup(
                    scope = scope,
                    posterKind = posterKind,
                    posterProviderTag = posterProviderTag,
                    proof = proof,
                    lookupErrorClass = "BlankDecisionKey"
                )
            }

        val decisionKey = ArtworkDecisionKey(keyValue)
        val decisionKeyHash = keyValue.sha256Short()
        val lookupResult = runCatching {
            artworkDecisionCache.lookup(decisionKey, requiredContext = null)
        }.getOrElse { error ->
            ArtworkDecisionLookupResult.LookupFailed(
                decisionKey = decisionKey,
                errorClass = error.javaClass.simpleName,
                messageHash = error.message?.sha256Short()
            )
        }
        val lookupErrorClass = when (lookupResult) {
            is ArtworkDecisionLookupResult.CacheNotAuthoritative -> lookupResult.errorClass
            is ArtworkDecisionLookupResult.LookupFailed -> lookupResult.errorClass
            else -> null
        }
        val rehydrateReason = when (lookupResult) {
            is ArtworkDecisionLookupResult.MissingAuthoritative -> "missing_decision_authoritative"
            is ArtworkDecisionLookupResult.CacheNotAuthoritative -> "decision_cache_not_authoritative"
            is ArtworkDecisionLookupResult.LookupFailed -> "lookup_failed"
            else -> null
        }

        return DecisionLookupProof(
            decisionFound = lookupResult is ArtworkDecisionLookupResult.Found,
            clearMissingDecisionRef = false,
            preserveNonAuthoritativeRef = lookupResult is ArtworkDecisionLookupResult.CacheNotAuthoritative ||
                lookupResult is ArtworkDecisionLookupResult.LookupFailed,
            decisionKeyHash = decisionKeyHash,
            lookupResultType = lookupResult.lookupResultType(),
            diagnostics = cacheDiagnostics()
        ).also { proof ->
            traceState.recordDecisionLookup(
                scope = scope,
                posterKind = posterKind,
                posterProviderTag = posterProviderTag,
                proof = proof,
                lookupErrorClass = lookupErrorClass
            )
            if (rehydrateReason != null) {
                traceState.recordRehydrateRequest()
                traceSnapshot(
                    eventType = "home.snapshot_artwork_rehydrate_requested",
                    payload = mapOf(
                        "scope" to scope,
                        "reason" to rehydrateReason,
                        "posterKind" to posterKind,
                        "providerTag" to posterProviderTag,
                        "decisionKeyHash" to decisionKeyHash,
                        "lookupResultType" to proof.lookupResultType
                    )
                )
            }
        }
    }

    private fun ArtworkDecisionLookupResult.lookupResultType(): String =
        when (this) {
            is ArtworkDecisionLookupResult.Found -> "found"
            is ArtworkDecisionLookupResult.MissingAuthoritative -> "missing_authoritative"
            is ArtworkDecisionLookupResult.CacheNotAuthoritative -> "cache_not_authoritative"
            is ArtworkDecisionLookupResult.LookupFailed -> "lookup_failed"
        }

    private fun cacheDiagnostics(): ArtworkDecisionCacheSnapshotDiagnostics? =
        (artworkDecisionCache as? ArtworkDecisionCacheDiagnostics)?.snapshotDiagnostics()

    private fun String.sha256Short(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }.take(16)
    }

    private data class DecisionLookupProof(
        val decisionFound: Boolean,
        val clearMissingDecisionRef: Boolean,
        val preserveNonAuthoritativeRef: Boolean,
        val decisionKeyHash: String?,
        val lookupResultType: String,
        val diagnostics: ArtworkDecisionCacheSnapshotDiagnostics?
    )

    private data class SnapshotSanitizeResult(
        val snapshot: Snapshot,
        val providerTagMismatchExemptPosterRefs: Set<String>
    )

    private data class PosterProviderTagMismatch(
        val scope: String,
        val providerTag: String,
        val posterKind: String,
        val decisionKeyHash: String?
    )

    private inner class SnapshotSanitizeTraceState {
        var decisionLookupCount: Int = 0
            private set
        var decisionFoundCount: Int = 0
            private set
        var missingDecisionCount: Int = 0
            private set
        var cacheNotAuthoritativeCount: Int = 0
            private set
        var lookupFailedCount: Int = 0
            private set
        var lookupErrorCount: Int = 0
            private set
        var sanitizedCount: Int = 0
            private set
        var rawPremiumCount: Int = 0
            private set
        var legacyIntegrationCount: Int = 0
            private set
        var rehydrateRequestCount: Int = 0
            private set
        private var latestDiagnostics: ArtworkDecisionCacheSnapshotDiagnostics? = null
        private val mutableProviderTagMismatchExemptPosterRefs = mutableSetOf<String>()
        val providerTagMismatchExemptPosterRefs: Set<String>
            get() = mutableProviderTagMismatchExemptPosterRefs
        private val sanitizedSamples = mutableListOf<String>()
        private val missingDecisionSamples = mutableListOf<String>()
        private val sanitizeReasonCounts = linkedMapOf<String, Int>()

        fun recordProviderTagMismatchExemptPosterRef(ref: String) {
            mutableProviderTagMismatchExemptPosterRefs += ref
        }

        fun recordDecisionLookup(
            scope: String,
            posterKind: String,
            posterProviderTag: String?,
            proof: DecisionLookupProof,
            lookupErrorClass: String?
        ) {
            decisionLookupCount += 1
            latestDiagnostics = proof.diagnostics
            if (proof.decisionFound) {
                decisionFoundCount += 1
            } else if (proof.lookupResultType == "missing_authoritative") {
                missingDecisionCount += 1
                rememberSample(missingDecisionSamples, "$scope:$posterKind:${posterProviderTag.orEmpty()}")
            }
            if (proof.lookupResultType == "cache_not_authoritative") {
                cacheNotAuthoritativeCount += 1
            }
            if (proof.lookupResultType == "lookup_failed") {
                lookupFailedCount += 1
            }
            if (lookupErrorClass != null) {
                lookupErrorCount += 1
            }
        }

        fun recordRehydrateRequest() {
            rehydrateRequestCount += 1
        }

        fun recordSanitized(
            scope: String,
            reason: String,
            posterKind: String,
            posterProviderTag: String?,
            lookupResultType: String?
        ) {
            sanitizedCount += 1
            when (reason) {
                "raw_premium_url" -> rawPremiumCount += 1
                "legacy_integration_ref" -> legacyIntegrationCount += 1
            }
            sanitizeReasonCounts[reason] = (sanitizeReasonCounts[reason] ?: 0) + 1
            rememberSample(sanitizedSamples, "$scope:$reason:$posterKind:${posterProviderTag.orEmpty()}:$lookupResultType")
        }

        fun emitIfNeeded() {
            if (decisionLookupCount == 0 && sanitizedCount == 0) return
            val diagnostics = latestDiagnostics
            traceSnapshot(
                eventType = "home.snapshot_decision_lookup",
                payload = mapOf(
                    "scope" to "snapshot",
                    "decisionLookupCount" to decisionLookupCount,
                    "decisionFoundCount" to decisionFoundCount,
                    "missingDecisionCount" to missingDecisionCount,
                    "cacheNotAuthoritativeCount" to cacheNotAuthoritativeCount,
                    "lookupFailedCount" to lookupFailedCount,
                    "lookupErrorCount" to lookupErrorCount,
                    "lookupResultTypes" to listOf(
                        "found=$decisionFoundCount",
                        "missing_authoritative=$missingDecisionCount",
                        "cache_not_authoritative=$cacheNotAuthoritativeCount",
                        "lookup_failed=$lookupFailedCount"
                    ).joinToString("|"),
                    "cacheLoaded" to diagnostics?.loaded,
                    "cacheDecisionCount" to diagnostics?.decisionCount,
                    "cacheLinkCount" to diagnostics?.linkCount,
                    "storeFilePresent" to diagnostics?.storeFilePresent,
                    "storeFileReadable" to diagnostics?.storeFileReadable,
                    "storeFileBytes" to diagnostics?.storeFileBytes,
                    "lastLoadSuccess" to diagnostics?.lastLoadSuccess,
                    "lastLoadReason" to diagnostics?.lastLoadReason,
                    "lastLoadErrorClass" to diagnostics?.lastLoadErrorClass,
                    "droppedDecisionCount" to diagnostics?.droppedDecisionCount,
                    "authoritative" to diagnostics?.authoritative,
                    "loadState" to diagnostics?.loadStateName,
                    "quarantinedDecisionCount" to diagnostics?.quarantinedDecisionCount,
                    "errorTopFrame" to diagnostics?.errorTopFrame,
                    "rehydrateRequestCount" to rehydrateRequestCount,
                    "missingDecisionSamples" to missingDecisionSamples.joinToString("|")
                )
            )
            if (sanitizedCount > 0) {
                traceSnapshot(
                    eventType = "home.snapshot_sanitize_artwork",
                    payload = mapOf(
                        "scope" to "snapshot",
                        "sanitizedCount" to sanitizedCount,
                        "rawPremiumCount" to rawPremiumCount,
                        "legacyIntegrationCount" to legacyIntegrationCount,
                        "missingDecisionCount" to missingDecisionCount,
                        "action" to "clear_poster_ref",
                        "reasons" to sanitizeReasonCounts.entries.joinToString("|") { (reason, count) -> "$reason=$count" },
                        "destructive" to true,
                        "writeBackAllowed" to false,
                        "posterProviderTagAction" to "clear",
                        "samples" to sanitizedSamples.joinToString("|")
                    )
                )
            }
        }

        private fun rememberSample(samples: MutableList<String>, sample: String) {
            if (samples.size < 5) {
                samples += sample
            }
        }
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
