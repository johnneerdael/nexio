package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.FanartTvCallId
import org.junit.Assert.assertFalse
import org.junit.Test

class FanartTvTraceRedactionTest {
    @Test
    fun `every traceable artifact for a fanarttv lookup excludes the api key`() {
        val rawKey = "07882f4309da827df559bb85b63793f9"
        val spec = FanartTvLookupShape.specFor(
            callId = FanartTvCallId(FanartTvCallId.Type.MOVIE, "550"),
            apiKey = rawKey
        )
        // Collect every candidate trace string this spec type exposes.
        // IntegrationSpec is a data class, so toString() includes all fields.
        // The key traceable surfaces are:
        // - toString(): data class representation of all fields
        // - operationKey: used for tracing/logging
        // - cacheKey: used for cache diagnostics and logging
        val traces = mutableListOf<String?>()
        traces += spec.toString()
        traces += spec.operationKey
        traces += spec.cacheKey
        traces += spec.headerPolicyId

        traces.filterNotNull().forEach { trace ->
            assertFalse(
                "trace must not contain raw api key, was: $trace",
                trace.contains(rawKey)
            )
        }
    }
}
