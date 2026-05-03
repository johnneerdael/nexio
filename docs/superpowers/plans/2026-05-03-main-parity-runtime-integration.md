# Main-Parity Runtime Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port critical behavior and bug fixes from `main` into `codex/integration-runtime-phase-a` without merging away the shared runtime/router/rail-preview architecture.

**Architecture:** Treat this branch as the architecture source of truth and `main` as a behavior source. Every port lands in the existing shared IntegrationRuntime, StableIdBundleResolver, MetadataRouter, ProviderPlanRunner, FieldResolver, catalog rail, home hydration, stream presentation, or player recovery component; direct provider bypasses and parallel render/hydration tracks are forbidden.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, coroutines/Flow, IntegrationRuntime, MetadataRouter, TVDB/TMDB/Kitsu/Trakt/Simkl runtime providers, Android logcat, JSONL runtime traces, JUnit/Robolectric, OpenSpec.

---

## File Structure

- Create `docs/main-parity/main-commit-ledger.md`
  - Commit-by-commit classification for `HEAD..main`.
- Create `docs/main-parity/main-port-decisions.md`
  - Human-readable decisions for behavior that must be redesigned into shared architecture.
- Modify `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
  - Preserve source addon context and route metadata for CW rows.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
  - Use stable identity, localized timing, and shared metadata overlays for CW display.
- Modify `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`
  - Preserve addon context, route context, and resume context from CW to stream/player routes.
- Modify `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt`
  - Add route args only if missing for addon context and stable identity side data.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
  - Port deterministic autoplay guards, deadline behavior, diagnostics, and playback context propagation.
- Modify `app/src/main/java/com/nexio/tv/core/stream/*`
  - Keep stream parsing/filtering/scoring behavior shared and testable outside the screen.
- Modify `app/src/main/java/com/nexio/tv/core/player/*`
  - Port proxy resolution, placeholder classification, auth recovery, and transient recovery behavior into shared player helpers.
- Modify `app/src/main/java/com/nexio/tv/core/metadata/router/StableIdBundleResolver.kt`
  - Ensure IMDb side IDs and canonical IDs are resolved together for metadata, ratings, detail, CW, and playback.
- Modify `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
  - Keep detail and home hydration on the router-selected provider path.
- Modify `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt`
  - Block provider plans when canonical target ID is unresolved.
- Modify `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanRunner.kt`
  - Run ported detail/provider enrichment through the shared plan runner.
- Modify `app/src/main/java/com/nexio/tv/data/integration/metadata/*`
  - Port TVDB/TMDB/Kitsu/IMDb metadata completeness into provider adapters.
- Modify `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt`
  - Port TVDB translation, episode, image, people, company, and network behavior through runtime operations.
- Modify `app/src/main/java/com/nexio/tv/data/integration/tmdb/*`
  - Port TMDB external ID/detail/cast/company/network behavior through runtime operations.
- Modify `app/src/main/java/com/nexio/tv/data/integration/kitsu/*`
  - Port Kitsu relationship and detail behavior through runtime operations.
- Modify `app/src/main/java/com/nexio/tv/data/repository/CatalogRailRepository.kt`
  - Ensure catalog enable/disable and settings changes publish row state.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`
  - Keep visible/focused hydration on the shared overlay path.
- Modify `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt`
  - Ensure item-level overlay updates remain observable and cache-safe.
- Add or modify focused tests under:
  - `app/src/test/java/com/nexio/tv/data/repository/*ContinueWatching*Test.kt`
  - `app/src/test/java/com/nexio/tv/ui/navigation/*ContinueWatching*Test.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/stream/*Autoplay*Test.kt`
  - `app/src/test/java/com/nexio/tv/core/player/*Recovery*Test.kt`
  - `app/src/test/java/com/nexio/tv/core/metadata/router/*Test.kt`
  - `app/src/test/java/com/nexio/tv/data/integration/metadata/*Test.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/home/*Catalog*Refresh*Test.kt`

---

### Task 1: OpenSpec And Design Scaffold

**Files:**
- Created: `openspec/changes/port-main-behavior-into-runtime-architecture/proposal.md`
- Created: `openspec/changes/port-main-behavior-into-runtime-architecture/tasks.md`
- Created: `openspec/changes/port-main-behavior-into-runtime-architecture/specs/integration-runtime/spec.md`
- Created: `openspec/changes/port-main-behavior-into-runtime-architecture/specs/metadata-router/spec.md`
- Created: `openspec/changes/port-main-behavior-into-runtime-architecture/specs/library-playback/spec.md`
- Created: `openspec/changes/port-main-behavior-into-runtime-architecture/specs/home-startup-refresh/spec.md`
- Created: `docs/superpowers/specs/2026-05-03-main-parity-runtime-integration-design.md`
- Created: `docs/superpowers/plans/2026-05-03-main-parity-runtime-integration.md`

- [ ] **Step 1: Validate OpenSpec**

Run:

```bash
openspec validate port-main-behavior-into-runtime-architecture --strict
```

Expected:

```text
Change 'port-main-behavior-into-runtime-architecture' is valid
```

- [ ] **Step 2: Commit planning artifacts**

Run:

```bash
git add openspec/changes/port-main-behavior-into-runtime-architecture docs/superpowers/specs/2026-05-03-main-parity-runtime-integration-design.md docs/superpowers/plans/2026-05-03-main-parity-runtime-integration.md
git commit -m "docs: plan main parity runtime integration"
```

Expected:

```text
[codex/integration-runtime-phase-a ...] docs: plan main parity runtime integration
```

---

### Task 2: Main Commit Ledger

**Files:**
- Create: `docs/main-parity/main-commit-ledger.md`
- Create: `docs/main-parity/main-port-decisions.md`

- [ ] **Step 1: Generate raw commit list**

Run:

```bash
git log --reverse --no-merges --format='%H%x09%ad%x09%s' --date=short HEAD..main > /tmp/main-parity-commits.tsv
```

Expected:

```text
/tmp/main-parity-commits.tsv contains 259 lines
```

- [ ] **Step 2: Generate touched-file summary**

Run:

```bash
git log --reverse --no-merges --name-status --format='commit %H %s' HEAD..main > /tmp/main-parity-files.txt
```

Expected:

```text
/tmp/main-parity-files.txt contains commit headers and name-status file changes
```

- [ ] **Step 3: Create ledger document**

Create `docs/main-parity/main-commit-ledger.md` with this header and classification table:

```markdown
# Main Commit Parity Ledger

Branch under integration: `codex/integration-runtime-phase-a`
Main source branch: `main`
Merge base: `9bb4349bf8b676dd1305c6bac5f4e7b6031a30a5`
Main-only count at planning time: `259`
Integration-only count at planning time: `702`

## Classification Values

- `PORT`: bring behavior into this branch.
- `ALREADY_COVERED`: this branch already has equivalent behavior in shared architecture.
- `OBSOLETE`: main behavior is superseded by this branch.
- `REDESIGN_FOR_SHARED_ARCHITECTURE`: behavior is valid but main implementation path is not.

## High-Risk Seed Commits

| Commit | Domain | Decision | Target Shared Boundary | Notes |
| --- | --- | --- | --- | --- |
| `bacd1e39b` | Continue Watching | `PORT` | CW snapshot, route builders, player route args | Preserve addon context for resume/manual playback. |
| `5723e649c` | Detail/autoplay | `REDESIGN_FOR_SHARED_ARCHITECTURE` | MetadataRouterFacade, StableIdBundleResolver, stream title guard | Canonical fallback must not become a screen-side direct path. |
| `a2357b29c` | Autoplay | `PORT` | stream presentation/autoplay guard | Reject cross-title candidates before scoring. |
| `46d9b3bd8` | Autoplay | `PORT` | autoplay resolver deadline | Bound wait per candidate with shared budget. |
| `aac4096d4` | Autoplay | `PORT` | autoplay scoring loop | Warm verdicts every pass and accept 1080p x265 for series. |
| `f44c71332` | Autoplay | `PORT` | autoplay scoring selection | Random-per-session tie-break across providers. |
| `1419bb608` | TVDB localization | `PORT` | TVDB runtime provider and adapter | Apply translated episode titles and overviews. |
| `14917f00b` | TVDB localization | `PORT` | TVDB runtime provider and adapter | Fetch English fallback translation. |
| `2306180d1` | TVDB cache | `PORT` | metadata disk cache schema/runtime cache keys | Invalidate untranslated episode cache entries. |
```

Then append every main-only commit from `/tmp/main-parity-commits.tsv` under `## Full Ledger` using the same columns.

- [ ] **Step 4: Create decision document**

Create `docs/main-parity/main-port-decisions.md`:

```markdown
# Main Port Decisions

## Rule

Do not cherry-pick code when the main implementation uses a path that this branch replaced. Port the behavior into the shared architecture boundary.

## Domain Mapping

| Main Area | Target On This Branch |
| --- | --- |
| Provider HTTP calls | IntegrationRuntime-backed provider |
| Provider identity lookup | StableIdBundleResolver and IdMappingStore |
| Detail canonical fallback | MetadataRouterFacade, ProviderPlanExecutor, ProviderPlanRunner, FieldResolver |
| Continue Watching source context | ContinueWatchingSnapshotService and route builders |
| Deterministic autoplay filters | Stream presentation/parsing/scoring path |
| Playback proxy recovery | Shared player/proxy recovery components |
| TVDB localization | TVDB runtime provider and metadata adapter |
| Modern Home refresh | CatalogRailRepository, first-paint preview stream, HydratedHomeOverlayStore |

## First Milestone

1. Continue Watching context parity.
2. Playback/autoplay parity.
3. Device proof for Survivor S05E10.

This order is mandatory because autoplay diagnostics are only meaningful after route context and stable IDs are correct.
```

- [ ] **Step 5: Commit ledger**

Run:

```bash
git add docs/main-parity/main-commit-ledger.md docs/main-parity/main-port-decisions.md
git commit -m "docs: classify main parity commits"
```

Expected:

```text
[codex/integration-runtime-phase-a ...] docs: classify main parity commits
```

---

### Task 3: Continue Watching Context Parity

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/navigation/NexioNavHostContinueWatchingRouteTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricherTest.kt`

- [ ] **Step 1: Write route preservation tests**

Add tests proving a `ContinueWatchingItem.InProgress` with:

```kotlin
contentId = "tt0239195"
videoId = "tt0239195:5:10"
contentType = "series"
name = "Survivor"
season = 5
episode = 10
addonBaseUrl = "https://addon.example/manifest.json"
```

creates a stream route whose decoded args contain the same `contentId`, `videoId`, `contentType`, `season`, `episode`, `contentName`, `addonBaseUrl`, and resume fields.

- [ ] **Step 2: Verify tests fail for missing args**

Run:

```bash
./gradlew testReleaseProfileableUnitTest --tests '*ContinueWatching*Route*'
```

Expected before implementation:

```text
FAILURE: addonBaseUrl or stable context assertion fails
```

- [ ] **Step 3: Port addon context preservation**

Apply the behavior from main commit `bacd1e39b`, but keep branch architecture:

```text
ContinueWatchingSnapshotService records source addon context.
Home CW item models expose source addon context.
Stream route carries addon context.
Player route carries addon context.
Playback records preserve addon context for future CW entries.
```

Do not add provider-specific CW fetchers.

- [ ] **Step 4: Route identity through stable ID bundle**

Before stream request construction for CW, ensure the CW item has a stable ID bundle when enough side IDs are available. Use `StableIdBundleResolver`; do not call TVDB/TMDB/Kitsu directly from navigation.

- [ ] **Step 5: Verify CW timing tests**

Run:

```bash
./gradlew testReleaseProfileableUnitTest --tests '*TvdbContinueWatchingTimingEnricherTest' --tests '*ContinueWatchingSnapshotService*'
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 6: Commit CW parity**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt app/src/test/java/com/nexio/tv
git commit -m "fix(cw): preserve stable playback context"
```

---

### Task 4: Playback And Autoplay Parity

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/stream/StreamPresentationEngine.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/stream/StreamPresentationModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/*`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/stream/DeterministicAutoplayTitleGuardTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/stream/DeterministicAutoplayDiagnosticsTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/*Recovery*Test.kt`

- [ ] **Step 1: Port title guard tests from main**

Bring over the assertions from main commits `a2357b29c` and `5723e649c`:

```kotlin
assertTrue(shouldRejectDeterministicAutoplayForTitle("One Piece", "Dune Prophecy"))
assertFalse(shouldRejectDeterministicAutoplayForTitle("Le Samouraï", "Le Samourai"))
assertFalse(shouldRejectDeterministicAutoplayForTitle("Survivor", "Survivor"))
```

- [ ] **Step 2: Run title guard test and confirm failure if behavior is absent**

Run:

```bash
./gradlew testReleaseProfileableUnitTest --tests '*DeterministicAutoplayTitleGuardTest'
```

Expected before port if missing:

```text
FAILURE: diacritic or mismatch assertion fails
```

- [ ] **Step 3: Port deterministic autoplay behavior**

Port behavior from:

```text
a2357b29c title mismatch guard
5723e649c diacritic folding
46d9b3bd8 bounded resolver wait
aac4096d4 warm verdicts and 1080p x265 series acceptance
f44c71332 random-per-session tiebreak
c4da69416 genuine Atmos passthrough tier scoring
ff477b624 codec fallback ladder candidates by release type
```

Place reusable parsing/filtering/scoring logic in shared stream/autoplay helpers. Keep `StreamScreenViewModel` as coordinator only.

- [ ] **Step 4: Port placeholder and proxy recovery behavior**

Port behavior from:

```text
392f5a03e ProxyResolution sealed class
23ffaddcd CometProxyUrlResolver public API migration
4deba983e 30s short verdict cache
56d086e2e placeholder predicate
810c6a7d6 placeholder predicate wiring and empty-result toast
0acd9806b AuthRecoveryInterceptor
94dea8c39 resolve proxy URLs at createMediaSource
e21cc33ed transient 5xx recovery
0c5431342 cold-start proxy recovery parity
```

Keep all network/proxy calls inside existing playback/proxy components.

- [ ] **Step 5: Add no-eligible diagnostics**

When deterministic autoplay returns no playable target, log:

```text
contentId
videoId
contentType
title
season
episode
sourceCount
candidateCount
languageRejected
titleRejected
placeholderRejected
preflightRejected
deadlineRejected
finalReason
```

- [ ] **Step 6: Run playback/autoplay tests**

Run:

```bash
./gradlew testReleaseProfileableUnitTest --tests '*Autoplay*' --tests '*Stream*' --tests '*Recovery*'
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 7: Commit autoplay parity**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/stream app/src/main/java/com/nexio/tv/core/stream app/src/main/java/com/nexio/tv/core/player app/src/main/java/com/nexio/tv/ui/screens/player app/src/test/java/com/nexio/tv/ui/screens/stream app/src/test/java/com/nexio/tv/ui/screens/player
git commit -m "fix(autoplay): port main candidate safety"
```

---

### Task 5: Canonical Detail Hydration Parity

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/StableIdBundleResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanRunner.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/StableIdBundleResolverTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutorTest.kt`

- [ ] **Step 1: Write canonical detail tests**

Add or update tests for:

```text
tmdb movie detail -> TMDB provider plan with TMDB id
tmdb tv detail -> TVDB provider plan with resolved TVDB id
trakt movie with tmdb/imdb side ids -> TMDB provider plan
trakt series with tvdb side id -> TVDB provider plan without network identity lookup
kitsu anime -> KITSU provider plan
addon series with IMDb id -> TVDB provider plan after identity resolution
```

- [ ] **Step 2: Run tests and confirm current failures**

Run:

```bash
./gradlew testReleaseProfileableUnitTest --tests '*MetaDetails*ProviderRouting*' --tests '*StableIdBundleResolver*' --tests '*ProviderPlanExecutor*'
```

Expected before port:

```text
FAILURE: at least one canonical detail route or target-id assertion fails
```

- [ ] **Step 3: Port main canonical fallback semantically**

Use main commit `5723e649c` as behavior evidence, but do not copy screen-side fallback directly. Implement:

```text
Detail opens with preview or addon meta when available.
MetadataRouter selects primary provider.
StableIdBundleResolver resolves target provider id and IMDb side id.
ProviderPlanExecutor refuses unresolved target ids.
ProviderPlanRunner hydrates canonical detail.
FieldResolver replaces preview/addon fields with primary fields.
```

- [ ] **Step 4: Run detail/router tests**

Run:

```bash
./gradlew testReleaseProfileableUnitTest --tests '*MetaDetails*' --tests '*MetadataRouter*' --tests '*ProviderPlan*' --tests '*StableIdBundle*'
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit detail parity**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail app/src/main/java/com/nexio/tv/core/metadata/router app/src/test/java/com/nexio/tv/ui/screens/detail app/src/test/java/com/nexio/tv/core/metadata/router
git commit -m "fix(detail): route canonical hydration through shared metadata"
```

---

### Task 6: Provider Metadata Completeness

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/TmdbMetadataProviderAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/KitsuMetadataProviderAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/imdb/CustomImdbRatingsIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt`
- Test: provider adapter tests under `app/src/test/java/com/nexio/tv/data/integration/metadata/`

- [ ] **Step 1: Port TVDB localization tests**

Use main commits `1419bb608`, `14917f00b`, and `2306180d1` as behavior sources. Tests must assert translated episode titles, translated overviews, English fallback, and cache schema invalidation for stale untranslated entries.

- [ ] **Step 2: Port TMDB/Kitsu/IMDb completeness tests**

Add tests asserting:

```text
TMDB movie detail has cast and production companies.
TMDB TV detail resolves TVDB before TVDB execution.
TVDB episode list contains thumbnail image URLs where provider payload has them.
Kitsu detail contains episodes, characters, voice actor names, productions, reviews, and related anime.
IMDb ratings enrichment uses IMDb side ID from stable ID bundle.
```

- [ ] **Step 3: Implement provider adapter ports**

Port behavior into IntegrationRuntime-backed providers and adapters only. If main code uses a direct service path that this branch replaced, re-express the behavior as:

```text
runtime provider operation
→ provider adapter candidate
→ ProviderPlanRunner result
→ FieldResolver selected field
```

- [ ] **Step 4: Run provider tests**

Run:

```bash
./gradlew testReleaseProfileableUnitTest --tests '*Tvdb*' --tests '*Tmdb*' --tests '*Kitsu*' --tests '*Imdb*'
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit provider parity**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/integration app/src/main/java/com/nexio/tv/core/metadata/router app/src/test/java/com/nexio/tv/data/integration app/src/test/java/com/nexio/tv/core/metadata/router
git commit -m "fix(metadata): port provider completeness through runtime"
```

---

### Task 7: Modern Home Mutation And Hydration Parity

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/CatalogRailRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/catalog/rails/*CatalogRailSource.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt`

- [ ] **Step 1: Add catalog mutation tests**

Tests must cover:

```text
enabling addon catalog row adds Modern Home row
disabling addon catalog row removes Modern Home row
enabling Trakt settings rail adds Modern Home row
enabling Simkl settings rail adds Modern Home row
hydration update changes one card display hash without row reorder
```

- [ ] **Step 2: Run mutation tests and confirm current stale-row behavior if present**

Run:

```bash
./gradlew testReleaseProfileableUnitTest --tests '*HomeCatalog*Refresh*' --tests '*HomeReactiveHydration*'
```

Expected before fix if gap remains:

```text
FAILURE: row mutation or overlay update assertion fails
```

- [ ] **Step 3: Implement shared refresh path**

Ensure every catalog/settings mutation publishes through:

```text
Catalog setting change
→ CatalogRailRepository row descriptor/membership change
→ FirstPaintPreview stream
→ Home UI row state
→ HomeHydrationCoordinator visible/focused hydration
→ HydratedHomeOverlayStore item patch
```

Do not reload full rows for hydration completion. Do not add provider-specific home renderers.

- [ ] **Step 4: Run home tests**

Run:

```bash
./gradlew testReleaseProfileableUnitTest --tests '*HomeCatalog*' --tests '*HomeHydration*' --tests '*RailPreview*'
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit home parity**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/repository/CatalogRailRepository.kt app/src/main/java/com/nexio/tv/data/catalog/rails app/src/main/java/com/nexio/tv/ui/screens/home app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt app/src/test/java/com/nexio/tv/ui/screens/home
git commit -m "fix(home): refresh catalog rows through shared rail state"
```

---

### Task 8: Device Validation And Report

**Files:**
- Modify: metadata execution report generator files discovered by `rg -n "metadata-execution-report|execution report|TraceSummary" app buildSrc docs`
- Add: `docs/debugging/main-parity-device-validation.md`

- [ ] **Step 1: Build and install profileable APK**

Run:

```bash
./gradlew :app:assembleReleaseProfileable
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/releaseProfileable/app-releaseProfileable.apk
```

Expected:

```text
Success
```

- [ ] **Step 2: Capture CW Survivor S05E10 trace**

Run:

```bash
mkdir -p tmp/logcat
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 logcat -v threadtime | tee tmp/logcat/main-parity-survivor-s05e10.log
```

Expected trace evidence:

```text
contentId=tt0239195
canonicalProvider=TVDB
resultId=76733
season=5
episode=10
candidateCount=...
finalReason=...
```

- [ ] **Step 3: Capture detail/provider scenarios**

Repeat focused traces for:

```text
TMDB movie detail cast/companies
TMDB TV detail route to TVDB
Trakt movie detail
Trakt series detail
Kitsu One Piece detail and second-open cache hit
Addon movie detail
Addon series detail
Catalog enable/disable
Trakt settings row add/remove
Simkl settings row add/remove
```

- [ ] **Step 4: Document validation**

Create `docs/debugging/main-parity-device-validation.md` with:

```markdown
# Main-Parity Device Validation

## Device

- Device: `192.168.50.98:5555`
- Package: `com.nexio.tv.profileable`
- Build: releaseProfileable

## Required Evidence

- Stable ID bundle contents.
- Metadata route decision.
- Provider plan target id.
- Runtime cache decision.
- Runtime HTTP request count.
- Home first-paint and hydration-applied events.
- Stream candidate counts and rejection reasons.

## Scenarios

| Scenario | Expected |
| --- | --- |
| Survivor S05E10 CW | TVDB `76733`, season `5`, episode `10`, stream rejection reasons logged if no link selected. |
| Kitsu One Piece second open | no unexpired Kitsu provider metadata `MISS_THEN_NETWORK`. |
| TMDB TV detail | TVDB target id resolved before TVDB execution. |
| Catalog enable/disable | Modern Home row changes without restart. |
```

- [ ] **Step 5: Run OpenSpec validation**

Run:

```bash
openspec validate port-main-behavior-into-runtime-architecture --strict
```

Expected:

```text
Change 'port-main-behavior-into-runtime-architecture' is valid
```

- [ ] **Step 6: Commit validation docs**

Run:

```bash
git add docs/debugging/main-parity-device-validation.md metadata-execution-report* app buildSrc docs
git commit -m "test(device): document main parity validation"
```

---

## Self-Review

- Spec coverage: The tasks cover inventory, Continue Watching, playback/autoplay, canonical detail, provider metadata, Modern Home mutation/reactive hydration, runtime cache proof, and device validation.
- Placeholder scan: No task uses `TBD`, `TODO`, or unspecified future work as an acceptance condition.
- Type consistency: Stable identity work is consistently assigned to `StableIdBundleResolver`; provider execution is consistently assigned to IntegrationRuntime-backed providers and adapters; final field ownership remains in `FieldResolver`.
- Scope: This is intentionally a single phased lift because the symptoms share route context, stable ID, runtime, and hydration dependencies. Each phase still produces a separately testable commit.
