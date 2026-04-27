package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitySnapshotSerializerTest {

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

    @Test
    fun `serializer emits required canonical fields`() {
        val json = DeviceCapabilitySnapshotSerializer.toJson(fullSnapshot())

        assertEquals("Google TV Streamer", json.get("model").asString)
        assertEquals("Google", json.get("manufacturer").asString)
        assertEquals(34, json.get("sdkInt").asInt)
        assertEquals(1775519900000L, json.get("capturedAtMs").asLong)

        val hdr = json.getAsJsonArray("displayHdrTypes").map { it.asString }
        assertTrue("HDR types must use lowercase wire keys", hdr.containsAll(listOf("hdr10", "dolby_vision")))

        val videoDecode = json.getAsJsonObject("videoDecode")
        assertNotNull("h264 codec must be emitted", videoDecode.get("h264"))
        assertEquals(true, videoDecode.getAsJsonObject("h264").get("hardwareAccelerated").asBoolean)

        val audioOutput = json.getAsJsonObject("audioOutput")
        // All 7 audio fields must be present, even atmos and dtsx (which are optional in the parser).
        listOf("ac3", "eac3", "atmos", "truehd", "dts", "dtshd", "dtsx").forEach { key ->
            assertNotNull("$key must be present in audioOutput", audioOutput.get(key))
            assertTrue("$key must have supported boolean", audioOutput.getAsJsonObject(key).has("supported"))
            assertTrue("$key must have passthroughLikely boolean", audioOutput.getAsJsonObject(key).has("passthroughLikely"))
        }
    }

    @Test
    fun `serializer round-trips through the existing parser`() {
        val original = fullSnapshot()
        val raw = DeviceCapabilitySnapshotSerializer.toJson(original).toString()
        val parsed = parseDeviceCapabilitySnapshotJson(raw)

        assertNotNull("round-trip parse must succeed", parsed)
        assertEquals(original.model, parsed!!.model)
        assertEquals(original.manufacturer, parsed.manufacturer)
        assertEquals(original.sdkInt, parsed.sdkInt)
        assertEquals(original.capturedAtMs, parsed.capturedAtMs)
        assertEquals(original.displayHdrTypes, parsed.displayHdrTypes)
        assertEquals(original.videoDecode, parsed.videoDecode)
        assertEquals(original.audioOutput, parsed.audioOutput)
    }

    @Test
    fun `serializer round-trips a minimal snapshot with no codec entries`() {
        val minimal = DeviceCapabilitySnapshot(
            model = null,
            manufacturer = null,
            sdkInt = 30,
            displayHdrTypes = emptySet(),
            videoDecode = DeviceVideoDecodeCapabilities(),  // all codecs null
            audioOutput = DeviceAudioOutputCapabilities(),  // all defaults (supported=false, passthroughLikely=false)
            evidence = null,
            capturedAtMs = 1L
        )

        val raw = DeviceCapabilitySnapshotSerializer.toJson(minimal).toString()
        val parsed = parseDeviceCapabilitySnapshotJson(raw)

        assertNotNull(parsed)
        assertEquals(30, parsed!!.sdkInt)
        assertEquals(1L, parsed.capturedAtMs)
        assertEquals(emptySet<DeviceHdrType>(), parsed.displayHdrTypes)
        assertEquals(DeviceVideoDecodeCapabilities(), parsed.videoDecode)
        assertEquals(DeviceAudioOutputCapabilities(), parsed.audioOutput)
    }
}
