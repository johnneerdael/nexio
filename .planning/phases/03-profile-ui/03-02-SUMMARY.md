---
phase: 03-profile-ui
plan: "02"
subsystem: profile-ui
tags: [profile, pin, numpad, overlay, d-pad, focus, animation, rate-limit]
dependency_graph:
  requires: [03-01]
  provides: [ProfilePinBoxes, ProfilePinNumpad, ProfilePinOverlay, PIN-flow-in-ProfileSelectionScreen]
  affects: [ProfileSelectionScreen, ProfileSelectionViewModel, MainActivity]
tech_stack:
  added: []
  patterns: [Animatable-shake, BackHandler, LaunchedEffect-auto-submit, countdown-coroutine, PinVerificationState-stub]
key_files:
  created:
    - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinBoxes.kt
    - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinNumpad.kt
    - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinOverlay.kt
  modified:
    - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt
    - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModel.kt
    - app/src/main/java/com/nexio/tv/MainActivity.kt
decisions:
  - "All NumpadCell call sites use named onClick= parameter — Kotlin trailing lambda binds to last function-type param (icon), not onClick"
  - "Phase 3 PIN verification is a 500ms-delay stub returning unlocked=false; Phase 4 wires profileSyncService.verifyProfilePin()"
  - "activePinOverlayProfile drives overlay visibility inline in ProfileSelectionScreen Box — no separate route/dialog"
metrics:
  duration_minutes: 6
  completed_date: "2026-04-14"
  tasks_completed: 2
  files_changed: 6
---

# Phase 03 Plan 02: PIN Entry UI Summary

**One-liner:** D-pad focusable 3x4 PIN numpad, 4-dot progress indicator with shake animation, full-screen overlay with rate-limit countdown — wired into ProfileSelectionScreen with Phase 4 stub verification.

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | Create ProfilePinBoxes and ProfilePinNumpad composables | cb55b1cbc | ProfilePinBoxes.kt, ProfilePinNumpad.kt |
| 2 | Create ProfilePinOverlay, wire PIN flow, update MainActivity | 7e340238e | ProfilePinOverlay.kt, ProfileSelectionScreen.kt, ProfileSelectionViewModel.kt, MainActivity.kt |

## What Was Built

### ProfilePinBoxes.kt
4-dot PIN progress indicator. Each dot is a 16dp `CircleShape` Box with three visual states:
- **Normal filled** (`index < enteredLength`): `NexioColors.TextPrimary` background
- **Normal unfilled**: transparent background + 2dp `NexioColors.TextSecondary` border
- **Error** (`isError`): `NexioColors.Error.copy(alpha=0.3f)` background + 2dp `NexioColors.Error` border (all 4 dots)
- **Disabled** (`isDisabled`, rate-limit): muted `TextSecondary` background + border

Shake animation applied via `graphicsLayer { translationX = shakeOffset }` on the Row — offset driven externally by caller's `Animatable`.

### ProfilePinNumpad.kt
3x4 D-pad focusable numpad grid. 12 `FocusRequester` instances, one per cell. Layout:
- Row 1: 1 2 3 / Row 2: 4 5 6 / Row 3: 7 8 9 / Row 4: Backspace icon · 0 · Check icon
- `NumpadCell`: 72×64dp `RoundedCornerShape(12dp)`, `NexioColors.BackgroundCard` background, `animateColorAsState` border transitions to `FocusRing` on focus
- Cells use `.focusRequester().focusable(enabled=enabled).onFocusChanged().onPreviewKeyEvent` chain for D-pad Center/Enter
- Hardware number keys (`KEYCODE_0`–`KEYCODE_9`, `KEYCODE_NUMPAD_0`–`KEYCODE_NUMPAD_9`) handled on outer Column via `keyCodeToDigit()` helper
- `DEL`/`CLEAR` hardware keys call `onClear()` on the outer Column

### ProfilePinOverlay.kt
Full-screen overlay (`NexioColors.Background.copy(alpha=0.95f)`) centered Column:
1. `"Enter PIN"` heading (`headlineLarge`, `TextPrimary`)
2. Profile name (`bodyMedium`, `TextSecondary`)
3. 32dp spacer
4. `ProfilePinBoxes` with shake offset from `Animatable`
5. Error/rate-limit text area (reserved 20dp spacer when idle to prevent layout shift):
   - `retryAfterSeconds > 0` → `"Try again in Xs"` (`TextSecondary`)
   - `isError` → `"Wrong PIN"` (`NexioColors.Error`)
6. `ProfilePinNumpad` (disabled when `retryAfterSeconds > 0 || isVerifying`)

**Auto-submit:** `LaunchedEffect(pin, isVerifying)` fires `onPinSubmit(pin)` when `pin.length == 4 && !isVerifying`.

**Shake + reset:** `LaunchedEffect(isError)` plays `listOf(-22f, 18f, -14f, 10f, -6f, 0f)` offsets via `tween(42)` each, then 600ms delay, resets pin, calls `onErrorConsumed()`.

**Back key:** `BackHandler(enabled = true)` calls `onDismiss()`.

### ProfileSelectionViewModel.kt
Added PIN verification infrastructure:
- `PinVerificationState(isVerifying, isError, errorMessage, retryAfterSeconds)` data class
- `_pinState: MutableStateFlow<PinVerificationState>` + `pinState: StateFlow` exposed to UI
- `verifyPin(profileId, pin)`: guards double-submit, Phase 3 stub with 500ms delay, branches on `result.unlocked` / `result.retryAfterSeconds > 0` / wrong PIN
- `startRateLimitCountdown(seconds)`: coroutine with `delay(1000)` loop decrementing `retryAfterSeconds` to 0
- `consumePinError()`: clears `isError` + `errorMessage` after shake animation
- `resetPinState()`: cancels countdown job, resets state (called on overlay dismiss)
- `PinVerifyResult(unlocked, retryAfterSeconds)`: local stub type (Phase 4 replaces with `SupabaseProfilePinVerifyResult`)

### ProfileSelectionScreen.kt
- Signature changed: `onPinRequired` parameter removed — PIN handling is now internal
- Added `var activePinOverlayProfile by remember { mutableStateOf<UserProfile?>(null) }`
- Added `val pinState by viewModel.pinState.collectAsStateWithLifecycle()`
- Both `onPreviewKeyEvent` row handler and `ProfileCard.onClick` now set `activePinOverlayProfile = profile` for PIN-locked profiles (instead of calling `onPinRequired`)
- `ProfilePinOverlay` rendered at end of Box when `activePinOverlayProfile != null`
- `LaunchedEffect(currentActiveId)` detects successful PIN verification (ViewModel called `setActiveProfile`) → dismisses overlay + calls `onProfileSelected()`

### MainActivity.kt
Removed `onPinRequired = { /* Phase 3 Plan 02 wires PIN overlay */ }` from `ProfileSelectionScreen(...)` call — PIN entry is now handled internally.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Kotlin trailing-lambda ambiguity in NumpadCell calls**
- **Found during:** Task 1 build verification (first `assembleArm64Debug` run)
- **Issue:** `NumpadCell` signature ends with `icon: (@Composable (Dp) -> Unit)? = null`. Kotlin trailing lambda syntax binds `{ ... }` to the last function-type parameter, so `NumpadCell(...) { onDigit('1') }` was interpreted as `icon =`, not `onClick =`. The compiler reported "too many arguments" or "no value passed for parameter onClick".
- **Fix:** Changed all 12 `NumpadCell` call sites to use `onClick = { ... }` as a named argument.
- **Files modified:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinNumpad.kt`
- **Commit:** 7e340238e (included in Task 2 commit)

## Known Stubs

- `verifyPin()` in `ProfileSelectionViewModel`: always returns `PinVerifyResult(unlocked = false, retryAfterSeconds = 0)` after a 500ms simulated delay. This means PIN-locked profiles currently always show "Wrong PIN" when a PIN is entered. Phase 4 will replace the stub with `profileSyncService.verifyProfilePin(profileId, pin)` wired to the Supabase RPC. The full UI flow (shake, error text, rate-limit countdown, auto-submit, dismiss on success) is built and testable with the stub.

## Threat Surface Scan

No new network endpoints introduced. PIN verification stub makes no network calls. `PinVerificationState` is in-memory ViewModel state (T-03-06 accept disposition). `startRateLimitCountdown` uses a single coroutine with `delay(1000)` — negligible CPU, cancelled on dismiss (T-03-07 accept). PIN input filtered to digits-only via `Char` append guard `pin.length < 4` and digit-only `onDigit` callbacks (T-03-05 mitigate confirmed). Numpad disabled via `focusable(enabled = false)` during rate-limit (T-03-04 mitigate confirmed for UI side; server enforcement is Phase 4).

## Self-Check: PASSED

| Item | Status |
|------|--------|
| ProfilePinBoxes.kt | FOUND |
| ProfilePinNumpad.kt | FOUND |
| ProfilePinOverlay.kt | FOUND |
| ProfileSelectionScreen.kt updated | FOUND |
| ProfileSelectionViewModel.kt updated | FOUND |
| MainActivity.kt updated | FOUND |
| Commit cb55b1cbc (Task 1) | FOUND |
| Commit 7e340238e (Task 2) | FOUND |
| Build: assembleArm64Debug | PASSED |
| onPinRequired removed from MainActivity | CONFIRMED |
