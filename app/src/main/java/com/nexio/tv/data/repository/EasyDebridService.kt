package com.nexio.tv.data.repository

import com.nexio.tv.data.local.EasyDebridSettingsDataStore
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.debrid.EasyDebridIntegrationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class EasyDebridAccountState(
    val apiKey: String = "",
    val userId: String? = null,
    val paidUntil: String? = null,
    val isConnected: Boolean = false,
    val errorMessage: String? = null
)

@Singleton
class EasyDebridService @Inject constructor(
    private val easyDebridIntegrationProvider: EasyDebridIntegrationProvider,
    private val easyDebridSettingsDataStore: EasyDebridSettingsDataStore
) {
    private val _accountState = MutableStateFlow(EasyDebridAccountState())
    val accountState: StateFlow<EasyDebridAccountState> = _accountState.asStateFlow()

    fun observeAccountState(): Flow<EasyDebridAccountState> = accountState

    suspend fun validateAndSaveApiKey(rawValue: String): Result<EasyDebridAccountState> {
        val apiKey = rawValue.trim()
        if (apiKey.isBlank()) {
            easyDebridSettingsDataStore.setApiKey("")
            val cleared = EasyDebridAccountState()
            _accountState.value = cleared
            return Result.success(cleared)
        }

        return when (val result = easyDebridIntegrationProvider.fetchAccountInfo(apiKey)) {
            is IntegrationCallResult.Success -> {
                val body = result.value
                val userId = body?.id?.trim().orEmpty()
                if (userId.isBlank()) {
                    val errorMessage =
                        body?.message ?: body?.error ?: "Invalid EasyDebrid API key"
                    _accountState.value = EasyDebridAccountState(
                        apiKey = apiKey,
                        isConnected = false,
                        errorMessage = errorMessage
                    )
                    Result.failure(IllegalStateException(errorMessage))
                } else {
                    easyDebridSettingsDataStore.setApiKey(apiKey)
                    val state = EasyDebridAccountState(
                        apiKey = apiKey,
                        userId = userId,
                        paidUntil = body?.paidUntil,
                        isConnected = true
                    )
                    _accountState.value = state
                    Result.success(state)
                }
            }

            is IntegrationCallResult.NetworkError -> {
                val errorMessage = result.throwable.message ?: "Failed to contact EasyDebrid"
                _accountState.value = EasyDebridAccountState(
                    apiKey = apiKey,
                    isConnected = false,
                    errorMessage = errorMessage
                )
                Result.failure(IllegalStateException(errorMessage))
            }

            else -> {
                val errorMessage = when (result) {
                    is IntegrationCallResult.HttpError -> result.reason
                    else -> "Invalid EasyDebrid API key"
                } ?: "Invalid EasyDebrid API key"
                _accountState.value = EasyDebridAccountState(
                    apiKey = apiKey,
                    isConnected = false,
                    errorMessage = errorMessage
                )
                Result.failure(IllegalStateException(errorMessage))
            }
        }
    }

    suspend fun refreshAccountState() {
        val apiKey = easyDebridSettingsDataStore.settings.first().apiKey.trim()
        if (apiKey.isBlank()) {
            _accountState.value = EasyDebridAccountState()
            return
        }

        val result = easyDebridIntegrationProvider.fetchAccountInfo(apiKey)
        val body = (result as? IntegrationCallResult.Success)?.value
        val userId = body?.id?.trim().orEmpty()
        if (result is IntegrationCallResult.Success && userId.isNotBlank()) {
            _accountState.value = EasyDebridAccountState(
                apiKey = apiKey,
                userId = userId,
                paidUntil = body?.paidUntil,
                isConnected = true
            )
            return
        }

        _accountState.value = EasyDebridAccountState(
            apiKey = apiKey,
            isConnected = false,
            errorMessage = when (result) {
                is IntegrationCallResult.HttpError -> result.reason
                is IntegrationCallResult.NetworkError -> result.throwable.message
                else -> null
            } ?: "EasyDebrid authentication failed"
        )
    }
}
