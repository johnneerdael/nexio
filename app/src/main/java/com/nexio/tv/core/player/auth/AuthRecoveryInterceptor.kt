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
    private val maxAttemptsPerSession: Int = 3
) : Interceptor {

    private val attemptsRemaining = AtomicInteger(maxAttemptsPerSession)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val response = chain.proceed(original)
        if (!AuthFailureCodes.matches(response.code)) return response

        val originalUrl = original.url.toString()
        val proxyUrl = CometProxyUrlResolver.proxyUrlFor(originalUrl)
        if (proxyUrl == null) {
            AuthRecoveryTracker.record(originalUrl, response.code, AuthRecoveryTracker.Outcome.NO_PROXY_KNOWN)
            return response
        }

        if (attemptsRemaining.decrementAndGet() < 0) {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
            return response
        }

        val invalidated = CometProxyUrlResolver.invalidate(proxyUrl)
        if (!invalidated) {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.RATE_LIMITED)
            return response
        }

        val headers = CometProxyUrlResolver.lastHeadersFor(proxyUrl) ?: emptyMap()
        val addonHost = CometProxyUrlResolver.lastAddonHostFor(proxyUrl)
        val freshUrl = CometProxyUrlResolver.resolveBlocking(proxyUrl, headers, addonHost)
        if (freshUrl.isNullOrBlank()) {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
            return response
        }

        val rewritten = rewriteUrl(original, freshUrl) ?: run {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
            return response
        }

        response.close()
        Log.i(
            TAG,
            "RETRYING_AFTER_AUTH_FAIL status=${response.code} " +
                "fromHost=${original.url.host} toHost=${rewritten.url.host}"
        )
        val retried = chain.proceed(rewritten)
        if (retried.isSuccessful) {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.RECOVERED)
        } else {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
        }
        return retried
    }

    private fun rewriteUrl(original: Request, freshUrl: String): Request? {
        val parsed = freshUrl.toHttpUrlOrNull() ?: return null
        return original.newBuilder().url(parsed).build()
    }

    companion object {
        private const val TAG = "AuthRecovery"
    }
}
