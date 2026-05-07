# Streaming Cache Phase 4 Cache-Miss Coordination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace transparent playback upstream fallback with explicit cache span coverage checks so playback and the fill worker never download the same byte range concurrently.

**Architecture:** Keep the existing `StreamingRangeCoordinator` as the fallback-owned interval store, add a small `StreamingCacheMissCoordinator` facade for urgent fill requests, and route streaming-cache playback through a new `CoverageAwareDataSource`. `CoverageAwareDataSource` segments each `DataSpec` into cache-only, urgent-fill, or bounded upstream-fallback segments before opening any upstream connection.

**Tech Stack:** Kotlin, AndroidX Media3 `DataSource` / `DataSpec` / `SimpleCache`, OkHttp, Robolectric, MockWebServer, existing NEXIO `StreamingCacheFillSession`, `CacheFillWorker`, `BandwidthMonitor`, `StreamingMetrics`.

---

## Current Code Map

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt`
  - Existing fallback-owned range store. Keep this as the canonical interval implementation.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinator.kt`
  - New facade used by playback and fill session. Delegates fallback interval storage to `StreamingRangeCoordinator`, owns the optional urgent fill handler.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt`
  - Implement `StreamingCacheMissCoordinator.UrgentFillHandler`, expose urgent fill to playback, and attach/detach itself to the coordinator.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt`
  - Add urgent-priority command path that fills the exact requested byte position without applying the normal 8 MB safety gap.
  - Add urgent fragment size support for the urgent chunk only.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSource.kt`
  - New playback DataSource that checks `SimpleCache.getCachedLength()` before opening cache-only or upstream segment sources.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
  - When streaming cache is enabled, return `CoverageAwareDataSource.Factory` instead of a pass-through `CacheDataSource` with an upstream factory.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Own `StreamingCacheMissCoordinator`, shared `BandwidthMonitor`, and first-frame startup state.
  - Wire the miss coordinator into playback networking and fill session.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Notify `PlayerMediaSourceFactory` after first frame renders.
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinatorTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSourceTest.kt`
- Modify tests: `app/src/test/java/com/nexio/tv/ui/screens/player/CacheFillWorkerTest.kt`
- Modify tests: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

## Non-Goals

- Do not implement Phase 5 provider probing.
- Do not implement Phase 6 second fill connection.
- Do not touch `TrailerPlayer.kt`.
- Do not reintroduce JSONL hot-path tracing.
- Do not let playback write to cache. The playback cache read path must use `setCacheWriteDataSinkFactory(null)` and no upstream factory.

---

### Task 1: Miss Coordinator Facade

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinator.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinatorTest.kt`
- Existing reference test: `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinatorTest.kt`

- [ ] **Step 1: Write failing coordinator tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinatorTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingCacheMissCoordinatorTest {

    @Test
    fun fallbackOwnership_delegatesToRangeCoordinatorAndClearsByToken() {
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())

        val token = coordinator.markFallbackOwned(start = 100L, endExclusive = 200L)

        assertTrue(coordinator.isOwnedByPlaybackFallback(start = 150L, endExclusive = 160L))

        coordinator.clearFallbackOwnership(token)

        assertFalse(coordinator.isOwnedByPlaybackFallback(start = 150L, endExclusive = 160L))
    }

    @Test
    fun requestUrgentFill_returnsFalseWhenNoHandlerIsAttached() {
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())

        val filled = coordinator.requestUrgentFill(
            cacheKey = "movie",
            position = 4096L,
            minLength = 2L * 1024L * 1024L,
            timeoutMs = 1L
        )

        assertFalse(filled)
        assertEquals(0L, coordinator.estimatedBytesPerSecond())
    }

    @Test
    fun requestUrgentFill_delegatesToAttachedHandler() {
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())
        val prioritizedPosition = AtomicLong(-1L)
        val awaited = AtomicBoolean(false)
        val handler = object : StreamingCacheMissCoordinator.UrgentFillHandler {
            override fun prioritize(position: Long) {
                prioritizedPosition.set(position)
            }

            override fun awaitSpanCommitted(
                cacheKey: String,
                position: Long,
                minLength: Long,
                timeoutMs: Long
            ): Boolean {
                awaited.set(true)
                assertEquals("movie", cacheKey)
                assertEquals(8192L, position)
                assertEquals(2L * 1024L * 1024L, minLength)
                assertEquals(3000L, timeoutMs)
                return true
            }

            override fun estimatedBytesPerSecond(): Long = 16L * 1024L * 1024L
        }

        coordinator.attachUrgentFillHandler(handler)

        val filled = coordinator.requestUrgentFill(
            cacheKey = "movie",
            position = 8192L,
            minLength = 2L * 1024L * 1024L,
            timeoutMs = 3000L
        )

        assertTrue(filled)
        assertTrue(awaited.get())
        assertEquals(8192L, prioritizedPosition.get())
        assertEquals(16L * 1024L * 1024L, coordinator.estimatedBytesPerSecond())
    }

    @Test
    fun detachUrgentFillHandler_onlyDetachesMatchingHandler() {
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())
        val first = handler(bytesPerSecond = 10L)
        val second = handler(bytesPerSecond = 20L)

        coordinator.attachUrgentFillHandler(first)
        coordinator.attachUrgentFillHandler(second)
        coordinator.detachUrgentFillHandler(first)

        assertEquals(20L, coordinator.estimatedBytesPerSecond())

        coordinator.detachUrgentFillHandler(second)

        assertEquals(0L, coordinator.estimatedBytesPerSecond())
    }

    private fun handler(bytesPerSecond: Long): StreamingCacheMissCoordinator.UrgentFillHandler {
        return object : StreamingCacheMissCoordinator.UrgentFillHandler {
            override fun prioritize(position: Long) = Unit

            override fun awaitSpanCommitted(
                cacheKey: String,
                position: Long,
                minLength: Long,
                timeoutMs: Long
            ): Boolean = false

            override fun estimatedBytesPerSecond(): Long = bytesPerSecond
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.StreamingCacheMissCoordinatorTest
```

Expected: FAIL with unresolved reference `StreamingCacheMissCoordinator`.

- [ ] **Step 3: Implement `StreamingCacheMissCoordinator`**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinator.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

internal interface PlaybackFallbackRangeChecker {
    fun isOwnedByPlaybackFallback(start: Long, endExclusive: Long): Boolean
}

internal class StreamingCacheMissCoordinator(
    private val rangeCoordinator: StreamingRangeCoordinator
) : PlaybackFallbackRangeChecker {
    interface UrgentFillHandler {
        fun prioritize(position: Long)

        fun awaitSpanCommitted(
            cacheKey: String,
            position: Long,
            minLength: Long,
            timeoutMs: Long
        ): Boolean

        fun estimatedBytesPerSecond(): Long
    }

    private val handlerLock = Any()

    @Volatile
    private var urgentFillHandler: UrgentFillHandler? = null

    fun attachUrgentFillHandler(handler: UrgentFillHandler) {
        synchronized(handlerLock) {
            urgentFillHandler = handler
        }
    }

    fun detachUrgentFillHandler(handler: UrgentFillHandler) {
        synchronized(handlerLock) {
            if (urgentFillHandler === handler) {
                urgentFillHandler = null
            }
        }
    }

    fun markFallbackOwned(start: Long, endExclusive: Long): String {
        StreamingMetrics.fallbackReadsTriggered.incrementAndGet()
        return rangeCoordinator.markFallbackOwned(start, endExclusive)
    }

    fun clearFallbackOwnership(token: String) {
        rangeCoordinator.clearFallbackOwnership(token)
    }

    override fun isOwnedByPlaybackFallback(start: Long, endExclusive: Long): Boolean {
        return rangeCoordinator.isOwnedByPlaybackFallback(start, endExclusive)
    }

    fun requestUrgentFill(
        cacheKey: String,
        position: Long,
        minLength: Long,
        timeoutMs: Long
    ): Boolean {
        StreamingMetrics.urgentFillRequests.incrementAndGet()
        val handler = urgentFillHandler ?: return false
        handler.prioritize(position)
        return handler.awaitSpanCommitted(
            cacheKey = cacheKey,
            position = position,
            minLength = minLength,
            timeoutMs = timeoutMs
        )
    }

    fun estimatedBytesPerSecond(): Long {
        return urgentFillHandler?.estimatedBytesPerSecond() ?: 0L
    }
}
```

- [ ] **Step 4: Run coordinator tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.StreamingCacheMissCoordinatorTest --tests com.nexio.tv.ui.screens.player.StreamingRangeCoordinatorTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinator.kt app/src/test/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinatorTest.kt
git commit -m "feat: add streaming cache miss coordinator"
```

---

### Task 2: Urgent Fill Support In Fill Session And Worker

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/CacheFillWorkerTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Add failing urgent worker tests**

Append these tests to `app/src/test/java/com/nexio/tv/ui/screens/player/CacheFillWorkerTest.kt`:

```kotlin
    @Test
    fun prioritizeForPlaybackHole_fillsExactPositionWithoutSafetyGap() {
        val chunkBytes = 64L
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 32-95/128")
                .setBody(BufferFactory.body(ByteArray(chunkBytes.toInt()) { it.toByte() }))
        )
        val cache = provider.getOrCreateCache()
        val cacheKey = "movie-urgent-priority"
        val worker = worker(
            cacheKey = cacheKey,
            profile = ProviderProfile(
                chunkBytes = chunkBytes,
                normalFragmentBytes = chunkBytes,
                fillHorizonBytes = chunkBytes * 4L,
                lowWaterBytes = chunkBytes,
                retainBehindBytes = 0L
            ),
            playbackByteProvider = { 0L },
            safetyGapBytes = 64L
        )

        worker.start(
            url = server.url("/movie").toString(),
            headers = emptyMap(),
            contentLength = 128L,
            startPosition = 0L
        )
        worker.prioritize(position = 32L)

        assertTrue(waitUntil { cache.isCached(cacheKey, 32L, chunkBytes) })
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("bytes=32-95", request?.getHeader("Range"))
        worker.stopAndJoin()
    }

    @Test
    fun downloadChunkToCache_usesUrgentFragmentSizeWhenProvided() {
        val data = ByteArray((CacheFillWorker.URGENT_FRAGMENT_SIZE + 512L).toInt()) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-${data.lastIndex}/${data.size}")
                .setBody(BufferFactory.body(data))
        )
        val cache = provider.getOrCreateCache()
        val cacheKey = "movie-urgent-fragment"
        val worker = worker(cacheKey = cacheKey)

        worker.downloadChunkToCache(
            url = server.url("/movie").toString(),
            headers = emptyMap(),
            start = 0L,
            end = data.size.toLong(),
            fragmentBytes = CacheFillWorker.URGENT_FRAGMENT_SIZE
        )

        assertTrue(cache.isCached(cacheKey, 0L, data.size.toLong()))
    }
```

If the existing `worker(...)` helper does not expose `safetyGapBytes`, update the helper signature in the same file:

```kotlin
    private fun worker(
        cacheKey: String,
        profile: ProviderProfile = ProviderProfile(),
        rangeCoordinator: StreamingRangeCoordinator = StreamingRangeCoordinator(),
        playbackByteProvider: () -> Long = { 0L },
        safetyGapBytes: Long = 8L * 1024L * 1024L,
        fillController: FillController? = null,
    ): CacheFillWorker {
        val cache = provider.getOrCreateCache()
        return CacheFillWorker(
            profile = profile,
            cache = cache,
            cacheKey = cacheKey,
            okHttpClient = OkHttpClient(),
            bandwidthMonitor = BandwidthMonitor(),
            fillController = fillController ?: FillController(
                profile = profile,
                cache = cache,
                cacheKey = cacheKey,
                playbackByteProvider = playbackByteProvider
            ),
            rangeCoordinator = rangeCoordinator,
            playbackByteProvider = playbackByteProvider,
            safetyGapBytes = safetyGapBytes
        )
    }
```

- [ ] **Step 2: Run urgent worker tests to verify failure**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.CacheFillWorkerTest
```

Expected: FAIL with unresolved references `prioritize`, `URGENT_FRAGMENT_SIZE`, or `fragmentBytes`.

- [ ] **Step 3: Implement urgent worker command and fragment size**

Modify `app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt`:

```kotlin
    private val pendingUrgentTarget = AtomicLong(NO_PENDING_SEEK)
```

Add this public method near `seekTo()`:

```kotlin
    fun prioritize(position: Long) {
        synchronized(controlLock) {
            pendingUrgentTarget.set(position.coerceAtLeast(0L))
            commandSerial.incrementAndGet()
            pauseRequested.set(false)
            cancelActiveCall()
        }
    }
```

In `requestStopLocked(...)`, add:

```kotlin
        pendingUrgentTarget.set(NO_PENDING_SEEK)
```

In `launchStartLocked(...)`, add:

```kotlin
        pendingUrgentTarget.set(NO_PENDING_SEEK)
```

At the top of the `while` loop inside `run(...)`, before reading `pendingSeekTarget`, add:

```kotlin
                var fragmentBytes = profile.normalFragmentBytes
                val urgentTarget = pendingUrgentTarget.getAndSet(NO_PENDING_SEEK)
                if (urgentTarget != NO_PENDING_SEEK) {
                    fillFrontier = urgentTarget.coerceAtMost(contentLength)
                    fragmentBytes = URGENT_FRAGMENT_SIZE
                }
```

Change the existing `downloadChunkToCache(...)` call in `run(...)` to pass the fragment size:

```kotlin
                    downloadChunkToCache(
                        url = url,
                        headers = headers,
                        start = start,
                        end = end,
                        workerGeneration = workerGeneration,
                        resultCommandSerial = resultCommandSerial,
                        fragmentBytes = fragmentBytes
                    )
```

Change the `downloadChunkToCache(...)` signature:

```kotlin
    internal fun downloadChunkToCache(
        url: String,
        headers: Map<String, String>,
        start: Long,
        end: Long,
        workerGeneration: Long = generation.get(),
        resultCommandSerial: Long = commandSerial.get(),
        fragmentBytes: Long = profile.normalFragmentBytes
    ): ChunkResult {
```

Change the sink creation line:

```kotlin
                val sink = CacheDataSink(cache, fragmentBytes)
```

Update `canContinueChunk(...)` and `shouldYieldChunk(...)` so urgent requests cancel the current chunk:

```kotlin
            pendingSeekTarget.get() == NO_PENDING_SEEK &&
            pendingUrgentTarget.get() == NO_PENDING_SEEK
```

```kotlin
            pendingSeekTarget.get() != NO_PENDING_SEEK ||
            pendingUrgentTarget.get() != NO_PENDING_SEEK
```

Add the constant:

```kotlin
        const val URGENT_FRAGMENT_SIZE = 2L * 1024L * 1024L
```

- [ ] **Step 4: Implement urgent fill handler in session without breaking existing factory wiring**

Modify `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt` so the existing coordinator also satisfies the worker's smaller range-checking dependency:

```kotlin
internal class StreamingRangeCoordinator : PlaybackFallbackRangeChecker {
```

Mark the existing method as the interface implementation:

```kotlin
    override fun isOwnedByPlaybackFallback(start: Long, endExclusive: Long): Boolean {
```

Modify the `CacheFillWorker` constructor type in `app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt`:

```kotlin
    private val rangeCoordinator: PlaybackFallbackRangeChecker,
```

Modify class declaration in `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt`:

```kotlin
internal class StreamingCacheFillSession(
    private val cache: SimpleCache,
    private val cacheKeyFactory: CacheKeyFactory,
    private val okHttpClient: OkHttpClient,
    private val memoryBudget: MemoryBudget,
    private val rangeCoordinator: PlaybackFallbackRangeChecker,
    private val missCoordinator: StreamingCacheMissCoordinator? = rangeCoordinator as? StreamingCacheMissCoordinator,
    private val bandwidthMonitor: BandwidthMonitor = BandwidthMonitor(),
    private val profile: ProviderProfile = ProviderProfile()
) : StreamingCacheMissCoordinator.UrgentFillHandler {
```

This keeps the existing `PlayerMediaSourceFactory(rangeCoordinator = StreamingRangeCoordinator())` path compiling until Task 4 passes the real `StreamingCacheMissCoordinator`.

In `launchStartLocked(...)`, keep passing the range checker to the worker:

```kotlin
            rangeCoordinator = rangeCoordinator
```

Add these methods to `StreamingCacheFillSession`:

```kotlin
    override fun prioritize(position: Long) {
        synchronized(sessionLock) {
            worker?.prioritize(position)
        }
    }

    override fun awaitSpanCommitted(
        cacheKey: String,
        position: Long,
        minLength: Long,
        timeoutMs: Long
    ): Boolean {
        val normalizedLength = minLength.coerceAtLeast(1L)
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (android.os.SystemClock.elapsedRealtime() <= deadlineMs) {
            val cachedLength = cache.getCachedLength(cacheKey, position.coerceAtLeast(0L), normalizedLength)
            if (cachedLength > 0L) return true
            Thread.sleep(25L)
        }
        return false
    }

    override fun estimatedBytesPerSecond(): Long {
        return bandwidthMonitor.estimatedBytesPerSecond()
    }
```

In `launchStartLocked(...)`, after assigning `worker = nextWorker`, attach the handler when available:

```kotlin
        missCoordinator?.attachUrgentFillHandler(this)
```

In `stop()`, when a worker is fully stopped and before setting `worker = null`, detach the handler when available:

```kotlin
                missCoordinator?.detachUrgentFillHandler(this)
```

- [ ] **Step 5: Run worker/session tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.CacheFillWorkerTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest --tests com.nexio.tv.ui.screens.player.StreamingCacheMissCoordinatorTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinator.kt app/src/test/java/com/nexio/tv/ui/screens/player/CacheFillWorkerTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinatorTest.kt
git commit -m "feat: prioritize urgent streaming cache fills"
```

---

### Task 3: Coverage-Aware DataSource

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSource.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSourceTest.kt`

- [ ] **Step 1: Write failing DataSource tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSourceTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoverageAwareDataSourceTest {
    private lateinit var provider: StreamingCacheProvider
    private lateinit var cache: SimpleCache
    private val cacheKeyFactory = StableCacheKeyFactory()

    @Before
    fun setUp() {
        provider = StreamingCacheProvider(
            context = ApplicationProvider.getApplicationContext(),
            cacheDirectoryName = "coverage-aware-${System.nanoTime()}"
        )
        cache = provider.getOrCreateCache()
    }

    @After
    fun tearDown() {
        provider.release()
        provider.cacheDirectory.deleteRecursively()
    }

    @Test
    fun open_fullyCachedRange_readsCacheOnly() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val dataSpec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(4L).build()
        val cacheKey = cacheKeyFactory.buildCacheKey(dataSpec)
        writeCacheSpan(uri = uri, cacheKey = cacheKey, position = 0L, bytes = byteArrayOf(1, 2, 3, 4))
        val upstreamOpens = AtomicInteger(0)
        val source = dataSource(
            upstream = FakeDataSource(byteArrayOf(9, 9, 9, 9), upstreamOpens)
        )

        assertEquals(4L, source.open(dataSpec))
        val buffer = ByteArray(4)
        assertEquals(4, source.read(buffer, 0, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer)
        source.close()

        assertEquals(0, upstreamOpens.get())
    }

    @Test
    fun open_startupHole_marksFallbackBeforeUpstreamAndClearsOnClose() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())
        val upstream = FakeDataSource(byteArrayOf(10, 11, 12, 13), AtomicInteger(0)) {
            assertTrue(coordinator.isOwnedByPlaybackFallback(0L, 4L))
        }
        val source = dataSource(
            coordinator = coordinator,
            upstream = upstream,
            startup = true
        )
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(4L).build()

        source.open(spec)
        val buffer = ByteArray(4)
        assertEquals(4, source.read(buffer, 0, 4))
        source.close()

        assertArrayEquals(byteArrayOf(10, 11, 12, 13), buffer)
        assertFalse(coordinator.isOwnedByPlaybackFallback(0L, 4L))
    }

    @Test
    fun open_steadyHole_waitsForUrgentFillBeforeFallingBack() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(4L).build()
        val cacheKey = cacheKeyFactory.buildCacheKey(spec)
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())
        val urgentRequests = AtomicInteger(0)
        coordinator.attachUrgentFillHandler(object : StreamingCacheMissCoordinator.UrgentFillHandler {
            override fun prioritize(position: Long) {
                assertEquals(0L, position)
            }

            override fun awaitSpanCommitted(
                cacheKey: String,
                position: Long,
                minLength: Long,
                timeoutMs: Long
            ): Boolean {
                urgentRequests.incrementAndGet()
                writeCacheSpan(uri = uri, cacheKey = cacheKey, position = 0L, bytes = byteArrayOf(7, 8, 9, 10))
                return true
            }

            override fun estimatedBytesPerSecond(): Long = 8L * 1024L * 1024L
        })
        val upstreamOpens = AtomicInteger(0)
        val source = dataSource(
            coordinator = coordinator,
            upstream = FakeDataSource(byteArrayOf(1, 1, 1, 1), upstreamOpens),
            startup = false
        )

        source.open(spec)
        val buffer = ByteArray(4)
        assertEquals(4, source.read(buffer, 0, 4))
        source.close()

        assertArrayEquals(byteArrayOf(7, 8, 9, 10), buffer)
        assertEquals(1, urgentRequests.get())
        assertEquals(0, upstreamOpens.get())
    }

    @Test
    fun open_partialCacheReadsCachedPrefixThenFallbackSegment() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(6L).build()
        val cacheKey = cacheKeyFactory.buildCacheKey(spec)
        writeCacheSpan(uri = uri, cacheKey = cacheKey, position = 0L, bytes = byteArrayOf(1, 2))
        val source = dataSource(
            upstream = FakeDataSource(byteArrayOf(3, 4, 5, 6), AtomicInteger(0)),
            startup = true
        )

        source.open(spec)
        val buffer = ByteArray(6)
        assertEquals(2, source.read(buffer, 0, 6))
        assertEquals(4, source.read(buffer, 2, 4))
        source.close()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), buffer)
    }

    private fun dataSource(
        coordinator: StreamingCacheMissCoordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator()),
        upstream: DataSource,
        startup: Boolean = true
    ): CoverageAwareDataSource {
        return CoverageAwareDataSource(
            cache = cache,
            cacheKeyFactory = cacheKeyFactory,
            cacheReadDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setCacheKeyFactory(cacheKeyFactory)
                .setCacheWriteDataSinkFactory(null),
            upstreamDataSourceFactory = DataSource.Factory { upstream },
            coordinator = coordinator,
            isStartupProvider = { startup }
        )
    }

    private fun writeCacheSpan(uri: Uri, cacheKey: String, position: Long, bytes: ByteArray) {
        val mutations = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(mutations, position + bytes.size)
        cache.applyContentMetadataMutations(cacheKey, mutations)

        val lockedSpan = try {
            cache.startReadWrite(cacheKey, position, bytes.size.toLong())
        } catch (e: InterruptedException) {
            throw AssertionError("unexpected interruption while reserving cache span", e)
        }
        val sink = CacheDataSink(cache, 1024)
        try {
            sink.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setKey(cacheKey)
                    .setPosition(position)
                    .setLength(bytes.size.toLong())
                    .build()
            )
            try {
                sink.write(bytes, 0, bytes.size)
            } finally {
                sink.close()
            }
        } finally {
            if (lockedSpan.isHoleSpan) {
                cache.releaseHoleSpan(lockedSpan)
            }
        }
    }

    private class FakeDataSource(
        private val bytes: ByteArray,
        private val openCount: AtomicInteger,
        private val onOpen: () -> Unit = {}
    ) : DataSource {
        private var readPosition = 0
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            opened = true
            readPosition = 0
            openCount.incrementAndGet()
            onOpen()
            return dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() } ?: bytes.size.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!opened) error("read before open")
            if (readPosition >= bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - readPosition)
            bytes.copyInto(buffer, offset, readPosition, readPosition + count)
            readPosition += count
            return count
        }

        override fun getUri(): Uri? = Uri.parse("https://example.com/movie.mkv")

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit

        override fun close() {
            opened = false
        }
    }
}
```

- [ ] **Step 2: Run DataSource tests to verify failure**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.CoverageAwareDataSourceTest
```

Expected: FAIL with unresolved reference `CoverageAwareDataSource`.

- [ ] **Step 3: Implement `CoverageAwareDataSource`**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSource.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException

internal class CoverageAwareDataSource(
    private val cache: SimpleCache,
    private val cacheKeyFactory: CacheKeyFactory,
    private val cacheReadDataSourceFactory: DataSource.Factory,
    private val upstreamDataSourceFactory: DataSource.Factory,
    private val coordinator: StreamingCacheMissCoordinator,
    private val isStartupProvider: () -> Boolean
) : DataSource {
    private var activeSource: DataSource? = null
    private var pendingSpec: DataSpec? = null
    private var currentToken: String? = null
    private var currentUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private val transferListeners = mutableListOf<TransferListener>()

    override fun open(dataSpec: DataSpec): Long {
        closeActiveSegment()
        currentUri = dataSpec.uri
        pendingSpec = dataSpec
        openNextSegment()
        return dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val source = activeSource ?: return C.RESULT_END_OF_INPUT
            val read = source.read(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT) return read
            closeActiveSegment()
            if (pendingSpec == null) return C.RESULT_END_OF_INPUT
            openNextSegment()
        }
    }

    override fun getUri(): Uri? = activeSource?.uri ?: currentUri

    override fun getResponseHeaders(): Map<String, List<String>> {
        return activeSource?.responseHeaders ?: responseHeaders
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        activeSource?.addTransferListener(transferListener)
    }

    override fun close() {
        closeActiveSegment()
        pendingSpec = null
    }

    private fun openNextSegment() {
        val spec = pendingSpec ?: return
        val cacheKey = cacheKeyFactory.buildCacheKey(spec)
        val queryLength = queryLength(spec)
        val cachedLength = cache.getCachedLength(cacheKey, spec.position, queryLength)

        when {
            isFullyCached(spec, cachedLength, queryLength) -> {
                StreamingMetrics.cacheHits.incrementAndGet()
                openSegment(cacheReadDataSourceFactory.createDataSource(), spec)
                pendingSpec = null
            }
            cachedLength > 0L -> {
                StreamingMetrics.cacheHits.incrementAndGet()
                val segmentLength = cachedLength.coerceAtMost(queryLength)
                openSegment(cacheReadDataSourceFactory.createDataSource(), spec.withSegmentLength(segmentLength))
                pendingSpec = spec.afterSegment(segmentLength)
            }
            else -> openHoleSegment(spec, cacheKey, cachedLength, queryLength)
        }
    }

    private fun openHoleSegment(
        spec: DataSpec,
        cacheKey: String,
        cachedLength: Long,
        queryLength: Long
    ) {
        StreamingMetrics.cacheMisses.incrementAndGet()
        val holeLength = if (cachedLength < 0L) -cachedLength else queryLength
        val segmentLength = holeLength.coerceAtMost(queryLength).coerceAtLeast(1L)
        if (!isStartupProvider()) {
            val urgentLength = segmentLength.coerceAtMost(CacheFillWorker.URGENT_FRAGMENT_SIZE)
            val timeoutMs = computeWaitTimeoutMs()
            if (coordinator.requestUrgentFill(cacheKey, spec.position, urgentLength, timeoutMs)) {
                openNextSegment()
                return
            }
            StreamingMetrics.coordinatorWaitTimeouts.incrementAndGet()
        }

        val segmentSpec = spec.withSegmentLength(segmentLength)
        val endExclusive = spec.position + segmentLength
        val token = coordinator.markFallbackOwned(spec.position, endExclusive)
        try {
            openSegment(upstreamDataSourceFactory.createDataSource(), segmentSpec)
            currentToken = token
            pendingSpec = spec.afterSegment(segmentLength)
        } catch (error: Throwable) {
            coordinator.clearFallbackOwnership(token)
            throw error
        }
    }

    private fun openSegment(source: DataSource, spec: DataSpec) {
        transferListeners.forEach(source::addTransferListener)
        activeSource = source
        source.open(spec)
        currentUri = source.uri ?: spec.uri
        responseHeaders = source.responseHeaders
    }

    private fun closeActiveSegment() {
        val token = currentToken
        currentToken = null
        try {
            activeSource?.close()
        } finally {
            activeSource = null
            if (token != null) {
                coordinator.clearFallbackOwnership(token)
            }
        }
    }

    private fun isFullyCached(spec: DataSpec, cachedLength: Long, queryLength: Long): Boolean {
        return spec.length != C.LENGTH_UNSET.toLong() && cachedLength >= queryLength
    }

    private fun queryLength(spec: DataSpec): Long {
        return if (spec.length == C.LENGTH_UNSET.toLong()) {
            OPEN_ENDED_SEGMENT_BYTES
        } else {
            spec.length.coerceAtMost(OPEN_ENDED_SEGMENT_BYTES).coerceAtLeast(1L)
        }
    }

    private fun computeWaitTimeoutMs(): Long {
        val bytesPerSecond = coordinator.estimatedBytesPerSecond()
        if (bytesPerSecond <= 0L) return DEFAULT_WAIT_TIMEOUT_MS
        val fillTimeMs = CacheFillWorker.URGENT_FRAGMENT_SIZE * 1_000L / bytesPerSecond
        return (fillTimeMs * 3L / 2L).coerceIn(MIN_WAIT_TIMEOUT_MS, MAX_WAIT_TIMEOUT_MS)
    }

    private fun DataSpec.withSegmentLength(segmentLength: Long): DataSpec {
        return buildUpon()
            .setPosition(position)
            .setLength(segmentLength)
            .build()
    }

    private fun DataSpec.afterSegment(segmentLength: Long): DataSpec? {
        val nextPosition = position + segmentLength
        return if (length == C.LENGTH_UNSET.toLong()) {
            buildUpon()
                .setPosition(nextPosition)
                .setLength(C.LENGTH_UNSET.toLong())
                .build()
        } else {
            val remaining = length - segmentLength
            if (remaining <= 0L) {
                null
            } else {
                buildUpon()
                    .setPosition(nextPosition)
                    .setLength(remaining)
                    .build()
            }
        }
    }

    class Factory(
        private val cache: SimpleCache,
        private val cacheKeyFactory: CacheKeyFactory,
        private val cacheReadDataSourceFactory: DataSource.Factory,
        private val upstreamDataSourceFactory: DataSource.Factory,
        private val coordinator: StreamingCacheMissCoordinator,
        private val isStartupProvider: () -> Boolean
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return CoverageAwareDataSource(
                cache = cache,
                cacheKeyFactory = cacheKeyFactory,
                cacheReadDataSourceFactory = cacheReadDataSourceFactory,
                upstreamDataSourceFactory = upstreamDataSourceFactory,
                coordinator = coordinator,
                isStartupProvider = isStartupProvider
            )
        }
    }

    companion object {
        const val OPEN_ENDED_SEGMENT_BYTES = 4L * 1024L * 1024L
        const val DEFAULT_WAIT_TIMEOUT_MS = 3_000L
        const val MIN_WAIT_TIMEOUT_MS = 1_000L
        const val MAX_WAIT_TIMEOUT_MS = 5_000L
    }
}
```

- [ ] **Step 4: Run DataSource tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.CoverageAwareDataSourceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSource.kt app/src/test/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSourceTest.kt
git commit -m "feat: add coverage-aware streaming cache data source"
```

---

### Task 4: Playback Wiring And Startup Phase Signal

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write failing wiring tests**

Update `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`:

Replace the existing cache-on networking assertion:

```kotlin
        assertTrue(dataSource is androidx.media3.datasource.cache.CacheDataSource)
```

with:

```kotlin
        assertTrue(dataSource is CoverageAwareDataSource)
```

Add this test near the other playback networking tests:

```kotlin
    @Test
    fun playbackNetworking_flagOn_usesCoverageAwareDataSourceWithoutPlaybackCacheWrites() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "stream-cache-coverage-aware-${System.nanoTime()}"
        )
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())

        val factory = PlayerPlaybackNetworking.createDataSourceFactory(
            context = context,
            client = OkHttpClient(),
            defaultHeaders = emptyMap(),
            streamingCacheProvider = provider,
            useStreamingCache = true,
            cacheKeyFactory = StableCacheKeyFactory(),
            missCoordinator = coordinator,
            isStartupProvider = { true }
        )
        val dataSource = factory.createDataSource()

        assertTrue(dataSource is CoverageAwareDataSource)
        assertTrue(provider.hasCacheInstance)

        provider.release()
        provider.cacheDirectory.deleteRecursively()
    }
```

Add this test near `mediaSourceFactory_flagOnForHttp_opensStreamingCache()`:

```kotlin
    @Test
    fun mediaSourceFactory_resetsStartupPhaseForNewMediaSourceAndClearsAfterFirstFrame() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-startup-phase-${System.nanoTime()}"
        )
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = OkHttpClient(),
            streamingCacheProvider = provider
        )
        factory.streamingCacheEnabled = true

        factory.createMediaSource(url = "https://example.com/movie.mkv", headers = emptyMap())
        assertTrue(factory.isStreamingCacheStartupForTesting)

        factory.onStreamingCacheFirstFrameRendered()
        assertFalse(factory.isStreamingCacheStartupForTesting)

        factory.createMediaSource(url = "https://example.com/other.mkv", headers = emptyMap())
        assertTrue(factory.isStreamingCacheStartupForTesting)

        factory.shutdown()
    }
```

- [ ] **Step 2: Run wiring tests to verify failure**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: FAIL because `missCoordinator`, `isStartupProvider`, `onStreamingCacheFirstFrameRendered`, or `isStreamingCacheStartupForTesting` does not exist.

- [ ] **Step 3: Wire `CoverageAwareDataSource` in playback networking**

Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`.

Change the function signature:

```kotlin
    fun createDataSourceFactory(
        context: Context,
        client: OkHttpClient,
        defaultHeaders: Map<String, String> = emptyMap(),
        streamingCacheProvider: StreamingCacheProvider? = null,
        useStreamingCache: Boolean = false,
        cacheKeyFactory: CacheKeyFactory = StableCacheKeyFactory(),
        missCoordinator: StreamingCacheMissCoordinator? = null,
        isStartupProvider: () -> Boolean = { true },
    ): DataSource.Factory {
```

Replace the existing `trackedUpstreamFactory` and returned `CacheDataSource.Factory()` block with:

```kotlin
        val coordinator = missCoordinator ?: StreamingCacheMissCoordinator(StreamingRangeCoordinator())
        val cacheReadFactory = CacheDataSource.Factory()
            .setCache(streamingCacheProvider.getOrCreateCache())
            .setCacheKeyFactory(cacheKeyFactory)
            .setCacheWriteDataSinkFactory(null)

        return CoverageAwareDataSource.Factory(
            cache = streamingCacheProvider.getOrCreateCache(),
            cacheKeyFactory = cacheKeyFactory,
            cacheReadDataSourceFactory = cacheReadFactory,
            upstreamDataSourceFactory = upstreamFactory,
            coordinator = coordinator,
            isStartupProvider = isStartupProvider
        )
```

Remove the unused `PlaybackFallbackTrackingDataSource` wrapping from `PlayerPlaybackNetworking`. Do not delete `PlaybackFallbackTrackingDataSource.kt` in this task; keep deletion as a later cleanup only if no references remain after all tests pass.

- [ ] **Step 4: Wire miss coordinator and startup phase in media source factory**

Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`.

Add fields near `rangeCoordinator`:

```kotlin
    private val missCoordinator = StreamingCacheMissCoordinator(rangeCoordinator)
    private val streamingBandwidthMonitor = BandwidthMonitor()

    @Volatile
    private var streamingCacheStartup = true

    @VisibleForTesting
    internal val isStreamingCacheStartupForTesting: Boolean
        get() = streamingCacheStartup
```

When creating the streaming-cache playback factory, pass the coordinator and startup provider:

```kotlin
                missCoordinator = missCoordinator,
                isStartupProvider = { streamingCacheStartup }
```

Before building the media item in `createMediaSource(...)`, reset startup for streaming-cache HTTP sources:

```kotlin
        if (useStreamingCache) {
            streamingCacheStartup = true
        }
```

When creating `StreamingCacheFillSession`, replace the existing `rangeCoordinator = rangeCoordinator` argument with:

```kotlin
            rangeCoordinator = missCoordinator,
            missCoordinator = missCoordinator,
            bandwidthMonitor = streamingBandwidthMonitor
```

Add this method:

```kotlin
    fun onStreamingCacheFirstFrameRendered() {
        streamingCacheStartup = false
    }
```

- [ ] **Step 5: Notify media source factory on first rendered frame**

Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt` in `onRenderedFirstFrame()`:

```kotlin
                        mediaSourceFactory.onStreamingCacheFirstFrameRendered()
                        hasRenderedFirstFrame = true
```

Put the call immediately before `hasRenderedFirstFrame = true` so seek/startup telemetry still sees the old value in the existing `if (!hasRenderedFirstFrame)` block.

- [ ] **Step 6: Run wiring tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest --tests com.nexio.tv.ui.screens.player.CoverageAwareDataSourceTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: route streaming cache playback through coverage checks"
```

---

### Task 5: Remove Obsolete Playback Fallback Wrapper And Add Regression Guards

**Files:**
- Delete or keep: `app/src/main/java/com/nexio/tv/ui/screens/player/PlaybackFallbackTrackingDataSource.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinatorTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStreamingCacheFillWiringTest.kt`

- [ ] **Step 1: Search references**

Run:

```bash
rg -n "PlaybackFallbackTrackingDataSource|rangeCoordinator =" app/src/main/java app/src/test/java
```

Expected: `PlaybackFallbackTrackingDataSource` appears only in its own file and old tests. `rangeCoordinator =` should not appear in `PlayerPlaybackNetworking.createDataSourceFactory(...)` call sites.

- [ ] **Step 2: Delete obsolete wrapper only if unreferenced**

If the search result confirms `PlaybackFallbackTrackingDataSource` is unreferenced by production code, delete `app/src/main/java/com/nexio/tv/ui/screens/player/PlaybackFallbackTrackingDataSource.kt`.

Update `StreamingRangeCoordinatorTest.kt` by deleting the two tests named:

```kotlin
playbackFallbackTrackingDataSource_clearsOwnershipWhenOpenFails
playbackFallbackTrackingDataSource_ownsOpenEndedUnsetLengthRange
```

Keep the pure interval tests.

- [ ] **Step 3: Add source guard for no playback CacheDataSource upstream fallback**

Append this test to `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStreamingCacheFillWiringTest.kt`:

```kotlin
    @Test
    fun playbackNetworking_doesNotUseCacheDataSourceUpstreamFallbackWhenStreamingCacheEnabled() {
        val source = sourceFile(
            "com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt"
        ).toFile().readText()

        val streamingBlock = source.substringAfter("if (!useStreamingCache || streamingCacheProvider == null)")

        assertFalse(
            "streaming-cache playback must not use CacheDataSource upstream fallback",
            streamingBlock.contains(".setUpstreamDataSourceFactory(")
        )
        assertTrue(streamingBlock.contains("CoverageAwareDataSource.Factory("))
        assertTrue(streamingBlock.contains(".setCacheWriteDataSinkFactory(null)"))
    }
```

Use the existing `sourceFile(...)` helper in `PlayerStreamingCacheFillWiringTest.kt`. If the helper is private and only accepts current file paths, adjust the helper to resolve any player source file:

```kotlin
    private fun sourceFile(relativePath: String): Path {
        val cwd = Paths.get("").toAbsolutePath().normalize()
        val directCandidate = cwd.resolve("app/src/main/java").resolve(relativePath)
        val parentCandidate = cwd.resolve("..").resolve("app/src/main/java").resolve(relativePath).normalize()
        return when {
            Files.exists(directCandidate) -> directCandidate
            Files.exists(parentCandidate) -> parentCandidate
            else -> error("Unable to locate $relativePath from working directory $cwd")
        }
    }
```

- [ ] **Step 4: Run regression guard tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.StreamingRangeCoordinatorTest --tests com.nexio.tv.ui.screens.player.PlayerStreamingCacheFillWiringTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlaybackFallbackTrackingDataSource.kt app/src/test/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinatorTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStreamingCacheFillWiringTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "test: guard coverage-aware streaming cache playback path"
```

If `PlaybackFallbackTrackingDataSource.kt` was already absent or intentionally kept because another test still uses it, omit it from `git add` and mention the reason in the commit body.

---

### Task 6: Phase 4 Verification

**Files:**
- No production files unless a test exposes a defect.

- [ ] **Step 1: Run targeted Phase 4 test suite**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.CoverageAwareDataSourceTest --tests com.nexio.tv.ui.screens.player.StreamingCacheMissCoordinatorTest --tests com.nexio.tv.ui.screens.player.StreamingRangeCoordinatorTest --tests com.nexio.tv.ui.screens.player.CacheFillWorkerTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest --tests com.nexio.tv.ui.screens.player.PlayerStreamingCacheFillWiringTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run Kotlin compile**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run scope guard**

Run:

```bash
git diff --name-only origin/main..HEAD
```

Expected changed files only under:

```text
app/src/main/java/com/nexio/tv/ui/screens/player/
app/src/test/java/com/nexio/tv/ui/screens/player/
```

Explicitly confirm there are no changes to:

```text
app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt
```

- [ ] **Step 4: Run final build**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:assembleUniversalDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Install debug build for validation**

Run:

```bash
adb -s 192.168.50.71:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
```

Expected: `Success`.

- [ ] **Step 6: Validate on device**

Manual validation sequence:

```text
1. Launch same remux source with streaming cache OFF.
2. Confirm no `CacheFill-0` thread and no new `cache/stream-cache` files for the debug package.
3. Launch same remux source with streaming cache ON.
4. Capture startup snapshot:
   adb -s 192.168.50.71:5555 shell pidof com.nexiodebug.tv
   adb -s 192.168.50.71:5555 shell dumpsys meminfo com.nexiodebug.tv
   adb -s 192.168.50.71:5555 shell ps -T -p <pid> | grep -E 'CacheFill|ExoPlayer|OkHttp'
   adb -s 192.168.50.71:5555 shell run-as com.nexiodebug.tv du -ah cache/stream-cache | tail -100
5. Let playback run for 5+ minutes and capture the same snapshot.
6. Seek forward and backward, wait for playback to settle, and capture the same snapshot.
7. Check recent logs:
   adb -s 192.168.50.71:5555 logcat -d --pid <pid> -t 1000 | grep -Ei 'fatal|crash|exception|rebuffer|CoverageAware|StreamingCache|CacheFill|SEEK_FIRST_FRAME'
8. Check exit info:
   adb -s 192.168.50.71:5555 shell dumpsys activity exit-info com.nexiodebug.tv
```

Pass criteria:

```text
A1: playback cache path still has no cache write sink.
A2: logs and coordinator behavior show fallback is marked before upstream opens.
A3: CacheFillWorker read buffer remains 512 KB and single connection remains under 2 MB.
A5: stream-cache stays bounded and seek-responsive.
A7: no observed rebuffer regression versus Phase 3 ON.
A9/A10: no new LOW_MEMORY or SIGNALED exit.
```

- [ ] **Step 7: Commit final fixes if any**

If verification required fixes, commit only those files:

```bash
git add <fixed-files>
git commit -m "fix: stabilize coverage-aware streaming cache playback"
```

If verification required no fixes, do not create an empty commit.

---

## Self-Review

**Spec coverage:** Phase 4 is covered by Tasks 1-5. Task 1 creates the coordination facade. Task 2 gives the fill worker urgent exact-position fill. Task 3 implements span-coverage-first playback segmentation. Task 4 wires the playback factory and first-frame startup state. Task 5 removes the obsolete transparent upstream fallback guard and adds regression coverage. Task 6 verifies A1, A2, A3, A5, A7, A9, and A10 at build and device level.

**Placeholder scan:** This plan avoids `TBD`, `TODO`, "implement later", and "write tests for the above" without test bodies. The only conditional step is deleting `PlaybackFallbackTrackingDataSource.kt`, and it includes an exact search command plus exact conditions.

**Type consistency:** The plan uses one new coordinator type, `StreamingCacheMissCoordinator`, and one new playback type, `CoverageAwareDataSource`. `StreamingCacheFillSession` implements `StreamingCacheMissCoordinator.UrgentFillHandler`. `CacheFillWorker.prioritize(position: Long)` and `CacheFillWorker.URGENT_FRAGMENT_SIZE` are defined before they are used by `CoverageAwareDataSource`.
