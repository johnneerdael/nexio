---
status: partial
phase: 06-tvdb-foundation-and-identity
source: [06-VERIFICATION.md]
started: 2026-04-15T02:35:12Z
updated: 2026-04-15T02:35:12Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. Android TV settings route and D-pad flow
expected: Settings > Integration > TVDB opens, focus lands predictably, credentials dialog can save/clear, and status text/masking are usable on device or emulator.
result: [pending]

### 2. Live TVDB credential validation
expected: A real valid TVDB API key, with optional PIN when required, validates successfully without exposing key, PIN, or token in visible UI, logs, or public sync.
result: [pending]

### 3. Runtime fallback diagnostics
expected: When TVDB is unavailable or a series is missing, browsing falls back explicitly and diagnostics show a sanitized reason without browse-time toasts.
result: [pending]

## Summary

total: 3
passed: 0
issues: 0
pending: 3
skipped: 0
blocked: 0

## Gaps
