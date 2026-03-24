## 1. Implementation

- [ ] 1.1 Add the `detail-view-navigation` OpenSpec delta covering recent-watch CTA targeting and
      manual season override behavior.
- [ ] 1.2 Add a focused view-model regression test proving that a recent completed episode in a
      later season beats an earlier first-gap fallback.
- [ ] 1.3 Update the season navigation instrumentation harness to cover initial auto-targeting,
      manual season override, and the existing short-show case.
- [ ] 1.4 Adjust the detail view-model CTA selection logic to prefer recent watch context before
      first-gap fallback.
- [ ] 1.5 Split the episode-row entry target from the hero CTA target and preserve manual season
      overrides in the screen layer.
- [ ] 1.6 Run focused verification for the new tests plus `openspec validate --strict`.
