# Phase 3: Profile UI - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase builds the profile selection screen, PIN entry overlay, sidebar profile switcher, and settings header profile display. All interactions are fully D-pad navigable for Android TV remotes. The entire profile UI is invisible for single-profile households (except the settings header). No profile CRUD logic (Phase 1), no DataStore migration (Phase 2), no Supabase sync (Phase 4), no nexio-web management (Phase 5).

</domain>

<decisions>
## Implementation Decisions

### Profile Selection Screen
- **D-01:** Profile avatars use Supabase photos when available (via `avatarId`). Until Phase 5 delivers photo upload, the fallback is a colored circle (from `avatarColorHex` / ProfileAvatarColors) with the profile name's first initial centered.
- **D-02:** Selection screen appears exactly once per session when 2+ profiles exist, never for single-profile users (UI-01, UI-02).
- **D-03:** D-pad focus indicator uses scale-up (~1.15x) plus a colored border glow on the focused avatar. Clear at 10-foot viewing distance.
- **D-04:** Selection screen is pick-only — no "Add Profile" button, no "Manage Profiles" mode. Profile creation/editing/deletion is exclusively in Settings.
- **D-05:** Layout is a horizontal row of profile avatars with "Who's watching?" heading.

### PIN Entry
- **D-06:** PIN numpad uses a 3x4 phone-style grid layout: 1-2-3 / 4-5-6 / 7-8-9 / clear-0-confirm. All cells are D-pad focusable.
- **D-07:** PIN dots (4 filled/unfilled circles) appear above the numpad showing entry progress.
- **D-08:** Wrong PIN: dots shake horizontally with brief red "Wrong PIN" text below. No modal dialogs.
- **D-09:** Rate limit exceeded: dots grayed out, countdown text "Try again in Xs" shown. Numpad disabled until timer expires. Server-enforced `retryAfterSeconds` drives the countdown (UI-06).
- **D-10:** Locked profiles show a small lock icon badge on the bottom-right of the avatar circle on the selection screen. PIN entry screen appears after selecting a locked profile.

### Sidebar Profile Switcher
- **D-11:** Profile switcher sits at the top of the sidebar, above navigation items (Home, Search, Library, etc.). Shows active profile avatar + name with an expand arrow.
- **D-12:** Activating the switcher expands other profiles inline below the active profile, pushing nav items down. D-pad down navigates through profiles. Select to switch, back to collapse.
- **D-13:** Selecting a different profile triggers playback stop + return to home (per Phase 2, D-04), then switches the active profile.
- **D-14:** Sidebar profile switcher is hidden when only 1 profile exists. Consistent with selection screen visibility rule.

### Settings Header
- **D-15:** Active profile displayed at the top of the settings screen: small avatar circle + profile name + "Default" badge for Profile 1.
- **D-16:** Settings header profile display is always visible, even for single-profile users. Provides identity context and prepares the UI for multi-profile.

### Claude's Discretion
- Animation durations and easing curves for profile selection focus, sidebar expand/collapse, and PIN shake
- Exact avatar circle sizes (selection screen vs sidebar vs settings header)
- Color of the focus border glow (accent color or avatar color)
- PIN entry screen background treatment (dimmed overlay vs full screen)
- Lock icon badge size and styling
- Keyboard/remote button shortcuts (e.g., whether number keys on a full keyboard can directly enter PIN digits)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### NuvioTV Reference Implementation (UI source)
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/profile/ProfileSelectionScreen.kt` — Profile selection screen layout, avatar presentation, D-pad navigation
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/profile/ProfileSelectionViewModel.kt` — Selection screen state management, session gating logic
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt` — Profile selection route integration, startDestination gating
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/MainActivity.kt` — Sidebar profile switcher integration

### Nexio Existing Code (integration points)
- `app/src/main/java/com/nexio/tv/ui/components/SidebarNavigation.kt` — Current sidebar with FocusRequester, SidebarItem model, 260dp width, animation patterns
- `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt` — Settings categories, D-pad key handling, FocusRequester patterns
- `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsDesignSystem.kt` — Reusable settings row/card composables
- `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt` — Sealed class for navigation routes (add ProfileSelection route here)
- `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt` — Compose Navigation host, startDestination gating point
- `app/src/main/java/com/nexio/tv/MainActivity.kt` — Sidebar items, LocalSidebarExpanded CompositionLocal
- `app/src/main/java/com/nexio/tv/domain/model/UserProfile.kt` — UserProfile data class with id, name, avatarColorHex, avatarId, pinEnabled
- `app/src/main/java/com/nexio/tv/domain/model/ProfileAvatarColors.kt` — 8 predefined avatar hex colors
- `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt` — activeProfileId StateFlow, switchProfile(), profiles list

### Phase Dependencies
- `.planning/phases/01-foundation/01-CONTEXT.md` — ProfileManager, ProfileDataStoreFactory, UserProfile model decisions
- `.planning/phases/02-per-profile-auth-and-settings/02-CONTEXT.md` — D-04 (stop playback on switch), D-06 (sidebar switching), D-07 (shared settings hidden for non-default)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `SidebarNavigation.kt` — FocusRequester + onFocusChanged patterns, animateIntOffsetAsState for slide animation, 260dp sidebar width
- `SettingsDesignSystem.kt` — Reusable card/row composables with focus states, can extend for profile header
- `ProfileAvatarColors.kt` — 8 hex colors for avatar circle backgrounds
- `ProfileManager` — `activeProfileId: StateFlow<Int>`, `profiles: StateFlow<List<UserProfile>>`, `switchProfile(id)` method
- `NexioColors.BackgroundElevated` — Sidebar background color, reuse for selection screen

### Established Patterns
- D-pad focus: `FocusRequester` + `focusRequester()` + `onFocusChanged` modifier chain used across all screens
- Key handling: `onKeyEvent` / `onPreviewKeyEvent` with `Key` constants for D-pad interception
- Navigation: `Screen` sealed class + `NavHost` composable routes
- Animation: `animateIntOffsetAsState`, `animateColorAsState`, `tween` easing used in sidebar
- State: `hiltViewModel()` + `collectAsStateWithLifecycle()` pattern in all screens
- Sidebar items defined in `MainActivity.kt` with Lottie icon resources

### Integration Points
- `Screen.kt` — Add `ProfileSelection` route
- `NexioNavHost.kt` — Gate `startDestination` based on profile count, add profile selection composable
- `MainActivity.kt` — Add profile switcher element to sidebar above nav items
- `SidebarNavigation.kt` — Extend with profile section at top
- `SettingsScreen.kt` — Add profile header at top of settings content

</code_context>

<specifics>
## Specific Ideas

- Port NuvioTV's ProfileSelectionScreen as the starting point — adapt to Nexio's design system
- Profile selection screen gating: check `profileManager.profiles.size >= 2` at navigation level, not inside the screen
- Sidebar profile switcher should use the same avatar composable as the selection screen (shared component)
- PIN entry should auto-submit when 4th digit is entered — no need to press confirm button manually
- Settings header profile display reuses SettingsDesignSystem row pattern with avatar + name + badge

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 03-profile-ui*
*Context gathered: 2026-04-14*
