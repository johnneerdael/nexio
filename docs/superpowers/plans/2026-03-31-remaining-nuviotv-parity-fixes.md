# Remaining NuvioTV Parity Fixes Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the still-unported, non-trailer NuvioTV parity fixes that remain after `adapt-nuviotv-performance-bugfix-batches`, while confirming trailer regressions are already covered in Nexio.

**Architecture:** Keep the existing merged parity work on `codex/merge-adapt-nuviotv-main` intact and treat the remaining items as targeted follow-up patches. Prefer narrow changes in the existing UI/state surfaces instead of another broad rewrite, and only port upstream behavior where Nexio has an equivalent screen or setting.

**Tech Stack:** Kotlin, Jetpack Compose for TV, Coroutines/Flow, DataStore, Coil, ExoPlayer/Media3, Android unit/UI tests.

---

## Already Validated

The trailer-related upstream fixes are already present on `codex/merge-adapt-nuviotv-main` (`6eada7b90`) and do not need more parity work:

- `#726` disable indefinite detail-page trailer playback:
  - `/private/tmp/nexio-merge-adapt-nuvio/app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- `#833` stop trailer playback when app backgrounds:
  - `/private/tmp/nexio-merge-adapt-nuvio/app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt`
- `#945` cancel delayed trailer activation when lifecycle leaves `RESUMED`:
  - `/private/tmp/nexio-merge-adapt-nuvio/app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
  - `/private/tmp/nexio-merge-adapt-nuvio/app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt`
  - `/private/tmp/nexio-merge-adapt-nuvio/app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt`
- `#1119` back dismisses fullscreen Modern Home trailer instead of opening nav:
  - `/private/tmp/nexio-merge-adapt-nuvio/app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt`

## File Map

**Primary implementation files**

- Modify: `app/src/main/java/com/nexio/tv/ModernSidebarBlurPanel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/DateFormat.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/search/SearchScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/LayoutSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/LayoutSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/ContinueWatchingSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/GridContinueWatchingSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAfrPreflight.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

**Applicability audit files**

- Audit: `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt`
- Audit: `app/src/main/java/com/nexio/tv/domain/model/UserProfile.kt`

**Tests to add or extend**

- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipelineTest.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/detail/DateFormatTest.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/search/SearchVoiceIndicatorTest.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/settings/LayoutSettingsViewModelTest.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupPreparationTest.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsBackgroundScrollTest.kt`

## Task 1: Audit Applicability Gaps Before Porting

**Files:**
- Audit: `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt`
- Audit: `app/src/main/java/com/nexio/tv/domain/model/UserProfile.kt`
- Audit: `app/src/main/java/com/nexio/tv/ModernSidebarBlurPanel.kt`

- [ ] Verify whether Nexio has a true equivalent for NuvioTV `#851` profile category tabs overflow.
- [ ] If no matching screen exists, record `#851` as `not applicable` in the working notes or PR description instead of inventing a new UI surface.
- [ ] Reconfirm `#778` is the modern sidebar pill/logo overlap in `ModernSidebarBlurPanel.kt`, not a different sidebar implementation.
- [ ] Commit only if the audit itself changes tracked docs or notes; otherwise continue without a commit.

## Task 2: Port Modern Sidebar Pill / Logo Overlap Fix (`#778`)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ModernSidebarBlurPanel.kt`

- [ ] Write a focused compose/unit test if there is already a sidebar layout test surface; otherwise document manual verification.
- [ ] Adjust collapsed-header logo sizing/offset and selected pill width/alignment so the collapsed pill no longer overlaps the logo mark.
- [ ] Recheck expanded mode so the change does not shift the wordmark or focused item hit area.
- [ ] Verify manually on modern sidebar collapsed and expanded states.
- [ ] Commit with message: `fix: align modern sidebar pill and logo`

## Task 3: Wire Full Movie Release Date Into Detail Hero (`#781`)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/DateFormat.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/DateFormatTest.kt`

- [ ] Add a failing `DateFormatTest` for full ISO dates, year-only fallback, and locale-aware output ordering.
- [ ] Replace year-only `releaseInfo?.split("-")?.firstOrNull()` hero rendering for movies with `formatReleaseDate(...)`, keeping series year/range behavior unchanged.
- [ ] Reuse the same formatter in any duplicate detail metadata chip path so hero and body agree.
- [ ] Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.DateFormatTest`
- [ ] Commit with message: `fix: show full movie release date in details`

## Task 4: Port See All / Grid-Size / Hero-Width Layout Fixes (`#867`, `#868`, `#862`)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipelineTest.kt`

- [ ] Normalize `CatalogSeeAllScreen` padding/spacing against home grid spacing so poster-size changes produce the same layout rhythm in both places.
- [ ] Change See All row behavior so the trailing “See all” card uses remaining capacity rather than burning a full extra row when possible.
- [ ] Inspect the grid/home hero width path and, if black side borders are still possible, size the hero/backdrop using the actual available grid width rather than generic `fillMaxWidth`.
- [ ] Add or extend tests for row/build behavior if the prepared presentation pipeline decides “See all” placement.
- [ ] Verify manually after changing poster size in layout settings.
- [ ] Commit with message: `fix: align see all and grid layout behavior`

## Task 5: Add Voice Search Listening Indicator (`#960`)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/search/SearchScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/search/SearchVoiceIndicatorTest.kt`

- [ ] Add a failing test around the visible listening state if the screen can be exercised in a unit/compose host; otherwise define a tight manual verification script.
- [ ] Reuse existing `isVoiceListening` state to render an explicit visible listening indicator on devices without system chrome.
- [ ] Keep the indicator localized, low-noise, and focused on current listening state only.
- [ ] Run the new targeted test if added.
- [ ] Commit with message: `feat: show voice search listening indicator`

## Task 6: Add Continue Watching Blur Toggle And Finish CW Polish (`#1039`)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/LayoutSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/LayoutSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/ContinueWatchingSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/GridContinueWatchingSection.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/LayoutSettingsViewModelTest.kt`

- [ ] Add a dedicated `continueWatchingBlurEnabled` preference instead of reusing detail-page `blurUnwatchedEpisodes`.
- [ ] Surface the new toggle in layout settings with copy that makes the scope explicit.
- [ ] Thread the new preference into both CW sections so blur can be enabled for CW independently of the detail-page blur.
- [ ] Audit whether the remaining movie-enrichment inconsistency from `#1039` is already covered by current CW enrichment paths; only patch if a real gap remains.
- [ ] Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.settings.LayoutSettingsViewModelTest`
- [ ] Commit with message: `feat: add continue watching blur toggle`

## Task 7: Fix MetaDetails Background Bleed While Scrolling (`#1179`)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsBackgroundScrollTest.kt`

- [ ] Reproduce the bleed path in `MetaDetailsScreen` by inspecting how the persistent left and bottom gradients interact with the scrolled content.
- [ ] Port only the minimal rendering change that removes backdrop bleed during scroll, without regressing the current overdraw reductions already landed from `#968`.
- [ ] Add a focused regression test if practical; otherwise capture deterministic manual verification steps.
- [ ] Commit with message: `fix: stop detail backdrop bleed during scroll`

## Task 8: Measure AFR + Subtitle Startup Concurrency And Port If Needed (`#944`)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAfrPreflight.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupPreparationTest.kt`

- [ ] Add temporary logging or a deterministic test seam to establish whether AFR preflight currently blocks subtitle startup.
- [ ] If startup is already effectively parallel, document `#944` AFR/subtitle optimization as already satisfied and remove the temporary instrumentation.
- [ ] If startup is still sequential, refactor initialization so AFR preflight and startup subtitle preparation run concurrently, preserving existing cancellation/session-guard behavior.
- [ ] Add a regression test that proves subtitle fetch can begin without waiting for AFR completion.
- [ ] Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerStartupPreparationTest`
- [ ] Commit with message: `perf: parallelize afr preflight and subtitle startup`

## Task 9: Final Verification And Integration Notes

**Files:**
- Review: `docs/superpowers/plans/2026-03-31-remaining-nuviotv-parity-fixes.md`

- [ ] Run targeted tests added in Tasks 3, 5, 6, and 8.
- [ ] Run: `./gradlew :app:compileDebugKotlin --continue`
- [ ] If compile still fails in unrelated pre-existing files, call that out explicitly and list the blockers separately from this work.
- [ ] Run: `git diff --check`
- [ ] Prepare a parity summary grouped as:
  - ported now
  - audited as already present
  - audited as not applicable
- [ ] Commit the final integration pass with message: `chore: finish remaining nuviotv parity fixes`

## Notes

- Trailer-related items `#726`, `#833`, `#945`, and `#1119` are already covered and should not be reworked in this follow-up.
- `#851` is not applicable in Nexio. There is no profile-category tab UI and the user explicitly does not want one added.
- `#944` AFR + subtitle startup overlap is already effectively satisfied by the current `launchStartupPreparationTasks(...)` flow, which starts AFR preflight and startup subtitle preparation in parallel jobs.
- Do not regress the performance work already merged from `#968` and `#1070` while fixing `#1179` or the grid/hero layout items.
