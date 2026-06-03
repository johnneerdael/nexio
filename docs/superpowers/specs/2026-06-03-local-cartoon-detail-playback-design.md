# Local Cartoon Detail Playback Design

Date: 2026-06-03

## Goal

Make Tekenfilms and Cartoons catalog items open the detail screen like normal content, while preserving deterministic playback from the detail page using the addon-provided source.

The prior local-cartoon integration optimized Modern Home first paint by bypassing detail navigation and starting playback directly. That is not viable for TV series because episode selection lives on the detail page.

## Current Problem

Modern Home currently treats matching local-cartoon items as `TekenfilmsDirectPlayback`. A click goes directly toward Player instead of Detail.

That works for a single playable movie-like item but breaks the TV flow:

- A series parent needs a detail screen to show seasons and episodes.
- The user must pick the episode before playback.
- Detail play and episode play should still resolve the addon-owned stream by default.

## Design

Split local-cartoon behavior into two independent concerns:

1. **Catalog rendering policy**
   - Keep the exact-origin policy for Modern Home first paint.
   - Keep no-truncation behavior for every approved local-cartoon rail.
   - Keep hydration skip behavior for those rows.
   - Keep manual stream selection pinning for Tekenfilms/Cartoons streams.

2. **Playback routing policy**
   - Stop bypassing Detail from Modern Home.
   - Matching Tekenfilms/Cartoons catalog items navigate to Detail like other catalog items.
   - Detail play and episode play pass the preferred addon base URL into the Stream route.
   - Stream autoplay uses that base URL to prefer the exact addon-owned source.

## Autoplay Source Selection

When the Stream route contains an addon base URL matching one of the approved local-cartoon origins:

- deterministic autoplay should choose a playable stream from that origin first;
- manual stream selection should continue showing local-cartoon streams pinned at the top;
- if the approved origin has no playable stream, Stream should fail or remain on selection rather than silently autoplaying another addon source.

This keeps the local-cartoon contract deterministic: content opened from Tekenfilms or Cartoons does not unexpectedly play from an unrelated addon.

## Expected Behavior

- Modern Home click on a Tekenfilms movie opens Detail.
- Modern Home click on a Tekenfilms series opens Detail.
- Series episode selection works from the detail page.
- Pressing Play on a detail page opened from Tekenfilms/Cartoons autoplays the matching Tekenfilms/Cartoons stream when available.
- Pressing Play on a selected episode from that detail page autoplays the matching episode stream from the same addon origin when available.
- Other addons keep normal detail navigation and normal stream selection/autoplay behavior.

## Test Plan

Add or update focused unit tests:

- Modern Home click resolution returns Detail for Tekenfilms/Cartoons rows.
- Detail-to-stream route creation passes the detail addon base URL for movie play and episode play.
- Stream autoplay selection prefers local-cartoon streams when the route addon base URL is local-cartoon.
- Stream autoplay does not select unrelated addon streams for local-cartoon deterministic playback when no local-cartoon stream is playable.
- Existing manual stream selection pinning tests remain passing.

Run targeted release tests around:

```bash
./gradlew testUniversalReleaseUnitTest \
  --tests com.nexio.tv.ui.screens.home.ModernHomeModelsTest \
  --tests com.nexio.tv.ui.navigation.* \
  --tests com.nexio.tv.ui.screens.stream.* \
  --tests com.nexio.tv.core.stream.StreamPresentationEngineTest
```

## Rollout Notes

This intentionally removes the home-click direct-player path for local cartoons. The dedicated direct playback ViewModel may become unused and should be removed if no other caller needs it.

The change should keep the approved origin gates from `TekenfilmsHomePlaybackPolicy`; do not introduce manifest-declared opt-in or broader addon behavior.
