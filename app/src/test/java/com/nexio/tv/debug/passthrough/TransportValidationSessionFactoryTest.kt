package com.nexio.tv.debug.passthrough

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportValidationSessionFactoryTest {

    private val loader = TransportValidationManifestLoader()

    @Test
    fun `create session snapshot from bundled sample manifest and bounded reference stream`() {
        val manifest = loader.parseManifest(assetFile("transport_validation_manifest.json").readText())
        val sample = manifest.samples.first { it.id == "truehd" }
        val preparedSample =
            PreparedTransportValidationSample(
                manifest = manifest,
                sample = sample,
                sourceFile = assetFile(sample.sourceAssetPath),
                referenceFile = assetFile(sample.referenceAssetPath),
                elementaryFile = sample.elementaryAssetPath?.let(::assetFile),
                assetSource = TransportValidationAssetSourceSnapshot(
                    baseUrl = "https://files.thepi.es/validator",
                    manifestUrl = "https://files.thepi.es/validator/transport_validation_manifest.json",
                    manifestVersion = manifest.version,
                    source = TransportValidationCachedAssetSnapshot(
                        remotePath = sample.sourceAssetPath,
                        localFileName = sample.sourceAssetPath,
                        localFilePath = assetFile(sample.sourceAssetPath).absolutePath,
                        checksum = sample.assetChecksums.getValue("sourceAssetPath"),
                        cacheState = TransportValidationAssetCacheState.REUSED,
                    ),
                    reference = TransportValidationCachedAssetSnapshot(
                        remotePath = sample.referenceAssetPath,
                        localFileName = sample.referenceAssetPath,
                        localFilePath = assetFile(sample.referenceAssetPath).absolutePath,
                        checksum = sample.assetChecksums.getValue("referenceAssetPath"),
                        cacheState = TransportValidationAssetCacheState.REUSED,
                    ),
                    elementary = sample.elementaryAssetPath?.let { path ->
                        TransportValidationCachedAssetSnapshot(
                            remotePath = path,
                            localFileName = path,
                            localFilePath = assetFile(path).absolutePath,
                            checksum = sample.assetChecksums.getValue("elementaryAssetPath"),
                            cacheState = TransportValidationAssetCacheState.REUSED,
                        )
                    },
                ),
            )

        val session =
            assetFile("truehd.spdif").inputStream().use { inputStream ->
                TransportValidationSessionFactory.createSession(
                    preparedSample = preparedSample,
                    referenceInputStream = inputStream,
                    referenceBurstLimit = 8,
                )
            }

        assertEquals("2026.03.19", session.manifestVersion)
        assertEquals("truehd", session.sample.id)
        assertEquals("truehd.thd", session.sample.elementaryAssetPath)
        assertTrue(session.referenceBursts.isNotEmpty())
        assertEquals(8, session.referenceBursts.size)
        assertEquals(0x16, session.referenceBursts.first().normalizedPc)
        assertEquals(61440, session.referenceBursts.first().burstSizeBytes)
    }

    private fun assetFile(name: String): File {
        val direct = File("assets/$name")
        if (direct.exists()) {
            return direct
        }
        val parent = File("../assets/$name")
        if (parent.exists()) {
            return parent
        }
        throw IllegalStateException("Unable to locate $name from test runtime")
    }
}
