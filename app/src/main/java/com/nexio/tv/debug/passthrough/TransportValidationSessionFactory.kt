package com.nexio.tv.debug.passthrough

object TransportValidationSessionFactory {

    fun createSession(
        manifest: TransportValidationManifest,
        sampleId: String,
        referenceBytes: ByteArray,
    ): TransportValidationSessionSnapshot {
        val sample = manifest.samples.first { it.id == sampleId }
        return TransportValidationSessionSnapshot(
            manifestVersion = manifest.version,
            sample = sample,
            referenceBursts = TransportValidationReferenceParser.parseReferenceBursts(
                sample = sample,
                bytes = referenceBytes
            )
        )
    }
}
