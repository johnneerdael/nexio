# Phase 2: Per-Profile Auth and Settings - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase migrates 8 DataStores from singleton delegates to the ProfileDataStoreFactory pattern (built in Phase 1), making Trakt/Simkl auth and per-profile settings automatically profile-scoped. Each migrated store uses `flatMapLatest` on `profileManager.activeProfileId` so that switching profiles reactively swaps all data flows. Shared settings remain singleton. No new UI screens (that's Phase 3).

</domain>

<decisions>
## Implementation Decisions

### DataStore Classification
- **D-01:** 8 DataStores are per-profile (migrated to factory pattern): `TraktAuthDataStore`, `SimklAuthDataStore`, `TraktSettingsDataStore`, `SimklSettingsDataStore`, `PlayerSettingsDataStore`, `LayoutPreferenceDataStore`, `ThemeDataStore`, `SearchHistoryDataStore`.
- **D-02:** 18 DataStores remain shared singletons: all debrid stores (RealDebrid, Premiumize, EasyDebrid, TorBox), all API integration stores (Tmdb, Omdb, MDBList, Imdb, PosterRatings/RPDB, SubtitleTranslation), AnimeSkipSettings, TrailerSettings, YouTubeTrailerAuth, StreamLinkCache, AndroidTvRecommendations, AppOnboarding, DebugSettings.
- **D-03:** SearchHistoryDataStore is per-profile despite not being in the original "7 DataStores" requirement — each profile should have their own search history for privacy.

### Auth Switch Behavior
- **D-04:** Stop playback immediately when the user switches profiles. Clean cut — stop player, return to home screen. Prevents scrobbling to wrong account.
- **D-05:** In-flight Trakt/Simkl sync operations complete using the original profile's tokens. No data leaks between profiles. New profile's sync starts fresh after switch.
- **D-06:** Profile switching is inline via sidebar menu (not a full-screen selector). Quick, no screen transition. Matches NuvioTV pattern. (Note: sidebar UI itself is Phase 3 scope; Phase 2 provides the `ProfileManager.switchProfile()` method.)

### Shared Settings Access
- **D-07:** Non-default profiles do NOT see shared setting sections in the Settings UI at all. Hidden entirely — clean UI, no confusion. Settings screen only shows what the profile can actually change.
- **D-08:** Shared settings are readable from any profile in code. Only the Settings UI restricts editing to the default profile. Player, sync, and service logic reads shared settings (debrid tokens, API keys) without profile checks.

### Migration Ordering
- **D-09:** Auth stores first, then settings stores. Migrate the hardest case first to validate the pattern, then apply to simpler stores.
- **D-10:** Group auth + settings per service: TraktAuth + TraktSettings together as one unit, SimklAuth + SimklSettings together as one unit. These are tightly coupled.
- **D-11:** Update consuming services (TraktAuthService, SimklAuthService, ViewModels, etc.) inline with their DataStore migration. The store API change won't compile without updating consumers anyway.

### Claude's Discretion
- Internal implementation of `flatMapLatest` wiring per store (exact Flow chain structure)
- Order of the 4 remaining settings stores after auth migration (PlayerSettings, LayoutPreference, Theme, SearchHistory)
- Error handling when a profile's DataStore file is corrupted or missing
- Whether to batch the 4 settings store migrations into one plan or split into separate plans

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### NuvioTV Reference Implementation (migration source)
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/TraktAuthDataStore.kt` — Per-profile Trakt auth with flatMapLatest pattern, factory injection
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/SimklAuthDataStore.kt` — Per-profile Simkl auth with same pattern
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/TraktSettingsDataStore.kt` — Per-profile Trakt settings migration example
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/PlayerSettingsDataStore.kt` — Per-profile player settings migration example
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/profile/ProfileManager.kt` — activeProfileId StateFlow, switchProfile method

### Nexio Existing Code (migration targets)
- `app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt` — Current singleton, delegate `trakt_auth_store`
- `app/src/main/java/com/nexio/tv/data/local/SimklAuthDataStore.kt` — Current singleton, delegate `simkl_auth_store`
- `app/src/main/java/com/nexio/tv/data/local/TraktSettingsDataStore.kt` — Current singleton, delegate `trakt_settings`
- `app/src/main/java/com/nexio/tv/data/local/SimklSettingsDataStore.kt` — Current singleton, delegate `simkl_settings`
- `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt` — Current singleton, delegate `player_settings`
- `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt` — Current singleton, delegate `layout_settings`
- `app/src/main/java/com/nexio/tv/data/local/ThemeDataStore.kt` — Current singleton, delegate `theme_settings`
- `app/src/main/java/com/nexio/tv/data/local/SearchHistoryDataStore.kt` — Current singleton (newly added to per-profile scope)

### Phase 1 Foundation (dependency)
- `.planning/phases/01-foundation/01-CONTEXT.md` — ProfileDataStoreFactory decisions (D-07 bare filenames, D-08 ConcurrentHashMap pattern)
- `.planning/phases/01-foundation/01-RESEARCH.md` — Factory architecture, pitfalls, test infrastructure

### Research
- `.planning/research/ARCHITECTURE.md` — Component responsibilities, migration patterns
- `.planning/research/PITFALLS.md` — Pitfall 1 (delegate coexistence), migration gotchas

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ProfileDataStoreFactory` (Phase 1) — `get(profileId, featureName)` returns per-profile DataStore instances
- `ProfileManager` (Phase 1) — `activeProfileId: StateFlow<Int>` drives `flatMapLatest` switching
- `flatMapLatest` pattern already used in 5+ locations (MetaDetailsViewModel, TrackingScrobbleService)

### Established Patterns
- All 26 DataStores use `@Singleton` + `@Inject constructor(@ApplicationContext context: Context)` + `preferencesDataStore` delegate
- Migration pattern: replace `context.dataStore` delegate with `factory.get(profileId, featureName)` and wrap reads in `flatMapLatest`
- Consuming services (TraktAuthService, SimklAuthService) inject auth stores directly via constructor

### Integration Points
- `TraktAuthService` — primary consumer of TraktAuthDataStore, handles OAuth flow
- `SimklAuthService` — primary consumer of SimklAuthDataStore
- `TrackingScrobbleService` — reads Trakt auth state during playback
- Settings screens — need conditional visibility based on active profile (default vs non-default)
- Player — reads PlayerSettingsDataStore for playback preferences

</code_context>

<specifics>
## Specific Ideas

- Port NuvioTV's `flatMapLatest` pattern directly — the reactive switching pattern is proven and the codebase already uses `flatMapLatest`
- TraktAuth + TraktSettings grouped together as one migration unit, SimklAuth + SimklSettings as another — tight coupling means they should move together
- Playback stop on profile switch is a clean cut — no graceful handoff needed

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 02-per-profile-auth-and-settings*
*Context gathered: 2026-04-14*
