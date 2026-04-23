---
date: 2026-04-15
topic: android-tv-entity-card-provider-link
focus: docs/brainstorms/2026-04-15-android-tv-entity-card-provider-link-requirements.md
---

# Ideation: Android TV Entity Card Provider Link

## Codebase Context

Nexio is an Android TV / Fire TV Kotlin app with an existing Android TV recommendations/channel pipeline, a newly added native Android TV search provider, and rich local catalog/metadata persistence. The relevant local sources are `HomeCatalogSnapshotStore`, `CatalogDiskCacheStore`, `MetadataDiskCacheStore`, `MetaPreview`, and the new `core/search` provider/service classes.

The strongest leverage point is not more live search. It is using already-loaded, enriched, locally persisted title data as an instant source for Android TV suggestions, because Android TV entity-card matching depends on high-confidence title/year/duration metadata. `HomeCatalogSnapshotStore` already stores `catalogRows`, `fullCatalogRows`, and `heroItems`; `MetadataDiskCacheStore` has richer `Meta` records but is key-oriented rather than enumerable.

No `docs/solutions/` directory exists in this checkout, so no institutional solution notes were available.

## Ranked Ideas

### 1. Local Corpus First Search Provider

**Description:** Add a local search corpus that reads `HomeCatalogSnapshotStore` first, dedupes `MetaPreview` items, scores title matches, and returns matching suggestions before live Cinemeta is queried.

**Rationale:** This directly addresses the user observation: titles already present on modern Home should be the most reliable and instant source. It also keeps the current provider simple because it can still fall back to Cinemeta only when local cache has no useful result.

**Downsides:** Home snapshot contents may not equal “everything Nexio can stream,” and hidden/private catalog semantics need careful handling before exposing results to global search.

**Confidence:** 92%

**Complexity:** Medium

**Status:** Unexplored

### 2. Entity Match Metadata Scoring

**Description:** Add a match-quality/richness score for suggestion candidates, favoring exact normalized title, production year, duration, source addon routeability, poster/backdrop, and locally enriched metadata over raw live search results.

**Rationale:** Entity-card matching will be fragile if the provider emits plausible but weak candidates. A score makes duplicate collapse and local-vs-live precedence explicit, testable, and tunable.

**Downsides:** Scoring can become overfit without device validation. The first version should stay small and explainable.

**Confidence:** 88%

**Complexity:** Medium

**Status:** Unexplored

### 3. Runtime Hydration Pass for Searchable Home Items

**Description:** When Home/catalog snapshots are written or refreshed, opportunistically preserve or hydrate reliable runtime values for movie/show candidates that lack duration but are likely to be searched.

**Rationale:** Android TV entity matching requires duration. Local snapshots often have title/year/artwork, but duration can be missing or string-shaped. Improving runtime completeness raises the chance that Nexio appears on Google media detail pages.

**Downsides:** If done eagerly, this could turn search readiness into metadata fan-out. It should be bounded to already-visible/high-priority Home items and reuse existing metadata cache paths.

**Confidence:** 80%

**Complexity:** Medium

**Status:** Unexplored

### 4. Entity Match Diagnostics Screen or Log Mode

**Description:** Add a debug-only diagnostic view or structured log output showing what the native search provider returned for a query, including source, title match type, year, duration, route target, and why candidates were dropped.

**Rationale:** Android TV/Google TV placement is partly opaque. Without diagnostics, every failure looks like “Nexio didn’t work.” A diagnostic trail lets the team distinguish provider registration issues, weak metadata, no local cache hit, and platform-side non-placement.

**Downsides:** It does not improve matching by itself and must avoid logging raw search queries in production logs.

**Confidence:** 84%

**Complexity:** Low / Medium

**Status:** Unexplored

### 5. On-Device Entity-Card Validation Checklist and Fixture Titles

**Description:** Define a small set of fixture titles that should exist in Home/cache, with expected title/year/duration values, and extend the Android TV checklist to validate both direct Nexio suggestions and Google entity-card app-option behavior.

**Rationale:** Android TV launcher behavior varies by device. A repeatable manual fixture set prevents guessing and creates a baseline for whether local metadata changes actually influence entity-card placement.

**Downsides:** Manual validation is slower than unit tests, and platform UI changes may still cause inconsistent results.

**Confidence:** 78%

**Complexity:** Low

**Status:** Unexplored

### 6. Media Actions Readiness Spike

**Description:** Run a bounded investigation into whether Google Media Actions or Engage SDK/catalog feeds are required for consistent provider placement on target Google TV entity pages, and what policy/catalog hosting obligations that would introduce.

**Rationale:** Local provider improvements may still not guarantee the Disney+-style button in every entity detail page. A spike keeps that larger path visible without letting it derail the lower-cost local-cache improvement.

**Downsides:** External/platform-dependent, likely heavier than an app-only change, and may require policy-safe public catalog/feed infrastructure.

**Confidence:** 70%

**Complexity:** Medium / High

**Status:** Unexplored

### 7. Content Identity Bridge for Future Watch Next and Media Actions

**Description:** Standardize stable title identity emitted by search suggestions, Android TV preview programs, Watch Next items, and any future Media Actions feed so they can reconcile around the same item IDs.

**Rationale:** Android TV Watch Next docs emphasize content ID matching with Media Actions feeds for stronger reconciliation. Nexio already has preview channels and now native search; aligning IDs now compounds later if Media Actions becomes necessary.

**Downsides:** This is architectural groundwork. It should not be the first step unless planning finds current IDs inconsistent or lossy.

**Confidence:** 72%

**Complexity:** Medium

**Status:** Unexplored

## Rejection Summary

| # | Idea | Reason Rejected |
|---|------|-----------------|
| 1 | Replace Cinemeta entirely with a full local index | Too much carrying cost; Home/catalog snapshot gives most of the value without a separate index. |
| 2 | Search every installed addon live from Android TV search | Already rejected in requirements; slow and risks exposing private addon catalogs. |
| 3 | Start playback directly from Google entity cards | Conflicts with the current detail-only product decision and creates wrong-stream/wrong-episode risk. |
| 4 | Force Nexio onto Google entity pages via UI tricks | Not actionable; final provider placement is platform-controlled. |
| 5 | Add a user setting for global search visibility | Not aligned with the goal; current decision is always enabled from Nexio’s side. |
| 6 | Persist every metadata cache key into a new SQLite/Room index | Potentially valuable later, but too expensive before proving Home snapshot search is insufficient. |
| 7 | Use poster/backdrop image matching | Not grounded in Android TV matching docs; title/year/duration are the documented levers. |
| 8 | Prioritize popular/trending titles in search results | Popularity does not solve entity-card matching and can pollute exact-title results. |
| 9 | Build a portal-side public catalog feed immediately | Duplicates the heavier Media Actions path before app-local matching is exhausted. |
| 10 | Add analytics for all Android TV search queries | Privacy risk and contrary to the no raw query logging requirement. |
| 11 | Treat Watch Next as the primary fix | Watch Next helps continuation surfaces, but the user’s reported problem is Google media detail provider links. |
| 12 | Use only `MetadataDiskCacheStore` as the corpus | Current APIs are key-based, not enumerable; Home snapshots are a better first corpus. |

## Session Log

- 2026-04-15: Initial ideation — 19 candidates generated, 7 survived.
