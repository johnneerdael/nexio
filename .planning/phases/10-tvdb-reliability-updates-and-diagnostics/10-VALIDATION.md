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
| 10-00-01 | 00 | 0 | UX-03,CACHE-02,CACHE-03 | T-10-00 | Phase 6-9 source contracts are bound before Phase 10 implementation creates or edits app code | file gate | `test -f app/src/main/java/com/nexio/tv/data/local/TvdbSettingsDataStore.kt && test -f app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt && test -f app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt` | Existing after Phase 6-9 | pending |
| 10-00-02 | 00 | 0 | UX-03,CACHE-02,CACHE-03 | T-10-05,T-10-06 | Shared diagnostics contract redacts secrets and provides structured log fields for all producers | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbReliabilityDiagnosticsTest"` | No - W0 | pending |
| 10-00-03 | 00 | 0 | UX-03,CACHE-02,CACHE-03 | T-10-05 | Concrete `TvdbDiagnosticsRecorder` storage and Hilt binding exist before producer plans enter the graph | unit/static | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.TvdbDiagnosticsDataStoreTest" --tests "com.nexio.tv.core.tvdb.TvdbReliabilityDiagnosticsTest"` | No - W0 | pending |
| 10-01-01 | 01 | 1 | CACHE-02 | T-10-01 | `/updates` DTOs preserve delete/merge fields including `mergeToId` and `mergeToEntityType` | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbUpdateProcessorTest"` | No - W0 | pending |
| 10-01-02 | 01 | 1 | CACHE-02 | T-10-01,T-10-05 | `/updates` processing invalidates cache, persists merge aliases, emits update diagnostics/logs, and advances cursor only after success | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbUpdateProcessorTest"` | No - W0 | pending |
| 10-02-01 | 02 | 2 | CACHE-02,UX-03 | T-10-02 | WorkManager update checks are unique, network constrained, and cannot retry-loop on invalid credentials | unit/Robolectric | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbUpdateSchedulingTest"` | No - W0 | pending |
| 10-02-02 | 02 | 2 | CACHE-02,UX-03 | T-10-02,T-10-05 | Startup/worker coordinator and credential health gate emit sanitized diagnostics/logs while blocking invalid credentials | unit/Robolectric | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbUpdateSchedulingTest"` | No - W0 | pending |
| 10-03-01 | 03 | 3 | CACHE-03,UX-03 | T-10-03 | Reference endpoint contracts prove entity types use `/entities`, and cache namespace tests cover schema guard, stale labels, and batching | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbReferenceDataServiceTest" --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreTest" --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreWriteBatchingTest"` | Partial | pending |
| 10-03-02 | 03 | 3 | CACHE-03,UX-03 | T-10-03,T-10-05 | Reference service warms on startup after credential gating, refreshes from update events, and emits sanitized diagnostics/logs | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbReferenceDataServiceTest" --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreTest" --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreWriteBatchingTest"` | Partial | pending |
| 10-04-01 | 04 | 4 | UX-03 | T-10-04 | Tests prove TVDB outage, invalid credentials, field-level fallback, and merge alias read-path behavior | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbGracefulFallbackTest" --tests "com.nexio.tv.core.tvdb.TvdbCredentialHealthTest"` | No - W0 | pending |
| 10-04-02 | 04 | 4 | UX-03 | T-10-04,T-10-05 | TVDB metadata reads serve last-known-good data, resolve merge aliases, and emit provider/fallback diagnostics/logs without secrets | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbGracefulFallbackTest" --tests "com.nexio.tv.core.tvdb.TvdbCredentialHealthTest" --tests "com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest"` | No - W0 | pending |
| 10-05-01 | 05 | 5 | UX-03,CACHE-02,CACHE-03 | T-10-05,T-10-06 | Settings and Debug UI consume the existing sanitized diagnostics snapshot and surface status/detail without recreating recorder binding | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbDiagnosticsTest" --tests "com.nexio.tv.ui.screens.settings.TvdbSettingsViewModelTest"` | DataStore exists after 10-00 | pending |
| 10-06-01 | 06 | 6 | UX-03,CACHE-02,CACHE-03 | T-10-07 | Documentation contains TVDB setup, provider precedence, update/reference caching, exact timing, date-only fallback, stale-cache behavior, and diagnostics guidance | static/docs | `rg -n "TVDB|TMDB remains movie|poster-ratings|Continue Watching|airsTime|date-only|/updates|reference data|stale-cache|last-known-good|Debug settings|diagnostics" docs/nexio-power-user-setup-guide.md docs/nexio-features-list.md` | Partial | pending |

*Status: pending, green, red, flaky*

---

## Wave 0 Requirements

- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbUpdateProcessorTest.kt` - covers CACHE-02 `/updates` pagination, update/delete/merge invalidation, and cursor ordering.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbReliabilityDiagnosticsTest.kt` - covers shared reason codes, recorder contract expectations, sanitization, and structured log fields.
- [ ] `app/src/test/java/com/nexio/tv/data/local/TvdbDiagnosticsDataStoreTest.kt` - covers concrete recorder storage, sanitized persistence/logging, and Hilt binding before producer plans inject `TvdbDiagnosticsRecorder`.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbUpdateSchedulingTest.kt` - covers WorkManager unique periodic scheduling, network constraints, and app-start catch-up coordinator wiring if WorkManager is added.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbReferenceDataServiceTest.kt` - covers CACHE-03 warming, stale-on-failure, schema guard, and update-triggered refresh.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbGracefulFallbackTest.kt` - covers UX-03 last-known-good cache and explicit fallback behavior.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbDiagnosticsTest.kt` - covers settings/debug projection from the existing diagnostics snapshot.
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
| T-10-00 | Phase 10 implementation runs against guessed or absent Phase 6-9 TVDB classes | Wave 0 binding gate stops execution until exact TVDB source files and required symbols exist |
| T-10-01 | Update cursor advances before invalidation succeeds, permanently skipping changed records | Processor advances cursor only after all pages and cache mutations complete |
| T-10-02 | WorkManager or startup catch-up causes a TVDB auth/network retry storm | Credential-health gate blocks new calls on invalid credentials and worker reports a handled blocked state |
| T-10-03 | Malformed reference/update payload poisons cache or leaks raw IDs into user-visible UI | Validate entity types, IDs, timestamps, merge targets, labels, URLs, and schema versions before writes |
| T-10-04 | Outage or invalid credentials blanks TV detail or Continue Watching despite usable cached data | Serve last-known-good TVDB data first; explicit fallback only when cache cannot satisfy the surface |
| T-10-05 | Diagnostics or logs expose TVDB API key, PIN, bearer token, or auth headers | Diagnostic payloads use reason codes and non-secret status only |
| T-10-06 | Diagnostics are too vague to prove provider choice, fallback, date-only gating, poster override, or skipped TMDB TV fetches | Typed diagnostic reasons cover every reason required by context D-11 |
| T-10-07 | Docs cause incorrect provider setup or troubleshooting behavior | Docs state provider precedence, stale-cache behavior, update/reference caching, and diagnostics location exactly |

---

## Validation Sign-Off

- [x] All tasks have automated verify commands or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all missing test references
- [x] No watch-mode flags
- [x] Feedback latency target defined
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-04-14
