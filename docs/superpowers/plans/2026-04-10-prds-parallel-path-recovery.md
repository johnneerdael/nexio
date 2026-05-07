# PRDS Parallel Path Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the PRDS/parallel playback path so Premiumize and Real-Debrid playback on Android TV delivers clear performance gains instead of CPU-heavy degradation, while also making PRDS traces trustworthy enough to diagnose future regressions.

**Architecture:** Keep the current PRDS architecture, but remove unsafe trace-record pooling, reduce hot-path tracing/telemetry overhead, and make PRDS cheaper under extractor-driven non-linear opens. The plan is intentionally limited to the parallel path: no direct-path or VOD-cache attach behavior changes beyond what PRDS needs to coexist with the existing cache wrapper.

**Tech Stack:** Android/Kotlin, Media3, OkHttp, JUnit4, Robolectric, MockK, JSONL trace instrumentation

---

## File Map

### Production files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt`
  - replace the unsafe shared freelist usage pattern that currently corrupts live PRDS traces
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`
  - keep record emission safe after the pool change
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
  - throttle/remove hot-path `read_return` and `store_progress` tracing
  - reduce PRDS restart cost on internal non-zero opens where possible
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
  - throttle body-progress tracing and preserve terminal diagnostics
  - prevent PRDS worker churn from overwhelming weak devices
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`
  - reduce stall-band trace spam without losing useful stall diagnostics
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt`
  - keep the read cursor cheap under pathological small-read consumers

### Test files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`
  - prove live-style concurrent range records stay parseable
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
  - pin bounded PRDS trace churn and range terminal behavior
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt`
  - pin cheap small-read behavior
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`
  - pin coarser stall reporting
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt`
  - reproduce and measure internal non-zero PRDS open behavior

## Guardrails

- Do not change the direct path in this plan.
- Do not remove PRDS or collapse it into single-connection playback.
- Do not hide useful diagnostics entirely; throttle or summarize them instead of deleting everything.
- Every task must include an architecture review checkpoint before moving on.
- Every task must use TDD and run the focused verification commands it defines.

---

### Task 1: Fix TraceRecord Pool Safety For PRDS Concurrency

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`

- [ ] **Step 1: Write the failing PRDS-style integrity test**

```kotlin
@Test(timeout = 20_000L)
fun `concurrent prds range records always produce parseable jsonl lines`() {
    val sink = StringWriter()
    val writer = SessionWriter(
        header = SessionHeaderFixture.build(sessionId = "prds-jsonl"),
        baseFile = null,
        capacity = 8192,
        testSink = sink,
        rotationBytes = Long.MAX_VALUE,
        parkNanos = 1_000_000L,
        overflowReportIntervalNanos = Long.MAX_VALUE
    )
    val threads = List(4) { worker ->
        Thread {
            repeat(200) { iteration ->
                writer.enqueue(EventFamily.RANGE, "range_http_body_progress") {
                    putLong("chunkIndex", worker.toLong())
                    putString("lane", "urgent")
                    putInt("bytesRead", 1024)
                    putInt("totalRead", iteration + 1)
                }
            }
        }.apply { start() }
    }

    threads.forEach { it.join(5_000L) }
    writer.shutdown()

    sink.toString()
        .lineSequence()
        .filter { it.isNotBlank() }
        .forEach { JSONObject(it) }
}
```

- [ ] **Step 2: Run the integrity test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.SessionWriterJsonlIntegrityTest"`

Expected before implementation: FAIL or flake under concurrent range-record load.

- [ ] **Step 3: Replace the shared freelist with a multi-consumer-safe structure**

```kotlin
internal object TraceRecordPool {
    private const val THREAD_LOCAL_CAP = 64
    private const val SHARED_CAP = 1024

    private val sharedFreelist = java.util.concurrent.ConcurrentLinkedQueue<TraceRecord>()

    private val local = object : ThreadLocal<ArrayDeque<TraceRecord>>() {
        override fun initialValue(): ArrayDeque<TraceRecord> = ArrayDeque(THREAD_LOCAL_CAP)
    }

    fun acquire(): TraceRecord {
        val deque = local.get()!!
        return deque.removeLastOrNull() ?: sharedFreelist.poll() ?: TraceRecord()
    }

    fun release(rec: TraceRecord) {
        val deque = local.get()!!
        if (deque.size < THREAD_LOCAL_CAP) {
            deque.addLast(rec)
        } else if (sharedFreelist.size < SHARED_CAP) {
            sharedFreelist.offer(rec)
        }
    }
}
```

- [ ] **Step 4: Keep `SessionWriter` behavior unchanged except for compatibility with the pool change**

```kotlin
fun recycle() {
    family = EventFamily.TRACER
    type = ""
    tNanos = 0L
    thread = ""
    payloadBuffer.setLength(0)
    TraceRecordPool.release(this)
}
```

- [ ] **Step 5: Run architecture review for the Task 1 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`

Expected review outcome:
- instrumentation remains isolated from playback logic
- no hot-path contract regressions are introduced
- pool safety is improved without coupling the writer to transport code

- [ ] **Step 6: Re-run the integrity test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.SessionWriterJsonlIntegrityTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt
git add app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt
git add app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt
git commit -m "fix: make PRDS trace record pooling concurrency safe"
```

---

### Task 2: Throttle PRDS Hot-Path Trace Spam

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`

- [ ] **Step 1: Write the failing bounded-churn trace test**

```kotlin
@Test(timeout = 10_000L)
fun `prds trace churn stays bounded under small reads`() {
    val out = runTinyReadPrdsTrace()
    val readReturnCount = out.lineSequence().count { it.contains("\"ev\":\"read_return\"") }
    val bodyProgressCount = out.lineSequence().count { it.contains("\"ev\":\"range_http_body_progress\"") }

    assertTrue(readReturnCount < 500)
    assertTrue(bodyProgressCount < 500)
}
```

- [ ] **Step 2: Run the focused tracing test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest"`

Expected before implementation: FAIL because every read/socket chunk emits a trace event.

- [ ] **Step 3: Summarize `read_return` instead of emitting every single read**

```kotlin
private var aggregatedReadBytes = 0L
private var aggregatedReadCalls = 0
private var lastReadTraceAtMs = 0L

private fun maybeEmitAggregatedReadReturn(read: Int) {
    if (read <= 0) return
    aggregatedReadBytes += read
    aggregatedReadCalls += 1
    val now = SystemClock.elapsedRealtime()
    if (aggregatedReadCalls < 32 && now - lastReadTraceAtMs < 500L) return
    PlaybackTracer.emit(EventFamily.PRDS, "read_return_agg") {
        putLong("bytesRead", aggregatedReadBytes)
        putInt("readCalls", aggregatedReadCalls)
        putLong("position", position)
        putLong("bytesRemaining", bytesRemaining)
    }
    aggregatedReadBytes = 0L
    aggregatedReadCalls = 0
    lastReadTraceAtMs = now
}
```

- [ ] **Step 4: Throttle `range_http_body_progress` to milestone emission**

```kotlin
private fun shouldEmitBodyProgress(totalRead: Int, expectedBytes: Int): Boolean {
    if (totalRead == expectedBytes) return true
    return totalRead % (256 * 1024) == 0
}

if (shouldEmitBodyProgress(totalRead, expectedBytes)) {
    emitRangeContextEvent("range_http_body_progress", attemptContext) {
        putInt("bytesRead", read)
        putInt("totalRead", totalRead)
        putInt("expectedBytes", expectedBytes)
        putLong("offsetInChunk", offsetInChunk)
        putLong("sampleTimeMs", sampleTime)
    }
}
```

- [ ] **Step 5: Run architecture review for the Task 2 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`

Expected review outcome:
- trace reduction does not remove essential correctness/terminal diagnostics
- throttling stays inside instrumentation paths, not scheduling logic

- [ ] **Step 6: Re-run the tracing test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git commit -m "perf: throttle PRDS trace spam on hot paths"
```

---

### Task 3: Remove Per-Write Startup Telemetry From The Hottest PRDS Loop

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`

- [ ] **Step 1: Write the failing test that `store_progress` is not emitted per write**

```kotlin
@Test(timeout = 10_000L)
fun `store progress is not logged for every body write`() {
    val out = runSuccessfulTrace()
    val storeProgressCount = out.lineSequence().count { it.contains("prds.startup.store_progress") }
    assertTrue(storeProgressCount < 20)
}
```

- [ ] **Step 2: Run the tracing test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest"`

Expected before implementation: FAIL if the hot loop is still logging every write.

- [ ] **Step 3: Replace per-write logging with milestone-first logging**

```kotlin
private val firstStoreProgressLogged = AtomicBoolean(false)

onStoreProgress = { lane, absolutePosition, bytesWritten, frontierAfter ->
    if (firstStoreProgressLogged.compareAndSet(false, true)) {
        logStartupStage(
            "first_store_progress",
            mapOf(
                "absolutePosition" to absolutePosition,
                "bytesWritten" to bytesWritten,
                "frontierAfter" to frontierAfter,
                "lane" to lane
            )
        )
    }
    maybeLogFirstFrontierAdvance(lane, frontierAfter)
}
```

- [ ] **Step 4: Run architecture review for the Task 3 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`

Expected review outcome:
- startup telemetry remains useful but no longer sits in the hottest write path

- [ ] **Step 5: Re-run the tracing test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git commit -m "perf: remove per-write startup telemetry from PRDS"
```

---

### Task 4: Make PRDS Cheaper On Extractor-Driven Non-Zero Opens

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt`

- [ ] **Step 1: Write the failing reopen-cost test**

```kotlin
@Test(timeout = 10_000L)
fun `internal non zero reopen does not rebuild PRDS from scratch when reopen stays within active coverage`() {
    val probe = ReopenCostProbe()
    val dataSource = newInstrumentedDataSource(probe)

    dataSource.open(spec(position = 3731434030L))
    dataSource.close()
    dataSource.open(spec(position = 3731434040L))

    assertEquals(1, probe.transportAttachCount)
    assertEquals(0, probe.storeResetCount)
}
```

- [ ] **Step 2: Run the focused reopen-cost test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceReopenCostTest"`

Expected before implementation: FAIL because every `open()` tears PRDS down.

- [ ] **Step 3: Preserve active PRDS state for small internal reopens that stay within readable coverage**

```kotlin
private fun canReuseOpenSession(dataSpec: DataSpec): Boolean {
    val session = openSession ?: return false
    if (continuationSource != null) return false
    val requestedPosition = dataSpec.position
    val currentReadableEnd = store.frontier
    return requestedPosition in session.startPosition until currentReadableEnd
}

override fun open(dataSpec: DataSpec): Long {
    if (canReuseOpenSession(dataSpec)) {
        position = dataSpec.position
        cursor = DefaultSequentialReadCursor(
            session = openSession!!,
            store = store,
            waitForBytes = ::waitForBytesAt,
            onPositionAdvanced = onReadPositionAdvanced,
            keepBehindBytes = keepBehindBytes,
            chunkWaitTimeoutMs = chunkWaitTimeoutMs
        )
        return bytesRemaining
    }
    // existing full-open path
}
```

- [ ] **Step 4: Run architecture review for the Task 4 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt`

Expected review outcome:
- reuse heuristic does not cross correctness boundaries for true seek/rebuffer/user-driven reopen paths
- PRDS state reuse stays below the Media3 facade boundary

- [ ] **Step 5: Re-run the reopen-cost test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceReopenCostTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt
git commit -m "perf: reuse PRDS state across internal non-zero reopens"
```

---

### Task 5: Reduce Frontier Stall Instrumentation Amplification

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`

- [ ] **Step 1: Write the failing test for coarser stall-band emission**

```kotlin
@Test(timeout = 5_000L)
fun `frontier no progress band emits at coarse intervals under sustained stall`() {
    val out = runFrontierStallTrace()
    val stallCount = out.lineSequence().count { it.contains("\"ev\":\"frontier_no_progress_band\"") }
    assertTrue(stallCount <= 3)
}
```

- [ ] **Step 2: Run the focused buffer test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferTest"`

Expected before implementation: FAIL because the stall event currently emits every 100 ms.

- [ ] **Step 3: Raise the stall-band reporting interval**

```kotlin
private const val FRONTIER_STALL_POLL_NANOS = 500_000_000L
```

Or if keeping the 100 ms internal clock, gate emission separately:

```kotlin
private const val FRONTIER_STALL_EMIT_NANOS = 500_000_000L
```

- [ ] **Step 4: Run architecture review for the Task 5 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`

Expected review outcome:
- stall diagnostics remain useful but stop contributing to runaway PRDS event pressure

- [ ] **Step 5: Re-run the buffer test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt
git commit -m "perf: coarsen PRDS frontier stall diagnostics"
```

---

### Task 6: Verify The Full PRDS Recovery Slice

**Files:**
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt`

- [ ] **Step 1: Run the focused PRDS regression suite**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.instrumentation.SessionWriterJsonlIntegrityTest" \
  --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest" \
  --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferTest" \
  --tests "com.nexio.tv.ui.screens.player.SequentialReadCursorTest" \
  --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceReopenCostTest"
```

Expected: PASS.

- [ ] **Step 2: Run the compile gate**

Run: `./gradlew :app:compileUniversalDebugKotlin`

Expected: PASS.

- [ ] **Step 3: Capture one fresh parallel-enabled latest-session trace from device**

Run:

```bash
adb -s 192.168.50.58:5555 shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_COPY_LATEST_SESSION \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
```

Expected:
- one latest-session ZIP only
- header shows `branch="prds"`
- no malformed JSONL lines
- materially fewer `read_return` / `range_http_body_progress` events
- fewer `frontier_no_progress_band`

- [ ] **Step 4: Commit the PRDS recovery pass**

```bash
git add app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt
git add app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt
git add app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt
git commit -m "perf: recover PRDS parallel playback path"
```

---

## Self-Review

- Spec coverage:
  - unsafe shared freelist is covered by Task 1
  - `read_return` hot-path spam is covered by Task 2
  - `range_http_body_progress` spam is covered by Task 2
  - `store_progress` hot-loop telemetry is covered by Task 3
  - extractor-driven `seek_like_reopen` amplification is covered by Task 4
  - frontier stall amplification is covered by Task 5
  - integrated PRDS-only verification is covered by Task 6
- Placeholder scan:
  - no `TODO`/`TBD`
  - every task includes exact files, code snippets, commands, and expected outcomes
- Type consistency:
  - `ParallelRangeDataSourceReopenCostTest`, `read_return_agg`, and `copy_latest_session` references are consistent across tasks

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-10-prds-parallel-path-recovery.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
