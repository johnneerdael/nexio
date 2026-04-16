package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.domain.model.TvdbValidationStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

typealias TvdbSettings = com.nexio.tv.domain.model.TvdbSettings

private val Context.tvdbSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tvdb_settings"
)

@Singleton
class TvdbSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: TvdbTokenStore
) {
    private val dataStore = context.tvdbSettingsDataStore
    private fun store() = dataStore

    private val enabledKey = booleanPreferencesKey("tvdb_enabled")
    private val apiKeyKey = stringPreferencesKey("tvdb_api_key")
    private val subscriberPinKey = stringPreferencesKey("tvdb_subscriber_pin")
    private val validationStatusKey = stringPreferencesKey("tvdb_validation_status")
    private val lastFailureKey = stringPreferencesKey("tvdb_last_failure")
    private val lastValidatedAtEpochMsKey = longPreferencesKey("tvdb_last_validated_at_epoch_ms")

    val settings: Flow<TvdbSettings> = dataStore.data.map { prefs ->
        val statusName = prefs[validationStatusKey]
        TvdbSettings(
            enabled = prefs[enabledKey] ?: true,
            apiKey = prefs[apiKeyKey] ?: "",
            subscriberPin = prefs[subscriberPinKey] ?: "",
            validationStatus = parseValidationStatus(statusName),
            lastFailure = prefs[lastFailureKey] ?: "",
            lastValidatedAtEpochMs = prefs[lastValidatedAtEpochMsKey]
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { prefs -> prefs[enabledKey] = true }
    }

    suspend fun setCredentials(apiKey: String, subscriberPin: String) {
        val trimmedApiKey = apiKey.trim()
        val trimmedPin = subscriberPin.trim()
        val current = settings.first()
        val credentialsChanged = current.apiKey != trimmedApiKey || current.subscriberPin != trimmedPin
        val nextStatus = if (trimmedApiKey.isBlank()) {
            TvdbValidationStatus.VALID
        } else {
            current.validationStatus
        }

        store().edit { prefs ->
            prefs[apiKeyKey] = trimmedApiKey
            prefs[subscriberPinKey] = trimmedPin
            prefs[validationStatusKey] = nextStatus.name
            if (trimmedApiKey.isBlank()) {
                prefs.remove(lastFailureKey)
                prefs.remove(lastValidatedAtEpochMsKey)
            }
        }

        if (credentialsChanged) {
            tokenStore.clear()
        }
    }

    suspend fun saveCredentials(
        apiKey: String,
        pin: String,
        validationStatus: TvdbValidationStatus
    ) {
        val trimmedApiKey = apiKey.trim()
        val trimmedPin = pin.trim()

        store().edit { prefs ->
            prefs[apiKeyKey] = trimmedApiKey
            prefs[subscriberPinKey] = trimmedPin
            prefs[validationStatusKey] = validationStatus.name
            prefs.remove(lastFailureKey)
            prefs[lastValidatedAtEpochMsKey] = System.currentTimeMillis()
        }
    }

    suspend fun saveValidationFailure(
        status: TvdbValidationStatus,
        lastFailure: String
    ) {
        store().edit { prefs ->
            prefs[validationStatusKey] = status.name
            prefs[lastFailureKey] = lastFailure
            prefs[lastValidatedAtEpochMsKey] = System.currentTimeMillis()
        }
    }

    suspend fun clearCredentials() {
        store().edit { prefs ->
            prefs.remove(apiKeyKey)
            prefs.remove(subscriberPinKey)
            prefs[validationStatusKey] = TvdbValidationStatus.VALID.name
            prefs.remove(lastFailureKey)
            prefs.remove(lastValidatedAtEpochMsKey)
            prefs[enabledKey] = true
        }
        tokenStore.clear()
    }

    private fun parseValidationStatus(statusName: String?): TvdbValidationStatus {
        return statusName
            ?.let { runCatching { TvdbValidationStatus.valueOf(it) }.getOrNull() }
            ?.takeUnless { it == TvdbValidationStatus.NOT_CONFIGURED }
            ?: TvdbValidationStatus.VALID
    }
}
