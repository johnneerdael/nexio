# Change: Disk-Back Trakt and MDBList Home Catalogs

## Why
Home currently treats Trakt and MDBList discovery rails differently from addon catalogs. They are snapshot-backed and synthesized in memory, which means they can diverge from the disk-first startup, hydration, cleanup, and staged publish behavior used by normal addon catalogs.

## What Changes
- Persist Trakt and MDBList Home rails as disk-backed catalog rows using the same cache lifecycle as addon catalogs.
- Refresh synthetic rails in the background and only publish renewed rows to Home after the disk-backed cache has been updated and hydrated.
- Fold Trakt and MDBList item references into the same metadata/image cache reference tracking and cleanup path as addon-backed Home rows.
- Remove the current “live synthetic row” special case from Home row assembly so UI reads from one persisted row model.

## Impact
- Affected specs: `home-startup-refresh`
- Affected code: `HomeViewModelCatalogPipeline`, Trakt/MDBList discovery services and snapshot stores, Home snapshot persistence, metadata/image cleanup, startup refresh coordinator
