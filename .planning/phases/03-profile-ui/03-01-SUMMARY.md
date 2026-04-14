---
phase: 03-profile-ui
plan: "01"
subsystem: profile-ui
tags: [profile, composable, navigation, session-gating, d-pad, focus]
dependency_graph:
  requires: [02-per-profile-auth-and-settings]
  provides: [ProfileAvatarCircle, ProfileSelectionScreen, ProfileSelectionViewModel, ProfileSelection-route, session-gating]
  affects: [MainActivity, Screen.kt]
tech_stack:
  added: []
  patterns: [hiltViewModel, StateFlow-collectAsState, animateFloatAsState, FocusRequester-chain, remember-session-flag]
key_files:
  created:
    - app/src/main/java/com/nexio/tv/ui/components/ProfileAvatarCircle.kt
    - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt
    - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModel.kt
  modified:
    - app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt
    - app/src/main/java/com/nexio/tv/MainActivity.kt
decisions:
  - "Use remember (not rememberSaveable) for hasSelectedProfileThisSession — strict once-per-process semantics, no persistence across process death"
  - "Capture LocalContentFocusRequester.current at composable scope before LaunchedEffect — CompositionLocals cannot be read inside coroutines"
  - "profilePinEnabled derived from UserProfile.pinEnabled field — no Supabase call in Phase 3 (Pitfall 5)"
metrics:
  duration_minutes: 42
  completed_date: "2026-04-14"
  tasks_completed: 2
  files_changed: 5
---

# Phase 03 Plan 01: Profile Selection UI Summary

**One-liner:** Profile selection screen with D-pad focus navigation, 1.15x scale animation, lock badges, and once-per-session gating in MainActivity.

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | Create ProfileAvatarCircle, ProfileSelectionViewModel, Screen route | 4e0a4e010 | ProfileAvatarCircle.kt, ProfileSelectionViewModel.kt, Screen.kt |
| 2 | Create ProfileSelectionScreen and wire session gating | 0e8518acb | ProfileSelectionScreen.kt, MainActivity.kt |

## What Was Built

### ProfileAvatarCircle.kt
Shared composable rendering a colored circle with an uppercase initial letter as fallback and Coil `AsyncImage` when `avatarImageUrl` is provided. Color parsed via `android.graphics.Color.parseColor()` wrapped in `runCatching` with Ocean blue (`#1E88E5`) fallback. Size-proportional font at `size * 0.4f`. Ported from NuvioTV with package changed to `com.nexio.tv.ui.components`.

### ProfileSelectionViewModel.kt
Hilt ViewModel exposing:
- `profiles: StateFlow<List<UserProfile>>` from `ProfileManager`
- `activeProfileId: StateFlow<Int>` from `ProfileManager`
- `profilePinEnabled: StateFlow<Map<Int, Boolean>>` derived via `map { list -> list.associate { it.id to it.pinEnabled } }` — no Supabase call, uses local `pinEnabled` field per Pitfall 5
- `selectProfile(profileId: Int)` launches `profileManager.setActiveProfile(id)` in `viewModelScope`

### ProfileSelectionScreen.kt
Full-screen profile picker. Selection-mode only — no Add/Edit/Delete management (per D-04). Layout:
- `Box(fillMaxSize, Background)` centered `Column`
- "Who's watching?" heading (`headlineLarge`, `SemiBold`, `TextPrimary`)
- 48dp spacer
- Horizontal `Row(spacedBy = 24.dp)` of `ProfileCard` composables

`ProfileCard` (private) renders:
- `ProfileAvatarCircle(size = 96.dp)` with `graphicsLayer { scaleX/Y = itemScale }` where `itemScale = 1f + (0.15f * focusProgress)`
- `focusProgress` via `animateFloatAsState(tween(210, CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))`
- 2dp `FocusRing` border with `alpha = focusProgress` on focus
- Lock badge: 20dp `CircleShape` `BackgroundElevated` box at `BottomEnd` with `Icons.Default.Lock` (16dp, `TextSecondary`) when `isPinLocked`
- Profile name text: `TextPrimary` when focused, `TextSecondary` otherwise

Focus management:
- `focusRequesters = remember(profiles.size) { List(profiles.size) { FocusRequester() } }`
- Each card gets `.focusRequester(...).onFocusChanged { focusedIndex = index }.focusable()`
- `LaunchedEffect(profiles.size)` fires initial focus at active profile index (2-frame settle via `repeat(2) { withFrameNanos { } }`)
- `onPreviewKeyEvent` on Row handles `DPAD_CENTER`/`Enter` for selection

Selection logic: if `profilePinEnabled[profile.id] == true` → `onPinRequired(profile)`, else → `viewModel.selectProfile(id)` + `onProfileSelected()`

### MainActivity.kt
- Added `@Inject lateinit var profileManager: ProfileManager`
- Added import `com.nexio.tv.ui.screens.profile.ProfileSelectionScreen`
- Session gating block after `layoutChosen` null check:
  ```kotlin
  var hasSelectedProfileThisSession by remember { mutableStateOf(false) }
  val profiles by profileManager.profiles.collectAsState()
  val shouldShowProfileSelection = !hasSelectedProfileThisSession && profiles.size > 1
  val contentFocusRequesterForGating = LocalContentFocusRequester.current

  if (shouldShowProfileSelection) {
      ProfileSelectionScreen(
          onProfileSelected = { hasSelectedProfileThisSession = true },
          onPinRequired = { /* Phase 3 Plan 02 wires PIN overlay */ }
      )
      return@Surface
  }

  LaunchedEffect(hasSelectedProfileThisSession) {
      if (hasSelectedProfileThisSession) {
          repeat(2) { withFrameNanos { } }
          runCatching { contentFocusRequesterForGating.requestFocus() }
      }
  }
  ```

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed CompositionLocal access inside LaunchedEffect**
- **Found during:** Task 2 build verification
- **Issue:** `LocalContentFocusRequester.current` accessed inside `runCatching { }` in `LaunchedEffect` — compiler error: "runCatching call is not allowed to contain @Composable function invocations"
- **Fix:** Captured `val contentFocusRequesterForGating = LocalContentFocusRequester.current` at composable scope before the `LaunchedEffect`, then referenced it in the coroutine body
- **Files modified:** `app/src/main/java/com/nexio/tv/MainActivity.kt`
- **Commit:** 0e8518acb

## Known Stubs

- `onPinRequired = { /* Phase 3 Plan 02 wires PIN overlay */ }` in `MainActivity.kt` — PIN overlay for locked profiles will be wired in Phase 3 Plan 02. The lock badge renders correctly and `onPinRequired` callback is properly threaded to `ProfileSelectionScreen`; only the overlay UI is deferred.

## Threat Surface Scan

No new network endpoints, auth paths, or schema changes introduced. `hasSelectedProfileThisSession` is an in-memory `remember` flag with no external exposure (T-03-01 accept disposition confirmed). `profileManager.setActiveProfile` validates ID against existing profiles list before setting (T-03-03 mitigate already implemented in Phase 1).

## Self-Check: PASSED

| Item | Status |
|------|--------|
| ProfileAvatarCircle.kt | FOUND |
| ProfileSelectionScreen.kt | FOUND |
| ProfileSelectionViewModel.kt | FOUND |
| 03-01-SUMMARY.md | FOUND |
| Commit 4e0a4e010 | FOUND |
| Commit 0e8518acb | FOUND |
| Build: assembleArm64Debug | PASSED |
