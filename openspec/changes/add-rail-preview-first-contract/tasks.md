# Tasks

- [ ] Add shared rail preview domain models and conversion helpers.
- [ ] Add `SourceRole.RAIL_PREVIEW` support in metadata candidate and field selection tracing.
- [ ] Add Room persistence for rail preview records while keeping rail membership and media identity records separate.
- [ ] Add provider-specific no-network preview mappers for Trakt, MDBList, TMDB, Kitsu, and Simkl.
- [ ] Wire built-in synthetic rail refresh to persist and publish rail previews before metadata hydration.
- [ ] Add visible/focused/adjacent hydration scheduling that routes previews through `MetadataRouter` only after first paint.
- [ ] Add metadata execution report fields and scenarios for built-in rail first paint and visible hydration.
- [ ] Add mapper, storage, hydration, resolver, no-network first-paint, and audit golden tests.
- [ ] Carry first-paint source provenance on the shared Home `MetaPreview` model.
- [ ] Trace rail-derived Home first paint as `RAIL_PREVIEW` through the existing first-paint tracer.
- [ ] Carry rail preview stable IDs through existing `MetadataSourceContext` requests.
- [ ] Replace rail-specific hydration helper logic with source-neutral preview hydration planning or the existing focus/preload entrypoint.
- [ ] Add architecture tests preventing provider DTOs, rail mappers, or rail-specific hydration schedulers from entering Home renderer code.

## Production Wiring Guard Pass

- [ ] Wire Trakt discovery rows through `TraktRailPreviewMapper` before shared `MetaPreview` first paint.
- [ ] Wire MDBList discovery rows through `MDBListRailPreviewMapper` before shared `MetaPreview` first paint.
- [ ] Wire TMDB discovery rows through `TmdbRailPreviewMapper` before shared `MetaPreview` first paint.
- [ ] Wire Kitsu discovery rows through `KitsuRailPreviewMapper` before shared `MetaPreview` first paint.
- [ ] Wire Simkl discovery rows through `SimklRailPreviewMapper` before shared `MetaPreview` first paint.
- [x] Add architecture guards proving built-in rail providers enter Home through the shared preview bridge.
- [x] Prove built-in rail audit scenarios construct `RailItemPreview` and convert through shared `MetaPreview` first paint.
- [x] Prove visible hydration target selection is source-neutral for addon and built-in rail previews.
