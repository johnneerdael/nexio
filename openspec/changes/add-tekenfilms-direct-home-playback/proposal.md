# Change: Add Tekenfilms Modern Home direct playback

## Why

The Nexio-only Tekenfilms add-on (`https://tekenfilms.nexioapp.org/manifest.json`,
manifest id `org.nexio.tekenfilms`) exposes a local Dutch-audio cartoon catalog whose catalog
entries already contain the artwork and text needed for first paint. The current generic Home
catalog path truncates large rows and routes item clicks through detail metadata hydration before
playback, which is unnecessary for this add-on and hides part of the local catalog.

## What Changes

- Treat only the exact Tekenfilms add-on as a first-paint-only Modern Home rail.
- Show every Tekenfilms catalog item in the Modern Home rail instead of applying the generic
  display-row truncation.
- Skip Home/detail metadata hydration for Tekenfilms catalog items.
- Route Tekenfilms catalog item clicks directly to scoped add-on stream lookup and player launch.
- Preserve the existing detail navigation, hydration, catalog truncation, and stream-selection
  behavior for every other add-on.

## Impact

- Affected specs: `tekenfilms-home-playback`
- Affected code: add-on/home policy helper, `HomeViewModelCatalogPipeline`,
  `HomeCatalogRefreshCoordinator`, Modern Home click routing, `NexioNavHost` or a scoped direct
  playback launch helper, focused home/add-on/playback tests
