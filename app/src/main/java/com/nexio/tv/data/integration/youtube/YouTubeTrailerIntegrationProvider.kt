package com.nexio.tv.data.integration.youtube

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.integration.youtube.transport.YouTubeTrailerTransport
import com.nexio.tv.data.integration.youtube.transport.YouTubeTrailerTransportCall
import com.nexio.tv.data.integration.youtube.transport.YouTubeTrailerTransportResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class YouTubeTrailerIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val transport: YouTubeTrailerTransport
) {
    suspend fun fetch(
        call: YouTubeTrailerTransportCall
    ): IntegrationCallResult<YouTubeTrailerTransportResponse> {
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.YOUTUBE_TRAILER,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("youtube:trailer"),
                apiShapeId = "youtube_trailer.transport.execute",
                operationKey = "youtube.trailer.fetch",
                call = {
                    try {
                        val response = transport.execute(call)
                        IntegrationCallResult.Success(response)
                    } catch (exception: Exception) {
                        if (exception is CancellationException) {
                            throw exception
                        }
                        IntegrationCallResult.NetworkError(exception)
                    }
                }
            )
        )
    }

    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.YOUTUBE_TRAILER,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("youtube:trailer"),
                apiShapeId = "youtube_trailer.transport.probe",
                operationKey = "youtube.trailer.probe",
                call = {
                    try {
                        IntegrationCallResult.Success(transport.probe(url = url, headers = headers))
                    } catch (exception: Exception) {
                        if (exception is CancellationException) {
                            throw exception
                        }
                        IntegrationCallResult.Success(false)
                    }
                }
            )
        )

        return (result as? IntegrationCallResult.Success)?.value ?: false
    }
}
