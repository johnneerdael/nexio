## Why

Cluster A's facade-bypass migration (`migrate-detail-screen-bypasses-to-router`) deferred two adapters because adding `MetadataPrimaryProvider.TRAKT` required touching ~19 production files of exhaustive `when` blocks, and the audit's primary observability goal was already met by the TMDB adapters:

- **F-05-02 (Trakt half):** `ReviewsRepository.fetchTraktReviewPage(...)` is still called directly from `MetaDetailsViewModel.kt:1135`. The TODO comment on line 1132 documents the deferral.
- **F-05-04 (TVDB half):** `TvdbPersonService.fetchPersonDetail(personId)` is still called directly from `CastDetailViewModel.kt:51`. The TODO comment on line 49 documents the deferral.

This change closes both deferrals: it adds the `TRAKT` enum value (with the unavoidable mechanical fan-out into the existing exhaustive `when` sites — every site gets a no-op or "not-supported" branch), introduces `TraktReviewMetadataAdapter` and `TvdbOrganizationPersonAdapter`, and migrates the two remaining direct call sites onto the canonical `MetadataRouterFacade` pipeline.

## What Changes

### ADDED

- `MetadataPrimaryProvider.TRAKT` enum value.
- `TraktReviewMetadataAdapter` (`MetadataPrimaryProvider.TRAKT`, supports `TraktApiShapes.MOVIE_COMMENTS` / `SHOW_COMMENTS`, delegates to `ReviewsRepository.fetchTraktReviewPage`).
- `TvdbOrganizationPersonAdapter` (`MetadataPrimaryProvider.TVDB`, supports `TvdbApiShapes.PERSON_EXTENDED`, delegates to `TvdbPersonService.fetchPersonDetail`).
- `MetadataRouterFacade.fetchPersonDetail(...)` now smart-routes to the TVDB adapter when `metadataRequest.contentId` carries a `tvdb:person:` prefix.

### MODIFIED

- `MetaDetailsViewModel.kt:1135` — `reviewsRepository.fetchTraktReviewPage(...)` replaced with `metadataRouterFacade.fetchReviews(...)` (the existing facade method now aggregates TMDB + Trakt review candidates via the resolver).
- `CastDetailViewModel.kt:51` — `tvdbPersonService.fetchPersonDetail(personId)` replaced with `metadataRouterFacade.fetchPersonDetail(...)`. The "tvdb" provider branch is no longer needed.

## Impact

- Affected specs: `metadata-router`.
- Affected code: ~19 production files gain no-op `TRAKT ->` branches; 2 VM files migrated; 2 new adapter files; 1 OpenSpec change.
- Trace bundles: detail-screen Trakt-comment loads now emit `metadata.route_decision/identity_resolution/provider_plan/field_selected` for the `REVIEWS` field; TVDB person loads now emit the same for the `CAST` field. The `ReviewResolver`'s aggregation behavior (added in cluster A) now actually merges TMDB and Trakt candidates — previously it could only see TMDB.
