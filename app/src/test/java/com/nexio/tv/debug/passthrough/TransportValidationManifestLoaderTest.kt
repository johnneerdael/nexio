package com.nexio.tv.debug.passthrough

import java.io.File
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportValidationManifestLoaderTest {

    private val loader = TransportValidationManifestLoader()

    @Test
    fun `load repository manifest and resolve validation assets`() {
        val manifest = loader.parseManifest(
            repoManifestFile().readText()
        )

        assertEquals("2026.03.19", manifest.version)
        assertEquals(6, manifest.samples.size)
        assertTrue(manifest.samples.any { it.id == "truehd" && it.referenceAssetPath == "truehd.spdif" })
        assertTrue(manifest.samples.any { it.id == "dtshd" && it.referenceAssetPath == "dtshd.spdif" })
        assertTrue(manifest.samples.any { it.id == "dtsx" && it.referenceAssetPath == "dtsx.spdif" })
        assertTrue(manifest.samples.any { it.id == "truehd" && it.elementaryAssetPath == "truehd.thd" })
        assertTrue(manifest.samples.any { it.id == "dts" && it.elementaryAssetPath == "dts.dts" })
        assertTrue(
            manifest.samples.all { sample ->
                sample.assetChecksums["sourceAssetPath"]?.isNotBlank() == true &&
                    sample.assetChecksums["referenceAssetPath"]?.isNotBlank() == true &&
                    sample.elementaryAssetPath?.let {
                        sample.assetChecksums["elementaryAssetPath"]?.isNotBlank() == true
                    } ?: true
            }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject manifest sample with missing checksum entry`() {
        loader.parseManifest(
            """
            {
              "version": "2026.03.19",
              "samples": [
                {
                  "id": "truehd",
                  "displayName": "TrueHD Validation",
                  "codecFamily": "TRUEHD",
                  "sourceAssetPath": "truehd.mkv",
                  "referenceAssetPath": "truehd.thd",
                  "expectedPc": 22,
                  "pdRule": "TRUEHD_MAT",
                  "expectedBurstModel": "MAT",
                  "expectedRouteTuple": {
                    "encoding": "IEC61937",
                    "sampleRate": 192000,
                    "channelMask": "7.1"
                  },
                  "assetChecksums": {
                    "sourceAssetPath": "sha256-source"
                  }
                }
              ]
            }
            """.trimIndent()
        )
    }

    @Test(expected = SerializationException::class)
    fun `reject malformed manifest sample entry`() {
        loader.parseManifest(
            """
            {
              "version": "2026.03.19",
              "samples": [
                {
                  "id": "truehd",
                  "displayName": "TrueHD Validation"
                }
              ]
            }
            """.trimIndent()
        )
    }

    private fun repoManifestFile(): File {
        val direct = File("assets/transport_validation_manifest.json")
        if (direct.exists()) {
            return direct
        }
        val parent = File("../assets/transport_validation_manifest.json")
        if (parent.exists()) {
            return parent
        }
        throw IllegalStateException("Unable to locate transport_validation_manifest.json from test runtime")
    }
}
