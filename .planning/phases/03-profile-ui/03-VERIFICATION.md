---
phase: 03-profile-ui
verified: 2026-04-14T00:00:00Z
status: human_needed
score: 4/5 must-haves verified
overrides_applied: 0
deferred:
  - truth: "User can set an optional PIN on a profile; a locked profile requires correct PIN entry before switching into it, with server-enforced rate limiting displayed when exceeded"
    addressed_in: "Phase 4"
    evidence: "Phase 4 goal: 'Profile metadata (name, avatar, PIN state) syncs to Supabase'. ProfileSelectionViewModel.verifyPin() stub comment: 'Phase 4 will replace this with: profileSyncService.verifyProfilePin(profileId, pin)'. The full PIN entry UI (numpad, shake, rate-limit countdown, auto-submit, dismiss on success) is built and wired; only the server verification call is deferred."
human_verification:
  - test: "Profile selection D-pad navigation: Launch app with 2+ profiles, navigate left/right with TV remote"
    expected: "Focus moves between avatars with 1.15x scale animation and FocusRing border glow; selected avatar stays highlighted; DPAD_CENTER selects the profile"
    why_human: "D-pad focus traversal, animation quality, and 10-foot viewing clarity cannot be verified programmatically"
  - test: "Lock badge rendering: Launch with a profile that has pinEnabled=true"
    expected: "Lock icon badge appears at bottom-right of the avatar circle on the selection screen"
    why_human: "Visual rendering of the 20dp CircleShape badge with Lock icon requires on-device inspection"
  - test: "PIN overlay D-pad: Select a PIN-locked profile, navigate the 3x4 numpad with TV remote"
    expected: "Focus moves between all 12 cells; DPAD_CENTER activates the focused cell; digits appear as filled dots above numpad"
    why_human: "Numpad D-pad focus grid traversal requires physical remote to verify cell-to-cell navigation"
  - test: "PIN wrong entry: Enter any 4 digits on PIN overlay"
    expected: "After 500ms, dots shake horizontally, 'Wrong PIN' text appears in red, dots reset after 600ms"
    why_human: "Shake animation quality and timing require visual inspection"
  - test: "Sidebar profile switcher: Open sidebar with 2+ profiles, select the profile switcher row"
    expected: "Active profile avatar (40dp) + name + expand arrow visible at top of sidebar; pressing D-pad Center expands the list showing other profiles (32dp avatars); pressing Back collapses without dismissing sidebar"
    why_human: "Sidebar expand/collapse animation, BackHandler LIFO behavior, and focus flow require physical device"
  - test: "Sidebar hidden for single-profile: Open sidebar with exactly 1 profile"
    expected: "No profile switcher section appears at the top of the sidebar; nav items start immediately"
    why_human: "Visual layout verification requires on-device"
  - test: "Settings header: Open Settings screen"
    expected: "Active profile avatar (40dp) + name appears at top of settings, above the category rail; Profile 1 shows 'Default' badge with accent-color background at 20% alpha"
    why_human: "Visual positioning above rail and badge styling require on-device inspection"
---

# Phase 3: Profile UI Verification Report

**Phase Goal:** Users can select, switch, and manage profiles through a fully D-pad navigable interface that stays invisible for single-profile households
**Verified:** 2026-04-14
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Profile selection screen appears exactly once per session when 2+ profiles exist, never for single-profile users | VERIFIED | `MainActivity.kt:481-491`: `remember { mutableStateOf(false) }` flag `hasSelectedProfileThisSession`, `shouldShowProfileSelection = !hasSelectedProfileThisSession && profiles.size > 1`. `return@Surface` at line 491 short-circuits content. Single-profile: `profiles.size > 1` is false, screen never shown. `remember` (not `rememberSaveable`) gives once-per-process semantics per CONTEXT.md decision. |
| 2 | All profile selection interactions (navigate, choose, PIN entry) completable using only D-pad | HUMAN NEEDED | Code structure is correct: `FocusRequester` per avatar card, `onPreviewKeyEvent` on Row for DPAD_CENTER/Enter, 12-cell `ProfilePinNumpad` with individual `FocusRequester` per cell, `focusable(enabled=enabled)` for rate-limit disable. Physical remote test required to confirm traversal behavior. |
| 3 | User can set optional PIN; locked profile requires correct PIN entry before switching; server-enforced rate limiting displayed when exceeded | DEFERRED (see deferred section) | PIN UI fully built: `ProfilePinOverlay`, `ProfilePinBoxes` (shake, error, disabled states), `ProfilePinNumpad` (3x4 grid). Lock badge wired in `ProfileCard`. Server verification deferred to Phase 4 — stub always returns `unlocked=false`. |
| 4 | User can switch to any profile from sidebar menu without returning to home screen | VERIFIED | `ModernSidebarBlurPanel.kt`: `ProfileSwitcherSection` at lines 318+, `ProfileSwitcherRow` at line 420. `if (profiles.size > 1)` gating at line 168. `onSwitchProfile` passed through `ModernSidebarScaffold` to `ModernSidebarBlurPanel`. `MainActivity.kt:939-944`: `profiles = profiles`, `activeProfileId = profileManager.activeProfileId.collectAsState().value`, `onSwitchProfile` lambda sets `hasSelectedProfileThisSession = false` then calls `profileManager.setActiveProfile(profileId)` via `lifecycleScope.launch`. Per D-13: session flag reset causes selection screen to show, which is spec-correct behavior. |
| 5 | Active profile name and avatar visible in settings header at all times | VERIFIED | `SettingsScreen.kt:293-294`: `activeProfile?.let { profile -> ProfileHeaderRow(profile = profile) }`. `ProfileHeaderRow` composable at line 460: `ProfileAvatarCircle(size = 40.dp)` + name (`titleMedium`) + "Default" badge when `profile.isPrimary` with `NexioColors.FocusRing.copy(alpha = 0.2f)` background. `SettingsProfileViewModel.activeProfile` uses `combine(profileManager.profiles, profileManager.activeProfileId)`. No `profiles.size` gate — always visible per D-16. |

**Score:** 4/5 truths verified (5th deferred to Phase 4, 2nd needs human)

### Deferred Items

Items not yet met but explicitly addressed in later milestone phases.

| # | Item | Addressed In | Evidence |
|---|------|-------------|----------|
| 1 | PIN server verification — correct PIN unlocks the profile | Phase 4 | Phase 4 SYNC-01: "Profile metadata (name, avatar, PIN state) syncs to Supabase". `ProfileSelectionViewModel.verifyPin()` stub comment (line 50): "Phase 4 will replace this with: profileSyncService.verifyProfilePin(profileId, pin)". All PIN UI is built and wired; stub returns `unlocked=false` by design for testability. |

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|---------|--------|---------|
| `app/src/main/java/com/nexio/tv/ui/components/ProfileAvatarCircle.kt` | Shared avatar composable with photo/initial fallback | VERIFIED | Exists, 73 lines. `runCatching { Color(android.graphics.Color.parseColor(colorHex)) }`, `AsyncImage` with `ContentScale.Crop`, `CircleShape`, initial letter fallback. |
| `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt` | Full-screen profile picker with D-pad navigation | VERIFIED | 265 lines. "Who's watching?", `animateFloatAsState(tween(210, CubicBezierEasing(0.22f,1f,0.36f,1f)))`, `1f + (0.15f * focusProgress)` scale, `Icons.Default.Lock` badge, `repeat(2) { withFrameNanos {} }` initial focus, no "Add Profile"/"Manage" text. |
| `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModel.kt` | Profile list state, PIN verification stub | VERIFIED | 121 lines. `@HiltViewModel`, `profiles: StateFlow`, `activeProfileId: StateFlow`, `profilePinEnabled: StateFlow<Map<Int,Boolean>>`, `verifyPin()`, `PinVerificationState`, `startRateLimitCountdown()`, `consumePinError()`, `resetPinState()`. |
| `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinBoxes.kt` | 4-dot PIN progress indicator with shake | VERIFIED | 70 lines. `graphicsLayer { translationX = shakeOffset }`, `size(16.dp).clip(CircleShape)`, error state with `NexioColors.Error`, disabled state with muted colors. |
| `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinNumpad.kt` | 3x4 D-pad focusable numpad grid | VERIFIED | 234 lines. 12 `FocusRequester` instances, `size(width=72.dp, height=64.dp)`, `RoundedCornerShape(12.dp)`, `focusable(enabled=enabled)`, `keyCodeToDigit()` helper covering KEYCODE_0–9 and KEYCODE_NUMPAD_0–9, DEL/CLEAR hardware key support, DPAD_CENTER/Enter per-cell. |
| `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinOverlay.kt` | Full-screen PIN entry overlay | VERIFIED | 153 lines. `BackHandler(enabled=true)`, `NexioColors.Background.copy(alpha=0.95f)`, "Enter PIN" heading, "Wrong PIN" error, "Try again in Xs" countdown, `Animatable(0f)` shake, `listOf(-22f,18f,-14f,10f,-6f,0f)` offsets, auto-submit `LaunchedEffect(pin,isVerifying)` when `pin.length==4`. |
| `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt` | ProfileSelection route | VERIFIED | `data object ProfileSelection : Screen("profile_selection")` at line 10. |
| `app/src/main/java/com/nexio/tv/ModernSidebarBlurPanel.kt` | ProfileSwitcherSection composable in sidebar | VERIFIED | `private fun ProfileSwitcherSection(` at line 318, `private fun ProfileSwitcherRow(` at line 420, `if (profiles.size > 1)` gating at line 168, `AnimatedVisibility` with `expandVertically`/`shrinkVertically`, `animateFloatAsState` arrow rotation 0f→180f, `BackHandler(enabled=isExpanded)` for LIFO collapse, `ProfileAvatarCircle` at 40dp (active row) and 32dp (expanded list). |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt` | ProfileHeaderRow at top of settings | VERIFIED | `private fun ProfileHeaderRow(` at line 460, `ProfileAvatarCircle(size=40.dp)`, "Default" badge at line 485, `NexioColors.FocusRing.copy(alpha=0.2f)` at line 491, `activeProfile?.let { ProfileHeaderRow(...) }` at line 293. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `MainActivity.kt` | `ProfileSelectionScreen` | `hasSelectedProfileThisSession` gating before content | WIRED | Lines 481-491: `remember { mutableStateOf(false) }`, `shouldShowProfileSelection = !hasSelectedProfileThisSession && profiles.size > 1`, `ProfileSelectionScreen(onProfileSelected = { hasSelectedProfileThisSession = true })`, `return@Surface`. |
| `ProfileSelectionScreen.kt` | `ProfileSelectionViewModel` | `hiltViewModel()` + `collectAsState` | WIRED | Line 61: `viewModel: ProfileSelectionViewModel = hiltViewModel()`. Lines 62-65: all four StateFlows collected. |
| `ProfileSelectionViewModel.kt` | `ProfileManager` | `profiles` StateFlow + `setActiveProfile()` | WIRED | Line 25: `val profiles = profileManager.profiles`. Line 27: `val activeProfileId = profileManager.activeProfileId`. Lines 40, 57: `profileManager.setActiveProfile(profileId)`. |
| `ProfileSelectionScreen.kt` | `ProfilePinOverlay` | `activePinOverlayProfile` state triggers overlay | WIRED | Lines 68, 119, 141: `activePinOverlayProfile = profile` on PIN-locked click. Lines 160-174: `activePinOverlayProfile?.let { profile -> ProfilePinOverlay(...) }`. |
| `ProfilePinOverlay.kt` | `ProfilePinNumpad` + `ProfilePinBoxes` | Composition | WIRED | `ProfilePinBoxes` at line 113, `ProfilePinNumpad` at line 139. Numpad `onDigit` updates `pin`, overlay passes `pin.length` and `shakeOffset.value` to boxes. |
| `ProfileSelectionViewModel.kt` | `ProfileManager` | `verifyPin` triggers `setActiveProfile` on success | WIRED (stub) | Line 57: `profileManager.setActiveProfile(profileId)` inside `if (result.unlocked)` branch. Stub always returns `unlocked=false` — Phase 4 enables the success path. |
| `ModernSidebarBlurPanel.kt` | `ProfileManager` | `profiles` StateFlow drives switcher visibility and content | WIRED | `profiles: List<UserProfile>` parameter flows from MainActivity which collects `profileManager.profiles`. |
| `ModernSidebarBlurPanel.kt` | `ProfileAvatarCircle` | Renders avatar for each profile in switcher | WIRED | Import at line 66: `import com.nexio.tv.ui.components.ProfileAvatarCircle`. Used at lines 376 (40dp) and 457 (32dp). |
| `SettingsScreen.kt` | `ProfileManager` | `activeProfile` drives header display | WIRED | `SettingsProfileViewModel.activeProfile` StateFlow uses `combine(profileManager.profiles, profileManager.activeProfileId)` at line 79. Collected at line 200, rendered at line 293. |
| `MainActivity.kt` | `ModernSidebarBlurPanel` | Passes `profiles`, `activeProfileId`, `onSwitchProfile` | WIRED | Lines 939-944 in `ModernSidebarScaffold` call: `profiles = profiles`, `activeProfileId = profileManager.activeProfileId.collectAsState().value`, `onSwitchProfile` lambda with `hasSelectedProfileThisSession = false` + `profileManager.setActiveProfile()`. Passed through at line 2210-2211. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|--------------|--------|--------------------|--------|
| `ProfileSelectionScreen.kt` | `profiles` | `profileManager.profiles` StateFlow (Phase 1) | Yes — live from ProfileManager | FLOWING |
| `ProfileSelectionScreen.kt` | `profilePinEnabled` | Derived from `profileManager.profiles` via `.map { list -> list.associate { it.id to it.pinEnabled } }` | Yes — real profile data | FLOWING |
| `ProfilePinOverlay.kt` | `pin` / `pinState` | Local state + `ProfileSelectionViewModel.pinState` | Verification stub (intentional) — always wrong PIN | STUB (documented, Phase 4) |
| `ModernSidebarBlurPanel.kt` | `profiles` / `activeProfileId` | Passed from MainActivity which collects `profileManager.profiles` | Yes — live from ProfileManager | FLOWING |
| `SettingsScreen.kt` | `activeProfile` | `combine(profileManager.profiles, profileManager.activeProfileId)` in ViewModel | Yes — reactive combine from ProfileManager | FLOWING |

### Behavioral Spot-Checks

Step 7b: SKIPPED — app requires Android TV device to run; no runnable entry points testable without device/emulator.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|---------|
| UI-01 | 03-01 | User sees profile selection screen only when 2+ profiles exist | SATISFIED | `shouldShowProfileSelection = !hasSelectedProfileThisSession && profiles.size > 1` in MainActivity |
| UI-02 | 03-01 | Profile selection is session-scoped (shown once per session) | SATISFIED | `remember { mutableStateOf(false) }` — resets on process death, persists within process lifetime |
| UI-03 | 03-01 | Profile selection screen is fully D-pad navigable | NEEDS HUMAN | Code structure correct (FocusRequester chain, onPreviewKeyEvent, 2-frame settle); physical remote required |
| UI-04 | 03-02 | User can optionally set a PIN (server-side hash) | SATISFIED (UI) | `profilePinEnabled` derived from `profile.pinEnabled`; lock badge renders; PIN overlay wired. Server-side hash is Phase 4 concern. |
| UI-05 | 03-02 | PIN entry uses D-pad-friendly on-screen numpad | NEEDS HUMAN | `ProfilePinNumpad` 3x4 grid built with 12 FocusRequesters and per-cell onPreviewKeyEvent; physical remote required to verify navigability |
| UI-06 | 03-02 | PIN verification respects server rate limiting (retryAfterSeconds) | SATISFIED (UI) | `startRateLimitCountdown()` in ViewModel, `retryAfterSeconds > 0` disables numpad via `focusable(enabled=enabled)`, "Try again in Xs" text rendered. Server enforcement is Phase 4. |
| UI-07 | 03-03 | User can switch profiles from sidebar menu | SATISFIED | `ProfileSwitcherSection` wired with `onSwitchProfile` → `profileManager.setActiveProfile()` + session flag reset |
| UI-08 | 03-03 | Active profile name/avatar visible in settings header | SATISFIED | `ProfileHeaderRow` at top of SettingsScreen, always visible, `activeProfile?.let { ProfileHeaderRow(it) }` |

All 8 Phase 3 requirement IDs (UI-01 through UI-08) are accounted for. No orphaned requirements.

### Anti-Patterns Found

| File | Pattern | Severity | Impact |
|------|---------|----------|--------|
| `ProfileSelectionViewModel.kt:53` | `val result = PinVerifyResult(unlocked = false, retryAfterSeconds = 0)` — stub always returns wrong PIN | Info | Intentional Phase 3 design decision. Documented in SUMMARY, plan comments, and phase boundary. Phase 4 replaces with `profileSyncService.verifyProfilePin()`. Not a blocker — full UI is testable. |

No unintentional stubs, TODOs, placeholder returns, or empty implementations found in Phase 3 files.

### Human Verification Required

#### 1. D-pad Profile Selection Navigation

**Test:** On Android TV with a physical remote, launch the app with 2+ profiles configured. Use D-pad left/right to navigate between profile avatars.
**Expected:** Focus moves between avatars smoothly; the focused avatar scales up to ~1.15x with a colored border ring; unfocused avatars have no border and normal scale; pressing DPAD_CENTER on a non-locked profile dismisses the screen and enters the app.
**Why human:** D-pad traversal correctness and animation quality at 10-foot viewing distance require physical device.

#### 2. Lock Badge Visual

**Test:** Configure a profile with `pinEnabled=true`. Open the selection screen.
**Expected:** A small circular badge with a lock icon appears at the bottom-right corner of the avatar circle for that profile.
**Why human:** Visual badge rendering and positioning require on-device inspection.

#### 3. PIN Numpad D-pad Navigation

**Test:** Select a PIN-locked profile. Use the TV remote to navigate the 3x4 PIN numpad.
**Expected:** Focus moves between all 12 cells (1-2-3, 4-5-6, 7-8-9, Clear-0-Confirm); each cell highlights with FocusRing border when focused; pressing DPAD_CENTER enters the digit; filled dots appear above the numpad as digits are entered.
**Why human:** Numpad focus grid traversal and cell activation require physical remote.

#### 4. Wrong PIN Shake Animation

**Test:** Enter any 4 digits on the PIN overlay (verification stub always rejects).
**Expected:** After ~500ms delay, the 4 dots shake horizontally, "Wrong PIN" text appears in red below the dots, dots reset to empty after ~600ms.
**Why human:** Shake animation quality, timing, and visual correctness require on-device observation.

#### 5. Sidebar Profile Switcher Expand/Collapse

**Test:** Open the sidebar with 2+ profiles. Navigate to the profile switcher row at the top. Press DPAD_CENTER to expand. Press D-pad Down to navigate to another profile. Press Back.
**Expected:** Expand: other profiles appear with AnimatedVisibility (slide down), arrow rotates 180°. Collapse on Back: other profiles hide, arrow returns to 0°, sidebar remains open (BackHandler LIFO behavior).
**Why human:** Animation, LIFO BackHandler behavior, and focus flow require physical device.

#### 6. Single-Profile Sidebar Visibility

**Test:** Use the app with exactly 1 profile. Open the sidebar.
**Expected:** No profile switcher section at the top; sidebar shows only navigation items (Home, Search, Library, Settings, etc.).
**Why human:** Visual layout verification requires on-device.

#### 7. Settings Header Profile Display

**Test:** Navigate to the Settings screen.
**Expected:** Profile avatar (40dp) + profile name appears at the very top, above the settings category rail, spanning the full width. Profile 1 shows a "Default" badge with accent-color text on a low-opacity accent background. Header visible regardless of profile count.
**Why human:** Visual positioning above the rail and badge styling require on-device inspection.

### Gaps Summary

No automated gaps found. All Phase 3 artifacts exist, are substantive (not stubs), and are wired into their consumers. Data flows from `ProfileManager` through all rendering paths.

The only non-verified item is the PIN server verification path — this is an intentional documented stub per phase boundary. Phase 4 (SYNC-01) explicitly covers it. This has been moved to `deferred` and does not affect the phase status.

Status is `human_needed` because D-pad navigation quality, animation rendering, and visual layout correctness for a TV remote interface cannot be verified programmatically.

---

_Verified: 2026-04-14_
_Verifier: Claude (gsd-verifier)_
