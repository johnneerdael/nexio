package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.fail
import org.junit.Test

class NoDirectOkHttpOutsideRuntimeTransportPackagesTest {
    @Test
    fun `only runtime transport packages and DI own raw outbound HTTP primitives`() {
        val offenders = productionRegexScanMatches(
            forbiddenPatterns = linkedMapOf(
                "OkHttpClient" to Regex("""\bOkHttpClient\b"""),
                "Request.Builder" to Regex("""\bRequest\s*\.\s*Builder\b"""),
                ".newCall(" to Regex("""\.\s*newCall\s*\("""),
                "HttpURLConnection" to Regex("""\bHttpURLConnection\b"""),
                ".openConnection()" to Regex("""\.\s*openConnection\s*\("""),
                "URL(...).openConnection(" to Regex("""\bURL\s*\([^\n]+\)\s*\.\s*openConnection\s*\(""")
            ),
            allowedPaths = productionAllowedPathSuffixes("/com/nexio/tv/core/di/") +
                productionAllowedPathContainsAll(
                    "/com/nexio/tv/data/integration/",
                    "/transport/"
                )
        ).mapNotNull(::removeCoilImageRequestFalsePositives)
            .map(PathPatternMatches::format)

        if (offenders.isNotEmpty()) {
            fail("Raw outbound HTTP escaped runtime-owned transport packages: $offenders")
        }
    }

    private fun removeCoilImageRequestFalsePositives(offender: PathPatternMatches): PathPatternMatches? {
        if ("Request.Builder" !in offender.matches) return offender

        val content = File(offender.path).readText()
        val hasRawRequestBuilder = Regex("""(?<!Image)Request\s*\.\s*Builder\s*\(""").containsMatchIn(content)
        if (hasRawRequestBuilder) return offender

        val filteredMatches = offender.matches.filterNot { it == "Request.Builder" }
        return filteredMatches.takeIf(List<String>::isNotEmpty)
            ?.let { offender.copy(matches = filteredMatches) }
    }
}
