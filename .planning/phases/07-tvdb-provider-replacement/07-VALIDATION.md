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
| 07-01-01 | 01 | 1 | PREF-02, PREF-03, META-01, META-02, META-04 | T-07-01 / T-07-04 | Phase 6 TVDB settings/auth/identity/API source exists before Phase 7 code starts | prerequisite | `test -f app/src/main/java/com/nexio/tv/data/local/TvdbSettingsDataStore.kt && test -f app/src/main/java/com/nexio/tv/data/local/TvdbTokenStore.kt && test -f app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt && test -f app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt && test -f app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt && test -f app/src/main/java/com/nexio/tv/domain/model/TvdbSettings.kt && test -f app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt` | prereq | ⬜ pending |
| 07-01-02 | 01 | 1 | PREF-02, META-01, META-02, META-04 | T-07-01 / T-07-02 | Provider-neutral models support nullable TVDB IDs, TVDB-only retained fields, and diagnostic event names | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvMetadataModelsTest"` | ❌ W0 | ⬜ pending |
| 07-01-03 | 01 | 1 | PREF-02, META-01, META-02, META-04 | T-07-03 | TVDB enrichment and episode cache entries use `tvdb::` / `tvdb_episode::` namespaces | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreTvdbTest"` | ❌ W0 | ⬜ pending |
| 07-02-01 | 02 | 2 | META-01, META-02, META-04 | T-07-01 / T-07-03 | TVDB API metadata endpoints and DTOs exist without duplicating credential/login code | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"` | ❌ W0 | ⬜ pending |
| 07-02-02 | 02 | 2 | PREF-02, PREF-07, META-01, META-02, META-04 | T-07-01 / T-07-03 | TVDB mapper writes provider-neutral metadata, preserves poster-ratings precedence, and uses TVDB cache methods | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"` | ❌ W0 | ⬜ pending |
| 07-02-03 | 02 | 2 | PREF-02, PREF-03, META-01, META-02 | T-07-02 / T-07-04 | Router centralizes TVDB success, explicit TMDB fallback, nullable fallback IDs, and skip-TMDB diagnostics | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvMetadataRouterTest"` | ❌ W0 | ⬜ pending |
| 07-06-01 | 06 | 2 | PREF-07, META-04 | T-07-03 | TopPosters/RPDB poster precedence handles TVDB IDs without leaking secrets | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.poster.PosterRatingsUrlResolverTest"` | ✅ | ⬜ pending |
| 07-06-02 | 06 | 2 | UX-01 | T-07-02 | Resource copy states TVDB for TV, TMDB for movies/fallback, poster-ratings for supported posters | unit/resource review | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.settings.ProviderPrecedenceCopyTest"` | ❌ W0 | ⬜ pending |
| 07-06-03 | 06 | 2 | UX-01, PREF-07 | T-07-02 / T-07-03 | Settings screens consume precedence copy without adding a TVDB toggle matrix | unit/resource review | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.settings.ProviderPrecedenceCopyTest" --tests "com.nexio.tv.core.poster.PosterRatingsUrlResolverTest"` | mixed | ⬜ pending |
| 07-03-01 | 03 | 3 | PREF-02, PREF-03, META-01, META-04 | T-07-02 / T-07-04 | Detail series enrichment goes through router before any TMDB ID conversion | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.detail.MetaDetailsTvdbProviderRoutingTest"` | ❌ W0 | ⬜ pending |
| 07-03-02 | 03 | 3 | PREF-02, PREF-03, META-02 | T-07-02 / T-07-04 | Detail episode metadata maps TVDB episode title/overview/released/thumbnail/runtime into `Video` rows | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.detail.MetaDetailsTvdbProviderRoutingTest"` | ❌ W0 | ⬜ pending |
| 07-03-03 | 03 | 3 | PREF-02, PREF-03, META-02 | T-07-02 / T-07-04 | Mark-season-watched uses TVDB authoritative episode list with existing date-only air gate | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.detail.MarkSeasonWatchedTest"` | ✅ | ⬜ pending |
| 07-04-01 | 04 | 3 | PREF-02, PREF-03 | T-07-02 / T-07-04 | Continue Watching HomeViewModel injection keeps movie/fallback paths while adding router access | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest"` | ❌ W0 | ⬜ pending |
| 07-04-02 | 04 | 3 | PREF-02, PREF-03, META-01, META-04 | T-07-02 / T-07-03 | Continue Watching display metadata uses TVDB first and preserves fallback merge semantics | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest" --tests "com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest"` | mixed | ⬜ pending |
| 07-04-03 | 04 | 3 | PREF-02, PREF-03, META-02 | T-07-02 / T-07-04 | Continue Watching runtime hydration uses TVDB episode/series runtime before explicit fallback | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest"` | ❌ W0 | ⬜ pending |
| 07-05-01 | 05 | 4 | PREF-02, PREF-03, META-01, META-04 | T-07-02 / T-07-04 | Focused, adjacent, and hero Home enrichment use TVDB for TV and TMDB for movies | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest"` | ❌ W0 | ⬜ pending |
| 07-05-02 | 05 | 4 | PREF-02, PREF-03, META-01, META-04 | T-07-02 / T-07-03 | Home catalog refresh metadata hydration logs fallback/skip diagnostics and preserves movie TMDB behavior | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTvdbTest"` | ❌ W0 | ⬜ pending |
| 07-05-03 | 05 | 4 | PREF-02, PREF-03, META-01, META-04 | T-07-02 / T-07-04 | Home provider routing suite covers both ViewModel and catalog refresh paths | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest" --tests "com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTvdbTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataModelsTest.kt` — provider-neutral model and nullable `seriesTvdbId` tests.
- [ ] `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTvdbTest.kt` — TVDB cache namespace and stale epoch cleanup tests.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt` — API DTO/mapper/service tests for `META-01`, `META-02`, `META-04`, and poster precedence.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt` — TVDB active/success/fallback/skip-TMDB decision tests for `PREF-02` and `PREF-03`, including nullable TMDB fallback/movie `seriesTvdbId`.
- [ ] `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt` or equivalent — representative detail path no-TMDB-call test.
- [ ] Extend `app/src/test/java/com/nexio/tv/ui/screens/detail/MarkSeasonWatchedTest.kt` for TVDB authoritative episode list routing.
- [ ] `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt` or equivalent — representative Home/Continue Watching no-TMDB-call test.
- [ ] `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTvdbTest.kt` or equivalent — catalog hydration routing and diagnostics test.
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
