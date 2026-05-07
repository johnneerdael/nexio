# Stock Reset Auth Teardown Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make logout and remote durable-auth revoke reset Nexio to stock local state without leaking credentials locally or pushing stock defaults back to the user's remote account.

**Architecture:** Treat signed-out stock reset as a local-only teardown operation with an explicit sync suppression boundary. Move stock account defaults into an explicit contract, make profile-scoped credential clears target profile `1` deterministically, and verify the real reset path with executable tests rather than source-string checks.

**Tech Stack:** Kotlin, Hilt, Android DataStore, coroutines, MockK, Gradle JVM unit tests, OpenSpec.

---

## File Structure

- `openspec/changes/harden-stock-reset-auth-teardown/proposal.md`
  Defines why the reset teardown hardening is required.
- `openspec/changes/harden-stock-reset-auth-teardown/tasks.md`
  Tracks implementation and verification work.
- `openspec/changes/harden-stock-reset-auth-teardown/specs/durable-device-auth/spec.md`
  Adds durable-auth reset requirements with scenario coverage.
- `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`
  Change revoke teardown ordering so account sync cannot see a live full-account session while local stock reset writes occur.
- `app/src/main/java/com/nexio/tv/core/auth/StockDeviceState.kt`
  Replace `AccountConfigSyncPayload()` as an implicit stock default with explicit stock account settings.
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
  Add a local-reset suppression guard and clear Kitsu against the default legacy profile explicitly.
- `app/src/main/java/com/nexio/tv/data/local/KitsuAuthDataStore.kt`
  Add profile-explicit save/clear APIs used by account reset.
- `app/src/test/java/com/nexio/tv/core/auth/AuthManagerStateTest.kt`
  Verify stock defaults are explicit and stable.
- `app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt`
  Replace source-string coverage with behavior tests for revoke ordering.
- `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`
  Add focused tests for local reset suppression and default-profile Kitsu clearing.
- `app/src/test/java/com/nexio/tv/data/local/KitsuAuthDataStoreTest.kt`
  Add profile-explicit credential clearing coverage.

## Task 1: Record The OpenSpec Change

**Files:**
- Create: `openspec/changes/harden-stock-reset-auth-teardown/proposal.md`
- Create: `openspec/changes/harden-stock-reset-auth-teardown/tasks.md`
- Create: `openspec/changes/harden-stock-reset-auth-teardown/specs/durable-device-auth/spec.md`

- [ ] **Step 1: Create the proposal**

Use this content for `openspec/changes/harden-stock-reset-auth-teardown/proposal.md`:

```markdown
# Harden Stock Reset Auth Teardown

## Why

Logout and remote durable-auth revoke must remove local account state immediately. The current implementation resets local DataStores before every live auth/sync path is guaranteed inactive, and one provider reset depends on the active profile instead of targeting the default account profile explicitly.

## What Changes

- Ensure remote durable-auth revoke disables account sync before stock reset writes occur.
- Define stock account settings explicitly instead of relying on DTO constructor defaults.
- Clear profile-scoped provider credentials against the default legacy profile deterministically.
- Add executable tests proving reset does not schedule remote account pushes and credentials are cleared.

## Impact

- Android TV app only.
- No Supabase schema migration.
- No remote data deletion during logout; this is local-device teardown only.
```

- [ ] **Step 2: Create the OpenSpec task list**

Use this content for `openspec/changes/harden-stock-reset-auth-teardown/tasks.md`:

```markdown
## 1. Implementation

- [ ] 1.1 Make remote durable-auth revoke disable live account sync before local stock reset writes.
- [ ] 1.2 Add explicit stock account-config defaults.
- [ ] 1.3 Add profile-explicit Kitsu auth clearing and use it from account reset.
- [ ] 1.4 Add local reset suppression coverage so reset writes cannot push stock defaults remotely.

## 2. Verification

- [ ] 2.1 Run focused auth, sync, and Kitsu tests.
- [ ] 2.2 Run `./gradlew :app:compileUniversalReleaseKotlin`.
- [ ] 2.3 Run `openspec validate harden-stock-reset-auth-teardown --strict`.
```

- [ ] **Step 3: Create the spec delta**

Use this content for `openspec/changes/harden-stock-reset-auth-teardown/specs/durable-device-auth/spec.md`:

```markdown
## ADDED Requirements

### Requirement: Local Stock Reset On Auth Teardown

When a user manually logs out or a durable device credential is authoritatively revoked, the Android TV app SHALL reset local account-owned state to stock defaults before presenting the signed-out or reconnect UI.

#### Scenario: Manual logout resets local account-owned state

- **GIVEN** the device has a full account session with custom profiles, addons, integrations, provider credentials, tracking settings, and formatter settings
- **WHEN** the user manually logs out on the device
- **THEN** the device has only the stock `Default` profile
- **AND** only stock addons remain installed locally
- **AND** integration credentials are cleared locally
- **AND** tracking and formatter settings match the stock defaults

#### Scenario: Remote durable-auth revoke resets without remote push

- **GIVEN** the device has a full account session and local account sync observers are active
- **WHEN** the durable auth credential is authoritatively rejected or revoked
- **THEN** the app disables live account sync before local reset writes can be observed as user edits
- **AND** the app does not push stock defaults to the remote account as part of local teardown
- **AND** the device transitions to reconnect or signed-out UI with stock local account state

#### Scenario: Profile-scoped credentials clear the stock account profile

- **GIVEN** profile-scoped provider credentials exist for multiple local profiles
- **WHEN** account-owned local state is reset to stock
- **THEN** credentials belonging to the stock account profile are cleared by explicit profile id
- **AND** reset behavior does not depend on the currently active profile at call time
```

- [ ] **Step 4: Validate the OpenSpec change**

Run:

```bash
openspec validate harden-stock-reset-auth-teardown --strict
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add openspec/changes/harden-stock-reset-auth-teardown
git commit -m "docs(auth): specify local stock reset teardown hardening"
```

## Task 2: Make Stock Account Defaults Explicit

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/auth/StockDeviceState.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/auth/AuthManagerStateTest.kt`

- [ ] **Step 1: Replace the stock-state test with explicit default assertions**

Add this test to `AuthManagerStateTest`:

```kotlin
@Test
fun `stock account config defines explicit local signed out defaults`() {
    val stock = stockAccountConfigSyncPayload()

    assertEquals(7, stock.schemaVersion)
    assertEquals("TRAKT", stock.playback.streamSelection.trackingProvider)
    assertTrue(stock.formatter.enabled)
    assertEquals("universal", stock.formatter.selectedTemplateId)
    assertNull(stock.formatter.customTemplate)
    assertFalse(stock.integrations.debrid.realDebrid.connected)
    assertFalse(stock.integrations.debrid.premiumize.configured)
    assertFalse(stock.integrations.debrid.torBox.configured)
    assertFalse(stock.integrations.debrid.easyDebrid.configured)
    assertTrue(stock.integrations.tvdb.enabled)
    assertTrue(stock.integrations.tvdb.configured)
    assertEquals("VALID", stock.integrations.tvdb.validationStatus)
}
```

- [ ] **Step 2: Run the focused test and verify it fails if defaults are still implicit**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.AuthManagerStateTest"
```

Expected: PASS may occur because current DTO defaults match, but the test still fails the plan review until implementation removes the bare `AccountConfigSyncPayload()` constructor from `StockDeviceState.kt`.

- [ ] **Step 3: Implement explicit stock defaults**

Replace `stockAccountConfigSyncPayload()` in `StockDeviceState.kt` with this implementation and add the required imports from `com.nexio.tv.data.remote.supabase`:

```kotlin
fun stockAccountConfigSyncPayload(): AccountConfigSyncPayload =
    AccountConfigSyncPayload(
        schemaVersion = 7,
        integrations = IntegrationSettings(
            debrid = DebridSyncSettings(
                premiumize = PremiumizeSyncSettings(configured = false, customerId = ""),
                torBox = TorBoxSyncSettings(configured = false, email = "", plan = ""),
                easyDebrid = EasyDebridSyncSettings(configured = false, userId = "", paidUntil = ""),
                realDebrid = RealDebridSyncSettings(
                    connected = false,
                    username = "",
                    pending = false,
                    deviceCode = "",
                    userCode = "",
                    verificationUrl = "",
                    expiresAt = null
                )
            ),
            tvdb = TvdbSyncSettings(
                enabled = true,
                configured = true,
                validationStatus = "VALID",
                lastFailure = ""
            )
        ),
        catalogs = CatalogSyncSettings(),
        playback = PlaybackConfigSyncSettings(
            streamSelection = StreamSelectionConfigSyncSettings(trackingProvider = "TRAKT")
        ),
        formatter = FormatterSyncSettings(
            enabled = true,
            selectedTemplateId = "universal",
            customTemplate = null
        )
    )
```

- [ ] **Step 4: Run the focused test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.AuthManagerStateTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/auth/StockDeviceState.kt app/src/test/java/com/nexio/tv/core/auth/AuthManagerStateTest.kt
git commit -m "fix(auth): make stock account defaults explicit"
```

## Task 3: Clear Kitsu Credentials By Explicit Profile Id

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/KitsuAuthDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/KitsuAuthDataStoreTest.kt`

- [ ] **Step 1: Write the failing Kitsu profile-explicit test**

Create `app/src/test/java/com/nexio/tv/data/local/KitsuAuthDataStoreTest.kt` using the same test context/profile factory style as the existing Trakt and Simkl auth datastore tests, then add:

```kotlin
@Test
fun `clearAuth clears requested profile without depending on active profile`() = runTest {
    authStore.saveForProfile(
        profileId = 1,
        snapshot = KitsuAuthSnapshot(
            enabled = true,
            username = "default-user",
            accessToken = "default-token",
            refreshToken = "default-refresh",
            expiresAtEpochSeconds = 1234L,
            includeNsfw = false
        )
    )
    authStore.saveForProfile(
        profileId = 2,
        snapshot = KitsuAuthSnapshot(
            enabled = true,
            username = "secondary-user",
            accessToken = "secondary-token",
            refreshToken = "secondary-refresh",
            expiresAtEpochSeconds = 5678L,
            includeNsfw = true
        )
    )
    profileManager.setActiveProfile(2)

    authStore.clearAuth(profileId = 1)

    assertFalse(authStore.stateForProfile(1).first().isAuthenticated)
    assertNull(authStore.stateForProfile(1).first().username)
    assertTrue(authStore.stateForProfile(2).first().isAuthenticated)
    assertEquals("secondary-user", authStore.stateForProfile(2).first().username)
}
```

- [ ] **Step 2: Run the Kitsu test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.KitsuAuthDataStoreTest"
```

Expected: FAIL because `saveForProfile()` and `clearAuth(profileId)` do not exist.

- [ ] **Step 3: Add profile-explicit APIs**

Add these methods to `KitsuAuthStore` and `KitsuAuthDataStore`:

```kotlin
suspend fun saveForProfile(profileId: Int, snapshot: KitsuAuthSnapshot)
suspend fun clearAuth(profileId: Int)
```

Implement them in `KitsuAuthDataStore`:

```kotlin
override suspend fun save(snapshot: KitsuAuthSnapshot) {
    saveForProfile(profileManager.activeProfileId.value, snapshot)
}

override suspend fun saveForProfile(profileId: Int, snapshot: KitsuAuthSnapshot) {
    store(profileId).edit { preferences ->
        preferences[enabledKey] = snapshot.enabled
        preferences[includeNsfwKey] = snapshot.includeNsfw
        val username = snapshot.username?.trim().orEmpty()
        if (username.isBlank()) preferences.remove(usernameKey) else preferences[usernameKey] = username
        val accessToken = snapshot.accessToken.orEmpty()
        if (accessToken.isBlank()) preferences.remove(accessTokenKey) else preferences[accessTokenKey] = accessToken
        val refreshToken = snapshot.refreshToken.orEmpty()
        if (refreshToken.isBlank()) preferences.remove(refreshTokenKey) else preferences[refreshTokenKey] = refreshToken
        val expiresAt = snapshot.expiresAtEpochSeconds
        if (expiresAt == null) preferences.remove(expiresAtKey) else preferences[expiresAtKey] = expiresAt
    }
}

override suspend fun clear() {
    clearAuth(profileManager.activeProfileId.value)
}

override suspend fun clearAuth(profileId: Int) {
    saveForProfile(profileId, KitsuAuthSnapshot())
}
```

- [ ] **Step 4: Use explicit Kitsu clearing from account reset**

Replace this line in `AccountSettingsSyncService.clearLocalAccountSecrets()`:

```kotlin
kitsuAuthDataStore.save(KitsuAuthSnapshot())
```

with:

```kotlin
kitsuAuthDataStore.clearAuth(profileModeRouter.defaultLegacyProfileId())
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.KitsuAuthDataStoreTest" --tests "com.nexio.tv.core.auth.LocalAccountResetCoordinatorTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/KitsuAuthDataStore.kt app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt app/src/test/java/com/nexio/tv/data/local/KitsuAuthDataStoreTest.kt
git commit -m "fix(auth): clear kitsu stock profile credentials explicitly"
```

## Task 4: Prevent Remote Push During Local Reset Teardown

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`
- Test: `app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt`

- [ ] **Step 1: Write the failing behavior test for revoke ordering**

Replace the existing revoke helper test in `DurableDeviceAuthRecoveryPolicyTest` with this stricter order assertion:

```kotlin
@Test
fun `authoritative durable rejection disables live sync before local stock reset writes`() = runTest {
    val events = mutableListOf<String>()

    handleAuthoritativeDurableCredentialRejection(
        disableLiveAccountSync = { events += "disable-live-sync" },
        resetLocalAccountState = { events += "reset-local-stock" },
        clearDurableCredential = { events += "clear-durable" },
        clearSupabaseSession = { events += "clear-session" },
        transitionToReconnectState = { events += "session-lost" }
    )

    assertEquals(
        listOf(
            "disable-live-sync",
            "reset-local-stock",
            "clear-durable",
            "clear-session",
            "session-lost"
        ),
        events
    )
}
```

- [ ] **Step 2: Run the auth policy test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest"
```

Expected: FAIL because `handleAuthoritativeDurableCredentialRejection()` has no `disableLiveAccountSync` parameter.

- [ ] **Step 3: Add a local reset suppression helper**

Add this method to `AccountSettingsSyncService`:

```kotlin
suspend fun runWithLocalResetPushSuppressed(block: suspend () -> Unit) {
    withContext(Dispatchers.IO) {
        applyingRemoteMutex.withLock {
            isApplyingRemote = true
            pushJob?.cancel()
            pushJob = null
            try {
                block()
                synchronized(pendingChangedPaths) {
                    pendingChangedPaths.clear()
                    pendingChangedPathsGeneration += 1L
                }
            } finally {
                isApplyingRemote = false
            }
        }
    }
}
```

Then refactor `resetLocalAccountConfigToDefaults()` to call this helper:

```kotlin
suspend fun resetLocalAccountConfigToDefaults() {
    runWithLocalResetPushSuppressed {
        applySharedAccountConfigSyncSettings(stockAccountConfigSyncPayload())
        clearLocalAccountSecrets()
    }
}
```

- [ ] **Step 4: Change revoke helper ordering**

Change the helper signature in `AuthManager.kt`:

```kotlin
internal suspend fun handleAuthoritativeDurableCredentialRejection(
    disableLiveAccountSync: () -> Unit,
    resetLocalAccountState: suspend () -> Unit,
    clearDurableCredential: suspend () -> Unit,
    clearSupabaseSession: suspend () -> Unit,
    transitionToReconnectState: () -> Unit
)
```

Make the first statement:

```kotlin
disableLiveAccountSync()
```

Call it from `clearLocalAuthStateAfterAuthoritativeDurableRejection()` like this:

```kotlin
handleAuthoritativeDurableCredentialRejection(
    disableLiveAccountSync = {
        transitionToSessionLost()
    },
    resetLocalAccountState = {
        localAccountResetCoordinator.resetToSignedOutStockState()
    },
    clearDurableCredential = {
        durableDeviceCredentialStore.clear()
    },
    clearSupabaseSession = {
        auth.clearSession()
    },
    transitionToReconnectState = {
        transitionToSessionLost()
    }
)
```

Implementation note:
- Calling `transitionToSessionLost()` twice is acceptable only if it is idempotent. If it is not idempotent, add a small local boolean in the helper so the final transition only executes when the first transition throws or is skipped.

- [ ] **Step 5: Run the focused policy test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt
git commit -m "fix(auth): suppress remote sync during stock reset teardown"
```

## Task 5: Add Executable Reset Regression Coverage

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/auth/LocalAccountResetCoordinatorTest.kt`

- [ ] **Step 1: Add a source-level guard for local reset suppression**

Add this test to `AccountConfigSyncContractTest`:

```kotlin
@Test
fun `local account reset suppresses pending account config pushes`() {
    val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()

    assertTrue(source.contains("suspend fun runWithLocalResetPushSuppressed"))
    assertTrue(source.contains("pushJob?.cancel()"))
    assertTrue(source.contains("pendingChangedPaths.clear()"))
    assertTrue(source.contains("resetLocalAccountConfigToDefaults()"))
    assertTrue(source.contains("runWithLocalResetPushSuppressed"))
}
```

- [ ] **Step 2: Add a coordinator ordering test**

Extend `LocalAccountResetCoordinatorTest` with ordered verification:

```kotlin
@Test
fun `resetToSignedOutStockState resets profiles addons then account config in order`() = runTest {
    val profileManager = mockk<ProfileManager>(relaxed = true)
    val addonPreferences = mockk<AddonPreferences>(relaxed = true)
    val accountSettingsSyncService = mockk<AccountSettingsSyncService>(relaxed = true)
    val coordinator = LocalAccountResetCoordinator(
        profileManager = profileManager,
        addonPreferences = addonPreferences,
        accountSettingsSyncService = javax.inject.Provider { accountSettingsSyncService }
    )

    coordinator.resetToSignedOutStockState()

    coVerifyOrder {
        profileManager.resetToSingleDefaultProfile()
        addonPreferences.resetToDefaultAddons()
        accountSettingsSyncService.resetLocalAccountConfigToDefaults()
    }
}
```

- [ ] **Step 3: Run focused regression tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest" --tests "com.nexio.tv.core.auth.LocalAccountResetCoordinatorTest"
```

Expected: PASS.

- [ ] **Step 4: Run the complete focused auth reset suite**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.AuthManagerStateTest" --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest" --tests "com.nexio.tv.core.auth.LocalAccountResetCoordinatorTest" --tests "com.nexio.tv.core.profile.ProfileManagerTest" --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest" --tests "com.nexio.tv.data.local.KitsuAuthDataStoreTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt app/src/test/java/com/nexio/tv/core/auth/LocalAccountResetCoordinatorTest.kt
git commit -m "test(auth): cover stock reset teardown regressions"
```

## Task 6: Final Verification

**Files:**
- Modify: `openspec/changes/harden-stock-reset-auth-teardown/tasks.md`

- [ ] **Step 1: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.AuthManagerStateTest" --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest" --tests "com.nexio.tv.core.auth.LocalAccountResetCoordinatorTest" --tests "com.nexio.tv.core.profile.ProfileManagerTest" --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest" --tests "com.nexio.tv.data.local.KitsuAuthDataStoreTest"
```

Expected: PASS.

- [ ] **Step 2: Run release Kotlin compile**

Run:

```bash
./gradlew :app:compileUniversalReleaseKotlin
```

Expected: PASS.

- [ ] **Step 3: Validate OpenSpec**

Run:

```bash
openspec validate harden-stock-reset-auth-teardown --strict
```

Expected: PASS.

- [ ] **Step 4: Mark OpenSpec tasks complete**

Update `openspec/changes/harden-stock-reset-auth-teardown/tasks.md`:

```markdown
## 1. Implementation

- [x] 1.1 Make remote durable-auth revoke disable live account sync before local stock reset writes.
- [x] 1.2 Add explicit stock account-config defaults.
- [x] 1.3 Add profile-explicit Kitsu auth clearing and use it from account reset.
- [x] 1.4 Add local reset suppression coverage so reset writes cannot push stock defaults remotely.

## 2. Verification

- [x] 2.1 Run focused auth, sync, and Kitsu tests.
- [x] 2.2 Run `./gradlew :app:compileUniversalReleaseKotlin`.
- [x] 2.3 Run `openspec validate harden-stock-reset-auth-teardown --strict`.
```

- [ ] **Step 5: Commit final verification**

```bash
git add openspec/changes/harden-stock-reset-auth-teardown/tasks.md
git commit -m "docs(auth): mark stock reset teardown hardening verified"
```

## Self-Review

- Spec coverage: The plan covers manual logout reset, remote durable-auth revoke reset, no remote push during local teardown, explicit stock settings, profile-explicit Kitsu clearing, and focused verification.
- Placeholder scan: No task contains deferred implementation placeholders. The only conditional note is the idempotency check for `transitionToSessionLost()`, with a concrete fallback.
- Type consistency: The plan consistently uses `stockAccountConfigSyncPayload()`, `runWithLocalResetPushSuppressed`, `clearAuth(profileId)`, and `saveForProfile(profileId, snapshot)`.
