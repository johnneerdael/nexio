# Use TMDB TV Default With TVDB Order Overrides

## Why

TVDB-first TV routing fixes titles whose stream scene numbering follows TVDB, but it breaks titles whose metadata and stream coordinates follow TMDB. Berlin should remain TMDB-coordinate by default, while Australian Survivor needs a manual TVDB numbering override.

## What Changes

- Standard TV metadata routes to TMDB by default.
- Canonical stable IDs distinguish TMDB movies from TMDB TV shows.
- TVDB remains available as a provider ID and episode-order source.
- A global manual show override selects TVDB season numbering for stream/UX coordinates.
- Continue Watching, detail episode lists, and stream-fetch consume the selected order.
- Scrobbling remains target-native and does not become TVDB-only.

## Impact

- Existing TVDB-canonical TV expectations and audit goldens flip to TMDB canonical.
- TVDB coordinate projection becomes opt-in per canonical TMDB TV show.
- Existing Continue Watching records migrate back to TMDB identity when a crosswalk exists unless the TVDB order override is enabled.
