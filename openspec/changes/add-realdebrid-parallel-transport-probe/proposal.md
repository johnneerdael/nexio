# Change: Add local Real-Debrid parallel transport probe

## Why

Nexio's Real-Debrid optimized benchmark shows repeatable multi-second starvation windows on the
parallel path, but the app benchmark does not expose enough raw worker-side and consumer-side data
to tell whether the issue is caused by one blocking chunk, a multi-worker stall, server-side
throttling, or a local assembly/retry problem.

## What Changes

- Add a local Real-Debrid-only transport investigation CLI under `tools/rd_probe/`.
- Authenticate with Real-Debrid from `.env` and resolve a large candidate the way Nexio does.
- Run controlled parallel range transfers with an in-order consumer model.
- Emit structured telemetry and summaries that identify isolated-vs-global stall behavior.
- Optionally integrate packet capture for the run.

## Impact

- Affected area: local tooling only
- Affected provider scope: Real-Debrid only
- Affected capability: transport forensics / debugging
- No production app behavior changes in this phase
