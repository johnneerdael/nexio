## ADDED Requirements

### Requirement: ProviderPlanExecutor appends RPDB and TOP_POSTERS poster steps for detail-depth plans

`ProviderPlanExecutor.buildPlan(route, depth)` MUST append `PosterApiShapes.RPDB_POSTER_TEMPLATE` and `PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE` steps to plans built for `MetadataPrimaryProvider.TMDB`, `TVDB`, or `KITSU` when the depth is `MetadataDepth.DETAIL_CORE`, `DETAIL_MEDIA`, or `DETAIL_SECONDARY`. The append is unconditional — the corresponding adapter short-circuits at execute time when the user hasn't configured that poster provider.

#### Scenario: TMDB DETAIL_CORE plan includes both poster steps

- **GIVEN** `route.provider = TMDB`, `route.mediaKind = MOVIE`, `depth = DETAIL_CORE`
- **WHEN** `ProviderPlanExecutor.buildPlan(route, depth)` runs
- **THEN** the returned plan's `steps` includes one step with `apiShapeId = PosterApiShapes.RPDB_POSTER_TEMPLATE` and `provider = MetadataPrimaryProvider.RPDB`
- **AND** one step with `apiShapeId = PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE` and `provider = MetadataPrimaryProvider.TOP_POSTERS`
- **AND** both steps have `role = ProviderPlanRole.ARTWORK`

#### Scenario: SEASON plan does not include poster steps

- **GIVEN** `route.provider = TMDB`, `route.mediaKind = SERIES`, `depth = SEASON`, `route.seasonNumber = 1`
- **WHEN** `ProviderPlanExecutor.buildPlan(route, depth)` runs
- **THEN** the returned plan's `steps` does NOT include any step with `apiShapeId = PosterApiShapes.RPDB_POSTER_TEMPLATE` or `TOP_POSTERS_POSTER_TEMPLATE`

### Requirement: Premium poster audit harness uses real adapter output

`MetadataAuditRunner.runAuditScenario(...)` MUST NOT synthesize `FieldSelectedEvent`s for premium-poster scenarios. The runner's adapter set MUST include `RpdbMetadataProviderAdapter` and `TopPostersMetadataProviderAdapter` (constructed with a stub `PosterRatingsUrlResolver` that returns the scenario's `premiumArtworkProvider` when configured). The premium-poster `FieldSelectedEvent` MUST originate from the adapter's `MetadataCandidate` flowing through `FieldResolver`.

#### Scenario: Premium-poster scenario emits adapter-driven field_selected event

- **GIVEN** a `MetadataAuditScenario` with `premiumArtworkProvider = "RPDB"` and the audit runner's stub `PosterRatingsUrlResolver` configured to return RPDB as active
- **WHEN** the runner builds and runs the plan for the scenario
- **THEN** the recorded `FieldSelectedEvent` for `field = "poster"` has `selectedProvider = "RPDB"`
- **AND** `sourceRole = "ARTWORK"`
- **AND** the event was NOT synthesized by `MetadataAuditRunner` — it flowed through `FieldResolver` from `RpdbMetadataProviderAdapter.execute(...)`
