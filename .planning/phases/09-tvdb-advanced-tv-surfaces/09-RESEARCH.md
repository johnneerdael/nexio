# Phase 09: TVDB Advanced TV Surfaces - Research

**Researched:** 2026-04-14
**Domain:** Android/Kotlin TVDB provider surface mapping, season-order preservation, trailer fallback, and metadata replacement
**Confidence:** HIGH for local code/contracts and TVDB OpenAPI shape; MEDIUM for exact Phase 7 class names because Phase 7 implementation is not present in the current worktree. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] [VERIFIED: tvdb.yml] [VERIFIED: rg Tvdb app/src/main/java]

<user_constraints>
## User Constraints (from CONTEXT.md)

Source for this section: [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

### Locked Decisions

### Season Ordering and Trakt Matching
- **D-01:** Preserve TVDB `defaultSeasonType` and season-type metadata in Nexio's TVDB model. Use the TVDB default-season episode list as the TVDB display/enrichment source where it cleanly fits the existing episode model.
- **D-02:** Keep canonical `season` and `episode` numbers stable for Trakt progress, watch-state, episode ratings, and mutation matching. If TVDB default ordering disagrees with Trakt/TMDB-style aired numbering, Trakt progress matching wins.
- **D-03:** Preserve metadata needed to understand specials and non-standard season types, but only display or act on seasons that map cleanly to the existing season tabs and progress behavior in this phase.
- **D-04:** Planning should require diagnostics/logs when TVDB season-type data is present, when canonical Trakt numbering is used, and when alternate ordering is preserved but not applied.

### TVDB Trailer Replacement
- **D-05:** TVDB should take priority for title-level TV trailers when TVDB is active and provides usable trailer data.
- **D-06:** Season-level TVDB trailers/recaps may replace TMDB season video lookup only when TVDB data cleanly supports the existing season media actions. Do not invent new season actions in this phase.
- **D-07:** TV trailer fallback order when TVDB is active: TVDB usable trailer, then Streailer/internal sources, then existing fallback YouTube IDs, then explicit TMDB fallback only when TVDB has no usable trailer data.
- **D-08:** A usable TVDB trailer is a playable or external video URL that can feed the existing trailer playback model, or a YouTube/Vimeo-style URL that can be resolved through the current trailer pipeline.

### Advanced Metadata Mapping
- **D-09:** Map TVDB characters/cast into existing cast surfaces, preserving character names and photos where available. Do not add a new cast UI.
- **D-10:** Map TVDB companies and networks into existing `MetaCompany` surfaces, preserving whether each entry is a network or production company where possible.
- **D-11:** When TVDB is active, TVDB replaces TMDB TV genres and content ratings. Use existing display fields and the existing country/language preference behavior where practical.
- **D-12:** Do not add new user-visible metadata sections. Populate existing detail, Home, stream, and screensaver metadata surfaces.

### Provider UX and Diagnostics
- **D-13:** Do not add new TVDB-specific toggles. Existing metadata toggles continue to govern categories, while TVDB/TMDB provider routing decides the source.
- **D-14:** Exact-air-time Continue Watching behavior should remain automatic and quiet once TVDB is configured. Add no new UI unless a diagnostic or fallback state needs explanation.
- **D-15:** Planning should require logs or diagnostic state for TVDB surface success, missing TVDB data, explicit TMDB fallback, and TMDB skipped because TVDB supplied the TV surface.
- **D-16:** Missing TVDB advanced data should feel like graceful omission or existing fallback behavior. Avoid browse-time warnings unless the surface becomes visibly inconsistent or empty.

### Claude's Discretion
- Exact Kotlin class names for TVDB season-type records, trailer records, and advanced metadata mappers.
- Exact cache key names and diagnostic log tags, as long as TVDB advanced surfaces are separate from TMDB cache entries and fallback/skipped paths are observable.
- Exact mapping heuristics for TVDB company types and content-rating country preference, provided existing user-facing contracts are preserved.
- Exact test placement and granularity, provided tests cover season-type preservation, Trakt matching stability, TVDB trailer priority/fallback, and advanced metadata replacement.

### Deferred Ideas (OUT OF SCOPE)
- Full user-facing alternate season-order picker remains deferred to v2 requirements ORDER-01 and ORDER-02.
- New cast, company, network, or TVDB-specific metadata UI sections are deferred unless a later phase explicitly designs them.
- Broad TVDB cache invalidation, stable reference-data heavy caching, and user-facing TVDB docs remain Phase 10.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| META-03 | TVDB season ordering is preserved at least through the default TVDB season type, without assuming TMDB-style aired ordering when TVDB exposes a different season-type model. | TVDB exposes `defaultSeasonType`, `seasonTypes`, and `/series/{id}/episodes/{season-type}`; Nexio `Video.season` / `Video.episode` currently drive season tabs and progress keys, so planning must add TVDB order metadata without replacing canonical keys. [VERIFIED: .planning/REQUIREMENTS.md] [VERIFIED: tvdb.yml] [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/Meta.kt] [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt] |
| META-05 | TVDB trailers, characters/cast, companies, networks, genres, and content ratings replace TMDB TV metadata where Nexio already has equivalent TV surfaces. | TVDB `SeriesExtendedRecord` exposes `trailers`, `characters`, `companies`, `originalNetwork`, `latestNetwork`, `genres`, and `contentRatings`; Nexio already has `Meta.trailerYtIds`, `MetaCastMember`, `MetaCompany`, `genres`, `ageRating`, and current TMDB mapping logic. [VERIFIED: .planning/REQUIREMENTS.md] [VERIFIED: tvdb.yml] [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/Meta.kt] [VERIFIED: app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt] |
| UX-02 | Users benefit from exact-air-time Continue Watching automatically once TVDB is configured, without needing additional provider-specific toggles. | Phase 8 owns exact timing, and Phase 9 must not add TVDB-specific toggles; existing metadata toggles remain category controls while provider routing determines TVDB/TMDB source. [VERIFIED: .planning/REQUIREMENTS.md] [VERIFIED: .planning/phases/08-exact-continue-watching-air-timing/08-CONTEXT.md] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] |
</phase_requirements>

## Summary

Phase 9 should extend the Phase 7 TVDB provider path, not introduce a parallel UI or a second metadata model for display. Nexio's existing UI contracts already have fields for the scoped surfaces: `MetaCastMember`, `MetaCompany`, `genres`, `ageRating`, `trailerYtIds`, `Video`, and `HomeDisplayMetadata`. [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/Meta.kt] [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt]

The critical planning risk is episode identity. `MetaDetailsUiState.withRefreshedMeta` derives tabs from `Video.season`, and `buildEpisodesForSeason` filters and sorts by `Video.season` / `Video.episode`; `WatchProgress` also stores `season` / `episode` as progress identity. Therefore, TVDB default-season ordering metadata should be preserved alongside canonical numbers, and only mapped into display episodes when it cleanly preserves canonical Trakt/progress behavior. [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt] [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

Trailer replacement should be a refactor of `TrailerService` inputs and ordering, not a new playback subsystem. The current trailer pipeline already models playable sources, external URLs, YouTube extraction, Streailer fallback, title availability, season availability, and negative caches; Phase 9 should insert TVDB TV trailer candidates before Streailer/fallback IDs and permit explicit TMDB TV fallback only when TVDB has no usable trailer data. [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt] [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

**Primary recommendation:** Plan one provider-extension wave for TVDB advanced DTO/mappers, one trailer-routing wave, one season-order/progress-safety wave, and one validation/diagnostics wave; do not add new UI sections or TVDB-specific toggles. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt]

## Project Constraints (from CLAUDE.md)

- Nexio is an Android TV / Fire TV app built with Kotlin and Jetpack Compose. [VERIFIED: CLAUDE.md]
- Package name is `com.nexio.tv`. [VERIFIED: CLAUDE.md]
- Preserve existing architecture and naming patterns. [VERIFIED: CLAUDE.md]
- Keep domain code free of Android framework dependencies. [VERIFIED: CLAUDE.md]
- Prefer small, targeted changes over broad refactors. [VERIFIED: CLAUDE.md]
- Prefer root-cause fixes over workarounds. [VERIFIED: CLAUDE.md]
- Do not introduce new libraries or patterns unless clearly justified by the existing codebase. [VERIFIED: CLAUDE.md]
- Use `arm64` for local development unless there is a clear reason not to. [VERIFIED: CLAUDE.md]
- Fast debug build command is `./gradlew assembleArm64Debug`. [VERIFIED: CLAUDE.md]
- Unit test command is `./gradlew testArm64DebugUnitTest`. [VERIFIED: CLAUDE.md]
- Lint command is `./gradlew lintArm64Debug`. [VERIFIED: CLAUDE.md]
- No repo-root `AGENTS.md` file exists in this checkout; the prompt-provided AGENTS instructions are scoped to `plugins/compound-engineering/` and should matter only if a plan touches that plugin directory. [VERIFIED: shell read of AGENTS.md] [VERIFIED: prompt AGENTS block]
- No project-local `.claude/skills/` or `.agents/skills/` directories were present. [VERIFIED: shell directory check]

## Standard Stack

### Core

| Library / Tool | Version | Purpose | Why Standard |
|----------------|---------|---------|--------------|
| Kotlin Android | 2.3.0 in version catalog | Main app language and Android plugin integration | Existing app stack; no new language/runtime is needed. [VERIFIED: gradle/libs.versions.toml] |
| Jetpack Compose / Compose for TV | Compose BOM 2026.01.01, `androidx.tv:tv-material` 1.0.1 | Detail/Home UI surfaces already render metadata with Compose | Phase explicitly avoids new UI sections and should feed existing Compose surfaces. [VERIFIED: gradle/libs.versions.toml] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] |
| Hilt | 2.58 | DI for services and repositories | Existing network/provider services use Hilt singleton injection patterns. [VERIFIED: gradle/libs.versions.toml] [VERIFIED: app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt] |
| Retrofit + Moshi | Retrofit 2.9.0, Moshi 1.15.1 | TVDB API DTOs and endpoints | Existing remote APIs are Retrofit interfaces with Moshi conversion; no OpenAPI generator is configured. [VERIFIED: gradle/libs.versions.toml] [VERIFIED: app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt] |
| Kotlin Coroutines / Flow | 1.8.1 | Async metadata, trailers, settings, and tests | Current metadata, trailer, and home/detail pipelines use suspending functions and flows. [VERIFIED: gradle/libs.versions.toml] [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt] |
| Existing TVDB Phase 6/7 foundation | Class names not present in current worktree | Auth, identity, provider routing, and TVDB TV replacement base | Phase 9 depends on Phase 7 and should extend its provider abstraction instead of creating direct TMDB-style call sites. [VERIFIED: .planning/ROADMAP.md] [VERIFIED: .planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md] [VERIFIED: rg Tvdb app/src/main/java] |

### Supporting

| Library / Tool | Version | Purpose | When to Use |
|----------------|---------|---------|-------------|
| JUnit 4 | 4.13.2 | Unit tests | Use for mapper, progress identity, trailer ordering, and provider-skip tests. [VERIFIED: app/build.gradle.kts] |
| MockK | 1.13.12 | Mock services/API calls | Use for call-count assertions that prove TMDB TV calls are skipped when TVDB supplies advanced TV surfaces. [VERIFIED: app/build.gradle.kts] [VERIFIED: app/src/test/java/com/nexio/tv/core/tmdb/TmdbMetadataPerformanceTest.kt] |
| MockWebServer | 4.12.0 | HTTP-backed service tests | Use when verifying TVDB DTO parsing, paging, or Retrofit endpoint behavior. [VERIFIED: app/build.gradle.kts] [VERIFIED: app/src/test/java/com/nexio/tv/data/repository/SimklProgressServiceTest.kt] |
| kotlinx-coroutines-test | 1.8.1 | Deterministic coroutine tests | Use for ViewModel/trailer/home pipeline tests. [VERIFIED: app/build.gradle.kts] [VERIFIED: app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsSeasonMediaViewModelTest.kt] |
| Local `tvdb.yml` | Checked in, untracked in this worktree | API contract reference | Use as the implementation contract for TVDB fields/endpoints; do not read or expose `.thetvdb.apikey`. [VERIFIED: tvdb.yml] [VERIFIED: git status --short] [VERIFIED: .planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Existing Retrofit/Moshi DTOs | OpenAPI code generation from `tvdb.yml` | Not recommended for Phase 9 because no OpenAPI generator is configured and project guidance rejects new libraries/patterns without strong justification. [VERIFIED: rg openapi build.gradle.kts app/build.gradle.kts gradle/libs.versions.toml] [VERIFIED: CLAUDE.md] |
| Existing `TrailerService` | Separate TVDB trailer player/resolver | Not recommended because current code already supports playable sources, external URLs, YouTube extraction, Streailer fallback, and Home/detail state. [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt] [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt] |
| Existing `Meta` / `Video` contracts | New TVDB-specific detail UI model | Not recommended because user constraints forbid new visible metadata sections and existing models already carry the relevant surfaces. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/Meta.kt] |

**Installation:** No new dependency installation should be planned for Phase 9. This is a Kotlin/Android code/config change using existing Gradle dependencies. [VERIFIED: CLAUDE.md] [VERIFIED: gradle/libs.versions.toml]

**Version verification:** Versions above were verified from `gradle/libs.versions.toml` and `app/build.gradle.kts`; `npm view` is not applicable because this Android phase does not add npm packages. [VERIFIED: gradle/libs.versions.toml] [VERIFIED: app/build.gradle.kts]

## Architecture Patterns

### Recommended Project Structure

Use the exact package names created by Phase 6/7 if they differ; the following structure is the recommended placement pattern for planning. [VERIFIED: .planning/phases/06-tvdb-foundation-and-identity/06-CONTEXT.md] [VERIFIED: .planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md]

```text
app/src/main/java/com/nexio/tv/
├── data/remote/api/
│   └── TvdbApi.kt                  # Retrofit endpoint additions for series extended and episodes/{season-type}
├── data/remote/dto/tvdb/
│   └── TvdbSeriesDtos.kt           # DTOs for SeriesExtendedRecord, EpisodeBaseRecord, Character, Company, Trailer
├── core/tvdb/
│   ├── TvdbMetadataService.kt      # Phase 7 provider service extended with advanced surfaces
│   ├── TvdbAdvancedMetadataMapper.kt
│   ├── TvdbSeasonOrderMapper.kt
│   └── TvdbTrailerMapper.kt
├── data/trailer/
│   └── TrailerService.kt           # Insert TVDB TV candidate stage before Streailer/fallback IDs/TMDB fallback
└── domain/model/
    └── Meta.kt                     # Add only provider-neutral season-order metadata if no Phase 7 model exists
```

### Pattern 1: Preserve Canonical Episode Identity, Add TVDB Order Context

**What:** Keep `Video.season` and `Video.episode` as canonical progress/mutation numbers, and add TVDB default season-type metadata in provider/domain records rather than overwriting those fields during ambiguous ordering. [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/Meta.kt] [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

**When to use:** Use this for all TVDB episode mapping and season-tab planning in Phase 9. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

**Example:**

```kotlin
// Source: tvdb.yml SeriesExtendedRecord/defaultSeasonType/seasonTypes and existing Video contract.
// Class names are planning placeholders; use Phase 7 names if already introduced. [ASSUMED]
data class TvdbSeasonOrderContext(
    val defaultSeasonTypeId: Long?,
    val defaultSeasonTypeName: String?,
    val defaultSeasonTypeSlug: String?,
    val availableSeasonTypes: List<TvdbSeasonTypeSummary>,
    val alternateOrderPreservedButNotApplied: Boolean
)

data class TvdbEpisodeMapping(
    val canonicalSeason: Int?,
    val canonicalEpisode: Int?,
    val tvdbDefaultSeason: Int?,
    val tvdbDefaultEpisode: Int?,
    val tvdbAbsoluteNumber: Int?,
    val airsAfterSeason: Int?,
    val airsBeforeSeason: Int?,
    val airsBeforeEpisode: Int?
)
```

### Pattern 2: Treat `default` as the Safe TVDB Episode Endpoint

**What:** Use `/series/{id}/episodes/default` for the default season-order episode list, and separately preserve `SeriesExtendedRecord.defaultSeasonType` plus `seasonTypes` so diagnostics can explain which named order was default. [VERIFIED: tvdb.yml] [CITED: https://github.com/thetvdb/v4-api]

**When to use:** Use this when planning Phase 9 episode enrichment from TVDB default order without adding an order picker. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

**Example:**

```kotlin
// Source: tvdb.yml /series/{id}/episodes/{season-type}. [VERIFIED: tvdb.yml]
interface TvdbApi {
    @GET("series/{id}/episodes/{seasonType}")
    suspend fun getSeriesEpisodes(
        @Path("id") seriesId: Long,
        @Path("seasonType") seasonType: String = "default",
        @Query("page") page: Int = 0,
        @Query("season") season: Int? = null
    ): Response<TvdbSeriesEpisodesResponse>
}
```

### Pattern 3: Map Advanced TVDB Surfaces Into `TmdbEnrichment`-Equivalent Provider Output

**What:** Mirror the useful shape of `TmdbEnrichment` for TVDB provider output, then apply existing settings category toggles in the caller or provider abstraction. [VERIFIED: app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt] [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt]

**When to use:** Use when mapping TVDB characters/cast, companies, networks, genres, and content ratings into current `Meta` fields. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

**Example:**

```kotlin
// Source: existing TmdbEnrichment and Meta fields. [VERIFIED: app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt]
data class TvdbAdvancedEnrichment(
    val castMembers: List<MetaCastMember>,
    val productionCompanies: List<MetaCompany>,
    val networks: List<MetaCompany>,
    val genres: List<String>,
    val ageRating: String?,
    val trailers: List<TvdbTrailerCandidate>,
    val seasonOrderContext: TvdbSeasonOrderContext?
)
```

### Pattern 4: Insert TVDB Trailer Candidates Into Existing Trailer Resolution

**What:** Add a TVDB candidate stage ahead of Streailer/internal, fallback YouTube IDs, and explicit TMDB TV fallback; reuse `TrailerResolutionResult.Playback` and `TrailerResolutionResult.External`. [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt] [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

**When to use:** Use for title-level TV trailers and only for season-level trailer/recap actions that map cleanly to existing season actions. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt]

**Example:**

```kotlin
// Source: current TrailerService.resolveTrailerInternal ordering, adjusted per Phase 9 constraints.
// Function names are planning placeholders. [ASSUMED]
private suspend fun resolveTvTrailerWhenTvdbActive(request: TrailerLookupRequest): TrailerResolutionResult? {
    tvdbTrailerResolver.resolveUsableTitleTrailer(request)?.let { return it }
    streailerResolver.resolveTitleTrailer(request)?.let { return it }
    fallbackYoutubeResolver.resolveFirstUsable(request.fallbackYtIds)?.let { return it }
    return tmdbFallbackResolver.resolveExplicitTvFallback(request)
}
```

### Anti-Patterns to Avoid

- **Overwriting canonical `Video.season` / `Video.episode` whenever TVDB exposes a different default order:** This breaks current season tabs, progress maps, watched overrides, episode ratings, mark-watched mutations, and Trakt-style identifiers. [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt] [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]
- **Passing `defaultSeasonType` integer directly as `/episodes/{season-type}`:** The endpoint path examples are strings such as `default`, `official`, `dvd`, `absolute`, `alternate`, and `regional`; `defaultSeasonType` is an ID that should be preserved and correlated with `seasonTypes`, not used as the endpoint slug. [VERIFIED: tvdb.yml]
- **Adding a TVDB Info section or TVDB-specific toggles:** The phase explicitly forbids new visible metadata sections and provider-specific toggles. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]
- **Silent TMDB TV enrichment after TVDB success:** Phase 7 and Phase 9 require observable provider routing and no duplicate TMDB TV fetches in normal TV success paths. [VERIFIED: .planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| TVDB HTTP/auth client foundation | A second ad hoc TVDB client inside Phase 9 | Phase 6/7 TVDB API/auth/cache foundation | Phase 9 depends on Phase 7 and should consume its provider abstraction. [VERIFIED: .planning/ROADMAP.md] [VERIFIED: .planning/phases/06-tvdb-foundation-and-identity/06-CONTEXT.md] |
| Trailer playback | A TVDB-only video player or extractor | Existing `TrailerService`, `InAppYouTubeExtractor`, `TrailerPlaybackSource`, and `TrailerResolutionResult` | Current code already handles playable video/audio sources, external URLs, YouTube extraction, helper/backend fallback, Streailer, and Home/detail state. [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt] [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt] |
| Season-order UI | Alternate season-order picker | Preserve TVDB default season-type metadata and only display clean mappings | Full order selection is deferred to v2 ORDER-01/ORDER-02. [VERIFIED: .planning/REQUIREMENTS.md] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] |
| Reference taxonomy constants | Hard-coded genre/content-rating/company-type tables | TVDB DTOs and, later, Phase 10 reference-data caching | TVDB recommends heavily caching stable reference endpoints and recommends against hard-coding values unless necessary. [CITED: https://github.com/thetvdb/v4-api] |
| Progress matching bridge | Custom fuzzy watched-state matching against titles or dates | Existing canonical season/episode keys and tracking-provider IDs | Existing progress state is keyed by `contentId`, `season`, and `episode`; user decision says Trakt progress matching wins. [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] |

**Key insight:** The hard part is not displaying more fields; it is preserving provider precedence and progress identity while allowing TVDB's richer season-order model to be stored and diagnosed. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt]

## Common Pitfalls

### Pitfall 1: Treating TVDB Default Order As A Replacement For Trakt Numbering

**What goes wrong:** Watched badges, resume rows, mark-season-watched, episode ratings, and Trakt/Simkl mutations can miss if `Video.season` / `Video.episode` stop matching canonical progress keys. [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt] [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt]

**Why it happens:** TVDB supports multiple season types, and the official README states episodes do not have to exist within every season type. [CITED: https://github.com/thetvdb/v4-api]

**How to avoid:** Add TVDB order metadata alongside canonical numbering; apply default-order display only when the mapping is clean, and log when alternate/default ordering is preserved but not applied. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

**Warning signs:** A test using the same `WatchProgress` map passes before TVDB season mapping and fails after TVDB default order is present. [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt]

### Pitfall 2: Reintroducing Duplicate TMDB TV Fetches

**What goes wrong:** TVDB active paths still call `TmdbMetadataService.fetchEnrichment`, `fetchEpisodeEnrichment`, `fetchSeasonEpisodes`, `TrailerService.fetchTmdbTvVideos`, or `TmdbService.ensureTmdbId` for normal TV success behavior. [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt] [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt]

**Why it happens:** Current code resolves TMDB IDs in detail and Home trailer paths before asking `TrailerService` or `TmdbMetadataService` for TV data. [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt] [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt]

**How to avoid:** Route TV through Phase 7 provider abstractions before resolving TMDB IDs, and expose explicit diagnostic states for TVDB supplied, TVDB missing, TMDB skipped, and explicit TMDB fallback. [VERIFIED: .planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

**Warning signs:** MockK call-count tests show `TmdbApi.getTvDetails`, `getTvVideos`, or `getTvSeasonVideos` executing when TVDB advanced data is available. [VERIFIED: app/src/test/java/com/nexio/tv/core/tmdb/TmdbMetadataPerformanceTest.kt] [VERIFIED: app/src/test/java/com/nexio/tv/data/trailer/TrailerServiceLatestSeasonTest.kt]

### Pitfall 3: Misclassifying TVDB Companies And Networks

**What goes wrong:** Networks can appear as production companies, production companies can appear as networks, or both arrays become duplicates in detail UI. [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/Meta.kt] [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt]

**Why it happens:** TVDB series data exposes `companies`, `originalNetwork`, and `latestNetwork`, while `Company` carries `primaryCompanyType`; current Nexio display only distinguishes `MetaCompanyKind.COMPANY` and `NETWORK`. [VERIFIED: tvdb.yml] [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/Meta.kt]

**How to avoid:** Map `originalNetwork` and `latestNetwork` to `MetaCompanyKind.NETWORK`, map production/studio-like entries from `companies` to `MetaCompanyKind.COMPANY`, dedupe by stable TVDB company ID/name, and preserve kind in diagnostics. [VERIFIED: tvdb.yml] [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/Meta.kt]

**Warning signs:** The same company appears in both "Networks" and "Production Companies" with identical names and no diagnostic reason. [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt]

### Pitfall 4: Assuming TVDB Trailer URLs Are Always Playable In-App

**What goes wrong:** A TVDB trailer URL can set trailer availability true but fail playback because the current in-app extractor is YouTube-focused and `TrailerPlaybackSource` needs direct playable URLs. [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt] [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt]

**Why it happens:** TVDB `Trailer` records expose a generic `url`; they do not expose TMDB-style `site` and `key` fields. [VERIFIED: tvdb.yml]

**How to avoid:** Define "usable" as direct playable media, resolvable YouTube URL, or external URL supported by existing UI state; log unusable TVDB trailer URLs separately from missing TVDB trailer data. [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt] [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

**Warning signs:** `titleHasPlayableTrailerMedia` becomes true for a non-YouTube external URL, but `resolveTrailer` returns null and no fallback diagnostic is emitted. [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt]

### Pitfall 5: Adding UX For Automatic Exact-Air-Time Behavior

**What goes wrong:** A TVDB timing label or toggle appears in Continue Watching even though the phase requires quiet automatic behavior. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

**Why it happens:** Phase 9 includes UX-02 but does not own the Phase 8 exact timing implementation. [VERIFIED: .planning/REQUIREMENTS.md] [VERIFIED: .planning/phases/08-exact-continue-watching-air-timing/08-CONTEXT.md]

**How to avoid:** Verify Phase 9 does not add user-facing toggles or labels; add only logs/diagnostic state if provider/fallback behavior needs explanation. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

**Warning signs:** New settings fields include "Use TVDB advanced metadata", "Use TVDB timing", or similar provider-specific controls. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

## Code Examples

Verified patterns from current code and TVDB contract:

### TVDB Advanced Mapper Skeleton

```kotlin
// Sources:
// - tvdb.yml SeriesExtendedRecord: characters, companies, originalNetwork, latestNetwork, genres, contentRatings, trailers.
// - Meta.kt: MetaCastMember, MetaCompany, MetaCompanyKind.
// Class names are planning placeholders. [ASSUMED]
internal fun TvdbSeriesExtendedRecord.toAdvancedEnrichment(
    preferredCountryCodes: List<String>
): TvdbAdvancedEnrichment {
    val cast = characters
        .orEmpty()
        .sortedBy { it.sort ?: Long.MAX_VALUE }
        .mapNotNull { character ->
            val personName = character.personName?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MetaCastMember(
                name = personName,
                character = character.name?.trim()?.takeIf { it.isNotBlank() },
                photo = character.personImgURL?.trim()?.takeIf { it.isNotBlank() }
            )
        }

    val networks = listOfNotNull(originalNetwork, latestNetwork)
        .mapNotNull { company -> company.toMetaCompany(MetaCompanyKind.NETWORK) }
        .distinctBy { it.name.lowercase() }

    val productionCompanies = companies
        .orEmpty()
        .mapNotNull { company -> company.toMetaCompany(MetaCompanyKind.COMPANY) }
        .filterNot { company -> networks.any { it.name.equals(company.name, ignoreCase = true) } }

    return TvdbAdvancedEnrichment(
        castMembers = cast,
        productionCompanies = productionCompanies,
        networks = networks,
        genres = genres.orEmpty().mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) },
        ageRating = selectTvdbContentRating(contentRatings.orEmpty(), preferredCountryCodes),
        trailers = trailers.orEmpty().mapNotNull(::toTvdbTrailerCandidate),
        seasonOrderContext = buildSeasonOrderContext()
    )
}
```

### Canonical Progress Boundary Test Shape

```kotlin
// Sources:
// - WatchProgress uses season/episode.
// - MetaDetailsUiState derives season tabs from Video.season and sorts by Video.episode.
// Test class names are planning placeholders. [ASSUMED]
@Test
fun `tvdb default order metadata does not change canonical progress keys`() {
    val canonicalVideo = Video(
        id = "tt0903747:1:5",
        title = "Canonical episode",
        released = "2008-02-24",
        thumbnail = null,
        season = 1,
        episode = 5,
        overview = null
    )

    val mapped = mapper.applyTvdbDefaultOrder(
        video = canonicalVideo,
        tvdbDefaultSeason = 2,
        tvdbDefaultEpisode = 3
    )

    assertEquals(1, mapped.season)
    assertEquals(5, mapped.episode)
    assertEquals(2, mapped.tvdbOrder?.defaultSeason)
    assertEquals(3, mapped.tvdbOrder?.defaultEpisode)
}
```

### Trailer Fallback Order Test Shape

```kotlin
// Sources:
// - TrailerService currently resolves TMDB, fallback YouTube IDs, and Streailer.
// - Phase 9 decision D-07 sets TVDB, Streailer, fallback IDs, explicit TMDB fallback.
// Test class names are planning placeholders. [ASSUMED]
@Test
fun `tvdb active title trailer uses tvdb before streailer fallback ids and tmdb`() = runTest {
    coEvery { tvdbTrailerResolver.resolveUsableTitleTrailer(any()) } returns
        TrailerResolutionResult.External("https://www.youtube.com/watch?v=abcdefghijk")

    val result = service.resolveTrailer(
        title = "Example Show",
        type = "series",
        contentId = "tvdb:123",
        fallbackYtIds = listOf("fallback12345")
    )

    assertTrue(result is TrailerResolutionResult.External)
    coVerify(exactly = 0) { tmdbApi.getTvVideos(any(), any(), any()) }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed / Source | Impact |
|--------------|------------------|-----------------------|--------|
| Treat series episodes as one TMDB-style aired order | Preserve TVDB default season type and available season types, and keep canonical progress numbers stable | TVDB v4 exposes season types and default season order; Phase 9 locks Trakt/progress stability. [CITED: https://github.com/thetvdb/v4-api] [VERIFIED: tvdb.yml] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] | Planner must model season-order context separately from `Video.season` / `Video.episode`. |
| Direct client calls with minimal caching | Use app cache now, and defer broader update-aware/reference caching to Phase 10 | TVDB recommends full database copy or caching proxy for scale and says stable reference endpoints can be cached heavily. [CITED: https://github.com/thetvdb/v4-api] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] | Phase 9 should add separate TVDB cache keys but not solve Phase 10 invalidation. |
| TMDB TV videos as first TV trailer source | TVDB title trailer first when TVDB active and usable, then Streailer, fallback IDs, explicit TMDB fallback | User decision in Phase 9 context. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] | Planner must change trailer resolution ordering for TV only and preserve movie TMDB behavior. |
| TMDB TV genres/content ratings/cast/networks | TVDB replaces these TV surfaces when active | Requirement META-05 and Phase 9 decisions. [VERIFIED: .planning/REQUIREMENTS.md] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] | Mapper should replace, not merge, TV taxonomy in normal TV success paths. |

**Deprecated/outdated:**
- Relying on `SeriesBaseRecord.nextAired` for future next-up timing is discouraged by the local TVDB spec note, which says callers should use the `nextAired` endpoint because base-record `nextAired` will be deprecated. [VERIFIED: tvdb.yml]
- Assuming all TVDB air times are viewer-local is wrong; TVDB documents US series in EST/premiere time, non-US by country capital or most populous city, and platform defaults for streamers. [CITED: https://support.thetvdb.com/kb/faq.php?id=29] [VERIFIED: tvdb.yml]

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Example class and function names such as `TvdbAdvancedEnrichment`, `TvdbSeasonOrderContext`, `TvdbTrailerResolver`, and `applyTvdbDefaultOrder` are placeholders. | Architecture Patterns, Code Examples | Low. Phase 9 planning can substitute the exact Phase 6/7 names once those phases are implemented. |

## Open Questions (RESOLVED)

1. **What exact Phase 7 provider abstraction will exist?** [VERIFIED: current worktree lacks TVDB implementation]
   - RESOLVED: Phase 9 will not guess or recreate Phase 7 provider classes. Plan 09-00 gates execution on the Phase 7 source files, and Phase 9 tasks read the actual `TvMetadataModels`, `TvdbMetadataService`, `TvMetadataRouter`, diagnostics, and settings files before editing. If those files are absent, Phase 9 stops before implementation. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-00-PLAN.md] [VERIFIED: .planning/ROADMAP.md]
   - What we know: Phase 7 is supposed to introduce or extend TVDB provider routing. [VERIFIED: .planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md]
   - What's unclear: The current worktree contains no TVDB production classes beyond poster URL parsing. [VERIFIED: rg Tvdb app/src/main/java]
   - Recommendation: Make Phase 9 Plan Wave 0 read Phase 7 implementation and bind tasks to its actual class names before editing. [VERIFIED: .planning/ROADMAP.md]

2. **Should TVDB company `primaryCompanyType` be resolved through `/companies/types` in Phase 9?** [VERIFIED: tvdb.yml]
   - RESOLVED: Do not call `/companies/types` in Phase 9. Map `originalNetwork` and `latestNetwork` directly to `MetaCompanyKind.NETWORK`, map `companies` conservatively to `MetaCompanyKind.COMPANY`, dedupe by ID/name where available, and leave heavy reference-data caching/type lookup to Phase 10. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] [VERIFIED: .planning/ROADMAP.md]
   - What we know: `Company` has `primaryCompanyType`, and `/companies/types` returns type names. [VERIFIED: tvdb.yml]
   - What's unclear: Phase 10 owns stable reference-data heavy caching, so a Phase 9 online type lookup may exceed scope. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]
   - Recommendation: Prefer direct `originalNetwork` / `latestNetwork` for networks and conservative `companies` mapping for production companies in Phase 9; log ambiguous company type IDs for Phase 10. [VERIFIED: tvdb.yml] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

3. **How much direct TVDB trailer URL playback exists beyond YouTube?** [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt]
   - RESOLVED: Treat a TVDB trailer as usable only when it is a direct playable media URL, a resolvable YouTube/Vimeo-style URL supported by the current trailer pipeline, or a supported external URL represented by existing `TrailerResolutionResult.External`. Unsupported or unsafe URLs must emit an unusable-URL diagnostic and continue the configured fallback chain. [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt] [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]
   - What we know: Current extraction path is YouTube-focused, while `TrailerResolutionResult.External` can carry an external URL. [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt] [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerPlaybackSource.kt]
   - What's unclear: Whether TVDB commonly returns Vimeo or other URLs that should be treated as external rather than playable. [VERIFIED: tvdb.yml]
   - Recommendation: Define URL usability in tests and diagnostics: direct playable media or resolvable YouTube is playable; otherwise expose supported external URL or continue fallback chain. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md]

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| Gradle wrapper | Build/test execution | Yes | Gradle 8.13 wrapper | None needed. [VERIFIED: ./gradlew -v] |
| JDK | Gradle/Kotlin build | Yes | OpenJDK 17.0.18 | None needed. [VERIFIED: java -version] |
| Android SDK `adb` | Optional device/emulator verification | Yes | Path: `/Users/jneerdael/Library/Android/sdk/platform-tools/adb` | Unit tests still run without a connected device. [VERIFIED: command -v adb] |
| TVDB API key file | Optional live API smoke checks | Present | Secret not read or exposed | Prefer `tvdb.yml` and mocked tests for planning; live checks only if implementation explicitly needs them. [VERIFIED: shell file existence check] [VERIFIED: .planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md] |
| `tvdb-cli` | Optional manual API probing | No | - | Use local `tvdb.yml`, Retrofit tests, or `curl` if live API probing is approved in implementation. [VERIFIED: command -v tvdb-cli] |
| `curl` | Optional manual API probing | Yes | `/usr/bin/curl` | MockWebServer for automated tests. [VERIFIED: command -v curl] [VERIFIED: app/build.gradle.kts] |

**Missing dependencies with no fallback:** None for planning and unit-testable implementation. [VERIFIED: environment audit]

**Missing dependencies with fallback:**
- `tvdb-cli` is missing; use `tvdb.yml`, Retrofit DTO tests, MockWebServer, or `curl` without exposing secrets. [VERIFIED: command -v tvdb-cli] [VERIFIED: tvdb.yml] [VERIFIED: app/build.gradle.kts]

## Validation Architecture

Validation is enabled because `.planning/config.json` does not set `workflow.nyquist_validation` to `false`. [VERIFIED: .planning/config.json]

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2, MockK 1.13.12, kotlinx-coroutines-test 1.8.1, MockWebServer 4.12.0. [VERIFIED: app/build.gradle.kts] |
| Config file | Gradle Kotlin DSL via `build.gradle.kts`, `app/build.gradle.kts`, and `gradle/libs.versions.toml`. [VERIFIED: build.gradle.kts] [VERIFIED: app/build.gradle.kts] [VERIFIED: gradle/libs.versions.toml] |
| Quick run command | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.*"` for new TVDB mapper/service tests, adjusted to actual package names. [VERIFIED: CLAUDE.md] [ASSUMED] |
| Full suite command | `./gradlew testArm64DebugUnitTest`. [VERIFIED: CLAUDE.md] |

### Phase Requirements -> Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| META-03 | Preserve `defaultSeasonType` / `seasonTypes` and avoid changing canonical season/episode progress keys when TVDB default order metadata is present. | Unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbSeasonOrderMapperTest"` | No - Wave 0 should add mapper test. [VERIFIED: rg Tvdb app/src/test/java] |
| META-03 | Detail season tabs continue deriving from canonical `Video.season`, and watched/progress maps still match canonical pairs. | Unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.detail.MetaDetailsTvdbSeasonOrderTest"` | No - Wave 0 should add state/ViewModel coverage. [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt] |
| META-05 | TVDB cast, companies, networks, genres, and content ratings replace equivalent TMDB TV fields when TVDB active. | Unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbAdvancedMetadataMapperTest"` | No - Wave 0 should add mapper test. [VERIFIED: rg Tvdb app/src/test/java] |
| META-05 | TVDB title trailer priority uses TVDB before Streailer/fallback IDs/TMDB, with explicit TMDB fallback only when TVDB has no usable trailer. | Unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.trailer.TrailerServiceTvdbTest"` | No - existing trailer tests provide pattern. [VERIFIED: app/src/test/java/com/nexio/tv/data/trailer/TrailerServiceLatestSeasonTest.kt] |
| META-05 | Normal TV success path skips TMDB TV calls after TVDB supplies advanced surfaces. | Unit/instrumented call-count | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbProviderRoutingTest"` | No - existing TMDB call-count tests provide pattern. [VERIFIED: app/src/test/java/com/nexio/tv/core/tmdb/TmdbMetadataPerformanceTest.kt] |
| UX-02 | No new TVDB-specific toggle is required for exact-air-time Continue Watching or advanced metadata source selection. | Unit/static UI test | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.settings.TvdbSettingsNoAdvancedToggleTest"` | No - depends on Phase 6/7 settings files. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] |

### Sampling Rate

- **Per task commit:** Run the narrow test class for the touched mapper/service/ViewModel plus any affected existing test such as `TrailerServiceLatestSeasonTest`, `MetaDetailsSeasonMediaViewModelTest`, or `TmdbMetadataPerformanceTest`. [VERIFIED: app/src/test/java/com/nexio/tv/data/trailer/TrailerServiceLatestSeasonTest.kt] [VERIFIED: app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsSeasonMediaViewModelTest.kt] [VERIFIED: app/src/test/java/com/nexio/tv/core/tmdb/TmdbMetadataPerformanceTest.kt]
- **Per wave merge:** Run `./gradlew testArm64DebugUnitTest`. [VERIFIED: CLAUDE.md]
- **Phase gate:** Run `./gradlew testArm64DebugUnitTest` and `./gradlew lintArm64Debug` before `/gsd-verify-work`. [VERIFIED: CLAUDE.md]

### Wave 0 Gaps

- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbSeasonOrderMapperTest.kt` - covers META-03 season-type preservation and canonical numbering stability. [VERIFIED: rg Tvdb app/src/test/java]
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbAdvancedMetadataMapperTest.kt` - covers META-05 cast/company/network/genre/rating mapping. [VERIFIED: rg Tvdb app/src/test/java]
- [ ] `app/src/test/java/com/nexio/tv/data/trailer/TrailerServiceTvdbTest.kt` - covers META-05 TVDB trailer priority and fallback order. [VERIFIED: rg Tvdb app/src/test/java]
- [ ] `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbSeasonOrderTest.kt` - covers META-03 season tabs/progress-key stability. [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt]
- [ ] A provider-routing test in the actual Phase 7 package - covers META-05 skipped TMDB TV calls when TVDB advanced data succeeds. [VERIFIED: .planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md]

## Security Domain

Security enforcement is enabled by default because `.planning/config.json` does not set `security_enforcement` to `false`. [VERIFIED: .planning/config.json]

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|------------------|
| V2 Authentication | Yes | Reuse Phase 6 TVDB bearer token/API key foundation; do not read or log `.thetvdb.apikey` in Phase 9. [VERIFIED: .planning/phases/06-tvdb-foundation-and-identity/06-CONTEXT.md] [VERIFIED: .planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md] |
| V3 Session Management | No direct new session surface | Phase 9 should not introduce user sessions; it consumes existing provider auth state. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] |
| V4 Access Control | Low | Account settings/provider enablement should continue to flow through existing settings and Phase 6/7 routing; no new public API or admin surface is planned. [VERIFIED: .planning/phases/06-tvdb-foundation-and-identity/06-CONTEXT.md] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] |
| V5 Input Validation | Yes | Treat TVDB fields as untrusted nullable remote data; trim/blank-check strings, validate URLs before playback/external launch, and keep unknown ordering/company/rating values diagnostic-only. [VERIFIED: tvdb.yml] [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt] |
| V6 Cryptography | No direct crypto | Do not hand-roll crypto; credential storage remains Phase 6 secret-backed settings/sync. [VERIFIED: .planning/phases/06-tvdb-foundation-and-identity/06-CONTEXT.md] |

### Known Threat Patterns for TVDB Advanced Metadata

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Untrusted trailer URL causes unsafe playback or external intent | Tampering / Elevation of Privilege | Validate URL shape and scheme, resolve YouTube through existing extractor, use `TrailerResolutionResult.External` only for supported external URLs, and log unusable URLs. [VERIFIED: app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt] [VERIFIED: app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt] |
| Secret leakage during live TVDB debugging | Information Disclosure | Do not print API keys/PINs/tokens; use local `tvdb.yml` and mocked tests by default. [VERIFIED: .planning/phases/06-tvdb-foundation-and-identity/06-CONTEXT.md] |
| Provider precedence bypass fetches TMDB TV unexpectedly | Information Disclosure / Privacy | Add call-count tests and diagnostics proving TVDB active success skips TMDB TV endpoints. [VERIFIED: .planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md] [VERIFIED: app/src/test/java/com/nexio/tv/core/tmdb/TmdbMetadataPerformanceTest.kt] |
| Malformed remote metadata breaks UI rendering | Denial of Service | Use nullable DTOs, blank filtering, conservative defaults, and existing graceful omission/fallback behavior. [VERIFIED: app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt] [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] |

## Sources

### Primary (HIGH confidence)

- `.planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md` - locked user decisions, constraints, deferred scope, and code pointers. [VERIFIED: file read]
- `.planning/REQUIREMENTS.md` - META-03, META-05, UX-02 definitions and v2 deferred ordering requirements. [VERIFIED: file read]
- `.planning/ROADMAP.md` - Phase 9 goal, dependencies, and success criteria. [VERIFIED: file read]
- `.planning/PROJECT.md` - milestone provider precedence and active requirements. [VERIFIED: file read]
- `CLAUDE.md` - project coding and verification constraints. [VERIFIED: file read]
- `tvdb.yml` - local TVDB OpenAPI schema for series extended records, season episodes endpoint, episodes, characters, companies, content ratings, genres, season types, and trailers. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/domain/model/Meta.kt` - `Meta`, `Video`, `MetaCastMember`, `MetaCompany`, `trailerYtIds`, and current user-facing metadata contracts. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt` - season tab and episode sorting behavior. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` - current TMDB enrichment, trailer, mark-watched, and progress integration points. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt` - existing enrichment mapper shape and cache/call-count patterns. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt` and `TrailerPlaybackSource.kt` - trailer resolution, availability, fallback, playable/external model. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt` - Home trailer availability and preview paths. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt` and `app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt` - progress identity and Trakt path ID behavior. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/data/repository/SimklProgressService.kt` - existing preference for Simkl TVDB season/episode fields when available. [VERIFIED: file read]
- `gradle/libs.versions.toml` and `app/build.gradle.kts` - dependency versions and test framework. [VERIFIED: file read]

### Primary External (HIGH confidence)

- https://github.com/thetvdb/v4-api - official TVDB v4 README for API access models, caching guidance, season types, and reference-data caching. [CITED: official GitHub]
- https://support.thetvdb.com/kb/faq.php?id=29 - official TVDB support FAQ for air-time policy used by Phase 8/UX-02 context. [CITED: official support]
- https://thetvdb.com/api-information - official TVDB API and data licensing page linking to documentation and API GitHub repository. [CITED: official site]

### Secondary (MEDIUM confidence)

- None used for implementation recommendations. [VERIFIED: research log]

### Tertiary (LOW confidence)

- None used. [VERIFIED: research log]

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - verified from Gradle catalog, app build file, CLAUDE.md, and existing network/DI code. [VERIFIED: gradle/libs.versions.toml] [VERIFIED: app/build.gradle.kts] [VERIFIED: CLAUDE.md] [VERIFIED: app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt]
- Architecture: MEDIUM - existing contracts and TVDB API shape are verified, but exact Phase 7 TVDB class names are not present in the current worktree. [VERIFIED: app/src/main/java/com/nexio/tv/domain/model/Meta.kt] [VERIFIED: tvdb.yml] [VERIFIED: rg Tvdb app/src/main/java]
- Pitfalls: HIGH - grounded in current code paths, locked Phase 9 decisions, and TVDB official season-type guidance. [VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-CONTEXT.md] [CITED: https://github.com/thetvdb/v4-api]
- Validation: HIGH for available test framework and existing patterns; MEDIUM for exact new test class names because Phase 7 implementation is absent. [VERIFIED: app/build.gradle.kts] [VERIFIED: app/src/test/java/com/nexio/tv/data/trailer/TrailerServiceLatestSeasonTest.kt] [VERIFIED: rg Tvdb app/src/test/java]

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 for codebase-grounded planning; re-check TVDB API docs and local Phase 7 implementation before implementation if planning happens after Phase 7 changes land. [VERIFIED: current_date] [VERIFIED: .planning/ROADMAP.md]
