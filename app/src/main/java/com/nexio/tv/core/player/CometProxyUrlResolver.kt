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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit

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

    private val clientHolder: Lazy<OkHttpClient> = lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            if (size <= MAX_CACHE_ENTRIES) return false
            eldest?.value?.resolvedUrl?.let { reverseCache.remove(it) }
            return true
        }
    }
    private val reverseCache: MutableMap<String, String> = HashMap()
    private val inFlight: MutableMap<String, CompletableDeferred<String?>> = HashMap()
    private val lastInvalidatedAtMs: MutableMap<String, Long> = HashMap()

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
    ): String? {
        if (!isCometProxy(url, addonHost)) return null

        val ownDeferred: CompletableDeferred<String?>
        val waitFor: CompletableDeferred<String?>?
        synchronized(lock) {
            val now = currentTimeMs()
            cache[url]?.let { entry ->
                if (now - entry.storedAtMs <= CACHE_TTL_MS) {
                    return entry.resolvedUrl
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

        val result = runCatching { fetchLocation(url, headers) }
            .onFailure { Log.w(TAG, "Resolve failed url=${sanitize(url)} error=${it.message}") }
            .getOrNull()

        synchronized(lock) {
            inFlight.remove(url)
            if (result != null) {
                cache[url] = CacheEntry(
                    resolvedUrl = result,
                    storedAtMs = currentTimeMs(),
                    headers = headers ?: emptyMap(),
                    addonHost = addonHost
                )
                reverseCache[result] = url
            }
        }
        ownDeferred.complete(result)
        return result
    }

    /**
     * Returns the addon proxy URL that originally resolved to [resolvedUrl], or
     * null if no entry exists or the entry has expired. Used by the auth-recovery
     * interceptor to find a proxy URL it can re-call to mint a fresh signed link.
     */
    fun proxyUrlFor(resolvedUrl: String): String? {
        synchronized(lock) {
            val proxy = reverseCache[resolvedUrl] ?: return null
            val entry = cache[proxy] ?: run {
                reverseCache.remove(resolvedUrl)
                return null
            }
            return if (currentTimeMs() - entry.storedAtMs <= CACHE_TTL_MS) proxy else null
        }
    }

    /**
     * Returns the headers associated with the most recent successful resolve
     * of [proxyUrl]. Used during recovery so the re-resolution call carries the
     * same auth context as the original.
     */
    fun lastHeadersFor(proxyUrl: String): Map<String, String>? {
        synchronized(lock) { return cache[proxyUrl]?.headers }
    }

    /**
     * Returns the [addonHost] passed to the most recent successful resolve.
     */
    fun lastAddonHostFor(proxyUrl: String): String? {
        synchronized(lock) { return cache[proxyUrl]?.addonHost }
    }

    /**
     * Drops both forward and reverse cache entries for [proxyUrl]. Call after
     * receiving a 401/403/410 from the resolved URL so the next resolve issues
     * a fresh upstream request rather than serving the stale mapping.
     *
     * Returns `true` if the invalidation was honoured, `false` if it was
     * suppressed by the [INVALIDATE_DEBOUNCE_MS] rate-limit (used to prevent
     * cascade re-resolution when many in-flight requests fail at once).
     */
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

    /**
     * Blocking wrapper around [resolve] for use on threads that cannot suspend
     * (the native FFmpeg probe path). Returns null on any failure including
     * timeout so callers fall back to the proxy URL.
     */
    fun resolveBlocking(
        url: String,
        headers: Map<String, String>?,
        addonHost: String? = null
    ): String? {
        if (!isCometProxy(url, addonHost)) return null
        return runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) { resolve(url, headers, addonHost) }
            }
        }.getOrNull()
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

    private suspend fun fetchLocation(url: String, headers: Map<String, String>?): String? {
        val transport = transportOverride ?: defaultTransport
        return withContext(Dispatchers.IO) {
            transport.execute(url, headers)
        }
    }

    private val defaultTransport: Transport = Transport { url, headers ->
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Range", "bytes=0-0")
        val forwardedHeaderKeys = mutableListOf<String>()
        headers?.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true)) {
                requestBuilder.header(key, value)
                forwardedHeaderKeys += key
            }
        }
        clientHolder.value.newCall(requestBuilder.build()).execute().use { response ->
            val location = response.header("Location")
            val locationHost = runCatching { location?.toHttpUrlOrNull()?.host }.getOrNull()
            val (decision, resolved) = when {
                response.code in 300..399 && !location.isNullOrBlank() -> "redirect" to location
                else -> "null-no-redirect" to null
            }
            Log.i(
                TAG,
                "RESOLVE_RESPONSE url=${sanitize(url)} status=${response.code} " +
                    "protocol=${response.protocol} locationPresent=${!location.isNullOrBlank()} " +
                    "locationHost=${locationHost ?: "none"} decision=$decision " +
                    "forwardedHeaderKeys=${forwardedHeaderKeys.joinToString(prefix = "[", postfix = "]")}"
            )
            resolved
        }
    }

    private fun currentTimeMs(): Long {
        return clockOverride?.invoke() ?: System.currentTimeMillis()
    }

    /**
     * Build a short, candidate-distinguishing label for a Comet proxy URL.
     * The auth blob in the path is identical across all candidates from the same
     * addon config, so a simple prefix-truncate makes sibling RESOLVE_RESPONSE
     * lines indistinguishable. Prefer host + last 2 path segments + torrent_name
     * if present.
     */
    private fun sanitize(url: String): String {
        val parsed = runCatching { url.toHttpUrlOrNull() }.getOrNull()
            ?: return url.substringBefore('?').take(160)
        val host = parsed.host
        val segments = parsed.encodedPathSegments
        val tail = segments.takeLast(2).joinToString(prefix = "/", separator = "/")
        val torrentName = parsed.queryParameter("torrent_name")?.take(80)
        val builder = StringBuilder()
        builder.append(host).append(tail)
        if (!torrentName.isNullOrBlank()) {
            builder.append("?torrent_name=").append(torrentName)
        }
        return builder.toString().take(220)
    }

    private data class CacheEntry(
        val resolvedUrl: String,
        val storedAtMs: Long,
        val headers: Map<String, String>,
        val addonHost: String?
    )

    internal fun interface Transport {
        fun execute(url: String, headers: Map<String, String>?): String?
    }
}
