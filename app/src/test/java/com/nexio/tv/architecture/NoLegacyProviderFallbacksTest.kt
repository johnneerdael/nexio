package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoLegacyProviderFallbacksTest {
    @Test
    fun `boundary layers do not retain direct provider fallbacks after migration across the whole tree`() {
        val offenders = productionRegexScan(
            forbiddenPatterns = linkedMapOf(
                "executeAuthorizedRequest(" to Regex("""\bexecuteAuthorizedRequest(?:\s*<[^\n(]+>)?\s*\("""),
                "passThroughRuntime(" to Regex("""\bpassThroughRuntime\s*\("""),
                "PassThroughRuntime" to Regex("""\bPassThroughRuntime\b"""),
                "tmdbPassThroughRuntime(" to Regex("""\btmdbPassThroughRuntime\s*\("""),
                "tvdbPassThroughRuntime(" to Regex("""\btvdbPassThroughRuntime\s*\("""),
                "object : IntegrationRuntime" to Regex("""object\s*:\s*IntegrationRuntime\b"""),
                "= passThroughRuntime()" to Regex("""=\s*passThroughRuntime\s*\(\s*\)"""),
                "= tmdbPassThroughRuntime()" to Regex("""=\s*tmdbPassThroughRuntime\s*\(\s*\)"""),
                "= tvdbPassThroughRuntime()" to Regex("""=\s*tvdbPassThroughRuntime\s*\(\s*\)"""),
                "helper containing passThroughRuntime(" to Regex("""\b\w*passThroughRuntime\w*\s*\("""),
                "helper containing tmdbPassThroughRuntime(" to Regex("""\b\w*tmdbPassThroughRuntime\w*\s*\("""),
                "helper containing tvdbPassThroughRuntime(" to Regex("""\b\w*tvdbPassThroughRuntime\w*\s*\(""")
            ),
            allowedPaths = productionAllowedPathSuffixes("/com/nexio/tv/core/di/")
        )

        if (offenders.isNotEmpty()) {
            fail("Legacy provider fallback paths still exist in boundary layers: $offenders")
        }
    }
}
