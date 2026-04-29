# Trace 06 — Season Tab: Per-Episode Localized Metadata Fetch

**Review SHA:** `774a540f8`
**Date:** 2026-04-29
**Lane cross-references:** E-01, E-04, E-06 (Lane E); B-04 (Lane B)

---

## 1. Path Overview

When a user selects a season tab on the series detail screen, the UI fires `MetaDetailsEvent.OnSeasonSelected(season)`. The ViewModel dispatches to `selectSeason()`, which resets trailer state and delegates to `preloadSeasonMediaAvailability()`. The episode metadata fetch — the localization-sensitive work — is triggered separately via `loadEpisodeMetadataAsync()`, which calls `MetadataRouterFacade.fetchTvEpisodeEnrichment()`. This facade routes through the `ProviderPlanRunner` with plan step `TvdbApiShapes.SERIES_EPISODES_LANGUAGE`, which ultimately lands in `TvdbMetadataProviderAdapter.execute()`. That adapter calls `TvdbIntegrationProvider.fetchLocalizedSeasonEpisodeBundle()`, which is the production policy-compliant path.

There is also a legacy path: `TvMetadataRouter.fetchSeasonEpisodes()` calls `TvdbMetadataService.fetchSeasonEpisodes()` — this path bypasses `LocalizationPolicy` entirely (documented as Finding E-04 in Lane E; traced again below as a path-specific observation).

---

## 2. Entry Point

**File:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt:633`

```
SeasonTabs(
    onSeasonSelected = { viewModel.onEvent(MetaDetailsEvent.OnSeasonSelected(it)) }
)
```

**File:** `app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodesSection.kt:193`

The `SeasonTabs` composable calls `onSeasonSelected(season)` on click (D-pad select), which fires `MetaDetailsEvent.OnSeasonSelected`.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:342`

`onEvent` dispatches `OnSeasonSelected` to `selectSeason(event.season)`.

---

## 3. Season Selection Dispatch

**File:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1735`

`selectSeason(season)`:
1. Cancels any in-flight `trailerFetchJob`.
2. Updates `_uiState` with `withManualSeasonSelection(season)` and clears all trailer state fields.
3. Calls `preloadSeasonMediaAvailability(season)`.

`preloadSeasonMediaAvailability` is a separate preload for trailer/recap availability — it does not trigger the episode metadata fetch. Episode metadata is initiated by `loadEpisodeMetadataAsync()`, which is called from the initial metadata load path (`applyMetaWithEnrichment`) and on metadata updates, not on every season selection.

The episode metadata map is fetched once per meta-load for all seasons simultaneously (via `seasonNumbers.flatMap { ... }` inside `fetchTvEpisodeEnrichment`), so changing the season tab does not re-fetch; it filters the already-populated `videos` list.

---

## 4. Episode Metadata Fetch Path (Policy-Compliant)

**Entry:** `MetaDetailsViewModel.applyTvEpisodeEnrichment()` → `MetadataRouterFacade.fetchTvEpisodeEnrichment()`

**File:** `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:411`

```kotlin
val episodeMetadata = tvRequest.seasonNumbers
    .ifEmpty { listOfNotNull(metadataRequest.seasonNumber) }
    .ifEmpty { listOf(1) }
    .flatMap { seasonNumber ->
        val seasonRoute = resolvedBaseRoute.copy(seasonNumber = seasonNumber)
        val plan = providerPlanExecutor.buildPlan(seasonRoute, MetadataDepth.SEASON)
        providerPlanRunner.run(plan).stepResults.flatMap { stepResult ->
            stepResult.episodeMetadata.entries
        }
    }
    .associate { it.toPair() }
```

The plan executor at depth `SEASON` produces a step with `apiShapeId = TvdbApiShapes.SERIES_EPISODES_LANGUAGE`. `TvdbMetadataProviderAdapter` handles this step.

---

## 5. Per-Episode Network Call Strategy

**File:** `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt:480`

`fetchLocalizedSeasonEpisodeBundle()` issues calls in this order:

1. **English batch fetch** (`SERIES_EPISODES_LANGUAGE` shape, `language = "eng"`): fetches the English baseline episode list via `fetchSeriesEpisodesTranslatedWithTrace()`. This is a batch endpoint (`tvdbApi.getSeriesEpisodesTranslated()`), not per-episode.

2. **Early exit — English requested:** If `policy.requestedIsFallback` (user locale collapsed to English by `TvdbLanguageMapper`), returns immediately after step 1 with no further fetches.

3. **Localized batch fetch** (`SERIES_EPISODES_LANGUAGE` shape, `language = requestedLanguage.providerCode`): fetches the localized season episode list via `fetchSeriesEpisodesTranslatedWithTrace()`. Again a batch endpoint.

4. **Per-episode translation fetch** (`EPISODE_TRANSLATION` shape): for each episode ID that is still missing either a localized title or a localized overview after the batch, `fetchEpisodeTranslationWithTrace()` issues **one network call per missing episode** up to `maxPerEpisodeTranslationFallbacksPerRequest = 8` (via `idsMissingLocalizedFields()` which sorts then truncates). These are individual calls to `tvdbApi.getEpisodeTranslation()`.

**Verdict: hybrid — batch endpoint first, then per-episode calls for misses, capped at 8.**

All three call types use `IntegrationCachePolicy.CacheFirst` with language-scoped cache keys including the policy version token.

---

## 6. Finding T-01: Dual Code Path — Policy-Compliant vs Legacy

**Severity:** MEDIUM (pre-existing, documented as E-04 in Lane E)

The season tab can traverse two entirely different TVDB localization code paths depending on the caller context:

**Path A (policy-compliant):** `MetadataRouterFacade.fetchTvEpisodeEnrichment()` → `ProviderPlanRunner` → `TvdbMetadataProviderAdapter` → `TvdbIntegrationProvider.fetchLocalizedSeasonEpisodeBundle()`. Uses `LocalizationPolicy.tvdb()`, `LocalizationResolver`, emits `metadata.localization_plan` and per-episode `metadata.field_selected`, enforces the fallback cap, populates `LocalizedEpisodeBundle`.

**Path B (legacy):** `TvMetadataRouter.fetchSeasonEpisodes()` → `TvdbMetadataService.fetchSeasonEpisodes()`. Does not construct `LocalizationPolicy`. Calls `fetchTranslatedSeasonEpisodeOverviews()` which has a hardcoded `if (language == "eng") return emptyMap()` early-exit. Calls `fetchPerEpisodeTranslationOverviews()` which has `if (language == "eng" || episodeIds.isEmpty()) return emptyMap()`. Populates only `overview` strings — no titles. Emits no `metadata.localization_plan` or `metadata.field_selected` events.

Path B is reachable from `MetadataRouterFacade.fetchTvSeasonEpisodes()` (used in `markSeasonWatched`) and from the legacy `TvMetadataRouter.fetchEpisodeEnrichment()` path.

The season tab episode display in the detail screen UI uses Path A. The `markSeasonWatched` action uses Path B via `fetchTvSeasonEpisodes`. The two paths can produce different episode title/overview values for the same content.

---

## 7. Finding T-02: Recent Fixes — What Changed and What Remains

### Commit `1419bb608` — "fix(tvdb): apply translated episode titles, not just overviews"

This commit touched `TvdbMetadataService.kt` (Path B). It renamed `fetchTranslatedSeasonEpisodeOverviews` → `fetchTranslatedSeasonEpisodeRecords` and `fetchPerEpisodeTranslationOverviews` → `fetchPerEpisodeTranslationRecords`, extending the return type from `Map<Int, String>` (overview only) to `Map<Int, TvdbTranslationRecord>` (name + overview). The `toEpisodeMetadata()` helper was updated to accept both `translation` and `fallbackTranslation` and to apply `translatedTitle = translation?.name ?: fallbackTranslation?.name`. It also removed the `if (language == "eng") return emptyMap()` early-exits from `fetchTranslatedSeasonEpisodeRecords`, enabling English-language record fetching.

**Net effect on Path B (TvdbMetadataService):** Episode titles are now applied from translations, not only overviews. An English-language baseline fetch is now attempted for episodes that have no localized translation, supplying an English fallback title/overview.

**Note:** Path A (`TvdbIntegrationProvider` / `TvdbEpisodeLocalization`) was already applying titles through `LocalizationResolver`. This fix brings Path B closer to parity with Path A on the title field, but the two paths still diverge on policy object construction, trace emission, and fallback cap enforcement.

### Commit `14917f00b` — "fix(tvdb): fetch english series translation when canonical record is non-english"

This commit touched `TvdbMetadataService.kt` (Path B), removing a guard in `fetchSeriesTranslationOverview()`:

```kotlin
// Before:
if (language == "eng") return null

// After:
// guard removed
```

This fix allows `fetchSeriesTranslationOverview()` to be called with `language = "eng"`. The scenario: when a TVDB series has a non-English canonical record (i.e., the primary record stores a Japanese name), requesting the English translation was being short-circuited and returning `null`. The series title would then fall back to the canonical non-English name. After this fix, the English translation is fetched explicitly when requested.

**Scope:** `TvdbMetadataService` series-level translation only. This is a series title/overview field, not episode-level.

### Commit `aec9a1f58` — "test(tvdb): cover english fallback for missing user-locale episode translations"

This commit added 81 lines of tests to `TvdbMetadataServiceTest.kt`. It covers the scenario where user-locale episode translations are missing and English translations must be fetched as fallback. These tests exercise Path B (`TvdbMetadataService`), validating that English fallback records are now fetched for episodes without a localized translation after the `1419bb608` refactor.

**Gap:** The tests exercise `TvdbMetadataService.fetchSeasonEpisodes()` directly. There are no tests added that verify the equivalent behavior in `TvdbIntegrationProvider.fetchLocalizedSeasonEpisodeBundle()` (Path A). Path A already had English-fallback behavior via `idsMissingLocalizedFields` + per-episode translation fetch, but `aec9a1f58` does not add integration tests for Path A's English fallback specifically for the case where the canonical series record is non-English (the scenario fixed by `14917f00b`).

---

## 8. Finding T-03: F-E-01 — Fallback Counter Now Surfaced (Closed)

**File:** `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt:84–94`

At SHA `774a540f8`, `LocalizedEpisodeBundle.perEpisodeTranslationFallbacksAttempted` is populated by `TvdbEpisodeLocalization.mergeEnglishBaseBundle()` at line 78 (`perEpisodeTranslationFallbacksAttempted = missingFallbackIds.size`) and surfaced in the second `emitLocalizationPlan` call inside the `SERIES_EPISODES_LANGUAGE` branch of `TvdbMetadataProviderAdapter.execute()`:

```kotlin
traceEvents.emitLocalizationPlan(
    ...
    perEpisodeFallbacksAttempted = bundle.perEpisodeTranslationFallbacksAttempted,
    perEpisodeFallbacksAllowed = bundle.maxPerEpisodeTranslationFallbacksAllowed
)
```

**Status: Confirmed CLOSED.**

Residual observation (also noted as E-06 in Lane E): the `execute()` method emits `localization_plan` twice for `SERIES_EPISODES_LANGUAGE` — once eagerly at the top with `perEpisodeFallbacksAttempted = 0`, and once after bundle assembly with the real count. The first emission always shows zero. A trace consumer reading only the first event will misread the counter as zero even when fallbacks occurred.

---

## 9. Finding T-04: F-E-03 — Per-Episode `field_selected` Emission (Closed)

**File:** `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt:108–133`

The `SERIES_EPISODES_LANGUAGE` branch loops over every `(season, episode)` pair in the resolved bundle and calls `traceEvents.emitFieldSelected()` for each field (`title`, `overview`) that has a winner in `localizedEpisode.fieldSources`. The content ID is tagged `"tvdb:{tvdbId}:s{season}e{episode}"`. The `ownershipRule` is `"localization-resolver: {fallbackRole.name}"`.

Emission is driven by `LocalizedEpisodeFieldSource.fallbackRole`, which records whether the winning candidate came from `LOCALIZED` (per-episode translation or localized batch), `LANGUAGE_FALLBACK` (English fallback), or another role.

**Status: Confirmed CLOSED for TVDB Path A.**

The emission only fires for episodes where `localizedEpisode.fieldSources` is non-empty, which requires a winner from `LocalizationResolver.selectField()`. Episodes for which `selectField()` returns `null` (all candidates have null or placeholder values) produce no `fieldSources` entry and therefore no `field_selected` event. This is a silent hole: an episode with no title and no overview in any language will not appear in the trace for field decisions. This is not a new finding at this SHA but worth noting for the per-episode path.

---

## 10. Finding T-05: F-E-04 — Kitsu Localized Fetch (Confirmed — English Fallback Non-Contractual)

**File:** `app/src/main/java/com/nexio/tv/data/integration/metadata/LocalizationPolicy.kt:66–83`

`LocalizationPolicy.kitsu()` sets `fallbackLanguageEmbeddedInResponse = true` and `maxPerEpisodeTranslationFallbacksPerRequest = 0`. This correctly documents that the Kitsu API is expected to carry both languages in a single response (`attributes.titles` map).

**For series-level fields (ANIME_CORE):** `selectKitsuTitleField()` in `MetadataAdapterCandidates.kt` constructs four candidates from the embedded `titles` map. If `titles["en"]` is present, it wins as `LANGUAGE_FALLBACK` when the requested language is absent. The English title fallback is structurally possible from the single response.

**For episode-level fields (ANIME_EPISODES / KitsuMetadataService):** `KitsuMetadataService.fetchEpisodeEnrichment()` reads `attributes.canonicalTitle` and `attributes.synopsis` directly without applying `LocalizationPolicy` or `LocalizationResolver`. There is no per-episode language field selection — the `canonicalTitle` is used regardless of user language. `fallbackLanguageEmbeddedInResponse = true` does not enforce the contract at the episode level because episode metadata does not pass through `LocalizationResolver` on the Kitsu path.

**Contractual gap (F-E-04, confirmed):** The `LocalizationPolicy.kitsu()` `fallbackLanguageEmbeddedInResponse` flag is an assertion about API shape. For series-level title/synopsis it is enforced by `selectKitsuTitleField()`. For per-episode titles and overviews it is not enforced — `canonicalTitle` is used unconditionally and there is no per-episode language selection in either the `KitsuMetadataService` path or the `KitsuMetadataProviderAdapter` `ANIME_EPISODES` branch (which returns `emptyCandidate` with no field resolution).

**Status: Flag is documented, but per-episode English fallback contract is unenforced at the episode level.**

---

## 11. Data Flow Diagram

```
User presses D-pad select on season tab
    │
    ▼
SeasonTabs.onSeasonSelected(season)         [EpisodesSection.kt:193]
    │
    ▼
viewModel.onEvent(OnSeasonSelected(season)) [MetaDetailsScreen.kt:633]
    │
    ▼
selectSeason(season)                        [MetaDetailsViewModel.kt:1735]
    ├── withManualSeasonSelection(season)    [UI state update — filters videos by season]
    └── preloadSeasonMediaAvailability()     [trailer/recap only, no episode metadata]

Episode metadata (loaded once on meta-load, not on season change):
    │
    ▼
loadEpisodeMetadataAsync()                  [MetaDetailsViewModel.kt:796]
    │
    ▼
applyTvEpisodeEnrichment()                  [MetaDetailsViewModel.kt:1637]
    │
    ▼
MetadataRouterFacade.fetchTvEpisodeEnrichment()  [MetadataRouterFacade.kt:411]
    │
    ▼
ProviderPlanRunner.run(plan) per season
    │ plan step: SERIES_EPISODES_LANGUAGE
    ▼
TvdbMetadataProviderAdapter.execute()       [TvdbMetadataProviderAdapter.kt:76]
    ├── emitLocalizationPlan() [initial, perEpisodeFallbacksAttempted=0]  ← F-E-01 early
    │
    ▼
TvdbIntegrationProvider.fetchLocalizedSeasonEpisodeBundle()
    ├── English batch fetch (tvdb.series.episodes.language, lang=eng) → CacheFirst
    ├── [exit if requestedIsFallback]
    ├── Localized batch fetch (tvdb.series.episodes.language, lang=requested) → CacheFirst
    ├── idsMissingLocalizedFields() → episode IDs missing title OR overview
    └── Per-episode fetch (tvdb.episode.translation, per ID, max 8) → CacheFirst [SERIAL]
    │
    ▼
TvdbEpisodeLocalization.mergeEnglishBaseBundle()
    ├── LocalizationResolver.selectField(TITLE) per episode
    ├── LocalizationResolver.selectField(OVERVIEW) per episode
    └── LocalizedEpisodeBundle(perEpisodeTranslationFallbacksAttempted=N)
    │
    ▼
Back in TvdbMetadataProviderAdapter:
    ├── emitLocalizationPlan() [with real perEpisodeFallbacksAttempted]  ← F-E-01 surfaced
    └── forEach episode: emitFieldSelected(title), emitFieldSelected(overview)  ← F-E-03
```

---

## 12. Network Call Characterization

| Fetch type | Endpoint style | Call count per season selection | Notes |
|---|---|---|---|
| English episode baseline | Batch (`getSeriesEpisodesTranslated`) | 1 per season | Always fetched; `CacheFirst` |
| Localized episode batch | Batch (`getSeriesEpisodesTranslated`) | 1 per season (skipped if `requestedIsFallback`) | `CacheFirst` |
| Per-episode translation | Per-ID (`getEpisodeTranslation`) | 0–8 per season (capped) | Serial; `CacheFirst`; only for episodes missing localized title OR overview |

The per-episode calls are serial (sequential `forEach`), not parallel. For a season with 8 episodes all lacking localized translations, that is up to 8 sequential network calls after the two batch fetches. The cap prevents runaway calls for large seasons (24-episode series would only attempt fallbacks for the 8 lowest episode IDs, in sorted order per F-E-05).

---

## 13. Path-Specific Findings Summary

| ID | Severity | Summary |
|---|---|---|
| T-01 | MEDIUM | Two TVDB localization code paths coexist for episodes: Path A (policy-compliant, TvdbIntegrationProvider) used for season tab display; Path B (legacy TvdbMetadataService) used in markSeasonWatched. Different trace coverage, different title behavior prior to commit 1419bb608. Cross-reference: E-04. |
| T-02 | INFO | Commits 14917f00b and 1419bb608 fixed title application and English series-translation suppression in Path B (TvdbMetadataService) only. aec9a1f58 tests cover Path B. Path A had equivalent behavior via LocalizationResolver already; no new coverage was added for Path A's non-English canonical series record scenario. |
| T-03 | INFO | F-E-01 confirmed closed: perEpisodeTranslationFallbacksAttempted is populated and surfaced via the second emitLocalizationPlan call. The initial emission at adapter entry always shows 0 (see E-06 in Lane E). |
| T-04 | INFO | F-E-03 confirmed closed for TVDB Path A: per-episode emitFieldSelected fires for each episode field winner. Episodes with no winning candidate (all-null/placeholder) emit no field_selected event — a silent hole for episodes with no available translations. |
| T-05 | LOW | F-E-04: Kitsu fallbackLanguageEmbeddedInResponse=true is enforced for series-level title field selection (selectKitsuTitleField reads titles map). For per-episode titles and overviews, canonicalTitle is used unconditionally without language resolution — English fallback contract is unenforced at episode granularity. Cross-reference: E-04, E-05. |

---

## 14. Checklist

| Question | Answer |
|---|---|
| Entry point confirmed | Yes: `SeasonTabs.onSeasonSelected` → `OnSeasonSelected` event → `selectSeason()` |
| Per-episode fetch: batch or per-episode? | Hybrid: two batch fetches (English + localized), then per-episode for misses (serial, capped at 8) |
| F-E-01 counter surfaced? | Yes — via second `emitLocalizationPlan` after bundle assembly |
| F-E-03 per-episode `field_selected` emitted? | Yes — for each episode/field pair with a non-null winner in TVDB Path A |
| F-E-04 Kitsu English fallback contractual? | Partially — enforced for series title; unenforced for per-episode title/overview |
| Recent commits (14917f00b, 1419bb608, aec9a1f58) read | Yes — fixes apply to Path B (TvdbMetadataService); Path A was already correct |
