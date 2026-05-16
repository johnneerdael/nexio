# Proposal: Fix Continue Watching Canonicalization

## Why

Continue Watching can persist completed, unaired, or wrongly-numbered rows. A rooted-device snapshot for `com.nexio.tv` showed the affected rows in `continue-watching-snapshot-v1/p1.json`, so the source snapshot is wrong rather than only the UI projection.

The current pipeline can key non-anime shows to TVDB identity while keeping provider-native season/episode coordinates. It also treats unknown air dates as aired and does not apply a final watched-anchor suppression pass across provider aliases.

## What Changes

- Add a canonical Continue Watching normalization pass before snapshot persistence.
- Use TVDB season/episode coordinates for non-anime series whenever TVDB identity is resolvable.
- Keep anime on the existing Kitsu/anime projection path.
- Suppress resume, next-up, synthetic, and retained rows at or before completed/watched canonical coordinates across provider aliases.
- Change main-feed air gating so unknown-air-date next-up rows are not rendered.
- Keep future dated rows only when they have a concrete scheduled reemit trigger.

## Impact

- Affects Continue Watching snapshot building, next-up validation, retained snapshot rows, stream-fetch identity, and provider progress tests.
- Does not change Library, Watchlist, tracker authentication, or provider fanout.
