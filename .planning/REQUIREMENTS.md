# Requirements: Nexio

**Defined:** 2026-04-14
**Milestone:** v1.1 TVDB First-Class TV Metadata
**Core Value:** Reliable, high-quality streaming playback with smart source selection and seamless library tracking across debrid providers.

## v1.1 Requirements

Requirements for the TVDB first-class TV metadata milestone. Each maps to roadmap phases.

### Provider Precedence

- [ ] **PREF-01**: User can configure TVDB with API key validation, local settings storage, and account sync support comparable to TMDB
- [ ] **PREF-02**: When TVDB is active, TVDB replaces TMDB as the metadata authority for TV/series metadata, detail pages, episodes, Continue Watching next-up metadata, artwork, trailers, related content, credits/cast, and networks
- [ ] **PREF-03**: When TVDB is active for TV, normal success paths do not perform duplicate TMDB TV metadata fetches for the same TV metadata purpose
- [ ] **PREF-04**: When TVDB is inactive, existing TMDB-backed TV behavior continues to work when TMDB is configured
- [ ] **PREF-05**: When active TVDB is invalid, unavailable, or lacks a required TV record, fallback is explicit, observable in diagnostics/logs, and does not silently double-fetch during normal success paths
- [ ] **PREF-06**: TVDB remote IDs are used for cross-provider identity matching so TVDB-backed TV records do not require TMDB lookups just to identify TV content
- [ ] **PREF-07**: When a poster-ratings integration supports a title, poster imagery comes from poster-ratings instead of TMDB or TVDB poster metadata

### Continue Watching Air Timing

- [ ] **AIR-01**: Continue Watching computes a precise TV episode availability instant from TVDB episode aired date plus series `airsTime` when both fields are available
- [ ] **AIR-02**: TVDB availability instants are converted to the Android TV device's local timezone before Continue Watching visibility decisions
- [ ] **AIR-03**: Future TV episodes are withheld from Continue Watching until the computed device-local TVDB availability instant
- [ ] **AIR-04**: Withheld future TVDB next-up entries schedule re-evaluation at the computed availability instant
- [ ] **AIR-05**: Date-only TVDB metadata falls back to existing date-only gating and exposes diagnostics explaining precise timing was unavailable
- [ ] **AIR-06**: TV detail screens can continue showing future unaired episodes while Continue Watching remains exact-air-time gated

### TV Metadata Value

- [ ] **META-01**: TVDB enriches TV titles with TV-specific fields including `airsDays`, `airsTime`, average runtime, original/latest network, original country/language, status, aliases, translations, content ratings, and remote IDs
- [ ] **META-02**: TVDB enriches episode rows with title, overview, image, runtime, aired date, absolute number, specials placement fields, linked movie data when present, and finale type when present
- [x] **META-03**: TVDB season ordering is preserved at least through the default TVDB season type, without assuming TMDB-style aired ordering when TVDB exposes a different season-type model
- [ ] **META-04**: TVDB artwork replaces TMDB TV artwork for TV records where TVDB provides artwork, while still honoring existing artwork controls and poster-ratings precedence
- [x] **META-05**: TVDB trailers, characters/cast, companies, networks, genres, and content ratings replace TMDB TV metadata where Nexio already has equivalent TV surfaces

### User Experience and Diagnostics

- [ ] **UX-01**: Settings explain provider precedence: TVDB is the TV metadata source when configured, TMDB remains movie metadata and TV fallback when TVDB is not configured, and poster-ratings is authoritative for supported poster imagery
- [x] **UX-02**: Users benefit from exact-air-time Continue Watching automatically once TVDB is configured, without needing additional provider-specific toggles
- [x] **UX-03**: TVDB failures degrade gracefully with validation or diagnostic signals instead of making Continue Watching or TV detail look randomly late, empty, or inconsistent

### Caching and API Use

- [ ] **CACHE-01**: TVDB authentication tokens and metadata responses are cached so normal browsing does not repeatedly authenticate or refetch stable TV metadata
- [x] **CACHE-02**: TVDB cache invalidation accounts for TVDB update signals or record timestamps so metadata can improve without aggressive refetching
- [x] **CACHE-03**: Stable TVDB reference data such as artwork types, genres, languages, statuses, and content ratings is heavily cached in line with TVDB guidance

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### TVDB Scale and Operations

- **OPS-01**: TVDB metadata is served through a dedicated caching proxy if direct client access becomes a scale, policy, or reliability constraint
- **OPS-02**: Nexio can maintain a broader local TVDB mirror or warmed cache for popular records if API usage patterns require it

### Advanced TV Ordering

- **ORDER-01**: User can choose between TVDB season order types such as official, DVD, absolute, alternate, and regional where TVDB provides them
- **ORDER-02**: Nexio exposes explicit UI for alternate season ordering without breaking Trakt progress matching

## Out of Scope

| Feature | Reason |
|---------|--------|
| Replacing TMDB for movie metadata | This milestone is TV-specific; TMDB remains valid for movies |
| Parallel TMDB and TVDB TV enrichment in normal success paths | The milestone goal is clear provider precedence and no duplicate TV metadata fetches |
| Home feed redesign | Continue Watching timing and TVDB-backed enrichment are in scope, but the feed structure is not |
| Requiring users to configure a TVDB caching proxy | Useful later, but too much setup burden for the first implementation |
| Full user-facing season-order picker | TVDB default season type must be preserved first; broad order selection is deferred |
| User toggle to show unaired next-up items in Continue Watching | Continue Watching should stay availability-gated |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

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
| AIR-01 | Phase 8 | Verified |
| AIR-02 | Phase 8 | Verified |
| AIR-03 | Phase 8 | Verified |
| AIR-04 | Phase 8 | Verified |
| AIR-05 | Phase 8 | Verified |
| AIR-06 | Phase 8 | Verified |
| META-03 | Phase 9 | Complete |
| META-05 | Phase 9 | Complete |
| UX-02 | Phase 9 | Complete |
| UX-03 | Phase 10 | Complete |
| CACHE-02 | Phase 10 | Complete |
| CACHE-03 | Phase 10 | Complete |

**Coverage:**
- v1.1 requirements: 24 total
- Mapped to phases: 24
- Unmapped: 0

---
*Requirements defined: 2026-04-14*
*Last updated: 2026-04-14 after roadmap creation*
