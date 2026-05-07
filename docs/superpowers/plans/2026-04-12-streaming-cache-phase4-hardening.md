# Streaming Cache Phase 4 Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Phase 4 safe enough to validate by removing loader-thread blocking, adding runtime memory pressure protection, reducing hot-path allocation, and preserving diagnostic A/B modes.

**Architecture:** Keep the diagnostic mode work already started, but harden the active Phase 4 path before further device validation. Convert urgent fill waiting to a signal-based, short bounded wait; add runtime `ComponentCallbacks2` memory pressure handling; reduce per-sample/per-segment allocation in `BandwidthMonitor` and `StreamingRangeCoordinator`; share one `MemoryBudget`; and only then rerun A/B validation on `50.58`.

**Tech Stack:** Kotlin, AndroidX Media3 `SimpleCache` / `CacheDataSource` / `DataSource`, Android `ComponentCallbacks2`, Android DataStore, Robolectric unit tests, ADB validation on `192.168.50.58`.

---

## Current State

- Current branch has Phase 4 implementation plus diagnostic-mode commits ahead of `origin/main`.
- `docs-site/*` has unrelated tracked local changes. Do not stage or commit those unless explicitly instructed.
- `cache/stream-cache` is the current streaming cache directory.
- `cache/player_vod_cache_v2` is legacy and should be deleted by the diagnostic cleanup task that already exists in the prior plan.
- Effective ON on `50.58` showed:
  - `CacheFill-0` present.
  - `cache/stream-cache` grew.
  - repeated buffering and AudioTrack underruns.
  - high PSS / SIGNALED exits.

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt`
  - Replace loader-thread polling with signal-based span notification.
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - Add urgent wait no-poll/timeout behavior tests.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSource.kt`
  - Reduce open-ended fallback segment size pressure and preserve bounded fallback windows.
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSourceTest.kt`
  - Verify urgent wait cap and bounded fallback behavior.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMemoryPressureMonitor.kt`
  - Register/unregister runtime trim callbacks.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Expose `memoryBudget`, own monitor, and forward memory warnings to fill session.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Use shared `mediaSourceFactory.memoryBudget`.
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingCacheMemoryPressureMonitorTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerLoadControlFactoryTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/BandwidthMonitor.kt`
  - Replace allocating sorted-list implementation with ring buffer.
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/BandwidthMonitorTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt`
  - Replace UUID and covered-region rebuild with lower-allocation token/range checks.
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinatorTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/MemoryBudget.kt`
  - Add fill-horizon budget.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ProviderProfile.kt`
  - Add factory from memory budget.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt`
  - Use budget-derived profile default.

---

### Task 1: Replace Loader-Thread Polling With Signal-Based Short Wait

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write the failing tests**

Append these tests to `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`:

```kotlin
    @Test
    fun streamingCacheFillSession_awaitSpanCommitted_capsWaitToFiftyMilliseconds() {
        val context = appContext()
        val cache = mockk<androidx.media3.datasource.cache.SimpleCache>(relaxed = true)
        every { cache.getCachedLength("movie", 0L, 16L) } returns 0L
        val session = StreamingCacheFillSession(
            cache = cache,
            cacheKeyFactory = StableCacheKeyFactory(),
            okHttpClient = OkHttpClient(),
            memoryBudget = MemoryBudget(context),
            rangeCoordinator = StreamingRangeCoordinator()
        )

        val startNs = System.nanoTime()
        assertFalse(
            session.awaitSpanCommitted(
                cacheKey = "movie",
                position = 0L,
                minLength = 16L,
                timeoutMs = 5_000L
            )
        )
        val elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        assertTrue("urgent wait must not block loader for seconds", elapsedMs < 250L)
    }

    @Test
    fun streamingCacheFillSession_awaitSpanCommitted_returnsWhenListenerSeesFullSpan() {
        val context = appContext()
        val cache = mockk<androidx.media3.datasource.cache.SimpleCache>(relaxed = true)
        val listeners = mutableListOf<androidx.media3.datasource.cache.Cache.Listener>()
        every { cache.getCachedLength("movie", 0L, 16L) } returns 0L andThen 16L
        every { cache.addListener(eq("movie"), any()) } answers {
            listeners += secondArg<androidx.media3.datasource.cache.Cache.Listener>()
            emptySet()
        }
        every { cache.removeListener(eq("movie"), any()) } answers {
            listeners.remove(secondArg<androidx.media3.datasource.cache.Cache.Listener>())
            Unit
        }
        val session = StreamingCacheFillSession(
            cache = cache,
            cacheKeyFactory = StableCacheKeyFactory(),
            okHttpClient = OkHttpClient(),
            memoryBudget = MemoryBudget(context),
            rangeCoordinator = StreamingRangeCoordinator()
        )

        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val waiter = Thread {
            result.set(
                session.awaitSpanCommitted(
                    cacheKey = "movie",
                    position = 0L,
                    minLength = 16L,
                    timeoutMs = 5_000L
                )
            )
        }
        waiter.start()
        assertTrue(waitUntil { listeners.isNotEmpty() })
        listeners.single().onSpanAdded(
            cache,
            mockk(relaxed = true)
        )
        waiter.join(1_000L)

        assertFalse(waiter.isAlive)
        assertTrue(result.get())
    }
```

If `mockk` imports are missing in `PlayerMediaSourceFactoryTest.kt`, add:

```kotlin
import io.mockk.eq
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.streamingCacheFillSession_awaitSpanCommitted_capsWaitToFiftyMilliseconds --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.streamingCacheFillSession_awaitSpanCommitted_returnsWhenListenerSeesFullSpan
```

Expected: first test FAILS because current implementation can sleep/poll longer than 250ms when timeout is 5s, or second test FAILS because no listener is registered.

- [ ] **Step 3: Implement signal-based wait**

Modify `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt` imports:

```kotlin
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.util.concurrent.CountDownLatch
```

Replace `awaitSpanCommitted(...)` with:

```kotlin
    override fun awaitSpanCommitted(
        cacheKey: String,
        position: Long,
        minLength: Long,
        timeoutMs: Long
    ): Boolean {
        val normalizedPosition = position.coerceAtLeast(0L)
        val normalizedLength = minLength.coerceAtLeast(1L)
        if (hasCommittedSpan(cacheKey, normalizedPosition, normalizedLength)) return true

        val waitMs = timeoutMs.coerceIn(0L, MAX_URGENT_WAIT_MS)
        if (waitMs <= 0L) return false

        val latch = CountDownLatch(1)
        val listener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) {
                if (hasCommittedSpan(cacheKey, normalizedPosition, normalizedLength)) {
                    latch.countDown()
                }
            }

            override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit

            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
                if (hasCommittedSpan(cacheKey, normalizedPosition, normalizedLength)) {
                    latch.countDown()
                }
            }
        }

        cache.addListener(cacheKey, listener)
        return try {
            if (hasCommittedSpan(cacheKey, normalizedPosition, normalizedLength)) {
                true
            } else {
                latch.await(waitMs, TimeUnit.MILLISECONDS) &&
                    hasCommittedSpan(cacheKey, normalizedPosition, normalizedLength)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            cache.removeListener(cacheKey, listener)
        }
    }

    private fun hasCommittedSpan(cacheKey: String, position: Long, length: Long): Boolean {
        return cache.getCachedLength(cacheKey, position, length) >= length
    }
```

Add constant in companion:

```kotlin
        const val MAX_URGENT_WAIT_MS = 50L
```

- [ ] **Step 4: Run tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.streamingCacheFillSession_awaitSpanCommitted_capsWaitToFiftyMilliseconds --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.streamingCacheFillSession_awaitSpanCommitted_returnsWhenListenerSeesFullSpan
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "fix: make urgent cache wait signal based"
```

---

### Task 2: Add Runtime Memory Pressure Handling

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMemoryPressureMonitor.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingCacheMemoryPressureMonitorTest.kt`

- [ ] **Step 1: Write failing monitor tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingCacheMemoryPressureMonitorTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingCacheMemoryPressureMonitorTest {

    @Test
    fun onTrimMemoryRunningLowInvokesCallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val called = AtomicBoolean(false)
        val monitor = StreamingCacheMemoryPressureMonitor(context) { called.set(true) }

        monitor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertTrue(called.get())
    }

    @Test
    fun onTrimMemoryUiHiddenDoesNotInvokeCallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val called = AtomicBoolean(false)
        val monitor = StreamingCacheMemoryPressureMonitor(context) { called.set(true) }

        monitor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        assertFalse(called.get())
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.StreamingCacheMemoryPressureMonitorTest
```

Expected: FAIL with unresolved `StreamingCacheMemoryPressureMonitor`.

- [ ] **Step 3: Implement monitor**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMemoryPressureMonitor.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration

internal class StreamingCacheMemoryPressureMonitor(
    private val context: Context,
    private val onMemoryPressure: () -> Unit
) : ComponentCallbacks2 {
    private var registered = false

    fun start() {
        if (registered) return
        context.applicationContext.registerComponentCallbacks(this)
        registered = true
    }

    fun stop() {
        if (!registered) return
        context.applicationContext.unregisterComponentCallbacks(this)
        registered = false
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            onMemoryPressure()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun onLowMemory() {
        onMemoryPressure()
    }
}
```

- [ ] **Step 4: Wire monitor into media source factory**

Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`:

Add field:

```kotlin
    private val memoryPressureMonitor = StreamingCacheMemoryPressureMonitor(context) {
        onStreamingCacheMemoryWarning()
    }
```

Add initializer:

```kotlin
    init {
        memoryPressureMonitor.start()
    }
```

Add method:

```kotlin
    fun onStreamingCacheMemoryWarning() {
        fillSession?.onMemoryWarning()
    }
```

Modify `shutdown()`:

```kotlin
    fun shutdown() {
        memoryPressureMonitor.stop()
        if (stopStreamingCacheFill()) {
            streamingCacheProvider.release()
        }
    }
```

- [ ] **Step 5: Run tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.StreamingCacheMemoryPressureMonitorTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMemoryPressureMonitor.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/test/java/com/nexio/tv/ui/screens/player/StreamingCacheMemoryPressureMonitorTest.kt
git commit -m "feat: pause streaming cache fill on memory pressure"
```

---

### Task 3: Replace `BandwidthMonitor` Allocating Implementation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/BandwidthMonitor.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/BandwidthMonitorTest.kt`

- [ ] **Step 1: Add failing out-of-order rejection test**

Append to `BandwidthMonitorTest.kt`:

```kotlin
    @Test
    fun estimatedBytesPerSecond_ignoresOutOfOrderSamplesWithoutSorting() {
        var now = 1_000L
        val monitor = BandwidthMonitor(windowMs = 5_000L, clockMs = { now })

        monitor.onBytesTransferred(1_000)
        now = 500L
        monitor.onBytesTransferred(5_000)
        now = 2_000L
        monitor.onBytesTransferred(1_000)

        assertEquals(2_000L, monitor.estimatedBytesPerSecond())
    }
```

- [ ] **Step 2: Run tests to verify current sort-based behavior fails**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.BandwidthMonitorTest
```

Expected: FAIL because the old implementation sorts and includes the out-of-order sample.

- [ ] **Step 3: Implement fixed-size ring buffer**

Replace `BandwidthMonitor.kt` with:

```kotlin
package com.nexio.tv.ui.screens.player

import android.os.SystemClock

internal class BandwidthMonitor(
    private val windowMs: Long = 5_000L,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    capacity: Int = DEFAULT_CAPACITY
) {
    private data class Sample(
        var timestampMs: Long = 0L,
        var bytes: Long = 0L
    )

    private val samples = Array(capacity.coerceAtLeast(2)) { Sample() }
    private val lock = Any()
    private var startIndex = 0
    private var size = 0
    private var lastTimestampMs = Long.MIN_VALUE

    fun onBytesTransferred(bytes: Long) {
        if (bytes <= 0L) return
        synchronized(lock) {
            val now = clockMs()
            if (now < lastTimestampMs) return
            lastTimestampMs = now
            pruneLocked(now)
            appendLocked(now, bytes)
        }
    }

    fun estimatedBytesPerSecond(): Long {
        synchronized(lock) {
            pruneLocked(clockMs())
            if (size <= 1) return 0L

            val first = samples[startIndex]
            val last = samples[index(size - 1)]
            val elapsedMs = last.timestampMs - first.timestampMs
            if (elapsedMs <= 0L) return 0L

            var totalBytes = 0L
            for (i in 0 until size) {
                totalBytes += samples[index(i)].bytes
            }
            return totalBytes * 1_000L / elapsedMs
        }
    }

    private fun appendLocked(timestampMs: Long, bytes: Long) {
        if (size == samples.size) {
            startIndex = (startIndex + 1) % samples.size
            size--
        }
        val insertIndex = index(size)
        samples[insertIndex].timestampMs = timestampMs
        samples[insertIndex].bytes = bytes
        size++
    }

    private fun pruneLocked(nowMs: Long) {
        val cutoffMs = nowMs - windowMs
        while (size > 0 && samples[startIndex].timestampMs < cutoffMs) {
            startIndex = (startIndex + 1) % samples.size
            size--
        }
    }

    private fun index(offset: Int): Int = (startIndex + offset) % samples.size

    companion object {
        private const val DEFAULT_CAPACITY = 256
    }
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.BandwidthMonitorTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/BandwidthMonitor.kt app/src/test/java/com/nexio/tv/ui/screens/player/BandwidthMonitorTest.kt
git commit -m "perf: make bandwidth monitor allocation free"
```

---

### Task 4: Reduce `StreamingRangeCoordinator` Hot-Path Allocation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinatorTest.kt`

- [ ] **Step 1: Add token monotonicity and overlapping clear tests**

Append to `StreamingRangeCoordinatorTest.kt`:

```kotlin
    @Test
    fun fallbackTokens_areMonotonicStringsWithoutUuidFormat() {
        val coordinator = StreamingRangeCoordinator()

        val first = coordinator.markFallbackOwned(0L, 10L)
        val second = coordinator.markFallbackOwned(20L, 30L)

        assertEquals("1", first)
        assertEquals("2", second)
    }

    @Test
    fun clearingOneOverlappingRangeKeepsOtherRangeActive() {
        val coordinator = StreamingRangeCoordinator()
        val first = coordinator.markFallbackOwned(0L, 100L)
        val second = coordinator.markFallbackOwned(50L, 150L)

        coordinator.clearFallbackOwnership(first)

        assertTrue(coordinator.isOwnedByPlaybackFallback(75L, 80L))
        coordinator.clearFallbackOwnership(second)
        assertFalse(coordinator.isOwnedByPlaybackFallback(75L, 80L))
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.StreamingRangeCoordinatorTest
```

Expected: FAIL because tokens are UUIDs.

- [ ] **Step 3: Replace UUID with `AtomicLong` and remove covered rebuild**

Modify `StreamingRangeCoordinator.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentSkipListMap

internal class StreamingRangeCoordinator : PlaybackFallbackRangeChecker {
    private val regionsByStart = ConcurrentSkipListMap<Long, MutableMap<String, Region>>()
    private val tokenIndex = LinkedHashMap<String, Region>()
    private val nextToken = AtomicLong(0L)
    private val lock = Any()

    fun markFallbackOwned(start: Long, endExclusive: Long): String {
        val normalizedStart = start.coerceAtLeast(0L)
        val normalizedEnd = endExclusive.coerceAtLeast(normalizedStart)
        val token = nextToken.incrementAndGet().toString()
        val region = Region(token, normalizedStart, normalizedEnd)

        synchronized(lock) {
            val regions = regionsByStart.getOrPut(normalizedStart) { LinkedHashMap() }
            regions[token] = region
            tokenIndex[token] = region
        }
        return token
    }

    fun clearFallbackOwnership(token: String) {
        synchronized(lock) {
            val region = tokenIndex.remove(token) ?: return
            val regions = regionsByStart[region.start] ?: return
            regions.remove(token)
            if (regions.isEmpty()) {
                regionsByStart.remove(region.start)
            }
        }
    }

    override fun isOwnedByPlaybackFallback(start: Long, endExclusive: Long): Boolean {
        val normalizedStart = start.coerceAtLeast(0L)
        val normalizedEnd = endExclusive.coerceAtLeast(normalizedStart)
        if (normalizedEnd <= normalizedStart) return false

        synchronized(lock) {
            for (regions in regionsByStart.headMap(normalizedStart, true).descendingMap().values) {
                for (region in regions.values) {
                    if (region.endExclusive > normalizedStart) return true
                }
            }
            for (regions in regionsByStart.subMap(normalizedStart, false, normalizedEnd, false).values) {
                for (region in regions.values) {
                    if (region.endExclusive > normalizedStart) return true
                }
            }
            return false
        }
    }

    private data class Region(
        val token: String,
        val start: Long,
        val endExclusive: Long
    )
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.StreamingRangeCoordinatorTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt app/src/test/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinatorTest.kt
git commit -m "perf: reduce streaming range coordinator churn"
```

---

### Task 5: Share MemoryBudget And Derive Fill Horizon

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/MemoryBudget.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ProviderProfile.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/MemoryBudgetTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/ProviderProfileTest.kt`

- [ ] **Step 1: Add failing memory/fill profile tests**

Append to `MemoryBudgetTest.kt`:

```kotlin
    @Test
    fun effectiveFillHorizonBytes_isBoundedBelowSampleQueueBudget() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val budget = MemoryBudget(context)

        assertTrue(budget.effectiveFillHorizonBytes >= 32L * 1024L * 1024L)
        assertTrue(budget.effectiveFillHorizonBytes <= 128L * 1024L * 1024L)
        assertTrue(budget.effectiveFillHorizonBytes <= budget.effectiveSampleQueueBytes)
    }
```

Append to `ProviderProfileTest.kt`:

```kotlin
    @Test
    fun forMemoryBudget_usesEffectiveFillHorizon() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val budget = MemoryBudget(context)

        val profile = ProviderProfile.forMemoryBudget(budget)

        assertEquals(budget.effectiveFillHorizonBytes, profile.fillHorizonBytes)
        assertEquals(budget.effectiveFillHorizonBytes / 2L, profile.lowWaterBytes)
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.MemoryBudgetTest --tests com.nexio.tv.ui.screens.player.ProviderProfileTest
```

Expected: FAIL with missing `effectiveFillHorizonBytes` and `forMemoryBudget`.

- [ ] **Step 3: Add fill horizon budget**

Modify `MemoryBudget.kt`:

```kotlin
    val effectiveFillHorizonBytes: Long
```

In `init`, after `effectiveSampleQueueBytes`:

```kotlin
        effectiveFillHorizonBytes = (effectiveSampleQueueBytes / 2L)
            .coerceIn(MIN_FILL_HORIZON_BYTES, MAX_FILL_HORIZON_BYTES)
```

Add constants:

```kotlin
        const val MIN_FILL_HORIZON_BYTES = 32L * 1024L * 1024L
        const val MAX_FILL_HORIZON_BYTES = 128L * 1024L * 1024L
```

- [ ] **Step 4: Add provider profile factory**

Modify `ProviderProfile.kt`:

```kotlin
    companion object {
        fun forMemoryBudget(memoryBudget: MemoryBudget): ProviderProfile {
            return ProviderProfile(
                fillHorizonBytes = memoryBudget.effectiveFillHorizonBytes,
                lowWaterBytes = memoryBudget.effectiveFillHorizonBytes / 2L
            )
        }
    }
```

- [ ] **Step 5: Share `MemoryBudget` instance**

Modify `PlayerMediaSourceFactory.kt`:

```kotlin
    internal val memoryBudget = MemoryBudget(context)
```

When constructing `StreamingCacheFillSession`, pass:

```kotlin
            profile = ProviderProfile.forMemoryBudget(memoryBudget)
```

Modify `PlayerRuntimeControllerInitialization.kt` load control:

```kotlin
                    effectiveSampleQueueBytes = mediaSourceFactory.memoryBudget.effectiveSampleQueueBytes
```

- [ ] **Step 6: Run tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.MemoryBudgetTest --tests com.nexio.tv.ui.screens.player.ProviderProfileTest --tests com.nexio.tv.ui.screens.player.PlayerLoadControlFactoryTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/MemoryBudget.kt app/src/main/java/com/nexio/tv/ui/screens/player/ProviderProfile.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/test/java/com/nexio/tv/ui/screens/player/MemoryBudgetTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/ProviderProfileTest.kt
git commit -m "fix: derive streaming fill horizon from memory budget"
```

---

### Task 6: Final Verification And A/B Validation

**Files:**
- No production files unless verification exposes a defect.

- [ ] **Step 1: Run focused tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.local.StreamingCacheDebugModeTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest --tests com.nexio.tv.ui.screens.player.PlayerStreamingCacheFillWiringTest --tests com.nexio.tv.ui.screens.player.CoverageAwareDataSourceTest --tests com.nexio.tv.ui.screens.player.CacheFillWorkerTest --tests com.nexio.tv.ui.screens.player.LegacyStreamingCacheCleanupTest --tests com.nexio.tv.ui.screens.player.StreamingCacheMemoryPressureMonitorTest --tests com.nexio.tv.ui.screens.player.BandwidthMonitorTest --tests com.nexio.tv.ui.screens.player.StreamingRangeCoordinatorTest --tests com.nexio.tv.ui.screens.player.MemoryBudgetTest --tests com.nexio.tv.ui.screens.player.ProviderProfileTest
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

- [ ] **Step 4: Validate diagnostic modes**

Run the same source on `50.58` in this order:

```text
1. OFF baseline.
2. Phase 3: cache + fill.
3. Coverage only: no fill.
4. Phase 4: coverage + fill.
```

For each run, collect:

```bash
adb -s 192.168.50.58 shell pidof com.nexiodebug.tv
adb -s 192.168.50.58 shell ps -T -p <pid> | grep -E 'CacheFill|ExoPlayer|OkHttp|AudioTrack'
adb -s 192.168.50.58 shell dumpsys meminfo <pid>
adb -s 192.168.50.58 shell run-as com.nexiodebug.tv du -sh cache/stream-cache cache/player_vod_cache_v2 cache 2>/dev/null
adb -s 192.168.50.58 logcat -d --pid <pid> -t 1500 | grep -Ei 'STREAM_CACHE|CacheFill|CoverageAware|AudioTrack|BUFFER|JankStats|PLAYBACK_STARTUP|ExoPlayer|GC'
```

Decision matrix:

```text
OFF smooth, Phase 3 smooth, CoverageOnly stutters:
  CoverageAwareDataSource is root cause.

OFF smooth, Phase 3 stutters, CoverageOnly smooth:
  Fill worker contention is root cause.

OFF smooth, Phase 3 smooth, CoverageOnly smooth, Phase 4 stutters:
  Coverage/fill interaction is root cause.

OFF stutters:
  Not a streaming cache regression.
```

- [ ] **Step 5: Push only after useful diagnostic build**

Run:

```bash
git push origin HEAD:main
```

Expected: push succeeds.

---

## Self-Review

**Spec coverage:** This plan covers all accepted audit items: loader-thread blocking, runtime memory pressure, BandwidthMonitor allocation, range coordinator rebuild/UUID churn, duplicated memory budgets, and fill horizon budget. It preserves diagnostic modes and legacy cache cleanup.

**Placeholder scan:** No `TBD`, `TODO`, "implement later", or generic "write tests" placeholders remain. All tasks include exact commands and code snippets.

**Type consistency:** Mode names remain `StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL`, `PHASE3_CACHE_WITH_FILL`, and `COVERAGE_ONLY`. The memory field is consistently `effectiveFillHorizonBytes`. The memory monitor type is consistently `StreamingCacheMemoryPressureMonitor`.
