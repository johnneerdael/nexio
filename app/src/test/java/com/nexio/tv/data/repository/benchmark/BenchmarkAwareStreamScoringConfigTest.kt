package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkAwareStreamScoringConfigTest {

    @Test
    fun `default config round trips through json`() {
        val config = BenchmarkAwareStreamScoringConfig.default()
        val reparsed = BenchmarkAwareStreamScoringConfig.fromJson(config.toJson())

        assertEquals(config.viability.minimumRatio, reparsed.viability.minimumRatio, 0.0)
        assertEquals(
            config.contentRewards.audio.getValue(ShadowAudioTier.TRUEHD_ATMOS),
            reparsed.contentRewards.audio.getValue(ShadowAudioTier.TRUEHD_ATMOS)
        )
        assertEquals(
            config.audioScoring.supportMultipliers.getValue(ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM),
            reparsed.audioScoring.supportMultipliers.getValue(ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM),
            0.0
        )
        assertTrue(reparsed.synergy.premiumFeatureStack > 0)
    }
}
