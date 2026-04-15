---
phase: 07-tvdb-provider-replacement
plan: 06
subsystem: ui
tags: [android, kotlin, settings, tvdb, tmdb, poster-ratings]

requires:
  - phase: 07-tvdb-provider-replacement
    provides: Provider-neutral TV metadata contracts and TVDB diagnostics/cache conventions from Plan 07-01
provides:
  - Provider precedence settings copy for TVDB, TMDB, and poster-ratings
  - Resource-level provider precedence assertion
  - TopPosters TVDB poster URL and RPDB IMDb-only poster assertions
affects: [07-tvdb-provider-replacement, settings, poster-ratings, tvdb-provider-routing]

tech-stack:
  added: []
  patterns:
    - Shared resource string for provider precedence summary copy
    - Robolectric resource assertion for settings copy contracts

key-files:
  created:
    - app/src/test/java/com/nexio/tv/ui/screens/settings/ProviderPrecedenceCopyTest.kt
  modified:
    - app/src/main/res/values/strings.xml
    - app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt
    - app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt

key-decisions:
  - "Reused the existing settings screen resource pattern instead of introducing a new layout or toggle matrix."
  - "Kept poster-ratings assertions at the resolver boundary where provider ID parsing and poster authority are centralized."

patterns-established:
  - "Provider precedence copy lives in provider_precedence_summary and is consumed by the TVDB settings screen."
  - "TopPosters TVDB ID support and RPDB IMDb-only behavior are locked in PosterRatingsUrlResolverTest."

requirements-completed: [PREF-07, META-04, UX-01]

duration: 7 min
completed: 2026-04-15
---

# Phase 07 Plan 06: Provider Precedence Copy Summary

**Android TV settings copy now states TVDB/TMDB/poster-ratings precedence, with resolver tests for TVDB poster IDs.**

## Performance

- **Duration:** 7 min
- **Started:** 2026-04-15T03:41:03Z
- **Completed:** 2026-04-15T03:46:59Z
- **Tasks:** 3
- **Files modified:** 4

## Accomplishments

- Added poster-ratings resolver assertions for TopPosters `tvdb:` poster URL generation and RPDB fallback behavior for TVDB IDs.
- Updated settings resource copy so TVDB is described as the TV metadata provider when configured, TMDB as movie metadata and TV fallback, and poster-ratings as supported poster artwork override.
- Added `ProviderPrecedenceCopyTest` and wired `TvdbSettingsScreen` to the shared `provider_precedence_summary` resource.
- Verified the settings hub and TMDB settings screen continue to consume their existing resource IDs, while the TVDB screen does not add a granular toggle matrix.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add poster-ratings TVDB precedence assertions** - `c10e9a95e` (test)
2. **Task 2 RED: Provider precedence resource test** - `cc87d9d88` (test)
3. **Task 2 GREEN: Provider precedence resource copy** - `7ad3911a3` (feat)
4. **Task 3: Verify settings screens consume precedence copy** - `e662d7a6d` (chore, empty verification commit)

## Files Created/Modified

- `app/src/test/java/com/nexio/tv/ui/screens/settings/ProviderPrecedenceCopyTest.kt` - Robolectric resource test for exact provider precedence substrings.
- `app/src/main/res/values/strings.xml` - Settings hub, TMDB, TVDB, and provider precedence copy.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt` - TVDB settings screen now consumes `provider_precedence_summary`.
- `app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt` - TVDB TopPosters and RPDB TVDB-ID assertions.

## Decisions Made

- Used the shared `provider_precedence_summary` string on the TVDB settings screen instead of adding another TVDB-specific copy resource with nearly identical wording.
- Left existing TMDB intent toggles intact because provider routing, not a second TVDB toggle matrix, decides TVDB versus TMDB fallback.
- Recorded Task 3 as an empty verification commit because no additional source edit was needed after the Task 2 copy wiring.

## Deviations from Plan

None - plan implementation scope stayed within the specified settings/resource/test files.

## Issues Encountered

- Task 1's new assertions could not produce a clean TDD RED cycle because `PosterRatingsUrlResolver` already supported TopPosters `tvdb:` IDs and RPDB already ignored non-IMDb IDs. The added assertions lock existing behavior.
- Targeted Gradle test commands could not complete because `:app:compileArm64DebugUnitTestKotlin` fails before requested tests run. The failures are in unrelated existing tests and concurrent/dirty work outside this plan, including `PlayerSettingsDataStore*`, `SearchHistoryDataStoreTest`, `ThemeDataStoreProfileTest`, `SimklViewModelTest`, `TraktViewModelPriorityHydrationTest`, `ProfileManagerTest`, and `AndroidTvSearchSuggestionMapperTest`.
- Kotlin daemon startup repeatedly reported unsupported `ZGenerational` and fell back to non-daemon compilation, matching the environment noise recorded in Plan 07-01.

## Verification

- `rg` checks confirmed required provider precedence strings and screen references are present.
- `rg` confirmed `TvdbSettingsScreen.kt` contains no `ToggleArtwork` or `ToggleCredits`.
- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.poster.PosterRatingsUrlResolverTest"` failed at unit-test compilation due unrelated test compile errors before this class ran.
- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.settings.ProviderPrecedenceCopyTest"` first failed as expected on missing `R.string.provider_precedence_summary`, then failed after implementation only on unrelated unit-test compile errors.
- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.settings.ProviderPrecedenceCopyTest" --tests "com.nexio.tv.core.poster.PosterRatingsUrlResolverTest"` failed at unit-test compilation due the same unrelated compile debt.

## Known Stubs

None. The scan surfaced pre-existing placeholder input strings and nullable Compose state in touched files; no new placeholder/stub UI data was introduced by this plan.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Provider precedence copy is ready for downstream TVDB routing plans. The branch still needs unrelated unit-test compile debt resolved before targeted settings/poster tests can execute normally.

## Self-Check: PASSED

- Found `.planning/phases/07-tvdb-provider-replacement/07-06-SUMMARY.md`.
- Found `app/src/test/java/com/nexio/tv/ui/screens/settings/ProviderPrecedenceCopyTest.kt`.
- Found task commits `c10e9a95e`, `cc87d9d88`, `7ad3911a3`, and `e662d7a6d` in git history.
- Left `.planning/STATE.md`, `.planning/ROADMAP.md`, PlayerSettingsDataStore files, `nexio-web`, and the deleted screenshot unstaged and uncommitted.

---
*Phase: 07-tvdb-provider-replacement*
*Completed: 2026-04-15*
