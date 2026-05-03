# Main-Parity Runtime Integration Design

## Context

`codex/integration-runtime-phase-a` is now the architecture branch for shared provider execution, stable identity resolution, rail-preview-first home rendering, and reactive hydration overlays. `main` has advanced by 259 commits and contains important product fixes that users depend on: Continue Watching route context, localized TVDB episode timing, deterministic autoplay safety, proxy recovery, TVDB localization, canonical detail fallback, and richer provider metadata.

A direct merge is the wrong tool. This branch is 702 commits ahead from the merge base and owns architecture files that do not exist on `main`. A mechanical merge would either remove the new shared components or reintroduce provider-specific direct paths.

## Decision

Use this branch as the architectural source of truth and `main` as the behavioral source of truth.

Port behavior semantically from `main` into the correct shared component on this branch:

- Provider calls land in IntegrationRuntime-backed providers.
- Identity fixes land in `StableIdBundleResolver` and `IdMappingStore`.
- Routing fixes land in `MetadataRouterFacade`, `ProviderPlanExecutor`, `ProviderPlanRunner`, and `FieldResolver`.
- Continue Watching fixes land in `ContinueWatchingSnapshotService`, CW route builders, TVDB timing enrichment, and stream request context.
- Autoplay fixes land in existing stream presentation, parsing, scoring, preflight, and player recovery components.
- Home refresh fixes land in catalog rail state, first-paint preview streams, hydration overlays, and item-level UI patching.

Provider-specific mappers remain allowed. Provider-specific renderers, hydration schedulers, final field merges, and direct metadata bypasses remain disallowed.

## Evidence From Initial Debugging

- Current branch: `codex/integration-runtime-phase-a`.
- Branch head: `e9951bada`.
- `main` head: `5723e649c`.
- Merge base: `9bb4349bf8b676dd1305c6bac5f4e7b6031a30a5`.
- `HEAD..main`: 259 commits.
- `main..HEAD`: 702 commits.

Initial high-risk main commits:

- `bacd1e39b fix(home): preserve addon context for continue watching`
- `5723e649c fix(detail,autoplay): hydrate canonical metadata and accept diacritic title matches`
- `a2357b29c fix(autoplay): reject candidates whose title doesn't match content`
- `46d9b3bd8 feat(autoplay): bound resolver wait per candidate with shared deadline budget`
- `aac4096d4 fix(autoplay): warm verdicts every pass and accept 1080p x265 for series`
- `f44c71332 fix(player): random-per-session tiebreak so PM and RD share autoplay fairly`
- `1419bb608 fix(tvdb): apply translated episode titles, not just overviews`
- `14917f00b fix(tvdb): fetch english series translation when canonical record is non-english`
- `2306180d1 chore(cache): bump TVDB episode cache schema to invalidate untranslated entries`
- Proxy/auth recovery and placeholder stream commits around `392f5a03e`, `23ffaddcd`, `4deba983e`, `56d086e2e`, `810c6a7d6`, `0acd9806b`, `94dea8c39`, `e21cc33ed`, and `0c5431342`.

## Integration Order

### Phase 1: Main Parity Ledger

Generate a ledger for all main-only commits. The ledger classifies every commit by domain, target shared component, and action: `PORT`, `ALREADY_COVERED`, `OBSOLETE`, or `REDESIGN_FOR_SHARED_ARCHITECTURE`.

This prevents cherry-picking code into old direct paths and gives each phase a measurable backlog.

### Phase 2: Continue Watching Identity And Route Context

Fix the data entering playback before fixing autoplay symptoms. Continue Watching must carry source addon context, stable IDs, content ID, video ID, type, title, season, episode, runtime, original language, resume state, and localized episode release-time gating.

Survivor S05E10 is the first device scenario. The expected path is:

```text
ContinueWatchingItem
→ stable ID bundle / TVDB identity
→ stream route with season=5 episode=10
→ shared stream request context
→ deterministic autoplay candidate diagnostics
```

### Phase 3: Playback And Autoplay Parity

Port main’s deterministic autoplay and playback recovery work into the existing shared stream path. This includes title guard, diacritic folding, original-language filtering, placeholder stream rejection, resolver deadlines, early-finish diagnostics, proxy resolution, auth recovery, transient 5xx recovery, random tie-breaks, and Atmos scoring corrections.

The stream screen must log enough evidence to explain “No eligible links”:

```text
request context
addon/source counts
candidate counts before and after each filter
rejection reasons
selected candidate or final empty reason
cache-link hit/miss
fallback candidates
```

### Phase 4: Canonical Detail Hydration

Port canonical detail fallback behavior through the router and stable ID bundle. Detail screens may open with previews, but provider detail fetches must wait for router-selected primary identity:

```text
movie → tmdb:{id}
series → tvdb:{id}
anime movie/series → kitsu:{id}
```

TMDB TV cannot execute TVDB detail with a raw TMDB ID. Trakt and Simkl IDs are never required for scrobble if TMDB/TVDB/Kitsu/IMDb IDs are present.

### Phase 5: Provider Metadata Completeness

Port provider completeness fixes into IntegrationRuntime-backed adapters:

- TVDB translated episode titles and overviews, English fallback, episode images, people, companies, networks, and season episode localization.
- TMDB movie and TV cast, companies, networks, reviews, recommendations, and IMDb rating enrichment where IMDb ID is required.
- Kitsu anime core, episodes, characters with voice actor names, productions, reviews, related anime, and relationship navigation.
- IMDb rating enrichment through stable ID bundles that include IMDb side IDs.

### Phase 6: Modern Home Mutation And Hydration

Catalog enable/disable and Trakt/Simkl/Kitsu/TMDB settings changes must publish through catalog rail state and update Modern Home without app restart. First paint stays preview-first. Canonical hydration updates cards through `HydratedHomeOverlayStore` and item-level patches.

### Phase 7: Device Validation

Use the rooted profileable device `192.168.50.98` for final verification. Required scenarios:

- Continue Watching Survivor S05E10 playback.
- TMDB movie detail with cast and companies.
- TMDB TV detail routing to TVDB.
- Trakt movie and series detail.
- Kitsu One Piece detail with episodes, characters, voice actors, productions, reviews, related items, and second-open cache hit.
- Addon movie and series detail.
- Addon catalog enable/disable and Trakt/Simkl settings row changes.

## Non-Negotiable Invariants

- No direct provider bypasses.
- No parallel metadata lifecycle.
- No provider-specific home renderer.
- No provider-specific hydration scheduler.
- No final field merge outside FieldResolver.
- No first-paint blocking on identity resolution or metadata hydration.
- No scrobble lookup for Trakt or Simkl internal IDs when TMDB/TVDB/Kitsu/IMDb IDs are available.
- No provider detail execution without the router-selected primary stable ID.

## Verification

Each phase must include:

- Focused unit tests for the ported behavior.
- Architecture tests or source checks proving no direct bypass was introduced.
- Runtime trace assertions for cache/network behavior.
- Device logcat validation for at least one representative scenario before moving to the next phase.

The branch is ready only when main’s selected product behavior is present in this architecture and the trace output proves it uses the shared runtime/router/home/playback components.
