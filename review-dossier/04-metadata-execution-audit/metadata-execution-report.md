# Metadata Execution Audit Bundle

**Verdict:** `PASS`
**Generated:** `1777330706979`
**Schema version:** `1`
**Git SHA:** `4d2d956ec`
**Git worktree:** `DIRTY` (1 changed, 3 untracked)
**Artifact role:** `SIGN_OFF_AGGREGATE`

## Summary
| Metric | Value |
|---|---:|
| Items | 22 |
| Routed items | 20 |
| Network calls | 14 |
| Cache hits | 7 |
| Cache misses | 14 |
| Stale hits | 1 |
| Forbidden overwrites | 1 |
| Policy violations | 0 |

## Scenario `preview-only-disney-mixed`

**Fixture:** `topstreaming_disney_mixed.json`
**Verdict:** `PASS`

### tt26443597 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

### tt27444205 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

## Scenario `disney-mixed-visible-items`

**Fixture:** `topstreaming_disney_mixed.json`
**Verdict:** `PASS`

### tt26443597 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TMDB` | `MOVIE` | `ITEM_TYPE_MOVIE` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TMDB` | `tmdb.movie.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TMDB` | `tmdb.movie.core` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TMDB` | `PRIMARY` | `tt26443597` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TMDB` | `PRIMARY` | `Runtime TMDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/tmdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TMDB` | `en-US` | `en-US` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tmdb.movie.core` | `en-US` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TMDB:tmdb.movie.core:tt26443597:en:policy:2` |

### tt27444205 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TVDB` | `SERIES` | `ITEM_TYPE_SERIES` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TVDB` | `tvdb.series.extended` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TVDB` | `tvdb.series.extended` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TVDB` | `PRIMARY` | `tt27444205` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TVDB` | `PRIMARY` | `Runtime TVDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/tvdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tt27444205:en:policy:2` |

## Scenario `crunchyroll-imdb-anime-detail-core`

**Fixture:** `topstreaming_crunchyroll.json`
**Verdict:** `PASS`

### tt12343534 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `KITSU` | `ANIME` | `ID_MAPPING_TO_KITSU` | `item.id, item.type, AnimeIdentityIndex, IdMappingStore` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `KITSU` | `kitsu.anime.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `KITSU` | `kitsu.anime.core` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `KITSU` | `PRIMARY` | `kitsu:7442` | `canonical_id owned by PRIMARY` | `` |
| `title` | `KITSU` | `PRIMARY` | `Runtime KITSU title` | `title owned by PRIMARY` | `` |
| `poster` | `KITSU` | `PRIMARY` | `https://example.test/kitsu-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `KITSU` | `en` | `en` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `kitsu.anime.core` | `en` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:KITSU:kitsu.anime.core:kitsu:7442:en:policy:2` |

## Scenario `kitsu-prefix-detail-core`

**Fixture:** `anime_kitsu_trending.json`
**Verdict:** `PASS`

### kitsu:7442 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `KITSU` | `ANIME` | `KITSU_PREFIX_DIRECT` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `KITSU` | `kitsu.anime.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `KITSU` | `kitsu.anime.core` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `KITSU` | `PRIMARY` | `kitsu:7442` | `canonical_id owned by PRIMARY` | `` |
| `title` | `KITSU` | `PRIMARY` | `Runtime KITSU title` | `title owned by PRIMARY` | `` |
| `poster` | `KITSU` | `PRIMARY` | `https://example.test/kitsu-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `KITSU` | `en` | `en` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `kitsu.anime.core` | `en` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:KITSU:kitsu.anime.core:kitsu:7442:en:policy:2` |

## Scenario `mal-prefix-detail-core`

**Fixture:** `anime_catalogs_mal.json`
**Verdict:** `PASS`

### mal:21 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `KITSU` | `ANIME` | `ANIME_PREFIX_MAPPED_TO_KITSU` | `item.id, item.type, AnimeIdentityIndex, IdMappingStore` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `KITSU` | `kitsu.anime.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `KITSU` | `kitsu.anime.core` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `KITSU` | `PRIMARY` | `kitsu:1` | `canonical_id owned by PRIMARY` | `` |
| `title` | `KITSU` | `PRIMARY` | `Runtime KITSU title` | `title owned by PRIMARY` | `` |
| `poster` | `KITSU` | `PRIMARY` | `https://example.test/kitsu-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `KITSU` | `en` | `en` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `kitsu.anime.core` | `en` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:KITSU:kitsu.anime.core:kitsu:1:en:policy:2` |

## Scenario `tvdb-series-detail-core`

**Fixture:** `netflix_series_nfx.json`
**Verdict:** `PASS`

### tt14403178 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TVDB` | `SERIES` | `ITEM_TYPE_SERIES` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TVDB` | `tvdb.series.extended` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TVDB` | `tvdb.series.extended` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TVDB` | `PRIMARY` | `tt14403178` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TVDB` | `PRIMARY` | `Runtime TVDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/tvdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tt14403178:en:policy:2` |

## Scenario `provider-native-conflict`

**Fixture:** `provider_native_conflict.json`
**Verdict:** `PASS`

### tmdb:1399 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TVDB` | `SERIES` | `ROUTING_ID_TYPE_CONFLICT` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `true` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TVDB` | `tvdb.series.extended` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TVDB` | `tvdb.series.extended` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TVDB` | `PRIMARY` | `tvdb:1399` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TVDB` | `PRIMARY` | `Runtime TVDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/tvdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tvdb:1399:en:policy:2` |

#### Identity resolution
| Required | Source ID | Target provider | Resolver | API shape | Result | Success |
|---:|---|---|---|---|---|---:|
| `true` | `tmdb:1399` | `TVDB` | `TvdbIdentityResolver` | `tvdb.remoteid.lookup` | `tvdb:1399` | `true` |

## Scenario `premium-artwork-topposters`

**Fixture:** `netflix_movie_nfx.json`
**Verdict:** `PASS`

### tt16431404 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TMDB` | `MOVIE` | `ITEM_TYPE_MOVIE` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TMDB` | `tmdb.movie.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TMDB` | `tmdb.movie.core` | `HIT` | `604800000` | `2592000000` | `primary-metadata-core` |
| `TOP_POSTERS` | `topposters.poster_template` | `HIT` | `86400000` | `604800000` | `poster-generated` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TMDB` | `PRIMARY` | `Runtime TMDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TOP_POSTERS` | `ARTWORK` | `https://example.test/top_posters-poster.jpg` | `poster owned by premium artwork provider TOP_POSTERS` | `TMDB:Premium artwork provider has poster precedence; primary poster retained only as fallback` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TMDB` | `en-US` | `en-US` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tmdb.movie.core` | `en-US` | `LOCALIZED` | `HIT` | `false` | `PRODUCTION_ADAPTER` | `metadata:TMDB:tmdb.movie.core:tt16431404:en:policy:2` |

## Scenario `premium-artwork-rpdb`

**Fixture:** `netflix_movie_nfx.json`
**Verdict:** `PASS`

### tt16431404 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TMDB` | `MOVIE` | `ITEM_TYPE_MOVIE` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TMDB` | `tmdb.movie.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TMDB` | `tmdb.movie.core` | `HIT` | `604800000` | `2592000000` | `primary-metadata-core` |
| `RPDB` | `rpdb.poster_template` | `HIT` | `86400000` | `604800000` | `poster-generated` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TMDB` | `PRIMARY` | `Runtime TMDB title` | `title owned by PRIMARY` | `` |
| `poster` | `RPDB` | `ARTWORK` | `https://example.test/rpdb-poster.jpg` | `poster owned by premium artwork provider RPDB` | `TMDB:Premium artwork provider has poster precedence; primary poster retained only as fallback` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TMDB` | `en-US` | `en-US` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tmdb.movie.core` | `en-US` | `LOCALIZED` | `HIT` | `false` | `PRODUCTION_ADAPTER` | `metadata:TMDB:tmdb.movie.core:tt16431404:en:policy:2` |

## Scenario `continue-watching-local-playback`

**Fixture:** `netflix_series_nfx.json`
**Verdict:** `PASS`

### tt14403178 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TVDB` | `SERIES` | `ITEM_TYPE_SERIES` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TVDB` | `tvdb.series.extended` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TVDB` | `tvdb.series.extended` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TVDB` | `PRIMARY` | `tt14403178` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TVDB` | `PRIMARY` | `Runtime TVDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/tvdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tt14403178:en:policy:2` |

### Continue Watching
| Parent ID | Provider | Routing version | Click-time metadata | Rerouted due to version mismatch |
|---|---|---:|---:|---:|
| `tt14403178` | `TVDB` | `1` | `true` | `false` |

## Scenario `continue-watching-stale-routing-version`

**Fixture:** `netflix_series_nfx.json`
**Verdict:** `PASS`

### tt14403178 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TVDB` | `SERIES` | `ITEM_TYPE_SERIES` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TVDB` | `tvdb.series.extended` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TVDB` | `tvdb.series.extended` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TVDB` | `PRIMARY` | `tt14403178` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TVDB` | `PRIMARY` | `Runtime TVDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/tvdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tt14403178:en:policy:2` |

### Continue Watching
| Parent ID | Provider | Routing version | Click-time metadata | Rerouted due to version mismatch |
|---|---|---:|---:|---:|
| `tt14403178` | `TVDB` | `1` | `true` | `true` |

## Scenario `field-ownership-conflict`

**Fixture:** `marvel_movies.json`
**Verdict:** `PASS`

### tt0036697 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TMDB` | `MOVIE` | `ITEM_TYPE_MOVIE` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TMDB` | `tmdb.movie.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TMDB` | `tmdb.movie.core` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TMDB` | `PRIMARY` | `tt0036697` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TMDB` | `PRIMARY` | `Runtime TMDB title` | `title owned by PRIMARY` | `KITSU:PRIMARY owner selected; secondary title rejected` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/tmdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TMDB` | `en-US` | `en-US` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tmdb.movie.core` | `en-US` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TMDB:tmdb.movie.core:tt0036697:en:policy:2` |

#### Forbidden overwrites
- `title` rejected `KITSU` because Field already owned by PRIMARY; rejected secondary candidate

## Scenario `tmdb-movie-core-warm-cache`

**Fixture:** `netflix_movie_nfx.json`
**Verdict:** `PASS`

### tt16431404 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TMDB` | `MOVIE` | `ITEM_TYPE_MOVIE` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TMDB` | `tmdb.movie.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TMDB` | `tmdb.movie.core` | `HIT` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TMDB` | `PRIMARY` | `Runtime TMDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/tmdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TMDB` | `en-US` | `en-US` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tmdb.movie.core` | `en-US` | `LOCALIZED` | `HIT` | `false` | `PRODUCTION_ADAPTER` | `metadata:TMDB:tmdb.movie.core:tt16431404:en:policy:2` |

## Scenario `tvdb-series-core-warm-cache`

**Fixture:** `netflix_series_nfx.json`
**Verdict:** `PASS`

### tt14403178 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TVDB` | `SERIES` | `ITEM_TYPE_SERIES` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TVDB` | `tvdb.series.extended` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TVDB` | `tvdb.series.extended` | `HIT` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TVDB` | `PRIMARY` | `tt14403178` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TVDB` | `PRIMARY` | `Runtime TVDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/tvdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `HIT` | `false` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tt14403178:en:policy:2` |

## Scenario `kitsu-anime-core-warm-cache`

**Fixture:** `topstreaming_crunchyroll.json`
**Verdict:** `PASS`

### tt12343534 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `KITSU` | `ANIME` | `ID_MAPPING_TO_KITSU` | `item.id, item.type, AnimeIdentityIndex, IdMappingStore` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `KITSU` | `kitsu.anime.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `KITSU` | `kitsu.anime.core` | `HIT` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `KITSU` | `PRIMARY` | `kitsu:7442` | `canonical_id owned by PRIMARY` | `` |
| `title` | `KITSU` | `PRIMARY` | `Runtime KITSU title` | `title owned by PRIMARY` | `` |
| `poster` | `KITSU` | `PRIMARY` | `https://example.test/kitsu-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `KITSU` | `en` | `en` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `kitsu.anime.core` | `en` | `LOCALIZED` | `HIT` | `false` | `PRODUCTION_ADAPTER` | `metadata:KITSU:kitsu.anime.core:kitsu:7442:en:policy:2` |

## Scenario `stale-on-429`

**Fixture:** `netflix_movie_nfx.json`
**Verdict:** `PASS`

### tt16431404 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TMDB` | `MOVIE` | `ITEM_TYPE_MOVIE` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TMDB` | `tmdb.movie.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TMDB` | `tmdb.movie.core` | `STALE_HIT` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TMDB` | `PRIMARY` | `Runtime TMDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/tmdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TMDB` | `en-US` | `en-US` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tmdb.movie.core` | `en-US` | `LOCALIZED` | `STALE_HIT` | `false` | `PRODUCTION_ADAPTER` | `metadata:TMDB:tmdb.movie.core:tt16431404:en:policy:2` |

## Scenario `production-caller-ownership`

**Fixture:** `netflix_movie_nfx.json`
**Verdict:** `PASS`

### tt16431404 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TMDB` | `MOVIE` | `ITEM_TYPE_MOVIE` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TMDB` | `tmdb.movie.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TMDB` | `tmdb.movie.core` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TMDB` | `PRIMARY` | `Runtime TMDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/tmdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TMDB` | `en-US` | `en-US` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tmdb.movie.core` | `en-US` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TMDB:tmdb.movie.core:tt16431404:en:policy:2` |

#### Production caller ownership
| Path | Entrypoint | Facade/repository | Plan runner | FieldResolver | Legacy after facade |
|---|---|---:|---:|---:|---:|
| `home_catalog` | `HomeCatalogRefreshCoordinator` | `true` | `true` | `true` | `false` |
| `detail_screen` | `MetaDetailsViewModel` | `true` | `true` | `true` | `false` |
| `player_start` | `PlayerRuntimeController` | `true` | `true` | `true` | `false` |
| `continue_watching_write` | `ContinueWatchingSnapshotService` | `true` | `true` | `true` | `false` |
| `continue_watching_render` | `HomeViewModelContinueWatching` | `true` | `true` | `true` | `false` |

## Scenario `tvdb-localized-english-fallback`

**Fixture:** `netflix_series_nfx.json`
**Verdict:** `PASS`

### tt14403178 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TVDB` | `SERIES` | `ITEM_TYPE_SERIES` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TVDB` | `tvdb.series.extended` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TVDB` | `tvdb.series.extended` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TVDB` | `PRIMARY` | `tt14403178` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TVDB` | `PRIMARY` | `Runtime TVDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/tvdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `nld` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `nld` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tt14403178:nl-NL:policy:2` |
| `tvdb.series.translation` | `eng` | `LANGUAGE_FALLBACK` | `HIT` | `false` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tt14403178:nl-NL:policy:2:fallback:eng` |

## Scenario `tmdb-localized-english-fallback`

**Fixture:** `netflix_movie_nfx.json`
**Verdict:** `PASS`

### tt16431404 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `TMDB` | `MOVIE` | `ITEM_TYPE_MOVIE` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TMDB` | `tmdb.movie.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `TMDB` | `tmdb.movie.core` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TMDB` | `PRIMARY` | `Runtime TMDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/tmdb-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TMDB` | `nl-NL` | `en-US` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tmdb.movie.core` | `nl-NL` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TMDB:tmdb.movie.core:tt16431404:nl-NL:policy:2` |
| `tmdb.movie.core` | `en-US` | `LANGUAGE_FALLBACK` | `HIT` | `false` | `PRODUCTION_ADAPTER` | `metadata:TMDB:tmdb.movie.core:tt16431404:nl-NL:policy:2:fallback:en-US` |

## Scenario `kitsu-localized-field-fallback`

**Fixture:** `anime_kitsu_trending.json`
**Verdict:** `PASS`

### kitsu:7442 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `ADDON_META_PREVIEW` | `false` | `false` |

#### Routing
| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |
|---|---|---|---|---|---:|---:|
| `KITSU` | `ANIME` | `KITSU_PREFIX_DIRECT` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `false` | `true` |

#### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `KITSU` | `kitsu.anime.core` | `USER_VISIBLE` | `provider-metadata` |

#### Cache decisions
| Provider | API shape | Decision | TTL | Stale | Reason |
|---|---|---|---:|---:|---|
| `KITSU` | `kitsu.anime.core` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` | `primary-metadata-core` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `KITSU` | `PRIMARY` | `kitsu:7442` | `canonical_id owned by PRIMARY` | `` |
| `title` | `KITSU` | `PRIMARY` | `Runtime KITSU title` | `title owned by PRIMARY` | `` |
| `poster` | `KITSU` | `PRIMARY` | `https://example.test/kitsu-poster.jpg` | `poster owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `KITSU` | `nl` | `en` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `kitsu.anime.core` | `nl` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:KITSU:kitsu.anime.core:kitsu:7442:nl-NL:policy:2` |
| `kitsu.anime.core` | `en` | `LANGUAGE_FALLBACK` | `HIT` | `false` | `PRODUCTION_ADAPTER` | `metadata:KITSU:kitsu.anime.core:kitsu:7442:nl-NL:policy:2:fallback:en` |

