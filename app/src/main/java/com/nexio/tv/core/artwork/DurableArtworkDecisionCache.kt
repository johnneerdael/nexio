package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import java.io.File
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
    private val codec = ArtworkDecisionJsonCodec(gson)
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
            val snapshot = codec.readStoreFile(file) ?: run {
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

            lastStoredSchemaVersion = snapshot.storedSchemaVersion
            firstQuarantinedDecisionKeyHash = snapshot.firstQuarantinedDecisionKeyHash
            snapshot.decisions.forEach { decision ->
                decisions[decision.decisionKey] = decision
            }
            snapshot.previewLinks.forEach { (previewKey, canonicalKey) ->
                previewToCanonical[previewKey] = canonicalKey
            }
            val loadState =
                if (snapshot.droppedDecisionCount == 0 && snapshot.quarantinedDecisionCount == 0) {
                    loadedAuthoritativeState(snapshot.droppedDecisionCount, snapshot.quarantinedDecisionCount)
                } else {
                    loadedPartialNonAuthoritativeState(snapshot.droppedDecisionCount, snapshot.quarantinedDecisionCount)
                }
            traceDecisionStoreLoad(
                success = true,
                authoritative = loadState.isAuthoritativeForMissing(),
                loadState = loadState,
                decisionCount = decisions.size,
                linkCount = previewToCanonical.size,
                droppedDecisionCount = snapshot.droppedDecisionCount,
                quarantinedDecisionCount = snapshot.quarantinedDecisionCount,
                filePresent = true
            )
        }.onFailure { error ->
            decisions.clear()
            previewToCanonical.clear()
            if (error is ArtworkDecisionJsonStoreDecodeException) {
                lastStoredSchemaVersion = error.storedSchemaVersion
                lastLoadErrorMessageHash = null
                lastLoadErrorTopFrame = null
                val loadState = failedNonAuthoritativeState(errorClass = error.errorClassForLoad)
                traceDecisionStoreLoad(
                    success = false,
                    authoritative = false,
                    loadState = loadState,
                    decisionCount = 0,
                    linkCount = 0,
                    droppedDecisionCount = error.droppedDecisionCount,
                    quarantinedDecisionCount = error.quarantinedDecisionCount,
                    filePresent = true,
                    reason = error.reason,
                    storedSchemaVersion = error.storedSchemaVersion
                )
            } else {
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
        val snapshot = synchronized(lock) {
            pendingFlush = null
            if (!dirty) return
            dirty = false
            StoreSnapshot(
                decisions = decisions.values.toList(),
                previewLinks = previewToCanonical.entries.map { (previewKey, canonicalKey) ->
                    previewKey to canonicalKey
                }
            )
        }
        persistSnapshotToFile(snapshot)
    }

    private fun flushPendingWritesLocked() {
        pendingFlush?.cancel(false)
        pendingFlush = null
        if (!dirty) return
        dirty = false
        persistLocked()
    }

    private fun persistLocked() {
        persistSnapshotToFile(
            StoreSnapshot(
                decisions = decisions.values.toList(),
                previewLinks = previewToCanonical.entries.map { (previewKey, canonicalKey) ->
                    previewKey to canonicalKey
                }
            )
        )
    }

    private data class StoreSnapshot(
        val decisions: List<ArtworkDecision>,
        val previewLinks: List<Pair<ArtworkDecisionKey, ArtworkDecisionKey>>
    )

    private fun persistSnapshotToFile(snapshot: StoreSnapshot) {
        try {
            codec.writeStoreFile(file, snapshot.decisions, snapshot.previewLinks)
            traceDecisionStoreWrite(
                success = true,
                decisionCount = snapshot.decisions.size,
                linkCount = snapshot.previewLinks.size
            )
        } catch (error: Exception) {
            traceDecisionStoreWrite(
                success = false,
                errorClass = error.javaClass.simpleName,
                decisionCount = snapshot.decisions.size,
                linkCount = snapshot.previewLinks.size
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
        errorClass: String? = null,
        decisionCount: Int = decisions.size,
        linkCount: Int = previewToCanonical.size
    ) {
        traceArtwork(
            eventType = "artwork.decision_store_write",
            payload = mapOf(
                "success" to success,
                "decisionCount" to decisionCount,
                "linkCount" to linkCount,
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
        private const val SCHEMA_VERSION = ArtworkDecisionJsonCodec.SCHEMA_VERSION
        private const val LOGCAT_ONLY_TRACE_SESSION_ID = "logcat-only"
        private val FALLBACK_SOURCE_ROLES = setOf(
            ArtworkSourceRole.PRIMARY,
            ArtworkSourceRole.RAIL_PREVIEW,
            ArtworkSourceRole.ADDON_PREVIEW,
            ArtworkSourceRole.FALLBACK
        )
    }
}
