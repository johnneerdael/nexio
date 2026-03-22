# Home Screen: Architecture and Daily Use

## Audience
This guide is for users who want a polished Home experience and want to understand why the screen behaves the way it does.

## What this page covers
- The Home loading pipeline
- Why you may see different empty states
- How Continue Watching, Hero, and Catalog rails are composed
- How long-press actions and watched state are resolved

## Source of truth
The Home behavior is implemented in:
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt`

## Home rendering model
Home is built from three content groups:
1. **Hero items**
2. **Continue Watching items**
3. **Catalog rows**

Home is considered renderable as soon as at least one group has content.

### Startup gate and timeout behavior
At startup, Nexio opens with a guarded loading state. If no content can be rendered, a timeout guard is armed.

- Timeout threshold: **5000 ms**
- If the threshold is reached with no renderable content, Home shows a retry-capable error state

This behavior exists to avoid infinite spinner states and to provide a deterministic recovery path.

## Empty and fallback states
Home can intentionally show different messages depending on root cause.

- **No addons installed**: no providers configured
- **No catalog addons installed**: addons exist, but none contribute Home catalogs
- **Timeout loading state**: startup gate elapsed before renderable content arrived

This distinction helps you fix the right layer quickly.

## Focus and remote UX model
Home is TV-first and focus-driven.

- Focus restoration is designed for rapid row re-entry
- Long-press actions are bound per item and include watched-state context
- On app resume, Home triggers foreground refresh logic

## Practical workflow

### 1. Validate provider layer
Confirm at least one addon with non-search catalogs exists.

**Expected result:** Home can render at least one catalog row.

### 2. Validate content layer
Open Home and confirm one of these appears quickly:
- Hero content
- Continue Watching
- Catalog rows

**Expected result:** Spinner state resolves into content.

### 3. Validate interaction layer
Long-press a poster and verify options appear and reflect watched state correctly.

**Expected result:** Context actions are available and relevant.

## Advanced troubleshooting

### Symptom
Home shows loading and then a timeout error.

### Likely root causes
- No active content providers
- Provider reachable but returns empty catalogs
- Slow startup path with no renderable fallback content

### Recovery runbook
1. Confirm at least one addon is installed in [Addon Manager](../../web/admin-workspaces/addons.md).
2. Confirm at least one catalog is enabled in [Catalog Inventory](../../web/admin-workspaces/catalogs.md).
3. Return to Home and use Retry.
4. If still empty, restart app once to force a clean bootstrap.

### Verification
At least one Home section renders without timeout.

## Design note
Home blocks display mode changes outside the main player session to prevent display switching side effects while browsing.

## Next page
Continue with [Media Detail](./detail.md) for metadata, trailer, and action orchestration.
