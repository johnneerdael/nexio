# Trace 03 — Detail Core Path

**Review SHA:** `774a540f8`
**Date:** 2026-04-29
**Dossier:** review-dossier-2

---

## 1. Entry Point

`MetaDetailsViewModel` is a `@HiltViewModel` annotated class.

```
app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
class MetaDetailsViewModel @Inject constructor(
    ...
    private val metadataRouterFacade: MetadataRouterFacade,
    private val metadataSecondaryRepository: MetadataSecondaryRepository,
    ...
)
```

`init {}` calls `loadMeta()` (line 238), which collects from `MetaRepository` and on success calls `applyMetaWithEnrichment(meta)` (line 757). `applyMetaWithEnrichment` calls `enrichMeta(expandedMeta, includeEpisodeMetadata = false)` (line 763), which is where the DETAIL_CORE pipeline begins.

---

## 2. Complete Call Path

### Step 1 — `enrichMeta(meta)` → DETAIL_CORE request construction

Located at `MetaDetailsViewModel.kt:1171`.

Two DETAIL_CORE requests are constructed here:

**Path A — TV/anime content (line 1186–1203):**

```kotlin
metadataRouterFacade.fetchTvEnrichment(
    metadataRequest = MetadataRequest(
        contentId = meta.id,
        contentType = tmdbContentType,
        sourceContext = MetadataSourceContext(itemType = tmdbContentType.toApiString()),
        language = tvdbLanguage,
        depth = MetadataDepth.DETAIL_CORE   // line 1193
    ),
    tvRequest = TvMetadataRequest(...)
)
```

**Path B — TMDB supplemental enrichment for TV (line 1231–1241) or movie (line 1253–1263):**

```kotlin
metadataRouterFacade.fetchTmdbEnrichment(
    metadataRequest = MetadataRequest(
        contentId = "tmdb:$tmdbId",
        contentType = tmdbContentType,
        sourceContext = MetadataSourceContext(itemType = tmdbContentType.toApiString()),
        language = tvdbLanguage,
        depth = MetadataDepth.DETAIL_CORE   // line 1237 / 1259
    ),
    tmdbId = tmdbId,
    contentType = tmdbContentType
)
```

### Step 2 — `MetadataRouterFacade.fetchTvEnrichment` (line 156)

```kotlin
suspend fun fetchTvEnrichment(
    metadataRequest: MetadataRequest,
    tvRequest: TvMetadataRequest
): TvMetadataDecision<TvMetadataEnrichment> {
    val result = resolveRequest(metadataRequest)
    return TvMetadataDecision(
        provider = result.route?.provider.toTvProvider(),
        reason = TvMetadataDecisionReason.TVDB_SUCCESS,
        value = result.resolvedDocument.toTvMetadataEnrichment(),
        diagnostics = emptyList()
    )
}
```

`resolveRequest` is called unconditionally; the returned `ResolvedMetadataDocument` is consumed (converted to `TvMetadataEnrichment`). This is **not** the discard pattern.

### Step 3 — `MetadataRouterFacade.resolveRequest(request)` (line 51)

Full canonical pipeline:

```
resolverOrchestrator.schedule(DETAIL_CORE)
  → schedules localResolvers = [ADDON_DISPLAY, RATING, ARTWORK]
  → schedules networkResolvers = []

routeRequest(request)
  → MetadataRouter.route(request)        // emits metadata.route_decision
  → MetadataIdentityResolver.resolve()   // cross-id resolution if needed

providerPlanExecutor.buildPlan(route, DETAIL_CORE)
  → tmdbSteps: [MOVIE_CORE | TV_CORE]  (PRIMARY_CORE only at DETAIL_CORE)
  → tvdbSteps: [SERIES_EXTENDED]       (PRIMARY_CORE only at DETAIL_CORE)

providerPlanRunner.run(plan)
  → emits metadata.provider_plan
  → per step: adapter.execute(route, step)
  → returns ProviderPlanRunResult

fieldResolver.resolveWithPreview(preview, primary, secondary, requestContentId)
  → emits metadata.field_selected per resolved field

networkResolvers.forEach → (empty for DETAIL_CORE, no dispatch)
```

### Step 4 — `MetadataRouter.route(request)` (line 19)

Normalizes request via `MetadataRequestNormalizer.normalize()`, parses content id scheme, and dispatches:

- `tmdb:NNNN` + MOVIE → `providerNativeOrConflict` → TMDB provider, `PROVIDER_NATIVE_DIRECT`
- `tmdb:NNNN` + SERIES/TV → `providerNativeOrConflict` → falls back to TVDB (conflict), `requiresIdentityResolution = true`
- `tvdb:NNNN` + SERIES → TVDB provider, `PROVIDER_NATIVE_DIRECT`
- IMDB id + SERIES → `imdbMappedOrFallback` → TVDB, `requiresIdentityResolution = true`
- Kitsu prefix → KITSU directly
- No recognized prefix, SERIES → `fallbackByItemType` → TVDB

`metadata.route_decision` is emitted inside the private `route(normalized, ...)` function via `traceEvents.emitRouteDecision(...)`.

### Step 5 — `MetadataIdentityResolver.resolve(route)` (line 23)

If `route.targetIdRequiresIdentityResolution == false`, returned immediately.

Otherwise performs cross-id lookup (tmdb→tvdb, imdb→tvdb, tvdb→tmdb) via the injected `Lookup` implementation (`RuntimeMetadataIdentityLookup`). Persists negative mappings for 30-day TTL on miss.

### Step 6 — `ProviderPlanExecutor.buildPlan(route, DETAIL_CORE)` (line 12)

At `DETAIL_CORE`, per provider:

| Provider | Steps added |
|----------|-------------|
| TMDB (movie) | `TmdbApiShapes.MOVIE_CORE` (PRIMARY_CORE) |
| TMDB (series) | `TmdbApiShapes.TV_CORE` (PRIMARY_CORE) |
| TVDB (series) | `TvdbApiShapes.SERIES_EXTENDED` (PRIMARY_CORE) |
| Kitsu (anime) | `KitsuApiShapes.ANIME_CORE` (PRIMARY_CORE) |

No MEDIA, SECONDARY, or SEASON steps are added at DETAIL_CORE.

### Step 7 — `ProviderPlanRunner.run(plan)` (line 14)

Emits `metadata.provider_plan` trace event listing all steps.

For each step, finds the registered `MetadataProviderAdapter` from the DI-bound `Set<MetadataProviderAdapter>` and calls `adapter.execute(route, step)`.

For TMDB MOVIE_CORE / TV_CORE:
- `TmdbMetadataProviderAdapter.execute` → `TmdbIntegrationProvider.fetchMovieCore` / `fetchTvCore`
- Both use `IntegrationCachePolicy.CacheFirst(ttlMs = 7 days, staleAfterExpiryMs = 30 days)`
- Warm-hit: `DefaultIntegrationRuntime.executeCacheFirst` returns cached value, no network call

For TVDB SERIES_EXTENDED:
- `TvdbMetadataProviderAdapter.execute` → `TvdbIntegrationProvider.fetchSeriesExtendedCached`
- Uses `IntegrationCachePolicy.CacheFirst(ttlMs = 7 days, staleAfterExpiryMs = 30 days)`
- Warm-hit: returns from cache without network

### Step 8 — `FieldResolver.resolveWithPreview(preview, primary, secondary, contentId)` (line 30)

Applies preview candidate first (addon metadata from `MetadataSourceContext`), then primary overwrites ADDON_PREVIEW fields with canonical PRIMARY fields, secondary fills any remaining gaps.

Emits `metadata.field_selected` for each resolved field via `buildDocument` → `traceEvents.emitFieldSelected(...)`.

At DETAIL_CORE, fields owned by PRIMARY (from TMDB/TVDB/Kitsu core step):
- `CANONICAL_ID`, `TITLE`, `OVERVIEW`, `POSTER`, `BACKDROP`, `LOGO`, `RATING`, `RUNTIME`

Returns `ResolvedMetadataDocument` with all DETAIL_CORE fields owned and `fieldOwners` map fully populated.

### Step 9 — TMDB dual-path enrichment via `fetchTmdbEnrichment` (line 179)

**For TV content**, after `fetchTvEnrichment` returns, `enrichMeta` checks whether TMDB supplemental data is needed (`shouldSupplementTvdbDetailWithTmdb`). If so, calls `metadataRouterFacade.fetchTmdbEnrichment(...)`.

```kotlin
suspend fun fetchTmdbEnrichment(
    metadataRequest: MetadataRequest,
    tmdbId: String,
    contentType: ContentType
): TmdbEnrichment? {
    val repo = checkNotNull(metadataSecondaryRepository) { ... }
    resolveRequest(metadataRequest)           // fire trace events; result intentionally discarded
    return repo.fetchTmdbEnrichment(tmdbId, contentType)
}
```

`resolveRequest` fires `metadata.route_decision` + `metadata.field_selected` events. The resolved document is discarded by design — the TMDB carry-set inside `ResolvedMetadataDocument` is narrower than the 22-field `TmdbEnrichment` shape (director, writer, cast, companies, networks, collection, etc.) that `enrichMeta` depends on downstream. The KDoc on `fetchTmdbEnrichment` documents this explicitly.

`MetadataSecondaryRepository.fetchTmdbEnrichment` → `TmdbMetadataService.fetchEnrichment` → also uses `CacheFirst(ttlMs = 7 days, staleAfterExpiryMs = 30 days)`.

---

## 3. Verification Points

### F-B-02: `metadataRouterFacade` is Hilt-injected with no fallback

CONFIRMED. `MetaDetailsViewModel` constructor receives `metadataRouterFacade: MetadataRouterFacade` via `@Inject` (line 178). There is no `?: defaultMetadataRouterFacadeForManualConstruction()` fallback or any `runCatching { metadataRouterFacade }` null-guarding in the VM. Lane B finding B (F-B-02) is CLOSED; `FieldResolverInjectionContractTest` pins this.

### `resolveRequest(...)` — not direct provider calls

CONFIRMED. Both `fetchTvEnrichment` and `fetchTmdbEnrichment` on `MetadataRouterFacade` call `resolveRequest(metadataRequest)` internally. No direct provider calls (no `tvdbApi.getSeriesExtended(...)` or `tmdbApi.getMovie(...)`) exist in `MetaDetailsViewModel`. The VM never imports or injects `TvdbMetadataService`, `TmdbMetadataService`, or any integration provider directly for the DETAIL_CORE path.

### `metadata.route_decision` + `metadata.field_selected` events

CONFIRMED.

- `metadata.route_decision` is emitted by `MetadataRouter.route` → private `route(...)` function → `traceEvents.emitRouteDecision(...)` (line 265–275).
- `metadata.provider_plan` is emitted by `ProviderPlanRunner.run` (line 15–30).
- `metadata.field_selected` is emitted by `FieldResolver.buildDocument` → `traceEvents.emitFieldSelected(...)` (line 239) for each field in the resolved map.

Events fire on both the `fetchTvEnrichment` call (result consumed) and the `fetchTmdbEnrichment` call (result discarded by design for trace-only firing).

### TMDB enrichment dual-path — intentional and documented

CONFIRMED. `MetadataRouterFacade.fetchTmdbEnrichment` (line 168–191) has explicit KDoc:

> "Routes a TMDB enrichment fetch through the canonical resolve pipeline so that `metadata.route_decision` and `metadata.field_selected` trace events fire, then delegates the actual rich-shape data fetch to [MetadataSecondaryRepository]. The resolved document from [resolveRequest] is intentionally discarded — its TMDB carry-set is narrower than the 22-field [TmdbEnrichment] that downstream `enrichMeta(...)` sites depend on. Migrating naively to the resolved document would silently drop director/writer/cast/companies/networks/collection."

Lane B (B-08) flags this as an architectural debt marker (Nit), not a defect. The intentionality is machine-visible via the KDoc but has no lint/pin guard distinguishing it from an accidental discard.

### Cache hit on warm — CacheFirst policy

CONFIRMED at two levels:

1. **TVDB SERIES_EXTENDED:** `TvdbIntegrationProvider.fetchSeriesExtendedCached` (line 195–215) uses `IntegrationCachePolicy.CacheFirst(ttlMs = 7 days, staleAfterExpiryMs = 30 days)`.
2. **TMDB MOVIE_CORE / TV_CORE:** `TmdbIntegrationProvider.fetchMovieCore` / `fetchTvCore` (line 307–394) use `IntegrationCachePolicy.CacheFirst(ttlMs = 7 days, staleAfterExpiryMs = 30 days)`.
3. **TMDB enrichment (MetadataSecondaryRepository path):** `TmdbIntegrationProvider.fetchEnrichment` (line 280–304) uses `IntegrationCachePolicy.CacheFirst(ttlMs = 7 days, staleAfterExpiryMs = 30 days)`.

On warm: `DefaultIntegrationRuntime.executeCacheFirst` returns the cached value; no network call is made. The audit bundle report confirms 7 cache hits across 23 routed requests.

### F-04-01: DETAIL_MEDIA has no production caller for the "media data collapses into core" scenario

CONFIRMED with clarification. `DETAIL_MEDIA` is referenced in exactly one production call site in `MetaDetailsViewModel`:

```
MetaDetailsViewModel.kt:2563 — depth = MetadataDepth.DETAIL_MEDIA
```

This is inside `fetchTrailer(...)` (trailer resolution, not core metadata). It is not called during the DETAIL_CORE path. The DETAIL_CORE response does **not** collapse media data — trailer steps (`MOVIE_VIDEOS`, `TV_VIDEOS`) are only added by `ProviderPlanExecutor.tmdbSteps` when depth is `DETAIL_MEDIA` or `DETAIL_SECONDARY`. At `DETAIL_CORE`, no media data enters the resolved document.

F-04-01's concern ("DETAIL_MEDIA has no production caller") is partially correct: there is a production caller (`fetchTrailer`) but it arrives via `MetadataRouterFacade.fetchTrailer`, not a direct `MetadataRequest(depth = DETAIL_MEDIA)` in the VM. The detail core response is clean — no media data is included or "collapsed."

### `ProviderMetadataRouter` / `TvMetadataRouter` legacy status

CONFIRMED not in use on the detail path. `MetaDetailsViewModel` does not inject `ProviderMetadataRouter` or `TvMetadataRouter`. The old `ProviderMetadataRouter` interface has no non-test injection sites outside `TvMetadataRouter` itself and the `TvMetadataRouterBindingModule` binding. The detail screen is fully on the facade path.

---

## 4. Findings

### DC-01 (Nit) — `fetchTmdbEnrichment` dual-path has no lint guard distinguishing intentional from accidental discard

**Severity:** Nit (same as Lane B B-08, surfaced in this path context)

The pattern `resolveRequest(metadataRequest) /* result discarded */; return repo.fetchTmdbEnrichment(...)` fires trace events correctly and is KDoc-documented as intentional. However, there is no naming convention or annotation that makes this machine-checkable. A future maintainer could add a similar discard without realizing it. Lane B B-08 recommends a naming convention (`resolveRequestForTraceOnly()`) or a local annotation.

**Cross-reference:** B-08

---

### DC-02 (Nit) — `ResolverOrchestrator.schedule(DETAIL_CORE)` schedules RATING and ARTWORK as local resolvers but the adapter set does not include artwork or rating adapters in the execution plan

**Severity:** Nit / documentation gap

`ResolverOrchestrator` schedules `RATING` and `ARTWORK` as local resolvers at DETAIL_CORE. These are labelled "local" (not network), meaning they should be handled locally by `FieldResolver` selection rather than via the `networkResolvers.forEach` dispatch loop in `resolveRequest`. However, no code in `resolveRequest` explicitly "runs" local resolvers — the naming implies they are exercised implicitly by the `FieldResolver.resolveWithPreview` call (which picks the best available field from all candidates). There is no dispatch block for `ResolverType.RATING` or `ResolverType.ARTWORK` anywhere in `resolveRequest`. This is architecturally correct (local resolvers operate through field ownership rules in `FieldResolver`, not explicit network dispatch), but the separation between "local" and "network" resolvers is not explained in code comments — only in the enum definition. A comment in `ResolverOrchestrator` or `resolveRequest` explaining the distinction would prevent future confusion.

---

### DC-03 (Info) — `fetchTvEnrichment` returns a `ResolvedMetadataDocument`-derived `TvMetadataEnrichment` with a narrower field set than `fetchTmdbEnrichment`

**Severity:** Informational

`MetadataRouterFacade.fetchTvEnrichment` converts the `ResolvedMetadataDocument` to `TvMetadataEnrichment` via `toTvMetadataEnrichment()` (line 532–542), which maps only: `canonicalId→seriesTvdbId`, `title`, `overview`, `backdrop`, `logo`, `poster`, `rating`, `runtimeMinutes`. Fields like `castMembers`, `genres`, `ageRating`, `countries`, `networks`, `productionCompanies`, `remoteIds`, `releaseInfo`, and `seasonOrderContext` that are populated by the legacy `TvMetadataRouter` path are not in the `ResolvedMetadataDocument` model. These fields do arrive at `enrichMeta` via the TVDB series extended response through the adapter, but they are not routed through the `ResolvedMetadataDocument` — they are populated by `TvMetadataEnrichment` properties that the adapter places in the `MetadataCandidate.fields` map and which `FieldResolver` does not carry (there are no `ResolvedField.CAST`, `ResolvedField.AGE_RATING` etc. entries set by the TVDB adapter at DETAIL_CORE).

This means `fetchTvEnrichment`'s `ResolvedMetadataDocument` only carries the 7 display fields. The rich TVDB metadata (cast, genres, networks, etc.) arrives indirectly — not through `ResolvedMetadataDocument.fieldOwners` — which means those fields do not appear in `metadata.field_selected` events. This is an observability gap: TVDB-sourced cast/genres/networks are not traceable via the resolver pipeline at DETAIL_CORE.

---

### DC-04 (Info) — `TvMetadataRouter` is still bound in DI but has no production injection sites on the detail path

**Severity:** Informational / housekeeping

`TvMetadataRouterBindingModule` still binds `TvMetadataRouter` as `ProviderMetadataRouter`. `ProviderMetadataRouter` itself has no injection sites in production code outside `TvMetadataRouter.kt` (the interface is orphaned). The `TvMetadataRouter` class remains fully functional but is unused by `MetaDetailsViewModel`. This is legacy code that can be removed in a housekeeping sweep after confirming no test-only injection sites depend on it.

---

## 5. Path Diagram

```
MetaDetailsViewModel.init
  └─ loadMeta()
       └─ [MetaRepository] → Meta
            └─ applyMetaWithEnrichment(meta)
                 └─ enrichMeta(meta)
                      │
                      ├─[TV/Anime] metadataRouterFacade.fetchTvEnrichment(
                      │                  MetadataRequest(depth=DETAIL_CORE))
                      │     └─ resolveRequest(request)
                      │           ├─ ResolverOrchestrator.schedule(DETAIL_CORE)
                      │           │     → localResolvers=[ADDON_DISPLAY, RATING, ARTWORK]
                      │           │     → networkResolvers=[]
                      │           ├─ MetadataRouter.route(request)
                      │           │     → emits metadata.route_decision
                      │           ├─ MetadataIdentityResolver.resolve(route)
                      │           ├─ ProviderPlanExecutor.buildPlan(route, DETAIL_CORE)
                      │           │     → [TMDB] MOVIE_CORE | TV_CORE (PRIMARY_CORE)
                      │           │     → [TVDB] SERIES_EXTENDED (PRIMARY_CORE)
                      │           │     → [Kitsu] ANIME_CORE (PRIMARY_CORE)
                      │           ├─ ProviderPlanRunner.run(plan)
                      │           │     → emits metadata.provider_plan
                      │           │     → adapter.execute() per step
                      │           │     → CacheFirst(7d/30d) warm hit
                      │           └─ FieldResolver.resolveWithPreview(preview, primary, [])
                      │                 → emits metadata.field_selected per field
                      │                 → ResolvedMetadataDocument (7 display fields)
                      │     → toTvMetadataEnrichment() → TvMetadataDecision<TvMetadataEnrichment>
                      │
                      └─[TMDB suppl.] metadataRouterFacade.fetchTmdbEnrichment(
                                          MetadataRequest(depth=DETAIL_CORE))
                             ├─ resolveRequest(request)  ← fires trace events, doc DISCARDED (by design)
                             └─ MetadataSecondaryRepository.fetchTmdbEnrichment(tmdbId, contentType)
                                   → TmdbMetadataService.fetchEnrichment()
                                   → CacheFirst(7d/30d) warm hit
                                   → TmdbEnrichment (22-field rich shape)
```

---

## 6. Cross-Reference to Lane Findings

| Finding | Lane | Notes |
|---------|------|-------|
| F-B-02 CLOSED — no fallback in `MetadataRouterFacade` injection | B | Confirmed at VM constructor level; `FieldResolverInjectionContractTest` pins |
| B-08 — intentional discard pattern in `fetchTmdbEnrichment` | B | DC-01 in this trace; dual-path is documented and correct |
| F-04-04 — ARTWORK only at DETAIL_CORE, not DETAIL_MEDIA | B / ResolverOrchestrator | Confirmed: `ResolverOrchestrator` does not schedule ARTWORK for DETAIL_MEDIA |
| F-04-01 — DETAIL_MEDIA no production caller for core data | B | DETAIL_MEDIA only called via `fetchTrailer`; detail core does not collapse media |
| CacheFirst policy at provider adapters | D | Confirmed: TVDB SERIES_EXTENDED and TMDB MOVIE/TV_CORE both use CacheFirst(7d/30d) |
| E-localization — localization_plan emitted by TVDB/TMDB adapters before provider_plan | E | Confirmed: `TvdbMetadataProviderAdapter` and `TmdbMetadataProviderAdapter` both call `traceEvents.emitLocalizationPlan(...)` before candidate construction |
| TVDB cast/genres not in ResolvedMetadataDocument fieldOwners | B/DC-03 | Observability gap: TVDB-sourced credits/genres not traceable via field_selected |
