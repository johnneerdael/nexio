---
phase: 08-exact-continue-watching-air-timing
verified: 2026-04-15T14:13:41Z
status: gaps_found
score: 13/14 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 13/14
  gaps_closed:
    - "Direct rescheduleAirTimeAlarmFromSnapshot with a pre-seeded overdue exact TVDB scheduledReemit row now forces ensureFresh(force = true) and does not reveal the withheld row directly."
  gaps_remaining:
    - "Persisted-load restore still calls scheduleReemitIfNeeded directly, so overdue exact TVDB rows restored from disk can be cancelled instead of refreshed."
    - "Boot/exact-permission reschedule still treats overdue date-only/provider fallback rows as no-future-target cancellation cases."
  regressions: []
gaps:
  - truth: "Future next-up entries schedule a re-evaluation for the computed availability instant and can appear without waiting for a day-level refresh"
    status: failed
    reason: "WR-01 remains a production blocker. loadPersistedSnapshotForActiveProfile restores persisted scheduledReemit rows, then calls scheduleReemitIfNeeded directly. scheduleReemitIfNeeded only finds future targets, so overdue exact TVDB rows loaded during startup/profile restore can be cancelled without a forced refresh."
    artifacts:
      - path: "app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt"
        issue: "loadPersistedSnapshotForActiveProfile at lines 196-200 bypasses the overdue exact refresh branch added to rescheduleAirTimeAlarmFromSnapshot."
      - path: "app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt"
        issue: "08-05 regression seeds rawSnapshotState and calls rescheduleAirTimeAlarmFromSnapshot; it does not write/read ContinueWatchingSnapshotStore or cover service init/profile restore."
    missing:
      - "Centralize persisted scheduledReemit handling so loadPersistedSnapshotForActiveProfile and rescheduleAirTimeAlarmFromSnapshot both check due rows before future-only scheduling."
      - "Add a regression that writes an overdue exact scheduledReemit row to ContinueWatchingSnapshotStore, constructs/restores the service, and verifies refreshNow is invoked without directly moving the row into nextUpItems."
  - truth: "Future next-up entries schedule a re-evaluation for the computed availability instant and can appear without waiting for a day-level refresh"
    status: failed
    reason: "WR-02 remains a blocker for AIR-04 fallback rows. rescheduleAirTimeAlarmFromSnapshot only considers tvdbAvailabilityInstantMs <= now as due. Date-only/provider fallback withheld rows have tvdbAvailabilityInstantMs == null, fall through to scheduleReemitIfNeeded, and are cancelled when their firstAiredMs or parsed firstAired date is already overdue."
    artifacts:
      - path: "app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt"
        issue: "The due-row branch at lines 170-173 checks only tvdbAvailabilityInstantMs and does not share AirDateGate's firstAiredMs/date fallback trigger logic."
      - path: "app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt"
        issue: "soonestPendingMs can extract exact, firstAiredMs, or date-string triggers, but it filters to values greater than nowMs and exposes no hasDuePending/shared trigger helper for restore paths."
    missing:
      - "Expose/use a shared AirDateGate trigger extractor or hasDuePending helper that checks tvdbAvailabilityInstantMs, firstAiredMs, and parsed firstAired dates consistently."
      - "Use that shared due detection in every persisted reschedule path before falling back to soonestPendingMs."
      - "Add regression coverage for an overdue persisted scheduledReemit row with tvdbAvailabilityInstantMs == null and firstAiredMs or firstAired already due."
---

# Phase 8: Exact Continue Watching Air Timing Verification Report

**Phase Goal:** Continue Watching shows TVDB-backed new episodes at their computed device-local airing instant instead of at the start of the release date
**Verified:** 2026-04-15T14:13:41Z
**Status:** gaps_found
**Re-verification:** Yes - after gap closure plan 08-05

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | TVDB aired date plus series airsTime computes an exact availability instant using source-timezone policy | VERIFIED | `TvdbAirAvailabilityCalculator.computeAvailability` still parses TVDB date/time metadata, resolves source policy, and computes epoch instants from the source zone. |
| 2 | Device-local conversion is available while visibility gates compare exact instants | VERIFIED | The calculator exposes `deviceLocalDateTime`; `AirDateGate.isAired` compares epoch ms rather than release-date starts. |
| 3 | TVDB-backed next-up rows carry availability fields before snapshot gating | VERIFIED | `TvdbContinueWatchingTimingEnricher` copies exact/date-only availability fields onto `TrackingNextUpEntry`; `TrackingProgressService` wires the enricher into provider flows. |
| 4 | TVDB exact timing wins over Trakt/Simkl first-aired timing when present | VERIFIED | `AirDateGate.isAired` and `soonestPendingMs` prefer `availabilityInstantMs` before provider timing. |
| 5 | Date-only fallback preserves existing gating and exposes diagnostic reason codes | VERIFIED WITH AIR-04 GAP | Date-only precision and diagnostics exist and persist, but overdue date-only scheduled reemit rows are not refreshed by boot/restore reschedule paths. |
| 6 | TVDB timing enrichment does not add duplicate TMDB TV metadata calls | VERIFIED | The timing enricher depends on `TvMetadataRouter` and `TvdbAirAvailabilityCalculator`, not direct TMDB services. |
| 7 | Future exact-timing rows are withheld from Continue Watching surfaces until available | VERIFIED | Snapshot, timeline, Home, and Android TV feed paths pass `tvdbAvailabilityInstantMs` into `AirDateGate`; withheld rows are stored in `scheduledReemit`. |
| 8 | Already-started resume rows remain visible regardless of future next-up timing | VERIFIED | Resume rows still bypass air-date withholding because they carry no air-date trigger data. |
| 9 | Withheld rows and timing diagnostics persist across process restart | VERIFIED | `ContinueWatchingSnapshotStore` schema 5 writes and reads `scheduledReemit` plus TVDB timing fields. |
| 10 | TV detail episode lists are not filtered by Continue Watching exact-air-time gating | VERIFIED | Phase 8 files do not wire this gate into detail-screen episode lists. |
| 11 | Only the soonest future withheld availability trigger is scheduled | VERIFIED | `scheduleReemitIfNeeded` uses `AirDateGate.soonestPendingMs` and schedules one target via `airScheduler.scheduleSoonest(soonestMs)`. |
| 12 | Durable re-evaluation survives missed restart/reboot reschedule paths | FAILED | 08-05 fixes only direct `rescheduleAirTimeAlarmFromSnapshot` for overdue exact rows already in memory. Persisted-load restore still bypasses that path, and date-only/provider fallback rows are not recognized as due. |
| 13 | Alarm firing refreshes tracking provider state instead of directly revealing stale rows | VERIFIED | The receiver calls `ensureFresh(force = true)` for reevaluate actions; no code appends `scheduledReemit` directly into visible `nextUpItems`. |
| 14 | Refresh failure keeps withheld rows and retries with backoff while recording diagnostics | VERIFIED | `launchAirTimeRefreshWithRetry` logs `reason=refresh_failure retryMs=900000` and schedules retry without moving withheld rows. |

**Score:** 13/14 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailability.kt` | Availability precision/result contract | VERIFIED | Enums/data classes remain present, including `REFRESH_FAILURE`. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculator.kt` | Source-zone exact instant calculation | VERIFIED | Parses time formats, resolves policy, computes exact instants, and returns date-only diagnostics on incomplete precision. |
| `app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt` | Shared exact/date-only gate | VERIFIED WITH GAP | Gate ordering is correct, but restore callers need a shared due-trigger helper instead of using only future-only `soonestPendingMs`. |
| `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt` | TVDB timing enrichment for next-up rows | VERIFIED WITH WARNING | Enrichment is wired. WR-03 diagnostic logging remains misleading and includes raw content IDs. |
| `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt` | Persist withheld rows and diagnostics | VERIFIED | Schema 5 persists `scheduledReemit`, exact instant, precision, source policy/zone, reason, and device-local diagnostic. |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt` | Snapshot gating, scheduling, refresh/retry | FAILED | 08-05 direct reschedule fix exists, but persisted restore and date-only due detection remain incomplete. |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingTimeline.kt` | Exact timing carrier for timeline selection | VERIFIED | `availabilityInstantMs` flows into the shared gate. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` | Home surface uses exact gate fields | VERIFIED | Home next-up refs and `hasAired` use TVDB availability instants. |
| `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt` | Android TV feed excludes withheld rows | VERIFIED | Feed builds from visible snapshot rows and does not map `scheduledReemit` into placeholders. |
| `app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirScheduler.kt` | Scheduler interface | VERIFIED | `scheduleSoonest` and `cancel` abstraction exists. |
| `app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmScheduler.kt` | AlarmManager implementation | VERIFIED | Exact/inexact alarm branches and package-scoped immutable PendingIntent exist. |
| `app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmReceiver.kt` | Broadcast receiver | VERIFIED FOR WIRING | Receiver calls refresh/reschedule entry points. Security is not marked passed because no `08-SECURITY.md` exists. |
| `app/src/main/AndroidManifest.xml` | Non-exported receiver and permissions | VERIFIED FOR WIRING | Receiver and permissions are declared; this is wiring evidence, not a security sign-off. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `TrackingProgressService.kt` | `TvdbContinueWatchingTimingEnricher.kt` | `mapLatest` enrichment on next-up flows | WIRED | Trakt and Simkl next-up flows call the enricher before snapshot gating. |
| `TvdbContinueWatchingTimingEnricher.kt` | `TvdbAirAvailabilityCalculator.kt` | `computeAvailability` | WIRED | Enricher computes availability from TVDB series/episode metadata. |
| `ContinueWatchingSnapshotService.kt` | `TrackingProgressService.refreshNow` | `ensureFresh(force = true)` | WIRED WITH GAP | `launchAirTimeRefreshWithRetry` calls `ensureFresh(force = true)`, and 08-05 direct reschedule reaches it for exact overdue in-memory rows. Persisted-load restore does not. |
| `ContinueWatchingSnapshotService.kt` | `AirDateGate.soonestPendingMs` | future-row fallback scheduling path | WIRED | Future rows still fall through to `scheduleReemitIfNeeded` and `soonestPendingMs`. |
| `ContinueWatchingSnapshotStore.kt` | `ContinueWatchingSnapshotService.kt` | persisted `scheduledReemit` restore | PARTIAL | Store reads rows, but service restore then calls future-only scheduling directly. |
| `AndroidManifest.xml` | `ContinueWatchingAirAlarmReceiver.kt` | non-exported receiver declaration | WIRED FOR GOAL | Manual source check confirms receiver wiring. Security review remains separate. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `TvdbContinueWatchingTimingEnricher.kt` | `tvdbAvailabilityInstantMs` and diagnostic fields | `TvMetadataRouter.fetchEnrichment` plus `fetchEpisodeEnrichment` | Yes | FLOWING |
| `TrackingProgressService.kt` | enriched next-up rows | Trakt/Simkl next-up flows through timing enricher | Yes | FLOWING |
| `ContinueWatchingSnapshotService.kt` | `scheduledReemit` | `buildRawSnapshot` filters unavailable next-up rows | Yes | FLOWING WITH GAPS |
| `ContinueWatchingSnapshotStore.kt` | persisted `scheduledReemit` and TVDB fields | schema 5 JSON encode/decode | Yes | FLOWING |
| `ContinueWatchingSnapshotService.kt` | overdue exact in-memory reschedule | `rawSnapshotState.value.scheduledReemit` via `rescheduleAirTimeAlarmFromSnapshot` | Yes | FLOWING FOR 08-05 NARROW CASE |
| `ContinueWatchingSnapshotService.kt` | overdue persisted restore | `snapshotStore.read()` then `loadPersistedSnapshotForActiveProfile` | No | HOLLOW RESTORE PATH - due rows are routed to future-only scheduling |
| `ContinueWatchingSnapshotService.kt` | overdue date-only/provider fallback reschedule | `firstAiredMs` or parsed `firstAired` with no exact TVDB instant | No | DISCONNECTED DUE DETECTION |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| 08-05 direct overdue exact reschedule and mutation suite | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest"` | Build successful in 11s; all targeted mutation tests passed. | PASS |
| 08-05 artifact verification | `gsd-tools verify artifacts 08-05-PLAN.md` | 2/2 artifacts exist and are substantive. | PASS |
| 08-05 key-link verification | `gsd-tools verify key-links 08-05-PLAN.md` plus manual source check | Tool verified the `soonestPendingMs` link and false-negatived escaped `ensureFresh`; manual check found `ensureFresh(force = true)` in `launchAirTimeRefreshWithRetry`. | PASS WITH MANUAL CHECK |
| Persisted-store restore regression | Source/test scan | No test writes overdue `scheduledReemit` to `ContinueWatchingSnapshotStore` and verifies service restore refreshes. | FAIL |
| Date-only overdue reschedule regression | Source/test scan | No test covers `tvdbAvailabilityInstantMs == null` with due `firstAiredMs` or `firstAired` in `rescheduleAirTimeAlarmFromSnapshot`. | FAIL |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| AIR-01 | 08-01, 08-02 | Continue Watching computes precise availability from TVDB aired date plus `airsTime` | SATISFIED | Calculator and enricher compute/carry exact `tvdbAvailabilityInstantMs`. |
| AIR-02 | 08-01, 08-02 | Availability instants account for device-local timezone before visibility decisions | SATISFIED | Exact epoch is computed from source zone; device-local diagnostic is exposed; gates compare instants. |
| AIR-03 | 08-01, 08-02, 08-03 | Future episodes withheld until computed instant | SATISFIED | Snapshot, Home, timeline, and Android TV feed paths use exact gate fields. |
| AIR-04 | 08-03, 08-04, 08-05 | Withheld future TVDB next-up entries schedule re-evaluation at computed availability instant | FAILED | Direct in-memory overdue exact reschedule is fixed, but persisted-load restore and date-only/provider fallback due rows still cancel instead of refreshing. |
| AIR-05 | 08-01, 08-02, 08-03, 08-04 | Date-only fallback and diagnostics | PARTIAL | Date-only precision and diagnostics exist. Re-evaluation after missed boot/restore is incomplete for date-only fallback rows. |
| AIR-06 | 08-03 | Detail screens still show future unaired episodes | SATISFIED | Gate remains scoped to Continue Watching surfaces. |

### Review Warning Evaluation

| Review ID | Phase Goal Gap? | Decision |
|---|---|---|
| WR-01 | Yes | Confirmed blocker for AIR-04. 08-05 did not update `loadPersistedSnapshotForActiveProfile`, so real startup/profile restore can still leave overdue exact TVDB rows hidden. |
| WR-02 | Yes | Confirmed blocker for AIR-04 fallback behavior. Due detection is exact-only, while scheduled rows can be withheld by `firstAiredMs` or date-only fallback. |
| WR-03 | Not an AIR-04 blocker | Warning remains real. The enricher logs `reason=missing_timezone_policy` for all exceptions and includes raw `contentId`. This is diagnostic/privacy debt and should be handled in a security/diagnostics pass. This verification does not claim security passed. |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|---|---:|---|---|---|
| `ContinueWatchingSnapshotService.kt` | 200 | Persisted restore calls `scheduleReemitIfNeeded` directly | Blocker | Overdue exact TVDB rows restored from disk can cancel instead of forcing provider refresh. |
| `ContinueWatchingSnapshotService.kt` | 170 | Due-row detection checks only `tvdbAvailabilityInstantMs` | Blocker | Overdue date-only/provider fallback rows are ignored by boot/permission reschedule. |
| `AirDateGate.kt` | 58 | `soonestPendingMs` exposes only future targets | Blocker at caller boundary | Correct for future scheduling, but callers need a shared due helper before using it for restore/reschedule. |
| `TvdbContinueWatchingTimingEnricher.kt` | 61 | False diagnostic reason plus raw content ID | Warning | Misleading diagnostics and potential watch-history exposure in logs. Not counted as phase-goal completion. |

### Human Verification Required

Automated checks cannot prove Android TV sleep, reboot, exact-alarm permission behavior, or security posture. These remain required after code gaps are closed:

1. Schedule a future TVDB-backed next-up row, kill the app process, let the airing instant pass, then reopen the app.
   Expected: Continue Watching refreshes provider state and the row appears when available.

2. Schedule a future row, reboot before the airing instant, and confirm the boot receiver reschedules or refreshes correctly when due.
   Expected: Continue Watching appears at or after the airing instant without waiting for a day-level refresh.

3. Disable exact-alarm permission on Android S+ and confirm the inexact fallback still refreshes without exposing scheduler degradation on cards.
   Expected: Logs show scheduler fallback diagnostics; UI remains normal.

4. Run a dedicated security verification for Phase 8 receiver/logging behavior.
   Expected: Receiver/export/PendingIntent/logging risks are explicitly evaluated in `08-SECURITY.md` or equivalent. No such file exists at verification time.

### Gaps Summary

Plan 08-05 closed the narrow direct-reschedule case it targeted: when `rawSnapshotState` already contains an overdue exact TVDB `scheduledReemit` row, `rescheduleAirTimeAlarmFromSnapshot()` now cancels stale scheduler state, calls the refresh-first path, and keeps the row withheld until provider refresh.

The phase goal is still not achieved because production restore paths remain incomplete. Service startup/profile restore loads persisted rows and sends them directly into future-only scheduling, so overdue exact rows can still remain hidden. The same reschedule path also ignores overdue date-only/provider fallback rows because due detection only looks at `tvdbAvailabilityInstantMs`.

Security is not marked passed. WR-03 remains a real diagnostic/privacy warning, and no `08-SECURITY.md` exists.

---

_Verified: 2026-04-15T14:13:41Z_
_Verifier: Claude (gsd-verifier)_
