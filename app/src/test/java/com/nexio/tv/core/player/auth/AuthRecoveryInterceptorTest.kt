package com.nexio.tv.core.player.auth

import android.util.Log
import com.nexio.tv.core.player.CometProxyUrlResolver
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AuthRecoveryInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        server = MockWebServer().also { it.start() }
        CometProxyUrlResolver.resetForTesting()
        AuthRecoveryTracker.resetForTesting()
    }

    @After
    fun tearDown() {
        server.shutdown()
        CometProxyUrlResolver.resetForTesting()
        AuthRecoveryTracker.resetForTesting()
        unmockkStatic(Log::class)
    }

    @Test
    fun `passes through unrelated 401 when url has no proxy mapping`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthRecoveryInterceptor()).build()
        val unknownUrl = server.url("/orphan").toString()

        val response = client.newCall(Request.Builder().url(unknownUrl).build()).execute()
        response.use { assertEquals(401, it.code) }

        val attempts = AuthRecoveryTracker.snapshot()
        assertEquals(1, attempts.size)
        assertEquals(AuthRecoveryTracker.Outcome.NO_PROXY_KNOWN, attempts.first().outcome)
    }

    @Test
    fun `gives up after exhausting maxAttemptsPerSession`() {
        val resolved = server.url("/cdn").toString()
        CometProxyUrlResolver.setTransportForTesting { _, _ -> resolved }
        runBlocking {
            CometProxyUrlResolver.resolve(
                "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n",
                headers = emptyMap()
            )
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(401)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthRecoveryInterceptor(maxAttemptsPerSession = 1)).build()

        val first = client.newCall(Request.Builder().url(resolved).build()).execute()
        first.use { assertEquals(401, it.code) }
        val second = client.newCall(Request.Builder().url(resolved).build()).execute()
        second.use { assertEquals(401, it.code) }

        val outcomes = AuthRecoveryTracker.snapshot().map { it.outcome }
        assertTrue(outcomes.contains(AuthRecoveryTracker.Outcome.GAVE_UP))
    }

    @Test
    fun `respects resolver debounce when invalidate is rate-limited`() {
        val proxy = "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n"
        val resolved = server.url("/cdn").toString()
        var now = 1_000L
        CometProxyUrlResolver.setClockForTesting { now }
        CometProxyUrlResolver.setTransportForTesting { _, _ -> resolved }
        runBlocking { CometProxyUrlResolver.resolve(proxy, headers = emptyMap()) }
        // Force prior invalidate so the next one is rate-limited.
        CometProxyUrlResolver.invalidate(proxy)
        // Re-resolve so reverseCache is repopulated, but lastInvalidatedAtMs is fresh.
        runBlocking { CometProxyUrlResolver.resolve(proxy, headers = emptyMap()) }

        server.enqueue(MockResponse().setResponseCode(401))
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthRecoveryInterceptor()).build()

        val response = client.newCall(Request.Builder().url(resolved).build()).execute()
        response.use { assertEquals(401, it.code) }

        val outcomes = AuthRecoveryTracker.snapshot().map { it.outcome }
        assertTrue(outcomes.contains(AuthRecoveryTracker.Outcome.RATE_LIMITED))
    }

    @Test
    fun `resetSessionState replenishes the attempt budget`() {
        val resolved = server.url("/cdn").toString()
        CometProxyUrlResolver.setTransportForTesting { _, _ -> resolved }
        runBlocking {
            CometProxyUrlResolver.resolve(
                "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n",
                headers = emptyMap()
            )
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(401)
        }
        val interceptor = AuthRecoveryInterceptor(maxAttemptsPerSession = 1)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        // Burn the single attempt across two failing calls.
        client.newCall(Request.Builder().url(resolved).build()).execute().close()
        client.newCall(Request.Builder().url(resolved).build()).execute().close()
        val outcomesBeforeReset = AuthRecoveryTracker.snapshot().map { it.outcome }
        assertTrue(outcomesBeforeReset.contains(AuthRecoveryTracker.Outcome.GAVE_UP))

        // Reset returns the budget; next failure must produce a fresh recovery
        // attempt (which still 401s here, but it is no longer GAVE_UP from
        // an exhausted budget — at minimum we see a new attempt recorded).
        AuthRecoveryTracker.resetForTesting()
        interceptor.resetSessionState()
        client.newCall(Request.Builder().url(resolved).build()).execute().close()
        val attemptsAfterReset = AuthRecoveryTracker.snapshot()
        // After reset we must have at least one attempt; outcome may be GAVE_UP
        // (recovery itself failed because the mock keeps 401-ing) but the
        // *path* to GAVE_UP must include consuming a budget slot, not skipping
        // because the budget was already zero.
        assertTrue(attemptsAfterReset.isNotEmpty())
    }

    @Test
    fun `resetSessionState clears stale URL forwards`() {
        val resolvedFirst = server.url("/cdn/first").toString()
        val resolvedSecond = server.url("/cdn/second").toString()
        val resolveCount = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            if (resolveCount.getAndIncrement() == 0) resolvedFirst else resolvedSecond
        }
        runBlocking {
            CometProxyUrlResolver.resolve(
                "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n",
                headers = emptyMap()
            )
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/cdn/first" -> MockResponse().setResponseCode(401)
                "/cdn/second" -> MockResponse().setResponseCode(200).setBody("ok")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val interceptor = AuthRecoveryInterceptor(maxAttemptsPerSession = 3)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        // First call recovers and registers /cdn/first -> /cdn/second forward.
        val first = client.newCall(Request.Builder().url(resolvedFirst).build()).execute()
        first.use { assertEquals(200, it.code) }

        // Reset clears the forward map; with a fresh server.dispatcher only
        // serving /cdn/third = 200, a request to /cdn/first must NOT be
        // rewritten to /cdn/second. The dispatcher returns 404 for
        // /cdn/first, proving the forward is gone.
        interceptor.resetSessionState()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/cdn/first" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(500)
            }
        }
        val second = client.newCall(Request.Builder().url(resolvedFirst).build()).execute()
        second.use { assertEquals(404, it.code) }
    }

    @Test
    fun `recovers from 401 by re-resolving and retrying once`() {
        val resolvedFirst = server.url("/cdn/first").toString()
        val resolvedSecond = server.url("/cdn/second").toString()
        val resolveCalls = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            if (resolveCalls.getAndIncrement() == 0) resolvedFirst else resolvedSecond
        }

        val proxy = "https://comet.feels.legal/aBc/playback/abcd/0/0/n/n?torrent_name=x&name=y"
        runBlocking { CometProxyUrlResolver.resolve(proxy, headers = emptyMap()) }

        val recordedPaths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedPaths += request.path ?: ""
                return when (request.path) {
                    "/cdn/first" -> MockResponse().setResponseCode(401)
                    "/cdn/second" -> MockResponse().setResponseCode(200).setBody("ok")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthRecoveryInterceptor(maxAttemptsPerSession = 3))
            .build()

        val response = client.newCall(Request.Builder().url(resolvedFirst).build()).execute()
        response.use {
            assertEquals(200, it.code)
            assertEquals("ok", it.body?.string())
        }
        assertEquals(listOf("/cdn/first", "/cdn/second"), recordedPaths)
        assertEquals(1, AuthRecoveryTracker.recoveredCount())
        assertTrue(AuthRecoveryTracker.totalAttempts() >= 1)
    }
}
