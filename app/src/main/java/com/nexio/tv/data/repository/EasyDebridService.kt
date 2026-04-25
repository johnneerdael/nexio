package com.nexio.tv.data.repository

import com.nexio.tv.data.local.EasyDebridSettingsDataStore
import com.nexio.tv.data.remote.api.EasyDebridApi
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
    private val easyDebridApi: EasyDebridApi,
    private val easyDebridSettingsDataStore: EasyDebridSettingsDataStore
) {
    private val accountStateLock = Any()
    private var accountStateGeneration = 0L
    private val _accountState = MutableStateFlow(EasyDebridAccountState())
    val accountState: StateFlow<EasyDebridAccountState> = _accountState.asStateFlow()

    fun observeAccountState(): Flow<EasyDebridAccountState> = accountState

    fun clearLocalAccountState() {
        replaceAccountState(EasyDebridAccountState())
    }

    private fun replaceAccountState(state: EasyDebridAccountState) {
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
        state: EasyDebridAccountState
    ) {
        synchronized(accountStateLock) {
            if (accountStateGeneration != observedGeneration || currentApiKey != observedApiKey) {
                return
            }
            _accountState.value = state
        }
    }

    suspend fun validateAndSaveApiKey(rawValue: String): Result<EasyDebridAccountState> {
        val apiKey = rawValue.trim()
        if (apiKey.isBlank()) {
            easyDebridSettingsDataStore.setApiKey("")
            val cleared = EasyDebridAccountState()
            replaceAccountState(cleared)
            return Result.success(cleared)
        }

        val response = runCatching {
            easyDebridApi.getUserDetails(authorization = "Bearer $apiKey")
        }.getOrElse { error ->
            return Result.failure(
                IllegalStateException(error.message ?: "Failed to contact EasyDebrid")
            )
        }

        val body = response.body()
        val userId = body?.id?.trim().orEmpty()
        if (!response.isSuccessful || userId.isBlank()) {
            return Result.failure(
                IllegalStateException(body?.message ?: body?.error ?: "Invalid EasyDebrid API key")
            )
        }

        easyDebridSettingsDataStore.setApiKey(apiKey)
        val state = EasyDebridAccountState(
            apiKey = apiKey,
            userId = userId,
            paidUntil = body?.paidUntil,
            isConnected = true
        )
        replaceAccountState(state)
        return Result.success(state)
    }

    suspend fun refreshAccountState() {
        val apiKey = easyDebridSettingsDataStore.settings.first().apiKey.trim()
        if (apiKey.isBlank()) {
            replaceAccountState(EasyDebridAccountState())
            return
        }
        val observedGeneration = currentAccountStateGeneration()

        val response = runCatching {
            easyDebridApi.getUserDetails(authorization = "Bearer $apiKey")
        }.getOrNull()
        val body = response?.body()
        val userId = body?.id?.trim().orEmpty()
        val currentApiKey = easyDebridSettingsDataStore.settings.first().apiKey.trim()
        if (response?.isSuccessful == true && userId.isNotBlank()) {
            replaceRefreshedAccountState(
                observedGeneration = observedGeneration,
                observedApiKey = apiKey,
                currentApiKey = currentApiKey,
                state = EasyDebridAccountState(
                    apiKey = apiKey,
                    userId = userId,
                    paidUntil = body?.paidUntil,
                    isConnected = true
                )
            )
            return
        }

        replaceRefreshedAccountState(
            observedGeneration = observedGeneration,
            observedApiKey = apiKey,
            currentApiKey = currentApiKey,
            state = EasyDebridAccountState(
                apiKey = apiKey,
                isConnected = false,
                errorMessage = body?.message ?: body?.error ?: "EasyDebrid authentication failed"
            )
        )
    }
}
