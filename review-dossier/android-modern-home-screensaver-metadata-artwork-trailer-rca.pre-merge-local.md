# Android Modern Home — Screensaver Metadata, Artwork, Ratings, and Trailer Parity RCA

Date: 2026-05-05

Scope: Android modern home hydrated metadata/artwork surface versus idle image screensaver and trailer screensaver preparation/playback. This is root cause analysis only; no code fixes were applied.

## Summary

The screensaver is not consuming the same hydrated display surface as modern home. Modern home already has the correct metadata-router decisions, artwork-router output, stable-ID sidecars, and rating overrides. Screensaver builds an independent pool from Trakt or stock Cinemeta rows and then runs a reduced enrichment path. That separate path explains the observed loss of images, incorrect IMDb ratings, missing trailer candidates, and trailer playback divergence.

There are three compounding root causes:

| Symptom | Root cause |
|---|---|
| Screensaver images do not match modern home artwork decisions. | RC1, RC2 |
| IMDb scores differ from modern home. | RC2 |
| Trailer screensaver has fewer/incorrect playable candidates than modern home trailer flows. | RC3 |

## Root Cause RC1 — Screensaver builds a separate source pool instead of consuming hydrated home rows

Modern home hydrates visible/focused items through `HomeHydrationCoordinator`, writes `HydratedHomeOverlay`, and applies those overlays back to home rows. The important path is:

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt:77` calls `metadataRouterFacade.resolveRequest(request)`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt:88` resolves the stable ID bundle.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt:172` applies `TitleRatingOverrideRepository.enrichPreview(...)`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt:177-179` preserves the `displayMetadata.artwork` bundle.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt:11-24` applies hydrated overlay fields to home rows.

Screensaver does not read that hydrated home row/overlay surface. `IdleScreensaverRepository` independently selects screensaver rows:

- `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt:121-140` selects Trakt-backed rows when eligible, otherwise stock Cinemeta popular movie/series rows.
- `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt:143-164` repeats the same independent selection for warm cache.

This means the screensaver is not downstream of modern home. It is a parallel consumer with its own row selection, cache timing, and metadata fallbacks. Even when modern home displays the correct artwork, screensaver may never see that exact hydrated item shape.

## Root Cause RC2 — Screensaver enrichment uses a reduced projection and bypasses the modern-home rating override path

Cold boot does call the metadata router, but only through `fetchIdleScreensaverMeta` at `MetadataDepth.DETAIL_CORE`:

- `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt:92-115`

That result is converted into a reduced `Meta` shape in `toScreensaverMeta`:

- `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt:123-150`

The conversion carries only title/poster/backdrop/logo/description/genres/release/runtime/rating fields and explicitly returns `trailerYtIds = preview.trailerYtIds`. It does not reuse the full `HydratedHomeOverlay` process, and it does not call `TitleRatingOverrideRepository`.

The rating difference is explained by this split:

- Modern home uses `TitleRatingOverrideRepository.enrichPreview(...)`, which first checks stable IMDb IDs and custom IMDb title ratings before falling back to MDBList: `app/src/main/java/com/nexio/tv/data/repository/TitleRatingOverrideRepository.kt:15-44`.
- Screensaver cold boot directly calls `mdbListRepository.enrichPreview(preview)`: `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt:106`.
- Screensaver warm cache does not even run MDBList enrichment; it uses cached meta only: `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt:78-85`.

Artwork is similarly reduced to plain fallback URL strings:

- `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt:47` builds fallback artwork URLs from the enriched preview.
- `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt:70-74` keeps only `background` and `poster`.
- `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt:223-244` maps those URLs into `IdleScreensaverSlide`.

Modern home's `HomeDisplayMetadata` supports `ArtworkBundle` and can project artwork-router refs through `displayPoster`, `displayBackdrop`, and `displayLogo`:

- `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt:22-35`

Screensaver ultimately flattens to `IdleScreensaverSlide.backgroundUrl` plus `fallbackArtworkUrls`, so the screensaver model has no durable overlay identity, no canonical provider/ID, no field trace, and no stable-ID-backed rating decision.

## Root Cause RC3 — Trailer screensaver consumes only pre-existing YouTube IDs, not the modern-home trailer resolution path

Modern home trailer availability and preview resolution use `TrailerService` by item ID/type/title and optional fallback IDs:

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt:241-282` computes trailer metadata availability with `TrailerService.getTitleMediaAvailability(...)`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt:303-351` resolves playback with `TrailerService.resolveTrailer(...)`.
- `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt:112-184` performs cached trailer resolution.
- `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt:475-528` handles movie ordering: TMDB, fallback YouTube IDs, then Streailer.
- `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt:530-590` handles TV ordering: TVDB, Streailer, fallback YouTube IDs, then TMDB fallback.

Trailer screensaver preparation is narrower:

- `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt:48-60` builds trailer IDs only from `hydratedMeta?.trailerYtIds`.
- `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt:148` sets `trailerYtIds = preview.trailerYtIds` after metadata-router hydration.
- `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt:247-267` drops trailer candidates when `trailerYtIds` is empty.
- `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSession.kt:33-67` collects candidates only from slides with `modeData.trailer.trailerYtIds`.
- `app/src/main/java/com/nexio/tv/MainActivity.kt:842-851` prepares the trailer screensaver session by resolving `https://www.youtube.com/watch?v=<id>` directly.
- `app/src/main/java/com/nexio/tv/MainActivity.kt:1038-1044` repeats that direct YouTube URL playback resolution inside the overlay.

That path never asks `TrailerService.resolveTrailer(...)` to discover a trailer by TMDB/TVDB/Streailer/title when the slide lacks a pre-baked YouTube ID. It only resolves playback for IDs already present in the slide. This explains why trailer screensaver can have empty or degraded candidates even when modern home or detail can resolve a trailer for the same item.

## Test Evidence

The existing tests confirm the current behavior rather than guarding against this parity break:

- `app/src/test/java/com/nexio/tv/data/repository/IdleScreensaverRepositoryTest.kt:260-264` explicitly documents that `fetchIdleScreensaverMeta(DETAIL_CORE)` returns a `Meta` with empty trailer IDs and that trailer candidates come from the warm-cache path instead.
- `app/src/test/java/com/nexio/tv/data/repository/IdleScreensaverResolveRequestTest.kt:123-135` only verifies that preview trailer IDs are carried through; it does not verify trailer discovery through `TrailerService.resolveTrailer(...)`.
- `app/src/test/java/com/nexio/tv/ui/shared/MetaSharedTvdbSurfacePropagationTest.kt:90-100` checks for `externalMeta?.toHomeDisplayMetadata()` and `genres = preview.genres`, but does not prove screensaver consumes `HydratedHomeOverlay`, stable IDs, or artwork-router refs.

Focused verification command run during RCA:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.data.repository.IdleScreensaverRepositoryTest \
  --tests com.nexio.tv.data.repository.IdleScreensaverResolveRequestTest \
  --tests com.nexio.tv.ui.screensaver.IdleTrailerScreensaverSessionTest \
  --tests com.nexio.tv.MainActivityIdleScreensaverTest
```

Result: `BUILD SUCCESSFUL in 37s`.

## Conclusion

The screensaver is not broken because the metadata router or artwork router lack correct data. The data exists in the modern-home hydration path. The screensaver breaks because it reconstructs its own catalog-derived item set and compresses hydration into a screensaver-specific projection.

The architectural fix is to make screensaver consume the same canonical hydrated display surface as modern home, or extract that surface behind a shared repository used by both. The required shared surface should include:

1. Hydrated display fields from `MetadataRouterFacade.resolveRequest(...)`.
2. Stable-ID bundle sidecars.
3. `TitleRatingOverrideRepository` rating decisions.
4. `ArtworkBundle` / artwork-router display refs.
5. Trailer candidate discovery through `TrailerService.resolveTrailer(...)`, not only pre-existing `trailerYtIds`.

