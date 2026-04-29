package com.nexio.tv.data.integration.simkl

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.SimklApiShapes
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.ProfileExecutionContext
import com.nexio.tv.core.integration.ProviderAccountRef
import com.nexio.tv.data.remote.SimklRequestGate
import com.nexio.tv.data.remote.api.SimklApi
import com.nexio.tv.data.remote.dto.simkl.SimklPinResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklPinStatusResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklUserSettingsResponseDto
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.domain.model.TrackingProvider
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
            apiShapeId = SimklApiShapes.PIN_START,
            operationKey = "simkl.pin.start"
        ) {
            simklApi.requestPinCode()
        }
    }

    suspend fun getPinStatus(userCode: String): Response<SimklPinStatusResponseDto>? {
        return authCall(
            apiShapeId = SimklApiShapes.PIN_STATUS,
            operationKey = "simkl.pin.status"
        ) {
            simklApi.getPinStatus(userCode)
        }
    }

    suspend fun getUserSettings(
        session: TrackingAuthSession,
        authorization: String
    ): Response<SimklUserSettingsResponseDto>? {
        return authCall(
            apiShapeId = SimklApiShapes.USER_SETTINGS,
            operationKey = accountOperationKey(session, "simkl.user_settings"),
            scope = accountScope(session),
            profileContext = profileContext(session)
        ) {
            simklApi.getUserSettings(authorization)
        }
    }

    private suspend fun <T> authCall(
        apiShapeId: String,
        operationKey: String,
        scope: IntegrationScope = IntegrationScope.GlobalContent,
        profileContext: ProfileExecutionContext? = null,
        request: suspend () -> Response<T>
    ): Response<T>? {
        return when (
            val result = runtime.call(
                IntegrationCallSpec(
                    provider = IntegrationProvider.SIMKL,
                    apiShapeId = apiShapeId,
                    operationKey = operationKey,
                    workClass = IntegrationWorkClass.USER_VISIBLE,
                    scope = scope,
                    profileContext = profileContext,
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

    private fun accountScope(session: TrackingAuthSession): IntegrationScope.Account =
        IntegrationScope.Account(
            profileId = session.profileId,
            provider = IntegrationProvider.SIMKL,
            credentialHash = credentialHash(session)
        )

    private fun profileContext(session: TrackingAuthSession): ProfileExecutionContext =
        ProfileExecutionContext(
            profileId = session.profileId,
            sessionId = "simkl:${session.profileId}",
            displayLanguage = "en",
            region = "global",
            accounts = mapOf(
                IntegrationProvider.SIMKL to ProviderAccountRef(
                    provider = IntegrationProvider.SIMKL,
                    credentialHash = credentialHash(session),
                    accountIdHash = session.accountIdHash
                )
            )
        )

    private fun accountOperationKey(session: TrackingAuthSession, operationKey: String): String =
        "profile:${session.profileId}:provider:SIMKL:credential:${credentialHash(session)}:operation:$operationKey"

    private fun credentialHash(session: TrackingAuthSession): String {
        require(session.provider == TrackingProvider.SIMKL) { "Expected SIMKL session, got ${session.provider}" }
        return session.credentialHash?.takeIf { it.isNotBlank() } ?: "simkl:profile:${session.profileId}"
    }
}
