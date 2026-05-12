package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.debrid.TorBoxIntegrationProvider
import com.nexio.tv.data.remote.dto.debrid.TorBoxDeviceCodeDataDto
import dagger.Lazy
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

data class TorBoxDeviceFlow(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val friendlyVerificationUrl: String,
    val intervalSeconds: Int,
    val expiresAtMillis: Long
)

sealed class TorBoxStartDeviceFlowResult {
    data class Success(val flow: TorBoxDeviceFlow) : TorBoxStartDeviceFlowResult()
    data class Failed(val message: String) : TorBoxStartDeviceFlowResult()
}

sealed class TorBoxTokenPollResult {
    data object Pending : TorBoxTokenPollResult()
    data class Approved(val apiKey: String) : TorBoxTokenPollResult()
    data object Expired : TorBoxTokenPollResult()
    data class Failed(val message: String) : TorBoxTokenPollResult()
}

@Singleton
class TorBoxAuthService @Inject constructor(
    private val torBoxIntegrationProvider: Lazy<TorBoxIntegrationProvider>
) {
    suspend fun startDeviceAuth(): TorBoxStartDeviceFlowResult {
        val result = torBoxIntegrationProvider.get().startDeviceCode()
        return when (result) {
            is IntegrationCallResult.Success -> {
                val data = result.value?.data
                val flow = data?.toFlow()
                if (flow == null) {
                    TorBoxStartDeviceFlowResult.Failed("TorBox returned an incomplete device-code payload.")
                } else {
                    TorBoxStartDeviceFlowResult.Success(flow)
                }
            }

            is IntegrationCallResult.NetworkError ->
                TorBoxStartDeviceFlowResult.Failed(
                    result.throwable.message ?: "Network error, please try again."
                )

            is IntegrationCallResult.HttpError ->
                TorBoxStartDeviceFlowResult.Failed(
                    result.reason ?: "TorBox device-code request failed."
                )

            else -> TorBoxStartDeviceFlowResult.Failed("TorBox device-code request failed.")
        }
    }

    suspend fun pollDeviceToken(deviceCode: String): TorBoxTokenPollResult {
        if (deviceCode.isBlank()) {
            return TorBoxTokenPollResult.Failed("No active TorBox device code.")
        }
        val result = torBoxIntegrationProvider.get().pollDeviceToken(deviceCode)
        return when (result) {
            is IntegrationCallResult.Success -> {
                val apiKey = result.value?.data?.resolvedApiKey()
                if (apiKey.isNullOrBlank()) {
                    // TorBox returns 200 with success=true but no token payload while the user is still approving.
                    TorBoxTokenPollResult.Pending
                } else {
                    TorBoxTokenPollResult.Approved(apiKey)
                }
            }

            is IntegrationCallResult.HttpError -> when (result.statusCode) {
                410 -> TorBoxTokenPollResult.Expired
                400, 403, 404, 425 -> TorBoxTokenPollResult.Pending
                else -> TorBoxTokenPollResult.Failed(result.reason ?: "TorBox token poll failed (${result.statusCode}).")
            }

            is IntegrationCallResult.NetworkError ->
                TorBoxTokenPollResult.Failed(result.throwable.message ?: "Network error.")

            else -> TorBoxTokenPollResult.Failed("TorBox token poll failed.")
        }
    }

    private fun TorBoxDeviceCodeDataDto.toFlow(): TorBoxDeviceFlow? {
        val deviceCode = deviceCode?.trim().orEmpty()
        val userCode = code?.trim().orEmpty()
        if (deviceCode.isEmpty() || userCode.isEmpty()) return null

        val resolvedInterval = interval?.takeIf { it > 0 } ?: DEFAULT_INTERVAL_SECONDS
        val resolvedExpiresAt = parseExpiresAtMillis(expiresAt) ?: (System.currentTimeMillis() + DEFAULT_EXPIRY_MS)
        return TorBoxDeviceFlow(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = verificationUrl?.trim().orEmpty(),
            friendlyVerificationUrl = friendlyVerificationUrl?.trim().orEmpty(),
            intervalSeconds = resolvedInterval,
            expiresAtMillis = resolvedExpiresAt
        )
    }

    private fun parseExpiresAtMillis(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private companion object {
        private const val DEFAULT_INTERVAL_SECONDS = 5
        private const val DEFAULT_EXPIRY_MS = 10L * 60L * 1000L
    }
}
