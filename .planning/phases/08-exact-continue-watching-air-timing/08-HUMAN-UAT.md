---
status: partial
phase: 08-exact-continue-watching-air-timing
source: [08-VERIFICATION.md]
started: 2026-04-15T14:52:55Z
updated: 2026-04-15T15:17:13Z
---

## Current Test

[awaiting human/device verification]

## Tests

### 1. Real Android TV missed-trigger restore
expected: Schedule a future TVDB-backed next-up row, kill the app process before the airing instant, let the instant pass, then reopen the app. Continue Watching refreshes provider state and the row appears when available.
result: [pending]

### 2. Real Android TV reboot reschedule
expected: Schedule a future row, reboot before the airing instant, and confirm the boot receiver reschedules or refreshes correctly when due. Continue Watching appears at or after the airing instant without waiting for a day-level refresh.
result: [pending]

### 3. Android S+ exact-alarm denied fallback
expected: Disable exact-alarm permission on Android S+ and confirm the inexact fallback still refreshes withheld rows. Logs show scheduler fallback diagnostics; UI remains normal and does not expose scheduler degradation on cards.
result: [pending]

### 4. Phase 8 security verification
expected: Receiver/export/PendingIntent/logging risks are explicitly evaluated in `08-SECURITY.md` or equivalent.
result: passed; `08-SECURITY.md` exists with `status: secured`, `threats_total: 23`, `threats_closed: 23`, and `threats_open: 0`.

## Summary

total: 4
passed: 1
issues: 0
pending: 3
skipped: 0
blocked: 0

## Gaps
