# Continue Watching Canonicalization Design

Date: 2026-05-16

## Problem

Continue Watching can persist rows that should not be visible:

- Completed shows remain in the feed, including Dexter: Original Sin, Paradise, Shrinking, and The Night Agent.
- Australian Survivor is keyed to TVDB identity but uses provider/TMDB-style coordinates (`S13E01`) instead of TVDB coordinates. This causes stream lookup to search the wrong episode.
- Landman appears as `S03E01` even though the episode has no known air date and has not aired.

The rooted device snapshot for `com.nexio.tv` on `192.168.50.98` confirms these rows are persisted in `/data/data/com.nexio.tv/files/continue-watching-snapshot-v1/p1.json`, not only rendered by the UI. The snapshot had 27 `nextUpItems`, 5 synthetic Trakt rows, 0 scheduled reemit rows, and the reported bad rows were present in `nextUpItems`.

## Evidence

`AirDateGate.isAired` currently returns `true` when all air-date inputs are unknown. Landman is persisted with `firstAiredMs = 0`, no `firstAired`, and no TVDB availability instant, so the current gate treats it as aired.

The live Trakt watched cache under `/data/data/com.nexio.tv/files/integration-cache/profile/1/provider/TRAKT/.../trakt/sync/watched/shows.bin` contains watched entries for several affected shows. Continue Watching still publishes next-up rows at or before those watched coordinates, which means provider next-up validation is not applying a final watched-anchor suppression pass across canonical aliases.

Australian Survivor is persisted as `contentId = tvdb:303904`, but with `season = 13`, `episode = 1`, and title `The Multiverse`. That proves the series identity is TVDB, while the episode coordinate is still provider-native. For non-anime shows, this breaks the stream-fetch contract because addon links are expected to use TVDB season/episode coordinates when TVDB is the authoritative identity.

## Goals

- Persist only canonical, currently actionable Continue Watching rows.
- For non-anime series, use TVDB season/episode coordinates whenever TVDB identity is resolvable.
- Keep anime on the existing Kitsu/anime projection path.
- Suppress rows at or before completed/watched canonical coordinates across Trakt, SIMKL, MDBList, local progress, and retained snapshots.
- Keep displayed labels, persisted snapshot rows, click-time metadata, and stream-fetch identity aligned to the same coordinates.
- Prevent unknown-air-date or future next-up rows from appearing in the main feed.

## Non-Goals

- Do not rewrite tracker authentication or provider fanout.
- Do not change Library, Watchlist, or Details behavior except where they consume corrected Continue Watching records.
- Do not add UI-only hiding for rows that remain invalid in the persisted snapshot.

## Architecture

Add a canonicalization pass in the data layer before `ContinueWatchingSnapshot` is persisted. The pass should run after raw provider/local progress and next-up rows are collected, and before `ContinueWatchingSnapshotService.buildRawSnapshot` returns.

The pass owns three decisions:

1. Resolve the canonical show identity and episode coordinate for Continue Watching.
2. Suppress entries that are completed, already watched, unaired, or unknown-air-date.
3. Produce rows whose display coordinate and stream-fetch coordinate match.

This keeps the UI as a renderer of already-correct snapshot state and avoids hiding bad data late in `HomeViewModelContinueWatching`.

## Canonical Episode Coordinates

For non-anime series, TVDB is the authoritative coordinate source when a TVDB ID can be resolved from any identity surface:

- `contentId`
- provider IDs from Trakt/SIMKL/MDBList payloads
- canonical route identity
- display identity
- resolved display sidecars

When a provider row arrives with IMDb/TMDB/Trakt/SIMKL identity and provider-native `season`/`episode`, the canonicalization pass should resolve the TVDB series and map the candidate episode onto the TVDB episode list. The result must update:

- `TrackingNextUpEntry.season`
- `TrackingNextUpEntry.episode`
- `TrackingNextUpEntry.videoId`
- `TrackingNextUpEntry.episodeTitle`
- `WatchProgress.season`
- `WatchProgress.episode`
- `WatchProgress.videoId`
- `ContinueWatchingRecord.episodeContext`
- stream-fetch/resume identities used for click navigation

If TVDB cannot resolve a confident coordinate, the row should not be promoted to canonical TVDB coordinates. For non-anime next-up rows, unresolved coordinates should be dropped from the main feed rather than published with mixed identity/coordinate semantics.

Anime keeps the existing Kitsu/anime projection behavior and must not be forced into TVDB coordinates.

## Completion Suppression

Build canonical watched anchors from all known progress sources:

- local completed progress
- Trakt watched/history/progress
- SIMKL watched/history/progress
- MDBList watched/history/progress
- retained snapshot records that carry canonical identity

Each anchor should include canonical parent identity, TVDB coordinate when available, provider aliases, and `lastWatched`.

Suppress any resume, next-up, synthetic next-up, or retained record when it matches the same canonical show and its coordinate is at or before the watched anchor. The suppression must work even when the candidate row uses `tt...`, `tmdb:...`, `trakt:...`, `simkl:...`, or `tvdb:...`.

Provider validation can still use provider APIs, but final publication must not trust a provider next-up row until it passes the canonical watched-anchor check.

## Air-Date Gate

Continue Watching main-feed next-up rows require known aired evidence:

- `tvdbAvailabilityInstantMs > 0` and `<= now`, or
- `firstAiredMs > 0` and `<= now`, or
- parseable `firstAired` date/timestamp that is `<= now`.

Rows with unknown air dates must not enter the main feed. Rows with concrete future air dates may be retained only in `scheduledReemit` so the alarm can re-evaluate them later. Rows with no concrete future trigger are dropped until a provider refresh supplies an air date.

Resume rows do not need air-date gating because they represent explicit playback progress.

## Snapshot Retention

`retainStableRowsFromPreviousSnapshot` must apply the same canonicalization, watched-anchor suppression, and air-date gate as fresh rows. Previous rows are allowed to preserve display continuity only if they are still valid after canonical checks.

This prevents stale persisted next-up rows from surviving after a provider marks the episode watched, removes it, or stops returning it.

## Data Flow

1. `TrackingProgressService` emits raw provider progress and next-up candidates.
2. `WatchProgressRepositoryImpl` merges provider and local progress without final CW publication authority.
3. `ContinueWatchingSnapshotService` builds raw candidates.
4. The new canonicalization pass resolves identity, projects coordinates, applies watched anchors, and applies air gating.
5. `ContinueWatchingMerger` merges only canonical eligible resume records.
6. The snapshot store persists the canonical result.
7. `HomeViewModelContinueWatching` renders the persisted canonical rows.

## Testing

Add focused unit tests for:

- Unknown-air-date next-up row is dropped from main CW.
- Future dated next-up row is scheduled, not rendered.
- Completed watched anchor suppresses same-coordinate and earlier next-up rows across IMDb and TVDB aliases.
- Retained previous rows are suppressed when a newer watched anchor exists.
- Australian Survivor-style provider coordinate is projected to TVDB coordinate for non-anime TVDB series.
- Anime/Kitsu projection remains unchanged.
- Stream-fetch identity uses the same canonical coordinate shown in the row label.

Run focused tests around:

- `ContinueWatchingSnapshotService`
- `TraktProgressService` next-up validation
- `SimklProgressService` coordinate mapping
- `MDBListProgressService` coordinate mapping
- `HomeViewModelContinueWatchingProjectionTest`

## Rollout

This is a data correction and should be safe to ship without migration. Existing persisted snapshots should be sanitized on read or next refresh so invalid rows disappear naturally. No manual release version bump is needed.

## Open Questions

None. The product decision is explicit: non-anime Continue Watching uses TVDB coordinates whenever TVDB identity is resolvable.
