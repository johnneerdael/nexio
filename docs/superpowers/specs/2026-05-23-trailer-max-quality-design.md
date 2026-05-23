# Trailer Max Quality Setting Design

## Goal

Add a user-facing global setting that caps YouTube trailer playback quality at
720p, 1080p, or 2160p. The default is 1080p so trailers stay sharp while
avoiding the heavier 4K software-decoding path unless the user opts in.

The setting applies to every trailer surface: home previews, detail trailers,
screensaver trailers, and manually started trailers.

## Current State

Trailer settings already live in `TrailerSettingsDataStore` and are exposed
through `TrailerSettings`. Playback settings already renders trailer controls
in the General section:

- Autoplay Trailers
- Trailer Delay

YouTube trailer stream selection currently uses a hard-coded
`TRAILER_MAX_ADAPTIVE_HEIGHT = 2160` in `InAppYouTubeExtractor`. That cap is
applied before selecting the best adaptive video/audio pair, so the extractor
is the correct layer to enforce a quality preference. `TrailerPlayer` receives
already-selected URLs or DASH data URIs, so a player-layer cap would be too
late and incomplete.

## User Experience

Add a new row under Trailer Delay in Playback settings:

- Title: Trailer Quality
- Subtitle: 720p, 1080p, or 2160p
- Enabled when trailers are enabled

Selecting the row opens a small dialog with three choices:

- 720p
- 1080p
- 2160p

Changing the setting affects future trailer resolutions. Already-playing
trailers do not need to be restarted or live-reselected.

## Data Model

Introduce a small trailer-quality model:

```kotlin
enum class TrailerMaxQuality(val maxHeight: Int) {
    P720(720),
    P1080(1080),
    P2160(2160)
}
```

`TrailerSettings` gains:

```kotlin
val maxQuality: TrailerMaxQuality = TrailerMaxQuality.P1080
```

`TrailerSettingsDataStore` persists the value as a small scalar preference.
Unknown or missing values resolve to `P1080`. This keeps migration behavior
deterministic for existing installs.

## Extraction Behavior

`InAppYouTubeExtractor` will receive the current `TrailerSettingsDataStore`
through Hilt and read `settings.first()` once at the start of each extraction.
The extracted `maxQuality.maxHeight` will replace the hard-coded
`TRAILER_MAX_ADAPTIVE_HEIGHT` in adaptive stream filtering.

Selection rules:

1. Filter video candidates to heights `1..maxHeight`.
2. If capped candidates exist, choose the highest height at or below the cap.
3. At that height, prefer cheaper codecs in this order:
   - H.264 / AVC
   - VP9
   - other non-AV1
   - AV1
4. If no candidates exist under the cap, fall back to the existing uncapped
   candidate list rather than failing trailer playback.

This preserves the current 2160p behavior when the user opts in while making
1080p the default global cap.

## Components

- `TrailerSettingsDataStore`: persist and expose `maxQuality`.
- `TrailerSettings`: carry the setting to UI and services.
- `PlaybackSettingsViewModel`: add `setTrailerMaxQuality`.
- `PlaybackSettingsScreen` / `PlaybackSettingsSections`: add dialog state,
  row, and setter wiring.
- `InAppYouTubeExtractor`: replace the compile-time cap with the configured
  cap for adaptive stream selection.
- Tests: update existing trailer selection tests and add default/persistence
  coverage.

## Error Handling

Invalid stored preference values fall back to `P1080`.

If the selected quality cap has no compatible adaptive video candidate, the
extractor falls back to the current uncapped candidate handling so trailers do
not fail solely because a cap was too restrictive for an unusual YouTube
response.

## Testing

Add or update tests for:

- `TrailerSettingsDataStore` default is `P1080`.
- persisted values round-trip for 720p, 1080p, and 2160p.
- adaptive selection caps to 720p when 1080p/2160p candidates exist.
- adaptive selection caps to 1080p by default.
- 2160p retains current codec preference: H.264 before VP9 before AV1 at the
  selected highest height.
- fallback behavior when no candidate exists under the cap.
- `PlaybackSettingsViewModel.setTrailerMaxQuality` delegates to
  `TrailerSettingsDataStore`.

Manual validation:

- Install on `192.168.50.98`.
- Set Trailer Quality to 1080p and confirm logs show
  `bestAdaptiveVideo=1080p`.
- Set Trailer Quality to 2160p and confirm the known VP9 trailer can select
  `bestAdaptiveVideo=2160p`, `bestAdaptiveVideoCodec=vp9`, and reach READY.
- Set Trailer Quality to 720p and confirm candidates above 720p are skipped.

## Scope

In scope:

- One global trailer quality cap.
- Values limited to 720p, 1080p, and 2160p.
- Default 1080p for existing and new installs.
- Extractor-level stream selection enforcement.

Out of scope:

- Per-surface quality preferences.
- Separate manual-vs-autoplay quality preferences.
- Automatic device capability detection.
- Runtime downgrade after decoder stalls.
- Changing movie/episode playback quality selection.
