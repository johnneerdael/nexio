package com.nexio.tv.data.integration.subtitles

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.SubtitleApiShapes
import com.nexio.tv.data.integration.subtitles.transport.SubtitleSourceDownloadTransport
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class SubtitleSourceDownloadIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val transport: SubtitleSourceDownloadTransport
) {
    suspend fun downloadText(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): IntegrationCallResult<String> {
        return execute(url, headers) { result ->
            result.body?.let {
                IntegrationCallResult.Success(it)
            } ?: IntegrationCallResult.Missing
        }
    }

    suspend fun downloadBytes(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): IntegrationCallResult<ByteArray> {
        return execute(url, headers) { result ->
            result.bytes?.let {
                IntegrationCallResult.Success(it)
            } ?: IntegrationCallResult.Missing
        }
    }

    private suspend fun <T> execute(
        url: String,
        headers: Map<String, String>,
        mapSuccess: (com.nexio.tv.data.integration.subtitles.transport.SubtitleSourceDownloadTransportResult) -> IntegrationCallResult<T>
    ): IntegrationCallResult<T> {
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.SUBTITLE_SOURCE_DOWNLOAD,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.Account("subtitle:source"),
                apiShapeId = SubtitleApiShapes.SOURCE_DOWNLOAD,
                operationKey = "subtitle.source.download",
                call = {
                    try {
                        val result = transport.executeUrl(url, headers)

                        if (!result.isSuccessful) {
                            IntegrationCallResult.HttpError(
                                statusCode = result.statusCode,
                                reason = "subtitle_source_download_failed"
                            )
                        } else {
                            mapSuccess(result)
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
