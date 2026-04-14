# Nexio

## What This Is

Nexio is an Android TV / Fire TV streaming app built with Kotlin and Jetpack Compose. It integrates debrid services (Real-Debrid, Premiumize, EasyDebrid, Torbox), Trakt/Simkl sync, and benchmark-driven playback through a forked Media3/ExoPlayer stack. Package: `com.nexio.tv`.

## Core Value

Reliable, high-quality streaming playback with smart source selection and seamless library tracking across debrid providers.

## Current Milestone: v1.0 Multi-Profile Support

**Goal:** Enable household multi-user support with per-profile Trakt/Simkl accounts, individualized settings, and customizable catalogs while keeping addons and debrid accounts shared.

**Target features:**
- ProfileDataStoreFactory for per-profile DataStore isolation
- Profile CRUD (max 4 profiles, configurable name, optional PIN lock)
- Profile selection screen (opt-in — only shown when 2+ profiles exist)
- Per-profile Trakt accounts (OAuth, tokens, library, scrobble)
- Per-profile Simkl accounts
- Per-profile settings (language, theme, player, catalog order)
- Per-profile catalog customization (enable/disable, ordering)
- Profile photo upload via nexio-web (stored in Supabase)
- Settings cascade: shared vs per-profile classification
- Per-profile sync infrastructure to Supabase
- Profile cleanup on deletion
- nexio-web: profile CRUD from master account, per-profile management (Trakt/Simkl auth, catalog ordering, formatter config) for non-default profiles

## Requirements

### Validated

<!-- Existing capabilities confirmed working. -->

- Debrid integration (Real-Debrid, Premiumize, EasyDebrid, Torbox)
- Trakt sync (global, single account)
- Simkl sync (global, single account)
- Benchmark-driven playback with forked Media3/ExoPlayer
- Addon management and catalog ordering
- Account settings sync to Supabase
- TMDB, MDBList, IMDB Ratings, OMDB, RPDB integrations

### Active

<!-- Current scope. Building toward these. -->

- [ ] Per-profile DataStore isolation via ProfileDataStoreFactory
- [ ] Profile CRUD with max 4 profiles
- [x] Profile selection screen (opt-in, only when 2+ profiles) — Validated in Phase 3: Profile UI
- [ ] Per-profile Trakt OAuth and token storage
- [ ] Per-profile Simkl accounts
- [ ] Per-profile settings (language, theme, player, catalogs)
- [ ] Profile photo upload via nexio-web
- [x] Optional PIN lock per profile — Validated in Phase 3: Profile UI (UI built, server verification deferred to Phase 4)
- [ ] Settings cascade (shared addons/debrid vs per-profile)
- [ ] Per-profile sync to Supabase
- [ ] Profile deletion with full cleanup
- [ ] nexio-web profile CRUD from master account
- [ ] nexio-web per-profile management for non-default profiles (Trakt/Simkl auth, catalog ordering, formatter config)

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- On-device photo upload — TV input limitations, use nexio-web instead
- More than 4 profiles — keeps DataStore file count manageable
- Profile-specific addon configurations — addons are shared across all profiles
- Profile-specific debrid accounts — debrid credentials are shared (default profile only)

## Context

- Nexio is a fork of NuvioTV. Profile features were removed from the fork but NuvioTV has since added per-profile Trakt accounts (commit `1d3c2c2`).
- NuvioTV reference implementation at `~/Scripts/NuvioTV` provides proven patterns: `ProfileDataStoreFactory`, `ProfileManager`, per-profile sync services.
- Existing `UserProfile` model (`id`, `name`, `avatarColorHex`, `usesPrimaryAddons`) and `ProfileAvatarColors` provide a foundation to build on.
- All current DataStores use `@Singleton` with `@ApplicationContext` — need refactoring to factory-based per-profile instances.
- Account sync to Supabase is already in place (v7 contract) — needs extension for per-profile payloads.

### Shared Settings (default profile only)

Addons, TMDB, MDBList, IMDB Ratings, OMDB, Auto Translate, Top-Posters, RPDB, Real-Debrid, Premiumize, EasyDebrid, Torbox.

### Per-Profile Settings

Trakt account, Simkl account, language, theme, player preferences, catalog order/visibility, and all other non-shared settings.

### nexio-web Profile Management

- Master account can CRUD all profiles from nexio-web
- Non-default profiles can manage via nexio-web: Trakt/Simkl auth, catalog ordering, formatter config
- Other settings for non-default profiles are only available on-device (not exposed in nexio-web)

## Constraints

- **Platform**: Android TV / Fire TV with Jetpack Compose for TV
- **Architecture**: Must preserve existing Hilt DI patterns and DataStore preferences architecture
- **UX**: Zero friction for single-profile users — no profile selection unless 2+ profiles exist
- **Sync**: Must integrate with existing Supabase account sync infrastructure
- **Reference**: NuvioTV patterns should be adopted where proven, not reinvented

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Adopt NuvioTV ProfileDataStoreFactory pattern | Proven approach with ConcurrentHashMap + lazy init, already handles per-profile file naming | — Pending |
| Max 4 profiles | Matches NuvioTV, keeps DataStore file count manageable | — Pending |
| Photo upload via nexio-web only | TV remote input is not suited for photo management | — Pending |
| Opt-in profile selection | Single-profile users should never see profile management UI | — Pending |
| Optional PIN per profile | Household privacy without mandatory complexity | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-14 after Phase 3 (Profile UI) completion*
