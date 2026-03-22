# Playback Interface: Runtime Control Model

## Audience
This guide is for users who want precise control of playback behavior and want to understand why Back and panel behavior work the way they do.

## What this page covers
- Player back-stack hierarchy
- Overlay and panel priority
- Lifecycle pause and frame-rate mode handling
- Reliable recovery patterns when playback fails

## Source of truth
Player behavior is implemented in:
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`

## Player state machine at a glance
The player handles multiple layers at once:
- Core playback state
- Controls visibility
- Episode and source panels
- Subtitle delay and speed dialogs
- Pause overlay and next-episode card
- Error state

Back navigation is intentionally hierarchical so one press closes the topmost UI layer before leaving playback.

## Back behavior priority
Back is resolved in this order:
1. Error exit path
2. Pause overlay
3. More dialog
4. Subtitle delay overlay
5. Sources panel
6. Episodes panel and nested episode streams
7. Skip-intro card
8. Next-episode card
9. Controls visibility
10. Exit player

This is why Back may hide UI first instead of immediately leaving playback.

## Lifecycle behavior
When app lifecycle moves to background, playback is paused through lifecycle handling.
On resume, Nexio avoids forced auto-resume and lets the user decide.

This protects user intent and reduces accidental resume events.

## Frame-rate matching lifecycle
Player screen coordinates display mode management with the host activity:
- Marks main player session active on entry
- Restores original display mode on exit depending on frame-rate mode
- Cleans up display listeners when needed

This design minimizes display-mode residue after playback exits.

## Practical operation runbook

### 1. Enter playback
Start from Detail and choose a stream.

**Expected result:** Player loads and can receive focus.

### 2. Validate control surface
Press OK to show controls.

**Expected result:** Play or Pause control becomes focus target.

### 3. Test panel stack
Open one panel then press Back.

**Expected result:** Panel closes first, playback remains active.

### 4. Exit correctly
Hide controls, then press Back to leave player.

**Expected result:** Player releases and returns to previous navigation target.

## Troubleshooting

### Symptom
Back does not exit player immediately.

### Likely cause
A higher-priority overlay or panel is currently open.

### Recovery
1. Press Back repeatedly and watch each overlay close.
2. Confirm controls are hidden.
3. Press Back again to exit player.

### Verification
Exit path is deterministic and returns without frozen overlays.

## Note on subtitle and audio issues
If a stream has no usable audio or subtitle track, use source switching or episode-stream fallback rather than repeatedly toggling the same broken track.

## Next page
Continue with [Settings & Account](./settings.md) for playback-related configuration and integration-level controls.
