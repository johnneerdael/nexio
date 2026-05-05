# Android Modern Home Screensaver Metadata Artwork Trailer RCA

## Root Cause

The idle image and trailer screensaver paths were acting as separate metadata consumers with their own reduced source pipeline. Modern Home used the shared Home hydration, metadata routing, artwork routing, rating override, stable ID, and trailer resolution surfaces, while screensaver reconstructed a smaller projection from provider rows and cached metadata.

That split caused screensaver output to drift from Modern Home:

- Artwork decisions could lose the same premium/router-selected assets shown on Home.
- Ratings could miss the same override and resolver precedence used by Home.
- Trailer candidates could depend on pre-existing YouTube IDs instead of resolving from item identity.
- Warm-cache and cold-boot behavior could produce different screensaver metadata.

## Architectural Finding

Screensaver should not be another metadata pipeline. It should consume the same final display surface as Modern Home.

The corrected boundary is:

```text
Modern Home owns final display composition.
ResolvedDisplaySurfaceRepository stores final resolved display items.
ScreensaverCandidateRepository projects screensaver candidates from that stored surface.
IdleScreensaverRepository consumes those candidates only.
Trailer screensaver resolves playback through TrailerService with stable IDs.
```

The incorrect boundary is:

```text
IdleScreensaverRepository calls provider/source pools directly.
IdleScreensaverPreparation re-routes artwork or ratings.
Trailer screensaver builds direct YouTube watch URLs.
```

## Implementation Plan

The implementation plan for the architectural fix is stored at:

`docs/superpowers/plans/2026-05-05-screensaver-display-surface-parity.md`

The plan preserves the RCA conclusion: screensaver becomes a consumer of the shared resolved display surface and does not add a second metadata/artwork/rating/trailer pipeline.
