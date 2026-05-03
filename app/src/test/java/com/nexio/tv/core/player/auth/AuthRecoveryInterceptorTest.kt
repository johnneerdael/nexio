package com.nexio.tv.core.player.auth

import android.util.Log
import com.nexio.tv.core.player.CometProxyUrlResolver
import com.nexio.tv.core.player.ProxyResolution
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
            .addInterceptor(AuthRecoveryInterceptor())
            .build()

        client.newCall(Request.Builder().url(server.url("/orphan")).build()).execute().use {
            assertEquals(401, it.code)
        }

        val attempts = AuthRecoveryTracker.snapshot()
        assertEquals(1, attempts.size)
        assertEquals(AuthRecoveryTracker.Outcome.NO_PROXY_KNOWN, attempts.first().outcome)
    }

    @Test
    fun `resetSessionState clears stale URL forwards`() {
        val resolvedFirst = server.url("/cdn/first").toString()
        val resolvedSecond = server.url("/cdn/second").toString()
        val resolveCount = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            ProxyResolution.Redirected(
                if (resolveCount.getAndIncrement() == 0) resolvedFirst else resolvedSecond
            )
        }
        runBlocking { CometProxyUrlResolver.resolve(proxyUrl("reset"), headers = emptyMap()) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/cdn/first" -> MockResponse().setResponseCode(401)
                "/cdn/second" -> MockResponse().setResponseCode(200).setBody("ok")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val interceptor = AuthRecoveryInterceptor(maxAttemptsPerSession = 3)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        client.newCall(Request.Builder().url(resolvedFirst).build()).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals(mapOf(resolvedFirst to resolvedSecond), interceptor.staleForwardsSnapshotForTesting())

        interceptor.resetSessionState()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/cdn/first" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(500)
            }
        }
        client.newCall(Request.Builder().url(resolvedFirst).build()).execute().use {
            assertEquals(404, it.code)
        }
        assertTrue(interceptor.staleForwardsSnapshotForTesting().isEmpty())
    }

    @Test
    fun `recovering B to C promotes prior A to B forward`() {
        val urlA = server.url("/A").toString()
        val urlB = server.url("/B").toString()
        val urlC = server.url("/C").toString()
        val resolveCount = AtomicInteger(0)
        var clock = 1_000L
        CometProxyUrlResolver.setClockForTesting { clock }
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            ProxyResolution.Redirected(
                when (resolveCount.getAndIncrement()) {
                    0 -> urlA
                    1 -> urlB
                    else -> urlC
                }
            )
        }
        runBlocking { CometProxyUrlResolver.resolve(proxyUrl("promote"), headers = emptyMap()) }

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

        clock += 31_000L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/A" -> MockResponse().setResponseCode(401)
                "/B" -> MockResponse().setResponseCode(401)
                "/C" -> MockResponse().setResponseCode(200).setBody("ok")
                else -> MockResponse().setResponseCode(404)
            }
        }

        client.newCall(Request.Builder().url(urlB).build()).execute().close()

        val forwards = interceptor.staleForwardsSnapshotForTesting()
        assertEquals(urlC, forwards[urlA])
        assertEquals(urlC, forwards[urlB])
    }

    @Test
    fun `LRU evicts oldest forward entries when maxForwardEntries is exceeded`() {
        val interceptor = AuthRecoveryInterceptor(maxAttemptsPerSession = 100, maxForwardEntries = 4)
        val resolveResults = (1..10).map { server.url("/r$it").toString() }
        val resolveCount = AtomicInteger(0)
        var clock = 1_000L
        CometProxyUrlResolver.setClockForTesting { clock }
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            ProxyResolution.Redirected(
                resolveResults[resolveCount.getAndIncrement().coerceAtMost(resolveResults.lastIndex)]
            )
        }
        val proxies = (1..5).map { proxyUrl("lru$it") }
        runBlocking { proxies.forEach { CometProxyUrlResolver.resolve(it, headers = emptyMap()) } }
        val staleUrls = resolveResults.subList(0, 5)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(404)
                return if (staleUrls.any { it.endsWith(path) }) MockResponse().setResponseCode(401)
                else MockResponse().setResponseCode(200).setBody("ok")
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        staleUrls.forEachIndexed { index, stale ->
            client.newCall(Request.Builder().url(stale).build()).execute().close()
            clock += 31_000L * (index + 1)
        }

        val forwards = interceptor.staleForwardsSnapshotForTesting()
        assertEquals(4, forwards.size)
        assertFalse(forwards.containsKey(staleUrls[0]))
        assertTrue(forwards.containsKey(staleUrls[4]))
    }

    @Test
    fun `concurrent failing requests for same proxy coalesce onto a single recovery`() {
        val resolvedFirst = server.url("/cdn/first").toString()
        val resolvedSecond = server.url("/cdn/second").toString()
        val transportCalls = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            val call = transportCalls.getAndIncrement()
            Thread.sleep(150)
            ProxyResolution.Redirected(if (call == 0) resolvedFirst else resolvedSecond)
        }
        runBlocking { CometProxyUrlResolver.resolve(proxyUrl("coalesce"), headers = emptyMap()) }
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
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthRecoveryInterceptor(maxAttemptsPerSession = 5))
            .build()
        val callsBeforeRecovery = transportCalls.get()
        val pool = Executors.newFixedThreadPool(2)

        try {
            val results = (1..2).map {
                pool.submit<Int> {
                    client.newCall(Request.Builder().url(resolvedFirst).build())
                        .execute()
                        .use { it.code }
                }
            }
            assertEquals(listOf(200, 200), results.map { it.get(10, TimeUnit.SECONDS) })
        } finally {
            pool.shutdown()
        }

        assertEquals(1, transportCalls.get() - callsBeforeRecovery)
        assertEquals(2, firstHits.get())
        assertEquals(2, secondHits.get())
    }

    @Test
    fun `transient 502 retries the same URL even when proxy is unknown`() {
        val hits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val attempt = hits.incrementAndGet()
                return if (attempt == 1) MockResponse().setResponseCode(502)
                else MockResponse().setResponseCode(200).setBody("ok")
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthRecoveryInterceptor()).build()
        val orphanUrl = server.url("/orphan").toString()

        val response = client.newCall(Request.Builder().url(orphanUrl).build()).execute()
        response.use { assertEquals(200, it.code) }

        assertEquals("expected exactly one same-URL retry", 2, hits.get())
        val outcomes = AuthRecoveryTracker.snapshot().map { it.outcome }
        assertTrue(
            "expected TRANSIENT_RETRIED for non-proxied 502 phase-1 recovery, got $outcomes",
            AuthRecoveryTracker.Outcome.TRANSIENT_RETRIED in outcomes
        )
    }

    @Test
    fun `transient 502 records NO_PROXY_KNOWN only after phase-1 retry also fails`() {
        val hits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits.incrementAndGet()
                return MockResponse().setResponseCode(502)
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthRecoveryInterceptor()).build()
        val orphanUrl = server.url("/orphan").toString()

        val response = client.newCall(Request.Builder().url(orphanUrl).build()).execute()
        response.use { assertEquals(502, it.code) }

        assertEquals("expected one initial + one phase-1 retry", 2, hits.get())
        val outcomes = AuthRecoveryTracker.snapshot().map { it.outcome }
        assertTrue(
            "expected NO_PROXY_KNOWN after phase-1 also fails, got $outcomes",
            AuthRecoveryTracker.Outcome.NO_PROXY_KNOWN in outcomes
        )
    }

    private fun proxyUrl(tag: String): String =
        "https://comet.feels.legal/$tag/playback/x/0/0/n/n?torrent_name=t&name=n"
}
