---
phase: 04-sync-and-cleanup
plan: 05
subsystem: sync
tags: [android, kotlin, datastore, profile-settings, account-sync]

requires:
  - phase: 04-sync-and-cleanup
    plan: 04
    provides: Full-snapshot profile settings blob imports and pull-before-observe hydration
provides:
  - Profile settings blob sync uses the real layout_settings DataStore feature
  - Regression coverage rejects the unused layout_preferences feature
  - Shared v7 account sync no longer observes, exports, or applies layout catalog-order values
affects: [phase-04-sync-and-cleanup, profile-settings-sync, account-settings-sync]

tech-stack:
  added: []
  patterns:
    - Per-profile layout settings flow through ProfileSettingsSyncService v8 blob sync only
    - Shared v7 account sync represents moved per-profile catalog fields as empty payload lists

key-files:
  created:
    - .planning/phases/04-sync-and-cleanup/04-05-SUMMARY.md
  modified:
    - app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt
    - app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt
    - app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt

key-decisions:
  - "Use layout_settings, not layout_preferences, as the v8 profile settings feature name because LayoutPreferenceDataStore persists to layout_settings."
  - "Neutralize v7 layout/catalog-order fields with emptyFlow and emptyList instead of deleting the v7 contract fields."
  - "Leave the dead legacy AccountSettingsPayload apply path untouched because it was outside this gap closure."

patterns-established:
  - "v7 shared sync moved fields keep explicit comments at each observer, payload, and apply site."
  - "Profile settings tests verify both the positive real DataStore feature and the negative unused feature."

requirements-completed: [SYNC-02]

duration: 5m 20s
completed: 2026-04-14
---

# Phase 04 Plan 05: Layout Settings Sync Gap Closure Summary

**Layout catalog-order settings now sync through the real v8 per-profile layout_settings blob instead of the shared v7 account contract**

## Performance

- **Duration:** 5m 20s
- **Started:** 2026-04-14T17:28:35Z
- **Completed:** 2026-04-14T17:33:55Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- Changed `ProfileSettingsSyncService.syncedFeatures` from `layout_preferences` to the real `layout_settings` feature used by `LayoutPreferenceDataStore`.
- Added regressions that expect `layout_settings`, reject `layout_preferences`, and prove imports write to `ProfileDataStoreFactory.get(2, "layout_settings")` while leaving `layout_preferences` empty.
- Removed active v7 layout/catalog-order observer inputs, payload reads, and apply writes from `AccountSettingsSyncService`.

## Task Commits

1. **Task 1 RED: Layout settings sync regressions** - `3d814e8a8` (test)
2. **Task 1 GREEN: v8 layout_settings allowlist** - `cdf43e22c` (fix)
3. **Task 2: Remove layout settings from v7 account sync** - `1e8a68bef` (fix)

**Plan metadata:** summary committed separately as the docs completion commit.

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt` - Uses `layout_settings` in the v8 settings blob feature allowlist.
- `app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt` - Adds positive and negative regressions for real layout settings imports.
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` - Neutralizes layout/catalog-order fields in the shared v7 sync observer, payload builder, and apply path.
- `.planning/phases/04-sync-and-cleanup/04-05-SUMMARY.md` - Execution summary.

## Decisions Made

- `layout_preferences` was not retained as a compatibility bridge because the verifier gap was caused by syncing that unused parallel store.
- v7 layout/catalog-order fields remain present in the schema payload as empty lists so the existing shared account contract stays stable while ownership moves to v8.
- Shared planning files were intentionally not updated because the orchestrator owns `STATE.md` and `ROADMAP.md` for this run.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.ProfileSettingsSyncServiceTest"` remains blocked by unrelated unit-test source-set compile drift. The first unrelated failing path is `app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt`; additional stale constructor failures appear in `PlayerSettingsDataStoreSpoolModeTest.kt`, `PlayerSettingsDataStoreTest.kt`, and other unrelated tests. `ProfileSettingsSyncServiceTest.kt` was not reported as a compile failure source.
- Kotlin daemon startup still rejects `ZGenerational`; Gradle falls back to non-daemon compilation and the production build completes.
- A transient stale `.git/index.lock` blocked one commit attempt; no git process was active and the lock disappeared before retry. No files were lost or reverted.

## Verification

- `ACTUAL_BASE=$(git merge-base HEAD 0c41ff962b0fd9bd6c58c9e199443a7e7a39ad8b)` confirmed the starting HEAD descended from `0c41ff962b0fd9bd6c58c9e199443a7e7a39ad8b`.
- `./gradlew assembleArm64Debug` - passed after Task 1 changes.
- `./gradlew assembleArm64Debug` - passed after Task 2 changes with `BUILD SUCCESSFUL in 1m 2s`.
- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.ProfileSettingsSyncServiceTest"` - blocked by unrelated source-set compile drift as described above.
- `rg -n "layout_preferences|layout_settings" ...` - shows production v8 sync and `LayoutPreferenceDataStore` use `layout_settings`; `layout_preferences` appears only in the negative test assertion and unused-store regression.
- Negative v7 path check passed: no active `layoutPreferenceDataStore` observer assignments, payload reads, or `settings.catalogs.home` apply writes remain in `AccountSettingsSyncService.kt`.

## Known Stubs

None. Stub-pattern scan hits were deliberate v7 empty payload lists required by this plan and existing nullable internal state fields, not placeholders.

## Threat Flags

None. The only trust-boundary changes were the planned mitigations T-04-19 and T-04-20: real v8 feature routing and removal of shared v7 layout/catalog-order flow.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

The remaining SYNC-02 layout/catalog-order routing gap is closed from a production build and source-level verification standpoint. Full targeted unit-test execution still depends on repairing unrelated stale unit-test source-set constructors.

## Self-Check: PASSED

- Found `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt`.
- Found `app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt`.
- Found `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`.
- Found `.planning/phases/04-sync-and-cleanup/04-05-SUMMARY.md`.
- Found task commits `3d814e8a8`, `cdf43e22c`, and `1e8a68bef`.

---
*Phase: 04-sync-and-cleanup*
*Completed: 2026-04-14*
