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
    val runtimeResult: IntegrationFetchResult<ByteArray>,
    val runtimeApiShapeId: String,
    val cacheDecision: String,
    val mimeType: String?,
    val networkExecuted: Boolean
)

@Singleton
class ArtworkAssetRepository @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val diskCache: ArtworkAssetDiskCache,
    private val sourceMaterializer: ArtworkSourceMaterializer,
    private val byteLoader: ArtworkByteLoader,
    private val decisionCache: ArtworkDecisionCache,
    private val traceSink: RuntimeTraceSink = NoopRuntimeTraceSink
) {
    private val traceSequence = AtomicLong(0L)

    suspend fun getOrFetchDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetResult? {
        val decision = decisionCache.get(decisionKey)
        traceArtwork(
            eventType = "artwork.decision_lookup",
            payload = mapOf(
                "decisionKey" to decisionKey.value,
                "found" to (decision != null)
            )
        )
        if (decision == null) {
            traceArtwork(
                eventType = "artwork.decision_missing",
                payload = mapOf("decisionKey" to decisionKey.value)
            )
            return null
        }

        val result = getOrFetch(decision)
        traceArtwork(
            eventType = "artwork.asset_materialized",
            payload = mapOf(
                "decisionKey" to decisionKey.value,
                "assetKey" to result?.assetKey?.value,
                "provider" to result?.record?.provider?.key,
                "imageType" to result?.record?.imageType?.name,
                "cacheDecision" to result?.cacheDecision,
                "networkExecuted" to result?.networkExecuted,
                "success" to (result != null)
            )
        )
        return result
    }

    suspend fun getOrFetch(decision: ArtworkDecision): ArtworkAssetResult? {
        val materialized = sourceMaterializer.materialize(decision) ?: return null
        diskCache.getExistingFile(materialized.assetKey)?.let { existing ->
            existingAssetResultOrNull(
                file = existing,
                materialized = materialized,
                decision = decision,
                cacheDecision = "ARTWORK_DISK_HIT",
                networkExecuted = false
            )?.let { return it }
        }

        val apiShapeId = materialized.apiShapeId
        val runtimeProvider = materialized.runtimeProvider
        var loaderInvoked = false
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
                    byteLoader.load(materialized.source, decision)
                }
            )
        )

        val bytes = result.bytesOrNull()
        if (bytes == null) {
            return diskCache.getExistingFile(materialized.assetKey)?.let { existing ->
                existingAssetResultOrNull(
                    file = existing,
                    materialized = materialized,
                    decision = decision,
                    cacheDecision = "ARTWORK_DISK_HIT_AFTER_RUNTIME_MISS",
                    networkExecuted = loaderInvoked
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
        return ArtworkAssetResult(
            assetKey = materialized.assetKey,
            localFile = write.file,
            record = write.record,
            runtimeResult = result,
            runtimeApiShapeId = apiShapeId,
            cacheDecision = result.cacheDecision(),
            mimeType = write.record.mimeType,
            networkExecuted = loaderInvoked
        )
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
        return ArtworkAssetResult(
            assetKey = materialized.assetKey,
            localFile = file,
            record = record,
            runtimeResult = IntegrationFetchResult.Fresh(bytes),
            runtimeApiShapeId = materialized.apiShapeId,
            cacheDecision = cacheDecision,
            mimeType = record.mimeType,
            networkExecuted = networkExecuted
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

    private companion object {
        const val LOGCAT_ONLY_TRACE_SESSION_ID = "logcat-only"
    }
}
