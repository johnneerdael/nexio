# Path 06 — Season tab (TVDB localized + English fallback)

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Lane:** B (router) + E (localization) + I (trace)
- **Contract:** TVDB localized → TVDB English fallback (same-provider). NEVER TMDB. Cache-first English. Bounded per-episode translations.

## Chain

| # | Symbol | File:line | Expected | Observed |
|---|---|---|---|---|
| 1 | UI: season tab open / `MetaDetailsEvent.OnSeasonOptionsOpened` | `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:371` | nav into season-aware preload | `preloadSeasonMediaAvailability(season)` invoked; main episode list hydration occurs once via `applyTvEpisodeEnrichment` (initial detail load) |
| 2 | Season VM enrichment `applyTvEpisodeEnrichment(...)` | `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1761` | builds `MetadataRequest(depth=SEASON, seasonNumber=…, language=tvdbLanguage)` and calls facade | matches expectation; profile language from `currentTvdbLanguageTag()` (line 1838-1840) |
| 3 | Facade `MetadataRouterFacade.fetchTvEpisodeEnrichment(...)` | `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:82-113` | route → identity-resolve → buildPlan(SEASON) → providerPlanRunner.run | facade-routed; **no direct repository bypass** for this path. Verdict: facade-honored |
| 4 | Plan execution → `TvdbMetadataProviderAdapter.execute` for `SERIES_EPISODES_LANGUAGE` | `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt:62-89` | calls `fetchLocalizedSeasonEpisodeBundle(...)` with `route.language` | matches |
| 5a | TVDB English payload fetch (`lang:eng`) | `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt:488-495` | `runtime.get` with CacheFirst (24h TTL / 7d stale) | matches; `IntegrationCachePolicy.CacheFirst` confirmed at line 426-429 |
| 5b | TVDB localized payload fetch (`lang:requested`) | `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt:520-527` | skipped when `requestedIsFallback`; otherwise CacheFirst fetch | matches; short-circuit on line 509 |
| 6 | `LocalizationResolver.selectField(...)` | `app/src/main/java/com/nexio/tv/data/integration/metadata/LocalizationResolver.kt:6` | per-field selection: localized → english fallback → canonical → addon | matches; called via `TvdbEpisodeLocalization.mergeEnglishBaseBundle` (`app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbEpisodeLocalization.kt:52-61`) |
| 7 | per-episode translation fallback | `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt:530-543` + `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbEpisodeLocalization.kt:92-108` | bounded to N attempts; CacheFirst per-episode | matches: `idsMissingLocalizedFields(...).take(policy.maxPerEpisodeTranslationFallbacksPerRequest)` (cap = 8 default) |
| 8 | season/episode list render via `Meta.videos.map { … }` | `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1803-1824` | localized titles + overviews applied to `Video.title` / `Video.overview` | matches; `ep?.title ?: video.title` and `ep?.overview ?: video.overview` |

`markSeasonWatched` (line 2348) takes a separate path through `MetadataRouterFacade.fetchTvSeasonEpisodes` (`MetadataRouterFacade.kt:115-155`) but reuses the same plan executor + adapter chain, so localization semantics are preserved. The `MetadataDepth.SEASON` branch in `ResolverOrchestrator.kt:40` only adds RATING (local) on top of `ADDON_DISPLAY`; the resolver schedule is computed in `resolveRequest` but `fetchTvEpisodeEnrichment` and `fetchTvSeasonEpisodes` bypass `resolveRequest` and call the plan runner directly, so the resolver schedule does NOT fire on the season tab path (cross-ref F-04-02).

## Critical contract checks

- ✅ NO TMDB fallback for missing TVDB localized text. Enforced in two ways:
  1. `LocalizationPolicy.tvdb(...)` sets `allowProviderFallbackForMissingLocalizedFields = false` (`app/src/main/java/com/nexio/tv/data/integration/metadata/LocalizationPolicy.kt:42`).
  2. Hard `check(!policy.allowProviderFallbackForMissingLocalizedFields)` guard at `TvdbEpisodeLocalization.kt:31-33` ("TVDB localization fallback must stay within TVDB for missing localized fields"). Episode candidate list at `TvdbEpisodeLocalization.kt:160-192` only ever lists TVDB sources (`provider = MetadataPrimaryProvider.TVDB`).
  - The `tvdb_fallback_tmdb` diagnostic in `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt:132-134` and `TvMetadataRouter.kt` is a **router-level** TMDB fallback (used when the entire TVDB record/identity is missing), not a per-field localized fallback. Distinct from the localization contract.
- ✅ English payload uses CacheFirst policy. `IntegrationCachePolicy.CacheFirst(ttlMs=24h, staleAfterExpiryMs=7d)` at `TvdbIntegrationProvider.kt:426-429` for `SERIES_EPISODES_LANGUAGE`; same for `EPISODE_TRANSLATION` at line 590-593. Cache key includes `:policy:$localizationPolicyVersion` so policy bumps invalidate cleanly.
- ✅ Per-episode translation fallback is bounded. Cap = `LocalizationPolicy.DEFAULT_PER_EPISODE_TRANSLATION_FALLBACK_CAP = 8` (`LocalizationPolicy.kt:30`); enforced in `TvdbEpisodeLocalization.kt:107` via `.take(policy.maxPerEpisodeTranslationFallbacksPerRequest)`. The bundle reports `perEpisodeTranslationFallbacksAttempted` and `maxPerEpisodeTranslationFallbacksAllowed` for downstream observability.
- ❌ `metadata.localization_plan` event NOT emitted in production. Confirmed: `grep -rn "emitLocalizationPlan\|metadata.localization_plan" app/src` returns empty. `TraceMetadataEvents.kt` exposes only `emitRouteDecision`, `emitProviderPlan`, `emitFieldSelected` (no localization helper). The helper was deleted in commit `e3a3ab8d7` because no canonical orchestration site was identified, and no replacement emission has been added. **F-06-01 filed.**

## What does NOT happen on this path (verified)

- ❌ NO TMDB fallback for missing localized TVDB fields (enforced by `check()` in `TvdbEpisodeLocalization.kt:31-33`).
- ❌ NO duplicate English payload network fetches when cache HIT — `runtime.get(spec)` with CacheFirst returns cached payload; same `cacheKey` used by both the SEASON-tab and DETAIL-core paths so the English payload fetched during detail load is reused on season-tab open.
- ❌ NO unbounded per-episode translation calls (capped at 8).
- ❌ NO `ResolverOrchestrator` schedule execution — `fetchTvEpisodeEnrichment` and `fetchTvSeasonEpisodes` skip `resolveRequest`, so the SEASON resolver schedule (`MetadataResolverGroup.RATING (LOCAL)`) never fires (cross-ref F-04-02).

## Trace event coverage

| Event | Emitted on this path? | Notes |
|---|---|---|
| metadata.route_decision | ❌ | `MetadataRouter.route(...)` at `MetadataRouter.kt:255` emits the event, but `fetchTvEpisodeEnrichment` calls `router.route(...)` directly without going through the trace-emitting wrapper used in `resolveRequest`; the trace is only emitted when the router instance has a tracer wired. (Need to verify wiring; presence of emit call ≠ runtime emission for this path.) |
| metadata.provider_plan | ❌ | `ProviderPlanRunner.run(plan)` (line 15) calls `traceEvents.emitProviderPlan(...)`. Emitted IFF `traceEvents` collector is wired and the plan runner is invoked — yes for this path via `providerPlanRunner.run(plan)` (`MetadataRouterFacade.kt:102, 130`). Coverage depends on consumer collector. |
| metadata.localization_plan | ❌ | helper deleted; emission deferred. **F-06-01.** |
| runtime.operation_start / cache_decision per payload | ✅ | Each `runtime.get(spec)` invocation in `TvdbIntegrationProvider` emits standard runtime trace events (verified via Phase 2 findings F-02-01..02). |
| metadata.field_selected per episode field | ❌ | `FieldResolver.kt:74` emits `emitFieldSelected` only inside the `fieldResolver.resolve(...)` path — which is invoked from `resolveRequest` (`MetadataRouterFacade.kt:52-55`), NOT from `fetchTvEpisodeEnrichment` / `fetchTvSeasonEpisodes`. Episode-level field selection happens inside `LocalizationResolver.selectField` but no `metadata.field_selected` event is emitted there. **F-06-02 filed.** |

## Verdict

⚠️ — The localization contract (TVDB→English same-provider, no TMDB fallback) is **sound and enforced** by both policy + runtime `check()`. Caching uses CacheFirst with policy-versioned keys and per-episode translations are bounded at 8. However, on-device verifiability is **broken**:
- `metadata.localization_plan` is not emitted anywhere (helper deleted, no replacement).
- `metadata.field_selected` is not emitted for per-episode field decisions, so the most operationally important per-field localization choice (which language won, why) is invisible to traces.

The SEASON depth resolver schedule is also dead because the season-episode entry points bypass `resolveRequest` (cross-ref F-04-02).

## Findings

- **F-06-01 (P1, observability):** `metadata.localization_plan` event is not emitted in production. Helper deleted in commit `e3a3ab8d7`; no replacement emission added. Impact: there is no trace-side evidence of which localization policy was applied (requested language, fallback language, policy version, per-episode-translation cap, attempted vs. allowed) for any TVDB/TMDB/Kitsu localized fetch. The localization contract is enforced by code but not observable post-hoc on user devices. Suggested owner: `MetadataRouterFacade` immediately after policy resolution OR `TvdbMetadataProviderAdapter.execute` immediately after constructing `LocalizationPolicy`.
- **F-06-02 (P1, observability):** `metadata.field_selected` is not emitted for per-episode title/overview localization decisions. `FieldResolver.emitFieldSelected` covers only top-level `ResolvedMetadataDocument` fields and is unreachable from `fetchTvEpisodeEnrichment`/`fetchTvSeasonEpisodes`. Per-episode field winners (TVDB localized vs. English fallback vs. per-episode translation) live in `LocalizedEpisodeMetadata.fieldSources` but never reach the trace stream. Suggested fix: emit `metadata.field_selected` (or a dedicated `metadata.episode_field_selected`) in `TvdbMetadataProviderAdapter.execute` after `bundle.episodes` is built, iterating `fieldSources`.
- **F-06-03 (P2, dead code, cross-ref F-04-02):** `MetadataDepth.SEASON` branch of `ResolverOrchestrator.schedule(...)` (line 40) computes a resolver schedule that adds RATING (local) on top of ADDON_DISPLAY — but the season-tab entry points (`fetchTvEpisodeEnrichment`, `fetchTvSeasonEpisodes`) bypass `resolveRequest` and call `providerPlanRunner.run(plan)` directly, so the SEASON resolver schedule never fires. Same root cause as F-04-02 (resolver-orchestrator dead in production), but the SEASON-specific RATING (local) overlay is the most likely candidate for missing-feature regression.

## Cross-references

- F-04-02 (ResolverOrchestrator never invoked) — affects whether `SEASON` depth resolver schedule fires; F-06-03 is the SEASON-specific instance.
- F-02-01..02 (runtime trace coverage) — runtime cache_decision events ARE emitted for each TVDB payload fetch on this path.
- Boundary map Q7 (no canonical localization owner) — F-06-01 is the trace-side manifestation: no canonical owner means no canonical trace emission site.
