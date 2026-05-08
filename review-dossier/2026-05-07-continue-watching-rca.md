# Continue Watching Feed RCA

Date: 2026-05-07

Scope: Android Continue Watching on rooted device `192.168.50.98:5555`, package `com.nexio.tv`, plus code comparison with `~/Scripts/NuvioTV`.

Status: root cause boundaries identified. No code fixes were applied.

## Executive Summary

Continue Watching is not currently using one shared canonical item identity or one shared metadata surface.

The pulled device snapshot proves that Citadel S02E01 is persisted twice in Continue Watching:

- local resume: `contentId=tvdb:393268`, `videoId=tvdb:393268:2:1`, `source=local`
- Trakt playback: `contentId=tt9794044`, `videoId=tt9794044:2:1`, `source=trakt_playback`

The duplicate is therefore not a Compose rendering duplication. It is already present in the profile snapshot.

Playback from the local Continue Watching entry fails because that route forwards the saved TVDB-shaped `videoId` into stream fetch. The stream repository contract expects series stream ids in IMDb episode shape, `IMDB_ID:season:episode`. Opening Citadel from detail succeeds because detail resolves and passes `streamVideoId=tt9794044:2:1`.

The localized metadata bug is also visible in the same device snapshot. The profile snapshot has `languageTag=nl`, and the route metadata snapshot for Citadel contains a Dutch description, but `displayMetadataByItemKey` for both Citadel keys contains the English description. The UI builds Continue Watching cards from `displayMetadataByItemKey`, so it can render English while a catalog rail on the same screen uses the localized catalog pipeline.

Newly aired episode surfacing is fragile because the active profile snapshot contains no next-up feed at all: `nextUpItems=[]`, `traktUpNextItems=[]`, and `scheduledReemit=[]`. If the tracking service does not emit a due next-up entry, Continue Watching has nothing to surface. NuvioTV has an additional older-seed/release-alert discovery pipeline that Nexio does not have in this snapshot service path.

## Evidence Collected

Device artifacts were pulled into `tmp/continue-watching-rca/device/`:

- `continue_watching_snapshot.xml`
- `continue_watching_snapshot_p2.xml`
- `home_catalog_snapshot.xml`
- `logcat-full.txt`
- `nexio-continue-debug.png`

Do not paste raw XML externally. The snapshot files include addon base URLs and credentials. This RCA only records redacted fields.

The device was rooted and connected as `192.168.50.98:5555`. The active app was `com.nexio.tv/.MainActivity`; installed `com.nexio.tv` was versionCode `73`, versionName `0.55`, last updated `2026-05-07 21:30:43`.

Logcat evidence:

- `logcat-full.txt` contains `ANR in com.nexio.tv` at `2026-05-07 02:52:47`, `02:53:04`, and `02:53:25`, all with `Input dispatching timed out`.
- The pulled logcat window does not contain a direct Citadel `StreamScreen` failure line. That is an evidence gap for the exact click, not for the route identity mismatch; the persisted snapshot plus code path reconstructs the failing route inputs.

## Finding 1: Continue Watching Playback Uses The Wrong Stream Identity

Confirmed data from the device snapshot:

```text
Citadel local resume
contentId: tvdb:393268
contentType: series
season: 2
episode: 1
videoId: tvdb:393268:2:1
source: local
```

Continue Watching constructs the stream route from the progress record:

- `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt:1274`
- `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt:1281`
- `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt:1292`

For `InProgress`, it passes:

```text
videoId = item.progress.videoId
contentId = item.progress.contentId
season = item.progress.season
episode = item.progress.episode
```

It does not pass `streamVideoId`, even though the route supports it:

- `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt:32`
- `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt:62`

The stream screen then fetches streams with:

- `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt:122`
- `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt:147`
- `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt:611`

```text
streamFetchVideoId = streamVideoId ?: videoId
getStreamsFromAllAddons(videoId = streamFetchVideoId)
```

The repository contract says series stream ids should be IMDb episode ids:

- `app/src/main/java/com/nexio/tv/domain/repository/StreamRepository.kt:14`

```text
for series: IMDB_ID:season:episode
```

Detail playback takes the successful route:

- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt:669`
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt:2592`

Detail resolves the IMDb sidecar id and passes `tt9794044:2:1` as `streamVideoId`. That explains the user report:

```text
Continue Watching local entry -> stream fetch id tvdb:393268:2:1 -> fails
Detail rail entry -> stream fetch id tt9794044:2:1 -> succeeds
```

Root cause: Continue Watching treats persisted resume `videoId` as a stream-fetch id. For provider-native TVDB local progress, that violates the stream repository's IMDb episode-id contract.

Confidence: high.

## Finding 2: Duplicate Citadel Is Raw-ID Dedup, Not UI Duplication

The active profile snapshot has two Citadel S02E01 resume rows:

```text
local row:
  contentId=tvdb:393268
  videoId=tvdb:393268:2:1
  source=local
  position=65066
  duration=2958656

trakt row:
  contentId=tt9794044
  videoId=tt9794044:2:1
  source=trakt_playback
  progressPercent=2.19917
  traktShowId=171028
  traktEpisodeId=13018336
```

Merge and snapshot dedup keys are raw ids:

- `WatchProgressRepositoryImpl.mergeProgressLists` keys by `progressKey(progress)`, which is `contentId` plus season/episode for episodes: `app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt:622`
- Merge stores remote and local under those raw keys: `app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt:630`
- Snapshot resume selection uses `distinctBy { it.contentId }`: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:736`
- Snapshot sanitization repeats `distinctBy { it.contentId }`: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:818`

The home UI has a second dedup pass, but its default identity key is also `contentId`:

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:66`
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:355`

That pass only has special projection support for anime cross-source identities. It does not collapse a TVDB series id and IMDb series id for the same show.

Root cause: Continue Watching lacks a canonical equivalence key at merge, snapshot, and UI dedup boundaries. `tvdb:393268` and `tt9794044` represent the same Citadel episode but are allowed to survive as separate rows.

Confidence: high.

## Finding 3: Continue Watching Does Not Reuse The Same Localized Metadata Surface As Rails

The active profile snapshot says:

```text
schemaVersion=5
languageTag=nl
```

In that same snapshot:

- `displayMetadataByItemKey["series:tvdb:393268"].description` is English.
- `displayMetadataByItemKey["series:tt9794044"].description` is English.
- `metadataSnapshotsByItemKey["series:tvdb:393268"].e.description` is Dutch.

So the localized metadata exists in the persisted Continue Watching snapshot, but the UI-facing display metadata map is still English.

The UI reads card text from `displayMetadataByItemKey`:

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:342`
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:990`
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:997`

Snapshot hydration rebuilds `displayMetadataByItemKey` separately from route metadata snapshots:

- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:1185`
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:1215`
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:1225`

It calls `fetchHomeDisplayMetadata(...)`, then renders display metadata from:

```text
canonical fetched metadata
clickTime metadata snapshot
persisted fallback
```

However, the snapshot proves the output display map is not the localized metadata that exists in `metadataSnapshotsByItemKey`.

There is also a key-shape mismatch for episode click-time metadata:

- `toContinueWatchingRecords` creates episode keys with a suffix like `parentId:s2e1`.
- `recordContinueWatchingRouteContextForPlayback` stores route metadata by parent key, `series:<contentId>`.
- Relevant code: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:84`, `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:596`

That mismatch can prevent episode-specific click-time metadata from being reused where the record path expects it.

Root cause: Continue Watching has a parallel display metadata cache that can diverge from canonical/localized route metadata and catalog rail metadata. The card render path trusts `displayMetadataByItemKey`, so it can show English even while the same content's catalog rail uses the localized metadata path.

Confidence: high for the divergence; medium for the episode-key mismatch as a contributing cause, because the exact render bug is already proven by the snapshot but that mismatch needs a focused test.

## Finding 4: Newly Aired Episodes Do Not Surface When The Snapshot Has No Next-Up Inputs

The active profile snapshot has:

```text
nextUpItems=[]
traktUpNextItems=[]
scheduledReemit=[]
```

With those fields empty, the Continue Watching UI cannot surface newly aired episodes from the snapshot.

Nexio's snapshot builder only works with the next-up entries it receives:

- it combines `observeContinueWatchingNextUp()` and `observeSyntheticContinueWatchingNextUp()` at `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:254`
- it normalizes and gates those entries in `buildRawSnapshot`: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:639`
- it filters to aired items through `AirDateGate`: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:662`
- it only schedules reemit for unaired candidates that are already present: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:702`

Profile 2's snapshot shows another failure shape: it has next-up entries, but with `firstAiredMs=0`, `tvdbAvailabilityPrecision=DATE_ONLY`, and `tvdbAvailabilityDiagnosticReason=MISSING_AIRS_TIME`. Those entries cannot be scheduled with a precise due instant. This supports a separate timing-availability risk, but it is not the active profile's primary issue because profile 1 had no next-up candidates at all.

Root cause: Continue Watching depends on tracking-service next-up streams and due scheduling for entries already in the snapshot. It does not have the Nuvio-style fallback that actively discovers next-up for older watched seeds and injects release alerts when an episode has newly aired after the user's last watched timestamp.

Confidence: medium-high. The empty active snapshot is conclusive for why nothing appeared at capture time; the upstream reason for why tracking emitted no next-up still needs provider-specific tracing.

## NuvioTV Comparison

NuvioTV does not have the same set of failure boundaries.

Progress persistence:

- Nexio still writes a series-level mirror when saving episode progress: `app/src/main/java/com/nexio/tv/data/local/WatchProgressPreferences.kt:137`
- Nuvio explicitly removes legacy series-level mirror keys for episode entries: `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/WatchProgressPreferences.kt:344`
- Nuvio's comment states the reason: mirror keys caused stale progress races.

This does not by itself explain the Citadel TVDB/IMDb duplicate, which is cross-id. It does show Nuvio removed one stale local-progress class that Nexio still preserves.

Metadata and language:

- Nuvio carries `contentLanguage` through Continue Watching stream navigation: `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt:166`
- Nuvio player progress saves retain the current playback `videoId` and content context: `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt:251`
- Nuvio does not have Nexio's `ContinueWatchingSnapshotService` split between `displayMetadataByItemKey` and `metadataSnapshotsByItemKey`, so the exact stale-English display map bug is not present in the same architecture.

Newly aired next-up:

- Nuvio's pipeline combines all progress, next-up seeds, remote load state, unaired setting, watched-item changes, and a refresh trigger: `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:254`
- It has an explicit older-seed path: `Discover next-up items for older seeds and inject release alerts into CW` at `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:702`
- It retains older/cached next-up items when they have aired or the user allows unaired: `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:956`
- It marks release alerts when a next episode aired after the user's last watched time and within 60 days: `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:2727`

This explains why Nuvio can surface newly aired in-progress shows in cases where Nexio's snapshot has no next-up candidates.

## Root Cause Map

```text
Playback failure:
local provider-native resume id
-> Continue Watching route omits streamVideoId
-> StreamScreen fetches by saved TVDB videoId
-> StreamRepository/addons expect IMDb episode id
-> stream fetch fails

Duplicate Citadel:
local progress tvdb:393268
+ Trakt playback tt9794044
-> merge/snapshot/UI dedup on raw contentId
-> both survive

Localized metadata mismatch:
catalog rail localized metadata path
!= Continue Watching displayMetadataByItemKey cache
-> snapshot contains Dutch route metadata but English display metadata
-> Continue Watching renders English

Newly aired missing:
tracking next-up streams empty or missing precise due timing
-> snapshot nextUp/traktUpNext/scheduledReemit empty
-> UI has no candidate to show
```

## Evidence Gaps And Next Diagnostics

These gaps remain because this RCA did not modify code or run an instrumented click reproduction:

- Capture a clean logcat around a fresh Citadel Continue Watching click and record the final stream route args: `videoId`, `streamVideoId`, `contentId`, `season`, `episode`, and `requestOrigin`.
- Add temporary diagnostics around `ContinueWatchingSnapshotService.buildRawSnapshot` to log counts and content ids from `allProgress`, provider next-up, synthetic next-up, and scheduled reemit.
- Add a snapshot assertion that `displayMetadataByItemKey` for `languageTag=nl` cannot be English when `metadataSnapshotsByItemKey` has localized Dutch metadata for the same canonical route.
- Add an identity-resolution assertion for `tvdb:393268` and `tt9794044` collapsing to one Continue Watching row for Citadel S02E01.

## Non-Fix Recommendations

Do not treat these as four unrelated UI bugs. The common architectural issue is that Continue Watching is a separate feed system with separate identity, metadata, and next-up rules from catalog/detail.

The fix should be designed around shared canonical identity and shared metadata routing:

- derive a canonical media identity before merging local and remote progress;
- resolve a stream-fetch identity separately from resume identity, the same way detail passes `streamVideoId`;
- remove or quarantine stale series mirror progress entries;
- make Continue Watching display metadata render from the same localized route/canonical metadata used by catalog/detail;
- add a Nuvio-style fallback for older watched seeds and newly aired release alerts, or move both apps to a shared next-up engine.
