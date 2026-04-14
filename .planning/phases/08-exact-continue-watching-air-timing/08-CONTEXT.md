# Phase 8: Exact Continue Watching Air Timing - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase makes TVDB-backed Continue Watching exact to the episode availability instant. Nexio computes availability from TVDB episode aired date plus TVDB series/network/platform timing policy, converts that source instant to the Android TV device timezone, withholds future next-up rows until the computed instant, and schedules re-evaluation so rows can appear when they become available. TV detail screens may still show future unaired episodes; Continue Watching remains availability-gated.

</domain>

<decisions>
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

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase Scope and Requirements
- `.planning/ROADMAP.md` — Phase 8 goal, success criteria, and dependency on Phase 7.
- `.planning/REQUIREMENTS.md` — AIR-01 through AIR-06 requirements for exact Continue Watching timing.
- `.planning/PROJECT.md` — Milestone-level TVDB provider precedence and exact `airsTime` decision.

### TVDB Timing Policy
- `https://support.thetvdb.com/kb/faq.php?id=29` — TVDB air-time FAQ defining Eastern-time handling for US series, country-based time handling for non-US series, and platform-specific streaming release defaults.

### Existing Continue Watching Gate and Scheduler
- `app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt` — Current air-date gate, date-only parsing, and soonest pending instant helper.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt` — Current Continue Watching snapshot build, scheduled withheld-entry model, in-memory re-emit timer, and refresh path.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingTimeline.kt` — Current split and mixed timeline helpers for resume vs next-up rows.
- `app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt` — `TrackingNextUpEntry` shape and Trakt/Simkl next-up routing.
- `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt` — Persisted Continue Watching snapshot schema and next-up entry serialization.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` — Home Continue Watching consumption, display conversion, and current TMDB enrichment path.

### Existing Tests
- `app/src/test/java/com/nexio/tv/data/repository/AirDateGateTest.kt` — Current gate and soonest pending behavior tests.
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingTimelineAirDateTest.kt` — Current rail gating and timer idempotency tests.
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt` — Current snapshot mutation and scheduled re-emit test coverage.
- `app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt` — Current persisted snapshot schema tests.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `AirDateGate` already centralizes aired/not-aired checks and exposes `soonestPendingMs`; extend or replace it with exact TVDB instant support instead of scattering time comparisons.
- `ContinueWatchingSnapshotService` already has `scheduledReemit`, `scheduleReemitIfNeeded`, and `ensureFresh(force = true)`; Phase 8 should preserve the soonest-target behavior while adding durable scheduling.
- `ContinueWatchingSnapshotStore` persists next-up entries and can carry new exact-timing fields through a schema bump.
- `TrackingNextUpEntry` is the shared next-up DTO crossing Trakt/Simkl, snapshot, UI, and Android TV feed paths.

### Established Patterns
- Continue Watching gating is already done before UI mapping, which is the right layer for exact TVDB availability.
- The current in-memory timer is idempotent when the soonest target is unchanged; keep that churn-prevention behavior for durable scheduling too.
- Snapshot storage uses explicit schema versions; exact timing metadata should be versioned rather than opportunistically appended without migration behavior.

### Integration Points
- Phase 7 TVDB provider replacement should supply TVDB episode aired date, series `airsTime`, and any network/platform/source-country metadata required by this phase.
- Home Continue Watching, Trakt up-next, and Android TV feed/recommendations all consume the same snapshot/timeline primitives and should receive consistent gated lists.
- Durable scheduling likely integrates near `ContinueWatchingSnapshotService.scheduleReemitIfNeeded` and must trigger `ensureFresh(force = true)` or equivalent provider refresh.

</code_context>

<specifics>
## Specific Ideas

- The important behavior is source-timezone conversion, not local-date comparison: a show airing at 8 PM Eastern can become available on the next local day for some device timezones.
- TVDB exact timing should be authoritative over Trakt/Simkl next-up dates whenever TVDB exact timing is available.
- Amazon Prime Video is a TVDB-documented special case: scheduled release day at `00:00 GMT`.
- Keep Continue Watching visually clean: future rows are absent until available, with no placeholder card in the rail.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 08-exact-continue-watching-air-timing*
*Context gathered: 2026-04-14*
