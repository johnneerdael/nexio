package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitySnapshotSerializerTest {
    @Test
    fun `serializer emits required canonical fields`() {
        val json = DeviceCapabilitySnapshotSerializer.toJson(fullSnapshot())

        assertEquals("Google TV Streamer", json.get("model").asString)
        assertEquals("Google", json.get("manufacturer").asString)
        assertEquals(34, json.get("sdkInt").asInt)
        assertEquals(1775519900000L, json.get("capturedAtMs").asLong)

        val hdr = json.getAsJsonArray("displayHdrTypes").map { it.asString }
        assertTrue(hdr.containsAll(listOf("hdr10", "dolby_vision")))

        val videoDecode = json.getAsJsonObject("videoDecode")
        assertNotNull(videoDecode.get("h264"))
        assertEquals(true, videoDecode.getAsJsonObject("h264").get("hardwareAccelerated").asBoolean)

        val audioOutput = json.getAsJsonObject("audioOutput")
        listOf("ac3", "eac3", "atmos", "truehd", "dts", "dtshd", "dtsx").forEach { key ->
            assertNotNull("$key must be present in audioOutput", audioOutput.get(key))
            assertTrue(audioOutput.getAsJsonObject(key).has("supported"))
            assertTrue(audioOutput.getAsJsonObject(key).has("passthroughLikely"))
        }
    }

    @Test
    fun `serializer round-trips through existing parser`() {
        val original = fullSnapshot()
        val parsed = parseDeviceCapabilitySnapshotJson(
            DeviceCapabilitySnapshotSerializer.toJson(original).toString()
        )

        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    private fun fullSnapshot() = DeviceCapabilitySnapshot(
        model = "Google TV Streamer",
        manufacturer = "Google",
        sdkInt = 34,
        displayHdrTypes = setOf(DeviceHdrType.HDR10, DeviceHdrType.DOLBY_VISION),
        videoDecode = DeviceVideoDecodeCapabilities(
            h264 = CodecSupport(hardwareAccelerated = true, softwareOnlyAvailable = true, secureSupported = false),
            hevc = CodecSupport(hardwareAccelerated = true, softwareOnlyAvailable = false, secureSupported = true),
            av1 = null,
            dolbyVision = CodecSupport(hardwareAccelerated = true, softwareOnlyAvailable = false, secureSupported = true)
        ),
        audioOutput = DeviceAudioOutputCapabilities(
            ac3 = AudioEncodingSupport(supported = true, passthroughLikely = true),
            eac3 = AudioEncodingSupport(supported = true, passthroughLikely = true),
            atmos = AudioEncodingSupport(supported = true, passthroughLikely = false),
            truehd = AudioEncodingSupport(supported = false, passthroughLikely = false),
            dts = AudioEncodingSupport(supported = false, passthroughLikely = false),
            dtshd = AudioEncodingSupport(supported = false, passthroughLikely = false),
            dtsx = AudioEncodingSupport(supported = false, passthroughLikely = false)
        ),
        evidence = null,
        capturedAtMs = 1775519900000L
    )
}
