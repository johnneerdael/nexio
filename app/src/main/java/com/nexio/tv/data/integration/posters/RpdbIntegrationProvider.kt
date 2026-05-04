package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.image.PosterIntegrationRequest
import com.nexio.tv.core.integration.ByteArrayIntegrationCodec
import com.nexio.tv.core.integration.IntegrationHeaderPolicies
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.PosterApiShapes
import com.nexio.tv.core.integration.StringIntegrationCodec
import com.nexio.tv.core.integration.credentialHash
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.RpdbApi
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RpdbIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val rpdbApi: RpdbApi,
    private val posterTransport: PosterTransport
) {
    suspend fun fetchPoster(request: PosterIntegrationRequest): ByteArray? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.RPDB,
            cacheKey = request.cacheKey,
            codec = ByteArrayIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = request.ttlMs,
                staleAfterExpiryMs = request.staleAfterExpiryMs
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            apiShapeId = PosterApiShapes.RPDB_POSTER_TEMPLATE,
            headerPolicyId = IntegrationHeaderPolicies.RPDB_IMAGE_PATH_KEY_V1,
            operationKey = "rpdb.poster.fetchPoster",
            load = {
                runCatching {
                    val result = posterTransport.execute(request.toRemoteUrl())
                    when {
                        result.body == null ->
                            IntegrationLoadResult.HttpError(result.statusCode, reason = "rpdb_poster_missing_body")
                        !result.isSuccessful ->
                            IntegrationLoadResult.HttpError(result.statusCode, reason = "rpdb_poster_failed")
                        else -> IntegrationLoadResult.Success(result.body)
                    }
                }.fold(
                    onSuccess = { it },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun validateApiKey(apiKey: String): Boolean {
        val credentialHash = credentialHash(IntegrationProvider.RPDB, apiKey)
        val spec = IntegrationSpec(
            provider = IntegrationProvider.RPDB,
            cacheKey = "rpdb:validate:credentialHash:$credentialHash",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.Disabled,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            apiShapeId = PosterApiShapes.RPDB_KEY_VALIDATION,
            headerPolicyId = IntegrationHeaderPolicies.RPDB_JSON_API_KEY_V1,
            operationKey = "rpdb.key.validate",
            load = {
                runCatching { rpdbApi.verifyApiKey(apiKey) }
                    .fold(
                        onSuccess = { response ->
                            val body = response.body()?.string()?.trim().orEmpty().lowercase()
                            val valid = response.isSuccessful &&
                                (body.isBlank() || body.contains("true") || body.contains("valid"))
                            IntegrationLoadResult.Success(valid.toString())
                        },
                        onFailure = { IntegrationLoadResult.NetworkError(it) }
                    )
            }
        )
        return runtime.get(spec).valueOrNull()?.toBoolean() == true
    }

    private fun PosterIntegrationRequest.toRemoteUrl(): String =
        "https://api.ratingposterdb.com/$apiKey/$path"
}
