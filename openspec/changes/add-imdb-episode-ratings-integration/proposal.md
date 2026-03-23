# Change: Add account-scoped IMDb episode ratings integration

## Why
Nexio needs a dedicated, account-scoped IMDb integration for episode ratings so the web portal and Android app can sync the active configuration and secret state through the same account settings contract. The current OMDb-backed path does not give us a distinct integration boundary for a custom IMDb endpoint.

## What Changes
- Add a new `integrations.imdb` sync block with `enabled` and `baseUrl`.
- Bump the account-config sync contract to version 3 while keeping version 2 requests supported.
- Extend the Android sync model/service plumbing so the new IMDb settings participate in account-config payloads and remote apply hooks.
- Add a new `imdb_api_key` secret type scoped to `integration:imdb`.
- Update the Supabase contract so v2 responses remain unchanged, v3 responses include IMDb settings, and the new secret type is allowed.
- Preserve custom IMDb as the primary episode-ratings source while active, with no OMDb/TMDB fallback in that mode.

## Impact
- Affected specs: `account-config-sync`
- Affected code: Android sync models/service, Android sync tests, Supabase settings SQL, web portal contract types/defaults
