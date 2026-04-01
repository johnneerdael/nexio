package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.debridBenchmarkDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "debrid_benchmark"
)

@Singleton
class DebridBenchmarkStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val gson = Gson()

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.debridBenchmarkDataStore)

    fun latestResult(provider: DebridBenchmarkProvider): Flow<DebridBenchmarkResult?> {
        val key = latestResultKey(provider)
        return dataStore.data.map { preferences ->
            preferences[key]?.let { raw ->
                runCatching { gson.fromJson(raw, DebridBenchmarkResult::class.java) }.getOrNull()
            }
        }
    }

    suspend fun saveLatest(result: DebridBenchmarkResult) {
        dataStore.edit { preferences ->
            preferences[latestResultKey(result.provider)] = gson.toJson(result)
        }
    }

    suspend fun clear(provider: DebridBenchmarkProvider) {
        dataStore.edit { preferences ->
            preferences.remove(latestResultKey(provider))
        }
    }

    private fun latestResultKey(provider: DebridBenchmarkProvider) =
        stringPreferencesKey("debrid_benchmark_latest_${provider.storageKey}")
}
