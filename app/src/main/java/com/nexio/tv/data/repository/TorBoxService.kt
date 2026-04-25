package com.nexio.tv.data.repository

import com.nexio.tv.data.local.TorBoxSettingsDataStore
import com.nexio.tv.data.remote.api.TorBoxApi
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
    private val torBoxApi: TorBoxApi,
    private val torBoxSettingsDataStore: TorBoxSettingsDataStore
) {
    private val accountStateLock = Any()
    private var accountStateGeneration = 0L
    private val _accountState = MutableStateFlow(TorBoxAccountState())
    val accountState: StateFlow<TorBoxAccountState> = _accountState.asStateFlow()

    fun observeAccountState(): Flow<TorBoxAccountState> = accountState

    fun clearLocalAccountState() {
        replaceAccountState(TorBoxAccountState())
    }

    private fun replaceAccountState(state: TorBoxAccountState) {
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
        state: TorBoxAccountState
    ) {
        synchronized(accountStateLock) {
            if (accountStateGeneration != observedGeneration || currentApiKey != observedApiKey) {
                return
            }
            _accountState.value = state
        }
    }

    suspend fun validateAndSaveApiKey(rawValue: String): Result<TorBoxAccountState> {
        val apiKey = rawValue.trim()
        if (apiKey.isBlank()) {
            torBoxSettingsDataStore.setApiKey("")
            val cleared = TorBoxAccountState()
            replaceAccountState(cleared)
            return Result.success(cleared)
        }

        val response = runCatching {
            torBoxApi.getCurrentUser(authorization = "Bearer $apiKey")
        }.getOrElse { error ->
            return Result.failure(
                IllegalStateException(error.message ?: "Failed to contact TorBox")
            )
        }

        val body = response.body()
        if (!response.isSuccessful || body?.success == false) {
            return Result.failure(
                IllegalStateException(body?.detail ?: body?.error ?: "Invalid TorBox API key")
            )
        }

        torBoxSettingsDataStore.setApiKey(apiKey)
        val state = TorBoxAccountState(
            apiKey = apiKey,
            email = body?.data?.email,
            plan = body?.data?.plan,
            isConnected = true
        )
        replaceAccountState(state)
        return Result.success(state)
    }

    suspend fun refreshAccountState() {
        val apiKey = torBoxSettingsDataStore.settings.first().apiKey.trim()
        if (apiKey.isBlank()) {
            replaceAccountState(TorBoxAccountState())
            return
        }
        val observedGeneration = currentAccountStateGeneration()

        val response = runCatching {
            torBoxApi.getCurrentUser(authorization = "Bearer $apiKey")
        }.getOrNull()
        val body = response?.body()
        val currentApiKey = torBoxSettingsDataStore.settings.first().apiKey.trim()
        if (response?.isSuccessful == true && body?.success != false) {
            replaceRefreshedAccountState(
                observedGeneration = observedGeneration,
                observedApiKey = apiKey,
                currentApiKey = currentApiKey,
                state = TorBoxAccountState(
                    apiKey = apiKey,
                    email = body?.data?.email,
                    plan = body?.data?.plan,
                    isConnected = true
                )
            )
            return
        }

        replaceRefreshedAccountState(
            observedGeneration = observedGeneration,
            observedApiKey = apiKey,
            currentApiKey = currentApiKey,
            state = TorBoxAccountState(
                apiKey = apiKey,
                isConnected = false,
                errorMessage = body?.detail ?: body?.error ?: "TorBox authentication failed"
            )
        )
    }
}
