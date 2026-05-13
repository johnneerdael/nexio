package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.FanartTvCallId
import com.nexio.tv.core.integration.IntegrationCachePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FanartTvLookupShapeTest {
    @Test
    fun `redacted url for trace contains no api_key value`() {
        val spec = FanartTvLookupShape.specFor(
            callId = FanartTvCallId(FanartTvCallId.Type.MOVIE, "550"),
            apiKey = "07882f4309da827df559bb85b63793f9"
        )
        // IntegrationSpec is a data class — toString() includes all fields.
        // cacheKey uses credentialHash (SHA-256 prefix), not the raw key.
        val trace = spec.toString()
        assertFalse(
            "trace must not contain raw api key, was: $trace",
            trace.contains("07882f4309da827df559bb85b63793f9")
        )
    }

    @Test
    fun `cache policy is CacheFirst with 14d ttl`() {
        val spec = FanartTvLookupShape.specFor(
            callId = FanartTvCallId(FanartTvCallId.Type.MOVIE, "550"),
            apiKey = "k"
        )
        val expectedTtl = 14L * 24 * 60 * 60 * 1000
        val policy = spec.cachePolicy
        assertTrue(
            "expected CacheFirst, got $policy",
            policy is IntegrationCachePolicy.CacheFirst
        )
        val cacheFirst = policy as IntegrationCachePolicy.CacheFirst
        assertTrue(
            "expected ttlMs=$expectedTtl in policy, got ttlMs=${cacheFirst.ttlMs}",
            cacheFirst.ttlMs == expectedTtl
        )
    }
}
