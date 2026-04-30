# Catalog Rails — End-to-End Data Flow

> **Engineering artifact.** Every node references a real class/file/method that exists in the
> Nexio codebase as of commit `067150141`. Use this diagram to look at any Home/Discover rail
> and trace exactly where each piece of metadata + each artifact (poster, title, ranking) comes
> from, when it's cached, what scope it lives under, and which trace events it emits.
>
> **Conventions:**
> - **Box label format:** `ClassName.method(...)` or `ClassName` (file path in parentheses for non-obvious ones).
> - **Edge labels:** `cachePolicy`, scope, TTL, or trigger condition.
> - **Colors:** source (blue), discovery service (orange), snapshot/cache (yellow), mapper (purple), first-paint boundary (green), router (teal), integration runtime (red), trace (gray), logcat tag (dark).
> - **Dashed edges:** observability (trace events written to disk + optionally logcat).
> - **Solid edges:** data flow.
>
> **Coverage:** 6 conformant `CatalogRailSource` providers (Addon, Trakt, MDBList, SIMKL,
> TMDB built-in, Kitsu built-in) + Continue Watching, with downstream into the first-paint
> boundary, the metadata router, and the integration runtime + cache + trace fan-out.

---

## Master Flow Diagram

```mermaid
%%{init: {'flowchart': {'curve': 'basis'}, 'theme': 'default'}}%%
flowchart TB
    %% ---------- STYLES ----------
    classDef src fill:#cfe2ff,stroke:#0d6efd,color:#000,stroke-width:1px
    classDef svc fill:#fde9c2,stroke:#fd7e14,color:#000,stroke-width:1px
    classDef snap fill:#fff3cd,stroke:#ffc107,color:#000,stroke-width:1px
    classDef map fill:#e2d4ff,stroke:#6f42c1,color:#000,stroke-width:1px
    classDef boundary fill:#d1e7dd,stroke:#198754,color:#000,stroke-width:2px
    classDef router fill:#bee5eb,stroke:#0d6efd,color:#000,stroke-width:1px
    classDef runtime fill:#f8d7da,stroke:#dc3545,color:#000,stroke-width:1px
    classDef trace fill:#e2e3e5,stroke:#6c757d,color:#000,stroke-width:1px,stroke-dasharray:3 2
    classDef logcat fill:#212529,stroke:#fff,color:#fff,stroke-width:1px
    classDef http fill:#ffe5d9,stroke:#fd7e14,color:#000

    %% ============================================================
    %% PHASE A — UI ENTRY
    %% ============================================================
    HomeVM["HomeViewModel<br/>ui/screens/home/HomeViewModel.kt"]
    Pipeline["HomeViewModelCatalogPipeline<br/>orchestrates per-rail load + ordering"]
    Hydration["HomeRailHydrationExecutor<br/>triggers on-demand rail refresh"]
    HomeVM --> Pipeline
    HomeVM --> Hydration

    %% ============================================================
    %% PHASE B — 7 RAIL SOURCES (sources at top)
    %% ============================================================

    subgraph SRC["Catalog Rail Sources"]
        direction LR
        SAddon["AddonRepository<br/>installed addons<br/>per-profile preferences"]:::src
        STrakt["TraktAuthService<br/>per-profile session<br/>(optional)"]:::src
        SMDB["MDBListSettingsDataStore<br/>API key (global, opt-in)"]:::src
        SSimkl["SIMKL public CDN<br/>data.simkl.in"]:::src
        STmdb["TMDB API v3<br/>api.themoviedb.org"]:::src
        SKitsu["Kitsu public API<br/>kitsu.io/api/edge"]:::src
        SCW["TraktSyncMutationOutbox<br/>+ scrobble history"]:::src
    end

    %% ============================================================
    %% PHASE C — DISCOVERY SERVICES (one per provider)
    %% ============================================================

    subgraph DISC["Discovery / Repository Layer"]
        direction TB
        DAddon["CatalogRepositoryImpl<br/>refreshCatalogToDisk(addon, catalog)<br/>data/repository/CatalogRepositoryImpl.kt"]:::svc
        DTrakt["TraktDiscoveryService<br/>fetchTrendingMovies/Shows<br/>fetchPopularMovies/Shows<br/>data/repository/TraktDiscoveryService.kt"]:::svc
        DMDB["MDBListDiscoveryService<br/>ensureFresh(profileId)<br/>data/repository/MDBListDiscoveryService.kt"]:::svc
        DSimkl["SimklDiscoveryService<br/>ensureFresh(profileId)<br/>data/repository/SimklDiscoveryService.kt"]:::svc
        DTmdb["TmdbDiscoveryService<br/>refreshCatalogs(prefs, force, ids)<br/>data/repository/TmdbDiscoveryService.kt:35"]:::svc
        DKitsu["KitsuDiscoveryService<br/>refreshCatalogs(prefs, force, ids)<br/>data/repository/KitsuDiscoveryService.kt"]:::svc
        DCW["ContinueWatchingSnapshotService<br/>(Trakt-derived)<br/>data/repository/ContinueWatchingSnapshotService.kt:77"]:::svc
    end

    SAddon --> DAddon
    STrakt --> DTrakt
    SMDB --> DMDB
    SSimkl --> DSimkl
    STmdb --> DTmdb
    SKitsu --> DKitsu
    SCW --> DCW

    %% ============================================================
    %% PHASE D — INTEGRATION PROVIDERS (per-provider runtime entry)
    %% ============================================================

    subgraph IP["Integration Providers (per-provider runtime entry)"]
        direction TB
        IPAddon["AddonCatalogIntegrationProvider.getCatalog<br/>provider=ADDON<br/>scope=ProviderConfig('addon:$id')<br/>op='addon.catalog.getCatalog'<br/>cachePolicy=ObserveOnlyOrMutation"]:::svc
        IPTrakt["TraktIntegrationProvider<br/>fetch{Trending,Popular}{Movies,Shows}(limit)<br/>scope=GlobalContent<br/>op=accountOperationKey(session, ...)<br/>cachePolicy=CacheFirst(10m / 60m)"]:::svc
        IPMDB["MDBListIntegrationProvider<br/>getRaw / getRawWithQuery<br/>scope=Account(profileId, MDBLIST, hash) / GlobalContent<br/>cachePolicy=CacheFirst(30m) for ratings"]:::svc
        IPSimkl["SimklIntegrationProvider.fetchDiscoveryBody<br/>SimklDiscoveryTransport bypass<br/>scope=GlobalContent (CDN)"]:::svc
        IPTmdb["TmdbIntegrationProvider.fetchCatalog<br/>(implements TmdbDiscoveryClient)<br/>scope=GlobalContent<br/>cachePolicy=Disabled<br/>op='tmdb.<endpoint>'"]:::svc
        IPKitsu["KitsuDiscoveryIntegrationProvider.fetchCatalog<br/>(implements KitsuDiscoveryClient)<br/>scope=GlobalContent<br/>op='kitsu.fetch_catalog'"]:::svc
    end

    DAddon --> IPAddon
    DTrakt --> IPTrakt
    DMDB --> IPMDB
    DSimkl --> IPSimkl
    DTmdb --> IPTmdb
    DKitsu --> IPKitsu

    %% ============================================================
    %% PHASE E — SNAPSHOT / CACHE STORES
    %% ============================================================

    subgraph CACHE["Cache & Snapshot Layer (per-rail persistence)"]
        direction LR
        CSAddonDisk["CatalogDiskCacheStore<br/>SharedPreferences (catalog_disk_cache_v1)<br/>NO TTL · NO eviction"]:::snap
        CSAddonMem["CatalogRepositoryImpl.catalogCache<br/>ConcurrentHashMap (in-process)"]:::snap
        CSTrakt["TraktDiscoverySnapshotStore<br/>SharedPreferences per profileId<br/>backed by runtime cache (CacheFirst 10m/60m)"]:::snap
        CSMDB["MDBListDiscoverySnapshotStore<br/>SharedPreferences per profileId<br/>read(profileId): MDBListDiscoverySnapshot?"]:::snap
        CSSimkl["SimklDiscoverySnapshotStore<br/>SharedPreferences per profileId<br/>read(profileId): SimklDiscoverySnapshot?"]:::snap
        CSTmdb["TmdbDiscoveryService.snapshot<br/>MutableStateFlow GLOBAL · in-memory<br/>⚠️ profile-isolation gap"]:::snap
        CSKitsu["KitsuDiscoveryService.snapshot<br/>MutableStateFlow GLOBAL · in-memory<br/>⚠️ profile-isolation gap"]:::snap
        CSCW["ContinueWatchingSnapshotStore<br/>per-profile · positionMs + episodeContext<br/>+ clickTimeDisplayMetadata cache"]:::snap
    end

    IPAddon -- "writes 'addon.catalog' result" --> CSAddonDisk
    IPAddon --> CSAddonMem
    DTrakt -- "writes per-profile" --> CSTrakt
    DMDB -- "writes per-profile" --> CSMDB
    DSimkl -- "writes per-profile" --> CSSimkl
    DTmdb -- "writes global" --> CSTmdb
    DKitsu -- "writes global" --> CSKitsu
    DCW -- "writes per-profile" --> CSCW

    %% ============================================================
    %% PHASE F — UNIFORM CATALOG RAIL SOURCE CONTRACT
    %% (Plans 1-8: 6 conformant impls + manifest registry)
    %% ============================================================

    subgraph CRS["CatalogRailSource Contract (data/catalog/rails/)"]
        direction TB
        CRSAddon["AddonCatalogRailSource<br/>availableRails(profileId) → flatten addon.catalogs<br/>fetchRail → catalogRepository.refreshCatalogToDisk + toLegacyRailItemPreviews"]:::map
        CRSTrakt["TraktCatalogRailSource<br/>availableRails(profileId) → 4 fixed kinds (TRENDING / POPULAR × MOVIES / SHOWS)<br/>fetchRail → integrationProvider.fetch* + mapper.map* (LIMIT=30)"]:::map
        CRSMDB["MDBListCatalogRailSource<br/>availableRails → snapshotStore.read(profileId).customListCatalogs<br/>fetchRail → snapshot lookup (gated on settings.enabled + apiKey)"]:::map
        CRSSimkl["SimklCatalogRailSource<br/>availableRails → snapshotStore.read(profileId).itemRecordsByCatalog<br/>10 hardcoded display titles"]:::map
        CRSTmdb["TmdbCatalogRailSource<br/>availableRails → discoveryService.observeSnapshot().first().rowRecordsByCatalog<br/>display from record.catalogName"]:::map
        CRSKitsu["KitsuCatalogRailSource<br/>availableRails → discoveryService.observeSnapshot().first().rowRecordsByCatalog<br/>9 anime rails"]:::map
    end

    CSAddonDisk --> CRSAddon
    CSAddonMem --> CRSAddon
    CSTrakt --> CRSTrakt
    CSMDB --> CRSMDB
    CSSimkl --> CRSSimkl
    CSTmdb --> CRSTmdb
    CSKitsu --> CRSKitsu

    %% ============================================================
    %% PHASE G — RAIL ITEM MAPPERS (provider DTO → RailItemPreview)
    %% ============================================================

    subgraph MAP["Rail-Preview Mappers (DTO → RailItemPreview)"]
        direction LR
        MAddon["MetaPreview.toLegacyRailItemPreview(railId)<br/>domain/model/RailPreviewLegacyAdapters.kt:3<br/>RailSource = ADDON_CATALOG"]:::map
        MTrakt["TraktRailPreviewMapper<br/>mapTrendingMovie/Show, mapMovie, mapShow, mapCalendarEpisode<br/>RailSource = BUILT_IN_TRAKT"]:::map
        MMDB["MDBListRailPreviewMapper.mapJsonObject<br/>RailSource = BUILT_IN_MDBLIST"]:::map
        MSimkl["SimklRailPreviewMapper.mapDiscoveryItem<br/>RailSource = BUILT_IN_SIMKL_DISCOVERY"]:::map
        MTmdb["TmdbRailPreviewMapper.mapResult<br/>RailSource = BUILT_IN_TMDB"]:::map
        MKitsu["KitsuRailPreviewMapper.mapAnime<br/>RailSource = BUILT_IN_KITSU"]:::map
        MCW["ContinueWatchingRecord<br/>+ clickTimeDisplayMetadata snapshot<br/>RailSource = CONTINUE_WATCHING"]:::map
    end

    IPAddon --> MAddon
    DTrakt --> MTrakt
    DMDB --> MMDB
    DSimkl --> MSimkl
    DTmdb --> MTmdb
    DKitsu --> MKitsu
    DCW --> MCW

    %% ============================================================
    %% PHASE H — CONVERGENCE: shared RailItemPreview type
    %% ============================================================

    RIP{{"List&lt;RailItemPreview&gt;<br/>domain/model/RailItemPreview.kt<br/>SHARED across all 7 sources"}}:::boundary

    MAddon --> RIP
    MTrakt --> RIP
    MMDB --> RIP
    MSimkl --> RIP
    MTmdb --> RIP
    MKitsu --> RIP
    MCW --> RIP

    %% ============================================================
    %% PHASE I — FIRST PAINT BOUNDARY (single shared site)
    %% ============================================================

    subgraph FP["First Paint Boundary (canonical Home rendering site)"]
        direction TB
        FPMapper["MetaPreview.toFirstPaintHomeDisplayMetadata()<br/>ui/screens/home/HomeFirstPaintMetadataMapper.kt:14<br/>collectFirstPaintFieldsUsed(display)"]:::boundary
        FPTracer["FirstPaintTracer.recordHomePreview(<br/>contentId, itemType, fieldsUsed, source)<br/>core/trace/FirstPaintTracer.kt:26"]:::boundary
        FPEvent["TraceMetadataEvents.emitFirstPaint(<br/>contentId, itemType, surface, source,<br/>routerExecuted, networkExecuted, fieldsUsed, profileHash)<br/>→ event 'metadata.first_paint'<br/>core/trace/TraceMetadataEvents.kt:27"]:::trace
        FPMapper --> FPTracer
        FPTracer --> FPEvent
    end

    RIP -- "Home composer renders rail" --> FPMapper

    %% ============================================================
    %% PHASE J — DETAIL CLICK PATH (only fires on user navigation)
    %% ============================================================

    DetailClick(["User clicks rail item<br/>(navigation to DetailScreen)"])
    RIP -. "click" .-> DetailClick

    subgraph ROUTER["Metadata Routing (per-detail)"]
        direction TB
        Router["MetadataRouterFacade.resolveRequest(request)<br/>core/metadata/router/MetadataRouterFacade.kt:57<br/>(also: routeRequest line 48)"]:::router
        Identity["MetadataIdentityResolver<br/>(IdMappingStore + negative cache)<br/>resolves contentId → provider IDs"]:::router
        Route["MetadataRouter.route(...)<br/>chooses primary provider per kind<br/>→ event 'metadata.route_decision'"]:::router
        IdEvent["TraceMetadataEvents.emitRouteDecision /<br/>emitIdentityResolution<br/>core/trace/TraceMetadataEvents.kt:60,196"]:::trace
        PlanExec["ProviderPlanExecutor.buildPlan()<br/>core/metadata/router/ProviderPlanExecutor.kt<br/>→ event 'metadata.provider_plan'"]:::router
        PlanRun["ProviderPlanRunner.run(plan)<br/>iterates plan.steps via mapNotNull<br/>(skips optional steps with no adapter)"]:::router
        Adapter["MetadataProviderAdapter.fetch(step)<br/>per provider (TmdbMetadataProviderAdapter,<br/>TvdbMetadataProviderAdapter, KitsuMetadataProviderAdapter, etc.)"]:::router
        FieldRes["FieldResolver.resolve(...)<br/>+ canReplaceRailPreview ownership<br/>→ event 'metadata.field_selected'"]:::router
        Result{{"ResolvedMetadataDocument<br/>(only constructed by FieldResolver +<br/>MetadataRouterFacade — F2-J-03 contract)"}}:::boundary
        Router --> Identity --> Route --> PlanExec --> PlanRun --> Adapter --> FieldRes --> Result
        Route --> IdEvent
    end

    DetailClick --> Router

    %% ============================================================
    %% PHASE K — INTEGRATION RUNTIME (every fetch funnels here)
    %% ============================================================

    subgraph RUNTIME["Integration Runtime (every networked fetch)"]
        direction TB
        RT["DefaultIntegrationRuntime<br/>core/integration/DefaultIntegrationRuntime.kt"]:::runtime
        RTget["runtime.get(spec): IntegrationFetchResult&lt;T&gt;<br/>line 115<br/>{ Updated, Fresh, Stale, Missing }"]:::runtime
        RTcall["runtime.call(spec): IntegrationCallResult&lt;T&gt;<br/>{ Success, HttpError, NetworkError, Missing }"]:::runtime
        RTPolicy["IntegrationCachePolicy<br/>{ CacheFirst(ttlMs, staleAfterExpiryMs),<br/>ObserveOnly(reason), Disabled, Mutation }"]:::runtime
        RTScope["IntegrationScope<br/>{ GlobalContent, GlobalLocalizedContent(lang, v),<br/>GlobalEnglishImage, ProviderConfig(key),<br/>Account(profileId, provider, hash),<br/>Profile(profileId), ProfileLocal }"]:::runtime
        RTBackoff["IntegrationBackoffManager<br/>+ IntegrationSingleFlight (coalesceConcurrent)"]:::runtime
        RT --> RTget
        RT --> RTcall
        RTget --> RTPolicy
        RTcall --> RTPolicy
        RT --> RTScope
        RT --> RTBackoff
    end

    Adapter --> RT
    IPAddon -- "runtime.call" --> RTcall
    IPTrakt -- "runtime.get" --> RTget
    IPMDB -- "runtime.call / runtime.get" --> RT
    IPSimkl -- "runtime.call (CDN)" --> RTcall
    IPTmdb -- "runtime.get" --> RTget
    IPKitsu -- "runtime.call" --> RTcall

    %% ============================================================
    %% PHASE L — RUNTIME CACHE DECISIONS + HTTP
    %% ============================================================

    subgraph CDEC["Runtime Cache Decisions (per call)"]
        direction TB
        CD["TraceCacheDecision<br/>{ FRESH, EXPIRED_MISS, MISS, WRITE,<br/>STALE_HIT, STALE_REFRESH, ... }"]:::runtime
        CDEvent["DefaultIntegrationRuntime.emitCacheDecision<br/>→ event 'runtime.cache_decision'<br/>(includes operationKey, cacheKey, decision)"]:::trace
        OPStart["DefaultIntegrationRuntime.emitTrace<br/>→ event 'runtime.operation_start'"]:::trace
        OPFin["→ event 'runtime.operation_finish'<br/>(durationMs, outcome)"]:::trace
        OPFail["→ event 'runtime.operation_failed'"]:::trace
        CD --> CDEvent
        OPStart --> CDEvent
        CDEvent --> OPFin
        CDEvent --> OPFail
    end

    RTget --> CD
    RTcall --> CD

    subgraph HTTP["HTTP Wire Layer"]
        direction TB
        HInt["RuntimeTraceInterceptor<br/>core/trace/RuntimeTraceInterceptor.kt"]:::http
        HReq["→ event 'http.request'<br/>(method, url, headers redacted)"]:::trace
        HResp["→ event 'http.response'<br/>(statusCode, durationMs, byteCount)"]:::trace
        HErr["→ event 'http.error'"]:::trace
        HBody["captureBodySample()<br/>→ event 'trace.body_sample'<br/>(only if INCLUDE_HTTP_BODIES_INTERNAL_ONLY)"]:::trace
        HInt --> HReq
        HInt --> HResp
        HInt --> HErr
        HInt --> HBody
    end

    CD -- "on EXPIRED_MISS / Disabled" --> HInt

    %% ============================================================
    %% PHASE M — TRACE FAN-OUT (file bundle + per-channel logcat)
    %% ============================================================

    subgraph TRACE["Trace Pipeline (file + logcat fan-out)"]
        direction TB
        Sink{{"CompositeRuntimeTraceSink<br/>core/trace/CompositeRuntimeTraceSink.kt<br/>(swallows Exception, propagates CancellationException + Error)"}}:::trace
        FileSink["FileRuntimeTraceSink<br/>JsonlTraceWriter (50MB cap)<br/>per-session file: /traces/&lt;sessionId&gt;/trace-events.jsonl"]:::trace
        LogcatSink["LogcatRuntimeTraceSink<br/>per-channel gated (LogcatTraceChannelsProvider)"]:::trace
        TFP[["adb logcat -s Nexio.FirstPaint"]]:::logcat
        TMR[["adb logcat -s Nexio.MetaRoute"]]:::logcat
        TIR[["adb logcat -s Nexio.IntRuntime"]]:::logcat
        Sink --> FileSink
        Sink --> LogcatSink
        LogcatSink -- "metadata.first_paint" --> TFP
        LogcatSink -- "metadata.{route_decision, identity_resolution, provider_plan, resolver_schedule, field_selected, localization_plan, normalizer_warning}" --> TMR
        LogcatSink -- "runtime.* + http.* + trace.body_sample" --> TIR
    end

    FPEvent --> Sink
    IdEvent --> Sink
    CDEvent --> Sink
    OPStart --> Sink
    OPFin --> Sink
    OPFail --> Sink
    HReq --> Sink
    HResp --> Sink
    HErr --> Sink
    HBody --> Sink
```

---

## Per-Provider Data-Origin Cheatsheet

What ends up in each `RailItemPreview` field per provider:

| Field | Addon | Trakt | MDBList | SIMKL | TMDB | Kitsu | Continue Watching |
|---|---|---|---|---|---|---|---|
| `railId` | `addon::<id>::<type>::<catalogId>` | `trakt::<kind>` | `mdblist_list_<slug>_<type>` | `simkl_<type>_trending_<period>` | `tmdb_<kind>_<type>` | `kitsu_<kind>_anime` | `continue_watching` (singleton) |
| `railSource` | `ADDON_CATALOG` | `BUILT_IN_TRAKT` | `BUILT_IN_MDBLIST` | `BUILT_IN_SIMKL_DISCOVERY` | `BUILT_IN_TMDB` | `BUILT_IN_KITSU` | `CONTINUE_WATCHING` |
| `sourceProvider` | depends on `MetaPreviewDto.imdb_id` / `tmdb` | `ProviderId.TRAKT` | `ProviderId.MDBLIST` | `ProviderId.SIMKL` | `ProviderId.TMDB` | `ProviderId.KITSU` | (per-record) |
| `sourceItemId` | addon-supplied (`tt0…` or `tmdb:…`) | `trakt:movie:<id>` / `trakt:show:<id>` | tmdb/imdb id from MDBList JSON | `simkl:<id>` (composite) | `tmdb:<type>:<id>` | `kitsu:anime:<id>` | `<contentId>:s<season>e<episode>` |
| `display.title` | addon JSON `name` | DTO `title` | JSON `title` | DTO `title` | DTO `title`/`name` | DTO `attributes.canonicalTitle` | snapshot's `clickTimeDisplayMetadata` |
| `display.posterUrl` | addon JSON `poster` (often via RPDB rewrite) | TMDB lookup at hydration time | `poster` URL (often RPDB) | `poster_url` | `poster_path` (TMDB CDN) | `attributes.posterImage.original` | snapshot |
| `ranking` | none (positional) | `RailRankingMetadata.watchers` | `like_count` proxy | `position` | `popularity` (positional) | `position` | `lastWatched` |
| `sourcePayloadQuality` | `DISPLAY_BASIC` | `SPARSE_IDENTITY` | `RICH_PREVIEW` | `RICH_PREVIEW` | `RICH_PREVIEW` | `RICH_PREVIEW` | snapshot-derived |

---

## Cache & Scope Cheatsheet (per source)

| Provider | Snapshot Type | TTL / Eviction | Per-Profile | Runtime Scope | Runtime Cache Policy |
|---|---|---|---|---|---|
| **Addon** | `CatalogDiskCacheStore` (SharedPrefs) + `ConcurrentHashMap` | **none** (grows unbounded) | ❌ shared | `ProviderConfig("addon:$id")` | `ObserveOnlyOrMutation` (no runtime cache) |
| **Trakt** (Plan 3 — global rails) | `TraktDiscoverySnapshotStore` (per-profile SharedPrefs) | runtime: 10m fresh, 60m stale | ✅ per-profile | `GlobalContent` + `accountOperationKey` | `CacheFirst(10m, 60m)` |
| **MDBList** | `MDBListDiscoverySnapshotStore` (per-profile SharedPrefs) | snapshot driven by `ensureFresh`; ratings: 30m | ✅ per-profile (snapshot); ❌ settings global | `Account(profileId, MDBLIST, hash)` when API key | `CacheFirst(30m)` for ratings |
| **SIMKL** | `SimklDiscoverySnapshotStore` (per-profile SharedPrefs) | snapshot driven by `ensureFresh` | ✅ per-profile | `GlobalContent` (CDN) | per-call (transport-bound) |
| **TMDB built-in** | `TmdbDiscoveryService.snapshot` (in-memory `MutableStateFlow`) | **none** (grows unbounded; ⚠️ global) | ❌ **global — known leak** | `GlobalContent` | `Disabled` (no runtime cache) |
| **Kitsu built-in** | `KitsuDiscoveryService.snapshot` (in-memory `MutableStateFlow`) | **none** (grows unbounded; ⚠️ global) | ❌ **global — known leak** | `GlobalContent` | `CacheFirst(24h, 7d)` for enrichment only |
| **Continue Watching** | `ContinueWatchingSnapshotStore` (per-profile) | event-driven (scrobble updates + alarm scheduler) | ✅ per-profile | n/a (sourced from local Trakt history + scrobble outbox) | n/a |

---

## Trace Event Reference (which events fire, where, when)

Every fetch + render + routing decision emits structured trace events via `TraceMetadataEvents` (in `core/trace/`). Events go into the file bundle (`FileRuntimeTraceSink`) and — gated per channel — into Android logcat (`LogcatRuntimeTraceSink`).

| Event Type | Emitted By (file:line) | Logcat Tag | Fires When |
|---|---|---|---|
| `metadata.first_paint` | `TraceMetadataEvents.emitFirstPaint` (`TraceMetadataEvents.kt:27`) via `FirstPaintTracer.recordHomePreview` (`FirstPaintTracer.kt:26`) | `Nexio.FirstPaint` | Each rail item rendered at the canonical Home boundary |
| `metadata.route_decision` | `TraceMetadataEvents.emitRouteDecision` (`TraceMetadataEvents.kt:196`) via `MetadataRouter.route()` | `Nexio.MetaRoute` | Detail open: per-content routing decision |
| `metadata.identity_resolution` | `TraceMetadataEvents.emitIdentityResolution` (`TraceMetadataEvents.kt:60`) via `MetadataIdentityResolver` | `Nexio.MetaRoute` | Identity lookup (TMDB ↔ TVDB ↔ IMDB) per detail |
| `metadata.provider_plan` | `TraceMetadataEvents.emitProviderPlan` (`TraceMetadataEvents.kt:97`) via `ProviderPlanExecutor.buildPlan()` | `Nexio.MetaRoute` | After router picks primary provider |
| `metadata.resolver_schedule` | `TraceMetadataEvents.emitResolverSchedule` (`TraceMetadataEvents.kt:124`) via `ProviderPlanRunner` | `Nexio.MetaRoute` | Per resolver-tier scheduling decision |
| `metadata.field_selected` | `TraceMetadataEvents.emitFieldSelected` (`TraceMetadataEvents.kt:165`) via `FieldResolver` | `Nexio.MetaRoute` | Per field decision (which provider wins) |
| `metadata.localization_plan` | `TraceMetadataEvents.emitLocalizationPlan` (`TraceMetadataEvents.kt:258`) | `Nexio.MetaRoute` | Per localization fallback decision |
| `metadata.normalizer_warning` | `TraceMetadataEvents.emitNormalizerWarning` (`TraceMetadataEvents.kt:147`) | `Nexio.MetaRoute` | Provider-specific normalizer caveat |
| `runtime.operation_start` | `DefaultIntegrationRuntime.emitTrace` (`DefaultIntegrationRuntime.kt:131`) | `Nexio.IntRuntime` | Every `runtime.get`/`runtime.call` entry |
| `runtime.cache_decision` | `DefaultIntegrationRuntime.emitCacheDecision` (`DefaultIntegrationRuntime.kt:89`) | `Nexio.IntRuntime` | After cache lookup (FRESH / EXPIRED_MISS / WRITE / etc.) |
| `runtime.operation_finish` | same as above (`DefaultIntegrationRuntime.kt:137`) | `Nexio.IntRuntime` | After call completes (durationMs, outcome) |
| `runtime.operation_failed` | same as above (line 147) | `Nexio.IntRuntime` | Throwable raised (excluding CancellationException) |
| `http.request` | `RuntimeTraceInterceptor` (`RuntimeTraceInterceptor.kt:36`) | `Nexio.IntRuntime` | OkHttp interceptor — every tagged outgoing request |
| `http.response` | `RuntimeTraceInterceptor.kt:65` | `Nexio.IntRuntime` | OkHttp interceptor — every response |
| `http.error` | `RuntimeTraceInterceptor.kt:53` | `Nexio.IntRuntime` | OkHttp interceptor — network/request error |
| `trace.body_sample` | `RuntimeTraceInterceptor.captureBodySample` (line 86) | `Nexio.IntRuntime` | Only when `TraceMode.INCLUDE_HTTP_BODIES_INTERNAL_ONLY` |

---

## How to use this artifact

**Q: Where does the poster on a Trakt Trending Movies rail come from?**
Trace from `RIP` (RailItemPreview) up: the Trakt mapper sets `display.posterUrl = null` initially (Trakt API doesn't return posters), so first paint shows a placeholder. On detail open, `MetadataRouterFacade.resolveRequest` routes through `MetadataIdentityResolver` (Trakt → TMDB ID), then `ProviderPlanExecutor.buildPlan` schedules a `TmdbMetadataProviderAdapter` call, which goes through `runtime.get(IntegrationSpec(provider=TMDB, scope=GlobalContent, cachePolicy=CacheFirst))` → cache hit OR `RuntimeTraceInterceptor` HTTP → poster URL written into `ResolvedMetadataDocument.poster`.

**Q: Why does an addon catalog rail sometimes show stale data after an addon update?**
`CatalogDiskCacheStore` has **no TTL** — it persists indefinitely. The runtime call uses `ObserveOnlyOrMutation` (no runtime cache), so all caching is in the bespoke disk store. The only invalidation paths are: (a) explicit `clearCache()`, (b) the in-memory `ConcurrentHashMap` losing the entry on process death, or (c) snapshot-key change (poster provider hash, etc.). Flagged as a known follow-up.

**Q: Why might Profile A briefly see Profile B's TMDB rails?**
`TmdbDiscoveryService.snapshot` is a single global `MutableStateFlow` (no per-profile keying). When Profile B's `refreshCatalogs()` writes the snapshot, Profile A's UI sees Profile B's content until the next refresh runs. Same gap exists for `KitsuDiscoveryService`. SIMKL and MDBList do NOT have this problem because they use disk-backed per-profile snapshot stores. Documented in the Plan 6/7 KDoc as an outstanding follow-up.

**Q: How do I prove on-device that a particular rail came from cache vs. network?**
Toggle `Nexio.IntRuntime` in the troubleshooting menu, scroll Home, then `adb logcat -s Nexio.IntRuntime`. Look for `runtime.cache_decision` events:
- `decision=FRESH` → cache hit, no network (no subsequent `http.request`)
- `decision=EXPIRED_MISS` → cache miss, followed by `http.request`/`http.response` with the same `operationKey`
- `decision=STALE_HIT` → cache served stale value while a refresh runs in the background
- `decision=WRITE` → fresh value being persisted to cache
