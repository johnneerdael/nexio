# Change: Fix library metadata hydration and direct-library playback recovery

## Why

The TV Library currently renders Trakt-backed items with long-lived missing poster and artwork
metadata, which makes watchlist and custom-list entries appear as blank cards. Debrid-backed
library items also take a broken direct-play path: Real-Debrid items can expose non-playable
provider links, and playback errors fall back into the stream-selection route even though library
direct-play should bypass stream selection entirely.

## What Changes

- Preserve the current Trakt library shape of watchlist plus custom lists.
- Hydrate Trakt library items with poster, background, logo, description, and related metadata
  before they are emitted to the Library UI.
- Treat debrid `directPlaybackUrl` values as player-ready URLs and resolve Real-Debrid torrents to
  playable download links before exposing them in Library.
- Add a direct-library player launch source so back and playback-error recovery return to Library
  instead of reopening `Stream`.

## Impact

- Affected specs: `library-playback`
- Affected code: `TraktLibraryService`, `DebridLibraryService`, `Screen`, `PlayerNavigationArgs`,
  `NexioNavHost`, focused repository/navigation tests
