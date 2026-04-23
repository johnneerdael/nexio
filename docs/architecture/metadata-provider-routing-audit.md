---
title: Metadata Provider Routing Audit
status: superseded
date: 2026-04-22
supersedes:
  - docs/architecture/metadata-provider-routing-audit.errata.md (merged in)
---

# Metadata Provider Routing Audit

> Superseded by `plans/2026-04-22-005-metadata-provider-system-audit.md`, which is the merged current-state source of truth after the final reconciliation pass.

This document remains as a working draft snapshot. The authoritative merged audit now lives in `plans/2026-04-22-005-metadata-provider-system-audit.md`.

This audit reflects the checked-in Android code in this repo plus the three named external addon manifests fetched on April 22, 2026. It documents current behavior only. It does not recommend a target-state rearchitecture except where the current implementation is ambiguous, duplicated, or internally inconsistent.

## Scope

- Included surfaces:
  - external addon catalog rows
  - built-in TMDB, Kitsu, Trakt, Simkl, and MDBList home rows
  - continue watching and next-up rows
  - detail title enrichment for movie, TV, and anime
  - person, company, and network detail flows
  - title ratings, episode ratings, posters, trailers, recaps, and skip-segment providers
  - snapshot stores, disk caches, and in-memory caches that affect metadata behavior
- Included provider families:
  - TMDB
  - TVDB
  - Kitsu
  - MDBList
  - OMDb
  - custom IMDb ratings
  - RPDB
  - Top-Posters
  - Trakt
  - Simkl
  - TheIntroDB
  - AniSkip
  - AnimeSkip
  - ARM id-bridge routes
- Excluded:
  - stream resolution
  - subtitle metadata
  - a future desired architecture

## Terminology

- External addon row:
  - A `CatalogRow` built from a remote Stremio addon through `CatalogRepositoryImpl`. The addon owns the row shape and the app largely preserves the incoming `meta.id`.
- Built-in synthetic row:
  - A `CatalogRow` assembled in-app from a provider snapshot such as Trakt, Simkl, MDBList, Kitsu, or TMDB. The app owns both the row metadata and the chosen canonical `MetaPreview.id`.
- Base meta:
  - The `Meta` object returned by `MetaRepositoryImpl`, usually from an addon `meta/<type>/<id>.json` route.
- Enrichment:
  - Provider overlays applied after base meta load, mainly through `TvMetadataRouter`, `TmdbMetadataService`, `TitleRatingOverrideRepository`, `EpisodeRatingsSelectionRepository`, `TrailerService`, and `SkipIntroRepository`.
- `contentId`:
  - The title-level identifier carried by rows, progress, and detail navigation. In practice this may be IMDb, TMDB, TVDB, Kitsu, Trakt, a raw addon slug, or another provider-specific id.
- `fallbackContentId`:
  - A second id passed into TV/anime routing when the primary `contentId` is not enough. On detail screens this is usually the incoming navigation `itemId`.
- `videoId`:
  - A more episode-specific identity used heavily by continue watching and next-up entries. It is not interchangeable with `contentId`.
- Provider token:
  - The cache discriminator used by poster-aware and metadata-aware stores, usually `native` or `<provider>:<apiKeyHash>`.
- `posterProviderTag`:
  - The provider marker persisted inside `Meta`, `MetaPreview`, `HomeDisplayMetadata`, and home snapshots when RPDB or Top-Posters rewrites are active.

## Provider Decision Ladders

### Base Meta Lookup

1. External addon and preferred-addon detail fetches go through `MetaRepositoryImpl.getMeta(addonBaseUrl, type, id, ...)`.
2. `MetaRepositoryImpl` first checks an in-memory cache keyed by `primaryType:primaryId:providerToken`.
3. If disk caching is enabled, it reads `MetadataDiskCacheStore` using multiple alias keys built from:
   - the original `type:id`
   - normalized type candidates such as `series` and `tv`
   - id candidates derived from raw ids, stripped `imdb:` / `tmdb:` / `trakt:` prefixes, and synthesized alias forms
4. If cache misses, it tries addon `meta/<type>/<id>.json` routes in candidate-type and candidate-id order.
5. The first successful response wins and is written back to disk under one or more alias keys built by `buildMetaDiskAliasKeys`.
6. Built-in discovery services that need richer previews, especially `TraktDiscoveryService` and `SimklDiscoveryService`, call `MetaRepositoryImpl.getMetaFromAllAddons`. That path searches installed addons exposing the `meta` resource and returns the first matching addon response.

Operational consequence:

- One logical title can have multiple valid cache keys at the base-meta layer.
- The app does not enforce one globally canonical id before meta caching.

### TV And Anime Title Enrichment

1. Detail enrichment and some home overlays call `TvMetadataRouter.fetchEnrichment` with `contentId`, `fallbackContentId`, `contentType`, and language.
2. The router itself tries Kitsu first through `firstAnimeId(request)`, which checks `contentId` first and `fallbackContentId` second.
3. Kitsu accepts direct `kitsu:{id}` ids and resolves `mal:`, `anilist:`, `anidb:`, `tmdb:`, `tvdb:`, and IMDb ids through the bundled anime id map in `AnimeIdMappingService`.
4. If Kitsu succeeds, the router returns `TvProvider.KITSU` and the TMDB/TVDB title path is skipped.
5. `MetaDetailsViewModel.enrichMeta` performs a separate pre-router anime classification step.
   - It parses both `meta.id` and the navigation `itemId`.
   - It computes `hasAnimeId` with `animeId.source != IMDB || tmdbContentType != MOVIE`.
   - This suppresses Kitsu routing for IMDb-only anime movies before `TvMetadataRouter` sees the request.
6. If the request is not TV and not anime-routed, the router goes straight to TMDB enrichment.
6. For TV content:
   - if TVDB is inactive, TMDB is used
   - if TVDB credentials are unhealthy, cached TVDB data is attempted first, otherwise TMDB fallback is used
   - if TVDB identity cannot be resolved, TMDB fallback is used
   - if TVDB identity resolves and TVDB returns a record, TVDB is the primary provider
7. Detail screens can still supplement a successful TVDB result with TMDB when `MetaDetailsViewModel.shouldSupplementTvdbDetailWithTmdb(...)` decides TVDB lacks enough credits, production, network, or artwork surface.

Current provider order by media category:

| Category | Primary enrichment path | Fallback path |
|---|---|---|
| Movie | TMDB | none beyond base meta |
| TV | TVDB | TMDB |
| Anime | Kitsu when the ViewModel allows anime routing and the router can resolve a Kitsu id | TVDB or TMDB through the normal TV/movie path when Kitsu is not attempted or does not resolve |

### Episode Enrichment And Season Metadata

1. `TvMetadataRouter.fetchEpisodeEnrichment` uses the same Kitsu-first rule for anime ids.
2. For TV content:
   - TVDB is used when active, healthy, and identity-resolvable
   - TMDB season and episode data is used as fallback
3. `TvMetadataRouter.fetchSeasonEpisodes` follows the same provider choice for the season tab and episode list path.
4. TVDB episode enrichment is language-sensitive and can request per-episode translated overviews.
5. Kitsu episode enrichment is paginated through `anime/{id}/episodes` and is not durably cached in `MetadataDiskCacheStore`.
6. `MetaDetailsViewModel` only applies Kitsu episode enrichment into UI state. It does not write Kitsu-native episode results back into disk metadata stores.
7. Fallback anime episode paths cache differently:
   - Kitsu-native episode results are request-time only
   - TVDB fallback episodes use the TVDB episode disk cache (`tvdb_episode::$seriesId::*`, 24 h TTL)
   - TMDB fallback episodes reuse TMDB season-memory caches
8. TMDB episode enrichment is season-based and reused for both episode metadata and the non-custom episode-ratings path.

### Kitsu Persistence Summary

Kitsu is uniformly request-time only across all three surfaces. TVDB/TMDB paths persist; Kitsu does not. This is the practical reason anime cold-start feels slower than TV.

| Kitsu surface | Source | Durable cache? | Eviction | Evidence |
|---|---|---|---|---|
| Title enrichment | `GET /anime/{id}` via `KitsuMetadataService.fetchEnrichment` | No | n/a | `core/anime/KitsuMetadataService.kt:39-60+` returns `TvMetadataEnrichment` directly to the caller; no `MetadataDiskCacheStore` write |
| Episode list | `GET /anime/{id}/episodes` via `KitsuMetadataService.fetchEpisodeEnrichment` / `fetchSeasonEpisodes` | No | n/a | `core/anime/KitsuMetadataService.kt:72-122`; no `kitsu_episode*` key family in `MetadataDiskCacheStore` |
| Advanced detail (characters, staff, productions, related) | `GET /castings`, `/anime/{id}/anime-staff`, `/anime/{id}/anime-productions`, `/anime/{id}/media-relationships` | No (process-memory map only) | process death | `core/anime/KitsuMetadataService.kt:133-155` — `ConcurrentHashMap` in the service instance |
| Kitsu discovery rows | `GET trending/anime`, `GET anime` | Partial — home snapshot keeps the row shape (`HomeCatalogSnapshotStore`), but underlying Kitsu discovery state is in-memory only | `HomeCatalogSnapshotStore` invalidation | see §Modern Home Flow |

### Title Ratings And Episode Ratings

Title ratings:

1. Base title ratings start in the underlying `Meta` or `MetaPreview`.
2. `TitleRatingOverrideRepository` tries custom IMDb title ratings first.
3. If custom IMDb is unavailable, it asks `MDBListRepository` for title ratings.
4. Only the IMDb field is overridden there; broader MDBList fields are surfaced separately in detail state as `mdbListRatings`.

Episode ratings:

1. `EpisodeRatingsSelectionRepository` prefers custom IMDb episode ratings when the custom IMDb provider is configured.
2. Otherwise it resolves a TMDB id and fetches TMDB episode enrichment for `voteAverage`.
3. It also asks `OmdbEpisodeRatingsRepository` for season-level OMDb episode ratings.
4. `resolveEpisodeRatings(...)` merges TMDB and OMDb results into the final detail-screen episode badge map.
5. `MDBListRepository` also implements per-episode ratings and caches them, but `EpisodeRatingsSelectionRepository` does not call it. MDBList episode ratings are implemented code, not active selection input.

Current rating-provider order:

| Surface | Priority |
|---|---|
| Title IMDb badge | custom IMDb -> MDBList IMDb -> base meta/provider rating |
| Detail MDBList badges | MDBList only, separate from base IMDb badge |
| Episode ratings | custom IMDb -> TMDB + OMDb merge |

### Posters

1. `PosterRatingsUrlResolver` is the only component that rewrites poster urls to RPDB or Top-Posters.
2. It runs during external addon catalog fetch, built-in preview mapping, and base meta fetch in `MetaRepositoryImpl`.
3. RPDB only supports ids the resolver can express as IMDb, TMDB, or TVDB.
4. Top-Posters supports a broader set: IMDb, TMDB, TVDB, Trakt, MAL, Kitsu, AniList, and AniDB.
5. Top-Posters can append `fallback_url=<originalPosterUrl>` to preserve a native fallback path.
6. Detail poster finalization is weaker than row/base-meta poster finalization.
   - `TmdbEnrichment`, `TvMetadataEnrichment`, and Kitsu title enrichment models all carry `poster` fields.
   - `MetaDetailsViewModel.enrichMeta` does not reapply `PosterRatingsUrlResolver` after enrichment.
   - `MetaDetailsViewModel.enrichMeta` also does not currently copy enrichment posters into the final `Meta`; it updates backdrop and logo, but not poster.
7. Practical consequence:
   - the final detail poster is effectively the base meta poster
   - if base meta rewrite failed, the detail screen keeps the native poster
   - if base meta poster is null, enrichment poster does not rescue the detail poster today
8. RPDB on Kitsu ids is a concrete edge case:
   - `parseContentId` recognizes `kitsu:{id}`
   - `buildRpdbPosterUrl` rejects Kitsu ids
   - the original poster url is retained while `posterProviderTag` is still set to `rpdb`
9. Cache validity depends on the active provider token and `posterProviderTag`, but existing native/TMDB/TVDB urls can still remain in older snapshots or caches until those caches are invalidated and rewritten.

### Trailer, Teaser, And Recap Media

Title-level trailer availability in `TrailerService`:

| Category | Order | Evidence |
|---|---|---|
| TV | TVDB trailer resolver → Streailer internal trailers → fallback YouTube ids already carried on the item → TMDB TV videos | `data/trailer/TrailerService.kt:472-570` — `resolveTvTrailerInternal`, numbered steps 1-4 |
| Movie | TMDB movie videos → fallback YouTube ids → Streailer internal trailers | `data/trailer/TrailerService.kt:485-510` — explicit inline comment "Movie ordering unchanged: TMDB -> fallback YT IDs -> Streailer" |

Movie recap handling (four distinct layers — do not conflate):

| Layer | Movie recap behavior | Evidence |
|---|---|---|
| Provider availability | TMDB `GET movie/{id}/videos` can return `type=recap` items | `data/remote/api/TmdbApi.kt` (movie videos route) |
| Cache filter | Recap items pass `isCacheableTmdbTrailerType` alongside trailer/teaser, so cached payload retains them | `data/trailer/TmdbVideoFiltering.kt:15-20` — `when (type) { "trailer", "teaser", "recap" -> true; else -> false }` |
| Playback ranking | `rankTmdbVideoCandidates` filters to `trailer` and `teaser` only; recap is never ranked as a playable movie trailer candidate | `data/trailer/TrailerService.kt:1116-1135` — line 1123-1126 |
| UI consumption | Movie title UI never surfaces recap. Season recap UI consumes TMDB **season** videos + Streailer recap candidates through `orderedSeasonRecapCandidates` | `data/trailer/TrailerService.kt:705` and `ui/screens/detail/SeasonMediaSupport.kt:87` |

Season-level media:

- Season trailer resolution uses TVDB only indirectly through title metadata; the active playback sources are TMDB season videos and Streailer streams.
- Season recap resolution uses TMDB season videos and Streailer recap candidates. `rankTmdbRecapCandidates` filters to `type=recap` only.
- There is no Kitsu-native trailer path. Anime trailers fall through the TVDB/TMDB pipeline via the normal TV trailer order.

Caching:

- TMDB title videos and season videos are persisted in `MetadataDiskCacheStore` — 12 h TTL on the videos cache.
- `TrailerService` lookup cache (`ConcurrentHashMap<String, CachedTrailerLookup>`, line 96) is process-memory with **no TTL**; keyed by title+year+tmdbId+type+season+contentId+fallbackYtIds+language+apiKey.
- `TrailerService` resolved YouTube playback cache (`ConcurrentHashMap<String, CachedTrailerPlaybackSource>`, line 97) is process-memory with **3 h TTL** (`Duration.ofHours(3)`, line 43). Validated at line 1070 (`getValidCachedYoutubeSource`).
- TVDB trailer series records are only process-memory de-duplicated in `TvdbTrailerResolver`.

### Intro, Recap, Credits, And Preview Skip Intervals

1. Playback route choice is decided by `PlayerSkipProviderPolicy` via `SkipProviderArbiter`.
2. Anime-primary routes are selected when the effective id looks like `mal:` or `kitsu:`, the content type is `anime`, or the effective/fallback ids contain `:anime:`.
3. Non-anime routes are handled by TheIntroDB through `SkipIntroRepository.getSkipIntervals(...)`.
4. Anime routes try:
   - AniSkip when MAL is available
   - AnimeSkip GraphQL when AniList can be resolved directly or bridged through ARM
   - ARM translation routes to move between IMDb, MAL, Kitsu, and AniList when necessary
5. Skip intervals are cached in memory only. There is no durable skip-interval cache.

### People, Actors, Companies, And Networks

1. Title-level credits on detail screens come from:
   - TMDB for movies and for TV when TMDB supplementation is enabled
   - TVDB cast members for TV when TVDB is primary and no TMDB supplement is needed
   - Kitsu advanced characters and staff for Kitsu-backed anime
2. Person detail pages are fetched by provider-specific ids already carried on the cast members:
   - `TmdbMetadataService.fetchPersonDetail` for TMDB ids
   - `TvdbPersonService.fetchPersonDetail` for TVDB people ids
3. Company and network detail pages are TMDB-only through `TmdbOrganizationService`.
4. Title-level company and network data can originate from TMDB, TVDB, Kitsu, or base meta.
5. Deep organization detail is a separate identity problem:
   - Kitsu and TVDB company/network chips do not automatically carry deep-detail ids the UI can always open
   - Kitsu entries rely on exact-name bridging into TMDB ids (`TmdbMetadataService.findCompanyIdByExactName` / `findPersonIdByExactName`)
   - network descriptions are dropped even on the TMDB network-detail path because the current `TmdbOrganizationDetail` model does not map one
   - company and network title lists are TMDB discover-only
6. Kitsu anime credits and companies can be bridged into TMDB ids later through exact-name lookup in `MetaDetailsViewModel.hydrateKitsuNavigationTargetsAsync` (lines 1635-1703), but the source data remains Kitsu.

Organization detail caching (verified):

- `TmdbOrganizationService.fetchOrganizationDetail` (`core/tmdb/TmdbOrganizationService.kt:51-123`) has **no cache layer at all** — no `MetadataDiskCacheStore` writes, no in-class `ConcurrentHashMap`.
- `OrganizationDetailViewModel` (`ui/screens/organizationdetail/OrganizationDetailViewModel.kt:19`) holds the result only as transient ViewModel state for the duration of the detail screen. Navigating away and back re-issues all three TMDB calls (`company/{id}` + `discover/movie?with_companies=` + `discover/tv?with_companies=` for companies; `network/{id}` + `discover/tv?with_networks=` for networks).
- The UI entry point is a clickable production / network company chip on the detail screen (`ui/screens/detail/MetaDetailsScreen.kt:2054-2109`), reachable through `Screen.OrganizationDetail` at `NexioNavHost.kt:1170-1184`.

## Identity Carriers By Surface

| Surface | Builder | Primary identity carried forward | Secondary or fallback identity | UI key shape | Persisted store |
|---|---|---|---|---|---|
| External addon catalog row | `CatalogRepositoryImpl.getCatalog` | `MetaPreview.id` from addon `meta.id` | none at row build time | `catalog_${row.key()}_${item.id}_${occurrence}` | `CatalogDiskCacheStore`, `HomeCatalogSnapshotStore` |
| Built-in TMDB row | `TmdbDiscoveryService.fetchCatalogRow` | raw `tt...` IMDb id when TMDB->IMDb resolves, otherwise `tmdb:{id}` | none at row build time | `catalog_${row.key()}_${item.id}_${occurrence}` | in-memory `TmdbDiscoverySnapshot`, `HomeCatalogSnapshotStore` |
| Built-in Kitsu row | `KitsuDiscoveryService.fetchCatalogRow` | `kitsu:{id}` | none at row build time | `catalog_${row.key()}_${item.id}_${occurrence}` | in-memory `KitsuDiscoverySnapshot`, `HomeCatalogSnapshotStore` |
| Built-in Trakt row | synthetic row builders in `HomeViewModelCatalogPipeline.kt` | `normalizeContentId(ids, fallback)` -> raw IMDb -> `tmdb:{id}` -> `trakt:{id}` -> fallback slug | Trakt list, slug, and recommendation refs live alongside the preview | `catalog_${row.key()}_${item.id}_${occurrence}` | `TraktDiscoverySnapshotStore`, `HomeCatalogSnapshotStore` |
| Built-in Simkl row | synthetic row builders in `HomeViewModelCatalogPipeline.kt` | canonicalized `MetaPreview.id`, usually IMDb or `tmdb:` after SIMKL id normalization | SIMKL external id cache keeps secondary mapping state | `catalog_${row.key()}_${item.id}_${occurrence}` | `SimklDiscoverySnapshotStore`, `HomeCatalogSnapshotStore` |
| Built-in MDBList row | synthetic row builders in `HomeViewModelCatalogPipeline.kt` | IMDb first, then `tmdb:{id}`, then raw item id/slug/list fallback | list-owner and list-id stay in MDBList snapshot models | `catalog_${row.key()}_${item.id}_${occurrence}` | `MDBListDiscoverySnapshotStore`, `HomeCatalogSnapshotStore` |
| Continue watching in-progress | `ContinueWatchingSnapshotService` + `HomeViewModelContinueWatching` | `progress.contentId` | `progress.videoId`, season, episode | `cw_inprogress_${contentId}_${videoId}_${season}_${episode}` | `ContinueWatchingSnapshotStore` |
| Continue watching next-up | `ContinueWatchingSnapshotService` + `HomeViewModelContinueWatching` | `info.contentId` | `info.videoId`, `traktShowId`, `traktEpisodeId`, season, episode | `cw_nextup_${contentId}_${videoId}_${season}_${episode}` | `ContinueWatchingSnapshotStore` |

Identity-layer distinction:

- Provider snapshot model ids are not the same thing as `MetaPreview.id`.
- `MetaPreview.id` is what home rows and detail navigation usually carry forward as `itemId`.
- `MetadataDiskCacheStore` then adds separate alias keys around those ids for base meta lookup.
- Continue watching introduces `videoId` and season/episode coordinates as an additional identity layer beyond title-level `itemId`.

### Named External Addon Inputs

The three manifests named in the research request were fetched live on April 22, 2026.

| Addon URL | Manifest id | Manifest name | Declared types | Declared catalogs | How Nexio treats the returned row |
|---|---|---|---|---|---|
| `...stremio-netflix-catalog-addon.../manifest.json` | `pw.ers.netflix-catalog` | `Streaming Catalogs` | `movie`, `series` | `nfx` movie and `nfx` series | external addon row through `CatalogRepositoryImpl`; Nexio does not remap it into a built-in synthetic row |
| `https://anime-kitsu.strem.fun/manifest.json` | `community.anime.kitsu` | `Anime Kitsu` | `anime`, `movie`, `series` | `kitsu-anime-trending`, `kitsu-anime-airing`, `kitsu-anime-popular`, `kitsu-anime-rating`, `kitsu-anime-list` | external addon row through `CatalogRepositoryImpl`; Kitsu is not treated as the in-app built-in synthetic row when sourced from this addon |
| `...stremio-anime-catalogs.../manifest.json` | `org.stremio.animecatalogs` | `Anime Catalogs` | `anime`, `movie`, `series` | `myanimelist_top-all-time`, `anidb_popular`, `anilist_trending-now`, `kitsu_top-airing`, `livechart_popular` | external addon row through `CatalogRepositoryImpl`; Nexio preserves the addon’s row identity and does not convert it into built-in Kitsu/TMDB rows |

Important distinction:

- A row sourced from an external addon can still carry a Kitsu, IMDb, TMDB, or TVDB id, but it remains an external addon row structurally.
- Built-in Kitsu and TMDB rows are separate in-app discovery systems with their own snapshot stores and their own canonical id rules.
- A Kitsu id does not mean the row came from the built-in Kitsu discovery service.
- A built-in Kitsu row and an external addon row carrying `kitsu:{id}` have different provenance, cache paths, and `addonBaseUrl` behavior.

## Modern Home Flow

1. `HomeViewModelCatalogPipeline` tries to restore a profile-scoped `HomeCatalogSnapshotStore.Snapshot`.
   - inbound identity: stored `CatalogRow` and `MetaPreview.id` values
   - validation gates: profile id, language tag, schema version, and poster provider token
   - scope: profile-derived cache
2. Built-in discovery snapshots are observed in parallel.
   - Trakt from `TraktDiscoverySnapshotStore`
   - Simkl from `SimklDiscoverySnapshotStore`
   - MDBList from `MDBListDiscoverySnapshotStore`
   - Kitsu from in-memory `KitsuDiscoveryService`
   - TMDB from in-memory `TmdbDiscoveryService`
3. External addon catalogs are fetched through `CatalogRepositoryImpl`.
   - request path: addon `catalog/<type>/<catalogId>.json` with optional encoded extras and `skip`
   - outbound identity: addon-provided `meta.id` becomes `MetaPreview.id`
   - poster rewrite may already change the stored poster url at this stage
4. Synthetic built-in rows are assembled in `HomeViewModelCatalogPipeline`.
   - Trakt, Simkl, and MDBList rows are not fetched through `CatalogRepositoryImpl`
   - they are converted from provider snapshot models into `CatalogRow` objects
5. Presentation is built in `ModernHomeModels.kt`.
   - catalog cards become `ModernPayload.Catalog(itemId = item.id, itemType = item.apiType, addonBaseUrl = row.addonBaseUrl, ...)`
   - catalog focus and trailer resolution later use these fields, not the raw row object
6. The merged visible rows and hero items are written back to `HomeCatalogSnapshotStore`.
7. `MetadataDiskCacheStore.replaceHomeFeedReferences(...)` is updated with item keys for shared metadata reuse, but the actual home rows remain in `HomeCatalogSnapshotStore`, not `MetadataDiskCacheStore`.

Home identity consequences:

- A catalog card on modern home is only as canonical as the `MetaPreview.id` selected by its source row builder.
- Built-in Trakt and Simkl rows often canonicalize ids by consulting addon meta routes, while external addon rows preserve the addon id verbatim.
- A `kitsu:{id}` row from `anime-kitsu.strem.fun` still follows the external-addon fetch and cache path, not the built-in Kitsu discovery path.

## Continue Watching Flow

1. `ContinueWatchingSnapshotService` combines:
   - local `WatchProgress`
   - provider next-up entries from tracking services
   - a `displayMetadataByItemKey` overlay map keyed by `homeDisplayItemKey(contentType, contentId)`
2. The persisted source of truth is `ContinueWatchingSnapshotStore`.
   - scope: profile-derived cache
   - validation: schema version and language tag
3. `HomeViewModelContinueWatching` converts snapshot rows into `ContinueWatchingItem.InProgress` and `ContinueWatchingItem.NextUp`.
4. Modern home presentation then converts those into `ModernPayload.ContinueWatching`.
   - primary title identity is still `contentId`
   - episode-level behavior additionally depends on `videoId`, season, and episode
5. Continue-watching enrichment can fetch preferred addon meta and localized episode descriptions.
   - preferred addon meta comes from `MetaRepository.getMeta(addonBaseUrl, type, id, cacheOnDisk = true, origin = "continue_watching_provider")`
   - localized episode descriptions go through `TvMetadataRouter.fetchEpisodeEnrichment`
6. The continue-watching row therefore carries a stronger identity bundle than normal catalog rows:
   - `contentId`
   - `videoId`
   - season and episode
   - optional provider fallback content id
   - provider-auth-specific next-up ids such as Trakt ids

Continue-watching identity consequences:

- Continue watching is not just another catalog row.
- It carries both title identity and episode identity, and it can legitimately diverge from the title id used by adjacent catalog rows.

## Detail View Flow

1. `MetaDetailsViewModel.loadMeta()` resolves base meta first.
   - source: preferred addon or addon-wide meta search through `MetaRepositoryImpl`
   - output: `Meta`
2. `MetaDetailsViewModel.enrichMeta(meta)` computes provider overlays.
   - TV/anime route: `TvMetadataRouter.fetchEnrichment`
   - non-TV route: `TmdbMetadataService.fetchEnrichment`
3. Title-level groups are applied in order:
   - artwork
   - basic info
   - runtime and release details
   - credits
   - productions
   - networks
   - season-order context
   - episode metadata
4. `TitleRatingOverrideRepository.enrichMeta(...)` runs after the core title overlay.
5. Secondary detail loads then start:
   - more like this via `TmdbMetadataService.fetchMoreLikeThis`
   - reviews via TMDB plus Trakt comments
   - collection rows for movies via `TmdbMetadataService.fetchMovieCollection`
   - episode ratings via `EpisodeRatingsSelectionRepository`
   - MDBList summary ratings via `MDBListRepository.getRatingsForMeta`
6. Person detail pages are provider-specific:
   - TMDB via `TmdbMetadataService.fetchPersonDetail`
   - TVDB via `TvdbPersonService.fetchPersonDetail`
7. Company and network detail pages are TMDB-only via `TmdbOrganizationService.fetchOrganizationDetail`.

Detail identity consequences:

- The base meta id may not match the id used by TVDB identity resolution, TMDB id resolution, Trakt review lookup, trailer lookup, or skip-intro lookup.
- Detail behavior is therefore alias-rich even when the UI looks like it is showing one single title id.
- `meta.id` and navigation `itemId` can disagree.
- When they disagree, the router prefers `contentId = meta.id` and only uses `fallbackContentId = itemId` as a secondary candidate.
- If the ViewModel suppresses anime routing before the router sees the request, `itemId` cannot rescue Kitsu selection.

## Ratings, Posters, Trailers, And Skip Segments

- Title IMDb display can come from:
  - base meta
  - TMDB enrichment
  - TVDB enrichment
  - custom IMDb title ratings
  - MDBList IMDb ratings
- Episode ratings can come from:
  - custom IMDb episode ratings
  - TMDB episode `voteAverage`
  - OMDb season episode ratings
- Posters can come from:
  - addon-native or TMDB/TVDB/Kitsu-provided art
  - RPDB rewrite
  - Top-Posters rewrite
- Trailer and recap availability can come from:
  - TVDB title trailer records
  - TMDB title or season video endpoints
  - Streailer stream metadata
  - fallback YouTube ids already embedded in the content preview
- Skip segments can come from:
  - TheIntroDB
  - AniSkip
  - AnimeSkip GraphQL
  - ARM id translation routes that bridge IMDb, MAL, Kitsu, and AniList

## Cache And Storage Summary

The full store-by-store matrix lives in the appendix. The short version:

- Home rows are persisted per profile in `HomeCatalogSnapshotStore`, but shared text metadata lives in `MetadataDiskCacheStore`.
- Continue watching is persisted per profile in `ContinueWatchingSnapshotStore` and carries its own `displayMetadataByItemKey` overlay map.
- TVDB, TMDB, and trailer video enrichments use `MetadataDiskCacheStore` with provider-token and language-sensitive keys.
- Ratings and skip-segment providers mostly use process-memory caches, not durable stores.
- Poster provider rewrites are reflected in cached payloads through `posterProviderTag`, which means cache validity depends on the active poster provider.

## Known Ambiguities And Drift

- Anime is not guaranteed to stay on Kitsu.
  - If `AnimeStremioId.parse` fails or the bundled anime id map does not resolve a non-Kitsu anime id, the request falls back to the normal TMDB or TVDB route.
- Anime movie IMDb-only routing has a concrete failure mode.
  - `AnimeStremioId.parse("tt...")` returns `AnimeIdSource.IMDB`.
  - `MetaDetailsViewModel.enrichMeta` excludes IMDb-only anime ids when `tmdbContentType == MOVIE`.
  - Result: an anime movie with only an IMDb id bypasses Kitsu entirely even though the router could have attempted Kitsu if called.
- Anime classification is duplicated.
  - `MetaDetailsViewModel.enrichMeta` decides whether the router is called.
  - `TvMetadataRouter.firstAnimeId` decides whether Kitsu is attempted once the router is called.
  - This split means Kitsu eligibility can be suppressed before the router sees the request.
- Poster variants can coexist.
  - RPDB and Top-Posters rewrites are provider-token-aware, but existing native poster urls can still survive in older snapshots or stale caches until those stores are refreshed.
- Detail poster finalization is weaker than row poster finalization.
  - base meta fetch applies poster-provider rewrites
  - detail enrichment does not reapply the resolver
  - detail enrichment does not currently copy provider poster overlays into final `Meta`
  - Kitsu+RPDB can therefore surface native posters while still carrying `posterProviderTag = rpdb`
- Base meta lookup is alias-heavy.
  - `MetaRepositoryImpl` writes and reads multiple `type:id` aliases, so the same title can be reachable through several cache keys.
- Continue watching and catalog rows do not carry the same identity shape.
  - Continue watching uses `contentId` plus `videoId` and episode coordinates; catalog rows only preserve `MetaPreview.id`.
- Company and network detail depth is provider-skewed.
  - Title-level companies and networks may originate from TVDB or Kitsu, but full organization detail pages are TMDB-only today.
- Kitsu advanced detail is partial.
  - Kitsu related titles, staff, productions, and character data exist, but ratings, reviews, and durable Kitsu metadata caching are not parallel to TMDB/TVDB.
- Kitsu caching is request-time only.
  - Kitsu title enrichment is not durably cached.
  - Kitsu episode metadata is not durably cached.
  - Kitsu advanced detail is not durably cached.
  - fallback TVDB/TMDB anime paths cache differently from Kitsu-native paths.
- TVDB updates are active, but not universal.
  - `NexioApplication.onCreate` triggers a startup catch-up and schedules periodic WorkManager refreshes (12 h interval, `NetworkType.CONNECTED`, no feature flag).
  - The invalidator removes TVDB series (`tvdb::$seriesId::*`), episode (`tvdb_episode::$seriesId::*`), and reference payload caches (`tvdb_ref::$type::*`) and records merge aliases through `TvdbMergeAliasStore`.
  - It does **not** invalidate `TvdbIdentityCacheStore`.
- `TvdbIdentityCacheStore` has no TTL and is only invalidated by schema-version change.
  - Backed by SharedPreferences (`tvdb_identity_cache_v1`) with `CACHE_SCHEMA_VERSION = 2` (`data/local/TvdbIdentityCacheStore.kt:17-25`).
  - `updatedAtMs` is written on every put (line 48) but never read for staleness; there is no time-based expiry path.
  - Consequence: a TVDB series that was merged or had its remote-id mapping changed upstream can keep serving stale identity from the app's local cache indefinitely until the schema version is bumped or the app data is cleared.
- Merge-alias behavior is separate from identity-cache invalidation.
  - `TvdbUpdateProcessor` records merge aliases in `TvdbMergeAliasStore` when it sees `method=merge` update events; this lets later lookups on an old TVDB id redirect to the surviving id.
  - This alias bookkeeping is distinct from, and does not touch, `TvdbIdentityCacheStore` entries.
- Ratings are intentionally non-canonical.
  - The app keeps a base rating, an overridden title IMDb rating, separate MDBList multi-provider ratings, and separate episode-rating sources.

## Appendix Links

- [Provider endpoint index](../research/metadata-audit/provider-endpoint-index.md)
- [Field source matrix](../research/metadata-audit/field-source-matrix.md)
