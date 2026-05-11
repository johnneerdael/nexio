# YouTube Extractor — Duktape + nsig/sigcipher Port

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port `YouTubeExtractor`'s Duktape-based JavaScript execution layer + n-signature descrambler + signatureCipher decryptor into our extractor so direct `googlevideo.com` URLs can be used without YouTube's throttle and signature-cipher protections blocking us. Validates whether this alone (no poToken) is sufficient for trailer playback at high quality before committing to the much larger WebView + BotGuard poToken work.

**Architecture:** YouTube wraps direct streaming URLs with two obfuscation layers separate from poToken: (1) a `signatureCipher` field that contains an encrypted signature requiring decryption via routines in the player JS, and (2) an `n=` URL parameter that, if not descrambled via a separate player-JS function, causes the CDN to throttle the download to ~100 kbps. NewPipeExtractor handles both via its own JS interpreter; the local `YouTubeExtractor` library (~/Scripts/YouTubeExtractor) is pure Kotlin and uses Duktape (Squareup's embedded JS engine) on Android via a ~12-line `captureReturnFromEval` actual. We port that library's three core classes (`JsNsigExtractor`, `JsSignatureExtractor`, `Decryptor`) plus its `FunctionManager` (cross-extraction cache for parsed JS function code) plus the Duktape `captureReturnFromEval` actual. We then fetch the YouTube watch page's player JS once per session, instantiate a `Decryptor`, and apply it to every adaptive stream we extract — both decrypting `signatureCipher` into a real URL and replacing the `n=` parameter with its descrambled value. The Decryptor lives alongside our existing `InAppYouTubeExtractor`; the extraction loop changes to prefer adaptive split video+audio (ANDROID client, since Android-signed adaptive URLs don't require poToken IF the throttle is bypassed via nsig descrambling). iOS HLS remains as the fallback.

**Tech Stack:** Kotlin coroutines (the upstream library is pure-Kotlin, no RxJava — port is mostly copy-paste with package renames), Duktape via `com.squareup.duktape:duktape-android` (verify artifact still resolves; fall back to a maintained fork if 404), Ktor URL utilities (already in our dep tree via Media3's transitive `okhttp`), our existing `fetchTransport` for the player-JS fetch. No new long-term dependency beyond Duktape.

---

## Why this approach (rationale)

The poToken WebView path (NewPipe's solution) is real but heavy: ~600 LoC of WebView orchestration, a runtime dependency on Google's BotGuard challenge JS (which has broken 2–3 times per year), and ~30MB resident memory while the hidden WebView is alive.

The `YouTubeExtractor` library uses a different, **complementary** approach: solve the JS-side problems (signatureCipher decryption + nsig descrambling) with a lightweight embedded JS engine (Duktape, ~200KB native binary). This addresses two of the three failure modes we've been chasing:

1. **`signatureCipher` field on adaptive streams** — when present, the `url` field is empty and the real URL must be reconstructed by decrypting the `s=` value through the player JS's signature transformation function. We don't currently do this; URLs with `signatureCipher` instead of `url` are silently dropped in our extraction loop.

2. **`n=` URL throttle parameter** — every `/videoplayback?...&n=XXX` URL must have `XXX` replaced with the descrambled value or YouTube's CDN throttles the response to ~100 kbps. We don't currently descramble. This is likely the root cause of the "low resolution / 240p" symptom even when video DOES play — the player simply can't pull enough bandwidth to upgrade variants.

3. **poToken anti-abuse token** — separate problem, not addressed by this plan. **Deferred to a follow-up.**

The empirical hypothesis: for public unauthenticated trailers (our use case), poToken enforcement is loose enough that nsig descrambling + signatureCipher decryption is sufficient. yt-dlp's documented behavior supports this — many public videos work without poToken once nsig is handled. The user explicitly approved this incremental approach over the heavier poToken-first plan.

**Decision gate at end of plan:** if on-device smoke shows reliable, full-quality, non-throttled playback for the four canonical trailers (Project Hail Mary, Citadel, Ready or Not 2, The Drama), we ship and never need poToken. If we still see 403s or throttling, we layer the WebView+BotGuard work from the earlier `2026-05-11-youtube-potoken-provider.md` plan on top.

---

## File Structure

**New files (port from `~/Scripts/YouTubeExtractor`):**

| Our path | Source path in YouTubeExtractor | Lines | Adaptations |
|---|---|---|---|
| `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsEvaluator.kt` | `commonMain/di/captureReturnFromEval.kt` + `androidMain/di/captureReturnFromEval.android.kt` | ~20 | Collapsed into a single Android file (no KMP `expect`/`actual` needed). `withContext(Dispatchers.IO)` retained. |
| `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/FunctionManager.kt` | `commonMain/core/FunctionManager.kt` | ~40 | Singleton object → `@Singleton class` with Hilt-friendly state. Caches parsed JS function code across extractions in a session. |
| `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsUtils.kt` | `commonMain/core/JsUtils.kt` | ~30 | Verbatim copy — pure-Kotlin string helpers. |
| `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsNsigExtractor.kt` | `commonMain/utils/JsNsigExtractor.kt` | ~95 | Verbatim copy. The class is constructed with the player JS source code as a String. |
| `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsSignatureExtractor.kt` | `commonMain/utils/JsSignatureExtractor.kt` | ~90 | Verbatim copy. Same constructor shape. |
| `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/Decryptor.kt` | `commonMain/utils/Decryptor.kt` | ~160 | Replace `io.ktor.http.URLBuilder` / `Url` with `android.net.Uri` (already used in our codebase) so we don't pull in Ktor. Otherwise verbatim. |
| `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/PlayerJsFetcher.kt` | derived from `commonMain/utils/DataExtractor.extractPlayerUrl` | ~50 | NEW (small): given an iframe `embed`-style watch page HTML, extract the `player_ias.vflset/.../base.js` URL, fetch its source, return the JS as a String. Uses our existing `fetchTransport` for HTTP. |

**Modified files (wire-in):**

| Path | What changes |
|---|---|
| `app/build.gradle.kts` (or version catalog) | Add `com.squareup.duktape:duktape-android:1.4.0` dependency. Verify it still resolves from Maven Central; if not, swap to a maintained fork (the only candidate in active use as of writing is `app.cash.zipline:zipline` which requires API changes — see Task 1). |
| `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt` | Fetch the player JS once per `extractPlaybackSourceInternal` call (in parallel with the player API calls). After both player responses arrive, if any `formats[]` or `adaptiveFormats[]` entries have `signatureCipher` instead of `url`, run them through `Decryptor.getStreamingUrl(...)`. For every direct `videoplayback` URL in `bestProgressive` / `bestVideo` / `bestAudio`, descramble the `n=` parameter via `Decryptor.decryptedNSignature(...)`. |
| `app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt` | Update `selectPreferredTrailerPlaybackSource` to prefer split adaptive video+audio when the URLs have already been nsig-descrambled (signalled via a new optional `nsigDescrambled: Boolean = false` field on `TrailerPlaybackSource` — or simpler, by simply preferring split adaptive again on the assumption it'll now work). Keep iOS HLS as fallback. |
| `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt` | No changes needed if nsig is descrambled at extraction time. The existing `ResolvingDataSource` per-host header dispatch remains. |

**Test files:**

| Path | Coverage |
|---|---|
| `app/src/test/java/com/nexio/tv/data/trailer/jsdecrypt/JsEvaluatorTest.kt` | Sanity: evaluate `(function(){ return 1+1 })()` → returns `2`. Verifies Duktape is wired in correctly. |
| `app/src/test/java/com/nexio/tv/data/trailer/jsdecrypt/JsNsigExtractorTest.kt` | Pure-Kotlin regex tests against a captured player JS sample: extract a known n-function name, extract its code body, verify the descramble produces a sample output. |

---

## Scope Check

One cohesive subsystem: JS-decrypt layer. No splits needed.

**Out of scope (deferred):**
- **poToken** — separate plan `2026-05-11-youtube-potoken-provider.md`. We ship and verify this plan first.
- **Updating nsig/sigcipher regexes** — `YouTubeExtractor` is "outdated" per the user; if the smoke test reveals the regexes no longer match YouTube's current minified player JS, the fix is to lift the **current** regexes from NewPipeExtractor's `YoutubeJavaScriptPlayerManager` and `YoutubeSignatureUtils`. Tracked in Task 10 as a fallback step but not a mandatory part of the plan.
- **WEB client support** — the WEB client returns `signatureCipher` URLs that need the most decryption work. We stay on ANDROID + IOS clients (which have direct `url` fields for most variants; signatureCipher is rarer there) for this plan. WEB client adoption can come with poToken if we end up needing it.

---

## Task 1: Add Duktape dependency

`YouTubeExtractor` uses `com.squareup.duktape:duktape-android:1.4.0` (Square stopped maintaining in 2020 but the artifact still resolves on Maven Central). It's a thin JNI wrapper over the Duktape C library, ~200KB native binary. If the artifact has been pulled or no longer resolves, fall back to `com.shaman.duktape:duktape-android` (a community fork) or `app.cash.zipline:zipline` (Square's modern successor using QuickJS — different API, only use if Duktape is truly unavailable).

**Files:**
- Modify: `app/build.gradle.kts` (or `gradle/libs.versions.toml` if your project uses a version catalog)

- [ ] **Step 1: Locate the dependency declaration site**

Run: `grep -nE "implementation\(libs|implementation\(\"" app/build.gradle.kts | head -10`
Run: `cat gradle/libs.versions.toml 2>/dev/null | head -30`

Identify whether the project uses a version catalog (likely) or inline `implementation("group:artifact:version")` strings.

- [ ] **Step 2: Add the dependency**

If the project uses a version catalog (`gradle/libs.versions.toml`), append to `[versions]`:

```toml
duktape = "1.4.0"
```

And to `[libraries]`:

```toml
duktape-android = { module = "com.squareup.duktape:duktape-android", version.ref = "duktape" }
```

Then in `app/build.gradle.kts`, add to the `dependencies { ... }` block:

```kotlin
implementation(libs.duktape.android)
```

If the project does NOT use a version catalog, add directly to `app/build.gradle.kts`:

```kotlin
implementation("com.squareup.duktape:duktape-android:1.4.0")
```

- [ ] **Step 3: Verify the dependency resolves**

Run: `./gradlew :app:dependencies --configuration universalDebugRuntimeClasspath --console=plain 2>&1 | grep -i duktape | head`
Expected: a line like `+--- com.squareup.duktape:duktape-android:1.4.0`.

If the dependency fails to resolve, swap to `com.shaman.duktape:duktape-android:2.1.0` (community fork, same API).

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts gradle/libs.versions.toml 2>/dev/null
git commit -m "build(trailer): add Duktape for in-process JS evaluation

com.squareup.duktape:duktape-android:1.4.0. ~200KB native binary, runs
Duktape (the embedded C JS engine) via JNI. Used to descramble
YouTube's signatureCipher and n= URL throttle parameter without
needing a WebView. Square no longer maintains the artifact but it
still resolves from Maven Central and the API surface (Duktape.create,
.evaluate, .close) hasn't changed."
```

---

## Task 2: Port `JsEvaluator.kt` and unit-test it

The leaf of the dependency graph. Wraps Duktape's `create()` / `evaluate()` / `close()` in a coroutine-friendly `suspend fun`. Direct port of NewPipe-shaped `captureReturnFromEval.android.kt` but collapsed into one file (no KMP `expect`/`actual` ceremony — we're Android-only).

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsEvaluator.kt`
- Create: `app/src/test/java/com/nexio/tv/data/trailer/jsdecrypt/JsEvaluatorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/trailer/jsdecrypt/JsEvaluatorTest.kt`:

```kotlin
package com.nexio.tv.data.trailer.jsdecrypt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class JsEvaluatorTest {

    @Test
    fun `evaluates simple expression`() = runBlocking {
        val result = captureReturnFromEval("(function(){ return 1 + 1; })()")
        assertEquals("2", result?.toString())
    }

    @Test
    fun `evaluates string return`() = runBlocking {
        val result = captureReturnFromEval("(function(){ return 'hello'; })()")
        assertEquals("hello", result?.toString())
    }

    @Test
    fun `returns null for void expression`() = runBlocking {
        val result = captureReturnFromEval("(function(){ var x = 1; })()")
        // Duktape returns null for undefined return
        assertEquals(null, result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.jsdecrypt.JsEvaluatorTest" --console=plain 2>&1 | tail -8`
Expected: `Unresolved reference: captureReturnFromEval` — file doesn't exist yet.

- [ ] **Step 3: Implement `JsEvaluator.kt`**

Create `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsEvaluator.kt`:

```kotlin
package com.nexio.tv.data.trailer.jsdecrypt

import com.squareup.duktape.Duktape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Evaluates a self-contained JavaScript expression on a fresh Duktape VM
 * instance, returning the value. The Duktape VM is created and disposed
 * around each call — this is fine for our usage (a few calls per trailer
 * extraction). Caching the VM across calls would be a follow-up
 * optimization if profiling shows it's hot.
 *
 * Port of YouTubeExtractor's `captureReturnFromEval.android.kt`.
 */
suspend fun captureReturnFromEval(formattedFunction: String): Any? =
    withContext(Dispatchers.IO) {
        val duktape = Duktape.create()
        try {
            duktape.evaluate(formattedFunction)
        } finally {
            duktape.close()
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.jsdecrypt.JsEvaluatorTest" --console=plain 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL` with 3 passing tests.

Note: these tests load Duktape's JNI library. If the test runs in a pure-JVM environment without the Android Duktape native lib available, the test may fail with `UnsatisfiedLinkError`. In that case mark the test with `@Ignore` and verify in instrumentation tests instead — but typically Duktape's JNI lib is on the test classpath for Android Studio's JVM unit tests because the dependency is universal-debug.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsEvaluator.kt \
        app/src/test/java/com/nexio/tv/data/trailer/jsdecrypt/JsEvaluatorTest.kt
git commit -m "feat(jsdecrypt): Duktape-backed JS evaluator

Single suspend fun captureReturnFromEval(jsString): Any? wrapping a
short-lived Duktape VM. Runs on Dispatchers.IO. This is the entry
point for all subsequent JS-side YouTube extraction work (n-signature
descrambling, signatureCipher decryption). Verified with simple
expression / string / undefined tests."
```

---

## Task 3: Port `JsUtils.kt` and `FunctionManager.kt`

Pure-Kotlin support code. `JsUtils.kt` is string helpers (regex constants used by extractors). `FunctionManager` is an in-memory cache for parsed JS functions so re-extractions in the same session don't re-parse the player JS — a real perf win since parsing is the expensive part.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsUtils.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/FunctionManager.kt`

- [ ] **Step 1: Inspect the upstream `JsUtils.kt`**

Run: `cat /Users/jneerdael/Scripts/YouTubeExtractor/shared/src/commonMain/kotlin/com/ar/youtubeextractor/core/JsUtils.kt`
Read its contents. Confirm it's pure-Kotlin (no Ktor, no platform deps). Copy verbatim.

- [ ] **Step 2: Create `JsUtils.kt` (verbatim copy with package rename)**

Open the file from Step 1 above and copy its body. Wrap in our package. Example shape (your copy may differ; use the upstream source verbatim):

```kotlin
package com.nexio.tv.data.trailer.jsdecrypt

// <<< paste upstream JsUtils.kt body here, change only the package line >>>
```

- [ ] **Step 3: Inspect the upstream `FunctionManager.kt`**

Run: `cat /Users/jneerdael/Scripts/YouTubeExtractor/shared/src/commonMain/kotlin/com/ar/youtubeextractor/core/FunctionManager.kt`

The upstream is an `object` (Kotlin singleton). To keep our Hilt graph clean we wrap as a `@Singleton class` with `@Inject constructor()`. Otherwise verbatim.

- [ ] **Step 4: Create `FunctionManager.kt`**

```kotlin
package com.nexio.tv.data.trailer.jsdecrypt

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-session cache for parsed JS function bodies. Re-extracting a
 * second trailer in the same session reuses these — significant perf
 * win because re-parsing the entire YouTube player JS for every trailer
 * is the dominant cost in this pipeline.
 *
 * Cleared automatically when the player-JS source changes (we detect
 * this by hashing the JS in [setPlayerJs] — different hash means
 * YouTube rotated their player, invalidate everything).
 *
 * Port of YouTubeExtractor's FunctionManager.kt — was a Kotlin `object`,
 * promoted to a @Singleton class so Hilt can inject it.
 */
@Singleton
class FunctionManager @Inject constructor() {

    private var playerJsHash: Int? = null
    private var mainFunName: String? = null
    private var signatureFunctionCode: String? = null
    private var nFunctionCode: Pair<String, String>? = null

    /** Called at the start of an extraction with the current player JS. Invalidates all caches if the JS changed. */
    @Synchronized
    fun setPlayerJs(jsCode: String) {
        val hash = jsCode.hashCode()
        if (hash != playerJsHash) {
            playerJsHash = hash
            mainFunName = null
            signatureFunctionCode = null
            nFunctionCode = null
        }
    }

    @Synchronized fun getMainFunName(): String? = mainFunName
    @Synchronized fun updateMainFunName(name: String?) { mainFunName = name }

    @Synchronized fun getSignatureFunctionCode(): String? = signatureFunctionCode
    @Synchronized fun updateSignatureFunctionCode(code: String?) { signatureFunctionCode = code }

    @Synchronized fun getNFunctionCode(): Pair<String, String>? = nFunctionCode
    @Synchronized fun updateNFunctionCode(code: Pair<String, String>?) { nFunctionCode = code }
}
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsUtils.kt \
        app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/FunctionManager.kt
git commit -m "feat(jsdecrypt): JS string helpers + parsed-function cache

JsUtils.kt is verbatim from YouTubeExtractor — pure-Kotlin regex
helpers used by the nsig and signature extractors. FunctionManager
is the upstream singleton promoted to a @Singleton class so Hilt can
inject it; caches parsed JS function code keyed on the player-JS
hash so a session's second trailer skips the heavy parse step."
```

---

## Task 4: Port `JsNsigExtractor.kt` and `JsSignatureExtractor.kt`

These two classes own the regex extraction of the n-function and signature-cipher transformation functions out of the player JS. **They are the most likely files to need maintenance** because YouTube changes their minified JS structure periodically and the regexes are brittle. If a smoke test fails because nsig isn't matching, lift the current regexes from NewPipeExtractor's `YoutubeJavaScriptPlayerManager`.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsNsigExtractor.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsSignatureExtractor.kt`

- [ ] **Step 1: Inspect both upstream files**

Run: `cat /Users/jneerdael/Scripts/YouTubeExtractor/shared/src/commonMain/kotlin/com/ar/youtubeextractor/utils/JsNsigExtractor.kt`
Run: `cat /Users/jneerdael/Scripts/YouTubeExtractor/shared/src/commonMain/kotlin/com/ar/youtubeextractor/utils/JsSignatureExtractor.kt`

Read each carefully. Note any Ktor imports or KMP-specific code that needs swapping.

- [ ] **Step 2: Create `JsNsigExtractor.kt`**

Copy the upstream `JsNsigExtractor.kt` body verbatim into the new package. Replace the import of `com.ar.youtubeextractor.di.captureReturnFromEval` with `com.nexio.tv.data.trailer.jsdecrypt.captureReturnFromEval` (our package).

```kotlin
package com.nexio.tv.data.trailer.jsdecrypt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JsNsigExtractor(private val jsCode: String) {

    // <<< Body verbatim from /Users/jneerdael/Scripts/YouTubeExtractor/
    //     shared/src/commonMain/kotlin/com/ar/youtubeextractor/utils/JsNsigExtractor.kt
    //     Replace any reference to com.ar.youtubeextractor.di.captureReturnFromEval
    //     with the local function defined in JsEvaluator.kt (same package). >>>
}
```

If the upstream file imports `kotlinx.coroutines.IO`, that's a no-op import (an alias for `Dispatchers.IO`); keep or drop as the upstream does.

- [ ] **Step 3: Create `JsSignatureExtractor.kt`**

Same procedure as Step 2 — copy verbatim from `/Users/jneerdael/Scripts/YouTubeExtractor/shared/src/commonMain/kotlin/com/ar/youtubeextractor/utils/JsSignatureExtractor.kt`, change the package and the `captureReturnFromEval` import.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsNsigExtractor.kt \
        app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/JsSignatureExtractor.kt
git commit -m "feat(jsdecrypt): n-signature + signatureCipher extractors

Verbatim port of YouTubeExtractor's JsNsigExtractor.kt and
JsSignatureExtractor.kt. The first extracts the n= URL throttle
function from YouTube's player JS and applies it via Duktape; without
this descrambling step the CDN throttles direct stream downloads to
~100 kbps. The second extracts the cipher transformation function and
applies it to decrypt signatureCipher fields on adaptive formats."
```

---

## Task 5: Port `Decryptor.kt` with Ktor → Uri rewrite

`Decryptor.kt` is the orchestrator. Its `getStreamingUrl(signatureCipher: String)` parses a `signatureCipher` string, decrypts the signature via JsSignatureExtractor, descrambles the `n=` via JsNsigExtractor, and rebuilds the URL. The upstream uses Ktor's `URLBuilder` / `Url` types; we replace those with `android.net.Uri` (already in our codebase) so we don't pull in Ktor.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/Decryptor.kt`

- [ ] **Step 1: Inspect the upstream `Decryptor.kt`**

Run: `cat /Users/jneerdael/Scripts/YouTubeExtractor/shared/src/commonMain/kotlin/com/ar/youtubeextractor/utils/Decryptor.kt`

Note specifically:
- Imports: `io.ktor.http.URLBuilder`, `io.ktor.http.Url`, `io.ktor.http.decodeURLQueryComponent`, `io.ktor.http.encodeURLParameter`, `io.ktor.util.flattenEntries`.
- Two methods on our public API: `decryptVideoData(videoData: VideoData)` and `getStreamingUrl(signatureCipher: String): String?`.

`decryptVideoData` operates on the library's `VideoData` model class which we don't have — we don't need that method since our extractor has its own model. Drop it.

`getStreamingUrl(signatureCipher)` is the method we actually use. Its Ktor usage is shallow: parse a URL, get its query params, mutate, rebuild. `android.net.Uri` and `Uri.Builder` cover every operation.

- [ ] **Step 2: Add public methods for the two operations we need**

In addition to `getStreamingUrl` (which is the signatureCipher decryptor), we also need to expose `descrambleNsig(url: String): String?` — applies the n-descrambler to an existing URL's `n=` param without going through the full signatureCipher path. The upstream's `decryptedNSignature(...)` is private; we promote it.

- [ ] **Step 3: Create `Decryptor.kt`**

```kotlin
package com.nexio.tv.data.trailer.jsdecrypt

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Decryptor(
    private val jsCode: String,
    private val functionManager: FunctionManager
) {
    private val jsSignatureExtractor = JsSignatureExtractor(jsCode)
    private val jsNsigExtractor = JsNsigExtractor(jsCode)

    init {
        functionManager.setPlayerJs(jsCode)
    }

    /**
     * Decrypts a `signatureCipher` field (encoded as a URL-encoded
     * key=value string typically containing `s=...&sp=sig&url=...`)
     * into a directly-fetchable streaming URL with both the signature
     * decrypted AND the n= throttle parameter descrambled.
     */
    suspend fun getStreamingUrl(signatureCipher: String): String? = withContext(Dispatchers.IO) {
        try {
            val regexUrlExtractor = "(\\w+)=([^&]+)".toRegex()
            val pairs = regexUrlExtractor.findAll(signatureCipher)
                .associate { it.groupValues[1] to it.groupValues[2] }

            val encodedUrl = pairs["url"] ?: return@withContext null
            val decodedUrl = Uri.decode(encodedUrl)
            val parsedUri = Uri.parse(decodedUrl)

            val queryParams = parsedUri.queryParameterNames
                .associateWith { parsedUri.getQueryParameter(it).orEmpty() }
                .toMutableMap()

            val encryptedSig = pairs["s"]?.replace("%3D", "=") ?: return@withContext null
            val nSignature = queryParams["n"] ?: return@withContext null

            val decryptedSignature = decryptSignature(encryptedSig) ?: return@withContext null
            val decryptedNSig = decryptedNSignature(nSignature) ?: return@withContext null
            val queryType = pairs["sp"] ?: return@withContext null

            queryParams[queryType] = decryptedSignature
            queryParams["n"] = decryptedNSig

            // Rebuild URL with mutated query params, preserving order
            val rebuilt = Uri.Builder()
                .scheme(parsedUri.scheme)
                .authority(parsedUri.authority)
                .path(parsedUri.path)
            for ((k, v) in queryParams) {
                rebuilt.appendQueryParameter(k, v)
            }
            rebuilt.build().toString()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Applies just the n-descrambler to an existing URL's `n=` parameter.
     * Use this for direct adaptive URLs that have a real `url` field and
     * just need the throttle removed.
     */
    suspend fun descrambleNsig(url: String): String? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(url)
        val nSignature = uri.getQueryParameter("n") ?: return@withContext url
        val descrambled = decryptedNSignature(nSignature) ?: return@withContext null

        val rebuilt = Uri.Builder()
            .scheme(uri.scheme)
            .authority(uri.authority)
            .path(uri.path)
        for (key in uri.queryParameterNames) {
            val value = if (key == "n") descrambled else uri.getQueryParameter(key).orEmpty()
            rebuilt.appendQueryParameter(key, value)
        }
        rebuilt.build().toString()
    }

    private suspend fun decryptedNSignature(nSignature: String): String? {
        functionManager.getNFunctionCode()?.let {
            return jsNsigExtractor.nFunctionResponse(it, nSignature)
        }
        val nFunctionName = jsNsigExtractor.extractNFunctionName() ?: return null
        val functionCode = jsNsigExtractor.extractNFunctionCode(nFunctionName)
        functionManager.updateNFunctionCode(functionCode)
        return jsNsigExtractor.nFunctionResponse(functionCode, nSignature)
    }

    private suspend fun decryptSignature(encryptedSig: String): String? {
        val funcName = functionManager.getMainFunName() ?: run {
            // First call this session: extract the function name from the JS
            jsSignatureExtractor.extractMainFunctionName()?.also {
                functionManager.updateMainFunName(it)
            } ?: return null
        }
        functionManager.getSignatureFunctionCode()?.let {
            return jsSignatureExtractor.signatureFunctionResponse(it, encryptedSig)
        }
        val sigFunctionCode = jsSignatureExtractor.extractJSFunctionCode(funcName)
        functionManager.updateSignatureFunctionCode(sigFunctionCode)
        return jsSignatureExtractor.signatureFunctionResponse(sigFunctionCode, encryptedSig)
    }
}
```

Note: the upstream `Decryptor.decryptSignature` requires `FunctionManager.getMainFunName()` to already be populated — it's set elsewhere in the upstream library's `DataExtractor`. In our port we extract it lazily on first call (the `?: run { ... }` block above). This requires `JsSignatureExtractor` to expose `extractMainFunctionName(): String?` — if the upstream class doesn't have that method publicly, lift its private regex into the call site here.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`. If `extractMainFunctionName` is missing from `JsSignatureExtractor`, either expose it (preferable) or inline the regex here.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/Decryptor.kt
git commit -m "feat(jsdecrypt): URL decryptor orchestrator (Ktor → android.net.Uri)

Port of YouTubeExtractor's Decryptor.kt with two changes: (1) Ktor's
URLBuilder / Url types swapped for android.net.Uri to avoid pulling
Ktor into our trailer pipeline; (2) two public methods exposed —
getStreamingUrl(signatureCipher) handles the full WEB-style
signatureCipher path, descrambleNsig(url) applies just the n=
throttle descrambling to URLs that already have a real `url` field
(the common ANDROID/IOS adaptive case). Uses FunctionManager to cache
parsed JS across calls in the same session."
```

---

## Task 6: Create `PlayerJsFetcher.kt` for retrieving the YouTube player JS

The decryptor needs YouTube's player JS source code as a String. The URL is in the watch-page HTML as `<script src="/s/player/<hash>/.../base.js">`. Fetching and caching is straightforward — but the URL changes whenever YouTube rotates their player (every few weeks), so the cache must be keyed on the URL.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/PlayerJsFetcher.kt`

- [ ] **Step 1: Implement `PlayerJsFetcher.kt`**

```kotlin
package com.nexio.tv.data.trailer.jsdecrypt

import com.nexio.tv.data.trailer.YOUTUBE_STABLE_WEB_USER_AGENT
import com.nexio.tv.data.trailer.buildYouTubeWireProperties
import com.nexio.tv.data.trailer.YouTubeWireProfile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class PlayerJsFetcher @Inject constructor() {

    private val cache = mutableMapOf<String, String>()

    /**
     * Extracts the `player_ias.vflset/.../base.js` URL from a YouTube
     * watch-page HTML. The script tag is always early in the HTML head;
     * we use a forgiving regex.
     */
    fun extractPlayerJsUrl(watchPageHtml: String): String? {
        // Multiple patterns observed across YouTube revisions; try each.
        val patterns = listOf(
            Regex("""\\"jsUrl\\":\\"(/s/player/[^"]+/base\.js)\\"""""),
            Regex("""<script\s+src="(/s/player/[^"]+/base\.js)""""),
            Regex(""""PLAYER_JS_URL":"(/s/player/[^"]+/base\.js)"""")
        )
        for (pattern in patterns) {
            pattern.find(watchPageHtml)?.let { m ->
                val raw = m.groupValues[1].replace("\\/", "/")
                return "https://www.youtube.com$raw"
            }
        }
        return null
    }

    /**
     * Fetches the player JS source. Cached by URL — within a session
     * the same player JS is reused across multiple trailer extractions.
     */
    suspend fun fetchPlayerJs(playerJsUrl: String): String? = withContext(Dispatchers.IO) {
        cache[playerJsUrl]?.let { return@withContext it }

        try {
            val conn = (URL(playerJsUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", YOUTUBE_STABLE_WEB_USER_AGENT)
                setRequestProperty("Referer", "https://www.youtube.com/")
                setRequestProperty("Accept", "*/*")
            }
            if (conn.responseCode != 200) {
                return@withContext null
            }
            val js = conn.inputStream.bufferedReader().use { it.readText() }
            cache[playerJsUrl] = js
            js
        } catch (e: IOException) {
            null
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/jsdecrypt/PlayerJsFetcher.kt
git commit -m "feat(jsdecrypt): player JS URL extractor + fetcher

Extracts the /s/player/<hash>/.../base.js URL from YouTube watch-page
HTML using three forgiving regexes covering known formats. Fetches the
JS once per URL and caches in-memory — within a session the same
player JS is reused across many trailer extractions. The cache key is
the URL itself, so a player rotation by YouTube naturally invalidates."
```

---

## Task 7: Wire the JS-decrypt pipeline into `InAppYouTubeExtractor`

The main integration point. In `extractPlaybackSourceInternal`, fetch the player JS in parallel with the player API calls. After both arrive, instantiate a `Decryptor` and run it over:

1. Every `formats[]` or `adaptiveFormats[]` entry that has a `signatureCipher` instead of a real `url`.
2. Every direct `googlevideo.com/videoplayback` URL with an `n=` param — descramble it in-place.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`

- [ ] **Step 1: Inject `PlayerJsFetcher` and `FunctionManager` into the extractor**

Modify the class declaration. Existing:

```kotlin
@Singleton
class InAppYouTubeExtractor @Inject constructor(
    private val integrationProvider: YouTubeTrailerIntegrationProvider,
    @ApplicationContext private val applicationContext: Context
)
```

Change to:

```kotlin
@Singleton
class InAppYouTubeExtractor @Inject constructor(
    private val integrationProvider: YouTubeTrailerIntegrationProvider,
    @ApplicationContext private val applicationContext: Context,
    private val playerJsFetcher: PlayerJsFetcher,
    private val functionManager: FunctionManager
)
```

Add imports:

```kotlin
import com.nexio.tv.data.trailer.jsdecrypt.Decryptor
import com.nexio.tv.data.trailer.jsdecrypt.FunctionManager
import com.nexio.tv.data.trailer.jsdecrypt.PlayerJsFetcher
```

- [ ] **Step 2: Fetch player JS in parallel with the player API calls**

In `extractPlaybackSourceInternal`, locate the start of the function (after `watchConfig` extraction). Add:

```kotlin
// Fetch player JS in parallel — needed for signatureCipher decryption
// and n= descrambling, both of which are required for direct adaptive
// URLs to work without throttling.
val playerJsDeferred = coroutineScope.async(Dispatchers.IO) {
    val playerJsUrl = playerJsFetcher.extractPlayerJsUrl(watchResponse.body)
        ?: return@async null
    playerJsFetcher.fetchPlayerJs(playerJsUrl)
}
```

The exact `coroutineScope` reference depends on the surrounding function context. If `extractPlaybackSourceInternal` is already in a `coroutineScope { }` block, use that directly. Otherwise wrap the for-loop region in `coroutineScope { ... }`.

If using a simple `async`, change the for-loop's `for (client in CLIENTS)` body to be inside a `coroutineScope { ... }` block, and call `playerJsDeferred.await()` after the loop completes.

- [ ] **Step 3: Instantiate `Decryptor` once player JS arrives**

After the per-client extraction loop completes, before the `selectPreferredTrailerPlaybackSource` call, add:

```kotlin
val playerJs = playerJsDeferred.await()
val decryptor = playerJs?.let { Decryptor(jsCode = it, functionManager = functionManager) }
```

- [ ] **Step 4: Apply Decryptor to signatureCipher entries during stream candidate collection**

In the existing extraction loop, where we read `format.stringValue("url") ?: continue` for both `formats` and `adaptiveFormats`, we currently DROP entries that lack a `url`. After this task, we instead try `signatureCipher` and decrypt:

Replace the existing `val url = format.stringValue("url") ?: continue` line with:

```kotlin
val rawUrl = format.stringValue("url")
val signatureCipher = format.stringValue("signatureCipher")
    ?: format.stringValue("cipher")  // older shape, sometimes still seen
val url = rawUrl ?: signatureCipher?.let { sc ->
    decryptor?.getStreamingUrl(sc)
} ?: continue
```

Note this is INSIDE the for-each over `streamingData.listMapValue("formats")` and `streamingData.listMapValue("adaptiveFormats")`. The `continue` skips this format entry only when neither url nor signatureCipher decryption produced a URL.

- [ ] **Step 5: Apply nsig descrambling to the chosen progressive/adaptive URLs**

After `bestProgressive`, `bestVideo`, `bestAudio` are picked but before `selectPreferredTrailerPlaybackSource`, descramble n= on each. Add:

```kotlin
val descrambledBestProgressive = bestProgressive?.let { c ->
    val newUrl = decryptor?.descrambleNsig(c.url) ?: c.url
    c.copy(url = newUrl)
}
val descrambledBestVideo = bestVideo?.let { c ->
    val newUrl = decryptor?.descrambleNsig(c.url) ?: c.url
    c.copy(url = newUrl)
}
val descrambledBestAudio = bestAudio?.let { c ->
    val newUrl = decryptor?.descrambleNsig(c.url) ?: c.url
    c.copy(url = newUrl)
}
```

Then in the `selectPreferredTrailerPlaybackSource(...)` call, use `descrambledBestProgressive` / `descrambledBestVideo` / `descrambledBestAudio` instead of the original variables.

(The `StreamCandidate` data class needs to support `.copy(url = ...)`. Verify by reading the data class — it likely already does since it's a Kotlin `data class`.)

- [ ] **Step 6: Update the Hilt module if needed**

Run: `grep -lE "PlayerJsFetcher|FunctionManager" app/src/main/java/com/nexio/tv/core/di/*.kt`
Expected: nothing (both classes are constructor-injectable, no module entry needed — they'll be resolved automatically).

- [ ] **Step 7: Build and verify**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt
git commit -m "feat(jsdecrypt): wire JS-decrypt pipeline into trailer extractor

InAppYouTubeExtractor now fetches the YouTube player JS in parallel
with the per-client player API calls. After both arrive, a Decryptor
is instantiated and applied to extracted streams in two ways:

1. Adaptive formats with a signatureCipher (no direct `url` field) are
   decrypted into real URLs that we can hand to Media3. Previously
   these entries were silently dropped.

2. The chosen progressive / bestVideo / bestAudio URLs have their n=
   throttle parameter descrambled. Without descrambling, YouTube's CDN
   throttles direct stream downloads to ~100 kbps — likely the root
   cause of the persistent low-resolution playback we've been seeing
   even when video does start.

FunctionManager caches parsed JS function code per-session so the
second+ trailer in a session skips re-parsing the player JS."
```

---

## Task 8: Update `selectPreferredTrailerPlaybackSource` to prefer split adaptive when descrambled

Now that direct adaptive URLs are no longer throttled, the original "prefer split adaptive video+audio" preference (which we backed out in commit `37707571f` because direct streams 403'd without poToken) becomes viable again — assuming the smoke test confirms.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/trailer/TrailerSupportTest.kt`

- [ ] **Step 1: Update the preference function**

Replace the existing `selectPreferredTrailerPlaybackSource` body with:

```kotlin
internal fun selectPreferredTrailerPlaybackSource(
    combinedUrl: String?,
    adaptiveVideoUrl: String?,
    adaptiveAudioUrl: String?,
    userAgent: String? = null
): TrailerPlaybackSource? {
    val normalizedAdaptiveVideoUrl = adaptiveVideoUrl?.takeIf { it.isNotBlank() }
    val normalizedAdaptiveAudioUrl = adaptiveAudioUrl?.takeIf { it.isNotBlank() }
    val normalizedCombinedUrl = combinedUrl?.takeIf { it.isNotBlank() }

    // Prefer split adaptive video+audio. The extractor descrambles the
    // n= throttle parameter and decrypts signatureCipher fields before
    // this function is called, so these direct URLs work at full
    // throughput. iOS HLS remains a fallback for the rare case where
    // a video has only a combined source (some shorts, very old
    // uploads).
    if (normalizedAdaptiveVideoUrl != null && normalizedAdaptiveAudioUrl != null) {
        return TrailerPlaybackSource(
            videoUrl = normalizedAdaptiveVideoUrl,
            audioUrl = normalizedAdaptiveAudioUrl,
            userAgent = userAgent
        )
    }
    if (normalizedCombinedUrl != null) {
        return TrailerPlaybackSource(videoUrl = normalizedCombinedUrl, userAgent = userAgent)
    }
    if (normalizedAdaptiveVideoUrl != null) {
        return TrailerPlaybackSource(
            videoUrl = normalizedAdaptiveVideoUrl,
            audioUrl = null,
            userAgent = userAgent
        )
    }
    return null
}
```

- [ ] **Step 2: Update the corresponding tests**

In `app/src/test/java/com/nexio/tv/data/trailer/TrailerSupportTest.kt`, find the `selectPreferredTrailerPlaybackSource` test cases (multiple — preferring combined, falling back to split, etc.). Update them to reflect the new ordering:

- "prefers combined over split" → now "prefers split adaptive over combined when both present"
- "falls back to adaptive split when combined is absent" → still passes (the fallback still works)
- "falls back to combined when split adaptive is incomplete" → still passes

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.TrailerSupportTest" --console=plain 2>&1 | tail -10`
Expected: identify and fix the now-failing test about preference ordering. Update its assertion to match the new behavior.

- [ ] **Step 3: Run tests to verify passing**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.TrailerSupportTest" --console=plain 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt \
        app/src/test/java/com/nexio/tv/data/trailer/TrailerSupportTest.kt
git commit -m "feat(jsdecrypt): prefer split adaptive once URLs are descrambled

With the JS-decrypt pipeline now nsig-descrambling all chosen URLs and
decrypting signatureCipher entries, direct adaptive video+audio
streams should work at full throughput without poToken. Flip
selectPreferredTrailerPlaybackSource to prefer the split adaptive path
again (previously reverted in 37707571f because direct streams 403'd
or throttled without descrambling). iOS HLS remains the fallback for
videos with only combined sources."
```

---

## Task 9: On-device smoke + decision gate

The empirical test of whether nsig descrambling + signatureCipher decryption is enough without poToken. Follow CLAUDE.md rule #8 for profile selection.

**Files:** none — operational verification.

- [ ] **Step 1: Build and install the APK**

Run: `./gradlew :app:installUniversalDebug --console=plain 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Force-stop, launch, select profile**

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
```

- [ ] **Step 3: Manually navigate to each canonical trailer and play it**

Project Hail Mary, Citadel, Ready or Not 2, The Drama — one at a time, let each play ~15 seconds.

- [ ] **Step 4: Pull logcat and check for the success criteria**

```bash
adb -s 192.168.50.98:5555 logcat -d -t 6000 \
  | grep -iE "InAppYouTubeExtractor|TrailerService|TrailerPlayer|OmxVideoDecoder.*nFrameWidth|Decryptor|signatureCipher|JsNsigExtractor|Response code: 4|Source error" \
  | tail -60
```

Success signals:
- `Kotlin selection video=rr...googlevideo.com/videoplayback audioPresent=true combinedSelected=false` — split adaptive path is selected.
- `OmxVideoDecoder: ... nFrameWidth=1920 nFrameHeight=1080` (or higher).
- Zero `Response code: 403` lines associated with `TrailerPlayer`.
- No `Source error` with chunked HTTP failures.

- [ ] **Step 5: Decision gate**

**Branch A — All four trailers play at 1080p+ with no 403s:** SHIP. Push commits, close out the plan. poToken work is not needed.

```bash
git push
```

**Branch B — At least one trailer 403s on adaptive URLs:** Most likely the video specifically requires poToken (rare for trailers but possible). Two sub-options:

- B1: Configure that one trailer to use the iOS HLS fallback by adding a deny-list in `selectPreferredTrailerPlaybackSource` and ship the rest. Acceptable if it's just one outlier.
- B2: Execute the `2026-05-11-youtube-potoken-provider.md` plan on top to add poToken for the remaining failures.

**Branch C — All trailers 403, or persistent throttling at low resolution:** Likely the nsig regexes in `JsNsigExtractor` no longer match YouTube's current player JS minification. Mitigation:

- C1: Check `Decryptor` logs for "Could not find JS N signature function" or similar errors.
- C2: Open `~/Scripts/NewPipeExtractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeJavaScriptPlayerManager.java` and `YoutubeSignatureUtils.java` — these have NewPipe's currently-maintained regexes. Lift the relevant patterns into our `JsNsigExtractor` / `JsSignatureExtractor` and re-test.
- C3: If even NewPipe's regexes don't match (YouTube rotated AGAIN), document the failure and commit to the poToken plan as the only remaining path.

- [ ] **Step 6: If Branch A: final push**

```bash
git push
```

The `2026-05-11-youtube-extractor-newpipe-alignment.md` and `2026-05-11-youtube-potoken-provider.md` plans can be archived (move to `docs/superpowers/plans/archived/` or delete). The JS-decrypt approach won.

---

## Self-Review

**Spec coverage:**

- "Port Duktape JS execution layer" → Tasks 1, 2.
- "Port nsig extractor + descrambler" → Tasks 3, 4.
- "Port signatureCipher extractor + decryptor" → Tasks 3, 4.
- "Port FunctionManager cross-extraction cache" → Task 3.
- "Replace Ktor URL types with android.net.Uri" → Task 5.
- "Fetch the player JS in parallel" → Task 7 Step 2.
- "Apply Decryptor to signatureCipher fields" → Task 7 Step 4.
- "Apply nsig descrambling to direct URLs" → Task 7 Step 5.
- "Flip preference back to split adaptive" → Task 8.
- "On-device validation + decision gate for poToken follow-up" → Task 9.

**Placeholder scan:**

- No "TBD" / "TODO" placeholders.
- One known gap: Task 5 Step 3 mentions `extractMainFunctionName()` on `JsSignatureExtractor` — this may or may not exist in the upstream class. If absent, inline the regex at the call site (Task 5 Step 4 notes this).

**Type consistency:**

- `Decryptor(jsCode: String, functionManager: FunctionManager)` constructor used in Task 5 / Task 7 — consistent.
- `Decryptor.getStreamingUrl(signatureCipher: String): String?` and `Decryptor.descrambleNsig(url: String): String?` — both `suspend fun`, both nullable return on failure.
- `FunctionManager` is a `@Singleton class` (Task 3), constructor-injectable into `InAppYouTubeExtractor` (Task 7) and `Decryptor` (Task 5).
- `PlayerJsFetcher` is a `@Singleton class`, constructor-injectable (Task 6).

---

## Known follow-ups (out of scope for this plan)

- **WEB client with signatureCipher** — adding the WEB client would expose more `signatureCipher` URLs (the Decryptor already handles them). Combined with this plan it would give us access to 4K direct streams. Punt until we confirm baseline ANDROID adaptive is working at 1080p+ first.
- **PoToken (NewPipe WebView + BotGuard)** — see `2026-05-11-youtube-potoken-provider.md`. Only execute if Task 9 Branch B/C reveals it's needed.
- **Duktape VM reuse** — current `JsEvaluator` creates a fresh VM per call. Caching the VM across calls within a single `Decryptor` instance is a perf optimization for sessions with many trailer extractions; benchmark first.
- **Cache the player JS to disk** — currently in-memory only, dropped on app restart. Disk cache (~1MB per player JS) would save ~500ms on cold start.
