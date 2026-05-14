# Add MDBList Watchlist and Scrobble

## Why

CrossWatch supports MDBList as both a watchlist sync provider and a scrobble sink. Nexio currently has MDBList discovery and ratings plumbing, but Unified Watchlist only reads Trakt and Simkl, and playback scrobble fan-out only writes Trakt and Simkl.

## What Changes

- Add MDBList watchlist read support and expose it as a source in Unified Watchlist.
- Add MDBList watchlist add/remove mutation support for later routing and auto-remove.
- Add MDBList as a third scrobble fan-out provider.
- Preserve provider-neutral Library UI cards; source membership remains model data only.
- Match CrossWatch endpoint/body behavior for MDBList watchlist and scrobble calls.

## Non-Goals

- Do not add provider badges to Unified Watchlist cards.
- Do not add next-episode or progress context to Unified Watchlist.
- Do not replace existing Trakt or Simkl scrobble behavior.
