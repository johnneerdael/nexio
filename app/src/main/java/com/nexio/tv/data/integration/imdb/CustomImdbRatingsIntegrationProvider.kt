package com.nexio.tv.data.integration.imdb

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.CustomImdbApiShapes
import com.nexio.tv.data.integration.imdb.transport.CustomImdbRatingsTransport
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class CustomImdbPayload(
    val body: String
)

@Singleton
class CustomImdbRatingsIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val transport: CustomImdbRatingsTransport
) {
    suspend fun execute(
        baseUrl: String,
        request: Request
    ): IntegrationCallResult<CustomImdbPayload> {
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
        url.encodedPath.endsWith("/v1/ratings/bulk") -> CustomImdbApiShapes.TITLE_BULK
        else -> CustomImdbApiShapes.EPISODE_SERIES
    }
