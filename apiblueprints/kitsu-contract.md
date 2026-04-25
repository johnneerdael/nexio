Yes. For **Kitsu**, I would treat this as the highest-priority metadata provider after TMDB/TVDB, because it is the **primary anime authority** but still has the biggest cache/readiness gap.

# Kitsu provider contract — draft v1

## Provider-level policy

| Area                | Kitsu contract                                                                   |
| ------------------- | -------------------------------------------------------------------------------- |
| Provider ID         | `KITSU`                                                                          |
| Logical role        | **Primary authority for anime**                                                  |
| Base role in router | If item is classified as anime, route to Kitsu before TMDB/TVDB                  |
| Auth                | Public JSON by default; no required auth for current metadata flows              |
| Header policy       | `public-json-v1`                                                                 |
| Required headers    | Nexio default User-Agent, `Accept: application/json`                             |
| Forbidden headers   | `X-Trakt-API-Key`, `simkl-api-key`, `X-TVDB-ApiKey`, `api_key`                   |
| 429 policy          | Capture `Retry-After`; provider-level cooldown; serve stale where available      |
| Default concurrency | `maxConcurrentNetworkStarts = 1` initially                                       |
| Cache posture       | Must become durable `CacheFirst`; current Kitsu data is mostly request-time only |
| Main gap            | Advanced detail routes are still missing runtime specs                           |

The audit confirms Kitsu is already **runtime-covered** for trending discovery, anime core, anime episodes, and advanced detail, but the latest MetadataRouter-readiness view still marks several Kitsu API shapes as missing runtime specs: `kitsu.castings`, `kitsu.anime_staff`, `kitsu.anime_productions`, `kitsu.media_relationships`, and `kitsu.search.text`. 

# Kitsu contract matrix

| API shape ID                | Endpoint                                                     | Purpose                                                      | Router lifecycle               | Cache policy                        | Cache key                                                 | Headers          | Notes                                                                    |
| --------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------ | ----------------------------------- | --------------------------------------------------------- | ---------------- | ------------------------------------------------------------------------ |
| `kitsu.discovery.trending`  | `GET /trending/anime`                                        | Built-in trending anime rows                                 | Active runtime covered         | `ObserveOnly` or `CacheFirst` later | `kitsu:discovery:trending:{page}:{sort}:{region?}`        | `public-json-v1` | Powers built-in trending anime row.                                      |
| `kitsu.discovery.anime`     | `GET /anime`                                                 | Popular, highest-rated, genre/search rows                    | Planned / missing runtime spec | `CacheFirst`                        | `kitsu:discovery:anime:{query/sort/category/page}`        | `public-json-v1` | Used for highest-rated, popular, and genre-scoped anime rows.            |
| `kitsu.search.text`         | `GET /anime?filter[text]=...`                                | Anime identity fallback                                      | Active required missing        | `CacheFirst`, short TTL             | `kitsu:search:text:{normalizedTitle}:{year?}`             | `public-json-v1` | Only use when source provenance says anime but ID mapping failed.        |
| `kitsu.anime.core`          | `GET /anime/{id}`                                            | Anime title core                                             | Active runtime covered         | `CacheFirst`                        | `kitsu:anime:{id}:core:{schemaVersion}`                   | `public-json-v1` | Owns anime title, synopsis, dates, status, poster/cover candidates.      |
| `kitsu.anime.episodes`      | `GET /anime/{id}/episodes`                                   | Episode titles, descriptions, thumbnails, air dates, runtime | Active runtime covered         | `CacheFirst`                        | `kitsu:anime:{id}:episodes:limit:{limit}:offset:{offset}` | `public-json-v1` | Paginated; current code walks pages up to a cap.                         |
| `kitsu.castings`            | `GET /castings?filter[mediaId]=...&include=person,character` | Characters, voice actors, actor photos                       | Active required missing        | `CacheFirst`                        | `kitsu:anime:{id}:castings:{includeVersion}`              | `public-json-v1` | Android uses top-level filtered castings, not nested relationship path.  |
| `kitsu.anime_staff`         | `GET /anime/{id}/anime-staff`                                | Staff                                                        | Active required missing        | `CacheFirst`                        | `kitsu:anime:{id}:staff:{page?}`                          | `public-json-v1` | Advanced detail; lazy after hero render.                                 |
| `kitsu.anime_productions`   | `GET /anime/{id}/anime-productions`                          | Production companies/studios                                 | Active required missing        | `CacheFirst`                        | `kitsu:anime:{id}:productions:{page?}`                    | `public-json-v1` | Advanced detail; lazy.                                                   |
| `kitsu.media_relationships` | `GET /anime/{id}/media-relationships`                        | Related anime titles                                         | Active required missing        | `CacheFirst`                        | `kitsu:anime:{id}:relationships:{page?}`                  | `public-json-v1` | Source for anime “more like this”.                                       |
| `kitsu.installments`        | `GET /anime/{id}/installments`                               | Franchise/installment ordering                               | Not active                     | Do not enable yet                   | n/a                                                       | `public-json-v1` | Explicitly avoided because live validation was unreliable.               |
| `kitsu.characters`          | `GET /characters`                                            | Character metadata                                           | Indirect only                  | Covered through castings include    | n/a                                                       | `public-json-v1` | Do not call directly unless later needed.                                |
| `kitsu.people`              | `GET /people`                                                | Person metadata                                              | Indirect only                  | Covered through include graph       | n/a                                                       | `public-json-v1` | Accessed indirectly through castings/staff includes.                     |

# Recommended cache policy

| Data class               |         TTL | Stale window | Reason                                   |
| ------------------------ | ----------: | -----------: | ---------------------------------------- |
| Anime core               |      7 days |      30 days | Core metadata changes slowly.            |
| Episodes                 |    24 hours |       7 days | Episode data can change while airing.    |
| Completed-show episodes  |      7 days |      30 days | Once complete, lower churn.              |
| Advanced detail          |      7 days |      30 days | Cast/staff/related data changes slowly.  |
| Discovery rows           |  6–12 hours |     24 hours | Trending/popular rows are volatile.      |
| Text search              |    24 hours |       7 days | Useful negative/positive identity cache. |
| Negative identity result | 12–24 hours |         none | Avoid repeatedly searching bad mappings. |

Current audit says Kitsu title enrichment, episode metadata, and advanced detail are not durably cached in the old metadata cache path; Kitsu-native data is effectively request-time only, unlike TVDB/TMDB fallbacks. 

# Field ownership

| Anime field               | Kitsu owns? | Supplement rule                                                                       |
| ------------------------- | ----------: | ------------------------------------------------------------------------------------- |
| Canonical title           |         Yes | TMDB/TVDB must not overwrite unless Kitsu unresolved                                  |
| Native / alternate titles |         Yes | Kitsu primary                                                                         |
| Synopsis                  |         Yes | Do not silently replace with TMDB/TVDB overview                                       |
| Poster / cover candidates |         Yes | Top-Posters may rewrite final poster; RPDB should not pretend Kitsu IDs are supported |
| Episode list              |         Yes | TVDB/TMDB fallback only after explicit Kitsu failure                                  |
| Episode numbering         |         Yes | Never overwrite Kitsu numbering silently                                              |
| Characters / voice actors |         Yes | Kitsu primary                                                                         |
| Staff                     |         Yes | Kitsu primary                                                                         |
| Productions/studios       |         Yes | Kitsu primary                                                                         |
| Related anime             |         Yes | Kitsu primary                                                                         |
| Logos/trailers            |     No/weak | TMDB can supplement where explicitly allowed                                          |

# Required fixes before MetadataRouter sign-off

1. Add runtime specs for:

```text
kitsu.discovery.anime
kitsu.search.text
kitsu.castings
kitsu.anime_staff
kitsu.anime_productions
kitsu.media_relationships
```

2. Split the current broad `kitsu.advanced_detail` into auditable sub-shapes or make its composed plan explicit:

```text
advanced_detail =
  castings
  anime_staff
  anime_productions
  media_relationships
```

3. Make Kitsu episode pages durably cached.

4. Make anime fallback typed, not silent:

```kotlin
KITSU_SUCCESS
KITSU_ID_UNRESOLVED
KITSU_PROVIDER_BACKOFF
KITSU_ENDPOINT_FAILED
KITSU_EXPLICIT_FALLBACK_TVDB
KITSU_EXPLICIT_FALLBACK_TMDB
```

5. Fix router ownership so IMDb-only anime is not suppressed before Kitsu gets a chance. The audit notes that current pre-router classification can suppress Kitsu routing for IMDb-only anime movies. 

# YAML-style contract draft

```yaml
provider: KITSU
role: PRIMARY_ANIME_AUTHORITY
headerPolicy: public-json-v1

providerPolicy:
  maxConcurrentNetworkStarts: 1
  captureRetryAfter: true
  defaultBackoffOn429Ms: 5000
  allowDuringPlayback:
    USER_VISIBLE: true
    PLAYBACK_RESOLUTION: true
    BACKGROUND_HYDRATION: false
    MAINTENANCE: false

fieldOwnership:
  owns:
    - anime.title
    - anime.native_titles
    - anime.synopsis
    - anime.status
    - anime.age_rating
    - anime.episode_length
    - anime.episode_list
    - anime.episode_numbering
    - anime.characters
    - anime.voice_actors
    - anime.staff
    - anime.productions
    - anime.related_titles
    - anime.poster_candidates
    - anime.cover_candidates
  supplementsAllowed:
    - topposters.poster_rewrite
    - tmdb.trailer_fallback
    - tmdb.logo_fallback
    - tmdb.person_bridge
  forbiddenSilentOverwrites:
    - tmdb.title
    - tvdb.title
    - tmdb.overview
    - tvdb.overview
    - tvdb.episodes
    - tmdb.episodes

apiShapes:
  kitsu.anime.core:
    method: GET
    path: /anime/{id}
    status: ACTIVE_RUNTIME_COVERED
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      key: kitsu:anime:{id}:core:{schemaVersion}

  kitsu.anime.episodes:
    method: GET
    path: /anime/{id}/episodes
    status: ACTIVE_RUNTIME_COVERED
    cache:
      policy: CacheFirst
      ttl: 24h
      staleAfterExpiry: 7d
      key: kitsu:anime:{id}:episodes:{limit}:{offset}:{schemaVersion}

  kitsu.search.text:
    method: GET
    path: /anime
    query:
      filter[text]: required
    status: ACTIVE_REQUIRED_MISSING
    cache:
      policy: CacheFirst
      ttl: 24h
      staleAfterExpiry: 7d
      negativeTtl: 24h

  kitsu.castings:
    method: GET
    path: /castings
    query:
      filter[mediaId]: required
      include: person,character
    status: ACTIVE_REQUIRED_MISSING
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d

  kitsu.anime_staff:
    method: GET
    path: /anime/{id}/anime-staff
    status: ACTIVE_REQUIRED_MISSING
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d

  kitsu.anime_productions:
    method: GET
    path: /anime/{id}/anime-productions
    status: ACTIVE_REQUIRED_MISSING
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d

  kitsu.media_relationships:
    method: GET
    path: /anime/{id}/media-relationships
    status: ACTIVE_REQUIRED_MISSING
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
```

My recommendation: **make Kitsu the first provider where you enforce the full MetadataRouter contract**, because the current asymmetry is clearest there: TVDB/TMDB fallbacks cache better than the actual anime authority.


CONTRACT VALIDATION:

========================================

Yes — here is the corrected **Kitsu provider contract**, validated against the uploaded `kitsu.apib` as far as the blueprint can validate the shapes.

Important correction versus the previous version:

| Previous draft                                                              | Blueprint-validated correction                                                                                                                                                                                                                                                             |
| --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `Accept: application/json`                                                  | Kitsu is JSON:API. Use `Accept: application/vnd.api+json`.                                                                                                                                                                                                                                 |
| `Content-Type` absent from GETs                                             | Kitsu docs say all requests should include `Content-Type: application/vnd.api+json` as well as `Accept`. I would still allow GET without body to omit `Content-Type` if your HTTP stack standardizes that, but the strict Kitsu blueprint policy should include it.                        |
| `GET /anime/{id}/episodes` as if directly documented as a collection route  | The blueprint documents top-level `GET /episodes` and relationship links under `/anime/{id}/episodes`. Android uses the nested related path, which is valid through JSON:API relationship links.                                                                                           |
| `GET /anime/{id}/anime-staff`, `/anime-productions`, `/media-relationships` | The blueprint documents top-level collections and nested related links. Android uses the nested related paths.                                                                                                                                                                             |
| `GET /castings?filter[mediaId]=...`                                         | Blueprint documents top-level `GET /castings`; it lists `mediaId` but marks it deprecated in favor of `anime_id`, `manga_id`, or `drama_id`. So the contract should prefer `filter[anime_id]` where possible, while allowing current `filter[mediaId]` as legacy/current Android behavior. |

The Kitsu APiB states that Kitsu uses JSON:API and that requests should include `Accept: application/vnd.api+json` and `Content-Type: application/vnd.api+json`. It also defines filtering as `filter[attribute]=value` and notes bracketed query names must be percent-encoded in real URLs. The checked endpoint index confirms the Android-used Kitsu route set, including trending anime, anime collection, anime resource, episode relationship, castings with include graph, nested anime staff, nested anime productions, and nested media relationships.

# Kitsu provider contract — blueprint-validated v2

## Provider-level policy

| Area                  | Kitsu contract                                                                                                                                                                                                                                                 |
| --------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Provider ID           | `KITSU`                                                                                                                                                                                                                                                        |
| Logical role          | **Primary authority for anime**                                                                                                                                                                                                                                |
| Base URL              | `https://kitsu.io/api/edge`                                                                                                                                                                                                                                    |
| API style             | JSON:API                                                                                                                                                                                                                                                       |
| Runtime adapter       | `KitsuIntegrationProvider`, `KitsuDiscoveryIntegrationProvider`                                                                                                                                                                                                |
| Runtime status        | Control-plane covered; MetadataRouter readiness incomplete                                                                                                                                                                                                     |
| Primary owned fields  | Anime canonical title, native/alternate titles, synopsis, status, age rating, episode length, episode list, episode numbering, episode titles/descriptions, characters, voice actors, staff, productions/studios, related anime, Kitsu poster/cover candidates |
| Auth                  | Public metadata GET routes do not require auth. Optional auth may be used only where product explicitly wants user/auth-enhanced behavior.                                                                                                                     |
| Default header policy | `kitsu-jsonapi-public-v1`                                                                                                                                                                                                                                      |
| Required headers      | Strict blueprint mode: `Accept: application/vnd.api+json`, `Content-Type: application/vnd.api+json`, Nexio default User-Agent                                                                                                                                  |
| Forbidden headers     | `X-Trakt-API-Key`, `simkl-api-key`, `X-TVDB-ApiKey`, `api_key`, TMDB `Authorization`, TVDB bearer token                                                                                                                                                        |
| 429 policy            | Capture `Retry-After` if present; provider-level cooldown; serve stale where possible                                                                                                                                                                          |
| Default concurrency   | `maxConcurrentNetworkStarts = 1` initially                                                                                                                                                                                                                     |
| Cache posture         | Durable `CacheFirst` required for core/episodes/advanced detail                                                                                                                                                                                                |
| Main gap              | Advanced Kitsu routes need individual auditable specs or a composed sub-plan under `kitsu.advanced_detail`                                                                                                                                                     |

The current runtime audit shows Kitsu is already runtime-covered for `kitsu.discovery.trending`, `kitsu.anime.core`, `kitsu.anime.episodes`, and `kitsu.advanced_detail`; however, MetadataRouter readiness still flags `kitsu.castings`, `kitsu.anime_staff`, `kitsu.anime_productions`, `kitsu.media_relationships`, and `kitsu.search.text` as `ACTIVE_REQUIRED_MISSING`.

---

# Kitsu contract matrix — blueprint-validated

| API shape ID                | Lifecycle                                                                         | Blueprint / JSON:API shape                                                                                                 | Required params                                                                                                           | Optional params                                                                                                                                | Purpose                                                                 | Cache policy                                                                 | Work class                             | Notes                                                                                                                                                                                                                                       |
| --------------------------- | --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- | ---------------------------------------------------------------------------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `kitsu.discovery.trending`  | `ACTIVE_RUNTIME_COVERED`                                                          | `GET /trending/anime`                                                                                                      | none                                                                                                                      | JSON:API pagination if supported by implementation                                                                                             | Built-in trending anime row                                             | `CacheFirst` preferred; current audit may still show `ObserveOnlyOrMutation` | `USER_VISIBLE`, `BACKGROUND_HYDRATION` | Blueprint documents `GET /trending/anime`; endpoint index says it powers built-in Kitsu trending anime.                                                                                                                                     |
| `kitsu.discovery.anime`     | `PLANNED_NOT_ACTIVE` or `ACTIVE_REQUIRED` if built-in anime rails are first-scope | `GET /anime`                                                                                                               | none                                                                                                                      | `filter[...]`, `sort`, `page[limit]`, `page[offset]`; blueprint lists anime filters including `season`, `seasonYear`, `streamers`, `ageRating` | Popular, highest-rated, genre/search/discovery anime rows               | `CacheFirst`                                                                 | `USER_VISIBLE`, `BACKGROUND_HYDRATION` | Endpoint index says Android uses `GET anime` for highest-rated, popular, and genre-scoped anime rows.                                                                                                                                       |
| `kitsu.search.text`         | `ACTIVE_REQUIRED_MISSING`                                                         | `GET /anime?filter[text]=...`                                                                                              | `filter[text]`                                                                                                            | `page[limit]`, `page[offset]`                                                                                                                  | Anime identity fallback when provenance says anime but ID mapping fails | `CacheFirst`, short positive/negative TTL                                    | `USER_VISIBLE`                         | Blueprint documents filtering syntax and text-search example; use only after direct ID/bridge resolution fails.                                                                                                                             |
| `kitsu.anime.core`          | `ACTIVE_RUNTIME_COVERED`                                                          | `GET /anime/{id}`                                                                                                          | Path `id`                                                                                                                 | `include=categories,mediaRelationships.destination` if you keep the current include strategy; fields selection if added later                  | Anime title core                                                        | `CacheFirst`                                                                 | `USER_VISIBLE`, `BACKGROUND_HYDRATION` | Endpoint index says this is anime title enrichment and a public metadata route with optional auth enhancement.                                                                                                                              |
| `kitsu.anime.episodes`      | `ACTIVE_RUNTIME_COVERED`                                                          | JSON:API related path `GET /anime/{id}/episodes`; blueprint also documents top-level `GET /episodes`                       | Path `id`; pagination should be explicit                                                                                  | `page[limit]`, `page[offset]`; if using top-level fallback, prefer `filter[anime_id]`                                                          | Episode titles, descriptions, thumbnails, air dates, runtime            | `CacheFirst`                                                                 | `USER_VISIBLE`, `BACKGROUND_HYDRATION` | Endpoint index says current code walks Kitsu episode pages up to a cap.                                                                                                                                                                     |
| `kitsu.castings`            | `ACTIVE_REQUIRED_MISSING`                                                         | `GET /castings`                                                                                                            | Prefer `filter[anime_id]` when possible; current Android uses `filter[mediaId]`; include graph required for current model | `include=person,character`, `filter[language]`, `filter[featured]`, `filter[isCharacter]`, pagination                                          | Characters, voice actors, actor photos                                  | `CacheFirst`                                                                 | `USER_VISIBLE`, `BACKGROUND_HYDRATION` | Blueprint documents top-level `GET /castings`; route-shape correction says Android calls filtered top-level collection, not nested relationship path. `mediaId` is documented but deprecated in favor of `anime_id`/`manga_id`/`drama_id`.  |
| `kitsu.anime_staff`         | `ACTIVE_REQUIRED_MISSING`                                                         | JSON:API related path `GET /anime/{id}/anime-staff`; blueprint also documents top-level `GET /anime-staff`                 | Path `id` if nested; pagination should be explicit                                                                        | `page[limit]`, `page[offset]`, `include=person` if implementation uses included person graph                                                   | Anime staff                                                             | `CacheFirst`                                                                 | `USER_VISIBLE`, `BACKGROUND_HYDRATION` | Route-shape correction confirms Android uses the nested related path, while APiB documents top-level collection and nested related link.                                                                                                    |
| `kitsu.anime_productions`   | `ACTIVE_REQUIRED_MISSING`                                                         | JSON:API related path `GET /anime/{id}/anime-productions`; blueprint also documents top-level `GET /anime-productions`     | Path `id` if nested; pagination should be explicit                                                                        | `page[limit]`, `page[offset]`, `include=producer` if used                                                                                      | Production companies/studios                                            | `CacheFirst`                                                                 | `USER_VISIBLE`, `BACKGROUND_HYDRATION` | Route-shape correction confirms Android uses nested related path.                                                                                                                                                                           |
| `kitsu.media_relationships` | `ACTIVE_REQUIRED_MISSING`                                                         | JSON:API related path `GET /anime/{id}/media-relationships`; blueprint also documents top-level `GET /media-relationships` | Path `id` if nested; pagination should be explicit                                                                        | `page[limit]`, `page[offset]`, `include=destination` if related title data is needed                                                           | Related anime titles / more-like-this                                   | `CacheFirst`                                                                 | `USER_VISIBLE`, `BACKGROUND_HYDRATION` | Endpoint index says this is the current source for anime related titles.                                                                                                                                                                    |
| `kitsu.installments`        | `NOT_ACTIVE`                                                                      | `GET /anime/{id}/installments` relationship-style path if used                                                             | n/a                                                                                                                       | pagination                                                                                                                                     | Franchise/installment ordering                                          | Do not enable yet                                                            | n/a                                    | Endpoint index says this is intentionally avoided because live validation was unreliable.                                                                                                                                                   |
| `kitsu.characters`          | `INDIRECT_ONLY`                                                                   | `GET /characters`                                                                                                          | n/a                                                                                                                       | included via castings                                                                                                                          | Character metadata                                                      | Covered via `kitsu.castings` include graph                                   | n/a                                    | Do not call directly unless a future UI needs standalone character detail.                                                                                                                                                                  |
| `kitsu.people`              | `INDIRECT_ONLY`                                                                   | `GET /people`                                                                                                              | n/a                                                                                                                       | included via castings/staff                                                                                                                    | Person metadata                                                         | Covered via include graph                                                    | n/a                                    | Current app does not use Kitsu for person-detail pages.                                                                                                                                                                                     |
| `kitsu.producers`           | `INDIRECT_ONLY`                                                                   | `GET /producers`                                                                                                           | n/a                                                                                                                       | included via anime-productions                                                                                                                 | Producer/studio metadata                                                | Covered via anime-productions include graph                                  | n/a                                    | Used indirectly through anime productions.                                                                                                                                                                                                  |
| `kitsu.franchises`          | `NOT_ACTIVE`                                                                      | `GET /franchises`                                                                                                          | n/a                                                                                                                       | pagination                                                                                                                                     | Franchise grouping                                                      | Do not enable yet                                                            | n/a                                    | No current UI surface.                                                                                                                                                                                                                      |

---

# Header contract

## `kitsu-jsonapi-public-v1`

```yaml
headerPolicies:
  kitsu-jsonapi-public-v1:
    stock:
      - nexio-default-user-agent
      - jsonapi-accept
      - jsonapi-content-type

    requiredHeaders:
      Accept:
        value: application/vnd.api+json
      Content-Type:
        value: application/vnd.api+json

    optionalHeaders:
      Authorization:
        kind: bearer
        source: kitsu.oauthAccessToken
        redact: true
        allowedOnlyWhen: authEnhancedRequest

    forbiddenHeaders:
      - X-Trakt-API-Key
      - simkl-api-key
      - X-TVDB-ApiKey
      - api_key
      - X-TMDB-API-Key

    forbiddenQueryCredentials:
      - api_key
      - apikey
      - token

    responseHeadersToCapture:
      - Retry-After

    userAgent:
      policy: nexio-default-user-agent
      browserLikeAllowed: false
```

If your HTTP client strongly avoids `Content-Type` on GETs, I would define a pragmatic variant:

```yaml
kitsu-jsonapi-public-get-v1:
  requiredHeaders:
    Accept: application/vnd.api+json
  recommendedHeaders:
    Content-Type: application/vnd.api+json
```

But the strict blueprint reading says Kitsu JSON:API requests should contain both. The key is that the audit must make this an explicit choice, not inherit generic `public-json-v1`.

---

# Cache policy presets

| Cache preset             | Applies to                                  |              TTL | Stale after expiry | Notes                                                               |
| ------------------------ | ------------------------------------------- | ---------------: | -----------------: | ------------------------------------------------------------------- |
| `kitsu_discovery_rail`   | trending and anime discovery rows           |            6–12h |                24h | Trending/popular changes more often.                                |
| `kitsu_search_identity`  | `filter[text]` search                       |              24h |                 7d | Negative result TTL 12–24h.                                         |
| `kitsu_anime_core`       | `anime.core`                                |               7d |                30d | Primary anime authority.                                            |
| `kitsu_episode_pages`    | `anime.episodes` pages                      | 24h while airing |                 7d | Completed shows can be promoted to 7d/30d.                          |
| `kitsu_advanced_detail`  | castings, staff, productions, relationships |               7d |                30d | Lazy after hero render.                                             |
| `kitsu_indirect_include` | characters/people/producers via includes    |   same as parent |     same as parent | Do not cache independently unless promoted to first-class resource. |

Current field-source analysis shows Kitsu episode, cast/character, staff, productions, and related-title data are request-time only today and not durably cached, while fallback provider routes often are cached. That is the asymmetry this contract should fix. 

---

# Field ownership

| Anime field                            |                                                  Kitsu owns? | Supplement rule                                                                            |
| -------------------------------------- | -----------------------------------------------------------: | ------------------------------------------------------------------------------------------ |
| Canonical title                        |                                                          Yes | TMDB/TVDB must not overwrite unless Kitsu unresolved                                       |
| Native / alternate titles              |                                                          Yes | Kitsu primary                                                                              |
| Synopsis                               |                                                          Yes | Do not silently replace with TMDB/TVDB overview                                            |
| Age rating                             |                                            Yes where present | Fallback may fill missing only                                                             |
| Runtime / episode length               | Yes, but distinguish anime movie runtime from episode length | Fallback can fill missing runtime only                                                     |
| Poster / cover candidates              |                                                          Yes | Top-Posters may rewrite final poster; RPDB should not pretend Kitsu-only IDs are supported |
| Episode list                           |                                                          Yes | TVDB/TMDB fallback only after explicit Kitsu failure                                       |
| Episode numbering                      |                                                          Yes | Never silently overwrite Kitsu numbering                                                   |
| Episode titles/descriptions/thumbnails |                                                          Yes | Fallback only after explicit Kitsu failure/missing data policy                             |
| Characters / voice actors              |                                                          Yes | Kitsu primary                                                                              |
| Staff                                  |                                                          Yes | Kitsu primary                                                                              |
| Productions/studios                    |                                                          Yes | Kitsu primary                                                                              |
| Related anime                          |                                                          Yes | Kitsu primary                                                                              |
| Reviews                                |                              No current Kitsu route in Nexio | Anime detail currently disables non-anime reviews flow                                     |
| Trailer/teaser/recap                   |                                No native Kitsu provider path | TMDB/TVDB/Streailer/fallback YouTube may supplement explicitly                             |

The field-source matrix calls out that anime related titles come from Kitsu `anime/{id}/media-relationships`, that anime uses Kitsu related titles rather than TMDB recommendations, and that Kitsu staff/production/cast paths are request-time only today.

---

# Required fixes before MetadataRouter sign-off

1. Add or split runtime specs for:

```text
kitsu.discovery.anime
kitsu.search.text
kitsu.castings
kitsu.anime_staff
kitsu.anime_productions
kitsu.media_relationships
```

2. Replace broad `kitsu.advanced_detail` as the only auditable shape with either:

```text
kitsu.advanced_detail composedPlan:
  - kitsu.castings
  - kitsu.anime_staff
  - kitsu.anime_productions
  - kitsu.media_relationships
```

or individual runtime specs for each sub-call.

3. Update the header policy from generic `public-json-v1` to Kitsu-specific JSON:API:

```text
kitsu-jsonapi-public-v1
```

4. Move Kitsu castings toward non-deprecated filter usage:

```text
preferred: filter[anime_id]
current-compatible: filter[mediaId]
```

Keep `filter[mediaId]` only as `legacyCurrentAndroid=true` until the Retrofit interface is changed.

5. Make Kitsu episode pages durably cached.

6. Make anime fallback typed:

```kotlin
KITSU_SUCCESS
KITSU_ID_UNRESOLVED
KITSU_PROVIDER_BACKOFF
KITSU_ENDPOINT_FAILED
KITSU_EXPLICIT_FALLBACK_TVDB
KITSU_EXPLICIT_FALLBACK_TMDB
```

7. Fix router ownership so IMDb-only anime is not suppressed before Kitsu gets a chance.

---

# YAML-style contract draft

```yaml
provider: KITSU
baseUrl: https://kitsu.io/api/edge
apiStyle: JSON_API
role: PRIMARY_ANIME_AUTHORITY
adapter: KitsuIntegrationProvider

providerPolicy:
  maxConcurrentNetworkStarts: 1
  minStartGapMs: 250
  backoffScope: provider
  defaultBackoffOn429Ms: 5000
  captureRetryAfter: true
  allowDuringPlayback:
    USER_VISIBLE: true
    PLAYBACK_RESOLUTION: true
    BACKGROUND_HYDRATION: false
    MAINTENANCE: false

headerPolicies:
  kitsu-jsonapi-public-v1:
    stock:
      - nexio-default-user-agent
      - jsonapi-accept
      - jsonapi-content-type
    requiredHeaders:
      Accept:
        value: application/vnd.api+json
      Content-Type:
        value: application/vnd.api+json
    optionalHeaders:
      Authorization:
        kind: bearer
        source: kitsu.oauthAccessToken
        redact: true
        allowedOnlyWhen: authEnhancedRequest
    forbiddenHeaders:
      - X-Trakt-API-Key
      - simkl-api-key
      - X-TVDB-ApiKey
      - api_key
      - X-TMDB-API-Key
    responseHeadersToCapture:
      - Retry-After

fieldOwnership:
  owns:
    - anime.title
    - anime.native_titles
    - anime.alternate_titles
    - anime.synopsis
    - anime.status
    - anime.age_rating
    - anime.episode_length
    - anime.episode_list
    - anime.episode_numbering
    - anime.episode_titles
    - anime.episode_descriptions
    - anime.episode_thumbnails
    - anime.characters
    - anime.voice_actors
    - anime.staff
    - anime.productions
    - anime.related_titles
    - anime.poster_candidates
    - anime.cover_candidates
  supplementsAllowed:
    - topposters.poster_rewrite
    - tmdb.trailer_fallback
    - tvdb.trailer_fallback
    - tmdb.logo_fallback
    - tmdb.person_bridge
    - tmdb.company_bridge
  forbiddenSilentOverwrites:
    - tmdb.title
    - tvdb.title
    - tmdb.overview
    - tvdb.overview
    - tvdb.episodes
    - tmdb.episodes
    - tmdb.episode_numbering
    - tvdb.episode_numbering

apiShapes:
  kitsu.discovery.trending:
    lifecycleStatus: ACTIVE_RUNTIME_COVERED
    method: GET
    path: /trending/anime
    headerPolicy: kitsu-jsonapi-public-v1
    bulkShape: paginated list
    workClasses: [USER_VISIBLE, BACKGROUND_HYDRATION]
    cache:
      policy: CacheFirst
      ttl: 6h
      staleAfterExpiry: 24h
      keyIncludes: [page_limit, page_offset, schema_version]

  kitsu.discovery.anime:
    lifecycleStatus: PLANNED_NOT_ACTIVE
    method: GET
    path: /anime
    headerPolicy: kitsu-jsonapi-public-v1
    bulkShape: paginated list
    query:
      optional:
        - filter[season]
        - filter[seasonYear]
        - filter[streamers]
        - filter[ageRating]
        - filter[categories]
        - sort
        - page[limit]
        - page[offset]
    cache:
      policy: CacheFirst
      ttl: 6h
      staleAfterExpiry: 24h
      keyIncludes: [canonical_query, page_limit, page_offset, schema_version]

  kitsu.search.text:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /anime
    headerPolicy: kitsu-jsonapi-public-v1
    bulkShape: paginated list
    query:
      required:
        - filter[text]
      optional:
        - page[limit]
        - page[offset]
    cache:
      policy: CacheFirst
      ttl: 24h
      staleAfterExpiry: 7d
      negativeTtl: 24h
      keyIncludes: [normalized_text, page_limit, page_offset, schema_version]

  kitsu.anime.core:
    lifecycleStatus: ACTIVE_RUNTIME_COVERED
    method: GET
    path: /anime/{id}
    headerPolicy: kitsu-jsonapi-public-v1
    bulkShape: single-item
    query:
      optional:
        - include
        - fields[anime]
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes: [kitsuId, include_profile, fields_profile, schema_version]

  kitsu.anime.episodes:
    lifecycleStatus: ACTIVE_RUNTIME_COVERED
    method: GET
    path: /anime/{id}/episodes
    headerPolicy: kitsu-jsonapi-public-v1
    bulkShape: paginated related collection
    query:
      required:
        - page[limit]
        - page[offset]
      optional:
        - sort
    cache:
      policy: CacheFirst
      ttl: 24h
      staleAfterExpiry: 7d
      completedShowTtl: 7d
      completedShowStaleAfterExpiry: 30d
      keyIncludes: [kitsuId, page_limit, page_offset, sort, schema_version]

  kitsu.castings:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /castings
    headerPolicy: kitsu-jsonapi-public-v1
    bulkShape: list batch
    query:
      requiredPreferred:
        - filter[anime_id]
      requiredLegacyCurrent:
        - filter[mediaId]
      required:
        - include
      includeMustContain:
        - person
        - character
      optional:
        - filter[language]
        - filter[featured]
        - filter[isCharacter]
        - page[limit]
        - page[offset]
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes: [kitsuId, filter_mode, include_profile, language, featured, isCharacter, page_limit, page_offset, schema_version]

  kitsu.anime_staff:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /anime/{id}/anime-staff
    headerPolicy: kitsu-jsonapi-public-v1
    bulkShape: paginated related collection
    query:
      optional:
        - include
        - page[limit]
        - page[offset]
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes: [kitsuId, include_profile, page_limit, page_offset, schema_version]

  kitsu.anime_productions:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /anime/{id}/anime-productions
    headerPolicy: kitsu-jsonapi-public-v1
    bulkShape: paginated related collection
    query:
      optional:
        - include
        - page[limit]
        - page[offset]
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes: [kitsuId, include_profile, page_limit, page_offset, schema_version]

  kitsu.media_relationships:
    lifecycleStatus: ACTIVE_REQUIRED_MISSING
    method: GET
    path: /anime/{id}/media-relationships
    headerPolicy: kitsu-jsonapi-public-v1
    bulkShape: paginated related collection
    query:
      optional:
        - include
        - page[limit]
        - page[offset]
    cache:
      policy: CacheFirst
      ttl: 7d
      staleAfterExpiry: 30d
      keyIncludes: [kitsuId, include_profile, page_limit, page_offset, schema_version]

  kitsu.installments:
    lifecycleStatus: NOT_ACTIVE
    method: GET
    path: /anime/{id}/installments
    headerPolicy: kitsu-jsonapi-public-v1
    reason: live_validation_unreliable_currently_not_consumed
```

---

# Current MetadataRouter blockers for Kitsu

Already runtime-covered:

```text
kitsu.discovery.trending
kitsu.anime.core
kitsu.anime.episodes
kitsu.advanced_detail
```

But still needs auditable sub-shapes before MetadataRouter sign-off:

```text
kitsu.search.text
kitsu.castings
kitsu.anime_staff
kitsu.anime_productions
kitsu.media_relationships
```

Required cleanup:

```text
1. Replace generic public-json-v1 with kitsu-jsonapi-public-v1.
2. Split kitsu.advanced_detail into explicit sub-shapes or record its composed sub-plan.
3. Prefer filter[anime_id] over deprecated filter[mediaId] for castings.
4. Ensure episode and advanced-detail payloads are durably CacheFirst.
```
