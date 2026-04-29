# Trace 01 — Home Row Preview

## Path summary

When Home loads catalog rows from Stremio addons, each item arrives as a `MetaPreview` with
addon-supplied display fields (title, overview, poster, backdrop, etc.) baked in at mapping time by
`CatalogMapper.toDomain()`. No router work and no network fetch occur for the initial tile render;
display metadata is consumed directly from the `MetaPreview` by the presentation layer via
`toFirstPaintHomeDisplayMetadata()`. If a PREVIEW-depth `MetadataRequest` is ever constructed and
passed to `MetadataRouterFacade.resolveRequest()`, the facade short-circuits before touching the
router, identity resolver, or any provider plan executor.

## Caller chain

| Step | Symbol | File:line | Notes |
|---|---|---|---|
| 1 | `HomeViewModel.loadCatalogPipeline()` | `HomeViewModelCatalogPipeline.kt:1943` | Fired from `loadAllCatalogsPipeline` for each addon/catalog pair; collects `NetworkResult<CatalogRow>` from `CatalogRepository` |
| 2 | `CatalogRepository.getCatalogCachedFirst()` | `HomeViewModelCatalogPipeline.kt:1969` | Fetches items from network/disk cache; maps DTOs via `CatalogMapper.MetaPreviewDto.toDomain()` |
| 3 | `MetaPreviewDto.toDomain()` | `CatalogMapper.kt:9` | Maps DTO → `MetaPreview`; sets `name`, `poster`, `background`, `logo`, `description`, `releaseInfo`, `imdbRating`, `genres`, `trailerYtIds`, `firstPaintStableIds`. `firstPaintSource` defaults to `FirstPaintSource.ADDON_META_PREVIEW`. No `MetadataRequest` built here. |
| 4 | `HomeViewModel.scheduleUpdateCatalogRows()` → `updateCatalogRowsPipeline()` | `HomeViewModelCatalogPipeline.kt:2001`, `2085` | Debounced flush; computes display rows from `catalogsMap` and publishes to `_uiState`. No metadata router call. |
| 5 | `HomeScreen` / `ModernHomeContent` renders row tiles | `HomeScreen.kt:756`, `ModernHomeRows.kt:607` | Composables read `uiState.catalogRows` and pass each `MetaPreview` item to `buildCatalogItem()` |
| 6 | `buildCatalogItem(item, row, …)` → `item.toFirstPaintHomeDisplayMetadata()` | `ModernHomeModels.kt:562`, `HomeFirstPaintMetadataMapper.kt:15` | Converts `MetaPreview` fields to `HomeDisplayMetadata` **and** emits `metadata.first_paint` event via `FirstPaintTracer.recordHomePreview()` with `routerExecuted=false`, `networkExecuted=false` |
| 7 | `FirstPaintTracer.recordHomePreview()` → `TraceMetadataEvents.emitFirstPaint()` | `FirstPaintTracer.kt:26`, `TraceMetadataEvents.kt:27` | Emits `metadata.first_paint` trace event; `source = "ADDON_META_PREVIEW"`, `routerExecuted = false`, `networkExecuted = false` |
| 8 | Tile rendered with `HeroPreview` / `ModernCarouselItem` | `ModernHomeModels.kt:574–614` | Final display fields (title, poster, backdrop, logo, releaseInfo, rating, genres) surfaced to the TV UI from the resolved `HomeDisplayMetadata` |

**Note on `MetadataRouterFacade.resolveRequest(depth=PREVIEW)` placement:** This facade entry point
exists and is correctly implemented (short-circuits at line 55 of `MetadataRouterFacade.kt`), but
**no production Home-row-load call site passes `depth=PREVIEW`**. The facade's PREVIEW branch is
exercised only when a caller explicitly constructs `MetadataRequest(depth = MetadataDepth.PREVIEW)`
— which does not happen during initial Home row render in this codebase. The first-paint render path
bypasses the facade entirely and reads addon fields directly from `MetaPreview`.

## Trace events expected at each step

| Step | Trace event | Payload required | Production emission site? |
|---|---|---|---|
| 6–7 | `metadata.first_paint` | `contentId`, `itemType`, `surface=HOME`, `source=ADDON_META_PREVIEW`, `routerExecuted=false`, `networkExecuted=false`, `fieldsUsed=[title,poster,…]`, `profileHash` | YES — `FirstPaintTracer.kt:32` via `HomeFirstPaintMetadataMapper.kt:17` |
| facade PREVIEW branch (if invoked) | `metadata.resolver_schedule` | `depth=PREVIEW`, `scheduled=["ADDON_DISPLAY"]`, `skipped={all others}` | YES (conditional) — `ResolverOrchestrator.kt:66` — only fires if a caller passes depth=PREVIEW |
| facade PREVIEW branch (if invoked) | `metadata.field_selected` (per preview field) | `contentId` = `request.contentId`, `field`, `selectedProvider`, `sourceRole=ADDON_PREVIEW`, `ownershipRule="rail preview fills field before canonical hydration"` | YES (conditional) — `FieldResolver.kt:239` via `buildDocument()` — only fires if facade PREVIEW branch is entered |
| Steps 1–5 | (none) | — | NO — catalog fetch, DTO mapping, and `_uiState` publish emit no trace events |

## Verification

For each architectural contract this path must satisfy:

- ✅ **No router work on PREVIEW** — `MetadataRouter.route()` guards against `depth=PREVIEW` at line 20 with a `require()` throw. The facade PREVIEW branch (line 55) returns before calling `routeRequest()`. Evidence: `MetadataRouter.kt:20`, `MetadataRouterFacade.kt:55–90`.
- ✅ **No identity resolution on PREVIEW** — `MetadataIdentityResolver` is only reached via `routeRequest()`, which is skipped in the PREVIEW branch. Evidence: `MetadataRouterFacade.kt:55–90`.
- ✅ **No network fetch on PREVIEW** — `ProviderPlanRunner` and `ProviderPlanExecutor` are only called after `routeRequest()`. `ProviderPlanExecutor` also enforces this with `unsupportedDepths = setOf(MetadataDepth.PREVIEW)` at line 170. Evidence: `ProviderPlanExecutor.kt:170`, `MetadataRouterFacade.kt:92–94`.
- ✅ **F-B-01: FieldResolver.resolveWithPreview() used (not emptyMap shortcut)** — The facade PREVIEW branch calls `fieldResolver.resolveWithPreview(preview=previewCandidate, primary=null, secondary=emptyList(), requestContentId=request.contentId)` when `previewCandidate != null`. Evidence: `MetadataRouterFacade.kt:59–65`.
- ✅ **F-B-05: `requestContentId` populated with actual content id** — PREVIEW branch passes `requestContentId = request.contentId` to `resolveWithPreview()`, which threads it to `buildDocument()` as `traceContentId`. Evidence: `MetadataRouterFacade.kt:64`, `FieldResolver.kt:128`.
- ✅ **`firstPaintSource` carries `ADDON_META_PREVIEW`** — `CatalogMapper.toDomain()` does not set `firstPaintSource`, so the `MetaPreview` default applies: `firstPaintSource = FirstPaintSource.ADDON_META_PREVIEW`. Evidence: `MetaPreview.kt:30`, `CatalogMapper.kt:9–32`.
- ✅ **`metadata.first_paint` emitted with `routerExecuted=false`, `networkExecuted=false`** — `FirstPaintTracer.recordHomePreview()` hardcodes both to `false`. Evidence: `FirstPaintTracer.kt:37–38`.
- ✅ **`PreviewMustNotRouteOrNetwork` validation rule wired** — Rule is present in `TraceValidationRules` and applies to `metadata.first_paint` events where `source == "ADDON_META_PREVIEW"`. Evidence: `TraceValidationRules.kt:9–18`.
- ❌ **Facade PREVIEW branch is never exercised by the Home row path** — No production code constructs `MetadataRequest(depth=PREVIEW)` at the Home catalog load call sites (`HomeViewModelCatalogPipeline.kt`, `CatalogRepositoryImpl.kt`, `CatalogMapper.kt`). The PREVIEW depth code in `MetadataRouterFacade` is dead from the Home row tile perspective; the contract is satisfied structurally but not exercised at the canonical entry point. Evidence: grep of all `depth = MetadataDepth.*` assignments shows no `PREVIEW` outside test code and internal router guards. See **Trace-01-01** below.
- ✅ **`resolverOrchestrator.schedule(PREVIEW)` produces empty networkResolvers** — `ResolverOrchestrator.schedule()` hits the `MetadataDepth.PREVIEW -> Unit` branch, adding no resolvers to `networkResolvers`. Evidence: `ResolverOrchestrator.kt:24`.
- ✅ **`MetadataResolutionResult` returned with `route=null`, `plan=null`** — PREVIEW branch constructs `MetadataResolutionResult(route=null, plan=null, …)`. Evidence: `MetadataRouterFacade.kt:82–89`.
- ✅ **`MetadataSourceContext.previewSourceRole` defaults to `ADDON_PREVIEW`** — `MetadataSourceContext` data class declares `previewSourceRole: SourceRole = SourceRole.ADDON_PREVIEW`. Evidence: `MetadataModels.kt:180`. This is also correctly mapped in `MetaPreview.toHomeMetadataSourceContext()` for `FirstPaintSource.ADDON_META_PREVIEW`. Evidence: `HomeProviderLocalizedMetadataOverlay.kt:80–81`.
- ✅ **`toPreviewFields()` maps only non-null addon fields** — `HomeDisplayMetadata.toPreviewFields()` uses `?.let { put(…) }` guards and an `isNotEmpty()` check for genres. Evidence: `MetadataRouterFacade.kt:501–512`.
- ✅ **`toPreviewCandidate()` returns null when `addonMetadata` is null** — Guard at line 489 returns `null`, which causes the PREVIEW branch to return an empty `ResolvedMetadataDocument` with `fieldOwners = emptyMap()`. Evidence: `MetadataRouterFacade.kt:489`.

## Findings (path-specific)

### Trace-01-01: PREVIEW depth is dead code from the Home row first-paint entry point
- **Severity:** P2
- **Evidence:** `HomeViewModelCatalogPipeline.kt:1943–2028`, `CatalogMapper.kt:9–32`, `ModernHomeModels.kt:562–614`, `HomeFirstPaintMetadataMapper.kt:15`
- **Violated contract:** The expected sequence in the path spec calls for the Home VM to build `MetadataRequest(depth=PREVIEW)` and pass it through `MetadataRouterFacade.resolveRequest()`. No production call site does this. The actual flow reads `MetaPreview` fields directly in the Compose render layer via `toFirstPaintHomeDisplayMetadata()`. The facade PREVIEW branch has no live caller in the Home row load path.
- **User-visible impact:** None at runtime — the effective outcome (display addon fields, no router work, no network) is correct. However, the `metadata.field_selected` events that should emit per-field for the PREVIEW candidate (per F-B-01 intent) are never produced for the Home row tile render, leaving that leg of the trace session dark. Any validator that expects `metadata.field_selected` events for addon-preview fields on Home row paint will not see them.
- **Required fix:** Either (a) wire a `MetadataRequest(depth=PREVIEW)` construction and `resolveRequest()` call into the catalog item build path (adding trace events for the preview leg) or (b) update the path spec and trace validators to acknowledge that Home row first-paint traces only through `metadata.first_paint` (no `field_selected` events), and document the resolver-schedule emission as opt-in rather than mandatory.
- **Test that should catch it:** An integration test that starts a trace session, loads a Home catalog row from an addon, and asserts at least one `metadata.field_selected` event with `sourceRole=ADDON_PREVIEW` is emitted per-tile — this test does not currently exist. `FieldResolverContentIdInTraceTest` (F-B-05) covers the field resolver in isolation but does not exercise the full Home path.

### Trace-01-02: `metadata.resolver_schedule` emitted even in PREVIEW path but not consumed by UI
- **Severity:** Nit
- **Evidence:** `ResolverOrchestrator.kt:56–66`, `MetadataRouterFacade.kt:52`
- **Violated contract:** None strictly. The `resolverOrchestrator.schedule(PREVIEW)` call at line 52 emits `metadata.resolver_schedule` with an empty `networkResolvers` list and all resolver types in `skipped`. This is correct behavior, but since the PREVIEW branch of the facade is never entered from Home (see Trace-01-01), this event is also never emitted for the Home row render. The finding is noted as Nit because the implementation is correct — the scheduling is appropriately a no-op — but the gap in observability compounds Trace-01-01.
- **Required fix:** Addressed by the same fix as Trace-01-01.
- **Test that should catch it:** Same as Trace-01-01.

## Cross-references to lane findings

- **B-01** (F-B-01 FieldResolver closure) — this path is the primary beneficiary of F-B-01; the `resolveWithPreview()` call in the PREVIEW branch satisfies the contract at the code level, but Trace-01-01 shows it is not reached by the live Home entry point.
- **B-05** (F-B-05 requestContentId in field_selected) — wired correctly in the facade PREVIEW branch (`MetadataRouterFacade.kt:64`); unreachable from Home row load for the same reason as Trace-01-01.
- **G-** (trace gap findings) — if any lane G finding covers missing `metadata.field_selected` events on the Home preview path, Trace-01-01 is the root cause.
- **I-** (integration runtime) — the `HomeRailHydrationExecutor` / `IntegrationHydrationCoordinator` path (`HomeViewModel.kt:403–423`) is separate from the catalog fetch pipeline and does not affect the PREVIEW trace path; no cross-reference needed.
