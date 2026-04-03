package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidateMetadata
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportConfigSnapshot
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkSessionSummary
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.debridConfigBenchmarkDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "debrid_config_benchmark",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Singleton
class DebridConfigBenchmarkStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val gson: Gson = Gson()
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.debridConfigBenchmarkDataStore)

    fun latestResult(provider: DebridBenchmarkProvider): Flow<DebridConfigBenchmarkResult?> {
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
                preferences[key]?.let { raw -> parseResult(raw, provider) }
            }
            .distinctUntilChanged()
    }

    suspend fun saveLatest(result: DebridConfigBenchmarkResult) {
        require(result.isValid()) { "Invalid DebridConfigBenchmarkResult" }
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
        stringPreferencesKey("debrid_config_benchmark_latest_${provider.storageKey}")

    private fun parseResult(
        raw: String,
        expectedProvider: DebridBenchmarkProvider
    ): DebridConfigBenchmarkResult? {
        val parsed = runCatching {
            gson.fromJson(raw, DebridConfigBenchmarkResult::class.java)
        }.getOrNull() ?: return null
        if (parsed.provider != expectedProvider) return null
        return parsed.takeIf { it.isValid() }
    }
}

private fun DebridConfigBenchmarkResult.isValid(): Boolean {
    return measuredAtMs > 0L &&
        candidate.isValid() &&
        profiles.isNotEmpty() &&
        profiles.all { it.isValid() } &&
        summary.isValidFor(profiles)
}

private fun DebridBenchmarkCandidateMetadata.isValid(): Boolean {
    return sizeBytes?.let { it >= 0L } != false
}

private fun DebridConfigBenchmarkSessionSummary.isValidFor(
    profiles: List<DebridConfigBenchmarkProfileResult>
): Boolean {
    if (totalProfileCount < 0 ||
        successfulProfileCount < 0 ||
        failedProfileCount < 0 ||
        unsupportedProfileCount < 0
    ) {
        return false
    }
    if (successfulProfileCount + failedProfileCount + unsupportedProfileCount != totalProfileCount) {
        return false
    }
    if (totalElapsedMs?.let { it < 0L } == true) return false
    if (bestProfile != null && (bestProfile !in profiles || bestProfile.status != DebridConfigBenchmarkStatus.SUCCESS)) {
        return false
    }
    return totalProfileCount == profiles.size &&
        successfulProfileCount == profiles.count { it.status == DebridConfigBenchmarkStatus.SUCCESS } &&
        failedProfileCount == profiles.count { it.status == DebridConfigBenchmarkStatus.FAILED } &&
        unsupportedProfileCount == profiles.count { it.status == DebridConfigBenchmarkStatus.UNSUPPORTED }
}

private fun DebridConfigBenchmarkProfileResult.isValid(): Boolean {
    if (parallelConnectionCount <= 0 || chunkSizeMb <= 0) return false
    if (averageThroughputMbps?.let { !it.isFinite() || it < 0.0 } == true) return false
    if (transferredBytes?.let { it < 0L } == true) return false
    if (elapsedMs?.let { it < 0L } == true) return false
    if (configSnapshot?.isValid() == false) return false
    return when (status) {
        DebridConfigBenchmarkStatus.SUCCESS ->
            averageThroughputMbps != null &&
                failureReason.isNullOrBlank() &&
                unsupportedReason.isNullOrBlank()

        DebridConfigBenchmarkStatus.FAILED ->
            !failureReason.isNullOrBlank() && unsupportedReason.isNullOrBlank()

        DebridConfigBenchmarkStatus.UNSUPPORTED ->
            failureReason.isNullOrBlank() && !unsupportedReason.isNullOrBlank()
    }
}

private fun DebridBenchmarkTransportConfigSnapshot.isValid(): Boolean {
    return parallelConnectionCount?.let { it > 0 } != false &&
        parallelChunkSizeMb?.let { it > 0 } != false
}
