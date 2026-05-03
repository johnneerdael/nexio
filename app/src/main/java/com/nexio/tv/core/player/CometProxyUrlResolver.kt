package com.nexio.tv.core.player

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.nexio.tv.data.integration.playback.transport.CometProxyHttpResult
import com.nexio.tv.data.integration.playback.transport.OkHttpCometProxyHttpTransport
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.LinkedHashMap
import java.util.Locale

sealed class ProxyResolution {
    data class Redirected(val url: String) : ProxyResolution()
    object Placeholder : ProxyResolution()
    object NotEligible : ProxyResolution()
    object ResolveFailed : ProxyResolution()
}

internal fun classifyHttpResponse(
    code: Int,
    location: String?,
    contentType: String?,
): ProxyResolution {
    if (code in 300..399 && !location.isNullOrBlank()) {
        return ProxyResolution.Redirected(location)
    }
    if (code == 200 &&
        !contentType.isNullOrBlank() &&
        contentType.trim().lowercase(Locale.ROOT).startsWith("video/")
    ) {
        return ProxyResolution.Placeholder
    }
    return ProxyResolution.ResolveFailed
}

/**
 * Resolves Comet addon `/playback/` proxy URLs to the underlying debrid CDN URL
 * by following the 302 `Location` header once and caching the result.
 *
 * The resolver is gated to a known allowlist of Comet hostnames. Any URL on a
 * non-Comet host is rejected by [isCometProxy] and the caller must fall back to
 * treating the URL as opaque.
 *
 * See `docs/engineering/comet-proxy-url-resolver-findings.md` for the
 * background on why this class exists.
 */
object CometProxyUrlResolver {
    private const val TAG = "CometProxyResolver"
    private const val MAX_CACHE_ENTRIES = 64
    private const val CACHE_TTL_MS = 50L * 60L * 1000L // 50 minutes, safely under Comet's 60-minute TTL
    private const val REQUEST_TIMEOUT_MS = 8_000L
    private const val INVALIDATE_DEBOUNCE_MS = 30_000L
    private const val SHORT_VERDICT_TTL_MS = 30_000L

    private val knownProxyHosts: Set<String> = setOf(
        // Comet instances — 302 redirect, 1h server cache
        "comet.feels.legal",
        "cometfortheweebs.midnightignite.me",
        "comet.elfhosted.com",
        "comet.stremio.ru",
        // Meteor instance — same 302 pattern, 2.1–3.4s latency
        "meteorfortheweebs.midnightignite.me",
        // StremThru instances — 302 redirect, 3h server cache, accepts HEAD
        "stremthru.atbphosting.com",
        "stremthrufortheweebs.midnightignite.me",
        "stremthru.elfhosted.com",
        "stremthru.fortheweak.cloud",
        "stremthru.13377001.xyz",
        "stremthru.stremio.ru",
    )

    private val proxyPathMarkers: List<String> = listOf(
        "/playback/", // Comet
        "/play/",     // Meteor
        "/_/strem/",  // StremThru
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            if (size <= MAX_CACHE_ENTRIES) return false
            eldest?.value?.resolvedUrl?.let { reverseCache.remove(it) }
            return size > MAX_CACHE_ENTRIES
        }
    }
    private val reverseCache: MutableMap<String, String> = HashMap()
    private val inFlight: MutableMap<String, CompletableDeferred<ProxyResolution>> = HashMap()
    private val lastInvalidatedAtMs: MutableMap<String, Long> = HashMap()
    private val shortVerdictCache: MutableMap<String, VerdictEntry> = HashMap()

    @Volatile
    private var transportOverride: Transport? = null

    @Volatile
    private var clockOverride: (() -> Long)? = null

    /** Testing hook — replace the HTTP transport with a fake. */
    internal fun setTransportForTesting(transport: Transport?) {
        transportOverride = transport
    }

    /** Testing hook — override the clock used for TTL decisions. */
    internal fun setClockForTesting(clock: (() -> Long)?) {
        clockOverride = clock
    }

    internal fun resetForTesting() {
        synchronized(lock) {
            cache.clear()
            reverseCache.clear()
            inFlight.clear()
            lastInvalidatedAtMs.clear()
            shortVerdictCache.clear()
        }
        transportOverride = null
        clockOverride = null
    }

    /**
     * Returns true when [url] is eligible for 302 resolution:
     * - **Gate A (host match):** [addonHost] is provided and [url]'s host equals
     *   it (case-insensitive). This covers self-hosted instances not in the
     *   static list — we trust the addon to redirect us off its own host.
     * - **Gate B (known host + path marker):** [url]'s host is in the static
     *   [knownProxyHosts] list **and** the path contains a known proxy marker
     *   (`/playback/`, `/play/`, or `/_/strem/`). Path markers prevent false
     *   positives when an addon serves non-proxy resources on the same host.
     */
    fun isCometProxy(url: String, addonHost: String? = null): Boolean {
        val parsed = runCatching { url.toHttpUrlOrNull() }.getOrNull() ?: return false
        val host = parsed.host.lowercase(Locale.ROOT)

        if (!addonHost.isNullOrBlank() && host == addonHost.lowercase(Locale.ROOT)) {
            return true
        }

        if (host !in knownProxyHosts) return false
        val path = parsed.encodedPath.lowercase(Locale.ROOT)
        return proxyPathMarkers.any { marker -> marker in path }
    }

    /**
     * Resolve [url] to the underlying CDN URL by issuing a single HTTP GET with
     * [Range: bytes=0-0] and redirect-following disabled, then reading the
     * `Location` header. Returns null if the URL isn't a known addon proxy, the
     * request fails, or the response isn't a 3xx redirect.
     *
     * [addonHost] is the host portion of the addon's manifest URL that surfaced
     * this stream. When provided and it matches [url]'s host, resolution is
     * permitted even for hosts not in the static allowlist.
     */
    suspend fun resolve(
        url: String,
        headers: Map<String, String>?,
        addonHost: String? = null
    ): ProxyResolution {
        if (!isCometProxy(url, addonHost)) {
            recordShortVerdict(url, ProxyResolution.NotEligible)
            return ProxyResolution.NotEligible
        }

        val ownDeferred: CompletableDeferred<ProxyResolution>
        val waitFor: CompletableDeferred<ProxyResolution>?
        synchronized(lock) {
            val now = currentTimeMs()
            cache[url]?.let { entry ->
                if (now - entry.storedAtMs <= CACHE_TTL_MS) {
                    return ProxyResolution.Redirected(entry.resolvedUrl)
                }
                cache.remove(url)
            }
            val existing = inFlight[url]
            if (existing != null) {
                waitFor = existing
                ownDeferred = CompletableDeferred()
            } else {
                ownDeferred = CompletableDeferred()
                inFlight[url] = ownDeferred
                waitFor = null
            }
        }

        if (waitFor != null) {
            return waitFor.await()
        }

        val outcome = runCatching { fetchClassification(url, headers) }
            .onFailure { Log.w(TAG, "Resolve failed url=${sanitize(url)} error=${it.message}") }
            .getOrElse { ProxyResolution.ResolveFailed }

        synchronized(lock) {
            inFlight.remove(url)
            if (outcome is ProxyResolution.Redirected) {
                cache[url] = CacheEntry(
                    resolvedUrl = outcome.url,
                    storedAtMs = currentTimeMs(),
                    headers = headers ?: emptyMap(),
                    addonHost = addonHost
                )
                reverseCache[outcome.url] = url
            }
            shortVerdictCache[url] = VerdictEntry(outcome, currentTimeMs())
        }
        ownDeferred.complete(outcome)
        return outcome
    }

    fun lastResolutionFor(url: String): ProxyResolution? {
        synchronized(lock) {
            val now = currentTimeMs()
            if (!isCometProxy(url)) return ProxyResolution.NotEligible
            cache[url]?.let { entry ->
                if (now - entry.storedAtMs <= CACHE_TTL_MS) {
                    return ProxyResolution.Redirected(entry.resolvedUrl)
                }
            }
            val verdict = shortVerdictCache[url] ?: return null
            if (now - verdict.storedAtMs > SHORT_VERDICT_TTL_MS) {
                shortVerdictCache.remove(url)
                return null
            }
            return verdict.resolution
        }
    }

    fun proxyUrlFor(resolvedUrl: String): String? {
        synchronized(lock) {
            val proxy = reverseCache[resolvedUrl] ?: return null
            val entry = cache[proxy]
            if (entry != null) {
                return if (currentTimeMs() - entry.storedAtMs <= CACHE_TTL_MS) proxy else null
            }
            if (inFlight.containsKey(proxy)) return proxy
            reverseCache.remove(resolvedUrl)
            return null
        }
    }

    fun lastHeadersFor(proxyUrl: String): Map<String, String>? {
        synchronized(lock) { return cache[proxyUrl]?.headers }
    }

    fun lastAddonHostFor(proxyUrl: String): String? {
        synchronized(lock) { return cache[proxyUrl]?.addonHost }
    }

    fun invalidate(proxyUrl: String): Boolean {
        synchronized(lock) {
            val now = currentTimeMs()
            val last = lastInvalidatedAtMs[proxyUrl]
            if (last != null && now - last < INVALIDATE_DEBOUNCE_MS) return false
            val entry = cache.remove(proxyUrl)
            if (entry != null) {
                reverseCache.remove(entry.resolvedUrl)
            }
            lastInvalidatedAtMs[proxyUrl] = now
            return true
        }
    }

    fun recoverProxyBlocking(
        proxyUrl: String,
        headers: Map<String, String>?,
        addonHost: String?
    ): String? {
        if (!isCometProxy(proxyUrl, addonHost)) return null

        val deferred: CompletableDeferred<ProxyResolution>
        val isLeader: Boolean
        var previousResolvedUrl: String? = null
        synchronized(lock) {
            val existing = inFlight[proxyUrl]
            if (existing != null) {
                deferred = existing
                isLeader = false
            } else {
                val now = currentTimeMs()
                val last = lastInvalidatedAtMs[proxyUrl]
                if (last != null && now - last < INVALIDATE_DEBOUNCE_MS) {
                    return cache[proxyUrl]?.resolvedUrl
                }
                deferred = CompletableDeferred()
                inFlight[proxyUrl] = deferred
                lastInvalidatedAtMs[proxyUrl] = now
                previousResolvedUrl = cache.remove(proxyUrl)?.resolvedUrl
                isLeader = true
            }
        }

        if (!isLeader) {
            val peerOutcome = runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
                }
            }.getOrNull()
            return (peerOutcome as? ProxyResolution.Redirected)?.url
        }

        val outcome = runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) { fetchClassification(proxyUrl, headers) }
            }
        }.getOrNull() ?: ProxyResolution.ResolveFailed
        val result = (outcome as? ProxyResolution.Redirected)?.url

        synchronized(lock) {
            inFlight.remove(proxyUrl)
            previousResolvedUrl?.let { prior ->
                if (prior != result) reverseCache.remove(prior)
            }
            if (result != null) {
                cache[proxyUrl] = CacheEntry(
                    resolvedUrl = result,
                    storedAtMs = currentTimeMs(),
                    headers = headers ?: emptyMap(),
                    addonHost = addonHost
                )
                reverseCache[result] = proxyUrl
            }
            shortVerdictCache[proxyUrl] = VerdictEntry(outcome, currentTimeMs())
        }
        deferred.complete(outcome)
        return result
    }

    /**
     * Blocking wrapper around [resolve] for use on threads that cannot suspend
     * (the native FFmpeg probe path). Returns null on any failure including
     * timeout so callers fall back to the proxy URL.
     */
    fun resolveBlocking(
        url: String,
        headers: Map<String, String>?,
        addonHost: String? = null
    ): ProxyResolution {
        if (!isCometProxy(url, addonHost)) return ProxyResolution.NotEligible
        return runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) { resolve(url, headers, addonHost) }
            }
        }.getOrNull() ?: ProxyResolution.ResolveFailed
    }

    /**
     * Kick off resolution of [url] in the background if it isn't already cached
     * or in-flight. Callers are expected to run this the moment an addon proxy
     * URL appears in an autoplay candidate set.
     */
    fun prewarm(
        url: String,
        headers: Map<String, String>?,
        addonHost: String? = null
    ): Job? {
        if (!isCometProxy(url, addonHost)) return null
        val now = currentTimeMs()
        synchronized(lock) {
            cache[url]?.let { entry ->
                if (now - entry.storedAtMs <= CACHE_TTL_MS) return null
            }
            if (inFlight.containsKey(url)) return null
        }
        return scope.launch { resolve(url, headers, addonHost) }
    }

    /**
     * Helper to extract the host portion of an addon's base URL (manifest URL).
     * Returns null if [addonBaseUrl] is null/blank or unparseable.
     */
    fun hostOfAddonBaseUrl(addonBaseUrl: String?): String? {
        if (addonBaseUrl.isNullOrBlank()) return null
        return runCatching { addonBaseUrl.toHttpUrlOrNull()?.host }.getOrNull()
    }

    private suspend fun fetchClassification(url: String, headers: Map<String, String>?): ProxyResolution {
        val transport = transportOverride ?: defaultTransport
        return withContext(Dispatchers.IO) {
            transport.execute(url, headers)
        }
    }

    private val cometHttpTransport: OkHttpCometProxyHttpTransport = OkHttpCometProxyHttpTransport()
    private val defaultTransport: Transport = Transport { url, headers ->
        classifyTransportResult(url, cometHttpTransport.execute(url, headers))
    }

    private fun currentTimeMs(): Long {
        return clockOverride?.invoke() ?: System.currentTimeMillis()
    }

    private fun recordShortVerdict(url: String, resolution: ProxyResolution) {
        synchronized(lock) {
            shortVerdictCache[url] = VerdictEntry(resolution, currentTimeMs())
        }
    }

    private fun classifyTransportResult(url: String, result: CometProxyHttpResult): ProxyResolution {
        val outcome = classifyHttpResponse(
            code = result.code,
            location = result.location,
            contentType = result.contentType
        )
        val locationHost = runCatching { result.location?.toHttpUrlOrNull()?.host }.getOrNull()
        val decision = when (outcome) {
            is ProxyResolution.Redirected -> "redirect"
            ProxyResolution.Placeholder -> "placeholder"
            ProxyResolution.NotEligible -> "not-eligible"
            ProxyResolution.ResolveFailed -> "failed"
        }
        Log.i(
            TAG,
            "RESOLVE_RESPONSE url=${sanitize(url)} status=${result.code} " +
                "locationPresent=${!result.location.isNullOrBlank()} " +
                "locationHost=${locationHost ?: "none"} contentType=${result.contentType ?: "none"} " +
                "decision=$decision"
        )
        return outcome
    }

    private fun sanitize(url: String): String {
        val parsed = runCatching { url.toHttpUrlOrNull() }.getOrNull()
            ?: return url.substringBefore('?').take(160)
        val tail = parsed.encodedPathSegments.takeLast(2).joinToString(prefix = "/", separator = "/")
        val torrentName = parsed.queryParameter("torrent_name")?.take(80)
        return buildString {
            append(parsed.host)
            append(tail)
            if (!torrentName.isNullOrBlank()) {
                append("?torrent_name=")
                append(torrentName)
            }
        }.take(220)
    }

    private data class CacheEntry(
        val resolvedUrl: String,
        val storedAtMs: Long,
        val headers: Map<String, String>,
        val addonHost: String?
    )

    private data class VerdictEntry(
        val resolution: ProxyResolution,
        val storedAtMs: Long
    )

    internal fun interface Transport {
        fun execute(url: String, headers: Map<String, String>?): ProxyResolution
    }
}
