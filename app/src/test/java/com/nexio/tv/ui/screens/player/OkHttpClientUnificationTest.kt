package com.nexio.tv.ui.screens.player

import com.nexio.tv.instrumentation.PlaybackOkHttpEventListener
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Verifies that the playback and benchmark OkHttpClient instances share the same
 * [ConnectionPool] and [Dispatcher] — the key requirement of B1 (OkHttp unification).
 *
 * In production the two clients are wired by NetworkModule:
 *   - @Named("playback") is the base client with shared pool + dispatcher.
 *   - @Named("benchmark") is derived via newBuilder() from the playback client.
 *
 * newBuilder() preserves the original pool and dispatcher references, so this test
 * constructs the same pattern and asserts referential equality on both.
 */
class OkHttpClientUnificationTest {

    private fun buildPlaybackClient(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 12
        }
        val pool = ConnectionPool(5, 5, TimeUnit.MINUTES)
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(pool)
            .callTimeout(120_000L, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun buildBenchmarkClient(playbackClient: OkHttpClient): OkHttpClient =
        playbackClient.newBuilder()
            .callTimeout(4, TimeUnit.MINUTES)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private fun buildPlaybackTracedClient(playbackClient: OkHttpClient): OkHttpClient =
        playbackClient.newBuilder()
            .eventListenerFactory(PlaybackOkHttpEventListener.FACTORY)
            .build()

    @Test
    fun `playback and benchmark clients share the same ConnectionPool`() {
        val playback = buildPlaybackClient()
        val benchmark = buildBenchmarkClient(playback)
        assertSame(
            "playback and benchmark must share the same ConnectionPool",
            playback.connectionPool,
            benchmark.connectionPool
        )
    }

    @Test
    fun `playback and benchmark clients share the same Dispatcher`() {
        val playback = buildPlaybackClient()
        val benchmark = buildBenchmarkClient(playback)
        assertSame(
            "playback and benchmark must share the same Dispatcher",
            playback.dispatcher,
            benchmark.dispatcher
        )
    }

    @Test
    fun `playbackTraced child keeps playback pool and dispatcher`() {
        val playback = buildPlaybackClient()
        val playbackTraced = buildPlaybackTracedClient(playback)

        assertSame(
            "playbackTraced must share the playback ConnectionPool",
            playback.connectionPool,
            playbackTraced.connectionPool
        )
        assertSame(
            "playbackTraced must share the playback Dispatcher",
            playback.dispatcher,
            playbackTraced.dispatcher
        )
    }

    @Test
    fun `benchmark client does not inherit playback traced listener`() {
        val playback = buildPlaybackClient()
        val playbackTraced = buildPlaybackTracedClient(playback)
        val benchmark = buildBenchmarkClient(playback)

        assertSame(
            "playbackTraced should use the WP5 playback listener factory",
            PlaybackOkHttpEventListener.FACTORY,
            playbackTraced.eventListenerFactory
        )
        assertFalse(
            "benchmark must derive from @Named(\"playback\"), not @Named(\"playbackTraced\")",
            benchmark.eventListenerFactory === PlaybackOkHttpEventListener.FACTORY
        )
    }

    /**
     * Regression guard: PlayerMediaSourceFactory.shutdown() must NOT shut down the
     * shared OkHttp dispatcher's executor or evict the connection pool. The client
     * is a Hilt singleton (NetworkModule @Named("playback")) shared with the benchmark
     * client. Tearing it down kills the singleton for the entire app and the next
     * playback / benchmark request fails with:
     *   java.io.InterruptedIOException: executor rejected
     * (okhttp3.RealCall wraps the underlying RejectedExecutionException.)
     *
     * This test reads the source file directly because PlayerMediaSourceFactory has
     * heavy Android / Hilt dependencies that are impractical to instantiate here.
     */
    @Test
    fun `PlayerMediaSourceFactory shutdown does not tear down shared OkHttp client`() {
        val source = File("src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt")
        assertTrue("source file not found at ${source.absolutePath}", source.exists())
        val text = source.readText()
        // Locate the shutdown() method body and assert the forbidden calls are absent.
        val shutdownStart = text.indexOf("fun shutdown()")
        assertTrue("shutdown() not found", shutdownStart >= 0)
        // Take a generous window after fun shutdown() to cover the body, then strip
        // line comments so the warning comment in shutdown() doesn't false-positive
        // its own forbidden substring.
        val rawWindow = text.substring(shutdownStart, minOf(shutdownStart + 2000, text.length))
        val window = rawWindow.lineSequence()
            .map { line -> line.substringBefore("//") }
            .joinToString("\n")
        assertFalse(
            "PlayerMediaSourceFactory.shutdown() must not call dispatcher.executorService.shutdown() — " +
                "it kills the shared singleton client and breaks the next playback session.",
            window.contains("dispatcher.executorService.shutdown")
        )
        assertFalse(
            "PlayerMediaSourceFactory.shutdown() must not call connectionPool.evictAll() — " +
                "it evicts the shared connection pool used by the benchmark client.",
            window.contains("connectionPool.evictAll")
        )
    }

    @Test
    fun `shared dispatcher has maxRequestsPerHost of 12`() {
        val playback = buildPlaybackClient()
        val benchmark = buildBenchmarkClient(playback)
        // Both clients see the same dispatcher value
        val expected = 12
        assert(playback.dispatcher.maxRequestsPerHost == expected) {
            "playback dispatcher.maxRequestsPerHost expected $expected but was ${playback.dispatcher.maxRequestsPerHost}"
        }
        assert(benchmark.dispatcher.maxRequestsPerHost == expected) {
            "benchmark dispatcher.maxRequestsPerHost expected $expected but was ${benchmark.dispatcher.maxRequestsPerHost}"
        }
    }
}
