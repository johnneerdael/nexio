---
phase: 08-exact-continue-watching-air-timing
verified: 2026-04-15T12:16:51Z
status: gaps_found
score: 13/14 must-haves verified
overrides_applied: 0
gaps:
  - truth: "Future next-up entries schedule a re-evaluation for the computed availability instant and can appear without waiting for a day-level refresh"
    status: failed
    reason: "Boot/restart reschedule handles only future scheduledReemit instants. If a persisted withheld row is already due, soonestPendingMs returns null, the alarm is cancelled, and no forced refresh is started."
    artifacts:
      - path: "app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt"
        issue: "rescheduleAirTimeAlarmFromSnapshot delegates to scheduleReemitIfNeeded, which cancels when all persisted reemit rows are overdue instead of calling ensureFresh(force = true)"
      - path: "app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt"
        issue: "soonestPendingMs filters to instants greater than nowMs, so due/overdue rows are invisible to rescheduleAirTimeAlarmFromSnapshot"
    missing:
      - "Detect persisted scheduledReemit rows whose TVDB availability instant is <= now during restore/boot/exact-permission reschedule and force a refresh immediately."
      - "Add a regression test for an overdue persisted scheduledReemit row causing rescheduleAirTimeAlarmFromSnapshot to refresh rather than cancel."
---

# Phase 8: Exact Continue Watching Air Timing Verification Report

**Phase Goal:** Continue Watching shows TVDB-backed new episodes at their computed device-local airing instant instead of at the start of the release date
**Verified:** 2026-04-15T12:16:51Z
**Status:** gaps_found
**Re-verification:** No - initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | TVDB aired date plus series airsTime computes an exact availability instant using source-timezone policy | VERIFIED | `TvdbAirAvailabilityCalculator.computeAvailability` parses TVDB dates/times, resolves source policy, and creates a `ZonedDateTime` in the source zone before converting to epoch ms. |
| 2 | Device-local conversion is available while visibility gates compare exact instants | VERIFIED | The calculator stores `deviceLocalDateTime`; `AirDateGate.isAired` compares `tvdbAvailabilityInstantMs` against `nowMs`, avoiding date-start local truncation. |
| 3 | TVDB-backed next-up rows carry availability fields before snapshot gating | VERIFIED | `TvdbContinueWatchingTimingEnricher` fetches series and episode metadata, computes availability, and copies exact/date-only diagnostic fields onto `TrackingNextUpEntry`; `TrackingProgressService` runs it on Trakt and Simkl next-up flows. |
| 4 | TVDB exact timing wins over Trakt/Simkl first-aired timing when present | VERIFIED | `AirDateGate.isAired` checks `availabilityInstantMs` before `firstAiredMs`; `soonestPendingMs` also prefers the availability selector. |
| 5 | Date-only fallback preserves existing gating and exposes diagnostic reason codes | VERIFIED | Missing/invalid/missing-policy precision returns `DATE_ONLY` with reason codes; snapshot persistence writes and reads precision/reason fields. |
| 6 | TVDB timing enrichment does not add duplicate TMDB TV metadata calls | VERIFIED | The timing enricher depends on `TvMetadataRouter` and `TvdbAirAvailabilityCalculator`, not TMDB services. |
| 7 | Future exact-timing rows are withheld from Continue Watching surfaces until available | VERIFIED | Snapshot, timeline, Home next-up, and Android TV feed paths pass `tvdbAvailabilityInstantMs` into `AirDateGate`; withheld rows are excluded from visible snapshot lists and Android TV feed rows. |
| 8 | Already-started resume rows remain visible regardless of future next-up timing | VERIFIED | Resume rows are kept through the snapshot/timeline path; the exact gate is a no-op for resume entries with no air-date data. |
| 9 | Withheld rows and timing diagnostics persist across process restart | VERIFIED | `ContinueWatchingSnapshotStore` schema 5 persists `scheduledReemit` plus TVDB timing and diagnostic fields. |
| 10 | TV detail episode lists are not filtered by Continue Watching exact-air-time gating | VERIFIED | Phase 8 changes did not modify detail screen files, and `git diff -- app/src/main/java/com/nexio/tv/ui/screens/detail` is empty. |
| 11 | Only the soonest withheld TVDB availability instant is scheduled | VERIFIED | `ContinueWatchingSnapshotService.scheduleReemitIfNeeded` uses `AirDateGate.soonestPendingMs` with `tvdbAvailabilityInstantMs` and schedules only that target. |
| 12 | Durable re-evaluation survives missed restart/reboot reschedule paths | FAILED | `rescheduleAirTimeAlarmFromSnapshot` delegates to `scheduleReemitIfNeeded`; `soonestPendingMs` drops instants `<= nowMs`, so overdue persisted rows cancel instead of refreshing. |
| 13 | Alarm firing refreshes tracking provider state instead of directly revealing stale rows | VERIFIED | `ContinueWatchingAirAlarmReceiver` calls `snapshotService.ensureFresh(force = true)` for `ACTION_REEVALUATE`; no code path appends `scheduledReemit` directly into visible `nextUpItems`. |
| 14 | Refresh failure keeps withheld rows and retries with backoff while recording diagnostics | VERIFIED | The timer failure path logs `reason=refresh_failure retryMs=900000`, leaves `scheduledReemit` unchanged, clears the timer target, and schedules a retry. |

**Score:** 13/14 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailability.kt` | Availability precision/result contract | VERIFIED | Enums and data classes exist, including `REFRESH_FAILURE`. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculator.kt` | Source-zone exact instant calculation | VERIFIED | Implements parsing, timezone policies, date-only fallback, and device-local diagnostics. |
| `app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt` | Shared exact/date-only gate | VERIFIED WITH GAP | Exact instant priority is present; `soonestPendingMs` correctly returns only future targets but callers lack overdue-row handling. |
| `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt` | TVDB timing enrichment for next-up rows | VERIFIED | Wired through router and calculator; exception diagnostics remain a warning, not a roadmap-blocking failure. |
| `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt` | Persist withheld rows and diagnostics | VERIFIED | Schema 5 persists `scheduledReemit` and TVDB fields. Schema 4 rejection is upgrade debt, not a Phase 8 goal blocker. |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt` | Snapshot gating, scheduling, refresh/retry | FAILED | Core paths are substantive and wired, but missed restart/reboot due rows are cancelled instead of force-refreshed. |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingTimeline.kt` | Exact timing carrier for timeline selection | VERIFIED | `ContinueWatchingNextUpRef.availabilityInstantMs` flows into the gate. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` | Home surface uses exact gate fields | VERIFIED | Home next-up refs and `hasAired` use `tvdbAvailabilityInstantMs`. |
| `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt` | Android TV feed excludes withheld rows | VERIFIED | Feed builds from visible snapshot rows and does not map `scheduledReemit` into placeholders. |
| `app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirScheduler.kt` | Scheduler interface | VERIFIED | `scheduleSoonest` and `cancel` abstraction exists. |
| `app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmScheduler.kt` | AlarmManager implementation | VERIFIED | Exact/inexact alarm branches and package-scoped immutable PendingIntent exist. |
| `app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmReceiver.kt` | Broadcast receiver | VERIFIED | Receiver is Hilt-enabled and calls refresh/reschedule paths. |
| `app/src/main/AndroidManifest.xml` | Non-exported receiver and permissions | VERIFIED | Receiver is declared as `.core.scheduler.ContinueWatchingAirAlarmReceiver` with `android:exported="false"` and boot/exact alarm permissions. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `TrackingProgressService.kt` | `TvdbContinueWatchingTimingEnricher.kt` | `mapLatest` enrichment on next-up flows | WIRED | Trakt and Simkl main/synthetic next-up flows call `enrich`. |
| `TvdbContinueWatchingTimingEnricher.kt` | `TvdbAirAvailabilityCalculator.kt` | `computeAvailability` | WIRED | Enricher computes availability from TVDB series and episode metadata. |
| `ContinueWatchingSnapshotService.kt` | `AirDateGate.kt` | exact `isAired` and `soonestPendingMs` | WIRED WITH GAP | Normal future scheduling works; overdue persisted rows are not handled. |
| `HomeViewModelContinueWatching.kt` | `AirDateGate.kt` | `NextUpInfo.hasAired` | WIRED | UI `hasAired` uses TVDB availability instant. |
| `AndroidTvFeedCatalogService.kt` | snapshot visible rows | feed helper | WIRED | Feed consumes visible `nextUpItems` only. |
| `AndroidManifest.xml` | `ContinueWatchingAirAlarmReceiver.kt` | non-exported receiver declaration | WIRED | gsd-tools reported a false negative because Android shorthand `.core.scheduler...` does not text-match the full class name. Manual check passes. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `TvdbContinueWatchingTimingEnricher.kt` | `tvdbAvailabilityInstantMs` and diagnostic fields | `TvMetadataRouter.fetchEnrichment` plus `fetchEpisodeEnrichment` | Yes | FLOWING |
| `TrackingProgressService.kt` | enriched next-up rows | Trakt/Simkl next-up flows through timing enricher | Yes | FLOWING |
| `ContinueWatchingSnapshotService.kt` | `scheduledReemit` | `buildRawSnapshot` filters future exact rows | Yes | FLOWING WITH GAP |
| `ContinueWatchingSnapshotStore.kt` | persisted `scheduledReemit` and TVDB fields | schema 5 JSON encode/decode | Yes | FLOWING |
| `HomeViewModelContinueWatching.kt` | `hasAired` | snapshot next-up rows and exact gate | Yes | FLOWING |
| `AndroidTvFeedCatalogService.kt` | feed items | snapshot visible rows only | Yes | FLOWING |
| `ContinueWatchingAirAlarmReceiver.kt` | alarm action | explicit package-scoped PendingIntent and manifest receiver | Yes | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Phase 8 targeted unit tests | `./gradlew testArm64DebugUnitTest --tests ...Phase 8 test classes...` | Failed before tests ran: Gradle could not delete `app/build/tmp/kotlin-classes/arm64DebugUnitTest`, with unrelated dirty `ProfileSettingsScopeContractTest.class` appearing during compilation. | BLOCKED - environment/build-dir contention, not counted as Phase 8 evidence |
| Manifest receiver wiring | Manual source check | Receiver declared at manifest lines 65-73 and implementation exists. | PASS |
| Detail screen isolation | `git diff -- app/src/main/java/com/nexio/tv/ui/screens/detail` | Empty output. | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| AIR-01 | 08-01, 08-02 | Continue Watching computes precise availability from TVDB aired date plus `airsTime` | SATISFIED | Calculator and enricher compute/carry exact `tvdbAvailabilityInstantMs`. |
| AIR-02 | 08-01, 08-02 | Availability instants account for device local timezone before visibility decisions | SATISFIED | Source-zone exact epoch is computed; device-local diagnostic is exposed; gates compare exact instants rather than date starts. |
| AIR-03 | 08-01, 08-02, 08-03 | Future episodes withheld until computed instant | SATISFIED | Snapshot, Home, timeline, and Android TV feed paths use exact gate fields. |
| AIR-04 | 08-03, 08-04 | Withheld future entries schedule re-evaluation at computed instant | PARTIAL / FAILED | Future targets schedule, but overdue persisted rows after restart/boot cancel instead of refreshing. |
| AIR-05 | 08-01, 08-02, 08-03, 08-04 | Date-only fallback and diagnostics | SATISFIED WITH WARNING | Missing/invalid/missing-policy diagnostics are implemented and persisted; enrichment exception logging is misleading and should be fixed as diagnostic debt. |
| AIR-06 | 08-03 | Detail screens still show future unaired episodes | SATISFIED | No Phase 8 changes under detail screens; exact gate is scoped to Continue Watching surfaces. |

### Review Warning Evaluation

| Review ID | Phase Goal Gap? | Decision |
|---|---|---|
| WR-01 | Yes | Confirmed gap. Missed/overdue persisted reemit rows can stay hidden after restart/boot because reschedule cancels instead of refreshing. |
| WR-02 | No | Duplicate in-process timer plus AlarmManager refresh can waste provider calls, but it does not prevent exact-time visibility. Track as reliability/performance debt. |
| WR-03 | No | Separate main/synthetic enrichment can duplicate TVDB work and create transient inconsistency on provider failure, but normal success-path exact gating is wired. Track as reliability debt, likely aligned with Phase 10 failure diagnostics. |
| WR-04 | No for Phase 8 goal; warning remains real | Misleading enrichment failure logging and raw content ID exposure should be fixed. It does not block the roadmap success criteria for TVDB-backed success/date-only rows, and broader failure diagnostics are Phase 10 scope. No security pass is claimed. |
| WR-05 | No | Rejecting schema 4 snapshots is upgrade/cache-preservation debt. It does not block schema 5 exact-air-time persistence or runtime gating. |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|---|---:|---|---|---|
| `ContinueWatchingSnapshotService.kt` | 167 | Due persisted rows routed through future-only scheduler | Blocker | Breaks AIR-04 after missed restart/boot reschedule. |
| `TvdbContinueWatchingTimingEnricher.kt` | 61 | False diagnostic reason plus raw content ID in log | Warning | Misleads diagnostics and may expose watched content IDs in logs; not counted as a Phase 8 goal blocker. |
| `ContinueWatchingSnapshotStore.kt` | 116 | Exact schema-version rejection | Warning | Clears schema 4 cache on upgrade; not a blocker for new schema 5 exact timing behavior. |

### Human Verification Required

Automated verification cannot prove device sleep, reboot, and exact-alarm permission behavior on real Android TV hardware. After the code gap is fixed, manually verify:

1. Schedule a future TVDB-backed next-up row, kill the app process, let the airing instant pass, then reopen the app.
   Expected: Continue Watching refreshes provider state and the row appears when available.

2. Schedule a future row, reboot before the airing instant, and confirm the boot receiver reschedules the alarm.
   Expected: Continue Watching refreshes at or after the airing instant without waiting for a day-level refresh.

3. Disable exact-alarm permission on Android S+ and confirm the inexact fallback still refreshes without exposing scheduler degradation on cards.
   Expected: Logs show `mode=inexact_alarm`; UI remains normal.

Security note: no `.planning/phases/08-exact-continue-watching-air-timing/08-SECURITY.md` exists, so this verification does not assert a security pass. The receiver/manifest mitigations were checked only as part of phase-goal wiring.

### Gaps Summary

The core exact-air-time pipeline exists: TVDB timing is calculated, enriched onto next-up rows, persisted when withheld, gated across Continue Watching surfaces, and scheduled through AlarmManager. The remaining goal gap is the missed-trigger restart path. If the process/device is unavailable when the airing instant passes, the boot/reschedule path looks only for future targets and cancels instead of refreshing due rows, so a withheld episode can remain hidden.

---

_Verified: 2026-04-15T12:16:51Z_
_Verifier: Claude (gsd-verifier)_
