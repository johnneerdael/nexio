# Path 07 — Player start

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Lane:** F (profile boundaries) + H (playback / scrobble) + I (trace mode)
- **Contract:** `PlayerViewModel` pins ownership at start via `PlaybackOwnerContext` + `PlaybackSessionRegistry`. Profile switching during playback is rejected. All scrobble routing uses `owner.ownerProfileId` rather than the live `activeProfileId`.

## Chain

| # | Symbol | File:line | Expected | Observed |
|---|---|---|---|---|
| 1 | UI: tap Play → nav to player | `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt:752` | `PlayerScreen(...)` composable mounted with route args | matches — `PlayerScreen` composed inside `NexioNavHost` route entry |
| 2 | `PlayerViewModel` constructor + `controller` init | `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt:67-77` | reads `profileManager.activeProfileSession.value`; builds `PlaybackOwnerContext(ownerProfileId, ownerSessionId, traktAccount=null, simklAccount=null, startedAtEpochMs=…)` | matches — constructor pulls `session.profileId` + `session.sessionId`; account refs are left null at start (provider lookup happens later) |
| 3 | `PlaybackSessionRegistry.register(ownerContext)` | `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt:78`; registry at `app/src/main/java/com/nexio/tv/core/playback/PlaybackSessionRegistry.kt:14` | returns `String` token; stored as `playbackRegistrationToken` | matches — token assigned to `playbackRegistrationToken` (declared line 65); registry uses `AtomicReference`, returns synthetic token id |
| 4 | `PlayerRuntimeController(playbackOwnerContext = ownerContext)` | `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt:77` | controller carries the context as `internal val playbackOwnerContext: PlaybackOwnerContext` | matches — passed positionally at `PlayerViewModel.kt:102`; field renamed from `playbackOwnerProfileId: Int` in commit `f990e6188` |
| 5 | `scrobble.start` when playback begins | `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt:325-329` | `trackingScrobbleService.scrobbleStart(item, percent, owner = playbackOwnerContext)` | matches — also at line 411 (heartbeat) and 347 (stop) all pass `owner = playbackOwnerContext` |
| 6 | `DefaultTrackingScrobbleService.scrobbleStart` routes to provider | `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt:53-65` | uses `owner.ownerProfileId` to dispatch to Trakt/Simkl per-profile services (NOT `profileManager.activeProfileId`) | matches — `simklScrobbleService.scrobbleStart(item, percent, owner.ownerProfileId)` and Trakt equivalent; profile-state lookup at line 165 also keyed by `owner.ownerProfileId` |
| 7 | `onCleared()` / `stopAndRelease()` teardown | `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt:125-128, 166-175` | both call `unregisterPlaybackSession()`; idempotent via nullable `playbackRegistrationToken` | matches — `unregisterPlaybackSession()` runs `playbackSessionRegistry.unregister(token)` then nulls token; safe to call twice |

## What does NOT happen on this path (verified)

- ❌ NO scrobble call uses `profileManager.activeProfileId.value` — `grep -n "activeProfileId.value" app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt app/src/main/java/com/nexio/tv/ui/screens/player/` returns zero hits.
- ❌ NO direct construction of `PlayerRuntimeController(playbackOwnerProfileId = …)` — that field was renamed to `playbackOwnerContext: PlaybackOwnerContext` in commit `f990e6188`; the only call site is `PlayerViewModel.kt:102` and it passes the context.
- ❌ NO profile switch succeeds while a session is registered. `ProfileManager.setActiveProfile(id)` (`app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt:109-119`) consults `playbackSessionRegistry.activeOwner()` and throws `ProfileBoundaryException(PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK)` when non-null. The `init` collector at line 87 also drops DataStore-driven profile changes while playback is active.

## Trace event coverage

| Event | Emitted on this path? | Notes |
|---|---|---|
| `runtime.operation_start` (preflight) | ✅ | `PlaybackPreflightIntegrationProvider` (lines 29, 65) routes through `runtime.call(…)`, which emits operation events when trace mode is on |
| `http.request` / `http.response` (preflight, streamlink) | ✅ (with caveat) | covered by the trace interceptor + tag bridge added in commits `2b696f168` / `ad69364f0`; provided the preflight transport is actually invoked through the tagged client (transports under `app/src/main/java/com/nexio/tv/data/integration/playback/transport/`) |
| `profile.boundary_check` (PASS) | ⚠️ NOT emitted from start path | `DefaultTrackingScrobbleService.scrobbleStart` does not emit a `profile.boundary_check` event itself; the boundary is enforced structurally by passing `owner.ownerProfileId` rather than reading `activeProfileId`. Account-scoped Trakt calls inherit boundary checks via the per-profile Trakt API client downstream — out of scope for the start handshake. Path 11 (Task 20) covers downstream emission. |

## Verdict

PASS

The contract is implemented cleanly: ownership is captured from `activeProfileSession.value` exactly once at controller construction, registered with the registry, threaded into the controller as a typed `PlaybackOwnerContext`, and consumed by all three scrobble emission sites via `owner = playbackOwnerContext`. Teardown unregisters from both `onCleared()` and explicit `stopAndRelease()` and is idempotent. The reciprocal `ProfileManager.setActiveProfile` guard rejects switches while the session is registered.

## Findings

None for this task. (Running findings count remains 17.)

## Cross-references

- Earlier work: commits `f990e6188`, `e33424e20` — `PlayerViewModel` + `PlaybackOwnerContext` + `PlaybackSessionRegistry` wiring
- Boundary map Q6 — scrobble owner derivation
- Path 11 (Task 20) — scrobble routing + per-provider profile boundary enforcement
- Path 08 (Task 17) — playback teardown / profile-switch-during-playback rejection
