# Android TV Native Search Checklist

Use this checklist after implementing or changing Nexio's Android TV native search provider.

## Build And Install

- Build an arm64 debug APK.
- Install the APK on an Android TV device or Android TV emulator.
- Confirm Nexio launches normally from the launcher.

## Search Source Registration

- Open Android TV system search or the launcher search surface.
- If the device exposes searchable app/source settings, confirm Nexio appears as a searchable source for movies and TV shows.
- If the device requires manual enablement, enable Nexio and rerun the checks below.

## Movie Search

- Search for a known movie, such as `The Matrix`.
- Confirm Nexio appears as a result or suggestion when the launcher surfaces app-provided suggestions.
- Select the Nexio result.
- Confirm Nexio opens the movie detail page.
- Confirm Nexio does not open stream selection, deterministic autoplay, or the player.

## TV Show Search

- Search for a known show, such as `Friends`.
- Confirm Nexio appears as a result or suggestion when the launcher surfaces app-provided suggestions.
- Select the Nexio result.
- Confirm Nexio opens the show detail page.
- Confirm Nexio does not infer or open an episode.

## Local Cache Search

- Open Nexio and let modern Home/catalog rows finish loading.
- Pick one movie currently visible on Home with a known year and runtime.
- Return to Android TV system search and search that exact title.
- Confirm Nexio can return the title even if network is disabled after Home has loaded.
- Confirm the result still opens the Nexio detail page.
- Repeat with one TV show visible on Home. Confirm Nexio opens show detail and does not infer an episode.

## Runtime Hydration

- Pick a Home/catalog title that initially lacks runtime in its visible metadata.
- Let Home refresh/deferred hydration complete.
- Search the title from Android TV system search.
- Confirm Nexio still returns the title and, where the platform exposes details, the candidate behaves as a stronger entity-card match after runtime metadata is cached.
- If the title still does not appear as an app option on the Google entity card, capture whether title, year, or duration is missing from the Nexio provider candidate.

## Failure Behavior

- Disable network connectivity or block Cinemeta access.
- Search for a known title from Android TV search.
- Confirm Android TV search remains responsive.
- Confirm locally cached titles can still appear without a network request.
- Confirm non-cached titles return no suggestions rather than crashing or surfacing an app error.
- Re-enable network connectivity and confirm search suggestions recover.

## Regression Checks

- Open Nexio in-app search and confirm existing addon fan-out search still works.
- Open an Android TV recommendation channel item and confirm it still routes to detail.
- Open a Continue Watching item and confirm stream/detail behavior is unchanged.
- Confirm app startup still completes without provider-related crashes.

## Notes To Capture

- Device or emulator image/version.
- Whether Android TV required enabling Nexio as a searchable source.
- Whether results appeared as suggestions, a Nexio row, or an entity-card action.
- Whether the result source was local cache or live Cinemeta, if diagnostics expose this.
- Whether title/year/duration were available for the candidate.
- Any launcher-specific delays, missing artwork, or result ordering differences.
