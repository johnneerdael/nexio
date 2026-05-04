package com.nexio.tv.integrations.hyperhdr.capture

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import com.google.common.truth.Truth.assertThat
import com.nexio.tv.integrations.hyperhdr.data.HdrMode
import org.junit.Test

class FormatDetectorTest {

    private fun colorInfo(transfer: Int, space: Int = C.COLOR_SPACE_BT2020): ColorInfo =
        ColorInfo.Builder()
            .setColorSpace(space)
            .setColorTransfer(transfer)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .build()

    @Test
    fun `Auto + ST2084 yields HDR_P010`() {
        val mode = FormatDetector.detect(
            colorInfo(C.COLOR_TRANSFER_ST2084),
            HdrMode.Auto,
            deviceComposesWideColor = true,
            serverSupportsP010 = true,
        )
        assertThat(mode).isEqualTo(CaptureMode.HDR_P010)
    }

    @Test
    fun `Auto + HLG yields HDR_P010`() {
        val mode = FormatDetector.detect(
            colorInfo(C.COLOR_TRANSFER_HLG),
            HdrMode.Auto,
            deviceComposesWideColor = true,
            serverSupportsP010 = true,
        )
        assertThat(mode).isEqualTo(CaptureMode.HDR_P010)
    }

    @Test
    fun `Auto + SDR transfer yields SDR_NV12`() {
        val mode = FormatDetector.detect(
            colorInfo(C.COLOR_TRANSFER_SDR, space = C.COLOR_SPACE_BT709),
            HdrMode.Auto,
            deviceComposesWideColor = true,
            serverSupportsP010 = true,
        )
        assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
    }

    @Test
    fun `Auto + null colorInfo yields SDR_NV12`() {
        val mode = FormatDetector.detect(
            null,
            HdrMode.Auto,
            deviceComposesWideColor = true,
            serverSupportsP010 = true,
        )
        assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
    }

    @Test
    fun `Auto + unknown transfer yields SDR_NV12`() {
        val mode = FormatDetector.detect(
            colorInfo(C.COLOR_TRANSFER_LINEAR),
            HdrMode.Auto,
            deviceComposesWideColor = true,
            serverSupportsP010 = true,
        )
        assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
    }

    @Test
    fun `ForceSdr always yields SDR_NV12 regardless of colorInfo`() {
        for (transfer in listOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG, C.COLOR_TRANSFER_SDR)) {
            val mode = FormatDetector.detect(
                colorInfo(transfer),
                HdrMode.ForceSdr,
                deviceComposesWideColor = true,
                serverSupportsP010 = true,
            )
            assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
        }
    }

    @Test
    fun `device without wide-color support forces SDR_NV12 regardless of source HDR`() {
        val mode = FormatDetector.detect(
            colorInfo(C.COLOR_TRANSFER_ST2084),
            HdrMode.Auto,
            deviceComposesWideColor = false,
            serverSupportsP010 = true,
        )
        assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
    }

    @Test
    fun `device without wide-color support forces SDR_NV12 even if user picks ForceSdr`() {
        // ForceSdr already forces SDR; this test just confirms the device gate doesn't
        // accidentally bypass it.
        val mode = FormatDetector.detect(
            colorInfo(C.COLOR_TRANSFER_HLG),
            HdrMode.ForceSdr,
            deviceComposesWideColor = false,
            serverSupportsP010 = true,
        )
        assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
    }

    @Test
    fun `HDR source on wide-color device yields SDR_NV12 when server lacks P010 support`() {
        val mode = FormatDetector.detect(
            colorInfo(C.COLOR_TRANSFER_ST2084),
            HdrMode.Auto,
            deviceComposesWideColor = true,
            serverSupportsP010 = false,
        )
        assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
    }

    @Test
    fun `HLG source on wide-color device yields SDR_NV12 when server lacks P010 support`() {
        val mode = FormatDetector.detect(
            colorInfo(C.COLOR_TRANSFER_HLG),
            HdrMode.Auto,
            deviceComposesWideColor = true,
            serverSupportsP010 = false,
        )
        assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
    }
}
