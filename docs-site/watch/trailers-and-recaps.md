# Trailers and Recaps

Nexio can play trailers in Home and on Detail, and it can surface recap skips during playback. This guide covers the app-first path for getting trailer playback working and explains where the fallback options fit.

## What this helps with

- Auto-playing trailers on Modern Home.
- Starting a trailer from a detail page.
- Understanding how season trailers and recap skips are surfaced.
- Setting up YouTube login for age-restricted trailers and better trailer availability.

## Where to set it up

- `Settings > Layout` controls Modern Home and the trailer behavior tied to focused posters.
- `Settings > Integration > YouTube Trailer Login` is the app-first sign-in path for authenticated YouTube trailer playback.
- `Settings > Playback` controls the broader playback behavior that trailer playback inherits.
- [Options and Self-Hosting](/advanced/options-and-self-hosting) is where the longer optional auth and fallback details live if the app-first route is not enough.

## Recommended setup

1. Use the in-app YouTube trailer login first.
2. Sign in with the TV code flow from the app instead of trying to configure trailer playback by hand.
3. Keep the trailer helper enabled so Nexio can resolve and play trailers inside the app with its bundled custom yt-dlp path for ad-free trailer playback.
4. Treat Streailer as a fallback, not the first setup target.

## What to expect

- On Modern Home, a focused hero can autoplay a trailer preview when trailer autoplay is enabled.
- On Detail, the hero shows a dedicated Trailer button whenever Nexio resolves one.
- For series, trailer lookup can use the selected season, and the season trailer action is reached by long-pressing a season on the detail screen.
- Season recaps use the same long-press season action on Detail. Playback can still surface Skip Recap later, but the season action is the entry point to look for first.
- YouTube login improves trailer reliability, and it matters most for age-restricted videos that need Google approval before playback will work.
- The TV login flow shows a QR code or verification URL plus a device code, so you approve trailer access on a phone or computer and come back to the TV when it is signed in.
- The app uses a bundled trailer helper built around its embedded yt-dlp-based runtime so trailers can play inside Nexio without forcing a browser handoff.
- If the built-in YouTube path cannot resolve a trailer, Nexio can fall back to Streailer when that addon is installed.

## If this is not working

- If trailer playback is missing on Home, check the Modern Home trailer settings and whether the focused item actually has a trailer.
- If the Detail trailer button is missing, the title may not have a trailer in the currently available sources.
- If age-restricted trailers fail, sign in through the YouTube trailer login screen and refresh the session.
- If you need longer auth or fallback setup steps, open [Advanced](/advanced/) instead of staying on this page.

## Related guides

- [Home and Continue Watching](/watch/home-and-continue-watching)
- [Details, Seasons, and Watching Flow](/watch/details-seasons-and-watching-flow)
- [Playback](/playback/)
- [Playback Tuning](/playback/playback-tuning)
- [Options and Self-Hosting](/advanced/options-and-self-hosting)
- [Troubleshooting](/troubleshooting/)
