package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoRawProviderInjectionTest {
    @Test
    fun `feature packages do not inject raw retrofit or okhttp types across the whole tree`() {
        val offenders = productionRegexScan(
            forbiddenPatterns = linkedMapOf(
                "Retrofit" to Regex("""\bRetrofit\b"""),
                "OkHttpClient" to Regex("""\bOkHttpClient\b""")
            ),
            allowedPaths = productionAllowedPathSuffixes("/com/nexio/tv/core/di/") +
                productionAllowedPathContainsAll(
                    "/com/nexio/tv/data/integration/",
                    "/transport/"
                )
        )

        if (offenders.isNotEmpty()) {
            fail("Raw networking types escaped integration boundary: $offenders")
        }
    }
}
