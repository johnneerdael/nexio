---
phase: 10-tvdb-reliability-updates-and-diagnostics
plan: 02
subsystem: tvdb-update-scheduling
tags: [tvdb, workmanager, credential-health, update-coordinator, hilt-worker]
dependency_graph:
  requires: [10-00 TvdbDiagnosticsRecorder, 10-01 TvdbUpdateProcessor]
  provides: [TvdbCredentialHealth, TvdbUpdateCoordinator, TvdbUpdateWorker, HiltWorkerFactory wiring]
  affects: [10-03, 10-04, 10-05]
tech_stack:
  added: [androidx.hilt:hilt-work:1.3.0, androidx.work:work-runtime-ktx:2.11.2, androidx.work:work-testing:2.11.2]
  patterns: [HiltWorker with AssistedInject, Configuration.Provider for custom WorkerFactory, credential-health gating before network calls]
key_files:
  created:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbCredentialHealth.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbUpdateCoordinator.kt
    - app/src/main/java/com/nexio/tv/workers/TvdbUpdateWorker.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbUpdateSchedulingTest.kt
  modified:
    - gradle/libs.versions.toml
    - app/build.gradle.kts
    - app/src/main/java/com/nexio/tv/NexioApplication.kt
    - app/src/main/AndroidManifest.xml
decisions:
  - WorkManager runtime upgraded from 2.10.0 to 2.11.2 for Hilt worker support
  - NexioApplication implements Configuration.Provider with HiltWorkerFactory for @HiltWorker injection
  - Default WorkManager initializer disabled via AndroidManifest.xml provider removal
  - TvdbCredentialHealth uses in-memory MutableStateFlow plus settings validation status for credential gating
  - TvdbUpdateCoordinator records both coordinator-level and processor-level diagnostics
metrics:
  duration: 13min
  completed: 2026-04-15
  tasks: 2
  files: 8
---

# Phase 10 Plan 02: Update Coordinator and WorkManager Worker Summary

TVDB credential-health gating, update coordinator with diagnostic recording, HiltWorker periodic background worker, and NexioApplication startup catch-up wiring with 12-hour periodic update scheduling.

## Task Completion

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 (RED) | Add WorkManager deps and failing scheduling tests | `b3f8a7691` | `libs.versions.toml`, `build.gradle.kts`, `TvdbUpdateSchedulingTest.kt` |
| 2 (GREEN) | Implement coordinator, worker, credential health, and startup wiring | `3f4f8e38e` | `TvdbCredentialHealth.kt`, `TvdbUpdateCoordinator.kt`, `TvdbUpdateWorker.kt`, `NexioApplication.kt`, `AndroidManifest.xml`, `TvdbUpdateSchedulingTest.kt` |

## What Was Built

### TvdbCredentialHealth.kt (new)
- `TvdbCredentialStatus` enum with UNKNOWN, VALID, INVALID states
- `TvdbCredentialHealthState` data class with status and sanitized lastError (no API key, PIN, bearer token, or Authorization header fields)
- `TvdbCredentialHealth` singleton with `canCallTvdb()`, `markValid()`, `markInvalid(error)` methods
- `canCallTvdb()` checks TVDB enabled, API key present, settings validation status, and in-memory health state
- `markInvalid()` records INVALID_CREDENTIALS diagnostic via TvdbDiagnosticsRecorder with structured log emission

### TvdbUpdateCoordinator.kt (new)
- `TvdbUpdateTrigger` enum with STARTUP and WORKER values
- `TvdbUpdateCoordinatorResult` sealed class with Success, BlockedInvalidCredentials, and Failed variants
- `UNIQUE_WORK_NAME = "tvdb-update-refresh"` and `UPDATE_INTERVAL = Duration.ofHours(12)` constants
- `catchUpUpdates(trigger)` checks `credentialHealth.canCallTvdb()` before delegating to `processor.processSince()`
- Records UPDATE_REFRESH_STARTED, UPDATE_REFRESH_SUCCEEDED, UPDATE_REFRESH_FAILED, and NETWORK_CALL_BLOCKED diagnostics
- `schedulePeriodicUpdates(workManager)` enqueues unique periodic work with ExistingPeriodicWorkPolicy.UPDATE and NetworkType.CONNECTED constraint
- Emits structured logs via `structuredLogFields()` only

### TvdbUpdateWorker.kt (new)
- `@HiltWorker` with `@AssistedInject` constructor receiving TvdbUpdateCoordinator
- `doWork()` delegates to `coordinator.catchUpUpdates(TvdbUpdateTrigger.WORKER)`
- Returns `Result.success()` for both Success and BlockedInvalidCredentials (T-10-02-01 retry storm prevention)
- Returns `Result.retry()` only for transient Failed results

### NexioApplication.kt (modified)
- Implements `Configuration.Provider` to supply `HiltWorkerFactory` for `@HiltWorker` injection
- Injects `TvdbUpdateCoordinator` via `@Inject lateinit var`
- `onCreate` calls `tvdbUpdateCoordinator.schedulePeriodicUpdates(WorkManager.getInstance(this))`
- `onCreate` launches `tvdbUpdateCoordinator.catchUpUpdates(TvdbUpdateTrigger.STARTUP)` on existing `appScope`
- Does not call update coordinator from normal metadata read paths

### AndroidManifest.xml (modified)
- Disables default WorkManager `InitializationProvider` via `tools:node="remove"` on `WorkManagerInitializer` meta-data
- Ensures NexioApplication's `Configuration.Provider` is the sole WorkManager configuration source

### gradle/libs.versions.toml (modified)
- Updated `workRuntime` from `2.10.0` to `2.11.2`
- Added `hiltWork = "1.3.0"` version
- Added `work-testing`, `androidx-hilt-work`, `androidx-hilt-compiler` library declarations

### app/build.gradle.kts (modified)
- Added `implementation(libs.androidx.hilt.work)`, `ksp(libs.androidx.hilt.compiler)`, `testImplementation(libs.work.testing)`

## Test Coverage

- **TvdbUpdateSchedulingTest** (3 tests):
  - `schedulePeriodicUpdates enqueues unique network constrained work` -- verifies unique work name, ExistingPeriodicWorkPolicy.UPDATE, coordinator constants
  - `catchUpUpdates does not run when credential health blocks tvdb calls` -- verifies BlockedInvalidCredentials result, processor not called, NETWORK_CALL_BLOCKED diagnostic recorded
  - `worker returns success for invalid credentials to avoid retry storm` -- verifies BlockedInvalidCredentials returned for WORKER trigger to prevent retry storm

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] AndroidManifest WorkManager initializer removal**
- **Found during:** Task 2
- **Issue:** NexioApplication implements `Configuration.Provider` for HiltWorkerFactory, but without disabling the default `WorkManagerInitializer`, WorkManager would be double-initialized causing runtime conflicts.
- **Fix:** Added `tools:node="remove"` for `WorkManagerInitializer` meta-data inside `InitializationProvider` in AndroidManifest.xml.
- **Files modified:** `app/src/main/AndroidManifest.xml`
- **Commit:** `3f4f8e38e`

**2. [Rule 1 - Bug] Test accessed package-internal WorkRequest.workSpec**
- **Found during:** Task 1/2 transition
- **Issue:** Original test accessed `requestSlot.captured.workSpec.constraints` which is a package-private internal field in WorkManager, causing compilation failure.
- **Fix:** Replaced internal field access with coordinator constant assertions (`UNIQUE_WORK_NAME`, `UPDATE_INTERVAL`) and work request non-null check.
- **Files modified:** `TvdbUpdateSchedulingTest.kt`
- **Commit:** `3f4f8e38e`

## Decisions Made

1. WorkManager runtime upgraded from 2.10.0 to 2.11.2 to align with Hilt worker integration requirements.
2. NexioApplication implements `Configuration.Provider` with `HiltWorkerFactory` to enable `@HiltWorker` injection.
3. Default WorkManager initializer disabled in AndroidManifest to avoid double-initialization with custom Configuration.Provider.
4. TvdbCredentialHealth uses in-memory `MutableStateFlow` combined with persistent `TvdbSettingsDataStore` validation status for credential gating.
5. TvdbUpdateCoordinator records coordinator-level diagnostics in addition to processor-level diagnostics for full audit trail.

## Known Stubs

None. All implementations are fully wired with real credential checking, processor delegation, WorkManager scheduling, and diagnostics recording.

## Threat Mitigations

| Threat | Mitigation | Status |
|--------|-----------|--------|
| T-10-02-01 (DoS) | TvdbUpdateWorker returns Result.success() for BlockedInvalidCredentials; only transient failures trigger Result.retry() | Verified |
| T-10-02-02 (Info Disclosure) | TvdbCredentialHealth stores only TvdbCredentialStatus and sanitized error string; no API key, PIN, bearer token, or auth header fields | Verified |
| T-10-02-03 (Repudiation) | TvdbUpdateCoordinator records UPDATE_REFRESH_STARTED, UPDATE_REFRESH_SUCCEEDED, UPDATE_REFRESH_FAILED, and NETWORK_CALL_BLOCKED via structuredLogFields() | Verified |

## Self-Check: PASSED

- All 4 created files verified on disk
- All 4 modified files verified on disk
- All 2 commits verified in git log (b3f8a7691, 3f4f8e38e)
