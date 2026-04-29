# Risk Register — Post-Cluster-F Audit

**Review SHA:** `774a540f8`
**Date:** 2026-04-29

---

## Live production risks

| ID | Risk | Owning finding | Likelihood | Blast radius | Mitigation |
|---|---|---|---|---|---|
| R-01 | Authenticated Trakt users see no trending/popular/recommended/calendar rails — `ProfileBoundaryException` thrown at spec construction | F2-D-01 | High (any auth Trakt user) | Major (4 Home rails dark) | Revert F-C-06 scope change OR migrate `scope` to `IntegrationScope.GlobalContent` + `profileContext = null` for the 6 global-content Trakt functions |
| R-02 | Architecture test suite fails CI — `AddonFirstPaintShapeArchitectureTest` failing on two Home files | F2-J-01 | Certain (deterministic test failure) | Major (CI blocked) | Route `HomeViewModelContinueWatchingRuntimePipeline.kt` and `HomeViewModelPresentationPipeline.kt` call sites through `MetadataRouterFacade` |
| R-03 | Stale scrobble credited to wrong Trakt/Simkl profile after profile switch | F2-H-01 | Low–Medium (requires profile switch during active playback) | Moderate (incorrect watch history; no crash) | Convert `checkScrobbleBoundary` to throw or return `Boolean`; gate `enqueueAndDrain` on result |
| R-04 | Subtitle or trailer playback silently missing after transient `call()`/`open()` error because backoff not cleared on success | F2-A-01 | Low (requires prior 429/5xx on same provider+scope via `get()` path) | Moderate (silent feature degradation) | Add `backoffManager.clear()` in `doCallInternal` and `openInternal` success branches |
| R-05 | Movie collection endpoint unmonitored — rate-limiting, errors, and audit gaps invisible | F2-C-01 | Medium (TMDB rate-limits are real in production) | Minor (collection feature only; no crash) | Migrate `loadMovieCollection()` to `runtime.get(IntegrationSpec(apiShapeId = TmdbApiShapes.COLLECTION, ...))` |
| R-06 | Trace bundles cannot be correlated to a specific commit — support engineers cannot reproduce bugs from field reports | F2-I-01 | Certain (gitSha = null always) | Minor (diagnostics only) | Add `buildConfigField("String", "GIT_SHA", ...)` in build.gradle.kts |
| R-07 | Trace settings accessible to retail users — HTTP URL + header capture on non-debug devices | F2-I-07 | Medium (requires user to discover settings menu) | Moderate (privacy/data exposure risk) | Gate trace settings entry on `BuildConfig.IS_DEBUG_BUILD` |
| R-08 | `LocalIntegrationCacheStore.deleteOwnedMedia` blob orphan on process kill during CW profile switch | F2-D-08 | Low (requires process kill mid-deletion) | Minor (orphaned Room rows; no corruption) | Run DAO delete first; or wrap blob + DAO delete in a single transaction |

---

## Architectural risks

| ID | Risk | Owning finding | Description |
|---|---|---|---|
| AR-01 | `ResolvedMetadataDocument` construction unguarded — next refactor could regress field ownership provenance | F2-J-03, F2-B-05 | No architecture pin prevents a third `ResolvedMetadataDocument(...)` construction site outside `FieldResolver` and `MetadataRouterFacade`. A future developer could inject a synthetic document bypassing field resolution. Pin modeled on `FieldResolverInjectionContractTest` would close this. |
| AR-02 | `GlobalLocalizedContent` and `GlobalEnglishImage` scopes are defined but never used — architecture intent unenforceable | F2-F-06 | `TraceValidationRules.kt:133` checks for `GlobalEnglishImage` scope on image events; no production spec ever sets this scope. The validator rule can never match a real event. Either adopt the scopes in production adapters or remove unused scope variants and the dead rule branch. |
| AR-03 | `TvdbMetadataService` parallel localization path will diverge further from `LocalizationPolicy` — two conflicting implementations of TVDB localization | F2-E-04 | `TvdbMetadataService` (used by `TvMetadataRouter`, `markSeasonWatched`) and `TvdbMetadataProviderAdapter` (used by `MetadataRouterFacade`) implement TVDB localization independently. Recent commits (`1419bb608`, `14917f00b`) fixed Path B (service) without equivalent test coverage for Path A. Divergence will grow as the policy evolves. |
| AR-04 | `SecondaryDoesNotOverwritePrimary` validator rule has inverted semantics — any multi-candidate resolution produces false-positive FAILs | F2-I-06 | The rule fires when `rejectedCandidates` is non-empty for protected fields, regardless of whether the winner is primary or secondary. In practice this means any primary-wins-with-competition scenario fails the audit. The rule cannot detect the actual invariant (secondary winning a protected field). Until fixed, operator-side trust in this validator is low. |
| AR-05 | `TraktGlobalContentCacheKeyTest` is a layout-sensitive source-grep proxy — future code reorganization will silently break the pin | F2-J-06 | The fixed 2500-char window scan already required an artificial separator comment to pass. Any refactor that reorganizes `TraktIntegrationProvider.kt` functions could produce false negatives. This pin is the sole guard for the F-C-06 cache-key contract. |
| AR-06 | `checkin()` callers supply no `ownerProfileId` — ambient profile resolution can mis-attribute checkins across profiles | F2-J-02 | `HomeViewModelContinueWatching.kt:590` and `MetaDetailsViewModel.kt:3076` both call `checkin()` with `ownerProfileId = null`. A profile switch between the UI action firing and `currentState()` being read credits the checkin to the post-switch profile. No architecture pin enforces caller-side intent. |
| AR-07 | `fetchRecommendations` dispatches `RecommendationResolver` but ignores its output — adapter output is dead weight | F2-TM-02 | `TmdbRecommendationMetadataAdapter` produces `ResolvedField.RECOMMENDATIONS` candidates that `fetchRecommendations` never reads. A future recommendations provider registered as an adapter would be silently ignored. The inconsistency with `fetchReviewsPage` (which correctly harvests from step results) will mislead future contributors. |
| AR-08 | `OrganizationDetailViewModel` is the only UI screen outside the facade boundary — no trace events, no resolver pipeline | F2-TM-03 | This screen was not included in the F-05-04 remediation. A systematic facade-boundary CI check would have caught it. Process change: expand `MetadataProductionBoundaryTest` entrypoints list to include `OrganizationDetailViewModel`. |
| AR-09 | Audit golden test for premium poster synthesises events from harness model, not real adapter — real adapter regressions undetectable | F2-T13-A | `MetadataAuditRunner.kt` bypasses the real `RpdbMetadataProviderAdapter` and `TopPostersMetadataProviderAdapter`. A regression in adapter `sourceProvider` string or `FieldResolver.selectField()` for `FieldOwner.ARTWORK` candidates would pass the golden test. |
| AR-10 | `JsonlTraceWriter.append()` silently swallows `IOException` — storage-full events show 0 dropped in UI | F2-I-10 | On low-storage devices or large trace sessions, events will be silently lost with no UI indication. A `BufferedWriter` + explicit IO error counter would give operators visibility. |

---

## Process risks

| ID | Risk | Description | Suggested process change |
|---|---|---|---|
| PR-01 | Source-grep architecture pins are fragile and missed the F2-D-01 spec-construction bug | `TraktGlobalContentCacheKeyTest` scans function bodies for `accountCacheKey(` but does not construct a real `IntegrationSpec`. The bug was invisible to the pin, visible only to end-to-end tests with a real `ProfileBoundaryEnforcer`. | Prefer behavior tests with Hilt instantiation over source-grep proxies for cache key / scope contract validation. For the Trakt global-content case, an integration test that calls `fetchTrendingMovies` and asserts the resulting spec does not throw at construction is authoritative. |
| PR-02 | Audit golden tests can be satisfied by audit-runner code while production code is broken | `MetadataExecutionAuditGoldenTest` passes for premium poster scenarios because `MetadataAuditRunner` synthesises the expected events directly. The real adapter path is never exercised. Similarly, `TraktGlobalContentCacheKeyTest` passes while `ProfileBoundaryException` is thrown at construction. | Separate concerns: audit-runner correctness tests should be distinct from production-behavior tests. Behavior tests should exercise the Hilt-wired production code path, not a harness model of it. |
| PR-03 | Cluster F regressions suggest "cache key only" changes are higher-risk than they appear | F-C-06 changed only cache key strings, not scope, assuming the cache layer keys solely on `cacheKey`. That assumption is correct at the cache layer but false at the spec construction layer (`ProfileBoundaryEnforcer.validateAccountScope` also checks cache key format). Changes touching cache keys must be validated against `ProfileBoundaryEnforcer` behavior. | Add a checklist item to the change proposal template: "if changing a cache key format, run `ProfileBoundaryEnforcer.validateRequest(...)` against the new key with the existing scope and assert no exception." |
| PR-04 | Broadening an architecture pin scope without a corresponding production fix creates false-alarm CI failures | Commit `726de12f3` broadened `AddonFirstPaintShapeArchitectureTest` to all `ui/screens/home/**` without fixing the two offending files. The pin was left in a failing state and merged. | Architecture pins must pass at the time they are merged. A failing pin is worse than no pin — it creates noise that desensitizes reviewers. If the production fix is not ready, keep the pin in the narrower scope and open a follow-up task for the broader scan. |
| PR-05 | F-H-03 was claimed closed by Cluster B with a telemetry-only implementation — the claim was accepted without end-to-end test evidence | The sign-off record (`SIGN-OFF.md`) states "F-H-03 closed" and the scrobble path does emit `playback.scrobble_rejected`. But the trace event name says "rejected" while the write proceeds. This divergence was not caught at sign-off. | Require test evidence that the write is actually suppressed (not just traced) before closing any P0 "boundary enforcement" finding. The test must assert that the mutation is NOT enqueued, not just that a trace event fires. |

---

## Carry-overs from prior audit (status)

| Original ID | Original severity | Claimed-closed by | Actually closed at SHA `774a540f8`? | New finding ID |
|---|---|---|---|---|
| F-F-01 | P0 | Cluster B | Yes — all 3 UI callers catch `ProfileBoundaryException` | (verified closed) |
| F-H-03 | P0 | Cluster B | **No — telemetry-only, write not halted** | F2-H-01, F2-H-02 |
| F-A-01 | P1 | Cluster B | **Partially — `get()` path has backoff; `call()`/`open()` paths do not clear backoff on success** | F2-A-01 |
| F-D-01 | P1 | Cluster B | Yes — `executeProviderLoad` falls back to stale on 429/5xx | (verified closed) |
| F-D-02 | P1 | Cluster B | Yes — `atomicRenameAndUpsert` + `@Transaction` in DAO | (verified closed) |
| F-D-03 | P1 | Cluster B | Yes — `INVALIDATED`/`EVICTED` removed from enum | (verified closed) |
| F-D-04 | P1 | Cluster B | **Partial — `localized()` removed but entire `MetadataCacheKeys` class is dead** | F2-D-04 (Nit) |
| F-D-05 | P1 | Cluster B | Yes — `TypedSingleFlightKey` in production | (verified closed) |
| F-D-06 | P1 | Cluster B | Yes — exponential schedule + `clear()` on `get()` success | (verified closed; see F2-A-01 for `call()`/`open()` gap) |
| F-TM-02 | P1 | Cluster B | Yes — `IntegrationSingleFlightTest` added | (verified closed) |
| F-A-02 | P1 | Cluster B | Yes — `coalesceConcurrent` opt-in for `call` path | (verified closed) |
| F-B-01 | P1 | Cluster F | Yes — `resolveRequest` PREVIEW branch calls `fieldResolver.resolveWithPreview` | (verified closed) |
| F-B-02 | P1 | Cluster F | Yes — `FieldResolverInjectionContractTest` pin; fallback helpers deleted | (verified closed) |
| F-B-05 | P1 | Cluster F | Yes — `requestContentId` threaded through; pinned by `FieldResolverContentIdInTraceTest` | (verified closed) |
| F-B-06 | P1 | Cluster F | Yes — negative identity cache reads + writes via `IdMappingStore.readRaw` | (verified closed) |
| F-B-07 | P1 | Cluster F | Yes — `emitNormalizerWarning` wired in `MetadataRequestNormalizer` | (verified closed) |
| F-C-02 | P1 | Cluster F | Yes — 182 constants; `IntegrationApiShapeRegistryCoverageTest` pin passes | (verified closed) |
| F-C-03 | P1 | Cluster F | Yes — `mal()`, `anilist()`, `anidb()`, `imdb()` parsers added and pinned | (verified closed) |
| F-C-04 | P1 | Cluster F | Yes — `RPDB`, `TOP_POSTERS` in enum; adapters bound via `@IntoSet` | (verified closed) |
| F-C-05 | P1 | Cluster F | Yes — `stableHashHex8` applied in both poster URL builders | (verified closed) |
| F-C-06 | P1 | Cluster F | **No — cache key correct but scope still `accountScope`; throws at construction** | F2-D-01 (P0) |
| F-E-01 | P1 | Cluster C | Yes — `perEpisodeTranslationFallbacksAttempted` surfaced in second `emitLocalizationPlan` | (verified closed) |
| F-E-02 | P1 | Cluster C | Yes — `emitLocalizationPlan` wired in TVDB, TMDB, Kitsu adapters | (verified closed) |
| F-E-03 | P1 | Cluster C | Yes — per-episode `emitFieldSelected` loop in `TvdbMetadataProviderAdapter` | (verified closed) |
| F-E-04 | P2 | Cluster C | Yes (per policy) — `fallbackLanguageEmbeddedInResponse = true` documented | (verified; per-episode Kitsu gap noted as F2-E-04 nit) |
| F-E-05 | P1 | Cluster C | Yes — `.sorted()` before `.take()`; pinned by `TvdbEpisodeLocalizationDeterministicTruncationTest` | (verified closed) |
| F-F-03 | P2 | Cluster E | Yes — `ProfileMetadataOverlay` and `ProfileResolvedDisplayDocument` deleted | (verified closed) |
| F-F-04 | P2 | Cluster E | Yes — `ProfileSwitchDeferralPolicy` wired; 4 state-machine scenarios pinned | (verified closed) |
| F-F-05 | P2 | Cluster E | Yes — `validateLegacyAccountScope` deleted (dead after F-J-02) | (verified closed) |
| F-H-01 | P2 | Cluster E | Yes — `TrackingScrobbleServiceCheckinShapeTest` pin passes; `Int?` shape enforced | (verified closed) |
| F-H-02 | P2 | Cluster E | Yes — `PlaybackSessionRegistrySingleSlotTest` pin passes | (verified closed) |
| F-J-02 | P2 | Cluster E | Yes — `Account(providerAccountId)` ctor deleted; zero references in production | (verified closed) |
| F-J-03 | P2 | Cluster E | Yes — `IntegrationScopeGlobalDeprecatedNoCallersTest` pin passes | (verified closed) |
| F-J-04 | P2 | Cluster E | Yes — `DeprecatedAnnotationsHaveReplaceWithTest` pin passes | (verified closed) |
| F-B-03 | P1 | Cluster F | Yes — `MetaDetailsViewModel` uses `metadataRouterFacade.fetchTmdbEnrichment`; no direct repo bypass | (verified closed) |
| F-B-04 | P1 | Cluster F | Yes — `networkResolvers` dispatch wired; `DETAIL_SECONDARY` has 6 production callers | (verified closed) |
| F-C-01 | P1 | Prior — TMDB person/company bypass | **Partial — person/company migrated; collection endpoint (`loadMovieCollection`) still bypasses runtime** | F2-C-01 (P1) |
| F-04-01 | P1 | Cluster F | Yes — `DETAIL_MEDIA` has production caller (`fetchTrailerUrl`); pinned by `MetaDetailsViewModelTrailerTest` | (verified closed) |
| F-04-03 | P1 | Cluster F | **Partial — title-trailer path through facade; season-media paths still bypass** | F2-TM-01 (P2) |
| F-05-01 | P1 | Cluster F | Yes — 6 production callsites confirmed across `MetaDetailsViewModel` and `CastDetailViewModel` | (verified closed) |
| F-05-02 | P1 | Cluster F | Yes — reviews routed via `fetchReviewsPage`; harvesting from step results | (verified closed) |
| F-05-03 | P1 | Cluster F | **Partial — facade entry point routes through `resolveRequest()` but result discarded; direct repo call** | F2-TM-02 (P2) |
| F-05-04 | P1 | Cluster F | **Partial — person detail screen through facade; `OrganizationDetailViewModel` still direct bypass** | F2-TM-03 (P2) |
| F-G-01 | P1 | Cluster D | **Partial — `HomeVM` and `AndroidTvFeedCatalogService` migrated; `AndroidTvChannelPublisher` uses unscoped trigger** | F2-G-02 (Nit) |
| F-G-02 | P2 | Cluster D | **Partial — test exists; payload assertions incomplete (profileHash, source missing)** | F2-G-01 (P2) |
| F-G-03 | P2 | Cluster D | Yes — three-rail formula at all 3 emission sites; pinned by static test | (verified closed) |
| F-I-01 | P2 | Cluster D | **Partial — `TraceRedactor` updated; `redactionManifest()` in `TraceBundleExporter` not updated** | F2-I-02 (P2) |
| F-I-02 | P1 | Cluster D | Yes — `emitFirstPaint` at canonical `buildCatalogItem` boundary; `HomeFirstPaintCanonicalBoundaryTest` passes | (verified closed) |
| F-I-03 | P2 | Cluster D | Yes — wildcard pattern matches `RuntimeTraceValidatorRealEmissionTest`; test XML confirmed | (verified closed) |
| F-I-04 | P2 | Cluster D | **Partial — 5 tests exist; `SAFE_METADATA_RUNTIME` mode coverage absent** | F2-I-04 (P2) |
| F-I-05 | P1 | Cluster D | Yes — YouTube trailer clients wire both interceptors; pinned by two tests | (verified closed) |
| F-F-02 | P2 | Cluster D | Yes — `assertCanSwitchProfile` emits `profile.boundary_check` before throwing | (verified closed; write path still missing — see F2-F-03) |
| F-J-01 (meta) | P1 | Cluster F | **Partial — some pins added; `AddonFirstPaintShapeArchitectureTest` failing** | F2-J-01 (P0) |
