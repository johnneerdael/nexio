---
phase: 05-nexio-web-integration
plan: 01
subsystem: database
tags: [supabase, rls, storage, nuxt, sharp, profiles]

requires:
  - phase: 05-nexio-web-integration
    provides: WEB-01/WEB-05 Wave 0 profile and avatar contract scaffolds
provides:
  - WEB-01 profile CRUD Supabase schema, RPCs, and Nuxt API routes
  - WEB-05 profile avatar Storage bucket, avatar_url profile column, and sharp dependency foundation
affects: [phase-05-nexio-web-integration, nexio-web-profile-management, android-profile-sync]

tech-stack:
  added: [sharp]
  patterns:
    - Supabase RPCs use auth.uid() for profile ownership filtering
    - Nuxt server routes proxy profile operations with bearerToken, supabaseUser, and supabaseFetch
    - Supabase migrations are timestamp-versioned and retry-safe after partial remote application

key-files:
  created:
    - supabase/migrations/20260414000000_phase5_profile_auth_tokens.sql
    - supabase/migrations/20260414000100_phase5_profile_avatar_storage.sql
    - nexio-web/server/api/account/profiles/index.get.ts
    - nexio-web/server/api/account/profiles/upsert.post.ts
    - nexio-web/server/api/account/profiles/delete.post.ts
  modified:
    - nexio-web/package.json
    - nexio-web/package-lock.json
    - nexio-web
    - .planning/phases/05-nexio-web-integration/05-01-PLAN.md

key-decisions:
  - "Created public.profiles in the Phase 5 migration because the live Supabase database had no profile table despite Phase 4 research documenting the intended schema."
  - "Renamed Phase 5 migrations to unique Supabase timestamp versions after the original same-day names collided in remote migration history."
  - "Made named policy creation retry-safe so partially applied failed pushes can be rerun without duplicate-policy failures."

patterns-established:
  - "Profile upsert preserves avatar_url when p_avatar_url is NULL and p_clear_avatar is false; explicit p_clear_avatar true is required to clear a photo."
  - "Profile deletion refuses profile_index 1, removes profile_auth_tokens, preserves profile_settings cleanup, and deletes the profile-avatars storage object."

requirements-completed: [WEB-01, WEB-05]

duration: 71 min
completed: 2026-04-14
---

# Phase 05 Plan 01: Supabase Profile Foundation Summary

**Supabase profile CRUD, per-profile auth token storage, public avatar bucket, and Nuxt profile API routes with sharp installed**

## Performance

- **Duration:** 71 min
- **Started:** 2026-04-14T18:06:41Z
- **Completed:** 2026-04-14T19:17:58Z
- **Tasks:** 3
- **Files modified:** 9

## Accomplishments

- Added `profile_auth_tokens` with RLS, unlink tombstones, and service-role-only token mutation RPCs.
- Added `public.profiles`, `profile-avatars` Storage bucket policy, `profile_upsert`, `profile_delete`, and `profile_auth_status`.
- Added Nuxt profile list/upsert/delete API routes using the established Supabase bearer-token proxy pattern.
- Installed `sharp` in `nexio-web` for downstream server-side avatar resizing.
- Applied the migrations to the live Supabase project; `supabase migration list` shows `20260414000000` and `20260414000100` present locally and remotely.

## Task Commits

Each task was committed atomically:

1. **Task 1: Create Supabase migration files** - `2a11f77d5` (feat)
2. **Task 2: Create web API routes and install sharp** - `nexio-web@64d71c9`, `root@6a421e9d7` (feat)
3. **Task 3: Push Supabase schema migrations** - live push completed by user, verified with `supabase migration list`

Additional fix commits created while unblocking Task 3:

- `cd6b63346` - added the missing `public.profiles` schema to the avatar/storage migration
- `a9cabddc4` - renamed the avatar/storage migration to a unique Supabase version
- `16be5735e` - renamed the auth-token migration to a unique Supabase version and updated plan references
- `c82881139` - made auth-token policies rerunnable after partial remote application

**Plan metadata:** committed separately in the final docs commit

## Files Created/Modified

- `supabase/migrations/20260414000000_phase5_profile_auth_tokens.sql` - per-profile auth token table, RLS, service set/delete token RPCs, and service-role grants.
- `supabase/migrations/20260414000100_phase5_profile_avatar_storage.sql` - profiles table, avatar_url support, Storage bucket policies, profile CRUD RPCs, and auth-status metadata RPC.
- `nexio-web/server/api/account/profiles/index.get.ts` - authenticated profile list route ordered by profile index.
- `nexio-web/server/api/account/profiles/upsert.post.ts` - validated profile create/update route calling `profile_upsert`.
- `nexio-web/server/api/account/profiles/delete.post.ts` - validated non-primary profile delete route calling `profile_delete`.
- `nexio-web/package.json` and `nexio-web/package-lock.json` - `sharp` dependency.
- `.planning/phases/05-nexio-web-integration/05-01-PLAN.md` - migration filename references updated to valid Supabase timestamp versions.

## Decisions Made

- Added `public.profiles` directly in this plan as Rule 2 missing critical functionality because downstream profile RPCs cannot exist without it and no prior migration created it.
- Used Supabase timestamped migration versions for both Phase 5 migrations so remote history can record them independently.
- Kept partially applied migration recovery in the SQL itself through `IF NOT EXISTS`, `ON CONFLICT`, `pg_policies` guards, and `CREATE OR REPLACE FUNCTION`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Created the missing profiles table**
- **Found during:** Task 3 (Push Supabase schema migrations)
- **Issue:** The live remote had no `public.profiles`, so `profile_upsert RETURNS SETOF public.profiles` failed during the real push.
- **Fix:** Added `public.profiles` with Phase 4/5 profile columns, RLS, and `(user_id, profile_index)` uniqueness before the profile RPC definitions.
- **Files modified:** `supabase/migrations/20260414000100_phase5_profile_avatar_storage.sql`
- **Verification:** Subsequent live push progressed past the missing table error; final migration list shows both Phase 5 migrations remote.
- **Committed in:** `cd6b63346`

**2. [Rule 3 - Blocking] Gave Phase 5 migrations unique Supabase versions**
- **Found during:** Task 3 (Push Supabase schema migrations)
- **Issue:** The original migration filenames shared the same `20260414` version, causing a remote `schema_migrations` primary-key collision.
- **Fix:** Renamed the migrations to `20260414000000_phase5_profile_auth_tokens.sql` and `20260414000100_phase5_profile_avatar_storage.sql`, and updated plan references.
- **Files modified:** `supabase/migrations/20260414000000_phase5_profile_auth_tokens.sql`, `supabase/migrations/20260414000100_phase5_profile_avatar_storage.sql`, `.planning/phases/05-nexio-web-integration/05-01-PLAN.md`
- **Verification:** `supabase migration list` shows both unique versions present locally and remotely.
- **Committed in:** `a9cabddc4`, `16be5735e`

**3. [Rule 3 - Blocking] Made policy creation rerunnable**
- **Found during:** Task 3 (Push Supabase schema migrations)
- **Issue:** Failed remote pushes partially applied objects before migration history was recorded, so retrying could hit duplicate policy errors.
- **Fix:** Guarded policy creation with `pg_policies` checks while preserving the same RLS semantics.
- **Files modified:** `supabase/migrations/20260414000000_phase5_profile_auth_tokens.sql`, `supabase/migrations/20260414000100_phase5_profile_avatar_storage.sql`
- **Verification:** User reran the real `supabase db push`; it finished successfully.
- **Committed in:** `c82881139`

---

**Total deviations:** 3 auto-fixed (1 missing critical, 2 blocking)
**Impact on plan:** All fixes were required to make the planned schema real in the linked Supabase project. No unrelated files were staged.

## Issues Encountered

- `supabase db push --yes` first failed because `public.profiles` did not exist remotely.
- A second push failed because both original migration filenames used the same Supabase migration version prefix.
- After migration-version and retry-safety fixes, the user reran the real push successfully.
- `npm install sharp` reported existing audit findings: 16 vulnerabilities already present in the npm dependency graph. No audit remediation was in scope for this plan.
- `npm run build` in `nexio-web` passed, with pre-existing Nuxt duplicate component/import warnings for formatter files.

## User Setup Required

None - the real Supabase push has completed.

## Known Stubs

None.

## Threat Flags

None beyond the plan threat model. The new public endpoint surface and Storage trust boundary were included in the plan and mitigated with route validation plus Supabase RLS/RPC ownership checks.

## Next Phase Readiness

Phase 5 downstream web UI, auth linking, photo upload, and Android sync plans can now depend on live profile CRUD RPCs, per-profile token storage, `profile_auth_status`, the `profile-avatars` bucket, and Nuxt profile CRUD API routes.

## Self-Check: PASSED

- Found `supabase/migrations/20260414000000_phase5_profile_auth_tokens.sql`
- Found `supabase/migrations/20260414000100_phase5_profile_avatar_storage.sql`
- Found `nexio-web/server/api/account/profiles/index.get.ts`
- Found `nexio-web/server/api/account/profiles/upsert.post.ts`
- Found `nexio-web/server/api/account/profiles/delete.post.ts`
- Found `nexio-web/package.json`
- Found `nexio-web/package-lock.json`
- Found commits `2a11f77d5`, `6a421e9d7`, `cd6b63346`, `a9cabddc4`, `16be5735e`, and `c82881139`
- Found submodule commit `nexio-web@64d71c9`
- Verified `supabase migration list` shows `20260414000000` and `20260414000100` as remote migrations

---
*Phase: 05-nexio-web-integration*
*Completed: 2026-04-14*
