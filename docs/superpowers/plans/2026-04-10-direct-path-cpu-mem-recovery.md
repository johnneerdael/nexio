# Direct Path CPU/MEM Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore low-overhead direct playback on Android TV devices by isolating and removing avoidable direct-path work, then proving the remaining CPU/MEM cost is decode/render dominated rather than self-inflicted by the playback loader path.

**Architecture:** Keep the PRDS recovery work separate. This plan only touches the direct branch and the tooling needed to isolate it. The direct branch should continue to read cached bytes when they already exist, but it should stop performing synchronous cache write-through on the playback loader path by default. Add a debug-only override so the team can capture the same stream under `default`, `read-only`, and `disabled` direct-cache modes and compare those captures against `~/Scripts/nexio-old`.

**Tech Stack:** Android/Kotlin, Media3, ExoPlayer, OkHttp, DataStore, Jetpack Compose TV, JUnit4, Robolectric, ADB

---

## Evidence Snapshot

- Current direct playback still wraps progressive playback in `CacheDataSource` via `createMediaSource(...)` and `buildVodCacheDataSourceFactory(...)` in `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`.
- That cache factory always installs `CacheDataSink`, so direct playback writes cache misses through the active loader path.
- The direct path does **not** instantiate `ParallelRangeDataSource` when `useParallelConnections=false`, so PRDS is not the root cause of the direct-path CPU spike.
- Warm-ahead is already suppressed on the direct path because `currentProgressiveIsEligibleForWarmAhead` is now gated by `useParallelConnections`; the existing regression test covers that.
- Compared with `~/Scripts/nexio-old`, the current code adds playback-trace session/header work, stable cache keying, and cache event listeners. Those are real current-vs-old deltas, but the strongest verified direct-path loader cost is still cache-miss write-through.
- The likely remaining non-network cost is decode/render/graphics pressure. The plan therefore adds a capture matrix so direct cache changes can be validated separately from surface/decode pressure.

## File Map

### Production files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - extract a direct-path cache attach policy
  - make direct playback cache read-only on misses by default
  - keep PRDS and explicit warm-ahead writes on the writable path
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - read a debug override and apply it to `PlayerMediaSourceFactory` before `createMediaSource(...)`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt`
  - persist a debug-only direct-path cache override
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt`
  - surface the override into debug UI state and events
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt`
  - expose a debug-only selector that cycles `Default`, `Read only`, `Disabled`, `Read/write`
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md`
  - document the direct-path capture matrix for current repo vs `nexio-old`

### Test files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt`
  - pin direct-path cache policy and keep warm-ahead off
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - pin the actual cache factory write-sink behavior for read-only vs writable modes
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModelTest.kt`
  - pin the debug override cycle and persistence wiring

## Guardrails

- Do not change PRDS behavior in this plan.
- Do not remove VOD cache as a feature for the optimized path.
- Do not let direct playback start warm-ahead again.
- Do not hide decode/render problems behind transport changes; keep the field capture matrix explicit so transport and render costs stay separable.
- Keep the debug override debug-only; it is for isolation and capture, not a user-facing playback preference.

---

### Task 1: Extract And Pin Direct-Path Cache Attach Policy

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt`

- [ ] **Step 1: Write the failing policy tests**

```kotlin
@Test
fun `direct path resolves read only cache attach mode`() {
    val mode = PlayerMediaSourceFactory.resolveVodCacheAttachMode(
        useVodCache = true,
        useParallelConnections = false,
        isHls = false,
        isDash = false
    )

    assertEquals(PlayerMediaSourceFactory.VodCacheAttachMode.READ_ONLY, mode)
}

@Test
fun `parallel progressive path resolves writable cache attach mode`() {
    val mode = PlayerMediaSourceFactory.resolveVodCacheAttachMode(
        useVodCache = true,
        useParallelConnections = true,
        isHls = false,
        isDash = false
    )

    assertEquals(PlayerMediaSourceFactory.VodCacheAttachMode.READ_WRITE, mode)
}
```

- [ ] **Step 2: Run the focused regression test class to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest"`

Expected before implementation: FAIL because the direct branch has no explicit attach-mode distinction yet.

- [ ] **Step 3: Extract an explicit cache attach policy in `PlayerMediaSourceFactory`**

```kotlin
internal enum class VodCacheAttachMode {
    DISABLED,
    READ_ONLY,
    READ_WRITE,
}

internal companion object {
    internal fun resolveVodCacheAttachMode(
        useVodCache: Boolean,
        useParallelConnections: Boolean,
        isHls: Boolean,
        isDash: Boolean
    ): VodCacheAttachMode = when {
        !useVodCache || isHls || isDash -> VodCacheAttachMode.DISABLED
        useParallelConnections -> VodCacheAttachMode.READ_WRITE
        else -> VodCacheAttachMode.READ_ONLY
    }
}
```

- [ ] **Step 4: Use the new attach mode at the `createMediaSource(...)` branch point**

```kotlin
val cacheAttachMode = resolveVodCacheAttachMode(
    useVodCache = useVodCache && !isVodCacheDisabled,
    useParallelConnections = useParallelConnections,
    isHls = isHls,
    isDash = isDash
)

currentProgressiveIsEligibleForWarmAhead =
    cacheAttachMode == VodCacheAttachMode.READ_WRITE && useParallelConnections
```

- [ ] **Step 5: Re-run the focused direct-path tests**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt
git commit -m "refactor: extract direct path cache attach policy"
```

---

### Task 2: Stop Direct Playback Cache Write-Through On The Loader Path

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt`

- [ ] **Step 1: Write the failing cache-factory behavior tests**

```kotlin
@Test
fun `read only direct cache factory omits cache write sink`() {
    val cacheFactory = buildCacheFactoryForTest(
        attachMode = PlayerMediaSourceFactory.VodCacheAttachMode.READ_ONLY,
        blockOnCache = false
    )

    assertNull(readCacheWriteDataSinkFactory(cacheFactory))
}

@Test
fun `writable cache factory keeps cache write sink`() {
    val cacheFactory = buildCacheFactoryForTest(
        attachMode = PlayerMediaSourceFactory.VodCacheAttachMode.READ_WRITE,
        blockOnCache = false
    )

    assertNotNull(readCacheWriteDataSinkFactory(cacheFactory))
}

@Test
fun `warm ahead path stays writable even when direct path is read only`() {
    val cacheFactory = buildCacheFactoryForTest(
        attachMode = PlayerMediaSourceFactory.VodCacheAttachMode.READ_ONLY,
        blockOnCache = true
    )

    assertNotNull(readCacheWriteDataSinkFactory(cacheFactory))
}
```

- [ ] **Step 2: Run the focused factory tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest"`

Expected before implementation: FAIL because `buildVodCacheDataSourceFactory(...)` always installs `CacheDataSink`.

- [ ] **Step 3: Add an explicit write control to `buildVodCacheDataSourceFactory(...)`**

```kotlin
private fun buildVodCacheDataSourceFactory(
    upstreamFactory: DataSource.Factory,
    cache: SimpleCache,
    allowWrites: Boolean,
    blockOnCache: Boolean = false
): DataSource.Factory {
    val dataSinkFactory = CacheDataSink.Factory()
        .setCache(cache)
        .setFragmentSize(2L * 1024L * 1024L)

    var flags = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
    if (blockOnCache) {
        flags = flags or CacheDataSource.FLAG_BLOCK_ON_CACHE
    }

    return CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory(stableCacheKeyFactory)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .apply {
            if (allowWrites) {
                setCacheWriteDataSinkFactory(dataSinkFactory)
            }
        }
        .setEventListener(
            PlaybackTraceCacheEventListener(if (blockOnCache) "warm_ahead" else "progressive")
        )
        .setFlags(flags)
}
```

- [ ] **Step 4: Use read-only mode for direct playback and keep explicit writes for warm-ahead**

```kotlin
val progressiveFactory = if (cache != null) {
    buildVodCacheDataSourceFactory(
        upstreamFactory = progressiveUpstreamFactory,
        cache = cache,
        allowWrites = cacheAttachMode == VodCacheAttachMode.READ_WRITE
    )
} else {
    progressiveUpstreamFactory
}

val prefetchFactory = buildVodCacheDataSourceFactory(
    upstreamFactory = upstreamFactory,
    cache = cache,
    allowWrites = true,
    blockOnCache = true
)
```

- [ ] **Step 5: Re-run the focused factory tests**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt
git commit -m "fix: make direct playback cache read only on misses"
```

---

### Task 3: Add A Debug Override And Capture Matrix For Direct-Path Isolation

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModelTest.kt`

- [ ] **Step 1: Write the failing debug-setting tests**

```kotlin
@Test
fun `advance direct path cache override cycles through all modes`() = runTest {
    val dataStore = FakeDebugSettingsDataStore(
        directPathCacheModeOverride = DirectPathCacheModeOverride.DEFAULT
    )
    val viewModel = buildDebugSettingsViewModel(dataStore = dataStore)

    viewModel.onEvent(DebugSettingsEvent.AdvanceDirectPathCacheModeOverride)
    advanceUntilIdle()
    assertEquals(
        DirectPathCacheModeOverride.READ_ONLY,
        viewModel.uiState.value.directPathCacheModeOverride
    )

    viewModel.onEvent(DebugSettingsEvent.AdvanceDirectPathCacheModeOverride)
    advanceUntilIdle()
    assertEquals(
        DirectPathCacheModeOverride.DISABLED,
        viewModel.uiState.value.directPathCacheModeOverride
    )
}
```

- [ ] **Step 2: Run the new settings test class to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DebugSettingsViewModelTest"`

Expected before implementation: FAIL because no direct-path cache override exists yet.

- [ ] **Step 3: Add a persisted debug override in `DebugSettingsDataStore`**

```kotlin
enum class DirectPathCacheModeOverride {
    DEFAULT,
    READ_ONLY,
    DISABLED,
    READ_WRITE,
}

private val directPathCacheModeOverrideKey =
    stringPreferencesKey("direct_path_cache_mode_override")

val directPathCacheModeOverride: Flow<DirectPathCacheModeOverride> =
    dataStore.data.map { prefs ->
        prefs[directPathCacheModeOverrideKey]
            ?.let(DirectPathCacheModeOverride::valueOf)
            ?: DirectPathCacheModeOverride.DEFAULT
    }

suspend fun setDirectPathCacheModeOverride(value: DirectPathCacheModeOverride) {
    dataStore.edit { prefs ->
        prefs[directPathCacheModeOverrideKey] = value.name
    }
}
```

- [ ] **Step 4: Surface the override through `DebugSettingsViewModel` and screen UI**

```kotlin
data class DebugSettingsUiState(
    // ...
    val directPathCacheModeOverride: DirectPathCacheModeOverride =
        DirectPathCacheModeOverride.DEFAULT,
)

sealed class DebugSettingsEvent {
    data object AdvanceDirectPathCacheModeOverride : DebugSettingsEvent()
}

viewModelScope.launch {
    dataStore.directPathCacheModeOverride.collectLatest { mode ->
        _uiState.update { it.copy(directPathCacheModeOverride = mode) }
    }
}

DebugActionCard(
    title = "Direct path cache mode",
    subtitle = uiState.directPathCacheModeOverride.name,
    onClick = { viewModel.onEvent(DebugSettingsEvent.AdvanceDirectPathCacheModeOverride) }
)
```

- [ ] **Step 5: Apply the override before player initialization**

```kotlin
val directPathCacheOverride = debugSettingsDataStore.directPathCacheModeOverride.first()
mediaSourceFactory.directPathCacheModeOverride = directPathCacheOverride
```

- [ ] **Step 6: Document the capture matrix in `collecting-traces.md`**

```md
### Direct-path isolation matrix

Run the same stream under these four cases:

1. Current repo, tracer OFF, direct-path cache override = `DEFAULT`
2. Current repo, tracer OFF, direct-path cache override = `READ_ONLY`
3. Current repo, tracer ON, direct-path cache override = `READ_ONLY`
4. `~/Scripts/nexio-old`, tracer OFF, equivalent direct playback

For each case, capture:

- `scripts/capture_perf_logs.sh`
- latest playback trace JSONL if tracer was ON
- `dumpsys meminfo com.nexio.tv`
- `dumpsys SurfaceFlinger`
```

- [ ] **Step 7: Re-run the settings tests and the existing direct-path tests**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DebugSettingsViewModelTest" --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest"`

Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
git add docs/instrumentation/collecting-traces.md
git add app/src/test/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModelTest.kt
git commit -m "feat: add direct path cache isolation controls"
```

---

## Field Verification Matrix

Run these captures after Task 3 lands. Keep the asset, playback backend, and device constant.

1. Current repo, `DEFAULT` direct cache mode, tracer OFF

```bash
cd /Users/jneerdael/Scripts/nexio
adb shell am broadcast -a com.nexio.tv.action.PLAYBACK_TRACE_DISABLE -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
./scripts/capture_perf_logs.sh
```

2. Current repo, `READ_ONLY` direct cache mode, tracer OFF

```bash
cd /Users/jneerdael/Scripts/nexio
./scripts/capture_perf_logs.sh
```

3. Current repo, `READ_ONLY` direct cache mode, tracer ON

```bash
cd /Users/jneerdael/Scripts/nexio
adb shell am broadcast -a com.nexio.tv.action.PLAYBACK_TRACE_ENABLE -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
./scripts/capture_perf_logs.sh
adb shell am broadcast -a com.nexio.tv.action.PLAYBACK_TRACE_COPY_LATEST_SESSION -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
```

4. Old baseline repo, equivalent direct playback, tracer OFF

```bash
cd /Users/jneerdael/Scripts/nexio-old
./scripts/capture_perf_logs.sh
```

Compare these metrics across all four captures:

- `top`: app CPU during steady playback
- `dumpsys meminfo`: total PSS, Java heap, graphics, swap
- `playback-trace`: `cache_write_latency_ms` count and p99 when tracer is ON
- `SurfaceFlinger` / `gfxinfo`: frame pacing and buffer pressure

## Acceptance Criteria

- Direct playback no longer installs a cache write sink on the active loader path unless the debug override explicitly forces `READ_WRITE`.
- Direct playback still reads already-cached bytes when they exist.
- Direct playback still never starts warm-ahead when `useParallelConnections=false`.
- The `READ_ONLY` direct-path capture materially reduces steady-playback CPU and cache-write trace activity versus `DEFAULT` on the same asset.
- If `READ_ONLY` materially reduces CPU while graphics and swap stay high, the next plan should target decode/render/surface pressure rather than transport/cache.
- If `READ_ONLY` does **not** materially reduce CPU, the next plan should focus on render/graphics plus direct-path diagnostics overhead instead of more transport work.
