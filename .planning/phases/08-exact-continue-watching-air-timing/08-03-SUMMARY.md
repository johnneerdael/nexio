---
phase: 08-exact-continue-watching-air-timing
plan: 03
subsystem: continue-watching
tags: [kotlin, android-tv, continue-watching, tvdb-air-timing, snapshot-store]

requires:
  - phase: 08-exact-continue-watching-air-timing
    provides: Exact TVDB availability fields on TrackingNextUpEntry and AirDateGate exact-instant overloads
provides:
  - Schema-versioned persistence for scheduledReemit rows and TVDB availability diagnostics
  - Exact availability propagation through Continue Watching timeline refs
  - Home and Android TV Continue Watching surfaces using the shared AirDateGate exact path
  - Regression tests for persistence, exact next-up withholding, resume visibility, and Android TV feed exclusion
affects: [08-exact-continue-watching-air-timing, continue-watching, android-tv-feed, snapshot-persistence]

tech-stack:
  added: []
  patterns:
    - Snapshot schema bump with explicit Gson object fields
    - File-local Android TV feed helper for direct Continue Watching feed tests
    - ContinueWatchingNextUpRef as the shared exact-air-time gate carrier

key-files:
  created:
    - app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogServiceContinueWatchingTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt
    - app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt
    - app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingTimeline.kt
    - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt
    - app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt
    - app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt
    - app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingTimelineAirDateTest.kt
    - app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogServiceContinueWatchingTest.kt

key-decisions:
  - "Persist scheduledReemit in the existing ContinueWatchingSnapshotStore schema rather than adding a separate withheld-row store."
  - "Keep Android TV feed behavior sourced from visible snapshot rows only; scheduledReemit rows are not converted to feed placeholders."
  - "Use ContinueWatchingNextUpRef.availabilityInstantMs as the common exact-timing carrier for timeline, Home, snapshot, and Android TV feed surfaces."

patterns-established:
  - "Persist optional TVDB timing diagnostics only when present or non-default."
  - "When scheduling withheld rows, use TVDB availability instants before provider first-aired values."

requirements-completed: [AIR-03, AIR-04, AIR-05, AIR-06]

duration: 13 min
completed: 2026-04-15
---

# Phase 08 Plan 03: Exact Continue Watching Surface Persistence Summary

**Scheduled exact-timing rows now persist across snapshots and all Continue Watching surfaces use the shared TVDB availability gate**

## Performance

- **Duration:** 13 min
- **Started:** 2026-04-15T11:38:26Z
- **Completed:** 2026-04-15T11:51:46Z
- **Tasks:** 3
- **Files modified:** 8

## Accomplishments

- Added Wave 0 tests for scheduled re-emit persistence, exact future next-up withholding, resume-row visibility, and Android TV feed exclusion of withheld rows.
- Bumped `ContinueWatchingSnapshotStore` schema to 5 and persisted `scheduledReemit` plus TVDB timing fields with defensive enum decoding.
- Added `availabilityInstantMs` to `ContinueWatchingNextUpRef` and wired it through snapshot, timeline, Home, and Android TV feed ref builders.
- Extracted `buildContinueWatchingItemsForAndroidTvFeed` so Android TV feed behavior can be tested without constructing the full service.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add Wave 0 persistence and surface gating tests** - `6b62d380c` and `522c734af` (test)
2. **Task 2: Persist scheduledReemit and TVDB timing fields** - `6594e25b8` (feat)
3. **Task 3: Pass exact availability through timeline, Home, and Android TV feed refs** - `c0321635b` (feat)

**Plan metadata:** committed separately with this summary.

## Files Created/Modified

- `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogServiceContinueWatchingTest.kt` - Android TV feed regression for excluding scheduled re-emit rows.
- `app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt` - Persistence regression for scheduled re-emit and TVDB timing fields.
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingTimelineAirDateTest.kt` - Exact future next-up gating and resume-row visibility regressions.
- `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt` - Schema 5 persistence for scheduled withheld rows and timing diagnostics.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingTimeline.kt` - Shared exact availability ref and main-feed filter.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt` - Exact gate use for visible rows, scheduled rows, and soonest re-emit scheduling.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` - Home next-up refs and `hasAired` use TVDB exact availability.
- `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt` - Android TV Continue Watching feed delegates to a testable helper and passes exact availability refs.

## Decisions Made

- Kept detail screen files untouched to preserve AIR-06: TV detail episode lists remain outside the Continue Watching gate.
- Preserved the pre-existing unowned `reloadPersistedSnapshotForActiveProfile` helper in `ContinueWatchingSnapshotService.kt` and excluded it from the 08-03 commit by staging only owned hunks.
- Used `TvdbAirAvailabilityPrecision.UNKNOWN` and nullable diagnostic reason fallback for malformed persisted enum values.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Made RED tests compile under Kotlin source-set compilation**
- **Found during:** Task 1
- **Issue:** Gradle compiles the whole unit-test source set even when `--tests` targets one class. Initial RED tests referenced not-yet-existing production symbols, blocking Task 2 store-only verification.
- **Fix:** Adjusted the RED tests so they compile against current code and fail by behavior/runtime until the planned production wiring exists.
- **Files modified:** `AndroidTvFeedCatalogServiceContinueWatchingTest.kt`, `ContinueWatchingTimelineAirDateTest.kt`
- **Verification:** `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest"` passed after Task 2.
- **Committed in:** `522c734af`

**2. [Rule 2 - Missing Critical] Scheduled re-emit timing now uses exact TVDB availability**
- **Found during:** Task 3
- **Issue:** The plan explicitly required exact timing to survive restart; leaving `scheduleReemitIfNeeded` on provider `firstAiredMs` would reschedule withheld rows at the wrong instant after persistence.
- **Fix:** Passed `availabilityInstantMsSelector = { it.tvdbAvailabilityInstantMs }` into `AirDateGate.soonestPendingMs`.
- **Files modified:** `ContinueWatchingSnapshotService.kt`
- **Verification:** Source acceptance confirmed the exact selector is wired; full targeted Gradle verification in the main worktree is blocked by unowned concurrent 08-02 dirty files.
- **Committed in:** `c0321635b`

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 missing critical)
**Impact on plan:** Both changes preserve the planned behavior and avoid scope expansion.

## Issues Encountered

- Concurrent 08-02 work staged/committed between Task 1 commits. The git history was repaired so 08-02 remained intact and 08-03 test adjustments landed in a separate 08-03 commit.
- Main worktree verification was briefly blocked by unowned 08-02 dirty files. After 08-02 advanced, the full targeted 08-03 Gradle command passed.

## Known Stubs

None. Stub-pattern scan found only intentional nullable/default constructor values and test fixtures.

## Threat Flags

None. The plan touched persisted snapshot JSON and existing Continue Watching display paths covered by the plan threat model; it added no network endpoint, auth path, file-access boundary, or detail-screen gate.

## Verification

Passed:

```sh
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest"
```

Source acceptance checks passed for:

- `ContinueWatchingSnapshotStore.kt` contains `SCHEMA_VERSION = 5`, `scheduledReemit`, TVDB timing fields, and defensive enum parsing.
- `ContinueWatchingTimeline.kt` contains `availabilityInstantMs: Long? = null` and passes `availabilityInstantMs = ref.availabilityInstantMs` into `AirDateGate`.
- `HomeViewModelContinueWatching.kt` and `AndroidTvFeedCatalogService.kt` contain `tvdbAvailabilityInstantMs`.
- `AndroidTvFeedCatalogService.kt` does not contain `scheduledReemit.map`.
Full targeted plan verification passed:

```sh
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest" --tests "com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest" --tests "com.nexio.tv.core.recommendations.AndroidTvFeedCatalogServiceContinueWatchingTest"
```

- `git diff -- app/src/main/java/com/nexio/tv/ui/screens/detail` is empty.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 08-04 can use persisted `scheduledReemit` rows and `AirDateGate.soonestPendingMs` exact selectors to add durable Android scheduling.

## Self-Check: PASSED

- Verified summary file exists at `.planning/phases/08-exact-continue-watching-air-timing/08-03-SUMMARY.md`.
- Verified task commits exist in git history: `6b62d380c`, `522c734af`, `6594e25b8`, and `c0321635b`.
- Verified no 08-03-owned staged files remain after task commits.

---
*Phase: 08-exact-continue-watching-air-timing*
*Completed: 2026-04-15*
