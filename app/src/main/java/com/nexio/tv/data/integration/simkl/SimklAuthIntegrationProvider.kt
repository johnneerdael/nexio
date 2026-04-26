package com.nexio.tv.data.integration.simkl

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.remote.SimklRequestGate
import com.nexio.tv.data.remote.api.SimklApi
import com.nexio.tv.data.remote.dto.simkl.SimklPinResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklPinStatusResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklUserSettingsResponseDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

@Singleton
class SimklAuthIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val simklApi: SimklApi,
    private val requestGate: SimklRequestGate
) {
    suspend fun requestPinCode(): Response<SimklPinResponseDto>? {
        return authCall(
            apiShapeId = "simkl.pin.start",
            operationKey = "simkl.pin.start"
        ) {
            simklApi.requestPinCode()
        }
    }

    suspend fun getPinStatus(userCode: String): Response<SimklPinStatusResponseDto>? {
        return authCall(
            apiShapeId = "simkl.pin.status",
            operationKey = "simkl.pin.status"
        ) {
            simklApi.getPinStatus(userCode)
        }
    }

    suspend fun getUserSettings(authorization: String): Response<SimklUserSettingsResponseDto>? {
        return authCall(
            apiShapeId = "simkl.user_settings",
            operationKey = "simkl.user_settings"
        ) {
            simklApi.getUserSettings(authorization)
        }
    }

    private suspend fun <T> authCall(
        apiShapeId: String,
        operationKey: String,
        request: suspend () -> Response<T>
    ): Response<T>? {
        return when (
            val result = runtime.call(
                IntegrationCallSpec(
                    provider = IntegrationProvider.SIMKL,
                    apiShapeId = apiShapeId,
                    operationKey = operationKey,
                    workClass = IntegrationWorkClass.USER_VISIBLE,
                    call = {
                        val response = runCatching {
                            requestGate.acquire { request() }
                        }.getOrElse { exception ->
                            if (exception is IOException) {
                                return@IntegrationCallSpec IntegrationCallResult.NetworkError(exception)
                            }
                            throw exception
                        }
                        IntegrationCallResult.Success(response)
                    }
                )
            )
        ) {
            is IntegrationCallResult.Success<Response<T>> -> result.value
            else -> null
        }
    }
}
