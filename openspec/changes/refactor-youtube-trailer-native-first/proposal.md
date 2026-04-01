# Change: Refactor YouTube Trailer Resolution To Native-First

## Why
Nexio's authenticated YouTube trailer path currently prefers the bundled helper before the native Kotlin extractor whenever a YouTube trailer login session is present. That makes many ordinary public trailers slower to start than necessary, even though the native extractor can often resolve them immediately without helper startup or JavaScript challenge work.

Recent device validation confirmed that the native resolver is materially faster for non-restricted trailers, while the helper path remains necessary for age-restricted or otherwise native-incompatible YouTube playback. The requested behavior is therefore to prefer native YouTube playback first everywhere and keep the authenticated helper as a fallback only when native resolution fails.

## What Changes
- Update shared YouTube trailer resolution to prefer reusable cached playback, then the native Kotlin extractor, then the authenticated helper, and finally the backend bridge.
- Allow native-resolved YouTube playback cache entries to be reused even when the user is signed into `YouTube Trailer Login`.
- Preserve all higher-level TMDB and Streailer routing so title trailers, season trailers, season recaps, Modern Home hero autoplay, and trailer screensaver all inherit the new order automatically through `TrailerService`.
- Keep the authenticated helper and Android JavaScriptEngine support intact as fallback-only behavior for age-restricted and other native-incompatible YouTube cases.
- Add focused debug logging that identifies whether cache, native, helper, or backend produced the final playback source.

## Impact
- Affected specs: `trailer-playback`
- Affected code: `TrailerService`, YouTube playback cache policy, debug diagnostics, and trailer resolution tests
