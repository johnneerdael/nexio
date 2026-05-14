# Proposal: Fix Tracker Fan-Out And MDBList Continue Watching

## Why

MDBList scrobble is currently written during playback, but MDBList playback/watched state is not read into Continue Watching. Manual progress/history mutations and season watched actions still route through deprecated single-provider compatibility state instead of `activeProviders`.

## What Changes

- Add MDBList playback and watched history reads to tracker progress aggregation.
- Fan out manual watched/progress/history mutations to all authenticated supported trackers.
- Add MDBList watched/remove/clear mutation support.
- Keep provider failures isolated and best-effort.
- Update season watched and previous watched actions to use batch fan-out.

## Impact

- Affects tracker sync, Continue Watching, detail-screen season actions, and provider mutation tests.
- Does not change Library provider selector behavior.
