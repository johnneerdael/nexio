## 1. Implementation

- [ ] Add a typed v2 hydrated overlay file store with streaming reads/writes and atomic replacement.
- [ ] Migrate `HydratedHomeOverlayStore` to v2 typed aliases and overlays.
- [ ] Add a typed v2 media clip file store with streaming reads/writes and atomic replacement.
- [ ] Migrate `MediaClipStore` to v2 typed records.
- [ ] Preserve v1 file-backed and legacy SharedPreferences migration paths.
- [ ] Clear old v1/legacy sources only after successful v2 persistence.

## 2. Verification

- [ ] Add v2 round-trip tests for both stores.
- [ ] Add v1 migration tests for both stores.
- [ ] Keep legacy SharedPreferences migration tests passing for both stores.
- [ ] Add malformed-entry skip tests for both stores.
- [ ] Run targeted store tests.
- [ ] Install debug APK, select profile, let home settle, and verify v2 files on device.
- [ ] Capture a post-home heap and verify these stores no longer retain data through `FileBackedJsonObjectStore$SharedState`.
