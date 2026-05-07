# Profile Selection Reentry Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent the app from jumping back to the profile selection screen during normal UI navigation after a profile has already been selected for the current app process.

**Architecture:** Replace the local `remember { mutableStateOf(false) }` profile gate in `MainActivity` with an explicit process-level startup/profile-selection latch. Keep profile selection as a startup gate, but do not re-open it when normal UI recomposes, when profile lists re-emit, or when the user switches profiles from the sidebar.

**Tech Stack:** Kotlin, Jetpack Compose, Android lifecycle, JUnit/Robolectric string-contract tests, Gradle unit tests, ADB/logcat validation.

---

## File Structure

- Modify `app/src/main/java/com/nexio/tv/StartupResumePolicy.kt`
  - Add a tiny pure function for profile-selection gate decisions.
  - Keep it side-effect free so it is easy to test without Compose.
- Modify `app/src/test/java/com/nexio/tv/StartupResumePolicyTest.kt`
  - Add unit coverage for the new gate policy.
- Modify `app/src/main/java/com/nexio/tv/MainActivity.kt`
  - Add a process-level profile gate latch in the `companion object`.
  - Replace `hasSelectedProfileThisSession` with a latch initialized from that process state.
  - Mark the gate passed after startup selection and after direct sidebar profile switches.
  - Remove the existing `hasSelectedProfileThisSession = false` reset in `onSwitchProfile`.
- Modify `app/src/test/java/com/nexio/tv/ui/ProfileSelectionContractTest.kt`
  - Update the contract test so future changes cannot reintroduce the reset.

---

### Task 1: Add Profile Gate Policy Tests

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/StartupResumePolicyTest.kt`
- Modify later: `app/src/main/java/com/nexio/tv/StartupResumePolicy.kt`

- [ ] **Step 1: Add failing policy tests**

Append these tests to `StartupResumePolicyTest`:

```kotlin
@Test
fun `startup profile selection shows only before profile gate is passed`() {
    assertTrue(
        shouldShowStartupProfileSelection(
            hasPassedProfileSelectionGate = false,
            profileCount = 2
        )
    )
    assertFalse(
        shouldShowStartupProfileSelection(
            hasPassedProfileSelectionGate = true,
            profileCount = 2
        )
    )
}

@Test
fun `startup profile selection stays hidden when there is one or no profiles`() {
    assertFalse(
        shouldShowStartupProfileSelection(
            hasPassedProfileSelectionGate = false,
            profileCount = 1
        )
    )
    assertFalse(
        shouldShowStartupProfileSelection(
            hasPassedProfileSelectionGate = false,
            profileCount = 0
        )
    )
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.StartupResumePolicyTest
```

Expected: compile failure mentioning `Unresolved reference: shouldShowStartupProfileSelection`.

- [ ] **Step 3: Implement the profile gate policy**

Add this function to `StartupResumePolicy.kt`, below `shouldShowAuthQrOnStartup`:

```kotlin
internal fun shouldShowStartupProfileSelection(
    hasPassedProfileSelectionGate: Boolean,
    profileCount: Int
): Boolean {
    return !hasPassedProfileSelectionGate && profileCount > 1
}
```

- [ ] **Step 4: Run tests to verify pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.StartupResumePolicyTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit policy tests and helper**

```bash
git add app/src/main/java/com/nexio/tv/StartupResumePolicy.kt app/src/test/java/com/nexio/tv/StartupResumePolicyTest.kt
git commit -m "test(profile): cover startup profile gate policy"
```

---

### Task 2: Make Profile Selection a Process-Level Startup Gate

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/MainActivity.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/ProfileSelectionContractTest.kt`

- [ ] **Step 1: Add a process-level latch**

In `MainActivity` companion object, after `cachedMainUiPrefs`, add:

```kotlin
@Volatile
private var processProfileSelectionGatePassed: Boolean = false
```

The companion object should now include:

```kotlin
@Volatile
private var cachedMainUiPrefs: MainUiPrefs? = null

@Volatile
private var processProfileSelectionGatePassed: Boolean = false
```

- [ ] **Step 2: Replace the local session flag**

Find this block in `MainActivity`:

```kotlin
// Profile selection gating (D-02, UI-01, UI-02)
var hasSelectedProfileThisSession by remember { mutableStateOf(false) }
val profiles by profileManager.profiles.collectAsState()
val shouldShowProfileSelection = !hasSelectedProfileThisSession && profiles.size > 1
```

Replace it with:

```kotlin
// Profile selection gating (D-02, UI-01, UI-02)
var hasPassedProfileSelectionGate by rememberSaveable {
    mutableStateOf(processProfileSelectionGatePassed)
}
val profiles by profileManager.profiles.collectAsState()
val shouldShowProfileSelection = shouldShowStartupProfileSelection(
    hasPassedProfileSelectionGate = hasPassedProfileSelectionGate,
    profileCount = profiles.size
)
```

This uses the pure helper from Task 1 and survives normal recomposition plus activity recreation.

- [ ] **Step 3: Mark the latch after startup profile selection**

Inside the `ProfileSelectionScreen(onProfileSelected = { ... })` callback, replace:

```kotlin
profileManager.setActiveProfile(profileId)
val afterLocale = AppLocaleResolver.resolveEffectiveAppLanguageTag(this@MainActivity)
hasSelectedProfileThisSession = true
if (beforeLocale != afterLocale) {
    recreate()
}
```

with:

```kotlin
profileManager.setActiveProfile(profileId)
val afterLocale = AppLocaleResolver.resolveEffectiveAppLanguageTag(this@MainActivity)
processProfileSelectionGatePassed = true
hasPassedProfileSelectionGate = true
if (beforeLocale != afterLocale) {
    recreate()
}
```

This makes locale-triggered `recreate()` safe because the new activity reads `processProfileSelectionGatePassed = true`.

- [ ] **Step 4: Update focus restoration effect**

Find:

```kotlin
LaunchedEffect(hasSelectedProfileThisSession) {
    if (hasSelectedProfileThisSession) {
        repeat(2) { withFrameNanos { } }
        runCatching { contentFocusRequesterForGating.requestFocus() }
    }
}
```

Replace it with:

```kotlin
LaunchedEffect(hasPassedProfileSelectionGate) {
    if (hasPassedProfileSelectionGate) {
        repeat(2) { withFrameNanos { } }
        runCatching { contentFocusRequesterForGating.requestFocus() }
    }
}
```

- [ ] **Step 5: Stop direct sidebar switches from reopening the startup gate**

There are two `onSwitchProfile` lambdas in `MainActivity`, one for `ModernSidebarBlurScaffold` and one for `LegacySidebarScaffold`. Each currently contains:

```kotlin
onSwitchProfile = { profileId ->
    hasSelectedProfileThisSession = false
    switchProfileAndApplyLocale(profileId)
}
```

Replace both with:

```kotlin
onSwitchProfile = { profileId ->
    processProfileSelectionGatePassed = true
    hasPassedProfileSelectionGate = true
    switchProfileAndApplyLocale(profileId)
}
```

This preserves direct profile switching behavior and prevents the app from routing back through `ProfileSelectionScreen`.

- [ ] **Step 6: Run compile check**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.StartupResumePolicyTest
```

Expected: `BUILD SUCCESSFUL`.

Do not commit yet. Task 3 updates the contract tests for these exact code paths.

---

### Task 3: Update Profile Selection Contract Tests

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/ProfileSelectionContractTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/ProfileSelectionContractTest.kt`

- [ ] **Step 1: Update existing startup selection contract**

In `ProfileSelectionContractTest`, update `startup profile selection waits for active profile write before exiting gate`.

Replace:

```kotlin
assertTrue(callback.indexOf("hasSelectedProfileThisSession = true") >= 0)
assertTrue(
    "The profile gate must exit only after setActiveProfile completes, otherwise the first click/PIN submit renders content under the previous active profile.",
    callback.indexOf("profileManager.setActiveProfile(profileId)") <
        callback.indexOf("hasSelectedProfileThisSession = true")
)
```

with:

```kotlin
assertTrue(callback.indexOf("processProfileSelectionGatePassed = true") >= 0)
assertTrue(callback.indexOf("hasPassedProfileSelectionGate = true") >= 0)
assertTrue(
    "The profile gate must exit only after setActiveProfile completes, otherwise the first click/PIN submit renders content under the previous active profile.",
    callback.indexOf("profileManager.setActiveProfile(profileId)") <
        callback.indexOf("hasPassedProfileSelectionGate = true")
)
```

- [ ] **Step 2: Add a contract test for sidebar profile switching**

Append this test to `ProfileSelectionContractTest`:

```kotlin
@Test
fun `sidebar profile switch does not reopen startup profile selection gate`() {
    val source = mainActivity.readText()

    assertTrue(
        "Direct profile switching must not reset the startup gate; otherwise normal sidebar clicks can send users back to profile selection.",
        !source.contains("hasSelectedProfileThisSession = false")
    )
    assertTrue(
        "Direct profile switching should preserve the process-level gate before changing profiles.",
        source.contains("processProfileSelectionGatePassed = true")
    )
    assertTrue(
        "Direct profile switching should preserve the composable gate before changing profiles.",
        source.contains("hasPassedProfileSelectionGate = true")
    )
}
```

- [ ] **Step 3: Run the contract test**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.ProfileSelectionContractTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the profile gate tests together**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.StartupResumePolicyTest --tests com.nexio.tv.ui.ProfileSelectionContractTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit the MainActivity and contract changes**

```bash
git add app/src/main/java/com/nexio/tv/MainActivity.kt app/src/test/java/com/nexio/tv/ui/ProfileSelectionContractTest.kt
git commit -m "fix(profile): keep startup profile gate closed"
```

---

### Task 4: Device Validation on 192.168.50.71

**Files:**
- No source files changed in this task.
- Validate built APK behavior on device `192.168.50.71`.

- [ ] **Step 1: Build the release APK used for device validation**

Run:

```bash
./gradlew assembleUniversalRelease
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install without clearing app data**

Run:

```bash
adb -s 192.168.50.71:5555 install -r app/build/outputs/apk/universal/release/app-universal-release.apk
```

Expected output includes:

```text
Performing Streamed Install
Success
```

- [ ] **Step 3: Clear logcat and launch**

Run:

```bash
adb -s 192.168.50.71:5555 logcat -c
adb -s 192.168.50.71:5555 shell am force-stop com.nexio.tv
adb -s 192.168.50.71:5555 shell monkey -p com.nexio.tv 1
```

Expected: app launches to the profile selection screen if multiple profiles are configured.

- [ ] **Step 4: Manual interaction validation**

On the device:

1. Select a profile.
2. Navigate Home, Library, Search, Settings, and back to Home.
3. Open the sidebar profile switcher.
4. Switch directly to another profile from the sidebar.
5. Navigate Home and Library again.

Expected:

- The profile selection screen appears at most once on initial app launch.
- Direct sidebar profile switch changes the active profile without showing `ProfileSelectionScreen`.
- Normal navigation does not return to profile selection.
- No auth QR/logon screen appears unless explicitly opened from Account/Settings.

- [ ] **Step 5: Check logcat for profile gate regressions**

Run:

```bash
adb -s 192.168.50.71:5555 logcat -d -t 6000 | rg -n "ProfileSelection|profile_selection|setActiveProfile|FATAL EXCEPTION|AndroidRuntime" -C 4
```

Expected:

- No `FATAL EXCEPTION`.
- `ProfileSelection` logs/screens only correspond to the initial launch selection.
- No repeated profile selection after sidebar switching.

- [ ] **Step 6: Commit validation notes if no code changed**

No commit is required if Task 4 only validates behavior. If validation finds a new issue, stop and return to root-cause investigation before changing implementation.

---

## Self-Review

**Spec coverage:** The plan targets the corrected symptom: returning to profile selection, not QR auth. Task 1 defines the gate rule, Task 2 applies it in `MainActivity`, Task 3 prevents regressions in startup and sidebar profile paths, and Task 4 validates on `192.168.50.71`.

**Placeholder scan:** No `TBD`, `TODO`, “similar to”, or open-ended implementation steps remain. Each code edit has concrete code and exact file paths.

**Type consistency:** The plan consistently uses `hasPassedProfileSelectionGate`, `processProfileSelectionGatePassed`, and `shouldShowStartupProfileSelection`. The test names and code snippets match the implementation names.

