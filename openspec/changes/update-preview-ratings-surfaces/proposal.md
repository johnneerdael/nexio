# Change: Update Preview Ratings Surfaces

## Why
The idle screensaver still renders a static call-to-action that can contribute to OLED burn-in, and its metadata treatment does not include Rotten Tomatoes even though the detail screen already supports MDBList ratings. Modern home also only exposes IMDb at the preview level, which prevents a consistent ratings presentation across discovery surfaces.

## What Changes
- Add a preview-level Rotten Tomatoes rating field so shared preview metadata can carry both IMDb and Tomatoes values.
- Populate preview-level Rotten Tomatoes ratings using the same MDBList repository path already used by the detail screen, with cache/settings behavior preserved.
- Update the idle screensaver metadata layout to remove description, year, and runtime, keep genre and IMDb, add Rotten Tomatoes after IMDb, and fade out the `Press OK for details` prompt 5 seconds after each newly visible slide.
- Update the modern home hero to render Rotten Tomatoes next to IMDb while keeping the existing year and description treatment.

## Impact
- Affected specs: `preview-ratings-surfaces`
- Affected code: `app/src/main/java/com/nexio/tv/domain/model`, `app/src/main/java/com/nexio/tv/data/repository`, `app/src/main/java/com/nexio/tv/ui/screensaver`, `app/src/main/java/com/nexio/tv/ui/screens/home`
