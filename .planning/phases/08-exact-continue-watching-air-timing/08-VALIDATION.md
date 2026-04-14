---
phase: 08
slug: exact-continue-watching-air-timing
status: draft
nyquist_compliant: true
wave_0_complete: false
created: 2026-04-14
---

# Phase 08 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4 `4.13.2`, kotlinx-coroutines-test `1.8.1`, MockK `1.13.12`, Robolectric `4.13` |
| **Config file** | `app/build.gradle.kts` |
| **Quick run command** | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.AirDateGateTest" --tests "com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest"` |
| **Full suite command** | `./gradlew testArm64DebugUnitTest` |
| **Estimated runtime** | ~60-180 seconds targeted, longer for full module suite |

---

## Sampling Rate

- **After every task commit:** Run the targeted test for the touched component plus `AirDateGateTest`.
- **After every plan wave:** Run explicit Continue Watching and snapshot classes touched by the wave.
- **Before `/gsd-verify-work`:** `./gradlew testArm64DebugUnitTest` and `./gradlew assembleArm64Debug` must be green.
- **Max feedback latency:** 3 task commits.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 08-01-01 | 01 | 1 | AIR-01, AIR-02, AIR-05 | T-08-01 | Malformed TVDB time data falls back without crashing | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbAirAvailabilityCalculatorTest"` | ❌ W0 | ⬜ pending |
| 08-01-02 | 01 | 1 | AIR-03, AIR-05 | T-08-04 | Availability gate prefers exact TVDB timing over date-only/provider timing when present | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.AirDateGateTest"` | ✅ | ⬜ pending |
| 08-02-01 | 02 | 2 | AIR-01, AIR-02, AIR-03, AIR-05 | T-08-05, T-08-06 | TVDB metadata mapping and timing enrichment tests preserve source fields without exposing secrets | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.TvdbContinueWatchingTimingEnricherTest" --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"` | ❌ W0 | ⬜ pending |
| 08-02-02 | 02 | 2 | AIR-01, AIR-02, AIR-03, AIR-05 | T-08-07 | TVDB metadata mapping preserves timing source fields for enrichment diagnostics | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"` | ✅ | ⬜ pending |
| 08-02-03 | 02 | 2 | AIR-01, AIR-02, AIR-03, AIR-05 | T-08-08 | Provider next-up flows receive TVDB availability fields before snapshot gating | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.TvdbContinueWatchingTimingEnricherTest"` | ❌ W0 | ⬜ pending |
| 08-03-01 | 03 | 2 | AIR-03, AIR-04, AIR-05, AIR-06 | T-08-09, T-08-11 | Persistence, timeline, and Android TV feed tests prove withheld rows stay out of visible surfaces | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest" --tests "com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest" --tests "com.nexio.tv.core.recommendations.AndroidTvFeedCatalogServiceContinueWatchingTest"` | ❌ W0 | ⬜ pending |
| 08-03-02 | 03 | 2 | AIR-03, AIR-04, AIR-05 | T-08-09, T-08-10 | Withheld exact-timing rows and diagnostics persist without exposing secrets | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest"` | ✅ | ⬜ pending |
| 08-03-03 | 03 | 2 | AIR-03, AIR-06 | T-08-11, T-08-12 | Main rail, Trakt up-next, and Android TV feed inherit the same exact gate while detail screens remain ungated | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest" --tests "com.nexio.tv.core.recommendations.AndroidTvFeedCatalogServiceContinueWatchingTest"` | ❌ W0 | ⬜ pending |
| 08-04-01 | 04 | 3 | AIR-04, AIR-05 | T-08-13, T-08-14 | Scheduler and retry tests cover exact/inexact alarms, package-scoped receiver action, and refresh-failure retry | Robolectric/unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.scheduler.ContinueWatchingAirAlarmSchedulerTest" --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest"` | ❌ W0 | ⬜ pending |
| 08-04-02 | 04 | 3 | AIR-04 | T-08-13 | Alarm receiver cannot be triggered by arbitrary external apps and exact alarms degrade to inexact fallback with diagnostics | Robolectric/unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.scheduler.ContinueWatchingAirAlarmSchedulerTest"` | ❌ W0 | ⬜ pending |
| 08-04-03 | 04 | 3 | AIR-04, AIR-05 | T-08-14, T-08-16 | Refresh failures keep withheld rows and retry with diagnostics instead of revealing stale scheduled rows | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest"` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculatorTest.kt` — covers AIR-01, AIR-02, AIR-05 exact-time parsing, timezone conversion, fallback diagnostics, and TVDB policy defaults.
- [ ] `app/src/test/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmSchedulerTest.kt` — covers AIR-04 exact/inexact alarm branch, cancel/reschedule behavior, and package-scoped receiver action.
- [ ] Extend `app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt` — covers persisted `scheduledReemit` and exact timing fields.
- [ ] Extend `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingTimelineAirDateTest.kt` — covers exact availability priority over date-only/Trakt timing across next-up rails.
- [ ] Add or extend Android TV feed tests to prove recommendations inherit exact gating from `ContinueWatchingSnapshotService`.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Alarm behavior after TV sleep/reboot | AIR-04 | Unit tests can verify scheduling calls, but not real device power-management behavior | On Android TV or emulator, schedule a withheld row a few minutes in the future, background/kill app, then confirm Continue Watching appears after the computed instant and refresh logs show the alarm path |
| Debug/settings diagnostic display | AIR-05 | Final diagnostic surface may depend on existing debug settings UI | Trigger missing `airsTime`, invalid time, and missing timezone cases; confirm diagnostics/logs include reason code and computed local time where available, with no Continue Watching placeholder card |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 3 task commits
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
