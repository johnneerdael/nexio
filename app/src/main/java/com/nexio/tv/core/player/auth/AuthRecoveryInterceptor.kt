package com.nexio.tv.core.player.auth

import android.util.Log
import com.nexio.tv.core.player.CometProxyUrlResolver
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.atomic.AtomicInteger

/**
 * OkHttp interceptor that transparently recovers playback range requests when
 * the upstream debrid CDN returns 401/403/410. Recovery flow:
 *
 *  1. Detect the failing request URL is a previously-resolved proxy URL via
 *     [CometProxyUrlResolver.proxyUrlFor].
 *  2. Call [CometProxyUrlResolver.invalidate] (debounced inside the resolver)
 *     and re-issue [CometProxyUrlResolver.resolveBlocking] to mint a fresh
 *     CDN URL.
 *  3. Rewrite the in-flight request's URL (preserving headers and Range) and
 *     reissue once.
 *
 * The interceptor is bounded by [maxAttemptsPerSession] (default 3 across the
 * whole interceptor instance) to prevent thrash when a debrid host is broadly
 * down. All outcomes go through [AuthRecoveryTracker].
 */
class AuthRecoveryInterceptor(
    private val maxAttemptsPerSession: Int = 3,
    private val maxForwardEntries: Int = 64
) : Interceptor {

    private val attemptsRemaining = AtomicInteger(maxAttemptsPerSession)

    /**
     * Reset per-session state at the start of a new playback session: the
     * recovery attempt budget is replenished and any [staleUrlForwards] from a
     * prior session are cleared. The interceptor itself is a process-lifetime
     * singleton (it lives on the playback [okhttp3.OkHttpClient]), but its
     * recovery budget and URL-rewrite map are conceptually per-session — without
     * this call the budget silently exhausts after a few stream switches.
     *
     * Wired into [com.nexio.tv.ui.screens.player.PlayerRuntimeController]'s
     * stream-start path next to [EgressIpFingerprint.captureBaseline].
     */
    fun resetSessionState() {
        attemptsRemaining.set(maxAttemptsPerSession)
        synchronized(forwardLock) { staleUrlForwards.clear() }
    }

    @androidx.annotation.VisibleForTesting
    internal fun staleForwardsSnapshotForTesting(): Map<String, String> =
        synchronized(forwardLock) { LinkedHashMap(staleUrlForwards) }

    /**
     * Stale → fresh URL forwards established by past recoveries. After we
     * recover `oldUrl → newUrl`, any later request to `oldUrl` is
     * transparently rewritten to `newUrl` before being dispatched. Without
     * this, a long-running writer that loops on the same source URL would
     * burn one recovery attempt per chunk after a CDN flip.
     */
    private val forwardLock = Any()
    private val staleUrlForwards: LinkedHashMap<String, String> =
        object : LinkedHashMap<String, String>(maxForwardEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > maxForwardEntries
        }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val rewritten = applyStaleForward(original) ?: original
        val response = chain.proceed(rewritten)
        if (!AuthFailureCodes.matches(response.code)) return response

        val originalUrl = rewritten.url.toString()
        val proxyUrl = CometProxyUrlResolver.proxyUrlFor(originalUrl)
        if (proxyUrl == null) {
            AuthRecoveryTracker.record(originalUrl, response.code, AuthRecoveryTracker.Outcome.NO_PROXY_KNOWN)
            return response
        }

        if (attemptsRemaining.decrementAndGet() < 0) {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
            return response
        }

        when (val ipState = PlaybackAuthFingerprintHolder.current()?.compareNow()) {
            is EgressIpFingerprint.State.Changed -> Log.w(
                TAG,
                "EGRESS_IP_SHIFTED baseline=${ipState.baseline} current=${ipState.current} " +
                    "proxyHost=${original.url.host}"
            )
            else -> Unit
        }

        // Capture the headers + addonHost first because the recovery may drop
        // the cache entry that backs lastHeadersFor / lastAddonHostFor.
        val headers = CometProxyUrlResolver.lastHeadersFor(proxyUrl) ?: emptyMap()
        val addonHost = CometProxyUrlResolver.lastAddonHostFor(proxyUrl)

        // Single coalescing recovery call: leader resolves, peers await the
        // leader's result, debounced callers pick up the leader's freshly-
        // cached URL. Replaces the older invalidate-then-resolve sequence
        // which raced into NO_PROXY_KNOWN under concurrent failures.
        val freshUrl = CometProxyUrlResolver.recoverProxyBlocking(proxyUrl, headers, addonHost)
        if (freshUrl.isNullOrBlank()) {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
            return response
        }
        if (freshUrl == originalUrl) {
            // Recovery returned the same URL we just failed on (debounced and
            // cache had not been refreshed yet). No retry would help; treat
            // as rate-limited so telemetry is honest.
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.RATE_LIMITED)
            return response
        }

        val retryRequest = rewriteUrl(rewritten, freshUrl) ?: run {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
            return response
        }

        response.close()
        Log.i(
            TAG,
            "RETRYING_AFTER_AUTH_FAIL status=${response.code} " +
                "fromHost=${rewritten.url.host} toHost=${retryRequest.url.host}"
        )
        val retried = chain.proceed(retryRequest)
        if (retried.isSuccessful) {
            registerForward(originalUrl, freshUrl)
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.RECOVERED)
        } else {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
        }
        return retried
    }

    private fun applyStaleForward(request: Request): Request? {
        val current = request.url.toString()
        val forwarded = synchronized(forwardLock) { staleUrlForwards[current] } ?: return null
        if (forwarded == current) return null
        val parsed = forwarded.toHttpUrlOrNull() ?: return null
        return request.newBuilder().url(parsed).build()
    }

    private fun registerForward(staleUrl: String, freshUrl: String) {
        if (staleUrl == freshUrl) return
        synchronized(forwardLock) {
            // Promote any existing chain that landed on the now-stale URL:
            // entries A → staleUrl must become A → freshUrl, otherwise a
            // request for A would be rewritten to a URL that has just been
            // invalidated and has no live reverse-cache entry, falling
            // through to NO_PROXY_KNOWN → raw 401.
            val toPromote = staleUrlForwards.entries
                .filter { (_, v) -> v == staleUrl }
                .map { it.key }
            toPromote.forEach { staleUrlForwards[it] = freshUrl }
            staleUrlForwards[staleUrl] = freshUrl
            // If the fresh URL was itself a previously-recovered target,
            // drop the entry where it was the stale key so we don't carry
            // a self-referencing rewrite.
            staleUrlForwards.entries.removeAll { (k, _) -> k == freshUrl }
        }
    }

    private fun rewriteUrl(original: Request, freshUrl: String): Request? {
        val parsed = freshUrl.toHttpUrlOrNull() ?: return null
        return original.newBuilder().url(parsed).build()
    }

    companion object {
        private const val TAG = "AuthRecovery"
    }
}
