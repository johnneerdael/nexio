package com.nexio.tv.debug.passthrough

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TransportValidationDiagnosticsExporter {

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
                Json.encodeToString(
                    TransportValidationExportSummary(
                        manifestVersion = session.manifestVersion,
                        sample = session.sample,
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
            zip.write(Json.encodeToString(session.comparisonResults).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("reference-bursts.json"))
            zip.write(Json.encodeToString(session.referenceBursts.map { it.toExportRecord() }).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("packer-input-bursts.json"))
            zip.write(Json.encodeToString(session.packerInputBursts.map { it.toExportRecord() }).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("packed-bursts.json"))
            zip.write(Json.encodeToString(session.packedBursts.map { it.toExportRecord() }).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("audiotrack-write-bursts.json"))
            zip.write(
                Json.encodeToString(session.audioTrackWriteBursts.map { it.toExportRecord() }).toByteArray()
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
    val routeSnapshot: TransportValidationRouteSnapshot? = null,
    val referenceBurstCount: Int,
    val packerInputBurstCount: Int,
    val packedBurstCount: Int,
    val audioTrackWriteBurstCount: Int,
    val comparisonResultCount: Int,
)
