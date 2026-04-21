package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authPresenceDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_presence")

/**
 * Persists a non-sensitive "we had a valid Supabase session last run" marker.
 * Used so transient session-storage or decode races on cold start (including
 * post-upgrade launches) don't get mistaken for an authoritative sign-out and
 * flip the app to the QR re-auth screen.
 */
@Singleton
class AuthPresenceDataStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore = context.authPresenceDataStore
    private val hadAuthenticatedSessionKey = booleanPreferencesKey("had_authenticated_session")
    private val lastUserIdKey = stringPreferencesKey("last_supabase_user_id")

    val hadAuthenticatedSession: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[hadAuthenticatedSessionKey] ?: false
    }

    val lastUserId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[lastUserIdKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun markAuthenticated(userId: String) {
        val trimmed = userId.trim()
        if (trimmed.isBlank()) return
        dataStore.edit { prefs ->
            prefs[hadAuthenticatedSessionKey] = true
            prefs[lastUserIdKey] = trimmed
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(hadAuthenticatedSessionKey)
            prefs.remove(lastUserIdKey)
        }
    }
}
