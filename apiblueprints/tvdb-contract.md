According to a document from **2026-04-25**, TVDB is currently **control-plane covered** but not yet fully **MetadataRouter-ready**. The latest audit shows TVDB has an adapter, policy coverage, no direct bypasses, and two runtime-covered calls; however, several TVDB shapes required for MetadataRouter are still `ACTIVE_REQUIRED_MISSING`, including login, remote-id lookup, series translations, season episode routes, and per-episode translations.

Below is the TVDB provider contract I would use as the reviewable v1.

# TVDB provider contract — draft v1

## Provider-level policy

| Area                            | TVDB contract                                                                                                                                                                                                                                                                                        |
| ------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Provider ID                     | `TVDB`                                                                                                                                                                                                                                                                                               |
| Logical role                    | **Primary authority for non-anime TV.** Fallback/supplement for anime only after Kitsu routing fails or is explicitly disabled. Not a movie primary provider.                                                                                                                                        |
| Base host                       | `api4.thetvdb.com`                                                                                                                                                                                                                                                                                   |
| API version                     | v4                                                                                                                                                                                                                                                                                                   |
| Runtime adapter                 | `TvdbIntegrationProvider`                                                                                                                                                                                                                                                                            |
| Runtime status                  | Control-plane covered; MetadataRouter readiness incomplete.                                                                                                                                                                                                                                          |
| Primary owned fields            | TV title, overview, translations, status, season order, episode list, episode numbering, episode titles/descriptions, networks, companies, TVDB cast/company data, TVDB artwork candidates.                                                                                                          |
| Allowed supplements             | TMDB may supplement missing logos/backdrops, recommendations, reviews, and some organization/person bridges. TMDB must not silently overwrite TVDB-owned TV fields.                                                                                                                                  |
| Default work class              | `USER_VISIBLE` for identity/detail/season routes; `BACKGROUND_HYDRATION` for rail warming; `MAINTENANCE` for `/updates` and reference warming.                                                                                                                                                       |
| Default concurrency             | `maxConcurrentNetworkStarts = 1` initially. TVDB has update-driven invalidation and authenticated calls, so keep conservative until telemetry proves otherwise.                                                                                                                                      |
| Default protected header policy | `tvdb-json-bearer-v1`                                                                                                                                                                                                                                                                                |
| Login header policy             | `json-body-no-auth-v1`                                                                                                                                                                                                                                                                               |
| Auth model                      | `POST /login` with JSON body containing `apikey`; include `pin` only for user-supported keys. The response provides a bearer token valid for one month. Subsequent direct calls use `Authorization: Bearer [your-token]`.                                                                            |
| Required protected header       | `Authorization: Bearer <tvdbJwt>`                                                                                                                                                                                                                                                                    |
| Stock headers                   | Nexio default User-Agent, `Accept: application/json`. Use `Content-Type: application/json` only for JSON body calls such as `/login`.                                                                                                                                                                |
| Forbidden headers               | Cross-provider auth headers: `X-Trakt-API-Key`, `simkl-api-key`, TMDB-style `api_key`, raw API-key query auth, and any legacy TVDB API-key header on protected calls.                                                                                                                                |
| Response headers to capture     | `Retry-After` at minimum. Capture any provider rate-limit headers if present in real responses, even if not fully described in the blueprint.                                                                                                                                                        |
| 429 policy                      | Parse `Retry-After`; persist blocked window by `TVDB + credentialHash`. If no `Retry-After`, use fallback exponential backoff. Serve stale TVDB data where allowed.                                                                                                                                  |
| Credential health               | `401` from protected endpoints should mark TVDB credential health degraded and trigger token refresh/login flow; repeated failures should open a provider/credential circuit.                                                                                                                        |
| Cache-key secret policy         | Never include raw API key, PIN, or bearer token. Use `credentialHash` only where credential affects access/entitlement.                                                                                                                                                                              |
| Invalidation model              | TVDB should use both TTL and `/updates`. `/updates` is not ordinary metadata; it is a maintenance invalidation feed. The endpoint index notes that it invalidates TVDB series, episode, and reference caches and records merge aliases, but currently does not invalidate `TvdbIdentityCacheStore`.  |

---

# TVDB contract matrix

| API shape ID                       | Lifecycle for MetadataRouter                           | Endpoint shape                                      | Purpose                                                                                                                                                                              | Recommended cache policy                                                                                              | Cache-key vary inputs                                                                             | Required headers                                                                                 | 429 / backoff                                                | Audit / best-practice rule                                                                                                                                             |
| ---------------------------------- | ------------------------------------------------------ | --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `tvdb.login`                       | **Active required missing**                            | `POST /login`                                       | Create TVDB bearer token.                                                                                                                                                            | `Disabled` or `Mutation`. Do not cache as metadata. Token persistence belongs to `TvdbTokenStore` / credential store. | `credentialHash` for audit only, not metadata cache.                                              | No `Authorization`; JSON body with `apikey`, optional `pin`; Nexio UA; JSON accept/content type. | 401 → credential invalid; 429 → credential/provider backoff. | Body credentials must be redacted. `pin` must be omitted entirely when absent, not sent as null.                                                                       |
| `tvdb.remoteid.lookup`             | **Active required missing**                            | `GET /search/remoteid/{remoteId}`                   | Bridge IMDb/TMDB/raw remote IDs to TVDB entity base records. Blueprint documents this path and says it returns base records for series, movie, people, episode, company, or season.  | `CacheFirst` identity alias.                                                                                          | `remoteId`, normalized remote-id type if known, schema version.                                   | `Authorization`; Nexio UA; JSON accept.                                                          | Stale allowed; negative cache short.                         | Positive TTL 30d, stale 180d. Negative TTL 12–24h. Must store identity aliases and confidence.                                                                         |
| `tvdb.search`                      | Planned fallback                                       | `GET /search`                                       | Title-search fallback when remote-id lookup is insufficient. Blueprint supports query filters such as year, company, country, language, network, remote_id, offset, and limit.       | `CacheFirst`, short-lived.                                                                                            | normalized query, year, language, country, network/company filters, offset/limit, schema version. | `Authorization`; Nexio UA; JSON accept.                                                          | Backoff provider/credential scope.                           | TTL 6h–24h. Must record confidence; should not silently replace exact remote-id lookup.                                                                                |
| `tvdb.series.base`                 | Planned fallback                                       | `GET /series/{id}`                                  | Base series record fallback when extended lookup fails.                                                                                                                              | `CacheFirst`                                                                                                          | `tvdbSeriesId`, schema version.                                                                   | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 7d, stale 30d. Do not use when `series.extended` is available unless explicitly fallback.                                                                          |
| `tvdb.series.extended`             | **Active runtime covered**                             | `GET /series/{id}/extended`                         | Main TV title enrichment: series data, remote IDs, cast/company/network data, trailer candidates. Endpoint index classifies this as one-call multi-field enrichment.                 | `CacheFirst`                                                                                                          | `tvdbSeriesId`, language policy version if response localization is mixed, schema version.        | `Authorization`; Nexio UA; JSON accept.                                                          | Serve stale on 429/5xx where allowed.                        | TTL 7d, stale 30d. This is the TV core bundle and should be used before TMDB fallback.                                                                                 |
| `tvdb.series.translation`          | **Active required missing**                            | `GET /series/{id}/translations/{language}`          | Localized TV title and overview supplement. Endpoint index identifies this as the localized series title/overview route.                                                             | `CacheFirst`                                                                                                          | `tvdbSeriesId`, `language`, schema version.                                                       | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 7d, stale 30d. Only override TVDB core title/overview when language fallback rules pass.                                                                           |
| `tvdb.series.episodes.season_type` | **Active required missing**                            | `GET /series/{id}/episodes/{seasonType}`            | Primary non-localized season episode batch. Endpoint index calls this the primary episode list route.                                                                                | `CacheFirst`                                                                                                          | `tvdbSeriesId`, `seasonType`, `page`, optional season/episode filters if used, schema version.    | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 24h, stale 7d. Prefer this batch route over per-episode calls.                                                                                                     |
| `tvdb.series.episodes.language`    | **Active required missing**                            | `GET /series/{id}/episodes/{seasonType}/{language}` | Translated season episode batch. Blueprint shows the translated route requires `page`, `id`, `season-type`, and `lang`.                                                              | `CacheFirst`                                                                                                          | `tvdbSeriesId`, `seasonType`, `language`, `page`, schema version.                                 | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 24h, stale 7d. Use when language is not default or localized episode metadata is needed.                                                                           |
| `tvdb.episode.translation`         | **Active required missing**, but should be exceptional | `GET /episodes/{id}/translations/{language}`        | Per-episode localized overview fallback. Endpoint index explicitly marks this as many-calls-for-one-dataset when per-episode translations are missing.                               | `CacheFirst`, but low-priority / exceptional.                                                                         | `episodeId`, `language`, schema version.                                                          | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 7d, stale 30d. Audit should warn if N per-episode calls happen while a season batch route is available.                                                            |
| `tvdb.updates`                     | **Active runtime covered**                             | `GET /updates`                                      | Maintenance invalidation feed. The blueprint shows filters including type/action/page and returns update records with links.                                                         | `Disabled` or `ObserveOnly`; update cursor stored separately.                                                         | operation key: since/cursor/page/type/action, but not metadata cache key.                         | `Authorization`; Nexio UA; JSON accept.                                                          | 429 should persist backoff and defer maintenance.            | Work class `MAINTENANCE`. Should not run during playback unless policy explicitly allows. Must invalidate series/episode/reference caches and review identity aliases. |
| `tvdb.reference.artwork_types`     | Planned, global reusable                               | `GET /artwork/types`                                | Artwork type labels. Endpoint index says this is cached reference data.                                                                                                              | `CacheFirst` global reusable                                                                                          | schema version.                                                                                   | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 30d, stale 90d.                                                                                                                                                    |
| `tvdb.reference.artwork_statuses`  | Planned, global reusable                               | `GET /artwork/statuses`                             | Artwork status labels.                                                                                                                                                               | `CacheFirst` global reusable                                                                                          | schema version.                                                                                   | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 30d, stale 90d.                                                                                                                                                    |
| `tvdb.reference.genres`            | Planned, global reusable                               | `GET /genres`                                       | Genre labels.                                                                                                                                                                        | `CacheFirst` global reusable                                                                                          | schema version.                                                                                   | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 30d, stale 90d.                                                                                                                                                    |
| `tvdb.reference.languages`         | Planned, global reusable                               | `GET /languages`                                    | Language labels.                                                                                                                                                                     | `CacheFirst` global reusable                                                                                          | schema version.                                                                                   | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 30d, stale 90d.                                                                                                                                                    |
| `tvdb.reference.series_statuses`   | Planned, global reusable                               | `GET /series/statuses`                              | Series status labels.                                                                                                                                                                | `CacheFirst` global reusable                                                                                          | schema version.                                                                                   | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 30d, stale 90d.                                                                                                                                                    |
| `tvdb.reference.content_ratings`   | Planned, global reusable                               | `GET /content/ratings`                              | Age/content rating labels.                                                                                                                                                           | `CacheFirst` global reusable                                                                                          | country/language if endpoint supports it, schema version.                                         | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 30d, stale 90d.                                                                                                                                                    |
| `tvdb.reference.season_types`      | Planned, global reusable                               | `GET /seasons/types`                                | Season-order labels.                                                                                                                                                                 | `CacheFirst` global reusable                                                                                          | schema version.                                                                                   | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 30d, stale 90d.                                                                                                                                                    |
| `tvdb.reference.source_types`      | Planned, global reusable                               | `GET /sources/types`                                | Source type labels.                                                                                                                                                                  | `CacheFirst` global reusable                                                                                          | schema version.                                                                                   | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 30d, stale 90d.                                                                                                                                                    |
| `tvdb.reference.entity_types`      | Planned, global reusable                               | `GET /entities/types` or checked contract route     | Entity type labels.                                                                                                                                                                  | `CacheFirst` global reusable                                                                                          | schema version.                                                                                   | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 30d, stale 90d. Confirm exact path in checked adapter/blueprint before enforcing.                                                                                  |
| `tvdb.reference.company_types`     | Planned, global reusable                               | `GET /companies/types` or checked contract route    | Company type labels.                                                                                                                                                                 | `CacheFirst` global reusable                                                                                          | schema version.                                                                                   | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 30d, stale 90d. Confirm exact path before enforcing.                                                                                                               |
| `tvdb.person.extended`             | Planned                                                | `GET /people/{id}/extended`                         | TVDB person/cast detail. Endpoint index classifies as one-call multi-field enrichment.                                                                                               | `CacheFirst`, user-triggered                                                                                          | `peopleId`, language if supported, schema version.                                                | `Authorization`; Nexio UA; JSON accept.                                                          | Same.                                                        | TTL 7d, stale 30d. Use mainly when cast source is TVDB or TMDB bridge is unavailable.                                                                                  |

---

# Recommended TVDB header contracts

## `json-body-no-auth-v1` for `tvdb.login`

```yaml
headerPolicies:
  json-body-no-auth-v1:
    stock:
      - nexio-default-user-agent
      - json-accept
      - json-content-type

    requiredHeaders: {}

    forbiddenHeaders:
      - Authorization
      - X-Trakt-API-Key
      - simkl-api-key
      - api_key
      - X-TVDB-ApiKey

    credentialLocation:
      kind: body
      required:
        - apikey
      optional:
        - pin
      redact: true

    responseHeadersToCapture:
      - Retry-After

    userAgent:
      policy: nexio-default-user-agent
      browserLikeAllowed: false
```

The blueprint is clear that `/login` receives the API key as `"apikey"` in the JSON body, with `"pin"` only when applicable, and returns the one-month bearer token.

## `tvdb-json-bearer-v1` for protected TVDB calls

```yaml
headerPolicies:
  tvdb-json-bearer-v1:
    stock:
      - nexio-default-user-agent
      - json-accept

    requiredHeaders:
      Authorization:
        kind: bearer
        source: tvdb.jwt
        redact: true

    forbiddenHeaders:
      - X-Trakt-API-Key
      - simkl-api-key
      - api_key
      - X-TVDB-ApiKey

    forbiddenQueryCredentials:
      - apikey
      - api_key
      - token

    responseHeadersToCapture:
      - Retry-After

    contentType:
      get: absent
      post: application/json

    userAgent:
      policy: nexio-default-user-agent
      browserLikeAllowed: false
```

The checked TVDB OpenAPI declares `bearerAuth` as HTTP bearer with JWT format, which matches this protected-call policy.

---

# Recommended TVDB cache contract presets

| Cache preset                | Applies to                                                                                                      |                                  TTL | Stale after expiry | Notes                                                                                         |
| --------------------------- | --------------------------------------------------------------------------------------------------------------- | -----------------------------------: | -----------------: | --------------------------------------------------------------------------------------------- |
| `tvdb_auth_token`           | `/login` result                                                                                                 | Token expiry based, not metadata TTL |                n/a | Token valid one month per blueprint. Refresh with skew, for example 24h before expiry.        |
| `tvdb_identity_alias`       | remote-id lookup, title search positive matches, base/extended identity confirmation                            |                                  30d |               180d | Add invalidation/review when `/updates` reports merge/delete/change.                          |
| `tvdb_series_core`          | `series.extended`                                                                                               |                                   7d |                30d | Primary TV authority. Also invalidated by `/updates`.                                         |
| `tvdb_series_translation`   | `series.translation`                                                                                            |                                   7d |                30d | Language-specific.                                                                            |
| `tvdb_season_episode_batch` | season episode routes                                                                                           |                                  24h |                 7d | The current audit/source index already treats TVDB episodes as shorter-lived than title core. |
| `tvdb_episode_translation`  | per-episode translation fallback                                                                                |                                   7d |                30d | Exceptional path; warn on fan-out.                                                            |
| `tvdb_reference`            | artwork types/statuses, genres, languages, statuses, content ratings, season types, source/entity/company types |                                  30d |                90d | Global reusable.                                                                              |
| `tvdb_updates_cursor`       | `/updates` cursor/page state                                                                                    |           not cache; persisted state |                n/a | Maintenance state, not metadata response cache.                                               |
| `tvdb_person_extended`      | person extended                                                                                                 |                                   7d |                30d | User-triggered.                                                                               |

---

# TVDB best-practice rules

These are the rules I would make the audit enforce.

## 1. TV core must use `series.extended`

For non-anime TV, the core detail route should use:

```text
GET /series/{id}/extended
```

This is the one-call TV enrichment shape and powers title enrichment, remote IDs, cast/company/network data, and TVDB trailer classification.

## 2. Remote ID lookup should be durable identity cache

If the incoming row has IMDb, TMDB, or raw external IDs, TVDB identity resolution should start with:

```text
GET /search/remoteid/{remoteId}
```

The checked blueprint confirms this route and describes it as returning base records for multiple entity types.

## 3. Season episodes must use season-batch routes

Use:

```text
GET /series/{id}/episodes/{seasonType}
GET /series/{id}/episodes/{seasonType}/{language}
```

The translated route requires `page`, `id`, `season-type`, and `lang`, and returns the series plus an episode list.

## 4. Per-episode translations are exceptional

The audit should warn when:

```text
GET /episodes/{id}/translations/{language}
```

is used across many episodes if the season-language batch route is available. The endpoint index explicitly labels it as many-calls-for-one-dataset.

## 5. `/updates` is maintenance, not normal metadata

`tvdb.updates` should be classified as:

```text
workClass = MAINTENANCE
cachePolicy = Disabled or ObserveOnly
```

It should update a cursor/state store and invalidate affected cache entries. It should not be treated as `CacheFirst` metadata. The endpoint index states it is active at startup and scheduled through WorkManager, invalidating TVDB series, episode, and reference caches and recording merge aliases.

## 6. Reference data should be global reusable

Reference endpoints such as genres, languages, content ratings, season types, and artwork labels should be long-lived `CacheFirst` entries with a 30d / 90d policy, plus invalidation when the update feed indicates reference changes.

## 7. Identity cache must participate in invalidation

The endpoint index calls out a current gap: `/updates` invalidates TVDB series, episode, and reference caches but does not invalidate `TvdbIdentityCacheStore`.

I would require one of these:

```text
A. identity aliases get TTL + stale window
B. updates feed marks affected identity aliases review-needed
C. merge aliases explicitly update identity graph
```

Do not leave TVDB identity aliases indefinite and invisible to invalidation.

---

# TVDB contract YAML draft

```yaml
provider: TVDB
lifecycleStatus: ACTIVE_RUNTIME_COVERED
adapter: TvdbIntegrationProvider

providerPolicy:
  maxConcurrentNetworkStarts: 1
  minStartGapMs: 250
  desiredConcurrencyAfterValidation: 1
  promotionRequiresTelemetryDays: 7
  backoffScope: provider_plus_credential_hash
  defaultBackoffOn429Ms: 5000
  captureRetryAfter: true
  credentialHealth:
    tokenValidity: 30d
    refreshSkew: 24h
    failureStatuses:
      - 401
      - 403

headerPolicies:
  json-body-no-auth-v1:
    stock:
      - nexio-default-user-agent
      - json-accept
      - json-content-type
    forbiddenHeaders:
      - Authorization
      - X-Trakt-API-Key
      - simkl-api-key
      - api_key
      - X-TVDB-ApiKey
    credentialLocation:
      kind: body
      required:
        - apikey
      optional:
        - pin
      redact: true
    responseHeadersToCapture:
      - Retry-After

  tvdb-json-bearer-v1:
    stock:
      - nexio-default-user-agent
      - json-accept
    requiredHeaders:
      Authorization:
        kind: bearer
        source: tvdb.jwt
        redact: true
    forbiddenHeaders:
      - X-Trakt-API-Key
      - simkl-api-key
      - api_key
      - X-TVDB-ApiKey
    forbiddenQueryCredentials:
      - apikey
      - api_key
      - token
    responseHeadersToCapture:
      - Retry-After

apiShapes:
  tvdb.login:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: POST
    path: /login
    headerPolicy: json-body-no-auth-v1
    bulkShape: auth
    workClasses:
      - USER_VISIBLE
      - MAINTENANCE
    cache:
      policy: Disabled
    body:
      required:
        - apikey
      optional:
        - pin
      redact: true

  tvdb.remoteid.lookup:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /search/remoteid/{remoteId}
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: single-item
    workClasses:
      - USER_VISIBLE
      - BACKGROUND_HYDRATION
    cache:
      policy: CacheFirst
      ttl: 30d
      staleAfterExpiry: 180d
      negativeTtl: 24h
      keyIncludes:
        - remoteId
        - remoteIdType
        - schema_version

  tvdb.search:
    lifecycleStatus: PLANNED_NOT_ACTIVE
    method: GET
    path: /search
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: paginated list
    workClasses:
      - USER_VISIBLE
      - BACKGROUND_HYDRATION
    cache:
      policy: CacheFirst
      ttl: 6h
      staleAfterExpiry: 24h
      keyIncludes:
        - normalized_query
        - year
        - company
        - country
        - language
        - network
        - offset
        - limit
        - schema_version

  tvdb.series.base:
    lifecycleStatus: PLANNED_NOT_ACTIVE
    method: GET
    path: /series/{id}
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: single-item
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes:
        - tvdbSeriesId
        - schema_version

  tvdb.series.extended:
    lifecycleStatus: ACTIVE_RUNTIME_COVERED
    method: GET
    path: /series/{id}/extended
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: one-call multi-field enrichment
    workClasses:
      - USER_VISIBLE
      - BACKGROUND_HYDRATION
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes:
        - tvdbSeriesId
        - schema_version
      invalidatedBy:
        - tvdb.updates

  tvdb.series.translation:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /series/{id}/translations/{language}
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: single-item
    workClasses:
      - USER_VISIBLE
      - BACKGROUND_HYDRATION
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes:
        - tvdbSeriesId
        - language
        - schema_version
      invalidatedBy:
        - tvdb.updates

  tvdb.series.episodes.season_type:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /series/{id}/episodes/{seasonType}
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: season batch
    workClasses:
      - USER_VISIBLE
      - BACKGROUND_HYDRATION
    cache:
      policy: CacheFirst
      ttl: 24h
      staleAfterExpiry: 7d
      keyIncludes:
        - tvdbSeriesId
        - seasonType
        - page
        - season
        - episodeNumber
        - airDate
        - schema_version
      invalidatedBy:
        - tvdb.updates

  tvdb.series.episodes.language:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /series/{id}/episodes/{seasonType}/{language}
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: season batch translated
    workClasses:
      - USER_VISIBLE
      - BACKGROUND_HYDRATION
    cache:
      policy: CacheFirst
      ttl: 24h
      staleAfterExpiry: 7d
      keyIncludes:
        - tvdbSeriesId
        - seasonType
        - language
        - page
        - schema_version
      invalidatedBy:
        - tvdb.updates

  tvdb.episode.translation:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /episodes/{id}/translations/{language}
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: single-item
    bestPractice:
      warnIfManyCallsForOneSeason: true
      prefer:
        - tvdb.series.episodes.language
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes:
        - episodeId
        - language
        - schema_version
      invalidatedBy:
        - tvdb.updates

  tvdb.updates:
    lifecycleStatus: ACTIVE_RUNTIME_COVERED
    method: GET
    path: /updates
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: maintenance page
    workClasses:
      - MAINTENANCE
    cache:
      policy: ObserveOnly
      reason: update-feed-maintenance-state-not-response-cache
    operationKeyIncludes:
      - since
      - type
      - action
      - page
    effects:
      - invalidate_tvdb_series
      - invalidate_tvdb_episodes
      - invalidate_tvdb_reference
      - record_merge_aliases
      - mark_identity_aliases_review_needed

  tvdb.reference.genres:
    lifecycleStatus: PLANNED_NOT_ACTIVE
    method: GET
    path: /genres
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: reference list
    cache:
      policy: CacheFirst
      ttl: 30d
      staleAfterExpiry: 90d
      keyIncludes:
        - schema_version

  tvdb.reference.languages:
    lifecycleStatus: PLANNED_NOT_ACTIVE
    method: GET
    path: /languages
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: reference list
    cache:
      policy: CacheFirst
      ttl: 30d
      staleAfterExpiry: 90d
      keyIncludes:
        - schema_version

  tvdb.reference.content_ratings:
    lifecycleStatus: PLANNED_NOT_ACTIVE
    method: GET
    path: /content/ratings
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: reference list
    cache:
      policy: CacheFirst
      ttl: 30d
      staleAfterExpiry: 90d
      keyIncludes:
        - schema_version

  tvdb.person.extended:
    lifecycleStatus: PLANNED_NOT_ACTIVE
    method: GET
    path: /people/{id}/extended
    headerPolicy: tvdb-json-bearer-v1
    bulkShape: one-call multi-field enrichment
    workClasses:
      - USER_VISIBLE
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes:
        - peopleId
        - language
        - schema_version
```

---

# Current TVDB gaps to resolve before MetadataRouter

The latest audit says TVDB passes the **control-plane** gate, but MetadataRouter-readiness still fails for several TVDB shapes. The required missing TVDB shapes are:

```text
tvdb.login
tvdb.remoteid.lookup
tvdb.series.translation
tvdb.series.episodes.season_type
tvdb.series.episodes.language
tvdb.episode.translation
```

`tvdb.series.extended` and `tvdb.updates` are already runtime-covered, although `tvdb.updates` should get a more specific adapter method name than `TvdbIntegrationProvider.unknown`.

I would resolve these in this order:

1. **`tvdb.login`** — ensure auth token acquisition/refresh is runtime-visible, but not metadata-cached.
2. **`tvdb.remoteid.lookup`** — required for choosing TVDB identity before TV enrichment.
3. **`tvdb.series.translation`** — needed for localized title/overview.
4. **`tvdb.series.episodes.season_type`** — primary season episode batch.
5. **`tvdb.series.episodes.language`** — translated season episode batch.
6. **`tvdb.episode.translation`** — exceptional fallback; implement with audit warnings for fan-out.
7. **Rename/fix `tvdb.updates` adapter evidence** from `unknown` to a clear method such as `TvdbIntegrationProvider.fetchUpdatesPage`.

---

# Review decisions for you

| Decision                                                        | My recommendation                                                                                                                                          |
| --------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Should TVDB remain primary for all non-anime TV?                | Yes. TVDB owns TV core metadata, episodes, season order, and TV-specific fields.                                                                           |
| Should `/login` be part of IntegrationRuntime?                  | Yes, but as `Disabled`/auth operation, not metadata cache.                                                                                                 |
| Should TVDB identity aliases be long-lived?                     | Yes, but not indefinite: 30d fresh, 180d stale, plus update/merge review.                                                                                  |
| Should `series.extended` include language in the cache key?     | Not unless the endpoint response itself varies by language. Keep translations separate via `series.translation`.                                           |
| Should per-episode translations block MetadataRouter readiness? | Only if localized episode descriptions are part of the first router release. Otherwise mark it `PLANNED_NOT_ACTIVE` but keep a best-practice warning rule. |
| Should TVDB reference routes be MetadataRouter blockers?        | No. They should be `PLANNED_NOT_ACTIVE` or `GLOBAL_REUSABLE`, not core blockers.                                                                           |
| Should `/updates` run during playback?                          | No for normal maintenance. It should defer or serve existing cache while playback is active.                                                               |
| Should `/updates` invalidate identity cache?                    | Yes, at least mark affected aliases `reviewNeeded`; do not leave identity aliases permanent.                                                               |
| Should TVDB concurrency be raised above 1?                      | Not initially. Keep serial until updates/episode batch behavior is stable.                                                                                 |

My suggested next provider after TVDB would be **Kitsu**, because it is the anime primary authority and currently has the biggest durable-cache gap.


CONTRACT VALIDATION:

========================================

Yes — here is the corrected TVDB contract, validated against the uploaded `tvdb.yml` OpenAPI where possible.

Key corrections versus my previous version:

| Previous draft                                  | Corrected from blueprint                                                                         |                         |        |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------ | ----------------------- | ------ |
| `/series/{id}/episodes/{seasonType}`            | `/series/{id}/episodes/{season-type}`                                                            |                         |        |
| `/series/{id}/episodes/{seasonType}/{language}` | `/series/{id}/episodes/{season-type}/{lang}`                                                     |                         |        |
| `page` was implied                              | `page` is **required** on both season episode routes                                             |                         |        |
| `/entities/types` uncertain                     | Correct path is `/entities`                                                                      |                         |        |
| `/companies/types` uncertain                    | Correct path is `/companies/types`                                                               |                         |        |
| `series.extended` treated as simple path only   | Blueprint supports optional `meta=translations                                                   | episodes`and`short=true | false` |
| `people.extended` path                          | Correct path is `/people/{id}/extended`                                                          |                         |        |
| Auth                                            | `/login` uses body `apikey`, optional `pin`; protected calls use `Authorization: Bearer [token]` |                         |        |

The TVDB OpenAPI says `/login` requires body field `apikey`, optionally `pin`, and returns a token valid for one month; subsequent direct API calls use `Authorization: Bearer [your-token]`.  The uploaded blueprint also defines global `bearerAuth` as HTTP bearer JWT.

---

# TVDB provider contract — blueprint-validated v2

## Provider-level policy

| Area                            | TVDB contract                                                                                                                                                            |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Provider ID                     | `TVDB`                                                                                                                                                                   |
| Logical role                    | **Primary authority for non-anime TV**                                                                                                                                   |
| Base URL                        | `https://api4.thetvdb.com/v4`                                                                                                                                            |
| OpenAPI version                 | TVDB API V4, version `4.7.10`                                                                                                                                            |
| Runtime adapter                 | `TvdbIntegrationProvider`                                                                                                                                                |
| Runtime status                  | Control-plane covered; MetadataRouter readiness incomplete                                                                                                               |
| Primary owned fields            | TV title, overview, translations, status, season order, episode list, episode titles/descriptions, episode numbering, networks, companies, cast, TVDB artwork candidates |
| Default protected header policy | `tvdb-json-bearer-v1`                                                                                                                                                    |
| Login header policy             | `json-body-no-auth-v1`                                                                                                                                                   |
| Auth model                      | `POST /login` with JSON body `apikey`, optional `pin`; returns one-month bearer token                                                                                    |
| Protected auth header           | `Authorization: Bearer <tvdbJwt>`                                                                                                                                        |
| Stock headers                   | Nexio default User-Agent, `Accept: application/json`; `Content-Type: application/json` only for JSON-body calls                                                          |
| Default concurrency             | `maxConcurrentNetworkStarts = 1` initially                                                                                                                               |
| 429 policy                      | Parse `Retry-After` if present; otherwise fallback exponential provider/credential backoff                                                                               |
| Credential health               | `401` on protected calls triggers token refresh / credential degraded state                                                                                              |
| Invalidation model              | Use TTL + `/updates`; `/updates` is maintenance state, not normal metadata cache                                                                                         |

---

# TVDB contract matrix — validated

| API shape ID                       | Lifecycle                                  | Blueprint endpoint                               | Required params                                  | Optional params                                                                                                                      | Purpose                               | Cache policy                                               | Work class                             | Notes                                                                                                                  |                                        |                                                                                                                                                      |
| ---------------------------------- | ------------------------------------------ | ------------------------------------------------ | ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------- | ---------------------------------------------------------- | -------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | -------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `tvdb.login`                       | `ACTIVE_REQUIRED_MISSING`                  | `POST /login`                                    | Body: `apikey`                                   | Body: `pin`                                                                                                                          | Create TVDB token                     | `Disabled` or `Mutation`; token stored in credential store | `USER_VISIBLE` / `MAINTENANCE`         | `pin` must be omitted when absent, not sent as null.                                                                   |                                        |                                                                                                                                                      |
| `tvdb.remoteid.lookup`             | `ACTIVE_REQUIRED_MISSING`                  | `GET /search/remoteid/{remoteId}`                | Path: `remoteId`                                 | none                                                                                                                                 | Resolve IMDb/EIDR/etc. to TVDB entity | `CacheFirst`, 30d + 180d stale; negative 12–24h            | `USER_VISIBLE`, `BACKGROUND_HYDRATION` | Blueprint returns base record candidates for series, movie, people, episode, company, or season.                       |                                        |                                                                                                                                                      |
| `tvdb.search`                      | `PLANNED_NOT_ACTIVE`                       | `GET /search`                                    | none                                             | `query`, `q`, `type`, `year`, `company`, `country`, `director`, `language`, `primaryType`, `network`, `remote_id`, `offset`, `limit` | Fallback title/entity search          | `CacheFirst`, 6–24h                                        | `USER_VISIBLE`                         | Use only when exact remote-id lookup is insufficient.                                                                  |                                        |                                                                                                                                                      |
| `tvdb.series.base`                 | `PLANNED_NOT_ACTIVE`                       | `GET /series/{id}`                               | Path: `id`                                       | none                                                                                                                                 | Base series fallback                  | `CacheFirst`, 7d + 30d stale                               | `USER_VISIBLE`                         | Prefer `series.extended` for core TV metadata.                                                                         |                                        |                                                                                                                                                      |
| `tvdb.series.extended`             | `ACTIVE_RUNTIME_COVERED`                   | `GET /series/{id}/extended`                      | Path: `id`                                       | Query: `meta=translations                                                                                                            | episodes`, `short=true                | false`                                                     | Main TV core bundle                    | `CacheFirst`, 7d + 30d stale                                                                                           | `USER_VISIBLE`, `BACKGROUND_HYDRATION` | Blueprint returns `SeriesExtendedRecord`, including artwork, characters, companies, remote IDs, seasons, season types, trailers, translations, etc.  |
| `tvdb.series.translation`          | `ACTIVE_REQUIRED_MISSING`                  | `GET /series/{id}/translations/{language}`       | Path: `id`, `language`                           | none                                                                                                                                 | Localized title/overview              | `CacheFirst`, 7d + 30d stale                               | `USER_VISIBLE`                         | Use only when localized title/overview is needed and language fallback rules pass.                                     |                                        |                                                                                                                                                      |
| `tvdb.series.episodes.season_type` | `ACTIVE_REQUIRED_MISSING`                  | `GET /series/{id}/episodes/{season-type}`        | Path: `id`, `season-type`; Query: `page`         | Query: `season`, `episodeNumber`, `airDate`                                                                                          | Non-localized season episode batch    | `CacheFirst`, 24h + 7d stale                               | `USER_VISIBLE`, `BACKGROUND_HYDRATION` | Blueprint requires `page`. `season-type` examples: `default`, `official`, `dvd`, `absolute`, `alternate`, `regional`.  |                                        |                                                                                                                                                      |
| `tvdb.series.episodes.language`    | `ACTIVE_REQUIRED_MISSING`                  | `GET /series/{id}/episodes/{season-type}/{lang}` | Path: `id`, `season-type`, `lang`; Query: `page` | none                                                                                                                                 | Translated season episode batch       | `CacheFirst`, 24h + 7d stale                               | `USER_VISIBLE`, `BACKGROUND_HYDRATION` | Use this before N per-episode translation calls.                                                                       |                                        |                                                                                                                                                      |
| `tvdb.episode.translation`         | `ACTIVE_REQUIRED_MISSING`, but exceptional | `GET /episodes/{id}/translations/{language}`     | Path: `id`, `language`                           | none                                                                                                                                 | Per-episode translation fallback      | `CacheFirst`, 7d + 30d stale                               | `USER_VISIBLE`                         | Audit should warn when many of these are used while season-language batch is available.                                |                                        |                                                                                                                                                      |
| `tvdb.updates`                     | `ACTIVE_RUNTIME_COVERED`                   | `GET /updates`                                   | Query: `since`                                   | Query: `type`, `action`, `page`                                                                                                      | Maintenance invalidation feed         | `ObserveOnly` or `Disabled`; cursor persisted separately   | `MAINTENANCE`                          | Blueprint says `methodInt` indicates created/updated/deleted; merge target fields are provided for duplicates.         |                                        |                                                                                                                                                      |
| `tvdb.reference.artwork_types`     | `PLANNED_NOT_ACTIVE`                       | `GET /artwork/types`                             | none                                             | none                                                                                                                                 | Artwork type labels                   | `CacheFirst`, 30d + 90d stale                              | `BACKGROUND_HYDRATION` / `MAINTENANCE` | Reference/global reusable.                                                                                             |                                        |                                                                                                                                                      |
| `tvdb.reference.artwork_statuses`  | `PLANNED_NOT_ACTIVE`                       | `GET /artwork/statuses`                          | none                                             | none                                                                                                                                 | Artwork status labels                 | `CacheFirst`, 30d + 90d stale                              | `BACKGROUND_HYDRATION` / `MAINTENANCE` | Reference/global reusable.                                                                                             |                                        |                                                                                                                                                      |
| `tvdb.reference.genres`            | `PLANNED_NOT_ACTIVE`                       | `GET /genres`                                    | none                                             | none                                                                                                                                 | Genre labels                          | `CacheFirst`, 30d + 90d stale                              | `BACKGROUND_HYDRATION` / `MAINTENANCE` | Reference/global reusable.                                                                                             |                                        |                                                                                                                                                      |
| `tvdb.reference.languages`         | `PLANNED_NOT_ACTIVE`                       | `GET /languages`                                 | none                                             | none                                                                                                                                 | Language labels                       | `CacheFirst`, 30d + 90d stale                              | `BACKGROUND_HYDRATION` / `MAINTENANCE` | Reference/global reusable.                                                                                             |                                        |                                                                                                                                                      |
| `tvdb.reference.series_statuses`   | `PLANNED_NOT_ACTIVE`                       | `GET /series/statuses`                           | none                                             | none                                                                                                                                 | Series status labels                  | `CacheFirst`, 30d + 90d stale                              | `BACKGROUND_HYDRATION` / `MAINTENANCE` | Reference/global reusable.                                                                                             |                                        |                                                                                                                                                      |
| `tvdb.reference.content_ratings`   | `PLANNED_NOT_ACTIVE`                       | `GET /content/ratings`                           | none                                             | none                                                                                                                                 | Content/age rating labels             | `CacheFirst`, 30d + 90d stale                              | `BACKGROUND_HYDRATION` / `MAINTENANCE` | Reference/global reusable.                                                                                             |                                        |                                                                                                                                                      |
| `tvdb.reference.season_types`      | `PLANNED_NOT_ACTIVE`                       | `GET /seasons/types`                             | none                                             | none                                                                                                                                 | Season-order labels                   | `CacheFirst`, 30d + 90d stale                              | `BACKGROUND_HYDRATION` / `MAINTENANCE` | Reference/global reusable.                                                                                             |                                        |                                                                                                                                                      |
| `tvdb.reference.source_types`      | `PLANNED_NOT_ACTIVE`                       | `GET /sources/types`                             | none                                             | none                                                                                                                                 | Remote/source type labels             | `CacheFirst`, 30d + 90d stale                              | `BACKGROUND_HYDRATION` / `MAINTENANCE` | Reference/global reusable.                                                                                             |                                        |                                                                                                                                                      |
| `tvdb.reference.entity_types`      | `PLANNED_NOT_ACTIVE`                       | `GET /entities`                                  | none                                             | none                                                                                                                                 | Active entity type labels             | `CacheFirst`, 30d + 90d stale                              | `BACKGROUND_HYDRATION` / `MAINTENANCE` | Correct blueprint path is `/entities`, not `/entities/types`.                                                          |                                        |                                                                                                                                                      |
| `tvdb.reference.company_types`     | `PLANNED_NOT_ACTIVE`                       | `GET /companies/types`                           | none                                             | none                                                                                                                                 | Company type labels                   | `CacheFirst`, 30d + 90d stale                              | `BACKGROUND_HYDRATION` / `MAINTENANCE` | Correct blueprint path is `/companies/types`.                                                                          |                                        |                                                                                                                                                      |
| `tvdb.person.extended`             | `PLANNED_NOT_ACTIVE`                       | `GET /people/{id}/extended`                      | Path: `id`                                       | Query: `meta=translations`                                                                                                           | TVDB person/cast detail               | `CacheFirst`, 7d + 30d stale                               | `USER_VISIBLE`                         | Use when cast member has TVDB people ID.                                                                               |                                        |                                                                                                                                                      |

The current endpoint index confirms the same Android-relevant route set: login, remote-id lookup, search, series base/extended, season episode routes, episode translations, series translations, updates, reference endpoints, and people extended.

---

# Header contracts

## `json-body-no-auth-v1` for `tvdb.login`

```yaml
headerPolicies:
  json-body-no-auth-v1:
    stock:
      - nexio-default-user-agent
      - json-accept
      - json-content-type

    requiredHeaders: {}

    forbiddenHeaders:
      - Authorization
      - X-Trakt-API-Key
      - simkl-api-key
      - api_key
      - X-TVDB-ApiKey

    credentialLocation:
      kind: body
      required:
        - apikey
      optional:
        - pin
      redact: true

    responseHeadersToCapture:
      - Retry-After

    userAgent:
      policy: nexio-default-user-agent
      browserLikeAllowed: false
```

## `tvdb-json-bearer-v1` for protected TVDB calls

```yaml
headerPolicies:
  tvdb-json-bearer-v1:
    stock:
      - nexio-default-user-agent
      - json-accept

    requiredHeaders:
      Authorization:
        kind: bearer
        source: tvdb.jwt
        redact: true

    forbiddenHeaders:
      - X-Trakt-API-Key
      - simkl-api-key
      - api_key
      - X-TVDB-ApiKey

    forbiddenQueryCredentials:
      - apikey
      - api_key
      - token

    responseHeadersToCapture:
      - Retry-After

    contentType:
      get: absent
      post: application/json

    userAgent:
      policy: nexio-default-user-agent
      browserLikeAllowed: false
```

---

# Cache policy presets

| Cache preset                | Applies to                                                              |                TTL | Stale after expiry | Notes                                                                 |
| --------------------------- | ----------------------------------------------------------------------- | -----------------: | -----------------: | --------------------------------------------------------------------- |
| `tvdb_auth_token`           | `/login` result                                                         | token-expiry based |                n/a | Token valid for one month per blueprint. Refresh with skew, e.g. 24h. |
| `tvdb_identity_alias`       | remote-id lookup, positive search matches                               |                30d |               180d | Mark aliases review-needed on merge/delete/update.                    |
| `tvdb_series_core`          | `series.extended`                                                       |                 7d |                30d | Primary TV metadata. Invalidated by `/updates`.                       |
| `tvdb_series_translation`   | `series.translation`                                                    |                 7d |                30d | Language-specific.                                                    |
| `tvdb_season_episode_batch` | season episode routes                                                   |                24h |                 7d | Batch route preferred.                                                |
| `tvdb_episode_translation`  | per-episode translation fallback                                        |                 7d |                30d | Exceptional path; warn on fan-out.                                    |
| `tvdb_reference`            | artwork/status/genre/language/content/season/source/entity/company refs |                30d |                90d | Global reusable.                                                      |
| `tvdb_updates_cursor`       | `/updates` progress                                                     |    persisted state |                n/a | Not response-cache metadata.                                          |
| `tvdb_person_extended`      | people extended                                                         |                 7d |                30d | User-triggered.                                                       |

---

# TVDB best-practice rules

## 1. TV core must use `series.extended`

For non-anime TV, core enrichment should use:

```text
GET /series/{id}/extended
```

Optional blueprint query parameters:

```text
meta=translations|episodes
short=true|false
```

I would **not** use `short=true` for core detail because it removes characters/artworks. I would keep `meta` off by default unless you deliberately want one-call translation/episode embedding.

## 2. Remote ID lookup is the first identity bridge

Use:

```text
GET /search/remoteid/{remoteId}
```

before title search. Positive results should become durable identity aliases.

## 3. Season episodes must use batch routes

Use:

```text
GET /series/{id}/episodes/{season-type}?page=0
GET /series/{id}/episodes/{season-type}/{lang}?page=0
```

The audit should fail or warn if code fans out per episode while these routes can supply the needed season page.

## 4. Per-episode translations are exceptional

Use:

```text
GET /episodes/{id}/translations/{language}
```

only when season-language data is missing/incomplete.

## 5. `/updates` is maintenance

Use:

```text
GET /updates?since={timestamp}&page={page}
```

Optional filters:

```text
type
action
```

Do not treat `/updates` as `CacheFirst` metadata. It should update cursor state and invalidate affected caches.

## 6. Identity cache must participate in invalidation

Because `/updates` can include deleted/merged records with merge targets, TVDB identity aliases should be:

```text
TTL-bound
or marked review-needed
or updated via merge aliases
```

Do not allow indefinite invisible identity cache entries.

---

# Corrected YAML contract draft

```yaml
provider: TVDB
baseUrl: https://api4.thetvdb.com/v4
openapiVersion: 4.7.10
role: PRIMARY_TV_AUTHORITY
adapter: TvdbIntegrationProvider

providerPolicy:
  maxConcurrentNetworkStarts: 1
  minStartGapMs: 250
  backoffScope: provider_plus_credential_hash
  defaultBackoffOn429Ms: 5000
  captureRetryAfter: true
  credentialHealth:
    tokenValidity: 30d
    refreshSkew: 24h
    failureStatuses: [401, 403]

apiShapes:
  tvdb.login:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: POST
    path: /login
    headerPolicy: json-body-no-auth-v1
    cache:
      policy: Disabled
    body:
      required: [apikey]
      optional: [pin]
      redact: true

  tvdb.remoteid.lookup:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /search/remoteid/{remoteId}
    headerPolicy: tvdb-json-bearer-v1
    cache:
      policy: CacheFirst
      ttl: 30d
      staleAfterExpiry: 180d
      negativeTtl: 24h
      keyIncludes: [remoteId, remoteIdType, schema_version]

  tvdb.search:
    lifecycleStatus: PLANNED_NOT_ACTIVE
    method: GET
    path: /search
    headerPolicy: tvdb-json-bearer-v1
    query:
      optional:
        - query
        - q
        - type
        - year
        - company
        - country
        - director
        - language
        - primaryType
        - network
        - remote_id
        - offset
        - limit
    cache:
      policy: CacheFirst
      ttl: 6h
      staleAfterExpiry: 24h

  tvdb.series.extended:
    lifecycleStatus: ACTIVE_RUNTIME_COVERED
    method: GET
    path: /series/{id}/extended
    headerPolicy: tvdb-json-bearer-v1
    query:
      optional:
        meta: [translations, episodes]
        short: [true, false]
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes: [tvdbSeriesId, meta, short, schema_version]
      invalidatedBy: [tvdb.updates]

  tvdb.series.translation:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /series/{id}/translations/{language}
    headerPolicy: tvdb-json-bearer-v1
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes: [tvdbSeriesId, language, schema_version]
      invalidatedBy: [tvdb.updates]

  tvdb.series.episodes.season_type:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /series/{id}/episodes/{season-type}
    headerPolicy: tvdb-json-bearer-v1
    query:
      required: [page]
      optional: [season, episodeNumber, airDate]
    cache:
      policy: CacheFirst
      ttl: 24h
      staleAfterExpiry: 7d
      keyIncludes: [tvdbSeriesId, season-type, page, season, episodeNumber, airDate, schema_version]
      invalidatedBy: [tvdb.updates]

  tvdb.series.episodes.language:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /series/{id}/episodes/{season-type}/{lang}
    headerPolicy: tvdb-json-bearer-v1
    query:
      required: [page]
    cache:
      policy: CacheFirst
      ttl: 24h
      staleAfterExpiry: 7d
      keyIncludes: [tvdbSeriesId, season-type, lang, page, schema_version]
      invalidatedBy: [tvdb.updates]

  tvdb.episode.translation:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /episodes/{id}/translations/{language}
    headerPolicy: tvdb-json-bearer-v1
    bestPractice:
      warnIfManyCallsForOneSeason: true
      prefer: [tvdb.series.episodes.language]
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes: [episodeId, language, schema_version]
      invalidatedBy: [tvdb.updates]

  tvdb.updates:
    lifecycleStatus: ACTIVE_RUNTIME_COVERED
    method: GET
    path: /updates
    headerPolicy: tvdb-json-bearer-v1
    query:
      required: [since]
      optional: [type, action, page]
    workClasses: [MAINTENANCE]
    cache:
      policy: ObserveOnly
      reason: update-feed-maintenance-state-not-response-cache
    operationKeyIncludes: [since, type, action, page]
    effects:
      - invalidate_tvdb_series
      - invalidate_tvdb_episodes
      - invalidate_tvdb_reference
      - record_merge_aliases
      - mark_identity_aliases_review_needed

  tvdb.reference.entity_types:
    lifecycleStatus: PLANNED_NOT_ACTIVE
    method: GET
    path: /entities
    headerPolicy: tvdb-json-bearer-v1
    cache:
      policy: CacheFirst
      ttl: 30d
      staleAfterExpiry: 90d

  tvdb.reference.company_types:
    lifecycleStatus: PLANNED_NOT_ACTIVE
    method: GET
    path: /companies/types
    headerPolicy: tvdb-json-bearer-v1
    cache:
      policy: CacheFirst
      ttl: 30d
      staleAfterExpiry: 90d

  tvdb.person.extended:
    lifecycleStatus: PLANNED_NOT_ACTIVE
    method: GET
    path: /people/{id}/extended
    headerPolicy: tvdb-json-bearer-v1
    query:
      optional:
        meta: [translations]
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes: [peopleId, meta, schema_version]
```

---

# Current MetadataRouter blockers for TVDB

These remain blockers:

```text
tvdb.login
tvdb.remoteid.lookup
tvdb.series.translation
tvdb.series.episodes.season_type
tvdb.series.episodes.language
tvdb.episode.translation
```

Already runtime-covered:

```text
tvdb.series.extended
tvdb.updates
```

Required cleanup:

```text
Rename/fix tvdb.updates adapter evidence from TvdbIntegrationProvider.unknown
to TvdbIntegrationProvider.fetchUpdatesPage or equivalent.
```
