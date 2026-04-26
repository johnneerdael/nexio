package com.nexio.tv.data.integration.collector

import com.nexio.tv.core.integration.CollectorApiShapes
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.integration.collector.transport.ShadowAutoplayUploadTransport
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class ShadowAutoplayUploadIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val transport: ShadowAutoplayUploadTransport
) {
    suspend fun uploadEvent(
        baseUrl: String,
        token: String,
        envelopeJson: String
    ): IntegrationCallResult<Unit> = runtime.call(
        IntegrationCallSpec(
            provider = IntegrationProvider.SHADOW_COLLECTOR,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            apiShapeId = CollectorApiShapes.SHADOW_AUTOPLAY_UPLOAD,
            operationKey = "shadowCollector.autoplay.uploadEvent",
            call = {
                runCatching {
                    transport.uploadEvent(
                        baseUrl = baseUrl,
                        token = token,
                        envelopeJson = envelopeJson
                    )
                }.fold(
                    onSuccess = { result ->
                        if (result.isSuccessful) {
                            IntegrationCallResult.Success(Unit)
                        } else {
                            IntegrationCallResult.HttpError(result.statusCode)
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        IntegrationCallResult.NetworkError(error)
                    }
                )
            }
        )
    )
}
