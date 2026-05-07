# Subtitle OLED Burn-in Protection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce OLED burn-in caused by subtitles by rotating subtitle position per stream (vertical zone + horizontal jitter), capping subtitle alpha at 0.90, and rendering subtitles in a hardcoded off-white. Master toggle only — no advanced controls. Existing user-configurable subtitle text-color setting is removed in the same change.

**Architecture:** Subtitle deltas are computed once per playback session from a deterministic seed (`contentId + season:episode | streamUrl + persistedUserSalt + dayBucket`) and threaded through the existing `PlayerSurfaceRenderState` into `applySubtitleStyle()`. The function applies the alpha cap and off-white color unconditionally when burn-in is enabled, and applies the per-stream `bottomPaddingFraction` delta plus `translationX` jitter to both the embedded `SubtitleView` and the external addon `SubtitleView`. The ASS/SSA bitmap overlay path is not modified.

**Tech Stack:** Kotlin, Jetpack Compose, Media3 (`SubtitleView`, `CaptionStyleCompat`, `Player.Listener`), DataStore Preferences, JUnit + MockK for tests.

---

## Spec source

Design spec: `/Users/jneerdael/.claude/plans/i-m-interesting-in-adding-piped-parnas.md`

## File Structure

### Files to create

- `app/src/main/java/com/nexio/tv/core/player/SubtitleBurnInProtection.kt` — pure helpers: zone/jitter math from seed, off-white constant, alpha cap. No Android dependencies beyond `Color.argb`-equivalent constants — must be unit-testable on the JVM.
- `app/src/main/java/com/nexio/tv/core/player/BurnInProtectionState.kt` — small immutable record `BurnInProtectionState(enabled, verticalDeltaPercent, horizontalOffsetPx)` that is threaded through render state.
- `app/src/test/java/com/nexio/tv/core/player/SubtitleBurnInProtectionTest.kt` — unit tests.

### Files to modify

- `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt` — add `BurnInProtectionSettings` block (single `enabled` field, default true), persisted user salt key, getter/setter. Remove `textColor` field from `SubtitleStyleSettings`, drop the `subtitleTextColorKey`, drop `setSubtitleTextColor()`.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt` — extend `applySubtitleStyle(...)` to accept the burn-in state and apply the alpha cap + off-white + horizontal translate. Replace `subtitleStyle.textColor` literal with the off-white constant or fall-through to white if disabled.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerVideoSurface.kt` — add `burnInProtection` to `PlayerSurfaceRenderState` and the mutation plan; pass through to `applySubtitleStyle(...)`.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt` — add `burnInProtection: BurnInProtectionState` to UI state; remove `OnSetSubtitleTextColor` event.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt` — extend `observeSubtitleSettings()` to also push `burnInProtection.enabled`; trigger delta recompute when `enabled` flips.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt` — at the point where a playback session begins (around `playbackSessionId = playbackSessionGuard.beginPlaybackSession()`), call a helper that computes the burn-in deltas from `contentId`, `initialSeason`, `initialEpisode`, `currentStreamUrl`, the persisted user salt, and the current day bucket, and writes the result into `_uiState.burnInProtection`.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt` — remove the `OnSetSubtitleTextColor` branch; remove `setSubtitleTextColor(defaults.textColor)` from `OnResetSubtitleDefaults`.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt` (call site at line 538) — extend `PlayerSurfaceRenderState(...)` with `burnInProtection = uiState.burnInProtection`.
- `app/src/main/java/com/nexio/tv/ui/screens/player/SubtitleDialog.kt` — remove the text-color picker block (lines 739-756 area).
- `app/src/main/java/com/nexio/tv/ui/screens/player/SubtitleStyleSidePanel.kt` — remove the text-color row (around line 169).
- `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSubtitleSettings.kt` — remove the `subtitle_text_color` `ColorSettingsItem` and the `showTextColorDialog` branch in `SubtitleSettingsDialogs`. Add a `subtitle_burn_in_protection` toggle row.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt` — remove `showTextColorDialog` state, the dialog dispatch on line 480, and the wiring at lines 192/210/258/453/509. Add wiring for the burn-in toggle (`onSetBurnInProtectionEnabled`).
- `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt` — remove `setSubtitleTextColor`. Add `setBurnInProtectionEnabled(enabled: Boolean)`.
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` — drop the `setSubtitleTextColor(settings.playback.subtitles.textColor)` call (line 1135). Inbound `textColor` is silently ignored. The model field stays in `AccountSyncModels.kt` so older clients pushing `textColor` don't break payload deserialization.
- `app/src/main/res/values/strings.xml` — add `subtitle_burn_in_protection_title`, `subtitle_burn_in_protection_subtitle`. Remove `sub_text_color`/`subtitle_text_color` if unused (verify with grep before deletion).

### Files explicitly NOT modified

- `app/src/main/java/com/nexio/tv/ui/screens/player/ass/**` — the ASS/SSA bitmap rendering path is out of scope. No changes.
- `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt` — does not render subtitles. No changes.
- `app/src/main/res/values-*/strings.xml` (locale variants) — string removals will be regenerated by the translation pipeline; do not hand-edit.
- `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt` — keep `SubtitleSyncSettings.textColor: Int = -1` for payload backward-compat. Older clients and stored cloud payloads still carry this field; we just stop reading it.

---

## Constants used throughout

```kotlin
// In SubtitleBurnInProtection.kt
internal const val SUBTITLE_OFF_WHITE_ARGB: Int = 0xFFF0F0F0.toInt()
internal const val SUBTITLE_MAX_ALPHA: Float = 0.90f
internal const val SUBTITLE_BURN_IN_ZONE_COUNT: Int = 5
internal const val SUBTITLE_BURN_IN_ZONE_SPREAD_PERCENT: Float = 6f
internal const val SUBTITLE_BURN_IN_HORIZONTAL_JITTER_PX: Float = 6f
private const val DAY_MS: Long = 24L * 60L * 60L * 1000L
```

```kotlin
// In BurnInProtectionState.kt
data class BurnInProtectionState(
    val enabled: Boolean,
    val verticalDeltaPercent: Float, // -3f..+3f when enabled, 0f when disabled
    val horizontalOffsetPx: Float,    // -6f..+6f when enabled, 0f when disabled
) {
    companion object {
        val DISABLED = BurnInProtectionState(enabled = false, 0f, 0f)
    }
}
```

---

## Tasks

### Task 1: Add pure helpers in `SubtitleBurnInProtection.kt`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/SubtitleBurnInProtection.kt`
- Create: `app/src/main/java/com/nexio/tv/core/player/BurnInProtectionState.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/SubtitleBurnInProtectionTest.kt`

- [ ] **Step 1: Write the failing test for `BurnInProtectionState.DISABLED`**

```kotlin
package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SubtitleBurnInProtectionTest {
    @Test
    fun disabled_state_has_zero_offsets_and_disabled_flag() {
        val state = BurnInProtectionState.DISABLED
        assertFalse(state.enabled)
        assertEquals(0f, state.verticalDeltaPercent, 0.0001f)
        assertEquals(0f, state.horizontalOffsetPx, 0.0001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
cd /Users/jneerdael/Scripts/nexio && ./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.SubtitleBurnInProtectionTest.disabled_state_has_zero_offsets_and_disabled_flag"
```

Expected: FAIL — `BurnInProtectionState` does not exist.

- [ ] **Step 3: Create `BurnInProtectionState.kt`**

```kotlin
package com.nexio.tv.core.player

data class BurnInProtectionState(
    val enabled: Boolean,
    val verticalDeltaPercent: Float,
    val horizontalOffsetPx: Float,
) {
    companion object {
        val DISABLED = BurnInProtectionState(enabled = false, 0f, 0f)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.SubtitleBurnInProtectionTest.disabled_state_has_zero_offsets_and_disabled_flag"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/nexio/tv/core/player/BurnInProtectionState.kt app/src/test/java/com/nexio/tv/core/player/SubtitleBurnInProtectionTest.kt
git commit -m "feat(burnin): add BurnInProtectionState data class"
```

---

### Task 2: Implement `computeBurnInProtectionState` from a seed

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/SubtitleBurnInProtection.kt` (created)
- Modify: `app/src/test/java/com/nexio/tv/core/player/SubtitleBurnInProtectionTest.kt`

- [ ] **Step 1: Write failing tests for seed-based zone/jitter selection**

Append to `SubtitleBurnInProtectionTest.kt`:

```kotlin
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

@Test
fun compute_returns_disabled_when_enabled_flag_false() {
    val state = computeBurnInProtectionState(
        enabled = false,
        mediaSeedKey = "tt1234567:s1e1",
        userSalt = "salt-abc",
        nowMs = 1_700_000_000_000L,
    )
    assertEquals(BurnInProtectionState.DISABLED, state)
}

@Test
fun compute_is_deterministic_for_same_inputs() {
    val a = computeBurnInProtectionState(true, "tt1234567:s1e1", "salt-abc", 1_700_000_000_000L)
    val b = computeBurnInProtectionState(true, "tt1234567:s1e1", "salt-abc", 1_700_000_000_000L)
    assertEquals(a, b)
}

@Test
fun compute_changes_across_day_boundary_at_least_70_percent_of_the_time() {
    val day1Ms = 1_700_000_000_000L
    val day2Ms = day1Ms + 24L * 60 * 60 * 1000
    var different = 0
    val total = 100
    repeat(total) { i ->
        val seed = "tt$i:s1e1"
        val a = computeBurnInProtectionState(true, seed, "salt-abc", day1Ms)
        val b = computeBurnInProtectionState(true, seed, "salt-abc", day2Ms)
        if (a.verticalDeltaPercent != b.verticalDeltaPercent) different++
    }
    assertTrue("expected day rollover to change zone >=70% of media; got $different/$total", different >= 70)
}

@Test
fun compute_zone_distribution_is_roughly_uniform_across_distinct_media() {
    val nowMs = 1_700_000_000_000L
    val buckets = IntArray(SUBTITLE_BURN_IN_ZONE_COUNT)
    val total = 500
    repeat(total) { i ->
        val state = computeBurnInProtectionState(true, "tt$i:s1e1", "salt-abc", nowMs)
        val deltaSlots = listOf(-3f, -1.5f, 0f, 1.5f, 3f)
        val idx = deltaSlots.indexOfFirst { kotlin.math.abs(it - state.verticalDeltaPercent) < 0.01f }
        assertTrue("delta ${state.verticalDeltaPercent} not in expected slots", idx >= 0)
        buckets[idx]++
    }
    buckets.forEach { count ->
        val pct = count.toFloat() / total
        assertTrue("bucket $count outside [0.10, 0.40] (pct=$pct)", pct in 0.10f..0.40f)
    }
}

@Test
fun compute_horizontal_offset_within_jitter_bounds() {
    val state = computeBurnInProtectionState(true, "anything", "salt", 1_700_000_000_000L)
    assertTrue(kotlin.math.abs(state.horizontalOffsetPx) <= SUBTITLE_BURN_IN_HORIZONTAL_JITTER_PX)
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.SubtitleBurnInProtectionTest"
```

Expected: FAIL — `computeBurnInProtectionState` and constants do not exist yet.

- [ ] **Step 3: Implement `SubtitleBurnInProtection.kt`**

```kotlin
package com.nexio.tv.core.player

internal const val SUBTITLE_OFF_WHITE_ARGB: Int = 0xFFF0F0F0.toInt()
internal const val SUBTITLE_MAX_ALPHA: Float = 0.90f
internal const val SUBTITLE_BURN_IN_ZONE_COUNT: Int = 5
internal const val SUBTITLE_BURN_IN_ZONE_SPREAD_PERCENT: Float = 6f
internal const val SUBTITLE_BURN_IN_HORIZONTAL_JITTER_PX: Float = 6f
private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

/**
 * Compute per-stream burn-in deltas from a deterministic seed.
 *
 * @param enabled whether burn-in protection is on; when false, returns DISABLED.
 * @param mediaSeedKey stable identity for the media ("contentId:s{n}e{m}" or stream URL).
 * @param userSalt persisted, per-install random string used to decorrelate users.
 * @param nowMs current epoch ms; bucketed to a day to drive cross-day rotation.
 */
fun computeBurnInProtectionState(
    enabled: Boolean,
    mediaSeedKey: String,
    userSalt: String,
    nowMs: Long,
): BurnInProtectionState {
    if (!enabled) return BurnInProtectionState.DISABLED

    val dayBucket = nowMs / DAY_MS
    val seedKey = "$mediaSeedKey:$userSalt:$dayBucket"
    val hash = seedKey.hashCode()

    val zoneIndex = Math.floorMod(hash, SUBTITLE_BURN_IN_ZONE_COUNT)
    val centerOffset = (SUBTITLE_BURN_IN_ZONE_COUNT - 1) / 2f
    val stepPercent = SUBTITLE_BURN_IN_ZONE_SPREAD_PERCENT / (SUBTITLE_BURN_IN_ZONE_COUNT - 1)
    val verticalDeltaPercent = (zoneIndex - centerOffset) * stepPercent

    val horizontalSlots = 5
    val horizontalIndex = Math.floorMod(hash / 7, horizontalSlots)
    val horizontalCenter = (horizontalSlots - 1) / 2f
    val horizontalStep = SUBTITLE_BURN_IN_HORIZONTAL_JITTER_PX / horizontalCenter
    val horizontalOffsetPx = (horizontalIndex - horizontalCenter) * horizontalStep

    return BurnInProtectionState(
        enabled = true,
        verticalDeltaPercent = verticalDeltaPercent,
        horizontalOffsetPx = horizontalOffsetPx,
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.SubtitleBurnInProtectionTest"
```

Expected: PASS for all 5 tests.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/nexio/tv/core/player/SubtitleBurnInProtection.kt app/src/test/java/com/nexio/tv/core/player/SubtitleBurnInProtectionTest.kt
git commit -m "feat(burnin): compute per-stream zone/jitter deltas from seed"
```

---

### Task 3: Add `BurnInProtectionSettings` and persisted salt to DataStore

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`

- [ ] **Step 1: Add data class and keys**

Above `data class PlayerSettings(` (around line 160), add:

```kotlin
/**
 * Subtitle OLED burn-in protection settings. Only the master toggle is user-facing;
 * all other tunables (zone count, spread, jitter, alpha cap, off-white color) are
 * internal constants in com.nexio.tv.core.player.SubtitleBurnInProtection.
 */
data class BurnInProtectionSettings(
    val enabled: Boolean = true,
)
```

In `data class PlayerSettings`, add the field (place adjacent to `subtitleStyle`):

```kotlin
val burnInProtection: BurnInProtectionSettings = BurnInProtectionSettings(),
```

In the section of `private val ...PreferencesKey` declarations (around line 555-565), add:

```kotlin
private val burnInProtectionEnabledKey = booleanPreferencesKey("burn_in_protection_enabled")
private val burnInProtectionUserSaltKey = stringPreferencesKey("burn_in_protection_user_salt")
```

- [ ] **Step 2: Wire into the read flow**

Find the `playerSettings` flow that constructs `PlayerSettings(...)` (the function around line 880-900 that reads `subtitleStyle = SubtitleStyleSettings(... textColor = prefs[subtitleTextColorKey] ?: ...)`). In the same `PlayerSettings(...)` constructor, add:

```kotlin
burnInProtection = BurnInProtectionSettings(
    enabled = prefs[burnInProtectionEnabledKey] ?: true,
),
```

- [ ] **Step 3: Add setter and salt accessor**

Near other `suspend fun setX(...)` functions (around line 1480 region):

```kotlin
suspend fun setBurnInProtectionEnabled(enabled: Boolean) {
    dataStore.edit { prefs ->
        prefs[burnInProtectionEnabledKey] = enabled
    }
}

/**
 * Returns the persisted per-install random salt used to decorrelate burn-in zone
 * selection across users. Generates and persists the salt on first read.
 */
suspend fun getOrCreateBurnInProtectionUserSalt(): String {
    val existing = dataStore.data.first()[burnInProtectionUserSaltKey]
    if (!existing.isNullOrBlank()) return existing
    val newSalt = java.util.UUID.randomUUID().toString()
    dataStore.edit { prefs ->
        // Re-check inside the edit to avoid a race generating two salts.
        if (prefs[burnInProtectionUserSaltKey].isNullOrBlank()) {
            prefs[burnInProtectionUserSaltKey] = newSalt
        }
    }
    return dataStore.data.first()[burnInProtectionUserSaltKey] ?: newSalt
}
```

Imports to add at top of file (if not already present):
- `kotlinx.coroutines.flow.first`

- [ ] **Step 4: Compile**

```
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt
git commit -m "feat(burnin): persist burn-in protection enabled flag and user salt"
```

---

### Task 4: Remove `SubtitleStyleSettings.textColor` from DataStore

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`

- [ ] **Step 1: Remove field from `SubtitleStyleSettings` (line 92)**

Edit `data class SubtitleStyleSettings`:

```kotlin
data class SubtitleStyleSettings(
    val preferredLanguage: String = "en",
    val secondaryPreferredLanguage: String? = null,
    val size: Int = 120,
    val verticalOffset: Int = 5,
    val bold: Boolean = false,
    // textColor removed — see BurnInProtectionSettings; subtitle color is hardcoded off-white.
    val backgroundColor: Int = Color.Transparent.toArgb(),
    val outlineEnabled: Boolean = true,
    val outlineColor: Int = Color.Black.toArgb(),
    val outlineWidth: Int = 2
)
```

- [ ] **Step 2: Remove the `subtitleTextColorKey` declaration (line 560)**

Delete the line:

```kotlin
private val subtitleTextColorKey = intPreferencesKey("subtitle_text_color")
```

- [ ] **Step 3: Remove `textColor =` from the read site (line 892)**

In the `SubtitleStyleSettings(...)` constructor inside the read flow, delete:

```kotlin
textColor = prefs[subtitleTextColorKey] ?: Color.White.toArgb(),
```

- [ ] **Step 4: Remove the `setSubtitleTextColor` function (line 1480-1483)**

Delete:

```kotlin
suspend fun setSubtitleTextColor(color: Int) {
    dataStore.edit { prefs ->
        prefs[subtitleTextColorKey] = color
    }
}
```

The persisted key `"subtitle_text_color"` is left in the DataStore on existing installs (no migration needed) — DataStore preferences silently ignore unread keys.

- [ ] **Step 5: Compile**

```
./gradlew :app:compileDebugKotlin
```

Expected: FAIL — there are still call sites of `setSubtitleTextColor` and references to `textColor`. The compiler errors will guide the next tasks.

Do not commit yet — Task 5 fixes the call sites in the same compile cycle.

---

### Task 5: Remove `textColor` consumers in playback events, sync, and ViewModel

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`

- [ ] **Step 1: Remove the `OnSetSubtitleTextColor` event branch (PlayerRuntimeControllerPlaybackEvents.kt lines 881-883)**

Delete:

```kotlin
is PlayerEvent.OnSetSubtitleTextColor -> {
    scope.launch { playerSettingsDataStore.setSubtitleTextColor(event.color) }
}
```

- [ ] **Step 2: Remove `setSubtitleTextColor` from the reset-defaults handler (line 900)**

In `PlayerEvent.OnResetSubtitleDefaults` branch, delete this line:

```kotlin
playerSettingsDataStore.setSubtitleTextColor(defaults.textColor)
```

- [ ] **Step 3: Remove `setSubtitleTextColor` from the sync apply path (AccountSettingsSyncService.kt line 1135)**

Delete:

```kotlin
playerSettingsDataStore.setSubtitleTextColor(settings.playback.subtitles.textColor)
```

The remote payload field `SubtitleSyncSettings.textColor` is left in `AccountSyncModels.kt` for backward-compatibility with older clients writing to the same cloud row.

- [ ] **Step 4: Remove `setSubtitleTextColor` from the settings ViewModel (lines 280-282)**

Delete:

```kotlin
suspend fun setSubtitleTextColor(color: Int) {
    playerSettingsDataStore.setSubtitleTextColor(color)
}
```

- [ ] **Step 5: Compile**

```
./gradlew :app:compileDebugKotlin
```

Expected: still failing — UI references in `PlayerScreen.kt`, `SubtitleDialog.kt`, `SubtitleStyleSidePanel.kt`, `PlaybackSubtitleSettings.kt`, `PlaybackSettingsScreen.kt` and the `OnSetSubtitleTextColor` sealed event remain. Task 6 cleans them up.

Do not commit yet.

---

### Task 6: Remove `textColor` UI references and the `OnSetSubtitleTextColor` event

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/SubtitleDialog.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/SubtitleStyleSidePanel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSubtitleSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Delete the sealed event (`PlayerUiState.kt:215`)**

Delete:

```kotlin
data class OnSetSubtitleTextColor(val color: Int) : PlayerEvent()
```

- [ ] **Step 2: Remove the text-color block in `SubtitleDialog.kt` (lines 739-756)**

Delete the entire block:

```kotlin
// Text Color
Column {
    Text(
        text = stringResource(R.string.subtitle_text_color),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SUBTITLE_TEXT_COLORS.forEach { color ->
            StyleColorChip(
                color = color,
                isSelected = subtitleStyle.textColor == color.toArgb(),
                onClick = { onEvent(PlayerEvent.OnSetSubtitleTextColor(color.toArgb())) }
            )
        }
    }
}

Spacer(modifier = Modifier.height(12.dp))
```

If `SUBTITLE_TEXT_COLORS` becomes unused after this deletion, also remove its declaration. Verify with:

```
grep -rn "SUBTITLE_TEXT_COLORS\b" app/src/main/java/com/nexio/tv
```

- [ ] **Step 3: Remove the text-color row in `SubtitleStyleSidePanel.kt` (around line 169)**

Open the file, locate the row that contains:

```kotlin
isSelected = subtitleStyle.textColor == color.toArgb(),
onClick = { onEvent(PlayerEvent.OnSetSubtitleTextColor(color.toArgb())) }
```

Delete the entire enclosing UI block (the surrounding `Row { ... }` or `Column { ... }` that renders the text color picker — search for the nearest enclosing label/section above the offending lines and remove that section through its closing brace).

- [ ] **Step 4: Remove the text-color settings item in `PlaybackSubtitleSettings.kt` (lines 182-191)**

Delete:

```kotlin
item(key = "subtitle_text_color") {
    ColorSettingsItem(
        icon = Icons.Default.Palette,
        title = stringResource(R.string.sub_text_color),
        currentColor = Color(playerSettings.subtitleStyle.textColor),
        onClick = onShowTextColorDialog,
        onFocused = onItemFocused,
        enabled = enabled
    )
}
```

Also remove the dialog branch (lines 293-304):

```kotlin
if (showTextColorDialog) {
    ColorSelectionDialog(
        title = stringResource(R.string.sub_text_color),
        colors = subtitleColors,
        selectedColor = Color(playerSettings.subtitleStyle.textColor),
        onColorSelected = {
            onSetTextColor(it)
            onDismissTextColorDialog()
        },
        onDismiss = onDismissTextColorDialog
    )
}
```

Remove the `showTextColorDialog`, `onSetTextColor`, `onShowTextColorDialog`, `onDismissTextColorDialog` parameters from both `SubtitleSettingsItems` and `SubtitleSettingsDialogs` function signatures (search the file for each and remove from the parameter lists).

- [ ] **Step 5: Remove text-color wiring in `PlaybackSettingsScreen.kt`**

Delete in this order:

- Line 192: `var showTextColorDialog by remember { mutableStateOf(false) }`
- Line 210: `showTextColorDialog = false`
- Line 258: `onShowTextColorDialog = { openDialog { showTextColorDialog = true } },`
- Line 453: `showTextColorDialog = showTextColorDialog,`
- Line 480: the `onSetTextColor = { color -> coroutineScope.launch { viewModel.setSubtitleTextColor(color.toArgb()) } },` line block
- Line 509: `onDismissTextColorDialog = ::dismissAllDialogs,`

- [ ] **Step 6: Compile**

```
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. (UI surface no longer references `textColor`.)

- [ ] **Step 7: Remove the unused string resources**

Verify nothing references the strings:

```
grep -rn "R.string.sub_text_color\|R.string.subtitle_text_color" app/src/main/java/com/nexio/tv
```

Expected: no matches. Then delete `<string name="sub_text_color">` and `<string name="subtitle_text_color">` lines in `app/src/main/res/values/strings.xml`.

- [ ] **Step 8: Compile and run unit tests**

```
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit Tasks 4-6 together**

```
git add -u
git commit -m "refactor(subtitles): remove user-configurable subtitle text color

The subtitle text color is now hardcoded off-white as part of OLED
burn-in protection. Removes:
- SubtitleStyleSettings.textColor field and its DataStore key/setter
- OnSetSubtitleTextColor player event and runtime handler
- Color pickers in SubtitleDialog, SubtitleStyleSidePanel, PlaybackSubtitleSettings
- Text color sync apply (model field kept for older-client compat)"
```

---

### Task 7: Apply off-white + alpha cap in `applySubtitleStyle()`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerVideoSurface.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/SubtitleBurnInProtectionTest.kt`

This task wires the burn-in state through render state and applies the alpha + color overrides. Vertical/horizontal deltas come in Task 9.

- [ ] **Step 1: Extend `PlayerSurfaceRenderState`**

In `PlayerVideoSurface.kt:22-28`:

```kotlin
internal data class PlayerSurfaceRenderState(
    val resizeMode: Int,
    val subtitleStyle: SubtitleStyleSettings,
    val burnInProtection: BurnInProtectionState,
    val overlayCues: List<Cue>,
    val suppressNativeSubtitles: Boolean,
    val keepScreenOn: Boolean = false
)
```

Add import: `import com.nexio.tv.core.player.BurnInProtectionState`.

In `buildPlayerViewMutationPlan` (around line 49-60), update `updateSubtitleStyle`:

```kotlin
updateSubtitleStyle = previous?.subtitleStyle != current.subtitleStyle ||
    previous?.burnInProtection != current.burnInProtection,
```

In the `update = { playerView -> ... }` block (around line 149-153), pass through the burn-in state:

```kotlin
if (plan.updateSubtitleStyle) {
    playerView.subtitleView?.let {
        applySubtitleStyle(it, renderState.subtitleStyle, renderState.burnInProtection)
    }
    playerView.ensureExternalSubtitleOverlay()?.let {
        applySubtitleStyle(it, renderState.subtitleStyle, renderState.burnInProtection)
    }
}
```

- [ ] **Step 2: Extend `applySubtitleStyle` (`PlayerScreen.kt:126-171`)**

Replace the existing function:

```kotlin
internal fun applySubtitleStyle(
    subtitleView: SubtitleView,
    subtitleStyle: com.nexio.tv.data.local.SubtitleStyleSettings,
    burnInProtection: com.nexio.tv.core.player.BurnInProtectionState,
) {
    val baseFontSize = 24f
    val scaledFontSize = baseFontSize * (subtitleStyle.size / 100f)
    subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, scaledFontSize)
    subtitleView.setApplyEmbeddedFontSizes(false)

    val typeface = if (subtitleStyle.bold) {
        Typeface.DEFAULT_BOLD
    } else {
        Typeface.DEFAULT
    }
    val edgeType = if (subtitleStyle.outlineEnabled) {
        CaptionStyleCompat.EDGE_TYPE_OUTLINE
    } else {
        CaptionStyleCompat.EDGE_TYPE_NONE
    }

    val foregroundColor = if (burnInProtection.enabled) {
        com.nexio.tv.core.player.SUBTITLE_OFF_WHITE_ARGB
    } else {
        android.graphics.Color.WHITE
    }

    subtitleView.setStyle(
        CaptionStyleCompat(
            foregroundColor,
            subtitleStyle.backgroundColor,
            android.graphics.Color.TRANSPARENT,
            edgeType,
            subtitleStyle.outlineColor,
            typeface
        )
    )
    subtitleView.setApplyEmbeddedStyles(false)

    subtitleView.alpha = if (burnInProtection.enabled) {
        com.nexio.tv.core.player.SUBTITLE_MAX_ALPHA
    } else {
        1.0f
    }
    subtitleView.translationX = burnInProtection.horizontalOffsetPx

    val baselinePercent = subtitleStyle.verticalOffset.toFloat()
    val effectivePercent = baselinePercent + burnInProtection.verticalDeltaPercent
    val bottomPaddingFraction = (0.06f + (effectivePercent / 250f)).coerceIn(0f, 0.4f)
    subtitleView.setBottomPaddingFraction(bottomPaddingFraction)
    subtitleView.post {
        val extraPadding = (subtitleView.height * (effectivePercent / 400f))
            .toInt()
            .coerceAtLeast(0)
        subtitleView.setPadding(
            subtitleView.paddingLeft,
            subtitleView.paddingTop,
            subtitleView.paddingRight,
            extraPadding
        )
    }
}
```

Note: removed `subtitleStyle.textColor` (no longer exists). The renderer-default white branch is kept for the toggle-off case so non-OLED users get pure white.

- [ ] **Step 3: Update PlayerScreen.kt:538 render state construction**

In the call to `PlayerSurfaceRenderState(...)`, add:

```kotlin
burnInProtection = uiState.burnInProtection,
```

- [ ] **Step 4: Add a smoke test verifying the constants are wired**

Append to `SubtitleBurnInProtectionTest.kt`:

```kotlin
@Test
fun off_white_constant_is_F0F0F0() {
    assertEquals(0xFFF0F0F0.toInt(), SUBTITLE_OFF_WHITE_ARGB)
}

@Test
fun max_alpha_constant_is_zero_point_nine() {
    assertEquals(0.90f, SUBTITLE_MAX_ALPHA, 0.0001f)
}
```

- [ ] **Step 5: Compile and run tests**

```
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.nexio.tv.core.player.SubtitleBurnInProtectionTest"
```

Expected: BUILD SUCCESSFUL; tests PASS. Other compile errors will surface from `uiState.burnInProtection` not yet existing — those are fixed in Task 8.

Do not commit yet.

---

### Task 8: Add `burnInProtection` to UI state and observers

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`

- [ ] **Step 1: Add field to `PlayerUiState` (next to `subtitleStyle`)**

```kotlin
val burnInProtection: com.nexio.tv.core.player.BurnInProtectionState =
    com.nexio.tv.core.player.BurnInProtectionState.DISABLED,
```

- [ ] **Step 2: Wire enabled-flag changes through `observeSubtitleSettings()`**

In `PlayerRuntimeControllerObservers.kt:230-248`, extend the state.copy block. Important: when the toggle flips to false at runtime we want the deltas zeroed out *immediately*, but when it flips to true we want a fresh seed-derived state. The simplest correct policy: track only the enabled flag here; recompute deltas in the playback initialization seam (Task 9). When toggling off mid-playback, set to DISABLED; when toggling on mid-playback, leave any prior `verticalDeltaPercent`/`horizontalOffsetPx` zeroed until the next stream — that's an acceptable v1 trade-off (toggling on takes effect on next stream).

```kotlin
state.copy(
    subtitleStyle = settings.subtitleStyle,
    subtitleOrganizationMode = SubtitleOrganizationMode.BY_LANGUAGE,
    loadingOverlayEnabled = settings.loadingOverlayEnabled,
    showLoadingOverlay = shouldShowOverlay,
    pauseOverlayEnabled = settings.pauseOverlayEnabled,
    osdClockEnabled = settings.osdClockEnabled,
    burnInProtection = if (settings.burnInProtection.enabled) {
        // Preserve any previously-resolved deltas; only the enable flag flips here.
        state.burnInProtection.copy(enabled = true)
    } else {
        com.nexio.tv.core.player.BurnInProtectionState.DISABLED
    },
)
```

- [ ] **Step 3: Compile**

```
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit Tasks 7-8 together**

```
git add -u
git commit -m "feat(burnin): apply off-white color and alpha cap to subtitle style

Threads BurnInProtectionState through PlayerSurfaceRenderState and
applies the burn-in overrides (off-white color, alpha cap, vertical/
horizontal deltas) inside applySubtitleStyle(). Vertical/horizontal
deltas remain zero until per-stream computation lands in the next task."
```

---

### Task 9: Compute per-stream deltas at playback session start

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/SubtitleBurnInProtectionTest.kt`

- [ ] **Step 1: Add a helper to derive the media seed key**

Add to `SubtitleBurnInProtection.kt`:

```kotlin
/**
 * Build a stable media-identity seed string. Prefers contentId+season+episode for
 * tracked content; falls back to the stream URL for arbitrary playback (e.g., trailers
 * launched into the main player or untracked debrid streams).
 */
fun buildMediaSeedKey(
    contentId: String?,
    season: Int?,
    episode: Int?,
    streamUrl: String,
): String {
    val trimmedContentId = contentId?.takeIf { it.isNotBlank() }
    return when {
        trimmedContentId != null && season != null && episode != null ->
            "$trimmedContentId:s${season}e${episode}"
        trimmedContentId != null -> trimmedContentId
        else -> "url:${streamUrl.hashCode()}"
    }
}
```

- [ ] **Step 2: Write tests for the seed builder**

Append to `SubtitleBurnInProtectionTest.kt`:

```kotlin
@Test
fun seed_uses_content_id_with_season_and_episode_when_all_present() {
    assertEquals("tt9999:s2e5", buildMediaSeedKey("tt9999", 2, 5, "https://example/x"))
}

@Test
fun seed_uses_content_id_alone_for_movies() {
    assertEquals("tt9999", buildMediaSeedKey("tt9999", null, null, "https://example/x"))
}

@Test
fun seed_falls_back_to_stream_url_hash_when_content_id_missing() {
    val seed = buildMediaSeedKey(null, null, null, "https://example/movie.mkv")
    assertEquals("url:${"https://example/movie.mkv".hashCode()}", seed)
}

@Test
fun seed_treats_blank_content_id_as_missing() {
    val seed = buildMediaSeedKey("   ", null, null, "https://example/y")
    assertEquals("url:${"https://example/y".hashCode()}", seed)
}
```

- [ ] **Step 3: Run tests**

```
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.player.SubtitleBurnInProtectionTest"
```

Expected: PASS.

- [ ] **Step 4: Resolve deltas in `PlayerRuntimeControllerInitialization.kt`**

In the same file, around line 191 where `playbackSessionId = playbackSessionGuard.beginPlaybackSession()`, add a call after the session has begun and `currentStreamUrl` is set, but before player listener registration. Find a stable insertion point inside the `apply { ... }` block where the player and current stream identity are known.

Add a helper near the top of the file (private, file-level):

```kotlin
private suspend fun PlayerRuntimeController.resolveBurnInProtectionState(
    enabled: Boolean,
): com.nexio.tv.core.player.BurnInProtectionState {
    if (!enabled) return com.nexio.tv.core.player.BurnInProtectionState.DISABLED
    val salt = playerSettingsDataStore.getOrCreateBurnInProtectionUserSalt()
    val seed = com.nexio.tv.core.player.buildMediaSeedKey(
        contentId = contentId,
        season = initialSeason,
        episode = initialEpisode,
        streamUrl = currentStreamUrl,
    )
    return com.nexio.tv.core.player.computeBurnInProtectionState(
        enabled = true,
        mediaSeedKey = seed,
        userSalt = salt,
        nowMs = System.currentTimeMillis(),
    )
}
```

In the body of the playback initialization function (the function that opens around line 191 — find by `fun .*beginPlaybackSession\|playbackSessionId = playbackSessionGuard.beginPlaybackSession`), add after `currentStreamUrl` has been finalized for the new session:

```kotlin
scope.launch {
    val enabled = playerSettingsDataStore.playerSettings.first().burnInProtection.enabled
    val resolved = resolveBurnInProtectionState(enabled)
    if (!playbackSessionGuard.shouldHandleCallback(playbackSessionId)) return@launch
    _uiState.update { it.copy(burnInProtection = resolved) }
}
```

Add imports as needed:
- `kotlinx.coroutines.flow.first`

- [ ] **Step 5: Compile and run tests**

```
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL; all tests PASS.

- [ ] **Step 6: Commit**

```
git add -u
git commit -m "feat(burnin): resolve per-stream deltas at playback session start

Derives a stable media seed (contentId+season+episode | stream URL hash),
combines with persisted user salt and day bucket, and writes the resolved
BurnInProtectionState into uiState before the player begins rendering."
```

---

### Task 10: Add the burn-in toggle to settings UI

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSubtitleSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the ViewModel setter**

In `PlaybackSettingsViewModel.kt`, near other subtitle setters:

```kotlin
suspend fun setBurnInProtectionEnabled(enabled: Boolean) {
    playerSettingsDataStore.setBurnInProtectionEnabled(enabled)
}
```

- [ ] **Step 2: Add the toggle row to `PlaybackSubtitleSettings.kt`**

Add a new `item(...)` entry at the top of the subtitle settings list (immediately after preferred-language items, before size/offset). Add an `onSetBurnInProtectionEnabled: (Boolean) -> Unit` parameter to the `SubtitleSettingsItems` function:

```kotlin
item(key = "subtitle_burn_in_protection") {
    ToggleSettingsItem(
        icon = Icons.Default.Shield,
        title = stringResource(R.string.subtitle_burn_in_protection_title),
        subtitle = stringResource(R.string.subtitle_burn_in_protection_subtitle),
        isChecked = playerSettings.burnInProtection.enabled,
        onCheckedChange = onSetBurnInProtectionEnabled,
        onFocused = onItemFocused,
        enabled = enabled
    )
}
```

If `Icons.Default.Shield` is not in the existing imports, use `Icons.Default.Tv` or `Icons.Default.AutoAwesome` — match an icon already imported in the file.

- [ ] **Step 3: Wire the screen handler in `PlaybackSettingsScreen.kt`**

In the call to `SubtitleSettingsItems(...)`, pass:

```kotlin
onSetBurnInProtectionEnabled = { enabled ->
    coroutineScope.launch { viewModel.setBurnInProtectionEnabled(enabled) }
},
```

- [ ] **Step 4: Add string resources**

In `app/src/main/res/values/strings.xml`:

```xml
<string name="subtitle_burn_in_protection_title">OLED burn-in protection</string>
<string name="subtitle_burn_in_protection_subtitle">Rotates subtitle position per stream and softens whites to reduce panel wear.</string>
```

- [ ] **Step 5: Compile**

```
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```
git add -u
git commit -m "feat(burnin): add OLED burn-in protection toggle to subtitle settings"
```

---

### Task 11: End-to-end verification on device

These are not code steps — they verify the implementation. Each line is one verification action with the expected outcome.

- [ ] **Step 1: Install on an Android TV / Fire TV device or emulator**

```
./gradlew :app:installDebug
```

Expected: install completes; app launches.

- [ ] **Step 2: Confirm the toggle is present and ON by default**

Navigate: Settings → Playback → Subtitles. Locate **OLED burn-in protection** row. Expected: toggle present, default ON.

- [ ] **Step 3: Confirm the text-color picker is gone**

In the same screen, scroll the subtitle controls. Expected: no "Text color" / "Subtitle text color" row anywhere. Open an in-playback subtitle dialog (during playback, press menu/options to open subtitles dialog). Expected: no text-color picker.

- [ ] **Step 4: Confirm subtitle color is off-white during playback**

Start any subtitled playback. Compare subtitle text against white UI elements (the OSD clock, control labels). Expected: subtitle text is visibly slightly cooler/dimmer (off-white #F0F0F0) than the pure-white UI text. Toggle burn-in OFF; subtitles should snap to pure white.

- [ ] **Step 5: Confirm zone rotation across episodes within one day**

Play 3–5 different episodes of a series back-to-back. Expected: each episode's subtitle vertical position is slightly different (within ±3% of the user's `verticalOffset`), and the horizontal centerline shifts up to ±6 px between episodes.

- [ ] **Step 6: Confirm zone stability for re-watch within the same day**

Replay the same episode within a few minutes. Expected: subtitle position is identical to the previous play of that episode.

- [ ] **Step 7: Confirm the embedded and addon subtitle views move in lockstep**

Find a stream that has both embedded subs and an addon-supplied SRT/VTT (Stremio addon). Toggle between the two subtitle tracks. Expected: both subtitle layers render at the same vertical zone and the same horizontal offset.

- [ ] **Step 8: Regression: ASS/SSA stream is unchanged**

Play an anime episode that uses ASS/SSA subtitles (these route through `AssSsaRenderOverlayView`). Expected: subtitle position and color match `main` branch behavior — no zone shift, pure white preserved, no alpha cap. (This is a regression test that the bitmap path was not touched.)

- [ ] **Step 9: Regression: pause behavior unchanged**

Pause playback for >60 seconds. Expected: subtitles do not dim or hide. They remain visible at the same alpha and color. (Pause protection is out of scope; verifying we did not accidentally introduce it.)

- [ ] **Step 10: Toggle OFF mid-playback**

While subtitled playback is running, open Settings, toggle burn-in OFF, return to playback. Expected: subtitle alpha returns to 1.0, color flips to pure white, horizontal position recenters (`translationX = 0`). Subtitle position falls back to the user's `verticalOffset` baseline only.

- [ ] **Step 11: Toggle ON mid-playback, start a new stream**

Toggle burn-in back ON. Start a new stream (different episode). Expected: subtitle alpha caps at 0.9, color is off-white, vertical/horizontal deltas reapply for the new stream.

- [ ] **Step 12: Run full test suite once more to confirm no regressions**

```
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

---

## Acceptance criteria

- All unit tests pass (`./gradlew :app:testDebugUnitTest`).
- Build succeeds for debug variant (`./gradlew :app:assembleDebug`).
- All 12 verification steps in Task 11 behave as described.
- `grep -rn "textColor" app/src/main/java/com/nexio/tv | grep -v ass\|library\|search\|components\|theme\|chip` returns no matches related to subtitle styling.
- Both `playerView.subtitleView` and the external addon `SubtitleView` receive identical `bottomPaddingFraction` and `translationX` after each per-stream resolve.
- The ASS/SSA path (`AssSsaRenderOverlayView` and `app/src/main/java/com/nexio/tv/ui/screens/player/ass/**`) is untouched in the diff for this branch (`git diff main -- 'app/src/main/java/com/nexio/tv/ui/screens/player/ass/**'` is empty).
- No new `Player.Listener` is registered. Existing listeners are not modified other than the unrelated handlers already present.

---

## Notes for the engineer

- The seed includes a `dayBucket` derived from `System.currentTimeMillis()`. There is no daylight-savings or timezone correction — buckets use UTC ms / 86_400_000. That's acceptable since drift is fine for this purpose; a user crossing a DST boundary mid-binge may get a different zone slightly earlier or later than expected, which is invisible.
- `PlayerSettingsDataStore.getOrCreateBurnInProtectionUserSalt()` performs a read-then-write. In the unlikely race where two callers hit it simultaneously on first run, the second `edit` block re-checks the key under the DataStore lock, so only one salt is ever stored.
- `BurnInProtectionState.DISABLED` is `enabled=false, vertical=0, horizontal=0`. When `applySubtitleStyle()` sees `enabled=false`, it does NOT clamp alpha and uses pure white — the user's pre-burn-in subtitle appearance is preserved.
- `subtitleStyle.backgroundColor` is left fully user-configurable. Burn-in protection only touches foreground color and alpha.
- The `SubtitleSyncSettings.textColor` field in `AccountSyncModels.kt` is intentionally retained to keep payload deserialization compatible with older clients that still write the field. The local DataStore no longer consumes it. A future cleanup pass can delete the model field once all clients are upgraded — that work is out of scope.
