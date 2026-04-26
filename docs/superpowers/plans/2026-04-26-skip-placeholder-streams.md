# Skip placeholder streams — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make autoplay skip Stremio addon "placeholder" responses (200 OK + `video/*` served from the addon's own host) and advance to the next eligible stream, leveraging the resolver's existing redirect-disabled probe — no new HTTP traffic.

**Architecture:** Extend `CometProxyUrlResolver`'s existing redirect-disabled `GET` to classify responses into a `ProxyResolution` sealed class (`Redirected | Placeholder | NotEligible | ResolveFailed`). The autoplay candidate-selection path injects a predicate that consults `lastResolutionFor(url)` (a 30-second TTL verdict cache populated as a side-effect of the existing `prewarm()` call) and drops any `Placeholder` candidates **before** any ffprobe runs. A new DataStore boolean (default ON) gates the behavior. Manual stream selection is unaffected.

**Tech Stack:** Kotlin, Android, Jetpack Compose, OkHttp, `androidx.datastore.preferences`, JUnit/MockK (existing test stack).

**Spec:** `docs/superpowers/specs/2026-04-26-skip-placeholder-streams-design.md`

**Conventions:**
- Branch: `feat/skip-placeholder-streams`
- Run a single test: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.<FQ>.<test>'`
- Run full suite for a file: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.player.CometProxyUrlResolverTest.*'`
- Build smoke: `./gradlew :app:assembleDebug`
- Commit messages: Conventional commits (`feat(player): …`, `test(player): …`, `refactor(player): …`).

---

## File Structure

**New / modified source:**

- `app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt` — add `ProxyResolution` sealed class, change return shape, add short-verdict cache + `lastResolutionFor`, classify 200-from-addon-host as `Placeholder`.
- `app/src/main/java/com/nexio/tv/core/player/StreamAutoPlaySelector.kt` — accept placeholder predicate param in `candidateAutoPlayStreams`.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt` — wire settings flow + predicate, fire toast on placeholder-induced empty result, adapt `prepareMediaSourceUrl` callers.
- `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt` — adapt `prepareMediaSourceUrl` for new return shape.
- `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt` — adapt any callers of resolver methods that changed shape.
- `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt` — new boolean `skipPlaceholderStreamsEnabled`, default `true`.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/<existing player-settings composable>` — add toggle row (file located in Task 9).

**New / modified tests:**

- `app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt` — extend (existing).
- `app/src/test/java/com/nexio/tv/core/player/StreamAutoPlaySelectorPlaceholderTest.kt` — new.
- `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSkipPlaceholderTest.kt` — new (or extend an existing PlayerSettingsDataStore test if one exists).
- `app/src/test/java/com/nexio/tv/core/player/auth/PlaybackAuthRecoveryEndToEndTest.kt` — extend with one placeholder scenario.

---

## Task 1: Add `ProxyResolution` sealed class and widen the `Transport` interface

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt`

**Why first:** Every other change depends on this type existing. We add the type and adapt the internal `Transport` interface in one step, but the public `resolve()` / `resolveBlocking()` / `prewarm()` signatures stay on `String?` for now (we'll migrate them in Task 3) so the rest of the codebase still compiles.

- [ ] **Step 1: Read the existing resolver file end-to-end**

Read `app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt` in full. Pay attention to:
- `defaultTransport` (line 431-459) — where the response is currently classified into `redirect` / `null-no-redirect`.
- `Transport` functional interface (line 494-496).
- `setTransportForTesting` and `resetForTesting`.
- The `CacheEntry` data class (line 487-492).

- [ ] **Step 2: Write the failing test for the sealed class shape**

Add to `app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt`:

```kotlin
@Test
fun `ProxyResolution_Redirected exposes the resolved url`() {
    val r: ProxyResolution = ProxyResolution.Redirected("https://cdn.example/file.mp4")
    assertTrue(r is ProxyResolution.Redirected)
    assertEquals("https://cdn.example/file.mp4", (r as ProxyResolution.Redirected).url)
}

@Test
fun `ProxyResolution sealed class has four variants`() {
    val variants: List<ProxyResolution> = listOf(
        ProxyResolution.Redirected("x"),
        ProxyResolution.Placeholder,
        ProxyResolution.NotEligible,
        ProxyResolution.ResolveFailed,
    )
    assertEquals(4, variants.size)
}
```

Make sure the imports include `com.nexio.tv.core.player.ProxyResolution`.

- [ ] **Step 3: Run the tests to verify they fail to compile**

Run:
```
./gradlew :app:compileDebugUnitTestKotlin
```
Expected: Kotlin compile error — unresolved reference `ProxyResolution`.

- [ ] **Step 4: Add the sealed class and widen `Transport`**

Edit `CometProxyUrlResolver.kt`. Above the `object CometProxyUrlResolver { ... }` declaration (above line 31), add:

```kotlin
/**
 * Outcome of a single redirect-disabled probe issued by [CometProxyUrlResolver].
 *
 * - [Redirected]    — addon returned `3xx` with a usable `Location`.
 * - [Placeholder]   — addon returned `200 OK` with `Content-Type: video/*`,
 *                     served from the addon's own host. Treated as a captioned
 *                     "error video" placeholder; autoplay should skip it.
 * - [NotEligible]   — URL did not pass `isCometProxy()` gates (not an addon proxy
 *                     URL we know how to interpret). Caller should treat the URL
 *                     as opaque.
 * - [ResolveFailed] — network error, non-`video/*` `200`, missing Content-Type,
 *                     `4xx`, or `5xx`. Caller should fall back to the original URL.
 */
sealed class ProxyResolution {
    data class Redirected(val url: String) : ProxyResolution()
    object Placeholder : ProxyResolution()
    object NotEligible : ProxyResolution()
    object ResolveFailed : ProxyResolution()
}
```

Inside the object, replace the `Transport` definition (currently around line 494) with:

```kotlin
internal fun interface Transport {
    fun execute(url: String, headers: Map<String, String>?): ProxyResolution
}
```

Do **not** yet change `resolve()`, `resolveBlocking()`, `prewarm()`, `defaultTransport`, or `fetchLocation`. Those still need to expose `String?`. We'll bridge in the next step.

- [ ] **Step 5: Bridge `defaultTransport` and `fetchLocation` so the file still compiles**

Replace `defaultTransport` (lines ~431-459) with the new return shape, but classify only `redirect` vs `null-no-redirect` for now (placeholder logic comes in Task 2):

```kotlin
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
        val outcome: ProxyResolution = when {
            response.code in 300..399 && !location.isNullOrBlank() ->
                ProxyResolution.Redirected(location)
            else -> ProxyResolution.ResolveFailed
        }
        val decision = when (outcome) {
            is ProxyResolution.Redirected -> "redirect"
            ProxyResolution.Placeholder -> "placeholder"
            ProxyResolution.NotEligible -> "not-eligible"
            ProxyResolution.ResolveFailed -> "failed"
        }
        Log.i(
            TAG,
            "RESOLVE_RESPONSE url=${sanitize(url)} status=${response.code} " +
                "protocol=${response.protocol} locationPresent=${!location.isNullOrBlank()} " +
                "locationHost=${locationHost ?: "none"} decision=$decision " +
                "forwardedHeaderKeys=${forwardedHeaderKeys.joinToString(prefix = "[", postfix = "]")}"
        )
        outcome
    }
}
```

Replace `fetchLocation` (line 424-429) with a private bridge that the existing callers still expect:

```kotlin
private suspend fun fetchLocation(url: String, headers: Map<String, String>?): String? {
    val transport = transportOverride ?: defaultTransport
    val outcome: ProxyResolution = withContext(Dispatchers.IO) {
        transport.execute(url, headers)
    }
    return when (outcome) {
        is ProxyResolution.Redirected -> outcome.url
        else -> null
    }
}
```

This keeps `resolve()`, `resolveBlocking()`, and `prewarm()` returning `String?` as they do today — Task 3 will migrate those.

- [ ] **Step 6: Update `setTransportForTesting` callers in tests if needed**

The signature change from `(url, headers) -> String?` to `(url, headers) -> ProxyResolution` will break any existing test code that wires a fake `Transport`. In `CometProxyUrlResolverTest.kt`, find calls to `CometProxyUrlResolver.setTransportForTesting { ... }` (or to the `Transport { url, headers -> ... }` lambda) and update each lambda to return one of:

- `ProxyResolution.Redirected("https://cdn/...")` for previously-`String` cases
- `ProxyResolution.ResolveFailed` for previously-`null` cases

Do not change test behavior expectations yet.

- [ ] **Step 7: Run the resolver tests and the full build**

```
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.player.CometProxyUrlResolverTest.*'
```

Expected: compiles. New `ProxyResolution` tests PASS. Existing resolver tests PASS unchanged.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt \
       app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt
git commit -m "feat(player): add ProxyResolution sealed class and widen Transport interface"
```

---

## Task 2: Classify `200 + video/*` as `Placeholder`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt`

- [ ] **Step 1: Write failing tests for placeholder classification**

Append to `CometProxyUrlResolverTest.kt`. The pattern uses `setTransportForTesting` to fake the HTTP layer; do NOT inject through `defaultTransport` directly (the file uses a `transportOverride` private field via the test hook).

Note: these tests assert on the **internal** transport classification by going through a manually-invoked `Transport` instance. We'll add a test-only helper that exposes the default transport's classification logic. Two options:

A) **Preferred:** Refactor the classification into a pure function inside the file (`internal fun classifyHttpResponse(code: Int, location: String?, contentType: String?): ProxyResolution`) and unit-test it directly.

B) Drive through the existing public API.

Use option A. First, write the tests:

```kotlin
@Test
fun `classifyHttpResponse returns Redirected on 302 with Location`() {
    val out = classifyHttpResponse(code = 302, location = "https://cdn.example/x", contentType = null)
    assertEquals(ProxyResolution.Redirected("https://cdn.example/x"), out)
}

@Test
fun `classifyHttpResponse returns Placeholder on 200 with video content-type`() {
    val out = classifyHttpResponse(code = 200, location = null, contentType = "video/mp4")
    assertEquals(ProxyResolution.Placeholder, out)
}

@Test
fun `classifyHttpResponse returns Placeholder on 200 with case-insensitive video content-type`() {
    val out = classifyHttpResponse(code = 200, location = null, contentType = "Video/MP4; codecs=avc1")
    assertEquals(ProxyResolution.Placeholder, out)
}

@Test
fun `classifyHttpResponse returns ResolveFailed on 200 with text-html`() {
    val out = classifyHttpResponse(code = 200, location = null, contentType = "text/html; charset=utf-8")
    assertEquals(ProxyResolution.ResolveFailed, out)
}

@Test
fun `classifyHttpResponse returns ResolveFailed on 200 with no content-type`() {
    val out = classifyHttpResponse(code = 200, location = null, contentType = null)
    assertEquals(ProxyResolution.ResolveFailed, out)
}

@Test
fun `classifyHttpResponse returns ResolveFailed on 4xx`() {
    val out = classifyHttpResponse(code = 401, location = null, contentType = "video/mp4")
    assertEquals(ProxyResolution.ResolveFailed, out)
}

@Test
fun `classifyHttpResponse returns ResolveFailed on 5xx`() {
    val out = classifyHttpResponse(code = 503, location = null, contentType = "video/mp4")
    assertEquals(ProxyResolution.ResolveFailed, out)
}

@Test
fun `classifyHttpResponse returns ResolveFailed on 3xx without Location`() {
    val out = classifyHttpResponse(code = 302, location = null, contentType = null)
    assertEquals(ProxyResolution.ResolveFailed, out)
}

@Test
fun `classifyHttpResponse returns ResolveFailed on 3xx with blank Location`() {
    val out = classifyHttpResponse(code = 302, location = "  ", contentType = null)
    assertEquals(ProxyResolution.ResolveFailed, out)
}
```

Make sure the import is `import com.nexio.tv.core.player.classifyHttpResponse` (or whatever package the helper lives in once added).

- [ ] **Step 2: Run tests to verify they fail to compile**

```
./gradlew :app:compileDebugUnitTestKotlin
```
Expected: unresolved reference `classifyHttpResponse`.

- [ ] **Step 3: Implement `classifyHttpResponse` and call it from `defaultTransport`**

In `CometProxyUrlResolver.kt`, add an internal top-level helper (file-level, outside the object, or inside as `internal` — pick file-level so the test imports it cleanly):

```kotlin
internal fun classifyHttpResponse(
    code: Int,
    location: String?,
    contentType: String?,
): ProxyResolution {
    if (code in 300..399 && !location.isNullOrBlank()) {
        return ProxyResolution.Redirected(location)
    }
    if (code == 200 && contentType != null && contentType.trim().lowercase(Locale.ROOT).startsWith("video/")) {
        return ProxyResolution.Placeholder
    }
    return ProxyResolution.ResolveFailed
}
```

Now update `defaultTransport` to call it. Replace the `outcome` block from Task 1 with:

```kotlin
clientHolder.value.newCall(requestBuilder.build()).execute().use { response ->
    val location = response.header("Location")
    val contentType = response.header("Content-Type")
    val locationHost = runCatching { location?.toHttpUrlOrNull()?.host }.getOrNull()
    val outcome = classifyHttpResponse(response.code, location, contentType)
    val decision = when (outcome) {
        is ProxyResolution.Redirected -> "redirect"
        ProxyResolution.Placeholder -> "placeholder"
        ProxyResolution.NotEligible -> "not-eligible"
        ProxyResolution.ResolveFailed -> "failed"
    }
    Log.i(
        TAG,
        "RESOLVE_RESPONSE url=${sanitize(url)} status=${response.code} " +
            "protocol=${response.protocol} locationPresent=${!location.isNullOrBlank()} " +
            "locationHost=${locationHost ?: "none"} contentType=${contentType ?: "none"} " +
            "decision=$decision " +
            "forwardedHeaderKeys=${forwardedHeaderKeys.joinToString(prefix = "[", postfix = "]")}"
    )
    outcome
}
```

- [ ] **Step 4: Run the new tests**

```
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.player.CometProxyUrlResolverTest.*classifyHttpResponse*'
```
Expected: PASS.

- [ ] **Step 5: Run the full resolver test class**

```
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.player.CometProxyUrlResolverTest.*'
```
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt \
       app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt
git commit -m "feat(player): classify 200 + video/* responses as Placeholder"
```

---

## Task 3: Migrate `resolve()` / `resolveBlocking()` / `prewarm()` to return `ProxyResolution`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt`

**Goal of this task:** the public surface returns `ProxyResolution`, and ALL existing call sites are adapted. After this task, the long-cache (50min) continues to store **only** `Redirected` outcomes — `Placeholder` / `ResolveFailed` are not long-cached.

- [ ] **Step 1: Write the failing test for `resolve()` returning `Placeholder`**

Append to `CometProxyUrlResolverTest.kt`. Pattern: install a fake `Transport` that returns `Placeholder`, call `resolve()`, assert.

```kotlin
@Test
fun `resolve returns Placeholder when transport classifies as Placeholder`() = runBlocking {
    CometProxyUrlResolver.resetForTesting()
    CometProxyUrlResolver.setTransportForTesting { _, _ -> ProxyResolution.Placeholder }

    val result = CometProxyUrlResolver.resolve(
        url = "https://comet.feels.legal/playback/abc/def",
        headers = null,
        addonHost = null,
    )

    assertEquals(ProxyResolution.Placeholder, result)
    CometProxyUrlResolver.setTransportForTesting(null)
}

@Test
fun `resolve does not long-cache Placeholder verdicts`() = runBlocking {
    CometProxyUrlResolver.resetForTesting()
    var calls = 0
    CometProxyUrlResolver.setTransportForTesting { _, _ ->
        calls++
        ProxyResolution.Placeholder
    }

    val url = "https://comet.feels.legal/playback/xyz/123"
    CometProxyUrlResolver.resolve(url, null)

    // Force the short-verdict cache to be considered expired.
    CometProxyUrlResolver.setClockForTesting { System.currentTimeMillis() + 60_000L }
    CometProxyUrlResolver.resolve(url, null)

    assertEquals(2, calls)
    CometProxyUrlResolver.setTransportForTesting(null)
    CometProxyUrlResolver.setClockForTesting(null)
}

@Test
fun `resolve returns NotEligible when isCometProxy false`() = runBlocking {
    CometProxyUrlResolver.resetForTesting()
    val result = CometProxyUrlResolver.resolve(
        url = "https://random.example/somefile.mp4",
        headers = null,
        addonHost = null,
    )
    assertEquals(ProxyResolution.NotEligible, result)
}
```

- [ ] **Step 2: Run tests to verify type mismatch**

```
./gradlew :app:compileDebugUnitTestKotlin
```
Expected: type mismatch — `resolve()` returns `String?`.

- [ ] **Step 3: Migrate the public surface and the cache**

Edit `CometProxyUrlResolver.kt`.

Change `CacheEntry.resolvedUrl: String` if it's not already exactly that — leave as-is; we only cache `Redirected`. The cache map stays keyed by URL with values of type `CacheEntry`.

Replace the `resolve()` body (currently lines 158-228 area) so it returns `ProxyResolution`. The body changes are:

1. The early `if (!isCometProxy(url, addonHost)) return null` becomes `return ProxyResolution.NotEligible`.
2. The cache-hit return becomes `ProxyResolution.Redirected(entry.resolvedUrl)`.
3. The `inFlight` `CompletableDeferred<String?>` becomes `CompletableDeferred<ProxyResolution>`.
4. After `fetchLocation` returns: instead of `String?`, get a `ProxyResolution` (we'll change `fetchLocation` next). Cache only the `Redirected` variant.

Also rename `fetchLocation` to `fetchClassification` (or adapt in place). New body:

```kotlin
private suspend fun fetchClassification(
    url: String,
    headers: Map<String, String>?,
): ProxyResolution {
    val transport = transportOverride ?: defaultTransport
    return withContext(Dispatchers.IO) {
        transport.execute(url, headers)
    }
}
```

Updated `resolve()`:

```kotlin
suspend fun resolve(
    url: String,
    headers: Map<String, String>?,
    addonHost: String? = null,
): ProxyResolution {
    if (!isCometProxy(url, addonHost)) return ProxyResolution.NotEligible

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

    if (waitFor != null) return waitFor.await()

    val outcome: ProxyResolution = runCatching { fetchClassification(url, headers) }
        .onFailure { Log.w(TAG, "Resolve failed url=${sanitize(url)} error=${it.message}") }
        .getOrElse { ProxyResolution.ResolveFailed }

    synchronized(lock) {
        inFlight.remove(url)
        if (outcome is ProxyResolution.Redirected) {
            cache[url] = CacheEntry(
                resolvedUrl = outcome.url,
                storedAtMs = currentTimeMs(),
                headers = headers ?: emptyMap(),
                addonHost = addonHost,
            )
            // Existing reverse-cache wiring continues to use the redirected URL.
            reverseCache[outcome.url] = url
        }
    }

    ownDeferred.complete(outcome)
    return outcome
}
```

Update `resolveBlocking()` (around line 381) to return `ProxyResolution` (just delegate `runBlocking { resolve(...) }`).

Update `prewarm()` (around line 399) — its launched coroutine now calls the new `resolve()`. The function still returns `Job?`. No call-site change needed for prewarm itself.

Bridge note: the `inFlight` map's existing type was `MutableMap<String, CompletableDeferred<String?>>`. Change to `MutableMap<String, CompletableDeferred<ProxyResolution>>`.

- [ ] **Step 4: Adapt `StreamScreenViewModel.prepareMediaSourceUrl`**

Edit `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`. The two relevant blocks today (per `git grep`):

- The top-level `prepareMediaSourceUrl` helper (around lines 1001-1004).
- A second adapter (lines 1011-1012).

Replace the body of `prepareMediaSourceUrl` with:

```kotlin
private fun prepareMediaSourceUrl(
    url: String,
    headers: Map<String, String>?,
    addonHost: String?,
): String {
    if (!CometProxyUrlResolver.isCometProxy(url, addonHost)) return url
    val outcome = CometProxyUrlResolver.resolveBlocking(url, headers, addonHost)
    return when (outcome) {
        is ProxyResolution.Redirected -> outcome.url
        // Manual-selection path: per spec, deliberately ignore Placeholder and
        // play whatever the user picked. NotEligible / ResolveFailed fall back
        // to the original URL — same as the legacy null-handling behavior.
        ProxyResolution.Placeholder,
        ProxyResolution.NotEligible,
        ProxyResolution.ResolveFailed -> url
    }
}
```

Add the missing import:
```kotlin
import com.nexio.tv.core.player.ProxyResolution
```

- [ ] **Step 5: Adapt `PlayerRuntimeControllerStreams` callers**

Edit `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`.

The two `prepareMediaSourceUrl` invocations at lines 454 and 794 are unchanged at the call site — they still receive a `String`. The helper they call (in this file) needs the same when-mapping as Task 3 Step 4. Find the local `prepareMediaSourceUrl` definition (search for `fun prepareMediaSourceUrl` or follow the import) and apply the same `when (outcome)` mapping.

Add the import if missing:
```kotlin
import com.nexio.tv.core.player.ProxyResolution
```

- [ ] **Step 6: Adapt `AuthRecoveryInterceptor`**

Edit `app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt`.

The interceptor's reads are `proxyUrlFor`, `lastHeadersFor`, `lastAddonHostFor`, and `recoverProxyBlocking` — none of which we changed shape. Verify by `grep -n "resolve(" app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt`. If the only matches are inside `recoverProxyBlocking`, no source change is needed.

If `recoverProxyBlocking` itself calls `resolve()` internally (it does — read `CometProxyUrlResolver.kt` for its body), update its return-shape handling there. Today it returns `String?` (the resolved URL). Keep that public shape for `recoverProxyBlocking`:

```kotlin
fun recoverProxyBlocking(
    proxyUrl: String,
    headers: Map<String, String>,
    addonHost: String?,
): String? = runBlocking {
    when (val outcome = resolve(proxyUrl, headers, addonHost)) {
        is ProxyResolution.Redirected -> outcome.url
        else -> null
    }
}
```

This preserves auth-recovery semantics: `Placeholder` and other non-redirect outcomes return `null` from recovery (today's behavior for failures).

- [ ] **Step 7: Update existing resolver tests that asserted on `String?`**

In `CometProxyUrlResolverTest.kt`, scan for tests that currently call `resolve(...)` or `resolveBlocking(...)` and assert on `String?` directly. Update each:

- `assertEquals("https://cdn/x", result)` → `assertEquals(ProxyResolution.Redirected("https://cdn/x"), result)`
- `assertNull(result)` → `assertEquals(ProxyResolution.ResolveFailed, result)` (or `ProxyResolution.NotEligible` if the test's scenario is "URL fails the gate"; check the test fixture).

Re-derive each per the test's intent.

- [ ] **Step 8: Compile + run all relevant test classes**

```
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
./gradlew :app:testDebugUnitTest \
  --tests 'com.nexio.tv.core.player.CometProxyUrlResolverTest.*' \
  --tests 'com.nexio.tv.core.player.auth.AuthRecoveryInterceptorTest.*' \
  --tests 'com.nexio.tv.core.player.auth.PlaybackAuthRecoveryEndToEndTest.*' \
  --tests 'com.nexio.tv.core.player.FfmpegStreamMetadataProbeTest.*' \
  --tests 'com.nexio.tv.ui.screens.player.PlayerRuntimeControllerStreamsAuthRecoveryTest.*'
```

Expected: all PASS. Auth-recovery and FfmpegStreamMetadataProbe tests should be unaffected — they're in the touched call-site graph but not behaviorally changed.

- [ ] **Step 9: Build smoke**

```
./gradlew :app:assembleDebug
```
Expected: success.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt \
       app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt \
       app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt \
       app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt \
       app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt
git commit -m "refactor(player): migrate CometProxyUrlResolver public API to ProxyResolution"
```

---

## Task 4: Add short-verdict cache and `lastResolutionFor(url)` accessor

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt`

**Why:** the autoplay selector needs to read the verdict produced by `prewarm()` without re-issuing the HTTP request. The 50-minute long cache only stores `Redirected`; we add a 30-second verdict cache that stores all three terminal verdicts (Redirected, Placeholder, ResolveFailed) keyed by URL.

- [ ] **Step 1: Write failing tests**

Append to `CometProxyUrlResolverTest.kt`:

```kotlin
@Test
fun `lastResolutionFor returns Placeholder within short verdict TTL`() = runBlocking {
    CometProxyUrlResolver.resetForTesting()
    CometProxyUrlResolver.setTransportForTesting { _, _ -> ProxyResolution.Placeholder }

    val url = "https://comet.feels.legal/playback/abc/def"
    CometProxyUrlResolver.resolve(url, null)

    assertEquals(ProxyResolution.Placeholder, CometProxyUrlResolver.lastResolutionFor(url))
    CometProxyUrlResolver.setTransportForTesting(null)
}

@Test
fun `lastResolutionFor returns null after short verdict TTL elapses`() = runBlocking {
    CometProxyUrlResolver.resetForTesting()
    var t = 1_000_000L
    CometProxyUrlResolver.setClockForTesting { t }
    CometProxyUrlResolver.setTransportForTesting { _, _ -> ProxyResolution.Placeholder }

    val url = "https://comet.feels.legal/playback/abc/def"
    CometProxyUrlResolver.resolve(url, null)
    assertEquals(ProxyResolution.Placeholder, CometProxyUrlResolver.lastResolutionFor(url))

    t += 31_000L
    assertNull(CometProxyUrlResolver.lastResolutionFor(url))

    CometProxyUrlResolver.setTransportForTesting(null)
    CometProxyUrlResolver.setClockForTesting(null)
}

@Test
fun `lastResolutionFor returns Redirected when long cache holds it`() = runBlocking {
    CometProxyUrlResolver.resetForTesting()
    CometProxyUrlResolver.setTransportForTesting { _, _ ->
        ProxyResolution.Redirected("https://cdn.example/file.mp4")
    }
    val url = "https://comet.feels.legal/playback/abc/def"
    CometProxyUrlResolver.resolve(url, null)

    val outcome = CometProxyUrlResolver.lastResolutionFor(url)
    assertEquals(ProxyResolution.Redirected("https://cdn.example/file.mp4"), outcome)

    CometProxyUrlResolver.setTransportForTesting(null)
}

@Test
fun `lastResolutionFor returns null for unknown URL`() {
    CometProxyUrlResolver.resetForTesting()
    assertNull(CometProxyUrlResolver.lastResolutionFor("https://random.example/never-resolved"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :app:compileDebugUnitTestKotlin
```
Expected: unresolved reference `lastResolutionFor`.

- [ ] **Step 3: Implement the short-verdict cache**

Edit `CometProxyUrlResolver.kt`. Add near the top of the object:

```kotlin
private const val SHORT_VERDICT_TTL_MS = 30_000L

private data class VerdictEntry(
    val resolution: ProxyResolution,
    val storedAtMs: Long,
)

private val shortVerdictCache: MutableMap<String, VerdictEntry> = HashMap()
```

In `resolve()`, after the `inFlight.remove(url)` line and before `ownDeferred.complete(outcome)`, also write to the short-verdict cache **for every terminal outcome** (Redirected, Placeholder, ResolveFailed):

```kotlin
synchronized(lock) {
    inFlight.remove(url)
    if (outcome is ProxyResolution.Redirected) {
        cache[url] = CacheEntry(
            resolvedUrl = outcome.url,
            storedAtMs = currentTimeMs(),
            headers = headers ?: emptyMap(),
            addonHost = addonHost,
        )
        reverseCache[outcome.url] = url
    }
    shortVerdictCache[url] = VerdictEntry(outcome, currentTimeMs())
}
```

Add the public accessor inside the object:

```kotlin
/**
 * Returns the most recent terminal [ProxyResolution] observed for [url], if it
 * was produced within the last [SHORT_VERDICT_TTL_MS] milliseconds. Returns null
 * when no recent verdict exists.
 *
 * Consumers (autoplay candidate selection) read this after [prewarm] has run so
 * placeholder candidates can be filtered without firing a second HTTP probe.
 *
 * `Redirected` verdicts are also surfaced from the long-lived cache when their
 * 50-minute TTL has not yet elapsed.
 */
fun lastResolutionFor(url: String): ProxyResolution? {
    synchronized(lock) {
        val now = currentTimeMs()
        // Long cache win: surface Redirected even past the 30s short window.
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
```

Update `resetForTesting` to also clear `shortVerdictCache`:

```kotlin
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
```

- [ ] **Step 4: Run the new tests + the full resolver test class**

```
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.player.CometProxyUrlResolverTest.*'
```
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt \
       app/src/test/java/com/nexio/tv/core/player/CometProxyUrlResolverTest.kt
git commit -m "feat(player): add 30s short-verdict cache and lastResolutionFor accessor"
```

---

## Task 5: Add `skipPlaceholderStreamsEnabled` setting (default ON)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSkipPlaceholderTest.kt` (new) OR an existing PlayerSettingsDataStore test class — search first.

- [ ] **Step 1: Identify the existing test class for `PlayerSettingsDataStore`**

```
find app/src/test -name 'PlayerSettings*Test.kt' -print
```

If a `PlayerSettingsDataStoreTest.kt` exists, extend it. Otherwise create the new file.

- [ ] **Step 2: Write the failing test**

```kotlin
@Test
fun `skipPlaceholderStreamsEnabled defaults to true`() = runTest {
    val store = makeStore() // existing test factory; copy from sibling tests in this folder.
    val initial = store.settingsFlow().first().skipPlaceholderStreamsEnabled
    assertTrue(initial)
}

@Test
fun `setSkipPlaceholderStreamsEnabled persists the new value`() = runTest {
    val store = makeStore()
    store.setSkipPlaceholderStreamsEnabled(false)
    val after = store.settingsFlow().first().skipPlaceholderStreamsEnabled
    assertFalse(after)
}
```

If no `makeStore()` factory exists in the file, copy the construction pattern from any existing `PlayerSettingsDataStore` test.

- [ ] **Step 3: Run tests to verify failure**

```
./gradlew :app:compileDebugUnitTestKotlin
```
Expected: unresolved `skipPlaceholderStreamsEnabled` and `setSkipPlaceholderStreamsEnabled`.

- [ ] **Step 4: Add the field, key, read, and setter**

Edit `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`.

In the `PlayerSettings` data class (around line 230), add a field. Place it adjacent to `filterWebDolbyVisionStreamsEnabled` for cohesion:

```kotlin
val filterWebDolbyVisionStreamsEnabled: Boolean = false,
val skipPlaceholderStreamsEnabled: Boolean = true,
val filterEpisodeMismatchStreamsEnabled: Boolean = true,
```

Add the private key (place after `filterWebDolbyVisionStreamsEnabledKey` at line 531):

```kotlin
private val filterWebDolbyVisionStreamsEnabledKey = booleanPreferencesKey("filter_web_dolby_vision_streams_enabled")
private val skipPlaceholderStreamsEnabledKey = booleanPreferencesKey("skip_placeholder_streams_enabled")
private val filterEpisodeMismatchStreamsEnabledKey = booleanPreferencesKey("filter_episode_mismatch_streams_enabled")
```

In the settings-flow read block (around line 833), add the line right after `filterWebDolbyVisionStreamsEnabled`:

```kotlin
filterWebDolbyVisionStreamsEnabled = prefs[filterWebDolbyVisionStreamsEnabledKey] ?: false,
skipPlaceholderStreamsEnabled = prefs[skipPlaceholderStreamsEnabledKey] ?: true,
filterEpisodeMismatchStreamsEnabled = prefs[filterEpisodeMismatchStreamsEnabledKey] ?: true,
```

Add the setter after `setFilterWebDolbyVisionStreamsEnabled` (around line 1206):

```kotlin
suspend fun setSkipPlaceholderStreamsEnabled(enabled: Boolean) {
    store().edit { prefs ->
        prefs[skipPlaceholderStreamsEnabledKey] = enabled
    }
}
```

- [ ] **Step 5: Run the new tests**

```
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.local.*PlayerSettings*'
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt \
       app/src/test/java/com/nexio/tv/data/local/
git commit -m "feat(settings): add skipPlaceholderStreamsEnabled (default true)"
```

---

## Task 6: Inject placeholder predicate into `StreamAutoPlaySelector.candidateAutoPlayStreams`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/StreamAutoPlaySelector.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/StreamAutoPlaySelectorPlaceholderTest.kt` (new)

- [ ] **Step 1: Read `StreamAutoPlaySelector.kt` to understand current shape**

Read the file fully. Pay attention to:
- `candidateAutoPlayStreams` (line 70) — its current parameters and what it returns.
- `selectAutoPlayStream` (line 151) — how it consumes candidates.

- [ ] **Step 2: Write failing tests for the predicate behavior**

Create `app/src/test/java/com/nexio/tv/core/player/StreamAutoPlaySelectorPlaceholderTest.kt`:

```kotlin
package com.nexio.tv.core.player

import org.junit.Test
import org.junit.Assert.*

class StreamAutoPlaySelectorPlaceholderTest {

    // Reuse the existing minimal-stream builder. Open
    // `app/src/test/java/com/nexio/tv/core/player/StreamAutoPlaySelectorTest.kt`
    // and copy whichever helper or factory function it uses to build a `Stream`
    // (or whatever type `candidateAutoPlayStreams` consumes) into this file —
    // do NOT introduce a new builder shape. The two new tests below assume that
    // helper exists as `private fun stream(url: String): Stream`.

    @Test
    fun `candidateAutoPlayStreams drops streams flagged by placeholder predicate`() {
        val a = stream("https://addon.example/a")
        val b = stream("https://addon.example/b")
        val candidates = StreamAutoPlaySelector.candidateAutoPlayStreams(
            // Copy the same non-default arguments StreamAutoPlaySelectorTest
            // uses for its happy-path tests (e.g. allStreams, settings flags,
            // mode enum). The only NEW argument is `placeholderPredicate`.
            allStreams = listOf(a, b),
            placeholderPredicate = { it === a },
        )
        assertEquals(listOf(b), candidates)
    }

    @Test
    fun `candidateAutoPlayStreams keeps all when placeholder predicate is constant false`() {
        val a = stream("https://addon.example/a")
        val b = stream("https://addon.example/b")
        val candidates = StreamAutoPlaySelector.candidateAutoPlayStreams(
            allStreams = listOf(a, b),
            placeholderPredicate = { false },
        )
        assertEquals(listOf(a, b), candidates)
    }
}
```

Before writing these tests, open `app/src/test/java/com/nexio/tv/core/player/StreamAutoPlaySelectorTest.kt` and identify two things: (1) the minimal helper for building a `Stream`-typed value, and (2) the full required-parameter list for `candidateAutoPlayStreams`. Mirror both — every existing argument is passed unchanged from a sibling test; the only new argument is `placeholderPredicate`.

- [ ] **Step 3: Run tests to verify failure**

```
./gradlew :app:compileDebugUnitTestKotlin
```
Expected: unresolved `placeholderPredicate` parameter.

- [ ] **Step 4: Add `placeholderPredicate` parameter**

Edit `StreamAutoPlaySelector.kt`. Add a parameter to `candidateAutoPlayStreams` with a default that disables filtering, so existing call sites don't break:

```kotlin
fun candidateAutoPlayStreams(
    // ... existing parameters ...
    placeholderPredicate: (Stream) -> Boolean = { false },
): List<Stream> {
    // ... existing filter pipeline ...
    return existingFilteredCandidates.filterNot(placeholderPredicate)
}
```

The `filterNot(placeholderPredicate)` step **must** run before any operation that triggers ffprobe (e.g., before any `DolbyVisionAutoPlayGate.resolve` call). If the existing function doesn't itself probe (probing is done in `selectAutoPlayStream`), this is straightforward — just append the filter at the end of the existing candidate-selection pipeline.

Replace the placeholder name `Stream` with the actual type used by the existing `candidateAutoPlayStreams` signature.

- [ ] **Step 5: Run the new tests**

```
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.player.StreamAutoPlaySelectorPlaceholderTest.*'
```
Expected: PASS.

- [ ] **Step 6: Run the existing selector tests**

```
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.player.StreamAutoPlaySelectorTest.*'
```
Expected: PASS unchanged (default predicate is a no-op).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/StreamAutoPlaySelector.kt \
       app/src/test/java/com/nexio/tv/core/player/StreamAutoPlaySelectorPlaceholderTest.kt
git commit -m "feat(player): add placeholder predicate to candidateAutoPlayStreams"
```

---

## Task 7: Wire predicate from `PlayerRuntimeControllerStreams` and fire toast on placeholder-induced empty result

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`
- Test: extend `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreamsAuthRecoveryTest.kt` OR add a new sibling test if that fixture is too auth-specific.

- [ ] **Step 1: Locate the autoplay candidate-selection invocation**

In `PlayerRuntimeControllerStreams.kt` find the `candidateAutoPlayStreams(...)` call (likely near the place where `prewarm` is invoked, lines 1059-1066 area). Identify how the controller currently obtains the `PlayerSettings` flow (search for `playerSettingsDataStore` or similar dependency injected into the controller).

- [ ] **Step 2: Build the predicate and pass it into `candidateAutoPlayStreams`**

At the candidate-selection call site, read the current settings value (using whatever pattern the controller already uses — most likely `playerSettingsDataStore.settingsFlow().first()` or the cached state in the controller scope). Then build:

```kotlin
val skipEnabled = currentSettings.skipPlaceholderStreamsEnabled
val placeholderPredicate: (Stream) -> Boolean = { stream ->
    skipEnabled && CometProxyUrlResolver.lastResolutionFor(stream.url) is ProxyResolution.Placeholder
}

// Pass every argument the controller currently passes to
// candidateAutoPlayStreams (don't change them); only `placeholderPredicate`
// is new.
val filteredCandidates = StreamAutoPlaySelector.candidateAutoPlayStreams(
    /* same arguments as the existing call site, with: */
    placeholderPredicate = placeholderPredicate,
)

// To detect whether the placeholder filter actually removed any candidates
// (so the toast only fires when relevant), count predicate hits against the
// upstream stream list the controller already holds. `upstreamStreams`
// below is whichever local variable the controller currently passes as the
// `allStreams` (or equivalent) argument to candidateAutoPlayStreams.
val placeholdersDropped = upstreamStreams.count(placeholderPredicate)
```

Two paths to consider:
- If `candidateAutoPlayStreams` is called once today, change that one call to pass the predicate. Compute `placeholdersDropped` by also computing the unfiltered candidate count locally (or by tagging each `Stream` with its resolution before filtering — simpler: take the unfiltered list once with `placeholderPredicate = { false }`, take the filtered list with the real predicate, diff sizes).
- If `candidateAutoPlayStreams` is called more than once (different selection modes), update each.

Persist `placeholdersDropped` as state (a property, not a global) so the empty-result branch (line 969) can check it.

- [ ] **Step 3: Fire one-shot toast on placeholder-induced empty result**

In the picker fallback branch around line 969, add the toast:

```kotlin
if (autoplayResult == null) {
    if (placeholdersDropped > 0) {
        showToast("Autoplay could not select a stream.")
    }
    openStreamPicker()
}
```

`showToast` should match whatever toast/snackbar pattern exists in the controller (search for `Toast.makeText` or for an injected `MessagingService` / `SnackbarHostState`). Use the existing pattern — do not introduce a new toast mechanism.

- [ ] **Step 4: Write tests**

Extend `PlayerRuntimeControllerStreamsAuthRecoveryTest.kt`:

```kotlin
@Test
fun `placeholder candidates dropped before probe when toggle enabled`() = runTest {
    // Arrange: settings.skipPlaceholderStreamsEnabled = true.
    // Stub CometProxyUrlResolver.setTransportForTesting so candidate A returns
    // ProxyResolution.Placeholder and candidate B returns
    // ProxyResolution.Redirected("https://cdn/b").
    // Run candidate selection.
    // Assert: filteredCandidates contains only candidate B.
    // Assert: dolbyVisionAutoPlayGate.resolve was NEVER called for candidate A.
}

@Test
fun `placeholder filter does nothing when toggle disabled`() = runTest {
    // Same setup, settings.skipPlaceholderStreamsEnabled = false.
    // Assert: filteredCandidates == [A, B].
}

@Test
fun `auth recovery still fires for 401 even when placeholder filter active`() = runTest {
    // Pre-existing scenario; just confirm it still passes once the new code path
    // is in place. Likely no edits needed if a similar test already exists —
    // run the file to confirm.
}
```

The exact mocking is whatever the existing test fixture uses (MockK / handwritten fakes). Mirror the surrounding tests in the same file.

- [ ] **Step 5: Run tests**

```
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerRuntimeControllerStreamsAuthRecoveryTest.*'
```
Expected: PASS (new and existing).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt \
       app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreamsAuthRecoveryTest.kt
git commit -m "feat(player): wire placeholder filter and empty-result toast"
```

---

## Task 8: Add settings UI toggle

**Files:**
- Modify: existing player-settings composable (located in this task's Step 1)

- [ ] **Step 1: Locate the player-settings composable**

```
grep -rln "filterWebDolbyVisionStreamsEnabled" app/src/main/java/com/nexio/tv/ui/screens/settings/
grep -rln "filterWebDolbyVisionStreamsEnabled" app/src/main/java/com/nexio/tv/ui/
```

Open the file(s) and find the toggle row that binds to `setFilterWebDolbyVisionStreamsEnabled`. The new toggle row will live next to it.

- [ ] **Step 2: Add the toggle row**

Add a new row in the same group, mirroring the surrounding toggles:

```kotlin
SettingsToggleRow( // or whatever the existing component is called
    title = "Skip addon placeholder streams",
    subtitle = "Automatically skip streams that return an error video instead of real content.",
    checked = settings.skipPlaceholderStreamsEnabled,
    onCheckedChange = { enabled ->
        coroutineScope.launch {
            playerSettingsDataStore.setSkipPlaceholderStreamsEnabled(enabled)
        }
    },
)
```

The exact component name (`SettingsToggleRow`, `SwitchPreference`, `BooleanPreference`, etc.) is whatever the surrounding code uses. Match the pattern verbatim.

- [ ] **Step 3: Build and visually verify**

```
./gradlew :app:assembleDebug
```

Sideload the APK on a TV device (or use Android Studio Run with a TV emulator). Navigate to player settings; confirm the new toggle appears next to "Filter Web Dolby Vision streams" and persists across app restart.

This is the only manual verification step in the plan; everything else is unit-tested.

- [ ] **Step 4: Commit**

```bash
git add <the modified composable file>
git commit -m "feat(settings): add skip-placeholder-streams toggle row"
```

---

## Task 9: Integration test — placeholder + real candidate list

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/player/auth/PlaybackAuthRecoveryEndToEndTest.kt`

- [ ] **Step 1: Read the existing end-to-end test scaffolding**

Open `PlaybackAuthRecoveryEndToEndTest.kt` and identify:
- How a fake addon HTTP layer is wired (likely a MockWebServer or a fake `Transport`).
- How a candidate list is fed into the selector / controller.
- How the test asserts on the chosen stream.

- [ ] **Step 2: Add the placeholder scenario**

```kotlin
@Test
fun `autoplay skips placeholder candidate and lands on real stream`() = runTest {
    // Arrange:
    //   candidate A: addon URL responds 200 + Content-Type video/mp4 (placeholder)
    //   candidate B: addon URL responds 302 + Location https://cdn.example/b (real)
    //   skipPlaceholderStreamsEnabled = true (default)
    // Act:
    //   trigger autoplay candidate selection
    // Assert:
    //   chosen stream URL maps to candidate B's resolved CDN URL
    //   no ffprobe was invoked for candidate A
    //   no toast was fired (B succeeded — full list was not exhausted)
}

@Test
fun `autoplay shows toast and opens picker when all candidates are placeholders`() = runTest {
    // Arrange: candidates A and B both respond as placeholder.
    // Act: trigger autoplay.
    // Assert: stream picker opened, "Autoplay could not select a stream." toast fired exactly once.
}
```

Match the existing fixture's idioms; do not introduce a new mocking framework.

- [ ] **Step 3: Run the test class**

```
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.player.auth.PlaybackAuthRecoveryEndToEndTest.*'
```
Expected: PASS.

- [ ] **Step 4: Run the entire `:app:testDebugUnitTest` task as a regression sweep**

```
./gradlew :app:testDebugUnitTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/player/auth/PlaybackAuthRecoveryEndToEndTest.kt
git commit -m "test(player): end-to-end placeholder skip and exhaustion scenarios"
```

---

## Task 10: Manual device verification + final cleanup

**Files:** none — this is a verification gate.

- [ ] **Step 1: Install on device**

```
./gradlew :app:installDebug
```

- [ ] **Step 2: Verify against a placeholder-returning addon**

Use a Stremio addon known to return placeholder MP4s on auth failure (or set up a test addon endpoint that returns `200 + Content-Type: video/mp4` from the addon's own host). With `skipPlaceholderStreamsEnabled = true`:

- Trigger autoplay on a title that has at least one placeholder candidate AND one real candidate. Confirm playback starts on the real candidate, not the placeholder.
- Trigger autoplay on a title where ALL candidates are placeholders. Confirm picker opens and toast "Autoplay could not select a stream." appears.
- Toggle the setting OFF, repeat the first scenario. Confirm the placeholder MP4 plays (proves the toggle is the only gate).
- Manually tap a placeholder stream from the picker. Confirm it plays (manual selection is unaffected).

- [ ] **Step 3: Latency sanity check**

Measure autoplay-to-first-frame on a real `302 → CDN` stream against `main`. Confirm no measurable regression (the resolver does the same single redirect-disabled GET it did before).

- [ ] **Step 4: Open PR**

```
git push -u origin feat/skip-placeholder-streams
gh pr create --title "feat(player): auto-skip placeholder error videos from Stremio addons" --body "$(cat <<'EOF'
## Summary
- Extends `CometProxyUrlResolver` to classify the response it already issues into `Redirected | Placeholder | NotEligible | ResolveFailed`. No new HTTP traffic, no probe changes.
- Autoplay candidate selection drops `Placeholder` candidates before any ffprobe runs.
- New setting `skipPlaceholderStreamsEnabled` (default ON) gates the behavior.
- Empty-result branch surfaces toast "Autoplay could not select a stream." Manual stream selection is unaffected.

## Spec
docs/superpowers/specs/2026-04-26-skip-placeholder-streams-design.md

## Test plan
- [ ] `./gradlew :app:testDebugUnitTest` passes locally
- [ ] Real `302 → CDN` stream plays (latency unchanged)
- [ ] Mixed candidate list — autoplay lands on real, skips placeholder
- [ ] All-placeholder list — picker opens with toast
- [ ] Toggle OFF — placeholder plays
- [ ] Manual selection — placeholder plays as picked
EOF
)"
```

---

## Execution notes for the implementing engineer

- **Order matters.** Tasks 1-4 are pure infrastructure on the resolver. Task 5 is independent; Tasks 6-9 depend on Tasks 1-5 being merged.
- **Cache invariants.** The 50-minute long cache stores **only** `Redirected`. The 30-second short-verdict cache stores all terminal verdicts (Redirected, Placeholder, ResolveFailed). `lastResolutionFor` consults the long cache first, then the short cache.
- **Probe cost.** Do not modify `FfmpegStreamMetadataProbe` or its ffprobe selectors. Audio-track presence is intentionally not surfaced.
- **Manual selection.** Do not extend placeholder filtering to `prepareMediaSourceUrl`'s direct-playback path. Per spec Q1, manual taps play whatever the user picked.
- **Telemetry.** The `RESOLVE_RESPONSE` log line's `decision=` field is now one of `redirect | placeholder | failed`. The legacy value `null-no-redirect` is deliberately retired.
