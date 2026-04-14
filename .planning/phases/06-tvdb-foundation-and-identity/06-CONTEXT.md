# Phase 6: TVDB Foundation and Identity - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 6 delivers the foundation for TVDB as a first-class TV metadata provider: TVDB settings, API key plus optional PIN validation, bearer token caching, broad remote-ID identity matching, account sync integration through the existing secret channel pattern, and observable fallback status. It does not replace TMDB TV metadata surfaces yet; provider replacement starts in Phase 7.

</domain>

<decisions>
## Implementation Decisions

### Credential Model
- **D-01:** TVDB supports API key plus optional subscriber PIN in Phase 6. The optional PIN must be accepted by the settings flow and sent to TVDB `/login` only when present.
- **D-02:** TVDB credentials must be secret-backed. The public account sync payload may carry enabled/configured state, but API key and PIN must not be stored in public sync JSON or logs.
- **D-03:** Planning should extend the existing account secret allowlist with a TVDB secret type, likely `tvdb_api_key`, and choose whether the optional PIN is stored in the same secret payload or a separate secret ref. Keep secret material out of planning docs and commits.

### Settings Shape
- **D-04:** Phase 6 should create a simple foundation settings screen: enable TVDB, API key, optional PIN, validation state, and provider-precedence copy.
- **D-05:** Do not mirror TMDB per-feature toggles in Phase 6. Surface-level controls for artwork, trailers, cast, related content, or networks belong in Phase 7+ only if provider replacement work proves they are needed.
- **D-06:** Settings copy should state that TVDB becomes the TV metadata source when configured, TMDB remains movie metadata and TV fallback when TVDB is not configured, and poster-ratings integrations remain authoritative for supported poster imagery.

### Fallback Diagnostics
- **D-07:** Phase 6 fallback observability is settings status plus logs. The TVDB settings state should distinguish not configured, validating, valid, invalid, and last failure where practical.
- **D-08:** When TVDB is active but unusable, fallback to non-TVDB behavior must log the reason and must be observable from TVDB settings. Avoid browse-time toasts or snackbars.
- **D-09:** Full provider-choice diagnostics for every TV metadata path can wait until Phase 10, but Phase 6 should create enough state/logging for planner and tests to prove fallback is not silent.

### Identity Matching Scope
- **D-10:** Phase 6 should support broad TVDB identity matching: TVDB ID, IMDb ID, TMDB remote ID, TV Maze ID, Wikidata ID, and official-site IDs when TVDB exposes them.
- **D-11:** TVDB remote IDs should be normalized and cached so a TVDB-backed TV record does not require a TMDB API call merely to identify the series.
- **D-12:** The first implementation may consume only the IDs needed by current call sites, but the identity model should preserve the broader remote-ID set for downstream provider replacement.

### Token and Foundation Cache Policy
- **D-13:** Persist TVDB bearer token with expiry metadata. Reuse it across app restarts and refresh before or after expiry instead of calling `/login` repeatedly.
- **D-14:** Foundation caching in Phase 6 should cover auth token state and identity lookup results. Deeper metadata cache invalidation using TVDB `/updates` or record timestamps is Phase 10.
- **D-15:** Token and lookup caches should follow existing in-flight de-duping patterns where possible so parallel TVDB lookups join the same request instead of creating duplicate network calls.

### the agent's Discretion
- Exact local class names and package layout for TVDB settings, API client, token store, and identity service.
- Whether optional PIN is persisted in the same secret payload as the API key or a separate secret ref, as long as it remains secret-backed and sync-compatible.
- Exact wording for settings validation and precedence copy, as long as it communicates the decided provider hierarchy.
- Exact log tag names and diagnostic state representation.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Milestone Definition
- `.planning/PROJECT.md` - Current milestone goal, provider precedence, poster-ratings precedence, and active requirements.
- `.planning/REQUIREMENTS.md` - Phase 6 requirements: PREF-01, PREF-04, PREF-05, PREF-06, CACHE-01.
- `.planning/ROADMAP.md` - Phase 6 boundary, success criteria, and dependency notes.
- `docs/brainstorms/2026-04-14-tvdb-first-class-tv-metadata-requirements.md` - Product decisions and TVDB research notes that produced this milestone.

### TVDB API Contract
- `tvdb.yml` - Checked-in TVDB OpenAPI contract. Relevant sections include `/login`, `/search`, `/search/remoteid/{remoteId}`, `/series/{id}`, `/series/{id}/extended`, `/series/{id}/episodes/{season-type}`, `SeriesBaseRecord`, `SeriesExtendedRecord`, `EpisodeBaseRecord`, `RemoteID`, and `/updates`.

### Existing Settings Patterns
- `app/src/main/java/com/nexio/tv/data/local/TmdbSettingsDataStore.kt` - Existing TMDB settings DataStore pattern.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModel.kt` - Existing API-key validation and enablement gating pattern.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt` - Existing Android TV settings UI pattern for metadata API configuration.
- `app/src/main/java/com/nexio/tv/data/local/TheIntroDbSettingsDataStore.kt` - Example of removing legacy secret-backed local API key state.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt` - Integration settings hub where TVDB entry should be added.

### Network and API Integration
- `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt` - Named Retrofit client/provider pattern used by TMDB, Trakt, Simkl, IntroDB, and other integrations.
- `app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt` - Existing Retrofit DTO/API shape for metadata provider calls.
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbService.kt` - Existing ID conversion, in-memory cache, and in-flight de-duping pattern to mirror for TVDB identity lookup.

### Account Sync and Secrets
- `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt` - Current account config contract, changed-path observer, push params, and apply helpers.
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` - Shared settings sync implementation and secret resolution patterns.
- `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt` - Serializable sync models for integrations and secret-backed settings.
- `supabase/account_settings_sync.sql` - Server-side secret-type allowlists, default payload, canonical extraction, and sync secret RPCs.
- `docs/settings/settings-sync.schema.json` - Public sync schema to update with TVDB non-secret settings.

### Prior Phase Context
- `.planning/phases/04-sync-and-cleanup/04-CONTEXT.md` - Shared vs per-profile store decisions; `MetadataDiskCacheStore` remains shared.
- `.planning/phases/05-nexio-web-integration/05-CONTEXT.md` - Existing account sync and Supabase extension context.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `TmdbSettingsDataStore`, `TmdbSettingsViewModel`, and `TmdbSettingsScreen` provide the nearest shape for TVDB settings, validation, masked API key display, and Android TV dialog behavior.
- `NetworkModule` already provides named Retrofit clients and API interfaces; TVDB should follow the same Hilt pattern.
- `TmdbService` has cache and in-flight request de-duping patterns that fit TVDB remote-ID identity lookup.
- Account sync already supports secret-backed API keys for TMDB, RPDB, Top Posters, OMDB, MDBList, translation providers, debrid providers, Trakt, and Simkl.

### Established Patterns
- Integration settings generally store public enablement/toggle state in DataStore and account sync, while secrets use the account secret channel.
- Public sync payloads include provider enabled/configured state but not raw API keys.
- `MetadataDiskCacheStore` is shared, not per-profile, based on Phase 4 context.
- Settings screen integration entries are routed through `IntegrationSettingsSection` in `SettingsScreen.kt`.

### Integration Points
- Add TVDB settings domain/data classes and DataStore.
- Add TVDB Retrofit API and named Retrofit provider in `NetworkModule`.
- Add TVDB settings UI and ViewModel under the existing settings integration hub.
- Extend account sync models, schema, change observers, push/apply paths, and Supabase secret allowlists for TVDB.
- Add a TVDB identity/token service that downstream Phase 7 code can call without importing TMDB identity lookup.

</code_context>

<specifics>
## Specific Ideas

- User selected API key plus optional PIN because TVDB `/login` supports `apikey` and optional `pin`.
- User selected a simple foundation settings screen for Phase 6, deferring TMDB-style per-surface toggles.
- User selected settings status plus logs for fallback diagnostics, not browse-time warnings.
- User selected broad remote-ID support, matching live TVDB validation that returned TMDB, IMDb, TV Maze, Wikidata, official-site, and other IDs.
- User selected persistent bearer-token caching with expiry metadata.

</specifics>

<deferred>
## Deferred Ideas

- App-level TVDB key path is deferred. Phase 6 should not assume a negotiated app-level credential.
- TMDB-style per-TVDB metadata toggles are deferred unless Phase 7 provider replacement shows they are necessary.
- Full provider-choice diagnostics across every metadata path are deferred to Phase 10.
- TVDB `/updates`-driven metadata cache invalidation is deferred to Phase 10.

</deferred>

---

*Phase: 06-tvdb-foundation-and-identity*
*Context gathered: 2026-04-14*
