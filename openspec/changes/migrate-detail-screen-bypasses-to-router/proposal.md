## Why

The architecture audit (`review-dossier/09-known-gaps.md`) identified 9 detail-screen code paths that bypass `MetadataRouterFacade`, calling `metadataSecondaryRepository.*`, `trailerService.*`, and `tvdbPersonService.*` directly. Bypassing the facade silences the four canonical trace events (`metadata.route_decision`, `metadata.identity_resolution`, `metadata.provider_plan`, `metadata.field_selected`), defeats `FieldResolver`'s primary-wins ownership rule, and leaves two declared-but-unwired `MetadataDepth` values (`DETAIL_MEDIA`, `DETAIL_SECONDARY`) without production callers.

This change migrates every bypass onto the canonical pipeline, introduces three new resolvers (`ReviewResolver`, `RecommendationResolver`, `OrganizationPersonResolver`) plus a trailer adapter family, wires `MetadataRouterFacade` to dispatch the `resolverSchedule.networkResolvers` it already emits (closes F-B-04), and tightens the architecture-boundary test so the bypass pattern can't re-emerge.

## What Changes

### ADDED

- `ReviewResolver`, `RecommendationResolver`, `OrganizationPersonResolver` interfaces under `core/metadata/router/resolver/`.
- `MetadataPrimaryProvider` adapters: `TmdbReviewMetadataAdapter`, `TraktReviewMetadataAdapter`, `TmdbRecommendationMetadataAdapter`, `TmdbOrganizationPersonAdapter`, `TmdbTrailerMetadataAdapter`, `TvdbTrailerMetadataAdapter`.
- `ResolvedField.ORGANIZATION_LIST`.
- `MetadataRouterFacade.resolveRequest(...)` now dispatches `DETAIL_MEDIA` (TRAILERS) and `DETAIL_SECONDARY` (REVIEWS + RECOMMENDATIONS + ORGANIZATION_PERSON).
- `TmdbApiShapes.SEARCH_PEOPLE`, `SEARCH_COMPANIES`, `PERSON_FIND_BY_NAME`, `COMPANY_FIND_BY_NAME` constants.

### MODIFIED

- `ResolverOrchestrator.schedule(depth)`: DETAIL_MEDIA now schedules TRAILERS (network); DETAIL_SECONDARY schedules REVIEWS + RECOMMENDATIONS + ORGANIZATION_PERSON (network); ARTWORK stays only at DETAIL_CORE.
- `MetadataRouterFacade.resolveRequest(...)` iterates `resolverSchedule.networkResolvers` and dispatches each through the registered resolver, attaching results to the returned `ResolvedMetadataDocument` (closes F-B-04).
- `MetaDetailsViewModel`, `CastDetailViewModel`: replace direct repository calls with facade reads.
- `TmdbIntegrationProvider`: `loadPersonDetails`, `loadPersonCombinedCredits`, `searchPeople`, `searchCompanies` now route through `runtime.call(IntegrationCallSpec(...))`.

### REMOVED

- `ResolverType.SKIP_SEGMENTS` and `ResolvedField.SKIP_SEGMENTS` (player skip stays on `SkipIntroRepository` as canonical, latency-critical surface).
- Whitelist entries in `MetadataRouterBoundaryTest` for `MetadataSecondaryRepository.fetchReviews/MoreLikeThis/PersonDetail`, `TrailerService.resolveTrailer` (callers in detail VMs are gone after this change).

## Impact

- Affected specs: `metadata-router`.
- Affected code: `core/metadata/router/**`, `data/integration/metadata/**`, `ui/screens/detail/**`, `data/integration/tmdb/TmdbIntegrationProvider.kt`, architecture tests.
- Trace bundles: detail screens now emit `metadata.route_decision/identity_resolution/provider_plan/field_selected` for every TMDB enrichment, every review/recommendation/cast load, and every trailer resolution. Path 04 + Path 05 + Path 12 trace assertions become provable.
- Player skip path is unchanged (intentional — latency-critical, single-purpose).
