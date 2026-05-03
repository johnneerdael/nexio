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
        val mode = FormatDetector.detect(colorInfo(C.COLOR_TRANSFER_ST2084), HdrMode.Auto)
        assertThat(mode).isEqualTo(CaptureMode.HDR_P010)
    }

    @Test
    fun `Auto + HLG yields HDR_P010`() {
        val mode = FormatDetector.detect(colorInfo(C.COLOR_TRANSFER_HLG), HdrMode.Auto)
        assertThat(mode).isEqualTo(CaptureMode.HDR_P010)
    }

    @Test
    fun `Auto + SDR transfer yields SDR_NV12`() {
        val mode = FormatDetector.detect(
            colorInfo(C.COLOR_TRANSFER_SDR, space = C.COLOR_SPACE_BT709),
            HdrMode.Auto,
        )
        assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
    }

    @Test
    fun `Auto + null colorInfo yields SDR_NV12`() {
        val mode = FormatDetector.detect(null, HdrMode.Auto)
        assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
    }

    @Test
    fun `Auto + unknown transfer yields SDR_NV12`() {
        val mode = FormatDetector.detect(
            colorInfo(C.COLOR_TRANSFER_LINEAR),
            HdrMode.Auto,
        )
        assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
    }

    @Test
    fun `ForceSdr always yields SDR_NV12 regardless of colorInfo`() {
        for (transfer in listOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG, C.COLOR_TRANSFER_SDR)) {
            val mode = FormatDetector.detect(colorInfo(transfer), HdrMode.ForceSdr)
            assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
        }
    }
}
