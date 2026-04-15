# Nexio

## What This Is

Nexio is an Android TV / Fire TV streaming app built with Kotlin and Jetpack Compose. It integrates debrid services (Real-Debrid, Premiumize, EasyDebrid, Torbox), Trakt/Simkl sync, and benchmark-driven playback through a forked Media3/ExoPlayer stack. Package: `com.nexio.tv`.

## Core Value

Reliable, high-quality streaming playback with smart source selection and seamless library tracking across debrid providers.

## Current Milestone: v1.1 TVDB First-Class TV Metadata

**Goal:** Make TheTVDB the authoritative TV metadata provider when configured, replacing TMDB for TV surfaces while adding exact Continue Watching airing behavior from TVDB air times.

**Target features:**
- TVDB integration settings, API key validation, token handling, local storage, and account sync support
- Provider precedence: TVDB replaces TMDB for TV when configured; TMDB remains TV fallback when TVDB is not configured and continues serving movies
- Poster-ratings integrations remain authoritative for poster imagery above both TVDB and TMDB
- TVDB remote-ID matching for IMDb, TMDB, TV Maze, Wikidata, official-site, and related IDs
- TVDB-backed TV detail, episode, artwork, trailers, related-content, credits/cast, networks, genres, and content ratings
- Continue Watching exact availability using TVDB episode aired date plus series `airsTime`, converted to the Android TV device timezone
- Re-evaluation scheduling when future TVDB next-up entries become available
- TVDB cache/token strategy aligned with TVDB update signals and heavy-cache guidance
- Diagnostics for provider precedence, fallback, and missing precise air-time metadata

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
- TVDB foundation settings, API validation, auth token handling, account sync support, remote-ID identity matching, fallback diagnostics, and first-pass token/identity caching (validated in Phase 6: TVDB Foundation and Identity)
- TVDB provider replacement for Phase 7 surfaces: detail, episodes, Continue Watching metadata/runtime, Home focused/hero/catalog refresh, TV artwork, poster-ratings poster precedence, provider diagnostics, and settings provider-precedence copy (validated in Phase 7: TVDB Provider Replacement)
- Exact Continue Watching air-time gating using TVDB aired date plus series airsTime, device-local timezone conversion, withheld row persistence, Android TV feed gating, and durable alarm-backed re-evaluation with retry behavior (validated in Phase 8: Exact Continue Watching Air Timing)

### Active

<!-- Current scope. Building toward these. -->

- [ ] TVDB-backed trailer, related-content, credits/cast, network, genre, and content-rating metadata
- [ ] TVDB update-aware metadata cache invalidation and heavy-cache reference data strategy
- [ ] Diagnostics for provider precedence, fallback, and missing precise air times

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

### TV Metadata Provider Precedence

When TVDB is configured, TVDB is the authoritative source for TV metadata surfaces and TMDB must not be queried for duplicate TV metadata in normal success paths. When TVDB is not configured, existing TMDB-backed TV behavior remains available. Poster-ratings integrations supersede both TVDB and TMDB for poster imagery wherever those integrations support the title.

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
| TVDB replaces TMDB for TV metadata when configured | Avoids duplicate metadata fetches and makes TV provider precedence clear | — Pending |
| Poster-ratings providers supersede TMDB/TVDB poster metadata | Poster-rating artwork is the explicit user-selected poster authority | — Pending |
| Use TVDB `airsTime` for Continue Watching availability | Lets new episodes appear at actual airing/release time instead of date start | — Pending |

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
*Last updated: 2026-04-15 after completing Phase 8 Exact Continue Watching Air Timing*
