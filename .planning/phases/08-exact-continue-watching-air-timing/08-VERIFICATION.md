---
phase: 08-exact-continue-watching-air-timing
verified: 2026-04-15T14:49:30Z
status: human_needed
score: 14/14 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 13/14
  gaps_closed:
    - "Persisted-load restore now refreshes immediately when restored scheduledReemit rows are already due."
    - "Boot/exact-permission reschedule now refreshes overdue date-only/provider fallback rows with no TVDB exact instant."
  gaps_remaining: []
  regressions: []
human_verification:
  - test: "Real Android TV missed-trigger restore"
    expected: "A TVDB-backed withheld row appears after its computed airing instant when the app process was killed before the instant."
    why_human: "Unit tests cover restore logic, but not real device process death, alarm delivery, and provider refresh timing."
  - test: "Real Android TV reboot reschedule"
    expected: "A withheld row is rescheduled or refreshed after reboot and appears at or after the computed instant without a day-level refresh."
    why_human: "Boot receiver and AlarmManager behavior depend on Android device state."
  - test: "Android S+ exact-alarm denied fallback"
    expected: "Inexact fallback still refreshes withheld rows and the UI does not expose scheduler degradation on cards."
    why_human: "Permission state and AlarmManager delivery cannot be proven by source/unit tests alone."
  - test: "Phase 8 security verification"
    expected: "Receiver/export/PendingIntent/logging risks are explicitly evaluated in 08-SECURITY.md or equivalent."
    result: "passed: 08-SECURITY.md exists with status secured and threats_open 0."
---

# Phase 8: Exact Continue Watching Air Timing Verification Report

**Phase Goal:** Continue Watching shows TVDB-backed new episodes at their computed device-local airing instant instead of at the start of the release date
**Verified:** 2026-04-15T14:49:30Z
**Status:** human_needed
**Re-verification:** Yes - after gap closure plan 08-06

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | TVDB aired date plus series airsTime computes an exact availability instant using source-timezone policy | VERIFIED | `TvdbAirAvailabilityCalculator.computeAvailability` computes exact instants and returns TVDB precision/diagnostic data. |
| 2 | Device-local conversion is available while visibility gates compare exact instants | VERIFIED | The calculator exposes `deviceLocalDateTime`; `AirDateGate.isAired` compares epoch milliseconds so local-date drift cannot reveal rows early. |
| 3 | TVDB-backed next-up rows carry availability fields before snapshot gating | VERIFIED | `TvdbContinueWatchingTimingEnricher` copies exact/date-only availability onto `TrackingNextUpEntry`; `TrackingProgressService` wires the enricher into provider flows. |
| 4 | TVDB exact timing wins over Trakt/Simkl first-aired timing when present | VERIFIED | `AirDateGate.pendingTriggerMs`, `isAired`, and `soonestPendingMs` prefer positive `tvdbAvailabilityInstantMs` before provider timing. |
| 5 | Date-only fallback preserves existing gating and exposes diagnostic reason codes | VERIFIED | Date-only precision and diagnostics persist through `ContinueWatchingSnapshotStore`; 08-06 closes the missed restore/reschedule date-only gap. |
| 6 | TVDB timing enrichment does not add duplicate TMDB TV metadata calls | VERIFIED | The timing enricher uses `TvMetadataRouter` and `TvdbAirAvailabilityCalculator`, with no direct TMDB service dependency. |
| 7 | Future exact-timing rows are withheld from Continue Watching surfaces until available | VERIFIED | Snapshot, timeline, Home, and Android TV feed paths pass exact availability into `AirDateGate`. |
| 8 | Already-started resume rows remain visible regardless of future next-up timing | VERIFIED | Resume rows still bypass next-up air-date data and remain visible via the resume pipeline. |
| 9 | Withheld rows and timing diagnostics persist across process restart | VERIFIED | Store schema 5 writes and reads `scheduledReemit` plus TVDB exact instant, precision, source policy/zone, reason, and device-local diagnostic. |
| 10 | TV detail episode lists are not filtered by Continue Watching exact-air-time gating | VERIFIED | Phase 8 gating is scoped to Continue Watching snapshot/timeline/feed paths; detail-screen episode files were not wired to `AirDateGate`. |
| 11 | Only the soonest future withheld availability trigger is scheduled | VERIFIED | `scheduleReemitIfNeeded` calls `AirDateGate.soonestPendingMs` and schedules one target via `airScheduler.scheduleSoonest(soonestMs)`. |
| 12 | Durable re-evaluation survives missed restart/reboot reschedule paths | VERIFIED | 08-06 routes persisted restore and direct reschedule through `handleScheduledReemit`, which checks due rows before future scheduling. |
| 13 | Alarm firing refreshes tracking provider state instead of directly revealing stale rows | VERIFIED | Due rows call `launchAirTimeRefreshWithRetry`, which calls `ensureFresh(force = true)`; tests assert withheld rows remain in `scheduledReemit` and visible `nextUpItems` stays empty. |
| 14 | Refresh failure keeps withheld rows and retries with backoff while recording diagnostics | VERIFIED | `launchAirTimeRefreshWithRetry` logs `reason=refresh_failure retryMs=900000` and schedules a retry without moving withheld rows into visible rails. |

**Score:** 14/14 truths verified by source/test checks

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailability.kt` | Availability precision/result contract | VERIFIED | Enums/data classes exist, including `REFRESH_FAILURE`. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculator.kt` | Source-zone exact instant calculation | VERIFIED | Parses TVDB time formats, resolves source policy, computes exact instants, and returns fallback diagnostics. |
| `app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt` | Shared exact/date-only gate and trigger extraction | VERIFIED | `pendingTriggerMs`, `hasDuePending`, and `soonestPendingMs` share exact, provider-ms, then date-string priority. |
| `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt` | TVDB timing enrichment for next-up rows | VERIFIED WITH WARNING | Enrichment is wired. WR-03 remains a non-blocking diagnostic/privacy warning. |
| `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt` | Persist withheld rows and diagnostics | VERIFIED | Schema 5 encodes/decodes `scheduledReemit` and TVDB timing fields. |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt` | Snapshot gating, scheduling, restore, refresh/retry | VERIFIED | `loadPersistedSnapshotForActiveProfile` and `rescheduleAirTimeAlarmFromSnapshot` both use due-aware `handleScheduledReemit`. |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingTimeline.kt` | Exact timing carrier for timeline selection | VERIFIED | Timeline refs carry `availabilityInstantMs` into the shared gate. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` | Home surface uses exact gate fields | VERIFIED | Home next-up refs and `hasAired` include TVDB availability instants. |
| `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt` | Android TV feed excludes withheld rows | VERIFIED | Feed mapping uses visible snapshot rows and exact availability fields. |
| `app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirScheduler.kt` | Scheduler interface | VERIFIED | `scheduleSoonest` and `cancel` abstraction exists. |
| `app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmScheduler.kt` | AlarmManager implementation | VERIFIED FOR WIRING | Exact/inexact alarm branches are present; real device delivery remains human verification. |
| `app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmReceiver.kt` | Broadcast receiver | VERIFIED | Receiver calls refresh/reschedule entry points; Phase 8 security report is secured. |
| `app/src/main/AndroidManifest.xml` | Receiver and permissions | VERIFIED | Receiver and permissions are declared; Phase 8 security report is secured. |
| `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt` | 08-06 restore/fallback regressions | VERIFIED | Tests cover persisted-load exact due rows, provider-ms fallback due rows, and date-only fallback due rows. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `TrackingProgressService.kt` | `TvdbContinueWatchingTimingEnricher.kt` | Provider flow enrichment | WIRED | Trakt and Simkl next-up rows are enriched before snapshot gating. |
| `TvdbContinueWatchingTimingEnricher.kt` | `TvdbAirAvailabilityCalculator.kt` | `computeAvailability` | WIRED | Enricher computes availability from TVDB series/episode metadata. |
| `ContinueWatchingSnapshotService.kt` | `AirDateGate.hasDuePending` | Shared due detection before future-only scheduling | WIRED | Manual source check found `AirDateGate.hasDuePending` in `handleScheduledReemit`. The gsd key-link helper false-negatived the escaped pattern. |
| `ContinueWatchingSnapshotService.kt` | `TrackingProgressService.refreshNow` | `ensureFresh(force = true)` | WIRED | Due rows call `launchAirTimeRefreshWithRetry`, which calls `ensureFresh(force = true)` and then `refreshNow`. |
| `ContinueWatchingSnapshotService.kt` | `AirDateGate.soonestPendingMs` | Future-row fallback scheduling path | WIRED | If no due row exists, `handleScheduledReemit` delegates to `scheduleReemitIfNeeded` and `soonestPendingMs`. |
| `ContinueWatchingSnapshotStore.kt` | `ContinueWatchingSnapshotService.kt` | Persisted `scheduledReemit` restore | WIRED | Store reads rows; restore sanitizes the snapshot and passes `scheduledReemit` through `handleScheduledReemit`. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `TvdbContinueWatchingTimingEnricher.kt` | `tvdbAvailabilityInstantMs` and diagnostics | `TvMetadataRouter.fetchEnrichment` and `fetchEpisodeEnrichment` | Yes | FLOWING |
| `TrackingProgressService.kt` | Enriched next-up rows | Trakt/Simkl next-up flows through timing enricher | Yes | FLOWING |
| `ContinueWatchingSnapshotService.kt` | `scheduledReemit` | `buildRawSnapshot` filters unavailable next-up rows | Yes | FLOWING |
| `ContinueWatchingSnapshotStore.kt` | Persisted `scheduledReemit` and TVDB fields | Schema 5 JSON encode/decode | Yes | FLOWING |
| `ContinueWatchingSnapshotService.kt` | Overdue persisted exact restore | `snapshotStore.read()` to `handleScheduledReemit` to refresh | Yes | FLOWING |
| `ContinueWatchingSnapshotService.kt` | Overdue provider-ms/date-only fallback restore/reschedule | `AirDateGate.pendingTriggerMs` through `hasDuePending` | Yes | FLOWING |
| `ContinueWatchingSnapshotService.kt` | Future withheld scheduling | `AirDateGate.soonestPendingMs` to scheduler | Yes | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| 08-06 artifact verification | `node .../gsd-tools.cjs verify artifacts .planning/phases/08-exact-continue-watching-air-timing/08-06-PLAN.md` | 3/3 artifacts passed. | PASS |
| 08-06 key-link helper | `node .../gsd-tools.cjs verify key-links .planning/phases/08-exact-continue-watching-air-timing/08-06-PLAN.md` | 1/3 verified by helper; two escaped-pattern false negatives manually verified with source grep. | PASS WITH MANUAL CHECK |
| 08-06 source link check | `rg "AirDateGate\\.hasDuePending|ensureFresh\\(force = true\\)|soonestPendingMs|handleScheduledReemit" ...` | Found due detection, force refresh, future scheduling, and centralized handler. | PASS |
| 08-06 regression tests | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.AirDateGateTest" --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest"` | BUILD SUCCESSFUL in 2s. | PASS |
| Build sanity | `./gradlew assembleArm64Debug` | BUILD SUCCESSFUL in 25s. | PASS |
| Security artifact check | `test -f .planning/phases/08-exact-continue-watching-air-timing/08-SECURITY.md` | Exists; `08-SECURITY.md` reports `status: secured`, `threats_total: 23`, `threats_closed: 23`, `threats_open: 0`. | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| AIR-01 | 08-01, 08-02 | Continue Watching computes precise availability from TVDB aired date plus `airsTime` | SATISFIED | Calculator and enricher compute/carry exact `tvdbAvailabilityInstantMs`. |
| AIR-02 | 08-01, 08-02 | Availability instants account for device-local timezone before visibility decisions | SATISFIED | Exact epoch is computed from source zone; device-local diagnostic is exposed; gates compare instants. |
| AIR-03 | 08-01, 08-02, 08-03 | Future episodes withheld until computed instant | SATISFIED | Snapshot, Home, timeline, and Android TV feed paths use exact gate fields. |
| AIR-04 | 08-03, 08-04, 08-05, 08-06 | Withheld future TVDB next-up entries schedule re-evaluation at computed availability instant | SATISFIED BY SOURCE/TEST | Future rows schedule via `soonestPendingMs`; overdue exact/provider/date-only rows refresh through `handleScheduledReemit`. Real device alarm behavior remains human verification. |
| AIR-05 | 08-01, 08-02, 08-03, 08-04, 08-06 | Date-only fallback and diagnostics | SATISFIED BY SOURCE/TEST | Date-only precision and diagnostics exist, persist, and no longer get stranded by missed restore/reschedule paths. WR-03 diagnostic/privacy warning remains non-blocking. |
| AIR-06 | 08-03 | Detail screens still show future unaired episodes | SATISFIED | Gate remains scoped to Continue Watching surfaces. |

### Review Warning Evaluation

| Review ID | Phase Goal Gap? | Decision |
|---|---|---|
| Former WR-01 | No | Closed by 08-06: persisted restore now calls `handleScheduledReemit`, so overdue persisted rows force refresh before future-only scheduling. |
| Former WR-02 | No | Closed by 08-06: `AirDateGate.pendingTriggerMs` and `hasDuePending` cover exact TVDB, provider `firstAiredMs`, and date-string fallback rows. |
| WR-03 | No Phase 8 scheduling blocker | Still real: `TvdbContinueWatchingTimingEnricher` logs `reason=missing_timezone_policy` for all failures and includes raw `contentId`. Security audit accepted this as T-08-23 follow-up risk. |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|---|---:|---|---|---|
| `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt` | 61 | False diagnostic reason plus raw content ID in failure log | Warning | Misleading diagnostics and potential watch-history exposure in logs. Not blocking Phase 8 source/test goal; requires security/diagnostics follow-up. |

### Human Verification Required

### 1. Real Android TV Missed-Trigger Restore

**Test:** Schedule a future TVDB-backed next-up row, kill the app process before the airing instant, let the instant pass, then reopen the app.
**Expected:** Continue Watching refreshes provider state and the row appears when available.
**Why human:** Unit tests cover restore logic, but not real process death, OS alarm delivery, or provider refresh timing.

### 2. Real Android TV Reboot Reschedule

**Test:** Schedule a future row, reboot before the airing instant, and confirm the boot receiver reschedules or refreshes correctly when due.
**Expected:** Continue Watching appears at or after the airing instant without waiting for a day-level refresh.
**Why human:** Boot receiver behavior depends on Android device lifecycle and AlarmManager delivery.

### 3. Android S+ Exact-Alarm Denied Fallback

**Test:** Disable exact-alarm permission on Android S+ and confirm the inexact fallback still refreshes withheld rows.
**Expected:** Logs show scheduler fallback diagnostics; UI remains normal and does not expose scheduler degradation on cards.
**Why human:** Permission state and alarm fallback behavior require a device/emulator runtime.

### Gaps Summary

No automated source/test gaps remain after 08-06. The two previous blockers are closed:

- Persisted snapshot restore now uses the same due-aware handler as direct reschedule.
- Date-only/provider fallback rows without a TVDB exact instant now use shared trigger extraction and refresh immediately when overdue.

Overall status is `human_needed`, not `passed`, because real Android TV alarm/reboot/permission behavior remains outside what source/unit/build checks can prove. WR-03 remains a non-blocking diagnostic/privacy warning accepted in the security report.

---

_Verified: 2026-04-15T14:49:30Z_
_Verifier: Claude (gsd-verifier)_
