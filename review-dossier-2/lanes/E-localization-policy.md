# Lane E — Localization Policy and Per-Provider Language Fallback

**Review SHA:** `774a540f8`  
**Date:** 2026-04-29  
**Reviewer:** Architecture Review (automated dossier)

---

## 1. Scope

This lane covers the localization policy contract across all three primary metadata providers (TVDB, TMDB, Kitsu) and the supporting infrastructure: `LocalizationPolicy`, `LocalizationResolver`, `TvdbEpisodeLocalization`, `LocalizedEpisodeBundle`, the per-provider localized fetch sites, image cache language enforcement, trace events (`metadata.localization_plan`, `metadata.field_selected`), and the trace-audit validator rule `LocalizationPlanPrecedesProviderSteps`.

Primary files examined:

- `app/src/main/java/com/nexio/tv/data/integration/metadata/LocalizationPolicy.kt`
- `app/src/main/java/com/nexio/tv/data/integration/metadata/LocalizationResolver.kt`
- `app/src/main/java/com/nexio/tv/data/integration/metadata/LocalizationModels.kt`
- `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbEpisodeLocalization.kt`
- `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt`
- `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt`
- `app/src/main/java/com/nexio/tv/data/integration/metadata/TmdbMetadataProviderAdapter.kt`
- `app/src/main/java/com/nexio/tv/data/integration/metadata/KitsuMetadataProviderAdapter.kt`
- `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt`
- `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt`
- `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt`
- `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`
- `app/src/main/java/com/nexio/tv/core/trace/TraceValidationRules.kt`
- `app/src/main/java/com/nexio/tv/core/image/ArtworkImageCacheKeys.kt`
- `app/src/main/java/com/nexio/tv/core/integration/IntegrationScope.kt`
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbLanguageMapper.kt`
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`
- `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorLocalizationPlanRuleTest.kt`
- `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorRealEmissionTest.kt`
- `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbEpisodeLocalizationDeterministicTruncationTest.kt`

---

## 2. Architecture Summary

### 2.1 LocalizationPolicy

`LocalizationPolicy` is a data class with three static factory methods: `tvdb()`, `tmdb()`, `kitsu()`. Each builds a per-provider policy containing:

- `requestedLanguage` / `fallbackLanguage` as `NormalizedLanguage` (holds both the original tag and the provider-specific code)
- `provider` (typed as `MetadataPrimaryProvider` — ensures no cross-provider construction)
- `policyVersion` (currently `CURRENT_VERSION = 2`, embedded in all cache keys)
- `allowProviderFallbackForMissingLocalizedFields = false` for all three providers
- `maxPerEpisodeTranslationFallbacksPerRequest` — only TVDB uses a non-zero cap (`DEFAULT_PER_EPISODE_TRANSLATION_FALLBACK_CAP = 8`); TMDB and Kitsu hard-code 0
- `fallbackLanguageEmbeddedInResponse` — only Kitsu sets this to `true`, indicating the response's `attributes.titles` map is expected to carry both languages

The `requestedIsFallback` convenience property short-circuits to `true` when the requested language normalizes to the same provider code as the fallback (e.g. English requested → TVDB `eng` == `eng`).

`languageChain()` returns a list with one or two elements depending on `requestedIsFallback`.

### 2.2 LocalizationResolver

A stateless singleton that operates on `LocalizedFieldCandidate` lists. Priority ordering (lower is better):

| Priority | Condition |
|---|---|
| 0 | Same provider, `LOCALIZED`, requested language |
| 1 | Same provider, `LANGUAGE_FALLBACK`, fallback language |
| 2 | Same provider, `CANONICAL` |
| 3 | Same provider, `ADDON_FALLBACK` |
| 50 | Same provider, unexpected role |
| 100 | Different provider (always last) |

Candidates with a non-provider-matching `provider` field receive priority 100 and are automatically tagged `cross_provider_fallback_not_allowed_for_missing_localized_field` in the rejection log. There is no code path that allows a cross-provider winner when `allowProviderFallbackForMissingLocalizedFields = false`.

### 2.3 TVDB Localization Path

Three layers:

1. **Series translation** (`SERIES_TRANSLATION`): `TvdbIntegrationProvider.fetchSeriesTranslationWithTrace()` wraps an `IntegrationSpec` with `CacheFirst` TTL (24 h / stale 7 d). The English fallback and the requested language are each fetched via the same `IntegrationSpec` mechanism, so both are independently cached and network-suppressed on fresh HIT.

2. **Season episode batch** (`SERIES_EPISODES_LANGUAGE`): `fetchSeriesEpisodesTranslatedWithTrace()` fetches the English baseline (`policy.fallbackLanguage.providerCode = "eng"`) first, then the requested-language batch, both as separate `IntegrationSpec` calls.

3. **Per-episode translation** (`EPISODE_TRANSLATION`): `fetchEpisodeTranslationWithTrace()` fetches individual episode translations for IDs returned by `idsMissingLocalizedFields()`. These are also `CacheFirst`.

`TvdbEpisodeLocalization.mergeEnglishBaseBundle()` assembles all three layers via `LocalizationResolver.selectField()` for both TITLE and OVERVIEW per episode.

### 2.4 TMDB Localization Path

`TmdbMetadataProviderAdapter` constructs `LocalizationPolicy.tmdb()`, emits `localization_plan`, then calls `TmdbIntegrationProvider.fetchMovieCore()` / `fetchTvCore()` / `fetchTvSeasonEpisodes()` in both the requested language and the English fallback. Field selection for MOVIE_CORE and TV_CORE goes through `buildTmdbLocalizedCandidate()` which calls `LocalizationResolver.selectField()`. For SEASON_EPISODES, `TmdbSeasonResponse.toEpisodeMetadata()` calls `LocalizationResolver.selectField()` per episode pair.

TMDB images always use `include_image_language=en,null` hardcoded in the Retrofit interface, independent of the display language.

### 2.5 Kitsu Localization Path

`LocalizationPolicy.kitsu()` normalizes the language to its ISO base (`"en_jp"` → `"en"`, `"fr-FR"` → `"fr"`) and sets `fallbackLanguageEmbeddedInResponse = true`. The `selectKitsuTitleField()` function in `MetadataAdapterCandidates.kt` constructs four candidates from the `titles` map (requested-code variant, English variant, `canonicalTitle`, `romanizedTitle`) and passes them to `LocalizationResolver.selectField()`.

For synopsis/overview, `selectKitsuSynopsisField()` unconditionally sets the LOCALIZED candidate value to `null`, meaning the LANGUAGE_FALLBACK candidate (`synopsis` field) always wins. This is intentional: Kitsu provides a single `synopsis` field with no per-language variant.

Neither Kitsu episodes nor Kitsu ANIME_CORE make a second network request for a fallback language; the single API response includes the full `titles` map.

### 2.6 Image Cache Language Enforcement

`ArtworkImageCacheKeys.build()` hardcodes `imageLang:en` in every key (line 37). The `IntegrationScope.GlobalEnglishImage` scope stores `global:image:lang:en`. `ProfileBoundaryEnforcer.validateImageCacheKey()` rejects any image cache key where `imageLang:` is followed by a value other than `en`. `TraceValidationRules.ImageKeyUsesEnglish` also validates this at the trace-audit level.

### 2.7 Trace Events

`TraceMetadataEvents.emitLocalizationPlan()` emits `metadata.localization_plan` with fields: `contentId`, `provider`, `policyVersion`, `requestedLanguage`, `fallbackLanguage`, `requestedIsFallback`, `allowProviderFallbackForMissingLocalizedFields`, `perEpisodeFallbacksAttempted`, `perEpisodeFallbacksAllowed`.

`TraceMetadataEvents.emitFieldSelected()` emits `metadata.field_selected` with `contentId`, `field`, `selectedProvider`, `sourceRole`, `valuePreview`, `ownershipRule`, `rejectedCandidates`.

---

## 3. Data Flow

```
MetadataRoute.language
    └─► LocalizationPolicy.tvdb/tmdb/kitsu()
            ├─► NormalizedLanguage(requested, fallback)
            ├─► emitLocalizationPlan()  [F-E-02]
            └─► Provider fetch layer
                    ├─► English fetch (CacheFirst)
                    ├─► Requested-language fetch (CacheFirst, skip if requestedIsFallback)
                    ├─► Per-episode fallback fetch (TVDB only, CacheFirst)
                    └─► LocalizationResolver.selectField()
                            ├─► SelectedLocalizedField (winner + rejections)
                            ├─► LocalizedEpisodeBundle (counter)  [F-E-01]
                            └─► emitFieldSelected() per episode field  [F-E-03]
```

---

## 4. Cache Key Analysis

| Provider | Key template | Language token |
|---|---|---|
| TVDB series translation | `tvdb:series:{id}:translation:{lang}:policy:{ver}` | Provider code (e.g. `eng`, `spa`) |
| TVDB episodes language | `tvdb:series:{id}:episodes:{type}:{lang}:season:{s}:page:{p}:policy:{ver}` | Provider code |
| TVDB episode translation | `tvdb:episode:{id}:translation:{lang}:policy:{ver}` | Provider code |
| TMDB movie/tv core | `tmdb:movie:{id}:{lang}:core:{token}:policy:{ver}` | Normalized locale (e.g. `en-US`, `fr`) |
| TMDB season episodes | `tmdb:tv:{id}:season:{s}:episodes:{lang}:policy:{ver}` | Normalized locale |
| Kitsu enrichment | `kitsu:{kind}:{rawId}:enrichment` | **None** — policy version not in key |
| Image (all) | `artwork:{provider}:{type}:{itemId}:imageLang:en:policy:1` | Hardcoded `en` |

The Kitsu cache key omits both language and policy version. This is consistent with `fallbackLanguageEmbeddedInResponse = true` (single fetch, no language variant), but means a `policyVersion` bump does not invalidate Kitsu enrichment. This was noted as an accepted trade-off for F-E-04.

---

## 5. Test Coverage

| Test | Status | Covers |
|---|---|---|
| `RuntimeTraceValidatorLocalizationPlanRuleTest` (3 tests) | PASS | `LocalizationPlanPrecedesProviderSteps` rule mechanics |
| `TvdbEpisodeLocalizationDeterministicTruncationTest` | PASS | F-E-05: sort-before-truncate |
| `TvdbEpisodeLocalizationTest` | Present (not in audit XML) | Per-episode merge and per-episode translation priority |
| `TvdbCoreLocalizationTest` | Present (not in audit XML) | Series-level title/overview field selection |
| `LocalizationResolverTest` | Present (not in audit XML) | Priority ordering and cross-provider rejection |
| `KitsuLocalizationPolicyTest` | Present (not in audit XML) | Kitsu language normalization |
| `TmdbLocalizationPolicyTest` | Present (not in audit XML) | TMDB language normalization |
| `TvdbMetadataProviderAdapterEpisodeFieldSelectedTraceTest` | Present | F-E-03 emission |
| `RuntimeTraceValidatorRealEmissionTest` | PASS | End-to-end schema drift; **does not emit TVDB/TMDB/Kitsu route_decision** |

The `generateTraceValidatorAudit` task XML shows only 3 tests in `RuntimeTraceValidatorLocalizationPlanRuleTest` and 0 failures. The `RuntimeTraceValidatorRealEmissionTest` passes but does not exercise the `LocalizationPlanPrecedesProviderSteps` rule against a real TVDB/TMDB/Kitsu `route_decision` + `localization_plan` + `provider_plan` sequence (see Finding E-03).

---

## 6. Cluster D Task State (verified at SHA 774a540f8)

| Finding | Status | Evidence |
|---|---|---|
| **F-E-01** — per-episode fallback counter in trace | **CLOSED** | `LocalizedEpisodeBundle.perEpisodeTranslationFallbacksAttempted` populated at `TvdbEpisodeLocalization.kt:78`; surfaced via `emitLocalizationPlan` at `TvdbMetadataProviderAdapter.kt:92` |
| **F-E-02** — `metadata.localization_plan` emission site | **CLOSED** | `emitLocalizationPlan` called in TVDB (lines 30, 84), TMDB (line 34), Kitsu (line 35) of each adapter |
| **F-E-03** — `metadata.field_selected` per-episode | **CLOSED** | `emitFieldSelected` loop in `TvdbMetadataProviderAdapter.kt:109–133`; TMDB and Kitsu explicitly documented as having no per-(episode, field) decisions |
| **F-E-04** — Kitsu single-language embedded response | **CLOSED** | `LocalizationPolicy.kitsu()` sets `fallbackLanguageEmbeddedInResponse = true`; `selectKitsuTitleField` reads from `titles` map with key normalization |
| **F-E-05** — `idsMissingLocalizedFields` truncates before sorting | **CLOSED** | `TvdbEpisodeLocalization.kt:107` calls `.sorted()` then `.take()`; verified by `TvdbEpisodeLocalizationDeterministicTruncationTest` |

---

## 7. Findings

### E-01 — MEDIUM: `TvdbLanguageMapper` silently collapses unsupported locales to English

**File:** `app/src/main/java/com/nexio/tv/core/tvdb/TvdbLanguageMapper.kt`

`TvdbLanguageMapper.normalize()` maps only 7 language families (English, Spanish, French, German, Dutch, Simplified Chinese, Traditional Chinese). Any other locale — Italian, Portuguese, Polish, Russian, Korean, Arabic, Japanese, and dozens more — falls through the `else` branch and returns `"eng"`. This makes `LocalizationPolicy.tvdb()` produce `requestedIsFallback = true` for those locales.

The consequence is that a user with display language set to Italian will receive English episode titles and overviews for TVDB content, with no localization attempt and no per-episode fetch. The policy silently degrades without surfacing a diagnostic event for unsupported locales. There is no trace field indicating `collapsed_to_fallback_unsupported_locale`.

**Recommendation:** Add a `isCollapsedToFallback: Boolean` field to `NormalizedLanguage` or emit a `metadata.localization_plan` field (e.g. `localeCollapsedToFallback: true`) when `normalize()` triggers the `else` branch. Alternatively, expand `TvdbLanguageMapper` to pass through ISO-639-3 codes for all TVDB-supported languages rather than whitelisting seven.

---

### E-02 — LOW: Kitsu enrichment cache key omits `policyVersion`

**File:** `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt`

The Kitsu `ANIME_CORE` cache key is `kitsu:{kind}:{rawId}:enrichment`. Unlike TVDB and TMDB paths (which embed `policy:N`), there is no policy version token. A future `CURRENT_VERSION` bump (currently `2`) would invalidate TVDB and TMDB caches but not Kitsu enrichment. A profile that switches display language also would not re-fetch Kitsu data because the key is also language-free.

The absence of a language token is intentional (`fallbackLanguageEmbeddedInResponse = true`, single fetch). However, the absence of a `policyVersion` token means policy migrations that change field selection logic (e.g. a new `FallbackRole` priority) cannot be propagated by bumping `CURRENT_VERSION`.

**Recommendation:** Add `policy:$policyVersion` to the Kitsu enrichment cache key, analogous to the TVDB and TMDB patterns. The cost is a one-time cache miss on the next policy bump.

---

### E-03 — MEDIUM: `RuntimeTraceValidatorRealEmissionTest` does not exercise `LocalizationPlanPrecedesProviderSteps` with real production events

**File:** `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorRealEmissionTest.kt`

The real-emission end-to-end test drives `MetadataRouter.route()` for a `kitsu:7442` content ID, which emits `metadata.route_decision` with `provider = KITSU`. However, it does not drive any `MetadataProviderAdapter.execute()` call, so no `metadata.localization_plan` or `metadata.provider_plan` events are emitted in this session. The `LocalizationPlanPrecedesProviderSteps` validator rule therefore sees a `route_decision` for KITSU but no subsequent `provider_plan`, so it never triggers its failure condition.

The `RuntimeTraceValidatorLocalizationPlanRuleTest` tests the rule logic correctly in isolation (using synthetic events). The gap is that schema drift between `emitLocalizationPlan`'s payload key names (e.g. `provider`) and the rule's lookup (`(map(next)["provider"] as? String)?.uppercase()`) is not caught by the end-to-end test. If `emitLocalizationPlan` were to rename the `"provider"` key, the isolation tests would still pass and the real-emission test would still pass, but the validator rule would silently stop matching plans.

**Recommendation:** Add a test scenario in `RuntimeTraceValidatorRealEmissionTest` (or a dedicated test) that emits a synthetic `metadata.route_decision` for TVDB/TMDB/KITSU without an intervening `localization_plan`, asserts `FAIL`, then emits one with the plan and asserts `PASS`. This catches payload key name drift.

---

### E-04 — LOW: `TvdbMetadataService` is a parallel localization path that bypasses `LocalizationPolicy`

**Files:**  
`app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`  
`app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt`

`TvdbMetadataService` is a legacy service used by `TvMetadataRouter` (for episode enrichment and series enrichment via `ProviderLocalizedMetadataResolver`'s fallback path). It calls `TvdbLanguageMapper.normalize()` directly (line 646) and uses `provider.fetchSeriesTranslation()` (not `fetchSeriesTranslationWithTrace()`). It does not call `LocalizationPolicy.tvdb()`, does not emit `metadata.localization_plan`, and does not route through `LocalizationResolver`.

This is the Stage 2 surprise noted in the existing context: TVDB has two localization paths. The new adapter path (`TvdbMetadataProviderAdapter`) is policy-compliant and fully instrumented. The legacy service path (`TvdbMetadataService`) is a parallel implementation with its own language normalization and no trace instrumentation.

`TvMetadataRouter.fetchSeasonEpisodes()` calls `tvdbMetadataService.fetchSeasonEpisodes()` (line 193) which ultimately calls `fetchSeriesTranslationOverview()` without `LocalizationPolicy`. There is no architecture test preventing `TvdbMetadataService` from diverging further from the policy contract.

**Recommendation:** Either (a) migrate `TvdbMetadataService` to construct and pass a `LocalizationPolicy` object so language normalization and `requestedIsFallback` logic is shared, or (b) add a deprecation comment and an architecture test that prevents new callers of `TvdbMetadataService`'s language-taking methods from being introduced outside `TvMetadataRouter`. This should be formally tracked as a new finding rather than closed.

---

### E-05 — LOW: Kitsu synopsis always uses English fallback; no production trace event for this decision

**File:** `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt` (lines 331–365)

`selectKitsuSynopsisField()` always sets the LOCALIZED candidate `value = null`. The resolver therefore always selects the LANGUAGE_FALLBACK candidate (English `synopsis`) or the CANONICAL candidate (`description`). The resulting `SelectedLocalizedField` has `fallbackRole = LANGUAGE_FALLBACK`, which is correct. However, there is no production `emitFieldSelected` call for this decision; the `KitsuMetadataProviderAdapter` only calls `withLocalizationTrace()` which embeds the trace in the `MetadataCandidate.localization` map, not as a standalone trace event.

For a user browsing in French, the synopsis will always be in English. The localization payload trace records this correctly, but it is not surfaced as a `metadata.field_selected` event in the trace stream, making it invisible to the `LocalizationPlanPrecedesProviderSteps`-adjacent validator rules.

**Status:** The absence of per-field `emitFieldSelected` for Kitsu is intentional and documented in `KitsuMetadataProviderAdapter.kt` (F-E-03 comment). However, the behavior (synopsis is always English regardless of requested language) should be explicitly documented in the Kitsu contract spec, and the `fallbackLanguageEmbeddedInResponse = true` flag should carry a comment noting the synopsis limitation specifically.

**Recommendation:** Add a comment to `selectKitsuSynopsisField()` explaining that `synopsis` is a single-language field and always resolves to LANGUAGE_FALLBACK. No code change required.

---

### E-06 — INFO: `emitLocalizationPlan` is called twice for the TVDB `SERIES_EPISODES_LANGUAGE` shape

**File:** `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt` (lines 30–40 and 84–94)

When `step.apiShapeId == TvdbApiShapes.SERIES_EPISODES_LANGUAGE`, `emitLocalizationPlan` is called twice: once at the top of `execute()` (line 30, with `perEpisodeFallbacksAttempted = 0`) and once after the bundle is assembled (line 84, with the real counter from `bundle.perEpisodeTranslationFallbacksAttempted`). The first emission is emitted before the English fetch is complete, so it always reports `perEpisodeFallbacksAttempted = 0`. The second emission is the accurate one.

The `LocalizationPlanPrecedesProviderSteps` validator rule only checks that at least one `localization_plan` precedes `provider_plan`, so two emissions do not cause a validation failure. However, any consumer that reads only the first `localization_plan` event will see a zero counter even when fallbacks were attempted.

**Recommendation:** For `SERIES_EPISODES_LANGUAGE`, suppress the initial `emitLocalizationPlan` call (lines 30–40) and rely solely on the post-bundle emission (lines 84–94) which carries the accurate counter. The validator rule will still pass because the single emission precedes `provider_plan`.

---

### E-07 — INFO: `TvdbLanguageMapper` maps all English variants to `"eng"` but `LocalizationPolicy.tvdb()` fallbackLanguage uses `"eng"` — correct

Confirmed: `LocalizationPolicy.tvdb()` hardcodes `fallbackLanguage = NormalizedLanguage("en", "eng")`. `TvdbLanguageMapper.normalize("en")` returns `"eng"`. `requestedIsFallback` computes `"eng" == "eng"` = `true` when the user's language normalizes to English. TVDB is then fetched only once (English baseline), and no second fetch is attempted. This is correct and the logic is consistent.

---

## 8. Red-Flag Checklist

| Red flag | Verdict | Evidence |
|---|---|---|
| "TVDB localized missing field falls back to TMDB" | **CLEAR** | `LocalizationPolicy.allowProviderFallbackForMissingLocalizedFields = false` for all providers; `LocalizationResolver` tags cross-provider candidates at priority 100 and records `cross_provider_fallback_not_allowed_for_missing_localized_field`; `TvdbEpisodeLocalization.mergeEnglishBaseBundle()` has `check(!policy.allowProviderFallbackForMissingLocalizedFields)` guard |
| "English fallback is network-fetched even when cached" | **CLEAR** | Both series translation and episode language fetches use `IntegrationCachePolicy.CacheFirst`; the runtime reports `cacheDecision = "HIT"` on fresh cache and `executedNetwork = false`; the `FreshCacheHitSuppressesNetwork` validator rule enforces this globally |
| "Image cache varies by profile language" | **CLEAR** | `ArtworkImageCacheKeys.build()` hardcodes `imageLang:en`; `IntegrationScope.GlobalEnglishImage.storageKey = "global:image:lang:en"`; `ProfileBoundaryEnforcer.validateImageCacheKey()` rejects non-English image keys; boundary audit scenario `profile2_different_language_fetches_text_only_not_images` PASS |
| "Localization plan event has no production emission site" (F-E-02) | **CLEAR — CLOSED** | `emitLocalizationPlan` called in TVDB, TMDB, Kitsu adapters (3 production sites confirmed by grep) |
| "Per-episode field_selected silently uses series-level fallback" (F-E-03) | **CLEAR — CLOSED** | TVDB adapter emits `emitFieldSelected` per-episode at lines 117–133; TMDB/Kitsu have no per-episode field-level fallback by design (documented in code) |
| "LocalizedEpisodeBundle counter computed but not surfaced" (F-E-01) | **CLEAR — CLOSED** | Counter computed at `TvdbEpisodeLocalization.kt:78` (`missingFallbackIds.size`); surfaced in second `emitLocalizationPlan` call at `TvdbMetadataProviderAdapter.kt:92` |
| "Validator rule has no event source" | **PARTIAL GAP — see E-03** | Rule logic passes in isolation tests; `RuntimeTraceValidatorRealEmissionTest` does not exercise the rule with a full route_decision → localization_plan → provider_plan sequence |
| "Provider-scoped localization fallback (Stage 2 surprise)" | **DOCUMENTED GAP — see E-04** | `TvdbMetadataService` is a second TVDB localization path that bypasses `LocalizationPolicy`; not an active regression, but an architectural inconsistency without an enforcement boundary |
| "Kitsu single-language fetch + no English fallback contract" (F-E-04) | **CLEAR — CLOSED** | `fallbackLanguageEmbeddedInResponse = true`; `selectKitsuTitleField` reads from embedded `titles` map; no second fetch issued |
| "TvdbEpisodeLocalization truncates before sorting" (F-E-05) | **CLEAR — CLOSED** | `.sorted()` before `.take()` at line 107; confirmed by `TvdbEpisodeLocalizationDeterministicTruncationTest` asserting `listOf(10, 20, 30)` |

---

## 9. Summary

Lane E is in good operational shape. All five Cluster D findings (F-E-01 through F-E-05) are verifiably closed at SHA `774a540f8`. The `LocalizationPolicy` factory pattern is correctly applied across all three provider adapters, cross-provider field mixing is structurally prevented by `LocalizationResolver`, and the image cache language invariant is enforced at both the key-builder and profile-boundary layers.

Four findings remain open:

- **E-01 (MEDIUM):** Silent locale collapse to English for unsupported TVDB languages — no diagnostic emitted.
- **E-02 (LOW):** Kitsu enrichment cache key omits `policyVersion`, making policy bumps non-invalidating for Kitsu.
- **E-03 (MEDIUM):** `LocalizationPlanPrecedesProviderSteps` validator rule has no real-emission end-to-end coverage; schema drift between `emitLocalizationPlan` payload keys and the rule's lookups would go undetected.
- **E-04 (LOW):** `TvdbMetadataService` is a legacy parallel TVDB localization path that bypasses `LocalizationPolicy`, `LocalizationResolver`, and all trace instrumentation.

Two informational observations (E-05, E-06, E-07) require no code changes.
