---
phase: 08-exact-continue-watching-air-timing
reviewed: 2026-04-15T12:11:10Z
depth: standard
files_reviewed: 26
files_reviewed_list:
  - app/src/main/AndroidManifest.xml
  - app/src/main/java/com/nexio/tv/core/di/ContinueWatchingSchedulerModule.kt
  - app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt
  - app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmReceiver.kt
  - app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmScheduler.kt
  - app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirScheduler.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailability.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculator.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt
  - app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt
  - app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt
  - app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt
  - app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingTimeline.kt
  - app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt
  - app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt
  - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt
  - app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogServiceContinueWatchingTest.kt
  - app/src/test/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmSchedulerTest.kt
  - app/src/test/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculatorTest.kt
  - app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt
  - app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt
  - app/src/test/java/com/nexio/tv/data/repository/AirDateGateTest.kt
  - app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt
  - app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingTimelineAirDateTest.kt
  - app/src/test/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricherTest.kt
findings:
  critical: 0
  warning: 5
  info: 0
  total: 5
status: issues_found
---

# Phase 8: Code Review Report

**Reviewed:** 2026-04-15T12:11:10Z
**Depth:** standard
**Files Reviewed:** 26
**Status:** issues_found

## Summary

Reviewed the exact continue-watching air timing changes across TVDB timing calculation, snapshot persistence, UI/feed gating, and Android alarm scheduling. The core gating direction is sound, but several edge cases can leave withheld rows stuck, cause duplicate provider refreshes, or emit misleading diagnostics. No critical security issues were found.

## Warnings

### WR-01: Overdue Persisted Reemit Rows Are Cancelled Instead Of Refreshed

**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:167`
**Issue:** `rescheduleAirTimeAlarmFromSnapshot()` only calls `scheduleReemitIfNeeded()`. That helper derives a target through `AirDateGate.soonestPendingMs()`, which filters to times strictly greater than `nowMs`. If the device was off, asleep, or the app process was dead when the exact air time passed, boot/permission reschedule sees no future target, cancels the alarm, and never calls `ensureFresh(force = true)`. The row can remain in `scheduledReemit` and hidden until some unrelated observer refresh happens.
**Fix:** Detect due scheduled rows during restore/boot reschedule and force a refresh immediately; only schedule an alarm when the next target is still in the future. Add a unit test where `scheduledReemit` has a target <= `nowMs` and `rescheduleAirTimeAlarmFromSnapshot()` calls `refreshNow()`.

```kotlin
fun rescheduleAirTimeAlarmFromSnapshot() {
    val snapshot = rawSnapshotState.value
    val nowMs = System.currentTimeMillis()

    if (snapshot.scheduledReemit.any { entry -> AirDateGate.isDue(entry, nowMs) }) {
        scope.launch { ensureFresh(force = true) }
        return
    }

    scheduleReemitIfNeeded(snapshot.scheduledReemit, nowMs)
}
```

### WR-02: AlarmManager And In-Process Delay Can Both Force The Same Refresh

**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:644`
**Issue:** `scheduleReemitIfNeeded()` schedules the Android alarm and also starts an in-process `delay()` job for the same `soonestMs`. When the app process is alive, both the coroutine and `ContinueWatchingAirAlarmReceiver` can fire and call `ensureFresh(force = true)`. The mutex serializes the calls, but `force = true` bypasses throttling, so the provider refresh can run twice at the same air-time boundary.
**Fix:** Use one trigger path or add a target-level coalescing method shared by the receiver and in-process fallback. For example, pass the target time in the alarm intent and skip if the same target was already handled within the current boundary.

```kotlin
private var handledAirRefreshTargetMs: Long? = null

suspend fun handleAirTimeTrigger(targetMs: Long) {
    refreshMutex.withLock {
        if (handledAirRefreshTargetMs == targetMs) return
        handledAirRefreshTargetMs = targetMs
    }
    ensureFresh(force = true)
}
```

### WR-03: Main And Synthetic Next-Up Flows Enrich The Same Provider Rows Separately

**File:** `app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt:114`
**Issue:** `observeContinueWatchingNextUp()` and `observeSyntheticContinueWatchingNextUp()` each call `tvdbContinueWatchingTimingEnricher.enrich(...)`. The Trakt main and synthetic streams can overlap, and each item enrichment calls both `fetchEnrichment()` and `fetchEpisodeEnrichment()`. A transient provider failure can therefore enrich one rail but not the other, producing inconsistent exact-time gating for the same episode, while also doubling provider work.
**Fix:** Coalesce raw main and synthetic next-up entries, enrich the distinct set once by `(contentId, season, episode)`, then split the enriched results back into the two exposed flows. Add a test that the same overlapping Trakt entry triggers one router series lookup and one episode lookup.

```kotlin
private suspend fun enrichBothRails(
    main: List<TrackingNextUpEntry>,
    synthetic: List<TrackingNextUpEntry>
): Pair<List<TrackingNextUpEntry>, List<TrackingNextUpEntry>> {
    val enrichedByKey = tvdbContinueWatchingTimingEnricher
        .enrich((main + synthetic).distinctBy { "${it.contentId}|${it.season}|${it.episode}" })
        .associateBy { "${it.contentId}|${it.season}|${it.episode}" }

    fun TrackingNextUpEntry.key() = "$contentId|$season|$episode"
    return main.map { enrichedByKey[it.key()] ?: it } to
        synthetic.map { enrichedByKey[it.key()] ?: it }
}
```

### WR-04: Timing Enrichment Failure Logs A False Diagnostic Reason And Raw Content ID

**File:** `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt:60`
**Issue:** The `onFailure` branch logs `reason=missing_timezone_policy` for every exception, even when the failure is an auth, network, identity, or parsing error. It also logs `contentId` directly. That makes exact-air diagnostics misleading and unnecessarily exposes watched content identifiers in logs.
**Fix:** Use the existing `REFRESH_FAILURE` diagnostic for exceptions, avoid raw IDs in reason-level logs, and persist the diagnostic on the returned entry so downstream snapshot persistence reflects the failure.

```kotlin
}.onFailure { error ->
    Log.w(
        TAG,
        "exact_air_time_diagnostic reason=${TvdbAirAvailabilityDiagnosticReason.REFRESH_FAILURE.code} " +
            "failure=${error.javaClass.simpleName}"
    )
}.getOrElse {
    entry.copy(
        tvdbAvailabilityDiagnosticReason = TvdbAirAvailabilityDiagnosticReason.REFRESH_FAILURE
    )
}
```

### WR-05: Previous Snapshot Schema Is Rejected Even Though New Fields Are Optional

**File:** `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt:115`
**Issue:** The store bumped from schema 4 to 5 to add `scheduledReemit` and TVDB timing fields, but `decode()` rejects any version that is not exactly 5 before it reaches the backward-compatible field defaults. Schema 4 snapshots already contain the main resume/next-up fields and can safely decode with `scheduledReemit = emptyList()` and unknown TVDB timing fields. Rejecting them clears a usable continue-watching cache on upgrade.
**Fix:** Accept the previous compatible schema range and keep rejecting older language-unaware payloads. Add a test that a schema 4 payload with `languageTag`, `resumeItems`, `nextUpItems`, and `traktUpNextItems` decodes successfully with empty `scheduledReemit`.

```kotlin
val schemaVersion = root.get("schemaVersion")?.asInt ?: 0
if (schemaVersion !in 4..SCHEMA_VERSION) {
    return null
}
```

---

_Reviewed: 2026-04-15T12:11:10Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
