# Auto-skip placeholder "error videos" returned by Stremio addons

**Date:** 2026-04-26
**Scope:** NEXIO Android TV / Fire TV (`com.nexio.tv`)
**Status:** Approved design — ready for implementation planning

## Background

Many Stremio addons, when they cannot resolve a real link (auth failure, source offline, quota exhausted, "not cached yet"), return `200 OK` with a short pre-rendered MP4 captioned with an error message instead of an HTTP error. The player today plays the 8-second placeholder clip rather than advancing to the next eligible stream.

NEXIO already resolves the addon's `302 → CDN` redirect via `CometProxyUrlResolver` (`app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt`). The resolver issues a redirect-disabled `GET` with `Range: bytes=0-0` and observes the addon's response. Today, when an addon returns `200` instead of `3xx`, the resolver collapses that to `null` ("null-no-redirect", line 448) and the caller falls back to playing the original URL — which is exactly the placeholder body.

The placeholder pattern is detectable from data the resolver already has. This spec extends the resolver's existing mechanic to surface that signal so the autoplay path can advance to the next candidate.

## Goal

Autoplay the next eligible link instead of landing on a captioned error MP4, without adding new HTTP probes, ffprobe invocations, or range requests.

## Non-goals

- Manual stream selection: when a user taps a stream from the picker, they get whatever they tapped (placeholder or not).
- Body inspection (OCR, frame analysis, etc.).
- Audio-track-presence corroboration: the existing ffprobe selector (`v:0,s`) excludes audio by design; adding it would expand probe output. Signal 1 alone is sufficient.
- Changes to `AuthRecoveryInterceptor` semantics. A `Placeholder` verdict is independent from an auth failure.

## Detection signal

**Signal 1 — response served from the addon host.**

Inside the resolver's existing redirect-disabled `GET`, classify the response:

| Response | Classification |
|---|---|
| `3xx` with `Location` header | `Redirected(url)` |
| `200 OK` with `Content-Type: video/*`, gated host | `Placeholder` |
| `200 OK` with non-video / missing Content-Type | `ResolveFailed` |
| `4xx` / `5xx` / network error | `ResolveFailed` |
| URL fails `isCometProxy()` gates | `NotEligible` (computed locally, no network) |

The host gate is the existing `isCometProxy(url, addonHost)`: either the URL host is on the static `knownProxyHosts` list with a known path marker, OR the caller-supplied `addonHost` matches the URL's host. Today every autoplay call site already passes `addonHost = hostOfAddonBaseUrl(stream.addonBaseUrl)` — the gate is universally active for arbitrary user-installed addons.

The `Content-Type: video/*` constraint avoids misclassifying HTML or JSON addon error bodies.

## Architecture

`CometProxyUrlResolver` remains the single source of truth for "what does this addon URL actually point at." We split today's binary `String?` answer into a four-way sealed class so the autoplay layer can distinguish *don't know* (`NotEligible` / `ResolveFailed`) from *definitely a placeholder* (`Placeholder`).

```kotlin
sealed class ProxyResolution {
    data class Redirected(val url: String) : ProxyResolution()
    object Placeholder : ProxyResolution()
    object NotEligible : ProxyResolution()
    object ResolveFailed : ProxyResolution()
}
```

The resolver issues exactly the request it issues today: `GET` with `Range: bytes=0-0`, redirects disabled. No new probe.

## Components & call sites

### Modified

**`app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt`**
- Add `ProxyResolution` sealed class (top-level in same file).
- `resolve()` and `resolveBlocking()` return `ProxyResolution` (was `String?`).
- `prewarm()` keeps returning `Job?`. Add internal `lastResolutionFor(url): ProxyResolution?` backed by a short-lived (≈30 s) verdict map so the autoplay selector can read prewarm outcomes without firing a second request.
- `Transport` interface widens its return type to `ProxyResolution`.
- Long-cache (`CacheEntry`, 50 min) continues to store **only** `Redirected` outcomes. `Placeholder` and `ResolveFailed` are not long-cached — same URL may resolve later (e.g., after auth recovers).
- Existing test hook `setTransportForTesting` updated for the new return shape.
- `RESOLVE_RESPONSE` log line gains `decision=placeholder` as a fourth case.

**`app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`**
- `prepareMediaSourceUrl` (line 1001-1004): map `Redirected → url`; all other variants → original `url` (preserves today's fallback for direct-playback paths).
- Autoplay candidate prewarm path (lines 1059-1066): unchanged invocation; downstream selector reads classifications via `lastResolutionFor`.

**`app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`**
- Two `prepareMediaSourceUrl` call sites (lines 454, 794) shift to the new return shape; behavior unchanged for non-placeholder outcomes.
- Picker fallback branch (line 969) gains a one-shot toast "Autoplay could not select a stream." — fired only when the placeholder filter actually dropped at least one candidate.

**`app/src/main/java/com/nexio/tv/core/player/StreamAutoPlaySelector.kt`**
- `candidateAutoPlayStreams` accepts an injected placeholder predicate that consults `CometProxyUrlResolver.lastResolutionFor(url)`. Filter runs **before** any ffprobe so we don't waste probe cost on known placeholders.
- `findViableFallback` is unchanged — placeholder filtering happens upstream so it sees only non-placeholder candidates.

**`tv/data/local/PlayerSettingsDataStore.kt`**
- Add `skipPlaceholderStreamsEnabledKey = booleanPreferencesKey("skip_placeholder_streams_enabled")` near line 531 with matching getter/setter, default `true`.

**`app/src/main/java/com/nexio/tv/core/player/auth/AuthRecoveryInterceptor.kt`**
- Adjust call sites that read `resolve(...)` for the `ProxyResolution` return shape. Auth-recovery semantics unchanged. A `Placeholder` verdict is **not** treated as an auth failure.

### New

- Settings UI row "Skip addon placeholder streams" co-located with the existing `filterWebDolbyVisionStreamsEnabledKey` toggle. Subtitle: *Automatically skip streams that return an error video instead of real content.* Exact composable located during planning.

## Data flow (autoplay path)

```
StreamScreenViewModel.candidateAutoPlayStreams
  ↓
CometProxyUrlResolver.prewarm(url, headers, addonHost)            // already happens today
  ↓
fetchLocation → defaultTransport.execute → classify response
  ↓
StreamAutoPlaySelector.candidateAutoPlayStreams
  ↓ apply placeholderFilter (reads resolver.lastResolutionFor):
       Placeholder & toggle ON → drop
       else → keep
  ↓
DolbyVisionAutoPlayGate.resolve  (existing ffprobe + DV gate)
  ↓
selectAutoPlayStream → playable URL
  ↓
PlayerRuntimeControllerStreams plays it
  (prepareMediaSourceUrl reads the same cached Redirected URL — no second resolve)
```

If every candidate is filtered out: `selectAutoPlayStream` returns null → existing picker fallback opens → toast "Autoplay could not select a stream."

**Direct-playback (manual selection):** unchanged. The Placeholder verdict is intentionally ignored here — user gets what they tapped.

**Toggle off:** the placeholder predicate becomes constant `false`; autoplay path is bit-for-bit identical to today, including prewarm.

## Cache lifetimes

| Verdict | Long cache (50 min) | Short verdict cache (≈30 s) |
|---|---|---|
| `Redirected(url)` | yes (existing) | n/a |
| `Placeholder` | no | yes — only to bridge prewarm → selector read |
| `ResolveFailed` | no | no |
| `NotEligible` | n/a (computed) | n/a |

The short verdict cache exists solely so the selector can read the prewarm verdict without firing a second request. After it expires, the next autoplay attempt re-probes — matching the spec invariant that the same URL may resolve to a real link minutes later.

## Settings

```kotlin
private val skipPlaceholderStreamsEnabledKey =
    booleanPreferencesKey("skip_placeholder_streams_enabled")

val skipPlaceholderStreamsEnabledFlow: Flow<Boolean> =
    context.dataStore.data.map { it[skipPlaceholderStreamsEnabledKey] ?: true }

suspend fun setSkipPlaceholderStreamsEnabled(enabled: Boolean) {
    context.dataStore.edit { it[skipPlaceholderStreamsEnabledKey] = enabled }
}
```

Default `true`. The autoplay path observes the flow at candidate-selection time, so a flipped toggle takes effect on the next autoplay decision without restart. Currently-playing stream is unaffected by a mid-session flip.

## Edge cases

- **Network error during probe** → `ResolveFailed`. Caller uses original URL (today's behavior). Not treated as placeholder; transient blips shouldn't permanently filter a stream.
- **`4xx` / `5xx`** → `ResolveFailed`. Bubbles up to ExoPlayer's existing error path; `AuthRecoveryInterceptor` continues to handle auth-failure codes.
- **`200 OK` with non-video Content-Type** (`text/html`, `application/json`) → `ResolveFailed`. Conservative — addon HTML or JSON error pages are not placeholder MP4s.
- **`200 OK` with no Content-Type** → `ResolveFailed`. If the addon doesn't claim it's video, we don't claim it's a placeholder.
- **Proxy-mode addons** (real stream served from the addon's own host) → classify as `Placeholder` under Signal 1. Accepted as a known limitation of the cheap-probe constraint. The settings toggle is the user's escape hatch.
- **Redirect chain back to addon host** (`3xx → Location` on same host) → still `Redirected`. The addon's choice of `3xx` over body-serving is signal enough that this isn't the placeholder pattern.
- **`isCometProxy` false** → `NotEligible`. Caller uses original URL; no placeholder check performed. Filter is opt-in by addon eligibility.
- **`addonBaseUrl` missing on stream** → `addonHost` null → only Gate B (static list + path marker) governs. Streams from arbitrary addons without `addonBaseUrl` always classify `NotEligible` and are never filtered. Acceptable; matches today's resolver scope.
- **Toggle flipped mid-session** → next autoplay decision picks up the new value. Currently-playing stream unaffected.
- **Cache invalidation on auth recovery** → `AuthRecoveryInterceptor` already invalidates cached redirects via `recoverProxyBlocking`. Placeholder verdicts have a 30 s TTL and aren't long-cached, so no extra invalidation hook is needed.

## Telemetry

- `RESOLVE_RESPONSE` log line gains `decision=placeholder` as a fourth value (alongside `redirect` / `null-no-redirect`).
- Selector adds one INFO log per filtered candidate: `AUTOPLAY_SKIP reason=placeholder url=<sanitized>`.
- No new metrics dashboards.

## Testing strategy

### Unit

**`CometProxyUrlResolverTest.kt`** (extend):
- `resolve_returnsRedirected_on302WithLocation` — happy path, updated assertion type.
- `resolve_returnsPlaceholder_on200FromAddonHost_videoContentType`.
- `resolve_returnsResolveFailed_on200_withHtmlContentType`.
- `resolve_returnsResolveFailed_on200_withNoContentType`.
- `resolve_returnsResolveFailed_on4xx` and `_on5xx`.
- `resolve_returnsNotEligible_whenIsCometProxyFalse`.
- `resolve_doesNotCachePlaceholder_inLongCache` — after short-verdict TTL elapses, second call re-probes.
- `resolve_cachesPlaceholderForShortWindow` — within 30 s, repeated lookups don't fire a second transport call.
- `resolve_redirectChainBackToAddonHost_isStillRedirected`.

**`StreamAutoPlaySelectorPlaceholderTest.kt`** (new):
- `candidateAutoPlayStreams_dropsPlaceholders_whenToggleEnabled`.
- `candidateAutoPlayStreams_keepsPlaceholders_whenToggleDisabled`.
- `candidateAutoPlayStreams_keepsAllWhenNoPlaceholders`.
- `selectAutoPlayStream_returnsNull_whenAllCandidatesArePlaceholders`.

**`PlayerRuntimeControllerStreamsAuthRecoveryTest.kt`** (extend):
- Auth recovery still triggers on its codes when placeholder filter is active.

### Integration

**`PlaybackAuthRecoveryEndToEndTest.kt`** (extend lightly):
- Candidate list `[placeholder, real]`, toggle ON → autoplay picks `real` and no probe is wasted on the placeholder.

### Manual / device verification

- Test addon returning `200 + video/mp4` placeholder for one stream and real `302 → CDN` for another. Verify autoplay lands on the real one.
- All-placeholder candidate list → picker opens with one-shot toast.
- Toggle off → previously-skipped placeholder plays (confirms the toggle is the only thing gating the behavior).
- Latency check: timed autoplay-to-first-frame on a real `302 → CDN` stream unchanged vs `main`. Probe cost (request count, bytes, CPU) unchanged.

### Out of scope for tests

- ffprobe behavior — the probe is untouched.
- `AuthRecoveryInterceptor` body parsing — placeholder detection is transport-level only.
- Manual stream selection — explicitly excluded by Q1.

## Acceptance criteria

- Against an addon configured to return placeholder MP4s for known failure modes (bad auth, link offline, quota exceeded), autoplay advances to the next stream instead of playing the placeholder.
- A real `302 → CDN` stream plays normally with no measurable added latency vs `main`.
- Probe cost (request count, bytes transferred, CPU) is unchanged: no second HTTP probe, no expanded ffprobe selector, no extra range request.
- Setting toggle disables the behavior cleanly (autoplay path bit-for-bit identical to today when off).
- When every autoplay candidate is filtered, the picker opens with toast "Autoplay could not select a stream."
- Manual stream selection plays whatever the user picked, regardless of placeholder verdict.
