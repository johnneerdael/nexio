# Change: Persist Trakt library cache and compact debrid library rows

## Why

The Library screen currently fetches Trakt watchlist and personal-list data live when the screen is
opened. `TraktLibraryService` only keeps an in-memory snapshot, and its observer flows call
`ensureFresh()` on start. That makes Library navigation slow even for returning users who already
synced the same account before. The intended behavior is the same as Modern Home: restore from a
disk-backed snapshot first, let live sync renew that cache in the background, and only allow the
very first uncached library sync to block.

The debrid readable-list presentation also repeats the same title three times and wastes vertical
space. The row should keep only the useful filename/title and render it in a denser one-line
layout.

## What Changes

- Add a persisted Trakt library snapshot store so Trakt watchlist and personal lists restore from
  disk before live refresh.
- Remove live fetch from Library observer startup and make Trakt refresh update the disk-backed
  snapshot instead of being the initial render source.
- Keep the Library full-screen loading state only for the first authenticated Trakt sync when no
  cache exists yet.
- Persist optimistic Trakt library mutations and roll back persisted state on mutation failure.
- Compact the readable Real-Debrid, Premiumize, and TorBox Library rows to a single filename/title
  line with smaller item height.

## Impact

- Affected specs: `library-playback`
- Related pending change: `fix-library-metadata-direct-playback`
- Affected code:
  - `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/LibraryRepositoryImpl.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryViewModel.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt`
  - focused repository and Library UI tests

## Rollout & Safety

- Scope the disk-backed restore model only to Trakt watchlist and personal lists in this change.
- Keep corrupt persisted snapshot handling defensive so invalid payloads self-clear and trigger a
  clean re-sync.
- Preserve the last good cached Trakt snapshot during warm-cache refresh failures.
