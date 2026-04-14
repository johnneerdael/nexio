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
| 08-01-02 | 01 | 1 | AIR-03, AIR-05 | T-08-01 | Exact TVDB timing wins over date-only/provider timing when present | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.AirDateGateTest"` | ✅ | ⬜ pending |
| 08-02-01 | 02 | 1 | AIR-03, AIR-04 | T-08-02 | Withheld exact-timing rows persist without exposing secrets | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest"` | ✅ | ⬜ pending |
| 08-02-02 | 02 | 1 | AIR-03, AIR-06 | T-08-01 | Main rail, Trakt up-next, and Android TV feed inherit the same gate | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest" --tests "com.nexio.tv.core.recommendations.AndroidTvOwnedChannelRowsTest"` | ✅ | ⬜ pending |
| 08-03-01 | 03 | 2 | AIR-04 | T-08-03 | Alarm receiver cannot be triggered by arbitrary external apps | Robolectric/unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.scheduler.ContinueWatchingAirAlarmSchedulerTest"` | ❌ W0 | ⬜ pending |
| 08-03-02 | 03 | 2 | AIR-04, AIR-05 | T-08-02 | Refresh failures keep withheld rows and retry with diagnostics | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest"` | ✅ | ⬜ pending |

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
