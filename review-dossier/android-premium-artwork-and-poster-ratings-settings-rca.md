# Android Premium Artwork and Poster Ratings Settings RCA

Date: 2026-05-06

Device inspected: `192.168.50.98:5555`

Package inspected on device: `com.nexio.tv`, versionCode `73`, versionName `0.55`, lastUpdateTime `2026-05-06 01:35:45`

## Summary

Two issues were investigated:

1. Premium posters are selected by the metadata router but fail to load through the shared artwork system.
2. The poster ratings provider selection dialog is not scrollable, so overflowing settings/options are unreachable.

The premium poster issue is not caused by RPDB/Top Posters provider selection. Runtime logs show the metadata router selecting `RPDB` for the `POSTER` field. The failure is downstream: the shared artwork path emits `nexio-artwork://decision/...` models, but the Coil fetcher registered for `nexio-artwork://` only reads already-materialized asset files and returns `null` for decision refs.

The poster ratings dialog issue is caused by a plain `Column` inside `NexioDialog`. Other settings dialogs use a bounded `LazyColumn`; `NexioDialog` itself does not provide scrolling or height constraints.

## Evidence

### Device and app state

`adb devices -l` shows the target device connected:

```text
192.168.50.98:5555 device product:AM6 model:UGOOS_AM6 device:AM6
```

`adb shell pidof com.nexio.tv` returned a running process (`29212`).

`dumpsys package com.nexio.tv` showed:

```text
versionCode=73
versionName=0.55
lastUpdateTime=2026-05-06 01:35:45
```

### Metadata router selects premium poster provider

Logcat from the running app showed premium artwork selection working at the metadata layer:

```text
metadata.field_selected contentId=tmdb:1297842 field=POSTER selectedProvider=RPDB sourceRole=ARTWORK ownershipRule=premium artwork may override poster only
metadata.field_selected contentId=tmdb:1273221 field=POSTER selectedProvider=RPDB sourceRole=ARTWORK ownershipRule=premium artwork may override poster only
metadata.field_selected contentId=kitsu:10028 field=POSTER selectedProvider=RPDB sourceRole=ARTWORK ownershipRule=premium artwork may override poster only
```

That rules out "provider not selected" as the primary failure.

### Shared artwork emits decision refs for posters

`PosterRatingsUrlResolver.resolvePosterArtworkRef` writes an `ArtworkDecision` and returns `ArtworkDisplayRef.RuntimeAsset` with no asset key:

```kotlin
artworkDecisionCache.put(decision)

return ArtworkDisplayRef.RuntimeAsset(
    decisionKey = decisionKey,
    assetKey = null,
    imageType = ArtworkType.POSTER,
    selectedProvider = selected.provider,
    sourceRole = selected.sourceRole,
    ...
)
```

Source: `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt:160`

`resolvePosterArtworkString` then converts this to a legacy string via `toLegacyArtworkString()`. Because `assetKey` is null, the output is `nexio-artwork://decision/<decisionKey>`, not `nexio-artwork://asset/<assetKey>`.

Source: `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt:177`

### Coil accepts decision refs but cannot fetch them

`NexioArtworkFetcher.Factory` recognizes decision URIs:

```kotlin
parseDecisionKey(model)?.let {
    return NexioArtworkFetcher(
        assetKey = null,
        repository = repository
    )
}
```

Source: `app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt:52`

But `NexioArtworkFetcher.fetch()` immediately returns `null` when `assetKey` is absent:

```kotlin
val key = assetKey ?: return null
val file = repository.getExistingFile(key) ?: return null
```

Source: `app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt:21`

This means every `nexio-artwork://decision/...` poster is accepted by the fetcher factory and then produces no image.

### App ImageLoader wires an incomplete shared artwork repository

`NexioApplication` manually constructs the `NexioArtworkFetcher.Factory` with a new `ArtworkAssetRepository`:

```kotlin
ArtworkAssetRepository(
    runtime = integrationRuntime,
    diskCache = ArtworkAssetDiskCache(cacheDir),
    sourceMaterializer = ArtworkSourceMaterializer(emptyMap())
)
```

Source: `app/src/main/java/com/nexio/tv/NexioApplication.kt:107`

This repository does not receive the app's shared `ArtworkDecisionCache`, and it uses the default `UnregisteredArtworkByteLoader`. Even if `NexioArtworkFetcher` attempted decision materialization, this instance would not be able to load provider-template bytes.

### Settings dialog is not scrollable

`ArtworkProviderSelectionDialog` renders choices with a plain `Column`:

```kotlin
Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(10.dp)
) {
    choices.forEach { choice ->
        SettingsChoiceChip(...)
    }
}
```

Source: `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsScreen.kt:234`

`NexioDialog` wraps content in a fixed-width `Box` and a plain `Column`; it does not apply max height or scrolling to arbitrary dialog content.

Source: `app/src/main/java/com/nexio/tv/ui/components/NexioDialog.kt:48`

Other settings dialogs use a bounded `LazyColumn`, for example autoplay and audio settings dialogs. That pattern is absent here.

## Root Cause

### Premium posters

The shared artwork system has an unresolved decision-to-asset boundary.

The metadata layer now correctly emits typed shared artwork decisions for premium posters, but the UI image layer only supports already-materialized assets. Poster decisions are emitted as `nexio-artwork://decision/...`, while the registered fetcher can only read `nexio-artwork://asset/...` files from disk. Decision refs are accepted, then fail at fetch time.

There is a second wiring gap: the `ArtworkAssetRepository` used by Coil is manually constructed in `NexioApplication`, not injected as a complete singleton. It has no decision cache reference and no registered byte loader for RPDB/Top Posters provider templates.

### Poster ratings dialog

The dialog content is not using the scrollable settings-dialog pattern. It renders option chips in an unbounded `Column` inside a `NexioDialog` that has no intrinsic scroll support. When the choice list exceeds the available TV viewport height, lower options cannot be reached.

## Proposed Fix

### Fix shared artwork rendering

Implement the decision materialization path instead of falling back to legacy poster URLs.

Recommended shape:

1. Provide a singleton `ArtworkAssetRepository` through Hilt.
2. Give that repository:
   - the shared `IntegrationRuntime`
   - `ArtworkAssetDiskCache(cacheDir)`
   - `ArtworkSourceMaterializer`
   - a real `ArtworkByteLoader`
   - access to the shared `ArtworkDecisionCache`, either directly or through a small resolver used by `NexioArtworkFetcher`
3. Change `NexioArtworkFetcher` to carry both optional `assetKey` and optional `decisionKey`.
4. For `nexio-artwork://asset/...`:
   - return `repository.getExistingFile(assetKey)` as today
   - optionally fall back to decision lookup only if a reverse mapping exists
5. For `nexio-artwork://decision/...`:
   - look up the decision in `ArtworkDecisionCache`
   - call `ArtworkAssetRepository.getOrFetch(decision)`
   - return the fetched file as a `SourceResult`
   - return `null` only when the decision is missing or provider fetch fails
6. Implement `ArtworkByteLoader` for provider-template sources:
   - RPDB poster template -> fetch via `PosterTransport` or the RPDB provider's byte path
   - Top Posters poster template -> fetch via `PosterTransport` or the Top Posters provider's byte path
   - Top Posters thumbnail template -> preserve existing thumbnail path behavior
   - remote URL source -> fetch only when the raw URL is available in `ArtworkSourceMaterializer`
7. Add tests:
   - `NexioArtworkFetcher` resolves a decision URI by fetching and writing an asset.
   - `NexioArtworkFetcher` returns an existing asset file for asset URIs.
   - Missing decision returns `null` without crashing.
   - Provider-template byte loader builds the expected RPDB and Top Posters paths without exposing raw API keys in trace/log strings.
   - Metadata router selected `RPDB`/`TOP_POSTERS` poster produces a renderable Coil model.

Avoid using `integration-poster://` as the final fix for the shared artwork path. That would reintroduce the legacy bypass the shared artwork system is replacing.

### Fix poster ratings provider selection dialog

Change `ArtworkProviderSelectionDialog` to match the established settings-dialog pattern:

1. Replace the plain `Column` of choices with a bounded `LazyColumn`.
2. Wrap it in a `Box` with a max height, for example `heightIn(max = 320.dp)` or a viewport-derived max if available.
3. Add vertical content padding and stable item keys.
4. Ensure initial focus lands on the selected choice, or on the first choice when no selected item is available.
5. Add a Compose/UI test or screenshot verification that a choice list larger than the max dialog height remains navigable.

This can be a small UI-only patch independent of the premium artwork fix.

## Verification Plan

Premium artwork:

1. Add failing unit coverage for `nexio-artwork://decision/...` fetches before implementing.
2. Run the focused artwork/image tests.
3. Install on `192.168.50.98:5555`.
4. Clear only image/artwork caches if needed, not account settings.
5. Open home/detail surfaces where logs currently show `selectedProvider=RPDB`.
6. Confirm logcat shows the premium poster field selected and a successful artwork asset fetch/cache decision.
7. Confirm visible posters render instead of blank/fallback.

Poster ratings dialog:

1. Add enough test choices or use the real configured choices to exceed the dialog max height.
2. Verify DPAD navigation reaches every choice.
3. Compare against existing settings dialogs that use bounded `LazyColumn`.

## Current Decision

No app code was changed as part of this RCA. The proposed fixes above should be implemented in a separate patch with tests.
