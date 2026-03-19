package com.nexio.tv.debug.passthrough

object TransportValidationComparator {
    fun compareReferenceBurst(
        sample: TransportValidationSample,
        reference: TransportValidationBurstRecord,
        live: TransportValidationBurstRecord,
        comparisonMode: TransportValidationComparisonMode = TransportValidationComparisonMode.FULL_BURST_COMPARE,
        stage: String = STAGE_REFERENCE_TO_PACKED,
    ): TransportValidationComparisonResult {
        if (reference.burstIndex != live.burstIndex) {
            return failed(stage, live.burstIndex, TransportValidationFailureCode.BURST_ALIGNMENT_FAILED)
        }

        live.codecFailureHint?.let { return failed(stage, live.burstIndex, it) }

        if (!samePreamble(reference, live)) {
            return failed(
                stage,
                live.burstIndex,
                when (sample.codecFamily) {
                    TransportValidationCodecFamily.TRUEHD -> {
                        TransportValidationFailureCode.TRUEHD_MAT_INVALID
                    }

                    else -> TransportValidationFailureCode.PREAMBLE_MISMATCH
                }
            )
        }

        if (sample.codecFamily == TransportValidationCodecFamily.TRUEHD &&
            live.normalizedPc != 0x16
        ) {
            return failed(stage, live.burstIndex, TransportValidationFailureCode.TRUEHD_MAT_INVALID)
        }

        if (sample.codecFamily == TransportValidationCodecFamily.DTS_HD ||
            sample.codecFamily == TransportValidationCodecFamily.DTS_X
        ) {
            if (live.normalizedPc != 0x11 || live.dtsHdWrapperPresent != true) {
                return failed(
                    stage,
                    live.burstIndex,
                    TransportValidationFailureCode.DTSHD_CORE_ONLY_FALLBACK,
                    live.dtsHdPayloadClassification,
                )
            }
            if (live.dtsHdPayloadClassification ==
                TransportValidationDtsPayloadClassification.LIKELY_CORE_ONLY_PAYLOAD
            ) {
                return failed(
                    stage,
                    live.burstIndex,
                    TransportValidationFailureCode.DTSHD_CORE_ONLY_FALLBACK,
                    live.dtsHdPayloadClassification,
                )
            }
        }

        if (sample.codecFamily in
            setOf(
                TransportValidationCodecFamily.E_AC3,
                TransportValidationCodecFamily.E_AC3_JOC
            ) &&
            live.payloadBytes != live.pd
        ) {
            return failed(
                stage,
                live.burstIndex,
                TransportValidationFailureCode.EAC3_AGGREGATION_MISMATCH,
                live.dtsHdPayloadClassification,
            )
        }

        if (comparisonMode == TransportValidationComparisonMode.FULL_BURST_COMPARE &&
            reference.fullBurstHash != live.fullBurstHash
        ) {
            return failed(
                stage,
                live.burstIndex,
                TransportValidationFailureCode.FULL_BURST_MISMATCH,
                live.dtsHdPayloadClassification,
            )
        }

        return TransportValidationComparisonResult(
            stage = stage,
            burstIndex = live.burstIndex,
            passed = true,
            dtsHdPayloadClassification = live.dtsHdPayloadClassification,
        )
    }

    fun comparePackedToAudioTrack(
        packed: TransportValidationBurstRecord,
        audioTrack: TransportValidationBurstRecord,
        comparisonMode: TransportValidationComparisonMode = TransportValidationComparisonMode.FULL_BURST_COMPARE,
    ): TransportValidationComparisonResult {
        if (packed.burstIndex != audioTrack.burstIndex) {
            return failed(
                STAGE_PACKED_TO_AUDIOTRACK,
                audioTrack.burstIndex,
                TransportValidationFailureCode.BURST_ALIGNMENT_FAILED,
                audioTrack.dtsHdPayloadClassification,
            )
        }
        if (!samePreamble(packed, audioTrack)) {
            return failed(
                STAGE_PACKED_TO_AUDIOTRACK,
                audioTrack.burstIndex,
                TransportValidationFailureCode.PREAMBLE_MISMATCH,
                audioTrack.dtsHdPayloadClassification,
            )
        }
        if (comparisonMode == TransportValidationComparisonMode.FULL_BURST_COMPARE &&
            packed.fullBurstHash != audioTrack.fullBurstHash
        ) {
            return failed(
                STAGE_PACKED_TO_AUDIOTRACK,
                audioTrack.burstIndex,
                TransportValidationFailureCode.PACKER_TO_AUDIOTRACK_MUTATION,
                audioTrack.dtsHdPayloadClassification,
            )
        }
        return TransportValidationComparisonResult(
            stage = STAGE_PACKED_TO_AUDIOTRACK,
            burstIndex = audioTrack.burstIndex,
            passed = true,
            dtsHdPayloadClassification = audioTrack.dtsHdPayloadClassification,
        )
    }

    private fun samePreamble(
        reference: TransportValidationBurstRecord,
        live: TransportValidationBurstRecord,
    ): Boolean {
        return reference.pa == live.pa &&
            reference.pb == live.pb &&
            reference.normalizedPc == live.normalizedPc &&
            reference.pd == live.pd
    }

    private fun failed(
        stage: String,
        burstIndex: Int,
        failureCode: TransportValidationFailureCode,
        dtsHdPayloadClassification: TransportValidationDtsPayloadClassification? = null,
    ): TransportValidationComparisonResult =
        TransportValidationComparisonResult(
            stage = stage,
            burstIndex = burstIndex,
            passed = false,
            failureCode = failureCode,
            dtsHdPayloadClassification = dtsHdPayloadClassification,
        )

    private const val STAGE_REFERENCE_TO_PACKED = "reference_to_packed"
    private const val STAGE_PACKED_TO_AUDIOTRACK = "packed_to_audiotrack"
}
