package com.nexio.tv.data.integration.debrid

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.DebridApiShapes
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.EasyDebridApi
import com.nexio.tv.data.remote.dto.debrid.EasyDebridUserDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridGenerateDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridGenerateRequestDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupDetailsDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupRequestDto
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EasyDebridIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val easyDebridApi: EasyDebridApi
) {
    suspend fun fetchAccountInfo(apiKey: String): IntegrationCallResult<EasyDebridUserDto> =
        runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.EASY_DEBRID,
                apiShapeId = DebridApiShapes.EASY_DEBRID_ACCOUNT,
                operationKey = "easy_debrid.account.fetch",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = accountScope(apiKey),
                call = {
                    val response = runCatching {
                        easyDebridApi.getUserDetails(authorization = "Bearer $apiKey")
                    }.getOrElse {
                        if (it is CancellationException) throw it
                        return@IntegrationCallSpec IntegrationCallResult.NetworkError(it)
                    }

                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        IntegrationCallResult.HttpError(
                            response.code(),
                            reason = body?.message ?: body?.error ?: "Invalid EasyDebrid API key"
                        )
                    } else {
                        IntegrationCallResult.Success(body)
                    }
                }
            )
        )

    suspend fun lookup(
        apiKey: String,
        body: EasyDebridLookupRequestDto
    ): EasyDebridLookupDto? =
        cachedRequest(
            cacheKey = "easydebrid:lookup:${body.hashCode()}:${apiKey.hashCode()}",
            apiShapeId = DebridApiShapes.EASY_DEBRID_LOOKUP,
            operationKey = "easy_debrid.lookup",
            codec = gsonCodec<EasyDebridLookupDto>(),
            scope = accountScope(apiKey),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION
        ) {
            easyDebridApi.lookup(
                authorization = "Bearer $apiKey",
                body = body
            )
        }

    suspend fun lookupDetails(
        apiKey: String,
        body: EasyDebridLookupRequestDto
    ): EasyDebridLookupDetailsDto? =
        cachedRequest(
            cacheKey = "easydebrid:lookupdetails:${body.hashCode()}:${apiKey.hashCode()}",
            apiShapeId = DebridApiShapes.EASY_DEBRID_LOOKUP_DETAILS,
            operationKey = "easy_debrid.lookup_details",
            codec = gsonCodec<EasyDebridLookupDetailsDto>(),
            scope = accountScope(apiKey),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION
        ) {
            easyDebridApi.lookupDetails(
                authorization = "Bearer $apiKey",
                body = body
            )
        }

    suspend fun generate(
        apiKey: String,
        body: EasyDebridGenerateRequestDto
    ): EasyDebridGenerateDto? =
        mutationRequest(
            apiShapeId = DebridApiShapes.EASY_DEBRID_GENERATE,
            operationKey = "easy_debrid.generate",
            codec = gsonCodec<EasyDebridGenerateDto>(),
            scope = accountScope(apiKey),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION
        ) {
            easyDebridApi.generate(
                authorization = "Bearer $apiKey",
                body = body
            )
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
            provider = IntegrationProvider.EASY_DEBRID,
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
            load = loadFrom(block)
        )
        return runtime.get(spec).valueOrNull()
    }

    private fun accountScope(apiKey: String): IntegrationScope =
        IntegrationScope.ProviderConfig("easydebrid:${apiKey.hashCode()}")

    private suspend fun <T> mutationRequest(
        apiShapeId: String,
        operationKey: String,
        codec: com.nexio.tv.core.integration.IntegrationCodec<T>,
        scope: IntegrationScope,
        workClass: IntegrationWorkClass,
        block: suspend () -> retrofit2.Response<T>
    ): T? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.EASY_DEBRID,
            apiShapeId = apiShapeId,
            operationKey = operationKey,
            cacheKey = null,
            codec = codec,
            cachePolicy = IntegrationCachePolicy.Mutation,
            workClass = workClass,
            scope = scope,
            load = loadFrom(block)
        )
        return runtime.get(spec).valueOrNull()
    }

    private fun <T> loadFrom(
        block: suspend () -> retrofit2.Response<T>
    ): suspend () -> IntegrationLoadResult<T> = {
        runCatching { block() }.fold(
            onSuccess = { response ->
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    IntegrationLoadResult.HttpError(response.code(), reason = "easydebrid_request_failed")
                } else {
                    IntegrationLoadResult.Success(body)
                }
            },
            onFailure = { IntegrationLoadResult.NetworkError(it) }
        )
    }
}
