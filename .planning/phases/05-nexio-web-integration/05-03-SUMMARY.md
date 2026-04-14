---
phase: 05-nexio-web-integration
plan: 03
subsystem: auth
tags: [nuxt, vue, oauth, trakt, simkl, supabase, profiles]

requires:
  - phase: 05-nexio-web-integration
    plan: 01
    provides: Profile auth token table, profile_auth_status, and service-role token mutation RPCs
  - phase: 05-nexio-web-integration
    plan: 02
    provides: useProfileStore and ProfileDetailShell profile tab scaffold
provides:
  - WEB-02 per-profile browser OAuth routes for Trakt and Simkl
  - WEB-02 signed OAuth state binding user id, profile index, provider, nonce, and expiry
  - WEB-02 Trakt revoke-before-unlink route with Supabase unlink tombstones
  - WEB-02 Simkl token-tombstone unlink per explicit D-12 exception decision
  - WEB-02 AuthPanel profile-mode UI and profile auth store helpers
affects: [phase-05-nexio-web-integration, nexio-web-profile-auth, android-profile-auth-sync]

tech-stack:
  added: []
  patterns:
    - HMAC-signed opaque OAuth state in Nuxt server routes
    - Authenticated authorize-start POST returning provider authorizeUrl
    - Profile auth status cached by profile_index in useProfileStore

key-files:
  created:
    - nexio-web/server/api/account/profiles/auth-status.post.ts
    - nexio-web/server/api/integrations/profiles/trakt/authorize.post.ts
    - nexio-web/server/api/integrations/profiles/trakt/callback.get.ts
    - nexio-web/server/api/integrations/profiles/trakt/disconnect.post.ts
    - nexio-web/server/api/integrations/profiles/simkl/authorize.post.ts
    - nexio-web/server/api/integrations/profiles/simkl/callback.get.ts
    - nexio-web/server/api/integrations/profiles/simkl/disconnect.post.ts
  modified:
    - nexio-web/components/portal/AuthPanel.vue
    - nexio-web/components/portal/ProfileDetailShell.vue
    - nexio-web/composables/useProfileStore.ts

key-decisions:
  - "Simkl unlink is token-tombstone-only for WEB-02 because the user explicitly changed D-12 for Simkl after local code and official Simkl API docs showed no provider revoke endpoint."
  - "Trakt unlink still requires a successful https://api.trakt.tv/oauth/revoke call before service_delete_profile_auth_tokens writes unlink tombstones."
  - "Browser OAuth callbacks verify signed state and use the recovered userId/profileIndex because provider redirects do not include the Supabase bearer header."

patterns-established:
  - "Per-profile web OAuth starts with authenticated POST routes that never put Supabase JWTs in URLs."
  - "Profile auth UI reuses AuthPanel.vue with a profileMode branch instead of creating an auth-tab component."

requirements-completed: [WEB-02]

duration: 11 min
completed: 2026-04-14
---

# Phase 05 Plan 03: Per-Profile Web Auth Summary

**Profile-scoped Trakt and Simkl browser OAuth with signed callback state, Trakt revoke enforcement, and AuthPanel profile-mode controls**

## Performance

- **Duration:** 11 min active execution after decision checkpoint
- **Started:** 2026-04-14T19:43:57Z
- **Completed:** 2026-04-14T19:55:05Z
- **Tasks:** 3
- **Files modified:** 11

## Accomplishments

- Resolved the blocking Simkl revoke gate with an explicit user decision allowing token-tombstone-only Simkl unlink.
- Added profile-scoped Trakt and Simkl authorize/callback/disconnect routes plus profile auth-status lookup.
- Bound OAuth callback trust to signed state containing provider, user id, profile index, nonce, and expiry.
- Enforced Trakt provider revoke before Supabase unlink tombstones.
- Extended AuthPanel.vue for profile-mode link/unlink controls and wired ProfileDetailShell to render it for the Auth tab.

## Task Commits

Each implementation task was committed atomically:

1. **Task 1: Resolve Simkl revoke contract before Simkl disconnect implementation** - no commit; resolved by explicit user decision.
2. **Task 2: Create per-profile browser OAuth routes and auth status route** - `nexio-web@2231a65`, `root@49ee4e8db` (feat)
3. **Task 3: Extend AuthPanel.vue and useProfileStore for per-profile auth UI** - `nexio-web@9fdad93`, `root@67d60c0c6` (feat)

**Plan metadata:** committed separately in the final docs commit

## Files Created/Modified

- `nexio-web/server/api/account/profiles/auth-status.post.ts` - Authenticated per-profile token metadata lookup through `profile_auth_status`, with no token payload returned.
- `nexio-web/server/api/integrations/profiles/trakt/authorize.post.ts` - Authenticated Trakt authorize URL creation with signed state.
- `nexio-web/server/api/integrations/profiles/trakt/callback.get.ts` - Trakt browser OAuth callback, state verification, token exchange, user settings fetch, and profile token storage.
- `nexio-web/server/api/integrations/profiles/trakt/disconnect.post.ts` - Trakt profile unlink route that reads the profile token, calls `oauth/revoke`, then writes Supabase unlink tombstones.
- `nexio-web/server/api/integrations/profiles/simkl/authorize.post.ts` - Authenticated Simkl authorize URL creation with signed state.
- `nexio-web/server/api/integrations/profiles/simkl/callback.get.ts` - Simkl browser OAuth callback, state verification, token exchange, user settings fetch, and profile token storage.
- `nexio-web/server/api/integrations/profiles/simkl/disconnect.post.ts` - Simkl profile unlink route that writes Supabase unlink tombstones per the explicit D-12 exception.
- `nexio-web/components/portal/AuthPanel.vue` - Existing auth panel extended with profile context chip and profile-mode Trakt/SIMKL link/unlink controls.
- `nexio-web/components/portal/ProfileDetailShell.vue` - Auth tab now renders `AuthPanel` in profile mode and fetches profile auth status.
- `nexio-web/composables/useProfileStore.ts` - Profile auth status cache plus profile Trakt/Simkl redirect and disconnect helpers.

## Decisions Made

- Accepted `decision: allow token-delete-only for Simkl` as a Simkl-only D-12 product/security exception after the checkpoint found no official Simkl revoke endpoint.
- Used `process.env.SIMKL_CLIENT_SECRET` as a server-side fallback for Simkl OAuth token exchange because `nuxt.config.ts` was outside this plan's allowed file scope.
- Redirected successful provider callbacks back to `/account?view=profiles` after profile token storage.

## Deviations from Plan

### User-Approved Scope Decision

**1. Simkl D-12 exception: token tombstones without provider revoke**
- **Found during:** Task 1 (Resolve Simkl revoke contract before Simkl disconnect implementation)
- **Issue:** Local Android code has no Simkl revoke endpoint, and the official Simkl API blueprint documents OAuth authorize/token plus user-managed Connected Apps revocation but no provider API revoke endpoint.
- **Decision:** User explicitly provided `decision: allow token-delete-only for Simkl`.
- **Implementation:** `simkl/disconnect.post.ts` calls `service_delete_profile_auth_tokens` for the `simkl` prefix and documents the decision in-route. No 501 placeholder or fake provider revoke was added.
- **Files modified:** `nexio-web/server/api/integrations/profiles/simkl/disconnect.post.ts`
- **Verification:** `npm run build` passed; static checks confirmed Simkl unlink writes `service_delete_profile_auth_tokens` and Trakt still contains `oauth/revoke`.
- **Committed in:** `nexio-web@2231a65`, `root@49ee4e8db`

---

**Total deviations:** 1 user-approved product/security decision
**Impact on plan:** WEB-02 can complete without inventing a non-existent Simkl revoke endpoint. Trakt's D-12 revoke requirement remains fully enforced.

## Issues Encountered

- `npm run build` passed. It still reports pre-existing Nuxt warnings for duplicate `PortalFormatterRichText` component resolution, duplicate formatter icon token imports, and a large client chunk.
- Visual/browser OAuth verification was not run because it requires live provider app redirect configuration and a signed-in portal session.

## Verification

- Passed: all seven planned server route files exist.
- Passed: no per-profile `device-code.post.ts` or `device-token.post.ts` route was created.
- Passed: `auth-status.post.ts` contains `profile_auth_status` and does not return token payloads.
- Passed: Trakt and Simkl authorize routes require bearer auth, use `supabaseUser`, include `user.id`, validate `profileIndex`, sign state with expiry, and return `authorizeUrl`.
- Passed: Trakt and Simkl callback routes verify signed state, recover trusted `userId` and `profileIndex`, avoid `supabaseUser(event)`, and store tokens via `service_set_profile_auth_token`.
- Passed: Trakt disconnect contains `https://api.trakt.tv/oauth/revoke` before `service_delete_profile_auth_tokens`.
- Passed: Simkl disconnect records the explicit no-revoke decision and writes Supabase unlink tombstones only.
- Passed: AuthPanel profile mode contains `Managing:` and the required secondary chip classes.
- Passed: ProfileDetailShell renders `AuthPanel`; the Plan 03 auth placeholder text was removed.
- Passed: `npm run build` in `nexio-web`.

## User Setup Required

- Simkl browser OAuth token exchange requires `SIMKL_CLIENT_SECRET` in the server environment unless a future plan adds an explicit `simklClientSecret` runtimeConfig key.
- Trakt and Simkl app redirect URIs must match the deployed callback URLs:
  - `/api/integrations/profiles/trakt/callback`
  - `/api/integrations/profiles/simkl/callback`

## Known Stubs

None. The null initializers found during stub scanning are state defaults and error resets, not placeholder UI data sources.

## Threat Flags

None beyond the plan threat model. The new OAuth callback and authenticated profile auth endpoints were in scope and mitigated with bearer checks, profileIndex validation, signed state, service-role RPCs, and Trakt revoke enforcement.

## Next Phase Readiness

Plan 04 can build on the existing profile detail tabs for catalog and formatter configuration. Android Plan 06 can consume profile auth token rows and unlink tombstones produced by these web routes.

## Self-Check: PASSED

- Found `.planning/phases/05-nexio-web-integration/05-03-SUMMARY.md`
- Found all seven per-profile server auth route files.
- Found `nexio-web/components/portal/AuthPanel.vue`
- Found `nexio-web/components/portal/ProfileDetailShell.vue`
- Found `nexio-web/composables/useProfileStore.ts`
- Found root commits `49ee4e8db` and `67d60c0c6`
- Found submodule commits `nexio-web@2231a65` and `nexio-web@9fdad93`
- Verified unrelated dirty files remain unstaged and outside plan commits.

---
*Phase: 05-nexio-web-integration*
*Completed: 2026-04-14*
