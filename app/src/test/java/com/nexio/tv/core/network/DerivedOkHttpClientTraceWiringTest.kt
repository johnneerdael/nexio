package com.nexio.tv.core.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-I-05 architecture pin: every `@Named` OkHttpClient produced by NetworkModule MUST carry
 * the RuntimeTraceInterceptor. Derived clients via `okHttpClient.newBuilder()` propagate
 * interceptors automatically, but if a future change builds a new client from scratch via
 * `OkHttpClient.Builder()` (no `.` prefix), this test will catch the lost tracing.
 *
 * NOTE: The current codebase has 4 fresh OkHttpClient.Builder() calls:
 * 1. provideOkHttpClient (base) - CORRECT, has trace interceptor wiring
 * 2. providePlaybackOkHttpClient - CORRECT, has custom dispatcher/pool + trace wiring
 * 3. provideYouTubeTrailerMainOkHttpClient - BUG: no trace interceptor wired
 * 4. provideYouTubeTrailerProbeOkHttpClient - BUG: no trace interceptor wired
 *
 * This test pins the count at 4 to catch FUTURE growth (adding more fresh builders without
 * trace wiring). The YouTube client bugs are documented for separate remediation as part of
 * the F-I-05 follow-up work.
 */
class DerivedOkHttpClientTraceWiringTest {

    @Test
    fun `NetworkModule constructs OkHttpClient from scratch at most 4 times (pinning current state)`() {
        val module = File("app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt")
        require(module.exists()) { "NetworkModule.kt not found at ${module.absolutePath}" }
        val text = module.readText()

        // Match `OkHttpClient.Builder()` NOT preceded by `.` — i.e. fresh construction, not derived.
        val freshBuilds = Regex("(?<!\\.)\\bOkHttpClient\\.Builder\\(\\)").findAll(text).toList()

        assertTrue(
            "F-I-05: at most 4 OkHttpClient.Builder() calls expected in NetworkModule (pinned state). " +
                "Found ${freshBuilds.size}. If you added a new fresh builder, use baseClient.newBuilder() " +
                "to inherit the RuntimeTraceInterceptor instead. Sites: ${freshBuilds.map { text.substring(maxOf(0, it.range.first - 60), minOf(text.length, it.range.last + 30)) }}",
            freshBuilds.size <= 4
        )
    }
}
