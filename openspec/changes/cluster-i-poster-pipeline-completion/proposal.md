## Why

Cluster H deferred a single P2 finding (F2-T13-A) with this rationale:

> Premium poster audit golden requires upstream pipeline work — `ProviderPlanExecutor.buildPlan()` does not yet append RPDB/TOP_POSTERS plan steps. The step-based poster pipeline is not fully wired.

Cluster F (F-C-04) added `RpdbMetadataProviderAdapter` + `TopPostersMetadataProviderAdapter` and registered them via Hilt `@Binds @IntoSet` into the `Set<MetadataProviderAdapter>` consumed by `ProviderPlanRunner`. The adapters correctly:
- Implement `supports(step)` matching `PosterApiShapes.RPDB_POSTER_TEMPLATE` / `TOP_POSTERS_POSTER_TEMPLATE`
- Implement `execute(route, step)` returning a `MetadataCandidate(ResolvedField.POSTER, FieldOwner.ARTWORK)` when the user has configured that poster provider, OR an empty candidate otherwise
- Emit `selectedProvider = "RPDB"` / `"TOP_POSTERS"` (uppercase, per cluster H Task 51 / F2-T13-C)

But `ProviderPlanExecutor.buildPlan()` never appends the poster steps to any primary-provider plan (TMDB / TVDB / KITSU). So the adapters are wired but starve — they never see a step to execute. The audit harness (`MetadataAuditRunner`) currently synthesizes the `FieldSelectedEvent` from `scenario.premiumArtworkProvider` directly because the real pipeline produces nothing.

This change closes F2-T13-A by:
1. Appending `RPDB_POSTER_TEMPLATE` + `TOP_POSTERS_POSTER_TEMPLATE` steps to TMDB/TVDB/KITSU plans for `DETAIL_CORE`, `DETAIL_MEDIA`, and `DETAIL_SECONDARY` depths (the depths that need posters).
2. Removing the `isPremiumPoster` synthesis branch from `MetadataAuditRunner` so the audit golden tests assert the REAL adapter-emitted event.

The append is unconditional. The adapter already short-circuits to no-candidate when the user hasn't configured RPDB/TOP_POSTERS, so `ProviderPlanExecutor` stays purely structural — no need to inject `PosterRatingsUrlResolver` into the executor.

## What Changes

### MODIFIED

- `ProviderPlanExecutor.tmdbSteps`, `tvdbSteps`, `kitsuSteps` — append poster steps to plans at `DETAIL_CORE` / `DETAIL_MEDIA` / `DETAIL_SECONDARY`. Skip `SEASON` (season-level posters are out of scope), `PREVIEW` (no router work), `PLAYER` (no metadata).
- `MetadataAuditRunner.runAuditScenario` — drop the synthesized `isPremiumPoster` branch in `selectedFields`. Wire `RpdbMetadataProviderAdapter` + `TopPostersMetadataProviderAdapter` into the runner's adapter set with a stubbed `PosterRatingsUrlResolver` that returns `scenario.premiumArtworkProvider` when configured.

### ADDED

- `ProviderPlanExecutorPosterStepsTest` — pins poster step append for TMDB/TVDB/KITSU at the 3 relevant depths.

### REMOVED

- `MetadataAuditRunner.kt:205-225` — the `isPremiumPoster` synthesis branch (the synthesized event is replaced by real adapter output).

## Impact

- Affected specs: `integration-runtime`.
- Affected code: 1 production file modified + 1 test file modified + 1 new test file.
- Behavior changes:
  - When `MetadataDepth.DETAIL_CORE`/`DETAIL_MEDIA`/`DETAIL_SECONDARY` plans run for TMDB/TVDB/KITSU primary providers, `ProviderPlanRunner` will now invoke `RpdbMetadataProviderAdapter` and `TopPostersMetadataProviderAdapter` (in addition to the primary-provider steps). When the user has configured neither poster provider, both adapters produce no candidates — net behavior unchanged.
  - When the user HAS configured RPDB or TOP_POSTERS, posters now flow through the canonical `FieldResolver` merge (with `FieldOwner.ARTWORK`) instead of being applied via the legacy `PosterRatingsUrlResolver.apply()` direct-rewrite path. `metadata.field_selected` events emit naturally with `selectedProvider = "RPDB"` / `"TOP_POSTERS"`.
  - Premium poster audit golden tests assert the REAL adapter event, not a synthesized one.
- No new dependencies. No new trace event types. No persistent schema changes.
