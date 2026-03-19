package com.nexio.tv.debug.passthrough

import kotlinx.serialization.Serializable

@Serializable
data class TransportValidationManifest(
    val version: String,
    val samples: List<TransportValidationSample>,
)

@Serializable
data class TransportValidationSample(
    val id: String,
    val displayName: String,
    val codecFamily: TransportValidationCodecFamily,
    val sourceAssetPath: String,
    val elementaryAssetPath: String? = null,
    val referenceAssetPath: String,
    val expectedPc: Int,
    val pdRule: TransportValidationPdRule,
    val expectedBurstModel: TransportValidationBurstModel,
    val expectedRouteTuple: TransportValidationRouteTuple,
    val assetChecksums: Map<String, String>,
    val notes: List<String> = emptyList(),
)

@Serializable
enum class TransportValidationCodecFamily {
    AC3,
    E_AC3,
    E_AC3_JOC,
    DTS_CORE,
    DTS_HD,
    DTS_X,
    TRUEHD,
}

@Serializable
enum class TransportValidationPdRule {
    EXACT_REFERENCE_MATCH,
    AGGREGATED_PAYLOAD_BYTES,
    TRUEHD_MAT,
}

@Serializable
enum class TransportValidationBurstModel {
    IEC_BURST,
    MAT,
}

@Serializable
data class TransportValidationRouteTuple(
    val encoding: String,
    val sampleRate: Int,
    val channelMask: String,
)

data class TransportValidationBurstRecord(
    val codecFamily: TransportValidationCodecFamily,
    val sampleId: String,
    val burstIndex: Int,
    val sourcePtsUs: Long,
    val rawBytes: ByteArray,
    val burstSizeBytes: Int,
    val pa: Int,
    val pb: Int,
    val rawPc: Int,
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
) {
    val normalizedPc: Int = rawPc and 0x7F
}

@Serializable
enum class TransportValidationDtsPayloadClassification {
    HD_PAYLOAD,
    LIKELY_CORE_ONLY_PAYLOAD,
    UNKNOWN_PAYLOAD,
}

@Serializable
data class TransportValidationComparisonResult(
    val stage: String = "",
    val burstIndex: Int = -1,
    val passed: Boolean,
    val failureCode: TransportValidationFailureCode? = null,
    val dtsHdPayloadClassification: TransportValidationDtsPayloadClassification? = null,
)

@Serializable
data class TransportValidationRouteSnapshot(
    val deviceName: String? = null,
    val encoding: String? = null,
    val sampleRate: Int? = null,
    val channelMask: String? = null,
    val directPlaybackSupported: Boolean? = null,
    val audioTrackState: Int? = null,
)

data class TransportValidationSessionSnapshot(
    val manifestVersion: String,
    val sample: TransportValidationSample,
    val routeSnapshot: TransportValidationRouteSnapshot? = null,
    val referenceBursts: List<TransportValidationBurstRecord> = emptyList(),
    val packerInputBursts: List<TransportValidationBurstRecord> = emptyList(),
    val packedBursts: List<TransportValidationBurstRecord> = emptyList(),
    val audioTrackWriteBursts: List<TransportValidationBurstRecord> = emptyList(),
    val comparisonResults: List<TransportValidationComparisonResult> = emptyList(),
)
