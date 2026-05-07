# Player Defaults Safe Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make DV7 HDR10 base-layer playback, VOD cache off, and parallel connections off the default and migrated state for both new and existing profile-scoped player settings.

**Architecture:** Change `PlayerSettings` defaults and `PlayerSettingsDataStore` preference fallbacks, then add a one-time profile-scoped migration inside `applyAllPlayerMigrations(...)`. The migration runs per `ProfileDataStoreFactory` profile store when that profile’s `playerSettings` flow is read, so profile 1 and secondary profiles 2-4 are migrated independently without crossing profile boundaries.

**Tech Stack:** Kotlin, AndroidX Preferences DataStore, profile-scoped `ProfileDataStoreFactory`, Robolectric unit tests, Kotlin coroutines test.

---

## Requirements

- New users:
  - DV7 -> HDR10/base-layer setting is enabled by default.
  - DV7 -> DV8.1 conversion is disabled by default.
  - DV7 preserve-mapping conversion is disabled by default.
  - VOD cache is off by default.
  - parallel connections are off by default.
- Existing users:
  - One-time migration turns those features to the new safe defaults.
  - Migration applies to the default profile and to each secondary profile’s own profile-scoped settings store.
  - Migration must not leak settings between profiles.
- Existing feature interactions still hold:
  - Enabling DV7 HEVC base layer disables DV8.1 conversion and preserve mapping.
  - Enabling DV8.1 conversion disables DV7 HEVC base layer.
  - Enabling VOD cache disables disk spool.
  - Enabling disk spool disables VOD cache.

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - Change defaults in `PlayerSettings`.
  - Change companion object defaults for VOD cache and parallel connections.
  - Add a one-time migration key and migration block in `applyAllPlayerMigrations(...)`.
  - Ensure migration writes to the current profile’s DataStore only.

- Modify: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`
  - Update existing DV7 default test.
  - Add tests for VOD cache and parallel connection defaults.
  - Add tests proving one-time migration turns existing values off.
  - Add profile-boundary tests using `MutableStateFlow<Int>` active profile switching.

- Modify: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt`
  - Update assumptions that currently expect VOD cache default `ON`, if any.

---

### Task 1: Update Default Tests First

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt`

- [ ] **Step 1: Replace the existing DV7 default test**

In `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`, replace:

```kotlin
    @Test
    fun `dv7 hevc base layer defaults off while dv81 conversion stays default`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        val settings = dataStore.playerSettings.first()

        assertEquals(true, settings.experimentalDv7ToDv81Enabled)
        assertEquals(false, settings.experimentalDv7HevcBaseLayerEnabled)
    }
```

with:

```kotlin
    @Test
    fun `safe playback defaults prefer dv7 hevc base layer over dv81 conversion`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        val settings = dataStore.playerSettings.first()

        assertEquals(false, settings.experimentalDv7ToDv81Enabled)
        assertEquals(true, settings.experimentalDv7HevcBaseLayerEnabled)
        assertEquals(false, settings.experimentalDv7ToDv81PreserveMappingEnabled)
    }
```

- [ ] **Step 2: Add VOD cache and parallel connection default tests**

Add these tests to `PlayerSettingsDataStoreTest` after the safe playback default test:

```kotlin
    @Test
    fun `safe playback defaults disable vod cache and parallel connections`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        val settings = dataStore.playerSettings.first()

        assertEquals(VodCacheSizeMode.OFF, settings.vodCacheSizeMode)
        assertEquals(false, settings.useParallelConnections)
    }

    @Test
    fun `player settings data class safe defaults match datastore defaults`() {
        val settings = PlayerSettings()

        assertEquals(false, settings.experimentalDv7ToDv81Enabled)
        assertEquals(true, settings.experimentalDv7HevcBaseLayerEnabled)
        assertEquals(false, settings.experimentalDv7ToDv81PreserveMappingEnabled)
        assertEquals(VodCacheSizeMode.OFF, settings.vodCacheSizeMode)
        assertEquals(false, settings.useParallelConnections)
    }
```

- [ ] **Step 3: Update VOD warm-ahead default test name and assertion if needed**

Find this existing test in `PlayerSettingsDataStoreTest`:

```kotlin
    @Test
    fun `vod cache warm ahead defaults to enabled`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        val settings = dataStore.playerSettings.first()

        assertTrue(settings.vodCacheWarmAheadEnabled)
    }
```

Leave it unchanged. Warm-ahead is harmless when VOD cache mode is off and should keep its stored default unless product asks otherwise.

- [ ] **Step 4: Run tests and verify expected failures**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.data.local.PlayerSettingsDataStoreTest
```

Expected: fails because current defaults still report DV8.1 on, HEVC base-layer off, VOD cache on, and parallel connections on.

- [ ] **Step 5: Commit the failing default tests**

```bash
git add app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt
git commit -m "test(player): pin safe playback defaults"
```

---

### Task 2: Implement New Safe Defaults For New Profiles

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`

- [ ] **Step 1: Change `PlayerSettings` data class defaults**

In `PlayerSettings`, replace:

```kotlin
    // Try native DV7 -> DV8.1 conversion before HEVC fallback.
    val experimentalDv7ToDv81Enabled: Boolean = true,
    // Map DV7 to its HEVC HDR10 base layer on non-Dolby Vision displays.
    val experimentalDv7HevcBaseLayerEnabled: Boolean = false,
```

with:

```kotlin
    // Prefer DV7 HEVC HDR10 base-layer playback by default; DV8.1 conversion remains opt-in.
    val experimentalDv7ToDv81Enabled: Boolean = false,
    // Map DV7 to its HEVC HDR10 base layer on non-Dolby Vision displays.
    val experimentalDv7HevcBaseLayerEnabled: Boolean = true,
```

- [ ] **Step 2: Change VOD cache and parallel connection companion defaults**

In `PlayerSettings.Companion`, replace:

```kotlin
        val DEFAULT_VOD_CACHE_SIZE_MODE: VodCacheSizeMode = VodCacheSizeMode.ON
        const val DEFAULT_USE_PARALLEL_CONNECTIONS = true
```

with:

```kotlin
        val DEFAULT_VOD_CACHE_SIZE_MODE: VodCacheSizeMode = VodCacheSizeMode.OFF
        const val DEFAULT_USE_PARALLEL_CONNECTIONS = false
```

- [ ] **Step 3: Change DataStore fallbacks for DV7 settings**

In `playerSettings: Flow<PlayerSettings>`, replace:

```kotlin
                experimentalDv7HevcBaseLayerEnabled =
                    prefs[experimentalDv7HevcBaseLayerEnabledKey] ?: false,
                experimentalDv7ToDv81Enabled =
                    if (prefs[experimentalDv7HevcBaseLayerEnabledKey] == true) {
                        false
                    } else {
                        prefs[experimentalDv7ToDv81EnabledKey] ?: true
                    },
```

with:

```kotlin
                experimentalDv7HevcBaseLayerEnabled =
                    prefs[experimentalDv7HevcBaseLayerEnabledKey] ?: true,
                experimentalDv7ToDv81Enabled =
                    if (prefs[experimentalDv7HevcBaseLayerEnabledKey] != false) {
                        false
                    } else {
                        prefs[experimentalDv7ToDv81EnabledKey] ?: false
                    },
```

Reason: an absent base-layer key should behave as enabled for new profiles.

- [ ] **Step 4: Keep preserve-mapping fallback disabled**

Leave this block as-is unless compilation requires formatting:

```kotlin
                experimentalDv7ToDv81PreserveMappingEnabled =
                    if (prefs[experimentalDv7HevcBaseLayerEnabledKey] == true) {
                        false
                    } else {
                        prefs[experimentalDv7ToDv81PreserveMappingEnabledKey] ?: false
                    },
```

If Step 3 uses `!= false`, update this block to match absent-as-base-layer-enabled:

```kotlin
                experimentalDv7ToDv81PreserveMappingEnabled =
                    if (prefs[experimentalDv7HevcBaseLayerEnabledKey] != false) {
                        false
                    } else {
                        prefs[experimentalDv7ToDv81PreserveMappingEnabledKey] ?: false
                    },
```

- [ ] **Step 5: Run default tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.data.local.PlayerSettingsDataStoreTest
```

Expected: the new default tests pass. Migration tests added later are not present yet.

- [ ] **Step 6: Commit new defaults**

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt
git commit -m "feat(player): default to safe dv7 hdr10 playback"
```

---

### Task 3: Add One-Time Profile-Scoped Migration Tests

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`

- [ ] **Step 1: Add missing imports**

At the top of `PlayerSettingsDataStoreTest.kt`, add:

```kotlin
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
```

Keep existing imports.

- [ ] **Step 2: Add migration test for existing default profile**

Add this test after the default tests:

```kotlin
    @Test
    fun `safe playback migration disables risky settings for existing default profile`() {
        val dv81Key = booleanPreferencesKey("experimental_dv7_to_dv81_enabled")
        val hevcBaseLayerKey = booleanPreferencesKey("experimental_dv7_hevc_base_layer_enabled")
        val preserveMappingKey = booleanPreferencesKey("experimental_dv7_to_dv81_preserve_mapping_enabled")
        val vodCacheModeKey = stringPreferencesKey("vod_cache_size_mode")
        val parallelConnectionsKey = booleanPreferencesKey("use_parallel_connections")
        val migrationDoneKey = booleanPreferencesKey("migration_safe_playback_defaults_done")
        val prefs = mutablePreferencesOf(
            dv81Key to true,
            hevcBaseLayerKey to false,
            preserveMappingKey to true,
            vodCacheModeKey to VodCacheSizeMode.ON.name,
            parallelConnectionsKey to true
        )

        applyPlayerSettingsMigrations(prefs)

        assertEquals(false, prefs[dv81Key])
        assertEquals(true, prefs[hevcBaseLayerKey])
        assertEquals(false, prefs[preserveMappingKey])
        assertEquals(VodCacheSizeMode.OFF.name, prefs[vodCacheModeKey])
        assertEquals(false, prefs[parallelConnectionsKey])
        assertEquals(true, prefs[migrationDoneKey])
    }
```

- [ ] **Step 3: Add one-time migration non-overwrite test**

Add:

```kotlin
    @Test
    fun `safe playback migration does not override after it has run`() {
        val dv81Key = booleanPreferencesKey("experimental_dv7_to_dv81_enabled")
        val hevcBaseLayerKey = booleanPreferencesKey("experimental_dv7_hevc_base_layer_enabled")
        val vodCacheModeKey = stringPreferencesKey("vod_cache_size_mode")
        val parallelConnectionsKey = booleanPreferencesKey("use_parallel_connections")
        val migrationDoneKey = booleanPreferencesKey("migration_safe_playback_defaults_done")
        val prefs = mutablePreferencesOf(
            dv81Key to true,
            hevcBaseLayerKey to false,
            vodCacheModeKey to VodCacheSizeMode.ON.name,
            parallelConnectionsKey to true,
            migrationDoneKey to true
        )

        applyPlayerSettingsMigrations(prefs)

        assertEquals(true, prefs[dv81Key])
        assertEquals(false, prefs[hevcBaseLayerKey])
        assertEquals(VodCacheSizeMode.ON.name, prefs[vodCacheModeKey])
        assertEquals(true, prefs[parallelConnectionsKey])
    }
```

- [ ] **Step 4: Add profile-boundary migration test**

Add:

```kotlin
    @Test
    fun `safe playback migration applies independently per profile settings store`() = runTest {
        val activeProfileId = MutableStateFlow(1)
        val dataStore = playerSettingsDataStoreForTest(activeProfileId)

        dataStore.setExperimentalDv7ToDv81Enabled(true)
        dataStore.setVodCacheSizeMode(VodCacheSizeMode.ON)
        dataStore.setUseParallelConnections(true)

        activeProfileId.value = 2
        dataStore.setExperimentalDv7ToDv81Enabled(true)
        dataStore.setVodCacheSizeMode(VodCacheSizeMode.ON)
        dataStore.setUseParallelConnections(true)

        activeProfileId.value = 1
        val defaultProfileSettings = dataStore.playerSettings.first()

        activeProfileId.value = 2
        val secondaryProfileSettings = dataStore.playerSettings.first()

        assertEquals(false, defaultProfileSettings.experimentalDv7ToDv81Enabled)
        assertEquals(true, defaultProfileSettings.experimentalDv7HevcBaseLayerEnabled)
        assertEquals(VodCacheSizeMode.OFF, defaultProfileSettings.vodCacheSizeMode)
        assertEquals(false, defaultProfileSettings.useParallelConnections)

        assertEquals(false, secondaryProfileSettings.experimentalDv7ToDv81Enabled)
        assertEquals(true, secondaryProfileSettings.experimentalDv7HevcBaseLayerEnabled)
        assertEquals(VodCacheSizeMode.OFF, secondaryProfileSettings.vodCacheSizeMode)
        assertEquals(false, secondaryProfileSettings.useParallelConnections)
    }
```

This proves the migration runs in both the default profile store and profile 2 store when each profile is active.

- [ ] **Step 5: Run migration tests and verify expected failures**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.data.local.PlayerSettingsDataStoreTest
```

Expected: new migration tests fail because `migration_safe_playback_defaults_done` and migration behavior do not exist yet.

- [ ] **Step 6: Commit failing migration tests**

```bash
git add app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt
git commit -m "test(player): pin safe playback settings migration"
```

---

### Task 4: Implement One-Time Safe Playback Migration

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`

- [ ] **Step 1: Add migration key aliases near existing migration keys**

In `PlayerSettingsDataStore.kt`, after:

```kotlin
private val migrationAutoplayManualDefaultsDoneKey =
    booleanPreferencesKey("migration_autoplay_manual_defaults_done")
```

add:

```kotlin
private val migrationSafePlaybackDefaultsDoneKey =
    booleanPreferencesKey("migration_safe_playback_defaults_done")
private val experimentalDv7ToDv81EnabledMigrationKey =
    booleanPreferencesKey("experimental_dv7_to_dv81_enabled")
private val experimentalDv7HevcBaseLayerEnabledMigrationKey =
    booleanPreferencesKey("experimental_dv7_hevc_base_layer_enabled")
private val experimentalDv7ToDv81PreserveMappingEnabledMigrationKey =
    booleanPreferencesKey("experimental_dv7_to_dv81_preserve_mapping_enabled")
private val vodCacheSizeModeMigrationKey =
    stringPreferencesKey("vod_cache_size_mode")
private val useParallelConnectionsMigrationKey =
    booleanPreferencesKey("use_parallel_connections")
```

- [ ] **Step 2: Add migration block to `applyPlayerSettingsMigrations(...)`**

At the end of `applyPlayerSettingsMigrations(prefs)`, after the autoplay migration block, add:

```kotlin
    val safePlaybackDefaultsDone = prefs[migrationSafePlaybackDefaultsDoneKey] ?: false
    if (!safePlaybackDefaultsDone) {
        prefs[experimentalDv7ToDv81EnabledMigrationKey] = false
        prefs[experimentalDv7HevcBaseLayerEnabledMigrationKey] = true
        prefs[experimentalDv7ToDv81PreserveMappingEnabledMigrationKey] = false
        prefs[vodCacheSizeModeMigrationKey] = VodCacheSizeMode.OFF.name
        prefs[useParallelConnectionsMigrationKey] = false
        prefs[migrationSafePlaybackDefaultsDoneKey] = true
    }
```

This intentionally overrides existing values one time because the product requirement says existing users must be moved to the safer playback defaults.

- [ ] **Step 3: Run tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.data.local.PlayerSettingsDataStoreTest
```

Expected: all `PlayerSettingsDataStoreTest` tests pass.

- [ ] **Step 4: Run spool mode tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest
```

Expected: pass. If any test assumes VOD cache default `ON`, update it to `OFF`.

- [ ] **Step 5: Commit migration implementation**

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt
git commit -m "fix(player): migrate profiles to safe playback defaults"
```

---

### Task 5: Add Profile Scope Contract Coverage

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Add static contract test for player settings profile scope**

Append this test to `ProfileSettingsScopeContractTest`:

```kotlin
    @Test
    fun `player safe playback defaults are profile scoped settings`() {
        val source = File("app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt").readText()

        assertTrue(source.contains("private const val FEATURE = \"player_settings\""))
        assertTrue(source.contains("factory.get(profileId, FEATURE)"))
        assertTrue(source.contains("migration_safe_playback_defaults_done"))
        assertTrue(source.contains("experimental_dv7_hevc_base_layer_enabled"))
        assertTrue(source.contains("experimental_dv7_to_dv81_enabled"))
        assertTrue(source.contains("vod_cache_size_mode"))
        assertTrue(source.contains("use_parallel_connections"))
    }
```

- [ ] **Step 2: Run profile scope contract**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest
```

Expected: pass.

- [ ] **Step 3: Commit contract coverage**

```bash
git add app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "test(profile): cover safe playback settings scope"
```

---

### Task 6: Final Verification

**Files:**
- No source edits expected.

- [ ] **Step 1: Run targeted settings tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.data.local.PlayerSettingsDataStoreTest \
  --tests com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest \
  --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest
```

Expected: pass.

- [ ] **Step 2: Run playback DV tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.core.player.DolbyVisionBaseLayerPolicyTest \
  --tests com.nexio.tv.core.player.DolbyVisionAutoPlayGateTest \
  --tests com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest \
  --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest
```

Expected: pass.

- [ ] **Step 3: Build profileable package**

Run:

```bash
./gradlew -q :app:assembleUniversalReleaseProfileable
```

Expected: pass.

- [ ] **Step 4: Run diff check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 5: Commit only if verification required generated changes**

No commit is expected in this step. If a test fixture had to be updated, commit with:

```bash
git add <changed-test-fixture>
git commit -m "test(player): align safe playback default verification"
```

---

## Risk Controls

- The migration is intentionally one-time. Users can re-enable DV8.1 conversion, VOD cache, or parallel connections after migration and will not be reset again.
- The migration runs in `applyAllPlayerMigrations(...)`, which is applied to the currently active profile’s `player_settings` DataStore. This preserves profile boundaries and applies as each profile is read.
- Default profile uses feature file `player_settings`; secondary profiles use `player_settings_p2`, `player_settings_p3`, etc. through `ProfileDataStoreFactory`.
- The profile-boundary test switches active profile IDs and proves profile 1 and profile 2 are migrated independently.
- Existing setter interactions remain unchanged and covered by existing tests.

## Self-Review

Spec coverage:

- New-user defaults covered by Tasks 1-2.
- Existing-user migration covered by Tasks 3-4.
- Default profile and secondary profile boundary covered by Task 3 and Task 5.
- VOD cache and parallel connection defaults/migration covered by Tasks 1-4.

Placeholder scan:

- No `TBD`, `TODO`, “implement later”, or “similar to” placeholders remain.

Type consistency:

- Migration key is consistently named `migration_safe_playback_defaults_done`.
- DV7 HEVC key is consistently `experimental_dv7_hevc_base_layer_enabled`.
- DV8.1 key is consistently `experimental_dv7_to_dv81_enabled`.
- VOD cache key is consistently `vod_cache_size_mode`.
- Parallel key is consistently `use_parallel_connections`.

