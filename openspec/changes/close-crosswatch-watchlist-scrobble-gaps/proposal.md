# Close CrossWatch Watchlist and Scrobble Gaps

## Why

The Unified Watchlist tab now exists, but the CrossWatch source audit found missing producer and scrobble parity behavior:

- Unified Watchlist memberships are projected from `UNIFIED_WATCHLIST_SURFACE_KEY`, but no production code publishes that surface.
- Completed scrobbles do not remove matching watchlist entries across active providers.
- Trakt episode scrobble 404s do not retry using CrossWatch's `/search/{id_type}/{id}?type=episode` fallback.
- Scrobble heartbeats can send weaker raw IDs than the initial hydrated start/pause/stop emission.
- MDBList scrobble is still unimplemented and needs a clear outbox-aligned extension seam.

## What Changes

- Add a Unified Watchlist resolved-display producer that uses the existing Modern Home hydration and display authority path.
- Preserve hydrated ID bundles on heartbeat scrobbles.
- Add completed-scrobble watchlist auto-remove through existing provider mutation adapters.
- Add Trakt episode 404 fallback search for scrobble retries.
- Document the MDBList scrobble seam without implementing MDBList network writes in this change.

## Non-Goals

- No provider badges in Unified Watchlist UI.
- No next episode or continue-watching progress context in Unified Watchlist.
- No MDBList scrobble implementation in this change.
- No manual release/version bump.

## Verification

- Unit tests for Unified Watchlist resolved-surface publication.
- Unit tests for heartbeat hydrated ID preservation.
- Unit tests for auto-remove TTL/routing.
- Unit tests for Trakt episode fallback.
- `openspec validate close-crosswatch-watchlist-scrobble-gaps --strict`.
