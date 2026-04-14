# Phase 3: Profile UI - Research

**Researched:** 2026-04-14
**Domain:** Android TV / Jetpack Compose — D-pad navigable profile selection, PIN entry, sidebar profile switcher, settings header
**Confidence:** HIGH (all key patterns verified directly from NuvioTV reference implementation and Nexio codebase)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Profile Selection Screen**
- D-01: Profile avatars use Supabase photos when available (via `avatarId`). Until Phase 5 delivers photo upload, the fallback is a colored circle (from `avatarColorHex` / ProfileAvatarColors) with the profile name's first initial centered.
- D-02: Selection screen appears exactly once per session when 2+ profiles exist, never for single-profile users (UI-01, UI-02).
- D-03: D-pad focus indicator uses scale-up (~1.15x) plus a colored border glow on the focused avatar. Clear at 10-foot viewing distance.
- D-04: Selection screen is pick-only — no "Add Profile" button, no "Manage Profiles" mode. Profile creation/editing/deletion is exclusively in Settings.
- D-05: Layout is a horizontal row of profile avatars with "Who's watching?" heading.

**PIN Entry**
- D-06: PIN numpad uses a 3x4 phone-style grid layout: 1-2-3 / 4-5-6 / 7-8-9 / clear-0-confirm. All cells are D-pad focusable.
- D-07: PIN dots (4 filled/unfilled circles) appear above the numpad showing entry progress.
- D-08: Wrong PIN: dots shake horizontally with brief red "Wrong PIN" text below. No modal dialogs.
- D-09: Rate limit exceeded: dots grayed out, countdown text "Try again in Xs" shown. Numpad disabled until timer expires. Server-enforced `retryAfterSeconds` drives the countdown (UI-06).
- D-10: Locked profiles show a small lock icon badge on the bottom-right of the avatar circle on the selection screen. PIN entry screen appears after selecting a locked profile.

**Sidebar Profile Switcher**
- D-11: Profile switcher sits at the top of the sidebar, above navigation items. Shows active profile avatar + name with an expand arrow.
- D-12: Activating the switcher expands other profiles inline below the active profile, pushing nav items down. D-pad down navigates through profiles. Select to switch, back to collapse.
- D-13: Selecting a different profile triggers playback stop + return to home, then switches the active profile.
- D-14: Sidebar profile switcher is hidden when only 1 profile exists.

**Settings Header**
- D-15: Active profile displayed at the top of the settings screen: small avatar circle + profile name + "Default" badge for Profile 1.
- D-16: Settings header profile display is always visible, even for single-profile users.

### Claude's Discretion
- Animation durations and easing curves for profile selection focus, sidebar expand/collapse, and PIN shake
- Exact avatar circle sizes (selection screen vs sidebar vs settings header)
- Color of the focus border glow (accent color or avatar color)
- PIN entry screen background treatment (dimmed overlay vs full screen)
- Lock icon badge size and styling
- Keyboard/remote button shortcuts (e.g., whether number keys on a full keyboard can directly enter PIN digits)

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| UI-01 | User sees profile selection screen only when 2+ profiles exist | Session gating pattern verified in NuvioTV: `rememberSaveable { mutableStateOf(false) }` + `profiles.size > 1` check before NavHost |
| UI-02 | Profile selection is session-scoped (shown once per session, not per launch) | `hasSelectedProfileThisSession` via `rememberSaveable` survives Activity re-creation within process lifetime; reset on profile switch |
| UI-03 | Profile selection screen is fully D-pad navigable on Android TV | FocusRequester per avatar card pattern; `onPreviewKeyEvent` for D-pad interception; auto-focus to active profile index |
| UI-04 | User can optionally set a PIN on their profile (server-side hash) | `profileManager.profiles` StateFlow exposes `pinEnabled`; `ProfileSyncService.verifyProfilePin()` returns `SupabaseProfilePinVerifyResult` |
| UI-05 | PIN entry uses a D-pad-friendly on-screen numpad | D-06 decision: 3x4 grid; NuvioTV uses invisible `BasicTextField` (1dp) + `onPreviewKeyEvent` digit capture + `keyCodeToDigit()` helper |
| UI-06 | PIN verification respects server rate limiting (retryAfterSeconds) | `SupabaseProfilePinVerifyResult.retryAfterSeconds > 0` drives countdown display; countdown must disable numpad until `retryAfterSeconds` elapses |
| UI-07 | User can switch profiles from the sidebar menu | NuvioTV: `SidebarProfileItem` composable in sidebar panel; `onSwitchProfile` sets `hasSelectedProfileThisSession = false` triggering re-gate |
| UI-08 | Active profile name/avatar is visible in settings header | `SettingsDesignSystem` row pattern extended with `ProfileAvatarCircle` + name + "Default" badge |
</phase_requirements>

---

## Summary

Phase 3 builds all profile-facing UI in Nexio: the profile selection screen (once per session), a D-pad numpad PIN entry overlay, a sidebar profile switcher, and a settings header profile display. The NuvioTV codebase contains a near-complete reference implementation for every one of these components — the primary work is adapting NuvioTV patterns to Nexio's design system and trimming Management Mode features (Add/Edit/Delete) that belong in Settings (Phase 1).

The session gating pattern is well-established: `hasSelectedProfileThisSession` via `rememberSaveable` inside `setContent {}` in `MainActivity`, checked before the NavHost renders. NuvioTV uses `profiles.size > 1` as the gating condition; Nexio will use the same. The PIN overlay in NuvioTV uses an invisible `BasicTextField` (1×1dp, alpha=0) to capture D-pad numeric key events — no explicit numpad grid composable exists in NuvioTV for *unlock* flow. Decision D-06 specifies a 3×4 numpad grid for Nexio; this will need to be built as a new composable since NuvioTV's unlock PIN flow relies on keyboard input capture rather than a rendered grid. The `ProfileAvatarCircle` composable is directly portable from NuvioTV verbatim with only the import package changed.

The most critical integration points are: (1) `NexioNavHost.kt` startDestination gating in `MainActivity.kt`, (2) `SidebarNavigation.kt` extended with a profile section at the top, and (3) `SettingsScreen.kt` header insertion. All three are well-understood from reading the existing Nexio code.

**Primary recommendation:** Port NuvioTV's `ProfileSelectionScreen` as the starting point (Selection mode only, strip Management mode), port `ProfileAvatarCircle` verbatim, build a new `ProfilePinNumpad` composable (3×4 grid per D-06), and wire session gating in `MainActivity` following the NuvioTV `rememberSaveable` pattern.

---

## Standard Stack

### Core (already in Nexio, no new dependencies)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Jetpack Compose (androidx.tv.material3) | In use | TV-optimized composables, focus management | Already used across all Nexio screens |
| Hilt (dagger.hilt.android) | In use | ViewModel injection | Used in every screen in Nexio |
| Coil (coil.compose) | In use | Async avatar image loading via `AsyncImage` | Already used in `SettingsDesignSystem.kt` and NuvioTV's `ProfileAvatarCircle` |
| Kotlin Coroutines / StateFlow | In use | Reactive profile state | `ProfileManager.profiles`, `activeProfileId` are `StateFlow` |
| androidx.navigation.compose | In use | NavHost routing, `composable {}` destinations | Existing `NexioNavHost.kt` |

**No new library dependencies required for this phase.** [VERIFIED: reading codebase imports]

---

## Architecture Patterns

### Recommended Project Structure

```
app/src/main/java/com/nexio/tv/
├── ui/
│   ├── components/
│   │   └── ProfileAvatarCircle.kt        ← port from NuvioTV verbatim (rename package)
│   ├── screens/
│   │   └── profile/
│   │       ├── ProfileSelectionScreen.kt  ← new (port + adapt from NuvioTV)
│   │       └── ProfileSelectionViewModel.kt ← new (simplified; no Sync/AvatarRepo deps yet)
│   ├── components/
│   │   └── SidebarNavigation.kt          ← extend existing with ProfileSwitcherSection
│   └── screens/settings/
│       └── SettingsScreen.kt             ← insert ProfileHeader composable at top
└── ui/navigation/
    └── Screen.kt                          ← add ProfileSelection data object
```

### Pattern 1: Session Gating (UI-01, UI-02)

**What:** Check `hasSelectedProfileThisSession` + profile count before rendering NavHost in `MainActivity.setContent {}`. Use `rememberSaveable` so the flag survives Activity re-creation (rotation, multi-window) within the same process.

**When to use:** At the `MainActivity` composition root, before `NexioNavHost` is called.

**Example (from NuvioTV MainActivity.kt — verified):**
```kotlin
// Source: NuvioTV/app/.../MainActivity.kt lines 225, 359-370
var hasSelectedProfileThisSession by rememberSaveable { mutableStateOf(false) }
val profiles by profileManager.profiles.collectAsState()

val shouldShowProfileSelection =
    !hasSelectedProfileThisSession && profiles.size > 1

if (shouldShowProfileSelection) {
    ProfileSelectionScreen(
        onProfileSelected = { hasSelectedProfileThisSession = true }
    )
    return@Surface
}
// NavHost renders here
```

**Reset on profile switch (sidebar):**
```kotlin
onSwitchProfile = { hasSelectedProfileThisSession = false }
```
This causes recomposition to show the selection screen again when the user switches from sidebar. [VERIFIED: NuvioTV MainActivity.kt lines 475-476]

### Pattern 2: ProfileAvatarCircle (shared component)

**What:** Colored circle with first-letter initial. Loads `avatarImageUrl` via Coil `AsyncImage` when non-null; falls back to initial letter.

**Example (from NuvioTV ProfileAvatarCircle.kt — verified):**
```kotlin
// Source: NuvioTV/app/.../ProfileAvatarCircle.kt
@Composable
fun ProfileAvatarCircle(
    name: String,
    colorHex: String,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    isSelected: Boolean = false,
    avatarImageUrl: String? = null
) {
    val avatarColor = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(Color(0xFF1E88E5))
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val fontSize = (size.value * 0.4f).sp
    Box(modifier = modifier.size(size).clip(CircleShape).background(avatarColor, CircleShape)) {
        if (avatarImageUrl != null) {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(avatarImageUrl).crossfade(true).build(),
                contentDescription = name, modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop)
        } else {
            Text(text = initial, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold)
        }
    }
}
```
This composable can be copied verbatim with only the package import changed. [VERIFIED: codebase read]

### Pattern 3: D-pad Focus on Avatar Cards

**What:** Each avatar card gets its own `FocusRequester`. A `LaunchedEffect` fires `repeat(2) { withFrameNanos {} }` then `requestFocus()` on the initially-active profile index. Scale animation driven by `animateFloatAsState` on `isFocused` state.

**Example (from NuvioTV ProfileSelectionScreen.kt — verified):**
```kotlin
// Source: NuvioTV ProfileSelectionScreen.kt lines 706-710, 764-769
val focusRequesters = remember(totalItems) { List(totalItems) { FocusRequester() } }
LaunchedEffect(totalItems, initialFocusIndex) {
    repeat(2) { withFrameNanos { } }
    runCatching { focusRequesters[initialFocusIndex].requestFocus() }
}

val focusProgress by animateFloatAsState(
    targetValue = if (isFocused) 1f else 0f,
    animationSpec = tween(durationMillis = 210, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
    label = "profileFocusProgress"
)
val itemScale = 1f + (0.04f * focusProgress)  // subtle 4% scale-up
```

The `repeat(2) { withFrameNanos {} }` skip is essential — requesting focus on the first frame often fails before the layout pass. [VERIFIED: NuvioTV codebase]

### Pattern 4: PIN Entry — NuvioTV Approach vs. Nexio Decision D-06

**Critical difference:** NuvioTV's PIN unlock uses an **invisible BasicTextField** (1dp, alpha=0) that captures hardware keyboard/numpad input via `onPreviewKeyEvent`. There is no rendered 3×4 button grid for the unlock flow. Nexio Decision D-06 specifies a **3×4 phone-style numpad grid** where all cells are D-pad focusable — this is a Nexio-specific design.

**What to build:**
- `ProfilePinNumpad` — new composable: 3 rows × 4 columns of focusable `Box` cells (1,2,3 / 4,5,6 / 7,8,9 / Clear,0,Confirm)
- Each cell uses `FocusRequester` + `onFocusChanged` + `onKeyEvent` (DPAD_CENTER = select)
- PIN dots composable (`ProfilePinBoxes`) can be ported verbatim from NuvioTV (lines 1833–1930) — it already implements filled dots with shake animation
- Auto-submit when 4th digit entered (LaunchedEffect on pin.length)

**NuvioTV PIN box (dot indicators) — portable verbatim:**
```kotlin
// Source: NuvioTV ProfileSelectionScreen.kt lines 1833-1930
// ProfilePinBoxes: Row of 4 boxes. isFilled = index < value.length.
// isErrorState triggers red border + dark red background on all boxes.
// shakeOffset applied via graphicsLayer { translationX = shakeOffset.value }
// Shake animation: listOf(-22f, 18f, -14f, 10f, -6f, 0f) each animated in 42ms
```

**Rate-limit display (D-09, UI-06):**
```kotlin
// retryAfterSeconds from SupabaseProfilePinVerifyResult drives a countdown
// NuvioTV: pinOverlayError = context.getString(R.string.profile_pin_locked, verify.retryAfterSeconds)
// Nexio: store retryAfterSeconds in ViewModel state; LaunchedEffect counts down with delay(1000)
// Numpad cells: enabled = retryAfterSeconds <= 0
```

### Pattern 5: Sidebar Profile Switcher

**NuvioTV approach (verified in ModernSidebarBlurPanel.kt):** A `SidebarProfileItem` composable renders above drawer items. It is a `Row` with a `ProfileAvatarCircle` + profile name, focusable via `.focusable(enabled = focusEnabled)`, key handler for DPAD_CENTER. Tapping it calls `onSwitchProfile` which resets `hasSelectedProfileThisSession = false`, triggering the selection screen.

**Nexio D-12 specifies inline expansion** (expand/collapse other profiles inline), which NuvioTV does not implement — NuvioTV goes directly back to the selection screen. This is the **primary divergence** from the reference: Nexio needs a collapsible profile list section in the sidebar.

**Recommended approach:**
```kotlin
// In SidebarNavigation.kt — add ProfileSwitcherSection above nav items
// State: var profileSectionExpanded by remember { mutableStateOf(false) }
// When expanded: AnimatedVisibility shows other profiles as SidebarProfileItem rows
// D-pad down from expanded section enters nav items
// Back key collapses section
```

### Pattern 6: Settings Header Profile Display (D-15, D-16, UI-08)

**What:** Always-visible row at the top of SettingsScreen content area using `SettingsDesignSystem` patterns.

**How:**
```kotlin
// In SettingsScreen.kt — insert before the LazyColumn of categories
ProfileHeaderRow(
    profile = activeProfile,     // from profileManager.activeProfile
    avatarImageUrl = null,       // Phase 5 delivers upload; null = initial fallback
)
```

Reuse `ProfileAvatarCircle` (small size, ~40dp) + profile name `Text` + conditional "Default" badge `Text` for `profile.isPrimary`. Follows `SettingsDesignSystem` card composable patterns already established. [VERIFIED: SettingsDesignSystem.kt, SettingsScreen.kt]

### Anti-Patterns to Avoid

- **Requesting focus synchronously on first frame:** Always use `repeat(2) { withFrameNanos {} }` before `requestFocus()`. Focus requested before layout is measured silently fails. [VERIFIED: pattern used in 3+ NuvioTV composables]
- **Using NavHost gating inside the NavGraph startDestination:** NuvioTV gates before the NavHost, not as the first route. Gating inside the NavGraph causes the back stack to include the selection screen, breaking back navigation.
- **Collecting `profileManager.profiles` as `StateFlow.value` (not reactive):** Always `collectAsStateWithLifecycle()` in composables; `StateFlow.value` snapshots can be stale immediately after a write.
- **Putting profile switcher expand state in ViewModel:** This is transient UI state that should live in `remember {}` in the composable, not in a ViewModel. It is session-local and should reset when the sidebar closes.
- **Blocking D-pad focus traversal with `focusProperties { canFocus = false }`:** Instead use `focusable(enabled = false)` for disabled numpad cells during rate-limit.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Avatar image loading | Custom image loader | Coil `AsyncImage` (already in project) | Handles crossfade, caching, ContentScale.Crop for circles |
| Focus animation interpolation | Manual lerp timing | `animateFloatAsState` + `CubicBezierEasing` | Frame-perfect, composable-friendly, cancellable |
| Shake animation for wrong PIN | Coroutine with delay loop | `Animatable.animateTo()` with sequential offsets | Cancellable, integrates with Compose snapshot state |
| Color parsing from hex strings | Custom hex parser | `android.graphics.Color.parseColor()` wrapped in `runCatching` | Handles malformed input, returns default on failure |
| Session flag persistence across Activity recreation | Custom saved state | `rememberSaveable` | Survives config changes within process; no extra code |

---

## Runtime State Inventory

Step 2.5 SKIPPED — this is a greenfield UI phase, not a rename/refactor/migration phase. No stored data, runtime configs, OS-registered state, secrets, or build artifacts need updating.

---

## Common Pitfalls

### Pitfall 1: Focus Lost After ProfileSelectionScreen Exits

**What goes wrong:** After `hasSelectedProfileThisSession = true`, the ProfileSelectionScreen composable leaves the composition. Nothing requests focus on the NavHost content. The D-pad stops responding.

**Why it happens:** Compose doesn't automatically redirect focus when a composable is removed from the tree.

**How to avoid:** In `MainActivity`, add a `LaunchedEffect(hasSelectedProfileThisSession)` that fires `contentFocusRequester.requestFocus()` (the existing `LocalContentFocusRequester`) after the profile screen exits.

**Warning signs:** User sees home screen but D-pad presses do nothing.

### Pitfall 2: PIN Rate-Limit Countdown Stops If Composable Recomposes

**What goes wrong:** A `LaunchedEffect(retryAfterSeconds)` countdown using `delay(1000)` is restarted when `retryAfterSeconds` changes (which it does at each decrement), causing the countdown to reset.

**Why it happens:** `LaunchedEffect` key changes cancel and restart the coroutine.

**How to avoid:** Use `LaunchedEffect(Unit)` to start the countdown once, with an internal loop that decrements a `MutableStateFlow<Int>` in the ViewModel each second. Key on the *initial* value, not the current value.

### Pitfall 3: Sidebar Profile Section Focus Trap

**What goes wrong:** User expands the profile section in the sidebar, navigates into it, then presses Back. The sidebar collapses entirely instead of just collapsing the profile section.

**Why it happens:** `BackHandler` intercepts all back presses; if the sidebar-level handler fires before the profile-section-level handler, the wrong thing collapses.

**How to avoid:** Profile section expanded state must have its own `BackHandler(enabled = profileSectionExpanded)` that runs before the sidebar's `BackHandler`. Compose `BackHandler` is LIFO — innermost wins when both are enabled.

**Warning signs:** User cannot "undo" expanding the profile section without fully dismissing the sidebar.

### Pitfall 4: `rememberSaveable` Session Flag Persisting Across Process Death

**What goes wrong:** User kills the app (process death), relaunches. `rememberSaveable` restores `hasSelectedProfileThisSession = true` from the Bundle, so the selection screen is skipped even on cold launch.

**Why it happens:** `rememberSaveable` saves to the Activity's `onSaveInstanceState` Bundle, which Android preserves across process death and restores on cold launch.

**How to avoid:** Use `rememberSaveable(stateSaver = Saver(...))` that always saves `false`, OR use `remember {}` (no Bundle save) if the goal is strictly "once per process lifetime". NuvioTV uses `rememberSaveable` which gives the stronger survival guarantee (config changes) at the cost of surviving process death. For Nexio, `remember {}` may be more correct semantically — investigate with owner.

**Warning signs:** App cold-launched from recent apps never shows selection screen.

### Pitfall 5: ProfileCard Lock Badge Not Rendering — `pinEnabled` vs. `profilePinEnabled` Map

**What goes wrong:** The lock badge is never shown because `profilePinEnabled` Map is empty, even though `profile.pinEnabled == true`.

**Why it happens:** NuvioTV's design separates local `profile.pinEnabled` (cached in DataStore) from a server-refreshed `profilePinEnabled: Map<Int, Boolean>` loaded via `ProfileSyncService.pullProfileLockStates()`. Phase 3 has no Supabase sync (that's Phase 4). The ViewModel must derive PIN state from `profile.pinEnabled` directly for this phase.

**How to avoid:** `ProfileSelectionViewModel` for Phase 3 should build the `profilePinEnabled` map directly from `profileManager.profiles.value` using `profile.pinEnabled` field, without calling `ProfileSyncService`. Phase 4 can replace this with server-refreshed data.

---

## Code Examples

### Session Gating in MainActivity

```kotlin
// Source: NuvioTV MainActivity.kt (verified, adapted for Nexio)
var hasSelectedProfileThisSession by rememberSaveable { mutableStateOf(false) }
val profiles by profileManager.profiles.collectAsState()

val shouldShowProfileSelection = !hasSelectedProfileThisSession && profiles.size > 1

if (shouldShowProfileSelection) {
    ProfileSelectionScreen(
        onProfileSelected = { hasSelectedProfileThisSession = true }
    )
    return@Surface
}
// ... rest of NavHost setup
```

### ProfileCard Focus with Scale Animation

```kotlin
// Source: NuvioTV ProfileSelectionScreen.kt (verified pattern)
val focusProgress by animateFloatAsState(
    targetValue = if (isFocused) 1f else 0f,
    animationSpec = tween(durationMillis = 210, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
    label = "profileFocusProgress"
)
val itemScale = 1f + (0.04f * focusProgress)
Modifier.graphicsLayer { scaleX = itemScale; scaleY = itemScale }
```

### PIN Digit Capture via onPreviewKeyEvent

```kotlin
// Source: NuvioTV ProfileSelectionScreen.kt lines 1667-1704 (verified)
.onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
    when (native.keyCode) {
        AndroidKeyEvent.KEYCODE_DEL, AndroidKeyEvent.KEYCODE_CLEAR -> {
            if (pin.isNotEmpty()) pin = pin.dropLast(1)
            true
        }
        else -> {
            val digit = keyCodeToDigit(native.keyCode)
            if (digit != null && pin.length < 4) { pin += digit; true } else false
        }
    }
}

private fun keyCodeToDigit(keyCode: Int): Char? = when (keyCode) {
    AndroidKeyEvent.KEYCODE_0, AndroidKeyEvent.KEYCODE_NUMPAD_0 -> '0'
    AndroidKeyEvent.KEYCODE_1, AndroidKeyEvent.KEYCODE_NUMPAD_1 -> '1'
    // ... through 9
    else -> null
}
```

### PIN Shake Animation

```kotlin
// Source: NuvioTV ProfileSelectionScreen.kt lines 1609-1614 (verified)
val shakeOffset = remember(state) { Animatable(0f) }
suspend fun playErrorAnimation() {
    shakeOffset.snapTo(0f)
    listOf(-22f, 18f, -14f, 10f, -6f, 0f).forEach { offset ->
        shakeOffset.animateTo(offset, animationSpec = tween(42))
    }
}
// Apply: Modifier.graphicsLayer { translationX = shakeOffset.value }
```

### Auto-Submit on 4th Digit

```kotlin
// Source: NuvioTV ProfileSelectionScreen.kt line 1637-1640 (verified)
LaunchedEffect(pin, isWorking) {
    if (pin.length != 4 || isWorking) return@LaunchedEffect
    onSubmit(pin)
}
```

### Sidebar Profile Item (simple, no inline expansion)

```kotlin
// Source: NuvioTV ModernSidebarBlurPanel.kt lines 312-379 (verified)
// SidebarProfileItem: Row with ProfileAvatarCircle + profile name text
// focusable(enabled = focusEnabled)
// onPreviewKeyEvent: DPAD_CENTER / Enter triggers onClick
// animateColorAsState for background on focus
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Selection screen gated by startDestination in NavGraph | Gate before NavHost in Activity composition root | NuvioTV current | Back stack doesn't include selection screen |
| PIN entry with rendered numpad buttons each time | Invisible BasicTextField + onPreviewKeyEvent for hardware key capture (NuvioTV) | Current | Cleaner, no focus management complexity for numpad |
| Static focus on first focusable element | `repeat(2) { withFrameNanos {} }` + requestFocus on active profile index | Current pattern | Eliminates race condition with layout measurement |

**Note:** Decision D-06 intentionally diverges from NuvioTV's invisible-TextField PIN approach. The 3×4 rendered numpad is a Nexio-specific design requirement for the unlock flow and must be built from scratch. [VERIFIED: D-06, NuvioTV code review]

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `remember {}` (not `rememberSaveable`) is the correct session flag mechanism for "once per process lifetime" semantics | Pitfall 4, Session Gating pattern | If `rememberSaveable` is intended (survives config change), code is correct. If `remember` is intended (resets on rotation), the flag type matters. Owner should confirm. | [ASSUMED] |
| A2 | Phase 3 `ProfileSelectionViewModel` derives `pinEnabled` from `profile.pinEnabled` in local DataStore, not from Supabase | Don't Hand-Roll / Pitfall 5 | If Supabase is expected in Phase 3 for PIN state, the ViewModel needs `ProfileSyncService` injected. Context.md says no Supabase sync until Phase 4. | [ASSUMED — consistent with CONTEXT.md phase boundary] |
| A3 | The sidebar profile switcher for Nexio (D-12: inline expansion) is a net-new composable not derived from NuvioTV | Architecture Patterns | NuvioTV goes directly to selection screen on profile item tap; inline expansion doesn't exist in reference. Risk if the planner expects to port it directly. | [ASSUMED — verified by code reading] |

---

## Open Questions

1. **Session flag semantics: `remember` vs `rememberSaveable`**
   - What we know: NuvioTV uses `rememberSaveable` (survives config changes + process death restore). Both are valid.
   - What's unclear: Should the selection screen re-appear after rotation? Or only after process death?
   - Recommendation: Use `remember {}` for strict "once per process" semantics. This matches Netflix/Disney+ behavior. If user wants rotation-safe session, use `rememberSaveable`.

2. **D-12 Sidebar Inline Expansion: how far to implement in Phase 3**
   - What we know: D-12 specifies inline profile list expansion in the sidebar. NuvioTV has no equivalent.
   - What's unclear: Should this animate with `AnimatedVisibility` pushing items down, or use a modal overlay?
   - Recommendation: `AnimatedVisibility` with `expandVertically` + `shrinkVertically` pushing nav items down. This is the most natural D-pad flow and avoids overlay z-order complexity.

---

## Environment Availability

Step 2.6 SKIPPED — phase is pure code changes within the existing Kotlin/Compose/Gradle project. No new external tools, services, or CLIs are required.

---

## Validation Architecture

`workflow.nyquist_validation` key is absent from `.planning/config.json` — treating as enabled.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit4 + Kotlin coroutines test (project standard) |
| Config file | `app/build.gradle.kts` (existing test config) |
| Quick run command | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.profile.*"` |
| Full suite command | `./gradlew testArm64DebugUnitTest` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| UI-01 | Selection screen shown only when profiles.size >= 2 | Unit (ViewModel) | `./gradlew testArm64DebugUnitTest --tests "*.ProfileSelectionViewModelTest"` | ❌ Wave 0 |
| UI-02 | Session flag resets on profile switch, stays set otherwise | Unit (state logic) | `./gradlew testArm64DebugUnitTest --tests "*.ProfileSelectionViewModelTest"` | ❌ Wave 0 |
| UI-03 | D-pad navigation — manual verification required | Manual | physical remote test | — |
| UI-04 | PIN enabled/disabled state derived from profile.pinEnabled | Unit (ViewModel) | `./gradlew testArm64DebugUnitTest --tests "*.ProfileSelectionViewModelTest"` | ❌ Wave 0 |
| UI-05 | PIN numpad D-pad focusability — manual verification | Manual | physical remote test | — |
| UI-06 | retryAfterSeconds countdown disables numpad | Unit (countdown state logic) | `./gradlew testArm64DebugUnitTest --tests "*.ProfileSelectionViewModelTest"` | ❌ Wave 0 |
| UI-07 | Sidebar switcher hidden when profiles.size == 1 | Unit (visibility logic) | `./gradlew testArm64DebugUnitTest --tests "*.SidebarProfileSwitcherTest"` | ❌ Wave 0 |
| UI-08 | Settings header shows active profile name and avatar | Unit (state observation) | `./gradlew testArm64DebugUnitTest --tests "*.SettingsProfileHeaderTest"` | ❌ Wave 0 |

### Wave 0 Gaps
- [ ] `tests/.../profile/ProfileSelectionViewModelTest.kt` — covers UI-01, UI-02, UI-04, UI-06
- [ ] `tests/.../profile/SidebarProfileSwitcherTest.kt` — covers UI-07 (visibility gating)
- [ ] `tests/.../settings/SettingsProfileHeaderTest.kt` — covers UI-08 (header state)

---

## Security Domain

Phase 3 surfaces PIN entry UI. The PIN itself is hashed server-side (Phase 4 concern); this phase only handles entry and result display. Applicable considerations:

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | Partial | PIN entry routes to `ProfileSyncService.verifyProfilePin()` — server enforces; client just displays result |
| V3 Session Management | Yes | `hasSelectedProfileThisSession` must not persist across intentional logout/app-kill |
| V4 Access Control | Yes | Locked profile must not be accessible without correct PIN; rate limit must be enforced server-side (UI-06) |
| V5 Input Validation | Yes | PIN input filtered to digits only, max length 4 — `value.filter(Char::isDigit).take(4)` pattern from NuvioTV |
| V6 Cryptography | No | PIN hashing is server-side; client never hashes |

### Known Threat Patterns
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| PIN brute force | Elevation of Privilege | Server-enforced `retryAfterSeconds` drives UI-06 countdown; client respects but does not enforce |
| PIN visible in memory longer than needed | Information Disclosure | Use `String` not `CharArray` (acceptable for 4-digit TV PIN; low sensitivity) |
| Session flag bypass (skip selection screen) | Elevation of Privilege | Flag is in-memory only; no API exposed; acceptable |

---

## Sources

### Primary (HIGH confidence)
- NuvioTV `ProfileSelectionScreen.kt` (read directly) — selection layout, ProfileCard, ProfileGrid, ProfilePinOverlay, PIN shake animation, auto-submit, keyCodeToDigit, ProfilePinBoxes
- NuvioTV `ProfileSelectionViewModel.kt` (read directly) — state management, PIN operation flows, profilePinEnabled map
- NuvioTV `ProfileAvatarCircle.kt` (read directly) — avatar composable implementation
- NuvioTV `ModernSidebarBlurPanel.kt` (read directly) — `SidebarProfileItem` composable, `showProfileSelector` gating
- NuvioTV `MainActivity.kt` (read directly) — `hasSelectedProfileThisSession` via `rememberSaveable`, session gating, `onSwitchProfile` reset
- NuvioTV `NuvioNavHost.kt` (read directly) — confirms ProfileSelection route is Management-mode only (not the startup gate)
- Nexio `SidebarNavigation.kt` (read directly) — FocusRequester patterns, 260dp width, animateIntOffsetAsState, existing structure
- Nexio `SettingsDesignSystem.kt` (read directly) — reusable card/row composables, design system primitives
- Nexio `SettingsScreen.kt` (read directly) — FocusRequester patterns, key handling, LazyColumn structure
- Nexio `Screen.kt` (read directly) — sealed class pattern for adding ProfileSelection route
- Nexio `NexioNavHost.kt` (read directly) — startDestination parameter, composable registration pattern
- Nexio `ProfileManager.kt` (read directly) — `activeProfileId: StateFlow<Int>`, `profiles: StateFlow<List<UserProfile>>`, `setActiveProfile()`
- Nexio `UserProfile.kt` (read directly) — `pinEnabled: Boolean`, `isPrimary: Boolean`, `avatarId: String?`
- Nexio `ProfileAvatarColors.kt` (read directly) — 8 predefined hex colors

### Secondary (MEDIUM confidence)
- CONTEXT.md (read directly) — D-01 through D-16 decisions, confirmed locked vs. discretion boundaries

---

## Metadata

**Confidence breakdown:**
- Session gating pattern: HIGH — verified directly in NuvioTV MainActivity, exact code read
- ProfileAvatarCircle: HIGH — verified, portable verbatim
- ProfileCard D-pad focus pattern: HIGH — verified, exact scale/easing values read
- PIN overlay (dots + shake): HIGH — verified and portable from NuvioTV
- PIN numpad grid (D-06): MEDIUM — design is specified but composable must be built new; no direct reference in NuvioTV
- Sidebar inline expansion (D-12): MEDIUM — no NuvioTV reference; AnimatedVisibility approach is standard Compose pattern but untested in this sidebar context
- Settings header: HIGH — SettingsDesignSystem and ProfileAvatarCircle are both well-understood

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 (stable Android TV / Compose ecosystem, no moving parts)
