## ADDED Requirements

### Requirement: Trakt is a participating MetadataPrimaryProvider

`MetadataPrimaryProvider` MUST include `TRAKT` as a participating provider for the resolver pipeline. A registered `TraktReviewMetadataAdapter` MUST handle plan-step `apiShapeId` values `TraktApiShapes.MOVIE_COMMENTS` and `TraktApiShapes.SHOW_COMMENTS`, returning a `MetadataCandidate` populated with `ResolvedField.REVIEWS` from `ReviewsRepository.fetchTraktReviewPage(...)`.

#### Scenario: Detail screen review load aggregates TMDB and Trakt candidates

- **WHEN** `MetadataRouterFacade.fetchReviews(...)` is invoked at `DETAIL_SECONDARY` depth for a movie with both TMDB reviews and Trakt comments available
- **THEN** the resolved document's `REVIEWS` field contains the aggregated list from both providers
- **AND** the trace contains exactly one `metadata.field_selected(field = "REVIEWS")` whose `selectedProvider` lists both `TMDB` and `TRAKT` (per `ReviewResolver`'s aggregation rule)

### Requirement: TVDB person fetch routes through MetadataRouterFacade

`MetadataRouterFacade.fetchPersonDetail(...)` MUST smart-route requests whose `metadataRequest.contentId` carries a `tvdb:person:` prefix to the registered `TvdbOrganizationPersonAdapter`, which delegates to `TvdbPersonService.fetchPersonDetail(...)`. Direct calls to `TvdbPersonService.fetchPersonDetail` from UI code (e.g. `CastDetailViewModel`) are forbidden.

#### Scenario: CastDetailViewModel TVDB branch routes through facade

- **WHEN** `CastDetailViewModel.loadPersonDetail()` is invoked with `provider == "tvdb"` and `personId == 287`
- **THEN** the trace contains one `metadata.route_decision` with `provider = TVDB` and one `metadata.field_selected(field = "CAST", selectedProvider = "TVDB")`
- **AND** `TvdbPersonService.fetchPersonDetail` is invoked exactly once (via the adapter dispatch), not directly from the VM

### Requirement: Production code outside the metadata router and skip-segment package never calls TvdbPersonService.fetchPersonDetail or ReviewsRepository.fetchTraktReviewPage directly

Following the F-J-01 boundary-test pattern, after this change lands, the architecture-boundary test MUST forbid direct calls to `TvdbPersonService.fetchPersonDetail` and `ReviewsRepository.fetchTraktReviewPage` from any file outside `data/integration/metadata/`.

#### Scenario: Boundary test fails when a VM bypasses the facade

- **WHEN** a developer adds `tvdbPersonService.fetchPersonDetail(...)` to `CastDetailViewModel.kt`
- **THEN** `./gradlew :app:testUniversalDebugUnitTest --tests MetadataRouterBoundaryTest` fails with a clear message naming the offending file:line
