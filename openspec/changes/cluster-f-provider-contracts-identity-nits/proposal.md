# Change: Cluster F Provider Contracts and Identity Nits

## Why

The architecture audit (`review-dossier/09-known-gaps.md`) identifies 10 cluster-F findings — the final cluster — across two lanes:

**Lane B — Metadata router (5 findings):**
- **F-B-01 (P2):** PREVIEW path in `MetadataRouterFacade` builds a `ResolvedMetadataDocument` with `fieldOwners = emptyMap()`, bypassing `FieldResolver`. Validators relying on `fieldOwners` see empty provenance.
- **F-B-02 (P2):** `MetaDetailsViewModel` and `HomeProviderLocalizedMetadataOverlay` construct `FieldResolver()` / `ProviderPlanRunner(emptySet())` directly — fallback paths that produce non-functional facades when Hilt fails. Silent failure mode.
- **F-B-05 (Nit):** `FieldResolver.emitFieldSelected` uses `primary.provider.name` as `contentId` in `metadata.field_selected` events. Trace bundles can't be filtered by content.
- **F-B-06 (P2):** `MetadataIdentityResolver` returns unresolved on null lookup but writes nothing to `IdMappingStore`. Every detail load for tmdb-as-series / tvdb-as-movie conflicts re-runs the failed lookup. Negative-cache infra (`IdMappingSource.NEGATIVE`, `NEGATIVE_TTL_MS = 30 days`) exists but is unused.
- **F-B-07 (Nit):** `MetadataRequestNormalizer` silently coerces `ContentType.TV` → `MediaKind.SERIES`. No trace event; debugging a TV-specific contract violation is opaque.

**Lane C — Provider contracts (5 findings):**
- **F-C-02 (P2):** ~50 production sites pass string literals to `apiShapeId` instead of constants from `*ApiShapes`. Audits under-report Trakt and Simkl footprint by ~70%.
- **F-C-03 (P2):** ID prefix parser only knows `tmdb:` / `tvdb:` / `kitsu:`. `mal:31964` from a Stremio addon → silent empty candidate.
- **F-C-04 (P2):** RPDB and Top Posters are wrapped in `IntegrationRuntime` but not registered as metadata adapters; `PosterRatingsUrlResolver.apply(...)` rewrites `Meta.poster` directly outside `FieldResolver`. Trace contract `SecondaryDoesNotOverwritePrimary` cannot validate poster overrides.
- **F-C-05 (Nit):** Premium poster cache keys use `apiKey.hashCode()` — JVM-instance-stable but not cross-JVM-stable. Cache keys can shift across process restarts.
- **F-C-06 (Nit):** Trakt account-scoped cache keys for global content (trending/popular/recommended) include `profile:N:` prefix. Two profiles fetching the same global rail trigger two cache misses.

This change closes all 10 and brings the cluster-by-cluster audit remediation effort to 100%.

## What Changes

### MODIFIED

- `MetadataRouterFacade.resolveRequest` PREVIEW branch routes through `fieldResolver.resolveWithPreview(preview, primary = null, secondary = emptyList())` so `fieldOwners` is non-empty (F-B-01).
- `FieldResolver.resolve(...)` and `resolveWithPreview(...)` accept a `requestContentId: String? = null` parameter; the existing `traceContentId` derivation falls back to provider name only if `requestContentId` is null. The two `MetadataRouterFacade` call sites pass `request.contentId` (F-B-05).
- `MetadataIdentityResolver.resolve(...)` reads existing NEGATIVE mapping at top; on null lookup result, persists a NEGATIVE `IdMapping` (F-B-06).
- `MetadataRequestNormalizer.toMetadataMediaKind()` for `ContentType.TV` emits a `metadata.normalizer_warning` trace event with reason `"TV_TYPE_COERCED_TO_SERIES"` (F-B-07).
- `MetaDetailsViewModel.kt` — delete `defaultMetadataRouterFacadeForManualConstruction()` helper; `metadataRouterFacade` is constructor-injected only (F-B-02 part 1).
- `HomeProviderLocalizedMetadataOverlay.kt` — replace `runCatching { metadataRouterFacade }.getOrNull()` with direct property read; let exceptions surface (F-B-02 part 2).
- `TraktIntegrationProvider.kt` (~45 sites), `SimklIntegrationProvider.kt` (5 sites), `TvdbIntegrationProvider.kt` (5 sites) — replace literal `apiShapeId = "..."` with property references on the matching `*ApiShapes` registry; add the missing constants (F-C-02).
- `MetadataProviderTargetIds.kt` — add `mal()`, `anilist()`, `anidb()`, `imdb()` parser functions (F-C-03).
- `MetadataModels.MetadataPrimaryProvider` enum — add `RPDB`, `TOP_POSTERS` entries (F-C-04 part 1).
- `PosterRatingsUrlResolver.kt:127,151` — replace `apiKey.hashCode()` with `stableHashHex8(apiKey)` (SHA-256 truncated to 8 hex chars; deterministic across JVM runs) (F-C-05).
- `TraktIntegrationProvider.kt` — split account-scope vs global-content cache keying. Trending/popular/recommended/calendar use new `globalContentCacheKey(logicalKey)` (no profile prefix); user-scoped endpoints (watched/history/playback/scrobble) keep `accountCacheKey(...)` (F-C-06).

### ADDED

- `RpdbMetadataProviderAdapter` and `TopPostersMetadataProviderAdapter` registered under `MetadataPrimaryProvider.RPDB` / `TOP_POSTERS`. Each produces a `MetadataCandidate` with `ResolvedField.POSTER` so `FieldResolver` merges premium artwork via the canonical path (F-C-04 part 2/3).
- `FieldResolverPreviewProvenanceTest` (F-B-01).
- `FieldResolverInjectionContractTest` (F-B-02 — bans `FieldResolver()` / `ProviderPlanRunner(emptySet())` outside `*test*`).
- `FieldResolverContentIdInTraceTest` (F-B-05).
- `MetadataIdentityResolverNegativeCacheTest` (F-B-06).
- `MetadataRequestNormalizerTvWarningTest` (F-B-07).
- `IntegrationApiShapeRegistryCoverageTest` (F-C-02 — bans `apiShapeId = "..."` literals).
- `MetadataProviderTargetIdsAnimePrefixTest` (F-C-03).
- `PremiumPosterAdapterRegistrationTest` (F-C-04).
- `PosterCacheKeyStableHashTest` (F-C-05).
- `TraktGlobalContentCacheKeyTest` (F-C-06).

### REMOVED

- `MetadataRouterFacade.HomeDisplayMetadata.toResolvedDocument()` private helper (only caller was the now-replaced PREVIEW branch).
- `MetaDetailsViewModel.defaultMetadataRouterFacadeForManualConstruction()` private helper.

## Impact

- Affected specs: `integration-runtime`.
- Affected code: 12 production files modified + 2 new production files + 10 new test files.
- Behavior changes:
  - PREVIEW responses now carry real provenance — downstream validators see `fieldOwners` populated (F-B-01).
  - Failed `MetadataRouterFacade` injection now fails fast instead of swallowing into a non-functional fallback (F-B-02).
  - `metadata.field_selected` events carry the real request `contentId` (F-B-05).
  - Failed identity lookups are negative-cached for 30 days (F-B-06) — second attempt for the same id within 30 days short-circuits without network call.
  - `ContentType.TV` requests emit a trace warning (F-B-07).
  - Audit reports now see ~50 previously-invisible Trakt/Simkl/Tvdb shapes (F-C-02).
  - `mal:` / `anilist:` / `anidb:` / `imdb:` / `tt` IDs parse correctly (F-C-03).
  - Premium poster overrides emit `metadata.field_selected` events (F-C-04).
  - Cache keys for premium posters are stable across JVM restarts (F-C-05).
  - Trakt global rails (trending/popular/recommended/calendar) share a single cache entry across profiles (F-C-06) — drops cache miss rate when multiple profiles browse home.
- No new dependencies. No new trace event types beyond `metadata.normalizer_warning`. No persistent schema changes (F-B-06 reuses existing `IdMappingStore` schema).
