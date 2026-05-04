- [ ] Add OpenSpec deltas for the unified artwork cache and rendering pipeline.
- [ ] Phase 1: add canonical artwork model types and compatibility projection helpers:
  `ArtworkBundle`, sealed `ArtworkDisplayRef`, `ArtworkOwnerKey`, runtime `ArtworkCandidate`,
  persisted-safe `ArtworkDecision`, `PersistedArtworkCandidate`, `PersistedProviderTemplate`, and
  `ArtworkAssetRecord`.
- [ ] Phase 1: add tests proving legacy string fields are derived only from `ArtworkDisplayRef` and
  produce `nexio-artwork://` or `nexio-placeholder://` values, never raw provider URLs.
- [ ] Phase 2: add `ArtworkRouter` candidate creation for premium, primary, current first-paint
  preview, other preview, and placeholder artwork across poster/backdrop/logo/thumbnail types.
- [ ] Phase 2: add provider capability checks for premium artwork so unsupported ID types are
  rejected with trace reasons instead of producing invalid provider URLs.
- [ ] Phase 2: update FieldResolver/display resolution so artwork fields consume
  `ArtworkRouter` decisions and provider adapters do not directly overwrite final artwork fields.
- [ ] Phase 3: add `ArtworkDecisionCache` keyed by owner key, image type, active artwork provider
  policy, premium settings hash, credential hash, `imageLanguage=en`, and policy version.
- [ ] Phase 3: support `PreviewItem` owner keys before canonical identity and supersede them with
  canonical decisions after ID resolution while retaining preview candidates as fallback.
- [ ] Phase 4: add `ArtworkAssetRepository`, `ArtworkSourceMaterializer`, and
  `ArtworkAssetDiskCache` with cache-relative asset records and stale support.
- [ ] Phase 4: route TMDB, TVDB, Kitsu, addon/rail preview, RPDB, and Top-Posters artwork fetches
  through `IntegrationRuntime` with explicit `CacheFirst` image policies.
- [ ] Phase 5: add `nexio-artwork://asset/{assetKey}`,
  `nexio-artwork://decision/{decisionKey}`, and `nexio-placeholder://{type}` Coil support.
- [ ] Phase 5: add tests proving Coil does not direct-fetch raw metadata artwork URLs from TMDB,
  TVDB, Kitsu, RPDB, Top-Posters, addon preview, or rail preview sources.
- [ ] Phase 6: migrate Home cards and hydrated overlays to canonical artwork refs while preserving
  derived legacy string compatibility.
- [ ] Phase 7: migrate Detail screen artwork to canonical artwork refs while preserving navigation
  compatibility.
- [ ] Phase 8: migrate Continue Watching and Player metadata artwork to canonical artwork refs.
- [ ] Phase 9: add metadata execution report/audit output for selected provider, source role,
  decision key, asset key, runtime `apiShapeId`, asset cache decision, network execution, Coil
  model, and raw remote URL boundary status.
- [ ] Phase 10: add invalidation tests proving premium provider, settings, style, credential,
  provider capability, and artwork policy changes invalidate artwork decisions/assets without
  invalidating TMDB/TVDB/Kitsu metadata caches or identity mappings.
- [ ] Phase 11: add architecture tests that ban new raw remote artwork URL authors in metadata UI
  paths and track remaining legacy string consumers.
- [ ] Phase 12: after all surfaces and persisted snapshots are migrated, raise legacy string
  deprecations and remove compatibility-only raw URL exemptions.
- [ ] Validate the OpenSpec change with `openspec validate add-unified-artwork-pipeline --strict`.
