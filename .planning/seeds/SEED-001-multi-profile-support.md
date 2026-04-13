---
id: SEED-001
status: dormant
planted: 2026-04-14
planted_during: post-v0.46 maintenance
trigger_when: next major feature milestone
scope: Large
---

# SEED-001: Multi-profile support with per-profile Trakt accounts and individualized settings

## Why This Matters

Many households share a single device but want personalized experiences — their own Trakt
continue-watching state, their own language preferences, their own catalog order. NuvioTV
(our upstream fork) has already implemented this including per-profile Trakt accounts in
commit `1d3c2c2cf1c8eb2eb3e19be9ee141cf0070fef3e`. This is a competitive feature that
directly addresses the most common multi-user pain point.

## When to Surface

**Trigger:** Next major feature milestone

This seed should be presented during `/gsd-new-milestone` when the milestone
scope matches any of these conditions:
- Milestone involves user accounts, profiles, or personalization
- Milestone targets multi-user or household features
- Milestone involves settings architecture or sync infrastructure overhaul
- Next major feature milestone after core stability is achieved

## Design Principles

- **Explicit opt-in**: No profile selection screen if only 1 profile exists (the default).
  Users should never be bothered with profile management unless they actively create a
  second profile.
- **Profile photos**: Configurable name + photo upload (possibly through nexio-web).
- **Shared addons, unique settings**: Addons are global, most settings are per-profile.

## What is Shared (configurable only from default profile)

- Addons
- TMDB configuration
- MDBList
- IMDB Ratings
- OMDB
- Auto Translate
- Top-Posters
- RPDB
- Real-Debrid, Premiumize, EasyDebrid, Torbox (debrid accounts)

## What is Per-Profile

- **Phase 1**: Trakt account (for continue watching, library, scrobble)
- **Phase 2**: Simkl account
- All other settings (language, theme, player preferences, catalog order, etc.)
- Catalog customization (which catalogs are enabled, ordering)

## Scope Estimate

**Large** — This is a full milestone requiring:
1. `ProfileDataStoreFactory` pattern (per-profile DataStore instances)
2. Per-profile Trakt OAuth flow and token storage
3. Profile CRUD UI (create, edit, delete, select)
4. Profile selection screen (only shown when >1 profile exists)
5. Settings cascade (shared vs per-profile classification)
6. Sync infrastructure updates (per-profile sync payloads to Supabase)
7. Profile cleanup on deletion (DataStore files, cached data)
8. Photo upload integration (nexio-web?)

## Breadcrumbs

### Nexio (current codebase)

- `app/src/main/java/com/nexio/tv/domain/model/UserProfile.kt` — Existing basic profile model (id, name, avatarColorHex, usesPrimaryAddons)
- `app/src/main/java/com/nexio/tv/domain/model/ProfileAvatarColors.kt` — 8 predefined avatar colors
- `app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt` — Single global Trakt auth (needs per-profile refactor)
- `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt` — Trakt OAuth flow
- `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt` — Sync models (TraktAuthSyncSettings, CatalogSyncSettings)
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` — Global settings sync to Supabase
- `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt` — Sync contract v7
- `app/src/main/java/com/nexio/tv/data/local/AddonPreferences.kt` — Addon preferences (stays global)
- `app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModel.kt` — Catalog ordering (needs per-profile)

### NuvioTV (reference implementation at ~/Scripts/NuvioTV)

- `core/profile/ProfileManager.kt` — Profile CRUD, max 4 profiles, active profile switching
- `data/local/ProfileDataStore.kt` — Profile list persistence as JSON
- `data/local/ProfileDataStoreFactory.kt` — **Key pattern**: creates per-profile DataStore instances with `{feature}_p{profileId}` naming
- `data/local/TraktAuthDataStore.kt` — Per-profile Trakt auth using `flatMapLatest(activeProfileId)`
- `core/sync/ProfileSettingsSyncService.kt` — Per-profile settings sync to Supabase
- `core/sync/ProfileSyncService.kt` — Profile definition sync
- `ui/screens/profile/ProfileSelectionScreen.kt` — Profile picker UI with animations
- `core/di/ProfileModule.kt` — DI module for profile components
- Commit `1d3c2c2` — Added per-profile Trakt accounts

## Notes

- The NuvioTV `ProfileDataStoreFactory` pattern using `ConcurrentHashMap` with lazy init is
  the proven approach — adopt it rather than inventing a new pattern.
- Nexio already has a `UserProfile` model; it needs extending but the foundation exists.
- All Nexio DataStores currently use `@Singleton` with `@ApplicationContext` — these need
  refactoring to accept a `profileId` parameter via the factory.
- Profile deletion must clean up all associated DataStore files (`*_p{profileId}.preferences_pb`).
