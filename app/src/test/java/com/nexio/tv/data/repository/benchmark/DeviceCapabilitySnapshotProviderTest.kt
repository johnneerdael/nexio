package com.nexio.tv.data.repository.benchmark

import android.media.AudioDeviceInfo
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitySnapshotProviderTest {

    @Test
    fun `normalizeHdrTypes maps supported display hdr flags`() {
        val hdrTypes = normalizeHdrTypes(
            intArrayOf(
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION,
                Display.HdrCapabilities.HDR_TYPE_HDR10,
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
                Display.HdrCapabilities.HDR_TYPE_HLG,
                Display.HdrCapabilities.HDR_TYPE_HDR10
            )
        )

        assertEquals(
            setOf(
                DeviceHdrType.DOLBY_VISION,
                DeviceHdrType.HDR10,
                DeviceHdrType.HDR10_PLUS,
                DeviceHdrType.HLG
            ),
            hdrTypes
        )
    }

    @Test
    fun `buildCodecSupportForMime aggregates hardware software and secure decoder signals`() {
        val support = buildCodecSupportForMime(
            decoders = listOf(
                DecoderCapabilityInfo(
                    codecName = "hevc.hw",
                    mimeType = MimeTypes.VIDEO_H265,
                    hardwareAccelerated = true,
                    softwareOnly = false,
                    secureSupported = true
                ),
                DecoderCapabilityInfo(
                    codecName = "hevc.sw",
                    mimeType = MimeTypes.VIDEO_H265,
                    hardwareAccelerated = false,
                    softwareOnly = true,
                    secureSupported = false
                )
            ),
            mimeType = MimeTypes.VIDEO_H265
        )

        assertNotNull(support)
        assertTrue(support?.hardwareAccelerated == true)
        assertTrue(support?.softwareOnlyAvailable == true)
        assertTrue(support?.secureSupported == true)
        assertEquals(null, buildCodecSupportForMime(emptyList(), MimeTypes.VIDEO_AV1))
    }

    @Test
    fun `buildAudioEncodingSupport respects passthrough encoding aliases`() {
        val directProfiles = setOf(
            C.ENCODING_AC3,
            C.ENCODING_E_AC3_JOC
        )
        val deviceEncodings = setOf(C.ENCODING_DTS_HD)

        val ac3 = buildAudioEncodingSupport(
            directProfileEncodings = directProfiles,
            deviceEncodings = deviceEncodings,
            passthroughEncodings = intArrayOf(C.ENCODING_AC3)
        )
        val eac3 = buildAudioEncodingSupport(
            directProfileEncodings = directProfiles,
            deviceEncodings = deviceEncodings,
            passthroughEncodings = intArrayOf(C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC)
        )
        val dtshd = buildAudioEncodingSupport(
            directProfileEncodings = directProfiles,
            deviceEncodings = deviceEncodings,
            passthroughEncodings = intArrayOf(C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2)
        )
        val truehd = buildAudioEncodingSupport(
            directProfileEncodings = directProfiles,
            deviceEncodings = deviceEncodings,
            passthroughEncodings = intArrayOf(C.ENCODING_DOLBY_TRUEHD)
        )

        assertTrue(ac3.supported)
        assertTrue(ac3.passthroughLikely)
        assertTrue(eac3.supported)
        assertTrue(eac3.passthroughLikely)
        assertTrue(dtshd.supported)
        assertFalse(dtshd.passthroughLikely)
        assertFalse(truehd.supported)
        assertFalse(truehd.passthroughLikely)
    }

    @Test
    fun `buildAudioEncodingSupport maps direct profiles and device encodings into support and passthrough`() {
        val directProfiles = setOf(
            C.ENCODING_E_AC3_JOC,
            C.ENCODING_DTS_HD
        )
        val deviceEncodings = setOf(C.ENCODING_DTS)

        val eac3 = buildAudioEncodingSupport(
            directProfileEncodings = directProfiles,
            deviceEncodings = emptySet(),
            passthroughEncodings = intArrayOf(C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC)
        )
        val dts = buildAudioEncodingSupport(
            directProfileEncodings = emptySet(),
            deviceEncodings = deviceEncodings,
            passthroughEncodings = intArrayOf(C.ENCODING_DTS, C.ENCODING_DTS_HD)
        )

        assertTrue(eac3.supported)
        assertTrue(eac3.passthroughLikely)
        assertTrue(dts.supported)
        assertTrue(dts.passthroughLikely)
    }

    @Test
    fun `wire name helpers expose stable raw evidence values`() {
        assertEquals("hdmi_earc", deviceTypeWireName(AudioDeviceInfo.TYPE_HDMI_EARC))
        assertEquals("truehd", audioEncodingWireName(C.ENCODING_DOLBY_TRUEHD))
        assertEquals("dolby_vision", hdrTypeWireName(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION))
        assertEquals("unknown:999", hdrTypeWireName(999))
    }
}
