# Change: Add TMDB company and network detail discovery

## Why
Production companies and TV networks on the detail page are rendered as focusable cards, but selecting them does nothing. TMDB already exposes enough metadata and discover filters to turn those surfaces into real navigation and discovery entry points.

## What Changes
- Preserve TMDB IDs and kinds for production companies and networks in detail enrichment.
- Add TMDB-backed organization detail discovery for companies and networks.
- Add a dedicated organization detail route and screen for browsing related titles.

## Impact
- Affected specs: tmdb-organization-discovery
- Affected code: detail screen, TMDB API/service layer, navigation
