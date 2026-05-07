# Local Account Reset On Sign-Out Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reset the Android TV app back to stock local state immediately when a real account session is manually signed out on-device or authoritatively revoked, including profiles, addons, account integrations, and synced formatter/tracking settings.

**Architecture:** Add a single local reset coordinator that owns “return device to stock account state” behavior, then call it from both manual sign-out and authoritative durable-credential rejection paths. Define one explicit stock-state helper as the single source of truth for the default profile, stock addons, and stock account-config payload, then make all reset code consume that helper so later default changes only require one update.

**Tech Stack:** Kotlin, Hilt, Android DataStore, Jan Supabase auth, existing profile/addon/settings repositories, Gradle JVM unit tests.

---

## File Structure

- `app/src/main/java/com/nexio/tv/core/auth/LocalAccountResetCoordinator.kt`
  New orchestrator that clears local account-scoped state back to stock defaults.
- `app/src/main/java/com/nexio/tv/core/auth/StockDeviceState.kt`
  New single source of truth for stock profile/addon/account-config defaults used by local reset flows.
- `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`
  Integrate the coordinator into manual sign-out and authoritative revoke handling.
- `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt`
  Add a focused helper to collapse local profiles back to only profile `1` (`Default`).
- `app/src/main/java/com/nexio/tv/data/local/AddonPreferences.kt`
  Add a reset helper that consumes the shared stock-state addon definition instead of deleting addon prefs ad hoc.
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
  Expose a public/local-only reset entrypoint that applies stock integration + formatter/tracking defaults and clears provider auth where needed.
- `app/src/test/java/com/nexio/tv/core/auth/LocalAccountResetCoordinatorTest.kt`
  New coordinator-focused unit tests.
- `app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt`
  Extend revoke-path expectations to assert reset coordination.
- `app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt`
  Add local reset coverage for collapsing back to the default profile.
- `openspec/changes/add-durable-device-auth/tasks.md`
  Record behavior/verification notes once the reset behavior lands.

## Task 1: Define A Canonical Stock Device State

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/auth/StockDeviceState.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/auth/AuthManagerStateTest.kt`

- [ ] **Step 1: Write the failing stock-state test**

```kotlin
@Test
fun `stock device state exposes canonical default profile addons and account config`() {
    assertEquals(1, stockDefaultProfile().id)
    assertEquals("Default", stockDefaultProfile().name)
    assertEquals(
        listOf("https://v3-cinemeta.strem.io", "https://opensubtitles-v3.strem.io"),
        stockAddonInstallConfigs().map { it.url }
    )
    assertEquals(AccountConfigSyncPayload(), stockAccountConfigSyncPayload())
}
```

- [ ] **Step 2: Run the stock-state test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.AuthManagerStateTest"
```

Expected: FAIL because the stock-state helper does not exist yet.

- [ ] **Step 3: Create the stock-state helper**

```kotlin
fun stockDefaultProfile(): UserProfile =
    UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5")

fun stockAddonInstallConfigs(): List<AddonPreferences.AddonInstallConfig> =
    listOf(
        AddonPreferences.AddonInstallConfig(url = "https://v3-cinemeta.strem.io"),
        AddonPreferences.AddonInstallConfig(url = "https://opensubtitles-v3.strem.io")
    )

fun stockAccountConfigSyncPayload(): AccountConfigSyncPayload =
    AccountConfigSyncPayload()
```

Implementation notes:
- This file is the only place that should define stock signed-out account defaults.
- Later reset tasks must consume this helper rather than rebuilding defaults inline.

- [ ] **Step 4: Run the stock-state test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.AuthManagerStateTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/core/auth/StockDeviceState.kt \
  app/src/test/java/com/nexio/tv/core/auth/AuthManagerStateTest.kt
git commit -m "feat(auth): define canonical stock device state"
```

## Task 2: Add Reset Primitives For Profiles, Addons, And Account Defaults

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/AddonPreferences.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Test: `app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt`

- [ ] **Step 1: Write the failing profile reset test**

```kotlin
@Test
fun `resetToSingleDefaultProfile removes secondary profiles and activates default`() = runTest {
    profileManager.createProfile(name = "Kids", avatarColorHex = "#FF0000")
    profileManager.createProfile(name = "Guest", avatarColorHex = "#00FF00")
    profileManager.setActiveProfile(3)

    profileManager.resetToSingleDefaultProfile()

    assertEquals(listOf(1), profileManager.profiles.value.map { it.id })
    assertEquals("Default", profileManager.profiles.value.single().name)
    assertEquals(1, profileManager.activeProfileId.value)
}
```

- [ ] **Step 2: Run the profile test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.profile.ProfileManagerTest"
```

Expected: FAIL because `resetToSingleDefaultProfile()` does not exist yet.

- [ ] **Step 3: Add the profile reset helper**

```kotlin
suspend fun resetToSingleDefaultProfile() {
    val currentProfiles = dataStore.profilesList.first()
    currentProfiles
        .map { it.id }
        .filter { it != 1 }
        .forEach { profileId ->
            deleteProfileDataAsync(profileId, syncRemoteDelete = false)
        }

    dataStore.setProfiles(listOf(stockDefaultProfile()))
    setActiveProfile(1)
}
```

- [ ] **Step 4: Add the stock-addon reset helper**

```kotlin
suspend fun resetToDefaultAddons() {
    store().edit { preferences ->
        preferences[orderedUrlsKey] = gson.toJson(stockAddonInstallConfigs())
        preferences.remove(legacyUrlsKey)
    }
}
```

- [ ] **Step 5: Add a public account-config reset entrypoint**

```kotlin
suspend fun resetLocalAccountConfigToDefaults() {
    applySharedAccountConfigSyncSettings(stockAccountConfigSyncPayload())

    traktAuthDataStore.clearAuth(profileModeRouter.defaultLegacyProfileId())
    simklAuthDataStore.clearAuth(profileModeRouter.defaultLegacyProfileId())
    kitsuAuthDataStore.clear()
    realDebridAuthDataStore.clearAuth()
}
```

Implementation notes:
- Keep this method local-only. It should not push or pull remote state.
- Reuse `stockAccountConfigSyncPayload()` as the source of stock integration + formatter/tracking defaults.
- Do not reset unrelated UI appearance/layout preferences in this task unless the current code proves they are account-scoped and must be cleared for correctness.

- [ ] **Step 6: Run the updated profile test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.profile.ProfileManagerTest"
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt \
  app/src/main/java/com/nexio/tv/data/local/AddonPreferences.kt \
  app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt \
  app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt
git commit -m "feat(auth): add local stock reset primitives"
```

## Task 3: Add A Single Local Account Reset Coordinator

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/auth/LocalAccountResetCoordinator.kt`
- Test: `app/src/test/java/com/nexio/tv/core/auth/LocalAccountResetCoordinatorTest.kt`

- [ ] **Step 1: Write the failing coordinator test**

```kotlin
@Test
fun `resetToSignedOutStockState collapses profiles clears addons and resets account config`() = runTest {
    coordinator.resetToSignedOutStockState()

    coVerify { profileManager.resetToSingleDefaultProfile() }
    coVerify { addonPreferences.resetToDefaultAddons() }
    coVerify { accountSettingsSyncService.resetLocalAccountConfigToDefaults() }
}
```

- [ ] **Step 2: Run the coordinator test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.LocalAccountResetCoordinatorTest"
```

Expected: FAIL because the coordinator file does not exist yet.

- [ ] **Step 3: Create the coordinator**

```kotlin
@Singleton
class LocalAccountResetCoordinator @Inject constructor(
    private val profileManager: ProfileManager,
    private val addonPreferences: AddonPreferences,
    private val accountSettingsSyncService: AccountSettingsSyncService
) {
    suspend fun resetToSignedOutStockState() {
        profileManager.resetToSingleDefaultProfile()
        addonPreferences.resetToDefaultAddons()
        accountSettingsSyncService.resetLocalAccountConfigToDefaults()
    }
}
```

Implementation notes:
- Keep ordering explicit: profiles first, then addons, then account config reset.
- Do not put auth sign-out / durable-credential clearing in this coordinator. `AuthManager` still owns auth/session teardown.

- [ ] **Step 4: Run the coordinator test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.LocalAccountResetCoordinatorTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/core/auth/LocalAccountResetCoordinator.kt \
  app/src/test/java/com/nexio/tv/core/auth/LocalAccountResetCoordinatorTest.kt
git commit -m "feat(auth): centralize local account reset coordination"
```

## Task 4: Invoke The Reset On Manual Sign-Out And Authoritative Revoke

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt`

- [ ] **Step 1: Write the failing auth-policy tests**

```kotlin
@Test
fun `manual sign out triggers local stock reset`() = runTest {
    var resetCalled = false

    handleManualSignOut(
        resetLocalAccountState = { resetCalled = true },
        clearDurableCredential = {},
        clearPresenceMarker = {},
        clearSupabaseSession = {}
    )

    assertTrue(resetCalled)
}

@Test
fun `authoritative durable rejection triggers local stock reset`() = runTest {
    var resetCalled = false

    handleAuthoritativeDurableCredentialRejection(
        resetLocalAccountState = { resetCalled = true },
        clearDurableCredential = {},
        clearSupabaseSession = {},
        transitionToReconnectState = {}
    )

    assertTrue(resetCalled)
}
```

- [ ] **Step 2: Run the auth-policy tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest"
```

Expected: FAIL because reset hooks are not wired into these paths yet.

- [ ] **Step 3: Inject the coordinator into `AuthManager` and wire both flows**

```kotlin
class AuthManager @Inject constructor(
    private val auth: Auth,
    private val postgrest: Postgrest,
    private val httpClient: OkHttpClient,
    private val authPresenceDataStore: AuthPresenceDataStore,
    private val appOnboardingDataStore: AppOnboardingDataStore,
    private val durableDeviceCredentialStore: DurableDeviceCredentialStore,
    private val localAccountResetCoordinator: LocalAccountResetCoordinator
)
```

```kotlin
suspend fun signOut() {
    localSignOutInProgress = true
    transitionToSignedOut(clearPresenceMarker = false)
    cachedEffectiveUserId = null
    cachedEffectiveUserSourceUserId = null
    try {
        localAccountResetCoordinator.resetToSignedOutStockState()
        authPresenceDataStore.clear()
        durableDeviceCredentialStore.clear()
        auth.signOut()
    } finally {
        localSignOutInProgress = false
    }
}
```

```kotlin
handleAuthoritativeDurableCredentialRejection(
    resetLocalAccountState = { localAccountResetCoordinator.resetToSignedOutStockState() },
    clearDurableCredential = { durableDeviceCredentialStore.clear() },
    clearSupabaseSession = { auth.signOut() },
    transitionToReconnectState = { transitionToSessionLost() }
)
```

Implementation notes:
- Run the reset before or alongside local credential/session teardown, but keep `transitionToSessionLost()` only for authoritative revoke, not manual sign-out.
- Preserve the existing reconnect semantics for revoke: the app should land in `SessionLost`, but with stock local profile/addon/integration/formatter state.
- Preserve manual sign-out semantics: the app should land in `SignedOut`, with stock local profile/addon/integration/formatter state.

- [ ] **Step 4: Run the focused auth tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.nexio.tv.core.auth.AuthManagerStateTest" \
  --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt \
  app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt
git commit -m "fix(auth): reset local account state on logout and revoke"
```

## Task 5: Add Broader Regression Coverage And Record Behavior

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/account/AccountViewModelSyncCodeSessionTest.kt`
- Modify: `openspec/changes/add-durable-device-auth/tasks.md`

- [ ] **Step 1: Add a focused logout/reset regression test**

```kotlin
@Test
fun `sign out returns device to stock account state`() = runTest {
    // Arrange a non-default account-local state, then sign out.
    // Assert:
    // - only profile 1 remains
    // - stock addons remain
    // - integration auth/config stores are back to stockAccountConfigSyncPayload() defaults
    // - synced formatter/tracking provider are back to defaults
}
```

- [ ] **Step 2: Run the new regression slice**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.nexio.tv.core.auth.LocalAccountResetCoordinatorTest" \
  --tests "com.nexio.tv.core.profile.ProfileManagerTest" \
  --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest"
```

Expected: PASS

- [ ] **Step 3: Update OpenSpec task notes**

Add a note like:

```markdown
### Local Reset Note 2026-04-24
- Manual device sign-out and authoritative durable-device revoke now reset local account-scoped state to stock defaults.
- Reset scope:
  - secondary profiles removed, active profile returns to `Default`
  - addons reset to stock addon set
  - account integration/auth config reset to stock defaults
  - synced formatter/tracking provider reset to stock defaults
```

- [ ] **Step 4: Commit**

```bash
git add \
  app/src/test/java/com/nexio/tv/ui/screens/account/AccountViewModelSyncCodeSessionTest.kt \
  openspec/changes/add-durable-device-auth/tasks.md
git commit -m "test(auth): cover local reset after logout and revoke"
```

## Task 6: Final Verification

**Files:**
- Modify as needed from prior tasks only

- [ ] **Step 1: Run the final targeted JVM suite**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.nexio.tv.core.auth.AuthManagerStateTest" \
  --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest" \
  --tests "com.nexio.tv.core.auth.LocalAccountResetCoordinatorTest" \
  --tests "com.nexio.tv.core.profile.ProfileManagerTest"
```

Expected: PASS

- [ ] **Step 2: Run a release-compile sanity check**

Run:

```bash
./gradlew :app:compileUniversalReleaseKotlin
```

Expected: PASS

- [ ] **Step 3: Manual validation on device**

Run:

```text
1. Sign in on TV with a non-default profile/addon/integration/formatter state.
2. Trigger manual sign-out from the device.
3. Confirm:
   - account screen is SignedOut
   - only Default profile remains
   - only stock addons remain
   - integration settings are back to stock defaults
   - formatter/tracking provider are back to stock defaults
4. Sign in again, recreate non-default state, revoke the device from the portal.
5. Confirm the TV lands in SessionLost/reconnect state with the same stock local reset applied.
```

- [ ] **Step 4: Commit any final fixups**

```bash
git add app/src/main/java app/src/test/java openspec/changes/add-durable-device-auth/tasks.md
git commit -m "chore(auth): finalize local stock reset verification"
```
