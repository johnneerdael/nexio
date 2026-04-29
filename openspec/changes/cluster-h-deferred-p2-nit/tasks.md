# Tasks: Cluster H Deferred P2 and Nit Closures

## 1. Foundation
- [ ] 1.1 OpenSpec scaffold

## 2. Stale tests batch
- [ ] 2.1 Fix F2-B-02, F2-B-03, F2-B-04, F2-D-02 (4 stale tests, 1 commit)

## 3. Dead code deletions batch
- [ ] 3.1 Delete F2-B-07 GlobalMetadataDocument
- [ ] 3.2 Delete F2-D-04 MetadataCacheKeys + F2-skip-01 FieldOwner.SKIP_SEGMENTS
- [ ] 3.3 Delete F2-C-02 six dead apiShape constants

## 4. Lane B
- [ ] 4.1 ResolvedMetadataDocumentSourceScanTest (F2-B-05)
- [ ] 4.2 Document fetchTmdbEnrichment intentional-discard pattern (F2-B-08)

## 5. Lane C
- [ ] 5.1 fetchPopularLists global-content cache key (F2-C-03)
- [ ] 5.2 Document Trakt accountCacheKey rationale (F2-C-04)
- [ ] 5.3 Replace section-separator hack with KDoc (F2-C-05)
- [ ] 5.4 Document auth-service carve-out migration path (F2-C-06)
- [ ] 5.5 MetadataAdapterUnknownPrefixTraceTest (F2-C-07)
- [ ] 5.6 SEASON_VIDEOS supports() registration (F2-C-08)
- [ ] 5.7 TMDB image cache language fragmentation note (F2-C-09)

## 6. Lane D
- [ ] 6.1 TraktIntegrationProviderRecommendationsTest stale fixture (F2-D-02)
- [ ] 6.2 F-D-01 stale-guard policy switch ObserveOnly→CacheFirst (F2-D-03)
- [ ] 6.3 RuntimeCacheDecisionTraceTest 5-value coverage (F2-D-05)
- [ ] 6.4 IntegrationSingleFlight @VisibleForTesting annotation (F2-D-06)
- [ ] 6.5 IntegrationOrphanCleanupService parallel batch (F2-D-07)
- [ ] 6.6 F2-D-09 trakt global-content backoff sharing note

## 7. Lane E
- [ ] 7.1 Kitsu enrichment cache key policyVersion (F2-E-02)
- [ ] 7.2 TvdbMetadataService legacy-path documentation (F2-E-04)
- [ ] 7.3 Document selectKitsuSynopsisField single-language behavior (F2-E-05)
- [ ] 7.4 Suppress duplicate emitLocalizationPlan for SERIES_EPISODES_LANGUAGE (F2-E-06)

## 8. Lane F
- [ ] 8.1 ProfileSwitchDeferralPolicyMultiSwitchTest (F2-F-02)
- [ ] 8.2 ProfileBoundaryCheckTraceFailEmissionTest + impl (F2-F-03)
- [ ] 8.3 Boundary audit artifact SHA propagation (F2-F-04)
- [ ] 8.4 Document GlobalLocalizedContent + GlobalEnglishImage usage status (F2-F-06)
- [ ] 8.5 TraktLibraryService.refresh staleness consistency comment (F2-F-07)
- [ ] 8.6 ProfileManager.setActiveProfile dual-write desync comment (F2-F-08)

## 9. Lane G
- [ ] 9.1 snapshot_read assertion completeness (F2-G-01)
- [ ] 9.2 AndroidTvChannelPublisher switch to observeProfileSnapshot (F2-G-02)
- [ ] 9.3 IntegrationOwnershipService.upsertRailMembership @Transaction (F2-G-03)
- [ ] 9.4 enrichContinueWatchingItemWithProvider catch documentation (F2-G-04)

## 10. Lane H
- [ ] 10.1 checkin call site ambient-fallback comment (F2-H-03)
- [ ] 10.2 PlaybackOwnerContext.traktAccount/simklAccount populate or remove (F2-H-04)
- [ ] 10.3 Skip-segment language-independence comment (F2-H-06)
- [ ] 10.4 PlayerViewModel dual unregister consolidation (F2-H-07)
- [ ] 10.5 PlaybackOwnerContext.ownerSessionId scrobble re-entry coverage (F2-S-04)

## 11. Lane I — trace mode
- [ ] 11.1 TraceRedactor F-I-01 additions reflected in manifest (F2-I-02)
- [ ] 11.2 RuntimeTraceModule comment fix (F2-I-02-nit)
- [ ] 11.3 Audit task explicit pattern for RuntimeTraceValidatorRealEmissionTest (F2-I-03)
- [ ] 11.4 RuntimeTraceInterceptorBodyGatingTest SAFE_METADATA_RUNTIME (F2-I-04)
- [ ] 11.5 EXPIRED_MISS + WRITE TraceCacheDecision validator rules (F2-I-05)
- [ ] 11.6 normalizer_warning validator rule (F2-I-08)
- [ ] 11.7 scrobble_rejected validator rule (F2-I-09)
- [ ] 11.8 JsonlTraceWriter IOException surfacing (F2-I-10)

## 12. Lane J — architecture pins
- [ ] 12.1 NoIntegrationRuntimeInjectionOutsideBoundaryTest allowlist cleanup (F2-A-03 + F2-J-04)
- [ ] 12.2 Auth-service carve-out documentation (F2-J-05)
- [ ] 12.3 TraktGlobalContentCacheKeyTest robustness pivot (F2-J-06)
- [ ] 12.4 HomeAddonHydrationFacadeBypassPinTest outside Home (F2-J-07)
- [ ] 12.5 Consolidate MetadataArchitectureBoundaryTest + MetadataProductionBoundaryTest (F2-J-08)

## 13. Trace-specific findings
- [ ] 13.1 fetchTrailer season + recap path facade routing (F2-TM-01)
- [ ] 13.2 fetchRecommendations resolver output usage (F2-TM-02)
- [ ] 13.3 OrganizationDetailViewModel facade routing (F2-TM-03)
- [ ] 13.4 Premium poster audit golden adapter-output assertion (F2-T13-A)
- [ ] 13.5 Premium poster selectedProvider case normalization (F2-T13-C)
- [ ] 13.6 fetchTvEpisodeEnrichment resolver_schedule emit (F2-T-02-nit)

## 14. Cleanup batch
- [ ] 14.1 Documentation + comment cleanups (F2-A-02, F2-A-04, F2-A-05, F2-B-06)
- [ ] 14.2 Shared poster adapter utility (F2-13-E)
- [ ] 14.3 TvdbSettingsNoAdvancedToggleTest type warning (F2-meta-01)

## 15. Sign-off
- [ ] 15.1 Re-run audits, update SIGN-OFF, push
