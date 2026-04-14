---
phase: 03-profile-ui
reviewed: 2026-04-14T12:00:00Z
depth: standard
files_reviewed: 10
files_reviewed_list:
  - app/src/main/java/com/nexio/tv/MainActivity.kt
  - app/src/main/java/com/nexio/tv/ModernSidebarBlurPanel.kt
  - app/src/main/java/com/nexio/tv/ui/components/ProfileAvatarCircle.kt
  - app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt
  - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinBoxes.kt
  - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinNumpad.kt
  - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinOverlay.kt
  - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt
  - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModel.kt
  - app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt
findings:
  critical: 0
  warning: 5
  info: 3
  total: 8
status: issues_found
---

# Phase 03: Code Review Report

**Reviewed:** 2026-04-14T12:00:00Z
**Depth:** standard
**Files Reviewed:** 10
**Status:** issues_found

## Summary

The Phase 03 Profile UI implementation is well-structured overall. The code follows existing codebase patterns for D-pad navigation, focus management, and Compose TV conventions. The ProfileSelectionScreen, PIN overlay, and sidebar switcher are cleanly decomposed with proper state hoisting.

Key concerns found: missing `Key.NumPadEnter` handling in two key event handlers (inconsistent with every other handler in the codebase), use of non-lifecycle-aware `collectAsState()` for three flows in ProfileSelectionScreen, a modifier ordering issue affecting focus event reliability, and a dead computed value.

No critical (security/crash) issues found. All warnings relate to correctness bugs that could cause broken behavior on certain remote controls or subtle lifecycle issues on Android TV.

## Warnings

### WR-01: Missing Key.NumPadEnter in NumpadCell onPreviewKeyEvent

**File:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinNumpad.kt:197-198`
**Issue:** The `NumpadCell` key handler only checks for `Key.DirectionCenter` and `Key.Enter`, but omits `Key.NumPadEnter`. Every other key handler in the codebase (ModernSidebarBlurPanel lines 264, 360, 441) consistently checks all three keys. Fire TV remotes or Bluetooth keyboards that emit NumPadEnter will not activate numpad cells, making PIN entry impossible on those devices.
**Fix:**
```kotlin
if (event.type == KeyEventType.KeyDown &&
    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
) {
```

### WR-02: Missing Key.NumPadEnter in ProfileSelectionScreen Row onPreviewKeyEvent

**File:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt:113-114`
**Issue:** Same pattern as WR-01. The profile card selection key handler only checks `Key.DirectionCenter` and `Key.Enter`, omitting `Key.NumPadEnter`. A user with a Fire TV remote that emits NumPadEnter cannot select a profile via this handler.
**Fix:**
```kotlin
if (keyEvent.type == KeyEventType.KeyDown &&
    (keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)
) {
```

### WR-03: Non-lifecycle-aware collectAsState() for three flows

**File:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt:62-64`
**Issue:** Three StateFlows (`profiles`, `activeProfileId`, `profilePinEnabled`) are collected with `collectAsState()` instead of `collectAsStateWithLifecycle()`. This is inconsistent with lines 65 and 84 in the same file which correctly use the lifecycle-aware variant. On Android TV, when the screen goes to standby or the app enters the background, these flows will continue collecting and processing updates unnecessarily. While unlikely to cause visible bugs in Phase 3, this is a correctness issue per Compose lifecycle best practices and could cause subtle bugs when Phase 4 adds server-side sync.
**Fix:**
```kotlin
val profiles by viewModel.profiles.collectAsStateWithLifecycle()
val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
val profilePinEnabled by viewModel.profilePinEnabled.collectAsStateWithLifecycle()
```

### WR-04: Modifier ordering -- onFocusChanged after focusable() in NumpadCell

**File:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinNumpad.kt:193-195`
**Issue:** The modifier chain applies `.focusRequester()` then `.focusable()` then `.onFocusChanged()`. In Compose, `onFocusChanged` placed after `focusable()` observes focus changes on the parent node rather than the focusable node itself, which can cause the `isFocused` state to not update reliably on some Compose versions. The established pattern in ModernSidebarBlurPanel (lines 257-261, 356-357, 437-438) correctly places `onFocusChanged` before `focusable()`.
**Fix:**
```kotlin
.focusRequester(focusRequester)
.onFocusChanged { isFocused = it.isFocused }
.focusable(enabled = enabled)
.onPreviewKeyEvent { event ->
```

### WR-05: Duplicate collection of activeProfileId with different lifecycle strategies

**File:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt:63,84`
**Issue:** `viewModel.activeProfileId` is collected twice -- once at line 63 via `collectAsState()` (stored as `activeProfileId`) and again at line 84 via `collectAsStateWithLifecycle()` (stored as `currentActiveId`). These two delegates can briefly hold different values during lifecycle transitions (e.g., when the activity is stopped, the lifecycle-aware one stops updating while the other continues). The `activeProfileId` at line 63 is used for initial focus index computation and display, while `currentActiveId` at line 84 is used for PIN verification success detection. Having two sources of truth for the same value risks subtle state inconsistency.
**Fix:** Remove the duplicate collection at line 63 and use a single lifecycle-aware collection. Rename the line 84 variable to `activeProfileId` and use it everywhere:
```kotlin
val profiles by viewModel.profiles.collectAsStateWithLifecycle()
val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
val profilePinEnabled by viewModel.profilePinEnabled.collectAsStateWithLifecycle()
val pinState by viewModel.pinState.collectAsStateWithLifecycle()

// ... later, remove the duplicate at line 84 and use activeProfileId in the LaunchedEffect:
LaunchedEffect(activeProfileId) {
    val overlayProfile = activePinOverlayProfile
    if (overlayProfile != null && activeProfileId == overlayProfile.id) {
        activePinOverlayProfile = null
        viewModel.resetPinState()
        onProfileSelected()
    }
}
```

## Info

### IN-01: Unused computed value initialFocusIndex

**File:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt:70-73`
**Issue:** `initialFocusIndex` is computed via `remember(profiles, activeProfileId)` but is never referenced anywhere in the composable. The actual focus targeting logic at lines 78-80 independently computes the same index inline. This is dead code.
**Fix:** Remove lines 70-73.

### IN-02: PIN verification stub always rejects -- no way to unlock PIN-locked profiles in Phase 3

**File:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModel.kt:54`
**Issue:** The Phase 3 PIN verification stub hardcodes `unlocked = false`, meaning any profile with `pinEnabled = true` can never be unlocked. This is documented as intentional (Phase 4 will replace with server call), but testers will encounter an always-failing PIN flow with no indication that it is a stub. Consider adding a log or toast during development.
**Fix:** No code change required if this is acceptable for Phase 3 scope. If testability is desired, a temporary `// TODO(phase-4)` log could clarify:
```kotlin
val result = PinVerifyResult(unlocked = false, retryAfterSeconds = 0)
android.util.Log.d("ProfileSelection", "PIN stub: always returns unlocked=false (Phase 4 will add real verification)")
```

### IN-03: rememberSettingsSectionSpecs naming implies memoization but does not use remember

**File:** `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt:132`
**Issue:** The function is named `rememberSettingsSectionSpecs` (suggesting Compose `remember` semantics) but it simply returns a new list on every call. It cannot use `remember` because it calls `stringResource()` internally, which is correct behavior. However, the `remember` prefix is misleading. The caller at line 202 wraps it in `remember(allSectionSpecs)` for filtering, but `allSectionSpecs` itself is a new list identity each recomposition, making that `remember` key always invalidate.
**Fix:** Rename to `settingsSectionSpecs()` to avoid implying memoization, or accept the current naming as a convention for composable factory functions.

---

_Reviewed: 2026-04-14T12:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
