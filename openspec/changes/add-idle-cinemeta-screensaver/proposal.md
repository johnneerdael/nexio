# Change: Add Idle Cinemeta Screensaver

## Why
Nexio currently has no idle-mode browsing screensaver. The requested behavior is a Netflix-style idle takeover that protects the panel with subtle motion, keeps the app visually polished after inactivity, and uses fresh stock Cinemeta discovery content instead of hardcoded assets.

## What Changes
- Add a global idle screensaver overlay that activates after 5 minutes of no remote input.
- Build a dedicated screensaver content pipeline from the stock Cinemeta `Popular - Movie` and `Popular - Series` catalogs, even when those rows are hidden from Home.
- Refresh the top 5 movie and top 5 series candidates on every cold boot, then rotate the combined pool in randomized order during the idle session.
- Add a full-screen screensaver UI with background artwork, title logo or title fallback, metadata, vignette, slow-motion pan/zoom, and timed cross-fades.
- Add instant dismiss behavior for any input, with `OK/Select` deep-linking to the selected title's detail page instead of only dismissing.

## Impact
- Affected specs: `idle-screensaver`
- Affected code: `MainActivity`, startup/deferred work path, home/catalog data pipeline, navigation/deep-link handling, new screensaver state/repository/ui files
