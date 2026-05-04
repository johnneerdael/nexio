package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.debrid.PremiumizeIntegrationProvider
import com.nexio.tv.data.local.PremiumizeSettingsDataStore
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
    private val premiumizeIntegrationProvider: PremiumizeIntegrationProvider,
    private val premiumizeSettingsDataStore: PremiumizeSettingsDataStore
) {
    private val accountStateLock = Any()
    private var accountStateGeneration = 0L
    private val _accountState = MutableStateFlow(PremiumizeAccountState())
    val accountState: StateFlow<PremiumizeAccountState> = _accountState.asStateFlow()

    fun observeAccountState(): Flow<PremiumizeAccountState> = accountState

    fun clearLocalAccountState() {
        replaceAccountState(PremiumizeAccountState())
    }

    private fun replaceAccountState(state: PremiumizeAccountState) {
        synchronized(accountStateLock) {
            accountStateGeneration += 1L
            _accountState.value = state
        }
    }

    private fun currentAccountStateGeneration(): Long =
        synchronized(accountStateLock) { accountStateGeneration }

    private fun replaceRefreshedAccountState(
        observedGeneration: Long,
        observedApiKey: String,
        currentApiKey: String,
        state: PremiumizeAccountState
    ) {
        synchronized(accountStateLock) {
            if (accountStateGeneration != observedGeneration || currentApiKey != observedApiKey) {
                return
            }
            _accountState.value = state
        }
    }

    suspend fun validateAndSaveApiKey(rawValue: String): Result<PremiumizeAccountState> {
        val apiKey = rawValue.trim()
        if (apiKey.isBlank()) {
            premiumizeSettingsDataStore.setApiKey("")
            val cleared = PremiumizeAccountState()
            replaceAccountState(cleared)
            return Result.success(cleared)
        }

        return when (val result = premiumizeIntegrationProvider.fetchAccountInfo(apiKey)) {
            is IntegrationCallResult.Success -> {
                val body = result.value
                if (!body.status.equals("success", ignoreCase = true)) {
                    Result.failure(IllegalStateException("Invalid Premiumize API key"))
                } else {
                    premiumizeSettingsDataStore.setApiKey(apiKey)
                    val state = PremiumizeAccountState(
                        apiKey = apiKey,
                        customerId = body.customerId,
                        premiumUntil = body.premiumUntil,
                        isConnected = true
                    )
                    replaceAccountState(state)
                    Result.success(state)
                }
            }

            is IntegrationCallResult.NetworkError -> Result.failure(
                IllegalStateException(
                    result.throwable.message ?: "Failed to contact Premiumize"
                )
            )

            else -> Result.failure(IllegalStateException("Invalid Premiumize API key"))
        }
    }

    suspend fun refreshAccountState() {
        val apiKey = premiumizeSettingsDataStore.settings.first().apiKey.trim()
        if (apiKey.isBlank()) {
            replaceAccountState(PremiumizeAccountState())
            return
        }
        val observedGeneration = currentAccountStateGeneration()

        val result = premiumizeIntegrationProvider.fetchAccountInfo(apiKey)
        val currentApiKey = premiumizeSettingsDataStore.settings.first().apiKey.trim()
        if (result is IntegrationCallResult.Success &&
            result.value.status.equals("success", ignoreCase = true)
        ) {
            replaceRefreshedAccountState(
                observedGeneration = observedGeneration,
                observedApiKey = apiKey,
                currentApiKey = currentApiKey,
                state = PremiumizeAccountState(
                    apiKey = apiKey,
                    customerId = result.value.customerId,
                    premiumUntil = result.value.premiumUntil,
                    isConnected = true
                )
            )
            return
        }

        replaceRefreshedAccountState(
            observedGeneration = observedGeneration,
            observedApiKey = apiKey,
            currentApiKey = currentApiKey,
            state = PremiumizeAccountState(
                apiKey = apiKey,
                isConnected = false,
                errorMessage = "Premiumize authentication failed"
            )
        )
    }
}
