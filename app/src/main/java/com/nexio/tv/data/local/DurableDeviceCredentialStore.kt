package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.durableDeviceCredentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "durable_device_credential"
)

data class DurableDeviceCredentialSnapshot(
    val devicePublicId: String? = null,
    val deviceSecret: String? = null
) {
    val isComplete: Boolean
        get() = !devicePublicId.isNullOrBlank() && !deviceSecret.isNullOrBlank()
}

@Singleton
class DurableDeviceCredentialStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(context.durableDeviceCredentialDataStore)

    private val devicePublicIdKey = stringPreferencesKey("device_public_id")
    private val deviceSecretKey = stringPreferencesKey("device_secret")

    suspend fun snapshot(): DurableDeviceCredentialSnapshot {
        val prefs = dataStore.data.first()
        return DurableDeviceCredentialSnapshot(
            devicePublicId = prefs[devicePublicIdKey]?.trim()?.takeIf { it.isNotBlank() },
            deviceSecret = prefs[deviceSecretKey]?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun save(devicePublicId: String, deviceSecret: String) {
        val normalizedPublicId = devicePublicId.trim()
        val normalizedSecret = deviceSecret.trim()
        dataStore.edit { prefs ->
            if (normalizedPublicId.isBlank()) {
                prefs.remove(devicePublicIdKey)
            } else {
                prefs[devicePublicIdKey] = normalizedPublicId
            }
            if (normalizedSecret.isBlank()) {
                prefs.remove(deviceSecretKey)
            } else {
                prefs[deviceSecretKey] = normalizedSecret
            }
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(devicePublicIdKey)
            prefs.remove(deviceSecretKey)
        }
    }
}
