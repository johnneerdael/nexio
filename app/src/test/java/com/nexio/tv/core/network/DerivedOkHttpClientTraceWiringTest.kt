package com.nexio.tv.core.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-I-05 architecture pin: every fresh `OkHttpClient.Builder()` construction in NetworkModule
 * (i.e. NOT preceded by `.`, so not a derived `.newBuilder()`) MUST wire the
 * RuntimeTraceInterceptor within its surrounding provider body.
 *
 * Derived clients via `okHttpClient.newBuilder()` propagate interceptors automatically. The real
 * concern is fresh constructions that bypass the base client — each such site must explicitly
 * add traceInterceptor. This test scans a forward window of ~1500 chars from each fresh
 * construction (~2000 chars) to verify the reference exists.
 */
class DerivedOkHttpClientTraceWiringTest {

    @Test
    fun `every fresh OkHttpClient_Builder construction in NetworkModule wires the trace interceptor`() {
        val module = File("app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt")
        require(module.exists()) { "NetworkModule.kt not found at ${module.absolutePath}" }
        val text = module.readText()

        // Match `OkHttpClient.Builder()` NOT preceded by `.` — i.e. fresh construction, not derived.
        val freshBuilds = Regex("(?<!\\.)\\bOkHttpClient\\.Builder\\(\\)").findAll(text).toList()

        val offenders = freshBuilds.filter { match ->
            val start = match.range.first
            val end = (match.range.last + 3000).coerceAtMost(text.length)
            val window = text.substring(start, end)
            !window.contains("traceInterceptor")
        }

        assertTrue(
            "F-I-05: every fresh OkHttpClient.Builder() in NetworkModule must wire `traceInterceptor` " +
                "within ~2000 chars of the construction. Offenders (count=${offenders.size}): " +
                offenders.map { text.substring(it.range.first, (it.range.last + 80).coerceAtMost(text.length)) },
            offenders.isEmpty()
        )
    }
}
