# Change: Refactor Account Config Sync Scope

## Why
Nexio currently syncs a wide account settings payload through Supabase, including appearance,
layout, playback, Trakt behavior, and debug fields that should remain device-local. We want the
account sync boundary to cover only integrations configuration, catalog configuration/order, and
addons while keeping older released clients functional during rollout.

## What Changes
- Narrow the canonical synced account settings contract to integrations and catalog configuration.
- Keep addons on the existing separate addon sync path.
- Add contract versioning to the settings RPCs so newer clients can use the narrowed contract while
  older clients continue using the legacy wide payload.
- Normalize stored settings rows into a canonical v2 payload with a compatibility-only legacy
  sidecar for older clients.
- Update Android account sync behavior so v2 clients no longer push or apply local-only settings.

## Impact
- Affected specs: `account-config-sync` (new capability)
- Affected code:
  - `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
  - `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
  - `app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/account/AccountViewModel.kt`
  - `supabase/account_settings_sync.sql`
- Related parallel work:
  - `nexio-web/` portal contract narrowing already in progress outside this implementation scope

## Rollout & Safety
- Deploy the Supabase compatibility layer before shipping contract-v2 clients.
- Preserve legacy compatibility fields on v2 writes so older clients do not lose their expected
  state during rollout.
- Support lazy normalization of existing legacy rows so no one-time database migration is required.
