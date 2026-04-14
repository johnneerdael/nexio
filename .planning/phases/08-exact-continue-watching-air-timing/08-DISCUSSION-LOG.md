# Phase 8: Exact Continue Watching Air Timing - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-14T19:48:18+02:00
**Phase:** 08-Exact Continue Watching Air Timing
**Areas discussed:** Source timezone policy, Gating surface coverage, Missing or partial air-time metadata, Re-evaluation reliability, User-visible diagnostics

---

## Source Timezone Policy

| Option | Description | Selected |
|--------|-------------|----------|
| Series country/network timezone | Use series country/network timezone when known, with US shows using Eastern time by default | ✓ |
| Device timezone | Always interpret `airsTime` in the Android TV device timezone | |
| UTC | Always interpret `airsTime` as UTC | |
| The agent decides | Delegate fallback details to the agent | ✓ |

**User's choice:** `1A, 2A, 3A, 4D`
**Notes:** User clarified: "All TVDB network shows use EST, but timing metadata needs to convert EST to local device TZ for accurate CW display." Also selected single Eastern instant for US network shows and permissive parsing of common `airsTime` formats.

---

## Gating Surface Coverage

| Option | Description | Selected |
|--------|-------------|----------|
| All next-up surfaces | Main rail, Trakt up-next rail, and Android TV recommendations/feed | ✓ |
| Main rail only | Gate only the in-app Continue Watching rail | |
| Main + Trakt only | Gate main and Trakt up-next, excluding Android TV feed/recommendations | |
| The agent decides | Delegate surface coverage details to the agent | |

**User's choice:** `1A, 2A, 3A, 4A`
**Notes:** In-progress/resume items remain visible. TV detail can show future episodes while play actions remain blocked/unaired. Withheld entries remain stored/scheduled for exact re-emit.

---

## Missing or Partial Air-Time Metadata

| Option | Description | Selected |
|--------|-------------|----------|
| Date-only fallback | Fall back to existing date-only gating and diagnose precise time unavailable | |
| Hide until refresh | Treat episode as unavailable until manual refresh | |
| Default midnight Eastern | Use `00:00` Eastern as a generic default | |
| TVDB policy defaults | Apply TVDB FAQ source-time policy and only fall back when no reliable default exists | ✓ |

**User's choice:** `1D, 2A, 3A, 4A`
**Notes:** User asked whether Nexio can apply per-network timing from `https://support.thetvdb.com/kb/faq.php?id=29` and allow local date to shift after timezone conversion so episodes are not displayed a day early. The captured decision is to use TVDB policy defaults when reliable, including US Eastern, non-US country capital/major-city time, and streaming service defaults. Invalid/unparsable `airsTime` falls back to date-only with diagnostics. TVDB exact timing wins over Trakt/Simkl.

---

## Re-evaluation Reliability

| Option | Description | Selected |
|--------|-------------|----------|
| Durable scheduling | Survive app process death, device sleep, and TV reboot | ✓ |
| In-memory only | Existing timer is enough while app is open | |
| Process death only | Survive process death but not reboot | |
| The agent decides | Delegate scheduler durability to the agent | |

**User's choice:** `1A, 2A, 3A, 4A`
**Notes:** Scheduled instant should refresh tracking provider next-up and rebuild Continue Watching. Schedule only the soonest withheld instant, then recompute. Refresh failures keep withheld entries and retry with backoff.

---

## User-Visible Diagnostics

| Option | Description | Selected |
|--------|-------------|----------|
| Debug/settings + logs | Diagnostics only in debug/settings diagnostics and logs | ✓ |
| Logs only | No settings/debug diagnostics | |
| Per-item visible | Show diagnostics on Continue Watching items | |
| The agent decides | Delegate diagnostic surface to the agent | |

**User's choice:** `1A, 2A, 3A, 4C`
**Notes:** Capture failure reasons only: missing `airsTime`, invalid time, missing timezone/source policy, and refresh failure. Expose computed device-local availability time in diagnostics/logs only. Continue Watching should not show placeholders; TV detail may show a placeholder if useful.

---

## the agent's Discretion

- Exact fallback when source timezone cannot be determined but TVDB has date plus time, constrained by avoiding fake precision.
- Internal policy representation, durable scheduler implementation, retry timing, and diagnostic payload structure.

## Deferred Ideas

None.
