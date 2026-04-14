---
phase: 07
slug: tvdb-provider-replacement
status: draft
nyquist_compliant: true
wave_0_complete: false
created: 2026-04-14
---

# Phase 07 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4, MockK, kotlinx-coroutines-test, Robolectric where Android APIs are needed |
| **Config file** | `app/build.gradle.kts` |
| **Quick run command** | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.*"` |
| **Full suite command** | `./gradlew testArm64DebugUnitTest` |
| **Estimated runtime** | ~60-300 seconds depending on test scope |

---

## Sampling Rate

- **After every task commit:** Run the targeted test class for the touched mapper/router/ViewModel.
- **After every plan wave:** Run `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.*"` plus touched existing Detail/Home/poster tests.
- **Before `/gsd-verify-work`:** Run `./gradlew testArm64DebugUnitTest`.
- **Max feedback latency:** 300 seconds for targeted tests, full suite before phase completion.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 07-01-01 | 01 | 1 | PREF-02, META-01, META-02, META-04 | T-07-01 / T-07-03 | TVDB metadata maps into existing UI-facing roles without leaking credentials or mixing TMDB cache namespaces | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"` | ❌ W0 | ⬜ pending |
| 07-01-02 | 01 | 1 | PREF-03 | T-07-02 / T-07-04 | TVDB-active TV success paths skip TMDB TV metadata calls and record provider decisions | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvMetadataRouterTest"` | ❌ W0 | ⬜ pending |
| 07-02-01 | 02 | 2 | PREF-02, PREF-03, META-02 | T-07-02 | Detail TV enrichment and season episode lists use TVDB first and call TMDB only through explicit fallback | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.detail.MetaDetailsTvdbProviderRoutingTest"` | ❌ W0 | ⬜ pending |
| 07-03-01 | 03 | 2 | PREF-02, PREF-03 | T-07-02 | Home and Continue Watching TV metadata use TVDB first without duplicate TMDB TV metadata fetches | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.*Tvdb*"` | ❌ W0 | ⬜ pending |
| 07-04-01 | 04 | 2 | PREF-07, META-04 | T-07-03 | Poster-ratings poster URLs survive TVDB replacement while TVDB non-poster artwork applies | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.poster.PosterRatingsUrlResolverTest"` | ✅ | ⬜ pending |
| 07-05-01 | 05 | 3 | UX-01 | — | Settings copy states TVDB for TV, TMDB for movies/fallback, and poster-ratings for supported posters | unit/resource review | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.settings.ProviderPrecedenceCopyTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt` — mapper/service tests for `META-01`, `META-02`, and `META-04`.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt` — TVDB active/success/fallback/skip-TMDB decision tests for `PREF-02` and `PREF-03`.
- [ ] `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt` or equivalent — representative detail path no-TMDB-call test.
- [ ] `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt` or equivalent — representative Home/Continue Watching no-TMDB-call test.
- [ ] Add a TVDB ID assertion to `app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt` for TopPosters `tvdb:` poster URLs.
- [ ] `app/src/test/java/com/nexio/tv/ui/screens/settings/ProviderPrecedenceCopyTest.kt` or equivalent resource assertion for provider precedence copy.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Settings copy reads clearly on Android TV | UX-01 | Text readability and focus behavior are easier to review on device/emulator after copy lands | Open Settings > Integrations, inspect TVDB/TMDB/poster-ratings copy, verify focus remains usable and copy states provider precedence without implying TMDB is the active TV source when TVDB is configured |

---

## Threat Model References

| Threat Ref | Threat | Required Mitigation |
|------------|--------|---------------------|
| T-07-01 | TVDB API key/PIN leaks through logs, diagnostics, cache keys, or tests | Do not log or store secret material in planning docs, cache keys, diagnostics, or test fixtures; consume Phase 6 secret-backed credentials |
| T-07-02 | Silent fallback masks unexpected TMDB TV metadata calls | Centralize fallback in the router and emit diagnostics for TVDB inactive, TVDB success, TVDB fallback to TMDB, and TMDB TV skipped |
| T-07-03 | Cache/provider confusion mixes TVDB and TMDB metadata or poster overrides | Use separate `tvdb::` cache namespace/schema version and include poster-provider token where cached output includes poster URL |
| T-07-04 | Invalid or ambiguous IDs route the wrong TV record | Resolve TVDB identity through Phase 6 identity service before TVDB calls and call TMDB identity only in explicit fallback |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 300s for targeted test loops
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-04-14
