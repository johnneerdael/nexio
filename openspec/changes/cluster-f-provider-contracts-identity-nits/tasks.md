# Tasks

## 1. Foundation
- [ ] 1.1 OpenSpec scaffold

## 2. Lane B — F-B-01 PREVIEW provenance
- [ ] 2.1 FieldResolverPreviewProvenanceTest (red)
- [ ] 2.2 Route PREVIEW through FieldResolver.resolveWithPreview; delete toResolvedDocument()

## 3. Lane B — F-B-05 contentId in trace
- [ ] 3.1 FieldResolverContentIdInTraceTest (red)
- [ ] 3.2 Thread requestContentId through resolve(...) + resolveWithPreview(...); update 2 facade call sites

## 4. Lane B — F-B-06 negative-cache identity lookups
- [ ] 4.1 MetadataIdentityResolverNegativeCacheTest (red)
- [ ] 4.2 Read+write NEGATIVE mappings in MetadataIdentityResolver.resolve

## 5. Lane B — F-B-07 normalizer TV warning
- [ ] 5.1 MetadataRequestNormalizerTvWarningTest (red)
- [ ] 5.2 Emit metadata.normalizer_warning when ContentType.TV coerces to SERIES

## 6. Lane B — F-B-02 fallback constructor deletion
- [ ] 6.1 FieldResolverInjectionContractTest (red)
- [ ] 6.2 Delete defaultMetadataRouterFacadeForManualConstruction in MetaDetailsViewModel
- [ ] 6.3 Replace runCatching fallback in HomeProviderLocalizedMetadataOverlay

## 7. Lane C — F-C-03 anime prefix parsers
- [ ] 7.1 MetadataProviderTargetIdsAnimePrefixTest (red)
- [ ] 7.2 Add mal/anilist/anidb/imdb parsers to MetadataProviderTargetIds

## 8. Lane C — F-C-02 apiShapeId registry coverage
- [ ] 8.1 Add missing TraktApiShapes / SimklApiShapes / TvdbApiShapes constants
- [ ] 8.2 Replace literals in TraktIntegrationProvider (~45 sites)
- [ ] 8.3 Replace literals in SimklIntegrationProvider (5 sites)
- [ ] 8.4 Replace literals in TvdbIntegrationProvider (5 sites)
- [ ] 8.5 IntegrationApiShapeRegistryCoverageTest architecture pin

## 9. Lane C — F-C-04 premium poster adapters
- [ ] 9.1 Add RPDB, TOP_POSTERS to MetadataPrimaryProvider
- [ ] 9.2 RpdbMetadataProviderAdapter + TopPostersMetadataProviderAdapter
- [ ] 9.3 PremiumPosterAdapterRegistrationTest pin

## 10. Lane C — F-C-05 stable poster cache keys
- [ ] 10.1 PosterCacheKeyStableHashTest (red)
- [ ] 10.2 Switch to SHA-256 hex prefix in PosterRatingsUrlResolver

## 11. Lane C — F-C-06 global-content cache key dedup
- [ ] 11.1 TraktGlobalContentCacheKeyTest (red)
- [ ] 11.2 Add globalContentCacheKey helper; migrate trending/popular/recommended/calendar sites

## 12. Sign-off
- [ ] 12.1 Re-run audits; update SIGN-OFF; push
