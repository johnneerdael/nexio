# Devices Page And Legacy Session Cleanup Design

## Summary

Move linked-device management out of `Integrations` and into a first-class `Devices` account section. Show only durable-backed devices by default. Hide legacy-only session rows behind an explicit `Show legacy sessions` toggle, and provide a bulk cleanup action to remove those stale legacy rows without touching durable-backed devices.

## Goals

- Make device management a clear top-level account navigation section.
- Remove the current UX confusion where linked devices appear under `Integrations`.
- Keep the default device-management surface clean and focused on real durable-backed devices.
- Give users a safe, explicit way to reveal and clean up stale legacy-only session rows.
- Preserve honest wording around durable revoke vs legacy session cleanup.

## Non-Goals

- Automatically deleting legacy-only session rows during migration.
- Reintroducing metadata-only legacy auto-migration to durable authority.
- Redesigning the broader account portal structure beyond adding `Devices`.

## Information Architecture

Add a new account-level navigation section named `Devices` alongside:
- `Profiles`
- `Addons`
- `Integrations`
- `Formatter`

`Devices` becomes the only primary portal surface for linked-device management.

`Integrations` returns to integration auth/config only and should no longer be the place where users manage TVs or other linked playback devices.

## Devices Page Behavior

### Default View

The default `Devices` page shows only durable-backed devices.

Each device row should use:
- Stable approval-time display name as the primary label
- Supporting metadata such as runtime/linkage status and durable auth state
- Durable revoke as the main destructive action

This default view should intentionally exclude legacy-only rows so the page stays clean even for users with dozens of stale historical sessions.

### Legacy Sessions Toggle

Add a control such as `Show legacy sessions`.

When disabled:
- legacy-only rows are completely hidden

When enabled:
- a separate `Legacy sessions` section appears below the main durable device list
- those rows are visually downgraded and clearly labeled as old pre-migration session/linkage records

## Legacy Cleanup

The `Legacy sessions` section should include a bulk action:
- `Remove all legacy sessions`

This action should:
- delete only legacy-only rows
- leave durable-backed devices untouched
- be described as cleanup/removal of old session records, not durable revoke

Per-row removal is optional. The primary user problem is bulk stale-session cleanup, so bulk-only is acceptable for the first implementation pass.

## Durable Revoke Semantics

Durable-backed devices in the main `Devices` list should continue using the current revoke behavior:
- future session issuance is blocked immediately
- already-issued JWTs may remain valid until expiry

This wording should remain explicit in the UI.

## Legacy Session Copy

Legacy-only rows should use different wording from durable devices.

They should not be described as:
- active durable authority
- revocable durable devices

They should be described as:
- legacy sessions
- pre-migration linked session records
- removable cleanup targets

## Migration Position

The migration story should remain honest:
- New devices use durable auth.
- Previously migrated devices use durable auth.
- Legacy-only sessions remain hidden by default.
- Users may reconnect a legacy device once to migrate it.
- Users may remove stale legacy session rows from the cleanup section.
- No metadata-only silent auto-migration should be reintroduced.

## Recommended Implementation Direction

Use the current durable-backed device inventory as the main `Devices` dataset.

Add a second legacy-session dataset for the hidden-by-default cleanup section. Keep the two lists logically separate in both data and UI so durable device management and legacy cleanup cannot be confused.

## Open Questions

- Whether legacy cleanup should be bulk-only or also support per-row removal in the first pass.
- Whether the `Show legacy sessions` toggle state should persist per browser session or reset each visit.
