## 1. Implementation

- [ ] 1.1 Add a narrow Tekenfilms policy helper keyed by normalized base URL
      `https://tekenfilms.nexioapp.org`, manifest id `org.nexio.tekenfilms`, catalog id
      `tekenfilms_nl`, movie type, and item id prefix `tekenfilms:`.
- [ ] 1.2 Add focused tests proving the policy matches only the Tekenfilms add-on and rejects
      same-prefix/same-id impostors.
- [ ] 1.3 Update Modern Home catalog row projection so Tekenfilms rows are not truncated by the
      generic 25-item display cap while other add-on rows keep the current cap.
- [ ] 1.4 Update Home hydration scheduling so Tekenfilms first-paint items are excluded from
      metadata/detail hydration and resolved-overlay work.
- [ ] 1.5 Add a scoped direct playback launch path that fetches streams from only the Tekenfilms
      add-on for the clicked `tekenfilms:*` id and launches the player with the first player-ready
      stream URL.
- [ ] 1.6 Update Modern Home click routing so only Tekenfilms items use the direct playback path;
      all other catalog items still call detail navigation.
- [ ] 1.7 Add regression tests for unlimited Modern Home row display, hydration exclusion, direct
      playback routing, and non-Tekenfilms add-on behavior.
- [ ] 1.8 Run focused unit tests and `openspec validate add-tekenfilms-direct-home-playback --strict`.
