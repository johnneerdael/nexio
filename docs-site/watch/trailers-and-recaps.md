# Trailers and Recaps

Nexio can play trailers in Home and on Detail, and it can surface recap skips during playback. This guide covers the app-first path for getting trailer playback working and explains where the fallback options fit.

## What this helps with

- Auto-playing trailers on Modern Home.
- Starting a trailer from a detail page.
- Understanding how season trailers and recap skips are surfaced.

## Where to set it up

- `Settings > Layout` controls Modern Home and the trailer behavior tied to focused posters.
- `Settings > Playback` controls the broader playback behavior that trailer playback inherits.
- [Options and Self-Hosting](/advanced/options-and-self-hosting) is where longer optional fallback details live if the app-first route is not enough.

## What to expect

- On Modern Home, a focused hero can autoplay a trailer preview when trailer autoplay is enabled.
- On Detail, the hero shows a dedicated Trailer button whenever Nexio resolves one.
- For series, trailer lookup can use the selected season, and the season trailer action is reached by long-pressing a season on the detail screen.
- Season recaps use the same long-press season action on Detail. Playback can still surface Skip Recap later, but the season action is the entry point to look for first.
- Nexio uses an in-app native YouTube extractor to resolve and play trailers without forcing a browser handoff. Age-restricted (16+) trailers are not supported.
- If the built-in YouTube path cannot resolve a trailer, Nexio can fall back to Streailer when that addon is installed.

## If this is not working

- If trailer playback is missing on Home, check the Modern Home trailer settings and whether the focused item actually has a trailer.
- If the Detail trailer button is missing, the title may not have a trailer in the currently available sources.
- If you need longer fallback setup steps, open [Advanced](/advanced/) instead of staying on this page.

## Related guides

- [Home and Continue Watching](/watch/home-and-continue-watching)
- [Details, Seasons, and Watching Flow](/watch/details-seasons-and-watching-flow)
- [Playback](/playback/)
- [Playback Tuning](/playback/playback-tuning)
- [Options and Self-Hosting](/advanced/options-and-self-hosting)
- [Troubleshooting](/troubleshooting/)
