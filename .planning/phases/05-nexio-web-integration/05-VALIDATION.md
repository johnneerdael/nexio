---
phase: 5
slug: nexio-web-integration
status: planned
nyquist_compliant: true
wave_0_complete: false
created: 2026-04-14
---

# Phase 5 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Gradle JUnit (Android) |
| **Config file** | `app/build.gradle.kts` |
| **Quick run command** | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.*" -x lint` |
| **Full suite command** | `./gradlew testArm64DebugUnitTest` |
| **Estimated runtime** | ~30 seconds |

Note: nexio-web has no test framework detected. Web-side verification is manual via local dev server.

---

## Sampling Rate

- **After every task commit:** Run `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.*" -x lint`
- **After every plan wave:** Run `./gradlew testArm64DebugUnitTest`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 05-00-01 | 00 | 0 | WEB-01 | T-05-01 | Profile CRUD RPCs filter by auth.uid(), profile_index != 1 for delete | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileSyncServiceTest"` | ❌ planned in 05-00 | ⬜ pending |
| 05-00-02 | 00 | 0 | WEB-02 | T-05-02 | Per-profile auth tokens scoped by user_id + profile_id | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileAuthSyncTest"` | ❌ planned in 05-00 | ⬜ pending |
| 05-00-03 | 00 | 0 | WEB-03 | — | N/A | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileCatalogSyncTest"` | ❌ planned in 05-00 | ⬜ pending |
| 05-00-04 | 00 | 0 | WEB-04 | — | N/A | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileFormatterSyncTest"` | ❌ planned in 05-00 | ⬜ pending |
| 05-00-05 | 00 | 0 | WEB-05 | T-05-03 | MIME type check + sharp validation before storage; public bucket only for avatar images | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.profile.ProfileAvatarTest"` | ❌ planned in 05-00 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

Planned in `.planning/phases/05-nexio-web-integration/05-00-PLAN.md`.

- [ ] `app/src/test/java/com/nexio/tv/sync/ProfileSyncServiceTest.kt` — stubs for WEB-01 (created by 05-00 Task 1)
- [ ] `app/src/test/java/com/nexio/tv/sync/ProfileAuthSyncTest.kt` — stubs for WEB-02 (created by 05-00 Task 1)
- [ ] `app/src/test/java/com/nexio/tv/sync/ProfileCatalogSyncTest.kt` — stubs for WEB-03 (created by 05-00 Task 2)
- [ ] `app/src/test/java/com/nexio/tv/sync/ProfileFormatterSyncTest.kt` — stubs for WEB-04 (created by 05-00 Task 2)
- [ ] `app/src/test/java/com/nexio/tv/profile/ProfileAvatarTest.kt` — stubs for WEB-05 (created by 05-00 Task 3)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| nexio-web UI renders ProfileDashboard correctly | WEB-01 | No web test framework; visual/interaction verification | Start `npm run dev` in nexio-web/, navigate to account page, verify profile grid renders |
| Trakt/Simkl device-flow completes on web | WEB-02 | Requires live Trakt/Simkl OAuth endpoints | Start dev server, click "Link Trakt" on a profile, complete device flow |
| Photo upload resizes and displays | WEB-05 | Requires Supabase Storage bucket and live upload | Upload a photo via ProfileDetailShell, verify 256x256 resize, verify TV app displays it |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references via 05-00-PLAN.md
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** planned; execution remains pending until 05-00 completes
