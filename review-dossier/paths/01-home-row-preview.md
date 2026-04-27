# Path 01 — Home row preview

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Lane:** B (metadata router) + I (trace mode)
- **Contract:** PREVIEW is addon-only — no router, no runtime, no network. The trace event `metadata.first_paint` is emitted at the canonical first-paint boundary with `routerExecuted = false` and `networkExecuted = false`.

## Chain

| # | Symbol | File:line | Expected | Observed |
|---|---|---|---|---|
| 1 | `MetaPreviewDto.toDomain()` (catalog DTO → MetaPreview) | `app/src/main/java/com/nexio/tv/data/mapper/CatalogMapper.kt:8` | populate `MetaPreview` from addon catalog response only — no provider/router/network | Pure copy of fields from `MetaPreviewDto` (id, type, name, poster, posterShape, background, logo, description, releaseInfo, runtime, imdbRating, genres, trailerYtIds, language). No I/O, no router, no runtime. ✅ |
| 2 | `buildModernHomePresentation` → per-row `row.items.map { buildCatalogItem(...) }` | `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomePresentation.kt:94` | translate each `MetaPreview` into a `ModernCarouselItem` for the Home tile grid (cache-aware) | Builds tile via `buildCatalogItem(item, row, useLandscapePosters, occurrence, previousCachedItem)`. No router/runtime calls. Cache hit returns prior tile; cache miss invokes `buildCatalogItem`. ✅ |
| 3 | `buildCatalogItem(...)` calls pure `item.toHomeDisplayMetadata()` | `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt:570` | derive `HomeDisplayMetadata` from MetaPreview only; populate `HeroPreview` + tile payload | Calls the **pure** `MetaPreview.toHomeDisplayMetadata()` (no side-effect, no trace emit). Then constructs `HeroPreview` and `ModernCarouselItem` purely from `displayMetadata` + `item` fields. No router/runtime/network. ⚠️ NOT instrumented — no `metadata.first_paint` event is emitted at this canonical first-paint boundary. |
| 4 | Pure `MetaPreview.toHomeDisplayMetadata()` | `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt:21` | side-effect-free conversion of the addon-supplied DTO into the UI value-object | Pure `data class` constructor mapping (title, logo, description, genres, releaseInfo, runtime, imdbRating, ratingSource, tomatoesRating, poster, posterProviderTag, backdrop). Defined exactly as documented in commit `ae3f1309c`. ✅ |
| 5 | `HomeDisplayMetadata` consumed by `HeroPreview` / `ModernCarouselItem` | `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt:575-617` | tile payload built from the addon-derived display metadata | All fields (`title`, `logo`, `description`, `poster`, `backdrop`, `imageUrl`, `genres`, `imdbText`, `tomatoesText`, `yearText`) sourced from `displayMetadata` or fallback `item` fields. `imageUrl` chosen between poster/backdrop based on `useLandscapePosters`. ✅ |
| 6 | `MetaPreview.toFirstPaintHomeDisplayMetadata()` (UI wrapper that DOES emit first-paint) | `app/src/main/java/com/nexio/tv/ui/screens/home/HomeFirstPaintMetadataMapper.kt:15` | per its own docstring: "the canonical Home first-paint boundaries (live presentation pipeline)" | Calls pure `toHomeDisplayMetadata()`, then `FirstPaintTracer.recordHomePreview(contentId=id, itemType=apiType, fieldsUsed=…)` with `fieldsUsed` derived from non-blank display fields. Implementation matches contract — but its only callers are NOT the first-paint boundary (see Finding F-01 below). |
| 7 | `FirstPaintTracer.recordHomePreview(...)` | `app/src/main/java/com/nexio/tv/core/trace/FirstPaintTracer.kt:26` | invoke `TraceMetadataEvents.emitFirstPaint` with `routerExecuted=false, networkExecuted=false, source="ADDON_META_PREVIEW", surface=SourceSurface.HOME, profileHash=<provider>()` | Implementation matches exactly: `source = "ADDON_META_PREVIEW"`, `surface = SourceSurface.HOME`, `routerExecuted = false`, `networkExecuted = false`, `profileHash = profileHashProvider()`. Uses install-from-Hilt-singleton pattern (DI wired in `RuntimeTraceModule.kt:102`). ✅ |
| 8 | `TraceMetadataEvents.emitFirstPaint(...)` | `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt:26` | wrap payload in `metadata.first_paint` envelope; no-op when no active trace session | Builds `TraceEventEnvelope(eventType="metadata.first_paint", payload={contentId,itemType,surface,source,routerExecuted,networkExecuted,fieldsUsed,profileHash})` and emits via `sink`. Returns early when `sessionId()` is null. ✅ |
| 9 | UI tile render (Compose consumes `ModernCarouselItem` / `HeroPreview`) | `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt:596-617` (model) | renders tile from the UI value objects derived from `HomeDisplayMetadata` | `ModernCarouselItem` exposes `title`, `subtitle`, `imageUrl`, `heroPreview`, `payload`, `metaPreview` directly to Compose layer; no further router/runtime/network involvement on the render path. ✅ |

## Where the first-paint emission actually fires today

The two and only call sites of `MetaPreview.toFirstPaintHomeDisplayMetadata()` in the production codebase are:

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt:729`
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt:749`

Both sit inside `HomeViewModel.fetchProviderEnrichmentForPreview(item: MetaPreview)` (defined at `HomeViewModelPresentationPipeline.kt:721`). That function constructs a `MetadataRequest` whose `sourceContext.addonMetadata` snapshot is built via `item.toFirstPaintHomeDisplayMetadata()`, then immediately calls `metadataRouterFacade.fetchTvEnrichment(...)` (`HomeViewModelPresentationPipeline.kt:723, 743`). `fetchTvEnrichment` invokes `resolveRequest(metadataRequest)` (`MetadataRouterFacade.kt:73`), which runs the `MetadataRouter` and the provider plan — i.e., the trace event is emitted as a pre-flight side-effect of *router-invoking* enrichment, not at the actual addon-only tile render.

`fetchProviderEnrichmentForPreview` is itself called from `enrichHeroItemsPipeline` (`HomeViewModelPresentationPipeline.kt:763`, called from `HomeViewModel.enrichHeroItems`, `HomeViewModel.kt:849-851`) — i.e., the hero-row enrichment path, not the catalog tile first-paint path.

## What does NOT happen on this path (verified)

- ❌ NO call to `MetadataRouter.route(...)` from `MetaPreviewDto.toDomain()` → `buildCatalogItem(...)` → `buildModernHomePresentation(...)` chain — confirmed by reading `CatalogMapper.kt`, `ModernHomeModels.kt`, `ModernHomePresentation.kt`. No imports of `com.nexio.tv.core.metadata.router.*` in `ModernHomeModels.kt` or `ModernHomePresentation.kt`.
- ❌ NO call to `MetadataRouterFacade.{fetchTvEnrichment, fetchTvEpisodeEnrichment, fetchTvSeasonEpisodes, ...}` from the tile-render path. The facade is only touched from `HomeViewModelPresentationPipeline.kt` enrichment functions (Path 02 territory).
- ❌ NO call to `IntegrationRuntime.{get,call,open}` from the preview-render path.
- ❌ NO HTTP request issued by the preview-render path. `MetaPreviewDto` arrives through the catalog repository upstream of this trace; from DTO→domain→UI is purely in-memory.

## Verdict

⚠️ **Partial — contract intent is satisfied at the addon-only render step (no router/runtime/network), but the `metadata.first_paint` trace event is NOT emitted at the canonical first-paint boundary.**

- The actual Home tile first-paint runs through `buildCatalogItem` → pure `toHomeDisplayMetadata()` and never invokes `FirstPaintTracer`. From a trace-validator perspective, the `PreviewMustNotRouteOrNetwork` rule has nothing to assert because no `metadata.first_paint` event with `surface=HOME, source=ADDON_META_PREVIEW` is produced when the tile actually first paints.
- The first-paint events that *are* produced fire from `fetchProviderEnrichmentForPreview` — a focus/hero enrichment pre-flight that immediately calls `metadataRouterFacade.fetchTvEnrichment` and runs the router/provider plan. The hard-coded `routerExecuted = false, networkExecuted = false` in `FirstPaintTracer.recordHomePreview` is technically true for the act of constructing the `addonMetadata` snapshot itself, but the placement is misleading: a validator that correlates `metadata.first_paint(routerExecuted=false)` with the absence of a subsequent `metadata.route_decision` for the same `contentId` will see them paired anyway, undermining the rule's intent.

## Findings

### F-01 — `metadata.first_paint` is not emitted at the Home tile first-paint boundary

- **Severity:** medium
- **Lane:** I (trace mode) + B (metadata router)
- **Where:** `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt:570` (canonical first-paint, no emit) versus `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt:729, 749` (current emit sites, inside a router-invoking pre-flight).
- **Observation:** `buildCatalogItem` — the function that builds every Home carousel tile from a `MetaPreview` — uses the pure `MetaPreview.toHomeDisplayMetadata()` extension and never touches `FirstPaintTracer`. The only production callers of `toFirstPaintHomeDisplayMetadata()` (the wrapper that does emit) are inside `HomeViewModel.fetchProviderEnrichmentForPreview`, which is the focus/hero enrichment pipeline and synchronously invokes `metadataRouterFacade.fetchTvEnrichment(...)` — i.e., it always co-occurs with router execution.
- **Impact:**
  - The `PreviewMustNotRouteOrNetwork` validator rule (Lane I) cannot meaningfully assert "first-paint is addon-only" because the emission point is not first-paint.
  - When trace mode is active, every focused row item produces a `metadata.first_paint(routerExecuted=false)` event immediately followed by router/plan/provider events, blurring the semantic of "first paint".
  - Path 02 (focus enrichment) and Path 01 (first paint) cannot be cleanly distinguished in trace recordings.
- **Suggested next step (out of scope here, no code changes in Phase 3):** wire `FirstPaintTracer.recordHomePreview` from `buildCatalogItem` (or an adjacent presentation-builder once-per-content-id throttle), and stop calling `toFirstPaintHomeDisplayMetadata()` from `fetchProviderEnrichmentForPreview` — that site should call the pure `toHomeDisplayMetadata()` since it's a router pre-flight, not a first paint. Tracked separately for Phase 4 / Lane I follow-up.

## Cross-references

- Earlier work: commit `ae3f1309c` (`fix(trace): move first_paint emission out of domain layer to a UI wrapper`) — extracted the side-effect into a UI-layer wrapper but did not migrate the canonical first-paint call site (`buildCatalogItem`) to use it.
- Related lane: `review-dossier/lanes/I-trace-mode.md`, `review-dossier/lanes/B-metadata-router.md`.
- Related contract: trace event taxonomy from `add-runtime-trace-mode` OpenSpec; `PreviewMustNotRouteOrNetwork` validator rule.
- DI wiring confirming `FirstPaintTracer` install: `app/src/main/java/com/nexio/tv/core/di/RuntimeTraceModule.kt:100-114`.
