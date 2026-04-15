---
phase: 08-exact-continue-watching-air-timing
plan: 06
subsystem: continue-watching
tags: [kotlin, android-tv, continue-watching, tvdb-air-timing, scheduling]

requires:
  - phase: 08-exact-continue-watching-air-timing
    provides: Persisted scheduledReemit rows and direct overdue exact reschedule handling from Plans 08-01 through 08-05
provides:
  - Shared AirDateGate trigger extraction for exact TVDB, provider firstAiredMs, and parsed firstAired date rows
  - Immediate refresh-first handling for overdue persisted-load scheduledReemit rows
  - Immediate refresh-first handling for overdue provider-ms and date-only fallback rows during boot/exact-permission reschedule
affects: [08-exact-continue-watching-air-timing, continue-watching, android-tv-scheduling]

tech-stack:
  added: []
  patterns:
    - Shared pending trigger extraction before due/future scheduled-reemit decisions
    - Restore and reschedule paths using one refresh-first scheduled-reemit handler

key-files:
  created:
    - .planning/phases/08-exact-continue-watching-air-timing/08-06-SUMMARY.md
  modified:
    - app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt
    - app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt
    - app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt

key-decisions:
  - "Centralized scheduled-reemit trigger priority in AirDateGate so exact TVDB, provider-ms, and date-only fallback rows share the same due/future interpretation."
  - "Kept refresh-first behavior: due scheduledReemit rows request ensureFresh(force = true) and are never copied directly into visible nextUpItems."
  - "Left WR-03 diagnostic/privacy logging untouched because the gap-closure plan explicitly scoped it out."

patterns-established:
  - "AirDateGate.pendingTriggerMs provides the single priority order for exact TVDB, provider firstAiredMs, and parsed firstAired date triggers."
  - "ContinueWatchingSnapshotService.handleScheduledReemit checks due rows first, then delegates future rows to scheduleReemitIfNeeded."

requirements-completed: [AIR-04, AIR-05]

duration: 3 min
completed: 2026-04-15
---

# Phase 08 Plan 06: Scheduled Re-emit Due Restore Summary

**Persisted Continue Watching scheduledReemit rows now refresh immediately when exact, provider-ms, or date-only fallback triggers are already due**

## Performance

- **Duration:** 3 min
- **Started:** 2026-04-15T14:38:16Z
- **Completed:** 2026-04-15T14:41:41Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- Added RED regressions for persisted-load overdue exact rows, provider-ms fallback rows, and date-only fallback rows.
- Added `AirDateGate.pendingTriggerMs(...)` and `AirDateGate.hasDuePending(...)` so due and future checks share one trigger priority.
- Routed both persisted snapshot restore and `rescheduleAirTimeAlarmFromSnapshot()` through `handleScheduledReemit(...)`.
- Preserved D-16 refresh-first behavior: withheld rows stay in `scheduledReemit` until provider refresh rebuilds the visible snapshot.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add regressions for restore and fallback due rows** - `3a9ec348d` (test)
2. **Task 2: Centralize scheduled-reemit trigger handling** - `182685933` (fix)

**Plan metadata:** committed separately with this summary.

## Files Created/Modified

- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt` - Adds the three gap regressions and helper support for persisted-store/date fixtures.
- `app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt` - Adds shared trigger extraction and due detection, and reuses it from future scheduling.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt` - Uses one scheduled-reemit handler from persisted restore and reschedule paths.
- `.planning/phases/08-exact-continue-watching-air-timing/08-06-SUMMARY.md` - Records gap closure, verification, and commits.

## Decisions Made

- Kept trigger priority identical to the existing visibility gate: positive TVDB availability instant first, positive provider `firstAiredMs` second, parsed `firstAired` date third.
- Treated due rows as a refresh request only; no code moves `scheduledReemit` entries into visible `nextUpItems`.
- Did not update `.planning/STATE.md` or `.planning/ROADMAP.md` because the orchestrator owns shared state writes for this execution.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- RED verification failed as expected before production changes: all three new regressions timed out waiting for `refreshCount` because restore/reschedule due paths did not call `refreshNow()`.
- Gradle repeated existing 32-bit native library and Moshi Kapt deprecation warnings during verification. The targeted test commands exited successfully after the fix.
- The worktree contained unrelated dirty files before and after execution. They were not staged or committed.

## Known Stubs

None. Stub-pattern scan only found existing Kotlin nullable defaults, empty snapshot defaults, and test fixture nulls; no placeholder UI data or unconnected mock data source was introduced.

## Threat Flags

None. No new endpoint, auth path, file access pattern, schema change, or new trust-boundary surface was introduced. The planned persisted snapshot to refresh-decision boundary is mitigated by one refresh branch per restore/reschedule call and by preserving withheld rows.

## Verification

RED evidence:

```sh
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest.reloadPersistedSnapshotForActiveProfile refreshes overdue exact scheduled reemit from persisted load" --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest.rescheduleAirTimeAlarmFromSnapshot refreshes overdue provider-ms scheduled reemit without exact instant" --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest.rescheduleAirTimeAlarmFromSnapshot refreshes overdue date-only scheduled reemit without exact instant"
```

Result before the fix: build failed with 3 failed tests, each waiting for `refreshCount` to change.

Passed after the fix:

```sh
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest.reloadPersistedSnapshotForActiveProfile refreshes overdue exact scheduled reemit from persisted load" --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest.rescheduleAirTimeAlarmFromSnapshot refreshes overdue provider-ms scheduled reemit without exact instant" --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest.rescheduleAirTimeAlarmFromSnapshot refreshes overdue date-only scheduled reemit without exact instant"
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.AirDateGateTest" --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest"
```

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

The remaining Phase 8 AIR-04/AIR-05 verifier blockers are closed in source and regression coverage. Persisted-load restore and boot/exact-permission reschedule now refresh overdue exact, provider-ms, and date-only scheduledReemit rows; future rows still use the existing soonest-only scheduler path.

## Self-Check: PASSED

- Verified summary file exists at `.planning/phases/08-exact-continue-watching-air-timing/08-06-SUMMARY.md`.
- Verified modified owned files exist: `AirDateGate.kt`, `ContinueWatchingSnapshotService.kt`, and `ContinueWatchingSnapshotServiceMutationTest.kt`.
- Verified task commits exist in git history: `3a9ec348d` and `182685933`.
- Verified targeted RED regressions failed before the fix and passed after the fix.
- Verified final plan command passed: `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.AirDateGateTest" --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest"`.

---
*Phase: 08-exact-continue-watching-air-timing*
*Completed: 2026-04-15*
