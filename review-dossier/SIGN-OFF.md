# Shared Resolution Open Findings Sign-Off

- **Audit date:** 2026-05-06
- **Worktree:** `.worktrees/shared-resolution-p0-bypass-removal`
- **Decision:** **APPROVED**

## Verdict

All open shared-resolution bypass findings from the refreshed 2026-05-06 audit are covered by the single closure plan and remediated on this branch.

## Closed Scope

- P0 account/profile correctness: provider mutation envelopes carry account scope; WatchProgress/Continue Watching writes use captured profile sessions; detail UI no longer performs direct TMDB ID resolution.
- P1/P2 ownership migration: detail/home metadata, identity bridging, ratings, trailers, skip segments, artwork, localization, and screensaver display data route through the shared owner systems.
- Final-review reopen items were also closed: WatchProgress remote outbox scoping, Kitsu secondary sidecar removal, home hero/carousel safe artwork models, person/company UI bridge removal, and stale sign-off state.

## Verification

Focused final gate passed:

```bash
./gradlew :app:compileUniversalDebugKotlin :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest" \
  --tests "com.nexio.tv.architecture.NoUiTmdbEnsureIdArchitectureTest" \
  --tests "com.nexio.tv.architecture.NoDetailUiTmdbEnsureIdArchitectureTest" \
  --tests "com.nexio.tv.architecture.WatchProgressProfileScopeArchitectureTest" \
  --tests "com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest" \
  --tests "com.nexio.tv.architecture.MetadataRouterBoundaryTest" \
  --tests "com.nexio.tv.architecture.SkipIntroRepositoryCanonicalSurfaceTest" \
  --tests "com.nexio.tv.data.repository.MetadataDisplayRepositoryTest" \
  --tests "com.nexio.tv.ui.screens.detail.MetaDetailsResolvedDocumentTest" \
  --tests "com.nexio.tv.ui.screens.detail.MetaDetailsKitsuAdvancedMetadataTest" \
  --tests "com.nexio.tv.ui.screens.detail.MetaDetailsTvdbAdvancedMetadataTest" \
  --tests "com.nexio.tv.ui.screens.home.HomeResolvedSurfacePublishingTest" \
  --tests "com.nexio.tv.core.metadata.router.resolver.RatingResolverTest" \
  --tests "com.nexio.tv.core.metadata.router.resolver.TrailerResolverPlaybackTest" \
  --tests "com.nexio.tv.core.metadata.router.resolver.SkipSegmentResolverTest" \
  --tests "com.nexio.tv.data.integration.metadata.LocalizationResolverPolicyTest" \
  --tests "com.nexio.tv.ui.screensaver.IdleTrailerScreensaverSessionTest" \
  --tests "com.nexio.tv.data.repository.WatchProgressRepositoryProviderRoutingTest"
```

Static scans are clean for direct YouTube URL construction, player `SkipIntroRepository` calls, detail/UI TMDB identity bridge calls, WatchProgress active-profile account derivation, and direct detail secondary sidecars. The only `trailerService.` hits are the two approved MainActivity screensaver transport calls documented in `shared-resolution-bypass-audit.md`.
