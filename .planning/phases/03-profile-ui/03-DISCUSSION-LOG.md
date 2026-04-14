# Phase 3: Profile UI - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-14
**Phase:** 03-profile-ui
**Areas discussed:** Profile selection screen, PIN entry UX, Sidebar profile switcher, Settings header display

---

## Profile Selection Screen

### Avatar Presentation

| Option | Description | Selected |
|--------|-------------|----------|
| Colored circles with initial | Large circle filled with avatarColorHex, name initial centered. Zero asset dependencies. | |
| Predefined avatar icons | Bundled avatar images (animals, characters). Richer but requires shipping assets. | |
| Supabase photo avatars | Load profile photos from Supabase Storage via avatarId. Richest visual. | ✓ |

**User's choice:** Supabase photo avatars
**Notes:** Fallback needed until Phase 5 delivers photo upload.

### Avatar Fallback

| Option | Description | Selected |
|--------|-------------|----------|
| Colored circle with initial | avatarColorHex circle with name initial. Works offline. | ✓ |
| Colored circle, no initial | Plain colored circle. Simpler but less distinguishable. | |

**User's choice:** Colored circle with initial
**Notes:** Photo overlays the fallback when available in Phase 5.

### D-pad Focus Style

| Option | Description | Selected |
|--------|-------------|----------|
| Scale up + border glow | Focused avatar scales ~1.15x with colored border glow. Netflix/Disney+ pattern. | ✓ |
| Border highlight only | White or accent border, no scale change. Simpler animation. | |
| You decide | Let Claude pick based on existing focus patterns. | |

**User's choice:** Scale up + border glow
**Notes:** None.

### Profile Management Access

| Option | Description | Selected |
|--------|-------------|----------|
| Settings only | Selection screen is pick-only. CRUD in Settings only. | ✓ |
| Add profile button on selection screen | '+' button after profiles for quick add. | |
| Full management on selection screen | Netflix-style manage mode toggle. | |

**User's choice:** Settings only
**Notes:** Keeps selection screen simple and focused.

---

## PIN Entry UX

### Numpad Layout

| Option | Description | Selected |
|--------|-------------|----------|
| 3x4 grid | Standard phone-style: 1-2-3 / 4-5-6 / 7-8-9 / clear-0-confirm. | ✓ |
| Horizontal digit strip | Single row 0-9 with left/right navigation. | |
| You decide | Let Claude choose based on existing D-pad patterns. | |

**User's choice:** 3x4 grid
**Notes:** Familiar layout, minimal D-pad moves.

### Error & Rate Limit Feedback

| Option | Description | Selected |
|--------|-------------|----------|
| Inline shake + text | Wrong PIN: dots shake, red text. Rate limit: grayed dots, countdown. No dialogs. | ✓ |
| Dialog popup | Modal dialog for errors. More prominent but interrupts flow. | |
| You decide | Let Claude pick error feedback pattern. | |

**User's choice:** Inline shake + text
**Notes:** None.

### Locked Profile Indicator

| Option | Description | Selected |
|--------|-------------|----------|
| Small lock icon on avatar | Lock icon badge on bottom-right of avatar circle. | ✓ |
| No visual indicator | Lock state is a surprise after selection. | |
| Lock icon replaces initial | Lock icon shown instead of name initial. | |

**User's choice:** Small lock icon on avatar
**Notes:** Subtle but clear. PIN entry triggers after selecting.

---

## Sidebar Profile Switcher

### Placement

| Option | Description | Selected |
|--------|-------------|----------|
| Top of sidebar | Active profile above nav items (Home, Search, Library). | ✓ |
| Bottom of sidebar | Below nav items, near Settings. | |
| Dedicated nav item | 'Switch Profile' menu item among nav items. | |

**User's choice:** Top of sidebar
**Notes:** None.

### Expand Behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Inline expand below | Other profiles slide in below active, pushing nav items down. | ✓ |
| Overlay popup | Floating popup over sidebar content. | |
| Full-screen overlay | Opens full profile selection screen. | |

**User's choice:** Inline expand below
**Notes:** D-pad down navigates through profiles. Back to collapse.

### Single-Profile Visibility

| Option | Description | Selected |
|--------|-------------|----------|
| Hidden when only 1 profile | No profile element in sidebar for single-profile users. | ✓ |
| Always show active profile | Show avatar + name even for single-profile, but no expand arrow. | |

**User's choice:** Hidden when only 1 profile
**Notes:** Consistent with selection screen rule (UI-01).

---

## Settings Header Display

### Header Content

| Option | Description | Selected |
|--------|-------------|----------|
| Avatar + name + badge | Small avatar, name, 'Default' badge for Profile 1. Always visible. | ✓ |
| Avatar + name only | Just avatar and name, no badge. | |
| You decide | Let Claude choose based on SettingsDesignSystem. | |

**User's choice:** Avatar + name + badge
**Notes:** Makes it clear which profile's settings you're editing.

### Single-Profile Visibility

| Option | Description | Selected |
|--------|-------------|----------|
| Always show in settings | Even single-profile users see profile name in settings header. | ✓ |
| Hidden for single-profile | Profile UI invisible until 2+ profiles. | |

**User's choice:** Always show in settings
**Notes:** Provides identity context and prepares UI for multi-profile.

---

## Claude's Discretion

- Animation durations and easing curves
- Exact avatar circle sizes per context
- Focus border glow color
- PIN entry screen background treatment
- Lock icon badge size and styling
- Keyboard shortcut mappings

## Deferred Ideas

None — discussion stayed within phase scope.
