# Path 05 — Detail secondary

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Lane:** B (router) + A (runtime) + I (trace)
- **Contract:** `DETAIL_SECONDARY` is the deepest non-PLAYER profile. `ResolverOrchestrator.schedule(DETAIL_SECONDARY)` (`app/src/main/java/com/nexio/tv/core/metadata/router/ResolverOrchestrator.kt:33-39`) adds `RATING` (local) + `ARTWORK` (local) + `REVIEWS` (network) + `RECOMMENDATIONS` (network) + `ORGANIZATION_PERSON` (network) on top of `ADDON_DISPLAY`. `ProviderPlanExecutor` (`ProviderPlanExecutor.kt:58-77, 115-128`) appends `MOVIE_VIDEOS`/`TV_VIDEOS`, `MOVIE_REVIEWS`/`TV_REVIEWS`, `MOVIE_RECOMMENDATIONS`/`TV_RECOMMENDATIONS` (TMDB) and `KitsuApiShapes.CASTINGS` / `ANIME_STAFF` / `ANIME_PRODUCTIONS` / `MEDIA_RELATIONSHIPS` (Kitsu) plan steps. `metadata.resolver_schedule` should fire with `scheduled = [ADDON_DISPLAY, RATING, ARTWORK, REVIEWS, RECOMMENDATIONS, ORGANIZATION_PERSON]`.

## Production caller status

- **`DETAIL_SECONDARY` production callers:** **0.** `grep -rn "DETAIL_SECONDARY" app/src/main` returns three hits: `MetadataModels.kt:21` (enum decl), `ResolverOrchestrator.kt:33` (schedule branch), `ProviderPlanExecutor.kt:58/66/115/123` (plan branches). No call-site requests this depth. The depth value is exercised only in `ProviderPlanExecutorTest` / `ResolverOrchestratorTest`.
- **REVIEWS production fetch path:** **Bypasses facade.** `MetaDetailsViewModel.loadReviewsAsync(...)` at `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:905-1090` calls `metadataSecondaryRepository.fetchReviews(tmdbId, contentType)` at line 1074. `MetadataSecondaryRepository.fetchReviews` (`app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataSecondaryRepository.kt:33-37`) delegates to `TmdbMetadataService.fetchReviews(...)` directly (`app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt:369`). Trakt reviews follow a parallel non-facade path through `reviewsRepository.fetchTraktReviewPage(...)` at `MetaDetailsViewModel.kt:1119`.
- **RECOMMENDATIONS production fetch path:** **Bypasses facade.** `MetaDetailsViewModel.loadMoreLikeThisAsync(...)` at `MetaDetailsViewModel.kt:856-895` calls `metadataSecondaryRepository.fetchMoreLikeThis(tmdbId, contentType)` at line 875. `MetadataSecondaryRepository.fetchMoreLikeThis` (`MetadataSecondaryRepository.kt:27-31`) delegates straight to `TmdbMetadataService.fetchMoreLikeThis(...)`. Trakt-side recommendations are fetched independently via `TraktDiscoveryService.fetchRecommendations(...)` (`app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt:285-396`) for home rows, also outside the facade.
- **ORGANIZATION_PERSON production fetch path:** **Bypasses facade and is split across two screens.**
  - In `MetaDetailsViewModel` the cast list is collapsed into the DETAIL_CORE response (cast members originate in `tvEnrichment`/`tmdbEnrichment` produced by `metadataSecondaryRepository.fetchTmdbEnrichment(...)` at lines 1391/1406; manual `MetaCastMember(...)` construction at line 1505). Person-id backfill for the Kitsu bridge calls `metadataSecondaryRepository.findPersonIdByExactName(...)` and `findCompanyIdByExactName(...)` directly (lines 1620-1625).
  - When the user navigates to a person, `CastDetailViewModel` (`app/src/main/java/com/nexio/tv/ui/screens/cast/CastDetailViewModel.kt:17,45-47`) calls `tvdbPersonService.fetchPersonDetail(personId)` and `metadataSecondaryRepository.fetchPersonDetail(personId, preferCrewCredits)` directly — `MetadataRouterFacade`, `MetadataRouter`, `ProviderPlanExecutor`, `ResolverOrchestrator`, `FieldResolver` are all skipped.
- **Resolver classes:** `ReviewResolver` / `RecommendationResolver` / `OrganizationPersonResolver` **do not exist** in `app/src/main` (`grep -rn "ReviewResolver\|RecommendationResolver\|OrganizationPersonResolver" app/src/main` returns zero matches). The `ResolverType` enum values are decorative — there is no implementation behind them.

## Chain

**Chain N/A — see F-05-01.** No production call-site requests `DETAIL_SECONDARY`, so the canonical chain (`MetadataRouterFacade → MetadataRouter → ProviderPlanExecutor → ProviderPlanRunner → adapter → IntegrationRuntime → FieldResolver`) is never entered for this depth in production. The actual production realisation is three independent direct-repository calls (reviews, more-like-this, person detail) and one collapsed-into-DETAIL_CORE path (cast list), all of which bypass the facade.

For traceability, the would-be chain is summarised here for reference only:

| # | Symbol | File:line | Status |
|---|---|---|---|
| 1 | UI requests `DETAIL_SECONDARY` after DETAIL_CORE / DETAIL_MEDIA paint | n/a | **NEVER HAPPENS** (F-05-01). |
| 2 | `ResolverOrchestrator.schedule(DETAIL_SECONDARY)` | `ResolverOrchestrator.kt:33-39` | Code present, never invoked (F-04-02 root cause). |
| 3 | `ProviderPlanExecutor.buildPlan(route, DETAIL_SECONDARY)` | `ProviderPlanExecutor.kt:58-128` | Code present (TMDB reviews/recs + Kitsu castings/staff/productions/relationships), never invoked. |
| 4 | `MetadataRouterFacade.resolveRequest(...)` dispatch | `MetadataRouterFacade.kt:33-66` | Generic non-PREVIEW branch would handle it; never called with this depth. |
| 5 | Reviews production reality | `MetaDetailsViewModel.kt:1074` → `MetadataSecondaryRepository.fetchReviews` → `TmdbMetadataService.fetchReviews` | **Bypasses facade** (F-05-02). |
| 6 | Recommendations production reality | `MetaDetailsViewModel.kt:875` → `MetadataSecondaryRepository.fetchMoreLikeThis` → `TmdbMetadataService.fetchMoreLikeThis` | **Bypasses facade** (F-05-03). |
| 7 | Person/cast production reality | `CastDetailViewModel.kt:45-47` → `TvdbPersonService.fetchPersonDetail` / `MetadataSecondaryRepository.fetchPersonDetail`; cast list collapsed into DETAIL_CORE response in `MetaDetailsViewModel.kt:1505` | **Bypasses facade** (F-05-04). |

## What does NOT happen on this path (verified)

- No `metadata.resolver_schedule(depth=DETAIL_SECONDARY)` event — `ResolverOrchestrator` not invoked from the facade (cross-ref F-04-02).
- No `metadata.route_decision(depth=DETAIL_SECONDARY)` event — `MetadataRouter` never asked.
- No `metadata.provider_plan` containing `MOVIE_REVIEWS` / `TV_REVIEWS` / `MOVIE_RECOMMENDATIONS` / `TV_RECOMMENDATIONS` / `KitsuApiShapes.CASTINGS` / `ANIME_STAFF` / `ANIME_PRODUCTIONS` / `MEDIA_RELATIONSHIPS` plan steps — `ProviderPlanExecutor.buildPlan(..., DETAIL_SECONDARY)` is never reached.
- No `metadata.field_selected` for `REVIEW_LIST` / `RECOMMENDATION_LIST` / `CAST_LIST` / `CREW_LIST` / `ORGANIZATION_LIST` — `FieldResolver` is not entered for these data types.
- No `ReviewResolver` / `RecommendationResolver` / `OrganizationPersonResolver` class instantiation — these resolver implementations do not exist.
- Reviews / recommendations / person fetches do still emit `runtime.operation_start`/`_finish`, `runtime.cache_decision`, and `http.*` events — but unscoped to any `DETAIL_SECONDARY` plan/route context (same partial-observability shape as F-04-03 for trailers).

## Trace event coverage

| Event | Emitted on this path? | Notes |
|---|---|---|
| `metadata.resolver_schedule` (depth=DETAIL_SECONDARY) | ❌ | Orchestrator not invoked anywhere in production (F-04-02). |
| `metadata.route_decision` (depth=DETAIL_SECONDARY) | ❌ | Facade never called with this depth (F-05-01). |
| `metadata.provider_plan` (TMDB reviews + recommendations / Kitsu castings + staff + productions + relationships) | ❌ | `ProviderPlanExecutor.buildPlan(..., DETAIL_SECONDARY)` unreachable. |
| `runtime.operation_start` / `_finish` per resolver (REVIEWS, RECOMMENDATIONS, ORGANIZATION_PERSON) | ⚠ PARTIAL | Underlying TMDB calls fire `runtime.operation_*` via `TmdbIntegrationProvider`/`DefaultIntegrationRuntime`, but with no `route` / `plan` / `resolver_type` correlation. Trakt review pagination and `TvdbPersonService` calls likewise fire runtime events without DETAIL_SECONDARY scoping. |
| `metadata.field_selected` (REVIEW_LIST / RECOMMENDATION_LIST / CAST_LIST / CREW_LIST / ORGANIZATION_LIST) | ❌ | `FieldResolver` not entered for these fields. |

## Verdict

❌ **FAIL** — The `DETAIL_SECONDARY` profile is fully plumbed in `MetadataDepth` / `ResolverOrchestrator` / `ProviderPlanExecutor` (with both TMDB and Kitsu plan branches) and unit-tested, but **never invoked from production code**. The data the contract assigns to this depth (reviews, recommendations, cast/crew/companies) is fetched through three independent direct-repository pipelines that skip `MetadataRouterFacade`, `ResolverOrchestrator`, `ProviderPlanExecutor`, `ProviderPlanRunner`, and `FieldResolver`. The named resolver classes (`ReviewResolver`, `RecommendationResolver`, `OrganizationPersonResolver`) do not exist.

## Findings

### F-05-01: `MetadataDepth.DETAIL_SECONDARY` has no production caller (P1)

- **Where:** Repo-wide search for `MetadataDepth.DETAIL_SECONDARY` shows only enum/orchestrator/plan-executor declarations (`MetadataModels.kt:21`, `ResolverOrchestrator.kt:33`, `ProviderPlanExecutor.kt:58/66/115/123`); no consumer requests it.
- **What:** The detail VM never escalates beyond `DETAIL_CORE` (cross-ref F-04-01); cast / reviews / recommendations / person details are loaded via direct `MetadataSecondaryRepository` / `TraktDiscoveryService` / `ReviewsRepository` / `TvdbPersonService` calls instead.
- **Impact:** The deepest non-PLAYER metadata profile is dead from the consumer's perspective. None of the contract's resolver scheduling, plan composition, or field-ownership guarantees apply to reviews/recommendations/persons. Trace events `metadata.resolver_schedule(depth=DETAIL_SECONDARY)`, `metadata.route_decision(depth=DETAIL_SECONDARY)`, `metadata.provider_plan` (with TMDB review/recommendation or Kitsu castings/staff/productions/relationships steps), and `metadata.field_selected` for the secondary fields cannot fire.
- **Severity:** P1.
- **Fix sketch:** Either (a) wire the detail VM to issue a `metadataRouterFacade.fetch...(... depth = DETAIL_SECONDARY)` call as a third enrichment step (after DETAIL_CORE / DETAIL_MEDIA), implement the missing `ReviewResolver` / `RecommendationResolver` / `OrganizationPersonResolver` classes, and have them surface fields through `FieldResolver`; or (b) remove `DETAIL_SECONDARY` from the depth enum / orchestrator / plan executor and document the actual collapsed/direct-repo behaviour as the contract.

### F-05-02: Reviews fetched via direct repository, bypassing the canonical metadata facade (P1)

- **Where:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1074` (`metadataSecondaryRepository.fetchReviews(...)`) and `:1119` (`reviewsRepository.fetchTraktReviewPage(...)`). Repository: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataSecondaryRepository.kt:33-37`. TMDB service: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt:369`.
- **What:** `MetaDetailsViewModel.loadReviewsAsync(...)` parallel-fetches TMDB reviews (`metadataSecondaryRepository.fetchReviews`) and Trakt reviews (`reviewsRepository.fetchTraktReviewPage`), then merges them into `_uiState.reviews`. Neither leg passes through `MetadataRouterFacade` / `MetadataRouter` / `ProviderPlanExecutor` / `ProviderPlanRunner` / `FieldResolver` / `ResolverOrchestrator`. There is no `ReviewResolver` class anywhere in `app/src/main`.
- **Impact:** Same observability + ownership-enforcement gap as F-04-03 (trailer bypass) and F-03-02 (movie DETAIL_CORE bypass). `metadata.field_selected(REVIEW_LIST ...)` cannot fire; multi-provider ownership rules (e.g. provider-priority, primary/secondary contention) cannot apply because reviews never enter `FieldResolver`. Trakt vs TMDB review merging is hand-rolled in the VM (`mergedReviews` deduplication at `:1035-1052`) instead of being expressed as a `FieldResolver` candidate-list resolution.
- **Severity:** P1.
- **Fix sketch:** Introduce a `REVIEW_LIST` `ResolvedField`; have `TmdbMetadataService` and the Trakt review fetcher participate as candidates inside `MetadataProviderAdapter` returns for the `MOVIE_REVIEWS` / `TV_REVIEWS` plan steps under `DETAIL_SECONDARY`; have the detail VM call the facade with `DETAIL_SECONDARY` and read merged reviews off the resolved doc.

### F-05-03: Recommendations ("More like this") fetched via direct repository, bypassing the canonical metadata facade (P1)

- **Where:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:875` (`metadataSecondaryRepository.fetchMoreLikeThis(...)`). Repository: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataSecondaryRepository.kt:27-31`. TMDB service: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt` (`fetchMoreLikeThis`).
- **What:** `MetaDetailsViewModel.loadMoreLikeThisAsync(...)` calls the secondary repo directly, gated only by a TMDB-settings toggle (`shouldLoadMoreLikeThis`). Trakt-side recommendations exist (`TraktDiscoveryService.fetchRecommendations(...)` at `app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt:285-396`) but only feed home rows, not the detail screen, and likewise bypass the facade. No `RecommendationResolver` class exists.
- **Impact:** Same observability gap as F-05-02. `metadata.field_selected(RECOMMENDATION_LIST ...)` cannot fire; `metadata.provider_plan` containing `MOVIE_RECOMMENDATIONS` / `TV_RECOMMENDATIONS` steps is never emitted in production. The VM cannot benefit from FieldResolver-style candidate ranking when (eventually) blending TMDB and Trakt recommendation sources for the same title.
- **Severity:** P1.
- **Fix sketch:** Introduce a `RECOMMENDATION_LIST` `ResolvedField`; have `TmdbMetadataService.fetchMoreLikeThis` and (optionally) Trakt-related providers participate as candidates for the `MOVIE_RECOMMENDATIONS` / `TV_RECOMMENDATIONS` plan steps under `DETAIL_SECONDARY`; have the detail VM read recommendations off the facade-resolved doc.

### F-05-04: Cast / crew / company enrichment bypasses the canonical chain — split between DETAIL_CORE collapse and direct `*PersonService` calls (P1)

- **Where:**
  - Cast list construction in `MetaDetailsViewModel` happens inside `enrichMeta`-style merging from `tvEnrichment` / `tmdbEnrichment` (the DETAIL_CORE responses) — `MetaCastMember(...)` is hand-built at `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1505`. Person/company id backfill for the Kitsu bridge: `:1620-1625` (`findPersonIdByExactName`, `findCompanyIdByExactName`).
  - Person detail screen: `app/src/main/java/com/nexio/tv/ui/screens/cast/CastDetailViewModel.kt:17,45-47` calls `tvdbPersonService.fetchPersonDetail(personId)` and `metadataSecondaryRepository.fetchPersonDetail(personId, preferCrewCredits=...)` directly.
- **What:** No `OrganizationPersonResolver` class exists; cast/crew/company information is sourced either from the DETAIL_CORE primary response (collapsed, with no separate ARTWORK/ORGANIZATION_PERSON plan step) or from direct repository / service calls in `CastDetailViewModel`. Neither path enters `MetadataRouterFacade` / `MetadataRouter` / `ProviderPlanExecutor` / `ProviderPlanRunner` / `FieldResolver` / `ResolverOrchestrator`.
- **Impact:** Same shape as F-05-02 / F-05-03 for the secondary-data class. The contract's expectation that `ORGANIZATION_PERSON` is a separately scheduled resolver with its own plan step (and Kitsu castings / staff / productions / relationships steps for anime, per `ProviderPlanExecutor.kt:123-127`) is unfulfilled. Trace events `metadata.field_selected(CAST_LIST / CREW_LIST / ORGANIZATION_LIST ...)` cannot fire. Cross-provider person-id reconciliation (TVDB vs TMDB person ids) is performed by ad-hoc VM logic instead of by `FieldResolver` candidate resolution.
- **Severity:** P1.
- **Fix sketch:** Introduce `CAST_LIST` / `CREW_LIST` / `ORGANIZATION_LIST` `ResolvedField`s (or a single `ORGANIZATION_PERSON` field); have `TmdbMetadataService` (cast/crew/companies), `TvdbPersonService`, and `KitsuMetadataService` (castings/staff/productions/relationships) participate as candidate adapters for the matching plan steps under `DETAIL_SECONDARY`; route both `MetaDetailsViewModel`'s cast-list build and `CastDetailViewModel`'s person-detail fetch through the facade; preserve TVDB-first preference via candidate priority rather than VM-side branching.

## Cross-references

- **F-04-01** (no `DETAIL_MEDIA` caller) — exact same pattern at the previous depth; F-05-01 is the analogue at `DETAIL_SECONDARY`.
- **F-04-02** (`ResolverOrchestrator` never invoked from the facade) — root cause that prevents `metadata.resolver_schedule(depth=DETAIL_SECONDARY)` from ever firing, even if a caller existed.
- **F-04-03** (trailer bypasses the canonical chain via `TrailerService`) — same anti-pattern, applied to reviews (F-05-02), recommendations (F-05-03), and cast/persons (F-05-04). Together these form a consistent picture: every depth beyond `DETAIL_CORE` is realised by a direct-repository / direct-service shortcut that skips the facade chain.
- **F-04-04** (no `ArtworkResolver`) — analogous to F-05-04: `ResolverType.ORGANIZATION_PERSON` enum value exists but has no resolver implementation; `ResolverType.REVIEWS` and `ResolverType.RECOMMENDATIONS` are the same shape.
- **Path 03 (Detail core) F-03-01** (UI manual-construction of the facade with `emptySet()` adapters and noop trace) — explains why even the partial `runtime.*` events that *do* fire are not scoped to a `DETAIL_SECONDARY` plan/route context.
- **Boundary map B-Q3.2** (UI directly instantiating `FieldResolver()`) — extends to `MetaDetailsViewModel` / `CastDetailViewModel` for the secondary-data lane.
