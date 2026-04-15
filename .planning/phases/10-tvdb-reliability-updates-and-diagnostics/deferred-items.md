# Phase 10 Deferred Items

## Pre-existing Issues (Out of Scope)

### 1. PlaybackBufferNetworkSettingsTest compilation error
- **File:** `app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettingsTest.kt:66`
- **Error:** `Unresolved reference 'resolveEffectiveDiskSpoolStorageLocation'`
- **Cause:** Uncommitted local changes in `PlaybackBufferNetworkSettings.kt` and related spool/settings files break test compilation. The function exists as `internal fun` in the main source but the dirty working tree state creates a visibility mismatch.
- **Impact:** Blocks `./gradlew testArm64DebugUnitTest` for ALL test targets when these dirty files are present. Tests pass when these files are stashed to their committed state.
- **Workaround:** Stash the 5 dirty playback files before running tests.
- **Files affected:** `PlaybackBufferNetworkSettings.kt`, `PlaybackBufferNetworkSettingsTest.kt`, `DiskSpoolStorageResolver.kt`, `DiskSpoolStorageResolverTest.kt`, `PlaybackSettingsViewModel.kt`
