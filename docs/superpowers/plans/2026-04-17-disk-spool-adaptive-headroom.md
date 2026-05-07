# Disk Spool Adaptive Headroom Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make disk spool playback maintain a bounded adaptive headroom instead of writing the full 2GB spool target, and make `DiskSpoolSession` strictly sequential so range sorting/rebuilding and parallel disk spool mode are removed.

**Architecture:** Disk spool becomes a single-writer sequential ring buffer. `DiskSpoolDataSource` reports read progress to the writer through `DiskSpoolSession`; the writer writes only up to `max(currentReadPosition + headroomBytes, startupPrebufferBytes)` and pauses when headroom is sufficient. Range tracking becomes a simple contiguous frontier/window model, not a sorted list.

**Tech Stack:** Android/Kotlin, Media3 `DataSource`, OkHttp/Okio, `RandomAccessFile`, Robolectric/JUnit tests, ADB logcat/gfxinfo for device validation.

---

## Research Notes

- The current 512KB disk spool I/O buffer is not the primary observed problem. Runtime evidence showed `Nexio-disk-spoo` CPU while frame health stayed acceptable, and the code shows each 512KB read/write also triggers synchronized range sorting/merging in `DiskSpoolSession`.
- Android's `BufferedOutputStream.write(byte[], off, len)` documentation says writes at least as large as the stream buffer are written directly to the underlying stream, avoiding redundant buffering. This supports using a reasonably large direct buffer rather than adding another small buffer layer.
- The local Okio dependency exposes `okio.Segment.SIZE = 8192`; the current 512KB buffer is 64 Okio segments. That is already a coarse bulk transfer size relative to Okio's internal segmentation.
- Apple’s file-system performance guidance, while not Android-specific, recommends larger sequential buffers such as 128KB to 256KB for disk operations. This suggests 512KB may be above the typical sweet spot, but the right value is device/storage dependent.
- Plan decision: keep the current 512KB default for the first implementation. Do not change buffer size in the same patch as headroom and sequential frontier changes. Add a small unit-tested constant so a follow-up A/B can compare 256KB vs 512KB without touching call-site logic.

Primary references used:

- Android `BufferedOutputStream` API reference: https://developer.android.com/reference/java/io/BufferedOutputStream
- Oracle `BufferedOutputStream` API reference: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/io/BufferedOutputStream.html
- Local Okio 3.10.2 bytecode inspection: `okio.Segment.SIZE = 8192`

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt`
  - Responsibility: single-writer ring-buffer state, contiguous frontier, reader wait/rebase coordination.
  - Change: remove `Range`, `ranges`, `recordRangeLocked`, `computeFrontierLocked`, and `pruneRangesLocked`; replace with sequential write validation and direct frontier/window update.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
  - Responsibility: HTTP range download loop for disk spool.
  - Change: remove `parallelConnections`, `downloadParallel`, worker pool, and all parallel behavior; add adaptive headroom loop that pauses when session frontier is sufficiently ahead of the consumer.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt`
  - Responsibility: Media3 read side for disk spool.
  - Change: publish read position to the session after successful reads and when opening at a nonzero position.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Responsibility: construct disk spool sessions/writers.
  - Change: pass adaptive headroom bytes to `DiskSpoolWriter`; force disk spool writer connection count to one regardless of the parallel-connections setting; delete tests/logic that profiles parallel disk spool workers.

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSessionTest.kt`
  - Responsibility: session frontier/ring behavior tests.
  - Change: replace overlap/out-of-order range tests with strict sequential behavior tests.

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`
  - Responsibility: writer range request and adaptive headroom behavior tests.
  - Change: add tests for adaptive pause/resume and remove/replace parallel writer test.

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt`
  - Responsibility: writer + data source integration tests.
  - Change: assert writer only advances to adaptive headroom until reader progresses.

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - Responsibility: factory routing tests.
  - Change: assert disk spool always schedules a single writer profile and never uses parallel disk spool mode.

---

### Task 1: Lock Disk Spool To Single-Writer Mode At The Factory

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write the failing factory test**

Add this test near the existing disk spool profile tests in `PlayerMediaSourceFactoryTest.kt`:

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

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.progressivePlayback_usesSingleConnectionDiskSpoolEvenWhenParallelConnectionsEnabled
```

Expected: FAIL because current code reports the Real-Debrid parallel profile, currently `2 to 18`.

- [ ] **Step 3: Implement single-writer disk spool routing**

In `PlayerMediaSourceFactory.createDiskSpoolFactoryIfEligible(...)`, replace the `diskSpoolProfile` branch with:

```kotlin
val diskSpoolProfile = ParallelProviderProfile(
    connectionCount = 1,
    chunkSizeMb = SAFE_DEFAULT_PARALLEL_CHUNK_SIZE_MB
)
```

Do not remove normal `ParallelRangeDataSource` support outside disk spool. This change applies only inside disk spool mode.

- [ ] **Step 4: Update/remove obsolete parallel disk spool test**

Replace the old test named:

```kotlin
fun progressivePlayback_passesParallelProfileIntoDiskSpoolWriterWhenEnabled()
```

with the new single-writer test from Step 1. There must be no test asserting disk spool receives more than one writer connection.

- [ ] **Step 5: Run the factory test and verify GREEN**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.progressivePlayback_usesSingleConnectionDiskSpoolEvenWhenParallelConnectionsEnabled
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "force disk spool to single writer mode"
```

---

### Task 2: Replace Range Sorting With Sequential Frontier Updates

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSessionTest.kt`

- [ ] **Step 1: Write failing sequential-session tests**

In `DiskSpoolSessionTest.kt`, replace the overlap test:

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

Add this new test:

```kotlin
@Test
fun `sequential writes advance frontier without range bookkeeping`() {
    val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)

    session.writeRange(start = 0L, bytes = ByteArray(100) { 1 }, length = 100)
    session.writeRange(start = 100L, bytes = ByteArray(150) { 2 }, length = 150)

    assertEquals(250L, session.contiguousFrontierBytes())

    session.close()
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolSessionTest
```

Expected: FAIL because overlapping writes currently merge and advance the frontier to `200L`.

- [ ] **Step 3: Implement strict sequential frontier logic**

In `DiskSpoolSession.kt`, remove:

```kotlin
private data class Range(
    val start: Long,
    val endExclusive: Long
)
private val ranges = mutableListOf<Range>()
```

In `rebaseTo(position)`, remove `ranges.clear()` and keep:

```kotlin
windowStart.set(position)
frontier.set(position)
lock.notifyAll()
```

In `writeRange(...)`, after stale-window guard and before writing, add:

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
frontier.set(start + length.toLong())
windowStart.set(maxOf(windowStart.get(), frontier.get() - capacityBytes))
```

Delete these functions entirely:

```kotlin
private fun updateFrontierAndWindowLocked()
private fun computeFrontierLocked()
private fun recordRangeLocked(start: Long, endExclusive: Long)
private fun pruneRangesLocked()
```

Keep `readFullyLocked(...)` and `ensureOpenLocked()` unchanged.

- [ ] **Step 4: Run session tests and verify GREEN**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolSessionTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSessionTest.kt
git commit -m "simplify disk spool sequential frontier tracking"
```

---

### Task 3: Remove Parallel Disk Spool Writer Implementation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt`

- [ ] **Step 1: Replace the parallel writer test with a single-writer invariant test**

In `DiskSpoolWriterTest.kt`, replace the existing test named `parallel writer schedules multiple adjacent range requests into one session` with:

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

- [ ] **Step 2: Run the writer test and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest.disk\\ spool\\ writer\\ ignores\\ parallel\\ connection\\ requests
```

Expected: FAIL because current writer schedules multiple range workers when `parallelConnections = 3`.

- [ ] **Step 3: Remove parallel writer code**

In `DiskSpoolWriter.kt`:

Remove these imports:

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

Keep the `parallelConnections` constructor parameter for now to avoid widespread call-site churn, but add this comment above it:

```kotlin
@Suppress("UNUSED_PARAMETER")
```

If Kotlin does not accept the annotation on the constructor parameter in this style, remove the constructor parameter and update call sites in `PlayerMediaSourceFactory` and tests in the same task.

- [ ] **Step 4: Run disk spool writer and pipeline tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolPipelineTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt
git commit -m "remove parallel disk spool writer path"
```

---

### Task 4: Add Adaptive Disk Spool Headroom

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt`

- [ ] **Step 1: Add failing adaptive headroom writer test**

Add this test to `DiskSpoolWriterTest.kt`:

```kotlin
@Test
fun `writer pauses after adaptive headroom target until reader advances`() {
    val content = ByteArray(512 * 1024) { (it % 251).toByte() }
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return when (val range = request.getHeader("Range")) {
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
    val session = DiskSpoolSession(File(temp.root, "adaptive.spool"), capacityBytes = 512 * 1024L)
    val writerFinished = AtomicInteger(0)
    val writerThread = Thread {
        DiskSpoolWriter(
            okHttpClient = OkHttpClient(),
            chunkBytes = 64 * 1024,
            ioBufferBytes = 8 * 1024,
            startupPriorityBytes = 64 * 1024L,
            adaptiveHeadroomBytes = 128 * 1024L,
            idlePollMs = 10L
        ).downloadUntil(server.url("/movie.bin").toString(), session, content.size.toLong())
        writerFinished.incrementAndGet()
    }

    try {
        writerThread.start()
        assertTrue(session.awaitFrontierAtLeast(128 * 1024L, timeoutMs = 2_000L))
        Thread.sleep(75L)
        assertTrue(session.contiguousFrontierBytes() <= 192 * 1024L)
        assertEquals(0, writerFinished.get())

        session.updateReadPosition(256 * 1024L)

        assertTrue(session.awaitFrontierAtLeast(384 * 1024L, timeoutMs = 2_000L))
        session.close()
        writerThread.join(2_000L)
        assertFalse(writerThread.isAlive)
    } finally {
        session.close()
        server.shutdown()
    }
}
```

- [ ] **Step 2: Run the adaptive writer test and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest.writer\\ pauses\\ after\\ adaptive\\ headroom\\ target\\ until\\ reader\\ advances
```

Expected: FAIL because `adaptiveHeadroomBytes`, `idlePollMs`, and `session.updateReadPosition(...)` do not exist and the current writer writes to full target.

- [ ] **Step 3: Add read-position state to `DiskSpoolSession`**

In `DiskSpoolSession.kt`, add:

```kotlin
private val readPosition = AtomicLong(0L)
```

Add these methods:

```kotlin
fun currentReadPositionBytes(): Long = readPosition.get()

fun updateReadPosition(position: Long) {
    if (position < 0L) return
    synchronized(lock) {
        val previous = readPosition.get()
        if (position > previous) {
            readPosition.set(position)
            lock.notifyAll()
        }
    }
}

fun adaptiveTargetFrontierBytes(
    maxFrontierBytes: Long,
    startupPrebufferBytes: Long,
    headroomBytes: Long
): Long {
    val safeMax = maxFrontierBytes.coerceAtLeast(0L)
    val startupTarget = startupPrebufferBytes.coerceAtLeast(0L)
    val readTarget = currentReadPositionBytes() + headroomBytes.coerceAtLeast(0L)
    val contentTarget = contentLength.get().takeIf { it > 0L } ?: safeMax
    return minOf(maxOf(startupTarget, readTarget), safeMax, contentTarget)
}
```

- [ ] **Step 4: Publish read progress from `DiskSpoolDataSource`**

In `DiskSpoolDataSource.open(...)`, after:

```kotlin
position = dataSpec.position
```

add:

```kotlin
session.updateReadPosition(position)
```

In `DiskSpoolDataSource.read(...)`, after:

```kotlin
position += read.toLong()
```

add:

```kotlin
session.updateReadPosition(position)
```

- [ ] **Step 5: Add adaptive parameters to `DiskSpoolWriter`**

Change `DiskSpoolWriter` constructor to:

```kotlin
internal class DiskSpoolWriter(
    private val okHttpClient: OkHttpClient,
    private val requestHeaders: Map<String, String> = emptyMap(),
    private val chunkBytes: Int = 18 * 1024 * 1024,
    private val ioBufferBytes: Int = DISK_SPOOL_IO_BUFFER_BYTES,
    @Suppress("UNUSED_PARAMETER")
    private val parallelConnections: Int = 1,
    private val startupPriorityBytes: Long = 100L * 1024L * 1024L,
    private val adaptiveHeadroomBytes: Long = DEFAULT_ADAPTIVE_HEADROOM_BYTES,
    private val idlePollMs: Long = DEFAULT_IDLE_POLL_MS
) {
```

Add constants near the bottom:

```kotlin
internal const val DISK_SPOOL_IO_BUFFER_BYTES = 512 * 1024
private const val DEFAULT_ADAPTIVE_HEADROOM_BYTES = 256L * 1024L * 1024L
private const val DEFAULT_IDLE_POLL_MS = 250L
```

- [ ] **Step 6: Change writer to use adaptive target**

In `downloadUntil(...)`, keep the startup target phase. Replace the second `downloadSequentially(...)` call with a loop:

```kotlin
while (!session.isClosed() && !Thread.currentThread().isInterrupted) {
    val target = session.adaptiveTargetFrontierBytes(
        maxFrontierBytes = targetFrontierBytes,
        startupPrebufferBytes = startupPriorityBytes,
        headroomBytes = adaptiveHeadroomBytes
    )
    val frontier = session.contiguousFrontierBytes()
    if (frontier >= target) {
        if (frontier >= minOf(targetFrontierBytes, metadata.contentLength)) return
        Thread.sleep(idlePollMs.coerceAtLeast(1L))
        continue
    }
    downloadSequentially(
        url = url,
        bridge = bridge,
        targetFrontierBytes = target,
        contentLength = metadata.contentLength
    )
}
```

This is intentionally simple. Do not add coroutines or a second signaling abstraction in this patch.

- [ ] **Step 7: Pass headroom from `PlayerMediaSourceFactory`**

In `PlayerMediaSourceFactory.scheduleDiskSpoolWriter(...)`, pass:

```kotlin
adaptiveHeadroomBytes = DISK_SPOOL_ADAPTIVE_HEADROOM_MB * BYTES_PER_MB
```

Add this companion constant:

```kotlin
private const val DISK_SPOOL_ADAPTIVE_HEADROOM_MB = 256L
```

This keeps the first implementation conservative: a 256MB spool window is far smaller than the current 2048MB full target while still large enough to absorb network variance.

- [ ] **Step 8: Run adaptive writer and pipeline tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSourceTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolPipelineTest
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSourceTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt
git commit -m "limit disk spool to adaptive playback headroom"
```

---

### Task 5: Keep 512KB I/O Buffer Explicit And Tested

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`

- [ ] **Step 1: Add a test documenting the default I/O buffer**

Add this test to `DiskSpoolWriterTest.kt`:

```kotlin
@Test
fun `default disk spool io buffer remains a bounded bulk transfer size`() {
    assertEquals(512 * 1024, DISK_SPOOL_IO_BUFFER_BYTES)
}
```

- [ ] **Step 2: Run the test and verify GREEN**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest.default\\ disk\\ spool\\ io\\ buffer\\ remains\\ a\\ bounded\\ bulk\\ transfer\\ size
```

Expected: PASS after Task 4 introduced `DISK_SPOOL_IO_BUFFER_BYTES`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt
git commit -m "document disk spool io buffer size"
```

---

### Task 6: Verification And Device Validation

**Files:**
- No source changes unless tests reveal a failure.

- [ ] **Step 1: Run focused disk spool tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolSessionTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSourceTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolPipelineTest \
  --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: PASS.

- [ ] **Step 2: Run broader player tests excluding known environment-brittle full suite if needed**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.ui.screens.player.PlayerLoadControlFactoryTest \
  --tests com.nexio.tv.ui.screens.player.PlayerPlaybackProgressUiStateTest \
  --tests com.nexio.tv.ui.screens.player.PlayerPlaybackSessionGuardTest \
  --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerBuiltInAiGroundworkTest \
  --tests com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest
```

Expected: PASS.

- [ ] **Step 3: Run diff hygiene**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 4: Device validation on `192.168.50.58` after installing a build**

After the user starts playback on the test device, run:

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
- `Nexio-disk-spoo` should drop after the adaptive headroom is reached instead of continuously consuming high CPU.
- Clean `gfxinfo` playback window should remain under 1% jank.
- No `AudioTrack underrun`.
- No `BUFFERING`.
- No blocking `WaitForGcToComplete`.

---

## Non-Goals

- Do not change PRDS behavior outside disk spool mode.
- Do not change VOD cache behavior.
- Do not change the disk spool startup buffer setting or fix the hard-coded startup buffer mismatch in this plan.
- Do not change the default 512KB I/O buffer in this plan.
- Do not introduce a new user-facing setting for adaptive headroom.
- Do not add parallel connections back into disk spool mode.

## Self-Review

**Spec coverage:** The plan covers adaptive headroom, strict sequential disk spool session behavior, removal of parallel disk spool behavior, and research-backed handling of the 512KB write target. It explicitly excludes the other suggestions.

**Placeholder scan:** No task contains TBD/TODO/fill-in instructions. Each implementation step includes exact files, code snippets, commands, and expected outcomes.

**Type consistency:** New names are consistent across tasks:

- `DiskSpoolSession.updateReadPosition(position: Long)`
- `DiskSpoolSession.currentReadPositionBytes()`
- `DiskSpoolSession.adaptiveTargetFrontierBytes(...)`
- `DiskSpoolWriter(adaptiveHeadroomBytes, idlePollMs)`
- `DISK_SPOOL_IO_BUFFER_BYTES`
- `DISK_SPOOL_ADAPTIVE_HEADROOM_MB`
