# Roadmap: Nexio

## Overview

v1.1 TVDB First-Class TV Metadata makes TheTVDB the authoritative TV metadata provider when configured. The milestone proceeds in dependency order: first the TVDB settings/client foundation and identity matching, then provider replacement across TV surfaces with poster-ratings precedence, then exact Continue Watching air-time gating, then advanced TVDB value surfaces, and finally cache/update reliability and diagnostics. Phase numbering continues from the previous roadmap, so this milestone starts at Phase 6.

## Phases

**Phase Numbering:**
- Integer phases (6, 7, 8): Planned milestone work
- Decimal phases (7.1, 7.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 6: TVDB Foundation and Identity** - TVDB settings, API validation, auth token handling, remote-ID matching, fallback diagnostics, and first-pass metadata caching (completed 2026-04-15)
- [x] **Phase 7: TVDB Provider Replacement** - Replace TMDB TV metadata paths with TVDB-backed TV detail, episode, artwork, poster precedence, and settings-facing provider rules (completed 2026-04-15)
- [ ] **Phase 8: Exact Continue Watching Air Timing** - Compute device-local TVDB airing instants, gate future next-up rows, and re-emit when episodes become available
- [ ] **Phase 9: TVDB Advanced TV Surfaces** - Preserve TVDB season ordering and replace remaining TMDB TV surfaces such as trailers, cast, companies, networks, genres, and content ratings
- [ ] **Phase 10: TVDB Reliability, Updates, and Diagnostics** - Update-aware cache invalidation, heavily cached reference data, graceful failure behavior, diagnostics, and documentation

## Phase Details

### Phase 6: TVDB Foundation and Identity
**Goal**: Users can configure TVDB, Nexio can authenticate and cache TVDB responses, and TVDB-backed TV identity matching works without TMDB lookup dependency
**Depends on**: Phase 5 completion or explicit branch decision to pause the previous milestone
**Requirements**: PREF-01, PREF-04, PREF-05, PREF-06, CACHE-01
**Plans:** 5 plans
Plans:
- [x] 06-01-PLAN.md — Create RED validation coverage for TVDB auth, identity, settings, sync, and fallback behavior
- [x] 06-02-PLAN.md — Implement TVDB settings, token cache, Retrofit API, DI, and cached authentication
- [x] 06-03-PLAN.md — Implement TVDB remote-ID identity matching, identity cache, and fallback diagnostic decisions
- [x] 06-04-PLAN.md — Add TVDB public account sync and secret-backed credential sync support
- [x] 06-05-PLAN.md — Build the TVDB settings UI, approved copy, and integration hub routing
**Success Criteria** (what must be TRUE):
  1. User can enable TVDB, save an API key, and receive validation feedback without exposing the key in logs or synced public payloads
  2. TVDB settings sync through the account settings system with the key handled through the existing secret channel pattern
  3. TVDB auth tokens are cached and reused so normal browsing does not log in repeatedly
  4. TVDB search and remote IDs can resolve a TV series through IMDb, TMDB, TV Maze, Wikidata, official-site, or TVDB IDs without using TMDB only for identification
  5. If TVDB is inactive, TMDB-backed TV metadata behavior remains unchanged; if TVDB is active but unusable, fallback is explicit and diagnostically visible

### Phase 7: TVDB Provider Replacement
**Goal**: TVDB replaces TMDB as the normal TV metadata provider across existing TV enrichment surfaces, while poster-ratings integrations remain authoritative for poster imagery
**Depends on**: Phase 6
**Requirements**: PREF-02, PREF-03, PREF-07, META-01, META-02, META-04, UX-01
**Plans:** 6 plans
Plans:
- [x] 07-01-PLAN.md — Verify Phase 6 TVDB foundation exists, then add provider-neutral TV metadata models, diagnostics, and TVDB cache namespaces
- [x] 07-02-PLAN.md — Implement TVDB metadata mapping and TVDB-first router with explicit TMDB fallback
- [x] 07-03-PLAN.md — Route Detail screen series metadata, episode rows, and season watched behavior through TVDB-first routing
- [x] 07-04-PLAN.md — Route Continue Watching display metadata and runtime hydration through TVDB-first routing
- [x] 07-05-PLAN.md — Route Home focused, hero, and catalog refresh TV metadata through TVDB-first routing
- [x] 07-06-PLAN.md — Update provider-precedence settings copy and poster-ratings TVDB poster assertions
**Success Criteria** (what must be TRUE):
  1. With TVDB enabled and TMDB enabled, normal TV detail, TV Home enrichment, episode metadata, TV artwork, and Continue Watching metadata paths do not issue TMDB TV metadata fetches
  2. TVDB series and episode fields populate the same user-facing metadata roles currently served by TMDB where TVDB provides equivalent data
  3. Poster-ratings provider URLs override TVDB and TMDB poster metadata for supported titles without suppressing non-poster artwork such as backdrops, logos, or episode images
  4. Settings copy or UI state clearly communicates provider precedence: TVDB for TV when configured, TMDB for movies and TV fallback, poster-ratings for supported posters
  5. Tests or instrumentation cover at least one TVDB-enabled path proving TMDB TV calls are skipped in normal success behavior

### Phase 8: Exact Continue Watching Air Timing
**Goal**: Continue Watching shows TVDB-backed new episodes at their computed device-local airing instant instead of at the start of the release date
**Depends on**: Phase 7
**Requirements**: AIR-01, AIR-02, AIR-03, AIR-04, AIR-05, AIR-06
**Plans:** 4 plans
Plans:
- [x] 08-01-PLAN.md — Create exact TVDB availability contracts, parsing policy, and central gate priority
- [x] 08-02-PLAN.md — Enrich tracking next-up rows with TVDB exact timing and fallback diagnostics
- [x] 08-03-PLAN.md — Persist withheld rows and apply exact gating across Continue Watching surfaces
- [x] 08-04-PLAN.md — Add durable Android alarm scheduling, refresh, and retry behavior
**Success Criteria** (what must be TRUE):
  1. For TVDB records with episode aired date plus series `airsTime`, Nexio computes an exact availability instant using the correct source-timezone policy
  2. Continue Watching withholds future TV episodes until the computed instant in the Android TV device timezone
  3. Future next-up entries schedule a re-evaluation for the computed availability instant and can appear without waiting for a day-level refresh
  4. Date-only TVDB entries preserve existing date-only gating and expose diagnostics explaining that precise timing was unavailable
  5. TV detail screens can still show future unaired episodes while Continue Watching remains exact-air-time gated

### Phase 9: TVDB Advanced TV Surfaces
**Goal**: Nexio captures the TV-specific value that makes TVDB more useful than TMDB for series metadata beyond basic enrichment and air timing
**Depends on**: Phase 7
**Requirements**: META-03, META-05, UX-02
**Plans:** 6 plans
Plans:
- [ ] 09-00-PLAN.md — Create Wave 0 validation scaffolds and block unless Phase 7 TVDB provider outputs exist
- [ ] 09-01-PLAN.md — Preserve TVDB default season type and season-order context while keeping canonical Trakt progress keys stable
- [ ] 09-02-PLAN.md — Map TVDB cast, companies, networks, genres, and content ratings into provider output with diagnostics
- [ ] 09-03-PLAN.md — Propagate TVDB advanced metadata through existing detail, Home, stream, screensaver, and player surfaces
- [ ] 09-04-PLAN.md — Route TV trailers through TVDB-first discovery before Streailer, fallback IDs, and explicit TMDB fallback
- [ ] 09-05-PLAN.md — Lock no-toggle provider UX, diagnostics, and Phase 9 validation gates
**Success Criteria** (what must be TRUE):
  1. TVDB default season type is preserved in Nexio's season/episode model without assuming TMDB-style aired ordering
  2. Trakt progress matching remains stable when TVDB season ordering data is present
  3. TVDB trailers replace TMDB TV trailer discovery where TVDB provides usable trailer data, or the fallback behavior is explicitly staged and diagnosable
  4. TVDB characters/cast, companies, networks, genres, and content ratings replace equivalent TMDB TV surfaces where those surfaces already exist
  5. Users receive exact-air-time Continue Watching behavior automatically once TVDB is configured, with no extra provider-specific toggle

### Phase 10: TVDB Reliability, Updates, and Diagnostics
**Goal**: TVDB metadata stays reliable over time through update-aware caching, heavily cached reference data, graceful failure behavior, and clear diagnostics
**Depends on**: Phase 8 and Phase 9
**Requirements**: UX-03, CACHE-02, CACHE-03
**Plans:** 7 plans
Plans:
- [ ] 10-00-PLAN.md — Bind Phase 6-9 TVDB source contracts and create sanitized reliability diagnostics
- [ ] 10-01-PLAN.md — Process TVDB `/updates` and invalidate TVDB cache namespaces safely
- [ ] 10-02-PLAN.md — Schedule background update checks with startup catch-up and credential-health gating
- [ ] 10-03-PLAN.md — Cache stable TVDB reference data heavily with stale-on-failure behavior
- [ ] 10-04-PLAN.md — Preserve last-known-good TVDB metadata through outages and invalid credentials
- [ ] 10-05-PLAN.md — Surface TVDB diagnostics in settings/debug UI with shared recorder logging
- [ ] 10-06-PLAN.md — Update user-facing TVDB setup and behavior docs
**Success Criteria** (what must be TRUE):
  1. TVDB metadata cache invalidation uses TVDB update signals or record timestamps so stale metadata can refresh without aggressive refetching
  2. Stable TVDB reference data such as artwork types, genres, languages, statuses, and content ratings is cached heavily
  3. TVDB outages or invalid credentials do not blank existing TV detail or Continue Watching data when a safe fallback is available
  4. Diagnostics can explain provider choice, fallback reason, missing `airsTime`, date-only gating, poster-ratings override, and skipped TMDB TV fetches
  5. User-facing docs describe TVDB setup, provider precedence, poster-ratings precedence, and exact Continue Watching air-time behavior

## Progress

**Execution Order:**
Phases execute in dependency order: 6 -> 7 -> 8 and 9 -> 10.

Note: Phase 9 can begin after Phase 7 while Phase 8 is being validated, but Phase 10 depends on both exact air timing and advanced TVDB surface replacement.

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 6. TVDB Foundation and Identity | 5/5 | Complete | 2026-04-15 |
| 7. TVDB Provider Replacement | 8/8 | Complete | 2026-04-15 |
| 8. Exact Continue Watching Air Timing | 0/? | Planned | - |
| 9. TVDB Advanced TV Surfaces | 0/? | Planned | - |
| 10. TVDB Reliability, Updates, and Diagnostics | 0/? | Planned | - |

## Requirement Coverage

| Requirement | Phase | Status |
|-------------|-------|--------|
| PREF-01 | Phase 6 | Verified |
| PREF-04 | Phase 6 | Verified |
| PREF-05 | Phase 6 | Verified with debt |
| PREF-06 | Phase 6 | Verified |
| CACHE-01 | Phase 6 | Verified |
| PREF-02 | Phase 7 | Verified for Phase 7 scope |
| PREF-03 | Phase 7 | Verified |
| PREF-07 | Phase 7 | Verified |
| META-01 | Phase 7 | Verified |
| META-02 | Phase 7 | Verified |
| META-04 | Phase 7 | Verified |
| UX-01 | Phase 7 | Verified |
| AIR-01 | Phase 8 | Pending |
| AIR-02 | Phase 8 | Pending |
| AIR-03 | Phase 8 | Pending |
| AIR-04 | Phase 8 | Pending |
| AIR-05 | Phase 8 | Pending |
| AIR-06 | Phase 8 | Pending |
| META-03 | Phase 9 | Pending |
| META-05 | Phase 9 | Pending |
| UX-02 | Phase 9 | Pending |
| UX-03 | Phase 10 | Pending |
| CACHE-02 | Phase 10 | Pending |
| CACHE-03 | Phase 10 | Pending |

**Coverage:**
- v1.1 requirements: 24 total
- Mapped to phases: 24
- Unmapped: 0
