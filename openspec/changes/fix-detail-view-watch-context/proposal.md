# Change: Fix detail view watch-context targeting

## Why

The TV detail view can currently derive a stale hero CTA such as `Next S1E2` even when the most
recent watch context is in a later season. The same stale target can also override season-tab down
navigation and block users from entering the episode row for a manually selected season.

## What Changes

- Prefer recent watch context over earliest missing-episode fallback when building the series hero
  CTA target.
- Separate the episode-row entry target from the hero CTA target.
- Respect manual season overrides when moving down from season tabs into episode cards.
- Add focused regression tests for CTA selection and season override navigation.

## Impact

- Affected specs: `detail-view-navigation`
- Affected code: `MetaDetailsViewModel`, `MetaDetailsScreen`, detail-screen navigation tests
