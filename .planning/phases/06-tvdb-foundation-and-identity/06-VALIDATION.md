---
phase: 6
slug: tvdb-foundation-and-identity
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-14
---

# Phase 6 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit4, MockK, kotlinx-coroutines-test, MockWebServer, Robolectric |
| **Config file** | `app/build.gradle.kts` |
| **Quick run command** | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.*" --tests "com.nexio.tv.ui.screens.settings.TvdbSettingsViewModelTest" --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"` |
| **Full suite command** | `./gradlew testArm64DebugUnitTest` |
| **Estimated runtime** | ~60-180 seconds |

---

## Sampling Rate

- **After every task commit:** Run the most specific TVDB/sync/settings test command for touched modules.
- **After every plan wave:** Run `./gradlew testArm64DebugUnitTest`.
- **Before `/gsd-verify-work`:** `./gradlew testArm64DebugUnitTest` and `./gradlew assembleArm64Debug` must pass.
- **Max feedback latency:** ~180 seconds for unit suite sampling.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 06-01-01 | 01 | 0 | PREF-01 | T-06-01 | TVDB credential validation omits blank PIN, stores no secrets in logs, and handles 401 safely | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbAuthServiceTest"` | ❌ W0 | ⬜ pending |
| 06-01-02 | 01 | 0 | PREF-06, CACHE-01 | T-06-02 | Identity lookup caches token/results, de-dupes parallel calls, and does not call TMDB for TV identity | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbIdentityServiceTest"` | ❌ W0 | ⬜ pending |
| 06-01-03 | 01 | 0 | PREF-01, PREF-05 | T-06-01 | TVDB settings expose validation state and prevent enabling with missing/invalid credentials | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.settings.TvdbSettingsViewModelTest"` | ❌ W0 | ⬜ pending |
| 06-01-04 | 01 | 0 | PREF-01 | T-06-01 | Account sync public payload includes TVDB non-secret state and omits API key/PIN/token | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"` | ✅ | ⬜ pending |
| 06-01-05 | 01 | 0 | PREF-04, PREF-05 | T-06-03 | Inactive TVDB leaves TMDB behavior unchanged; active unusable TVDB records diagnostic fallback | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbProviderFallbackTest" --tests "com.nexio.tv.core.tvdb.TvdbDiagnosticsTest"` | ❌ W0 | ⬜ pending |
| 06-01-06 | 01 | 0 | PREF-01 | T-06-01 | Supabase secret allowlists accept `tvdb_api_key` everywhere required | static | `grep -n "tvdb_api_key" supabase/account_settings_sync.sql` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbAuthServiceTest.kt` — covers `/login` payload, blank PIN omission, token cache, and 401 handling.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbIdentityServiceTest.kt` — covers broad remote-ID normalization, in-flight de-duping, cache hit, and no TMDB dependency.
- [ ] `app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModelTest.kt` — covers validation states and enablement gating.
- [ ] Extend `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt` — covers TVDB public sync fields and credential omission.
- [ ] Add SQL/static verification for every `tvdb_api_key` allowlist occurrence in `supabase/account_settings_sync.sql`.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| TVDB settings are D-pad navigable and precedence copy is readable on Android TV | PREF-01, PREF-05 | JVM tests can verify state, not full TV focus behavior | Build `assembleArm64Debug`, open Settings > Integration > TVDB, verify focus order, API key/PIN dialog, validation status, and precedence copy with D-pad |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 180s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
