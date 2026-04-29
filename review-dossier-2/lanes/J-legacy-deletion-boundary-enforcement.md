# Lane J — Legacy Deletion and Boundary Enforcement

Review SHA: `774a540f8` — Generated 2026-04-29

---

## 1. What changed in this lane on this branch

**Deleted symbols (all confirmed absent from production at SHA `774a540f8`).**

| Symbol | Deletion commit | Package |
|---|---|---|
| `IntegrationScope.Account(providerAccountId: String)` (legacy single-arg ctor) | `3716d4b0c` | `core/integration/IntegrationScope.kt` |
| `ProfileBoundaryEnforcer.validateLegacyAccountScope` | `d3c34336a` | `core/integration/ProfileBoundaryEnforcer.kt` |
| `ProfileMetadataOverlay` | `431b94105` | `core/metadata/composition/` |
| `ProfileResolvedDisplayDocument` | `431b94105` | `core/metadata/composition/` |
| `CompositionTypeShapeTest` (fixture for the two above) | `431b94105` | deleted with its subjects |
| `HomeDisplayMetadata.toResolvedDocument()` | `0dca11291` | `core/metadata/router/MetadataRouterFacade.kt` — PREVIEW path now routes through `FieldResolver.resolveWithPreview()` |
| `MetaDetailsViewModel.defaultMetadataRouterFacadeForManualConstruction()` | `e88054a1f` | `ui/screens/detail/MetaDetailsViewModel.kt` |
| `HomeProviderLocalizedMetadataOverlay.runCatching` fallback sidecar | `5dbf2163d` | `ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt` |
| `metadataRouterFacadeOrNull()` / `providerLocalizedMetadataResolverOrNull()` helpers | `5dbf2163d` | same file |

**Architecture pins added (all live at SHA `774a540f8`).**

| Pin ID | Test class | Summary |
|---|---|---|
| F-J-03 | `IntegrationScopeGlobalDeprecatedNoCallersTest` | No production file constructs `IntegrationScope.Global` outside allowlist |
| F-J-04 | `DeprecatedAnnotationsHaveReplaceWithTest` | Every `@Deprecated` in production carries `ReplaceWith` |
| F-C-02 | `IntegrationApiShapeRegistryCoverageTest` | `apiShapeId` arguments are property references, not string literals |
| F-B-02 | `FieldResolverInjectionContractTest` | No production file constructs `FieldResolver()` or `ProviderPlanRunner(emptySet())` directly |
| F-B-01 | `FieldResolverPreviewProvenanceTest` | PREVIEW resolution produces non-empty `fieldOwners` |
| F-C-06 | `TraktGlobalContentCacheKeyTest` | Global-content fetch functions do not use `accountCacheKey()` |
| — | `AddonFirstPaintShapeArchitectureTest` (`home hydration does not call addon detail metadata directly`) | Home hydration must not call `getMetaFromAllAddons()` — **two iterations in quick succession** (df5ddb293 → 726de12f3) |

**Commits** that landed boundary tests in this branch visible in `git log`:
- `df5ddb293` — "test(arch): forbid direct addon detail hydration in Home" — first iteration scanned only `HomeCatalogRefreshCoordinator.kt`.
- `726de12f3` — "test(arch): scan all Home addon hydration calls" — broadened scope to all `ui/screens/home/**/*.kt` files via `homeFiles()`.
- `b620f8927` — "test: allow generic addon lifecycle guard names" — expanded allowed generic prefixes in the provider-specific lifecycle guard.

---

## 2. Architecture surfaces in scope

| Surface | File | Status |
|---|---|---|
| `NoIntegrationRuntimeInjectionOutsideBoundaryTest` | `architecture/NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt` | active — allowlist: 6 packages |
| `IntegrationBoundaryTest` | `architecture/IntegrationBoundaryTest.kt` | active — allowlist: 8 path suffixes (incl. 3 auth-service carve-outs) |
| `NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest` | `architecture/NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest.kt` | active — allowlist: 4 path suffixes |
| `NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest` | `architecture/NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest.kt` | active — allowlist: 14 path suffixes |
| `NoDirectOkHttpOutsideRuntimeTransportPackagesTest` | `architecture/NoDirectOkHttpOutsideRuntimeTransportPackagesTest.kt` | active — allowlist: `core/di/` + `data/integration/*/transport/` |
| `NoRawProviderInjectionTest` | `architecture/NoRawProviderInjectionTest.kt` | active — same allowlist |
| `NoRuntimeSpecOutsideIntegrationPackagesTest` | `architecture/NoRuntimeSpecOutsideIntegrationPackagesTest.kt` | active — allowlist: 6 packages |
| `NoLegacyProviderFallbacksTest` | `architecture/NoLegacyProviderFallbacksTest.kt` | active — allowlist: `core/di/` only |
| `NoUnwrappedProviderCallsInsideIntegrationPackagesTest` | `architecture/NoUnwrappedProviderCallsInsideIntegrationPackagesTest.kt` | active — scans `data/integration/**` only |
| `IntegrationScopeGlobalDeprecatedNoCallersTest` | `architecture/IntegrationScopeGlobalDeprecatedNoCallersTest.kt` | active — allowlist: 2 relative paths |
| `DeprecatedAnnotationsHaveReplaceWithTest` | `architecture/DeprecatedAnnotationsHaveReplaceWithTest.kt` | active — allowlist: 3 relative paths (BringIntoViewSpec forced overrides) |
| `IntegrationApiShapeRegistryCoverageTest` | `architecture/IntegrationApiShapeRegistryCoverageTest.kt` | active — no allowlist (zero exemptions) |
| `FieldResolverInjectionContractTest` | `architecture/FieldResolverInjectionContractTest.kt` | active — no allowlist (zero exemptions) |
| `FieldResolverPreviewProvenanceTest` | `core/metadata/router/FieldResolverPreviewProvenanceTest.kt` | active — behavior test |
| `MetadataRouterBoundaryTest` | `architecture/MetadataRouterBoundaryTest.kt` | active — allowlist: 13 path suffixes |
| `MetadataProductionBoundaryTest` | `architecture/MetadataProductionBoundaryTest.kt` | active — targeted entrypoints |
| `MetadataArchitectureBoundaryTest` | `metadata/audit/MetadataArchitectureBoundaryTest.kt` | active — imports scan only |
| `ProfileBoundaryArchitectureTest` | `architecture/ProfileBoundaryArchitectureTest.kt` | active — source-grep and function-body checks |
| `AddonFirstPaintShapeArchitectureTest` | `architecture/AddonFirstPaintShapeArchitectureTest.kt` | active — home-hydration and lifecycle naming checks |
| `TraktGlobalContentCacheKeyTest` (F-C-06) | `data/integration/trakt/TraktGlobalContentCacheKeyTest.kt` | active — source-grep on function bodies |
| `IntegrationScope.Global` (deprecated object) | `core/integration/IntegrationScope.kt:40` | deprecated — no production callers; F-J-03 pin enforces this |
| `ProfileBoundaryEnforcer.validateRequest` | `core/integration/ProfileBoundaryEnforcer.kt` | active — handles `IntegrationScope.Global` in `when` for back-compat |

---

## 3. Contracts this lane must satisfy

1. All symbols deleted in F-J-02/F-F-03/F-F-05/F-B-01/F-B-02 have zero remaining references in production code.
2. Every architecture-pin allowlist entry names a directory or file that still exists and still warrants the exemption.
3. `IntegrationRuntime` references are confined to `core/integration`, `core/di`, `core/anime`, `data/integration`, `core/tmdb`, and `core/tvdb`.
4. `IntegrationSpec`/`IntegrationCallSpec`/`IntegrationStreamSpec` construction is confined to `core/integration`, `core/anime`, `data/integration`, `core/tmdb`, `core/tvdb`, and `data/local/integration`.
5. Raw Retrofit API interfaces in `data/remote/api/` are not referenced outside `core/di/`, `data/integration/`, `data/remote/api/`, and an explicit carve-out for `KitsuAuthService.kt`.
6. Auth services (`TraktAuthService`, `SimklAuthService`, `KitsuAuthService`, `TvdbAuthService`, `RealDebridAuthService`) are not referenced outside their explicit 14-entry allowlist.
7. Raw OkHttp primitives (`OkHttpClient`, `Request.Builder`, `.newCall(`, `HttpURLConnection`) are confined to `core/di/` and `data/integration/*/transport/`.
8. `IntegrationScope.Global` (deprecated) is not constructed in production outside the 2-file allowlist.
9. Every `@Deprecated` annotation carries `ReplaceWith` (3 UI-framework forced-override exceptions exempt).
10. `apiShapeId` arguments in production use property references, not string literals.
11. `FieldResolver()` and `ProviderPlanRunner(emptySet())` are not directly constructed in production.
12. PREVIEW metadata resolution routes through `FieldResolver.resolveWithPreview()` and produces non-empty `fieldOwners`.
13. `MetadataRouter.route()` is called only from within `MetadataRouterFacade`.
14. Home hydration does not call `getMetaFromAllAddons()` from any file under `ui/screens/home/`.
15. Global Trakt content cache keys do not include a profile prefix.

---

## 4. Generated reports proving (or not) each contract

| Contract | Evidence | Verdict |
|---|---|---|
| C-1: Deleted symbols have zero production references | Source grep: `ProfileMetadataOverlay`, `ProfileResolvedDisplayDocument`, `providerAccountId`, `validateLegacyAccountScope`, `defaultMetadataRouterFacadeForManualConstruction`, `metadataRouterFacadeOrNull`, `providerLocalizedMetadataResolverOrNull` — all return 0 hits in `app/src/main/` | PASS |
| C-2: `toResolvedDocument()` deleted | Single reference found only in a KDoc comment inside the test `FieldResolverPreviewProvenanceTest.kt` — the method no longer exists in production | PASS |
| C-3: `IntegrationRuntime` confined to approved packages | Source scan — zero hits outside approved 6-package set | PASS |
| C-4: `IntegrationSpec` confined to approved packages | Source scan — zero hits outside approved 6-package set | PASS |
| C-5: Raw API interfaces confined | `NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest` and `IntegrationBoundaryTest` both pass at SHA | PASS |
| C-6: Auth services confined | `NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest` passes at SHA | PASS |
| C-7: Raw OkHttp confined | `NoDirectOkHttpOutsideRuntimeTransportPackagesTest` and `NoRawProviderInjectionTest` pass at SHA | PASS |
| C-8: `IntegrationScope.Global` not constructed in production | `IntegrationScopeGlobalDeprecatedNoCallersTest` (F-J-03) — scans all production Kotlin sources | PASS |
| C-9: `@Deprecated` has `ReplaceWith` | `DeprecatedAnnotationsHaveReplaceWithTest` (F-J-04) | PASS |
| C-10: `apiShapeId` literals banned | `IntegrationApiShapeRegistryCoverageTest` (F-C-02) | PASS |
| C-11: No direct `FieldResolver()` / `ProviderPlanRunner(emptySet())` construction | `FieldResolverInjectionContractTest` (F-B-02) | PASS |
| C-12: PREVIEW path carries non-empty `fieldOwners` | `FieldResolverPreviewProvenanceTest` (F-B-01) — behavior test | PASS |
| C-13: `MetadataRouter.route()` only from facade | Source scan — only `MetadataRouterFacade.kt` calls `router.route(...)` | PASS |
| C-14: Home hydration not calling `getMetaFromAllAddons()` | `AddonFirstPaintShapeArchitectureTest` — scans all `ui/screens/home/**` | **FAIL — see J-01** |
| C-15: Trakt global content keys not profile-prefixed | `TraktGlobalContentCacheKeyTest` (F-C-06) — source-grep on function bodies | PASS |

---

## 5. Cross-cutting risks

**J-01 (P1) — `getMetaFromAllAddons()` still called from two Home files; architecture pin scope is insufficient.**
The pin `home hydration does not call addon detail metadata directly` in `AddonFirstPaintShapeArchitectureTest` scans `homeFiles()` which covers `ui/screens/home/**`. However, `HomeViewModelContinueWatchingRuntimePipeline.kt` (line 29) and `HomeViewModelPresentationPipeline.kt` (lines 474, 548) both contain `getMetaFromAllAddons()` calls. The test therefore currently *fails* on the production tree at SHA `774a540f8`. The pin was broadened from single-file (commit df5ddb293) to all home files (commit 726de12f3) but no corresponding production fix was made. This is an active test failure that blocks a clean run of the architecture test suite for the lane.

**J-02 (P1) — `checkin` callers in Home and Detail never supply `ownerProfileId`; no architecture pin.**
`HomeViewModelContinueWatching.kt` (line 590) and `MetaDetailsViewModel.kt` (line 3076) both call `trackingScrobbleService.checkin(item)` without supplying `ownerProfileId`. The `TrackingScrobbleService.checkin` signature accepts `ownerProfileId: Int? = null` as an optional parameter — the contract allows null — but the downstream Simkl and Trakt scrobble services route through account-scoped integration calls. There is no architecture pin verifying that `checkin` callers in the UI supply a profile owner. This was identified as a Stage 2 surprise and remains unresolved: callers rely on an implicit profile context derived inside the service rather than propagated from the UI session boundary.

**J-03 (P1) — `ResolvedMetadataDocument` direct construction inside `MetadataRouterFacade` is not pinned.**
`MetadataRouterFacade.kt` (line 69) constructs `ResolvedMetadataDocument(...)` directly in the PREVIEW no-data fallback branch. This is within `core/metadata/router` which is the correct package, and the instance has `fieldOwners = emptyMap()` (explicitly documented as the no-data case, post-fix). However, there is no architecture pin preventing future direct construction of `ResolvedMetadataDocument` outside `FieldResolver` or `MetadataRouterFacade`. Any file could construct one and inject it as a result without going through the field-resolution pipeline. `FieldResolverInjectionContractTest` covers `FieldResolver()` no-arg construction but does not cover `ResolvedMetadataDocument(...)` construction outside authorized sites. Identified in Stage 2 survey; remains without a pin.

**J-04 (P2) — `core/tmdb` and `core/tvdb` appear on two allowlists but contain no `IntegrationRuntime` or `IntegrationSpec` references at SHA.**
Both `NoIntegrationRuntimeInjectionOutsideBoundaryTest` and `NoRuntimeSpecOutsideIntegrationPackagesTest` exempt `com.nexio.tv.core.tmdb` and `com.nexio.tv.core.tvdb` from their scans. Source inspection at SHA `774a540f8` confirms that none of the four files in `core/tmdb/` and none of the 20+ files in `core/tvdb/` contain any `IntegrationRuntime` or `IntegrationSpec` references. The exemptions therefore allow packages that don't actually need them. The justification is architectural anticipation — both packages contain provider-wrapping services (`TmdbMetadataService`, `TvdbMetadataService`, etc.) that may receive runtime injection as part of planned adapter migration. However, since no file currently uses the exempted type, the allowlist entries are "pre-granted" rather than "justified-by-current-usage". If the migration is not completed, these entries will remain as latent holes in the boundary: a future commit could import `IntegrationRuntime` into `TmdbMetadataService` without triggering any test failure.

**J-05 (P2) — Auth-service carve-outs in `IntegrationBoundaryTest` create a structural split: `KitsuAuthService`, `RealDebridAuthService`, and `SimklAuthService` live outside `data/integration/` but own raw Retrofit API references.**
`IntegrationBoundaryTest` and `NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest` both exempt these three files. Investigation confirms:
- `KitsuAuthService.kt` imports `KitsuAuthApi` (a Retrofit interface in `data/remote/api/`) and injects it directly.
- `RealDebridAuthService.kt` imports `retrofit2.Response` and delegates to `RealDebridAuthIntegrationProvider` for actual calls — raw Retrofit surface is minimal but present.
- `SimklAuthService.kt` imports `retrofit2.Response` and delegates to `SimklAuthIntegrationProvider`.

The justification is historical placement: auth services predate the integration runtime and were never migrated into `data/integration/`. `NoDirectAuthProviderNetworkOwnersTest` verifies that `RealDebridAuthService` and `SimklAuthService` do not own raw `realDebridApi.` or `simklApi.` calls (they delegate to integration providers), which partially mitigates the risk. However, `KitsuAuthService` is an explicit exception from the full-tree provider API test, and the `TvdbAuthService` in `core/tvdb/` is separately allowed in `NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest`. There is no migration plan or target date for moving these into `data/integration/`.

**J-06 (P2) — F-C-06 pin (`TraktGlobalContentCacheKeyTest`) is a source-grep proxy, not a behavior test; window size creates false-negative risk.**
The test reads up to 2500 chars from each function declaration start and checks for `accountCacheKey(`. This approach is explicitly acknowledged in the commit message as a proxy. The risk: if a global-content fetch function is refactored to use an internal helper that is itself longer than 2500 chars before the cache-key call, or if the function is restructured such that `accountCacheKey` appears beyond the window, the test would not catch it. The Lane C dossier (finding F-C-06) already flagged that the earlier fix required adding a separator comment block (Task 26) to maintain separator visibility — confirming layout sensitivity. This remains the only architecture pin implemented as a fixed-window substring scan.

**J-07 (P2) — F-J-01 meta-finding: the new pins partially close facade-bypass gaps but do not cover all surfaces identified in Lanes B, C, and H.**
At SHA `774a540f8`, the following new pins are in place that close previously unguarded surfaces:
- `MetadataRouterBoundaryTest.home preview paths do not execute router preview requests` — closes one H-lane bypass.
- `RailPreviewLifecycleArchitectureTest.home provider overlay does not retain router facade sidecar helpers` — closes B-lane sidecar bypass.
- `AddonFirstPaintShapeArchitectureTest.home hydration does not call addon detail metadata directly` — targets the Home direct-addon-call surface (but is currently failing per J-01).
- `MetadataProductionBoundaryTest.production metadata entrypoints use facade or repository ownership` — checks 5 specific entrypoints for `MetadataRouterFacade` symbol presence.

Gaps remaining without architecture pins:
1. No pin prevents `getMetaFromAllAddons()` calls from files **outside** `ui/screens/home/` (e.g. `StreamScreenViewModel.kt`, `PlayerRuntimeControllerStreams.kt`, `IdleScreensaverPreparation.kt` all call it — no test forbids this).
2. No pin verifies that `ProviderLocalizedMetadataResolver` is not re-instantiated manually outside Hilt (the `FieldResolverInjectionContractTest` pattern only covers `FieldResolver` and `ProviderPlanRunner`).
3. No pin verifies that `ResolvedMetadataDocument` is only constructed inside `FieldResolver` or `MetadataRouterFacade` (covered in J-03).

**J-08 (Nit) — `MetadataArchitectureBoundaryTest` checks import lines only; `MetadataProductionBoundaryTest` checks content; both cover the same forbidden symbols — duplication with divergent scope.**
`MetadataArchitectureBoundaryTest` (in `metadata/audit/`) scans import statements of files in `ui/`, `workers/`, and a `ContinueWatching` prefix path. `MetadataProductionBoundaryTest` (in `architecture/`) scans whole-file content of the same paths (via `metadataCallerRoots`). The import-level scan is strictly weaker than the content scan because a class could be used without an explicit import (same package). The duplication means neither is obviously the canonical pin. No functional gap at this SHA but the divergence adds maintenance cost.

---

## 6. Test classification by type

| Test | Type | Fragility |
|---|---|---|
| `NoIntegrationRuntimeInjectionOutsideBoundaryTest` | Source-grep (package-name based) | Low — package names stable |
| `IntegrationBoundaryTest` | Source-grep (path suffix + regex) | Low — Retrofit API names stable |
| `NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest` | Source-grep (path suffix + regex) | Low — API interface names stable |
| `NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest` | Source-grep (path suffix + regex) | Low — auth service names stable |
| `NoDirectOkHttpOutsideRuntimeTransportPackagesTest` | Source-grep (path suffix + regex) | Low — OkHttp type names stable |
| `NoRawProviderInjectionTest` | Source-grep (path suffix + regex) | Low |
| `NoRuntimeSpecOutsideIntegrationPackagesTest` | Source-grep (package-name based) | Low |
| `NoLegacyProviderFallbacksTest` | Source-grep (content + path) | Low |
| `NoUnwrappedProviderCallsInsideIntegrationPackagesTest` | Behavioral (AST-like brace-depth parser) | Medium — custom parser; depends on code layout |
| `IntegrationScopeGlobalDeprecatedNoCallersTest` (F-J-03) | Source-grep (regex) | Low |
| `DeprecatedAnnotationsHaveReplaceWithTest` (F-J-04) | Source-grep (paren-balanced parser) | Low |
| `IntegrationApiShapeRegistryCoverageTest` (F-C-02) | Source-grep (regex) | Low |
| `FieldResolverInjectionContractTest` (F-B-02) | Source-grep (regex) | Low |
| `FieldResolverPreviewProvenanceTest` (F-B-01) | Behavioral (instantiates `FieldResolver`, calls `resolveWithPreview`) | Low — true behavior test |
| `MetadataRouterBoundaryTest` | Mixed: 3 source-grep tests, 1 file-existence test | Low |
| `MetadataProductionBoundaryTest` | Source-grep (entrypoint symbol presence) | Medium — symbol-presence check does not verify wiring |
| `MetadataArchitectureBoundaryTest` | Source-grep (import lines only) | Medium — weaker than content scan |
| `ProfileBoundaryArchitectureTest` | Source-grep (function body substring + regex) | Medium — function body extractor is line-start sensitive |
| `AddonFirstPaintShapeArchitectureTest` | Source-grep (regex over homeFiles()) | Low — but scope limited to `ui/screens/home/` |
| `TraktGlobalContentCacheKeyTest` (F-C-06) | Source-grep (fixed-window substring scan) | **High** — 2500-char fixed window; layout-sensitive |
| `RailPreviewLifecycleArchitectureTest` | Mixed: source-grep, function-body extraction, file-existence | Medium |

---

## 7. Red-flag checklist

### "Architecture pin allowlist that grows"

**`NoIntegrationRuntimeInjectionOutsideBoundaryTest` allowlist — 6 packages:**

| Package | Justification | Assessment |
|---|---|---|
| `com.nexio.tv.core.anime` | Anime integration provider wraps IntegrationRuntime for special Kitsu routing | **Justified** — active usage verified |
| `com.nexio.tv.data.integration` | Primary integration layer — correct by design | **Justified** |
| `com.nexio.tv.core.integration` | Contains `IntegrationRuntime` itself | **Justified** |
| `com.nexio.tv.core.di` | Hilt module provides `IntegrationRuntime` | **Justified** |
| `com.nexio.tv.core.tmdb` | Pre-granted for anticipated adapter migration | **Stale — 0 actual uses** (see J-04) |
| `com.nexio.tv.core.tvdb` | Pre-granted for anticipated adapter migration | **Stale — 0 actual uses** (see J-04) |

**`NoRuntimeSpecOutsideIntegrationPackagesTest` allowlist — 6 packages (same + `data/local/integration`):**
Same assessment applies to `core/tmdb` and `core/tvdb`. The additional `data/local/integration` entry is verified in use.

**`IntegrationBoundaryTest` allowlist — 8 path suffixes:**

| Path | Justification | Assessment |
|---|---|---|
| `/com/nexio/tv/core/di/` | Hilt wires Retrofit clients | **Justified** |
| `/com/nexio/tv/core/tvdb/TvdbAuthService.kt` | Auth service references `TvdbApi` indirectly through `MetadataCredentialSource` only — does NOT import `TvdbApi` | **Questionable** — file does not import any raw API; carve-out may be stale |
| `/com/nexio/tv/data/integration/` | Integration layer — correct | **Justified** |
| `/com/nexio/tv/data/remote/api/` | API definitions themselves | **Justified** |
| `/com/nexio/tv/data/repository/KitsuAuthService.kt` | Imports `KitsuAuthApi` directly | **Justified** (but migration target) |
| `/com/nexio/tv/data/repository/RealDebridAuthService.kt` | Imports `retrofit2.Response` | **Justified** (but migration target) |
| `/com/nexio/tv/data/repository/SimklAuthService.kt` | Imports `retrofit2.Response` | **Justified** (but migration target) |

Note: The `TvdbAuthService.kt` entry in `IntegrationBoundaryTest` appears stale — source inspection shows it does not import any `TvdbApi` or Retrofit type from `data/remote/api/`. The carve-out was likely added prophylactically and may be safely removed.

**`IntegrationScopeGlobalDeprecatedNoCallersTest` allowlist — 2 relative paths:**

| Path | Justification | Assessment |
|---|---|---|
| `core/integration/ProfileBoundaryEnforcer.kt` | Back-compat `when` branch handles `IntegrationScope.Global` for existing persisted scopes | **Justified** — line 39 in enforcer |
| `core/integration/IntegrationScope.kt` | The value declaration itself | **Justified** |

**`DeprecatedAnnotationsHaveReplaceWithTest` allowlist — 3 relative paths:**

| Path | Justification | Assessment |
|---|---|---|
| `ui/components/NexioScrollDefaults.kt` | Forced override of deprecated `BringIntoViewSpec.scrollAnimationSpec` | **Justified** |
| `ui/screens/home/ModernHomeRows.kt` | Same | **Justified** |
| `ui/screens/home/ModernHomeContent.kt` | Same | **Justified** |

---

### "Deleted symbol still has zombie references"

All symbols deleted in F-J-02, F-F-03, F-F-05, F-B-01, F-B-02 were verified by grep of `app/src/` at SHA `774a540f8`:

| Symbol | `app/src/main/` hits | `app/src/test/` hits | Verdict |
|---|---|---|---|
| `ProfileMetadataOverlay` | 0 | 0 | Clean |
| `ProfileResolvedDisplayDocument` | 0 | 0 | Clean |
| `providerAccountId` (Account ctor arg) | 0 | 0 | Clean |
| `validateLegacyAccountScope` | 0 | 0 | Clean |
| `defaultMetadataRouterFacadeForManualConstruction` | 0 | 0 | Clean |
| `metadataRouterFacadeOrNull` | 0 | 0 | Clean |
| `providerLocalizedMetadataResolverOrNull` | 0 | 0 | Clean |
| `HomeDisplayMetadata.toResolvedDocument` (method call) | 0 | 1 (KDoc comment in test) | Clean |

The single test hit is a KDoc comment in `FieldResolverPreviewProvenanceTest.kt` describing the bug being guarded — not a production reference.

---

### "Dead test fixture references to deleted production code"

`CompositionTypeShapeTest.kt` was deleted in commit `431b94105` together with `ProfileMetadataOverlay` and `ProfileResolvedDisplayDocument`. No orphaned test fixtures referencing these types remain.

---

### "Legacy validator/path with no production caller but still loaded into Hilt"

`validateLegacyAccountScope` was deleted (d3c34336a). `ProfileBoundaryEnforcer` is a Kotlin `object` (not a Hilt-injected class) so there is no Hilt loading concern. No other legacy validator paths were identified as loaded into Hilt without callers.

---

### "Boundary test that uses source-grep instead of behavior"

See Section 6 classification table. The one high-fragility pin:

**`TraktGlobalContentCacheKeyTest` (F-C-06)** uses a fixed 2500-char window scan on function bodies. This is confirmed fragile (commit history shows that `Task 26` had to add a separator comment block to maintain text layout for the earlier version of this test). The test has no behavior fallback — it cannot detect a regression that routes through a renamed or inlined helper.

**`NoUnwrappedProviderCallsInsideIntegrationPackagesTest`** uses a custom brace-depth parser to check that raw API calls are inside runtime-owned lambdas. While more sophisticated than a substring scan, the parser approximates AST analysis and can be fooled by multi-line string literals or unusual formatting. Classified as Medium fragility.

Most other pins use simple regex/substring scans on stable symbols (type names, method names, annotation names) and are low-fragility because those names are unlikely to change without a deliberate refactor.

---

### "Architecture pin scope: which production directories scanned vs allowlisted"

**`NoIntegrationRuntimeInjectionOutsideBoundaryTest`** scans ALL production source roots (via `architectureScan` → `mainSourceFiles()`) and exempts 6 packages by package declaration. This is a whole-tree scan with package-level exemptions — broad coverage.

**`NoRuntimeSpecOutsideIntegrationPackagesTest`** same mechanism — whole-tree with 6-package exemptions. No path-based hole.

**`AddonFirstPaintShapeArchitectureTest.home hydration`** scans only `ui/screens/home/**` — **critically**, `getMetaFromAllAddons()` calls in `ui/screens/detail/`, `ui/screens/stream/`, `ui/screens/player/`, and `data/repository/` are NOT covered. This is both a cause of the current J-01 test failure (the test passes on files that don't have the issue — wait, the test IS failing because the home files DO have calls) and a structural gap where non-Home callers are unguarded.

**`MetadataArchitectureBoundaryTest`** scans only `ui/`, `workers/`, and a `data/repository/ContinueWatching` prefix — does not cover `data/integration/` or `core/` packages.

**`core/tmdb` and `core/tvdb` on allowlists** — see J-04 above. At SHA `774a540f8` neither package contains any reference to `IntegrationRuntime` or `IntegrationSpec`. The allowlist grants future rights without current justification.

---

### "ResolvedMetadataDocument construction not pinned"

Confirmed unresolved (J-03). `ResolvedMetadataDocument(...)` is constructed:
- `FieldResolver.kt:250` — correct; this is the authorized construction site.
- `MetadataRouterFacade.kt:69` — correct; the no-data PREVIEW fallback, explicitly documented.

No pin prevents a third construction site from appearing elsewhere. A pin modeled on `FieldResolverInjectionContractTest` but scanning for `ResolvedMetadataDocument(` and allowing only `FieldResolver.kt` and `MetadataRouterFacade.kt` would close this gap.

---

### "MetadataRouter.route() callers outside MetadataRouterFacade"

At SHA `774a540f8`, `router.route(...)` is called only at `MetadataRouterFacade.kt:43` and `MetadataRouterFacade.kt:415`. No other production file calls it. However, there is no dedicated architecture pin for this constraint — it relies on the broader `MetadataRouterBoundaryTest` which checks that legacy router types are not referenced outside allowed files, but does not specifically scan for `.route(` call sites. The coverage is indirect.

---

### "ProviderPlanRunner construction outside Hilt"

`FieldResolverInjectionContractTest` (F-B-02) covers both `FieldResolver()` (no-arg) and `ProviderPlanRunner(emptySet())`. The allowlist is empty — no exemptions. At SHA `774a540f8`, no production file constructs either type directly. `MetaDetailsViewModel` imports `ProviderPlanRunner` but the `@Inject constructor` makes it Hilt-supplied; the import is for type annotation only, not direct construction. The pin is effective.

---

### "F-J-01 meta-finding: architecture tests didn't catch facade-bypass"

**Status at SHA `774a540f8`: partially closed, one gap remains active.**

The facade-bypass patterns identified in Lanes B, C, and H were:
1. `HomeProviderLocalizedMetadataOverlay` sidecar helpers (`fun MetadataRouterFacade.resolveHomeRequest`, `fun HomeViewModel.resolveHomeRequestIfAvailable`) — **CLOSED**: `RailPreviewLifecycleArchitectureTest.home provider overlay does not retain router facade sidecar helpers` scans `HomeProviderLocalizedMetadataOverlay.kt` for these tokens.
2. `HomeDisplayMetadata.toResolvedDocument()` PREVIEW bypass with empty `fieldOwners` — **CLOSED**: `FieldResolverPreviewProvenanceTest` (behavior test) and the method no longer exists.
3. Direct `getMetaFromAllAddons()` from Home — **OPEN AND FAILING**: J-01 confirms the test is failing because two Home files still call `getMetaFromAllAddons()`. The test was added but the production fix was not made.
4. Direct construction of `FieldResolver()` in `MetaDetailsViewModel` — **CLOSED**: F-B-02 pin + production deletion.
5. Trailer fetch bypassing facade — **CLOSED**: covered by `MetadataRouterFacadeFetchTmdbEnrichmentTest` (cluster-A migration task pins).
6. `StreamScreenViewModel`, `PlayerRuntimeControllerStreams`, and `IdleScreensaverPreparation` calling `getMetaFromAllAddons()` — **OPEN, NO PIN**: these files are outside `ui/screens/home/` and are not covered by any architecture test.

The F-J-01 gap is partially closed but meaningfully narrowed. The remaining structural hole (facade-bypass outside the Home directory) is larger than it appears from the test suite alone.

---

### "Auth services not under integration boundary"

`KitsuAuthService`, `RealDebridAuthService`, and `SimklAuthService` live in `data/repository/` rather than `data/integration/`. They each have explicit carve-outs in `IntegrationBoundaryTest` (for Retrofit API usage). Investigation at SHA:

- `KitsuAuthService` directly holds a `KitsuAuthApi` (Retrofit interface) reference and calls it. It has no Hilt integration wrapper.
- `RealDebridAuthService` delegates all network calls to `RealDebridAuthIntegrationProvider`; its raw Retrofit import is `retrofit2.Response` for response types only.
- `SimklAuthService` delegates to `SimklAuthIntegrationProvider`; same pattern.

`NoDirectAuthProviderNetworkOwnersTest` explicitly checks that `RealDebridAuthService` and `SimklAuthService` do not directly call `realDebridApi.` or `simklApi.` — this test passes, confirming the delegation pattern is enforced.

`KitsuAuthService` retains a direct `KitsuAuthApi` call path (no integration provider wrapping its auth calls), which is the most architecturally inconsistent of the three. A migration to `KitsuAuthIntegrationProvider` (parallel to the `SimklAuthIntegrationProvider` and `RealDebridAuthIntegrationProvider` wrappers) would close this carve-out and eliminate the lone `KitsuAuthApi` exemption in `NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest`.

No migration plan or timeline exists in the current branch.

---

## Summary of findings

| ID | Severity | Description |
|---|---|---|
| J-01 | P1 | `getMetaFromAllAddons()` called from two Home files; architecture pin is failing at SHA `774a540f8` |
| J-02 | P1 | `checkin()` callers never supply `ownerProfileId`; no architecture pin; profile context is derived implicitly inside service |
| J-03 | P1 | `ResolvedMetadataDocument` direct construction is not pinned — any file could construct it and bypass field-resolution provenance |
| J-04 | P2 | `core/tmdb` and `core/tvdb` on two allowlists with zero actual `IntegrationRuntime`/`IntegrationSpec` uses — pre-granted exemptions without current justification |
| J-05 | P2 | Three auth-service files (`KitsuAuthService`, `RealDebridAuthService`, `SimklAuthService`) outside integration boundary with no migration plan; `KitsuAuthService` retains direct Retrofit API calls |
| J-06 | P2 | F-C-06 pin (`TraktGlobalContentCacheKeyTest`) is a fixed-window source-grep proxy — layout-sensitive, cannot detect helper-inlining regressions |
| J-07 | P2 | F-J-01 partially closed: `getMetaFromAllAddons()` callers outside `ui/screens/home/` (Stream, Player, Screensaver) have no facade-bypass pin |
| J-08 | Nit | `MetadataArchitectureBoundaryTest` (import scan) duplicates `MetadataProductionBoundaryTest` (content scan) with weaker coverage and overlapping scope |
