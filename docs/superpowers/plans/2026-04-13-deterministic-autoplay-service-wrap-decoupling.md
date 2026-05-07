# Deterministic Autoplay Service Wrap Decoupling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make deterministic autoplay available independently from Service Wrap while preserving existing autoplay bandwidth fallback behavior.

**Architecture:** Move the deterministic autoplay availability rule into a small shared settings helper so both settings surfaces use the same dependency model. The rule must not accept or inspect `serviceWrapEnabled`; deterministic autoplay availability is based on the effective autoplay bandwidth mode and benchmark availability only. Service Wrap remains a separate debrid-wrapping feature and disabling it must not disable deterministic autoplay.

**Tech Stack:** Kotlin, Android Jetpack Compose, Hilt ViewModels, Kotlin Flow, JUnit4, Gradle Android unit tests.

---

## File Structure

- Create `app/src/main/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailability.kt` for shared availability helpers in the existing settings package.
- Create `app/src/test/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailabilityTest.kt` for focused JVM tests that prove the helper has no Service Wrap dependency.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt` to use the shared helper and remove the file-local `PlayerSettings.effectiveAutoplayBandwidthMode` extension.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt` to remove `serviceWrapEnabled` from deterministic autoplay availability and to stop disabling deterministic autoplay when Service Wrap is turned off.
- Modify `app/src/main/res/values/strings.xml` to remove the inaccurate “Requires Service Wrap” copy.

### Task 1: Add Shared Deterministic Autoplay Availability Helper

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailability.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailabilityTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailabilityTest.kt` with this content:

```kotlin
package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.local.AutoplayBandwidthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicAutoplayAvailabilityTest {

    @Test
    fun `manual mode makes deterministic autoplay available without benchmark`() {
        assertEquals(
            AutoplayBandwidthMode.MANUAL,
            AutoplayBandwidthMode.MANUAL.effectiveAutoplayBandwidthMode(
                autoplayBenchmarkAvailable = false
            )
        )

        assertTrue(
            AutoplayBandwidthMode.MANUAL.isDeterministicAutoplayAvailable(
                autoplayBenchmarkAvailable = false
            )
        )
    }

    @Test
    fun `auto mode falls back to manual when benchmark is unavailable`() {
        assertEquals(
            AutoplayBandwidthMode.MANUAL,
            AutoplayBandwidthMode.AUTO.effectiveAutoplayBandwidthMode(
                autoplayBenchmarkAvailable = false
            )
        )

        assertTrue(
            AutoplayBandwidthMode.AUTO.isDeterministicAutoplayAvailable(
                autoplayBenchmarkAvailable = false
            )
        )
    }

    @Test
    fun `auto mode remains auto when benchmark is available`() {
        assertEquals(
            AutoplayBandwidthMode.AUTO,
            AutoplayBandwidthMode.AUTO.effectiveAutoplayBandwidthMode(
                autoplayBenchmarkAvailable = true
            )
        )

        assertTrue(
            AutoplayBandwidthMode.AUTO.isDeterministicAutoplayAvailable(
                autoplayBenchmarkAvailable = true
            )
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DeterministicAutoplayAvailabilityTest"
```

Expected: FAIL with unresolved references for `effectiveAutoplayBandwidthMode` and `isDeterministicAutoplayAvailable`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailability.kt` with this content:

```kotlin
package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.local.AutoplayBandwidthMode

internal fun AutoplayBandwidthMode.effectiveAutoplayBandwidthMode(
    autoplayBenchmarkAvailable: Boolean
): AutoplayBandwidthMode {
    return if (this == AutoplayBandwidthMode.AUTO && !autoplayBenchmarkAvailable) {
        AutoplayBandwidthMode.MANUAL
    } else {
        this
    }
}

internal fun AutoplayBandwidthMode.isDeterministicAutoplayAvailable(
    autoplayBenchmarkAvailable: Boolean
): Boolean {
    val effectiveBandwidthMode = effectiveAutoplayBandwidthMode(autoplayBenchmarkAvailable)
    return effectiveBandwidthMode == AutoplayBandwidthMode.MANUAL || autoplayBenchmarkAvailable
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DeterministicAutoplayAvailabilityTest"
```

Expected: PASS with `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailability.kt app/src/test/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailabilityTest.kt
git commit -m "test: cover deterministic autoplay availability"
```

Expected: commit succeeds and only the two helper/test files are included.

### Task 2: Use Shared Availability in Playback Settings

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailabilityTest.kt`

- [ ] **Step 1: Update PlaybackAutoPlaySettings availability calculation**

In `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt`, replace the block at the top of `autoPlaySettingsItems`:

```kotlin
    val effectiveBandwidthMode = playerSettings.effectiveAutoplayBandwidthMode(autoplayBenchmarkAvailable)
    val deterministicAutoplayAvailable =
        playerSettings.serviceWrapEnabled &&
            (effectiveBandwidthMode == AutoplayBandwidthMode.MANUAL || autoplayBenchmarkAvailable)
```

with:

```kotlin
    val effectiveBandwidthMode = playerSettings.autoplayBandwidthMode.effectiveAutoplayBandwidthMode(
        autoplayBenchmarkAvailable
    )
    val deterministicAutoplayAvailable =
        playerSettings.autoplayBandwidthMode.isDeterministicAutoplayAvailable(autoplayBenchmarkAvailable)
```

- [ ] **Step 2: Remove the obsolete private extension**

In `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt`, delete this function near the bottom of the file:

```kotlin
private fun PlayerSettings.effectiveAutoplayBandwidthMode(
    autoplayBenchmarkAvailable: Boolean
): AutoplayBandwidthMode {
    return if (autoplayBandwidthMode == AutoplayBandwidthMode.AUTO && !autoplayBenchmarkAvailable) {
        AutoplayBandwidthMode.MANUAL
    } else {
        autoplayBandwidthMode
    }
}
```

- [ ] **Step 3: Update the unavailable subtitle copy**

In `app/src/main/res/values/strings.xml`, replace:

```xml
    <string name="autoplay_deterministic_unavailable_sub">Requires Service Wrap. Auto bandwidth mode also needs a current valid benchmark.</string>
```

with:

```xml
    <string name="autoplay_deterministic_unavailable_sub">Manual mode works without Service Wrap. Auto mode uses Manual until a current valid benchmark exists.</string>
```

- [ ] **Step 4: Run focused verification**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DeterministicAutoplayAvailabilityTest"
```

Expected: PASS with `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt app/src/main/res/values/strings.xml
git commit -m "fix: decouple deterministic autoplay from service wrap settings"
```

Expected: commit succeeds and only the playback settings file plus base strings file are included.

### Task 3: Decouple Debrid Settings State from Service Wrap

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailabilityTest.kt`

- [ ] **Step 1: Add the benchmark eligibility import**

In `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`, add this import next to the existing benchmark imports:

```kotlin
import com.nexio.tv.data.repository.benchmark.hasValidAutoplayBenchmarkFor
```

- [ ] **Step 2: Replace the deterministic autoplay availability flow**

In `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`, replace this block:

```kotlin
        val serviceWrapEnabled = playerSettingsSnapshot.map { it.serviceWrapEnabled }

        val deterministicAutoplayAvailable = combine(
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.REAL_DEBRID),
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.PREMIUMIZE),
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.TORBOX),
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.EASY_DEBRID),
            serviceWrapEnabled
        ) { latestRealDebridResult, latestPremiumizeResult, latestTorBoxResult, latestEasyDebridResult, serviceWrapEnabled ->
            (
                latestRealDebridResult != null ||
                    latestPremiumizeResult != null ||
                    latestTorBoxResult != null ||
                    latestEasyDebridResult != null
                ) && serviceWrapEnabled
        }
```

with:

```kotlin
        val deterministicAutoplayAvailable = combine(
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.REAL_DEBRID),
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.PREMIUMIZE),
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.TORBOX),
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.EASY_DEBRID),
            playerSettingsDataStore.playerSettings
        ) { latestRealDebridResult, latestPremiumizeResult, latestTorBoxResult, latestEasyDebridResult, settings ->
            val autoplayBenchmarkAvailable = listOfNotNull(
                latestRealDebridResult,
                latestPremiumizeResult,
                latestTorBoxResult,
                latestEasyDebridResult
            ).any { result ->
                result.hasValidAutoplayBenchmarkFor(settings)
            }

            settings.autoplayBandwidthMode.isDeterministicAutoplayAvailable(autoplayBenchmarkAvailable)
        }
```

- [ ] **Step 3: Stop disabling deterministic autoplay when Service Wrap is disabled**

In `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`, replace:

```kotlin
    fun setServiceWrapEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !uiState.value.serviceWrapAvailable) return@launch
            playerSettingsDataStore.setServiceWrapEnabled(enabled)
            if (!enabled && uiState.value.deterministicAutoplayEnabled) {
                playerSettingsDataStore.setDeterministicAutoplayEnabled(false)
            }
        }
    }
```

with:

```kotlin
    fun setServiceWrapEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !uiState.value.serviceWrapAvailable) return@launch
            playerSettingsDataStore.setServiceWrapEnabled(enabled)
        }
    }
```

- [ ] **Step 4: Run focused verification**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DeterministicAutoplayAvailabilityTest"
```

Expected: PASS with `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run Kotlin compile verification**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS with `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt
git commit -m "fix: keep deterministic autoplay when service wrap is disabled"
```

Expected: commit succeeds and only `DebridSettingsContent.kt` is included.

### Task 4: Final Regression Checks

**Files:**
- Verify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailability.kt`
- Verify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt`
- Verify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
- Verify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Confirm no deterministic autoplay availability rule still requires Service Wrap**

Run:

```bash
rg -n "deterministicAutoplayAvailable|isDeterministicAutoplayAvailable|autoplay_deterministic_unavailable_sub|setServiceWrapEnabled" app/src/main/java/com/nexio/tv/ui/screens/settings app/src/main/res/values/strings.xml
```

Expected: output includes one or more matches in these files:

```text
app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt
app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt
app/src/main/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailability.kt
app/src/main/res/values/strings.xml
```

Expected: no output line combines `deterministicAutoplayAvailable` with `serviceWrapEnabled`, `playerSettings.serviceWrapEnabled`, or `setDeterministicAutoplayEnabled(false)`.

- [ ] **Step 2: Run focused unit test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DeterministicAutoplayAvailabilityTest"
```

Expected: PASS with `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run Kotlin compile verification**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS with `BUILD SUCCESSFUL`.

- [ ] **Step 4: Inspect staged and unstaged changes**

Run:

```bash
git status --short
```

Expected: only unrelated pre-existing changes may remain outside this plan. The plan-owned files should either be committed or appear as intentional changes:

```text
app/src/main/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailability.kt
app/src/test/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailabilityTest.kt
app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt
app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt
app/src/main/res/values/strings.xml
```

- [ ] **Step 5: Final commit if Task 4 changed files**

If Task 4 inspection or formatting produced additional changes in plan-owned files, run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailability.kt app/src/test/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailabilityTest.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt app/src/main/res/values/strings.xml
git commit -m "chore: verify deterministic autoplay service wrap decoupling"
```

Expected: commit succeeds when there are remaining plan-owned changes; if there are no plan-owned changes, `git status --short` is unchanged and this commit step is skipped.

## Self-Review

- Spec coverage: The plan removes the Service Wrap dependency from the playback settings UI, removes the Service Wrap dependency from the debrid settings state, preserves existing bandwidth fallback behavior, stops disabling deterministic autoplay when Service Wrap is disabled, updates inaccurate user-facing copy, and adds a focused test for the shared rule.
- Placeholder scan: The plan contains exact file paths, exact code replacements, exact commands, and expected outcomes for each verification step.
- Type consistency: The shared helper uses `AutoplayBandwidthMode`, the existing `PlayerSettings.autoplayBandwidthMode` property, and the existing `DebridBenchmarkResult.hasValidAutoplayBenchmarkFor(PlayerSettings)` extension.
