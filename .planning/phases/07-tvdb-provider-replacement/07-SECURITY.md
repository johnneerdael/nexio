---
phase: 07-tvdb-provider-replacement
phase_number: 7
phase_name: tvdb-provider-replacement
security_reviewed: 2026-04-15
status: secured
asvs_level: 1
block_on: open_threats
threats_total: 34
threats_closed: 34
threats_open: 0
unregistered_flags: 0
auditor: gsd-secure-phase
---

# Phase 07 Security Verification

## Result

SECURED. All Phase 07 PLAN.md threat-model entries were verified by declared disposition. No implementation files were modified during this audit.

Verification scope was limited to the eight Phase 07 plan threat models and the implementation/test files listed in the audit prompt. Reused threat IDs were treated as `{plan_file}:{threat_id}:{component}`.

## Threat Register

| Threat Key | Category | Disposition | Status | Evidence |
|---|---|---:|---|---|
| `07-01-PLAN.md:T-07-01:Phase 6 prerequisite and diagnostics` | Information Disclosure | mitigate | CLOSED | Phase 6 prerequisite files exist; TVDB service logs only failure class/status (`TvdbMetadataService.kt:57`, `TvdbMetadataService.kt:131`, `TvdbMetadataService.kt:135`), and poster provider cache token hashes API keys (`TvdbMetadataService.kt:292`). |
| `07-01-PLAN.md:T-07-02:TvMetadataDiagnostics.kt` | Repudiation | mitigate | CLOSED | Exact diagnostic event names exist in `TvMetadataDiagnostics.kt:8`. |
| `07-01-PLAN.md:T-07-03:MetadataDiskCacheStore.kt` | Tampering | mitigate | CLOSED | TVDB prefixes and schema versions are separate from TMDB (`MetadataDiskCacheStore.kt:40`, `MetadataDiskCacheStore.kt:47`); TVDB keys use `tvdb::` / `tvdb_episode::` builders (`MetadataDiskCacheStore.kt:463`, `MetadataDiskCacheStore.kt:472`). |
| `07-01-PLAN.md:T-07-04:Phase 6 identity prerequisite` | Tampering | mitigate | CLOSED | Phase 6 identity source exists; router resolves TVDB identity before TVDB metadata or fallback (`TvMetadataRouter.kt:39`, `TvMetadataRouter.kt:80`, `TvMetadataRouter.kt:128`, `TvMetadataRouter.kt:253`). |
| `07-02-PLAN.md:T-07-01:TvdbMetadataService` | Information Disclosure | mitigate | CLOSED | TVDB metadata service logs no credentials or auth headers; failures log class/status only (`TvdbMetadataService.kt:57`, `TvdbMetadataService.kt:131`, `TvdbMetadataService.kt:135`). |
| `07-02-PLAN.md:T-07-02:TvMetadataRouter` | Repudiation | mitigate | CLOSED | Router emits inactive, missing, fallback, success, and skipped-TMDB diagnostics (`TvMetadataRouter.kt:280`, `TvMetadataRouter.kt:287`, `TvMetadataRouter.kt:294`, `TvMetadataRouter.kt:301`). |
| `07-02-PLAN.md:T-07-03:TvdbMetadataService cache writes` | Tampering | mitigate | CLOSED | TVDB service uses TVDB cache methods (`TvdbMetadataService.kt:40`, `TvdbMetadataService.kt:65`, `TvdbMetadataService.kt:112`, `TvdbMetadataService.kt:146`) and no TMDB cache functions for TVDB data. |
| `07-02-PLAN.md:T-07-04:TvMetadataRouter ID handling` | Tampering | mitigate | CLOSED | TVDB identity resolution precedes metadata fetch/fallback (`TvMetadataRouter.kt:39`, `TvMetadataRouter.kt:80`, `TvMetadataRouter.kt:128`); TMDB ID conversion is isolated to fallback helpers (`TvMetadataRouter.kt:248`). |
| `07-03-PLAN.md:T-07-01:Detail logs/tests` | Information Disclosure | mitigate | CLOSED | Detail routing tests use mocked router values (`MetaDetailsTvdbProviderRoutingTest.kt:51`, `MetaDetailsTvdbProviderRoutingTest.kt:59`) and no TVDB credential fixtures. |
| `07-03-PLAN.md:T-07-02:MetaDetailsViewModel.enrichMeta` | Repudiation | mitigate | CLOSED | Series detail enrichment calls `TvMetadataRouter.fetchEnrichment` (`MetaDetailsViewModel.kt:1256`) and episode enrichment calls router episode metadata (`MetaDetailsViewModel.kt:1358`). |
| `07-03-PLAN.md:T-07-03:Detail cache/provider output` | Tampering | mitigate | CLOSED | Detail consumes provider-neutral `TvMetadataEnrichment` and maps only into UI model fields (`MetaDetailsViewModel.kt:1281`). No cache writes are present in the detail path. |
| `07-03-PLAN.md:T-07-04:Detail ID routing` | Tampering | mitigate | CLOSED | Series path sets `tmdbEnrichment = null` before the movie-only TMDB branch (`MetaDetailsViewModel.kt:1267`), so TMDB ID resolution is not called before router handling for series. |
| `07-04-PLAN.md:T-07-01:Continue Watching logs/tests` | Information Disclosure | mitigate | CLOSED | Continue Watching tests use mocked router decisions and fake IDs (`HomeViewModelTvdbProviderRoutingTest.kt:162`, `HomeViewModelTvdbProviderRoutingTest.kt:189`); production logs do not include TVDB credentials. |
| `07-04-PLAN.md:T-07-02:Continue Watching provider routing` | Repudiation | mitigate | CLOSED | Continue Watching display and episode description call `TvMetadataRouter` (`HomeViewModelContinueWatching.kt:132`, `HomeViewModelContinueWatching.kt:192`). |
| `07-04-PLAN.md:T-07-03:Continue Watching display metadata` | Tampering | mitigate | CLOSED | Provider output is merged through `HomeDisplayMetadata.mergeFallback` (`HomeViewModelContinueWatching.kt:215`). No TMDB cache writes occur in this path. |
| `07-04-PLAN.md:T-07-04:Continue Watching ID routing` | Tampering | mitigate | CLOSED | Series runtime/enrichment routes to TVDB router before the movie/TMDB branch (`HomeViewModelContinueWatchingRuntimePipeline.kt:33`, `HomeViewModelContinueWatchingRuntimePipeline.kt:59`). |
| `07-05-PLAN.md:T-07-01:Home telemetry/logs` | Information Disclosure | mitigate | CLOSED | Catalog diagnostics emit only event names and `itemKey` (`HomeCatalogRefreshCoordinator.kt:590`, `HomeCatalogRefreshCoordinator.kt:591`, `HomeCatalogRefreshCoordinator.kt:594`). |
| `07-05-PLAN.md:T-07-02:Home provider routing` | Repudiation | mitigate | CLOSED | Catalog hydration emits `tmdb_tv_skipped` and `tvdb_fallback_tmdb` through existing logging callback (`HomeCatalogRefreshCoordinator.kt:591`, `HomeCatalogRefreshCoordinator.kt:594`). |
| `07-05-PLAN.md:T-07-03:Home metadata cache/provider output` | Tampering | mitigate | CLOSED | Home preview/catalog consume provider-neutral router enrichment (`HomeViewModelPresentationPipeline.kt:632`, `HomeCatalogRefreshCoordinator.kt:93`); no TVDB-to-TMDB cache writes are present. |
| `07-05-PLAN.md:T-07-04:Home ID routing` | Tampering | mitigate | CLOSED | Home series preview/catalog branches call router before TMDB ID conversion (`HomeViewModelPresentationPipeline.kt:633`, `HomeCatalogRefreshCoordinator.kt:93`). |
| `07-06-PLAN.md:T-07-01:Settings/tests` | Information Disclosure | mitigate | CLOSED | Settings resources contain labels/placeholders, not secret values (`strings.xml:851`, `strings.xml:852`, `strings.xml:853`); provider precedence tests assert copy only (`ProviderPrecedenceCopyTest.kt:17`). |
| `07-06-PLAN.md:T-07-02:Settings provider copy` | Repudiation | mitigate | CLOSED | Copy explicitly states TVDB/TMDB/poster-ratings precedence (`strings.xml:849`) and TVDB screen displays it (`TvdbSettingsScreen.kt:149`). |
| `07-06-PLAN.md:T-07-03:Poster URL precedence` | Tampering | mitigate | CLOSED | Tests assert TopPosters `tvdb:` URL output and RPDB fallback behavior (`PosterRatingsUrlResolverTest.kt:56`, `PosterRatingsUrlResolverTest.kt:74`). |
| `07-06-PLAN.md:T-07-04:Settings routing assumptions` | Tampering | accept | CLOSED | Accepted risk logged below; this copy-only plan delegates invalid-ID routing to router mitigations verified in `TvMetadataRouter.kt:253`. |
| `07-06-PLAN.md:T-07-05:Fallback observability` | Repudiation | transfer | CLOSED | Transfer documented below; router and Home emit fallback/skipped diagnostics (`TvMetadataRouter.kt:280`, `TvMetadataRouter.kt:301`, `HomeCatalogRefreshCoordinator.kt:591`). |
| `07-06-PLAN.md:T-07-06:TVDB/TMDB cache namespace separation` | Tampering | transfer | CLOSED | Transfer documented below; Plan 01 cache namespace implementation verified in `MetadataDiskCacheStore.kt:40` and `MetadataDiskCacheStore.kt:472`. |
| `07-07-PLAN.md:T-07-07-01:TvMetadataRouter` | Repudiation | mitigate | CLOSED | TMDB fallback guard runs before every fallback helper resolves TMDB IDs (`TvMetadataRouter.kt:156`, `TvMetadataRouter.kt:185`, `TvMetadataRouter.kt:214`, `TvMetadataRouter.kt:244`). |
| `07-07-PLAN.md:T-07-07-02:TvMetadataRouter` | Information Disclosure | mitigate | CLOSED | Router contains no logging and reads settings only through data stores (`TvMetadataRouter.kt:16`, `TvMetadataRouter.kt:244`). |
| `07-07-PLAN.md:T-07-07-03:TvdbMetadataService.fetchSeasonEpisodes` | Tampering | mitigate | CLOSED | Season cache write occurs only after successful response processing (`TvdbMetadataService.kt:121`, `TvdbMetadataService.kt:134`, `TvdbMetadataService.kt:146`). |
| `07-07-PLAN.md:T-07-07-04:TvdbMetadataService.fetchSeasonEpisodes` | Denial of Service | mitigate | CLOSED | Exceptions and non-success HTTP responses return before cache writes (`TvdbMetadataService.kt:122`, `TvdbMetadataService.kt:134`); tests assert no cache write for both cases (`TvdbMetadataServiceTest.kt:225`, `TvdbMetadataServiceTest.kt:245`). |
| `07-08-PLAN.md:T-07-08-01:shouldEnrichContinueWatchingProviderMetadata` | Repudiation | mitigate | CLOSED | Focused gate tests cover TVDB-eligible series and movie-only disabled-TMDB rows (`HomeViewModelTvdbProviderRoutingTest.kt:103`, `HomeViewModelTvdbProviderRoutingTest.kt:125`). |
| `07-08-PLAN.md:T-07-08-02:HomeViewModelContinueWatching.kt` | Tampering | mitigate | CLOSED | Provider metadata merge remains through `enrichContinueWatchingItemWithProvider` and `mergeFallback` (`HomeViewModelContinueWatching.kt:119`, `HomeViewModelContinueWatching.kt:215`). |
| `07-08-PLAN.md:T-07-08-03:Continue Watching logs/tests` | Information Disclosure | mitigate | CLOSED | Tests use mocked router decisions and fake IDs (`HomeViewModelTvdbProviderRoutingTest.kt:229`, `HomeViewModelTvdbProviderRoutingTest.kt:238`); no credential logging was found in the plan-owned path. |
| `07-08-PLAN.md:T-07-08-04:Continue Watching enrichment scheduling` | Denial of Service | accept | CLOSED | Accepted risk logged below; gate limits disabled-TMDB enrichment to series rows and keeps movie-only rows blocked (`HomeViewModelContinueWatching.kt:39`, `HomeViewModelContinueWatching.kt:41`). |

## Accepted Risks

| Threat Key | Risk | Rationale | Boundaries |
|---|---|---|---|
| `07-06-PLAN.md:T-07-04:Settings routing assumptions` | Copy-only settings work can mislead if routing assumptions drift. | Accepted because this plan changes copy only; invalid-ID routing is mitigated in router plans. | Router identity/fallback handling verified in `TvMetadataRouter.kt:253` and `TvMetadataRouter.kt:248`. |
| `07-08-PLAN.md:T-07-08-04:Continue Watching enrichment scheduling` | Series rows may invoke router when TMDB is disabled. | Accepted because the router owns provider settings/cache bounds and the gate does not broaden disabled-TMDB behavior to movies. | `shouldEnrichContinueWatchingProviderMetadata` blocks when `useBasicInfo` is disabled and blocks movie-only rows when TMDB is inactive (`HomeViewModelContinueWatching.kt:39`, `HomeViewModelContinueWatching.kt:41`). |

## Transfer Risks

| Threat Key | Transferred To | Transfer Documentation | Verification |
|---|---|---|---|
| `07-06-PLAN.md:T-07-05:Fallback observability` | `TvMetadataRouter` and Home routing plans. | `07-06-PLAN.md` states the copy-only plan delegates fallback observability to router/Home plans that emit `tvdb_fallback_tmdb` and `tmdb_tv_skipped`. | Router diagnostics exist (`TvMetadataRouter.kt:280`, `TvMetadataRouter.kt:301`) and Home catalog logs both event names (`HomeCatalogRefreshCoordinator.kt:591`, `HomeCatalogRefreshCoordinator.kt:594`). |
| `07-06-PLAN.md:T-07-06:TVDB/TMDB cache namespace separation` | Plan 01 cache namespace work. | `07-06-PLAN.md` states the copy-only plan delegates namespace separation to Plan 01 using `tvdb::` and `tvdb_episode::`. | TVDB cache prefixes and schema fields exist (`MetadataDiskCacheStore.kt:40`, `MetadataDiskCacheStore.kt:47`) and TVDB episode keys use `tvdb_episode::` (`MetadataDiskCacheStore.kt:472`). |

## Unregistered Flags

None. Summary threat-flag sections for Plans 07-02 through 07-08 report no new threat flags. Plan 07-01 has no `## Threat Flags` section and no unregistered flag was present in its summary.

## Audit Trail

| Item | Result |
|---|---|
| Files loaded | All prompt-listed PLAN, SUMMARY, REVIEW, VERIFICATION, implementation, and test files were read or inspected before writing this report. |
| Threat-model extraction | Extracted `<threat_model>` blocks from `07-01-PLAN.md` through `07-08-PLAN.md`. |
| Verification method | For `mitigate`, grepped and inspected declared mitigation patterns in cited files. For `accept`, recorded accepted risk entries in this report. For `transfer`, verified transfer documentation in `07-06-PLAN.md` and destination implementation evidence. |
| Test execution | Not required by the security workflow. Existing Phase 07 summaries and verification note targeted tests are blocked by unrelated unit-test compile debt; this audit verifies declared mitigation presence at source level. |
| Implementation edits | None. Only this `07-SECURITY.md` artifact was created. |
