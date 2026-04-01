# Change: Add YouTube Trailer Helper Auth

## Why
Nexio's current trailer system can only play YouTube-backed trailers internally when the built-in resolver can derive direct playable media URLs without an authenticated YouTube session. That excludes age-restricted and other authenticated-only YouTube trailers, which causes missing trailer affordances or external-app handoff instead of reliable in-app playback.

Local validation has now proved a better same-device path: SmartTube-style device-code login can produce bearer auth that, when fed into a narrowly patched `yt-dlp`, resolves direct playback URLs for age-restricted trailers without browser cookies. The requested behavior is therefore to make authenticated YouTube trailer playback a same-device Nexio capability using device-code auth plus a bundled helper, while still only surfacing trailer UI when Nexio can actually play the trailer internally.

## What Changes
- Add a new `Integration > YouTube Trailer Login` settings entry with a TV-friendly device-code / QR sign-in flow.
- Add an app-owned YouTube trailer auth session built around refresh/access tokens, not browser cookies.
- Add a bundled same-device trailer helper runtime built around `yt-dlp` plus `node`, invoked only after YouTube trailer login is present.
- Extend trailer resolution policy so signed-out behavior remains the current internal resolver only, while signed-in behavior routes all YouTube-backed trailers through the auth-backed helper.
- Change trailer availability rules so trailer UI is shown only when Nexio can produce an internal playable trailer; external-only trailer results must not surface trailer affordances.
- Add a home poster long-press `Play Trailer` action that is shown only when trailer availability is positive under the signed-in/signed-out policy.

## Impact
- Affected specs: `trailer-playback`
- Affected code: settings integration UI, device auth/session management, token refresh, helper process/runtime management, helper request contract, trailer resolution policy, detail trailer availability, home poster options dialog, and trailer caching
