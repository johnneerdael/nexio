package com.nexio.tv.data.repository

import com.nexio.tv.data.local.TorBoxSettingsDataStore
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.debrid.TorBoxIntegrationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class TorBoxAccountState(
    val apiKey: String = "",
    val email: String? = null,
    val plan: String? = null,
    val isConnected: Boolean = false,
    val errorMessage: String? = null
)

@Singleton
class TorBoxService @Inject constructor(
    private val torBoxIntegrationProvider: TorBoxIntegrationProvider,
    private val torBoxSettingsDataStore: TorBoxSettingsDataStore
) {
    private val _accountState = MutableStateFlow(TorBoxAccountState())
    val accountState: StateFlow<TorBoxAccountState> = _accountState.asStateFlow()

    fun observeAccountState(): Flow<TorBoxAccountState> = accountState

    suspend fun validateAndSaveApiKey(rawValue: String): Result<TorBoxAccountState> {
        val apiKey = rawValue.trim()
        if (apiKey.isBlank()) {
            torBoxSettingsDataStore.setApiKey("")
            val cleared = TorBoxAccountState()
            _accountState.value = cleared
            return Result.success(cleared)
        }

        return when (val result = torBoxIntegrationProvider.fetchAccountInfo(apiKey)) {
            is IntegrationCallResult.Success -> {
                val body = result.value
                if (body?.success != true) {
                    val errorMessage = "Invalid TorBox API key"
                    _accountState.value = TorBoxAccountState(
                        apiKey = apiKey,
                        isConnected = false,
                        errorMessage = errorMessage
                    )
                    Result.failure(IllegalStateException(errorMessage))
                } else {
                    torBoxSettingsDataStore.setApiKey(apiKey)
                    val state = TorBoxAccountState(
                        apiKey = apiKey,
                        email = body.data?.email,
                        plan = body.data?.plan,
                        isConnected = true
                    )
                    _accountState.value = state
                    Result.success(state)
                }
            }

            is IntegrationCallResult.NetworkError -> Result.failure(
                run {
                    val errorMessage = result.throwable.message ?: "Failed to contact TorBox"
                    _accountState.value = TorBoxAccountState(
                        apiKey = apiKey,
                        isConnected = false,
                        errorMessage = errorMessage
                    )
                    IllegalStateException(errorMessage)
                }
            )

            is IntegrationCallResult.HttpError -> Result.failure(
                run {
                    val errorMessage = result.reason ?: "Invalid TorBox API key"
                    _accountState.value = TorBoxAccountState(
                        apiKey = apiKey,
                        isConnected = false,
                        errorMessage = errorMessage
                    )
                    IllegalStateException(errorMessage)
                }
            )

            else -> {
                val errorMessage = "Invalid TorBox API key"
                _accountState.value = TorBoxAccountState(
                    apiKey = apiKey,
                    isConnected = false,
                    errorMessage = errorMessage
                )
                Result.failure(IllegalStateException(errorMessage))
            }
        }
    }

    suspend fun refreshAccountState() {
        val apiKey = torBoxSettingsDataStore.settings.first().apiKey.trim()
        if (apiKey.isBlank()) {
            _accountState.value = TorBoxAccountState()
            return
        }

        val result = torBoxIntegrationProvider.fetchAccountInfo(apiKey)
        if (result is IntegrationCallResult.Success && result.value?.success == true) {
            _accountState.value = TorBoxAccountState(
                apiKey = apiKey,
                email = result.value?.data?.email,
                plan = result.value?.data?.plan,
                isConnected = true
            )
            return
        }

        _accountState.value = TorBoxAccountState(
            apiKey = apiKey,
            isConnected = false,
            errorMessage = when (result) {
                is IntegrationCallResult.HttpError -> result.reason ?: "TorBox authentication failed"
                else -> "TorBox authentication failed"
            }
        )
    }
}
