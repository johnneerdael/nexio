---
title: feat: Kitsu anime detail parity
type: feat
status: active
date: 2026-04-21
---

# feat: Kitsu anime detail parity

## Overview

Anime detail currently uses Kitsu only for title-level enrichment and episode hydration, while the lower detail page still reflects the TV/movie TMDB-first model: cast is often sparse, the tab row is built around ratings / more like this / reviews, and clickable cast / company navigation assumes TMDB or TVDB identifiers only.

This plan makes anime detail feel structurally equivalent to movie/TV detail while staying provider-correct:

- populate character cards from Kitsu castings
- replace anime-only lower tabs with `Characters` and `Related`
- populate related content from Kitsu relationship/franchise/installment surfaces
- show clickable production companies when Kitsu gives us a stable producer identity
- preserve the existing detail-page composition and focus behavior instead of creating an anime-only screen

If implemented, anime detail keeps the same visual grammar as the user-provided screenshot and current `MetaDetailsScreen`, but stops showing TV/movie-specific surfaces that Kitsu does not back well, especially ratings and reviews.

## Origin Inputs

- `docs/brainstorms/2026-04-14-tvdb-first-class-tv-metadata-requirements.md`
  - relevant because it established the current provider-authority model and the expectation that provider-backed advanced metadata should fill cast / companies / related surfaces
- `docs/brainstorms/2026-04-15-android-tv-entity-card-provider-link-requirements.md`
  - relevant because it reinforced that detail and discover flows should preserve stable provider identity instead of collapsing everything into a single global model
- `plans/2026-04-21-kitsu-get-endpoint-index.md`
  - relevant because it documents the public Kitsu endpoints available for cast, related, and production enrichment

## Problem Frame

The current Kitsu path in `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt` enriches:

- title / synopsis / poster / backdrop / runtime / age rating
- episode rows from `/anime/{id}/episodes`

It does not enrich:

- cast members
- people / staff click-through identities
- related-title rails
- production companies

At the UI layer, `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt` builds a lower people-content area around `Cast`, `Ratings`, `More Like This`, `Reviews`, and `Collection`. That shape works for TMDB/TVDB-backed movies and TV, but it is the wrong semantic model for anime titles whose strongest public Kitsu surfaces are cast/staff, relationship graphs, franchises, installments, and production links.

At the navigation layer:

- cast click-through only works when a `MetaCastMember` has `tmdbId` or `tvdbPeopleId`
- company click-through only works through the TMDB-only `OrganizationDetailViewModel` and `TmdbOrganizationService`

That means even if Kitsu gives us useful anime-specific people and production data, the app currently has no provider-aware way to navigate it.

## Requirements Trace

- R1. Anime detail must populate character cards from Kitsu public metadata rather than leaving anime titles with empty or low-quality people sections.
- R2. Anime detail must replace the current anime lower-tab set with provider-appropriate tabs: `Characters` and `Related`.
- R3. Anime detail must not show ratings or reviews tabs for Kitsu-backed anime detail.
- R4. The `Related` tab must be populated from Kitsu relationship surfaces, specifically `/media-relationships`, `/franchises`, and `/installments`.
- R5. Anime detail must show production companies when Kitsu yields a reliable producer identity.
- R6. Production companies shown for anime detail must be clickable to other titles by that company when Nexio has a reliable provider-backed route.
- R7. Character cards shown for anime detail must be clickable to an “other work” surface comparable to existing cast detail, opening the actor/person detail flow rather than a character detail screen.
- R7a. Kitsu actor click-through must use TMDB person search by actor name as a guarded bridge.
- R7b. The app must only enable actor click-through when the top TMDB person-search result is a strong exact-name match.
- R7c. If a strong exact TMDB person match is not available, the anime character card must remain visible but non-clickable.
- R8. Anime detail must keep the same overall interaction quality and visual hierarchy as movie / TV detail rather than becoming a special-case low-feature detail page.
- R9. Existing episode hydration, routing, focus restoration, and navigation behavior for anime detail must remain intact.
- R10. The implementation must remain public-Kitsu-first; no auth-required Kitsu endpoints should be a prerequisite for anime detail parity.
- R11. Anime character-card language selection must be Kitsu-only and must not depend on mapped provider metadata. It must prefer the likely source-region voice language when that can be inferred reliably from the anime title itself, then fall back to English.

## Scope Boundaries

- Do not add Kitsu ratings or Kitsu review surfaces in this pass.
- Do not add Kitsu social/community surfaces such as favorites, follows, posts, comments, or reactions.
- Do not redesign the hero or episode shelf layout for all content types.
- Do not replace TMDB/TVDB behavior for movie or TV detail tabs.
- Do not require Kitsu account auth for cast, related, or production enrichment.
- Do not solve every possible Kitsu person or producer reverse-lookup edge case in the first pass if a narrower provider-aware detail route gets the core experience working.

## Current Code & Pattern Context

### Existing detail-view shape

- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
  - owns the lower detail tabs and the cast / more-like-this / reviews / company rows
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt`
  - currently exposes `moreLikeThis`, `reviews`, `collection`, and episode ratings, but no provider-native `related` dataset
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
  - currently loads TMDB-backed more-like-this, reviews, and collection data asynchronously

### Existing provider-backed advanced metadata pattern

- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt`
  - `TvMetadataEnrichment` already has the right structural slots for `castMembers`, `productionCompanies`, and `networks`
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAdvancedMetadataMapper.kt`
  - demonstrates the intended pattern for mapping provider-native credits / companies into `MetaCastMember` and `MetaCompany`
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt`
  - already routes Kitsu as the anime metadata authority before TMDB / TVDB fallback

### Existing Kitsu detail implementation

- `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
  - currently uses only:
    - `GET /anime/{id}`
    - `GET /anime/{id}/episodes`
  - does not yet query:
    - `/anime-characters`
    - `/characters`
    - `/castings`
    - `/anime-staff`
    - `/anime-productions`
    - `/producers`
    - `/people`
    - `/media-relationships`
    - `/franchises`
    - `/installments`

### Existing click-through surfaces

- `app/src/main/java/com/nexio/tv/ui/screens/cast/CastDetailViewModel.kt`
  - supports only TMDB or TVDB providers and expects an integer `personId`
- `app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailViewModel.kt`
  - is TMDB-only and expects an integer `entityId`
- `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
  - `MetaCastMember` stores `tmdbId` / `tvdbPeopleId` only
  - `MetaCompany` stores `tmdbId` only

These model constraints are the main reason Kitsu people and production data cannot currently participate in navigation even if enrichment is added.

## Kitsu Endpoint Reality Relevant To This Plan

From `plans/2026-04-21-kitsu-get-endpoint-index.md` and direct local validation:

- public and clearly useful now:
  - `/anime`
  - `/anime/{id}`
  - `/anime-characters`
  - `/characters`
  - `/castings`
  - `/anime-staff`
  - `/anime-productions`
  - `/producers`
  - `/people`
  - `/media-relationships`
  - `/franchises`
  - `/installments`
- relationship / discover rails can be consumed without Kitsu auth
- category rails were also verified publicly, but they are not part of this detail-view plan

Important limitation to carry into implementation:

- Kitsu collection filters clearly support `animeId` on `/anime-characters` and `animeId` / `producerId` on `/anime-productions`
- reverse-lookup filter semantics for “show all titles for this person” are not clearly documented on `castings`, `anime-staff`, or `people`
- implementation therefore must either:
  - validate provider-supported reverse lookups during execution, or
  - use relationship links exposed by detail resources rather than assuming collection filters exist

## Key Technical Decisions

- **Decision: keep one shared detail screen and make anime behavior provider-conditional.**
  - Rejected alternative: build an anime-only detail screen.
  - Reason: the current detail layout, focus model, and section composition are already strong. The request is parity, not bifurcation.

- **Decision: use a provider-native `Characters` tab for anime instead of a TV/movie-style cast rail.**
  - Rejected alternative: show real actor portrait first with actor name primary and character secondary, matching TV/movie detail exactly.
  - Reason: character-first cards are more anime-native and more visually distinctive while still supporting actor click-through.

- **Decision: add a provider-native `Related` tab for anime instead of reusing `moreLikeThis`.**
  - Rejected alternative: silently map Kitsu related items into `moreLikeThis`.
  - Reason: the source semantics are different. Kitsu relationships are deterministic graph links, not recommendation results, and the UI label should reflect that.

- **Decision: remove ratings and reviews tabs for Kitsu-backed anime detail.**
  - Rejected alternative: leave empty or partially populated tabs for parity optics.
  - Reason: the user explicitly wants anime detail to feel similar in quality, not identical in provider semantics. Empty/weak tabs would reduce quality.

- **Decision: extend shared entity models with provider-aware identity instead of bolting Kitsu IDs into TMDB-only fields.**
  - Rejected alternative: overload `tmdbId` or `tvdbPeopleId` with Kitsu ids.
  - Reason: that would preserve the wrong abstraction and break navigation semantics.

- **Decision: make cast and production navigation provider-aware.**
  - Rejected alternative: show Kitsu cast / companies as non-clickable until TMDB IDs are available.
  - Reason: click-through is part of the requested feature, and the provider identity should survive all the way into detail/discover routing.

- **Decision: use guarded TMDB person search for Kitsu actor click-through.**
  - Rejected alternative: fuzzy-name TMDB matching for all Kitsu actors.
  - Reason: a wrong actor detail screen is worse than a non-clickable card. Only strong exact-name TMDB matches should unlock the existing cast-detail modal.

- **Decision: keep production companies visible only when a stable producer identity exists.**
  - Rejected alternative: always render textual production names even when they cannot navigate.
  - Reason: the requested bottom section should behave like the existing company row, not degrade into static labels unless we explicitly decide on a placeholder state later.

## High-Level Design

### 1. Expand Kitsu advanced-detail service surface

Extend `KitsuMetadataService` so it can build an anime detail graph composed of:

- title-level enrichment from `/anime/{id}`
- character / actor / staff graph from `/anime-characters`, `/characters`, `/castings`, `/anime-staff`, `/people`
- production companies from `/anime-productions` and `/producers`
- related titles from `/media-relationships`, `/franchises`, `/installments`

This should map into existing shared detail primitives where possible:

- `TvMetadataEnrichment.castMembers`
- `TvMetadataEnrichment.productionCompanies`
- `MetaPreview` for related tiles

Add a small Kitsu-specific detail aggregate model rather than bloating `TvMetadataEnrichment` with unrelated fields if the mapper becomes awkward.

### 2. Introduce provider-aware entity references

Current shared models only know TMDB / TVDB integer IDs. Anime parity needs provider-aware entity identity.

Preferred shape:

- add a small provider-aware entity ref model, for example:
  - person ref: provider + provider entity id + display name
  - organization ref: provider + provider entity id + kind + display name
- use that model from:
  - `MetaCastMember`
  - `MetaCompany`

This lets the UI remain generic while routing click-through to TMDB, TVDB, or Kitsu-specific detail flows.

### 3. Split anime lower tabs from movie / TV lower tabs

`MetaDetailsScreen` should derive tab composition from content/provider shape:

- movie / TV stays as today
- Kitsu-backed anime becomes:
  - `Characters`
  - `Related`
  - optional `Collection` only if some separate provider-backed collection surface still exists and is valid for that title

`Ratings` and `Reviews` should not appear for anime detail.

This change belongs in:

- `MetaDetailsUiState`
- `MetaDetailsViewModel`
- `MetaDetailsScreen`

It should avoid rewriting the lower-content focus model. The correct move is to rename / replace the anime branch of the people-content tabs, not redesign focus ownership.

### 4. Add a provider-aware related-content loader

Anime `Related` should not depend on TMDB recommendation APIs. It should be built from Kitsu graph surfaces:

- direct media relationships
- franchise siblings
- installments

Normalization rules should:

- dedupe repeated titles across the three sources
- prefer stable anime ids in `kitsu:{id}` form for navigation
- preserve order in a way that favors the strongest graph semantics first:
  1. explicit media relationships
  2. installments
  3. franchise siblings

`Related` items should map into `MetaPreview` so the existing row components can be reused.

### 5. Add Kitsu-backed person and organization detail/discover flows

#### Person detail

Extend the existing cast-detail route and view model so provider is first-class:

- TMDB and TVDB continue to work unchanged
- Kitsu adds:
  - guarded TMDB person-search bridge from Kitsu actor name to TMDB person id
  - reuse of the existing TMDB-backed cast detail modal only when that bridge yields a strong exact-name match
  - non-clickable character cards when no strong TMDB person match exists

Anime character-card composition should use:

- card image: character image first, actor image as fallback
- primary text: character name
- secondary text: actor name
- click target: actor/person detail screen

Language policy for character cards:

- use Kitsu title and casting data only; do not use TMDB, TVDB, or any other mapped-provider metadata for language selection
- prefer the likely source-region voice language when the anime title itself makes that inference reliable
- use a tiered anime-language preference by source country:
  - Japan -> Japanese first
  - Korea -> Korean first
  - China / donghua -> Mandarin / Chinese first
- if that source-region inference is unavailable, fall back to a default anime preference of Japanese first
- then fall back to English

The current `personId: Int` route shape should be widened to a provider-aware string id to avoid fighting Kitsu identity.

#### Organization detail

The current organization-detail flow is TMDB-only.

For Kitsu-backed anime productions, extend this flow so provider-aware company detail can:

- load producer/company identity from Kitsu
- list other titles for the same producer when the producer id is stable and reverse lookup is supported

If Kitsu producer reverse-title lookup cannot be validated reliably, the implementation should keep the producer row hidden rather than shipping a broken click-through promise.

### 6. Keep anime visual parity without provider fakery

The target is the current detail feel shown in the user-provided screenshot:

- strong cast rail
- clickable people
- clickable production logos / names
- related titles in the same visual tier as current more-like-this rows

But the plan should not simulate movie/TV parity by showing provider-inappropriate tabs. Anime parity here means:

- same quality of surface design
- same quality of navigation
- provider-correct data

## Implementation Units

- [ ] **Unit 1: Expand Kitsu API and detail aggregate models**

**Goal:** Add the Kitsu endpoint surface and local models needed for cast, related, and production enrichment.

**Requirements:** R1, R4, R5, R9, R10

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/KitsuApi.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/KitsuNetworkModuleTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt`

**Approach:**
- Add explicit endpoint methods for the public Kitsu resources used by this plan.
- Introduce local DTOs / mappers for:
  - anime-character relations
  - people / staff / cast entities
  - anime production relations and producers
  - related-title relations
- Keep title / episode enrichment behavior intact while layering advanced anime detail support on top.

**Test scenarios:**
- verify endpoint interfaces map to the intended Kitsu paths
- verify anime cast mapping yields stable display names, roles, and provider refs
- verify related-title mapping dedupes repeated titles across relationship sources
- verify production mapping only emits companies when stable producer identity is present

- [ ] **Unit 2: Add provider-aware person and organization identity to shared models**

**Goal:** Make cast and company cards navigable with Kitsu-backed identity instead of TMDB-/TVDB-only integer ids.

**Requirements:** R5, R6, R7, R8

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/MetadataModelSanitizers.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAdvancedMetadataMapper.kt`
- Test: `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterKitsuTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/navigation/OrganizationDetailRouteTest.kt`

**Approach:**
- Introduce provider-aware entity identity in a backward-compatible way.
- Update TMDB and TVDB mappers to populate the new model shape so existing flows do not regress.
- Preserve cache compatibility by updating disk-cache sanitization and read/write recovery.

**Test scenarios:**
- verify cached metadata survives the new cast/company model shape
- verify TMDB/TVDB cast and company mappings still produce navigable entities
- verify Kitsu cast/company mappings can carry provider-native ids without abusing TMDB fields

- [ ] **Unit 3: Reframe anime detail lower tabs around `Characters` and `Related`**

**Goal:** Replace anime-only `Ratings` / `More Like This` / `Reviews` behavior with `Characters` and `Related`, while keeping movie/TV detail unchanged.

**Requirements:** R2, R3, R4, R8, R9

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
- Modify or add: `app/src/main/java/com/nexio/tv/ui/screens/detail/MoreLikeThisSection.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/detail/RelatedSection.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreenRuntimeTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsSeasonMediaViewModelTest.kt`
- Create test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsKitsuRelatedTabTest.kt`

**Approach:**
- Add explicit `related` state instead of overloading `moreLikeThis`.
- Build provider-conditional tab sets:
  - anime: cast + related
  - non-anime: existing tabs unchanged
- Remove anime review/rating loading branches from the visible anime UI path without disturbing non-anime logic.

**Test scenarios:**
- verify anime detail exposes `Characters` and `Related` tabs only
- verify TV/movie detail still exposes existing tabs
- verify anime detail never shows reviews or ratings tabs even when unrelated state values are populated
- verify related-item click-through routes to the correct detail id/type

- [ ] **Unit 4: Add Kitsu-backed person detail and filmography**

**Goal:** Make anime character cards clickable to the existing TMDB actor-detail flow only when TMDB person search resolves a strong exact match.

**Requirements:** R1, R7, R8

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/cast/CastDetailViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/cast/CastDetailUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`
- Create: `app/src/main/java/com/nexio/tv/core/anime/KitsuPersonService.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/cast/CastDetailViewModelTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/navigation/CastDetailRouteTest.kt`

**Approach:**
- Add a TMDB person-search bridge for Kitsu actor names.
- Gate click-through on a strong exact-name match for the top TMDB result.
- Reuse the existing cast-detail screen for matched actors.
- Keep anime character cards non-clickable when TMDB search does not resolve confidently.

**Test scenarios:**
- verify TMDB and TVDB person routes still resolve correctly
- verify Kitsu actor name search enables click-through only on strong exact-name TMDB matches
- verify ambiguous or missing TMDB matches leave the character card non-clickable
- verify matched Kitsu actors open the existing cast-detail modal unchanged

- [ ] **Unit 5: Add Kitsu-backed production company detail and click-through**

**Goal:** Make anime production companies clickable to a provider-aware “other titles by this company” flow when Kitsu producer identity is reliable.

**Requirements:** R5, R6, R8

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbOrganizationService.kt`
- Create: `app/src/main/java/com/nexio/tv/core/anime/KitsuOrganizationService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/CompanyLogosSection.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/navigation/OrganizationDetailRouteTest.kt`
- Create test: `app/src/test/java/com/nexio/tv/ui/screens/organization/OrganizationDetailViewModelTest.kt`

**Approach:**
- Generalize organization-detail routing and state away from TMDB-only assumptions.
- Add a Kitsu producer-backed organization service for anime company discovery.
- Render the bottom production row for anime only when it can navigate reliably.

**Test scenarios:**
- verify TMDB organization detail behavior remains intact
- verify Kitsu company navigation preserves provider-aware identity
- verify anime production rows are hidden or disabled safely when Kitsu producer reverse lookup is unavailable
- verify producer-backed title results navigate to launchable anime detail ids

- [ ] **Unit 6: Wire advanced Kitsu detail enrichment into detail loading and refresh**

**Goal:** Ensure advanced Kitsu anime metadata participates in the same refresh, caching, and focus-stable detail lifecycle as existing advanced metadata.

**Requirements:** R1-R10

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt`
- Create test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsKitsuAdvancedMetadataTest.kt`

**Approach:**
- Treat advanced Kitsu anime data as part of the normal detail enrichment pipeline.
- Ensure refreshed meta writes cast / production / related data through the same safe cache paths.
- Keep episode hydration and next-to-watch behavior intact.

**Test scenarios:**
- verify anime detail receives Kitsu cast / related / production enrichment without regressing episode hydration
- verify cache round-trips preserve new Kitsu-backed fields
- verify non-anime routes do not accidentally enter the Kitsu advanced-detail branch

## Dependencies and Sequencing

1. Unit 1 must land before any UI or navigation work because it defines the Kitsu data surface.
2. Unit 2 must land before Units 4 and 5 because click-through depends on provider-aware entity identity.
3. Unit 3 can start once Unit 1 defines the `related` dataset shape.
4. Unit 4 and Unit 5 can proceed in parallel after Unit 2 if the entity model is stable.
5. Unit 6 is the integration/hardening pass after the individual service and UI changes exist.

## Execution Posture

- Characterization-first on the detail tab composition and navigation routes.
- Avoid broad UI rewrites. Prefer provider-conditional branching inside existing files where the structure is already sound.
- Validate Kitsu reverse person / producer lookup behavior early during execution. If those routes are weaker than expected, keep the plan’s fallback rule: do not ship broken click-through just to satisfy the section visually.

## Risks / Trade-offs

- **Kitsu reverse-credit lookup may be weaker than the forward anime-detail graph.**
  - Mitigation: validate reverse relationships first and keep company/cast click-through gated on stable provider-backed identity.
- **Provider-aware entity routing expands shared models and cache formats.**
  - Mitigation: keep the model extension explicit and add cache regression coverage.
- **Anime-specific tab logic could drift away from movie/TV detail.**
  - Mitigation: change only tab composition and datasets, not the overall screen architecture or focus model.
- **There is a temptation to map Kitsu `Related` into existing TMDB recommendation semantics.**
  - Mitigation: keep the related dataset separate and name it correctly in state and UI.

## Open Questions

### Resolved by this plan

- **Should anime get a separate detail screen?**
  - No. Reuse the existing detail screen with provider-conditional lower content.

- **Should anime retain ratings and reviews tabs for parity optics?**
  - No. Remove them for Kitsu-backed anime detail.

- **Should cast and production click-through be in scope now?**
  - Yes, but only with provider-aware identities and reliable Kitsu reverse lookup.

### Deferred to implementation

- Validate the best Kitsu reverse-lookup path for person filmography:
  - direct relation links from `/people/{id}`
  - `castings`-based traversal
  - `anime-staff`-based traversal
- Validate the best Kitsu producer/company reverse-lookup path:
  - direct relation links from `/producers/{id}`
  - `anime-productions?filter[producerId]=...`
- Decide whether Kitsu “creator/director/writer” credits belong in leading cast, a separate staff grouping, or are merged into cast cards for v1.
- Decide whether anime production rows should include network-like sections if a meaningful public Kitsu equivalent exists.

## Verification Checklist

- Anime detail for a mapped `kitsu:{id}` title shows:
  - populated character rail
  - `Related` tab
  - no ratings tab
  - no reviews tab
  - production row only when reliable
- Character cards are clickable only when TMDB person search yields a strong exact-name match, and then open the existing cast-detail modal.
- Production cards are clickable only when provider-backed company lookup is reliable.
- Related items navigate back into normal detail flows with launchable ids.
- TV/movie detail tabs and organization/cast routes remain intact for TMDB/TVDB-backed content.
