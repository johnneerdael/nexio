# Phase 07: TVDB Provider Replacement - Research

**Researched:** 2026-04-14
**Domain:** Kotlin Android TV metadata-provider routing, TVDB API v4 mapping, TMDB fallback containment
**Confidence:** HIGH for current code inventory; MEDIUM for exact Phase 6 integration names because Phase 6 source code is not present in this checkout.

<user_constraints>
## User Constraints (from CONTEXT.md)

Source: copied from `.planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md`. [VERIFIED: 07-CONTEXT.md]

### Locked Decisions

### Provider Routing
- **D-01:** TVDB is the authoritative metadata provider for `ContentType.SERIES` / TV when TVDB is active. TMDB stays authoritative for movies.
- **D-02:** Normal TV success paths must not call TMDB TV endpoints for duplicate TV metadata purposes. TVDB-enabled detail, Home, Continue Watching, episode, and artwork enrichment should resolve through TVDB first.
- **D-03:** TMDB TV calls are allowed only as explicit fallback when TVDB is inactive, invalid, unavailable, or lacks a required record. Fallback must be observable through diagnostics or logs so it is not a silent double-fetch.
- **D-04:** Preserve current user-facing metadata contracts. Map TVDB data into the existing `Meta`, `Video`, `HomeDisplayMetadata`, and enrichment-style roles instead of redesigning TV detail, Home, or Continue Watching UI in this phase.

### Surface Coverage
- **D-05:** Phase 7 should replace the TV metadata roles currently served by `TmdbMetadataService.fetchEnrichment`, `fetchEpisodeEnrichment`, and `fetchSeasonEpisodes` for the scoped TV surfaces: title/description/basic details, TV artwork, episode title/overview/image/runtime/air date, Home display metadata, Continue Watching labels and runtime metadata.
- **D-06:** The planner should inventory direct `TmdbService.ensureTmdbId` and `TmdbMetadataService` usage before implementation, then classify each use as movie-only, TV replacement, explicit TV fallback, or deferred Phase 9 surface.
- **D-07:** Do not broaden Phase 7 into TVDB trailer, review, related-content, cast/company/network/reference-taxonomy feature work. Basic detail fields already displayed by the central enrichment path can be populated when the TVDB replacement model naturally supports them, but dedicated advanced surfaces remain deferred unless a small provider abstraction is needed to keep Phase 7 routing coherent.

### Artwork And Poster Precedence
- **D-08:** Poster-ratings integrations remain the highest-precedence poster source for supported titles. TVDB must not override RPDB or TopPosters poster URLs when a poster-ratings provider can produce a supported poster.
- **D-09:** Poster precedence applies only to poster imagery. TVDB remains the preferred source for non-poster TV artwork such as backdrops, logos, and episode images when TVDB provides usable assets.
- **D-10:** Reuse `PosterRatingsUrlResolver` semantics where possible. Its existing TVDB ID support is relevant for TopPosters, while RPDB remains IMDb-only by design.

### Settings And Provider Copy
- **D-11:** Settings copy should clearly state the provider order: TVDB is used for TV metadata when configured, TMDB remains for movies and TV fallback, and poster-ratings providers override poster artwork where supported.
- **D-12:** Do not add a second granular TVDB toggle matrix in this phase. Existing metadata intent toggles can continue to control whether artwork/basic info/details/episodes are enriched, while provider routing decides whether the TV implementation uses TVDB or TMDB fallback.
- **D-13:** The TMDB settings screen copy should stop implying TMDB is the only metadata source for TV once TVDB is configured. Any new TVDB settings UI should fit the existing Integration settings hub patterns.

### Caching, Identity, And Diagnostics
- **D-14:** TVDB cache entries should be separate from TMDB cache entries and named/schema-versioned as TVDB data, not overloaded into `tmdb::` keys. Cache keys should include TVDB series identity, content type or record kind, language where applicable, and poster-provider token where poster output is affected.
- **D-15:** Phase 7 should consume the Phase 6 identity/auth/cache foundation rather than reimplementing TVDB login or remote-ID matching. If Phase 6 context or code is absent, planning must call that out as a dependency.
- **D-16:** Diagnostics should distinguish these cases: TVDB inactive, TVDB active and successful, TVDB active but falling back to TMDB, poster-ratings poster override applied, and TMDB TV fetch intentionally skipped.

### Verification
- **D-17:** Tests or instrumentation must cover at least one TVDB-enabled normal success path where TMDB TV metadata calls are not issued. Prefer call-count assertions against `TmdbApi` or `TmdbMetadataService` in the relevant detail/Home/Continue Watching path.
- **D-18:** Poster precedence tests should prove poster-ratings poster URLs survive TVDB replacement while TVDB backdrops/logos/episode images remain eligible.

### Claude's Discretion
- Exact class names and adapter shape for provider routing, such as whether to introduce a provider-neutral metadata service or a `TvdbMetadataService` that mirrors the existing TMDB enrichment API.
- Exact TVDB image selection heuristics, language fallback order, and cache TTL/schema version values.
- Exact wording of settings copy, as long as provider precedence is clear and not misleading.
- Exact test file placement and test granularity, as long as the normal TV success path skip-TMDB behavior is covered.

### Deferred Ideas (OUT OF SCOPE)
- Exact Continue Watching availability instants from TVDB `airsTime` and episode aired date - Phase 8.
- Re-evaluation scheduling when withheld future episodes become available - Phase 8.
- TVDB default season ordering preservation and Trakt progress matching implications - Phase 9.
- TVDB trailers, remaining cast/credits, companies, networks, genre/reference taxonomy, content-rating reference behavior, and other advanced TV-specific surfaces - Phase 9.
- Update-aware TVDB cache invalidation, stable reference-data heavy caching, and user-facing TVDB docs - Phase 10.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PREF-02 | TVDB replaces TMDB as the metadata authority for TV/series metadata, detail pages, episodes, Continue Watching next-up metadata, artwork, trailers, related content, credits/cast, and networks when active. | Phase 7 should replace the scoped existing TV enrichment roles and defer trailers/related/reviews/cast/company/network deep surfaces to Phase 9 per `07-CONTEXT.md`; use a TV-only router so movies stay on TMDB. [VERIFIED: REQUIREMENTS.md; VERIFIED: 07-CONTEXT.md; VERIFIED: codebase rg] |
| PREF-03 | Normal TV success paths must not perform duplicate TMDB TV metadata fetches when TVDB is active. | Add call-count tests around a TVDB-success path and assert no `TmdbMetadataService.fetchEnrichment`, `fetchEpisodeEnrichment`, `fetchSeasonEpisodes`, or TV `TmdbApi` endpoints are called for that metadata purpose. [VERIFIED: 07-CONTEXT.md; VERIFIED: TmdbMetadataPerformanceTest.kt] |
| PREF-07 | Poster-ratings poster imagery wins over TMDB or TVDB poster metadata. | Keep `PosterRatingsUrlResolver` as the post-provider poster override layer; it already supports TopPosters `tvdb:` IDs and RPDB only for IMDb IDs. [VERIFIED: PosterRatingsUrlResolver.kt] |
| META-01 | TVDB enriches TV titles with TV-specific fields including schedule, runtime, network, country/language, status, aliases, translations, content ratings, and remote IDs. | TVDB `SeriesExtendedRecord` exposes `airsDays`, `airsTime`, `averageRuntime`, `originalNetwork`, `latestNetwork`, `originalCountry`, `originalLanguage`, `status`, `aliases`, `contentRatings`, `remoteIds`, and translations; map only fields that fit existing `Meta` / `HomeDisplayMetadata` in Phase 7. [CITED: tvdb.yml:3871; CITED: tvdb.yml:3940] |
| META-02 | TVDB enriches episode rows with title, overview, image, runtime, aired date, absolute number, specials placement, linked movie data, and finale type when present. | TVDB `EpisodeBaseRecord` and `EpisodeExtendedRecord` expose `name`, `overview`, `image`, `runtime`, `aired`, `absoluteNumber`, specials placement fields, `linkedMovie`, and `finaleType`; map the existing `Video` fields now and retain richer fields in provider DTOs for later phases. [CITED: tvdb.yml:2821; CITED: tvdb.yml:2890] |
| META-04 | TVDB artwork replaces TMDB TV artwork where TVDB provides artwork while honoring artwork controls and poster-ratings precedence. | TVDB has `/series/{id}/artworks` and extended-series `artworks`; map non-poster artwork to background/logo/episode image and apply poster-ratings only to poster URLs. [CITED: tvdb.yml:1685; CITED: tvdb.yml:3885; VERIFIED: PosterRatingsUrlResolver.kt] |
| UX-01 | Settings explain provider precedence. | Update TMDB copy and Integration hub copy because current strings say metadata fields come from TMDB and the hub subtitle is only "Metadata enrichment controls". [VERIFIED: strings.xml:156; VERIFIED: strings.xml:796] |
</phase_requirements>

## Project Constraints (from CLAUDE.md and AGENTS instructions)

- The app is Kotlin Android TV / Fire TV under package `com.nexio.tv`; Phase 7 should plan Android app code, not plugin release work. [VERIFIED: CLAUDE.md]
- Prefer small, targeted changes, preserve existing architecture and naming patterns, and do not introduce new libraries unless justified by the codebase. [VERIFIED: CLAUDE.md]
- Keep domain code free of Android framework dependencies. [VERIFIED: CLAUDE.md]
- Use local development commands `./gradlew assembleArm64Debug`, `./gradlew testArm64DebugUnitTest`, and targeted `--tests` invocations. [VERIFIED: CLAUDE.md]
- Do not bump plugin release versions or add root `CHANGELOG.md` release entries; this phase is app planning and should not touch plugin release metadata. [VERIFIED: user-provided AGENTS.md instructions]
- Do not read or expose `.thetvdb.apikey`; use checked-in `tvdb.yml` and mocked tests for planning and verification. [VERIFIED: 07-CONTEXT.md; VERIFIED: git status]

## Summary

Phase 7 should be planned as a TV-only provider routing replacement, not as a global TMDB removal. The current code concentrates scoped TV metadata in `TmdbMetadataService.fetchEnrichment`, `fetchEpisodeEnrichment`, and `fetchSeasonEpisodes`, but those methods are called from Detail, Home, Continue Watching, and startup Home hydration with direct `TmdbService.ensureTmdbId` lookups before each metadata call. [VERIFIED: TmdbMetadataService.kt; VERIFIED: MetaDetailsViewModel.kt; VERIFIED: HomeViewModelContinueWatching.kt; VERIFIED: HomeViewModelPresentationPipeline.kt; VERIFIED: HomeCatalogRefreshCoordinator.kt]

The lowest-risk architecture is to add a TV-specific provider router that consumes Phase 6 TVDB settings/auth/identity services, calls a new `TvdbMetadataService` for active TVDB success paths, and invokes TMDB only through an explicit fallback branch with diagnostics. Movies should keep the existing TMDB path, and deferred TV surfaces such as trailers, reviews, related content, cast, companies, networks-as-dedicated-surfaces, and organization discovery should remain on TMDB or be explicitly marked Phase 9. [VERIFIED: 07-CONTEXT.md; VERIFIED: ROADMAP.md; VERIFIED: codebase rg]

**Primary recommendation:** Implement `TvMetadataRouter` plus `TvdbMetadataService` that mirrors the existing TMDB enrichment roles for TV, maps into existing `Meta`, `Video`, and `HomeDisplayMetadata`, writes `tvdb::` cache entries, and makes skipped-TMDB/fallback decisions observable. [VERIFIED: 07-CONTEXT.md; VERIFIED: MetadataDiskCacheStore.kt; VERIFIED: domain model files]

## Standard Stack

### Core

| Library / Component | Version | Purpose | Why Standard |
|---------------------|---------|---------|--------------|
| Kotlin / Android Gradle Plugin | Kotlin `2.3.0`, AGP `8.13.2` | App implementation language and build system | Already used by this Android TV app; no new language/runtime should be introduced. [VERIFIED: gradle/libs.versions.toml:1] |
| Hilt | `2.58` | Inject `TvdbMetadataService`, provider router, Phase 6 identity/auth/settings services | Current metadata services and API clients use Hilt singleton injection. [VERIFIED: gradle/libs.versions.toml:13; VERIFIED: TmdbMetadataService.kt:42] |
| Retrofit + Moshi | Retrofit `2.9.0`, Moshi `1.15.1` | TVDB API interface and DTO mapping | Existing TMDB, Trakt, Simkl, and other APIs use Retrofit and shared Moshi. [VERIFIED: gradle/libs.versions.toml:15; VERIFIED: NetworkModule.kt:83; VERIFIED: NetworkModule.kt:331] |
| OkHttp | `4.12.0` | Shared HTTP client for Retrofit | Existing `NetworkModule` provides a shared OkHttp client with a 50 MB disk cache and timeouts. [VERIFIED: gradle/libs.versions.toml:16; VERIFIED: NetworkModule.kt:89] |
| AndroidX DataStore Preferences | `1.1.1` | Consume Phase 6 TVDB settings and existing TMDB setting toggles | Current provider settings are DataStore-backed flows. [VERIFIED: gradle/libs.versions.toml:21; VERIFIED: TmdbSettingsDataStore.kt:17] |
| `MetadataDiskCacheStore` | app component | Disk cache for provider-shaped metadata and Home references | Existing metadata caches include schema versions, language epoch, provider tokens, and cleanup paths that TVDB should mirror with a separate namespace. [VERIFIED: MetadataDiskCacheStore.kt:33; VERIFIED: MetadataDiskCacheStore.kt:159] |

### Supporting

| Library / Component | Version | Purpose | When to Use |
|---------------------|---------|---------|-------------|
| MockK | `1.13.12` | Call-count assertions and mocked provider services | Use for no-TMDB-call tests and fallback branch tests. [VERIFIED: app/build.gradle.kts:423] |
| kotlinx-coroutines-test | `1.8.1` | Unit tests for suspend provider methods and ViewModel pipelines | Existing tests use `runTest`; keep that pattern. [VERIFIED: app/build.gradle.kts:423; VERIFIED: HomeViewModelContinueWatchingTest.kt:9] |
| JUnit 4 | `4.13.2` | Unit test runner | Existing app unit tests use JUnit 4. [VERIFIED: app/build.gradle.kts:423] |
| Robolectric | `4.13` | Android framework-dependent unit tests such as resolver/settings tests | Use only when Context/Android APIs are needed. [VERIFIED: app/build.gradle.kts:428; VERIFIED: PosterRatingsUrlResolverTest.kt:12] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Retrofit/Moshi TVDB API | New TVDB SDK or Ktor client | Do not add a new SDK/client stack; Retrofit/Moshi is already the app's standard API layer, and `tvdb.yml` is enough to define DTOs. [VERIFIED: NetworkModule.kt:331; CITED: tvdb.yml:1] |
| TV-only provider router | Parallel TVDB + TMDB merge | Parallel merge violates the locked no-duplicate-TMDB success-path decision. [VERIFIED: 07-CONTEXT.md] |
| Separate `tvdb::` cache keys | Store TVDB-shaped data under `tmdb::` | Reusing `tmdb::` violates the locked cache namespace decision and makes fallback diagnostics ambiguous. [VERIFIED: 07-CONTEXT.md; VERIFIED: MetadataDiskCacheStore.kt:36] |

**Installation:** No new dependency is recommended for Phase 7. Use the existing Gradle catalog and app modules. [VERIFIED: gradle/libs.versions.toml; VERIFIED: CLAUDE.md]

**Version verification:** Versions above were verified from `gradle/libs.versions.toml` and `app/build.gradle.kts` in this checkout. This is an Android project, so the npm registry instruction is not applicable. [VERIFIED: gradle/libs.versions.toml; VERIFIED: app/build.gradle.kts]

## Architecture Patterns

### Recommended Project Structure

```text
app/src/main/java/com/nexio/tv/core/tvdb/
  TvdbApi.kt                  # Retrofit API generated by hand from tvdb.yml
  TvdbMetadataService.kt      # TVDB series/episode/artwork mapper
  TvdbMetadataModels.kt       # TVDB DTOs + provider-neutral TV metadata result types
  TvMetadataRouter.kt         # TV-only TVDB-first/TMDB-fallback decision point
  TvMetadataDiagnostics.kt    # provider decision/fallback result values

app/src/main/java/com/nexio/tv/data/local/
  MetadataDiskCacheStore.kt   # add tvdb:: and tvdb_episode:: entries

app/src/main/java/com/nexio/tv/ui/screens/detail/
  MetaDetailsViewModel.kt     # route series enrichment through TvMetadataRouter

app/src/main/java/com/nexio/tv/ui/screens/home/
  HomeViewModel*.kt           # route series Home/CW enrichment through TvMetadataRouter
```

This structure keeps TVDB provider code out of UI packages and keeps domain models Android-free. [VERIFIED: CLAUDE.md; VERIFIED: existing package layout]

### Pattern 1: TV-Only Router With Explicit Fallback

**What:** Introduce a router that handles only `ContentType.SERIES` / `TV` metadata and returns provider-neutral TV enrichment objects. Movies continue calling TMDB directly. [VERIFIED: 07-CONTEXT.md; VERIFIED: TmdbMetadataService.kt]

**When to use:** Use the router anywhere current TV code calls `ensureTmdbId` and then `fetchEnrichment`, `fetchEpisodeEnrichment`, or `fetchSeasonEpisodes`. [VERIFIED: codebase rg]

**Example:**

```kotlin
// Source: app pattern from TmdbMetadataService + Phase 7 decisions. [VERIFIED: TmdbMetadataService.kt; VERIFIED: 07-CONTEXT.md]
suspend fun fetchTvEnrichment(request: TvMetadataRequest): TvMetadataDecision<TvMetadataEnrichment> {
    if (!tvdbSettings.isActive()) {
        diagnostics.record(TvMetadataEvent.TvdbInactive)
        return tmdbFallback.fetchEnrichment(request, reason = "tvdb_inactive")
    }

    val tvdbIdentity = tvdbIdentityService.resolveSeries(request.contentId, request.contentType)
    if (tvdbIdentity == null) {
        diagnostics.record(TvMetadataEvent.TvdbMissingIdentity)
        return tmdbFallback.fetchEnrichment(request, reason = "tvdb_identity_missing")
    }

    val tvdb = tvdbMetadataService.fetchEnrichment(tvdbIdentity, request.language)
    if (tvdb != null) {
        diagnostics.record(TvMetadataEvent.TmdbTvSkipped)
        return TvMetadataDecision.Success(provider = TvProvider.TVDB, value = tvdb)
    }

    diagnostics.record(TvMetadataEvent.TvdbFallbackToTmdb)
    return tmdbFallback.fetchEnrichment(request, reason = "tvdb_record_missing")
}
```

### Pattern 2: Preserve Existing UI Contracts

**What:** Map TVDB into the existing `Meta`, `Video`, and `HomeDisplayMetadata` fields instead of changing screen contracts. [VERIFIED: 07-CONTEXT.md; VERIFIED: Meta.kt:6; VERIFIED: HomeDisplayMetadata.kt:6]

**When to use:** Detail, Home, Continue Watching, and snapshot hydration should receive the same field roles they currently get from TMDB: localized title, description, genres, rating, runtime, release info, country/language, poster/backdrop/logo, and episode title/overview/image/runtime/air date. [VERIFIED: TmdbMetadataService.kt:293; VERIFIED: MetaDetailsViewModel.kt:1245; VERIFIED: HomeViewModelContinueWatching.kt:135]

**Code mapping guidance:** Keep richer TVDB-only fields such as `airsDays`, `airsTime`, aliases, remote IDs, specials placement, linked movie ID, and finale type in provider DTOs or an internal `TvdbSeriesMetadata` result, but only push fields into UI models when a current model slot exists. [CITED: tvdb.yml:3871; CITED: tvdb.yml:2821; VERIFIED: Meta.kt]

### Pattern 3: Poster Override After Provider Mapping

**What:** Let TVDB choose native TV artwork first, then apply `PosterRatingsUrlResolver` only to poster imagery. [VERIFIED: PosterRatingsUrlResolver.kt:28; VERIFIED: PosterRatingsUrlResolver.kt:66]

**When to use:** Apply after creating `Meta` or `MetaPreview` with a TVDB poster fallback, and before writing display metadata that affects poster URLs. Include the active poster provider in cache keys when cached output stores poster results. [VERIFIED: TmdbMetadataService.kt:65; VERIFIED: MetadataDiskCacheStore.kt:159]

### Anti-Patterns to Avoid

- **Global TMDB replacement:** Do not route movie detail, movie collections, or movie trailers through TVDB. TMDB remains the movie provider. [VERIFIED: 07-CONTEXT.md; VERIFIED: TmdbApi.kt:58; VERIFIED: MetaDetailsViewModel.kt:1136]
- **Silent fallback:** Do not call TMDB after a TVDB failure without recording fallback reason. The phase requires observable fallback/skip behavior. [VERIFIED: 07-CONTEXT.md]
- **Reusing `TmdbEnrichment` as the TVDB public shape:** It works mechanically but leaks TMDB naming into new provider code and disk cache. Prefer provider-neutral `TvMetadataEnrichment` / `TvEpisodeMetadata` and adapt TMDB fallback into that shape. [VERIFIED: TmdbMetadataService.kt:994; VERIFIED: MetadataDiskCacheStore.kt:9]
- **Adding a granular TVDB toggle matrix:** Use the existing metadata intent toggles and provider routing; do not duplicate every TMDB setting for TVDB. [VERIFIED: 07-CONTEXT.md; VERIFIED: TmdbSettings.kt:3]

## TV Metadata Call-Site Classification

| Surface / File | Current TMDB Dependency | Phase 7 Classification | Planning Notes |
|----------------|-------------------------|------------------------|----------------|
| Detail basic/artwork/details/episodes in `MetaDetailsViewModel.enrichMeta` | `ensureTmdbId` then `fetchEnrichment`; if series, `fetchEpisodeEnrichment`. [VERIFIED: MetaDetailsViewModel.kt:1245] | Replace for TV; keep TMDB for movies and explicit TV fallback. | This is the highest-value no-TMDB-call test target because one method covers `Meta` and `Video` hydration. |
| Detail mark-season-watched | `ensureTmdbId` then `fetchSeasonEpisodes`, filtered by `AirDateGate`. [VERIFIED: MetaDetailsViewModel.kt:1866] | Replace for TV. | Router should return an authoritative season episode list with aired date; keep date-only gating until Phase 8. |
| Continue Watching item labels/artwork | `enrichContinueWatchingItemWithTmdb` calls `ensureTmdbId`, `fetchEnrichment`, and episode overview helper. [VERIFIED: HomeViewModelContinueWatching.kt:106] | Replace for TV. | Keep `HomeDisplayMetadata` merge semantics and existing fallbacks. |
| Continue Watching runtime warming | Runtime pipeline calls `fetchEpisodeEnrichment` for episode runtime and `fetchEnrichment` for title runtime. [VERIFIED: HomeViewModelContinueWatchingRuntimePipeline.kt:31] | Replace for TV. | Use TVDB episode runtime first when season/episode exists; use average runtime as title fallback. |
| Home focused item enrichment | Focus and adjacent prefetch call `ensureTmdbId` and `fetchEnrichment`. [VERIFIED: HomeViewModelPresentationPipeline.kt:377; VERIFIED: HomeViewModelPresentationPipeline.kt:460] | Replace for TV. | Rename pending state away from `pendingTmdb*` only where touched; avoid broad unrelated refactor. |
| Home hero enrichment | `enrichHeroItemsPipeline` calls `ensureTmdbId` and `fetchEnrichment`. [VERIFIED: HomeViewModelPresentationPipeline.kt:635] | Replace for TV. | Signature should include TVDB active/provider token/language, not only TMDB settings. |
| Startup/Home catalog hydration | `HomeCatalogRefreshCoordinator.overlayTmdbLocalizedMetadata` overlays localized TMDB metadata and warms the disk cache. [VERIFIED: HomeCatalogRefreshCoordinator.kt:69] | Replace for TV. | This is the other strong no-TMDB-call test target because it is isolated and logs metadata hydrate events. |
| More Like This | `fetchMoreLikeThis` uses TV recommendations and extra TV image/detail calls. [VERIFIED: TmdbMetadataService.kt:456] | Deferred Phase 9 for TV; movie-only remains TMDB. | Do not block Phase 7 on TVDB related-content parity. |
| Reviews | `fetchReviews` calls TV reviews for TV. [VERIFIED: TmdbMetadataService.kt:583; VERIFIED: MetaDetailsViewModel.kt:925] | Deferred Phase 9 for TV; movie reviews remain TMDB/Trakt as today. | Settings copy should avoid promising TVDB reviews in Phase 7. |
| Trailers / title and season media availability | Detail and Home pass TMDB IDs into `TrailerService`; `TrailerService` calls TV videos, season videos, and TV details. [VERIFIED: MetaDetailsViewModel.kt:1467; VERIFIED: HomeViewModelPresentationPipeline.kt:204; VERIFIED: TrailerService.kt:236] | Deferred Phase 9 for TV. | Avoid including trailer endpoints in the Phase 7 no-TMDB metadata assertion unless explicitly testing deferred status. |
| Cast/company/network dedicated surfaces | `TmdbOrganizationService` discovers TV companies/networks. [VERIFIED: TmdbOrganizationService.kt:37] | Deferred Phase 9 for TV; movie company discovery remains TMDB. | Phase 7 may fill current `Meta.networks` from series extended if cheap, but should not build TVDB organization discovery. |
| Episode rating fallback | `EpisodeRatingsSelectionRepository` and IMDb/OMDb repositories use TMDB ID conversion and episode enrichment for rating fallback. [VERIFIED: EpisodeRatingsSelectionRepository.kt:46; VERIFIED: CustomImdbEpisodeRatingsRepository.kt:112] | Defer or treat as non-metadata fallback, not a Phase 7 replacement target. | Planner should state whether no-TMDB-call tests disable rating integrations or assert only metadata-service calls. |
| MDBList IMDb resolution | Uses `ensureTmdbId` / `tmdbToImdb` when IDs are missing. [VERIFIED: MDBListRepository.kt:360] | Explicit identity fallback, not TV metadata enrichment. | Phase 6 TVDB identity may reduce this later, but not a Phase 7 blocker. |
| TMDB settings validation | `TmdbSettingsViewModel` validates TMDB API configuration. [VERIFIED: TmdbSettingsViewModel.kt] | Keep. | Update copy only; TMDB remains movie and fallback provider. |

## Existing Model / API Shape To Mirror

- `TmdbMetadataService.fetchEnrichment(tmdbId, contentType, language)` returns localized title, description, genres, backdrop, logo, poster, release info, rating, runtime, age rating, countries, language, and advanced cast/production/network fields. TVDB should mirror the scoped fields and avoid Phase 9-only advanced expansion. [VERIFIED: TmdbMetadataService.kt:58; VERIFIED: TmdbMetadataService.kt:293; VERIFIED: 07-CONTEXT.md]
- `TmdbMetadataService.fetchEpisodeEnrichment(tmdbId, seasonNumbers, language)` returns a `Map<Pair<Int, Int>, TmdbEpisodeEnrichment>` keyed by season and episode. TVDB should keep that key shape for easy replacement in `MetaDetailsViewModel`, Continue Watching, and runtime warming. [VERIFIED: TmdbMetadataService.kt:351; VERIFIED: TmdbMetadataService.kt:1018]
- `TmdbMetadataService.fetchSeasonEpisodes(tvId, seasonNumber, language)` returns ordered raw season episodes with `airDate` for date-only gating. TVDB should return a provider-neutral `TvSeasonEpisode` with episode number, aired date, and optional title/runtime/image. [VERIFIED: TmdbMetadataService.kt:335; VERIFIED: AirDateGate.kt:3]
- `HomeDisplayMetadata` has only title, logo, description, genres, releaseInfo, runtime, IMDb rating, tomatoes rating, poster, and backdrop. TVDB fields without slots should not force UI contract changes in Phase 7. [VERIFIED: HomeDisplayMetadata.kt:6]
- `Video` has title, released, thumbnail, season, episode, overview, and runtime; TVDB `absoluteNumber`, specials placement, linked movie, and finale type should be retained internally or deferred because the current UI model has no slots. [VERIFIED: Meta.kt:67; CITED: tvdb.yml:2821]

## Cache And Diagnostics Patterns

- Existing provider cache keys include provider namespace, item identity, language tag, poster-provider token, schema version, updated timestamp, and language epoch; TVDB should add separate `TVDB_PREFIX` / `TVDB_EPISODE_PREFIX` constants and include those entries in stale-epoch cleanup. [VERIFIED: MetadataDiskCacheStore.kt:33; VERIFIED: MetadataDiskCacheStore.kt:159; VERIFIED: MetadataDiskCacheStore.kt:396]
- Existing Home metadata hydration logs `metadata_hydrate_start`, `item_metadata_cached`, `item_metadata_fetch`, and `metadata_hydrate_end`; TVDB provider decisions should use similarly structured diagnostic events, not UI toasts during browsing. [VERIFIED: HomeCatalogRefreshCoordinator.kt:238; VERIFIED: 07-CONTEXT.md]
- Recommended diagnostic events: `tvdb_inactive_tmdb_fallback`, `tvdb_success`, `tvdb_fallback_tmdb`, `tmdb_tv_skipped`, and `poster_ratings_override`. These event names are recommended, not existing code. [ASSUMED]
- TVDB cache key pattern should be `tvdb::<seriesId>::<kind>::<languageTag>::<providerToken>::v<schema>` for series/display metadata and `tvdb_episode::<seriesId>::default::<season>::<languageTag>::v<schema>` for season episodes. This follows existing schema/language/provider-token patterns but the exact string is a planning recommendation. [VERIFIED: MetadataDiskCacheStore.kt:439; ASSUMED]

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| HTTP client stack | Custom `HttpURLConnection` or a new SDK | Retrofit/Moshi using `NetworkModule` patterns | Existing API layer already uses Retrofit/Moshi and shared OkHttp. [VERIFIED: NetworkModule.kt:331] |
| TVDB authentication | New login/token persistence in Phase 7 | Phase 6 TVDB auth/token service | Phase 7 is explicitly dependent on Phase 6 and must not reimplement login. [VERIFIED: 07-CONTEXT.md; VERIFIED: ROADMAP.md] |
| Remote-ID matching | TMDB lookup as normal TV identity path | Phase 6 TVDB identity service | Requirement PREF-06 assigns broad TVDB identity matching to Phase 6. [VERIFIED: REQUIREMENTS.md; VERIFIED: 06-CONTEXT.md] |
| Poster precedence | Per-screen poster URL conditionals | `PosterRatingsUrlResolver` applied after provider mapping | Resolver already centralizes RPDB/TopPosters semantics and ID parsing. [VERIFIED: PosterRatingsUrlResolver.kt] |
| Date gating rewrite | Exact airtime availability in Phase 7 | Existing `AirDateGate` date-only behavior | Exact TVDB `airsTime` handling is Phase 8. [VERIFIED: ROADMAP.md; VERIFIED: AirDateGate.kt] |
| Parallel TVDB/TMDB merge | Fetch both providers and opportunistically merge | TVDB-first router with explicit TMDB fallback | Parallel merge violates the phase's no-duplicate-TMDB decision. [VERIFIED: 07-CONTEXT.md] |

**Key insight:** The hard part is not calling TVDB; it is removing implicit TMDB TV calls from existing UI pipelines without breaking movie TMDB behavior, poster-ratings precedence, or fallback observability. [VERIFIED: codebase rg; VERIFIED: 07-CONTEXT.md]

## Runtime State Inventory

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| Stored data | `MetadataDiskCacheStore` stores `meta::`, `tmdb::`, `tmdb_videos::`, `tmdb_season_videos::`, and `home_ref::` entries in shared preferences. [VERIFIED: MetadataDiskCacheStore.kt:33] | Add TVDB-specific entries; do not migrate `tmdb::` entries. TMDB caches can remain for movie and explicit TV fallback. |
| Live service config | No TVDB live service config exists in app source; Phase 6 context describes TVDB settings/auth foundation but app code has no `Tvdb*` source classes in this checkout. [VERIFIED: rg tvdb app/src/main/java; VERIFIED: 06-CONTEXT.md] | Plan Phase 7 as dependent on Phase 6 code or include a blocker step to verify Phase 6 merged. |
| OS-registered state | None found for provider names in app source. [VERIFIED: rg tvdb app/src/main/java] | No OS state migration. |
| Secrets/env vars | TMDB API key is in `TmdbSettingsDataStore`; TVDB credentials are Phase 6-owned and must stay secret-backed. [VERIFIED: TmdbSettingsDataStore.kt:28; VERIFIED: 06-CONTEXT.md] | Do not read or expose local TVDB key files; use mocked TVDB tests. |
| Build artifacts | No provider-specific build artifacts found. [VERIFIED: rg tvdb app/src/main/java app/src/test/java] | None. |

## Common Pitfalls

### Pitfall 1: Hidden TMDB Calls Through ID Resolution

**What goes wrong:** Replacing `fetchEnrichment` but leaving `ensureTmdbId` before TVDB provider routing still calls TMDB for TV identity. [VERIFIED: TmdbService.kt:214; VERIFIED: MetaDetailsViewModel.kt:1251]

**Why it happens:** Current pipelines resolve TMDB ID before every TMDB metadata call. [VERIFIED: codebase rg]

**How to avoid:** For series paths, resolve TVDB identity through Phase 6 first and call TMDB ID resolution only in explicit fallback. [VERIFIED: 07-CONTEXT.md; VERIFIED: 06-CONTEXT.md]

**Warning signs:** New code calls `tmdbService.ensureTmdbId(..., "series")` before checking TVDB active/success state. [VERIFIED: codebase rg]

### Pitfall 2: Treating Trailer Calls As Phase 7 Metadata

**What goes wrong:** Trailer availability and playback routes keep TMDB TV video calls, causing a broad refactor if included in Phase 7. [VERIFIED: TrailerService.kt:236; VERIFIED: TrailerService.kt:506]

**Why it happens:** Home and Detail call `ensureTmdbId` for trailer availability near metadata enrichment code. [VERIFIED: HomeViewModelPresentationPipeline.kt:204; VERIFIED: MetaDetailsViewModel.kt:1467]

**How to avoid:** Classify trailers as Phase 9 and keep Phase 7 tests focused on metadata methods, unless the user explicitly expands scope. [VERIFIED: 07-CONTEXT.md]

**Warning signs:** Plan tasks modify `TrailerService` TVDB trailer discovery before Detail/Home metadata skip-TMDB tests exist. [VERIFIED: ROADMAP.md]

### Pitfall 3: Poster Override Regression

**What goes wrong:** TVDB poster URLs overwrite RPDB or TopPosters URLs. [VERIFIED: 07-CONTEXT.md]

**Why it happens:** Provider mappers set poster fields after poster-ratings has already been applied, or cache TVDB posters without provider token separation. [VERIFIED: PosterRatingsUrlResolver.kt; VERIFIED: MetadataDiskCacheStore.kt:159]

**How to avoid:** Apply poster-ratings as the last poster step and include the poster provider token when cached output includes poster URL. [VERIFIED: TmdbMetadataService.kt:65; VERIFIED: PosterRatingsUrlResolver.kt:28]

**Warning signs:** Tests only assert backdrop/logo replacement and do not assert poster-ratings survival. [VERIFIED: PosterRatingsUrlResolverTest.kt]

### Pitfall 4: Overfitting TVDB Fields Into UI Models

**What goes wrong:** Planning expands into new UI for TVDB season order, aliases, remote IDs, exact air times, trailers, cast, and reference taxonomies. [VERIFIED: 07-CONTEXT.md; VERIFIED: ROADMAP.md]

**Why it happens:** TVDB exposes rich data in `SeriesExtendedRecord` and `EpisodeBaseRecord`, but Phase 7's locked boundary is existing metadata roles. [CITED: tvdb.yml:3871; CITED: tvdb.yml:2821; VERIFIED: 07-CONTEXT.md]

**How to avoid:** Preserve richer values internally only when needed for Phase 8/9, and map current fields into `Meta`, `Video`, and `HomeDisplayMetadata`. [VERIFIED: Meta.kt; VERIFIED: HomeDisplayMetadata.kt]

**Warning signs:** Plan tasks add new user-facing fields or season-order controls before provider replacement is complete. [VERIFIED: ROADMAP.md]

## Code Examples

### Provider-Neutral Episode Mapping

```kotlin
// Source: existing TmdbEpisodeEnrichment shape + TVDB EpisodeBaseRecord fields.
// [VERIFIED: TmdbMetadataService.kt:1018; CITED: tvdb.yml:2821]
data class TvEpisodeMetadata(
    val providerEpisodeId: String?,
    val title: String?,
    val overview: String?,
    val thumbnail: String?,
    val airDate: String?,
    val runtimeMinutes: Int?,
    val absoluteNumber: Int? = null,
    val finaleType: String? = null
)

fun TvEpisodeMetadata.applyTo(video: Video): Video =
    video.copy(
        title = title ?: video.title,
        overview = overview ?: video.overview,
        released = airDate ?: video.released,
        thumbnail = thumbnail ?: video.thumbnail,
        runtime = runtimeMinutes ?: video.runtime
    )
```

### TVDB Cache Entry Pattern

```kotlin
// Source: MetadataDiskCacheStore's schema/language/provider-token pattern.
// [VERIFIED: MetadataDiskCacheStore.kt:159; VERIFIED: MetadataDiskCacheStore.kt:439]
private const val TVDB_PREFIX = "tvdb::"
private const val TVDB_EPISODE_PREFIX = "tvdb_episode::"
private const val TVDB_CACHE_SCHEMA_VERSION = 1

private fun buildTvdbSeriesKey(
    seriesId: Int,
    recordKind: String,
    languageTag: String,
    providerToken: String
): String = "$TVDB_PREFIX$seriesId::$recordKind::$languageTag::$providerToken"
```

### No-TMDB Success Path Test Shape

```kotlin
// Source: existing MockK call-count tests.
// [VERIFIED: TmdbMetadataPerformanceTest.kt:84; VERIFIED: HomeViewModelContinueWatchingTest.kt:50]
@Test
fun `tvdb active detail enrichment does not call tmdb tv metadata`() = runTest {
    coEvery { tvMetadataRouter.fetchTvEnrichment(any()) } returns TvMetadataDecision.Success(
        provider = TvProvider.TVDB,
        value = tvdbEnrichment(title = "TVDB title")
    )

    viewModel.loadSeries(...)
    advanceUntilIdle()

    coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), ContentType.SERIES, any()) }
    coVerify(exactly = 0) { tmdbMetadataService.fetchEpisodeEnrichment(any(), any(), any()) }
    coVerify(exactly = 0) { tmdbMetadataService.fetchSeasonEpisodes(any(), any(), any()) }
}
```

## State of the Art

| Old Approach | Current Approach For Phase 7 | When Changed | Impact |
|--------------|------------------------------|--------------|--------|
| TMDB as default TV enrichment provider | TVDB-first for TV, TMDB movie provider, explicit TMDB TV fallback | Milestone v1.1 Phase 7 decision on 2026-04-14 | Plans must route TV through TVDB before TMDB. [VERIFIED: 07-CONTEXT.md; VERIFIED: ROADMAP.md] |
| Provider-specific UI data from `TmdbEnrichment` | Provider-neutral TV metadata returned by router | Phase 7 recommendation | Reduces TMDB naming leakage and makes skip/fallback diagnostics central. [VERIFIED: TmdbMetadataService.kt; ASSUMED] |
| TMDB cache namespace for all provider-shaped metadata | Separate `tvdb::` namespace with schema version | Phase 7 locked decision | Prevents cache ambiguity and supports explicit fallback. [VERIFIED: 07-CONTEXT.md; VERIFIED: MetadataDiskCacheStore.kt] |
| Date-only Continue Watching gating | Keep date-only gating in Phase 7; exact `airsTime` gating in Phase 8 | Roadmap split | Avoids absorbing Phase 8 scheduling and timezone policy. [VERIFIED: ROADMAP.md; VERIFIED: AirDateGate.kt] |

**Deprecated/outdated for Phase 7:**
- TMDB settings copy that says metadata fields should come from TMDB for all content is outdated once TVDB is configured for TV. [VERIFIED: strings.xml:796; VERIFIED: 07-CONTEXT.md]
- Direct series `ensureTmdbId` before metadata enrichment is outdated for TVDB-active normal success paths. [VERIFIED: codebase rg; VERIFIED: 07-CONTEXT.md]

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Recommended diagnostic event names are new and not existing code. | Cache And Diagnostics Patterns | Planner may choose different names; behavior requirements still stand. |
| A2 | Recommended exact TVDB cache-key string is a planning recommendation, not an existing app convention. | Cache And Diagnostics Patterns | Planner may need to align with Phase 6 cache helpers if they exist on the execution branch. |
| A3 | Provider-neutral `TvMetadataEnrichment` is recommended instead of reusing `TmdbEnrichment`. | Architecture Patterns / State of the Art | If implementers prefer minimal diff, they may adapt `TmdbEnrichment`, but must avoid TMDB-named TVDB cache/schema leakage. |

## Open Questions (RESOLVED)

1. **Has Phase 6 merged into the execution branch?**
   - What we know: This checkout has Phase 6 planning context but no app source classes named `Tvdb*`. [VERIFIED: rg tvdb app/src/main/java; VERIFIED: 06-CONTEXT.md]
   - Resolution: Phase 6 source availability is enforced by Plan 01's prerequisite gate. If the required Phase 6 settings, auth, identity, and TVDB API source files are absent, Phase 7 stops before provider replacement code is written. [VERIFIED: 07-01-PLAN.md]

2. **Which TVDB artwork type IDs map to posters, backgrounds, and logos?**
   - What we know: TVDB artwork records have `type`, and `type` corresponds to `/artwork/types`. [CITED: tvdb.yml:2320; CITED: tvdb.yml:1685]
   - Resolution: Phase 7 uses local deterministic heuristics plus fixture tests: `type == 2` poster, `type == 3` backdrop, `type == 23` logo, sorted by descending score with `SeriesExtendedRecord.image` as poster fallback. Reference-data caching remains deferred to Phase 10. [VERIFIED: 07-02-PLAN.md]

3. **How strict should no-TMDB-call assertions be around non-metadata integrations?**
   - What we know: Episode ratings, trailers, reviews, and MDBList can still trigger TMDB calls outside scoped Phase 7 metadata. [VERIFIED: EpisodeRatingsSelectionRepository.kt; VERIFIED: TrailerService.kt; VERIFIED: MetaDetailsViewModel.kt]
   - Resolution: No-TMDB assertions are scoped to Phase 7 TV metadata calls. They prove TVDB-success metadata paths avoid `TmdbMetadataService` and pre-router `TmdbService.ensureTmdbId`; they do not assert global zero TMDB usage for trailers, reviews, ratings, MDBList, or other deferred integrations. [VERIFIED: 07-CONTEXT.md; VERIFIED: 07-02-PLAN.md]

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| JDK | Gradle Android build/tests | Yes | OpenJDK `17.0.18` | None needed. [VERIFIED: java -version] |
| Gradle wrapper | Build and unit tests | Yes | Gradle `8.13`, Kotlin runtime `2.0.21` in wrapper output | None needed. [VERIFIED: ./gradlew --version] |
| `tvdb.yml` | TVDB API contract | Yes | TVDB API v4 spec `4.7.10`, 4263 lines | Use `tvdb.yml`; do not use live credentials for planning. [CITED: tvdb.yml:12; VERIFIED: wc tvdb.yml] |
| Live TVDB credentials | Manual live smoke only | Not required | - | Use mocked Retrofit/DTO tests; live API tests should not be phase gate. [VERIFIED: 07-CONTEXT.md] |

**Missing dependencies with no fallback:**
- Phase 6 source implementation is not present in this checkout; Phase 7 implementation should be blocked or explicitly include a prerequisite verification step if the execution branch is the same. [VERIFIED: rg tvdb app/src/main/java; VERIFIED: ROADMAP.md]

**Missing dependencies with fallback:**
- Live TVDB API access is not needed for automated tests; use mocks and checked-in `tvdb.yml` DTO fixtures. [VERIFIED: app/build.gradle.kts:423; CITED: tvdb.yml]

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 4, MockK, kotlinx-coroutines-test, Robolectric where Android APIs are needed. [VERIFIED: app/build.gradle.kts:423] |
| Config file | Gradle module config in `app/build.gradle.kts`. [VERIFIED: app/build.gradle.kts] |
| Quick run command | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.*"` [VERIFIED: CLAUDE.md; ASSUMED package] |
| Full suite command | `./gradlew testArm64DebugUnitTest` [VERIFIED: CLAUDE.md] |

### Phase Requirements To Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| PREF-02 | TVDB series enrichment maps into existing `Meta` / `HomeDisplayMetadata` roles. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"` | No - Wave 0. [ASSUMED] |
| PREF-03 | TVDB-active normal TV success path skips TMDB TV metadata calls. | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.detail.MetaDetailsTvdbProviderRoutingTest"` | No - Wave 0. [ASSUMED] |
| PREF-07 | Poster-ratings poster survives TVDB replacement while TVDB non-poster artwork applies. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.poster.PosterRatingsUrlResolverTest"` | Existing file, needs new TVDB assertion. [VERIFIED: PosterRatingsUrlResolverTest.kt] |
| META-01 | TVDB series fields map to current title/detail/artwork fields without UI model redesign. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataMapperTest"` | No - Wave 0. [ASSUMED] |
| META-02 | TVDB episode fields map to `Video` rows and season episode list. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbEpisodeMapperTest"` | No - Wave 0. [ASSUMED] |
| META-04 | TVDB artwork is used for backdrop/logo/episode image and poster-ratings controls poster. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbArtworkMapperTest"` | No - Wave 0. [ASSUMED] |
| UX-01 | Settings copy states TVDB for TV, TMDB for movies/fallback, poster-ratings for posters. | unit or resource review | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.settings.ProviderPrecedenceCopyTest"` | No - Wave 0. [ASSUMED] |

### Sampling Rate

- **Per task commit:** Run the targeted test class for the touched mapper/router/ViewModel. [VERIFIED: CLAUDE.md]
- **Per wave merge:** Run `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.*"` plus touched existing Detail/Home/poster tests. [VERIFIED: CLAUDE.md; ASSUMED package]
- **Phase gate:** Run `./gradlew testArm64DebugUnitTest` and verify at least one TVDB-success path has zero TMDB TV metadata calls. [VERIFIED: CLAUDE.md; VERIFIED: 07-CONTEXT.md]

### Wave 0 Gaps

- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt` - mapper/API service tests for `META-01`, `META-02`, `META-04`. [ASSUMED]
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt` - active/success/fallback/skip-TMDB decision tests for `PREF-02`, `PREF-03`. [ASSUMED]
- [ ] `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt` or equivalent - representative UI pipeline no-TMDB-call test. [ASSUMED]
- [ ] Add TVDB ID assertion to `PosterRatingsUrlResolverTest.kt` for TopPosters `tvdb:` poster URLs. [VERIFIED: PosterRatingsUrlResolverTest.kt; VERIFIED: PosterRatingsUrlResolver.kt:142]

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|------------------|
| V2 Authentication | Yes | Consume Phase 6 TVDB bearer-token service; do not implement separate auth in Phase 7. [VERIFIED: 06-CONTEXT.md; CITED: tvdb.yml:5] |
| V3 Session Management | Limited | Token lifecycle is Phase 6-owned; Phase 7 should only handle inactive/invalid/unavailable provider states. [VERIFIED: 06-CONTEXT.md; VERIFIED: 07-CONTEXT.md] |
| V4 Access Control | No new user authorization surface | Existing primary-profile settings gating applies to integrations hub; Phase 7 copy changes should follow existing settings patterns. [VERIFIED: SettingsScreen.kt:752] |
| V5 Input Validation | Yes | Normalize and validate provider IDs, content type, language tag, and season/episode numbers before API/cache calls. [VERIFIED: TmdbService.kt:214; VERIFIED: TmdbMetadataService.kt:707] |
| V6 Cryptography | Yes for secrets | Do not hand-roll crypto or expose TVDB API key/PIN; use Phase 6 secret-backed settings. [VERIFIED: 06-CONTEXT.md] |

### Known Threat Patterns for TVDB Provider Routing

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| API key/PIN leakage in logs, diagnostics, cache keys, or research docs | Information Disclosure | Never log credentials; use hash/provider tokens only for cache partitioning; do not read `.thetvdb.apikey`. [VERIFIED: 06-CONTEXT.md; VERIFIED: TmdbMetadataService.kt:728] |
| Silent fallback hides provider outage or unexpected TMDB fetches | Repudiation / Information Disclosure | Emit diagnostic events for inactive, success, fallback, poster override, and skip-TMDB. [VERIFIED: 07-CONTEXT.md] |
| Cache poisoning between TMDB and TVDB records | Tampering | Use separate cache prefixes and schema versions; include language and provider token where output varies. [VERIFIED: 07-CONTEXT.md; VERIFIED: MetadataDiskCacheStore.kt:33] |
| Invalid IDs causing wrong provider lookup | Tampering | Normalize content IDs and use Phase 6 TVDB identity service before provider calls. [VERIFIED: TmdbService.kt:214; VERIFIED: 06-CONTEXT.md] |

## Sources

### Primary (HIGH confidence)

- `.planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md` - locked decisions, scope boundaries, cache/diagnostic requirements. [VERIFIED]
- `.planning/ROADMAP.md` - Phase 7 dependency, success criteria, Phase 8/9/10 boundaries. [VERIFIED]
- `.planning/REQUIREMENTS.md` - phase requirement IDs and provider precedence requirements. [VERIFIED]
- `CLAUDE.md` - project build/test and architecture constraints. [VERIFIED]
- `tvdb.yml` - local TVDB API v4 OpenAPI contract for auth, series, artwork, episode, and schema fields. [CITED]
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt` - current TMDB enrichment API and cache behavior. [VERIFIED]
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbService.kt` - current TMDB ID conversion and in-flight de-duping. [VERIFIED]
- `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt` - disk cache schema/prefix/language/provider-token patterns. [VERIFIED]
- `app/src/main/java/com/nexio/tv/domain/model/Meta.kt` and `HomeDisplayMetadata.kt` - UI-facing contracts to preserve. [VERIFIED]
- Detail/Home/Continue Watching source files listed in call-site classification. [VERIFIED]

### Secondary (MEDIUM confidence)

- `.planning/phases/06-tvdb-foundation-and-identity/06-CONTEXT.md` - Phase 6 intended auth/settings/identity foundation; source implementation is absent in this checkout. [VERIFIED]

### Tertiary (LOW confidence)

- None from web search. No external web search was needed because the checked-in `tvdb.yml` is the project-canonical TVDB API source for this phase. [VERIFIED: 07-CONTEXT.md]

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - verified from Gradle catalog, build file, and existing Hilt/Retrofit/Moshi patterns. [VERIFIED: gradle/libs.versions.toml; VERIFIED: NetworkModule.kt]
- Architecture: MEDIUM - current call sites are verified, but exact Phase 6 class names are unavailable in source. [VERIFIED: codebase rg; VERIFIED: rg tvdb app/src/main/java]
- Pitfalls: HIGH for hidden TMDB calls and deferred surfaces because code paths and phase boundaries are explicit. [VERIFIED: codebase rg; VERIFIED: 07-CONTEXT.md]
- TVDB field mapping: HIGH for field availability in `tvdb.yml`; MEDIUM for image type heuristics because artwork type IDs were not resolved. [CITED: tvdb.yml; ASSUMED]

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 for codebase-specific findings, or until Phase 6 implementation lands and changes provider class names.
