# home-startup-refresh Spec Delta

## ADDED Requirements

### Requirement: Addon catalog refresh uses daily app-owned freshness

Addon catalog refresh SHALL use the app-owned catalog disk cache to avoid refetching the same addon catalog row while it is fresh.

#### Scenario: Fresh addon catalog row skips network refresh

- **GIVEN** `CatalogDiskCacheStore` contains a row for an addon catalog cache key
- **AND** the row was updated less than 24 hours ago
- **WHEN** `CatalogRepositoryImpl.refreshCatalogToDisk()` is called for the same addon base URL, addon id, catalog type, catalog id, skip value, extra args, and provider cache token
- **THEN** the cached row is returned
- **AND** `AddonCatalogIntegrationProvider.getCatalog()` is not called
- **AND** the row is not rewritten to disk only to update its timestamp.

#### Scenario: Stale addon catalog row refreshes from network

- **GIVEN** `CatalogDiskCacheStore` contains a row for an addon catalog cache key
- **AND** the row was updated at least 24 hours ago
- **WHEN** `CatalogRepositoryImpl.refreshCatalogToDisk()` is called for that key
- **THEN** the repository calls `AddonCatalogIntegrationProvider.getCatalog()`
- **AND** a successful network response is adapted with the existing catalog mapping behavior
- **AND** the refreshed row is written to `CatalogDiskCacheStore`
- **AND** the refreshed row is returned.

#### Scenario: Missing addon catalog row refreshes from network

- **GIVEN** `CatalogDiskCacheStore` has no row for an addon catalog cache key
- **WHEN** `CatalogRepositoryImpl.refreshCatalogToDisk()` is called for that key
- **THEN** the repository calls `AddonCatalogIntegrationProvider.getCatalog()`
- **AND** a successful network response is adapted, written to `CatalogDiskCacheStore`, and returned.

#### Scenario: Stale addon catalog network failure keeps Home renderable

- **GIVEN** `CatalogDiskCacheStore` contains a stale row for an addon catalog cache key
- **AND** `AddonCatalogIntegrationProvider.getCatalog()` fails for that key
- **WHEN** `CatalogRepositoryImpl.refreshCatalogToDisk()` handles the failure
- **THEN** the stale cached row is returned as a successful refresh result
- **AND** the stale row is not rewritten as if it were newly refreshed
- **AND** Home catalog publish and hydration ordering can continue with the cached row.

#### Scenario: App freshness policy remains above OkHttp cache

- **GIVEN** an addon catalog row is missing or stale
- **WHEN** the repository intentionally performs an addon catalog network refresh
- **THEN** addon catalog GETs still use the app-level cache-bypass transport policy
- **AND** OkHttp disk cache does not decide whether the response is fresh.

### Requirement: Addon catalog daily freshness preserves Home publish behavior

Daily freshness gating SHALL NOT change the existing Home catalog publish lifecycle.

#### Scenario: Fresh cached row follows existing first-paint and hydration publish order

- **GIVEN** a fresh cached addon catalog row is returned by `CatalogRepositoryImpl.refreshCatalogToDisk()`
- **WHEN** `HomeCatalogRefreshCoordinator.refreshSerially()` receives that row
- **THEN** the coordinator publishes the first-paint row using the existing `onCatalogReady` flow
- **AND** provider metadata hydration runs after raw catalog rows are batched
- **AND** any hydrated overlay publish uses the existing diff and merge behavior.

