# Type hydrated overlay and media clip cache stores

## Why

The large SharedPreferences migration removed the most severe cache-retention violation, but heap analysis still shows generic Gson tree retention from file-backed caches. The two lowest-risk targets are `HydratedHomeOverlayStore` and `MediaClipStore`:

- Hydrated overlay aliases are mostly tiny `JsonObject` wrappers around a string.
- Media clip entries already have a typed record model, but are persisted and read through `JsonObject`.

## What Changes

- Add versioned v2 file formats for hydrated home overlays and media clips.
- Replace resident `JsonObject` maps for these stores with typed maps.
- Migrate from current v1 file-backed JSON and legacy SharedPreferences.
- Clear v1/legacy data only after v2 writes successfully.
- Keep thumbnail/artwork decision persistence unchanged.

## Impact

Home overlay and media clip behavior should remain unchanged. The expected impact is lower retained heap and lower per-query allocation for media clip reads. Existing cached data survives through migration.
