# NEXIO — OLED Burn-in Hardening (Design)

**Date:** 2026-04-27
**Status:** Approved
**Owner:** john@neerdael.nl

## Goal

Close localized burn-in vectors in NEXIO's player, sidebar, focus rings, loading overlay, and captions, and give users control over screensaver onset. Preserve the protections already in place (5-min screensaver baseline, near-black themes, scoped `keepScreenOn` on the main player, lifecycle hygiene).

## Non-goals

Theme palette overhaul, dark-mode redesign, ExoPlayer changes, new screensaver content modes, accessibility audit beyond the caption-opacity cap.

## What stays as-is (already safe)

- 5-min idle screensaver with Ken-Burns motion (`MainActivity.kt:217`, `IdleScreensaverOverlay.kt:73-79`).
- Near-black theme backgrounds — `#0D0D0D` base, ≤`#242424` surfaces (`tv/ui/theme/ThemeColors.kt:10-21`).
- `FLAG_KEEP_SCREEN_ON` lifecycle for the screensaver overlay (`MainActivity.kt:753-762`).
- Main `PlayerVideoSurface` `keepScreenOn` scoping to `isPlaying || isBuffering` (`PlayerVideoSurface.kt:27-28,132,168-170`).
- Remote-key idle-timer reset (`MainActivity.kt:1155-1167`).
- Transient overlay timeouts (3 s controls, 10 s skip-intro, 5 s display-mode badge).
- Pure-black letterbox bars via ExoPlayer surface.
- No app-level `WAKE_LOCK` declared.

## Changes

### 1. Pause overlay no longer blocks the screensaver (R1)

`PlaybackIdleGateState` drops the `isPausedByUser` exclusion from screensaver eligibility. The pause overlay continues to render unchanged; the screensaver simply layers over it after the configured idle delay. On any remote key press, the screensaver dismisses and the user returns to the existing pause overlay. No auto-dismiss of the overlay itself.

**Files:** `PlaybackIdleGateState.kt`, `MainActivity.kt` (idle gate composition).

### 2. TrailerPlayer: scope `keepScreenOn` AND consume pause (R2)

Tie the `keepScreenOn` flag to `isPlaying || isBuffering` (mirroring `PlayerVideoSurface.kt:27-28,132,168-170`) so it drops the moment the trailer pauses for any reason. Additionally, intercept the remote pause key on the trailer surface and treat it as a no-op — trailers are short ambient content where pause has no meaningful UX value.

**File:** `TrailerPlayer.kt:307-349`.

### 3. Focus rings: motion + remove white (R3)

Add a slow breathing animation to the focus ring: alpha cycles `0.7 ↔ 1.0` over ~3.5 s, infinite easing-in-out. The animation is the load-bearing burn-in mitigation; the color choice is secondary. Remove the white accent from `ThemeColors.kt`. Keep crimson, ocean, amber, and any others. Default accent becomes red (crimson). Existing users on the white theme auto-migrate to red on next launch via a one-time settings migration.

**Files:** `tv/ui/theme/ThemeColors.kt`, focus-ring rendering site, settings migration.

### 4. Sidebar: legacy collapsed-by-default becomes the only mode (R4)

Remove `modernSidebarEnabled`, `modernSidebarBlurPref`, `sidebarCollapsedByDefault`, and any other sidebar-modifying preferences. Delete the `ModernSidebarBlurPanel` code path entirely. Ship a single sidebar: legacy, fully collapsed at rest (0 dp resting width, summons to 216 dp on D-pad-left/Back). The icon-strip-visible mode is removed. Existing users with non-default settings silently snap to this permanent default.

**Files:** `MainActivity.kt:188,203-204,2083-2240+`, `ModernSidebarBlurPanel.kt` (delete), settings screens (remove sidebar section), preference DataStore (remove keys).

### 5. Loading overlay timeouts (R6)

Two ceilings on `LoadingOverlay`:

- **Initial load** (no playback started): 120 s.
- **Mid-playback rebuffer**: 60 s.

On either ceiling, auto-retry the current operation **once**. If the second attempt also hits the ceiling, transition to the existing error screen with Retry / Back. State machine: `Loading → (timeout) → Retrying (silent) → (timeout) → Error`.

**File:** `LoadingOverlay.kt` and the loading state owner.

### 6. Subtitle background opacity cap (R7)

Cap the user-configurable opacity slider at 75 %. Existing users with values >75 % silently clamp to 75 % on first launch after update. Slider UI shows 0–75 % range; remove "Opaque" preset if one exists.

**File:** `PlayerScreen.kt::applySubtitleStyle` and the captions settings screen.

### 7. Configurable screensaver delay (new)

Replace the constant `IDLE_SCREENSAVER_TIMEOUT_MS = 5 * 60_000` (`MainActivity.kt:217`) with a user preference, range 1.0–10.0 minutes, 30-second steps (19 positions). Default 5.0 minutes (preserves current behavior). Setting lives in the existing Screensaver settings section alongside the trailer-screensaver toggle. Label: "Start screensaver after". Wired through the existing `IdleScreensaverController` so changes take effect on next idle cycle without restart.

**Files:** preference DataStore, Screensaver settings screen, `IdleScreensaverController`, `MainActivity.kt:217`.

## Migration summary

| Old state | New state |
|---|---|
| White theme accent | Red (crimson) |
| Modern sidebar enabled | Legacy collapsed |
| Sidebar uncollapsed | Legacy collapsed |
| Subtitle opacity > 75 % | 75 % |
| Screensaver delay (constant 5 min) | 5.0 min in new pref, user-adjustable |

All migrations are silent and one-shot.

## Testing

- Extend `MainActivityIdleScreensaverTest.kt`: pause-overlay-active eligibility (R1), configurable delay honoured by controller (new setting).
- New tests for `TrailerPlayer` `keepScreenOn` scoping under play/pause/buffering transitions and pause-key consume (R2).
- Unit test for loading-overlay state machine: initial-vs-rebuffer ceilings, single auto-retry, error transition (R6).
- Unit test for subtitle-opacity clamp on migration (R7).
- Unit tests for theme-accent migration (white → red) and sidebar-pref purge (R3, R4).
- Manual verification: focus-ring breathing animation visible on Home, Settings, Search; legacy sidebar present on all root routes after upgrade from a build with modern sidebar enabled; screensaver delay slider in Screensaver settings honors new value within one idle cycle.

## Open implementation questions (resolve at plan time)

- Exact composable hosting the focus ring (Compose `Modifier.border` site).
- Whether the trailer pause-key consume happens at `TrailerPlayer` surface or the route-level key dispatcher.
- Whether the loading state machine lives in a ViewModel or in `LoadingOverlay` state holder.
