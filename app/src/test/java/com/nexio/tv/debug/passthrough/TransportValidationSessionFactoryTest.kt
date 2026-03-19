package com.nexio.tv.debug.passthrough

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportValidationSessionFactoryTest {

    private val loader = TransportValidationManifestLoader()

    @Test
    fun `create session snapshot from bundled sample manifest and reference bytes`() {
        val manifest = loader.parseManifest(assetFile("transport_validation_manifest.json").readText())

        val session = TransportValidationSessionFactory.createSession(
            manifest = manifest,
            sampleId = "truehd",
            referenceBytes = assetFile("truehd.spdif").readBytes()
        )

        assertEquals("2026.03.19", session.manifestVersion)
        assertEquals("truehd", session.sample.id)
        assertEquals("truehd.thd", session.sample.elementaryAssetPath)
        assertTrue(session.referenceBursts.isNotEmpty())
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
