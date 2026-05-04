package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.ByteArrayIntegrationCodec
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import java.io.File
import java.io.IOException

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
    val networkExecuted: Boolean
)

class ArtworkAssetRepository(
    private val runtime: IntegrationRuntime,
    private val diskCache: ArtworkAssetDiskCache,
    private val sourceMaterializer: ArtworkSourceMaterializer,
    private val byteLoader: ArtworkByteLoader = UnregisteredArtworkByteLoader(),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun getOrFetch(decision: ArtworkDecision): ArtworkAssetResult? {
        val materialized = sourceMaterializer.materialize(decision) ?: return null
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

        val bytes = result.bytesOrNull() ?: return null
        val record = diskCache.recordFor(
            assetKey = materialized.assetKey,
            decision = decision,
            provider = materialized.provider,
            sourceHash = materialized.sourceHash,
            mimeType = ByteArrayIntegrationCodec.mimeType,
            byteCount = bytes.size.toLong(),
            fetchedAtMs = nowMs()
        )
        val write = diskCache.write(record, bytes)
        return ArtworkAssetResult(
            assetKey = materialized.assetKey,
            localFile = write.file,
            record = write.record,
            runtimeResult = result,
            runtimeApiShapeId = apiShapeId,
            cacheDecision = result.cacheDecision(),
            networkExecuted = loaderInvoked
        )
    }

    fun getExistingFile(assetKey: ArtworkAssetKey): File? =
        diskCache.getExistingFile(assetKey)

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
}
