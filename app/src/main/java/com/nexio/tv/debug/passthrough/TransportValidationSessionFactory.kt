package com.nexio.tv.debug.passthrough

import java.io.InputStream

object TransportValidationSessionFactory {

    fun createSession(
        preparedSample: PreparedTransportValidationSample,
        referenceInputStream: InputStream,
        referenceBurstLimit: Int,
    ): TransportValidationSessionSnapshot {
        return TransportValidationSessionSnapshot(
            manifestVersion = preparedSample.manifest.version,
            sample = preparedSample.sample,
            assetSource = preparedSample.assetSource,
            referenceBursts = TransportValidationReferenceParser.parseReferenceBursts(
                sample = preparedSample.sample,
                inputStream = referenceInputStream,
                maxBursts = referenceBurstLimit
            )
        )
    }
}
