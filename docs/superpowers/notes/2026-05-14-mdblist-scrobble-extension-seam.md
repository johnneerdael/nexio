# MDBList Scrobble Extension Seam

Source audit: `/Users/jneerdael/Scripts/CrossWatch/providers/scrobble/mdblist/sink.py`.

This change does not implement MDBList scrobble writes. The intended Nexio seam is:

1. Add MDBList tracking auth to `TrackingProviderStateService`.
   - Current state only exposes Trakt/SIMKL authentication.
   - MDBList should become an independently active sink, not the `effectiveProvider` for the single "watching now" badge unless a merged badge design exists.

2. Add `MDBListScrobbleService` beside `TraktScrobbleService` and `SimklScrobbleService`.
   - It should accept the shared `TrackingScrobbleItem` model with hydrated IDs.
   - It should enqueue mutations through `ProviderMutationOutboxCoordinator`; do not write directly from playback callbacks.
   - It should preserve owner profile boundaries using the same `PlaybackOwnerContext` path.

3. Add `MDBListScrobbleMutationAdapter`.
   - Adapter key: `mdblist.scrobble`.
   - Mutation kinds: `mdblist.scrobble.state` and optionally `mdblist.scrobble.checkin` only if MDBList supports a separate check-in concept.
   - Priority bucket: `SCROBBLE`.
   - Rate-limit class: write-limited, matching CrossWatch's default MDBList write ceiling of 1 write/sec.

4. Extend `MDBListApi` and `MDBListIntegrationProvider`.
   - Endpoints mirror CrossWatch paths:
     - `POST /scrobble/start`
     - `POST /scrobble/pause`
     - `POST /scrobble/stop`
   - Authentication remains query parameter `apikey`.
   - Use an `IntegrationApiShapes` entry separate from rating/list reads, e.g. `MDBListApiShapes.SCROBBLE`.
   - Use `IntegrationWorkClass.SCROBBLE`.

5. Body model requirements from CrossWatch:
   - Movies: `{ "movie": { "ids": ... }, "progress": percent }`.
   - Episodes: `{ "show": { "ids": ..., "season": { "number": S, "episode": { "number": E } } }, "progress": percent }`.
   - ID priority:
     - Show/episode show IDs: `tmdb`, `trakt`, `imdb`, `tvdb`, `mdblist`.
     - Movies: `tmdb`, `imdb`, `trakt`, `mdblist`.
   - Fall back to title/year only if strong IDs are unavailable, and surface the same rejection reason style used by current tracking services.

6. Behavior to carry over from CrossWatch when implementing:
   - Start progress floor should avoid 0%.
   - Pause/stop debouncing should not emit repeated non-completion writes.
   - Completed stop should route through the shared watchlist auto-remove coordinator rather than duplicating provider-specific removal state.
   - Retain the best accepted skeleton per item/session if MDBList accepts one body shape after rejecting another.

7. Tests required for implementation:
   - Auth disabled means no MDBList mutation is enqueued.
   - Start/pause/stop enqueue one MDBList outbox mutation each when enabled.
   - Movie and episode request bodies match the CrossWatch shapes above.
   - Hydrated IDs win over parsed raw `contentId`.
   - Completed stop invokes shared auto-remove after successful settlement.
