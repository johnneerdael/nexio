package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
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
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
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
        return dataStore.data
            .catch { throwable ->
                if (throwable is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw throwable
                }
            }
            .map { preferences ->
                preferences[key]?.let { raw ->
                    parseResult(raw, provider)
                }
            }
            .distinctUntilChanged()
    }

    suspend fun saveLatest(result: DebridBenchmarkResult) {
        require(result.isValid()) { "Invalid DebridBenchmarkResult" }
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
        return try {
            val providerName = root.stringOrNull("provider")?.trim() ?: return null
            val parsedProvider = runCatching { DebridBenchmarkProvider.valueOf(providerName) }.getOrNull()
                ?: return null
            if (parsedProvider != expectedProvider) return null

            val measuredAtMs = root.longOrNull("measuredAtMs")?.takeIf { it > 0L } ?: return null
            val summaryJson = root.get("summary")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val summary = DebridBenchmarkSummary(
                startupTimeMs = summaryJson.optionalLongOrNull("startupTimeMs"),
                sustainedThroughputMbps = summaryJson.optionalDoubleOrNull("sustainedThroughputMbps"),
                transferredBytes = summaryJson.longOrNull("transferredBytes")?.takeIf { it >= 0L }
                    ?: return null,
                elapsedMs = summaryJson.longOrNull("elapsedMs")?.takeIf { it >= 0L } ?: return null
            )
            val terminationReason = root.stringOrNull("terminationReason")?.let { reason ->
                runCatching { DebridBenchmarkTerminationReason.valueOf(reason) }.getOrNull()
            } ?: return null

            DebridBenchmarkResult(
                provider = parsedProvider,
                measuredAtMs = measuredAtMs,
                summary = summary,
                terminationReason = terminationReason
            ).takeIf { it.isValid() }
        } catch (_: InvalidDebridBenchmarkPayload) {
            null
        }
    }

    private fun DebridBenchmarkResult.isValid(): Boolean {
        return measuredAtMs > 0L && summary.isValid()
    }

    private fun DebridBenchmarkSummary.isValid(): Boolean {
        return startupTimeMs?.let { it >= 0L } != false &&
            sustainedThroughputMbps?.let { it.isFinite() && it >= 0.0 } != false &&
            transferredBytes >= 0L &&
            elapsedMs >= 0L
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

    private fun com.google.gson.JsonObject.optionalLongOrNull(key: String): Long? {
        if (!has(key) || get(key)?.isJsonNull == true) return null
        return longOrNull(key)?.takeIf { it >= 0L } ?: throw InvalidDebridBenchmarkPayload()
    }

    private fun com.google.gson.JsonObject.optionalDoubleOrNull(key: String): Double? {
        if (!has(key) || get(key)?.isJsonNull == true) return null
        return doubleOrNull(key)?.takeIf { it.isFinite() && it >= 0.0 }
            ?: throw InvalidDebridBenchmarkPayload()
    }

    private class InvalidDebridBenchmarkPayload : IllegalArgumentException()
}
