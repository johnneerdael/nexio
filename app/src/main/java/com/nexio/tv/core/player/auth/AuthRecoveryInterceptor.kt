package com.nexio.tv.core.player.auth

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.nexio.tv.core.player.CometProxyUrlResolver
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

class AuthRecoveryInterceptor(
    private val maxAttemptsPerSession: Int = 3,
    private val maxForwardEntries: Int = 64
) : Interceptor {
    private val attemptsRemaining = AtomicInteger(maxAttemptsPerSession)
    private val forwardLock = Any()
    private val staleUrlForwards: LinkedHashMap<String, String> =
        object : LinkedHashMap<String, String>(maxForwardEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > maxForwardEntries
        }

    fun resetSessionState() {
        attemptsRemaining.set(maxAttemptsPerSession)
        synchronized(forwardLock) { staleUrlForwards.clear() }
    }

    @VisibleForTesting
    internal fun staleForwardsSnapshotForTesting(): Map<String, String> =
        synchronized(forwardLock) { LinkedHashMap(staleUrlForwards) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val rewritten = applyStaleForward(original) ?: original
        val response = chain.proceed(rewritten)
        val isAuth = AuthFailureCodes.matches(response.code)
        val isTransient = TransientFailureCodes.matches(response.code)
        if (!isAuth && !isTransient) return response

        Log.i(
            TAG,
            "AUTH_RECOVERY_INTERCEPTED status=${response.code} " +
                "bucket=${if (isAuth) "auth" else "transient"} " +
                "host=${rewritten.url.host} attemptsRemainingBeforeDecrement=${attemptsRemaining.get()}"
        )

        val originalUrl = rewritten.url.toString()
        val proxyUrl = CometProxyUrlResolver.proxyUrlFor(originalUrl)

        if (isTransient) {
            if (attemptsRemaining.decrementAndGet() < 0) {
                AuthRecoveryTracker.record(proxyUrl ?: originalUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
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
                AuthRecoveryTracker.record(
                    proxyUrl ?: originalUrl,
                    response.code,
                    AuthRecoveryTracker.Outcome.TRANSIENT_RETRIED
                )
                return phase1
            }
            val phase1Code = phase1.code
            val phase1Recoverable = AuthFailureCodes.matches(phase1Code) ||
                TransientFailureCodes.matches(phase1Code)
            if (!phase1Recoverable) {
                AuthRecoveryTracker.record(proxyUrl ?: originalUrl, phase1Code, AuthRecoveryTracker.Outcome.GAVE_UP)
                return phase1
            }
            if (proxyUrl == null) {
                AuthRecoveryTracker.record(originalUrl, phase1Code, AuthRecoveryTracker.Outcome.NO_PROXY_KNOWN)
                return phase1
            }
            return runReResolveRecovery(chain, rewritten, originalUrl, proxyUrl, phase1Code, phase1)
        }

        if (proxyUrl == null) {
            AuthRecoveryTracker.record(originalUrl, response.code, AuthRecoveryTracker.Outcome.NO_PROXY_KNOWN)
            return response
        }
        if (attemptsRemaining.decrementAndGet() < 0) {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
            return response
        }
        return runReResolveRecovery(chain, rewritten, originalUrl, proxyUrl, response.code, response)
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

        val headers = CometProxyUrlResolver.lastHeadersFor(proxyUrl) ?: emptyMap()
        val addonHost = CometProxyUrlResolver.lastAddonHostFor(proxyUrl)
        val freshUrl = CometProxyUrlResolver.recoverProxyBlocking(proxyUrl, headers, addonHost)
        if (freshUrl.isNullOrBlank()) {
            AuthRecoveryTracker.record(proxyUrl, triggerStatus, AuthRecoveryTracker.Outcome.GAVE_UP)
            return failureResponse
        }
        if (freshUrl == originalUrl) {
            AuthRecoveryTracker.record(proxyUrl, triggerStatus, AuthRecoveryTracker.Outcome.RATE_LIMITED)
            return failureResponse
        }

        val retryRequest = rewriteUrl(rewritten, freshUrl) ?: run {
            AuthRecoveryTracker.record(proxyUrl, triggerStatus, AuthRecoveryTracker.Outcome.GAVE_UP)
            return failureResponse
        }

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
            val toPromote = staleUrlForwards.entries
                .filter { (_, value) -> value == staleUrl }
                .map { it.key }
            toPromote.forEach { staleUrlForwards[it] = freshUrl }
            staleUrlForwards[staleUrl] = freshUrl
            staleUrlForwards.entries.removeAll { (key, _) -> key == freshUrl }
        }
    }

    private fun rewriteUrl(original: Request, freshUrl: String): Request? {
        val parsed = freshUrl.toHttpUrlOrNull() ?: return null
        return original.newBuilder().url(parsed).build()
    }

    private fun sleepBeforeTransientRetry() {
        runCatching { Thread.sleep(TRANSIENT_RETRY_BACKOFF_MS) }
    }

    private companion object {
        const val TAG = "AuthRecovery"
        const val TRANSIENT_RETRY_BACKOFF_MS = 250L
    }
}
