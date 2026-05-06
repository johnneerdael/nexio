package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NoDetailUiTmdbEnsureIdArchitectureTest {
    @Test
    fun `detail metadata enrichment must not call TmdbService ensureTmdbId`() {
        val file = File("app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt")
        val source = file.readText()
        val enrichMetaBody = source.functionBody("private suspend fun enrichMeta(")
        val offenders = Regex("""tmdbService\.ensureTmdbId|\.ensureTmdbId\(""")
            .findAll(enrichMetaBody.text)
            .map { match ->
                "${file.path}:${enrichMetaBody.startLine + enrichMetaBody.text.substring(0, match.range.first).count { ch -> ch == '\n' }}"
            }
            .toList()

        assertTrue(
            "Detail metadata enrichment must use CanonicalIdentityResolver/StableIdBundleResolver output, not TmdbService.ensureTmdbId: $offenders",
            offenders.isEmpty()
        )
    }

    private data class FunctionBody(
        val text: String,
        val startLine: Int
    )

    private fun String.functionBody(signature: String): FunctionBody {
        val signatureIndex = indexOf(signature)
        check(signatureIndex >= 0) { "Missing function signature: $signature" }
        val bodyStart = indexOf('{', signatureIndex)
        check(bodyStart >= 0) { "Missing function body for: $signature" }
        var depth = 0
        for (index in bodyStart until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return FunctionBody(
                            text = substring(bodyStart, index + 1),
                            startLine = substring(0, bodyStart).count { ch -> ch == '\n' } + 1
                        )
                    }
                }
            }
        }
        error("Unclosed function body for: $signature")
    }
}
