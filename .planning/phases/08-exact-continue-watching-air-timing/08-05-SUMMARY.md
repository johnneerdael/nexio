---
phase: 08-exact-continue-watching-air-timing
plan: 05
subsystem: continue-watching
tags: [kotlin, android-tv, continue-watching, tvdb-air-timing, scheduling]

requires:
  - phase: 08-exact-continue-watching-air-timing
    provides: Persisted scheduledReemit rows, durable air-time reschedule paths, and refresh-first scheduled re-emit behavior from Plans 08-01 through 08-04
provides:
  - Immediate forced refresh for overdue persisted TVDB scheduledReemit rows during restart, boot, and exact-permission reschedule
  - Regression coverage proving overdue persisted rows are not directly moved into visible Continue Watching rows
  - Shared refresh-failure retry path for timer-fired and overdue restore-triggered air-time refreshes
affects: [08-exact-continue-watching-air-timing, continue-watching, android-tv-scheduling]

tech-stack:
  added: []
  patterns:
    - Exact TVDB overdue detection before future-only AirDateGate scheduling
    - Refresh-first persisted re-emit restore path using ensureFresh(force = true)

key-files:
  created:
    - .planning/phases/08-exact-continue-watching-air-timing/08-05-SUMMARY.md
  modified:
    - app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt
    - app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt

key-decisions:
  - "Keep overdue detection scoped to positive TVDB exact availability instants; non-TVDB/date-only warning items remain outside this gap closure."
  - "Preserve D-16 refresh-first behavior: reschedule paths request a provider refresh and never reveal scheduledReemit rows directly."

patterns-established:
  - "rescheduleAirTimeAlarmFromSnapshot handles due exact persisted rows before delegating future rows to scheduleReemitIfNeeded."
  - "Timer and restore-triggered refreshes share the same refresh_failure diagnostic and 15 minute retry behavior."

requirements-completed: [AIR-04]

duration: 3 min
completed: 2026-04-15
---

# Phase 08 Plan 05: Overdue Persisted Re-emit Restore Summary

**Overdue persisted TVDB scheduledReemit rows now force an immediate provider refresh during reschedule without revealing stale withheld rows**

## Performance

- **Duration:** 3 min
- **Started:** 2026-04-15T14:01:44Z
- **Completed:** 2026-04-15T14:04:45Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- Added a RED regression for a persisted `scheduledReemit` row whose `tvdbAvailabilityInstantMs` is already due.
- Updated `ContinueWatchingSnapshotService.rescheduleAirTimeAlarmFromSnapshot()` to force `ensureFresh(force = true)` for overdue exact TVDB rows before using the future-only scheduler.
- Kept refresh-first behavior intact: overdue persisted rows remain withheld until the provider refresh rebuilds the snapshot.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add overdue persisted re-emit regression test** - `fa7b09c1a` (test)
2. **Task 2: Force refresh due persisted re-emit rows during reschedule** - `5bd5ba503` (feat)

**Plan metadata:** committed separately with this summary.

## Files Created/Modified

- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt` - Adds the overdue persisted scheduled re-emit restore regression.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt` - Detects due positive TVDB exact instants, cancels stale scheduling state, and launches the forced refresh retry path.
- `.planning/phases/08-exact-continue-watching-air-timing/08-05-SUMMARY.md` - Records gap closure, verification, and commits.

## Decisions Made

- Kept the detection exact-TVDB scoped with `tvdbAvailabilityInstantMs != null && tvdbAvailabilityInstantMs > 0L && tvdbAvailabilityInstantMs <= nowMs`.
- Reused the existing `refresh_failure` diagnostic text and retry interval for both timer-fired refreshes and overdue restore-triggered refreshes.
- Left WR-02, WR-03, WR-04, and WR-05 untouched because they are nonblocking review warnings outside this gap-closure scope.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- Gradle repeated the existing 32-bit native library and Moshi Kapt deprecation warnings during verification; both targeted test commands exited successfully.
- The worktree had unrelated dirty state in `.planning/STATE.md`, `nexio-web`, and untracked `profile2*.png` screenshots after verification. These were not staged, modified, or committed for this plan.

## Known Stubs

None. Stub-pattern scan only found existing nullable/default Kotlin state and test fixture nulls; no hardcoded empty UI data or placeholder behavior was introduced.

## Threat Flags

None. No new endpoint, auth path, file access pattern, schema change, or trust-boundary surface was introduced. The planned persisted snapshot to refresh-decision boundary remains mitigated by one refresh launch per reschedule call and by never moving `scheduledReemit` directly into visible rows.

## Verification

Passed:

```sh
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest.rescheduleAirTimeAlarmFromSnapshot refreshes immediately for overdue persisted scheduled reemit"
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest"
```

RED evidence:

```sh
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest.rescheduleAirTimeAlarmFromSnapshot refreshes immediately for overdue persisted scheduled reemit"
```

Failed before the service change at `ContinueWatchingSnapshotServiceMutationTest.kt:203`, where `refreshCount == 1` was not reached.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

The Phase 8 verifier blocker is closed for missed restart, boot, and exact-permission reschedule paths. Future persisted rows still use the existing soonest-only scheduler path, while due persisted TVDB exact rows force a provider refresh immediately.

## Self-Check: PASSED

- Verified summary file exists at `.planning/phases/08-exact-continue-watching-air-timing/08-05-SUMMARY.md`.
- Verified modified owned files exist: `ContinueWatchingSnapshotService.kt` and `ContinueWatchingSnapshotServiceMutationTest.kt`.
- Verified task commits exist in git history: `fa7b09c1a` and `5bd5ba503`.
- Verified targeted regression and full `ContinueWatchingSnapshotServiceMutationTest` commands passed after the service fix.

---
*Phase: 08-exact-continue-watching-air-timing*
*Completed: 2026-04-15*
