package com.nexio.tv.core.player

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class CometProxyUrlResolverTest {

    private val cometUrl = "https://comet.feels.legal/cfg/playback/" +
        "09b382fa312ad70adaba13d707b500697e72e6fb/0/0/n/n?torrent_name=x&name=y"
    private val resolvedUrl = "https://43-4.download.real-debrid.com/d/TOKEN/file.mp4"

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        CometProxyUrlResolver.resetForTesting()
    }

    @After
    fun tearDown() {
        CometProxyUrlResolver.resetForTesting()
        unmockkStatic(Log::class)
    }

    @Test
    fun `isCometProxy matches known Comet hosts`() {
        assertTrue(CometProxyUrlResolver.isCometProxy("https://comet.feels.legal/x/playback/h/0/0/n/n"))
        assertTrue(CometProxyUrlResolver.isCometProxy("https://COMET.FEELS.LEGAL/x/playback/h/0/0/n/n"))
        assertTrue(CometProxyUrlResolver.isCometProxy("https://cometfortheweebs.midnightignite.me/x/playback/h/0/0/n/n"))
        assertTrue(CometProxyUrlResolver.isCometProxy("https://comet.elfhosted.com/x/playback/h/0/0/n/n"))
        assertTrue(CometProxyUrlResolver.isCometProxy("https://comet.stremio.ru/x/playback/h/0/0/n/n"))
    }

    @Test
    fun `isCometProxy matches Meteor host`() {
        assertTrue(CometProxyUrlResolver.isCometProxy("https://meteorfortheweebs.midnightignite.me/x/play/h/0/0/0/f.mkv"))
        assertTrue(CometProxyUrlResolver.isCometProxy("https://METEORFORTHEWEEBS.MIDNIGHTIGNITE.ME/x/play/h/0/0/0/f.mkv"))
    }

    @Test
    fun `isCometProxy matches known StremThru hosts`() {
        assertTrue(CometProxyUrlResolver.isCometProxy("https://stremthru.atbphosting.com/_/strem/h/0/f.mkv"))
        assertTrue(CometProxyUrlResolver.isCometProxy("https://stremthrufortheweebs.midnightignite.me/_/strem/h/0/f.mkv"))
        assertTrue(CometProxyUrlResolver.isCometProxy("https://stremthru.elfhosted.com/_/strem/h/0/f.mkv"))
        assertTrue(CometProxyUrlResolver.isCometProxy("https://stremthru.fortheweak.cloud/_/strem/h/0/f.mkv"))
        assertTrue(CometProxyUrlResolver.isCometProxy("https://stremthru.13377001.xyz/_/strem/h/0/f.mkv"))
        assertTrue(CometProxyUrlResolver.isCometProxy("https://stremthru.stremio.ru/_/strem/h/0/f.mkv"))
    }

    @Test
    fun `isCometProxy rejects unknown hosts without addonHost`() {
        assertFalse(CometProxyUrlResolver.isCometProxy("https://torrentio.strem.fun/resolve/realdebrid/key/h/null/0/f.mkv"))
        assertFalse(CometProxyUrlResolver.isCometProxy("https://stremthru.example.com/_/strem/h/0/f.mkv"))
        assertFalse(CometProxyUrlResolver.isCometProxy("https://meteor.example.com/x/play/h/0/0/0/f.mkv"))
        assertFalse(CometProxyUrlResolver.isCometProxy("not a url"))
        assertFalse(CometProxyUrlResolver.isCometProxy(""))
    }

    @Test
    fun `isCometProxy rejects known host when path lacks proxy marker`() {
        // Host is in the static list but the path is not a known proxy endpoint.
        assertFalse(CometProxyUrlResolver.isCometProxy("https://comet.feels.legal/static/logo.png"))
        assertFalse(CometProxyUrlResolver.isCometProxy("https://stremthru.elfhosted.com/health"))
    }

    @Test
    fun `isCometProxy matches via addonHost even when host is not in static list`() {
        // Self-hosted Comet on a custom domain — addonHost gate lets it through.
        assertTrue(
            CometProxyUrlResolver.isCometProxy(
                url = "https://comet.my-seedbox.net/cfg/playback/h/0/0/n/n",
                addonHost = "comet.my-seedbox.net"
            )
        )
        // Case-insensitive host comparison.
        assertTrue(
            CometProxyUrlResolver.isCometProxy(
                url = "https://Stremthru.MyDomain.Example/_/strem/h/0/f.mkv",
                addonHost = "stremthru.mydomain.example"
            )
        )
    }

    @Test
    fun `isCometProxy rejects addonHost mismatch`() {
        // addonHost provided but stream URL is on a different host → false.
        assertFalse(
            CometProxyUrlResolver.isCometProxy(
                url = "https://evil.example.com/playback/h/0/0/n/n",
                addonHost = "comet.my-seedbox.net"
            )
        )
    }

    @Test
    fun `isCometProxy ignores path markers when addonHost matches`() {
        // When the stream is on the addon's own host, any path is accepted —
        // Gate A trusts the addon to know its own endpoints.
        assertTrue(
            CometProxyUrlResolver.isCometProxy(
                url = "https://comet.my-seedbox.net/custom/endpoint/h",
                addonHost = "comet.my-seedbox.net"
            )
        )
    }

    @Test
    fun `resolve returns null for non-Comet urls`() = runBlocking {
        CometProxyUrlResolver.setTransportForTesting { _, _ -> ProxyResolution.Redirected("should not be called") }
        val result = CometProxyUrlResolver.resolve(
            "https://torrentio.strem.fun/resolve/realdebrid/key/h/null/0/f.mkv",
            headers = null
        )
        assertEquals(ProxyResolution.NotEligible, result)
    }

    @Test
    fun `resolve returns Location and caches on 302`() = runBlocking {
        val calls = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { url, _ ->
            calls.incrementAndGet()
            assertEquals(cometUrl, url)
            ProxyResolution.Redirected(resolvedUrl)
        }

        val first = CometProxyUrlResolver.resolve(cometUrl, null)
        val second = CometProxyUrlResolver.resolve(cometUrl, null)

        assertEquals(ProxyResolution.Redirected(resolvedUrl), first)
        assertEquals(ProxyResolution.Redirected(resolvedUrl), second)
        assertEquals(1, calls.get())
    }

    @Test
    fun `classifyHttpResponse returns placeholder only for video content type`() {
        assertEquals(
            ProxyResolution.Placeholder,
            classifyHttpResponse(code = 200, location = null, contentType = "video/mp4")
        )
        assertEquals(
            ProxyResolution.ResolveFailed,
            classifyHttpResponse(code = 200, location = null, contentType = "text/html")
        )
    }

    @Test
    fun `resolve re-fetches after TTL expiry`() = runBlocking {
        val calls = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            calls.incrementAndGet()
            ProxyResolution.Redirected("$resolvedUrl?v=${calls.get()}")
        }
        val clock = object {
            var nowMs = 1_000L
        }
        CometProxyUrlResolver.setClockForTesting { clock.nowMs }

        val first = CometProxyUrlResolver.resolve(cometUrl, null)
        assertEquals(ProxyResolution.Redirected("$resolvedUrl?v=1"), first)

        // Within TTL → cache hit
        clock.nowMs += 10 * 60 * 1000
        val cached = CometProxyUrlResolver.resolve(cometUrl, null)
        assertEquals(ProxyResolution.Redirected("$resolvedUrl?v=1"), cached)
        assertEquals(1, calls.get())

        // Past TTL → re-fetch
        clock.nowMs += 51 * 60 * 1000
        val refreshed = CometProxyUrlResolver.resolve(cometUrl, null)
        assertEquals(ProxyResolution.Redirected("$resolvedUrl?v=2"), refreshed)
        assertEquals(2, calls.get())
    }

    @Test
    fun `concurrent resolvers share one network call`() = runBlocking {
        val calls = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            calls.incrementAndGet()
            Thread.sleep(50)
            ProxyResolution.Redirected(resolvedUrl)
        }

        val results = (1..8)
            .map { async { CometProxyUrlResolver.resolve(cometUrl, null) } }
            .awaitAll()

        assertTrue(results.all { it == ProxyResolution.Redirected(resolvedUrl) })
        assertEquals(1, calls.get())
    }

    @Test
    fun `resolve returns null on transport failure and does not cache`() = runBlocking {
        val calls = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            calls.incrementAndGet()
            ProxyResolution.ResolveFailed
        }

        val first = CometProxyUrlResolver.resolve(cometUrl, null)
        val second = CometProxyUrlResolver.resolve(cometUrl, null)

        assertEquals(ProxyResolution.ResolveFailed, first)
        assertEquals(ProxyResolution.ResolveFailed, second)
        assertEquals(2, calls.get())
    }

    @Test
    fun `resolve propagates headers to transport but strips Range`() = runBlocking {
        var capturedHeaders: Map<String, String>? = null
        CometProxyUrlResolver.setTransportForTesting { _, headers ->
            capturedHeaders = headers
            ProxyResolution.Redirected(resolvedUrl)
        }

        CometProxyUrlResolver.resolve(
            cometUrl,
            headers = linkedMapOf("Authorization" to "Bearer x", "Range" to "bytes=100-")
        )

        assertNotNull(capturedHeaders)
        assertEquals("Bearer x", capturedHeaders!!["Authorization"])
        // Range must be untouched at this layer — the default transport strips it when
        // building the OkHttp request. The resolver itself is transport-agnostic.
        assertEquals("bytes=100-", capturedHeaders!!["Range"])
    }

    @Test
    fun `resolveBlocking returns cached value`() {
        CometProxyUrlResolver.setTransportForTesting { _, _ -> ProxyResolution.Redirected(resolvedUrl) }
        val result = CometProxyUrlResolver.resolveBlocking(cometUrl, null)
        assertEquals(ProxyResolution.Redirected(resolvedUrl), result)
    }

    @Test
    fun `resolveBlocking returns null for non-Comet`() {
        CometProxyUrlResolver.setTransportForTesting { _, _ -> ProxyResolution.Redirected("unexpected") }
        val result = CometProxyUrlResolver.resolveBlocking(
            "https://torrentio.strem.fun/resolve/x/y/z/null/0/f.mkv",
            null
        )
        assertEquals(ProxyResolution.NotEligible, result)
    }

    @Test
    fun `prewarm populates cache for later resolve`() = runBlocking {
        val calls = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            calls.incrementAndGet()
            ProxyResolution.Redirected(resolvedUrl)
        }

        val job = CometProxyUrlResolver.prewarm(cometUrl, null)
        assertNotNull(job)
        job!!.join()

        val resolved = CometProxyUrlResolver.resolve(cometUrl, null)
        assertEquals(ProxyResolution.Redirected(resolvedUrl), resolved)
        assertEquals(1, calls.get())
    }

    @Test
    fun `prewarm is a noop for non-Comet urls`() {
        val job = CometProxyUrlResolver.prewarm(
            "https://torrentio.strem.fun/resolve/x/y/z/null/0/f.mkv",
            null
        )
        assertNull(job)
    }

    @Test
    fun `prewarm skips when already cached`() = runBlocking {
        val calls = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            calls.incrementAndGet()
            ProxyResolution.Redirected(resolvedUrl)
        }
        CometProxyUrlResolver.resolve(cometUrl, null)
        assertEquals(1, calls.get())

        val job = CometProxyUrlResolver.prewarm(cometUrl, null)
        // Cached → no job kicked off
        assertNull(job)
        // Ensure no background fetch happened
        delay(30)
        assertEquals(1, calls.get())
    }

    @Test
    fun `reverse mapping survives successful resolve and invalidate removes it`() = runBlocking {
        CometProxyUrlResolver.setTransportForTesting { _, _ -> ProxyResolution.Redirected(resolvedUrl) }

        CometProxyUrlResolver.resolve(cometUrl, null)
        assertEquals(cometUrl, CometProxyUrlResolver.proxyUrlFor(resolvedUrl))

        assertTrue(CometProxyUrlResolver.invalidate(cometUrl))
        assertNull(CometProxyUrlResolver.proxyUrlFor(resolvedUrl))
    }

    @Test
    fun `reverse cache drops evicted forward cache entries`() = runBlocking {
        CometProxyUrlResolver.setTransportForTesting { url, _ ->
            val tag = url.substringAfter("cfg").substringBefore("/playback")
            ProxyResolution.Redirected("https://cdn.example.test/$tag/movie.mp4")
        }

        val resolvedByProxy = (0..64).associate { index ->
            val proxy = "https://comet.feels.legal/cfg$index/playback/hash/0/0/n/n?torrent_name=x&name=y"
            val resolved = (CometProxyUrlResolver.resolve(proxy, null) as ProxyResolution.Redirected).url
            proxy to resolved
        }

        val oldest = resolvedByProxy.entries.first()
        val newest = resolvedByProxy.entries.last()
        assertNull(CometProxyUrlResolver.proxyUrlFor(oldest.value))
        assertEquals(newest.key, CometProxyUrlResolver.proxyUrlFor(newest.value))
    }

    @Test
    fun `lastResolutionFor returns placeholder within short verdict ttl`() = runBlocking {
        var now = 1_000L
        CometProxyUrlResolver.setClockForTesting { now }
        CometProxyUrlResolver.setTransportForTesting { _, _ -> ProxyResolution.Placeholder }

        CometProxyUrlResolver.resolve(cometUrl, null)
        assertEquals(ProxyResolution.Placeholder, CometProxyUrlResolver.lastResolutionFor(cometUrl))

        now += 31_000L
        assertNull(CometProxyUrlResolver.lastResolutionFor(cometUrl))
    }
}
