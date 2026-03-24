# Detail View Watch Context Design

## Context

The TV detail screen currently lets one `nextToWatch` value drive both the hero CTA and the
season-tab-to-episode-row focus handoff. That coupling produces two regressions:

- the hero CTA can rewind to an early episode gap such as `S1E2` even when the most recent watch
  context is later in the series
- the season tabs can route focus back toward that stale CTA target instead of honoring a manual
  season override, which blocks users from entering the currently selected season's episode row

The intended behavior is stricter:

- the hero CTA should stay anchored to the most recently watched episode context
- the episode row entry target should follow the CTA only while the detail screen is still on the
  auto-targeted season
- once the user manually changes seasons, down navigation should enter that chosen season instead
  of being pulled back by the CTA target

## Goals

- Keep the hero CTA anchored to recent watch context for TV series.
- Preserve the working auto-target behavior for long-running shows when the selected season still
  matches the CTA target.
- Respect manual season changes when moving from season tabs into episode cards.
- Cover both regressions with focused automated tests before implementation.

## Non-Goals

- Change route arguments or detail-screen navigation contracts.
- Reorder seasons or modify specials handling.
- Redesign Trakt sync architecture beyond the logic required to pick the correct local hero target.

## Decisions

### 1. Split the hero target from the episode-row entry target

`nextToWatch` remains the hero CTA model. The episodes section gets its own derived entry target in
the screen layer.

Entry target priority for the currently selected season:

1. last focused episode for that season
2. CTA target, but only while the selected season still matches the current auto-targeted season
3. first episode in the selected season

This removes the accidental coupling where a stale CTA can override manual season navigation.

### 2. Use recent watch context before earliest-unwatched fallback

For series, CTA selection should prefer recent context in this order:

1. resume the most recently watched in-progress episode
2. if the most recently watched episode is completed, target the next episode in series order
3. only when no usable recent anchor exists, fall back to the earliest unwatched regular episode
   in series order

If the show has no regular-season episodes, the fallback may use specials. This preserves the
existing season-ordering behavior while making the fallback target explicit.

This prevents an older missing episode from outranking a more recent completed watch history entry.

### 3. Treat manual season selection as an explicit override

The detail UI should distinguish between:

- the season selected automatically from the CTA target
- a season selected by user focus/navigation

Once the user manually selects another season, down navigation should stop consulting the CTA
target for row entry for the rest of the current detail-screen session. The override clears only
when the user leaves and reopens the detail screen.

### 4. Keep focus restoration local to the selected season

Per-season last-focused episode tracking remains valid. When available, it should continue to win
over CTA-derived entry targeting for the same season. This keeps return navigation and repeated
movement within a season stable.

## Component Changes

### `MetaDetailsViewModel`

- Adjust series next-to-watch selection so a recent completion anchor beats the earliest-unwatched
  fallback.
- Preserve the current `NextToWatch` data shape so existing hero rendering does not need a public
  API change.

### `MetaDetailsScreen`

- Derive a season entry target separately from `nextToWatch`.
- Track whether the selected season is still the auto-targeted season or has been manually
  overridden.
- Use the season entry target for `seasonDownFocusRequester` and episode-row scroll targeting.

### `SeasonTabsNavigationTest`

- Extend the navigation harness to reflect the real behavior contract: CTA targeting is allowed for
  the initial auto-selected season, but not after a manual season change.

### ViewModel tests

- Add a focused unit test proving that recent watch context in a later season outranks the
  earliest-unwatched fallback in an earlier season.

## Data Flow

### Hero CTA

1. Episode progress updates arrive from the merged watch-progress repository.
2. The view model finds the most recent usable series anchor.
3. The hero CTA is built from that anchor or, if none exists, from the earliest unwatched regular
   episode fallback.

### Season tab down navigation

1. The screen computes whether the current selected season still matches the auto-targeted CTA
   season.
2. The first manual season change marks the screen as manually overridden for the rest of the
   current detail-screen session.
3. When the user presses down from the selected season tab:
   - restore the last focused episode for that season when available
   - otherwise use the CTA target only if the season has not been manually overridden
   - otherwise enter the first episode in the selected season

## Error Handling

- If the CTA target references an episode that is missing from the selected season, the screen
  should ignore it for row entry and fall back safely to season-local behavior.
- If no series episode progress exists, the hero CTA should still fall back to the earliest
  unwatched regular episode, or specials when no regular episodes exist, without changing the
  existing movie behavior.

## Testing

- Unit test: a recent completed `S3E9` anchor produces `Next S3E10` instead of rewinding to an
  older earliest-unwatched fallback.
- Instrumentation test: long-running auto-targeted season still enters the CTA episode correctly.
- Instrumentation test: manual season override enters the selected season instead of the CTA
  target.
- Instrumentation test: short-show currently working behavior remains unchanged.
