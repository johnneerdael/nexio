---
phase: 10
slug: tvdb-reliability-updates-and-diagnostics
status: ready
nyquist_compliant: true
wave_0_complete: false
created: 2026-04-14
---

# Phase 10 - Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4 `4.13.2`, MockK `1.13.12`, kotlinx-coroutines-test `1.8.1`, MockWebServer `4.12.0`, Robolectric `4.13`, WorkManager testing `2.11.2` if WorkManager is added |
| **Config file** | `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml` |
| **Quick run command** | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.*" --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreTest"` |
| **Full suite command** | `./gradlew testArm64DebugUnitTest` |
| **Estimated runtime** | ~120 seconds quick, project-dependent full suite |

---

## Sampling Rate

- **After every task commit:** Run the narrow test class for the touched update, cache, reference-data, credential-health, diagnostic, settings, or docs component.
- **After every plan wave:** Run `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.*"` plus touched settings/cache/Continue Watching tests after actual Phase 6-9 package names are known.
- **Before `/gsd-verify-work`:** Run `./gradlew testArm64DebugUnitTest`, `./gradlew assembleArm64Debug`, and `./gradlew lintArm64Debug`.
- **Max feedback latency:** 5 minutes for narrow tests, full suite before final verification.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 10-01-01 | 01 | 0 | CACHE-02 | T-10-01 | `/updates` pagination, update/delete/merge invalidation, and cursor advancement happen without exposing credentials | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbUpdateProcessorTest"` | No - W0 | pending |
| 10-01-02 | 01 | 0 | CACHE-02 | T-10-02 | WorkManager update checks are unique, network constrained, and cannot retry-loop on invalid credentials | unit/Robolectric | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbUpdateSchedulingTest"` | No - W0 | pending |
| 10-02-01 | 02 | 0 | CACHE-03 | T-10-03 | Reference data is schema-guarded, update-aware, and stale-serveable without raw secret leakage | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbReferenceDataServiceTest"` | No - W0 | pending |
| 10-02-02 | 02 | 0 | CACHE-03 | T-10-03 | TVDB cache/reference namespaces ignore old schema payloads and preserve write batching | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreTest" --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreWriteBatchingTest"` | Partial | pending |
| 10-03-01 | 03 | 0 | UX-03 | T-10-04 | TVDB outage serves last-known-good data before explicit fallback and never blanks safe cached TV detail or Continue Watching metadata | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbGracefulFallbackTest"` | No - W0 | pending |
| 10-03-02 | 03 | 0 | UX-03 | T-10-05 | Invalid credentials block new TVDB network calls, keep cached data, and surface invalid status without repeated unauthorized retries | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbCredentialHealthTest" --tests "com.nexio.tv.ui.screens.settings.TvdbSettingsViewModelTest"` | No - W0 | pending |
| 10-04-01 | 04 | 0 | UX-03 | T-10-06 | Diagnostics represent provider choice, fallback reason, missing `airsTime`, date-only gating, poster-ratings override, skipped TMDB fetches, update status, stale cache, and invalid credentials | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbDiagnosticsTest"` | No - W0 | pending |
| 10-04-02 | 04 | 0 | UX-03 | T-10-06 | Documentation contains TVDB setup, provider precedence, poster-ratings precedence, exact timing, date-only fallback, stale-cache behavior, and diagnostics guidance | static/docs | `rg -n "TVDB|poster-ratings|Continue Watching|date-only|stale cache|diagnostics" docs app/src/main/res` | Partial | pending |

*Status: pending, green, red, flaky*

---

## Wave 0 Requirements

- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbUpdateProcessorTest.kt` - covers CACHE-02 `/updates` pagination, update/delete/merge invalidation, and cursor ordering.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbUpdateSchedulingTest.kt` - covers WorkManager unique periodic scheduling, network constraints, and app-start catch-up coordinator wiring if WorkManager is added.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbReferenceDataServiceTest.kt` - covers CACHE-03 warming, stale-on-failure, schema guard, and update-triggered refresh.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbGracefulFallbackTest.kt` - covers UX-03 last-known-good cache and explicit fallback behavior.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbDiagnosticsTest.kt` - covers all required diagnostic reason codes.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbCredentialHealthTest.kt` - covers invalid credential network-call blocking without cache purge.
- [ ] Extend `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt` and `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreWriteBatchingTest.kt` for TVDB cache/reference namespace behavior.
- [ ] Extend settings/docs coverage or add static assertions after Phase 6 adds TVDB strings and settings classes.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| TVDB settings diagnostic status is understandable on Android TV | UX-03 | Compose TV UI copy/focus behavior may need emulator/device review beyond unit assertions | Open TVDB settings and Debug settings on an Android TV emulator/device; verify status text distinguishes valid, invalid credentials, stale cache served, and last update refresh state |
| User-facing docs answer setup and troubleshooting questions | UX-03 | Documentation usefulness is partly editorial | Read the updated TVDB docs and confirm they include setup, provider precedence, poster-ratings precedence, exact Continue Watching timing, date-only fallback, stale-cache behavior, and diagnostics location |

---

## Security Threat References

| Threat Ref | Threat | Mitigation Expected In Plans |
|------------|--------|------------------------------|
| T-10-01 | Update cursor advances before invalidation succeeds, permanently skipping changed records | Processor advances cursor only after all pages and cache mutations complete |
| T-10-02 | WorkManager or startup catch-up causes a TVDB auth/network retry storm | Credential-health gate blocks new calls on invalid credentials and worker reports a handled blocked state |
| T-10-03 | Malformed reference/update payload poisons cache or leaks raw IDs into user-visible UI | Validate entity types, IDs, timestamps, merge targets, labels, URLs, and schema versions before writes |
| T-10-04 | Outage or invalid credentials blanks TV detail or Continue Watching despite usable cached data | Serve last-known-good TVDB data first; explicit fallback only when cache cannot satisfy the surface |
| T-10-05 | Diagnostics or logs expose TVDB API key, PIN, bearer token, or auth headers | Diagnostic payloads use reason codes and non-secret status only |
| T-10-06 | Diagnostics are too vague to prove provider choice, fallback, date-only gating, poster override, or skipped TMDB TV fetches | Typed diagnostic reasons cover every reason required by context D-11 |

---

## Validation Sign-Off

- [x] All tasks have automated verify commands or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all missing test references
- [x] No watch-mode flags
- [x] Feedback latency target defined
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-04-14
