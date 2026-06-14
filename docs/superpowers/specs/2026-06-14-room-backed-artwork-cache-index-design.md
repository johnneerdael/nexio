# Room-Backed Artwork Cache Index Design

Date: 2026-06-14

## Scope

This design migrates Nexio's artwork decision cache and artwork asset-record index to Room/SQLite.
It does not move image bytes, home first-paint snapshots, resolved-display snapshots, or Coil's
image cache into SQLite.

## Context

Kodi with Arctic Fuse 3 and POV appears fully populated at startup because list metadata, provider
metadata, and artwork decisions are already durable before the skin paints. Kodi also has a texture
cache for rendered images. Nexio has similar pieces, but the weak point is narrower than "all caches
should be SQLite":

- Integration, rail, owner, provider backoff, identity, and external-id caches already live in
  `IntegrationCacheDatabase`.
- Home startup structure is restored from bounded JSON snapshots.
- Resolved display metadata is restored from bounded JSON snapshots.
- Raw artwork bytes are stored as files under `cache/artwork-assets`.
- Coil has a memory cache and a 200 MB disk cache under `cache/image_cache`.
- Artwork decisions and asset records are currently JSON-backed. On the inspected device, the
  decision cache had thousands of decisions while the asset-record file had only a few records
  despite many cached image files.

The user-visible failure is that a warm restart can still show brown poster placeholders because
first paint may hold legacy decision refs or decision-only runtime refs rather than durable
`assetKey` refs that point directly at readable cached bytes.

## Goals

- Make artwork decisions and asset records durable, queryable, and repairable through Room.
- Preserve fast first paint by allowing `nexio-artwork://asset/<assetKey>` to render directly from
  `ArtworkAssetDiskCache` when the file exists.
- Allow legacy `nexio-artwork://decision/<decisionKey>` refs to recover a latest valid local asset
  without network.
- Import existing JSON decision and asset-record files without deleting them immediately.
- Keep startup repair bounded and non-blocking so the home screen can paint before cache
  reconciliation completes.
- Preserve app-owned raw artwork files and Coil as the renderer/request cache.

## Non-Goals

- Do not store image bytes in SQLite.
- Do not migrate `HomeCatalogSnapshotStore` or `ResolvedDisplaySnapshotStore` to SQLite.
- Do not replace Coil's memory or disk cache.
- Do not refetch primary metadata solely because artwork cache storage changes.
- Do not run a full cache sweep on the critical startup path.

## Recommended Architecture

Add a Room-backed artwork cache database owned by the artwork layer. The database should replace or
wrap the current JSON-backed `DurableArtworkDecisionCache` and `DurableArtworkAssetRecordStore`.

Primary tables:

- `artwork_decisions`
  - primary key: `decisionKey`
  - stores the safe persisted `ArtworkDecision` payload, selected candidate, rejected candidates,
    owner key, image type, provider/settings/credential hashes, policy version, created/expiry/stale
    timestamps, and invalidation state.
- `artwork_asset_records`
  - primary key: `assetKey`
  - stores `decisionKey`, provider, image type, image language, cache-relative file path, mime type,
    byte count, source hash, policy version, fetched timestamp, expiry timestamp, and stale-until
    timestamp.

Indexes:

- `decisionKey`
- `assetKey`
- `(decisionKey, fetchedAtMs)` for latest-asset lookup
- expiry/stale timestamps for cleanup and diagnostics
- provider/image-type indexes only if implementation or reports need them

The database stores safe metadata and pointers only. `ArtworkAssetDiskCache` remains responsible for
deterministic file paths and readable-byte checks.

## Data Flow

When artwork is resolved or fetched, Nexio writes three durable artifacts:

1. The selected artwork decision into `artwork_decisions`.
2. The image bytes into `cache/artwork-assets/...`.
3. The asset record into `artwork_asset_records`.

Snapshots and UI-facing models should only promote a durable asset URI after the asset-record write
succeeds. If bytes are written but the record write fails, the image can render for the current
session, but the app should not persist a `nexio-artwork://asset/<assetKey>` pointer that cannot be
repaired after restart.

Startup path:

1. Load home and resolved-display JSON snapshots as today.
2. Prefer typed `RuntimeAsset.assetKey` for poster/backdrop/logo/thumbnail refs.
3. `NexioArtworkFetcher` checks `ArtworkAssetDiskCache.getExistingFile(assetKey)`.
4. If the file exists and is readable, render from disk without decision lookup or network.
5. If the file is missing, use `assetKey -> decisionKey` from Room to rehydrate from the stored
   decision.
6. If a snapshot still contains a decision URI, use `decisionKey -> latest valid asset` from Room;
   render the asset if the file is readable.
7. Only execute network when neither a readable asset nor a valid stored decision can satisfy the
   request.

## Migration

On first access after upgrade, import existing JSON decisions and JSON asset records into Room.
Migration should be tolerant:

- Import valid entries.
- Skip malformed entries and count them for diagnostics.
- Leave source JSON files in place for at least one compatibility window.
- Record a migration-complete marker only after Room import and required indexes are available.
- Avoid deleting old JSON until a later cleanup change or app version proves the Room-backed path is
  stable.

SQLite open or migration failure should fall back to the existing JSON stores for that app session.
The fallback must emit a trace event but must not block the home screen from rendering.

## Repair

Repair is background work, not a startup gate. After home is visible, a bounded repair job may:

- Promote legacy decision refs to asset refs when Room has a latest readable asset.
- Recreate missing asset records when enough metadata exists to prove a deterministic `assetKey`
  file is valid.
- Mark DB records orphaned when their files are missing and rehydration fails.
- Mark stale decisions after artwork provider/settings changes without eagerly deleting files.
- Prune expired records and files within configured cache bounds.

The recovery chain rule is strict: do not persist a durable pointer unless the app can recover it
after process death.

## Texture And Image Cache Policy

Nexio should not add a second persistent decoded-bitmap or GPU texture cache. Android process memory
and GPU textures disappear on restart, so the durable strategy is stable keys plus disk-backed bytes:

- `cache/artwork-assets` stores canonical app-owned artwork bytes.
- Coil's `cache/image_cache` stores renderer/request cache entries.
- Coil memory cache handles same-session reuse.
- Stable `nexio-artwork://asset/<assetKey>` models let both the app-owned file cache and Coil's disk
  cache hit after restart.

If measurements later prove decode time, not lookup/network, is the bottleneck, the next step should
be Coil request-key tuning or selective prewarming, not a custom texture database.

## Error Handling

- Asset file exists, DB record missing: render from deterministic `assetKey` path when possible, and
  enqueue bounded repair.
- DB record exists, file missing: rehydrate through `decisionKey`; if that fails, avoid hot retry
  loops and fall back to decision path or placeholder.
- Decision exists, no asset record: materialize the decision, write bytes, then write the asset
  record before durable promotion.
- Decision missing, asset record exists: render only if the file is readable; otherwise mark orphaned.
- SQLite unavailable: use JSON fallback for the session and emit diagnostics.
- Provider/settings change: mark affected decisions stale; keep prior local artwork visible while
  refresh happens.

## Testing

Unit tests:

- DAO round-trip for decisions and asset records.
- latest valid asset lookup by decision key.
- stale/expiry query behavior.
- JSON import skips bad entries without aborting the full import.
- asset URI promotion only happens after asset-record persistence succeeds.

Repository tests:

- decision fetch writes decision, bytes, and asset record.
- asset rehydration works after recreating Room-backed stores.
- legacy decision refs recover existing assets through the Room index.
- missing file plus valid DB record rehydrates instead of staying permanently blank.
- SQLite failure uses JSON fallback for that session.

Device verification:

- Populate home fully once, restart Nexio, select a profile, and observe first visible rails after
  5-10 seconds.
- Confirm higher artwork disk-hit counts and lower startup artwork network execution.
- Confirm fewer brown placeholders in first visible rails.
- Confirm `cache/artwork-assets` and `cache/image_cache` stay bounded.
- Confirm old JSON files are imported but not deleted.

## Rollout

Ship this as an internal data-store migration with compatibility fallback. The old JSON files remain
available during the first rollout so rollback builds can still render from the previous caches. A
later cleanup can remove JSON fallback only after device verification shows Room import, asset-first
first paint, and repair metrics are stable.
