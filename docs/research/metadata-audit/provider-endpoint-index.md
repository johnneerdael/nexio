# Metadata Provider Endpoint Index

## How To Read This Appendix

- This appendix is limited to endpoints that materially affect metadata population, identity normalization, reviews, recommendations, trailers, posters, or skip-segment routing.
- `Interface or Blueprint` names the checked-in Retrofit interface or checked-in provider blueprint used as the evidence source.
- `Current caller(s)` names the concrete Android service or repository that uses the route today.
- `Bulk or single-item` answers the rearchitecture question directly:
  - `single-item`
  - `season batch`
  - `list batch`
  - `paginated list`
  - `one-call multi-field enrichment`
  - `many-calls-for-one-dataset`

## Route Shape Corrections

These are the disputed route shapes that were rechecked against the Android Retrofit interfaces first, then the checked-in provider blueprints.

| Provider area | Android Retrofit route currently called | Provider blueprint or API route | Notes on naming mismatch |
|---|---|---|---|
| TVDB remote id lookup | `GET /search/remoteid/{remoteId}` | `apiblueprints/tvdb.yml` also documents `GET /search/remoteid/{remoteId}` | no mismatch in the checked-in sources |
| Kitsu castings for anime detail | top-level `GET /castings?filter[mediaId]=...&include=person,character` | `kitsu.apib` documents top-level `GET /castings` and also anime relationship links under `/anime/{id}/castings` | Android calls the filtered top-level collection, not the nested relationship path |
| Kitsu anime staff | `GET /anime/{id}/anime-staff` | `kitsu.apib` documents `GET /anime-staff` and the nested related link `/anime/{id}/anime-staff` | Android uses the nested related path |
| Kitsu anime productions | `GET /anime/{id}/anime-productions` | `kitsu.apib` documents `GET /anime-productions` and the nested related link `/anime/{id}/anime-productions` | Android uses the nested related path |
| Kitsu media relationships | `GET /anime/{id}/media-relationships` | `kitsu.apib` documents `GET /media-relationships` and the nested related link `/anime/{id}/media-relationships` | Android uses the nested related path |

## TMDB

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|
| `TmdbApi.kt` | `GET search/movie` | `TmdbDiscoveryService.search` | built-in TMDB movie search row | paginated list | title search only; row ids become IMDb when possible, else `tmdb:{id}` |
| `TmdbApi.kt` | `GET search/tv` | `TmdbDiscoveryService.search` | built-in TMDB TV search row | paginated list | same canonical-id rule as movie search |
| `TmdbApi.kt` | `GET trending/movie/{time_window}` | `TmdbDiscoveryService.fetchCatalog` | built-in TMDB home row | paginated list | home discovery source |
| `TmdbApi.kt` | `GET trending/tv/{time_window}` | `TmdbDiscoveryService.fetchCatalog` | built-in TMDB home row | paginated list | home discovery source |
| `TmdbApi.kt` | `GET movie/popular` | `TmdbDiscoveryService.fetchCatalog` | built-in TMDB home row | paginated list | home discovery source |
| `TmdbApi.kt` | `GET tv/popular` | `TmdbDiscoveryService.fetchCatalog` | built-in TMDB home row | paginated list | home discovery source |
| `TmdbApi.kt` | `GET discover/movie` | `TmdbDiscoveryService.fetchCatalog`, `TmdbOrganizationService` | built-in TMDB rows, movie-company detail titles | paginated list | used both for stock rows and company-title discovery |
| `TmdbApi.kt` | `GET discover/tv` | `TmdbDiscoveryService.fetchCatalog`, `TmdbOrganizationService` | built-in TMDB rows, company/network detail titles | paginated list | also used for network/company title discovery |
| `TmdbApi.kt` | `GET find/{external_id}` | `TmdbService.ensureTmdbId` | TMDB id translation | single-item | external id lookup bridge |
| `TmdbApi.kt` | `GET movie/{movie_id}/external_ids` | `TmdbService.tmdbToImdb` | IMDb bridge for movie ids | single-item | key for custom IMDb and OMDb fallback paths |
| `TmdbApi.kt` | `GET tv/{tv_id}/external_ids` | `TmdbService.tmdbToImdb` | IMDb bridge for show ids | single-item | key for custom IMDb and OMDb fallback paths |
| `TmdbApi.kt` | `GET movie/{movie_id}` with `append_to_response=credits,images,release_dates` | `TmdbMetadataService.fetchEnrichment` | movie title enrichment | one-call multi-field enrichment | one request delivers core details, credits, images, and release ratings |
| `TmdbApi.kt` | `GET tv/{tv_id}` with `append_to_response=credits,images,content_ratings` | `TmdbMetadataService.fetchEnrichment` | TV title enrichment | one-call multi-field enrichment | one request delivers core details, credits, images, and content ratings |
| `TmdbApi.kt` | `GET tv/{tv_id}/season/{season_number}` | `TmdbMetadataService.fetchSeasonEpisodes`, `TmdbMetadataService.fetchEpisodeEnrichment` | episode titles, overviews, thumbnails, runtime, TMDB episode ratings | season batch | season response is reused for both metadata and ratings |
| `TmdbApi.kt` | `GET movie/{movie_id}/videos` | `TrailerService` | movie trailer and teaser availability | single-item | cached in `MetadataDiskCacheStore` |
| `TmdbApi.kt` | `GET tv/{tv_id}/videos` | `TrailerService` | TV title trailer fallback | single-item | only used after TVDB and Streailer checks |
| `TmdbApi.kt` | `GET tv/{tv_id}/season/{season_number}/videos` | `TrailerService` | season trailer and recap availability | season batch | cached in `MetadataDiskCacheStore` |
| `TmdbApi.kt` | `GET movie/{movie_id}/recommendations` | `TmdbMetadataService.fetchMoreLikeThis` | movie `More like this` | paginated list | app keeps top-ranked localized subset |
| `TmdbApi.kt` | `GET tv/{tv_id}/recommendations` | `TmdbMetadataService.fetchMoreLikeThis` | TV `More like this` | paginated list | app keeps top-ranked localized subset |
| `TmdbApi.kt` | `GET movie/{movie_id}/reviews` | `TmdbMetadataService.fetchReviews` | movie reviews | paginated list | merged with Trakt comments on detail |
| `TmdbApi.kt` | `GET tv/{tv_id}/reviews` | `TmdbMetadataService.fetchReviews` | TV reviews | paginated list | merged with Trakt comments on detail |
| `TmdbApi.kt` | `GET collection/{collection_id}` | `TmdbMetadataService.fetchMovieCollection` | movie collection rail on detail | list batch | used for movie collections only |
| `TmdbApi.kt` | `GET person/{person_id}` | `TmdbMetadataService.fetchPersonDetail` | actor/person biography, birthday, birthplace, photo | single-item | fetched in parallel with combined credits |
| `TmdbApi.kt` | `GET person/{person_id}/combined_credits` | `TmdbMetadataService.fetchPersonDetail` | actor/person filmography | list batch | combines movie and TV credits |
| `TmdbApi.kt` | `GET search/person` | `TmdbMetadataService.findPersonIdByExactName` | Kitsu cast bridge into TMDB person ids | single-item | exact-name bridge only |
| `TmdbApi.kt` | `GET company/{company_id}` | `TmdbOrganizationService.fetchOrganizationDetail` | production-company detail | single-item | includes description, headquarters, homepage, origin country |
| `TmdbApi.kt` | `GET network/{network_id}` | `TmdbOrganizationService.fetchOrganizationDetail` | network detail | single-item | description is not supplied in the current TMDB network model |
| `TmdbApi.kt` | `GET search/company` | `TmdbMetadataService.findCompanyIdByExactName` | Kitsu company bridge into TMDB ids | single-item | exact-name bridge only |
| `apiblueprints/tmdb.json` | TMDB OpenAPI surface | all TMDB callers above | contract cross-check | n/a | used to confirm that current Android usage already takes advantage of TMDB bulk enrichment routes |

## TVDB

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|
| `TvdbApi.kt` | `POST login` | `TvdbAuthService` | TVDB bearer token for every protected route | single-item | prerequisite for TV metadata, people, and trailer paths |
| `TvdbApi.kt` | `GET search/remoteid/{remoteId}` | `TvdbIdentityService.resolveSeriesByRemoteId` | TVDB series identity resolution | single-item | key bridge from IMDb, TMDB, and raw ids |
| `TvdbApi.kt` | `GET search` | `TvdbIdentityService.resolveSeriesByRemoteId` fallback | TVDB series identity resolution | single-item | title-search fallback when remote-id search is insufficient |
| `TvdbApi.kt` | `GET series/{id}` | `TvdbIdentityService.resolveSeriesByTvdbId` fallback | TVDB series identity resolution | single-item | base record fallback when extended lookup fails |
| `TvdbApi.kt` | `GET series/{id}/extended` | `TvdbIdentityService`, `TvdbMetadataService`, `TvdbTrailerResolver` | TV title enrichment, remote ids, cast/company/network data, trailer candidates | one-call multi-field enrichment | same record powers both title enrichment and TVDB trailer classification |
| `TvdbApi.kt` | `GET series/{id}/episodes/{seasonType}` | `TvdbMetadataService.fetchSeasonEpisodes` | TV season episode metadata | season batch | primary episode list route |
| `TvdbApi.kt` | `GET series/{id}/episodes/{seasonType}/{language}` | `TvdbMetadataService.fetchSeasonEpisodes` | translated TV season episode metadata | season batch | used when language is not English |
| `TvdbApi.kt` | `GET episodes/{id}/translations/{language}` | `TvdbMetadataService.fetchTranslatedEpisodeOverviews` | localized episode overviews | single-item | many-calls-for-one-dataset when per-episode translations are missing |
| `TvdbApi.kt` | `GET series/{id}/translations/{language}` | `TvdbMetadataService` | localized series title and overview | single-item | supplements the extended series payload |
| `TvdbApi.kt` | `GET updates` | `TvdbUpdateCoordinator`, `TvdbUpdateProcessor`, `NexioApplication`, `TvdbUpdateWorker` | TVDB cache invalidation feed | paginated list | active at app startup and scheduled periodically through WorkManager; invalidates TVDB series, episode, and reference caches and records merge aliases, but does not invalidate `TvdbIdentityCacheStore` |
| `TvdbApi.kt` | `GET artwork/types` | `TvdbReferenceDataService` | reference labels | list batch | cached in `MetadataDiskCacheStore` |
| `TvdbApi.kt` | `GET artwork/statuses` | `TvdbReferenceDataService` | reference labels | list batch | cached in `MetadataDiskCacheStore` |
| `TvdbApi.kt` | `GET genres` | `TvdbReferenceDataService` | genre labels | list batch | cached in `MetadataDiskCacheStore` |
| `TvdbApi.kt` | `GET languages` | `TvdbReferenceDataService` | language labels | list batch | cached in `MetadataDiskCacheStore` |
| `TvdbApi.kt` | `GET series/statuses` | `TvdbReferenceDataService` | series status labels | list batch | cached in `MetadataDiskCacheStore` |
| `TvdbApi.kt` | `GET content/ratings` | `TvdbReferenceDataService` | age-rating labels | list batch | cached in `MetadataDiskCacheStore` |
| `TvdbApi.kt` | `GET seasons/types` | `TvdbReferenceDataService` | season-order labels | list batch | cached in `MetadataDiskCacheStore` |
| `TvdbApi.kt` | `GET sources/types` | `TvdbReferenceDataService` | remote-id source labels | list batch | cached in `MetadataDiskCacheStore` |
| `TvdbApi.kt` | `GET entities` | `TvdbReferenceDataService` | entity-type labels | list batch | cached in `MetadataDiskCacheStore` |
| `TvdbApi.kt` | `GET companies/types` | `TvdbReferenceDataService` | company-type labels | list batch | cached in `MetadataDiskCacheStore` |
| `TvdbApi.kt` | `GET people/{id}/extended` | `TvdbPersonService.fetchPersonDetail` | TVDB person detail and filmography | one-call multi-field enrichment | used only when cast members carry TVDB people ids |
| `apiblueprints/tvdb.yml` | TVDB OpenAPI surface | all TVDB callers above | contract cross-check | n/a | confirms the reference-data and update feeds used by the Android layer |

## Kitsu

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|
| `KitsuApi.kt` | `GET trending/anime` | `KitsuDiscoveryService.fetchCatalog` | built-in Kitsu home row | paginated list | powers the built-in trending anime row |
| `KitsuApi.kt` | `GET anime` | `KitsuDiscoveryService.fetchCatalog` | built-in Kitsu home rows by sort and category | paginated list | used for highest-rated, popular, and genre-scoped anime rows |
| `KitsuApi.kt` | `GET anime/{id}` | `KitsuMetadataService.fetchEnrichment` | anime title enrichment | single-item | public metadata route with optional auth enhancement |
| `KitsuApi.kt` | `GET anime/{id}/episodes` | `KitsuMetadataService.fetchEpisodeEnrichment`, `KitsuMetadataService.fetchSeasonEpisodes` | anime episode titles, descriptions, thumbnails, air dates, runtime | paginated list | current code walks pages up to a capped max |
| `KitsuApi.kt` | `GET castings` with include graph | `KitsuMetadataService.fetchAdvancedDetail` | anime characters, voice actors, actor photos | list batch | current advanced anime cast path |
| `KitsuApi.kt` | `GET anime/{id}/anime-staff` | `KitsuMetadataService.fetchAdvancedDetail` | anime staff | list batch | current advanced anime staff path |
| `KitsuApi.kt` | `GET anime/{id}/anime-productions` | `KitsuMetadataService.fetchAdvancedDetail` | anime production companies | list batch | current advanced anime productions path |
| `KitsuApi.kt` | `GET anime/{id}/media-relationships` | `KitsuMetadataService.fetchAdvancedDetail` | related anime titles | list batch | current source for anime related titles |
| `KitsuApi.kt` | `GET anime/{id}/installments` | not currently consumed in-product | franchise/installment ordering | paginated list | explicitly avoided in current code because live validation was unreliable |
| `KitsuApi.kt` | `GET characters` | advanced include graph only | character metadata | list batch | accessed indirectly through casting includes |
| `KitsuApi.kt` | `GET people` | advanced include graph only | person metadata | list batch | accessed indirectly through casting and staff includes |
| `KitsuApi.kt` | `GET producers` | advanced include graph only | production-company metadata | list batch | accessed indirectly through anime productions includes |
| `KitsuApi.kt` | `GET franchises` | not currently consumed in-product | franchise grouping | list batch | no current UI surface |
| `apiblueprints/kitsu.apib` | checked-in API blueprint | all Kitsu callers above | contract cross-check | n/a | confirms that many more public metadata routes exist than Nexio currently uses |

## MDBList

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|
| `MDBListApi.kt` | `GET user` | `MDBListDiscoveryService`, settings validation flows | account validation and list access | single-item | establishes whether MDBList is usable |
| `MDBListApi.kt` | `POST rating/{mediaType}/{ratingType}` | `MDBListRepository.fetchRatings`, `MDBListRepository.fetchEpisodeRatingsForSeason` | title IMDb/TMDb/Trakt/Letterboxd/Tomatoes/Audience/Metacritic ratings and implemented-but-inactive episode ratings | list batch | body accepts a list of ids, but current code usually sends one IMDb id and performs many calls for one title across rating types; episode-rating calls are implemented in the repository but not used by `EpisodeRatingsSelectionRepository` |
| `MDBListApi.kt` | `GET @Url` | `MDBListDiscoveryService` | personal list fetch, top-list fetch, list-item fetch | paginated list | raw passthrough route used for discovery-list endpoints |
| `MDBListApi.kt` | `GET @Url` with `@QueryMap` | `MDBListDiscoveryService` | list-item fetch with query parameters | paginated list | raw passthrough route used when MDBList expects custom query shapes |
| `apiblueprints/mdblist.apib` | checked-in API blueprint | all MDBList callers above | contract cross-check | n/a | highlights where Nexio could collapse multiple rating fields into fewer requests in a future redesign |

MDBList episode ratings:

- Endpoint implemented: yes
- Current caller active: no direct UI caller; repository-only implementation
- Included in final episode badge selection: no
- Priority relative to custom IMDb, TMDB, OMDb: currently outside the active selection path
- Cache key and TTL: `show:<cacheNamespace>:<season>:<apiKeyHash>` with 7-day TTL when complete and 30-minute retry TTL when partial

## OMDb

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|
| `OmdbApi.kt` | `GET /?apikey=&i=<seriesImdbId>&season=<n>` | `OmdbEpisodeRatingsRepository.getSeasonRatings` | episode IMDb ratings per season | season batch | one OMDb season response yields many episode ratings; cache 24 h in-memory |

## Custom IMDb

Nexio-operated backend at `BuildConfig.IMDB_API_URL` (not the official IMDb API). Authenticated with `X-API-Key` header from `BuildConfig.IMDB_API_KEY`. Gated at build time only — the path is active whenever both BuildConfig values are non-blank.

| Interface | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|
| `CustomImdbClient.kt` | `POST /v1/ratings/bulk` with body `{"identifiers":["tt…", "tt…", …]}` | `CustomImdbTitleRatingsRepository.getTitleRating` via `TitleRatingOverrideRepository` | title IMDb rating (averageRating, numVotes) | `list batch` — accepts many IMDb ids in one call | first-priority title rating source; overrides MDBList and base-meta IMDb when non-null |
| `CustomImdbClient.kt` | `GET /v1/ratings/{tconst}?episodes=true` | `CustomImdbEpisodeRatingsRepository.getEpisodeRatingsForMeta` via `EpisodeRatingsSelectionRepository` | per-episode IMDb ratings for an entire series | `single-item` — one call returns every episode across every season | first-priority episode rating source; when active, bypasses TMDB+OMDb merge entirely |
| `CustomImdbClient.kt` | `GET /v1/meta/stats` | `CustomImdbClient` validation / health check | provider reachability | `single-item` | used only to confirm the backend is alive; does not contribute metadata |

Selection and caching:

| Question | Answer |
|---|---|
| Active title-rating caller | `TitleRatingOverrideRepository.enrichMeta` at `data/repository/TitleRatingOverrideRepository.kt:15-28` — priority Custom IMDb → MDBList → base rating |
| Active episode-rating caller | `EpisodeRatingsSelectionRepository.getEpisodeRatings` at `data/repository/EpisodeRatingsSelectionRepository.kt:33-71` — when `customImdbActiveProvider()` is true, Custom IMDb is returned exclusively; otherwise TMDB `voteAverage` + OMDb merged via `resolveEpisodeRatings` |
| Title cache | `ConcurrentHashMap<String, CacheEntry>` in `CustomImdbTitleRatingsRepository`; TTL 7 days (`7L * 24L * 60L * 60L * 1000L` ms) |
| Episode cache | `ConcurrentHashMap<String, CacheEntry>` in `CustomImdbEpisodeRatingsRepository`; TTL 7 days on complete episode sets; 30-minute retry TTL on partial/failed sets |
| Disk persistence | none — both caches are process-memory only |
| Feature flag / runtime toggle | none — gated by `BuildConfig.IMDB_API_URL.isNotBlank() && BuildConfig.IMDB_API_KEY.isNotBlank()` at build time |
| Batch-capable routes | yes on title ratings (`POST /v1/ratings/bulk`); episode endpoint returns a whole series in one call and so is implicitly bulk per series |

Consequence for rearchitecture: Custom IMDb is the only bulk-capable rating endpoint actually used in production today. MDBList's batch-capable `POST /rating/{mediaType}/{ratingType}` is invoked with a one-element id list; OMDb is single-call-per-season. Any future ratings pipeline must treat Custom IMDb as the canonical first-priority path, not a peer.

## Trakt

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|
| `TraktApi.kt` | `GET recommendations/{type}` | `TraktDiscoveryService` | built-in recommendation rows | paginated list | feeds synthetic movie and show rows |
| `TraktApi.kt` | `GET calendars/my/shows/{start_date}/{days}` | `TraktDiscoveryService` | built-in calendar row | paginated list | feeds show-calendar row and next-up context |
| `TraktApi.kt` | `GET movies/trending` | `TraktDiscoveryService` | built-in trending movie row | paginated list | synthetic home row |
| `TraktApi.kt` | `GET shows/trending` | `TraktDiscoveryService` | built-in trending show row | paginated list | synthetic home row |
| `TraktApi.kt` | `GET movies/popular` | `TraktDiscoveryService` | built-in popular movie row | paginated list | synthetic home row |
| `TraktApi.kt` | `GET shows/popular` | `TraktDiscoveryService` | built-in popular show row | paginated list | synthetic home row |
| `TraktApi.kt` | `GET lists/popular` | `TraktDiscoveryService` | selectable popular Trakt lists | paginated list | list inventory for synthetic rows |
| `TraktApi.kt` | `GET users/{id}/lists` | `TraktDiscoveryService`, library/list management | personal list inventory | paginated list | list inventory for synthetic rows |
| `TraktApi.kt` | `GET users/{id}/lists/{list_id}/items/{type}` | `TraktDiscoveryService`, library/list management | custom-list row items | list batch | source for synthetic Trakt custom rows |
| `TraktApi.kt` | `GET sync/playback/{type}` | continue-watching/tracking services | playback progress and resume rows | paginated list | feeds continue-watching identity |
| `TraktApi.kt` | `GET shows/{id}/progress/watched` | tracking services | next-up and watched-episode context | single-item | show-level watch context |
| `TraktApi.kt` | `GET sync/history/episodes` | tracking services | recent episode activity | paginated list | next-up recency and watch history |
| `TraktApi.kt` | `GET movies/{id}/comments/{sort}` | `MetaDetailsViewModel.fetchTraktReviewsPage` | movie reviews/comments | paginated list | merged with TMDB reviews |
| `TraktApi.kt` | `GET shows/{id}/comments/{sort}` | `MetaDetailsViewModel.fetchTraktReviewsPage` | show reviews/comments | paginated list | merged with TMDB reviews |
| `apiblueprints/trakt.apib` | checked-in API blueprint | all Trakt callers above | contract cross-check | n/a | useful for future pruning of duplicate show/movie review and recommendation lookups |

## Simkl

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|
| `SimklApi.kt` | `GET sync/all-items/{type}/{status}` | `SimklLibraryService`, `SimklDiscoveryService` | SIMKL library and discovery inventory | paginated list | raw JSON route used for snapshot building |
| `SimklApi.kt` | `GET sync/all-items/{type}/` | `SimklLibraryService`, `SimklDiscoveryService` | SIMKL library and discovery inventory | paginated list | raw JSON route used for snapshot building |
| `SimklApi.kt` | `GET sync/all-items/` | `SimklLibraryService`, `SimklDiscoveryService` | SIMKL library and discovery inventory | paginated list | raw JSON route used for full sync |
| `SimklApi.kt` | `GET sync/playback/{type}` | tracking services | SIMKL playback progress | paginated list | contributes to continue-watching state |
| `SimklDiscoveryService` direct GET fallback | `https://api.simkl.com/{tv|anime|movies}/{simklId}?extended=full` | `SimklDiscoveryService.fetchExternalContentId` | canonical external id bridge for SIMKL discovery items | single-item | used when SIMKL discovery items lack stable IMDb/TMDB ids |
| `SimklApi.kt` | `POST users/settings` | auth/settings services | account settings | single-item | not a metadata row source |
| `SimklApi.kt` | `POST sync/activities` | sync services | freshness checkpoint | single-item | used to decide refresh behavior |
| `apiblueprints/simkl.apib` | checked-in API blueprint | all SIMKL callers above | contract cross-check | n/a | useful for future consolidation of raw JSON list calls |

## RPDB And Top-Posters

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|
| `PosterRatingsApi.kt` | `GET {apiKey}/isValid` | settings validation flows | RPDB key validation | single-item | not used to fetch poster art itself |
| `PosterRatingsApi.kt` | `GET auth/verify/{apiKey}` | settings validation flows | Top-Posters key validation | single-item | not used to fetch poster art itself |
| `PosterRatingsUrlResolver.kt` | `https://api.ratingposterdb.com/<key>/<idType>/poster-default/<id>.jpg` | `CatalogRepositoryImpl`, built-in discovery services, `MetaRepositoryImpl` | poster rewrite | single-item url template | RPDB only supports IMDb, TMDB, and TVDB ids in current resolver |
| `PosterRatingsUrlResolver.kt` | `https://api.top-posters.com/<key>/<provider>/poster/<id>.jpg` | `CatalogRepositoryImpl`, built-in discovery services, `MetaRepositoryImpl` | poster rewrite | single-item url template | Top-Posters supports IMDb, TMDB, TVDB, Trakt, MAL, Kitsu, AniList, and AniDB ids |
| `apiblueprints/rpdb.apib` and `apiblueprints/topposters.json` | checked-in provider references | poster validation and rewrite code | contract cross-check | n/a | useful for verifying the poster-provider-specific URL vocabulary |

## Fanart.tv

Status: blueprint present, no active Android caller found.

Impact: Fanart.tv should not be considered a current poster or artwork provider in Nexio's Android metadata flow.

Required future decision: either wire Fanart.tv as an artwork provider or remove it from current-state provider inventories.

## TheIntroDB, AniSkip, AnimeSkip, And ARM

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|
| `SkipIntroApi.kt` / `IntroDbApi` | `GET media` | `SkipIntroRepository.fetchFromTheIntroDb` | intro, recap, credits, preview segments for non-anime | single-item | default skip provider when the route is not anime-primary |
| `SkipIntroApi.kt` / `AniSkipApi` | `GET skip-times/{malId}/{episode}` | `SkipIntroRepository.fetchFromAniSkip` | anime skip intervals by MAL id | single-item | current fast path when MAL is available |
| `SkipIntroApi.kt` / `AnimeSkipApi` | `POST graphql` | `SkipIntroRepository.fetchFromAnimeSkip` | anime skip intervals by AniList/show-id graph | many-calls-for-one-dataset | one call resolves show ids, another fetches episode timestamps |
| `SkipIntroApi.kt` / `ArmApi` | `GET imdb` | `SkipIntroRepository.resolveMalId`, `SkipIntroRepository.resolveAllAnilistIdsFromImdb` | IMDb -> MAL or AniList bridge | single-item | bridge only, not a metadata surface by itself |
| `SkipIntroApi.kt` / `ArmApi` | `GET ids` | `SkipIntroRepository.getSkipIntervalsForKitsu`, `SkipIntroRepository.getSkipIntervalsForMal` | MAL/Kitsu/AniList/IMDb bridge | single-item | bridge only, used to move to AniSkip or AnimeSkip |
| `apiblueprints/tidb.yaml` | checked-in TheIntroDB reference | `SkipIntroRepository` | contract cross-check for TheIntroDB | n/a | useful for confirming the segment payload families |
