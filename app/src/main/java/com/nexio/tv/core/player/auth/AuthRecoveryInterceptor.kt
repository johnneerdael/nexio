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
 *     [CometProxyUrlResolver.proxyUrlFor]. (The proxyUrlFor lookup is
 *     tolerant of the in-flight recovery window — peers arriving while a
 *     leader is mid-resolve still find the proxy.)
 *  2. Call [CometProxyUrlResolver.recoverProxyBlocking], a coalescing
 *     leader/peer/debounced state machine: the first concurrent caller leads
 *     a single network resolve; later callers within the same window await
 *     the leader's `CompletableDeferred`; debounced stragglers receive
 *     whatever the leader most recently cached. This replaces an earlier
 *     invalidate-then-resolve sequence which raced concurrent failing
 *     requests into NO_PROXY_KNOWN / RATE_LIMITED branches under burst
 *     failures (the parallel-range data source can have up to 12 concurrent
 *     connections per host).
 *  3. Rewrite the in-flight request's URL (preserving headers and Range)
 *     and reissue once.
 *  4. On success, register `staleUrl → freshUrl` in [staleUrlForwards] so
 *     subsequent requests by the same caller (e.g. a long-running disk
 *     spool writer that loops on the same source URL) are silently
 *     redirected to the fresh URL without burning another recovery attempt.
 *     Chain entries pointing at the now-stale URL are promoted in the same
 *     critical section so chains stay rooted at a live URL.
 *
 * The interceptor is bounded by [maxAttemptsPerSession] (default 3) to
 * prevent thrash when a debrid host is broadly down, and by
 * [maxForwardEntries] (default 64, LRU) to bound the stale-URL map. Both
 * are reset by [resetSessionState], called from
 * [com.nexio.tv.ui.screens.player.PlayerRuntimeController] at stream start.
 * All outcomes go through [AuthRecoveryTracker].
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

        val isAuth = AuthFailureCodes.matches(response.code)
        val isTransient = TransientFailureCodes.matches(response.code)
        if (!isAuth && !isTransient) return response

        // Always emit when we see a recoverable failure, even before deciding
        // whether to recover. Surfaces the case where 5xx responses reach the
        // interceptor but get short-circuited by NO_PROXY_KNOWN / GAVE_UP /
        // RATE_LIMITED, which used to be invisible until the per-outcome line
        // fired.
        Log.i(
            TAG,
            "AUTH_RECOVERY_INTERCEPTED status=${response.code} " +
                "bucket=${if (isAuth) "auth" else "transient"} " +
                "host=${rewritten.url.host} attemptsRemainingBeforeDecrement=${attemptsRemaining.get()}"
        )

        val originalUrl = rewritten.url.toString()
        val proxyUrl = CometProxyUrlResolver.proxyUrlFor(originalUrl)
        if (proxyUrl == null) {
            AuthRecoveryTracker.record(originalUrl, response.code, AuthRecoveryTracker.Outcome.NO_PROXY_KNOWN)
            return response
        }

        // Transient 5xx: phase-1 same-URL retry first. Real-Debrid edge load
        // balancers usually route the second attempt to a healthy edge while
        // the signed URL itself is still valid, so a re-resolve is overkill
        // for the first failure. The auth bucket skips phase-1 because
        // auth-failure URLs are *known* dead — re-issuing won't help.
        if (isTransient) {
            if (attemptsRemaining.decrementAndGet() < 0) {
                AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
                return response
            }
            response.close()
            sleepBeforeTransientRetry()
            Log.i(
                TAG,
                "RETRYING_AFTER_TRANSIENT_FAIL status=${response.code} " +
                    "host=${rewritten.url.host} attempt=phase1_same_url"
            )
            val phase1 = chain.proceed(rewritten)
            if (phase1.isSuccessful) {
                AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.TRANSIENT_RETRIED)
                return phase1
            }
            // Phase-1 retry failed. If it's another recoverable code,
            // escalate to phase-2 (re-resolve). If it's anything else, give
            // up — the response itself is the user-facing answer.
            val phase1Code = phase1.code
            val phase1Recoverable = AuthFailureCodes.matches(phase1Code) ||
                TransientFailureCodes.matches(phase1Code)
            if (!phase1Recoverable) {
                AuthRecoveryTracker.record(proxyUrl, phase1Code, AuthRecoveryTracker.Outcome.GAVE_UP)
                return phase1
            }
            return runReResolveRecovery(
                chain = chain,
                rewritten = rewritten,
                originalUrl = originalUrl,
                proxyUrl = proxyUrl,
                triggerStatus = phase1Code,
                failureResponse = phase1
            )
        }

        // Auth path (401/403/410): no phase-1, jump straight to re-resolve.
        if (attemptsRemaining.decrementAndGet() < 0) {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
            return response
        }
        return runReResolveRecovery(
            chain = chain,
            rewritten = rewritten,
            originalUrl = originalUrl,
            proxyUrl = proxyUrl,
            triggerStatus = response.code,
            failureResponse = response
        )
    }

    private fun sleepBeforeTransientRetry() {
        // Tiny backoff so the second request lands on a different edge load
        // balancer cycle. Picked empirically — 250 ms is below the typical
        // 4-second user-perceived "playback stall" threshold but long enough
        // for an RD CDN to flap to a healthy backend.
        runCatching { Thread.sleep(TRANSIENT_RETRY_BACKOFF_MS) }
    }

    private fun runReResolveRecovery(
        chain: Interceptor.Chain,
        rewritten: Request,
        originalUrl: String,
        proxyUrl: String,
        triggerStatus: Int,
        failureResponse: Response
    ): Response {
        when (val ipState = PlaybackAuthFingerprintHolder.current()?.compareNow()) {
            is EgressIpFingerprint.State.Changed -> Log.w(
                TAG,
                "EGRESS_IP_SHIFTED baseline=${ipState.baseline} current=${ipState.current} " +
                    "proxyHost=${rewritten.url.host}"
            )
            else -> Unit
        }

        // Capture the headers + addonHost first because the recovery may drop
        // the cache entry that backs lastHeadersFor / lastAddonHostFor.
        val headers = CometProxyUrlResolver.lastHeadersFor(proxyUrl) ?: emptyMap()
        val addonHost = CometProxyUrlResolver.lastAddonHostFor(proxyUrl)

        // Single coalescing recovery call: leader resolves, peers await the
        // leader's result, debounced callers pick up the leader's freshly-
        // cached URL.
        val freshUrl = CometProxyUrlResolver.recoverProxyBlocking(proxyUrl, headers, addonHost)
        if (freshUrl.isNullOrBlank()) {
            AuthRecoveryTracker.record(proxyUrl, triggerStatus, AuthRecoveryTracker.Outcome.GAVE_UP)
            return failureResponse
        }
        if (freshUrl == originalUrl) {
            // Recovery returned the same URL we just failed on (debounced and
            // cache had not been refreshed yet). No retry would help; treat
            // as rate-limited so telemetry is honest.
            AuthRecoveryTracker.record(proxyUrl, triggerStatus, AuthRecoveryTracker.Outcome.RATE_LIMITED)
            return failureResponse
        }

        val retryRequest = rewriteUrl(rewritten, freshUrl) ?: run {
            AuthRecoveryTracker.record(proxyUrl, triggerStatus, AuthRecoveryTracker.Outcome.GAVE_UP)
            return failureResponse
        }

        // Only close the failure response now that we're committing to a
        // fresh request. Earlier branches return it as the user-facing
        // result instead.
        failureResponse.close()
        Log.i(
            TAG,
            "RETRYING_AFTER_AUTH_FAIL status=$triggerStatus " +
                "fromHost=${rewritten.url.host} toHost=${retryRequest.url.host}"
        )
        val retried = chain.proceed(retryRequest)
        if (retried.isSuccessful) {
            registerForward(originalUrl, freshUrl)
            AuthRecoveryTracker.record(proxyUrl, triggerStatus, AuthRecoveryTracker.Outcome.RECOVERED)
        } else {
            AuthRecoveryTracker.record(proxyUrl, triggerStatus, AuthRecoveryTracker.Outcome.GAVE_UP)
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
        private const val TRANSIENT_RETRY_BACKOFF_MS = 250L
    }
}
