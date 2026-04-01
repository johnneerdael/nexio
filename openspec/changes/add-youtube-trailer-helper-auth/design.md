## Context
Local validation proved that age-restricted YouTube trailer resolution can succeed with `yt-dlp` when three conditions are true: authenticated YouTube state is present, a JavaScript runtime is available for YouTube challenge solving, and the EJS challenge solver distribution is enabled. A local PoC then proved a more TV-native auth model: a SmartTube-style device-code login can mint a bearer token which, when passed into a narrowly patched `yt-dlp`, resolves direct playback URLs for an age-restricted trailer without cookies.

The user chose a same-device architecture: the helper must be bundled into Nexio and run on the Android TV device. The user also chose a strict product rule: if Nexio cannot resolve an internally playable trailer, trailer UI should not be shown at all. That rule removes trailer-specific external fallback from the surfaced UX contract. The user further chose that once auth is present, all YouTube trailers should use the helper path rather than mixing old and new YouTube resolvers.

## Goals / Non-Goals
- Goals:
  - Provide a one-time YouTube trailer sign-in under `Settings > Integration > YouTube Trailer Login`.
  - Persist an app-owned YouTube trailer auth session using refresh/access tokens.
  - Bundle and invoke a same-device trailer helper using `yt-dlp` plus `node`.
  - Keep signed-out behavior on the current internal resolver only.
  - Show trailer UI only when Nexio has a playable internal trailer result.
  - Add a `Play Trailer` action to home poster long-press dialogs when trailer availability is positive.
- Non-Goals:
  - Password-based Google login automation or browser-cookie export.
  - General-purpose YouTube browsing or account management in Nexio.
  - Keeping trailer-only external app launch as a surfaced trailer UX.
  - Replacing the existing non-YouTube internal trailer playback path.

## Decisions
- Decision: Keep signed-out behavior on the existing internal resolver only.
  - Rationale: this preserves current behavior and avoids making helper health a prerequisite for basic public trailer playback.

- Decision: Gate helper usage behind an app-owned YouTube trailer login using device-code auth.
  - Rationale: local validation proved bearer auth from device-code login works with a narrow yt-dlp fork, and QR/device-code is the right UX for Android TV.

- Decision: Signed-in YouTube trailers use the helper path exclusively.
  - Rationale: one YouTube playback contract is easier to reason about, easier to log, and avoids long-term dual-path inconsistencies.

- Decision: The helper contract is bearer-auth-based, not cookie-based.
  - Rationale: the live yt-dlp PoC resolved age-restricted playback with `Authorization: Bearer ...` after the extractor was taught to treat that auth as authenticated.

- Decision: Bundle `yt-dlp` plus `node` and invoke them through a narrow helper boundary.
  - Rationale: local validation succeeded with `yt-dlp --js-runtimes node --remote-components ejs:github ...`, and extending the validated fork is lower risk than attempting a Kotlin port of the same logic.

- Decision: Trailer UI is availability-driven and internal-playback-only.
  - Rationale: the user explicitly wants trailer affordances hidden when Nexio cannot produce a playable trailer, rather than falling back to external launch.

- Decision: Add `Play Trailer` to the shared home poster long-press dialog only when the availability service says the trailer is playable.
  - Rationale: this extends the existing poster action surface cleanly across Classic, Grid, and Modern home layouts without inventing a second action surface.

## Architecture
- `YouTubeTrailerAuthManager`
  - Owns login state and launches the device-code / QR sign-in flow from Integration settings.
  - Exposes whether helper-backed authenticated trailer resolution is currently available.
- `YouTubeTrailerTokenStore`
  - Persists refresh token, access token, expiry, and optional delegated page id.
  - Refreshes access tokens on demand before helper execution.
- `BundledTrailerHelper`
  - Runs the packaged helper stack on-device.
  - Accepts a YouTube URL or video id plus bearer auth context.
  - Returns direct `videoUrl`, optional `audioUrl`, expiry metadata, and structured failure reasons.
- `TrailerResolutionPolicy`
  - Signed out: current internal resolver only.
  - Signed in: helper-first and helper-only for YouTube-backed cases; existing internal path for non-YouTube cases.
  - Only internal playable results are considered trailer-available.
- `TrailerAvailabilityService`
  - Used by detail and home surfaces before showing trailer actions.
  - Signed-out path only admits current internal playback results.
  - Signed-in path admits helper playback results for YouTube and existing internal playback results for non-YouTube.
- `TrailerCache`
  - Caches helper playback results until close to expiry.
  - Negative-caches misses briefly to avoid repeated helper churn during focus movement.

## Risks / Trade-offs
- Bundling `yt-dlp` and `node` on Android TV increases packaging and runtime complexity.
  - Mitigation: keep the helper boundary narrow, helper invocation structured, and helper runtime isolated from normal app startup.

- Google/YouTube auth semantics can still change over time.
  - Mitigation: treat device auth, token refresh, and helper invocation as separately testable components, and preserve the signed-out internal resolver path as a non-helper baseline.

- Trailer availability may become more expensive if every surface blocks on helper calls.
  - Mitigation: use cached availability/results, opportunistic prefetch, and negative caching; only invoke the helper when signed-in policy allows it.

- Trailer UI hiding on helper failure can make failures look like missing trailers.
  - Mitigation: expose helper/session health in Integration settings and internal diagnostics, even though user-facing trailer actions remain hidden.

## Migration Plan
1. Add the Integration settings entry and auth/session state model.
2. Replace the embedded sign-in flow with device-code / QR login and token persistence.
3. Add access-token refresh and a bearer-auth helper request contract.
4. Add the bundled helper boundary and direct playback result contract.
5. Add helper-backed trailer availability and caching.
6. Update detail and home surfaces to use availability-driven trailer UI.
7. Add the home long-press `Play Trailer` action.

## Open Questions
- Whether some signed-in account shapes will require `X-Goog-PageId` remains implementation work, but does not block the approved architecture because the helper contract can already carry it as optional auth context.
