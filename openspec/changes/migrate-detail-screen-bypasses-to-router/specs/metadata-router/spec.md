## ADDED Requirements

### Requirement: DETAIL_MEDIA depth resolves trailer fields via canonical facade

A caller invoking `MetadataRouterFacade.resolveRequest(depth = DETAIL_MEDIA)` MUST receive a `ResolvedMetadataDocument` whose trailer fields were produced by a registered `TrailerResolver` consuming candidates from `MetadataProviderAdapter` plan-step results for `MOVIE_VIDEOS` / `TV_VIDEOS` / `SEASON_VIDEOS`.

#### Scenario: Movie DETAIL_MEDIA emits field_selected for TRAILERS

- **WHEN** the user opens a movie detail screen and the VM issues a follow-up `resolveRequest(depth = DETAIL_MEDIA)` after `DETAIL_CORE`
- **THEN** the trace contains exactly one `metadata.route_decision`, one `metadata.provider_plan` step targeting `tmdb.movie.videos`, and one `metadata.field_selected(field = "TRAILERS")` whose `selectedProvider` is `TMDB` (or `TVDB` for TV with TVDB-first preference)

### Requirement: DETAIL_SECONDARY depth resolves reviews, recommendations, and people via canonical facade

A caller invoking `MetadataRouterFacade.resolveRequest(depth = DETAIL_SECONDARY)` MUST receive a `ResolvedMetadataDocument` whose review, recommendation, and person/company fields were produced by `ReviewResolver`, `RecommendationResolver`, and `OrganizationPersonResolver` respectively, dispatched from `resolverSchedule.networkResolvers`.

#### Scenario: Detail screen secondary load emits one route_decision per resolver

- **WHEN** the detail VM issues `resolveRequest(depth = DETAIL_SECONDARY)`
- **THEN** the trace contains `metadata.field_selected(field = "REVIEWS")`, `metadata.field_selected(field = "RECOMMENDATIONS")`, and `metadata.field_selected(field = "ORGANIZATION_LIST")`, each with `selectedProvider` matching the candidate priority in the registered resolver

### Requirement: Direct calls to MetadataSecondaryRepository, TrailerService.resolveTrailer, and TmdbApi person/company helpers from non-router code are forbidden

Production code outside `core/metadata/router/` and `data/integration/metadata/` MUST NOT invoke `metadataSecondaryRepository.fetchTmdbEnrichment`, `fetchMoreLikeThis`, `fetchReviews`, `fetchPersonDetail`, `findPersonIdByExactName`, `findCompanyIdByExactName`, `trailerService.resolveTrailer`, or `tmdbApi.personDetail`/`personCombinedCredits`/`searchPeople`/`searchCompanies` directly. The architecture-boundary test enforces this.

#### Scenario: MetadataRouterBoundaryTest fails when a VM bypasses the facade

- **WHEN** a developer adds `metadataSecondaryRepository.fetchReviews(...)` in `MetaDetailsViewModel.kt`
- **THEN** `./gradlew :app:testUniversalDebugUnitTest --tests MetadataRouterBoundaryTest` fails with a clear message naming the offending file:line

### Requirement: DETAIL_CORE primary source is the Stremio meta-addon, with canonical facade providing enrichment

`MetaDetailsViewModel.loadMeta()` MUST source the primary detail metadata (canonical id, title, overview, season list, episode placeholders) from a Stremio meta-addon via `getMetaFromAllAddons` / `getMeta`. `MetadataRouterFacade.resolveRequest(depth = DETAIL_CORE)` then enriches the addon-supplied baseline with TMDB/TVDB-sourced fields using `FieldResolver` primary-wins ownership rules. This layering is intentional — the addon ecosystem is the catalog source of truth; canonical providers are enrichment.

#### Scenario: Detail screen load sequence is addon-first, facade-second

- **WHEN** the user opens a detail screen
- **THEN** trace order MUST show one Stremio meta-addon HTTP request → one `metadata.route_decision` for DETAIL_CORE → ≥1 `metadata.field_selected` events for non-null enrichment fields
- **AND** the addon-supplied title field MUST appear as `selectedProvider = "ADDON"` in `metadata.field_selected` if the addon's title is preferred over TMDB

## REMOVED Requirements

### Requirement: ResolverType.SKIP_SEGMENTS lists a resolver scheduled by orchestrator

**Reason:** Player-skip latency requirements (sub-50ms from playback start) are incompatible with the resolver pipeline's identity-resolution + provider-plan overhead. `SkipIntroRepository` is documented as the canonical, single-purpose surface.

**Migration:** Existing callers (`PlayerRuntimeControllerObservers.fetchSkipIntervals`) already use `SkipIntroRepository` directly; only the enum value and orchestrator scheduling are removed. A new architecture pin (`SkipIntroRepositoryCanonicalSurfaceTest`) asserts no other code may fetch skip intervals.

## MODIFIED Requirements

### Requirement: ResolverOrchestrator.schedule(depth) outputs are dispatched, not only emitted

`MetadataRouterFacade` MUST iterate `resolverSchedule.networkResolvers` and dispatch each through the registered resolver. The result MUST be merged into the returned `ResolvedMetadataDocument`. Emitting `metadata.resolver_schedule` without dispatch is forbidden.

#### Scenario: A scheduled resolver that fails to dispatch breaks the validator

- **WHEN** the trace contains `metadata.resolver_schedule { networkResolvers = [REVIEWS, RECOMMENDATIONS] }`
- **AND** the validator runs `ScheduledResolversAreDispatched`
- **THEN** if no `metadata.field_selected` for `REVIEWS` and `RECOMMENDATIONS` follows in the same `traceSessionId`, the validator MUST fail
