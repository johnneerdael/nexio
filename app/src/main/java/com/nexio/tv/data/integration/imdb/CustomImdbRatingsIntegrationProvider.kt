package com.nexio.tv.data.integration.imdb

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.CustomImdbApiShapes
import com.nexio.tv.core.integration.StringIntegrationCodec
import com.nexio.tv.data.integration.imdb.transport.CustomImdbRatingsTransport
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class CustomImdbPayload(
    val body: String
)

private const val CUSTOM_IMDB_EPISODE_RATINGS_TTL_MS = 7L * 24L * 60L * 60L * 1000L

@Singleton
class CustomImdbRatingsIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val transport: CustomImdbRatingsTransport
) {
    suspend fun executeCached(
        baseUrl: String,
        request: Request,
        cacheKey: String,
        ttlMs: Long
    ): IntegrationCallResult<CustomImdbPayload> {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.CUSTOM_IMDB,
            apiShapeId = request.customImdbApiShapeId(),
            operationKey = "custom_imdb.ratings_cached",
            cacheKey = cacheKey,
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = ttlMs,
                staleAfterExpiryMs = ttlMs
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.ProviderConfig("custom-imdb:$baseUrl"),
            load = {
                try {
                    val result = transport.execute(request)
                    if (!result.isSuccessful) {
                        IntegrationLoadResult.HttpError(
                            statusCode = result.statusCode,
                            retryAfterMs = result.retryAfterMs,
                            reason = "custom_imdb_http_${result.statusCode}"
                        )
                    } else {
                        IntegrationLoadResult.Success(result.body)
                    }
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    IntegrationLoadResult.NetworkError(exception)
                }
            }
        )

        return when (val result = runtime.get(spec)) {
            is IntegrationFetchResult.Fresh -> IntegrationCallResult.Success(CustomImdbPayload(result.value))
            is IntegrationFetchResult.Updated -> IntegrationCallResult.Success(CustomImdbPayload(result.value))
            is IntegrationFetchResult.Stale -> IntegrationCallResult.Success(CustomImdbPayload(result.value))
            IntegrationFetchResult.Missing -> IntegrationCallResult.Missing
        }
    }

    suspend fun execute(
        baseUrl: String,
        request: Request
    ): IntegrationCallResult<CustomImdbPayload> {
        if (request.isEpisodeRatingsRequest()) {
            return executeCached(
                baseUrl = baseUrl,
                request = request,
                cacheKey = "custom-imdb:episode-ratings:${baseUrl.hashCode()}:${request.bodySignature()}",
                ttlMs = CUSTOM_IMDB_EPISODE_RATINGS_TTL_MS
            )
        }

        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.CUSTOM_IMDB,
                apiShapeId = CustomImdbApiShapes.TRANSPORT_EXECUTE,
                operationKey = "custom_imdb.ratings_execute",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("custom-imdb:$baseUrl"),
                call = {
                    try {
                        val result = transport.execute(request)
                        if (!result.isSuccessful) {
                            IntegrationCallResult.HttpError(
                                statusCode = result.statusCode,
                                retryAfterMs = result.retryAfterMs,
                                reason = "custom_imdb_http_${result.statusCode}"
                            )
                        } else {
                            IntegrationCallResult.Success(CustomImdbPayload(result.body))
                        }
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        IntegrationCallResult.NetworkError(exception)
                    }
                }
            )
        )
    }
}

private fun Request.customImdbApiShapeId(): String =
    when {
        isEpisodeRatingsRequest() -> CustomImdbApiShapes.EPISODE_SERIES
        url.encodedPath.endsWith("/v1/ratings/bulk") -> CustomImdbApiShapes.TITLE_BULK
        else -> CustomImdbApiShapes.EPISODE_SERIES
    }

private fun Request.isEpisodeRatingsRequest(): Boolean =
    url.encodedPath.endsWith("/v1/ratings/bulk") &&
        url.queryParameter("episodes").equals("true", ignoreCase = true)

private fun Request.bodySignature(): Int {
    val body = body ?: return url.toString().hashCode()
    val buffer = okio.Buffer()
    body.writeTo(buffer)
    return "${url.encodedPath}?${url.encodedQuery.orEmpty()}:${buffer.readUtf8()}".hashCode()
}
