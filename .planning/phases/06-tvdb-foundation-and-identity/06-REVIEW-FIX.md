---
phase: 06-tvdb-foundation-and-identity
fixed_at: 2026-04-15T03:09:54Z
review_path: .planning/phases/06-tvdb-foundation-and-identity/06-REVIEW.md
iteration: 1
findings_in_scope: 3
fixed: 3
skipped: 0
status: all_fixed
---

# Phase 6: Code Review Fix Report

**Fixed at:** 2026-04-15T03:09:54Z
**Source review:** .planning/phases/06-tvdb-foundation-and-identity/06-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 3
- Fixed: 3
- Skipped: 0

## Fixed Issues

### WR-01: Transient TVDB Auth Failures Mark Credentials Invalid

**Status:** fixed: requires human verification
**Files modified:** `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt`, `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModel.kt`, `app/src/test/java/com/nexio/tv/core/tvdb/TvdbAuthServiceTest.kt`, `app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModelTest.kt`
**Commit:** 0cb05cd20
**Applied fix:** Added `TvdbAuthResult.AuthUnavailable` for non-401 HTTP failures, missing auth data, and IO exceptions; preserved token state on transient failures; saved `FALLBACK_ACTIVE` instead of `INVALID`; added result-aware validation for the settings save path so transient failures are not rewritten as invalid credentials; added coverage for HTTP 500 and `IOException` fallback handling.

### WR-02: Settings Observation Disables Fallback-Active TVDB

**Status:** fixed: requires human verification
**Files modified:** `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModel.kt`, `app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModelTest.kt`
**Commit:** eeaa21391
**Applied fix:** Changed settings observation to force-disable only unconfigured or invalid enabled settings, preserving enabled/configured `FALLBACK_ACTIVE` state; added ViewModel coverage asserting fallback-active settings are observed without `setEnabled(false)`.

### WR-03: Sync Schema Rejects Formatter Payloads With Badge Rows

**Status:** fixed
**Files modified:** `docs/settings/settings-sync.schema.json`, `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`
**Commit:** dddc10c2b
**Applied fix:** Added `badgeRowTemplate` to the custom formatter schema required fields and properties; extended the account config sync contract test to assert emitted custom formatter keys are allowed by the schema while `additionalProperties` remains false.

## Skipped Issues

None.

## Verification

- `node -e "JSON.parse(require('fs').readFileSync('docs/settings/settings-sync.schema.json','utf8'))"`: passed.
- `./gradlew assembleArm64Debug`: passed after WR-01, then blocked in the final pass by unrelated dirty/untracked Android TV search work under `app/src/main/java/com/nexio/tv/core/search/`.
- Final `./gradlew assembleArm64Debug` blocker: `:app:hiltJavaCompileArm64Debug` fails because `AndroidTvSearchSuggestionCache` has multiple `@Inject` constructors, `AndroidTvNativeSearchService(..., timeoutMs)` requests an unqualified `Long` binding, and `AndroidTvSearchProvider.ProviderEntryPoint.suggestionCache()` requests `AndroidTvSearchSuggestionCache` without a valid binding.
- Targeted unit tests attempted with `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.tvdb.TvdbAuthServiceTest --tests com.nexio.tv.ui.screens.settings.TvdbSettingsViewModelTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest`: blocked by the same unrelated Hilt errors before selected tests could run.
- Earlier targeted unit-test attempt also hit unrelated global unit-test source compilation errors in dirty test work, including old constructor calls in `PlayerSettingsDataStoreTest`, `PlayerSettingsDataStoreSpoolModeTest`, `SearchHistoryDataStoreTest`, `SearchViewModelHistoryTest`, `ThemeDataStoreProfileTest`, `CatalogSelectionPersistenceTest`, `SimklViewModelTest`, and `TraktViewModelPriorityHydrationTest`.

---

_Fixed: 2026-04-15T03:09:54Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
