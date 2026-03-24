# Detail View Watch Context Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the TV detail view so the hero CTA follows recent watch context and season-tab down navigation respects manual season overrides for the current detail-screen session.

**Architecture:** Extract pure detail-navigation support helpers for series CTA selection and season entry targeting, then wire `MetaDetailsViewModel` to track session-scoped manual season overrides while `MetaDetailsScreen` consumes the shared helper for down-focus and scroll targeting. Keep public routes and `NextToWatch` shape unchanged.

**Tech Stack:** Kotlin, Jetpack Compose for TV, StateFlow/ViewModel, JUnit4 JVM tests, Android Compose instrumentation, Gradle

---

### Task 1: Write Failing JVM Regression Tests For Shared Detail Navigation Logic

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsNavigationSupportTest.kt`
- Reference: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Reference: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`

- [ ] **Step 1: Write the failing recent-watch CTA test**

Create a JVM test that describes the bad `Shrinking` regression:

```kotlin
@Test
fun recentCompletedEpisodeBeatsEarlierGapFallback() {
    val result = buildSeriesNextToWatchCandidate(
        episodes = episodesForSeasons(1..3, episodeCount = 10),
        progressMap = mapOf(
            (1 to 1) to completedEpisode(lastWatched = 1_000L, season = 1, episode = 1),
            (3 to 9) to completedEpisode(lastWatched = 9_000L, season = 3, episode = 9)
        ),
        metaId = "show"
    )

    assertEquals("show:3:10", result.nextVideoId)
    assertEquals(3, result.nextSeason)
    assertEquals(10, result.nextEpisode)
}
```

- [ ] **Step 2: Run the JVM test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsNavigationSupportTest.recentCompletedEpisodeBeatsEarlierGapFallback`

Expected: FAIL because the shared helper does not exist yet.

- [ ] **Step 3: Write the failing manual-season-override entry-target tests**

Add tests for:
- auto-targeted season uses the CTA episode when there is no stored per-season focus
- manual override returns the selected season’s first episode instead of the CTA target
- stored per-season focus beats CTA targeting
- specials are used only when no regular-season episodes exist

Use a pure helper target such as `resolveSeasonEntryEpisodeId(...)` so the tests do not depend on Compose runtime.

- [ ] **Step 4: Run the JVM test class to verify all new tests fail for the expected reason**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsNavigationSupportTest`

Expected: FAIL with missing-symbol or behavior failures tied to the unimplemented helper functions.

- [ ] **Step 5: Commit the red test state scaffold if the branch policy or local workflow requires it; otherwise leave the failing tests staged for Task 2**

Run: `git status --short`

Expected: only the new JVM test file is pending.

### Task 2: Implement Shared Detail Navigation Support Helpers

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsNavigationSupport.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsNavigationSupportTest.kt`
- Reference: `app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt`
- Reference: `app/src/main/java/com/nexio/tv/domain/model/Video.kt`

- [ ] **Step 1: Implement the minimal shared helper API to satisfy the red tests**

Add focused internal helpers, for example:

```kotlin
internal fun buildSeriesNextToWatchCandidate(...)
internal fun shouldAutoSwitchToTargetSeason(
    selectedSeason: Int,
    targetSeason: Int?,
    manualOverrideActive: Boolean,
    availableSeasons: List<Int>
): Boolean
internal fun resolveSeasonEntryEpisodeId(...)
```

Rules the helpers must encode:
- most recent in-progress episode wins
- otherwise, advance from the most recent completed episode
- only then fall back to the earliest unwatched regular episode, with specials only when no regular episodes exist
- manual override blocks CTA-based season entry
- per-season last-focused episode wins over CTA entry targeting

- [ ] **Step 2: Run the JVM test class to verify the helpers now pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsNavigationSupportTest`

Expected: PASS

- [ ] **Step 3: Do a quick self-review for helper scope**

Check that:
- helper file owns only pure selection logic
- no Compose or ViewModel state leaks into the helper API
- no new behavior beyond the approved spec was added

- [ ] **Step 4: Commit the helper implementation**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsNavigationSupport.kt \
        app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsNavigationSupportTest.kt
git commit -m "fix: add detail navigation selection helpers"
```

### Task 3: Wire ViewModel State For Recent-Watch CTA And Session-Scoped Manual Override

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Reference: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsNavigationSupport.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsNavigationSupportTest.kt`

- [ ] **Step 1: Add the failing state-transition tests if Task 2 did not already cover them**

Cover the pure decision that:
- initial CTA target may auto-switch the season
- a user-triggered season change marks the session as manually overridden
- later CTA recalculations must not auto-switch seasons while the override is active

Prove this through the shared helper seam in `MetaDetailsNavigationSupportTest`, not through a
ViewModel test harness. The red assertion should target `shouldAutoSwitchToTargetSeason(...)`.

- [ ] **Step 2: Run the affected JVM tests to verify the state-transition case is red**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsNavigationSupportTest`

Expected: FAIL if the state-transition helper or contract is not yet wired.

- [ ] **Step 3: Update `MetaDetailsUiState` and `MetaDetailsViewModel` to use the helper-driven rules**

Implementation requirements:
- add session-scoped manual override state to the detail UI model
- set the override when handling `OnSeasonSelected`
- clear the override only when the detail screen is recreated by a new view-model instance
- keep `nextToWatch` as the hero CTA source
- let `updateNextToWatch(...)` auto-switch seasons only when manual override is not active

- [ ] **Step 4: Run the focused JVM tests again**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsNavigationSupportTest`

Expected: PASS

- [ ] **Step 5: Commit the view-model wiring**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt \
        app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt \
        app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsNavigationSupportTest.kt
git commit -m "fix: preserve manual season override in detail view"
```

### Task 4: Wire Screen Entry Targeting And Update Instrumentation Coverage

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
- Modify: `app/src/androidTest/java/com/nexio/tv/ui/screens/detail/SeasonTabsNavigationTest.kt`
- Reference: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsNavigationSupport.kt`
- Reference: `app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodesSection.kt`

- [ ] **Step 1: Update the instrumentation harness to encode the approved behavior**

The harness should explicitly model:
- initial auto-targeted season enters the CTA episode
- manual override enters the selected season instead of the CTA season
- stored per-season focus still wins
- existing short-show behavior remains unchanged

- [ ] **Step 2: Run the instrumentation test class to verify the new/updated case is red if screen wiring is incomplete**

Run: `ANDROID_SERIAL=192.168.50.102:5555 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexio.tv.ui.screens.detail.SeasonTabsNavigationTest`

Expected: FAIL until the screen logic matches the helper-driven behavior.

- [ ] **Step 3: Update `MetaDetailsScreen` to use the shared season-entry helper**

Implementation requirements:
- derive the row entry target separately from hero CTA text/rendering
- use the CTA target only when the selected season still matches the current auto-target and no manual override is active
- otherwise use last-focused episode or first episode in the selected season
- keep the existing `SeasonTabs` ordering and `EpisodesRow` restore behavior intact

- [ ] **Step 4: Run the instrumentation test class again**

Run: `ANDROID_SERIAL=192.168.50.102:5555 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexio.tv.ui.screens.detail.SeasonTabsNavigationTest`

Expected: PASS

- [ ] **Step 5: Commit the screen and instrumentation changes**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt \
        app/src/androidTest/java/com/nexio/tv/ui/screens/detail/SeasonTabsNavigationTest.kt
git commit -m "fix: honor detail season entry overrides"
```

### Task 5: Run Final Focused Verification And Capture Branch State

**Files:**
- Modify: `openspec/changes/fix-detail-view-watch-context/tasks.md`
- Reference: `docs/superpowers/specs/2026-03-24-detail-view-watch-context-design.md`
- Reference: `openspec/changes/fix-detail-view-watch-context/specs/detail-view-navigation/spec.md`

- [ ] **Step 1: Mark the OpenSpec task checklist complete**

Update `openspec/changes/fix-detail-view-watch-context/tasks.md` so every implemented item is checked.

- [ ] **Step 2: Run strict OpenSpec validation**

Run: `openspec validate fix-detail-view-watch-context --strict`

Expected: PASS

- [ ] **Step 3: Run the full focused JVM verification**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsNavigationSupportTest`

Expected: PASS

- [ ] **Step 4: Run the full focused instrumentation verification**

Run: `ANDROID_SERIAL=192.168.50.102:5555 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexio.tv.ui.screens.detail.SeasonTabsNavigationTest`

Expected: PASS

- [ ] **Step 5: Commit the task-list update and any final cleanup**

```bash
git add openspec/changes/fix-detail-view-watch-context/tasks.md
git commit -m "test: verify detail view watch-context fix"
```
