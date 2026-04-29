# Lane B — Metadata Router & FieldResolver Architecture Review

**Review SHA:** `774a540f8`  
**Date:** 2026-04-29  
**Reviewer:** Architecture Review Dossier 2

---

## 1. Scope

This lane covers the end-to-end metadata routing and field-resolution pipeline from entry point to final document output:

| Component | Location |
|---|---|
| `MetadataRouterFacade` | `core/metadata/router/MetadataRouterFacade.kt` |
| `MetadataRouter` | `core/metadata/router/MetadataRouter.kt` |
| `MetadataRequestNormalizer` | `core/metadata/router/MetadataRequestNormalizer.kt` |
| `MetadataIdentityResolver` | `core/metadata/router/MetadataIdentityResolver.kt` |
| `IdMappingStore` / `LocalIdMappingStore` | `core/metadata/router/IdMappingStore.kt`, `LocalIdMappingStore.kt` |
| `ProviderPlanExecutor` | `core/metadata/router/ProviderPlanExecutor.kt` |
| `ProviderPlanRunner` | `core/metadata/router/ProviderPlanRunner.kt` |
| `FieldResolver` | `core/metadata/router/FieldResolver.kt` |
| `ResolverOrchestrator` | `core/metadata/router/ResolverOrchestrator.kt` |
| Network resolvers | `core/metadata/router/resolver/{Trailer,Review,Recommendation,OrganizationPerson}Resolver.kt` |
| Provider adapters | `data/integration/metadata/*MetadataProviderAdapter.kt` |
| `ResolvedMetadataDocument` / `MetadataResolutionResult` | `core/metadata/router/MetadataModels.kt`, `MetadataExecutionModels.kt` |
| Audit machinery | `test/.../metadata/audit/MetadataAuditRunner.kt`, `MetadataExecutionAuditGoldenTest.kt` |
| Architecture pins | `FieldResolverInjectionContractTest`, `FieldResolverPreviewProvenanceTest`, `FieldResolverContentIdInTraceTest`, `MetadataIdentityResolverNegativeCacheTest`, `MetadataRequestNormalizerTvWarningTest` |

### Post-F-F-03 composition residue

`app/src/main/java/com/nexio/tv/core/metadata/composition/` now contains **only** `GlobalMetadataDocument.kt`, a data-only shape (`GlobalMetadataDocument`, `EpisodeMetadata`, `ArtworkCandidate`, `FieldTrace`) with no references from the routing pipeline. It appears to be a leftover from a pre-router architecture that was never fully deleted. No routing code uses it; it is dead weight.

---

## 2. Cluster F Closure Status

| Task | Status | Evidence |
|---|---|---|
| F-B-01 (PREVIEW→FieldResolver) | CLOSED | `resolveRequest` PREVIEW branch calls `fieldResolver.resolveWithPreview`; pinned by `FieldResolverPreviewProvenanceTest` |
| F-B-02 (no direct FieldResolver construction) | CLOSED | `FieldResolverInjectionContractTest` scans production tree; `defaultMetadataRouterFacadeForManualConstruction()` and `runCatching { metadataRouterFacade }.getOrNull()` removed |
| F-B-05 (requestContentId threaded) | CLOSED | `requestContentId` passed through `resolve` / `resolveWithPreview` / 2 internal helpers; facade passes `request.contentId`; pinned by `FieldResolverContentIdInTraceTest` |
| F-B-06 (negative identity cache) | CLOSED | `MetadataIdentityResolver` reads + writes `NEGATIVE` mappings via `IdMappingStore.readRaw`; `LocalIdMappingStore` updated to pass `includeNegative = true`; pinned by `MetadataIdentityResolverNegativeCacheTest` |
| F-B-07 (TV→SERIES normalizer warning) | CLOSED | `MetadataRequestNormalizer` emits `metadata.normalizer_warning` with `reason = "TV_TYPE_COERCED_TO_SERIES"`; pinned by `MetadataRequestNormalizerTvWarningTest` |

---

## 3. Architecture Pins — Verification

| Pin | File | Status | Notes |
|---|---|---|---|
| `FieldResolverInjectionContractTest` | `architecture/FieldResolverInjectionContractTest.kt` | PASS (F-B-02) | Scans all production `.kt` files for bare `FieldResolver()` or `ProviderPlanRunner(emptySet())` |
| `FieldResolverPreviewProvenanceTest` | `router/FieldResolverPreviewProvenanceTest.kt` | PASS (F-B-01) | Proves `resolveWithPreview(preview, null, [])` produces non-empty `fieldOwners` |
| `FieldResolverContentIdInTraceTest` | `router/FieldResolverContentIdInTraceTest.kt` | PASS (F-B-05) | Proves `field_selected` payload carries `requestContentId`, not provider name |
| `MetadataIdentityResolverNegativeCacheTest` | `router/MetadataIdentityResolverNegativeCacheTest.kt` | PASS (F-B-06) | Proves second `resolve()` after null lookup short-circuits via NEGATIVE mapping |
| `MetadataRequestNormalizerTvWarningTest` | `router/MetadataRequestNormalizerTvWarningTest.kt` | PASS (F-B-07) | Proves `ContentType.TV` emits `metadata.normalizer_warning` with correct reason |
| `IntegrationApiShapeRegistryCoverageTest` | `architecture/IntegrationApiShapeRegistryCoverageTest.kt` | PASS (F-C-02) | No apiShapeId string literals in production code |

---

## 4. Metadata Execution Audit Verdict

**Bundle verdict:** `PASS` (23 routed, 17 network calls, 7 cache hits, 1 stale hit, 5 forbidden overwrites, 0 policy violations)

**Gradle test task:** `FAILED` — 1 test failure in `MetadataExecutionAuditGoldenTest`

```
routing rules match spec for all id types
  expected: <ITEM_TYPE_SERIES> but was: <ROUTING_ID_TYPE_CONFLICT>
  (MetadataExecutionAuditGoldenTest.kt:130)
```

The bundle-level verdict is `PASS` because the audit runner itself does not assert routing reasons; the assertion at line 130 is a standalone golden test in the same class. The `runDefaultScenarioBundle()` path (which generates the JSON/markdown artifacts) succeeded because no `AuditVerdict.FAIL` was triggered by policy violations. This divergence means the audit JSON is optimistic — **the sign-off artifact should not be used as evidence of routing correctness for the `netflix-series` / IMDB-as-series scenario.**

---

## 5. Red-Flag Checklist

### "Facade called, result ignored"

`MetadataRouterFacade.fetchTmdbEnrichment`, `fetchTrailer`, `fetchRecommendations`, `findPersonIdByExactName`, and `findCompanyIdByExactName` all call `resolveRequest(...)` internally and **intentionally discard** the resolved document — their KDoc is explicit (the document's TMDB carry-set is narrower than the rich enrichment shape, or the caller needs a player-ready `TrailerResolutionResult`). This pattern is deliberate and documented; it is an architectural smell to monitor but not a defect given the explanation. The `ProviderLocalizedMetadataResolver` and `MetaDetailsViewModel` all consume the resolved document, so no regressions exist on that dimension. No unintentional discard found.

### "Legacy router still used after facade"

`grep -rn "MetadataRouter.route\|router\.route"` against production sources finds zero direct callsites outside `MetadataRouterFacade` itself (which owns the two internal calls in `routeRequest` and `fetchTvEpisodeEnrichment`). Clean.

### "Provider-native conflict silently rewrites IDs"

See B-01 and B-03 below. The ID rewrite is flagged and traced (via `ROUTING_ID_TYPE_CONFLICT` in trace), but the `MetadataAuditRunner.toAuditEvent` override logic conflates the identity-resolver's post-resolution trace entry with the original routing decision reason, causing the false audit report.

### "PREVIEW triggers route or runtime work"

F-B-01 fully closed. `resolveRequest` at `MetadataDepth.PREVIEW` returns before `routeRequest()` is ever called. The `MetadataRouter` itself asserts `require(request.depth != PREVIEW)`. Pin confirmed passing.

### "Validator rule has no event source"

`TraceValidationRules.LocalizationPlanPrecedesProviderSteps` requires `metadata.localization_plan` events before `metadata.provider_plan` for TVDB/TMDB/KITSU routes. Production adapters (`TmdbMetadataProviderAdapter`, `TvdbMetadataProviderAdapter`, `KitsuMetadataProviderAdapter`) all call `traceEvents.emitLocalizationPlan(...)`. The **audit-runner stub adapter** (`AuditMetadataProviderAdapter`) does NOT emit this event, which means the audit runner does not exercise this rule — but this is a cross-lane concern (Lane E / localization). No orphaned rule in the Lane B flow proper.

### "FieldResolver.emit* helpers with no production callers"

All `traceEvents.emit*` calls in `FieldResolver` (`emitFieldSelected`) have production callsites via `buildDocument`. No orphaned helpers.

---

## 6. Cross-Lane Concerns

**Localization scope (defer to Lane E):** TMDB uses `LocalizationPolicy` only for cache-key versioning (`policy:2` suffix); locale selection is done per-provider inside each adapter, not through a unified `LocalizationResolver`. This is provider-scoped localization, not a routing-layer concern. Flag only: the `LocalizationPlanPrecedesProviderSteps` validator rule depends on the adapter layer emitting `metadata.localization_plan` synchronously before `metadata.provider_plan` — if an adapter is refactored to defer this event, the rule will fire false positives. Track in Lane E.

**`GlobalMetadataDocument` dead code (defer to housekeeping):** `core/metadata/composition/GlobalMetadataDocument.kt` has no callers in the routing pipeline. Recommend deletion in a follow-up sweep.

---

## 7. Findings

### B-01 (P1) — `MetadataExecutionAuditGoldenTest.routing rules match spec for all id types` fails: IMDB-as-series reported as `ROUTING_ID_TYPE_CONFLICT` instead of `ITEM_TYPE_SERIES`

**Severity:** P1 — Breaks the CI gate for the audit golden test; corrupts the routing-reason field in the audit report for the `netflix-series` scenario.

**Root cause (two-layer bug):**

**Layer 1 — `MetadataIdentityResolver` appends `ROUTING_ID_TYPE_CONFLICT` to the trace of any successfully resolved route:**

`MetadataIdentityResolver.resolve()` (line 75–82) adds a `MetadataRouteTrace(reason = ROUTING_ID_TYPE_CONFLICT, ...)` to the route trace when it successfully resolves an identity. This trace entry is semantically correct for a provider-native-conflict route (e.g., `tmdb:1399` + SERIES), but it is **also appended for IMDB-as-series routes** that legitimately go through `imdbMappedOrFallback → fallbackByItemType(ITEM_TYPE_SERIES)` and then require identity resolution (because TVDB has no native IMDB id — see buildTargetIds logic).

For `tt14403178` (series):
1. `AnimeIdScheme.IMDB` → `imdbMappedOrFallback` — no Kitsu mapping, falls through to `fallbackByItemType`.
2. `fallbackByItemType` emits trace `ITEM_TYPE_SERIES` → routes to TVDB.
3. `buildTargetIds`: IMDB id present, TVDB key absent, `canResolveThroughKnownCrossProviderTarget = true` → `requiresIdentityResolution = true`.
4. `MetadataIdentityResolver.resolve()` performs `imdbToTvdb("tt14403178")` successfully and appends `ROUTING_ID_TYPE_CONFLICT` to the trace.

**Layer 2 — `MetadataAuditRunner.toAuditEvent` overrides the route reason when any trace entry is `ROUTING_ID_TYPE_CONFLICT`:**

```kotlin
reason = if (trace.any { it.reason == ROUTING_ID_TYPE_CONFLICT }) {
    ROUTING_ID_TYPE_CONFLICT
} else { reason }
```
(MetadataAuditRunner.kt lines 582–586)

This override was designed for provider-native-conflict routes where the canonical reason on the route itself is the final fallback reason (e.g., `ITEM_TYPE_SERIES`), and the trace entry is the conflict signal. However, it also fires for IMDB-as-series routes that went through `fallbackByItemType` → `ITEM_TYPE_SERIES` but required identity resolution. The audit now misreports the reason.

**Two valid fixes — maintainer must choose:**

*Option A — Fix `MetadataIdentityResolver` (preferred):* The identity resolver's success trace entry should NOT use `ROUTING_ID_TYPE_CONFLICT` as the reason for routes that did not originate from a provider-native conflict. Rename the trace entry reason to a dedicated `IDENTITY_RESOLVED` (or similar), or omit the trace entry entirely when resolution is not a conflict case. The conflict signal is already present as the pre-resolution trace entry emitted by `providerNativeOrConflict`.

*Option B — Fix `MetadataAuditRunner.toAuditEvent`:* Change the override to check `route.reason == ROUTING_ID_TYPE_CONFLICT` (the route's own canonical reason) rather than scanning the trace for any `ROUTING_ID_TYPE_CONFLICT` entry. This is a narrower fix that does not change production behaviour.

**Required fix summary for maintainer:**
The audit runner's `toAuditEvent` override is too broad — it conflates the resolver's post-resolution trace entry with the original routing decision reason. For a proper fix, `MetadataIdentityResolver` should not emit `ROUTING_ID_TYPE_CONFLICT` for routes that originated from `ITEM_TYPE_SERIES` / `ITEM_TYPE_MOVIE` fallback paths. The `ROUTING_ID_TYPE_CONFLICT` trace reason should only appear when the originating routing decision was a provider-native conflict.

**Files to change:**
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataIdentityResolver.kt` (line 79)
- OR `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditRunner.kt` (lines 582–586)

---

### B-02 (P2) — `FieldSelectedTraceTest.secondary field rejected` fails — rejection reason wording mismatch

**Severity:** P2 — Test failure; no production behaviour change.

**Root cause:**

`FieldSelectedTraceTest.secondary field rejected by primary ownership emits in rejectedCandidates` (line 40–71) asserts:
```kotlin
assertTrue((rejected.first()["reason"] as String).contains("primary"))
```

In `FieldResolver.applyMissingCandidate`, when a secondary candidate tries to set a field that is already claimed (by the primary), the recorded rejection reason is:
```kotlin
"reason" to "field already filled"
```
(FieldResolver.kt line 355)

The string `"field already filled"` does not contain `"primary"`. The test was written against an older wording that included the word "primary" (e.g., `"field already owned by PRIMARY"` or similar). A change to `FieldResolver` — most likely during the F-B-01/F-B-02 refactor — updated the rejection reason wording without updating this test.

**Required fix:** Update the test assertion to match the current wording `"field already filled"`, OR update `FieldResolver` to use a reason string that includes "primary" (e.g., `"field already owned by PRIMARY; rejected secondary"`). The latter makes the reason more descriptive and matches the `SecondaryDoesNotOverwritePrimary` validator's intent. Either change is safe.

**File to change:**
- `app/src/test/java/com/nexio/tv/core/metadata/router/FieldSelectedTraceTest.kt` (line 70)

---

### B-03 (P2) — `MetadataRouterPrecedenceTest.provider native id type conflict records conflict and falls back by item type` fails — targetIds map key change

**Severity:** P2 — Test failure; production routing behaviour is correct (identity resolution flag is set), but the test expectation is stale.

**Root cause:**

The test was introduced in commit `e5c19c813` and expected:
```kotlin
assertEquals("tmdb:1399", route.targetIds[MetadataPrimaryProvider.TVDB])
```

In commit `5468aba18` (`fix: seed metadata targets from addon preview ids`), `buildTargetIds` was refactored from a simple `mutableMapOf(provider to targetId)` start into a scheme-aware dispatch:
```kotlin
val targetParsed = MetadataIdParser.parse(targetId)
when (targetParsed.scheme) {
    AnimeIdScheme.TMDB -> builder.putIfAbsent(MetadataPrimaryProvider.TMDB, "tmdb:${targetParsed.value}")
    ...
    else -> builder.putIfAbsent(provider, targetId)
}
```

For `tmdb:1399` routed to TVDB (conflict path → `fallbackByItemType`), `targetId = "tmdb:1399"` is parsed as `AnimeIdScheme.TMDB`, so it is now inserted under `MetadataPrimaryProvider.TMDB` (not `TVDB`). The test asserts `targetIds[TVDB] == "tmdb:1399"`, but the current code puts `"tmdb:1399"` under `TMDB` and leaves `TVDB` absent. The `requiresIdentityResolution = true` flag is still set correctly (TVDB has IMDB or TMDB cross-target → true), so the production behaviour (identity resolution required, identity resolver will look up tvdb id from tmdb id) is correct.

**Required fix:** Update the test to reflect the current map layout:
```kotlin
assertEquals("tmdb:1399", route.targetIds[MetadataPrimaryProvider.TMDB])
assertNull(route.targetIds[MetadataPrimaryProvider.TVDB])
assertTrue(route.targetIdRequiresIdentityResolution)
```

**File to change:**
- `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterPrecedenceTest.kt` (lines 153–157)

---

### B-04 (P2) — `MetadataRouterTargetIdsImdbTest` x2 failures — incomplete `previewStableIds` routing WIP

**Severity:** P2 — Two tests fail; represent incomplete implementation of `previewStableIds` routing.

**Root cause:**

Commits `5468aba18` and `361761bb7` added `previewStableIds`-aware routing to `buildTargetIds` and added corresponding tests, but two tests were committed in a failing state. The test file `MetadataRouterTargetIdsImdbTest` contains cases that depend on behaviour not yet fully implemented or that conflict with the now-stricter `numericProviderTarget` validation (which rejects non-numeric TVDB/TMDB values).

The two failing tests are (based on context):
1. `addon preview stable TVDB id wins over raw IMDB series content id` — expects `route.targetIdRequiresIdentityResolution == false` when a numeric TVDB stable ID is provided.
2. `malformed addon preview provider stable ids are ignored` — expects that non-numeric stable IDs are silently dropped.

Both tests are in the same file and appear to be WIP assertions that were committed without a passing production implementation. They do not represent a regression against a prior green state — they were authored red.

**Required fix:** Confirm which tests are failing (Gradle output from these tests was not captured in the XML artifact). Implement the missing `previewStableIds` routing logic for the cases that are failing, or mark the tests as `@Ignore` with a tracking note if the feature is planned for a later cluster.

**File to investigate:**
- `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterTargetIdsImdbTest.kt`

---

### B-05 (P2) — No architecture pin enforces "only `FieldResolver`/`MetadataRouterFacade` construct `ResolvedMetadataDocument`"

**Severity:** P2 — Missing guard; constraint enforced only by source scan at review time.

**Evidence:**

`grep -rn "ResolvedMetadataDocument("` in production finds exactly two construction sites:
1. `FieldResolver.buildDocument()` — the canonical path.
2. `MetadataRouterFacade.resolveRequest()` — the PREVIEW/no-data early-return path (line 69), which constructs an empty document when `previewCandidate == null`.

Both are legitimate. However, there is no enforcement mechanism (architecture pin test) that prevents future code from adding a third construction site (e.g., in a ViewModel or Repository). Given that `FieldResolverInjectionContractTest` already demonstrates the pattern for `FieldResolver()` and `ProviderPlanRunner(emptySet())`, a companion pin for `ResolvedMetadataDocument(` would complete the boundary.

**Required fix:** Add a test (similar to `FieldResolverInjectionContractTest`) that scans production sources for `ResolvedMetadataDocument(` and asserts the only permitted construction sites are within `core/metadata/router/FieldResolver.kt` and `core/metadata/router/MetadataRouterFacade.kt`.

**File to create:**
- `app/src/test/java/com/nexio/tv/architecture/ResolvedMetadataDocumentOwnershipTest.kt`

---

### B-06 (Nit) — `MetadataIdentityResolver` uses `ROUTING_ID_TYPE_CONFLICT` as success-resolution trace reason

**Severity:** Nit (subsumed by B-01, documented separately for precision)

Beyond causing B-01, the semantics of `ROUTING_ID_TYPE_CONFLICT` as a trace reason for **successful** identity resolution are misleading. A trace reader sees `ROUTING_ID_TYPE_CONFLICT` and expects the route is in a conflicted, unresolved state. In reality, at the point this trace entry is written (line 79 of `MetadataIdentityResolver.kt`), the conflict has been **resolved** — the identity resolver found a TVDB id. A dedicated `IDENTITY_RESOLVED` reason (or a comment + enum entry) would make the trace self-explanatory.

---

### B-07 (Nit) — `GlobalMetadataDocument` dead code in composition package

**Severity:** Nit

`app/src/main/java/com/nexio/tv/core/metadata/composition/GlobalMetadataDocument.kt` is unreferenced by the routing pipeline, any ViewModel, or any test. It contains only data classes (`GlobalMetadataDocument`, `EpisodeMetadata`, `ArtworkCandidate`, `FieldTrace`). It is a remnant of a pre-router architecture. Recommend deletion.

---

### B-08 (Nit) — `MetadataRouterFacade.fetchTmdbEnrichment` discards resolved document with a deliberate but fragile pattern

**Severity:** Nit / architectural debt marker

`fetchTmdbEnrichment` intentionally discards the `ResolvedMetadataDocument` from `resolveRequest()` to fire trace events and then delegates to `MetadataSecondaryRepository` for the richer 22-field enrichment shape. The KDoc is explicit. The risk: if a future maintainer adds a call to `resolveRequest()` that **also** discards the result but for non-intentional reasons (e.g., a forgotten assignment), there is no lint or pin that distinguishes "intentional discard (documented)" from "accidental discard". Consider a naming convention (e.g., `resolveRequestForTraceOnly()`) or a local annotation to make the intentionality machine-checkable.

---

## 8. Summary Table

| ID | Severity | Description | Status |
|---|---|---|---|
| B-01 | P1 | `MetadataExecutionAuditGoldenTest.routing rules match spec for all id types` fails — IMDB-as-series incorrectly reported as `ROUTING_ID_TYPE_CONFLICT` due to identity resolver appending that reason to non-conflict routes | OPEN |
| B-02 | P2 | `FieldSelectedTraceTest.secondary field rejected` fails — rejection reason `"field already filled"` does not contain `"primary"` as test expects | OPEN |
| B-03 | P2 | `MetadataRouterPrecedenceTest.provider native id type conflict` fails — `buildTargetIds` refactor (commit `5468aba18`) moved TMDB-scheme ids to the TMDB key rather than TVDB; test assertion stale | OPEN |
| B-04 | P2 | `MetadataRouterTargetIdsImdbTest` x2 failures — incomplete `previewStableIds` routing WIP committed red | OPEN |
| B-05 | P2 | No architecture pin guards `ResolvedMetadataDocument` construction to only `FieldResolver` and `MetadataRouterFacade` | OPEN |
| B-06 | Nit | `ROUTING_ID_TYPE_CONFLICT` trace reason used for successful identity resolution — misleading semantics (root cause of B-01) | OPEN |
| B-07 | Nit | `GlobalMetadataDocument.kt` in `core/metadata/composition/` is dead code with no callers | OPEN |
| B-08 | Nit | `fetchTmdbEnrichment` intentionally-discarded-resolve pattern has no machine-checkable marker distinguishing it from accidental discard | OPEN |

---

## 9. Overall Lane Health

**Lane B is conditionally healthy.** The Cluster F fixes (F-B-01 through F-B-07) are all correctly implemented and pinned. The production routing logic is sound: the IMDB-as-series route DOES reach TVDB via `ITEM_TYPE_SERIES` and identity resolution — only the audit reporting layer misattributes the reason. The three parallel-session WIP failures (B-02, B-03, B-04) are all test-only issues that do not affect production execution. The one genuine architectural gap is B-05 (no ownership pin for `ResolvedMetadataDocument`). **The single P1 finding (B-01) must be resolved before the audit test gate is re-run, as it currently breaks the CI sign-off for this lane.**
