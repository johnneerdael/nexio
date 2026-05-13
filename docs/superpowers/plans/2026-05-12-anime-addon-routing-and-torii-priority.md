# Anime Addon Routing and Torii Priority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Query only anime-specific addons for anime stream requests when anime addons are configured, and rank Nexio Torii ahead of Nexio Nagare in grouped stream presentation.

**Architecture:** Keep addon selection inside `StreamRepositoryImpl`, where compatible addons and anime classification already exist. Keep provider ranking inside `StreamPresentationEngine.organize`, where grouped stream ordering already compares cache, resolution, and size.

**Tech Stack:** Kotlin, coroutines `Flow`, MockK, JUnit4, OpenSpec.

---

## File Structure

- Modify: `openspec/changes/restrict-anime-stream-addons/proposal.md` for the change summary.
- Modify: `openspec/changes/restrict-anime-stream-addons/tasks.md` for OpenSpec task tracking.
- Modify: `openspec/changes/restrict-anime-stream-addons/specs/stream-source-selection/spec.md` for behavior requirements.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt` to select the addon fan-out list after anime classification.
- Modify: `app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplAnimeBucketTest.kt` to cover anime-only query selection and update the old generic fallback expectation.
- Modify: `app/src/main/java/com/nexio/tv/core/stream/StreamPresentationModels.kt` to add Torii-before-Nagare ordering in grouped presentation.
- Modify: `app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt` to cover the provider-rank sort key.

## Task 1: Validate the OpenSpec Scaffold

**Files:**
- Modify: `openspec/changes/restrict-anime-stream-addons/proposal.md`
- Modify: `openspec/changes/restrict-anime-stream-addons/tasks.md`
- Modify: `openspec/changes/restrict-anime-stream-addons/specs/stream-source-selection/spec.md`

- [ ] **Step 1: Review the scaffolded proposal**

Confirm `openspec/changes/restrict-anime-stream-addons/proposal.md` contains:

```markdown
# Change: restrict anime stream addons and prefer Torii

## Why

Anime-specific stream addons currently receive priority in presentation, but the stream repository still queries every compatible addon for anime content. That keeps generic addons on the hot path even after the user has explicitly configured anime-specific sources.

NEXIO also supports two built-in Nexio provider presets. Torii should outrank Nagare when their other core ranking signals tie because Torii exposes selected-file size, which makes downstream ranking more accurate than Nagare's weaker metadata.

## What Changes

- When the requested content is classified as anime and at least one compatible installed addon is tagged `isAnime`, only those anime-tagged addons are queried for streams.
- For non-anime content, unknown content, or users without anime-tagged compatible addons, the existing all-compatible-addon query behavior remains unchanged.
- Grouped stream presentation ranks `AddonParserPreset.NEXIO_TORII` ahead of `AddonParserPreset.NEXIO_NAGARE` after cache and resolution, before size-based ordering.
- Existing service-wrap and progressive stream emission behavior remains intact for the selected addon set.

## Impact

- Affected app: `app`
- Affected areas: stream query fan-out, stream presentation ranking, autoplay candidate ordering through presented stream order
- Compatibility: existing generic-addon behavior remains unchanged unless content is confidently classified as anime and anime-specific addons are configured
```

- [ ] **Step 2: Review the spec delta**

Confirm `openspec/changes/restrict-anime-stream-addons/specs/stream-source-selection/spec.md` contains the `Anime stream requests use only anime-specific addons when available` and `Torii is preferred over Nagare for grouped stream ranking` requirements with `#### Scenario:` blocks.

- [ ] **Step 3: Validate OpenSpec**

Run:

```bash
openspec validate restrict-anime-stream-addons --strict
```

Expected: validation succeeds with no errors.

- [ ] **Step 4: Commit only the OpenSpec scaffold**

Run:

```bash
git add -f openspec/changes/restrict-anime-stream-addons/proposal.md openspec/changes/restrict-anime-stream-addons/tasks.md openspec/changes/restrict-anime-stream-addons/specs/stream-source-selection/spec.md
git status -sb
git commit -m "docs: specify anime stream addon routing"
```

Expected: only the three OpenSpec files are staged. The `-f` is required because `openspec/` is ignored by `.gitignore`; keep the paths explicit.

## Task 2: Add Repository Tests for Anime-Only Query Selection

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplAnimeBucketTest.kt`

- [ ] **Step 1: Add the anime-only query test**

Add this test inside `StreamRepositoryImplAnimeBucketTest`:

```kotlin
    @Test
    fun `anime content queries only anime tagged compatible addons when configured`() = runTest {
        mockAndroidLog()

        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val genericAddon = streamAddon("https://generic.example", "Generic Addon")
        val addonStreamIntegrationProvider = mockk<AddonStreamIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val serviceWrapSessionFactory = mockk<ServiceWrapSessionFactory>(relaxed = true)
        val addonStreamRequestCanceller = mockk<AddonStreamRequestCanceller>(relaxed = true)

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(animeAddon, genericAddon))
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        coEvery {
            addonStreamIntegrationProvider.getStreams(animeAddon.id, match { it.contains("anime.example") }, any())
        } returns NetworkResult.Success(StreamResponseDto(streams = listOf(streamDto("Anime Stream"))))
        coEvery {
            addonStreamIntegrationProvider.getStreams(genericAddon.id, any(), any())
        } returns NetworkResult.Success(StreamResponseDto(streams = listOf(streamDto("Generic Stream"))))

        val repository = StreamRepositoryImpl(
            addonStreamIntegrationProvider = addonStreamIntegrationProvider,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            serviceWrapSessionFactory = serviceWrapSessionFactory,
            addonStreamRequestCanceller = addonStreamRequestCanceller,
            animeIdentityIndex = RecordingAnimeIdentityIndex(contentIsAnime = true),
            traceMetadataEvents = mockk(relaxed = true)
        )

        val emissions = repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "mal:21",
            requestOrigin = "test_anime_only_addons",
            requestId = "request-anime-only-addons"
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList()

        assertEquals(listOf("Anime Addon"), emissions.last().data.map { it.addonName })
        assertTrue(emissions.last().data.single().isAnimeBucket)
        coVerify(exactly = 1) {
            addonStreamIntegrationProvider.getStreams(animeAddon.id, match { it.contains("anime.example") }, any())
        }
        coVerify(exactly = 0) {
            addonStreamIntegrationProvider.getStreams(genericAddon.id, any(), any())
        }
    }
```

- [ ] **Step 2: Add the non-anime compatibility test**

Add this test inside `StreamRepositoryImplAnimeBucketTest`:

```kotlin
    @Test
    fun `non anime content still queries anime tagged and generic compatible addons`() = runTest {
        mockAndroidLog()

        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val genericAddon = streamAddon("https://generic.example", "Generic Addon")
        val addonStreamIntegrationProvider = mockk<AddonStreamIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val serviceWrapSessionFactory = mockk<ServiceWrapSessionFactory>(relaxed = true)
        val addonStreamRequestCanceller = mockk<AddonStreamRequestCanceller>(relaxed = true)

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(animeAddon, genericAddon))
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        coEvery {
            addonStreamIntegrationProvider.getStreams(animeAddon.id, match { it.contains("anime.example") }, any())
        } returns NetworkResult.Success(StreamResponseDto(streams = listOf(streamDto("Anime Tagged Stream"))))
        coEvery {
            addonStreamIntegrationProvider.getStreams(genericAddon.id, match { it.contains("generic.example") }, any())
        } returns NetworkResult.Success(StreamResponseDto(streams = listOf(streamDto("Generic Stream"))))

        val repository = StreamRepositoryImpl(
            addonStreamIntegrationProvider = addonStreamIntegrationProvider,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            serviceWrapSessionFactory = serviceWrapSessionFactory,
            addonStreamRequestCanceller = addonStreamRequestCanceller,
            animeIdentityIndex = RecordingAnimeIdentityIndex(contentIsAnime = false),
            traceMetadataEvents = mockk(relaxed = true)
        )

        val emissions = repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1234567",
            requestOrigin = "test_non_anime_all_addons",
            requestId = "request-non-anime-all-addons"
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList()

        assertEquals(setOf("Anime Addon", "Generic Addon"), emissions.last().data.map { it.addonName }.toSet())
        assertTrue(emissions.last().data.none { it.isAnimeBucket })
        coVerify(exactly = 1) {
            addonStreamIntegrationProvider.getStreams(animeAddon.id, match { it.contains("anime.example") }, any())
        }
        coVerify(exactly = 1) {
            addonStreamIntegrationProvider.getStreams(genericAddon.id, match { it.contains("generic.example") }, any())
        }
    }
```

- [ ] **Step 3: Update the old fallback test expectation**

Replace the existing test named `anime_tagged_addon_empty_generic_addons_still_selected` with:

```kotlin
    @Test
    fun `anime tagged addon empty result does not fall back to generic addons`() = runTest {
        val animeAddon = streamAddon(
            baseUrl = "https://anime.example",
            displayName = "Anime Addon",
            isAnime = true,
            returnedStreams = emptyList()
        )
        val genericAddon = streamAddon("https://generic.example", "Generic Addon")
        val repository = repository(
            addons = listOf(animeAddon, genericAddon),
            animeIdentityIndex = RecordingAnimeIdentityIndex(contentIsAnime = true)
        )

        val buckets = repository.successBuckets(videoId = "mal:21")

        assertEquals(listOf("Anime Addon"), buckets.map { it.addonName })
        assertTrue(buckets.single().isAnimeBucket)
        assertTrue(buckets.single().streams.isEmpty())
    }
```

- [ ] **Step 4: Run the focused repository tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.StreamRepositoryImplAnimeBucketTest
```

Expected before implementation: at least the new anime-only test fails because `Generic Addon` is still queried and appears in final buckets.

- [ ] **Step 5: Commit failing tests**

Run:

```bash
git add app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplAnimeBucketTest.kt
git status -sb
git commit -m "test: cover anime-only stream addon routing"
```

Expected: only `StreamRepositoryImplAnimeBucketTest.kt` is staged.

## Task 3: Implement Anime-Only Addon Fan-Out

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt`

- [ ] **Step 1: Introduce the selected addon list**

In `StreamRepositoryImpl.getStreamsFromAllAddons`, immediately after `animePriorityGateEnabled` is assigned, add `queryAddons`:

```kotlin
            val accumulatedResults = LinkedHashMap<String, AddonStreams>()
            val addonDiagnostics = mutableListOf<AddonFetchDiagnostic>()
            val animePriorityGateEnabled = contentIsAnime && streamAddons.any { it.isAnime }
            val queryAddons = if (animePriorityGateEnabled) {
                streamAddons.filter { it.isAnime }
            } else {
                streamAddons
            }
            var emittedSuccess = false
```

- [ ] **Step 2: Launch only selected addons**

Replace:

```kotlin
                val jobs = streamAddons.map { addon ->
```

with:

```kotlin
                val jobs = queryAddons.map { addon ->
```

- [ ] **Step 3: Count remaining anime events from selected addons**

Replace:

```kotlin
                var remainingAddonEvents = jobs.size
                var remainingAnimeAddonEvents = if (animePriorityGateEnabled) streamAddons.count { it.isAnime } else 0
```

with:

```kotlin
                var remainingAddonEvents = jobs.size
                var remainingAnimeAddonEvents = if (animePriorityGateEnabled) queryAddons.count { it.isAnime } else 0
```

- [ ] **Step 4: Report diagnostics for selected addon candidates**

Replace the `totalAddonCandidates` argument in `logRequestSummary`:

```kotlin
                    totalAddonCandidates = streamAddons.size,
```

with:

```kotlin
                    totalAddonCandidates = queryAddons.size,
```

- [ ] **Step 5: Run repository tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.StreamRepositoryImplAnimeBucketTest --tests com.nexio.tv.data.repository.StreamRepositoryImplTest
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit implementation**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt
git status -sb
git commit -m "fix: query only anime stream addons for anime content"
```

Expected: only `StreamRepositoryImpl.kt` is staged.

## Task 4: Add Torii-Before-Nagare Ranking Test

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`

- [ ] **Step 1: Add the failing grouped ranking test**

Add this test after `grouped sorting orders cached then unknown then uncached before resolution and size`:

```kotlin
    @Test
    fun `grouped sorting prefers torii over nagare before size when cache and resolution tie`() {
        val torii = stream(
            filename = "Show.S01E01.1080p.WEB-DL.x265.Torii.mkv",
            name = "⚡ RD",
            parserPreset = AddonParserPreset.NEXIO_TORII,
            addonName = "Nexio Torii",
            videoSizeBytes = 2L * 1024L * 1024L * 1024L
        )
        val nagare = stream(
            filename = "Show.S01E01.1080p.WEB-DL.x265.Nagare.mkv",
            name = "⚡ RD",
            parserPreset = AddonParserPreset.NEXIO_NAGARE,
            addonName = "Nexio Nagare",
            videoSizeBytes = 20L * 1024L * 1024L * 1024L
        )

        val result = StreamPresentationEngine.organize(
            streams = listOf(nagare, torii),
            availableAddons = listOf("Nexio Nagare", "Nexio Torii"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(groupAcrossAddonsEnabled = true),
            requestContext = StreamRequestContext(contentType = "series")
        )

        assertEquals(
            listOf("Nexio Torii", "Nexio Nagare"),
            result.items.map { it.stream.addonName }
        )
    }
```

- [ ] **Step 2: Run the focused presentation test and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest
```

Expected before implementation: the new test fails with Nagare first because size ordering currently wins.

- [ ] **Step 3: Commit failing test**

Run:

```bash
git add app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt
git status -sb
git commit -m "test: cover torii provider stream priority"
```

Expected: only `StreamPresentationEngineTest.kt` is staged.

## Task 5: Implement Torii-Before-Nagare Ranking

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/stream/StreamPresentationModels.kt`

- [ ] **Step 1: Add provider rank to grouped sorting**

In `StreamPresentationEngine.organize`, update the grouped sort comparator from:

```kotlin
        val groupedItems = groupedPreSortItems.sortedWith(
            compareBy<StreamCardModel> { it.addonPriorityRank }
                .thenBy { cacheStateRank(it.parsed.isCached) }
                .thenByDescending { resolutionRank(it.parsed.resolution) }
                .thenByDescending { it.parsed.sizeBytes ?: -1L }
                .thenBy { it.stream.addonName.lowercase(Locale.US) }
                .thenBy { it.title.lowercase(Locale.US) }
        )
```

to:

```kotlin
        val groupedItems = groupedPreSortItems.sortedWith(
            compareBy<StreamCardModel> { it.addonPriorityRank }
                .thenBy { cacheStateRank(it.parsed.isCached) }
                .thenByDescending { resolutionRank(it.parsed.resolution) }
                .thenBy { nexioProviderRank(it.stream.addonParserPreset) }
                .thenByDescending { it.parsed.sizeBytes ?: -1L }
                .thenBy { it.stream.addonName.lowercase(Locale.US) }
                .thenBy { it.title.lowercase(Locale.US) }
        )
```

- [ ] **Step 2: Add the provider rank helper**

Add this private helper near `cacheStateRank`:

```kotlin
    private fun nexioProviderRank(preset: AddonParserPreset): Int {
        return when (preset) {
            AddonParserPreset.NEXIO_TORII -> 0
            AddonParserPreset.NEXIO_NAGARE -> 1
            else -> 2
        }
    }
```

- [ ] **Step 3: Run presentation tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest
```

Expected: all `StreamPresentationEngineTest` tests pass, including the new Torii/Nagare test.

- [ ] **Step 4: Commit implementation**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/stream/StreamPresentationModels.kt
git status -sb
git commit -m "fix: prefer torii streams over nagare peers"
```

Expected: only `StreamPresentationModels.kt` is staged.

## Task 6: Final Verification

**Files:**
- Verify: `openspec/changes/restrict-anime-stream-addons/*`
- Verify: `app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt`
- Verify: `app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplAnimeBucketTest.kt`
- Verify: `app/src/main/java/com/nexio/tv/core/stream/StreamPresentationModels.kt`
- Verify: `app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`

- [ ] **Step 1: Run targeted unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.StreamRepositoryImplAnimeBucketTest --tests com.nexio.tv.data.repository.StreamRepositoryImplTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest --tests com.nexio.tv.core.player.StreamAutoPlaySelectorAnimePriorityTest
```

Expected: all targeted tests pass.

- [ ] **Step 2: Validate OpenSpec**

Run:

```bash
openspec validate restrict-anime-stream-addons --strict
```

Expected: validation succeeds with no errors.

- [ ] **Step 3: Inspect staged and unstaged files**

Run:

```bash
git status -sb
```

Expected: only files touched by this plan are modified or committed. Do not use `git add -A`, `git add .`, `git commit -a`, or `git stash`.

- [ ] **Step 4: Update OpenSpec tasks if implementation is complete**

Mark every item in `openspec/changes/restrict-anime-stream-addons/tasks.md` as complete:

```markdown
- [x] Add spec delta for anime-only addon query selection and Nexio provider ranking.
- [x] Add failing repository tests proving anime content queries only anime-tagged compatible addons and non-anime content still queries all compatible addons.
- [x] Implement selected-addon fan-out in `StreamRepositoryImpl` using the existing anime classification result.
- [x] Update existing anime bucket tests whose old expectation allowed generic fallback during anime-only mode.
- [x] Add failing presentation test proving Torii ranks ahead of Nagare when cache and resolution tie.
- [x] Implement Torii-before-Nagare provider ranking in grouped stream presentation.
- [x] Run targeted unit tests and `openspec validate restrict-anime-stream-addons --strict`.
```

- [ ] **Step 5: Commit final task status**

Run:

```bash
git add -f openspec/changes/restrict-anime-stream-addons/tasks.md
git status -sb
git commit -m "docs: mark anime stream routing tasks complete"
```

Expected: only the OpenSpec task file is staged. The `-f` is required because `openspec/` is ignored by `.gitignore`; keep the path explicit.

## Self-Review

- Spec coverage: The plan covers anime-only addon querying, no generic fallback when anime addons are configured, non-anime compatibility, unknown-classification compatibility, and Torii-before-Nagare grouped ranking.
- Placeholder scan: No forbidden placeholder phrases or undefined implementation references remain.
- Type consistency: The plan uses existing `Addon.isAnime`, `AddonParserPreset.NEXIO_TORII`, `AddonParserPreset.NEXIO_NAGARE`, `AddonStreams.isAnimeBucket`, `Stream.addonParserPreset`, `StreamPresentationEngine.organize`, and `StreamRepositoryImpl.getStreamsFromAllAddons`.
