# Path 03 — Detail core

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Lane:** B (metadata router) + A (runtime) + I (trace mode)
- **Contract:** Opening a detail screen issues a DETAIL_CORE-depth metadata fetch through the canonical facade → router → plan → runtime → resolver chain, emitting the full event set, with TMDB-primary for movies and TVDB-primary for series (Kitsu via id mapping for anime).

## Chain

| # | Symbol | File:line | Expected | Observed |
|---|---|---|---|---|
| 1 | Detail screen open / nav arg | `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:265` | nav `itemId`/`itemType` flows into VM `init { loadMeta() }` | `loadMeta()` first goes through Stremio addon path (`metaRepository.getMetaFromAllAddons(...)` at lines 598/669), then once a `Meta` is in hand calls `applyMetaWithEnrichment` → `enrichMeta` for the canonical chain. The Stremio addon hop is *not* part of the canonical metadata chain; the DETAIL_CORE call is gated behind it. |
| 2 | `MetaDetailsViewModel.enrichMeta` → facade call | `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1347-1361` | calls facade with depth=DETAIL_CORE | Calls `metadataRouterFacade.fetchTvEnrichment(...)` with `MetadataRequest(... depth = MetadataDepth.DETAIL_CORE)` only when `isTvContent || hasAnimeId`. For pure-movie content this branch is `null` (line 1362-1364); the movie DETAIL_CORE path goes through `metadataSecondaryRepository.fetchTmdbEnrichment` (line 1406-1409) which **bypasses the canonical facade entirely** — F-03-02 below. |
| 3 | `MetadataRouterFacade.fetchTvEnrichment` | `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:69-80` | dispatches to router/plan/runtime via `resolveRequest` | Calls `resolveRequest(metadataRequest)` which hits the full chain, then converts the resolved document via `toTvMetadataEnrichment()` (lines 182-192). Branch at line 37 (`if (request.depth == MetadataDepth.PREVIEW)`) does NOT trigger for DETAIL_CORE — confirms B-Q3.1 is PREVIEW-only as Task 11 found. |
| 4 | `MetadataRouter.route` | `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouter.kt:17-50` | builds MetadataRoute; emits route_decision | Routes by id scheme (Kitsu/anime/IMDB/TMDB/TVDB). `route(...)` helper at line 246-279 emits `metadata.route_decision` via `traceEvents.emitRouteDecision(...)` at line 255. |
| 5 | `ProviderPlanExecutor.buildPlan` | `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt:14-58` | builds ProviderExecutionPlan for DETAIL_CORE | `MetadataRouterFacade.resolveRequest` at line 50 builds the plan; `DETAIL_MEDIA`/`DETAIL_SECONDARY` add extra steps but DETAIL_CORE uses the base plan. |
| 6 | `ProviderPlanRunner.run` | `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanRunner.kt:14-52` | emits provider_plan; runs adapters | Emits `metadata.provider_plan` at line 15 then dispatches each step to the matching `MetadataProviderAdapter` (line 33-37). |
| 7 | adapter execute | `app/src/main/java/com/nexio/tv/data/integration/metadata/TmdbMetadataProviderAdapter.kt:26-79` (TMDB) / `TvdbMetadataProviderAdapter` / `KitsuMetadataProviderAdapter` | TMDB/TVDB/Kitsu adapter | TMDB adapter calls `integrationProvider.fetchMovieCore`/`fetchTvCore` which routes through `IntegrationRuntime.get`. |
| 8 | `IntegrationRuntime.get` | `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt:117-161` | cache-first; emits operation_start + cache_decision | Emits `runtime.operation_start` at line 133, `runtime.operation_finish` at line 139; `executeCacheFirst` (line 191) emits `runtime.cache_decision` via `emitCacheDecision` (line 90-101). Called from `TmdbIntegrationProvider.fetchMovieCore:314` / `fetchTvCore:350`. |
| 9 | `FieldResolver.resolve` | `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt:19-98` | sole producer of ResolvedMetadataDocument | Called by `MetadataRouterFacade.resolveRequest` at line 52. Emits `metadata.field_selected` per field (line 74-82). |
| 10 | facade conversion | `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:182-192` | converts `ResolvedMetadataDocument` to `TvMetadataEnrichment`; does NOT bypass FieldResolver | `toTvMetadataEnrichment()` reads only fields already produced by `FieldResolver`; OK. |
| 11 | UI state update | `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1413-end of enrichMeta` then `applyMeta` (line 771-782) | renders detail screen | `_uiState.update { state -> state.withRefreshedMeta(meta) }` at line 775. |

## What does NOT happen on this path (verified)

- Direct `FieldResolver()` instantiation in UI: **VIOLATED** — see F-03-01.
- UI does NOT call `MetadataRouter` directly (only via the facade): confirmed.
- UI does NOT make direct provider HTTP calls on this branch: confirmed (TMDB calls go through `metadataSecondaryRepository` for movies — separate chain, see F-03-02).
- `MetadataRouterFacade.toResolvedDocument` (B-Q3.1) NOT triggered on DETAIL_CORE: confirmed at `MetadataRouterFacade.kt:37` (gated to PREVIEW).

## Trace event coverage

| Event | Emitted on this path? | File:line of emission site |
|---|---|---|
| metadata.route_decision | YES (when sink injected) | `MetadataRouter.kt:255` via `traceEvents.emitRouteDecision` |
| metadata.provider_plan | YES (when sink injected) | `ProviderPlanRunner.kt:15` via `traceEvents.emitProviderPlan` |
| runtime.operation_start | YES | `DefaultIntegrationRuntime.kt:133` |
| runtime.operation_finish | YES | `DefaultIntegrationRuntime.kt:139` |
| runtime.cache_decision | YES | `DefaultIntegrationRuntime.kt:90-101` (called from `executeCacheFirst` etc.) |
| metadata.field_selected (TITLE) | YES | `FieldResolver.kt:74-82` |
| metadata.field_selected (OVERVIEW) | YES | `FieldResolver.kt:74-82` |
| metadata.field_selected (POSTER) | YES | `FieldResolver.kt:74-82` |

Coverage **8/8** in principle, but see F-03-01: when the VM uses the manual-construction facade, `FieldResolver()` is built with a `NoopRuntimeTraceSink`, so `metadata.field_selected` events are silently dropped on this path. `MetadataRouter` and `ProviderPlanRunner` likewise default to noop sinks here.

## Verdict

**FAIL** — Two structural problems:

1. The DI-bypassing `defaultMetadataRouterFacadeForManualConstruction()` constructs the facade with `emptySet()` adapters and noop trace sinks, so the chain executes but produces no candidates and emits no observability when this default is used (B-Q3.2 / extends F-02-02 to detail).
2. Movie DETAIL_CORE skips the canonical facade entirely and calls `metadataSecondaryRepository.fetchTmdbEnrichment` directly, bypassing router/plan/resolver/trace.

## Findings

### F-03-01: `MetaDetailsViewModel` constructs `MetadataRouterFacade` with empty adapters and noop trace (P1)

- **Where:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:98-113` and `:203` (default-arg fallback).
- **What:** `defaultMetadataRouterFacadeForManualConstruction()` builds the facade with `ProviderPlanRunner(emptySet())` and `FieldResolver()` (the no-arg constructor that injects `NoopRuntimeTraceSink`). The VM uses this as a constructor default at line 203 (`metadataRouterFacade: MetadataRouterFacade = defaultMetadataRouterFacadeForManualConstruction()`).
- **Impact:** When Hilt injects the singleton facade everything works; but this default-arg path silently substitutes a non-functional facade if construction ever bypasses Hilt (tests, manual instantiation, future regressions). When invoked it would throw `MissingPlanStepAdapter` immediately, OR — worse — silently return an empty enrichment. This is the same anti-pattern called out as F-02-02 for `HomeProviderLocalizedMetadataOverlay` (`app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt:62-77`, also containing `FieldResolver()`).
- **B-Q3.2 status:** CONFIRMED. Two UI files instantiate `FieldResolver()` directly:
  - `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:112`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt:76`
- **Severity:** P1 (DI bypass + observability silently dropped).
- **Fix sketch:** Make `metadataRouterFacade` a non-defaulted `@Inject`-required constructor parameter. Delete `defaultMetadataRouterFacadeForManualConstruction` from both VM/overlay files. Tests should construct a real facade with fakes or use Hilt test modules.

### F-03-02: Movie DETAIL_CORE bypasses the canonical facade (P1)

- **Where:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1380-1411`. The branch at line 1398 (the `else` for `isTvContent`) calls `metadataSecondaryRepository.fetchTmdbEnrichment(...)` directly — there is no DETAIL_CORE round-trip through `MetadataRouterFacade` for movie detail.
- **Impact:** For movie detail screens the canonical chain is not exercised: no `metadata.route_decision`, no `metadata.provider_plan`, no `metadata.field_selected` events; `FieldResolver` ownership semantics never apply; `IntegrationRuntime` cache_decision still fires (since `metadataSecondaryRepository`/`TmdbIntegrationProvider` go through it) but routing/plan/ownership observability is silently lost. The contract for Path 03 explicitly states "TMDB primary for movies" *via the facade*; this code violates that contract.
- **Severity:** P1.
- **Fix sketch:** Replace the direct `metadataSecondaryRepository.fetchTmdbEnrichment` call with a `metadataRouterFacade.fetchTvEnrichment(...)` (or a parallel `fetchMovieEnrichment` helper) using `MetadataDepth.DETAIL_CORE`, allowing `MetadataRouter`'s movie-id routing to pick TMDB and `FieldResolver` to assemble the document.

### F-03-03: `loadMeta()` uses Stremio meta-addon as the primary detail source, not the canonical facade (P2)

- **Where:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:556-700`.
- **What:** The first thing `loadMeta()` does is `metaRepository.getMetaFromAllAddons(...)` (lines 598/669) or the preferred-addon variant `metaRepository.getMeta(addonBaseUrl, ...)` (line 612, 653). Only after that returns a `Meta` does the canonical facade run (via `applyMetaWithEnrichment` → `enrichMeta`).
- **Impact:** The contract framing of "DETAIL_CORE depth fetch goes through facade → router → plan → runtime → resolver" is partially false: the *primary* source for the title/poster/description shown to the user is the Stremio addon response, and the facade is used only to *enrich* that. If the Stremio addon fails, the entire detail load fails (line 640 sets `error = result.message`). This is a design choice (Stremio app compatibility), not necessarily a bug — but it is a structural gap between the architectural intent expressed by the canonical chain and the actual UI behaviour.
- **Severity:** P2 (architectural-intent mismatch; documented for the audit but may be intentional).
- **Fix sketch:** Either (a) make this layering explicit in the Path 03 contract / spec, or (b) flip the priority so the canonical facade runs first and Stremio addons only contribute when the canonical chain returns nothing.

## Cross-references

- Boundary map: B-Q3.1 (PREVIEW-only, not triggered here), B-Q3.2 (CONFIRMED — F-03-01) from `02-architecture-boundary-map.md`.
- Related findings: F-02-02 (manual-fallback facade with `emptySet()` adapters in `HomeProviderLocalizedMetadataOverlay`) — F-03-01 is the same pattern in `MetaDetailsViewModel`.
- Related paths: 04 (DETAIL_MEDIA), 05 (DETAIL_SECONDARY) — likely share F-03-01 since they reuse the same VM and facade.
