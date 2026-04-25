Yes — bundling **Trakt + Simkl** makes sense, but I would model them as one shared **tracking / user-state subsystem** with provider-specific contracts.

The key distinction:

| Area                                    | Trakt                                                            | Simkl                                                  |
| --------------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------------ |
| Scrobble / playback / continue watching | Yes, authenticated                                               | Yes, authenticated                                     |
| User library / watchlist state          | Yes, authenticated                                               | Yes, authenticated                                     |
| Authenticated catalog/discovery rows    | Yes                                                              | Limited                                                |
| Public unauthenticated JSON rails       | Not the main model                                               | Yes, separate shape                                    |
| Primary metadata authority              | No                                                               | No                                                     |
| Metadata role                           | Secondary resolver: tracking, reviews/comments, rows, user state | Secondary resolver: tracking, rows, external ID bridge |

Your endpoint index confirms that Trakt provides recommendations, calendars, trending/popular rows, lists, custom-list items, playback progress, watched progress, history, and comments; Simkl provides all-items sync, playback progress, external-content lookup, user settings, and activities freshness checkpoints.

---

# Trakt + Simkl combined provider contract — draft v1

## Shared subsystem policy

| Area                        | Contract                                                                                                                                    |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| Subsystem                   | `TRACKING_AND_USER_STATE`                                                                                                                   |
| Providers                   | `TRAKT`, `SIMKL`                                                                                                                            |
| Primary metadata authority? | No                                                                                                                                          |
| Allowed primary fields      | None                                                                                                                                        |
| Allowed outputs             | Continue-watching rows, scrobble/checkin state, watched/progress state, user lists, user catalog rows, comments/reviews, external ID bridge |
| Runtime role                | All calls must pass through `IntegrationRuntime`                                                                                            |
| Playback behavior           | Scrobble/checkin mutations are allowed during playback; background sync/prefetch is paused or deferred                                      |
| Cache posture               | Account-scoped snapshots and activity-driven freshness, not generic global metadata cache                                                   |
| Scope                       | `Profile(profileId)` or `Account(provider, accountHash)`                                                                                    |
| Secret policy               | Never cache raw OAuth tokens, client IDs, or API keys in keys; use `credentialHash` or `profileId`                                          |
| Backoff                     | Provider + account scoped                                                                                                                   |
| Concurrency                 | `maxConcurrentNetworkStarts = 1` initially for both                                                                                         |
| Shared abstraction          | `TrackingProviderAdapter` with `TraktTrackingProvider` and `SimklTrackingProvider` implementations                                          |

---

# Header contracts

## Trakt — `trakt-json-v2`

Trakt’s blueprint states required headers are `Content-Type: application/json`, `User-Agent`, `trakt-api-key`, and `trakt-api-version: 2`; OAuth is sent as `Authorization: Bearer [access_token]` for protected or user-specific calls. It also documents `429` with `Retry-After` and `X-Ratelimit` headers.

The current runtime audit already models Trakt as requiring `X-Trakt-API-Key` and `X-Trakt-API-Version`, with optional `Authorization`, default User-Agent, forbidden Simkl/TVDB/API-key leakage, and `Retry-After` capture. 

```yaml
trakt-json-v2:
  stock:
    - nexio-default-user-agent
    - json-accept
    - json-content-type
  requiredHeaders:
    X-Trakt-API-Key:
      source: trakt.clientId
      redact: true
    X-Trakt-API-Version:
      value: "2"
  optionalHeaders:
    Authorization:
      kind: bearer
      source: trakt.accessToken
      redact: true
      requiredWhen: oauthRequired
  forbiddenHeaders:
    - simkl-api-key
    - X-TVDB-ApiKey
    - api_key
  responseHeadersToCapture:
    - Retry-After
    - X-Ratelimit
    - X-Account-Locked
    - X-Account-Deactivated
```

## Simkl — `simkl-json-v1`

Simkl’s blueprint states `Content-Type: application/json` and `simkl-api-key` are required headers. Authenticated sync examples also use `Authorization: Bearer [token]` with `simkl-api-key`. Simkl documents `429` as rate limit exceeded and `412 client_id_failed` for incorrect `client_id` or total request limit exceeded.

The audit already models Simkl as requiring `simkl-api-key`, forbidding Trakt/TVDB/API-key leakage, using default User-Agent, and capturing `Retry-After`. 

```yaml
simkl-json-v1:
  stock:
    - nexio-default-user-agent
    - json-accept
    - json-content-type
  requiredHeaders:
    simkl-api-key:
      source: simkl.clientId
      redact: true
  optionalHeaders:
    Authorization:
      kind: bearer
      source: simkl.accessToken
      redact: true
      requiredWhen: tokenRequired
  forbiddenHeaders:
    - X-Trakt-API-Key
    - X-TVDB-ApiKey
    - api_key
  responseHeadersToCapture:
    - Retry-After
```

---

# Shared tracking model

Use one internal model for both providers:

```kotlin
sealed interface TrackingProvider {
    data object Trakt : TrackingProvider
    data object Simkl : TrackingProvider
}

data class ContinueWatchingEntry(
    val provider: TrackingProvider,
    val providerPlaybackId: String?,
    val mediaKind: MediaKind,
    val providerIds: ProviderIds,
    val season: Int?,
    val episode: Int?,
    val progressPercent: Double,
    val pausedAt: Instant?,
    val updatedAt: Instant?,
    val rawProviderPayloadRef: CacheRef
)

data class ScrobbleMutation(
    val provider: TrackingProvider,
    val action: ScrobbleAction,
    val providerIds: ProviderIds,
    val progressPercent: Double,
    val startedAt: Instant?,
    val pausedAt: Instant?
)

enum class ScrobbleAction {
    START,
    PAUSE,
    STOP,
    CHECKIN
}
```

The runtime contract should make both providers produce the same **Nexio result shape**, while keeping their wire APIs separate.

---

# Trakt contract matrix

| API shape ID                   | Endpoint                                       | Purpose                            | Cache policy                            | Work class                              | Notes                                                                                    |
| ------------------------------ | ---------------------------------------------- | ---------------------------------- | --------------------------------------- | --------------------------------------- | ---------------------------------------------------------------------------------------- |
| `trakt.oauth.device_code`      | `POST /oauth/device/code`                      | Device auth start                  | `Disabled`                              | `USER_VISIBLE`                          | Auth flow, no metadata cache.                                                            |
| `trakt.oauth.device_token`     | `POST /oauth/device/token`                     | Device auth polling                | `Disabled`                              | `USER_VISIBLE`                          | Respect poll interval from auth response.                                                |
| `trakt.oauth.refresh`          | `POST /oauth/token`                            | Refresh token                      | `Disabled`                              | `USER_VISIBLE` / `MAINTENANCE`          | Protected by auth mutex/circuit.                                                         |
| `trakt.calendar.shows`         | `GET /calendars/my/shows/{start_date}/{days}`  | Authenticated calendar row         | `CacheFirst`, profile-scoped            | `USER_VISIBLE` / `BACKGROUND_HYDRATION` | Runtime-covered today.                                                                   |
| `trakt.trending.movies`        | `GET /movies/trending`                         | Trakt movie rail                   | `CacheFirst`, profile/app scoped        | `USER_VISIBLE`                          | Runtime-covered today.                                                                   |
| `trakt.trending.shows`         | `GET /shows/trending`                          | Trakt show rail                    | `CacheFirst`, profile/app scoped        | `USER_VISIBLE`                          | Runtime-covered today.                                                                   |
| `trakt.popular.movies`         | `GET /movies/popular`                          | Trakt movie rail                   | `CacheFirst`                            | `USER_VISIBLE`                          | Runtime-covered today.                                                                   |
| `trakt.popular.shows`          | `GET /shows/popular`                           | Trakt show rail                    | `CacheFirst`                            | `USER_VISIBLE`                          | Runtime-covered today.                                                                   |
| `trakt.recommendations.movies` | `GET /recommendations/movies`                  | Personalized movie rail            | `CacheFirst`, profile-scoped            | `USER_VISIBLE`                          | Personalized; cache key must include profile.                                            |
| `trakt.recommendations.shows`  | `GET /recommendations/shows`                   | Personalized show rail             | `CacheFirst`, profile-scoped            | `USER_VISIBLE`                          | Runtime-covered as recommendations.                                                      |
| `trakt.popular.lists`          | `GET /lists/popular`                           | List inventory                     | `CacheFirst`                            | `USER_VISIBLE`                          | Runtime-covered today.                                                                   |
| `trakt.user.lists`             | `GET /users/{id}/lists`                        | Personal list inventory            | `CacheFirst`, profile-scoped            | `USER_VISIBLE`                          | Runtime-covered today.                                                                   |
| `trakt.user.list_items`        | `GET /users/{id}/lists/{list_id}/items/{type}` | Custom-list row items              | `CacheFirst`, profile-scoped            | `USER_VISIBLE`                          | Runtime-covered today.                                                                   |
| `trakt.playback`               | `GET /sync/playback/{type}`                    | Continue watching / resume rows    | `CacheFirst` or activity-gated snapshot | `USER_VISIBLE`                          | Active endpoint in current app; must map to shared `ContinueWatchingEntry`.              |
| `trakt.playback.remove`        | `DELETE /sync/playback/{id}`                   | Remove paused playback             | `Mutation`                              | `MUTATION_OUTBOX`                       | Allowed during playback if user action.                                                  |
| `trakt.progress.watched_show`  | `GET /shows/{id}/progress/watched`             | Watched progress / next-up context | `CacheFirst`, short TTL                 | `USER_VISIBLE`                          | Current tracking endpoint.                                                               |
| `trakt.history.episodes`       | `GET /sync/history/episodes`                   | Recent episode history             | `CacheFirst`, short TTL                 | `BACKGROUND_HYDRATION`                  | Used for next-up recency/history.                                                        |
| `trakt.scrobble.start`         | `POST /scrobble/start`                         | Start scrobble                     | `Mutation`                              | `SCROBBLE`                              | Allowed during playback.                                                                 |
| `trakt.scrobble.pause`         | `POST /scrobble/pause`                         | Save paused progress               | `Mutation`                              | `SCROBBLE`                              | Allowed during playback.                                                                 |
| `trakt.scrobble.stop`          | `POST /scrobble/stop`                          | Stop/commit progress               | `Mutation`                              | `SCROBBLE`                              | Trakt stop >80% becomes watched; 1–79% pause; <1% returns 422; duplicate can return 409. |
| `trakt.checkin`                | `POST /checkin`                                | Check-in                           | `Mutation`                              | `SCROBBLE`                              | Optional tracking action.                                                                |
| `trakt.movie.comments`         | `GET /movies/{id}/comments/{sort}`             | Movie comments/reviews             | `CacheFirst`, lazy                      | `USER_VISIBLE`                          | Current audit still shows movie comments missing while show comments are covered.        |
| `trakt.show.comments`          | `GET /shows/{id}/comments/{sort}`              | Show comments/reviews              | `CacheFirst`, lazy                      | `USER_VISIBLE`                          | Runtime-covered today.                                                                   |

---

# Simkl contract matrix

| API shape ID             | Endpoint                              | Purpose                                  | Cache policy                                           | Work class                                  | Notes                                                                                                                          |                                         |                                                             |
| ------------------------ | ------------------------------------- | ---------------------------------------- | ------------------------------------------------------ | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------- | ----------------------------------------------------------- |
| `simkl.oauth.pin`        | `GET /oauth/pin`                      | Device/pin auth start                    | `Disabled`                                             | `USER_VISIBLE`                              | Requires `client_id` query per blueprint.                                                                                      |                                         |                                                             |
| `simkl.oauth.pin_status` | `GET /oauth/pin/{USER_CODE}`          | Device/pin polling                       | `Disabled`                                             | `USER_VISIBLE`                              | Requires `client_id` query.                                                                                                    |                                         |                                                             |
| `simkl.user.settings`    | `POST /users/settings`                | User settings/account identity           | `ObserveOnly` or short `CacheFirst`                    | `USER_VISIBLE`                              | Not a metadata row source.                                                                                                     |                                         |                                                             |
| `simkl.last_activities`  | `POST /sync/activities`               | Freshness checkpoint                     | `ObserveOnly`; persist activity state separately       | `USER_VISIBLE` / `BACKGROUND_HYDRATION`     | Runtime-covered today. Simkl docs recommend checking activities first before syncing playback/all-items.                       |                                         |                                                             |
| `simkl.all_items.status` | `GET /sync/all-items/{type}/{status}` | Library/list inventory by status         | `CacheFirst` or activity-driven snapshot               | `BACKGROUND_HYDRATION`                      | Use only after activity timestamp says changed.                                                                                |                                         |                                                             |
| `simkl.all_items.type`   | `GET /sync/all-items/{type}/`         | Library/list inventory by type           | `CacheFirst` or activity-driven snapshot               | `BACKGROUND_HYDRATION`                      | Current app uses raw JSON route.                                                                                               |                                         |                                                             |
| `simkl.all_items.full`   | `GET /sync/all-items/`                | Full sync snapshot                       | `CacheFirst` or activity-driven snapshot               | `BACKGROUND_HYDRATION`                      | Use for full reconciliation / removed items.                                                                                   |                                         |                                                             |
| `simkl.playback`         | `GET /sync/playback/{type}`           | Continue watching / paused sessions      | `CacheFirst` or activity-driven snapshot               | `USER_VISIBLE`                              | Planned today; should map to shared `ContinueWatchingEntry`.                                                                   |                                         |                                                             |
| `simkl.playback.delete`  | `DELETE /sync/playback/{id}`          | Remove paused session                    | `Mutation`                                             | `MUTATION_OUTBOX`                           | Planned.                                                                                                                       |                                         |                                                             |
| `simkl.scrobble.start`   | `POST /scrobble/start`                | Start session                            | `Mutation`                                             | `SCROBBLE`                                  | Simkl stores one active scrobble session per show/movie/anime; starting a new session replaces the existing one for that item. |                                         |                                                             |
| `simkl.scrobble.pause`   | `POST /scrobble/pause`                | Save pause/progress                      | `Mutation`                                             | `SCROBBLE`                                  | Creates paused session retrievable through playback.                                                                           |                                         |                                                             |
| `simkl.scrobble.stop`    | `POST /scrobble/stop`                 | Stop/commit progress                     | `Mutation`                                             | `SCROBBLE`                                  | Similar result semantics to shared scrobble model.                                                                             |                                         |                                                             |
| `simkl.scrobble.checkin` | `POST /scrobble/checkin`              | Check-in                                 | `Mutation`                                             | `SCROBBLE`                                  | Optional tracking action.                                                                                                      |                                         |                                                             |
| `simkl.history.add`      | `POST /sync/history`                  | Add history / watched                    | `Mutation`                                             | `MUTATION_OUTBOX`                           | Used for non-playback history repair or manual mark watched.                                                                   |                                         |                                                             |
| `simkl.history.remove`   | `POST /sync/history/remove`           | Remove history/list items                | `Mutation`                                             | `MUTATION_OUTBOX`                           | User-state mutation.                                                                                                           |                                         |                                                             |
| `simkl.add_to_list`      | `POST /sync/add-to-list`              | Add item to list                         | `Mutation`                                             | `MUTATION_OUTBOX`                           | User-state mutation.                                                                                                           |                                         |                                                             |
| `simkl.discovery`        | dynamic public/provider URL           | Public JSON rails / Simkl discovery body | `ObserveOnly` now; later `CacheFirst` for public rails | `USER_VISIBLE`                              | Runtime-covered today as `fetchDiscoveryBody`.                                                                                 |                                         |                                                             |
| `simkl.summary`          | `GET /{tv                             | anime                                    | movies}/{simklId}?extended=full`                       | External ID bridge for Simkl discovery rows | `CacheFirst`, short/medium TTL                                                                                                 | `USER_VISIBLE` / `BACKGROUND_HYDRATION` | Used when Simkl discovery items lack stable IMDb/TMDB IDs.  |

---

# Shared cache policy

| Data class                   | Trakt policy                                       | Simkl policy                                                             | Reason                                                          |
| ---------------------------- | -------------------------------------------------- | ------------------------------------------------------------------------ | --------------------------------------------------------------- |
| Scrobble mutations           | `Mutation`, outbox                                 | `Mutation`, outbox                                                       | Never cache as read data. Must retry safely.                    |
| Continue watching / playback | `CacheFirst` short TTL or activity-driven snapshot | Activity-driven via `/sync/activities`, then `/sync/playback`            | Must show recent progress but avoid repeated calls.             |
| Last activities / freshness  | n/a or `ObserveOnly`                               | `ObserveOnly`, persist state separately                                  | It is a checkpoint, not metadata.                               |
| Watchlist/library snapshots  | `CacheFirst`, profile-scoped                       | Activity-driven `CacheFirst`, profile-scoped                             | User-specific.                                                  |
| Catalog/discovery rails      | `CacheFirst`, profile/app scoped                   | Public rails can be `CacheFirst`; authenticated snapshots profile-scoped | Trakt has authenticated rows; Simkl may have public JSON rails. |
| Comments/reviews             | `CacheFirst`, lazy                                 | Not equivalent core Simkl surface                                        | Secondary resolver only.                                        |
| Auth/device flows            | `Disabled`                                         | `Disabled`                                                               | Tokens go to credential store, not metadata cache.              |

Suggested TTLs:

| Shape                            |                             TTL | Stale |
| -------------------------------- | ------------------------------: | ----: |
| Trakt trending/popular/list rows |                            1–6h |   24h |
| Trakt recommendations/calendar   |                          30m–2h |   24h |
| Trakt comments                   |                          12–24h |    7d |
| Trakt playback/progress/history  |                           2–10m |    1h |
| Simkl public discovery rails     |                            1–6h |   24h |
| Simkl all-items snapshots        |    activity-driven; fallback 6h |   24h |
| Simkl playback                   | activity-driven; fallback 2–10m |    1h |

---

# Shared best-practice rules

## 1. Do not treat Trakt/Simkl as primary metadata authorities

They may create rows and provide IDs, but final metadata should still route:

```text
movie → TMDB
TV → TVDB
anime → Kitsu
```

## 2. Use a shared continue-watching model

Both providers should normalize to:

```text
ContinueWatchingEntry
```

Then your UI and MetadataRouter should not care whether resume state came from Trakt or Simkl.

## 3. Use activity gates before heavy Simkl sync

Simkl documentation explicitly recommends `/sync/activities` first, then fetching `/sync/all-items` or `/sync/playback` only when timestamps indicate changes. 

## 4. Trakt rate limits need special handling

Trakt documents separate authed GET and POST/PUT/DELETE limits, `Retry-After`, `X-Ratelimit`, and special account statuses such as locked/deactivated. Scrobble and mutation lanes should be stricter than read lanes.

## 5. Scrobble endpoints must be playback-allowed

These are not background metadata calls. They should be allowed during playback:

```text
SCROBBLE
MUTATION_OUTBOX
```

but they should still pass through runtime lanes/backoff.

## 6. Public Simkl rails must not inherit OAuth scoping

Simkl public JSON rails should use:

```text
scope = Global or App
cachePolicy = CacheFirst
headerPolicy = simkl-public-json-v1
```

Authenticated Simkl sync/playback/user-state should use:

```text
scope = Account(simkl, accountHash)
headerPolicy = simkl-json-v1
```

This is the most important distinction in your note.

---

# YAML-style combined contract draft

```yaml
trackingSubsystem:
  providers: [TRAKT, SIMKL]
  role: SECONDARY_TRACKING_AND_USER_STATE
  primaryMetadataAuthority: false

  sharedResultTypes:
    - ContinueWatchingEntry
    - ScrobbleMutation
    - TrackingActivitySnapshot
    - UserLibrarySnapshot
    - ProviderCatalogRail

providerPolicies:
  TRAKT:
    headerPolicy: trakt-json-v2
    maxConcurrentNetworkStarts: 1
    minStartGapMs: 500
    backoffScope: provider_plus_profile
    responseHeadersToCapture: [Retry-After, X-Ratelimit, X-Account-Locked, X-Account-Deactivated]
    allowDuringPlayback:
      SCROBBLE: true
      MUTATION_OUTBOX: true
      USER_VISIBLE: true
      BACKGROUND_HYDRATION: false
      PREFETCH: false

  SIMKL:
    headerPolicy: simkl-json-v1
    publicHeaderPolicy: simkl-public-json-v1
    maxConcurrentNetworkStarts: 1
    minStartGapMs: 500
    backoffScope: provider_plus_account
    responseHeadersToCapture: [Retry-After]
    allowDuringPlayback:
      SCROBBLE: true
      MUTATION_OUTBOX: true
      USER_VISIBLE: true
      BACKGROUND_HYDRATION: false
      PREFETCH: false

headerPolicies:
  trakt-json-v2:
    requiredHeaders:
      X-Trakt-API-Key: { source: trakt.clientId, redact: true }
      X-Trakt-API-Version: { value: "2" }
    optionalHeaders:
      Authorization: { kind: bearer, source: trakt.accessToken, redact: true }
    forbiddenHeaders: [simkl-api-key, X-TVDB-ApiKey, api_key]
    stock: [nexio-default-user-agent, json-accept, json-content-type]

  simkl-json-v1:
    requiredHeaders:
      simkl-api-key: { source: simkl.clientId, redact: true }
    optionalHeaders:
      Authorization: { kind: bearer, source: simkl.accessToken, redact: true }
    forbiddenHeaders: [X-Trakt-API-Key, X-TVDB-ApiKey, api_key]
    stock: [nexio-default-user-agent, json-accept, json-content-type]

  simkl-public-json-v1:
    requiredHeaders:
      simkl-api-key: { source: simkl.clientId, redact: true }
    forbiddenHeaders: [X-Trakt-API-Key, X-TVDB-ApiKey, api_key, Authorization]
    stock: [nexio-default-user-agent, json-accept]

apiShapes:
  trakt.playback:
    method: GET
    path: /sync/playback/{type}
    cache: { policy: CacheFirst, ttl: 5m, staleAfterExpiry: 1h }
    normalizedResult: ContinueWatchingEntry

  simkl.playback:
    method: GET
    path: /sync/playback/{type}
    cache: { policy: CacheFirst, ttl: activity-driven, fallbackTtl: 5m, staleAfterExpiry: 1h }
    prerequisite: simkl.last_activities
    normalizedResult: ContinueWatchingEntry

  trakt.scrobble.start:
    method: POST
    path: /scrobble/start
    cache: { policy: Mutation }
    workClass: SCROBBLE
    normalizedResult: ScrobbleMutationResult

  simkl.scrobble.start:
    method: POST
    path: /scrobble/start
    cache: { policy: Mutation }
    workClass: SCROBBLE
    normalizedResult: ScrobbleMutationResult

  simkl.last_activities:
    method: POST
    path: /sync/activities
    cache: { policy: ObserveOnly, persistedState: SimklActivityStateStore }

  simkl.discovery.public:
    method: GET
    path: "{discoveryUrl}"
    headerPolicy: simkl-public-json-v1
    scope: Global
    cache: { policy: CacheFirst, ttl: 1h, staleAfterExpiry: 24h }
```

---

# Current blockers / cleanup

1. **Add runtime specs for planned Simkl tracking shapes:**

```text
simkl.playback
simkl.scrobble.start
simkl.scrobble.pause
simkl.scrobble.stop
simkl.scrobble.checkin
```

The audit currently lists Simkl playback and scrobble as planned-not-active, not runtime-covered. 

2. **Split Simkl public discovery from authenticated user-state.**

```text
simkl.discovery.public
simkl.discovery.authenticated_or_snapshot
```

3. **Add explicit Trakt movie comments shape.**

The audit/index currently show Trakt show comments runtime-covered, while movie comments still need a first-class runtime shape. 

4. **Do not hide all Trakt mutations under `trakt.authorized_response`.**

Break into auditable shapes:

```text
trakt.scrobble.start
trakt.scrobble.pause
trakt.scrobble.stop
trakt.checkin
trakt.watchlist.add
trakt.watchlist.remove
trakt.history.add
trakt.history.remove
trakt.playback.remove
```

5. **Rename the shared outbox.**

```text
TraktMutationOutboxCoordinator
```

should become:

```text
ProviderMutationOutboxCoordinator
```

with adapters:

```text
TraktMutationAdapter
SimklMutationAdapter
```

This matches your point that scrobble and continue-watching systems should have the same product shape even though provider APIs differ.
