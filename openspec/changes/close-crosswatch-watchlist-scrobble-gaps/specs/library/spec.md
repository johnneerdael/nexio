## ADDED Requirements

### Requirement: Unified Watchlist publishes a resolved-display surface

Unified Watchlist MUST publish rows to `ResolvedDisplaySurfaceRepository.UNIFIED_WATCHLIST_SURFACE_KEY` for the active profile before the Library UI projects visible rows.

#### Scenario: Trakt-only watchlist item has hydrated metadata

- **GIVEN** a Trakt watchlist membership with a TMDb or IMDb identifier
- **WHEN** the Library screen observes Unified Watchlist rows
- **THEN** the app publishes a provider-neutral `ResolvedDisplayItem` through `UNIFIED_WATCHLIST_SURFACE_KEY`
- **AND** the UI card renders from the resolved display item, not raw provider artwork fallback logic.

#### Scenario: Same item exists in Trakt and Simkl

- **GIVEN** Trakt and Simkl source items match by strong ID
- **WHEN** memberships are reduced
- **THEN** exactly one canonical row is published
- **AND** source membership remains available in model data only.

### Requirement: Unified Watchlist remains library-only

Unified Watchlist MUST NOT show continue-watching progress, next episode prompts, or provider badges.

#### Scenario: Series is watchlisted

- **GIVEN** a series membership with episode source data
- **WHEN** the row renders in Library
- **THEN** the card represents the show/series
- **AND** no season/episode progress text is shown.
