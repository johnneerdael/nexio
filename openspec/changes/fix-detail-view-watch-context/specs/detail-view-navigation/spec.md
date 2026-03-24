## ADDED Requirements

### Requirement: Recent watch context drives the series hero CTA

The detail view SHALL derive a series hero CTA from the most recent usable watch context before it
falls back to the earliest unwatched regular episode in series order.

#### Scenario: Resume the most recent in-progress episode

- **GIVEN** a series has an in-progress episode that is the most recently watched episode context
- **WHEN** the detail view computes the hero CTA
- **THEN** the CTA targets that in-progress episode as a resume action

#### Scenario: Advance from the most recent completed episode

- **GIVEN** a series has no more recent in-progress episode
- **AND** the most recently watched episode context is a completed episode
- **WHEN** the detail view computes the hero CTA
- **THEN** the CTA targets the next episode in series order
- **AND** the CTA does not rewind to an older missing progress gap in an earlier season

#### Scenario: Fall back when no recent watch context exists

- **GIVEN** a series has no usable recent in-progress or completed watch context
- **WHEN** the detail view computes the hero CTA
- **THEN** the CTA falls back to the earliest unwatched regular episode in series order

#### Scenario: Specials are used only when no regular seasons exist

- **GIVEN** a series has both regular seasons and specials
- **WHEN** the detail view falls back because no recent watch context exists
- **THEN** the CTA chooses from regular-season episodes before specials
- **AND** specials are considered only when the show has no regular-season episodes

### Requirement: Manual season override controls episode-row entry

The detail view SHALL use the hero CTA target for season-tab down navigation only while the
selected season still matches the current auto-targeted season and no manual override is active.

#### Scenario: Auto-targeted season enters the CTA episode

- **GIVEN** the selected season matches the auto-targeted season chosen from the hero CTA
- **AND** there is no stored last-focused episode for that season
- **WHEN** the user presses down from the selected season tab
- **THEN** focus enters the episode row at the CTA target episode

#### Scenario: Manual season override enters the selected season

- **GIVEN** the user manually changes the selected season away from the auto-targeted season
- **AND** there is no stored last-focused episode for the newly selected season
- **WHEN** the user presses down from the selected season tab
- **THEN** focus enters the episode row for the selected season
- **AND** the hero CTA target does not pull focus back to the previous auto-targeted season

#### Scenario: Manual override lasts for the current detail session

- **GIVEN** the user manually changes the selected season away from the auto-targeted season
- **WHEN** episode progress or CTA state changes later during the same detail-screen session
- **THEN** the manual season override remains active for season-tab down navigation
- **AND** automatic CTA-based row entry is restored only after the user leaves and reopens the
  detail screen

#### Scenario: Season-local focus restoration wins

- **GIVEN** the selected season has a previously focused episode card
- **WHEN** the user re-enters the episode row from the season tabs
- **THEN** focus returns to that stored episode for the selected season
- **AND** this season-local restore target outranks the CTA target
