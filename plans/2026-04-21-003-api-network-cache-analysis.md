# Nexio Metadata-Provider API / Cache / Concurrency Analysis

**Date:** 2026-04-21
**Scope:** Current-state audit only — no recommendations.
**Services audited:**
`trakt.tv`, `themoviedb.org`, `thetvdb.com`, `kitsu.app` (actually `kitsu.io/api/edge/`), `simkl.com`, `mdblist.com`, `api.top-streaming.stream`, `ratingposterdb.com`.

> **Purpose.** Map every metadata-provider API call the platform makes, and for each call answer:
> 1. When does it go to the network vs. in-memory vs. disk cache?
> 2. Are calls ever blocked/throttled (playback, boot, credential health)?
> 3. How are requests scheduled — serial gate, per-host dispatcher, single connection, parallel?
> This is the foundation for a later optimisation phase.

---

## 0. Executive summary (read this first)

- **One shared OkHttp dispatcher** (`default`, 64 total / 5 per host) serves almost every metadata provider: TMDB, TVDB, Kitsu, RPDB, TopPosters/top-streaming, OMDB, GitHub, debrid, IntroDb, AniSkip, ARM, AnimeSkip, Trailer. Trakt, Simkl, MDBList, AddonCatalog, and Benchmark derive their own client from that same default via `newBuilder()`, so **they share the same ConnectionPool and Dispatcher** (they only override cache/headers). `playback` and `addonStreams` are the only truly independent clients (12/host and 32/host).
  Evidence: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt:97–101, 127–165, 169–230, 234–267, 270–300, 541–560`.
- **HTTP disk cache asymmetry (important)** — the 50 MB `http_cache` at `cacheDir/http_cache` is **enabled** for TMDB, TVDB, Kitsu, RPDB, TopPosters, OMDB, GitHub, debrid, IntroDb, AniSkip, ARM, AnimeSkip, Trailer, but **explicitly disabled** (via `disableDiskCacheForGetRequests()`) for Trakt, Simkl, MDBList, AddonCatalog, Benchmark. `NetworkModule.kt:59–83`.
- **Only Trakt and Simkl have request gates** — both are 500 ms serial `Mutex`-based gates (`TraktRequestGate.kt`, `SimklRequestGate.kt`). All authorized Trakt/Simkl requests flow through `TraktAuthService.executeAuthorizedRequest` / `SimklAuthService.executeAuthorizedRequest` and therefore serialize, one at a time, with a 500 ms minimum gap between request starts. Auth endpoints bypass their respective gates (device code/token/refresh/revoke).
- **No other service has any per-service rate limit or semaphore at the HTTP layer.** Internal semaphores exist for caller-side fan-out (MDBList providers: `Semaphore(4)`; MDBList episode seasons: `Semaphore(3)`; Trakt personal-list items: `Semaphore(3)`; Trakt episode validation: `Semaphore(2)`; Simkl metadata hydration: `Semaphore(4)`), but these do not constrain the raw network layer.
- **Playback gating is narrow.** `HomePlaybackWorkGate` cancels a specific set of **home-screen enrichment jobs** when playback starts — continue-watching enrichment, hero/focus enrichment, TMDB focus enrichment, external meta prefetch, adjacent prefetch, metadata-flush, trailer preview, trailer metadata availability, poster reconcile, library observers. **It does not gate Trakt/Simkl outbox drains, the TVDB update worker, MDBList discovery refresh, or direct Kitsu/TMDB/TVDB calls initiated from a non-home screen.** Scrobble/checkin calls are *expected* to run during playback.
- **Boot/cold-start fan-out** is dominated by the TVDB update coordinator (fired immediately from `NexioApplication.onCreate`), plus a synthetic 20-second "startup refresh gate" inside `TraktDiscoveryService` and `TraktLibraryService` that serves disk-first, then refreshes. Kitsu, TMDB, RPDB/TopPosters make no boot calls — they're on-demand only.
- **Read-through app caches** exist separately from HTTP cache: `MetadataDiskCacheStore` (TMDB 7d, TVDB 7d, TVDB episodes 24h, TVDB refs 30d, TMDB videos 12h), `TvdbIdentityCacheStore`, `TraktLibrarySnapshotStore`, `SimklLibrarySnapshotStore`, `MDBListDiscoverySnapshotStore`, `CatalogDiskCacheStore`, `TvdbMergeAliasStore`, `KitsuAuthDataStore`, `TraktAuthDataStore`, `SimklAuthDataStore`, plus `MetadataDiskCacheStore`-independent in-memory maps inside `TmdbService`, `TmdbMetadataService`, `MDBListRepository`, `TraktProgressService`, `TraktDiscoveryService`, `TraktLibraryService`, `SimklLibraryService`, `SimklProgressService`.
- **Top-Streaming anomaly.** The user listed `api.top-streaming.stream` as a platform service. In the current code, `PosterRatingsUrlResolver.buildTopPostersUrl()` hardcodes `https://api.top-posters.com/` — `api.top-streaming.stream` appears **only in test fixtures**. Either the URL is user-configurable somewhere not yet wired through, or the production base has changed from what the code expects. Flagged for verification in §9.
- **Surprising couplings.** The `SimklScrobbleService`, `SimklLibraryService`, and `SimklProgressService` all enqueue mutations via `TraktMutationOutboxCoordinator` — i.e. Simkl mutations ride on the Trakt outbox infrastructure. `NetworkModule` exposes `@Named("simkl") OkHttpClient` with `disableDiskCacheForGetRequests()` but Kitsu (which uses the default client) does **not** disable disk cache, creating a deliberate asymmetry between the three user-account providers.

---

## 1. Shared HTTP infrastructure

### 1.1 OkHttpClient catalogue

| Named client | Source | Dispatcher | ConnectionPool | Disk cache | Consumers |
|---|---|---|---|---|---|
| *(unnamed default)* `provideOkHttpClient` | `NetworkModule.kt:95–101` | OkHttp default **64 / 5** | OkHttp default **5 idle / 5 min keep-alive** | **50 MB** `cacheDir/http_cache` | TMDB, TVDB, Kitsu, KitsuOauth, RPDB, TopPosters, OMDB, GitHub, IntroDb, Trailer, AniSkip, ARM, AnimeSkip, RealDebrid, Premiumize, TorBox, EasyDebrid, ImdbSearch, CustomImdbClient |
| `@Named("trakt")` `provideTraktOkHttpClient` | `NetworkModule.kt:168–230` | inherits default (64/5) | inherits default pool | **disabled** via `.cache(null)` + GET request/response interceptors | Trakt Retrofit |
| `@Named("simkl")` `provideSimklOkHttpClient` | `NetworkModule.kt:233–267` | inherits default | inherits default pool | **disabled** | Simkl Retrofit |
| `@Named("mdblist")` `provideMDBListOkHttpClient` | `NetworkModule.kt:542–550` | inherits default | inherits default pool | **disabled** | MDBList Retrofit |
| `@Named("addonCatalog")` | `NetworkModule.kt:269–277` | inherits default | inherits default pool | **disabled** | the "placeholder" Retrofit (`provideRetrofit`) used for per-addon Retrofit instances |
| `@Named("addonStreams")` | `NetworkModule.kt:289–300` | **128 / 32** (override) | inherits default pool | inherits (disk cache enabled on the derived client, but disabled because the source derives from default; in practice responses are not cacheable anyway) | Addon stream fetches |
| `@Named("playback")` | `NetworkModule.kt:127–165` | dedicated **64 / 12** | dedicated **5 idle / 5 min** | — (no Cache installed) | Playback MediaSource, benchmark |
| `@Named("benchmark")` | `NetworkModule.kt:308–316` | reuses playback | reuses playback | **disabled** | Direct-discard benchmark transport, 4-minute call timeout |

Because named clients derive via `okHttpClient.newBuilder()`, **they share the same Dispatcher instance and ConnectionPool instance as the default**. This means Trakt/Simkl/MDBList requests **count against the same 64-total / 5-per-host quota** as TMDB/TVDB/Kitsu/RPDB/etc. The only way this doesn't bite in practice is that Trakt and Simkl are already gated to 1-in-flight by their gates, and most metadata providers are different hosts.

### 1.2 `disableDiskCacheForGetRequests()` semantics

Helper at `NetworkModule.kt:59–83`:

- `.cache(null)` on the builder — the shared 50 MB cache is not attached to this client.
- Application interceptor adds `Cache-Control: no-cache` and `Pragma: no-cache` to every GET request.
- Network interceptor removes `Pragma` from responses and sets `Cache-Control: no-store` for GET responses.

Net effect: for Trakt / Simkl / MDBList / AddonCatalog, the 50 MB disk cache is entirely bypassed and any downstream OkHttp cache heuristics are disabled.

### 1.3 Retrofit base URLs

| Service | BaseUrl | Client |
|---|---|---|
| TMDB | `MetadataProviderConfig.tmdbBaseUrl()` (default `https://api.themoviedb.org/3/`) | default |
| TVDB | `MetadataProviderConfig.tvdbBaseUrl()` | default |
| Kitsu | `https://kitsu.io/api/edge/` | default |
| Kitsu OAuth | `https://kitsu.io/api/oauth/` | default |
| Trakt | `BuildConfig.TRAKT_API_URL` or `https://api.trakt.tv/` | `@Named("trakt")` |
| Simkl | `BuildConfig.SIMKL_API_URL` or `https://api.simkl.com/` | `@Named("simkl")` |
| MDBList | `https://api.mdblist.com/` | `@Named("mdblist")` |
| RPDB | `https://api.ratingposterdb.com/` | default |
| TopPosters | `https://api.top-posters.com/` | default |

---

## 2. Per-service call analysis

### 2.1 Trakt (`api.trakt.tv`)

**Client:** `@Named("trakt")` — no HTTP disk cache. 500 ms serial gate via `TraktRequestGate`.

**Endpoint groups (inventoried from `TraktApi.kt`, adapters, and `TraktProgressService`):**

| Group | Endpoints | Caller surface | Caching |
|---|---|---|---|
| Auth | `POST oauth/device/code`, `POST oauth/device/token`, `POST oauth/token` (refresh), `POST oauth/revoke`, `GET users/settings` | `TraktAuthService` | `TraktAuthDataStore` (DataStore). **Bypasses `TraktRequestGate`** (pre-auth). |
| Discovery | `GET calendars/my/shows/{start}/{days}`, `GET movies/trending`, `GET shows/trending`, `GET movies/popular`, `GET shows/popular`, `GET lists/popular`, `GET recommendations/{type}`, `DELETE recommendations/{type}/{id}` | `TraktDiscoveryService` (+ mutation adapter) | disk `TraktDiscoverySnapshotStore` per profile; in-memory `rawProfileSnapshots`/`profileSnapshots` `MutableStateFlow`. Refresh throttled to 30 s, **20 s boot startup gate**. |
| Library | `GET sync/watchlist/{type}`, `GET users/{id}/lists`, `GET users/{id}/lists/{list_id}/items/{type}`, `POST sync/watchlist`, `POST sync/watchlist/remove`, `POST users/{id}/lists`, `PUT …`, `DELETE …`, `POST …/items`, `POST …/items/remove`, `POST users/{id}/lists/reorder` | `TraktLibraryService`, `TraktLibraryMutationExecutor` | disk `TraktLibrarySnapshotStore` + in-memory StateFlows; mutations go through outbox (see §6). **20 s boot startup gate.** |
| Progress / history | `GET sync/last_activities`, `GET sync/playback/{type}`, `GET sync/watched/{type}`, `GET sync/history/episodes`, `GET shows/{id}/progress/watched`, `GET shows/{id}/seasons/{s}`, `GET shows/{id}/seasons/{s}/episodes/{e}`, `GET users/{id}/stats`, `GET users/hidden/{section}`, `POST sync/history`, `POST sync/history/remove`, `DELETE sync/playback/{id}` | `TraktProgressService`, `TraktNextUpValidationPolicy`, `TraktProgressMutationExecutor` | in-memory: `cachedActivities` (10 s), `cachedUserStats` (indefinite), `optimisticProgress` (3 min); watched-movies/shows 10 min, history 5 min, per-show progress 5 min. |
| Scrobble / checkin | `POST scrobble/start`, `scrobble/pause`, `scrobble/stop`, `POST checkin` | `TraktScrobbleMutationAdapter` | network only; runs during playback via outbox. |
| Comments | `GET movies/{id}/comments/{sort}`, `GET shows/{id}/comments/{sort}` | `MetaDetailsViewModel.loadTraktReviews` (ViewModel calls `executeAuthorizedRequest` directly — no service layer) | none (network only). |

**Concurrency.** `TraktRequestGate.acquire` holds a `Mutex` for the entire block (`TraktRequestGate.kt:41–54`), so Trakt is effectively **1 in-flight with ≥ 500 ms start-to-start spacing**. The default 64/5 dispatcher is irrelevant for Trakt because the gate serializes upstream.

**Auth.** Token refresh is both proactive (60 s leeway inside `getValidAccessToken`) and lazy (on 401 in `executeAuthorizedRequest`); refresh protected by `tokenRefreshMutex`. A circuit breaker trips on 401/403/423/repeated transient errors with 5–60 min exponential backoff.

**Playback/boot.**
- Scrobbles/checkins run during playback by design (outbox).
- `NexioApplication.onCreate` does **not** hit Trakt; init restores `TraktLibrarySnapshotStore` / `TraktDiscoverySnapshotStore` from disk.
- `TraktLibraryService` and `TraktDiscoveryService` apply a `startupRefreshGateMs = 20_000L` before refreshing from network on first observation.
- `TraktMutationOutboxCoordinator.init` launches `worker.recoverExpiredLeases()` + `requestDrain()` — the outbox can fire mutations on boot.

**Surprises.**
- Auth endpoints (device code/token/refresh/revoke, `users/settings`) bypass the gate — they call `traktApi.*()` directly.
- `MetaDetailsViewModel` calls `traktAuthService.executeAuthorizedRequest` for comments from the ViewModel layer — no service class, no cache.
- `cachedActivities` is a plain `@Volatile` (not mutex-protected); safe in practice because the gate serializes all Trakt traffic.
- `TraktNextUpValidationPolicy` uses `Semaphore(2)` for episode validation but each request still goes through the gate, so the semaphore provides no additional throughput.

### 2.2 Simkl (`api.simkl.com`)

**Client:** `@Named("simkl")` — no HTTP disk cache. 500 ms serial gate via `SimklRequestGate`. `client_id`, `app-name`, `app-version` injected as query params; `simkl-api-key` header injected.

**Endpoints (from `SimklApi.kt`):**

| Group | Endpoints | Caller |
|---|---|---|
| Device auth | `GET oauth/pin`, `GET oauth/pin/{user_code}` | `SimklAuthService.startPinAuth`/`pollPin` |
| User | `POST users/settings` | `SimklAuthService.fetchUserSettings` |
| Activity / delta | `POST sync/activities` | `SimklLibraryService`, `SimklProgressService` |
| Library | `GET sync/all-items/{type}/{status}`, `GET sync/all-items/{type}/`, `GET sync/all-items/`, `POST sync/add-to-list`, `POST sync/history`, `POST sync/history/remove` | `SimklLibraryService`, `SimklLibraryMutationAdapter` |
| Playback | `GET sync/playback/{type}`, `DELETE sync/playback/{id}` | `SimklProgressService`, `SimklProgressHistoryMutationAdapter` |
| Scrobble | `POST scrobble/start`, `POST scrobble/pause`, `POST scrobble/stop`, `POST scrobble/checkin` | `SimklScrobbleMutationAdapter` |

**Concurrency.** Gate is the same pattern as Trakt — `Mutex.withLock` around the entire request, 500 ms spacing. `SimklProgressService.executeRequest` builds raw OkHttp requests (not Retrofit) and **also** acquires the gate (`SimklProgressService.kt:623`). No bypass paths observed.

**Caching.**
- `SimklLibrarySnapshotStore` (disk, profile-scoped): full library snapshot with activity timestamps.
- `SimklProgressSyncStateStore` (disk): activity timestamps, playback IDs.
- In-memory: `SimklLibraryService.snapshotState`/`metadataState` `MutableStateFlow`; `SimklProgressRuntimeState` registry; `metadataFetchSemaphore = Semaphore(4)` for downstream addon metadata hydration (not Simkl API itself).
- No TTLs — refresh driven by `/sync/activities` timestamps. `ensureFresh` has a 6-hour idle floor and a 3-second debounce for progress refreshes.

**Scrobble throttling.** `SimklScrobbleService.shouldSkip` (`minSendIntervalMs = 8_000L`, plus 1.5% progress window) drops redundant scrobbles.

**Playback/boot.**
- Scrobbles expected during playback.
- No boot auto-fetch; snapshots restored from disk, refresh triggered on home/library observers.
- Outbox mutations (library/progress/scrobble) **ride on `TraktMutationOutboxCoordinator`** — Simkl and Trakt share one outbox coordinator instance.

### 2.3 TMDB (`api.themoviedb.org`)

**Client:** default — 50 MB HTTP disk cache **enabled**. No gate, no interceptor, no per-service semaphore. `api_key` as query param (`TmdbService.requireApiKey()` resolves from `MetadataApiKeyResolver.tmdbCredential()` or `TmdbSettingsDataStore`).

**Endpoints (25 in `TmdbApi.kt`):** configuration, find, external IDs (movie/tv), videos (movie/tv/season), details (movie/tv), credits, images, release dates / content ratings, recommendations, reviews, collection, season details, person details, person combined credits, company details, network details, discover movie/tv by company, discover tv by network.

**Caching stack.**
- **OkHttp HTTP cache (50 MB)** — honours TMDB's `Cache-Control: public, max-age=28800` (8h) by default.
- **In-memory** (`TmdbService.kt:52–59`): `imdbToTmdbCache`, `tmdbToImdbCache` (`ConcurrentHashMap`), plus in-flight dedup via `CompletableDeferred` (`imdbToTmdbInFlight`/`tmdbToImdbInFlight`). Process-lifetime, no TTL.
- **In-memory enrichment** (`TmdbMetadataService.kt:88–94`): `enrichmentCache`, `episodeSeasonCache` (+ in-flight `CompletableDeferred`), `personCache`, `moreLikeThisCache`, `reviewsCache`. All `ConcurrentHashMap`, process-lifetime, no TTL.
- **Disk `MetadataDiskCacheStore`** (prefix `tmdb::`): enrichment 7 d, videos 12 h. Writes debounced 250 ms.

**Concurrency.** `TmdbMetadataService` fans out credits + images + recommendations in parallel inside `coroutineScope { async … awaitAll() }`. Shares the default 64/5 pool with every other non-Trakt/Simkl service.

**Playback/boot.** Not called on boot — lazy on screen demand. Home enrichment is cancelled during playback via `HomePlaybackWorkGate`. TMDB fallback path in `TvMetadataRouter` can still fire during player init if a detail screen is opened.

### 2.4 TVDB (`api4.thetvdb.com`)

**Client:** default — 50 MB HTTP disk cache enabled. No gate. Bearer JWT in `Authorization` header (effectively poisons the HTTP cache for authenticated endpoints because most TVDB responses Vary on Authorization).

**Endpoints (21 in `TvdbApi.kt`):** login, search, searchByRemoteId, series base/extended, series episodes, translations, episode translation, updates, artwork types/statuses, genres, languages, series statuses, content ratings, season types, source types, entity types, company types, personExtended.

**Caching stack.**
- **OkHttp HTTP cache:** enabled but marginal for most authorized endpoints; reference endpoints (`/artwork/types`, `/genres`, `/languages`) benefit most.
- **Bearer token**: `TvdbTokenStore` persists JWT; TTL 30 days, refresh skew 24 h; proactive refresh before expiry.
- **Disk `MetadataDiskCacheStore`**: `tvdb::` 7 d, `tvdb_episode::` 24 h, `tvdb_ref::` 30 d. Serves stale on network failure (read-through + stale-on-error).
- **`TvdbIdentityCacheStore`**: persistent schema-v2 map of identity resolutions (IMDB → TVDB series etc.).
- **`TvdbMergeAliasStore`**: persists TVDB series merge-to-id redirects discovered from `/updates`.
- **`TvdbUpdateStateStore`**: cursor for `/updates`.
- **In-memory reference data**: inside `TvdbReferenceDataService` (lifetime of process, invalidated by `TvdbCacheInvalidator.invalidateReferences()`).

**Update coordinator.** `TvdbUpdateCoordinator` (`NetworkModule` / `TvdbUpdateCoordinator.kt:54–131`):
- Immediate catch-up on `NexioApplication.onCreate` via `appScope.launch { catchUpUpdates(STARTUP) }`.
- Periodic WorkManager job every 12 h.
- Gated by `TvdbCredentialHealth.canCallTvdb()` — if credential invalid, skip.
- Warms reference data via `TvdbReferenceDataService.warmCoreReferences()`.
- `TvdbUpdateProcessor` loops `getUpdates(since, page)` sequentially (no parallel pages) and feeds `TvdbCacheInvalidator` to invalidate disk entries per series.

**Concurrency.** Shares the default 64/5 pool with TMDB, Kitsu, RPDB, etc. No per-service semaphore; `TvdbMetadataService` can fan out season translations/episodes inside a `coroutineScope` block. Sequential pagination for updates.

**Playback/boot.** TVDB update catch-up runs unconditionally on app start. Metadata enrichment on screen demand; cancelled by `HomePlaybackWorkGate` when triggered from home surface, but direct-from-details-screen calls during playback are not gated.

### 2.5 Kitsu (`kitsu.io/api/edge`)

**Client:** default — 50 MB HTTP disk cache **enabled**. No gate. Bearer token optional (only used for NSFW / private content).

**Endpoints used in production (from `KitsuApi.kt`):**
- `GET /anime/{id}` — single title with `include=categories,mediaRelationships.destination`.
- `GET /anime/{id}/episodes` — paginated (limit=20).
- `POST /oauth/token` (via `KitsuAuthApi`) — password grant and refresh-token grant.

Documented endpoint inventory (per `plans/2026-04-21-kitsu-get-endpoint-index.md`) lists 126 GET endpoints available, of which the current app uses 2.

**Caching stack.**
- **OkHttp HTTP cache** — Kitsu is the only "account-like" provider that participates in the 50 MB shared cache. No explicit `Cache-Control` override; server headers apply.
- **No in-memory cache** inside `KitsuMetadataService` — each call is a fresh IO request (HTTP cache decides).
- **`KitsuAuthDataStore`** persists tokens.

**Concurrency.** No gate; no semaphore. Multiple screens (home enrichment, `MetaDetailsViewModel`, player metadata) can call Kitsu in parallel, bounded only by default 64/5.

**Call sites.** `KitsuMetadataService.fetchEnrichment` is invoked by `TvMetadataRouter.fetchEnrichment` (for anime). `fetchEpisodeEnrichment` is driven by season tab loads. `validAccessToken()` is called per request and can trigger a refresh (`POST /oauth/token`, grant_type=refresh_token).

**Playback/boot.** No boot call. Home enrichment for anime items can fire, and is cancelled via `HomePlaybackWorkGate` during playback. Detail-screen calls are not gated.

### 2.6 MDBList (`api.mdblist.com`)

**Client:** `@Named("mdblist")` — no HTTP disk cache. No gate; `apikey` as query param (from `MDBListSettingsDataStore`).

**Endpoints (from `MDBListApi.kt`):**
- `GET /user` — credential verification.
- `POST /rating/{mediaType}/{ratingType}` — batched ratings (provider = imdb/trakt/tmdb/letterboxd/tomatoes/audience/metacritic).
- `GET @Url` — generic (lists, users, list detail).
- `GET @Url` + `@QueryMap` — paginated list items.

**Caller surfaces.**
- `MDBListDiscoveryService` — home/discovery rails. Uses `MDBListDiscoverySnapshotStore` (profile-keyed). 20 s startup refresh gate (opt-in via `diskFirstHomeStartupEnabled`). Minimum 30 s refresh interval (`refreshMutex`).
- `MDBListRepository` — rating enrichment for detail screens / home cards. In-memory `ConcurrentHashMap` ratings cache (30-min TTL, keyed by `mediaType:imdbId:providerHash:apiKeyHashCode`). Episode-rating cache TTL: 7 d if all episodes present, 30 min on partial/miss. In-flight dedup via `CompletableDeferred` + mutex.

**Concurrency.** `MDBListRepository.fetchRatings` uses `Semaphore(4)` for parallel provider requests within one `getRatingsForMeta` call. Episode ratings use `Semaphore(3)` per season. No HTTP-layer gate; default 64/5 dispatcher.

**Playback/boot.** No explicit playback gate on MDBList; discovery refresh respects the 20 s startup gate and 30 s minimum interval. Ratings enrichment is screen-triggered.

### 2.7 RPDB / RatingPosterDB (`api.ratingposterdb.com`)

**Nature:** almost entirely an *image CDN*, not a JSON API.

**Retrofit:** `@Named("rpdb")` uses the default OkHttpClient (50 MB HTTP cache enabled). Exposes a single verification endpoint:
- `GET /{apiKey}/isValid` — used by `PosterRatingsSettingsViewModel.validateAndSaveRpdbApiKey`.

**Poster URL construction** (`PosterRatingsUrlResolver.buildRpdbPosterUrl`, `PosterRatingsUrlResolver.kt:106–114`):
```
https://api.ratingposterdb.com/{apiKey}/{idType}/poster-default/{id}.jpg
```
where `idType ∈ {imdb, tmdb, tvdb}`.

**Delivery path.** The URL is placed into `Meta`/`MetaPreview.poster` via `PosterRatingsUrlResolver.resolvePosterUrl` during metadata enrichment. Coil loads the bytes via its internal OkHttp client (see `NexioApplication` Coil configuration). Coil's own disk cache (`image_cache/`, 200 MB, evicted by `ImageCacheTtlWorker` with 10-day TTL) is the primary cache layer for RPDB images. The 50 MB HTTP cache of the default client is not in Coil's path.

**Concurrency.** Image fetches are driven by the list renderer; no service-level gate. Coil internally coalesces requests.

**Playback/boot.** Home rows trigger RPDB URL resolution; actual image fetches are coalesced with normal poster loads. During playback, the home screen is not rendering new cells, so RPDB fetches naturally quiet.

### 2.8 `api.top-streaming.stream` / TopPosters (`api.top-posters.com`)

**Ambiguity flagged.** The user's list includes `api.top-streaming.stream`. The production code at `PosterRatingsUrlResolver.buildTopPostersUrl` (`PosterRatingsUrlResolver.kt:116–137`) hardcodes:
```
https://api.top-posters.com/{apiKey}/{idType}/poster/{id}.jpg[?fallback_url=...]
```
and the `@Named("topPosters")` Retrofit is `https://api.top-posters.com/`. `api.top-streaming.stream` appears only in test fixtures (`app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt:153` mocks the resolver to return that URL; `app/src/test/java/com/nexio/tv/core/sync/AddonSyncCodecTest.kt` uses `top-streaming.stream` as an addon publicBaseUrl, unrelated to poster ratings).

Two possibilities:
1. The canonical poster proxy URL has moved from `top-posters.com` to `top-streaming.stream` (or the reverse), and the code still reflects the older base.
2. `top-streaming.stream` is a user-configurable provider endpoint that is expected to be wired through `PosterRatingsSettingsDataStore` but currently is not.

Current code path:
- URL is generated client-side from `idType + id + apiKey` (+ optional `fallback_url`).
- Applied to `Meta.poster` by `PosterRatingsUrlResolver.apply` (same as RPDB path).
- Fetched by Coil (image-loader cache), not by Retrofit.

**Concurrency / caching** are identical to the RPDB image-delivery case (Coil memory + 200 MB disk cache). No Retrofit calls beyond the URL-templating — the `@Named("topPosters")` Retrofit / `TopPostersApi` is declared in `NetworkModule` but we did not find production call sites for it in this audit; it may be used only in settings validation (similar to `RpdbApi.isValid`).

---

## 3. Cache-layer summary matrix

| Service | OkHttp HTTP disk cache | App in-memory cache | App disk cache | Outbox | Token/auth store |
|---|---|---|---|---|---|
| Trakt | **disabled** | `TraktLibraryService` (StateFlow), `TraktDiscoveryService` (StateFlow), `TraktProgressService` (activities 10s, stats ∞, optimistic 3m, watched 10m, history 5m, show-progress 5m), `TraktWatchingNowStateController` | `TraktLibrarySnapshotStore`, `TraktDiscoverySnapshotStore`, `TraktSettingsDataStore` | `TraktMutationOutboxStore` / Worker | `TraktAuthDataStore` |
| TMDB | **enabled** (50 MB) | `TmdbService` IMDB↔TMDB + in-flight dedup; `TmdbMetadataService` enrichment + episodes + persons + more-like-this + reviews (all `ConcurrentHashMap`, process-lifetime) | `MetadataDiskCacheStore` (`tmdb::` 7d, `tmdb_videos::` 12h) | — | — (static API key) |
| TVDB | **enabled** (50 MB, limited value due to `Authorization` Vary) | `TvdbReferenceDataService` (lifetime) | `MetadataDiskCacheStore` (`tvdb::` 7d, `tvdb_episode::` 24h, `tvdb_ref::` 30d), `TvdbIdentityCacheStore`, `TvdbMergeAliasStore`, `TvdbUpdateStateStore` | — | `TvdbTokenStore` (30d TTL, 24h refresh skew) |
| Kitsu | **enabled** (50 MB; server Cache-Control applies) | none inside `KitsuMetadataService` | — | — | `KitsuAuthDataStore` |
| Simkl | **disabled** | `SimklLibraryService.snapshotState`/`metadataState`, `SimklProgressRuntimeState` (per profile) | `SimklLibrarySnapshotStore`, `SimklProgressSyncStateStore`, `SimklSettingsDataStore` | shares `TraktMutationOutboxCoordinator` | `SimklAuthDataStore` |
| MDBList | **disabled** | `MDBListRepository` ratings (30m) + episode ratings (7d/30m); in-flight dedup | `MDBListDiscoverySnapshotStore`, `MDBListSettingsDataStore` | — | — |
| RPDB | enabled (but Coil path bypasses it) | Coil memory cache | Coil `image_cache/` (200 MB, 10-day TTL via `ImageCacheTtlWorker`) | — | — |
| TopPosters / top-streaming | enabled (but Coil path bypasses it) | Coil memory cache | Coil `image_cache/` | — | — |

---

## 4. Concurrency summary matrix

| Service | Serial gate? | Per-host cap | Caller-side semaphores | Parallel fan-out inside one operation |
|---|---|---|---|---|
| Trakt | **Yes** — 500 ms Mutex (`TraktRequestGate`) | 5 (default) — irrelevant, gate is stricter | `Semaphore(3)` for list-items hydration, `Semaphore(2)` for next-up validation, `Semaphore(5)` for metadata hydration (all still pass through gate) | None effective |
| Simkl | **Yes** — 500 ms Mutex (`SimklRequestGate`) | 5 — irrelevant | `Semaphore(4)` for addon metadata hydration (not Simkl API) | None effective for Simkl calls |
| TMDB | No | 5 | In-flight `CompletableDeferred` dedup | Yes — `coroutineScope { async … awaitAll }` in `TmdbMetadataService` |
| TVDB | No | 5 | — | Yes — in `TvdbMetadataService` (episodes/translations). `/updates` pagination is sequential. |
| Kitsu | No | 5 | — | No explicit fan-out inside a single call; independent callers may run in parallel |
| MDBList | No | 5 | `Semaphore(4)` providers, `Semaphore(3)` seasons | Yes — multiple provider ratings per request |
| RPDB | No | 5 | — | Image-loader parallelism (Coil) |
| TopPosters | No | 5 | — | Same as RPDB |

**Key point:** every service on the default OkHttp client shares the same 5-connection-per-host ceiling **per distinct host**. TMDB/TVDB/Kitsu/RPDB/TopPosters target different hosts so they do not compete, but within a single provider (e.g. TVDB episodes + artwork during enrichment), 5-per-host is the effective ceiling.

---

## 5. Playback gating (current behaviour)

### 5.1 What actually pauses during playback

`HomePlaybackWorkGate.shouldRunHomeBackgroundWork(snapshot)` returns `!snapshot.hasActiveSession` (`HomePlaybackWorkGate.kt:8–12`). When playback becomes active, `HomeViewModel` cancels the following jobs (from the same file):

- `continueWatchingEnrichmentJob`
- `heroEnrichmentJob`
- `tmdbEnrichFocusJob`
- `externalMetaPrefetchJob`
- `adjacentItemPrefetchJob`
- `metadataEnrichmentFlushJob`
- `trailerPreviewJob`
- `trailerMetadataAvailabilityJobs` (all in-flight)
- `posterStatusReconcileJob`
- Library observer jobs

Effect: home-screen TMDB/TVDB/Kitsu/MDBList/RPDB activity is cut when the user enters playback.

### 5.2 What is **not** gated

- `TraktMutationOutboxCoordinator` drain loop — flushes Trakt/Simkl mutations during playback.
- `TraktScrobbleMutationAdapter`, `SimklScrobbleMutationAdapter` — scrobbles are expected during playback.
- `TvdbUpdateWorker` — fires on WorkManager schedule (12 h), independent of playback.
- Direct-from-detail-screen enrichment calls in `MetaDetailsViewModel` (Trakt comments, TMDB fetch, TVDB enrichment, Kitsu) if the detail screen is re-opened during playback transitions.
- MDBList discovery refresh (no playback check).
- TVDB catch-up on `NexioApplication.onCreate` — runs once at start regardless.

### 5.3 `PlaybackIdleGateSnapshot`

Used to propagate the "player active" boolean into the home state machine. There is no analogous provider-side gate at `NetworkModule` / OkHttp layer — i.e. playback does not reduce the per-host dispatcher budget for metadata traffic while streaming.

---

## 6. Boot / cold-start fan-out

From `NexioApplication.onCreate` (referenced throughout the research):

1. `ObsoletePlaybackCacheCleanup.cleanup(cacheDir)` — local playback cache hygiene.
2. `retainPosterCacheOnStartup()` — no-op placeholder.
3. `ImageCacheTtlWorker.evictExpiredEntries()` — prune Coil disk.
4. Enqueue daily `ImageCacheTtlWorker` (periodic).
5. `tvdbUpdateCoordinator.schedulePeriodicUpdates(WorkManager)` — schedule 12 h periodic.
6. `appScope.launch { tvdbUpdateCoordinator.catchUpUpdates(TvdbUpdateTrigger.STARTUP) }` — **immediate TVDB `/updates` and reference warm**.

HomeViewModel lazy observers (fire when home screen first composes):
- Trakt library / discovery observers (disk-first, then 20 s startup gate, then refresh).
- Simkl library observers (disk-first; refresh on explicit activity change).
- MDBList discovery observers (disk-first; 20 s startup gate when `diskFirstHomeStartupEnabled`).
- Continue-watching enrichment chain: Trakt `/sync/playback` → per-item TVDB enrichment → TMDB fallback on TVDB miss → air availability computation.
- Hero/focus enrichment chain: `TvMetadataRouter.fetchEnrichment` → Kitsu (if anime) OR TVDB extended OR TMDB fallback.

Rough first-5-second cold-start API count (worst case, populated library, anime mix):
- TVDB `/updates`: **1** (+ follow-up invalidations)
- TVDB reference warm: **1–5** (`/artwork/types`, `/genres`, `/languages`, `/seasons/types`, etc.)
- Trakt: **3–10** (watchlist, watched/shows, watched/movies, personal-list items × N)
- Simkl: **1–2** (`/sync/activities`, `/sync/all-items/...`)
- MDBList: **1–3** (lists/top, lists/user, and any custom catalog)
- TMDB: **0–many** (driven by hero/focus enrichment, depends on what's on screen)
- Kitsu: **0–1** (only if anime item surfaces)
- RPDB/TopPosters: **0** for the APIs themselves; many image fetches ride Coil

---

## 7. Cross-service interactions and chains

- **Continue-watching enrichment chain.** `TraktProgressService.observePlayback` → for each item, `TvdbContinueWatchingTimingEnricher.enrich(...)` → TVDB `/series/{id}/extended` + `/series/{id}/episodes` → `TvdbAirAvailabilityCalculator` → on TVDB miss, `TvMetadataRouter` falls back to TMDB. This runs under `continueWatchingEnrichmentJob` which is cancelled on playback.
- **Detail screen chain.** `MetaDetailsViewModel.init` → in parallel: Trakt comments (direct `executeAuthorizedRequest`), Trakt progress/stats (via `TraktProgressService`), TVDB (if TV) or TMDB (if movie) via `TvMetadataRouter`, Kitsu (if anime) via `KitsuMetadataService`, MDBList ratings via `MDBListRepository`, RPDB/TopPosters URL overlay via `PosterRatingsUrlResolver`.
- **Mutation chain (Trakt library toggle).** ViewModel → `TraktLibraryService.toggleWatchlist` → `TraktMutationOutboxCoordinator.enqueueAndDrain` → `TraktLibraryMutationExecutor.execute` → `TraktAuthService.executeAuthorizedRequest` (gate 500 ms) → success → `refreshProfile(force=true)` → another 500 ms gated request.
- **Simkl on Trakt's outbox.** `SimklLibraryService.toggleWatchlist` / `SimklScrobbleService.enqueueScrobble` → `TraktMutationOutboxCoordinator.enqueueAndDrain` → `SimklLibraryMutationAdapter.execute` / `SimklScrobbleMutationAdapter.execute` → `SimklAuthService.executeAuthorizedRequest` (Simkl gate 500 ms).
- **TVDB update cascade.** `TvdbUpdateCoordinator.catchUpUpdates` → paginated `getUpdates(since,page)` → per event, `TvdbCacheInvalidator.invalidateChanged/DeletedOrMerged` → `MetadataDiskCacheStore` entries removed → next-read path does network again.

---

## 8. Dedup and double-fetch behaviour

| Situation | Deduplicated? | Evidence |
|---|---|---|
| Same IMDB → TMDB resolution in two callers | **Yes** via `CompletableDeferred` | `TmdbService.kt:89–99` |
| Same TMDB enrichment fetch in two callers | **No** — two `coroutineScope` fan-outs both hit network (disk cache mitigates after first completes) | `TmdbMetadataService` |
| Same TVDB series enrichment in two callers | **No** explicit coalescing; relies on 7-day `MetadataDiskCacheStore` entry | `TvdbMetadataService` |
| Same Kitsu anime id in two callers | **No** in-app dedup; relies on OkHttp HTTP cache | `KitsuMetadataService` |
| Same MDBList rating bundle | **Yes** via `inFlight` + mutex | `MDBListRepository.kt:120–142` |
| Same MDBList episode ratings for season | **Yes** via `episodeRatingsInFlightMutex` | `MDBListRepository.kt:301–325` |
| Trakt concurrent requests | **Serialised** by gate — effectively dedup'd through 500 ms spacing but not merged | `TraktRequestGate.kt:41–54` |
| Simkl concurrent requests | Same as Trakt | `SimklRequestGate.kt:39–53` |

---

## 9. Inconsistencies, anomalies, and loose ends

1. **HTTP-cache bypass asymmetry.** Trakt / Simkl / MDBList explicitly disable the 50 MB cache; Kitsu does not. Justification for Trakt/Simkl is clear (user-state, needs fresh). MDBList discovery is explicitly commented ("should refresh from network and update snapshot cache promptly"). Kitsu anime metadata is public and a good cache candidate; it being on the default client appears intentional but unstated.
2. **`TopPostersApi` vs `api.top-streaming.stream` drift.** Code constructs URLs for `https://api.top-posters.com/…` (`PosterRatingsUrlResolver.kt:116–137`) but the user's audit list names `api.top-streaming.stream`. Only test fixtures reference `top-streaming.stream`. Needs confirmation whether production expects one base, the other, or a user-configurable switch. No `PosterRatingsSettings.baseUrl` field observed in this pass.
3. **Simkl mutations tied to `TraktMutationOutboxCoordinator`.** Functional and intentional per code comments, but not obvious from names — a surprise if debugging Simkl alone.
4. **`MetaDetailsViewModel.loadTraktReviews`** issues Trakt calls directly via `traktAuthService.executeAuthorizedRequest` without a service-layer DTO mapping. No cache, and fetched every time the detail screen is opened.
5. **TVDB catch-up is unconditional on boot.** If the user launches the app with TVDB credentials missing but previously configured, `TvdbCredentialHealth.canCallTvdb()` short-circuits. If credentials are valid, `/updates` + reference warm fires immediately alongside home init.
6. **No playback gate on MDBList / Kitsu / direct-call detail screens.** `HomePlaybackWorkGate` only cancels home-owned jobs.
7. **Default dispatcher ceiling.** 5-per-host is OkHttp's untuned default; the comment in `providePlaybackOkHttpClient` notes that 12-per-host is safe for debrid CDNs but the metadata-provider dispatcher was never raised.
8. **Trakt auth endpoints bypass the gate.** If several auth paths run concurrently (settings screen + background 401 refresh), they can race against normal traffic.
9. **`cachedActivities` on `TraktProgressService`** is a plain `@Volatile`; safe today because the gate serialises all reads/writes of Trakt traffic but depends on that invariant.
10. **`TvdbUpdateWorker`** has no network constraint (`NetworkType.UNMETERED` etc.) — it runs on any connection.
11. **`TraktNextUpValidationPolicy`** uses `Semaphore(2)` inside gated calls — the semaphore provides no throughput benefit because the gate is already serial; it only limits coroutine parallelism above the gate.
12. **Kitsu has no in-app cache above OkHttp.** `fetchSeasonEpisodes` pages sequentially with no memoisation — reopening an anime detail screen repaginates if the HTTP cache TTL has expired.

---

## 10. Per-service concurrency / blocking cheat sheet

```
                ┌── HTTP cache ──┐  ┌── Serial gate ──┐  ┌── Playback gate ──┐
trakt.tv       │    disabled     │ │   Yes 500ms      │ │  home jobs cancelled │
                │                 │ │                  │ │  outbox continues     │
simkl.com      │    disabled     │ │   Yes 500ms      │ │  home jobs cancelled │
                │                 │ │                  │ │  scrobble expected    │
themoviedb.org │    enabled 50MB │ │   No             │ │  home jobs cancelled │
                │                 │ │                  │ │  detail-screen: no    │
thetvdb.com    │    enabled 50MB │ │   No             │ │  home jobs cancelled │
                │   (Vary on JWT) │ │                  │ │  update-worker: no    │
kitsu.io       │    enabled 50MB │ │   No             │ │  home jobs cancelled │
                │                 │ │                  │ │  detail-screen: no    │
mdblist.com    │    disabled     │ │   No             │ │  none                 │
                │                 │ │                  │ │                       │
ratingposterdb │  via Coil pipe  │ │   No (image)     │ │  N/A (image loader)   │
top-posters/   │  via Coil pipe  │ │   No (image)     │ │  N/A (image loader)   │
top-streaming  │                 │ │                  │ │                       │
```

Shared ceilings for everything on the default/derived clients: **Dispatcher 64 total / 5 per host**, **ConnectionPool 5 idle / 5-minute keep-alive**. Playback uses a separate ConnectionPool (12/host). AddonStreams uses a separate Dispatcher (128/32).

---

## 11. Evidence index

- `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
- `app/src/main/java/com/nexio/tv/data/remote/TraktRequestGate.kt`, `SimklRequestGate.kt`
- `app/src/main/java/com/nexio/tv/data/remote/api/TraktApi.kt`, `SimklApi.kt`, `TmdbApi.kt`, `TvdbApi.kt`, `KitsuApi.kt`, `KitsuAuthApi.kt`, `MDBListApi.kt`, `RpdbApi.kt` (in `PosterRatingsApi.kt`), `TopPostersApi.kt`
- `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt`, `TraktLibraryService.kt`, `TraktProgressService.kt`, `TraktScrobbleService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt`, `SimklLibraryService.kt`, `SimklProgressService.kt`, `SimklScrobbleService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt`, `TraktProgressMutationExecutor.kt`, `TraktScrobbleMutationAdapter.kt`, `TraktDiscoveryMutationAdapter.kt`, `TraktNextUpValidationPolicy.kt`
- `app/src/main/java/com/nexio/tv/data/repository/simkl/SimklLibraryMutationAdapter.kt`, `SimklScrobbleMutationAdapter.kt`, `SimklProgressHistoryMutationAdapter.kt`, `SimklSeasonMarkMutationAdapter.kt`
- `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxCoordinator.kt`, `TraktMutationOutboxWorker.kt`, `TraktMutationOutboxPolicy.kt`, `TraktMutationOutboxModels.kt`
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbService.kt`, `TmdbOrganizationService.kt`, `TmdbMetadataService.kt` (in `core/metadata/`)
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt`, `TvdbIdentityService.kt`, `TvdbReferenceDataService.kt`, `TvdbUpdateCoordinator.kt`, `TvdbUpdateProcessor.kt`, `TvdbCacheInvalidator.kt`, `TvdbProviderFallback.kt`, `TvdbAirAvailabilityCalculator.kt`, `TvdbCredentialHealth.kt`, `TvMetadataRouter.kt`
- `app/src/main/java/com/nexio/tv/workers/TvdbUpdateWorker.kt`
- `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`, `data/repository/KitsuAuthService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt` (core logic), `core/mdblist/MDBListDiscoveryService.kt`, `data/local/MDBListDiscoverySnapshotStore.kt`, `data/local/MDBListSettingsDataStore.kt`
- `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`, `data/remote/api/PosterRatingsApi.kt`, `data/local/PosterRatingsSettingsDataStore.kt`, `core/image/ArtworkImageCacheKeys.kt`, `core/image/ImageCacheTtlWorker.kt`, `core/recommendations/AndroidTvChannelArtworkCache.kt`
- `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`, `CatalogDiskCacheStore.kt`, `TvdbIdentityCacheStore.kt`, `TraktLibrarySnapshotStore.kt`, `SimklLibrarySnapshotStore.kt`, `TraktAuthDataStore.kt`, `SimklAuthDataStore.kt`, `KitsuAuthDataStore.kt`, `TvdbTokenStore.kt`, `TvdbUpdateStateStore.kt`, `TvdbMergeAliasStore.kt`, `TvdbDiagnosticsDataStore.kt`, `TmdbSettingsDataStore.kt`, `TvdbSettingsDataStore.kt`, `SimklSettingsDataStore.kt`, `TraktSettingsDataStore.kt`, `MDBListSettingsDataStore.kt`, `PosterRatingsSettingsDataStore.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel*.kt` (and `HomePlaybackWorkGate.kt`)
- `app/src/main/java/com/nexio/tv/ui/screens/details/MetaDetailsViewModel.kt`
- `app/src/main/java/com/nexio/tv/NexioApplication.kt`
- Test references: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`, `app/src/test/java/com/nexio/tv/core/sync/AddonSyncCodecTest.kt`

---

*End of audit. Next phase (not covered here): propose changes to reduce redundant network calls, raise per-host ceilings where safe, align cache policies, and gate non-critical providers during playback.*
