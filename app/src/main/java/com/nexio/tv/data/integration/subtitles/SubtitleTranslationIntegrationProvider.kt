package com.nexio.tv.data.integration.subtitles

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.SubtitleApiShapes
import com.nexio.tv.data.integration.subtitles.transport.SubtitleTranslationTransport
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.Request

sealed interface SubtitleTranslationExecutionResult {
    data class Response(
        val statusCode: Int,
        val isSuccessful: Boolean,
        val body: String?,
        val retryAfterHeader: String?,
        val retryAfterHeaderMs: Long?
    ) : SubtitleTranslationExecutionResult

    object Missing : SubtitleTranslationExecutionResult
}

@Singleton
class SubtitleTranslationIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val transport: SubtitleTranslationTransport
) {
    suspend fun execute(request: Request): IntegrationCallResult<SubtitleTranslationExecutionResult> {
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.SUBTITLE_TRANSLATION,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("subtitle:translation"),
                apiShapeId = SubtitleApiShapes.TRANSLATION,
                operationKey = "subtitle.translation.execute",
                call = {
                    try {
                        val result = transport.execute(request)
                        when {
                            !result.isSuccessful -> IntegrationCallResult.HttpError(
                                statusCode = result.statusCode,
                                retryAfterMs = result.retryAfterHeaderMs,
                                reason = result.body
                            )
                            result.body == null -> IntegrationCallResult.Missing
                            else -> IntegrationCallResult.Success(
                                SubtitleTranslationExecutionResult.Response(
                                    statusCode = result.statusCode,
                                    isSuccessful = result.isSuccessful,
                                    body = result.body,
                                    retryAfterHeader = result.retryAfterHeader,
                                    retryAfterHeaderMs = result.retryAfterHeaderMs
                                )
                            )
                        }
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
}
