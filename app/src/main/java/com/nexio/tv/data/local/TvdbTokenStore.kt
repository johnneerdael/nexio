package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tvdbTokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tvdb_token"
)

data class TvdbTokenState(
    val token: String = "",
    val expiresAtEpochMs: Long = 0L,
    val credentialFingerprint: String = ""
)

@Singleton
class TvdbTokenStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.tvdbTokenDataStore
    private fun store() = dataStore

    private val tokenKey = stringPreferencesKey("tvdb_bearer_token")
    private val expiresAtEpochMsKey = longPreferencesKey("tvdb_token_expires_at_epoch_ms")
    private val credentialFingerprintKey = stringPreferencesKey("tvdb_token_credential_fingerprint")

    val tokenState: Flow<TvdbTokenState> = dataStore.data.map { prefs ->
        TvdbTokenState(
            token = prefs[tokenKey] ?: "",
            expiresAtEpochMs = prefs[expiresAtEpochMsKey] ?: 0L,
            credentialFingerprint = prefs[credentialFingerprintKey] ?: ""
        )
    }

    suspend fun saveToken(
        token: String,
        expiresAtEpochMillis: Long,
        credentialFingerprint: String = ""
    ) {
        store().edit { prefs ->
            prefs[tokenKey] = token
            prefs[expiresAtEpochMsKey] = expiresAtEpochMillis
            prefs[credentialFingerprintKey] = credentialFingerprint
        }
    }

    suspend fun clear() {
        store().edit { prefs ->
            prefs.remove(tokenKey)
            prefs.remove(expiresAtEpochMsKey)
            prefs.remove(credentialFingerprintKey)
        }
    }
}
