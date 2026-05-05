package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot

interface ProviderSettingsRepository {
    suspend fun validateOmdbApiKey(apiKey: String): Boolean
    suspend fun validateMDBListApiKey(apiKey: String): Boolean
    suspend fun validateTmdbApiKey(apiKey: String): Boolean
    suspend fun validateAnimeSkipClientId(clientId: String): Boolean
    suspend fun validateRpdbApiKey(apiKey: String): Boolean
    suspend fun validateTopPostersApiKey(
        apiKey: String,
        forceRefresh: Boolean = false
    ): TopPostersEntitlementSnapshot?

    suspend fun isTopPostersApiKeyValid(apiKey: String): Boolean =
        validateTopPostersApiKey(apiKey)?.valid == true
}
