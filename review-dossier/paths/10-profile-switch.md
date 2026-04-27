# Path 10 — Profile switch

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Lane:** F (profile boundaries) + H (playback) + G (CW)
- **Contract:** `setActiveProfile(id)` rejects switch during active playback via PlaybackSessionRegistry guard. On success, creates a fresh `ActiveProfileSession` with new sessionId. Downstream subscribers (CW, etc.) reset state.

## Chain

| # | Symbol | File:line | Expected | Observed |
|---|---|---|---|---|
| 1 | UI: profile selector tap (startup gating) | `app/src/main/java/com/nexio/tv/MainActivity.kt:520-531` | calls `profileManager.setActiveProfile(id)` from `profileSelectionScope.launch { … }` | calls `setActiveProfile` directly inside coroutine; no try/catch around the call |
| 2 | UI: in-app profile switch (settings/dropdown) | `app/src/main/java/com/nexio/tv/MainActivity.kt:343-352` (`switchProfileAndApplyLocale`, invoked at `:992` and `:1026`) | calls `profileManager.setActiveProfile(id)` from `lifecycleScope.launch` | calls `setActiveProfile` directly; no try/catch |
| 3 | UI: ProfileSelectionViewModel.selectProfile | `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModel.kt:47-51` | calls `profileManager.setActiveProfile(id)` from `viewModelScope.launch` | calls `setActiveProfile` directly; no try/catch |
| 4 | `ProfileManager.setActiveProfile(id)` validation | `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt:109-112` | loads profile list; returns if id unknown; no-ops if id == active | matches: `dataStore.profilesList.first()`, two early returns |
| 5 | playback guard | `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt:113-119` | if `activeOwner() != null` → throws `ProfileBoundaryException(PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK)` with owner profile/session in message | matches exactly |
| 6 | success path: locale + activeProfileId + new session + persist + emit | `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt:120-124` | atomic; new sessionId via UUID; persists via `dataStore.setActiveProfile(id)`; emits `_profileSwitched` | matches; `newProfileSession` (`:127-133`) generates `profile:$id:${UUID.randomUUID()}` |
| 7 | DataStore-driven switch (background collector) | `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt:83-101` | also respects playback guard — if `activeOwner() != null`, returns early instead of clobbering active session | matches: line 87-90 logs warning and returns (Task 4 of harden-profile-boundary-contract) |
| 8 | `ContinueWatchingSnapshotService` observes `_profileSwitched` | `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:189-197` | clears in-memory state; reloads persisted snapshot for new profile | matches: `markProfileAwaitingLiveReset(profileId)`, sets `persistedSnapshotReady = false`, calls `loadPersistedSnapshotForActiveProfile(clearWhenMissing = true)` |
| 9 | UI handles `ProfileBoundaryException` | (none) | shows "stop playback first" message | NOT IMPLEMENTED — see Findings F-10-1 |

## What does NOT happen on this path (verified)

- NO `setActiveProfile` succeeds while a playback session is registered (`PlaybackSessionRegistry.activeOwner()` non-null short-circuits both the imperative API and the DataStore collector).
- NO sessionId reuse — every switch generates a fresh UUID via `newProfileSession(id)`.
- NO async profile-bound write completes against the new profile if started under the old one (covered by `assertCanWriteProfileState` → `STALE_SESSION_WRITE_REJECTED`, see Path 11).

## Trace event coverage

| Event | Emitted? | Notes |
|---|---|---|
| profile.switch.success | n/a | no dedicated trace event in spec; downstream reactions fire from state-flow changes |
| profile.boundary_check (FAIL with PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK) | NO | the throw at `ProfileManager.kt:115` happens BEFORE the boundary enforcer is invoked, so no enforcer-driven trace event fires. The DataStore-collector path at `:87-90` only logs a `Log.w(TAG, …)` (no trace at all). See finding F-10-2. |

## Verdict

WARN

## Findings

### F-10-1 — UI callers do not handle `ProfileBoundaryException` (P1, UX bug)

`ProfileBoundaryException` extends `IllegalArgumentException` (`app/src/main/java/com/nexio/tv/core/integration/ProfileBoundaryViolation.kt:18-21`) — it is unchecked. All three call sites invoke `setActiveProfile` inside `launch { … }` blocks with no `try/catch`:

- `MainActivity.kt:346` (inside `lifecycleScope.launch` in `switchProfileAndApplyLocale`)
- `MainActivity.kt:523` (inside `profileSelectionScope.launch` in startup gating)
- `ProfileSelectionViewModel.kt:49` (inside `viewModelScope.launch` in `selectProfile`)

If a user taps a different profile while playback is active (e.g., from settings while a player overlay is dismissed but the player session is still registered), the throw propagates to the coroutine scope's uncaught-exception handler. On `lifecycleScope` this typically crashes the activity; on `viewModelScope` it cancels the ViewModel scope silently. Either way the user gets no "stop playback first" message — the documented contract behavior.

**Fix:** wrap each call site in `runCatching { profileManager.setActiveProfile(id) }` (or `try/catch ProfileBoundaryException`) and surface a snackbar/toast such as "Stop playback before switching profiles."

### F-10-2 — Boundary rejection is not observable via trace events (P2)

The playback-active rejection short-circuits inside `ProfileManager.setActiveProfile` (line 113-119) and inside the DataStore collector (line 87-90) without ever calling the boundary enforcer. As a result, no `profile.boundary_check` trace event with violation `PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK` is emitted. The DataStore path only writes `Log.w(TAG, …)` — invisible to trace dumps and the audit harness.

**Impact:** the audit harness cannot confirm rejection happened end-to-end; field telemetry has no signal for "users tried to switch during playback and were blocked." Combined with F-10-1, a swallowed rejection on the UI side becomes silent in every channel.

**Fix:** invoke the boundary enforcer (or emit a synthetic `profile.boundary_check` trace) at both rejection sites before throwing/returning, so the violation is recorded.

## Cross-references

- Earlier work: commits `81d18ca8e` (ProfileManager guard), `e33424e20` (PlayerViewModel registration)
- Path 07 (player start — registers playback owner via `PlaybackSessionRegistry`)
- Path 11 (scrobble — covers stale-write rejection / `STALE_SESSION_WRITE_REJECTED`)
- Path 09 (continue-watching render — consumes `_profileSwitched` via the same observer in step 8)
