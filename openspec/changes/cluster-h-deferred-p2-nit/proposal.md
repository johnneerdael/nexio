# Change: Cluster H Deferred P2 and Nit Closures

## Why

Cluster G (commit `0613283d0`) cleared the merge gate by closing the 17 P0+P1 findings from `review-dossier-2/09-known-gaps.md`. The remaining 34 P2 + 34 Nit findings (68 total) were tracked as deferred. This change closes them in one sweep.

The work splits into three buckets:

**Stale tests + dead code (~10 findings):** failing fixtures from parallel-session WIP (F2-B-02..04, F2-D-02), unused classes (F2-B-07 `GlobalMetadataDocument`, F2-D-04 `MetadataCacheKeys`, F2-skip-01 `FieldOwner.SKIP_SEGMENTS`), unused enum constants (F2-C-02 6 dead constants in `IntegrationApiShapes`).

**Test/audit coverage gaps (~20 findings):** new architecture pins (F2-J-04..07, F2-B-05), validator rules (F2-I-08, F2-I-09 — `normalizer_warning` and `scrobble_rejected` events have emitters but no consumer rules), trace cache-decision coverage (F2-D-05, F2-I-04, F2-I-05), regression scenarios (F2-F-02 last-writer-wins switch, F2-D-03 stale-guard policy), and the SHA-correlation problem (F2-F-04 — boundary audit artifact stamps wrong SHA).

**Substantive code fixes (~30 findings):** the F-C-06 follow-on (`fetchPopularLists` missed, F2-C-03), atomicity (`upsertRailMembership` no @Transaction, F2-G-03), trace integrity (`JsonlTraceWriter` swallows IOException, F2-I-10), behavior cleanups across Lanes E/F/G/H/I, and a ~15-finding documentation/comment batch covering ambient-fallback intent, design notes, and migration paths.

## What Changes

### MODIFIED

[~30 production files updated for behavior fixes; full list in plan §"File structure" + per-task "Files" sections.]

### ADDED

- 6 new test pins (F2-B-05, F2-C-07, F2-F-02, F2-F-03, F2-J-07, F2-I-10).
- 4 new validator rules / extended rule coverage (F2-I-04 SAFE_METADATA_RUNTIME, F2-D-05 5 cache-decision values, F2-I-08 normalizer_warning, F2-I-09 scrobble_rejected).
- 1 new shared utility (`PosterAdapterUtils.toContentType()` — F2-13-E).

### REMOVED

- `MetadataCacheKeys` (5 dead methods — F2-D-04).
- `GlobalMetadataDocument` (F2-B-07).
- `FieldOwner.SKIP_SEGMENTS` (F2-skip-01).
- 6 unimplemented constants in `IntegrationApiShapes` (F2-C-02).
- 2 stale comments / section-separator hacks (F2-A-04, F2-C-05).

## Impact

- Affected specs: `integration-runtime`.
- Affected code: ~35 production files modified + ~10 new test files + 3 production deletions.
- Behavior changes: new validator rules surface previously-silent emissions; `fetchPopularLists` now shares cache across profiles; `upsertRailMembership` write is atomic; `JsonlTraceWriter` errors are observable; `AndroidTvChannelPublisher` no longer reads foreign-profile snapshots; trace settings UI hidden in release builds (already done in cluster G — referenced).
- No new dependencies. No persistent schema changes. No new trace event types beyond payload field documentation.
