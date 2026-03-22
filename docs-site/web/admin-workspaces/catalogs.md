# Catalog Inventory: Ranking and Visibility Control

## Audience
This page is for users who want deterministic Home composition and repeatable catalog ordering across sessions.

## What this page covers
- Catalog ordering model
- Enable and disable behavior
- Android TV launcher feed linkage
- Operational strategy for clean Home rails

## Source of truth
Catalog behavior is implemented in:
- `app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderScreen.kt`

## Catalog control architecture
Catalog Inventory is the ranking layer between addon output and Home rendering.
It controls:
- Relative order of catalogs
- Per-catalog enabled state for Home
- Android TV launcher feed publication options

This lets you keep provider breadth while maintaining a disciplined surface area on Home.

## Ordering model
Each catalog item has:
- A stable key
- Move-up and move-down operations
- Optional enable or disable toggle for Home visibility

Order changes are immediately reflected in the managed list and should become visible in Home after refresh.

## Enabled and disabled semantics
If a catalog is toggleable and disabled:
- It remains known to configuration
- It is marked disabled for Home rendering
- It can be re-enabled without reinstalling the addon

This supports reversible curation without destructive provider edits.

## Android TV launcher integration
Catalog Inventory includes Android TV launcher feed controls:
- Global on or off switch for channel publishing
- Feed key selection set

Use this only after in-app Home composition is already stable.

## High-confidence curation workflow

### 1. Build a minimal baseline
Keep only top-value catalogs enabled.

### 2. Rank by usage frequency
Move frequently used catalogs to the first rows.

### 3. Remove semantic duplicates
Disable rows that repeat the same intent.

### 4. Validate in Home
Return to Home and inspect first-screen relevance.

**Expected result:** First rows represent your highest-value discovery paths.

## Troubleshooting

### Symptom
Catalog order changed in settings but Home order did not change.

### Likely root causes
- Settings were not persisted before exit
- Home has stale view state
- Catalog was disabled but expected as visible

### Recovery
1. Reopen Catalog Inventory and confirm the saved order.
2. Confirm target catalog is enabled.
3. Return to Home and force a refresh by navigating away and back.
4. Restart app if state still appears stale.

### Verification
Home row order matches configured ranking.

## Next page
Continue with [Integrations](./integrations.md) to add identity and metadata services on top of your curated catalog graph.
