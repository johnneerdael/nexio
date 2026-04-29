# Tasks

## 1. Foundation
- [ ] 1.1 OpenSpec scaffold

## 2. P0 — F2-D-01 Trakt global-content scope migration
- [ ] 2.1 Boundary audit scenario for authenticated Trakt global-content (red)
- [ ] 2.2 Migrate 6 Trakt global-content specs to GlobalContent scope + null profileContext

## 3. P0 — F2-J-01 Home addon hydration through facade
- [ ] 3.1 Migrate 2 Home files; verify AddonFirstPaintShapeArchitectureTest passes

## 4. P1 — Lane H scrobble enforcement
- [ ] 4.1 TraktScrobbleServiceProfileBoundaryTest + SimklScrobbleServiceProfileBoundaryTest (red)
- [ ] 4.2 Convert checkScrobbleBoundary to Boolean; gate enqueueAndDrain on it
- [ ] 4.3 TrackingProgressServiceProfileBoundaryRecheckTest (red)
- [ ] 4.4 Add result-time assertCanWriteProfileState in TrackingProgressService

## 5. P1 — F2-B-01 Audit golden fix
- [ ] 5.1 Suppress ROUTING_ID_TYPE_CONFLICT for routes originating as ITEM_TYPE_SERIES/MOVIE

## 6. P1 — F2-A-01 Backoff clear on success
- [ ] 6.1 IntegrationCallRuntimeBackoffClearTest (red)
- [ ] 6.2 Add backoffManager.clear() to doCallInternal + openInternal success branches

## 7. P1 — F2-C-01 loadMovieCollection runtime coverage
- [ ] 7.1 TmdbCollectionRuntimeCoverageTest (red)
- [ ] 7.2 Migrate loadMovieCollection to runtime.get pattern

## 8. P1 — F2-D-08 deleteOwnedMedia atomicity
- [ ] 8.1 Reorder DAO delete before blob delete

## 9. P1 — Lane E localization
- [ ] 9.1 TvdbLanguageMapperFallbackDiagnosticTest (red)
- [ ] 9.2 Add isCollapsedToFallback field to TvdbLanguageMapper result + propagate
- [ ] 9.3 Extend RuntimeTraceValidatorRealEmissionTest with TVDB episode-bundle scenario

## 10. P1 — Lane I trace mode
- [ ] 10.1 TraceBuildInfoGitShaTest (red)
- [ ] 10.2 Wire BuildConfig.GIT_SHA into TraceBuildInfo
- [ ] 10.3 SecondaryDoesNotOverwritePrimaryNoFalsePositiveTest (red)
- [ ] 10.4 Fix SecondaryDoesNotOverwritePrimary inverted semantics
- [ ] 10.5 Gate trace settings UI on BuildConfig.IS_DEBUG_BUILD

## 11. P1 — Lane J pins
- [ ] 11.1 CheckinCallerOwnerProfileIdContractTest pin (red)
- [ ] 11.2 Thread ownerProfileId to checkin call sites
- [ ] 11.3 ResolvedMetadataDocumentConstructionContractTest pin

## 12. Sign-off
- [ ] 12.1 Re-run all 4 audits + 17 pin/test verifications; update SIGN-OFF; push
