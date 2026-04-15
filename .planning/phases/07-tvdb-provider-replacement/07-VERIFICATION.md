---
phase: 07-tvdb-provider-replacement
verified: 2026-04-15T11:23:03Z
status: passed
score: "8/8 must-haves verified"
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: "5/8"
  gaps_closed:
    - "Continue Watching TV metadata can use TVDB as the normal provider when TVDB is active, without requiring TMDB to be active"
    - "TMDB TV fallback is explicit and respects TMDB provider settings"
    - "TVDB season episode cache does not persist failed TVDB requests as empty authoritative seasons"
  gaps_remaining: []
  regressions: []
deferred:
  - truth: "PREF-02 broad surfaces for TV trailers, related content, credits/cast, and networks"
    addressed_in: "Phase 9"
    evidence: "Phase 9 success criteria cover TVDB trailers plus characters/cast, companies, networks, genres, and content ratings."
external_verification:
  - test: "./gradlew testArm64DebugUnitTest --continue"
    expected: "Unit test task completes with Phase 7 code included."
    result: "passed: BUILD SUCCESSFUL in 50s"
  - test: "Phase 7 security verification"
    expected: ".planning/phases/07-tvdb-provider-replacement/07-SECURITY.md exists and records the enforced security gate result."
    result: "passed: 07-SECURITY.md status secured, threats_open 0"
---

# Phase 7: TVDB Provider Replacement Verification Report

**Phase Goal:** TVDB replaces TMDB as the normal TV metadata provider across existing TV enrichment surfaces, while poster-ratings integrations remain authoritative for poster imagery
**Verified:** 2026-04-15T11:23:03Z
**Status:** passed
**Re-verification:** Yes - after gap closure plans 07-07 and 07-08

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Phase 6 prerequisites, provider-neutral models, TVDB cache namespaces, and diagnostics exist | VERIFIED | `TvMetadataDiagnostics.kt` includes `TMDB_TV_SKIPPED` and `POSTER_RATINGS_OVERRIDE`; `MetadataDiskCacheStore.kt` includes `TVDB_PREFIX` and `TVDB_EPISODE_PREFIX`; Plan 07-01 artifacts are still present and substantive. |
| 2 | TVDB series and episode fields populate provider-neutral roles used by existing UI contracts | VERIFIED | `TvdbMetadataService.kt` maps series title, description, genres, artwork, runtime, ratings, schedule, status, aliases, content ratings, remote IDs, and episode fields into `TvMetadataEnrichment`/`TvEpisodeMetadata`. |
| 3 | Detail screen series enrichment, episode rows, and mark-season-watched use `TvMetadataRouter` for TV | VERIFIED | `MetaDetailsViewModel.kt` calls `tvMetadataRouter.fetchEnrichment`, `fetchEpisodeEnrichment`, and `fetchSeasonEpisodes` for TV paths; prior passed item had no regression in the gap-owned changes. |
| 4 | Continue Watching TV metadata can use TVDB as the normal provider without requiring TMDB to be active | VERIFIED | Gap closed. `shouldEnrichContinueWatchingProviderMetadata` allows TV/series rows when TMDB is inactive while still respecting `useBasicInfo` (`HomeViewModelContinueWatching.kt:34`). Snapshot enrichment uses it at `HomeViewModelContinueWatching.kt:75`, and manual refresh uses it at `HomeViewModelContinueWatching.kt:528`. |
| 5 | Home focused, hero, and catalog TV metadata route through TVDB-first routing | VERIFIED | Focus/hero and catalog paths still call `tvMetadataRouter.fetchEnrichment`; no regression found in Phase 7 gap-owned files. |
| 6 | Poster-ratings provider URLs override poster imagery without suppressing non-poster artwork, and settings explain precedence | VERIFIED | `TvdbMetadataService.kt:163` applies `PosterRatingsUrlResolver` only to poster output; settings copy exists in `strings.xml:849`, and `PosterRatingsUrlResolverTest.kt` covers TopPosters `tvdb/poster`. |
| 7 | TMDB TV fallback is explicit and respects TMDB provider settings | VERIFIED | Gap closed. `TvMetadataRouter` now injects `TmdbSettingsDataStore` (`TvMetadataRouter.kt:7`, `TvMetadataRouter.kt:18`), checks `canUseTmdbFallback()` before all fallback helper work (`TvMetadataRouter.kt:161`, `TvMetadataRouter.kt:190`, `TvMetadataRouter.kt:220`), and reads `settings.first().isActive` at `TvMetadataRouter.kt:244`. |
| 8 | TVDB season episode cache does not persist failed TVDB requests as empty authoritative seasons | VERIFIED | Gap closed. `fetchSeasonEpisodes` returns before cache writes when the TVDB call throws (`TvdbMetadataService.kt:122`) or returns non-success (`TvdbMetadataService.kt:134`); `writeTvdbSeasonEpisodes` is reached only after success at `TvdbMetadataService.kt:146`. |

**Score:** 8/8 truths verified at source level

### Deferred Items

Items not yet met but explicitly addressed in later milestone phases.

| # | Item | Addressed In | Evidence |
|---|------|--------------|----------|
| 1 | PREF-02 broad surfaces for TV trailers, related content, credits/cast, and networks | Phase 9 | Phase 9 success criteria explicitly cover TVDB trailers plus characters/cast, companies, networks, genres, and content ratings. |

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt` | Provider-neutral TV metadata contracts | VERIFIED | Exists and substantive; unchanged from prior pass. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt` | Provider diagnostic event names | VERIFIED | Includes inactive, success, fallback, skipped-TMDB, and poster override reasons. |
| `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt` | TVDB metadata cache namespace | VERIFIED | TVDB prefixes, schema versions, read/write methods, and stale prefix awareness exist. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt` | TVDB series/episode metadata mapping and season cache behavior | VERIFIED | Artifact verifier passed; manual trace confirms failure paths return before season cache writes. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt` | TVDB-first routing with explicit TMDB fallback | VERIFIED | Artifact verifier passed; manual trace confirms TMDB settings guard runs before TMDB fallback calls. |
| `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt` | TVDB metadata API endpoints | VERIFIED | Prior passed item; no gap-owned regression. |
| `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` | Detail screen TVDB routing | VERIFIED | Prior passed item; no gap-owned regression. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` | Continue Watching TVDB display metadata routing | VERIFIED | Artifact verifier passed; shared gate is wired in snapshot and manual refresh paths. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt` | Continue Watching TVDB runtime routing | VERIFIED | Prior passed item; no gap-owned regression. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt` | Focused/hero TV routing | VERIFIED | Prior passed item; no gap-owned regression. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt` | Catalog TV routing and diagnostics | VERIFIED | Prior passed item; no gap-owned regression. |
| `app/src/main/res/values/strings.xml` | Provider precedence copy | VERIFIED | `provider_precedence_summary` states TVDB/TMDB/poster-ratings precedence. |
| `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt` | Disabled TMDB fallback regression coverage | VERIFIED_SOURCE_ONLY | Tests exist and assert zero direct TMDB calls, but they did not execute because unit-test compilation is externally blocked. |
| `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt` | Failed season request cache regression coverage | VERIFIED_SOURCE_ONLY | Tests exist for thrown, non-success, and successful-empty responses; execution is externally blocked. |
| `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt` | TVDB-only Continue Watching regression coverage | VERIFIED_SOURCE_ONLY | Gate and metadata tests exist, including TMDB-disabled TVDB metadata merge; execution is externally blocked. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `MetadataDiskCacheStore` | `TvMetadataModels` | TVDB cache read/write serialization | WIRED | Existing TVDB cache methods use provider-neutral enrichment and episode metadata types. |
| `TvMetadataRouter` | `TvdbIdentityService` | Resolve TVDB identity before fallback | WIRED | TV paths resolve TVDB identity before TVDB metadata fetch or fallback. |
| `TvMetadataRouter` | `TmdbSettingsDataStore` | `settings.first().isActive` before fallback calls | WIRED | Manual check verified `canUseTmdbFallback()` at `TvMetadataRouter.kt:244`; gsd-tools missed it because the planned pattern spans helper naming rather than a source path token. |
| `TvMetadataRouter` | `TmdbMetadataService` | Explicit fallback only | WIRED | Fallback helpers now guard before `resolveTmdbId` and TMDB metadata calls. |
| `TvdbMetadataService` | `MetadataDiskCacheStore` | Write season cache only after successful TVDB response | WIRED | Failure returns precede `writeTvdbSeasonEpisodes`; successful empty 200 remains cacheable. |
| `TvdbMetadataService` | `PosterRatingsUrlResolver` | Apply poster override after TVDB artwork mapping | WIRED | `resolvePosterUrl` receives TVDB poster only; backdrop/logo remain TVDB artwork. |
| `MetaDetailsViewModel` | `TvMetadataRouter` | Detail enrichment, episode rows, season marking | WIRED | Prior passed item; no regression. |
| `HomeViewModelContinueWatching.loadContinueWatchingPipeline` | `HomeViewModel.enrichContinueWatchingItems` | Shared provider-enrichment gate | WIRED | Gate result controls enrichment job at `HomeViewModelContinueWatching.kt:75`; gsd-tools could not resolve method-name pseudo-path links, so this was manually verified. |
| `HomeViewModelContinueWatching.enrichContinueWatchingWithCurrentSettings` | `HomeViewModel.enrichContinueWatchingNextUpItems` | Same provider-enrichment gate as snapshot collection | WIRED | Manual refresh uses the same helper at `HomeViewModelContinueWatching.kt:528`. |
| `HomeViewModelContinueWatching.enrichContinueWatchingItemWithProvider` | `TvMetadataRouter.fetchEnrichment` | TVDB-only series metadata request | WIRED | Provider enrichment calls `tvMetadataRouter.fetchEnrichment` at `HomeViewModelContinueWatching.kt:132`. |
| `HomeViewModelContinueWatchingRuntimePipeline.kt` | `TvMetadataRouter.fetchEpisodeEnrichment` | Runtime warming | WIRED | Prior passed item; no regression. |
| `HomeViewModelPresentationPipeline.kt` | `TvMetadataRouter.fetchEnrichment` | Focused, adjacent, and hero item enrichment | WIRED | Prior passed item; no regression. |
| `HomeCatalogRefreshCoordinator.kt` | `TvMetadataRouter.fetchEnrichment` | Catalog refresh localized metadata overlay | WIRED | Prior passed item; no regression. |
| `strings.xml` | TVDB settings UI | Provider precedence copy | WIRED | `TvdbSettingsScreen.kt` consumes `provider_precedence_summary`. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `TvMetadataRouter.kt` | `TvMetadataDecision<TvMetadataEnrichment>` | `TvdbIdentityService` plus `TvdbMetadataService.fetchSeriesEnrichment` | Yes | FLOWING |
| `TvMetadataRouter.kt` | TMDB fallback values | `TmdbSettingsDataStore.settings.first().isActive` gate before `resolveTmdbId` | Yes, when TMDB active | FLOWING_WITH_SETTINGS_GUARD |
| `TvdbMetadataService.kt` | `List<TvSeasonEpisode>` | `tvdbApi.getSeriesEpisodes` after cache miss and auth token | Yes on HTTP success | FLOWING |
| `TvdbMetadataService.kt` | Failed season response value | Exception or non-success TVDB response | No cache write | FLOWING_WITH_FAILURE_GUARD |
| `HomeViewModelContinueWatching.kt` | Continue Watching provider metadata | Shared gate, then `TvMetadataRouter.fetchEnrichment` and `fetchEpisodeEnrichment` | Yes for TV/series rows when TMDB disabled; movies still require active TMDB | FLOWING |
| `HomeViewModelContinueWatchingRuntimePipeline.kt` | Episode runtime | `TvMetadataRouter.fetchEpisodeEnrichment` for series, direct TMDB path only for movies | Yes | FLOWING |
| `HomeViewModelPresentationPipeline.kt` | Home preview/hero enrichment | `TvMetadataRouter.fetchEnrichment` for TV, TMDB for movies | Yes | FLOWING |
| `HomeCatalogRefreshCoordinator.kt` | Catalog overlay enrichment | `TvMetadataRouter.fetchEnrichment` for TV, TMDB for movies | Yes | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Phase plan index completeness | `node /Users/jneerdael/.codex/get-shit-done/bin/gsd-tools.cjs phase-plan-index 07` | 8 plans, all have summaries, `incomplete: []` | PASS |
| Gap-closure artifact existence/substance | `gsd-tools verify artifacts 07-07-PLAN.md` and `07-08-PLAN.md` | 6/6 artifact checks passed | PASS |
| Gap-closure key links | `gsd-tools verify key-links 07-07-PLAN.md` and `07-08-PLAN.md` plus manual trace | gsd-tools resolved file-path links; method-name pseudo-path links required manual verification and are wired | PASS_WITH_MANUAL_TRACE |
| Prior source gaps | Manual source trace | Original three failed truths are closed at source level | PASS |
| Phase source compilation | Not rerun per prompt context | Current dirty worktree compile is blocked by unrelated files; source compilation is not marked passed | BLOCKED_EXTERNAL |
| Targeted unit tests | Not rerun per prompt context | Unit-test compile debt prevents targeted Phase 7 tests from executing; tests are not marked passed | BLOCKED_EXTERNAL |
| Code review | `.planning/phases/07-tvdb-provider-replacement/07-REVIEW.md` | Refreshed after gap closure with `status: clean`, 0 findings | PASS_REPORTED |
| Schema drift | Provided gate context | `drift_detected=false` | PASS_REPORTED |
| Security gate | `find .planning/phases/07-tvdb-provider-replacement -maxdepth 1 -name '*SECURITY.md' -print` | No Phase 07 SECURITY.md found | HUMAN_REQUIRED |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| PREF-02 | 07-01 through 07-05, 07-07, 07-08 | TVDB replaces TMDB as metadata authority for TV/series surfaces | SATISFIED_FOR_PHASE_7_SCOPE | Detail, Continue Watching, Home preview/hero/catalog, episode metadata, artwork, and season episode paths route through TVDB-first provider code. Broad trailers/cast/networks are deferred to Phase 9 by roadmap. |
| PREF-03 | 07-01 through 07-05, 07-07, 07-08 | Normal TVDB success paths do not perform duplicate TMDB TV metadata fetches | SATISFIED_SOURCE_ONLY | Router success emits `TMDB_TV_SKIPPED`; tests assert zero TMDB calls for TVDB success and disabled fallback cases. Test execution remains externally blocked. |
| PREF-07 | 07-02, 07-06 | Poster-ratings supported titles override provider poster metadata | SATISFIED | `TvdbMetadataService` applies poster-ratings only to poster URL, and `PosterRatingsUrlResolverTest` covers TopPosters TVDB poster URLs. |
| META-01 | 07-01 through 07-05, 07-08 | TVDB enriches TV titles with TV-specific fields | SATISFIED | Provider models and service mapping include `airsDays`, `airsTime`, runtime, country/language, status, aliases, content ratings, and remote IDs, then map user-facing fields into existing surfaces. |
| META-02 | 07-01 through 07-04, 07-07, 07-08 | TVDB enriches episode rows with episode fields | SATISFIED_SOURCE_ONLY | Episode metadata mapping and Detail/Continue Watching runtime paths are wired; failed season requests no longer poison cache. Test execution remains externally blocked. |
| META-04 | 07-01 through 07-06, 07-08 | TVDB artwork replaces TMDB TV artwork while honoring controls and poster-ratings | SATISFIED | TVDB poster/backdrop/logo mapping exists; poster-ratings overrides poster only; settings and tests cover precedence. |
| UX-01 | 07-06 | Settings explain provider precedence | SATISFIED | `provider_precedence_summary` states TVDB for TV, TMDB movie/fallback, and poster-ratings poster authority. |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|---|---:|---|---|---|
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` | 188 | `return null` for missing season/episode in localized episode description | INFO | Intentional guard; not a stub or user-visible placeholder. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt` | 181 | `return null` when TVDB record has no usable metadata fields | INFO | Intentional absent-provider result; not a stub. |

No blocker anti-patterns remain in the gap-owned source files.

### External Verification Completed

| Gate | Evidence | Status |
|---|---|---|
| Source and unit-test compile/test gate | User ran `./gradlew testArm64DebugUnitTest --continue`; result: `BUILD SUCCESSFUL in 50s` | PASSED |
| Security gate | `07-SECURITY.md` exists with `status: secured`, `threats_total: 34`, `threats_closed: 34`, `threats_open: 0` | PASSED |

### Gaps Summary

The original three source gaps are closed. Continue Watching can now reach TVDB-backed provider enrichment when TMDB is disabled, TMDB fallback is guarded by active TMDB settings, and failed TVDB season requests no longer write empty authoritative season cache entries.

Phase 7 is passed. The original three source gaps are closed, post-gap code review is clean, the security gate is secured, and the external test gate was reported green with `./gradlew testArm64DebugUnitTest --continue`.

---

_Verified: 2026-04-15T11:23:03Z_
_Verifier: Claude (gsd-verifier)_
