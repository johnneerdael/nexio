package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionCacheDiagnostics
import com.nexio.tv.core.artwork.ArtworkDecisionCacheSnapshotDiagnostics
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
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
        profileManager: ProfileManager,
        identityResolver: RailMediaIdentityResolver,
        traceSink: RuntimeTraceSink
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        posterRatingsUrlResolver = posterRatingsUrlResolver,
        artworkDecisionCache = artworkDecisionCache,
        activeProfileId = { profileManager.activeProfileId.value },
        identityResolver = identityResolver,
        traceSink = traceSink
    )

    constructor(
        context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        posterRatingsUrlResolver: PosterRatingsUrlResolver,
        artworkDecisionCache: ArtworkDecisionCache = InMemoryArtworkDecisionCache(),
        traceSink: RuntimeTraceSink = NoopRuntimeTraceSink
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        posterRatingsUrlResolver = posterRatingsUrlResolver,
        artworkDecisionCache = artworkDecisionCache,
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
        private const val LEGACY_INTEGRATION_POSTER_PREFIX = "integration-poster://"
        private const val LOGCAT_ONLY_TRACE_SESSION_ID = "logcat-only"
        private val PREMIUM_PROVIDER_URL_PREFIXES = listOf(
            "https://api.ratingposterdb.com/",
            "https://api.top-posters.com/"
        )
    }

    private val gson = Gson()
    private val traceSequence = AtomicLong(0L)

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
            val raw = prefs.getString(snapshotKey(profileId), null)?.takeIf { it.isNotBlank() }
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
            val sanitized = decoded.sanitize()
            val restored = sanitized.takeIf { it.hasValidPosterProviderTags(requiredPosterProviderTag) }
            traceSnapshot(
                eventType = "home.snapshot_read",
                payload = mapOf(
                    "success" to (restored != null),
                    "profileId" to profileId,
                    "snapshotFound" to true,
                    "catalogRowCount" to sanitized.catalogRows.size,
                    "fullCatalogRowCount" to sanitized.fullCatalogRows.size,
                    "heroItemCount" to sanitized.heroItems.size,
                    "requiredPosterProviderTag" to requiredPosterProviderTag,
                    "reason" to if (restored == null) "poster_provider_tag_mismatch" else null
                )
            )
            restored
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
            val sanitizedSnapshot = snapshot.sanitize()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                addProperty("schemaVersion", SCHEMA_VERSION)
                addProperty("languageEpoch", metadataDiskCacheStore.currentLanguageEpoch())
                addProperty("languageTag", currentLanguageTag())
                addProperty("posterProviderToken", posterProviderToken)
                add("catalogRows", gson.toJsonTree(sanitizedSnapshot.catalogRows))
                add("fullCatalogRows", gson.toJsonTree(sanitizedSnapshot.fullCatalogRows))
                add("heroItems", gson.toJsonTree(sanitizedSnapshot.heroItems))
                add("orderedGroupKeys", gson.toJsonTree(sanitizedSnapshot.orderedGroupKeys))
            }
            val success = prefs.edit().putString(snapshotKey(profileId), gson.toJson(payload)).commit()
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
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(snapshotKey(profileId)).commit()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear home snapshot", error)
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

    private fun Snapshot.hasValidPosterProviderTags(requiredTag: String?): Boolean {
        if (requiredTag == null) return true
        return sequence {
            catalogRows.forEach { row -> yieldAll(row.items) }
            fullCatalogRows.forEach { row -> yieldAll(row.items) }
            yieldAll(heroItems)
        }.all { item ->
            item.posterProviderTag == null || item.posterProviderTag == requiredTag
        }
    }

    private fun Snapshot.sanitize(): Snapshot {
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

        return Snapshot(
            catalogRows = sanitizedCatalogRows,
            fullCatalogRows = sanitizedFullCatalogRows,
            heroItems = sanitizedHeroItems,
            orderedGroupKeys = orderedGroupKeys.distinct()
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
            row.copy(items = sanitizedItems).sanitizedForCache()
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
            item?.sanitizedForCache()?.sanitizePremiumArtworkForSnapshot("$label[$index]", traceState)
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
        val reason = clearReasonForPosterRef(posterRef, decisionLookup?.decisionFound)
        if (posterRef.isBlank() || reason == null) {
            return this
        }
        traceState.recordSanitized(
            scope = scope,
            reason = reason,
            posterKind = posterKind,
            posterProviderTag = posterProviderTag,
            decisionFound = decisionLookup?.decisionFound
        )
        return copy(poster = null, posterProviderTag = null)
    }

    private fun clearReasonForPosterRef(ref: String, decisionFound: Boolean?): String? {
        return when {
            isRawPremiumProviderUrl(ref) -> "raw_premium_url"
            isLegacyIntegrationPosterRef(ref) -> "legacy_integration_ref"
            isMissingDecisionRef(ref, decisionFound) -> "missing_decision"
            else -> null
        }
    }

    private fun isRawPremiumProviderUrl(ref: String): Boolean {
        return PREMIUM_PROVIDER_URL_PREFIXES.any { prefix ->
            ref.startsWith(prefix, ignoreCase = true)
        }
    }

    private fun isLegacyIntegrationPosterRef(ref: String): Boolean {
        return ref.startsWith(LEGACY_INTEGRATION_POSTER_PREFIX, ignoreCase = true)
    }

    private fun isMissingDecisionRef(ref: String, decisionFound: Boolean?): Boolean {
        return isDecisionRef(ref) && decisionFound != true
    }

    private fun isDecisionRef(ref: String): Boolean {
        return ref.startsWith(ARTWORK_DECISION_PREFIX)
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
                decisionKeyHash = null,
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

        var lookupErrorClass: String? = null
        val decisionFound = runCatching {
            artworkDecisionCache.get(ArtworkDecisionKey(keyValue)) != null
        }.onFailure { error ->
            lookupErrorClass = error.javaClass.simpleName
        }.getOrDefault(false)

        return DecisionLookupProof(
            decisionFound = decisionFound,
            decisionKeyHash = keyValue.sha256Short(),
            diagnostics = cacheDiagnostics()
        ).also { proof ->
            traceState.recordDecisionLookup(
                scope = scope,
                posterKind = posterKind,
                posterProviderTag = posterProviderTag,
                proof = proof,
                lookupErrorClass = lookupErrorClass
            )
        }
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
        val decisionKeyHash: String?,
        val diagnostics: ArtworkDecisionCacheSnapshotDiagnostics?
    )

    private inner class SnapshotSanitizeTraceState {
        var decisionLookupCount: Int = 0
            private set
        var decisionFoundCount: Int = 0
            private set
        var missingDecisionCount: Int = 0
            private set
        var lookupErrorCount: Int = 0
            private set
        var sanitizedCount: Int = 0
            private set
        var rawPremiumCount: Int = 0
            private set
        var legacyIntegrationCount: Int = 0
            private set
        private var latestDiagnostics: ArtworkDecisionCacheSnapshotDiagnostics? = null
        private val sanitizedSamples = mutableListOf<String>()
        private val missingDecisionSamples = mutableListOf<String>()

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
            } else {
                missingDecisionCount += 1
                rememberSample(missingDecisionSamples, "$scope:$posterKind:${posterProviderTag.orEmpty()}")
            }
            if (lookupErrorClass != null) {
                lookupErrorCount += 1
            }
        }

        fun recordSanitized(
            scope: String,
            reason: String,
            posterKind: String,
            posterProviderTag: String?,
            decisionFound: Boolean?
        ) {
            sanitizedCount += 1
            when (reason) {
                "raw_premium_url" -> rawPremiumCount += 1
                "legacy_integration_ref" -> legacyIntegrationCount += 1
            }
            rememberSample(sanitizedSamples, "$scope:$reason:$posterKind:${posterProviderTag.orEmpty()}:$decisionFound")
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
                    "lookupErrorCount" to lookupErrorCount,
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
