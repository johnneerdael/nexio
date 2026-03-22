# Addon Manager: Operational Guide

## Audience
This page is for users who manage multiple providers and want predictable stream quality, clean labels, and controlled rollout of source changes.

## What this page covers
- Addon installation and parser behavior
- Read-only versus editable states
- QR manage mode architecture
- Operational workflow for safe provider changes

## Source of truth
Addon manager behavior is implemented in:
- `app/src/main/java/com/nexio/tv/ui/screens/addon/AddonManagerScreen.kt`
- `app/src/main/java/com/nexio/tv/core/server/AddonConfigServer.kt`

## Addon manager architecture
Addon Manager is the source control plane for content providers.
It governs:
- Installed addon URL set
- Parser preset assignment
- Catalog contribution visibility
- Optional phone-based remote management flow

If no addon contributes non-search catalogs, Home cannot present normal row content.

## Editable and read-only modes
The UI can present a read-only notice state.
In read-only mode, mutation actions are suppressed.
In editable mode, install URL input and action buttons are active.

This split supports safer operation for restricted configurations.

## Install flow
1. Open Addon Manager.
2. Enter addon URL.
3. Commit install.
4. Validate addon appears in installed list.

**Expected result:** Provider is persisted and available for catalog and stream workflows.

## Parser preset strategy
Parser presets determine how stream metadata is interpreted.
Wrong preset selection can degrade stream label quality and matching behavior.

Recommended practice:
- Start with provider-native preset when available.
- Validate one known title.
- Switch preset only if parsed labels are visibly incorrect.

## QR manage mode architecture
Nexio includes a local HTTP control channel for phone management.

Server capabilities:
- Serves local management page
- Exposes state endpoints
- Accepts proposed changes as pending operations
- Requires confirm or reject lifecycle for pending change status

Pending change status model:
- `PENDING`
- `CONFIRMED`
- `REJECTED`

Stale pending changes are auto-rejected when a new update request arrives.

## Operational runbook for production-safe changes

### 1. Add one provider at a time
Do not batch multiple new provider URLs in one session.

### 2. Validate parser output immediately
Open a known title and inspect stream naming quality.

### 3. Validate Home impact
Confirm at least one useful catalog row appears.

### 4. Only then add second provider
Repeat the same validation sequence.

## Troubleshooting

### Symptom
Addon is installed but Home still has no usable rows.

### Likely root causes
- Provider contributes search-only catalogs
- Catalogs are disabled in ordering settings
- Install URL is valid syntactically but functionally dead

### Recovery
1. Confirm addon appears in installed list.
2. Check catalog state in [Catalog Inventory](./catalogs.md).
3. Test a known-good addon URL for comparison.
4. Remove non-working provider to reduce noise.

### Verification
Home renders at least one populated catalog row and stream list quality is acceptable.

## Next page
Continue with [Catalog Inventory](./catalogs.md) to control row ordering and visibility behavior.
