# Debrid Benchmark Transport Comparison Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand the existing debrid provider benchmark into a dual-transport session that measures direct and Nexio-optimized paths side-by-side, persists the latest provider comparison result, and exposes startup, sustained, and seek metrics in the completion modal.

**Architecture:** Keep the existing benchmark feature centered on `DebridBenchmarkService`, but evolve it from a single direct-path run into a session runner that executes shared startup, sustained, and seek phases for two transports against one resolved candidate. Reuse the existing direct discard transport, add a benchmark-only optimized transport harness around `ParallelRangeDataSource`, store one latest comparison session per provider, and keep the settings UI responsible only for presentation and user actions.

**Tech Stack:** Kotlin, Jetpack Compose for TV, Hilt, DataStore Preferences, OkHttp, Media3 `DataSource`, existing `ParallelRangeDataSource`, JUnit, MockK, MockWebServer, Android instrumentation via `adb`.

---

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkModels.kt`
  Responsibility: Evolve the benchmark model from one summary into a session result with candidate metadata, direct/optimized transport profiles, seek metrics, raw samples, comparison summary, and richer runtime state.
- Modify: `app/src/main/java/com/nexio/tv/data/local/DebridBenchmarkStore.kt`
  Responsibility: Serialize and restore the latest dual-transport benchmark session per provider while keeping provider isolation and payload validation.
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkMetricsCollector.kt`
  Responsibility: Collect startup, sustained, and seek samples; compute percentiles, variance/stddev, coefficient of variation, stall/read-gap summaries, and transport profile rollups.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkTransport.kt`
  Responsibility: Narrow the shared transport contracts, keep the direct transport implementation, and delegate heavy metrics logic out to the new collector.
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
  Responsibility: Provide a benchmark-only transport that uses Nexio’s parallel range path with a frozen config snapshot and no playback cache or warm-ahead reuse.
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt`
  Responsibility: Orchestrate direct vs optimized startup/sustained/seek phases, enforce fair phase ordering, and emit live progress snapshots back to the service.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt`
  Responsibility: Snapshot player transport config, run the new session runner, persist completed comparison results, and emit the richer completion outcome payload.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
  Responsibility: Add optional benchmark instrumentation hooks and an opt-out for bootstrap reuse so the optimized benchmark path can measure true transport behavior without polluting playback.
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
  Responsibility: Provide the direct and optimized benchmark transports plus any named clients or factories needed by the benchmark harness.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
  Responsibility: Replace the simple benchmark dialog content with a side-by-side direct/optimized comparison modal and adapt latest-result row/viewmodel logic to the new result shape.
- Modify: `app/src/main/res/values/strings.xml`
  Responsibility: Add direct/optimized comparison labels and metric strings for the modal.
- Test: `app/src/test/java/com/nexio/tv/data/local/DebridBenchmarkStoreTest.kt`
  Responsibility: Verify latest-result persistence for the new session schema.
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkMetricsTest.kt`
  Responsibility: Verify percentile, stddev/CV, stall/read-gap, and seek-latency aggregation behavior.
- Create: `app/src/test/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransportTest.kt`
  Responsibility: Verify optimized transport uses a frozen config snapshot, emits seek measurements, and avoids bootstrap reuse.
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkServiceTest.kt`
  Responsibility: Verify dual-transport session orchestration, persistence, and completion outcomes.
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt`
  Responsibility: Verify the completion modal and latest-result reopen flow for side-by-side results.
- Test: `app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt`
  Responsibility: Preserve the existing benchmark-is-not-idle behavior while the benchmark feature evolves.

## Task 1: Evolve the result model and latest-result store to a dual-transport session

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/DebridBenchmarkStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/DebridBenchmarkStoreTest.kt`

- [ ] **Step 1: Write the failing store tests for the new session result shape**

```kotlin
@Test
fun `saving a completed comparison result restores both transport profiles`() = runTest {
    val store = buildStore(backgroundScope)
    val expected = sampleSessionResult(
        provider = DebridBenchmarkProvider.REAL_DEBRID,
        measuredAtMs = 7L
    )

    store.saveLatest(expected)

    assertEquals(expected, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
}

@Test
fun `saving an incomplete comparison result is rejected without replacing the previous valid session`() = runTest {
    val store = buildStore(backgroundScope)
    val valid = sampleSessionResult(DebridBenchmarkProvider.PREMIUMIZE, measuredAtMs = 10L)
    store.saveLatest(valid)

    val invalid = valid.copy(
        optimized = valid.optimized.copy(seek = valid.optimized.seek.copy(seekTtfbP95Ms = null))
    )

    assertFailsWith<IllegalArgumentException> {
        store.saveLatest(invalid)
    }
    assertEquals(valid, store.latestResult(DebridBenchmarkProvider.PREMIUMIZE).first())
}
```

- [ ] **Step 2: Run the targeted store test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.DebridBenchmarkStoreTest"`
Expected: FAIL because `DebridBenchmarkResult` and `DebridBenchmarkStore` still only understand the phase-1 summary schema.

- [ ] **Step 3: Implement the minimal session-level model and store serialization**

```kotlin
data class DebridBenchmarkResult(
    val provider: DebridBenchmarkProvider,
    val measuredAtMs: Long,
    val candidate: DebridBenchmarkCandidateMetadata,
    val session: DebridBenchmarkSessionMetadata,
    val direct: DebridBenchmarkTransportProfile,
    val optimized: DebridBenchmarkTransportProfile,
    val comparison: DebridBenchmarkComparisonSummary,
    val terminationReason: DebridBenchmarkTerminationReason
)

data class DebridBenchmarkTransportProfile(
    val startup: DebridBenchmarkStartupMetrics,
    val sustained: DebridBenchmarkSustainedMetrics,
    val seek: DebridBenchmarkSeekMetrics,
    val configSnapshot: DebridBenchmarkTransportConfigSnapshot? = null,
    val rawSamples: DebridBenchmarkRawSamples
)
```

- [ ] **Step 4: Run the targeted store test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.DebridBenchmarkStoreTest"`
Expected: PASS

- [ ] **Step 5: Commit the model/store slice**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkModels.kt \
  app/src/main/java/com/nexio/tv/data/local/DebridBenchmarkStore.kt \
  app/src/test/java/com/nexio/tv/data/local/DebridBenchmarkStoreTest.kt
git commit -m "feat: store dual transport debrid benchmark sessions"
```

## Task 2: Build the shared startup, sustained, and seek metrics collector

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkMetricsCollector.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkTransport.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkMetricsTest.kt`

- [ ] **Step 1: Write the failing collector tests for percentile and stability summaries**

```kotlin
@Test
fun `collector reports p10 stddev and coefficient of variation from throughput windows`() {
    val collector = DebridBenchmarkMetricsCollector()
    collector.recordThroughputWindow(mbps = 120.0, atMs = 1_000L)
    collector.recordThroughputWindow(mbps = 80.0, atMs = 2_000L)
    collector.recordThroughputWindow(mbps = 100.0, atMs = 3_000L)

    val sustained = collector.finishSustained()

    assertEquals(80.0, sustained.p10ThroughputMbps, 0.001)
    assertTrue(sustained.throughputStddevMbps!! > 0.0)
    assertTrue(sustained.throughputCv!! > 0.0)
}

@Test
fun `collector reports seek p50 p95 and p99 from seek samples`() {
    val collector = DebridBenchmarkMetricsCollector()
    listOf(120L, 150L, 190L, 400L, 600L).forEachIndexed { index, ttfb ->
        collector.recordSeekSample(
            DebridBenchmarkSeekSample(targetOffsetBytes = index * 1_000_000L, ttfbMs = ttfb, succeeded = true)
        )
    }

    val seek = collector.finishSeek()

    assertEquals(190L, seek.seekTtfbP50Ms)
    assertEquals(600L, seek.seekTtfbP95Ms)
    assertEquals(600L, seek.seekTtfbP99Ms)
}
```

- [ ] **Step 2: Run the targeted metrics test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkMetricsTest"`
Expected: FAIL because the collector, throughput windows, and seek percentile types do not exist yet.

- [ ] **Step 3: Implement the minimal collector and transport-facing sample types**

```kotlin
class DebridBenchmarkMetricsCollector(
    private val sustainedThresholdBytes: Long = 500L * 1024L * 1024L,
    private val sustainedThresholdElapsedMs: Long = 120_000L
) {
    fun recordStartup(firstByteAtMs: Long, requestStartedAtMs: Long)
    fun recordBytesRead(totalBytesRead: Long, sampleAtMs: Long)
    fun recordReadGap(gapMs: Long)
    fun recordSeekSample(sample: DebridBenchmarkSeekSample)
    fun currentLiveSummary(): DebridBenchmarkLiveSummary
    fun finishSustained(): DebridBenchmarkSustainedMetrics
    fun finishSeek(): DebridBenchmarkSeekMetrics
}
```

- [ ] **Step 4: Run the targeted metrics test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkMetricsTest"`
Expected: PASS

- [ ] **Step 5: Commit the metrics slice**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkMetricsCollector.kt \
  app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkTransport.kt \
  app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkMetricsTest.kt
git commit -m "feat: add debrid benchmark percentile metrics"
```

## Task 3: Add the benchmark-only optimized transport harness

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransportTest.kt`

- [ ] **Step 1: Write the failing optimized transport tests**

```kotlin
@Test
fun `optimized transport freezes the player parallel config at benchmark start`() = runTest {
    val transport = buildOptimizedTransport(
        configSnapshot = DebridBenchmarkTransportConfigSnapshot(
            useParallelConnections = true,
            parallelConnectionCount = 4,
            parallelChunkSizeMb = 8
        )
    )

    val result = transport.runStartup(candidate())

    assertEquals(4, result.configSnapshot?.parallelConnectionCount)
    assertEquals(8, result.configSnapshot?.parallelChunkSizeMb)
}

@Test
fun `optimized transport disables bootstrap reuse during benchmark seeks`() = runTest {
    val transport = buildOptimizedTransport()

    transport.runSeekPhase(candidate(), seekTargets = listOf(10L, 20L, 30L))

    assertFalse(transport.usedBootstrapReuse)
}
```

- [ ] **Step 2: Run the targeted optimized transport test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkTransportTest"`
Expected: FAIL because no optimized benchmark transport or benchmark listener hooks exist yet.

- [ ] **Step 3: Implement the optimized benchmark transport and minimal parallel-range hooks**

```kotlin
class OptimizedBenchmarkTransport @Inject constructor(
    @Named("benchmark") private val okHttpClient: OkHttpClient
) {
    suspend fun runPhase(
        candidate: DebridBenchmarkCandidate,
        config: DebridBenchmarkTransportConfigSnapshot,
        phase: DebridBenchmarkPhase,
        collector: DebridBenchmarkMetricsCollector
    ): DebridBenchmarkPhaseResult
}
```

```kotlin
internal interface ParallelRangeBenchmarkListener {
    fun onFirstByte()
    fun onBytesRead(totalBytesRead: Long)
    fun onReadGap(gapMs: Long)
}
```

- [ ] **Step 4: Run the targeted optimized transport test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkTransportTest"`
Expected: PASS

- [ ] **Step 5: Commit the optimized transport slice**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt \
  app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt \
  app/src/test/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransportTest.kt
git commit -m "feat: add optimized debrid benchmark transport"
```

## Task 4: Orchestrate a dual-transport benchmark session in the service layer

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkTransport.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkServiceTest.kt`

- [ ] **Step 1: Write the failing session-orchestration tests**

```kotlin
@Test
fun `service persists one completed result containing direct and optimized profiles`() = runTest {
    val service = buildService(sessionRunner = completedSessionRunner(), scope = backgroundScope)

    assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))

    coVerify {
        store().saveLatest(withArg { saved ->
            assertNotNull(saved.direct.seek.seekTtfbP95Ms)
            assertNotNull(saved.optimized.configSnapshot)
        })
    }
}

@Test
fun `runtime state exposes active phase and transport while benchmarking`() = runTest {
    val service = buildService(sessionRunner = stagedSessionRunner(), scope = backgroundScope)

    service.start(DebridBenchmarkProvider.PREMIUMIZE)

    assertEquals(DebridBenchmarkPhase.SUSTAINED, service.activeState.value.phase)
    assertEquals(DebridBenchmarkTransportMode.OPTIMIZED, service.activeState.value.activeTransport)
}
```

- [ ] **Step 2: Run the targeted service test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkServiceTest"`
Expected: FAIL because the service still runs one direct transport and persists only a single summary.

- [ ] **Step 3: Implement the minimal session runner and service orchestration**

```kotlin
class DebridBenchmarkSessionRunner @Inject constructor(
    private val directTransport: DebridBenchmarkTransport,
    private val optimizedTransport: OptimizedBenchmarkTransport
) {
    suspend fun run(
        candidate: DebridBenchmarkCandidate,
        optimizedConfig: DebridBenchmarkTransportConfigSnapshot,
        onProgress: (DebridBenchmarkLiveProgress) -> Unit
    ): DebridBenchmarkSessionResult
}
```

- [ ] **Step 4: Run the targeted service test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit the session orchestration slice**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt \
  app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt \
  app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkTransport.kt \
  app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkServiceTest.kt
git commit -m "feat: orchestrate dual transport debrid benchmarks"
```

## Task 5: Expand the benchmark results UI into a side-by-side comparison modal

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt`

- [ ] **Step 1: Write the failing viewmodel/UI-state tests**

```kotlin
@Test
fun `completed benchmark outcome opens a comparison dialog with direct and optimized results`() = runTest(dispatcher) {
    val outcome = sampleCompletedComparisonOutcome()
    val viewModel = buildViewModel(benchmarkService = benchmarkServiceWithOutcome(outcome))

    advanceUntilIdle()

    assertEquals(outcome.result, viewModel.uiState.value.benchmarkResultDialog?.result)
}

@Test
fun `opening latest benchmark result exposes both transport columns`() = runTest(dispatcher) {
    val latest = sampleCompletedComparisonResult(DebridBenchmarkProvider.PREMIUMIZE)
    val viewModel = buildViewModel(
        premiumizeConnected = true,
        latestPremiumizeResult = latest
    )

    advanceUntilIdle()
    viewModel.openLatestBenchmarkResult(DebridBenchmarkProvider.PREMIUMIZE)

    assertEquals(latest.optimized.sustained.p10ThroughputMbps, viewModel.uiState.value.benchmarkResultDialog?.result?.optimized?.sustained?.p10ThroughputMbps)
}
```

- [ ] **Step 2: Run the targeted settings test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`
Expected: FAIL because the dialog and row logic still assume one summary, not a dual-column comparison.

- [ ] **Step 3: Implement the minimal comparison modal and viewmodel wiring**

```kotlin
internal data class DebridBenchmarkResultDialogUi(
    val provider: DebridBenchmarkProvider,
    val result: DebridBenchmarkResult
)
```

```kotlin
@Composable
private fun DebridBenchmarkResultDialog(
    dialog: DebridBenchmarkResultDialogUi,
    onDismiss: () -> Unit
) {
    // Shared header + Direct column + Nexio Optimized column
}
```

- [ ] **Step 4: Run the targeted settings test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`
Expected: PASS

- [ ] **Step 5: Commit the UI slice**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt \
  app/src/main/res/values/strings.xml \
  app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt
git commit -m "feat: show direct and optimized benchmark results"
```

## Task 6: Run focused regression verification and device smoke checks

**Files:**
- Test: `app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt`
- Modify after implementation: `openspec/changes/expand-debrid-benchmark-transport-comparison/tasks.md`

- [ ] **Step 1: Run the full focused benchmark verification slice**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.nexio.tv.data.local.DebridBenchmarkStoreTest" \
  --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkMetricsTest" \
  --tests "com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkTransportTest" \
  --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkServiceTest" \
  --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest" \
  --tests "com.nexio.tv.MainActivityIdleScreensaverTest"
```

Expected: PASS

- [ ] **Step 2: Validate the OpenSpec change after code completion**

Run: `openspec validate expand-debrid-benchmark-transport-comparison --strict`
Expected: `Change 'expand-debrid-benchmark-transport-comparison' is valid`

- [ ] **Step 3: Install the debug build on the target device**

Run:

```bash
adb connect 192.168.50.84:5555
./gradlew :app:installDebug
adb -s 192.168.50.84:5555 shell monkey -p com.nexio.tv -c android.intent.category.LAUNCHER 1
```

Expected: install succeeds and the app launches on the Google TV device.

- [ ] **Step 4: Mark the OpenSpec task checklist complete**

```markdown
- [x] Add spec deltas for dual-transport debrid benchmark sessions, richer metrics, and side-by-side results presentation.
- [x] Extend the benchmark result model and local storage to persist direct and optimized transport profiles plus shared candidate metadata.
- [x] Add shared metrics collection for startup, sustained transfer, and seek phases, including percentile and stability summaries.
- [x] Implement an optimized benchmark transport that measures the current Nexio parallel path without reusing playback cache or warm-ahead state.
- [x] Update the benchmark completion UX to show direct and optimized results side-by-side immediately and keep a latest-result re-open affordance.
- [x] Validate the OpenSpec change and write the follow-up implementation plan after spec approval.
```

- [ ] **Step 5: Commit the completed implementation**

```bash
git add app/src/main/java/com/nexio/tv/data/local/DebridBenchmarkStore.kt \
  app/src/main/java/com/nexio/tv/data/repository/benchmark \
  app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt \
  app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt \
  app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt \
  app/src/main/res/values/strings.xml \
  app/src/test/java/com/nexio/tv/data/local/DebridBenchmarkStoreTest.kt \
  app/src/test/java/com/nexio/tv/data/repository/benchmark \
  app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt \
  app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt \
  openspec/changes/expand-debrid-benchmark-transport-comparison/tasks.md
git commit -m "feat: compare direct and optimized debrid benchmarks"
```
