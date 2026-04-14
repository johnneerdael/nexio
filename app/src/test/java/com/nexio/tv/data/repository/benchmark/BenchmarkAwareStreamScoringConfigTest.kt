package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
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
            config.transportRewards.ratioBands.first().base,
            reparsed.transportRewards.ratioBands.first().base
        )
    }

    @Test
    fun `config parser maps legacy source rewards to release type rewards`() {
        val json = javaClass.classLoader!!
            .getResourceAsStream("benchmark_scoring_corpus/variants/default.json")!!
            .bufferedReader()
            .readText()

        val reparsed = BenchmarkAwareStreamScoringConfig.fromJson(json)

        assertEquals(
            28,
            reparsed.contentRewards.releaseType?.get(ShadowReleaseType.REMUX)
        )
        assertEquals(
            12,
            reparsed.contentRewards.releaseType?.get(ShadowReleaseType.WEBDL)
        )
    }
}
