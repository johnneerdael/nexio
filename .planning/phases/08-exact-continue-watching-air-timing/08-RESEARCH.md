# Phase 08: Exact Continue Watching Air Timing - Research

**Researched:** 2026-04-14 [VERIFIED: system date]
**Domain:** Kotlin Android TV air-time calculation, Continue Watching gating, durable Android scheduling [VERIFIED: `.planning/phases/08-exact-continue-watching-air-timing/08-CONTEXT.md`; VERIFIED: `CLAUDE.md`; VERIFIED: codebase inspection]
**Confidence:** HIGH for current Continue Watching architecture and TVDB policy source; MEDIUM for exact alarm permission/product posture and non-US multi-zone country mapping because those require policy choices beyond the current codebase. [VERIFIED: codebase inspection; CITED: https://developer.android.com/develop/background-work/services/alarms; CITED: https://support.thetvdb.com/kb/faq.php?id=29]

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
## Implementation Decisions

### Source Timezone Policy
- **D-01:** Treat TVDB network-show air times as source-market Eastern time for US/network TV, then convert the resulting instant to the Android TV device timezone for Continue Watching visibility.
- **D-02:** Use a single Eastern instant for US network shows; do not attempt an Eastern/Pacific split based on viewer location.
- **D-03:** Accept common `airsTime` formats such as `20:00`, `8:00 PM`, and `8pm`; invalid values fall back with diagnostics.
- **D-04:** When a source timezone cannot be determined, the agent may decide the exact fallback during planning, constrained by the rule that fake precision must be avoided.

### Missing or Partial Air-Time Metadata
- **D-05:** When TVDB has an episode aired date but lacks a direct `airsTime`, apply TVDB's published timing policy where it provides a reliable default: US series use Eastern premiere time, non-US series use the show's country capital or most populous-city timezone, and listed streaming services use their documented default release times.
- **D-06:** Convert the source instant to the Android TV device timezone and allow the local availability date to move forward or backward. Gate by the computed instant, not by the device-local calendar date, so episodes do not appear a day early depending on viewer location.
- **D-07:** If no reliable TVDB/network/platform default can be inferred, fall back to existing date-only gating and expose diagnostics explaining that precise timing was unavailable.
- **D-08:** If TVDB `airsTime` exists but is invalid or unparsable, use date-only fallback with diagnostics.
- **D-09:** Streaming/platform originals use the same Eastern-source policy unless Phase 7 metadata includes a reliable better source timezone.
- **D-10:** TVDB exact timing wins over Trakt/Simkl first-aired data for Continue Watching visibility whenever TVDB exact timing is available.

### Gating Surface Coverage
- **D-11:** Exact TVDB air-time gating applies to all Continue Watching next-up surfaces: the main in-app rail, Trakt up-next rail, and Android TV recommendations/feed.
- **D-12:** Already-started in-progress/resume items remain visible. Gating applies to future next-up rows, not resume rows.
- **D-13:** TV detail episode lists can show future episodes, but play actions should remain blocked/unaired where applicable.
- **D-14:** Withheld next-up items stay in a scheduled/persisted withheld-entry model so they can appear at the exact computed instant.

### Re-evaluation Reliability
- **D-15:** Scheduled re-evaluation must survive app process death, device sleep, and TV reboot using durable Android scheduling in addition to the current in-memory timer.
- **D-16:** When the scheduled instant arrives, refresh tracking provider next-up and rebuild Continue Watching rather than blindly showing stale withheld rows.
- **D-17:** If multiple future episodes are withheld, schedule only the soonest withheld instant, then recompute after it fires.
- **D-18:** If refresh fails at the airing instant, keep the withheld entry and retry with backoff.

### Diagnostics
- **D-19:** Exact air-time diagnostics live in debug/settings diagnostics and logs only, not on normal Continue Watching cards.
- **D-20:** Diagnostics capture failure reasons only: missing `airsTime`, invalid time, missing timezone/source policy, and refresh failure.
- **D-21:** Diagnostics/logs may expose the computed device-local availability time to make timezone bugs debuggable.
- **D-22:** Continue Watching should not show placeholders for gated future rows. TV detail may show an availability placeholder if useful, but the Continue Watching row remains absent until available.

### the agent's Discretion
- Exact fallback when a source timezone cannot be determined but TVDB has date plus time, provided the implementation avoids fake precision and emits diagnostics.
- Internal representation of TVDB source timezone policy and platform default-time lookup.
- Exact durable scheduler implementation, retry policy parameters, and diagnostic payload shape.

### Claude's Discretion
See "the agent's Discretion" above, copied verbatim from CONTEXT.md. [VERIFIED: `.planning/phases/08-exact-continue-watching-air-timing/08-CONTEXT.md`]

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AIR-01 | Continue Watching computes a precise TV episode availability instant from TVDB episode aired date plus series `airsTime` when both fields are available. | Add a pure Kotlin `TvdbAirAvailabilityCalculator` using `LocalDate`, parsed `LocalTime`, source `ZoneId`, and `Instant`; prefer exact TVDB timing over `firstAiredMs`. [VERIFIED: `.planning/REQUIREMENTS.md`; VERIFIED: `app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt`; CITED: `tvdb.yml:3871`; CITED: https://support.thetvdb.com/kb/faq.php?id=29] |
| AIR-02 | TVDB availability instants are converted to the Android TV device's local timezone before Continue Watching visibility decisions. | Compare epoch instants for gating and expose `ZoneId.systemDefault()` converted diagnostics for humans; epoch comparison avoids local-date drift bugs. [VERIFIED: `.planning/REQUIREMENTS.md`; VERIFIED: `HomeViewModelContinueWatching.kt` uses `ZoneId.systemDefault()` for existing display parsing] |
| AIR-03 | Future TV episodes are withheld from Continue Watching until the computed device-local TVDB availability instant. | Extend `TrackingNextUpEntry` or a companion timing model with `availabilityInstantMs`, then update `AirDateGate.isAired` and timeline filtering to use it before date-only fallback. [VERIFIED: `TrackingProgressService.kt`; VERIFIED: `ContinueWatchingSnapshotService.kt`; VERIFIED: `ContinueWatchingTimeline.kt`] |
| AIR-04 | Withheld future TVDB next-up entries schedule re-evaluation at the computed availability instant. | Preserve the existing soonest-target behavior, persist withheld rows, and add an Android `AlarmManager`-backed scheduler plus boot reschedule path. [VERIFIED: `ContinueWatchingSnapshotService.kt`; CITED: https://developer.android.com/develop/background-work/services/alarms] |
| AIR-05 | Date-only TVDB metadata falls back to existing date-only gating and exposes diagnostics explaining precise timing was unavailable. | Keep `AirDateGate` date-only parsing semantics as the fallback and record reason codes `missing_airs_time`, `invalid_time`, and `missing_timezone_policy`. [VERIFIED: `AirDateGate.kt`; VERIFIED: `AirDateGateTest.kt`; VERIFIED: `.planning/phases/08-exact-continue-watching-air-timing/08-CONTEXT.md`] |
| AIR-06 | TV detail screens can continue showing future unaired episodes while Continue Watching remains exact-air-time gated. | Keep exact gating in Continue Watching snapshot/timeline surfaces; do not filter `Meta.videos` or detail season lists during this phase. [VERIFIED: `Meta.kt`; VERIFIED: `MetaDetailsViewModel.kt`; VERIFIED: `.planning/phases/08-exact-continue-watching-air-timing/08-CONTEXT.md`] |
</phase_requirements>

## Project Constraints (from CLAUDE.md and user AGENTS instructions)

- Nexio is an Android TV / Fire TV Kotlin app using Jetpack Compose, package `com.nexio.tv`. [VERIFIED: `CLAUDE.md`; VERIFIED: `app/build.gradle.kts`]
- Prefer small targeted changes, preserve existing architecture/naming patterns, fix root causes, and keep the phase scoped. [VERIFIED: `CLAUDE.md`]
- Keep domain code free of Android framework dependencies; place Android scheduling behind an app/data-layer wrapper, not in pure availability calculation code. [VERIFIED: `CLAUDE.md`; VERIFIED: current package layout]
- Do not introduce new libraries unless clearly justified by existing codebase needs. [VERIFIED: `CLAUDE.md`]
- Use `./gradlew assembleArm64Debug`, `./gradlew testArm64DebugUnitTest`, targeted `--tests`, and `./gradlew lintArm64Debug` for local verification. [VERIFIED: `CLAUDE.md`]
- Do not bump plugin release versions or create root `CHANGELOG.md` release entries; this phase is app work and should avoid plugin release metadata. [VERIFIED: user-provided AGENTS instructions]
- `.claude/skills/` and `.agents/skills/` are absent in this checkout, so no project-local skill patterns were loaded. [VERIFIED: directory existence checks]

## Summary

Phase 8 should extend the existing Continue Watching gate rather than adding a parallel visibility system. `AirDateGate` already owns aired/not-aired checks, `ContinueWatchingSnapshotService` already separates withheld future next-up rows into `scheduledReemit`, and Android TV feed rows already consume the same `ContinueWatchingSnapshotService` snapshot. [VERIFIED: `AirDateGate.kt`; VERIFIED: `ContinueWatchingSnapshotService.kt`; VERIFIED: `AndroidTvFeedCatalogService.kt`]

The key implementation gap is metadata fidelity: `TrackingNextUpEntry` currently carries only `firstAired` and `firstAiredMs`, and `ContinueWatchingSnapshotStore` persists visible next-up rows but does not serialize `scheduledReemit`. Phase 8 needs a TVDB timing result that can preserve exact instant, source timezone policy, fallback reason, and diagnostics through Trakt/Simkl routing, snapshot persistence, the in-app rail, Trakt up-next rail, and Android TV channels. [VERIFIED: `TrackingProgressService.kt`; VERIFIED: `ContinueWatchingSnapshotStore.kt`; VERIFIED: `.planning/phases/08-exact-continue-watching-air-timing/08-CONTEXT.md`]

**Primary recommendation:** Add a pure Kotlin `TvdbAirAvailabilityCalculator`, extend the shared next-up model with exact availability metadata, keep `AirDateGate` as the single gate, persist `scheduledReemit`, and use an `AlarmManager`-based one-shot scheduler plus boot/permission reschedule receivers to call `ensureFresh(force = true)` at the soonest withheld instant. [VERIFIED: current codebase; CITED: https://developer.android.com/develop/background-work/services/alarms]

## Standard Stack

### Core

| Component | Version | Purpose | Why Standard |
|-----------|---------|---------|--------------|
| Kotlin / Android Gradle Plugin | Kotlin `2.3.0`, AGP `8.13.2` | Phase implementation and tests | Already configured for the app; no new runtime is needed. [VERIFIED: `gradle/libs.versions.toml`; VERIFIED: `app/build.gradle.kts`] |
| Android platform `AlarmManager` + manifest `BroadcastReceiver` | minSdk `26`, targetSdk `36` | Durable one-shot re-evaluation outside app process lifetime | Android alarms run outside app lifetime and can trigger even when the app is not running or the device is asleep. [VERIFIED: `app/build.gradle.kts`; CITED: https://developer.android.com/develop/background-work/services/alarms] |
| `java.time` (`LocalDate`, `LocalTime`, `ZoneId`, `ZonedDateTime`, `Instant`, `Clock`) | JDK/Android runtime APIs, minSdk `26` | Time parsing, source-zone conversion, deterministic tests | The codebase already uses `java.time`, and minSdk 26 supports it without adding a dependency. [VERIFIED: `app/build.gradle.kts`; VERIFIED: codebase `java.time` usage] |
| Hilt | `2.58` | Inject scheduler, calculator, diagnostics, and Phase 7 TVDB metadata services | Current app services and repositories use Hilt singletons. [VERIFIED: `gradle/libs.versions.toml`; VERIFIED: `ContinueWatchingSnapshotService.kt`] |
| Gson-backed `ContinueWatchingSnapshotStore` | Gson `2.10.1` | Persist visible and withheld Continue Watching snapshot data | Snapshot store already uses explicit schema versioning and Gson object encoding. [VERIFIED: `gradle/libs.versions.toml`; VERIFIED: `ContinueWatchingSnapshotStore.kt`] |
| AndroidX TV Provider | `1.1.0` | Android TV Continue Watching recommendation/feed publication | Existing channel publisher uses `PreviewChannelHelper`, `PreviewProgram`, and `TvContractCompat`. [VERIFIED: `gradle/libs.versions.toml`; VERIFIED: `AndroidTvChannelPublisher.kt`] |

### Supporting

| Component | Version | Purpose | When to Use |
|-----------|---------|---------|-------------|
| JUnit 4 | `4.13.2` | Unit test runner | Use for pure timing, gate, serializer, and policy tests. [VERIFIED: `app/build.gradle.kts`] |
| kotlinx-coroutines-test | `1.8.1` | Scheduler/retry and snapshot timing tests | Use for in-memory timer/backoff tests around `ContinueWatchingSnapshotService`. [VERIFIED: `app/build.gradle.kts`; VERIFIED: `ContinueWatchingTimelineAirDateTest.kt`] |
| MockK | `1.13.12` | Mock scheduler, tracking provider, and metadata services | Use for `ensureFresh(force = true)` and failure/retry assertions. [VERIFIED: `app/build.gradle.kts`] |
| Robolectric | `4.13` | Android framework tests for `AlarmManager` wrapper and receiver wiring | Use only where `Context`, `Intent`, or manifest receiver behavior is required. [VERIFIED: `app/build.gradle.kts`] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `AlarmManager` | WorkManager | WorkManager is appropriate for scheduled background work but is not the precise-at-instant primitive; Android docs direct exact timing to alarms and periodic WorkManager has coarse timing. [CITED: https://developer.android.com/develop/background-work/services/alarms] |
| `java.time` region zones | Fixed numeric offsets such as UTC-05:00 | Fixed offsets would miss daylight-saving changes and other tzdb updates; use IANA `ZoneId` values for source policies. [CITED: https://developer.android.com/reference/android/icu/util/TimeZone; VERIFIED: codebase `java.time` usage] |
| Extending `AirDateGate` | Add UI-level filters in Home/Android TV publisher | UI-level filters would miss other Continue Watching consumers and duplicate logic; the current gate is already upstream of UI mapping. [VERIFIED: `ContinueWatchingSnapshotService.kt`; VERIFIED: `AndroidTvFeedCatalogService.kt`] |
| Persist withheld rows in a new store | Extend `ContinueWatchingSnapshotStore` schema | The snapshot store already owns Continue Watching persistence and schema versions; splitting stores raises consistency risk. [VERIFIED: `ContinueWatchingSnapshotStore.kt`] |

**Installation:** No new dependency is recommended for Phase 8. [VERIFIED: `CLAUDE.md`; VERIFIED: `app/build.gradle.kts`; VERIFIED: `gradle/libs.versions.toml`]

**Version verification:** This is a Gradle Android project, not an npm project; versions were verified from `gradle/libs.versions.toml`, `app/build.gradle.kts`, and `./gradlew --version`. [VERIFIED: local Gradle/version commands]

## Architecture Patterns

### Recommended Project Structure

```text
app/src/main/java/com/nexio/tv/core/tvdb/
  TvdbAirAvailabilityCalculator.kt       # pure Kotlin date + time + source-zone -> Instant
  TvdbAirTimePolicy.kt                   # TVDB source timezone/default time rules
  TvdbAirTimeDiagnostics.kt              # reason codes and computed-time payloads

app/src/main/java/com/nexio/tv/data/repository/
  AirDateGate.kt                         # central gate consumes exact availability first
  TrackingProgressService.kt             # shared next-up DTO carries timing metadata
  ContinueWatchingSnapshotService.kt     # builds withheld rows, schedules soonest, retries

app/src/main/java/com/nexio/tv/data/local/
  ContinueWatchingSnapshotStore.kt       # schema bump: persist scheduledReemit + timing fields

app/src/main/java/com/nexio/tv/core/scheduler/
  ContinueWatchingAirAlarmScheduler.kt   # Android AlarmManager wrapper
  ContinueWatchingAirAlarmReceiver.kt    # alarm + boot + exact-alarm permission reschedule entry
```

This structure keeps source-time calculation Android-free while isolating Android scheduling in an app-layer component. [VERIFIED: `CLAUDE.md`; VERIFIED: current package layout]

### Pattern 1: Exact Availability Result, Not Boolean-Only Gate

**What:** Compute and store a result object with the availability instant, source policy, source zone, device-local display timestamp, precision, and optional diagnostic reason. [VERIFIED: phase decisions; VERIFIED: current `AirDateGate` is boolean-only]

**When to use:** Use for every TVDB-backed next-up entry that has an episode aired date and enough series/network/platform metadata to infer a reliable source policy. [VERIFIED: `.planning/phases/08-exact-continue-watching-air-timing/08-CONTEXT.md`; CITED: `tvdb.yml:3871`]

**Example:**

```kotlin
// Source: java.time APIs already used in the app; TVDB policy from FAQ/context. [VERIFIED: codebase; CITED: https://support.thetvdb.com/kb/faq.php?id=29]
data class TvdbAvailability(
    val instantMs: Long?,
    val precision: Precision,
    val sourceZoneId: String?,
    val sourcePolicy: String?,
    val diagnosticReason: Reason?
)

enum class Precision { EXACT_INSTANT, DATE_ONLY, UNKNOWN }
enum class Reason { MISSING_AIRS_TIME, INVALID_TIME, MISSING_TIMEZONE_POLICY, REFRESH_FAILURE }

fun computeAvailability(
    episodeAiredDate: String?,
    airsTime: String?,
    sourceZone: ZoneId?,
    clock: Clock
): TvdbAvailability {
    val date = episodeAiredDate?.trim()?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return TvdbAvailability(null, Precision.UNKNOWN, null, null, Reason.MISSING_TIMEZONE_POLICY)

    val time = parseTvdbAirsTime(airsTime)
        ?: return TvdbAvailability(null, Precision.DATE_ONLY, sourceZone?.id, null, Reason.INVALID_TIME)

    val zone = sourceZone ?: return TvdbAvailability(null, Precision.DATE_ONLY, null, null, Reason.MISSING_TIMEZONE_POLICY)
    val instant = ZonedDateTime.of(date, time, zone).toInstant()
    return TvdbAvailability(instant.toEpochMilli(), Precision.EXACT_INSTANT, zone.id, "tvdb_policy", null)
}
```

### Pattern 2: Gate By Epoch Instant, Diagnose In Device Time

**What:** Compare `availabilityInstantMs <= nowMs` for visibility, and format a device-local diagnostic timestamp only for logs/debug settings. [VERIFIED: `AirDateGate.kt`; VERIFIED: phase decisions]

**When to use:** Use this for the main in-app rail, Trakt up-next synthetic rail, and Android TV feed because all consume `ContinueWatchingSnapshot`. [VERIFIED: `ContinueWatchingSnapshotService.kt`; VERIFIED: `AndroidTvFeedCatalogService.kt`]

**Example:**

```kotlin
// Source: existing AirDateGate priority pattern. [VERIFIED: AirDateGate.kt]
fun isAired(
    availabilityInstantMs: Long?,
    firstAiredMs: Long,
    dateOnlyAirDate: String?,
    nowMs: Long
): Boolean {
    if (availabilityInstantMs != null && availabilityInstantMs > 0L) {
        return availabilityInstantMs <= nowMs
    }
    return isAired(firstAiredMs = firstAiredMs, tmdbAirDate = dateOnlyAirDate, nowMs = nowMs)
}
```

### Pattern 3: Durable Soonest-Only Alarm Mirrors In-Memory Timer

**What:** Keep the existing `currentTimerTargetMs` idempotency guard, but make it drive both the coroutine timer and an `AlarmManager` one-shot. [VERIFIED: `ContinueWatchingSnapshotService.kt`; VERIFIED: `ContinueWatchingTimelineAirDateTest.kt`]

**When to use:** Re-run scheduling after every snapshot write, every alarm fire, boot completed, and exact-alarm permission change. [CITED: https://developer.android.com/develop/background-work/services/alarms; CITED: https://developer.android.com/reference/android/app/AlarmManager.html]

**Example:**

```kotlin
// Source: Android alarm guidance + existing scheduleReemitIfNeeded shape. [CITED: Android alarms docs; VERIFIED: ContinueWatchingSnapshotService.kt]
interface ContinueWatchingAirScheduler {
    fun scheduleSoonest(triggerAtMs: Long?)
    fun cancel()
}

class ContinueWatchingAirAlarmScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager,
    private val diagnostics: TvdbAirTimeDiagnostics
) : ContinueWatchingAirScheduler {
    override fun scheduleSoonest(triggerAtMs: Long?) {
        if (triggerAtMs == null) {
            alarmManager.cancel(pendingIntent())
            return
        }
        val operation = pendingIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            diagnostics.recordMissingExactAlarmPermission(triggerAtMs)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
            return
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
    }
}
```

### Pattern 4: Refresh, Do Not Blindly Reveal

**What:** On alarm fire, call `ContinueWatchingSnapshotService.ensureFresh(force = true)` and rebuild from tracking provider state; keep withheld rows if refresh fails and schedule retry. [VERIFIED: phase decisions; VERIFIED: `ContinueWatchingSnapshotService.ensureFresh`]

**When to use:** Use for both in-memory timer and durable alarm receiver so behavior is identical whether the app is alive or relaunched. [VERIFIED: phase decisions; VERIFIED: existing service API]

### Anti-Patterns to Avoid

- **Do not parse `airsTime` with ad hoc substring math:** Use `java.time.LocalTime` with explicit accepted patterns and normalized AM/PM input. [VERIFIED: phase decisions; VERIFIED: `java.time` is already used]
- **Do not treat `firstAiredMs` from Trakt/Simkl as authoritative when TVDB exact timing exists:** Phase decisions explicitly give TVDB exact timing priority. [VERIFIED: `.planning/phases/08-exact-continue-watching-air-timing/08-CONTEXT.md`]
- **Do not serialize only visible rows:** Withheld rows must survive process death and reboot rescheduling, so `scheduledReemit` and timing metadata need schema-versioned persistence. [VERIFIED: current `ContinueWatchingSnapshotStore.kt`; VERIFIED: phase decisions]
- **Do not request a visible user alarm clock for Continue Watching:** Android docs position `setAlarmClock()` for highly visible user-facing alarms; Continue Watching refresh is background metadata work. [CITED: https://developer.android.com/develop/background-work/services/alarms]
- **Do not filter detail screen episode lists through the Continue Watching gate:** AIR-06 requires TV detail to keep showing future unaired episodes. [VERIFIED: `.planning/REQUIREMENTS.md`; VERIFIED: `MetaDetailsViewModel.kt`]

## TVDB Timing Policy Findings

| Policy Area | Finding | Confidence |
|-------------|---------|------------|
| US series source time | TVDB FAQ says series-level air times for US series use US Eastern premiere time; the phase context locks this to source-market Eastern and a single Eastern instant. [CITED: https://support.thetvdb.com/kb/faq.php?id=29; VERIFIED: `08-CONTEXT.md`] | HIGH |
| Non-US series source time | TVDB FAQ says non-US series use the show's country capital or most populous-city time; `tvdb.yml` repeats this policy in `SeriesBaseRecord` and `SeriesExtendedRecord`. [CITED: https://support.thetvdb.com/kb/faq.php?id=29; CITED: `tvdb.yml:3806`; CITED: `tvdb.yml:3871`] | HIGH for policy, MEDIUM for implementation mapping |
| Streaming defaults | TVDB FAQ lists streaming service defaults: Netflix, Disney+, HBO Max, Paramount+, AMC+, ALLBLK, BET+, and Peacock at 3 a.m.; Hulu and Apple TV+ at 12 a.m.; Amazon Prime Video at 12 a.m. GMT on release day. [CITED: https://support.thetvdb.com/kb/faq.php?id=29] | HIGH |
| TVDB fields available | `SeriesExtendedRecord` contains `airsTime`, `airsDays`, `country`, `originalCountry`, `originalNetwork`, `latestNetwork`, and `companies`; `EpisodeBaseRecord` contains `aired`, `seasonNumber`, `number`, and runtime/image/title fields. [CITED: `tvdb.yml:3871`; CITED: `tvdb.yml:2821`] | HIGH |
| DST interpretation | Use `ZoneId.of("America/New_York")` for US Eastern rather than a fixed UTC-05:00 offset because the phase says source-market Eastern time and Android/JDK zone rules handle daylight saving for actual instants. [VERIFIED: `08-CONTEXT.md`; ASSUMED] | MEDIUM |

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Timezone offsets and daylight saving | Hard-coded offsets like `-05:00` or homemade DST rules | IANA `ZoneId` values and Android/JDK tzdb | Timezone laws change, and Android exposes system timezone IDs/versioned timezone data. [CITED: https://developer.android.com/reference/android/icu/util/TimeZone] |
| Durable background wakeup | Coroutine `delay()` only | Existing coroutine timer plus `AlarmManager` one-shot | Coroutine timers die with the process; Android alarms operate outside app lifetime. [VERIFIED: `ContinueWatchingSnapshotService.kt`; CITED: https://developer.android.com/develop/background-work/services/alarms] |
| Multiple withheld triggers | One alarm per episode | Single soonest withheld instant, recompute after refresh | The phase locks soonest-only scheduling and existing tests already cover timer churn prevention. [VERIFIED: `08-CONTEXT.md`; VERIFIED: `ContinueWatchingTimelineAirDateTest.kt`] |
| TVDB policy spread across UI files | Inline time parsing in Home, detail, and recommendations | Shared `TvdbAirAvailabilityCalculator` + `AirDateGate` | Current architecture centralizes gate decisions before UI mapping. [VERIFIED: `AirDateGate.kt`; VERIFIED: `ContinueWatchingSnapshotService.kt`] |
| Snapshot serialization | Append raw Gson data without schema bump | Bump `ContinueWatchingSnapshotStore.SCHEMA_VERSION` and encode/decode new fields explicitly | Current store rejects mismatched schema versions and uses explicit object encoding for next-up entries. [VERIFIED: `ContinueWatchingSnapshotStore.kt`] |

**Key insight:** The hard part is not comparing times; it is preserving the provenance of precision. The planner should require each next-up row to say whether visibility is exact, date-only, or unknown so Nexio avoids fake precision while still scheduling exact rows. [VERIFIED: phase decisions; VERIFIED: current code inspection]

## Common Pitfalls

### Pitfall 1: Treating TVDB `aired` as Device-Local Midnight

**What goes wrong:** Episodes appear at the start of the release date in the viewer's timezone instead of the source airing instant. [VERIFIED: phase goal; VERIFIED: current `AirDateGate.parseDateToEpochMs`]

**Why it happens:** Current date-only fallback parses an ISO date to midnight UTC, which was acceptable for day-level gating but is not exact-air-time behavior. [VERIFIED: `AirDateGate.kt`]

**How to avoid:** Only use date-only fallback when exact TVDB timing cannot be computed; otherwise combine TVDB `aired` date, parsed `airsTime`, and source `ZoneId` into an `Instant`. [VERIFIED: phase decisions; CITED: TVDB FAQ]

**Warning signs:** Tests pass in UTC but fail for Europe/Amsterdam, Pacific, or Asia timezones; diagnostic `sourceZoneId` is missing for exact rows. [ASSUMED]

### Pitfall 2: Losing Withheld Rows Across App Restart

**What goes wrong:** A future row is withheld correctly while the app is open but cannot appear at the exact instant after process death or reboot. [VERIFIED: current store omission; VERIFIED: phase decisions]

**Why it happens:** `ContinueWatchingSnapshot.scheduledReemit` exists in memory, but `ContinueWatchingSnapshotStore.write` does not encode it and `decode` does not restore it. [VERIFIED: `ContinueWatchingSnapshotService.kt`; VERIFIED: `ContinueWatchingSnapshotStore.kt`]

**How to avoid:** Persist `scheduledReemit` and exact timing fields in a schema bump, reschedule the soonest persisted row on app init and boot, and keep the old snapshot if the alarm refresh fails. [VERIFIED: phase decisions; VERIFIED: store schema pattern]

**Warning signs:** Store tests only assert visible `nextUpItems`, or alarm receiver tests pass with an in-memory service instance only. [VERIFIED: `ContinueWatchingSnapshotStoreTest.kt`; ASSUMED]

### Pitfall 3: Exact Alarm Permission Blindness

**What goes wrong:** The app calls exact alarm APIs on target SDK 36 devices without permission state handling, causing failures or canceled future alarms. [VERIFIED: `app/build.gradle.kts`; CITED: Android AlarmManager docs]

**Why it happens:** Android 12+ exact alarms require declaring an alarms/reminders permission or using APIs that do not require exact permission in limited cases, and the permission can be revoked. [CITED: https://developer.android.com/develop/background-work/services/alarms; CITED: https://developer.android.com/reference/android/app/AlarmManager.html]

**How to avoid:** The planner must decide whether to request `SCHEDULE_EXACT_ALARM`; if not granted, use `setAndAllowWhileIdle` as a no-earlier-than fallback and record diagnostics. [CITED: Android alarms docs; ASSUMED]

**Warning signs:** No manifest receiver for `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, no `canScheduleExactAlarms()` branch, or no diagnostic when falling back to inexact scheduling. [CITED: Android alarms docs; ASSUMED]

### Pitfall 4: Filtering Detail Episodes

**What goes wrong:** Future episodes disappear from TV detail pages because the exact Continue Watching gate is reused too broadly. [VERIFIED: AIR-06; VERIFIED: phase decisions]

**Why it happens:** `Meta.videos` and detail season lists use the same episode dates that Continue Watching needs, but the product surfaces have different visibility contracts. [VERIFIED: `Meta.kt`; VERIFIED: `MetaDetailsViewModel.kt`; VERIFIED: `.planning/REQUIREMENTS.md`]

**How to avoid:** Apply exact gating only to next-up snapshot/timeline/recommendation rows; detail screens may use diagnostics/placeholder copy but should not use the rail filter. [VERIFIED: phase decisions]

**Warning signs:** Tests for `MetaDetailsViewModel` expecting future episodes are deleted or updated to hide unaired episodes. [ASSUMED]

## Code Examples

### Robust `airsTime` Parser

```kotlin
// Source: Phase accepted formats; java.time pattern style already used in app. [VERIFIED: 08-CONTEXT.md; VERIFIED: codebase java.time usage]
private val airTimeFormatters = listOf(
    DateTimeFormatter.ofPattern("H:mm", Locale.US),
    DateTimeFormatter.ofPattern("h:mm a", Locale.US),
    DateTimeFormatter.ofPattern("ha", Locale.US)
)

fun parseTvdbAirsTime(raw: String?): LocalTime? {
    val normalized = raw
        ?.trim()
        ?.replace(".", "")
        ?.replace(Regex("\\s+"), " ")
        ?.uppercase(Locale.US)
        ?.replace(Regex("^(\\d{1,2})(AM|PM)$"), "$1 $2")
        ?: return null

    return airTimeFormatters.firstNotNullOfOrNull { formatter ->
        runCatching { LocalTime.parse(normalized, formatter) }.getOrNull()
    }
}
```

### TVDB Policy Selection

```kotlin
// Source: TVDB FAQ + phase decisions. [CITED: https://support.thetvdb.com/kb/faq.php?id=29; VERIFIED: 08-CONTEXT.md]
data class TvdbAirSourcePolicy(
    val zoneId: ZoneId,
    val defaultTime: LocalTime?,
    val policyName: String
)

fun resolveTvdbAirSourcePolicy(series: TvdbSeriesTiming): TvdbAirSourcePolicy? {
    val platform = series.platformName?.trim()?.lowercase(Locale.US)

    if (platform == "amazon prime video") {
        return TvdbAirSourcePolicy(ZoneOffset.UTC, LocalTime.MIDNIGHT, "amazon_prime_video_00_00_gmt")
    }

    val easternStreamingDefaults = setOf(
        "netflix", "disney+", "hbo max", "paramount+", "amc+", "allblk", "bet+", "peacock"
    )
    if (platform in easternStreamingDefaults) {
        return TvdbAirSourcePolicy(ZoneId.of("America/New_York"), LocalTime.of(3, 0), "streaming_03_00_eastern")
    }
    if (platform == "hulu" || platform == "apple tv+") {
        return TvdbAirSourcePolicy(ZoneId.of("America/New_York"), LocalTime.MIDNIGHT, "streaming_00_00_eastern")
    }

    if (series.isUsSeries) {
        return TvdbAirSourcePolicy(ZoneId.of("America/New_York"), null, "us_network_eastern")
    }

    return countryPolicyTable[series.originalCountryCode] // null means date-only fallback with diagnostics.
}
```

### Receiver Entry Point

```kotlin
// Source: Android alarm docs recommend BroadcastReceiver integration; service action mirrors existing ensureFresh API. [CITED: Android alarms docs; VERIFIED: ContinueWatchingSnapshotService.kt]
@AndroidEntryPoint
class ContinueWatchingAirAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var snapshotService: ContinueWatchingSnapshotService

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                snapshotService.ensureFresh(force = true)
            } finally {
                pending.finish()
            }
        }
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed / Verified | Impact |
|--------------|------------------|--------------------------|--------|
| Day-level gate from `firstAiredMs` or parsed ISO date | TVDB exact `aired` + `airsTime` + source-zone instant for TVDB-backed rows; date-only fallback only when precision is unavailable | Phase 8 context and TVDB FAQ verified 2026-04-14. [VERIFIED: `08-CONTEXT.md`; CITED: TVDB FAQ] | Avoids episodes appearing at local midnight or on the wrong local day. |
| Coroutine timer only | Coroutine timer plus Android alarm receiver and boot reschedule | Android alarms docs verified 2026-04-14. [CITED: Android alarms docs] | Survives app process death and device sleep; reboot requires persisted state and boot receiver. |
| Implicit missing precision | Explicit precision and diagnostic reason fields | Phase decisions verified 2026-04-14. [VERIFIED: `08-CONTEXT.md`] | Debug/settings diagnostics can explain why exact timing was or was not used. |
| WorkManager as generic scheduler | AlarmManager for exact time, WorkManager/JobScheduler only for longer work if needed from receiver | Android docs verified 2026-04-14. [CITED: Android alarms docs] | Avoids planning a coarse scheduler for an exact timing requirement. |

**Deprecated/outdated:**
- Using `java.util.Calendar` for new exact timing code is outdated relative to the codebase's existing `java.time` usage and the need for deterministic `Clock` tests. [VERIFIED: codebase `java.time` usage; ASSUMED]
- Relying only on `ContinueWatchingSnapshotService.reemitJob` is insufficient for Phase 8 because it is process-lifetime state. [VERIFIED: `ContinueWatchingSnapshotService.kt`; VERIFIED: phase decisions]

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Interpret US "Eastern" as `America/New_York` civil time rather than fixed `UTC-05:00`, despite TVDB FAQ wording "EST". | TVDB Timing Policy Findings | Summer airings could be off by one hour if TVDB intended fixed EST. |
| A2 | Use `setAndAllowWhileIdle` fallback when exact alarm permission is not granted, with diagnostics, rather than blocking Phase 8 on special app access. | Common Pitfalls / Architecture | Rows may appear late on sleeping devices without exact permission. |
| A3 | A project-owned country-to-source-zone policy table is acceptable when Phase 7 metadata does not expose a better network/platform timezone. | Architecture / Open Questions | Non-US multi-zone countries could receive fake precision unless the table is carefully limited and diagnostic fallback is used. |
| A4 | The final Phase 7 TVDB model will expose episode `aired`, series `airsTime`, country/originalCountry, and network/platform metadata to `TrackingNextUpEntry` construction. | Architecture Patterns | Phase 8 tasks may need a preliminary integration task if Phase 7 stores these fields elsewhere. |

## Open Questions

1. **Exact alarm permission posture**
   - What we know: The app targets SDK 36, and Android docs require alarms/reminders permission handling for exact alarm APIs on Android 12+. [VERIFIED: `app/build.gradle.kts`; CITED: Android alarms docs]
   - What's unclear: Whether Nexio should request `SCHEDULE_EXACT_ALARM`, rely on in-memory exact timer plus inexact durable fallback, or treat exact permission absence as a diagnostic-only degraded mode. [ASSUMED]
   - Recommendation: Plan a scheduler abstraction with `canScheduleExactAlarms()` branching and diagnostics; only add a user-facing permission request if product accepts the Play policy/UX cost. [CITED: Android alarms docs; ASSUMED]

2. **Non-US country timezone data source**
   - What we know: TVDB policy requires country capital or most populous-city time for non-US series, and Android ICU can list country-associated timezone IDs for two-letter country codes. [CITED: TVDB FAQ; CITED: Android ICU TimeZone docs]
   - What's unclear: Android does not provide a direct "capital or most populous city timezone" API, and TVDB country fields in `tvdb.yml` are strings whose exact alpha-2/alpha-3 shape must be confirmed from Phase 7 DTO mapping. [CITED: `tvdb.yml`; ASSUMED]
   - Recommendation: Use TVDB/platform/network metadata first; for non-US fallback, create a small audited country policy table that returns null when confidence is low, causing date-only fallback with diagnostics. [ASSUMED]

3. **Phase 7 exact field names**
   - What we know: Phase 7 research recommends provider-neutral TV metadata and TVDB series/episode DTOs, but source code is not yet present in this checkout. [VERIFIED: `07-RESEARCH.md`; VERIFIED: codebase grep]
   - What's unclear: The exact class and field names Phase 8 will extend. [VERIFIED: codebase grep]
   - Recommendation: Plan Wave 0 to inspect Phase 7 implementation before editing, then adapt the timing model names to the implemented TVDB provider contracts. [VERIFIED: `07-CONTEXT.md`; ASSUMED]

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| Java | Gradle build and JVM tests | Yes [VERIFIED: `java -version`] | OpenJDK `17.0.18` | None needed |
| Gradle wrapper | Unit/build verification | Yes [VERIFIED: `./gradlew --version`] | Gradle `8.13` | None needed |
| Android SDK / adb | Manual Android TV/device verification and receiver smoke checks | Yes [VERIFIED: `command -v adb`] | Installed at `/Users/jneerdael/Library/Android/sdk/platform-tools/adb` | JVM/Robolectric tests cover non-device behavior |
| Internet access | TVDB FAQ / Android docs verification | Yes [VERIFIED: web fetches] | N/A | Context summary was available as fallback |
| WorkManager | Alternative scheduler | Not present [VERIFIED: codebase grep] | N/A | Use Android platform `AlarmManager` |

**Missing dependencies with no fallback:** None identified for planning. [VERIFIED: environment probes]

**Missing dependencies with fallback:**
- WorkManager is absent and not recommended for exact instant scheduling; use `AlarmManager`. [VERIFIED: codebase grep; CITED: Android alarms docs]

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 4 `4.13.2`, kotlinx-coroutines-test `1.8.1`, MockK `1.13.12`, Robolectric `4.13`. [VERIFIED: `app/build.gradle.kts`] |
| Config file | Gradle app module configuration in `app/build.gradle.kts`; no separate JUnit config found. [VERIFIED: `app/build.gradle.kts`; VERIFIED: test file inventory] |
| Quick run command | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.AirDateGateTest" --tests "com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest"` [VERIFIED: existing tests] |
| Full suite command | `./gradlew testArm64DebugUnitTest` [VERIFIED: `CLAUDE.md`] |

### Phase Requirements -> Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| AIR-01 | Computes exact TVDB availability from `aired` date + `airsTime`, including `20:00`, `8:00 PM`, and `8pm`. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbAirAvailabilityCalculatorTest"` | No, Wave 0 [VERIFIED: test inventory] |
| AIR-02 | Converts source instant to device-local timezone for diagnostics while gating by instant. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbAirAvailabilityCalculatorTest"` | No, Wave 0 [VERIFIED: test inventory] |
| AIR-03 | Withholds future exact next-up rows across main rail, Trakt up-next rail, and Android TV feed. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest" --tests "com.nexio.tv.core.recommendations.AndroidTvOwnedChannelRowsTest"` | Partial; needs new Android TV feed gating assertions. [VERIFIED: existing tests] |
| AIR-04 | Schedules soonest withheld instant, refreshes at trigger, persists across restart, and retries after refresh failure. | unit/Robolectric | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest" --tests "com.nexio.tv.core.scheduler.ContinueWatchingAirAlarmSchedulerTest"` | Partial; scheduler test is Wave 0. [VERIFIED: existing tests] |
| AIR-05 | Date-only and invalid-time rows fall back to existing date-only gate and emit diagnostics. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.AirDateGateTest" --tests "com.nexio.tv.core.tvdb.TvdbAirAvailabilityCalculatorTest"` | Partial; diagnostics tests are Wave 0. [VERIFIED: existing tests] |
| AIR-06 | Detail episodes remain visible while Continue Watching stays gated. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.detail.MetaDetailsSeasonMediaStateTest" --tests "com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest"` | Partial; add regression assertion if detail code is touched. [VERIFIED: existing tests] |

### Sampling Rate

- **Per task commit:** Run the targeted test for the touched component plus `AirDateGateTest`. [VERIFIED: test infrastructure]
- **Per wave merge:** Run `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.*AirDate*" --tests "com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest"` where Gradle pattern support permits; otherwise run explicit classes. [VERIFIED: Gradle test command pattern in `CLAUDE.md`; ASSUMED]
- **Phase gate:** Run `./gradlew testArm64DebugUnitTest` and `./gradlew assembleArm64Debug`. [VERIFIED: `CLAUDE.md`]

### Wave 0 Gaps

- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculatorTest.kt` — covers AIR-01, AIR-02, AIR-05. [VERIFIED: test inventory]
- [ ] `app/src/test/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmSchedulerTest.kt` — covers AIR-04 exact/inexact branch and cancel/reschedule behavior. [VERIFIED: scheduler absent by codebase grep]
- [ ] Extend `app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt` — covers persisted `scheduledReemit` and exact timing fields. [VERIFIED: existing test file]
- [ ] Extend `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingTimelineAirDateTest.kt` — covers exact availability priority over date-only/Trakt timing for all next-up rails. [VERIFIED: existing test file]
- [ ] Add or extend Android TV feed tests to prove Continue Watching feed rows inherit exact gating from snapshot, not separate recommendation logic. [VERIFIED: `AndroidTvFeedCatalogService.kt`; VERIFIED: existing recommendation tests]

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|------------------|
| V2 Authentication | No direct new auth in Phase 8 | TVDB auth remains Phase 6; do not log credentials or tokens in diagnostics. [VERIFIED: Phase 6 research; VERIFIED: Phase 8 scope] |
| V3 Session Management | No | Phase does not add sessions. [VERIFIED: Phase 8 scope] |
| V4 Access Control | Low | Alarm receiver must be non-exported or protected so external apps cannot trigger arbitrary refresh behavior. [VERIFIED: Android receiver pattern; ASSUMED] |
| V5 Input Validation | Yes | Validate `airsTime`, dates, country/platform strings, and source-zone IDs before exact timing; invalid data falls back with diagnostics. [VERIFIED: phase decisions] |
| V6 Cryptography | No | Phase does not add cryptographic primitives; do not hand-roll any token/secret handling. [VERIFIED: Phase 8 scope] |

### Known Threat Patterns for Android Scheduling and Diagnostics

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Exported alarm receiver triggered by another app | Spoofing / Denial of Service | Declare receiver `android:exported="false"` for app-internal alarms unless a system broadcast action requires export; keep alarm action package-scoped. [ASSUMED] |
| Log leakage of provider identifiers or credentials | Information Disclosure | Diagnostics should include reason codes and computed local time only; never log API key, PIN, bearer token, or raw secret payload. [VERIFIED: Phase 6 constraints; VERIFIED: Phase 8 decisions] |
| Malformed TVDB time strings causing crashes | Denial of Service | Parse with `runCatching`, return date-only fallback, and record `invalid_time`. [VERIFIED: phase decisions] |
| Timezone policy fake precision | Integrity | If source timezone cannot be determined reliably, return date-only fallback rather than inventing a timezone. [VERIFIED: phase decisions] |

## Sources

### Primary (HIGH confidence)

- `.planning/phases/08-exact-continue-watching-air-timing/08-CONTEXT.md` — locked Phase 8 decisions, scope, diagnostics, and scheduling requirements. [VERIFIED: file read]
- `.planning/REQUIREMENTS.md` — AIR-01 through AIR-06 requirement text. [VERIFIED: file read]
- `.planning/ROADMAP.md` — Phase 8 goal and success criteria. [VERIFIED: file read]
- `CLAUDE.md` — project architecture, dependency, and verification constraints. [VERIFIED: file read]
- `https://support.thetvdb.com/kb/faq.php?id=29` — TVDB air-time policy and streaming defaults. [CITED: official TVDB support FAQ]
- `tvdb.yml` — local TVDB OpenAPI reference for series `airsTime` and episode `aired` fields. [CITED: checked-in OpenAPI file]
- Android Developers alarms guide — alarm behavior, exact/inexact tradeoffs, and permission requirements. [CITED: https://developer.android.com/develop/background-work/services/alarms]
- Android `AlarmManager` API reference — exact alarm methods and permission state broadcast. [CITED: https://developer.android.com/reference/android/app/AlarmManager.html]

### Secondary (MEDIUM confidence)

- Android ICU `TimeZone` API reference — available timezone IDs, country-associated IDs, canonical IDs, and tzdata version exposure. [CITED: https://developer.android.com/reference/android/icu/util/TimeZone]
- Phase 7 research/context — expected TVDB provider replacement shape and dependency on Phase 7 models. [VERIFIED: `.planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md`; VERIFIED: `.planning/phases/07-tvdb-provider-replacement/07-RESEARCH.md`]
- Phase 6 research — TVDB foundation and credential/logging constraints that Phase 8 must not contradict. [VERIFIED: `.planning/phases/06-tvdb-foundation-and-identity/06-RESEARCH.md`]

### Tertiary (LOW confidence)

- Country capital/most-populous timezone implementation details are not fully sourced in this session; treat the country policy table as an implementation artifact requiring review. [ASSUMED]

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — current Gradle dependencies, Android platform APIs, and test libraries were verified locally. [VERIFIED: `app/build.gradle.kts`; VERIFIED: `gradle/libs.versions.toml`]
- Architecture: HIGH for Continue Watching gate/store/scheduler seams, MEDIUM for Phase 7 TVDB model names because Phase 7 code is not present in this checkout. [VERIFIED: codebase inspection; VERIFIED: Phase 7 docs]
- TVDB timing policy: HIGH for FAQ/defaults, MEDIUM for DST and non-US country-zone implementation. [CITED: TVDB FAQ; ASSUMED]
- Pitfalls: HIGH for persistence and current gating gaps, MEDIUM for exact alarm permission UX because product posture is not locked. [VERIFIED: codebase inspection; CITED: Android docs]

**Research date:** 2026-04-14 [VERIFIED: system date]
**Valid until:** 2026-05-14 for codebase architecture; 2026-04-21 for Android exact-alarm policy and TVDB FAQ details because platform policies and support docs can change. [ASSUMED]
