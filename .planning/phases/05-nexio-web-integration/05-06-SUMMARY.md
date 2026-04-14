---
phase: 05-nexio-web-integration
plan: 06
subsystem: android
tags: [android, kotlin, supabase, profiles, avatars, auth-sync]

requires:
  - phase: 05-nexio-web-integration
    plan: 01
    provides: Profile CRUD schema, avatar_url contract, and profile_auth_tokens table
provides:
  - WEB-01 Android profile avatar_url propagation
  - WEB-02 Android profile auth token pull from profile_auth_tokens
  - WEB-05 Android profile avatar rendering
affects: [android-profile-sync, android-profile-auth, android-profile-ui]

tech-stack:
  added: [supabase-storage]
  patterns:
    - Supabase Storage plugin installed through the existing SupabaseModule DI provider pattern
    - Per-profile Trakt/Simkl auth writes use explicit profile-index store access
    - Avatar UI surfaces pass UserProfile.avatarUrl into existing Coil-backed ProfileAvatarCircle

key-files:
  created:
    - app/src/main/java/com/nexio/tv/core/sync/ProfileWebSyncService.kt
    - app/src/main/java/com/nexio/tv/ui/components/ProfileAvatarImage.kt
  modified:
    - gradle/libs.versions.toml
    - app/build.gradle.kts
    - app/src/main/java/com/nexio/tv/core/di/SupabaseModule.kt
    - app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt
    - app/src/main/java/com/nexio/tv/domain/model/UserProfile.kt
    - app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt
    - app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt
    - app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt
    - app/src/main/java/com/nexio/tv/data/local/SimklAuthDataStore.kt
    - app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt
    - app/src/main/java/com/nexio/tv/ModernSidebarBlurPanel.kt
    - app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt

key-decisions:
  - "ProfileSyncService omits avatar_url from push payloads when UserProfile.avatarUrl is null, preserving remote web-uploaded avatars until an explicit clear signal exists."
  - "ProfileWebSyncService treats missing profile_auth_tokens rows as no-op and only clears local auth from newer linked=false or revoked_at tombstone rows."
  - "Trakt and Simkl auth stores now expose explicit profile-index read/write helpers while preserving active-profile defaults for existing callers."

requirements-completed: [WEB-01, WEB-02, WEB-03, WEB-04, WEB-05]

duration: 11 min
completed: 2026-04-14
---

# Phase 05 Plan 06: Android Web Integration Summary

**Android Supabase avatar propagation, web-originated profile auth token sync, and TV avatar rendering**

## Performance

- **Duration:** 11 min
- **Started:** 2026-04-14T19:30:37Z
- **Completed:** 2026-04-14T19:40:54Z
- **Tasks:** 3
- **Files modified:** 14

## Accomplishments

- Installed the Supabase Storage client plugin and exposed it through Hilt.
- Added nullable `avatarUrl` to Supabase and domain profile models.
- Updated profile pull and push behavior so Android receives `avatar_url` while preserving remote web avatars when local `avatarUrl` is null.
- Added `ProfileWebSyncService` to pull per-profile `profile_auth_tokens` rows with `JsonObject` token payload decoding.
- Wired web token sync into startup profile-state hydration and profile-switch events.
- Applied newer linked Trakt/Simkl token rows to local per-profile auth stores and propagated newer unlink tombstones by clearing local auth.
- Passed `profile.avatarUrl` into profile selection, sidebar profile switcher, and settings header avatar rendering.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add Storage dependency and propagate avatarUrl through profile models** - `20f6995cb` (feat)
2. **Task 2: Create ProfileWebSyncService and wire it into launch/profile-switch sync** - `8c6fe1e91` (feat)
3. **Task 3: Wire avatar rendering into TV profile surfaces** - `26c84fde3` (feat)

**Plan metadata:** committed separately in the final docs commit

## Files Created/Modified

- `gradle/libs.versions.toml` - added `supabase-storage`.
- `app/build.gradle.kts` - added `implementation(libs.supabase.storage)`.
- `app/src/main/java/com/nexio/tv/core/di/SupabaseModule.kt` - installed Storage and provided `Storage`.
- `app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt` - added `SupabaseProfile.avatarUrl`.
- `app/src/main/java/com/nexio/tv/domain/model/UserProfile.kt` - added nullable `avatarUrl`.
- `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt` - maps remote `avatar_url` and omits null local avatar URLs from push payloads.
- `app/src/main/java/com/nexio/tv/core/sync/ProfileWebSyncService.kt` - pulls and applies web-originated profile auth token rows.
- `app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt` - invokes web auth sync on startup and profile switch.
- `app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt` - added explicit profile-index state/write helpers.
- `app/src/main/java/com/nexio/tv/data/local/SimklAuthDataStore.kt` - added explicit profile-index state/write helpers.
- `app/src/main/java/com/nexio/tv/ui/components/ProfileAvatarImage.kt` - added thin reusable avatar wrapper.
- `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt` - passes `profile.avatarUrl`.
- `app/src/main/java/com/nexio/tv/ModernSidebarBlurPanel.kt` - passes active and switcher profile avatar URLs.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt` - passes settings header avatar URL.

## Decisions Made

- Preserved remote avatar URLs by omission rather than sending `JsonNull`, because TV has no explicit avatar clear flow in Phase 5.
- Kept `ProfileWebSyncService` failure-visible: Postgrest failures return `Result.failure` instead of silently treating them as empty token rows.
- Supported both camelCase token payload keys from the web API and snake_case token keys from Phase 5 contract tests.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Added profile-index auth store helpers**
- **Found during:** Task 2
- **Issue:** Existing Trakt and Simkl auth write helpers defaulted to the active profile, but the planned service accepts an explicit `profileIndex` and must write or clear that supplied profile deterministically.
- **Fix:** Added `stateForProfile(profileId)` plus defaulted profile-index parameters to token/user/device-flow/auth helpers while preserving existing active-profile call sites.
- **Files modified:** `app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt`, `app/src/main/java/com/nexio/tv/data/local/SimklAuthDataStore.kt`
- **Verification:** `./gradlew compileArm64DebugKotlin` and `./gradlew assembleArm64Debug` passed.
- **Committed in:** `8c6fe1e91`

---

**Total deviations:** 1 auto-fixed missing critical functionality
**Impact on plan:** Required to satisfy the per-profile auth-token sync contract. Existing callers keep the same behavior through default arguments.

## Issues Encountered

- `./gradlew compileArm64DebugKotlin` passed, but Gradle first failed to start the Kotlin daemon because the local JVM rejects `ZGenerational`; it then succeeded using non-daemon fallback. This matches the known local environment behavior from prior Phase 5 summaries.
- `./gradlew assembleArm64Debug` passed with the same Kotlin daemon fallback and pre-existing warnings in unrelated player/spool files.
- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileAuthSyncTest" --tests "com.nexio.tv.profile.ProfileAvatarTest" -x lint` failed before running the requested tests at `:app:compileArm64DebugUnitTestKotlin` due to unrelated stale test constructors and mocks, including `ProfileManagerTest`, `PlayerSettingsDataStoreTest`, `PlayerSettingsDataStoreSpoolModeTest`, `SearchHistoryDataStoreTest`, `ThemeDataStoreProfileTest`, `SearchViewModelHistoryTest`, `CatalogSelectionPersistenceTest`, `SimklViewModelTest`, and `TraktViewModelPriorityHydrationTest`.

## Verification

- Passed: Task 1 grep checks for `storage-kt`, `supabase.storage`, `install(Storage)`, `avatar_url`, `avatarUrl`, and absence of `avatar_url` plus `JsonNull`.
- Passed: Task 2 grep checks for `ProfileWebSyncService.kt`, `JsonObject`/`JsonElement`, `profile_auth_tokens`, and startup `profileWebSyncService.syncActiveProfile`.
- Passed: Task 3 grep checks for `ProfileAvatarImage.kt` and `avatarUrl` usage in all three profile UI surfaces.
- Passed: `./gradlew compileArm64DebugKotlin`.
- Passed: `./gradlew assembleArm64Debug`.
- Blocked by unrelated test compile failures: targeted unit-test command listed above.

## User Setup Required

None.

## Known Stubs

None. The nullable defaults added here are compatibility defaults and fallback behavior, not placeholder data sources.

## Threat Flags

None beyond the plan threat model. The new Supabase token read and public avatar rendering surfaces were explicitly covered by T-05-18 through T-05-21.

## Next Phase Readiness

The TV app now has the Android-side integration layer needed for web-created profile avatars and web-linked profile auth state to appear after launch or profile switch. Remaining validation should focus on live Supabase rows from nexio-web once the web auth/profile flows are exercised end to end.

## Self-Check: PASSED

- Found `.planning/phases/05-nexio-web-integration/05-06-SUMMARY.md`
- Found `app/src/main/java/com/nexio/tv/core/sync/ProfileWebSyncService.kt`
- Found `app/src/main/java/com/nexio/tv/ui/components/ProfileAvatarImage.kt`
- Found commits `20f6995cb`, `8c6fe1e91`, and `26c84fde3`
- Verified unrelated dirty files remain unstaged and outside plan commits
