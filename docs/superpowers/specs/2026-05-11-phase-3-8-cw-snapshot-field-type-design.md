# Phase 3.8 full — `ContinueWatchingMetadataSnapshot` field type migration design

**Status:** Spec — awaiting plan generation.

**Goal:** Eliminate `HomeDisplayMetadata` from `ContinueWatchingMetadataSnapshot.clickTimeDisplayMetadata`. Replace with `clickTimeSlots: ResolvedDisplayFieldSlots`. One-time read-time projection of v1 records to v2 via a custom gson `JsonDeserializer`; future writes always emit v2.

**Background:** Sub-project 3.8 in `docs/superpowers/specs/2026-05-10-phase-3-catalog-pipeline-restructure-design.md`. Phase 3.8 partial (commit `ab7a966c4`) already migrated `renderDisplayMetadata` to a slot-based rank-aware merge internally; the persisted snapshot field is the last remaining `HomeDisplayMetadata` surface in the CW pipeline. With this commit, the typed slot bag flows from click-time capture through persistence to render without a `HomeDisplayMetadata` intermediary.

**Risk:** LOW to MEDIUM. The custom deserializer is the load-bearing component — getting field-presence detection right matters for upgrade paths. Persistence write path is unchanged (default gson serialization handles the new shape correctly). User-visible failure mode: a CW card briefly missing click-time metadata if the deserializer rejects a record. Bounded by the number of CW items per profile (typically 5-20).

---

## Architecture

### Field swap

```kotlin
data class ContinueWatchingMetadataSnapshot(
    val routingVersion: Int,
    val parentId: String,
    val primaryProvider: MetadataPrimaryProvider,
    val decisionReason: MetadataDecisionReason,
    val clickTimeSlots: ResolvedDisplayFieldSlots,  // was clickTimeDisplayMetadata: HomeDisplayMetadata
)
```

### `fromRoute()` signature

```kotlin
fun fromRoute(
    route: MetadataRoute,
    clickTimeSlots: ResolvedDisplayFieldSlots,  // was clickTimeDisplayMetadata: HomeDisplayMetadata
): ContinueWatchingMetadataSnapshot
```

Callers responsible for converting their `HomeDisplayMetadata` click-time capture via the existing `HomeDisplayMetadata.toResolvedFieldSlots(nowMs, rank = DisplaySourceRank.FIRST_PAINT)` helper (added in `ab7a966c4`).

### `renderDisplayMetadata()` signature

```kotlin
fun renderDisplayMetadata(
    canonical: HomeDisplayMetadata?,
    clickTimeSlots: ResolvedDisplayFieldSlots?,  // was clickTime: HomeDisplayMetadata?
    persistedFallback: HomeDisplayMetadata?,
): HomeDisplayMetadata
```

Internal change: skip the HomeDisplayMetadata → slots conversion for the click-time input (it's already slots). Canonical and persistedFallback continue to convert internally (those migrations belong to a later phase).

### Persistence: custom `JsonDeserializer`

Register a `JsonDeserializer<ContinueWatchingMetadataSnapshot>` on the `Gson` instance used by `ContinueWatchingSnapshotStore`:

```kotlin
class ContinueWatchingMetadataSnapshotTypeAdapter(private val gson: Gson) :
    JsonDeserializer<ContinueWatchingMetadataSnapshot> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): ContinueWatchingMetadataSnapshot {
        val obj = json.asJsonObject
        val clickTimeSlots = when {
            obj.has("clickTimeSlots") -> {
                // v2: direct deserialize
                gson.fromJson(obj.get("clickTimeSlots"), ResolvedDisplayFieldSlots::class.java)
            }
            obj.has("clickTimeDisplayMetadata") -> {
                // v1 → v2 projection at FIRST_PAINT rank
                val legacy = gson.fromJson(obj.get("clickTimeDisplayMetadata"), HomeDisplayMetadata::class.java)
                legacy.toResolvedFieldSlots(
                    nowMs = System.currentTimeMillis(),
                    rank = DisplaySourceRank.FIRST_PAINT,
                )
            }
            else -> emptySlotsAt(System.currentTimeMillis())
        }
        return ContinueWatchingMetadataSnapshot(
            routingVersion = obj.get("routingVersion").asInt,
            parentId = obj.get("parentId").asString,
            primaryProvider = gson.fromJson(obj.get("primaryProvider"), MetadataPrimaryProvider::class.java),
            decisionReason = gson.fromJson(obj.get("decisionReason"), MetadataDecisionReason::class.java),
            clickTimeSlots = clickTimeSlots,
        )
    }
}
```

Writes use default gson reflection — the new field is the default-serializable shape.

**No `ContinueWatchingSnapshotStore.SCHEMA_VERSION` bump needed.** The schema version tracks the OUTER snapshot file format; this is an INNER per-record field shape change handled by the deserializer.

---

## Component map

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMetadataSnapshot.kt` | Field swap; `fromRoute` signature change; `renderDisplayMetadata` signature change |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMetadataSnapshotTypeAdapter.kt` (new) | `JsonDeserializer` implementing v1/v2 field-presence detection |
| `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt` | Register the type adapter on the `Gson` instance |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt` | 3 call sites: `shouldReroute` check (no change), `fromRoute(...)` call (convert HomeDisplayMetadata → slots at call site), `renderDisplayMetadata(...)` call (pass `clickTimeSlots` typed) |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` | 1 call site (line ~663) — convert click-time input before `fromRoute` |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt` | 2 call sites (lines 85, 165) — convert before constructor |

---

## Data flow

**Write path (unchanged in essence, new shape on disk):**

1. User clicks CW item → `HomeViewModelContinueWatching.handleContinueWatchingItemClick`
2. Capture `clickTimeDisplayMetadata: HomeDisplayMetadata` from `ContinueWatchingItem.displayMetadata`
3. Convert to slots: `clickTimeDisplayMetadata.toResolvedFieldSlots(nowMs, FIRST_PAINT)`
4. `ContinueWatchingMetadataSnapshot.fromRoute(route, clickTimeSlots)` — new typed signature
5. Eventually flushed to disk via `ContinueWatchingSnapshotStore.write(...)` — default gson reflection emits v2 shape

**Read path (new branching):**

1. App boot or profile switch → `ContinueWatchingSnapshotStore.read(...)`
2. Streaming JsonReader (existing) walks the snapshot file
3. Per-record deserialization invokes the custom `JsonDeserializer`
4. Deserializer detects v1 vs v2 by field presence:
   - `clickTimeSlots` present → v2, direct deserialize
   - `clickTimeDisplayMetadata` present → v1, project to slots at `FIRST_PAINT` rank
   - Neither → empty slots (defensive default)
5. Snapshot returned with `clickTimeSlots: ResolvedDisplayFieldSlots` regardless of source schema

**Render path:**

1. `ContinueWatchingSnapshotService.fetchHomeDisplayMetadata(...)` resolves canonical metadata
2. `renderDisplayMetadata(canonical, clickTimeSlots, persistedFallback)` runs slot-based rank-aware merge
3. Result is a `HomeDisplayMetadata` projected back from slots — same shape that `displayMetadataByItemKey` consumes downstream (no consumer changes)

---

## Error handling

- **Deserializer failure:** wrap the projection in `runCatching` — on any exception (corrupt v1 data, missing fields), fall back to `emptySlotsAt(nowMs)`. The CW item still loads; its click-time metadata is just absent (canonical resolution will fill what it can).
- **Missing required fields (`routingVersion`, `parentId`, etc.):** let gson's default behavior throw — these are required for routing; a missing field means the record is unusable and dropping it is correct.
- **TypeAdapter registration:** the existing `Gson()` builder in `ContinueWatchingSnapshotStore.kt` becomes `GsonBuilder().registerTypeAdapter(ContinueWatchingMetadataSnapshot::class.java, ContinueWatchingMetadataSnapshotTypeAdapter(...)).create()`. The adapter needs a `Gson` reference — pass `gson` itself once constructed, or use a lazy holder.

---

## Testing

| Test | Coverage |
|---|---|
| `ContinueWatchingMetadataSnapshotTypeAdapterTest` (new) | v1 JSON fixture → deserialized with projected slots at `FIRST_PAINT`; v2 JSON fixture → round-trips; missing-fields fixture → falls back to empty slots |
| `ContinueWatchingMetadataSnapshotTest` (existing) | Update test methods to construct via `ResolvedDisplayFieldSlots`; assert via `clickTimeSlots.title.value` etc. |
| `ContinueWatchingSnapshotServiceTest` (existing) | Add a v1-file-on-disk fixture; verify post-deserialize state matches expected v2 projection |
| `HomeDisplayMetadataApplyToPreviewTest` (existing) | No change — `applyToPreview` still exists |
| `HomeRailProjectionReducerTest` (existing) | No change — covered by Phase 3.6.5 test set |

**On-device verification:** install the new APK on a device with existing CW items, force-stop, relaunch → CW items should render with the same titles/posters as pre-upgrade. The v1 → v2 projection happens once per record on first read; subsequent reads are pure v2.

---

## Non-goals

- **Persistence schema version bump:** not needed — the outer file schema is unchanged; only the inner per-record shape detected by field presence.
- **`canonical` and `persistedFallback` migration to slots:** out of scope. Those continue to be `HomeDisplayMetadata?` in `renderDisplayMetadata`; the function internally converts them to slots for the merge. Migrating those inputs is a follow-up phase touching `MetadataRouter.resolve` and the disk-backed metadata cache — too broad for this spec.
- **Routing version bump (`CURRENT_ROUTING_VERSION` → 2):** not needed — the route hasn't changed, only the cached click-time field shape.

---

## Acceptance

- `ContinueWatchingMetadataSnapshot.clickTimeDisplayMetadata: HomeDisplayMetadata` field is gone.
- New `clickTimeSlots: ResolvedDisplayFieldSlots` field exists; all callers populate it.
- gson `JsonDeserializer` handles v1 records: heap dump after upgrade shows zero `HomeDisplayMetadata` instances held via `ContinueWatchingMetadataSnapshot.*` retainer chain.
- Smoke (rule #8): no FATAL/ANR/ClassCast/NoSuchMethod across launch + CW render.
- On-device verification: existing CW items render with their previous click-time titles/posters intact.

---

## Self-review

**1. Placeholder scan:** No "TBD", no "TODO", every component has explicit file path. The pseudocode TypeAdapter sketch is illustrative (full implementation lives in the plan), but its responsibilities are concrete.

**2. Internal consistency:** Field type, signature changes, TypeAdapter behavior, and data flow all align on the same shape (`ResolvedDisplayFieldSlots` end-to-end through persistence).

**3. Scope check:** Single implementation plan — one data class change, one new deserializer, ~6 call sites updated. Fits a single plan cycle.

**4. Ambiguity check:** Two judgement calls made explicit:
   - v1 records get `FIRST_PAINT` rank (matches what `fromRoute` would emit if called fresh today)
   - `runCatching` fallback for deserializer exceptions returns empty slots (matches existing tolerance for incomplete records elsewhere in the CW pipeline)
