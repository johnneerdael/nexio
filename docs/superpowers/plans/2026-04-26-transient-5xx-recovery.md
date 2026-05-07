# Transient 5xx playback recovery — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `AuthRecoveryInterceptor` to also recover playback range requests that fail with `502 / 503 / 504` from a debrid CDN edge, eliminating fatal `Source error: InvalidResponseCodeException: Response code: 502` mid-stream playback failures.

**Architecture:** Two-phase recovery on transient 5xx. Phase 1 issues a single same-URL retry after a small backoff (~250 ms) — Real-Debrid CDN 502s are usually edge-load-balancer-specific and a re-attempt routes to a different edge. Phase 2 falls back to the existing `CometProxyUrlResolver.recoverProxyBlocking` re-resolve + retry path that today handles `401 / 403 / 410`. Both phases preserve the `Range` header (critical for resuming mid-stream) and share the `maxAttemptsPerSession` budget. Transient retries that succeed register a stale→fresh forward only when the URL actually changed (i.e. only after Phase 2).

**Tech Stack:** Kotlin, OkHttp 4 `Interceptor`, JUnit 4, MockWebServer (`okhttp3.mockwebserver`).

**Spec source:** monitor capture from 2026-04-26 22:05 — Real-Debrid CDN edge `110-4.download.real-debrid.com` returned 502 for byte range `50331648-75497471` after ~48 MB of an 18 GB Bluray HDR remux had streamed cleanly. ExoPlayer surfaced the 502 as fatal `Source error`. `AuthRecoveryInterceptor` only matched `401 / 403 / 410` so the 5xx fell through untouched. Logcat tag: `PlayerMediaSource: java.io.IOException: Unexpected response 502 for range 50331648-75497471`.

**Conventions:**
- Branch: not used — committing directly to `main` (per current session policy).
- Run a single test: `./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.<name>'`
- Build smoke: `./gradlew :app:assembleUniversalDebug`
- Commit messages: Conventional commits (`feat(player): …`, `test(player): …`, `refactor(player): …`).

---

## File Structure

**New / modified source:**

- `app/src/main/java/com/nexio/tv/core/player/auth/TransientFailureCodes.kt` — new file. Mirrors the shape of `AuthFailureCodes.kt` for the `502 / 503 / 504` bucket. Kept separate from `AuthFailureCodes` because the recovery path differs (same-URL retry first, re-resolve second).
- `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt` — extend `intercept` to branch on failure-bucket (auth vs transient), add Phase 1 same-URL retry helper, and route Phase 2 through the existing recover-then-retry codepath. The class name stays `AuthRecoveryInterceptor` — keeping the name avoids touching every call site (`NetworkModule`, several tests).
- `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryTracker.kt` — add a new `Outcome.TRANSIENT_RETRIED` variant so we can tell at-a-glance whether recovery used Phase 1 (same-URL) or Phase 2 (re-resolve).

**Modified tests:**

- `app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt` — extend with seven new test cases covering the 5xx happy paths and edge cases.
- `app/src/test/java/com/nexio/tv/core/player/auth/AuthFailureCodesTest.kt` — new file. Tiny; just exercises the membership predicate so a future contributor can't widen `AuthFailureCodes.ALL` without also updating tests.
- `app/src/test/java/com/nexio/tv/core/player/auth/TransientFailureCodesTest.kt` — new file, sibling of the above.

**Manual verification target:** the `192.168.50.98:5555` Fire TV. Same playback flow that hit the 502 today — replay it on a freshly-installed APK and confirm logcat shows `RETRYING_AFTER_TRANSIENT_FAIL` followed by either continued playback or graceful give-up.

---

## Task 1: Define `TransientFailureCodes` and a tiny test for it

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/auth/TransientFailureCodes.kt`
- Create: `app/src/test/java/com/nexio/tv/core/player/auth/TransientFailureCodesTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/core/player/auth/TransientFailureCodesTest.kt`:

```kotlin
package com.nexio.tv.core.player.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientFailureCodesTest {

    @Test
    fun `matches recognises 502 503 504`() {
        assertTrue(TransientFailureCodes.matches(502))
        assertTrue(TransientFailureCodes.matches(503))
        assertTrue(TransientFailureCodes.matches(504))
    }

    @Test
    fun `matches rejects auth failure codes`() {
        assertFalse(TransientFailureCodes.matches(401))
        assertFalse(TransientFailureCodes.matches(403))
        assertFalse(TransientFailureCodes.matches(410))
    }

    @Test
    fun `matches rejects success and other 5xx`() {
        assertFalse(TransientFailureCodes.matches(200))
        assertFalse(TransientFailureCodes.matches(206))
        assertFalse(TransientFailureCodes.matches(500))
        assertFalse(TransientFailureCodes.matches(505))
        assertFalse(TransientFailureCodes.matches(599))
    }

    @Test
    fun `ALL contains exactly the three transient codes`() {
        assertEquals(setOf(502, 503, 504), TransientFailureCodes.ALL)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run:
```
./gradlew :app:compileUniversalDebugUnitTestKotlin
```
Expected: Kotlin compile error — unresolved reference `TransientFailureCodes`.

- [ ] **Step 3: Implement `TransientFailureCodes`**

Create `app/src/main/java/com/nexio/tv/core/player/auth/TransientFailureCodes.kt`:

```kotlin
package com.nexio.tv.core.player.auth

/**
 * HTTP status codes that signal a transient upstream-CDN failure on what is
 * otherwise still a valid signed link. Recovery for these is two-phase:
 *
 *  1. Retry once against the same URL after a short backoff. Real-Debrid edge
 *     load balancers tend to route the second attempt to a different healthy
 *     edge; the signed URL itself is usually still live.
 *  2. If the retry also fails, escalate to the auth-style re-resolve path via
 *     [com.nexio.tv.core.player.CometProxyUrlResolver.recoverProxyBlocking].
 *
 * - 502 Bad Gateway — Real-Debrid CDN edges (`*.download.real-debrid.com`)
 *   return this mid-stream when the upstream backend hiccups.
 * - 503 Service Unavailable — emitted by Premiumize / EnergyCDN under load.
 * - 504 Gateway Timeout — sporadic; same recovery as 502.
 *
 * Distinct from [AuthFailureCodes] because the auth bucket signals
 * "the signed link is dead, don't retry it" whereas this bucket signals
 * "the link is probably still good, just hit a bad edge — try again".
 */
object TransientFailureCodes {
    const val BAD_GATEWAY = 502
    const val SERVICE_UNAVAILABLE = 503
    const val GATEWAY_TIMEOUT = 504

    val ALL: Set<Int> = setOf(BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT)

    fun matches(status: Int): Boolean = status in ALL
}
```

- [ ] **Step 4: Run the test to verify it passes**

```
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.auth.TransientFailureCodesTest'
```
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/auth/TransientFailureCodes.kt \
       app/src/test/java/com/nexio/tv/core/player/auth/TransientFailureCodesTest.kt
git commit -m "feat(player): introduce TransientFailureCodes for 502/503/504 recovery bucket"
```

---

## Task 2: Add `Outcome.TRANSIENT_RETRIED` to `AuthRecoveryTracker`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryTracker.kt`

The existing `Outcome` enum has `RECOVERED, GAVE_UP, RATE_LIMITED, NO_PROXY_KNOWN`. We add `TRANSIENT_RETRIED` so logcat / diagnostics distinguish "Phase 1 same-URL retry succeeded" from "Phase 2 re-resolve succeeded" (the latter still records as `RECOVERED`, matching existing semantics).

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt` near the existing test class — under any `@Test`:

```kotlin
@Test
fun `Outcome enum exposes TRANSIENT_RETRIED for phase-1 same-url recovery`() {
    val all = AuthRecoveryTracker.Outcome.values().toSet()
    assertTrue(
        "Outcome enum must expose TRANSIENT_RETRIED for 5xx phase-1 recovery telemetry. " +
            "Got: $all",
        AuthRecoveryTracker.Outcome.TRANSIENT_RETRIED in all
    )
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.Outcome enum exposes TRANSIENT_RETRIED for phase-1 same-url recovery'
```
Expected: compile error — `Unresolved reference 'TRANSIENT_RETRIED'`.

- [ ] **Step 3: Add the enum variant**

Edit `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryTracker.kt`. Replace:

```kotlin
    enum class Outcome { RECOVERED, GAVE_UP, RATE_LIMITED, NO_PROXY_KNOWN }
```

with:

```kotlin
    enum class Outcome {
        /** Phase-2 recovery: re-resolved the proxy URL, retried, retry succeeded. */
        RECOVERED,
        /** Phase-1 recovery: same-URL retry succeeded after a transient 5xx. */
        TRANSIENT_RETRIED,
        /** Both retry attempts (or all attempts allowed by the budget) failed. */
        GAVE_UP,
        /** Resolver debounce returned the same URL we just failed on; retry would not help. */
        RATE_LIMITED,
        /** Failing URL is not a known addon-proxy mapping; no recovery is possible. */
        NO_PROXY_KNOWN
    }
```

- [ ] **Step 4: Run the test to verify it passes**

```
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.Outcome enum exposes TRANSIENT_RETRIED for phase-1 same-url recovery'
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryTracker.kt \
       app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt
git commit -m "feat(player): add TRANSIENT_RETRIED outcome for phase-1 5xx recovery"
```

---

## Task 3: Phase 1 — same-URL retry on 5xx

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt`

The interceptor currently routes through `AuthFailureCodes.matches(response.code)`. We extend `intercept()` to handle a 5xx as well, but only the Phase 1 path: re-issue the same request once after a 250 ms backoff, charging one against `attemptsRemaining`. If the retry succeeds, log `TRANSIENT_RETRIED`. If it still fails 5xx, fall through to Phase 2 (Task 4). If the retry fails with anything else (4xx auth, success-with-different-code, etc.), the result is returned as-is.

- [ ] **Step 1: Write the failing test for the happy path**

Append the following helper + tests to `AuthRecoveryInterceptorTest.kt`. (The fixture pattern with MockWebServer is already established in this test class — copy the imports if not already there.)

```kotlin
@Test
fun `recovers from 502 by retrying the same URL once`() {
    val resolved = server.url("/cdn").toString()
    CometProxyUrlResolver.setTransportForTesting { _, _ ->
        ProxyResolution.Redirected(resolved)
    }
    runBlocking {
        CometProxyUrlResolver.resolve(
            "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n",
            headers = emptyMap()
        )
    }

    val hits = AtomicInteger(0)
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val n = hits.getAndIncrement()
            return when (n) {
                0 -> MockResponse().setResponseCode(502)
                else -> MockResponse().setResponseCode(200).setBody("ok")
            }
        }
    }
    val client = OkHttpClient.Builder()
        .addInterceptor(AuthRecoveryInterceptor()).build()

    val response = client.newCall(Request.Builder().url(resolved).build()).execute()
    response.use {
        assertEquals(200, it.code)
        assertEquals("ok", it.body?.string())
    }
    assertEquals("server should see exactly two requests: original + same-URL retry", 2, hits.get())

    val outcomes = AuthRecoveryTracker.snapshot().map { it.outcome }
    assertTrue(
        "expected TRANSIENT_RETRIED in outcomes, got $outcomes",
        AuthRecoveryTracker.Outcome.TRANSIENT_RETRIED in outcomes
    )
}

@Test
fun `5xx phase-1 retry preserves the Range header so mid-stream resumes work`() {
    val resolved = server.url("/cdn").toString()
    CometProxyUrlResolver.setTransportForTesting { _, _ ->
        ProxyResolution.Redirected(resolved)
    }
    runBlocking {
        CometProxyUrlResolver.resolve(
            "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n",
            headers = emptyMap()
        )
    }

    val rangeHeaders = mutableListOf<String>()
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            rangeHeaders += request.getHeader("Range").orEmpty()
            return if (rangeHeaders.size == 1) {
                MockResponse().setResponseCode(503)
            } else {
                MockResponse().setResponseCode(200).setBody("ok")
            }
        }
    }
    val client = OkHttpClient.Builder()
        .addInterceptor(AuthRecoveryInterceptor()).build()

    client.newCall(
        Request.Builder().url(resolved).header("Range", "bytes=50331648-75497471").build()
    ).execute().close()

    assertEquals(
        "Range header must survive the phase-1 retry verbatim",
        listOf("bytes=50331648-75497471", "bytes=50331648-75497471"),
        rangeHeaders
    )
}

@Test
fun `5xx with no proxy mapping is returned unchanged without retry`() {
    server.enqueue(MockResponse().setResponseCode(502))
    val unknown = server.url("/orphan").toString()
    val client = OkHttpClient.Builder()
        .addInterceptor(AuthRecoveryInterceptor()).build()

    val response = client.newCall(Request.Builder().url(unknown).build()).execute()
    response.use { assertEquals(502, it.code) }

    val attempts = AuthRecoveryTracker.snapshot()
    assertEquals(1, attempts.size)
    assertEquals(AuthRecoveryTracker.Outcome.NO_PROXY_KNOWN, attempts.first().outcome)
}
```

Imports needed if not already in the file (most are): `java.util.concurrent.atomic.AtomicInteger`, `okhttp3.mockwebserver.Dispatcher`, `okhttp3.mockwebserver.MockResponse`, `okhttp3.mockwebserver.RecordedRequest`, `kotlinx.coroutines.runBlocking`, `okhttp3.OkHttpClient`, `okhttp3.Request`, `com.nexio.tv.core.player.CometProxyUrlResolver`, `com.nexio.tv.core.player.ProxyResolution`.

- [ ] **Step 2: Run the new tests to verify they fail**

```
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.recovers from 502 by retrying the same URL once' \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.5xx phase-1 retry preserves the Range header so mid-stream resumes work' \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.5xx with no proxy mapping is returned unchanged without retry'
```
Expected: failures — the interceptor doesn't recognise 502/503 yet, so the responses pass through and tests assert against unmet conditions.

- [ ] **Step 3: Implement Phase 1 in `AuthRecoveryInterceptor.intercept`**

Edit `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt`. **One contiguous replace** that covers the entire existing `intercept` method (from `override fun intercept(chain: Interceptor.Chain): Response {` through the closing `}` that follows `return retried`) and substitutes the new dispatcher plus the two new private helpers below it.

Replace this entire method:

```kotlin
    override fun intercept(chain: Interceptor.Chain): Response {
        // ... existing 80-line body ending with `return retried` ...
    }
```

with this complete replacement (new method + two new helpers, all together):

```kotlin
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val rewritten = applyStaleForward(original) ?: original
        val response = chain.proceed(rewritten)

        val isAuth = AuthFailureCodes.matches(response.code)
        val isTransient = TransientFailureCodes.matches(response.code)
        if (!isAuth && !isTransient) return response

        val originalUrl = rewritten.url.toString()
        val proxyUrl = CometProxyUrlResolver.proxyUrlFor(originalUrl)
        if (proxyUrl == null) {
            AuthRecoveryTracker.record(originalUrl, response.code, AuthRecoveryTracker.Outcome.NO_PROXY_KNOWN)
            return response
        }

        // Transient 5xx: phase-1 same-URL retry first. Real-Debrid edge load
        // balancers usually route the second attempt to a healthy edge while
        // the signed URL itself is still valid, so a re-resolve is overkill
        // for the first failure.
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
            // Phase-1 retry also failed. If it failed with another transient
            // 5xx we will escalate to phase-2 (re-resolve). If it failed with
            // an auth code we let phase-2 handle that as well — the resolver
            // path is identical. Anything else (e.g. the second response is
            // a different non-recoverable code) we return as-is.
            val phase1Code = phase1.code
            val phase1Auth = AuthFailureCodes.matches(phase1Code)
            val phase1Transient = TransientFailureCodes.matches(phase1Code)
            if (!phase1Auth && !phase1Transient) {
                AuthRecoveryTracker.record(proxyUrl, phase1Code, AuthRecoveryTracker.Outcome.GAVE_UP)
                return phase1
            }
            phase1.close()
            // Fall through into phase-2 below using the second-attempt status
            // for telemetry.
            return runReResolveRecovery(
                chain = chain,
                rewritten = rewritten,
                originalUrl = originalUrl,
                proxyUrl = proxyUrl,
                triggerStatus = phase1Code
            )
        }

        // Auth path (401/403/410): no phase-1 retry, jump straight to re-resolve.
        if (attemptsRemaining.decrementAndGet() < 0) {
            AuthRecoveryTracker.record(proxyUrl, response.code, AuthRecoveryTracker.Outcome.GAVE_UP)
            return response
        }
        response.close()
        return runReResolveRecovery(
            chain = chain,
            rewritten = rewritten,
            originalUrl = originalUrl,
            proxyUrl = proxyUrl,
            triggerStatus = response.code
        )
    }

    private fun sleepBeforeTransientRetry() {
        // Tiny backoff so the second request lands on a different edge load
        // balancer cycle. Picked empirically — 250 ms is below the typical
        // 4-second user-perceived "playback stall" threshold but long enough
        // for an RD CDN to flap to a healthy backend.
        runCatching { Thread.sleep(TRANSIENT_RETRY_BACKOFF_MS) }
    }
```

(The replacement above is the complete substitute. The new private helpers `sleepBeforeTransientRetry` and `runReResolveRecovery` go inside the same class, immediately after the new `intercept` method. `runReResolveRecovery` is the consolidated tail of the old auth path:)

```kotlin
    private fun runReResolveRecovery(
        chain: Interceptor.Chain,
        rewritten: Request,
        originalUrl: String,
        proxyUrl: String,
        triggerStatus: Int
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
            return chain.proceed(rewritten)
        }
        if (freshUrl == originalUrl) {
            // Recovery returned the same URL we just failed on (debounced and
            // cache had not been refreshed yet). No retry would help; treat
            // as rate-limited so telemetry is honest.
            AuthRecoveryTracker.record(proxyUrl, triggerStatus, AuthRecoveryTracker.Outcome.RATE_LIMITED)
            return chain.proceed(rewritten)
        }

        val retryRequest = rewriteUrl(rewritten, freshUrl) ?: run {
            AuthRecoveryTracker.record(proxyUrl, triggerStatus, AuthRecoveryTracker.Outcome.GAVE_UP)
            return chain.proceed(rewritten)
        }

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
```

Add the constant to the `companion object`:

```kotlin
    companion object {
        private const val TAG = "AuthRecovery"
        private const val TRANSIENT_RETRY_BACKOFF_MS = 250L
    }
```

- [ ] **Step 4: Run the Phase 1 tests**

```
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.recovers from 502 by retrying the same URL once' \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.5xx phase-1 retry preserves the Range header so mid-stream resumes work' \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.5xx with no proxy mapping is returned unchanged without retry'
```
Expected: PASS.

- [ ] **Step 5: Run the existing auth-flow regression tests**

```
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.*' \
  --tests 'com.nexio.tv.core.player.auth.PlaybackAuthRecoveryEndToEndTest.*'
```
Expected: every existing test still passes — auth-bucket behaviour is unchanged because the new `if (isTransient)` branch only activates on 5xx codes.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt \
       app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt
git commit -m "feat(player): same-URL retry phase for transient 5xx playback failures"
```

---

## Task 4: Phase 2 — escalate to re-resolve when same-URL retry also fails

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt` — Phase 2 paths are already implemented under Task 3 (the `runReResolveRecovery` helper). This task only adds the tests that prove that escalation works for transient 5xx, plus the give-up case.

- [ ] **Step 1: Write the failing tests**

Append to `AuthRecoveryInterceptorTest.kt`:

```kotlin
@Test
fun `escalates to re-resolve when same-URL 502 retry also returns 502`() {
    val staleResolved = server.url("/cdn/stale").toString()
    val freshResolved = server.url("/cdn/fresh").toString()
    val resolveCount = AtomicInteger(0)
    CometProxyUrlResolver.setTransportForTesting { _, _ ->
        ProxyResolution.Redirected(
            if (resolveCount.getAndIncrement() == 0) staleResolved else freshResolved
        )
    }
    runBlocking {
        CometProxyUrlResolver.resolve(
            "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n",
            headers = emptyMap()
        )
    }

    val staleHits = AtomicInteger(0)
    val freshHits = AtomicInteger(0)
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/cdn/stale" -> {
                staleHits.incrementAndGet()
                MockResponse().setResponseCode(502)
            }
            "/cdn/fresh" -> {
                freshHits.incrementAndGet()
                MockResponse().setResponseCode(200).setBody("ok")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }
    val client = OkHttpClient.Builder()
        .addInterceptor(AuthRecoveryInterceptor()).build()

    val response = client.newCall(Request.Builder().url(staleResolved).build()).execute()
    response.use {
        assertEquals(200, it.code)
        assertEquals("ok", it.body?.string())
    }
    assertEquals("stale URL hit twice (original + phase-1 retry)", 2, staleHits.get())
    assertEquals("fresh URL hit once (phase-2 retry)", 1, freshHits.get())

    val outcomes = AuthRecoveryTracker.snapshot().map { it.outcome }
    assertTrue(
        "expected RECOVERED in outcomes after phase-2 escalation, got $outcomes",
        AuthRecoveryTracker.Outcome.RECOVERED in outcomes
    )
}

@Test
fun `gives up on 502 when both phase-1 retry and phase-2 re-resolve fail`() {
    val resolved = server.url("/cdn").toString()
    CometProxyUrlResolver.setTransportForTesting { _, _ ->
        ProxyResolution.Redirected(resolved)
    }
    runBlocking {
        CometProxyUrlResolver.resolve(
            "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n",
            headers = emptyMap()
        )
    }
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) =
            MockResponse().setResponseCode(502)
    }
    val client = OkHttpClient.Builder()
        .addInterceptor(AuthRecoveryInterceptor()).build()

    val response = client.newCall(Request.Builder().url(resolved).build()).execute()
    response.use { assertEquals(502, it.code) }

    val outcomes = AuthRecoveryTracker.snapshot().map { it.outcome }
    assertTrue(
        "expected GAVE_UP after both phases failed, got $outcomes",
        AuthRecoveryTracker.Outcome.GAVE_UP in outcomes
    )
}

@Test
fun `503 follows the same two-phase recovery as 502`() {
    val resolved = server.url("/cdn").toString()
    CometProxyUrlResolver.setTransportForTesting { _, _ ->
        ProxyResolution.Redirected(resolved)
    }
    runBlocking {
        CometProxyUrlResolver.resolve(
            "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n",
            headers = emptyMap()
        )
    }
    val hits = AtomicInteger(0)
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            if (hits.getAndIncrement() == 0) MockResponse().setResponseCode(503)
            else MockResponse().setResponseCode(200).setBody("ok")
    }
    val client = OkHttpClient.Builder()
        .addInterceptor(AuthRecoveryInterceptor()).build()

    client.newCall(Request.Builder().url(resolved).build()).execute().use {
        assertEquals(200, it.code)
    }
    val outcomes = AuthRecoveryTracker.snapshot().map { it.outcome }
    assertTrue(
        "expected TRANSIENT_RETRIED for 503 phase-1 recovery, got $outcomes",
        AuthRecoveryTracker.Outcome.TRANSIENT_RETRIED in outcomes
    )
}

@Test
fun `504 follows the same two-phase recovery as 502`() {
    val resolved = server.url("/cdn").toString()
    CometProxyUrlResolver.setTransportForTesting { _, _ ->
        ProxyResolution.Redirected(resolved)
    }
    runBlocking {
        CometProxyUrlResolver.resolve(
            "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n",
            headers = emptyMap()
        )
    }
    val hits = AtomicInteger(0)
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            if (hits.getAndIncrement() == 0) MockResponse().setResponseCode(504)
            else MockResponse().setResponseCode(200).setBody("ok")
    }
    val client = OkHttpClient.Builder()
        .addInterceptor(AuthRecoveryInterceptor()).build()

    client.newCall(Request.Builder().url(resolved).build()).execute().use {
        assertEquals(200, it.code)
    }
    val outcomes = AuthRecoveryTracker.snapshot().map { it.outcome }
    assertTrue(
        "expected TRANSIENT_RETRIED for 504 phase-1 recovery, got $outcomes",
        AuthRecoveryTracker.Outcome.TRANSIENT_RETRIED in outcomes
    )
}
```

- [ ] **Step 2: Run the new tests**

```
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.escalates to re-resolve when same-URL 502 retry also returns 502' \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.gives up on 502 when both phase-1 retry and phase-2 re-resolve fail' \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.503 follows the same two-phase recovery as 502' \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.504 follows the same two-phase recovery as 502'
```
Expected: PASS — Task 3 already implemented Phase 2 (`runReResolveRecovery`) so the escalation path works without further code changes.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt
git commit -m "test(player): cover phase-2 5xx escalation, give-up, and 503/504 parity"
```

---

## Task 5: Budget-sharing edge case + small `AuthFailureCodes` regression

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/player/auth/AuthFailureCodesTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt`

Two final tests for completeness: (a) `AuthFailureCodes` has its own tiny membership test so a future widening of `AuthFailureCodes.ALL` is intentional; (b) one combined-budget test that proves `maxAttemptsPerSession` is shared between auth and transient buckets.

- [ ] **Step 1: Write the failing AuthFailureCodes test**

Create `app/src/test/java/com/nexio/tv/core/player/auth/AuthFailureCodesTest.kt`:

```kotlin
package com.nexio.tv.core.player.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFailureCodesTest {

    @Test
    fun `matches recognises 401 403 410`() {
        assertTrue(AuthFailureCodes.matches(401))
        assertTrue(AuthFailureCodes.matches(403))
        assertTrue(AuthFailureCodes.matches(410))
    }

    @Test
    fun `matches rejects transient 5xx codes which belong to TransientFailureCodes`() {
        assertFalse(AuthFailureCodes.matches(502))
        assertFalse(AuthFailureCodes.matches(503))
        assertFalse(AuthFailureCodes.matches(504))
    }

    @Test
    fun `ALL contains exactly the three auth codes`() {
        assertEquals(setOf(401, 403, 410), AuthFailureCodes.ALL)
    }
}
```

- [ ] **Step 2: Add the budget-sharing test**

Append to `AuthRecoveryInterceptorTest.kt`:

```kotlin
@Test
fun `auth and transient recoveries share the maxAttemptsPerSession budget`() {
    val resolved = server.url("/cdn").toString()
    CometProxyUrlResolver.setTransportForTesting { _, _ ->
        ProxyResolution.Redirected(resolved)
    }
    runBlocking {
        CometProxyUrlResolver.resolve(
            "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n",
            headers = emptyMap()
        )
    }
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) =
            MockResponse().setResponseCode(502)
    }
    val client = OkHttpClient.Builder()
        // Budget of 1 means: first failing request consumes the only attempt
        // (the phase-1 retry), the second failing request must be returned
        // unchanged because no attempts remain.
        .addInterceptor(AuthRecoveryInterceptor(maxAttemptsPerSession = 1)).build()

    // First call: 502 → phase-1 retry → still 502 → phase-2 re-resolve →
    // resolver returns same URL → still 502 → returns 502.
    client.newCall(Request.Builder().url(resolved).build()).execute().use {
        assertEquals(502, it.code)
    }

    // Second call: 502 → budget exhausted → returns 502 with GAVE_UP outcome
    // *without* issuing a phase-1 retry (server should see no extra attempts).
    val hitsBefore = server.requestCount
    client.newCall(Request.Builder().url(resolved).build()).execute().use {
        assertEquals(502, it.code)
    }
    val hitsAfter = server.requestCount

    assertEquals(
        "second failing request must not consume a phase-1 retry once budget is gone",
        1, hitsAfter - hitsBefore
    )
    val outcomes = AuthRecoveryTracker.snapshot().map { it.outcome }
    assertTrue(
        "expected GAVE_UP for budget-exhausted second request, got $outcomes",
        AuthRecoveryTracker.Outcome.GAVE_UP in outcomes
    )
}
```

- [ ] **Step 3: Run the new tests**

```
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.player.auth.AuthFailureCodesTest' \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.auth and transient recoveries share the maxAttemptsPerSession budget'
```
Expected: PASS.

- [ ] **Step 4: Run the full auth + player-streams test suite as a regression sweep**

```
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.player.auth.*' \
  --tests 'com.nexio.tv.ui.screens.player.PlayerRuntimeControllerStreamsAuthRecoveryTest.*'
```
Expected: every test passes.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/player/auth/AuthFailureCodesTest.kt \
       app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt
git commit -m "test(player): cover shared recovery budget and AuthFailureCodes regression"
```

---

## Task 6: Build smoke + push

**Files:** none (build / push only).

- [ ] **Step 1: Full Universal-flavor APK build**

```
./gradlew :app:assembleUniversalDebug
```
Expected: `BUILD SUCCESSFUL`. No new lint or resource regressions.

- [ ] **Step 2: Push to origin/main**

```
git push origin main
```

- [ ] **Step 3: Install on the target device**

```
./gradlew :app:installUniversalDebug
```
Expected: `INSTALL_SUCCESS` on `192.168.50.98:5555`.

---

## Task 7: Manual device verification

**Files:** none — verification gate.

- [ ] **Step 1: Trigger a stream that previously hit a 502**

Replay the same Bluray HDR remux flow that produced the 502 in the 2026-04-26 22:05 capture (Missione Shelter 2026 4K HDR). Start playback and let it run for ~60 seconds to give the CDN edge time to flap.

- [ ] **Step 2: Watch logcat for transient recovery**

```
adb -s 192.168.50.98:5555 logcat -v time \
  -s 'AuthRecovery:V' 'PlayerMediaSource:V' 'ExoPlayerImplInternal:V'
```

Expected new lines on a transient hit:
- `RETRYING_AFTER_TRANSIENT_FAIL status=502 host=... attempt=phase1_same_url`
- Either `AUTH_RECOVERY status=502 outcome=TRANSIENT_RETRIED ...` (Phase 1 succeeded — most common) or `RETRYING_AFTER_AUTH_FAIL status=502 fromHost=... toHost=...` followed by `AUTH_RECOVERY status=502 outcome=RECOVERED ...` (Phase 1 failed, Phase 2 succeeded).

Expected absent on a normal stream: no `Source error: InvalidResponseCodeException: Response code: 502` lines from `ExoPlayerImplInternal`. If those still appear, the recovery path didn't fire — capture the surrounding `AuthRecovery` lines and investigate.

- [ ] **Step 3: Confirm the existing auth recovery path still works**

If a 401/403/410 happens in the same session (these are far rarer than 502 today), confirm logcat still shows `RETRYING_AFTER_AUTH_FAIL status=401 ...` followed by `AUTH_RECOVERY status=401 outcome=RECOVERED ...` exactly as before. The auth path is unchanged in this work.

---

## Execution notes for the implementing engineer

- **Tasks 1, 2, 3 must be done in order** — Task 3 references `TransientFailureCodes` (Task 1) and `Outcome.TRANSIENT_RETRIED` (Task 2). Tasks 4 and 5 only add tests on top of Task 3's implementation and can be done in either order.
- **`Thread.sleep` inside the interceptor is intentional**: OkHttp interceptors run on the dispatcher I/O thread, so a 250 ms sleep does not block the UI. Do not refactor to `delay()` — that would require making `intercept` suspend and rewiring `OkHttpClient` setup, which is out of scope.
- **The `runReResolveRecovery` helper is the deliberate consolidation** of the auth path's tail (re-resolve, register forward, retry once). The auth and transient paths converge there to avoid duplicating `lastHeadersFor` / `lastAddonHostFor` / `recoverProxyBlocking` plumbing.
- **`registerForward` is only called from the Phase-2 success branch** — Phase-1 success returns the same URL, so registering a forward there would be a no-op (and the existing `registerForward` short-circuits when stale == fresh).
- **Existing tests are the regression net.** Do not modify any existing `AuthRecoveryInterceptorTest` test method during Tasks 1-5; only append new methods. If any existing test fails after Task 3, the change broke the auth flow — back out and re-examine the dispatcher's `if (isTransient)` branch placement.
