## ADDED Requirements

### Requirement: metadata.localization_plan event fires once per route to TVDB/TMDB/Kitsu

After a `metadata.route_decision` targeting TVDB / TMDB / Kitsu, exactly one `metadata.localization_plan` event MUST fire before the first `metadata.provider_plan` step. The payload MUST include `policyVersion`, `requestedLanguage`, `fallbackLanguage`, `requestedIsFallback`, `allowProviderFallbackForMissingLocalizedFields`, `perEpisodeFallbacksAttempted`, `perEpisodeFallbacksAllowed`.

#### Scenario: Dutch profile + TVDB SERIES route emits localization_plan with policy v2

- **GIVEN** the active profile language is `nld`
- **WHEN** `MetadataRouterFacade.resolveRequest(...)` runs against a TVDB SERIES route
- **THEN** the trace contains `metadata.localization_plan` with `requestedLanguage = "nld"`, `fallbackLanguage = "eng"`, `policyVersion = 2`, `perEpisodeFallbacksAllowed = 8`
- **AND** the event sequence is BEFORE the first `metadata.provider_plan` step

### Requirement: per-episode field_selected events fire for each translated title/overview

When `LocalizedEpisodeBundle` returns episodes with `fieldSources`, each adapter MUST emit one `metadata.field_selected` per episode per field with `contentId = "{provider}:{seriesId}:s{season}e{number}"` and `ownershipRule = "localization-resolver: {fallbackRole}"`.

#### Scenario: TVDB localized SERIES_EPISODES_LANGUAGE step emits per-episode field_selected

- **WHEN** `TvdbMetadataProviderAdapter.execute(route, step)` returns a `LocalizedEpisodeBundle` with 10 episodes carrying `LocalizedEpisodeFieldSource` entries
- **THEN** the trace contains exactly 20 `metadata.field_selected` events (10 episodes × 2 fields: title + overview)
- **AND** each event's `contentId` follows the `tvdb:{tvdbId}:s{season}e{number}` shape

## MODIFIED Requirements

### Requirement: TvdbEpisodeLocalization.idsMissingLocalizedFields truncation is deterministic

`idsMissingLocalizedFields` MUST sort the IDs before `.take(maxPerEpisodeTranslationFallbacksPerRequest)` to guarantee a stable subset across runs.

#### Scenario: Same input produces same truncated subset across runs

- **GIVEN** an `englishEpisodes: List<TvdbEpisodeRecord>` of 50 episodes
- **WHEN** `idsMissingLocalizedFields(...)` is called twice with identical inputs
- **THEN** both calls produce the SAME `List<Int>`
