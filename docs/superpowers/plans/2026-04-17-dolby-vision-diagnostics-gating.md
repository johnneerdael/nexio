# Dolby Vision Diagnostics Gating Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in Dolby Vision diagnostics for release/debug builds while moving high-frequency DV7 conversion success logging behind a Playback Settings troubleshooting toggle.

**Architecture:** Store a local-only diagnostics flag in `DebugSettingsDataStore`, surface it in Playback Settings → Logging, observe it in `PlayerRuntimeController`, and propagate it to `DoviBridge`, `MatroskaDolbyVisionHookInstaller`, and `Dv5HardwareToneMapRpuTap`. The native `DoviBridgeNative` per-conversion success log is disabled by default and enabled only when the flag is on.

**Tech Stack:** Kotlin, Jetpack DataStore, Compose settings UI, Media3 player runtime, JNI/C++ native bridge logging, JUnit/Robolectric tests.

---

## Scope

This plan implements:

- A new local troubleshooting toggle: Dolby Vision diagnostics.
- Native per-conversion success log gating for `DoviBridgeNative`.
- Lightweight opt-in telemetry counters for DV conversion allocation-shaped paths.
- Optional inclusion of DV allocation telemetry in existing `BUFFER:` logs when both buffer logs and Dolby Vision diagnostics are enabled.

This plan intentionally does not:

- Change Dolby Vision conversion behavior.
- Disable DV7→DV8.1 conversion.
- Add HDR10-base-layer fallback.
- Change disk spool, PRDS, VOD cache, progress saving, subtitles, or TheIntroDB.
- Sync the new troubleshooting flag through account/profile settings.

## Current Evidence

- Release logs showed high-frequency native logging:

```text
DoviBridgeNative: nativeConvertDv7RpuToDv81 converted 227 bytes -> 189 bytes (mode=2)
```

- This log is emitted unconditionally in `app/src/main/cpp/dovi_bridge.cpp` after every successful native conversion.
- Existing low-frequency startup logs such as `DV7_DOVI: ...` are not the issue and should remain available.
- Existing playback buffer logs already include basic DV counters, but only when `enableBufferLogs` is enabled.
- Existing disk spool diagnostics already use `DebugSettingsDataStore`, which is the right persistence pattern for local troubleshooting toggles.

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt`
  - Add `dolbyVisionDiagnosticsEnabled` flow and setter.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
  - Expose the new flow and setter to Playback Settings.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - Collect the new flow and pass it into `PlaybackSettingsSections`.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - Add a toggle in the existing Logging section.

- Modify: `app/src/main/res/values/strings.xml`
  - Add title/subtitle strings for the Dolby Vision diagnostics toggle.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
  - Observe the new toggle and propagate it into DV diagnostics/logging components.

- Modify: `app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt`
  - Add verbose logging setter, diagnostics snapshot, and gated byte counters.

- Modify: `app/src/main/cpp/dovi_bridge.cpp`
  - Add native verbose logging flag and JNI setter.
  - Gate only high-frequency conversion success logs.

- Modify: `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`
  - Add opt-in allocation counters and snapshot.

- Modify: `app/src/main/java/com/nexio/tv/core/player/Dv5HardwareToneMapRpuTap.kt`
  - Add opt-in RPU queue/copy counters and snapshot.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
  - Append DV allocation telemetry to `BUFFER:` only when both buffer logs and DV diagnostics are enabled.

- Tests:
  - Create/modify `app/src/test/java/com/nexio/tv/core/player/DolbyVisionDiagnosticsTest.kt`
  - Modify `app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModelTest.kt`

---

### Task 1: Add Local Dolby Vision Diagnostics Setting

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModelTest.kt`

- [ ] **Step 1: Add failing datastore/viewmodel test**

Add this test to `PlaybackSettingsViewModelTest.kt` if the file already contains local settings tests. If the file is not structured for injected stores, create a focused test in `app/src/test/java/com/nexio/tv/data/local/DebugSettingsDataStoreTest.kt` instead:

```kotlin
@Test
fun `dolby vision diagnostics defaults disabled and can be toggled`() = runTest {
    val store = DebugSettingsDataStore(context = ApplicationProvider.getApplicationContext())

    assertFalse(store.dolbyVisionDiagnosticsEnabled.first())

    store.setDolbyVisionDiagnosticsEnabled(true)

    assertTrue(store.dolbyVisionDiagnosticsEnabled.first())
}
```

Use the same test pattern currently used by existing DataStore tests in this repo. If a unique preferences file is required in tests, follow the existing local DataStore test helper pattern rather than sharing production storage.

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests '*dolby vision diagnostics defaults disabled and can be toggled'
```

Expected: FAIL because `dolbyVisionDiagnosticsEnabled` and `setDolbyVisionDiagnosticsEnabled(...)` do not exist.

- [ ] **Step 3: Add key, flow, and setter**

In `DebugSettingsDataStore.kt`, add:

```kotlin
private val dolbyVisionDiagnosticsEnabledKey =
    booleanPreferencesKey("dolby_vision_diagnostics_enabled")
```

Add:

```kotlin
val dolbyVisionDiagnosticsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
    prefs[dolbyVisionDiagnosticsEnabledKey] ?: false
}
```

Add:

```kotlin
suspend fun setDolbyVisionDiagnosticsEnabled(enabled: Boolean) {
    dataStore.edit { prefs ->
        prefs[dolbyVisionDiagnosticsEnabledKey] = enabled
    }
}
```

In `PlaybackSettingsViewModel.kt`, add:

```kotlin
val dolbyVisionDiagnosticsEnabled: Flow<Boolean> =
    debugSettingsDataStore.dolbyVisionDiagnosticsEnabled

suspend fun setDolbyVisionDiagnosticsEnabled(enabled: Boolean) {
    debugSettingsDataStore.setDolbyVisionDiagnosticsEnabled(enabled)
}
```

- [ ] **Step 4: Run test and verify GREEN**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests '*dolby vision diagnostics defaults disabled and can be toggled'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModelTest.kt app/src/test/java/com/nexio/tv/data/local/DebugSettingsDataStoreTest.kt
git commit -m "add dolby vision diagnostics setting"
```

---

### Task 2: Add Playback Settings Toggle

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add UI parameters**

In `PlaybackSettingsContent(...)`, collect:

```kotlin
val dolbyVisionDiagnosticsEnabled by viewModel.dolbyVisionDiagnosticsEnabled
    .collectAsStateWithLifecycle(initialValue = false)
```

Pass into `PlaybackSettingsSections(...)`:

```kotlin
dolbyVisionDiagnosticsEnabled = dolbyVisionDiagnosticsEnabled,
onSetDolbyVisionDiagnosticsEnabled = { enabled ->
    coroutineScope.launch { viewModel.setDolbyVisionDiagnosticsEnabled(enabled) }
}
```

In `PlaybackSettingsSections(...)`, add parameters near existing logging toggles:

```kotlin
dolbyVisionDiagnosticsEnabled: Boolean,
onSetDolbyVisionDiagnosticsEnabled: (Boolean) -> Unit,
```

- [ ] **Step 2: Add strings**

In `strings.xml`, add:

```xml
<string name="playback_logging_dolby_vision_diagnostics_title">Dolby Vision diagnostics</string>
<string name="playback_logging_dolby_vision_diagnostics_subtitle">Log DV conversion counters and native conversion details for troubleshooting. Leave off during normal playback.</string>
```

- [ ] **Step 3: Add toggle row**

In the Logging section in `PlaybackSettingsSections.kt`, after disk spool diagnostics, add:

```kotlin
item(key = "logging_dolby_vision_diagnostics") {
    ToggleSettingsItem(
        icon = Icons.Default.Visibility,
        title = stringResource(R.string.playback_logging_dolby_vision_diagnostics_title),
        subtitle = stringResource(R.string.playback_logging_dolby_vision_diagnostics_subtitle),
        isChecked = dolbyVisionDiagnosticsEnabled,
        onCheckedChange = onSetDolbyVisionDiagnosticsEnabled,
        onFocused = { focusedSection = PlaybackSection.LOGGING }
    )
}
```

If `Icons.Default.Visibility` is not imported/available, use `Icons.Default.Tune`, matching existing troubleshooting items.

- [ ] **Step 4: Compile settings code**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.settings.PlaybackSettingsViewModelTest
```

Expected: PASS or no matching tests if the class does not exist. If no matching tests exist, run:

```bash
./gradlew -q :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt app/src/main/res/values/strings.xml
git commit -m "surface dolby vision diagnostics toggle"
```

---

### Task 3: Gate Native DoviBridge Success Logging

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt`
- Modify: `app/src/main/cpp/dovi_bridge.cpp`
- Create/modify: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionDiagnosticsTest.kt`

- [ ] **Step 1: Add failing Kotlin API test**

Create `DolbyVisionDiagnosticsTest.kt`:

```kotlin
package com.nexio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionDiagnosticsTest {

    @Test
    fun `dolby vision verbose logging flag is tracked in kotlin`() {
        DoviBridge.setVerboseLoggingEnabled(false)
        assertFalse(DoviBridge.isVerboseLoggingEnabled())

        DoviBridge.setVerboseLoggingEnabled(true)
        assertTrue(DoviBridge.isVerboseLoggingEnabled())

        DoviBridge.setVerboseLoggingEnabled(false)
    }
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest
```

Expected: FAIL because the methods do not exist.

- [ ] **Step 3: Add Kotlin flag and JNI setter**

In `DoviBridge.kt`, add:

```kotlin
@Volatile
private var verboseLoggingEnabled: Boolean = false

fun isVerboseLoggingEnabled(): Boolean = verboseLoggingEnabled

fun setVerboseLoggingEnabled(enabled: Boolean) {
    verboseLoggingEnabled = enabled
    if (isNativeEnabledInBuild && nativeLoaded) {
        runCatching { nativeSetVerboseLoggingEnabled(enabled) }
            .onFailure { Log.w(TAG, "Failed to set native verbose logging: ${it.message}") }
    }
}
```

Add external method:

```kotlin
@JvmStatic
private external fun nativeSetVerboseLoggingEnabled(enabled: Boolean)
```

- [ ] **Step 4: Gate native success log**

In `dovi_bridge.cpp`, add:

```cpp
#include <atomic>
static std::atomic<bool> g_verbose_logging_enabled(false);
```

Add JNI method:

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_nexio_tv_core_player_DoviBridge_nativeSetVerboseLoggingEnabled(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jboolean enabled
) {
    g_verbose_logging_enabled.store(enabled == JNI_TRUE, std::memory_order_relaxed);
}
```

Replace the per-conversion success log:

```cpp
LOGI(
    "nativeConvertDv7RpuToDv81 converted %d bytes -> %d bytes (mode=%u)",
    static_cast<int>(len),
    static_cast<int>(env->GetArrayLength(out)),
    static_cast<unsigned int>(conversion_mode)
);
```

with:

```cpp
if (g_verbose_logging_enabled.load(std::memory_order_relaxed)) {
    LOGI(
        "nativeConvertDv7RpuToDv81 converted %d bytes -> %d bytes (mode=%u)",
        static_cast<int>(len),
        static_cast<int>(env->GetArrayLength(out)),
        static_cast<unsigned int>(conversion_mode)
    );
}
```

Do not gate warnings for parse/convert/write failures.

- [ ] **Step 5: Run tests and compile native-aware build**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest
```

Expected: PASS.

Run:

```bash
./gradlew -q :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt app/src/main/cpp/dovi_bridge.cpp app/src/test/java/com/nexio/tv/core/player/DolbyVisionDiagnosticsTest.kt
git commit -m "gate native dolby vision conversion logging"
```

---

### Task 4: Add Opt-In Dolby Allocation Telemetry

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/player/Dv5HardwareToneMapRpuTap.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionDiagnosticsTest.kt`

- [ ] **Step 1: Add failing snapshot tests**

Append to `DolbyVisionDiagnosticsTest.kt`:

```kotlin
@Test
fun `dolby diagnostics snapshots are zero when disabled and reset`() {
    DoviBridge.setVerboseLoggingEnabled(false)
    DoviBridge.resetRuntimeCounters()
    MatroskaDolbyVisionHookInstaller.setDiagnosticsEnabled(false)
    MatroskaDolbyVisionHookInstaller.resetRuntimeCounters()
    Dv5HardwareToneMapRpuTap.setDiagnosticsEnabled(false)
    Dv5HardwareToneMapRpuTap.setEnabledForPlayback(false, "https://example.com/movie.mkv")

    val bridge = DoviBridge.runtimeDiagnosticsSnapshot()
    val hook = MatroskaDolbyVisionHookInstaller.runtimeAllocationSnapshot()
    val tap = Dv5HardwareToneMapRpuTap.runtimeSnapshot()

    assertFalse(bridge.enabled)
    assertFalse(hook.enabled)
    assertFalse(tap.enabled)
    assertEquals(0L, bridge.inputBytes)
    assertEquals(0L, hook.nalCopyBytes)
    assertEquals(0L, tap.copiedBytes)
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest
```

Expected: FAIL because snapshot APIs do not exist.

- [ ] **Step 3: Add `DoviBridge` diagnostics snapshot**

In `DoviBridge.kt`, add:

```kotlin
data class RuntimeDiagnosticsSnapshot(
    val enabled: Boolean,
    val inputBytes: Long,
    val outputBytes: Long,
    val failedConversions: Long
)
```

Add counters:

```kotlin
private val diagnosticInputBytes = AtomicLong(0L)
private val diagnosticOutputBytes = AtomicLong(0L)
private val diagnosticFailedConversions = AtomicLong(0L)
```

In `resetRuntimeCounters()`, clear them.

In `convertDv7RpuToDv81(...)`, only when `verboseLoggingEnabled` is true:

```kotlin
diagnosticInputBytes.addAndGet(payload.size.toLong())
```

After conversion:

```kotlin
if (verboseLoggingEnabled) {
    if (converted != null && converted.isNotEmpty()) {
        diagnosticOutputBytes.addAndGet(converted.size.toLong())
    } else {
        diagnosticFailedConversions.incrementAndGet()
    }
}
```

Add:

```kotlin
fun runtimeDiagnosticsSnapshot(): RuntimeDiagnosticsSnapshot {
    return RuntimeDiagnosticsSnapshot(
        enabled = verboseLoggingEnabled,
        inputBytes = diagnosticInputBytes.get(),
        outputBytes = diagnosticOutputBytes.get(),
        failedConversions = diagnosticFailedConversions.get()
    )
}
```

- [ ] **Step 4: Add hook allocation snapshot gated by diagnostics**

In `MatroskaDolbyVisionHookInstaller.kt`, add:

```kotlin
data class AllocationSnapshot(
    val enabled: Boolean,
    val rewriteSampleCalls: Long,
    val rewriteInputBytes: Long,
    val rewriteOutputBytes: Long,
    val nalCopyBytes: Long,
    val appendedSampleBytes: Long,
    val rpuNalTransformCalls: Long
)
```

Add:

```kotlin
@Volatile
private var diagnosticsEnabled = false
private val rewriteSampleCalls = AtomicLong(0L)
private val rewriteInputBytes = AtomicLong(0L)
private val rewriteOutputBytes = AtomicLong(0L)
private val nalCopyBytes = AtomicLong(0L)
private val appendedSampleBytes = AtomicLong(0L)
private val rpuNalTransformCalls = AtomicLong(0L)
```

Add:

```kotlin
fun setDiagnosticsEnabled(enabled: Boolean) {
    diagnosticsEnabled = enabled
}

fun runtimeAllocationSnapshot(): AllocationSnapshot {
    return AllocationSnapshot(
        enabled = diagnosticsEnabled,
        rewriteSampleCalls = rewriteSampleCalls.get(),
        rewriteInputBytes = rewriteInputBytes.get(),
        rewriteOutputBytes = rewriteOutputBytes.get(),
        nalCopyBytes = nalCopyBytes.get(),
        appendedSampleBytes = appendedSampleBytes.get(),
        rpuNalTransformCalls = rpuNalTransformCalls.get()
    )
}
```

In `resetRuntimeCounters()`, reset all new counters.

Increment counters only inside `if (diagnosticsEnabled) { ... }` blocks at:

- start of `rewriteMp4HevcSample(...)`
- after `copyOfRange(...)`
- before returning rewritten sample bytes
- after `normalizeNuhLayerIdToZero(...)` copies
- after `appendLengthDelimitedNal(...)` allocates `out`
- start of `maybeConvertDolbyVisionRpuNal(...)`

- [ ] **Step 5: Add RPU tap diagnostics snapshot**

In `Dv5HardwareToneMapRpuTap.kt`, add:

```kotlin
data class RuntimeSnapshot(
    val enabled: Boolean,
    val queuedEntries: Int,
    val queuedBytes: Long,
    val copiedBytes: Long
)
```

Add:

```kotlin
@Volatile
private var diagnosticsEnabled = false
private val copiedBytes = AtomicLong(0L)

fun setDiagnosticsEnabled(enabled: Boolean) {
    diagnosticsEnabled = enabled
}

fun runtimeSnapshot(): RuntimeSnapshot {
    return synchronized(lock) {
        RuntimeSnapshot(
            enabled = diagnosticsEnabled,
            queuedEntries = queue.size,
            queuedBytes = if (diagnosticsEnabled) queue.values.sumOf { it.size.toLong() } else 0L,
            copiedBytes = copiedBytes.get()
        )
    }
}
```

In `setEnabledForPlayback(...)`, reset `copiedBytes`.

In `onRpuSample(...)`, after making the existing copy, add only when diagnostics are enabled:

```kotlin
if (diagnosticsEnabled) {
    copiedBytes.addAndGet(copy.size.toLong())
}
```

Do not remove the copy itself; it is part of current behavior.

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt app/src/main/java/com/nexio/tv/core/player/Dv5HardwareToneMapRpuTap.kt app/src/test/java/com/nexio/tv/core/player/DolbyVisionDiagnosticsTest.kt
git commit -m "add opt-in dolby vision allocation telemetry"
```

---

### Task 5: Wire Toggle To Player Runtime And Buffer Logs

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`

- [ ] **Step 1: Add runtime state**

In `PlayerRuntimeController.kt`, add:

```kotlin
@Volatile
internal var dolbyVisionDiagnosticsEnabled: Boolean = false
```

- [ ] **Step 2: Observe debug setting**

In `observeDebugSettings()`, add:

```kotlin
scope.launch {
    debugSettingsDataStore.dolbyVisionDiagnosticsEnabled.collectLatest { enabled ->
        dolbyVisionDiagnosticsEnabled = enabled
        DoviBridge.setVerboseLoggingEnabled(enabled)
        MatroskaDolbyVisionHookInstaller.setDiagnosticsEnabled(enabled)
        Dv5HardwareToneMapRpuTap.setDiagnosticsEnabled(enabled)
    }
}
```

Add imports for `DoviBridge`, `MatroskaDolbyVisionHookInstaller`, and `Dv5HardwareToneMapRpuTap` if not present.

- [ ] **Step 3: Gate buffer-log DV allocation telemetry**

In `PlayerRuntimeControllerPlaybackEvents.kt`, inside the `bufferLogJob` `buildString`, append extra DV diagnostics only when enabled:

```kotlin
if (dolbyVisionDiagnosticsEnabled) {
    val bridgeDiagnostics = DoviBridge.runtimeDiagnosticsSnapshot()
    val hookDiagnostics = MatroskaDolbyVisionHookInstaller.runtimeAllocationSnapshot()
    val tapDiagnostics = Dv5HardwareToneMapRpuTap.runtimeSnapshot()
    append(",dvDiag=on")
    append(",dvInMb=")
    append(bridgeDiagnostics.inputBytes / (1024L * 1024L))
    append(",dvOutMb=")
    append(bridgeDiagnostics.outputBytes / (1024L * 1024L))
    append(",dvFail=")
    append(bridgeDiagnostics.failedConversions)
    append(",rewriteCalls=")
    append(hookDiagnostics.rewriteSampleCalls)
    append(",rewriteInMb=")
    append(hookDiagnostics.rewriteInputBytes / (1024L * 1024L))
    append(",rewriteOutMb=")
    append(hookDiagnostics.rewriteOutputBytes / (1024L * 1024L))
    append(",nalCopyMb=")
    append(hookDiagnostics.nalCopyBytes / (1024L * 1024L))
    append(",appendMb=")
    append(hookDiagnostics.appendedSampleBytes / (1024L * 1024L))
    append(",rpuCalls=")
    append(hookDiagnostics.rpuNalTransformCalls)
    append(",rpuTapEntries=")
    append(tapDiagnostics.queuedEntries)
    append(",rpuTapQueuedKb=")
    append(tapDiagnostics.queuedBytes / 1024L)
    append(",rpuTapCopiedKb=")
    append(tapDiagnostics.copiedBytes / 1024L)
}
```

When disabled, do not call these snapshot methods in the buffer-log path.

- [ ] **Step 4: Compile targeted player code**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest --tests com.nexio.tv.ui.screens.player.PlayerPlaybackProgressUiStateTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt
git commit -m "wire dolby diagnostics into playback runtime"
```

---

### Task 6: Verification

**Files:**
- No production changes unless verification reveals a real issue.

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest \
  --tests com.nexio.tv.ui.screens.settings.PlaybackSettingsViewModelTest \
  --tests com.nexio.tv.ui.screens.player.PlayerPlaybackProgressUiStateTest
```

Expected: PASS. If `PlaybackSettingsViewModelTest` is not the right class for this repo, run the concrete DataStore test class used in Task 1 instead.

- [ ] **Step 2: Compile debug and release variants**

Run:

```bash
./gradlew -q :app:assembleDebug
```

Expected: PASS.

Run:

```bash
./gradlew -q :app:assembleRelease
```

Expected: PASS, unless release signing credentials are intentionally unavailable locally. If signing blocks the release build, run the repo’s existing unsigned/universal release assemble task if present and record the exact command/result.

- [ ] **Step 3: Run diff hygiene**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 4: Device validation**

Install the build and test on `192.168.50.58`.

With Dolby Vision diagnostics disabled:

```bash
adb -s 192.168.50.58:5555 logcat -c
sleep 30
adb -s 192.168.50.58:5555 logcat -d | rg -n "DoviBridgeNative: nativeConvertDv7RpuToDv81 converted|BUFFER:.*dvDiag=on"
```

Expected:

- No high-frequency `DoviBridgeNative: nativeConvertDv7RpuToDv81 converted...` lines.
- No `BUFFER: ... dvDiag=on` lines.
- Failure/warning logs still appear if conversion fails.

With Dolby Vision diagnostics enabled and buffer logs enabled:

```bash
adb -s 192.168.50.58:5555 logcat -c
sleep 60
adb -s 192.168.50.58:5555 logcat -d | rg -n "DoviBridgeNative: nativeConvertDv7RpuToDv81 converted|BUFFER:.*dvDiag=on|Background concurrent mark compact GC|WaitForGcToComplete"
```

Expected:

- Native per-conversion success logs may appear.
- `BUFFER:` lines include `dvDiag=on`.
- DV counters increase during DV playback.

---

## Self-Review

**Spec coverage:** This plan gates the current high-frequency native conversion logging, adds opt-in release-safe telemetry, and places the toggle in the Playback Settings troubleshooting/logging section.

**Placeholder scan:** No TBD/TODO/fill-in instructions. All behavior, APIs, tests, and validation commands are explicit.

**Type consistency:** New names are consistent: `dolbyVisionDiagnosticsEnabled`, `setDolbyVisionDiagnosticsEnabled`, `setVerboseLoggingEnabled`, `runtimeDiagnosticsSnapshot`, `runtimeAllocationSnapshot`, and `Dv5HardwareToneMapRpuTap.runtimeSnapshot`.
