package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTerminationReason
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
                parseResult(raw, provider)
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

    private fun parseResult(
        raw: String,
        expectedProvider: DebridBenchmarkProvider
    ): DebridBenchmarkResult? {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val providerName = root.stringOrNull("provider")?.trim() ?: return null
        val parsedProvider = runCatching { DebridBenchmarkProvider.valueOf(providerName) }.getOrNull()
            ?: return null
        if (parsedProvider != expectedProvider) return null

        val measuredAtMs = root.longOrNull("measuredAtMs")?.takeIf { it > 0L } ?: return null
        val summaryJson = root.getAsJsonObject("summary") ?: return null
        val summary = DebridBenchmarkSummary(
            startupTimeMs = summaryJson.longOrNull("startupTimeMs"),
            sustainedThroughputMbps = summaryJson.doubleOrNull("sustainedThroughputMbps"),
            transferredBytes = summaryJson.longOrNull("transferredBytes") ?: return null,
            elapsedMs = summaryJson.longOrNull("elapsedMs") ?: return null
        )
        val terminationReason = root.stringOrNull("terminationReason")?.let { reason ->
            runCatching { DebridBenchmarkTerminationReason.valueOf(reason) }.getOrNull()
        } ?: return null

        return DebridBenchmarkResult(
            provider = parsedProvider,
            measuredAtMs = measuredAtMs,
            summary = summary,
            terminationReason = terminationReason
        )
    }

    private fun com.google.gson.JsonObject.stringOrNull(key: String): String? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

    private fun com.google.gson.JsonObject.longOrNull(key: String): Long? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asLong
        }.getOrNull()
    }

    private fun com.google.gson.JsonObject.doubleOrNull(key: String): Double? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asDouble
        }.getOrNull()
    }
}
