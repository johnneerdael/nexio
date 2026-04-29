# Lane C — Provider Contracts and API Shapes

**Review SHA:** `774a540f8`
**Reviewer:** Architecture-review agent
**Date:** 2026-04-29

---

## 1. Scope

This lane covers the provider-adapter layer and the API-shape policy registry:

- All `*IntegrationProvider.kt` files under `data/integration/<provider>/`
- `IntegrationApiShapes.kt` — the policy-registry objects for all 13 providers
- `MetadataProviderTargetIds` — ID prefix parsing (`tmdb:`, `tvdb:`, `kitsu:`, `mal:`, `anilist:`, `anidb:`, `imdb:`/`tt`)
- `MetadataProviderAdapter` implementations: `TmdbMetadataProviderAdapter`, `TvdbMetadataProviderAdapter`, `KitsuMetadataProviderAdapter`, `RpdbMetadataProviderAdapter`, `TopPostersMetadataProviderAdapter`, and the supporting organization/person/trailer adapters
- `MetadataPrimaryProvider` enum and `MetadataProviderAdapterShapeRegistry`
- Architecture pins: `IntegrationApiShapeRegistryCoverageTest`, `MetadataProviderTargetIdsAnimePrefixTest`, `PremiumPosterAdapterRegistrationTest`

Cluster F tasks F-C-02 through F-C-06 landed prior to this review SHA.

---

## 2. Key Files Inspected

| File | Role |
|------|------|
| `core/integration/IntegrationApiShapes.kt` | Policy-registry objects (182 `const val` entries across 13 provider objects) |
| `core/metadata/router/MetadataProviderAdapterShapeRegistry.kt` | Canonical set of shapes that the adapter dispatcher recognizes |
| `core/metadata/router/MetadataModels.kt` | `MetadataPrimaryProvider` enum; includes `RPDB`, `TOP_POSTERS` |
| `core/metadata/router/MetadataRouter.kt` | Route-decision logic including `providerNativeOrConflict` |
| `core/metadata/router/MetadataIdentityResolver.kt` | Cross-provider ID resolution with trace emission |
| `core/metadata/router/ProviderPlanExecutor.kt` | Converts route+depth into `ProviderPlanStep` list |
| `data/integration/metadata/MetadataProviderTargetIds.kt` | Prefix-parsing helpers |
| `data/integration/metadata/TmdbMetadataProviderAdapter.kt` | TMDB adapter |
| `data/integration/metadata/TvdbMetadataProviderAdapter.kt` | TVDB adapter |
| `data/integration/metadata/KitsuMetadataProviderAdapter.kt` | Kitsu adapter |
| `data/integration/posters/RpdbMetadataProviderAdapter.kt` | RPDB poster adapter |
| `data/integration/posters/TopPostersMetadataProviderAdapter.kt` | Top Posters adapter |
| `core/di/MetadataExecutionModule.kt` | Hilt `@Binds @IntoSet` registrations for all adapters |
| `data/integration/trakt/TraktIntegrationProvider.kt` | Trakt provider with F-C-06 global-content cache keys |
| `data/integration/tmdb/TmdbIntegrationProvider.kt` | TMDB provider; image language and cache-key analysis |
| `core/poster/PosterRatingsUrlResolver.kt` | `stableHashHex8` stable hash (F-C-05) |
| `data/repository/{Kitsu,RealDebrid,Simkl}AuthService.kt` | Stage-2 auth-service carve-outs |
| `test/architecture/IntegrationApiShapeRegistryCoverageTest.kt` | F-C-02 pin |
| `test/data/integration/metadata/MetadataProviderTargetIdsAnimePrefixTest.kt` | F-C-03 pin |
| `test/data/integration/posters/PremiumPosterAdapterRegistrationTest.kt` | F-C-04 pin |

---

## 3. Cluster F Status Verification

### F-C-02 — `apiShapeId` literal migration

Pin at `IntegrationApiShapeRegistryCoverageTest` scans all production `.kt` files for two patterns:
- Named-arg form: `apiShapeId = "..."`
- Positional form: `callAuthenticated("..."` / `callPublic("..."`

Verified: no matches found in production code. The pin is sound and currently green. All 182 shape constants are referenced via their object qualifiers (`TraktApiShapes.WATCHED`, etc.). The pin does not cover `ProviderPlanStep(apiShapeId = ...)` construction sites — however those sites only appear in `ProviderPlanExecutor.kt` which itself only receives constants forwarded from the `step()` helper, which receives `String` from callers that always pass `*ApiShapes.*` references. No gap.

### F-C-03 — `MetadataProviderTargetIds` anime prefix extension

`mal()`, `anilist()`, `anidb()`, and `imdb()` are present. The `imdb()` parser correctly handles both bare `tt0111161` and `imdb:tt0111161` forms. The `providerValue()` helper used by `mal`, `anilist`, `anidb` correctly rejects cross-prefix values (returns `null`). Pin `MetadataProviderTargetIdsAnimePrefixTest` covers the key cases.

### F-C-04 — `RpdbMetadataProviderAdapter` + `TopPostersMetadataProviderAdapter`

Both classes exist, both implement `MetadataProviderAdapter`, both are bound via `@Binds @IntoSet` in `MetadataExecutionModule`. `MetadataPrimaryProvider` enum includes `RPDB` and `TOP_POSTERS`. `MetadataProviderAdapterShapeRegistry.all` includes `PosterApiShapes.RPDB_POSTER_TEMPLATE` and `PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE`. Pin `PremiumPosterAdapterRegistrationTest` is satisfied.

### F-C-05 — `stableHashHex8` in `PosterRatingsUrlResolver`

`stableHashHex8(apiKey)` is implemented correctly using SHA-256 first 4 bytes as 8 hex chars. Applied in both `buildRpdbPosterUrl` (cache key: `rpdb:$idType:${id.value}:poster-default:${stableHashHex8(apiKey)}`) and `buildTopPostersUrl` (cache key: `topposters:${id.type.name.lowercase()}:${id.value}:${stableHashHex8(apiKey)}`). No remaining `hashCode()` calls visible.

### F-C-06 — Trakt global-content cache keys

Six discovery functions (`fetchCalendarShows`, `fetchTrendingMovies`, `fetchTrendingShows`, `fetchPopularMovies`, `fetchPopularShows`, `fetchRecommendations`) use `globalContentCacheKey("global:provider:TRAKT:$logicalKey")`. `scope` and `profileContext` remain `accountScope(session)` / `profileContext(session)` as documented — this is intentional because `ProfileExecutionContext` requires a positive `profileId` and a global singleton does not exist.

**However:** `fetchPopularLists` (line 956) uses `accountCacheKey` instead of `globalContentCacheKey`. The Trakt `/lists/popular` endpoint is fully global (no user data) — analogous to trending/popular/recommended. This was not addressed in F-C-06. See C-03 below.

---

## 4. Provider ID Parsing — Coverage by Adapter

| Provider | Parser | Called by adapter? | New prefixes accessible? |
|----------|--------|--------------------|--------------------------|
| TMDB | `tmdbInt()` — strips `tmdb:`, parses int | Yes, in `TmdbMetadataProviderAdapter.execute()` | N/A (pre-existing) |
| TVDB | `tvdbInt()` — strips `tvdb:`, parses int | Yes, in `TvdbMetadataProviderAdapter.execute()` | N/A |
| Kitsu | `kitsu()` — strips `kitsu:` | Yes, in `KitsuMetadataProviderAdapter.execute()` | N/A |
| MAL | `mal()` — strips `mal:` | **No adapter** — MAL routes through `animePrefixMapped()` to Kitsu; no direct `MetadataProviderAdapter` exists for MAL. Parser added in F-C-03 but adapters don't call it. | Correctly reached via router mapping, not adapters. |
| AniList | `anilist()` | Same as MAL — router-mapped to Kitsu | Correct by design |
| AniDB | `anidb()` | Same | Correct by design |
| IMDb | `imdb()` — handles `tt...` and `imdb:tt...` | No direct adapter — IMDb routes through `imdbMappedOrFallback()` → Kitsu or fallback. `MetadataPrimaryProvider.IMDB` has no `ProviderPlanExecutor` branch (throws). | Correct by design |
| RPDB | N/A — poster URL, not ID-based | `RpdbMetadataProviderAdapter` uses `PosterRatingsUrlResolver` which has its own `parseContentId()` with full prefix support | OK |
| TOP_POSTERS | Same | `TopPostersMetadataProviderAdapter` same path | OK |

Conclusion: adapters that call `MetadataProviderTargetIds` parsers (TMDB/TVDB/Kitsu) are correct. MAL/AniList/AniDB/IMDb parsers are exercised only by tests and implicitly by `MetadataRouter`'s `animePrefixMapped()` / `imdbMappedOrFallback()` — but no adapter calls them, which is the correct design. No silent `emptyCandidate` regression.

---

## 5. API Shape Policy Registry — Constant Inventory

Total constants in `IntegrationApiShapes.kt`: **182**

Of these, **7 constants have zero production callers** (verified by full-source scan):

| Constant | Verdict |
|----------|---------|
| `KitsuApiShapes.ADVANCED_DETAIL` | Dead — no caller anywhere in production |
| `TmdbApiShapes.COLLECTION` | Dead — `loadMovieCollection()` bypasses `IntegrationRuntime` via a bare `loadResponse()` call; the constant is never passed as `apiShapeId` |
| `TraktApiShapes.COLLECTION_MOVIES` | Dead — no Trakt collection endpoint is implemented |
| `TraktApiShapes.COLLECTION_SHOWS` | Dead — same |
| `YouTubeTrailerApiShapes.DEVICE_CODE` | Dead — the YouTube integration does not implement OAuth device flow |
| `YouTubeTrailerApiShapes.TOKEN` | Dead — same |
| `MDBListApiShapes.USER` | Dead — the MDB List user-profile endpoint is not called |

The 7 dead constants are split between "planned but not yet implemented" (Trakt collection, YouTube OAuth, MDB List user) and "implementation bypassed the registry" (`TmdbApiShapes.COLLECTION` — the `loadMovieCollection()` function skips `IntegrationRuntime` entirely). See C-01 and C-02 below.

---

## 6. Adapter Boundary and Field-Mixing Audit

**TMDB adapter**: injects only `TmdbIntegrationProvider` and `TraceMetadataEvents`. Never reads from TVDB. No field pre-mixing.

**TVDB adapter**: injects only `TvdbIntegrationProvider` and `TraceMetadataEvents`. Never reads from TMDB. No field pre-mixing.

**Kitsu adapter**: injects only `KitsuIntegrationProvider` and `TraceMetadataEvents`. No cross-provider reads.

**RPDB and Top Posters adapters**: inject only `PosterRatingsUrlResolver`. No metadata field reads.

**FieldResolver**: Cross-provider fallback for missing localized fields is the responsibility of `LocalizationResolver` and `FieldResolver`, not adapters. The adapters correctly return `emptyCandidate(provider)` for unsupported shapes and let the planner pipeline handle field merging.

**Provider-native conflict flow**: When an addon sends `tmdb:550` for a series, `MetadataRouter.providerNativeOrConflict()` detects the mismatch (TMDB native but `ContentType.SERIES`), adds a `ROUTING_ID_TYPE_CONFLICT` trace entry, and sets `requiresIdentityResolution = true`. `MetadataIdentityResolver.resolve()` then emits an `identity_resolution` trace event and either resolves to a TVDB ID or falls back. This is fully observable via trace. No silent rewrite.

**TMDB image cache key and user language**: The enrichment cache key is `tmdb:$tmdbType:$tmdbId:$normalizedLanguage:enrichment:$providerToken`. The `normalizedLanguage` is the user's display language (e.g., `nl-NL`). The `includeImageLanguage` request parameter is built as `"$baseLang,$fullLangTag,en,null"` — this is by design (localization-aware image priority). The consequence is that the enrichment payload (which embeds poster/backdrop/logo paths) is cached per-language, causing cache fragmentation across profiles with different display languages. This is **expected behaviour** given the localization policy; there is no separate language-invariant image cache. No bug, but reviewers should be aware of the cache multiplication factor.

---

## 7. Findings

### C-01 — `TmdbApiShapes.COLLECTION` is a dead constant AND the collection endpoint bypasses the runtime

**Severity: P1**

`TmdbApiShapes.COLLECTION = "tmdb.collection"` has no production caller. The collection fetch (`loadMovieCollection()` in `TmdbIntegrationProvider`) calls `loadResponse()` directly — a private helper that calls the Retrofit API and returns an `IntegrationLoadResult` without going through `IntegrationRuntime`. This means:
1. The collection endpoint has no backoff management, no single-flight deduplication, no audit trail, no cache ownership tracking.
2. The F-C-02 architecture pin can never catch a future violation for this shape because the shape is never passed as `apiShapeId` anywhere.

**Evidence:** `TmdbIntegrationProvider.kt` line 1351–1362: `loadMovieCollection()` uses `loadResponse(request = "tmdb_collection", ...)` not `runtime.get(IntegrationSpec(apiShapeId = TmdbApiShapes.COLLECTION, ...))`.

**Recommendation:** Migrate `loadMovieCollection()` to use `runtime.get(IntegrationSpec(apiShapeId = TmdbApiShapes.COLLECTION, ...))` via the `tmdbRuntimeGet()` pattern used by neighbouring functions (`fetchCompanyDetails`, `discoverMoviesByCompany`, etc.).

---

### C-02 — Six dead constants for unimplemented features; four of them have no tracking comment

**Severity: P2**

`KitsuApiShapes.ADVANCED_DETAIL`, `TraktApiShapes.COLLECTION_MOVIES`, `TraktApiShapes.COLLECTION_SHOWS`, `YouTubeTrailerApiShapes.DEVICE_CODE`, `YouTubeTrailerApiShapes.TOKEN`, and `MDBListApiShapes.USER` are unused. The F-C-02 architecture pin does not check whether a constant has at least one caller — it only checks that callers do not use string literals. Dead constants in the policy registry create noise and may mislead future developers into believing endpoints are implemented.

**Evidence:** Python-assisted full-source scan of `app/src/main/java/` confirms zero `*ApiShapes.*CONSTANT` references for the six constants listed above.

**Recommendation:** For constants representing genuinely unimplemented-but-planned endpoints, add a `// TODO(<ticket>): unimplemented` comment. For constants with no planned use, remove them. Add a companion architecture pin that verifies every registered constant has at least one production caller, or document the exception policy explicitly.

---

### C-03 — `fetchPopularLists` uses `accountCacheKey` for a global endpoint

**Severity: P2**

`TraktIntegrationProvider.fetchPopularLists()` (line 956) calls `accountCacheKey(session, "trakt:popular:lists:...")`. The underlying Trakt endpoint is `GET /lists/popular` — a fully public, non-personalized ranking. This is structurally identical to `fetchTrendingMovies`, `fetchTrendingShows`, `fetchPopularMovies`, `fetchPopularShows`, and `fetchRecommendations`, all of which were migrated to `globalContentCacheKey` in F-C-06.

**Evidence:** `TraktIntegrationProvider.kt` line 963–965 uses `accountCacheKey`; line 1287 defines `globalContentCacheKey`. The Trakt API endpoint `GET /lists/popular` requires no OAuth token in practice (publicly accessible), though the implementation passes `authorization` anyway.

**Recommendation:** Change `fetchPopularLists` cache key to `globalContentCacheKey("trakt:popular:lists:page:$page:limit:$limit")`. This is a one-line change consistent with the F-C-06 pattern.

---

### C-04 — `TraktIntegrationProvider` review (comments) endpoints use `accountCacheKey` — may be correct but worth documenting

**Severity: Nit**

`fetchMovieCommentsPage()` and `fetchShowCommentsPage()` use `accountCacheKey`. The Trakt `/movies/{id}/comments` and `/shows/{id}/comments` endpoints are public and not user-specific. However, the current design intentionally passes an authorization header (rate-limiting benefit), so the profile binding may be acceptable. The inconsistency with `fetchTrendingMovies` (global) is not documented.

**Evidence:** `TraktIntegrationProvider.kt` lines 1093–1095 and 1145–1147.

**Recommendation:** Add a code comment explaining why these review endpoints use `accountCacheKey` rather than `globalContentCacheKey` (e.g., "authorization header increases rate limit; caching per-account avoids sharing between sessions with different rate-limit tiers").

---

### C-05 — Section-separator comment block in `TraktIntegrationProvider` is a fragile test dependency

**Severity: Nit**

The section-separator comment at lines 952–954:
```
// -----------------------------------------------------------------------------------------
// User-specific list / review endpoints (account-scoped cache keys)
// -----------------------------------------------------------------------------------------
```
was added in F-C-06 Task 26 specifically to prevent a 2500-char source grep window from bleeding from `fetchRecommendations` (which uses `globalContentCacheKey`) into `fetchPopularLists` (which uses `accountCacheKey`). This is a physical-text-layout dependency: if a developer reorganizes the function order or removes the comment, the test logic may produce false negatives.

**Evidence:** Noted in the briefing; confirmed by the comment's physical position between `fetchRecommendations` (global) and `fetchPopularLists` (account).

**Recommendation:** Replace the source-grep window approach in the test with a structured test that programmatically parses cache key construction patterns (e.g., compile-time constant maps or an explicit allowlist of functions expected to use each cache key type). Alternatively, fix C-03 (migrate `fetchPopularLists` to `globalContentCacheKey`), which eliminates the need for the separator entirely.

---

### C-06 — Auth-service carve-outs in `IntegrationBoundaryTest` lack migration path documentation

**Severity: P2**

`IntegrationBoundaryTest` (`allowedPaths`) explicitly exempts three auth services from the Retrofit-usage boundary:
- `data/repository/KitsuAuthService.kt` — calls `KitsuAuthApi.token()` directly
- `data/repository/RealDebridAuthService.kt` — delegates to `RealDebridAuthIntegrationProvider` (one level of indirection; the provider uses `IntegrationRuntime`)
- `data/repository/SimklAuthService.kt` — delegates to `SimklAuthIntegrationProvider`

`KitsuAuthService` is the only one that makes a raw Retrofit call outside any `IntegrationRuntime` context. `RealDebridAuthService` and `SimklAuthService` delegate to integration providers, so their carve-outs in the test are wider than necessary.

The carve-outs have no associated comment, ticket reference, or policy statement explaining whether they are:
(a) permanent exceptions (OAuth token exchanges are exempt from runtime backoff/caching because they are stateful auth operations), or
(b) temporary tech-debt pending future migration.

**Evidence:** `IntegrationBoundaryTest.kt` lines 16–21; `KitsuAuthService.kt` lines 39–40 call `api.token(...)` directly; no documentation present.

**Recommendation:** Add a comment block in `IntegrationBoundaryTest` policy section (or a separate `AuthServiceExemptionPolicy.md` in `/openspec/`) documenting the rationale. If the intent is permanent exemption for token endpoints, codify this as a policy. If migration is planned, open a tracking ticket and reference it.

---

### C-07 — `MetadataAdapterUnknownPrefixTraceTest` is absent

**Severity: P2**

The briefing references `MetadataAdapterUnknownPrefixTraceTest` as a Lane C architecture pin. This test does not exist in the repository. The equivalent coverage — verifying that adapters return `emptyCandidate(provider)` for unrecognized prefixes rather than crashing or returning stale data — is not pinned by any test. The `MetadataProviderTargetIdsAnimePrefixTest` covers the *parser* returning `null` for mismatched prefixes, but no test verifies that adapters correctly propagate a `null` parse result into `emptyCandidate(provider)`.

**Evidence:** `find ... -name "MetadataAdapterUnknownPrefixTraceTest.kt"` returns no result. The briefing listed this as an existing pin. Each adapter (`TmdbMetadataProviderAdapter`, `TvdbMetadataProviderAdapter`, `KitsuMetadataProviderAdapter`) has a guard `?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))` which is correct but untested.

**Recommendation:** Create `MetadataAdapterUnknownPrefixTraceTest` to verify that supplying a mismatched prefix (e.g., `tvdb:123` as the TMDB target ID) causes `TmdbMetadataProviderAdapter.execute()` to return `emptyCandidate(TMDB)` without throwing, and that no fields leak from a previous step.

---

### C-08 — `TmdbApiShapes.SEASON_VIDEOS` appears in `MetadataProviderAdapterShapeRegistry` but not in `TmdbMetadataProviderAdapter.tmdbShapes`

**Severity: P2**

`MetadataProviderAdapterShapeRegistry.all` (line 16) includes `TmdbApiShapes.SEASON_VIDEOS`. `TmdbMetadataProviderAdapter.tmdbShapes` (the `companion object` set used by `supports()`) does not include `SEASON_VIDEOS`. This means the dispatcher's registry claims it handles `SEASON_VIDEOS` but the adapter will return `false` from `supports()` for any plan step with that shape, resulting in a silent no-op.

**Evidence:**
- `MetadataProviderAdapterShapeRegistry.kt` line 16: `TmdbApiShapes.SEASON_VIDEOS`
- `TmdbMetadataProviderAdapter.kt` lines 228–238: `tmdbShapes` set contains 9 shapes; `SEASON_VIDEOS` is absent
- `ProviderPlanExecutor.kt` does not build a `SEASON_VIDEOS` step (only `TV_VIDEOS` and `MOVIE_VIDEOS` appear), so this is likely dead registry entry rather than a missing handler — but that means `SEASON_VIDEOS` should also be removed from the registry to prevent confusion.

**Recommendation:** Remove `TmdbApiShapes.SEASON_VIDEOS` from `MetadataProviderAdapterShapeRegistry.all` if no plan step is ever generated for it. If it is intended for future use, add a `TODO` comment and remove it from the registry until implementation is complete.

---

### C-09 — TMDB enrichment image cache is fragmented by display language (informational)

**Severity: Nit (design observation)**

The TMDB enrichment cache key is `tmdb:$tmdbType:$tmdbId:$normalizedLanguage:enrichment:$providerToken`. The `normalizedLanguage` comes from the user's display language (e.g., `nl-NL`). Poster, backdrop, and logo URLs are embedded in the serialized `TmdbEnrichment` payload. The `includeImageLanguage` TMDB request parameter is built as `"$baseLang,$fullLangTag,en,null"`, so images are fetched with a user-language-first priority.

This means two profiles with different display languages will each independently fetch and cache the same movie's metadata (including images). For a 10-language userbase, every TMDB item occupies up to 10 independent cache entries. This is the correct design given the localization policy, but operators should be aware of the storage multiplication factor when projecting cache growth.

No action required unless a language-invariant image CDN cache is desired as a separate optimization.

---

## Summary Table

| ID | Title | Severity | File(s) |
|----|-------|----------|---------|
| C-01 | `TmdbApiShapes.COLLECTION` dead constant + collection endpoint bypasses runtime | P1 | `TmdbIntegrationProvider.kt` L1351–1362; `IntegrationApiShapes.kt` L125 |
| C-02 | Six dead constants for unimplemented features | P2 | `IntegrationApiShapes.kt` |
| C-03 | `fetchPopularLists` uses `accountCacheKey` for global endpoint | P2 | `TraktIntegrationProvider.kt` L956–993 |
| C-04 | Comments endpoints cache key rationale undocumented | Nit | `TraktIntegrationProvider.kt` L1085–1187 |
| C-05 | Section-separator comment as fragile test dependency | Nit | `TraktIntegrationProvider.kt` L952–954 |
| C-06 | Auth-service carve-outs lack migration path documentation | P2 | `IntegrationBoundaryTest.kt`; `KitsuAuthService.kt` |
| C-07 | `MetadataAdapterUnknownPrefixTraceTest` is absent | P2 | (missing test) |
| C-08 | `SEASON_VIDEOS` in shape registry but not in adapter's `supports()` set | P2 | `MetadataProviderAdapterShapeRegistry.kt` L16; `TmdbMetadataProviderAdapter.kt` L228–238 |
| C-09 | TMDB enrichment cache fragmented by display language | Nit | `TmdbIntegrationProvider.kt` L283 |

**Finding counts: P0: 0 / P1: 1 / P2: 5 / Nit: 3**

---

## Overall Lane Health

The core commitments of Cluster F (literal elimination, anime prefix parsers, premium poster registration, stable hash, global content cache keys) are correctly implemented and pinned. The lane's primary structural risk is **C-01**: the `loadMovieCollection()` function bypasses `IntegrationRuntime` entirely, making it invisible to backoff, audit, and the shape registry — a regression from the F-C-02 sweep's intent. Secondary concerns are the six dead constants (C-02), the missed global cache key for Trakt popular lists (C-03), the missing `MetadataAdapterUnknownPrefixTraceTest` (C-07), and the shape registry / adapter `supports()` inconsistency for `SEASON_VIDEOS` (C-08).
