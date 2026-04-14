package com.nexio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class FakeProfileDataStoreFactory(private val testDir: File) {
    private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val key = if (profileId == 1) featureName else "${featureName}_p$profileId"
        return stores.getOrPut(key) {
            PreferenceDataStoreFactory.create { File(testDir, "$key.preferences_pb") }
        }
    }
}
