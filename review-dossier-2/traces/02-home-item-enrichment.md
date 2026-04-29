# Trace 02 — Home Visible Item Enrichment

**SHA under review:** `774a540f8` (`codex/integration-runtime-phase-a`)
**Date:** 2026-04-29

---

## 1. Path Summary

When a `ContinueWatchingItem` becomes visible in a Home rail, the ViewModel enriches it with
localized provider metadata (title, description, poster, backdrop, logo, rating, runtime in the
user's language). The enrichment path has two parallel sub-paths that both fuse into the same
returned item:

1. **Title / imagery enrichment** — `overlayProviderLocalizedMetadataForHome` via
   `ProviderLocalizedMetadataResolver.fetchDecision` → `MetadataRouterFacade.resolveRequest`.
2. **Episode description enrichment** (series only) — `localizedContinueWatchingEpisodeDescription`
   via `MetadataRouterFacade.fetchTvEpisodeEnrichment` → SEASON-depth plan.

Both paths go exclusively through the canonical facade. No direct `metadataSecondaryRepository`
calls exist anywhere in the `home/` screen package.

---

## 2. Caller Chain

```
HomeViewModel.enrichContinueWatchingItems(items, settings)
  └─ mapContinueWatchingEnrichmentWithLimit { item ->
       HomeViewModel.enrichContinueWatchingItemWithProvider(item)
         │
         ├─── overlayProviderLocalizedMetadataForHome(item.toContinueWatchingProviderPreview(), ...)
         │      └─ fetchProviderLocalizedMetadataDecisionForHome(item, ...)
         │           │  MetadataRequest(depth = DETAIL_CORE, language = tvdbLanguage)
         │           │  TvMetadataRequest(contentId, fallbackContentId, contentType, language)
         │           └─ ProviderLocalizedMetadataResolver.fetchDecision(metadataRequest, tvRequest)
         │                └─ MetadataRouterFacade.resolveRequest(metadataRequest)
         │                     ├─ ResolverOrchestrator.schedule(DETAIL_CORE)
         │                     │    └─ schedules localResolvers: [ADDON_DISPLAY, RATING, ARTWORK]
         │                     │       networkResolvers: []   (no network resolvers at DETAIL_CORE)
         │                     ├─ MetadataRouter.route(request)
         │                     │    └─ emit metadata.route_decision
         │                     ├─ MetadataIdentityResolver.resolve(route)
         │                     ├─ ProviderPlanExecutor.buildPlan(route, DETAIL_CORE)
         │                     │    └─ TVDB → [SERIES_EXTENDED]
         │                     │       TMDB → [TV_CORE or MOVIE_CORE]
         │                     │       Kitsu → [ANIME_CORE]
         │                     ├─ ProviderPlanRunner.run(plan)
         │                     │    ├─ emit metadata.provider_plan
         │                     │    └─ MetadataProviderAdapter.execute(route, step)
         │                     │         └─ emit metadata.localization_plan  [F-E-02]
         │                     │            fetchSeriesExtendedCached / fetchMovieCore / fetchTvCore
         │                     └─ FieldResolver.resolveWithPreview(preview, primary, secondary,
         │                             requestContentId = request.contentId)
         │                          └─ emit metadata.field_selected × N  [F-B-05]
         │                             → ResolvedMetadataDocument { fieldOwners populated }
         │
         ├─── localizedContinueWatchingEpisodeDescription(metadataRouterFacade, item, language)
         │      MetadataRequest(depth = SEASON, seasonNumber = season)
         │      └─ MetadataRouterFacade.fetchTvEpisodeEnrichment(metadataRequest, tvRequest)
         │           ├─ MetadataRouter.route (SEASON depth)
         │           ├─ MetadataIdentityResolver.resolve
         │           ├─ ProviderPlanExecutor.buildPlan(route, SEASON)
         │           │    └─ TVDB → [SERIES_EXTENDED, SERIES_EPISODES_LANGUAGE]
         │           │       TMDB → [TV_CORE, SEASON_EPISODES]
         │           └─ ProviderPlanRunner.run(plan)  → episodeMetadata map
         │
         └─ merge: localizedPreview.toHomeDisplayMetadata().mergeFallback(existing)
              → item.copy(displayMetadata, episodeDescription, genres, releaseInfo)
```

**Key types at each stage:**

| Stage | Type |
|---|---|
| Entry input | `ContinueWatchingItem` (InProgress or NextUp) |
| Router request | `MetadataRequest(depth=DETAIL_CORE, language=tvdbLanguage)` |
| Router output | `MetadataRoute` → `MetadataPrimaryProvider` selection |
| Plan | `ProviderExecutionPlan` with `ProviderPlanStep` list |
| Run output | `ProviderPlanRunResult` with `primaryCandidate` + `secondaryCandidates` |
| Field resolution | `FieldResolver.resolveWithPreview(...)` → `ResolvedMetadataDocument` |
| Facade result | `MetadataResolutionResult { resolvedDocument, route, providerRunResult }` |
| Resolver result | `TvMetadataDecision<TvMetadataEnrichment>` |
| Final | enriched `ContinueWatchingItem` via `item.copy(...)` |

---

## 3. Trace Events Expected

The following trace events fire when a Home CW item is enriched with a real trace session active
(i.e., `sessionId() != null`). Events fire in sequence order:

| Sequence | Event | Emitter | Key payload fields |
|---|---|---|---|
| 1 | `metadata.resolver_schedule` | `ResolverOrchestrator.schedule` | `depth=DETAIL_CORE`, `scheduled=[ADDON_DISPLAY, RATING, ARTWORK]`, `skipped={...}` |
| 2 | `metadata.route_decision` | `MetadataRouter.route` | `contentId`, `provider`, `reason`, `targetIds`, `targetIdRequiresIdentityResolution` |
| 3 | `metadata.localization_plan` | `TvdbMetadataProviderAdapter.execute` (or TMDB/Kitsu adapter) | `contentId`, `provider`, `policyVersion`, `requestedLanguage`, `fallbackLanguage` |
| 4 | `metadata.provider_plan` | `ProviderPlanRunner.run` | `contentId`, `provider`, `depth=DETAIL_CORE`, `steps` |
| 5…N | `metadata.field_selected` × per-field | `FieldResolver.buildDocument` | `contentId = request.contentId` (F-B-05), `field`, `selectedProvider`, `sourceRole`, `ownershipRule` |

For series items the episode description sub-path fires an additional SEASON-depth sequence (events
1–4 repeat at SEASON depth, with `metadata.localization_plan` containing non-zero
`perEpisodeFallbacksAttempted` for TVDB, per F-E-01).

No `metadata.identity_resolution` event fires unless the item's ID requires cross-provider
resolution (e.g., an IMDB-scheme id for a series).

---

## 4. Verification

### F-B-02: No direct `metadataSecondaryRepository` calls in `home/`

```
grep -rn "fetchTmdbEnrichment\|fetchTvEnrichment\|metadataSecondaryRepository\." \
  app/src/main/java/com/nexio/tv/ui/screens/home/ 2>/dev/null
```

Result: **zero matches**. The home package is clean. The only `metadataSecondaryRepository` call
visible in the broader UI layer from the grep is `MetaDetailsViewModel` (detail screen, out of
scope) and `MetadataRouterFacade.fetchTmdbEnrichment` itself, which is the facade wrapper, not a
bypass.

### F-B-03: Facade used, not bypassed

`ProviderLocalizedMetadataResolver.fetchDecision` calls `metadataRouterFacade.resolveRequest`
(line 21, `ProviderLocalizedMetadataResolver.kt`). There is no legacy codepath. The
`fetchTvEnrichment` and `fetchTvEpisodeEnrichment` facades also call `resolveRequest` internally.

### F-B-04: `networkResolvers` dispatch

At `DETAIL_CORE` depth, `ResolverOrchestrator.schedule` returns `networkResolvers = []` — there
are no network resolvers to dispatch for the Home enrichment path. This is correct: TRAILERS,
REVIEWS, RECOMMENDATIONS, and ORGANIZATION_PERSON are not part of the home-rail enrichment
contract. The dispatch loop at `MetadataRouterFacade.resolveRequest:110` iterates over an empty
list and does nothing.

### F-B-05: `requestContentId` in `field_selected` events

`FieldResolver.resolveWithPreview(…, requestContentId = request.contentId)` threads
`request.contentId` as `traceContentId` into `buildDocument`. Every `emitFieldSelected` call at
line 239 uses `traceContentId` as the `contentId` payload key — not the provider name fallback.
This satisfies the F-B-05 contract. Pinned by `FieldResolverContentIdInTraceTest`.

### F-E-02: `metadata.localization_plan` fires

Each provider adapter emits `emitLocalizationPlan` immediately after `LocalizationPolicy`
construction, before any network call:
- `TvdbMetadataProviderAdapter.kt:30` (SERIES_EXTENDED step)
- `TvdbMetadataProviderAdapter.kt:84` (SERIES_EPISODES_LANGUAGE step — SEASON depth)
- `TmdbMetadataProviderAdapter.kt:34`
- `KitsuMetadataProviderAdapter.kt:35`

The validator rule `LocalizationPlanPrecedesProviderSteps` expects this event between
`route_decision` and `provider_plan` (see `TraceValidationRules.kt:258–290`).

### Cache decisions use real TTLs

Both TVDB (`fetchSeriesExtendedCached`) and TMDB (`fetchMovieCore` / `fetchTvCore`) use
`IntegrationCachePolicy.CacheFirst(ttlMs = 7 * 24 * 60 * 60 * 1_000L,
staleAfterExpiryMs = 30 * 24 * 60 * 60 * 1_000L)` — 7-day freshness, 30-day stale window. This
is a real TTL, not `Disabled` or `ObserveOnly`. Kitsu uses 24-hour TTLs. No hot-path calls
use `IntegrationCachePolicy.Disabled` for the home enrichment shapes.

### `fieldOwners` populated

`FieldResolver.buildDocument` writes into `owners: linkedMapOf<ResolvedField, FieldOwner>` and
returns `ResolvedMetadataDocument(fieldOwners = owners, …)`. For the PREVIEW-depth early-return
branch the document is also produced via `resolveWithPreview(preview, null, [])` which populates
`fieldOwners` from the preview candidate (F-B-01 closed). For DETAIL_CORE the primary candidate
always exists (default `emptyCandidate` if the adapter returns nothing), so `fieldOwners` is
non-null and non-empty whenever the provider returns at least one field.

---

## 5. Path-Specific Findings

### Finding 02-A (P2) — `fetchTvEnrichment` on `MetadataRouterFacade` discards `resolvedDocument`

**Location:** `MetadataRouterFacade.kt:156–167`

`fetchTvEnrichment` calls `resolveRequest`, then constructs a `TvMetadataDecision` by calling
`result.resolvedDocument.toTvMetadataEnrichment()`. This is correct for the *runtime pipeline*
uses (e.g., `HomeViewModelContinueWatchingRuntimePipeline.kt:61, 83`). However, the method is
distinct from the `ProviderLocalizedMetadataResolver` path used by
`enrichContinueWatchingItemWithProvider`. The runtime pipeline uses `fetchTvEnrichment` only for
the *runtime minutes* warm-up path (`resolveContinueWatchingRuntimeMinutes`), not for the primary
enrichment. The two paths are not interchangeable: `fetchTvEnrichment` always returns
`TVDB_SUCCESS` regardless of actual provider, losing the `diagnostics` that
`ProviderLocalizedMetadataResolver` produces. This is an existing pattern flagged as B-08 (Nit)
in Lane B, but it also surfaces here as the runtime path silently upgrades its decision reason.

**Impact:** Low — the runtime minutes path only reads `runtimeMinutes`, not the decision reason.
No user-visible defect. The diagnostics loss is only observable in trace sessions.

### Finding 02-B (P3) — SEASON-depth episode enrichment does not emit `metadata.resolver_schedule`

**Location:** `MetadataRouterFacade.fetchTvEpisodeEnrichment:411–442`

`fetchTvEpisodeEnrichment` calls `router.route`, `identityResolver.resolve`, and
`providerPlanExecutor.buildPlan` directly — it does **not** call `resolveRequest`. As a result,
`ResolverOrchestrator.schedule` is never invoked, and no `metadata.resolver_schedule` event fires
for the SEASON-depth sub-path. The `metadata.provider_plan` event still fires (via
`ProviderPlanRunner.run`), but the validator rule `ResolverSchedulePrecedesPlan` (if it exists)
would fire against an event stream with a `provider_plan` that has no preceding
`resolver_schedule`.

This also means `ARTWORK`, `RATING`, and `ADDON_DISPLAY` are never scheduled for the episode-description
enrichment — which is intentionally correct (episode descriptions do not need artwork), but the
absence of the schedule event is an instrumentation gap rather than a correctness issue.

**Impact:** Trace completeness gap. Does not affect production enrichment logic.

### Finding 02-C (P3) — `enrichContinueWatchingItemWithProvider` broad exception catch masks localization failures

**Location:** `HomeViewModelContinueWatching.kt:247–252`

```kotlin
} catch (e: Exception) {
    Log.w(HomeViewModel.TAG, "Provider enrichment failed for continue watching item $contentId: ${e.message}")
    item
}
```

Any failure in `overlayProviderLocalizedMetadataForHome` or `localizedContinueWatchingEpisodeDescription`
(including a `MetadataRouteFailure.MissingPlanStepAdapter` or an identity resolution failure) is
silently degraded to the un-enriched item. This is the same pattern noted in Lane G finding G-04,
confirming it covers both the outer wrapper and the inner overlay. No trace event fires on this
fallback path, so a misconfigured adapter in a test harness will produce a correctly-shaped item
with stale addon data and no observable signal.

**Impact:** Same as G-04 (P3). No production risk given Hilt wiring; masks misconfiguration in
partial test environments.

### Finding 02-D (Nit) — `ProviderLocalizedMetadataResolver` does not thread `requestContentId` into its local TvMetadataEnrichment construction

**Location:** `ProviderLocalizedMetadataResolver.kt:39–49`

The resolver calls `metadataRouterFacade.resolveRequest(metadataRequest)` which correctly threads
`requestContentId` through `FieldResolver`. The `TvMetadataEnrichment` is then constructed from
`canonicalDocument` fields directly. This is correct — the enrichment does not need a
`requestContentId`. However, the `TvMetadataDecision` returned to the caller carries
`diagnostics` built from `tvRequest.contentId` (line 49), not from `metadataRequest.contentId`.
If these differ (e.g., when `fallbackContentId` is set), diagnostic events reference the
fallback id. This is cosmetic, not a functional issue.

---

## 6. Cross-References

| Reference | Lane | Status | Relevance to this trace |
|---|---|---|---|
| F-B-01 (PREVIEW→FieldResolver) | B | CLOSED | `resolveRequest` PREVIEW branch uses `resolveWithPreview`; Home first-paint path is distinct from enrichment path but same resolver. |
| F-B-02 (no direct FieldResolver construction) | B | CLOSED | No bare `FieldResolver()` in home package; enrichment flows through facade. |
| F-B-03 (no direct `metadataSecondaryRepository` in UI) | B | CLOSED | Verified above — zero direct calls in `home/`. |
| F-B-04 (networkResolvers dispatch) | B | CLOSED | Dispatches empty list at `DETAIL_CORE`; correct for home enrichment. |
| F-B-05 (requestContentId in field_selected) | B | CLOSED | `request.contentId` threaded through `resolveWithPreview` to `emitFieldSelected`. |
| B-08 (fetchTmdbEnrichment discards resolved document) | B | Nit | Same pattern seen in `fetchTvEnrichment`; manifests as Finding 02-A. |
| F-E-02 (localization_plan emission) | E | CLOSED | Emitted by all three adapters before any network call; confirmed in path. |
| F-E-01 (per-episode fallback counter) | E | CLOSED | Surfaced via second `emitLocalizationPlan` in TVDB adapter SEASON branch. |
| G-04 (broad exception catch in enrichContinueWatchingItemWithProvider) | G | P3 | Same catch block confirmed here as Finding 02-C. |
| D-cache-ttl | D | — | TVDB and TMDB core fetch calls use `CacheFirst(7d, 30d)` real TTLs; no `Disabled` or zero-TTL in the home enrichment shapes. |

---

## 7. Summary

The home item enrichment path correctly routes all localized metadata resolution through
`MetadataRouterFacade.resolveRequest` via the `ProviderLocalizedMetadataResolver` intermediary.
There are no direct `metadataSecondaryRepository` calls in the `home/` package. Depth is
`DETAIL_CORE` for title/imagery enrichment and `SEASON` for episode description.
`fieldOwners` is populated on every returned `ResolvedMetadataDocument`. All five verification
criteria (F-B-02/03, F-B-04, F-B-05, F-E-02, real TTLs) pass. Four path-specific findings were
identified: one P2 (runtime minutes path loses diagnostics via `fetchTvEnrichment`), two P3
(SEASON sub-path omits `resolver_schedule` event; broad catch masks localization failures), and
one Nit (diagnostic contentId may reference fallback rather than canonical id).
