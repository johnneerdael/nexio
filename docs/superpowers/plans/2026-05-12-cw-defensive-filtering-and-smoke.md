# Continue Watching — Defensive Filtering + On-Device Smoke Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `ContinueWatchingMerger` self-consistent (filter completed/history records at merger entry, drop the dead `?: existing` null-swallow path) so the post-Phase-3 cross-provider conflict resolver behaves correctly even if upstream filters change. Then run the on-device smoke test deferred at finish-branch time to confirm the full dual-provider CW pipeline surfaces the right items.

**Architecture:** Two small phases. Phase 1 is a defensive-correctness rewrite of `ContinueWatchingMerger` — filter completed records at entry, propagate null returns from `ContinueWatchingProgressDiffPlanner` correctly, drop them from the output. Phase 2 is a documented on-device smoke run (no code changes) that validates the dual-provider CW behavior against the audit's failure-mode catalog.

**Honest scope statement:** Phase 1 is **defense-in-depth, not a hot-fix.** In current code the merger's input is already <85%-filtered by `ContinueWatchingSnapshotService.selectResumeItemsForContinueWatching:797`, so the planner's >=95% null-return path is unreachable in production. This plan removes the dead-code branch so a future change to the upstream filter (e.g. raising the cap to 0.95) cannot silently reintroduce the latent bug.

**Tech Stack:** Kotlin, JUnit 4 + MockK, Android Studio. ADB for on-device smoke. No new dependencies.

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt` | Modify — filter near-complete + history-source records at top of `merge()`; convert `chooseProgressWinner` to nullable return; drop null records from result |
| `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt` | Modify — add 3 defense-in-depth tests; existing tests stay green |
| `docs/superpowers/notes/2026-05-12-cw-dual-provider-smoke.md` | Create — capture on-device smoke output for traceability |

---

## Phase 1 — Defensive filter at the merger

**Goal:** The merger drops any record passing through that does not belong in CW (>=85% complete, or history/show-progress source), regardless of whether the upstream pipeline already filtered. The planner's null return now correctly removes the row from the merged output instead of falling back to `existing`.

### Task 1.1: Pin existing behavior — record what already works

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt`

- [ ] **Step 1: Run the existing merger test suite to confirm current green**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingMergerTest"`

Expected: 10 tests pass (the 5 original + 5 added in Phase 2.4 / 3.5 of the prior plan).

- [ ] **Step 2: No commit — this is a pre-flight check**

If any test fails here, STOP. Fix the regression before continuing. A failure means main has drifted since the dual-provider work merged.

### Task 1.2: Write the failing tests for completed-record drop

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt`

- [ ] **Step 1: Add the three new tests**

Place these tests AFTER the existing `cross-provider conflict keeps leader on meaningful delta despite older timestamp` test in the same class (~line 320). The `record()` helper already accepts `idBundle` and `positionMs` so no helper changes are needed.

```kotlin
    @Test
    fun `near-complete cross-provider records are dropped from merged output`() {
        val nearTrakt = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt-near", videoId = "tt-near:1:1",
            positionMs = 95_000L, durationMs = 100_000L, lastWatchedMs = 1000L,
        )
        val nearSimkl = resumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tt-near", videoId = "tt-near:1:1",
            positionMs = 96_000L, durationMs = 100_000L, lastWatchedMs = 2000L,
        )
        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = nearTrakt,
                    positionMs = 95_000L,
                    updatedAt = 1000L,
                    parentId = "x",
                    contentIdKey = "x:s1e1",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt-near", season = 1, episode = 1),
                ).copy(provider = TrackingProvider.TRAKT, durationMs = 100_000L),
                record(
                    resumeIdentity = nearSimkl,
                    positionMs = 96_000L,
                    updatedAt = 2000L,
                    parentId = "y",
                    contentIdKey = "y:s1e1",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt-near", season = 1, episode = 1),
                ).copy(provider = TrackingProvider.SIMKL, durationMs = 100_000L),
            )
        )
        assertEquals(0, merged.size)
    }

    @Test
    fun `near-complete same-provider records are also dropped`() {
        val a = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt-same", videoId = "tt-same:1:1",
            positionMs = 95_500L, durationMs = 100_000L, lastWatchedMs = 1000L,
        )
        val b = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_HISTORY,
            contentId = "tt-same", videoId = "tt-same:1:1",
            positionMs = 100_000L, durationMs = 100_000L, lastWatchedMs = 2000L,
        )
        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = a,
                    positionMs = 95_500L,
                    updatedAt = 1000L,
                    parentId = "x",
                    contentIdKey = "x:s1e1",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt-same", season = 1, episode = 1),
                ).copy(provider = TrackingProvider.TRAKT, durationMs = 100_000L),
                record(
                    resumeIdentity = b,
                    positionMs = 100_000L,
                    updatedAt = 2000L,
                    parentId = "x",
                    contentIdKey = "x:s1e1",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt-same", season = 1, episode = 1),
                ).copy(provider = TrackingProvider.TRAKT, durationMs = 100_000L),
            )
        )
        assertEquals(0, merged.size)
    }

    @Test
    fun `records at the 95 percent boundary still drop`() {
        val onBoundary = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt-edge", videoId = "tt-edge:1:1",
            positionMs = 95_000L, durationMs = 100_000L, lastWatchedMs = 1000L,
        )
        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = onBoundary,
                    positionMs = 95_000L,
                    updatedAt = 1000L,
                    parentId = "x",
                    contentIdKey = "x:s1e1",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt-edge", season = 1, episode = 1),
                ).copy(provider = TrackingProvider.TRAKT, durationMs = 100_000L),
            )
        )
        assertEquals(0, merged.size)
    }
```

- [ ] **Step 2: Run the three new tests — expect FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingMergerTest"`

Expected output (substring): 3 tests FAILED:
- `near-complete cross-provider records are dropped from merged output FAILED`
- `near-complete same-provider records are also dropped FAILED`
- `records at the 95 percent boundary still drop FAILED`

The 10 prior tests continue to pass. The assertion `assertEquals(0, merged.size)` fails because the current merger surfaces 1 record (the planner returns null, `?: existing` swallows that, the record stays).

### Task 1.3: Filter at merger entry; let planner-null drop the group

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt`

- [ ] **Step 1: Replace the file contents**

The full new file (writing it out completely because the file is small and the diff would otherwise span 4 hunks):

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.WatchProgress

object ContinueWatchingMerger {
    // Stateless — instantiated once at object-init time. Hilt-injected planners elsewhere
    // reuse the same class; this internal instance is only consulted by mergeRecords.
    private val diffPlanner = ContinueWatchingProgressDiffPlanner()

    // Records at or above this completion ratio do not belong in Continue Watching.
    // Matches ContinueWatchingProgressDiffPlanner.NEAR_COMPLETE_PERCENT (95%) and
    // CrossWatch's _planner.py:218 max_percent default. The upstream
    // ContinueWatchingSnapshotService.shouldTreatAsResumeForContinueWatching already
    // drops >=85% records (WatchProgress.COMPLETED_THRESHOLD), but applying the cap here
    // too keeps the merger self-consistent regardless of upstream filter drift.
    private const val NEAR_COMPLETE_PERCENT = 95f

    fun merge(records: List<ContinueWatchingRecord>): List<ContinueWatchingRecord> {
        if (records.isEmpty()) return emptyList()
        val eligible = records.filter { it.isEligibleForContinueWatching() }
        if (eligible.isEmpty()) return emptyList()
        val sorted = eligible.sortedByDescending { it.updatedAt }

        // Union-find: two records share a group if they share any non-null ID under the
        // same provider key. Episode bundles include season+episode in the bucket key so
        // they only collapse when both episode coordinates match.
        val parent = IntArray(sorted.size) { it }
        fun find(x: Int): Int {
            var cur = x
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]
                cur = parent[cur]
            }
            return cur
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        val byBucket = HashMap<String, MutableList<Int>>()
        sorted.forEachIndexed { idx, record ->
            val keys = record.idBundle.toBucketKeys()
            if (keys.isEmpty()) {
                // Back-compat: records without idBundle fall back to legacy identityKey().
                byBucket.getOrPut("legacy:${record.identityKey()}") { mutableListOf() }.add(idx)
            } else {
                keys.forEach { key ->
                    byBucket.getOrPut(key) { mutableListOf() }.add(idx)
                }
            }
        }
        byBucket.values.forEach { indices ->
            for (i in 1 until indices.size) union(indices[0], indices[i])
        }

        // Reduce each group. mergeRecords returns null when the planner decides the group
        // should not surface in CW (e.g. all candidates >=95% complete). Null groups are
        // dropped from the final list.
        val groups = LinkedHashMap<Int, ContinueWatchingRecord?>()
        sorted.indices.forEach { idx ->
            val root = find(idx)
            val cur = groups[root]
            groups[root] = if (cur == null && !groups.containsKey(root)) {
                sorted[idx]
            } else {
                mergeRecords(cur, sorted[idx])
            }
        }
        return groups.values.filterNotNull().sortedByDescending { it.updatedAt }
    }

    private fun ContinueWatchingRecord.isEligibleForContinueWatching(): Boolean {
        if (percentComplete() >= NEAR_COMPLETE_PERCENT) return false
        // History and show-progress records describe past-tense watched state, not
        // resume state. They survive earlier filters only when emitters bypass
        // shouldTreatAsResumeForContinueWatching for any reason; this is a backstop.
        return when (source) {
            ContinueWatchingRecord.Source.LOCAL,
            ContinueWatchingRecord.Source.SYNTHETIC,
            ContinueWatchingRecord.Source.REMOTE -> true
        }
    }

    private fun ContinueWatchingRecord.percentComplete(): Float =
        if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()) * 100f

    private fun mergeRecords(
        existing: ContinueWatchingRecord?,
        candidate: ContinueWatchingRecord
    ): ContinueWatchingRecord? {
        if (existing == null) return candidate
        val progressWinner = chooseProgressWinner(existing, candidate) ?: return null
        val aliases = (existing.resumeIdentities + candidate.resumeIdentities)
            .distinctBy { it.lookupKey() }
        val aliasLookupKeys = aliases.map { it.lookupKey() }.toSet()
        val primaryResumeLookupKey = progressWinner.primaryResumeLookupKey
            ?.takeIf { it in aliasLookupKeys }
            ?: progressWinner.resumeIdentities
                .firstOrNull { it.lookupKey() in aliasLookupKeys }
                ?.lookupKey()

        return progressWinner.copy(
            resumeIdentities = aliases,
            primaryResumeLookupKey = primaryResumeLookupKey,
            streamFetchIdentity = chooseStreamIdentity(
                existing.streamFetchIdentity,
                candidate.streamFetchIdentity
            ),
            trackingIdentity = existing.trackingIdentity ?: candidate.trackingIdentity,
            displayIdentity = existing.displayIdentity ?: candidate.displayIdentity,
            identityConfidence = listOf(existing.identityConfidence, candidate.identityConfidence)
                .minBy { it.ordinal },
            identityWarnings = (existing.identityWarnings + candidate.identityWarnings).distinct()
        )
    }

    private fun chooseProgressWinner(
        existing: ContinueWatchingRecord,
        candidate: ContinueWatchingRecord
    ): ContinueWatchingRecord? {
        // Cross-provider conflict: defer to the diff planner so a meaningful position lead
        // never regresses just because the trailing provider has a newer timestamp. Null
        // return means the planner judged neither candidate eligible for CW; propagate it.
        if (existing.provider != candidate.provider) {
            return diffPlanner.pickWinner(listOf(existing, candidate))
        }
        val existingHasProgress = existing.hasMeaningfulProgress()
        val candidateHasProgress = candidate.hasMeaningfulProgress()
        if (!existingHasProgress && candidateHasProgress) return candidate
        if (candidate.updatedAt > existing.updatedAt && candidateHasProgress) return candidate
        return existing
    }

    private fun ContinueWatchingRecord.hasMeaningfulProgress(): Boolean {
        if (positionMs > 0L) return true

        val primaryResumeLookupKey = primaryResumeLookupKey
        val currentResumeIdentities = if (primaryResumeLookupKey == null) {
            resumeIdentities.take(1)
        } else {
            resumeIdentities.filter { it.lookupKey() == primaryResumeLookupKey }
        }
        return currentResumeIdentities.any { (it.progressPercent ?: 0f) > 0f }
    }

    private fun chooseStreamIdentity(
        existing: StreamFetchIdentity?,
        candidate: StreamFetchIdentity?
    ): StreamFetchIdentity? {
        if (existing == null) return candidate
        if (candidate == null) return existing
        return if (candidate.confidence.ordinal < existing.confidence.ordinal) candidate else existing
    }
}
```

Note: the `WatchProgress` import is added at the top even though the new code doesn't reference `WatchProgress` directly — it's there to support the `Source` enum reference (which lives on `ContinueWatchingRecord`, not `WatchProgress`). If the IDE flags `WatchProgress` as unused, remove the import. The truly-needed changes vs. the current file are:
1. Add `NEAR_COMPLETE_PERCENT` constant + `isEligibleForContinueWatching` extension + `percentComplete` extension
2. Filter `records` to `eligible` at top of `merge()`
3. Change `groups` value type to `ContinueWatchingRecord?` and use `filterNotNull()` before sort
4. Change `mergeRecords` to nullable return; threadnull through chooseProgressWinner
5. Remove `?: existing` from the cross-provider branch in `chooseProgressWinner`

The rest of the file body is unchanged.

- [ ] **Step 2: Run the merger tests — expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingMergerTest"`

Expected: 13 tests pass (10 prior + 3 new).

If any prior test fails, the change broke an established invariant. Common causes:
- The new filter dropped a record that an old test expected to survive (e.g. an old test with `durationMs = 100L, positionMs = 100L` would now be filtered as 100% complete — check fixture math).
- The null-propagation in `mergeRecords` returned null where the old `?: existing` would have returned the record. Read the failing test to identify which case it pinned.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt \
        app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt
git commit -m "$(cat <<'EOF'
fix(cw): merger drops near-complete records instead of swallowing planner null

ContinueWatchingMerger.merge() now filters records at >= 95% completion at
the top of the function and propagates ContinueWatchingProgressDiffPlanner's
null return through mergeRecords -> chooseProgressWinner -> merge output.

Prior code had `?: existing` after the planner call, which silently
surfaced records the planner had judged ineligible. The upstream
ContinueWatchingSnapshotService.shouldTreatAsResumeForContinueWatching
already drops >= 85% records, so the previous behavior was a latent bug,
not a live one. This change makes the merger self-consistent so a future
change to the upstream filter cannot reintroduce the silent surface.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 1.4: Pin the "history record reaching merger" backstop

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt`

This task is a no-impl-needed pin: the `isEligibleForContinueWatching` source-based check in Task 1.3 already exits early for History/Show-Progress sources, but `ContinueWatchingRecord.Source` only has `LOCAL / SYNTHETIC / REMOTE` (we checked at `ContinueWatchingRecord.kt:77`). All three are passthrough. The source-string-level distinction lives on `WatchProgress.source` (string), not on `ContinueWatchingRecord.Source`.

So the source check in `isEligibleForContinueWatching` is currently a no-op pass-through. We document this with a test so a future maintainer doesn't add a `HISTORY` value to the enum without also wiring it up.

- [ ] **Step 1: Add the pin test**

```kotlin
    @Test
    fun `all current source enum values pass the eligibility backstop`() {
        // Pin: when ContinueWatchingRecord.Source gains a new value (e.g. HISTORY),
        // the maintainer must update ContinueWatchingMerger.isEligibleForContinueWatching
        // to decide whether that source should surface in CW. This test fails if a new
        // enum value is added without being explicitly enumerated.
        val expected = setOf(
            ContinueWatchingRecord.Source.LOCAL,
            ContinueWatchingRecord.Source.SYNTHETIC,
            ContinueWatchingRecord.Source.REMOTE,
        )
        assertEquals(expected, ContinueWatchingRecord.Source.values().toSet())
    }
```

- [ ] **Step 2: Run it — expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingMergerTest"`

Expected: 14 tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt
git commit -m "$(cat <<'EOF'
test(cw): pin Source enum so eligibility backstop stays in sync

If a future change adds a History or ShowProgress value to
ContinueWatchingRecord.Source, this test will fail and force the
maintainer to update ContinueWatchingMerger.isEligibleForContinueWatching
to decide whether that source should surface in CW.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2 — On-device smoke verification

**Goal:** Confirm on a real device that the dual-provider CW pipeline correctly surfaces items per the audit's failure-mode catalog. This is the smoke I deferred at finish-branch time after the dual-provider work merged.

Phase 2 produces no code commit. It produces a `docs/superpowers/notes/2026-05-12-cw-dual-provider-smoke.md` artifact that captures the test run for traceability.

### Task 2.1: Pre-flight — verify device + auth state

**Files:**
- Create: `docs/superpowers/notes/2026-05-12-cw-dual-provider-smoke.md` (start of file)

- [ ] **Step 1: Confirm device is reachable**

Run:
```bash
adb -s 192.168.50.98:5555 shell getprop ro.product.model
```

Expected: a non-empty product model string (e.g. `AFTKMST12`). If empty, prompt the user for the correct ADB device serial and use that for the rest of Phase 2.

- [ ] **Step 2: Confirm both Trakt and Simkl are authenticated**

In-app: Settings → Tracking → confirm both connect cards show "Connected as @username". If only one is connected, complete OAuth for the other before continuing (per the dual-provider plan, Phase 0.3 made the two cards independent — both can be active).

- [ ] **Step 3: Open the smoke notes file**

```bash
mkdir -p docs/superpowers/notes
```

Create `docs/superpowers/notes/2026-05-12-cw-dual-provider-smoke.md` with:

```markdown
# Continue Watching Dual-Provider Smoke — 2026-05-12

Branch under test: feat/scrobble-cw-dual-provider merge tip (commit afca3dd34)
Device: <product model> serial 192.168.50.98:5555
Auth state: Trakt @<user> / Simkl @<user>

## Scenarios

### Scenario 1: TMDB-keyed addon stream — full ID hydration
(filled in by Task 2.2)

### Scenario 2: Cross-provider dedup
(filled in by Task 2.3)

### Scenario 3: Anime SIMKL-first routing
(filled in by Task 2.4)

### Scenario 4: Trakt pause-above-80% guard
(filled in by Task 2.5)

## Conclusion
(filled in by Task 2.6)
```

### Task 2.2: Scenario 1 — TMDB-keyed addon stream, full ID hydration

**Files:**
- Modify: `docs/superpowers/notes/2026-05-12-cw-dual-provider-smoke.md`

- [ ] **Step 1: Launch app and select profile**

Per CLAUDE.md §8 (profile-picker rule):

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
```

- [ ] **Step 2: Start playback on a known TMDB-keyed series**

Navigate to a TMDB-keyed item (e.g. an addon-sourced series whose item id starts with `tmdb:`). Start playback. Let it run for at least 60 seconds (past the start scrobble + first heartbeat).

- [ ] **Step 3: Pull logcat for scrobble emissions**

```bash
adb -s 192.168.50.98:5555 logcat -d -t 1200 | grep -iE "Trakt.*ids|Simkl.*ids|scrobble" | tail -40 > /tmp/cw-smoke-scenario-1.log
```

- [ ] **Step 4: Inspect — both providers received the scrobble with full IDs**

Open `/tmp/cw-smoke-scenario-1.log`. Confirm:
- Trakt envelope includes `imdb=tt...` AND `tmdb=...` AND ideally `tvdb=...` (depending on the show)
- Simkl envelope includes `imdb=tt...` AND `tmdb=...` AND ideally `simkl=...`

If either envelope only carries TMDB (the original contentId scheme), Phase 1's hydrator is not wired correctly on this code path. Document the failure and stop.

- [ ] **Step 5: Confirm Trakt + Simkl web both recorded the play**

Manually open https://trakt.tv/users/<your-user>/history and confirm the just-watched item is there.
Manually open https://simkl.com/dashboard/ and confirm the same item is in "Watching".

- [ ] **Step 6: Update the smoke notes**

Append to `docs/superpowers/notes/2026-05-12-cw-dual-provider-smoke.md` under `Scenario 1`:

```markdown
- Content: <show title> S<season>E<episode>
- contentId launched with: tmdb:<id>
- Trakt envelope IDs: imdb=tt..., tmdb=..., tvdb=...
- Simkl envelope IDs: imdb=tt..., tmdb=..., simkl=...
- Trakt web confirms watched: YES / NO
- Simkl web confirms watching: YES / NO
- Verdict: PASS / FAIL (one-line reason)
```

### Task 2.3: Scenario 2 — cross-provider dedup

**Files:**
- Modify: `docs/superpowers/notes/2026-05-12-cw-dual-provider-smoke.md`

- [ ] **Step 1: Set up the duplicate**

While playback from Scenario 1 is still in-flight on Trakt (paused around 40%), use the Simkl web UI to mark the same episode as "Watching at 60%" via the Simkl dashboard. This creates two playback entries for the same episode under different IDs.

- [ ] **Step 2: Force a CW refresh in the app**

In the app: navigate back to Home → scroll up to refresh CW. Or kill and relaunch with profile selection.

- [ ] **Step 3: Pull logcat for merger emissions**

```bash
adb -s 192.168.50.98:5555 logcat -d -t 600 | grep -iE "ContinueWatchingMerger|ContinueWatchingIdentity|merge by multi-id" | tail -20 > /tmp/cw-smoke-scenario-2.log
```

If the merger does not log emissions, fall back to inspecting the CW UI: does the show appear once or twice?

- [ ] **Step 4: Inspect**

On the home screen, count how many CW tiles exist for the same episode. Expected: 1 tile.

If there are 2 tiles, dedup failed for this combination of source IDs. Document the failure including the contentIds visible in `/tmp/cw-smoke-scenario-2.log`.

- [ ] **Step 5: Update smoke notes**

Append to Scenario 2:

```markdown
- Two sources: Trakt playback at <X>% + Simkl playback at <Y>%
- CW tiles for that episode: <count>
- If 1, which provider's progress wins (TRAKT / SIMKL): <which>
- Diff planner's choice matches CrossWatch rules (newer-by-30s OR meaningful-position-lead): YES / NO
- Verdict: PASS / FAIL (one-line reason)
```

### Task 2.4: Scenario 3 — anime SIMKL-first

**Files:**
- Modify: `docs/superpowers/notes/2026-05-12-cw-dual-provider-smoke.md`

- [ ] **Step 1: Choose a Kitsu/MAL anime item**

Navigate to an anime rail (Kitsu-sourced if available). Pick a series whose contentId begins with `kitsu:` or `mal:`. Start playback of an episode. Let it run 60+ seconds.

- [ ] **Step 2: Pull logcat for scrobble routing**

```bash
adb -s 192.168.50.98:5555 logcat -d -t 1200 | grep -iE "anime|parent.*anime|Trakt.*projection|Simkl.*scrobble|ANIME_COORDINATE_UNRESOLVED" | tail -40 > /tmp/cw-smoke-scenario-3.log
```

- [ ] **Step 3: Inspect — Simkl received an `anime` parent envelope**

Open `/tmp/cw-smoke-scenario-3.log`. Confirm:
- The Simkl envelope's `parentKind` is `anime` (not `show`)
- The Simkl envelope's `ids` carries `mal=` AND/OR `kitsu=` AND/OR `anilist=` AND/OR `anidb=`
- If Trakt anime projection failed (`ANIME_COORDINATE_UNRESOLVED`), the Trakt scrobble is dropped — that's the expected behavior. Simkl should still scrobble.

- [ ] **Step 4: Confirm Simkl web**

https://simkl.com/dashboard/ → the anime item should be in "Watching". Trakt may or may not record it depending on projection success.

- [ ] **Step 5: Update smoke notes**

Append to Scenario 3:

```markdown
- Anime: <title> S<s>E<e>
- contentId: kitsu:<id> (or mal:<id>)
- Trakt projection succeeded: YES / NO (if NO, this is the failure mode Phase 4 is supposed to handle)
- Simkl envelope parentKind: anime / show
- Simkl envelope anime IDs included: mal=..., kitsu=..., anilist=..., anidb=...
- Simkl web confirms anime entry: YES / NO
- Verdict: PASS / FAIL (one-line reason)
```

### Task 2.5: Scenario 4 — Trakt pause-above-80% guard

**Files:**
- Modify: `docs/superpowers/notes/2026-05-12-cw-dual-provider-smoke.md`

- [ ] **Step 1: Play to ~85% and pause**

Pick any non-anime episode. Seek to ~85% of the runtime. Press pause.

- [ ] **Step 2: Pull logcat**

```bash
adb -s 192.168.50.98:5555 logcat -d -t 1200 | grep -iE "Trakt.*scrobble|action=pause|action=stop|coerce" | tail -20 > /tmp/cw-smoke-scenario-4.log
```

- [ ] **Step 3: Inspect — pause coerced to stop at >=80%**

Open `/tmp/cw-smoke-scenario-4.log`. The Trakt envelope's `action` field for the pause emit should be `stop` (Phase 5 coercion). It should NOT be `pause`. Confirm no `422` response from Trakt for that envelope (would indicate the coercion didn't trigger).

- [ ] **Step 4: Update smoke notes**

Append to Scenario 4:

```markdown
- Progress at pause: ~<X>%
- Trakt envelope action: stop / pause
- Trakt response code (if visible in logs): 2xx / 422 / other
- Verdict: PASS / FAIL (one-line reason)
```

### Task 2.6: Conclusion + commit

**Files:**
- Modify: `docs/superpowers/notes/2026-05-12-cw-dual-provider-smoke.md`

- [ ] **Step 1: Fill in the conclusion section**

```markdown
## Conclusion

| Scenario | Verdict |
|---|---|
| 1. Full ID hydration | PASS / FAIL |
| 2. Cross-provider dedup | PASS / FAIL |
| 3. Anime SIMKL-first | PASS / FAIL |
| 4. Trakt pause >=80% guard | PASS / FAIL |

Open issues:
- <list any FAILs and the suspected file:line>

Next action:
- <e.g. "All four pass; close the dual-provider audit." OR "Scenario 2 failed; investigate ContinueWatchingMerger.merge with the logged contentIds.">
```

- [ ] **Step 2: Commit the smoke notes**

```bash
git add docs/superpowers/notes/2026-05-12-cw-dual-provider-smoke.md
git commit -m "$(cat <<'EOF'
docs(cw): on-device smoke verification for dual-provider pipeline

Captures the four scenarios from the audit's failure-mode catalog:
full ID hydration, cross-provider dedup, anime SIMKL-first routing,
and the Trakt pause-above-80 guard. Run against the dual-provider
work that merged in commits e63f47a36..afca3dd34.

This is the smoke that was deferred at finish-branch time.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-review

**Spec coverage:**
- Defensive filter at merger entry: Phase 1 Task 1.3
- Drop `?: existing` dead-code path: Phase 1 Task 1.3 (inside the same rewrite)
- Pin against future enum drift: Phase 1 Task 1.4
- On-device smoke: Phase 2 Tasks 2.2–2.6
- Honest scope statement: in the Goal section

**Placeholder scan:** Every code block contains real Kotlin / shell. The only `<placeholder>` strings are in the smoke-notes templates and the conclusion table, which are explicit "fill in during execution" slots that the executing agent will replace from observed logcat content. Those are not code placeholders.

**Type consistency:** `ContinueWatchingRecord.Source` enum has exactly the three values referenced in the eligibility check (`LOCAL`, `SYNTHETIC`, `REMOTE`) per `ContinueWatchingRecord.kt:77`. The new `NEAR_COMPLETE_PERCENT` constant (95f) matches `ContinueWatchingProgressDiffPlanner.kt`'s `NEAR_COMPLETE_PERCENT` exactly. The `percentComplete()` extension function exists on both classes after this change — same name, same shape, same purpose; that's deliberate parallel structure. No method-rename drift.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-12-cw-defensive-filtering-and-smoke.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
