## 1. Foundation
- [ ] 1.1 OpenSpec scaffold

## 2. F-F-03 — delete ProfileMetadataOverlay + ProfileResolvedDisplayDocument
- [ ] 2.1 Verify zero production callers; delete files; prune CompositionTypeShapeTest references

## 3. F-F-04 — reactive switch deferred-pending
- [ ] 3.1 ProfileManagerReactiveSwitchDuringPlaybackTest
- [ ] 3.2 Implement pendingActiveProfileId + drain on isIdle()

## 4. F-J-03 — migrate Global → GlobalContent
- [ ] 4.1 OpenSubtitlesHashIntegrationProvider migration
- [ ] 4.2 IntegrationScopeGlobalDeprecatedNoCallersTest architecture pin

## 5. F-J-02 + F-F-05 — delete legacy Account ctor + unreachable validator
- [ ] 5.1 Delete IntegrationScope.Account(providerAccountId) ctor
- [ ] 5.2 Delete ProfileBoundaryEnforcer.validateLegacyAccountScope and its caller

## 6. F-J-04 — @Deprecated ReplaceWith parity
- [ ] 6.1 IntegrationScope.Global @Deprecated gains ReplaceWith
- [ ] 6.2 DeprecatedAnnotationsHaveReplaceWithTest architecture pin

## 7. F-H-01 — checkin shape pin
- [ ] 7.1 TrackingScrobbleServiceCheckinShapeTest reflection assertion

## 8. F-H-02 — PlaybackSessionRegistry single-slot pin
- [ ] 8.1 PlaybackSessionRegistrySingleSlotTest

## 9. Sign-off
- [ ] 9.1 Re-run audits; update SIGN-OFF
