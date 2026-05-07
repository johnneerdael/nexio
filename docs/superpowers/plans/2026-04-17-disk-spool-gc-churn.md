# Disk Spool GC Churn Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce playback-time disk spool CPU and large-object GC churn by reusing the disk-spool I/O buffer and writing only to an adaptive playback headroom target instead of the full spool capacity.

**Architecture:** Disk spool remains a single sequential writer. `DiskSpoolSession` publishes the player read position, `DiskSpoolWriter` computes a target frontier from `max(startupPrebufferBytes, currentReadPosition + adaptiveHeadroomBytes)`, and the writer idles when that target is satisfied. The writer allocates one I/O buffer per writer run and reuses it across HTTP range requests.

**Tech Stack:** Kotlin, Android Media3 `DataSource`, OkHttp/Okio, `RandomAccessFile`, JUnit/Robolectric tests, ADB logcat/top/gfxinfo validation.

---

## Scope

This plan implements only the disk spool GC/CPU follow-up:

- Reuse one disk spool I/O buffer per writer run instead of allocating a new `ByteArray(512 * 1024)` per HTTP range.
- Add read-position tracking to `DiskSpoolSession`.
- Add adaptive disk spool headroom so the writer stops after a bounded lead over playback.
- Keep disk spool single-writer/sequential.

This plan intentionally does not:

- Change the 512KB I/O buffer size.
- Reintroduce parallel disk spool writes.
- Change PRDS outside disk spool mode.
- Change VOD cache.
- Change Dolby Vision conversion.
- Change progress saving, subtitles, or TheIntroDB.

## Current Evidence

Runtime after the prior fixes:

- `Nexio-disk-spoo` is hot during the disk-spool burst.
- GC logs still show large-object-space frees.
- Once the disk spool writer goes idle, app CPU drops.
- The current `DiskSpoolWriter.downloadRangeIntoSession(...)` allocates `ByteArray(ioBufferBytes)` inside each HTTP range download.
- `ioBufferBytes` defaults to `512 * 1024`, which is large enough to be treated as a large allocation on ART.

Research notes:

- Android/Java `BufferedOutputStream` documentation supports direct/bulk writes for byte arrays at least as large as the stream buffer; adding another small stream buffer is not the right first move.
- Local Okio 3.10.2 bytecode inspection shows `okio.Segment.SIZE = 8192`, so the current 512KB buffer already spans 64 Okio segments.
- The more likely issue is allocation cadence and full-target spooling, not the specific 512KB value. Keep 512KB unchanged until A/B data says otherwise.

References:

- Android `BufferedOutputStream`: https://developer.android.com/reference/java/io/BufferedOutputStream
- Oracle `BufferedOutputStream`: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/io/BufferedOutputStream.html
- Android ART improvements / concurrent compacting GC: https://source.android.com/docs/core/runtime/improvements

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt`
  - Add read-position tracking and adaptive target computation.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt`
  - Publish read progress to `DiskSpoolSession`.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
  - Reuse one I/O buffer per writer run.
  - Add adaptive headroom loop.
  - Keep constructor-compatible test hooks for buffer allocation counting.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Pass adaptive headroom bytes into the writer.

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSessionTest.kt`
  - Test read-position tracking and adaptive target math.

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSourceTest.kt`
  - Test reads publish progress.

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`
  - Test one I/O buffer allocation per writer run.
  - Test writer idles at adaptive headroom and resumes after read progress advances.

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt`
  - Test writer + data source interact under adaptive headroom.

---

### Task 1: Track Read Position And Adaptive Target In DiskSpoolSession

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSessionTest.kt`

- [ ] **Step 1: Add failing read-position and adaptive-target tests**

Add these tests to `DiskSpoolSessionTest.kt`:

```kotlin
@Test
fun `read position only advances forward`() {
    val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)

    session.updateReadPosition(256L)
    session.updateReadPosition(128L)

    assertEquals(256L, session.currentReadPositionBytes())

    session.close()
}

@Test
fun `adaptive target uses startup target until read headroom catches up`() {
    val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
    session.setSourceMetadata(contentLength = 10_000L, supportsRanges = true)

    session.updateReadPosition(100L)

    assertEquals(
        2_000L,
        session.adaptiveTargetFrontierBytes(
            maxFrontierBytes = 8_000L,
            startupPrebufferBytes = 2_000L,
            headroomBytes = 500L
        )
    )

    session.close()
}

@Test
fun `adaptive target follows read position plus headroom after startup target`() {
    val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
    session.setSourceMetadata(contentLength = 10_000L, supportsRanges = true)

    session.updateReadPosition(3_000L)

    assertEquals(
        3_500L,
        session.adaptiveTargetFrontierBytes(
            maxFrontierBytes = 8_000L,
            startupPrebufferBytes = 2_000L,
            headroomBytes = 500L
        )
    )

    session.close()
}

@Test
fun `adaptive target never exceeds content length or max frontier`() {
    val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
    session.setSourceMetadata(contentLength = 4_000L, supportsRanges = true)

    session.updateReadPosition(3_800L)

    assertEquals(
        4_000L,
        session.adaptiveTargetFrontierBytes(
            maxFrontierBytes = 8_000L,
            startupPrebufferBytes = 2_000L,
            headroomBytes = 1_000L
        )
    )

    assertEquals(
        3_900L,
        session.adaptiveTargetFrontierBytes(
            maxFrontierBytes = 3_900L,
            startupPrebufferBytes = 2_000L,
            headroomBytes = 1_000L
        )
    )

    session.close()
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolSessionTest
```

Expected: FAIL because `updateReadPosition`, `currentReadPositionBytes`, and `adaptiveTargetFrontierBytes` do not exist.

- [ ] **Step 3: Implement read-position tracking**

In `DiskSpoolSession.kt`, add this field next to `frontier`:

```kotlin
private val readPosition = AtomicLong(0L)
```

Add these methods after `windowStartBytes()`:

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

In `rebaseTo(position)`, after setting `frontier`, add:

```kotlin
readPosition.set(position)
```

- [ ] **Step 4: Run session tests and verify GREEN**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolSessionTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSessionTest.kt
git commit -m "track disk spool read headroom"
```

---

### Task 2: Publish Read Progress From DiskSpoolDataSource

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSourceTest.kt`

- [ ] **Step 1: Add failing data source progress tests**

Add these tests to `DiskSpoolDataSourceTest.kt`:

```kotlin
@Test
fun `open publishes initial read position to session`() {
    val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
    session.writeRange(0L, ByteArray(512) { 1 }, 512)
    val uri = Uri.parse("https://example.com/movie.mkv")

    val dataSource = DiskSpoolDataSource(session, uri)
    dataSource.open(DataSpec.Builder().setUri(uri).setPosition(256L).build())

    assertEquals(256L, session.currentReadPositionBytes())

    dataSource.close()
    session.close()
}

@Test
fun `read publishes advanced read position to session`() {
    val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
    session.writeRange(0L, ByteArray(512) { 1 }, 512)
    val uri = Uri.parse("https://example.com/movie.mkv")
    val dataSource = DiskSpoolDataSource(session, uri)

    dataSource.open(DataSpec(uri))
    val buffer = ByteArray(128)
    assertEquals(128, dataSource.read(buffer, 0, 128))

    assertEquals(128L, session.currentReadPositionBytes())

    dataSource.close()
    session.close()
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSourceTest
```

Expected: FAIL because `DiskSpoolDataSource` does not publish read position yet.

- [ ] **Step 3: Publish read position on open and read**

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

- [ ] **Step 4: Run data source tests and verify GREEN**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSourceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSourceTest.kt
git commit -m "publish disk spool read progress"
```

---

### Task 3: Reuse One Disk Spool I/O Buffer Per Writer Run

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`

- [ ] **Step 1: Add failing buffer allocation test**

Add this test to `DiskSpoolWriterTest.kt`:

```kotlin
@Test
fun `writer allocates one io buffer for multiple ranges in one run`() {
    val content = ByteArray(96 * 1024) { (it % 251).toByte() }
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
    val session = DiskSpoolSession(File(temp.root, "single-buffer.spool"), capacityBytes = 128 * 1024L)
    val allocationCount = AtomicInteger(0)

    try {
        DiskSpoolWriter(
            okHttpClient = OkHttpClient(),
            chunkBytes = 32 * 1024,
            ioBufferBytes = 4 * 1024,
            startupPriorityBytes = 0L,
            ioBufferFactory = { size ->
                allocationCount.incrementAndGet()
                ByteArray(size)
            }
        ).downloadUntil(server.url("/movie.bin").toString(), session, content.size.toLong())

        val buffer = ByteArray(content.size)
        assertEquals(content.size, session.read(0L, buffer, 0, buffer.size))
        assertArrayEquals(content, buffer)
        assertEquals(1, allocationCount.get())
    } finally {
        session.close()
        server.shutdown()
    }
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest.writer\\ allocates\\ one\\ io\\ buffer\\ for\\ multiple\\ ranges\\ in\\ one\\ run
```

Expected: FAIL because `ioBufferFactory` does not exist.

- [ ] **Step 3: Add `ioBufferFactory` and reuse buffer**

Change the `DiskSpoolWriter` constructor to:

```kotlin
internal class DiskSpoolWriter(
    private val okHttpClient: OkHttpClient,
    private val requestHeaders: Map<String, String> = emptyMap(),
    private val chunkBytes: Int = 18 * 1024 * 1024,
    private val ioBufferBytes: Int = 512 * 1024,
    @Suppress("UNUSED_PARAMETER")
    parallelConnections: Int = 1,
    private val startupPriorityBytes: Long = 100L * 1024L * 1024L,
    private val ioBufferFactory: (Int) -> ByteArray = { ByteArray(it) }
) {
```

In `downloadUntil(...)`, after `val bridge = SessionAdapter(session)`, add:

```kotlin
val ioBuffer = ioBufferFactory(ioBufferBytes)
```

Change both `downloadSequentially(...)` calls to include `ioBuffer = ioBuffer`.

Change `downloadSequentially(...)` signature to:

```kotlin
private fun downloadSequentially(
    url: String,
    bridge: SessionBridge,
    targetFrontierBytes: Long,
    contentLength: Long,
    ioBuffer: ByteArray
)
```

Change the `downloadRangeIntoSession(url, cursor, endInclusive, bridge)` call to:

```kotlin
cursor = downloadRangeIntoSession(url, cursor, endInclusive, bridge, ioBuffer)
```

Change private `downloadRangeIntoSession(...)` signature to:

```kotlin
private fun downloadRangeIntoSession(
    url: String,
    start: Long,
    endInclusive: Long,
    session: SessionBridge,
    ioBuffer: ByteArray
): Long
```

Inside that function, change:

```kotlin
downloadRangeIntoSession(source, start, endInclusive, session)
```

to:

```kotlin
downloadRangeIntoSession(source, start, endInclusive, session, ioBuffer)
```

Change internal `downloadRangeIntoSession(...)` signature to:

```kotlin
internal fun downloadRangeIntoSession(
    source: BufferedSource,
    start: Long,
    endInclusive: Long,
    session: SessionBridge,
    buffer: ByteArray
): Long
```

Remove the local allocation:

```kotlin
val buffer = ByteArray(ioBufferBytes)
```

- [ ] **Step 4: Update tests that call internal `downloadRangeIntoSession(...)` directly**

In `DiskSpoolWriterTest.kt`, find direct calls to:

```kotlin
writer.downloadRangeIntoSession(source, start, endInclusive, session)
```

Replace each call with:

```kotlin
writer.downloadRangeIntoSession(
    source = source,
    start = start,
    endInclusive = endInclusive,
    session = session,
    buffer = ByteArray(8 * 1024)
)
```

If a test uses a different `ioBufferBytes`, use the same size for the `ByteArray`.

- [ ] **Step 5: Run writer tests and verify GREEN**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt
git commit -m "reuse disk spool writer io buffer"
```

---

### Task 4: Add Adaptive Disk Spool Headroom

**Files:**
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

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest.writer\\ pauses\\ after\\ adaptive\\ headroom\\ target\\ until\\ reader\\ advances
```

Expected: FAIL because `adaptiveHeadroomBytes` and `idlePollMs` do not exist and the writer currently writes to full target.

- [ ] **Step 3: Add adaptive writer constructor parameters**

Change the `DiskSpoolWriter` constructor to include these parameters after `startupPriorityBytes`:

```kotlin
private val startupPriorityBytes: Long = 100L * 1024L * 1024L,
private val ioBufferFactory: (Int) -> ByteArray = { ByteArray(it) },
private val adaptiveHeadroomBytes: Long = DEFAULT_ADAPTIVE_HEADROOM_BYTES,
private val idlePollMs: Long = DEFAULT_IDLE_POLL_MS
```

Add constants near the bottom:

```kotlin
private const val DEFAULT_ADAPTIVE_HEADROOM_BYTES = 256L * 1024L * 1024L
private const val DEFAULT_IDLE_POLL_MS = 250L
```

- [ ] **Step 4: Replace full-target second sequential write with adaptive loop**

In `DiskSpoolWriter.downloadUntil(...)`, replace the second `downloadSequentially(...)` call:

```kotlin
downloadSequentially(
    url = url,
    bridge = bridge,
    targetFrontierBytes = targetFrontierBytes,
    contentLength = metadata.contentLength,
    ioBuffer = ioBuffer
)
```

with:

```kotlin
val maxFrontier = minOf(targetFrontierBytes, metadata.contentLength)
while (!session.isClosed() && !Thread.currentThread().isInterrupted) {
    val target = session.adaptiveTargetFrontierBytes(
        maxFrontierBytes = maxFrontier,
        startupPrebufferBytes = startupPriorityBytes,
        headroomBytes = adaptiveHeadroomBytes
    )
    val frontier = bridge.contiguousFrontierBytes()
    if (frontier >= target) {
        if (frontier >= maxFrontier) return
        try {
            Thread.sleep(idlePollMs.coerceAtLeast(1L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        continue
    }
    downloadSequentially(
        url = url,
        bridge = bridge,
        targetFrontierBytes = target,
        contentLength = metadata.contentLength,
        ioBuffer = ioBuffer
    )
}
```

- [ ] **Step 5: Pass adaptive headroom from factory**

In `PlayerMediaSourceFactory.scheduleDiskSpoolWriter(...)`, pass:

```kotlin
adaptiveHeadroomBytes = DISK_SPOOL_ADAPTIVE_HEADROOM_MB * BYTES_PER_MB
```

Add this companion constant:

```kotlin
private const val DISK_SPOOL_ADAPTIVE_HEADROOM_MB = 256L
```

- [ ] **Step 6: Run writer and pipeline tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolPipelineTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt
git commit -m "limit disk spool writes to playback headroom"
```

---

### Task 5: Focused Verification

**Files:**
- No production changes unless verification finds a real issue.

- [ ] **Step 1: Run focused disk spool tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolSessionTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSourceTest \
  --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolPipelineTest
```

Expected: PASS.

- [ ] **Step 2: Run disk-spool-specific factory tests**

Run:

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

- [ ] **Step 4: Device validation after build install**

After installing a build and starting disk spool playback on `192.168.50.58`, run:

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
- `Nexio-disk-spoo` should idle after startup/prebuffer and headroom are satisfied.
- Clean playback jank should stay under 1%.
- GC logs should show fewer large-object-space frees during the same 45-second playback window.
- No `AudioTrack underrun`.
- No `BUFFERING`.
- No blocking `WaitForGcToComplete`.

---

## Self-Review

**Spec coverage:** This plan covers the disk spool buffer churn root cause: one large buffer per range and full-target spooling. It also keeps the 512KB buffer size unchanged.

**Placeholder scan:** No TBD/TODO/fill-in instructions. Each code change has concrete signatures and snippets.

**Type consistency:** New names are consistent across tasks: `currentReadPositionBytes`, `updateReadPosition`, `adaptiveTargetFrontierBytes`, `ioBufferFactory`, `adaptiveHeadroomBytes`, and `idlePollMs`.

