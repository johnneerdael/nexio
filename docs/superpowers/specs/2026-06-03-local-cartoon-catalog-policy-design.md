# Local Cartoon Catalog Policy Design

Date: 2026-06-03

## Goal

Allow the Tekenfilms local-cartoon addon integration to support any number of Modern Home catalog rails without requiring Nexio code changes for each catalog id.

The existing integration is correct for the original `tekenfilms_nl` rail, but the special behavior is still coupled to that single catalog id. The new addon shape includes additional rails such as `tekenfilms_series_nl`, and future rails should inherit the same behavior automatically when they come from the approved local-cartoon addon origins.

## Current Constraint

`TekenfilmsHomePlaybackPolicy` currently requires `catalogId == "tekenfilms_nl"`. That one check is reused by several surfaces:

- Modern Home row truncation bypass.
- Refresh-time and visible-home hydration skip.
- Modern Home detail-bypass click handling.
- Tekenfilms direct playback route construction.
- Presentation fallback that keeps first-paint items visible when hydration was skipped.

Because those callers all route through the policy, the policy is the correct boundary for the fix.

## Design

Remove catalog id from the eligibility contract.

A local-cartoon row or item is special only when all of these are true:

- The normalized addon base URL is exactly `https://tekenfilms.nexioapp.org` or `https://cartoons.nexioapp.org`.
- The addon id is exactly `org.nexio.tekenfilms`.
- The media type is supported: `movie` or `series`.
- The item id is supported: `tekenfilms:` prefixed ids or IMDb ids in movie or episode form, such as `tt0103639` or `tt1234567:1:2`.

The catalog id may be any non-null or null value. It is metadata for rail identity and display ordering, not authorization for the special playback behavior.

## Expected Behavior

Every catalog rail from the exact Tekenfilms or Cartoons origins receives the same existing local-cartoon behavior:

- Modern Home does not truncate the rail to 25 items.
- Home refresh and visible-home hydration skip these rows.
- Clicking a matching item bypasses detail navigation.
- Direct playback requests streams only from the clicked addon origin.
- Series episode ids use `stream/series/<id>`.
- Manual stream selection pins Tekenfilms and Cartoons streams above other addon streams.

Other addons do not receive this behavior, even if they reuse a similar catalog id or item id shape.

## Rejected Alternatives

### Catalog id prefix allow-list

Allowing ids such as `tekenfilms_*` would support the current expansion but would keep Nexio coupled to addon catalog naming. It would also make future renames or differently named rails require another Nexio patch.

### Manifest behavior hint

A manifest hint could make the behavior declarative, but it expands the addon contract and creates a trust/parsing decision for a behavior that must remain exclusive to two exact origins. The exact-origin policy is simpler and safer.

## Test Plan

Add or update focused unit tests:

- Policy accepts a Tekenfilms row with a non-`tekenfilms_nl` catalog id.
- Policy accepts a Tekenfilms series row such as `tekenfilms_series_nl`.
- Policy still rejects wrong addon ids, wrong origins, unsupported media types, and unsupported item ids.
- Modern Home click resolution uses direct playback for a non-original catalog id.
- Existing stream presentation tests continue to verify Tekenfilms/Cartoons stream pinning in manual selection.

Run the targeted release test slice:

```bash
./gradlew testUniversalReleaseUnitTest \
  --tests com.nexio.tv.core.addon.TekenfilmsHomePlaybackPolicyTest \
  --tests com.nexio.tv.ui.screens.home.ModernHomeModelsTest \
  --tests com.nexio.tv.ui.navigation.TekenfilmsDirectPlaybackViewModelTest \
  --tests com.nexio.tv.core.stream.StreamPresentationEngineTest
```

## Rollout Notes

No migration is required. Existing cached rows with `tekenfilms_nl` keep working, and newly fetched rows with any other catalog id from the approved origins become eligible on the next catalog refresh.

The change is intentionally narrow: it relaxes only catalog id matching and keeps domain, addon id, type, and item id gates intact.
