package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.JsonObject
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
    name = "debrid_benchmark",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Singleton
class DebridBenchmarkStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
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
            preferences[latestResultKey(result.provider)] = canonicalPayload(result).toString()
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

            val measuredAtMs = root.strictIntegralLongOrNull("measuredAtMs")?.takeIf { it > 0L } ?: return null
            val summaryJson = root.get("summary")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val summary = DebridBenchmarkSummary(
                startupTimeMs = summaryJson.optionalStrictIntegralLongOrNull("startupTimeMs"),
                sustainedThroughputMbps = summaryJson.optionalStrictDoubleOrNull("sustainedThroughputMbps"),
                transferredBytes = summaryJson.strictIntegralLongOrNull("transferredBytes")?.takeIf { it >= 0L }
                    ?: return null,
                elapsedMs = summaryJson.strictIntegralLongOrNull("elapsedMs")?.takeIf { it >= 0L } ?: return null
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

    private fun canonicalPayload(result: DebridBenchmarkResult): JsonObject {
        return JsonObject().apply {
            addProperty("provider", result.provider.name)
            addProperty("measuredAtMs", result.measuredAtMs)
            add("summary", JsonObject().apply {
                result.summary.startupTimeMs?.let { addProperty("startupTimeMs", it) }
                result.summary.sustainedThroughputMbps?.let { addProperty("sustainedThroughputMbps", it) }
                addProperty("transferredBytes", result.summary.transferredBytes)
                addProperty("elapsedMs", result.summary.elapsedMs)
            })
            addProperty("terminationReason", result.terminationReason.name)
        }
    }

    private fun com.google.gson.JsonObject.stringOrNull(key: String): String? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

    private fun JsonObject.strictIntegralLongOrNull(key: String): Long? {
        val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        if (!primitive.isNumber) return null
        val text = primitive.asString.trim()
        if (!text.matches(INTEGRAL_NUMBER_REGEX)) return null
        return text.toLongOrNull()
    }

    private fun JsonObject.optionalStrictIntegralLongOrNull(key: String): Long? {
        if (!has(key) || get(key)?.isJsonNull == true) return null
        return strictIntegralLongOrNull(key)?.takeIf { it >= 0L } ?: throw InvalidDebridBenchmarkPayload()
    }

    private fun JsonObject.optionalStrictDoubleOrNull(key: String): Double? {
        if (!has(key) || get(key)?.isJsonNull == true) return null
        val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: throw InvalidDebridBenchmarkPayload()
        if (!primitive.isNumber) throw InvalidDebridBenchmarkPayload()
        val value = runCatching { primitive.asDouble }.getOrNull() ?: throw InvalidDebridBenchmarkPayload()
        return value.takeIf { it.isFinite() && it >= 0.0 } ?: throw InvalidDebridBenchmarkPayload()
    }

    companion object {
        private val INTEGRAL_NUMBER_REGEX = Regex("^-?\\d+$")
    }

    private class InvalidDebridBenchmarkPayload : IllegalArgumentException()
}
