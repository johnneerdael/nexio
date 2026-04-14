---
phase: 03-profile-ui
plan: "03"
subsystem: profile-ui
tags: [profile, sidebar, settings, composable, d-pad, focus, switcher]
dependency_graph:
  requires: [03-01, 02-per-profile-auth-and-settings]
  provides: [ProfileSwitcherSection, ProfileSwitcherRow, ProfileHeaderRow]
  affects: [ModernSidebarBlurPanel, ModernSidebarScaffold, MainActivity, SettingsScreen, SettingsProfileViewModel]
tech_stack:
  added: []
  patterns: [AnimatedVisibility, animateFloatAsState, BackHandler-LIFO, combine-StateFlow, collectAsStateWithLifecycle]
key_files:
  created: []
  modified:
    - app/src/main/java/com/nexio/tv/ModernSidebarBlurPanel.kt
    - app/src/main/java/com/nexio/tv/MainActivity.kt
    - app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt
decisions:
  - "animateFloatAsState is in androidx.compose.animation.core not androidx.compose.animation — fixed import"
  - "Wrap SettingsWorkspaceSurface Row in Column to insert ProfileHeaderRow above rail without disturbing focus logic"
  - "BackHandler inside ProfileSwitcherSection is innermost handler (LIFO) so it fires before sidebar-level BackHandler"
metrics:
  duration_minutes: 28
  completed_date: "2026-04-14"
  tasks_completed: 2
  files_changed: 3
---

# Phase 03 Plan 03: Sidebar Profile Switcher and Settings Header Summary

**One-liner:** Expandable inline profile switcher at the top of the modern sidebar (40dp/32dp avatars, animateFloatAsState arrow, AnimatedVisibility list, BackHandler collapse) and always-visible profile header row in settings (40dp avatar, Default badge with FocusRing 20% alpha).

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | Add ProfileSwitcherSection to ModernSidebarBlurPanel and wire in MainActivity | 176228964 | ModernSidebarBlurPanel.kt, MainActivity.kt |
| 2 | Add ProfileHeaderRow to SettingsScreen | 498767e3d | SettingsScreen.kt |

## What Was Built

### ModernSidebarBlurPanel.kt

New parameters added to `ModernSidebarBlurPanel`:
- `profiles: List<UserProfile> = emptyList()`
- `activeProfileId: Int = 1`
- `onSwitchProfile: (Int) -> Unit = {}`

`ProfileSwitcherSection` (private composable):
- Active profile row: 56dp height, `RoundedCornerShape(14.dp)`, `BackgroundCard` bg, `FocusRing` focus border via `animateColorAsState`.
- `ProfileAvatarCircle(size = 40.dp)` for active profile avatar.
- Profile name with `sidebarLabelAlpha` applied via `graphicsLayer`.
- `KeyboardArrowDown` icon with `animateFloatAsState` rotation 0f→180f (tween 200ms) on expand.
- `BackHandler(enabled = isExpanded)` innermost — collapses section on Back without dismissing sidebar.
- `AnimatedVisibility(enter = expandVertically(tween(200)), exit = shrinkVertically(tween(180)))` wraps other profiles list.

`ProfileSwitcherRow` (private composable):
- 48dp height row, `RoundedCornerShape(14.dp)`, focus border.
- `ProfileAvatarCircle(size = 32.dp)` per UI-SPEC expanded list size.
- Name text: `TextPrimary` when focused, `TextSecondary` otherwise.
- `onPreviewKeyEvent` handles `DPAD_CENTER`/`Enter`/`NumPadEnter`.

Visibility gating: `if (profiles.size > 1)` — switcher hidden for single-profile users (D-14).

### MainActivity.kt

`ModernSidebarScaffold` signature extended with `profiles`, `activeProfileId`, `onSwitchProfile` params (defaulted). These are passed through to `ModernSidebarBlurPanel`. At the call site:
- `profiles = profiles` (already collected from `profileManager.profiles`)
- `activeProfileId = profileManager.activeProfileId.collectAsState().value`
- `onSwitchProfile` lambda: sets `hasSelectedProfileThisSession = false` (D-13) then calls `profileManager.setActiveProfile(profileId)` via `lifecycleScope.launch`.

### SettingsScreen.kt

`SettingsProfileViewModel` extended with:
```kotlin
val activeProfile: StateFlow<UserProfile?> = combine(
    profileManager.profiles,
    profileManager.activeProfileId
) { profiles, activeId ->
    profiles.find { it.id == activeId }
}.stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

`ProfileHeaderRow` (private composable):
- `Row` with `fillMaxWidth`, padding `horizontal = 32.dp, vertical = 16.dp`.
- `ProfileAvatarCircle(size = 40.dp)` — matches sidebar expanded row.
- Profile name: `MaterialTheme.typography.titleMedium`, `TextPrimary`.
- "Default" badge (when `profile.isPrimary`): `bodyMedium`, `FocusRing` color, `FocusRing.copy(alpha = 0.2f)` background, `RoundedCornerShape(4.dp)`.

Layout: `SettingsWorkspaceSurface` content wrapped in `Column`; `ProfileHeaderRow` inserted above the rail+content `Row` (which gets `Modifier.weight(1f)`). Always visible even for single-profile users (D-16).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed wrong import package for `animateFloatAsState`**
- **Found during:** Task 1 first build
- **Issue:** Imported as `androidx.compose.animation.animateFloatAsState` — unresolved reference at runtime.
- **Fix:** Changed to `androidx.compose.animation.core.animateFloatAsState` (correct package).
- **Files modified:** `app/src/main/java/com/nexio/tv/ModernSidebarBlurPanel.kt`
- **Commit:** 176228964

## Known Stubs

None — all profile data is live from `ProfileManager.profiles` and `ProfileManager.activeProfileId` StateFlows.

## Threat Surface Scan

No new network endpoints, auth paths, or file access patterns introduced. Profile IDs passed to `ProfileManager.setActiveProfile` are sourced exclusively from the `profiles` StateFlow (T-03-09 mitigate already implemented — validation in ProfileManager). Sidebar profile names are visible to all device users, which is expected household behavior (T-03-10 accept disposition confirmed).

## Self-Check: PASSED

| Item | Status |
|------|--------|
| ModernSidebarBlurPanel.kt contains ProfileSwitcherSection | FOUND |
| ModernSidebarBlurPanel.kt contains ProfileSwitcherRow | FOUND |
| ModernSidebarBlurPanel.kt contains profiles.size > 1 gate | FOUND |
| ModernSidebarBlurPanel.kt contains AnimatedVisibility | FOUND |
| ModernSidebarBlurPanel.kt contains animateFloatAsState | FOUND |
| ModernSidebarBlurPanel.kt contains BackHandler(enabled = isExpanded) | FOUND |
| ModernSidebarBlurPanel.kt contains ProfileAvatarCircle at 40dp and 32dp | FOUND |
| MainActivity.kt contains onSwitchProfile with hasSelectedProfileThisSession = false | FOUND |
| SettingsScreen.kt contains ProfileHeaderRow | FOUND |
| SettingsScreen.kt contains activeProfile StateFlow with combine | FOUND |
| SettingsScreen.kt contains "Default" badge with FocusRing.copy(alpha = 0.2f) | FOUND |
| SettingsScreen.kt contains ProfileAvatarCircle at 40dp | FOUND |
| Commit 176228964 | FOUND |
| Commit 498767e3d | FOUND |
| Build assembleArm64Debug | PASSED |
