# Trace 04 — Detail Media Path

**Review SHA:** `774a540f8`
**Date:** 2026-04-29
**Auditor:** Architecture Review Dossier 2
**Original flags:** F-04-01 (no production caller for `DETAIL_MEDIA`), F-04-03 (trailer bypasses canonical facade), F-04-04 (detail-screen artwork collapsed into `DETAIL_CORE`, not `DETAIL_MEDIA`)

---

## 1. Scope

This trace audits the end-to-end path for `MetadataDepth.DETAIL_MEDIA` — its production callers, resolver schedule, provider plan steps, the trailer-fetch routing, and the F-04-04 ARTWORK placement decision.

| Component | Location |
|---|---|
| `MetadataDepth` enum | `core/metadata/router/MetadataModels.kt:22` |
| `ResolverOrchestrator.schedule()` | `core/metadata/router/ResolverOrchestrator.kt:19–73` |
| `ProviderPlanExecutor.tmdbSteps()` | `core/metadata/router/ProviderPlanExecutor.kt:54–110` |
| `MetadataRouterFacade.fetchTrailer()` | `core/metadata/router/MetadataRouterFacade.kt:204–229` |
| `MetadataRouterFacade.resolveRequest()` network dispatch | `core/metadata/router/MetadataRouterFacade.kt:110–143` |
| `TrailerResolver` | `core/metadata/router/resolver/TrailerResolver.kt` |
| `TrailerService` | `data/trailer/TrailerService.kt` |
| Primary VM caller | `ui/screens/detail/MetaDetailsViewModel.kt:2536–2579` |
| Secondary callers (bypass path) | `ui/screens/detail/MetaDetailsViewModel.kt:1833, 1878, 1914, 2656, 2734` |
| Home screen bypass | `ui/screens/home/HomeViewModelPresentationPipeline.kt:276, 350` |
| Pinning tests | `ResolverOrchestratorTest.kt:133–148`, `MetaDetailsViewModelTrailerTest.kt:142–154`, `ProviderPlanExecutorTest.kt:43–110` |

---

## 2. F-04-01 Closure — Does `DETAIL_MEDIA` have a production caller?

**Status: CLOSED. One production caller exists.**

### Call site

`MetaDetailsViewModel.fetchTrailerUrl()` (line 2557) issues:

```kotlin
val trailerResult = metadataRouterFacade.fetchTrailer(
    metadataRequest = MetadataRequest(
        contentId = ...,
        contentType = trailerContentType,
        sourceContext = MetadataSourceContext(itemType = trailerContentType.toApiString()),
        language = currentTvdbLanguageTag(),
        depth = MetadataDepth.DETAIL_MEDIA          // ← the sole production DETAIL_MEDIA assignment
    ),
    title = meta.name,
    year = year,
    tmdbId = tmdbId,
    ...
)
```

This is the only site in production code that assigns `MetadataDepth.DETAIL_MEDIA` to a `MetadataRequest`. (Confirmed by grep: one hit in `app/src/main/java/`.)

### Trigger chain

`fetchTrailerUrl()` is a private function. Its two callers are:

1. `handleTrailerButtonClick()` (line 2849) — fired when the user taps the trailer button on the detail screen, when `titleHasPlayableTrailerMedia == true` and no trailer URL is already loaded.
2. `fetchSeasonTrailer()` does **not** route through `fetchTrailerUrl`; it calls `trailerService.*` directly (see section 4).

The `fetchTrailerUrl` path goes through `MetadataRouterFacade.fetchTrailer()`, which internally calls `resolveRequest(metadataRequest)` with `depth = DETAIL_MEDIA` before delegating to `TrailerService.resolveTrailer()`.

### Test pin

`MetaDetailsViewModelTrailerTest` (line 142–154) pins this with `coVerify`:

```kotlin
coVerify(exactly = 1) {
    facade.fetchTrailer(
        metadataRequest = match { it.depth == MetadataDepth.DETAIL_MEDIA },
        ...
    )
}
```

F-04-01 is fully closed. The depth is live, used, and pinned.

---

## 3. Resolver Schedule at `DETAIL_MEDIA`

`ResolverOrchestrator.schedule(DETAIL_MEDIA)` (lines 34–36):

```kotlin
MetadataDepth.DETAIL_MEDIA -> {
    networkResolvers += ResolverType.TRAILERS
}
```

Together with the always-present `localResolvers += ResolverType.ADDON_DISPLAY` (line 20), the full schedule is:

| Resolver | Lane | Note |
|---|---|---|
| `ADDON_DISPLAY` | local | always included |
| `TRAILERS` | network | the only non-default entry at this depth |

All other resolver types (`RATING`, `ARTWORK`, `REVIEWS`, `RECOMMENDATIONS`, `ORGANIZATION_PERSON`, `TRACKING`) are **skipped** at `DETAIL_MEDIA`.

### How TRAILERS is dispatched

After `resolveRequest()` builds the provider plan and runs it, it iterates `resolverSchedule.networkResolvers`. For `ResolverType.TRAILERS`:

```kotlin
ResolverType.TRAILERS -> trailerResolver?.resolve(
    contentId = request.contentId,
    primary = runResult.primaryCandidateFor(ResolvedField.TRAILERS),
    secondary = runResult.secondaryCandidatesFor(ResolvedField.TRAILERS)
)
```

`TrailerResolver.resolve()` selects the first candidate (primary-then-secondary order) that carries a non-empty `TRAILERS` field and emits a `metadata.field_selected` trace event for the winner. The resolved document is then **intentionally discarded** by `fetchTrailer()` — `TrailerService.resolveTrailer()` is the actual playback-ready resolver (see section 4).

---

## 4. F-04-03 — Does Trailer Fetch Go Through the Canonical Facade?

**Status: PARTIALLY. The primary trailer-button path does; multiple season-media paths bypass the facade entirely.**

### Path A — Canonical (through facade): `fetchTrailerUrl()` → `metadataRouterFacade.fetchTrailer()`

`MetadataRouterFacade.fetchTrailer()` (lines 193–229) is explicit: it calls `resolveRequest(metadataRequest)` to fire canonical trace events (`metadata.route_decision`, `metadata.resolver_schedule`, `metadata.field_selected`) and then delegates to `TrailerService.resolveTrailer()`. The resolved document is intentionally discarded because the `TrailerService` produces a richer player-ready `TrailerResolutionResult` (with In-App YouTube extraction, separate video/audio adaptive URLs).

This path is canonical, documented in KDoc, and pinned by `MetaDetailsViewModelTrailerTest`.

### Path B — Bypass: `fetchSeasonTrailer()` → `trailerService.getSeasonTrailerPlaybackSource()` (line 2734)

`fetchSeasonTrailer()` does not use the metadata facade. It calls `trailerService.getSeasonTrailerPlaybackSource(...)` directly, emitting no `metadata.route_decision` or `metadata.resolver_schedule` trace events. No `MetadataDepth` is associated.

### Path C — Bypass: `fetchSeasonRecap()` → `trailerService.getSeasonRecapPlaybackSource()` (line 2656)

Same pattern as Path B. Direct call to `TrailerService`, no facade, no trace events.

### Path D — Bypass: `preloadTitleTrailerAvailability()` → `trailerService.getTitleMediaAvailability()` (line 1833)

Availability probing calls `trailerService.getTitleMediaAvailability()` directly. This is a pre-load heuristic call (not a playback resolution call), so the lack of facade routing is architecturally acceptable for availability checks, but it generates no resolver trace.

### Path E — Bypass: `preloadSeasonMediaAvailability()` and `ensureSeasonMediaAvailability()` → `trailerService.getSeasonMediaAvailability()` (lines 1878, 1914)

Same as Path D — direct `TrailerService` calls for season availability probing.

### Home screen bypass

`HomeViewModelPresentationPipeline.requestTrailerPreviewPipeline()` (lines 350–357) also calls `trailerService.resolveTrailer(...)` directly without going through `MetadataRouterFacade`. This is a home-screen preview path, not the detail-screen path, and does not use `DETAIL_MEDIA` depth.

### Summary of bypass scope

F-04-03 was flagged because trailer fetch bypasses the canonical metadata facade. At SHA `774a540f8`:

- The **main trailer-button path** (title trailer, `fetchTrailerUrl`) is routed through the facade and fires canonical trace events. F-04-03 is closed for this path.
- The **season trailer, season recap, and availability-probe paths** still call `TrailerService` directly. These were not addressed by the closure of F-04-03. They represent a residual bypass that reduces trace observability for season-level media resolution.

---

## 5. F-04-04 — ARTWORK Resolver: `DETAIL_CORE` vs `DETAIL_MEDIA`

**Status: CORRECTLY RESOLVED. ARTWORK belongs to `DETAIL_CORE` only.**

### Current state

`ResolverOrchestrator.schedule()` assigns `ARTWORK` exclusively to `DETAIL_CORE` (line 27) and `DETAIL_SECONDARY` (line 39). At `DETAIL_MEDIA`, `ARTWORK` is absent. The code comment at lines 29–33 documents the rationale explicitly:

```kotlin
// ARTWORK belongs to DETAIL_CORE only (F-04-04). Backdrop/logo are returned by primary
// providers in their core response (TMDB *_CORE, TVDB SERIES); a separate DETAIL_MEDIA
// artwork pass would force a redundant network round-trip with no observable benefit.
// The pinning tests `DETAIL_MEDIA does not schedule ARTWORK` and
// `DETAIL_CORE still schedules ARTWORK` enforce this invariant.
```

### Architecture pins

Two tests in `ResolverOrchestratorTest` enforce this invariant (lines 132–148):

- `DETAIL_MEDIA does not schedule ARTWORK (F-04-04 pin)` — asserts `ResolverType.ARTWORK` is absent from the `DETAIL_MEDIA` schedule.
- `DETAIL_CORE still schedules ARTWORK (regression guard)` — asserts `ResolverType.ARTWORK` is present in the `DETAIL_CORE` schedule.

Both tests pass against the current implementation.

### Why this is the correct design

Detail-screen artwork (backdrop, logo, poster) is embedded in the primary provider's core API response:
- TMDB: returned by `TmdbApiShapes.TV_CORE` and `TmdbApiShapes.MOVIE_CORE` (step role `PRIMARY_CORE`).
- TVDB: returned by `TvdbApiShapes.SERIES_EXTENDED` (step role `PRIMARY_CORE`).

A separate `DETAIL_MEDIA` artwork pass would issue a redundant network call to RPDB/TOP_POSTERS after the core response has already provided the artwork. The `ARTWORK` resolver's role is to overlay provider-specific artwork (from RPDB, TOP_POSTERS) on top of the core-provider artwork — this overlay is logically part of the core detail fetch, not the media (trailer/video) fetch.

F-04-04 is correctly closed: `DETAIL_MEDIA` must not and does not schedule `ARTWORK`. The ARTWORK resolver runs at `DETAIL_CORE` (and `DETAIL_SECONDARY`).

---

## 6. Provider Plan Steps at `DETAIL_MEDIA`

`ProviderPlanExecutor.tmdbSteps()` (lines 77–83) adds a `MEDIA`-role step at `DETAIL_MEDIA`:

```kotlin
if (depth == MetadataDepth.DETAIL_MEDIA || depth == MetadataDepth.DETAIL_SECONDARY) {
    steps += step(
        apiShapeId = if (isSeries) TmdbApiShapes.TV_VIDEOS else TmdbApiShapes.MOVIE_VIDEOS,
        provider = MetadataPrimaryProvider.TMDB,
        role = ProviderPlanRole.MEDIA
    )
}
```

So at `DETAIL_MEDIA`, a TMDB route builds a two-step plan:
1. `PRIMARY_CORE` step: `TV_CORE` or `MOVIE_CORE`
2. `MEDIA` step: `TV_VIDEOS` or `MOVIE_VIDEOS`

For TVDB routes, `tvdbSteps()` does not add a `MEDIA` step at `DETAIL_MEDIA` (only `SEASON` depth adds an extra TVDB step). This is correct: TVDB does not have a separate videos endpoint in the current adapter; trailer data for TVDB-routed content is resolved by `TrailerService` via its own TVDB integration (`TvdbTrailerResolver`).

For Kitsu routes, `kitsuSteps()` similarly adds no MEDIA step at `DETAIL_MEDIA`.

The `ProviderPlanExecutorTest` pins this with two tests (lines 43–110) that verify the TMDB plan contains exactly the core + videos steps at `DETAIL_MEDIA`.

---

## 7. Cross-Lane References

### B-08 — `fetchTrailer` intentional-discard pattern

Lane B (finding B-08) flags that `MetadataRouterFacade.fetchTrailer()` discards the `ResolvedMetadataDocument` from `resolveRequest()`. This is confirmed correct by the KDoc and by the fact that `TrailerService` produces a richer player-ready shape (`TrailerResolutionResult` with `videoUrl`/`audioUrl`). No action needed for this trace.

### H-red-flag — Trailer playback bypasses `RuntimeTraceInterceptor`

Lane H (red-flag table) confirms that the YouTube trailer OkHttp clients wire `traceInterceptor` at the network level. The facade bypass for season-media paths (Paths B, C above) means those resolution paths do not fire `metadata.route_decision` events, but the underlying HTTP requests are still traced by the OkHttp interceptor layer.

### F-12-01 / `SKIP_SEGMENTS`

Not relevant to this path. `SKIP_SEGMENTS` was removed from all `ResolverType` schedules. `DETAIL_MEDIA` never scheduled it.

---

## 8. Findings

### TM-01 (P2) — Season trailer and recap paths bypass the canonical facade, emitting no `metadata.route_decision` or `metadata.resolver_schedule` trace events

**Severity:** P2 — Reduced trace observability; no behavioral defect.

**Location:**
- `ui/screens/detail/MetaDetailsViewModel.kt:2734` — `fetchSeasonTrailer()` → `trailerService.getSeasonTrailerPlaybackSource()`
- `ui/screens/detail/MetaDetailsViewModel.kt:2656` — `fetchSeasonRecap()` → `trailerService.getSeasonRecapPlaybackSource()`

**Description:**
F-04-03 was flagged because trailer enrichment bypasses the canonical metadata facade. The title-trailer path through `fetchTrailerUrl()` is now correctly routed via `MetadataRouterFacade.fetchTrailer()`, which fires canonical trace events before delegating to `TrailerService`. However, the season trailer and season recap paths (`fetchSeasonTrailer`, `fetchSeasonRecap`) still call `TrailerService` methods directly. These two paths:

- Issue no `MetadataRequest` and therefore set no `MetadataDepth`.
- Fire no `metadata.route_decision`, `metadata.resolver_schedule`, or `metadata.field_selected` trace events.
- Are not covered by any architecture pin equivalent to `MetaDetailsViewModelTrailerTest`.

**Impact:** Season trailer and recap resolution is invisible to the metadata trace pipeline. If a routing or provider issue occurs for a season-specific trailer, there is no trace artifact to diagnose it. This is especially relevant for TVDB-primary series content, where `TrailerService` has a separate TVDB-first resolution path.

**Recommendation:** Introduce `MetadataRouterFacade.fetchSeasonTrailer()` and `fetchSeasonRecap()` (or a single `fetchSeasonMedia()` variant with a `mediaType` parameter) that route through `resolveRequest(depth = DETAIL_MEDIA)` before delegating to the corresponding `TrailerService` method. Alternatively, wrap the two direct calls to set a trace context via a lightweight `TraceMetadataEvents.emitSeasonMediaResolution(…)` event if full facade routing is disproportionate for these paths.

---

### TM-02 (P3) — Availability-probe calls (`getTitleMediaAvailability`, `getSeasonMediaAvailability`) bypass the facade; no `MetadataDepth` is attached

**Severity:** P3 — Trace gap only; availability probing is a read-only heuristic.

**Location:**
- `ui/screens/detail/MetaDetailsViewModel.kt:1833` — `preloadTitleTrailerAvailability()`
- `ui/screens/detail/MetaDetailsViewModel.kt:1878, 1914` — `preloadSeasonMediaAvailability()` / `ensureSeasonMediaAvailability()`

**Description:**
Availability probing calls `TrailerService` methods directly without routing through the facade. Unlike the season-media resolution paths (TM-01), these are not playback-initiation calls — they merely test whether any trailer or recap media exists before surfacing UI affordances. There is no architectural requirement for these to fire full resolver trace events. However, they do represent a second distinct category of `TrailerService` calls that bypass the facade, and they are not documented as intentional bypasses.

**Recommendation:** Add a `// ARCHITECTURE: availability probe — no depth or trace needed` comment at each call site to distinguish intentional bypasses from oversight. If a future audit criterion requires tracing all `TrailerService` interactions, these sites will be easier to identify.

---

### TM-03 (P3) — Home screen `requestTrailerPreviewPipeline` calls `trailerService.resolveTrailer()` directly with no `MetadataDepth`

**Severity:** P3 — Out-of-scope for detail-screen trace, but consistent pattern.

**Location:** `ui/screens/home/HomeViewModelPresentationPipeline.kt:350`

**Description:**
The home-screen trailer preview pipeline calls `trailerService.resolveTrailer(...)` directly without any facade involvement. This is architecturally distinct from the detail-screen path — the home screen uses `MetadataDepth.DETAIL_CORE` for the metadata fetch and `TrailerService` directly for trailer resolution. There is no `DETAIL_MEDIA` depth involved. This is a parallel call pattern, not a regression of F-04-03 per se, but it represents a third facade-bypass site for `resolveTrailer()`.

**Recommendation:** Track in a future F-04-05 task: "unify home-screen trailer preview resolution through the canonical facade." Not a blocker for the current review cycle.

---

### TM-04 (Nit) — `MetadataRouterFacade.fetchTrailer()` discards the `ResolvedMetadataDocument` with documented intent; no machine-checkable marker

**Severity:** Nit — subsumed by B-08 in Lane B; noted here for completeness.

**Location:** `core/metadata/router/MetadataRouterFacade.kt:217–219`

**Description:**
`fetchTrailer()` calls `resolveRequest(metadataRequest)` purely for its trace side-effects and discards the resolved document. The KDoc is explicit. The same pattern exists for `fetchTmdbEnrichment()` (noted as B-08). There is no naming or annotation convention that distinguishes "intentional trace-only resolve" from "accidental discard". This is a maintenance risk if the pattern is imitated without context.

**Recommendation:** See B-08 in Lane B. A naming convention such as `resolveRequestForTraceOnly()` or a `@TraceOnly` annotation on the call site would make the intent machine-checkable.

---

## 9. F-04-01 Closure Verdict

**`DETAIL_MEDIA` should be kept. It is not dead code.**

The depth has one active production caller (`MetaDetailsViewModel.fetchTrailerUrl()` via `MetadataRouterFacade.fetchTrailer()`), a well-defined resolver schedule (TRAILERS network, ADDON_DISPLAY local), and a provider plan step (TMDB TV/MOVIE_VIDEOS at `ProviderPlanRole.MEDIA`). All three are pinned by tests.

The `ARTWORK` resolver does not and should not run at `DETAIL_MEDIA`. F-04-04 established that artwork is collapsed into the `DETAIL_CORE` response (it arrives with the primary provider's core API call). Running ARTWORK again at `DETAIL_MEDIA` would be a redundant network round-trip. The two pinning tests in `ResolverOrchestratorTest` enforce this invariant.

**Recommendation for F-04-01 close note:** Mark F-04-01 CLOSED — `DETAIL_MEDIA` has a production caller (trailer button path in `MetaDetailsViewModel`). The ARTWORK resolver correctly does not run at this depth (F-04-04). The depth is necessary and should not be removed.

---

## 10. Summary Table

| ID | Severity | Description | Status |
|---|---|---|---|
| TM-01 | P2 | Season trailer (`fetchSeasonTrailer`) and recap (`fetchSeasonRecap`) calls bypass the canonical facade — no `metadata.route_decision` / `metadata.resolver_schedule` events fire | OPEN |
| TM-02 | P3 | Availability-probe calls (`getTitleMediaAvailability`, `getSeasonMediaAvailability`) bypass the facade with no documented intent marker | OPEN |
| TM-03 | P3 | Home-screen `requestTrailerPreviewPipeline` calls `trailerService.resolveTrailer()` directly without facade or `DETAIL_MEDIA` depth — consistent cross-screen pattern | OPEN |
| TM-04 | Nit | `fetchTrailer()` intentional-discard pattern lacks a machine-checkable marker (subsumed by B-08) | OPEN |
| F-04-01 | — | `DETAIL_MEDIA` has a production caller (trailer-button path); depth is live and should NOT be removed | CLOSED |
| F-04-03 | — | Title-trailer path (`fetchTrailerUrl`) routed through facade; season-media paths still bypass (TM-01) | PARTIALLY CLOSED |
| F-04-04 | — | `ARTWORK` resolver correctly assigned to `DETAIL_CORE` only; `DETAIL_MEDIA` does not schedule ARTWORK; two pinning tests enforce invariant | CLOSED |

---

## 11. Overall Path Health

**The `DETAIL_MEDIA` depth is alive, correctly scoped, and well-pinned for its primary use case.** F-04-01 is fully closed. F-04-04 is correctly resolved with documentation and pin coverage. F-04-03 is partially closed: the title-trailer path is canonical, but the season-media paths (season trailer, season recap) remain direct `TrailerService` calls with no metadata facade involvement and no resolver trace events. The single P2 finding (TM-01) is a trace observability gap, not a behavioral defect. No source changes are required to unblock other lanes; TM-01 should be addressed in a follow-up task scoped to season-media trace coverage.
