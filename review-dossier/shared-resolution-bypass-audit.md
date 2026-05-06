# Shared Resolution Bypass Audit

## Executive Verdict

PASS

Fresh audit date: 2026-05-06

The refreshed shared-resolution bypass findings have been remediated on this branch. No confirmed production bypass remains for metadata ownership, identity bridging, final field selection, ratings, trailers, skip segments, artwork display, localization, profile state, account mutation scope, Continue Watching, or screensaver display data.

## Summary

| Category | Confirmed bypasses | Approved boundaries | False positives | P0 | P1 | P2 |
|---|---:|---:|---:|---:|---:|---:|
| Metadata authority bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Identity bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Field merge bypass | 0 | 0 | 0 | 0 | 0 | 0 |
| Artwork bypass | 0 | 1 | 1 | 0 | 0 | 0 |
| Rating bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Trailer bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Skip bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Screensaver bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| CW/profile bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Account boundary bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Runtime bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Localization bypass | 0 | 1 | 0 | 0 | 0 | 0 |

## Confirmed Bypasses

No confirmed production bypasses remain after the packet sequence in this branch.

## Fixed Findings

| Severity | Category | Previous evidence | Status | Required owner | Verification |
|---|---|---|---|---|---|
| P0 | Account boundary bypass | Trakt/Simkl mutation envelopes lacked provider and credential hash | fixed at `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt:26` | IntegrationRuntime Account(profileId, provider, credentialHash) | ProviderMutationEnvelopeAccountScopeTest |
| P0 | CW/profile bypass | WatchProgress APIs selected active profile at persistence time | fixed at `app/src/main/java/com/nexio/tv/domain/repository/WatchProgressRepository.kt:12` and `app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt:318` | ProfileBoundaryEnforcer and profile-scoped CW repository | WatchProgressProfileScopeArchitectureTest, WatchProgressRepositoryProviderRoutingTest |
| P0 | Identity bypass | MetaDetailsViewModel enrichment called TmdbService.ensureTmdbId and hydrated person/company TMDB IDs from UI | fixed at `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1107` | StableIdBundleResolver / CanonicalIdentityResolver | NoDetailUiTmdbEnsureIdArchitectureTest, NoUiTmdbEnsureIdArchitectureTest, SharedResolutionOpenFindingsArchitectureTest |
| P1 | Metadata authority bypass | MetadataRouterFacade trace-only sidecar methods and display-level Kitsu secondary fetches | fixed at `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt:240` and `app/src/main/java/com/nexio/tv/data/integration/metadata/KitsuMetadataProviderAdapter.kt:118` | MetadataRouter + ProviderPlanRunner | SharedResolutionOpenFindingsArchitectureTest, MetadataRouterBoundaryTest, MetaDetailsKitsuAdvancedMetadataTest |
| P1 | Field merge bypass | Detail/home manually merged final display fields from provider sidecars | fixed at `app/src/main/java/com/nexio/tv/data/repository/MetadataDisplayRepository.kt:78` and `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:484` | ResolverOrchestrator + FieldResolver | MetaDetailsResolvedDocumentTest, HomeResolvedSurfacePublishingTest |
| P1 | Rating bypass | Detail/home called MDBList/custom/episode rating repositories directly | fixed at `app/src/main/java/com/nexio/tv/data/repository/DetailRatingDisplayRepository.kt:76` | RatingResolver | RatingResolverTest |
| P1 | Trailer bypass | Detail/home/screensaver called TrailerService or built YouTube playback URLs | fixed at `app/src/main/java/com/nexio/tv/core/metadata/router/resolver/TrailerResolver.kt:74` | TrailerResolver | TrailerResolverPlaybackTest |
| P1 | Skip bypass | Player called SkipIntroRepository directly | fixed at `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt:475` | SkipSegmentResolver | SkipSegmentResolverTest, SkipIntroRepositoryCanonicalSurfaceTest |
| P1 | Artwork bypass | Metadata UI passed raw provider URL fields to Coil | fixed at `app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt:103`, `app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt:196`, and `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt:91` | ArtworkRouter + ArtworkAssetRepository | RawRemoteArtworkUrlBoundaryTest, SharedResolutionOpenFindingsArchitectureTest |
| P1 | Localization bypass | Detail fell across TVDB/TMDB localized fields manually or via preview text | fixed at `app/src/main/java/com/nexio/tv/data/integration/metadata/LocalizationResolver.kt:5` | LocalizationResolver / LocalizationPolicy | LocalizationResolverPolicyTest |
| P2 | Screensaver bypass | Screensaver legacy string models held artwork/trailer strings | fixed at `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt:48` | ResolvedDisplaySurfaceRepository + TrailerResolver + ArtworkDisplayRef | IdleTrailerScreensaverSessionTest |

## Approved Boundaries

- Provider integration adapters may call provider Retrofit/auth APIs only inside IntegrationRuntime-governed operations.
- Raw DTO mappers may parse source payloads but may not decide final display fields.
- TrailerService is approved only as playback transport under TrailerResolver.
- SkipIntroRepository is approved only as a provider adapter/cache behind SkipSegmentResolver.
- Artwork legacy projections and custom image fetcher models are approved only at final render boundaries and must expose safe internal models, not raw remote URL fields.
- RailMediaIdentityResolver is approved only as a temporary cache-key compatibility adapter and must not perform network identity bridging.

| Category | File:line | Symbol | Why allowed | Owner |
|---|---|---|---|---|
| Trailer bypass | `app/src/main/java/com/nexio/tv/MainActivity.kt:910` | `resolveIdleTrailerScreensaverPlaybackSource` | Screensaver first asks `TrailerResolver` to select a `TrailerPlaybackRef`; `TrailerService` only translates the selected ref into a playable source. | TrailerResolver decision, TrailerService transport |
| Trailer bypass | `app/src/main/java/com/nexio/tv/MainActivity.kt:1103` | `resolveIdleTrailerScreensaverPlaybackSource` | Overlay playback uses the same resolver-first helper; no YouTube URL is constructed in UI. | TrailerResolver decision, TrailerService transport |
| Metadata authority bypass | `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataSecondaryRepository.kt:18` | `MetadataSecondaryRepository` | Low-level provider secondary adapter boundary used by ProviderPlanRunner adapters, not by UI/ViewModels. | MetadataRouter + ProviderPlanRunner |
| Rating bypass | `app/src/main/java/com/nexio/tv/data/repository/DetailRatingDisplayRepository.kt:76` | `resolveTitleRating` | Rating repositories now emit candidates; `RatingResolver` owns final title and episode selection. | RatingResolver |
| Skip bypass | `app/src/main/java/com/nexio/tv/core/metadata/router/resolver/SkipSegmentResolver.kt:20` | `SkipIntroRepositoryPort` | SkipIntroRepository is hidden behind a port and no longer called by player UI directly. | SkipSegmentResolver |
| Runtime bypass | approved provider adapter packages | IntegrationRuntime provider adapters | Provider network/auth calls remain in approved data/integration/provider boundaries. | IntegrationRuntime |
| Identity bypass | `app/src/main/java/com/nexio/tv/core/integration/RailMediaIdentityResolver.kt:26` | `RailMediaIdentityResolver` | Temporary cache-key compatibility adapter only; it does not perform network identity bridging. | StableIdBundleResolver |

## False Positives

- Test fixtures and source DTOs may contain raw provider URLs or YouTube IDs.
- Resource image requests for local rating badges and settings icons are not metadata artwork bypasses.
- ArtworkLegacyProjection is not a raw URL UI bypass when it emits nexio-artwork, placeholder, file, content, resource, or safe fetcher models.

## Required Architecture Tests

- SharedResolutionOpenFindingsArchitectureTest
- NoUiTmdbEnsureIdArchitectureTest
- NoDetailUiTmdbEnsureIdArchitectureTest
- MetadataRouterBoundaryTest
- RawRemoteArtworkUrlBoundaryTest
- SkipIntroRepositoryCanonicalSurfaceTest
- WatchProgressProfileScopeArchitectureTest
- WatchProgressRepositoryProviderRoutingTest
- MetaDetailsKitsuAdvancedMetadataTest
- MetaDetailsTvdbAdvancedMetadataTest

## Final Gate

The focused Gradle architecture/resolver sweep passes on this branch, including Kitsu/TVDB advanced detail regressions that now validate provider-plan resolved output rather than UI sidecars. Static scans are clean for direct YouTube URL construction, player SkipIntroRepository calls, direct detail/UI TMDB identity bridge calls, WatchProgress active-profile account derivation, direct detail secondary sidecars, and raw metadata artwork provider URLs reaching metadata UI. The only `trailerService.` scan hits are the two approved screensaver playback transport calls listed above.
