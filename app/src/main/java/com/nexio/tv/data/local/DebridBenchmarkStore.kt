package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidateMetadata
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkComparisonSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkPhase
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkPhaseExecution
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkRawSamples
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSeekMetrics
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSeekSample
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSessionMetadata
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkStartupMetrics
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSustainedMetrics
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTerminationReason
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportConfigSnapshot
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportMode
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
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
        require(result.isCompletedAndValid()) { "Invalid DebridBenchmarkResult" }
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
            val parsedProvider = DebridBenchmarkProvider.fromStorageKey(providerName)
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
                DebridBenchmarkTerminationReason.fromWireKey(reason)
            } ?: return null
            if (terminationReason != DebridBenchmarkTerminationReason.COMPLETED) return null
            val comparisonPayloadPresent = COMPARISON_PAYLOAD_KEYS.any(root::has)
            val candidate = root.optionalObject("candidate")?.let(::parseCandidate)
            val session = root.optionalObject("session")?.let(::parseSession)
            val direct = root.optionalObject("direct")?.let(::parseTransportProfile)
            val optimized = root.optionalObject("optimized")?.let(::parseTransportProfile)
            val comparison = root.optionalObject("comparison")?.let(::parseComparisonSummary)

            if (comparisonPayloadPresent &&
                (candidate == null || session == null || direct == null || optimized == null || comparison == null)
            ) {
                return null
            }

            DebridBenchmarkResult(
                provider = parsedProvider,
                measuredAtMs = measuredAtMs,
                summary = summary,
                terminationReason = terminationReason,
                candidate = candidate,
                session = session,
                direct = direct,
                optimized = optimized,
                comparison = comparison
            ).takeIf { it.isCompletedAndValid() }
        } catch (_: InvalidDebridBenchmarkPayload) {
            null
        }
    }

    private fun DebridBenchmarkResult.isValid(): Boolean {
        return measuredAtMs > 0L && summary.isValid() && comparisonPayloadIsValid()
    }

    private fun DebridBenchmarkResult.isCompletedAndValid(): Boolean {
        return terminationReason == DebridBenchmarkTerminationReason.COMPLETED &&
            measuredAtMs > 0L &&
            summary.isCompletedValid() &&
            comparisonPayloadIsCompletedValid()
    }

    private fun DebridBenchmarkResult.hasComparisonPayload(): Boolean {
        return candidate != null || session != null || direct != null || optimized != null || comparison != null
    }

    private fun DebridBenchmarkResult.comparisonPayloadIsValid(): Boolean {
        if (!hasComparisonPayload()) return true
        return candidate?.isValid() == true &&
            session?.isValid() == true &&
            direct?.isValid() == true &&
            optimized?.isValid() == true &&
            comparison?.isValid() == true
    }

    private fun DebridBenchmarkResult.comparisonPayloadIsCompletedValid(): Boolean {
        if (!hasComparisonPayload()) return true
        return comparisonPayloadIsValid() &&
            direct?.isCompletedValid() == true &&
            optimized?.isCompletedValid() == true
    }

    private fun DebridBenchmarkSummary.isValid(): Boolean {
        return startupTimeMs?.let { it >= 0L } != false &&
            sustainedThroughputMbps?.let { it.isFinite() && it >= 0.0 } != false &&
            transferredBytes >= 0L &&
            elapsedMs >= 0L
    }

    private fun DebridBenchmarkSummary.isCompletedValid(): Boolean {
        return startupTimeMs != null &&
            sustainedThroughputMbps != null &&
            isValid()
    }

    private fun DebridBenchmarkCandidateMetadata.isValid(): Boolean {
        return sizeBytes?.let { it >= 0L } != false
    }

    private fun DebridBenchmarkSessionMetadata.isValid(): Boolean {
        return benchmarkVersion > 0 &&
            totalElapsedMs?.let { it >= 0L } != false &&
            executionOrder.all { it.isValid() }
    }

    private fun DebridBenchmarkPhaseExecution.isValid(): Boolean {
        return order.isNotEmpty()
    }

    private fun DebridBenchmarkStartupMetrics.isValid(): Boolean {
        return initialTtfbMs?.let { it >= 0L } != false &&
            startupFailureRate?.let { it.isFinite() && it in 0.0..1.0 } != false
    }

    private fun DebridBenchmarkStartupMetrics.isCompletedValid(): Boolean {
        return initialTtfbMs != null &&
            startupFailureRate != null &&
            isValid()
    }

    private fun DebridBenchmarkSustainedMetrics.isValid(): Boolean {
        return averageThroughputMbps.isNonNegativeFiniteOrNull() &&
            p10ThroughputMbps.isNonNegativeFiniteOrNull() &&
            p50ThroughputMbps.isNonNegativeFiniteOrNull() &&
            peakThroughputMbps.isNonNegativeFiniteOrNull() &&
            throughputStddevMbps.isNonNegativeFiniteOrNull() &&
            throughputCv.isNonNegativeFiniteOrNull() &&
            stallCount?.let { it >= 0 } != false &&
            maxReadGapMs?.let { it >= 0L } != false &&
            bytesTransferred?.let { it >= 0L } != false &&
            elapsedMs?.let { it >= 0L } != false
    }

    private fun DebridBenchmarkSustainedMetrics.isCompletedValid(): Boolean {
        return averageThroughputMbps != null &&
            p10ThroughputMbps != null &&
            p50ThroughputMbps != null &&
            peakThroughputMbps != null &&
            throughputStddevMbps != null &&
            throughputCv != null &&
            stallCount != null &&
            maxReadGapMs != null &&
            bytesTransferred != null &&
            elapsedMs != null &&
            isValid()
    }

    private fun DebridBenchmarkSeekMetrics.isValid(): Boolean {
        return seekTtfbP50Ms?.let { it >= 0L } != false &&
            seekTtfbP95Ms?.let { it >= 0L } != false &&
            seekTtfbP99Ms?.let { it >= 0L } != false &&
            seekTtfbStddevMs.isNonNegativeFiniteOrNull() &&
            seekFailRate?.let { it.isFinite() && it in 0.0..1.0 } != false
    }

    private fun DebridBenchmarkSeekMetrics.isCompletedValid(): Boolean {
        return seekTtfbP50Ms != null &&
            seekTtfbP95Ms != null &&
            seekTtfbP99Ms != null &&
            seekTtfbStddevMs != null &&
            seekFailRate != null &&
            isValid()
    }

    private fun DebridBenchmarkTransportConfigSnapshot.isValid(): Boolean {
        return useParallelConnections != null &&
            parallelConnectionCount?.let { it > 0 } != false &&
            parallelChunkSizeMb?.let { it > 0 } != false
    }

    private fun DebridBenchmarkSeekSample.isValid(): Boolean {
        return targetOffsetBytes >= 0L &&
            ttfbMs?.let { it >= 0L } != false &&
            (!succeeded || ttfbMs != null)
    }

    private fun DebridBenchmarkRawSamples.isValid(): Boolean {
        return throughputWindowsMbps.all { it.isFinite() && it >= 0.0 } &&
            seekSamples.all { it.isValid() }
    }

    private fun DebridBenchmarkTransportProfile.isValid(): Boolean {
        return startup.isValid() &&
            sustained.isValid() &&
            seek.isValid() &&
            configSnapshot?.isValid() != false &&
            rawSamples.isValid()
    }

    private fun DebridBenchmarkTransportProfile.isCompletedValid(): Boolean {
        return startup.isCompletedValid() &&
            sustained.isCompletedValid() &&
            seek.isCompletedValid() &&
            isValid()
    }

    private fun DebridBenchmarkComparisonSummary.isValid(): Boolean {
        return sustainedWinner != null &&
            seekWinner != null &&
            stabilityWinner != null
    }

    private fun canonicalPayload(result: DebridBenchmarkResult): JsonObject {
        return JsonObject().apply {
            addProperty("provider", result.provider.storageKey)
            addProperty("measuredAtMs", result.measuredAtMs)
            add("summary", JsonObject().apply {
                result.summary.startupTimeMs?.let { addProperty("startupTimeMs", it) }
                result.summary.sustainedThroughputMbps?.let { addProperty("sustainedThroughputMbps", it) }
                addProperty("transferredBytes", result.summary.transferredBytes)
                addProperty("elapsedMs", result.summary.elapsedMs)
            })
            addProperty("terminationReason", result.terminationReason.wireKey)
            result.candidate?.let { candidate ->
                add("candidate", JsonObject().apply {
                    candidate.filename?.let { addProperty("filename", it) }
                    candidate.sizeBytes?.let { addProperty("sizeBytes", it) }
                    candidate.host?.let { addProperty("host", it) }
                    candidate.directUrlFingerprint?.let { addProperty("directUrlFingerprint", it) }
                })
            }
            result.session?.let { session ->
                add("session", JsonObject().apply {
                    addProperty("benchmarkVersion", session.benchmarkVersion)
                    add("executionOrder", JsonArray().apply {
                        session.executionOrder.forEach { phase ->
                            add(JsonObject().apply {
                                addProperty("phase", phase.phase.wireKey)
                                add("order", JsonArray().apply {
                                    phase.order.forEach { mode ->
                                        add(mode.wireKey)
                                    }
                                })
                            })
                        }
                    })
                    session.totalElapsedMs?.let { addProperty("totalElapsedMs", it) }
                })
            }
            result.direct?.let { add("direct", canonicalTransportProfile(it)) }
            result.optimized?.let { add("optimized", canonicalTransportProfile(it)) }
            result.comparison?.let { comparison ->
                add("comparison", JsonObject().apply {
                    comparison.sustainedWinner?.let { addProperty("sustainedWinner", it.wireKey) }
                    comparison.seekWinner?.let { addProperty("seekWinner", it.wireKey) }
                    comparison.stabilityWinner?.let { addProperty("stabilityWinner", it.wireKey) }
                })
            }
        }
    }

    private fun canonicalTransportProfile(profile: DebridBenchmarkTransportProfile): JsonObject {
        return JsonObject().apply {
            add("startup", JsonObject().apply {
                profile.startup.initialTtfbMs?.let { addProperty("initialTtfbMs", it) }
                profile.startup.startupFailureRate?.let { addProperty("startupFailureRate", it) }
            })
            add("sustained", JsonObject().apply {
                profile.sustained.averageThroughputMbps?.let { addProperty("averageThroughputMbps", it) }
                profile.sustained.p10ThroughputMbps?.let { addProperty("p10ThroughputMbps", it) }
                profile.sustained.p50ThroughputMbps?.let { addProperty("p50ThroughputMbps", it) }
                profile.sustained.peakThroughputMbps?.let { addProperty("peakThroughputMbps", it) }
                profile.sustained.throughputStddevMbps?.let { addProperty("throughputStddevMbps", it) }
                profile.sustained.throughputCv?.let { addProperty("throughputCv", it) }
                profile.sustained.stallCount?.let { addProperty("stallCount", it) }
                profile.sustained.maxReadGapMs?.let { addProperty("maxReadGapMs", it) }
                profile.sustained.bytesTransferred?.let { addProperty("bytesTransferred", it) }
                profile.sustained.elapsedMs?.let { addProperty("elapsedMs", it) }
            })
            add("seek", JsonObject().apply {
                profile.seek.seekTtfbP50Ms?.let { addProperty("seekTtfbP50Ms", it) }
                profile.seek.seekTtfbP95Ms?.let { addProperty("seekTtfbP95Ms", it) }
                profile.seek.seekTtfbP99Ms?.let { addProperty("seekTtfbP99Ms", it) }
                profile.seek.seekTtfbStddevMs?.let { addProperty("seekTtfbStddevMs", it) }
                profile.seek.seekFailRate?.let { addProperty("seekFailRate", it) }
            })
            profile.configSnapshot?.let { config ->
                add("configSnapshot", JsonObject().apply {
                    config.useParallelConnections?.let { addProperty("useParallelConnections", it) }
                    config.parallelConnectionCount?.let { addProperty("parallelConnectionCount", it) }
                    config.parallelChunkSizeMb?.let { addProperty("parallelChunkSizeMb", it) }
                })
            }
            add("rawSamples", JsonObject().apply {
                add("throughputWindowsMbps", JsonArray().apply {
                    profile.rawSamples.throughputWindowsMbps.forEach { add(it) }
                })
                add("seekSamples", JsonArray().apply {
                    profile.rawSamples.seekSamples.forEach { sample ->
                        add(JsonObject().apply {
                            addProperty("targetOffsetBytes", sample.targetOffsetBytes)
                            sample.ttfbMs?.let { addProperty("ttfbMs", it) }
                            addProperty("succeeded", sample.succeeded)
                        })
                    }
                })
            })
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

    private fun parseSession(sessionJson: JsonObject): DebridBenchmarkSessionMetadata {
        val benchmarkVersion = sessionJson.strictIntegralIntOrNull("benchmarkVersion")?.takeIf { it > 0 }
            ?: throw InvalidDebridBenchmarkPayload()
        val executionOrder = sessionJson.arrayOrEmpty("executionOrder").map { execution ->
            val executionJson = execution.asJsonObjectOrThrow()
            val phase = executionJson.stringOrNull("phase")
                ?.let(DebridBenchmarkPhase::fromWireKey)
                ?: throw InvalidDebridBenchmarkPayload()
            val order = executionJson.arrayOrEmpty("order").map { mode ->
                mode.asStringOrThrow().let(DebridBenchmarkTransportMode::fromWireKey)
                    ?: throw InvalidDebridBenchmarkPayload()
            }
            DebridBenchmarkPhaseExecution(
                phase = phase,
                order = order
            )
        }
        return DebridBenchmarkSessionMetadata(
            benchmarkVersion = benchmarkVersion,
            executionOrder = executionOrder,
            totalElapsedMs = sessionJson.optionalStrictIntegralLongOrNull("totalElapsedMs")
        )
    }

    private fun parseTransportProfile(profileJson: JsonObject): DebridBenchmarkTransportProfile {
        val startupJson = profileJson.requiredObject("startup")
        val sustainedJson = profileJson.requiredObject("sustained")
        val seekJson = profileJson.requiredObject("seek")
        val rawSamplesJson = profileJson.optionalObject("rawSamples")
        return DebridBenchmarkTransportProfile(
            startup = DebridBenchmarkStartupMetrics(
                initialTtfbMs = startupJson.optionalStrictIntegralLongOrNull("initialTtfbMs"),
                startupFailureRate = startupJson.optionalStrictDoubleOrNull("startupFailureRate")
            ),
            sustained = DebridBenchmarkSustainedMetrics(
                averageThroughputMbps = sustainedJson.optionalStrictDoubleOrNull("averageThroughputMbps"),
                p10ThroughputMbps = sustainedJson.optionalStrictDoubleOrNull("p10ThroughputMbps"),
                p50ThroughputMbps = sustainedJson.optionalStrictDoubleOrNull("p50ThroughputMbps"),
                peakThroughputMbps = sustainedJson.optionalStrictDoubleOrNull("peakThroughputMbps"),
                throughputStddevMbps = sustainedJson.optionalStrictDoubleOrNull("throughputStddevMbps"),
                throughputCv = sustainedJson.optionalStrictDoubleOrNull("throughputCv"),
                stallCount = sustainedJson.optionalStrictIntegralIntOrNull("stallCount"),
                maxReadGapMs = sustainedJson.optionalStrictIntegralLongOrNull("maxReadGapMs"),
                bytesTransferred = sustainedJson.optionalStrictIntegralLongOrNull("bytesTransferred"),
                elapsedMs = sustainedJson.optionalStrictIntegralLongOrNull("elapsedMs")
            ),
            seek = DebridBenchmarkSeekMetrics(
                seekTtfbP50Ms = seekJson.optionalStrictIntegralLongOrNull("seekTtfbP50Ms"),
                seekTtfbP95Ms = seekJson.optionalStrictIntegralLongOrNull("seekTtfbP95Ms"),
                seekTtfbP99Ms = seekJson.optionalStrictIntegralLongOrNull("seekTtfbP99Ms"),
                seekTtfbStddevMs = seekJson.optionalStrictDoubleOrNull("seekTtfbStddevMs"),
                seekFailRate = seekJson.optionalStrictDoubleOrNull("seekFailRate")
            ),
            configSnapshot = profileJson.optionalObject("configSnapshot")?.let { configJson ->
                DebridBenchmarkTransportConfigSnapshot(
                    useParallelConnections = configJson.optionalStrictBooleanOrNull("useParallelConnections"),
                    parallelConnectionCount = configJson.optionalStrictIntegralIntOrNull("parallelConnectionCount"),
                    parallelChunkSizeMb = configJson.optionalStrictIntegralIntOrNull("parallelChunkSizeMb")
                )
            },
            rawSamples = DebridBenchmarkRawSamples(
                throughputWindowsMbps = rawSamplesJson?.arrayOrEmpty("throughputWindowsMbps")?.map { sample ->
                    sample.asStrictNonNegativeDouble()
                } ?: emptyList(),
                seekSamples = rawSamplesJson?.arrayOrEmpty("seekSamples")?.map { sample ->
                    val seekSampleJson = sample.asJsonObjectOrThrow()
                    DebridBenchmarkSeekSample(
                        targetOffsetBytes = seekSampleJson.strictIntegralLongOrNull("targetOffsetBytes")
                            ?.takeIf { it >= 0L }
                            ?: throw InvalidDebridBenchmarkPayload(),
                        ttfbMs = seekSampleJson.optionalStrictIntegralLongOrNull("ttfbMs"),
                        succeeded = seekSampleJson.strictBooleanOrNull("succeeded")
                            ?: throw InvalidDebridBenchmarkPayload()
                    )
                } ?: emptyList()
            )
        )
    }

    private fun parseComparisonSummary(comparisonJson: JsonObject): DebridBenchmarkComparisonSummary {
        return DebridBenchmarkComparisonSummary(
            sustainedWinner = comparisonJson.stringOrNull("sustainedWinner")
                ?.let(DebridBenchmarkTransportMode::fromWireKey),
            seekWinner = comparisonJson.stringOrNull("seekWinner")
                ?.let(DebridBenchmarkTransportMode::fromWireKey),
            stabilityWinner = comparisonJson.stringOrNull("stabilityWinner")
                ?.let(DebridBenchmarkTransportMode::fromWireKey)
        )
    }

    private fun com.google.gson.JsonObject.stringOrNull(key: String): String? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

    private fun JsonObject.optionalObject(key: String): JsonObject? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return value.takeIf { it.isJsonObject }?.asJsonObject ?: throw InvalidDebridBenchmarkPayload()
    }

    private fun JsonObject.requiredObject(key: String): JsonObject {
        return optionalObject(key) ?: throw InvalidDebridBenchmarkPayload()
    }

    private fun JsonObject.arrayOrEmpty(key: String) =
        get(key)?.let { value ->
            if (!value.isJsonArray) throw InvalidDebridBenchmarkPayload()
            value.asJsonArray.asList()
        } ?: emptyList()

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

    private fun JsonObject.strictIntegralIntOrNull(key: String): Int? {
        return strictIntegralLongOrNull(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    private fun JsonObject.optionalStrictIntegralIntOrNull(key: String): Int? {
        if (!has(key) || get(key)?.isJsonNull == true) return null
        return strictIntegralIntOrNull(key)?.takeIf { it >= 0 } ?: throw InvalidDebridBenchmarkPayload()
    }

    private fun JsonObject.optionalStrictDoubleOrNull(key: String): Double? {
        if (!has(key) || get(key)?.isJsonNull == true) return null
        val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: throw InvalidDebridBenchmarkPayload()
        if (!primitive.isNumber) throw InvalidDebridBenchmarkPayload()
        val value = runCatching { primitive.asDouble }.getOrNull() ?: throw InvalidDebridBenchmarkPayload()
        return value.takeIf { it.isFinite() && it >= 0.0 } ?: throw InvalidDebridBenchmarkPayload()
    }

    private fun JsonObject.strictBooleanOrNull(key: String): Boolean? {
        val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        if (!primitive.isBoolean) return null
        return primitive.asBoolean
    }

    private fun JsonObject.optionalStrictBooleanOrNull(key: String): Boolean? {
        if (!has(key) || get(key)?.isJsonNull == true) return null
        return strictBooleanOrNull(key) ?: throw InvalidDebridBenchmarkPayload()
    }

    private fun com.google.gson.JsonElement.asJsonObjectOrThrow(): JsonObject {
        return takeIf { it.isJsonObject }?.asJsonObject ?: throw InvalidDebridBenchmarkPayload()
    }

    private fun com.google.gson.JsonElement.asStringOrThrow(): String {
        return takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw InvalidDebridBenchmarkPayload()
    }

    private fun com.google.gson.JsonElement.asStrictNonNegativeDouble(): Double {
        val primitive = takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: throw InvalidDebridBenchmarkPayload()
        if (!primitive.isNumber) throw InvalidDebridBenchmarkPayload()
        val value = primitive.asDouble
        return value.takeIf { it.isFinite() && it >= 0.0 } ?: throw InvalidDebridBenchmarkPayload()
    }

    private fun Double?.isNonNegativeFiniteOrNull(): Boolean {
        return this?.let { it.isFinite() && it >= 0.0 } != false
    }

    companion object {
        private val INTEGRAL_NUMBER_REGEX = Regex("^-?\\d+$")
        private val COMPARISON_PAYLOAD_KEYS = setOf(
            "candidate",
            "session",
            "direct",
            "optimized",
            "comparison"
        )
    }

    private class InvalidDebridBenchmarkPayload : IllegalArgumentException()
}
