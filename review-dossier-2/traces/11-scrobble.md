# Trace 11 — Scrobble

**Review SHA:** `774a540f8`
**Generated:** 2026-04-29
**Dossier series:** review-dossier-2

---

## 1. Path under trace

Player progress events (start / pause / stop) → `TrackingScrobbleService` → backend dispatch to either `TraktScrobbleService` or `SimklScrobbleService` → `ProviderMutationOutboxCoordinator` → `TraktIntegrationProvider.scrobble` / `SimklIntegrationProvider.scrobble{Start,Pause,Stop}`.

---

## 2. Entry point verification

### 2.1 `TrackingScrobbleService` interface signature

`TrackingScrobbleService.kt:37–43` defines the public interface:

```kotlin
interface TrackingScrobbleService {
    suspend fun scrobbleStart(item: TrackingScrobbleItem, progressPercent: Float, owner: PlaybackOwnerContext)
    suspend fun scrobbleStop(item: TrackingScrobbleItem, progressPercent: Float, owner: PlaybackOwnerContext)
    suspend fun scrobblePause(item: TrackingScrobbleItem, progressPercent: Float, owner: PlaybackOwnerContext)
    suspend fun checkin(item: TrackingScrobbleItem, message: String? = null, ownerProfileId: Int? = null): Boolean
    fun observeWatchingNowState(): Flow<TrackingWatchingNowState>
}
```

**Confirmed:** All three scrobble entry points (`scrobbleStart`, `scrobbleStop`, `scrobblePause`) accept `owner: PlaybackOwnerContext` — not a bare `Int`. `checkin` intentionally retains `ownerProfileId: Int?` (the F-H-01 architecture pin, discussed in §3 below).

### 2.2 `PlaybackOwnerContext` shape

`PlaybackOwnerContext.kt:5–16`:

```kotlin
data class PlaybackOwnerContext(
    val ownerProfileId: Int,
    val ownerSessionId: String,
    val traktAccount: ProviderAccountRef?,
    val simklAccount: ProviderAccountRef?,
    val startedAtEpochMs: Long
)
```

All three `init` precondition guards are present: `ownerProfileId > 0`, `ownerSessionId.isNotBlank()`, `startedAtEpochMs > 0L`.

### 2.3 Construction and registration in `PlayerViewModel`

`PlayerViewModel.kt:67–78`:

```kotlin
val ownerContext = run {
    val session = profileManager.activeProfileSession.value
    PlaybackOwnerContext(
        ownerProfileId = session.profileId,
        ownerSessionId = session.sessionId,
        traktAccount = null,
        simklAccount = null,
        startedAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L)
    )
}
playbackRegistrationToken = playbackSessionRegistry.register(ownerContext)
```

`PlaybackOwnerContext` is constructed from the active profile session **at the moment the `PlayerViewModel` is created** — before `PlayerRuntimeController` is instantiated (line 79) and therefore before any scrobble can fire. The profile-snapshot is immutable for the lifetime of the player session.

### 2.4 Caller in `PlayerRuntimeControllerPlaybackEvents`

`PlayerRuntimeControllerPlaybackEvents.kt:315–332` (`emitScrobbleStart`):

```kotlin
scope.launch {
    val progressPercent = currentPlaybackProgressPercent()
    trackingScrobbleService.scrobbleStart(
        item = item,
        progressPercent = progressPercent,
        owner = playbackOwnerContext        // ← PlaybackOwnerContext, not Int
    )
    ...
}
```

`emitScrobbleStop` at line 347 and the heartbeat loop at line 411 both pass `owner = playbackOwnerContext` identically.

**Contrast with `checkin`:** `checkin` is called from `HomeViewModelContinueWatching.kt:590` and `MetaDetailsViewModel.kt:3076` without supplying `ownerProfileId` (falls back to `null`). These are not player-initiated paths and carry no `PlaybackOwnerContext`. This asymmetry is the documented F-H-01 architectural pin; see §5 (Finding S-01).

---

## 3. F-H-01 pin — `checkin` retains `ownerProfileId: Int?`

**Confirmed present and unbroken.**

`TrackingScrobbleService.kt:41`:
```kotlin
suspend fun checkin(item: TrackingScrobbleItem, message: String? = null, ownerProfileId: Int? = null): Boolean
```

The third parameter is `ownerProfileId: Int?`, not `PlaybackOwnerContext`. The architecture pin test `TrackingScrobbleServiceCheckinShapeTest` verifies via reflection that the third parameter is `java.lang.Integer` (nullable `Int` on JVM). This is an intentional design choice: `checkin` is issued from non-playback contexts (Continue Watching, Detail screen) where no `PlaybackOwnerContext` exists. Forcing callers to fabricate one would introduce coupling; the `Int?` type forces callers to either supply a known profile ID or fall back to the ambient active profile.

---

## 4. F-H-03 closure status — result-time `assertCanWriteProfileState` re-check

### 4.1 What was claimed

The earlier audit (Cluster B) claimed F-H-03 (P0) as closed: "result-time `assertCanWriteProfileState` re-check on scrobble completion — `STALE_SESSION_WRITE_REJECTED` reachable from scrobble path."

### 4.2 What the code actually does

`TraktScrobbleService.kt:294–303`:

```kotlin
private fun checkScrobbleBoundary(envelopeProfileId: Int, operation: String) {
    val active = profileManager.activeProfileId.value
    if (envelopeProfileId != active) {
        traceMetadataEvents.emitScrobbleRejected(
            envelopeProfileId = envelopeProfileId,
            activeProfileId = active,
            operation = "trakt.$operation",
            reason = "STALE_SESSION_WRITE_REJECTED"
        )
    }
}
```

`SimklScrobbleService.kt:248–258` is structurally identical (substituting `"simkl.$operation"`).

**Key observations:**

1. `checkScrobbleBoundary` is `private fun` with return type `Unit`. It emits a trace event and returns — it does **not** throw and does **not** return a value that callers can inspect.
2. In `TraktScrobbleService.enqueueScrobble` (line 266), the call is:
   ```kotlin
   checkScrobbleBoundary(request.profileId, "scrobble.$action")
   if (shouldSkip(...)) return MutationResult.Success
   return runCatching {
       traktMutationOutboxCoordinator.enqueueAndDrain(...)
       ...
   }
   ```
   Execution continues to `enqueueAndDrain` regardless of the boundary check outcome.
3. In `TraktScrobbleService.enqueueCheckin` (line 242) the identical pattern applies.
4. The Simkl variants at lines 200 and 222 behave identically.
5. `ProfileBoundaryEnforcer.assertCanWriteProfileState` (the function that actually throws `ProfileBoundaryException`) is **not called anywhere** in the Trakt or Simkl scrobble paths. Its only production caller is `ContinueWatchingSnapshotService.canPublishProfileWrite` (line 1066), which gates Continue Watching writes.

**Conclusion:** The F-H-03 P0 closure claim is **incorrect**. `checkScrobbleBoundary` is telemetry-only. The write is never halted on profile mismatch. Lane H Finding H-01 (documented in `H-playback-scrobble-skip.md`) correctly characterizes this as INCOMPLETE.

### 4.3 No result-time re-check anywhere on the path

After `enqueueAndDrain` returns (i.e., after the network call completes), there is no subsequent call to `assertCanWriteProfileState` or any equivalent profile-identity comparison. The scrobble result is committed to the Trakt/Simkl backend without any post-network profile guard.

**Lane H H-01 verdict: CONFIRMED — the write is not halted.**

---

## 5. Trakt scrobble path

### 5.1 `DefaultTrackingScrobbleService` → `TraktScrobbleService`

`TrackingScrobbleService.kt:60–63`:

```kotlin
TrackingProvider.TRAKT -> {
    if (!providerState.traktAuthenticated) return
    toTraktItem(item)?.let { traktScrobbleService.scrobbleStart(it, progressPercent, owner.ownerProfileId) }
}
```

`owner.ownerProfileId` (the profile ID frozen at playback-start time) is extracted from `PlaybackOwnerContext` and forwarded as `ownerProfileId: Int?` to `TraktScrobbleService`. The provider-state lookup on line 54 also uses `owner.ownerProfileId` via `providerState(owner)` → `trackingProviderStateService.currentState(owner.ownerProfileId)`.

### 5.2 `TraktScrobbleService.scrobbleStart` → `submitMutation`

`TraktScrobbleService.kt:106–118`:

```kotlin
suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float, ownerProfileId: Int? = null) {
    val session = authSession(ownerProfileId)
    if (!canMutateWatchingState(session)) return
    val optimisticVersion = watchingNowStateController.nextOptimisticVersion()
    submitMutation(
        request = WatchingMutationRequest.Scrobble(
            action = "start",
            item = item,
            progressPercent = progressPercent,
            optimisticVersion = optimisticVersion,
            profileId = session.profileId
        )
    )
}
```

`authSession(ownerProfileId)` at line 173 constructs `TrackingAuthSession(TrackingProvider.TRAKT, ownerProfileId)` when `ownerProfileId` is non-null — using the **playback-owner** profile ID, not the ambient active profile. The `profileId` embedded in `WatchingMutationRequest.Scrobble` is therefore the owner's profile ID.

### 5.3 Mutation drain → `TraktScrobbleMutationAdapter` → `TraktIntegrationProvider.scrobble`

`TraktScrobbleService.enqueueScrobble` (line 268):
```kotlin
traktMutationOutboxCoordinator.enqueueAndDrain(
    TraktScrobbleMutationAdapter.buildScrobbleEnvelope(
        item = item,
        action = action,
        progressPercent = clampedProgress,
        rollbackState = rollbackState,
        optimisticVersion = request.optimisticVersion,
        profileId = request.profileId      // ← owner profile ID
    )
)
```

`TraktScrobbleMutationAdapter.executeScrobble` (lines 104–135) calls:
```kotlin
val response = traktIntegrationProvider.scrobble(
    session = session,   // session built from envelope.profileId
    action = action,
    body = requestBody
)
```

`TraktIntegrationProvider.scrobble` (lines 701–717):
```kotlin
suspend fun scrobble(
    session: TrackingAuthSession,
    action: String,
    body: TraktScrobbleRequestDto
): Response<TraktScrobbleResponseDto>? =
    executeAuthorizedResponseCall(
        session = session,
        apiShapeId = TraktApiShapes.SCROBBLE,        // F-C-02 confirmed
        operationKey = "trakt.scrobble.$action",
        workClass = IntegrationWorkClass.SCROBBLE
    ) { authorization ->
        when (action) {
            "start" -> traktApi.scrobbleStart(authorization, body)
            "pause" -> traktApi.scrobblePause(authorization, body)
            else -> traktApi.scrobbleStop(authorization, body)
        }
    }
```

**F-C-02 confirmed:** `apiShapeId = TraktApiShapes.SCROBBLE` (`"trakt.scrobble"` — `IntegrationApiShapes.kt:201`). No literal string used.

---

## 6. Simkl scrobble path

### 6.1 `DefaultTrackingScrobbleService` → `SimklScrobbleService`

`TrackingScrobbleService.kt:56–59`:

```kotlin
TrackingProvider.SIMKL -> {
    if (!providerState.simklAuthenticated) return
    simklScrobbleService.scrobbleStart(item, progressPercent, owner.ownerProfileId)
}
```

Same owner-profile-ID forwarding pattern as Trakt.

### 6.2 `SimklScrobbleService` → `ProviderMutationOutboxCoordinator` → `SimklIntegrationProvider`

`SimklScrobbleService.enqueueScrobble` (line 225):
```kotlin
traktMutationOutboxCoordinator.enqueueAndDrain(
    SimklScrobbleMutationAdapter.buildScrobbleEnvelope(
        item = item,
        action = action,
        progressPercent = clampedProgress,
        rollbackState = rollbackState,
        optimisticVersion = request.optimisticVersion,
        profileId = request.profileId
    )
)
```

`SimklIntegrationProvider` exposes three dedicated functions for the three scrobble actions (`scrobbleStart`, `scrobblePause`, `scrobbleStop`), each using:

```kotlin
apiShapeId = SimklApiShapes.SCROBBLE     // "simkl.scrobble" — IntegrationApiShapes.kt:103
```

**F-C-02 confirmed for Simkl:** All four Simkl scrobble-related calls (lines 189, 202, 215, 228 of `SimklIntegrationProvider.kt`) use `SimklApiShapes.SCROBBLE`. No literal shape strings used.

**Note:** `SimklScrobbleService` also uses `traktMutationOutboxCoordinator` (not a Simkl-named outbox coordinator) — this is a structural anomaly (the Simkl scrobble service shares the Trakt outbox) but does not affect correctness of the path being traced. The `SimklScrobbleMutationAdapter.ADAPTER_KEY` is `"simkl.scrobble"`, distinguishing it from the Trakt adapter key `"scrobble"`.

---

## 7. Profile context — capture and validation

### 7.1 Profile ID captured at playback-start time

`PlaybackOwnerContext` is constructed once, at `PlayerViewModel` init time (before any scrobble fires), from `profileManager.activeProfileSession.value`. The `ownerProfileId` is the profile ID active **when the player was opened**. This ID is baked into:

- `WatchingMutationRequest.Scrobble.profileId` / `WatchingMutationRequest.CheckIn.profileId`
- `TraktMutationEnvelope.profileId` / `SimklMutationEnvelope.profileId`
- `TrackingAuthSession` used for credential lookup

The field `PlaybackOwnerContext.ownerSessionId` (the `sessionId` of the active profile session) is also captured but is **not used** anywhere in `TraktScrobbleService` or `SimklScrobbleService`. It is stored on `PlaybackOwnerContext` but the scrobble services operate exclusively from `ownerProfileId`. (See Finding S-04.)

### 7.2 Validation at result-delivery time

**No result-time profile validation exists on the scrobble path.** `checkScrobbleBoundary` runs at mutation-drain time (before the network call), not after. After `enqueueAndDrain` returns:

- No call to `ProfileBoundaryEnforcer.assertCanWriteProfileState`
- No comparison of `request.profileId` against current `activeProfileSession.value.sessionId`
- No discard of the result if the active profile has changed

This is the incomplete F-H-03 finding, confirmed independently by this trace.

### 7.3 `start_at_owner_profile_id` field

The term `start_at_owner_profile_id` does not appear in any source file at this SHA. The concept is represented structurally as `PlaybackOwnerContext.ownerProfileId` (captured at playback start) vs. `profileManager.activeProfileId.value` (current active profile, read lazily inside `checkScrobbleBoundary`). No explicit timestamp or labeled "start_at_owner_profile_id" field is carried in the mutation envelope or trace event.

---

## 8. `metadata.scrobble_rejected` event

### 8.1 Emission confirmed

`TraceMetadataEvents.kt:229–252`:

```kotlin
fun emitScrobbleRejected(
    envelopeProfileId: Int,
    activeProfileId: Int,
    operation: String,
    reason: String
) {
    val sid = sessionId() ?: return
    sink.emit(
        TraceEventEnvelope(
            ...
            eventType = "playback.scrobble_rejected",
            payload = mapOf(
                "envelopeProfileId" to envelopeProfileId,
                "activeProfileId" to activeProfileId,
                "operation" to operation,
                "reason" to reason
            )
        )
    )
}
```

Called from:
- `TraktScrobbleService.kt:297` — `operation = "trakt.checkin"` or `"trakt.scrobble.$action"`
- `SimklScrobbleService.kt:251` — `operation = "simkl.checkin"` or `"simkl.scrobble.$action"`

`TraceMetadataEventsScrobbleRejectedTest` pins the payload shape.

### 8.2 No validator rule — Lane I I-11 confirmed

`TraceValidationRules.ALL` (16 rules) contains no rule referencing `"playback.scrobble_rejected"`. Lane I Finding I-11 documents this gap: the event is emitted but not consumed by any structural invariant rule. No rule asserts that a `playback.scrobble_rejected` event carries non-null `envelopeProfileId` and `activeProfileId`, nor that the write was subsequently blocked (which it is not — see §4).

### 8.3 Conflation of event semantics

The event is named `scrobble_rejected` and the `reason` field is `"STALE_SESSION_WRITE_REJECTED"`. However, as established in §4, the scrobble is **not** rejected — it proceeds to the network. The event name and reason misrepresent the actual behavior: it is a **detection** event, not a **rejection** event. This conflation makes the event actively misleading when consumed by support tooling or validator rules.

---

## 9. Findings

### S-01 — F-H-03 is INCOMPLETE: `checkScrobbleBoundary` emits telemetry but does not halt the write (confirmed restatement of H-01)

**Severity:** P0 (original audit classification), confirmed P1 in Lane H

**Location:**
- `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt:266, 294–303`
- `app/src/main/java/com/nexio/tv/data/repository/SimklScrobbleService.kt:222, 248–258`

**Evidence:**
Both `checkScrobbleBoundary` implementations return `Unit`. The callers (`enqueueScrobble` at Trakt:266, Simkl:222; `enqueueCheckin` at Trakt:242, Simkl:200) do not inspect any return value and do not guard `enqueueAndDrain` on the check outcome. After `checkScrobbleBoundary` fires the `emitScrobbleRejected` event, execution falls through directly to `traktMutationOutboxCoordinator.enqueueAndDrain(...)`.

`ProfileBoundaryEnforcer.assertCanWriteProfileState` (which does throw `ProfileBoundaryException`) is **absent** from the scrobble path. Its sole production caller is `ContinueWatchingSnapshotService.canPublishProfileWrite` (line 1066), which correctly gates the Continue Watching write.

**Impact:** A scrobble issued during a profile switch (or a late-arriving stop/pause from a prior session) will be credited to the wrong profile's Trakt or Simkl account. The trace event fires but carries no enforcement effect.

**Required fix:** Convert `checkScrobbleBoundary` to `Boolean`-returning (or throw-on-mismatch) and gate `enqueueAndDrain` on the result. Alternatively, introduce a `assertCanWriteScrobbleState` helper that routes through `ProfileBoundaryEnforcer`. Add a unit test that verifies the mutation is **not** enqueued when profile mismatch is detected.

---

### S-02 — `scrobble_rejected` event name misrepresents actual behavior (the write proceeds)

**Severity:** P2

**Location:**
- `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt:229–252`
- `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt:297–303`
- `app/src/main/java/com/nexio/tv/data/repository/SimklScrobbleService.kt:251–257`

**Evidence:** The event type is `"playback.scrobble_rejected"` and the reason is `"STALE_SESSION_WRITE_REJECTED"`. Both names assert that the write was rejected. As confirmed in §4 and Finding S-01, the write proceeds regardless. Support engineers, on-call responders, and any future validator rule that takes the event name at face value will incorrectly conclude the boundary is enforced.

**Required fix:** Either (a) fix the enforcement so the name is accurate (the S-01 fix), or (b) rename the event to `"playback.scrobble_boundary_mismatch"` and the reason to `"STALE_SESSION_DETECTED"` to signal detection-only until enforcement is implemented. Option (a) is strongly preferred.

---

### S-03 — No validator rule consumes `playback.scrobble_rejected` events (Lane I I-11, confirmed)

**Severity:** P2

**Location:** `app/src/main/java/com/nexio/tv/core/trace/TraceValidationRules.kt`

**Evidence:** `TraceValidationRules.ALL` has 16 rules. None reference `"playback.scrobble_rejected"`. The `TraceMetadataEventsScrobbleRejectedTest` verifies payload shape at emit time, but no `TraceValidationRule` asserts structural invariants about the event in a trace session (e.g., "every `playback.scrobble_rejected` event carries non-null `envelopeProfileId` and `activeProfileId`").

**Impact:** Schema drift in `emitScrobbleRejected` (e.g., a payload key rename) will not be caught by `RuntimeTraceValidatorRealEmissionTest` or the `generateTraceValidatorAudit` Gradle task.

**Required fix:** Add a `ScrobbleRejectedHasProfileIds` rule to `TraceValidationRules` asserting that every `playback.scrobble_rejected` event carries integer-typed `envelopeProfileId` and `activeProfileId`. Wire the rule into `TraceValidationRules.ALL` and add a test in `RuntimeTraceValidatorTest`. (Cross-reference: Lane I I-11.)

---

### S-04 — `PlaybackOwnerContext.ownerSessionId` is captured but never used in scrobble boundary checks

**Severity:** P2

**Location:**
- `app/src/main/java/com/nexio/tv/core/playback/PlaybackOwnerContext.kt:7`
- `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt:294`
- `app/src/main/java/com/nexio/tv/data/repository/SimklScrobbleService.kt:248`

**Evidence:** `PlaybackOwnerContext` carries `ownerSessionId: String` (the session ID of the active profile at playback start). `DefaultTrackingScrobbleService` extracts only `owner.ownerProfileId` (lines 58, 62, 72, 76, 86, 90), discarding `ownerSessionId`. `checkScrobbleBoundary` compares `envelopeProfileId` against `profileManager.activeProfileId.value` — a profile-ID-only comparison. It does not compare session IDs.

`ProfileBoundaryEnforcer.assertCanWriteProfileState` (line 189) accepts both `resultProfileId` AND `resultSessionId` — its check is `resultProfileId != activeProfileId || resultSessionId != activeSessionId`. The scrobble path uses only the profile-ID half of this predicate. A profile switch that reuses the same `profileId` (e.g., switching from profile 1 to profile 2 and back to profile 1 while the scrobble is in-flight) will not be detected by `checkScrobbleBoundary`.

**Impact:** Stale session writes from the same profile (re-entry scenario) pass the boundary check silently. This is a secondary gap behind S-01 (the check doesn't block anything in any case), but it also means even fixing S-01 to throw would not catch profile-1 → profile-2 → profile-1 re-entry.

**Required fix:** When implementing the S-01 fix, use session-ID comparison, not just profile-ID comparison. The `ownerSessionId` is already on `PlaybackOwnerContext`; pass it through to the scrobble service and compare against `profileManager.activeProfileSession.value.sessionId` at boundary-check time.

---

### S-05 — `PlaybackOwnerContext.traktAccount` and `.simklAccount` are always `null` in production (confirmed restatement of H-04)

**Severity:** P3

**Location:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt:73–74`

**Evidence:**
```kotlin
PlaybackOwnerContext(
    ownerProfileId = session.profileId,
    ownerSessionId = session.sessionId,
    traktAccount = null,
    simklAccount = null,
    startedAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L)
)
```

This is the only production construction site for `PlaybackOwnerContext`. Neither `traktAccount` nor `simklAccount` is read anywhere in `TrackingScrobbleService`, `TraktScrobbleService`, or `SimklScrobbleService`. Scrobble authentication is resolved independently via `authSession(ownerProfileId)` → `traktAuthService.getAuthState(session)`.

**Impact:** No behavioral defect. The fields are dead weight on a data class that carries init precondition guards, adding noise for maintainers.

**Required fix:** Either populate the fields from the active session's provider accounts (if per-session account pinning is planned for future cross-profile scenarios) or remove them and update the F-H-02 architecture pin test accordingly.

---

## 10. Dependency map

```
PlayerScreen
  └─ PlayerViewModel (@HiltViewModel)
        │ init: profileManager.activeProfileSession.value → PlaybackOwnerContext (immutable for session)
        │       playbackSessionRegistry.register(ownerContext)
        └─ PlayerRuntimeController
              ├─ emitScrobbleStart / emitScrobbleStop / emitPauseScrobble
              │     └─ TrackingScrobbleService.scrobbleStart/Stop/Pause(item, progress, owner: PlaybackOwnerContext)
              │           └─ DefaultTrackingScrobbleService
              │                 ├─ trackingProviderStateService.currentState(owner.ownerProfileId) → provider dispatch
              │                 ├─ [TRAKT] → TraktScrobbleService.scrobbleStart/Stop/Pause(item, progress, ownerProfileId)
              │                 │     ├─ authSession(ownerProfileId) → TrackingAuthSession
              │                 │     ├─ submitMutation → pendingMutationMutex → drainPendingMutations
              │                 │     │     └─ enqueueScrobble / enqueueCheckin
              │                 │     │           ├─ checkScrobbleBoundary(profileId, op)    [telemetry only — S-01]
              │                 │     │           │     └─ TraceMetadataEvents.emitScrobbleRejected → "playback.scrobble_rejected"
              │                 │     │           └─ traktMutationOutboxCoordinator.enqueueAndDrain(TraktMutationEnvelope)
              │                 │     │                 └─ TraktScrobbleMutationAdapter.execute → TraktIntegrationProvider.scrobble
              │                 │     │                       └─ apiShapeId = TraktApiShapes.SCROBBLE  [F-C-02 confirmed]
              │                 │     │                             └─ TraktApi.scrobbleStart/Pause/Stop
              │                 │     └─ [on stop/checkin] TraktProgressService.refreshNow()
              │                 └─ [SIMKL] → SimklScrobbleService.scrobbleStart/Stop/Pause(item, progress, ownerProfileId)
              │                       ├─ trackingProviderStateService.currentState(profileId) → auth check
              │                       ├─ submitMutation → drainPendingMutations
              │                       │     └─ enqueueScrobble / enqueueCheckin
              │                       │           ├─ checkScrobbleBoundary(profileId, op)    [telemetry only — S-01]
              │                       │           │     └─ TraceMetadataEvents.emitScrobbleRejected → "playback.scrobble_rejected"
              │                       │           └─ traktMutationOutboxCoordinator.enqueueAndDrain(TraktMutationEnvelope)
              │                       │                 └─ SimklScrobbleMutationAdapter.execute → SimklIntegrationProvider.scrobble{Start,Pause,Stop}
              │                       │                       └─ apiShapeId = SimklApiShapes.SCROBBLE  [F-C-02 confirmed]
              │                       │                             └─ SimklApi.scrobble{Start,Pause,Stop}
              │                       └─ [shared outbox] traktMutationOutboxCoordinator  [S-note: Simkl reuses Trakt outbox]
              └─ scrobbleHeartbeatJob (15 min interval) — same scrobbleStart path

HomeViewModelContinueWatching / MetaDetailsViewModel
  └─ TrackingScrobbleService.checkin(item, message, ownerProfileId = null)  [H-01/H-03; ownerProfileId: Int?, not PlaybackOwnerContext]
```

---

## 11. Findings summary

| ID | Title | Severity | Lane cross-ref | Status |
|----|-------|----------|----------------|--------|
| S-01 | `checkScrobbleBoundary` emits telemetry but does not halt the write — F-H-03 INCOMPLETE | P0/P1 | H-01, F-05 | Open |
| S-02 | `scrobble_rejected` event name misrepresents actual behavior (write proceeds) | P2 | H-01 | Open |
| S-03 | No validator rule consumes `playback.scrobble_rejected` events | P2 | I-11 | Open |
| S-04 | `PlaybackOwnerContext.ownerSessionId` never used in scrobble boundary check — re-entry gap | P2 | — | Open |
| S-05 | `PlaybackOwnerContext.traktAccount` / `.simklAccount` always `null` in production | P3 | H-04 | Open |

---

## 12. Red-flag checklist

| Red flag | Verdict | Evidence |
|----------|---------|----------|
| Scrobble uses current active profile instead of playback-owner profile | CLEAR (structural) | `DefaultTrackingScrobbleService.providerState(owner)` uses `owner.ownerProfileId`; auth session built from owner profile ID not ambient active profile. Pinned by `TrackingScrobbleServicePlaybackOwnerTest`. |
| Profile boundary check actually halts stale writes | FAIL — S-01 | `checkScrobbleBoundary` is `Unit`-returning, telemetry-only; write proceeds regardless of mismatch. |
| `checkin` uses `PlaybackOwnerContext` (it should not) | CLEAR | F-H-01 pin enforces `ownerProfileId: Int?` via reflection. Third parameter confirmed `java.lang.Integer`. |
| `TraktIntegrationProvider.scrobble` uses `TraktApiShapes.SCROBBLE` (not a literal) | CLEAR | `TraktIntegrationProvider.kt:708` — `apiShapeId = TraktApiShapes.SCROBBLE`. F-C-02 pin scans for literals and finds none. |
| `SimklIntegrationProvider` scrobble functions use `SimklApiShapes.SCROBBLE` (not literals) | CLEAR | All four Simkl scrobble calls at lines 189, 202, 215, 228 use `SimklApiShapes.SCROBBLE`. |
| `playback.scrobble_rejected` event has no validator rule | FAIL — S-03 | `TraceValidationRules.ALL` (16 rules): no rule references this event type. Lane I I-11. |
| PlaybackOwnerContext registered before first scrobble fires | CLEAR | `playbackSessionRegistry.register(ownerContext)` called before `PlayerRuntimeController` construction; no scrobble possible before `PlayerRuntimeController` exists. |
| Result-time profile re-validation present | FAIL — S-01 | No call to `assertCanWriteProfileState` exists anywhere in the Trakt or Simkl scrobble paths, before or after the network call. |
