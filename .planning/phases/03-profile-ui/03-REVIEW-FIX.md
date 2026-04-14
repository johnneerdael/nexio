---
phase: 03-profile-ui
fixed_at: 2026-04-14T13:13:50.397Z
review_path: .planning/phases/03-profile-ui/03-REVIEW.md
iteration: 1
findings_in_scope: 5
fixed: 5
skipped: 0
status: all_fixed
---

# Phase 03: Code Review Fix Report

**Fixed at:** 2026-04-14T13:13:50.397Z
**Source review:** .planning/phases/03-profile-ui/03-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 5
- Fixed: 5
- Skipped: 0

## Fixed Issues

### WR-01: Missing Key.NumPadEnter in NumpadCell onPreviewKeyEvent

**Files modified:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinNumpad.kt`
**Commit:** b3947ee08
**Status:** fixed: requires human verification
**Applied fix:** Added `Key.NumPadEnter` to the numpad cell activation key check.

### WR-02: Missing Key.NumPadEnter in ProfileSelectionScreen Row onPreviewKeyEvent

**Files modified:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt`
**Commit:** f0ffec9fb
**Status:** fixed: requires human verification
**Applied fix:** Added `Key.NumPadEnter` to the profile selection row activation key check.

### WR-03: Non-lifecycle-aware collectAsState() for three flows

**Files modified:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt`
**Commit:** b963f7329
**Status:** fixed: requires human verification
**Applied fix:** Switched `profiles`, `activeProfileId`, and `profilePinEnabled` to `collectAsStateWithLifecycle()` and removed the unused `collectAsState` import.

### WR-04: Modifier ordering -- onFocusChanged after focusable() in NumpadCell

**Files modified:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinNumpad.kt`
**Commit:** d904a6e78
**Status:** fixed: requires human verification
**Applied fix:** Moved `onFocusChanged` before `focusable()` in the numpad cell modifier chain.

### WR-05: Duplicate collection of activeProfileId with different lifecycle strategies

**Files modified:** `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt`
**Commit:** cb93cf06f
**Status:** fixed: requires human verification
**Applied fix:** Removed the duplicate `activeProfileId` collection and reused the existing lifecycle-aware `activeProfileId` in the PIN verification success effect.

---

_Fixed: 2026-04-14T13:13:50.397Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
