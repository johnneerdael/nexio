# Phase 10: TVDB Reliability, Updates, and Diagnostics - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md - this log preserves the alternatives considered.

**Date:** 2026-04-14
**Phase:** 10-TVDB Reliability, Updates, and Diagnostics
**Areas discussed:** Update-aware invalidation, Stable reference-data caching, Graceful failure behavior, Diagnostics and docs

---

## Update-aware invalidation

| Option | Description | Selected |
|--------|-------------|----------|
| TVDB `/updates` first | Poll `/updates?since=...`, invalidate changed entity IDs, and use timestamps/schema keys as safety checks. | Yes |
| Record `lastUpdated` first | Keep cached entries until their own TVDB record timestamp changes when a record is fetched or checked. | No |
| TTL first with update hints | Use conservative TTLs and only consult `/updates` opportunistically. | No |
| You decide | Let the planner choose the simplest update-aware strategy that satisfies the success criteria. | No |

**User's choice:** TVDB `/updates` first.
**Notes:** `/updates` is the primary freshness driver, with schema keys, language epochs, provider tokens, and record timestamps as safety checks.

| Option | Description | Selected |
|--------|-------------|----------|
| Invalidate and remap where possible | Delete events purge affected cache entries; duplicate merges purge the old ID and remap to `mergeToType` / `mergeToId` when TVDB provides it. | Yes |
| Invalidate only | Delete and merge events remove stale entries; the next lookup rediscover the right record. | No |
| Mark stale, do not purge immediately | Keep old entries as emergency fallback but require refresh before normal use. | No |
| You decide | Let implementation choose based on the existing TVDB identity model. | No |

**User's choice:** Invalidate and remap where possible.
**Notes:** Merge handling should preserve continuity where TVDB identifies the target record.

| Option | Description | Selected |
|--------|-------------|----------|
| Background periodic plus startup catch-up | Store last successful update cursor, catch up on app startup, then run periodic background checks. | Yes |
| Startup only | Check updates when the app starts and rely on manual browsing afterward. | No |
| Before TVDB metadata reads | Check updates inline before serving cached TVDB metadata. | No |
| You decide | Let planner select a conservative scheduling pattern. | No |

**User's choice:** Background periodic plus startup catch-up.
**Notes:** Normal metadata reads should not be blocked by inline update checks.

---

## Stable reference-data caching

| Option | Description | Selected |
|--------|-------------|----------|
| Long-lived cache with update invalidation | Cache for weeks/months, refresh through `/updates` when reference entity types change, and keep a schema-version escape hatch. | Yes |
| Session/app-start refresh | Refresh reference data once per app start if TVDB is enabled. | No |
| Bundle fallback plus lazy network refresh | Ship minimal built-in fallback mappings and refresh from TVDB only when needed. | No |
| You decide | Let the planner pick cache lifetimes and fallback strategy. | No |

**User's choice:** Long-lived cache with update invalidation.
**Notes:** Stable reference data should align with TVDB heavy-cache guidance.

| Option | Description | Selected |
|--------|-------------|----------|
| Use last-known-good reference data | Continue using cached labels/types even if stale, and report the refresh failure diagnostically. | Yes |
| Fall back to raw IDs/codes | Show or use raw TVDB IDs/codes when labels are unavailable. | No |
| Drop optional reference labels | Hide reference-backed fields until refresh works. | No |
| You decide | Let implementation choose per field. | No |

**User's choice:** Use last-known-good reference data.
**Notes:** Stale labels are preferable to blank metadata or raw IDs when refresh fails.

| Option | Description | Selected |
|--------|-------------|----------|
| Warm core references during TVDB setup/startup | Artwork types, languages, genres, statuses, content ratings, season/source/entity/company types are warmed once TVDB is valid, then refreshed by update signals. | Yes |
| Lazy per surface | Fetch each reference category only when a screen needs it. | No |
| Only fetch categories currently used by implemented TVDB mappers | Narrow first pass; future categories get added later. | No |
| You decide | Let planner pick a minimal set. | No |

**User's choice:** Warm core references during TVDB setup/startup.
**Notes:** Core reference data should be available before TVDB surfaces need labels.

---

## Graceful failure behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Serve last-known-good TVDB data, then explicit fallback | Use cached TVDB data when present; only fall back to TMDB/existing metadata when cache cannot safely satisfy the surface, and record the reason. | Yes |
| Fallback to TMDB immediately | Treat outage as provider fallback and use TMDB if configured. | No |
| Show existing local metadata only | Avoid external fallback during outages. | No |
| You decide | Let planner choose per surface. | No |

**User's choice:** Serve last-known-good TVDB data, then explicit fallback.
**Notes:** Outages should not blank TV detail or Continue Watching when cached TVDB data is safe.

| Option | Description | Selected |
|--------|-------------|----------|
| Keep cached data, block new TVDB network calls, surface invalid status | Existing cached TVDB metadata may continue as last-known-good; new refreshes stop until credentials are fixed; fallback is explicit when needed. | Yes |
| Purge TVDB cache and fall back | Treat invalid credentials as a hard reset. | No |
| Keep retrying silently | Continue normal TVDB calls and rely on logs. | No |
| You decide | Let planner define invalid-credential policy. | No |

**User's choice:** Keep cached data, block new TVDB network calls, surface invalid status.
**Notes:** Invalid credentials should not create repeated unauthorized calls.

| Option | Description | Selected |
|--------|-------------|----------|
| Field-level fallback with reason codes | Keep TVDB as the provider, fill only missing fields from safe existing sources where allowed, and record per-field reasons. | Yes |
| Record-level fallback | If important TVDB fields are missing, fall back the whole TV record to TMDB/existing metadata. | No |
| No fallback for missing optional fields | Leave missing fields blank and report diagnostics. | No |
| You decide | Let planner define per-field fallback rules. | No |

**User's choice:** Field-level fallback with reason codes.
**Notes:** Missing `airsTime`, date-only gating, and poster-ratings override should be represented as diagnostic reason codes.

---

## Diagnostics and docs

| Option | Description | Selected |
|--------|-------------|----------|
| Settings status + debug diagnostics + logs | Keep user-facing status in TVDB settings, add detailed provider/cache/fallback diagnostics under Debug, and log structured reasons. | Yes |
| Settings only | Put all provider/fallback status on the TVDB settings screen. | No |
| Logs/export only | Keep diagnostics technical and out of UI. | No |
| You decide | Let planner pick diagnostic surfaces. | No |

**User's choice:** Settings status + debug diagnostics + logs.
**Notes:** Normal users get status; developer-level detail belongs under Debug and logs.

| Option | Description | Selected |
|--------|-------------|----------|
| All roadmap reasons | Provider choice, fallback reason, missing `airsTime`, date-only gating, poster-ratings override, skipped TMDB TV fetches, update refresh status, stale cache served, invalid credentials. | Yes |
| Only success-criteria reasons | Provider choice, fallback reason, missing `airsTime`, date-only gating, poster-ratings override, skipped TMDB TV fetches. | No |
| Only failures | Invalid credentials, outage, fallback reason, missing `airsTime`. | No |
| You decide | Let planner define diagnostic enum/event shape. | No |

**User's choice:** All roadmap reasons.
**Notes:** Include positive proof states such as skipped TMDB TV fetches, not only failures.

| Option | Description | Selected |
|--------|-------------|----------|
| Setup + precedence + exact timing + troubleshooting | Explain TVDB setup, TVDB/TMDB/poster-ratings precedence, exact Continue Watching air-time behavior, date-only fallback, stale-cache behavior, and where to find diagnostics. | Yes |
| Setup and precedence only | Keep docs short; diagnostics remain in-app/logs. | No |
| Developer diagnostics only | Document internal reason codes and cache behavior, not user setup. | No |
| You decide | Let planner pick doc scope. | No |

**User's choice:** Setup + precedence + exact timing + troubleshooting.
**Notes:** Documentation should address the confusion points most likely to appear in TVDB support reports.

---

## the agent's Discretion

- Exact WorkManager/job scheduling interval for periodic `/updates` checks.
- Exact cache store shape, DTO names, and schema-version numbers.
- Exact diagnostic enum/event names and log tag names.
- Exact documentation placement.

## Deferred Ideas

None.
