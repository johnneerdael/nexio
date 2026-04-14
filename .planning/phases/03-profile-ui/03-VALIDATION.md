---
phase: 3
slug: profile-ui
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-14
---

# Phase 3 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Robolectric (Android unit tests) |
| **Config file** | `app/build.gradle.kts` |
| **Quick run command** | `./gradlew testArm64DebugUnitTest` |
| **Full suite command** | `./gradlew testArm64DebugUnitTest` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew testArm64DebugUnitTest`
- **After every plan wave:** Run `./gradlew testArm64DebugUnitTest`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 3-01-01 | 01 | 1 | UI-01 | — | N/A | unit | `./gradlew testArm64DebugUnitTest` | ❌ W0 | ⬜ pending |
| 3-01-02 | 01 | 1 | UI-02 | — | N/A | unit | `./gradlew testArm64DebugUnitTest` | ❌ W0 | ⬜ pending |
| 3-02-01 | 02 | 1 | UI-03 | — | N/A | unit | `./gradlew testArm64DebugUnitTest` | ❌ W0 | ⬜ pending |
| 3-02-02 | 02 | 1 | UI-04 | — | N/A | unit | `./gradlew testArm64DebugUnitTest` | ❌ W0 | ⬜ pending |
| 3-03-01 | 03 | 2 | UI-05, UI-06 | — | N/A | unit | `./gradlew testArm64DebugUnitTest` | ❌ W0 | ⬜ pending |
| 3-04-01 | 04 | 2 | UI-07, UI-08 | — | N/A | unit | `./gradlew testArm64DebugUnitTest` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] ViewModel unit tests for ProfileSelectionViewModel (session gating, profile list)
- [ ] ViewModel unit tests for PinEntryViewModel (PIN validation, rate limiting)
- [ ] Existing test infrastructure covers framework needs

*Existing infrastructure covers framework installation — JUnit 5 and Robolectric already configured.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| D-pad navigation through profile avatars | UI-02 | Requires physical remote or emulator input | Navigate with D-pad arrows, verify focus ring moves between all avatars |
| PIN numpad 3x4 grid D-pad focus traversal | UI-03 | Requires D-pad hardware/emulator | Navigate all 12 cells with D-pad, verify no dead zones |
| Sidebar profile expansion animation | UI-07 | Visual verification needed | Expand sidebar profile section, verify smooth animation and D-pad traversal |
| 10-foot viewing distance readability | UI-02 | Physical viewing distance test | View selection screen from 10 feet on TV |

*If none: "All phase behaviors have automated verification."*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
