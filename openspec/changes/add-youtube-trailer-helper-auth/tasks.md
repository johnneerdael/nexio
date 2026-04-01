## 1. Implementation
- [ ] 1.1 Add `Integration > YouTube Trailer Login` settings UI and state plumbing for a Nexio-owned YouTube trailer session.
- [ ] 1.2 Add a SmartTube-style device-code / QR sign-in flow that can establish and refresh the app-owned trailer auth session.
- [ ] 1.3 Add token persistence and access-token refresh for the YouTube trailer auth session, including optional delegated page-id support.
- [ ] 1.4 Bundle a same-device trailer helper runtime using the patched `yt-dlp` plus `node`, and define a structured bearer-auth request/response contract for direct playback URL resolution.
- [ ] 1.5 Add a helper-backed trailer resolution policy that preserves the current internal resolver for signed-out users and routes all signed-in YouTube-backed trailers through the helper only.
- [ ] 1.6 Add availability gating so trailer UI is shown only when Nexio has an internally playable trailer result, with no surfaced trailer-only external fallback.
- [ ] 1.7 Add a `Play Trailer` action to the shared home poster long-press dialog for Classic, Grid, and Modern home layouts when trailer availability is positive.
- [ ] 1.8 Add caching, expiry handling, timeout behavior, and negative-caching for helper-backed trailer resolution.
- [ ] 1.9 Add targeted tests for signed-in/signed-out availability policy, device auth state, token refresh, helper result parsing, and trailer visibility on detail and home surfaces.
