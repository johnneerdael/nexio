## ADDED Requirements

### Requirement: Sync Catalog Fields Use Nullable Presence Semantics

All catalog-section fields involved in Modern Home rail order or enabled state — `CatalogSyncSettings.home/trakt/simkl/mdblist/tmdb/kitsu` and the inner list fields `homeCatalogOrderKeys`, `disabledHomeCatalogKeys`, `heroCatalogKeys`, `catalogEnabledSet`, `catalogOrder` — SHALL be nullable. The apply path SHALL treat `null` as "field absent in this payload, do not change" and any non-null value (including an empty list) as "field present, apply as-is".

#### Scenario: Null section is not applied
- **GIVEN** the target's existing TMDB `catalogOrder` is `[tmdb_popular_movies, tmdb_top_rated_movies]`
- **WHEN** an account-config sync payload arrives with `catalogs.tmdb = null`
- **THEN** the target's TMDB `catalogOrder` is unchanged

#### Scenario: Null inner field is not applied
- **GIVEN** the target's existing TMDB `catalogOrder` is `[tmdb_popular_movies, tmdb_top_rated_movies]`
- **WHEN** an account-config sync payload arrives with `catalogs.tmdb` non-null but `catalogOrder = null`
- **THEN** the target's TMDB `catalogOrder` is unchanged

#### Scenario: Empty list is applied as intentional clear
- **GIVEN** the target's existing TMDB `catalogEnabledSet` is `[tmdb_popular_movies]`
- **WHEN** an account-config sync payload arrives with `catalogs.tmdb.catalogEnabledSet = []` (non-null, empty)
- **THEN** the target's TMDB `catalogEnabledSet` is cleared to empty

#### Scenario: Older payload with empty lists is treated as intentionally empty
- **GIVEN** an older client emits a payload with `catalogs.simkl.catalogOrder = []` (its data-class default at the time)
- **WHEN** the target applies the payload
- **THEN** the target's SIMKL `catalogOrder` is cleared to empty
- **AND** this is treated as the safe interpretation under the new rule, not as a presence error

### Requirement: TMDB Catalog Settings Sync

The system SHALL support a nullable `catalogs.tmdb` section in the account-config sync payload with nullable inner fields `catalogEnabledSet: List<String>?` and `catalogOrder: List<String>?`, mirroring the field-naming convention of the existing `SimklCatalogSyncSettings`. When `catalogs.tmdb` is non-null, pull-apply SHALL respect the inner null-vs-non-null presence semantics: write only the inner fields that are non-null, leaving null inner fields' target state unchanged. For any non-null `catalogOrder`, the apply path SHALL also call `HomeRailOrderStore.reorderProviderKeys(TMDB, providerKeys, ACCOUNT_SYNC)`.

#### Scenario: TMDB section is round-tripped through sync
- **GIVEN** TMDB `catalogOrder` on the source is `[tmdb_top_rated_movies, tmdb_popular_movies]` (non-null) and `catalogEnabledSet` is `[tmdb_top_rated_movies, tmdb_popular_movies]` (non-null)
- **WHEN** account-config sync emits and re-applies the payload on a target device
- **THEN** the target device's TMDB provider preference matches the source
- **AND** `HomeRailOrderStore.orderedKeys` reflects the TMDB family slice in the order `[tmdb_top_rated_movies, tmdb_popular_movies]`
- **AND** `lastMutationSource` is `ACCOUNT_SYNC` for the resulting state

#### Scenario: TMDB enable/disable propagates through sync
- **GIVEN** TMDB `catalogEnabledSet` on the source is `[tmdb_popular_movies]` (non-null, omits `tmdb_top_rated_movies`)
- **WHEN** account-config sync applies the payload
- **THEN** `tmdb_top_rated_movies` is disabled in the TMDB provider preference store
- **AND** Modern Home does not display `tmdb_top_rated_movies` after sync applies

### Requirement: Kitsu Catalog Settings Sync

The system SHALL support a nullable `catalogs.kitsu` section in the account-config sync payload with nullable inner fields `catalogEnabledSet: List<String>?` and `catalogOrder: List<String>?`. Pull-apply behavior is parallel to the TMDB section: respect null-vs-non-null presence semantics, write the Kitsu provider preference store, and call `HomeRailOrderStore.reorderProviderKeys(KITSU, providerKeys, ACCOUNT_SYNC)` for any non-null `catalogOrder`.

#### Scenario: Kitsu section is round-tripped through sync
- **GIVEN** Kitsu `catalogOrder` on the source is `[kitsu_trending_anime, kitsu_popular_anime]` (non-null)
- **WHEN** account-config sync emits and re-applies the payload on a target device
- **THEN** the target device's Kitsu provider preference matches the source
- **AND** `HomeRailOrderStore.orderedKeys` reflects the Kitsu family slice in the order `[kitsu_trending_anime, kitsu_popular_anime]`

### Requirement: Partial Sync Does Not Revert Home Order

A sync payload in which `catalogs.home` or `catalogs.home.homeCatalogOrderKeys` is null SHALL NOT clear or overwrite the existing `HomeRailOrderState.orderedKeys`. Provider-only sync sections SHALL update only the relevant family slice via `HomeRailOrderStore.reorderProviderKeys` and SHALL preserve the relative positions of all non-family keys.

#### Scenario: Provider-only sync preserves cross-provider positions
- **GIVEN** `HomeRailOrderState.orderedKeys` on the target is `[trakt_popular_movies, tmdb_popular_movies, simkl_tv_trending_today, tmdb_top_rated_movies]`
- **WHEN** the target receives a sync payload containing only `catalogs.tmdb` with `catalogOrder = [tmdb_top_rated_movies, tmdb_popular_movies]` (all other catalog sections null)
- **THEN** `HomeRailOrderState.orderedKeys` becomes `[trakt_popular_movies, tmdb_top_rated_movies, simkl_tv_trending_today, tmdb_popular_movies]`
- **AND** the position of `trakt_popular_movies` and `simkl_tv_trending_today` relative to other non-TMDB keys is preserved

#### Scenario: Sync with null home section does not erase existing global order
- **GIVEN** `HomeRailOrderState.orderedKeys` on the target is `[A, B, C]`
- **WHEN** the target receives a sync payload with `catalogs.tmdb` and `catalogs.kitsu` non-null and `catalogs.home = null`
- **THEN** `HomeRailOrderState.orderedKeys` is not cleared
- **AND** any non-TMDB and non-Kitsu keys remain in their positions

### Requirement: Account-Config Sync Contract Version 9

The system SHALL emit `ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 9` from current clients and SHALL retain acceptance of prior contract versions (including 8). Version 9 SHALL include `catalogs.tmdb` and `catalogs.kitsu` sections when those provider settings are non-empty and SHALL omit them otherwise.

#### Scenario: Version 9 payload includes TMDB and Kitsu sections
- **GIVEN** the source has non-empty TMDB and Kitsu catalog settings
- **WHEN** the device emits an account-config sync payload
- **THEN** the payload's contract version is 9
- **AND** `catalogs.tmdb` and `catalogs.kitsu` sections are present

#### Scenario: Older payload without TMDB/Kitsu fields is accepted
- **GIVEN** an older client emits a version-8 payload without TMDB or Kitsu sections
- **WHEN** the server or a current client applies the payload
- **THEN** the payload is accepted
- **AND** existing TMDB and Kitsu provider preferences on the target are not modified
