# Lane B — MetadataRouter / ProviderPlanRunner / FieldResolver

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Phase:** 5
- **Owner task:** Task 26
- **Status:** PLACEHOLDER

<remainder filled in by the owner task>

## Pre-staged findings (from Task 23 red-flag scan)

- **F-RF-01** (cross-ref **F-03-02**): Legacy `metadataSecondaryRepository.fetchTmdbEnrichment(...)` is invoked from `MetaDetailsViewModel.kt:1391` and `MetaDetailsViewModel.kt:1406`, bypassing `MetadataRouterFacade`. Detected by Red flag 2 (legacy router after facade). The facade is the supported entry point; TMDB enrichment must route through `metadataRouterFacade.fetch*` so the route decision, identity resolution, and trace events are emitted consistently.
- **Note on F-04-02**: Red flag 13 found `emitResolverSchedule` IS wired (caller: `ResolverOrchestrator.kt:55`). If F-04-02 predicted zero callers, revisit it during Lane B authoring — the current evidence shows the emission site exists.
