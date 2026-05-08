# 2026-05-08 TV Artwork and Premium Poster Regression RCA

## Scope

This is root cause analysis only. No code fix was made.

User-reported symptoms:

- TV show posters, backdrops, and logos are still broken after the recent TVDB artwork changes.
- Premium poster behavior regressed: RPDB/premium decisions exist, but cards can show blank, non-premium, or wrong-shaped artwork.
- Some poster slots appear to use backdrops as posters.
- Trakt TV rails are worse than TMDB rails: TMDB first paint often has usable posters, while Trakt TV first paint often has only title/year and renders empty cards.
- The regression affects TV content broadly and also appears on some movies.

## Evidence Captured

Device:

```text
adb connect 192.168.50.98
package: com.nexio.tv
process pid during capture: 31027
device uptime: 4 days, 11:05
```

Logcat was not cleared. A snapshot was pulled from the existing buffers:

```text
review-dossier/2026-05-08-tv-artwork-regression-logcat.txt
```

Important caveat: the device log buffers are small:

```text
main buffer: 256Kb
system buffer: 256Kb
```

The captured log includes package replacement, app launch, and the visible Home session, but it cannot be treated as a complete multi-day startup history because older lines were already evicted by the ring buffer.

On-device app state was pulled with root without clearing app data:

```text
review-dossier/2026-05-08-tv-artwork-home_catalog_snapshot.xml
review-dossier/2026-05-08-tv-artwork-hydrated_home_overlay_v1.xml
review-dossier/2026-05-08-tv-artwork-catalog_disk_cache_v1.xml
review-dossier/2026-05-08-tv-artwork-decisions-v1.json
review-dossier/2026-05-08-tv-artwork-asset-records-v1.json
review-dossier/2026-05-08-tv-artwork-remote-sources-v1.json
```

## Executive Verdict

```text
Primary root cause:
Hydrated artwork overlays are being created, but the resolved Home display path still builds artwork/rating from first-paint MetaPreview fields and does not merge overlay.fields before producing ResolvedDisplayItem.

Secondary root cause:
Premium poster selection and premium poster materialization are not the same invariant. RPDB decisions can persist without asset records, and fallback materialization can write a TMDB/non-premium asset under the original RPDB decision key.

Tertiary root cause:
Portrait poster card selection can fall back to backdrop when poster is absent or unmaterialized, which turns missing poster data into wrong-shaped poster display.
```

This is not primarily a TVDB API availability issue. The overlay store proves TVDB logos and backdrops are present for affected TV shows. The failure is later in the Home/resolved display/materialization path.

## Finding 1: Hydrated Overlay Fields Exist But Are Ignored By Resolved Display Mapping

Evidence from the pulled overlay store shows TVDB hydration succeeded for affected TV shows.

Examples:

```text
The Boys
canonicalProvider=TVDB
canonicalId=355567
imdbId=tt1190634
poster=nexio-artwork://decision/...provider:RPDB...
backdrop=nexio-artwork://asset/...TVDB:backdrop...
logo=nexio-artwork://asset/...TVDB:logo...

Legends
canonicalProvider=TVDB
canonicalId=453615
imdbId=tt33265765
poster=nexio-artwork://decision/...provider:RPDB...
backdrop=nexio-artwork://asset/...TVDB:backdrop...
logo=nexio-artwork://asset/...TVDB:logo...

Daredevil: Born Again
canonicalProvider=TVDB
canonicalId=422712
imdbId=tt18923754
poster=nexio-artwork://decision/...provider:RPDB...
backdrop=nexio-artwork://asset/...TVDB:backdrop...
logo=nexio-artwork://asset/...TVDB:logo...
```

Aliases also exist, including:

```text
series:tmdb:76479 -> canonical:TVDB:355567
series:tvdb:355567 -> canonical:TVDB:355567
series:tmdb:262280 -> canonical:TVDB:453615
series:tvdb:453615 -> canonical:TVDB:453615
```

But [HomeResolvedDisplayMapper.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt:45) reads the overlay and then builds the display item from first-paint fields:

```kotlin
val itemKey = homeDisplayItemKey(apiType, id)
val overlay = overlaysByItemKey[itemKey]
val fields = toHomeDisplayMetadata()
...
artwork = fields.toResolvedArtworkBundle()
rating = fields.imdbRating ...
```

The `overlay` is used for canonical identity and hydration state, but `overlay.fields` is not merged into `fields` before artwork and rating are projected. There is a separate [HydratedHomeOverlay.toResolvedDisplayItem](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt:165) path that does use overlay fields, but the normal row item path does not.

Why this matches the screenshots:

- TMDB rails have first-paint poster/backdrop data, so they can look partially correct even if hydration is ignored.
- Trakt TV rows often have stable IDs but no first-paint poster/background/logo, so ignoring overlays renders blank/dark cards.
- TVDB logos can exist in overlays but still never reach Home/screensaver display items.

Logcat supports this first-paint gap. Trakt TVDB rows report:

```text
t=metadata.first_paint contentId=tvdb:355567 surface=HOME source=RAIL_PREVIEW routerExecuted=false networkExecuted=false used=[title,releaseInfo]
t=metadata.first_paint contentId=tvdb:422712 surface=HOME source=RAIL_PREVIEW routerExecuted=false networkExecuted=false used=[title,releaseInfo]
t=metadata.first_paint contentId=tvdb:350665 surface=HOME source=RAIL_PREVIEW routerExecuted=false networkExecuted=false used=[title,releaseInfo]
```

The first-paint payload only has title/releaseInfo. If the overlay is not merged into the resolved display item, there is no poster/logo/backdrop to display.

Confidence: high.

## Finding 2: Premium Decisions Are Persisted, But Premium Assets Are Often Missing

The artwork decision cache contains many selected RPDB decisions:

```text
artwork decision count: 2064
asset record count: 57
```

Examples from `artwork-decisions-v1.json` and `artwork-asset-records-v1.json`:

```text
The Boys, tmdb:series-76479:
  selectedProvider=RPDB
  selectedTemplate=idType=tmdb, mediaId=series-76479
  asset record exists

The Boys, tvdb:series-355567:
  selectedProvider=RPDB
  selectedTemplate=idType=tvdb, mediaId=series-355567
  asset record missing

Legends, tmdb:series-262280:
  selectedProvider=RPDB
  selectedTemplate=idType=tmdb, mediaId=series-262280
  asset record missing

The Chestnut Man, tmdb:series-127865:
  selectedProvider=RPDB
  selectedTemplate=idType=tmdb, mediaId=series-127865
  asset record missing

My Dearest Assassin, imdb:tt39749979:
  selectedProvider=RPDB
  selectedTemplate=idType=imdb, mediaId=tt39749979
  asset record missing
```

This means the shared router can correctly decide "premium wins" while the UI still cannot render a premium image because the selected decision URI has no materialized asset yet.

Logcat also shows missing decision/rehydration pressure:

```text
t=home.snapshot_decision_lookup
decisionLookupCount=184
decisionFoundCount=182
missingDecisionCount=2
rehydrateRequestCount=2
missingDecisionSamples=catalogRows[7].items[13]:decision:rpdb|fullCatalogRows[7].items[13]:decision:rpdb

t=artwork.orphan_decision_ref_rehydrate_requested
```

This is not enough by itself to explain every blank card, because some missing assets may materialize on demand. But it proves the rendered model depends on runtime rehydration for persisted premium decision URIs.

Confidence: high.

## Finding 3: Fallback Materialization Can Store Non-Premium Assets Under A Premium Decision Key

[ArtworkAssetRepository.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt:120) materializes a selected decision, then tries fallback candidates if selected materialization fails:

```kotlin
val result = getOrFetch(decision) ?: getOrFetchFallback(decision)
```

Inside [getOrFetchFallback](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt:221), the fallback decision is created by copying the original decision and swapping only the selected candidate:

```kotlin
val fallbackDecision = decision.copy(
    selectedCandidate = PersistedArtworkCandidate(...),
    rejectedCandidates = emptyList()
)
val result = getOrFetch(fallbackDecision) ?: continue
```

The original `decisionKey` is therefore retained while the selected candidate can become a TMDB/non-premium fallback.

The asset store confirms this state exists. Example:

```text
Daredevil: Born Again
decisionKey=artwork-decision:poster:canonical:tmdb:series-202555:provider:RPDB:premium:true:...
asset record provider=TMDB
asset record imageType=POSTER
```

So a premium RPDB decision key can recover or render a non-premium TMDB asset. That explains why traces or provider tags can still imply premium/RPDB while the actual visible poster is non-premium.

This is a serious invariant break:

```text
selected premium decision != rendered premium asset
```

Confidence: high.

## Finding 4: Backdrops Can Be Used In Poster Slots When Poster Is Missing

[ModernHomeRows.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt:123) allows poster card model selection to fall back to backdrop:

```kotlin
val typedModel = if (useBackdrop) {
    artwork?.backdrop.toCoilModelOrNull() ?: artwork?.poster.toCoilModelOrNull()
} else {
    artwork?.poster.toCoilModelOrNull() ?: artwork?.backdrop.toCoilModelOrNull()
}
```

The legacy fallback path also permits background after poster for poster mode:

```kotlin
firstNonBlank(
    item.metaPreview?.poster,
    item.heroPreview.poster,
    item.metaPreview?.background,
    item.heroPreview.backdrop
)
```

This makes a missing/unmaterialized poster degrade into a backdrop in a portrait poster slot. With TV shows, this becomes more visible because:

- Trakt first paint often has no poster.
- Hydrated overlay poster can be a premium decision URI that has no asset yet.
- Hydrated overlay backdrop can already be a TVDB asset.
- The card selector is allowed to use backdrop after poster fails.

Confidence: medium-high. Code proves the fallback rule. The exact rendered source for each screenshot card still needs per-card Coil/artwork fetch trace to prove whether the displayed image was the typed backdrop or a legacy background fallback.

## Finding 5: TVDB API Shape Is Not The Current Blocking Issue

`apiblueprints/tvdb.yml` confirms the expected TVDB endpoints and payload shape:

- `/series/{id}/extended` is documented in [apiblueprints/tvdb.yml](/Users/jneerdael/Scripts/nexio/apiblueprints/tvdb.yml:1761).
- Its `short` parameter says the short record excludes characters and artworks.
- `/series/{id}/artworks` is documented in [apiblueprints/tvdb.yml](/Users/jneerdael/Scripts/nexio/apiblueprints/tvdb.yml:1685).
- `SeriesExtendedRecord.artworks` is present in [apiblueprints/tvdb.yml](/Users/jneerdael/Scripts/nexio/apiblueprints/tvdb.yml:3885).

The pulled overlay store proves TVDB backdrop/logo assets are already being produced for affected titles. Therefore the missing logo/backdrop symptom has moved downstream from TVDB fetch/parsing into Home overlay application, resolved display projection, and artwork materialization.

Confidence: high.

## How The Three Failures Combine

The failure chain for Trakt TV rows is:

```text
Trakt TV first paint
  -> stable IDs and title/year only
  -> no poster/background/logo
  -> TVDB/RPDB hydration overlay exists
  -> HomeResolvedDisplayMapper looks up overlay but does not merge overlay.fields
  -> ResolvedDisplayItem uses empty first-paint artwork
  -> card renders blank/play placeholder
```

The failure chain for TMDB TV rows is:

```text
TMDB TV first paint
  -> has TMDB poster/backdrop
  -> looks partially correct before hydration
  -> TVDB/RPDB hydration overlay exists
  -> overlay fields are not consistently projected into resolved display item
  -> hydrated TVDB logo may not reach Home/screensaver
  -> if another path uses overlay poster decision, RPDB decision may lack materialized asset
  -> fallback can render TMDB/non-premium asset under RPDB decision key
```

The failure chain for wrong-shaped posters is:

```text
poster slot requests poster
  -> selected poster is absent/unmaterialized
  -> card model fallback allows backdrop/background
  -> landscape backdrop can appear inside portrait poster card
```

## Rating Side Note

`My Dearest Assassin` shows `TMDB 0.0` in the provided screenshot. That is a separate rating-quality issue, not the artwork root cause.

The earlier rating sanitizer rejects out-of-range title ratings, but `0.0` is inside the valid numeric range. If TMDB preview/hydration supplies a placeholder zero for unrated content, range validation alone will not reject it. That requires source-aware rating semantics, not an artwork fix.

## What Would Prove This Fully

Add diagnostic traces before making fixes:

```text
HomeResolvedDisplayMapper:
  itemKey
  firstPaint poster/backdrop/logo present
  overlay present
  overlay.fields poster/backdrop/logo present
  resolved artwork poster/backdrop/logo selected

NexioArtworkFetcher / ArtworkAssetRepository:
  decisionKey hash
  selected decision provider
  materialized asset provider
  fallback materialized true/false
  asset found/missing

Card artwork selection:
  card type poster/backdrop
  typed poster present
  typed backdrop present
  selected model source type
```

Expected confirming traces:

```text
Trakt The Boys:
  firstPaint poster=false backdrop=false logo=false
  overlay poster=true backdrop=true logo=true
  resolved poster=false backdrop=false logo=false

Legends:
  selected decision provider=RPDB
  materialized asset missing OR fallback provider=TMDB

Any wrong-shaped poster:
  card mode=POSTER
  selected sourceType=BACKDROP
```

## Root Cause Classification

```text
Category: shared display projection regression
Blast radius: Home, resolved display surfaces, screensaver candidates, TV rows, premium artwork rows
Primary broken invariant: hydrated overlay fields must be applied before display projection
Secondary broken invariant: premium decision references must not silently resolve to non-premium assets without explicit fallback state
Tertiary broken invariant: portrait poster slots must not use backdrop as a normal poster fallback
```

## RCA Conclusion

The recent TVDB artwork work appears to have repaired part of the provider side: TVDB logos and backdrops exist in hydrated overlays for affected TV shows.

The current regression is downstream:

```text
1. HomeResolvedDisplayMapper looks up overlays but keeps using first-paint fields for artwork/rating.
2. Premium RPDB decision URIs are persisted even when the premium asset is not materialized.
3. Fallback materialization can bind a non-premium asset to a premium decision key.
4. Poster card selection can fall back to backdrops when poster materialization fails.
```

That combination explains why TMDB rails can look correct on first paint, why Trakt TV rails render empty, why hydrated TVDB logos still do not show reliably, why premium posters appear lost, and why backdrops can appear in poster slots.
