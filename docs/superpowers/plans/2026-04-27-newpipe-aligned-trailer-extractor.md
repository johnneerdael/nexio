# NewPipe-Aligned YouTube Trailer Extractor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the 403 trailer-playback regression by aligning `InAppYouTubeExtractor` with NewPipe's working approach: prefer the iOS InnerTube client for HLS, drop the Quest VR client, refresh client versions to NewPipe's current values, and propagate the *same* User-Agent the URL was signed with all the way through to ExoPlayer.

**Architecture:** YouTube signs each `hlsManifestUrl` for the User-Agent of the InnerTube client that issued the player request. Today our extractor asks as `ANDROID_VR`/`ANDROID`/`IOS` but our `TrailerPlayer` fetches the manifest with a hardcoded Chrome desktop UA (`YOUTUBE_STABLE_WEB_USER_AGENT`). Mismatch → 403 on `manifest.googlevideo.com`. NewPipe (`YoutubeStreamExtractor.getHlsUrl()`) prefers iOS for HLS and uses `getIosUserAgent()` everywhere downstream. We mirror that: extractor returns the chosen client's UA inside `TrailerPlaybackSource`, and the player threads that UA into both `DefaultHttpDataSource.Factory` (for the `.m3u8`) and `YoutubeChunkedDataSourceFactory` (for the segments).

**Tech Stack:**
- Kotlin (Android, 8.x toolchain)
- Media3 1.10.0 (forked, `media/libraries/exoplayer*`)
- OkHttp (extractor HTTP)
- JUnit + Robolectric for unit tests under `app/src/test`
- Reference: `~/Scripts/NewPipeExtractor` (Apr 2026 master)

**Reference values from NewPipe `ClientsConstants` (for parity):**
- `IOS_CLIENT_VERSION = "21.03.2"` (`InnertubeClientRequestInfo` for iOS)
- `IOS_DEVICE_MODEL = "iPhone16,2"`
- `IOS_USER_AGENT_VERSION = "18_7_2"` (used in iOS UA; iOS InnerTube body uses `osVersion = "18.7.2.22G100"`)
- `ANDROID_CLIENT_VERSION = "21.03.36"`
- iOS UA template: `com.google.ios.youtube/<ver>(<device>; U; CPU iOS <ua_ver> like Mac OS X; <country>)` — see `YoutubeParsingHelper.getIosUserAgent`
- Android UA template: `com.google.android.youtube/<ver> (Linux; U; Android 15; <country>) gzip` — see `YoutubeParsingHelper.getAndroidUserAgent`

---

## File Structure (decomposition decisions locked here)

| File | Responsibility |
|---|---|
| `app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt` | Carry `userAgent` alongside `videoUrl`/`audioUrl` |
| `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt` | Refresh `CLIENTS` (drop ANDROID_VR, prefer IOS, update versions/UAs); capture chosen client's UA into the returned `TrailerPlaybackSource` |
| `app/src/main/java/com/nexio/tv/data/trailer/YoutubeChunkedDataSourceFactory.kt` | Accept optional `userAgent` constructor arg; default keeps existing web UA |
| `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt` | New `trailerUserAgent: String?` parameter; pass it into both data-source factory branches |
| `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt` | Pass `userAgent` through to cached `TrailerPlaybackSource` |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerResolver.kt` | Construct `TrailerPlaybackSource` with `userAgent = null` (TVDB trailers are direct CDN URLs and don't need it) |
| Call-sites of `TrailerPlayer(...)` | Pass `trailerUserAgent = source.userAgent` |
| Test: `app/src/test/java/com/nexio/tv/data/trailer/InAppYouTubeExtractorClientTest.kt` | Verify client priority order + UA strings match NewPipe |
| Test: `app/src/test/java/com/nexio/tv/data/trailer/YoutubeChunkedDataSourceFactoryUserAgentTest.kt` | Verify factory honours UA override |
| Test: `app/src/test/java/com/nexio/tv/data/trailer/TrailerPlaybackSourceUserAgentTest.kt` | Verify data-class round-trips `userAgent` |

---

## Task 1: `TrailerPlaybackSource` carries `userAgent`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt`
- Test: `app/src/test/java/com/nexio/tv/data/trailer/TrailerPlaybackSourceUserAgentTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/trailer/TrailerPlaybackSourceUserAgentTest.kt`:

```kotlin
package com.nexio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerPlaybackSourceUserAgentTest {

    @Test
    fun `userAgent defaults to null for backwards compatibility`() {
        val source = TrailerPlaybackSource(videoUrl = "https://example.com/v.mp4")
        assertNull(source.userAgent)
    }

    @Test
    fun `userAgent is preserved when provided`() {
        val ua = "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)"
        val source = TrailerPlaybackSource(
            videoUrl = "https://manifest.googlevideo.com/.../index.m3u8",
            userAgent = ua
        )
        assertEquals(ua, source.userAgent)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testUniversalReleaseUnitTest --tests com.nexio.tv.data.trailer.TrailerPlaybackSourceUserAgentTest`
Expected: FAIL — compile error `Unresolved reference: userAgent`.

- [ ] **Step 3: Add the field**

Edit `app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt`. Replace the existing `data class` block:

```kotlin
data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    val userAgent: String? = null
)
```

Then update the two call-sites inside the same file (`selectPreferredTrailerPlaybackSource`) to forward `userAgent`. The full file should read:

```kotlin
package com.nexio.tv.data.trailer

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    val userAgent: String? = null
)

internal fun selectPreferredTrailerPlaybackSource(
    combinedUrl: String?,
    adaptiveVideoUrl: String?,
    adaptiveAudioUrl: String?,
    userAgent: String? = null
): TrailerPlaybackSource? {
    val normalizedCombinedUrl = combinedUrl?.takeIf { it.isNotBlank() }
    if (normalizedCombinedUrl != null) {
        return TrailerPlaybackSource(videoUrl = normalizedCombinedUrl, userAgent = userAgent)
    }
    val normalizedAdaptiveVideoUrl = adaptiveVideoUrl?.takeIf { it.isNotBlank() } ?: return null
    return TrailerPlaybackSource(
        videoUrl = normalizedAdaptiveVideoUrl,
        audioUrl = adaptiveAudioUrl?.takeIf { it.isNotBlank() },
        userAgent = userAgent
    )
}

sealed interface TrailerResolutionResult {
    data class Playback(val source: TrailerPlaybackSource) : TrailerResolutionResult
}
```

(The `sealed interface` line at the bottom must match what already exists in the file — verify with `Read` before saving.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testUniversalReleaseUnitTest --tests com.nexio.tv.data.trailer.TrailerPlaybackSourceUserAgentTest`
Expected: PASS — both tests green.

- [ ] **Step 5: Build to confirm no other call-site broke**

Run: `./gradlew :app:assembleUniversalRelease 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`. If a call-site fails because it constructs `TrailerPlaybackSource` positionally (e.g., `TrailerPlaybackSource("u", "a")`), the new defaulted parameter will not break it — but watch for any code that unpacks via destructuring (none exist today; `Read` if a failure says otherwise).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt \
        app/src/test/java/com/nexio/tv/data/trailer/TrailerPlaybackSourceUserAgentTest.kt
git commit -m "feat(trailers): carry chosen client User-Agent on TrailerPlaybackSource"
```

---

## Task 2: Refresh `CLIENTS` list in extractor (NewPipe parity)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt:112-167`
- Test: `app/src/test/java/com/nexio/tv/data/trailer/InAppYouTubeExtractorClientTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/trailer/InAppYouTubeExtractorClientTest.kt`:

```kotlin
package com.nexio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppYouTubeExtractorClientTest {

    @Test
    fun `iOS client is the highest-priority HLS client`() {
        val firstByPriority = CLIENTS_FOR_TEST.minByOrNull { it.priority }
        assertEquals("ios", firstByPriority?.key)
    }

    @Test
    fun `android_vr is no longer in the client list`() {
        assertTrue(CLIENTS_FOR_TEST.none { it.key == "android_vr" })
    }

    @Test
    fun `iOS UA matches NewPipe template`() {
        val ios = CLIENTS_FOR_TEST.first { it.key == "ios" }
        // NewPipe: com.google.ios.youtube/<ver>(<device>; U; CPU iOS <ua_ver> like Mac OS X; US)
        assertTrue(ios.userAgent.startsWith("com.google.ios.youtube/21.03.2("))
        assertTrue(ios.userAgent.contains("iPhone16,2"))
        assertTrue(ios.userAgent.contains("CPU iOS 18_7_2 like Mac OS X"))
        assertTrue(ios.userAgent.endsWith("US)"))
    }

    @Test
    fun `Android UA matches NewPipe template`() {
        val android = CLIENTS_FOR_TEST.first { it.key == "android" }
        // NewPipe: com.google.android.youtube/<ver> (Linux; U; Android 15; US) gzip
        assertEquals(
            "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip",
            android.userAgent
        )
    }

    @Test
    fun `iOS context block carries clientName clientVersion deviceModel`() {
        val ios = CLIENTS_FOR_TEST.first { it.key == "ios" }
        assertEquals("IOS", ios.context["clientName"])
        assertEquals("21.03.2", ios.context["clientVersion"])
        assertEquals("iPhone16,2", ios.context["deviceModel"])
    }

    @Test
    fun `client list contains exactly ios then android`() {
        assertEquals(listOf("ios", "android"), CLIENTS_FOR_TEST.sortedBy { it.priority }.map { it.key })
    }
}
```

Note: the test references a `CLIENTS_FOR_TEST` symbol — Step 3 exposes the existing private `CLIENTS` list under that name for testability.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testUniversalReleaseUnitTest --tests com.nexio.tv.data.trailer.InAppYouTubeExtractorClientTest`
Expected: FAIL — `Unresolved reference: CLIENTS_FOR_TEST`.

- [ ] **Step 3: Replace the `CLIENTS` list and expose it for tests**

Open `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`. Replace the existing `private val CLIENTS = listOf(...)` block (lines 112–167) with:

```kotlin
internal val CLIENTS_FOR_TEST: List<YouTubeClient> get() = CLIENTS

private val CLIENTS = listOf(
    // NewPipe prefers iOS for HLS — non-iOS clients don't return hlsManifestUrl
    // unless a Safari macOS UA is used. See YoutubeStreamExtractor.getHlsUrl().
    YouTubeClient(
        key = "ios",
        id = "5",
        version = "21.03.2",
        userAgent = "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)",
        context = mapOf(
            "clientName" to "IOS",
            "clientVersion" to "21.03.2",
            "deviceMake" to "Apple",
            "deviceModel" to "iPhone16,2",
            "osName" to "iPhone",
            "osVersion" to "18.7.2.22G100",
            "platform" to "MOBILE",
            "hl" to "en",
            "gl" to "US"
        ),
        priority = 0
    ),
    YouTubeClient(
        key = "android",
        id = "3",
        version = "21.03.36",
        userAgent = "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip",
        context = mapOf(
            "clientName" to "ANDROID",
            "clientVersion" to "21.03.36",
            "osName" to "Android",
            "osVersion" to "15",
            "platform" to "MOBILE",
            "androidSdkVersion" to 35,
            "hl" to "en",
            "gl" to "US"
        ),
        priority = 1
    )
)
```

The `YouTubeClient` data class (around line 33–42 of the same file — confirm with `Read`) is unchanged. The `ANDROID_VR` entry is gone entirely. `priority` ordering is preserved (lowest = tried first).

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testUniversalReleaseUnitTest --tests com.nexio.tv.data.trailer.InAppYouTubeExtractorClientTest`
Expected: PASS — six assertions green.

- [ ] **Step 5: Compile-check**

Run: `./gradlew :app:assembleUniversalRelease 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`. Anything that referenced ANDROID_VR by string (search before edit: `git grep -nE "android_vr|ANDROID_VR"` should now return only test files and comments — fix any leftover by removing it).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt \
        app/src/test/java/com/nexio/tv/data/trailer/InAppYouTubeExtractorClientTest.kt
git commit -m "refactor(trailers): align CLIENTS with NewPipe (iOS primary, drop Quest VR, current versions)"
```

---

## Task 3: Capture chosen client's UA when extractor selects a playback source

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt:344-403`

- [ ] **Step 1: Add a focused unit test**

Append to `app/src/test/java/com/nexio/tv/data/trailer/InAppYouTubeExtractorClientTest.kt`:

```kotlin
    @Test
    fun `lookupClientUserAgent returns null for unknown key`() {
        assertEquals(null, lookupClientUserAgentForTest("does_not_exist"))
    }

    @Test
    fun `lookupClientUserAgent returns iOS UA when key is ios`() {
        val ios = CLIENTS_FOR_TEST.first { it.key == "ios" }
        assertEquals(ios.userAgent, lookupClientUserAgentForTest("ios"))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testUniversalReleaseUnitTest --tests com.nexio.tv.data.trailer.InAppYouTubeExtractorClientTest`
Expected: FAIL — `Unresolved reference: lookupClientUserAgentForTest`.

- [ ] **Step 3: Implement helper + thread UA into the returned `TrailerPlaybackSource`**

In `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`, near the existing top-level helpers (right after the `CLIENTS` declaration), add:

```kotlin
internal fun lookupClientUserAgent(clientKey: String?): String? =
    clientKey?.let { key -> CLIENTS.firstOrNull { it.key == key }?.userAgent }

internal fun lookupClientUserAgentForTest(clientKey: String?): String? =
    lookupClientUserAgent(clientKey)
```

Then locate the block at the end of `extractPlaybackSourceInternal` (currently lines ~374-403) that builds the playback source. Modify it so the chosen client's UA flows in. Replace:

```kotlin
        val bestProgressive = sortTrailerCandidatesForPlayback(progressive).firstOrNull()
        val bestVideo = pickBestForClient(adaptiveVideo, PREFERRED_SEPARATE_CLIENT)
        val bestAudio = pickBestForClient(adaptiveAudio, PREFERRED_SEPARATE_CLIENT)
        val combinedUrl = selectPreferredCombinedTrailerUrl(
            manifestUrl = bestManifest?.manifestUrl,
            progressiveUrl = bestProgressive?.url
        )

        val playbackSource = selectPreferredTrailerPlaybackSource(
            combinedUrl = combinedUrl?.let { resolveReachableUrl(it) },
            adaptiveVideoUrl = bestVideo?.url?.let { resolveReachableUrl(it) },
            adaptiveAudioUrl = bestAudio?.url?.let { resolveReachableUrl(it) }
        ) ?: return null
```

with:

```kotlin
        val bestProgressive = sortTrailerCandidatesForPlayback(progressive).firstOrNull()
        val bestVideo = pickBestForClient(adaptiveVideo, PREFERRED_SEPARATE_CLIENT)
        val bestAudio = pickBestForClient(adaptiveAudio, PREFERRED_SEPARATE_CLIENT)
        val combinedUrl = selectPreferredCombinedTrailerUrl(
            manifestUrl = bestManifest?.manifestUrl,
            progressiveUrl = bestProgressive?.url
        )

        // The UA must match the client whose URL we'll actually play. If a manifest
        // beat the progressive URL, the manifest's client wins; otherwise it's the
        // progressive candidate's client. Adaptive video falls back last.
        val resolvedClientKey = when {
            combinedUrl != null && combinedUrl == bestManifest?.manifestUrl -> bestManifest.client
            combinedUrl != null && combinedUrl == bestProgressive?.url -> bestProgressive.client
            bestVideo != null -> bestVideo.client
            else -> null
        }
        val resolvedUserAgent = lookupClientUserAgent(resolvedClientKey)

        val playbackSource = selectPreferredTrailerPlaybackSource(
            combinedUrl = combinedUrl?.let { resolveReachableUrl(it) },
            adaptiveVideoUrl = bestVideo?.url?.let { resolveReachableUrl(it) },
            adaptiveAudioUrl = bestAudio?.url?.let { resolveReachableUrl(it) },
            userAgent = resolvedUserAgent
        ) ?: return null
```

- [ ] **Step 4: Run unit tests**

Run: `./gradlew :app:testUniversalReleaseUnitTest --tests com.nexio.tv.data.trailer.InAppYouTubeExtractorClientTest`
Expected: PASS — all eight tests green.

- [ ] **Step 5: Compile-check the full app**

Run: `./gradlew :app:assembleUniversalRelease 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt \
        app/src/test/java/com/nexio/tv/data/trailer/InAppYouTubeExtractorClientTest.kt
git commit -m "feat(trailers): propagate chosen InnerTube client User-Agent into TrailerPlaybackSource"
```

---

## Task 4: `YoutubeChunkedDataSourceFactory` accepts a UA override

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/YoutubeChunkedDataSourceFactory.kt:50-69`
- Test: `app/src/test/java/com/nexio/tv/data/trailer/YoutubeChunkedDataSourceFactoryUserAgentTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/trailer/YoutubeChunkedDataSourceFactoryUserAgentTest.kt`:

```kotlin
package com.nexio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeChunkedDataSourceFactoryUserAgentTest {

    @Test
    fun `default user agent matches stable web UA`() {
        val factory = YoutubeChunkedDataSourceFactory()
        assertEquals(YOUTUBE_STABLE_WEB_USER_AGENT, factory.effectiveUserAgent)
    }

    @Test
    fun `override user agent is preserved`() {
        val ios = "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)"
        val factory = YoutubeChunkedDataSourceFactory(userAgent = ios)
        assertEquals(ios, factory.effectiveUserAgent)
    }

    @Test
    fun `blank override falls back to default`() {
        val factory = YoutubeChunkedDataSourceFactory(userAgent = "")
        assertEquals(YOUTUBE_STABLE_WEB_USER_AGENT, factory.effectiveUserAgent)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testUniversalReleaseUnitTest --tests com.nexio.tv.data.trailer.YoutubeChunkedDataSourceFactoryUserAgentTest`
Expected: FAIL — `Unresolved reference: userAgent` / `effectiveUserAgent`.

- [ ] **Step 3: Add the constructor parameter**

In `app/src/main/java/com/nexio/tv/data/trailer/YoutubeChunkedDataSourceFactory.kt`, replace the class declaration (around line 49–69):

```kotlin
@UnstableApi
class YoutubeChunkedDataSourceFactory(
    private val chunkSizeBytes: Long = CHUNK_SIZE,
    userAgent: String? = null
) : DataSource.Factory {

    val effectiveUserAgent: String = userAgent
        ?.takeIf { it.isNotBlank() }
        ?: YOUTUBE_STABLE_WEB_USER_AGENT

    companion object {
        private const val TAG = "YTChunkedDS"
        /** 10 MB chunks – large enough to avoid too many requests, small enough to dodge throttle. */
        private const val CHUNK_SIZE = 10L * 1024 * 1024
    }

    override fun createDataSource(): DataSource {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(effectiveUserAgent)
            .setDefaultRequestProperties(buildStableYouTubeRequestHeaders())
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
        return YoutubeChunkedDataSource(upstream, chunkSizeBytes)
    }
```

(The inner `private class YoutubeChunkedDataSource` and helpers stay unchanged; only the outer class header + `createDataSource` body change.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testUniversalReleaseUnitTest --tests com.nexio.tv.data.trailer.YoutubeChunkedDataSourceFactoryUserAgentTest`
Expected: PASS — three tests green.

- [ ] **Step 5: Verify the existing test still passes**

Run: `./gradlew :app:testUniversalReleaseUnitTest --tests com.nexio.tv.data.trailer.YoutubeChunkedDataSourceFactoryTest`
Expected: PASS — existing two assertions still hold.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/YoutubeChunkedDataSourceFactory.kt \
        app/src/test/java/com/nexio/tv/data/trailer/YoutubeChunkedDataSourceFactoryUserAgentTest.kt
git commit -m "feat(trailers): YoutubeChunkedDataSourceFactory accepts UA override"
```

---

## Task 5: `TrailerPlayer` threads `trailerUserAgent` into both data-source branches

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt`

- [ ] **Step 1: Add the param + thread it through** (no test — Compose composables aren't unit-testable here; integration covered by Task 7's adb retest.)

In `TrailerPlayer.kt` add the import (next to existing `YOUTUBE_STABLE_WEB_USER_AGENT` import):

```kotlin
// already imported in earlier turn:
// import com.nexio.tv.data.trailer.YOUTUBE_STABLE_WEB_USER_AGENT
// import com.nexio.tv.data.trailer.buildStableYouTubeRequestHeaders
// import com.nexio.tv.data.trailer.YoutubeChunkedDataSourceFactory
```

Add `trailerUserAgent: String? = null` to the `TrailerPlayer` parameter list. The full updated signature (replace the existing `fun TrailerPlayer(...)` block at line ~83):

```kotlin
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrailerPlayer(
    trailerUrl: String?,
    trailerAudioUrl: String? = null,
    isPlaying: Boolean,
    onEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit = {},
    onError: () -> Unit = {},
    muted: Boolean = false,
    seekRequestToken: Int = 0,
    seekDeltaMs: Long = 0L,
    onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onRemoteKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean = { _, _, _ -> false },
    cropToFill: Boolean = false,
    overscanZoom: Float = 1f,
    trailerUserAgent: String? = null,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = tween(800)),
    exit: ExitTransition = fadeOut(animationSpec = tween(500))
) {
```

(The new `trailerUserAgent` is inserted before `modifier` on purpose — composable conventions keep `modifier` and animation transitions last.)

Update `buildTrailerMediaSourceFactory` (currently at line ~152). Replace the entire function body:

```kotlin
    fun buildTrailerMediaSourceFactory(
        videoUrl: String,
        audioUrl: String?
    ): DefaultMediaSourceFactory {
        val effectiveUa = trailerUserAgent?.takeIf { it.isNotBlank() }
            ?: YOUTUBE_STABLE_WEB_USER_AGENT
        return if (shouldUseChunkedTrailerDataSource(videoUrl, audioUrl)) {
            DefaultMediaSourceFactory(YoutubeChunkedDataSourceFactory(userAgent = effectiveUa))
        } else {
            DefaultMediaSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent(effectiveUa)
                    .setDefaultRequestProperties(buildStableYouTubeRequestHeaders())
                    .setAllowCrossProtocolRedirects(true)
            )
        }
    }
```

- [ ] **Step 2: Compile-check**

Run: `./gradlew :app:assembleUniversalRelease 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt
git commit -m "feat(trailers): TrailerPlayer threads UA into chunked + bare DataSource branches"
```

---

## Task 6: Pass `trailerUserAgent` from every `TrailerPlayer` call-site

**Files (modify all six):**
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt:541`
- `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt:950`
- `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt:108`
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt:2121`
- `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt:292`
- `app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt:372`

Each call-site already has access to a `TrailerPlaybackSource` (or its `videoUrl`/`audioUrl` fields are unpacked from one). Find the source variable and pass `trailerUserAgent = source.userAgent`.

- [ ] **Step 1: Audit + locate each `TrailerPlaybackSource`**

Run: `git grep -nE "TrailerPlayer\(" app/src/main/java`
Note the six lines listed above. For each, scroll up ≈30 lines to find where the `TrailerPlaybackSource` (or its unpacked fields) is in scope. The convention in this codebase is to hold a non-null `TrailerPlaybackSource` named one of: `activePosterTrailerPlayback`, `trailerSource`, `seasonTrailerSource`, or `playback`.

- [ ] **Step 2: HomeScreen.kt — add the new arg**

Edit `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt` around line 541. Inside the `TrailerPlayer(...)` call, add right after `trailerAudioUrl = activePosterTrailerPlayback.audioUrl,`:

```kotlin
                trailerUserAgent = activePosterTrailerPlayback.userAgent,
```

- [ ] **Step 3: ModernHomeRows.kt — same edit**

Edit `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt` around line 950. Inside the `TrailerPlayer(...)` call, add `trailerUserAgent = <source>.userAgent,` next to the audio URL line — the source variable name is `playback` here (verify by reading lines ~940-960; if the name differs, use whatever variable holds the `TrailerPlaybackSource`).

- [ ] **Step 4: ModernHomeHero.kt — same edit**

Edit `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt` around line 108. Add `trailerUserAgent = <source>.userAgent,` to the `TrailerPlayer(...)` call. Read lines 100–115 to find the source variable name; pass its `.userAgent`.

- [ ] **Step 5: MetaDetailsScreen.kt — same edit**

Edit `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt` around line 2121. Look at the props passed to `TrailerPlayer`. The source object is in scope (read the surrounding `if (shouldRenderDetailTrailerPlayer(...))` block at line ~2120). Add `trailerUserAgent = <source>.userAgent,`.

- [ ] **Step 6: IdleTrailerScreensaverOverlay.kt — same edit**

Edit `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt` around line 292. Add `trailerUserAgent = <source>.userAgent,`.

- [ ] **Step 7: ContentCard.kt — same edit**

Edit `app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt` around line 372. Add `trailerUserAgent = <source>.userAgent,`.

- [ ] **Step 8: Compile-check + run all unit tests**

Run: `./gradlew :app:testUniversalReleaseUnitTest 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`. If any test in `app/src/test/java/com/nexio/tv/ui/...TrailerPlayer*` constructs `TrailerPlayer(...)` directly via reflection-free Compose, it should still work because `trailerUserAgent` is defaulted to `null`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt \
        app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt \
        app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt \
        app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt
git commit -m "wire(trailers): pass TrailerPlaybackSource.userAgent into TrailerPlayer at every call-site"
```

---

## Task 7: TVDB resolver explicitly opts out of UA threading

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerResolver.kt:210`

TVDB trailers are direct CDN URLs (not YouTube), so they don't need a YouTube UA. We pass `userAgent = null` explicitly to make the intent visible.

- [ ] **Step 1: Update the constructor call**

Edit `app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerResolver.kt:210`. Replace:

```kotlin
                            TrailerPlaybackSource(videoUrl = usability.url)
```

with:

```kotlin
                            TrailerPlaybackSource(videoUrl = usability.url, userAgent = null)
```

(The `userAgent = null` is already the default — this is purely documentary, but matters for grep-ability and review.)

- [ ] **Step 2: Compile-check**

Run: `./gradlew :app:assembleUniversalRelease 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerResolver.kt
git commit -m "docs(trailers): make TVDB trailers' null UA explicit"
```

---

## Task 8: End-to-end verification on device

**Files:** none (deployment + smoke test)

- [ ] **Step 1: Install the release on the test device**

Run: `./gradlew :app:installUniversalRelease 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`. Note `lastUpdateTime`:

Run: `adb -s 192.168.50.98:5555 shell dumpsys package com.nexio.tv | grep -E "lastUpdateTime"`

- [ ] **Step 2: Clear logcat, restart app, arm crash + 403 monitor**

Run:
```bash
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell am force-stop com.nexio.tv
adb -s 192.168.50.98:5555 shell monkey -p com.nexio.tv -c android.intent.category.LEANBACK_LAUNCHER 1
```

Then arm a Monitor (use whatever harness the executor environment provides; for direct adb, run in another terminal):
```bash
adb -s 192.168.50.98:5555 logcat -v time 2>&1 | grep --line-buffered -E \
  "InAppYouTubeExtractor: Kotlin extraction success|TrailerPlayer.*failed|Response code: [0-9]+|onRenderedFirstFrame|FATAL|isTrackExcluded"
```

- [ ] **Step 3: Trigger a YouTube trailer**

Manually navigate to a movie or series detail page (or wait for a poster trailer to autoplay on the home screen). Watch logcat for:
- `InAppYouTubeExtractor: Kotlin extraction success` (extraction OK)
- `onRenderedFirstFrame` from `TrailerPlayer` (trailer is actually playing — this was missing pre-fix)
- **No** `Response code: 403` lines on `manifest.googlevideo.com`
- **No** `TrailerPlayer ... failed code=ERROR_CODE_IO_BAD_HTTP_STATUS`
- **No** `FATAL` / `isTrackExcluded` events

- [ ] **Step 4: Repeat against three different trailers**

YouTube binds URLs per-video; verify with at least three distinct titles to rule out one-shot luck. Sample: a recent blockbuster, an older catalog title, a TV series episode trailer. All three should produce `onRenderedFirstFrame` with no 403.

- [ ] **Step 5: Verify dedup + autoplay are still working** (regression guard for the rest of the session's work)

Trigger a Stremio autoplay session and confirm:
- `EARLY_FINISH_COUNT_BREAKDOWN ... provider={premiumize:N,real_debrid:M}` with both buckets non-zero
- `PROBE_PROFILING_DONE ... totalProbes=4` (no `primary_proxy_direct`)
- `DETERMINISTIC_EARLY_FINISH triggered=true reason=threshold_met`

- [ ] **Step 6: Final commit (no code changes; verification artefact)**

If any of the above fails, do not commit — file a follow-up task instead. If all pass:

```bash
git log --oneline -10
# Expected: see the seven preceding commits, all on main, and no uncommitted changes.
git status
# Expected: nothing to commit, working tree clean.
```

---

## Self-Review

**Spec coverage (each requirement maps to a task):**

| Requirement | Covered by |
|---|---|
| Drop the Quest VR client (irrelevant to TV trailers, never returns HLS for VOD) | Task 2 |
| Prefer iOS for HLS (NewPipe parity) | Task 2 |
| Update client versions to NewPipe-current values | Task 2 |
| Match URL signature with playback UA | Tasks 3 + 4 + 5 + 6 |
| Don't hardcode Chrome web UA for YouTube trailers | Tasks 4 + 5 |
| TVDB trailers continue to work (no YouTube UA needed) | Task 7 |
| End-to-end smoke on device | Task 8 |

**Placeholder scan:** none of the listed anti-patterns appear. Every code step shows the actual code; every command has expected output. The "find `<source>`" instruction in Task 6 is intentional — call-sites differ in the variable name they bind the source to; running `Read` on each line is faster and more accurate than guessing.

**Type consistency:**
- `TrailerPlaybackSource.userAgent: String?` is referenced identically in Tasks 1, 3, 6, 7.
- `TrailerPlayer.trailerUserAgent: String? = null` matches the prop passed in Task 6.
- `YoutubeChunkedDataSourceFactory(userAgent = ...)` constructor arg is the same in Tasks 4 and 5.
- `lookupClientUserAgent` / `lookupClientUserAgentForTest` defined in Task 3 are the only callers of `CLIENTS` outside the extractor.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-27-newpipe-aligned-trailer-extractor.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
