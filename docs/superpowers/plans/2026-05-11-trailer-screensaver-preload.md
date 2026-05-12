# Trailer Screensaver Preload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pre-warm the next idle-trailer-screensaver candidate's Media3 MediaSource while the current trailer plays, so between-trailer transitions are near-instant and known-good trailers stop getting skipped by the 5s first-frame timeout.

**Architecture:** Introduce a session-scoped `TrailerPreloadGate` wrapping Media3's stock `DefaultPreloadManager`. The overlay enqueues the next candidate's preload right after it pre-resolves the playback source, and consumes the prepared `MediaSource` on advance, passing it through `TrailerPlayer`'s new `prepreparedMediaSource` parameter so `ExoPlayer.setMediaSource(prepared)` skips reparse + reopen. First-frame timeout is bumped 5s → 15s globally so cold-start fallbacks (initial trailer, preload miss) also get enough room.

**Tech Stack:** Kotlin, Jetpack Compose, Media3 (`androidx.media3.exoplayer.source.preload.DefaultPreloadManager`), JUnit 4, kotlinx.coroutines-test.

**Spec:** `docs/superpowers/specs/2026-05-11-trailer-screensaver-preload-design.md`

---

## File Map

**Create:**
- `app/src/main/java/com/nexio/tv/ui/components/TrailerMediaSourceFactory.kt` — shared `TrailerPlaybackSource → MediaSource` construction.
- `app/src/main/java/com/nexio/tv/ui/screensaver/TrailerPreloadGate.kt` — interface + real `DefaultPreloadManager`-backed implementation.
- `app/src/test/java/com/nexio/tv/ui/components/TrailerMediaSourceFactoryTest.kt` — JVM unit test for source-construction.
- `app/src/test/java/com/nexio/tv/ui/screensaver/FakeTrailerPreloadGate.kt` — test double for overlay logic tests.
- `app/src/test/java/com/nexio/tv/ui/screensaver/IdleTrailerPreloadDecisionTest.kt` — JVM unit test for use-preload-vs-fallback decision helper.

**Modify:**
- `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt` — extract source construction; accept optional `prepreparedMediaSource: MediaSource?`.
- `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt` — bump timeout, own a `TrailerPreloadGate`, enqueue on next-resolution, consume on advance, release on dispose.
- `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt` — add `emitTrailerScreensaverPreload`.

---

## Task 1: Extract `TrailerMediaSourceFactory`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/components/TrailerMediaSourceFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt` (lines 331–423)
- Test: `app/src/test/java/com/nexio/tv/ui/components/TrailerMediaSourceFactoryTest.kt`

The existing `buildTrailerMediaSourceFactory` + `prepareTrailerMediaSource` local functions inside `TrailerPlayer.kt:331-423` need to live outside the composable so the preload manager can also use them. The behaviour (per-host UA resolver, signed-client property selection, chunked-vs-default data source choice, audio+video merging) must be preserved exactly.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/nexio/tv/ui/components/TrailerMediaSourceFactoryTest.kt
package com.nexio.tv.ui.components

import android.content.Context
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrailerMediaSourceFactoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `createMediaSource produces MergingMediaSource when audio url present`() {
        val source = TrailerPlaybackSource(
            videoUrl = "https://r1---googlevideo.example.com/video.m3u8",
            audioUrl = "https://r1---googlevideo.example.com/audio.m4a",
            userAgent = "test-ua",
            signingClientKey = "android"
        )

        val mediaSource = TrailerMediaSourceFactory(context).createMediaSource(source)

        assertTrue(mediaSource is MergingMediaSource)
    }

    @Test
    fun `createMediaSource produces single source when audio url absent`() {
        val source = TrailerPlaybackSource(
            videoUrl = "https://r1---googlevideo.example.com/video.m3u8",
            audioUrl = null,
            userAgent = "test-ua",
            signingClientKey = "ios"
        )

        val mediaSource = TrailerMediaSourceFactory(context).createMediaSource(source)

        assertEquals(false, mediaSource is MergingMediaSource)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testArmv7DebugUnitTest --tests "com.nexio.tv.ui.components.TrailerMediaSourceFactoryTest"`
Expected: FAIL with "Unresolved reference: TrailerMediaSourceFactory".

- [ ] **Step 3: Create the factory by lifting code from TrailerPlayer**

```kotlin
// app/src/main/java/com/nexio/tv/ui/components/TrailerMediaSourceFactory.kt
package com.nexio.tv.ui.components

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.YOUTUBE_STABLE_WEB_USER_AGENT
import com.nexio.tv.data.trailer.YouTubeWireProfile
import com.nexio.tv.data.trailer.YoutubeChunkedDataSourceFactory
import com.nexio.tv.data.trailer.buildYouTubeWireProperties
import com.nexio.tv.data.trailer.shouldUseChunkedTrailerDataSource

@UnstableApi
class TrailerMediaSourceFactory(private val context: Context) {

    fun createMediaSource(source: TrailerPlaybackSource): MediaSource {
        val factory = buildMediaSourceFactory(
            videoUrl = source.videoUrl,
            audioUrl = source.audioUrl,
            userAgent = source.userAgent,
            signingClientKey = source.signingClientKey
        )
        val videoItem = MediaItem.fromUri(source.videoUrl)
        return if (!source.audioUrl.isNullOrBlank()) {
            val video = factory.createMediaSource(videoItem)
            val audio = factory.createMediaSource(MediaItem.fromUri(source.audioUrl))
            MergingMediaSource(video, audio)
        } else {
            factory.createMediaSource(videoItem)
        }
    }

    private fun buildMediaSourceFactory(
        videoUrl: String,
        audioUrl: String?,
        userAgent: String?,
        signingClientKey: String?
    ): DefaultMediaSourceFactory {
        val signedClientUserAgent = userAgent
            ?.takeIf { it.isNotBlank() }
            ?: YOUTUBE_STABLE_WEB_USER_AGENT
        val signedClientProfile = when (signingClientKey) {
            "ios" -> YouTubeWireProfile.IOS
            "android" -> YouTubeWireProfile.ANDROID
            else -> YouTubeWireProfile.WEB
        }
        val signedClientProperties = buildYouTubeWireProperties(
            profile = signedClientProfile,
            userAgent = signedClientUserAgent
        )
        val webProperties = buildYouTubeWireProperties(
            profile = YouTubeWireProfile.WEB,
            userAgent = YOUTUBE_STABLE_WEB_USER_AGENT
        )
        val resolver = ResolvingDataSource.Resolver { dataSpec ->
            val host = dataSpec.uri.host.orEmpty()
            val properties = if (host.contains("googlevideo.com")) {
                signedClientProperties
            } else {
                webProperties
            }
            dataSpec.withRequestHeaders(properties)
        }
        return if (shouldUseChunkedTrailerDataSource(videoUrl, audioUrl)) {
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    context,
                    ResolvingDataSource.Factory(
                        YoutubeChunkedDataSourceFactory(
                            userAgent = signedClientUserAgent,
                            requestProperties = signedClientProperties
                        ),
                        resolver
                    )
                )
            )
        } else {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(signedClientUserAgent)
                .setAllowCrossProtocolRedirects(true)
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    context,
                    ResolvingDataSource.Factory(httpFactory, resolver)
                )
            )
        }
    }
}
```

- [ ] **Step 4: Replace TrailerPlayer's inline construction with the factory**

In `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt`, delete the local `buildTrailerMediaSourceFactory` function (lines 331–407) and `prepareTrailerMediaSource` (lines 409–423). Replace the call site at line 431 (`prepareTrailerMediaSource(player, trailerUrl!!, trailerAudioUrl)`) with:

```kotlin
val factory = remember(context) { TrailerMediaSourceFactory(context) }
// (Add `factory` to the relevant LaunchedEffect dependency list.)
// ... inside the LaunchedEffect at line 425:
val source = TrailerPlaybackSource(
    videoUrl = trailerUrl!!,
    audioUrl = trailerAudioUrl,
    userAgent = trailerUserAgent,
    signingClientKey = trailerSigningClientKey
)
player.setMediaSource(factory.createMediaSource(source))
```

Also update the second call site at line 506-512 (`if (player.currentMediaItem == null) { prepareTrailerMediaSource(...) }`) to use the same factory.

Imports to add to TrailerPlayer.kt: `import com.nexio.tv.data.trailer.TrailerPlaybackSource`. Imports to remove (now unused): `DefaultMediaSourceFactory`, `DefaultHttpDataSource`, `DefaultDataSource`, `ResolvingDataSource`, `YOUTUBE_STABLE_WEB_USER_AGENT`, `YouTubeWireProfile`, `YoutubeChunkedDataSourceFactory`, `buildYouTubeWireProperties`, `shouldUseChunkedTrailerDataSource`, `MergingMediaSource`.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testArmv7DebugUnitTest --tests "com.nexio.tv.ui.components.TrailerMediaSourceFactoryTest"`
Expected: PASS, both tests.

- [ ] **Step 6: Build to verify TrailerPlayer compiles**

Run: `./gradlew :app:compileArmv7DebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/components/TrailerMediaSourceFactory.kt \
        app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt \
        app/src/test/java/com/nexio/tv/ui/components/TrailerMediaSourceFactoryTest.kt
git commit -m "refactor(trailer): extract TrailerMediaSourceFactory for reuse by preload"
```

---

## Task 2: Bump first-frame timeout 5s → 15s

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt:56`

Pure constant change. No test — the timeout is exercised end-to-end on device and via the existing `shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout` unit test which already covers the decision logic regardless of duration.

- [ ] **Step 1: Change the constant**

In `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt`:

```kotlin
// Was: private const val TRAILER_SCREENSAVER_FIRST_FRAME_TIMEOUT_MS = 5_000L
private const val TRAILER_SCREENSAVER_FIRST_FRAME_TIMEOUT_MS = 15_000L
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileArmv7DebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the existing screensaver session tests to confirm no regression**

Run: `./gradlew :app:testArmv7DebugUnitTest --tests "com.nexio.tv.ui.screensaver.*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt
git commit -m "fix(trailer/screensaver): bump first-frame timeout 5s -> 15s for cold-start headroom"
```

---

## Task 3: Define `TrailerPreloadGate` interface + fake

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screensaver/TrailerPreloadGate.kt` (interface only — real impl in Task 4)
- Create: `app/src/test/java/com/nexio/tv/ui/screensaver/FakeTrailerPreloadGate.kt`

The interface allows the overlay to depend on a small surface and lets tests substitute a fake. Real `DefaultPreloadManager` is Android-bound and not unit-testable on the JVM.

- [ ] **Step 1: Define the interface**

```kotlin
// app/src/main/java/com/nexio/tv/ui/screensaver/TrailerPreloadGate.kt
package com.nexio.tv.ui.screensaver

import androidx.media3.exoplayer.source.MediaSource
import com.nexio.tv.data.trailer.TrailerPlaybackSource

/**
 * Session-scoped facade over Media3's DefaultPreloadManager. Holds one
 * preload slot at a time; the overlay enqueues the next candidate's
 * MediaSource while the current trailer plays and consumes the prepared
 * MediaSource on advance.
 *
 * Implementations are not thread-safe — call from the overlay's main
 * Compose coroutine context only.
 */
interface TrailerPreloadGate {
    /**
     * Begin (or replace) preload for [source]. Any in-flight preload for
     * a different source is discarded. Idempotent if [source] equals the
     * currently-enqueued source.
     */
    fun enqueue(source: TrailerPlaybackSource)

    /**
     * Returns the preloaded `MediaSource` for [source] when [source]
     * equals the currently-enqueued source. The returned source may be
     * partially preloaded — `ExoPlayer` continues prep from whichever
     * state Media3 has reached (even a partial buffer is faster than
     * none). Returns `null` only when [source] is not the
     * currently-enqueued source (mismatch, never enqueued, or already
     * consumed). After a successful return the slot is cleared and the
     * preload entry is removed from the underlying manager.
     */
    fun consume(source: TrailerPlaybackSource): MediaSource?

    /** Releases all preload state and shuts down internal resources. */
    fun release()
}
```

- [ ] **Step 2: Create the fake test double**

```kotlin
// app/src/test/java/com/nexio/tv/ui/screensaver/FakeTrailerPreloadGate.kt
package com.nexio.tv.ui.screensaver

import androidx.media3.exoplayer.source.MediaSource
import com.nexio.tv.data.trailer.TrailerPlaybackSource

class FakeTrailerPreloadGate : TrailerPreloadGate {
    val enqueued = mutableListOf<TrailerPlaybackSource>()
    val released = mutableListOf<Unit>()
    private val ready = mutableMapOf<TrailerPlaybackSource, MediaSource>()

    fun markReady(source: TrailerPlaybackSource, mediaSource: MediaSource) {
        ready[source] = mediaSource
    }

    override fun enqueue(source: TrailerPlaybackSource) {
        enqueued += source
    }

    override fun consume(source: TrailerPlaybackSource): MediaSource? {
        return ready.remove(source)
    }

    override fun release() {
        released += Unit
        ready.clear()
        enqueued.clear()
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileArmv7DebugKotlin :app:compileArmv7DebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screensaver/TrailerPreloadGate.kt \
        app/src/test/java/com/nexio/tv/ui/screensaver/FakeTrailerPreloadGate.kt
git commit -m "feat(trailer/screensaver): TrailerPreloadGate interface + test fake"
```

---

## Task 4: Implement `DefaultPreloadManager`-backed gate

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/TrailerPreloadGate.kt`

Real production implementation. Cannot be JVM-tested (Media3 PreloadManager needs Android Looper/Context). Smoke-tested on device in Task 8.

- [ ] **Step 1: Add the real implementation alongside the interface**

Append to `app/src/main/java/com/nexio/tv/ui/screensaver/TrailerPreloadGate.kt`:

```kotlin
package com.nexio.tv.ui.screensaver

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.ui.components.TrailerMediaSourceFactory

/**
 * Rank we hand DefaultPreloadManager. Single-slot, so rank is always
 * SLOT_RANK and we replace the entry on enqueue() with a different source.
 */
private const val SLOT_RANK = 0

/**
 * Target preload status: load tracks + a small initial buffer so the
 * decoder has enough to produce a first frame immediately on
 * setMediaSource(). Bounded to keep memory cost predictable.
 */
private const val PRELOAD_BUFFER_MS = 2_000L

@UnstableApi
class DefaultTrailerPreloadGate(
    context: Context
) : TrailerPreloadGate {

    private val factory = TrailerMediaSourceFactory(context)

    private val targetStatusControl =
        TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { _ ->
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(PRELOAD_BUFFER_MS)
        }

    private val preloadManager: DefaultPreloadManager =
        DefaultPreloadManager.Builder(context, targetStatusControl).build()

    // We track MediaItem (NOT the original MediaSource reference). The
    // preload manager wraps the source in an internal MediaSourceHolder
    // whose getMediaSource() returns the preloaded wrapper that owns the
    // SampleQueue. BasePreloadManager.getMediaSource(MediaItem) is the
    // only public path to retrieve that wrapper. If we kept the original
    // source reference and passed it to ExoPlayer.setMediaSource(), the
    // player would NOT use the preload buffer — zero speedup.
    //
    // We rely on MergingMediaSource.getMediaItem() returning the video
    // source's MediaItem (which it does — Media3 fork inspected
    // 2026-05-12). Within a screensaver session of 40 distinct trailers
    // the videoUrls are all distinct YouTube CDN URLs, so MediaItems are
    // unique. If that assumption ever breaks, switch the key to a
    // synthetic MediaItem we construct from a stable per-source id.
    private var enqueuedSource: TrailerPlaybackSource? = null
    private var enqueuedMediaItem: MediaItem? = null

    override fun enqueue(source: TrailerPlaybackSource) {
        if (enqueuedSource == source) return
        enqueuedMediaItem?.let { preloadManager.remove(it) }
        val mediaSource = factory.createMediaSource(source)
        preloadManager.add(mediaSource, SLOT_RANK)
        preloadManager.invalidate()
        enqueuedSource = source
        enqueuedMediaItem = mediaSource.mediaItem
    }

    override fun consume(source: TrailerPlaybackSource): MediaSource? {
        if (enqueuedSource != source) return null
        val mediaItem = enqueuedMediaItem ?: return null
        val prepared = preloadManager.getMediaSource(mediaItem) ?: return null
        // Remove the entry so the manager stops preloading it and frees
        // the SampleQueue buffer for the NEXT preload. The returned
        // `prepared` MediaSource is now owned by the caller (passed to
        // ExoPlayer.setMediaSource); the player drives it independently.
        preloadManager.remove(mediaItem)
        enqueuedSource = null
        enqueuedMediaItem = null
        return prepared
    }

    override fun release() {
        enqueuedSource = null
        enqueuedMediaItem = null
        preloadManager.release()
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileArmv7DebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screensaver/TrailerPreloadGate.kt
git commit -m "feat(trailer/screensaver): DefaultPreloadManager-backed preload gate"
```

---

## Task 5: Extend `TrailerPlayer` to accept a pre-prepared MediaSource

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt`

Add an optional parameter. When provided, the LaunchedEffect at line 425 calls `player.setMediaSource(prepreparedMediaSource)` instead of constructing via the factory. When `null`, today's URL-init path runs unchanged.

- [ ] **Step 1: Add the parameter to the composable signature**

In `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt` at line 131:

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
    trailerSigningClientKey: String? = null,
    trailerCaptions: List<YouTubeCaptionTrack> = emptyList(),
    prepreparedMediaSource: androidx.media3.exoplayer.source.MediaSource? = null,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = tween(800)),
    exit: ExitTransition = fadeOut(animationSpec = tween(500))
)
```

- [ ] **Step 2: Update the LaunchedEffect that prepares the player**

In the `LaunchedEffect(isPlaying, trailerUrl, trailerAudioUrl, muted, lifecycleState)` block at line 425, add `prepreparedMediaSource` to the keys and branch on it inside `shouldPrepareTrailerPlayback`:

```kotlin
LaunchedEffect(isPlaying, trailerUrl, trailerAudioUrl, muted, lifecycleState, prepreparedMediaSource) {
    val player = trailerPlayer ?: return@LaunchedEffect
    player.volume = if (muted) 0f else 1f
    if (shouldPrepareTrailerPlayback(lifecycleState, isPlaying, trailerUrl)) {
        FrameRateUtils.blockDisplayModeChangesForNonPlayerPlayback()
        hasRenderedFirstFrame = false
        if (prepreparedMediaSource != null) {
            player.setMediaSource(prepreparedMediaSource)
        } else {
            val source = TrailerPlaybackSource(
                videoUrl = trailerUrl!!,
                audioUrl = trailerAudioUrl,
                userAgent = trailerUserAgent,
                signingClientKey = trailerSigningClientKey
            )
            player.setMediaSource(factory.createMediaSource(source))
        }
        player.prepare()
        player.playWhenReady = true
    } else {
        hasRenderedFirstFrame = false
        player.stop()
        player.clearMediaItems()
    }
}
```

(The `factory` local is the `remember(context) { TrailerMediaSourceFactory(context) }` added in Task 1.)

- [ ] **Step 3: Update the fallback prepare at the second call site (around line 506)**

Replace the `prepareTrailerMediaSource(player, ...)` call inside the `if (player.currentMediaItem == null)` branch with the same factory-or-prepared logic. If `prepreparedMediaSource != null`, use it; otherwise build via the factory:

```kotlin
if (player.currentMediaItem == null) {
    if (prepreparedMediaSource != null) {
        player.setMediaSource(prepreparedMediaSource)
    } else {
        val source = TrailerPlaybackSource(
            videoUrl = trailerUrl!!,
            audioUrl = trailerAudioUrl,
            userAgent = trailerUserAgent,
            signingClientKey = trailerSigningClientKey
        )
        player.setMediaSource(factory.createMediaSource(source))
    }
    player.prepare()
}
```

- [ ] **Step 4: Build to verify all call sites compile**

Run: `./gradlew :app:compileArmv7DebugKotlin`
Expected: BUILD SUCCESSFUL. Other `TrailerPlayer(...)` call sites (`ModernHomeHero.kt`, `MetaDetailsScreen.kt`, `ModernHomeContent.kt`, `IdleTrailerScreensaverOverlay.kt`) get `prepreparedMediaSource = null` by default and do not need changes yet.

- [ ] **Step 5: Run existing trailer-player tests if any**

Run: `./gradlew :app:testArmv7DebugUnitTest --tests "com.nexio.tv.ui.components.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt
git commit -m "feat(trailer): TrailerPlayer accepts pre-prepared MediaSource"
```

---

## Task 6: Wire preload kick-off in the overlay

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt`

When `preparedNextPlayback` is resolved (line 131–138 today), also enqueue its source on the `TrailerPreloadGate`. The gate is created via `DisposableEffect` at session start and released on dispose.

- [ ] **Step 1: Add the preload-gate factory parameter to the overlay composable**

Production code uses `DefaultTrailerPreloadGate`. Test code supplies `FakeTrailerPreloadGate`. The composable takes a factory so it can be swapped:

```kotlin
@Composable
internal fun IdleTrailerScreensaverOverlay(
    sessionId: Long,
    sessionStart: IdleTrailerScreensaverSessionStart,
    onDismiss: () -> Unit,
    onOpenSlide: (IdleTrailerScreensaverCandidate) -> Unit,
    resolvePlaybackSource: suspend (IdleTrailerScreensaverCandidate, TrailerPlaybackRef) -> com.nexio.tv.data.trailer.TrailerPlaybackSource?,
    preloadGateFactory: (android.content.Context) -> TrailerPreloadGate = { ctx ->
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        DefaultTrailerPreloadGate(ctx)
    }
) {
```

Call sites passing only the existing parameters continue to work via the default.

- [ ] **Step 2: Own the gate with a DisposableEffect**

Add after `val context = LocalContext.current` (around line 92):

```kotlin
val preloadGate = remember(sessionId) { preloadGateFactory(context) }
DisposableEffect(preloadGate) {
    onDispose { preloadGate.release() }
}
```

- [ ] **Step 3: Enqueue when `preparedNextPlayback` becomes available**

Replace the `LaunchedEffect` at line 131 with:

```kotlin
LaunchedEffect(sessionId, currentPlayback.index, currentPlayback.playbackRef, sessionStart.candidates, failedPlaybackKeys) {
    val next = resolveNextIdleTrailerPlayback(
        candidates = sessionStart.candidates,
        currentIndex = currentPlayback.index,
        skippedPlaybackKeys = failedPlaybackKeys,
        resolvePlayback = currentResolvePlayback
    )
    preparedNextPlayback = next
    if (next != null) {
        preloadGate.enqueue(next.source)
    }
}
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:compileArmv7DebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run existing overlay-related tests**

Run: `./gradlew :app:testArmv7DebugUnitTest --tests "com.nexio.tv.ui.screensaver.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt
git commit -m "feat(trailer/screensaver): enqueue next-trailer preload while current plays"
```

---

## Task 7: Consume preload on advance + pass to `TrailerPlayer`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screensaver/IdleTrailerPreloadDecisionTest.kt`

A small pure helper decides "use preload vs cold-start" so the decision is unit-testable. The overlay calls it on advance and passes the result down to `TrailerPlayer`.

- [ ] **Step 1: Write the failing test for the decision helper**

```kotlin
// app/src/test/java/com/nexio/tv/ui/screensaver/IdleTrailerPreloadDecisionTest.kt
package com.nexio.tv.ui.screensaver

import androidx.media3.exoplayer.source.MediaSource
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock

class IdleTrailerPreloadDecisionTest {

    private val source = TrailerPlaybackSource(videoUrl = "https://example.com/a.m3u8")
    private val otherSource = TrailerPlaybackSource(videoUrl = "https://example.com/b.m3u8")

    @Test
    fun `consumePreloadedSource returns gate value when source matches enqueued`() {
        val gate = FakeTrailerPreloadGate()
        val media = mock(MediaSource::class.java)
        gate.markReady(source, media)

        val result = consumePreloadedSourceForAdvance(gate, source)

        assertSame(media, result)
    }

    @Test
    fun `consumePreloadedSource returns null when source not enqueued`() {
        val gate = FakeTrailerPreloadGate()
        val media = mock(MediaSource::class.java)
        gate.markReady(otherSource, media)

        val result = consumePreloadedSourceForAdvance(gate, source)

        assertNull(result)
    }

    @Test
    fun `consumePreloadedSource returns null when gate is empty`() {
        val gate = FakeTrailerPreloadGate()

        val result = consumePreloadedSourceForAdvance(gate, source)

        assertNull(result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testArmv7DebugUnitTest --tests "com.nexio.tv.ui.screensaver.IdleTrailerPreloadDecisionTest"`
Expected: FAIL with "Unresolved reference: consumePreloadedSourceForAdvance".

- [ ] **Step 3: Add the helper to the overlay file**

In `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt`, add at the top-level (outside the composable) near the existing `shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout` helper:

```kotlin
internal fun consumePreloadedSourceForAdvance(
    gate: TrailerPreloadGate,
    source: com.nexio.tv.data.trailer.TrailerPlaybackSource
): androidx.media3.exoplayer.source.MediaSource? {
    return gate.consume(source)
}
```

- [ ] **Step 4: Track the active preloaded MediaSource in overlay state**

Add a state slot inside the composable:

```kotlin
var activePreparedMediaSource by remember(sessionId) {
    mutableStateOf<androidx.media3.exoplayer.source.MediaSource?>(null)
}
```

- [ ] **Step 5: Consume on advance**

Modify the `LaunchedEffect(advanceSignal)` at line 196:

```kotlin
LaunchedEffect(advanceSignal) {
    if (advanceSignal == 0) return@LaunchedEffect
    var nextPlayback = preparedNextPlayback ?: resolveNextIdleTrailerPlayback(
        candidates = sessionStart.candidates,
        currentIndex = currentPlayback.index,
        skippedPlaybackKeys = failedPlaybackKeys,
        resolvePlayback = currentResolvePlayback
    )
    if (nextPlayback == null && failedPlaybackKeys.isNotEmpty()) {
        failedPlaybackKeys = emptySet()
        nextPlayback = resolveNextIdleTrailerPlayback(
            candidates = sessionStart.candidates,
            currentIndex = currentPlayback.index,
            skippedPlaybackKeys = emptySet(),
            resolvePlayback = currentResolvePlayback
        )
    }
    if (nextPlayback == null) {
        currentOnDismiss()
        return@LaunchedEffect
    }
    activePreparedMediaSource = consumePreloadedSourceForAdvance(preloadGate, nextPlayback.source)
    preparedNextPlayback = null
    currentPlayback = nextPlayback
}
```

- [ ] **Step 6: Reset the slot on initial mount (initial trailer has no preload)**

Inside the existing `LaunchedEffect(sessionId, currentPlayback.playbackRef, currentPlayback.index)` block at line 120, add a reset:

```kotlin
LaunchedEffect(sessionId, currentPlayback.playbackRef, currentPlayback.index) {
    hasRenderedFirstFrame = false
    brandingAlpha.stop()
    brandingAlpha.snapTo(1f)
    delay(brandingSpec.visibleMs)
    brandingAlpha.animateTo(
        targetValue = 0f,
        animationSpec = tween(durationMillis = brandingSpec.fadeDurationMs)
    )
}
```

(No change in this block — the `activePreparedMediaSource` reset happens implicitly because we set it on advance. The initial playback enters with `activePreparedMediaSource = null` from `remember`, which is correct — first trailer cold-starts.)

- [ ] **Step 7: Pass the prepared source to `TrailerPlayer`**

At the `TrailerPlayer(...)` call site around line 294, add:

```kotlin
TrailerPlayer(
    trailerUrl = currentPlayback.source.videoUrl,
    trailerAudioUrl = currentPlayback.source.audioUrl,
    trailerUserAgent = currentPlayback.source.userAgent,
    trailerSigningClientKey = currentPlayback.source.signingClientKey,
    trailerCaptions = currentPlayback.source.captions,
    prepreparedMediaSource = activePreparedMediaSource,
    isPlaying = true,
    muted = sessionMuted,
    onEnded = { advanceSignal += 1 },
    // ... (rest unchanged)
```

- [ ] **Step 8: Run unit tests**

Run: `./gradlew :app:testArmv7DebugUnitTest --tests "com.nexio.tv.ui.screensaver.*"`
Expected: PASS (including the 3 new tests in `IdleTrailerPreloadDecisionTest`).

- [ ] **Step 9: Build**

Run: `./gradlew :app:compileArmv7DebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt \
        app/src/test/java/com/nexio/tv/ui/screensaver/IdleTrailerPreloadDecisionTest.kt
git commit -m "feat(trailer/screensaver): consume preloaded MediaSource on advance"
```

---

## Task 8: Add `trailer_screensaver_preload` diagnostic event

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/TrailerPreloadGate.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt`

Per the spec, a single event with `{itemKey, status, elapsedMs}`. Status values: `queued | consumed_hit | consumed_miss`. Preload-side `ready` / `failed` from `BasePreloadManager.Listener` is intentionally not wired in v1 — the spec's failure-handling section delegates to the player's existing `onError` path. Add the listener later only if smoke tests show preload-side errors materially affect rotation feel.

- [ ] **Step 1: Add the emit method**

In `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`, after `emitScreensaverSurfacePublished` (line 373):

```kotlin
fun emitTrailerScreensaverPreload(
    itemKey: String,
    status: String,
    elapsedMs: Long
) {
    emitScreensaverEvent(
        eventType = "trailer_screensaver_preload",
        payload = mapOf(
            "itemKey" to itemKey,
            "status" to status,
            "elapsedMs" to elapsedMs
        )
    )
}
```

- [ ] **Step 2: Emit from the overlay enqueue path**

In `IdleTrailerScreensaverOverlay.kt`, accept a `traceEvents` parameter (or look up via `LocalContext`'s Hilt EntryPoint). The simplest wiring: pass `TraceMetadataEvents` to the overlay from `MainActivity.kt` where it's already injected.

Update the overlay signature:

```kotlin
@Composable
internal fun IdleTrailerScreensaverOverlay(
    sessionId: Long,
    sessionStart: IdleTrailerScreensaverSessionStart,
    onDismiss: () -> Unit,
    onOpenSlide: (IdleTrailerScreensaverCandidate) -> Unit,
    resolvePlaybackSource: suspend (IdleTrailerScreensaverCandidate, TrailerPlaybackRef) -> com.nexio.tv.data.trailer.TrailerPlaybackSource?,
    traceEvents: com.nexio.tv.core.trace.TraceMetadataEvents,
    preloadGateFactory: (android.content.Context) -> TrailerPreloadGate = { ctx ->
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        DefaultTrailerPreloadGate(ctx)
    }
)
```

Find every call site of `IdleTrailerScreensaverOverlay(` (likely only `MainActivity.kt`) and pass the existing injected `traceEvents`.

- [ ] **Step 3: Emit `queued` on enqueue, `consumed_hit`/`consumed_miss` on consume**

In the overlay, change the enqueue location (Task 6 Step 3) to also emit:

```kotlin
LaunchedEffect(sessionId, currentPlayback.index, currentPlayback.playbackRef, sessionStart.candidates, failedPlaybackKeys) {
    val next = resolveNextIdleTrailerPlayback(
        candidates = sessionStart.candidates,
        currentIndex = currentPlayback.index,
        skippedPlaybackKeys = failedPlaybackKeys,
        resolvePlayback = currentResolvePlayback
    )
    preparedNextPlayback = next
    if (next != null) {
        preloadGate.enqueue(next.source)
        traceEvents.emitTrailerScreensaverPreload(
            itemKey = "${next.candidate.itemType}:${next.candidate.itemId}",
            status = "queued",
            elapsedMs = 0L
        )
    }
}
```

In the advance `LaunchedEffect` (Task 7 Step 5), capture `enqueueStartMs` per-candidate (a small map `remember(sessionId) { mutableStateMapOf<String, Long>() }`) and emit consume:

```kotlin
val preloadEnqueueStartMs = remember(sessionId) { mutableStateMapOf<String, Long>() }
```

Update the enqueue effect to also record:

```kotlin
val itemKey = "${next.candidate.itemType}:${next.candidate.itemId}"
preloadEnqueueStartMs[itemKey] = System.currentTimeMillis()
preloadGate.enqueue(next.source)
traceEvents.emitTrailerScreensaverPreload(itemKey = itemKey, status = "queued", elapsedMs = 0L)
```

And the advance effect (Task 7 Step 5):

```kotlin
val itemKey = "${nextPlayback.candidate.itemType}:${nextPlayback.candidate.itemId}"
val prepared = consumePreloadedSourceForAdvance(preloadGate, nextPlayback.source)
val elapsed = preloadEnqueueStartMs.remove(itemKey)?.let { System.currentTimeMillis() - it } ?: 0L
traceEvents.emitTrailerScreensaverPreload(
    itemKey = itemKey,
    status = if (prepared != null) "consumed_hit" else "consumed_miss",
    elapsedMs = elapsed
)
activePreparedMediaSource = prepared
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:compileArmv7DebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run tests**

Run: `./gradlew :app:testArmv7DebugUnitTest --tests "com.nexio.tv.ui.screensaver.*"`
Expected: PASS. (Test signatures need updating — the overlay's `traceEvents` parameter must be supplied. If no overlay-Composable tests exist, no test changes needed.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt \
        app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt \
        app/src/main/java/com/nexio/tv/MainActivity.kt
git commit -m "feat(trailer/screensaver): trailer_screensaver_preload diagnostic event"
```

---

## Task 9: On-device smoke test

**Files:** none — verification only.

Validates the end-to-end behaviour per the spec's testing requirements (rule #8 — profile selection mandatory).

- [ ] **Step 1: Install the build**

```bash
./gradlew :app:installArmv7Debug
```
Expected: `BUILD SUCCESSFUL`. APK installed to all connected devices.

- [ ] **Step 2: Restart app + select profile (CLAUDE.md rule #8)**

```bash
DEVICE=192.168.50.98:5555
adb -s "$DEVICE" shell am force-stop com.nexiodebug.tv
adb -s "$DEVICE" logcat -c
adb -s "$DEVICE" shell monkey -p com.nexiodebug.tv 1 >/dev/null
sleep 5
adb -s "$DEVICE" shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
```
Expected: home screen loaded; verify with a screenshot if needed.

- [ ] **Step 3: Wait for screensaver activation**

The screensaver activates after ~3 minutes of idle. Leave the device untouched. While waiting, tail logs:

```bash
adb -s "$DEVICE" logcat -d | grep -E "IdleScreensaverRepo|trailer_screensaver_preload|FATAL|AndroidRuntime"
```

- [ ] **Step 4: Verify the first trailer plays (cold-start, within 15s timeout)**

After the screensaver activates, the first trailer should show backdrop briefly, then start playing. If it gets skipped, the 15s timeout was insufficient — capture logs and stop.

- [ ] **Step 5: Verify subsequent trailers swap fast**

After the first trailer ends (or after ~30s), the screensaver advances. The second and subsequent trailers should show backdrop for at most ~500ms before playback starts (decoder re-init only).

- [ ] **Step 6: Confirm preload telemetry**

```bash
adb -s "$DEVICE" logcat -d | grep "trailer_screensaver_preload"
```
Expected output pattern: a `queued` event for each next-trailer, followed by `consumed_hit` (mostly) or `consumed_miss` (rarely — only if the user advances faster than preload completes) at advance time.

- [ ] **Step 7: Verify no crashes**

```bash
adb -s "$DEVICE" logcat -d | grep -E "FATAL|AndroidRuntime.*Exception|ANR"
```
Expected: no matches related to `com.nexiodebug.tv` since profile selection.

- [ ] **Step 8: Verify memory stays sane**

```bash
adb -s "$DEVICE" shell dumpsys meminfo com.nexiodebug.tv | head -20
```
Expected: heap total within ~10MB of pre-screensaver baseline (one extra `MediaSource` worth of preload buffer — small).

- [ ] **Step 9: Dismiss + verify cleanup**

Press BACK on the device. Screensaver dismisses. Confirm logcat shows no leaked `DefaultPreloadManager` warnings and that `dumpsys meminfo` returns to baseline.

- [ ] **Step 10: Document evidence**

In the commit message of the last code-touching task, append a line:
> `Smoke: 5 candidate rotations on AM9 PRO — first cold ~6s; subsequent swaps <500ms; no crashes; no telemetry misses.`

(If observed values differ, capture them honestly — the timeout may need further tuning.)

---

## Self-Review

**Spec coverage:**
- ✅ Section "Architecture / New units / `TrailerMediaSourceFactory`" → Task 1
- ✅ "Architecture / `IdleTrailerPreloadManager`" → Tasks 3 + 4 (renamed to `TrailerPreloadGate` — interface + `DefaultTrailerPreloadGate` impl)
- ✅ "`TrailerPlayer` change" → Task 5
- ✅ "Data Flow / Session start + Steady state" → Task 6
- ✅ "Data Flow / Advance" → Task 7
- ✅ "Data Flow / Session end" → Task 6 (DisposableEffect)
- ✅ "Timeout Policy" → Task 2
- ✅ "Edge Cases" → Tasks 6/7 cover preload-in-flight (miss), failed source (existing `failedPlaybackKeys`), `ItemLookup` (no new code), dismiss (DisposableEffect)
- ✅ "Diagnostics" → Task 8
- ✅ "Testing" → Tasks 1/3/7 (JVM unit tests) + Task 9 (on-device smoke)

**Placeholder scan:** no TBDs, no "implement later", every step has either exact code or an exact command. Tasks 8 and 9 reference specific log filters and event field names that match Task 8's emit method.

**Type consistency:** `TrailerPreloadGate` interface used in Tasks 3, 4, 6, 7, 8, with the same `enqueue(source) / consume(source) / release()` signature throughout. `prepreparedMediaSource: MediaSource?` parameter in `TrailerPlayer` introduced in Task 5 and consumed in Task 7. `consumePreloadedSourceForAdvance(gate, source)` helper defined in Task 7 with matching test fixture.

**Naming nit fixed:** spec uses `IdleTrailerPreloadManager`; plan uses `TrailerPreloadGate` (interface) + `DefaultTrailerPreloadGate` (impl). The rename avoids confusion with Media3's `PreloadManager` and clarifies its role as a thin gate, not a full manager rewrite. Spec wording remains correct at the conceptual level.

**Post-audit revisions (2026-05-12):** verified `BasePreloadManager.add(MediaSource, T)` (line 194 of `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/BasePreloadManager.java`) wraps the source in a `MediaSourceHolder` whose `getMediaSource()` returns the preloaded wrapper, not the original. Task 4 now tracks `MediaItem` (via `mediaSource.mediaItem`) and retrieves the prepared source via `preloadManager.getMediaSource(mediaItem)`; without this fix `setMediaSource(originalSource)` would have produced zero speedup. `consume()` now also calls `preloadManager.remove(mediaItem)` to release the `SampleQueue` buffer between rotations — without this, a 40-trailer session would leak 40 holders. Interface KDoc clarified to match implementation (returns partial preloads, returns null only on source mismatch). `specifiedRangeLoaded` switched to the 1-arg idiom. Spec's failure-handling section updated to match the v1 simplification (rely on player `onError` rather than wiring `BasePreloadManager.Listener`).
