---
phase: 2
slug: per-profile-auth-and-settings
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-14
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4 + Robolectric |
| **Config file** | `app/build.gradle` — `testOptions.unitTests.includeAndroidResources = true` |
| **Quick run command** | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.*DataStore*"` |
| **Full suite command** | `./gradlew testArm64DebugUnitTest` |
| **Estimated runtime** | ~45 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.*"`
- **After every plan wave:** Run `./gradlew testArm64DebugUnitTest`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 45 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 02-01-01 | 01 | 0 | AUTH-01, AUTH-02 | T-02-01 | Profile 1 token ≠ profile 2 token | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.TraktAuthDataStoreProfileTest"` | ❌ W0 | ⬜ pending |
| 02-01-02 | 01 | 0 | AUTH-03, AUTH-04 | T-02-02 | SimklAuth profile isolation | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.SimklAuthDataStoreProfileTest"` | ❌ W0 | ⬜ pending |
| 02-01-03 | 01 | 0 | AUTH-06 | — | Settings store profile switching | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ThemeDataStoreProfileTest"` | ❌ W0 | ⬜ pending |
| 02-01-04 | 01 | 0 | AUTH-05 | T-02-03 | isPrimaryProfileActive gate | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.profile.ProfileManagerTest"` | ❌ W0 | ⬜ pending |
| 02-01-05 | 01 | 0 | — | — | Existing SearchHistoryDataStoreTest updated | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.SearchHistoryDataStoreTest"` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `app/src/test/java/com/nexio/tv/data/local/TraktAuthDataStoreProfileTest.kt` — stubs for AUTH-01, AUTH-02
- [ ] `app/src/test/java/com/nexio/tv/data/local/SimklAuthDataStoreProfileTest.kt` — stubs for AUTH-03, AUTH-04
- [ ] `app/src/test/java/com/nexio/tv/data/local/ThemeDataStoreProfileTest.kt` — stubs for AUTH-06
- [ ] `app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt` — stubs for AUTH-05
- [ ] `FakeProfileDataStoreFactory` and `FakeProfileManager` test helpers — shared fixture

*Existing infrastructure covers framework installation.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Profile switch stops active playback | AUTH-01 (D-04) | Requires active media player session | 1. Start playback on profile 1. 2. Switch to profile 2 via sidebar. 3. Verify player stops and returns to home. |
| Shared settings hidden for non-default profiles | AUTH-05 (D-07) | UI visibility requires visual inspection | 1. Switch to non-default profile. 2. Open Settings. 3. Verify debrid/API sections are absent. |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 45s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
