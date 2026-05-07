# Debrid Configuration Benchmark Matrix Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second Debrid settings benchmark mode for Real-Debrid and Premiumize that runs a fixed 9-profile optimized transport matrix, marks memory-unsafe profiles as unsupported, shows all profile results in a compact completion modal, and highlights the best successful profile when one exists.

**Architecture:** Keep the existing direct-vs-optimized benchmark intact. Introduce a parallel configuration-matrix benchmark service/store/result schema that reuses the existing optimized benchmark transport with frozen config snapshots, runs all profiles against the same resolved candidate, and presents grouped completion results by chunk size with subrows for 2x/3x/4x parallelism.

**Tech Stack:** Kotlin, Jetpack Compose for TV, Hilt, DataStore Preferences, existing Debrid benchmark candidate resolution, existing optimized benchmark transport/runtime, JUnit, MockK.

---

## File Structure

- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkModels.kt`
  Responsibility: Session result, profile result, status, unsupported reason, summary, and runtime-state models for the configuration matrix benchmark.
- Create: `app/src/main/java/com/nexio/tv/data/local/DebridConfigBenchmarkStore.kt`
  Responsibility: Persist the latest configuration benchmark result per provider.
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkService.kt`
  Responsibility: Single-flight orchestration, candidate reuse, profile iteration, memory gating, persistence, and runtime state.
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkMemoryGate.kt`
  Responsibility: Determine whether a config profile is safe to run on the current device and return an explainable unsupported reason when not safe.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
  Responsibility: Expose or parameterize a sustained-only 30-second profile run that can be reused by the config matrix service without dragging in the direct-vs-optimized comparison semantics.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCandidateResolver.kt`
  Responsibility: Reuse the same candidate resolution path for the new benchmark mode.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt` and/or shared benchmark coordinator wiring
  Responsibility: Enforce mutual exclusion so the configuration matrix benchmark cannot run concurrently with the existing transport-comparison benchmark.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
  Responsibility: Add a second benchmark action, live runtime labels, latest-result summary, and the grouped completion modal for configuration matrix results.
- Modify: `app/src/main/res/values/strings.xml`
  Responsibility: Add labels and status copy for configuration benchmark actions/results.
- Test: `app/src/test/java/com/nexio/tv/data/local/DebridConfigBenchmarkStoreTest.kt`
  Responsibility: Verify latest-result persistence per provider.
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkMemoryGateTest.kt`
  Responsibility: Verify memory-unsafe profiles are marked unsupported instead of executed.
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkServiceTest.kt`
  Responsibility: Verify same-candidate reuse, 9-profile orchestration, ranking, unsupported handling, failure handling, and persistence.
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt`
  Responsibility: Verify settings row state and completion dialog behavior for the new benchmark mode.

## Task 1: Add configuration benchmark models and latest-result persistence

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkModels.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/DebridConfigBenchmarkStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/DebridConfigBenchmarkStoreTest.kt`

- [ ] **Step 1: Write the failing store tests**

```kotlin
@Test
fun `saving a latest config benchmark result overwrites the previous result for that provider`() = runTest {
    val store = buildStore()
    store.saveLatest(sampleMatrixResult(provider = DebridBenchmarkProvider.REAL_DEBRID, measuredAtMs = 1L))
    store.saveLatest(sampleMatrixResult(provider = DebridBenchmarkProvider.REAL_DEBRID, measuredAtMs = 2L))

    assertEquals(2L, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()?.measuredAtMs)
}

@Test
fun `provider config benchmark results remain isolated`() = runTest {
    val store = buildStore()
    store.saveLatest(sampleMatrixResult(provider = DebridBenchmarkProvider.REAL_DEBRID))
    store.saveLatest(sampleMatrixResult(provider = DebridBenchmarkProvider.PREMIUMIZE))

    assertNotNull(store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
    assertNotNull(store.latestResult(DebridBenchmarkProvider.PREMIUMIZE).first())
}
```

- [ ] **Step 2: Run the targeted store tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.DebridConfigBenchmarkStoreTest"`
Expected: FAIL because the config benchmark models/store do not exist yet.

- [ ] **Step 3: Implement the minimal result model and store**

```kotlin
enum class DebridConfigBenchmarkStatus { SUCCESS, FAILED, UNSUPPORTED }

data class DebridConfigBenchmarkProfileResult(
    val parallelConnectionCount: Int,
    val chunkSizeMb: Int,
    val status: DebridConfigBenchmarkStatus,
    val averageThroughputMbps: Double? = null,
    val failureReason: String? = null,
    val unsupportedReason: String? = null
)
```

- [ ] **Step 4: Run the targeted store tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.DebridConfigBenchmarkStoreTest"`
Expected: PASS

- [ ] **Step 5: Commit the persistence slice**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkModels.kt \
  app/src/main/java/com/nexio/tv/data/local/DebridConfigBenchmarkStore.kt \
  app/src/test/java/com/nexio/tv/data/local/DebridConfigBenchmarkStoreTest.kt
git commit -m "Add config-benchmark result storage for debrid providers\n\nConstraint: Must keep the existing transport-comparison benchmark schema untouched\nRejected: Reuse DebridBenchmarkStore | would couple two unrelated result shapes and dialogs\nConfidence: high\nScope-risk: narrow\nDirective: Keep config-benchmark persistence separate from transport-comparison persistence unless the UI/result schemas are unified deliberately\nTested: DebridConfigBenchmarkStoreTest\nNot-tested: DataStore migration from older app versions"
```

## Task 2: Add a memory-safety gate for benchmark profiles

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkMemoryGate.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkMemoryGateTest.kt`

- [ ] **Step 1: Write the failing memory-gate tests**

```kotlin
@Test
fun `24mb x4 profile is reported unsupported when estimated memory exceeds device budget`() {
    val gate = DebridConfigBenchmarkMemoryGate(deviceBudgetBytes = 64L * 1024L * 1024L)

    val decision = gate.evaluate(parallelConnectionCount = 4, chunkSizeMb = 24)

    assertEquals(ProfileRunDecision.Unsupported("exceeds safe memory budget"), decision)
}

@Test
fun `8mb x2 profile is runnable when within device budget`() {
    val gate = DebridConfigBenchmarkMemoryGate(deviceBudgetBytes = 256L * 1024L * 1024L)

    assertEquals(ProfileRunDecision.Run, gate.evaluate(parallelConnectionCount = 2, chunkSizeMb = 8))
}
```

- [ ] **Step 2: Run the memory-gate tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkMemoryGateTest"`
Expected: FAIL because the gate does not exist yet.

- [ ] **Step 3: Implement a deterministic and explainable gate**

```kotlin
class DebridConfigBenchmarkMemoryGate(
    private val deviceBudgetBytes: Long,
    private val overheadMultiplier: Double = 1.5
) {
    fun evaluate(parallelConnectionCount: Int, chunkSizeMb: Int): ProfileRunDecision
}
```

- [ ] **Step 4: Run the memory-gate tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkMemoryGateTest"`
Expected: PASS

- [ ] **Step 5: Commit the memory-gate slice**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkMemoryGate.kt \
  app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkMemoryGateTest.kt
git commit -m "Skip memory-unsafe config benchmark profiles before execution\n\nConstraint: Unsupported settings must be visible to users without risking OOM on constrained devices\nRejected: Let every profile run and rely on failure | unsafe and user-hostile\nConfidence: high\nScope-risk: narrow\nDirective: Keep the gate conservative and explainable; do not hide skipped profiles\nTested: DebridConfigBenchmarkMemoryGateTest\nNot-tested: Real-device calibration of threshold heuristics"
```

## Task 3: Reuse the optimized benchmark transport for sustained-only matrix runs

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransportTest.kt`

- [ ] **Step 1: Write the failing optimized transport reuse tests**

```kotlin
@Test
fun `config benchmark run returns a 30 second sustained average for the supplied snapshot`() = runTest {
    val transport = buildTransport()

    val result = transport.runConfigProfile(
        candidate = candidate(),
        configSnapshot = DebridBenchmarkTransportConfigSnapshot(
            useParallelConnections = true,
            parallelConnectionCount = 3,
            parallelChunkSizeMb = 16
        )
    )

    assertEquals(30_000L, result.elapsedMs)
    assertNotNull(result.averageThroughputMbps)
}
```

- [ ] **Step 2: Run the targeted transport test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkTransportTest"`
Expected: FAIL because the optimized transport does not expose a config-benchmark-specific profile run yet.

- [ ] **Step 3: Implement a sustained-only config benchmark API**

```kotlin
suspend fun runConfigProfile(
    candidate: DebridBenchmarkCandidate,
    configSnapshot: DebridBenchmarkTransportConfigSnapshot,
    observer: DebridConfigBenchmarkObserver = DebridConfigBenchmarkObserver {}
): DebridConfigBenchmarkTransportResult
```

- [ ] **Step 4: Run the targeted transport test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkTransportTest"`
Expected: PASS

- [ ] **Step 5: Commit the transport reuse slice**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt \
  app/src/test/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransportTest.kt
git commit -m "Expose sustained-only optimized transport runs for config benchmarking\n\nConstraint: Config benchmark must reuse the existing optimized benchmark path to stay representative\nRejected: Build a second optimized transport implementation | duplicate logic and drift risk\nConfidence: medium\nScope-risk: moderate\nDirective: Keep config-benchmark entry points thin wrappers around the shared optimized benchmark runtime\nTested: OptimizedBenchmarkTransportTest\nNot-tested: Device-side bandwidth/runtime impact across full nine-profile sessions"
```

## Task 4: Add the configuration matrix benchmark service

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCandidateResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkServiceTest.kt`

- [ ] **Step 1: Write the failing service orchestration tests**

```kotlin
@Test
fun `service runs all matrix profiles against the same candidate`() = runTest {
    val candidate = candidate(directUrl = "https://host/file")
    val service = buildService(resolvedCandidate = candidate)

    service.start(DebridBenchmarkProvider.REAL_DEBRID)

    assertTrue(service.executedCandidates.all { it.directUrl == candidate.directUrl })
}

@Test
fun `best profile is highest average throughput among successful runs only`() = runTest {
    val service = buildService(
        profileResults = listOf(
            success(2, 8, 400.0),
            failed(3, 8),
            success(4, 16, 700.0)
        )
    )

    val result = service.runOnce(DebridBenchmarkProvider.REAL_DEBRID)

    assertEquals(4, result.summary.bestProfile!!.parallelConnectionCount)
    assertEquals(16, result.summary.bestProfile!!.chunkSizeMb)
}

@Test
fun `session with no successful profiles persists without a best profile summary`() = runTest {
    val service = buildService(
        profileResults = listOf(unsupported(2, 24), failed(3, 24), failed(4, 24))
    )

    val result = service.runOnce(DebridBenchmarkProvider.REAL_DEBRID)

    assertNull(result.summary.bestProfile)
    assertEquals(0, result.summary.successfulProfileCount)
}
```

- [ ] **Step 2: Run the targeted service test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkServiceTest"`
Expected: FAIL because the config benchmark service does not exist yet.

- [ ] **Step 3: Implement sequential matrix orchestration**

This slice must also define:
- no-success session behavior (persist + reopenable, but no best-profile banner)
- cancel behavior during profile iteration
- mutual exclusion with the existing transport-comparison benchmark
- URL-expiry behavior: fail the affected session clearly, do not re-resolve a different file mid-session

```kotlin
private val DEFAULT_MATRIX = listOf(
    profile(2, 8), profile(3, 8), profile(4, 8),
    profile(2, 16), profile(3, 16), profile(4, 16),
    profile(2, 24), profile(3, 24), profile(4, 24)
)
```

- [ ] **Step 4: Run the targeted service test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit the service slice**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCandidateResolver.kt \
  app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkService.kt \
  app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkServiceTest.kt
git commit -m "Add debrid config benchmark matrix orchestration\n\nConstraint: All profile runs in one session must use the same resolved file and URL for fairness\nRejected: Re-resolve per profile | would make results incomparable across the matrix\nConfidence: high\nScope-risk: moderate\nDirective: Keep config-profile ranking based on average throughput among successful runs only unless product requirements change\nTested: DebridConfigBenchmarkServiceTest\nNot-tested: Provider URL expiry mid-session on real accounts"
```

## Task 5: Integrate the second benchmark mode into Debrid settings UI

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt`

- [ ] **Step 1: Write the failing UI/ViewModel tests**

```kotlin
@Test
fun `connected provider row shows config benchmark action and latest best-profile summary`() = runTest {
    val viewModel = buildViewModel(
        latestConfigBenchmarkResult = sampleMatrixResult(bestProfile = success(4, 16, 742.0))
    )

    assertTrue(viewModel.uiState.value.realDebridConfigBenchmark.canRun)
    assertTrue(viewModel.uiState.value.realDebridConfigBenchmarkSummary!!.contains("4x / 16 MB"))
}

@Test
fun `completion dialog groups profile rows by chunk size with subrows`() = runTest {
    val viewModel = buildViewModel(completedConfigBenchmarkResult = sampleMatrixResult())

    viewModel.openLatestConfigBenchmarkResult(DebridBenchmarkProvider.REAL_DEBRID)

    assertEquals(3, viewModel.uiState.value.configBenchmarkResultDialog!!.chunkGroups.size)
}
```

- [ ] **Step 2: Run the targeted settings tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`
Expected: FAIL because the second benchmark mode is not wired into settings yet.

- [ ] **Step 3: Implement row state and modal presentation**

```kotlin
data class DebridConfigBenchmarkUi(
    val canRun: Boolean = false,
    val isRunning: Boolean = false,
    val latestResult: DebridConfigBenchmarkResult? = null,
    val activeProfileLabel: String? = null
)
```

- [ ] **Step 4: Run the targeted settings tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`
Expected: PASS

- [ ] **Step 5: Commit the UI slice**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt \
  app/src/main/res/values/strings.xml \
  app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt
git commit -m "Expose debrid config benchmark results in settings\n\nConstraint: The new benchmark must coexist with the existing transport-comparison benchmark without replacing it\nRejected: Merge both result types into one modal | would blur two different user jobs\nConfidence: medium\nScope-risk: moderate\nDirective: Preserve TV readability by keeping grouped chunk-size sections with compact 2x/3x/4x subrows\nTested: DebridSettingsViewModelTest\nNot-tested: Final TV focus ergonomics on device"
```

## Task 6: Run full verification and document rollout constraints

**Files:**
- Modify: `openspec/changes/add-debrid-config-benchmark-matrix/tasks.md`
- Optional docs touch if rollout notes need capture

- [ ] **Step 1: Run the benchmark-focused verification suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.DebridConfigBenchmarkStoreTest" --tests "com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkMemoryGateTest" --tests "com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkServiceTest" --tests "com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkTransportTest" --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`
Expected: PASS

- [ ] **Step 2: Run compile verification**

Run: `./gradlew :app:compileUniversalDebugKotlin`
Expected: PASS

- [ ] **Step 3: Mark the OpenSpec task list and rollout notes complete**

- [ ] **Step 4: Commit the verification/update slice**

```bash
git add openspec/changes/add-debrid-config-benchmark-matrix/tasks.md
git commit -m "Record config benchmark matrix verification completion\n\nConstraint: Planning artifacts must stay aligned with the implemented verification surface\nConfidence: high\nScope-risk: narrow\nDirective: Do not claim the config benchmark is ready without proving both service logic and settings presentation\nTested: benchmark-focused unit suite, compileUniversalDebugKotlin\nNot-tested: On-device bandwidth cost and provider URL expiry behavior"
```
