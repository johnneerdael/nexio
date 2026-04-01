# Debrid Provider Benchmark Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add manual Real-Debrid and Premiumize benchmark collection to the Debrid integration settings screen, persist the latest local result per provider, and expose live progress plus last-run summaries without changing autoplay or playback behavior.

**Architecture:** Introduce a dedicated debrid benchmark subsystem with provider candidate resolution, a direct-stream discard transport, rolling metric aggregation, and a latest-result local store. Integrate the subsystem into the existing Debrid settings ViewModel/UI as a single-flight, foreground-scoped workflow that stays isolated from playback cache, warm-ahead, and stream-link reuse behavior.

**Tech Stack:** Kotlin, Jetpack Compose for TV, Hilt, DataStore Preferences, OkHttp, Android lifecycle observation, JUnit, MockWebServer.

---

## File Structure

- Create: `app/src/main/java/com/nexio/tv/data/local/DebridBenchmarkStore.kt`
  Responsibility: Persist the latest local benchmark result per provider and expose it as flows.
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkModels.kt`
  Responsibility: Shared runtime state, result, summary, provider, failure-reason, and sample models.
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCandidateResolver.kt`
  Responsibility: Resolve a provider-specific direct playback candidate from the existing debrid library integration path.
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkTransport.kt`
  Responsibility: Transport abstraction plus the direct discard-stream transport implementation.
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt`
  Responsibility: Single-flight benchmark coordination, lifecycle cancellation, persistence writes, and UI-facing state flows.
- Create: `app/src/test/java/com/nexio/tv/data/local/DebridBenchmarkStoreTest.kt`
  Responsibility: Verify latest-result overwrite and restore behavior.
- Create: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCandidateResolverTest.kt`
  Responsibility: Verify provider candidate selection and failure paths.
- Create: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkMetricsTest.kt`
  Responsibility: Verify rolling metric aggregation, completion thresholds, and throughput summaries.
- Create: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkServiceTest.kt`
  Responsibility: Verify single-flight execution, cancellation, persistence writes, and background cancellation behavior.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt`
  Responsibility: Expose reusable provider benchmark candidate queries without duplicating library resolution logic.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
  Responsibility: Extend Debrid settings UI and ViewModel with benchmark controls, progress, summaries, and cancel behavior.
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
  Responsibility: Provide a dedicated benchmark OkHttp client if current network wiring cannot be reused safely.
- Modify: `app/src/main/res/values/strings.xml`
  Responsibility: Add benchmark-specific labels, statuses, and error messages.

### Task 1: Add benchmark models and latest-result persistence

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkModels.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/DebridBenchmarkStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/DebridBenchmarkStoreTest.kt`

- [ ] **Step 1: Write the failing persistence tests**

```kotlin
@Test
fun `saving a completed provider result overwrites the previous result for that provider`() = runTest {
    val store = buildStore()
    store.saveLatest(sampleResult(provider = REAL_DEBRID, measuredAtMs = 1L))
    store.saveLatest(sampleResult(provider = REAL_DEBRID, measuredAtMs = 2L))

    assertThat(store.latestResult(REAL_DEBRID).first()?.measuredAtMs).isEqualTo(2L)
}

@Test
fun `premiumize and real debrid latest results remain independent`() = runTest {
    val store = buildStore()
    store.saveLatest(sampleResult(provider = REAL_DEBRID))
    store.saveLatest(sampleResult(provider = PREMIUMIZE))

    assertThat(store.latestResult(REAL_DEBRID).first()).isNotNull()
    assertThat(store.latestResult(PREMIUMIZE).first()).isNotNull()
}
```

- [ ] **Step 2: Run the targeted store tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.DebridBenchmarkStoreTest"`
Expected: FAIL because the benchmark models/store do not exist yet.

- [ ] **Step 3: Implement the minimal benchmark model and store layer**

```kotlin
enum class DebridBenchmarkProvider { REAL_DEBRID, PREMIUMIZE }

data class DebridBenchmarkResult(
    val provider: DebridBenchmarkProvider,
    val measuredAtMs: Long,
    val summary: DebridBenchmarkSummary,
    val terminationReason: DebridBenchmarkTerminationReason
)
```

```kotlin
class DebridBenchmarkStore @Inject constructor(...) {
    fun latestResult(provider: DebridBenchmarkProvider): Flow<DebridBenchmarkResult?>
    suspend fun saveLatest(result: DebridBenchmarkResult)
    suspend fun clear(provider: DebridBenchmarkProvider)
}
```

- [ ] **Step 4: Run the targeted store tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.DebridBenchmarkStoreTest"`
Expected: PASS

- [ ] **Step 5: Commit the persistence slice**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkModels.kt \
  app/src/main/java/com/nexio/tv/data/local/DebridBenchmarkStore.kt \
  app/src/test/java/com/nexio/tv/data/local/DebridBenchmarkStoreTest.kt
git commit -m "feat: add debrid benchmark result storage"
```

### Task 2: Add provider benchmark candidate resolution from debrid library integration

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCandidateResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCandidateResolverTest.kt`

- [ ] **Step 1: Write the failing candidate resolver tests**

```kotlin
@Test
fun `resolver picks the newest playable real debrid item with a direct playback url`() = runTest {
    val resolver = buildResolver(
        realDebridItems = listOf(
            libraryEntry(provider = REAL_DEBRID, listedAt = 1L, directPlaybackUrl = "a"),
            libraryEntry(provider = REAL_DEBRID, listedAt = 2L, directPlaybackUrl = "b")
        )
    )

    assertThat(resolver.resolve(REAL_DEBRID)?.directUrl).isEqualTo("b")
}

@Test
fun `resolver reports no candidate when provider library is empty`() = runTest {
    val resolver = buildResolver()
    assertThat(resolver.resolve(PREMIUMIZE)).isNull()
}
```

- [ ] **Step 2: Run the resolver tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidateResolverTest"`
Expected: FAIL because the resolver and reusable provider query hook do not exist yet.

- [ ] **Step 3: Add reusable provider benchmark candidate access**

```kotlin
data class DebridBenchmarkCandidate(
    val provider: DebridBenchmarkProvider,
    val directUrl: String,
    val headers: Map<String, String>,
    val filename: String?,
    val sourceSizeBytes: Long?
)
```

```kotlin
class DebridBenchmarkCandidateResolver @Inject constructor(
    private val debridLibraryService: DebridLibraryService
) {
    suspend fun resolve(provider: DebridBenchmarkProvider): DebridBenchmarkCandidate?
}
```

- [ ] **Step 4: Run the resolver tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidateResolverTest"`
Expected: PASS

- [ ] **Step 5: Commit the candidate resolver slice**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt \
  app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCandidateResolver.kt \
  app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCandidateResolverTest.kt
git commit -m "feat: add debrid benchmark candidate resolution"
```

### Task 3: Add the direct discard-stream benchmark transport and metric aggregation

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkTransport.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkMetricsTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkServiceTest.kt`

- [ ] **Step 1: Write the failing metric and service tests**

```kotlin
@Test
fun `benchmark only completes after both 500MB and 120 seconds are satisfied`() {
    val aggregator = DebridBenchmarkAggregator(...)
    aggregator.recordSample(bytesRead = 600.mb, elapsedMs = 90.seconds)

    assertThat(aggregator.shouldComplete()).isFalse()
}

@Test
fun `service rejects a second benchmark while one is already active`() = runTest {
    val service = buildService(activeTransport = neverEndingTransport())

    assertThat(service.start(REAL_DEBRID)).isTrue()
    assertThat(service.start(PREMIUMIZE)).isFalse()
}
```

- [ ] **Step 2: Run the targeted benchmark tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkMetricsTest" --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkServiceTest"`
Expected: FAIL because the benchmark service, transport, and aggregator do not exist yet.

- [ ] **Step 3: Implement the direct benchmark runtime**

```kotlin
interface DebridBenchmarkTransport {
    suspend fun run(
        candidate: DebridBenchmarkCandidate,
        observer: DebridBenchmarkObserver
    ): DebridBenchmarkTransportResult
}

class DirectDiscardBenchmarkTransport @Inject constructor(
    @Named("benchmark") private val okHttpClient: OkHttpClient
) : DebridBenchmarkTransport
```

```kotlin
class DebridBenchmarkService @Inject constructor(
    private val resolver: DebridBenchmarkCandidateResolver,
    private val store: DebridBenchmarkStore,
    private val transport: DebridBenchmarkTransport
) {
    val activeState: StateFlow<DebridBenchmarkRuntimeState>
    fun latestResult(provider: DebridBenchmarkProvider): Flow<DebridBenchmarkResult?>
    suspend fun start(provider: DebridBenchmarkProvider): Boolean
    suspend fun cancel()
}
```

- [ ] **Step 4: Run the targeted benchmark tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkMetricsTest" --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit the benchmark runtime slice**

```bash
git add app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt \
  app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkTransport.kt \
  app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt \
  app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkMetricsTest.kt \
  app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkServiceTest.kt
git commit -m "feat: add debrid benchmark transport runtime"
```

### Task 4: Integrate benchmark state into the Debrid settings screen

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt`

- [ ] **Step 1: Write the failing settings ViewModel/UI tests**

```kotlin
@Test
fun `connected premiumize row exposes latest benchmark summary`() = runTest {
    val viewModel = buildViewModel(
        premiumizeConnected = true,
        latestPremiumizeResult = sampleResult(provider = PREMIUMIZE)
    )

    assertThat(viewModel.uiState.value.premiumizeBenchmarkSummary).contains("sustained")
}

@Test
fun `start benchmark updates row into measuring state`() = runTest {
    val viewModel = buildViewModel(service = fakeRunningService(provider = REAL_DEBRID))
    viewModel.startBenchmark(REAL_DEBRID)

    assertThat(viewModel.uiState.value.realDebridBenchmarkStatus).isEqualTo(MEASURING)
}
```

- [ ] **Step 2: Run the targeted settings tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`
Expected: FAIL because the ViewModel/UI benchmark state is not wired yet.

- [ ] **Step 3: Add row-level benchmark controls and summary state**

```kotlin
data class DebridProviderBenchmarkUi(
    val canRun: Boolean = false,
    val isRunning: Boolean = false,
    val actionLabel: String = "",
    val summaryLabel: String? = null,
    val detailLabel: String? = null
)
```

```kotlin
fun startBenchmark(provider: DebridBenchmarkProvider)
fun cancelBenchmark()
```

- [ ] **Step 4: Run the targeted settings tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`
Expected: PASS

- [ ] **Step 5: Commit the settings integration slice**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt \
  app/src/main/res/values/strings.xml \
  app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt
git commit -m "feat: expose debrid provider benchmark controls"
```

### Task 5: Verify, validate, and document the feature handoff

**Files:**
- Modify: `openspec/changes/add-debrid-provider-benchmark/proposal.md`
- Modify: `openspec/changes/add-debrid-provider-benchmark/tasks.md`
- Modify: `openspec/changes/add-debrid-provider-benchmark/specs/debrid-provider-benchmark/spec.md`

- [ ] **Step 1: Run focused Android verification**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.DebridBenchmarkStoreTest" --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidateResolverTest" --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkMetricsTest" --tests "com.nexio.tv.data.repository.benchmark.DebridBenchmarkServiceTest" --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`
Expected: PASS

- [ ] **Step 2: Validate the OpenSpec change**

Run: `openspec validate add-debrid-provider-benchmark --strict`
Expected: PASS

- [ ] **Step 3: Smoke-check the settings UX manually**

Run:

```bash
./gradlew :app:installDebug
adb shell am start -n com.nexio.tv.debug/com.nexio.tv.MainActivity
```

Expected:
- connected Real-Debrid / Premiumize rows show `Run benchmark`
- starting a benchmark shows live progress and disables parallel runs
- cancelling or backgrounding stops the active benchmark
- completed runs show the latest local summary after returning to the settings screen

- [ ] **Step 4: Commit the verification and plan completion state**

```bash
git add openspec/changes/add-debrid-provider-benchmark/proposal.md \
  openspec/changes/add-debrid-provider-benchmark/tasks.md \
  openspec/changes/add-debrid-provider-benchmark/specs/debrid-provider-benchmark/spec.md
git commit -m "docs: finalize debrid provider benchmark spec"
```
