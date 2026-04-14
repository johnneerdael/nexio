---
status: testing
phase: 04-sync-and-cleanup
source:
  - 04-01-SUMMARY.md
  - 04-02-SUMMARY.md
  - 04-03-SUMMARY.md
  - 04-04-SUMMARY.md
  - 04-05-SUMMARY.md
  - 04-06-SUMMARY.md
started: 2026-04-14T17:59:59Z
updated: 2026-04-14T19:14:53Z
---

## Current Test

number: 2
name: Startup sync remains silent
expected: |
  Start the app while signed in. Profile metadata and the active profile settings pull silently before account sync continues. There is no new blocking dialog, no visible sync interruption, and the app reaches its normal start screen.
awaiting: user response

## Tests

### 1. Settings Sync Now feedback
expected: Open Settings. The Account/Profile area has a Sync Now action. Activating it with the TV remote shows a transient syncing state, then either success feedback or a clear error message without leaving the Settings screen.
result: pass

### 2. Startup sync remains silent
expected: Start the app while signed in. Profile metadata and the active profile settings pull silently before account sync continues. There is no new blocking dialog, no visible sync interruption, and the app reaches its normal start screen.
result: [pending]

### 3. Primary profile delete guard
expected: Open Settings while using the primary/default profile. The profile delete action is not offered for the primary profile.
result: [pending]

### 4. Non-primary delete confirmation
expected: With a non-primary profile selected, Settings shows Delete Profile. Activating it opens a NexioDialog that names the profile, focuses Keep Profile by default, and offers a destructive Delete Profile action.
result: [pending]

### 5. Non-primary delete cleanup
expected: Confirming Delete Profile removes the selected non-primary profile locally. The dialog closes, the app falls back to a valid remaining profile, and remote cleanup failure does not block local deletion.
result: [pending]

### 6. Cross-profile settings isolation
expected: If two profiles are available, changing layout/catalog/player/theme settings on Profile 2 does not alter Profile 1 after sync/startup. Returning to Profile 1 shows its own settings.
result: [pending]

## Summary

total: 6
passed: 1
issues: 0
pending: 5
skipped: 0
blocked: 0

## Gaps
