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
