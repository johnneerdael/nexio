# NEXIO — OLED Burn-in Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the seven changes specified in `docs/superpowers/specs/2026-04-27-oled-burnin-hardening-design.md` — close burn-in vectors in pause overlay, trailer player, focus rings, sidebar, loading overlay, and captions, plus add a configurable screensaver delay.

**Architecture:** Each change is localized; tasks are independent and may be merged in any order. Pure-Kotlin policy helpers carry the testable logic where the existing UI is hard to unit-test (mirroring the existing `PauseOverlayVisibilityPolicy` pattern). Migrations are silent and one-shot, run during DataStore read.

**Tech Stack:** Kotlin, Jetpack Compose, Media3/ExoPlayer, AndroidX DataStore (Preferences), JUnit4, Robolectric.

---

## Conventions

- Working dir: `/Users/jneerdael/Scripts/nexio`.
- Run tests with: `./gradlew :app:testUniversalDebugUnitTest --tests <pattern>`.
- Run full test suite: `./gradlew :app:testUniversalDebugUnitTest`.
- Build: `./gradlew :app:assembleDebug`.
- Commit per task; do NOT use `--no-verify`.
- Each task is bite-sized (TDD where unit-testable).

---

## Task 1 — R1: Pause overlay no longer blocks the screensaver

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/MainActivity.kt` (eligibility function `isIdleScreensaverEligibleRoute`)
- Test: `app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt`

### - [ ] Step 1.1: Read current eligibility function

Locate `isIdleScreensaverEligibleRoute` in `MainActivity.kt` (referenced by the existing test `MainActivityIdleScreensaverTest.kt`). Note the line range and how it consumes `playbackIdleSnapshot.isPausedByUser`.

Run: `grep -n "isIdleScreensaverEligibleRoute" app/src/main/java/com/nexio/tv/MainActivity.kt app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt`

### - [ ] Step 1.2: Add failing test for paused-state eligibility

Append to `MainActivityIdleScreensaverTest.kt`:

```kotlin
@Test
fun `home route is eligible while playback is paused by user`() {
    assertTrue(
        isIdleScreensaverEligibleRoute(
            currentRoute = Screen.Home.route,
            playbackIdleSnapshot = PlaybackIdleGateSnapshot(
                hasActiveSession = true,
                isPausedByUser = true
            ),
            inAppTrailerPlaybackActive = false
        )
    )
}

@Test
fun `player route is eligible while playback is paused by user`() {
    assertTrue(
        isIdleScreensaverEligibleRoute(
            currentRoute = Screen.Player.route,
            playbackIdleSnapshot = PlaybackIdleGateSnapshot(
                hasActiveSession = true,
                isPausedByUser = true
            ),
            inAppTrailerPlaybackActive = false
        )
    )
}
```

### - [ ] Step 1.3: Run new tests, verify they fail

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.MainActivityIdleScreensaverTest"`
Expected: the two new tests fail with `expected:<true> but was:<false>` because the current eligibility excludes paused playback.

### - [ ] Step 1.4: Update eligibility function to ignore `isPausedByUser`

In `MainActivity.kt`, locate `isIdleScreensaverEligibleRoute`. Remove the `&& !playbackIdleSnapshot.isPausedByUser` (or equivalent) clause so eligibility no longer depends on pause state. Keep `hasActiveSession` and route checks intact. Do NOT delete `isPausedByUser` from `PlaybackIdleGateSnapshot` — it may have other consumers.

### - [ ] Step 1.5: Run all idle screensaver tests, verify all pass

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.MainActivityIdleScreensaverTest"`
Expected: every test passes, including the two new ones AND the pre-existing `home route is not eligible while modern home trailer is active`.

### - [ ] Step 1.6: Commit

```bash
git add app/src/main/java/com/nexio/tv/MainActivity.kt \
        app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt
git commit -m "fix(screensaver): allow screensaver to fire while pause overlay is shown

Pause overlay was blocking idle screensaver eligibility, leaving a bright
mostly-static surface visible indefinitely. Drop the isPausedByUser gate;
the screensaver now layers over the pause overlay after the configured idle
delay. R1 from oled-burnin-hardening spec."
```

---

## Task 2 — R2: TrailerPlayer scope `keepScreenOn` and consume pause

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/components/TrailerKeepScreenOnPolicy.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt:307-349`
- Test: `app/src/test/java/com/nexio/tv/ui/components/TrailerKeepScreenOnPolicyTest.kt`

### - [ ] Step 2.1: Write the failing policy test

Create `app/src/test/java/com/nexio/tv/ui/components/TrailerKeepScreenOnPolicyTest.kt`:

```kotlin
package com.nexio.tv.ui.components

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class TrailerKeepScreenOnPolicyTest {
    @Test fun `keepScreenOn is true while playing`() {
        assertTrue(shouldKeepScreenOnForTrailer(isPlaying = true, isBuffering = false))
    }

    @Test fun `keepScreenOn is true while buffering`() {
        assertTrue(shouldKeepScreenOnForTrailer(isPlaying = false, isBuffering = true))
    }

    @Test fun `keepScreenOn is false while paused`() {
        assertFalse(shouldKeepScreenOnForTrailer(isPlaying = false, isBuffering = false))
    }

    @Test fun `pause key is consumed`() {
        assertTrue(shouldConsumeTrailerKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertTrue(shouldConsumeTrailerKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test fun `non-pause keys are not consumed`() {
        assertFalse(shouldConsumeTrailerKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(shouldConsumeTrailerKey(android.view.KeyEvent.KEYCODE_BACK))
    }
}
```

### - [ ] Step 2.2: Run tests, verify they fail

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.components.TrailerKeepScreenOnPolicyTest"`
Expected: compile failure (`shouldKeepScreenOnForTrailer`, `shouldConsumeTrailerKey` unresolved).

### - [ ] Step 2.3: Implement the policy

Create `app/src/main/java/com/nexio/tv/ui/components/TrailerKeepScreenOnPolicy.kt`:

```kotlin
package com.nexio.tv.ui.components

import android.view.KeyEvent

internal fun shouldKeepScreenOnForTrailer(isPlaying: Boolean, isBuffering: Boolean): Boolean =
    isPlaying || isBuffering

internal fun shouldConsumeTrailerKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> true
    else -> false
}
```

### - [ ] Step 2.4: Run tests, verify they pass

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.components.TrailerKeepScreenOnPolicyTest"`
Expected: all 5 tests pass.

### - [ ] Step 2.5: Wire policy into `TrailerPlayer.kt`

In `TrailerPlayer.kt` around lines 307-349 (the `AndroidView` factory block):

1. Inside the composable scope (above the `AndroidView`), capture playback state from `trailerPlayer`. Add:

```kotlin
val isBuffering by produceState(initialValue = false, trailerPlayer) {
    val listener = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            value = state == androidx.media3.common.Player.STATE_BUFFERING
        }
    }
    trailerPlayer?.addListener(listener)
    awaitDispose { trailerPlayer?.removeListener(listener) }
}
val keepOn = shouldKeepScreenOnForTrailer(isPlaying = isPlaying, isBuffering = isBuffering)
```

2. Replace the hard-coded `keepScreenOn = true` line in the `apply { ... }` block with:

```kotlin
keepScreenOn = keepOn
```

3. Add an `update` block to the `AndroidView` so `keepScreenOn` updates when `keepOn` changes:

```kotlin
AndroidView(
    factory = { ctx -> /* existing factory body */ },
    update = { view -> view.keepScreenOn = keepOn }
)
```

4. Replace the existing `setOnKeyListener` body to consume the pause keys before delegating:

```kotlin
setOnKeyListener { _, keyCode, event ->
    if (shouldConsumeTrailerKey(keyCode)) return@setOnKeyListener true
    currentOnRemoteKey(keyCode, event.action, event.repeatCount)
}
```

### - [ ] Step 2.6: Build and verify

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testUniversalDebugUnitTest`
Expected: all tests still pass.

### - [ ] Step 2.7: Commit

```bash
git add app/src/main/java/com/nexio/tv/ui/components/TrailerKeepScreenOnPolicy.kt \
        app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt \
        app/src/test/java/com/nexio/tv/ui/components/TrailerKeepScreenOnPolicyTest.kt
git commit -m "fix(trailer): scope keepScreenOn to playback state and consume pause keys

TrailerPlayer was holding FLAG_KEEP_SCREEN_ON unconditionally and accepted
remote pause, leaving a paused trailer frame on screen with the wake lock
asserted. Mirror PlayerVideoSurface scoping (isPlaying || isBuffering) and
consume KEYCODE_MEDIA_PAUSE / PLAY_PAUSE so trailers cannot be paused.
R2 from oled-burnin-hardening spec."
```

---

## Task 3 — R3: Focus ring breathing animation + remove white theme

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/theme/BreathingFocusRing.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/theme/ThemeColors.kt` (remove `WHITE` enum value)
- Modify: `app/src/main/java/com/nexio/tv/MainActivity.kt:198` (default `theme = AppTheme.CRIMSON`)
- Modify: `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt` (theme read migration)
- Modify all 7 call sites that reference `NexioColors.FocusRing` directly:
  - `app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt:325`
  - `app/src/main/java/com/nexio/tv/ui/components/CatalogRowSection.kt:264`
  - `app/src/main/java/com/nexio/tv/ui/components/GridContentCard.kt:133`
  - `app/src/main/java/com/nexio/tv/ui/components/SourceStatusFilterChip.kt:119,127`
  - `app/src/main/java/com/nexio/tv/ui/components/SidebarNavigation.kt:117`
  - `app/src/main/java/com/nexio/tv/ui/components/ContinueWatchingSection.kt:393`
  - `app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt:138-165`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/ThemeSettingsScreen.kt` (drop WHITE from picker)
- Test: `app/src/test/java/com/nexio/tv/data/local/ThemeMigrationTest.kt`

### - [ ] Step 3.1: Add breathing focus ring helper

Create `app/src/main/java/com/nexio/tv/ui/theme/BreathingFocusRing.kt`:

```kotlin
package com.nexio.tv.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

private const val BREATHING_PERIOD_MS = 3500
private const val MIN_ALPHA = 0.7f
private const val MAX_ALPHA = 1.0f

@Composable
fun rememberBreathingFocusRing(base: Color = NexioColors.FocusRing): Color {
    val transition = rememberInfiniteTransition(label = "focusRingBreathing")
    val alpha by transition.animateFloat(
        initialValue = MIN_ALPHA,
        targetValue = MAX_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BREATHING_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "focusRingAlpha"
    )
    return base.copy(alpha = alpha)
}
```

> Note: `transition.animateFloat` and `getValue` come from `androidx.compose.animation.core.animateFloat` — keep imports tight.

### - [ ] Step 3.2: Replace direct `NexioColors.FocusRing` usages with breathing variant

For each of the 7 files listed above, replace `NexioColors.FocusRing` inside `BorderStroke(...)` and `targetValue = ...` calls with `rememberBreathingFocusRing()`. Add the import `import com.nexio.tv.ui.theme.rememberBreathingFocusRing`. Example for `ContentCard.kt:325`:

Before:
```kotlin
border = BorderStroke(posterCardStyle.focusedBorderWidth, NexioColors.FocusRing),
```

After:
```kotlin
border = BorderStroke(posterCardStyle.focusedBorderWidth, rememberBreathingFocusRing()),
```

For `HeroCarousel.kt:138-165`, replace the `val focusRing = NexioColors.FocusRing` line with `val focusRing = rememberBreathingFocusRing()` (the rest of the file already references the local `focusRing` variable).

For `SidebarNavigation.kt:117`, the call is inside `animateColorAsState` — replace `NexioColors.FocusRing` with `rememberBreathingFocusRing()`.

For `SourceStatusFilterChip.kt:119,127`, replace both occurrences in the same file.

### - [ ] Step 3.3: Build, verify all sites compile

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

### - [ ] Step 3.4: Write failing test for theme migration

Create `app/src/test/java/com/nexio/tv/data/local/ThemeMigrationTest.kt`:

```kotlin
package com.nexio.tv.data.local

import org.junit.Test
import org.junit.Assert.assertEquals

class ThemeMigrationTest {
    @Test fun `legacy WHITE value migrates to CRIMSON`() {
        assertEquals(AppTheme.CRIMSON, migrateThemePreference("WHITE"))
    }

    @Test fun `valid theme values pass through unchanged`() {
        assertEquals(AppTheme.OCEAN, migrateThemePreference("OCEAN"))
        assertEquals(AppTheme.AMBER, migrateThemePreference("AMBER"))
        assertEquals(AppTheme.CRIMSON, migrateThemePreference("CRIMSON"))
    }

    @Test fun `unknown values fall back to CRIMSON`() {
        assertEquals(AppTheme.CRIMSON, migrateThemePreference("garbage"))
        assertEquals(AppTheme.CRIMSON, migrateThemePreference(null))
    }
}
```

### - [ ] Step 3.5: Run test, verify failure

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.ThemeMigrationTest"`
Expected: compile failure (`migrateThemePreference` unresolved).

### - [ ] Step 3.6: Implement migration helper

In `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt`, add a top-level helper:

```kotlin
internal fun migrateThemePreference(stored: String?): AppTheme {
    if (stored == "WHITE") return AppTheme.CRIMSON
    return runCatching { AppTheme.valueOf(stored ?: "") }.getOrDefault(AppTheme.CRIMSON)
}
```

Then update the existing theme read flow in this file to call `migrateThemePreference(rawString)` instead of `AppTheme.valueOf(...)`.

### - [ ] Step 3.7: Run migration test, verify pass

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.ThemeMigrationTest"`
Expected: all 4 tests pass.

### - [ ] Step 3.8: Remove WHITE from `AppTheme` enum

In `app/src/main/java/com/nexio/tv/ui/theme/ThemeColors.kt`, remove the `WHITE` entry from the `AppTheme` enum and remove the corresponding palette object/branch. Keep CRIMSON, OCEAN, VIOLET, EMERALD, AMBER, ROSE.

### - [ ] Step 3.9: Update default in `MainUiPrefs`

In `MainActivity.kt:198-206`, change:

```kotlin
val theme: AppTheme = AppTheme.WHITE,
```

to:

```kotlin
val theme: AppTheme = AppTheme.CRIMSON,
```

### - [ ] Step 3.10: Drop WHITE from theme picker

In `ThemeSettingsScreen.kt`, locate the `availableThemes` list (around lines 127-149). Confirm it iterates over `AppTheme.values()`. If yes, no change needed (enum removal handles it). If it has a hard-coded list including WHITE, remove WHITE from that list.

Run: `grep -n "WHITE\|availableThemes" app/src/main/java/com/nexio/tv/ui/screens/settings/ThemeSettingsScreen.kt`

### - [ ] Step 3.11: Build, run all tests

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testUniversalDebugUnitTest`
Expected: BUILD SUCCESSFUL and all tests pass. Any test that referenced `AppTheme.WHITE` must be updated to use `AppTheme.CRIMSON`.

### - [ ] Step 3.12: Commit

```bash
git add app/src/main/java/com/nexio/tv/ui/theme/BreathingFocusRing.kt \
        app/src/main/java/com/nexio/tv/ui/theme/ThemeColors.kt \
        app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt \
        app/src/main/java/com/nexio/tv/ui/components/CatalogRowSection.kt \
        app/src/main/java/com/nexio/tv/ui/components/GridContentCard.kt \
        app/src/main/java/com/nexio/tv/ui/components/SourceStatusFilterChip.kt \
        app/src/main/java/com/nexio/tv/ui/components/SidebarNavigation.kt \
        app/src/main/java/com/nexio/tv/ui/components/ContinueWatchingSection.kt \
        app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt \
        app/src/main/java/com/nexio/tv/ui/screens/settings/ThemeSettingsScreen.kt \
        app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt \
        app/src/main/java/com/nexio/tv/MainActivity.kt \
        app/src/test/java/com/nexio/tv/data/local/ThemeMigrationTest.kt
git commit -m "feat(theme): breathing focus ring, remove white accent

Static fully-saturated focus rings on long-parked focus were a burn-in vector,
worst-case on the white theme. Add a slow alpha-breathing animation to all
focus-ring sites so no subpixel is driven hard for long, and remove WHITE
from AppTheme. Existing white-theme users migrate to CRIMSON on next read.
R3 from oled-burnin-hardening spec."
```

---

## Task 4 — R4: Sidebar — collapse-by-default legacy is the only mode

**Files:**
- Delete: `app/src/main/java/com/nexio/tv/ModernSidebarBlurPanel.kt`
- Modify: `app/src/main/java/com/nexio/tv/MainActivity.kt` (`MainUiPrefs`, sidebar render block 2083-2240, import 188)
- Modify: `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt` (remove sidebar prefs, keep migration purge)
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/LayoutSettingsScreen.kt` (remove sidebar section)
- Test: `app/src/test/java/com/nexio/tv/data/local/LayoutPreferenceDataStoreTest.kt` (extend or create)

### - [ ] Step 4.1: Audit current sidebar references

Run: `grep -rn "modernSidebar\|sidebarCollapsed\|ModernSidebarBlurPanel" app/src/main/java/com/nexio/tv/ | sort`

This produces the complete list of call sites. Record them — every entry must be addressed.

### - [ ] Step 4.2: Remove sidebar fields from `MainUiPrefs`

In `MainActivity.kt:198-206`, change:

```kotlin
private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.CRIMSON,
    val font: AppFont = AppFont.INTER,
    val hasChosenLayout: Boolean? = null,
    val sidebarCollapsed: Boolean = false,
    val modernSidebarEnabled: Boolean = false,
    val modernSidebarBlurPref: Boolean = false,
    val trailerScreensaverEnabled: Boolean = false
)
```

to:

```kotlin
private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.CRIMSON,
    val font: AppFont = AppFont.INTER,
    val hasChosenLayout: Boolean? = null,
    val trailerScreensaverEnabled: Boolean = false
)
```

### - [ ] Step 4.3: Remove sidebar prefs from DataStore

`LayoutPreferenceDataStore` uses an injected `ProfileDataStoreFactory`, so the migration story is simpler: stop reading the old keys and they orphan harmlessly in storage.

In `LayoutPreferenceDataStore.kt`:

1. Delete the `Preferences.Key` declarations:
   - `modernSidebarBlurEnabledKey` (line ~88).
   - The `modern_sidebar_enabled` key (run `grep -n "modern_sidebar_enabled" app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt`).
   - The `sidebar_collapsed` key (run `grep -n "sidebar_collapsed" app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt`).

2. Delete the public flow accessors (e.g. `val modernSidebarEnabled: Flow<Boolean>`, `val modernSidebarBlurEnabled: Flow<Boolean>`, `val sidebarCollapsedByDefault: Flow<Boolean>`) that exposed those keys.

3. Delete the suspending setters (e.g. `suspend fun setModernSidebarEnabled(...)`).

Existing rows in the underlying preferences file remain but are never read — no explicit migration step is needed.

### - [ ] Step 4.4: Update consumers that read those flows

Run: `grep -rn "modernSidebarEnabled\|modernSidebarBlurEnabled\|sidebarCollapsedByDefault\|setModernSidebar\|setSidebarCollapsed" app/src/main/java/com/nexio/tv/`

For every hit, delete the call site. In ViewModels that exposed those fields on UI state, remove the field. In the `combine(...)` block in `MainActivity` that produces `MainUiPrefs`, drop the corresponding sources.

### - [ ] Step 4.5: Replace sidebar render block with legacy-only path

In `MainActivity.kt:2083-2240`, the existing block branches on `modernSidebarEnabled`. Remove the modern branch entirely. The remaining legacy path should:

- Always render the legacy sidebar at 72 dp width (collapsed).
- Always auto-hide on Detail/Stream routes (preserve existing behavior).
- Never reference `ModernSidebarBlurPanel`, `sidebarExpandProgress`, `sidebarLabelAlpha`, `sidebarIconScale`, `keepSidebarFocusDuringCollapse`, `isSidebarExpanded`, or `sidebarCollapsePending`.

If the legacy code path uses `sidebarCollapsed` from prefs, replace those references with the literal `true`.

Remove import at line 188: `import com.nexio.tv.ModernSidebarBlurPanel`.

### - [ ] Step 4.6: Delete `ModernSidebarBlurPanel.kt`

```bash
git rm app/src/main/java/com/nexio/tv/ModernSidebarBlurPanel.kt
```

### - [ ] Step 4.7: Remove sidebar section from `LayoutSettingsScreen`

In `LayoutSettingsScreen.kt`:
- Remove any `LayoutSettingsSection` enum entries for sidebar (search for `SIDEBAR`, `MODERN_SIDEBAR`, `MODERN_SIDEBAR_BLUR`).
- Remove the corresponding `item { ... }` blocks that render their toggle rows.
- Remove the `rememberSaveable` expanded-state vars for those sections.

### - [ ] Step 4.8: Build

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. If unresolved-reference errors appear, address each one — they will be at sites referencing fields removed from `MainUiPrefs`.

### - [ ] Step 4.9: Run tests

Run: `./gradlew :app:testUniversalDebugUnitTest`
Expected: all tests pass. Update or delete any tests that reference `modernSidebarEnabled`, `modernSidebarBlurPref`, or `sidebarCollapsed`.

### - [ ] Step 4.10: Commit

```bash
git add -A
git commit -m "refactor(sidebar): collapse-by-default legacy is the only mode

Remove modern sidebar variant and all sidebar-modifying preferences. Modern
sidebar held a 184dp persistent labelled column on root routes — a burn-in
vector during long browse sessions. Ship a single 72dp collapsed legacy
sidebar with the existing auto-hide on Detail/Stream. DataStore migration
silently drops legacy preference keys. R4 from oled-burnin-hardening spec."
```

---

## Task 5 — R6: Loading overlay timeouts (auto-retry once, then error)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/LoadingTimeoutController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreen.kt:244` (initial-load owner)
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt:580` (mid-playback owner)
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/LoadingTimeoutControllerTest.kt`

### - [ ] Step 5.1: Define the controller (failing test first)

Create `app/src/test/java/com/nexio/tv/ui/screens/player/LoadingTimeoutControllerTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoadingTimeoutControllerTest {

    @Test fun `initial phase fires retry after 120 seconds`() = runTest {
        val events = mutableListOf<LoadingTimeoutEvent>()
        val controller = LoadingTimeoutController(
            phase = LoadingPhase.Initial,
            onEvent = events::add,
            scope = this
        )
        controller.start()
        advanceTimeBy(119_999)
        assertEquals(emptyList<LoadingTimeoutEvent>(), events)
        advanceTimeBy(2)
        assertEquals(listOf(LoadingTimeoutEvent.Retry), events)
    }

    @Test fun `mid-stream phase fires retry after 60 seconds`() = runTest {
        val events = mutableListOf<LoadingTimeoutEvent>()
        val controller = LoadingTimeoutController(
            phase = LoadingPhase.MidStream,
            onEvent = events::add,
            scope = this
        )
        controller.start()
        advanceTimeBy(59_999)
        assertEquals(emptyList<LoadingTimeoutEvent>(), events)
        advanceTimeBy(2)
        assertEquals(listOf(LoadingTimeoutEvent.Retry), events)
    }

    @Test fun `second timeout after retry fires Error`() = runTest {
        val events = mutableListOf<LoadingTimeoutEvent>()
        val controller = LoadingTimeoutController(
            phase = LoadingPhase.MidStream,
            onEvent = events::add,
            scope = this
        )
        controller.start()
        advanceTimeBy(60_001)        // first ceiling -> Retry
        controller.start()           // caller restarts after retry
        advanceTimeBy(60_001)        // second ceiling -> Error
        assertEquals(listOf(LoadingTimeoutEvent.Retry, LoadingTimeoutEvent.Error), events)
    }

    @Test fun `cancel before timeout produces no events`() = runTest {
        val events = mutableListOf<LoadingTimeoutEvent>()
        val controller = LoadingTimeoutController(
            phase = LoadingPhase.Initial,
            onEvent = events::add,
            scope = this
        )
        controller.start()
        advanceTimeBy(60_000)
        controller.cancel()
        advanceTimeBy(120_000)
        assertEquals(emptyList<LoadingTimeoutEvent>(), events)
    }
}
```

### - [ ] Step 5.2: Run test, verify it fails

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.LoadingTimeoutControllerTest"`
Expected: compile failure.

### - [ ] Step 5.3: Implement the controller

Create `app/src/main/java/com/nexio/tv/ui/screens/player/LoadingTimeoutController.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class LoadingPhase(val timeoutMs: Long) {
    Initial(120_000L),
    MidStream(60_000L)
}

sealed class LoadingTimeoutEvent {
    object Retry : LoadingTimeoutEvent()
    object Error : LoadingTimeoutEvent()
}

class LoadingTimeoutController(
    private val phase: LoadingPhase,
    private val onEvent: (LoadingTimeoutEvent) -> Unit,
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    private var hasRetried = false

    fun start() {
        job?.cancel()
        job = scope.launch {
            delay(phase.timeoutMs)
            if (!hasRetried) {
                hasRetried = true
                onEvent(LoadingTimeoutEvent.Retry)
            } else {
                onEvent(LoadingTimeoutEvent.Error)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
```

### - [ ] Step 5.4: Run tests, verify pass

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.LoadingTimeoutControllerTest"`
Expected: all 4 tests pass.

### - [ ] Step 5.5: Wire into `StreamScreen.kt:244` (initial load)

At the LoadingOverlay call site in `StreamScreen.kt`, add a `DisposableEffect` keyed on the loading state:

```kotlin
val loadingScope = rememberCoroutineScope()
DisposableEffect(isLoading) {
    if (!isLoading) return@DisposableEffect onDispose {}
    val controller = LoadingTimeoutController(
        phase = LoadingPhase.Initial,
        onEvent = { event ->
            when (event) {
                LoadingTimeoutEvent.Retry -> viewModel.retryStreamResolution()
                LoadingTimeoutEvent.Error -> viewModel.surfaceLoadingTimeoutError()
            }
        },
        scope = loadingScope
    )
    controller.start()
    onDispose { controller.cancel() }
}
```

> Note: `retryStreamResolution()` and `surfaceLoadingTimeoutError()` may not exist on the ViewModel yet. If not, add them as thin wrappers around the existing retry/error code paths (search for existing retry handling in StreamViewModel and reuse it).

### - [ ] Step 5.6: Wire into `PlayerScreen.kt:580` (mid-playback rebuffer)

Same pattern, but with `phase = LoadingPhase.MidStream`:

```kotlin
val rebufferScope = rememberCoroutineScope()
DisposableEffect(isBuffering) {
    if (!isBuffering) return@DisposableEffect onDispose {}
    val controller = LoadingTimeoutController(
        phase = LoadingPhase.MidStream,
        onEvent = { event ->
            when (event) {
                LoadingTimeoutEvent.Retry -> viewModel.retryCurrentSegment()
                LoadingTimeoutEvent.Error -> viewModel.surfaceRebufferTimeoutError()
            }
        },
        scope = rebufferScope
    )
    controller.start()
    onDispose { controller.cancel() }
}
```

> If `isBuffering` isn't already exposed by the player UI state, derive it from the existing `LoadingOverlay`-visibility predicate.

### - [ ] Step 5.7: Build

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

### - [ ] Step 5.8: Run all tests

Run: `./gradlew :app:testUniversalDebugUnitTest`
Expected: all tests pass.

### - [ ] Step 5.9: Commit

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/LoadingTimeoutController.kt \
        app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreen.kt \
        app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt \
        app/src/test/java/com/nexio/tv/ui/screens/player/LoadingTimeoutControllerTest.kt
git commit -m "feat(loading): cap loading overlay at 120s initial / 60s rebuffer

LoadingOverlay had no stall ceiling, leaving a gradient backdrop and pulsing
logo on screen indefinitely on stuck debrid resolution or rebuffer. Add a
two-phase timeout controller that auto-retries once and then surfaces the
existing error screen. R6 from oled-burnin-hardening spec."
```

---

## Task 6 — R7: Cap subtitle background opacity at 75 %

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/SubtitleBackgroundClamp.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt` (clamp on read AND write)
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSubtitleSettings.kt:186-188,282-284` (color picker callback clamps)
- Test: `app/src/test/java/com/nexio/tv/data/local/SubtitleBackgroundClampTest.kt`

### - [ ] Step 6.1: Write failing clamp test

Create `app/src/test/java/com/nexio/tv/data/local/SubtitleBackgroundClampTest.kt`:

```kotlin
package com.nexio.tv.data.local

import org.junit.Test
import org.junit.Assert.assertEquals

class SubtitleBackgroundClampTest {
    @Test fun `transparent stays transparent`() {
        assertEquals(0x00000000, clampSubtitleBackgroundAlpha(0x00000000))
    }

    @Test fun `value below cap is unchanged`() {
        // 50% alpha black = 0x80000000
        assertEquals(0x80000000.toInt(), clampSubtitleBackgroundAlpha(0x80000000.toInt()))
    }

    @Test fun `value at cap passes through`() {
        // 75% alpha = 0xBF (191)
        assertEquals(0xBF000000.toInt(), clampSubtitleBackgroundAlpha(0xBF000000.toInt()))
    }

    @Test fun `value above cap is clamped to 75 percent alpha`() {
        // Fully opaque white = 0xFFFFFFFF; alpha clamped to 0xBF, RGB preserved
        assertEquals(0xBFFFFFFF.toInt(), clampSubtitleBackgroundAlpha(0xFFFFFFFF.toInt()))
    }

    @Test fun `opaque black is clamped`() {
        assertEquals(0xBF000000.toInt(), clampSubtitleBackgroundAlpha(0xFF000000.toInt()))
    }
}
```

### - [ ] Step 6.2: Run, verify failure

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.SubtitleBackgroundClampTest"`
Expected: compile failure.

### - [ ] Step 6.3: Implement the clamp helper

Create `app/src/main/java/com/nexio/tv/data/local/SubtitleBackgroundClamp.kt`:

```kotlin
package com.nexio.tv.data.local

const val SUBTITLE_BACKGROUND_MAX_ALPHA: Int = 0xBF // 191 / 255 ≈ 75%

fun clampSubtitleBackgroundAlpha(argb: Int): Int {
    val storedAlpha = (argb ushr 24) and 0xFF
    val clampedAlpha = minOf(storedAlpha, SUBTITLE_BACKGROUND_MAX_ALPHA)
    val rgb = argb and 0x00FFFFFF
    return (clampedAlpha shl 24) or rgb
}
```

### - [ ] Step 6.4: Run, verify pass

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.SubtitleBackgroundClampTest"`
Expected: all 5 tests pass.

### - [ ] Step 6.5: Apply clamp on read and write in `PlayerSettingsDataStore`

In `PlayerSettingsDataStore.kt`, find the `SubtitleStyleSettings` read flow (where the stored `Int` becomes the data class field) and the write function. Wrap both:

- On read: `backgroundColor = clampSubtitleBackgroundAlpha(prefs[backgroundColorKey] ?: Color.Transparent.toArgb())`.
- On write: store `clampSubtitleBackgroundAlpha(newValue)` rather than `newValue`.

Run: `grep -n "backgroundColor\|SubtitleStyleSettings" app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt` to find the exact lines.

### - [ ] Step 6.6: Clamp in the UI color picker callback

In `PlaybackSubtitleSettings.kt`, at the two background-color sections (~line 186 and ~line 282), find the picker's `onColorSelected` lambda. Wrap the call to update the setting:

Before:
```kotlin
onColorSelected = { color -> viewModel.setSubtitleBackgroundColor(color.toArgb()) }
```

After:
```kotlin
onColorSelected = { color ->
    viewModel.setSubtitleBackgroundColor(clampSubtitleBackgroundAlpha(color.toArgb()))
}
```

Add import: `import com.nexio.tv.data.local.clampSubtitleBackgroundAlpha`.

### - [ ] Step 6.7: Build, run all tests

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testUniversalDebugUnitTest`
Expected: BUILD SUCCESSFUL and all tests pass.

### - [ ] Step 6.8: Commit

```bash
git add app/src/main/java/com/nexio/tv/data/local/SubtitleBackgroundClamp.kt \
        app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt \
        app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSubtitleSettings.kt \
        app/src/test/java/com/nexio/tv/data/local/SubtitleBackgroundClampTest.kt
git commit -m "fix(subtitles): cap user-configurable background alpha at 75 percent

Fully-opaque caption backgrounds are a known burn-in vector at the bottom
fifth of the screen during long binges. Clamp alpha to 0xBF on read and
write so the worst-case is never rendered, while preserving the readability
win for users who need a contrast-boosting background. Existing values
above the cap silently clamp on next read. R7 from oled-burnin-hardening
spec."
```

---

## Task 7 — Configurable screensaver delay (1.0–10.0 min, 30 s steps)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt` (new key)
- Modify: `app/src/main/java/com/nexio/tv/MainActivity.kt:217` (drop constant, accept value)
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverController.kt` (accept delay flow)
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/LayoutSettingsScreen.kt` (slider in Screensaver section)
- Modify: `app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt` (delete or update timeout-constant test)
- Test: `app/src/test/java/com/nexio/tv/ui/screensaver/ScreensaverDelayTest.kt`

### - [ ] Step 7.1: Add preference key + flow

In `LayoutPreferenceDataStore.kt`, add:

```kotlin
private val screensaverDelaySecondsKey = androidx.datastore.preferences.core.intPreferencesKey("screensaver_delay_seconds")

val screensaverDelaySeconds: kotlinx.coroutines.flow.Flow<Int> = dataStore.data.map { prefs ->
    val stored = prefs[screensaverDelaySecondsKey] ?: 300
    stored.coerceIn(60, 600)
}

suspend fun setScreensaverDelaySeconds(seconds: Int) {
    dataStore.edit { prefs ->
        prefs[screensaverDelaySecondsKey] = seconds.coerceIn(60, 600)
    }
}
```

> Range 60-600 seconds = 1.0-10.0 min. 30 s step is enforced by the UI slider, not the store (a stored value of 73 from a prior version is safe — it just means screensaver fires after 73 s).

### - [ ] Step 7.2: Replace the constant in `MainActivity`

In `MainActivity.kt:217`:

Before:
```kotlin
internal const val IDLE_SCREENSAVER_TIMEOUT_MS = 5L * 60 * 1000L
```

After:
```kotlin
internal const val IDLE_SCREENSAVER_DEFAULT_TIMEOUT_MS = 5L * 60 * 1000L
internal const val IDLE_SCREENSAVER_MIN_TIMEOUT_MS = 60L * 1000L
internal const val IDLE_SCREENSAVER_MAX_TIMEOUT_MS = 10L * 60 * 1000L
```

Find every reference to `IDLE_SCREENSAVER_TIMEOUT_MS` (run `grep -rn IDLE_SCREENSAVER_TIMEOUT_MS app/src/main app/src/test`) and replace with the value from the new flow (collected in the Compose layer) or `IDLE_SCREENSAVER_DEFAULT_TIMEOUT_MS` if it's a fallback.

### - [ ] Step 7.3: Pass the delay flow into `IdleScreensaverController`

The controller currently uses `MainActivity.IDLE_SCREENSAVER_TIMEOUT_MS`. Add a `StateFlow<Long>` parameter or an injected `LayoutPreferenceDataStore` dependency:

```kotlin
@Singleton
class IdleScreensaverController @Inject constructor(
    private val layoutPrefs: LayoutPreferenceDataStore
) {
    val currentTimeoutMs: kotlinx.coroutines.flow.Flow<Long> =
        layoutPrefs.screensaverDelaySeconds.map { it * 1000L }
    /* existing fields */
}
```

Update every consumer that previously read `IDLE_SCREENSAVER_TIMEOUT_MS` from `MainActivity` to instead `collectAsState` from `controller.currentTimeoutMs`.

### - [ ] Step 7.4: Add slider in Screensaver section of `LayoutSettingsScreen`

Locate the existing trailer-screensaver toggle (referenced at lines 81, 96, 103, 126-130, 442-458 in `LayoutSettingsScreen.kt`). Below the toggle, add a `SliderSettingsItem`:

```kotlin
item(key = "screensaver_delay") {
    SliderSettingsItem(
        icon = Icons.Default.Timer,
        title = stringResource(R.string.screensaver_delay_title),
        value = uiState.screensaverDelaySeconds,
        valueText = formatScreensaverDelay(uiState.screensaverDelaySeconds),
        minValue = 60,
        maxValue = 600,
        step = 30,
        onValueChange = { viewModel.setScreensaverDelaySeconds(it) }
    )
}
```

Add helper `formatScreensaverDelay`:

```kotlin
private fun formatScreensaverDelay(seconds: Int): String {
    val minutes = seconds / 60
    val remainder = seconds % 60
    return if (remainder == 0) "$minutes min" else "$minutes min ${remainder}s"
}
```

Add the string resource in `app/src/main/res/values/strings.xml`:

```xml
<string name="screensaver_delay_title">Start screensaver after</string>
```

### - [ ] Step 7.5: Update settings ViewModel

Find the ViewModel backing `LayoutSettingsScreen` (likely `LayoutSettingsViewModel`). Add:
- `screensaverDelaySeconds` field on the UI state, sourced from `layoutPrefs.screensaverDelaySeconds`.
- `fun setScreensaverDelaySeconds(seconds: Int)` that calls `layoutPrefs.setScreensaverDelaySeconds`.

### - [ ] Step 7.6: Update existing test, add new test

In `MainActivityIdleScreensaverTest.kt`, the existing test:

```kotlin
@Test fun `idle screensaver timeout is five minutes`() {
    assertEquals(5L * 60 * 1000L, MainActivity.IDLE_SCREENSAVER_TIMEOUT_MS)
}
```

Replace with:

```kotlin
@Test fun `idle screensaver default timeout is five minutes`() {
    assertEquals(5L * 60 * 1000L, MainActivity.IDLE_SCREENSAVER_DEFAULT_TIMEOUT_MS)
}

@Test fun `idle screensaver min and max are 1 and 10 minutes`() {
    assertEquals(60L * 1000L, MainActivity.IDLE_SCREENSAVER_MIN_TIMEOUT_MS)
    assertEquals(10L * 60 * 1000L, MainActivity.IDLE_SCREENSAVER_MAX_TIMEOUT_MS)
}
```

Create `app/src/test/java/com/nexio/tv/ui/screensaver/ScreensaverDelayTest.kt`:

```kotlin
package com.nexio.tv.ui.screensaver

import org.junit.Test
import org.junit.Assert.assertEquals

class ScreensaverDelayTest {
    @Test fun `value within range passes through`() {
        assertEquals(180, coerceScreensaverDelaySeconds(180))
    }

    @Test fun `value below 60 clamps to 60`() {
        assertEquals(60, coerceScreensaverDelaySeconds(0))
        assertEquals(60, coerceScreensaverDelaySeconds(45))
    }

    @Test fun `value above 600 clamps to 600`() {
        assertEquals(600, coerceScreensaverDelaySeconds(1200))
    }
}
```

### - [ ] Step 7.7: Add the `coerceScreensaverDelaySeconds` helper

In `IdleScreensaverController.kt` (or alongside it), add:

```kotlin
internal fun coerceScreensaverDelaySeconds(seconds: Int): Int = seconds.coerceIn(60, 600)
```

Use it in `LayoutPreferenceDataStore` (replace the inline `coerceIn(60, 600)` with this call) for a single source of truth.

### - [ ] Step 7.8: Build and test

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testUniversalDebugUnitTest`
Expected: BUILD SUCCESSFUL and all tests pass.

### - [ ] Step 7.9: Manual verification checklist

- Open Settings → Screensaver. The new "Start screensaver after" slider is visible below the trailer-screensaver toggle.
- Slider snaps in 30 s increments between 1 min and 10 min.
- Set to 1 min. Idle on Home — screensaver fires at ~60 s.
- Set to 10 min. Idle on Home — screensaver does not fire before ~10 min.
- Existing trailer-screensaver toggle still works.

### - [ ] Step 7.10: Commit

```bash
git add app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt \
        app/src/main/java/com/nexio/tv/MainActivity.kt \
        app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverController.kt \
        app/src/main/java/com/nexio/tv/ui/screens/settings/LayoutSettingsScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt \
        app/src/test/java/com/nexio/tv/ui/screensaver/ScreensaverDelayTest.kt
git commit -m "feat(screensaver): user-configurable idle delay (1-10 min, 30s steps)

Replace the hard-coded 5-minute idle timeout with a user preference exposed
in Settings -> Screensaver. Range 60-600s in 30s increments, default 300s
preserves prior behavior. Wired through IdleScreensaverController so changes
take effect on the next idle cycle without restart."
```

---

## Final verification

### - [ ] Run full test suite

```bash
./gradlew :app:testUniversalDebugUnitTest
```

Expected: all tests green.

### - [ ] Build a release-config debug APK

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

### - [ ] Manual smoke test on device / emulator

1. Pause playback in the player and idle 1+ min (with new short delay) — screensaver fires (R1).
2. Trailer plays on Home; press pause on remote — nothing happens, trailer continues (R2).
3. Park focus on a Home tile for 30 s — focus ring is visibly breathing (R3).
4. Sidebar is 72 dp on Home/Search/Library/Settings, hides on Detail/Stream (R4).
5. No sidebar settings appear in Settings (R4).
6. Force a debrid stall (firewall / airplane mode) — error screen appears after 120 s on initial load, 60 s on rebuffer (R6).
7. Try to set subtitle background to fully opaque — slider/picker clamps to 75 % (R7).
8. Settings → Screensaver → Start screensaver after — slider visible, snaps to 30 s steps (new).

### - [ ] Verify migration on upgrade

Install a build at the previous commit (or any commit prior to this plan), set:
- Theme = WHITE.
- Modern sidebar = enabled, blur = enabled.
- Subtitle background = fully opaque.

Then upgrade to the new build. Expected:
- Theme is now CRIMSON.
- Sidebar is legacy 72 dp; settings show no sidebar options.
- Subtitle background opacity is at most 75 %.

---

## Out of scope (do NOT do here)

- New screensaver content modes.
- ExoPlayer / Media3 changes.
- Theme palette overhaul.
- Accessibility audit beyond the caption-opacity cap.
- Pause overlay redesign (R1 fix is gate-only; the overlay itself is unchanged).
- Loading overlay visual redesign (R6 adds a state machine, not new visuals).
