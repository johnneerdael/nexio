# Playback Transport Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the transport, diagnostics, and export issues uncovered by the full playback-path audit so Nexio can reliably stream high-bitrate Blu-ray remuxes through debrid providers on Android TV devices and produce trustworthy traces for future analysis.

**Architecture:** Treat this as one transport-subsystem pass, not a series of disconnected tweaks. Keep the existing PRDS architecture, but correct the provider/fallback policy surfaces, remove scheduling/path behaviors that mis-target urgent work, improve frontier publication and churn behavior for weak Android TV hardware, and make every trace/export artifact trustworthy enough to explain failures without guesswork.

**Tech Stack:** Android/Kotlin, Media3, OkHttp, Jetpack Compose, JUnit4, Robolectric, MockK, JSONL trace instrumentation

---

## File Map

### Production files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`
  - make direct Downloads export the canonical all-sessions export path
  - harden export logging and failure reporting
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackDiagnosticsSection.kt`
  - wire “Export all sessions” to direct Downloads instead of SAF document creation
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - update callback plumbing for the direct Downloads export action
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - pass the direct Downloads export callback into the diagnostics section
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
  - surface success/failure toasts for direct Downloads export
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerTransportBenchmarkSelection.kt`
  - carry envelope provenance (`config_benchmark`, `fallback_benchmark`, `locked_default`)
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - record the selected envelope source/provider and log reopen causes / envelope details
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - include real `serviceKey`, `provider`, benchmark id/source, and reopen-cause metadata in the trace header
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
  - remove continuation-end scheduling substitution
  - add scheduling de-dup / throttling
  - make bootstrap sizing provider-aware
  - emit reopen-cause details and trim scheduler churn on tiny reads
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
  - make range scheduling honor real reader position
  - add mandatory terminal events and body-progress events
  - stop self-scheduling ahead from `end` where it mis-targets urgent work
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`
  - tune startup publication / no-progress behavior so partial urgent reads can become visible sooner
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackOkHttpEventListener.kt`
  - ensure request/response/body events stay consistent and analyzable
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt`
  - harden pooled trace-record reuse
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`
  - fix malformed/truncated JSONL output and ensure newline termination / flush semantics
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PayloadBuilder.kt`
  - support any new trace-header fields and safer payload composition
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md`
  - document the direct Downloads export path and new trace-provenance fields

### Test files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`
  - pin direct Downloads ZIP export behavior
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
  - pin trace header identity, body-progress, and terminal range events
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/CapabilityEnvelopeTest.kt`
  - pin the new default envelope shape
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/TransportPolicyControllerSeekPolicyTest.kt`
  - keep provider-aware startup/seek expectations
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitializationTest.kt`
  - verify locked/default/config/fallback provenance and shape logging assumptions
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManagerEnvelopeTest.kt`
  - pin real-reader-position urgent scheduling behavior and parked prefetch semantics
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt`
  - verify cursor/read-side behavior with startup publication granularity changes
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PutHeaderContractTest.kt`
  - pin new header fields and output keys
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`
  - reproduce and prevent malformed/truncated JSONL output under concurrent range events

## Guardrails

- Do not change the locked RD/PM structural shapes.
- Do not remove the PRDS architecture or fall back to a single-connection transport as the default.
- Do not assume the trace’s prior malformed lines were only a parser issue; fix the writer path, not just offline analysis.
- Keep all new transport instrumentation cheap on the hot path.
- For any scheduling change, preserve correctness for seek/rebuffer/reopen paths before optimizing throughput.
- The plan must cover all 10 audited issues in one pass:
  - direct Downloads export
  - trace header provenance
  - reopen-cause instrumentation
  - provider-aware bootstrap sizing
  - continuation-pump scheduling override removal
  - scheduler churn reduction
  - mandatory terminal/body-progress range events
  - startup/frontier publication tuning
  - tiny-read CPU churn mitigation
  - JSONL corruption fix

---

### Task 1: Lock In Export Reliability With Failing Tests

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`

- [ ] **Step 1: Add/confirm a failing test for direct Downloads ZIP export**

```kotlin
@Test
fun `copyAllToDownloads writes zip to downloads and returns adb path`() = runTest {
    val appFilesDir = createTempDirectory("playback-trace-controller-downloads").toFile()
    val tracesDir = File(appFilesDir, "playback-traces").apply { mkdirs() }
    File(tracesDir, "session-a.jsonl").writeText("{\"type\":\"started\"}\n")
    File(tracesDir, "session-a-1.jsonl").writeText("{\"type\":\"rotated\"}\n")

    val insertedUri = Uri.parse("content://tests/downloads/playback-traces.zip")
    val destinationBytes = ByteArrayOutputStream()
    val contentResolver = mockk<ContentResolver>()
    every {
        contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, any<ContentValues>())
    } returns insertedUri
    every { contentResolver.openOutputStream(insertedUri) } returns destinationBytes

    val controller = buildController(
        filesDir = appFilesDir,
        cacheDir = createTempDirectory("playback-trace-controller-downloads-cache").toFile(),
        contentResolver = contentResolver,
    )

    val adbPath = controller.copyAllToDownloads()

    assertTrue(adbPath?.startsWith("/sdcard/Download/playback-traces-") == true)
    assertTrue(adbPath?.endsWith(".zip") == true)
}
```

- [ ] **Step 2: Add a failing test that zero-byte Downloads export reports failure**

```kotlin
@Test
fun `copyAllToDownloads returns null when downloads output stream is unavailable`() = runTest {
    val appFilesDir = createTempDirectory("playback-trace-controller-downloads-null").toFile()
    File(appFilesDir, "playback-traces").apply {
        mkdirs()
        resolve("session-a.jsonl").writeText("{\"type\":\"started\"}\n")
    }

    val insertedUri = Uri.parse("content://tests/downloads/broken")
    val contentResolver = mockk<ContentResolver>()
    every {
        contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, any<ContentValues>())
    } returns insertedUri
    every { contentResolver.openOutputStream(insertedUri) } returns null

    val controller = buildController(
        filesDir = appFilesDir,
        cacheDir = createTempDirectory("playback-trace-controller-downloads-null-cache").toFile(),
        contentResolver = contentResolver,
    )

    val adbPath = controller.copyAllToDownloads()

    assertEquals(null, adbPath)
}
```

- [ ] **Step 3: Run the focused controller test class and verify red/green behavior**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.PlaybackTraceControllerTest"`

Expected before implementation: FAIL on the new assertions if behavior is missing.

- [ ] **Step 4: Keep the implementation minimal**

```kotlin
suspend fun copyAllToDownloads(): String? = withContext(Dispatchers.IO) {
    val files = listTraces()
    if (files.isEmpty()) return@withContext null
    val zipFile = buildAllSessionsZip(files)
    writeFileToDownloads(
        sourceFile = zipFile,
        displayName = zipFile.name,
        mimeType = "application/zip",
    )
}
```

- [ ] **Step 5: Re-run the focused controller tests and commit**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt
git commit -m "feat: export playback traces directly to downloads"
```

---

### Task 2: Move The Main UI Export Path To Direct Downloads

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackDiagnosticsSection.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values/strings.xml`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-de/strings.xml`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-es/strings.xml`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-nl/strings.xml`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-zh-rCN/strings.xml`

- [ ] **Step 1: Replace the all-sessions callback with a direct Downloads action**

```kotlin
internal fun LazyListScope.playbackDiagnosticsItems(
    // ...
    onExportAllToDownloads: () -> Unit,
    onCopyToDownloads: (Uri) -> Unit,
    // ...
)
```

- [ ] **Step 2: Make the row click directly invoke Downloads export**

```kotlin
item(key = "playback_diagnostics_export_all") {
    SettingsActionRow(
        title = stringResource(R.string.playback_diagnostics_export_all_title),
        subtitle = stringResource(R.string.playback_diagnostics_export_all_subtitle),
        value = if (status.sessionCount > 0) {
            "${status.sessionCount} · ${"%.1f".format(status.totalBytes / (1024.0 * 1024.0))} MiB"
        } else null,
        enabled = status.sessionCount > 0,
        onClick = onExportAllToDownloads,
    )
}
```

- [ ] **Step 3: Surface explicit success/failure toasts**

```kotlin
internal fun copyAllTracesZipToDownloads() {
    viewModelScope.launch {
        val adbPath = playbackTraceController.copyAllToDownloads()
        playbackTraceController.refreshStatus()
        if (adbPath != null) {
            messages.tryEmit("Saved diagnostics zip to $adbPath")
        } else {
            messages.tryEmit("Failed to save diagnostics zip")
        }
    }
}
```

- [ ] **Step 4: Update copy/docs to reflect Downloads as the primary path**

```xml
<string name="playback_diagnostics_export_all_subtitle">
    Bundle every retained session into a .zip and save it to Downloads
</string>
```

- [ ] **Step 5: Run the compile gate**

Run: `./gradlew :app:compileUniversalDebugKotlin`

Expected: PASS.

- [ ] **Step 6: Commit the export path update**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackDiagnosticsSection.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/res/values/strings.xml
git add /Users/jneerdael/Scripts/nexio/app/src/main/res/values-de/strings.xml
git add /Users/jneerdael/Scripts/nexio/app/src/main/res/values-es/strings.xml
git add /Users/jneerdael/Scripts/nexio/app/src/main/res/values-nl/strings.xml
git add /Users/jneerdael/Scripts/nexio/app/src/main/res/values-zh-rCN/strings.xml
git add /Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md
git commit -m "feat: use downloads for playback diagnostics export"
```

---

### Task 3: Record Full Provider And Envelope Provenance In The Trace Header

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerTransportBenchmarkSelection.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PutHeaderContractTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitializationTest.kt`

- [ ] **Step 1: Extend the selected-benchmark object with provenance**

```kotlin
internal data class SelectedTransportBenchmark(
    val capabilityEnvelope: CapabilityEnvelope?,
    val runtimeTransportHints: RuntimeTransportHintsV2?,
    val benchmarkSource: String?,
    val benchmarkResultId: String?,
    val providerStorageKey: String?,
)
```

- [ ] **Step 2: Carry source labels from selection**

```kotlin
benchmarkSource = "config_benchmark"
benchmarkResultId = "${primaryResult.provider.storageKey}:${primaryResult.measuredAtMs}"
providerStorageKey = primaryResult.provider.storageKey
```

```kotlin
benchmarkSource = "fallback_benchmark"
benchmarkResultId = "${provider.storageKey}:${fallbackResults[provider]?.measuredAtMs}"
providerStorageKey = provider.storageKey
```

- [ ] **Step 3: Thread the fields into the trace session header**

```kotlin
mediaSourceFactory.playbackTraceServiceKey = currentStreamServiceKey
mediaSourceFactory.playbackTraceProviderStorageKey =
    selectedTransportBenchmark?.providerStorageKey ?: providerStorageKey
mediaSourceFactory.playbackTraceBenchmarkSource = when {
    selectedTransportBenchmark?.benchmarkSource != null ->
        selectedTransportBenchmark.benchmarkSource
    lockedEnvelope != null -> "locked_default"
    else -> null
}
mediaSourceFactory.playbackTraceBenchmarkResultId =
    selectedTransportBenchmark?.benchmarkResultId
```

```kotlin
val header = SessionHeader(
    // ...
    serviceKey = playbackTraceServiceKey,
    provider = playbackTraceProviderStorageKey,
    benchmarkResultId = playbackTraceBenchmarkResultId,
    benchmarkSource = playbackTraceBenchmarkSource,
    // ...
)
```

- [ ] **Step 4: Add an initialization test for locked/default provenance**

```kotlin
@Test
fun `RD locked default path records RD provider provenance`() {
    val rdEnvelope = CapabilityEnvelope.LOCKED_REAL_DEBRID
    val controller = TransportPolicyController(rdEnvelope, provider = "real_debrid")
    assertEquals(1, controller.currentPolicy.urgentWorkers)
}
```

Add companion assertions in the header-contract test for `serviceKey`, `provider`, `benchmarkSource`, and `benchmarkResultId`.

- [ ] **Step 5: Run focused tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerInitializationTest" --tests "com.nexio.tv.instrumentation.PutHeaderContractTest"`

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerTransportBenchmarkSelection.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PutHeaderContractTest.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitializationTest.kt
git commit -m "feat: record playback transport provenance in traces"
```

---

### Task 4: Add Reopen-Cause Instrumentation

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`

- [ ] **Step 1: Add a reopen-cause field to PRDS open/close events**

```kotlin
private fun emitPrdsOpenStart(dataSpec: DataSpec, reopenCause: String?) {
    PlaybackTracer.emit(EventFamily.PRDS, "prds_open_start") {
        putLong("position", dataSpec.position)
        putLong("length", dataSpec.length)
        putString("reopenCause", reopenCause)
        // ...
    }
}
```

- [ ] **Step 2: Distinguish cold open, EOF probe, continuation reopen, and seek-like reopen**

```kotlin
val reopenCause = when {
    dataSpec.position == 0L -> "cold_start"
    clampedOpenLength == 0L -> "eof_probe"
    dataSpec.position > 0L && dataSpec.length == C.LENGTH_UNSET.toLong() -> "seek_like_reopen"
    else -> "continuation_reopen"
}
```

- [ ] **Step 3: Extend the tracing test to assert the new key exists**

```kotlin
assertTrue(out.contains("\"reopenCause\""))
```

- [ ] **Step 4: Run the focused tracing test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest"`

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git commit -m "feat: label playback transport reopen causes"
```

---

### Task 5: Make Bootstrap Sizing Provider-Aware

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`

- [ ] **Step 1: Write a failing test for provider-aware bootstrap sizing**

```kotlin
assertTrue(out.contains("\"bootstrapBytesRequested\":33554432"))
```

Use an RD-shaped envelope and assert the trace no longer shows a hardcoded `1 MiB` bootstrap request.

- [ ] **Step 2: Replace the fixed bootstrap cap with provider-aware sizing**

```kotlin
private fun bootstrapReadBytes(): Long {
    return when {
        envelope == CapabilityEnvelope.LOCKED_REAL_DEBRID -> 32L * 1024L * 1024L
        envelope == CapabilityEnvelope.LOCKED_PREMIUMIZE -> 16L * 1024L * 1024L
        else -> 24L * 1024L * 1024L
    }
}
```

```kotlin
val bootstrapBytes = minOf(
    minOf(activeChunkSize, bootstrapReadBytes()),
    clampedOpenLength
).toInt()
```

- [ ] **Step 3: Run the tracing test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest"`

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git commit -m "feat: use provider-aware playback bootstrap sizing"
```

---

### Task 6: Remove Continuation-End Scheduling Override

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`

- [ ] **Step 1: Add a failing test that reader-position scheduling does not jump to chunk-end**

```kotlin
assertFalse(out.contains("\"readerPosition\":33554432"))
```

For a low-byte read near session start, the trace should not schedule from a synthetic chunk-end position while the cursor is still at the true read position.

- [ ] **Step 2: Remove the substitution in `scheduleFromCursor()`**

```kotlin
private fun scheduleFromCursor() {
    if (!transportManager.isAttached()) return
    transportManager.scheduleForReaderPosition(position)
}
```

- [ ] **Step 3: Keep continuation pump signaling but stop using it as a fake reader position**

If needed, add a separate event such as:

```kotlin
emitPrdsReadReturn(read, length)
PlaybackTracer.emit(EventFamily.PRDS, "continuation_window") {
    putLong("continuationEndPositionExclusive", continuationEndPositionExclusive)
}
```

- [ ] **Step 4: Run focused tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.SequentialReadCursorTest" --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest"`

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git commit -m "fix: schedule ranges from the real reader position"
```

---

### Task 7: Reduce Scheduling Churn On Tiny Reads

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`

- [ ] **Step 1: Add a failing test that repeated tiny reads do not spam promote/schedule**

```kotlin
val promoteCount = "\"ev\":\"range_promote\"".toRegex().findAll(out).count()
assertTrue(promoteCount < 50)
```

Use a small deterministic fixture where the current implementation would otherwise promote on every tiny read.

- [ ] **Step 2: Add de-dup state to PRDS scheduling**

```kotlin
@Volatile private var lastScheduledChunkIndex: Long = C.INDEX_UNSET.toLong()
@Volatile private var lastScheduledFrontier: Long = -1L
```

```kotlin
private fun scheduleFromCursor() {
    if (!transportManager.isAttached()) return
    val currentChunkIndex = position / activeChunkSize
    val currentFrontier = store.frontier
    if (currentChunkIndex == lastScheduledChunkIndex && currentFrontier == lastScheduledFrontier) return
    lastScheduledChunkIndex = currentChunkIndex
    lastScheduledFrontier = currentFrontier
    transportManager.scheduleForReaderPosition(position)
}
```

- [ ] **Step 3: Run the focused tracing test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest"`

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git commit -m "perf: reduce playback transport scheduling churn"
```

---

### Task 8: Add Mandatory Terminal Events And Body-Progress Diagnostics

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/StutterClassifierRuleCoverageTest.kt`

- [ ] **Step 1: Write a failing tracing test for `range_finish` and `range_http_body_progress`**

```kotlin
assertTrue(out.contains("\"ev\":\"range_finish\""))
assertTrue(out.contains("\"ev\":\"range_http_body_progress\""))
```

- [ ] **Step 2: Emit body-progress for each read**

```kotlin
emitRangeContextEvent("range_http_body_progress", attemptContext) {
    putInt("bytesRead", read)
    putInt("totalRead", totalRead)
    putInt("expectedBytes", expectedBytes)
    putLong("offsetInChunk", offsetInChunk)
    putLong("sampleTimeMs", sampleTime)
}
```

- [ ] **Step 3: Emit `range_finish` for success, preempt, and failure**

```kotlin
emitRangeContextEvent("range_finish", attemptContext) {
    putString("result", "success")
    putInt("totalRead", totalRead)
    putInt("expectedBytes", expectedBytes)
}
```

```kotlin
emitRangeContextEvent("range_finish", context) {
    putString("result", "preempted")
    putInt("totalRead", handle.totalRead)
    putInt("expectedBytes", expectedBytes)
}
```

```kotlin
emitRangeEvent("range_finish") {
    putLong("chunkIndex", chunkIndex)
    putString("lane", "urgent")
    putString("result", "failed")
    putInt("totalRead", totalRead)
    putInt("expectedBytes", expectedBytes)
}
```

- [ ] **Step 4: Extend the classifier coverage to consume the new event shape where relevant**

```kotlin
events += event("RANGE", "range_finish", "tNs" to T_END - 500_000_000L, "result" to "success", "totalRead" to 4L * 1024 * 1024)
```

- [ ] **Step 5: Run focused tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest" --tests "com.nexio.tv.instrumentation.StutterClassifierRuleCoverageTest"`

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/StutterClassifierRuleCoverageTest.kt
git commit -m "feat: add terminal playback range diagnostics"
```

---

### Task 9: Tune Startup Frontier Publication And Tiny-Read Costs

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/AbsoluteByteStore.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`

- [ ] **Step 1: Add a failing test for startup visibility when bytes arrive below a full page**

```kotlin
@Test
fun `startup path can advance reader visibility before a full 128 KiB page is filled`() {
    val buffer = PagedFrontierBuffer()
    buffer.setTotalLength(4L * PagedFrontierBuffer.PAGE_SIZE)
    buffer.writeAt(1_048_576L, ByteArray(73_728), 0, 73_728)
    assertTrue(buffer.readableContiguousBytesFrom(1_048_576L) > 0L)
}
```

- [ ] **Step 2: Introduce a startup-friendly publication strategy**

One acceptable direction:

```kotlin
fun publishStartupWindow(absoluteStart: Long, chunk: ByteArray, length: Int) {
    writeAt(absoluteStart, chunk, 0, length)
}
```

Or reduce the page size only for startup bootstrap buffers if the existing tests show that’s cleaner.

- [ ] **Step 3: Keep no-progress-band polling meaningful**

Do not emit `frontier_no_progress_band` every 100 ms if the only reason is tiny page-fragment progress.

- [ ] **Step 4: Run focused buffer tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferTest"`

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/AbsoluteByteStore.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt
git commit -m "perf: improve startup frontier publication"
```

---

### Task 10: Fix JSONL Corruption In Concurrent Trace Output

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PayloadBuilder.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`

- [ ] **Step 1: Write a failing concurrency test that reproduces malformed JSONL**

```kotlin
@Test
fun `concurrent range events always produce parseable jsonl lines`() {
    val sink = StringWriter()
    val writer = SessionWriter(header = SessionHeaderFixture.build(), baseFile = null, testSink = sink)
    val threads = List(4) { index ->
        Thread {
            repeat(1_000) {
                writer.enqueue(EventFamily.RANGE, "range_http_body") {
                    putLong("chunkIndex", index.toLong())
                    putInt("bytesRead", 1024)
                    putInt("totalRead", it + 1)
                }
            }
        }
    }
    threads.forEach(Thread::start)
    threads.forEach(Thread::join)
    writer.shutdown()

    sink.toString().lineSequence().filter { it.isNotBlank() }.forEach { line ->
        JsonParser.parseString(line)
    }
}
```

- [ ] **Step 2: Make payload composition and record recycling unambiguously safe**

One acceptable direction:

```kotlin
fun recycle() {
    family = EventFamily.TRACER
    type = ""
    tNanos = 0L
    thread = ""
    resetPayload()
    TraceRecordPool.release(this)
}
```

And ensure the writer always appends one full builder snapshot per record before recycling.

- [ ] **Step 3: Ensure the writer flushes/terminates the final line cleanly**

```kotlin
line.append("}\n")
sink.append(line)
sink.flush()
```

Only add the extra flush if the test demonstrates it is required to prevent truncation.

- [ ] **Step 4: Run the focused integrity tests and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.instrumentation.SessionWriterJsonlIntegrityTest" --tests "com.nexio.tv.instrumentation.PutHeaderContractTest"`

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PayloadBuilder.kt
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt
git commit -m "fix: harden playback trace jsonl integrity"
```

---

### Task 11: Final Cross-Cut Verification And Fresh-Trace Audit

**Files:**
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/CapabilityEnvelopeTest.kt`
- Test: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/TransportPolicyControllerSeekPolicyTest.kt`

- [ ] **Step 1: Run the focused regression suite**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.instrumentation.PlaybackTraceControllerTest" \
  --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTracingTest" \
  --tests "com.nexio.tv.data.repository.benchmark.CapabilityEnvelopeTest" \
  --tests "com.nexio.tv.ui.screens.player.TransportPolicyControllerSeekPolicyTest" \
  --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerInitializationTest" \
  --tests "com.nexio.tv.ui.screens.player.SequentialReadCursorTest" \
  --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferTest" \
  --tests "com.nexio.tv.instrumentation.SessionWriterJsonlIntegrityTest"
```

Expected: PASS.

- [ ] **Step 2: Run the Kotlin compile gate**

Run: `./gradlew :app:compileUniversalDebugKotlin`

Expected: PASS.

- [ ] **Step 3: Validate ADB export on a connected device**

Run:

```bash
adb shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_COPY_ALL \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
```

Expected:
- `PlaybackTraceAdb: ADB: copy_all — wrote /sdcard/Download/playback-traces-<timestamp>.zip`
- non-zero file size under `/sdcard/Download`

- [ ] **Step 4: Capture and analyze one fresh rolled trace**

Check:
- header shows `serviceKey`, `provider`, `benchmarkSource`
- no malformed JSONL lines
- `range_finish` exists for all started ranges
- reader-position scheduling no longer jumps to synthetic chunk ends

- [ ] **Step 5: Commit the completed audit-fix pass**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java
git add /Users/jneerdael/Scripts/nexio/app/src/test/java
git add /Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md
git commit -m "fix: harden playback transport path for high bitrate remuxes"
```

---

## Self-Review

### Spec coverage

- Export reliability is covered by Tasks 1-2.
- Trace header/provider provenance is covered by Task 3.
- Reopen-cause instrumentation is covered by Task 4.
- Provider-aware bootstrap sizing is covered by Task 5.
- Continuation scheduling override removal is covered by Task 6.
- Scheduler churn reduction is covered by Task 7.
- Mandatory terminal/body-progress range events are covered by Task 8.
- Startup/frontier publication tuning is covered by Task 9.
- JSONL corruption is covered by Task 10.
- Final integrated verification and fresh-trace audit is covered by Task 11.

### Placeholder scan

- No `TODO`, `TBD`, or “similar to task above” placeholders remain.
- Every code-changing task includes concrete file paths, commands, and example code.
- All verification steps specify explicit Gradle or ADB commands.

### Type consistency

- Export path names are consistent: `copyAllToDownloads`, `copyAllTracesZipToDownloads`, `onExportAllToDownloads`.
- Provenance names are consistent: `benchmarkSource`, `benchmarkResultId`, `providerStorageKey`.
- Range terminal/body diagnostics are consistently named `range_finish` and `range_http_body_progress`.
