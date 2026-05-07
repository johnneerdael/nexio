# Autoplay Manual Bandwidth Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an autoplay bandwidth mode that lets users set a fixed max bitrate cap so autoplay can score debrid streams without requiring completed benchmark data.

**Architecture:** Keep benchmark-based autoplay as the default `AUTO` path and add a separate `MANUAL` path gated by persisted player settings. Manual scoring reuses the same stream parsing, content scoring, rejection reasons, and ranking bucket logic, but it bypasses benchmark lookup and assigns zero transport score with the manual cap as the safe budget.

**Tech Stack:** Kotlin, Android DataStore Preferences, Jetpack Compose TV settings UI, Hilt ViewModels, Robolectric/JUnit JVM tests, Gradle Android tasks.

---

## File Map

- Modify `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - Add `AutoplayBandwidthMode`.
  - Persist `autoplayBandwidthMode` and `manualBitrateLimitMbps`.
  - Add setters with safe range normalization.
- Modify `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`
  - Cover defaults, enum persistence, and 5..200 Mbps coercion.
- Modify `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt`
  - Add `scoreWithManualCap`.
  - Add a private `evaluateStreamWithManualCap` helper.
  - Add a private `manualTransportBreakdown` helper.
- Modify `app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareScoringHarnessTest.kt`
  - Cover manual scoring without benchmarks, cap rejection, and non-debrid rejection.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
  - Import `AutoplayBandwidthMode`.
  - Thread `isManualBandwidthMode`.
  - Branch autoplay playback and shadow replay scoring to `scoreWithManualCap`.
- Modify `app/src/test/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModelDeterministicAutoplayTest.kt`
  - Add a focused coordinator test proving manual replay emits without benchmark sessions.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt`
  - Add mode selector item, manual cap slider, and `AutoplayBandwidthModeDialog`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - Add callback parameters and route them through the stream selection section and dialogs host.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - Add dialog state and wire ViewModel calls.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
  - Add delegate setters.
- Modify `app/src/main/res/values/strings.xml`
  - Add labels and descriptions for bandwidth mode and manual bitrate cap.

## Task 1: Persist Manual Bandwidth Settings

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`

- [ ] **Step 1: Write failing DataStore tests**

Add these imports to `PlayerSettingsDataStoreTest.kt`:

```kotlin
import org.junit.Assert.assertTrue
```

Add these tests inside `PlayerSettingsDataStoreTest`:

```kotlin
    @Test
    fun `autoplay bandwidth mode defaults to auto with 20 mbps manual cap`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        val settings = dataStore.playerSettings.first()

        assertEquals(AutoplayBandwidthMode.AUTO, settings.autoplayBandwidthMode)
        assertEquals(20.0, settings.manualBitrateLimitMbps, 0.0)
    }

    @Test
    fun `manual bitrate limit is coerced to supported range`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setManualBitrateLimitMbps(2.0)
        assertEquals(5.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)

        dataStore.setManualBitrateLimitMbps(205.0)
        assertEquals(200.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)

        dataStore.setManualBitrateLimitMbps(Double.NaN)
        assertEquals(20.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)
    }

    @Test
    fun `autoplay bandwidth mode persists manual selection`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setAutoplayBandwidthMode(AutoplayBandwidthMode.MANUAL)

        assertEquals(AutoplayBandwidthMode.MANUAL, dataStore.playerSettings.first().autoplayBandwidthMode)
        assertTrue(dataStore.playerSettings.first().manualBitrateLimitMbps.isFinite())
    }
```

- [ ] **Step 2: Run the targeted failing tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.local.PlayerSettingsDataStoreTest
```

Expected: FAIL because `AutoplayBandwidthMode`, `autoplayBandwidthMode`, `manualBitrateLimitMbps`, `setAutoplayBandwidthMode`, and `setManualBitrateLimitMbps` do not exist.

- [ ] **Step 3: Add enum and data class fields**

In `PlayerSettingsDataStore.kt`, add the fields immediately after `autoplayMaxBitrateMbps`:

```kotlin
    val autoplayMaxBitrateEnabled: Boolean = true,
    val autoplayMaxBitrateMbps: Double? = null,
    val autoplayBandwidthMode: AutoplayBandwidthMode = AutoplayBandwidthMode.AUTO,
    val manualBitrateLimitMbps: Double = 20.0,
    val nextEpisodeThresholdMode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
```

Add the enum immediately after `StreamAutoPlayMode`:

```kotlin
enum class StreamAutoPlayMode {
    MANUAL,
    FIRST_STREAM,
    REGEX_MATCH
}

enum class AutoplayBandwidthMode {
    AUTO,
    MANUAL
}
```

- [ ] **Step 4: Add DataStore keys and Flow mapping**

Add keys immediately after `autoplayMaxBitrateMbpsKey`:

```kotlin
    private val autoplayMaxBitrateEnabledKey = booleanPreferencesKey("autoplay_max_bitrate_enabled")
    private val autoplayMaxBitrateMbpsKey = doublePreferencesKey("autoplay_max_bitrate_mbps")
    private val autoplayBandwidthModeKey = stringPreferencesKey("autoplay_bandwidth_mode")
    private val manualBitrateLimitMbpsKey = doublePreferencesKey("manual_bitrate_limit_mbps")
```

Update the `PlayerSettings(...)` mapping immediately after `autoplayMaxBitrateMbps`:

```kotlin
                autoplayMaxBitrateEnabled = prefs[autoplayMaxBitrateEnabledKey] ?: true,
                autoplayMaxBitrateMbps = prefs[autoplayMaxBitrateMbpsKey]?.takeIf { it.isFinite() && it > 0.0 },
                autoplayBandwidthMode = prefs[autoplayBandwidthModeKey]?.let {
                    runCatching { AutoplayBandwidthMode.valueOf(it) }.getOrDefault(AutoplayBandwidthMode.AUTO)
                } ?: AutoplayBandwidthMode.AUTO,
                manualBitrateLimitMbps = normalizeManualBitrateLimit(
                    prefs[manualBitrateLimitMbpsKey] ?: 20.0
                ),
                nextEpisodeThresholdMode = prefs[nextEpisodeThresholdModeKey]?.let {
```

- [ ] **Step 5: Add setters and normalization helper**

Add these functions after `setAutoplayMaxBitrate`:

```kotlin
    suspend fun setAutoplayBandwidthMode(mode: AutoplayBandwidthMode) {
        store().edit { prefs ->
            prefs[autoplayBandwidthModeKey] = mode.name
        }
    }

    suspend fun setManualBitrateLimitMbps(mbps: Double) {
        store().edit { prefs ->
            prefs[manualBitrateLimitMbpsKey] = normalizeManualBitrateLimit(mbps)
        }
    }

    private fun normalizeManualBitrateLimit(mbps: Double): Double {
        if (!mbps.isFinite()) return 20.0
        return mbps.coerceIn(5.0, 200.0)
    }
```

- [ ] **Step 6: Run the targeted tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.local.PlayerSettingsDataStoreTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt
git commit -m "feat: persist autoplay bandwidth mode"
```

## Task 2: Add Manual Cap Scoring

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareScoringHarnessTest.kt`

- [ ] **Step 1: Write failing scorer tests**

Add these tests inside `BenchmarkAwareScoringHarnessTest`:

```kotlin
    @Test
    fun `manual cap scoring selects stream without benchmark sessions`() {
        val scenario = sampleDataset().scenarios.single()

        val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
            request = scenario.toShadowRequestContext(),
            streams = scenario.toStreamCards(),
            manualBitrateCap = 80.0
        )

        assertNotNull(event.selected)
        assertTrue(event.benchmarksUsed.isEmpty())
        assertEquals(0, event.selected?.transportFitScore)
        assertEquals(80.0, event.selected?.safeBudgetMbps ?: -1.0, 0.0)
        assertEquals(0, event.selected?.breakdown?.transport?.ratioScore)
        assertEquals(0, event.selected?.breakdown?.transport?.startupScore)
        assertEquals(0, event.selected?.breakdown?.transport?.seekScore)
        assertEquals(0, event.selected?.breakdown?.transport?.stabilityScore)
        assertEquals(null, event.selectedNonDolbyVisionFallback)
    }

    @Test
    fun `manual cap scoring rejects streams over fixed bitrate cap without missing benchmark`() {
        val scenario = sampleDataset().scenarios.single()

        val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
            request = scenario.toShadowRequestContext(),
            streams = scenario.toStreamCards(),
            manualBitrateCap = 20.0
        )

        assertEquals(null, event.selected)
        assertTrue(event.rejected.isNotEmpty())
        assertTrue(event.rejected.all { rejected ->
            rejected.reasons == listOf(ShadowRejectReason.EXCEEDS_AUTOPLAY_CAP)
        })
    }

    @Test
    fun `manual cap scoring rejects non debrid streams without benchmark requirement`() {
        val scenario = sampleDataset().scenarios.single()
        val stream = scenario.streams.first().copy(providerId = "HTTP")

        val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
            request = scenario.toShadowRequestContext(),
            streams = listOf(stream.toStreamCardModel()),
            manualBitrateCap = 80.0
        )

        assertEquals(null, event.selected)
        assertEquals(listOf(ShadowRejectReason.NOT_DEBRID_WRAPPED), event.rejected.single().reasons)
    }
```

- [ ] **Step 2: Run the targeted failing tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.benchmark.BenchmarkAwareScoringHarnessTest
```

Expected: FAIL because `scoreWithManualCap` does not exist.

- [ ] **Step 3: Add `scoreWithManualCap`**

Add this public method in `BenchmarkAwareStreamScorer` immediately after `score(...)`:

```kotlin
    fun scoreWithManualCap(
        request: ShadowRequestContext,
        streams: List<StreamCardModel>,
        manualBitrateCap: Double,
        elapsedMs: Long? = null
    ): ShadowAutoPlayDecisionEvent {
        val safeManualCap = manualBitrateCap.takeIf { it.isFinite() && it > 0.0 } ?: 20.0
        val winners = mutableListOf<ShadowStreamDecision>()
        val rejected = mutableListOf<ShadowRejectedStream>()

        streams.forEach { item ->
            val provider = item.toBenchmarkProvider()
            val streamKey = item.shadowStreamKey()
            val parsedFacts = item.shadowParsedFacts(request)
            if (provider == null) {
                rejected += ShadowRejectedStream(
                    streamKey = streamKey,
                    parsed = parsedFacts,
                    reasons = listOf(ShadowRejectReason.NOT_DEBRID_WRAPPED)
                )
                return@forEach
            }

            evaluateStreamWithManualCap(
                item = item,
                provider = provider,
                request = request,
                manualBitrateCap = safeManualCap
            ).fold(
                onSuccess = { winners += it },
                onFailure = { reasons ->
                    rejected += ShadowRejectedStream(
                        streamKey = streamKey,
                        parsed = parsedFacts,
                        provider = provider,
                        reasons = reasons
                    )
                }
            )
        }

        val ranked = winners.sortedWith(baseDecisionComparator())
        val finalRanked = applyViableBitrateBucket(ranked)

        return ShadowAutoPlayDecisionEvent(
            eventVersion = 1,
            eventType = "shadow_autoplay_decision",
            request = request,
            benchmarksUsed = emptyList(),
            winners = finalRanked,
            rejected = rejected.sortedBy { it.streamKey },
            selected = finalRanked.firstOrNull(),
            selectedNonDolbyVisionFallback = null,
            timingsMs = elapsedMs
        )
    }
```

- [ ] **Step 4: Add manual stream evaluation helper**

Add this private method immediately after `evaluateStream(...)`:

```kotlin
    private fun evaluateStreamWithManualCap(
        item: StreamCardModel,
        provider: DebridBenchmarkProvider,
        request: ShadowRequestContext,
        manualBitrateCap: Double
    ): EitherSuccessOrReject<ShadowStreamDecision> {
        val parsed = item.parsed
        val sizeBytes = parsed.sizeBytes
        if (sizeBytes == null || sizeBytes <= 0L) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.MISSING_SIZE)
        }

        val runtimeMs = item.shadowRuntimeMs(request)
        val hasRuntime = runtimeMs != null && runtimeMs > 0L
        val averageBitrateMbps = runtimeMs?.takeIf { it > 0L }?.let {
            calculateAverageBitrateMbps(sizeBytes, it)
        } ?: return EitherSuccessOrReject.reject(ShadowRejectReason.MISSING_RUNTIME)

        if (averageBitrateMbps > manualBitrateCap) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.EXCEEDS_AUTOPLAY_CAP)
        }

        val resolutionTier = resolveResolutionTier(parsed.resolution)
        val releaseType = classifyReleaseType(parsed, averageBitrateMbps)
        var codecTier = resolveVideoCodecTier(parsed.encode, device = null)
        if (codecTier == ShadowVideoCodecTier.OTHER &&
            resolutionTier == ShadowResolutionTier.UHD_2160 &&
            releaseType == ShadowReleaseType.REMUX
        ) {
            codecTier = ShadowVideoCodecTier.HEVC_HW
        }
        if (codecTier == ShadowVideoCodecTier.UNSUPPORTED) {
            return EitherSuccessOrReject.reject(ShadowRejectReason.UNSUPPORTED_CODEC)
        }

        val contentBreakdown = buildContentScoreBreakdown(
            parsed = parsed,
            averageBitrateMbps = averageBitrateMbps,
            resolutionTier = resolutionTier,
            releaseType = releaseType,
            codecTier = codecTier,
            device = null,
            runtimeKnown = hasRuntime
        )
        val contentScore = contentBreakdown.total()
        val transportBreakdown = manualTransportBreakdown(
            provider = provider,
            safeBudgetMbps = manualBitrateCap,
            requiredMbps = averageBitrateMbps
        )

        return EitherSuccessOrReject.success(
            ShadowStreamDecision(
                streamKey = item.shadowStreamKey(),
                parsed = item.shadowParsedFacts(request),
                provider = provider,
                transport = DebridBenchmarkTransportMode.DIRECT,
                finalScore = contentScore,
                contentQualityScore = contentScore,
                transportFitScore = 0,
                suitabilityRatio = if (averageBitrateMbps <= 0.0) 0.0 else manualBitrateCap / averageBitrateMbps,
                requiredMbps = averageBitrateMbps,
                safeBudgetMbps = manualBitrateCap,
                resolution = parsed.resolution,
                hdrTags = parsed.visualTags.filter { it in HDR_VISUAL_TAGS },
                audioTags = parsed.audioTags,
                breakdown = ShadowDecisionBreakdown(
                    averageBitrateMbps = averageBitrateMbps,
                    releaseType = releaseType.wireKey,
                    lowQuality4k = contentBreakdown.lowQuality4kPenalty < 0,
                    realismRatio = contentBreakdown.realismRatio,
                    content = contentBreakdown,
                    transport = transportBreakdown
                )
            )
        )
    }
```

- [ ] **Step 5: Add manual transport breakdown helper**

Add this private top-level function near `ShadowTransportOption.toBreakdown(...)` or before `selectNonDolbyVisionFallback(...)`:

```kotlin
private fun manualTransportBreakdown(
    provider: DebridBenchmarkProvider,
    safeBudgetMbps: Double,
    requiredMbps: Double
): ShadowTransportScoreBreakdown {
    return ShadowTransportScoreBreakdown(
        provider = provider,
        transport = DebridBenchmarkTransportMode.DIRECT,
        safeBudgetMbps = safeBudgetMbps,
        requiredMbps = requiredMbps,
        suitabilityRatio = if (requiredMbps <= 0.0) 0.0 else safeBudgetMbps / requiredMbps,
        ratioScore = 0,
        startupScore = 0,
        seekScore = 0,
        stabilityScore = 0,
        startupTtfbMs = null,
        seekTtfbP95Ms = null,
        seekFailRate = null
    )
}
```

- [ ] **Step 6: Run the targeted tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.benchmark.BenchmarkAwareScoringHarnessTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareScoringHarnessTest.kt
git commit -m "feat: score autoplay streams with manual cap"
```

## Task 3: Thread Manual Mode Through Stream Autoplay

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModelDeterministicAutoplayTest.kt`

- [ ] **Step 1: Write the failing replay coordinator test**

Add this import to `StreamScreenViewModelDeterministicAutoplayTest.kt`:

```kotlin
import com.nexio.tv.data.repository.benchmark.BenchmarkAwareScoringScenarioStream
import com.nexio.tv.data.repository.benchmark.ShadowRejectReason
import org.junit.Assert.assertNotNull
```

Add this test inside `StreamScreenViewModelDeterministicAutoplayTest`:

```kotlin
    @Test
    fun `shadow autoplay replay coordinator emits manual cap event without benchmarks`() {
        val request = ShadowRequestContext(
            requestId = "manual-cap",
            videoId = "tt123",
            contentType = "movie",
            title = "Example",
            season = null,
            episode = null,
            runtimeMinutes = 120
        )
        val coordinator = ShadowAutoPlayReplayCoordinator(BenchmarkAwareStreamScorer())

        coordinator.updateCandidates(
            request = request,
            organizedStreams = listOf(
                BenchmarkAwareScoringScenarioStream(
                    streamKey = "manual-cap-1080p",
                    providerId = "RD",
                    resolution = "1080p",
                    quality = "WEB-DL",
                    encode = "H264",
                    sizeBytes = 12L * 1024L * 1024L * 1024L,
                    durationMs = 120L * 60_000L,
                    visualTags = emptyList(),
                    audioTags = listOf("DD+")
                ).toStreamCardModel()
            ),
            activeTransportMode = null,
            autoplayMaxBitrateMbps = 20.0,
            isManualBandwidthMode = true,
            isFinalPass = true,
            allowEarlyFinishTerminal = false
        )

        val event = coordinator.buildEventIfReady(
            benchmarkSessions = emptyMap(),
            timingsMs = 7L
        )

        assertNotNull(event)
        assertEquals("manual-cap-1080p", event?.selected?.streamKey)
        assertEquals(emptyList<ShadowRejectReason>(), event?.rejected?.flatMap { it.reasons })
        assertEquals(7L, event?.timingsMs)
    }
```

- [ ] **Step 2: Run the targeted failing test**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest
```

Expected: FAIL because `isManualBandwidthMode` is not accepted by `updateCandidates`, and `AutoplayBandwidthMode` is not wired into `StreamScreenViewModel`.

- [ ] **Step 3: Import bandwidth mode**

Add this import to `StreamScreenViewModel.kt`:

```kotlin
import com.nexio.tv.data.local.AutoplayBandwidthMode
```

- [ ] **Step 4: Thread manual flag through autoplay call sites**

Update the deterministic autoplay call:

```kotlin
                            buildDeterministicAutoPlayPlaybackInfo(
                                request = buildShadowRequestContext(requestId),
                                organizedStreams = organizedStreams.items,
                                activeTransportMode = playerSettings.toShadowActiveTransportMode(),
                                autoplayMaxBitrateMbps = playerSettings.autoplayMaxBitrateForScoring(),
                                isManualBandwidthMode = playerSettings.isManualBandwidthMode(),
                                isFinalPass = isFinalPass
                            )
```

Update the benchmark-aware autoplay call:

```kotlin
                            buildBenchmarkAwareAutoPlayPlaybackInfo(
                                request = buildShadowRequestContext(requestId),
                                organizedStreams = organizedStreams.items,
                                autoPlayCandidates = autoPlayCandidates,
                                activeTransportMode = playerSettings.toShadowActiveTransportMode(),
                                autoplayMaxBitrateMbps = playerSettings.autoplayMaxBitrateForScoring(),
                                isManualBandwidthMode = playerSettings.isManualBandwidthMode()
                            )
```

Update the shadow replay call in `applyOrganizedPayload`:

```kotlin
                    updateShadowAutoPlayDecision(
                        request = buildShadowRequestContext(requestId),
                        organizedStreams = organizedResult.organizedStreams.items,
                        activeTransportMode = playerSettings.toShadowActiveTransportMode(),
                        autoplayMaxBitrateMbps = playerSettings.autoplayMaxBitrateForScoring(),
                        isManualBandwidthMode = playerSettings.isManualBandwidthMode(),
                        isFinalPass = organizedResult.shadowDecisionFinalPass,
                        allowEarlyFinishTerminal = organizedResult.shadowDecisionAllowEarlyFinish
                    )
```

- [ ] **Step 5: Branch playback info builders**

Change `buildBenchmarkAwareAutoPlayPlaybackInfo` signature and body header to:

```kotlin
    private suspend fun buildBenchmarkAwareAutoPlayPlaybackInfo(
        request: ShadowRequestContext,
        organizedStreams: List<StreamCardModel>,
        autoPlayCandidates: List<Stream>,
        activeTransportMode: DebridBenchmarkTransportMode?,
        autoplayMaxBitrateMbps: Double?,
        isManualBandwidthMode: Boolean
    ): StreamPlaybackInfo? {
        if (autoPlayCandidates.isEmpty()) return null
        val candidateItems = organizedStreams.filter { item -> item.stream in autoPlayCandidates }
        if (candidateItems.isEmpty()) return null

        val event = if (isManualBandwidthMode) {
            benchmarkAwareStreamScorer.scoreWithManualCap(
                request = request,
                streams = candidateItems,
                manualBitrateCap = autoplayMaxBitrateMbps ?: return null
            )
        } else {
            val benchmarkSessions = latestBenchmarkSessions()
            if (benchmarkSessions.isEmpty()) return null
            benchmarkAwareStreamScorer.score(
                request = request,
                streams = candidateItems,
                benchmarkSessions = benchmarkSessions,
                activeTransportMode = activeTransportMode,
                autoplayMaxBitrateMbps = autoplayMaxBitrateMbps
            )
        }
```

Leave the existing selected key, selected item, fallback item, and `buildStreamPlaybackInfo(...)` code after this block unchanged.

Change `buildDeterministicAutoPlayPlaybackInfo` signature and scorer block to:

```kotlin
    private suspend fun buildDeterministicAutoPlayPlaybackInfo(
        request: ShadowRequestContext,
        organizedStreams: List<StreamCardModel>,
        activeTransportMode: DebridBenchmarkTransportMode?,
        autoplayMaxBitrateMbps: Double?,
        isManualBandwidthMode: Boolean,
        isFinalPass: Boolean
    ): StreamPlaybackInfo? {
        if (organizedStreams.isEmpty()) return null
        val eligibleStreams = applyDeterministicOriginalLanguageGuard(
            originalLanguage = originalLanguage,
            streams = organizedStreams
        )
        if (eligibleStreams.isEmpty()) return null

        val event = if (isManualBandwidthMode) {
            benchmarkAwareStreamScorer.scoreWithManualCap(
                request = request,
                streams = eligibleStreams,
                manualBitrateCap = autoplayMaxBitrateMbps ?: return null
            )
        } else {
            val benchmarkSessions = latestBenchmarkSessions()
            if (benchmarkSessions.isEmpty()) return null
            benchmarkAwareStreamScorer.score(
                request = request,
                streams = eligibleStreams,
                benchmarkSessions = benchmarkSessions,
                activeTransportMode = activeTransportMode,
                autoplayMaxBitrateMbps = autoplayMaxBitrateMbps
            )
        }
```

Leave the existing selection and early-finish logic after this block unchanged.

- [ ] **Step 6: Thread manual flag through shadow replay**

Change `updateShadowAutoPlayDecision` signature and coordinator call:

```kotlin
    private suspend fun updateShadowAutoPlayDecision(
        request: ShadowRequestContext,
        organizedStreams: List<com.nexio.tv.core.stream.StreamCardModel>,
        activeTransportMode: DebridBenchmarkTransportMode?,
        autoplayMaxBitrateMbps: Double?,
        isManualBandwidthMode: Boolean,
        isFinalPass: Boolean,
        allowEarlyFinishTerminal: Boolean
    ) {
        shadowAutoPlayReplayCoordinator.updateCandidates(
            request = request,
            organizedStreams = organizedStreams,
            activeTransportMode = activeTransportMode,
            autoplayMaxBitrateMbps = autoplayMaxBitrateMbps,
            isManualBandwidthMode = isManualBandwidthMode,
            isFinalPass = isFinalPass,
            allowEarlyFinishTerminal = allowEarlyFinishTerminal
        )
        emitShadowAutoPlayDecisionIfReady()
    }
```

Change `ShadowAutoPlayReplayCoordinator` fields and reset logic:

```kotlin
    private var latestAutoplayMaxBitrateMbps: Double? = null
    private var latestManualBandwidthMode: Boolean = false
    private var finalPassObserved: Boolean = false
```

In the request-id reset block:

```kotlin
            latestActiveTransportMode = null
            latestAutoplayMaxBitrateMbps = null
            latestManualBandwidthMode = false
            finalPassObserved = false
```

Change `updateCandidates` signature and assignment:

```kotlin
        autoplayMaxBitrateMbps: Double?,
        isManualBandwidthMode: Boolean,
        isFinalPass: Boolean,
        allowEarlyFinishTerminal: Boolean
```

```kotlin
        latestActiveTransportMode = activeTransportMode
        latestAutoplayMaxBitrateMbps = autoplayMaxBitrateMbps
        latestManualBandwidthMode = isManualBandwidthMode
        finalPassObserved = finalPassObserved || isFinalPass
```

Change the event creation in `buildEventIfReady`:

```kotlin
        val event = if (latestManualBandwidthMode) {
            scorer.scoreWithManualCap(
                request = request,
                streams = latestStreams,
                manualBitrateCap = latestAutoplayMaxBitrateMbps ?: return null,
                elapsedMs = timingsMs
            )
        } else {
            buildShadowAutoPlayDecisionEvent(
                scorer = scorer,
                request = request,
                organizedStreams = latestStreams,
                benchmarkSessions = benchmarkSessions,
                activeTransportMode = latestActiveTransportMode,
                autoplayMaxBitrateMbps = latestAutoplayMaxBitrateMbps,
                timingsMs = timingsMs
            )
        }
```

In `clear()` add:

```kotlin
        latestManualBandwidthMode = false
```

- [ ] **Step 7: Update settings helper functions**

Replace `autoplayMaxBitrateForScoring` and add `isManualBandwidthMode`:

```kotlin
private fun PlayerSettings.autoplayMaxBitrateForScoring(): Double? {
    return when (autoplayBandwidthMode) {
        AutoplayBandwidthMode.AUTO -> if (autoplayMaxBitrateEnabled) autoplayMaxBitrateMbps else null
        AutoplayBandwidthMode.MANUAL -> manualBitrateLimitMbps
    }
}

private fun PlayerSettings.isManualBandwidthMode(): Boolean {
    return autoplayBandwidthMode == AutoplayBandwidthMode.MANUAL
}
```

- [ ] **Step 8: Run the targeted tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest
```

Expected: PASS.

- [ ] **Step 9: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt app/src/test/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModelDeterministicAutoplayTest.kt
git commit -m "feat: route autoplay manual bandwidth mode"
```

## Task 4: Add Settings UI and ViewModel Wiring

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add ViewModel delegate imports and functions**

Add this import to `PlaybackSettingsViewModel.kt`:

```kotlin
import com.nexio.tv.data.local.AutoplayBandwidthMode
```

Add these functions after `setStreamAutoPlayPreferBingeGroupForNextEpisode`:

```kotlin
    suspend fun setAutoplayBandwidthMode(mode: AutoplayBandwidthMode) {
        playerSettingsDataStore.setAutoplayBandwidthMode(mode)
    }

    suspend fun setManualBitrateLimitMbps(mbps: Double) {
        playerSettingsDataStore.setManualBitrateLimitMbps(mbps)
    }
```

- [ ] **Step 2: Add UI imports**

Add this import to `PlaybackAutoPlaySettings.kt`:

```kotlin
import androidx.compose.material.icons.filled.Wifi
import com.nexio.tv.data.local.AutoplayBandwidthMode
```

Add this import to `PlaybackSettingsSections.kt`:

```kotlin
import com.nexio.tv.data.local.AutoplayBandwidthMode
```

Add this import to `PlaybackSettingsScreen.kt`:

```kotlin
import com.nexio.tv.data.local.AutoplayBandwidthMode
```

- [ ] **Step 3: Update `autoPlaySettingsItems` signature and items**

Change the `autoPlaySettingsItems` signature:

```kotlin
internal fun LazyListScope.autoPlaySettingsItems(
    playerSettings: PlayerSettings,
    onShowAutoplayBandwidthModeDialog: () -> Unit,
    onShowNextEpisodeThresholdModeDialog: () -> Unit,
    onShowReuseLastLinkCacheDialog: () -> Unit,
    onSetManualBitrateLimitMbps: (Double) -> Unit,
    onSetStreamAutoPlayNextEpisodeEnabled: (Boolean) -> Unit,
```

Add these items as the first items in the function body, before `autoplay_reuse_last_link`:

```kotlin
    item(key = "autoplay_bandwidth_mode") {
        val modeSubtitle = when (playerSettings.autoplayBandwidthMode) {
            AutoplayBandwidthMode.AUTO -> stringResource(R.string.autoplay_bandwidth_mode_auto)
            AutoplayBandwidthMode.MANUAL -> stringResource(R.string.autoplay_bandwidth_mode_manual)
        }
        NavigationSettingsItem(
            icon = Icons.Default.Wifi,
            title = stringResource(R.string.autoplay_bandwidth_mode_title),
            subtitle = modeSubtitle,
            onClick = onShowAutoplayBandwidthModeDialog,
            onFocused = onItemFocused
        )
    }

    if (playerSettings.autoplayBandwidthMode == AutoplayBandwidthMode.MANUAL) {
        item(key = "autoplay_manual_bitrate_limit") {
            val sliderValue = (playerSettings.manualBitrateLimitMbps / 5.0).roundToInt()
            SliderSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.autoplay_manual_bitrate_limit_title),
                subtitle = stringResource(R.string.autoplay_manual_bitrate_limit_sub),
                value = sliderValue,
                valueText = "${sliderValue * 5} Mbps",
                minValue = 1,
                maxValue = 40,
                step = 1,
                onValueChange = { onSetManualBitrateLimitMbps(it * 5.0) },
                onFocused = onItemFocused
            )
        }
    }
```

- [ ] **Step 4: Add bandwidth mode dialog**

Change `AutoPlaySettingsDialogs` signature:

```kotlin
internal fun AutoPlaySettingsDialogs(
    showAutoplayBandwidthModeDialog: Boolean,
    showNextEpisodeThresholdModeDialog: Boolean,
    showReuseLastLinkCacheDialog: Boolean,
    playerSettings: PlayerSettings,
    installedAddonNames: List<String>,
    onSetAutoplayBandwidthMode: (AutoplayBandwidthMode) -> Unit,
    onSetNextEpisodeThresholdMode: (NextEpisodeThresholdMode) -> Unit,
    onSetReuseLastLinkCacheHours: (Int) -> Unit,
    onDismissAutoplayBandwidthModeDialog: () -> Unit,
    onDismissNextEpisodeThresholdModeDialog: () -> Unit,
    onDismissReuseLastLinkCacheDialog: () -> Unit
)
```

Add this block before the existing threshold dialog block:

```kotlin
    if (showAutoplayBandwidthModeDialog) {
        AutoplayBandwidthModeDialog(
            selectedMode = playerSettings.autoplayBandwidthMode,
            onModeSelected = {
                onSetAutoplayBandwidthMode(it)
                onDismissAutoplayBandwidthModeDialog()
            },
            onDismiss = onDismissAutoplayBandwidthModeDialog
        )
    }
```

Add this composable before `NextEpisodeThresholdModeDialog`:

```kotlin
@Composable
private fun AutoplayBandwidthModeDialog(
    selectedMode: AutoplayBandwidthMode,
    onModeSelected: (AutoplayBandwidthMode) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val options = listOf(
        Triple(
            AutoplayBandwidthMode.AUTO,
            stringResource(R.string.autoplay_bandwidth_mode_auto),
            stringResource(R.string.autoplay_bandwidth_mode_auto_desc)
        ),
        Triple(
            AutoplayBandwidthMode.MANUAL,
            stringResource(R.string.autoplay_bandwidth_mode_manual),
            stringResource(R.string.autoplay_bandwidth_mode_manual_desc)
        )
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.autoplay_bandwidth_mode_title),
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
            ) {
                items(
                    count = options.size,
                    key = { index -> options[index].first.name }
                ) { index ->
                    val (mode, title, description) = options[index]
                    val isSelected = mode == selectedMode

                    Card(
                        onClick = { onModeSelected(mode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                        colors = CardDefaults.colors(
                            containerColor = if (isSelected) NexioColors.FocusBackground else NexioColors.BackgroundCard,
                            focusedContainerColor = NexioColors.FocusBackground
                        ),
                        shape = CardDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                        scale = CardDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    color = if (isSelected) NexioColors.Primary else NexioColors.TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = description,
                                    color = NexioColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(12.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = NexioColors.Primary,
                                    modifier = Modifier.height(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Wire `PlaybackSettingsSections` callbacks**

Add parameters to `PlaybackSettingsSections` near the existing autoplay dialog parameters:

```kotlin
    onShowAutoplayBandwidthModeDialog: () -> Unit,
    onShowNextEpisodeThresholdModeDialog: () -> Unit,
    onShowReuseLastLinkCacheDialog: () -> Unit,
    onSetManualBitrateLimitMbps: (Double) -> Unit,
```

Update the `autoPlaySettingsItems` call:

```kotlin
            autoPlaySettingsItems(
                playerSettings = playerSettings,
                onShowAutoplayBandwidthModeDialog = onShowAutoplayBandwidthModeDialog,
                onShowNextEpisodeThresholdModeDialog = onShowNextEpisodeThresholdModeDialog,
                onShowReuseLastLinkCacheDialog = onShowReuseLastLinkCacheDialog,
                onSetManualBitrateLimitMbps = onSetManualBitrateLimitMbps,
                onSetStreamAutoPlayNextEpisodeEnabled = onSetStreamAutoPlayNextEpisodeEnabled,
```

Update `PlaybackSettingsDialogsHost` signature near the autoplay dialog state:

```kotlin
    showAutoplayBandwidthModeDialog: Boolean,
    showNextEpisodeThresholdModeDialog: Boolean,
```

Add callback parameters near the existing threshold setter:

```kotlin
    onSetAutoplayBandwidthMode: (AutoplayBandwidthMode) -> Unit,
    onSetNextEpisodeThresholdMode: (com.nexio.tv.data.local.NextEpisodeThresholdMode) -> Unit,
```

Add dismiss parameter:

```kotlin
    onDismissAutoplayBandwidthModeDialog: () -> Unit,
    onDismissNextEpisodeThresholdModeDialog: () -> Unit,
```

Update the `AutoPlaySettingsDialogs` call:

```kotlin
    AutoPlaySettingsDialogs(
        showAutoplayBandwidthModeDialog = showAutoplayBandwidthModeDialog,
        showNextEpisodeThresholdModeDialog = showNextEpisodeThresholdModeDialog,
        showReuseLastLinkCacheDialog = showReuseLastLinkCacheDialog,
        playerSettings = playerSettings,
        installedAddonNames = installedAddonNames,
        onSetAutoplayBandwidthMode = onSetAutoplayBandwidthMode,
        onSetNextEpisodeThresholdMode = onSetNextEpisodeThresholdMode,
        onSetReuseLastLinkCacheHours = onSetReuseLastLinkCacheHours,
        onDismissAutoplayBandwidthModeDialog = onDismissAutoplayBandwidthModeDialog,
        onDismissNextEpisodeThresholdModeDialog = onDismissNextEpisodeThresholdModeDialog,
        onDismissReuseLastLinkCacheDialog = onDismissReuseLastLinkCacheDialog
    )
```

- [ ] **Step 6: Wire `PlaybackSettingsScreen` state and callbacks**

Add dialog state:

```kotlin
    var showAutoplayBandwidthModeDialog by remember { mutableStateOf(false) }
    var showNextEpisodeThresholdModeDialog by remember { mutableStateOf(false) }
```

Update `dismissAllDialogs()`:

```kotlin
        showAutoplayBandwidthModeDialog = false
        showNextEpisodeThresholdModeDialog = false
```

Update the `PlaybackSettingsSections` call:

```kotlin
                onShowAutoplayBandwidthModeDialog = { openDialog { showAutoplayBandwidthModeDialog = true } },
                onShowNextEpisodeThresholdModeDialog = { openDialog { showNextEpisodeThresholdModeDialog = true } },
                onShowReuseLastLinkCacheDialog = { openDialog { showReuseLastLinkCacheDialog = true } },
                onSetManualBitrateLimitMbps = { mbps ->
                    coroutineScope.launch { viewModel.setManualBitrateLimitMbps(mbps) }
                },
```

Update the `PlaybackSettingsDialogsHost` call:

```kotlin
        showAutoplayBandwidthModeDialog = showAutoplayBandwidthModeDialog,
        showNextEpisodeThresholdModeDialog = showNextEpisodeThresholdModeDialog,
```

Add the dialog setter callback:

```kotlin
        onSetAutoplayBandwidthMode = { mode ->
            coroutineScope.launch { viewModel.setAutoplayBandwidthMode(mode) }
        },
        onSetNextEpisodeThresholdMode = { mode ->
            coroutineScope.launch { viewModel.setNextEpisodeThresholdMode(mode) }
        },
```

Add dismiss callback:

```kotlin
        onDismissAutoplayBandwidthModeDialog = ::dismissAllDialogs,
        onDismissNextEpisodeThresholdModeDialog = ::dismissAllDialogs,
```

- [ ] **Step 7: Add string resources**

Add these strings in `strings.xml` under `<!-- PlaybackAutoPlaySettings -->`:

```xml
    <string name="autoplay_bandwidth_mode_title">Autoplay Bandwidth Mode</string>
    <string name="autoplay_bandwidth_mode_sub">Choose how autoplay decides which bitrates are safe.</string>
    <string name="autoplay_bandwidth_mode_auto">Auto (Benchmark)</string>
    <string name="autoplay_bandwidth_mode_auto_desc">Uses benchmark data to score streams by transport quality. Requires a completed benchmark.</string>
    <string name="autoplay_bandwidth_mode_manual">Manual (Fixed Cap)</string>
    <string name="autoplay_bandwidth_mode_manual_desc">Set a maximum bitrate limit. No benchmark required. Recommended for WiFi devices.</string>
    <string name="autoplay_manual_bitrate_limit_title">Manual Bitrate Limit</string>
    <string name="autoplay_manual_bitrate_limit_sub">Autoplay will only choose streams at or below this average bitrate.</string>
```

- [ ] **Step 8: Run a compile check**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.local.PlayerSettingsDataStoreTest --tests com.nexio.tv.data.repository.benchmark.BenchmarkAwareScoringHarnessTest --tests com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest
```

Expected: PASS.

- [ ] **Step 9: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt app/src/main/res/values/strings.xml
git commit -m "feat: expose manual autoplay bandwidth setting"
```

## Task 5: Full Verification

**Files:**
- Verify: full working tree

- [ ] **Step 1: Run unit tests**

Run:

```bash
./gradlew testArm64DebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Run build**

Run:

```bash
./gradlew assembleArm64Debug
```

Expected: PASS.

- [ ] **Step 3: Manual app verification**

Run the app and verify these flows:

```text
1. Open Settings > Playback > Stream Selection.
2. Select Autoplay Bandwidth Mode.
3. Choose Manual (Fixed Cap).
4. Set Manual Bitrate Limit to 20 Mbps.
5. Open a title with debrid-wrapped streams and no completed benchmark.
6. Verify autoplay selects the best content-quality stream at or below 20 Mbps.
7. Return to Settings > Playback > Stream Selection.
8. Select Autoplay Bandwidth Mode.
9. Choose Auto (Benchmark).
10. Open the same title without a benchmark.
11. Verify benchmark-aware autoplay preserves existing behavior and does not select through the manual path.
```

Expected: manual mode autoplays without benchmark data; auto mode still requires benchmark data.

- [ ] **Step 4: Inspect release/version guardrails**

Run:

```bash
git diff -- . ':!docs/superpowers/plans/2026-04-12-autoplay-manual-bandwidth-mode.md'
```

Expected: app/test/resource changes only. No root `CHANGELOG.md`, plugin release version, or marketplace version edits.

- [ ] **Step 5: Commit verification fixes only if needed**

If verification required code changes, run:

```bash
git add app/src/main/java app/src/test/java app/src/main/res/values/strings.xml
git commit -m "fix: finish manual autoplay bandwidth mode"
```

Expected: commit succeeds only when verification produced additional source changes.

## Self-Review

- Spec coverage:
  - Data model, keys, defaults, and setters are covered by Task 1.
  - Manual scorer path without benchmark lookup is covered by Task 2.
  - ViewModel autoplay and shadow replay branching are covered by Task 3.
  - Settings UI, dialog, slider, ViewModel delegates, and strings are covered by Task 4.
  - Build, unit tests, and manual flows are covered by Task 5.
- Placeholder scan:
  - No prohibited placeholder terms or unspecified edge-condition instructions remain.
- Type consistency:
  - `AutoplayBandwidthMode`, `autoplayBandwidthMode`, `manualBitrateLimitMbps`, `setAutoplayBandwidthMode`, `setManualBitrateLimitMbps`, and `scoreWithManualCap` use the same names across storage, scorer, ViewModel, UI, and tests.
