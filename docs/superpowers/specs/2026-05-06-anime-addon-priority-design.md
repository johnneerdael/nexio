# Anime Addon Priority — Design

**Date:** 2026-05-06
**Status:** Approved for implementation planning
**Owner:** John Neerdael

## Problem

Generic stream addons (Torrentio, StremThru, AIO, etc.) routinely return poor or absent results for anime titles, where naming conventions, season/episode mappings, and source pools differ from mainstream Western movies and shows. Users who curate dedicated anime addons have no way to tell NEXIO that those addons should be preferred when fetching streams for anime content.

Today, addons carry one categorization dimension — `parserPreset` (`GENERIC | STREMTHRU | TORRENTIO | WEBSTREAMR`) — which only drives stream-line parsing. Addon order in the stream list is governed by `sortOrder`, which is content-agnostic.

## Goal

Introduce a per-addon "anime specialty" tag that, when content is detected as anime, floats anime-tagged addon sections above the rest in both the manual stream list and the autoplay candidate stream order. Within each bucket, the existing `sortOrder` rule applies unchanged. For non-anime content, behavior is byte-identical to today.

## Non-goals

- **Catalog/rail ordering.** Catalog rails can contain mixed content; the priority is purely a *stream-resolution* concern. Rail composition is unaffected.
- **Search/meta resolution.** Only the stream pipeline reads the new flag.
- **Auto-detection.** No heuristic that infers an addon is anime from its manifest. Users opt in explicitly per addon.
- **Multi-specialty taxonomy.** A boolean is enough for this iteration. A future migration to `specialty: 'anime' | 'kids' | ...` is not blocked by this design.

## Decisions locked in

| Decision | Choice |
|---|---|
| When does the priority kick in? | **Content-aware.** Only when content is detected as anime. |
| Sort vs filter? | **Soft re-bucket.** All addons fetched in parallel; anime-tagged sections sort above the rest. No fetch gating. |
| Anime detection | Reuse the existing `AnimeIdentityIndex.resolveKitsuId(ParsedMetadataId)` — no parallel system. |
| Schema shape | **Boolean `isAnime`** on `AddonRecord`. Single field, optional, defaults to `false`. |
| Surface scope | **Streams only.** Manual stream list and autoplay. Catalogs, search, meta untouched. |
| Pipeline seam | **Tag at repository, sort at consumer.** Repo computes `isAnimeBucket` once per request and stamps it on each emitted `AddonStreams`; the existing `StreamAutoPlaySelector.orderAddonStreams` comparator applies the bucket sort. |

## Architecture

### Data model

**Web — `nexio-web/types/portal.ts`:**
```ts
export type AddonRecord = {
  // ...existing fields...
  isAnime?: boolean   // default false; absent on read = false
}
```

**Android — `com.nexio.tv.domain.model.Addon`:**
```kotlin
data class Addon(
    // ...existing fields...
    val isAnime: Boolean = false,
)
```

**Android — `com.nexio.tv.domain.model.AddonStreams`:**
```kotlin
data class AddonStreams(
    val addonName: String,
    val addonLogo: String?,
    val streams: List<Stream>,
    val isAnimeBucket: Boolean = false,   // = addon.isAnime && contentIsAnime
)
```

The persisted source of truth is the per-account portal record. Both nexio-web and Android read/write through the same record shape.

### Editing UI

**nexio-web addon manager:** new "Anime" toggle on each addon row and on the add-addon dialog, persisted via the existing addon-update mutation. Helper text: "Prioritize for anime content."

**Android `AddonManagerScreen`:** new toggle row in the addon edit panel, mirroring how `parserPreset` is exposed today. Plumbs through a new `AddonManagerViewModel.updateAddonIsAnime(baseUrl, value)` method, mirroring the existing `updateAddonParserPreset`.

No badge or grouping in the addon-list view; the toggle in the edit panel is sufficient discoverability.

### Stream pipeline

**Resolve `contentIsAnime` once per request** in `StreamRepositoryImpl.fetchStreams`, immediately after `streamAddons` is computed:

```kotlin
val parsedId = MetadataIdParser.parse(videoId)
val contentIsAnime = parsedId?.let {
    animeIdentityIndex.resolveKitsuId(it) != null
} ?: false
```

`AnimeIdentityIndex` is a `@Singleton` injected into the repository. The signal covers Kitsu/MAL/AniList/AniDB native IDs *and* the IMDB→Kitsu mapping pack — exactly the same definition the rest of the app already uses (`MetadataRouter`, `TrackingScrobbleService`, anime season projection).

**Tag each emitted `AddonStreams`** at the existing emit site (~line 113 of `StreamRepositoryImpl`):

```kotlin
emittedAddonStreams = AddonStreams(
    addonName = addon.displayName,
    addonLogo = addon.logo,
    streams = streamsResult.data,
    isAnimeBucket = addon.isAnime && contentIsAnime,
)
```

Fetch parallelism is unchanged — every installed stream-resource addon still launches concurrently. The bucket flag is metadata, not a fetch gate.

### Consumer sort

Single seam: `StreamAutoPlaySelector.orderAddonStreams`, called from `StreamScreenViewModel:408` and feeding both the manual stream list (`addonStreams = organizedResult.orderedAddonStreams` at line 514) and the autoplay candidate flatten (`orderedAddonStreams.flatMap { it.streams }` at line 412).

**Today:**
```kotlin
return streams.sortedBy { addonStreams ->
    installedOrder.indexOf(addonStreams.addonName).let { index ->
        if (index >= 0) index else Int.MAX_VALUE
    }
}
```

**After:**
```kotlin
return streams.sortedWith(
    compareByDescending<AddonStreams> { it.isAnimeBucket }
        .thenBy { addonStreams ->
            installedOrder.indexOf(addonStreams.addonName).let { index ->
                if (index >= 0) index else Int.MAX_VALUE
            }
        }
)
```

`candidateAutoPlayStreams` is unchanged: because it consumes a `List<Stream>` flattened *after* `orderAddonStreams`, autoplay automatically picks anime-bucket streams first when scanning for `FIRST_STREAM` and `REGEX_MATCH` matches.

**Web parity:** the same two-level comparator is applied client-side in nexio-web's stream view, keyed on the `isAnimeBucket` field returned by the API.

## Edge cases

| Case | Behavior |
|---|---|
| Non-anime content | Every addon's `isAnimeBucket = false`. Comparator's first key collapses. Ordering byte-identical to today. |
| Anime content, no anime-tagged addons installed | All `isAnimeBucket = false`. Comparator collapses. Ordering identical to today. Feature invisible until at least one addon is tagged. |
| Anime-tagged addon returns zero streams | Generic-tagged addons ran in parallel and their streams are visible below an empty anime section. No fallback logic needed — never gated fetching. |
| `MetadataIdParser.parse(videoId)` returns null | `contentIsAnime = false`. Behavior identical to today. Anime-priority is only ever additive, never subtractive. |
| Older Android client reads new server data | Unknown `isAnime` field ignored at JSON parse. No crash. |
| Newer Android client reads old server data without `isAnime` | Decoded as `false` via Kotlin default. No migration step. |

## Migration

None. The new field is optional on the wire (`isAnime?: boolean` web / `Boolean = false` Android default). No data migration script, no schema version bump.

## Testing

**Android unit tests (new):**
- `StreamAutoPlaySelectorAnimePriorityTest` — anime-bucket sections sort above non-anime; ties resolved by `installedOrder`; mixed input produces stable two-level ordering; all-non-anime input ordering is byte-identical to legacy output (regression guard).
- `StreamRepositoryImplAnimeBucketTest` — `isAnimeBucket = true` only when both `addon.isAnime` and `AnimeIdentityIndex.resolveKitsuId(parsedId) != null`; falsy when either condition fails; unparseable `videoId` → all buckets false.
- `AddonManagerViewModelAnimeToggleTest` — toggling `isAnime` persists through the existing addon-update path.

**nexio-web tests (new):**
- API serializer round-trip for `isAnime` (present, absent, explicit false).
- Stream-list comparator parity — shared fixture with the Android test, asserts identical ordering output.

**Trace events (new):**
- `streamRequest.contentIsAnime` (boolean, once per request).
- `addonStreams.isAnimeBucket` (per addon section).

These let us confirm in production traces that the flag was computed correctly without re-running the request.

## Out-of-scope follow-ups

- Multi-specialty schema (`specialty: 'anime' | 'kids' | 'sports' | ...`). Re-evaluate after a second specialty appears.
- Per-content-type override (e.g. "this addon is anime for series only"). Not requested.
- Visual grouping in the addon manager list. Skipped per user direction.
- Catalog/rail ordering by addon specialty. Explicitly out — rails contain mixed content.
