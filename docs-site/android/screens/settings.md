# Settings and Account: Information Architecture Guide

## Audience
This guide is for users who need predictable, high-confidence configuration changes and want to understand the settings architecture.

## What this page covers
- Settings category model
- Integration hub structure
- Focus and navigation behavior
- Safe change and rollback strategy

## Source of truth
Settings behavior is implemented in:
- `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt`

## Settings category architecture
Nexio settings are organized into explicit categories:
- Account
- Appearance
- Layout
- Integration
- Playback
- Catalogs
- About
- Debug build only

This architecture separates high-frequency personalization from advanced diagnostics and operational controls.

## Destination model
Settings sections use two destination types:
- **Inline**: managed inside the settings detail area
- **External**: navigates to a dedicated screen

Catalog management is external by design to keep ordering and enablement workflows focused.

## Integration hub model
Integration is segmented into sub-sections so connections can be isolated and tested independently:
- Hub overview
- Debrid
- Trakt
- TMDB
- MDBList
- Anime Skip
- Gemini
- Poster ratings

This structure supports staged onboarding and easier incident isolation.

## Focus and remote behavior
Settings is optimized for TV remote operation:
- Left rail category focus
- Detail-pane autofocus with guard delay
- Controlled transitions between rail and detail

This prevents accidental context jumps during rapid directional input.

## Safe configuration workflow

### 1. Change one category at a time
Apply only one category worth of changes in a session.

**Expected result:** Root-cause tracing remains simple.

### 2. Validate in runtime screen
Immediately test in Home, Detail, or Player after each change.

**Expected result:** Behavior differences are attributable to a known change.

### 3. Commit stable baseline
Keep settings that improve behavior.
Revert changes that degrade results.

**Expected result:** Configuration converges to a stable profile.

## Troubleshooting

### Symptom
A feature stopped working after settings updates.

### Likely cause
Cross-category changes introduced interaction effects.

### Recovery
1. Revert the most recent category first.
2. Re-test runtime behavior.
3. Re-apply changes one section at a time.
4. Stop when issue reproduces.

### Verification
You can identify the exact setting group that caused the regression.

## Cross-links
- Catalog-specific controls: [Catalog Inventory](../../web/admin-workspaces/catalogs.md)
- Integration setup sequence: [Integrations](../../web/admin-workspaces/integrations.md)

## Next page
Continue with [Search & Cast](./search-and-cast.md) for discovery workflows and cross-device use cases.
