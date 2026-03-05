# Supabase Settings Sync Integration Guide

## Scope
The redesigned portal syncs only these account-owned domains:
- Settings payload
- Addon stack and repository controls
- Linked-device awareness
- TV QR approval flow

Not part of this redesign:
- Library sync
- Watch progress sync
- Watched-items sync

Those legacy paths still exist in the repo in a few places, but they are intentionally outside the new portal contract.

## Implementation Status
Current repo state as of March 5, 2026:
- `nexio-web` now boots against the account snapshot RPCs.
- Empty accounts are auto-seeded from the web bootstrap route with Android-aligned default settings plus the built-in Cinemeta and OpenSubtitles addons.
- Web persistence now writes only through `sync_push_account_settings(...)` and `sync_push_account_addons(...)`.
- Trakt device auth is now represented inside the synced settings payload under `integrations.traktAuth`.
- MDBList API validation and addon manifest inspection are now handled by server routes in `nexio-web`.
- Android startup/full settings sync work has started in the app codebase, but Android compilation and realtime subscription wiring are still pending validation.

## Current Repo Reality
Relevant existing assets in this repo:
- `supabase/db.sql`: legacy schema snapshot with `linked_devices`, `addons`, `tv_login_sessions`, and older sync tables.
- `supabase/edge_function.ts`: QR session exchange function for `tv_login_sessions`.
- `app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt`: current addon push/pull pattern.
- `app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt`: current startup addon pull pattern.
- `nexio-web`: new account portal scaffold that now expects account-level `settings` and `addons` snapshot endpoints.

## Target Behavior
1. Web changes to synced settings or addons write immediately to Supabase.
2. App startup immediately pulls the account snapshot from Supabase.
3. Running TVs subscribe to an account sync event stream and pull again when settings or addons change.
4. Device-specific playback tuning stays local and is never written to the account snapshot.

## Device-Specific Exclusions
Do not sync these keys into the account snapshot:
- `playback.audioTrailer.mapDV7ToHevc`
- `playback.audioTrailer.experimentalDv7ToDv81Enabled`
- `playback.audioTrailer.experimentalDv7ToDv81PreserveMappingEnabled`
- `playback.audioTrailer.experimentalDv5ToDv81Enabled`
- `playback.audioTrailer.experimentalDtsIecPassthroughEnabled`
- `playback.bufferNetwork.vodCacheSizeMode`
- `playback.bufferNetwork.vodCacheSizeMb`
- `playback.bufferNetwork.useParallelConnections`
- `playback.bufferNetwork.parallelConnectionCount`
- `playback.bufferNetwork.parallelChunkSizeMb`

## Recommended Schema
Apply `supabase/account_settings_sync.sql` as the basis for the new contract. It introduces:
- `account_settings`
  - One row per account owner.
  - Holds the synced settings JSON payload.
  - Carries a monotonic `sync_revision` and `updated_at`.
- `account_addons`
  - Ordered addon list for the account owner.
  - Keeps `enabled`, `sort_order`, and metadata useful to the portal.
- `account_sync_events`
  - Small realtime event stream.
  - Running TVs subscribe to inserts here and then re-pull the snapshot.

Existing tables reused as-is:
- `linked_devices`
- `tv_login_sessions`

## Migration Sequence
1. Keep `linked_devices` and QR login RPCs in place.
2. Add the new account tables and functions from `supabase/account_settings_sync.sql`.
3. Enable Supabase Realtime for `account_sync_events`.
4. Optional: backfill one `account_settings` row per current owner if you want a pre-seeded state before first web login.
5. Do not seed device-specific keys, library data, watch progress, or watched markers.
6. Empty accounts that are not backfilled will be seeded automatically by `GET /api/account/bootstrap` on first authenticated web load.
5. Stop extending the old library/watch/watched sync functions. They are legacy only.

## RPC Contract
New RPCs to expose to the app/web stack:
- `sync_pull_account_snapshot()`
  - Returns one JSON snapshot containing `settings`, `addons`, `revision`, and `updated_at`.
  - Uses the linked-device owner relationship automatically.
- `sync_push_account_settings(p_settings_payload jsonb, p_source text)`
  - Upserts the owner settings row.
  - Emits a `settings` event into `account_sync_events`.
- `sync_push_account_addons(p_addons jsonb, p_source text)`
  - Replaces the owner addon list.
  - Emits an `addons` event into `account_sync_events`.

## Web Portal Integration
The new `nexio-web` portal now expects this account model:
- `GET /api/account/bootstrap`
  - Reads the signed-in account snapshot via `sync_pull_account_snapshot()`.
  - If the snapshot is empty, seeds default settings plus the built-in addons using `p_source = 'web-bootstrap'`.
- `POST /api/account/persist`
  - Pushes the full settings payload through `sync_push_account_settings(..., 'web')`.
  - Replaces the ordered addon list through `sync_push_account_addons(..., 'web')`.
- `POST /api/account/approve-tv-login`
  - Calls the existing `approve_tv_login_session` RPC.

Additional server routes now used by the portal:
- `POST /api/addons/inspect`
  - Fetches addon manifests and derives catalogs/resources/types for the web catalog inventory.
- `POST /api/integrations/mdblist/validate`
  - Validates the MDBList API key and returns personal/top list options.
- `POST /api/integrations/trakt/device-code`
  - Starts Trakt device authentication.
- `POST /api/integrations/trakt/device-token`
  - Polls Trakt device auth completion and returns tokens/profile fields for `integrations.traktAuth`.
- `POST /api/integrations/trakt/popular-lists`
  - Loads Trakt popular-list options for catalog configuration.

Runtime env required by Nuxt:
- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `SUPABASE_SERVICE_ROLE_KEY`
- `TRAKT_CLIENT_ID`
- `TRAKT_CLIENT_SECRET`

## Android Integration
### Startup pull
Replace the old startup-only addon pull with a full account snapshot pull:
1. On authenticated startup, call `sync_pull_account_snapshot()`.
2. Apply `settings` into local stores.
3. Apply `addons` through the existing addon reconciliation path.
4. Do not overwrite excluded device-specific settings.

### Immediate push
Whenever a synced setting changes locally:
1. Rebuild the synced settings payload.
2. Strip excluded keys.
3. Call `sync_push_account_settings(..., 'app')`.

Whenever addons change locally:
1. Rebuild the ordered addon payload.
2. Call `sync_push_account_addons(..., 'app')`.

### Running-device instant sync
1. Subscribe to `account_sync_events` filtered by `sync_owner_id()`.
2. On each inserted row:
   - Ignore events originating from the same device if you add a device identifier later.
   - Call `sync_pull_account_snapshot()`.
   - Re-apply settings and addon state.

This is the cleanest pattern because realtime carries only a tiny event row, not the full JSON payload.

## Settings Payload Mapping
Recommended source-of-truth mapping from current Android stores:
- `ThemeDataStore` -> `appearance`
- `LayoutPreferenceDataStore` -> `layout`
- `TmdbSettingsDataStore` -> `integrations.tmdb`
- `MDBListSettingsDataStore` -> `integrations.mdblist`
- `AnimeSkipSettingsDataStore` -> `integrations.animeSkip`
- `PosterRatingsSettingsDataStore` -> `integrations.posterRatings`
- `TraktSettingsDataStore` -> `trakt` and `integrations.traktAuth`
- `DebugSettingsDataStore` -> `debug`
- `PlayerSettingsDataStore` -> synced portions of `playback`
- `AppLocaleResolver` shared prefs -> `appearance.localeTag`

## Default Account Bootstrap
When a newly created Supabase user has no `account_settings` row and no `account_addons` rows:
- The portal starts with Android-aligned defaults from `nexio-web/utils/portal-defaults.ts`.
- The portal writes those defaults immediately to Supabase through the account RPCs.
- The default addon set is:
  - Cinemeta
  - OpenSubtitles
- This means first-run web accounts begin from the same baseline expected by the Android app instead of an empty account snapshot.

## Trakt Notes
Current implementation:
- The web portal completes Trakt device auth server-side.
- The resulting Trakt profile and token fields are stored in `settings_payload.integrations.traktAuth`.
- Trakt catalog preferences remain in the top-level `trakt` section of the synced settings payload.

Operational note:
- These tokens now live inside JSON synced through `account_settings`.
- If you want stronger separation later, migrate them into a dedicated encrypted table and keep only connection state in `settings_payload`.
- The current SQL migration does not require a schema change for this because the tokens are stored inside the existing JSON payload.

## Recommended Rollout
1. Apply the new SQL.
2. Confirm `account_sync_events` is included in the `supabase_realtime` publication.
3. Set `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`, `TRAKT_CLIENT_ID`, and `TRAKT_CLIENT_SECRET` for `nexio-web`.
4. Verify first-login bootstrap seeds defaults for a new Supabase user.
5. Ship Android startup pull for the full account snapshot.
6. Ship Android instant push for synced settings.
7. Add Realtime subscription to `account_sync_events`.
8. After that is stable, delete or archive the unused legacy sync paths.

