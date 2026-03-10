package com.nexio.tv.data.repository

import com.nexio.tv.data.local.PremiumizeSettingsDataStore
import com.nexio.tv.data.remote.api.PremiumizeApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class PremiumizeAccountState(
    val apiKey: String = "",
    val customerId: Int? = null,
    val premiumUntil: Long? = null,
    val isConnected: Boolean = false,
    val errorMessage: String? = null
)

@Singleton
class PremiumizeService @Inject constructor(
    private val premiumizeApi: PremiumizeApi,
    private val premiumizeSettingsDataStore: PremiumizeSettingsDataStore
) {
    private val _accountState = MutableStateFlow(PremiumizeAccountState())
    val accountState: StateFlow<PremiumizeAccountState> = _accountState.asStateFlow()

    fun observeAccountState(): Flow<PremiumizeAccountState> = accountState

    suspend fun validateAndSaveApiKey(rawValue: String): Result<PremiumizeAccountState> {
        val apiKey = rawValue.trim()
        if (apiKey.isBlank()) {
            premiumizeSettingsDataStore.setApiKey("")
            val cleared = PremiumizeAccountState()
            _accountState.value = cleared
            return Result.success(cleared)
        }

        val response = runCatching { premiumizeApi.getAccountInfo(apiKey) }
            .getOrElse { error ->
                return Result.failure(
                    IllegalStateException(error.message ?: "Failed to contact Premiumize")
                )
            }

        val body = response.body()
        if (!response.isSuccessful || body?.status?.equals("success", ignoreCase = true) != true) {
            return Result.failure(IllegalStateException("Invalid Premiumize API key"))
        }

        premiumizeSettingsDataStore.setApiKey(apiKey)
        val state = PremiumizeAccountState(
            apiKey = apiKey,
            customerId = body.customerId,
            premiumUntil = body.premiumUntil,
            isConnected = true
        )
        _accountState.value = state
        return Result.success(state)
    }

    suspend fun refreshAccountState() {
        val apiKey = premiumizeSettingsDataStore.settings.first().apiKey.trim()
        if (apiKey.isBlank()) {
            _accountState.value = PremiumizeAccountState()
            return
        }

        val response = runCatching { premiumizeApi.getAccountInfo(apiKey) }.getOrNull()
        val body = response?.body()
        if (response?.isSuccessful == true && body?.status?.equals("success", ignoreCase = true) == true) {
            _accountState.value = PremiumizeAccountState(
                apiKey = apiKey,
                customerId = body.customerId,
                premiumUntil = body.premiumUntil,
                isConnected = true
            )
            return
        }

        _accountState.value = PremiumizeAccountState(
            apiKey = apiKey,
            isConnected = false,
            errorMessage = "Premiumize authentication failed"
        )
    }
}
