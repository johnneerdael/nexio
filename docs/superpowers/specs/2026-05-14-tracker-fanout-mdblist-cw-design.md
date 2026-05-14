# Tracker Fan-Out And MDBList Continue Watching Design

## Context

Nexio now treats Trakt, SIMKL, and MDBList as authenticated tracker providers, but the implementation is uneven. Playback scrobble start, pause, and stop already fan out to all authenticated trackers. Manual progress/history mutations and season watched actions still route through the deprecated `effectiveProvider` compatibility field, and MDBList is explicitly skipped in several read/write paths.

This creates three user-visible gaps:

- MDBList scrobble can write paused/watched state, but the Continue Watching feed does not read MDBList playback state back.
- Mark watched, mark unwatched, clear progress, and season watched actions do not update every authenticated tracker.
- Season actions do not fully match the recent Nuvio behavior for filtering already-watched episodes, `other` season-media sources, and previous-seasons watched support.

`EffectiveTrackingProviderState.activeProviders` is the product authority for tracker fan-out. `effectiveProvider` may remain only as display compatibility until separate cleanup removes it.

## Goals

- Make MDBList a full Continue Watching source using its playback and watched sync endpoints.
- Route user progress/history mutations to every authenticated supported tracker with best-effort fan-out.
- Remove `effectiveProvider` from tracker write-routing decisions.
- Keep UI cards/provider display neutral and preserve existing CW stable-ID dedupe behavior.
- Bring season watched actions in line with Nexio's provider batch architecture and Nuvio's episode-selection fixes.

## Non-Goals

- Rework Library provider selection or list management.
- Invent MDBList next-up rows when only paused playback/history rows are available.
- Replace the existing provider outbox wholesale.
- Remove all legacy `effectiveProvider` references from display-only surfaces.

## Architecture

Add a tracker capability layer below `WatchProgressRepositoryImpl` and `TrackingProgressService`. Each provider implementation declares the operations it supports:

- Read playback progress for Continue Watching.
- Read watched/history state.
- Add watched history.
- Remove watched history.
- Clear paused playback/progress.
- Batch mark season episodes watched.

Concrete implementations:

- Trakt capability: adapts existing Trakt progress/history/scrobble mutation adapters.
- SIMKL capability: adapts existing SIMKL progress/history/season mutation adapters.
- MDBList capability: adds MDBList progress/history read and write support.

`WatchProgressRepositoryImpl` becomes an orchestrator. It applies local progress/snapshot state once, then creates provider mutations for each provider in `activeProviders`. Provider-specific payload build failures or enqueue failures are isolated to that provider and must not cancel the remaining providers.

`TrackingProgressService` combines all authenticated provider progress streams. It should not subtract `TrackingProvider.MDBLIST` from `activeProviders`. Downstream `ContinueWatchingSnapshotService` and `ContinueWatchingMerger` continue to collapse duplicates using existing stable IDs and episode coordinates.

## MDBList Continue Watching Source

MDBList gets a progress/history service separate from `MDBListScrobbleService`.

Read paths:

- `GET /sync/playback?apikey=...` maps paused sessions to `WatchProgress` for resume/CW.
- `GET /sync/watched?apikey=...` maps watched movies/episodes to completed `WatchProgress` and watched-state lookups.
- MDBList watched rows inform completion state and dedupe. They do not create a synthetic next-up feed unless the API response contains a concrete next-unwatched episode shape that can be mapped without inference.

Mutation paths:

- Manual mark watched uses MDBList `/sync/watched`.
- Manual mark unwatched uses `/sync/watched/remove`.
- Clear paused progress uses `/scrobble/clear`.
- Full show clear removes paused playback and watched history only when the existing Nexio action semantics already mean clearing history, not for a simple resume-row dismissal.
- Playback scrobble remains `/scrobble/start`, `/scrobble/pause`, and `/scrobble/stop`.

MDBList-specific raw keys should be preserved for later routing where available, but canonical dedupe should rely on IMDb, TMDb, TVDb, Kitsu, MAL, AniList, AniDB, Trakt, and SIMKL IDs already carried through the existing hydration path.

## Fan-Out Semantics

User actions use best-effort fan-out:

- Update local/UI optimistic state once.
- Resolve `activeProviders` for the profile.
- Build one provider mutation per authenticated supported provider.
- Enqueue each provider independently.
- Continue when a provider lacks enough IDs, is temporarily unavailable, or fails to enqueue.
- Record provider-specific skipped/rejected/failure details in logs, trace, or outbox state.
- Keep successful provider writes intact.

This applies to:

- Mark movie watched.
- Mark episode watched.
- Mark item unwatched.
- Clear episode/movie/show progress.
- Mark season watched.
- Mark previous episodes watched.
- Mark previous seasons watched.

The user-facing message should stay concise. It should not expose a long provider-by-provider report unless the app already has an appropriate diagnostics surface.

## Season Actions

Season marking combines Nexio's provider batch architecture with Nuvio's selection behavior.

Selection rules:

- Mark season watched only targets episodes in that season that are not already watched locally or remotely.
- Preserve Nexio's aired-only filtering so unaired episodes are not marked watched.
- For non-standard `other` media or season-media detail flows, use the current season episode source when present, matching Nuvio's `episodesForSeason` behavior.
- Existing "mark previous episodes watched" should use the same batch fan-out pipeline instead of per-episode single-provider writes.
- Add "mark previous seasons watched" to the existing season action surface when the UI has a natural place for it.

Provider writes:

- Trakt keeps using resolved Trakt episode IDs and provider-scoped partial not-found handling.
- SIMKL keeps using season and episode numbers.
- MDBList uses `/sync/watched` with show IDs and episode list payloads from the MDBList API contract.
- If one provider cannot build a valid season payload, skip that provider only and continue with the rest.

## Error Handling And Rollback

Provider failures are scoped. A failed Trakt write must not roll back a successful SIMKL or MDBList enqueue, and vice versa.

Local/UI optimistic state reflects the user's accepted intent. It should not jump back when at least one provider write was accepted or queued. Provider-level retry and reconciliation remain the responsibility of the outbox/provider adapter. Rollback is appropriate only for provider-scoped optimistic provider caches when that provider's mutation reaches terminal failure.

If every provider skips or fails synchronously before enqueue, the UI may report the action as failed and restore local optimistic state where existing behavior expects that.

## Testing

Add focused tests that prevent regression to single-provider routing:

- `WatchProgressRepositoryImpl` fan-out:
  - Trakt+SIMKL+MDBList authenticated queues all supported provider mutations.
  - MDBList-only mark watched works.
  - One provider failure does not block the other provider mutations.
  - `effectiveProvider` value does not affect write routing.

- `TrackingProgressService`:
  - MDBList playback rows are included in `observeAllProgress`.
  - Trakt+SIMKL+MDBList progress rows are combined.
  - MDBList-only progress is not empty when playback exists.

- Continue Watching dedupe:
  - Same episode from Trakt, SIMKL, MDBList, and local collapses to one row through stable IDs plus season/episode.
  - MDBList weak/title-only rows do not merge with unrelated items.

- Season actions:
  - Season watched filters already-watched and unaired episodes.
  - `other`/season-media episodes use the current season episode source.
  - Previous seasons watched batches prior unwatched aired episodes.
  - Trakt partial not-found rollback remains provider-scoped.
  - SIMKL and MDBList continue when Trakt skips or fails.

- Parity audits:
  - MDBList scrobble thresholds/body shape remain aligned with CrossWatch.
  - Season episode selection remains aligned with the relevant Nuvio behavior.

## Verification

Run targeted unit tests for tracker fan-out, MDBList progress/history mapping, CW dedupe, and season actions. Run Kotlin compile after implementation. Device smoke should be attempted only if the native FFmpeg/CMake APK build blocker is resolved.
