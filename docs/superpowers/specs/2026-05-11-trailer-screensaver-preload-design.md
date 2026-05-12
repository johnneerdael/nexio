# Trailer Screensaver Preload Design

**Date:** 2026-05-11
**Status:** Design approved, pending implementation plan
**Owner:** John Neerdael (john@neerdael.nl)

## Motivation

`IdleTrailerScreensaverOverlay` rotates through up to 40 TMDB trending trailers per idle session. Source URL extraction is already prefetched (`preparedNextPlayback` at `IdleTrailerScreensaverOverlay.kt:131`), but the `ExoPlayer` instance is re-mounted with new URL props on every advance — so each transition pays the full Media3 source-prep + decoder-init cost (typically 3–8s on Fire TV / Android TV boxes for YouTube DASH streams).

The current first-frame timeout is `5_000L` (`IdleTrailerScreensaverOverlay.kt:56`). When cold-start exceeds 5s, the overlay marks the playback as failed, adds its key to `failedPlaybackKeys`, and rotates to the next candidate. Result: trailers that demonstrably play fine on the home hero / detail surfaces (which have no analogous timeout) get skipped in the screensaver after only showing a backdrop. Reported example: *Project Hail Mary* — known-good trailer, skipped in the screensaver.

## Goal

Eliminate visible cold-start between trailers by pre-warming the next trailer's `MediaSource` while the current one plays. After the swap, only decoder re-init remains — sub-second on warm decoders. The 5s first-frame timeout becomes irrelevant for preloaded slots; for the rare miss it's raised to 15s so cold-start streams aren't unfairly skipped.

Non-goal: pre-warming the *first* trailer of a session. Idle-session activation latency is more user-visible than the first-trailer cold-start, so the first slot keeps the existing cold-start path with the bumped timeout.

## Architecture

A new session-scoped facade — `IdleTrailerPreloadManager` — wraps Media3's stock `DefaultPreloadManager`. It manages a single preload slot at a time (next-only lookahead).

```
┌───────────────────────────────────────────┐
│        IdleTrailerScreensaverOverlay      │
│                                           │
│  current ─► TrailerPlayer (visible)       │
│                                           │
│  next   ─► IdleTrailerPreloadManager      │
│                  │                        │
│                  ▼                        │
│             DefaultPreloadManager         │
│             (Media3 stock)                │
│                                           │
│  on advance:                              │
│    1) preloadManager.consume(next)        │
│       → returns prepared MediaSource      │
│    2) TrailerPlayer remounts with         │
│       preparedMediaSource (skip the       │
│       URL-init path)                      │
│    3) preloadManager.enqueue(next+1)      │
└───────────────────────────────────────────┘
```

### New units

**`TrailerMediaSourceFactory`** — pure function: `TrailerPlaybackSource → MediaSource`. Constructs the `MergingMediaSource` from the video + audio URLs that `TrailerPlaybackSource` carries. Used by both `TrailerPlayer` (today's URL-init path) and the preload manager so source construction is centralised. Replaces the inline source construction currently inside `TrailerPlayer`.

Subtitles are NOT part of the `MediaSource`. The `TrailerSubtitleOverlay` pipeline (commit `0098cee61`) handles subtitle parsing + translation + position-polled cue rendering at the Compose layer, after the player is playing. Subtitles therefore do not affect preload behaviour.

**`IdleTrailerPreloadManager`** — session-scoped facade over `DefaultPreloadManager`:
- `enqueue(source: TrailerPlaybackSource)` — convert via `TrailerMediaSourceFactory`, register with `DefaultPreloadManager`.
- `consume(source: TrailerPlaybackSource): MediaSource?` — atomically retrieve the prepared `MediaSource` keyed by source identity, or `null` if not ready / not enqueued.
- `clear()` — cancel pending preloads and release `DefaultPreloadManager`.

### `TrailerPlayer` change

Add a new optional parameter `prepreparedMediaSource: MediaSource?`. When non-null, `TrailerPlayer` calls `player.setMediaSource(prepreparedMediaSource)` and skips its URL-based construction path. When null, the existing URL path runs (the today-behaviour fallback).

### Failure handling

A preload failure (network error during pre-buffer, source-extraction error) is *not* listened to directly in v1. The simpler approach: hand the still-failing source to `ExoPlayer.setMediaSource()` on advance. The player's existing `onError` callback (`IdleTrailerScreensaverOverlay.kt:304`) catches it, adds the playback key to `failedPlaybackKeys`, and `resolveNextIdleTrailerPlayback` skips it on the next advance — the same path that catches non-preload failures today. Cost: a broken stream takes ~5s of attempted-cold-start (covered by the bumped 15s timeout) before being blacklisted, versus near-instant skip if we wired the preload listener.

Out of scope for v1: wiring a `BasePreloadManager.Listener.onError(MediaItem, Throwable)` to surface preload-side failures earlier. Add later if smoke tests show preload-side errors are common enough to matter.

## Data Flow

### Session start

In `prepareIdleTrailerScreensaverSessionFromCandidates`:
1. Resolve `candidate[0]` → `initialPlayback`. *(unchanged)*
2. Create the session's `IdleTrailerPreloadManager`. The overlay holds it and releases it on dismiss.
3. Resolve `candidate[1]`'s `TrailerPlaybackSource` and call `preloadManager.enqueue(it)`. Fire and forget — the manager handles the async preload internally.

### Steady state

The existing `LaunchedEffect` at `IdleTrailerScreensaverOverlay.kt:131` already pre-resolves `preparedNextPlayback`. Extend it: after `resolvePlayback` succeeds, also call `preloadManager.enqueue(preparedNextPlayback.source)`.

`preparedNextPlayback` continues to carry the resolved `IdleTrailerScreensaverPlayback`; the preloaded `MediaSource` lives inside the manager keyed by source identity. The overlay does *not* need to plumb the `MediaSource` through `preparedNextPlayback` — it looks it up on advance.

### Advance

When `advanceSignal` bumps (`IdleTrailerScreensaverOverlay.kt:196`):
1. Pull `nextPlayback` (existing logic unchanged).
2. Look up `preloadManager.consume(nextPlayback.source)`:
   - **Hit** → pass the returned `MediaSource` to `TrailerPlayer` as `prepreparedMediaSource`. `ExoPlayer.setMediaSource(prepared)` skips reparse + reopen. Decoder re-init only. Sub-second swap.
   - **Miss** (preload not ready yet, or skipped past it due to a failure rotation) → pass `null`. `TrailerPlayer` cold-starts the source via its existing URL path. The bumped 15s timeout covers this case.
3. `preloadManager.enqueue(candidates[i+2])` to start the next preload.

### Skip / failure

- Preload-side failure → playback key added to `failedPlaybackKeys` by the manager's status callback. `resolveNextIdleTrailerPlayback` skips it.
- Mid-stream playback failure → existing flow (`onError`, stall timeout) bumps `advanceSignal`. If the next candidate isn't the one we preloaded, we discard the preload and start a new one on the new "next".

### Session end

On dismiss (`BackHandler` or `currentOnDismiss()`), call `preloadManager.clear()`. `DefaultPreloadManager.release()` shuts down internal buffers and worker threads.

## Timeout Policy

Change `TRAILER_SCREENSAVER_FIRST_FRAME_TIMEOUT_MS` from `5_000L` to `15_000L` (`IdleTrailerScreensaverOverlay.kt:56`). Single global constant — no per-slot variants.

Rationale:
- Preload hit → swap is sub-second → 15s never fires.
- Preload miss (initial trailer, skipped-past slot) → 15s gives YouTube room for cold extraction + manifest + decoder init.

`TRAILER_SCREENSAVER_STALL_TIMEOUT_MS = 8_000L` is unchanged. It only runs *after* `hasRenderedFirstFrame`, so it measures a genuinely stalled stream, not a slow start.

## Edge Cases

| Case | Behaviour |
|---|---|
| Preload still in flight at advance time | `consume()` returns `null` → `TrailerPlayer` cold-starts the source. Same as today. 15s timeout covers it. |
| Preload errored | Still passed to `ExoPlayer.setMediaSource()` on advance. Player's `onError` fires (existing handler), adds the key to `failedPlaybackKeys`, and `resolveNextIdleTrailerPlayback` skips on the next advance. Up-front preload-listener wiring deferred to a later iteration. |
| Very short trailer (current ends before preload completes) | Same as "preload still in flight" — cold-start fallback. |
| User opens details (`KEYCODE_DPAD_CENTER`) | `currentOnOpenDetails(candidate)` runs; overlay tears down. `preloadManager.clear()` releases everything in the existing dismiss path. |
| User backs out | Same — `preloadManager.clear()` in the dismiss path. |
| Source mid-rotation produces an `ItemLookup` ref | `TrailerMediaSourceFactory` requires a concrete URL — `ItemLookup` must be resolved to a concrete ref before preload. `resolveIdleTrailerScreensaverPlaybackSource` (`MainActivity.kt:204`) already does this. No new code. |

## Diagnostics

Add a single diagnostic event: `trailer_screensaver_preload` on the existing `TraceMetadataEvents` channel.

Fields:
- `itemKey: String` — `itemType:itemId` for the candidate.
- `status: String` — one of `queued | ready | failed | consumed_hit | consumed_miss`.
- `elapsedMs: Long` — milliseconds from `enqueue` to current status (for `queued` always 0).

## Testing

**Unit (`IdleTrailerScreensaverSessionTest`):**
- Drive the new advance-with-preload branch using a fake `IdleTrailerPreloadManager` whose `consume()` returns a stub `MediaSource`. Assert the `TrailerPlayer` mount call carries `prepreparedMediaSource != null`.
- Drive the miss path (preload not ready). Assert `prepreparedMediaSource == null` and the URL fallback wires through.
- Drive the preload-failure path. Assert the failed key lands in `failedPlaybackKeys` and the overlay advances past it.

**Instrumented:**
- Two-candidate session; mock the second candidate's preload to resolve quickly. Assert that on advance, `TrailerPlayer` receives `prepreparedMediaSource` (verifiable via a test-only callback or via the new `trailer_screensaver_preload` `consumed_hit` event).

**On-device smoke (rule #8 — profile-select first):**
- Force-stop, launch via monkey, select John profile, wait for home, idle until trailer screensaver activates.
- First trailer: backdrop briefly visible (acceptable, within new 15s timeout).
- Second trailer onwards: transition feels instant. Verify via `trailer_screensaver_preload` event log: `consumed_hit` for every advance after the first.

## Out of Scope

- Pre-warming the first trailer (would delay screensaver onset by 2–5s).
- Multi-slot preload queue (current design preloads next only).
- Cross-session preload (singleton manager warming trailers while modern home is in foreground) — wasted bandwidth/decoder allocation when the user never goes idle.
- Refactoring `TrailerPlayer` to externalise its `ExoPlayer` ownership (one-player swap covers the perf goal without lifting that boundary).
