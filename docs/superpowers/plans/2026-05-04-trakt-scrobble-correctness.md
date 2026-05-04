# Trakt Scrobble Correctness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify Trakt's recommended scrobble lifecycle invariants are encoded as contract tests, and add the two missing semantic guards (Seren's progress clamp on completion stops; explicit lock on the existing 409-as-success branch).

**Architecture:** No structural changes. Add focused contract tests around five invariants ported from NuvioTV (the upstream) plus the Seren-style progress clamp on completion stops. Verify the existing 409 handling in `TraktScrobbleMutationAdapter` (lines 93, 127) is not silently changed by future refactors.

**Tech Stack:** Kotlin, JUnit + Mockito, MockK, Retrofit/OkHttp test fakes.

**Source review:** `docs/superpowers/specs/2026-05-04-trakt-watched-history-sync-design.md` Part 2 (NuvioTV port survival list, Seren scrobble patterns).

---

## File Map

**Modify:**
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt` — add `max(progressPercent, 80f)` clamp on the completion-stop scrobble call (around line 349/367).

**Create:**
- `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter409Test.kt` — locks the HTTP 409 → success contract.
- `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleCompletionClampTest.kt` — verifies the new max(progress, 80) clamp.
- `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleThresholdSplitContractTest.kt` — locks the ≥80% completion vs <80% pause split.
- `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleCompletionGuardTest.kt` — locks `hasSentCompletionScrobbleForCurrentItem` (no duplicate completion stops per item).
- `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleEpisodeMappingOrderingTest.kt` — locks `warmTraktEpisodeMappingForCurrentPlayback()` runs before `refreshScrobbleItem()`.
- `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceShowSiblingsAmbiguityTest.kt` — locks the `"__ambiguous__"` sibling sentinel for shared IMDB ids.
- `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceHiddenDroppedFilterTest.kt` — locks Continue Watching filtering against `users/hidden/dropped`.

---

## Task 1: Baseline test build

**Files:** none.

- [ ] **Step 1: Confirm clean tree state**

```bash
git status
git rev-parse --abbrev-ref HEAD
```

- [ ] **Step 2: Run the test surface**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  --tests "com.nexio.tv.ui.screens.player.*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: No commit**

---

## Task 2: Lock HTTP 409 → success on scrobble stop

The contract is already implemented at `TraktScrobbleMutationAdapter.kt:93,127` (`response.isSuccessful || response.code() == 409`). Add a contract test so future refactors can't silently break it.

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter409Test.kt` (create)

- [ ] **Step 1: Read the adapter to understand the success path**

```bash
sed -n '70,140p' app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt
```

Expected: Two methods (likely `interpretStartResponse`, `interpretStopResponse` or similar) that return a success outcome when `response.isSuccessful || response.code() == 409`.

- [ ] **Step 2: Write the test**

Create `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter409Test.kt`:

```kotlin
package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.remote.dto.trakt.TraktScrobbleResponseDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktScrobbleMutationAdapter409Test {

    @Test
    fun stop_409_already_scrobbled_is_treated_as_success() {
        val emptyJson = "{}".toResponseBody("application/json".toMediaType())
        val response409: Response<TraktScrobbleResponseDto> = Response.error(409, emptyJson)
        val outcome = TraktScrobbleMutationAdapter.classifyStopResponse(response = response409)
        assertTrue(
            "Trakt 409 on /scrobble/stop means 'already scrobbled' and must be treated as success",
            outcome.isSuccess
        )
    }

    @Test
    fun start_409_already_scrobbled_is_treated_as_success() {
        val emptyJson = "{}".toResponseBody("application/json".toMediaType())
        val response409: Response<TraktScrobbleResponseDto> = Response.error(409, emptyJson)
        val outcome = TraktScrobbleMutationAdapter.classifyStartResponse(response = response409)
        assertTrue(outcome.isSuccess)
    }
}
```

The exact name of `classifyStopResponse` / `classifyStartResponse` depends on what the adapter exposes. Read the file to find the equivalent — it may be `interpret*Response`, `outcomeFor*`, `mapResponse*`, etc. If no public/internal classifier exists, use a different surface — e.g. construct an envelope, dispatch through the executor, assert on the outcome type. The point of the test is to invoke the same code path that lines 93 and 127 take.

If the only access is via `executeMutation(envelope)`, mock the underlying executor / api to return `Response.error(409, ...)` and assert the public outcome. Adapt to whatever shape exists.

- [ ] **Step 3: Run to verify it passes (the behaviour is already there)**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.trakt.TraktScrobbleMutationAdapter409Test" \
  -x generateIntegrationRuntimeAudit
```
Expected: PASS.

- [ ] **Step 4: Verify the test would fail if the behaviour broke**

Temporarily remove `|| response.code() == 409` from `TraktScrobbleMutationAdapter.kt:93`. Re-run:

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.trakt.TraktScrobbleMutationAdapter409Test.stop_409_already_scrobbled_is_treated_as_success" \
  -x generateIntegrationRuntimeAudit
```
Expected: FAIL. Restore the `|| response.code() == 409` and re-run — pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter409Test.kt
git commit -m "$(cat <<'EOF'
test(trakt): lock HTTP 409 -> success on scrobble responses

Trakt returns 409 for /scrobble/stop and /scrobble/start when the
item is already in the user's history (e.g. scrobbled by another
device). The adapter already handles this; add a contract test so
future refactors can't silently turn it into a hard failure that
would loop the mutation outbox forever.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Add `max(progress, 80f)` clamp on completion-stop

Per Seren's pattern (`player.py:441`): if a user stops at 79% with a local "watched" threshold of 70%, Trakt sees 79% and does not mark the item watched (Trakt's own threshold is 80%). Boost completion-stop progress to ≥80% before posting.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt:366-377` (the `emitCompletionScrobbleStop` function).
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleCompletionClampTest.kt` (create).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleCompletionClampTest.kt`. Mirror the wiring from any existing `PlayerRuntimeController*Test.kt`:

```bash
ls app/src/test/java/com/nexio/tv/ui/screens/player/ | head
```

Add this test (adapt to whatever stub style the existing player tests use):

```kotlin
@Test
fun completion_scrobble_stop_clamps_progress_to_at_least_80_percent() = runBlocking {
    val capturedProgress = mutableListOf<Float>()
    coEvery {
        trackingScrobbleService.scrobbleStop(
            item = any(),
            progressPercent = capture(capturedProgress),
            ownerProfileId = any(),
            ownerSessionId = any()
        )
    } just Runs

    // The local "watched" threshold may be lower than 80% (e.g. 70%). When it crosses we
    // call emitCompletionScrobbleStop with the actual progress (e.g. 75f). The contract
    // is that the value sent to Trakt is max(actual, 80f).
    controller.emitCompletionScrobbleStop(progressPercent = 75f)

    assertEquals(1, capturedProgress.size)
    assertTrue(
        "completion scrobble must post >= 80f (Trakt threshold). Got: ${capturedProgress.first()}",
        capturedProgress.first() >= 80f
    )
}

@Test
fun completion_scrobble_stop_does_not_clamp_when_already_above_80() = runBlocking {
    val capturedProgress = mutableListOf<Float>()
    coEvery {
        trackingScrobbleService.scrobbleStop(
            item = any(),
            progressPercent = capture(capturedProgress),
            ownerProfileId = any(),
            ownerSessionId = any()
        )
    } just Runs

    controller.emitCompletionScrobbleStop(progressPercent = 95f)

    assertEquals(95f, capturedProgress.first())
}
```

`emitCompletionScrobbleStop` is the receiver function at `PlayerRuntimeControllerPlaybackEvents.kt:366`. The test must construct enough of the controller to invoke it. If construction is heavy, consider a minimal fake or use whatever helper the existing tests in this directory use.

If no existing test in this directory exercises `emitCompletionScrobbleStop` directly, look one directory up for a helper, or construct the controller inline mocking only the dependencies needed (the controller takes the scope, the scrobble service, the playback state — a `relaxed = true` mockk for non-essential deps is fine).

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.player.PlayerScrobbleCompletionClampTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: failure on `completion_scrobble_stop_clamps_progress_to_at_least_80_percent` (75f passes through unclamped).

- [ ] **Step 3: Apply the clamp**

Edit `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`. Find `emitCompletionScrobbleStop` (line ~366):

```kotlin
internal fun PlayerRuntimeController.emitCompletionScrobbleStop(progressPercent: Float) {
    if (progressPercent < 80f || hasSentCompletionScrobbleForCurrentItem) return
    hasSentCompletionScrobbleForCurrentItem = true
    // ... existing call to trackingScrobbleService.scrobbleStop(...) with progressPercent
}
```

Wait — re-read this. The function already returns early if `progressPercent < 80f`. So the clamp is only needed if `emitCompletionScrobbleStop` is sometimes called with values below 80f from a different code path. Let me re-trace.

```bash
grep -n "emitCompletionScrobbleStop\|emitStopScrobbleForCurrentProgress" app/src/main/java/com/nexio/tv/ui/screens/player/*.kt
```

If the early-return guard at the top of `emitCompletionScrobbleStop` is the only completion-stop path, and pause-stops go through `emitPauseScrobble` separately (which uses `/scrobble/pause`, not stop), then the Seren clamp is **not needed** — Trakt already gets >= 80f on completion stops because the function returns early otherwise. **Document this** in the test:

Replace the failing-test step with a verification test that locks the early-return guard:

```kotlin
@Test
fun completion_scrobble_stop_does_not_fire_when_progress_below_80_percent() = runBlocking {
    coEvery {
        trackingScrobbleService.scrobbleStop(any(), any(), any(), any())
    } just Runs

    controller.emitCompletionScrobbleStop(progressPercent = 75f)

    coVerify(exactly = 0) {
        trackingScrobbleService.scrobbleStop(any(), any(), any(), any())
    }
}
```

Plus the existing test that verifies 95f passes through. No production code change.

- [ ] **Step 4: Run to confirm pass**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.player.PlayerScrobbleCompletionClampTest" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleCompletionClampTest.kt
git commit -m "$(cat <<'EOF'
test(player): lock completion-scrobble 80%-threshold guard

Verifies emitCompletionScrobbleStop returns early when progress is
below Trakt's 80% completion threshold and posts unmodified progress
above it. Prevents accidental removal of the early-return guard
during future refactors of the player runtime controller.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

NOTE: If during Step 1 you discover that `emitStopScrobbleForCurrentProgress` (line 368-377 area) calls completion-stop in a path where the 80f guard isn't triggered, then the Seren clamp IS needed. Add this to `emitCompletionScrobbleStop`:

```kotlin
internal fun PlayerRuntimeController.emitCompletionScrobbleStop(progressPercent: Float) {
    if (progressPercent < 80f || hasSentCompletionScrobbleForCurrentItem) return
    hasSentCompletionScrobbleForCurrentItem = true
    val clampedProgress = maxOf(progressPercent, 80f)  // Seren pattern: ensure Trakt's own 80% heuristic always passes
    scope.launch {
        trackingScrobbleService.scrobbleStop(
            item = currentScrobbleItem ?: return@launch,
            progressPercent = clampedProgress,
            // ...
        )
    }
}
```

The clamp here is defensive — the early-return already enforces ≥ 80f, but a future refactor that loosens the guard wouldn't accidentally regress Trakt watched-status detection.

---

## Task 4: Lock the ≥80% completion vs <80% pause split

Trakt's contract: `/scrobble/stop` posted with progress >= 80% marks the item watched; <80% records pause/incomplete. The split is encoded today in `emitStopScrobbleForCurrentProgress` (around line 368-377). Lock it.

**Files:**
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleThresholdSplitContractTest.kt` (create)

- [ ] **Step 1: Write the test**

Create the file with two tests:

```kotlin
@Test
fun stop_with_progress_below_80_routes_to_pause_endpoint_not_stop() = runBlocking {
    coEvery { trackingScrobbleService.scrobbleStop(any(), any(), any(), any()) } just Runs
    coEvery { trackingScrobbleService.scrobblePause(any(), any(), any(), any()) } just Runs

    controller.emitStopScrobbleForCurrentProgress(progressPercent = 50f)

    coVerify(exactly = 0) { trackingScrobbleService.scrobbleStop(any(), any(), any(), any()) }
    coVerify(exactly = 1) { trackingScrobbleService.scrobblePause(any(), any(), any(), any()) }
}

@Test
fun stop_with_progress_above_80_routes_to_stop_endpoint_not_pause() = runBlocking {
    coEvery { trackingScrobbleService.scrobbleStop(any(), any(), any(), any()) } just Runs
    coEvery { trackingScrobbleService.scrobblePause(any(), any(), any(), any()) } just Runs

    controller.emitStopScrobbleForCurrentProgress(progressPercent = 90f)

    coVerify(exactly = 1) { trackingScrobbleService.scrobbleStop(any(), any(), any(), any()) }
    coVerify(exactly = 0) { trackingScrobbleService.scrobblePause(any(), any(), any(), any()) }
}
```

If `emitStopScrobbleForCurrentProgress` doesn't take `progressPercent` directly — it may read it from controller state — you'll need to set up the state via the controller's playback state holder before invoking. Adapt.

- [ ] **Step 2: Run to verify the contract holds**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.player.PlayerScrobbleThresholdSplitContractTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: PASS (contract is already implemented).

- [ ] **Step 3: Verify the test detects regression**

Temporarily flip the threshold guard in `emitStopScrobbleForCurrentProgress` (e.g. change `>= 80f` to `>= 100f`). Re-run; tests should fail. Restore.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleThresholdSplitContractTest.kt
git commit -m "$(cat <<'EOF'
test(player): lock 80% scrobble completion vs pause split

Trakt's /scrobble/stop with progress >= 80% marks the item watched;
< 80% records as pause. Lock the split so a future refactor can't
silently mark a 50%-progress stop as completed (or fail to mark a
95%-progress stop).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Lock `hasSentCompletionScrobbleForCurrentItem` (no duplicate completion stops)

End-of-stream callbacks can fire multiple times in quick succession on Android ExoPlayer. The flag at `PlayerRuntimeControllerPlaybackEvents.kt:367` prevents the second completion-stop. Lock it.

**Files:**
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleCompletionGuardTest.kt` (create)

- [ ] **Step 1: Write the test**

```kotlin
@Test
fun multiple_completion_calls_for_same_item_send_only_one_stop_scrobble() = runBlocking {
    coEvery { trackingScrobbleService.scrobbleStop(any(), any(), any(), any()) } just Runs

    controller.emitCompletionScrobbleStop(progressPercent = 95f)
    controller.emitCompletionScrobbleStop(progressPercent = 96f)
    controller.emitCompletionScrobbleStop(progressPercent = 100f)

    coVerify(exactly = 1) { trackingScrobbleService.scrobbleStop(any(), any(), any(), any()) }
}

@Test
fun completion_guard_resets_when_a_new_item_starts() = runBlocking {
    coEvery { trackingScrobbleService.scrobbleStop(any(), any(), any(), any()) } just Runs

    controller.emitCompletionScrobbleStop(progressPercent = 95f)
    controller.onItemStarted()  // or whatever the per-item reset hook is — check the source
    controller.emitCompletionScrobbleStop(progressPercent = 95f)

    coVerify(exactly = 2) { trackingScrobbleService.scrobbleStop(any(), any(), any(), any()) }
}
```

Read `PlayerRuntimeControllerPlaybackEvents.kt:286-287` for what resets `hasSentCompletionScrobbleForCurrentItem` (it's set to false when a new item starts via `scrobbleStartRequestGeneration++`). Use the corresponding controller method in the second test.

- [ ] **Step 2: Run, verify regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.player.PlayerScrobbleCompletionGuardTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: PASS. Temporarily remove the `|| hasSentCompletionScrobbleForCurrentItem` clause from line 367 — first test fails. Restore.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleCompletionGuardTest.kt
git commit -m "$(cat <<'EOF'
test(player): lock per-item completion-scrobble dedup guard

ExoPlayer can fire end-of-stream callbacks multiple times during
teardown. The hasSentCompletionScrobbleForCurrentItem guard ensures
exactly one /scrobble/stop is sent per item; verify it does not
regress.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Lock episode-mapping warmup ordering before scrobble item refresh

For shows with absolute episode numbering, the scrobble item must be built with the season/episode mapping already resolved. NuvioTV's pattern: `warmTraktEpisodeMappingForCurrentPlayback()` runs before `refreshScrobbleItem()` inside `preparePlaybackBeforeStart()`.

**Files:**
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleEpisodeMappingOrderingTest.kt` (create)

- [ ] **Step 1: Locate the relevant functions**

```bash
grep -rn "warmTraktEpisodeMappingForCurrentPlayback\|refreshScrobbleItem\|preparePlaybackBeforeStart" \
  app/src/main/java/com/nexio/tv/ui/screens/player --include="*.kt"
```

If `warmTraktEpisodeMappingForCurrentPlayback` doesn't exist in our fork (function name may have been renamed during the namespace migration), search for alternatives:

```bash
grep -rn "warm.*Episode\|episodeMapping\|absoluteToRelative" app/src/main/java/com/nexio/tv/ui/screens/player --include="*.kt"
```

If the warmup doesn't exist at all in our fork, this is a real gap — flag it and add the warmup before locking the ordering test. Document the finding in the commit message.

- [ ] **Step 2: Write the ordering test**

```kotlin
@Test
fun warm_episode_mapping_runs_before_refresh_scrobble_item() = runBlocking {
    val callOrder = mutableListOf<String>()
    coEvery {
        controller.warmTraktEpisodeMappingForCurrentPlayback()
    } answers {
        callOrder.add("warm")
    }
    coEvery {
        controller.refreshScrobbleItem()
    } answers {
        callOrder.add("refresh")
    }

    controller.preparePlaybackBeforeStart()

    assertEquals(listOf("warm", "refresh"), callOrder)
}
```

If those methods are private/internal extensions, you'll need to use a spy or expose a test seam. Adapt — the goal is to catch a swap of the two calls.

- [ ] **Step 3: Run + verify regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.player.PlayerScrobbleEpisodeMappingOrderingTest" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleEpisodeMappingOrderingTest.kt
git commit -m "$(cat <<'EOF'
test(player): lock episode-mapping warmup before scrobble item refresh

Shows that use absolute episode numbering need the Trakt season/
episode mapping resolved before the scrobble item is built. Pin the
ordering so a future refactor can't swap the two calls and silently
post wrong (season, episode) numbers for absolute-numbered shows.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Lock `showIdSiblingsMap` ambiguity sentinel

NuvioTV `TraktProgressService.kt:1250-1261` marks shared-IMDB-id entries with `"__ambiguous__"` so badge state doesn't flip incorrectly between two shows that share an external id. After Tasks 10-12 of the previous plan added alias-key projection, the equivalent guard should still be present.

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceShowSiblingsAmbiguityTest.kt` (create)

- [ ] **Step 1: Find or define the equivalent in our fork**

```bash
grep -rn "__ambiguous__\|ambiguous\|sibling\|sharedImdb" app/src/main/java/com/nexio/tv/data/repository/Trakt*.kt | head -10
```

If the sentinel exists, lock it with a contract test. If it doesn't (regressed during port), this task escalates — STOP and report so the user can decide whether to add the guard or accept the gap.

- [ ] **Step 2: If the sentinel exists, write the test**

```kotlin
@Test
fun two_shows_sharing_imdb_id_do_not_pollute_each_others_alias_lookup() = runBlocking {
    val showA = TraktWatchedShowItemDto(
        plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z",
        show = TraktShowDto(
            title = "Show A", year = 2020,
            ids = TraktIdsDto(trakt = 1, slug = "show-a", imdb = "tt9999999", tvdb = 100)
        ),
        seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
            TraktWatchedEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z")
        )))
    )
    val showB = TraktWatchedShowItemDto(
        plays = 1, lastWatchedAt = "2026-05-04T11:00:00Z",
        show = TraktShowDto(
            title = "Show B", year = 2021,
            // Same imdb id as Show A — known data-quality issue on Trakt.
            ids = TraktIdsDto(trakt = 2, slug = "show-b", imdb = "tt9999999", tvdb = 200)
        ),
        seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
            TraktWatchedEpisodeDto(number = 2, plays = 1, lastWatchedAt = "2026-05-04T11:00:00Z")
        )))
    )
    coEvery { traktIntegrationProvider.getWatchedShows() } returns
        IntegrationCallResult.Success(listOf(showA, showB))

    val watchedByImdb = service.observeEpisodeProgress("tt9999999").first()

    // Lookup by the ambiguous IMDB id should NOT return both shows' episode sets merged.
    // Either it returns one show's set deterministically or it returns empty (rejecting the
    // ambiguous lookup). Both are acceptable; what's NOT acceptable is returning the union
    // {(1,1), (1,2)} as if both belonged to one show.
    assertTrue(
        "ambiguous IMDB lookup must not merge episode sets across shows. Got: $watchedByImdb",
        watchedByImdb.size <= 1
    )
}
```

- [ ] **Step 3: Run + verify**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceShowSiblingsAmbiguityTest" \
  -x generateIntegrationRuntimeAudit
```

If the test fails (i.e. the bug is present), STOP. The fix is non-trivial (it requires implementing the `__ambiguous__` sentinel pattern) and warrants its own plan. Report DONE_WITH_CONCERNS noting that the contract test reveals an existing bug.

- [ ] **Step 4: Commit (only if green)**

```bash
git add app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceShowSiblingsAmbiguityTest.kt
git commit -m "$(cat <<'EOF'
test(trakt): lock alias-id ambiguity guard for shared IMDB ids

Two shows sharing an IMDB id (a known Trakt data-quality issue) must
not pollute each other's episode-watched alias lookup. Pin the guard
so a future change to the alias projection can't accidentally merge
unrelated shows.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Lock hidden/dropped filtering in Continue Watching

NuvioTV uses `users/hidden/dropped` to suppress dropped shows from Continue Watching. We have `HiddenProgressSnapshot`; verify it's actually applied to the next-up flow.

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceHiddenDroppedFilterTest.kt` (create)

- [ ] **Step 1: Find the hidden-progress consumer**

```bash
grep -rn "HiddenProgressSnapshot\|hiddenShowIds\|droppedShowIds" app/src/main/java/com/nexio/tv/data/repository/Trakt*.kt | head -10
```

Locate where `getHiddenProgressSnapshot` is consumed by the next-up derivation (likely `deriveNextUpFromWatchedShows`).

- [ ] **Step 2: Write the test**

```kotlin
@Test
fun dropped_show_is_excluded_from_next_up() = runBlocking {
    val droppedShow = TraktWatchedShowItemDto(
        plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z",
        show = TraktShowDto(
            title = "Dropped Show", year = 2020,
            ids = TraktIdsDto(trakt = 99, tvdb = 999, imdb = "tt9999998", tmdb = 9998)
        ),
        seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
            TraktWatchedEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z")
        )))
    )
    val droppedHidden = TraktHiddenItemDto(
        type = "show",
        show = droppedShow.show
    )

    coEvery { traktIntegrationProvider.getWatchedShows() } returns
        IntegrationCallResult.Success(listOf(droppedShow))
    coEvery {
        traktIntegrationProvider.getHiddenItems(section = "dropped", type = "show", page = any(), limit = any())
    } returns IntegrationCallResult.Success(TraktPagedResponse(body = listOf(droppedHidden), pageCount = 1))

    val nextUp = service.observeNextUp().first()
    assertTrue(
        "dropped show must not appear in next-up. Got: ${nextUp.map { it.contentId }}",
        nextUp.none { it.contentId == "tvdb:999" }
    )
}
```

`observeNextUp` may be `myShowsNextUp` flow or similar. Check the public API.

- [ ] **Step 3: Run + verify regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceHiddenDroppedFilterTest" \
  -x generateIntegrationRuntimeAudit
```

If the test fails, the filter is missing. STOP and report DONE_WITH_CONCERNS — the fix is wiring `HiddenProgressSnapshot.droppedShowIds` into the next-up derivation, which warrants a small dedicated patch.

- [ ] **Step 4: Commit (if green)**

```bash
git add app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceHiddenDroppedFilterTest.kt
git commit -m "$(cat <<'EOF'
test(trakt): lock hidden/dropped show filter in next-up derivation

Shows the user has marked dropped on Trakt must not appear in
Continue Watching. Pin the contract so a future change to next-up
derivation can't silently resurface them.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Final verification

**Files:** none.

- [ ] **Step 1: Full test surface**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  --tests "com.nexio.tv.ui.screens.player.*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL. ~7 new contract tests added (Tasks 2, 3, 4, 5, 6, 7, 8).

- [ ] **Step 2: Document any DONE_WITH_CONCERNS findings**

If Tasks 6, 7, or 8 escalated (warmup/sentinel/dropped-filter missing in our fork), summarize in `docs/superpowers/specs/2026-05-04-trakt-watched-history-sync-design.md` follow-ups list.

---

## Self-Review

**Spec coverage:**
- Recommendation 2a (HTTP 409 handling): Task 2. ✅
- Recommendation 2b (max(progress, 80) clamp): Task 3 (verified existing guard already does the equivalent; Seren clamp added defensively if needed). ✅
- Recommendation 5.1 (sibling ambiguity): Task 7. ✅
- Recommendation 5.2 (hidden/dropped filter): Task 8. ✅
- Recommendation 5.3 (completion vs pause split): Task 4. ✅
- Recommendation 5.4 (episode mapping warmup): Task 6. ✅
- Recommendation 5.5 (completion guard): Task 5. ✅

**Placeholder scan:** clean. Each test step contains the actual assertion code; each "if it doesn't exist, escalate" branch is explicit about what to do.

**Type consistency:** `emitCompletionScrobbleStop`, `emitStopScrobbleForCurrentProgress`, `hasSentCompletionScrobbleForCurrentItem`, `warmTraktEpisodeMappingForCurrentPlayback`, `refreshScrobbleItem`, `preparePlaybackBeforeStart` referenced consistently across tasks 3-6. `TraktScrobbleMutationAdapter` classifier method names are flagged as "may need adaptation" in Task 2 — implementer reads source first.
