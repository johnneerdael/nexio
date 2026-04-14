---
phase: 02-per-profile-auth-and-settings
plan: "04"
subsystem: profile-sync-and-settings-gating
tags: [profile, sync, settings-ui, security, shared-flow]
dependency_graph:
  requires: [02-01, 02-02]
  provides: [profileSwitched-event, sync-push-suppression, isPrimaryProfile-gating]
  affects: [AccountSettingsSyncService, ProfileManager, TraktViewModel, SimklViewModel, SettingsScreen]
tech_stack:
  added: []
  patterns:
    - "profileSwitched SharedFlow emitted from setActiveProfile for downstream observers"
    - "@Volatile recentlySwitchedProfile flag with 2-second window to suppress spurious push after profile switch"
    - "isPrimaryProfile StateFlow derived via activeProfileId.map { it == 1 }.stateIn(Eagerly)"
    - "SettingsProfileViewModel injected via hiltViewModel() for composable-level profile gating"
    - "LaunchedEffect(isPrimaryProfile, selectedSection) safety redirect from shared sections"
key_files:
  created: []
  modified:
    - app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt
    - app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt
    - app/src/main/java/com/nexio/tv/ui/screens/settings/TraktViewModel.kt
    - app/src/main/java/com/nexio/tv/ui/screens/settings/SimklViewModel.kt
    - app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt
    - app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt
decisions:
  - "Placed recentlySwitchedProfile guard on both collect block and schedulePush() for defence-in-depth against cross-profile push (T-02-11)"
  - "SettingsProfileViewModel added inline in SettingsScreen.kt (not a separate file) — small enough to keep collocated"
  - "hubEntryFocusRequester moved to Trakt entry for non-primary profiles since Debrid is hidden — preserves D-pad focus entry point"
  - "Shared sections set built with remember {} to avoid reallocation on recomposition"
metrics:
  duration_minutes: 45
  completed_date: "2026-04-14"
  tasks_completed: 2
  tasks_total: 2
  files_changed: 6
requirements_fulfilled: [AUTH-05]
---

# Phase 02 Plan 04: Cross-cutting Profile Behaviors Summary

Profile-switch push-suppression guard, profileSwitched SharedFlow event, and isPrimaryProfile-gated Integration Hub composable gating using a 2-second @Volatile flag, SharedFlow emission on setActiveProfile, and SettingsProfileViewModel driving LazyColumn visibility.

## What Was Built

### Task 1: ProfileManager profileSwitched + AccountSettingsSyncService push guard

**ProfileManager.kt:**
- Added `MutableSharedFlow<Unit>(extraBufferCapacity = 1)` as `_profileSwitched`
- Exposed as `val profileSwitched: SharedFlow<Unit> = _profileSwitched.asSharedFlow()`
- `_profileSwitched.emit(Unit)` called after `dataStore.setActiveProfile(id)` in `setActiveProfile`
- Fulfills Phase 2 half of D-04 (Phase 3 will wire player observation)

**AccountSettingsSyncService.kt:**
- Added `private val profileManager: ProfileManager` constructor injection
- Added `@Volatile private var recentlySwitchedProfile = false`
- `observeProfileSwitches()` called from `init`: watches `profileManager.activeProfileId.drop(1)`, sets flag true, delays 2000ms, sets false
- Both `collect` block and `schedulePush()` guard updated to `isApplyingRemote || recentlySwitchedProfile`
- Prevents Pitfall 3: flatMapLatest re-emissions from 6+ migrated stores after profile switch are no longer interpreted as settings mutations

**ProfileManagerTest.kt:**
- Added `profileSwitched emits on setActiveProfile` test verifying at least one emission when profile is switched to a valid non-current profile

### Task 2: isPrimaryProfile ViewModels + Integration Hub gating

**TraktViewModel.kt:**
- Added `private val profileManager: ProfileManager` to constructor
- Added `val isPrimaryProfile: StateFlow<Boolean> = profileManager.activeProfileId.map { it == 1 }.stateIn(viewModelScope, SharingStarted.Eagerly, true)`

**SimklViewModel.kt:**
- Same pattern as TraktViewModel

**SettingsScreen.kt:**
- Added `SettingsProfileViewModel` (HiltViewModel) with `isPrimaryProfile: StateFlow<Boolean>` — same derivation pattern
- `SettingsScreen` composable collects via `collectAsStateWithLifecycle()`
- `IntegrationSettingsContent` receives new `isPrimaryProfile: Boolean` parameter
- Safety `LaunchedEffect(isPrimaryProfile, selectedSection)` redirects non-primary profiles to Hub if on a shared section
- Hub LazyColumn: Debrid wrapped in `if (isPrimaryProfile)`, all shared entries (TheIntroDb, Tmdb, Omdb, Imdb, MdbList, AnimeSkip, SubtitleTranslation, YouTubeTrailerLogin, PosterRatings) also wrapped
- Trakt and Simkl entries always visible
- `hubEntryFocusRequester` applied to Trakt entry conditionally when `!isPrimaryProfile` (Debrid hidden, Trakt becomes first focusable)

## Deviations from Plan

None - plan executed exactly as written.

## Known Stubs

None — all wiring is live. `isPrimaryProfile` derives from `ProfileManager.activeProfileId` which reflects the real active profile. No mock/stub data flows to UI.

## Threat Surface Scan

No new network endpoints, auth paths, file access patterns, or schema changes introduced. All changes are read-only derivations from existing `activeProfileId` StateFlow or coroutine guards on existing sync path.

## Self-Check: PASSED

Files exist:
- app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt: FOUND (contains profileSwitched, _profileSwitched.emit)
- app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt: FOUND (contains recentlySwitchedProfile, isApplyingRemote || recentlySwitchedProfile)
- app/src/main/java/com/nexio/tv/ui/screens/settings/TraktViewModel.kt: FOUND (contains isPrimaryProfile StateFlow)
- app/src/main/java/com/nexio/tv/ui/screens/settings/SimklViewModel.kt: FOUND (contains isPrimaryProfile StateFlow)
- app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt: FOUND (contains SettingsProfileViewModel, isPrimaryProfile gating)
- app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt: FOUND (contains profileSwitched test)

Commits exist:
- 85387d787: feat(02-04): add profileSwitched SharedFlow and profile-switch push-suppression guard - FOUND
- 75efc6a3d: feat(02-04): add isPrimaryProfile to ViewModels and gate shared integration sections - FOUND

Build notes: Full `assembleArm64Debug` fails on pre-existing player module errors (DolbyVisionAutoPlayGate, Dv5HardwareToneMapRpuTap, MatroskaDolbyVisionHookInstaller, BuiltInSubtitleCueTranslator) that exist identically on the base commit (10ec20a5e). Zero errors in any of our modified files.
