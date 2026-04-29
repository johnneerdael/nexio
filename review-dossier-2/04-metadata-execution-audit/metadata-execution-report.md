# Metadata Execution Audit Bundle

**Verdict:** `PASS`
**Generated:** `1777469835389`
**Schema version:** `1`
**Git SHA:** `9f0555a5a`
**Git worktree:** `DIRTY` (1 changed, 2 untracked)
**Artifact role:** `SIGN_OFF_AGGREGATE`

## Summary
| Metric | Value |
|---|---:|
| Items | 30 |
| Routed items | 23 |
| Network calls | 17 |
| Cache hits | 7 |
| Cache misses | 17 |
| Stale hits | 1 |
| Forbidden overwrites | 5 |
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
| `title` | `TMDB` | `PRIMARY` | `Disney Movie` | `title owned by PRIMARY` | `` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/disney-movie.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `TMDB` | `PRIMARY` | `tt26443597` | `canonical_id owned by PRIMARY` | `` |

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
| `title` | `TVDB` | `PRIMARY` | `Disney Series` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/disney-series.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `TVDB` | `PRIMARY` | `tvdb:tt27444205` | `canonical_id owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tvdb:tt27444205:en:policy:2` |

#### Identity resolution
| Required | Source ID | Target provider | Resolver | API shape | Result | Success |
|---:|---|---|---|---|---|---:|
| `true` | `tt27444205` | `TVDB` | `TvdbIdentityResolver` | `tvdb.remoteid.lookup` | `tvdb:tt27444205` | `true` |

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
| `title` | `KITSU` | `PRIMARY` | `Crunchyroll IMDb Anime` | `title owned by PRIMARY` | `` |
| `poster` | `KITSU` | `PRIMARY` | `https://example.test/crunchyroll.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `KITSU` | `PRIMARY` | `kitsu:7442` | `canonical_id owned by PRIMARY` | `` |

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
| `title` | `KITSU` | `PRIMARY` | `Kitsu Anime` | `title owned by PRIMARY` | `` |
| `poster` | `KITSU` | `PRIMARY` | `https://example.test/kitsu.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `KITSU` | `PRIMARY` | `kitsu:7442` | `canonical_id owned by PRIMARY` | `` |

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
| `title` | `KITSU` | `PRIMARY` | `MAL Anime` | `title owned by PRIMARY` | `` |
| `poster` | `KITSU` | `PRIMARY` | `https://example.test/mal.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `KITSU` | `PRIMARY` | `kitsu:1` | `canonical_id owned by PRIMARY` | `` |

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
| `title` | `TVDB` | `PRIMARY` | `Netflix Series` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/netflix-series.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `TVDB` | `PRIMARY` | `tvdb:tt14403178` | `canonical_id owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tvdb:tt14403178:en:policy:2` |

#### Identity resolution
| Required | Source ID | Target provider | Resolver | API shape | Result | Success |
|---:|---|---|---|---|---|---:|
| `true` | `tt14403178` | `TVDB` | `TvdbIdentityResolver` | `tvdb.remoteid.lookup` | `tvdb:tt14403178` | `true` |

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
| `title` | `TVDB` | `PRIMARY` | `TMDB Series Conflict` | `title owned by PRIMARY` | `` |
| `overview` | `TVDB` | `PRIMARY` | `A provider-native TMDB id paired with series type to prove identity resolution before TVDB execution.` | `overview owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/provider-native-conflict.jpg` | `poster owned by PRIMARY` | `` |
| `backdrop` | `TVDB` | `PRIMARY` | `https://example.test/provider-native-conflict-bg.jpg` | `backdrop owned by PRIMARY` | `` |
| `canonical_id` | `TVDB` | `PRIMARY` | `tvdb:1399` | `canonical_id owned by PRIMARY` | `` |

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
| `title` | `TMDB` | `PRIMARY` | `Netflix Movie` | `title owned by PRIMARY` | `` |
| `poster` | `TOP_POSTERS` | `ARTWORK` | `https://example.test/top_posters-poster.jpg` | `poster owned by premium artwork provider TOP_POSTERS` | `TMDB: Premium artwork provider has poster precedence; primary poster retained only as fallback` |
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |

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
| `title` | `TMDB` | `PRIMARY` | `Netflix Movie` | `title owned by PRIMARY` | `` |
| `poster` | `RPDB` | `ARTWORK` | `https://example.test/rpdb-poster.jpg` | `poster owned by premium artwork provider RPDB` | `TMDB: Premium artwork provider has poster precedence; primary poster retained only as fallback` |
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |

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
| `title` | `TVDB` | `PRIMARY` | `Netflix Series` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/netflix-series.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `TVDB` | `PRIMARY` | `tvdb:tt14403178` | `canonical_id owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tvdb:tt14403178:en:policy:2` |

#### Identity resolution
| Required | Source ID | Target provider | Resolver | API shape | Result | Success |
|---:|---|---|---|---|---|---:|
| `true` | `tt14403178` | `TVDB` | `TvdbIdentityResolver` | `tvdb.remoteid.lookup` | `tvdb:tt14403178` | `true` |

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
| `title` | `TVDB` | `PRIMARY` | `Netflix Series` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/netflix-series.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `TVDB` | `PRIMARY` | `tvdb:tt14403178` | `canonical_id owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tvdb:tt14403178:en:policy:2` |

#### Identity resolution
| Required | Source ID | Target provider | Resolver | API shape | Result | Success |
|---:|---|---|---|---|---|---:|
| `true` | `tt14403178` | `TVDB` | `TvdbIdentityResolver` | `tvdb.remoteid.lookup` | `tvdb:tt14403178` | `true` |

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
| `title` | `TMDB` | `PRIMARY` | `Captain America` | `title owned by PRIMARY` | `KITSU: PRIMARY owner selected; secondary title rejected` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/captain-america.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `TMDB` | `PRIMARY` | `tt0036697` | `canonical_id owned by PRIMARY` | `` |

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
| `title` | `TMDB` | `PRIMARY` | `Netflix Movie` | `title owned by PRIMARY` | `` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/netflix-movie.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |

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
| `TVDB` | `SERIES` | `ROUTING_ID_TYPE_CONFLICT` | `item.id, item.type` | `catalog.type, catalog.id, addon.name, source.name, genre, animeType, links, trend, popularity` | `true` | `true` |

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
| `title` | `TVDB` | `PRIMARY` | `Netflix Series` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/netflix-series.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `TVDB` | `PRIMARY` | `tvdb:tt14403178` | `canonical_id owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `HIT` | `false` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tvdb:tt14403178:en:policy:2` |

#### Identity resolution
| Required | Source ID | Target provider | Resolver | API shape | Result | Success |
|---:|---|---|---|---|---|---:|
| `true` | `tt14403178` | `TVDB` | `TvdbIdentityResolver` | `tvdb.remoteid.lookup` | `tvdb:tt14403178` | `true` |

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
| `title` | `KITSU` | `PRIMARY` | `Crunchyroll IMDb Anime` | `title owned by PRIMARY` | `` |
| `poster` | `KITSU` | `PRIMARY` | `https://example.test/crunchyroll.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `KITSU` | `PRIMARY` | `kitsu:7442` | `canonical_id owned by PRIMARY` | `` |

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
| `title` | `TMDB` | `PRIMARY` | `Netflix Movie` | `title owned by PRIMARY` | `` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/netflix-movie.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |

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
| `title` | `TMDB` | `PRIMARY` | `Netflix Movie` | `title owned by PRIMARY` | `` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/netflix-movie.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |

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
| `title` | `TVDB` | `PRIMARY` | `Netflix Series` | `title owned by PRIMARY` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/netflix-series.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `TVDB` | `PRIMARY` | `tvdb:tt14403178` | `canonical_id owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `nld` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `nld` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tvdb:tt14403178:nl-NL:policy:2` |
| `tvdb.series.translation` | `eng` | `LANGUAGE_FALLBACK` | `HIT` | `false` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tvdb:tt14403178:nl-NL:policy:2:fallback:eng` |

#### Identity resolution
| Required | Source ID | Target provider | Resolver | API shape | Result | Success |
|---:|---|---|---|---|---|---:|
| `true` | `tt14403178` | `TVDB` | `TvdbIdentityResolver` | `tvdb.remoteid.lookup` | `tvdb:tt14403178` | `true` |

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
| `title` | `TMDB` | `PRIMARY` | `Netflix Movie` | `title owned by PRIMARY` | `` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/netflix-movie.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |

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
| `title` | `KITSU` | `PRIMARY` | `Kitsu Anime` | `title owned by PRIMARY` | `` |
| `poster` | `KITSU` | `PRIMARY` | `https://example.test/kitsu.jpg` | `poster owned by PRIMARY` | `` |
| `canonical_id` | `KITSU` | `PRIMARY` | `kitsu:7442` | `canonical_id owned by PRIMARY` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `KITSU` | `nl` | `en` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `kitsu.anime.core` | `nl` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:KITSU:kitsu.anime.core:kitsu:7442:nl-NL:policy:2` |
| `kitsu.anime.core` | `en` | `LANGUAGE_FALLBACK` | `HIT` | `false` | `PRODUCTION_ADAPTER` | `metadata:KITSU:kitsu.anime.core:kitsu:7442:nl-NL:policy:2:fallback:en` |

## Scenario `trakt-rail-first-paint-title-year`

**Fixture:** `metadata/rails/trakt-rail-first-paint-title-year.json`
**Verdict:** `PASS`

### trakt:movie:hope-2026 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `RAIL_PREVIEW` | `false` | `false` |

#### Rail preview
| Rail source | Source provider | Payload fields used | Routing after visible | Identity mappings harvested |
|---|---|---|---|---|
| `BUILT_IN_TRAKT` | `TRAKT` | `title, year` | `` | `` |

##### Fields before hydration
| Field | Provider | Value |
|---|---|---|
| `title` | `TRAKT` | `Hope` |
| `year` | `TRAKT` | `2026` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `title` | `TRAKT` | `RAIL_PREVIEW` | `Hope` | `title selected from RAIL_PREVIEW` | `` |
| `year` | `TRAKT` | `RAIL_PREVIEW` | `2026` | `year selected from RAIL_PREVIEW` | `` |

## Scenario `trakt-rail-visible-hydrates-tvdb`

**Fixture:** `metadata/rails/trakt-rail-visible-hydrates-tvdb.json`
**Verdict:** `PASS`

### tvdb:1001 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `RAIL_PREVIEW` | `false` | `false` |

#### Rail preview
| Rail source | Source provider | Payload fields used | Routing after visible | Identity mappings harvested |
|---|---|---|---|---|
| `BUILT_IN_TRAKT` | `TRAKT` | `title, year` | `TVDB` | `` |

##### Fields before hydration
| Field | Provider | Value |
|---|---|---|
| `title` | `TRAKT` | `Signal` |
| `year` | `TRAKT` | `2026` |

##### Fields after hydration
| Field | Provider | Value |
|---|---|---|
| `title` | `TVDB` | `Signal TVDB Canonical` |
| `release_date` | `TRAKT` | `` |
| `canonical_id` | `TVDB` | `tvdb:1001` |
| `overview` | `TVDB` | `Hydrated TVDB series overview` |
| `poster` | `TVDB` | `https://example.test/tvdb-signal.jpg` |

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
| `title` | `TVDB` | `PRIMARY` | `Signal TVDB Canonical` | `primary always wins` | `TRAKT/RAIL_PREVIEW: primary canonical field available` |
| `release_date` | `TRAKT` | `RAIL_PREVIEW` | `` | `rail preview fills field before canonical hydration` | `` |
| `canonical_id` | `TVDB` | `PRIMARY` | `tvdb:1001` | `primary always wins` | `` |
| `overview` | `TVDB` | `PRIMARY` | `Hydrated TVDB series overview` | `primary always wins` | `` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/tvdb-signal.jpg` | `primary always wins` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tvdb:1001::policy:2` |

#### Forbidden overwrites
- `title` rejected `TRAKT` because Field already owned by PRIMARY; rejected PRIMARY

## Scenario `mdblist-rail-first-paint-rich-preview`

**Fixture:** `metadata/rails/mdblist-rail-first-paint-rich-preview.json`
**Verdict:** `PASS`

### mdblist:movie:aurora / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `RAIL_PREVIEW` | `false` | `false` |

#### Rail preview
| Rail source | Source provider | Payload fields used | Routing after visible | Identity mappings harvested |
|---|---|---|---|---|
| `BUILT_IN_MDBLIST` | `MDBLIST` | `title, year, poster, overview` | `` | `` |

##### Fields before hydration
| Field | Provider | Value |
|---|---|---|
| `title` | `MDBLIST` | `Aurora` |
| `year` | `MDBLIST` | `2026` |
| `poster` | `MDBLIST` | `https://example.test/mdblist-aurora.jpg` |
| `overview` | `MDBLIST` | `MDBList rich preview` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `title` | `MDBLIST` | `RAIL_PREVIEW` | `Aurora` | `title selected from RAIL_PREVIEW` | `` |
| `year` | `MDBLIST` | `RAIL_PREVIEW` | `2026` | `year selected from RAIL_PREVIEW` | `` |
| `poster` | `MDBLIST` | `RAIL_PREVIEW` | `https://example.test/mdblist-aurora.jpg` | `poster selected from RAIL_PREVIEW` | `` |
| `overview` | `MDBLIST` | `RAIL_PREVIEW` | `MDBList rich preview` | `overview selected from RAIL_PREVIEW` | `` |

## Scenario `tmdb-movie-rail-first-paint-rich-preview`

**Fixture:** `metadata/rails/tmdb-movie-rail-first-paint-rich-preview.json`
**Verdict:** `PASS`

### tmdb:movie:501 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `RAIL_PREVIEW` | `false` | `false` |

#### Rail preview
| Rail source | Source provider | Payload fields used | Routing after visible | Identity mappings harvested |
|---|---|---|---|---|
| `BUILT_IN_TMDB` | `TMDB` | `title, year, poster, backdrop` | `` | `` |

##### Fields before hydration
| Field | Provider | Value |
|---|---|---|
| `title` | `TMDB` | `TMDB Preview Movie` |
| `year` | `TMDB` | `2026` |
| `poster` | `TMDB` | `https://example.test/tmdb-movie.jpg` |
| `backdrop` | `TMDB` | `https://example.test/tmdb-backdrop.jpg` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `title` | `TMDB` | `RAIL_PREVIEW` | `TMDB Preview Movie` | `title selected from RAIL_PREVIEW` | `` |
| `year` | `TMDB` | `RAIL_PREVIEW` | `2026` | `year selected from RAIL_PREVIEW` | `` |
| `poster` | `TMDB` | `RAIL_PREVIEW` | `https://example.test/tmdb-movie.jpg` | `poster selected from RAIL_PREVIEW` | `` |
| `backdrop` | `TMDB` | `RAIL_PREVIEW` | `https://example.test/tmdb-backdrop.jpg` | `backdrop selected from RAIL_PREVIEW` | `` |

## Scenario `tmdb-tv-rail-preview-then-tvdb-hydration`

**Fixture:** `metadata/rails/tmdb-tv-rail-preview-then-tvdb-hydration.json`
**Verdict:** `PASS`

### tvdb:121361 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `RAIL_PREVIEW` | `false` | `false` |

#### Rail preview
| Rail source | Source provider | Payload fields used | Routing after visible | Identity mappings harvested |
|---|---|---|---|---|
| `BUILT_IN_TMDB` | `TMDB` | `title, poster` | `TVDB` | `` |

##### Fields before hydration
| Field | Provider | Value |
|---|---|---|
| `title` | `TMDB` | `TMDB TV Preview` |
| `poster` | `TMDB` | `https://example.test/tmdb-tv.jpg` |

##### Fields after hydration
| Field | Provider | Value |
|---|---|---|
| `title` | `TVDB` | `TVDB Hydrated Series` |
| `poster` | `TVDB` | `https://example.test/tvdb-tv.jpg` |
| `canonical_id` | `TVDB` | `tvdb:tv` |
| `overview` | `TVDB` | `TVDB replaced rail preview fields` |

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
| `title` | `TVDB` | `PRIMARY` | `TVDB Hydrated Series` | `primary always wins` | `TMDB/RAIL_PREVIEW: primary canonical field available` |
| `poster` | `TVDB` | `PRIMARY` | `https://example.test/tvdb-tv.jpg` | `primary always wins` | `TMDB/RAIL_PREVIEW: primary canonical field available` |
| `canonical_id` | `TVDB` | `PRIMARY` | `tvdb:tv` | `primary always wins` | `` |
| `overview` | `TVDB` | `PRIMARY` | `TVDB replaced rail preview fields` | `primary always wins` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TVDB` | `eng` | `eng` | `2` | `false` | `0/8` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tvdb.series.translation` | `eng` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TVDB:tvdb.series.extended:tvdb:tv::policy:2` |

#### Forbidden overwrites
- `title` rejected `TMDB` because Field already owned by PRIMARY; rejected PRIMARY
- `poster` rejected `TMDB` because Field already owned by PRIMARY; rejected PRIMARY

## Scenario `kitsu-rail-first-paint-rich-preview`

**Fixture:** `metadata/rails/kitsu-rail-first-paint-rich-preview.json`
**Verdict:** `PASS`

### kitsu:7442 / series

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `RAIL_PREVIEW` | `false` | `false` |

#### Rail preview
| Rail source | Source provider | Payload fields used | Routing after visible | Identity mappings harvested |
|---|---|---|---|---|
| `BUILT_IN_KITSU` | `KITSU` | `title, poster, overview` | `` | `` |

##### Fields before hydration
| Field | Provider | Value |
|---|---|---|
| `title` | `KITSU` | `Kitsu Preview Anime` |
| `poster` | `KITSU` | `https://example.test/kitsu-anime.jpg` |
| `overview` | `KITSU` | `Kitsu rich preview` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `title` | `KITSU` | `RAIL_PREVIEW` | `Kitsu Preview Anime` | `title selected from RAIL_PREVIEW` | `` |
| `poster` | `KITSU` | `RAIL_PREVIEW` | `https://example.test/kitsu-anime.jpg` | `poster selected from RAIL_PREVIEW` | `` |
| `overview` | `KITSU` | `RAIL_PREVIEW` | `Kitsu rich preview` | `overview selected from RAIL_PREVIEW` | `` |

## Scenario `simkl-json-rail-first-paint-rich-preview`

**Fixture:** `metadata/rails/simkl-json-rail-first-paint-rich-preview.json`
**Verdict:** `PASS`

### simkl:movie:77 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `RAIL_PREVIEW` | `false` | `false` |

#### Rail preview
| Rail source | Source provider | Payload fields used | Routing after visible | Identity mappings harvested |
|---|---|---|---|---|
| `BUILT_IN_SIMKL_DISCOVERY` | `SIMKL` | `title, year, poster, overview` | `` | `` |

##### Fields before hydration
| Field | Provider | Value |
|---|---|---|
| `title` | `SIMKL` | `Simkl JSON Movie` |
| `year` | `SIMKL` | `2026` |
| `poster` | `SIMKL` | `https://example.test/simkl-json.jpg` |
| `overview` | `SIMKL` | `Simkl JSON rich preview` |

#### Final fields
| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `title` | `SIMKL` | `RAIL_PREVIEW` | `Simkl JSON Movie` | `title selected from RAIL_PREVIEW` | `` |
| `year` | `SIMKL` | `RAIL_PREVIEW` | `2026` | `year selected from RAIL_PREVIEW` | `` |
| `poster` | `SIMKL` | `RAIL_PREVIEW` | `https://example.test/simkl-json.jpg` | `poster selected from RAIL_PREVIEW` | `` |
| `overview` | `SIMKL` | `RAIL_PREVIEW` | `Simkl JSON rich preview` | `overview selected from RAIL_PREVIEW` | `` |

## Scenario `simkl-json-rail-visible-hydrates-tmdb`

**Fixture:** `metadata/rails/simkl-json-rail-visible-hydrates-tmdb.json`
**Verdict:** `PASS`

### tmdb:5088 / movie

| First paint source | Router executed | Network executed |
|---|---:|---:|
| `RAIL_PREVIEW` | `false` | `false` |

#### Rail preview
| Rail source | Source provider | Payload fields used | Routing after visible | Identity mappings harvested |
|---|---|---|---|---|
| `BUILT_IN_SIMKL_DISCOVERY` | `SIMKL` | `title, year` | `TMDB` | `` |

##### Fields before hydration
| Field | Provider | Value |
|---|---|---|
| `title` | `SIMKL` | `Simkl JSON Hydration` |
| `year` | `SIMKL` | `2026` |

##### Fields after hydration
| Field | Provider | Value |
|---|---|---|
| `title` | `TMDB` | `TMDB Hydrated Movie` |
| `release_date` | `SIMKL` | `` |
| `canonical_id` | `TMDB` | `tmdb:5088` |
| `overview` | `TMDB` | `TMDB replaced Simkl rail preview fields` |
| `poster` | `TMDB` | `https://example.test/tmdb-simkl.jpg` |

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
| `title` | `TMDB` | `PRIMARY` | `TMDB Hydrated Movie` | `primary always wins` | `SIMKL/RAIL_PREVIEW: primary canonical field available` |
| `release_date` | `SIMKL` | `RAIL_PREVIEW` | `` | `rail preview fills field before canonical hydration` | `` |
| `canonical_id` | `TMDB` | `PRIMARY` | `tmdb:5088` | `primary always wins` | `` |
| `overview` | `TMDB` | `PRIMARY` | `TMDB replaced Simkl rail preview fields` | `primary always wins` | `` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/tmdb-simkl.jpg` | `primary always wins` | `` |

#### Localization
| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |
|---|---|---|---:|---:|---:|
| `TMDB` | `en-US` | `en-US` | `2` | `false` | `0/0` |

| API shape | Language | Role | Cache decision | Network | Source | Cache key |
|---|---|---|---|---:|---|---|
| `tmdb.movie.core` | `en-US` | `LOCALIZED` | `MISS_THEN_NETWORK` | `true` | `PRODUCTION_ADAPTER` | `metadata:TMDB:tmdb.movie.core:tmdb:5088::policy:2` |

#### Forbidden overwrites
- `title` rejected `SIMKL` because Field already owned by PRIMARY; rejected PRIMARY

