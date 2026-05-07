# Playback Auth Recovery and Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make NEXIO playback survive mid-stream `401` / `403` / `410` responses from debrid CDNs by transparently re-resolving the upstream proxy URL and retrying, regardless of which caching path (disk spool, VOD cache, parallel range, warm-ahead, plain progressive) issued the failing request.

**Architecture:** All playback HTTP traffic flows through a single `playbackOkHttpClient` (provided in `NetworkModule.providePlaybackOkHttpClient`). We add one `AuthRecoveryInterceptor` to that client. On a `401`/`403`/`410`, the interceptor consults `CometProxyUrlResolver`'s new reverse-cache to find the **original Comet/Meteor/StremThru proxy URL** that produced the failing CDN URL, evicts the stale mapping, re-issues the addon's `/playback/` (or equivalent) `GET` to mint a fresh `Location:`, rewrites the in-flight request to the new URL, and reissues it once. The same OkHttp client is shared by `OkHttpDataSource` (ExoPlayer's progressive/HLS/DASH paths), `DiskSpoolWriter`, `ParallelRangeDataSource`, the VOD cache upstream, and the warm-ahead loop, so one interceptor recovers all of them with no per-cache plumbing. Recovery is bounded by debounce + per-session caps; when recovery is exhausted the failure is classified into a user-actionable error message and surfaced via `Player.Listener.onPlayerError`. We also add User-Agent parity (so the resolver, the writer, and ExoPlayer all present an identical request fingerprint to RD/Premiumize) and an egress-IP fingerprint so we can log "IP shifted" vs "link revoked" distinctly.

**Tech Stack:** Kotlin, OkHttp 4 interceptors, Media3 / ExoPlayer (forked), Robolectric + JUnit4 + `MockWebServer` for unit tests, `adb logcat` for on-device verification against a Fire TV at `192.168.50.98`.

**Out of scope:** the in-process FFmpeg metadata probe (`FfmpegStreamMetadataProbe`) — it uses native mbedtls HTTP, not OkHttp, and its current PROBE_FAILED fallback already keeps autoplay flowing. A separate plan will be needed if probe-side recovery is later required.

---

## File Structure

**New files:**
- `app/src/main/java/com/nexio/tv/core/player/auth/AuthFailureCodes.kt` — single source of truth for the status codes treated as "auth/link expired" (`401`, `403`, `410`).
- `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt` — the OkHttp interceptor.
- `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryTracker.kt` — structured logging + in-memory ring buffer for diagnostics.
- `app/src/main/java/com/nexio/tv/core/player/auth/EgressIpFingerprint.kt` — captures egress IP at session start, compares on failure.
- `app/src/main/java/com/nexio/tv/core/player/auth/PlaybackErrorClassifier.kt` — turns `PlaybackException` into a user-facing reason string.
- `app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt`
- `app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryTrackerTest.kt`
- `app/src/test/java/com/nexio/tv/core/player/auth/EgressIpFingerprintTest.kt`
- `app/src/test/java/com/nexio/tv/core/player/auth/PlaybackErrorClassifierTest.kt`

**Modified files:**
- `app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt` — add reverse-cache, `invalidate(proxyUrl)`, `proxyUrlFor(resolvedUrl)`, headers retention.
- `app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt` — extend with reverse-cache + invalidate tests.
- `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt` — install `AuthRecoveryInterceptor` and a User-Agent parity interceptor on `playbackOkHttpClient`.
- `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt` — stop retrying on auth-failure status codes (the interceptor already retried).
- `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt` — assert no inner retry on `401`.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt` — register proxy↔resolved mapping at `createMediaSource` time and surface classified errors.
- `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreamsAuthRecoveryTest.kt` — new test covering the registration handoff.

---

### Task 1: Define the auth-failure status code set

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/auth/AuthFailureCodes.kt`

- [ ] **Step 1: Create the constant file**

```kotlin
package com.nexio.tv.core.player.auth

/**
 * HTTP status codes treated as "the upstream signed link is no longer valid
 * for this request". Triggers re-resolution via [CometProxyUrlResolver].
 *
 * - 401 Unauthorized — observed in production from real-debrid.com/d/<token>
 *   mirrors mid-stream when the token's IP/UA binding is no longer satisfied.
 * - 403 Forbidden — Premiumize and EnergyCDN paths emit this when the signed
 *   query parameter has expired.
 * - 410 Gone — some StremThru-fronted hosts emit this for definitively-revoked
 *   download links rather than 401.
 */
object AuthFailureCodes {
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val GONE = 410

    val ALL: Set<Int> = setOf(UNAUTHORIZED, FORBIDDEN, GONE)

    fun matches(status: Int): Boolean = status in ALL
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/auth/AuthFailureCodes.kt
git commit -m "feat(player): introduce AuthFailureCodes constant set"
```

---

### Task 2: Add reverse-cache + invalidate to CometProxyUrlResolver (failing test first)

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt`

- [ ] **Step 1: Locate the existing test class**

Run: `head -30 app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt`

Expected: a `@RunWith(RobolectricTestRunner::class)` class with `@After fun tearDown` calling `CometProxyUrlResolver.resetForTesting()`.

- [ ] **Step 2: Append failing test for reverse lookup**

Add to the class (preserve existing tests):

```kotlin
@Test
fun `proxyUrlFor returns originating proxy after a successful resolve`() {
    val proxy = "https://comet.feels.legal/aBc/playback/abcd/0/0/n/n?torrent_name=x&name=y"
    val resolved = "https://43-4.download.real-debrid.com/d/UO52ZQKDGA7G4/movie.mp4"
    CometProxyUrlResolver.setTransportForTesting { _, _ -> resolved }

    val out = runBlocking {
        CometProxyUrlResolver.resolve(proxy, headers = emptyMap())
    }

    assertEquals(resolved, out)
    assertEquals(proxy, CometProxyUrlResolver.proxyUrlFor(resolved))
}

@Test
fun `invalidate removes both forward and reverse mappings`() {
    val proxy = "https://comet.feels.legal/aBc/playback/abcd/0/0/n/n?torrent_name=x&name=y"
    val resolved = "https://43-4.download.real-debrid.com/d/A/movie.mp4"
    CometProxyUrlResolver.setTransportForTesting { _, _ -> resolved }
    runBlocking { CometProxyUrlResolver.resolve(proxy, headers = emptyMap()) }

    CometProxyUrlResolver.invalidate(proxy)

    assertNull(CometProxyUrlResolver.proxyUrlFor(resolved))
}

@Test
fun `invalidate replays the transport on next resolve`() {
    val proxy = "https://comet.feels.legal/aBc/playback/abcd/0/0/n/n?torrent_name=x&name=y"
    val first = "https://43-4.download.real-debrid.com/d/FIRST/movie.mp4"
    val second = "https://44-1.download.real-debrid.com/d/SECOND/movie.mp4"
    val calls = AtomicInteger(0)
    CometProxyUrlResolver.setTransportForTesting { _, _ ->
        if (calls.getAndIncrement() == 0) first else second
    }
    runBlocking { CometProxyUrlResolver.resolve(proxy, headers = emptyMap()) }

    CometProxyUrlResolver.invalidate(proxy)
    val out = runBlocking { CometProxyUrlResolver.resolve(proxy, headers = emptyMap()) }

    assertEquals(second, out)
    assertEquals(2, calls.get())
}
```

Add imports if missing: `import java.util.concurrent.atomic.AtomicInteger` and `import org.junit.Assert.assertNull`.

- [ ] **Step 3: Run the failing tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.CometProxyUrlResolverTest"`
Expected: FAIL — `proxyUrlFor`/`invalidate` are unresolved references.

- [ ] **Step 4: Commit the failing test (red checkpoint)**

```bash
git add app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt
git commit -m "test(player): cover reverse-cache and invalidate on CometProxyUrlResolver"
```

---

### Task 3: Implement reverse-cache + invalidate on CometProxyUrlResolver

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt`

- [ ] **Step 1: Extend the cache to include reverse mapping**

Replace the `CacheEntry` data class and the cache field to also track headers (needed for re-resolution) and add a reverse map. Find the existing block:

```kotlin
private val lock = Any()
private val cache = object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
        return size > MAX_CACHE_ENTRIES
    }
}
private val inFlight: MutableMap<String, CompletableDeferred<String?>> = HashMap()
```

Replace with:

```kotlin
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
```

- [ ] **Step 2: Update `CacheEntry` to retain headers and last-invalidate timestamp**

Replace:

```kotlin
private data class CacheEntry(val resolvedUrl: String, val storedAtMs: Long)
```

With:

```kotlin
private data class CacheEntry(
    val resolvedUrl: String,
    val storedAtMs: Long,
    val headers: Map<String, String>,
    val addonHost: String?
)
```

- [ ] **Step 3: Populate the reverse cache and headers when storing**

Find the `synchronized(lock) { … cache[url] = CacheEntry(...) }` block inside `resolve()`. Replace the storage block with one that writes both directions and saves the headers:

```kotlin
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
```

- [ ] **Step 4: Add the public lookup + invalidate API**

Insert above the `private suspend fun fetchLocation(...)` line:

```kotlin
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
 */
fun invalidate(proxyUrl: String) {
    synchronized(lock) {
        val entry = cache.remove(proxyUrl)
        if (entry != null) {
            reverseCache.remove(entry.resolvedUrl)
        }
    }
}
```

- [ ] **Step 5: Update `resetForTesting` to clear the reverse cache**

Replace:

```kotlin
internal fun resetForTesting() {
    synchronized(lock) {
        cache.clear()
        inFlight.clear()
    }
    transportOverride = null
    clockOverride = null
}
```

With:

```kotlin
internal fun resetForTesting() {
    synchronized(lock) {
        cache.clear()
        reverseCache.clear()
        inFlight.clear()
    }
    transportOverride = null
    clockOverride = null
}
```

- [ ] **Step 6: Run the test from Task 2 — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.CometProxyUrlResolverTest"`
Expected: PASS for the 3 new tests and all pre-existing tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt
git commit -m "feat(player): add reverse-cache, invalidate, and headers retention to resolver"
```

---

### Task 4: Add re-resolution debounce to resolver (failing test first)

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt`

- [ ] **Step 1: Add the failing debounce test**

Append:

```kotlin
@Test
fun `invalidate is rate-limited per proxy url`() {
    val proxy = "https://comet.feels.legal/aBc/playback/abcd/0/0/n/n?torrent_name=x&name=y"
    val resolved = "https://43-4.download.real-debrid.com/d/A/movie.mp4"
    var now = 1_000L
    CometProxyUrlResolver.setClockForTesting { now }
    CometProxyUrlResolver.setTransportForTesting { _, _ -> resolved }
    runBlocking { CometProxyUrlResolver.resolve(proxy, headers = emptyMap()) }

    assertTrue(CometProxyUrlResolver.invalidate(proxy))
    // Second invalidate within the 30s debounce window must report no-op.
    now += 5_000L
    assertFalse(CometProxyUrlResolver.invalidate(proxy))
    // After the window elapses, invalidate is honoured again.
    now += 30_000L
    runBlocking { CometProxyUrlResolver.resolve(proxy, headers = emptyMap()) }
    assertTrue(CometProxyUrlResolver.invalidate(proxy))
}
```

- [ ] **Step 2: Run the test — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.CometProxyUrlResolverTest.invalidate is rate-limited per proxy url"`
Expected: FAIL — `invalidate` returns `Unit`, so `assertTrue` won't compile.

- [ ] **Step 3: Change `invalidate` signature to return `Boolean` with debounce**

In `CometProxyUrlResolver.kt`, near the other constants:

```kotlin
private const val INVALIDATE_DEBOUNCE_MS = 30_000L
```

Add a debounce map next to `reverseCache`:

```kotlin
private val lastInvalidatedAtMs: MutableMap<String, Long> = HashMap()
```

Replace `fun invalidate(proxyUrl: String)` body:

```kotlin
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
```

Update `resetForTesting` to also clear `lastInvalidatedAtMs`:

```kotlin
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
```

- [ ] **Step 4: Run all resolver tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.CometProxyUrlResolverTest"`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt
git commit -m "feat(player): debounce resolver invalidate to 30s per proxy url"
```

---

### Task 5: Add the AuthRecoveryTracker (telemetry sink)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryTracker.kt`
- Create: `app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryTrackerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `AuthRecoveryTrackerTest.kt`:

```kotlin
package com.nexio.tv.core.player.auth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRecoveryTrackerTest {
    @After
    fun tearDown() {
        AuthRecoveryTracker.resetForTesting()
    }

    @Test
    fun `records attempts in insertion order capped at ring size`() {
        repeat(20) { i ->
            AuthRecoveryTracker.record(
                proxyUrl = "https://example/p$i",
                statusCode = 401,
                outcome = AuthRecoveryTracker.Outcome.RECOVERED
            )
        }
        val snapshot = AuthRecoveryTracker.snapshot()
        assertEquals(16, snapshot.size)
        assertEquals("https://example/p4", snapshot.first().proxyUrl)
        assertEquals("https://example/p19", snapshot.last().proxyUrl)
    }

    @Test
    fun `count returns total attempts since reset`() {
        AuthRecoveryTracker.record("u", 401, AuthRecoveryTracker.Outcome.RECOVERED)
        AuthRecoveryTracker.record("u", 401, AuthRecoveryTracker.Outcome.GAVE_UP)
        assertEquals(2, AuthRecoveryTracker.totalAttempts())
        assertEquals(1, AuthRecoveryTracker.recoveredCount())
    }
}
```

- [ ] **Step 2: Run — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.auth.AuthRecoveryTrackerTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement the tracker**

Create `AuthRecoveryTracker.kt`:

```kotlin
package com.nexio.tv.core.player.auth

import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory ring buffer of auth-recovery attempts. Used for adb-visible
 * telemetry (`adb logcat -s AuthRecovery:*`) and exposed read-only via
 * [snapshot] for diagnostic UIs / playback ZIP exports.
 */
object AuthRecoveryTracker {
    private const val TAG = "AuthRecovery"
    private const val RING_SIZE = 16

    enum class Outcome { RECOVERED, GAVE_UP, RATE_LIMITED, NO_PROXY_KNOWN }

    data class Attempt(
        val proxyUrl: String,
        val statusCode: Int,
        val outcome: Outcome,
        val timestampMs: Long
    )

    private val lock = Any()
    private val ring = ArrayDeque<Attempt>(RING_SIZE)
    private val total = AtomicInteger(0)
    private val recovered = AtomicInteger(0)

    fun record(proxyUrl: String, statusCode: Int, outcome: Outcome) {
        val attempt = Attempt(proxyUrl, statusCode, outcome, System.currentTimeMillis())
        synchronized(lock) {
            if (ring.size >= RING_SIZE) ring.removeFirst()
            ring.addLast(attempt)
        }
        total.incrementAndGet()
        if (outcome == Outcome.RECOVERED) recovered.incrementAndGet()
        Log.i(
            TAG,
            "AUTH_RECOVERY status=$statusCode outcome=$outcome proxyHost=${safeHost(proxyUrl)}"
        )
    }

    fun snapshot(): List<Attempt> = synchronized(lock) { ring.toList() }
    fun totalAttempts(): Int = total.get()
    fun recoveredCount(): Int = recovered.get()

    internal fun resetForTesting() {
        synchronized(lock) { ring.clear() }
        total.set(0)
        recovered.set(0)
    }

    private fun safeHost(url: String): String =
        runCatching { java.net.URI(url).host ?: "unknown" }.getOrDefault("unknown")
}
```

- [ ] **Step 4: Run — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.auth.AuthRecoveryTrackerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryTracker.kt app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryTrackerTest.kt
git commit -m "feat(player): add AuthRecoveryTracker ring-buffer telemetry"
```

---

### Task 6: Add EgressIpFingerprint helper (failing test first)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/auth/EgressIpFingerprint.kt`
- Create: `app/src/test/java/com/nexio/tv/core/player/auth/EgressIpFingerprintTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.player.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class EgressIpFingerprintTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `samples ip from probe endpoint`() {
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        val fp = EgressIpFingerprint(OkHttpClient(), server.url("/ip").toString())
        val sample = fp.sampleNow()
        assertEquals("1.2.3.4", sample)
    }

    @Test
    fun `compare returns Changed when ip differs from baseline`() {
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        server.enqueue(MockResponse().setBody("5.6.7.8"))
        val fp = EgressIpFingerprint(OkHttpClient(), server.url("/ip").toString())
        fp.captureBaseline()
        val state = fp.compareNow()
        assertEquals(EgressIpFingerprint.State.Changed("1.2.3.4", "5.6.7.8"), state)
    }

    @Test
    fun `compare returns Stable when ip matches baseline`() {
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        val fp = EgressIpFingerprint(OkHttpClient(), server.url("/ip").toString())
        fp.captureBaseline()
        assertEquals(EgressIpFingerprint.State.Stable("1.2.3.4"), fp.compareNow())
    }

    @Test
    fun `compare returns Unknown when probe fails`() {
        val fp = EgressIpFingerprint(OkHttpClient(), "http://127.0.0.1:1/ip")
        assertNotEquals(EgressIpFingerprint.State.Stable(""), fp.compareNow())
    }
}
```

- [ ] **Step 2: Run — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.auth.EgressIpFingerprintTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement the helper**

```kotlin
package com.nexio.tv.core.player.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the device's egress IP at session start and exposes a comparison
 * against the current IP. Used at auth-failure time to distinguish
 * "user's IP shifted" (CGNAT, VPN, network handoff — RD revokes signed links
 * bound to the original IP) from "RD revoked the link for other reasons".
 *
 * Probe endpoint is expected to return the caller's IP as plain text. Default
 * production endpoint is set in NetworkModule. Failures during probe yield
 * [State.Unknown] — never throw; this helper is observational only.
 */
class EgressIpFingerprint(
    private val client: OkHttpClient,
    private val probeUrl: String
) {
    sealed class State {
        data class Stable(val ip: String) : State()
        data class Changed(val baseline: String, val current: String) : State()
        data object Unknown : State()
    }

    private val baseline = AtomicReference<String?>(null)

    fun captureBaseline() {
        baseline.set(sampleNow())
    }

    fun sampleNow(): String? {
        return runCatching {
            val request = Request.Builder().url(probeUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string()?.trim()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    fun compareNow(): State {
        val base = baseline.get() ?: return State.Unknown
        val current = sampleNow() ?: return State.Unknown
        return if (current == base) State.Stable(base) else State.Changed(base, current)
    }
}
```

- [ ] **Step 4: Run — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.auth.EgressIpFingerprintTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/auth/EgressIpFingerprint.kt app/src/test/java/com/nexio/tv/core/player/auth/EgressIpFingerprintTest.kt
git commit -m "feat(player): add EgressIpFingerprint helper for IP-shift diagnostics"
```

---

### Task 7: Add the AuthRecoveryInterceptor — happy path test first

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt`

- [ ] **Step 1: Write the happy-path test (mid-stream 401 → recovered)**

```kotlin
package com.nexio.tv.core.player.auth

import com.nexio.tv.core.player.CometProxyUrlResolver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthRecoveryInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        CometProxyUrlResolver.resetForTesting()
        AuthRecoveryTracker.resetForTesting()
    }

    @Test
    fun `recovers from 401 by re-resolving and retrying once`() {
        // Initial resolve maps proxy -> resolvedFirst (which we will configure to 401).
        // After interceptor calls invalidate + resolve again, the resolver returns resolvedSecond.
        val resolvedFirst = server.url("/cdn/first").toString()
        val resolvedSecond = server.url("/cdn/second").toString()
        var resolveCalls = 0
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            if (resolveCalls++ == 0) resolvedFirst else resolvedSecond
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
        // Order: 1st request (401), then re-resolve happens in-process via test transport,
        // then retry to /cdn/second.
        assertEquals(listOf("/cdn/first", "/cdn/second"), recordedPaths)
        assertEquals(1, AuthRecoveryTracker.recoveredCount())
        assertTrue(AuthRecoveryTracker.totalAttempts() >= 1)
    }
}
```

- [ ] **Step 2: Run — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest"`
Expected: FAIL — `AuthRecoveryInterceptor` is unresolved.

- [ ] **Step 3: Implement the interceptor (happy path)**

Create `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt`:

```kotlin
package com.nexio.tv.core.player.auth

import android.util.Log
import com.nexio.tv.core.player.CometProxyUrlResolver
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

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

    private val attemptsRemaining = java.util.concurrent.atomic.AtomicInteger(maxAttemptsPerSession)

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
```

- [ ] **Step 4: Run the test — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt
git commit -m "feat(player): add AuthRecoveryInterceptor for transparent 401/403/410 recovery"
```

---

### Task 8: Cover the negative paths in AuthRecoveryInterceptor

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt`

- [ ] **Step 1: Add tests for unknown URL, exhausted attempts, debounce hit, and re-resolve failure**

Append to the test class:

```kotlin
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

    // First call: interceptor consumes its 1 attempt, retry also returns 401.
    val first = client.newCall(Request.Builder().url(resolved).build()).execute()
    first.use { assertEquals(401, it.code) }
    // Second call: no attempts remaining → returns 401 untouched.
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
```

- [ ] **Step 2: Run — verify all pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest"`
Expected: PASS for all four tests.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptorTest.kt
git commit -m "test(player): cover unknown-url, exhaustion, and debounce paths in AuthRecoveryInterceptor"
```

---

### Task 9: Wire the interceptor + UA parity into NetworkModule

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`

- [ ] **Step 1: Locate the playback client builder**

Run: `grep -n "providePlaybackOkHttpClient" app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`

Expected: line ~157 — the `@Provides @Named("playback")` block.

- [ ] **Step 2: Add `import` lines**

At the top of `NetworkModule.kt` (alphabetically among other player imports):

```kotlin
import com.nexio.tv.core.player.auth.AuthRecoveryInterceptor
```

- [ ] **Step 3: Add the constant for the playback default User-Agent**

Above `provideOkHttpClient` (or near the top of the `Module` object), add:

```kotlin
/**
 * Single source of truth for the User-Agent presented to debrid CDNs by every
 * playback HTTP path (ExoPlayer's OkHttpDataSource, DiskSpoolWriter, the
 * parallel range data source, the warm-ahead loop, and the resolver's redirect
 * probe). UA divergence between paths has been observed to trigger mid-stream
 * 401s on Real-Debrid edge nodes when the resolved download token is bound to
 * the original requester's UA.
 */
internal const val NEXIO_PLAYBACK_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
```

- [ ] **Step 4: Install both interceptors on the playback client**

Inside `providePlaybackOkHttpClient`, after the existing redirect interceptor's `.addInterceptor { … }` block, before `.build()`:

```kotlin
.addInterceptor { chain ->
    val original = chain.request()
    val request = if (original.header("User-Agent") == null) {
        original.newBuilder().header("User-Agent", NEXIO_PLAYBACK_USER_AGENT).build()
    } else original
    chain.proceed(request)
}
.addInterceptor(AuthRecoveryInterceptor())
```

The UA interceptor must come **before** `AuthRecoveryInterceptor` so the recovered request reissue inherits a UA too.

- [ ] **Step 5: Build to confirm compilation**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt
git commit -m "feat(network): install AuthRecoveryInterceptor and UA parity on playback client"
```

---

### Task 10: Replace PlayerMediaSourceFactory's hard-coded UA with the shared constant

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

- [ ] **Step 1: Locate the duplicated constant**

Run: `grep -n "DEFAULT_USER_AGENT" app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

Expected: line ~1272 (the `private const val DEFAULT_USER_AGENT` block) and line ~706 (its consumer).

- [ ] **Step 2: Remove the local constant and import the shared one**

Add the import:

```kotlin
import com.nexio.tv.core.di.NEXIO_PLAYBACK_USER_AGENT
```

Delete the `private const val DEFAULT_USER_AGENT = …` block (the multi-line UA string at line ~1272).

Replace the consumer:

```kotlin
if (!headers.containsKey("User-Agent")) {
    setUserAgent(DEFAULT_USER_AGENT)
}
```

With:

```kotlin
if (!headers.containsKey("User-Agent")) {
    setUserAgent(NEXIO_PLAYBACK_USER_AGENT)
}
```

- [ ] **Step 3: Build to confirm**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git commit -m "refactor(player): consolidate playback User-Agent constant in NetworkModule"
```

---

### Task 11: Stop DiskSpoolWriter from retrying on auth-failure status codes

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`

The interceptor already issued one retry. Letting the writer's 4×retry loop run on top wastes ~1 s and four extra mock-response slots in tests, and amplifies the failure on real RD when the link is genuinely revoked.

- [ ] **Step 1: Add the failing test**

Append to `DiskSpoolWriterTest.kt`:

```kotlin
@Test
fun `range download does not retry on 401 response`() {
    val attempts = AtomicInteger(0)
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return when (request.getHeader("Range")) {
                "bytes=0-0" -> MockResponse()
                    .setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes 0-0/4096")
                    .setHeader("Content-Length", 1)
                    .setBody(Buffer().writeByte(0x2A))
                else -> {
                    attempts.incrementAndGet()
                    MockResponse().setResponseCode(401)
                }
            }
        }
    }
    server.start()
    try {
        val writer = DiskSpoolWriter(OkHttpClient(), chunkBytes = 1024)
        val session = DiskSpoolSession(File(temp.root, "spool.bin"), capacityBytes = 4096)
        try {
            try {
                writer.downloadUntil(server.url("/movie.bin").toString(), session, 4096L)
                org.junit.Assert.fail("Expected IOException for unrecoverable 401")
            } catch (_: IOException) {
                // expected
            }
            assertEquals(1, attempts.get())
        } finally {
            session.close()
        }
    } finally {
        server.shutdown()
    }
}
```

Add imports if missing: `import com.nexio.tv.core.player.auth.AuthFailureCodes` (used in the implementation), `import java.io.IOException`, `import java.util.concurrent.atomic.AtomicInteger`.

- [ ] **Step 2: Run — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest.range download does not retry on 401 response"`
Expected: FAIL — observed attempts = 4 (the writer's existing retry loop).

- [ ] **Step 3: Skip retries for auth-failure codes in `downloadRangeIntoSession`**

In `DiskSpoolWriter.kt`, locate the `private fun downloadRangeIntoSession(url: String, …)` block (around line 246). Replace the `if (!response.isSuccessful)` branch:

```kotlin
if (!response.isSuccessful) {
    throw IOException("Unexpected response ${response.code} for range $start-$endInclusive")
}
```

With:

```kotlin
if (!response.isSuccessful) {
    if (AuthFailureCodes.matches(response.code)) {
        throw UnrecoverableHttpException(response.code,
            "Auth-failure response ${response.code} for range $start-$endInclusive")
    }
    throw IOException("Unexpected response ${response.code} for range $start-$endInclusive")
}
```

Add the typed exception inside `DiskSpoolWriter` (just above the `private companion object` block):

```kotlin
internal class UnrecoverableHttpException(val statusCode: Int, message: String) : IOException(message)
```

Replace the catch block of the same function:

```kotlin
} catch (throwable: IOException) {
    if (isCancelled()) {
        return start
    }
    if (attempt >= 4) {
        throw throwable
    }
    try {
        Thread.sleep(minOf(50L * attempt, 250L))
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while retrying range $start-$endInclusive", interrupted)
    }
    attempt++
}
```

With:

```kotlin
} catch (throwable: UnrecoverableHttpException) {
    throw throwable
} catch (throwable: IOException) {
    if (isCancelled()) {
        return start
    }
    if (attempt >= 4) {
        throw throwable
    }
    try {
        Thread.sleep(minOf(50L * attempt, 250L))
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while retrying range $start-$endInclusive", interrupted)
    }
    attempt++
}
```

Add the import to `DiskSpoolWriter.kt`:

```kotlin
import com.nexio.tv.core.player.auth.AuthFailureCodes
```

- [ ] **Step 4: Run — verify pass and no regressions**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest"`
Expected: PASS for the new test and all existing writer tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt
git commit -m "fix(spool): stop retrying on auth-failure codes inside DiskSpoolWriter"
```

---

### Task 12: Ensure proxy↔resolved mapping is registered for every playable URL

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreamsAuthRecoveryTest.kt`

The interceptor only fires when the failing URL has a reverse mapping in the resolver. Today, autoplay's prewarm path populates that mapping, but a manually selected stream that bypasses prewarm could end up with an unmapped URL even when the source was a Comet proxy. We close that gap by always running through `CometProxyUrlResolver.resolve` for proxy URLs at `createMediaSource` time.

- [ ] **Step 1: Add the failing test**

Create `PlayerRuntimeControllerStreamsAuthRecoveryTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import com.nexio.tv.core.player.CometProxyUrlResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerRuntimeControllerStreamsAuthRecoveryTest {
    @After fun tearDown() = CometProxyUrlResolver.resetForTesting()

    @Test
    fun `prepareMediaSourceUrl resolves comet proxy and returns resolved url`() {
        val proxy = "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n"
        val resolved = "https://1-1.download.real-debrid.com/d/AAAA/movie.mp4"
        CometProxyUrlResolver.setTransportForTesting { _, _ -> resolved }

        val out = prepareMediaSourceUrlForTesting(
            url = proxy,
            headers = emptyMap(),
            addonHost = "comet.feels.legal"
        )

        assertEquals(resolved, out)
        assertEquals(proxy, CometProxyUrlResolver.proxyUrlFor(resolved))
    }

    @Test
    fun `prepareMediaSourceUrl returns input unchanged when not a proxy`() {
        val direct = "https://1-1.download.real-debrid.com/d/AAAA/movie.mp4"
        val out = prepareMediaSourceUrlForTesting(
            url = direct,
            headers = emptyMap(),
            addonHost = null
        )
        assertEquals(direct, out)
    }
}
```

- [ ] **Step 2: Run — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerStreamsAuthRecoveryTest"`
Expected: FAIL — `prepareMediaSourceUrlForTesting` is unresolved.

- [ ] **Step 3: Add the helper to `PlayerRuntimeControllerStreams.kt`**

At the bottom of the file (top-level, not inside any class), add:

```kotlin
internal fun prepareMediaSourceUrl(
    url: String,
    headers: Map<String, String>,
    addonHost: String?
): String {
    if (!CometProxyUrlResolver.isCometProxy(url, addonHost)) return url
    return CometProxyUrlResolver.resolveBlocking(url, headers, addonHost) ?: url
}

@VisibleForTesting
internal fun prepareMediaSourceUrlForTesting(
    url: String,
    headers: Map<String, String>,
    addonHost: String?
): String = prepareMediaSourceUrl(url, headers, addonHost)
```

Add imports:

```kotlin
import androidx.annotation.VisibleForTesting
import com.nexio.tv.core.player.CometProxyUrlResolver
```

- [ ] **Step 4: Wire the helper into the two `createMediaSource` call sites**

Run: `grep -n "mediaSourceFactory.createMediaSource" app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`

Expected: lines 449 and 785.

For both call sites, find the existing `mediaSourceFactory.createMediaSource(url, newHeaders)` line and replace with:

```kotlin
val playableUrl = prepareMediaSourceUrl(
    url = url,
    headers = newHeaders,
    addonHost = stream.addonBaseUrl
        ?.let(CometProxyUrlResolver::hostOfAddonBaseUrl)
)
mediaSourceFactory.createMediaSource(playableUrl, newHeaders)
```

If `stream` is not in scope at one of the call sites, capture the `addonBaseUrl` before launching the coroutine and reference it directly.

- [ ] **Step 5: Run the new test — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerStreamsAuthRecoveryTest"`
Expected: PASS.

- [ ] **Step 6: Build to confirm full compilation**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreamsAuthRecoveryTest.kt
git commit -m "feat(player): always resolve proxy URLs at createMediaSource so AuthRecoveryInterceptor can recover"
```

---

### Task 13: Add user-facing error classification

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/auth/PlaybackErrorClassifier.kt`
- Create: `app/src/test/java/com/nexio/tv/core/player/auth/PlaybackErrorClassifierTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.player.auth

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackErrorClassifierTest {
    @Test
    fun `401 invalid response code is classified as link expired`() {
        val cause = HttpDataSource.InvalidResponseCodeException(
            401, "Unauthorized", emptyMap(),
            HttpDataSource.HttpDataSourceException(
                java.io.IOException(), java.io.IOException()
                    .let { _ -> okhttp3.HttpUrl.Builder().scheme("https").host("a").build().toUri() }
                    .let(::createDataSpec),
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            ).dataSpec, ByteArray(0)
        )
        val exception = PlaybackException(
            "open", cause,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        val classified = PlaybackErrorClassifier.classify(exception)
        assertEquals(PlaybackErrorClassifier.Classification.LinkExpired, classified)
    }

    @Test
    fun `non-http exception classifies to generic`() {
        val exception = PlaybackException(
            "decode", IllegalStateException(),
            PlaybackException.ERROR_CODE_DECODING_FAILED
        )
        assertEquals(
            PlaybackErrorClassifier.Classification.Generic,
            PlaybackErrorClassifier.classify(exception)
        )
    }

    private fun createDataSpec(uri: java.net.URI) =
        androidx.media3.datasource.DataSpec(android.net.Uri.parse(uri.toString()))
}
```

- [ ] **Step 2: Run — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.auth.PlaybackErrorClassifierTest"`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement the classifier**

```kotlin
package com.nexio.tv.core.player.auth

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

object PlaybackErrorClassifier {
    sealed class Classification(val userMessage: String) {
        data object LinkExpired : Classification(
            "Stream link expired or was revoked. Try selecting another source."
        )
        data object Forbidden : Classification(
            "Stream blocked by the debrid host. Try selecting another source."
        )
        data object Generic : Classification(
            "Playback failed. Try selecting another source."
        )
    }

    fun classify(exception: PlaybackException): Classification {
        var cause: Throwable? = exception.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return when (cause.responseCode) {
                    AuthFailureCodes.UNAUTHORIZED, AuthFailureCodes.GONE -> Classification.LinkExpired
                    AuthFailureCodes.FORBIDDEN -> Classification.Forbidden
                    else -> Classification.Generic
                }
            }
            cause = cause.cause
        }
        return Classification.Generic
    }
}
```

- [ ] **Step 4: Run — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.auth.PlaybackErrorClassifierTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/auth/PlaybackErrorClassifier.kt app/src/test/java/com/nexio/tv/core/player/auth/PlaybackErrorClassifierTest.kt
git commit -m "feat(player): classify playback HTTP errors into user-actionable messages"
```

---

### Task 14: Surface classified errors via Player.Listener.onPlayerError

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`

- [ ] **Step 1: Find the existing `onPlayerError` handler**

Run: `grep -n "onPlayerError" app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

Expected: at least one match — the listener that updates `_uiState.error`.

- [ ] **Step 2: Replace the raw error-message read with the classifier**

Wherever the listener reads `error.message` to populate `_uiState`, replace with:

```kotlin
val classification = PlaybackErrorClassifier.classify(error)
_uiState.update { it.copy(error = classification.userMessage) }
```

Add the import at the top of the modified file:

```kotlin
import com.nexio.tv.core.player.auth.PlaybackErrorClassifier
```

- [ ] **Step 3: Build to confirm**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt
git commit -m "feat(player): surface classified playback errors through onPlayerError"
```

---

### Task 15: Capture egress-IP baseline at session start, log on auth failure

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`

- [ ] **Step 1: Provide a singleton `EgressIpFingerprint` from NetworkModule**

In `NetworkModule.kt`, add:

```kotlin
@Provides
@Singleton
fun provideEgressIpFingerprint(
    @Named("playback") client: OkHttpClient
): EgressIpFingerprint = EgressIpFingerprint(
    client = client,
    probeUrl = "https://api.ipify.org/"
)
```

Add imports as required:

```kotlin
import com.nexio.tv.core.player.auth.EgressIpFingerprint
```

- [ ] **Step 2: Wire baseline capture into the player session start**

In `PlayerRuntimeControllerStreams.kt`, find the function that starts a stream (around `mediaSourceFactory.createMediaSource(url, newHeaders)` at line 449). Inject the fingerprint via the existing constructor parameters of the controller (or a new field):

Add the field to `PlayerRuntimeController`'s constructor parameter list (one path: pass through Hilt where the controller is constructed). Then before the `scope.launch` that creates the media source, add:

```kotlin
egressIpFingerprint.captureBaseline()
```

Search for the controller construction site (`@Inject` constructor or factory) to thread the dependency through. If the controller is constructed by Hilt, add the parameter and let Hilt resolve it; otherwise update the call-site to pass it.

- [ ] **Step 3: Have AuthRecoveryInterceptor query the fingerprint on failure**

Update `AuthRecoveryInterceptor.kt` to accept an optional `EgressIpFingerprint`:

```kotlin
class AuthRecoveryInterceptor(
    private val maxAttemptsPerSession: Int = 3,
    private val egressIpFingerprint: EgressIpFingerprint? = null
) : Interceptor {
```

Inside `intercept`, after the `attemptsRemaining.decrementAndGet()` check, add:

```kotlin
val ipState = egressIpFingerprint?.compareNow()
if (ipState is EgressIpFingerprint.State.Changed) {
    Log.w(
        TAG,
        "EGRESS_IP_SHIFTED baseline=${ipState.baseline} current=${ipState.current} " +
            "proxyHost=${original.url.host}"
    )
}
```

Add the import:

```kotlin
import com.nexio.tv.core.player.auth.EgressIpFingerprint
```

- [ ] **Step 4: Pass the fingerprint into the interceptor in NetworkModule**

In `providePlaybackOkHttpClient`, change the signature to accept the fingerprint provider via a method-level parameter is not possible because it depends on the same client. Resolve via lazy: build the client first without the fingerprint, then add a separate `@Provides` for `AuthRecoveryInterceptor` and inject the fingerprint there.

Replace the inline `.addInterceptor(AuthRecoveryInterceptor())` with:

```kotlin
.addInterceptor(authRecoveryInterceptor)
```

And change the function signature:

```kotlin
@Provides
@Singleton
@Named("playback")
fun providePlaybackOkHttpClient(
    @ApplicationContext context: Context,
    @Named("playback.callTimeoutMs") callTimeoutMs: Long,
    authRecoveryInterceptor: AuthRecoveryInterceptor
): OkHttpClient { ... }

@Provides
@Singleton
fun provideAuthRecoveryInterceptor(
    egressIpFingerprint: EgressIpFingerprint
): AuthRecoveryInterceptor = AuthRecoveryInterceptor(
    egressIpFingerprint = egressIpFingerprint
)
```

The fingerprint depends on the playback client; this would be circular. Break the cycle by giving the fingerprint a non-playback `OkHttpClient` (use the default `provideOkHttpClient`):

```kotlin
@Provides
@Singleton
fun provideEgressIpFingerprint(
    okHttpClient: OkHttpClient
): EgressIpFingerprint = EgressIpFingerprint(
    client = okHttpClient,
    probeUrl = "https://api.ipify.org/"
)
```

- [ ] **Step 5: Build to confirm and run all auth tests**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.auth.*"`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt
git commit -m "feat(player): log egress-IP shift alongside auth-recovery attempts"
```

---

### Task 16: End-to-end integration test using MockWebServer (range fetch + 401 + recover)

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/player/auth/PlaybackAuthRecoveryEndToEndTest.kt`

This test wires the actual `playbackOkHttpClient` configuration (via direct construction with the same interceptors) and exercises the *full* DiskSpoolWriter → AuthRecoveryInterceptor → CometProxyUrlResolver loop, proving that a mid-stream 401 on a forward range request is silently recovered.

- [ ] **Step 1: Write the integration test**

```kotlin
package com.nexio.tv.core.player.auth

import com.nexio.tv.core.player.CometProxyUrlResolver
import com.nexio.tv.ui.screens.player.spool.DiskSpoolSession
import com.nexio.tv.ui.screens.player.spool.DiskSpoolWriter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class PlaybackAuthRecoveryEndToEndTest {
    @get:Rule val temp = TemporaryFolder()

    @After
    fun tearDown() {
        CometProxyUrlResolver.resetForTesting()
        AuthRecoveryTracker.resetForTesting()
    }

    @Test
    fun `disk spool recovers transparently from mid-stream 401`() {
        val cdnA = MockWebServer().also { it.start() }
        val cdnB = MockWebServer().also { it.start() }
        val totalBytes = 4 * 1024 * 1024
        val payloadA = ByteArray(totalBytes) { 0xAA.toByte() }
        val payloadB = ByteArray(totalBytes) { 0xBB.toByte() }
        val resolveCount = AtomicInteger(0)

        try {
            // Resolver returns A first, then B after invalidate.
            CometProxyUrlResolver.setTransportForTesting { _, _ ->
                if (resolveCount.getAndIncrement() == 0)
                    cdnA.url("/movie.bin").toString()
                else
                    cdnB.url("/movie.bin").toString()
            }
            val proxy = "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n"
            runBlocking {
                CometProxyUrlResolver.resolve(proxy, headers = emptyMap(), addonHost = "comet.feels.legal")
            }

            cdnA.dispatcher = makeDispatcher(payloadA, after512KbReturn401 = true)
            cdnB.dispatcher = makeDispatcher(payloadB, after512KbReturn401 = false)

            val client = OkHttpClient.Builder()
                .addInterceptor(AuthRecoveryInterceptor())
                .build()
            val writer = DiskSpoolWriter(client, chunkBytes = 256 * 1024)
            val session = DiskSpoolSession(File(temp.root, "spool.bin"), capacityBytes = totalBytes.toLong())

            try {
                writer.downloadUntil(cdnA.url("/movie.bin").toString(), session, totalBytes.toLong())
            } finally {
                session.close()
            }

            // Recovered at least once.
            assertTrue(AuthRecoveryTracker.recoveredCount() >= 1)
        } finally {
            cdnA.shutdown()
            cdnB.shutdown()
        }
    }

    private fun makeDispatcher(payload: ByteArray, after512KbReturn401: Boolean): Dispatcher {
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range") ?: ""
                if (range == "bytes=0-0") {
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${payload.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(payload[0].toInt()))
                }
                val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range)
                    ?: return MockResponse().setResponseCode(400)
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toInt()
                if (after512KbReturn401 && start >= 512 * 1024) {
                    return MockResponse().setResponseCode(401)
                }
                val slice = payload.copyOfRange(start, end + 1)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setHeader("Content-Length", slice.size)
                    .setBody(Buffer().write(slice))
            }
        }
    }
}
```

- [ ] **Step 2: Run — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.auth.PlaybackAuthRecoveryEndToEndTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/player/auth/PlaybackAuthRecoveryEndToEndTest.kt
git commit -m "test(player): end-to-end auth-recovery via DiskSpoolWriter + interceptor"
```

---

### Task 17: Run the whole player test suite to confirm no regressions

**Files:** none

- [ ] **Step 1: Run player tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.*" --tests "com.nexio.tv.ui.screens.player.*"`
Expected: ALL PASS. If anything fails, debug as a normal regression — none of the changes alter business semantics for non-auth-failure paths.

- [ ] **Step 2: Run a full assemble**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: If green, commit any incidental fixups; otherwise stop and triage**

```bash
git status
# If clean, this task is done.
```

---

### Task 18: On-device verification protocol against the Fire TV at 192.168.50.98

**Files:** none — manual verification.

- [ ] **Step 1: Install the build to the device**

Run: `adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`.

- [ ] **Step 2: Open a logcat window and start a playback**

Run (separate shell): `adb -s 192.168.50.98:5555 logcat -v threadtime AuthRecovery:V CometProxyResolver:V PlayerMediaSource:V DiskSpoolWriter:V *:W`

In NEXIO, start playback of any RD-backed stream that took ≥4 minutes to fail historically (the same `Monarch: Legacy of Monsters` series episode as the bug report is a reliable repro).

- [ ] **Step 3: Confirm baseline IP is captured**

In the logcat output, expect a single early entry of the form:

```
I AuthRecovery: AUTH_RECOVERY ...
```

If no recovery is needed, no `AUTH_RECOVERY` line will print — which is fine.

- [ ] **Step 4: If a 401 occurs**

Confirm the sequence:

1. `W AuthRecovery: RETRYING_AFTER_AUTH_FAIL status=401 fromHost=… toHost=…`
2. Playback continues without the audio drain stop observed before.
3. `I AuthRecovery: AUTH_RECOVERY status=401 outcome=RECOVERED proxyHost=…`
4. `DiskSpoolWriter`'s prior "Disk spool writer failed; closing spool session" message must NOT appear in the same window.

- [ ] **Step 5: If recovery exhausts (genuine link revoke)**

Confirm:

1. Three `RETRYING_AFTER_AUTH_FAIL` entries followed by `outcome=GAVE_UP`.
2. ExoPlayer surfaces an error and the UI shows `Stream link expired or was revoked. Try selecting another source.` (the `LinkExpired` classification).

- [ ] **Step 6: Capture a logcat snippet**

```bash
adb -s 192.168.50.98:5555 logcat -d -v threadtime -t 5000 > /tmp/nexio-auth-recovery-verification.log
grep -E "AuthRecovery|EGRESS_IP_SHIFTED|RETRYING_AFTER_AUTH_FAIL" /tmp/nexio-auth-recovery-verification.log | head -50
```

Attach the resulting snippet to the merge / PR description so we have on-device evidence the recovery path fired.

---

## Self-Review

**Spec coverage:**
- Mid-stream 401 transparent recovery (the original incident): Tasks 7–9, 12, 16 cover it end-to-end.
- "Works across all caching methods" (user clarification): the interceptor is installed on the singleton `playbackOkHttpClient` (Task 9), which is the same client used by `OkHttpDataSource` (ExoPlayer's progressive/HLS/DASH paths), `DiskSpoolWriter` (Task 11), `ParallelRangeDataSource` (constructed with `OkHttpDataSource.Factory(playbackOkHttpClient)`), and the warm-ahead loop. No per-cache plumbing required.
- UA divergence between resolver and consumers: Tasks 9 and 10 unify on `NEXIO_PLAYBACK_USER_AGENT`.
- IP-shift detection: Task 15.
- User-actionable error surfacing: Tasks 13 and 14.
- Telemetry / observability: Task 5; on-device protocol Task 18.
- The FFmpeg native probe path is intentionally out of scope (called out in the architecture preamble).

**Placeholder scan:** none of the disallowed phrases appear; every code-changing step contains the actual code.

**Type consistency:**
- `AuthRecoveryTracker.Outcome` enum values (`RECOVERED`, `GAVE_UP`, `RATE_LIMITED`, `NO_PROXY_KNOWN`) are consistent across Tasks 5, 7, 8.
- `EgressIpFingerprint.State` shape (`Stable`, `Changed`, `Unknown`) is consistent across Tasks 6 and 15.
- `CometProxyUrlResolver.invalidate(...)` returns `Boolean` after Task 4 and is read as a `Boolean` in Task 7 (interceptor) and Task 8 (rate-limit test).
- `prepareMediaSourceUrl(url, headers, addonHost)` signature is identical between definition (Task 12) and call sites.
- `NEXIO_PLAYBACK_USER_AGENT` is referenced from both `NetworkModule` (Task 9) and `PlayerMediaSourceFactory` (Task 10).
