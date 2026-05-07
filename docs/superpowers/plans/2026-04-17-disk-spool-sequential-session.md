# Disk Spool Sequential Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make disk spool mode strictly single-writer/sequential and remove `DiskSpoolSession` range sorting/rebuilding so SD-card playback does less CPU work per write.

**Architecture:** Disk spool mode will no longer use parallel writer workers under any setting. `DiskSpoolWriter` will write one HTTP range stream at a time, and `DiskSpoolSession` will track only a contiguous frontier/window. Writes that are stale, overlapping, or out-of-order will be ignored instead of sorted/merged, because disk spool mode is now explicitly sequential.

**Tech Stack:** Kotlin, Android Media3 `DataSource`, OkHttp/Okio, `RandomAccessFile`, JUnit/Robolectric tests.

---

## Scope

This plan implements only:

- Force disk spool mode to single-writer operation even when the global parallel-connection toggle is enabled.
- Remove parallel disk spool writer behavior.
- Remove `DiskSpoolSession` sorted range tracking and replace it with sequential contiguous frontier tracking.

This plan intentionally does not implement:

- Adaptive headroom writes.
- Startup buffer setting changes.
- 512KB I/O buffer changes.
- PRDS changes outside disk spool mode.
- VOD cache changes.

## Current Code Context

The current disk spool path is:

1. `PlayerMediaSourceFactory.createMediaSource(...)`
2. `PlayerMediaSourceFactory.selectProgressiveUpstreamFactory(...)`
3. `PlayerMediaSourceFactory.createDiskSpoolFactoryIfEligible(...)`
4. `PlayerMediaSourceFactory.scheduleDiskSpoolWriter(...)`
5. `DiskSpoolWriter.downloadUntil(...)`
6. `DiskSpoolSession.writeRange(...)`
7. `DiskSpoolDataSource.read(...)`

Current problem areas:

- `PlayerMediaSourceFactory.createDiskSpoolFactoryIfEligible(...)` passes parallel profiles into disk spool when `useParallelConnections = true`.
- `DiskSpoolWriter.downloadUntil(...)` can run `downloadParallel(...)`.
- `DiskSpoolSession.recordRangeLocked(...)` creates/sorts/merges a range list on every write.
- On external/SD-card storage, the repeated write + sort/merge bookkeeping is wasteful because disk spool mode should be sequential.

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Force disk spool writer profile to one connection.
  - Keep normal non-spool PRDS behavior unchanged.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
  - Remove `downloadParallel(...)`.
  - Make `downloadUntil(...)` always use `downloadSequentially(...)` after startup priority.
  - Retain constructor compatibility if practical, but ignore `parallelConnections`.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt`
  - Remove `Range`, `ranges`, `recordRangeLocked(...)`, `computeFrontierLocked(...)`, `updateFrontierAndWindowLocked(...)`, and `pruneRangesLocked(...)`.
  - Advance `frontier` only when `writeRange(start)` equals the current frontier.

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - Replace parallel disk spool profile test with single-writer disk spool invariant.

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`
  - Replace parallel writer scheduling test with an invariant that parallel requests are ignored and ranges are still sequential.

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSessionTest.kt`
  - Replace overlap merge expectations with sequential-only expectations.
  - Add tests that out-of-order writes do not advance the frontier.

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt`
  - Remove/replace any test that expects parallel disk spool behavior.

---

### Task 1: Force Disk Spool Factory To Single Writer

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Replace the existing parallel disk spool profile test**

In `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`, replace the test named:

```kotlin
fun progressivePlayback_passesParallelProfileIntoDiskSpoolWriterWhenEnabled()
```

with this test:

```kotlin
@Test
fun progressivePlayback_usesSingleConnectionDiskSpoolEvenWhenParallelConnectionsEnabled() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val capturedProfiles = mutableListOf<Pair<Int, Int>>()
    val factory = PlayerMediaSourceFactory(
        context = context,
        playbackOkHttpClient = noNetworkOkHttpClient()
    ).apply {
        useParallelConnections = true
        progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
        diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
        diskSpoolWriterExecutorForTesting = Executor { }
        diskSpoolWriterProfileObserverForTesting = { connections, chunkBytes, _ ->
            capturedProfiles += connections to (chunkBytes / 1024 / 1024)
        }
    }

    factory.progressiveUpstreamFactoryForTesting(
        url = "https://real-debrid.com/path/video.mkv",
        headers = emptyMap()
    )

    assertEquals(listOf(1 to 24), capturedProfiles)
    factory.shutdown()
}
```

- [ ] **Step 2: Run the test to verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.progressivePlayback_usesSingleConnectionDiskSpoolEvenWhenParallelConnectionsEnabled
```

Expected: FAIL because current disk spool mode reports a provider-specific parallel profile such as `2 to 18` for Real-Debrid.

- [ ] **Step 3: Implement single-writer factory routing**

In `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`, inside `createDiskSpoolFactoryIfEligible(...)`, replace the existing `diskSpoolProfile` assignment:

```kotlin
val diskSpoolProfile = if (parallelConnectionsEnabled) {
    resolveParallelProviderProfiles(
        url = url,
        warmAheadEnabledForStream = false,
        fallbackConnectionCount = fallbackParallelConnectionCount,
        fallbackChunkSizeMb = fallbackParallelChunkSizeMb
    ).playback
} else {
    ParallelProviderProfile(connectionCount = 1, chunkSizeMb = SAFE_DEFAULT_PARALLEL_CHUNK_SIZE_MB)
}
```

with:

```kotlin
val diskSpoolProfile = ParallelProviderProfile(
    connectionCount = 1,
    chunkSizeMb = SAFE_DEFAULT_PARALLEL_CHUNK_SIZE_MB
)
```

Do not modify the normal non-spool PRDS branch in `selectProgressiveUpstreamFactory(...)`.

- [ ] **Step 4: Run the factory test to verify GREEN**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.progressivePlayback_usesSingleConnectionDiskSpoolEvenWhenParallelConnectionsEnabled
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "force disk spool to single writer mode"
```

---

### Task 2: Make DiskSpoolSession Sequential-Only

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSessionTest.kt`

- [ ] **Step 1: Replace overlap merge test**

In `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSessionTest.kt`, replace:

```kotlin
@Test
fun `overlapping writes advance frontier through overlap`() {
    val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)

    session.writeRange(start = 0L, bytes = ByteArray(100) { 1 }, length = 100)
    session.writeRange(start = 50L, bytes = ByteArray(150) { 2 }, length = 150)

    assertEquals(200L, session.contiguousFrontierBytes())

    session.close()
}
```

with:

```kotlin
@Test
fun `overlapping writes are ignored in sequential spool mode`() {
    val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)

    session.writeRange(start = 0L, bytes = ByteArray(100) { 1 }, length = 100)
    session.writeRange(start = 50L, bytes = ByteArray(150) { 2 }, length = 150)

    assertEquals(100L, session.contiguousFrontierBytes())

    session.close()
}
```

- [ ] **Step 2: Add explicit sequential frontier tests**

Add these tests to `DiskSpoolSessionTest.kt` near the other frontier tests:

```kotlin
@Test
fun `sequential writes advance frontier without range bookkeeping`() {
    val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)

    session.writeRange(start = 0L, bytes = ByteArray(100) { 1 }, length = 100)
    session.writeRange(start = 100L, bytes = ByteArray(150) { 2 }, length = 150)

    assertEquals(250L, session.contiguousFrontierBytes())

    session.close()
}

@Test
fun `out of order future writes are ignored in sequential spool mode`() {
    val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)

    session.writeRange(start = 128L, bytes = ByteArray(64) { 9 }, length = 64)

    assertEquals(0L, session.contiguousFrontierBytes())

    session.writeRange(start = 0L, bytes = ByteArray(128) { 1 }, length = 128)

    assertEquals(128L, session.contiguousFrontierBytes())

    session.close()
}
```

- [ ] **Step 3: Run session tests to verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolSessionTest
```

Expected: FAIL because current `recordRangeLocked(...)` merges overlapping and out-of-order ranges.

- [ ] **Step 4: Implement sequential-only session frontier**

In `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt`, delete:

```kotlin
private data class Range(
    val start: Long,
    val endExclusive: Long
)
```

Delete:

```kotlin
private val ranges = mutableListOf<Range>()
```

In `rebaseTo(position)`, replace the synchronized body:

```kotlin
ensureOpenLocked()
ranges.clear()
windowStart.set(position)
frontier.set(position)
lock.notifyAll()
```

with:

```kotlin
ensureOpenLocked()
windowStart.set(position)
frontier.set(position)
lock.notifyAll()
```

In `writeRange(...)`, after:

```kotlin
val currentWindowStart = windowStart.get()
if (start < currentWindowStart) {
    return
}
```

add:

```kotlin
val currentFrontier = frontier.get()
if (start != currentFrontier) {
    return
}
```

Replace:

```kotlin
recordRangeLocked(start, start + length.toLong())
updateFrontierAndWindowLocked()
```

with:

```kotlin
val nextFrontier = start + length.toLong()
frontier.set(nextFrontier)
windowStart.set(maxOf(windowStart.get(), nextFrontier - capacityBytes))
```

Delete these functions completely:

```kotlin
private fun updateFrontierAndWindowLocked() {
    val currentFrontier = computeFrontierLocked()
    frontier.set(currentFrontier)
    val nextWindowStart = maxOf(windowStart.get(), currentFrontier - capacityBytes)
    windowStart.set(nextWindowStart)
    pruneRangesLocked()
}

private fun computeFrontierLocked(): Long {
    var current = windowStart.get()
    for (range in ranges) {
        if (range.endExclusive <= current) {
            continue
        }
        if (range.start > current) {
            break
        }
        current = maxOf(current, range.endExclusive)
    }
    return current
}

private fun recordRangeLocked(start: Long, endExclusive: Long) {
    ranges.add(Range(start, endExclusive))
    ranges.sortBy { it.start }

    val merged = ArrayList<Range>(ranges.size)
    for (range in ranges) {
        val last = merged.lastOrNull()
        if (last == null || range.start > last.endExclusive) {
            merged.add(range)
        } else {
            merged[merged.lastIndex] = last.copy(endExclusive = maxOf(last.endExclusive, range.endExclusive))
        }
    }

    ranges.clear()
    ranges.addAll(merged)
}

private fun pruneRangesLocked() {
    val currentWindowStart = windowStart.get()
    if (ranges.isEmpty()) return

    val retained = ranges.filter { it.endExclusive > currentWindowStart }
    ranges.clear()
    ranges.addAll(retained)
}
```

Keep `readFullyLocked(...)` and `ensureOpenLocked()` unchanged.

- [ ] **Step 5: Run session tests to verify GREEN**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolSessionTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSessionTest.kt
git commit -m "simplify disk spool sequential frontier tracking"
```

---

### Task 3: Remove Parallel DiskSpoolWriter Path

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt`

- [ ] **Step 1: Replace parallel writer scheduling test**

In `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`, replace the test named:

```kotlin
fun `parallel writer schedules multiple adjacent range requests into one session`
```

with:

```kotlin
@Test
fun `disk spool writer ignores parallel connection requests`() {
    val content = ByteArray(96 * 1024) { (it % 251).toByte() }
    val requestedRanges = java.util.concurrent.CopyOnWriteArrayList<String>()
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")
            if (range != null) requestedRanges += range
            return when (range) {
                "bytes=0-0" -> MockResponse()
                    .setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes 0-0/${content.size}")
                    .setHeader("Content-Length", 1)
                    .setBody(Buffer().writeByte(0x2A))
                else -> rangedResponse(content, range ?: error("Missing range"))
            }
        }
    }
    server.start()
    val session = DiskSpoolSession(File(temp.root, "single-writer.spool"), capacityBytes = 128 * 1024L)

    try {
        DiskSpoolWriter(
            okHttpClient = OkHttpClient(),
            chunkBytes = 32 * 1024,
            ioBufferBytes = 4 * 1024,
            parallelConnections = 3,
            startupPriorityBytes = 64 * 1024L
        ).downloadUntil(server.url("/movie.bin").toString(), session, content.size.toLong())

        val buffer = ByteArray(content.size)
        assertEquals(content.size, session.read(0L, buffer, 0, buffer.size))
        assertArrayEquals(content, buffer)
        assertEquals(
            listOf("bytes=0-0", "bytes=0-32767", "bytes=32768-65535", "bytes=65536-98303"),
            requestedRanges.toList()
        )
    } finally {
        session.close()
        server.shutdown()
    }
}
```

- [ ] **Step 2: Run the writer test to verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest.disk\\ spool\\ writer\\ ignores\\ parallel\\ connection\\ requests
```

Expected: FAIL because the current writer uses multiple workers when `parallelConnections = 3`.

- [ ] **Step 3: Remove parallel writer implementation**

In `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`, remove these imports:

```kotlin
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
```

Remove:

```kotlin
private val normalizedParallelConnections = parallelConnections.coerceAtLeast(1)
```

In the constructor, change:

```kotlin
private val parallelConnections: Int = 1,
```

to:

```kotlin
@Suppress("UNUSED_PARAMETER")
private val parallelConnections: Int = 1,
```

If the compiler rejects annotation placement on the constructor parameter, use:

```kotlin
@Suppress("UNUSED_PARAMETER") parallelConnections: Int = 1,
```

In `downloadUntil(...)`, replace:

```kotlin
if (normalizedParallelConnections <= 1) {
    downloadSequentially(
        url = url,
        bridge = bridge,
        targetFrontierBytes = targetFrontierBytes,
        contentLength = metadata.contentLength
    )
} else {
    downloadParallel(
        url = url,
        bridge = bridge,
        targetFrontierBytes = targetFrontierBytes,
        contentLength = metadata.contentLength
    )
}
```

with:

```kotlin
downloadSequentially(
    url = url,
    bridge = bridge,
    targetFrontierBytes = targetFrontierBytes,
    contentLength = metadata.contentLength
)
```

Delete the entire `downloadParallel(...)` function.

- [ ] **Step 4: Update pipeline tests that expected parallel behavior**

In `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt`, find any test that constructs `DiskSpoolWriter(... parallelConnections = 2 or 3 ...)` and expects parallel range behavior.

Change the assertion to validate content correctness and sequential ranges, not parallel scheduling. Use this expected range shape for a 64KB chunk test:

```kotlin
assertEquals(
    listOf("bytes=0-0", "bytes=0-65535", "bytes=65536-131071", "bytes=131072-196607", "bytes=196608-262143"),
    requestedRanges.toList()
)
```

If the existing test does not collect `requestedRanges`, add:

```kotlin
val requestedRanges = java.util.concurrent.CopyOnWriteArrayList<String>()
```

and in the `MockWebServer` dispatcher:

```kotlin
request.getHeader("Range")?.let { requestedRanges += it }
```

- [ ] **Step 5: Run writer and pipeline tests to verify GREEN**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolPipelineTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt
git commit -m "remove parallel disk spool writer path"
```

---

### Task 4: Focused Verification

**Files:**
- No production files unless tests expose a real issue.

- [ ] **Step 1: Run all disk spool tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolSessionTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSourceTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolPipelineTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageResolverTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageDiagnosticTest \
  --tests com.nexio.tv.ui.screens.player.spool.SpoolStorageCapabilityTest \
  --tests com.nexio.tv.ui.screens.player.spool.SpoolStorageCapabilityProbeTest
```

Expected: PASS.

- [ ] **Step 2: Run media source factory tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: PASS. If this suite still hits the known Robolectric `Environment.getExternalStorageState(...) must not be null` issue, run the disk-spool-specific factory tests instead:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.progressivePlayback_usesSingleConnectionDiskSpoolEvenWhenParallelConnectionsEnabled \
  --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.progressivePlayback_usesDiskSpoolFactoryWhenEnabledAndProbePasses \
  --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.progressivePlayback_passesSanitizedHeadersIntoDiskSpoolWriter \
  --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.progressivePlayback_usesExternalDiskSpoolStorageWhenSelected
```

Expected: PASS.

- [ ] **Step 3: Run diff hygiene**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 4: Optional device validation after a build is installed**

After the user starts disk spool playback on `192.168.50.58`, run:

```bash
adb connect 192.168.50.58:5555
adb -s 192.168.50.58:5555 shell dumpsys gfxinfo com.nexio.tv reset
adb -s 192.168.50.58:5555 logcat -c
sleep 45
adb -s 192.168.50.58:5555 shell dumpsys media_session | rg -n "com.nexio.tv|PlaybackState|state=|position=|buffered position|error="
adb -s 192.168.50.58:5555 shell top -H -b -n 1 -p "$(adb -s 192.168.50.58:5555 shell pidof com.nexio.tv)" | head -80
adb -s 192.168.50.58:5555 shell dumpsys gfxinfo com.nexio.tv
adb -s 192.168.50.58:5555 logcat -d | rg -n "DiskSpool|Nexio-disk-spoo|Background concurrent mark compact GC|WaitForGcToComplete|JankStats|AudioTrack.*underrun|BUFFERING"
```

Expected:

- Media session remains `PLAYING`.
- `Nexio-disk-spoo` CPU should be lower or less bursty than the pre-change sequential+sorting implementation on the same storage.
- Clean `gfxinfo` window remains under 1% jank.
- No `AudioTrack underrun`.
- No `BUFFERING`.
- No blocking `WaitForGcToComplete`.

---

## Non-Goals

- Do not implement adaptive headroom in this plan.
- Do not change the 512KB disk spool I/O buffer in this plan.
- Do not alter the user-facing disk spool settings.
- Do not change PRDS outside disk spool mode.
- Do not change VOD cache.
- Do not change progress saving, subtitle translation, or TheIntroDB behavior.

## Self-Review

**Spec coverage:** The plan covers the requested first executable slice: sequential disk spool only, no sort/rebuild range bookkeeping, no parallel disk spool mode. Adaptive headroom is intentionally split out into a later plan.

**Placeholder scan:** The plan contains no TBD/TODO/fill-in instructions and no incomplete code snippets.

**Type consistency:** Existing names are used consistently: `DiskSpoolSession.writeRange(...)`, `DiskSpoolSession.contiguousFrontierBytes()`, `DiskSpoolWriter.downloadUntil(...)`, and `PlayerMediaSourceFactory.createDiskSpoolFactoryIfEligible(...)`.

