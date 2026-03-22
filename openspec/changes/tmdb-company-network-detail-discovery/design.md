## Context
The detail screen already enriches titles with TMDB production companies and networks, and those items are shown as TV-remote focusable cards. The current model only keeps display fields, so the UI has no stable organization identifier to navigate with, and there is no TMDB-backed organization detail flow equivalent to the existing actor detail screen.

## Goals / Non-Goals
- Goals:
  - Make detail-page production companies and networks navigable.
  - Show organization metadata and a TMDB-filtered list of related titles.
  - Route movie companies to movie discover, TV companies to TV discover, and TV networks to TV network discover.
- Non-Goals:
  - General text search for networks.
  - Merging TMDB organization discovery into addon-backed Search/Discover.
  - Multi-company or multi-network filter composition.

## Decisions
- Decision: add a dedicated TMDB organization detail route and screen.
  - Alternatives considered: reuse addon-backed discover UI. Rejected because it is a different data source and state model.
- Decision: extend `MetaCompany` to retain TMDB id and organization kind.
  - Alternatives considered: infer type from section title only. Rejected because navigation should be data-driven and stable.
- Decision: add a dedicated `TmdbOrganizationService` instead of overloading `TmdbMetadataService`.
  - Alternatives considered: keep all TMDB detail/discovery logic in `TmdbMetadataService`. Rejected because organization discovery is a separate responsibility from title/person enrichment.

## Risks / Trade-offs
- New TMDB requests add some latency when opening organization detail.
  - Mitigation: keep first version minimal and fetch detail plus discover in parallel.
- Organization cards may still be missing IDs in edge cases.
  - Mitigation: preserve click affordance only when TMDB id is present and no-op safely otherwise.

## Migration Plan
1. Add TMDB ids to enriched production companies and networks.
2. Add organization detail/discover TMDB endpoints and mapping.
3. Add navigation route and dedicated screen.
4. Wire detail-page cards to the new route by title type.

## Open Questions
- None for v1.
