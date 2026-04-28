# Lane J — Legacy Deletion / Boundary Enforcement

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Phase:** 5
- **Owner task:** Task 34

## What changed

This branch consolidated the integration runtime control plane and migrated metadata callers onto `MetadataRouterFacade`/`IntegrationRuntime` while introducing 23 architecture tests under `app/src/test/java/com/nexio/tv/architecture/` to lock the new boundaries (notably `MetadataRouterBoundaryTest`, `MetadataProductionBoundaryTest`, `NoLegacyProviderFallbacksTest`, `ProfileBoundaryArchitectureTest`, `IntegrationBoundaryTest`, `NoIntegrationRuntimeInjectionOutsideBoundaryTest`, `NoUnwrappedProviderCallsInsideIntegrationPackagesTest`, `NoRuntimeSpecOutsideIntegrationPackagesTest`, `NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest`, `NoRawProviderInjectionTest`, `NoDirectOkHttpOutsideRuntimeTransportPackagesTest`, `NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest`, `NoDirectAuthProviderNetworkOwnersTest`, `NoBlockingRailOwnershipSyncTest`, `RailOwnershipLifecycleTest`, `IntegrationRuntimeAuditArtifactTest`, `IntegrationRuntimeHeaderPolicyResolutionTest`, `IntegrationProviderContractConformanceTest`, `IntegrationProviderContractRegistryTest`, `IntegrationProviderProvenanceCompletenessTest`, `IntegrationBestPracticeRuleConformanceTest`, `MetadataRouterReadinessAuditTest`). Several legacy code paths remain alive: dedicated metadata services (`TmdbMetadataService`, `KitsuMetadataService`, `TvdbMetadataService`) are explicitly whitelisted in the boundary tests, and `IntegrationScope.Global` plus `Account(providerAccountId)`/`validateLegacyAccountScope` are still in production.

## Architecture test inventory

| Test file | What it bans / verifies |
|---|---|
| `MetadataRouterBoundaryTest.kt` | Bans `TvMetadataRouter`, `ProviderMetadataRouter`, `*MetadataService` references in production except whitelisted adapter/service/router files; bans raw provider/network types inside `core/metadata/router/`; asserts `MetadataRouterFacade` exists; bans `MetadataDepth.PREVIEW` in home UI. |
| `MetadataProductionBoundaryTest.kt` | Bans legacy provider execution imports inside metadata UI repository paths. |
| `MetadataRouterReadinessAuditTest.kt` | Asserts every `MetadataRouter` active endpoint shape is runtime-covered and that cache policy audit has no `hashCode` credential evidence. |
| `NoLegacyProviderFallbacksTest.kt` | Bans `executeAuthorizedRequest(`, `passThroughRuntime(`, `tmdb/tvdbPassThroughRuntime(`, `object : IntegrationRuntime`, and helper variants; only allows `core/di/`. |
| `IntegrationBoundaryTest.kt` | Bans non-integration packages from referencing provider Retrofit APIs across the full tree. |
| `NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest.kt` | Confirms provider APIs are only referenced from integration packages. |
| `NoRawProviderInjectionTest.kt` | Bans Retrofit/OkHttp injection in feature packages. |
| `NoDirectOkHttpOutsideRuntimeTransportPackagesTest.kt` | Bans raw `Request.Builder`, `.newCall(`, `.openConnection()` outside runtime transport/DI. |
| `NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt` | Bans feature/presentation packages from injecting `IntegrationRuntime` directly. |
| `NoUnwrappedProviderCallsInsideIntegrationPackagesTest.kt` | Requires raw `*Api.<call>(` inside integration packages to be near a runtime spec. |
| `NoRuntimeSpecOutsideIntegrationPackagesTest.kt` | Restricts `IntegrationCallSpec`/`IntegrationStreamSpec` creation to integration/approved packages. |
| `NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest.kt` | Bans non-adapter code from depending on auth services. |
| `NoDirectAuthProviderNetworkOwnersTest.kt` | Bans repository auth services from owning raw provider network calls. |
| `NoBlockingRailOwnershipSyncTest.kt` | Bans snapshot stores from owning rail sync authority. |
| `RailOwnershipLifecycleTest.kt` | Asserts legacy snapshot ownership paths are retired in favor of rail store. |
| `ProfileBoundaryArchitectureTest.kt` | Asserts metadata providers use `Global*Content` scopes (not `Profile`); image cache keys always pin English language. |
| `IntegrationRuntimeAuditArtifactTest.kt` | Audit generator lives in build logic, not app build script; provenance resolved only inside audited checkout. |
| `IntegrationRuntimeHeaderPolicyResolutionTest.kt` | Runtime specs do not rely on scanner fallback for header policy; poster providers declare endpoint-specific header policies. |
| `IntegrationProviderContractConformanceTest.kt` | API shape, header policy, and cache contract IDs match the contract registry. |
| `IntegrationProviderContractRegistryTest.kt` | Contract registry exists with required sections and provenance for reviewed providers. |
| `IntegrationProviderProvenanceCompletenessTest.kt` | Every `IntegrationProvider` has provenance / lifecycle classification; contract source paths are repo-relative. |
| `IntegrationBestPracticeRuleConformanceTest.kt` | Best-practice rules listed in registry are executable or explicitly declaration-only. |
| `ArchitectureScan.kt` | Shared `productionRegexScan` / `sourceTextScan` helpers (not a test). |

## @Deprecated markers added on this branch

| Marker | File:line | Has ReplaceWith / TODO? | Verdict |
|---|---|---|---|
| `IntegrationScope.Global` data object | `core/integration/IntegrationScope.kt:36` | Message names replacements (`GlobalContent`, `GlobalLocalizedContent`, `GlobalEnglishImage`); no `ReplaceWith`, no removal date | warn |
| `IntegrationScope.Account(providerAccountId)` ctor | `core/integration/IntegrationScope.kt:86` | `level=ERROR`, message names alternatives; no `ReplaceWith`, no removal date | warn (stronger because ERROR-level blocks reuse) |
| `TraktMutationOutboxCoordinator` typealias | `data/trakt/outbox/TraktMutationOutboxCoordinator.kt` | Has `ReplaceWith("ProviderMutationOutboxCoordinator")` | ok |
| `TrackingProgressService.observeContinueWatchingNextUp()` | `data/repository/TrackingProgressService.kt` | Documents replacement (`ContinueWatchingSnapshotService.observeContinueWatching(profileId)`); no `ReplaceWith`, no removal date | ok (documented but no removal commitment) |
| `BringIntoViewSpec.scrollAnimationSpec` overrides (3 sites) | `ui/screens/home/ModernHomeContent.kt`, `ui/screens/home/ModernHomeRows.kt`, `ui/components/NexioScrollDefaults.kt` | Compose framework deprecation passthrough (override of an upstream deprecated member) | ok (out of scope: framework-driven) |

## Live use of @Deprecated members in production

- `IntegrationScope.Global` is still constructed by:
  - `app/src/main/java/com/nexio/tv/data/integration/playback/OpenSubtitlesHashIntegrationProvider.kt:44`
  - `app/src/main/java/com/nexio/tv/core/integration/ProfileBoundaryEnforcer.kt:39, 301`
- `validateLegacyAccountScope` and `validateLegacyProfileScope` remain wired into `ProfileBoundaryEnforcer.kt:48, 198, 214, 263`. The `Account(providerAccountId)` ERROR-level ctor is gone from callers, but the legacy-name validators and the ctor itself remain as dead-but-tolerated surface area (cross-references F-F-05).

## Contract verdicts

| Contract | Verdict | Evidence |
|---|---|---|
| No production caller bypasses `MetadataRouterFacade` / `IntegrationRuntime` | fail — see Lane B/C/H findings F-03-02, F-04-03, F-05-02..04, F-12-02; the dedicated services (`TmdbMetadataService`, `KitsuMetadataService`, `TvdbMetadataService`, `EpisodeRatingsSelectionRepository`, `TrailerService`, `MetadataSecondaryRepository`) are explicitly *whitelisted* by `MetadataRouterBoundaryTest.kt:21-33` rather than retired. | `app/src/test/java/com/nexio/tv/architecture/MetadataRouterBoundaryTest.kt:21-33` |
| Architecture tests ban legacy code paths | partial — tests do ban `passThroughRuntime`, raw provider APIs outside integration packages, raw OkHttp/Retrofit injection, and direct `*MetadataService` calls *unless on the whitelist*. They do NOT detect the facade-bypass paths because the bypass entrypoints are themselves on the allowlist. Coverage gap. | `MetadataRouterBoundaryTest.kt`, `NoLegacyProviderFallbacksTest.kt` |
| `@Deprecated` markers carry `ReplaceWith` or removal TODO | partial — typealias has `ReplaceWith`; `IntegrationScope.Global` and `Account(providerAccountId)` only have prose messages, no `ReplaceWith`, no removal commitment, and `Global` still has live callers. | `IntegrationScope.kt:36, 86`, deprecated-markers table above |

## Findings

- **F-J-01 (P2 — coverage gap):** Architecture tests would not have caught the facade-bypass findings reported in Lanes B/C/H. `MetadataRouterBoundaryTest.kt:21-33` whitelists `EpisodeRatingsSelectionRepository.kt`, `TrailerService.kt`, `MetadataSecondaryRepository.kt`, and the three legacy `*MetadataService.kt` files, so the boundary test cannot fail when callers reach those services directly. Either retire those services or replace the path-suffix whitelist with a per-symbol allowlist scoped to `core/metadata/router/`.
- **F-J-02 (Nit — deletion candidate):** `IntegrationScope.Account(providerAccountId)` (`IntegrationScope.kt:86-100`) is `level = ERROR` with no remaining callers in `app/src/main/`. Delete the ctor and the matching `validateLegacyAccountScope` (`ProfileBoundaryEnforcer.kt:263`) plus the `providerAccountId` field/equals/hashCode/toString tail. Cross-references F-F-05.
- **F-J-03 (P2):** `IntegrationScope.Global` is `@Deprecated` but still constructed by `OpenSubtitlesHashIntegrationProvider.kt:44` and referenced by `ProfileBoundaryEnforcer.kt:39, 301`. Either migrate `OpenSubtitlesHashIntegrationProvider` to one of the explicit `Global*` variants and drop `Global`, or remove the deprecation and document its remaining purpose.
- **F-J-04 (Nit):** `@Deprecated("Use GlobalContent…")` on `IntegrationScope.Global` lacks `ReplaceWith` and a removal date / follow-up issue ref. Same gap on the `Account(providerAccountId)` ctor and on `TrackingProgressService.observeContinueWatchingNextUp()`. Add either `ReplaceWith` (where mechanical migration is possible) or a `// TODO(<issue>): remove after <date>` follow-up.

## Cross-references

- F-03-02, F-04-03, F-05-02..04, F-12-02 (facade-bypass via dedicated services and repositories) — confirmed legacy paths whitelisted by `MetadataRouterBoundaryTest.kt`.
- F-F-05 (Lane F): `validateLegacyAccountScope` + `Account(providerAccountId)` deletion candidate — confirmed here as F-J-02.
- F-A-01 (Lane A): 429/5xx backoff gap — touches Lane J because architecture tests do not cover backoff parity.

## Outcome

CHANGES_REQUESTED — architecture tests lock the new shape but do not block the documented bypass paths, and three live `@Deprecated` surfaces still need either deletion or a removal commitment.
