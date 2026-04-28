## Why

The architecture audit (`review-dossier/09-known-gaps.md`) identified 10 cluster-D findings against trace observability:

- **F-I-02 (P1):** First-paint emission was wired to a router pre-flight site, not the canonical first-paint boundary. Parallel-session work appears to have moved it; this change pins the contract via test.
- **F-I-03 (P1):** `RuntimeTraceValidatorRealEmissionTest` is excluded from the audit-task filter — schema drift between an emission site and a validator lookup will pass the synthetic-event golden but break real-emission validation silently.
- **F-G-01 (P1):** Profile-scoped CW read API has zero production callers; `HomeViewModelContinueWatching.kt:73` and `AndroidTvFeedCatalogService.kt:150` use the un-scoped `observeSnapshot()` and filter manually.
- **F-I-01 (P2):** `TraceRedactor` lags the actual auth surface — missing `simkl-api-key`, `trakt-api-key`, `simkl-client-id`, OAuth POST keys.
- **F-I-04 (P2):** No test asserts "bodies absent when `mode != INCLUDE_HTTP_BODIES_INTERNAL_ONLY`".
- **F-I-05 (P2):** Derived OkHttp clients via `okHttpClient.newBuilder()` are not pinned by an interceptor-survival test.
- **F-F-02 (P2):** Profile-switch rejection short-circuits before `ProfileBoundaryEnforcer.validateRequest`, so no `profile.boundary_check` trace event fires.
- **F-G-02 (P2):** `continue_watching.snapshot_read` has no test coverage.
- **F-G-03 (P2):** `snapshot_write.recordCount` excludes `traktUpNextItems`.
- **F-02-01 (Nit):** Cross-reference of F-I-02 (folded into Task 2 closure).

This change closes all 10.

## What Changes

### MODIFIED

- `TraceRedactor.redactedHeaders` adds `simkl-api-key`, `trakt-api-key`, `simkl-client-id`, `x-tvdb-apikey`. `redactedJsonKeys` adds `code`, `client_id` (F-I-01).
- `ContinueWatchingSnapshotService.recordCount` calculations include `traktUpNextItems.size` at all 3 sites (F-G-03).
- `HomeViewModelContinueWatching` and `AndroidTvFeedCatalogService` route CW reads through `observeContinueWatching(activeProfileId)` (F-G-01).
- `ProfileManager.setActiveProfile(...)` routes the playback-active rejection through `ProfileBoundaryEnforcer.assertCanSwitchProfile(...)` so a `profile.boundary_check` event fires (F-F-02).
- `app/build.gradle.kts` `generateTraceValidatorAudit` filter includes `RuntimeTraceValidatorRealEmissionTest` and the new `RuntimeTraceValidatorLocalizationPlanRuleTest` (F-I-03).

### ADDED

- `ProfileBoundaryEnforcer.assertCanSwitchProfile(activeProfileId, targetProfileId, hasActivePlaybackOwner)` — single method that emits `profile.boundary_check` and throws on FAIL (F-F-02).
- New regression tests for F-I-01, F-I-04, F-I-05, F-F-02, F-G-02, F-G-03, F-G-01 (×2), F-I-02 (×1).

## Impact

- Affected specs: `integration-runtime`.
- Affected code: 5 production files + 9 new test files + 1 build script.
- Trace bundles: profile-switch rejections now emit `profile.boundary_check` (F-F-02). Internal-build trace bundles redact more secrets (F-I-01). Continue-watching counts are consistent across Trakt and non-Trakt rails (F-G-03).
- Regression coverage gains: body-gating contract pinned (F-I-04), derived-client interceptors pinned (F-I-05), audit task catches real-emission schema drift (F-I-03).
- No new dependencies.
