# PRDS Startup Follow-Up Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the remaining PM/RD startup degradation by making PRDS cheap under extractor-driven probe reopens, drastically reducing tiny-read CPU overhead, honoring runtime no-prefetch states, making active-session traces durable and parseable, and removing metadata cache fsync pressure from the startup window.

**Architecture:** Keep PRDS as the optimized playback path, but split the remaining failure window into five contained fixes: lightweight probe-open handling in the Media3 façade, a small-read staging layer in the sequential cursor, runtime-policy-enforced scheduler gating, stall/trace summarization that preserves signal without hot-loop spam, and batched metadata cache persistence so playback startup is not competing with SharedPreferences fsyncs. Latest-session export semantics remain “one session family,” where multiple rotated `.jsonl` files sharing one session stem are expected and correct.

**Tech Stack:** Android/Kotlin, Media3, OkHttp, JUnit4, Robolectric, JSONL playback tracing, SharedPreferences-backed metadata cache

---

## File Map

### Production files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
  - add lightweight probe-open mode
  - preserve main PRDS state across bounded extractor probes
  - keep reopen tracing accurate
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt`
  - add small-read staging buffer so millions of sub-4-byte reads do not hit the store/lock path directly
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
  - enforce runtime `prefetchWorkers=0`
  - summarize `range_http_body` in addition to the already-throttled progress event
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`
  - replace fixed-cadence stall-band emission with banded milestone emission
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`
  - harden active-session export durability and trailing-line integrity
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`
  - keep latest-session export authoritative for one session family and snapshot all rotated parts safely
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
  - batch/debounce writes off the playback-critical path
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt`
  - route startup-time metadata writes through the new batched persistence API
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
  - route TMDB enrichment writes through the batched persistence API
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`
  - route trailer video cache writes through the batched persistence API

### Test files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
  - pin reduced reopen churn and reduced hot-path trace volume
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt`
  - pin small-read staging behavior
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt`
  - pin bounded probe-open reuse
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`
  - pin banded stall reporting
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`
  - pin active-session snapshot parseability
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreWriteBatchingTest.kt`
  - pin debounced persistence and reduced commit count

## Guardrails

- Do not change the direct path or VOD cache attach semantics in this plan.
- Do not remove PRDS range diagnostics entirely; summarize or band them.
- Treat multiple rotated `.jsonl` files with the same session stem as one valid latest session.
- Keep every task TDD-first and include an architecture review checkpoint.
- Prefer preserving existing abstractions over introducing new services unless a file becomes impossible to reason about.

---

### Task 1: Make Extractor Probe Reopens Cheap

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt`

- [ ] **Step 1: Write the failing probe-reopen test**

```kotlin
@Test(timeout = 10_000L)
fun `bounded extractor probe reopen does not rebuild transport`() {
    val content = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
    val server = startRangeServer(content)
    val probe = ReopenCostProbe()
    val dataSource = newInstrumentedDataSource(probe)

    dataSource.open(spec(server, position = 0L))
    dataSource.close()

    dataSource.open(
        spec(
            server = server,
            position = content.size.toLong() - 64 * 1024L,
            length = 64 * 1024L,
        )
    )
    dataSource.close()

    assertEquals(1, probe.transportAttachCount)
    assertEquals(0, probe.storeResetCount)
}
```

- [ ] **Step 2: Run the focused reopen-cost test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceReopenCostTest"`

Expected: FAIL because the bounded non-zero reopen still detaches PRDS and resets the store.

- [ ] **Step 3: Add bounded probe-open reuse in `ParallelRangeDataSource`**

```kotlin
private fun isBoundedProbeOpen(dataSpec: DataSpec): Boolean {
    if (dataSpec.length == C.LENGTH_UNSET.toLong()) return false
    val requestedLength = dataSpec.length
    return requestedLength in 1L..(512L * 1024L)
}

private fun canReuseForProbeOpen(dataSpec: DataSpec): Boolean {
    val session = openSession ?: return false
    if (!readerClosed.get()) return false
    if (!transportManager.isAttached()) return false
    if (continuationSource != null || fallbackSource != null) return false
    if (session.requestSpec.uri != dataSpec.uri) return false
    if (!isBoundedProbeOpen(dataSpec)) return false
    val requestedEndExclusive = dataSpec.position + dataSpec.length
    return requestedEndExclusive <= store.frontier
}

private fun reopenFromRetainedProbeWindow(dataSpec: DataSpec): Long {
    val prior = openSession ?: error("openSession required")
    val reused = prior.copy(
        requestSpec = dataSpec,
        startPosition = dataSpec.position,
        openLength = dataSpec.length,
    )
    openSession = reused
    position = dataSpec.position
    bytesRemaining = dataSpec.length
    readerClosed.set(false)
    closed.set(false)
    cursor?.close()
    cursor = DefaultSequentialReadCursor(
        session = reused,
        store = store,
        waitForBytes = ::waitForBytesAt,
        onPositionAdvanced = onReadPositionAdvanced,
        keepBehindBytes = keepBehindBytes,
        chunkWaitTimeoutMs = chunkWaitTimeoutMs,
    )
    fireTransferStarted(dataSpec)
    emitPrdsOpenSuccess("parallel_probe_reuse", dataSpec.length, reused.acceptsRanges.toString())
    return dataSpec.length
}
```

- [ ] **Step 4: Gate the existing full-open path with the new probe reuse**

```kotlin
transferInitializing(dataSpec)
if (canReuseOpenSession(dataSpec)) {
    return reopenFromRetainedCoverage(dataSpec)
}
if (canReuseForProbeOpen(dataSpec)) {
    beginPrdsOpenTrace(dataSpec, ReopenCause.SEEK_LIKE_REOPEN)
    return reopenFromRetainedProbeWindow(dataSpec)
}
```

- [ ] **Step 5: Run architecture review for the Task 1 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`

Expected review outcome:
- probe reuse stays inside the Media3 façade boundary
- true user seeks still rebuild transport when coverage is not already present

- [ ] **Step 6: Re-run the reopen-cost test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceReopenCostTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt
git commit -m "perf: reuse PRDS for bounded extractor probe reopens"
```

---

### Task 2: Add Small-Read Staging To The Sequential Cursor

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt`

- [ ] **Step 1: Write the failing tiny-read staging test**

```kotlin
@Test
fun `tiny reads are served from a staged window`() {
    val store = RecordingByteStore(pageSize = 64 * 1024)
    store.seed(position = 0L, bytes = ByteArray(64 * 1024) { 7 })
    val cursor = DefaultSequentialReadCursor(
        session = session(start = 0L, length = 64 * 1024L),
        store = store,
        waitForBytes = { _, _ -> WaitOutcome.DATA_AVAILABLE },
        onPositionAdvanced = {},
        keepBehindBytes = 0L,
        chunkWaitTimeoutMs = 1000L,
    )

    val one = ByteArray(1)
    repeat(512) {
        assertEquals(1, cursor.read(one, 0, 1))
    }

    assertTrue(store.readCallCount < 64)
}
```

- [ ] **Step 2: Run the cursor test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.SequentialReadCursorTest"`

Expected: FAIL because every 1-byte read still calls into the store.

- [ ] **Step 3: Add a small staged window inside `DefaultSequentialReadCursor`**

```kotlin
private val stagedBuffer = ByteArray(32 * 1024)
private var stagedStart = 0L
private var stagedLength = 0
private var stagedOffset = 0

private fun tryReadFromStage(buffer: ByteArray, offset: Int, length: Int): Int {
    if (stagedLength <= stagedOffset) return 0
    val available = stagedLength - stagedOffset
    val toCopy = minOf(length, available)
    System.arraycopy(stagedBuffer, stagedOffset, buffer, offset, toCopy)
    stagedOffset += toCopy
    _position += toCopy
    if (_bytesRemaining != C.LENGTH_UNSET.toLong()) {
        _bytesRemaining -= toCopy
    }
    onPositionAdvanced(_position)
    return toCopy
}

private fun refillStage(targetLength: Int): Int {
    stagedStart = _position
    stagedOffset = 0
    stagedLength = store.read(_position, stagedBuffer, 0, minOf(stagedBuffer.size, targetLength))
    return stagedLength
}
```

- [ ] **Step 4: Use the stage before touching the store again**

```kotlin
override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (_bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

    val staged = tryReadFromStage(buffer, offset, length)
    if (staged > 0) {
        store.evictBefore(_position - keepBehindBytes)
        return staged
    }

    val toRead = if (_bytesRemaining == C.LENGTH_UNSET.toLong()) {
        length
    } else {
        minOf(length.toLong(), _bytesRemaining).toInt()
    }

    if (toRead <= stagedBuffer.size && refillStage(stagedBuffer.size) > 0) {
        return tryReadFromStage(buffer, offset, length)
    }
    // existing direct read / wait loop
}
```

- [ ] **Step 5: Run architecture review for the Task 2 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt`

Expected review outcome:
- the cursor stays the sole Media3-facing reader
- staging is local cursor state, not a new transport or buffer layer

- [ ] **Step 6: Re-run the cursor test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.SequentialReadCursorTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt
git commit -m "perf: stage tiny PRDS reads in the sequential cursor"
```

---

### Task 3: Enforce Runtime No-Prefetch And Summarize Remaining Body Events

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManagerPrefetchChunkSourceTest.kt`

- [ ] **Step 1: Write the failing runtime no-prefetch test**

```kotlin
@Test
fun `rebuffer policy with zero prefetch workers does not submit prefetch`() {
    val trace = runTraceWithRuntimePolicy(
        TransportPolicy(
            urgentWorkers = 2,
            prefetchWorkers = 0,
            urgentChunkBytes = 16L * 1024L * 1024L,
            prefetchChunkBytes = 0L,
            warmAheadEnabled = false,
        )
    )

    assertFalse(trace.contains("\"lane\":\"prefetch\""))
    assertFalse(trace.contains("\"ev\":\"submit_prefetch\""))
}
```

- [ ] **Step 2: Run the focused tracing test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest" --tests "com.nexio.tv.ui.screens.player.SharedParallelTransportManagerPrefetchChunkSourceTest"`

Expected: FAIL because prefetch still appears during `REBUFFER`.

- [ ] **Step 3: Hard-gate prefetch submission on runtime policy**

```kotlin
private fun promoteRanges(
    readerPosition: Long,
    activeChunkSize: Long,
    totalFileLength: Long,
    envelope: CapabilityEnvelope,
    policy: TransportPolicy?,
    submit: (chunkIndex: Long, start: Long, end: Long, urgent: Boolean) -> Unit
) {
    val runtimePrefetchWorkers = policy?.prefetchWorkers ?: envelope.maxSafePrefetchWorkers
    ...
    val allowPrefetch = runtimePrefetchWorkers > 0
    ...
    val urgent = uncovered && remainingUrgentBudget > 0
    if (urgent || allowPrefetch) {
        submit(ci, start, end, urgent)
    }
}
```

- [ ] **Step 4: Summarize `range_http_body` milestones instead of emitting every 8 KiB read**

```kotlin
private const val BODY_EVENT_STEP_BYTES = 256 * 1024

private fun shouldEmitBodyEvent(totalRead: Int, expectedBytes: Int, lastMilestone: Int): Int? {
    if (expectedBytes <= 0) return null
    val milestone = when {
        totalRead >= expectedBytes -> Int.MAX_VALUE
        totalRead < BODY_EVENT_STEP_BYTES -> return null
        else -> totalRead / BODY_EVENT_STEP_BYTES
    }
    return if (milestone > lastMilestone) milestone else null
}
```

- [ ] **Step 5: Run architecture review for the Task 3 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`

Expected review outcome:
- runtime transport policy is now truly authoritative
- success/failure terminal diagnostics remain intact

- [ ] **Step 6: Re-run the tracing tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest" --tests "com.nexio.tv.ui.screens.player.SharedParallelTransportManagerPrefetchChunkSourceTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManagerPrefetchChunkSourceTest.kt
git commit -m "perf: honor runtime prefetch policy in PRDS"
```

---

### Task 4: Replace Fixed Stall Polling With Milestone Stall Bands

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`

- [ ] **Step 1: Write the failing milestone-band test**

```kotlin
@Test(timeout = 5_000L)
fun `frontier no progress emits at stall milestones not fixed cadence`() {
    val out = runFrontierStallTrace(durationMs = 6_000L)
    val stallLines = out.lineSequence()
        .filter { it.contains("\"ev\":\"frontier_no_progress_band\"") }
        .toList()

    assertTrue(stallLines.size <= 5)
}
```

- [ ] **Step 2: Run the focused buffer test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferTest"`

Expected: FAIL because the fixed cadence still emits too often over sustained stalls.

- [ ] **Step 3: Replace fixed interval emission with milestone bands**

```kotlin
private val FRONTIER_STALL_BANDS_NANOS = longArrayOf(
    1_000_000_000L,
    2_000_000_000L,
    5_000_000_000L,
    10_000_000_000L,
    30_000_000_000L,
)
private val lastEmittedStallBandIndex = AtomicLong(-1L)

private fun maybeEmitNoProgressBand(currentFrontier: Long) {
    if (!PlaybackTracer.enabled) return
    val idleNanos = SystemClock.elapsedRealtimeNanos() - lastFrontierAdvanceNanos.get()
    val nextBand = FRONTIER_STALL_BANDS_NANOS.indexOfLast { idleNanos >= it }
    if (nextBand < 0) return
    val previousBand = lastEmittedStallBandIndex.get()
    if (nextBand.toLong() <= previousBand) return
    if (!lastEmittedStallBandIndex.compareAndSet(previousBand, nextBand.toLong())) return
    PlaybackTracer.emit(EventFamily.FRONTIER, "frontier_no_progress_band") {
        putLong("frontier", currentFrontier)
        putLong("idleNanos", idleNanos)
        putInt("bandIndex", nextBand)
    }
}
```

- [ ] **Step 4: Reset the stall-band state on frontier advance**

```kotlin
private fun emitFrontierAdvance(delta: Long, newBytes: Long) {
    if (delta <= 0L) return
    ...
    lastEmittedStallBandIndex.set(-1L)
    PlaybackTracer.emit(EventFamily.FRONTIER, "frontier_advance") {
        putLong("delta", delta)
        putLong("newBytes", newBytes)
    }
}
```

- [ ] **Step 5: Run architecture review for the Task 4 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`

Expected review outcome:
- stall diagnostics remain available
- one real 55-second stall no longer explodes into 100+ trace records

- [ ] **Step 6: Re-run the buffer test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt
git commit -m "perf: band PRDS frontier stall diagnostics"
```

---

### Task 5: Make Active-Session Latest Exports Parseable And Finalized

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`

- [ ] **Step 1: Write the failing active-session export test**

```kotlin
@Test(timeout = 20_000L)
fun `copy latest session snapshots active rotated family without partial lines`() {
    val sink = mutableListOf<String>()
    val writer = newRotatingWriter(rotationBytes = 1024L)

    repeat(1000) { index ->
        writer.enqueue(EventFamily.RANGE, "range_http_body_progress") {
            putLong("chunkIndex", 1L)
            putString("lane", "urgent")
            putInt("bytesRead", 8192)
            putInt("totalRead", index + 1)
        }
    }

    val zipPath = controller.copyLatestSessionToDownloads(nowMs = 1234L)
    val lines = unzipAllJsonl(zipPath!!)
    lines.filter { it.isNotBlank() }.forEach { JSONObject(it) }
}
```

- [ ] **Step 2: Run the integrity/controller tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.SessionWriterJsonlIntegrityTest" --tests "com.nexio.tv.instrumentation.PlaybackTraceControllerTest"`

Expected: FAIL or flake because the active latest-session snapshot can still end on a truncated line.

- [ ] **Step 3: Add a snapshot footer and trim incomplete trailing records**

```kotlin
internal fun snapshotIfCurrentFile(requestedFile: File, snapshotDir: File): FileSnapshot? {
    ...
    sink.flush()
    val bytes = current.readBytes()
    val safeLength = bytes.indexOfLast { it == '\n'.code.toByte() } + 1
    if (safeLength <= 0) return null
    snapshot.outputStream().buffered().use { out ->
        out.write(bytes, 0, safeLength)
    }
    ...
}
```

- [ ] **Step 4: Snapshot every rotated file in the latest session family before zipping**

```kotlin
private suspend fun exportStableFiles(files: List<File>, block: (List<File>) -> String?): String? {
    val snapshotDir = File(appContext.cacheDir, "playback-trace-export-snapshots")
    val exportFiles = files.map { file ->
        PlaybackTracer.snapshotIfActive(file, snapshotDir)?.snapshotFile ?: file
    }
    return block(exportFiles)
}
```

- [ ] **Step 5: Run architecture review for the Task 5 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`

Expected review outcome:
- latest-session export still means one session family
- active-session export is parseable without forcing playback to stop

- [ ] **Step 6: Re-run the integrity/controller tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.SessionWriterJsonlIntegrityTest" --tests "com.nexio.tv.instrumentation.PlaybackTraceControllerTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt
git add app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt
git add app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt
git add app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt
git commit -m "fix: snapshot active playback traces without partial lines"
```

---

### Task 6: Batch Metadata Cache Writes Off The Startup Path

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreWriteBatchingTest.kt`

- [ ] **Step 1: Write the failing batching test**

```kotlin
@Test
fun `repeated metadata writes are batched into one disk commit window`() {
    val prefs = RecordingSharedPreferences()
    val store = MetadataDiskCacheStore(
        context = FakePrefsContext(prefs),
        ioScope = TestScope(UnconfinedTestDispatcher()),
        debounceMs = 250L,
    )

    repeat(20) { index ->
        store.enqueueMetaWrite("item-$index", "en", "pm", meta(index))
    }

    store.flushPendingWritesForTest()

    assertEquals(1, prefs.applyCount)
}
```

- [ ] **Step 2: Run the focused batching test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreWriteBatchingTest"`

Expected: FAIL because every write still calls `prefs.edit().putString(...).apply()`.

- [ ] **Step 3: Add an in-memory pending-write queue plus debounced flush**

```kotlin
private val pendingWrites = ConcurrentHashMap<String, String?>()
private val flushScheduled = AtomicBoolean(false)

fun enqueueMetaWrite(itemKey: String, languageTag: String, providerToken: String, meta: Meta) {
    val key = buildMetaKey(itemKey, languageTag, providerToken)
    val payload = JsonObject().apply {
        add("value", gson.toJsonTree(meta))
        addProperty("languageEpoch", currentLanguageEpoch())
        addProperty("metaSchemaVersion", META_CACHE_SCHEMA_VERSION)
        addProperty("updatedAtMs", System.currentTimeMillis())
    }
    pendingWrites[key] = gson.toJson(payload)
    scheduleFlush()
}

private fun scheduleFlush() {
    if (!flushScheduled.compareAndSet(false, true)) return
    ioScope.launch {
        delay(debounceMs)
        flushPendingWrites()
    }
}
```

- [ ] **Step 4: Flush queued writes in one editor transaction**

```kotlin
internal fun flushPendingWritesForTest() = flushPendingWrites()

private fun flushPendingWrites() {
    val snapshot = pendingWrites.entries.toList()
    if (snapshot.isEmpty()) {
        flushScheduled.set(false)
        return
    }
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val editor = prefs.edit()
    snapshot.forEach { (key, value) ->
        if (value == null) editor.remove(key) else editor.putString(key, value)
    }
    editor.apply()
    snapshot.forEach { (key, _) -> pendingWrites.remove(key) }
    flushScheduled.set(false)
    if (pendingWrites.isNotEmpty()) scheduleFlush()
}
```

- [ ] **Step 5: Route startup call sites through the queued write API**

```kotlin
metadataDiskCacheStore.enqueueMetaWrite(
    itemKey = itemKey,
    languageTag = languageTag,
    providerToken = providerToken,
    meta = meta,
)
```

Also replace:
- `writeTmdbEnrichment(...)` call sites with `enqueueTmdbEnrichmentWrite(...)`
- `writeTmdbTitleVideos(...)` call sites with `enqueueTmdbTitleVideosWrite(...)`
- `writeTmdbSeasonVideos(...)` call sites with `enqueueTmdbSeasonVideosWrite(...)`

- [ ] **Step 6: Run architecture review for the Task 6 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`

Expected review outcome:
- metadata cache semantics are preserved
- startup path stops paying one fsync per metadata update

- [ ] **Step 7: Re-run the batching test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreWriteBatchingTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt
git add app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt
git add app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt
git add app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt
git add app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreWriteBatchingTest.kt
git commit -m "perf: batch metadata cache writes off playback startup"
```

---

### Task 7: Verify The Full Startup-Recovery Slice Against Device Output

**Files:**
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreWriteBatchingTest.kt`

- [ ] **Step 1: Run the focused startup-recovery regression suite**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceReopenCostTest" \
  --tests "com.nexio.tv.ui.screens.player.SequentialReadCursorTest" \
  --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest" \
  --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferTest" \
  --tests "com.nexio.tv.instrumentation.SessionWriterJsonlIntegrityTest" \
  --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreWriteBatchingTest"
```

Expected: PASS

- [ ] **Step 2: Run the compile gate**

Run: `./gradlew :app:compileUniversalDebugKotlin`

Expected: PASS

- [ ] **Step 3: Install the build and capture one fresh latest session**

Run:

```bash
adb -s 192.168.50.58:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
adb -s 192.168.50.58:5555 shell am broadcast -a com.nexio.tv.action.PLAYBACK_TRACE_CLEAR -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
```

Then reproduce one PM parallel playback run, and export:

```bash
adb -s 192.168.50.58:5555 shell am broadcast -a com.nexio.tv.action.PLAYBACK_TRACE_COPY_LATEST_SESSION -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
```

- [ ] **Step 4: Pull and assert the new latest-session trace characteristics**

Expected trace outcome:
- exactly one session family in the ZIP, possibly multiple rotated files with one shared stem
- `BAD 0`
- no per-read `read_return`
- materially lower `read_return_agg` count than `60970`
- materially lower `frontier_no_progress_band` count than `110`
- no prefetch lane events after `transport_policy_state` transitions to `prefetchWorkers=0`
- fewer or no giant `seek_like_reopen` probe rebuilds

- [ ] **Step 5: Commit the verification pass**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt
git add app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt
git add app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt
git add app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt
git add app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt
git add app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt
git add app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt
git add app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt
git add app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreWriteBatchingTest.kt
git commit -m "perf: finish PRDS startup recovery follow-up"
```

