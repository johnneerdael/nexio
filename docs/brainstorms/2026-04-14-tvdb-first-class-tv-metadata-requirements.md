---
date: 2026-04-14
topic: tvdb-first-class-tv-metadata
---

# TVDB First-Class TV Metadata

## Problem Frame

Nexio currently treats TMDB as the primary metadata enrichment layer for TV and movies. That works for broad artwork and detail completion, but TV-specific behavior has a higher bar than movie metadata: season ordering, next episode timing, original network context, and episode availability all affect whether the app prompts the user at the right moment.

TheTVDB should become a first-class TV metadata integration. When configured, TVDB should replace TMDB for TV metadata rather than run beside it. The flagship user-visible improvement is exact Continue Watching availability for new episodes using TVDB's series-level `airsTime`, converted into the Android TV device's local timezone, instead of showing an episode at the start of its release date.

## Requirements

**Provider Selection**
- R1. TVDB must have its own integration settings, API key validation, local settings storage, and account sync support comparable to TMDB.
- R2. When TVDB is active, TVDB must replace TMDB as the metadata authority for TV/series content across TV metadata enrichment, TV detail metadata, episode metadata, Continue Watching next-up metadata, TV artwork, TV trailers, TV related-content recommendations, TV credits/cast, and TV networks.
- R3. When TVDB is active for TV, Nexio must not perform duplicate TMDB TV metadata fetches for the same TV metadata purpose. TMDB may remain active for movies.
- R4. If TVDB is inactive, Nexio should keep using the existing TMDB-backed TV behavior when TMDB is configured. If TVDB is active but invalid, unavailable, or lacks a required TV record, fallback to the existing non-TVDB behavior must be explicit, observable in logs/debug state, and must not silently double-fetch during normal success paths.
- R5. TVDB remote IDs, including IMDb, TMDB, TV Maze, Wikidata, and official-site IDs when present, should be used for matching and cross-provider identity so the app does not need TMDB lookups just to identify TVDB-backed TV records.
- R6. When a poster-ratings integration is configured for a title, it must supersede both TMDB and TVDB poster metadata for the poster surfaces it covers. TVDB and TMDB may still provide non-poster artwork and metadata according to their normal precedence.

**Continue Watching Air Timing**
- R7. Continue Watching must use TVDB episode air date plus series `airsTime` to compute an exact episode availability instant when both fields are available.
- R8. The computed TVDB availability instant must be converted to the Android TV device's local timezone before deciding whether the episode can appear in Continue Watching.
- R9. A future TV episode must not appear in Continue Watching before its computed local availability instant.
- R10. When a TVDB-backed future episode is withheld from Continue Watching, Nexio must schedule re-evaluation for the computed availability instant so the episode can appear without waiting for a later day-level refresh.
- R11. If TVDB has an episode date but no usable `airsTime`, Continue Watching should fall back to existing date-only behavior and expose enough debug information to explain that precise timing was unavailable.
- R12. TV detail screens may still show future unaired episodes where they already do today; the exact-air-time gate applies to Continue Watching availability.

**TV Metadata Value**
- R13. TVDB should enrich TV titles with TV-specific series fields that create value over TMDB: `airsDays`, `airsTime`, average runtime, original/latest network, original country/language, status, aliases, translations, content ratings, and remote IDs.
- R14. TVDB should enrich episode rows with episode title, overview, image, runtime, aired date, absolute number, specials placement fields, linked movie data when present, and finale type when present.
- R15. TVDB should support TVDB season ordering as a first-class TV capability. At minimum, Nexio should preserve the default TVDB season type and avoid assuming TMDB-style aired ordering when TVDB provides a different season-type model.
- R16. TVDB artwork should be eligible to replace TMDB TV artwork for TV records, including series artwork and episode images, while preserving Nexio's existing user controls for whether artwork enrichment is enabled and poster-ratings precedence is honored.
- R17. TVDB trailers, characters/cast, companies, networks, genres, and content ratings should be considered TVDB-backed replacements for TMDB TV metadata where the app already has equivalent TV surfaces.

**User Experience**
- R18. The settings UI should make the precedence clear: enabling TVDB makes it the TV metadata source, TMDB continues to serve movie metadata and TV fallback when TVDB is not configured, and poster-ratings integrations remain authoritative for poster imagery when configured.
- R19. Users should not need to understand provider internals to benefit from exact air timing; once TVDB is configured, Continue Watching timing should improve automatically.
- R20. If TVDB is configured but cannot be used, the app should degrade gracefully with a visible validation or diagnostic signal instead of making Continue Watching look randomly late or empty.

**Caching and API Use**
- R21. TVDB authentication tokens and metadata responses must be cached so normal browsing does not repeatedly authenticate or refetch stable TV metadata.
- R22. TVDB cache invalidation should account for TVDB update signals or record timestamps so metadata can improve over time without aggressive refetching.
- R23. The integration must respect TVDB's guidance that clients should cache heavily or use a caching proxy where appropriate, especially for stable reference data such as artwork types, genres, languages, statuses, and content ratings.

## Success Criteria

- With TVDB enabled and TMDB enabled, browsing TV content, TV artwork, TV trailers, TV related content, TV detail pages, and Continue Watching next-up TV items does not trigger TMDB TV metadata fetches in normal success paths.
- Movies continue to use existing TMDB behavior when TMDB is configured.
- With poster ratings configured, poster imagery comes from the poster-ratings integration rather than TMDB or TVDB wherever that integration supports the title.
- A TV episode with a future TVDB air date and `airsTime` appears in Continue Watching only after the computed device-local airing instant.
- A TV episode with date-only TVDB metadata behaves no worse than today's date-only gating and can be diagnosed as missing precise timing.
- TV detail and Continue Watching surfaces show TVDB-backed TV titles, episode descriptions, artwork, runtime, network, status, content rating, and season/episode data where TVDB provides them.
- TVDB integration failures do not blank existing Continue Watching or TV detail data when a safe fallback is available.

## Scope Boundaries

- Do not replace TMDB for movie metadata.
- Do not perform parallel TMDB and TVDB TV enrichment as a normal behavior when TVDB is active.
- Do not redesign the entire Home feed; the Continue Watching change is limited to TV next-up availability timing and TVDB-backed display enrichment.
- Do not require users to configure a TVDB caching proxy for the first implementation, but do keep the design compatible with heavier caching or proxying later.
- Do not solve every TVDB season-ordering edge case in the first pass if doing so would delay the core provider replacement and exact-air-time behavior.
- Do not expose a user toggle for showing unaired TVDB next-up items in Continue Watching.

## Key Decisions

- TVDB is a replacement, not an additive duplicate, for TV metadata when configured. TMDB remains the TV fallback when TVDB is not configured, and continues to support movies when configured. Poster-ratings integrations sit above both providers for poster imagery. This directly addresses duplicate fetches and makes provider precedence understandable.
- Exact Continue Watching airing is the flagship TVDB-only feature. It creates visible value beyond metadata polish and gives users a reason to configure TVDB even if TMDB is already working.
- TVDB's remote IDs should be part of the matching strategy. Live validation showed series records can include TMDB, IMDb, TV Maze, Wikidata, official site, and other IDs, which reduces the need for TMDB lookup calls in TV paths.
- Season ordering should be treated as a TVDB value area, but the first implementation should be careful. TVDB supports multiple season types, and planning should decide how much of that model Nexio can safely expose in the first pass.

## Dependencies / Assumptions

- `tvdb.yml` is the checked-in TVDB API contract used for planning. It documents series search, series extended records, series episodes by season type, `airsTime`, `airsDays`, remote IDs, artwork, content ratings, translations, companies, trailers, and `/updates`.
- Live API validation with the local `.thetvdb.apikey` confirmed `The Last of Us` TVDB series `392256` includes `airsTime: 21:00`, Sunday airing, TVDB episode records, TMDB remote ID `100088`, IMDb ID `tt3581920`, TV Maze ID, Wikidata ID, networks, content ratings, artwork, trailers, and episode runtime/image data.
- The current Continue Watching air-date gate lives in `app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt` and currently compares precise `firstAiredMs` when available, otherwise date-only metadata at midnight UTC.
- Current TMDB TV enrichment lives in `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`, `app/src/main/java/com/nexio/tv/core/tmdb/TmdbService.kt`, and TV-facing Home enrichment code such as `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`.
- TVDB documentation says TVDB air times are standardized by US EST for US series, by the show's country capital or most populous city for non-US series, and by official release time for streaming services.

## Alternatives Considered

| Approach | Outcome |
| --- | --- |
| TVDB as additive enrichment beside TMDB | Rejected. It preserves duplicate TV fetches and makes precedence unclear. |
| TVDB only for exact air timing | Too narrow. It misses the chance to make TVDB a meaningful premium TV metadata source. |
| TVDB as strict TV replacement | Preferred. It matches the product intent, reduces duplicate metadata work, and creates a clear mental model: TVDB for TV when configured, TMDB for TV otherwise, TMDB for movies when configured. |
| TVDB-backed proxy first | Deferred. It may be valuable later for scale and API policy alignment, but requiring it upfront would slow down the user-visible feature. |

## Outstanding Questions

### Resolve Before Planning

- None.

### Deferred to Planning

- [Affects R7, R8][Technical] Define the exact source-timezone mapping for TVDB `airsTime`: US EST/ET rules, non-US country timezone lookup, streaming-service exceptions, daylight-saving behavior, and missing country/network cases.
- [Affects R2, R3][Technical] Inventory every TV path that currently calls TMDB directly, including trailers and organization detail discovery, and decide which calls are TV metadata fetches that TVDB must replace.
- [Affects R6, R16][Technical] Verify all poster surfaces that currently use TMDB or TVDB artwork and ensure poster-ratings URLs override provider posters without suppressing non-poster TV artwork.
- [Affects R15][Technical] Decide how to map TVDB season types into Nexio's existing season/episode model without breaking Trakt progress matching.
- [Affects R21, R22][Technical] Choose TVDB cache keys, TTLs, and update invalidation strategy, including token refresh behavior.
- [Affects R17][Technical] Decide whether TVDB trailers are sufficient to replace TMDB TV trailer discovery in the first implementation or whether TV trailer replacement needs a staged rollout.
- [Affects R1][Technical] Decide whether the API key is a user-provided client key only, a subscriber-supported key plus PIN, or an app-level negotiated key path.

## Next Steps

-> /ce:plan for structured implementation planning.
