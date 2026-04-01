# Change: Serialize Disk-Backed Home Refresh Pipeline

## Why
Disk-first startup is still allowing Home to be materially derived from fragmented per-source state during boot and refresh. That breaks the intended startup model: Home should restore from one merged persisted snapshot in configured order, while addon, Trakt, and MDBList source refresh should only renew their disk caches in the background and only later cause Home to reload from the renewed merged disk snapshot.

## What Changes
- Make the merged persisted Home snapshot the only startup source of truth for rendered Home rows.
- Route addon, Trakt, and MDBList refresh through the same serialized post-startup refresh queue.
- Remove any Home-path publish that rebuilds rendered rows directly from per-source live state or intermediate per-source restore state.
- Keep per-source disk caches as background refresh inputs only, never as direct rendered Home sources.
- Ensure Home reloads only from the renewed merged persisted snapshot after serialized refresh and hydration complete.

## Impact
- Affected specs: `home-startup-refresh`
- Affected code: `HomeViewModelCatalogPipeline`, `HomeViewModelIntegrationRefreshPipeline`, `HomeCatalogRefreshCoordinator`, source snapshot persistence/materialization paths, merged Home snapshot reload paths, startup telemetry
