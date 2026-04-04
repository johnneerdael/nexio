package com.nexio.tv.data.repository.benchmark

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.Display
import android.media.AudioFormat
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
    fun `wire name helpers expose stable raw evidence values`() {
        assertEquals("hdmi_earc", deviceTypeWireName(AudioDeviceInfo.TYPE_HDMI_EARC))
        assertEquals("truehd", audioEncodingWireName(C.ENCODING_DOLBY_TRUEHD))
        assertEquals("dolby_vision", hdrTypeWireName(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION))
        assertEquals("unknown:999", hdrTypeWireName(999))
    }

    @Test
    fun `direct playback support wire names stay stable`() {
        assertEquals("not_supported", directPlaybackSupportWireName(AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED))
        assertEquals("offload_supported", directPlaybackSupportWireName(AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED))
        assertEquals("offload_gapless_supported", directPlaybackSupportWireName(AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED))
        assertEquals("bitstream_supported", directPlaybackSupportWireName(AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED))
    }

    @Test
    fun `probe specs include atmos and truehd separation`() {
        val specs = buildAudioCapabilityProbeSpecs()
        assertTrue(specs.any { it.bucket == "atmos" && it.encoding == C.ENCODING_E_AC3_JOC })
        assertTrue(specs.any { it.bucket == "truehd" && it.encoding == C.ENCODING_DOLBY_TRUEHD })
        assertTrue(specs.any { it.bucket == "dtsx" && it.encoding == C.ENCODING_DTS_UHD_P2 })
        assertTrue(specs.any { it.channelMask == AudioFormat.CHANNEL_OUT_7POINT1_SURROUND })
    }

    @Test
    fun `probe capability derivation uses bitstream only and can represent atmos without truehd or dtshd`() {
        val capabilities = buildAudioOutputCapabilitiesFromProbes(
            listOf(
                AudioPlaybackProbeEvidence("ac3", "ac3", AudioFormat.CHANNEL_OUT_5POINT1, 48_000, "bitstream_supported"),
                AudioPlaybackProbeEvidence("eac3", "eac3", AudioFormat.CHANNEL_OUT_5POINT1, 48_000, "offload_supported"),
                AudioPlaybackProbeEvidence("atmos", "eac3_joc", AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 48_000, "bitstream_supported"),
                AudioPlaybackProbeEvidence("truehd", "truehd", AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 48_000, "not_supported"),
                AudioPlaybackProbeEvidence("dtshd", "dtshd", AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 48_000, "not_supported"),
                AudioPlaybackProbeEvidence("dtsx", "dts_uhd_p2", AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 48_000, "not_supported")
            )
        )

        assertTrue(capabilities.ac3.supported)
        assertFalse(capabilities.eac3.supported)
        assertTrue(capabilities.atmos.supported)
        assertTrue(capabilities.atmos.passthroughLikely)
        assertFalse(capabilities.truehd.supported)
        assertFalse(capabilities.dtshd.supported)
        assertFalse(capabilities.dtsx.supported)
    }
}
