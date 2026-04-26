package com.nexio.tv.data.integration.debrid

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.DebridApiShapes
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.PremiumizeApi
import com.nexio.tv.data.remote.dto.debrid.PremiumizeAccountInfoDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeCacheCheckDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeDirectDownloadDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeItemDetailsDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeListAllDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class PremiumizeIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val premiumizeApi: PremiumizeApi
) {
    suspend fun fetchAccountInfo(apiKey: String): IntegrationCallResult<PremiumizeAccountInfoDto> =
        runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.PREMIUMIZE,
                apiShapeId = DebridApiShapes.PREMIUMIZE_ACCOUNT,
                operationKey = "premiumize.account.fetch",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = accountScope(apiKey),
                call = {
                    val response = runCatching { premiumizeApi.getAccountInfo(apiKey) }
                        .getOrElse {
                            if (it is CancellationException) throw it
                            return@IntegrationCallSpec IntegrationCallResult.NetworkError(it)
                        }
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        IntegrationCallResult.HttpError(
                            response.code(),
                            reason = "premiumize_account_info_failed"
                        )
                    } else {
                        IntegrationCallResult.Success(body)
                    }
                }
            )
        )

    suspend fun fetchListAllItems(apiKey: String): PremiumizeListAllDto? =
        cachedRequest(
            cacheKey = "premiumize:listall:${apiKey.hashCode()}",
            apiShapeId = "premiumize.list_all",
            operationKey = "premiumize.list_all",
            codec = gsonCodec<PremiumizeListAllDto>(),
            scope = accountScope(apiKey),
            workClass = IntegrationWorkClass.USER_VISIBLE
        ) {
            premiumizeApi.listAllItems(apiKey)
        }

    suspend fun fetchItemDetails(apiKey: String, id: String): PremiumizeItemDetailsDto? =
        cachedRequest(
            cacheKey = "premiumize:item:$id:${apiKey.hashCode()}",
            apiShapeId = "premiumize.item_details",
            operationKey = "premiumize.item_details",
            codec = gsonCodec<PremiumizeItemDetailsDto>(),
            scope = accountScope(apiKey),
            workClass = IntegrationWorkClass.USER_VISIBLE
        ) {
            premiumizeApi.getItemDetails(apiKey = apiKey, id = id)
        }

    suspend fun checkCache(apiKey: String, items: List<String>): PremiumizeCacheCheckDto? =
        cachedRequest(
            cacheKey = "premiumize:cache:${items.joinToString(",").hashCode()}:${apiKey.hashCode()}",
            apiShapeId = "premiumize.cache_check",
            operationKey = "premiumize.cache_check",
            codec = gsonCodec<PremiumizeCacheCheckDto>(),
            scope = accountScope(apiKey),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION
        ) {
            premiumizeApi.checkCache(apiKey = apiKey, items = items)
        }

    suspend fun createDirectDownload(apiKey: String, source: String): PremiumizeDirectDownloadDto? =
        mutationRequest(
            apiShapeId = DebridApiShapes.PREMIUMIZE_DIRECT_DOWNLOAD,
            operationKey = "premiumize.direct_download",
            codec = gsonCodec<PremiumizeDirectDownloadDto>(),
            scope = accountScope(apiKey),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION
        ) {
            premiumizeApi.createDirectDownload(apiKey = apiKey, source = source)
        }

    private suspend fun <T> cachedRequest(
        cacheKey: String,
        apiShapeId: String,
        operationKey: String,
        codec: com.nexio.tv.core.integration.IntegrationCodec<T>,
        scope: IntegrationScope,
        workClass: IntegrationWorkClass,
        block: suspend () -> retrofit2.Response<T>
    ): T? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.PREMIUMIZE,
            apiShapeId = apiShapeId,
            operationKey = operationKey,
            cacheKey = cacheKey,
            codec = codec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 60_000L,
                staleAfterExpiryMs = 5L * 60L * 1000L
            ),
            workClass = workClass,
            scope = scope,
            load = {
                runCatching { block() }.fold(
                    onSuccess = { response ->
                        val body = response.body()
                        if (!response.isSuccessful || body == null) {
                            IntegrationLoadResult.HttpError(
                                response.code(),
                                reason = "premiumize_request_failed"
                            )
                        } else {
                            IntegrationLoadResult.Success(body)
                        }
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    private suspend fun <T> mutationRequest(
        apiShapeId: String,
        operationKey: String,
        codec: com.nexio.tv.core.integration.IntegrationCodec<T>,
        scope: IntegrationScope,
        workClass: IntegrationWorkClass,
        block: suspend () -> retrofit2.Response<T>
    ): T? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.PREMIUMIZE,
            apiShapeId = apiShapeId,
            operationKey = operationKey,
            cacheKey = null,
            codec = codec,
            cachePolicy = IntegrationCachePolicy.Mutation,
            workClass = workClass,
            scope = scope,
            load = {
                runCatching { block() }.fold(
                    onSuccess = { response ->
                        val body = response.body()
                        if (!response.isSuccessful || body == null) {
                            IntegrationLoadResult.HttpError(
                                response.code(),
                                reason = "premiumize_mutation_failed"
                            )
                        } else {
                            IntegrationLoadResult.Success(body)
                        }
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    private fun accountScope(apiKey: String): IntegrationScope =
        IntegrationScope.Account("premiumize:${apiKey.hashCode()}")
}
