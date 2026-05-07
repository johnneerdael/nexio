# Android Home And Screensaver Slowdown RCA

Date: 2026-05-07

ADB devices observed:

- `192.168.50.98:5555`
- Earlier comparison runs on `192.168.50.71:5555`

Package: `com.nexio.tv`

Status: root cause is narrowed to an artwork decision persistence write-amplification path. Trailer playback is not the primary cause of the interface slowdown seen on `192.168.50.98`.

## Executive Summary

The severe UI slowdown reproduced on the rooted Android TV device is not explained by trailer playback itself. Trailers historically worked, and the current on-device evidence points elsewhere.

The strongest proven bottleneck is:

```text
episode / thumbnail artwork decisions
-> DurableArtworkDecisionCache.put(...)
-> persistLocked()
-> serializes and rewrites the entire artwork decision store
-> repeats once per thumbnail decision
-> large object allocation + repeated full JSON writes
-> continuous GC and jank while UI is active
```

This was visible in logcat as hundreds of alternating:

```text
artwork.decision_put ... imageType=THUMBNAIL ... tt0239195:SxEy ...
artwork.decision_store_write success=true decisionCount=515...
Background concurrent copying GC freed 55MB / 94MB / 72MB / 107MB ...
```

The device simultaneously showed:

- app process using more than 200% CPU in `top -H`
- `HeapTaskDaemon` using 60-70% CPU
- app PSS rising to about 672 MB
- Dalvik heap allocated about 411 MB
- `gfxinfo` jank above 90%

This is enough to make both Home and screensaver feel choppy even if trailer playback is not doing anything wrong.

## What Was Ruled Out

### Trailer playback as the primary cause

Trailer playback was a valid suspect because the user-visible complaint was choppy trailer screensaver playback. But the current slowdown reproduced even when the relevant trace window showed the dominant churn in artwork decision persistence and GC, not trailer decode.

Evidence:

- `top -H` showed main app CPU and `HeapTaskDaemon` dominating.
- logcat showed repeated artwork decision writes and GCs.
- after removing bulk HOME trailer candidate resolution and reducing snapshot trace spam, the app still became severely janky.
- the post-patch log still showed severe memory/GC pressure without a corresponding flood of HOME trailer resolver events.

Conclusion:

```text
Trailers may expose the slowdown because they need smooth frame delivery, but they are not the root allocator/write hot path proven by the logs.
```

### Logcat trace volume as the only cause

The first run did show excessive per-item snapshot trace events:

```text
home.snapshot_decision_lookup scope=fullCatalogRows...
home.snapshot_sanitize_artwork scope=fullCatalogRows...
```

Those were reduced to summary events in a later diagnostic patch. That removed one obvious source of allocation and log spam.

However, after that change the device was still worse:

```text
PSS: ~672 MB
Dalvik allocated: ~411 MB
HeapTaskDaemon: ~71.8% CPU
gfxinfo janky frames: 91.85%
```

Conclusion:

```text
Per-item snapshot trace logging was a real contributor, but not the main remaining root cause.
```

### Direct YouTube trailer URL behavior

The screensaver architecture work correctly identified direct trailer bypasses as a separate design problem. But the observed slowdown on `192.168.50.98` correlated with artwork decision store writes, not direct YouTube URL construction.

Conclusion:

```text
Direct trailer bypasses still need architectural cleanup, but they do not explain the current app-wide slowdown evidence.
```

## Timeline Of Evidence

### Initial slowdown sample on `192.168.50.98`

PID:

```text
10731
```

Window manager state:

```text
com.nexio.tv/com.nexio.tv.MainActivity focused and visible
Display current/base: 3840x2160
Density: 640 dpi
No ANR
```

Memory:

```text
TOTAL PSS: ~345 MB
Dalvik Heap allocated: ~199 MB
Native Heap allocated: ~72 MB
```

Frame metrics:

```text
Total frames rendered: 957
Janky frames: 795 (83.07%)
50th percentile: 38ms
90th percentile: 350ms
95th percentile: 700ms
99th percentile: 1050ms
Slow UI thread: 547
Frame deadline missed: 592
```

Thread CPU:

```text
com.nexio.tv main thread: ~219% CPU
HeapTaskDaemon: ~64.5% CPU
arch_disk_io_* active
DefaultDispatcher active
```

Important log evidence in this run:

```text
home.snapshot_decision_lookup ... repeated per fullCatalogRows item
home.snapshot_sanitize_artwork ... repeated per fullCatalogRows item
media_clip.candidate_selected surface=HOME ... repeated for a small number of HOME items
Background concurrent copying GC freed 38MB / 57MB ...
```

Interpretation:

- HOME trailer resolver activity was still present, but not enough by itself to explain the memory pressure.
- Snapshot artwork decision tracing was clearly too chatty.
- GC and allocation pressure were already visible.

### After reducing HOME trailer bulk resolution and snapshot trace spam

PID:

```text
12022
```

Immediate startup sample was not conclusive. After about 40 seconds:

Memory:

```text
TOTAL PSS: ~672 MB
Dalvik Heap allocated: ~411 MB
Native Heap allocated: ~140 MB
```

Frame metrics:

```text
Total frames rendered: 233
Janky frames: 214 (91.85%)
50th percentile: 69ms
90th percentile: 350ms
95th percentile: 600ms
99th percentile: 1600ms
```

Thread CPU:

```text
com.nexio.tv main thread: ~209% CPU
HeapTaskDaemon: ~71.8% CPU
DefaultDispatcher threads active
arch_disk_io_* active
```

Dominant log pattern:

```text
I/Nexio.IntRuntime: t=artwork.decision_put
  decisionKey=artwork-decision:thumbnail:canonical:tt0239195:S1E5...
  provider=TVDB
  imageType=THUMBNAIL
  sourceRole=PRIMARY

I/Nexio.IntRuntime: t=artwork.decision_store_write
  success=true
  decisionCount=515
  linkCount=0

I/com.nexio.tv: Background concurrent copying GC freed 1810602(55MB) AllocSpace objects, 154(33MB) LOS objects, 5% free, 376MB/400MB, total 792.943ms

I/com.nexio.tv: Background concurrent copying GC freed 3048925(94MB) AllocSpace objects, 363(81MB) LOS objects, total 833.673ms

I/com.nexio.tv: Background concurrent copying GC freed 2449604(72MB) AllocSpace objects, 256(56MB) LOS objects, total 709.179ms

I/com.nexio.tv: Background concurrent copying GC freed 2835398(107MB) AllocSpace objects, 253(56MB) LOS objects, total 662.568ms
```

The decisions advanced through many season/episode thumbnail keys:

```text
tt0239195:S1E5
tt0239195:S1E6
...
tt0239195:S8E18
tt0239195:S9E1
...
```

Interpretation:

The app is repeatedly resolving or re-recording episode thumbnail decisions, and every single decision write is followed by a durable store write. Because `DurableArtworkDecisionCache.persistLocked()` serializes the complete `decisions.values.map(DecisionDto::fromDomain)` list and writes the entire file, a burst of hundreds of thumbnail decisions becomes hundreds of whole-store serializations and disk writes.

That explains all observed symptoms:

- large object allocations
- high `HeapTaskDaemon`
- high app CPU
- disk IO threads active
- UI jank even outside the trailer surface
- trailer playback looking choppy because the app process cannot reliably deliver frames

## Proven Root Cause Boundary

The confirmed root cause boundary is:

```text
DurableArtworkDecisionCache.put()
```

Current implementation:

```kotlin
override fun put(decision: ArtworkDecision) = synchronized(lock) {
    ensureLoadedLocked()
    decisions[decision.decisionKey] = decision
    traceArtwork(...)
    persistLocked()
}
```

And:

```kotlin
private fun persistLocked() {
    val dto = StoreDto(
        schemaVersion = SCHEMA_VERSION,
        decisions = decisions.values.map(DecisionDto::fromDomain),
        previewLinks = previewToCanonical.map { ... }
    )
    tempFile.writeText(gson.toJson(dto))
    Files.move(...)
    traceDecisionStoreWrite(success = true)
}
```

This is safe for low-volume poster decisions, but unsafe for high-volume episode thumbnail bursts. It turns each thumbnail decision into a full-store JSON rewrite.

## Likely Trigger

The exact trigger may be from today's work or from another concurrent agent's changes, but the runtime evidence shows the trigger is high-volume thumbnail decision production while the UI is active.

Likely trigger candidates:

1. Shared display/surface work widened the set of rows/items being observed or hydrated.
2. Post-startup catalog refresh is processing add-on/catalog rows while the UI route is active.
3. Episode thumbnail artwork routing is now producing durable decisions for many episodes in a single burst.
4. The app is in or near `addon_manager` while background catalog refresh and screensaver scheduler events are also active.

Observed related log lines:

```text
W/JankStats: JANK: 10128ms | states: [Screen: addon_manager]
D/CatalogRepository: Fetching catalog addonId=org.community.nexiotorii ...
D/HomeViewModel: Post-startup refresh addon catalogs refreshed=12
D/HomeViewModel: Post-startup refresh settled synthetic snapshot ...
I/Nexio.MetaRoute: t=screensaver.scheduler_state ... route=addon_manager ... waiting_for_timeout
```

The trigger matters for scoping, but the storage defect is independent:

```text
The durable artwork decision store must tolerate bursts of decisions without rewriting the whole store for every item.
```

## Contributing Factors

### Full-catalog snapshot size

The snapshot read/write path showed:

```text
catalogRowCount=274
fullCatalogRowCount=274
heroItemCount=7
orderedKeys=22
```

Large snapshots increase sanitization, serialization, and UI-state churn. The snapshot path should not perform expensive per-item diagnostics or durable cache lookups more than needed.

### 4K UI output

The device is rendering at:

```text
3840x2160
640 dpi
```

That makes any CPU/GC pressure more visible. This is a multiplier, not the root cause.

### Trace events inside hot loops

The earlier run showed per-item `home.snapshot_decision_lookup` and `home.snapshot_sanitize_artwork` events. Even after summary conversion, `artwork.decision_put` and `artwork.decision_store_write` still emit per decision. Trace volume is not the core issue, but it adds allocations on the hot path.

### HOME trailer availability bulk resolution

The earlier run showed repeated:

```text
media_clip.candidate_selected surface=HOME
```

Bulk HOME trailer availability resolution was removed from the display-surface publish path and later changed to rely on existing fallback IDs or published previews. That was correct for architecture, but it did not remove the main slowdown.

## Architectural Lessons

### Durable stores must batch writes

Any durable store touched by metadata/artwork hydration must avoid:

```text
put(item)
-> serialize entire store
-> write file
```

inside a burst loop.

For artwork decisions, acceptable behavior is one of:

```text
put many decisions in memory
-> debounce/coalesce
-> one durable write
```

or:

```text
append/update keyed records in a real database
```

or:

```text
write through only for small critical decisions and batch lower-priority thumbnail decisions
```

### Episode thumbnails are not the same priority as title posters

Poster/logo decisions are first-paint and identity-critical. Episode thumbnails are valuable but should not block or degrade the top-level Home and screensaver surfaces.

The current store treats these decision types the same. The logs show episode thumbnails can dominate the store.

### Screensaver source work must stay display-only until playback

The screensaver shared-surface design is still correct:

```text
TMDB trending rails
-> shared display surface
-> screensaver projection
-> lazy trailer playback resolution
```

But shared-surface publication must not accidentally trigger broad artwork/episode hydration or snapshot rewrites. Screensaver source rows should hydrate only the title-level display fields needed for image/trailer candidate selection.

## Recommended Fix Direction

Do not attempt more trailer-specific patches for the slowdown. Fix the write amplification and validate on device.

### P0: Batch durable artwork decision writes

Change `DurableArtworkDecisionCache` so `put()` does not immediately call `persistLocked()` for every decision.

Minimum acceptable behavior:

```text
put()
  update in-memory map
  mark dirty
  schedule debounced flush

flush()
  serialize whole store once
  atomic write once
```

Operational constraints:

- preserve immediate read-your-write from in-memory cache
- flush synchronously on explicit invalidation if needed
- expose `flushForTest()` or a controlled test hook if unit tests need deterministic writes
- keep atomic file replace for durability
- never perform full JSON serialization on the UI thread

Acceptance evidence:

```text
For a burst of 100 thumbnail decisions:
  artwork.decision_put may appear 100 times if tracing remains enabled
  artwork.decision_store_write should appear once or a very small bounded number of times
  no repeated 50-100MB GC cycles
```

### P1: Split or cap thumbnail decisions

Episode thumbnails should not evict or bloat the same first-paint title artwork decision surface.

Options:

```text
1. store title poster/logo/backdrop decisions in the durable decision store
2. store episode thumbnails in a separate lower-priority store
3. cap thumbnail decisions per title/season
4. persist thumbnail decisions only after asset materialization succeeds
```

The exact policy can be chosen later, but the immediate issue is that hundreds of thumbnail decisions currently share the same whole-file write path.

### P1: Gate background artwork hydration while playback or screensaver is active

The work gate already blocks some non-playback work during playback and idle trailer playback. It should also ensure high-volume episode thumbnail hydration cannot run while:

```text
idle trailer screensaver is visible
video playback is active
user is navigating a high-interaction screen
```

This is secondary. Batching writes is still required because the same burst can happen during normal UI.

### P2: Reduce hot-path artwork trace payloads

Keep traceability, but do not emit expensive per-item payloads in burst paths unless a debug toggle is explicitly enabled.

For normal gated logs:

```text
artwork.decision_put_summary
artwork.decision_store_write
artwork.decision_store_flush_scheduled
artwork.decision_store_flush_completed
```

should be enough.

## Tests Needed

### Durable write batching

```text
durable_artwork_decision_cache_batches_many_puts_into_one_write
durable_artwork_decision_cache_read_your_write_before_flush
durable_artwork_decision_cache_flush_persists_all_pending_decisions
durable_artwork_decision_cache_remove_flushes_deleted_keys
durable_artwork_decision_cache_invalidation_flushes_once
```

### Thumbnail burst safety

```text
episode_thumbnail_decision_burst_does_not_write_store_per_episode
episode_thumbnail_decision_burst_keeps_title_poster_decisions_available
episode_thumbnail_decision_burst_does_not_allocate_large_json_per_item
```

### On-device regression check

Use rooted ADB:

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexio.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexio.tv -c android.intent.category.LAUNCHER 1
adb -s 192.168.50.98:5555 shell dumpsys gfxinfo com.nexio.tv reset
```

Wait 60 seconds, then capture:

```bash
adb -s 192.168.50.98:5555 shell dumpsys gfxinfo com.nexio.tv
adb -s 192.168.50.98:5555 shell top -b -n 1 -H -p "$(adb -s 192.168.50.98:5555 shell pidof com.nexio.tv)"
adb -s 192.168.50.98:5555 shell dumpsys meminfo com.nexio.tv
adb -s 192.168.50.98:5555 logcat -d -v time
```

Expected proof after fix:

```text
No long run of artwork.decision_store_write after each thumbnail decision.
HeapTaskDaemon not continuously hot.
Dalvik allocated heap does not climb into ~400MB during startup/home idle.
JankStats does not show multi-second jank on Home/addon_manager.
Trailer screensaver playback no longer appears choppy due app-wide GC pressure.
```

## Open Questions

1. Which exact caller is producing the `tt0239195:SxEy` thumbnail decision burst?
2. Did today's screensaver shared-surface work increase that caller's input set, or was this introduced by another concurrent change?
3. Should episode thumbnail artwork decisions be durable at all, or should they rely on integration/runtime cache only?
4. Should `DurableArtworkDecisionCache` move from JSON file persistence to Room or another keyed store?
5. Should first-paint title artwork decisions and deep episode thumbnail decisions use separate stores and TTL policies?

## Bottom Line

The slowdown is best understood as an artwork decision persistence regression, not a trailer playback regression.

The app is doing high-volume episode thumbnail decision persistence while the UI is active. Each decision currently rewrites the whole durable artwork decision store, causing large allocations, repeated GC, and frame jank. Trailer playback looks choppy because it is sharing a process that is already saturated by GC and disk/write work.

The next engineering step should be to batch or redesign `DurableArtworkDecisionCache` persistence, then re-run the same rooted-device verification on `192.168.50.98`.
