package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitySnapshotJsonTest {

    @Test
    fun `device snapshot json round trips capability fields`() {
        val snapshot = deviceSnapshot()

        val restored = parseDeviceCapabilitySnapshotJson(
            snapshot.toJsonObject().toString()
        )

        assertEquals(snapshot.model, restored?.model)
        assertEquals(snapshot.manufacturer, restored?.manufacturer)
        assertEquals(snapshot.sdkInt, restored?.sdkInt)
        assertEquals(snapshot.displayHdrTypes, restored?.displayHdrTypes)
        assertTrue(restored?.videoDecode?.hevc?.hardwareAccelerated == true)
        assertFalse(restored?.videoDecode?.av1?.hardwareAccelerated == true)
        assertTrue(restored?.audioOutput?.eac3?.supported == true)
        assertFalse(restored?.audioOutput?.truehd?.supported == true)
        assertEquals(snapshot.capturedAtMs, restored?.capturedAtMs)
    }

    @Test
    fun `invalid device snapshot json returns null`() {
        assertEquals(null, parseDeviceCapabilitySnapshotJson("{\"sdkInt\":0}"))
        assertEquals(null, parseDeviceCapabilitySnapshotJson("not-json"))
    }

    private fun deviceSnapshot(): DeviceCapabilitySnapshot {
        return DeviceCapabilitySnapshot(
            model = "AM9 PRO",
            manufacturer = "UGOOS",
            sdkInt = 34,
            displayHdrTypes = setOf(DeviceHdrType.HDR10),
            videoDecode = DeviceVideoDecodeCapabilities(
                h264 = CodecSupport(true, false, true),
                hevc = CodecSupport(true, false, true),
                av1 = CodecSupport(false, true, false),
                dolbyVision = CodecSupport(false, false, false)
            ),
            audioOutput = DeviceAudioOutputCapabilities(
                ac3 = AudioEncodingSupport(true, true),
                eac3 = AudioEncodingSupport(true, true),
                atmos = AudioEncodingSupport(true, true),
                truehd = AudioEncodingSupport(false, false),
                dts = AudioEncodingSupport(false, false),
                dtshd = AudioEncodingSupport(false, false),
                dtsx = AudioEncodingSupport(false, false)
            ),
            capturedAtMs = 123_456L
        )
    }
}
