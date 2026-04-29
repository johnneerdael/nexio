# Align addon first-paint response shapes

## Why

Live Stremio addon catalog responses expose card-ready first-paint fields that the current source adapter does not fully preserve. The documented TMDB addon payload uses both `genre` and `genres`, both `year` and `releaseInfo`, and exposes stable IDs through `id`, `imdb_id`, and `behaviorHints.defaultVideoId`. Top Streaming also shows that a catalog route declared as `series` may return mixed `movie` and `series` items.

If these fields are dropped, addon rows still render through the shared first-paint lifecycle, but first paint is poorer and visible hydration starts with weaker identity than the addon payload already provided.

## What changes

- Preserve addon catalog `genre` as a fallback for `genres`.
- Preserve addon catalog `year` as a fallback for `releaseInfo`.
- Harvest stable IDs from `id`, `imdb_id`, and `behaviorHints.defaultVideoId` into `MetaPreview.firstPaintStableIds`.
- Add a mixed-type catalog regression test proving item type wins over route/catalog type.
- Add architecture guards proving the adapter remains the only new source-specific code and Home/hydration stay shared.

## What does not change

- No provider-specific Home renderer.
- No addon-specific hydration scheduler.
- No MetadataRouter bypass.
- No first-paint search fallback or extra network call.
