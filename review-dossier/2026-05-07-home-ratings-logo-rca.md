# Home Ratings And TV Logos RCA

Date: 2026-05-07

Status: root cause boundary is identified; one input-source detail still needs a runtime trace sample to prove whether the bad seed comes from stale persisted preview data or a current upstream payload.

## Executive Summary

The screenshots show two separate failures on home/screensaver surfaces:

1. TV series logos are still missing on home after the TVDB adapter fix.
2. Home/screensaver ratings sometimes show impossible values such as `15129,0`, `1767427,0`, `152596,0`, and `8649,0`, while opening detail shows correct IMDb/TMDB/Rotten Tomatoes ratings.

This is not a detail-screen rating failure. Detail uses the metadata router rating path and custom/MDBList/provider candidate precedence. Home and screensaver can still render first-paint rail preview fields until a hydrated overlay is available and applied.

The comma decimal separator has a confirmed cause: multiple UI formatters call `String.format("%.1f", rating)` without `Locale.US`. On a Dutch locale, Java/Kotlin formatting renders `8.3` as `8,3`.

The impossible rating values are most consistent with a bad preview-stage score being promoted as `imdbRating`. Several screenshot values look like TMDB `popularity` values scaled by 1000, for example `1767427,0` is consistent with a popularity-like `1767.427` rendered as a one-decimal rating after the decimal was lost upstream or in stale cache. Current `TmdbRailPreviewMapper` maps `voteAverage`, not `popularity`, so the remaining question is whether these values are from stale persisted snapshot/rail data or a separate active preview source.

## User-Visible Evidence

- Image #1: `Widow's Bay` on `TMDB Trending Series - Series` shows no logo and an IMDb badge with `15129,0`.
- Image #2: `House of the Dragon` on `TMDB Trending Series - Series` shows a TMDB badge with `1767427,0`.
- Image #3: screensaver slide for `Kastanjemanden/The Chestnut Man` shows an IMDb badge with `152596,0`.
- Image #4: screensaver slide for `The House of the Spirits` shows an IMDb badge with `8649,0`.
- User observation: opening detail shows the correct IMDb, TMDB, and Rotten Tomatoes values.

The detail/home mismatch is the key clue. If the provider APIs were returning these values as ratings globally, detail would be wrong too.

## Relevant Data Flow

First-paint rail path:

```text
TMDB/MDBList/Trakt rail preview
-> RailItemPreview.display.rating / ratingText
-> RailItemPreview.toMetaPreview()
-> MetaPreview.imdbRating + ratingSource
-> ModernHomeModels.buildCatalogItem()
-> HeroPreview.imdbText
-> ModernHomeHero / screensaver overlay
```

Hydrated home/detail path:

```text
MetaPreview
-> HomeHydrationCoordinator
-> MetadataRouterFacade.resolveRequest()
-> stable-id bundle / IMDb sidecar when available
-> HydratedHomeOverlay.fields
-> HomeDisplayMetadata.applyTo(...)
-> home/surface/screensaver
```

Detail path:

```text
MetaDetails
-> MetadataDisplayRepository / DetailRatingDisplayRepository
-> TitleRatingOverrideRepository / Custom IMDb / MDBList / provider candidates
-> RatingResolver
-> detail UI
```

## Confirmed Finding 1: Locale-Sensitive Decimal Formatting

Home hero rating text is formatted here:

- `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt:560`

```kotlin
imdbText = (displayMetadata.imdbRating ?: item.imdbRating)?.let { String.format("%.1f", it) }
```

Screensaver has the same issue:

- `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverOverlay.kt:439`

```kotlin
text = String.format("%.1f", rating)
```

Detail has similar formatting in `HeroSection.kt`, and tomatoes/aggregate helpers also use locale-sensitive `String.format` in several places.

Root cause:

```text
String.format("%.1f", value)
uses the device default Locale.
On nl-NL, decimal separator is ",".
Therefore 8.3 renders as 8,3.
```

This is why `8.3` appears as `8,3`, and why invalid values appear as `1767427,0`.

## Confirmed Finding 2: Home Preview Ratings Are Not Range-Gated

`RailItemPreview.toMetaPreview()` accepts `display.ratingText` or `display.rating.value` and assigns it directly to `MetaPreview.imdbRating`:

- `app/src/main/java/com/nexio/tv/domain/model/RailItemPreview.kt:131-147`

```kotlin
val rating = display.ratingText?.toFloatOrNull()
    ?: display.rating?.value?.toFloat()
...
imdbRating = rating
```

There is no validation that title ratings are in the expected range:

```text
IMDb/TMDB title ratings: 0.0..10.0
Rotten Tomatoes: 0..100, separate field
Popularity/rank/votes/IDs: must never become title rating
```

Because there is no range gate, any bad preview seed can be rendered as a rating.

## Strong Inference: Bad Values Resemble TMDB Popularity, Not IMDb Ratings

The screenshot values are not plausible IMDb ratings, TMDB vote averages, or Rotten Tomatoes percentages.

They are plausible popularity-like values after the decimal point was lost or scaled:

```text
15129,0   ~= 15.129 * 1000
1767427,0 ~= 1767.427 * 1000
152596,0  ~= 152.596 * 1000
8649,0    ~= 8.649 * 1000
```

TMDB discovery/search payloads include both:

- `vote_average`
- `popularity`

The current TMDB search code explicitly sorts by `popularity`:

- `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt:50-54`

The current TMDB rail mapper correctly maps rating from `voteAverage`:

- `app/src/main/java/com/nexio/tv/data/integration/railpreview/TmdbRailPreviewMapper.kt:35-37`

```kotlin
rating = result.voteAverage?.let {
    RatingSeed(provider = ProviderId.TMDB, value = it, votes = result.voteCount)
}
```

So the active source of the bad value is not proven from static code alone. The likely possibilities are:

- a stale persisted `HomeCatalogSnapshotStore` / discovery snapshot produced by an older bad mapper,
- a separate preview source that maps `popularity` into `imdbRating`,
- an upstream/addon preview payload whose `imdbRating` field contains popularity-scaled data,
- a cached `RailItemPreview.ratingText` or `MetaPreview.imdbRating` that predates the current mapper.

The app currently lacks a range gate, so any of those sources can survive into home/surface rendering.

## Confirmed Finding 3: Detail Uses A Richer Rating Path

Detail rating resolution builds ordered candidates and prefers custom/MDBList/provider ratings before preview fallback:

- `app/src/main/java/com/nexio/tv/data/repository/DetailRatingDisplayRepository.kt:56-77`

Custom IMDb lookup can resolve from IMDb directly or from TMDB -> IMDb:

- `app/src/main/java/com/nexio/tv/data/repository/CustomImdbTitleRatingsRepository.kt:83-101`

This explains the user observation:

```text
Home/screensaver can show preview-stage bad ratings.
Opening detail resolves stable IDs and rating candidates again.
Detail then shows the correct IMDb/TMDB/Rotten Tomatoes values.
```

## Is This Related To Missing IMDb IDs?

Partly, but missing IMDb ID is not the full root cause.

Missing or delayed IMDb identity explains why home hydration may fail to replace preview ratings with custom IMDb API ratings. `CustomImdbTitleRatingsRepository` needs either a usable IMDb ID or a TMDB ID that can be mapped to IMDb.

However, missing IMDb ID alone should result in no IMDb rating or a lower-priority provider rating. It should not produce `15129,0` or `1767427,0`.

The complete failure requires both:

```text
1. a bad preview-stage numeric seed enters MetaPreview.imdbRating
2. no 0..10 title-rating validation rejects it before rendering
```

## TV Logo Finding

The prior TVDB adapter fix addressed this path:

```text
TVDB series extended.artworks
-> TVDB artwork candidates
-> metadata-router artwork fields
```

The screenshots are home/surface rows labeled `TMDB Trending Series - Series`, and their first-paint items originate from TMDB rails. First-paint TMDB rail previews have no logo in the TMDB rail mapper. TVDB clearlogos only become available if home hydration routes the item to TVDB, resolves stable IDs, receives TVDB artwork, writes a `HydratedHomeOverlay`, and that overlay is applied to the visible home/surface item.

Relevant overlay application path:

- `HomeHydrationCoordinator` resolves metadata and stable IDs: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt:74-95`
- Applied overlays are published to the resolved home surface: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:504-508`
- Overlay fields are applied to row items: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt:11-45`

Therefore, the screenshot does not prove TVDB still lacks logos in the adapter. It proves the visible home/surface item is still not receiving/using a hydrated logo overlay for these TMDB rail entries.

Most likely reasons:

- TMDB rail item only has TMDB ID at first paint and stable-id hydration has not produced a TVDB ID yet.
- The item is displayed before hydration completes and the row/surface has not been recomposed with the overlay.
- The home hero is using hero enrichment or first-paint `MetaPreview.logo`, while the TVDB logo exists only in a detail/hydrated document.
- The screensaver surface is sourced from TMDB trending rows and may publish preview-derived candidates before overlay logo availability.

## Root Cause Statement

Primary root cause:

```text
Home and screensaver still trust first-paint preview rating fields too much.
Those preview fields can contain non-rating numeric values, and no shared rating validator
prevents values outside the expected title-rating range from being rendered.
```

Secondary root cause:

```text
Home/screensaver/detail rating formatting uses device-default locale in several places,
so Dutch locale renders decimal ratings with "," instead of ".".
```

Logo root cause boundary:

```text
TVDB clearlogo retention may be fixed in the provider adapter, but TMDB home/surface
rows still depend on home hydration and overlay propagation before TVDB logos can appear.
The observed missing logos are at the home/surface overlay propagation and identity-hydration boundary,
not at the detail UI rendering layer.
```

## Architecture Verdict

These are shared display-system issues, not individual UI-composable issues.

Do not fix with local patches in:

```text
ModernHomeHero
IdleScreensaverOverlay
ContentCard
```

Those components should consume already-resolved, validated display data. The fix belongs in the shared rating, artwork, hydration-overlay, and resolved-display-surface path:

```text
RatingDisplayFormatter
RatingValueValidator
RatingResolver / preview rating candidate handling
HydratedHomeOverlay
ArtworkBundle / ArtworkRouter
ResolvedDisplaySurfaceRepository
ScreensaverCandidateRepository
```

The two visible failures should be handled as separate packets:

```text
Packet A: shared rating validation and formatting
Packet B: preview rating quarantine / stale snapshot cleanup
Packet C: home/screensaver logo hydration propagation
```

## Verification Needed To Close The Last Gap

Add or inspect one runtime trace sample for an affected item before changing behavior:

```json
{
  "surface": "HOME_OR_SCREENSAVER",
  "itemKey": "series:tmdb:<id>",
  "title": "House of the Dragon",
  "firstPaintStableIds": { "tmdb": "...", "imdb": null, "tvdb": null },
  "previewRating": {
    "value": 1767427.0,
    "source": "TMDB",
    "rawField": "unknown"
  },
  "tmdbPayload": {
    "vote_average": 8.3,
    "popularity": 1767.427
  },
  "overlay": {
    "present": true,
    "imdbId": "tt...",
    "rating": 8.3,
    "ratingSource": "IMDB",
    "logoPresent": true
  }
}
```

This will distinguish stale cache from an active mapper/source bug.

## Fix Direction

RCA-only, no code changes made here. The implementation direction should be:

1. Centralize title rating formatting with `Locale.US`.
2. Add title-rating range validation before assigning/rendering `MetaPreview.imdbRating`, `HomeDisplayMetadata.imdbRating`, and `ResolvedDisplayItem.rating`.
3. Treat out-of-range preview ratings as rejected candidates, not displayable fallback.
4. Trace the raw rating source field for home/surface preview ratings.
5. Ensure TMDB series rail items get stable-id hydration to TVDB/IMDb and that hydrated `ArtworkBundle.logo` is applied to home and screensaver surfaces.
6. Do not fix logos by direct UI URL assignment; keep using the shared hydrated artwork/overlay path.

### Packet A: Shared Rating Validation And Formatting

Add a shared formatter for badge/display ratings:

```kotlin
object RatingDisplayFormatter {
    fun oneDecimal(value: Double): String =
        String.format(Locale.US, "%.1f", value)
}
```

Or use an injected formatter if the codebase prefers DI-managed display helpers:

```kotlin
class RatingDisplayFormatter @Inject constructor() {
    fun formatTitleRating(value: RatingValue): String
    fun formatPercentage(value: PercentageRating): String
}
```

Add a shared validator:

```kotlin
object RatingValueValidator {
    fun validTitleRating(value: Double?): Boolean =
        value != null && value.isFinite() && value in 0.0..10.0

    fun validPercentRating(value: Double?): Boolean =
        value != null && value.isFinite() && value in 0.0..100.0
}
```

Rule:

```text
Title ratings must be 0.0..10.0.
Percent ratings must live in a percent-specific field.
Popularity, votes, ranks, IDs, and counts must never enter title rating fields.
```

Apply the validator before assigning or rendering:

```text
MetaPreview.imdbRating
HomeDisplayMetadata.imdbRating
ResolvedDisplayItem.rating
ScreensaverSlideCandidate.rating
```

### Packet B: Preview Rating Quarantine And Snapshot Cleanup

`RailItemPreview.toMetaPreview()` should not blindly promote raw preview floats into `MetaPreview.imdbRating`. Convert preview ratings through a typed resolution result:

```kotlin
data class PreviewRatingResolution(
    val rating: Float?,
    val source: ProviderId?,
    val rejected: RejectedPreviewRating?
)
```

Expected behavior:

```text
invalid preview rating -> no display rating
invalid preview rating -> trace rejection
invalid preview rating -> item queued for rating hydration when identity permits
```

Existing persisted snapshots may already contain bad values. Snapshot read should sanitize them non-destructively:

```text
if imdbRating !in 0..10:
  clear imdbRating
  clear ratingSource if tied to invalid rating
  mark/trace ratingNeedsHydration
  do not fail the whole snapshot
```

Do not display the bad value while waiting for hydration.

### Packet C: Logo Hydration Propagation

First-paint TMDB series previews are allowed to have no logo. Hydrated overlays must be able to add one later:

```text
TMDB series ID
-> canonical identity / TVDB ID
-> TVDB series extended artworks
-> type 23 clearlogo candidate
-> ArtworkRouter
-> HydratedHomeOverlay
-> ResolvedDisplaySurface
-> Home/screensaver recompose
```

Required rule:

```text
Apply artwork per image type.
Do not replace an existing/fallback artwork bundle with a sparse bundle that has logo=null.
Screensaver must consume ResolvedDisplayItem.artwork.logo, not preview.logo only.
```

Likely failure points to verify:

```text
1. TMDB series rail item is not queued for hydration.
2. Stable ID resolver does not resolve TMDB series -> TVDB ID.
3. TVDB adapter emits logo, but overlay does not include ArtworkBundle.logo.
4. Overlay applier only applies poster/backdrop, not logo.
5. HomeResolvedDisplayMapper drops logo.
6. ScreensaverCandidateRepository drops logo.
7. The row item is not recomposed after overlay update.
```

## Required Tests For Implementation

Rating tests:

```text
rating_formatter_uses_locale_us_under_dutch_locale
rail_preview_rejects_rating_above_10
rail_preview_rejects_popularity_scaled_value
home_snapshot_clears_out_of_range_imdb_rating
screensaver_does_not_render_out_of_range_preview_rating
detail_rating_format_uses_locale_us
```

Logo propagation tests:

```text
tmdb_series_rail_hydrates_to_tvdb_identity
tvdb_hydration_overlay_contains_logo
home_overlay_applier_applies_logo
home_overlay_applier_does_not_clear_logo_when_overlay_logo_null
resolved_display_surface_preserves_logo
screensaver_candidate_preserves_logo
tmdb_series_home_row_shows_logo_after_tvdb_hydration
```

Shared-system boundary tests:

```text
no_direct_tvdb_logo_assignment_in_home_ui
no_direct_rating_format_string_format_without_locale
home_rating_uses_rating_display_formatter
screensaver_rating_uses_rating_display_formatter
```
