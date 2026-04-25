According to the April 25 TMDB blueprint and the latest IntegrationRuntime audit, I would define TMDB’s provider contract as below. This is meant to be a **manual review matrix**: something your engineers can turn into `expected_integration_contracts.yaml`, and something you can adjust provider-by-provider.

TMDB is currently **control-plane covered** in the runtime, but not yet fully MetadataRouter-ready. The latest audit shows TMDB has an adapter, a policy entry, 3 runtime-covered calls, no direct bypasses, and no missing endpoint-shape IDs for those covered calls; however, several TMDB shapes required for MetadataRouter are still marked `ACTIVE_REQUIRED_MISSING`, including movie core, season episodes, videos, recommendations, and reviews.  

# TMDB provider contract — draft v1

## Provider-level policy

| Area                         | TMDB contract                                                                                                                                                                                                                                                                                                  |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Provider ID                  | `TMDB`                                                                                                                                                                                                                                                                                                         |
| Logical role                 | **Primary authority for non-anime movies.** Secondary/fallback/supplement provider for TV. Not primary for anime except explicit fallback after Kitsu failure.                                                                                                                                                 |
| Base host                    | `api.themoviedb.org`                                                                                                                                                                                                                                                                                           |
| API version                  | v3 paths under `/3/...`                                                                                                                                                                                                                                                                                        |
| Runtime adapter              | `TmdbIntegrationProvider`                                                                                                                                                                                                                                                                                      |
| Runtime status               | Control-plane covered; MetadataRouter readiness still incomplete.                                                                                                                                                                                                                                              |
| Default work class           | `USER_VISIBLE` for detail/identity/search; `BACKGROUND_HYDRATION` for rail warming; `MAINTENANCE` only for future change feeds or background refresh.                                                                                                                                                          |
| Default provider concurrency | `maxConcurrentNetworkStarts = 1` until telemetry validates promotion. TMDB is a candidate for later concurrency increase, but not by default.                                                                                                                                                                  |
| Default header policy        | `tmdb-json-v1`                                                                                                                                                                                                                                                                                                 |
| Auth header                  | Required `Authorization` header using bearer-style TMDB token. The uploaded TMDB OpenAPI declares a security scheme in the request header named `Authorization`, with bearer format.                                                                                                                           |
| Stock headers                | Nexio default User-Agent, `Accept: application/json`. No `Content-Type` on GET requests.                                                                                                                                                                                                                       |
| Forbidden headers            | `X-Trakt-API-Key`, `simkl-api-key`, `X-TVDB-ApiKey`, raw `api_key` query credential, or any other cross-provider auth header. The latest audit’s TMDB header policy already models this forbidden-header set and captures `Retry-After`.                                                                       |
| Credential location          | Header only by default. If a legacy query-key path is ever supported, it should be a separate explicit policy such as `tmdb-query-key-legacy-v1`, not the default.                                                                                                                                             |
| 429 policy                   | On HTTP `429`, parse `Retry-After` if present; if absent, use runtime fallback backoff. Persist by `provider + credentialHash` or `provider + scope`, not by raw token. MDN documents that `429 Too Many Requests` may include a `Retry-After` header telling the client how long to wait. ([MDN Web Docs][1]) |
| Cache key secret policy      | Never include raw bearer token or raw API key. If credential affects quota or response entitlement, use `credentialHash`.                                                                                                                                                                                      |
| Negative-cache policy        | Cache “not found / unresolved” identity misses briefly, not permanently. Suggested: 6–24h depending on route.                                                                                                                                                                                                  |
| Audit requirement            | Every TMDB call must have `apiShapeId`, `headerPolicyId = tmdb-json-v1`, `operationKey`, cache policy, work class, endpoint-shape contract, and runtime event sample.                                                                                                                                          |

---

# TMDB contract matrix

The TTLs below are the recommended **target policy**, not necessarily what the current audit already proves.

| API shape ID                   | Lifecycle for MetadataRouter                                     | Endpoint shape                                                                             | Purpose                                                                                                                                                                                             | Recommended cache policy                                                                         | Cache-key vary inputs                                                                                                                              | Required headers                        | 429 / backoff                                                                | Audit / best-practice rule                                                                                                                           |
| ------------------------------ | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------- | ---------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `tmdb.key_validation`          | Active, settings only                                            | `GET /3/authentication`                                                                    | Validate configured TMDB credential.                                                                                                                                                                | `Disabled`. This should intentionally hit network when user validates settings.                  | `credentialHash` only for operation/audit identity, not durable cache.                                                                             | `Authorization`; Nexio UA; JSON accept. | Persist 401/403 as credential-health issue; persist 429 as provider backoff. | Do not cache validation success as authority. Redact credential.                                                                                     |
| `tmdb.find.external_id`        | **Active required**                                              | `GET /3/find/{external_id}` with `external_source`                                         | Bridge IMDb/TVDB/etc. IDs into TMDB IDs. Endpoint index identifies this as the TMDB external-ID bridge.                                                                                             | **Target: `CacheFirst`**. Current audit has this as `ObserveOnlyOrMutation`; I would upgrade it. | `external_id`, `external_source`, `language`, schema version.                                                                                      | `Authorization`; Nexio UA; JSON accept. | Backoff provider/credential scope.                                           | Positive TTL 30d, stale 180d. Negative TTL 12–24h. This should be a durable identity alias, not refetched every detail visit.                        |
| `tmdb.movie.external_ids`      | Active required if movie IDs need IMDb bridge                    | `GET /3/movie/{movie_id}/external_ids`                                                     | Resolve TMDB movie → IMDb. Endpoint index marks this as key for custom IMDb and OMDb fallback paths.                                                                                                | `CacheFirst`                                                                                     | `movie_id`, schema version.                                                                                                                        | Same.                                   | Same.                                                                        | Positive TTL 30d, stale 180d.                                                                                                                        |
| `tmdb.tv.external_ids`         | Active required if TV fallback/ratings need IMDb bridge          | `GET /3/tv/{series_id}/external_ids`                                                       | Resolve TMDB TV → IMDb.                                                                                                                                                                             | `CacheFirst`                                                                                     | `series_id`, schema version.                                                                                                                       | Same.                                   | Same.                                                                        | Positive TTL 30d, stale 180d.                                                                                                                        |
| `tmdb.movie.core`              | **Active required missing**                                      | `GET /3/movie/{movie_id}` with `append_to_response`                                        | Primary non-anime movie metadata bundle. Endpoint index confirms current Android already uses movie detail as one-call multi-field enrichment.                                                      | `CacheFirst`                                                                                     | `movie_id`, `language`, `region` if used, append-bundle version, schema version, provider token/poster policy if response embeds selected artwork. | Same.                                   | Same; serve stale if available during backoff.                               | Required append bundle should include `credits,images,release_dates,external_ids`. Add `videos` only if trailer availability is part of detail core. |
| `tmdb.tv.core`                 | Active covered                                                   | `GET /3/tv/{series_id}` with `append_to_response`                                          | TV fallback/supplement bundle, not primary TV authority when TVDB is healthy. Current audit maps this through `TmdbIntegrationProvider.fetchEnrichment` with `CacheFirst`.                          | `CacheFirst`                                                                                     | `series_id`, `language`, append-bundle version, schema version, provider token/poster policy if relevant.                                          | Same.                                   | Same.                                                                        | Required append bundle: `credits,images,content_ratings,external_ids`. Do not let this overwrite TVDB-owned TV fields unless fallback is explicit.   |
| `tmdb.season.episodes`         | **Active required missing**                                      | `GET /3/tv/{series_id}/season/{season_number}`                                             | Season-batch fallback episode metadata and TMDB episode ratings. Endpoint index says this season response is reused for metadata and ratings.                                                       | `CacheFirst`                                                                                     | `series_id`, `season_number`, `language`, append-bundle version if used, schema version.                                                           | Same.                                   | Same.                                                                        | TTL 24h, stale 7d. Prefer this batch route over per-episode TMDB calls.                                                                              |
| `tmdb.movie.videos`            | **Active required missing**                                      | `GET /3/movie/{movie_id}/videos`                                                           | Movie trailers, teasers, possible recaps. Endpoint index says TrailerService uses it and it is cached today in `MetadataDiskCacheStore`.                                                            | `CacheFirst`                                                                                     | `movie_id`, `language`, `include_video_language` if added, schema version.                                                                         | Same.                                   | Same.                                                                        | TTL 12h, stale 7d. If `videos` is appended into `movie.core`, this route becomes lazy/fallback only.                                                 |
| `tmdb.tv.videos`               | **Active required missing**                                      | `GET /3/tv/{series_id}/videos`                                                             | TV trailer fallback after TVDB/Streailer checks.                                                                                                                                                    | `CacheFirst`                                                                                     | `series_id`, `language`, `include_video_language`, schema version.                                                                                 | Same.                                   | Same.                                                                        | TTL 12h, stale 7d. Secondary resolver only; not TV primary metadata.                                                                                 |
| `tmdb.season.videos`           | Planned, not first MetadataRouter blocker                        | `GET /3/tv/{series_id}/season/{season_number}/videos`                                      | Season trailer / recap availability. Endpoint index classifies this as season-batch video metadata.                                                                                                 | `CacheFirst` when implemented                                                                    | `series_id`, `season_number`, `language`, `include_video_language`, schema version.                                                                | Same.                                   | Same.                                                                        | TTL 12h, stale 7d. Fetch only for `DETAIL_MEDIA` / season recap UI.                                                                                  |
| `tmdb.movie.recommendations`   | **Active required missing** if related rails launch with router  | `GET /3/movie/{movie_id}/recommendations`                                                  | Movie “more like this.” Endpoint index says app keeps top-ranked localized subset.                                                                                                                  | `CacheFirst`, lazy                                                                               | `movie_id`, `language`, `page`, schema version.                                                                                                    | Same.                                   | Same.                                                                        | TTL 24h, stale 7d. Lazy `DETAIL_SECONDARY`, not core.                                                                                                |
| `tmdb.tv.recommendations`      | **Active required missing** if TV related rails are router scope | `GET /3/tv/{series_id}/recommendations`                                                    | Non-anime TV “more like this.”                                                                                                                                                                      | `CacheFirst`, lazy                                                                               | `series_id`, `language`, `page`, schema version.                                                                                                   | Same.                                   | Same.                                                                        | TTL 24h, stale 7d. Kitsu owns anime related titles; TMDB only for non-anime TV or explicit fallback.                                                 |
| `tmdb.movie.reviews`           | **Active required missing** if reviews are router scope          | `GET /3/movie/{movie_id}/reviews`                                                          | TMDB movie reviews, later merged with Trakt comments.                                                                                                                                               | `CacheFirst`, lazy                                                                               | `movie_id`, `language`, `page`, schema version.                                                                                                    | Same.                                   | Same.                                                                        | TTL 12–24h, stale 7d. Do not fetch during `DETAIL_CORE`.                                                                                             |
| `tmdb.tv.reviews`              | **Active required missing** if reviews are router scope          | `GET /3/tv/{series_id}/reviews`                                                            | TMDB TV reviews, later merged with Trakt comments.                                                                                                                                                  | `CacheFirst`, lazy                                                                               | `series_id`, `language`, `page`, schema version.                                                                                                   | Same.                                   | Same.                                                                        | TTL 12–24h, stale 7d. Secondary resolver only.                                                                                                       |
| `tmdb.collection`              | Planned                                                          | `GET /3/collection/{collection_id}`                                                        | Movie collection rail on detail. Endpoint index says used for movie collections only.                                                                                                               | `CacheFirst`, lazy                                                                               | `collection_id`, `language`, schema version.                                                                                                       | Same.                                   | Same.                                                                        | TTL 7d, stale 30d. Fetch after movie core only if collection section is visible.                                                                     |
| `tmdb.person.core`             | Recommended replacement for split person calls                   | `GET /3/person/{person_id}` with `append_to_response=combined_credits,images,external_ids` | Person biography + image + filmography in one call. TMDB blueprint supports `append_to_response` on person detail; current endpoint index shows separate person detail and combined credits calls.  | `CacheFirst`, user-triggered                                                                     | `person_id`, `language`, append-bundle version, schema version.                                                                                    | Same.                                   | Same.                                                                        | Prefer this combined shape over separate `person.detail` + `person.combined_credits`. TTL 7d, stale 30d.                                             |
| `tmdb.person.detail`           | Legacy/planned if not using `person.core`                        | `GET /3/person/{person_id}`                                                                | Person biography, birthday, birthplace, photo.                                                                                                                                                      | `CacheFirst`                                                                                     | `person_id`, `language`, schema version.                                                                                                           | Same.                                   | Same.                                                                        | If kept separate, TTL 7d, stale 30d.                                                                                                                 |
| `tmdb.person.combined_credits` | Legacy/planned if not using `person.core`                        | `GET /3/person/{person_id}/combined_credits`                                               | Person movie+TV credits.                                                                                                                                                                            | `CacheFirst`                                                                                     | `person_id`, `language`, schema version.                                                                                                           | Same.                                   | Same.                                                                        | Prefer append inside `tmdb.person.core` where possible.                                                                                              |
| `tmdb.search.person`           | Planned bridge                                                   | `GET /3/search/person`                                                                     | Bridge Kitsu cast/company names into TMDB person IDs. Endpoint index says exact-name bridge only.                                                                                                   | `CacheFirst` with short negative cache                                                           | normalized name, language, page, schema version.                                                                                                   | Same.                                   | Same.                                                                        | TTL 7d for positive, 24h negative. Mark confidence in result.                                                                                        |
| `tmdb.search.company`          | Planned bridge                                                   | `GET /3/search/company`                                                                    | Bridge Kitsu/company names into TMDB IDs.                                                                                                                                                           | `CacheFirst` with short negative cache                                                           | normalized name, page, schema version.                                                                                                             | Same.                                   | Same.                                                                        | TTL 7d positive, 24h negative.                                                                                                                       |
| `tmdb.company.detail`          | Planned                                                          | `GET /3/company/{company_id}`                                                              | Production-company detail. Endpoint index says this includes description, headquarters, homepage, origin country.                                                                                   | `CacheFirst`, user-triggered                                                                     | `company_id`, schema version.                                                                                                                      | Same.                                   | Same.                                                                        | TTL 7d, stale 30d.                                                                                                                                   |
| `tmdb.network.detail`          | Planned                                                          | `GET /3/network/{network_id}`                                                              | TV network detail. Endpoint index notes current TMDB network model lacks description.                                                                                                               | `CacheFirst`, user-triggered                                                                     | `network_id`, schema version.                                                                                                                      | Same.                                   | Same.                                                                        | TTL 7d, stale 30d.                                                                                                                                   |
| `tmdb.discover.movie`          | Active/planned depending catalog rails                           | `GET /3/discover/movie`                                                                    | Built-in TMDB rails and company-title discovery. Endpoint index says used for stock rows and company-title discovery.                                                                               | `CacheFirst` for rails                                                                           | full canonical query map, language, region, page, schema version.                                                                                  | Same.                                   | Same.                                                                        | TTL 1–6h for trending-like rails; 12–24h for stable company filters.                                                                                 |
| `tmdb.discover.tv`             | Active/planned depending catalog rails                           | `GET /3/discover/tv`                                                                       | Built-in TMDB rails and company/network-title discovery.                                                                                                                                            | `CacheFirst` for rails                                                                           | full canonical query map, language, timezone/region, page, schema version.                                                                         | Same.                                   | Same.                                                                        | TTL 1–6h for discovery rails; 12–24h for stable organization filters.                                                                                |
| `tmdb.search.movie`            | Planned / search surface                                         | `GET /3/search/movie`                                                                      | Built-in TMDB movie search row. Endpoint index says row IDs become IMDb when possible, otherwise `tmdb:{id}`.                                                                                       | `CacheFirst`, ephemeral                                                                          | query, language, region, year, page, includeAdult, schema version.                                                                                 | Same.                                   | Same.                                                                        | TTL 6h, stale 24h. Do not use as identity authority if exact ID bridge exists.                                                                       |
| `tmdb.search.tv`               | Planned / search surface                                         | `GET /3/search/tv`                                                                         | Built-in TMDB TV search row.                                                                                                                                                                        | `CacheFirst`, ephemeral                                                                          | query, language, firstAirYear/year, page, includeAdult, schema version.                                                                            | Same.                                   | Same.                                                                        | TTL 6h, stale 24h.                                                                                                                                   |
| `tmdb.trending.movie`          | Active/planned discovery                                         | `GET /3/trending/movie/{time_window}`                                                      | Built-in TMDB home row.                                                                                                                                                                             | `CacheFirst` rail                                                                                | timeWindow, language, page if used, schema version.                                                                                                | Same.                                   | Same.                                                                        | TTL 1h, stale 24h.                                                                                                                                   |
| `tmdb.trending.tv`             | Active/planned discovery                                         | `GET /3/trending/tv/{time_window}`                                                         | Built-in TMDB home row.                                                                                                                                                                             | `CacheFirst` rail                                                                                | timeWindow, language, page if used, schema version.                                                                                                | Same.                                   | Same.                                                                        | TTL 1h, stale 24h.                                                                                                                                   |
| `tmdb.popular.movie`           | Active/planned discovery                                         | `GET /3/movie/popular`                                                                     | Built-in TMDB home row.                                                                                                                                                                             | `CacheFirst` rail                                                                                | language, region, page, schema version.                                                                                                            | Same.                                   | Same.                                                                        | TTL 6h, stale 24h.                                                                                                                                   |
| `tmdb.popular.tv`              | Active/planned discovery                                         | `GET /3/tv/popular`                                                                        | Built-in TMDB home row.                                                                                                                                                                             | `CacheFirst` rail                                                                                | language, page, schema version.                                                                                                                    | Same.                                   | Same.                                                                        | TTL 6h, stale 24h.                                                                                                                                   |
| `tmdb.genre.movie.list`        | Optional global reusable                                         | `GET /3/genre/movie/list`                                                                  | Genre labels. TMDB blueprint includes official movie genre list with `language` query.                                                                                                              | `CacheFirst` global reusable                                                                     | language, schema version.                                                                                                                          | Same.                                   | Same.                                                                        | TTL 30d, stale 90d.                                                                                                                                  |
| `tmdb.genre.tv.list`           | Optional global reusable                                         | `GET /3/genre/tv/list`                                                                     | TV genre labels. TMDB blueprint includes official TV genre list with `language` query.                                                                                                              | `CacheFirst` global reusable                                                                     | language, schema version.                                                                                                                          | Same.                                   | Same.                                                                        | TTL 30d, stale 90d.                                                                                                                                  |
| `tmdb.configuration`           | Optional global reusable                                         | `GET /3/configuration`                                                                     | Image base URLs, sizes, static config.                                                                                                                                                              | `CacheFirst` global reusable                                                                     | schema version.                                                                                                                                    | Same.                                   | Same.                                                                        | TTL 7d–30d, stale 90d.                                                                                                                               |

---

# Recommended TMDB header contract

This can go into `expected_integration_contracts.yaml`.

```yaml
headerPolicies:
  tmdb-json-v1:
    stock:
      - nexio-default-user-agent
      - json-accept

    requiredHeaders:
      Authorization:
        kind: bearer
        source: tmdb.readAccessToken
        redact: true

    forbiddenHeaders:
      - X-Trakt-API-Key
      - simkl-api-key
      - X-TVDB-ApiKey
      - api_key

    forbiddenQueryCredentials:
      - api_key

    responseHeadersToCapture:
      - Retry-After

    contentType:
      get: absent
      post: application/json

    userAgent:
      policy: nexio-default-user-agent
      browserLikeAllowed: false
```

I would be strict here: use TMDB bearer `Authorization` as the default. The blueprint’s security scheme names the header `Authorization`; your latest header audit also expects `Authorization` and forbids cross-provider headers and raw `api_key` usage for TMDB.  

---

# Recommended TMDB cache contract presets

These are reusable cache classes for TMDB rows.

| Cache preset            | Applies to                                                  |    TTL | Stale after expiry | Notes                                                                                 |
| ----------------------- | ----------------------------------------------------------- | -----: | -----------------: | ------------------------------------------------------------------------------------- |
| `tmdb_identity_alias`   | `find.external_id`, `movie.external_ids`, `tv.external_ids` |    30d |               180d | Positive aliases are stable. Negative misses should be much shorter, 12–24h.          |
| `tmdb_movie_core`       | `movie.core`                                                |     7d |                30d | Use 24h TTL for unreleased or very recent titles if you can detect release proximity. |
| `tmdb_tv_fallback_core` | `tv.core`                                                   |     7d |             14–30d | Secondary to TVDB. Must not silently overwrite TVDB-owned fields.                     |
| `tmdb_season_batch`     | `season.episodes`                                           |    24h |                 7d | Used for fallback episode metadata and ratings.                                       |
| `tmdb_video_candidates` | movie/TV/season videos                                      |    12h |                 7d | Current storage matrix already has TMDB title/season videos at 12h.                   |
| `tmdb_reviews`          | movie/TV reviews                                            | 12–24h |                 7d | Lazy `DETAIL_SECONDARY`.                                                              |
| `tmdb_recommendations`  | movie/TV recommendations                                    |    24h |                 7d | Lazy `DETAIL_SECONDARY`.                                                              |
| `tmdb_collection`       | movie collections                                           |     7d |                30d | Lazy.                                                                                 |
| `tmdb_person_or_org`    | person/company/network detail                               |     7d |                30d | User-triggered.                                                                       |
| `tmdb_search_ephemeral` | search movie/TV/person/company                              |     6h |                24h | Search can change and is user-query-specific.                                         |
| `tmdb_discovery_rail`   | trending/popular/discover rails                             |   1–6h |                24h | Rail ownership should later protect item data.                                        |
| `tmdb_reference`        | genres/configuration                                        |    30d |                90d | Global reusable.                                                                      |

---

# TMDB best-practice rules

These are the rules I would make the audit enforce.

## 1. Movie detail must be one-call multi-field enrichment

`tmdb.movie.core` should use:

```text
GET /3/movie/{movie_id}
  ?language={language}
  &append_to_response=credits,images,release_dates,external_ids
```

Optional, depending on UI choice:

```text
,videos
```

Do not make separate calls for credits/images/release dates during detail core unless there is a documented reason. The endpoint index explicitly classifies TMDB movie detail as a one-call multi-field enrichment route. 

## 2. TV detail through TMDB is fallback/supplement only

`tmdb.tv.core` should use:

```text
GET /3/tv/{series_id}
  ?language={language}
  &append_to_response=credits,images,content_ratings,external_ids
```

TMDB TV data may fill allowed supplemental fields such as missing artwork, recommendations, reviews, and fallback metadata, but it should not overwrite TVDB-owned TV fields once the MetadataRouter exists.

## 3. Season data must be season batch

Use:

```text
GET /3/tv/{series_id}/season/{season_number}
```

for TMDB fallback episode metadata and episode ratings. Do not fan out per episode unless a specific endpoint requires it. The endpoint index classifies this route as a season batch and says the season response is reused for metadata and ratings. 

## 4. Person detail should probably become one combined shape

Instead of:

```text
GET /3/person/{person_id}
GET /3/person/{person_id}/combined_credits
```

prefer:

```text
GET /3/person/{person_id}
  ?append_to_response=combined_credits,images,external_ids
```

The blueprint supports `append_to_response` on person details, while the current endpoint index shows person detail and combined credits as separate calls. 

## 5. Reviews and recommendations are lazy

Do not fetch reviews and recommendations as part of `DETAIL_CORE` unless the UI immediately renders those sections. They should be `DETAIL_SECONDARY`.

## 6. Cache key must include append bundle version

For any append-based endpoint, include an explicit bundle version:

```text
tmdb:movie:{movieId}:lang:{language}:bundle:movie_core_v1:schema:{schemaVersion}
```

If you later add `videos` or `translations` to the append bundle, bump the bundle version. Otherwise old cache entries will decode as if they contain fields they never fetched.

---

# TMDB contract YAML draft

A compact version you can adapt:

```yaml
provider: TMDB
lifecycleStatus: ACTIVE_RUNTIME_COVERED
adapter: TmdbIntegrationProvider

providerPolicy:
  maxConcurrentNetworkStarts: 1
  minStartGapMs: 0
  desiredConcurrencyAfterValidation: 2
  promotionRequiresTelemetryDays: 7
  backoffScope: provider_plus_credential_hash
  defaultBackoffOn429Ms: 2000
  captureRetryAfter: true

headerPolicies:
  tmdb-json-v1:
    stock:
      - nexio-default-user-agent
      - json-accept
    requiredHeaders:
      Authorization:
        kind: bearer
        source: tmdb.readAccessToken
        redact: true
    forbiddenHeaders:
      - X-Trakt-API-Key
      - simkl-api-key
      - X-TVDB-ApiKey
      - api_key
    forbiddenQueryCredentials:
      - api_key
    responseHeadersToCapture:
      - Retry-After

apiShapes:
  tmdb.find.external_id:
    lifecycleStatus: ACTIVE_RUNTIME_COVERED
    method: GET
    path: /3/find/{external_id}
    bulkShape: single-item
    query:
      required:
        external_source: present
      optional:
        language: present
    workClasses:
      - USER_VISIBLE
      - BACKGROUND_HYDRATION
    cache:
      policy: CacheFirst
      ttl: 30d
      staleAfterExpiry: 180d
      negativeTtl: 24h
      keyIncludes:
        - external_id
        - external_source
        - language
        - schema_version

  tmdb.movie.core:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /3/movie/{movie_id}
    bulkShape: one-call multi-field enrichment
    query:
      required:
        language: present
        append_to_response:
          contains:
            - credits
            - images
            - release_dates
            - external_ids
      optional:
        append_to_response:
          mayContain:
            - videos
            - recommendations
            - reviews
            - translations
    workClasses:
      - USER_VISIBLE
      - BACKGROUND_HYDRATION
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes:
        - movie_id
        - language
        - region
        - append_bundle_version
        - schema_version

  tmdb.tv.core:
    lifecycleStatus: ACTIVE_RUNTIME_COVERED
    method: GET
    path: /3/tv/{series_id}
    bulkShape: one-call multi-field enrichment
    query:
      required:
        language: present
        append_to_response:
          contains:
            - credits
            - images
            - content_ratings
            - external_ids
    workClasses:
      - USER_VISIBLE
      - BACKGROUND_HYDRATION
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes:
        - series_id
        - language
        - append_bundle_version
        - schema_version

  tmdb.season.episodes:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /3/tv/{series_id}/season/{season_number}
    bulkShape: season batch
    workClasses:
      - USER_VISIBLE
      - BACKGROUND_HYDRATION
    cache:
      policy: CacheFirst
      ttl: 24h
      staleAfterExpiry: 7d
      keyIncludes:
        - series_id
        - season_number
        - language
        - append_bundle_version
        - schema_version

  tmdb.movie.videos:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /3/movie/{movie_id}/videos
    bulkShape: list
    workClasses:
      - USER_VISIBLE
      - BACKGROUND_HYDRATION
    cache:
      policy: CacheFirst
      ttl: 12h
      staleAfterExpiry: 7d
      keyIncludes:
        - movie_id
        - language
        - schema_version

  tmdb.recommendations:
    appliesTo:
      - tmdb.movie.recommendations
      - tmdb.tv.recommendations
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    cache:
      policy: CacheFirst
      ttl: 24h
      staleAfterExpiry: 7d

  tmdb.reviews:
    appliesTo:
      - tmdb.movie.reviews
      - tmdb.tv.reviews
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    cache:
      policy: CacheFirst
      ttl: 24h
      staleAfterExpiry: 7d
```

---

# Current TMDB gaps to resolve before MetadataRouter

The latest audit says TMDB is passing the **control-plane** gate, but the MetadataRouter-readiness section still marks these as missing runtime specs:

```text
tmdb.movie.core
tmdb.season.episodes
tmdb.movie.videos
tmdb.tv.videos
tmdb.movie.recommendations
tmdb.tv.recommendations
tmdb.movie.reviews
tmdb.tv.reviews
```

It also marks several TMDB shapes as `PLANNED_NOT_ACTIVE`, including season videos, collections, person detail, combined credits, company detail, network detail, and organization discover routes. 

I would handle the missing shapes like this:

1. **Implement `tmdb.movie.core` first.** It is required because TMDB is the primary movie authority.
2. **Implement `tmdb.season.episodes` next.** It is needed for TMDB fallback episode metadata/ratings.
3. **Decide whether videos are core or lazy.** If lazy, move `tmdb.movie.videos` and `tmdb.tv.videos` out of router-required core and into `DETAIL_MEDIA`.
4. **Decide whether reviews/recommendations are in first router scope.** If not, downgrade them from `ACTIVE_REQUIRED_MISSING` to `PLANNED_NOT_ACTIVE` with a reason.
5. **Replace person split calls with a single `tmdb.person.core` append shape** if you agree with the one-call person-detail optimization.

---

# Review decisions for you

These are the only points I would ask you to decide manually for TMDB before locking the contract:

| Decision                                                       | My recommendation                                                                                             |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| Should TMDB use bearer `Authorization` only?                   | Yes. Keep query `api_key` as forbidden unless you deliberately support a legacy policy.                       |
| Should `tmdb.movie.core` append `videos`?                      | Only if trailer availability is always needed during hero render. Otherwise keep videos lazy.                 |
| Should `tmdb.find.external_id` be `CacheFirst`?                | Yes. It is an identity bridge and should be durable.                                                          |
| Should TMDB TV core be allowed to overwrite TVDB fields?       | No. TMDB TV is fallback/supplement once TVDB is primary.                                                      |
| Should reviews/recommendations block MetadataRouter readiness? | I would say no unless first router release includes those sections immediately. Make them `DETAIL_SECONDARY`. |
| Should person detail use one append call?                      | Yes: `person/{id}?append_to_response=combined_credits,images,external_ids`.                                   |
| Should TMDB concurrency stay 1?                                | Yes for initial runtime validation; mark as candidate for promotion after clean telemetry.                    |
| Should `apiKey.hashCode()` remain in audit/cache identities?   | No. Replace with explicit `credentialHash = sha256(provider + normalizedCredential)`.                         |

My suggested next step: approve or edit the TMDB matrix above, then we can move to **TVDB**, where the big decisions will be bearer JWT, update-driven invalidation, season-batch episode routing, and reference-data caching.

[1]: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Status/429?utm_source=chatgpt.com "429 Too Many Requests - HTTP - MDN Web Docs"
