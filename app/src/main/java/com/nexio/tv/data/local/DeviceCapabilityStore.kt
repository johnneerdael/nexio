package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import com.nexio.tv.data.repository.benchmark.parseDeviceCapabilitySnapshotJson
import com.nexio.tv.data.repository.benchmark.toJsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceCapabilityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "device_capability",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

interface DeviceCapabilityStoreContract {
    val latestSnapshot: Flow<DeviceCapabilitySnapshot?>
    suspend fun save(snapshot: DeviceCapabilitySnapshot)
}

@Singleton
class DeviceCapabilityStore internal constructor(
    private val dataStore: DataStore<Preferences>
) : DeviceCapabilityStoreContract {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.deviceCapabilityDataStore)

    override val latestSnapshot: Flow<DeviceCapabilitySnapshot?> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences ->
            preferences[deviceCapabilitySnapshotKey]?.let(::parseDeviceCapabilitySnapshotJson)
        }
        .distinctUntilChanged()

    override suspend fun save(snapshot: DeviceCapabilitySnapshot) {
        dataStore.edit { preferences ->
            preferences[deviceCapabilitySnapshotKey] = snapshot.toJsonObject().toString()
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(deviceCapabilitySnapshotKey)
        }
    }

    private companion object {
        val deviceCapabilitySnapshotKey = stringPreferencesKey("device_capability_snapshot")
    }
}
