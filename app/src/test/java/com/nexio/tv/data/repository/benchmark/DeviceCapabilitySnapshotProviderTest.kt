package com.nexio.tv.data.repository.benchmark

import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.nexio.tv.data.local.PlayerSettings
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
                    mimeType = MimeTypes.VIDEO_H265,
                    hardwareAccelerated = true,
                    softwareOnly = false,
                    secureSupported = true
                ),
                DecoderCapabilityInfo(
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
        val supportedEncodings = setOf(
            C.ENCODING_AC3,
            C.ENCODING_E_AC3_JOC,
            C.ENCODING_DTS_HD
        )

        val ac3 = buildAudioEncodingSupport(
            supportsEncoding = supportedEncodings::contains,
            passthroughEncodings = intArrayOf(C.ENCODING_AC3)
        )
        val eac3 = buildAudioEncodingSupport(
            supportsEncoding = supportedEncodings::contains,
            passthroughEncodings = intArrayOf(C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC)
        )
        val dtshd = buildAudioEncodingSupport(
            supportsEncoding = supportedEncodings::contains,
            passthroughEncodings = intArrayOf(C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2)
        )
        val truehd = buildAudioEncodingSupport(
            supportsEncoding = supportedEncodings::contains,
            passthroughEncodings = intArrayOf(C.ENCODING_DOLBY_TRUEHD)
        )

        assertTrue(ac3.supported)
        assertTrue(ac3.passthroughLikely)
        assertTrue(eac3.supported)
        assertTrue(eac3.passthroughLikely)
        assertTrue(dtshd.supported)
        assertTrue(dtshd.passthroughLikely)
        assertFalse(truehd.supported)
        assertFalse(truehd.passthroughLikely)
    }

    @Test
    fun `buildBenchmarkSupportedEncodings maps atmos and dtshd aliases into scorer-safe encodings`() {
        val supportedEncodings = setOf(
            C.ENCODING_E_AC3_JOC,
            C.ENCODING_DTS_HD
        )

        val effective = buildBenchmarkSupportedEncodings(supportedEncodings::contains).toSet()

        assertTrue(C.ENCODING_E_AC3_JOC in effective)
        assertTrue(C.ENCODING_E_AC3 in effective)
        assertTrue(C.ENCODING_DTS in effective)
        assertFalse(C.ENCODING_DOLBY_TRUEHD in effective)
    }

    @Test
    fun `default player settings keep benchmark audio toggles enabled`() {
        val defaults = PlayerSettings()

        assertTrue(defaults.iecPackerAc3PassthroughEnabled)
        assertTrue(defaults.iecPackerEac3PassthroughEnabled)
        assertTrue(defaults.iecPackerDtsPassthroughEnabled)
        assertTrue(defaults.iecPackerTruehdPassthroughEnabled)
        assertTrue(defaults.iecPackerDtshdPassthroughEnabled)
        assertTrue(defaults.iecPackerDtshdCoreFallbackEnabled)
    }
}
