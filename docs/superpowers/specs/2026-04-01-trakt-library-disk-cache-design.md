# Trakt Library Disk Cache and Debrid Row Design

Date: 2026-04-01

## Context

The Library screen currently treats Trakt watchlist and personal lists as live data. `TraktLibraryService`
keeps only an in-memory snapshot, and the Library-facing flows call `ensureFresh()` from `onStart`.
That means navigating to Library can trigger a full Trakt watchlist and custom-list fetch before the
screen is allowed to settle, even when the same account already loaded this data on a previous run.

Modern Home already follows a better model: restore from disk-backed state first, then let live
refresh renew the cache in the background. The Library should use that same model for Trakt-backed
lists so the first sync can be slow, but later opens are fast and deterministic.

The debrid readable-list layout also wastes vertical space and repeats the same title three times:
once as the filename, once as the parsed title, and once again as path/detail text. The requested
behavior is to keep only the useful filename/title and render it in a denser single-line row.

## Goals

- Restore Trakt watchlist and Trakt personal lists from disk cache before any live refresh.
- Allow the blocking full-screen loading state only until the first Trakt library cache exists.
- Keep later Library opens and refreshes cache-first, with live work happening in the background.
- Persist hydrated Trakt library metadata so warm-cache restores remain display-ready.
- Persist optimistic Trakt library mutations and roll them back cleanly on failure.
- Compact Real-Debrid, Premiumize, and TorBox readable rows to one useful title line.

## Non-Goals

- Move Real-Debrid, Premiumize, or TorBox fetching onto a disk-backed restore path in this change.
- Redesign the poster-grid Library layout or list/filter controls.
- Replace the broader Home disk-cache stack with a shared generic cache abstraction.
- Change Trakt list scope beyond watchlist plus personal lists.

## Decisions

### 1. Add a dedicated persisted Trakt library snapshot store

Introduce a `TraktLibrarySnapshotStore` that persists the Trakt library snapshot shape needed by the
Library UI:

- `listTabs`
- `entriesByList`
- `allEntries`
- `membershipByContent`
- hydrated display metadata keyed for restored items
- `updatedAtMs`

This keeps cache ownership inside `TraktLibraryService`, which already owns Trakt list fetch,
membership mutation, and metadata hydration behavior.

### 2. Remove live refresh from Library observer startup

`observeListTabs()`, `observeAllItems()`, and `observeMembership()` should stop using `onStart {
ensureFresh() }`. Library collectors should read the current in-memory snapshot only. On startup,
that snapshot is primed from disk when available. Live refresh becomes an explicit action triggered
by first uncached startup or user/background refresh, not by simply opening Library.

### 3. Keep a one-time blocking load only for the first uncached Trakt sync

When Trakt is authenticated and no persisted Trakt library cache exists yet, the Library may keep
the current blocking loading screen until the first sync completes or fails. Once a cache exists,
routine Trakt refresh must never blank the Library or re-enter the full-screen loading state.

### 4. Refresh and mutation paths publish through cache state

Warm-cache refresh should keep the current cached snapshot visible while a live Trakt refresh runs.
Only once the refreshed snapshot is ready should `TraktLibraryService` replace in-memory state and
write the renewed snapshot back to disk.

Optimistic mutations such as watchlist toggle, list membership edits, and personal-list
create/edit/delete/reorder should update the in-memory snapshot and persisted snapshot together. If
the network mutation fails, both memory and disk should roll back to the last confirmed snapshot.

### 5. Keep debrid fetching behavior unchanged, but compact the readable list rows

The debrid services remain live-only in this change. The UI adjustment is isolated to
`DebridLibraryListRow`:

- keep the current filename/primary title source
- render it using the current second-line text scale instead of the oversized first-line style
- drop the duplicate parsed-title line
- drop the path/detail line
- reduce padding and row spacing so more items fit on screen

## Component Changes

### `TraktLibrarySnapshotStore`

- Add SharedPreferences-backed read/write/clear behavior similar to `TraktDiscoverySnapshotStore`.
- Persist both the structural Trakt library snapshot and hydrated metadata needed for warm-cache
  Library rendering.
- Defensively clear corrupt or incompatible payloads.

### `TraktLibraryService`

- Restore persisted snapshot and metadata into state during startup.
- Track whether a persisted Trakt cache exists.
- Remove observer-driven `ensureFresh()` calls.
- Persist refreshed snapshots after successful fetch and after metadata hydration advances the
  display-ready state.
- Clear persisted snapshot state when Trakt auth is lost.

### `LibraryViewModel`

- Distinguish between:
  - initial blocking load because authenticated Trakt has no cache yet
  - warm-cache sync while cached items remain visible
- Keep `isLoading` tied only to the uncached-first-sync case for Trakt.

### `LibraryScreen`

- Preserve the current full-screen loading view for the first uncached Trakt sync.
- Keep the visible screen stable during warm-cache refreshes.
- Render compact single-line readable rows for Real-Debrid, Premiumize, and TorBox.

## Data Flow

### Cold start with no Trakt library cache

1. Trakt auth is available.
2. `TraktLibraryService` finds no persisted Trakt library snapshot.
3. Library enters blocking loading.
4. Live Trakt fetch builds the snapshot, primes/hydrates metadata, publishes state, and writes the
   snapshot to disk.
5. Library exits blocking loading and from then on treats the snapshot as the authoritative source
   for initial render.

### Warm start with cached Trakt library state

1. `TraktLibraryService` restores the persisted snapshot and hydrated metadata from disk.
2. Library observers render the restored snapshot immediately.
3. Any later refresh runs in the background and, if successful, replaces the snapshot and rewrites
   disk state.
4. If the refresh fails, the last good cached snapshot stays visible.

### Optimistic mutation

1. User changes watchlist or personal-list membership.
2. `TraktLibraryService` updates the in-memory snapshot optimistically.
3. The same optimistic snapshot is persisted to disk immediately.
4. Network mutation succeeds:
   - keep the updated snapshot.
5. Network mutation fails:
   - restore the previous in-memory snapshot
   - rewrite the previous snapshot to disk

## Error Handling

- Corrupt persisted Trakt library payloads should clear themselves and fall back to empty in-memory
  state.
- Warm-cache refresh failures should surface an error/transient message without clearing visible
  Library content.
- First uncached Trakt sync failures should leave Library in a recoverable error/retry state
  because no cache exists yet.
- Trakt sign-out or effective auth loss should clear the persisted Trakt library snapshot so a
  future sign-in does not restore stale cross-account state.

## Testing

- Unit test persisted Trakt library snapshot restore without observer-triggered live fetch.
- Unit test first authenticated uncached Trakt load still blocks until a snapshot exists.
- Unit test warm-cache refresh keeps cached items visible on refresh failure.
- Unit test optimistic Trakt mutations persist updated snapshot state and roll back both memory and
  disk on failure.
- UI/unit test compact debrid readable rows so only one title line is rendered and the layout stays
  enabled only for debrid tabs.

## Rollout / Rollback

- Roll out behind the normal Trakt library path; no user-facing toggle is needed because the target
  behavior is deterministic and scoped to Library restore.
- If the persisted snapshot path regresses Library correctness, fall back to the current live-fetch
  observer behavior by removing the restore-first path while keeping the snapshot store isolated.

## Related OpenSpec

- Change id: `persist-trakt-library-disk-cache`
- Capability touched: `library-playback`
