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
import com.nexio.tv.data.repository.benchmark.AudioEncodingSupport
import com.nexio.tv.data.repository.benchmark.CodecSupport
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
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkThroughputBucketSample
import com.nexio.tv.data.repository.benchmark.DeviceAudioOutputCapabilities
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import com.nexio.tv.data.repository.benchmark.DeviceHdrType
import com.nexio.tv.data.repository.benchmark.DeviceVideoDecodeCapabilities
import com.nexio.tv.data.repository.benchmark.toJsonObject
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
            preferences[latestResultKey(result.provider)] = result.toJsonObject().toString()
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
            val device = root.optionalObject("device")?.let(::parseDeviceSnapshot)
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
                device = device,
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
        return candidate != null ||
            device != null ||
            session != null ||
            direct != null ||
            optimized != null ||
            comparison != null
    }

    private fun DebridBenchmarkResult.comparisonPayloadIsValid(): Boolean {
        if (!hasComparisonPayload()) return true
        return candidate?.isValid() == true &&
            device?.isValid() != false &&
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

    private fun DeviceCapabilitySnapshot.isValid(): Boolean {
        return sdkInt > 0 &&
            capturedAtMs > 0L &&
            videoDecode.isValid() &&
            audioOutput.isValid()
    }

    private fun DeviceVideoDecodeCapabilities.isValid(): Boolean {
        return listOf(h264, hevc, av1, dolbyVision).all { it?.isValid() != false }
    }

    private fun CodecSupport.isValid(): Boolean {
        return true
    }

    private fun DeviceAudioOutputCapabilities.isValid(): Boolean {
        return listOf(ac3, eac3, truehd, dts, dtshd).all { it.isValid() }
    }

    private fun AudioEncodingSupport.isValid(): Boolean {
        return !passthroughLikely || supported
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
        return collectorVersion > 0 &&
            samplingMode?.isNotBlank() != false &&
            bucketMs?.let { it > 0L } != false &&
            averageThroughputMbps.isNonNegativeFiniteOrNull() &&
            derivedAverageThroughputMbps.isNonNegativeFiniteOrNull() &&
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

    private fun DebridBenchmarkThroughputBucketSample.isValid(): Boolean {
        return startOffsetMs >= 0L &&
            durationMs > 0L &&
            bytesTransferred >= 0L &&
            throughputMbps.isFinite() &&
            throughputMbps >= 0.0
    }

    private fun DebridBenchmarkRawSamples.isValid(): Boolean {
        return throughputWindowsMbps.all { it.isFinite() && it >= 0.0 } &&
            throughputBuckets.all { it.isValid() } &&
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

    private fun parseCandidate(candidateJson: JsonObject): DebridBenchmarkCandidateMetadata {
        return DebridBenchmarkCandidateMetadata(
            filename = candidateJson.stringOrNull("filename"),
            sizeBytes = candidateJson.optionalStrictIntegralLongOrNull("sizeBytes"),
            host = candidateJson.stringOrNull("host"),
            directUrlFingerprint = candidateJson.stringOrNull("directUrlFingerprint")
        )
    }

    private fun parseDeviceSnapshot(deviceJson: JsonObject): DeviceCapabilitySnapshot {
        return DeviceCapabilitySnapshot(
            model = deviceJson.stringOrNull("model"),
            manufacturer = deviceJson.stringOrNull("manufacturer"),
            sdkInt = deviceJson.strictIntegralIntOrNull("sdkInt")?.takeIf { it > 0 }
                ?: throw InvalidDebridBenchmarkPayload(),
            displayHdrTypes = deviceJson.arrayOrEmpty("displayHdrTypes").map { hdrType ->
                hdrType.asStringOrThrow().let(DeviceHdrType::fromWireKey)
                    ?: throw InvalidDebridBenchmarkPayload()
            }.toSet(),
            videoDecode = deviceJson.requiredObject("videoDecode").let(::parseVideoDecode),
            audioOutput = deviceJson.requiredObject("audioOutput").let(::parseAudioOutput),
            capturedAtMs = deviceJson.strictIntegralLongOrNull("capturedAtMs")?.takeIf { it > 0L }
                ?: throw InvalidDebridBenchmarkPayload()
        )
    }

    private fun parseVideoDecode(videoDecodeJson: JsonObject): DeviceVideoDecodeCapabilities {
        return DeviceVideoDecodeCapabilities(
            h264 = videoDecodeJson.optionalObject("h264")?.let(::parseCodecSupport),
            hevc = videoDecodeJson.optionalObject("hevc")?.let(::parseCodecSupport),
            av1 = videoDecodeJson.optionalObject("av1")?.let(::parseCodecSupport),
            dolbyVision = videoDecodeJson.optionalObject("dolbyVision")?.let(::parseCodecSupport)
        )
    }

    private fun parseCodecSupport(codecJson: JsonObject): CodecSupport {
        return CodecSupport(
            hardwareAccelerated = codecJson.strictBooleanOrNull("hardwareAccelerated")
                ?: throw InvalidDebridBenchmarkPayload(),
            softwareOnlyAvailable = codecJson.strictBooleanOrNull("softwareOnlyAvailable")
                ?: throw InvalidDebridBenchmarkPayload(),
            secureSupported = codecJson.strictBooleanOrNull("secureSupported")
                ?: throw InvalidDebridBenchmarkPayload()
        )
    }

    private fun parseAudioOutput(audioOutputJson: JsonObject): DeviceAudioOutputCapabilities {
        return DeviceAudioOutputCapabilities(
            ac3 = audioOutputJson.requiredObject("ac3").let(::parseAudioEncodingSupport),
            eac3 = audioOutputJson.requiredObject("eac3").let(::parseAudioEncodingSupport),
            truehd = audioOutputJson.requiredObject("truehd").let(::parseAudioEncodingSupport),
            dts = audioOutputJson.requiredObject("dts").let(::parseAudioEncodingSupport),
            dtshd = audioOutputJson.requiredObject("dtshd").let(::parseAudioEncodingSupport)
        )
    }

    private fun parseAudioEncodingSupport(audioEncodingJson: JsonObject): AudioEncodingSupport {
        return AudioEncodingSupport(
            supported = audioEncodingJson.strictBooleanOrNull("supported")
                ?: throw InvalidDebridBenchmarkPayload(),
            passthroughLikely = audioEncodingJson.strictBooleanOrNull("passthroughLikely")
                ?: throw InvalidDebridBenchmarkPayload()
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
                collectorVersion = sustainedJson.optionalStrictIntegralIntOrNull("collectorVersion") ?: 1,
                samplingMode = sustainedJson.stringOrNull("samplingMode"),
                bucketMs = sustainedJson.optionalStrictIntegralLongOrNull("bucketMs"),
                averageThroughputMbps = sustainedJson.optionalStrictDoubleOrNull("averageThroughputMbps"),
                derivedAverageThroughputMbps = sustainedJson.optionalStrictDoubleOrNull("derivedAverageThroughputMbps"),
                actionable = sustainedJson.optionalStrictBooleanOrNull("actionable") ?: true,
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
                throughputBuckets = rawSamplesJson?.arrayOrEmpty("throughputBuckets")?.map { sample ->
                    val bucketJson = sample.asJsonObjectOrThrow()
                    DebridBenchmarkThroughputBucketSample(
                        startOffsetMs = bucketJson.strictIntegralLongOrNull("startOffsetMs")
                            ?.takeIf { it >= 0L }
                            ?: throw InvalidDebridBenchmarkPayload(),
                        durationMs = bucketJson.strictIntegralLongOrNull("durationMs")
                            ?.takeIf { it > 0L }
                            ?: throw InvalidDebridBenchmarkPayload(),
                        throughputMbps = bucketJson.optionalStrictDoubleOrNull("throughputMbps")
                            ?.takeIf { it.isFinite() && it >= 0.0 }
                            ?: throw InvalidDebridBenchmarkPayload(),
                        bytesTransferred = bucketJson.strictIntegralLongOrNull("bytesTransferred")
                            ?.takeIf { it >= 0L }
                            ?: (((bucketJson.optionalStrictDoubleOrNull("throughputMbps")
                            ?: throw InvalidDebridBenchmarkPayload()) * requireNotNull(bucketJson.strictIntegralLongOrNull("durationMs")) * 1_000.0) / 8.0)
                                .toLong(),
                        complete = bucketJson.strictBooleanOrNull("complete")
                            ?: throw InvalidDebridBenchmarkPayload()
                    )
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
            "device",
            "session",
            "direct",
            "optimized",
            "comparison"
        )
    }

    private class InvalidDebridBenchmarkPayload : IllegalArgumentException()
}
