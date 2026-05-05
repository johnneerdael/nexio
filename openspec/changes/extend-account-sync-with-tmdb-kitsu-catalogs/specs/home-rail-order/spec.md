## ADDED Requirements

### Requirement: Account Sync Writes Through To HomeRailOrderStore

When account-config sync applies a provider catalog section (Trakt, SIMKL, MDBList, TMDB, or Kitsu) that changes order or enabled state, the system SHALL call `HomeRailOrderStore.reorderProviderKeys(family, providerKeys, ACCOUNT_SYNC)` and/or `HomeRailOrderStore.setEnabled(key, enabled, ACCOUNT_SYNC)` so that Modern Home updates immediately without restart.

#### Scenario: Sync-driven order change updates Modern Home immediately
- **GIVEN** Modern Home is rendering with TMDB rails in order `[tmdb_popular_movies, tmdb_top_rated_movies]`
- **WHEN** account-config sync applies a payload setting TMDB `catalogOrder` to `[tmdb_top_rated_movies, tmdb_popular_movies]`
- **THEN** `HomeRailOrderStore.reorderProviderKeys(TMDB, [tmdb_top_rated_movies, tmdb_popular_movies], ACCOUNT_SYNC)` is called
- **AND** Modern Home rerenders with `tmdb_top_rated_movies` before `tmdb_popular_movies` without an app restart
- **AND** the `home.rail_order_reconciled` diagnostics event records `lastMutationSource = ACCOUNT_SYNC`

#### Scenario: Sync-driven enable change adds the rail immediately
- **GIVEN** rail `kitsu_trending_anime` is currently disabled on the target
- **WHEN** account-config sync applies a payload that includes `kitsu_trending_anime` in Kitsu `enabled`
- **THEN** the Kitsu provider preference reflects the new enabled state
- **AND** `EffectiveHomeRailOrder.visibleKeys` contains `kitsu_trending_anime` at its saved or default position
- **AND** Modern Home renders the rail without restart, using cached content as fallback when present
