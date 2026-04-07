package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidateMetadata
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportConfigSnapshot
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkSessionSummary
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkStatus
import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import com.nexio.tv.data.repository.benchmark.toJsonObject
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
    private val dataStore: DataStore<Preferences>
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
            preferences[latestResultKey(result.provider)] = result.toJsonObject().toString()
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
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        return try {
            val provider = root.stringOrNull("provider")
                ?.let(DebridBenchmarkProvider::fromStorageKey)
                ?: return null
            if (provider != expectedProvider) return null

            val measuredAtMs = root.strictIntegralLongOrNull("measuredAtMs")?.takeIf { it > 0L } ?: return null
            val candidate = root.optionalObject("candidate")?.let(::parseCandidate)
                ?: DebridBenchmarkCandidateMetadata()
            val summary = root.requiredObject("summary").let(::parseSummary)
            val profiles = root.requiredArray("orderedProfileResults").map { element ->
                parseProfileResult(element.asJsonObject)
            }

            val migratedSummary = migrateEnvelopeIfNeeded(summary, provider)
            DebridConfigBenchmarkResult(
                provider = provider,
                measuredAtMs = measuredAtMs,
                candidate = candidate,
                summary = migratedSummary,
                profiles = profiles
            ).takeIf { it.isValid() }
        } catch (_: InvalidDebridConfigBenchmarkPayload) {
            null
        }
    }
}

/**
 * For RD/PM: if the persisted [CapabilityEnvelope] has a shape that diverges from the locked
 * constants (e.g., a legacy 8 MiB urgent value), silently discard it. The cold-start locked
 * shape will be synthesised by [toCapabilityEnvelope] on the next read. Does NOT throw.
 */
private fun migrateEnvelopeIfNeeded(
    summary: DebridConfigBenchmarkSessionSummary,
    provider: DebridBenchmarkProvider
): DebridConfigBenchmarkSessionSummary {
    val stored = summary.capabilityEnvelope ?: return summary
    val locked = CapabilityEnvelope.lockedFor(provider.storageKey) ?: return summary
    return if (locked.matchesLockedShape(stored)) {
        summary
    } else {
        summary.copy(capabilityEnvelope = null)
    }
}

private fun parseCandidate(candidateJson: JsonObject): DebridBenchmarkCandidateMetadata {
    return DebridBenchmarkCandidateMetadata(
        filename = candidateJson.stringOrNull("filename"),
        sizeBytes = candidateJson.optionalStrictIntegralLongOrNull("sizeBytes"),
        host = candidateJson.stringOrNull("host"),
        directUrlFingerprint = candidateJson.stringOrNull("directUrlFingerprint")
    )
}

private fun parseSummary(summaryJson: JsonObject): DebridConfigBenchmarkSessionSummary {
    return DebridConfigBenchmarkSessionSummary(
        totalProfileCount = summaryJson.strictIntegralIntOrThrow("totalProfiles"),
        successfulProfileCount = summaryJson.strictIntegralIntOrThrow("successfulProfiles"),
        failedProfileCount = summaryJson.strictIntegralIntOrThrow("failedProfiles"),
        unsupportedProfileCount = summaryJson.strictIntegralIntOrThrow("unsupportedProfiles"),
        totalElapsedMs = summaryJson.optionalStrictIntegralLongOrNull("totalElapsedMs"),
        bestProfile = summaryJson.optionalObject("bestProfile")?.let(::parseProfileResult),
        capabilityEnvelope = summaryJson.optionalObject("capabilityEnvelope")?.let { envelopeJson ->
            CapabilityEnvelope.fromJson(envelopeJson.toString())
        },
        runtimeTransportHints = summaryJson.optionalObject("runtimeTransportHints")?.let { hintsJson ->
            RuntimeTransportHintsV2(
                artifactVersion = hintsJson.strictIntegralIntOrThrow("artifactVersion"),
                serviceKey = hintsJson.stringOrNull("serviceKey")
                    ?: throw InvalidDebridConfigBenchmarkPayload(),
                measuredAtMs = hintsJson.strictIntegralLongOrNull("measuredAtMs")
                    ?.takeIf { it > 0L }
                    ?: throw InvalidDebridConfigBenchmarkPayload(),
                observedTransportClass = hintsJson.stringOrNull("observedTransportClass"),
                observedHostScope = hintsJson.stringOrNull("observedHostScope"),
                recommendedUrgentChunkBytes = hintsJson.optionalStrictIntegralLongOrNull("recommendedUrgentChunkBytes"),
                recommendedUrgentWorkers = hintsJson.optionalStrictIntegralIntOrNull("recommendedUrgentWorkers"),
                connectionBudgetHint = hintsJson.optionalStrictIntegralIntOrNull("connectionBudgetHint"),
                retryMode = hintsJson.stringOrNull("retryMode")
            )
        }
    )
}

private fun parseProfileResult(profileJson: JsonObject): DebridConfigBenchmarkProfileResult {
    val profileMetadata = profileJson.requiredObject("profile")
    val configSnapshot = profileJson.optionalObject("configSnapshot")?.let { snapshotJson ->
        DebridBenchmarkTransportConfigSnapshot(
            useParallelConnections = snapshotJson.optionalStrictBooleanOrNull("useParallelConnections"),
            parallelConnectionCount = snapshotJson.optionalStrictIntegralIntOrNull("parallelConnectionCount"),
            parallelChunkSizeMb = snapshotJson.optionalStrictIntegralIntOrNull("parallelChunkSizeMb")
        )
    }
    return DebridConfigBenchmarkProfileResult(
        parallelConnectionCount = profileMetadata.strictIntegralIntOrThrow("parallelConnectionCount"),
        chunkSizeMb = profileMetadata.strictIntegralIntOrThrow("chunkSizeMb"),
        status = profileJson.stringOrNull("status")
            ?.let(DebridConfigBenchmarkStatus::fromWireKey)
            ?: throw InvalidDebridConfigBenchmarkPayload(),
        averageThroughputMbps = profileJson.optionalStrictDoubleOrNull("averageThroughputMbps"),
        transferredBytes = profileJson.optionalStrictIntegralLongOrNull("transferredBytes"),
        elapsedMs = profileJson.optionalStrictIntegralLongOrNull("elapsedMs"),
        failureReason = profileJson.stringOrNull("failureReason"),
        unsupportedReason = profileJson.stringOrNull("unsupportedReason"),
        configSnapshot = configSnapshot
    )
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
    if (runtimeTransportHints?.let { it.isValid() } == false) return false
    return totalProfileCount == profiles.size &&
        successfulProfileCount == profiles.count { it.status == DebridConfigBenchmarkStatus.SUCCESS } &&
        failedProfileCount == profiles.count { it.status == DebridConfigBenchmarkStatus.FAILED } &&
        unsupportedProfileCount == profiles.count { it.status == DebridConfigBenchmarkStatus.UNSUPPORTED }
}

private fun RuntimeTransportHintsV2.isValid(): Boolean {
    if (artifactVersion <= 0) return false
    if (serviceKey.isBlank()) return false
    if (measuredAtMs <= 0L) return false
    if (recommendedUrgentChunkBytes?.let { it <= 0L } == true) return false
    if (recommendedUrgentWorkers?.let { it <= 0 } == true) return false
    if (connectionBudgetHint?.let { it <= 0 } == true) return false
    return true
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

private fun JsonObject.stringOrNull(key: String): String? {
    return runCatching {
        get(key)?.takeIf { !it.isJsonNull }?.asString
    }.getOrNull()
}

private fun JsonObject.optionalObject(key: String): JsonObject? {
    val value = get(key) ?: return null
    if (value.isJsonNull) return null
    return value.takeIf { it.isJsonObject }?.asJsonObject ?: throw InvalidDebridConfigBenchmarkPayload()
}

private fun JsonObject.requiredObject(key: String): JsonObject {
    return optionalObject(key) ?: throw InvalidDebridConfigBenchmarkPayload()
}

private fun JsonObject.requiredArray(key: String) =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        ?: throw InvalidDebridConfigBenchmarkPayload()

private fun JsonObject.strictIntegralLongOrNull(key: String): Long? {
    val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if (!primitive.isNumber) return null
    val text = primitive.asString.trim()
    if (!text.matches(INTEGRAL_NUMBER_REGEX)) return null
    return text.toLongOrNull()
}

private fun JsonObject.optionalStrictIntegralLongOrNull(key: String): Long? {
    if (!has(key) || get(key)?.isJsonNull == true) return null
    return strictIntegralLongOrNull(key)?.takeIf { it >= 0L } ?: throw InvalidDebridConfigBenchmarkPayload()
}

private fun JsonObject.strictIntegralIntOrThrow(key: String): Int {
    return strictIntegralLongOrNull(key)
        ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
        ?.toInt()
        ?: throw InvalidDebridConfigBenchmarkPayload()
}

private fun JsonObject.optionalStrictIntegralIntOrNull(key: String): Int? {
    if (!has(key) || get(key)?.isJsonNull == true) return null
    return strictIntegralLongOrNull(key)
        ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
        ?.toInt()
        ?: throw InvalidDebridConfigBenchmarkPayload()
}

private fun JsonObject.optionalStrictDoubleOrNull(key: String): Double? {
    if (!has(key) || get(key)?.isJsonNull == true) return null
    val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        ?: throw InvalidDebridConfigBenchmarkPayload()
    if (!primitive.isNumber) throw InvalidDebridConfigBenchmarkPayload()
    val value = runCatching { primitive.asDouble }.getOrNull()
        ?: throw InvalidDebridConfigBenchmarkPayload()
    return value.takeIf { it.isFinite() && it >= 0.0 }
        ?: throw InvalidDebridConfigBenchmarkPayload()
}

private fun JsonObject.optionalStrictBooleanOrNull(key: String): Boolean? {
    if (!has(key) || get(key)?.isJsonNull == true) return null
    val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        ?: throw InvalidDebridConfigBenchmarkPayload()
    if (!primitive.isBoolean) throw InvalidDebridConfigBenchmarkPayload()
    return primitive.asBoolean
}

private val INTEGRAL_NUMBER_REGEX = Regex("^-?\\d+$")

private class InvalidDebridConfigBenchmarkPayload : IllegalArgumentException()
