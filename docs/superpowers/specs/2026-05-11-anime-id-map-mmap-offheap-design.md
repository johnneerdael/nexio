# AnimeIdMappingService — mmap'd compact-binary asset (off-heap)

**Status:** approved design, ready for implementation plan
**Date:** 2026-05-11
**Owner:** core/anime
**Related rules:** CLAUDE.md #3 (no big-blob string materialization), #5 (memoize at reference-fresh boundaries), #6 (don't pin large values across coroutine fan-out)
**Predecessor incident:** cold-start ANR fix `45984efc7` — streamed the JSON parse to eliminate the 48 MiB transient peak. This design eliminates the residual **15 MiB retained baseline** that streaming did not address.

---

## 1. Problem

`AnimeIdMappingService` (`app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt`) lazy-loads `app/src/main/assets/anime/nexio-anime-map-v1.json` (8.3 MiB) into Kotlin data classes:

- `identityRecordsByKitsu`: 12,960 `AnimeIdMapRecord` entries
- `episodeMappingsByAnidb`: 1,409 `AnimeEpisodeMappingRecord` entries (with nested ranges/explicit-maps lists)
- 8 lookup indexes (`byKitsu`/`byMal`/`byAnilist`/`byAnidb`/`byTvdb`/`byTmdbTv`/`byTmdbMovie`/`byImdb`) totaling ~66,000 map entries

Retained Java-heap baseline: **~15 MiB**, permanent for the life of the `@Singleton`. Heap dumps after profile selection show this as one of the top retainers post-Phase-2C.

## 2. Goal

Move the asset's retained working set off the JVM heap:

- **<1 MiB Java heap retention** (down from ~15 MiB)
- **Off-heap mmap'd region** (~5–6 MiB, OS-paged, evictable)
- **Public API unchanged** — all 9 read methods on `AnimeIdMappingService` keep their current signatures and synchronous semantics. Zero ripple to callers.
- **No per-recomposition allocation** on hot paths (rule #5 alignment).
- **No new dispatcher rules** — reads stay non-suspending; no Compose Main-thread risk.

## 3. Non-goals

- Schema evolution beyond v1 of the binary format. Reserved header fields exist for future use; the reader rejects unknown versions.
- Network-fetched updates of the asset. The asset ships with the APK and is regenerated as part of release cuts.
- Sharing the mmap region across processes. App is single-process.
- Migrating other large in-memory assets (TVDB caches, etc.) — they already follow the streaming-JSON file pattern.

## 4. Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Build time (Gradle task, runs when JSON asset changes)             │
│                                                                     │
│   nexio-anime-map-v1.json  ──► AnimeIdMapBinaryEncoder ──►          │
│                                  nexio-anime-map-v1.bin             │
│   (8.3 MiB, human-readable)      (~5–6 MiB, packed binary)          │
│   (dataset-version suffix matches the source JSON; the binary       │
│    *format* version lives in the file header, not the filename.)    │
│                                                                     │
│   Encoder lives in :tools:anime-id-map-encoder (kotlin-jvm).        │
│   Output committed into app/src/main/assets/anime/ alongside JSON.  │
│   JSON kept for provenance / diff review only — NOT loaded at       │
│   runtime.                                                          │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  First launch per asset version (one-time)                          │
│                                                                     │
│   assets/anime/nexio-anime-map-v1.bin                               │
│     ──► stream-copy bytes to                                        │
│         filesDir/anime-id-map/fmt<BINARY_SCHEMA_VERSION>.bin        │
│         (APK assets aren't directly mmap-able; need a real File.    │
│          The on-disk filename uses the binary-format version so a   │
│          format bump cleanly invalidates older cached copies.)      │
│     ──► delete older fmt<n>.bin files                               │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  Steady state (every cold start)                                    │
│                                                                     │
│   AnimeIdMappingService (Singleton, unchanged public API)           │
│     └─ AnimeIdMapBinaryReader (Singleton)                           │
│         - One MappedByteBuffer over the full file, plus six         │
│           absolute-indexed slice views (header, indexTable,         │
│           indexRegion, multiListPool, records, stringPool).         │
│         - synchronous lookups: binary search → record decode        │
│                                                                     │
│   JVM heap retention: 6 thin buffer wrappers + offset constants ≈   │
│   1 KiB total. All slices share the single underlying mmap region.  │
│   Native mmap region: ~5–6 MiB, OS-paged, evictable under pressure. │
└─────────────────────────────────────────────────────────────────────┘
```

### Key invariants

- `AnimeIdMappingService`'s 9 public methods keep exact signatures and return types; only the constructor changes (replaces `assetProvider: () -> AnimeIdMapAsset` with `reader: AnimeIdMapBinaryReader`).
- The JSON asset stays in the repo as provenance / human-diffable source, but is **not loaded** at runtime.
- Versioning lives in the file header (magic + schemaVersion). Mismatch → delete on-disk copy, recopy from APK asset once. If still bad → degrade to `EMPTY_ASSET` behavior (matches current).

## 5. File layout

All multi-byte values little-endian. Offsets are absolute from start of file unless noted.

```
┌─────────────────────────────────────────────────────────────────────┐
│ HEADER (64 bytes, fixed)                                            │
├─────────────────────────────────────────────────────────────────────┤
│  0  magic            "NXAI"  (4 bytes)                              │
│  4  schemaVersion    u32     (currently 1; bump on layout change)   │
│  8  generatedAtEpoch i64     (from input JSON's generatedAt field)  │
│ 16  recordCount      u32                                            │
│ 20  recordsOffset    u64     (start of RECORDS region)              │
│ 28  recordsLength    u64                                            │
│ 36  indexTableOffset u64     (start of INDEX TABLE)                 │
│ 44  stringPoolOffset u64     (start of STRING POOL)                 │
│ 52  stringPoolLength u64                                            │
│ 60  reserved         u32     (zero)                                 │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ INDEX TABLE (9 descriptors × 24 bytes = 216 B)                      │
├─────────────────────────────────────────────────────────────────────┤
│  Slot 0: byKitsu        stride=12 U64                               │
│  Slot 1: byMal          stride=12 U64                               │
│  Slot 2: byAnilist      stride=12 U64                               │
│  Slot 3: byAnidb        stride=12 U64                               │
│  Slot 4: byTmdbMovie    stride=12 U64                               │
│  Slot 5: byTvdb         stride=16 MULTI                             │
│  Slot 6: byTmdbTv       stride=16 MULTI                             │
│  Slot 7: byImdb         stride=20 IMDB                              │
│  Slot 8: byAnidbEpisode stride=12 U64                               │
│  (each descriptor: u32 kind | u32 stride | u64 offset | u64 count)  │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ INDEX REGIONS (sorted, fixed-stride; binary-searchable)             │
├─────────────────────────────────────────────────────────────────────┤
│ Single-value U64 index (byKitsu/byMal/byAnilist/byAnidb/            │
│ byTmdbMovie/byAnidbEpisode):                                        │
│    repeated [ u64 key | u32 recordOffset ]   (12 B/entry)           │
│                                                                     │
│ Multi-value index (byTvdb/byTmdbTv):                                │
│    repeated [ u64 key | u32 listOffset | u32 listLen ] (16 B)       │
│    listOffset points into MULTI-LIST POOL (u32 recordOffsets[])     │
│                                                                     │
│ IMDB index (string keys):                                           │
│    repeated [ u64 keyHash | u32 stringPoolOffset                    │
│              | u32 listOffset | u32 listLen ]  (20 B)               │
│    Sorted by keyHash. Collisions resolved by comparing the          │
│    string at stringPoolOffset. Lookup = binary-search by hash,      │
│    then linear-probe within hash-equal run (expected probe ≈ 1).    │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ MULTI-LIST POOL — densely packed u32 record-offset arrays           │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ RECORDS REGION (12,960 identity + 1,409 episode records)            │
├─────────────────────────────────────────────────────────────────────┤
│ Identity record (variable length, varint-encoded):                  │
│    u8  recordKind=0                                                 │
│    u8  presenceBits   (mal, anilist, anidb, tmdb, tvdb, imdb,       │
│                        mediaType, sourceType)                       │
│    u8  presenceBits2  (tvdbSeason, tmdbSeason, tvdbEpisodeOffset,   │
│                        tmdbEpisodeOffset, hasMappingRules,          │
│                        evidenceCount>0)                             │
│    varint kitsuId                                                   │
│    [varint mal] [varint anilist] [varint anidb]                     │
│    [varint tmdb] [varint tvdb]                                      │
│    [u32 imdbStringPoolOffset]                                       │
│    [u8 mediaTypeEnum]   (0=movie 1=series 2=other)                  │
│    [u8 sourceTypeEnum]  (0=TV 1=OVA 2=ONA 3=Movie 4=Music           │
│                          5=Special 6=Other)                         │
│    [varint tvdbSeason] [varint tmdbSeason]                          │
│    [varint tvdbEpisodeOffset] [varint tmdbEpisodeOffset]            │
│    [u8 hasMappingRules]                                             │
│    [varint evidenceCount] [u32 stringPoolOffset × evidenceCount]    │
│                                                                     │
│ Episode record (recordKind=1):                                      │
│    u8 recordKind=1                                                  │
│    varint anidbId                                                   │
│    [u32 nameStringPoolOffset]                                       │
│    [varint tvdbSeriesId] [varint tmdbTvId]                          │
│    varint rangesCount                                               │
│      × [u8 srcSeason | u16 startEp | u16 endEp | u8 targetProv      │
│         | u8 targetSeason | i16 offset]    (10 B fixed per range)   │
│    varint explicitMapsCount                                         │
│      × [u8 srcSeason | u16 srcEp | u8 targetProv | u8 targetSeason  │
│         | u16 targetEp]                    (7 B fixed per map)      │
│    varint evidenceCount × [u32 stringPoolOffset]                    │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ STRING POOL                                                         │
│   repeated [varint length | utf8 bytes]                             │
│   Referenced by u32 stringPoolOffset (byte offset into the pool).   │
│   Deduplicated at encode time (evidence strings repeat heavily).    │
└─────────────────────────────────────────────────────────────────────┘
```

### Layout rationale

- **u64 numeric keys, not String.** The 4 simple indexes (`byKitsu`/`byMal`/`byAnilist`/`byAnidb`) and the TMDB/TVDB indexes are all integer IDs serialized as strings in JSON. Storing as u64 packs to 8 bytes vs ~40 bytes/String on heap and makes binary-search comparisons primitive.
- **Hash-sorted IMDB index.** IMDB IDs (`tt0286390`) are strings; hashing them to u64 lets us reuse the same binary-search routine and only touch the string pool on hash hits. Expected collision rate at 5,069 entries with XXHash64 is essentially zero.
- **Single records region addressed by u32 offset.** All indexes point into the same records blob — one record, many index entries.
- **Enum bytes for `mediaType`/`sourceType`.** Current code only switches on `"movie"` / `"series"` and `"tv"` / `""`; full string round-trip is unnecessary. Decoder hands back canonical strings.
- **Fixed-stride range/explicitMap sub-arrays.** Keeps episode-record decode cheap and avoids nested varint chains.

**Estimated size: ~5–6 MiB.** Headroom for asset growth (u32 record-offset addresses ~4 B records).

## 6. Build pipeline

### New Gradle subproject

```
tools/
└── anime-id-map-encoder/        ← new :tools:anime-id-map-encoder
    ├── build.gradle.kts         (kotlin-jvm, depends on moshi + okio)
    └── src/main/kotlin/com/nexio/tools/animeidmap/
        ├── Main.kt                            (CLI entry)
        ├── AnimeIdMapBinaryEncoder.kt        (~400 LOC)
        ├── StringPoolBuilder.kt              (~80 LOC, dedup'd append)
        ├── SortedIndexBuilder.kt             (~120 LOC, per-kind builders)
        └── VarintWriter.kt                   (~40 LOC)
```

`buildSrc` is not used: it would recompile on every encoder change and slow every developer's incremental build. A subproject is built only when its own sources change and produces a cached JAR.

### Gradle task wiring

```kotlin
// app/build.gradle.kts (sketch)
val generateAnimeIdMapBinary by tasks.registering(JavaExec::class) {
    description = "Encode nexio-anime-map-v1.json into nexio-anime-map-v1.bin"
    group = "anime-id-map"

    val jsonAsset = layout.projectDirectory.file(
        "src/main/assets/anime/nexio-anime-map-v1.json"
    )
    val binAsset = layout.projectDirectory.file(
        "src/main/assets/anime/nexio-anime-map-v1.bin"
    )

    inputs.file(jsonAsset)
    outputs.file(binAsset)

    classpath = configurations["animeIdMapEncoderRuntime"]
    mainClass.set("com.nexio.tools.animeidmap.MainKt")
    args(jsonAsset.asFile.absolutePath, binAsset.asFile.absolutePath)
}

tasks.named("preBuild") { dependsOn(generateAnimeIdMapBinary) }
```

A hidden `animeIdMapEncoderRuntime` configuration declares the encoder subproject as a dependency without leaking it into the app's runtime classpath.

### Encoder pipeline (deterministic, single-pass)

1. Stream-parse input JSON (Moshi + okio source — same pattern as the runtime did before this migration).
2. Walk `identityRecordsByKitsu` in iteration order; pre-compute encoded sizes to allocate stable u32 `recordOffset` values; intern all string fields (`imdb`, `evidence[]`) into `StringPoolBuilder`.
3. Walk `episodeMappingsByAnidb`, append after identity records.
4. For each of 8 indexes: decode JSON key → u64 (numeric parse) or XXHash64 (imdb); map JSON value (kitsu string) → `recordOffset` via the record table; collect into `List<Entry>`, sort by key, write fixed-stride bytes.
5. Append `byAnidbEpisode` index from the episode pass.
6. Write header last with final offsets; atomically rename `tempFile` → `binAsset` (matches CLAUDE.md rule #3 streaming-write pattern).

### Determinism guarantee

Identical input JSON → byte-identical `.bin`. The committed `.bin` is reviewable in git as a stable artifact; any unexpected diff signals an encoder change, not nondeterminism. Order-stable iteration over `LinkedHashMap`, sort-stable index entries, no timestamps in body (`generatedAtEpoch` comes from the input JSON's `generatedAt` field).

### Why commit the `.bin`

Two options were considered:

- **(chosen) Commit `.bin`.** Pro: reproducible builds without running the encoder; no `preBuild` JVM exec on CI/dev cycles; git review surfaces every encoder change. Con: `.bin` adds ~5–6 MiB to the repo. The asset is updated once every several months (current `generatedAt: 2026-05-06`), so this is fine.
- **Generate at build time only.** Pro: ~5 MiB saved in git. Con: every fresh checkout / CI build runs the encoder; up-to-date check has to be perfect.

## 7. Runtime API + reader

### Public API (unchanged)

`AnimeIdMappingService`'s 9 read methods (`resolveKitsuId`, `recordForKitsuId`, `recordForAnidbId`, `episodeMappingForAnidb`, `recordsForImdbId`, `isAnimeImdbId`, `resolveProviderIdsForKitsu`, `allSeriesRecordsSharingTvdb`, plus `warmUp`) keep exact signatures.

Only the constructor changes — the `assetProvider: () -> AnimeIdMapAsset` parameter is replaced with `reader: AnimeIdMapBinaryReader`. All ~6 caller files (`KitsuMetadataService`, `KitsuRailFranchiseGrouper`, `TrackingScrobbleService`, `TraktProgressService`, `AnimeIdentityIndex`, `DefaultAnimeSeasonProjectionResolver`) remain untouched.

```kotlin
@Singleton
class AnimeIdMappingService @Inject constructor(
    private val reader: AnimeIdMapBinaryReader,
) {
    fun warmUp() { reader.ensureOpen() }

    fun resolveKitsuId(id: AnimeStremioId, mediaKind: ContentMediaKind): String? =
        when (id.source) {
            AnimeIdSource.KITSU   -> if (reader.containsKitsu(id.value)) id.value else id.value
            AnimeIdSource.MAL     -> reader.lookupSingle(IndexKind.BY_MAL, id.value)
            AnimeIdSource.ANILIST -> reader.lookupSingle(IndexKind.BY_ANILIST, id.value)
            AnimeIdSource.ANIDB   -> reader.lookupSingle(IndexKind.BY_ANIDB, id.value)
            AnimeIdSource.TVDB    -> reader.lookupMultiFirst(IndexKind.BY_TVDB, id.value)
            AnimeIdSource.IMDB    -> resolveImdbKitsuId(id.value, mediaKind)
            AnimeIdSource.TMDB    -> when (mediaKind) {
                ContentMediaKind.MOVIE  -> reader.lookupSingle(IndexKind.BY_TMDB_MOVIE, id.value)
                ContentMediaKind.SERIES -> reader.lookupMultiFirst(IndexKind.BY_TMDB_TV, id.value)
            }
        }
    // …other methods delegate similarly.
}
```

`AnimeIdMapRecord` and `AnimeEpisodeMappingRecord` data classes are **kept** as return types. Decoding allocates one short-lived `AnimeIdMapRecord` per call (~200 bytes) — acceptable for current call sites (rail building, scrobble, season projection; bounded fan-out, not in tight per-recomposition loops).

### `AnimeIdMapBinaryReader` (new, ~350 LOC)

```kotlin
@Singleton
class AnimeIdMapBinaryReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var state: State = State.Closed
    private val openLock = Any()

    private sealed interface State {
        object Closed : State
        object Failed : State
        data class Open(
            val header: ByteBuffer,
            val indexTable: ByteBuffer,
            val indexRegion: ByteBuffer,
            val multiListPool: ByteBuffer,
            val records: ByteBuffer,
            val stringPool: ByteBuffer,
        ) : State
    }

    fun ensureOpen() {
        if (state !== State.Closed) return
        synchronized(openLock) {
            if (state !== State.Closed) return
            state = openInternal()
        }
    }

    private fun openInternal(): State { /* maps file, validates header */ }
    private fun ensureBinaryOnDisk(): File? { /* one-time asset → filesDir copy */ }

    fun containsKitsu(value: String): Boolean
    fun lookupSingle(kind: IndexKind, value: String): String?
    fun lookupMultiFirst(kind: IndexKind, value: String): String?
    fun recordOffsetsForTvdb(value: String): IntArray
    fun recordForKitsu(value: String): AnimeIdMapRecord?
    fun recordsForImdbId(value: String): List<AnimeIdMapRecord>
    fun episodeMappingForAnidb(value: String): AnimeEpisodeMappingRecord?
}
```

### Concurrency model

- `ensureOpen()` is idempotent and double-checked-locked. `state` is `@Volatile` so the fast path is a plain volatile read with no lock acquisition.
- After open, the `Open` instance is immutable. All buffer slices are read via **absolute-indexed** methods (`getLong(offset)`, `get(offset)`) — never the buffer cursor — so **no synchronization is needed** for concurrent reads. Absolute-indexed `ByteBuffer` reads are thread-safe by spec.
- `warmUp()` from `NexioApplication.onCreate()` still drives the one-time disk copy + mmap on `Dispatchers.IO`. Same call site, same semantics.

### Allocation profile per call (rule #5 alignment)

| Method | Allocations |
|---|---|
| `resolveKitsuId` (single-value branches) | 0 if not found; 1 short String (the kitsu numeric ID) if found |
| `resolveKitsuId` (TVDB/TMDB_TV/IMDB) | 1 short String if found |
| `recordForKitsuId` | 1 `AnimeIdMapRecord`; evidence lazily decoded on first `record.evidence` access |
| `episodeMappingForAnidb` | 1 `AnimeEpisodeMappingRecord` + nested ranges/explicit-maps lists |
| `allSeriesRecordsSharingTvdb` | up to ~10 `AnimeIdMapRecord` (typical TVDB-shared anime have 1–3 entries) |

Short-lived young-gen allocations on demand, replacing the **permanent** retention of all 13k records. Not the same problem rule #5 addresses (Compose recomposition churn) — these methods aren't called per recomposition.

### Lazy evidence

Evidence strings (`["fribb.kitsu=265", …]`) account for ~30% of record bytes and are only consulted by debugging/test code (zero production reads). The record stores `_evidenceOffset: Int` and `_evidenceCount: Int`; `evidence: List<String>` is a `by lazy` delegate that calls back into the reader's string-pool decoder. Saves cold-path decode work and avoids per-record evidence allocation for the common case.

## 8. Error handling and telemetry

| Failure | Detection | Response |
|---|---|---|
| Asset missing from APK | `assets.open(ASSET_PATH)` throws `FileNotFoundException` | Log warn, `state = Failed`, all reads return `null`/`emptyList()`. Matches current `EMPTY_ASSET` behavior. |
| Asset copy fails | `FileOutputStream` or `Files.move` throws | Same as above. Tmp file cleaned up. Retried on next `warmUp()`. |
| `.bin` exists but truncated/corrupted | `FileChannel.map` succeeds; `parseHeaderAndSlice` validates magic + length | Delete on-disk copy, retry from asset once. Second failure → `Failed`. |
| Magic / schemaVersion mismatch | Header parse | Same as corrupted: delete + recopy. APK asset is canonical; corruption is the only realistic cause on a non-tampered device. |
| Index points past records region | First lookup that touches the bad offset | Catch in `recordForOffset`, log once with rate-limit, return `null` for that record. Other lookups unaffected. |
| `MappedByteBuffer` OOM (very low-end TV box) | `FileChannel.map` throws `IOException` | `state = Failed`. Logged with native heap stats. |

### Recovery design rationale

- **Delete-and-recopy on corruption** matches CLAUDE.md rule #3: atomic rename is all-or-nothing, but the *source* (APK asset) is the immutable truth. Recopy is always safe.
- **Per-record swallow-and-log** prevents a single bad byte from crashing the app. Mirrors the existing `EMPTY_ASSET` "degrade silently rather than ANR" stance.
- **No retry loops.** All recovery is bounded.

### Telemetry

Logcat tag `AnimeIdMappingService` (existing) + new tag `AnimeIdMapBinaryReader`. Single-line structured logs:

```
I AnimeIdMapBinaryReader: open ok schema=1 records=12960 sizeBytes=5847296 openMs=42
W AnimeIdMapBinaryReader: copy_failed source=asset reason=ENOSPC
W AnimeIdMapBinaryReader: header_invalid magic=0x… schema=… → recopy
W AnimeIdMapBinaryReader: header_invalid_after_recopy → failed
W AnimeIdMapBinaryReader: record_decode_failed offset=0x… (rate-limited 1/min)
```

No new analytics pipeline. Consistent with how `MetadataDiskCacheStore` reports failures.

### Deliberate omissions

- No CRC in file header (APK signing covers integrity; corruption is rare and already structurally caught).
- No background re-validation (mmap + immutable state means there's nothing to re-validate).
- No mutex-guarded close/reopen (mmap stays live as long as the buffer is reachable).
- No lookup-latency metrics (binary search on 13k entries is unconditionally sub-microsecond).

## 9. Testing

### Encoder unit tests (`tools/anime-id-map-encoder/src/test/`)

- Round-trip property: `encode(JSON) → decode → equality` with the original Moshi-parsed model. Catches every field-mapping mistake in one assertion.
- Deterministic-output test: encode same JSON twice; assert byte-identical output. Locks down the committable-artifact invariant.
- Edge cases per record kind: record with no optional fields, record with all optional fields, record with imdb hash collision (synthesize via fixture), episode record with empty ranges, multi-value index with 0/1/many values.

### Reader unit tests (`app/src/test/java/com/nexio/tv/core/anime/AnimeIdMapBinaryReaderTest.kt`)

- Open + lookup against a small fixture `.bin` checked into `app/src/test/resources/anime/` (built once by a separate `:tools:anime-id-map-encoder` test task using hand-written minimal JSON).
- All 9 `IndexKind` lookups: present key, absent key, multi-value first-of-many, imdb collision.
- Lazy evidence: assert `record.evidence` returns expected list on access, and that the record has no inflated `List<String>` reference before access.
- Failure paths: truncated file → `State.Failed`; bad magic → recopy path via fake `Context.assets`.

### Service-level tests (`AnimeIdMappingServiceTest.kt`, existing)

- Keep current test cases as the contract regression gate. Swap the test constructor's `assetProvider` factory for a real `AnimeIdMapBinaryReader` pointed at the fixture `.bin`.
- Add one new test: `recordForKitsuId(missingId)` returns `null` without allocating an `AnimeIdMapRecord` (assertable via small allocation-counting helper). Locks rule-#5-friendly behavior.

### Integration sanity (manual, once, post-migration)

- Capture heap dump after profile selection + 30 s Modern Home soak (per CLAUDE.md rule #8).
- Run `heaptrail`; assert:
  - Zero `AnimeIdMapRecord` instances retained (transient young-gen instances allowed).
  - Zero `AnimeIdMapIndexes` instance.
  - One `AnimeIdMapBinaryReader` with three `DirectByteBuffer`s; off-heap region ~5–6 MiB.
- Expected delta: **≈ −14 MiB Java heap**.

## 10. Migration plan

Single PR — `AnimeIdMappingService`'s public API doesn't change.

```
Phase 0  (done — this brainstorm)
  Design doc landed.

Phase 1 — Encoder
  • Add tools/anime-id-map-encoder subproject.
  • Implement encoder + tests.
  • Run encoder against committed JSON; commit nexio-anime-map-v1.bin.
  • Wire generateAnimeIdMapBinary task; verify Gradle up-to-date check.
  • CI green.

Phase 2 — Reader + service swap
  • Add AnimeIdMapBinaryReader + tests.
  • Replace AnimeIdMappingService internals; KEEP all 9 public methods +
    return types byte-identical.
  • Delete AnimeIdMapAsset.identityRecordsByKitsu /
    episodeMappingsByAnidb / indexes fields — now unused. Keep
    AnimeIdMapRecord / AnimeEpisodeMappingRecord (return types).
  • Update Hilt module: bind AnimeIdMapBinaryReader instead of the
    asset-provider lambda.
  • Run full app test suite. Hand-test on Fire TV stick:
      adb force-stop → monkey → KEYCODE_DPAD_CENTER → 30s soak.
  • Capture heap dump, verify retention delta.

Phase 3 — Cleanup (same PR or follow-up)
  • Keep nexio-anime-map-v1.json in assets for provenance / diff review.
  • Remove unused Moshi-KSP-generated bindings for the deleted asset
    fields (ksp drops automatically).
```

### Rollback

If anything breaks on-device, the `Failed` state already degrades to "no anime ID resolution" — annoying but not crash-inducing. A revert of the swap commit fully restores prior behavior; encoder + reader subprojects are additive and can stay landed independently.

## 11. References

- `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt`
- `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMapAsset.kt`
- `app/src/main/assets/anime/nexio-anime-map-v1.json` (8.3 MiB, 12,960 records)
- CLAUDE.md hard rules #3, #5, #6
- Predecessor: cold-start ANR fix `45984efc7` (streamed JSON parse)
- Related streaming-write pattern: `HomeCatalogSnapshotStore.streamSnapshotToFile` (`bc7b5061a`)
