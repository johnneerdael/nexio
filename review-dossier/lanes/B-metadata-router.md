# Lane B — MetadataRouter / ProviderPlanRunner / FieldResolver

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Phase:** 5
- **Owner task:** Task 26
- **Files inspected:** 11 (`MetadataRouter.kt`, `MetadataRouterFacade.kt`, `ProviderPlanRunner.kt`, `ProviderPlanExecutor.kt`, `FieldResolver.kt`, `MetadataIdentityResolver.kt`, `AnimeIdentityIndex.kt`, `IdMappingStore.kt`, `ResolverOrchestrator.kt`, `MetadataModels.kt`, `MetadataRequestNormalizer.kt`)

## What changed (per diff map)

The `core/metadata/router` package is a phase-5 introduction that splits routing (`MetadataRouter`) from plan construction (`ProviderPlanExecutor`), execution (`ProviderPlanRunner`), and field merging (`FieldResolver`). `MetadataRouterFacade` is the singleton orchestration entry point composing all six collaborators plus `ResolverOrchestrator` and `MetadataIdentityResolver`. The router emits `metadata.route_decision` with explicit `usedInputs`/`ignoredInputs` arrays, the runner emits `metadata.provider_plan` before adapter execution, and the resolver emits per-field `metadata.field_selected` with rejection reasons. PREVIEW depth is short-circuited inside the facade and refused at the router/executor level.

## Contract verdicts

| Contract | Verdict | Evidence |
|---|---|---|
| `MetadataRouter.route` rejects PREVIEW | ✅ | `MetadataRouter.kt:18-20` (`require(request.depth != MetadataDepth.PREVIEW)`); also enforced at `ProviderPlanExecutor.kt:15-17,140` |
| `usedInputs` excludes catalog/genre/animeType/links/trend | ✅ | `MetadataRouter.kt:262-263` — emit hard-codes `usedInputs = listOf("item.id","item.type","AnimeIdentityIndex","IdMappingStore")` and `ignoredInputs` lists `catalog.type, addon.name, genre, animeType, links, trend`; route-decision construction touches only `normalized.parentId`, `normalized.contentType`, `animeIdentityIndex`, `idMappingStore` (`:73, :89, :122, :138`) |
| `ProviderPlanRunner.run` emits `metadata.provider_plan` BEFORE adapter execution | ✅ | `ProviderPlanRunner.kt:15-31` emits via `traceEvents.emitProviderPlan(...)` before the `plan.steps.map` loop at `:33-37` |
| `FieldResolver.resolve` is sole producer of `ResolvedMetadataDocument` | ❌ | `MetadataRouterFacade.kt:157-169` (`HomeDisplayMetadata.toResolvedDocument()`) constructs a `ResolvedMetadataDocument` directly for the PREVIEW path. PREVIEW skips routing entirely (`:37-47`), so this is gated to PREVIEW (confirms B-Q3.1 narrowed scope from Path 02) but the contract as stated is still violated — see F-B-01 |
| Secondary doesn't overwrite primary on TITLE/OVERVIEW/EPISODE_LIST | ✅ | `FieldResolver.kt:30-60` — primary fields populate first (`:30-35`); secondary candidates only fill via the `existingOwner == null` branch (`:40`), otherwise emit `IgnoredFieldOverwrite` (`:46-51`). Validator rule `TraceValidationRules.SecondaryDoesNotOverwritePrimary` (`TraceValidationRules.kt:171-235`) covers TITLE / OVERVIEW; `EPISODES` follows the same generic merge (no special override path exists in `FieldResolver`) |
| `MetadataIdentityResolver` runs before any plan with `requiresIdentityResolution` | ✅ | `MetadataRouterFacade.routeRequest` (`:24-31`) calls `identityResolver.resolve(routed)` between `router.route` and any `providerPlanExecutor.buildPlan` call. `ProviderPlanExecutor.kt:12-14` reasserts via `check(!route.targetIdRequiresIdentityResolution)`. `fetchTvEpisodeEnrichment` (`:86-95`) and `fetchTvSeasonEpisodes` via `routeRequest` (`:122-128`) both go through `identityResolver` first |

## Reconciliation: F-04-02 vs RF#13

Re-grepped at SHA `39b0df54`: `ResolverOrchestrator` is referenced from four production sites:

- `MetadataRouterFacade.kt:19,34` — `resolveRequest()` invokes `resolverOrchestrator.schedule(request.depth)` for every routed request (the `resolverSchedule` is then attached to `MetadataResolutionResult`).
- `MetaDetailsViewModel.kt:23,106` — manual fallback facade construction.
- `HomeProviderLocalizedMetadataOverlay.kt:16,70` — manual fallback facade construction.

`ResolverOrchestrator.schedule()` IS called in production via the facade (`MetadataRouterFacade.kt:34`); RF#13 was correct that `emitResolverSchedule` has a real caller, and F-04-02 is **partially refuted** — the orchestrator is wired and emits `metadata.resolver_schedule`. However, the schedule it produces (`localResolvers`/`networkResolvers`) is consumed only as a payload field on `MetadataResolutionResult` and is **not used** to actually dispatch resolvers — see F-B-04 below (event-without-orchestration).

## Findings

### F-B-01: PREVIEW path constructs `ResolvedMetadataDocument` outside `FieldResolver`

- **Severity:** P2
- **Evidence:** `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:157-169` — `HomeDisplayMetadata.toResolvedDocument()` builds a `ResolvedMetadataDocument` with `fieldOwners = emptyMap()` and `ignoredOverwrites = emptyList()`, bypassing `FieldResolver` entirely. Reached from the PREVIEW branch (`:37-47`).
- **Violated contract:** "FieldResolver.resolve is the SOLE final field owner" — confirms B-Q3.1 (narrowed to PREVIEW only, but still a violation of the stated contract).
- **User-visible impact:** PREVIEW-depth surfaces (home rows) emit a `ResolvedMetadataDocument` that has no provenance metadata: validators relying on `fieldOwners` to assert TITLE / OVERVIEW ownership will see an empty map and either skip the row (false negative) or false-positive that no fields were resolved. Trace mode cannot distinguish addon-display PREVIEW from a malformed primary-resolved doc.
- **Required fix:** Either route PREVIEW through `FieldResolver.resolve(primary = MetadataCandidate(provider = ADDON, fields = …), secondary = emptyList())` so ownership is correctly recorded, or formalise the carve-out by introducing a separate `AddonDisplayDocument` type instead of overloading `ResolvedMetadataDocument`. The current shape pretends PREVIEW went through resolution.
- **Test or report that should catch it:** `FieldResolverPreviewProvenanceTest` asserting that any `ResolvedMetadataDocument` reachable from `MetadataResolutionResult` has a non-empty `fieldOwners` for non-null fields, plus a contract test that PREVIEW emits a `metadata.field_selected` event for each populated field.

### F-B-02: UI-side `FieldResolver()` direct instantiation in fallback facades

- **Severity:** P2
- **Evidence:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:98-113` (`defaultMetadataRouterFacadeForManualConstruction`) and `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt:62-77` both call `FieldResolver()` (no-arg constructor) and `ProviderPlanRunner(emptySet())`. The no-arg constructor at `FieldResolver.kt:12-17` wires `NoopRuntimeTraceSink`.
- **Violated contract:** Confirms B-Q3.2 + F-03-01 — the singleton-injected `FieldResolver` (with the real `TraceMetadataEvents` sink) is bypassed by the manual fallback path, silencing `metadata.field_selected` events. Also F-02-02 confirmed: `ProviderPlanRunner(emptySet())` ships an empty adapter set so any non-PREVIEW request through the fallback facade throws `MetadataRouteFailure.MissingPlanStepAdapter`.
- **User-visible impact:** Whenever Hilt injection fails (or in legacy code paths that invoke the fallback constructor), the ViewModel obtains a facade that (a) cannot run any provider plan step (empty adapter set), (b) emits no field-selection trace events, and (c) cannot resolve cross-provider identity (`Lookup` returns null). The `resolveHomeRequest` extension (`HomeProviderLocalizedMetadataOverlay.kt:99-103`) silently swallows the resulting exception, so trace mode appears clean while no enrichment actually happened.
- **Required fix:** Delete both manual fallback constructors and require the facade to be `@Inject`ed (or fail fast). If a manual fallback truly is needed for unit-test scaffolding, move it into `*test*` source set so production code cannot reach it. The fallback's silent `catch (_: Exception)` should at minimum log + emit a trace event so the gap is observable.
- **Test or report that should catch it:** `FieldResolverInjectionContractTest` asserting that production `MetadataRouterFacade` instances always carry the singleton `FieldResolver` (e.g., via reflection or a Hilt module test), plus a Detekt rule banning `FieldResolver()` and `ProviderPlanRunner(emptySet())` outside `*test*`.

### F-B-03: DETAIL_CORE bypasses facade — calls `metadataSecondaryRepository.fetchTmdbEnrichment` directly (re-stating F-03-02 / F-RF-01)

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1391` and `:1406` invoke `metadataSecondaryRepository.fetchTmdbEnrichment(...)` directly, bypassing `MetadataRouterFacade.fetchTvEnrichment`. Confirmed at SHA `39b0df54` — both call sites still present. This is the legacy router-after-facade pattern flagged by Red flag 2.
- **Violated contract:** Facade is the supported single entry point for TMDB / TVDB / KITSU enrichment; bypassing it skips `MetadataRouter.route`, `MetadataIdentityResolver.resolve`, and `ProviderPlanRunner.run` (so `metadata.route_decision`, `metadata.identity_resolution`, `metadata.provider_plan`, and `metadata.field_selected` are all suppressed for this enrichment).
- **User-visible impact:** Movie DETAIL_CORE TMDB enrichment is invisible to trace mode and validators. Routing decisions cannot be audited, identity resolution for tmdb→tvdb conflicts is skipped, and field provenance for description/genres/backdrop/logo (`MetaDetailsViewModel.kt:1414-1419`) is unrecorded. Worse, the manual merge at `:1414-1419` performs `tvEnrichment ?: tmdbEnrichment` field-level fallback that does not respect `FieldResolver`'s primary-wins ownership rule.
- **Required fix:** Replace both call sites with `metadataRouterFacade.fetchTvEnrichment(...)` (or a TMDB-equivalent facade method, adding one if needed) and let `FieldResolver` perform the merge. Delete the manual `?: tmdbEnrichment` chain and rely on the resolved document's fields.
- **Test or report that should catch it:** Path 03 (`detail-core`) trace assertion that for movie loads with a TMDB enrichment, exactly one `metadata.route_decision` and one `metadata.field_selected` per non-null field are emitted; static-analysis rule banning `metadataSecondaryRepository.fetchTmdbEnrichment` calls outside the metadata router package.

### F-B-04: `ResolverOrchestrator.schedule` output is event-only, never dispatched

- **Severity:** P2 (reconciles F-04-02 + RF#13)
- **Evidence:** `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:34` calls `resolverOrchestrator.schedule(request.depth)` and assigns the result to `resolverSchedule`. `ResolverOrchestrator.kt:19-66` builds `localResolvers`/`networkResolvers` lists and emits `metadata.resolver_schedule`. Tracing the consumption: the schedule is stored on `MetadataResolutionResult.resolverSchedule` (`MetadataExecutionModels.kt:27`) but nothing in `MetadataRouterFacade` (or any caller verified via grep) actually iterates `localResolvers`/`networkResolvers` to dispatch them. The set-of-resolvers (`ADDON_DISPLAY`, `RATING`, `ARTWORK`, `TRAILERS`, `REVIEWS`, `RECOMMENDATIONS`, `ORGANIZATION_PERSON`, `TRACKING`, `SKIP_SEGMENTS`) has no code path that consumes the schedule.
- **Violated contract:** RF#13 was correct (`emitResolverSchedule` has a caller). F-04-02's stronger claim that `ResolverOrchestrator` is dead is partially refuted (it IS invoked) but confirmed in spirit (the schedule it produces is dispatched nowhere — it is a trace event without orchestration).
- **User-visible impact:** Trace mode reports a `metadata.resolver_schedule` envelope that suggests resolvers will run, but no resolver pipeline is actually exercised. Validators that assert "every scheduled resolver fires a corresponding event" cannot pass; auditors are misled into thinking a resolver subsystem exists. Provider-plan steps execute (good) but resolver-typed enrichments (rating, artwork, trailers, reviews, recommendations, tracking, skip segments) advertised by the schedule never run from the facade.
- **Required fix:** Either (a) wire `ResolverOrchestrator` output into a dispatcher that runs each `ResolverType` against a registered handler, or (b) demote `ResolverOrchestrator` to a planner-only artifact and remove the trace event so it does not advertise unrun work. Update `08-test-matrix.md` to reflect the chosen direction.
- **Test or report that should catch it:** Trace validator rule `ScheduledResolversAreDispatched` that asserts each `metadata.resolver_schedule.scheduled[*]` has a matching downstream event; or, if Option (b) is chosen, removal of the rule plus a unit test asserting `metadata.resolver_schedule` is no longer emitted.

### F-B-05: `FieldResolver.emitFieldSelected` uses `primary.provider.name` as `contentId`

- **Severity:** Nit (data-quality)
- **Evidence:** `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt:74-82` — `traceEvents.emitFieldSelected(contentId = primary.provider.name, …)`. The `contentId` argument is passed the provider name (`"TMDB"`, `"TVDB"`, `"KITSU"`), not the actual content id (which is not threaded into `FieldResolver.resolve`).
- **Violated contract:** Trace event payload semantics; `contentId` field on `metadata.field_selected` should correlate to the originating `MetadataRequest.contentId` so validators can join field-selection events back to a single request.
- **User-visible impact:** Trace consumers cannot correlate field selections to the request they belong to. Multiple concurrent requests will all emit `contentId = "TMDB"`, making per-request grouping impossible; validators (e.g., F-B-04's `ScheduledResolversAreDispatched`) lose the join key.
- **Required fix:** Thread `route.parentId` (or `MetadataRequest.contentId`) through the `FieldResolver.resolve(...)` signature, or capture it on `MetadataCandidate`. Update callers (`MetadataRouterFacade.kt:52-55`) to pass the id.
- **Test or report that should catch it:** `FieldResolverContentIdTest` asserting the emitted `contentId` matches the route's `parentId` for both single-request and concurrent-request scenarios.

### F-B-06: `MetadataIdentityResolver` does not register negative ID mappings

- **Severity:** P2
- **Evidence:** `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataIdentityResolver.kt:43` — when `lookupResult == null` the method silently returns the unresolved route (which then trips the `IdentityResolutionFailed` throw at `MetadataRouterFacade.kt:27-29`). No negative-cache write to `IdMappingStore` despite the existence of `IdMappingSource.NEGATIVE` and `IdMappingTtlPolicy.NEGATIVE_TTL_MS = 30 days` (`IdMappingStore.kt:3, :15`).
- **Violated contract:** Negative-mapping policy exists in `IdMappingTtlPolicy` but is never invoked from the live identity resolution path. Lane-D-style cache/backoff intent for identity lookups is not wired.
- **User-visible impact:** Every detail load for a tmdb-as-series or tvdb-as-movie conflict re-runs the identity lookup against the upstream resolver, wasting network and producing duplicate `metadata.identity_resolution` events with `success = false`. The 30-day negative TTL is dead code.
- **Required fix:** When `lookupResult == null`, persist `IdMapping(sourceId = parsed, provider = route.provider, providerId = "", source = IdMappingSource.NEGATIVE, evidence = "identity lookup failed")` via `idMappingStore.persist(...)` (after threading the store into `MetadataIdentityResolver`). Then check the store at the top of `resolve()` and short-circuit if a non-expired NEGATIVE mapping exists.
- **Test or report that should catch it:** `MetadataIdentityResolverNegativeCacheTest` asserting (a) failed lookup persists a NEGATIVE entry and (b) subsequent `resolve()` within 30 days returns immediately without invoking `Lookup`.

### F-B-07: `MetadataRequestNormalizer` swallows `ContentType.TV` into `MediaKind.SERIES` silently

- **Severity:** Nit
- **Evidence:** `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt:39-45` — `ContentType.TV` is mapped to `MetadataMediaKind.SERIES` without any trace event. `MetadataRouter.providerNativeOrConflict` (`:172`) also silently treats `TV` and `SERIES` as equivalent.
- **Violated contract:** `usedInputs` claims `item.type` is the only type-input, but the normalizer's TV→SERIES collapse is unobservable in trace mode. Validators cannot detect when a TV-typed request was routed.
- **User-visible impact:** None functionally (TV and SERIES are semantically equivalent in NEXIO), but auditing TV vs SERIES routing patterns is impossible. If a future change adds a TV-specific provider, this silent collapse will mask the regression.
- **Required fix:** Either drop `ContentType.TV` from the `ContentType` enum (preferred — there is no observable behavioural difference), or add `mediaKindCollapsed = true` to the route-decision payload when the collapse occurs.
- **Test or report that should catch it:** `MetadataRequestNormalizerTvCollapseTest` asserting the collapse is either eliminated or annotated.

### F-TM-* gaps (re-stated)

- `08-test-matrix.md` flags missing `MetadataRouterTest` coverage for: (a) PREVIEW rejection assertion, (b) ignoredInputs payload assertion, (c) provider-plan-before-execute ordering, and (d) FieldResolver primary-wins. Items (a), (b), (c), (d) are individually covered by F-B-01..05 above; the matrix should be updated to point at the same tests.

## Outcome

CHANGES_REQUESTED — Lane B's core routing/plan/run/resolve pipeline is correct in shape, but four issues block approval: F-B-01 (PREVIEW bypasses FieldResolver), F-B-02 (manual fallback facades silently disable trace + adapters), F-B-03 (DETAIL_CORE bypasses facade for TMDB enrichment), and F-B-04 (`ResolverOrchestrator` schedule is advertised but never dispatched). F-B-05/06/07 are smaller hygiene items but should ride along.
