# Phase 9: TVDB Advanced TV Surfaces - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 9 preserves the TV-specific value that makes TVDB more useful than TMDB beyond the Phase 7 provider replacement. It covers TVDB default season-type preservation, stable Trakt progress matching while TVDB ordering data is present, TVDB-first trailer discovery for TV, and replacement of equivalent TMDB TV surfaces for characters/cast, companies, networks, genres, and content ratings.

This phase does not redesign the detail, Home, stream, or screensaver UI. It should populate existing metadata surfaces and preserve the Phase 7 provider-routing rule: TVDB is authoritative for TV when active, TMDB remains movie provider and explicit TV fallback, and poster-ratings providers remain authoritative for supported poster imagery.

</domain>

<decisions>
## Implementation Decisions

### Season Ordering and Trakt Matching
- **D-01:** Preserve TVDB `defaultSeasonType` and season-type metadata in Nexio's TVDB model. Use the TVDB default-season episode list as the TVDB display/enrichment source where it cleanly fits the existing episode model.
- **D-02:** Keep canonical `season` and `episode` numbers stable for Trakt progress, watch-state, episode ratings, and mutation matching. If TVDB default ordering disagrees with Trakt/TMDB-style aired numbering, Trakt progress matching wins.
- **D-03:** Preserve metadata needed to understand specials and non-standard season types, but only display or act on seasons that map cleanly to the existing season tabs and progress behavior in this phase.
- **D-04:** Planning should require diagnostics/logs when TVDB season-type data is present, when canonical Trakt numbering is used, and when alternate ordering is preserved but not applied.

### TVDB Trailer Replacement
- **D-05:** TVDB should take priority for title-level TV trailers when TVDB is active and provides usable trailer data.
- **D-06:** Season-level TVDB trailers/recaps may replace TMDB season video lookup only when TVDB data cleanly supports the existing season media actions. Do not invent new season actions in this phase.
- **D-07:** TV trailer fallback order when TVDB is active: TVDB usable trailer, then Streailer/internal sources, then existing fallback YouTube IDs, then explicit TMDB fallback only when TVDB has no usable trailer data.
- **D-08:** A usable TVDB trailer is a playable or external video URL that can feed the existing trailer playback model, or a YouTube/Vimeo-style URL that can be resolved through the current trailer pipeline.

### Advanced Metadata Mapping
- **D-09:** Map TVDB characters/cast into existing cast surfaces, preserving character names and photos where available. Do not add a new cast UI.
- **D-10:** Map TVDB companies and networks into existing `MetaCompany` surfaces, preserving whether each entry is a network or production company where possible.
- **D-11:** When TVDB is active, TVDB replaces TMDB TV genres and content ratings. Use existing display fields and the existing country/language preference behavior where practical.
- **D-12:** Do not add new user-visible metadata sections. Populate existing detail, Home, stream, and screensaver metadata surfaces.

### Provider UX and Diagnostics
- **D-13:** Do not add new TVDB-specific toggles. Existing metadata toggles continue to govern categories, while TVDB/TMDB provider routing decides the source.
- **D-14:** Exact-air-time Continue Watching behavior should remain automatic and quiet once TVDB is configured. Add no new UI unless a diagnostic or fallback state needs explanation.
- **D-15:** Planning should require logs or diagnostic state for TVDB surface success, missing TVDB data, explicit TMDB fallback, and TMDB skipped because TVDB supplied the TV surface.
- **D-16:** Missing TVDB advanced data should feel like graceful omission or existing fallback behavior. Avoid browse-time warnings unless the surface becomes visibly inconsistent or empty.

### the agent's Discretion
- Exact Kotlin class names for TVDB season-type records, trailer records, and advanced metadata mappers.
- Exact cache key names and diagnostic log tags, as long as TVDB advanced surfaces are separate from TMDB cache entries and fallback/skipped paths are observable.
- Exact mapping heuristics for TVDB company types and content-rating country preference, provided existing user-facing contracts are preserved.
- Exact test placement and granularity, provided tests cover season-type preservation, Trakt matching stability, TVDB trailer priority/fallback, and advanced metadata replacement.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase Definition
- `.planning/ROADMAP.md` - Phase 9 goal, dependency on Phase 7, success criteria, and Phase 10 boundary.
- `.planning/REQUIREMENTS.md` - Phase 9 requirements: META-03, META-05, UX-02.
- `.planning/PROJECT.md` - Milestone provider precedence, poster-ratings precedence, TVDB active requirements, and key decisions.

### Prior Phase Context
- `.planning/phases/06-tvdb-foundation-and-identity/06-CONTEXT.md` - TVDB auth, identity, token/cache foundation, and provider-precedence settings copy.
- `.planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md` - TVDB provider replacement decisions, deferred Phase 9 surfaces, provider routing, poster precedence, and verification expectations.

### TVDB API Reference
- `tvdb.yml` - Local TVDB OpenAPI reference. Relevant sections include `SeriesExtendedRecord`, `EpisodeBaseRecord`, `defaultSeasonType`, `seasonTypes`, `/series/{id}/episodes/{season-type}`, `/series/{id}/artworks`, `characters`, `companies`, `genres`, `contentRatings`, and `trailers`.

### Existing Domain Contracts and UI Surfaces
- `app/src/main/java/com/nexio/tv/domain/model/Meta.kt` - Existing `Meta`, `Video`, `MetaCastMember`, `MetaCompany`, `ageRating`, `genres`, and `trailerYtIds` contracts.
- `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt` - Home and Continue Watching metadata merge contract.
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt` - Existing season tab derivation and episode sorting behavior.
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` - Current detail enrichment, season selection, episode enrichment, trailers, More Like This, reviews, and mark-watched integration points.
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt` - Existing user-visible detail metadata sections for genres, networks, production companies, cast, age ratings, and episodes.

### Current TMDB and Trailer Implementation
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt` - Existing TMDB mapping for cast, creators, companies, networks, genres, content ratings, episodes, recommendations, reviews, and cache behavior.
- `app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt` - Existing TMDB endpoint and DTO surface that Phase 9 TV replacements should replace or explicitly fall back from.
- `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt` - Current TMDB, Streailer, YouTube/external trailer resolution and season trailer/recap availability behavior.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt` - Home and hero metadata enrichment, trailer availability, and trailer preview integration points.

### Progress and Matching
- `app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt` - Current canonical content ID parsing and Trakt path ID conversion.
- `app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt` - Existing season/episode watch-progress identity.
- `app/src/main/java/com/nexio/tv/data/repository/SimklProgressService.kt` - Existing example of preferring TVDB season/episode numbering when Simkl exposes it.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `Meta`, `Video`, `MetaCastMember`, `MetaCompany`, and `HomeDisplayMetadata` already provide user-facing fields for the advanced TVDB surfaces in scope. Phase 9 should feed these contracts instead of adding visible metadata sections.
- `TmdbMetadataService` already shows the mapper shape for cast, companies, networks, genres, age ratings, runtime, language, episodes, recommendations, reviews, and artwork. TVDB advanced mapping can mirror the useful parts while staying TVDB-first for TV.
- `TrailerService` already has the playback model, external URL handling, Streailer fallback, negative caches, and season trailer/recap actions. TVDB trailer support should route into this model.
- `MetaDetailsUiState.withRefreshedMeta` and `buildEpisodesForSeason` currently derive seasons from `Video.season` and sort episodes by `Video.episode`. Any TVDB season-type preservation must account for that behavior.
- `SimklProgressService` already contains precedent for preferring TVDB season/episode fields when present, while still producing canonical progress keys.

### Established Patterns
- Provider replacement should preserve existing UI contracts and settings toggles. Provider routing, not new toggles, decides whether TV data comes from TVDB or TMDB fallback.
- TMDB fallback must be explicit and observable, not a silent duplicate fetch during normal TV success paths.
- Poster-ratings integrations supersede poster imagery only; TVDB remains eligible for non-poster TV metadata and artwork when active.
- Home and detail enrichment paths fall back gracefully when enrichment is missing or fails.

### Integration Points
- TVDB advanced metadata mapper should connect to the Phase 7 provider abstraction or TVDB metadata service, not reintroduce direct TMDB TV calls.
- Detail enrichment must replace TMDB TV cast, companies, networks, genres, content ratings, and episode metadata where equivalent TVDB data exists.
- Trailer resolution must replace TV title video discovery and safe season video discovery for TVDB-active TV paths while preserving existing Streailer and YouTube fallbacks.
- Season-type preservation needs a model-level place to store TVDB default season-type context without destabilizing `Video.season` / `Video.episode` progress keys.
- Diagnostics should make provider choices observable for advanced surfaces: TVDB success, missing TVDB data, explicit TMDB fallback, and TMDB skipped because TVDB supplied the surface.

</code_context>

<specifics>
## Specific Ideas

- User selected TVDB default season-type preservation with Trakt progress matching as the stability boundary.
- User selected TVDB-first trailer replacement for title-level TV trailers, with season trailer/recap routing only where it fits the existing actions.
- User selected existing UI contracts for advanced metadata. No new detail sections or TVDB-specific UI surfaces should be added in this phase.
- User selected automatic, quiet exact-air-time Continue Watching behavior after TVDB configuration; no provider-specific toggle or visible label is needed.

</specifics>

<deferred>
## Deferred Ideas

- Full user-facing alternate season-order picker remains deferred to v2 requirements ORDER-01 and ORDER-02.
- New cast, company, network, or TVDB-specific metadata UI sections are deferred unless a later phase explicitly designs them.
- Broad TVDB cache invalidation, stable reference-data heavy caching, and user-facing TVDB docs remain Phase 10.

</deferred>

---

*Phase: 09-tvdb-advanced-tv-surfaces*
*Context gathered: 2026-04-14*
