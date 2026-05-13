# Typed Cache Stores Design

Date: 2026-05-14

## Scope

This design covers only two cache stores:

- `HydratedHomeOverlayStore`
- `MediaClipStore`

It intentionally excludes `DurableArtworkDecisionCache` and thumbnail decision persistence. Heap analysis showed thumbnail decision cardinality is real, not duplicate data, and changing it carries higher playback/detail artwork risk than this pass should take.

## Context

The large SharedPreferences migration moved several caches into file-backed JSON. Device verification on `192.168.50.98` confirmed the targeted SharedPreferences XML files are now small, but heap analysis still showed retained memory from generic JSON trees:

- `FileBackedJsonObjectStore$SharedState`: largest retained instance about 1.75 MiB.
- `com.google.gson.JsonObject` / `LinkedTreeMap` / `LinkedTreeMap$Node`: several MiB across the heap.

The two best low-risk targets are:

- `HydratedHomeOverlayStore`: current `entries.json` has 831 entries, including 697 alias entries shaped as tiny `JsonObject` wrappers.
- `MediaClipStore`: records already have a typed `StoredMediaClipRecord` model, but the store persists and reads them through `JsonObject`.

## Goals

- Remove these two stores from generic `FileBackedJsonObjectStore` resident state.
- Preserve existing public behavior and call sites.
- Keep atomic file writes and streaming JSON reads/writes.
- Preserve migration from current v1 file-backed data and legacy SharedPreferences.
- Clear old storage only after a successful v2 write.
- Keep malformed-entry handling tolerant: skip bad entries, retain valid entries, do not crash home startup.

## Non-Goals

- Do not change artwork decision or thumbnail cache behavior.
- Do not change hydration rules, overlay selection, stale marking semantics, or media clip ranking.
- Do not introduce a database or mmap store for these small caches.
- Do not optimize `CatalogDiskCacheStore`, addon manifests, or metadata cache in this pass.

## Recommended Approach

Use new versioned v2 paths and typed in-memory models:

- `files/hydrated-home-overlay-v2/entries.json`
- `files/media-clip-store-v2/entries.json`

The v2 paths give a clean migration boundary. They avoid mixed schemas under the old v1 paths and make rooted-device validation straightforward: v2 files exist, old v1 files are empty/deleted or no longer used, and heap no longer shows these stores under `FileBackedJsonObjectStore$SharedState`.

## HydratedHomeOverlayStore Design

Create a dedicated typed file store for hydrated overlays. The v2 file shape is:

```json
{
  "schemaVersion": 2,
  "aliases": {
    "alias::en::policy:1::movie:tmdb:550": "canonical:TMDB:550:type:MOVIE:lang:en:policy:1"
  },
  "overlays": {
    "canonical:TMDB:550:type:MOVIE:lang:en:policy:1": {
      "schemaVersion": 1,
      "value": {}
    }
  }
}
```

Runtime state is:

- `aliases: LinkedHashMap<String, String>`
- `overlays: LinkedHashMap<String, HydratedHomeOverlay>`

`upsert` writes the overlay into `overlays` and alias keys into `aliases`. `readForItemKeys`, `readByCanonicalIdentity`, `markStaleAll`, and `readOverlayForItemKey` read from the typed maps instead of repeatedly fetching `JsonObject` wrappers.

The existing overlay payload wrapper keeps `schemaVersion = 1` for the overlay record value, because this change is about storage layout, not the `HydratedHomeOverlay` domain model. Missing `stableIdsSnapshot` and `settingsSignature` still normalize to safe defaults when reading migrated old data.

## MediaClipStore Design

Create a dedicated typed file store for media clips. The v2 file shape is:

```json
{
  "schemaVersion": 2,
  "records": {
    "media-clip:<hash>": {}
  }
}
```

Runtime state is:

- `records: LinkedHashMap<String, StoredMediaClipRecord>`

`storeCandidates` converts candidates to `StoredMediaClipRecord`, de-duplicates by key, updates the typed map, and streams the full records map to disk atomically. `getCandidates` scans typed records directly and keeps the existing matching, TTL, stale-hit, sorting, and trace behavior.

## Migration

On first access, each store follows the same source order:

1. Load v2 if present and valid.
2. If v2 is missing, migrate from the current v1 file-backed `entries.json`.
3. If no v1 data exists, migrate from legacy SharedPreferences.

For v1 migration:

- `HydratedHomeOverlayStore` reads `overlay::` entries as `{schemaVersion, value}` and `alias::` entries as `{overlayKey}`.
- `MediaClipStore` reads `media-clip:` entries and decodes each `JsonObject` to `StoredMediaClipRecord`.
- Existing v2 entries win over older v1/legacy entries if both are present.
- Bad entries are skipped and counted only in tests/logs if diagnostics are added.

Old v1 file data and legacy SharedPreferences are cleared only after v2 is written successfully. If the write fails, the old source remains available for the next startup.

## Error Handling

- Invalid top-level v2 JSON makes the typed store behave as empty and leaves v1/legacy data available if v2 was never successfully written.
- Invalid individual entries are skipped.
- Atomic writes use temp file plus rename, following the existing project rule for file-backed JSON.
- No read-after-write verification is added.
- For JSON files above 50 KiB, reads and writes use `JsonReader` / `JsonWriter` over buffered streams rather than `readText`, `writeText`, or `gson.toJson(value): String`.

## User-Facing Impact

The intended user-facing behavior is no visible change. Home overlays should appear the same, stale overlay updates should behave the same, and media clip/trailer candidates should be returned with the same ranking and stale-hit rules.

Expected performance impact:

- Lower retained heap because alias strings and typed records replace generic Gson tree objects.
- Less per-query allocation in `MediaClipStore.getCandidates`, because records no longer need to be deep-copied from `JsonObject` and parsed on every query.
- Similar or slightly better cold-start cost. The stores still parse their full file on first access, but into compact typed records instead of Gson trees.

## Testing

Add or update tests for:

- v2 round-trip persistence for hydrated overlays.
- v2 round-trip persistence for media clips.
- v1 file-backed migration for both stores.
- legacy SharedPreferences migration for both stores.
- malformed v2 entries are skipped without crashing.
- migration does not overwrite existing newer v2 entries.
- stale overlay behavior remains unchanged.
- media clip identity, scope, language, TTL, stale-hit, and sorting behavior remains unchanged.

Device verification should:

- Install debug APK on `192.168.50.98`.
- Launch `com.nexiodebug.tv`, select a profile, and wait for home to load.
- Verify v2 files exist.
- Verify old targeted v1 files are empty/deleted or no longer grow for these two stores.
- Capture a post-home heap and confirm `FileBackedJsonObjectStore$SharedState` no longer retains hydrated overlay or media clip data.

## Rollout

This is a local data-format migration. No remote contract changes are required. If v2 loading fails before old sources are cleared, the app can retry migration from v1/legacy on the next run. Once v2 is written and old sources are cleared, rollback to a build that only knows v1 would lose these two caches but can rebuild them from network/local providers.
