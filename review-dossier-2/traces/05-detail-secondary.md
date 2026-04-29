# Trace 05 — Detail Secondary Path

**Review SHA:** `774a540f8`
**Date:** 2026-04-29
**Lane cross-references:** B-08, C (provider shape ownership), F-05-01 through F-05-04

---

## 1. Scope

This trace verifies the current state of the `MetadataDepth.DETAIL_SECONDARY` pipeline: whether it
has production callers, how reviews / recommendations / cast-crew-company data reach the UI, and
whether `ResolverOrchestrator.schedule(DETAIL_SECONDARY)` correctly dispatches all four network
resolvers (TrailerResolver, ReviewResolver, RecommendationResolver, OrganizationPersonResolver).

---

## 2. Verification Commands Run

```bash
grep -rn "MetadataDepth.DETAIL_SECONDARY\|DETAIL_SECONDARY" app/src/main/java/ 2>/dev/null
grep -rn "ReviewResolver\|RecommendationResolver\|OrganizationPersonResolver\|TraktReviewMetadataAdapter" app/src/main/java/ 2>/dev/null
grep -rn "fetchReviews\|fetchRecommendations\|fetchCastCrew" app/src/main/java/com/nexio/tv/ui/ 2>/dev/null
grep -rn "metadataSecondaryRepository\.\|reviewsRepository\.\|tmdbMetadataService\." app/src/main/java/com/nexio/tv/ui/ 2>/dev/null
```

---

## 3. DETAIL_SECONDARY — Production Caller Status

**F-05-01 resolution: CLOSED.** `MetadataDepth.DETAIL_SECONDARY` now has six production callsites.

| Callsite | File | Facade method |
|---|---|---|
| `loadMoreLikeThisAsync` | `MetaDetailsViewModel.kt:854` | `metadataRouterFacade.fetchRecommendations(...)` |
| `loadReviewsAsync` (initial page) | `MetaDetailsViewModel.kt:935` | `metadataRouterFacade.fetchReviewsPage(...)` |
| `loadMoreTraktReviewsPage` (paginated) | `MetaDetailsViewModel.kt:999` | `metadataRouterFacade.fetchReviewsPage(...)` |
| `hydrateKitsuNavigationTargetsAsync` — actor id lookup | `MetaDetailsViewModel.kt:1481` | `metadataRouterFacade.findPersonIdByExactName(...)` |
| `hydrateKitsuNavigationTargetsAsync` — company id lookup | `MetaDetailsViewModel.kt:1497` | `metadataRouterFacade.findCompanyIdByExactName(...)` |
| `CastDetailViewModel.loadPersonDetail` | `CastDetailViewModel.kt:59` | `metadataRouterFacade.fetchPersonDetail(...)` |

All six callsites pass `depth = MetadataDepth.DETAIL_SECONDARY` inside a `MetadataRequest` and
route through `MetadataRouterFacade`. None bypass the facade.

---

## 4. Reviews — Source Path

**F-05-02 resolution: CLOSED.**

Reviews are delivered via `MetadataRouterFacade.fetchReviewsPage(...)`, which:

1. Calls `resolveRequest(paginatedRequest)` — triggers the full canonical pipeline at
   `DETAIL_SECONDARY` depth.
2. Harvests `resolverReviews` directly from `resolution.providerRunResult?.stepResults` by
   collecting every candidate whose `fields[ResolvedField.REVIEWS]` is non-empty.
3. Falls back to `repo.fetchReviews(tmdbId, contentType)` (TMDB-only, no continuation) only when
   no step produced a `REVIEWS` candidate.

There are **two registered review adapters** (both DI-bound via `MetadataExecutionModule`):

- `TmdbReviewMetadataAdapter` — claims `MOVIE_REVIEWS` / `TV_REVIEWS` shapes; calls
  `MetadataSecondaryRepository.fetchReviews(...)` internally.
- `TraktReviewMetadataAdapter` — claims `MOVIE_COMMENTS` / `SHOW_COMMENTS` shapes; calls
  `ReviewsRepository.fetchTraktReviewPage(...)` and reads `route.pagination?.page` / `limit` for
  cursor support. Trakt step is appended by `ProviderPlanExecutor.tmdbSteps(...)` only when
  `route.targetIds.containsKey(MetadataPrimaryProvider.IMDB)`.

`ReviewResolver.resolve(...)` aggregates all REVIEWS candidates (union, not first-match) and emits
a `metadata.field_selected(REVIEWS)` trace event with `sourceRole = "aggregate"`.

The comment at `MetaDetailsViewModel.kt:922` explicitly records the F-05-02 closure:
> "F-05-02 closed: Trakt comments now route via MetadataRouterFacade.fetchReviewsPage…"

**No direct repo bypass for reviews remains in the UI layer.**

---

## 5. Recommendations — Source Path

**F-05-03 resolution: PARTIAL / ARCHITECTURAL GAP REMAINS.**

`MetadataRouterFacade.fetchRecommendations(...)` follows the "intentional discard" pattern
(documented in Lane B-08):

1. Calls `resolveRequest(metadataRequest)` — fires canonical trace events including dispatching
   `RecommendationResolver` (because `DETAIL_SECONDARY` schedules `ResolverType.RECOMMENDATIONS`).
2. **Discards** the resolution result entirely.
3. Calls `repo.fetchMoreLikeThis(tmdbId, contentType)` directly to obtain the recommendations list.

This means the `RecommendationResolver` runs, emits its `metadata.field_selected(RECOMMENDATIONS)`
trace event, but **its output is never consumed by the caller**. The actual data delivered to the
UI comes from the secondary repository direct call, not from the resolver pipeline.

Contrast with reviews: `fetchReviewsPage` **does** harvest from `resolution.providerRunResult.stepResults`,
meaning the resolver output feeds the result. Recommendations have no equivalent harvest step.

`TmdbRecommendationMetadataAdapter` is DI-registered and correctly emits a
`ResolvedField.RECOMMENDATIONS` candidate — but `fetchRecommendations` ignores it. The facade KDoc
states the discard is intentional ("Delegate to the secondary repository for the TMDB recommendations
list"), but this is inconsistent with the reviews path, which successfully adopted the resolver harvest
model. **This is an active architectural asymmetry.**

---

## 6. Cast / Crew / Company — Source Path

**F-05-04 resolution: CLOSED for the person-detail path; OPEN for the organization-detail screen.**

### 6a. Person detail (cast/crew screen)

`CastDetailViewModel.loadPersonDetail()` routes entirely through
`metadataRouterFacade.fetchPersonDetail(...)` at `DETAIL_SECONDARY` depth.

`MetadataRouterFacade.fetchPersonDetail(...)`:
1. Calls `resolveRequest(metadataRequest)`.
2. If `contentId.startsWith("tvdb:person:")`, extracts the CAST candidate from
   `resolution.providerRunResult?.stepResults` (harvests from `TvdbOrganizationPersonAdapter`
   output directly).
3. Otherwise delegates to `repo.fetchPersonDetail(personId, preferCrewCredits)` — TMDB path.

Two adapters handle the person/org shapes:
- `TmdbOrganizationPersonAdapter` — claims `PERSON_DETAIL`, `PERSON_COMBINED_CREDITS`,
  `PERSON_FIND_BY_NAME`, `COMPANY_DETAIL`, `NETWORK_DETAIL`, `COMPANY_FIND_BY_NAME` shapes.
- `TvdbOrganizationPersonAdapter` — claims `PERSON_EXTENDED` shape.

`OrganizationPersonResolver.resolve(...)` picks independent winners for CAST, CREW, and
ORGANIZATION_LIST fields (first-match-by-priority for each), emitting three separate
`metadata.field_selected` events.

### 6b. Kitsu bridge hydration

`MetaDetailsViewModel.hydrateKitsuNavigationTargetsAsync(...)` calls
`metadataRouterFacade.findPersonIdByExactName(...)` and
`metadataRouterFacade.findCompanyIdByExactName(...)` — both route via `DETAIL_SECONDARY`, fire
`resolveRequest()`, then delegate to `repo.findPersonIdByExactName(...)` /
`repo.findCompanyIdByExactName(...)` using the same intentional-discard pattern as recommendations.

### 6c. Organization detail screen (open gap)

`OrganizationDetailViewModel` (`ui/screens/organization/OrganizationDetailViewModel.kt`) injects
`TmdbOrganizationService` directly and calls `tmdbOrganizationService.fetchOrganizationDetail(...)`.
**This is a direct service call that completely bypasses the metadata facade and the resolver
pipeline.** No `MetadataDepth`, no trace events, no `OrganizationPersonResolver` involvement.
This screen was not covered by the F-05-04 remediation.

---

## 7. ResolverOrchestrator.schedule(DETAIL_SECONDARY) — Dispatch Verification

**F-B-04 resolution: CLOSED.**

`ResolverOrchestrator.schedule(DETAIL_SECONDARY)` (lines 37–43):

```kotlin
MetadataDepth.DETAIL_SECONDARY -> {
    localResolvers += ResolverType.RATING
    localResolvers += ResolverType.ARTWORK
    networkResolvers += ResolverType.REVIEWS
    networkResolvers += ResolverType.RECOMMENDATIONS
    networkResolvers += ResolverType.ORGANIZATION_PERSON
}
```

`MetadataRouterFacade.resolveRequest(...)` dispatches `resolverSchedule.networkResolvers` via a
`forEach` / `when` at lines 110–143:

| ResolverType | Dispatch call | Null-safe? |
|---|---|---|
| `TRAILERS` | `trailerResolver?.resolve(...)` | Yes (`?.`) |
| `REVIEWS` | `reviewResolver?.resolve(...)` | Yes (`?.`) |
| `RECOMMENDATIONS` | `recommendationResolver?.resolve(...)` | Yes (`?.`) |
| `ORGANIZATION_PERSON` | `organizationPersonResolver?.resolve(...)` | Yes (`?.`) |

All four resolvers are injected as nullable constructor parameters in `MetadataRouterFacade`. The
`@Inject`-constructed singleton in production (via `MetadataExecutionModule`) provides non-null
instances for all bound adapters. The `?.` guard only matters for test construction or manual
instantiation without full DI.

Note: `TRAILERS` is NOT scheduled under `DETAIL_SECONDARY` by the orchestrator — it is scheduled
under `DETAIL_MEDIA` only. However, `ProviderPlanExecutor.tmdbSteps(...)` adds a
`TV_VIDEOS`/`MOVIE_VIDEOS` step at `DETAIL_SECONDARY` depth (line 77), meaning video candidates
are fetched but `TrailerResolver` is not dispatched for them. This is internally consistent
(videos are a plan-step artefact, not a resolver-dispatched output) but worth noting.

---

## 8. ProviderPlanExecutor — DETAIL_SECONDARY Steps

For TMDB routes (`tmdbSteps`), `DETAIL_SECONDARY` appends:
- `TV_VIDEOS` / `MOVIE_VIDEOS` (ProviderPlanRole.MEDIA)
- `TV_REVIEWS` / `MOVIE_REVIEWS` (ProviderPlanRole.SECONDARY)
- `TV_RECOMMENDATIONS` / `MOVIE_RECOMMENDATIONS` (ProviderPlanRole.SECONDARY)
- `SHOW_COMMENTS` / `MOVIE_COMMENTS` (ProviderPlanRole.SECONDARY, `required = false`) — **only
  when `route.targetIds.containsKey(MetadataPrimaryProvider.IMDB)`**

For TVDB routes (`tvdbSteps`): `DETAIL_SECONDARY` produces **no additional steps** beyond the base
`SERIES_EXTENDED` core step. TVDB has no review, recommendation, or cast-specific secondary shapes
defined in the executor. Cast data for TVDB is delivered as part of the `SERIES_EXTENDED` response.

For Kitsu routes (`kitsuSteps`), `DETAIL_SECONDARY` appends:
- `ANIME_EPISODES` (ProviderPlanRole.SECONDARY)
- `CASTINGS` (ProviderPlanRole.SECONDARY)
- `ANIME_STAFF` (ProviderPlanRole.SECONDARY)
- `ANIME_PRODUCTIONS` (ProviderPlanRole.SECONDARY)
- `MEDIA_RELATIONSHIPS` (ProviderPlanRole.SECONDARY)

---

## 9. DI Binding Status

`MetadataExecutionModule` (`core/di/MetadataExecutionModule.kt`) registers via `@Binds @IntoSet`:

| Adapter | Bound |
|---|---|
| `TmdbReviewMetadataAdapter` | Yes |
| `TraktReviewMetadataAdapter` | Yes |
| `TmdbRecommendationMetadataAdapter` | Yes |
| `TmdbOrganizationPersonAdapter` | Yes |
| `TvdbOrganizationPersonAdapter` | Yes |

All five secondary-depth adapters are DI-registered and will be injected into `ProviderPlanRunner`'s
`Set<MetadataProviderAdapter>`. The dispatcher (`ProviderPlanRunner.run`) selects adapters via
`adapters.firstOrNull { it.provider == step.provider && it.supports(step) }`.

---

## 10. Findings

### DS-01 (P2) — Recommendations resolver output is never consumed; `fetchRecommendations` uses direct repo bypass

**Severity:** P2 — Architectural asymmetry; recommendations data does not flow through the resolver pipeline despite the resolver being scheduled and dispatched.

`MetadataRouterFacade.fetchRecommendations(...)` calls `resolveRequest()` (which dispatches
`RecommendationResolver`) but discards the resolution result and calls
`repo.fetchMoreLikeThis(tmdbId, contentType)` directly. `TmdbRecommendationMetadataAdapter` emits a
`ResolvedField.RECOMMENDATIONS` candidate that is never read by the facade entry point.

Compare with reviews: `fetchReviewsPage` harvests from `resolution.providerRunResult?.stepResults`.
Recommendations have no equivalent harvest. This means:

- The `metadata.field_selected(RECOMMENDATIONS)` trace event fires from `RecommendationResolver`
  but does not describe the data actually returned to the caller.
- A future recommendations provider (e.g., Trakt suggestions) registered as an adapter would
  produce candidates that are silently ignored by `fetchRecommendations`.

**Required fix:** Mirror the `fetchReviewsPage` harvest pattern — after `resolveRequest()`, collect
`ResolvedField.RECOMMENDATIONS` from `resolution.providerRunResult?.stepResults`, fall back to
`repo.fetchMoreLikeThis()` only when the resolver produced no candidates.

**Files to change:**
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt` (`fetchRecommendations` method)

---

### DS-02 (P2) — `OrganizationDetailViewModel` bypasses the metadata facade entirely

**Severity:** P2 — Screen-level bypass; no trace events, no resolver pipeline, no `OrganizationPersonResolver` involvement.

`OrganizationDetailViewModel` (`ui/screens/organization/OrganizationDetailViewModel.kt`) injects
`TmdbOrganizationService` directly and calls `tmdbOrganizationService.fetchOrganizationDetail(...)`
with no `MetadataRouterFacade` involvement. This is the only UI screen in the secondary-depth
surface that was not migrated to the canonical facade path by the F-05-04 remediation.

**Required fix:** Route `OrganizationDetailViewModel` through `MetadataRouterFacade.fetchPersonDetail()`
(with an appropriate content-id convention for organization entities) or add a dedicated
`fetchOrganizationDetail(...)` method to the facade that fires `resolveRequest()` and delegates to
the service. The `TmdbOrganizationPersonAdapter` already handles `COMPANY_DETAIL` and
`NETWORK_DETAIL` shapes.

**Files to change:**
- `app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailViewModel.kt`
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt` (new method, if needed)

---

### DS-03 (Nit) — TVDB routes at `DETAIL_SECONDARY` produce no secondary steps; resolver dispatch fires with empty candidates

**Severity:** Nit — Not a bug, but a traceability concern.

For TVDB-routed content at `DETAIL_SECONDARY`, `ProviderPlanExecutor.tvdbSteps(...)` appends no
additional steps beyond `SERIES_EXTENDED`. When `resolveRequest()` runs and dispatches
`REVIEWS`, `RECOMMENDATIONS`, and `ORGANIZATION_PERSON` resolvers (as scheduled by
`ResolverOrchestrator`), all three resolvers receive empty candidate lists. Each emits no
`metadata.field_selected` event (early-return on empty). A trace reader querying "what REVIEWS
candidates were evaluated for this TVDB series?" will find no trace entry — which is correct but
potentially confusing. A dedicated `DETAIL_SECONDARY` TVDB no-op trace marker could improve
observability.

---

### DS-04 (Nit) — Trakt review step gated on IMDB id presence; TVDB-only content silently skips Trakt reviews

**Severity:** Nit — By-design, but undocumented at the callsite.

`ProviderPlanExecutor.tmdbSteps(...)` appends `SHOW_COMMENTS` / `MOVIE_COMMENTS` only when
`route.targetIds.containsKey(MetadataPrimaryProvider.IMDB)`. TVDB-routed content (or TMDB content
without a resolved IMDB id in `targetIds`) never reaches `TraktReviewMetadataAdapter`. This is
intentional (Trakt uses IMDB ids as lookup keys), but there is no comment or trace event that
explains the omission to a developer inspecting why Trakt reviews never appear for a given title.

---

## 11. Summary Table

| ID | Severity | Description | Status |
|---|---|---|---|
| DS-01 | P2 | `fetchRecommendations` discards resolver output; actual data comes from direct `repo.fetchMoreLikeThis()` call — inconsistent with reviews path | OPEN |
| DS-02 | P2 | `OrganizationDetailViewModel` bypasses `MetadataRouterFacade` and calls `TmdbOrganizationService` directly — no resolver pipeline, no trace events | OPEN |
| DS-03 | Nit | TVDB routes at `DETAIL_SECONDARY` produce no secondary steps; resolver dispatch fires with empty candidates, leaving no trace entry | INFO |
| DS-04 | Nit | Trakt review step gated on IMDB id in `targetIds`; TVDB-only content silently receives no Trakt reviews | INFO |

**F-05-01 (no production callers):** CLOSED — 6 production callsites confirmed across `MetaDetailsViewModel` and `CastDetailViewModel`.

**F-05-02 (reviews bypass):** CLOSED — reviews routed via `MetadataRouterFacade.fetchReviewsPage`, harvesting from resolver pipeline step results; `TraktReviewMetadataAdapter` DI-registered and plan-appended.

**F-05-03 (recommendations bypass):** PARTIALLY CLOSED — facade entry point routes through `resolveRequest()`, but result is discarded; actual data still sourced directly from `repo.fetchMoreLikeThis()`. Resolver is dispatched but its output is never consumed. (DS-01)

**F-05-04 (cast/crew/company bypass):** PARTIALLY CLOSED — person detail screen (`CastDetailViewModel`) routed through facade; Kitsu bridge hydration uses facade for id lookups. Organization detail screen (`OrganizationDetailViewModel`) remains a direct bypass. (DS-02)

**F-B-04 (orchestrator dispatch):** CLOSED — `ResolverOrchestrator` correctly schedules REVIEWS, RECOMMENDATIONS, ORGANIZATION_PERSON for `DETAIL_SECONDARY`; facade dispatches all three via null-safe calls in `resolveRequest()`.

---

## 12. Overall Path Health

**Detail Secondary is substantially healthy but not fully closed.** The orchestrator scheduling and
resolver dispatch are correct. The reviews path is the cleanest: it runs through the resolver
pipeline and harvests from step results. The recommendations path has a documented but active
asymmetry (resolver dispatched, output ignored, repo called directly — DS-01). One UI screen
(`OrganizationDetailViewModel`) remains outside the facade boundary entirely (DS-02). Both open
findings are P2 architectural debt items with clear remediation paths.
