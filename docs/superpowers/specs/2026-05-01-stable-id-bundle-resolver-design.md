# Stable ID Bundle Resolver Design

## Context

NEXIO now has the pieces of a rail-preview-first architecture:

- `RailItemPreview.stableIds` stores provider IDs emitted by built-in rail mappers.
- `MetaPreview.firstPaintStableIds` carries those IDs through the existing first-paint model.
- `RailIdentityHarvester` persists explicit rail ID facts into `IdMappingStore`.
- `MetadataIdentityResolver` resolves provider-native routing conflicts after `MetadataRouter` selects a primary provider.
- Rating enrichment still frequently starts from `MetaPreview.id`, so TMDB/Kitsu rails can miss IMDb-backed rating APIs when IMDb is only present as a sidecar stable ID or must be resolved after first paint.

The current gap is not that IDs are absent everywhere. The gap is that no single contract says which IDs are required for canonical metadata, which IDs are sidecars for ratings/anime bridges, and which IDs are only source facts. That makes home rails, detail screens, Continue Watching, player flows, and rating enrichment resolve IDs opportunistically.

## Goal

Add a shared `StableIdBundleResolver` that prepares stable IDs for all catalog and rail items after first paint, without introducing a parallel rendering, hydration, or rating path.

The resolver must return:

- the canonical provider ID needed by the routed primary metadata authority
- the IMDb sidecar ID needed by IMDb-backed rating APIs when available
- anime bridge sidecars such as MAL, AniList, and AniDB when available
- source evidence for trace and cache ownership

It must not fetch or chase Trakt or Simkl IDs. Trakt and Simkl scrobble APIs accept common external IDs such as IMDb, TMDB, TVDB, Kitsu, MAL, AniList, and AniDB, so provider-native Trakt/Simkl IDs are not scrobble prerequisites.

## Non-Goals

- Do not block first paint.
- Do not change MetadataRouter authority rules.
- Do not add provider-specific home renderers.
- Do not add provider-specific hydration schedulers.
- Do not add a separate rating-rendering lifecycle.
- Do not create a second identity database outside `IdMappingStore`.
- Do not resolve missing Trakt or Simkl IDs for scrobbling.

## Architecture

The stable ID bundle resolver lives inside the existing metadata identity layer.

```text
Rail/addon payload
    -> FirstPaintPreview / MetaPreview / RailItemPreview
    -> render immediately
    -> visible/focused/adjacent/detail trigger
    -> MetadataRouter selects primary authority
    -> StableIdBundleResolver resolves needed stable IDs
    -> ProviderPlanExecutor / ProviderPlanRunner
    -> FieldResolver
    -> home/detail repaint with resolved metadata
```

`MetadataRouter` continues to decide ownership:

```text
movie -> TMDB
tv/series -> TVDB
anime -> Kitsu
```

`StableIdBundleResolver` only answers:

```text
Given the selected authority and known IDs, which provider-native canonical ID and sidecar IDs are ready?
```

## Data Model

```kotlin
data class StableIdBundle(
    val itemKey: String,
    val itemType: ContentType,
    val canonical: CanonicalStableIds,
    val sidecars: SidecarStableIds,
    val source: SourceStableIds,
    val status: StableIdBundleStatus,
    val evidence: List<StableIdEvidence>,
    val resolvedAtMs: Long
)

data class CanonicalStableIds(
    val tmdbMovieId: String? = null,
    val tvdbSeriesId: String? = null,
    val kitsuAnimeId: String? = null
)

data class SidecarStableIds(
    val imdbId: String? = null,
    val malId: String? = null,
    val anilistId: String? = null,
    val anidbId: String? = null
)

data class SourceStableIds(
    val sourceProvider: ProviderId?,
    val sourceItemId: String?,
    val railId: String?,
    val observedIds: ProviderIds
)

enum class StableIdBundleStatus {
    PREVIEW_IDS_ONLY,
    CANONICAL_READY,
    CANONICAL_AND_RATING_READY,
    CANONICAL_READY_RATING_UNRESOLVED,
    UNRESOLVED,
    NEGATIVE_CACHED
}
```

The model separates ID purpose:

- canonical IDs are required for canonical metadata hydration
- IMDb is required for IMDb-backed title and episode ratings
- MAL, AniList, and AniDB are sidecars for anime identity bridges
- Trakt and Simkl are source facts only

`ProviderIds` can still carry `trakt` and `simkl` when a provider payload explicitly includes them, but those fields are not target IDs.

## Resolution Flow

Resolution is cache-first and starts only after first paint.

```text
1. Start with RailItemPreview.stableIds or MetaPreview.firstPaintStableIds.
2. Harvest direct facts into IdMappingStore.
3. MetadataRouter chooses primary authority.
4. Resolve canonical target ID for that authority.
5. Resolve IMDb sidecar when needed for ratings.
6. Return StableIdBundle.
7. Existing hydration writes HomeDisplayMetadata / ResolvedMetadataDocument and triggers repaint.
```

### Movie

```text
canonical target = TMDB

tmdb present:
  canonical ready

imdb present and tmdb missing:
  use IdMappingStore
  then TMDB find external ID

tmdb present and imdb missing:
  use IdMappingStore
  then tmdb.movie.external_ids
  then tmdb.movie.core only if the existing core response already includes external_ids
```

### TV

```text
canonical target = TVDB

tvdb present:
  canonical ready

imdb present and tvdb missing:
  use IdMappingStore
  then TVDB remote ID lookup

tmdb present and tvdb missing:
  use IdMappingStore
  then tmdb.tv.external_ids
  then tmdb.tv.core only if the existing core response already includes external_ids

tvdb present and imdb missing:
  use IdMappingStore
  then tvdb.series.extended remote IDs
```

Do not convert `tmdb:{id}` series items into `tvdb:{same-id}`. The bridge must be explicit and traced.

### Anime

```text
canonical target = Kitsu

kitsu present:
  canonical ready

mal/anilist/anidb/imdb present and kitsu missing:
  use IdMappingStore
  then AnimeIdentityIndex / Fribb

kitsu present and imdb missing:
  use IdMappingStore
  do not call a new remote bridge in this change
  leave IMDb unresolved unless IdMappingStore or the bundled anime index already has it
```

Do not send `kitsu:{id}` through Fribb. It is already canonical for anime.

## Rating Enrichment

Rating enrichment must consume the same stable ID bundle.

```text
visible/focused/adjacent item
    -> StableIdBundleResolver
    -> if imdb is available, TitleRatingOverrideRepository uses imdb directly
    -> if canonical metadata is ready, MetadataRouter hydration proceeds
    -> existing home metadata cache/state update triggers repaint
```

This avoids the current pattern where `TitleRatingOverrideRepository` tries to infer IMDb from `preview.id` and fallback IDs even when the correct IMDb sidecar is already known elsewhere.

IMDb unresolved is not a canonical metadata failure. The item can still hydrate through TMDB, TVDB, or Kitsu and retain the existing source rating or no rating.

## Cache Policy

`IdMappingStore` remains the single shared identity cache.

TTL policy:

```text
Provider-confirmed positive mapping:
  ttl = 7 days
  examples: TMDB find, TVDB remoteid, TMDB external IDs, TVDB extended remote IDs

Rail-payload explicit mapping:
  ttl = 7 days
  examples: Trakt IDs block says imdb/tmdb/tvdb; Simkl JSON says imdb/tmdb/tvdb/kitsu

Static anime-index mapping:
  ttl = 30 days or index-version-bound
  because the source index is bundled/refreshed as data

Negative mapping:
  default ttl = 24 hours
  ttl = 7 days only for repeated detail misses backed by stable provider evidence
```

Single-flight key:

```text
sourceScheme + sourceId + targetScheme + itemType + policyVersion
```

Cache ownership:

```text
Rail membership owns row presence.
Rail preview cache owns source display seed.
IdMappingStore owns stable ID facts.
Canonical metadata cache owns provider metadata.
Ratings cache owns rating payloads by IMDb ID.
```

Rail cleanup must remove membership and preview records only. Stable ID facts expire through TTL or explicit invalidation and should not be deleted just because one rail is removed.

## Execution Boundaries

First paint:

```text
render source payload only
no MetadataRouter
no StableIdBundleResolver
no per-item rating API
```

After first paint:

```text
visible/focused/adjacent scheduler asks for StableIdBundle
bundle resolution may use IdMappingStore or runtime-covered bridge calls
existing MetadataRouter hydration continues once canonical ID is ready
existing rating enrichment uses IMDb sidecar once available
existing home display metadata write triggers card/hero repaint
```

Allowed provider-specific code:

```text
rail fetch
rail preview mapper
identity bridge provider implementation
```

Forbidden provider-specific code:

```text
home card renderer
hydration scheduler
final field merge
rating enrichment path
Trakt/Simkl ID lookup for scrobbling
```

## Scrobble Identity Policy

Trakt and Simkl scrobble calls should pass the best already-known supported IDs.

Supported evidence includes:

```text
imdb
tmdb
tvdb
kitsu
mal
anilist
anidb
title
year
```

The app must not perform network lookups to obtain missing Trakt or Simkl IDs for scrobbling. Provider-native Trakt/Simkl IDs are useful only when they are already present as observed source facts.

## Failure Behavior

Canonical ID unresolved:

```text
keep preview
emit typed identity failure
do not call provider detail with the wrong ID
```

IMDb unresolved:

```text
keep canonical metadata if available
keep existing rating or no rating
do not block home/detail hydration
```

Bridge/provider failure:

```text
negative-cache according to TTL
keep preview or cached metadata
do not retry hot in visible-home loops
```

## Trace And Report Events

Add `metadata.stable_id_bundle` trace/report events.

```json
{
  "eventType": "metadata.stable_id_bundle",
  "payload": {
    "itemKey": "movie:tmdb:1007757",
    "firstPaintSource": "RAIL_PREVIEW",
    "canonicalTarget": "TMDB",
    "canonicalId": "tmdb:1007757",
    "sidecarIds": {
      "imdb": "tt..."
    },
    "status": "CANONICAL_AND_RATING_READY",
    "networkExecuted": true,
    "apiShapeIds": ["tmdb.movie.external_ids"],
    "trigger": "VISIBLE_HOME_HYDRATION"
  }
}
```

Reports must prove:

- first paint source remains `RAIL_PREVIEW` or `ADDON_META_PREVIEW`
- stable ID bundle resolution does not run before first paint
- resolution runs after first paint for visible, focused, adjacent, and detail-triggered items
- IMDb sidecar feeds rating enrichment
- canonical ID feeds existing MetadataRouter hydration
- Trakt and Simkl IDs are not chased
- unresolved IMDb does not block canonical hydration
- unresolved canonical IDs prevent wrong-provider detail calls

## Test Plan

Add focused unit and integration tests:

```text
stable_id_bundle_trakt_tv_uses_known_tvdb_and_imdb_without_network
stable_id_bundle_simkl_movie_uses_known_tmdb_and_imdb_without_network
stable_id_bundle_tmdb_movie_resolves_imdb_sidecar_after_first_paint
stable_id_bundle_tmdb_tv_resolves_tvdb_canonical_and_imdb_sidecar
stable_id_bundle_kitsu_id_is_canonical_without_fribb
stable_id_bundle_kitsu_movie_subtype_preserves_movie_detail_type
stable_id_bundle_does_not_resolve_trakt_or_simkl_tracking_ids
stable_id_bundle_negative_cache_suppresses_repeat_bridge_lookup
stable_id_bundle_visible_resolution_does_not_block_first_paint
stable_id_bundle_ready_triggers_existing_home_metadata_repaint
```

Add architecture checks:

```text
home_renderer_does_not_import_stable_id_bundle
rating_enrichment_consumes_shared_stable_id_bundle
trakt_simkl_scrobble_paths_do_not_request_provider_native_tracking_ids
provider_plan_executor_rejects_unresolved_canonical_id
```

## Acceptance Criteria

- First paint remains immediate for addon and built-in rails.
- Visible TMDB movie rails can resolve IMDb sidecars after first paint and repaint with IMDb-backed ratings.
- TMDB TV rails resolve TVDB canonical IDs before TVDB detail calls.
- Kitsu rails preserve the correct movie/series subtype and canonical Kitsu identity.
- Trakt rails use known `tvdb`/`tmdb`/`imdb` payload IDs without provider-native Trakt ID lookups.
- Simkl scrobble uses common supported IDs and never requires a Simkl ID lookup.
- All identity bridge work goes through runtime-covered provider shapes.
- `IdMappingStore` is the only durable identity fact store.
