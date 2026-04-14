---
phase: 05-nexio-web-integration
plan: 04
subsystem: ui
tags: [nuxt, vue, supabase, profiles, catalog-ordering, formatter-settings]

requires:
  - phase: 05-nexio-web-integration
    plan: 02
    provides: useProfileStore and ProfileDetailShell profile tab scaffold
  - phase: 05-nexio-web-integration
    plan: 03
    provides: AuthPanel profile-mode wiring for the Auth tab
provides:
  - WEB-03 profile-scoped catalog settings load/save routes using Phase 4 blob RPCs
  - WEB-03 ProfileCatalogsTab wrapper around CatalogInventory with per-profile settings
  - WEB-04 ProfileFormatterTab wrapper around FormatterWorkspace with per-profile settings
  - ProfileDetailShell Auth, Catalogs, and Formatter tabs wired to real components
affects: [phase-05-nexio-web-integration, nexio-web-profile-settings, android-profile-settings-sync]

tech-stack:
  added: []
  patterns:
    - Profile settings routes use bearer-authenticated Supabase RPC calls scoped by profileIndex
    - Profile tab wrappers reuse account-level components while sourcing mutable settings from profileSettings
    - Profile settings blobs are sanitized to the PortalSettings shape before UI rendering

key-files:
  created:
    - nexio-web/server/api/account/profiles/settings.get.ts
    - nexio-web/server/api/account/profiles/settings.post.ts
    - nexio-web/components/portal/ProfileCatalogsTab.vue
    - nexio-web/components/portal/ProfileFormatterTab.vue
  modified:
    - nexio-web/composables/useProfileStore.ts
    - nexio-web/components/portal/ProfileDetailShell.vue

key-decisions:
  - "Reused the existing portal session bearer-token pattern in useProfileStore instead of adding useSupabaseClient, matching the actual nexio-web auth architecture."
  - "Sanitized loaded profile settings blobs through sanitizePortalSettings before rendering CatalogInventory or FormatterWorkspace."
  - "Saved formatter profile changes immediately from ProfileFormatterTab because FormatterWorkspace emits update events but does not emit persist for template changes."

patterns-established:
  - "Profile settings state is cached by profile_index under useProfileStore.profileSettings."
  - "Catalog tab mutators persist changed profile home catalog order and disabled keys through /api/account/profiles/settings."
  - "Profile detail tabs should wrap shared account components rather than duplicating their UI."

requirements-completed: [WEB-03, WEB-04]

duration: 8 min
completed: 2026-04-14
---

# Phase 05 Plan 04: Profile Settings Tabs Summary

**Profile-scoped catalog ordering and formatter settings in nexio-web using Phase 4 settings blob RPCs**

## Performance

- **Duration:** 8 min
- **Started:** 2026-04-14T19:57:59Z
- **Completed:** 2026-04-14T20:05:21Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments

- Added authenticated profile settings load/save routes backed by `sync_pull_profile_settings_blob` and `sync_push_profile_settings_blob`.
- Extended `useProfileStore` with per-profile settings cache, sanitized load/save helpers, profile catalog inventory derivation, and profile catalog mutators.
- Added `ProfileCatalogsTab` and `ProfileFormatterTab` wrappers that reuse the existing CatalogInventory and FormatterWorkspace components with profile-scoped settings.
- Replaced ProfileDetailShell catalog and formatter placeholders with real tab content while preserving the Plan 03 AuthPanel profile-mode tab.

## Task Commits

Each task was committed atomically:

1. **Task 1: Create per-profile settings load/save server routes and add settings methods to useProfileStore** - `nexio-web@101c82a`, `root@a9cfc2b9f` (feat)
2. **Task 2: Create ProfileCatalogsTab, ProfileFormatterTab, and wire all tabs into ProfileDetailShell** - `nexio-web@d9c9544`, `root@6da2fec56` (feat)

**Plan metadata:** committed separately in the final docs commit

## Files Created/Modified

- `nexio-web/server/api/account/profiles/settings.get.ts` - Loads per-profile settings through `sync_pull_profile_settings_blob` after bearer auth and profileIndex validation.
- `nexio-web/server/api/account/profiles/settings.post.ts` - Saves per-profile settings through `sync_push_profile_settings_blob` after bearer auth and payload validation.
- `nexio-web/composables/useProfileStore.ts` - Adds profile settings state, settings load/save/update helpers, and profile-scoped catalog ordering/toggle methods.
- `nexio-web/components/portal/ProfileCatalogsTab.vue` - Loads profile settings and renders CatalogInventory with profile catalog order and disabled keys.
- `nexio-web/components/portal/ProfileFormatterTab.vue` - Loads profile settings and renders FormatterWorkspace with profile formatter settings.
- `nexio-web/components/portal/ProfileDetailShell.vue` - Renders AuthPanel, ProfileCatalogsTab, and ProfileFormatterTab as real tab bodies.

## Decisions Made

- Used `usePortalStore().state.value.session?.accessToken` through the existing `authHeaders()` helper because the web app already authenticates account APIs through the portal session and does not use a Nuxt Supabase composable module.
- Sanitized loaded settings blobs with `sanitizePortalSettings` so partial or legacy profile settings still render complete CatalogInventory and FormatterWorkspace props.
- Kept addon inventory account-scoped in ProfileCatalogsTab because addons are shared across profiles; only catalog visibility/order is profile-scoped.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Used existing portal session auth instead of useSupabaseClient**
- **Found during:** Task 1 (Create per-profile settings load/save server routes and add settings methods to useProfileStore)
- **Issue:** The plan's helper example used `useSupabaseClient()`, but nexio-web follows the custom portal session pattern from Plan 02 and does not expose that composable.
- **Fix:** Reused the existing `authHeaders()` helper, preserving bearer-token API calls without adding an unavailable dependency.
- **Files modified:** `nexio-web/composables/useProfileStore.ts`
- **Verification:** Task 1 acceptance grep passed and `npm run build` in `nexio-web` completed successfully.
- **Committed in:** `nexio-web@101c82a`, `root@a9cfc2b9f`

**2. [Rule 2 - Missing Critical] Persisted formatter updates from the profile wrapper**
- **Found during:** Task 2 (Create ProfileCatalogsTab, ProfileFormatterTab, and wire all tabs into ProfileDetailShell)
- **Issue:** FormatterWorkspace emits `update` for template changes but does not emit `persist`; account-level persistence relies on portal-store autosave, which profileSettings does not have.
- **Fix:** ProfileFormatterTab saves profile settings immediately after each formatter update so non-default profile formatter changes persist through the profile blob route.
- **Files modified:** `nexio-web/components/portal/ProfileFormatterTab.vue`
- **Verification:** Task 2 acceptance grep passed and `npm run build` in `nexio-web` completed successfully.
- **Committed in:** `nexio-web@d9c9544`, `root@6da2fec56`

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 missing critical functionality)
**Impact on plan:** Both changes preserve the planned behavior while matching the actual app auth and persistence architecture.

## Issues Encountered

- `npm run build` passed after both tasks. It still reports pre-existing Nuxt warnings for duplicate `PortalFormatterRichText` component resolution, duplicate formatter icon token imports, and a large client chunk.
- Live browser verification was not run because the profile tabs require a signed-in portal session and live Supabase profile settings RPCs.

## Verification

- Passed: settings GET and POST routes exist.
- Passed: settings GET validates `profileIndex` and calls `sync_pull_profile_settings_blob`.
- Passed: settings POST validates `profileIndex`, rejects missing settings payloads, and calls `sync_push_profile_settings_blob`.
- Passed: `useProfileStore.ts` contains `profileSettings`, `loadProfileSettings`, `saveProfileSettings`, `updateProfileSetting`, and `getProfileCatalogInventory`.
- Passed: ProfileCatalogsTab imports CatalogInventory, accepts `profileIndex`, and calls `loadProfileSettings`.
- Passed: ProfileFormatterTab imports FormatterWorkspace and accepts `profileIndex`.
- Passed: ProfileDetailShell imports AuthPanel, ProfileCatalogsTab, and ProfileFormatterTab.
- Passed: ProfileDetailShell no longer contains Plan 03 or Plan 04 placeholder text.
- Passed: `npm run build` in `nexio-web`.

## User Setup Required

None.

## Known Stubs

None. The loading and error text in the tab wrappers are operational states, not placeholder data sources.

## Threat Flags

None beyond the plan threat model. The new profile settings endpoints were in scope and validate profileIndex before invoking auth-scoped Supabase RPCs.

## Next Phase Readiness

Plan 05 can build on the profile settings API and tab wrappers for any remaining web-profile management work. Android profile settings sync can rely on the same Phase 4 blob RPC names used here.

## Self-Check: PASSED

- Found `.planning/phases/05-nexio-web-integration/05-04-SUMMARY.md`
- Found `nexio-web/server/api/account/profiles/settings.get.ts`
- Found `nexio-web/server/api/account/profiles/settings.post.ts`
- Found `nexio-web/components/portal/ProfileCatalogsTab.vue`
- Found `nexio-web/components/portal/ProfileFormatterTab.vue`
- Found root commits `a9cfc2b9f` and `6da2fec56`
- Found submodule commits `nexio-web@101c82a` and `nexio-web@d9c9544`
- Verified unrelated dirty files remain unstaged and outside plan commits

---
*Phase: 05-nexio-web-integration*
*Completed: 2026-04-14*
