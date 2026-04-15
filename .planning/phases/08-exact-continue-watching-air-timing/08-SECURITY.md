---
phase: 08-exact-continue-watching-air-timing
phase_number: 08
status: secured
asvs_level: 1
block_on: open_threats
threats_total: 23
threats_closed: 23
threats_open: 0
accepted_risks: 1
transfer_risks: 0
audited: 2026-04-15
auditor: codex-gsd-security-auditor
---

# Phase 08 Security Verification

## Scope

Verified mitigations from the Phase 08 plan threat models only:

- `08-01-PLAN.md` through `08-06-PLAN.md`
- `08-01-SUMMARY.md` through `08-06-SUMMARY.md`
- `08-REVIEW.md` and `08-VERIFICATION.md`
- implementation files listed in the security prompt

No implementation files were modified during this audit.

## Result

| Metric | Count |
|--------|-------|
| Threats registered | 23 |
| Mitigations verified | 22 |
| Accepted risks documented | 1 |
| Transfer risks documented | 0 |
| Open threats | 0 |

## Final Threat Register

| Threat ID | Category | Component | Disposition | Status | Evidence |
|-----------|----------|-----------|-------------|--------|----------|
| T-08-01 | Tampering / Denial of Service | `TvdbAirAvailabilityCalculator.parseAirsTime` | mitigate | CLOSED | `TvdbAirAvailabilityCalculator.kt:21` parses with `runCatching`; `TvdbAirAvailabilityCalculator.kt:40` returns `INVALID_TIME` date-only fallback; formatter allowlist is at `TvdbAirAvailabilityCalculator.kt:149`. |
| T-08-02 | Tampering | `TvdbAirAvailabilityCalculator` source policy | mitigate | CLOSED | Unknown source policy returns `MISSING_TIMEZONE_POLICY` at `TvdbAirAvailabilityCalculator.kt:50`; unresolved country policies return null at `TvdbAirAvailabilityCalculator.kt:95` instead of inventing precision. |
| T-08-03 | Information Disclosure | Timing diagnostics | mitigate | CLOSED | Diagnostic DTO contains reason/source/local time fields only at `TvdbAirAvailability.kt:20`; snapshot persistence writes only TVDB availability fields at `ContinueWatchingSnapshotStore.kt:187`. |
| T-08-04 | Spoofing | Provider `firstAiredMs` vs TVDB exact fields | mitigate | CLOSED | `AirDateGate.isAired` prioritizes positive `availabilityInstantMs` at `AirDateGate.kt:14`; `pendingTriggerMs` uses the same priority at `AirDateGate.kt:35`. |
| T-08-05 | Tampering | `TvdbContinueWatchingTimingEnricher` | mitigate | CLOSED | Enricher copies only TVDB availability fields and leaves provider identity fields untouched at `TvdbContinueWatchingTimingEnricher.kt:52`. |
| T-08-06 | Denial of Service | `TvdbContinueWatchingTimingEnricher.enrich` | mitigate | CLOSED | Per-entry `runCatching` wraps enrichment at `TvdbContinueWatchingTimingEnricher.kt:27`; failures return the original entry at `TvdbContinueWatchingTimingEnricher.kt:62`. |
| T-08-07 | Information Disclosure | Enricher diagnostics/logging | mitigate | CLOSED | Failure log contains only a reason code and contentId at `TvdbContinueWatchingTimingEnricher.kt:60`; grep found no TVDB key, bearer token, raw auth header, or raw response logging in the checked timing path. WR-03 raw-ID privacy concern is accepted separately as T-08-23. |
| T-08-08 | Repudiation | Provider fallback path | mitigate | CLOSED | Enrichment uses `TvMetadataRouter.fetchEnrichment` and `fetchEpisodeEnrichment` at `TvdbContinueWatchingTimingEnricher.kt:34`; no `TmdbService` reference was found in the timing enricher. |
| T-08-09 | Tampering / Denial of Service | `ContinueWatchingSnapshotStore.decodeNextUpItemObject` | mitigate | CLOSED | JSON object parsing is guarded at `ContinueWatchingSnapshotStore.kt:214`; required ID/season/episode validation is at `ContinueWatchingSnapshotStore.kt:245`; enum parsing falls back through `runCatching` at `ContinueWatchingSnapshotStore.kt:325`. |
| T-08-10 | Information Disclosure | Snapshot persistence | mitigate | CLOSED | Snapshot encode writes `tvdbAvailabilityInstantMs`, precision, source zone/policy, diagnostic reason, and device-local time only at `ContinueWatchingSnapshotStore.kt:187`; no credentials/token/header/payload strings were found in snapshot persistence. |
| T-08-11 | Integrity | Home/Android TV feed refs | mitigate | CLOSED | Shared `AirDateGate` path is used in timeline refs at `ContinueWatchingTimeline.kt:41`, snapshot filtering at `ContinueWatchingSnapshotService.kt:455`, Home refs at `HomeViewModelContinueWatching.kt:500`, and Android TV feed refs at `AndroidTvFeedCatalogService.kt:512`. |
| T-08-12 | Integrity | Detail screen boundary | mitigate | CLOSED | `git diff -- app/src/main/java/com/nexio/tv/ui/screens/detail` produced no diff; Continue Watching exact-gate wiring is scoped to snapshot/timeline/Home/feed files. |
| T-08-13 | Spoofing / Denial of Service | `ContinueWatchingAirAlarmReceiver` | mitigate | CLOSED | Receiver is declared `android:exported="false"` at `AndroidManifest.xml:65`; the alarm `PendingIntent` is explicit, package-scoped, and immutable at `ContinueWatchingAirAlarmScheduler.kt:44`. |
| T-08-14 | Denial of Service | `ContinueWatchingSnapshotService.scheduleReemitIfNeeded` | mitigate | CLOSED | Future scheduling selects `AirDateGate.soonestPendingMs` at `ContinueWatchingSnapshotService.kt:651`; idempotency guard prevents rescheduling the same target at `ContinueWatchingSnapshotService.kt:658`. |
| T-08-15 | Information Disclosure | Scheduler and refresh failure logs | mitigate | CLOSED | Inexact alarm log contains mode and trigger time only at `ContinueWatchingAirAlarmScheduler.kt:29`; refresh failure log contains reason and retry interval only at `ContinueWatchingSnapshotService.kt:679`. |
| T-08-16 | Tampering | Alarm trigger behavior | mitigate | CLOSED | Receiver calls `ensureFresh(force = true)` for reevaluation at `ContinueWatchingAirAlarmReceiver.kt:23`; grep found no direct append from `scheduledReemit` into `nextUpItems`. |
| T-08-17 | Denial of Service | `rescheduleAirTimeAlarmFromSnapshot` | mitigate | CLOSED | `rescheduleAirTimeAlarmFromSnapshot` delegates once to `handleScheduledReemit` at `ContinueWatchingSnapshotService.kt:167`; due rows trigger one refresh branch at `ContinueWatchingSnapshotService.kt:628`, while future rows fall through to scheduling at `ContinueWatchingSnapshotService.kt:644`. |
| T-08-18 | Tampering | `ContinueWatchingSnapshotService` visible rows | mitigate | CLOSED | Due scheduled rows call the refresh-first path at `ContinueWatchingSnapshotService.kt:640`; grep found no code directly moving `scheduledReemit` into visible `nextUpItems`. |
| T-08-19 | Information Disclosure | Refresh failure logging | mitigate | CLOSED | Refresh failure diagnostic logs only `reason=refresh_failure retryMs=900000` at `ContinueWatchingSnapshotService.kt:679`; no content IDs, provider payloads, API keys, tokens, or auth headers were found in that path. |
| T-08-20 | Tampering | `AirDateGate.pendingTriggerMs` | mitigate | CLOSED | `pendingTriggerMs` ignores non-positive availability/provider timestamps at `AirDateGate.kt:35`; unparsable date strings return null through `parseDateToEpochMs` at `AirDateGate.kt:85`. |
| T-08-21 | Denial of Service | `ContinueWatchingSnapshotService.handleScheduledReemit` | mitigate | CLOSED | Due rows start one refresh branch at `ContinueWatchingSnapshotService.kt:628`; future rows continue through the soonest-only scheduler at `ContinueWatchingSnapshotService.kt:647`. |
| T-08-22 | Integrity | `ContinueWatchingSnapshotService` visible rows | mitigate | CLOSED | Due rows use `launchAirTimeRefreshWithRetry` at `ContinueWatchingSnapshotService.kt:640`, which calls `ensureFresh(force = true)` at `ContinueWatchingSnapshotService.kt:677`; no direct `scheduledReemit` to `nextUpItems` append was found. |
| T-08-23 | Information Disclosure | Gap closure scope | accept | CLOSED | Accepted risk documented below. The plan explicitly accepts WR-03 at `08-06-PLAN.md:307`, and review documents the remaining nonblocking warning at `08-REVIEW.md:59`. |

## Accepted Risks

| Threat ID | Category | Risk | Acceptance Rationale | Follow-up |
|-----------|----------|------|----------------------|-----------|
| T-08-23 | Information Disclosure | `TvdbContinueWatchingTimingEnricher` failure logs use `reason=missing_timezone_policy` for broad failures and include raw `contentId`. | Phase 08 gap closure explicitly scoped WR-03 out of the verifier blocker and accepted it as nonblocking in `08-06-PLAN.md:307`; `08-REVIEW.md:55` states no remaining Phase 8-blocking issues were found. | Track separately: replace raw-ID logging with a failure-specific reason and avoid raw watched-item identifiers in logs. |

## Transfer Risks

None.

## Unregistered Flags

None. Each Phase 08 summary reported `## Threat Flags` as none:

- `08-01-SUMMARY.md:105`
- `08-02-SUMMARY.md:123`
- `08-03-SUMMARY.md:132`
- `08-04-SUMMARY.md:130`
- `08-05-SUMMARY.md:95`
- `08-06-SUMMARY.md:100`

WR-03 from `08-REVIEW.md` maps to accepted threat `T-08-23` and is therefore not an unregistered flag.

## Audit Trail

| Date | Auditor | Action | Result |
|------|---------|--------|--------|
| 2026-04-15 | codex-gsd-security-auditor | Loaded all requested Phase 08 plans, summaries, review/verification artifacts, and implementation files. | Context complete. |
| 2026-04-15 | codex-gsd-security-auditor | Verified each registered threat by disposition: mitigate via source evidence, accept via accepted risks log, transfer via transfer log. | 23/23 closed. |
| 2026-04-15 | codex-gsd-security-auditor | Checked Summary `## Threat Flags` sections and mapped WR-03 to T-08-23. | No unregistered flags. |

