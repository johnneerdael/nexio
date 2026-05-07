# Playback Performance Regression Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore highly performant high-bitrate playback on Android TV devices by fixing current transport-path bottlenecks, making diagnostics trustworthy and isolatable, proving that parallel/VOD-cache materially outperform the disabled path, and restoring the disabled path to pre-0.38 behavior/performance.

**Architecture:** Treat this as a playback-stack recovery pass spanning four boundaries: trace lifecycle/integrity, transport/frontier/read behavior, disabled/direct-path rollback, and benchmark/performance proof. Keep the current PRDS/VOD-cache architecture, but make trace capture authoritative, reduce transport churn and reopen overhead, and add regression harnesses that compare disabled versus parallel/VOD-cache behavior under the same workloads.

**Tech Stack:** Android/Kotlin, Media3, OkHttp, Jetpack Compose, JUnit4, Robolectric, MockK, JSONL trace instrumentation, ADB

---

## File Map

### Production files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`
  - make `clearAll()` authoritative by ending/draining active sessions before deleting files
  - add “latest session only” export helpers so retained-history bundles are separate from clean forensic capture
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceAdbReceiver.kt`
  - expose ADB actions for latest-session export and authoritative clear/status flows
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
  - surface authoritative clear/export semantics in the in-app diagnostics actions
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTracer.kt`
  - expose active-session state needed for clear/drain orchestration
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`
  - make live-device JSONL emission durable and parseable under concurrent range events
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt`
  - hard-reset pooled trace records and protect against recycled state contamination
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PayloadBuilder.kt`
  - make payload escaping allocation-light and unambiguously safe for live device traces
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackOkHttpEventListener.kt`
  - ensure live range HTTP timing events keep a consistent shape when device load is high
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
  - reduce tiny-read scheduling churn
  - tighten bootstrap/frontier/read path behavior
  - add explicit instrumentation for reopen/probe/root-cause analysis
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
  - guarantee range terminal events, reduce urgent-lane churn, and preserve progress-to-frontier behavior
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`
  - improve startup visibility and frontier advancement semantics without harming steady-state correctness
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - isolate disabled path from PRDS/VOD-cache overhead
  - reduce reopen/probe churn
  - ensure VOD-cache warm-ahead only participates when it should
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/TransportPolicyController.kt`
  - keep policy transitions visible and ensure startup/rebuffer behavior is tuned for Android TV playback stability
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DirectProfileBenchmarkTransport.kt`
  - make disabled-path benchmark transport stay clean and pre-0.38-like
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
  - make benchmark transport prove PRDS/VOD-cache material benefit over direct path
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt`
  - expose why safe budget stays constrained even when raw throughput rises
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkResultJsonLogger.kt`
  - emit decision-path observability for steady-state budget derivation
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt`
  - keep autoplay scoring aligned with the decision metrics while exposing stability-driven constraints
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md`
  - document authoritative clear/latest-session capture workflow

### Test files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`
  - pin authoritative clear and latest-session export semantics
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`
  - pin parseable JSONL under concurrent range event emission
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PutHeaderContractTest.kt`
  - keep header shape stable after trace lifecycle changes
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
  - pin bounded scheduling churn, terminal range events, and frontier progress visibility
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`
  - pin startup-readable frontier behavior
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt`
  - keep read cursor behavior aligned with startup publication changes
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - pin disabled-path and VOD-cache branch behavior
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt`
  - prove disabled path does not inherit PRDS/VOD-cache overhead or reopen churn
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransportTest.kt`
  - prove optimized path produces materially better stability/throughput than direct path
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunnerTest.kt`
  - pin safe-budget derivation and explanatory metrics
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorerTest.kt`
  - prove autoplay uses the correct constrained budget while exposing the reason

## Guardrails

- Do not remove PRDS or VOD cache as features; the goal is to make them materially beneficial.
- Do not allow the disabled/direct path to pick up PRDS/VOD-cache complexity or background work.
- Do not trust “Export all” as the authoritative latest-session source; the plan must add an isolated latest-session flow.
- Do not treat safe-budget flatness as a scoring bug; treat it as a stability symptom unless the decision math itself is proven wrong.
- After each task implementation pass, run an architecture review using the `architecture-strategist` skill before continuing.
- Verification is mandatory before moving between tasks.

---

### Task 1: Make Trace Clear And Latest-Session Capture Authoritative

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTracer.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceAdbReceiver.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`

- [ ] **Step 1: Write the failing controller tests for authoritative clear and latest-session export**

```kotlin
@Test
fun `clearAll ends active session before deleting retained traces`() = runTest {
    val appFilesDir = createTempDirectory("trace-clear-files").toFile()
    val cacheDir = createTempDirectory("trace-clear-cache").toFile()
    val tracesDir = File(appFilesDir, "playback-traces").apply { mkdirs() }
    val contentResolver = mockk<ContentResolver>(relaxed = true)
    val controller = buildController(appFilesDir, cacheDir, contentResolver)
    controller.installFilesDirOnce()
    PlaybackTracer.enabled = true
    val sessionId = PlaybackTracer.beginSession(SessionHeaderFixture.build(sessionId = "active-rd"))
    PlaybackTracer.emit(EventFamily.RANGE, "range_start") { putLong("chunkIndex", 0L) }
    File(tracesDir, "stale-pm.jsonl").writeText("{\"sid\":\"stale-pm\"}\n")

    val deleted = controller.clearAll()

    assertTrue(deleted >= 1)
    assertTrue(controller.listTraces().isEmpty())
    assertEquals(null, PlaybackTracer.currentInternal())
    PlaybackTracer.endSession(sessionId)
}

@Test
fun `copyLatestSessionToDownloads exports only the newest session family`() = runTest {
    val appFilesDir = createTempDirectory("trace-latest-files").toFile()
    val cacheDir = createTempDirectory("trace-latest-cache").toFile()
    val tracesDir = File(appFilesDir, "playback-traces").apply { mkdirs() }
    File(tracesDir, "pm-old.jsonl").writeText("{\"sid\":\"pm-old\"}\n")
    File(tracesDir, "rd-live.jsonl").writeText("{\"sid\":\"rd-live\"}\n")
    File(tracesDir, "rd-live-1.jsonl").writeText("{\"sid\":\"rd-live\"}\n")
    File(tracesDir, "pm-old.jsonl").setLastModified(1L)
    File(tracesDir, "rd-live.jsonl").setLastModified(2L)
    File(tracesDir, "rd-live-1.jsonl").setLastModified(3L)

    val insertedUri = Uri.parse("content://tests/downloads/latest.zip")
    val destinationBytes = ByteArrayOutputStream()
    val contentResolver = mockk<ContentResolver>()
    every { contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, any<ContentValues>()) } returns insertedUri
    every { contentResolver.openOutputStream(insertedUri) } returns destinationBytes

    val controller = buildController(appFilesDir, cacheDir, contentResolver)
    val adbPath = controller.copyLatestSessionToDownloads(nowMs = 42L)

    assertEquals("/sdcard/Download/playback-trace-latest-42.zip", adbPath)
    ZipInputStream(ByteArrayInputStream(destinationBytes.toByteArray())).use { zip ->
        val names = generateSequence { zip.nextEntry }.map { it.name }.toSet()
        assertEquals(setOf("rd-live.jsonl", "rd-live-1.jsonl"), names)
    }
}
```

- [ ] **Step 2: Run the focused controller test class to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.PlaybackTraceControllerTest"`

Expected before implementation: FAIL because `clearAll()` does not end/drain the active session and there is no latest-session-only export path.

- [ ] **Step 3: Implement authoritative clear and latest-session family export**

```kotlin
suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
    PlaybackTracer.currentInternal()?.let { active ->
        PlaybackTracer.endSession(active.sessionId)
    }
    val files = listTraces()
    var deleted = 0
    for (file in files) {
        if (file.delete()) deleted++
    }
    refreshStatus()
    deleted
}

internal fun listLatestSessionFiles(files: List<File> = listTraces()): List<File> {
    val newest = files.maxByOrNull { it.lastModified() } ?: return emptyList()
    val latestSessionId = newest.nameWithoutExtension.substringBeforeLast('-').let { base ->
        if (base.matches(Regex(".*[0-9a-fA-F]{8}.*"))) base else newest.nameWithoutExtension
    }
    return files.filter { file ->
        val stem = file.nameWithoutExtension
        stem == latestSessionId || stem.startsWith("$latestSessionId-")
    }.sortedBy { it.name }
}

suspend fun copyLatestSessionToDownloads(nowMs: Long = System.currentTimeMillis()): String? = withContext(Dispatchers.IO) {
    val latestFiles = listLatestSessionFiles()
    if (latestFiles.isEmpty()) return@withContext null
    exportStableFiles(latestFiles) { exportFiles ->
        val zipFile = buildSessionsZip(exportFiles, "playback-trace-latest-$nowMs.zip")
        writeFileToDownloads(zipFile, zipFile.name, "application/zip")
    }
}
```

- [ ] **Step 4: Add ADB wiring for latest-session export and authoritative clear**

```kotlin
const val ACTION_COPY_LATEST_SESSION = "com.nexio.tv.action.PLAYBACK_TRACE_COPY_LATEST_SESSION"

ACTION_COPY_LATEST_SESSION -> {
    val adbPath = controller.copyLatestSessionToDownloads()
    if (adbPath != null) {
        Log.i(TAG, "ADB: copy_latest_session — wrote $adbPath")
    } else {
        Log.i(TAG, "ADB: copy_latest_session — no trace available")
    }
}
```

- [ ] **Step 5: Run architecture review for the Task 1 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTracer.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceAdbReceiver.kt`

Expected review outcome:
- clear/export authority remains inside the diagnostics boundary
- no new transport/player coupling
- no circular dependency introduced

- [ ] **Step 6: Re-run tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.PlaybackTraceControllerTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt
git add app/src/main/java/com/nexio/tv/instrumentation/PlaybackTracer.kt
git add app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceAdbReceiver.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt
git add app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt
git commit -m "fix: make playback trace capture authoritative"
```

---

### Task 2: Fix Live JSONL Corruption And Missing Terminal Trace Durability

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PayloadBuilder.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackOkHttpEventListener.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PutHeaderContractTest.kt`

- [ ] **Step 1: Write the failing integrity tests for concurrent parseability and terminal durability**

```kotlin
@Test(timeout = 20_000L)
fun `concurrent range events always produce parseable jsonl lines`() {
    val sink = StringWriter()
    val writer = SessionWriter(
        header = SessionHeaderFixture.build(sessionId = "jsonl-integrity"),
        baseFile = null,
        capacity = 8192,
        testSink = sink,
        rotationBytes = Long.MAX_VALUE,
        parkNanos = 1_000_000L,
        overflowReportIntervalNanos = Long.MAX_VALUE,
    )
    val threads = List(4) { worker ->
        Thread {
            repeat(50) { iteration ->
                writer.enqueue(EventFamily.RANGE, "range_http_body_progress") {
                    putLong("chunkIndex", worker.toLong())
                    putInt("bytesRead", 1024)
                    putInt("totalRead", iteration + 1)
                    putString("lane", "urgent")
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

@Test
fun `range finish records survive after heavy progress emission`() {
    val sink = StringWriter()
    val writer = SessionWriter(
        header = SessionHeaderFixture.build(sessionId = "range-finish-durable"),
        baseFile = null,
        testSink = sink,
        rotationBytes = Long.MAX_VALUE
    )
    repeat(200) {
        writer.enqueue(EventFamily.RANGE, "range_http_body_progress") {
            putLong("chunkIndex", 1L)
            putInt("bytesRead", 8192)
            putInt("totalRead", (it + 1) * 8192)
        }
    }
    writer.enqueue(EventFamily.RANGE, "range_finish") {
        putLong("chunkIndex", 1L)
        putString("lane", "urgent")
        putString("result", "success")
        putInt("totalRead", 1_638_400)
        putInt("expectedBytes", 1_638_400)
    }
    writer.shutdown()

    assertTrue(sink.toString().contains("\"ev\":\"range_finish\""))
}
```

- [ ] **Step 2: Run the focused integrity tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.SessionWriterJsonlIntegrityTest" --tests "com.nexio.tv.instrumentation.PutHeaderContractTest"`

Expected before implementation: FAIL or flake under concurrent writer load.

- [ ] **Step 3: Implement writer/record/payload hardening**

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

```kotlin
private fun writeRecord(rec: TraceRecord) {
    synchronized(this) {
        lineBuffer.setLength(0)
        lineBuffer.append("{\"sid\":\"").append(header.sessionId)
            .append("\",\"tNs\":").append(rec.tNanos)
            .append(",\"th\":\"").append(escape(rec.thread))
            .append("\",\"fam\":\"").append(rec.family.name)
            .append("\",\"ev\":\"").append(escape(rec.type)).append('"')
        if (rec.payloadBuffer.isNotEmpty()) {
            lineBuffer.append(rec.payloadBuffer)
        }
        lineBuffer.append("}\n")
        sink.append(lineBuffer)
        sink.flush()
    }
}
```

```kotlin
private fun appendUnicodeEscape(sb: StringBuilder, codePoint: Int) {
    sb.append("\\u")
    sb.append(HEX_DIGITS[(codePoint ushr 12) and 0xF])
    sb.append(HEX_DIGITS[(codePoint ushr 8) and 0xF])
    sb.append(HEX_DIGITS[(codePoint ushr 4) and 0xF])
    sb.append(HEX_DIGITS[codePoint and 0xF])
}
```

- [ ] **Step 4: Keep listener range events structurally uniform**

```kotlin
private inline fun emit(
    call: Call,
    event: String,
    crossinline extra: PayloadBuilder.() -> Unit = {}
) {
    if (!PlaybackTracer.enabled) return
    val context = call.request().tag(RangeContext::class.java) ?: return
    PlaybackTracer.emit(EventFamily.RANGE, event) {
        putRangeContext(context)
        putLong("eventAtNanos", SystemClock.elapsedRealtimeNanos())
        extra()
    }
}
```

- [ ] **Step 5: Run architecture review for the Task 2 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PayloadBuilder.kt`

Expected review outcome:
- instrumentation remains isolated from playback logic
- hot-path overhead stays bounded
- no new lifecycle coupling into player code

- [ ] **Step 6: Re-run tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.SessionWriterJsonlIntegrityTest" --tests "com.nexio.tv.instrumentation.PutHeaderContractTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt
git add app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt
git add app/src/main/java/com/nexio/tv/instrumentation/PayloadBuilder.kt
git add app/src/main/java/com/nexio/tv/instrumentation/PlaybackOkHttpEventListener.kt
git add app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt
git add app/src/test/java/com/nexio/tv/instrumentation/PutHeaderContractTest.kt
git commit -m "fix: harden live playback trace integrity"
```

---

### Task 3: Recover Frontier Progress And Eliminate Tiny-Read Scheduling Pressure

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/AbsoluteByteStore.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt`

- [ ] **Step 1: Write the failing transport tests for bounded churn and startup-readable frontier**

```kotlin
@Test(timeout = 10_000L)
fun `continuation scheduling stays bounded during repeated tiny reads`() {
    val content = ByteArray(25 * 1024 * 1024) { (it % 251).toByte() }
    val chunkSize = 48L * 1024L * 1024L
    val server = startSlowRangeServer(content)
    val sid = PlaybackTracer.beginSession(fakeHeader())
    try {
        val dataSource = newDataSource(chunkSize)
        dataSource.open(
            DataSpec.Builder()
                .setUri(server.url("/media.bin").toString())
                .setPosition(1L)
                .setLength(C.LENGTH_UNSET.toLong())
                .build()
        )
        repeat(8) { dataSource.read(ByteArray(1), 0, 1) }
        dataSource.close()
    } finally {
        server.shutdown()
        PlaybackTracer.endSession(sid)
    }

    val out = sinks.last().toString()
    assertTrue(out.lineSequence().count { it.contains("\"ev\":\"range_schedule_reader_position\"") } < 20)
    assertTrue(out.lineSequence().count { it.contains("\"ev\":\"range_promote\"") } < 20)
}

@Test
fun `startup path can advance reader visibility before a full page is filled`() {
    val buffer = PagedFrontierBuffer()
    val start = 1_048_576L
    val payload = ByteArray(73_728) { 0x2A }
    buffer.setTotalLength(4L * PagedFrontierBuffer.PAGE_SIZE)
    buffer.setBasePosition(start)

    buffer.publishStartupWindow(start, payload, 0, payload.size)

    assertTrue(buffer.readableContiguousBytesFrom(start) > 0L)
}
```

- [ ] **Step 2: Run focused transport/buffer tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest" --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferTest" --tests "com.nexio.tv.ui.screens.player.SequentialReadCursorTest"`

Expected before implementation: FAIL or expose runaway churn/frontier-stall behavior.

- [ ] **Step 3: Keep schedule churn memoized by reader chunk, frontier chunk, and continuation window**

```kotlin
private fun scheduleFromCursor() {
    if (!transportManager.isAttached()) return
    val chunkSizeForSchedule = activeChunkSize.takeIf { it > 0L } ?: return
    val readerChunkIndex = position / chunkSizeForSchedule
    val currentFrontier = store.frontier
    val frontierChunkIndex = if (currentFrontier <= 0L) 0L else (currentFrontier - 1L) / chunkSizeForSchedule
    val continuationWindowEnd = if (continuationSource != null) continuationEndPositionExclusive else C.TIME_UNSET
    if (
        readerChunkIndex == lastScheduledReaderChunkIndex &&
        frontierChunkIndex == lastScheduledFrontierChunkIndex &&
        continuationWindowEnd == lastScheduledContinuationWindowEndExclusive
    ) {
        return
    }
    transportManager.scheduleForReaderPosition(position)
    lastScheduledReaderChunkIndex = readerChunkIndex
    lastScheduledFrontierChunkIndex = frontierChunkIndex
    lastScheduledContinuationWindowEndExclusive = continuationWindowEnd
}
```

- [ ] **Step 4: Preserve startup readability via the byte store instead of waiting for full-page completion**

```kotlin
fun publishStartupWindow(absoluteOffset: Long, data: ByteArray, dataOffset: Int, length: Int) {
    if (length <= 0) return
    onBytesWritten(absoluteOffset, data, dataOffset, length)
    synchronized(this) {
        advanceStartupReadableFrontier()
    }
}
```

```kotlin
store.publishStartupWindow(position, bootstrapData, 0, bootstrapSize)
```

- [ ] **Step 5: Run architecture review for the Task 3 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`

Expected review outcome:
- store/publication semantics stay below the player boundary
- scheduler de-dup does not break correctness on reopen/seek/rebuffer
- no inappropriate coupling between diagnostics and transport logic

- [ ] **Step 6: Re-run tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest" --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferTest" --tests "com.nexio.tv.ui.screens.player.SequentialReadCursorTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/AbsoluteByteStore.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt
git commit -m "perf: recover startup frontier progress and reduce churn"
```

---

### Task 4: Reduce Reopen/Probe Churn And Make Range Completion Observable

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`

- [ ] **Step 1: Write the failing tests for reopen/probe churn and durable `range_finish`**

```kotlin
@Test
fun `progressive direct branch does not reopen for the same content key during steady playback`() {
    val factory = newFactory()
    factory.useParallelConnections = false

    factory.buildMediaSource("https://example.com/movie.mkv")
    factory.buildMediaSource("https://example.com/movie.mkv")

    assertEquals(1, factory.testHooks.progressiveOpenCount)
}

@Test(timeout = 10_000L)
fun `live trace emits range finish after successful urgent range`() {
    val out = runSuccessfulTrace()
    assertTrue(out.contains("\"ev\":\"range_finish\""))
    assertTrue(out.contains("\"result\":\"success\""))
}
```

- [ ] **Step 2: Run focused tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest"`

Expected before implementation: FAIL on reopen-count and/or terminal-event durability.

- [ ] **Step 3: Cache and reuse steady progressive state when the direct path is selected**

```kotlin
if (!useParallelConnections && currentStreamUrl == url && currentVodCacheActive == previousVodCacheActive) {
    return buildCurrentProgressiveMediaSource()
}
```

```kotlin
private fun buildCurrentProgressiveMediaSource(): MediaSource {
    val mediaItem = MediaItem.fromUri(currentStreamUrl ?: error("missing stream url"))
    return ProgressiveMediaSource.Factory(buildDataSourceFactory())
        .createMediaSource(mediaItem)
}
```

- [ ] **Step 4: Ensure all urgent/prefetch exits emit terminal events**

```kotlin
if (closed.get() && !completionEmitted && lastAttemptContext != null) {
    emitRangeContextEvent("range_finish", lastAttemptContext) {
        putString("result", "cancelled")
        putInt("totalRead", totalRead)
        putInt("expectedBytes", expectedBytes)
    }
}
```

- [ ] **Step 5: Run architecture review for the Task 4 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`

Expected review outcome:
- direct path stays isolated from PRDS/VOD-cache state
- media-source factory does not become transport-policy stateful beyond current responsibility

- [ ] **Step 6: Re-run tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git commit -m "fix: reduce playback reopen churn and persist range completion"
```

---

### Task 5: Restore Disabled/Direct Path To Pre-0.38 Performance Characteristics

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DirectProfileBenchmarkTransport.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunnerTest.kt`

- [ ] **Step 1: Write failing regression tests for the disabled path**

```kotlin
@Test
fun `disabled path does not start VOD warm ahead`() {
    val factory = newFactory()
    factory.useParallelConnections = false

    factory.buildMediaSource("https://example.com/movie.mkv")
    factory.notifyPlaybackFirstFrameRendered()

    assertFalse(factory.testHooks.warmAheadStarted)
}

@Test
fun `disabled benchmark transport keeps useParallelConnections false and no cache penalties`() {
    val result = runDirectBenchmarkResult()
    assertEquals(false, result.optimized?.configSnapshot?.useParallelConnections)
    assertEquals(0, result.optimized?.sustained?.cacheDrainingDeficitCount ?: 0)
}
```

- [ ] **Step 2: Run the focused direct-path tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest" --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkSessionRunnerTest"`

Expected before implementation: FAIL because disabled path still shares warm-ahead/benchmark artifacts.

- [ ] **Step 3: Explicitly short-circuit disabled path away from PRDS/VOD-cache side effects**

```kotlin
if (!useParallelConnections) {
    stopVodWarmAhead()
    parallelStartupPrefetchUnlocked.set(true)
    currentVodCacheActive = false
    currentVodCacheResolvedUrl = null
    return ProgressiveMediaSource.Factory(buildDataSourceFactory())
        .createMediaSource(MediaItem.fromUri(url))
}
```

```kotlin
return DebridBenchmarkTransportConfigSnapshot(
    useParallelConnections = false,
    useVodCache = false,
    // keep the direct path free from optimized transport knobs
)
```

- [ ] **Step 4: Run architecture review for the Task 5 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DirectProfileBenchmarkTransport.kt`

Expected review outcome:
- direct path is simpler than optimized path
- no backflow of optimized-path responsibilities into the disabled path

- [ ] **Step 5: Re-run tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest" --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkSessionRunnerTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DirectProfileBenchmarkTransport.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt
git add app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunnerTest.kt
git commit -m "fix: restore direct playback path isolation"
```

---

### Task 6: Prove Parallel/VOD-Cache Materially Beat The Disabled Path

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransportTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunnerTest.kt`

- [ ] **Step 1: Write the failing comparative benchmark tests**

```kotlin
@Test
fun `optimized benchmark transport materially improves steady state over direct path`() {
    val direct = runDirectFixture()
    val optimized = runOptimizedFixture()

    assertTrue(optimized.summary.sustainedThroughputMbps!! > direct.summary.sustainedThroughputMbps!! * 1.15)
    assertTrue((optimized.optimized?.safeSustainedBudgetMbps() ?: 0.0) > (direct.optimized?.safeSustainedBudgetMbps() ?: 0.0))
}

@Test
fun `optimized path produces fewer frontier drain deficits than direct path`() {
    val direct = runDirectFixture()
    val optimized = runOptimizedFixture()

    assertTrue((optimized.optimized?.sustained?.cacheDrainingDeficitCount ?: 0) < (direct.optimized?.sustained?.cacheDrainingDeficitCount ?: 0))
}
```

- [ ] **Step 2: Run the benchmark transport tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkTransportTest" --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkSessionRunnerTest"`

Expected before implementation: FAIL or no meaningful optimized/direct separation.

- [ ] **Step 3: Make optimized transport explicitly prove its benefit**

```kotlin
val optimizedMode = DebridBenchmarkTransportConfigSnapshot(
    useParallelConnections = true,
    useVodCache = true,
    // optimized path should retain warm-ahead/cache participation
)

val directMode = DebridBenchmarkTransportConfigSnapshot(
    useParallelConnections = false,
    useVodCache = false,
)
```

```kotlin
require(
    optimizedResult.summary.sustainedThroughputMbps ?: 0.0 >
        (directResult.summary.sustainedThroughputMbps ?: 0.0)
) { "optimized transport must outperform direct path for this fixture" }
```

- [ ] **Step 4: Run architecture review for the Task 6 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt`

Expected review outcome:
- benchmark runner remains an evaluator, not a second playback architecture
- comparative fixtures stay in test code rather than production coupling

- [ ] **Step 5: Re-run tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkTransportTest" --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkSessionRunnerTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt
git add app/src/test/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransportTest.kt
git add app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunnerTest.kt
git commit -m "test: prove optimized playback materially beats direct path"
```

---

### Task 7: Make Benchmark Decision Metrics Explain Why Safe Budget Stays Flat

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkResultJsonLogger.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunnerTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorerTest.kt`

- [ ] **Step 1: Write the failing tests for budget explanation fields**

```kotlin
@Test
fun `decision metrics expose whether shadow or legacy path constrained the budget`() {
    val result = runBenchmarkResult()
    assertNotNull(result.optimized?.decision?.legacyBudgetMbps)
    assertNotNull(result.optimized?.decision?.budgetDivergenceRatio)
}

@Test
fun `stream scorer carries constrained safe budget into autoplay option`() {
    val option = ShadowTransportOption.fromProfile(
        config = BenchmarkAwareStreamScoringConfig.default(),
        transport = DebridBenchmarkTransportMode.OPTIMIZED,
        profile = benchmarkProfile(),
        requiredMbps = 120.0,
        movieMode = true,
        showMode = false
    )
    assertEquals(benchmarkProfile().safeSustainedBudgetMbps(), option?.safeBudgetMbps)
}
```

- [ ] **Step 2: Run the focused benchmark/scorer tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkSessionRunnerTest" --tests "com.nexio.tv.data.repository.benchmark.BenchmarkAwareStreamScorerTest"`

Expected before implementation: FAIL if explanatory metrics are missing from the serialized/logged path.

- [ ] **Step 3: Emit explicit decision-path explanation fields**

```kotlin
return copy(
    decision = DebridBenchmarkTransportDecisionMetrics(
        safeSustainedBudgetMbps = steadyStateBudgetMbps,
        startupSafeBudgetMbps = startupBudgetMbps,
        steadyStateSafeBudgetMbps = steadyStateBudgetMbps,
        actionable = sustained.actionable,
        shadowPlayerResult = shadowResult,
        legacyBudgetMbps = legacyBudgetMbps,
        budgetDivergenceRatio = divergenceRatio
    )
)
```

```kotlin
decision.legacyBudgetMbps?.let { addProperty("legacyBudgetMbps", it) }
decision.budgetDivergenceRatio?.let { addProperty("budgetDivergenceRatio", it) }
```

- [ ] **Step 4: Run architecture review for the Task 7 pass**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkResultJsonLogger.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt`

Expected review outcome:
- observability stays additive
- autoplay scoring continues to consume decision metrics rather than duplicating budget math

- [ ] **Step 5: Re-run tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkSessionRunnerTest" --tests "com.nexio.tv.data.repository.benchmark.BenchmarkAwareStreamScorerTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkResultJsonLogger.kt
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt
git add app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunnerTest.kt
git add app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorerTest.kt
git commit -m "feat: explain constrained autoplay safe budgets"
```

---

### Task 8: Final Verification And Device Audit

**Files:**
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransportTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunnerTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorerTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md`

- [ ] **Step 1: Run the full focused regression suite**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.instrumentation.PlaybackTraceControllerTest" \
  --tests "com.nexio.tv.instrumentation.SessionWriterJsonlIntegrityTest" \
  --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest" \
  --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferTest" \
  --tests "com.nexio.tv.ui.screens.player.SequentialReadCursorTest" \
  --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" \
  --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest" \
  --tests "com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkTransportTest" \
  --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkSessionRunnerTest" \
  --tests "com.nexio.tv.data.repository.benchmark.BenchmarkAwareStreamScorerTest" \
  --tests "com.nexio.tv.data.repository.benchmark.CapabilityEnvelopeTest" \
  --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerInitializationTest"
```

Expected: PASS

- [ ] **Step 2: Run the compile gate**

Run: `./gradlew :app:compileUniversalDebugKotlin`

Expected: PASS

- [ ] **Step 3: Validate authoritative clear + latest-session capture on device**

Run:

```bash
adb -s 192.168.50.58:5555 shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_CLEAR \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver

adb -s 192.168.50.58:5555 shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_STATUS \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver

adb -s 192.168.50.58:5555 shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_COPY_LATEST_SESSION \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
```

Expected:
- clear log reports only current retained files deleted
- status after clear reports `sessions=0`
- latest-session export after one RD playback produces exactly one RD session family

- [ ] **Step 4: Pull and audit the authoritative latest-session ZIP**

Run:

```bash
adb -s 192.168.50.58:5555 pull /sdcard/Download/playback-trace-latest-<timestamp>.zip .
python3 scripts/audit_latest_trace.py playback-trace-latest-<timestamp>.zip
```

Expected audit assertions:
- no malformed JSONL lines
- header shows the actual current provider/session
- `range_start` and `range_finish` counts match
- frontier advances beyond bootstrap
- optimized path shows materially better stability than direct path on the same fixture set

- [ ] **Step 5: Update docs and commit**

```markdown
- Use `Clear traces` before capture; this now ends the active writer and removes retained files.
- Use `Export latest session` when isolating a single playback run.
- Use `Export all sessions` only for historical forensics.
```

```bash
git add app/src/main/java
git add app/src/test/java
git add docs/instrumentation/collecting-traces.md
git commit -m "fix: recover high bitrate playback performance and trace authority"
```

---

## Self-Review

- Spec coverage:
  - Trace clear/session contamination is covered by Task 1.
  - Live JSONL corruption is covered by Task 2.
  - Frontier pinning and tiny-read churn are covered by Task 3.
  - Reopen/probe churn and missing terminal events are covered by Task 4.
  - Disabled/direct-path rollback is covered by Task 5.
  - Parallel/VOD-cache material gain proof is covered by Task 6.
  - Safe-budget observability is covered by Task 7.
  - Device validation and authoritative latest-session audit are covered by Task 8.
- Placeholder scan:
  - No `TODO`, `TBD`, or “write tests for above” placeholders remain.
  - Every task includes concrete files, commands, and code snippets.
- Type consistency:
  - `copyLatestSessionToDownloads`, `publishStartupWindow`, and `PlayerMediaSourceFactoryDirectPathRegressionTest` are referenced consistently across later tasks.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-10-playback-performance-regression-recovery.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
