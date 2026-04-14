---
phase: 4
slug: sync-and-cleanup
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-14
---

# Phase 4 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit4 + Kotlin coroutines test (already in project) |
| **Config file** | `build.gradle.kts` (test dependencies already configured) |
| **Quick run command** | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.*"` |
| **Full suite command** | `./gradlew testArm64DebugUnitTest` |
| **Estimated runtime** | ~45 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.*"`
- **After every plan wave:** Run `./gradlew testArm64DebugUnitTest`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 45 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 4-01-01 | 01 | 1 | SYNC-01 | — | ProfileSyncService push encodes all UserProfile fields | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.ProfileSyncServiceTest"` | ❌ W0 | ⬜ pending |
| 4-01-02 | 01 | 1 | SYNC-01 | — | ProfileSyncService pull replaces profile list atomically | unit | same | ❌ W0 | ⬜ pending |
| 4-02-01 | 02 | 1 | SYNC-02 | — | profilePrefsName() returns bare name for profile 1, suffixed for 2-4 | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfilePrefsNameTest"` | ❌ W0 | ⬜ pending |
| 4-02-02 | 02 | 1 | SYNC-02 | — | ProfileSettingsSyncService blob encodes all 7 Preference types | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.ProfileSettingsSyncServiceTest"` | ❌ W0 | ⬜ pending |
| 4-03-01 | 03 | 2 | SYNC-03 | — | deleteSharedPreferencesForProfile() clears and deletes all 7 SP files | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.profile.ProfileManagerTest"` | ❌ W0 | ⬜ pending |
| 4-03-02 | 03 | 2 | SYNC-03 | — | Profile deletion with remote failure still completes local deletion | unit | same | ❌ W0 | ⬜ pending |
| 4-04-01 | 04 | 1 | SYNC-04 | — | TraktLibrarySnapshotStore reads from profile-suffixed SP name | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.TraktLibrarySnapshotStoreTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `app/src/test/.../core/sync/ProfileSyncServiceTest.kt` — stubs for SYNC-01
- [ ] `app/src/test/.../core/sync/ProfileSettingsSyncServiceTest.kt` — stubs for SYNC-02
- [ ] `app/src/test/.../data/local/ProfilePrefsNameTest.kt` — stubs for SYNC-02 naming helper
- [ ] `app/src/test/.../core/profile/ProfileManagerTest.kt` (extend existing if present) — stubs for SYNC-03 deletion
- [ ] `app/src/test/.../data/local/TraktLibrarySnapshotStoreTest.kt` — stubs for SYNC-04 per-profile reads

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Sync Now button shows brief feedback | SYNC-01 | UI interaction on Android TV | Navigate to Settings > Profiles, focus Sync Now, press enter, verify "Synced" or "Failed" appears |
| Delete confirmation dialog appears | SYNC-03 | UI interaction on Android TV | Navigate to profile, trigger delete, verify NexioDialog with "Keep Profile" and "Delete Profile" |
| Startup pull is silent — no loading indication | SYNC-01 | Timing/visual behavior | Cold start app, observe no splash hold or sync indicator |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 45s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
