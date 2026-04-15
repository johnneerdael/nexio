---
phase: 07-tvdb-provider-replacement
verified: 2026-04-15T04:42:13Z
status: gaps_found
score: "5/8 must-haves verified"
overrides_applied: 0
gaps:
  - truth: "Continue Watching TV metadata can use TVDB as the normal provider when TVDB is active, without requiring TMDB to be active"
    status: failed
    reason: "Continue Watching enrichment is still gated by currentTmdbSettings.isActive && useBasicInfo, so a TVDB-only configuration never calls TvMetadataRouter for display metadata."
    artifacts:
      - path: "app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt"
        issue: "loadContinueWatchingPipeline and enrichContinueWatchingWithCurrentSettings return before TVDB routing unless TMDB settings are active."
    missing:
      - "Gate Continue Watching provider enrichment on TVDB-active TV rows as well as TMDB-active settings."
      - "Add regression coverage for TVDB active with TMDB disabled/missing API key."
  - truth: "TMDB TV fallback is explicit and respects TMDB provider settings"
    status: failed
    reason: "TvMetadataRouter has no TmdbSettingsDataStore dependency and fallback helpers call TmdbService/TmdbMetadataService whenever TVDB is inactive, identity is missing, or records are missing."
    artifacts:
      - path: "app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt"
        issue: "fetchTmdbEnrichment, fetchTmdbEpisodeEnrichment, and fetchTmdbSeasonEpisodes do not check TmdbSettings.enabled/isActive before TMDB calls."
    missing:
      - "Inject/read TMDB settings before TV fallback calls."
      - "Add router tests proving TVDB inactive/record-missing with TMDB disabled does not call TMDB services."
  - truth: "TVDB season episode cache does not persist failed TVDB requests as empty authoritative seasons"
    status: failed
    reason: "fetchSeasonEpisodes converts thrown/non-2xx TVDB responses to empty records and then unconditionally writes the empty mapped list to the TVDB episode cache."
    artifacts:
      - path: "app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt"
        issue: "runCatching/getOrNull/takeIf(response.isSuccessful).body().data.orEmpty() collapses failures to empty data, then writeTvdbSeasonEpisodes caches the result."
    missing:
      - "Return empty results on failures without writing TVDB season cache entries."
      - "Add tests for thrown exception and non-2xx response proving writeTvdbSeasonEpisodes is not called."
deferred:
  - truth: "PREF-02 broad surfaces for TV trailers, related content, credits/cast, and networks"
    addressed_in: "Phase 9"
    evidence: "Phase 9 success criteria explicitly cover replacing remaining TMDB TV surfaces such as trailers, characters/cast, companies, networks, genres, and content ratings."
---

# Phase 7: TVDB Provider Replacement Verification Report

**Phase Goal:** TVDB replaces TMDB as the normal TV metadata provider across existing TV enrichment surfaces, while poster-ratings integrations remain authoritative for poster imagery
**Verified:** 2026-04-15T04:42:13Z
**Status:** gaps_found
**Re-verification:** No - initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Phase 6 prerequisites, provider-neutral models, TVDB cache namespaces, and diagnostics exist | VERIFIED | Phase 6 source file check exited 0. `TvMetadataEnrichment` carries TVDB fields in `TvMetadataModels.kt:20`; diagnostics include `TVDB_SUCCESS`, `TVDB_FALLBACK_TMDB`, `TMDB_TV_SKIPPED`, and `POSTER_RATINGS_OVERRIDE` in `TvMetadataDiagnostics.kt:8`. Cache prefixes/schema fields are in `MetadataDiskCacheStore.kt:40` and `MetadataDiskCacheStore.kt:209`. |
| 2 | TVDB series and episode fields populate provider-neutral roles used by existing UI contracts | VERIFIED | `TvdbMetadataService.toEnrichment` maps title, description, genres, artwork, runtime, `airsDays`, `airsTime`, country/language, status, aliases, content ratings, and remote IDs at `TvdbMetadataService.kt:181`. Episode metadata maps title, overview, image, air date, runtime, absolute number, specials placement, linked movie, and finale type at `TvdbMetadataService.kt:207`. |
| 3 | Detail screen series enrichment, episode rows, and mark-season-watched use `TvMetadataRouter` for TV | VERIFIED | `MetaDetailsViewModel.enrichMeta` calls `tvMetadataRouter.fetchEnrichment` for TV at `MetaDetailsViewModel.kt:1256`. Episode rows call `fetchEpisodeEnrichment` at `MetaDetailsViewModel.kt:1360`. Mark-season-watched calls `fetchSeasonEpisodes` at `MetaDetailsViewModel.kt:1911`. |
| 4 | Continue Watching TV metadata can use TVDB as the normal provider without requiring TMDB to be active | FAILED | `loadContinueWatchingPipeline` starts enrichment only when `currentTmdbSettings.isActive && useBasicInfo` at `HomeViewModelContinueWatching.kt:64`. Manual refresh repeats the same TMDB-only gate at `HomeViewModelContinueWatching.kt:517`. TVDB-only settings never reach the router. |
| 5 | Home focused, hero, and catalog TV metadata route through TVDB-first routing | VERIFIED | Focus/hero enrichment calls `tvMetadataRouter.fetchEnrichment` for TV items at `HomeViewModelPresentationPipeline.kt:633`. Catalog hydration calls the router for TV content and logs provider decisions at `HomeCatalogRefreshCoordinator.kt:92`. |
| 6 | Poster-ratings provider URLs override poster imagery without suppressing non-poster artwork, and settings explain precedence | VERIFIED | `TvdbMetadataService` resolves only poster through `PosterRatingsUrlResolver` while preserving `artwork.backdrop` and `artwork.logo` at `TvdbMetadataService.kt:158`. TopPosters supports TVDB IDs in `PosterRatingsUrlResolver.kt:117`. Settings copy states TVDB/TMDB/poster precedence in `strings.xml:849`. |
| 7 | TMDB TV fallback is explicit and respects TMDB provider settings | FAILED | Router fallback is explicit diagnostically, but `TvMetadataRouter` injects only `TvdbSettingsDataStore` and not TMDB settings at `TvMetadataRouter.kt:15`. Fallback helpers call `tmdbService.ensureTmdbId` and `tmdbMetadataService` directly at `TvMetadataRouter.kt:154`, `TvMetadataRouter.kt:174`, and `TvMetadataRouter.kt:194`. |
| 8 | TVDB season episode cache does not persist failed TVDB requests as empty authoritative seasons | FAILED | `fetchSeasonEpisodes` collapses thrown or non-success responses to `.orEmpty()` at `TvdbMetadataService.kt:122`, then writes `mapped` to `writeTvdbSeasonEpisodes` at `TvdbMetadataService.kt:143`. |

**Score:** 5/8 truths verified

### Deferred Items

Items not yet met but explicitly addressed in later milestone phases.

| # | Item | Addressed In | Evidence |
|---|------|--------------|----------|
| 1 | PREF-02 broad surfaces for TV trailers, related content, credits/cast, and networks | Phase 9 | Phase 9 success criteria cover TVDB trailers plus characters/cast, companies, networks, genres, and content ratings. |

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt` | Provider-neutral TV metadata contracts | VERIFIED | Exists and substantive; includes enrichment, decision, request, episode, and season models. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt` | Provider diagnostic event names | VERIFIED | Includes TVDB inactive/success/fallback/missing/skipped/poster override reasons. |
| `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt` | TVDB metadata cache namespace | VERIFIED | TVDB prefixes, schema versions, read/write methods, and stale epoch awareness exist. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt` | TVDB series/episode metadata mapping | PARTIAL | Mapping exists, but failure responses are cached as empty TVDB season entries. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt` | TVDB-first routing with explicit TMDB fallback | PARTIAL | TVDB-first and diagnostics exist; fallback ignores TMDB enabled state. |
| `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt` | TVDB metadata API endpoints | VERIFIED | Artifact verifier passed. |
| `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` | Detail screen TVDB routing | VERIFIED | Uses router for TV enrichment, episodes, and season marking. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` | Continue Watching TVDB display metadata routing | PARTIAL | Router call exists, but is gated by TMDB active settings. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt` | Continue Watching TVDB runtime routing | VERIFIED | TV series runtime uses router before direct movie TMDB branch. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt` | Focused/hero TV routing | VERIFIED | TV items call `tvMetadataRouter.fetchEnrichment`; movie items stay TMDB-backed. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt` | Catalog TV routing and diagnostics | VERIFIED | TV catalog overlay uses router and logs `tmdb_tv_skipped`/`tvdb_fallback_tmdb`. |
| `app/src/main/res/values/strings.xml` | Provider precedence copy | VERIFIED | `provider_precedence_summary` contains TVDB, TMDB fallback, and poster-ratings precedence. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `MetadataDiskCacheStore` | `TvMetadataModels` | TVDB cache read/write serialization | WIRED | `readTvdbEnrichment`, `writeTvdbEnrichment`, `readTvdbSeasonEpisodes`, and `writeTvdbSeasonEpisodes` use `TvMetadataEnrichment`/`TvEpisodeMetadata`. |
| `TvMetadataRouter` | `TvdbIdentityService` | Resolve TVDB identity before fallback | WIRED | `resolveTvdbIdentity` is called before TVDB fetch/fallback for TV enrichment and episodes. |
| `TvMetadataRouter` | `TmdbMetadataService` | Explicit fallback only | PARTIAL | Fallback diagnostics exist, but fallback does not respect TMDB enabled state. |
| `TvdbMetadataService` | `PosterRatingsUrlResolver` | Apply poster override after TVDB artwork mapping | WIRED | `resolvePosterUrl` receives the TVDB poster only; backdrop/logo remain TVDB artwork. |
| `MetaDetailsViewModel` | `TvMetadataRouter` | Detail enrichment, episode rows, season marking | WIRED | Detail TV paths call router methods; movie branch remains TMDB. |
| `HomeViewModelContinueWatching.kt` | `TvMetadataRouter` | Display metadata enrichment | PARTIAL | Router call exists but is unreachable for TVDB-only provider settings. |
| `HomeViewModelContinueWatchingRuntimePipeline.kt` | `TvMetadataRouter` | Runtime warming | WIRED | Series runtime resolves via router before movie TMDB fallback. |
| `HomeViewModelPresentationPipeline.kt` | `TvMetadataRouter` | Focused, adjacent, and hero item enrichment | WIRED | TV items route through `fetchProviderEnrichmentForPreview`. |
| `HomeCatalogRefreshCoordinator.kt` | `TvMetadataRouter` | Catalog refresh overlay | WIRED | TV catalog rows call `fetchEnrichment` and log provider diagnostics. |
| `strings.xml` | Settings UI | Provider precedence copy | WIRED | `provider_precedence_summary` is consumed by `TvdbSettingsScreen`. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `TvdbMetadataService.kt` | `TvMetadataEnrichment` | `tvdbApi.getSeriesExtended` through `TvdbSeriesExtendedRecord.toEnrichment` | Yes | FLOWING |
| `TvdbMetadataService.kt` | `List<TvSeasonEpisode>` | `tvdbApi.getSeriesEpisodes` through `TvdbEpisodeRecord.toEpisodeMetadata` | Partial | HOLLOW_ON_FAILURE - non-success network paths become cached empty data. |
| `MetaDetailsViewModel.kt` | `tvEnrichment`, `episodeMap`, `seasonEpisodes` | `TvMetadataRouter` decisions | Yes | FLOWING |
| `HomeViewModelContinueWatching.kt` | `enrichment`, `localizedEpisodeDescription` | `TvMetadataRouter` decisions | Partial | HOLLOW_GATE - data source is blocked when TMDB settings are inactive. |
| `HomeViewModelContinueWatchingRuntimePipeline.kt` | `episodeRuntime`, series runtime | `TvMetadataRouter` decisions | Yes | FLOWING |
| `HomeViewModelPresentationPipeline.kt` | `TvMetadataEnrichment` for preview/hero | `TvMetadataRouter` for TV, TMDB for movies | Yes | FLOWING |
| `HomeCatalogRefreshCoordinator.kt` | `enrichment` for catalog overlay | `TvMetadataRouter` for TV, TMDB for movies | Yes | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Phase plan index completeness | `node .../gsd-tools.cjs phase-plan-index 07` | 6 plans, all have summaries, `incomplete: []` | PASS |
| Artifact existence/substance | `node .../gsd-tools.cjs verify artifacts 07-01..07-06-PLAN.md` | 17/18 artifact checks passed; 07-06 exact lowercase pattern miss is a false positive because the test asserts `Poster-ratings` with the same user-facing capitalization as the string | PASS_WITH_NOTE |
| Key link checks | `node .../gsd-tools.cjs verify key-links 07-01..07-06-PLAN.md` plus manual grep | gsd-tools resolved only file-path links; manual checks verified shorthand links and identified two partial links | PARTIAL |
| Phase source compilation | Provided gate context | `./gradlew compileArm64DebugKotlin` passed after Phase 7 execution | PASS_REPORTED_NOT_RERUN |
| Phase unit tests | Provided gate context | Targeted command failed during unrelated `:app:compileArm64DebugUnitTestKotlin` compile debt outside Phase 7 owned files | FAIL_EXTERNAL_DEBT - tests not marked passed |
| Schema drift | Provided gate context | `drift_detected=false` | PASS_REPORTED |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| PREF-02 | 07-01, 07-02, 07-03, 07-04, 07-05 | TVDB replaces TMDB as metadata authority for TV/series surfaces | BLOCKED | Detail and Home routes are wired, but Continue Watching TVDB display enrichment is TMDB-settings gated. Trailers/cast/networks portions are explicitly deferred to Phase 9. |
| PREF-03 | 07-01, 07-02, 07-03, 07-04, 07-05 | Normal TVDB success paths do not duplicate TMDB TV metadata fetches | PARTIAL | Static tests and route structure cover TVDB success skip paths, but router fallback ignores TMDB enabled state and Continue Watching TVDB-only flow is blocked. Unit tests were not runnable due unrelated compile debt. |
| PREF-07 | 07-02, 07-06 | Poster-ratings supported titles override provider poster metadata | SATISFIED | TVDB poster passes through `PosterRatingsUrlResolver`; TopPosters supports `tvdb/poster` URLs. |
| META-01 | 07-01, 07-02, 07-03, 07-04, 07-05 | TVDB enriches TV titles with TV-specific fields | SATISFIED | `TvMetadataEnrichment` and service mapping include the required fields and propagate title/description/artwork/rating/runtime roles to surfaces. |
| META-02 | 07-01, 07-02, 07-03, 07-04 | TVDB enriches episode rows with episode fields | BLOCKED | Mapping exists, but failed TVDB season requests can be cached as empty, causing episode metadata to remain absent/fallback after transient failures. |
| META-04 | 07-01, 07-02, 07-03, 07-04, 07-05, 07-06 | TVDB artwork replaces TMDB TV artwork while honoring controls and poster-ratings | SATISFIED | TVDB poster/backdrop/logo mapping exists; poster-ratings only overrides poster URL. |
| UX-01 | 07-06 | Settings explain provider precedence | SATISFIED | `provider_precedence_summary` states TVDB for TV, TMDB movie/fallback, and poster-ratings poster authority. |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|---|---:|---|---|---|
| `HomeViewModelContinueWatching.kt` | 64 | Provider enrichment guarded only by TMDB settings | BLOCKER | TVDB-only Continue Watching display metadata never runs. |
| `HomeViewModelContinueWatching.kt` | 519 | Manual enrichment returns when TMDB inactive | BLOCKER | Refresh path repeats the same TVDB-only blocker. |
| `TvMetadataRouter.kt` | 154 | Fallback helper has no TMDB settings guard | WARNING | Disabled TMDB can still be used as fallback if an API key remains stored. |
| `TvdbMetadataService.kt` | 122 | Failed TVDB response collapsed to empty cacheable data | WARNING | Transient failures can poison season episode cache with empty data. |

### Human Verification Required

None before gap closure. After fixes, run a device/UI pass for:

1. TVDB active + TMDB disabled Continue Watching rows show TVDB title/artwork/episode descriptions.
2. Settings copy reads clearly in the Android TV settings layout.
3. Poster-ratings TVDB poster override displays while TVDB backdrop/logo/episode images remain provider-derived.

### Gaps Summary

Phase 7 is not goal-complete. The core TVDB contracts, mappings, detail routing, Home routing, poster precedence, and settings copy exist, but provider precedence fails in Continue Watching TVDB-only configurations and TMDB fallback does not respect the TMDB enabled setting. A separate TVDB season cache bug can persist failed episode requests as empty authoritative seasons, which undermines TVDB episode metadata replacement after transient failures.

The broad `PREF-02` items for trailers, related content, credits/cast, and networks are not counted as Phase 7 gaps because the roadmap explicitly moves those remaining TV surfaces to Phase 9.

---

_Verified: 2026-04-15T04:42:13Z_
_Verifier: Claude (gsd-verifier)_
