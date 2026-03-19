package com.nexio.tv.debug.passthrough

import java.io.InputStream

object TransportValidationSessionFactory {

    fun createSession(
        manifest: TransportValidationManifest,
        sampleId: String,
        referenceInputStream: InputStream,
        referenceBurstLimit: Int,
    ): TransportValidationSessionSnapshot {
        val sample = manifest.samples.first { it.id == sampleId }
        return TransportValidationSessionSnapshot(
            manifestVersion = manifest.version,
            sample = sample,
            referenceBursts = TransportValidationReferenceParser.parseReferenceBursts(
                sample = sample,
                inputStream = referenceInputStream,
                maxBursts = referenceBurstLimit
            )
        )
    }
}
