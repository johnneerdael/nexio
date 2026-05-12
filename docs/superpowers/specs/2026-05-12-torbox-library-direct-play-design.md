# TorBox Library Direct-Play Design

Date: 2026-05-12
Owner: nexio
Status: spec — pending implementation plan

## Summary

When TorBox is connected, surface the user's TorBox cloud library (the
`/v1/api/torrents/mylist` set, filtered to downloaded torrents) as a tab inside
Nexio's existing Library screen. Each playable video file becomes its own card.
Clicking a card resolves a fresh TorBox playback URL and jumps straight to the
Player — no detail screen, no stream-selection screen. Resume positions and
within-torrent autoplay-next are wired so a season pack feels like a binge.

## Locked decisions

| Decision | Value |
| --- | --- |
| Surface | Library screen tab only (no home rail, no standalone screen) |
| Card rendering | Filename + size only (no TMDB / Trakt enrichment) |
| Refresh policy | On tab focus + explicit "Refresh" action only (no background polling) |
| Playback URL resolution | Lazy on click — no eager `requestDownloadLink` at fetch time |
| "Playable" filter | `mimeType.startsWith("video/") && size >= 50 MB` (both required) |
| Continuity | Resume position per `torbox:{torrentId}:{fileId}` + autoplay-next within the same torrent (filename-sorted) |

## Architecture

```
┌───────────────────────┐    fetchTorBoxItems     ┌──────────────────────────┐
│ TorBoxIntegration-    │ ──────────────────────▶ │ DebridLibraryService     │
│ Provider              │ (no eager requestdl)    │  emits Flow<LibraryEntry>│
└───────────────────────┘                         └─────────┬────────────────┘
            ▲                                               │ Flow
            │ requestDownloadLink (on click)                ▼
            │                                     ┌──────────────────────────┐
            │                                     │ LibraryViewModel         │
            │                                     │ (service:torbox branch)  │
            │                                     └─────────┬────────────────┘
            │                                               │ click → direct-play
            │                                               ▼
            │                                     ┌──────────────────────────┐
            └─── resolve fresh URL ◀───────────── │ TorBoxDirectPlayHandler  │
                                                  │ navigates to Player      │
                                                  │  (deterministicAutoplay) │
                                                  └─────────┬────────────────┘
                                                            │
                                                  ┌─────────▼────────────────┐
                                                  │ Player                   │
                                                  │ • reads/writes resume    │
                                                  │   in TorBoxResumeStore   │
                                                  │ • on complete: ask DLS   │
                                                  │   for next file in       │
                                                  │   same torrent           │
                                                  └──────────────────────────┘
```

TorBox library items intentionally stay **outside** the
`ResolvedDisplaySurfaceRepository` typed-authority pipeline because they lack
stable TMDB / TVDB / IMDB IDs. Treating them as raw `LibraryEntry` rows with
TorBox-specific click semantics keeps the existing display-authority invariant
intact (see `docs/superpowers/notes/2026-05-09-modern-home-leak-root-cause.md`).

## Components & data shapes

### Modified files

- `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt`
  - In `fetchTorBoxItems`: drop the per-file `requestDownloadLink` calls and
    the concurrency semaphore. The `directPlaybackUrl` field on `LibraryEntry`
    is no longer populated for TorBox — pass `null`. Field-deletion is
    out of scope for this design (revisit once Premiumize / EasyDebrid /
    Real-Debrid library tabs converge on a single playback path).
  - Apply the tightened filter:
    `file.mimeType?.startsWith("video/") == true && (file.size ?: 0L) >= 50L * 1024 * 1024`.
  - Emit one `LibraryEntry` per surviving file. Default sort: torrents by
    `createdAt` descending; files within each torrent by `name` ascending.
  - New helper:
    ```kotlin
    suspend fun nextPlayableFileInTorrent(
        torrentId: Int,
        currentFileId: Int
    ): TorBoxNextFile?
    ```
    Reuses whatever the current `fetchTorrentList` cache holds — no fresh
    fetch. Returns null if the torrent is no longer present or no later file
    survives the filter.

- `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryViewModel.kt`
  - In the existing `selectedList?.key == DebridLibraryService.TORBOX_LIST_KEY`
    branch, add `onTorBoxItemClick(entry: LibraryEntry)` that emits a one-shot
    `DirectPlayCommand` event via `MutableSharedFlow`.
  - Add `refreshTorBoxLibrary()` that re-invokes the existing
    `DebridLibraryService` refresh with `refreshTorBox = true` and forces the
    integration-layer cache to bypass (the exact param/flag is the
    implementer's call — `TorBoxApi.getMyTorrentList` already defaults
    `bypass_cache = true`, so the work is at the `IntegrationCallSpec` /
    cache-policy layer in `TorBoxIntegrationProvider`).
  - Expose `torBoxRefreshing: StateFlow<Boolean>` for the refresh affordance.

- `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt`
  - `LaunchedEffect(selectedListKey)` calls `viewModel.refreshTorBoxLibrary()`
    when the user first lands on the TorBox tab.
  - Render a "Refresh" action in the tab header when the TorBox tab is focused
    (small icon button bound to `torBoxRefreshing` for the spinner state).
  - Collect `DirectPlayCommand` and trigger navigation through the existing
    `Navigator` to `Screen.Player.createRoute(...)`.

### New files

- `app/src/main/java/com/nexio/tv/data/local/TorBoxResumeStore.kt`
  - Per-profile preferences DataStore (`filesDir/torbox-resume-v1/p{profileId}/...`).
  - Per CLAUDE.md #3, this is **small** scalar data (one `Long` per key); it
    fits well within the DataStore size bound and does not require streaming
    JSON.
  - Keys: `torbox:{torrentId}:{fileId}` → resume position in millis.
  - API:
    ```kotlin
    suspend fun savePosition(torrentId: Int, fileId: Int, positionMs: Long, durationMs: Long)
    suspend fun loadPosition(torrentId: Int, fileId: Int): Long?
    suspend fun clear(torrentId: Int, fileId: Int)
    ```
  - `savePosition` auto-calls `clear` when `positionMs >= durationMs - 30_000`
    (30 s — matches the existing "near-end → mark watched" threshold used by
    the Player; the implementation plan should reference that constant rather
    than hardcoding a fresh one) so a finished file does not leave a stale
    resume entry.

- `app/src/main/java/com/nexio/tv/data/repository/TorBoxDirectPlayHandler.kt`
  - Injected into `LibraryViewModel`.
  - `suspend fun resolve(entry: LibraryEntry): TorBoxResolvedPlayback` — calls
    `TorBoxIntegrationProvider.requestDownloadLink(torrentId, fileId)` once,
    reads `TorBoxResumeStore.loadPosition`, returns a sealed result.
  - Same handler is used by autoplay-next, just constructed from a
    `TorBoxNextFile` instead of a `LibraryEntry`.

- `app/src/main/java/com/nexio/tv/data/repository/TorBoxAutoplayNext.kt`
  - Thin facade: `suspend fun nextEntryInSameTorrent(torrentId: Int, currentFileId: Int): TorBoxNextFile?`
  - Delegates to `DebridLibraryService.nextPlayableFileInTorrent`. Lives in a
    separate file so the Player module does not have to depend on the full
    library service surface.

- `app/src/main/java/com/nexio/tv/domain/model/TorBoxPlaybackContext.kt`
  - Parcelable record `{ torrentId: Int, fileId: Int, fileName: String }`
    carried in the Player route args so the Player has everything it needs for
    resume save and next-file lookup without re-consulting
    `DebridLibraryService`.

### Data classes

```kotlin
data class TorBoxNextFile(
    val torrentId: Int,
    val fileId: Int,
    val fileName: String
)

sealed interface TorBoxResolvedPlayback {
    data class Resolved(
        val url: String,
        val torrentId: Int,
        val fileId: Int,
        val fileName: String,
        val resumePositionMs: Long
    ) : TorBoxResolvedPlayback
    data class Failed(val message: String) : TorBoxResolvedPlayback
}

sealed interface DirectPlayCommand {
    data class Resolving(val fileName: String) : DirectPlayCommand
    data class Navigate(val args: PlayerRouteArgs) : DirectPlayCommand
    data class Failed(val message: String) : DirectPlayCommand
}
```

(`PlayerRouteArgs` already exists; this design adds an optional
`torBoxContext: TorBoxPlaybackContext? = null` field on it.)

## Data flow

### Tab open / manual refresh

```
LibraryScreen tab focus = "service:torbox"
    └─▶ LaunchedEffect(key)
          └─▶ LibraryViewModel.refreshTorBoxLibrary()
                ├─▶ torBoxRefreshing = true
                └─▶ DebridLibraryService.refresh(refreshTorBox = true, bypassCache = true)
                      └─▶ TorBoxIntegrationProvider.fetchTorrentList(apiKey)
                            └─▶ filter isDownloaded() && file is video/* ≥ 50 MB
                                  └─▶ emit Flow<List<LibraryEntry>>
                                        └─▶ Compose recomposes the grid
LibraryViewModel.torBoxRefreshing = false
```

The manual refresh button calls `refreshTorBoxLibrary()` and the same path
runs with `bypassCache = true` to skip the 60 s integration-layer cache.

### Card click → direct-play

```
User clicks LibraryEntry card
    └─▶ LibraryViewModel.onTorBoxItemClick(entry)
          ├─▶ emit DirectPlayCommand.Resolving(fileName)
          ├─▶ TorBoxDirectPlayHandler.resolve(entry)
          │     ├─▶ TorBoxIntegrationProvider.requestDownloadLink(torrentId, fileId)
          │     └─▶ TorBoxResumeStore.loadPosition(torrentId, fileId)
          ├─▶ on Resolved: emit DirectPlayCommand.Navigate(args)
          │     └─▶ LibraryScreen collects → Navigator.navigate(
          │             Screen.Player.createRoute(
          │                 sourceUrl = url,
          │                 initialPositionMs = resumePositionMs,
          │                 torBoxContext = TorBoxPlaybackContext(...),
          │                 deterministicAutoplay = true
          │             )
          │         )
          └─▶ on Failed: emit DirectPlayCommand.Failed(message) → toast
```

### Player tick + completion

```
Player tick (~ every 10 s while playing)
    └─▶ if torBoxContext != null:
          TorBoxResumeStore.savePosition(torrentId, fileId, positionMs, durationMs)

Player onMediaEnded()
    └─▶ if torBoxContext != null:
          ├─▶ TorBoxResumeStore.clear(torrentId, fileId)
          └─▶ TorBoxAutoplayNext.nextEntryInSameTorrent(torrentId, currentFileId)
                ├─▶ null → Navigator.popBackStack to library tab
                └─▶ TorBoxNextFile(torrentId, fileId, fileName)
                      └─▶ TorBoxDirectPlayHandler.resolve(synthetic entry)
                            └─▶ Player.replaceMedia(url, initialPositionMs = 0)
```

### Invariants

- No `requestdl` URL is cached across a click. Two distinct round-trips on
  click and on autoplay-next mean a 10-hour idle session is harmless.
- `TorBoxPlaybackContext` is the only state crossing into the Player. Three
  fields, stored as a single record on the active Player session — **not**
  pinned as an outer-fun local across any `supervisorScope` / `launch`
  boundary (CLAUDE.md #6).
- The file-picker helper iterates files with `for (i in files.indices)` —
  never `Iterable.forEach` inside a suspending function (CLAUDE.md #4).
- `TorBoxResumeStore` is profile-scoped; switching profiles does not bleed
  resume positions across users.

## Error handling

### Refresh / list fetch

| Failure | UX |
| --- | --- |
| Network error | Keep prior list. Toast "Couldn't reach TorBox." `torBoxRefreshing → false`. |
| HTTP 401 / invalid key | `TorBoxService` already flips `accountState.isConnected = false`; the tab disappears via `observeListTabs()`. No extra handling. |
| HTTP 5xx | Same as network error. |
| Successful response, zero playable files | Inline "No playable files in your TorBox library" state. No toast. |
| Stale cache returned because refresh failed offline | Banner "Showing cached library — couldn't refresh" above the grid. |

### URL resolve on click

| Failure | UX |
| --- | --- |
| `requestDownloadLink` returns `Failed` / `NetworkError` | Dismiss spinner. Toast `"Couldn't get a playback link for {fileName}: {reason}"`. No navigation. |
| `Success` with null / blank URL | Treated as `Failed("TorBox returned no link")`. |
| User backs out while resolving | `viewModelScope` cancellation cancels the coroutine; no navigation occurs. |
| Player rejects the URL post-navigation | Player's existing error UI. Retrying from within the Player session re-resolves a fresh URL (same path as autoplay-next). |

### Resume save

| Failure | UX |
| --- | --- |
| DataStore write throws | Log + swallow. The next tick retries. Never user-visible. |
| Profile switched mid-playback | Resume save continues to the originating profile until the Player session ends. |

### Autoplay-next

| Failure | UX |
| --- | --- |
| No next file in torrent | Pop back to library tab. No toast. |
| Next file found but resolve fails | Toast `"Couldn't auto-advance: {reason}"`. Pop back to library tab. |
| Torrent list cache went stale offline | Helper returns the stale entry; file IDs are stable per torrent so resolve still succeeds. |

### State invariants

- No silent fallback to detail-view or stream-selection. A failed click stays
  on the library tab with a toast.
- The TorBox tab disappears with the integration. In-flight clicks on a
  stale card return `Failed("TorBox disconnected")`.
- All TorBox DTO fields stay nullable; the playable filter treats every null
  as "not playable" so a malformed response cannot crash.

## Testing

### Unit — file selection + filter

`DebridLibraryServiceTorBoxFilesTest`

- `.nfo`, `.srt`, `.txt` excluded.
- File with null `mimeType` excluded even if extension is `.mp4`.
- A 47 MB `video/mp4` excluded; a 51 MB one included.
- `sample.mkv` < 50 MB excluded; ≥ 50 MB included (size, not filename, is the
  filter).
- Season pack `S01E03.mkv` / `S01E01.mkv` / `S01E02.mkv`:
  `nextPlayableFileInTorrent(torrentId, idOfS01E01)` returns `S01E02.mkv`;
  after `S01E03.mkv` returns null.
- Single-file movie torrent: `nextPlayableFileInTorrent` returns null.
- Files in different torrents are never returned as "next" — boundary stays
  at the torrent.

### Unit — direct-play handler

`TorBoxDirectPlayHandlerTest`

- `resolve(entry)` calls `requestDownloadLink(torrentId, fileId)` exactly
  once; returns `Resolved` with the URL on `IntegrationCallResult.Success`.
- `resumePositionMs` reads `TorBoxResumeStore` and surfaces zero when no
  entry exists.
- Returns `Failed` on `NetworkError` / `HttpError`, propagating upstream
  `reason`.
- Returns `Failed("TorBox returned no link")` on `Success` with blank URL.

### Integration — on-click path

`LibraryViewModelTorBoxClickTest`

- Clicking a `LibraryEntry` in the `service:torbox` tab emits a
  `DirectPlayCommand.Resolving(fileName)` then exactly one
  `DirectPlayCommand.Navigate(args)`.
- `Navigate.args` contains `deterministicAutoplay = true` and the URL from
  the faked provider.
- `Failed` from the handler emits a single `DirectPlayCommand.Failed(message)`
  and never a `Navigate`.
- Cancelling the VM scope mid-resolve produces no orphaned events.

### Out of scope for new tests

- Player resume save / autoplay-next loops are exercised by the existing
  Player test infrastructure. This design adds one
  `torBoxContext: TorBoxPlaybackContext?` field; a single regression test in
  the Player test file should assert "ticks call
  `TorBoxResumeStore.savePosition` while `torBoxContext` is non-null."
- TorBox API integration tests. The integration-layer cache and retry paths
  are already covered; this design adds no new endpoints.

### Test discipline

- No `Iterable.forEach` in suspending test bodies — `for (i in list.indices)`
  when iterating fixtures.
- `TorBoxResumeStore` tests use an in-memory DataStore (matching how existing
  Trakt / Real-Debrid auth-store tests avoid real file I/O — implementation
  picks the closest existing helper or builds a minimal one).

## Out of scope

- Home rail surface for TorBox items.
- TMDB / Trakt metadata enrichment for TorBox filenames.
- Premiumize / EasyDebrid / Real-Debrid parity for direct-play library tabs
  (those flows already differ; if we want parity it gets its own design).
- Continuous background polling of `mylist` while the Library tab is not
  visible.
- Long-press card menus (e.g. "Remove from TorBox").
- Filename parsing to expose season / episode metadata.
- Cross-torrent autoplay-next (e.g. "movies you have queued").

## Compliance with hard rules

This design has been audited against the project-wide invariants in
`CLAUDE.md`:

- **#1 Display authority** — items stay outside `ResolvedDisplaySurfaceRepository`
  because they have no stable cross-provider IDs. No first-paint downgrade
  paths exist on this surface.
- **#2 State retention** — no large lists land in observed `UiState`. The
  TorBox library list is collected from `DebridLibraryService` as a separate
  `StateFlow` already; this design does not change that.
- **#3 Persistence** — `TorBoxResumeStore` stores one `Long` per key, well
  under the SharedPreferences / DataStore size threshold. No JSON, no blobs.
- **#4 Coroutines — no suspending forEach** — the next-file picker uses
  indexed-for.
- **#5 Memoization** — no new reference-fresh boundaries introduced. Existing
  `DebridLibraryService` memoization is unchanged.
- **#6 Coroutines — no outer-fun pins** — `TorBoxPlaybackContext` is the only
  value crossing into the Player and it is held as a field on the active
  Player session, not as a function-head local across fan-out.
- **#7 Git staging** — implementation will stage by explicit path.
- **#8 Smoke tests** — any smoke test against the new tab MUST select a
  profile via `KEYCODE_DPAD_CENTER` before navigating to the Library screen,
  per the project rule.
