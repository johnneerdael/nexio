package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.supabaseSessionBackupDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "supabase_session_backup"
)

data class SupabaseSessionBackup(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val userId: String? = null,
    val email: String? = null,
    val updatedAtEpochMillis: Long? = null
) {
    val hasTokens: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()

    val hasIdentity: Boolean
        get() = !userId.isNullOrBlank() && !email.isNullOrBlank()
}

@Singleton
class SupabaseSessionBackupDataStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore = context.supabaseSessionBackupDataStore
    private val accessTokenKey = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val userIdKey = stringPreferencesKey("user_id")
    private val emailKey = stringPreferencesKey("email")
    private val updatedAtEpochMillisKey = longPreferencesKey("updated_at_epoch_millis")

    val session: Flow<SupabaseSessionBackup> = dataStore.data.map { prefs ->
        SupabaseSessionBackup(
            accessToken = prefs[accessTokenKey],
            refreshToken = prefs[refreshTokenKey],
            userId = prefs[userIdKey],
            email = prefs[emailKey],
            updatedAtEpochMillis = prefs[updatedAtEpochMillisKey]
        )
    }

    suspend fun snapshot(): SupabaseSessionBackup {
        return session.first()
    }

    suspend fun save(
        accessToken: String,
        refreshToken: String,
        userId: String,
        email: String
    ) {
        val normalizedAccessToken = accessToken.trim()
        val normalizedRefreshToken = refreshToken.trim()
        val normalizedUserId = userId.trim()
        val normalizedEmail = email.trim()
        if (
            normalizedAccessToken.isBlank() ||
            normalizedRefreshToken.isBlank() ||
            normalizedUserId.isBlank() ||
            normalizedEmail.isBlank()
        ) {
            return
        }
        dataStore.edit { prefs ->
            prefs[accessTokenKey] = normalizedAccessToken
            prefs[refreshTokenKey] = normalizedRefreshToken
            prefs[userIdKey] = normalizedUserId
            prefs[emailKey] = normalizedEmail
            prefs[updatedAtEpochMillisKey] = System.currentTimeMillis()
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(accessTokenKey)
            prefs.remove(refreshTokenKey)
            prefs.remove(userIdKey)
            prefs.remove(emailKey)
            prefs.remove(updatedAtEpochMillisKey)
        }
    }
}
