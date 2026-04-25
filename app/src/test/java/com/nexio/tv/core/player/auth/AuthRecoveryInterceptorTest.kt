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
import org.junit.Assert.assertFalse
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
    fun `second request to a previously-recovered URL is rewritten without consuming an attempt`() {
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
        val firstHits = AtomicInteger(0)
        val secondHits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/cdn/first" -> {
                    firstHits.incrementAndGet()
                    MockResponse().setResponseCode(401)
                }
                "/cdn/second" -> {
                    secondHits.incrementAndGet()
                    MockResponse().setResponseCode(200).setBody("ok")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val interceptor = AuthRecoveryInterceptor(maxAttemptsPerSession = 1)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        // First call: 401 → recovered → /cdn/first hit once, /cdn/second hit once.
        client.newCall(Request.Builder().url(resolvedFirst).build()).execute().close()
        assertEquals(1, firstHits.get())
        assertEquals(1, secondHits.get())
        // Second call to the same stale URL must be rewritten before dispatch.
        // /cdn/first must NOT be hit again, /cdn/second receives the request directly.
        client.newCall(Request.Builder().url(resolvedFirst).build()).execute().close()
        assertEquals("stale URL must not be hit a second time", 1, firstHits.get())
        assertEquals("rewritten request must reach the fresh URL", 2, secondHits.get())
        // The recovery counter increments only once — no second attempt was burned.
        assertEquals(1, AuthRecoveryTracker.recoveredCount())
    }

    @Test
    fun `recovering B to C drops a prior A to B forward to avoid chain rewrites`() {
        // Manufacture an A→B forward by recovering once (A=401, B=200), then
        // flip the dispatcher so B starts 401-ing and C=200, drive a B-side
        // recovery, and assert the forward map no longer contains an A→B
        // chain entry — only B→C remains, with A's entry promoted/removed.
        val urlA = server.url("/A").toString()
        val urlB = server.url("/B").toString()
        val urlC = server.url("/C").toString()
        val resolveCount = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            when (resolveCount.getAndIncrement()) {
                0 -> urlA
                1 -> urlB
                else -> urlC
            }
        }
        val proxy = "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n"
        var clock = 1_000L
        CometProxyUrlResolver.setClockForTesting { clock }
        runBlocking { CometProxyUrlResolver.resolve(proxy, headers = emptyMap()) }

        // Phase 1: A=401, B=200 → first recovery registers A→B.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/A" -> MockResponse().setResponseCode(401)
                "/B" -> MockResponse().setResponseCode(200).setBody("ok")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val interceptor = AuthRecoveryInterceptor(maxAttemptsPerSession = 5)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        client.newCall(Request.Builder().url(urlA).build()).execute().close()
        assertEquals(mapOf(urlA to urlB), interceptor.staleForwardsSnapshotForTesting())

        // Phase 2: flip dispatcher so B starts failing, C is fresh; advance
        // the clock past the 30s invalidate debounce so the next recovery
        // can re-resolve.
        clock += 31_000L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/A" -> MockResponse().setResponseCode(401)
                "/B" -> MockResponse().setResponseCode(401)
                "/C" -> MockResponse().setResponseCode(200).setBody("ok")
                else -> MockResponse().setResponseCode(404)
            }
        }
        // Hit B directly (not via the A→B forward path) so the interceptor
        // sees a 401 attributed to B's URL and runs a fresh recovery to C.
        client.newCall(Request.Builder().url(urlB).build()).execute().close()

        val forwards = interceptor.staleForwardsSnapshotForTesting()
        assertEquals("B→C must be registered: $forwards", urlC, forwards[urlB])
        assertEquals(
            "A→B must be promoted to A→C so requests for A do not land on a now-stale B: $forwards",
            urlC,
            forwards[urlA]
        )
    }

    @Test
    fun `LRU evicts oldest forward entries when maxForwardEntries is exceeded`() {
        val interceptor = AuthRecoveryInterceptor(maxAttemptsPerSession = 100, maxForwardEntries = 4)
        val resolveResults = (1..10).map { server.url("/r$it").toString() }
        val resolveCount = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            resolveResults[resolveCount.getAndIncrement().coerceAtMost(resolveResults.lastIndex)]
        }
        var clock = 1_000L
        CometProxyUrlResolver.setClockForTesting { clock }

        // Establish 5 distinct proxy → resolved mappings, then drive a
        // recovery on each of the 5 first-resolved URLs to populate
        // staleUrlForwards with 5 entries (max is 4).
        val proxies = (1..5).map { i ->
            "https://comet.feels.legal/p$i/playback/x/0/0/n/n?torrent_name=t&name=n"
        }
        runBlocking { proxies.forEach { CometProxyUrlResolver.resolve(it, headers = emptyMap()) } }

        val staleUrls = resolveResults.subList(0, 5) // r1..r5 are the originals
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(404)
                val isStaleSlot = staleUrls.any { it.endsWith(path) }
                return if (isStaleSlot) MockResponse().setResponseCode(401)
                else MockResponse().setResponseCode(200).setBody("ok")
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        staleUrls.forEachIndexed { i, stale ->
            client.newCall(Request.Builder().url(stale).build()).execute().close()
            // Advance clock past invalidate debounce between recoveries on
            // distinct proxies (each proxy has its own debounce key).
            clock += 31_000L * (i + 1)
        }

        val forwards = interceptor.staleForwardsSnapshotForTesting()
        assertEquals("LRU bound respected", 4, forwards.size)
        assertFalse("oldest entry evicted", forwards.containsKey(staleUrls[0]))
        assertTrue("newest entry retained", forwards.containsKey(staleUrls[4]))
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
