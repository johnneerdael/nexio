# Change: Migrate artwork cache index to Room

## Why

Nexio already stores integration, rail, owner, identity, and provider cache data in Room, and keeps
image bytes as files. The startup artwork gap is narrower: first paint can have warm artwork bytes
on disk but still render placeholders because durable display refs often point at artwork decisions
or legacy strings instead of directly addressable assets.

The current JSON-backed artwork decision and asset-record stores are the best target for SQLite.
They are key/index stores, need reverse lookup from decision to latest asset, and need tolerant
repair of old decision refs after restart. Moving this layer to Room gives Nexio the POV-like
durability where it matters without moving whole home snapshots or image bytes into SQLite.

## What Changes

- Add a Room-backed artwork cache index for persisted artwork decisions and asset records.
- Preserve `HomeCatalogSnapshotStore` and `ResolvedDisplaySnapshotStore` as bounded JSON
  first-paint snapshots.
- Preserve `ArtworkAssetDiskCache` as the owner of raw artwork bytes under `cache/artwork-assets`.
- Preserve Coil as the renderer/request image cache under `cache/image_cache`.
- Import existing JSON artwork decisions and asset records into Room without deleting the old files
  immediately.
- Prefer direct `assetKey` rendering when snapshots contain durable asset refs.
- Recover legacy decision refs through `decisionKey -> latest readable asset` before network.
- Add bounded background repair and diagnostics for missing records, missing files, and orphaned
  decisions.

## Impact

- Affected app: `app`
- Affected areas:
  - `app/src/main/java/com/nexio/tv/core/artwork/`
  - `app/src/main/java/com/nexio/tv/core/image/`
  - `app/src/main/java/com/nexio/tv/core/di/`
  - `app/src/main/java/com/nexio/tv/data/local/`
  - `app/src/test/`
- Affected spec:
  - `artwork-cache-pipeline`
- Rollout:
  - compatibility import from JSON to Room
  - JSON fallback remains available during the first rollout
  - bytes stay file-backed and are not stored in SQLite
  - repair runs after home can paint, not as a first-paint blocker
