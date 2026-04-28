## Why

The architecture audit (`review-dossier/09-known-gaps.md`) identified 5 cluster-C findings: localization decisions are made by `LocalizationPolicy` and `LocalizationResolver` but are invisible in trace bundles.

- **F-E-01 (P1):** `LocalizedEpisodeBundle.perEpisodeTranslationFallbacksAttempted` is computed but dropped before reaching the trace.
- **F-E-02 (P1):** `metadata.localization_plan` event has no production emission site (the helper was removed in commit `e3a3ab8d7`).
- **F-E-03 (P1):** Per-episode title/overview decisions don't emit `metadata.field_selected` — they're stored in `LocalizedEpisodeMetadata.fieldSources` then dropped.
- **F-E-04 (P2):** Kitsu's localized fetch is single-language; the English-fallback contract is implicit (relies on Kitsu's API response shape).
- **F-E-05 (Nit):** `TvdbEpisodeLocalization.idsMissingLocalizedFields` truncates before sorting — non-deterministic.

This change closes all 5.

## What Changes

### ADDED

- `TraceMetadataEvents.emitLocalizationPlan(contentId, provider, policyVersion, requestedLanguage, fallbackLanguage, requestedIsFallback, allowProviderFallbackForMissingLocalizedFields, perEpisodeFallbacksAttempted, perEpisodeFallbacksAllowed)`.
- `LocalizationPlanPrecedesProviderSteps` validator rule: every `metadata.route_decision` targeting TVDB/TMDB/Kitsu must be followed by ≥1 `metadata.localization_plan` before its first `metadata.provider_plan` step.
- `LocalizationPolicy.fallbackLanguageEmbeddedInResponse: Boolean = false` field documenting F-E-04 (default `true` for Kitsu factory).

### MODIFIED

- `TvdbMetadataProviderAdapter`, `TmdbMetadataProviderAdapter`, `KitsuMetadataProviderAdapter` emit `metadata.localization_plan` after policy construction.
- TVDB/TMDB/Kitsu adapters' per-episode loops emit `metadata.field_selected` with `contentId = "{provider}:{seriesId}:s{season}e{number}"` when title/overview winners are computed.
- `TvdbEpisodeLocalization.idsMissingLocalizedFields` sorts by id before `.take(...)` to guarantee deterministic truncation order.

## Impact

- Affected specs: `integration-runtime`.
- Affected code: 6 production files (1 trace helper + 1 validator + 3 adapter implementations + 1 policy data class) + 1 localization helper.
- Trace bundles: localization-policy state and per-episode field winners become visible. Operators reading a trace can answer "which language won for episode 4 of season 2's title?" — previously invisible.
- No new dependencies; F-E-04 is documented (option a per audit) rather than fetched (option b deferred).
