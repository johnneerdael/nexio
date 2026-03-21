package com.nexio.tv.debug.passthrough

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TransportValidationDiagnosticsExporter {

    private val json = Json
    private val jsonWithDefaults = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun exportBundle(
        session: TransportValidationSessionSnapshot,
        outputDirectory: File,
        includeBinaryDumps: Boolean,
    ): File {
        outputDirectory.mkdirs()
        val bundle = File(
            outputDirectory,
            "transport-validation-${session.sample.id}-${System.currentTimeMillis()}.zip"
        )
        ZipOutputStream(bundle.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("summary.json"))
            zip.write(
                json.encodeToString(
                    TransportValidationExportSummary(
                        manifestVersion = session.manifestVersion,
                        sample = session.sample,
                        transportVerdict = session.transportVerdict,
                        runtimeVerdict = session.runtimeSnapshot?.summary?.verdict
                            ?: TransportValidationRuntimeVerdict.UNKNOWN,
                        assetSource = session.assetSource,
                        routeSnapshot = session.routeSnapshot,
                        referenceBurstCount = session.referenceBursts.size,
                        packerInputBurstCount = session.packerInputBursts.size,
                        packedBurstCount = session.packedBursts.size,
                        audioTrackWriteBurstCount = session.audioTrackWriteBursts.size,
                        comparisonResultCount = session.comparisonResults.size,
                    )
                ).toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("comparison-results.json"))
            zip.write(json.encodeToString(session.comparisonResults).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("asset-source.json"))
            zip.write(
                encodeNullableJson(session.assetSource).toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("runtime-summary.json"))
            zip.write(
                encodeNullableJsonWithDefaults(session.runtimeSnapshot?.summary).toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("playback-stats.json"))
            zip.write(
                encodeNullableJson(
                    session.runtimeSnapshot?.playbackStats
                ).toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("player-events.json"))
            zip.write(
                json.encodeToString(session.runtimeSnapshot?.playerEvents ?: emptyList()).toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("analytics-events.json"))
            zip.write(
                json.encodeToString(session.runtimeSnapshot?.analyticsEvents ?: emptyList()).toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("sink-health.json"))
            val sinkHealthSummary =
                session.runtimeSnapshot?.sinkHealth
                    ?: buildSinkHealthSummaryFallback(session.runtimeSnapshot?.analyticsEvents.orEmpty())
            zip.write(encodeNullableJsonWithDefaults(sinkHealthSummary).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("route-health.json"))
            zip.write(
                encodeNullableJsonWithDefaults(
                    session.runtimeSnapshot?.routeHealth
                ).toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("playback-head-health.json"))
            zip.write(
                encodeNullableJsonWithDefaults(
                    session.runtimeSnapshot?.playbackHeadHealth
                ).toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("operator-observation.json"))
            zip.write(
                encodeNullableJson(
                    session.runtimeSnapshot?.summary?.operatorObservation
                ).toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("reference-bursts.json"))
            zip.write(json.encodeToString(session.referenceBursts.map { it.toExportRecord() }).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("packer-input-bursts.json"))
            zip.write(json.encodeToString(session.packerInputBursts.map { it.toExportRecord() }).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("packed-bursts.json"))
            zip.write(json.encodeToString(session.packedBursts.map { it.toExportRecord() }).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("audiotrack-write-bursts.json"))
            zip.write(
                json.encodeToString(session.audioTrackWriteBursts.map { it.toExportRecord() }).toByteArray()
            )
            zip.closeEntry()
            if (includeBinaryDumps) {
                writeBurstDumps(zip, session.sample.id, "packer_in", session.packerInputBursts)
                writeBurstDumps(zip, session.sample.id, "packed", session.packedBursts)
                writeBurstDumps(zip, session.sample.id, "audiotrack_write", session.audioTrackWriteBursts)
            }
        }
        return bundle
    }

    private inline fun <reified T> encodeNullableJson(value: T?): String =
        value?.let(json::encodeToString) ?: "null"

    private inline fun <reified T> encodeNullableJsonWithDefaults(value: T?): String =
        value?.let(jsonWithDefaults::encodeToString) ?: "null"

    private fun buildSinkHealthSummaryFallback(
        analyticsEvents: List<TransportValidationRuntimeAnalyticsEvent>
    ): TransportValidationSinkHealthSummary? {
        if (analyticsEvents.isEmpty()) {
            return null
        }
        val orderedEvents = analyticsEvents.sortedBy { it.elapsedRealtimeMs }
        val writeEvents = mutableListOf<TransportValidationSinkWriteEventRecord>()
        var writeAttemptCount = 0L
        var successfulWriteCount = 0L
        var zeroWriteCount = 0L
        var partialWriteCount = 0L
        var audioUnderrunCount = 0L
        var audioTrackRestartCount = 0L
        orderedEvents.forEach { event ->
            val writeDetail = parseTransportValidationSinkWriteEventDetail(event.detail)
            when (event.type) {
                "audio_write_zero" -> {
                    writeAttemptCount += 1
                    zeroWriteCount += 1
                    writeEvents +=
                        TransportValidationSinkWriteEventRecord(
                            elapsedRealtimeMs = event.elapsedRealtimeMs,
                            type = event.type,
                            requestedBytes = writeDetail.requestedBytes,
                            value = event.value,
                            detail = writeDetail,
                        )
                }
                "audio_write_deferred" -> {
                    writeEvents +=
                        TransportValidationSinkWriteEventRecord(
                            elapsedRealtimeMs = event.elapsedRealtimeMs,
                            type = event.type,
                            requestedBytes = writeDetail.requestedBytes,
                            value = event.value,
                            detail = writeDetail,
                        )
                }
                "audio_write_partial" -> {
                    writeAttemptCount += 1
                    successfulWriteCount += 1
                    partialWriteCount += 1
                    writeEvents +=
                        TransportValidationSinkWriteEventRecord(
                            elapsedRealtimeMs = event.elapsedRealtimeMs,
                            type = event.type,
                            requestedBytes = writeDetail.requestedBytes,
                            value = event.value,
                            detail = writeDetail,
                        )
                }
                "audio_write_success" -> {
                    writeAttemptCount += 1
                    successfulWriteCount += 1
                    writeEvents +=
                        TransportValidationSinkWriteEventRecord(
                            elapsedRealtimeMs = event.elapsedRealtimeMs,
                            type = event.type,
                            requestedBytes = writeDetail.requestedBytes,
                            value = event.value,
                            detail = writeDetail,
                        )
                }
                "audio_underrun" -> audioUnderrunCount += maxOf(1L, event.value ?: 1L)
                "audiotrack_restart" -> audioTrackRestartCount += maxOf(1L, event.value ?: 1L)
            }
        }
        if (writeEvents.isEmpty() && audioUnderrunCount == 0L && audioTrackRestartCount == 0L) {
            return null
        }
        return TransportValidationSinkHealthSummary(
            writeAttemptCount = writeAttemptCount,
            successfulWriteCount = successfulWriteCount,
            zeroWriteCount = zeroWriteCount,
            partialWriteCount = partialWriteCount,
            audioUnderrunCount = audioUnderrunCount,
            audioTrackRestartCount = audioTrackRestartCount,
            writeEvents = writeEvents,
        )
    }

    private fun writeBurstDumps(
        zip: ZipOutputStream,
        codecName: String,
        dumpName: String,
        bursts: List<TransportValidationBurstRecord>,
    ) {
        bursts.forEach { burst ->
            zip.putNextEntry(
                ZipEntry(
                    "%s_%s_%06d.bin".format(codecName, dumpName, burst.burstIndex + 1)
                )
            )
            zip.write(burst.rawBytes)
            zip.closeEntry()
        }
    }
}

@Serializable
private data class TransportValidationBurstExportRecord(
    val codecFamily: TransportValidationCodecFamily,
    val sampleId: String,
    val burstIndex: Int,
    val sourcePtsUs: Long,
    val burstSizeBytes: Int,
    val pa: Int,
    val pb: Int,
    val rawPc: Int,
    val normalizedPc: Int,
    val pd: Int,
    val payloadBytes: Int,
    val zeroPaddingBytes: Int,
    val first64ByteHash: String,
    val fullBurstHash: String,
    val dtsHdSubtype: Int? = null,
    val dtsHdWrapperPresent: Boolean? = null,
    val dtsHdWrapperPayloadSize: Int? = null,
    val dtsHdPayloadClassification: TransportValidationDtsPayloadClassification? = null,
    val codecFailureHint: TransportValidationFailureCode? = null,
)

private fun TransportValidationBurstRecord.toExportRecord(): TransportValidationBurstExportRecord =
    TransportValidationBurstExportRecord(
        codecFamily = codecFamily,
        sampleId = sampleId,
        burstIndex = burstIndex,
        sourcePtsUs = sourcePtsUs,
        burstSizeBytes = burstSizeBytes,
        pa = pa,
        pb = pb,
        rawPc = rawPc,
        normalizedPc = normalizedPc,
        pd = pd,
        payloadBytes = payloadBytes,
        zeroPaddingBytes = zeroPaddingBytes,
        first64ByteHash = first64ByteHash,
        fullBurstHash = fullBurstHash,
        dtsHdSubtype = dtsHdSubtype,
        dtsHdWrapperPresent = dtsHdWrapperPresent,
        dtsHdWrapperPayloadSize = dtsHdWrapperPayloadSize,
        dtsHdPayloadClassification = dtsHdPayloadClassification,
        codecFailureHint = codecFailureHint,
    )

@Serializable
private data class TransportValidationExportSummary(
    val manifestVersion: String,
    val sample: TransportValidationSample,
    val transportVerdict: TransportValidationVerdict,
    val runtimeVerdict: TransportValidationRuntimeVerdict,
    val assetSource: TransportValidationAssetSourceSnapshot? = null,
    val routeSnapshot: TransportValidationRouteSnapshot? = null,
    val referenceBurstCount: Int,
    val packerInputBurstCount: Int,
    val packedBurstCount: Int,
    val audioTrackWriteBurstCount: Int,
    val comparisonResultCount: Int,
)
