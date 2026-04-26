You are not missing it — **the spec mentions ArtworkRouter, but it does not explicitly define artwork decision caching / policy-version invalidation**. That gap could absolutely cause premium posters to not surface if a stale resolved document or artwork decision is reused.

Below is the corrected full spec with the missing cache-layer rules added.

---

# Engineering Spec: MetadataRouter + ResolverOrchestrator

## Goal

Build the metadata architecture above `IntegrationRuntime` so Nexio has:

```text
1. one deterministic primary metadata route
2. one orchestration layer for secondary resolvers
3. one field-resolution layer that controls final output
4. separate caches for provider metadata, resolver decisions, and image bytes
5. no secondary provider overwriting primary-owned fields
```

This spec is the handoff for writing the implementation plan.

---

## Target architecture

```text
Catalog / Detail / Continue Watching / Player
    ↓
MetadataRepository
    ↓
MetadataRequestNormalizer
    ↓
MetadataRouter
    ↓
ProviderPlanExecutor
      ├── TmdbPrimaryProvider
      ├── TvdbPrimaryProvider
      └── KitsuPrimaryProvider
    ↓
Canonical metadata candidates
    ↓
ResolverOrchestrator
      ├── RatingResolver
      ├── ArtworkRouter
      ├── ReviewResolver
      ├── TrackingResolver
      ├── SkipSegmentResolver
      ├── TrailerResolver
      ├── RecommendationResolver
      └── OrganizationPersonResolver
    ↓
FieldResolver
    ↓
ResolvedMetadataDocument / HomeDisplayMetadata
    ↓
UI / Player
```

`IntegrationRuntime` remains below provider adapters and secondary resolver adapters. The router and resolver layers must not call Retrofit/provider clients directly.

---

# 1. Non-goals

Do not implement:

```text
- a generic “all providers enrich everything” model
- direct provider calls from ViewModels
- primary metadata decisions inside IntegrationRuntime
- field merging inside provider adapters
- anime guessing from weak catalog/addon heuristics
- secondary provider overwrites of primary-owned fields
- storing only one final poster URL inside TMDB/TVDB/Kitsu metadata cache
```

---

# 2. Core responsibilities

## MetadataRouter

Answers:

```text
Who is the primary metadata authority for this title?
```

Rules:

```text
anime prefix → Kitsu
Fribb / AnimeIdentityIndex hit → Kitsu
series → TVDB
movie → TMDB
```

The router returns:

```kotlin
data class MetadataRoute(
    val provider: PrimaryProvider,
    val parentId: String,
    val mediaKind: MediaKind,
    val decisionReason: RouteDecisionReason,
    val kitsuId: String? = null,
    val trace: List<RouteTraceEntry>
)
```

The router must not fetch metadata.

---

## ProviderPlanExecutor

Answers:

```text
What primary data should be fetched for this route and depth?
```

Example:

```text
DETAIL_CORE movie → TMDB movie core
DETAIL_CORE TV → TVDB series extended + optional translation
DETAIL_CORE anime → Kitsu anime core
SEASON TV → TVDB season episode batch
SEASON anime → Kitsu episode pages
```

---

## ResolverOrchestrator

Answers:

```text
Which secondary modules should run for this request depth?
```

It invokes secondary resolvers, but only returns candidates.

It must not decide final title/poster/rating/etc.

---

## FieldResolver

Answers:

```text
Which candidate becomes the final user-visible field?
```

It enforces:

```text
primary provider wins primary-owned fields
secondary providers only fill allowed fields
fallbacks are typed and traceable
forbidden overwrites are ignored and logged
```

---

# 3. Primary provider ownership

## Movie

Primary provider:

```text
TMDB
```

Owned fields:

```text
title
localized title
overview
release date
runtime
genres
age rating
country
language
cast
crew
production companies
collection
artwork candidates
external ids
trailer candidates when requested
```

## TV

Primary provider:

```text
TVDB
```

Owned fields:

```text
series title
overview
translations
status
season order
episode list
episode numbering
episode titles
episode descriptions
networks
companies
cast
TVDB artwork candidates
```

## Anime

Primary provider:

```text
Kitsu
```

Owned fields:

```text
canonical title
native titles
alternate titles
synopsis
status
age rating
episode length
episode list
episode numbering
episode titles
episode descriptions
characters
voice actors
staff
productions
related anime
poster candidates
cover candidates
```

---

# 4. Secondary resolver ownership

| Resolver                     | Providers                                             | Owns                                   | Must not own                       |
| ---------------------------- | ----------------------------------------------------- | -------------------------------------- | ---------------------------------- |
| `RatingResolver`             | Custom IMDb, MDBList, OMDb, primary provider fallback | ratings only                           | title, overview, poster, episodes  |
| `ArtworkRouter`              | Top-Posters, RPDB, primary artwork, addon artwork     | final artwork selection                | identity, title, overview, ratings |
| `ReviewResolver`             | Trakt, TMDB reviews                                   | comments/reviews                       | title metadata                     |
| `TrackingResolver`           | Trakt, Simkl                                          | progress, scrobble, watched/list state | primary metadata                   |
| `SkipSegmentResolver`        | TheIntroDB, AniSkip, AnimeSkip                        | intro/outro/recap/credits timestamps   | metadata fields                    |
| `TrailerResolver`            | TMDB, TVDB, Streailer, fallback YouTube               | trailer/teaser/recap media candidates  | primary metadata                   |
| `RecommendationResolver`     | TMDB, Kitsu related titles, Trakt rows                | related/recommended item candidates    | primary fields                     |
| `OrganizationPersonResolver` | TMDB, TVDB, Kitsu bridges                             | person/company/network detail pages    | title ownership                    |

---

# 5. Requested depth behavior

```kotlin
enum class MetadataDepth {
    PREVIEW,
    DETAIL_CORE,
    DETAIL_MEDIA,
    DETAIL_SECONDARY,
    SEASON,
    PERSON,
    ORGANIZATION,
    PLAYER
}
```

## PREVIEW

Behavior:

```text
use addon MetaPreview immediately
no required provider call
no secondary network by default
optional cached rating/artwork decisions only
```

## DETAIL_CORE

Behavior:

```text
primary provider core only
RatingResolver may run if cached or cheap
ArtworkRouter may select premium poster if configured
```

## DETAIL_MEDIA

Behavior:

```text
TrailerResolver
ArtworkRouter thumbnails/logos if visible
```

## DETAIL_SECONDARY

Behavior:

```text
ReviewResolver
RecommendationResolver
related titles
collection
advanced anime detail
```

## SEASON

Behavior:

```text
primary episode provider
episode rating resolver
skip segments only if player/episode context requires them
```

## PLAYER

Behavior:

```text
TrackingResolver scrobble/progress
SkipSegmentResolver
no broad metadata prefetch
```

---

# 6. Rating precedence

## Title ratings

```text
1. Custom IMDb ratings API
2. MDBList
3. OMDb
4. Primary provider rating
5. Addon-bundled rating fallback
```

## Episode ratings

```text
1. Custom IMDb episode ratings API
2. OMDb season/episode ratings
3. TMDB season/episode vote average, if used as fallback
4. Addon-bundled episode rating
```

MDBList episode ratings are opt-in only.

---

# 7. Artwork precedence and cache policy

Only one premium provider may be active:

```text
Top-Posters xor RPDB
```

Precedence:

```text
1. User-selected premium provider
   a. Top-Posters
   b. RPDB
2. Primary provider artwork candidates
   a. TMDB for movies
   b. TVDB for TV
   c. Kitsu for anime
3. Addon-bundled poster/background/logo
4. Placeholder
```

Premium artwork providers must not change metadata identity.

## Critical cache rule

Provider metadata freshness must not imply final artwork decision freshness.

The system must store artwork in three layers:

```text
1. Primary metadata cache
   TMDB/TVDB/Kitsu metadata and artwork candidates

2. Artwork decision cache
   final selected poster/logo/backdrop/thumbnail for the current artwork policy

3. Image/blob cache
   downloaded image bytes
```

Do **not** store only the final poster URL in the primary metadata cache.

When the user enables, disables, or changes Top-Posters/RPDB settings, the artwork policy version changes. This must invalidate or miss the old artwork decision cache even if TMDB/TVDB/Kitsu metadata is still fresh.

Example artwork decision cache key:

```text
artwork:poster:
  contentIdentity={canonical id}
  mediaKind={movie|tv|anime}
  primaryProvider={TMDB|TVDB|KITSU}
  primaryArtworkCandidateVersion={v}
  premiumProvider={NONE|TOP_POSTERS|RPDB}
  premiumCredentialHash={hash or none}
  premiumStyle={style/profile}
  language={language}
  region={region}
  season={optional}
  episode={optional}
  artworkPolicyVersion={version}
  schemaVersion={version}
```

Top-Posters thumbnail keys must also include output-varying options:

```text
badge_position
badge_size
blur
trend
style
fallback_url_hash
user_agent_profile_id
season
episode
```

## Artwork settings change behavior

When any of these change:

```text
premium provider enabled/disabled
Top-Posters ↔ RPDB switch
premium API key
poster style
language
badge options
thumbnail options
artwork provider priority
```

the app must invalidate or version-bump:

```text
artwork decision cache
resolved metadata document cache
HomeDisplayMetadata cache
premium image/blob cache entries if provider/key/style changed
```

The app must **not** invalidate all TMDB/TVDB/Kitsu metadata caches unless the primary metadata policy changes.

---

# 8. Skip segment placement

TheIntroDB must not be part of `MetadataRouter`.

Placement:

```text
Player / Episode context
    ↓
SkipSegmentResolver
    ↓
TheIntroDB / AniSkip / AnimeSkip
    ↓
IntegrationRuntime
```

Policy:

```text
non-anime movie/TV → TheIntroDB
anime → AniSkip / AnimeSkip first
TheIntroDB for anime only if policy allows fallback
```

Cache class:

```text
EpisodeImmutable / long-lived CacheFirst
```

---

# 9. Trakt and Simkl placement

Trakt and Simkl do not decide primary metadata authority.

They produce:

```text
ContinueWatchingEntry
WatchState
ScrobbleMutation
UserLibrarySnapshot
ProviderCatalogRail
ReviewCandidates, Trakt only
```

Flow:

```text
Trakt/Simkl row
    ↓
normalize ids
    ↓
MetadataRouter chooses TMDB/TVDB/Kitsu
    ↓
primary provider fetches canonical metadata
    ↓
TrackingResolver overlays watched/progress/list state
```

For Continue Watching:

```text
Trakt/Simkl CW row
    ↓
normalize parent id
    ↓
route from parent id
    ↓
fetch canonical parent/episode metadata if needed
    ↓
apply progress overlay
```

---

# 10. MDBList placement

MDBList has two roles:

```text
RatingResolver
CatalogRailResolver
```

For catalog rows:

```text
MDBList row
    ↓
id normalization
    ↓
MetadataRouter
    ↓
TMDB / TVDB / Kitsu canonical metadata
```

MDBList must not own title, overview, poster, episode list, or identity.

---

# 11. Candidate model

Provider adapters and resolvers return candidates, not final fields.

```kotlin
data class MetadataCandidateSet(
    val identity: ContentIdentity,
    val route: MetadataRoute,
    val titleCandidates: List<FieldCandidate<String>>,
    val overviewCandidates: List<FieldCandidate<String>>,
    val runtimeCandidates: List<FieldCandidate<Int>>,
    val artworkCandidates: List<ArtworkCandidate>,
    val ratingCandidates: List<RatingCandidate>,
    val episodeCandidates: List<EpisodeCandidate>,
    val castCandidates: List<CastCandidate>,
    val trailerCandidates: List<TrailerCandidate>,
    val skipSegmentCandidates: List<SkipSegmentCandidate>,
    val reviewCandidates: List<ReviewCandidate>,
    val trackingCandidates: List<TrackingCandidate>
)
```

Each candidate must include:

```kotlin
data class FieldCandidate<T>(
    val value: T,
    val sourceProvider: ProviderId,
    val sourceRole: SourceRole,
    val confidence: Confidence,
    val fallbackReason: FallbackReason? = null,
    val trace: List<String> = emptyList()
)
```

Artwork-specific candidates must include enough data to recompute final selection when artwork settings change:

```kotlin
data class ArtworkCandidate(
    val kind: ArtworkKind, // poster, backdrop, logo, thumbnail
    val uri: String?,
    val provider: ProviderId,
    val sourceRole: SourceRole,
    val identity: ContentIdentity,
    val language: String?,
    val width: Int?,
    val height: Int?,
    val styleProfile: String?,
    val requiresPremiumFetch: Boolean,
    val trace: List<String>
)
```

---

# 12. FieldResolver rules

FieldResolver must enforce:

```text
primary provider owns primary fields
secondary providers may only fill explicitly allowed fields
premium artwork only affects artwork
ratings only affect rating fields
tracking only affects watched/progress/list state
skip segments only affect player skip data
fallback values must be marked as fallback
forbidden overwrite attempts are logged
```

Example rules:

```text
Kitsu title cannot be overwritten by TMDB/TVDB
TVDB episode numbering cannot be overwritten by TMDB
TMDB movie title cannot be overwritten by addon metadata after canonical success
Top-Posters can select final poster but cannot change identity
MDBList can supply rating but cannot change title/poster
```

---

# 13. Fallback model

Fallback must be typed.

```kotlin
enum class FallbackReason {
    NONE,
    PRIMARY_ID_MISSING,
    PRIMARY_ID_UNRESOLVED,
    PRIMARY_PROVIDER_DISABLED,
    PRIMARY_PROVIDER_UNHEALTHY,
    PRIMARY_ENDPOINT_FAILED,
    FIELD_NOT_SUPPORTED_BY_PRIMARY,
    USER_POLICY_ALLOW_SUPPLEMENT,
    CACHE_STALE_USED,
    ADDON_FALLBACK_USED,
    ARTWORK_POLICY_CHANGED,
    ARTWORK_PREMIUM_PROVIDER_UNAVAILABLE
}
```

Output:

```kotlin
data class MetadataResolutionResult(
    val document: ResolvedMetadataDocument?,
    val route: MetadataRoute,
    val candidateSet: MetadataCandidateSet,
    val fallbackReason: FallbackReason?,
    val warnings: List<RoutingWarning>,
    val trace: List<RouteTraceEntry>
)
```

---

# 14. Storage model

Store separately:

```text
raw provider payloads
normalized candidates
resolved metadata documents
artwork decision cache
image/blob cache
route traces
click-time addon display metadata
tracking/progress state
```

Do not store only final merged `Meta`.

## Resolved document cache key

Resolved metadata documents must include resolver policy versions:

```text
resolvedDocument:
  contentIdentity
  mediaKind
  primaryProvider
  primaryPayloadVersion
  fieldOwnershipPolicyVersion
  ratingPolicyVersion
  artworkPolicyVersion
  trailerPolicyVersion
  language
  region
  depth
  schemaVersion
```

This ensures that changing artwork, rating, or field-selection policy recomputes the resolved document without invalidating raw provider payloads.

---

# 15. Continue Watching requirements

For local playback-started items:

```text
persist parentId
persist chosen provider
persist click-time HomeDisplayMetadata
```

For Trakt/Simkl-imported items:

```text
normalize provider IDs
resolve parent master id
store parentId
route from parentId
```

CW must not route from raw episode id.

Merge order:

```text
canonical refetch
click-time addon metadata
existing persisted fallback
placeholder
```

---

# 16. IntegrationRuntime requirements

All provider and resolver calls must go through `IntegrationRuntime`.

Forbidden:

```text
ViewModel → Retrofit
ViewModel → IntegrationRuntime
MetadataRouter → Retrofit
ResolverOrchestrator → Retrofit
FieldResolver → Retrofit
```

Allowed:

```text
Provider adapter → IntegrationRuntime → existing Retrofit/auth service
Resolver adapter → IntegrationRuntime → existing Retrofit/auth service
```

---

# 17. Implementation phases

## Phase 1 — Primary routing foundation

Deliver:

```text
AnimeIdentityIndex
MetadataRouter
parentIdOf()
IdMappingStore
catalog cross-ref harvest
route trace
```

Exit criteria:

```text
Crunchyroll IMDb anime routes Kitsu via Fribb
non-anime IMDb movie routes TMDB
non-anime IMDb series routes TVDB
Disney mixed row uses per-item type
unknown anime-like Fribb miss does not guess Kitsu
```

## Phase 2 — Primary provider plans

Deliver:

```text
MoviePrimaryPlan → TMDB
TvPrimaryPlan → TVDB
AnimePrimaryPlan → Kitsu
ProviderPlanExecutor
primary candidate mappers
```

Exit criteria:

```text
DETAIL_CORE fetches only one primary authority path
SEASON fetches primary episode path
provider calls go through IntegrationRuntime
```

## Phase 3 — ResolverOrchestrator

Deliver:

```text
ResolverOrchestrator
RatingResolver
ArtworkRouter
TrackingResolver
SkipSegmentResolver
TrailerResolver
ReviewResolver skeleton
```

Exit criteria:

```text
secondary resolvers return candidates only
requested depth controls resolver execution
no secondary resolver overwrites primary-owned fields
```

## Phase 4 — Artwork decision cache and policy invalidation

Deliver:

```text
ArtworkDecisionCache
ArtworkPolicyVersion
premium provider mutual-exclusion setting
Top-Posters/RPDB decision keys
artwork settings invalidation
resolved document policy-version invalidation
```

Exit criteria:

```text
enabling Top-Posters/RPDB updates posters without refetching TMDB/TVDB/Kitsu metadata
switching Top-Posters ↔ RPDB recomputes artwork decisions
changing premium API key/style/language invalidates artwork decisions
primary metadata cache remains valid across artwork provider setting changes
```

## Phase 5 — FieldResolver

Deliver:

```text
FieldOwnershipPolicy
FieldResolver
ResolvedMetadataDocument
candidate source trace
forbidden overwrite logging
```

Exit criteria:

```text
Kitsu title cannot be overwritten
TVDB episode numbering cannot be overwritten
premium poster only affects artwork
ratings only affect ratings
```

## Phase 6 — Continue Watching parity

Deliver:

```text
parentId on WatchProgress
provider on locally started playback
click-time metadata capture
CW normalized parent routing
Trakt/Simkl CW normalization
```

Exit criteria:

```text
CW does not degrade poster/title after playback
CW does not re-route from raw episode id
offline CW still renders click-time fallback
```

## Phase 7 — Audit and guardrails

Deliver tests and architecture checks:

```text
no direct Retrofit/provider calls from feature code
no IntegrationRuntime injection into ViewModels
all resolver calls are IntegrationRuntime governed
all candidates have source trace
all fallbacks typed
all forbidden overwrites logged
artwork decision cache keys include artwork policy version
resolved document cache keys include resolver policy versions
```

---

# 18. Required tests

## Routing tests

```text
kitsu:7442 routes Kitsu
tt12343534 series with Fribb hit routes Kitsu
tt16431404 movie with Fribb miss routes TMDB
tt14403178 series with Fribb miss routes TVDB
Disney mixed row uses per-item type
unknown Crunchyroll-like tt series with Fribb miss routes TVDB
```

## Parent ID tests

```text
tt12343534:1:1 → tt12343534
kitsu:7442:1:1 → kitsu:7442
tmdb:550 → tmdb:550
tvdb:399838:1:1 → tvdb:399838
```

## Resolver tests

```text
PREVIEW does not trigger secondary network
DETAIL_CORE runs primary provider only
DETAIL_SECONDARY runs reviews/recommendations
PLAYER runs tracking and skip segments only
```

## Artwork tests

```text
TMDB/TVDB/Kitsu metadata cache can stay fresh while premium poster changes
enabling Top-Posters causes ArtworkRouter to recompute final poster
enabling RPDB causes ArtworkRouter to recompute final poster
switching Top-Posters ↔ RPDB invalidates artwork decision cache
changing premium API key invalidates premium artwork decision/cache namespace
changing poster style/language/badge options changes artwork decision cache key
Top-Posters/RPDB cannot change content identity
resolved document cache includes artworkPolicyVersion
```

## Field ownership tests

```text
Kitsu title cannot be overwritten by TMDB
TVDB episode list cannot be overwritten by TMDB
TMDB movie title cannot be overwritten by addon fallback after canonical success
MDBList cannot overwrite overview/poster/title
Top-Posters cannot change identity
TheIntroDB cannot affect metadata fields
```

## Continue Watching tests

```text
local playback stores parentId/provider/click metadata
Trakt CW normalizes parent id
Simkl CW normalizes parent id
CW render uses canonical → click-time fallback → persisted fallback
```

---

# 19. Agentic coding prompt

```text
Implement the MetadataRouter + ResolverOrchestrator layer above IntegrationRuntime.

The goal is to separate:
- primary metadata authority decisions
- secondary resolver enrichment
- final field ownership
- provider execution control
- resolver-policy-specific decision caches

Do not call provider clients directly from ViewModels, routers, resolvers, or FieldResolver. Provider/resolver adapters must go through IntegrationRuntime.

Implement the architecture in phases:

1. MetadataRouter
- route raw id + per-item type to TMDB, TVDB, or Kitsu.
- anime prefix and local anime id-map hit route to Kitsu.
- movie routes TMDB.
- series routes TVDB.
- do not guess anime from addon name, genre, or catalog name.
- return route trace and normalized parent id.

2. ProviderPlanExecutor
- execute primary-provider plans based on route and MetadataDepth.
- movie core uses TMDB.
- TV core uses TVDB.
- anime core uses Kitsu.
- season depth uses provider-specific episode batch/page routes.

3. ResolverOrchestrator
- run secondary modules based on requested depth.
- PREVIEW: no secondary network by default.
- DETAIL_CORE: primary core plus optional cached ratings/artwork.
- DETAIL_MEDIA: trailers/artwork media.
- DETAIL_SECONDARY: reviews/recommendations/related/advanced anime.
- SEASON: episodes + episode ratings.
- PLAYER: tracking and skip segments.

4. Secondary modules
- RatingResolver handles Custom IMDb, MDBList, OMDb, primary fallback ratings.
- ArtworkRouter handles Top-Posters/RPDB/primary/addon artwork.
- TrackingResolver handles Trakt/Simkl progress/scrobble/list state.
- SkipSegmentResolver handles TheIntroDB/AniSkip/AnimeSkip.
- TrailerResolver handles TMDB/TVDB/Streailer/fallback YouTube.
- ReviewResolver handles Trakt/TMDB reviews.
- CatalogRailResolver normalizes rows from Trakt/Simkl/MDBList/addons.

5. Artwork cache design
- Store provider metadata, artwork decisions, and image bytes separately.
- Do not store only final poster URL inside primary metadata cache.
- Artwork decision cache keys must include premium provider, credential hash, style/profile, language/region, season/episode where relevant, artworkPolicyVersion, and schemaVersion.
- Resolved document cache keys must include artworkPolicyVersion.
- When Top-Posters/RPDB settings change, invalidate or version-bump artwork decision cache and resolved document cache.
- Do not invalidate TMDB/TVDB/Kitsu metadata merely because premium poster settings changed.

6. FieldResolver
- merge candidates into ResolvedMetadataDocument.
- enforce provider ownership.
- log forbidden overwrites.
- mark fallback reason.
- preserve source trace.

7. Continue Watching
- store parentId.
- store provider for locally started playback.
- capture click-time HomeDisplayMetadata.
- normalize Trakt/Simkl CW rows to parent id.
- never route from raw episode id.

Acceptance:
- all primary and secondary provider calls go through IntegrationRuntime.
- MetadataRouter never fetches provider data.
- ResolverOrchestrator never overwrites fields.
- FieldResolver is the only final field-selection authority.
- PREVIEW renders from addon metadata immediately.
- canonical metadata replaces addon fields only on success.
- secondary providers cannot overwrite primary-owned fields.
- enabling Top-Posters/RPDB updates artwork even when primary provider metadata cache is still fresh.
```

---

# Final recommendation

Use this corrected spec. The missing concept was:

```text
Artwork decisions are resolver-policy outputs, not primary metadata payloads.
```

So the cache model must be:

```text
raw provider payload cache
candidate cache
resolver decision cache
resolved document cache
image/blob cache
```

That ensures premium poster changes surface immediately without wasting valid TMDB/TVDB/Kitsu metadata.

