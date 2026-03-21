## 1. Display Metadata Model
- [x] 1.1 Define a shared Home display metadata model for catalog items and Continue Watching items.
- [x] 1.2 Update Modern Home item builders to consume the shared model instead of ad hoc per-source fields.

## 2. Persisted Hydration Pipeline
- [x] 2.1 Update Home refresh/publish flow so changed/new Home items are enriched before snapshot publish.
- [x] 2.2 Persist display-ready metadata for both Home catalog rows and Continue Watching snapshots.
- [x] 2.3 Keep focus-time enrichment as fallback-only behavior for missing/stale entries.

## 3. Locale & Snapshot Compatibility
- [x] 3.1 Make persisted Home display metadata language-aware and integrate with locale epoch invalidation.
- [x] 3.2 Add backward-compatible snapshot migration/version handling for existing raw Home snapshots.

## 4. Cleanup & Storage Control
- [x] 4.1 Add reference tracking for persisted Home display metadata keyed by current Home feed membership.
- [x] 4.2 Evict metadata for items no longer referenced by any persisted Home feed in bounded cleanup batches.
- [x] 4.3 Add cleanup on language epoch/schema version changes to prevent stale storage buildup.

## 5. Validation
- [x] 5.1 Add targeted tests for persisted enriched snapshot restore, Continue Watching parity, locale invalidation, and dropped-feed eviction.
- [x] 5.2 Verify that cold-start Modern Home restores consistent metadata across all rail types without requiring focus-time repair.
