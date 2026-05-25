# Tasks

- [ ] Add an injectable or test-controllable clock path for addon catalog freshness decisions.
- [ ] Add a 24-hour fresh TTL constant for repository-owned addon catalog rows.
- [ ] Update `CatalogRepositoryImpl.refreshCatalogToDisk()` to read `CatalogDiskCacheStore` before network and skip network when the cached row is fresh.
- [ ] Preserve current stale-refresh behavior: stale cache triggers network, successful network writes disk and memory cache.
- [ ] Add stale-on-error fallback: if network refresh fails and a cached row exists, return the stale row.
- [ ] Keep `getCatalogCachedFirst()` behavior compatible with existing cached-first emissions and network refresh expectations.
- [ ] Add repository tests for fresh cache skip, stale cache refresh, stale-on-error fallback, and cache-key isolation.
- [ ] Add or update coordinator tests proving first-paint publish and provider hydration ordering are unchanged.
- [ ] Run focused repository/coordinator tests and `openspec validate add-addon-catalog-daily-freshness --strict`.

