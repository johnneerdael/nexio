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
import com.nexio.tv.data.remote.api.TopPostersApi
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopPostersIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val topPostersApi: TopPostersApi,
    private val posterTransport: PosterTransport
) {
    suspend fun fetchPoster(request: PosterIntegrationRequest): ByteArray? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TOP_POSTERS,
            cacheKey = request.cacheKey,
            codec = ByteArrayIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = request.ttlMs,
                staleAfterExpiryMs = request.ttlMs
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            apiShapeId = PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE,
            headerPolicyId = IntegrationHeaderPolicies.TOP_POSTERS_IMAGE_PATH_KEY_V1,
            operationKey = "topposters.poster.fetchPoster",
            load = {
                runCatching {
                    val result = posterTransport.execute(request.toRemoteUrl())
                    when {
                        result.body == null ->
                            IntegrationLoadResult.HttpError(result.statusCode, reason = "topposters_poster_missing_body")
                        !result.isSuccessful ->
                            IntegrationLoadResult.HttpError(result.statusCode, reason = "topposters_poster_failed")
                        else ->
                            IntegrationLoadResult.Success(result.body)
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
        val credentialHash = credentialHash(IntegrationProvider.TOP_POSTERS, apiKey)
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TOP_POSTERS,
            cacheKey = "topposters:validate:credentialHash:$credentialHash",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.Disabled,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            apiShapeId = PosterApiShapes.TOP_POSTERS_KEY_VALIDATION,
            headerPolicyId = IntegrationHeaderPolicies.TOP_POSTERS_IMAGE_PATH_KEY_V1,
            operationKey = "topposters.key.validate",
            load = {
                runCatching { topPostersApi.verifyApiKey(apiKey) }
                    .fold(
                        onSuccess = { response ->
                            val body = response.body()?.string()?.trim().orEmpty().lowercase()
                            val valid = response.isSuccessful &&
                                (body.isBlank() || body.contains("\"valid\":true") || body.contains("tier"))
                            IntegrationLoadResult.Success(valid.toString())
                        },
                        onFailure = { IntegrationLoadResult.NetworkError(it) }
                    )
            }
        )
        return runtime.get(spec).valueOrNull()?.toBoolean() == true
    }

    private fun PosterIntegrationRequest.toRemoteUrl(): String {
        val baseUrl = "https://api.top-posters.com/$apiKey/$path"
        val fallback = fallbackUrl ?: return baseUrl
        return "$baseUrl?fallback_url=${URLEncoder.encode(fallback, StandardCharsets.UTF_8.name())}"
    }
}
