# Supabase Clean Setup Guide For Nexio

This guide is for a brand new, empty Supabase project.

It does **not** assume any legacy tables, no data migration, and no backfill.
The target is the hardened Supabase-only model where:
- public account settings sync stays in normal tables
- public addon inventory stays in normal tables
- user secrets are stored through Supabase Vault and only exposed through server-side routes/RPCs

## Security Boundary
This setup protects against:
- plaintext secrets in public sync payloads
- accidental exposure through app tables and exports
- overbroad client reads
- most logging and operational mistakes

This setup does **not** protect against:
- a fully trusted Supabase project admin
- a database maintainer with full project control

Supabase project admins remain in the trust boundary.

## What Gets Synced
Public sync payload:
- appearance settings
- layout settings
- playback settings, excluding device-specific values
- plugin repositories
- Trakt catalog preferences
- MDBList catalog preferences
- addon enablement, ordering, and public metadata

Not synced:
- library
- watch progress
- watched items
- device-specific playback tuning
- plaintext API keys or OAuth tokens
- credential-bearing addon URLs

## What Is Stored As Secrets
Store these through the secret channel immediately:
- addon credentials embedded in addon URLs
- MDBList API key
- RPDB API key
- TOP Posters API key
- Trakt access token
- Trakt refresh token

## Prerequisites
1. Create a new Supabase project.
2. Enable `Automatic RLS` during project creation.
3. Configure your auth provider.
   - Email/password is enough for the current portal flow.
4. Create a server-side secret key for the web portal.
   - Use this as `SUPABASE_SERVICE_ROLE_KEY` in `nexio-web`.

## SQL Setup
Run the clean install SQL from:
- `supabase/account_settings_sync.sql`

Apply it in the Supabase SQL editor against the empty project.

What it creates:
- `account_settings_public`
- `account_addons_public`
- `account_secrets`
- `account_secret_audit`
- `account_sync_events`
- owner-resolution helpers for linked devices
- public snapshot/persist RPCs
- service-role-only secret RPCs
- realtime publication for `account_sync_events`
- Supabase Vault extension usage for secret payload storage

## Supabase Features Used
This setup relies on:
- Auth
- Row Level Security
- Realtime publication for `account_sync_events`
- Supabase Vault (`supabase_vault` extension)

## Environment Variables
### `nexio-web`
Set these runtime variables:
- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `SUPABASE_SERVICE_ROLE_KEY`
- `TRAKT_CLIENT_ID`
- `TRAKT_CLIENT_SECRET`

Notes:
- `SUPABASE_SERVICE_ROLE_KEY` must stay server-side only.
- The portal server uses it for secret set/delete/resolve RPCs.
- Clients should never receive the service key.

## Public Table Model
### `account_settings_public`
One row per account owner.

Contains:
- scrubbed settings JSON only
- sync revision
- timestamps
- source label

No secrets are stored in this payload.

### `account_addons_public`
One row per synced addon entry.

Contains:
- public base URL
- public manifest URL
- display metadata
- enabled state
- sort order
- non-secret query params
- optional `secret_ref`

Do not store credential-bearing addon URLs here.

### `account_secrets`
Metadata table only.

Contains:
- secret identity
- secret reference
- Vault secret id
- masked preview
- status
- version
- timestamps

Plaintext secret material is not stored in this table.
The actual secret payload is stored in Supabase Vault.

## RPC Contract
### Authenticated RPCs
These are called with the user JWT:
- `sync_pull_account_snapshot()`
- `sync_push_account_settings(p_settings_payload jsonb, p_source text)`
- `sync_push_account_addons(p_addons jsonb, p_source text)`

### Service-role-only RPCs
These are called by server-side code only:
- `service_set_account_secret(...)`
- `service_delete_account_secret(...)`
- `service_resolve_account_secret(...)`
- `service_get_account_addon_transport(...)`

Do not expose these directly to clients.

## Web Portal Contract
### Public bootstrap and persist
The portal now uses:
- `GET /api/account/bootstrap`
  - loads public settings snapshot
  - loads public addons snapshot
  - loads secret metadata rows
  - seeds default public rows for new accounts
- `POST /api/account/persist`
  - writes scrubbed public settings
  - writes public addon inventory

### Secret endpoints
The portal also uses:
- `GET /api/account/secrets/status`
- `POST /api/account/secrets/set`
- `POST /api/account/secrets/delete`
- `POST /api/account/addons/resolve`

### Integration routes
- `POST /api/integrations/mdblist/validate`
  - validates a draft key or the stored secret
- `POST /api/integrations/trakt/device-code`
  - starts device auth
- `POST /api/integrations/trakt/device-token`
  - stores Trakt tokens as secrets
  - returns only public Trakt profile state
- `POST /api/integrations/trakt/popular-lists`
  - resolves the stored Trakt access token server-side
- `POST /api/addons/inspect`
  - resolves credentialed addon manifests server-side when needed

## Default Bootstrap Behavior
For a newly created Supabase user with no account rows yet:
- `GET /api/account/bootstrap` seeds default public settings
- `GET /api/account/bootstrap` seeds default public addons
- default addons are:
  - Cinemeta
  - OpenSubtitles
- no secrets are seeded by default

## Addon Secret Rules
When a user pastes a tokenized addon install URL:
1. extract sensitive query params from the URL
2. store the public base URL in `account_addons_public`
3. store the secret query params in Vault through `service_set_account_secret`
4. store only the `secret_ref` in the addon row
5. resolve the final authenticated manifest URL only in controlled server code

This prevents secret leakage through:
- public tables
- sync payloads
- logs
- analytics
- debug dumps

## Android Expectations
Current direction for the addon:
1. pull only the public snapshot on startup
2. do not expect remote plaintext keys or tokens
3. do not wipe local secrets when public snapshot values are missing
4. resolve secrets through controlled endpoints when secret-backed integrations are fully wired

Important tradeoff:
- if fully offline use is required for a secret-backed integration, the Android app will still need secure local storage for that secret on-device

## Clean Install Verification Checklist
After applying the SQL and setting runtime env vars, verify:
1. new user signup works
2. first portal bootstrap creates:
   - one `account_settings_public` row
   - default `account_addons_public` rows
3. changing a normal setting updates only public tables
4. saving an MDBList key creates:
   - one `account_secrets` metadata row
   - one Vault secret
5. saving a tokenized addon URL stores:
   - public base URL in `account_addons_public`
   - masked secret metadata in `account_secrets`
   - no plaintext credential in public addon fields
6. Trakt connect stores tokens as secrets and only public profile state in settings
7. `account_sync_events` receives inserts for public and secret changes
8. no plaintext secrets appear in public snapshot responses

## Operational Notes
- Never log request bodies for secret endpoints.
- Never send `SUPABASE_SERVICE_ROLE_KEY` to the client.
- Keep addon URLs scrubbed before logging or analytics.
- Show only masked previews like `Stored ••••abcd` in the UI.
- Rotate provider credentials by overwriting the Vault secret and incrementing the metadata version.

## Recommended Rollout Order
1. apply `supabase/account_settings_sync.sql`
2. set `nexio-web` runtime env vars
3. deploy the updated portal server routes
4. verify clean bootstrap for a new account
5. verify secret save/delete flows
6. verify tokenized addon handling
7. finish Android secret-resolution wiring

## Source Files In This Repo
Main files for this setup:
- `supabase/account_settings_sync.sql`
- `docs/supabase-settings-sync-guide.md`
- `nexio-web/server/api/account/bootstrap.get.ts`
- `nexio-web/server/api/account/persist.post.ts`
- `nexio-web/server/api/account/secrets/set.post.ts`
- `nexio-web/server/api/account/secrets/delete.post.ts`
- `nexio-web/server/api/account/addons/resolve.post.ts`
- `nexio-web/server/api/integrations/mdblist/validate.post.ts`
- `nexio-web/server/api/integrations/trakt/device-code.post.ts`
- `nexio-web/server/api/integrations/trakt/device-token.post.ts`
- `nexio-web/server/api/integrations/trakt/popular-lists.post.ts`
