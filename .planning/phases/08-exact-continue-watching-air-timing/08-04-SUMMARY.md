---
phase: 08-exact-continue-watching-air-timing
plan: 04
subsystem: continue-watching
tags: [kotlin, android-tv, alarm-manager, continue-watching, tvdb-air-timing]

requires:
  - phase: 08-exact-continue-watching-air-timing
    provides: Persisted scheduledReemit rows, TVDB exact availability fields, and exact AirDateGate soonest selector behavior from Plans 08-01 through 08-03
provides:
  - AlarmManager-backed durable Continue Watching air-time scheduler
  - Non-exported alarm receiver for re-evaluation, boot, and exact-alarm permission reschedule paths
  - Snapshot service integration that schedules only the soonest withheld TVDB availability instant
  - Refresh-failure diagnostics and 15 minute durable retry without revealing withheld rows
affects: [08-exact-continue-watching-air-timing, continue-watching, android-tv-scheduling]

tech-stack:
  added: []
  patterns:
    - Android AlarmManager wrapper behind a ContinueWatchingAirScheduler interface
    - Package-scoped explicit PendingIntent with non-exported BroadcastReceiver
    - Refresh-first scheduled re-emit path with durable retry on provider refresh failure

key-files:
  created:
    - app/src/main/java/com/nexio/tv/core/di/ContinueWatchingSchedulerModule.kt
    - app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmReceiver.kt
    - app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmScheduler.kt
    - app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirScheduler.kt
    - app/src/test/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmSchedulerTest.kt
  modified:
    - app/src/main/AndroidManifest.xml
    - app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt
    - app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt

key-decisions:
  - "Use exact alarms when allowed and inexact allow-while-idle alarms with diagnostics when exact alarm permission is unavailable."
  - "Receiver-triggered re-evaluation always refreshes provider next-up state instead of directly moving scheduledReemit rows into visible rails."
  - "Keep a no-op scheduler constructor default for existing direct unit-test construction while production Hilt binding provides the AlarmManager scheduler."

patterns-established:
  - "ContinueWatchingSnapshotService.scheduleReemitIfNeeded drives both the coroutine timer and durable scheduler from the same exact soonest selector."
  - "Refresh failure leaves scheduledReemit intact, clears the in-memory target, logs a reason-only diagnostic, and schedules a 15 minute retry."

requirements-completed: [AIR-04, AIR-05]

duration: 10 min
completed: 2026-04-15
---

# Phase 08 Plan 04: Durable Continue Watching Air Scheduling Summary

**AlarmManager-backed exact-air-time re-evaluation refreshes provider state at the soonest withheld TVDB instant and retries refresh failures without revealing stale rows**

## Performance

- **Duration:** 10 min
- **Started:** 2026-04-15T11:55:24Z
- **Completed:** 2026-04-15T12:04:57Z
- **Tasks:** 3
- **Files modified:** 8

## Accomplishments

- Added Wave 0 scheduler and snapshot retry tests for exact alarms, inexact fallback, cancellation, package-scoped PendingIntents, exact soonest selection, and refresh-failure retry.
- Added `ContinueWatchingAirScheduler`, an AlarmManager implementation, Hilt scheduler modules, manifest permissions, and a non-exported receiver.
- Integrated durable scheduling into `ContinueWatchingSnapshotService` so persisted `scheduledReemit` rows reschedule on restore/boot, trigger provider refresh, and retry failures after 15 minutes.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add Wave 0 scheduler and retry tests** - `2e8766703` (test)
2. **Task 2: Implement AlarmManager scheduler and non-exported receiver** - `2d33ea5bf` (feat)
3. **Task 3: Integrate scheduler with snapshot re-emit and refresh retry** - `f0f129305` (feat)

**Plan metadata:** committed separately with this summary.

## Files Created/Modified

- `app/src/test/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmSchedulerTest.kt` - New Robolectric/MockK scheduler tests for exact/inexact/cancel/package-scoped alarm behavior.
- `app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirScheduler.kt` - Scheduler abstraction used by the snapshot service.
- `app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmScheduler.kt` - AlarmManager implementation with exact permission branch, inexact fallback diagnostic, and immutable package-scoped PendingIntent.
- `app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmReceiver.kt` - Hilt receiver for re-evaluate, boot, and exact-alarm permission state broadcasts.
- `app/src/main/java/com/nexio/tv/core/di/ContinueWatchingSchedulerModule.kt` - Hilt providers/bindings for AlarmManager and the scheduler interface.
- `app/src/main/AndroidManifest.xml` - Adds boot/exact-alarm permissions and a non-exported Continue Watching alarm receiver.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt` - Schedules durable alarms, cancels them on snapshot clears, restores from persisted scheduled rows, and retries refresh failures.
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt` - Adds exact soonest scheduling and refresh-failure retry coverage.

## Decisions Made

- Used `SCHEDULE_EXACT_ALARM` plus `canScheduleExactAlarms()` fallback rather than adding a user-facing special-access flow in this plan.
- Kept diagnostics reason-only for scheduler degradation and refresh failure; logs include mode/trigger or retry interval but no provider payloads or credentials.
- Added a no-op scheduler default only for direct constructor compatibility in existing unit tests; production Hilt uses `ContinueWatchingAirAlarmScheduler`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added receiver reschedule entry point during Task 2**
- **Found during:** Task 2 (AlarmManager scheduler and receiver)
- **Issue:** The planned receiver references `ContinueWatchingSnapshotService.rescheduleAirTimeAlarmFromSnapshot()`, but that method was scheduled for Task 3. Without a narrow stub, Task 2 could not compile or run the scheduler tests.
- **Fix:** Added the public reschedule method early, delegating to the existing `scheduleReemitIfNeeded` path. Task 3 then completed the durable scheduler integration behind it.
- **Files modified:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- **Verification:** `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.scheduler.ContinueWatchingAirAlarmSchedulerTest"` passed.
- **Committed in:** `2d33ea5bf`

**2. [Rule 3 - Blocking] Preserved existing direct service constructors**
- **Found during:** Task 3 (snapshot scheduler injection)
- **Issue:** Existing unit tests construct `ContinueWatchingSnapshotService` directly outside Hilt. Requiring a new scheduler argument would have forced unrelated test edits outside this plan's owned file list.
- **Fix:** Added a private no-op scheduler default for direct construction while keeping the production Hilt binding to `ContinueWatchingAirAlarmScheduler`.
- **Files modified:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- **Verification:** Targeted mutation tests and `./gradlew assembleArm64Debug` passed with Hilt compilation.
- **Committed in:** `f0f129305`

---

**Total deviations:** 2 auto-fixed (2 blocking)
**Impact on plan:** Both fixes were required to keep the planned receiver/service integration buildable without touching unrelated files. No behavior outside durable Continue Watching scheduling was added.

## Issues Encountered

- The worktree had pre-existing/unrelated dirty state in `.planning/STATE.md`, `.planning/ROADMAP.md`, `nexio-web`, and later unrelated app sync files. Those files were not staged or committed for this plan.
- Gradle emitted existing native-library and deprecation warnings during verification; both required commands exited successfully.

## Known Stubs

None. Stub-pattern scan found only ordinary nullable/default Kotlin state and test fixture nulls. The no-op scheduler default is a direct-constructor compatibility path; production DI binds the AlarmManager implementation.

## Threat Flags

None. The new Android broadcast and alarm surfaces were planned in the threat model and mitigated with `android:exported="false"`, explicit package-scoped PendingIntents, refresh-first receiver behavior, and reason-only diagnostics.

## Verification

Passed:

```sh
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.scheduler.ContinueWatchingAirAlarmSchedulerTest" --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest"
./gradlew assembleArm64Debug
```

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Phase 8 now has exact TVDB availability calculation, persisted withheld rows, gated Continue Watching surfaces, and durable Android scheduling with retry behavior. The phase is ready for verification/UAT against real device sleep/reboot alarm behavior.

## Self-Check: PASSED

- Verified summary file exists at `.planning/phases/08-exact-continue-watching-air-timing/08-04-SUMMARY.md`.
- Verified created scheduler files exist: `ContinueWatchingAirScheduler.kt`, `ContinueWatchingAirAlarmScheduler.kt`, and `ContinueWatchingAirAlarmReceiver.kt`.
- Verified task commits exist in git history: `2e8766703`, `2d33ea5bf`, and `f0f129305`.
- Verified final targeted Gradle command and `assembleArm64Debug` passed after all task commits.

---
*Phase: 08-exact-continue-watching-air-timing*
*Completed: 2026-04-15*
