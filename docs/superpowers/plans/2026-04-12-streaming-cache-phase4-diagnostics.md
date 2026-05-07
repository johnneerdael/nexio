# Streaming Cache Phase 4 Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add developer-only diagnostics that isolate whether Phase 4 stutter is caused by coverage-aware playback, background fill contention, or legacy cache pressure.

**Architecture:** Introduce an explicit streaming-cache transport mode that selects one of three ON-path variants: Phase 4 coverage-aware playback with fill, Phase 3 read-only `CacheDataSource` playback with fill, or coverage-aware playback without fill. Add minimal log/counter snapshots for fallback/fill behavior and a separate one-time legacy `player_vod_cache_v2` cleanup path.

**Tech Stack:** Kotlin, AndroidX Media3 `DataSource` / `CacheDataSource` / `SimpleCache`, Android DataStore preferences, Jetpack Compose TV settings UI, Robolectric unit tests, ADB validation on `192.168.50.58`.

---

## Context And Current Evidence

- OFF baseline on `192.168.50.58` is smooth for the same remux source.
- Effective ON shows `CacheFill-0`, `STREAM_CACHE_FILL start`, `cache/stream-cache` growth, repeated playback buffering, audio underruns, GC pressure, and SIGNALED exits.
- A previous fix bounded open-ended fallback ownership so the fill worker can proceed beyond the active fallback window, but stutter persists.
- Current legacy cache `cache/player_vod_cache_v2` remains around `185 MB` on `50.58`. This is disk pressure / hygiene, not direct Java heap, and must be handled separately.
- Do not make a blind rollback. The next change must let validation isolate:
  - coverage-aware playback cost
  - fill worker network/disk contention
  - legacy disk footprint
  - unrelated baseline/device/provider pressure

## File Structure

- Create: `app/src/main/java/com/nexio/tv/data/local/StreamingCacheDebugMode.kt`
  - Enum persisted by debug settings and consumed by player wiring.
- Modify: `app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt`
  - Persist selected diagnostic mode.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt`
  - Expose current mode and cycle action.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt`
  - Add a developer-only mode card under Streaming Cache toggle.
- Modify: `app/src/main/res/values/strings.xml`
  - Add labels for diagnostic mode card.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
  - Select Phase 4 coverage-aware path vs Phase 3 read-only cache path.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Store the mode, gate fill startup for coverage-only mode, and expose current mode for tests.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
  - Observe mode DataStore alongside enabled state.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Read the selected mode before creating the player.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingMetrics.kt`
  - Add bounded counters for coverage/fill diagnostics.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSource.kt`
  - Increment counters for fallback segment opens/urgent waits/cache hits/misses.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt`
  - Increment counters for fill chunk starts, successful writes, and fallback-owned skips.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/LegacyStreamingCacheCleanup.kt`
  - Deletes stale `player_vod_cache_v2` safely at app startup.
- Modify: `app/src/main/java/com/nexio/tv/NexioApplication.kt`
  - Run legacy cache cleanup in existing IO startup scope.
- Test: `app/src/test/java/com/nexio/tv/data/local/StreamingCacheDebugModeTest.kt`
- Modify tests: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
- Modify tests: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStreamingCacheFillWiringTest.kt`
- Modify tests: `app/src/test/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSourceTest.kt`
- Modify tests: `app/src/test/java/com/nexio/tv/ui/screens/player/CacheFillWorkerTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/LegacyStreamingCacheCleanupTest.kt`

## Diagnostic Modes

```kotlin
enum class StreamingCacheDebugMode(val storageValue: String, val label: String) {
    PHASE4_COVERAGE_WITH_FILL("phase4_coverage_with_fill", "Phase 4: coverage + fill"),
    PHASE3_CACHE_WITH_FILL("phase3_cache_with_fill", "Phase 3: cache + fill"),
    COVERAGE_ONLY("coverage_only", "Coverage only: no fill");

    fun next(): StreamingCacheDebugMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    companion object {
        fun fromStorageValue(value: String?): StreamingCacheDebugMode {
            return entries.firstOrNull { it.storageValue == value } ?: PHASE4_COVERAGE_WITH_FILL
        }
    }
}
```

Mode behavior:

```text
streaming_cache_enabled=false:
  Plain upstream DefaultDataSource/OkHttp path. No SimpleCache opened. No fill worker.

PHASE4_COVERAGE_WITH_FILL:
  CoverageAwareDataSource playback + CacheFillWorker background fill.

PHASE3_CACHE_WITH_FILL:
  Phase 3 read-only CacheDataSource playback with upstream fallback + CacheFillWorker background fill.
  Playback still does not write to cache because setCacheWriteDataSinkFactory(null) remains set.

COVERAGE_ONLY:
  CoverageAwareDataSource playback + no CacheFillWorker.
```

---

### Task 1: Add Streaming Cache Diagnostic Mode Setting

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/StreamingCacheDebugMode.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/java/com/nexio/tv/data/local/StreamingCacheDebugModeTest.kt`

- [ ] **Step 1: Write failing enum tests**

Create `app/src/test/java/com/nexio/tv/data/local/StreamingCacheDebugModeTest.kt`:

```kotlin
package com.nexio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingCacheDebugModeTest {

    @Test
    fun fromStorageValue_defaultsToPhase4CoverageWithFill() {
        assertEquals(
            StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL,
            StreamingCacheDebugMode.fromStorageValue(null)
        )
        assertEquals(
            StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL,
            StreamingCacheDebugMode.fromStorageValue("unknown")
        )
    }

    @Test
    fun next_cyclesThroughDiagnosticModes() {
        assertEquals(
            StreamingCacheDebugMode.PHASE3_CACHE_WITH_FILL,
            StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL.next()
        )
        assertEquals(
            StreamingCacheDebugMode.COVERAGE_ONLY,
            StreamingCacheDebugMode.PHASE3_CACHE_WITH_FILL.next()
        )
        assertEquals(
            StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL,
            StreamingCacheDebugMode.COVERAGE_ONLY.next()
        )
    }
}
```

- [ ] **Step 2: Run enum test to verify it fails**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.local.StreamingCacheDebugModeTest
```

Expected: FAIL with unresolved reference `StreamingCacheDebugMode`.

- [ ] **Step 3: Add enum**

Create `app/src/main/java/com/nexio/tv/data/local/StreamingCacheDebugMode.kt`:

```kotlin
package com.nexio.tv.data.local

enum class StreamingCacheDebugMode(
    val storageValue: String,
    val label: String
) {
    PHASE4_COVERAGE_WITH_FILL(
        storageValue = "phase4_coverage_with_fill",
        label = "Phase 4: coverage + fill"
    ),
    PHASE3_CACHE_WITH_FILL(
        storageValue = "phase3_cache_with_fill",
        label = "Phase 3: cache + fill"
    ),
    COVERAGE_ONLY(
        storageValue = "coverage_only",
        label = "Coverage only: no fill"
    );

    fun next(): StreamingCacheDebugMode {
        val modes = entries
        return modes[(ordinal + 1) % modes.size]
    }

    companion object {
        fun fromStorageValue(value: String?): StreamingCacheDebugMode {
            return entries.firstOrNull { mode -> mode.storageValue == value }
                ?: PHASE4_COVERAGE_WITH_FILL
        }
    }
}
```

- [ ] **Step 4: Add DataStore mode flow and setter**

Modify `app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt` imports:

```kotlin
import androidx.datastore.preferences.core.stringPreferencesKey
```

Add the key near `streamingCacheEnabledKey`:

```kotlin
    private val streamingCacheDebugModeKey = stringPreferencesKey("streaming_cache_debug_mode")
```

Add the flow near `streamingCacheEnabled`:

```kotlin
    val streamingCacheDebugMode: Flow<StreamingCacheDebugMode> = dataStore.data.map { prefs ->
        StreamingCacheDebugMode.fromStorageValue(prefs[streamingCacheDebugModeKey])
    }
```

Add the setter near `setStreamingCacheEnabled`:

```kotlin
    suspend fun setStreamingCacheDebugMode(mode: StreamingCacheDebugMode) {
        dataStore.edit { prefs ->
            prefs[streamingCacheDebugModeKey] = mode.storageValue
        }
    }
```

- [ ] **Step 5: Add ViewModel state and event**

Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt` imports:

```kotlin
import com.nexio.tv.data.local.StreamingCacheDebugMode
```

Add collection in `init` after `streamingCacheEnabled`:

```kotlin
        viewModelScope.launch {
            dataStore.streamingCacheDebugMode.collectLatest { mode ->
                _uiState.update { it.copy(streamingCacheDebugMode = mode) }
            }
        }
```

Add event handling:

```kotlin
            is DebugSettingsEvent.CycleStreamingCacheDebugMode -> {
                viewModelScope.launch {
                    dataStore.setStreamingCacheDebugMode(_uiState.value.streamingCacheDebugMode.next())
                }
            }
```

Add state field:

```kotlin
    val streamingCacheDebugMode: StreamingCacheDebugMode =
        StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL,
```

Add event:

```kotlin
    data object CycleStreamingCacheDebugMode : DebugSettingsEvent()
```

- [ ] **Step 6: Add debug screen action card and strings**

Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt` immediately after the Streaming Cache toggle item:

```kotlin
            item(key = "debug_streaming_cache_mode") {
                DebugActionCard(
                    title = stringResource(R.string.debug_streaming_cache_mode_title),
                    subtitle = stringResource(
                        R.string.debug_streaming_cache_mode_subtitle,
                        uiState.streamingCacheDebugMode.label
                    ),
                    onClick = {
                        viewModel.onEvent(DebugSettingsEvent.CycleStreamingCacheDebugMode)
                    }
                )
            }
```

Add strings to `app/src/main/res/values/strings.xml` near existing streaming-cache strings:

```xml
    <string name="debug_streaming_cache_mode_title">Streaming Cache Mode</string>
    <string name="debug_streaming_cache_mode_subtitle">Current diagnostic mode: %1$s</string>
```

- [ ] **Step 7: Run tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.local.StreamingCacheDebugModeTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/local/StreamingCacheDebugMode.kt app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt app/src/main/res/values/strings.xml app/src/test/java/com/nexio/tv/data/local/StreamingCacheDebugModeTest.kt
git commit -m "feat: add streaming cache diagnostic mode"
```

---

### Task 2: Wire Diagnostic Modes Into Playback And Fill

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write failing tests for diagnostic mode routing**

Append to `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`:

```kotlin
    @Test
    fun playbackNetworking_phase3ModeUsesReadOnlyCacheDataSource() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "stream-cache-phase3-mode-${System.nanoTime()}"
        )

        val factory = PlayerPlaybackNetworking.createDataSourceFactory(
            context = context,
            client = OkHttpClient(),
            streamingCacheProvider = provider,
            useStreamingCache = true,
            streamingCacheDebugMode =
                com.nexio.tv.data.local.StreamingCacheDebugMode.PHASE3_CACHE_WITH_FILL
        )
        val dataSource = factory.createDataSource()

        assertTrue(dataSource is androidx.media3.datasource.cache.CacheDataSource)
        assertTrue(provider.hasCacheInstance)

        provider.release()
        provider.cacheDirectory.deleteRecursively()
    }

    @Test
    fun playbackNetworking_phase4ModeUsesCoverageAwareDataSource() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "stream-cache-phase4-mode-${System.nanoTime()}"
        )

        val factory = PlayerPlaybackNetworking.createDataSourceFactory(
            context = context,
            client = OkHttpClient(),
            streamingCacheProvider = provider,
            useStreamingCache = true,
            streamingCacheDebugMode =
                com.nexio.tv.data.local.StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL
        )
        val dataSource = factory.createDataSource()

        assertTrue(dataSource is CoverageAwareDataSource)
        assertTrue(provider.hasCacheInstance)

        provider.release()
        provider.cacheDirectory.deleteRecursively()
    }

    @Test
    fun startStreamingCacheFill_coverageOnlyDoesNotStartFillWorker() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "stream-cache-coverage-only-${System.nanoTime()}"
        )
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = OkHttpClient(),
            streamingCacheProvider = provider
        )
        factory.streamingCacheEnabled = true
        factory.streamingCacheDebugMode =
            com.nexio.tv.data.local.StreamingCacheDebugMode.COVERAGE_ONLY

        factory.startStreamingCacheFill(
            url = "https://example.com/movie.mkv",
            headers = emptyMap(),
            contentLength = 1024L,
            playbackByteProvider = { 0L }
        )

        assertFalse(factory.hasActiveFillSession)

        factory.shutdown()
        provider.cacheDirectory.deleteRecursively()
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: FAIL with missing `streamingCacheDebugMode` parameters/properties.

- [ ] **Step 3: Wire mode into `PlayerPlaybackNetworking`**

Modify imports:

```kotlin
import com.nexio.tv.data.local.StreamingCacheDebugMode
```

Add parameter:

```kotlin
        streamingCacheDebugMode: StreamingCacheDebugMode =
            StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL,
```

After `val cache = streamingCacheProvider.getOrCreateCache()`, add:

```kotlin
        if (streamingCacheDebugMode == StreamingCacheDebugMode.PHASE3_CACHE_WITH_FILL) {
            return CacheDataSource.Factory()
                .setCache(cache)
                .setCacheKeyFactory(cacheKeyFactory)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
```

Leave existing `CoverageAwareDataSource.Factory` path for `PHASE4_COVERAGE_WITH_FILL` and `COVERAGE_ONLY`.

- [ ] **Step 4: Wire mode into `PlayerMediaSourceFactory`**

Add import:

```kotlin
import com.nexio.tv.data.local.StreamingCacheDebugMode
```

Add property near `streamingCacheEnabled`:

```kotlin
    var streamingCacheDebugMode: StreamingCacheDebugMode =
        StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL
```

Pass to networking:

```kotlin
                streamingCacheDebugMode = streamingCacheDebugMode,
```

Gate fill start at the beginning of `startStreamingCacheFill(...)`, after the existing enabled/http/content-length guard:

```kotlin
        if (streamingCacheDebugMode == StreamingCacheDebugMode.COVERAGE_ONLY) {
            Log.d(TAG, "STREAM_CACHE_FILL skip mode=$streamingCacheDebugMode")
            stopStreamingCacheFill()
            return
        }
```

- [ ] **Step 5: Wire mode into runtime controller**

Modify `PlayerRuntimeControllerInitialization.kt`:

```kotlin
            val streamingCacheDebugMode =
                debugSettingsDataStore.streamingCacheDebugMode.first()
            mediaSourceFactory.streamingCacheDebugMode = streamingCacheDebugMode
```

Place this before `createMediaSource(...)`.

Modify `PlayerRuntimeController.kt` observer to combine three flows:

```kotlin
            combine(
                debugSettingsDataStore.streamingCacheEnabled,
                debugSettingsDataStore.streamingCacheManualEnableTimestampMs,
                debugSettingsDataStore.streamingCacheDebugMode
            ) { enabled, manualEnableTimestampMs, mode ->
                Triple(enabled, manualEnableTimestampMs, mode)
            }.collect { (enabled, manualEnableTimestampMs, mode) ->
                mediaSourceFactory.streamingCacheDebugMode = mode
                val decision = StreamingCacheKillSwitch.evaluate(
                    context = context,
                    requested = enabled,
                    manualEnableTimestampMs = manualEnableTimestampMs
                )
                mediaSourceFactory.streamingCacheEnabled = decision.enabled
                if (!decision.enabled) {
                    mediaSourceFactory.stopStreamingCacheFill()
                }
                if (enabled && decision.blockedByKillSwitch) {
                    debugSettingsDataStore.setStreamingCacheEnabled(false)
                }
            }
```

Remove the two-flow `combine` block.

- [ ] **Step 6: Run tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest --tests com.nexio.tv.ui.screens.player.PlayerStreamingCacheFillWiringTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: route streaming cache diagnostic modes"
```

---

### Task 3: Add Minimal Streaming Cache Diagnostic Counters

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingMetrics.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSource.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSourceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/CacheFillWorkerTest.kt`

- [ ] **Step 1: Add failing metrics snapshot tests**

Append to `CoverageAwareDataSourceTest.kt`:

```kotlin
    @Test
    fun metrics_countFallbackSegmentOpensAndBytes() {
        StreamingMetrics.reset()
        val uri = Uri.parse("https://example.com/movie.mkv")
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(4L).build()
        val source = dataSource(
            upstream = FakeDataSource(byteArrayOf(1, 2, 3, 4), AtomicInteger(0)),
            startup = true
        )

        source.open(spec)
        source.close()

        val snapshot = StreamingMetrics.snapshot()
        assertEquals(1L, snapshot.getValue("coverage_fallback_segment_opens"))
        assertEquals(4L, snapshot.getValue("coverage_fallback_bytes_requested"))
    }
```

Append to `CacheFillWorkerTest.kt`:

```kotlin
    @Test
    fun metrics_countFillChunkStarts() {
        StreamingMetrics.reset()
        val data = ByteArray(64) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-63/64")
                .setBody(BufferFactory.body(data))
        )
        val worker = worker(cacheKey = "movie-fill-metrics")

        worker.downloadChunkToCache(
            url = server.url("/movie").toString(),
            headers = emptyMap(),
            start = 0L,
            end = data.size.toLong()
        )

        assertEquals(
            1L,
            StreamingMetrics.snapshot().getValue("fill_worker_chunk_starts")
        )
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.CoverageAwareDataSourceTest --tests com.nexio.tv.ui.screens.player.CacheFillWorkerTest
```

Expected: FAIL with missing `StreamingMetrics.reset()` and new metric keys.

- [ ] **Step 3: Extend `StreamingMetrics`**

Modify `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingMetrics.kt`:

```kotlin
    val coverageFallbackSegmentOpens = AtomicLong(0L)
    val coverageFallbackBytesRequested = AtomicLong(0L)
    val coverageUrgentFillAttempts = AtomicLong(0L)
    val coverageUrgentFillSuccesses = AtomicLong(0L)
    val fillWorkerChunkStarts = AtomicLong(0L)
    val fillWorkerFallbackOwnedSkips = AtomicLong(0L)
```

Add them to `snapshot()`:

```kotlin
            "coverage_fallback_segment_opens" to coverageFallbackSegmentOpens.get(),
            "coverage_fallback_bytes_requested" to coverageFallbackBytesRequested.get(),
            "coverage_urgent_fill_attempts" to coverageUrgentFillAttempts.get(),
            "coverage_urgent_fill_successes" to coverageUrgentFillSuccesses.get(),
            "fill_worker_chunk_starts" to fillWorkerChunkStarts.get(),
            "fill_worker_fallback_owned_skips" to fillWorkerFallbackOwnedSkips.get()
```

Add reset helper:

```kotlin
    fun reset() {
        cacheHits.set(0L)
        cacheMisses.set(0L)
        fillWorkerBytesWritten.set(0L)
        fallbackReadsTriggered.set(0L)
        coordinatorWaitTimeouts.set(0L)
        fillWorkerPauseCount.set(0L)
        urgentFillRequests.set(0L)
        coverageFallbackSegmentOpens.set(0L)
        coverageFallbackBytesRequested.set(0L)
        coverageUrgentFillAttempts.set(0L)
        coverageUrgentFillSuccesses.set(0L)
        fillWorkerChunkStarts.set(0L)
        fillWorkerFallbackOwnedSkips.set(0L)
    }
```

- [ ] **Step 4: Increment metrics in coverage and fill paths**

In `CoverageAwareDataSource.openHoleSegment(...)`, before urgent fill:

```kotlin
        if (allowUrgentFill && !isStartupProvider()) {
            StreamingMetrics.coverageUrgentFillAttempts.incrementAndGet()
            val urgentLength = segmentLength.coerceAtMost(CacheFillWorker.URGENT_FRAGMENT_SIZE)
            val timeoutMs = computeWaitTimeoutMs()
            if (coordinator.requestUrgentFill(cacheKey, spec.position, urgentLength, timeoutMs)) {
                StreamingMetrics.coverageUrgentFillSuccesses.incrementAndGet()
                openNextSegment(allowUrgentFill = false)
                return
            }
        }
```

Before `val token = coordinator.markFallbackOwned(...)`:

```kotlin
        StreamingMetrics.coverageFallbackSegmentOpens.incrementAndGet()
        StreamingMetrics.coverageFallbackBytesRequested.addAndGet(segmentLength)
```

In `CacheFillWorker.downloadChunkToCache(...)`, after `if (end <= start)` block and before cache checks:

```kotlin
        StreamingMetrics.fillWorkerChunkStarts.incrementAndGet()
```

In `CacheFillWorker.run(...)`, where `rangeCoordinator.isOwnedByPlaybackFallback(start, end)` causes a skip:

```kotlin
                    StreamingMetrics.fillWorkerFallbackOwnedSkips.incrementAndGet()
```

Also increment in the `result.bytesWritten <= 0L` fallback-owned branch.

- [ ] **Step 5: Run tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.CoverageAwareDataSourceTest --tests com.nexio.tv.ui.screens.player.CacheFillWorkerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/StreamingMetrics.kt app/src/main/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSource.kt app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt app/src/test/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSourceTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/CacheFillWorkerTest.kt
git commit -m "feat: add streaming cache diagnostic counters"
```

---

### Task 4: Clean Legacy `player_vod_cache_v2`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/LegacyStreamingCacheCleanup.kt`
- Modify: `app/src/main/java/com/nexio/tv/NexioApplication.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/LegacyStreamingCacheCleanupTest.kt`

- [ ] **Step 1: Write failing cleanup tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/LegacyStreamingCacheCleanupTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyStreamingCacheCleanupTest {

    @Test
    fun cleanupDeletesLegacyPlayerVodCacheDirectory() {
        val root = createTempDirectory("legacy-cache-cleanup").toFile()
        val legacy = File(root, "player_vod_cache_v2").apply {
            mkdirs()
            resolve("old.exo").writeText("old")
        }

        LegacyStreamingCacheCleanup.cleanup(root)

        assertFalse(legacy.exists())
    }

    @Test
    fun cleanupLeavesCurrentStreamCacheDirectory() {
        val root = createTempDirectory("legacy-cache-cleanup-current").toFile()
        val current = File(root, "stream-cache").apply {
            mkdirs()
            resolve("current.exo").writeText("current")
        }

        LegacyStreamingCacheCleanup.cleanup(root)

        assertTrue(current.exists())
        assertTrue(current.resolve("current.exo").exists())
    }
}
```

- [ ] **Step 2: Run cleanup test to verify failure**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.LegacyStreamingCacheCleanupTest
```

Expected: FAIL with unresolved `LegacyStreamingCacheCleanup`.

- [ ] **Step 3: Add cleanup object**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/LegacyStreamingCacheCleanup.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import java.io.File

internal object LegacyStreamingCacheCleanup {
    private const val LEGACY_PLAYER_VOD_CACHE_DIR = "player_vod_cache_v2"

    fun cleanup(cacheRoot: File) {
        val legacyDir = File(cacheRoot, LEGACY_PLAYER_VOD_CACHE_DIR)
        if (!legacyDir.exists()) return
        if (!legacyDir.isDirectory) return
        runCatching { legacyDir.deleteRecursively() }
    }
}
```

- [ ] **Step 4: Run cleanup at app startup**

Modify `app/src/main/java/com/nexio/tv/NexioApplication.kt` imports:

```kotlin
import com.nexio.tv.ui.screens.player.LegacyStreamingCacheCleanup
```

Modify `onCreate()` startup scope:

```kotlin
        appScope.launch {
            LegacyStreamingCacheCleanup.cleanup(cacheDir)
            runPosterCacheCleanup()
        }
```

- [ ] **Step 5: Run cleanup tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.LegacyStreamingCacheCleanupTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/LegacyStreamingCacheCleanup.kt app/src/main/java/com/nexio/tv/NexioApplication.kt app/src/test/java/com/nexio/tv/ui/screens/player/LegacyStreamingCacheCleanupTest.kt
git commit -m "fix: remove legacy player vod cache on startup"
```

---

### Task 5: Verification Build And A/B Validation

**Files:**
- No production changes unless verification exposes a defect.

- [ ] **Step 1: Run focused tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.local.StreamingCacheDebugModeTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest --tests com.nexio.tv.ui.screens.player.PlayerStreamingCacheFillWiringTest --tests com.nexio.tv.ui.screens.player.CoverageAwareDataSourceTest --tests com.nexio.tv.ui.screens.player.CacheFillWorkerTest --tests com.nexio.tv.ui.screens.player.LegacyStreamingCacheCleanupTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Compile and assemble**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:assembleUniversalDebug
```

Expected: both commands end with `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install to `50.58`**

Run:

```bash
adb -s 192.168.50.58:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
```

Expected: `Success`.

- [ ] **Step 4: Validate mode A: OFF baseline**

Manual:

```text
1. Toggle Streaming Cache OFF.
2. Start same remux source.
3. Wait until playback starts.
```

Collect:

```bash
adb -s 192.168.50.58:5555 shell pidof com.nexiodebug.tv
adb -s 192.168.50.58:5555 shell ps -T -p <pid> | grep -E 'CacheFill|ExoPlayer|OkHttp|AudioTrack'
adb -s 192.168.50.58:5555 shell dumpsys meminfo <pid>
adb -s 192.168.50.58:5555 shell run-as com.nexiodebug.tv du -sh cache/stream-cache cache/player_vod_cache_v2 cache 2>/dev/null
adb -s 192.168.50.58:5555 logcat -d --pid <pid> -t 1200 | grep -Ei 'BUFFER|AudioTrack|JankStats|STREAM_CACHE|CacheFill|PLAYBACK_STARTUP'
```

Expected:

```text
No CacheFill-0.
No new stream-cache growth.
Playback smooth, matching user observation.
```

- [ ] **Step 5: Validate mode B: Phase 3 cache + fill**

Manual:

```text
1. Toggle Streaming Cache ON.
2. Cycle Streaming Cache Mode to "Phase 3: cache + fill".
3. Restart same remux source.
4. Capture startup and 5-minute sustained snapshots.
```

Expected:

```text
CacheFill-0 present.
stream-cache grows beyond startup-sized fragments.
Playback is no worse than OFF baseline.
If smooth: CoverageAwareDataSource is the likely stutter source.
If stutter: fill worker contention or memory/disk pressure is the likely source.
```

- [ ] **Step 6: Validate mode C: Coverage only**

Manual:

```text
1. Toggle Streaming Cache ON.
2. Cycle Streaming Cache Mode to "Coverage only: no fill".
3. Restart same remux source.
4. Capture startup and 5-minute sustained snapshots.
```

Expected:

```text
No CacheFill-0.
CoverageAwareDataSource active via mode.
If stutter occurs here: CoverageAwareDataSource segmentation/fallback is the likely source.
If smooth here but mode B stutters: fill worker contention is the likely source.
```

- [ ] **Step 7: Validate mode D: Phase 4 coverage + fill**

Manual:

```text
1. Toggle Streaming Cache ON.
2. Cycle Streaming Cache Mode to "Phase 4: coverage + fill".
3. Restart same remux source.
4. Capture startup and 5-minute sustained snapshots.
```

Expected:

```text
CacheFill-0 present.
stream-cache grows.
Use this mode only after A/B modes identify which subsystem is safe.
```

- [ ] **Step 8: Decision gate**

Use this table:

```text
OFF smooth, PHASE3 smooth, COVERAGE_ONLY stutters:
  CoverageAwareDataSource is the root cause. Keep Phase 3 mode as default for streaming cache and redesign coverage coordination.

OFF smooth, PHASE3 stutters, COVERAGE_ONLY smooth:
  Fill worker contention is root cause. Tune fill start delay, chunk size, horizon, or pause during startup.

OFF smooth, PHASE3 smooth, COVERAGE_ONLY smooth, PHASE4 stutters:
  Interaction bug between coverage and fill. Inspect metrics: urgent waits, fallback opens, fill skips.

OFF stutters too:
  Not a streaming cache regression. Investigate provider/device/decoder/UI load.
```

- [ ] **Step 9: Push after validation**

If the diagnostic build is useful and tests pass:

```bash
git push origin HEAD:main
```

Expected: push succeeds.

---

## Self-Review

**Spec coverage:** The plan covers the immediate Phase 4 diagnostic need with explicit mode isolation, minimal counters, and device validation. It also covers the separate legacy `player_vod_cache_v2` cleanup issue as its own task.

**Placeholder scan:** The plan avoids `TBD`, `TODO`, "implement later", and vague "write tests" instructions. Every code-changing step includes exact snippets or exact commands.

**Type consistency:** The mode name is consistently `StreamingCacheDebugMode`; DataStore flow is `streamingCacheDebugMode`; player property is `streamingCacheDebugMode`; UI event is `CycleStreamingCacheDebugMode`.
