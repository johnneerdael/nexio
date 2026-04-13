# Project Research Summary

**Project:** Nexio — Multi-Profile Support
**Domain:** Android TV streaming app — multi-profile state isolation
**Researched:** 2026-04-14
**Confidence:** HIGH

## Executive Summary

Multi-profile support for Nexio is a structural refactor of the DataStore layer plus additive UI and sync infrastructure. The critical finding across all four research dimensions is that **no new dependencies are needed** — every library required is already in the project. The work is purely architectural: replacing `preferencesDataStore` Kotlin property delegates with a `ProfileDataStoreFactory` pattern (proven in the NuvioTV reference implementation), adding per-profile reactive flows via `flatMapLatest(activeProfileId)`, and extending Supabase sync with an independent per-profile blob service.

The highest-risk component is the DataStore migration itself. The `preferencesDataStore` delegate creates a process-wide singleton that cannot coexist with factory-created instances for the same file. Profile 1 must always use bare filenames (no `_p1` suffix) to preserve zero-migration for existing users. Getting this wrong silently logs out all existing Trakt users on first update.

The NuvioTV reference implementation at `~/Scripts/NuvioTV` provides battle-tested patterns for every component. The recommended approach is to port these patterns directly rather than inventing alternatives.

## Key Findings

### Recommended Stack

No new dependencies. All required libraries are already in `libs.versions.toml`:

- **`datastore-preferences:1.1.1`** — `PreferenceDataStoreFactory.create {}` for per-profile instances
- **`hilt-android:2.58`** — `@Singleton` + `@Inject` constructors (no new `@Provides` needed)
- **`kotlinx-coroutines-core:1.8.1`** — `flatMapLatest` for profile-switching reactive flows
- **`tv-material:1.0.1`** — Profile selection screen with D-pad navigation
- **`supabase-bom:3.1.4`** — New RPCs for per-profile sync (additive, not breaking v7)
- **`moshi:1.15.1`** — Profile list JSON serialization in ProfileDataStore
- **`coil-compose:2.7.0`** — Avatar image loading from Supabase Storage URLs

**New files:** 8 (ProfileDataStoreFactory, ProfileDataStore, ProfileManager, ProfileModule, ProfileSyncService, ProfileSettingsSyncService, ProfileSelectionScreen, ProfileSelectionViewModel)
**Modified files:** 7 DataStores converted to factory pattern + UserProfile model extended

### Expected Features

**Must have (table stakes):**
- Profile selection screen gated by `profiles.size > 1` — zero friction for single-profile users
- Per-profile Trakt/Simkl OAuth — the primary reason households want profiles
- Per-profile DataStore isolation via ProfileDataStoreFactory
- Profile CRUD (max 4, protect profile 1 from deletion)
- Optional PIN lock per profile (server-side hash via Supabase)
- Settings cascade: shared (addons, debrid, integrations) vs per-profile (auth, language, theme, player, catalogs)
- Profile deletion with full cleanup (DataStore files AND SharedPreferences files)

**Should have (competitive):**
- Session-scoped profile gate (once per session, not per launch)
- Avatar catalog via nexio-web companion
- PIN rate-limiting UI (respect `retryAfterSeconds`)
- Per-profile catalog ordering
- Sidebar profile switcher (shown only when 2+ profiles)

**Defer (v2+):**
- Per-profile parental controls / content filtering
- Guest profiles (temporary, no sync)
- On-device photo upload

### Architecture Approach

The architecture follows a strict layered approach where `ProfileDataStoreFactory` is the foundation everything builds on.

**Major components:**
1. **ProfileDataStoreFactory** — `ConcurrentHashMap` cache; `get(profileId, featureName)` returns correct per-profile DataStore. Profile 1 uses bare filename (zero migration).
2. **ProfileManager** — CRUD + `activeProfileId: StateFlow<Int>`. Wraps ProfileDataStore. Max 4 profiles, ID reuse on deletion.
3. **ProfileSettingsSyncService** — Independent of v7 contract. Uses blob push/pull RPCs to Supabase keyed by `(user_id, profile_id, platform)`.
4. **Per-profile DataStores** — 7 stores migrated from delegate to factory + `flatMapLatest`. Auth services (TraktAuthService) need NO code changes — they automatically follow the active profile.

### Critical Pitfalls

1. **`preferencesDataStore` delegate + factory coexistence crashes** — Remove all delegates from per-profile stores. Two DataStore instances for the same file = `IllegalStateException`.
2. **Profile 1 must use bare filenames** — Any `_p1` suffix silently logs out all existing Trakt users on update.
3. **SharedPreferences snapshot stores invisible to factory** — `TraktLibrarySnapshotStore`, `ContinueWatchingSnapshotStore` use `getSharedPreferences` with hardcoded names. Profile deletion misses these `.xml` files.
4. **AccountConfigSyncContract v7 mixes shared and per-profile** — Push from Profile 2 overwrites Profile 1's Trakt tokens unless split into separate sync paths.
5. **Profile switch race conditions** — In-flight Trakt scrobbles during switch can land in wrong profile. Drain outbox before switching.

## Implications for Roadmap

### Phase 1: Foundation — ProfileDataStoreFactory + ProfileManager
**Rationale:** Everything depends on this. Cannot build auth, settings, or UI without the factory in place.
**Delivers:** ProfileDataStoreFactory, ProfileDataStore, ProfileManager, ProfileModule, UserProfile model extension
**Addresses:** PROF-01 (DataStore isolation), PROF-02 (profile CRUD)
**Avoids:** Pitfall 1 (delegate coexistence), Pitfall 7 (Profile 1 data migration)

### Phase 2: DataStore Migration — Convert 7 stores to per-profile
**Rationale:** Must happen before any profile-switching UX. Auth services automatically become per-profile.
**Delivers:** TraktAuthDataStore, SimklAuthDataStore, ThemeDataStore, LayoutPreferenceDataStore, PlayerSettingsDataStore, TraktSettingsDataStore, SimklSettingsDataStore all using factory + flatMapLatest
**Addresses:** PROF-04 (per-profile Trakt), PROF-05 (per-profile Simkl), PROF-06 (per-profile settings)
**Avoids:** Pitfall 2 (token isolation), Pitfall 4 (Singleton services with stale state)

### Phase 3: Profile Selection UI + Switching
**Rationale:** First user-visible feature. Requires Phase 1 (ProfileManager) and Phase 2 (per-profile DataStores) to be complete.
**Delivers:** ProfileSelectionScreen, ProfileSelectionViewModel, sidebar switcher, session-scoped gate, D-pad PIN entry
**Addresses:** PROF-03 (profile selection), PROF-08 (PIN lock)
**Avoids:** Pitfall 10 (D-pad focus loss), Pitfall 11 (single-profile gate timing), Pitfall 8 (PIN bypass)

### Phase 4: Sync Infrastructure + Cleanup
**Rationale:** Requires Supabase schema additions. Can be developed in parallel with Phase 3 UI but must land before profile sync is live.
**Delivers:** ProfileSyncService, ProfileSettingsSyncService, Supabase RPCs, profile deletion cleanup (DataStore + SharedPreferences), snapshot store audit
**Addresses:** PROF-10 (per-profile sync), PROF-11 (deletion cleanup)
**Avoids:** Pitfall 5 (v7 contract mixing), Pitfall 3 (SharedPreferences orphans), Pitfall 9 (orphaned files)

### Phase 5: nexio-web Profile Management + Polish
**Rationale:** Depends on sync infrastructure (Phase 4) and profile model being stable.
**Delivers:** nexio-web profile CRUD from master account, per-profile management (Trakt/Simkl auth, catalog ordering, formatter config), avatar photo upload, profile switch drain logic
**Addresses:** PROF-07 (photo upload), PROF-12 (nexio-web management), PROF-09 (settings cascade)
**Avoids:** Pitfall 6 (switch race conditions)

### Phase Ordering Rationale

- Foundation → DataStore migration is a strict dependency: factory must exist before stores can use it
- DataStore migration → UI is a strict dependency: profile-switching UI is meaningless without per-profile data
- Sync can be developed alongside UI but must be verified before GA
- nexio-web is last because it depends on the Supabase schema and profile model being stable
- Profile switch drain logic is in Phase 5 because it's polish — basic switching works without it

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 2:** Audit all 27+ DataStore files — classify each as shared vs per-profile
- **Phase 3:** D-pad focus management and PIN entry UX need prototype testing with physical remote
- **Phase 4:** Supabase schema design — need to confirm RPC signatures and table structures

Phases with standard patterns (skip research-phase):
- **Phase 1:** Direct port from NuvioTV — well-documented, proven code
- **Phase 5:** nexio-web is a separate surface — standard web CRUD

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All libraries already in project; versions verified from `libs.versions.toml` |
| Features | HIGH | NuvioTV reference + Netflix/Plex/Kodi/Jellyfin patterns cross-referenced |
| Architecture | HIGH | Direct inspection of both Nexio and NuvioTV source code |
| Pitfalls | HIGH | 11 specific pitfalls identified with code-level evidence |

**Overall confidence:** HIGH

### Gaps to Address

- **Supabase schema:** RPC functions and tables need to be created in the Nexio Supabase project (they exist in NuvioTV's backend but Nexio has a separate project)
- **SharedPreferences audit:** `TraktLibrarySnapshotStore`, `ContinueWatchingSnapshotStore`, `HomeCatalogSnapshotStore` need classification as shared vs per-profile
- **`BuildConfig.AVATAR_PUBLIC_BASE_URL`:** Needs adding to Nexio's `build.gradle.kts` (exists in NuvioTV)
- **Moshi vs Gson:** NuvioTV uses Moshi for ProfileDataStore JSON; Nexio uses Gson elsewhere — use Gson for consistency

## Sources

### Primary (HIGH confidence)
- Direct inspection of NuvioTV source: ProfileDataStoreFactory, ProfileManager, TraktAuthDataStore, ProfileSettingsSyncService, ProfileSyncService, ProfileSelectionScreen
- Direct inspection of Nexio source: all 27+ DataStore files, AccountConfigSyncContract v7, AccountSettingsSyncService, UserProfile model
- `gradle/libs.versions.toml` — version verification

### Secondary (MEDIUM confidence)
- Netflix/Disney+/Plex/Kodi/Jellyfin profile patterns — cross-referenced via web research
- Android TV navigation and focus management documentation

---
*Research completed: 2026-04-14*
*Ready for roadmap: yes*
