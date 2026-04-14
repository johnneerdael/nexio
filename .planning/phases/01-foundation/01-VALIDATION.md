---
phase: 1
slug: foundation
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-14
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4 + Robolectric (`@RunWith(RobolectricTestRunner::class)`) |
| **Config file** | Standard Android test runner config via `build.gradle.kts` |
| **Quick run command** | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStore*" --tests "com.nexio.tv.core.profile.ProfileManager*"` |
| **Full suite command** | `./gradlew testArm64DebugUnitTest` |
| **Estimated runtime** | ~30 seconds (quick), ~120 seconds (full) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStore*" --tests "com.nexio.tv.core.profile.ProfileManager*"`
- **After every plan wave:** Run `./gradlew testArm64DebugUnitTest`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** ~30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 01-01-01 | 01 | 0 | INFRA-01 | — | N/A | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStoreFactoryTest"` | ❌ W0 | ⬜ pending |
| 01-01-02 | 01 | 0 | INFRA-05 | — | N/A | unit | same | ❌ W0 | ⬜ pending |
| 01-01-03 | 01 | 0 | INFRA-07 | — | N/A | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStoreTest"` | ❌ W0 | ⬜ pending |
| 01-01-04 | 01 | 0 | INFRA-02 | — | N/A | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.profile.ProfileManagerTest"` | ❌ W0 | ⬜ pending |
| 01-01-05 | 01 | 0 | INFRA-03 | — | N/A | unit | same | ❌ W0 | ⬜ pending |
| 01-01-06 | 01 | 0 | INFRA-04 | — | N/A | unit | same | ❌ W0 | ⬜ pending |
| 01-02-01 | 02 | 1 | INFRA-01 | — | N/A | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStoreFactoryTest"` | ❌ W0 | ⬜ pending |
| 01-02-02 | 02 | 1 | INFRA-05 | — | N/A | unit | same | ❌ W0 | ⬜ pending |
| 01-03-01 | 03 | 1 | INFRA-07 | — | N/A | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStoreTest"` | ❌ W0 | ⬜ pending |
| 01-04-01 | 04 | 2 | INFRA-02, INFRA-03, INFRA-04 | — | N/A | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.profile.ProfileManagerTest"` | ❌ W0 | ⬜ pending |
| 01-05-01 | 05 | 2 | INFRA-06 | — | N/A | build | `./gradlew assembleArm64Debug` | ❌ implicit | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreFactoryTest.kt` — stubs for INFRA-01, INFRA-05
- [ ] `app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreTest.kt` — stubs for INFRA-07, silent migration (D-06), corrupted JSON fallback
- [ ] `app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt` — stubs for INFRA-02, INFRA-03, INFRA-04, slot reuse, deletion guard

*Existing infrastructure covers framework setup — only test files need creation.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Hilt DI graph compiles and injects singletons | INFRA-06 | Hilt compile-time validation only runs during full build | `./gradlew assembleArm64Debug` must complete without Hilt errors |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
