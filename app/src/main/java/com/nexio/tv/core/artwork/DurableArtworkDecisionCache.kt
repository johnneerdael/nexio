package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DurableArtworkDecisionCache(
    private val file: File,
    private val gson: Gson,
    private val traceSink: RuntimeTraceSink = NoopRuntimeTraceSink,
    private val thumbnailWriteDebounceMs: Long = 0L
) : ArtworkDecisionCache, ArtworkDecisionCacheDiagnostics {
    private val lock = Any()
    private var loaded = false
    private var currentLoadState: ArtworkDecisionStoreLoadState = ArtworkDecisionStoreLoadState.NotLoaded
    private val decisions = linkedMapOf<ArtworkDecisionKey, ArtworkDecision>()
    private val previewToCanonical = linkedMapOf<ArtworkDecisionKey, ArtworkDecisionKey>()
    private val traceSequence = AtomicLong(0L)
    private val flushExecutor: ScheduledExecutorService? =
        if (thumbnailWriteDebounceMs > 0L) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ArtworkDecisionCacheFlush").apply { isDaemon = true }
            }
        } else {
            null
        }
    private var pendingFlush: ScheduledFuture<*>? = null
    private var dirty = false
    private var lastLoadSuccess: Boolean? = null
    private var lastLoadReason: String? = null
    private var lastLoadErrorClass: String? = null
    private var lastDroppedDecisionCount: Int? = null
    private var lastQuarantinedDecisionCount: Int? = null
    private var lastStoredSchemaVersion: Int? = null
    private var lastLoadErrorMessageHash: String? = null
    private var lastLoadErrorTopFrame: String? = null
    private var firstQuarantinedDecisionKeyHash: String? = null
    private val authorityContext: ArtworkDecisionAuthorityContext
        get() = ArtworkDecisionAuthorityContext(
            storeIdHash = artworkDecisionShortSha256(file.absolutePath),
            schemaVersion = SCHEMA_VERSION,
            providerPolicyHash = null,
            settingsHash = null,
            credentialHash = null,
            imageLanguage = null
        )

    override fun get(key: ArtworkDecisionKey): ArtworkDecision? = synchronized(lock) {
        ensureLoadedLocked()
        decisions[key]
    }

    override fun lookup(
        key: ArtworkDecisionKey,
        requiredContext: ArtworkDecisionAuthorityContext?
    ): ArtworkDecisionLookupResult = synchronized(lock) {
        runCatching {
            ensureLoadedLocked()
            decisions[key]?.let(ArtworkDecisionLookupResult::Found) ?: if (
                currentLoadState.isAuthoritativeForMissing(requiredContext)
            ) {
                ArtworkDecisionLookupResult.MissingAuthoritative(
                    decisionKey = key,
                    loadState = currentLoadState as ArtworkDecisionStoreLoadState.LoadedAuthoritative
                )
            } else {
                ArtworkDecisionLookupResult.CacheNotAuthoritative(
                    decisionKey = key,
                    loadState = currentLoadState,
                    reason = currentLoadState.nonAuthoritativeReason(requiredContext),
                    errorClass = (currentLoadState as? ArtworkDecisionStoreLoadState.FailedNonAuthoritative)
                        ?.errorClass
                )
            }
        }.getOrElse { error ->
            ArtworkDecisionLookupResult.LookupFailed(
                decisionKey = key,
                errorClass = error.javaClass.simpleName,
                messageHash = error.message?.let(::artworkDecisionShortSha256)
            )
        }
    }

    override fun loadState(): ArtworkDecisionStoreLoadState = synchronized(lock) {
        ensureLoadedLocked()
        currentLoadState
    }

    override fun put(decision: ArtworkDecision) = synchronized(lock) {
        ensureLoadedLocked()
        val existing = decisions[decision.decisionKey]
        if (existing != null && existing.hasSameDurablePayload(decision)) {
            return@synchronized
        }
        decisions[decision.decisionKey] = decision
        refreshLoadedStateAfterMutationLocked()
        traceArtwork(
            eventType = "artwork.decision_put",
            payload = mapOf(
                "decisionKey" to decision.decisionKey.value,
                "provider" to decision.selectedCandidate.provider?.key,
                "imageType" to decision.imageType.name,
                "sourceRole" to decision.selectedCandidate.sourceRole.name,
                "rejectedCount" to decision.rejectedCandidates.size,
                "hasFallbackCandidate" to decision.rejectedCandidates.any { candidate ->
                    candidate.provider != null &&
                        candidate.sourceRole in FALLBACK_SOURCE_ROLES
                }
            )
        )
        schedulePersistLocked()
    }

    override fun remove(key: ArtworkDecisionKey) = synchronized(lock) {
        ensureLoadedLocked()
        decisions.remove(key)
        removeLinksForLocked(setOf(key))
        refreshLoadedStateAfterMutationLocked()
        schedulePersistLocked()
    }

    override fun linkPreviewToCanonical(
        previewKey: ArtworkDecisionKey,
        canonicalKey: ArtworkDecisionKey
    ) = synchronized(lock) {
        ensureLoadedLocked()
        previewToCanonical[previewKey] = canonicalKey
        refreshLoadedStateAfterMutationLocked()
        schedulePersistLocked()
    }

    override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = synchronized(lock) {
        ensureLoadedLocked()
        previewToCanonical[previewKey]?.let(decisions::get)
    }

    override fun invalidateBySettingsHash(settingsHash: String) = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision -> decision.settingsHash == settingsHash }
    }

    override fun invalidateByCredentialHash(credentialHash: String) = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision -> decision.credentialHash == credentialHash }
    }

    override fun invalidateArtworkPolicy(
        settingsHashes: Set<String>,
        credentialHashes: Set<String>
    ) = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision ->
            decision.settingsHash in settingsHashes || decision.credentialHash in credentialHashes
        }
    }

    override fun invalidatePremiumArtworkPolicy() = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision ->
            decision.settingsHash != null || decision.credentialHash != null
        }
    }

    override fun snapshotDiagnostics(): ArtworkDecisionCacheSnapshotDiagnostics = synchronized(lock) {
        val fileStats = currentFileStats()
        ArtworkDecisionCacheSnapshotDiagnostics(
            loaded = loaded,
            decisionCount = decisions.size,
            linkCount = previewToCanonical.size,
            storeFilePresent = fileStats.present,
            storeFileReadable = fileStats.readable,
            storeFileBytes = fileStats.bytes,
            lastLoadSuccess = lastLoadSuccess,
            lastLoadReason = lastLoadReason,
            lastLoadErrorClass = lastLoadErrorClass,
            droppedDecisionCount = lastDroppedDecisionCount,
            loadStateName = currentLoadState.nameForDiagnostics(),
            authoritative = currentLoadState.isAuthoritativeForMissing(),
            schemaVersion = SCHEMA_VERSION,
            storedSchemaVersion = lastStoredSchemaVersion,
            quarantinedDecisionCount = lastQuarantinedDecisionCount,
            errorMessageHash = lastLoadErrorMessageHash,
            errorTopFrame = lastLoadErrorTopFrame,
            firstQuarantinedDecisionKeyHash = firstQuarantinedDecisionKeyHash,
            authorityContext = authorityContext
        )
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        currentLoadState = ArtworkDecisionStoreLoadState.Loading
        if (!file.isFile) {
            val loadState = loadedAuthoritativeState(droppedDecisionCount = 0, quarantinedDecisionCount = 0)
            traceDecisionStoreLoad(
                success = true,
                authoritative = true,
                loadState = loadState,
                decisionCount = 0,
                linkCount = 0,
                droppedDecisionCount = 0,
                quarantinedDecisionCount = 0,
                filePresent = false
            )
            return
        }

        runCatching {
            val raw = file.readText()
            if (raw.isBlank()) {
                val loadState = loadedAuthoritativeState(droppedDecisionCount = 0, quarantinedDecisionCount = 0)
                traceDecisionStoreLoad(
                    success = true,
                    authoritative = true,
                    loadState = loadState,
                    decisionCount = 0,
                    linkCount = 0,
                    droppedDecisionCount = 0,
                    quarantinedDecisionCount = 0,
                    filePresent = true
                )
                return
            }
            val storeJson = JsonParser.parseString(raw).asJsonObject
            val storedSchemaVersion = storeJson.intOrNull("schemaVersion")
            val legacyObfuscatedStore = storedSchemaVersion == null && storeJson.has("a")
            if (storedSchemaVersion == null) {
                if (!legacyObfuscatedStore) {
                    val loadState = failedNonAuthoritativeState(errorClass = "MissingSchemaVersion")
                    traceDecisionStoreLoad(
                        success = false,
                        authoritative = false,
                        loadState = loadState,
                        decisionCount = 0,
                        linkCount = 0,
                        droppedDecisionCount = 0,
                        quarantinedDecisionCount = 0,
                        filePresent = true,
                        reason = "missing_schema_version"
                    )
                    return
                }
                lastStoredSchemaVersion = SCHEMA_VERSION
            } else {
                lastStoredSchemaVersion = storedSchemaVersion
            }
            val decisionElements =
                if (legacyObfuscatedStore) {
                    storeJson.requiredArray("a")
                } else {
                    storeJson.requiredArray("decisions")
                }
            if (storedSchemaVersion != null && storedSchemaVersion != SCHEMA_VERSION) {
                val droppedDecisionCount = decisionElements.size()
                val loadState = failedNonAuthoritativeState(errorClass = "SchemaVersionMismatch")
                traceDecisionStoreLoad(
                    success = false,
                    authoritative = false,
                    loadState = loadState,
                    decisionCount = 0,
                    linkCount = 0,
                    droppedDecisionCount = droppedDecisionCount,
                    quarantinedDecisionCount = droppedDecisionCount,
                    filePresent = true,
                    reason = "schema_version_mismatch",
                    storedSchemaVersion = storedSchemaVersion
                )
                return
            }

            var droppedDecisionCount = 0
            var quarantinedDecisionCount = 0
            decisionElements.forEach { decisionElement ->
                val restored = runCatching {
                    decisionElement.asJsonObjectOrNull()?.toDecisionDomainOrNull()
                }.getOrNull()
                if (restored == null) {
                    droppedDecisionCount += 1
                    quarantinedDecisionCount += 1
                    if (firstQuarantinedDecisionKeyHash == null) {
                        firstQuarantinedDecisionKeyHash = decisionElement.safeDecisionKeyHash()
                    }
                } else {
                    decisions[restored.decisionKey] = restored
                }
            }
            val previewLinkElements =
                if (legacyObfuscatedStore) {
                    storeJson.requiredArray("b")
                } else {
                    storeJson.requiredArray("previewLinks")
                }
            previewLinkElements.forEach { linkElement ->
                runCatching {
                    val link = requireNotNull(linkElement.asJsonObjectOrNull())
                    previewToCanonical[ArtworkDecisionKey(requireNotNull(link.stringOrNull("previewKey", "a")))] =
                        ArtworkDecisionKey(requireNotNull(link.stringOrNull("canonicalKey", "b")))
                }.onFailure {
                    droppedDecisionCount += 1
                    quarantinedDecisionCount += 1
                    if (firstQuarantinedDecisionKeyHash == null) {
                        firstQuarantinedDecisionKeyHash = linkElement.safePreviewLinkKeyHash()
                    }
                }
            }
            val loadState =
                if (droppedDecisionCount == 0 && quarantinedDecisionCount == 0) {
                    loadedAuthoritativeState(droppedDecisionCount, quarantinedDecisionCount)
                } else {
                    loadedPartialNonAuthoritativeState(droppedDecisionCount, quarantinedDecisionCount)
                }
            traceDecisionStoreLoad(
                success = true,
                authoritative = loadState.isAuthoritativeForMissing(),
                loadState = loadState,
                decisionCount = decisions.size,
                linkCount = previewToCanonical.size,
                droppedDecisionCount = droppedDecisionCount,
                quarantinedDecisionCount = quarantinedDecisionCount,
                filePresent = true
            )
        }.onFailure { error ->
            decisions.clear()
            previewToCanonical.clear()
            lastLoadErrorMessageHash = error.message?.let(::artworkDecisionShortSha256)
            lastLoadErrorTopFrame = error.stackTrace.firstOrNull()?.toTopFrameString()
            val loadState = failedNonAuthoritativeState(errorClass = error.javaClass.simpleName)
            traceDecisionStoreLoad(
                success = false,
                authoritative = false,
                loadState = loadState,
                decisionCount = 0,
                linkCount = 0,
                droppedDecisionCount = 0,
                quarantinedDecisionCount = 0,
                filePresent = true,
                reason = "exception",
                errorClass = error.javaClass.simpleName,
                errorMessageHash = lastLoadErrorMessageHash,
                errorTopFrame = lastLoadErrorTopFrame
            )
        }
    }

    private fun invalidateMatchingLocked(matches: (ArtworkDecision) -> Boolean) {
        val deletedKeys = decisions.values
            .filter(matches)
            .mapTo(mutableSetOf()) { decision -> decision.decisionKey }
        if (deletedKeys.isEmpty()) return

        deletedKeys.forEach(decisions::remove)
        removeLinksForLocked(deletedKeys)
        refreshLoadedStateAfterMutationLocked()
        schedulePersistLocked()
    }

    private fun removeLinksForLocked(keys: Set<ArtworkDecisionKey>) {
        val links = previewToCanonical.iterator()
        while (links.hasNext()) {
            val (previewKey, canonicalKey) = links.next()
            if (previewKey in keys || canonicalKey in keys) {
                links.remove()
            }
        }
    }

    private fun refreshLoadedStateAfterMutationLocked() {
        currentLoadState = when (val state = currentLoadState) {
            is ArtworkDecisionStoreLoadState.LoadedAuthoritative ->
                loadedAuthoritativeState(
                    droppedDecisionCount = state.droppedDecisionCount,
                    quarantinedDecisionCount = state.quarantinedDecisionCount
                )
            is ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative ->
                loadedPartialNonAuthoritativeState(
                    droppedDecisionCount = state.droppedDecisionCount,
                    quarantinedDecisionCount = state.quarantinedDecisionCount
                )
            else -> state
        }
    }

    internal fun flushPendingWritesForTest() = synchronized(lock) {
        flushPendingWritesLocked()
    }

    /**
     * Coalesces every mutation (`put`, `remove`, `linkPreviewToCanonical`,
     * `invalidate*`) onto the same debounced flush path so multiple in-flight
     * writes share a single `toStoreJson()` build and disk write per debounce
     * window. When debounce is disabled (e.g. tests with
     * `thumbnailWriteDebounceMs = 0L`) the executor is null and we fall back
     * to a synchronous flush, preserving immediate-write semantics for tests.
     */
    private fun schedulePersistLocked() {
        if (thumbnailWriteDebounceMs > 0L) {
            scheduleThumbnailPersistLocked()
        } else {
            // Test/unconfigured path: no debounce, flush synchronously so callers
            // (and tests using the default `thumbnailWriteDebounceMs = 0L` ctor)
            // observe writes immediately. `flushPendingWritesLocked` is a no-op
            // unless `dirty` is set, so mark dirty first.
            dirty = true
            flushPendingWritesLocked()
        }
    }

    private fun scheduleThumbnailPersistLocked() {
        dirty = true
        val currentFlush = pendingFlush
        if (currentFlush != null && !currentFlush.isDone && !currentFlush.isCancelled) return

        val executor = flushExecutor
        if (executor == null) {
            flushPendingWritesLocked()
            return
        }

        pendingFlush = executor.schedule(
            ::executeScheduledFlush,
            thumbnailWriteDebounceMs,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * Executes a debounced flush from the [flushExecutor] without holding [lock] during
     * disk IO. The map lock is acquired only long enough to snapshot the in-memory state
     * to JSON and clear the dirty flag; the actual file write happens lock-free.
     *
     * The single-threaded scheduled executor naturally serializes disk writes, so no
     * separate flush lock is needed. This eliminates the
     * `Long monitor contention with owner ArtworkDecisionCacheFlush` warnings observed
     * during home startup, where the previous implementation held [lock] across disk IO
     * and blocked main-thread readers (`get`, `lookup`, `loadState`).
     */
    private fun executeScheduledFlush() {
        val storeJson = synchronized(lock) {
            pendingFlush = null
            if (!dirty) return
            dirty = false
            toStoreJson()
        }
        persistJsonToFile(storeJson)
    }

    private fun flushPendingWritesLocked() {
        pendingFlush?.cancel(false)
        pendingFlush = null
        if (!dirty) return
        dirty = false
        persistLocked()
    }

    private fun persistLocked() {
        persistJsonToFile(toStoreJson())
    }

    /**
     * Writes the supplied store JSON to disk atomically. Callable from any thread; does
     * not require [lock]. The synchronous put/remove paths still hold [lock] when they
     * invoke this, but the debounced flush path captures the JSON under the lock and
     * then calls this lock-free.
     */
    private fun persistJsonToFile(storeJson: JsonObject) {
        var tempFile: File? = null
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()

            tempFile = File(parent ?: File("."), "${file.name}.tmp")
            tempFile.writeText(gson.toJson(storeJson))
            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            traceDecisionStoreWrite(success = true)
        } catch (error: Exception) {
            tempFile?.delete()
            traceDecisionStoreWrite(
                success = false,
                errorClass = error.javaClass.simpleName
            )
        }
    }

    private fun traceDecisionStoreLoad(
        success: Boolean,
        authoritative: Boolean,
        loadState: ArtworkDecisionStoreLoadState,
        decisionCount: Int,
        linkCount: Int,
        droppedDecisionCount: Int,
        quarantinedDecisionCount: Int,
        filePresent: Boolean,
        reason: String? = null,
        storedSchemaVersion: Int? = lastStoredSchemaVersion,
        errorClass: String? = null,
        errorMessageHash: String? = lastLoadErrorMessageHash,
        errorTopFrame: String? = lastLoadErrorTopFrame
    ) {
        val fileStats = currentFileStats()
        currentLoadState = loadState
        lastLoadSuccess = success
        lastLoadReason = reason
        lastLoadErrorClass = errorClass
        lastDroppedDecisionCount = droppedDecisionCount
        lastQuarantinedDecisionCount = quarantinedDecisionCount
        traceArtwork(
            eventType = "artwork.decision_store_load",
            payload = mapOf(
                "success" to success,
                "authoritative" to authoritative,
                "loadState" to loadState.nameForDiagnostics(),
                "filePresent" to filePresent,
                "fileReadable" to fileStats.readable,
                "fileBytes" to fileStats.bytes,
                "decisionCount" to decisionCount,
                "linkCount" to linkCount,
                "droppedDecisionCount" to droppedDecisionCount,
                "quarantinedDecisionCount" to quarantinedDecisionCount,
                "reason" to reason,
                "schemaVersion" to SCHEMA_VERSION,
                "storedSchemaVersion" to storedSchemaVersion,
                "errorClass" to errorClass,
                "errorMessageHash" to errorMessageHash,
                "errorTopFrame" to errorTopFrame,
                "firstQuarantinedDecisionKeyHash" to firstQuarantinedDecisionKeyHash
            )
        )
    }

    private fun currentFileStats(): FileStats {
        val present = file.exists()
        return FileStats(
            present = present,
            readable = present && file.canRead(),
            bytes = if (present && file.isFile) file.length() else null
        )
    }

    private data class FileStats(
        val present: Boolean,
        val readable: Boolean,
        val bytes: Long?
    )

    private fun JsonObject.arrayOrEmpty(name: String, legacyName: String? = null): JsonArray =
        elementOrNull(name, legacyName)
            ?.takeUnless { it.isJsonNull }
            ?.asJsonArray
            ?: JsonArray()

    private fun JsonObject.requiredArray(name: String): JsonArray {
        val element = requireNotNull(get(name)) { "Missing required array $name" }
        require(!element.isJsonNull) { "Null required array $name" }
        return element.asJsonArray
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.elementOrNull(name: String, legacyName: String? = null): JsonElement? =
        get(name) ?: legacyName?.let(::get)

    private fun JsonObject.stringOrNull(name: String, legacyName: String? = null): String? =
        runCatching {
            elementOrNull(name, legacyName)
                ?.takeUnless { it is JsonNull || it.isJsonNull }
                ?.asString
        }.getOrNull()

    private fun JsonObject.intOrNull(name: String, legacyName: String? = null): Int? =
        runCatching {
            elementOrNull(name, legacyName)
                ?.takeUnless { it is JsonNull || it.isJsonNull }
                ?.asInt
        }.getOrNull()

    private fun JsonObject.longOrNull(name: String, legacyName: String? = null): Long? =
        runCatching {
            elementOrNull(name, legacyName)
                ?.takeUnless { it is JsonNull || it.isJsonNull }
                ?.asLong
        }.getOrNull()

    private fun JsonObject.objectOrNull(name: String, legacyName: String? = null): JsonObject? =
        runCatching {
            elementOrNull(name, legacyName)?.asJsonObjectOrNull()
        }.getOrNull()

    private fun JsonObject.toDecisionDomainOrNull(): ArtworkDecision? = runCatching {
        ArtworkDecision(
            decisionKey = ArtworkDecisionKey(requireNotNull(stringOrNull("decisionKey", "a"))),
            ownerKey = requireNotNull(objectOrNull("owner", "b")).toOwnerDomain(),
            canonicalContentId = stringOrNull("canonicalContentId", "c"),
            imageType = ArtworkType.valueOf(requireNotNull(stringOrNull("imageType", "d"))),
            selectedCandidate = requireNotNull(objectOrNull("selectedCandidate", "e")).toCandidateDomain(),
            rejectedCandidates = arrayOrEmpty("rejectedCandidates", "f").map { element ->
                requireNotNull(element.asJsonObjectOrNull()?.toRejectedDomainOrNull())
            },
            policyVersion = requireNotNull(intOrNull("policyVersion", "g")),
            imageLanguage = requireNotNull(stringOrNull("imageLanguage", "h")),
            settingsHash = stringOrNull("settingsHash", "i"),
            credentialHash = stringOrNull("credentialHash", "j"),
            createdAtMs = requireNotNull(longOrNull("createdAtMs", "k")),
            expiresAtMs = requireNotNull(longOrNull("expiresAtMs", "l")),
            staleUntilMs = longOrNull("staleUntilMs", "m")
        )
    }.getOrNull()

    private fun JsonObject.toOwnerDomain(): ArtworkOwnerKey = when (val type = requireNotNull(stringOrNull("type", "a"))) {
        "canonical" -> ArtworkOwnerKey.CanonicalContent(requireNotNull(stringOrNull("contentId", "b")))
        "preview" -> ArtworkOwnerKey.PreviewItem(
            itemKey = requireNotNull(stringOrNull("itemKey", "c")),
            sourcePayloadHash = requireNotNull(stringOrNull("sourcePayloadHash", "d"))
        )
        else -> error("Unknown owner type $type")
    }

    private fun JsonObject.toCandidateDomain(): PersistedArtworkCandidate =
        PersistedArtworkCandidate(
            provider = objectOrNull("provider", "a")?.toProviderDomain(),
            sourceRole = ArtworkSourceRole.valueOf(requireNotNull(stringOrNull("sourceRole", "b"))),
            sourceHash = stringOrNull("sourceHash", "c"),
            redactedSourceForTrace = stringOrNull("redactedSourceForTrace", "d"),
            providerTemplate = objectOrNull("providerTemplate", "e")?.toTemplateDomain(),
            priority = requireNotNull(intOrNull("priority", "f"))
        )

    private fun JsonObject.toRejectedDomainOrNull(): RejectedArtworkCandidate? = runCatching {
        RejectedArtworkCandidate(
            provider = objectOrNull("provider", "a")?.toProviderDomain(),
            sourceRole = ArtworkSourceRole.valueOf(requireNotNull(stringOrNull("sourceRole", "b"))),
            reason = requireNotNull(stringOrNull("reason", "c")),
            sourceHash = stringOrNull("sourceHash", "d"),
            redactedSourceForTrace = stringOrNull("redactedSourceForTrace", "e"),
            providerTemplate = objectOrNull("providerTemplate", "f")?.toTemplateDomain(),
            priority = requireNotNull(intOrNull("priority", "g"))
        )
    }.getOrNull()

    private fun JsonObject.toTemplateDomain(): PersistedProviderTemplate =
        PersistedProviderTemplate(
            provider = requireNotNull(objectOrNull("provider", "a")).toProviderDomain(),
            imageType = ArtworkType.valueOf(requireNotNull(stringOrNull("imageType", "b"))),
            idType = requireNotNull(stringOrNull("idType", "c")),
            mediaId = requireNotNull(stringOrNull("mediaId", "d")),
            providerPathHash = stringOrNull("providerPathHash", "e"),
            settingsHash = stringOrNull("settingsHash", "f"),
            credentialHash = stringOrNull("credentialHash", "g"),
            imageLanguage = requireNotNull(stringOrNull("imageLanguage", "h")),
            policyVersion = requireNotNull(intOrNull("policyVersion", "i")),
            pathParams = objectOrNull("pathParams", "j")
                ?.entrySet()
                ?.associate { (key, value) -> key to value.asString }
                .orEmpty()
        )

    private fun JsonObject.toProviderDomain(): ArtworkProviderId = when (val type = requireNotNull(stringOrNull("type", "a"))) {
        "runtime" -> ArtworkProviderId.RuntimeProvider(
            IntegrationProvider.valueOf(requireNotNull(stringOrNull("integrationProvider", "b")))
        )
        "rail_preview" -> ArtworkProviderId.RailPreview
        "addon_preview" -> ArtworkProviderId.AddonPreview
        "placeholder" -> ArtworkProviderId.Placeholder
        else -> error("Unknown provider type $type")
    }

    private fun toStoreJson(): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", SCHEMA_VERSION)
        add("decisions", JsonArray().apply {
            decisions.values.forEach { decision -> add(decision.toJson()) }
        })
        add("previewLinks", JsonArray().apply {
            previewToCanonical.forEach { (previewKey, canonicalKey) ->
                add(JsonObject().apply {
                    addProperty("previewKey", previewKey.value)
                    addProperty("canonicalKey", canonicalKey.value)
                })
            }
        })
    }

    private fun ArtworkDecision.toJson(): JsonObject = JsonObject().apply {
        addProperty("decisionKey", decisionKey.value)
        add("owner", ownerKey.toJson())
        addNullableProperty("canonicalContentId", canonicalContentId)
        addProperty("imageType", imageType.name)
        add("selectedCandidate", selectedCandidate.toJson())
        add("rejectedCandidates", JsonArray().apply {
            rejectedCandidates.forEach { rejected -> add(rejected.toJson()) }
        })
        addProperty("policyVersion", policyVersion)
        addProperty("imageLanguage", imageLanguage)
        addNullableProperty("settingsHash", settingsHash)
        addNullableProperty("credentialHash", credentialHash)
        addProperty("createdAtMs", createdAtMs)
        addProperty("expiresAtMs", expiresAtMs)
        addNullableProperty("staleUntilMs", staleUntilMs)
    }

    private fun ArtworkOwnerKey.toJson(): JsonObject = JsonObject().apply {
        when (this@toJson) {
            is ArtworkOwnerKey.CanonicalContent -> {
                addProperty("type", "canonical")
                addProperty("contentId", contentId)
                add("itemKey", JsonNull.INSTANCE)
                add("sourcePayloadHash", JsonNull.INSTANCE)
            }
            is ArtworkOwnerKey.PreviewItem -> {
                addProperty("type", "preview")
                add("contentId", JsonNull.INSTANCE)
                addProperty("itemKey", itemKey)
                addProperty("sourcePayloadHash", sourcePayloadHash)
            }
        }
    }

    private fun PersistedArtworkCandidate.toJson(): JsonObject = JsonObject().apply {
        add("provider", provider?.toJson() ?: JsonNull.INSTANCE)
        addProperty("sourceRole", sourceRole.name)
        addNullableProperty("sourceHash", sourceHash)
        addNullableProperty("redactedSourceForTrace", redactedSourceForTrace)
        add("providerTemplate", providerTemplate?.toJson() ?: JsonNull.INSTANCE)
        addProperty("priority", priority)
    }

    private fun RejectedArtworkCandidate.toJson(): JsonObject = JsonObject().apply {
        add("provider", provider?.toJson() ?: JsonNull.INSTANCE)
        addProperty("sourceRole", sourceRole.name)
        addProperty("reason", reason)
        addNullableProperty("sourceHash", sourceHash)
        addNullableProperty("redactedSourceForTrace", redactedSourceForTrace)
        add("providerTemplate", providerTemplate?.toJson() ?: JsonNull.INSTANCE)
        addProperty("priority", priority)
    }

    private fun PersistedProviderTemplate.toJson(): JsonObject = JsonObject().apply {
        add("provider", provider.toJson())
        addProperty("imageType", imageType.name)
        addProperty("idType", idType)
        addProperty("mediaId", mediaId)
        addNullableProperty("providerPathHash", providerPathHash)
        addNullableProperty("settingsHash", settingsHash)
        addNullableProperty("credentialHash", credentialHash)
        addProperty("imageLanguage", imageLanguage)
        addProperty("policyVersion", policyVersion)
        add("pathParams", JsonObject().apply {
            pathParams.forEach { (key, value) -> addProperty(key, value) }
        })
    }

    private fun ArtworkProviderId.toJson(): JsonObject = JsonObject().apply {
        when (this@toJson) {
            is ArtworkProviderId.RuntimeProvider -> {
                addProperty("type", "runtime")
                addProperty("integrationProvider", providerId.name)
            }
            ArtworkProviderId.RailPreview -> {
                addProperty("type", "rail_preview")
                add("integrationProvider", JsonNull.INSTANCE)
            }
            ArtworkProviderId.AddonPreview -> {
                addProperty("type", "addon_preview")
                add("integrationProvider", JsonNull.INSTANCE)
            }
            ArtworkProviderId.Placeholder -> {
                addProperty("type", "placeholder")
                add("integrationProvider", JsonNull.INSTANCE)
            }
        }
    }

    private fun JsonObject.addNullableProperty(name: String, value: String?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun JsonObject.addNullableProperty(name: String, value: Long?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun JsonElement.safeDecisionKeyHash(): String {
        val decisionKey = runCatching {
            asJsonObject.get("decisionKey")?.takeIf { element -> element.isJsonPrimitive }?.asString
        }.getOrNull()
        return artworkDecisionShortSha256(decisionKey ?: toString())
    }

    private fun JsonElement.safePreviewLinkKeyHash(): String {
        val previewKey = runCatching {
            asJsonObject.get("previewKey")?.takeIf { element -> element.isJsonPrimitive }?.asString
        }.getOrNull()
        return artworkDecisionShortSha256(previewKey ?: toString())
    }

    private fun loadedAuthoritativeState(
        droppedDecisionCount: Int,
        quarantinedDecisionCount: Int
    ): ArtworkDecisionStoreLoadState.LoadedAuthoritative =
        ArtworkDecisionStoreLoadState.LoadedAuthoritative(
            decisionCount = decisions.size,
            droppedDecisionCount = droppedDecisionCount,
            quarantinedDecisionCount = quarantinedDecisionCount,
            schemaVersion = SCHEMA_VERSION,
            storedSchemaVersion = lastStoredSchemaVersion,
            authorityContext = authorityContext
        )

    private fun loadedPartialNonAuthoritativeState(
        droppedDecisionCount: Int,
        quarantinedDecisionCount: Int
    ): ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative =
        ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative(
            decisionCount = decisions.size,
            droppedDecisionCount = droppedDecisionCount,
            quarantinedDecisionCount = quarantinedDecisionCount,
            schemaVersion = SCHEMA_VERSION,
            storedSchemaVersion = lastStoredSchemaVersion,
            authorityContext = authorityContext,
            errorClass = lastLoadErrorClass,
            errorMessageHash = lastLoadErrorMessageHash,
            errorTopFrame = lastLoadErrorTopFrame,
            firstQuarantinedDecisionKeyHash = firstQuarantinedDecisionKeyHash
        )

    private fun failedNonAuthoritativeState(
        errorClass: String
    ): ArtworkDecisionStoreLoadState.FailedNonAuthoritative =
        ArtworkDecisionStoreLoadState.FailedNonAuthoritative(
            errorClass = errorClass,
            errorMessageHash = lastLoadErrorMessageHash,
            errorTopFrame = lastLoadErrorTopFrame,
            authorityContext = authorityContext
        )

    private fun ArtworkDecisionStoreLoadState.nameForDiagnostics(): String = when (this) {
        ArtworkDecisionStoreLoadState.NotLoaded -> "NotLoaded"
        ArtworkDecisionStoreLoadState.Loading -> "Loading"
        is ArtworkDecisionStoreLoadState.LoadedAuthoritative -> "LoadedAuthoritative"
        is ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative -> "LoadedPartialNonAuthoritative"
        is ArtworkDecisionStoreLoadState.FailedNonAuthoritative -> "FailedNonAuthoritative"
    }

    private fun StackTraceElement.toTopFrameString(): String =
        "$className.$methodName:$lineNumber"

    private fun traceDecisionStoreWrite(
        success: Boolean,
        errorClass: String? = null
    ) {
        traceArtwork(
            eventType = "artwork.decision_store_write",
            payload = mapOf(
                "success" to success,
                "decisionCount" to decisions.size,
                "linkCount" to previewToCanonical.size,
                "errorClass" to errorClass
            )
        )
    }

    private fun traceArtwork(
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

    private fun ArtworkDecision.hasSameDurablePayload(other: ArtworkDecision): Boolean =
        decisionKey == other.decisionKey &&
            ownerKey == other.ownerKey &&
            canonicalContentId == other.canonicalContentId &&
            imageType == other.imageType &&
            selectedCandidate == other.selectedCandidate &&
            rejectedCandidates == other.rejectedCandidates &&
            policyVersion == other.policyVersion &&
            imageLanguage == other.imageLanguage &&
            settingsHash == other.settingsHash &&
            credentialHash == other.credentialHash

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val LOGCAT_ONLY_TRACE_SESSION_ID = "logcat-only"
        private val FALLBACK_SOURCE_ROLES = setOf(
            ArtworkSourceRole.PRIMARY,
            ArtworkSourceRole.RAIL_PREVIEW,
            ArtworkSourceRole.ADDON_PREVIEW,
            ArtworkSourceRole.FALLBACK
        )
    }
}
