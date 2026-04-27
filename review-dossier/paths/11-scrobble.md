# Path 11 — Scrobble (incl. late scrobble after profile switch)

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Lane:** H (playback / scrobble) + F (profile boundaries) + I (trace mode)
- **Contract:** `scrobbleStart/Stop/Pause` requires PlaybackOwnerContext. Routes via `owner.ownerProfileId`. Late writes after switch rejected via STALE_SESSION_WRITE_REJECTED. `checkin()` correctly stayed on the simpler nullable Int form.

## Chain

| # | Symbol | File:line | Expected | Observed |
|---|---|---|---|---|
| 1 | `PlayerRuntimeController.emitScrobbleStart` (player progress event) | `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt:325-329` | calls `trackingScrobbleService.scrobbleStart(item, progressPercent, owner = playbackOwnerContext)` | matches — passes `owner = playbackOwnerContext` |
| 1b | `emitScrobbleStop` (stop / pause / completion path) | `…PlayerRuntimeControllerPlaybackEvents.kt:347-351` | calls `trackingScrobbleService.scrobbleStop(..., owner = playbackOwnerContext)` | matches |
| 1c | `startScrobbleHeartbeat` (periodic re-start while playing) | `…PlayerRuntimeControllerPlaybackEvents.kt:411-415` | calls `scrobbleStart(..., owner = playbackOwnerContext)` on heartbeat tick | matches |
| 2 | `DefaultTrackingScrobbleService.scrobbleStart` | `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt:53-65` | `providerState(owner)` → `trackingProviderStateService.currentState(owner.ownerProfileId)`; routes to TRAKT or SIMKL underlying service | matches — `providerState(owner)` at L54 dispatches per `effectiveProvider`; profileManager.activeProfileId NOT consulted |
| 2b | `DefaultTrackingScrobbleService.scrobbleStop` / `scrobblePause` | `…TrackingScrobbleService.kt:67-93` | same routing pattern via `providerState(owner)` | matches |
| 3a | underlying SIMKL call | `…TrackingScrobbleService.kt:58, 72, 86` | `simklScrobbleService.scrobbleStart(item, progressPercent, owner.ownerProfileId)` (passes ownerProfileId, not active) | matches |
| 3b | underlying Trakt call | `…TrackingScrobbleService.kt:62, 76, 90` | `traktScrobbleService.scrobbleStart(it, progressPercent, owner.ownerProfileId)` | matches |
| 4 | `ProfileBoundaryEnforcer.assertCanWriteProfileState(resultSession, activeSession)` | `app/src/main/java/com/nexio/tv/core/integration/ProfileBoundaryEnforcer.kt:126-150` | compares result session vs active session; throws on mismatch | matches — throws `ProfileBoundaryException(STALE_SESSION_WRITE_REJECTED, …)` |
| 5 | success path: profile-keyed write | `…ContinueWatchingSnapshotService.kt:1050-1061` (`canPublishProfileWrite`) | when sessions match, write proceeds | matches — only profile-state writer that gates via the enforcer in production code |
| 6 | stale path (after profile switch): write discarded | `…ContinueWatchingSnapshotService.kt:1057-1060` | catches `ProfileBoundaryException`, logs, returns false → no publish | matches — log "Skipping stale continue watching publish" |

Note on coverage: scrobble-result sites in `TraktScrobbleService` / `SimklScrobbleService` themselves do NOT today route their post-network result through `assertCanWriteProfileState` (the enforcer is currently invoked only by `ContinueWatchingSnapshotService.canPublishProfileWrite` in production code). Test coverage in `TrackingScrobbleServicePlaybackOwnerTest.kt:62-69` exercises the enforcer for the scrobble shape, but production scrobble mutations rely on the upstream `owner.ownerProfileId` being captured at call time rather than re-checking on completion. See finding below.

## checkin() — confirmed NOT migrated

- ✅ `TrackingScrobbleService.checkin(item, message: String? = null, ownerProfileId: Int? = null): Boolean` — `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt:41`
- ✅ Default impl uses `providerState(ownerProfileId)` overload at `…TrackingScrobbleService.kt:95-108, 168-170` (falls back to `currentState()` when null)
- Callers (both use the default null):
  - `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:3192` — `trackingScrobbleService.checkin(TrackingScrobbleItem.Episode(...))`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:593` — `trackingScrobbleService.checkin(scrobbleItem)`

## What does NOT happen on this path (verified)

- ❌ NO scrobble call uses `profileManager.activeProfileId.value` for routing — `providerState(owner)` exclusively uses `owner.ownerProfileId`
- ❌ NO `playbackOwnerProfileId: Int` parameter on the scrobble interface (renamed to `owner: PlaybackOwnerContext` in commit `7b4f364bc`)
- ❌ NO stale scrobble result silently writes to the new profile via the CW snapshot service (gated by `canPublishProfileWrite`); stale results from underlying provider services have no equivalent gate (see finding)

## Trace event coverage

| Event | Emitted? | Notes |
|---|---|---|
| `profile.boundary_check` (PASS or FAIL with STALE) | ⚠️ partial | `ProfileBoundaryEnforcer.recordBoundaryCheck` emits on profile-scoped `assertProfileScope` invocations; `assertCanWriteProfileState` (L126-138) does NOT itself emit — relies on caller `ContinueWatchingSnapshotService.canPublishProfileWrite` to log; trace validation rule `NoStaleProfileWritesAfterSwitch` (`TraceValidationRules.kt:208`) consumes `verdict=FAIL, violation=STALE_SESSION_WRITE_REJECTED` when produced |
| `http.request` / `http.response` (Trakt scrobble.start) | ✅ | through TraceLoggingInterceptor on the OkHttp client — Trakt scrobble endpoints are unprefixed REST calls captured by the global interceptor |

## Verdict

⚠️ — contract holds for the call-site signature and routing (steps 1–3 fully verified), and `checkin()` correctly stayed on the nullable Int form. The stale-write rejection via `assertCanWriteProfileState` is wired only through `ContinueWatchingSnapshotService.canPublishProfileWrite`; scrobble HTTP completions on `TraktScrobbleService` / `SimklScrobbleService` do not themselves re-validate against the enforcer on result delivery. This matches the boundary-map H-Q6.1 risk: nullable defaults on underlying provider scrobble interfaces are a legacy default that's risky.

## Findings

- **H-Q6.1 (confirmed in this path):** `TraktScrobbleService.scrobbleStart/Stop/Pause/checkin` and `SimklScrobbleService.scrobbleStart/Stop/Pause/checkin` declare `ownerProfileId: Int? = null` defaults (`TraktScrobbleService.kt:102,117,132,147`; `SimklScrobbleService.kt:77,92,107,122`). Today the only production caller (`DefaultTrackingScrobbleService`) always passes `owner.ownerProfileId`, but a future caller could omit the argument and silently fall back to nullable behavior. No `assertCanWriteProfileState` re-check occurs on the post-HTTP result path of the underlying provider services; the late-after-switch guarantee depends entirely on the upstream owner being captured before the request, not on the result being re-validated against the active session at completion.

## Cross-references

- Boundary map H-Q6.1
- Path 07 (player start) — establishes `playbackOwnerContext` snapshot used here
- Path 10 (profile switch) — drives the active-session change that would otherwise stale these writes
- Earlier work: commits `7b4f364bc` (interface change to PlaybackOwnerContext), `f990e6188` (player wiring + checkin revert), `81d18ca8e` (assertCanWriteProfileState ProfileManager wiring)
