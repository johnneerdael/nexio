# Media Detail: Decision Hub Architecture

## Audience
This guide is for users who want both efficient day-to-day use and a deeper understanding of how the Detail screen manages focus, trailer, and playback handoff.

## What this page covers
- Detail screen role in the app flow
- Trailer mode and back behavior
- Episode return focus and next-episode targeting
- Practical selection workflow

## Source of truth
Detail behavior is implemented in:
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`

## Why the Detail screen matters
The Detail screen is the decision hub between discovery and playback.
It combines:
- Metadata context
- Actions like Play and Trailer
- Episode-level targeting for series
- Rich people and organization navigation paths

## Navigation and focus model
Detail is integrated with explicit return-focus parameters.
When returning from playback, Nexio can target the relevant episode focus state instead of resetting focus to top-level hero actions.

This improves high-volume binge workflows by reducing remote clicks.

## Trailer interaction model
Trailer mode has a dedicated interaction layer.

- Pressing Back while trailer is active does not immediately leave the detail page
- Back first exits trailer playback state
- A tokenized focus-restore mechanism returns focus to the intended control

This separation prevents accidental loss of page context.

## Episode progression targeting
For series, the detail screen can resolve the next actionable episode using:
- Requested return season and episode
- Progress completion map
- Watched episode set

If a requested episode is already completed, Nexio can move focus intent to the next logical episode.

## Practical high-confidence workflow

### 1. Open detail from Home or Search
Press OK on a title card.

**Expected result:** Metadata and action surface load with stable focus.

### 2. Validate content identity
Check year, runtime, and genre before opening streams.

**Expected result:** You confirm the correct title before stream selection.

### 3. Use Trailer when uncertain
Play trailer for a fast quality check.
Press Back once to return to Detail without losing context.

**Expected result:** Trailer exits and focus returns cleanly.

### 4. Start playback
Use Play to transition to stream selection and then player.

**Expected result:** Detail context is passed forward for playback and return logic.

## Troubleshooting

### Symptom
Back behavior feels inconsistent while trailer is active.

### Likely cause
You are in trailer mode, where Back is consumed to stop trailer first.

### Recovery
1. Press Back once to end trailer mode.
2. Press Back again only if you want to leave Detail.

### Verification
You can reliably control whether you exit trailer or leave the page.

## Implementation note
Detail also enforces display mode safety outside main player sessions through frame-rate utility hooks, reducing mode-switch side effects during browsing.

## Next page
Continue with [Playback Interface](./player.md) for panel stack, back-stack behavior, and frame-rate lifecycle.
