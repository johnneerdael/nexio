# Nexio Metadata Provider System — End-to-End Audit

**Date:** 2026-04-22
**Status:** Current authoritative merged source of truth after the final reconciliation pass.
**Scope:** Current-state reference document — no recommendations, no prescriptions.
**Sibling documents (read these alongside):**
- `plans/2026-04-21-003-api-network-cache-analysis.md` — HTTP/OkHttp layer, request gates, connection pool, 50 MB `http_cache`.
- `plans/2026-04-21-003-api-network-cache-analysis-preanalysis.md` — the `IntegrationHub` re-architecture sketch.
- `plans/2026-04-21-kitsu-get-endpoint-index.md` — Kitsu public GET surface.
- `plans/2026-04-21-004-api-integration-runtime-rearchitecture-plan.md` — the re-architecture plan this document feeds into.

> **Purpose.** Groundwork for the integration-runtime re-architecture. For every media category (Movies, TV, Anime) and every user-visible metadata field, this document records **where the data comes from, which API endpoint delivers it, how it is stored, how identity propagates from a catalog row to a detail fetch, and which ambiguity / multi-storage pitfalls exist today**. Assume the reader will change the architecture based on this document — claims are cited with `path/to/file.kt:line` so they can be re-verified before acting on them.

---

## 0. Executive summary (read this first)

1. **Identity is not normalized anywhere on the Modern Home.** A `MetaPreview.id` emitted by a catalog row can be any of: `tt1234567` (IMDB), `tmdb:12345`, `kitsu:12345`, `simkl:12345`, an addon-internal slug, or a raw numeric string. The detail view receives exactly three args (`itemId`, `itemType`, optional `addonBaseUrl`) and is expected to figure out the rest. See §2.3.
2. **Primary-provider routing for TV/anime is centralized in `TvMetadataRouter`** (`app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt:35-101`), but the **anime detection decision is duplicated** in `MetaDetailsViewModel.enrichMeta()` (`app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1379-1398`). Two decision trees with slightly different inputs means anime can be classified differently between the router and the ViewModel.
3. **Anime-detection is gated by a static asset.** `core/anime/AnimeIdMappingService.kt:9-45` loads `anime-id-map.json` at boot; an IMDB or TMDB anime that is not in that asset will not be routed to Kitsu regardless of content. This is the direct root cause of "anime not always strictly gathered from Kitsu."
4. **Poster routing for home rows applies RPDB/Top-Posters resolver twice** (before and after provider enrichment) and TMDB explicitly skips poster emission when a premium provider is active (`core/tmdb/TmdbMetadataService.kt:197-201`). The user-reported "TMDB poster wins despite RPDB configured" has several plausible leaks: (a) detail-screen path appears to not re-apply the resolver, (b) `buildRpdbPosterUrl()` silently falls back to the original URL for unsupported ID types (RPDB supports IMDB/TMDB/TVDB only), (c) stale disk-cache entries with `posterProviderTag="none"` that bypassed invalidation. See §5.3.
5. **Meta and MetaPreview store exactly one `poster: String?` field** (`domain/model/Meta.kt:5-40`, `domain/model/MetaPreview.kt:5-28`). There is no "candidate posters" list. A `posterProviderTag` marks which source supplied it, which is how cache invalidation works when the active poster provider changes, but there is no way to fall back to a different source at render time without a full re-fetch.
6. **Continue-watching is winner-take-all between Trakt and Simkl.** `TrackingProgressService.observeAllProgress()` (`data/repository/TrackingProgressService.kt:101-138`) reads from a single `effectiveProvider`; there is no merge. CW entries from Trakt carry **only IMDB/TMDB/TVDB/trakt/slug** — no Kitsu or MAL. Simkl CW carries MAL additionally. This forces every anime CW entry to bounce through the ARM service (`IMDB → AniList/MAL → Kitsu`) before Kitsu metadata can be fetched.
7. **Fanart.tv is wired into the build only as a blueprint** (`apiblueprints/fanarttv.json`). Grep returned zero hits for `fanart` in source code. It is listed in the user's poster-art providers but is not in production.
8. **MDBList ratings are fetched per-item, per-type via `POST /rating/{media_type}/{ratingType}`.** The endpoint supports batch IDs in one request (`ids: [...]`), but Nexio always sends one ID per call (`data/repository/MDBListRepository.kt:223`). Every rating type is a separate round trip (imdb, tomatoes, metacritic, letterboxd, trakt, tmdb, audience). Custom IMDb exists as a separate first-priority rating provider with its own batch endpoint; see §8.3.
9. **TMDB's `append_to_response` is used for `credits,images,release_dates` (movies) / `credits,images,content_ratings` (TV)** but not for `recommendations,reviews,videos,translations`, which are fetched as separate round trips when the detail view asks for them (`core/tmdb/TmdbMetadataService.kt:133-147`). Bulk-fetch opportunity documented in §9.
10. **Anime advanced data is not persisted.** `KitsuAdvancedAnimeDetail` (characters, voice actors, staff, production companies, related titles) lives only in an in-memory map on `KitsuMetadataService` (`core/anime/KitsuMetadataService.kt:133-155`). Rotate the app and it refetches.
11. **TMDB "More like this" and TMDB review payloads are in-memory-only** on `TmdbMetadataService` (`moreLikeThisCache`, `reviewsCache`). Final detail reviews also merge in Trakt comments when Trakt auth is present, but that merged review state is UI-memory only and never persisted to disk.

---

## 1. Source layout (where things live)

| Area | Package / path |
|---|---|
| Modern Home screen | `app/src/main/java/com/nexio/tv/ui/screens/home/` |
| Detail screen + ViewModel | `app/src/main/java/com/nexio/tv/ui/screens/detail/` |
| Primary metadata services | `core/tmdb/`, `core/tvdb/`, `core/anime/` |
| Router (the routing decision) | `core/tvdb/TvMetadataRouter.kt` |
| Identity mapping | `core/tvdb/TvdbIdentityService.kt`, `core/tvdb/TvdbIdentityCacheStore.kt`, `core/anime/AnimeIdMappingService.kt` (+ `assets/anime-id-map.json`), `core/anime/AnimeStremioId.kt`, ARM service (IMDB/Kitsu/AniList/MAL bridge) |
| Poster / image resolution | `core/poster/PosterRatingsUrlResolver.kt` |
| Metadata disk cache | `core/metadata/MetadataDiskCacheStore.kt` |
| Catalog + addon meta fetch | `data/repository/CatalogRepositoryImpl.kt`, `data/repository/MetaRepositoryImpl.kt` |
| Discovery services (built-in catalogs) | `data/repository/TraktDiscoveryService.kt`, `SimklDiscoveryService.kt`, `KitsuDiscoveryService.kt`, `TmdbDiscoveryService.kt`, `MDBListDiscoveryService.kt` |
| CW, scrobble, tracking | `data/repository/TrackingProgressService.kt`, `TraktProgressService.kt`, `SimklProgressService.kt`, `data/repository/ContinueWatchingSnapshotService.kt`, `data/repository/trakt/TraktScrobbleMutationAdapter.kt`, `data/repository/simkl/SimklScrobbleMutationAdapter.kt` |
| Intro/credits skip | `data/repository/SkipIntroRepository.kt`, `data/remote/api/SkipIntroApi.kt` |
| Ratings — MDBList | `data/repository/MDBListRepository.kt`, `data/repository/OmdbEpisodeRatingsRepository.kt` |
| Poster ratings settings | `PosterRatingsSettingsDataStore`, `PosterRatingsSettings` |
| Blueprints | `apiblueprints/*.apib`, `*.yml`, `*.json`, `*.yaml` (10 files) |

---

## 2. Modern Home → Detail: identity flow

### 2.1 Home-row data model

The Modern Home renders three kinds of rows (`ui/screens/home/ModernHomeModels.kt:55-97`):

```kotlin
sealed class ModernPayload {
    data class ContinueWatching(val item: ContinueWatchingItem) : ModernPayload()
    data class Catalog(
        val focusKey: String,       // "{row.key()}::{item.id}"
        val itemId: String,          // primary identity, format depends on source
        val itemType: String,        // "movie" / "series" (or addon-specific)
        val addonBaseUrl: String,    // producer-of-the-row URL
        val trailerTitle: String,
        val trailerReleaseInfo: String?,
        val trailerApiType: String,
        val fallbackTrailerYtId: String? = null
    ) : ModernPayload()
}
```

`ModernCarouselItem` wraps a `ModernPayload` plus a denormalized preview (title, subtitle, imageUrl, hero preview) plus an optional back-reference `MetaPreview` to the domain identity.

The backing domain type is `MetaPreview` (`domain/model/MetaPreview.kt:6-28`):

```kotlin
@Immutable
data class MetaPreview(
    val id: String,                  // primary identity — IMDB "tt...", "tmdb:n", "kitsu:n", "simkl:n", addon-slug
    val type: ContentType,           // MOVIE or SERIES
    val rawType: String,             // raw "movie" / "series" / addon-specific
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String? = null,
    val imdbRating: Float?,
    val ratingSource: TitleRatingSource? = TitleRatingSource.IMDB,
    val tomatoesRating: Double? = null,
    val genres: List<String>,
    val trailerYtIds: List<String> = emptyList(),
    val language: String? = null,
    val posterProviderTag: String? = null
)
```

**Note:** `MetaPreview.id` is not normalized. The same logical title can appear with three different `id`s depending on which row it came from.

### 2.2 Continue-watching identity is different from catalog-row identity

`ContinueWatchingItem` is a sealed type (`ui/screens/home/HomeUiState.kt:61-100`):

- `InProgress(progress: WatchProgress, displayMetadata: HomeDisplayMetadata?, ...)`
- `NextUp(info: NextUpInfo)`

`WatchProgress` (`domain/model/WatchProgress.kt:9-83`) is the one that carries resume state:

```kotlin
data class WatchProgress(
    val contentId: String,         // IMDB tt... (from addon or Trakt) or provider-prefixed
    val contentType: String,       // "movie" / "series"
    val name: String,
    val poster: String?, val backdrop: String?, val logo: String?,
    val videoId: String,           // episode/video ID being watched
    val season: Int?, val episode: Int?,
    val episodeTitle: String?,
    val position: Long, val duration: Long,  // ms; only valid when SOURCE_LOCAL
    val lastWatched: Long,
    val addonBaseUrl: String?,     // addon that served the original stream
    val progressPercent: Float?,   // 0..100, populated by Trakt/Simkl remote sources
    val source: String = SOURCE_LOCAL,  // LOCAL | TRAKT_PLAYBACK | TRAKT_HISTORY | TRAKT_SHOW_PROGRESS
    val traktPlaybackId: Long? = null,
    val traktMovieId: Int? = null,
    val traktShowId: Int? = null,
    val traktEpisodeId: Int? = null
)
```

Where catalog rows package identity in `ModernPayload.Catalog(itemId, itemType, addonBaseUrl)`, continue-watching items keep identity inside the wrapped `WatchProgress`/`NextUpInfo`. On click, `ModernHomeRows.kt:121-167` (CW) and `ModernHomeRows.kt:246-251` (catalog) both call `onNavigateToDetail(itemId, itemType, addonBaseUrl)` but extract the args from different places.

Note: the `source` field on `WatchProgress` is the only hint about whether position/duration are authoritative (local playback) vs. derived-from-percent (Trakt). See §7.1 for resume-position math.

### 2.3 Detail-view navigation contract

`ui/navigation/Screen.kt:13-31`:

```
detail/{itemId}/{itemType}?addonBaseUrl=...&detailSource=...&returnFocusSeason=...&returnFocusEpisode=...
```

`MetaDetailsViewModel` extracts the args from `SavedStateHandle` (`ui/screens/detail/MetaDetailsViewModel.kt:205-213`):

```kotlin
private val itemId: String = savedStateHandle["itemId"] ?: ""
private val itemType: String = savedStateHandle["itemType"] ?: ""
private val preferredAddonBaseUrl: String? = savedStateHandle["addonBaseUrl"]
private val detailSource: String = (savedStateHandle["detailSource"] as? String)?.trim()?.lowercase().orEmpty()
private val shouldCacheDetailMetaOnDisk: Boolean = detailSource !in setOf("library", "search")
private val detailMetaOrigin: String = if (shouldCacheDetailMetaOnDisk) "detail" else "detail_$detailSource"
```

`detailSource` drives whether the detail meta gets persisted on disk (search/library results are not persisted).

### 2.4 Built-in catalog sources — what identity they emit

Each discovery service produces `MetaPreview` objects; the `id` format differs:

| Service | File:line | `MetaPreview.id` format | Fallback on missing external id |
|---|---|---|---|
| **Trakt** (`TraktDiscoveryService`) | `data/repository/TraktDiscoveryService.kt:127-200+` | `normalizeContentId(ids, fallback)` → raw IMDB `tt...` → `tmdb:{n}` → `trakt:{n}` → slug fallback. | Item is kept unless every fallback is blank. |
| **Simkl** (`SimklDiscoveryService`) | `data/repository/SimklDiscoveryService.kt:42-97` | Preference: IMDB `tt...` → `tmdb:{n}` → `simkl:{n}` | `preferredSimklExternalContentId()` + `resolveSimklCanonicalContentId()` (lines 71-97). |
| **MDBList** (`MDBListDiscoveryService`) | `data/repository/MDBListDiscoveryService.kt:60-100+` | IMDB `tt...` → `tmdb:{n}` → raw `id` / `slug` / raw trakt id fallback from the list payload. | List entries are dropped only if every fallback field is blank. |
| **Kitsu** (`KitsuDiscoveryService`) | `data/repository/KitsuDiscoveryService.kt:43-149` | `kitsu:{id}` always. Hardcoded `language = "ja"`. | N/A. |
| **TMDB** (`TmdbDiscoveryService`) | `data/repository/TmdbDiscoveryService.kt:135-200+` | raw IMDB `tt...` if `TmdbService.tmdbToImdb()` resolves, else `tmdb:{n}`. | Item kept with `tmdb:{n}` id. |

Important distinction: a row carrying `kitsu:{id}` is not necessarily a built-in Kitsu row. A built-in Kitsu row (`KitsuDiscoveryService`) and an external addon row from `anime-kitsu.strem.fun` carrying `kitsu:{id}` have different provenance, cache paths, and `addonBaseUrl` behavior.

### 2.5 Addon-catalog identity (Stremio-compatible)

Addon catalog fetch goes through `CatalogRepositoryImpl.kt:36-142`, which calls the addon at `{addonBaseUrl}/catalog/{type}/{catalogId}.json`. The response DTO is passed through verbatim (`data/remote/dto/CatalogResponseDto.kt:6-27`, `data/mapper/CatalogMapper.kt:8-26`):

```kotlin
fun MetaPreviewDto.toDomain(): MetaPreview = MetaPreview(
    id = id,                           // passed through — no normalization
    type = ContentType.fromString(type),
    rawType = type,
    name = name, poster = poster, posterShape = PosterShape.fromString(posterShape),
    background = background, logo = logo, description = description,
    releaseInfo = releaseInfo, runtime = runtime,
    imdbRating = imdbRating?.toFloatOrNull(),
    genres = genres ?: emptyList(),
    trailerYtIds = trailerStreams?.mapNotNull { it.ytId?.takeIf { s -> s.isNotBlank() } } ?: emptyList(),
    language = language
)
```

The three addons the user flagged:

| Addon manifest | Typical `id` format | Type coverage |
|---|---|---|
| `anime-kitsu.strem.fun` | `kitsu:{n}` | series (anime) |
| `stremio-netflix-catalog-addon.baby-beamup.club/…/manifest.json` | IMDB `tt...` | movie, series |
| `stremio-anime-catalogs.baby-beamup.club/…/manifest.json` (livechart / kitsu / anilist / anidb / myanimelist) | Mixed: `kitsu:{n}`, `mal:{n}`, `anilist:{n}`, `anidb:{n}`, or IMDB depending on which sub-catalog | series (anime) |

Key implication: for the anime-catalogs addon, which source-id format arrives depends on which sub-catalog was selected in the addon manifest query string. None of this is normalized before hitting `MetaPreview.id`.

### 2.6 Identity-to-detail propagation diagram

```
┌────────────────────────── MODERN HOME ──────────────────────────┐
│                                                                 │
│  CW row (Trakt)       CW row (Simkl)      Catalog row (anime    │
│  WatchProgress         WatchProgress       addon) MetaPreview   │
│  contentId=tt…         contentId=tt… /     id=kitsu:123         │
│                        mal:…                                    │
│                                                                 │
│  [TraktDiscovery]      [SimklDiscovery]    [KitsuDiscovery]      │
│  MetaPreview.id=tt…    id=tt… / tmdb:n /   id=kitsu:n            │
│                        simkl:n                                  │
│                                                                 │
│  [TmdbDiscovery]       [MDBListDiscovery]  [AddonCatalog]        │
│  id=tt… / tmdb:n       id=tt… / tmdb:n     id=<addon-decided>    │
│                                                                 │
└─────────────────────────────────┬───────────────────────────────┘
                                  │
                                  ▼ onNavigateToDetail(itemId, itemType, addonBaseUrl)
┌──────────────────── MetaDetailsViewModel ───────────────────────┐
│   itemId, itemType, preferredAddonBaseUrl, detailSource         │
│                          │                                      │
│                          ▼                                      │
│   metaRepository.getMeta(...) or .getMetaFromAllAddons(...)     │
│                          │                                      │
│                          ▼  [see §3]                            │
│   enrichMeta(...)                                               │
│     ├─ AnimeStremioId.parse(meta.id) / parse(itemId)           │
│     ├─ TvMetadataRouter.fetchEnrichment(...)  (Kitsu / TVDB / TMDB) │
│     ├─ tmdbMetadataService.fetchEnrichment(tmdbId, MOVIE)       │
│     └─ MDBListRepository, PosterRatingsUrlResolver, ...         │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Primary-provider routing (TMDB / TVDB / Kitsu)

### 3.1 `TvMetadataRouter` — the decision

The router handles every TV and anime enrichment request (`core/tvdb/TvMetadataRouter.kt:35-101`):

```
fetchEnrichment(TvMetadataRequest):
  1. tryFetchKitsuEnrichment(request)                    ← 35-39
       └─ firstAnimeId(request.contentId, request.fallbackContentId)
            └─ AnimeStremioId.parse(…) [kitsu|mal|anilist|anidb|tmdb|tvdb|imdb]
            └─ AnimeIdMappingService.resolveKitsuId(animeId, mediaKind)
       └─ if Kitsu id resolved → KitsuMetadataService.fetchEnrichment → KITSU_SUCCESS

  2. if (!request.contentType.isTv())                    ← 40-41
       └─ fetchTmdbEnrichment(TVDB_INACTIVE)

  3. if (!tvdbSettingsDataStore.settings.first().isActive)   ← 44-49
       └─ fetchTmdbEnrichment(TVDB_INACTIVE)

  4. if (!credentialHealth.canCallTvdb())                ← 53-54
       └─ handleInvalidCredentialEnrichment(request)

  5. identity = resolveTvdbIdentity(contentId, contentType)   ← 57
       └─ TvdbIdentityService.resolveSeriesByRemoteId(remoteId, source)
            └─ normalizeTvdbRemoteIdValue(remoteId, source)
            └─ identityCacheStore.read(source, value)
            └─ POST /search/remote-id?remoteId={id}
            └─ POST /search?type=series (fallback)
            └─ GET /series/{id}/extended (build identity)
       └─ if null → fetchTmdbEnrichment(TVDB_IDENTITY_MISSING)

  6. enrichment = tvdbMetadataService.fetchSeriesEnrichment(identity, language)   ← 66
  7. if (enrichment != null) return TVDB_SUCCESS          ← 67-87
     else fetchTmdbEnrichment(TVDB_RECORD_MISSING)       ← 96-100
```

**Provider enum:** `TvProvider.{ KITSU | TVDB | TMDB }`.
**Decision reason enum** (partial, from file): `KITSU_SUCCESS, TVDB_SUCCESS, TVDB_INACTIVE, TVDB_IDENTITY_MISSING, TVDB_RECORD_MISSING, ...`.

Episode fetching (`TvMetadataRouter.kt:103-148`) follows the same tree but per-season.

### 3.2 Anime detection has two fragments

**Fragment A — inside the router** (`core/tvdb/TvMetadataRouter.kt:287-289`, `firstAnimeId`): iterates `request.contentId` and `request.fallbackContentId`, returns the first parseable `AnimeStremioId`.

**Fragment B — inside the detail ViewModel** (`ui/screens/detail/MetaDetailsViewModel.kt:1379-1398`):

```kotlin
val parsedAnimeIds = listOfNotNull(
    AnimeStremioId.parse(meta.id),
    AnimeStremioId.parse(itemId)
)
val hasAnimeId = parsedAnimeIds.any { animeId ->
    animeId.source != AnimeIdSource.IMDB || tmdbContentType != ContentType.MOVIE
}
val tvDecision = if (isTvContent || hasAnimeId) {
    tvMetadataRouter.fetchEnrichment(TvMetadataRequest(...))
} else {
    null
}
```

`hasAnimeId` rules:
- Any prefixed anime id (`kitsu:`, `mal:`, `anilist:`, `anidb:`, `tmdb:`, `tvdb:`) → true.
- IMDB id + non-movie content type → true.
- IMDB id + `ContentType.MOVIE` → **false** (IMDB movies bypass Kitsu lookup entirely).

Two fragments, slightly different inputs — see §3.5 for why this matters.

### 3.3 Anime id mapping = static asset

`core/anime/AnimeIdMappingService.kt:9-45` loads `anime-id-map.json` once (lazy). The asset structure (inferred):

```kotlin
class AnimeIdMapAsset(
    val byKitsu: Map<String, String>,
    val byMal: Map<String, String>,
    val byAnilist: Map<String, String>,
    val byAnidb: Map<String, String>,
    val byTvdb: Map<String, String>,
    val byImdb: Map<String, String>,
    val byTmdbMovie: Map<String, String>,
    val byTmdbSeries: Map<String, String>
)

fun resolveKitsuId(id: AnimeStremioId, mediaKind: ContentMediaKind): String? = when (id.source) {
    AnimeIdSource.IMDB   -> asset.byImdb[id.value]
    AnimeIdSource.TMDB   -> if (mediaKind == MOVIE) asset.byTmdbMovie[id.value] else asset.byTmdbSeries[id.value]
    AnimeIdSource.TVDB   -> asset.byTvdb[id.value]
    AnimeIdSource.MAL    -> asset.byMal[id.value]
    AnimeIdSource.ANILIST -> asset.byAnilist[id.value]
    AnimeIdSource.ANIDB  -> asset.byAnidb[id.value]
    AnimeIdSource.KITSU  -> id.value        // pass-through
}
```

`AnimeStremioId` (`core/anime/AnimeStremioId.kt:13-49`) parses the raw id string. Supported prefixes: `kitsu`, `mal`, `anilist`, `anidb`, `tmdb`, `tvdb`, `imdb`. If the string starts with `tt` it is treated as IMDB even without the `imdb:` prefix.

**This is why "Anime is not always gathered by Kitsu."** If a new anime is not pre-registered in `anime-id-map.json`, and the `MetaPreview.id` arriving at the detail screen is IMDB or TMDB, `resolveKitsuId()` returns `null`, Kitsu is skipped, and TVDB/TMDB serves the data.

### 3.4 Cross-provider id resolution

| From → To | Endpoint / mechanism | File:line |
|---|---|---|
| IMDB → TMDB | TMDB `GET /find/{externalId}?external_source=imdb_id`. | `core/tmdb/TmdbService.kt`, `ensureTmdbId()` called from `MetaDetailsViewModel.kt:699`. |
| TMDB → IMDB | TMDB `GET /movie/{id}/external_ids` or `GET /tv/{id}/external_ids`. | `core/tmdb/TmdbService.kt:161-220`. |
| IMDB → TVDB | TVDB `GET /search/remoteid/{remoteId}` with fallback `GET /search`, then `GET /series/{id}/extended`. Cached in `TvdbIdentityCacheStore`. | `core/tvdb/TvdbIdentityService.kt:84-104, 158`; `data/remote/api/TvdbApi.kt:20-32`. |
| TMDB → TVDB | Same TVDB `GET /search/remoteid/{remoteId}` with `TvdbRemoteIdSource.TMDB`. | `core/tvdb/TvdbIdentityService.kt:27-82`; `data/remote/api/TvdbApi.kt:28-32`. |
| TVDB remote-ids map (outbound) | From `GET /series/{id}/extended` response — `remoteIds: List<TvdbRemoteId>` with `sourceName` ∈ {IMDB, TMDB, TheTVDB, MyAnimeList, AniList, AniDB, ...}. | `TvdbIdentityService.kt:191-209`. |
| IMDB/TMDB/TVDB/AniList/MAL/AniDB → Kitsu | Static `anime-id-map.json` asset (no network call). | `AnimeIdMappingService.kt:32-45`. |
| IMDB → AniList / MAL | ARM (`armApi.resolveImdbToAnilist(imdbId)` → `[{anilist, myanimelist}]`). | `data/repository/SkipIntroRepository.kt:340-346`. |
| Kitsu → MAL / AniList | ARM (`armApi.resolveKitsuToMal(kitsuId)`, `armApi.resolveKitsuToAnilist(kitsuId)`). | `SkipIntroRepository.kt:182-222`. |

The ARM service and the static asset are the **two anime-id-bridge** mechanisms in the codebase. The TVDB identity cache and TMDB `/find` handle live-action bridging.

### 3.5 Ambiguity / mis-routing surfaces (8 documented)

1. **Anime not in `anime-id-map.json` → falls through.** `AnimeIdMappingService.resolveKitsuId()` returns `null` → router skips Kitsu, hits TVDB (many anime exist in TVDB) or TMDB fallback. Evidence: `AnimeIdMappingService.kt:32-45`.
2. **IMDB movie-anime is intentionally excluded from Kitsu.** `MetaDetailsViewModel.kt:1384`: `animeId.source != AnimeIdSource.IMDB || tmdbContentType != ContentType.MOVIE`. If an anime movie has only an IMDB id, `hasAnimeId=false`, `tvDecision=null`, TMDB serves it directly. There is no `movie` router path that consults Kitsu.
3. **Addon emits non-prefixed numeric id.** `{ "id": "123456", "type": "series" }` → `AnimeStremioId.parse("123456")` returns `null` (no prefix, not `tt…`). `hasAnimeId=false`. If `itemType=="series"` the router still fires (TVDB path) but Kitsu never participates.
4. **Two parallel anime-detection sites.** `TvMetadataRouter.firstAnimeId` checks `contentId+fallbackContentId`. `MetaDetailsViewModel.enrichMeta` checks `meta.id+itemId`. If the meta from the addon has a different id than what was navigated with, the two sites can disagree.
5. **Continue-watching IMDB ids via Trakt.** Trakt CW returns only `{trakt, slug, imdb, tmdb, tvdb}` — no Kitsu or MAL (`data/remote/dto/trakt/TraktMediaDtos.kt`, `TraktIdsDto`). For an anime CW entry, Kitsu is consulted only if the asset maps `byImdb[tt…]` or `byTmdbSeries[n]`. Otherwise TVDB/TMDB serves metadata.
6. **Addon cross-catalog collision.** Two rows with the same logical content can ship different `id`s (`tt…` vs `kitsu:123`). `ModernHomeModels.kt:597`: `key = "catalog_${row.key()}_${item.id}_${occurrence}"` — dedup is per-row, not global. Same item visibly appears twice with different metadata.
7. **Detail-view receives 3 args only.** `itemId, itemType, addonBaseUrl`. Provenance (which catalog produced this row) is not preserved. If the user navigated from a Kitsu row but `addonBaseUrl` is null (happens for built-in Kitsu catalog), the detail screen uses `getMetaFromAllAddons()` and the first addon to respond wins — which may not be the Kitsu-aware one.
8. **TMDB id ambiguity at detail time.** A `MetaPreview.id="tmdb:123"` can come from TMDB discovery OR an addon. `MetaDetailsViewModel.kt:205-213` only knows `itemId` + optional `addonBaseUrl`. With no addon url, `getMetaFromAllAddons("tmdb:123")` runs — addons may not all accept `tmdb:` prefixed ids.

---

## 4. Text-metadata provenance matrix

Conventions:
- **"Addon meta"** = the `Meta` object returned by a Stremio-style addon via `{addonBaseUrl}/meta/{type}/{id}.json` and stored under the item's key in the addon-meta JSON disk cache (see §10).
- **"Enrichment"** = the secondary overlay from TMDB / TVDB / Kitsu produced by `MetaDetailsViewModel.enrichMeta` and cached in `MetadataDiskCacheStore`.
- **"—"** = field not fetched from that source (or empty by default).
- **Storage columns** are the union of where the value ends up: `Meta` (domain cache, in-memory + addon-meta JSON on disk, `MetaRepositoryImpl`), `TmdbEnrichment` (`MetadataDiskCacheStore`, TTL 7d), `TvdbEnrichment` (`MetadataDiskCacheStore`, TTL 7d), `KitsuEnrichment` (in-memory only unless applied to `Meta`), `MDBListRepository` in-memory (TTL 30m), `OmdbEpisodeRatingsRepository` in-memory (TTL 24h), `KitsuAdvancedAnimeDetail` in-memory only.

### 4.1 Movies — text

| Field | Primary source | Fallback / overlay | API endpoint(s) | Storage | Multi-source stored? |
|---|---|---|---|---|---|
| Title (primary) | Addon meta `name` | TMDB `/movie/{id}` `title` (localized) via enrichment | `GET /meta/{type}/{id}.json` ; `GET /movie/{id}?language=…` | `Meta.name`, `TmdbEnrichment.localizedTitle` | **Yes** — addon name + TMDB localized title both cached |
| Alternate titles | — | — | (TMDB `/movie/{id}/alternative_titles` exists but is **not called**) | — | No |
| Genres | Addon meta `genres` | TMDB `genres[].name` | `/meta/…`, `/movie/{id}` (already appended via `credits,images,release_dates`) | `Meta.genres`, `TmdbEnrichment.genres` | Yes |
| Year / release date | Addon meta `releaseInfo` | TMDB `release_date` | `/meta/…`, `/movie/{id}` | `Meta.releaseInfo`, `TmdbEnrichment.releaseInfo`, `localReleaseInfo` computed at UI time | Yes |
| IMDb score | MDBList (primary if enabled) | Addon meta `imdbRating` → TMDB `vote_average` | MDBList `POST /rating/movie/imdb`; `/meta/…`; `/movie/{id}` | `MDBListRepository` in-mem; `Meta.imdbRating`; `TmdbEnrichment` (carries vote_average indirectly) | **Yes** — MDBList + addon + TMDB all can populate |
| Rotten Tomatoes | MDBList | — | `POST /rating/movie/tomatoes` | MDBList in-mem | MDBList-only |
| Metacritic | MDBList | — | `POST /rating/movie/metacritic` | MDBList in-mem | MDBList-only |
| Letterboxd | MDBList | — | `POST /rating/movie/letterboxd` | MDBList in-mem | MDBList-only |
| Trakt rating (0-10) | MDBList | — | `POST /rating/movie/trakt` | MDBList in-mem | MDBList-only |
| TMDB rating (user score) | MDBList | TMDB `vote_average` | `POST /rating/movie/tmdb`; `GET /movie/{id}` | MDBList in-mem + `TmdbEnrichment` | Yes |
| Audience score | MDBList | — | `POST /rating/movie/audience` | MDBList in-mem | MDBList-only |
| Description / overview | Addon meta `description` | TMDB `overview` (by `language`) | `/meta/…`, `/movie/{id}?language=…` | `Meta.description`, `TmdbEnrichment.description` | Yes |
| Description (localized) | TMDB (appended with `language`) | — | `GET /movie/{id}?language={lang}` | `TmdbEnrichment.description` | — |
| Runtime | Addon meta `runtime` | TMDB `runtime` | `/meta/…`, `/movie/{id}` | `Meta.runtime`, `TmdbEnrichment.runtimeMinutes` | Yes |
| Age rating | Addon meta | TMDB `release_dates.results[].release_dates[].certification` (by region) | `/movie/{id}?append_to_response=release_dates` | `Meta`, `TmdbEnrichment.ageRating` | Yes |
| Country | Addon meta `country` | TMDB `production_countries[].iso_3166_1` | `/meta/…`, `/movie/{id}` | `Meta`, `TmdbEnrichment.countries` | Yes |
| Original language | Addon meta `language` | TMDB `original_language` | `/meta/…`, `/movie/{id}` | `Meta.language`, `TmdbEnrichment.language` | Yes |
| Trailer link | Addon meta `trailerStreams[].ytId` | `TrailerService` movie path: TMDB trailer/teaser candidates first, then fallback YouTube ids, then Streailer (`data/trailer/TrailerService.kt:485-510`) | `/meta/…`, `/movie/{id}/videos` (not appended by default — separate call), Streailer stream fetch | `Meta.trailerYtIds`; TMDB title-video disk cache; process-lifetime TrailerService lookup caches | Yes |
| Recap / teaser | Teaser is consumed as a trailer candidate; TMDB `Recap` can be returned by provider and retained in the TMDB title-video cache, but movie title playback/UI does not consume it | — | `/movie/{id}/videos` | TMDB title-video disk cache only; no movie recap UI state | TMDB-only, provider-available but not consumed for movie playback |
| Director | Addon meta `director` (string list) | TMDB crew `jobs=Director` (with profile photo + TMDB id) | `/movie/{id}?append_to_response=credits` | `Meta.director` (strings), `TmdbEnrichment.castMembers` (with `character="Director"`) | **Yes** — strings in `Meta`, structured in `TmdbEnrichment` |
| Producer | TMDB crew `jobs=Producer/Executive Producer` | — | `/movie/{id}?append_to_response=credits` | `TmdbEnrichment.castMembers` | TMDB-only |
| Writer | TMDB crew `jobs=Writer/Screenplay` | — | same as above | `TmdbEnrichment.castMembers` | TMDB-only |
| Cast (character + actor) | Addon meta `cast` (flat list) | TMDB `credits.cast[]` with `character`, `profilePath`, `id`, `order` | `/meta/…`, `/movie/{id}?append_to_response=credits` | `Meta.cast` (strings), `Meta.castMembers` (structured from addon `appExtras.cast`), `TmdbEnrichment.castMembers` | Yes — three storages |
| Production companies | TMDB `production_companies[]` | — | `/movie/{id}` | `TmdbEnrichment.productionCompanies: List<MetaCompany>` | TMDB-only |
| Network companies | N/A for movies | — | — | — | — |
| Reviews | TMDB `/movie/{id}/reviews` merged with Trakt `GET /movies/{id}/comments/{sort}` when Trakt auth present | — | `/movie/{id}/reviews` (separate call, not appended) + Trakt movie comments | TMDB reviews in-memory + Trakt review state in UI memory only | Not persisted |
| More like this | TMDB `/movie/{id}/recommendations` | — | `/movie/{id}/recommendations` (separate call, not appended) | `TmdbMetadataService.moreLikeThisCache` **in-memory only** | Not persisted |
| Collection (if part of one) | TMDB `/collection/{collectionId}` | — | `/collection/{id}` | `TmdbEnrichment` (collection field) | TMDB-only |
| Person / actor bio (on click) | TMDB `/person/{id}` (`biography, birthday, deathday, place_of_birth, known_for_department`) | — | `/person/{id}`, `/person/{id}/combined_credits` or `/movie_credits`, `/tv_credits` | Not persisted — fetched on demand in ViewModel | Not cached |
| Person filmography | TMDB `/person/{id}/combined_credits` | — | above | in-memory per person-detail session | — |
| Company detail (name, country, homepage, description) | TMDB `GET /company/{id}` when user opens an organization detail view | — | TMDB `GET /company/{id}` + TMDB discover by company | transient ViewModel/network state only; no durable cache | request-time only |

### 4.2 TV — text

| Field | Primary source | Fallback / overlay | API endpoint(s) | Storage | Multi-source stored? |
|---|---|---|---|---|---|
| Title (primary) | Addon meta `name` | TVDB localized title (`/series/{id}/translations/{lang}` overlay) | `/meta/…`, TVDB `GET /series/{id}/extended`, `GET /series/{id}/translations/{lang}` | `Meta.name`, `TvdbEnrichment.localizedTitle` | Yes |
| Aliases (AKAs) | TVDB series `aliases[]` | — | `/series/{id}/extended` | `TvdbEnrichment.aliases` | TVDB-only |
| Genres | Addon meta | TVDB `genres[].name` | `/meta/…`, `/series/{id}/extended` | `Meta.genres`, `TvdbEnrichment.genres` | Yes |
| Year / first aired | Addon meta | TVDB `firstAired` | `/meta/…`, `/series/{id}/extended` | `Meta.releaseInfo`, `TvdbEnrichment.releaseInfo` | Yes |
| Last aired / status | TVDB `lastAired`, `status` | — | `/series/{id}/extended` | `TvdbEnrichment.status`, `TvdbEnrichment.lastAired` (where stored) | TVDB-only |
| IMDb score | MDBList | Addon `imdbRating` → TMDB `vote_average` | MDBList `POST /rating/show/imdb`; TMDB `/tv/{id}` | MDBList in-mem, `Meta.imdbRating`, `TmdbEnrichment.vote_average` | Yes |
| Rotten Tomatoes / Metacritic / Letterboxd / Trakt / TMDB / Audience | MDBList | — | `POST /rating/show/{type}` | MDBList in-mem | MDBList-only |
| Description | Addon meta | TVDB base `overview` → TVDB translation overlay `/series/{id}/translations/{lang}` (replaces when available) | `/meta/…`, `/series/{id}/extended`, `/series/{id}/translations/{lang}` | `Meta.description`, `TvdbEnrichment.description` | Yes |
| Description (localized) | TVDB translations | — | `/series/{id}/translations/{lang}` | `TvdbEnrichment.description` (overwritten by translation if present) | — |
| Runtime / average runtime | Addon meta | TVDB `averageRuntime` | `/meta/…`, `/series/{id}/extended` | `Meta.runtime`, `TvdbEnrichment.runtimeMinutes` | Yes |
| Age rating | Addon meta | TVDB `contentRatings[]` (by country) | `/series/{id}/extended` | `Meta`, `TvdbEnrichment.ageRating`, `TvdbEnrichment.contentRatings` | Yes |
| Country | Addon meta | TVDB `country`, `originalCountry` | `/meta/…`, `/series/{id}/extended` | `Meta`, `TvdbEnrichment.countries`, `TvdbEnrichment.originalCountry` | Yes |
| Original language | TVDB `originalLanguage` | Addon `language` | `/series/{id}/extended` | `Meta.language`, `TvdbEnrichment.originalLanguage` | Yes |
| Trailer link | TVDB `trailers[]` (when present on extended record) | TMDB `/tv/{id}/videos` (separate call when TMDB enrichment taken) | `/series/{id}/extended`; `/tv/{id}/videos` | `TvdbEnrichment.trailers`, `TmdbEnrichment.videos` | Yes |
| Director (TV-level) | — typically empty for series; episode-level directors on TVDB episode records | — | `/series/{id}/episodes` | `TvEpisodeMetadata` (if extracted) | — |
| Cast (character + actor) | Addon `cast` | TVDB cast (from extended characters[] when present) + TMDB `credits.cast[]` | `/meta/…`, `/series/{id}/extended`, `/tv/{id}?append_to_response=credits` | `Meta.cast`, `Meta.castMembers`, `TvdbEnrichment.castMembers` or `TmdbEnrichment.castMembers` (whichever provider wins) | Yes |
| Production companies | TVDB advanced `companies[]` (role=Production) | TMDB `production_companies[]` | `/series/{id}/extended`, `/tv/{id}` | `TvdbEnrichment.productionCompanies` or `TmdbEnrichment.productionCompanies` | — |
| Network companies | TVDB `originalNetwork`, `latestNetwork`, advanced companies (role=Network) | TMDB `networks[]` | `/series/{id}/extended`, `/tv/{id}` | `TvdbEnrichment.networks`, `TvdbEnrichment.originalNetwork`, `TvdbEnrichment.latestNetwork`, `TvdbEnrichment.platformName`; or `TmdbEnrichment.networks` | Yes |
| Season count / season types | TVDB `/series/{id}/extended` seasons[] → `TvdbSeasonOrderContext` | — | `/series/{id}/extended` | `TvdbEnrichment.seasonOrderContext` | TVDB-only |
| Air schedule (days/time) | TVDB `airsDays`, `airsTime` | — | `/series/{id}/extended` | `TvdbEnrichment.airsDays`, `.airsTime`, `.platformName` | TVDB-only |
| Episode titles | TVDB `/series/{id}/episodes` (per season) | TMDB `/tv/{id}/season/{n}` fallback | TVDB: `GET /series/{id}/episodes?season={n}` ; TMDB: `GET /tv/{tvId}/season/{seasonNumber}` | `TvEpisodeMetadata.title` (in `MetadataDiskCacheStore`, TVDB episodes TTL 24h) | — (only the winning provider's data is stored) |
| Episode descriptions | TVDB `overview` → translation overlay (per ep) | TMDB `overview` | TVDB: `/series/{id}/episodes` + `/episodes/{id}/translations/{lang}`; TMDB: `/tv/{id}/season/{n}` | `TvEpisodeMetadata.overview` | — |
| Episode descriptions (localized) | TVDB per-episode translation endpoint | — | `/episodes/{id}/translations/{lang}` (1 call per episode — no bulk) | `TvEpisodeMetadata.overview` (overwritten by translation) | — |
| Episode season numbers / episode numbers / absolute number | TVDB `seasonNumber`, `number`, `absoluteNumber` | TMDB `season_number`, `episode_number` | see above | `TvEpisodeMetadata.seasonNumber`, `.episodeNumber`, `tvdbEpisodeOrder` | — |
| Episode runtime | TVDB `length`, TMDB `runtime` | — | as above | `TvEpisodeMetadata.runtimeMinutes` | — |
| Episode air date | TVDB `airDate`, TMDB `air_date` | — | as above | `TvEpisodeMetadata.airDate`, `localReleaseInfo` | — |
| Episode ratings (IMDb) | Custom IMDb when configured | TMDB episode `voteAverage` + OMDB season ratings merge. MDBList per-episode ratings code exists but is not in the active selection path. | Custom IMDb `GET /v1/ratings/{tconst}?episodes=true`; TMDB `/tv/{id}/season/{n}`; OMDB `GET /?i=…&Season=…`; MDBList `POST /rating/show/imdb` (inactive path) | Custom IMDb in-mem (7d complete / 30m retry), TMDB season cache, OMDB in-mem (24h), MDBList in-mem (inactive for final badges) | **Yes** — multiple providers stored, but final UI path is binary: Custom IMDb or TMDB+OMDB |
| Reviews | TMDB `/tv/{id}/reviews` merged with Trakt `GET /shows/{id}/comments/{sort}` when Trakt auth present | — | TMDB `/tv/{id}/reviews`; Trakt comments route | TMDB reviews in-memory + Trakt review state in UI memory | not persisted |
| More like this | TMDB `/tv/{id}/recommendations` | — | `/tv/{id}/recommendations` | `TmdbMetadataService.moreLikeThisCache` in-memory | not persisted |
| Creator / "created by" | TMDB `created_by[]` | — | `/tv/{id}` | `TmdbEnrichment.castMembers` (character="Creator") | TMDB-only |

### 4.3 Anime — text

Anime enrichment calls only `KitsuMetadataService` (`core/anime/KitsuMetadataService.kt`). If the router cannot resolve a Kitsu id (see §3.3), anime falls out of this matrix and into the TV matrix.

| Field | Source | API endpoint | Storage | Notes |
|---|---|---|---|---|
| Title (canonical) | Kitsu `attributes.canonicalTitle` | `GET /anime/{id}` | Request-time only in `TvMetadataEnrichment.localizedTitle`; not durably cached | — |
| Native / alt titles (JA, en_jp, en) | Kitsu `attributes.titles[…]` | same | Not carried forward into a dedicated stored field today; only the chosen canonical/localized title survives | — |
| Genres | — (not currently pulled) | Kitsu `/anime/{id}/categories` would supply them | `TvMetadataEnrichment.genres = emptyList()` | `KitsuMetadataService.kt:55-69` |
| Start / end date | Kitsu `startDate`, `endDate` | `GET /anime/{id}` | `TvMetadataEnrichment.releaseInfo` (startDate). `endDate` **not stored** | — |
| IMDb / other ratings | — | MDBList doesn't classify anime: `MDBListRepository.normalizeMediaType` returns "show"/"movie" only (`data/repository/MDBListRepository.kt:106`) | — | Anime do not receive MDBList enrichment today |
| Description / synopsis | Kitsu `synopsis` → fallback `description` | `GET /anime/{id}` | `TvMetadataEnrichment.description` | — |
| Description localized | — | Kitsu lacks per-language overview endpoint | — | — |
| Runtime | Kitsu `episodeLength` | `GET /anime/{id}` | `TvMetadataEnrichment.runtimeMinutes` | — |
| Age rating | Kitsu `ageRating` | same | `TvMetadataEnrichment.ageRating` | — |
| Status | Kitsu `status` | same | `TvMetadataEnrichment.status` | — |
| Original language | **Hardcoded "ja"** | — | `TvMetadataEnrichment.language = "ja"` | `KitsuMetadataService.kt:55-69` |
| Episode titles | Kitsu `canonicalTitle` of each episode | `GET /anime/{id}/episodes?limit=20&offset=…` (paged, max 100 pages) | Request-time only in `TvEpisodeMetadata.title`; not durably cached | |
| Episode descriptions | Kitsu `synopsis` (→ `description`) | same | Request-time only in `TvEpisodeMetadata.overview`; not durably cached | — |
| Episode air date | Kitsu `airdate` | same | Request-time only in `TvEpisodeMetadata.airDate`; not durably cached | — |
| Episode runtime | Kitsu `length` | same | Request-time only in `TvEpisodeMetadata.runtimeMinutes`; not durably cached | — |
| Episode season number | Kitsu `seasonNumber` (defaults to 1 if null) | same | Request-time only in `TvEpisodeMetadata.seasonNumber`; not durably cached | — |
| Characters | Kitsu castings → included character | `GET /anime/{id}/castings?include=character,person` (paginated) | `KitsuAdvancedAnimeDetail.characters` **in-memory only, not persisted** | `KitsuMetadataService.kt:133-155` |
| Voice actors (seiyuu) | Kitsu castings with role ≈ "Voice Actor" → included person (JA preferred) | same | `.characters[].actorName/actorImage` in-memory | — |
| Staff (directors, writers, producers) | Kitsu anime-staff | `GET /anime/{id}/anime-staff` | `KitsuAdvancedAnimeDetail.staff` in-memory only | — |
| Production companies (studios) | Kitsu anime-productions with role=Studio etc. | `GET /anime/{id}/anime-productions` | `KitsuAdvancedAnimeDetail.productionCompanies` in-memory only | — |
| Related titles (sequel, prequel, alt) | Kitsu media-relationships | `GET /anime/{id}/media-relationships` filtered to anime type | `KitsuAdvancedAnimeDetail.relatedTitles` in-memory only | — |
| Reviews | — | `GET /reviews?filter[anime]=…` available per `kitsu.apib` but **not called** | — | — |
| More like this | — | not called | — | — |

### 4.4 Fields that are stored from multiple sources (ambiguity callouts)

These are fields where the same logical value gets written into more than one storage location by different providers, making "which one wins" a UI/read-time decision:

| Field | Sources stored in parallel | Where each lives | Resolution at render |
|---|---|---|---|
| IMDb rating (movie / TV) | Addon `imdbRating` + TMDB `vote_average` + MDBList imdb rating | `Meta.imdbRating`, `TmdbEnrichment` indirectly, `MDBListRepository` in-mem | MDBList wins if enabled; else falls through to `Meta.imdbRating` (addon) / TMDB — selection logic in `MetaDetailsViewModel` rating section |
| Description | Addon `description` + TVDB base overview + TVDB translation overlay + TMDB overview | `Meta.description`, `TvdbEnrichment.description`, `TmdbEnrichment.description` | TVDB translation → TVDB base → TMDB → addon |
| Title | Addon `name` + TVDB `name` / localized title + TMDB localized title | `Meta.name`, `TvdbEnrichment.localizedTitle`, `TmdbEnrichment.localizedTitle` | Enrichment localized → addon fallback |
| Genres | Addon + TVDB + TMDB | `Meta.genres`, `TvdbEnrichment.genres`, `TmdbEnrichment.genres` | Enrichment wins, addon is fallback |
| Runtime | Addon + TVDB `averageRuntime` + TMDB `runtime` | `Meta.runtime`, `TvdbEnrichment.runtimeMinutes`, `TmdbEnrichment.runtimeMinutes` | Enrichment wins |
| Age rating | Addon + TVDB `contentRatings` + TMDB `release_dates`/`content_ratings` | `Meta`, `TvdbEnrichment.ageRating`, `TmdbEnrichment.ageRating` | Enrichment wins |
| Country | Addon + TVDB + TMDB `production_countries` | same pattern | Enrichment wins |
| Original language | Addon + TVDB `originalLanguage` + TMDB `original_language` | same pattern | Enrichment wins |
| Trailer | Addon `trailerStreams[].ytId` + TMDB videos + TVDB trailers + Streailer streams | `Meta.trailerYtIds`, TMDB title/season video caches, `TvdbEnrichment.trailers`, Streailer stream responses | Movie: TMDB → fallback YouTube ids → Streailer (`TrailerService.kt:485-510`). TV title: TVDB → Streailer → fallback YouTube ids → TMDB (`TrailerService.kt:522-569`) |
| Director | Addon `director` (string list) + TMDB crew (structured, with photos) | `Meta.director`, `TmdbEnrichment.castMembers` with character="Director" | Structured wins on detail, addon strings shown on home |
| Cast | Addon `cast` flat + addon `appExtras.cast` structured + TMDB/TVDB `castMembers` structured | `Meta.cast`, `Meta.castMembers`, `Tv/TmdbEnrichment.castMembers` | Enrichment wins on detail |
| Poster | Addon `poster` + TVDB artwork + TMDB (null if provider active) + RPDB URL + Top-Posters URL | Single `Meta.poster`/`MetaPreview.poster` field, plus `posterProviderTag` | See §5 — pipeline writes one URL but selection is time-sensitive |
| Backdrop | Addon `background` + TVDB artwork + TMDB `backdrop_path` | Single field, `Meta.background` | Enrichment if addon null |
| Logo | Addon `logo` + TVDB artwork (logo) + TMDB `images.logos` | Single field, `Meta.logo` | Enrichment if addon null |
| Episode ratings (IMDb) | Custom IMDb when configured; else TMDB episode `voteAverage` + OMDB season-bulk merge. MDBList per-episode code exists but is inactive. | Custom IMDb in-mem (7d / 30m retry) + TMDB season cache + OMDB in-mem (24h) + inactive MDBList in-mem | Final UI path is Custom IMDb **or** TMDB+OMDB; MDBList does not participate |
| Episode description | TVDB base overview + TVDB translation overlay + TMDB | `TvEpisodeMetadata.overview` (single field, last writer wins) | TVDB translation → TVDB base → TMDB |

### 4.5 Fields not fetched today

These come up in the user's requested field set but have no current path in the codebase:

- **Alternate titles / AKAs for movies** (TMDB `/movie/{id}/alternative_titles` is not called).
- **Movie keywords / tags** (`/movie/{id}/keywords`).
- **Watch providers** (`/movie/{id}/watch/providers`, `/tv/{id}/watch/providers`).
- **Persisted company / network detail cache**. Deep company/network detail is fetched live through `TmdbOrganizationService`, but there is no durable cache layer.
- **Actor bios, birthdate, filmography for TV detail / cast detail** — `GET /person/{id}`, `/person/{id}/combined_credits` exist and can be called from the person-detail screen but nothing is persisted.
- **Anime genres** — Kitsu returns them in `/categories` but `KitsuMetadataService.fetchEnrichment` returns `emptyList()` for genres.
- **Anime ratings from external sources** — MDBList is not consulted for anime (`normalizeMediaType` only accepts "show"/"movie").
- **Anime localized descriptions** — Kitsu has no translations endpoint analogous to TVDB; native/JA titles are in `attributes.titles` but synopsis is single-language.
- **Movie recap playback surface** — TMDB can return movie `Recap` videos, but movie title playback/UI does not consume them.
- **Anime character photos persisted** — fetched into `KitsuAdvancedAnimeDetail.characters[].characterImage` but not saved; app rotation loses them.
- **"More like this" / recommendations and reviews persisted** — `TmdbMetadataService.moreLikeThisCache` and `reviewsCache` are in-memory maps that get cleared on process death.

---

## 5. Image / artwork routing

### 5.1 Image-field source matrix

| Image | Primary | Fallback chain (top → bottom) | Endpoint(s) | Storage |
|---|---|---|---|---|
| Poster | PosterRatingsUrlResolver's **active provider** (RPDB or Top-Posters) if both a provider is configured AND the id can be formatted for that provider | TVDB poster (artwork) → TMDB (only if no active poster provider) → Kitsu `posterImage.bestUrl()` → addon `poster` | RPDB `GET /{apiKey}/{idType}/poster-default/{id}.jpg` ; Top-Posters `GET /{apiKey}/{idType}/poster/{id}.jpg?fallback_url=…` ; TVDB artworks ; TMDB `append_to_response=images` (skipped when active provider) ; Kitsu `/anime/{id}` | Single `Meta.poster` / `MetaPreview.poster`, plus `posterProviderTag` — final detail poster is base-meta-biased because enrichment posters are not copied back into `Meta` |
| Backdrop | TVDB (artwork type=backdrop) | TMDB `backdrop_path` → Kitsu `coverImage.bestUrl()` → addon `background` | `/series/{id}/artworks`, TMDB `append_to_response=images`, Kitsu `/anime/{id}` | Single `Meta.background` |
| Clear-title logo | TMDB `images.logos` filtered by language (language → English → null preference) | TVDB artwork type=clearlogo → addon `logo` | `GET /movie/{id}?append_to_response=images&include_image_language=…` ; TVDB `/series/{id}/artworks` | Single `Meta.logo`, `TmdbEnrichment.logo`, `TvdbEnrichment.logo` |
| Episode thumbnail | TVDB episode `image` | TMDB `still_path` → Kitsu episode `thumbnail.bestUrl()` | TVDB `/series/{id}/episodes` ; TMDB `/tv/{id}/season/{n}` ; Kitsu `/anime/{id}/episodes` | `TvEpisodeMetadata.thumbnail` |
| Actor photo | TMDB cast member `profile_path` (via `w500`) | TVDB people → Kitsu people `avatar` (anime only) | TMDB `append_to_response=credits` ; Kitsu included resources | `MetaCastMember.photo` (single URL) |
| Anime character photo | Kitsu `characters.image.bestUrl()` | — | Kitsu `/anime/{id}/castings?include=character` | `KitsuAdvancedAnimeCharacter.characterImage` — **in-memory only** |
| Voice-actor photo | Kitsu `person.image.bestUrl()` | — | Kitsu castings include | same — in-memory only |
| Production company logo | TMDB `production_companies[].logo_path` | TVDB advanced company logos | TMDB `/movie/{id}`, TVDB `/series/{id}/extended` | `MetaCompany.logo` (single URL) |
| Network logo | TMDB `networks[].logo_path` | TVDB network logo | same | `MetaCompany.logo` |

### 5.2 Poster-URL resolution pipeline (home rows)

1. Addon meta fetched → poster is `meta.poster` from addon (`data/repository/MetaRepositoryImpl.kt:88` applies `posterRatingsUrlResolver.apply(meta, activePosterProvider)` first).
2. If RPDB or Top-Posters is active and the `contentId` can be parsed into an id format the provider supports → poster URL is **rewritten** to the provider URL.
3. Home catalog coordinator merges persisted home display metadata (`HomeCatalogRefreshCoordinator.mergePersistedHomeDisplayMetadata`).
4. Home-provider localized overlay applies enrichment (TVDB for TV, TMDB for movies), using `enrichment.poster ?: poster` — enrichment only overrides if the current value is `null` (`HomeCatalogRefreshCoordinator.kt:542-554`).
5. `PosterRatingsUrlResolver.apply` runs **again** (`HomeCatalogRefreshCoordinator.kt:164`); this pass is idempotent (it detects URLs that are already provider-owned and skips).
6. UI renders; `buildImagePrefetchTelemetry` → `prefetchImageEntries` warms Coil's 200 MB disk cache (`NexioApplication.kt:69-73`).

TMDB's own enrichment explicitly **does not** contribute a poster URL when a poster provider is active (`core/tmdb/TmdbMetadataService.kt:197-201`):

```kotlin
val poster = if (activePosterProvider == null) {
    buildImageUrl(details?.posterPath, size = "w500")
} else {
    null
}
```

The provider-active state is threaded into the TMDB disk-cache key: `val cacheKey = "$tmdbId:${contentType.name}:$normalizedLanguage:$providerToken"` (`TmdbMetadataService.kt:106`). Flipping the active provider in settings produces a different `providerToken` and therefore invalidates the prior cache entry automatically.

### 5.3 Why TMDB posters still sometimes win despite RPDB/Top-Posters being configured

The user's reported symptom has several concrete leaks. They are not mutually exclusive and #1, #2, and #3 are source-verified in the current code.

1. **Detail-screen path does not re-apply the resolver, and does not currently copy enrichment poster fields at all.** The explicit `posterRatingsUrlResolver.apply()` passes are in `MetaRepositoryImpl` (base meta read) and `HomeCatalogRefreshCoordinator` (home rows). No equivalent re-apply exists in `MetaDetailsViewModel.enrichMeta`. More importantly, `enrichMeta` updates backdrop and logo but does not copy `tvEnrichment?.poster` or `tmdbEnrichment?.poster` into the final `Meta`. Final detail poster behavior is therefore base-meta-biased: if the base poster was null or unsupported for RPDB/Top-Posters, the enrichment poster does not rescue the detail view.
2. **`buildRpdbPosterUrl()` silently falls back to the addon URL for unsupported id types** (`core/poster/PosterRatingsUrlResolver.kt:70-96`). RPDB's ID-type parser accepts IMDB, TMDB (with `movie-`/`series-` prefix), TVDB only. An id like `kitsu:12345` or a non-prefixed numeric addon id cannot be formatted into a RPDB URL, and the resolver returns `originalPosterUrl`. The caller cannot distinguish "RPDB said no" from "RPDB said yes." This is the most likely cause for Kitsu-sourced catalog rows.
3. **Top-Posters accepts broader id types** (IMDB, TMDB, TVDB, Trakt, MAL, Kitsu, AniList, AniDB — `PosterRatingsUrlResolver.kt:122-129`) but only if the content id can be parsed into one of those. Addon-emitted opaque ids (`netflix:SOMETHING`) still fall through.
4. **Stale disk-cache entry with mismatched `providerToken`.** `MetadataDiskCacheStore.readMeta()` includes a `hasValidPosterProviderTag(providerToken)` filter (`core/metadata/MetadataDiskCacheStore.kt:134-148`), so on a provider change, stale entries are ignored for the new token. However addon and snapshot stores can still hold older native or premium poster URLs until those stores are refreshed.
5. **Home-row enrichment overlay only sets poster when `enrichment.poster ?: poster`, never when both are non-null.** This is correct for providers, but it means a home row that was painted with a provider URL that was *subsequently* overridden by enrichment (because `enrichment.poster` was non-null and the provider URL was stripped for some reason upstream) will not self-heal.
6. **Image-cache staleness via Coil.** The image URL field can be right, but Coil's 200-MB disk cache could serve a previously-fetched image byte stream keyed by URL. This only bites when the URL did not change but the remote artwork did (not the user's scenario).
7. **`api.top-streaming.stream` vs `api.top-posters.com`.** The user listed `api.top-streaming.stream` but the codebase hardcodes `api.top-posters.com` (`PosterRatingsUrlResolver.kt:131`). If this repo represents the production build and `top-streaming.stream` is where the user expects traffic to go, the client will silently fail (or hit a different service).

### 5.4 Single-URL storage

Every image-bearing domain model stores exactly one URL per image type:

- `Meta.poster: String?`, `.background: String?`, `.logo: String?`
- `Meta.posterProviderTag: String?` (marker only, not a second URL)
- `MetaPreview.poster: String?`, etc., same shape.
- `MetaCastMember.photo: String?`.
- `MetaCompany.logo: String?`.
- `TvEpisodeMetadata.thumbnail: String?`.

There is no "candidate set" concept. Once a URL is chosen at write time, the other providers' URLs are discarded. This forces every re-evaluation (user flips RPDB setting) to be a full re-fetch or a disk-cache invalidation.

### 5.5 Fanart.tv is not wired up

`apiblueprints/fanarttv.json` is present in the repo. Grep for `fanart` in `app/src/main/java/com/nexio/tv/` returns zero hits. No service, no DI binding, no URL construction. Fanart.tv is a blueprint-only artifact.

---

## 6. Identity mapping and lookup caches

| Cache | Type | TTL | File |
|---|---|---|---|
| `TvdbIdentityCacheStore` | Disk, persisted | 30 d for refs | `core/tvdb/TvdbIdentityCacheStore.kt` |
| `TvdbMergeAliasStore` | Disk | — | `core/tvdb/TvdbMergeAliasStore.kt` |
| `TmdbService.tmdbIdByImdb` + `imdbByTmdbId` | In-memory maps | session | `core/tmdb/TmdbService.kt` |
| `KitsuAuthDataStore` | Disk | — | `core/anime/` |
| `anime-id-map.json` | App asset, lazy-loaded | app build | `core/anime/AnimeIdMappingService.kt:9-30` |
| `ARM` (anime-relations-mapper) | Network call, per-request cache | n/a | called by `SkipIntroRepository` |
| In-flight dedup on TVDB identity | `ConcurrentHashMap<String, CompletableDeferred<...>>` | request-lifetime | `TvdbIdentityService.kt:25, 52-54` |

`TvdbIdentityService.resolveSeriesByRemoteId` (`core/tvdb/TvdbIdentityService.kt:27-82`):
1. Normalize id (strip `tt` for IMDB inputs so the remote-id search matches TVDB format).
2. Check `identityCacheStore`.
3. Check in-flight map; if someone else is already looking this id up, await the same `CompletableDeferred`.
4. `POST /search/remote-id?remoteId={id}` → on hit, fetch `GET /series/{id}/extended` to build the identity.
5. Fallback `POST /search?type=series` if remote-id returns nothing.
6. Write to cache store.

The extended record's `remoteIds: List<TvdbRemoteId>` gets folded into `Map<TvdbRemoteIdSource, Set<String>>` (`TvdbIdentityService.kt:158, 191-209`). Source names in that payload: `IMDB`, `TMDB`, `TheTVDB`, `MyAnimeList`, `AniList`, `AniDB` (at least).

---

## 7. Continue-watching, scrobble, skip-intro

### 7.1 Trakt CW

Endpoint: `GET /sync/playback/{type}` where `{type}` is `movies` or `episodes` (`apiblueprints/trakt.apib:2810-2871`).

Response DTO (`data/remote/dto/trakt/TraktSyncDtos.kt:44-52`):

```kotlin
data class TraktPlaybackItemDto(
    val id: Long?,             // deletable via DELETE /sync/playback/{id}
    val type: String?,         // "movie" | "episode"
    val progress: Float?,      // 0-100
    val pausedAt: String?,
    val movie: TraktMovieDto?,
    val show: TraktShowDto?,
    val episode: TraktEpisodeDto?
)

data class TraktIdsDto(val trakt: Int?, val slug: String?, val imdb: String?, val tmdb: Int?, val tvdb: Int?)
```

Mapping to `WatchProgress` (`data/repository/TraktProgressService.kt:2006-2055` for movies; similar for episodes):

- `source = WatchProgress.SOURCE_TRAKT_PLAYBACK`
- `progressPercent = item.progress?.coerceIn(0f, 100f)`
- `position = 0L, duration = 0L` — Trakt does not carry millisecond playback position
- `contentId` = preferred external id (IMDB first)
- `traktPlaybackId, traktMovieId, traktShowId, traktEpisodeId` stored for future mutations (e.g., delete via `DELETE /sync/playback/{id}`)

Resume-at-play math (`domain/model/WatchProgress.kt:66-76`): given the real duration at playback time, `resolveResumePosition(durationMs)` returns `position` if `SOURCE_LOCAL`, else `(progressPercent / 100f * durationMs).toLong()`.

### 7.2 Simkl CW

Endpoint: `GET /sync/playback/{type}` (`apiblueprints/simkl.apib:4096-4260`).

`SimklPlaybackItemDto` (`data/remote/dto/simkl/SimklTrackingDtos.kt:51-62`) differs in two important ways:

- Carries `anime` field in addition to `movie` / `show` (Simkl models anime separately).
- `SimklIdsDto` adds `mal: String?` (MyAnimeList id). Kitsu id is still absent.

```kotlin
data class SimklIdsDto(
    val simkl: Long?, val simklId: Long?, val slug: String?,
    val imdb: String?, val tmdb: String?, val tvdb: String?, val mal: String?
)
```

Episode payload `SimklEpisodeDto` also carries `tvdbSeason` and `tvdbNumber` (TVDB numbering alongside "absolute" season/number) — important for the anime dual-numbering problem.

### 7.3 CW merge / home-row render

`TrackingProgressService.observeAllProgress()` (`data/repository/TrackingProgressService.kt:101-138`) observes the "effective provider" (Trakt or Simkl, whichever is active for the profile) and returns that provider's `WatchProgress` list — no merge, no fallback. `ContinueWatchingSnapshotService.kt:50-219` combines `watchProgressRepository.allProgress` with any next-up entries (`observeContinueWatchingNextUp`, `observeSyntheticContinueWatchingNextUp`) into the single `ContinueWatchingSnapshot` the home screen renders.

### 7.4 Scrobble

Trakt endpoints (`apiblueprints/trakt.apib:2598-2750`):
- `POST /scrobble/start` — begin playback / resume
- `POST /scrobble/pause` — heartbeat / user pause
- `POST /scrobble/stop` — end, marks as watched when `progress >= 80`

Simkl has analogous `POST /scrobble/{start|pause|stop}` (`apiblueprints/simkl.apib:2930-3240`).

Request body (`data/remote/dto/trakt/TraktScrobbleDtos.kt:7-13`): `{ movie | show + episode, progress: Float, app_version }`. Identity sent upstream via `TraktIdsDto` (IMDB, TMDB, TVDB, Trakt, slug).

Adapters route via the mutation outbox: `TraktScrobbleMutationAdapter` (`data/repository/trakt/TraktScrobbleMutationAdapter.kt:105-139`) and `SimklScrobbleMutationAdapter` (`data/repository/simkl/SimklScrobbleMutationAdapter.kt:36-56`). Both implement `TraktMutationAdapter` and register into `TraktMutationOutboxCoordinator` (`data/trakt/outbox/TraktMutationOutboxCoordinator.kt:18-20`) — meaning Simkl scrobbles share Trakt's outbox worker, retry policy, lease lifecycle.

Playback-time firing (`ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt:284-366`):
- On media / item change: build `currentScrobbleItem`, reset flags, increment `scrobbleStartRequestGeneration`.
- First play event → `emitScrobbleStart()` → `trackingScrobbleService.scrobbleStart(item, progressPercent)` → starts heartbeat.
- Heartbeat: periodic `scrobblePause(currentPercent)`.
- User pause → `emitPauseScrobble(progressPercent)` → `emitScrobbleStop`.
- Auto-completion at ≥80%: `emitCompletionScrobbleStop()` — fires exactly once (`hasSentCompletionScrobbleForCurrentItem` guard).

Trakt `POST /checkin` (`apiblueprints/trakt.apib:2482-2596`) exists — used for manual "checkin now" actions; flows through the same adapter (`TraktScrobbleMutationAdapter.kt:75-103`).

### 7.5 Skip intro / credits

Provider arbiter (`data/repository/SkipIntroRepository.kt:27-44`):

```kotlin
fun resolve(contentType, effectiveId, fallbackId): SkipProviderRoute = when {
    effectiveId.startsWith("mal:") || effectiveId.startsWith("kitsu:") -> ANIME_PRIMARY
    contentType == "anime"                                             -> ANIME_PRIMARY
    ":anime:" in effectiveId || ":anime:" in fallbackId                -> ANIME_PRIMARY
    else                                                               -> THEINTRODB
}
```

**Anime-Skip** (`data/remote/api/SkipIntroApi.kt:145-193`): GraphQL over `POST /graphql` with `X-Client-ID` header.
- Step 1 — resolve AniList → AnimeSkip show id: `{ findShowsByExternalId(service: ANILIST, serviceId: "{anilistId}") { id } }`.
- Step 2 — fetch episodes: `{ findEpisodesByShowId(showId: "{id}") { season number timestamps { at type { name } } } }`.
- Timestamp `type.name` mapped to `op` (intro / new intro), `ed` (credits), `recap` (`SkipIntroRepository.kt:303-312`).

**TheIntroDB** (`data/remote/api/SkipIntroApi.kt:15-46`): REST `GET /media?tmdb_id=…&imdb_id=…&season=…&episode=…`. Response carries four arrays: `intro`, `recap`, `credits`, `preview`, each `{ start_ms, end_ms, confidence, submission_count, updated_at }`. Mapped per user settings (`TheIntroDbSegmentMapper.map`).

**Anime → id chain** (`SkipIntroRepository.kt:125-222`):
1. Prefer MAL id from Simkl CW → `fetchFromAniSkip(malId, episode)`.
2. Else IMDB → ARM `resolveImdbToAnilist(imdb)` → AnimeSkip GraphQL.
3. Kitsu id path: `armApi.resolveKitsuToMal` → if null `armApi.resolveKitsuToAnilist`.

**Cache**: `ConcurrentHashMap<String, List<SkipInterval>>` keyed by `"{contentId}:{season}:{episode}"` or `"anime:{imdb}:{s}:{e}"` etc. **In-memory only — no disk persistence.** Plus `malIdCache` and `animeSkipShowIdCache` for id lookups. `clearCachedIntervals()` clears all.

### 7.6 Identity-interop table (skip / scrobble)

| Service | Query identity | Returned identity | Chain to primary metadata |
|---|---|---|---|
| Trakt CW | OAuth | IMDB, TMDB, TVDB, trakt, slug | IMDB → TMDB/TVDB direct; anime: IMDB → ARM → AniList/MAL → Kitsu asset/AnimeSkip |
| Simkl CW | OAuth | IMDB, TMDB, TVDB, simkl, slug, **MAL** | MAL usable directly for AniSkip; Kitsu id still resolved via ARM / asset |
| Anime-Skip | AniList id (must resolve from IMDB/MAL/Kitsu via ARM) | skip intervals only | not used for metadata |
| TheIntroDB | TMDB or IMDB id | skip intervals | not used for metadata |
| ARM | any of (imdb, kitsu, mal, anilist, anidb, tvdb) | any of the others | the bridge between id spaces |

---

## 8. Ratings providers (MDBList / OMDB / IMDb)

### 8.1 MDBList

Endpoint: `POST /rating/{media_type}/{ratingType}` with body `{ ids: [...], apikey: "..." }`.
- `media_type` is normalized to `show` or `movie` only; anime is not routed through MDBList (`data/repository/MDBListRepository.kt:106`).
- `ratingType` ∈ `imdb, tomatoes, metacritic, letterboxd, trakt, tmdb, audience`.

Code sends **one id per call** (`MDBListRepository.kt:223`: `ids = listOf(imdbId)`) — so N ids × M rating types = N·M round trips, all serialized by the shared OkHttp dispatcher.

TTL: 30 min in-memory (per 003 analysis); separate 7-d "complete" / 30-m retry semantics for per-episode ratings.

Episode ratings:
- Code path exists: `MDBListRepository.getEpisodeRatingsForMeta` / `fetchEpisodeRatingsForSeason`.
- Active production caller: **none** in the final episode-badge selection path.
- Final detail badges use Custom IMDb first, else TMDB + OMDB merge.

### 8.2 OMDB

Endpoint: `GET http://www.omdbapi.com/?i={seriesImdbId}&Season={n}&apiKey=…` (`OmdbEpisodeRatingsRepository.kt:39-71`). Returns a whole season's episode ratings in one call — the only source doing season-bulk ratings today. In-memory cache, 24 h TTL.

### 8.3 IMDb direct

There is no official IMDb API integration, but there **is** a Nexio-operated Custom IMDb backend and it is the **first-priority** title and episode rating provider when configured at build time.

Custom IMDb endpoints (`data/remote/CustomImdbClient.kt:39-163`):

| Route | Method | Capability | Active callers | Batch-capable? | Validation |
|---|---|---|---|---|---|
| `/v1/meta/stats` | `GET` | provider validation / health check | settings validation through `CustomImdbClient.validate()` | no | yes |
| `/v1/ratings/bulk` | `POST` | title ratings | `CustomImdbTitleRatingsRepository` → `TitleRatingOverrideRepository` | **yes** | no |
| `/v1/ratings/{tconst}?episodes=true` | `GET` | episode ratings for one series | `CustomImdbEpisodeRatingsRepository` → `EpisodeRatingsSelectionRepository` | no | no |

Priority:

- **Title rating:** Custom IMDb → MDBList IMDb → base/addon/TMDB/TVDB rating
- **Episode rating:** Custom IMDb **or** TMDB + OMDB merge. MDBList per-episode code exists but is not in the active selection path.

Cache / TTL:

| Repository | Key | TTL | Persistence |
|---|---|---|---|
| `CustomImdbTitleRatingsRepository` | `<imdbId>` | 7 days | in-memory only |
| `CustomImdbEpisodeRatingsRepository` | `<seriesImdbId>:<seasonAndEpisodeSignature>` | 7 days complete / 30 minutes retry | in-memory only |

---

## 9. API blueprints inventory + bulk-enrichment opportunities

Blueprints in `apiblueprints/`:

| File | Provider | Coverage |
|---|---|---|
| `tmdb.json` | TMDB v3 | Full REST surface |
| `tvdb.yml` | TVDB v4 | Full REST surface |
| `kitsu.apib` | Kitsu v1 (edge) | JSON:API full surface |
| `trakt.apib` | Trakt v2 | Full REST surface |
| `simkl.apib` | Simkl v1 | Full REST surface |
| `mdblist.apib` | MDBList | Rating + list surface |
| `rpdb.apib` | RatingPosterDB | Image URL spec + `/isValid`, `/requests` |
| `topposters.json` | Top-Posters | Image URL spec + `/auth/verify` |
| `fanarttv.json` | Fanart.tv | Full REST surface (blueprint-only, not wired) |
| `tidb.yaml` | TheIntroDB | Media timestamps |

### 9.1 Bulk opportunities currently unused

| Provider | Opportunity | Current shape | Cost saving |
|---|---|---|---|
| TMDB movie | Append `recommendations,reviews,videos,translations` to existing `credits,images,release_dates` | Each is a separate `GET /movie/{id}/{…}` call today | 4 calls → 1 per movie detail |
| TMDB TV | Same — extend `credits,images,content_ratings` append | 3–4 extra calls per TV detail | 4 calls → 1 |
| TMDB | `GET /person/{id}?append_to_response=combined_credits,images,external_ids` | Multiple calls for person detail screen | 3 → 1 |
| TVDB | `GET /series/{id}/episodes/translations` batch | Per-episode `/episodes/{id}/translations/{lang}` (~N per season) | up to 24×N → 1 per season |
| TVDB | `GET /series` with pagination for sync | Nexio pulls series one at a time | (only if bulk sync is added) |
| Kitsu | `GET /anime?filter[text]=…` search | Current code only does id-based fetch | dynamic anime detection to complement `anime-id-map.json` |
| Kitsu | `GET /categories` for a given anime | `KitsuMetadataService.fetchEnrichment` returns `genres = emptyList()` | populate genres |
| MDBList | Multi-id `ids: [id1, id2, …]` batch | `ids = listOf(imdbId)` today | N ratings of one type → 1 call; also possible: fewer rating types queried from the "top-level" `GET /imdb/…` which bundles ratings |
| MDBList | `GET /imdb/{media_type}/{imdbid}` bundles ratings in one call per item | Per-rating-type POSTs | 7 rating types → 1 |
| Custom IMDb | `POST /v1/ratings/bulk` for title ratings | Already used, but only at title-rating layer | preserve as the only actively-used bulk rating endpoint |
| OMDB | Already season-bulk — no further win | — | — |
| Trakt | `GET /sync/history` with pagination; `GET /users/{id}/lists/{list_id}/items/{type}` — both carry full external ids, no extra calls needed | — | — |
| Simkl | `GET /sync/all-items/{type}` is a full-library snapshot — currently also used, per 003 analysis | — | — |

### 9.2 Existing efficient calls worth documenting

- TMDB movie/TV with `append_to_response=credits,images,release_dates|content_ratings` saves ~3 round trips already.
- TVDB `/series/{id}/extended` returns companies, networks, season types, artwork, castings, translations pointer in one payload.
- TVDB `GET /updates` is active at startup and on a 12-hour WorkManager cadence, so TVDB freshness is not TTL-only.
- Kitsu advanced detail fetches (castings / staff / productions / relationships) run **in parallel** (`KitsuMetadataService.kt:133-155`, `coroutineScope { async {...} }`).
- TVDB episodes fetched per-season in parallel (`TvdbMetadataService.kt:173-210`), bounded by the shared 5-per-host dispatcher.

---

## 10. Caching layers

Cross-reference `plans/2026-04-21-003-api-network-cache-analysis.md` for the HTTP layer. Below is the **metadata-cache layer** as relevant for this audit.

| Cache | Mechanism | TTL | File |
|---|---|---|---|
| Addon meta (raw) | JSON on disk + in-memory map | undocumented (long-lived, tagged with provider token) | `data/repository/MetaRepositoryImpl.kt` (`metaCache`, `addonMetaCache`) |
| `MetadataDiskCacheStore` — TMDB enrichment | SharedPreferences JSON, schema v4 | 7 d | `core/metadata/MetadataDiskCacheStore.kt:39-148` |
| `MetadataDiskCacheStore` — TVDB enrichment | same | 7 d | same |
| `MetadataDiskCacheStore` — TVDB episodes | same | 24 h | same |
| `MetadataDiskCacheStore` — TVDB reference data | same | 30 d | same |
| `MetadataDiskCacheStore` — TMDB videos | same | 12 h | same |
| In-memory — `TmdbMetadataService` reviews / moreLikeThis | `ConcurrentHashMap` | process lifetime | `core/tmdb/TmdbMetadataService.kt` |
| Request-time only — Kitsu title enrichment | none | no durable cache | `core/anime/KitsuMetadataService.kt:39-70` |
| Request-time only — Kitsu episode enrichment | none | no durable cache | `core/anime/KitsuMetadataService.kt:72-122` |
| In-memory — `KitsuMetadataService` advanced detail | `ConcurrentHashMap` | process lifetime | `core/anime/KitsuMetadataService.kt:133-155` |
| In-memory — `MDBListRepository` | `ConcurrentHashMap` | 30 m; complete seasons 7 d; retry 30 m | `data/repository/MDBListRepository.kt` |
| In-memory — `OmdbEpisodeRatingsRepository` | `ConcurrentHashMap` | 24 h | `data/repository/OmdbEpisodeRatingsRepository.kt` |
| In-memory — `CustomImdbTitleRatingsRepository` | `ConcurrentHashMap` | 7 d | `data/repository/CustomImdbTitleRatingsRepository.kt` |
| In-memory — `CustomImdbEpisodeRatingsRepository` | `ConcurrentHashMap` | 7 d complete / 30 m retry | `data/repository/CustomImdbEpisodeRatingsRepository.kt` |
| In-memory — `SkipIntroRepository` cache / malIdCache / animeSkipShowIdCache | `ConcurrentHashMap` | process lifetime | `data/repository/SkipIntroRepository.kt:107` |
| In-memory — `TraktProgressService` activities (10 s), watched (10 m), history (5 m), per-show progress (5 m), stats (indefinite), optimistic progress (3 m) | `StateFlow` / maps | varies | per 003 analysis |
| Request-time only — `TmdbOrganizationService` | none | no cache | `core/tmdb/TmdbOrganizationService.kt` |
| On-disk — `TraktDiscoverySnapshotStore`, `TraktLibrarySnapshotStore`, `SimklLibrarySnapshotStore`, `MDBListDiscoverySnapshotStore`, `CatalogDiskCacheStore` | per-profile JSON | application lifetime (startup refresh gate 20 s) | per 003 analysis |
| Identity — `TvdbIdentityCacheStore` | JSON on disk | no explicit TTL (schema-gated only) | `data/local/TvdbIdentityCacheStore.kt` |
| Image disk — Coil | directory cache | TTL 10 d via `ImageCacheTtlWorker` | `NexioApplication.kt:69-73`, `workers/ImageCacheTtlWorker.kt` |
| HTTP disk — OkHttp `http_cache` | OkHttp response cache | 50 MB LRU; disabled for trakt, simkl, mdblist, addonCatalog, benchmark | `core/di/NetworkModule.kt:59-83` |

**Cache-key provenance.** `MetadataDiskCacheStore` keys are `{itemType}:{itemId}:{languageTag}:{providerToken}`. `providerToken` changes when the active poster-ratings provider or language changes, giving automatic invalidation for those axes. It does **not** invalidate on e.g. "user connected MDBList" — ratings additions read through the MDBList in-mem layer independently.

**TVDB identity cache note.** `TvdbIdentityCacheStore` has no explicit TTL; entries are schema-gated only. The active `GET /updates` path invalidates TVDB enrichment, episode, and reference-data caches and records merge aliases, but it does **not** invalidate `TvdbIdentityCacheStore`.

**Coil is 200 MB** (`NexioApplication.kt:69-73`), separate from OkHttp's 50 MB.

---

## 11. Gaps and risk register for re-architecture

Non-prescriptive enumeration — what the integration-runtime should eventually address:

1. **No canonical identity**. `MetaPreview.id` can be any of 6+ formats depending on producer. Proposal target: a `ContentKey` type that carries all known identifiers.
2. **Two anime-detection sites with drift risk**. `TvMetadataRouter.firstAnimeId` and `MetaDetailsViewModel.enrichMeta` each parse and interpret anime ids.
3. **Static `anime-id-map.json`** bottlenecks anime routing. New releases not in the asset are silently mis-routed to TVDB/TMDB.
4. **Detail path does not re-apply `PosterRatingsUrlResolver`, and does not copy enrichment posters into final `Meta`**. Home rows get two resolver passes; detail remains base-meta-biased.
5. **Single-URL image storage** forecloses lossless fallback at render time. No way to render a TVDB poster if RPDB fails without a full re-fetch.
6. **Advanced anime detail (characters, staff, productions, relationships) not persisted**. Room/disk store missing.
7. **Reviews and "more like this" not persisted** — in-memory only.
8. **MDBList per-rating-type, per-id loop** — 7×N calls where 1 call / 7 types or 1 call / N ids is possible.
9. **Anime advanced detail fields not surfaced as Meta** — currently kept on a side `KitsuAdvancedAnimeDetail` map consumed only by the ViewModel.
10. **Fanart.tv wired as blueprint only**. Either integrate or delete the asset to avoid confusion.
11. **Top-Posters hardcoded host** `api.top-posters.com` does not match `api.top-streaming.stream` from user's service list. Verify current production host.
12. **CW is single-provider** — Trakt *or* Simkl, never both merged. For households with both connected, half the watch history is invisible at any time.
13. **Skip intervals in-memory only** — cold-start hits ARM + AnimeSkip/IntroDB for every episode seen so far.
14. **Company / network detail has no durable cache** — deep detail views are live, but every navigation re-fetches TMDB organization detail + discover lists.
15. **Trakt CW does not guarantee IMDB ids**; it can carry raw IMDB, `tmdb:`, `trakt:`, or slug fallback. Anime CW can still funnel through ARM to reach Kitsu when no direct Kitsu/MAL/AniList path exists.
16. **Cross-catalog item dedup** is per-row (`"catalog_${row.key()}_${item.id}_${occurrence}"`). Same logical item appears N times if N catalogs list it.
17. **TMDB `append_to_response`** misses `recommendations, reviews, videos, translations` — still N+1 for detail view.

---

## 12. Cross-references

- HTTP / cache / concurrency: `plans/2026-04-21-003-api-network-cache-analysis.md` (authoritative for OkHttp, gates, connection pool).
- Kitsu endpoint surface: `plans/2026-04-21-kitsu-get-endpoint-index.md`.
- Integration-hub target architecture: `plans/2026-04-21-003-api-network-cache-analysis-preanalysis.md` (sketch) and `plans/2026-04-21-004-api-integration-runtime-rearchitecture-plan.md` (plan).
- Kitsu anime detail parity work: `plans/2026-04-21-002-kitsu-anime-detail-parity-plan.md`.

---

## Appendix A — File:line index (for re-verification)

| Topic | Location |
|---|---|
| Modern Home models | `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt:55-97, 360-620` |
| MetaPreview | `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt:6-28` |
| Meta | `app/src/main/java/com/nexio/tv/domain/model/Meta.kt:5-40, 42-61, 104-116` |
| WatchProgress | `app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt:9-83, 66-76` |
| Detail nav route | `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt:13-31` |
| MetaDetailsViewModel identity & enrichment | `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:205-213, 699, 1372-1633, 1379-1398, 1448-1515` |
| TvMetadataRouter | `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt:35-101, 103-148, 254-289` |
| TvdbMetadataService | `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt:1-210, 275-286, 316-392` |
| TvdbIdentityService | `app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt:27-82, 84-104, 158, 191-209` |
| KitsuMetadataService | `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt:30-31, 48, 55-69, 72-104, 124-155, 174-204` |
| AnimeIdMappingService | `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt:9-45` |
| AnimeStremioId | `app/src/main/java/com/nexio/tv/core/anime/AnimeStremioId.kt:13-49` |
| TmdbMetadataService | `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt:106, 132-218, 220-232, 254-333, 386-456, 535, 1142-1156` |
| PosterRatingsUrlResolver | `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt:23-54, 70-96, 107-137` |
| MetadataDiskCacheStore | `app/src/main/java/com/nexio/tv/core/metadata/MetadataDiskCacheStore.kt:39-148` |
| MetaRepositoryImpl | `app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt:43-250` |
| CatalogRepositoryImpl + mapping | `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt:36-142`, `data/mapper/CatalogMapper.kt:8-26`, `data/remote/dto/CatalogResponseDto.kt:6-27` |
| Discovery services | `data/repository/{Trakt,Simkl,Kitsu,Tmdb,MDBList}DiscoveryService.kt` |
| HomeCatalogRefreshCoordinator | `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt:107-187, 542-554, 164, 180` |
| HomeViewModelContinueWatching enrichment | `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:128, 193-200` |
| TrackingProgressService | `app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt:101-138` |
| TraktProgressService CW mapping | `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:2006-2055, 2032-2055` |
| Trakt / Simkl scrobble adapters | `data/repository/trakt/TraktScrobbleMutationAdapter.kt:75-103, 105-139`; `data/repository/simkl/SimklScrobbleMutationAdapter.kt:36-56` |
| Mutation outbox | `data/trakt/outbox/TraktMutationOutboxCoordinator.kt:18-20` |
| Player scrobble firing | `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt:284, 315-331, 357-360, 362-366` |
| SkipIntroRepository | `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt:27-44, 98-365, 107, 125-147, 182-222, 278-320, 340-346` |
| SkipIntroApi | `app/src/main/java/com/nexio/tv/data/remote/api/SkipIntroApi.kt:15-46, 60-95, 145-193` |
| MDBListRepository | `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt:74-95, 106, 176-213, 215-256, 223, 265-270` |
| OmdbEpisodeRatingsRepository | `app/src/main/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepository.kt:39-93` |
| NetworkModule + Coil config | `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt:59-83, 95-101, 125`; `NexioApplication.kt:69-73` |

---

*End of audit.*
