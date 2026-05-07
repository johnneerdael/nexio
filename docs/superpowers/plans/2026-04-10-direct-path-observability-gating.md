# Direct Path Observability Gating Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make direct playback carry only the machinery required for playback functionality, while enabling telemetry and debug-feature plumbing only when the corresponding feature is explicitly enabled.

**Architecture:** Split the current playback stack into three concerns: core playback functionality, playback-diagnostics telemetry, and transport-validation debug functionality. Keep the base playback OkHttp client as the default path. Use the traced client and range-tag wrapper only when playback diagnostics is enabled and the path actually needs range telemetry. Keep transport-validation collector work dormant unless a transport-validation session is active. For the direct path specifically, disable all trace-only scaffolding that is not needed for direct playback correctness.

**Tech Stack:** Android/Kotlin, Hilt, OkHttp, Media3, ExoPlayer, DataStore, JUnit4, Robolectric, MockK

---

## Scope

This plan covers one subsystem: playback-path observability gating.

It does **not** change PRDS transport behavior, cache write policy, or decode/render behavior directly. Those stay in separate recovery work. This plan only decides what support machinery is present on each playback path.

## Evidence Snapshot

- `PlayerViewModel` currently injects `@Named("playbackTraced")` unconditionally and threads that client into `PlayerRuntimeController`, so the player stack always starts from the traced client. `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt`
- `PlayerMediaSourceFactory.createMediaSource(...)` always constructs `OkHttpDataSource.Factory(PlaybackRangeContextCallFactory(getOrCreateOkHttpClient()))`, even on the direct path where no PRDS `RangeContext` is needed. `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Playback trace session creation is already gated by `PlaybackTracer.enabled`, but the traced client, range-tagging wrapper, and cache event listener attachment still exist structurally even when diagnostics is off. `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- `transportValidationRuntimeCollector.attachPlayer(...)` and `onPrepareRequested(...)` are called on normal playback initialization even when there is no active transport-validation session. `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

## File Map

### Production files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
  - keep both playback clients available from DI, with the base client as the default functional client and the traced client as the diagnostics-only variant
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt`
  - inject both playback clients instead of only the traced one
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
  - thread both clients into `PlayerMediaSourceFactory`
  - only configure trace-session binding when diagnostics and validation correlation are actually active
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - avoid transport-validation collector calls on ordinary playback when no validation session is active
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - select base vs traced client by playback-path need
  - only use `PlaybackRangeContextCallFactory` when the path requires range telemetry
  - only attach `PlaybackTraceCacheEventListener` when playback diagnostics is enabled
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt`
  - expose session-active state
  - only activate runtime-validation machinery when the transport-validation feature is actually active
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationSessionStore.kt`
  - avoid starting runtime validation when transport validation is disabled
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationPlaybackLauncher.kt`
  - refuse playback launch when the debug feature is disabled instead of half-starting validation plumbing
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md`
  - document which playback paths use the traced client and which direct-path scaffolding is intentionally disabled

### Test files

- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerPlaybackObservabilityModeTest.kt`
  - pin base-vs-traced client selection and direct-path observability gating
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - pin cache listener attachment and direct-path factory behavior
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitializationTest.kt`
  - pin that transport-validation collector callbacks are skipped on ordinary playback
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollectorTest.kt`
  - pin runtime collector activation only when transport validation is active

## Guardrails

- Do not remove playback diagnostics as a feature.
- Do not remove transport validation as a feature.
- Do not route direct playback through traced-range machinery unless direct playback genuinely needs it for functionality.
- Keep the direct path able to use the base playback client regardless of playback-diagnostics state.
- Keep correlation between playback diagnostics and transport validation only when both features are active at the same time.

---

### Task 1: Split Base Playback Functionality From Diagnostics-Only Client Selection

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerPlaybackObservabilityModeTest.kt`

- [ ] **Step 1: Write the failing observability-mode tests**

```kotlin
package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerPlaybackObservabilityModeTest {

    @Test
    fun `direct path uses base client when playback diagnostics is off`() {
        val mode = PlayerMediaSourceFactory.resolvePlaybackClientMode(
            useParallelConnections = false,
            playbackTraceEnabled = false
        )

        assertEquals(PlayerMediaSourceFactory.PlaybackClientMode.BASE, mode)
    }

    @Test
    fun `direct path still uses base client when playback diagnostics is on`() {
        val mode = PlayerMediaSourceFactory.resolvePlaybackClientMode(
            useParallelConnections = false,
            playbackTraceEnabled = true
        )

        assertEquals(PlayerMediaSourceFactory.PlaybackClientMode.BASE, mode)
    }

    @Test
    fun `parallel path uses traced range client only when playback diagnostics is on`() {
        val mode = PlayerMediaSourceFactory.resolvePlaybackClientMode(
            useParallelConnections = true,
            playbackTraceEnabled = true
        )

        assertEquals(PlayerMediaSourceFactory.PlaybackClientMode.TRACED_RANGE, mode)
    }
}
```

- [ ] **Step 2: Run the focused observability-mode tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerPlaybackObservabilityModeTest"`

Expected before implementation: FAIL because there is no explicit playback-client mode abstraction and the traced client is injected unconditionally.

- [ ] **Step 3: Add an explicit playback client mode to `PlayerMediaSourceFactory`**

```kotlin
internal enum class PlaybackClientMode {
    BASE,
    TRACED_RANGE,
}

companion object {
    internal fun resolvePlaybackClientMode(
        useParallelConnections: Boolean,
        playbackTraceEnabled: Boolean
    ): PlaybackClientMode {
        return if (useParallelConnections && playbackTraceEnabled) {
            PlaybackClientMode.TRACED_RANGE
        } else {
            PlaybackClientMode.BASE
        }
    }
}
```

- [ ] **Step 4: Thread both clients from DI into the controller and factory**

```kotlin
@HiltViewModel
class PlayerViewModel @Inject constructor(
    // ...
    @Named("playback") private val playbackOkHttpClient: OkHttpClient,
    @Named("playbackTraced") private val playbackTracedOkHttpClient: OkHttpClient,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val controller = PlayerRuntimeController(
        // ...
        playbackOkHttpClient = playbackOkHttpClient,
        playbackTracedOkHttpClient = playbackTracedOkHttpClient,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope
    )
}
```

```kotlin
class PlayerRuntimeController(
    // ...
    internal val playbackOkHttpClient: OkHttpClient,
    internal val playbackTracedOkHttpClient: OkHttpClient,
    savedStateHandle: SavedStateHandle,
    internal val scope: CoroutineScope
) {
    internal val mediaSourceFactory = PlayerMediaSourceFactory(
        context = context.applicationContext,
        basePlaybackOkHttpClient = playbackOkHttpClient,
        tracedPlaybackOkHttpClient = playbackTracedOkHttpClient
    )
}
```

- [ ] **Step 5: Re-run the focused observability-mode tests**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerPlaybackObservabilityModeTest"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerPlaybackObservabilityModeTest.kt
git commit -m "refactor: split playback functionality from diagnostics client selection"
```

---

### Task 2: Disable Trace-Only Scaffolding On The Direct Path

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write the failing direct-path gating tests**

```kotlin
@Test
fun `cache trace listener attaches only when playback diagnostics is enabled`() {
    assertFalse(
        PlayerMediaSourceFactory.shouldAttachCacheTraceListener(
            playbackTraceEnabled = false
        )
    )
    assertTrue(
        PlayerMediaSourceFactory.shouldAttachCacheTraceListener(
            playbackTraceEnabled = true
        )
    )
}

@Test
fun `direct path does not use range context wrapper when diagnostics are enabled`() {
    val mode = PlayerMediaSourceFactory.resolvePlaybackClientMode(
        useParallelConnections = false,
        playbackTraceEnabled = true
    )

    assertEquals(PlayerMediaSourceFactory.PlaybackClientMode.BASE, mode)
}
```

- [ ] **Step 2: Run the focused factory tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.PlayerPlaybackObservabilityModeTest"`

Expected before implementation: FAIL because cache listener attachment is unconditional and the factory always wraps OkHttp in `PlaybackRangeContextCallFactory`.

- [ ] **Step 3: Select the actual call factory from the playback client mode**

```kotlin
private fun createOkHttpDataSourceFactory(
    headers: Map<String, String>,
    clientMode: PlaybackClientMode
): OkHttpDataSource.Factory {
    val callFactory = when (clientMode) {
        PlaybackClientMode.BASE -> basePlaybackOkHttpClient
        PlaybackClientMode.TRACED_RANGE ->
            PlaybackRangeContextCallFactory(tracedPlaybackOkHttpClient)
    }

    return OkHttpDataSource.Factory(callFactory).apply {
        setDefaultRequestProperties(headers)
        if (!headers.containsKey("User-Agent")) {
            setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        }
    }
}
```

- [ ] **Step 4: Attach cache trace listener only when diagnostics are enabled**

```kotlin
internal fun shouldAttachCacheTraceListener(playbackTraceEnabled: Boolean): Boolean {
    return playbackTraceEnabled
}

private fun buildVodCacheDataSourceFactory(
    upstreamFactory: DataSource.Factory,
    cache: SimpleCache,
    blockOnCache: Boolean = false,
    playbackTraceEnabled: Boolean = PlaybackTracer.enabled
): DataSource.Factory {
    val dataSinkFactory = CacheDataSink.Factory()
        .setCache(cache)
        .setFragmentSize(2L * 1024L * 1024L)

    var flags = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
    if (blockOnCache) {
        flags = flags or CacheDataSource.FLAG_BLOCK_ON_CACHE
    }

    return CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory(stableCacheKeyFactory)
        .setCacheWriteDataSinkFactory(dataSinkFactory)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .apply {
            if (shouldAttachCacheTraceListener(playbackTraceEnabled)) {
                setEventListener(
                    PlaybackTraceCacheEventListener(
                        if (blockOnCache) "warm_ahead" else "progressive"
                    )
                )
            }
        }
        .setFlags(flags)
}
```

- [ ] **Step 5: Re-run the focused factory tests**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.PlayerPlaybackObservabilityModeTest"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerPlaybackObservabilityModeTest.kt
git commit -m "fix: disable trace-only scaffolding on the direct path"
```

---

### Task 3: Activate Transport-Validation Runtime Plumbing Only When The Feature Is Active

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationSessionStore.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationPlaybackLauncher.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollectorTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitializationTest.kt`

- [ ] **Step 1: Write the failing transport-validation activation tests**

```kotlin
package com.nexio.tv.debug.passthrough

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransportValidationRuntimeCollectorTest {

    private fun sample() = TransportValidationSample(
        id = "sample-1",
        displayName = "Sample",
        codecFamily = TransportValidationCodecFamily.TRUEHD,
        sourceAssetPath = "source.bin",
        referenceAssetPath = "reference.bin",
        expectedPc = 16,
        pdRule = TransportValidationPdRule.EXACT_REFERENCE_MATCH,
        expectedBurstModel = TransportValidationBurstModel.IEC_BURST,
        expectedRouteTuple = TransportValidationRouteTuple(
            encoding = "ENCODING_IEC61937",
            sampleRate = 48_000,
            channelMask = "CHANNEL_OUT_5POINT1"
        ),
        assetChecksums = mapOf("referenceAssetPath" to "abc123")
    )

    @Test
    fun `collector stays inactive when transport validation feature is disabled`() {
        val collector = TransportValidationRuntimeCollector(
            ApplicationProvider.getApplicationContext()
        )

        collector.beginSession(
            sample = sample(),
            settings = TransportValidationSettings(
                enabled = false,
                runtimeValidationEnabled = true
            )
        )

        assertFalse(collector.isSessionActive())
    }

    @Test
    fun `collector becomes active only when transport validation is enabled`() {
        val collector = TransportValidationRuntimeCollector(
            ApplicationProvider.getApplicationContext()
        )

        collector.beginSession(
            sample = sample(),
            settings = TransportValidationSettings(
                enabled = true,
                runtimeValidationEnabled = true
            )
        )

        assertTrue(collector.isSessionActive())
    }
}
```

- [ ] **Step 2: Run the focused transport-validation tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.debug.passthrough.TransportValidationRuntimeCollectorTest" --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerInitializationTest"`

Expected before implementation: FAIL because `beginSession(...)` currently starts validation machinery regardless of `settings.enabled`, and normal playback initialization always calls `attachPlayer(...)` / `onPrepareRequested(...)`.

- [ ] **Step 3: Add session-active gating to the runtime collector**

```kotlin
fun isSessionActive(): Boolean = activeSession != null

fun beginSession(
    sample: TransportValidationSample,
    settings: TransportValidationSettings,
) {
    clearSession()
    if (!settings.enabled) {
        return
    }

    if (settings.runtimeValidationEnabled) {
        deviceHealthSampler.start()
    }

    activeSession =
        ActiveRuntimeSession(
            sampleId = sample.id,
            sourceAssetPath = sample.sourceAssetPath,
            enabled = settings.runtimeValidationEnabled,
            thresholds =
                TransportValidationRuntimeDefaults.thresholds.copy(
                    startupTimeoutMs = settings.runtimeStartupTimeoutMs.toLong(),
                    observationWindowMs = settings.runtimeObservationWindowMs.toLong(),
                ),
            startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            operatorObservation = pendingOperatorObservation,
        )
}
```

- [ ] **Step 4: Refuse transport-validation playback/session startup when the feature is disabled**

```kotlin
suspend fun startSession(preparedSample: PreparedTransportValidationSample): TransportValidationSessionSnapshot? {
    val settings = settingsStore.transportValidationSettings.first()
    if (!settings.enabled) {
        Log.w(TAG, "startSession rejected because transport validation is disabled")
        return null
    }
    // existing session startup follows
}
```

```kotlin
suspend fun launchSelectedSample(sampleId: String): Boolean {
    val session = sessionStore.startSession(preparedSample) ?: return false
    // existing launch path follows
}
```

- [ ] **Step 5: Skip runtime-collector calls on ordinary playback when no validation session is active**

```kotlin
addListener(playerListener)
if (transportValidationRuntimeCollector.isSessionActive()) {
    transportValidationRuntimeCollector.attachPlayer(this, url)
}
// ...
setMediaSource(initialMediaSource)
playWhenReady = true
if (transportValidationRuntimeCollector.isSessionActive()) {
    transportValidationRuntimeCollector.onPrepareRequested(url)
}
prepare()
```

- [ ] **Step 6: Re-run the focused transport-validation tests**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.debug.passthrough.TransportValidationRuntimeCollectorTest" --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerInitializationTest"`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt
git add app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationSessionStore.kt
git add app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationPlaybackLauncher.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
git add app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollectorTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitializationTest.kt
git commit -m "fix: only activate transport validation runtime when enabled"
```

---

### Task 4: Document And Verify Direct-Path Gating End To End

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt`

- [ ] **Step 1: Write the failing direct-path regression test**

```kotlin
@Test
fun `direct path never resolves traced range mode`() {
    val mode = PlayerMediaSourceFactory.resolvePlaybackClientMode(
        useParallelConnections = false,
        playbackTraceEnabled = true
    )

    assertEquals(PlayerMediaSourceFactory.PlaybackClientMode.BASE, mode)
}
```

- [ ] **Step 2: Run the focused direct-path regression test**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest"`

Expected before implementation: FAIL until the earlier tasks land.

- [ ] **Step 3: Document the direct-path gating rules**

```md
## Direct-path observability rules

- Direct playback uses the base playback OkHttp client.
- Direct playback does not use the traced range client.
- `PlaybackRangeContextCallFactory` is only used for traced parallel-range playback.
- Cache event listeners are attached only when playback diagnostics is enabled.
- Transport-validation runtime hooks are active only during an enabled transport-validation session.
```

- [ ] **Step 4: Run the final focused verification suite**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerPlaybackObservabilityModeTest" --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest" --tests "com.nexio.tv.debug.passthrough.TransportValidationRuntimeCollectorTest" --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerInitializationTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add docs/instrumentation/collecting-traces.md
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt
git commit -m "docs: codify direct path observability gating"
```

---

## Acceptance Criteria

- Direct playback uses the base playback client, not the traced client.
- Direct playback never wraps OkHttp with `PlaybackRangeContextCallFactory`.
- Trace-only cache listeners are attached only when playback diagnostics is enabled.
- Playback trace session/header creation remains available when diagnostics is enabled.
- Transport-validation runtime collection does not activate unless transport validation is enabled and a validation session is active.
- Normal playback initialization no longer calls transport-validation collector hooks when no validation session exists.
- The plan preserves playback functionality while reducing direct-path observability overhead.
