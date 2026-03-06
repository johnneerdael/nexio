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
- TMDB API key
- MDBList API key
- RPDB API key
- TOP Posters API key
- Trakt access token
- Trakt refresh token

## Prerequisites
1. Create a new Supabase project.
2. Enable `Automatic RLS` during project creation.
3. Configure auth providers.
   - Enable Email provider.
   - Enable Anonymous Sign-Ins.
   - Recommended for the current portal UX: disable mandatory email confirmation unless you plan to rewrite the sign-up flow.
4. Create a server-side secret key for the web portal.
   - Use this as `NUXT_SUPABASE_SERVICE_ROLE_KEY` in `nexio-web`.

## Required Auth Settings
### Email
Current `nexio-web` account creation expects Supabase sign-up to return a session immediately.

If Email Confirmation is enabled, sign-up can succeed without returning a usable session and the current portal route will treat that as an error.

Recommended for now:
- enable Email auth
- disable required email confirmation

If you want mandatory email confirmation later, the web sign-up flow needs to be changed accordingly.

### Google OAuth
If you enable Google auth for the portal:
- add your public callback URL to Supabase Auth redirect URLs
- for production this should include:
  - `https://nexioapp.org/auth/callback`
- for local development also add your local callback, for example:
  - `http://localhost:3000/auth/callback`
  - `http://localhost:3100/auth/callback`

### Anonymous Sign-Ins
Enable Anonymous Sign-Ins.

Why:
- the Android QR login flow currently calls `auth.signInAnonymously()` before starting `start_tv_login_session`
- this gives the TV/device a temporary authenticated Supabase identity so it can create and poll its login session before it is attached to a real account
- legacy device-link flows also benefit from having a temporary auth user available on unsigned-in TVs

Without Anonymous Sign-Ins:
- QR login startup on Android will fail before `start_tv_login_session`
- device linking on unsigned-in TVs may fail depending on client path

## SQL Setup
Run the clean install SQL from:
- `supabase/account_settings_sync.sql`

Apply it in the Supabase SQL editor against the empty project.

If you already provisioned a project from an older version of this schema and only need the TMDB secret-channel update:
- run `supabase/tmdb_secret_upgrade_patch.sql`
- this patch adds `tmdb_api_key` to the allowed secret types
- it also installs the authenticated `sync_set_account_secret`, `sync_delete_account_secret`, and `sync_resolve_account_secret` RPC wrappers if your project is missing them

What it creates:
- `linked_devices`
- `sync_codes`
- `tv_login_sessions`
- `account_settings_public`
- `account_addons_public`
- `account_secrets`
- `account_secret_audit`
- `account_sync_events`
- owner-resolution helpers for linked devices
- sync-code device-linking RPCs
- QR TV-login RPCs
- public snapshot/persist RPCs
- service-role-only secret RPCs
- realtime publication for `account_sync_events`
- Supabase Vault extension usage for secret payload storage

Important:
- this script now includes the `linked_devices` table required by `sync_owner_id()`
- this script also recreates the current compatibility RPCs used by the Android app and QR approval flow:
  - `generate_sync_code`
  - `get_sync_code`
  - `claim_sync_code`
  - `unlink_device`
  - `start_tv_login_session`
  - `poll_tv_login_session`
  - `approve_tv_login_session`

## Supabase Features Used
This setup relies on:
- Auth
- Row Level Security
- Realtime publication for `account_sync_events`
- Supabase Vault (`supabase_vault` extension)

## Environment Variables
### `nexio-web`
Set these runtime variables:
- `NUXT_SUPABASE_URL`
- `NUXT_SUPABASE_ANON_KEY`
- `NUXT_SUPABASE_SERVICE_ROLE_KEY`
- `NUXT_TRAKT_CLIENT_ID`
- `NUXT_TRAKT_CLIENT_SECRET`

Notes:
- `NUXT_SUPABASE_SERVICE_ROLE_KEY` must stay server-side only.
- The portal server uses it for secret set/delete/resolve RPCs.
- Clients should never receive the service key.
- `nexio-web` reads these through Nuxt runtime config, so the `NUXT_` prefix is required in production.

## Public Table Model
### `linked_devices`
One row per linked TV/device account.

Contains:
- owner account id
- linked device auth user id
- device name
- optional model and platform labels
- last seen timestamp
- device status

The clean install uses this table to resolve account ownership for linked device sessions.

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
- `generate_sync_code(p_pin text)`
- `get_sync_code(p_pin text)`
- `claim_sync_code(p_code text, p_pin text, p_device_name text)`
- `unlink_device(p_device_user_id uuid)`
- `start_tv_login_session(p_device_nonce text, p_redirect_base_url text, p_device_name text default null)`
- `poll_tv_login_session(p_code text, p_device_nonce text)`
- `approve_tv_login_session(p_code text, p_device_nonce text)`
- `sync_pull_account_snapshot()`
- `sync_push_account_settings(p_settings_payload jsonb, p_source text)`
- `sync_push_account_addons(p_addons jsonb, p_source text)`
- `sync_set_account_secret(p_secret_type text, p_secret_ref text, p_secret_payload jsonb, p_masked_preview text, p_status text default 'configured', p_source text default 'app')`
- `sync_delete_account_secret(p_secret_type text, p_secret_ref text, p_source text default 'app')`
- `sync_resolve_account_secret(p_secret_type text, p_secret_ref text, p_source text default 'app')`

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
  - loads linked devices from `linked_devices`
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

## Edge Functions
Current Supabase Edge Function requirement:
- `tv-logins-exchange`

This function is required by the Android QR flow after the web user approves the login request.

Source in this repo:
- `supabase/functions/tv-logins-exchange/index.ts`

What it does:
- validates the device's pending QR session
- verifies the session was approved in `tv_login_sessions`
- mints an owner session through the Supabase auth admin APIs
- upserts `linked_devices`
- marks the QR session as `used`

Important:
- the account portal secret APIs are **not** Supabase Edge Functions
- those are Nuxt server routes inside `nexio-web`
- only the QR exchange path currently depends on a Supabase Edge Function

### Deploying the Edge Function
Using the Supabase CLI:

1. Link the local repo to your project.
2. Set the function secrets.
3. Deploy `tv-logins-exchange`.

Example commands:

```bash
supabase link --project-ref YOUR_PROJECT_REF
supabase secrets set SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
supabase secrets set SUPABASE_ANON_KEY=YOUR_SUPABASE_ANON_KEY
supabase secrets set SUPABASE_SERVICE_ROLE_KEY=YOUR_SUPABASE_SERVICE_ROLE_KEY
supabase functions deploy tv-logins-exchange
```

Important:
- the Supabase Edge Function uses plain function secrets:
  - `SUPABASE_URL`
  - `SUPABASE_ANON_KEY`
  - `SUPABASE_SERVICE_ROLE_KEY`
- this is different from `nexio-web`, which expects `NUXT_*` env names

If you prefer the dashboard:
- create a function named `tv-logins-exchange`
- paste the source from `supabase/functions/tv-logins-exchange/index.ts`
- add the same three secrets in the function environment

### Edge Function JWT Behavior
Keep normal JWT verification enabled.

Why:
- the app calls the function with the device's bearer token
- the function expects an authenticated caller and resolves the current requester from that token
- there is no reason to expose this function publicly without JWT verification

## Realtime Setup
The SQL script already adds `public.account_sync_events` to the `supabase_realtime` publication:
- no extra SQL is required if the script completes successfully

After applying SQL, verify in Supabase:
1. Open `Database` -> `Publications`.
2. Open the `supabase_realtime` publication.
3. Confirm `public.account_sync_events` is listed.

What Realtime is used for:
- account settings sync notifications
- addon inventory sync notifications
- secret-change sync notifications

The current design uses `account_sync_events` as the realtime event table.
Clients do not need realtime on `account_settings_public` or `account_addons_public` directly.

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

Current Android auth/login dependencies on Supabase:
- anonymous auth must be enabled for QR login bootstrap
- `start_tv_login_session` and `poll_tv_login_session` must exist
- Edge Function `tv-logins-exchange` must be deployed
- `approve_tv_login_session` must exist for the web approval page
- legacy device-link support still expects:
  - `generate_sync_code`
  - `get_sync_code`
  - `claim_sync_code`
  - `unlink_device`

## Clean Install Verification Checklist
After applying the SQL and setting runtime env vars, verify:
1. new user signup works
2. anonymous sign-in works from the Android app
3. first portal bootstrap creates:
   - one `account_settings_public` row
   - default `account_addons_public` rows
4. QR login start works and creates a `tv_login_sessions` row
5. approving the QR request through the portal updates that session to `approved`
6. `tv-logins-exchange` returns an owner session and upserts `linked_devices`
7. legacy sync-code linking works if you still ship that UI
8. changing a normal setting updates only public tables
9. saving an MDBList key creates:
   - one `account_secrets` metadata row
   - one Vault secret
10. saving a TMDB key creates:
   - one `account_secrets` metadata row
   - one Vault secret
   - no public TMDB API key in `account_settings_public`
11. saving a tokenized addon URL stores:
   - public base URL in `account_addons_public`
   - masked secret metadata in `account_secrets`
   - no plaintext credential in public addon fields
12. Trakt connect stores tokens as secrets and only public profile state in settings
13. `account_sync_events` receives inserts for public and secret changes
14. no plaintext secrets appear in public snapshot responses

## Operational Notes
- Never log request bodies for secret endpoints.
- Never send `NUXT_SUPABASE_SERVICE_ROLE_KEY` to the client.
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
- `supabase/functions/tv-logins-exchange/index.ts`
- `docs/supabase-settings-sync-guide.md`
- `nexio-web/server/api/account/bootstrap.get.ts`
- `nexio-web/server/api/account/persist.post.ts`
- `nexio-web/server/api/account/approve-tv-login.post.ts`
- `nexio-web/server/api/account/secrets/set.post.ts`
- `nexio-web/server/api/account/secrets/delete.post.ts`
- `nexio-web/server/api/account/addons/resolve.post.ts`
- `nexio-web/server/api/integrations/mdblist/validate.post.ts`
- `nexio-web/server/api/integrations/trakt/device-code.post.ts`
- `nexio-web/server/api/integrations/trakt/device-token.post.ts`
- `nexio-web/server/api/integrations/trakt/popular-lists.post.ts`
