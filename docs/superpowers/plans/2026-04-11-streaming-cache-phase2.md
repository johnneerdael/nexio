# Streaming Cache Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single-connection background cache fill path that writes media bytes to `SimpleCache` while playback remains read-only and the feature flag OFF path stays identical to the clean Media3 baseline.

**Architecture:** Phase 2 adds a subordinate `CacheFillWorker` and a lightweight range coordinator so playback upstream fallback owns any overlapping HTTP byte ranges. Full Phase 4 coverage-aware waiting is intentionally deferred, but A2 still holds because the playback upstream factory marks fallback ranges before opening upstream and the fill worker skips/cancels those ranges.

**Tech Stack:** Kotlin, Media3 `SimpleCache`/`CacheDataSink`/`CacheDataSource`, OkHttp, Robolectric, MockWebServer, Gradle universal debug unit tests.

---

## Scope

Implement only Phase 2:

- Add a single `CacheFill-0` background thread.
- Write cache bytes only from `CacheFillWorker` through `CacheDataSink`.
- Keep playback `CacheDataSource` read-only via `setCacheWriteDataSinkFactory(null)`.
- Add range ownership coordination sufficient for A2 without implementing Phase 4 blocking/waiting behavior.
- Add memory-budget and fill-controller primitives.
- Wire start/stop behind the existing `streaming_cache_enabled` debug flag.

Do not implement:

- Phase 3 tuned `DefaultLoadControl`.
- Phase 4 `CoverageAwareDataSource` steady-state wait policy.
- Phase 5 provider probe/profile selection.
- Phase 6 second fill connection.
- Normal settings UI or hot-path JSONL diagnostics.

---

## File Structure

- Create `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt`: Owns fallback byte ranges and exposes overlap checks for playback/fill coordination.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/PlaybackFallbackTrackingDataSource.kt`: Wraps upstream `DataSource` used by `CacheDataSource`; marks fallback-owned ranges before upstream open and clears them on close.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/BandwidthMonitor.kt`: 5-second sliding-window byte throughput.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/MemoryBudget.kt`: Runtime heap/memory class budget derivation and fill-worker budget check.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/ProviderProfile.kt`: Phase 2 single-connection fill constants.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingMetrics.kt`: Atomic counters only.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/FillController.kt`: Horizon/backpressure and position-aware eviction.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt`: Single-thread range downloader with one 512 KB buffer and direct `CacheDataSink` writes.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt`: Session lifecycle object that owns controller, monitor, worker, and coordinator.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`: Inject stable cache key factory and optional fallback tracking upstream factory.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`: Own the fill session, expose start/stop methods, pass coordinator to playback networking.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`: Store active fill-session state and stop fill on controller clear/flag disable.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`: Start fill after `prepare()` when flag is enabled and content length is known.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`: Stop/restart fill when switching streams.
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinatorTest.kt`.
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/BandwidthMonitorTest.kt`.
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/MemoryBudgetTest.kt`.
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/CacheFillWorkerTest.kt`.
- Extend `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`.

---

### Task 1: Range Coordinator and Playback Upstream Tracking

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/PlaybackFallbackTrackingDataSource.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinatorTest.kt`

- [ ] **Step 1: Write the failing coordinator tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinatorTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingRangeCoordinatorTest {

    @Test
    fun fallbackRange_overlapsContainedFillRange() {
        val coordinator = StreamingRangeCoordinator()
        val token = coordinator.markFallbackOwned(start = 100, endExclusive = 200)

        assertTrue(coordinator.isOwnedByPlaybackFallback(start = 120, endExclusive = 180))

        coordinator.clearFallbackOwnership(token)
        assertFalse(coordinator.isOwnedByPlaybackFallback(start = 120, endExclusive = 180))
    }

    @Test
    fun fallbackRange_overlapsFillRangeStartingBeforeFallback() {
        val coordinator = StreamingRangeCoordinator()
        coordinator.markFallbackOwned(start = 100, endExclusive = 200)

        assertTrue(coordinator.isOwnedByPlaybackFallback(start = 50, endExclusive = 150))
    }

    @Test
    fun adjacentRanges_doNotOverlap() {
        val coordinator = StreamingRangeCoordinator()
        coordinator.markFallbackOwned(start = 100, endExclusive = 200)

        assertFalse(coordinator.isOwnedByPlaybackFallback(start = 0, endExclusive = 100))
        assertFalse(coordinator.isOwnedByPlaybackFallback(start = 200, endExclusive = 300))
    }
}
```

- [ ] **Step 2: Run the failing coordinator test**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.StreamingRangeCoordinatorTest
```

Expected: FAIL because `StreamingRangeCoordinator` does not exist.

- [ ] **Step 3: Implement the coordinator**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

internal class StreamingRangeCoordinator {
    private val fallbackOwnedRegions = ConcurrentSkipListMap<Long, MutableMap<String, Region>>()
    private val tokenIndex = ConcurrentHashMap<String, Region>()

    fun markFallbackOwned(start: Long, endExclusive: Long): String {
        val normalizedStart = start.coerceAtLeast(0L)
        val normalizedEnd = endExclusive.coerceAtLeast(normalizedStart)
        val token = UUID.randomUUID().toString()
        val region = Region(token, normalizedStart, normalizedEnd)
        fallbackOwnedRegions.compute(normalizedStart) { _, existing ->
            (existing ?: LinkedHashMap()).apply { put(token, region) }
        }
        tokenIndex[token] = region
        return token
    }

    fun clearFallbackOwnership(token: String) {
        val region = tokenIndex.remove(token) ?: return
        fallbackOwnedRegions.computeIfPresent(region.start) { _, regions ->
            regions.remove(token)
            if (regions.isEmpty()) null else regions
        }
    }

    fun isOwnedByPlaybackFallback(start: Long, endExclusive: Long): Boolean {
        val normalizedStart = start.coerceAtLeast(0L)
        val normalizedEnd = endExclusive.coerceAtLeast(normalizedStart)
        if (normalizedEnd <= normalizedStart) return false

        val floor = fallbackOwnedRegions.floorEntry(normalizedStart)
        if (floor?.value?.values?.any { it.endExclusive > normalizedStart } == true) {
            return true
        }

        val overlappingStarts = fallbackOwnedRegions.subMap(normalizedStart, true, normalizedEnd, false)
        return overlappingStarts.values.any { regions ->
            regions.values.any { it.endExclusive > normalizedStart }
        }
    }

    private data class Region(
        val token: String,
        val start: Long,
        val endExclusive: Long
    )
}
```

- [ ] **Step 4: Add fallback tracking DataSource wrapper**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/PlaybackFallbackTrackingDataSource.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

internal class PlaybackFallbackTrackingDataSource(
    private val upstream: DataSource,
    private val coordinator: StreamingRangeCoordinator,
    private val defaultFallbackBytes: Long = DEFAULT_FALLBACK_BYTES
) : DataSource {
    private var ownershipToken: String? = null

    override fun open(dataSpec: DataSpec): Long {
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            dataSpec.position + defaultFallbackBytes
        } else {
            dataSpec.position + dataSpec.length
        }
        ownershipToken = coordinator.markFallbackOwned(dataSpec.position, end)
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun close() {
        try {
            upstream.close()
        } finally {
            ownershipToken?.let(coordinator::clearFallbackOwnership)
            ownershipToken = null
        }
    }

    companion object {
        const val DEFAULT_FALLBACK_BYTES = 16L * 1024L * 1024L
    }
}
```

- [ ] **Step 5: Run the coordinator test**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.StreamingRangeCoordinatorTest
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlaybackFallbackTrackingDataSource.kt app/src/test/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinatorTest.kt
git commit -m "feat: coordinate streaming cache fallback ranges"
```

---

### Task 2: Budget, Profile, Metrics, and Bandwidth Primitives

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/BandwidthMonitor.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/MemoryBudget.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ProviderProfile.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingMetrics.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/BandwidthMonitorTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/MemoryBudgetTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/BandwidthMonitorTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BandwidthMonitorTest {
    @Test
    fun estimatedBytesPerSecond_usesSlidingWindow() {
        var now = 0L
        val monitor = BandwidthMonitor(windowMs = 5_000L, clockMs = { now })

        monitor.onBytesTransferred(1_000)
        now = 1_000
        monitor.onBytesTransferred(1_000)

        assertEquals(2_000L, monitor.estimatedBytesPerSecond())

        now = 7_000
        monitor.onBytesTransferred(500)

        assertEquals(0L, monitor.estimatedBytesPerSecond())
    }
}
```

Create `app/src/test/java/com/nexio/tv/ui/screens/player/MemoryBudgetTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryBudgetTest {
    @Test
    fun fillWorkerBudget_allowsOnePhase2Connection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val budget = MemoryBudget(context)

        assertTrue(budget.fillWorkerWithinBudget(activeConnections = 1))
        assertTrue(budget.effectiveHeapBytes > 0)
        assertTrue(budget.effectiveSampleQueueBytes >= MemoryBudget.MIN_SAMPLE_QUEUE_BYTES)
    }
}
```

- [ ] **Step 2: Run failing tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.BandwidthMonitorTest --tests com.nexio.tv.ui.screens.player.MemoryBudgetTest
```

Expected: FAIL because `BandwidthMonitor` and `MemoryBudget` do not exist.

- [ ] **Step 3: Implement the primitive classes**

Create concrete classes with these exact public/internal APIs:

```kotlin
// BandwidthMonitor.kt
internal class BandwidthMonitor(
    private val windowMs: Long = 5_000L,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime
) {
    fun onBytesTransferred(bytes: Long)
    fun estimatedBytesPerSecond(): Long
}

// MemoryBudget.kt
internal class MemoryBudget(context: Context) {
    val heapLimitBytes: Long
    val memoryClassBytes: Long
    val largeMemoryClassBytes: Long
    val sampleQueueBudgetBytes: Long
    val effectiveSampleQueueBytes: Long
    val effectiveHeapBytes: Long
    fun fillWorkerWithinBudget(activeConnections: Int): Boolean

    companion object {
        const val FILL_WORKER_READ_BUFFER_BYTES = 512 * 1024
        const val MIN_SAMPLE_QUEUE_BYTES = 32L * 1024L * 1024L
        const val MAX_SAMPLE_QUEUE_BYTES = 350L * 1024L * 1024L
    }
}

// ProviderProfile.kt
internal data class ProviderProfile(
    val chunkBytes: Long = 8L * 1024L * 1024L,
    val normalFragmentBytes: Long = 8L * 1024L * 1024L,
    val fillHorizonBytes: Long = 256L * 1024L * 1024L,
    val lowWaterBytes: Long = fillHorizonBytes / 2,
    val retainBehindBytes: Long = 8L * 1024L * 1024L,
    val maxConnections: Int = 1
)

// StreamingMetrics.kt
internal object StreamingMetrics {
    val cacheHits: AtomicLong
    val cacheMisses: AtomicLong
    val fillWorkerBytesWritten: AtomicLong
    val fallbackReadsTriggered: AtomicLong
    val coordinatorWaitTimeouts: AtomicLong
    val fillWorkerPauseCount: AtomicLong
    val urgentFillRequests: AtomicLong
    fun snapshot(): Map<String, Long>
}
```

Use a 512 KB per-connection budget check:

```kotlin
fun fillWorkerWithinBudget(activeConnections: Int): Boolean {
    return activeConnections.coerceAtLeast(0) * FILL_WORKER_READ_BUFFER_BYTES <=
        2L * 1024L * 1024L
}
```

- [ ] **Step 4: Run primitive tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.BandwidthMonitorTest --tests com.nexio.tv.ui.screens.player.MemoryBudgetTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/BandwidthMonitor.kt app/src/main/java/com/nexio/tv/ui/screens/player/MemoryBudget.kt app/src/main/java/com/nexio/tv/ui/screens/player/ProviderProfile.kt app/src/main/java/com/nexio/tv/ui/screens/player/StreamingMetrics.kt app/src/test/java/com/nexio/tv/ui/screens/player/BandwidthMonitorTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/MemoryBudgetTest.kt
git commit -m "feat: add streaming cache budget primitives"
```

---

### Task 3: Fill Controller and Position-Aware Eviction

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/FillController.kt`
- Test: add tests to `app/src/test/java/com/nexio/tv/ui/screens/player/FillControllerTest.kt`

- [ ] **Step 1: Write failing FillController tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/FillControllerTest.kt` with tests for high-water pause, low-water resume, and eviction behind playback. Use `StreamingCacheProvider` with a unique `cacheDirectoryName`, write spans through `CacheDataSink`, then assert spans behind playback are removed and spans ahead remain.

- [ ] **Step 2: Run failing FillController test**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.FillControllerTest
```

Expected: FAIL because `FillController` does not exist.

- [ ] **Step 3: Implement FillController**

Implement `FillController` with this API:

```kotlin
internal class FillController(
    private val profile: ProviderProfile,
    private val cache: SimpleCache,
    private val cacheKey: String,
    private val playbackByteProvider: () -> Long
) {
    enum class State { STARTUP, FILLING, HORIZON_REACHED, SEEK, MEMORY_PRESSURE, STOPPED }
    val state: State
    fun onStart()
    fun onSeek()
    fun onMemoryWarning()
    fun shouldPause(fillFrontier: Long): Boolean
    fun evictBehindPlayback(retainBehindBytes: Long = profile.retainBehindBytes)
    fun stop()
}
```

The pause rule is:

```kotlin
val aheadBytes = (fillFrontier - playbackByteProvider()).coerceAtLeast(0L)
val shouldPause = when (state) {
    State.HORIZON_REACHED -> aheadBytes > profile.lowWaterBytes
    else -> aheadBytes >= profile.fillHorizonBytes
}
```

The eviction rule is:

```kotlin
val evictBefore = (playbackByteProvider() - retainBehindBytes).coerceAtLeast(0L)
for (span in cache.getCachedSpans(cacheKey)) {
    if (span.position + span.length <= evictBefore) {
        cache.removeSpan(span)
    }
}
```

- [ ] **Step 4: Run FillController tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.FillControllerTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/FillController.kt app/src/test/java/com/nexio/tv/ui/screens/player/FillControllerTest.kt
git commit -m "feat: add streaming cache fill controller"
```

---

### Task 4: Single-Connection CacheFillWorker

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/CacheFillWorkerTest.kt`

- [ ] **Step 1: Write failing worker tests**

Create `CacheFillWorkerTest` using `MockWebServer` to serve byte ranges. The required assertions:

- The worker sends `Range: bytes=...`.
- The worker writes to `SimpleCache` under the same cache key playback will use.
- `CacheFillWorker.READ_BUFFER_SIZE == 512 * 1024`.
- If `StreamingRangeCoordinator` owns the requested range, the worker skips it and no request is sent.

- [ ] **Step 2: Run failing worker test**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.CacheFillWorkerTest
```

Expected: FAIL because `CacheFillWorker` does not exist.

- [ ] **Step 3: Implement CacheFillWorker**

Create `CacheFillWorker` with this API:

```kotlin
internal class CacheFillWorker(
    private val profile: ProviderProfile,
    private val cache: SimpleCache,
    private val cacheKey: String,
    private val okHttpClient: OkHttpClient,
    private val bandwidthMonitor: BandwidthMonitor,
    private val fillController: FillController,
    private val rangeCoordinator: StreamingRangeCoordinator,
    private val playbackByteProvider: () -> Long,
    private val safetyGapBytes: Long = 8L * 1024L * 1024L
) {
    val fillFrontierPosition: Long
    val active: Boolean
    fun start(url: String, headers: Map<String, String>, contentLength: Long, startPosition: Long)
    fun seekTo(newPosition: Long)
    fun pause()
    fun resume()
    fun stop()
}
```

Implementation constraints:

- The only read staging field is `private val readBuffer = ByteArray(READ_BUFFER_SIZE)`.
- `READ_BUFFER_SIZE` is `512 * 1024`.
- Thread name is `CacheFill-0`.
- Before every chunk and during reads, check `rangeCoordinator.isOwnedByPlaybackFallback(start, end)`; if true, advance or cancel so fill never overlaps playback fallback.
- Use `CacheDataSink(cache, profile.normalFragmentBytes, READ_BUFFER_SIZE)`.
- Build `DataSpec` with `.setKey(cacheKey)`.
- Accept HTTP `206` for ranged chunks. Accept `200` only when `start == 0`.
- On `stop()`, cancel the active OkHttp call and interrupt the worker thread.

- [ ] **Step 4: Run worker tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.CacheFillWorkerTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 4**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt app/src/test/java/com/nexio/tv/ui/screens/player/CacheFillWorkerTest.kt
git commit -m "feat: add single-connection cache fill worker"
```

---

### Task 5: Playback Networking Integration

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
- Extend: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write failing networking tests**

Add tests proving:

- Flag OFF returns plain `DefaultDataSource` and does not open `SimpleCache`.
- Flag ON returns `CacheDataSource`.
- Flag ON uses read-only cache and wraps upstream with fallback tracking when a coordinator is provided.
- Cache key factory is `StableCacheKeyFactory` so fill and playback use the same key.

- [ ] **Step 2: Run failing networking tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: FAIL on the new coordinator/cache-key assertions.

- [ ] **Step 3: Modify PlayerPlaybackNetworking**

Change `createDataSourceFactory` to accept:

```kotlin
cacheKeyFactory: CacheKeyFactory = StableCacheKeyFactory(),
rangeCoordinator: StreamingRangeCoordinator? = null,
```

Wrap the upstream factory only when the coordinator is non-null:

```kotlin
val upstreamFactory = DefaultDataSource.Factory(context, httpFactory)
val trackedUpstreamFactory = DataSource.Factory {
    val upstream = upstreamFactory.createDataSource()
    if (rangeCoordinator == null) {
        upstream
    } else {
        PlaybackFallbackTrackingDataSource(upstream, rangeCoordinator)
    }
}
```

Return read-only cache:

```kotlin
return CacheDataSource.Factory()
    .setCache(streamingCacheProvider.getOrCreateCache())
    .setCacheKeyFactory(cacheKeyFactory)
    .setUpstreamDataSourceFactory(trackedUpstreamFactory)
    .setCacheWriteDataSinkFactory(null)
    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
```

- [ ] **Step 4: Run networking tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 5**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: track playback fallback ranges"
```

---

### Task 6: Fill Session Lifecycle Wiring

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`
- Extend: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write failing lifecycle tests**

Add tests proving:

- `PlayerMediaSourceFactory` does not create/start fill when `streamingCacheEnabled == false`.
- `PlayerMediaSourceFactory` creates a fill session only for HTTP(S), known positive content length, and `streamingCacheEnabled == true`.
- `shutdown()` stops the fill session and releases the provider.
- Asset URLs never start fill.

- [ ] **Step 2: Run failing lifecycle tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: FAIL on the new fill-session lifecycle assertions.

- [ ] **Step 3: Implement StreamingCacheFillSession**

Create a session class that owns:

```kotlin
internal class StreamingCacheFillSession(
    private val cache: SimpleCache,
    private val cacheKeyFactory: CacheKeyFactory,
    private val okHttpClient: OkHttpClient,
    private val memoryBudget: MemoryBudget,
    private val rangeCoordinator: StreamingRangeCoordinator,
    private val bandwidthMonitor: BandwidthMonitor = BandwidthMonitor(),
    private val profile: ProviderProfile = ProviderProfile()
) {
    val isActive: Boolean
    fun start(url: String, headers: Map<String, String>, contentLength: Long, playbackByteProvider: () -> Long)
    fun seekTo(newPositionBytes: Long)
    fun onMemoryWarning()
    fun stop()
}
```

Compute the playback/fill cache key with:

```kotlin
val dataSpec = DataSpec.Builder()
    .setUri(Uri.parse(url))
    .setLength(C.LENGTH_UNSET.toLong())
    .build()
val cacheKey = cacheKeyFactory.buildCacheKey(dataSpec)
```

- [ ] **Step 4: Wire PlayerMediaSourceFactory**

Add private fields:

```kotlin
private val cacheKeyFactory = StableCacheKeyFactory()
private val rangeCoordinator = StreamingRangeCoordinator()
private var fillSession: StreamingCacheFillSession? = null
```

Pass `cacheKeyFactory` and `rangeCoordinator` into `PlayerPlaybackNetworking.createDataSourceFactory` when cache is enabled.

Add:

```kotlin
fun startStreamingCacheFill(
    url: String,
    headers: Map<String, String>,
    contentLength: Long?,
    playbackByteProvider: () -> Long
)

fun stopStreamingCacheFill()
```

The method must return without creating a cache/fill session when:

- `streamingCacheEnabled == false`
- `usesHttpUpstream(url) == false`
- `contentLength == null || contentLength <= 0`

- [ ] **Step 5: Wire PlayerRuntimeController startup and stream switches**

After `player.prepare()` in `initializePlayer`, call:

```kotlin
mediaSourceFactory.startStreamingCacheFill(
    url = url,
    headers = headers,
    contentLength = currentVideoSize,
    playbackByteProvider = { estimateBufferedBytePosition() }
)
```

Add controller helper:

```kotlin
internal fun PlayerRuntimeController.estimateBufferedBytePosition(): Long {
    val player = _exoPlayer ?: return 0L
    val size = currentVideoSize ?: return 0L
    val duration = player.duration
    if (duration <= 0 || duration == C.TIME_UNSET) return 0L
    val bufferedMs = player.bufferedPosition.coerceAtLeast(player.currentPosition)
    return (size * bufferedMs / duration).coerceIn(0L, size)
}
```

Stop fill before `backendStop()` on stream switches and in `onCleared()`.

When the debug flag collector sets `mediaSourceFactory.streamingCacheEnabled = false`, also call `mediaSourceFactory.stopStreamingCacheFill()`.

- [ ] **Step 6: Run lifecycle tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: PASS.

- [ ] **Step 7: Commit Task 6**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: start streaming cache fill sessions"
```

---

### Task 7: Phase 2 Verification Gate

**Files:**
- Modify only if needed based on failed verification.

- [ ] **Step 1: Run targeted Phase 2 unit tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.StreamingRangeCoordinatorTest --tests com.nexio.tv.ui.screens.player.BandwidthMonitorTest --tests com.nexio.tv.ui.screens.player.MemoryBudgetTest --tests com.nexio.tv.ui.screens.player.FillControllerTest --tests com.nexio.tv.ui.screens.player.CacheFillWorkerTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: PASS.

- [ ] **Step 2: Run compile**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: PASS.

- [ ] **Step 3: Run build**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:assembleUniversalDebug
```

Expected: PASS.

- [ ] **Step 4: Verify acceptance criteria**

Check:

- A1: `PlayerPlaybackNetworking` still calls `setCacheWriteDataSinkFactory(null)`.
- A2: `PlaybackFallbackTrackingDataSource.open()` marks fallback ownership before upstream open; `CacheFillWorker` checks `StreamingRangeCoordinator` before downloads and during reads.
- A3: `CacheFillWorker.READ_BUFFER_SIZE == 512 * 1024`; `ProviderProfile.maxConnections == 1`; `MemoryBudget.fillWorkerWithinBudget(1)` passes.
- A5: `StreamingCacheProvider` still uses `LeastRecentlyUsedCacheEvictor`; `FillController.evictBehindPlayback()` removes behind-playhead spans.
- A6: Existing flag-off tests still prove no cache/fill startup.

- [ ] **Step 5: Commit verification-only fixes if any**

If Step 1-4 required fixes, commit them:

```bash
git add <changed-files>
git commit -m "fix: stabilize streaming cache phase 2"
```

If no fixes were needed, do not create an empty commit.

---

## Self-Review

**Spec coverage:** The plan covers Phase 2 single-connection fill, read-only playback cache, background-only writes, memory budget, bounded cache/eviction, minimal diagnostics, kill-switch compatibility, and feature flag OFF behavior. Provider probe, LoadControl tuning, full miss coordination, and second connection are intentionally deferred to later phases.

**A2 coverage:** The original Phase 2 text plus pass-through `CacheDataSource` would otherwise risk duplicate ranges because cache misses are internal. This plan adds fallback upstream range tracking inside the `CacheDataSource` upstream factory so playback ownership is marked before upstream opens, satisfying A2 without implementing the full Phase 4 wait policy.

**Placeholder scan:** No task uses TBD/TODO/fill-in-later instructions. Tests and APIs have concrete names and commands.

**Type consistency:** The core shared types are `StreamingRangeCoordinator`, `PlaybackFallbackTrackingDataSource`, `ProviderProfile`, `BandwidthMonitor`, `MemoryBudget`, `FillController`, `CacheFillWorker`, and `StreamingCacheFillSession`; task references use those names consistently.
