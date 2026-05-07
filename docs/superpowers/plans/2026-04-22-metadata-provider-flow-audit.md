# Metadata Provider Flow Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a current-state, engineer-facing source of truth for how Nexio populates metadata across modern home, continue watching, and detail flows for movie, TV, and anime, including provider priority, identity propagation, storage, caching, and exact API endpoints.

**Architecture:** Write one canonical architecture document in `docs/architecture/metadata-provider-routing-audit.md` and keep the bulky evidence in `docs/research/metadata-audit/`. Use only checked-in Kotlin code, checked-in API blueprints, and checked-in docs as admissible evidence. This pass documents current behavior only; it must not propose or implement rearchitecture beyond clearly labeled ambiguity and duplication findings.

**Tech Stack:** Markdown, Kotlin Android sources, Retrofit interfaces, SharedPreferences/DataStore stores, checked-in API blueprints in `apiblueprints/`, `rg`, `sed`, `git`.

---

## Scope Decision

- This plan is for a current-state audit, not a behavior change.
- Do not open an OpenSpec change in this pass. Save architectural change proposals for the follow-up rearchitecture once the audit is reviewed.
- Do not modify runtime code unless a tiny mechanical helper is absolutely required to prevent documentation errors. Prefer docs-only output.
- If the audit surfaces correctness bugs, record them in a `Known Ambiguities And Drift` section with code references instead of fixing them here.

## File Structure

- Create: `docs/architecture/metadata-provider-routing-audit.md`
  - Canonical narrative and decision-ladder document. This is the file engineers should read first.
- Create: `docs/research/metadata-audit/provider-endpoint-index.md`
  - Provider-by-provider endpoint appendix covering checked-in Retrofit interfaces and checked-in API blueprints.
- Create: `docs/research/metadata-audit/field-source-matrix.md`
  - Table-heavy appendix covering Movie, TV, Anime, Images, and Cache/Storage matrices.

Evidence sources to cite directly in those docs:

- Home identity and row construction:
  - `app/src/main/java/com/nexio/tv/domain/model/CatalogRow.kt`
  - `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt`
  - `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/KitsuDiscoveryService.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/MDBListDiscoveryService.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/SimklDiscoveryService.kt`
- Snapshot and cache stores:
  - `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
  - `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt`
  - `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
  - `app/src/main/java/com/nexio/tv/data/local/CatalogDiskCacheStore.kt`
  - `app/src/main/java/com/nexio/tv/data/local/TvdbIdentityCacheStore.kt`
  - `app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt`
  - `app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt`
  - `app/src/main/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStore.kt`
- Detail and enrichment routing:
  - `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
  - `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt`
  - `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt`
  - `app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt`
  - `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`
  - `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
  - `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt`
  - `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
  - `app/src/main/java/com/nexio/tv/core/tvdb/TvdbPersonService.kt`
  - `app/src/main/java/com/nexio/tv/core/tmdb/TmdbOrganizationService.kt`
- Ratings, posters, trailers, and skip-intro:
  - `app/src/main/java/com/nexio/tv/data/repository/TitleRatingOverrideRepository.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/EpisodeRatingsSelectionRepository.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepository.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/CustomImdbTitleRatingsRepository.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/CustomImdbEpisodeRatingsRepository.kt`
  - `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
  - `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`
  - `app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerResolver.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt`
- Provider interface and blueprint references:
  - `app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt`
  - `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt`
  - `app/src/main/java/com/nexio/tv/data/remote/api/KitsuApi.kt`
  - `app/src/main/java/com/nexio/tv/data/remote/api/MDBListApi.kt`
  - `app/src/main/java/com/nexio/tv/data/remote/api/OmdbApi.kt`
  - `app/src/main/java/com/nexio/tv/data/remote/api/TraktApi.kt`
  - `app/src/main/java/com/nexio/tv/data/remote/api/SimklApi.kt`
  - `app/src/main/java/com/nexio/tv/data/remote/api/SkipIntroApi.kt`
  - `app/src/main/java/com/nexio/tv/data/remote/api/PosterRatingsApi.kt`
  - `apiblueprints/tmdb.json`
  - `apiblueprints/tvdb.yml`
  - `apiblueprints/kitsu.apib`
  - `apiblueprints/mdblist.apib`
  - `apiblueprints/trakt.apib`
  - `apiblueprints/simkl.apib`
  - `apiblueprints/rpdb.apib`
  - `apiblueprints/topposters.json`
  - `apiblueprints/tidb.yaml`

### Task 1: Scaffold The Canonical Doc And Appendix Layout

**Files:**
- Create: `docs/architecture/metadata-provider-routing-audit.md`
- Create: `docs/research/metadata-audit/provider-endpoint-index.md`
- Create: `docs/research/metadata-audit/field-source-matrix.md`

- [ ] **Step 1: Create the canonical architecture doc shell**

Create `docs/architecture/metadata-provider-routing-audit.md` with this exact starting structure:

```md
---
title: Metadata Provider Routing Audit
status: draft
date: 2026-04-22
---

# Metadata Provider Routing Audit

This document is the current-state source of truth for how Nexio decides which metadata provider to use, what identity each surface carries forward, how metadata is enriched on home and detail flows, and where each result is stored or cached.

## Scope

## Terminology

## Provider Decision Ladders

## Identity Carriers By Surface

## Modern Home Flow

## Continue Watching Flow

## Detail View Flow

## Ratings, Posters, Trailers, And Skip Segments

## Cache And Storage Summary

## Known Ambiguities And Drift

## Appendix Links
```

- [ ] **Step 2: Create the provider endpoint appendix shell**

Create `docs/research/metadata-audit/provider-endpoint-index.md` with this exact section layout:

```md
# Metadata Provider Endpoint Index

## How To Read This Appendix

## TMDB

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|

## TVDB

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|

## Kitsu

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|

## MDBList

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|

## OMDb

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|

## Trakt

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|

## Simkl

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|

## RPDB And Top-Posters

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|

## TheIntroDB, AniSkip, AnimeSkip, And ARM

| Interface or Blueprint | Endpoint or Route | Current caller(s) | Metadata surface | Bulk or single-item | Notes |
|---|---|---|---|---|---|
```

- [ ] **Step 3: Create the field matrix appendix shell**

Create `docs/research/metadata-audit/field-source-matrix.md` with this exact section layout:

```md
# Metadata Field Source Matrix

## Column Definitions

| Column | Meaning |
|---|---|
| Field | Concrete user-visible or internal metadata field. |
| Home surfaces | Where the field is used on modern home, hero, or continue watching. |
| Detail surfaces | Where the field is used on title, season, episode, person, or company detail views. |
| Current source path | Service, repository, or provider path that supplies the field today. |
| Priority and fallback | Current decision order, including explicit fallback behavior. |
| Endpoint(s) | Retrofit methods or blueprint routes used to fetch the field. |
| Storage and cache | Data store, snapshot store, in-memory cache, or disk cache that persists the field. |
| Multiple variants stored? | Whether Nexio persists competing versions of the same field. |
| Ambiguity or drift | Current conflicts, missing rules, or observable inconsistency. |

## Movie

| Field | Home surfaces | Detail surfaces | Current source path | Priority and fallback | Endpoint(s) | Storage and cache | Multiple variants stored? | Ambiguity or drift |
|---|---|---|---|---|---|---|---|---|

## TV

| Field | Home surfaces | Detail surfaces | Current source path | Priority and fallback | Endpoint(s) | Storage and cache | Multiple variants stored? | Ambiguity or drift |
|---|---|---|---|---|---|---|---|---|

## Anime

| Field | Home surfaces | Detail surfaces | Current source path | Priority and fallback | Endpoint(s) | Storage and cache | Multiple variants stored? | Ambiguity or drift |
|---|---|---|---|---|---|---|---|---|

## Images

| Field | Home surfaces | Detail surfaces | Current source path | Priority and fallback | Endpoint(s) | Storage and cache | Multiple variants stored? | Ambiguity or drift |
|---|---|---|---|---|---|---|---|---|

## Cache And Storage Matrix

| Service or store | Key shape | Scope | TTL or freshness rule | What is stored | Invalidators | Notes |
|---|---|---|---|---|---|---|
```

- [ ] **Step 4: Verify the scaffolding exists**

Run: `rg --files docs/architecture docs/research/metadata-audit`

Expected:

```text
docs/architecture/metadata-provider-routing-audit.md
docs/research/metadata-audit/provider-endpoint-index.md
docs/research/metadata-audit/field-source-matrix.md
```

- [ ] **Step 5: Commit the documentation scaffold**

```bash
git add docs/architecture/metadata-provider-routing-audit.md \
        docs/research/metadata-audit/provider-endpoint-index.md \
        docs/research/metadata-audit/field-source-matrix.md
git commit -m "docs: scaffold metadata provider audit"
```

### Task 2: Document Identity Carriers And Modern Home Population

**Files:**
- Modify: `docs/architecture/metadata-provider-routing-audit.md`
- Modify: `docs/research/metadata-audit/field-source-matrix.md`
- Reference: `app/src/main/java/com/nexio/tv/domain/model/CatalogRow.kt`
- Reference: `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt`
- Reference: `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
- Reference: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
- Reference: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Reference: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/KitsuDiscoveryService.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/MDBListDiscoveryService.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt`

- [ ] **Step 1: Capture the identity evidence from code**

Run these commands and keep the output visible while writing:

```bash
rg -n "data class CatalogRow|data class MetaPreview|data class HomeDisplayMetadata|sealed class ModernPayload|continueWatchingItemKey|catalogRowKey|providerFallbackContentId" \
  app/src/main/java/com/nexio/tv/domain/model/CatalogRow.kt \
  app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt \
  app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt
```

Expected: output confirming that catalog cards carry `MetaPreview.id` and `MetaPreview.apiType`, while continue-watching cards carry `contentId`, `videoId`, season/episode, and `providerFallbackContentId()`.

- [ ] **Step 2: Fetch and document the user-named external addon manifests**

Run these commands one at a time and capture the results in notes before writing the final doc:

```bash
curl -fsSL "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/bmZ4OjpOTDoxNzc2ODU0NTY0NTM3OjA6MDpOTA%3D%3D/manifest.json" | jq '{id, name, resources, types, catalogs}'
```

```bash
curl -fsSL "https://anime-kitsu.strem.fun/manifest.json" | jq '{id, name, resources, types, catalogs}'
```

```bash
curl -fsSL "https://1fe84bc728af-stremio-anime-catalogs.baby-beamup.club/%7B%22myanimelist_top-all-time%22%3A%22on%22%2C%22anidb_popular%22%3A%22on%22%2C%22anilist_trending-now%22%3A%22on%22%2C%22kitsu_top-airing%22%3A%22on%22%2C%22livechart_popular%22%3A%22on%22%7D/manifest.json" | jq '{id, name, resources, types, catalogs}'
```

Expected: three manifest summaries showing addon `id`, supported `types`, and catalog descriptors that can be referenced in the audit when explaining how external addon rows differ from built-in rows.

- [ ] **Step 3: Write the `Identity Carriers By Surface` section**

Add this exact comparison table to `docs/architecture/metadata-provider-routing-audit.md`, then fill the final prose around it with code citations:

```md
| Surface | Builder | Primary identity carried forward | Secondary or fallback identity | UI key shape | Persisted store |
|---|---|---|---|---|---|
| External addon catalog row | `CatalogRepositoryImpl.getCatalog` | `MetaPreview.id` from addon `meta.id` | none at row build time | `catalog_${row.key()}_${item.id}_${occurrence}` | `CatalogDiskCacheStore`, `HomeCatalogSnapshotStore` |
| Built-in TMDB row | `TmdbDiscoveryService.fetchCatalogRow` | `imdb:{id}` when TMDB->IMDb resolves, otherwise `tmdb:{id}` | none at row build time | `catalog_${row.key()}_${item.id}_${occurrence}` | in-memory `TmdbDiscoverySnapshot`, `HomeCatalogSnapshotStore` |
| Built-in Kitsu row | `KitsuDiscoveryService.fetchCatalogRow` | `kitsu:{id}` | none at row build time | `catalog_${row.key()}_${item.id}_${occurrence}` | in-memory `KitsuDiscoverySnapshot`, `HomeCatalogSnapshotStore` |
| Built-in Trakt row | synthetic row builders in `HomeViewModelCatalogPipeline.kt` | `MetaPreview.id` from Trakt discovery mapping | upstream Trakt ids can also live inside snapshot models | `catalog_${row.key()}_${item.id}_${occurrence}` | `TraktDiscoverySnapshotStore`, `HomeCatalogSnapshotStore` |
| Built-in Simkl row | synthetic row builders in `HomeViewModelCatalogPipeline.kt` | `MetaPreview.id` from SIMKL discovery mapping | upstream SIMKL ids remain provider-local | `catalog_${row.key()}_${item.id}_${occurrence}` | `SimklDiscoverySnapshotStore`, `HomeCatalogSnapshotStore` |
| Built-in MDBList row | synthetic row builders in `HomeViewModelCatalogPipeline.kt` | `MetaPreview.id` from MDBList list mapping | upstream list ids remain provider-local | `catalog_${row.key()}_${item.id}_${occurrence}` | `MDBListDiscoverySnapshotStore`, `HomeCatalogSnapshotStore` |
| Continue watching in-progress | `ContinueWatchingSnapshotService` + `HomeViewModelContinueWatching` | `progress.contentId` | `progress.videoId`, season, episode | `cw_inprogress_${contentId}_${videoId}_${season}_${episode}` | `ContinueWatchingSnapshotStore` |
| Continue watching next-up | `ContinueWatchingSnapshotService` + `HomeViewModelContinueWatching` | `info.contentId` | `info.videoId`, `traktShowId`, `traktEpisodeId`, season, episode | `cw_nextup_${contentId}_${videoId}_${season}_${episode}` | `ContinueWatchingSnapshotStore` |
```

- [ ] **Step 4: Add a `Named External Addon Inputs` subsection**

Under `## Identity Carriers By Surface`, add a short subsection that records the manifest facts for these exact addon groups:

- Netflix-style movie and TV catalog addon from `baby-beamup.club`
- `anime-kitsu.strem.fun`
- configured anime-catalog bundle from `baby-beamup.club`

For each addon, record:

- manifest `id`
- manifest `name`
- declared `types`
- declared `catalogs`
- whether Nexio treats the returned row as an external addon row or remaps it into a built-in synthetic row

- [ ] **Step 5: Write the `Modern Home Flow` and `Continue Watching Flow` sections**

Document the current home pipeline in this exact order:

1. persisted home snapshot restore from `HomeCatalogSnapshotStore`
2. synthetic discovery snapshot observation for Trakt, Simkl, MDBList, Kitsu, and TMDB
3. external addon catalog fetch through `CatalogRepositoryImpl`
4. home display metadata overlay and localized metadata overlay
5. modern home payload construction in `ModernHomeModels.kt`
6. merged home snapshot persistence back to `HomeCatalogSnapshotStore`
7. continue-watching snapshot persistence and publish path through `ContinueWatchingSnapshotService` and `ContinueWatchingSnapshotStore`

For each stage, include:

- owner file
- inbound identity shape
- outbound identity shape
- whether the stage is profile-scoped or shared

- [ ] **Step 6: Add the first cache rows to the appendix**

Append these exact stores to `docs/research/metadata-audit/field-source-matrix.md` under `## Cache And Storage Matrix`:

```md
| Service or store | Key shape | Scope | TTL or freshness rule | What is stored | Invalidators | Notes |
|---|---|---|---|---|---|---|
| `CatalogDiskCacheStore` | catalog request cache key including addon/type/catalog/skip/provider token | shared per device | version-hash based, no explicit provider TTL in this appendix unless code proves one | full `CatalogRow` payloads | explicit refresh and cache clear paths | external addon row cache |
| `HomeCatalogSnapshotStore` | `snapshot:p<profileId>:<languageTag>` plus poster provider token in payload | profile-derived cache | invalidated by schema, language tag, or poster provider token mismatch | merged home rows, full rows, hero items, ordered group keys | profile clear and snapshot invalidation checks | startup disk-first path |
| `ContinueWatchingSnapshotStore` | profile-prefixed `continue_watching_snapshot` prefs entry | profile-derived cache | invalidated by schema or language mismatch | resume items, next-up items, display metadata map | profile clear and live snapshot replacement | source for modern continue-watching row restore |
```

- [ ] **Step 7: Verify the home/identity sections landed**

Run:

```bash
rg -n "Identity Carriers By Surface|Modern Home Flow|Continue Watching Flow|CatalogDiskCacheStore|HomeCatalogSnapshotStore|ContinueWatchingSnapshotStore" \
  docs/architecture/metadata-provider-routing-audit.md \
  docs/research/metadata-audit/field-source-matrix.md
```

Expected: all new section headings and the three cache-store names appear.

- [ ] **Step 8: Commit the home and identity audit**

```bash
git add docs/architecture/metadata-provider-routing-audit.md \
        docs/research/metadata-audit/field-source-matrix.md
git commit -m "docs: audit home metadata identity flow"
```

### Task 3: Document Detail Lookup, Enrichment, Ratings, Trailers, And Skip Segments

**Files:**
- Modify: `docs/architecture/metadata-provider-routing-audit.md`
- Modify: `docs/research/metadata-audit/field-source-matrix.md`
- Reference: `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
- Reference: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt`
- Reference: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt`
- Reference: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt`
- Reference: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`
- Reference: `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
- Reference: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Reference: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/TitleRatingOverrideRepository.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/EpisodeRatingsSelectionRepository.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepository.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`
- Reference: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbPersonService.kt`
- Reference: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbOrganizationService.kt`

- [ ] **Step 1: Capture the detail and router evidence**

Run:

```bash
rg -n "getMeta\\(|getMetaFromAllAddons\\(|fetchEnrichment\\(|fetchEpisodeEnrichment\\(|resolveTvdbIdentity\\(|tryFetchKitsu|loadMDBListRatings|EpisodeRatingsSelectionRepository|TitleRatingOverrideRepository|TrailerService|SkipIntroRepository|TvdbPersonService|TmdbOrganizationService" \
  app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt \
  app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt \
  app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt \
  app/src/main/java/com/nexio/tv/data/repository/TitleRatingOverrideRepository.kt \
  app/src/main/java/com/nexio/tv/data/repository/EpisodeRatingsSelectionRepository.kt \
  app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt \
  app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt
```

Expected: output showing the concrete call chain from detail load to base meta fetch, TV/anime enrichment routing, ratings overlays, trailer availability, and skip-intro routing.

- [ ] **Step 2: Write the detail decision ladders**

Add these exact subsections to `docs/architecture/metadata-provider-routing-audit.md` and fill each with numbered rules plus file citations:

```md
## Provider Decision Ladders

### Base meta lookup

### TV and anime title enrichment

### Episode enrichment and season metadata

### Title ratings and episode ratings

### Posters

### Trailer, teaser, and recap media

### Intro, recap, credits, and preview skip intervals

### People, actors, companies, and networks
```

The decision ladders must explicitly capture these current rules:

- TV/anime title enrichment:
  - Kitsu is attempted first when `TvMetadataRouter` can parse an anime ID from `contentId` or `fallbackContentId`.
  - Non-TV content bypasses TVDB and goes to TMDB enrichment.
  - TV content uses TVDB when active and healthy, then falls back to TMDB.
- Title ratings:
  - `TitleRatingOverrideRepository` prefers custom IMDb title ratings first, then MDBList, then leaves the base meta rating untouched.
- Episode ratings:
  - `EpisodeRatingsSelectionRepository` prefers custom IMDb episode ratings when active.
  - Otherwise it combines TMDB episode `voteAverage` data with OMDb season ratings through `resolveEpisodeRatings`.
- Posters:
  - `PosterRatingsUrlResolver` rewrites poster URLs to RPDB or Top-Posters based on account settings and content-id shape, but it does not replace all previously stored poster variants everywhere.
- Trailer availability:
  - TV flow checks TVDB title trailers first, then Streailer, then fallback YouTube IDs, then TMDB TV videos.
  - Movie flow checks TMDB videos first, then Streailer, then fallback YouTube IDs.
- Skip segments:
  - Anime routes prefer AniSkip and AnimeSkip.
  - Non-anime routes go through TheIntroDB.

- [ ] **Step 3: Write the `Known Ambiguities And Drift` section**

Capture at least these findings with code references:

- Anime is not guaranteed to stay on Kitsu if the inbound identity is not recognized as anime or the local anime-id map has no match.
- Poster URLs can be rewritten to RPDB or Top-Posters while older native/TMDB/TVDB poster variants still exist in caches or snapshots.
- Detail meta lookup uses multiple type and id aliases in `MetaRepositoryImpl`, which can make one logical title addressable through competing cache keys.
- Continue watching can carry a `contentId` plus a separate `videoId` fallback, which is materially different from catalog-row identity.

- [ ] **Step 4: Add the core rating/trailer/skip rows to the appendix**

Append these exact rows to `docs/research/metadata-audit/field-source-matrix.md` before filling the rest of the matrix:

```md
| Field | Home surfaces | Detail surfaces | Current source path | Priority and fallback | Endpoint(s) | Storage and cache | Multiple variants stored? | Ambiguity or drift |
|---|---|---|---|---|---|---|---|---|
| title IMDb rating | hero text, cards, continue watching | hero badge | base meta, `TitleRatingOverrideRepository`, `MDBListRepository` | custom IMDb -> MDBList -> base provider rating | provider-specific appendix rows | base meta disk cache plus in-memory overlays | yes | title rating can differ between base meta and override layers |
| episode ratings | not used on home today | episode badges | `EpisodeRatingsSelectionRepository` | custom IMDb -> TMDB + OMDb merge | provider-specific appendix rows | repository-local in-memory caches plus TMDB metadata caches | yes | TMDB and OMDb can disagree by episode |
| title poster | cards, hero, continue watching | detail hero | addon meta, TMDB/TVDB/Kitsu enrichment, `PosterRatingsUrlResolver` | active poster provider rewrite over native poster url | provider-specific appendix rows | home snapshot, meta cache, artwork cache | yes | native, RPDB, and Top-Posters variants can coexist |
| trailer / teaser / recap link | hero autoplay availability only | trailer button, season trailer button, season recap button | `TrailerService`, `TvdbTrailerResolver`, TMDB video fetches, fallback YouTube ids | TVDB -> Streailer -> fallback YT -> TMDB for TV; TMDB -> Streailer -> fallback YT for movie | provider-specific appendix rows | `MetadataDiskCacheStore` for TMDB videos plus process caches | yes | availability and playback source can come from different providers |
| intro / credits / preview skip intervals | not used on home | player skip buttons | `SkipIntroRepository` | anime route -> AniSkip / AnimeSkip; default -> TheIntroDB | provider-specific appendix rows | repository in-memory cache only | yes | anime id bridges depend on MAL/AniList/Kitsu/IMDb translation |
```

- [ ] **Step 5: Verify the detail sections landed**

Run:

```bash
rg -n "Base meta lookup|TV and anime title enrichment|Title ratings and episode ratings|Trailer, teaser, and recap media|Intro, recap, credits, and preview skip intervals|Known Ambiguities And Drift" \
  docs/architecture/metadata-provider-routing-audit.md
```

Expected: all six headings appear.

- [ ] **Step 6: Commit the detail audit**

```bash
git add docs/architecture/metadata-provider-routing-audit.md \
        docs/research/metadata-audit/field-source-matrix.md
git commit -m "docs: audit detail metadata decision ladders"
```

### Task 4: Build The Provider Endpoint Appendix From Interfaces And Blueprints

**Files:**
- Modify: `docs/research/metadata-audit/provider-endpoint-index.md`
- Modify: `docs/architecture/metadata-provider-routing-audit.md`
- Reference: `app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/remote/api/KitsuApi.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/remote/api/MDBListApi.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/remote/api/OmdbApi.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/remote/api/TraktApi.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/remote/api/SimklApi.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/remote/api/SkipIntroApi.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/remote/api/PosterRatingsApi.kt`
- Reference: `apiblueprints/tmdb.json`
- Reference: `apiblueprints/tvdb.yml`
- Reference: `apiblueprints/kitsu.apib`
- Reference: `apiblueprints/mdblist.apib`
- Reference: `apiblueprints/trakt.apib`
- Reference: `apiblueprints/simkl.apib`
- Reference: `apiblueprints/rpdb.apib`
- Reference: `apiblueprints/topposters.json`
- Reference: `apiblueprints/tidb.yaml`

- [ ] **Step 1: Inventory the Retrofit routes**

Run:

```bash
rg -n "@(GET|POST|PUT|PATCH|DELETE)|interface " \
  app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt \
  app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt \
  app/src/main/java/com/nexio/tv/data/remote/api/KitsuApi.kt \
  app/src/main/java/com/nexio/tv/data/remote/api/MDBListApi.kt \
  app/src/main/java/com/nexio/tv/data/remote/api/OmdbApi.kt \
  app/src/main/java/com/nexio/tv/data/remote/api/TraktApi.kt \
  app/src/main/java/com/nexio/tv/data/remote/api/SimklApi.kt \
  app/src/main/java/com/nexio/tv/data/remote/api/SkipIntroApi.kt \
  app/src/main/java/com/nexio/tv/data/remote/api/PosterRatingsApi.kt
```

Expected: a route inventory covering the current Retrofit surface for every provider the audit will mention.

- [ ] **Step 2: Inventory the concrete call sites**

Run:

```bash
rg -n "TmdbApi\\.|TvdbApi\\.|KitsuApi\\.|MDBListApi\\.|OmdbApi\\.|TraktApi\\.|SimklApi\\.|IntroDbApi\\.|AniSkipApi\\.|AnimeSkipApi\\.|RpdbApi\\.|TopPostersApi\\." \
  app/src/main/java/com/nexio/tv
```

Expected: concrete caller lists that can be copied into the `Current caller(s)` column.

- [ ] **Step 3: Cross-check against checked-in blueprints**

Run:

```bash
rg -n "\"/|^## |^# " \
  apiblueprints/tmdb.json \
  apiblueprints/tvdb.yml \
  apiblueprints/kitsu.apib \
  apiblueprints/mdblist.apib \
  apiblueprints/trakt.apib \
  apiblueprints/simkl.apib \
  apiblueprints/rpdb.apib \
  apiblueprints/topposters.json \
  apiblueprints/tidb.yaml
```

Expected: raw blueprint route references to compare against the Retrofit surface and identify missing or unused bulk endpoints.

- [ ] **Step 4: Fill the endpoint appendix provider by provider**

Populate `docs/research/metadata-audit/provider-endpoint-index.md` with one row per metadata-relevant endpoint currently used by the app. Include at least:

- TMDB discovery, title enrichment, episode enrichment, videos, company lookup, network lookup, and ID translation routes
- TVDB identity lookup, series enrichment, episode enrichment, person lookup, trailer lookup, updates, and reference-data routes
- Kitsu title, episode, castings, mappings, and any advanced-relationship routes currently used
- MDBList title ratings, episode ratings, and discovery-list routes
- OMDb season ratings routes
- Trakt and Simkl discovery and continue-watching routes that feed home identity or detail review flows
- RPDB and Top-Posters auth/verification and poster URL patterns
- TheIntroDB, AniSkip, AnimeSkip, and ARM routes that bridge anime ids or fetch skip segments

Every row must say whether the current code uses the endpoint as:

- single title fetch
- season batch
- list batch
- paginated list
- one-call multi-field enrichment
- many-calls-for-one-dataset

- [ ] **Step 5: Link the appendix from the canonical doc**

Add this exact appendix block to the bottom of `docs/architecture/metadata-provider-routing-audit.md`:

```md
## Appendix Links

- [Provider endpoint index](../research/metadata-audit/provider-endpoint-index.md)
- [Field source matrix](../research/metadata-audit/field-source-matrix.md)
```

- [ ] **Step 6: Verify every requested provider has a section**

Run:

```bash
rg -n "^## (TMDB|TVDB|Kitsu|MDBList|OMDb|Trakt|Simkl|RPDB And Top-Posters|TheIntroDB, AniSkip, AnimeSkip, And ARM)$" \
  docs/research/metadata-audit/provider-endpoint-index.md
```

Expected: eight section matches, one for each provider group.

- [ ] **Step 7: Commit the endpoint appendix**

```bash
git add docs/research/metadata-audit/provider-endpoint-index.md \
        docs/architecture/metadata-provider-routing-audit.md
git commit -m "docs: index metadata provider endpoints"
```

### Task 5: Fill The Movie, TV, Anime, Image, And Cache Matrices Completely

**Files:**
- Modify: `docs/research/metadata-audit/field-source-matrix.md`
- Modify: `docs/architecture/metadata-provider-routing-audit.md`
- Reference: `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
- Reference: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
- Reference: `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepository.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt`
- Reference: `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`

- [ ] **Step 1: Add the exact row inventory to the Movie table**

Populate the `## Movie` table with these exact field rows in this exact order:

1. title
2. localized title
3. description
4. localized description
5. year / release info
6. genres
7. IMDb rating
8. MDBList ratings: trakt
9. MDBList ratings: tmdb
10. MDBList ratings: letterboxd
11. MDBList ratings: tomatoes
12. MDBList ratings: audience
13. MDBList ratings: metacritic
14. runtime
15. age rating
16. country
17. original language
18. trailer link
19. teaser link
20. recap link
21. director
22. producer / maker
23. cast names
24. cast members with character and photo
25. actor name
26. actor birthdate and age
27. actor description
28. actor filmography titles
29. production company names
30. production company country
31. production company headquarters
32. production company homepage
33. production company description
34. production company titles
35. reviews
36. more like this titles
37. more like this year
38. posters
39. logos
40. backdrops

For every row, fill all columns. If a field is not supported today, write `not collected` in `Current source path` and cite the closest owning code path that proves the gap.

- [ ] **Step 2: Add the exact row inventory to the TV table**

Populate the `## TV` table with these exact field rows in this exact order:

1. title
2. localized title
3. description
4. localized description
5. year / release info
6. genres
7. IMDb rating
8. MDBList ratings: trakt
9. MDBList ratings: tmdb
10. MDBList ratings: letterboxd
11. MDBList ratings: tomatoes
12. MDBList ratings: audience
13. MDBList ratings: metacritic
14. runtime
15. average runtime
16. age rating
17. country
18. original language
19. trailer link
20. teaser link
21. recap link
22. director
23. producer / maker
24. cast names
25. cast members with character and photo
26. actor name
27. actor birthdate and age
28. actor description
29. actor filmography titles
30. production company names
31. production company country
32. production company headquarters
33. production company homepage
34. production company description
35. production company titles
36. network company names
37. network company country
38. network company headquarters
39. network company homepage
40. network company description
41. network company titles
42. episode season and episode numbering
43. episode titles
44. episode descriptions
45. localized episode descriptions
46. episode thumbnails
47. episode ratings
48. season order / alternate order context
49. reviews
50. more like this titles
51. more like this year
52. posters
53. logos
54. backdrops

- [ ] **Step 3: Add the exact row inventory to the Anime table**

Populate the `## Anime` table with these exact field rows in this exact order:

1. title
2. localized title
3. description
4. localized description
5. year / release info
6. genres
7. rating
8. runtime
9. age rating
10. country
11. original language
12. trailer link
13. teaser link
14. recap link
15. cast / characters
16. staff
17. producers / makers
18. actor name
19. actor birthdate and age
20. actor description
21. actor filmography titles
22. production company names
23. production company country
24. production company headquarters
25. production company homepage
26. production company description
27. production company titles
28. related titles
29. episode season and episode numbering
30. episode titles
31. episode descriptions
32. localized episode descriptions
33. episode thumbnails
34. posters
35. logos
36. backdrops
37. anime character photos
38. skip-intro ids and bridges

Where anime falls back to non-Kitsu providers, state that explicitly in `Priority and fallback` and in `Ambiguity or drift`.

- [ ] **Step 4: Fill the `## Images` and `## Cache And Storage Matrix` sections**

The `## Images` section must include these exact fields:

- title posters
- clear title logo
- title backdrops
- episode thumbnails
- actor photos
- anime character photos
- production company logos
- network logos

The `## Cache And Storage Matrix` must include at least these exact stores or caches:

- `MetadataDiskCacheStore` meta entries
- `MetadataDiskCacheStore` TMDB enrichment entries
- `MetadataDiskCacheStore` TVDB enrichment entries
- `MetadataDiskCacheStore` TVDB season-episode entries
- `MetadataDiskCacheStore` TMDB title-video entries
- `MetadataDiskCacheStore` TMDB season-video entries
- `MetadataDiskCacheStore` TVDB reference entries
- `MetadataDiskCacheStore` home feed references
- `HomeCatalogSnapshotStore`
- `ContinueWatchingSnapshotStore`
- `CatalogDiskCacheStore`
- `TvdbIdentityCacheStore`
- `TraktDiscoverySnapshotStore`
- `SimklDiscoverySnapshotStore`
- `MDBListDiscoverySnapshotStore`
- `TmdbDiscoveryService` in-memory snapshot
- `KitsuDiscoveryService` in-memory snapshot
- `MDBListRepository` in-memory rating caches
- `OmdbEpisodeRatingsRepository` in-memory cache
- `TrailerService` lookup and YouTube playback caches
- `SkipIntroRepository` in-memory caches

- [ ] **Step 5: Add one summary table to the canonical doc**

Add this exact summary subsection to `docs/architecture/metadata-provider-routing-audit.md`:

```md
## Cache And Storage Summary

The full store-by-store matrix lives in the appendix. The short version:

- Home rows are persisted per profile in `HomeCatalogSnapshotStore`, but shared text metadata lives in `MetadataDiskCacheStore`.
- Continue watching is persisted per profile in `ContinueWatchingSnapshotStore` and carries its own `displayMetadataByItemKey` overlay map.
- TVDB, TMDB, and trailer video enrichments use `MetadataDiskCacheStore` with provider-token and language-sensitive keys.
- Ratings and skip-segment providers mostly use process-memory caches, not durable stores.
- Poster provider rewrites are reflected in cached payloads through `posterProviderTag`, which means cache validity depends on the active poster provider.
```

- [ ] **Step 6: Verify the field coverage**

Run:

```bash
rg -n "metacritic|letterboxd|runtime|age rating|original language|episode ratings|actor photos|anime character photos|network logos|SkipIntroRepository|TrailerService" \
  docs/research/metadata-audit/field-source-matrix.md
```

Expected: every representative field family appears at least once in the matrix.

- [ ] **Step 7: Commit the completed matrices**

```bash
git add docs/research/metadata-audit/field-source-matrix.md \
        docs/architecture/metadata-provider-routing-audit.md
git commit -m "docs: complete metadata field source matrix"
```

### Task 6: Final Cross-Check, Cleanups, And Handoff

**Files:**
- Modify: `docs/architecture/metadata-provider-routing-audit.md`
- Modify: `docs/research/metadata-audit/provider-endpoint-index.md`
- Modify: `docs/research/metadata-audit/field-source-matrix.md`

- [ ] **Step 1: Do a claim-by-claim source pass**

For every section in the canonical doc, confirm that every non-obvious claim points back to:

- a concrete Kotlin file and symbol
- a concrete store name or cache key shape
- or a concrete blueprint / Retrofit endpoint

If a claim cannot be backed, delete it or rewrite it as an explicit uncertainty.

- [ ] **Step 2: Check that the docs mention every provider requested by the user**

Run:

```bash
rg -n "TMDB|TVDB|Kitsu|MDBList|OMDb|IMDb|RPDB|Top-Posters|Trakt|Simkl|TheIntroDB|AniSkip|AnimeSkip" \
  docs/architecture/metadata-provider-routing-audit.md \
  docs/research/metadata-audit/provider-endpoint-index.md \
  docs/research/metadata-audit/field-source-matrix.md
```

Expected: all provider names appear in the final docs.

- [ ] **Step 3: Run a diff sanity check**

Run: `git diff --check -- docs/architecture/metadata-provider-routing-audit.md docs/research/metadata-audit/provider-endpoint-index.md docs/research/metadata-audit/field-source-matrix.md`

Expected: PASS with no whitespace or conflict-marker errors.

- [ ] **Step 4: Capture branch status without staging unrelated work**

Run: `git status --short`

Expected: only the three documentation files from this plan are staged or modified for commit. Do not stage unrelated pre-existing changes in the repo.

- [ ] **Step 5: Commit the finished audit**

```bash
git add docs/architecture/metadata-provider-routing-audit.md \
        docs/research/metadata-audit/provider-endpoint-index.md \
        docs/research/metadata-audit/field-source-matrix.md
git commit -m "docs: audit metadata provider routing and storage"
```

## Self-Review

Spec coverage check:

- Home row population from external addons, built-in TMDB/Kitsu rows, and built-in Trakt/Simkl/MDBList rows is covered in Task 2.
- Identity differences between catalog rows and continue watching are covered in Task 2.
- End-to-end detail flow, ratings, posters, trailers, skip segments, people, companies, reviews, and related titles are covered in Task 3.
- API endpoints and bulk-vs-single-call analysis are covered in Task 4.
- Full Movie/TV/Anime tables, image fields, duplicate-variant tracking, and cache/storage coverage are covered in Task 5.
- Final provider-presence and evidence-backed verification are covered in Task 6.

Placeholder scan:

- No `TODO`, `TBD`, or “similar to previous task” shortcuts remain.
- Every task names exact files and exact commands.
- Every verification step states the expected result.

Type and terminology consistency:

- `contentId`, `fallbackContentId`, `videoId`, `MetaPreview.id`, `posterProviderTag`, and `provider token` are used consistently across tasks.
- `provider-endpoint-index` and `field-source-matrix` are the only appendix filenames used in this plan.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-22-metadata-provider-flow-audit.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
