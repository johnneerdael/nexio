package com.nexio.tv.data.integration.debrid

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.remote.api.RealDebridApi
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCodeResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCredentialsResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTokenResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridUserDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import retrofit2.Response

@Singleton
class RealDebridAuthIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val realDebridApi: RealDebridApi
) {
    suspend fun requestDeviceCode(
        clientId: String,
        newCredentials: String?
    ): Response<RealDebridDeviceCodeResponseDto>? {
        return authCall(
            apiShapeId = "real_debrid.device_code",
            operationKey = "real_debrid.device_code"
        ) {
            realDebridApi.requestDeviceCode(clientId = clientId, newCredentials = newCredentials)
        }
    }

    suspend fun requestDeviceCredentials(
        clientId: String,
        deviceCode: String
    ): Response<RealDebridDeviceCredentialsResponseDto>? {
        return authCall(
            apiShapeId = "real_debrid.device_credentials",
            operationKey = "real_debrid.device_credentials"
        ) {
            realDebridApi.requestDeviceCredentials(clientId = clientId, deviceCode = deviceCode)
        }
    }

    suspend fun requestToken(
        clientId: String,
        clientSecret: String,
        code: String,
        grantType: String
    ): Response<RealDebridTokenResponseDto>? {
        return authCall(
            apiShapeId = "real_debrid.token",
            operationKey = "real_debrid.token"
        ) {
            realDebridApi.requestToken(
                clientId = clientId,
                clientSecret = clientSecret,
                code = code,
                grantType = grantType
            )
        }
    }

    suspend fun getCurrentUser(authorization: String): Response<RealDebridUserDto>? {
        return authCall(
            apiShapeId = "real_debrid.account",
            operationKey = "real_debrid.account"
        ) {
            realDebridApi.getCurrentUser(authorization)
        }
    }

    suspend fun disableCurrentAccessToken(authorization: String): Response<Unit>? {
        return authCall(
            apiShapeId = "real_debrid.revoke_token",
            operationKey = "real_debrid.revoke_token"
        ) {
            realDebridApi.disableCurrentAccessToken(authorization)
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
                    provider = IntegrationProvider.REAL_DEBRID,
                    apiShapeId = apiShapeId,
                    operationKey = operationKey,
                    workClass = IntegrationWorkClass.USER_VISIBLE,
                    call = {
                        val response = runCatching { request() }.getOrElse { exception ->
                            if (exception is CancellationException) throw exception
                            return@IntegrationCallSpec IntegrationCallResult.NetworkError(exception)
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
