package com.nexio.tv.data.integration.tvdb

import android.util.Log
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.tvdb.TVDB_TOKEN_TTL_MS
import com.nexio.tv.core.tvdb.TvdbAuthResult
import com.nexio.tv.core.tvdb.TvdbLoginGateway
import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.data.remote.api.TvdbLoginRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

private const val TAG = "TvdbLoginIntegrationProvider"

@Singleton
class TvdbLoginIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val tvdbApi: TvdbApi
) : TvdbLoginGateway {
    override suspend fun requestToken(
        apiKey: String,
        pin: String,
        nowMillis: () -> Long
    ): TvdbAuthResult {
        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TVDB,
                apiShapeId = TvdbApiShapes.LOGIN,
                operationKey = "tvdb.login",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    runCatching {
                        tvdbApi.login(
                            TvdbLoginRequest(
                                apikey = apiKey,
                                pin = pin.takeIf { it.isNotBlank() }
                            )
                        )
                    }.fold(
                        onSuccess = { response ->
                            if (!response.isSuccessful) {
                                IntegrationCallResult.HttpError(response.code())
                            } else {
                                response.body()?.let { IntegrationCallResult.Success(it) }
                                    ?: IntegrationCallResult.Missing
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

        return when (result) {
            is IntegrationCallResult.Success -> {
                val token = result.value.data?.token.orEmpty()
                if (token.isNotBlank()) {
                    TvdbAuthResult.Valid(
                        authorizationHeader = "Bearer $token",
                        expiresAtEpochMillis = nowMillis() + TVDB_TOKEN_TTL_MS
                    )
                } else {
                    val reason = "TVDB auth response did not include credentials"
                    Log.w(TAG, "TVDB /login failed status=200 reason=missing-auth-data")
                    TvdbAuthResult.AuthUnavailable(reason)
                }
            }
            is IntegrationCallResult.HttpError -> {
                if (result.statusCode == 401) {
                    val reason = "Invalid TVDB credentials"
                    Log.w(TAG, "TVDB /login failed status=${result.statusCode} reason=http-${result.statusCode}")
                    TvdbAuthResult.InvalidCredentials(reason)
                } else {
                    val reason = "TVDB login failed with HTTP ${result.statusCode}"
                    Log.w(TAG, "TVDB /login failed status=${result.statusCode} reason=http-${result.statusCode}")
                    TvdbAuthResult.AuthUnavailable(reason)
                }
            }
            is IntegrationCallResult.NetworkError -> {
                val reason = "TVDB login failed: ${result.throwable.javaClass.simpleName}"
                Log.w(TAG, "TVDB /login failed status=exception reason=${result.throwable.javaClass.simpleName}")
                TvdbAuthResult.AuthUnavailable(reason)
            }
            IntegrationCallResult.Missing -> {
                val reason = "TVDB auth response did not include credentials"
                Log.w(TAG, "TVDB /login failed status=missing reason=missing-auth-data")
                TvdbAuthResult.AuthUnavailable(reason)
            }
        }
    }
}
