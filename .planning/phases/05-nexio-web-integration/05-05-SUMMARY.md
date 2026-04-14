---
phase: 05-nexio-web-integration
plan: 05
subsystem: ui
tags: [nuxt, vue, supabase, storage, profiles, avatars, sharp]

requires:
  - phase: 05-nexio-web-integration
    plan: 01
    provides: profile_upsert contract with p_avatar_url and p_clear_avatar
  - phase: 05-nexio-web-integration
    plan: 02
    provides: useProfileStore, ProfileCard, and ProfileDetailShell profile management UI
  - phase: 05-nexio-web-integration
    plan: 04
    provides: ProfileDetailShell tab wiring that the editor header preserves
provides:
  - WEB-05 profile photo upload and removal through nexio-web
  - Sharp-based 256x256 JPEG avatar resize before Supabase Storage upload
  - Public profile-avatars Storage object paths namespaced by userId/profileIndex
  - ProfileDetailShell avatar click-to-upload UI with inline error and remove action
affects: [phase-05-nexio-web-integration, nexio-web-profiles, android-profile-avatar-sync]

tech-stack:
  added: []
  patterns:
    - Profile avatar mutations use bearer-authenticated account APIs plus server-side Supabase Storage service-role calls
    - Profile photo UI refreshes profiles after upload/delete so ProfileCard and ProfileDetailShell render the updated avatar_url

key-files:
  created:
    - nexio-web/server/api/account/profiles/photo.post.ts
    - nexio-web/server/api/account/profiles/photo.delete.ts
    - nexio-web/components/portal/ProfilePhotoUpload.vue
    - nexio-web/components/portal/ProfileEditorSection.vue
  modified:
    - nexio-web/components/portal/ProfileDetailShell.vue
    - nexio-web/composables/useProfileStore.ts

key-decisions:
  - "Used service-role Supabase Storage REST calls from server routes while deriving object paths only from server-verified user.id and validated profileIndex."
  - "Reused the existing useProfileStore authHeaders helper instead of adding a new getAccessToken helper or Supabase composable."
  - "Kept photo upload state in useProfileStore so the ProfileEditorSection can show shared upload progress and inline errors."

patterns-established:
  - "Profile avatar upload endpoints validate profileIndex, MIME type, size, and sharp image decoding before storage mutation."
  - "Profile detail identity editing is isolated in ProfileEditorSection, keeping ProfileDetailShell focused on back navigation and tabs."
  - "Profile photo removal deletes Storage first, then clears avatar_url through profile_upsert with p_clear_avatar true."

requirements-completed: [WEB-05]

duration: 6 min
completed: 2026-04-14
---

# Phase 05 Plan 05: Profile Photo Upload Summary

**Profile avatars uploaded from nexio-web, resized with sharp, stored in Supabase Storage, and managed from the profile detail header**

## Performance

- **Duration:** 6 min
- **Started:** 2026-04-14T20:20:21Z
- **Completed:** 2026-04-14T20:25:52Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments

- Added `photo.post.ts` to accept multipart avatar uploads, validate JPEG/PNG/WebP input, enforce a 10 MB pre-resize limit, resize to 256x256 JPEG with sharp, upload to `profile-avatars/{userId}/{profileIndex}.jpg`, and update `profiles.avatar_url`.
- Added `photo.delete.ts` to delete the avatar object, treat Storage 404 as idempotent success, and clear `avatar_url` through `profile_upsert` with `p_clear_avatar: true`.
- Added `ProfilePhotoUpload` with hidden file input, hover camera overlay, spinner overlay, remove link, and inline error rendering.
- Added `ProfileEditorSection` and wired it into `ProfileDetailShell` so avatar upload/removal and inline profile name editing live together in the identity header.
- Extended `useProfileStore` with photo upload/removal state and actions that refresh profiles after avatar mutations.

## Task Commits

Each task was committed atomically:

1. **Task 1: Create photo upload and delete server routes with sharp resize** - `nexio-web@bd8a979`, `root@0becc1aca` (feat)
2. **Task 2: Create ProfilePhotoUpload and ProfileEditorSection components, wire into ProfileDetailShell** - `nexio-web@97b5b04`, `root@81219aabd` (feat)

**Plan metadata:** committed separately in the final docs commit

## Files Created/Modified

- `nexio-web/server/api/account/profiles/photo.post.ts` - Multipart avatar upload route with MIME/size validation, sharp resize, Supabase Storage upload, and profile_upsert avatar URL update.
- `nexio-web/server/api/account/profiles/photo.delete.ts` - Avatar delete route with Storage cleanup, 404 idempotency, and explicit `p_clear_avatar: true` profile update.
- `nexio-web/components/portal/ProfilePhotoUpload.vue` - Clickable avatar upload control with Material Symbols camera overlay, spinner, remove action, and inline error display.
- `nexio-web/components/portal/ProfileEditorSection.vue` - Profile detail identity header combining photo upload/removal with inline name editing.
- `nexio-web/components/portal/ProfileDetailShell.vue` - Replaced inline avatar/name markup with ProfileEditorSection while preserving Auth/Catalogs/Formatter tabs.
- `nexio-web/composables/useProfileStore.ts` - Added `photoUploading`, `photoError`, `uploadProfilePhoto`, `removeProfilePhoto`, and better API status message extraction for inline errors.

## Decisions Made

- Used server-side service-role Storage calls for upload/delete, but never accepted user-controlled path components; the path is always built from `supabaseUser(event).id` and validated `profileIndex`.
- Added the Storage `apikey` header and explicit service-role config checks because Supabase Storage REST requests require proper auth headers to succeed reliably.
- Reused `authHeaders()` from `useProfileStore` because the web app already stores the portal session access token there; introducing a new `getAccessToken()` helper would duplicate the existing auth pattern.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Added complete Supabase Storage REST auth headers**
- **Found during:** Task 1 (Create photo upload and delete server routes with sharp resize)
- **Issue:** The plan sketch showed only an Authorization header for Storage calls. Supabase Storage REST calls need the API key header as well in this app's server-side pattern, and missing service-role config should fail clearly.
- **Fix:** Added `apikey: config.serviceRoleKey`, `Authorization: Bearer ...`, and a 503 guard when storage config is missing.
- **Files modified:** `nexio-web/server/api/account/profiles/photo.post.ts`, `nexio-web/server/api/account/profiles/photo.delete.ts`
- **Verification:** Task 1 grep checks passed and `npm run build` completed successfully.
- **Committed in:** `nexio-web@bd8a979`, `root@0becc1aca`

**2. [Rule 3 - Blocking] Used existing profile store authHeaders instead of unavailable getAccessToken**
- **Found during:** Task 2 (Create ProfilePhotoUpload and ProfileEditorSection components, wire into ProfileDetailShell)
- **Issue:** The plan sketch referenced `getAccessToken()`, but `useProfileStore` already uses `authHeaders()` backed by `usePortalStore().state.value.session?.accessToken`.
- **Fix:** Implemented photo upload/delete actions with `authHeaders()` to match the actual account API auth architecture.
- **Files modified:** `nexio-web/composables/useProfileStore.ts`
- **Verification:** Task 2 grep checks passed and `npm run build` completed successfully.
- **Committed in:** `nexio-web@97b5b04`, `root@81219aabd`

---

**Total deviations:** 2 auto-fixed (1 missing critical functionality, 1 blocking)
**Impact on plan:** Both changes preserve the planned behavior while making the routes and store actions match the app's existing auth and Supabase patterns.

## Issues Encountered

- `npm run build` passed after each task. It still reports pre-existing Nuxt warnings for duplicate `PortalFormatterRichText` component resolution, duplicate formatter icon token imports, and a large client chunk.
- Live browser upload verification was not run because the profile detail screen requires a signed-in portal session and live Supabase Storage credentials.

## Verification

- Passed: Task 1 route existence and grep checks for `sharp`, `profile-avatars`, `deleteResponse.ok`, `deleteResponse.status !== 404`, and `p_clear_avatar: true`.
- Passed: Task 2 component/store grep checks for `ProfileEditorSection`, `uploadProfilePhoto`, `removeProfilePhoto`, `photo_camera`, accepted MIME types, `bg-black/50 rounded-full`, `animate-spin`, `Remove photo`, inline error classes, and `photoUploading`.
- Passed: `npm run build` in `nexio-web` after Task 1.
- Passed: `npm run build` in `nexio-web` after Task 2.

## User Setup Required

None. The implementation uses the existing nexio-web Supabase runtime config and the existing `profile-avatars` bucket contract.

## Known Stubs

None. The stub scan only found normal `null` state resets and empty-array defaults in existing store logic.

## Threat Flags

None beyond the plan threat model. The new upload and delete endpoints were in scope, validate `profileIndex`, validate image MIME/type/size, rely on sharp decoding, and derive Storage paths from server-verified identity.

## Next Phase Readiness

WEB-05 is ready for human/live-service verification with a signed-in nexio-web account and configured Supabase Storage. Downstream Android or sync work can rely on profile avatar URLs being persisted in `profiles.avatar_url`.

## Self-Check: PASSED

- Found `.planning/phases/05-nexio-web-integration/05-05-SUMMARY.md`
- Found `nexio-web/server/api/account/profiles/photo.post.ts`
- Found `nexio-web/server/api/account/profiles/photo.delete.ts`
- Found `nexio-web/components/portal/ProfilePhotoUpload.vue`
- Found `nexio-web/components/portal/ProfileEditorSection.vue`
- Found root commits `0becc1aca` and `81219aabd`
- Found submodule commits `nexio-web@bd8a979` and `nexio-web@97b5b04`
- Verified unrelated dirty files remain unstaged and outside plan commits

---
*Phase: 05-nexio-web-integration*
*Completed: 2026-04-14*
