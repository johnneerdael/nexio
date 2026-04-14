---
status: partial
phase: 03-profile-ui
source: [03-VERIFICATION.md]
started: 2026-04-14T13:30:00Z
updated: 2026-04-14T13:30:00Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. D-pad avatar navigation
expected: Left/right D-pad navigates between profile avatars, focused avatar shows 1.15x scale with FocusRing colored border, DPAD_CENTER selects profile
result: [pending]

### 2. Lock badge visual
expected: 20dp CircleShape badge at avatar bottom-right for profiles with pinEnabled=true, containing Lock icon
result: [pending]

### 3. PIN numpad D-pad traversal
expected: 12-cell grid navigable with D-pad, cell activation fills PIN dot, 4th digit auto-submits
result: [pending]

### 4. Wrong PIN shake animation
expected: Horizontal shake animation on wrong PIN (500ms delay, -22/18/-14/10/-6/0 offsets), red "Wrong PIN" text, dots clear after 600ms
result: [pending]

### 5. Sidebar expand/collapse
expected: Profile section expands with AnimatedVisibility, arrow rotates 180 degrees, Back key collapses section without dismissing sidebar
result: [pending]

### 6. Single-profile sidebar hidden
expected: No profile switcher section visible in sidebar when only 1 profile exists
result: [pending]

### 7. Settings header positioning
expected: Profile header with avatar (40dp) + name above settings category rail, "Default" badge for Profile 1, visible for all users
result: [pending]

## Summary

total: 7
passed: 0
issues: 0
pending: 7
skipped: 0
blocked: 0

## Gaps
