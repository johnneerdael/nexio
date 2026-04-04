# Change: add a universal-streamer no-addon default

## Why

NEXIO's no-addon experience currently collapses into a dead-end playback/search path even though a zero-addon setup could still provide real value as a legal universal launcher for installed official streaming apps. A no-addon default mode would also strengthen future Play Store positioning by giving the app a fully legal, useful baseline experience before users ever install an addon.

## What Changes

- Detect when the user has zero installed addons and enable a universal-streamer mode.
- On detail-page play actions in that mode, resolve only installed supported official streaming apps that expose a usable in-app search entry point instead of routing into addon-based stream playback.
- Show a clean chooser when multiple supported apps are installed.
- Show a clear guidance dialog when no supported installed app is available.
- Disable this mode completely as soon as the user has one or more installed addons.
- Document Android TV searchable integration for NEXIO itself as a future second phase, separate from the current provider handoff work.

## Impact

- Affected app: `app`
- Affected surfaces: detail-page play / episode-play entry points
- Affected policy posture: improves the no-addon legal default experience for future Play positioning
