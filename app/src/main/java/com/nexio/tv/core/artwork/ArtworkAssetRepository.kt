package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.ByteArrayIntegrationCodec
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

fun interface ArtworkByteLoader {
    suspend fun load(source: ArtworkSource, decision: ArtworkDecision): IntegrationLoadResult<ByteArray>
}

class UnregisteredArtworkByteLoader : ArtworkByteLoader {
    override suspend fun load(
        source: ArtworkSource,
        decision: ArtworkDecision
    ): IntegrationLoadResult<ByteArray> =
        IntegrationLoadResult.NetworkError(IOException("No artwork byte loader registered"))
}

data class ArtworkAssetResult(
    val assetKey: ArtworkAssetKey,
    val localFile: File,
    val record: ArtworkAssetRecord,
    val runtimeResult: IntegrationFetchResult<ByteArray>?,
    val runtimeApiShapeId: String,
    val cacheDecision: String,
    val mimeType: String?,
    val networkExecuted: Boolean,
    /**
     * False when bytes are on disk but [ArtworkAssetRepository.persistAssetRecordBestEffort]
     * could not write the record-store entry. Downstream snapshot/overlay writers must NOT
     * promote this asset to a `nexio-artwork://asset/<assetKey>` durable reference until a
     * subsequent record write succeeds, otherwise the URI becomes a dangling pointer that
     * `getOrRehydrateAsset` cannot recover.
     */
    val durable: Boolean = true
)

@Singleton
class ArtworkAssetRepository @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val diskCache: ArtworkAssetDiskCache,
    private val sourceMaterializer: ArtworkSourceMaterializer,
    private val byteLoader: ArtworkByteLoader,
    private val decisionCache: ArtworkDecisionCache,
    private val assetRecordStore: ArtworkAssetRecordStore,
    private val traceSink: RuntimeTraceSink = NoopRuntimeTraceSink
) {
    private val traceSequence = AtomicLong(0L)

    suspend fun getOrFetchDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetResult? {
        val lookupResult = decisionCache.lookup(decisionKey, requiredContext = null)
        return when (lookupResult) {
            is ArtworkDecisionLookupResult.Found -> {
                traceDecisionLookup(
                    decisionKey = decisionKey,
                    found = true,
                    lookupResult = lookupResult
                )
                materializeFoundDecision(lookupResult.decision)
            }

            is ArtworkDecisionLookupResult.MissingAuthoritative -> {
                traceDecisionLookup(
                    decisionKey = decisionKey,
                    found = false,
                    lookupResult = lookupResult
                )
                traceArtwork(
                    eventType = "artwork.orphan_decision_ref_found",
                    payload = mapOf(
                        "decisionKeyHash" to decisionKey.hashedForTrace(),
                        "lookupResult" to "MissingAuthoritative"
                    )
                )
                recoverMissingDecisionFromAsset(decisionKey)
            }

            is ArtworkDecisionLookupResult.CacheNotAuthoritative -> {
                traceDecisionLookup(
                    decisionKey = decisionKey,
                    found = false,
                    lookupResult = lookupResult
                )
                traceOrphanDecisionRefRehydrateRequested(
                    decisionKey = decisionKey,
                    reason = lookupResult.reason ?: "cache_not_authoritative",
                    extraPayload = mapOf(
                        "lookupResult" to "CacheNotAuthoritative",
                        "errorClass" to lookupResult.errorClass
                    )
                )
                null
            }

            is ArtworkDecisionLookupResult.LookupFailed -> {
                traceDecisionLookup(
                    decisionKey = decisionKey,
                    found = false,
                    lookupResult = lookupResult
                )
                traceOrphanDecisionRefRehydrateRequested(
                    decisionKey = decisionKey,
                    reason = "decision_lookup_failed",
                    extraPayload = mapOf(
                        "lookupResult" to "LookupFailed",
                        "errorClass" to lookupResult.errorClass,
                        "messageHash" to lookupResult.messageHash
                    )
                )
                null
            }
        }
    }

    private suspend fun materializeFoundDecision(decision: ArtworkDecision): ArtworkAssetResult? {
        val result = fetchOrFallback(decision)
        traceArtwork(
            eventType = "artwork.asset_materialized",
            payload = mapOf(
                "decisionKeyHash" to decision.decisionKey.hashedForTrace(),
                "assetKeyHash" to result?.assetKey?.hashedForTrace(),
                "provider" to result?.record?.provider?.key,
                "imageType" to result?.record?.imageType?.name,
                "cacheDecision" to result?.cacheDecision,
                "networkExecuted" to result?.networkExecuted,
                "success" to (result != null)
            )
        )
        return result
    }

    private fun recoverMissingDecisionFromAsset(decisionKey: ArtworkDecisionKey): ArtworkAssetResult? {
        val record = runCatching {
            assetRecordStore.findLatestAssetForDecision(decisionKey)
        }.onFailure { error ->
            traceArtwork(
                eventType = "artwork.orphan_decision_ref_recovery_lookup_failed",
                payload = mapOf(
                    "decisionKeyHash" to decisionKey.hashedForTrace(),
                    "errorClass" to error::class.java.name,
                    "messageHash" to error.message?.let(::artworkDecisionShortSha256)
                )
            )
        }.getOrNull()
        val file = record?.let(diskCache::getExistingFile)
        val fileExists = file != null
        val byteValid = record?.let(diskCache::hasReadableImageBytes) == true
        if (record != null && file != null && byteValid) {
            traceArtwork(
                eventType = "artwork.orphan_decision_ref_asset_recovered",
                payload = mapOf(
                    "decisionKeyHash" to decisionKey.hashedForTrace(),
                    "assetKeyHash" to record.assetKey.hashedForTrace(),
                    "provider" to record.provider?.key,
                    "imageType" to record.imageType.name,
                    "fileExists" to true,
                    "byteValid" to true,
                    "source" to "asset_reverse_index"
                )
            )
            return ArtworkAssetResult(
                assetKey = record.assetKey,
                localFile = file,
                record = record,
                runtimeResult = null,
                runtimeApiShapeId = "ARTWORK_REVERSE_INDEX",
                cacheDecision = "DECISION_MISSING_ASSET_RECOVERED",
                mimeType = record.mimeType,
                networkExecuted = false
            )
        }

        traceOrphanDecisionRefRehydrateRequested(
            decisionKey = decisionKey,
            reason = "missing_decision_no_asset",
            extraPayload = mapOf(
                "assetKeyHash" to record?.assetKey?.hashedForTrace(),
                "provider" to record?.provider?.key,
                "imageType" to record?.imageType?.name,
                "fileExists" to fileExists,
                "byteValid" to byteValid
            )
        )
        return null
    }

    private fun traceDecisionLookup(
        decisionKey: ArtworkDecisionKey,
        found: Boolean,
        lookupResult: ArtworkDecisionLookupResult
    ) {
        traceArtwork(
            eventType = "artwork.decision_lookup",
            payload = mapOf(
                "decisionKeyHash" to decisionKey.hashedForTrace(),
                "found" to found,
                "lookupResult" to lookupResult.traceName()
            )
        )
    }

    private fun traceOrphanDecisionRefRehydrateRequested(
        decisionKey: ArtworkDecisionKey,
        reason: String,
        extraPayload: Map<String, Any?> = emptyMap()
    ) {
        traceArtwork(
            eventType = "artwork.orphan_decision_ref_rehydrate_requested",
            payload = mapOf(
                "decisionKeyHash" to decisionKey.hashedForTrace(),
                "reason" to reason
            ) + extraPayload
        )
    }

    private suspend fun getOrFetchFallback(decision: ArtworkDecision): ArtworkAssetResult? {
        val fallbackRoles = setOf(
            ArtworkSourceRole.PRIMARY,
            ArtworkSourceRole.RAIL_PREVIEW,
            ArtworkSourceRole.ADDON_PREVIEW,
            ArtworkSourceRole.FALLBACK
        )
        val fallbackCandidates = decision.rejectedCandidates
            .filter { rejected ->
                rejected.provider != null && rejected.sourceRole in fallbackRoles
            }

        for (candidate in fallbackCandidates) {
            val fallbackDecision = decision.fallbackDecisionFor(candidate)
            val result = getOrFetch(fallbackDecision) ?: continue
            traceArtwork(
                eventType = "artwork.fallback_materialized",
                payload = mapOf(
                    "decisionKeyHash" to decision.decisionKey.hashedForTrace(),
                    "requestedDecisionKeyHash" to decision.decisionKey.hashedForTrace(),
                    "fallbackDecisionKeyHash" to fallbackDecision.decisionKey.hashedForTrace(),
                    "fallbackProvider" to result.record.provider?.key,
                    "assetKeyHash" to result.assetKey.hashedForTrace()
                )
            )
            return result.copy(cacheDecision = "FALLBACK_MATERIALIZED")
        }

        return null
    }

    private fun ArtworkDecision.fallbackDecisionFor(candidate: RejectedArtworkCandidate): ArtworkDecision {
        val fallbackSettingsHash = candidate.providerTemplate?.settingsHash
        val fallbackCredentialHash = candidate.providerTemplate?.credentialHash
        return copy(
            decisionKey = ArtworkCacheKeys.decisionKey(
                ownerKey = ownerKey,
                imageType = imageType,
                provider = candidate.provider,
                premiumEnabled = candidate.providerTemplate != null &&
                    candidate.sourceRole == ArtworkSourceRole.PREMIUM,
                settingsHash = fallbackSettingsHash,
                credentialHash = fallbackCredentialHash,
                policyVersion = policyVersion
            ),
            selectedCandidate = PersistedArtworkCandidate(
                provider = candidate.provider,
                sourceRole = candidate.sourceRole,
                sourceHash = candidate.sourceHash,
                redactedSourceForTrace = candidate.redactedSourceForTrace,
                providerTemplate = candidate.providerTemplate,
                priority = candidate.priority
            ),
            rejectedCandidates = emptyList(),
            settingsHash = fallbackSettingsHash,
            credentialHash = fallbackCredentialHash
        )
    }

    suspend fun getOrFetch(decision: ArtworkDecision): ArtworkAssetResult? =
        (fetchInternal(decision) as? FetchOutcome.Success)?.result

    /**
     * Calls [getOrFetch]; on failure, only invokes [getOrFetchFallback] when the failure
     * mode warrants it. Soft transient failures (network missing, timeouts, 5xx, 429)
     * combined with a recoverable prior asset (file on disk or asset record present) are
     * suppressed, preserving the previously-resolved premium artwork rather than swapping
     * in a weaker fallback for one bad refresh.
     */
    suspend fun fetchOrFallback(decision: ArtworkDecision): ArtworkAssetResult? {
        return when (val outcome = fetchInternal(decision)) {
            is FetchOutcome.Success -> outcome.result
            is FetchOutcome.SoftFailure -> {
                if (outcome.priorAssetRecoverable) {
                    traceArtwork(
                        eventType = "artwork.fallback_suppressed_soft_failure",
                        payload = mapOf(
                            "decisionKeyHash" to decision.decisionKey.hashedForTrace(),
                            "reason" to "soft_failure_prior_asset_recoverable",
                            "loadErrorClass" to outcome.loadErrorClass
                        )
                    )
                    null
                } else {
                    getOrFetchFallback(decision)
                }
            }
            is FetchOutcome.HardFailure -> getOrFetchFallback(decision)
            FetchOutcome.NotMaterialized -> null
        }
    }

    private suspend fun fetchInternal(decision: ArtworkDecision): FetchOutcome {
        val materialized = sourceMaterializer.materialize(decision)
            ?: return FetchOutcome.NotMaterialized
        diskCache.getExistingFile(materialized.assetKey)?.let { existing ->
            existingAssetResultOrNull(
                file = existing,
                materialized = materialized,
                decision = decision,
                cacheDecision = "ARTWORK_DISK_HIT",
                networkExecuted = false
            )?.let { return FetchOutcome.Success(it) }
        }

        val apiShapeId = materialized.apiShapeId
        val runtimeProvider = materialized.runtimeProvider
        var loaderInvoked = false
        var lastLoadResult: IntegrationLoadResult<ByteArray>? = null
        val result = runtime.get(
            IntegrationSpec(
                provider = runtimeProvider,
                apiShapeId = apiShapeId,
                operationKey = materialized.assetKey.value,
                cacheKey = materialized.assetKey.value,
                codec = ByteArrayIntegrationCodec,
                cachePolicy = IntegrationCachePolicy.CacheFirst(
                    ttlMs = (decision.expiresAtMs - decision.createdAtMs).coerceAtLeast(1L),
                    staleAfterExpiryMs = ((decision.staleUntilMs ?: decision.expiresAtMs) - decision.expiresAtMs)
                        .coerceAtLeast(0L)
                ),
                workClass = IntegrationWorkClass.BACKGROUND_HYDRATION,
                scope = IntegrationScope.GlobalEnglishImage,
                load = {
                    loaderInvoked = true
                    val loaded = byteLoader.load(materialized.source, decision)
                    lastLoadResult = loaded
                    loaded
                }
            )
        )

        val bytes = result.bytesOrNull()
        if (bytes == null) {
            diskCache.getExistingFile(materialized.assetKey)?.let { existing ->
                existingAssetResultOrNull(
                    file = existing,
                    materialized = materialized,
                    decision = decision,
                    cacheDecision = "ARTWORK_DISK_HIT_AFTER_RUNTIME_MISS",
                    networkExecuted = loaderInvoked
                )?.let { return FetchOutcome.Success(it) }
            }
            val priorAssetRecoverable = priorAssetRecoverable(materialized)
            return when {
                lastLoadResult.isSoftFailure() -> FetchOutcome.SoftFailure(
                    priorAssetRecoverable = priorAssetRecoverable,
                    loadErrorClass = lastLoadResult.classifyForTrace()
                )
                else -> FetchOutcome.HardFailure(
                    loadErrorClass = lastLoadResult.classifyForTrace()
                )
            }
        }

        val record = diskCache.recordFor(
            assetKey = materialized.assetKey,
            decision = decision,
            provider = materialized.provider,
            sourceHash = materialized.sourceHash,
            mimeType = ByteArrayIntegrationCodec.mimeType,
            byteCount = bytes.size.toLong(),
            fetchedAtMs = System.currentTimeMillis()
        )
        val write = diskCache.write(record, bytes)
        val durable = persistAssetRecordBestEffort(write.record)
        return FetchOutcome.Success(
            ArtworkAssetResult(
                assetKey = materialized.assetKey,
                localFile = write.file,
                record = write.record,
                runtimeResult = result,
                runtimeApiShapeId = apiShapeId,
                // Non-durable signals to downstream snapshot/overlay writers that the asset
                // record was not persisted; promoting it to a stored nexio-artwork://asset/
                // reference would create a dangling pointer that getOrRehydrateAsset cannot
                // recover. Wired in a follow-up plan; for now the flag is informational.
                cacheDecision = if (durable) result.cacheDecision() else "EPHEMERAL_RECORD_WRITE_FAILED",
                mimeType = write.record.mimeType,
                networkExecuted = loaderInvoked,
                durable = durable
            )
        )
    }

    private fun priorAssetRecoverable(materialized: MaterializedArtworkSource): Boolean {
        if (diskCache.getExistingFile(materialized.assetKey) != null) return true
        return runCatching { assetRecordStore.get(materialized.assetKey) }.getOrNull() != null
    }

    private fun IntegrationLoadResult<*>?.isSoftFailure(): Boolean = when (this) {
        null, is IntegrationLoadResult.Success<*> -> false
        is IntegrationLoadResult.NetworkError -> true
        is IntegrationLoadResult.HttpError -> when (statusCode) {
            408, 429 -> true
            in 500..599 -> true
            else -> false
        }
    }

    private fun IntegrationLoadResult<*>?.classifyForTrace(): String = when (this) {
        null -> "no_load_attempted"
        is IntegrationLoadResult.Success<*> -> "success"
        is IntegrationLoadResult.NetworkError -> "network_error:${throwable::class.java.simpleName}"
        is IntegrationLoadResult.HttpError -> "http_error:$statusCode"
    }

    private sealed interface FetchOutcome {
        data class Success(val result: ArtworkAssetResult) : FetchOutcome
        data class SoftFailure(
            val priorAssetRecoverable: Boolean,
            val loadErrorClass: String
        ) : FetchOutcome
        data class HardFailure(val loadErrorClass: String) : FetchOutcome
        data object NotMaterialized : FetchOutcome
    }

    fun getExistingFile(assetKey: ArtworkAssetKey): File? =
        diskCache.getExistingFile(assetKey)

    private fun existingAssetResultOrNull(
        file: File,
        materialized: MaterializedArtworkSource,
        decision: ArtworkDecision,
        cacheDecision: String,
        networkExecuted: Boolean
    ): ArtworkAssetResult? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val record = diskCache.recordFor(
            assetKey = materialized.assetKey,
            decision = decision,
            provider = materialized.provider,
            sourceHash = materialized.sourceHash,
            mimeType = ByteArrayIntegrationCodec.mimeType,
            byteCount = bytes.size.toLong(),
            fetchedAtMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        val durable = persistAssetRecordBestEffort(record)
        return ArtworkAssetResult(
            assetKey = materialized.assetKey,
            localFile = file,
            record = record,
            runtimeResult = IntegrationFetchResult.Fresh(bytes),
            runtimeApiShapeId = materialized.apiShapeId,
            cacheDecision = if (durable) cacheDecision else "EPHEMERAL_RECORD_WRITE_FAILED",
            mimeType = record.mimeType,
            networkExecuted = networkExecuted,
            durable = durable
        )
    }

    /**
     * Persists the record. Returns true if the store entry is now durable (either it was
     * already present with identical contents, or a fresh write succeeded). Returns false
     * when the underlying store throws — callers must treat the asset as non-durable so
     * downstream snapshot/overlay writers do not promote it to a stored URI reference.
     */
    private fun persistAssetRecordBestEffort(record: ArtworkAssetRecord): Boolean {
        val readResult = runCatching { assetRecordStore.get(record.assetKey) }
        readResult.onFailure { error ->
            traceArtwork(
                eventType = "artwork.asset_record_store_read_failed",
                payload = mapOf(
                    "assetKeyHash" to record.assetKey.hashedForTrace(),
                    "decisionKeyHash" to record.decisionKey?.hashedForTrace(),
                    "errorClass" to error::class.java.name,
                    "errorMessage" to (error.message ?: "")
                )
            )
        }
        if (readResult.isSuccess && readResult.getOrNull() == record) {
            return true
        }

        return runCatching {
            assetRecordStore.put(record)
            true
        }.onFailure { error ->
            traceArtwork(
                eventType = "artwork.asset_record_store_write_failed",
                payload = mapOf(
                    "assetKeyHash" to record.assetKey.hashedForTrace(),
                    "decisionKeyHash" to record.decisionKey?.hashedForTrace(),
                    "errorClass" to error::class.java.name,
                    "errorMessage" to (error.message ?: "")
                )
            )
        }.getOrDefault(false)
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

    private fun IntegrationFetchResult<ByteArray>.bytesOrNull(): ByteArray? =
        when (this) {
            is IntegrationFetchResult.Fresh -> value
            is IntegrationFetchResult.Updated -> value
            is IntegrationFetchResult.Stale -> value
            IntegrationFetchResult.Missing -> null
        }

    private fun IntegrationFetchResult<ByteArray>.cacheDecision(): String =
        when (this) {
            is IntegrationFetchResult.Fresh -> "HIT"
            is IntegrationFetchResult.Updated -> "MISS_THEN_NETWORK"
            is IntegrationFetchResult.Stale -> "STALE_HIT"
            IntegrationFetchResult.Missing -> "MISS_NETWORK_SUPPRESSED"
        }

    private fun ArtworkDecisionLookupResult.traceName(): String =
        when (this) {
            is ArtworkDecisionLookupResult.Found -> "Found"
            is ArtworkDecisionLookupResult.MissingAuthoritative -> "MissingAuthoritative"
            is ArtworkDecisionLookupResult.CacheNotAuthoritative -> "CacheNotAuthoritative"
            is ArtworkDecisionLookupResult.LookupFailed -> "LookupFailed"
        }

    private fun ArtworkDecisionKey.hashedForTrace(): String =
        artworkDecisionShortSha256(value)

    private fun ArtworkAssetKey.hashedForTrace(): String =
        artworkDecisionShortSha256(value)

    private companion object {
        const val LOGCAT_ONLY_TRACE_SESSION_ID = "logcat-only"
    }
}
