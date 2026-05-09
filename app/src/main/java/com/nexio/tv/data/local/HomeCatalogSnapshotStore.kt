package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
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
        private const val SCHEMA_VERSION = 4
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
            val raw = readSnapshotJson(profileId)?.takeIf { it.isNotBlank() }
                ?: run {
                    traceSnapshot(
                        eventType = "home.snapshot_read",
                        payload = mapOf(
                            "success" to true,
                            "profileId" to profileId,
                            "snapshotFound" to false,
                            "reason" to "missing_snapshot"
                        )
                    )
                    return null
                }
            val requiredPosterProviderTag = requiredPosterProviderTag(posterProviderToken)
            val decoded = decodeSnapshot(raw, posterProviderToken)
                ?: run {
                    traceSnapshot(
                        eventType = "home.snapshot_read",
                        payload = mapOf(
                            "success" to false,
                            "profileId" to profileId,
                            "snapshotFound" to true,
                            "reason" to "decode_or_policy_rejected"
                        )
                    )
                    return null
                }
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

    private fun readSnapshotJson(profileId: Int): String? {
        val file = snapshotFileFor(profileId)
        if (file.exists()) {
            return runCatching { file.readText() }.getOrNull()
        }
        // One-time migration: legacy SharedPreferences-backed payloads
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacy = prefs.getString(snapshotKey(profileId), null)
        if (legacy != null) {
            // Migrate: write legacy JSON to file, remove the legacy SharedPreferences key.
            runCatching { writeSnapshotJsonToFile(legacy, file) }
                .onFailure { error ->
                    Log.w(TAG, "Failed to migrate legacy snapshot to file", error)
                }
            runCatching {
                prefs.edit().remove(snapshotKey(profileId)).apply()
            }
        }
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
                        writer.name("catalogRows")
                        gson.toJson(snapshot.catalogRows, catalogRowListType, writer)
                        writer.name("fullCatalogRows")
                        gson.toJson(snapshot.fullCatalogRows, catalogRowListType, writer)
                        writer.name("heroItems")
                        gson.toJson(snapshot.heroItems, metaPreviewListType, writer)
                        writer.name("orderedGroupKeys")
                        gson.toJson(snapshot.orderedGroupKeys, stringListType, writer)
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
            orderedGroupKeys = orderedGroupKeys
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
        traceState.emitIfNeeded()

        return SnapshotSanitizeResult(
            snapshot = Snapshot(
                catalogRows = sanitizedCatalogRows,
                fullCatalogRows = sanitizedFullCatalogRows,
                heroItems = sanitizedHeroItems,
                orderedGroupKeys = orderedGroupKeys.distinct()
            ),
            providerTagMismatchExemptPosterRefs = traceState.providerTagMismatchExemptPosterRefs.toSet()
        )
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
