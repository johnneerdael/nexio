# AnimeIdMappingService mmap'd Off-Heap Binary Asset — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `AnimeIdMappingService`'s 15 MiB JVM-heap retention with a mmap'd compact-binary asset (~5–6 MiB off-heap, ~1 KiB on-heap), preserving the public API exactly.

**Architecture:** Build-time encoder produces deterministic `nexio-anime-map-v1.bin` from the existing `nexio-anime-map-v1.json`; runtime `AnimeIdMapBinaryReader` mmaps the file via `FileChannel.map(READ_ONLY)` and serves lookups via binary search over absolute-indexed `ByteBuffer` slices. The encoder lives in the existing `:tools:anime-mapping-generator` Kotlin-JVM subproject; the runtime additions live in `app/src/main/java/com/nexio/tv/core/anime/`.

**Tech Stack:** Kotlin (JVM-1.8 runtime / JVM-17 toolchain), Moshi (already present), Gradle JavaExec task, JUnit 4 (existing test runner). No new top-level dependencies — the encoder reuses Moshi; the reader uses `java.nio` only.

**Spec:** `docs/superpowers/specs/2026-05-11-anime-id-map-mmap-offheap-design.md`

**Project-pattern note:** The spec's section 6 shows wiring `generateAnimeIdMapBinary` into `:app:preBuild`. This plan instead follows the **existing project convention** (visible in `app/build.gradle.kts:checkAnimeMappingAsset`): the generator task is **explicit-invocation**, the committed `.bin` is verified by a `check`-side task that re-encodes to a temp file and asserts byte-identical. Same guarantee ("`.bin` never drifts from JSON"), matches the pattern already used for the JSON asset.

---

## File Structure

### New files

**Encoder (`tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/`)**
- `VarintWriter.kt` — varint u64 encoder (~40 LOC). Encode side only; decoder lives in the app.
- `StringPoolBuilder.kt` — append-with-dedup builder for the string pool (~80 LOC).
- `SortedIndexBuilder.kt` — per-index sorted-array builders (single-value, multi-value, IMDB) (~150 LOC).
- `AnimeIdMapBinaryEncoder.kt` — top-level orchestrator: reads `AnimeIdMapAsset` model, writes `.bin` (~300 LOC).
- `EncodeMain.kt` — CLI entry point for the Gradle task (~30 LOC).
- `BinaryFormat.kt` — shared constants (magic, schema version, record kinds, index slot indices, enum byte tables) — referenced by encoder; the reader has its own copy of the constants since the encoder subproject is build-time-only and not on the app's runtime classpath.

**Reader (`app/src/main/java/com/nexio/tv/core/anime/binary/`)**
- `BinaryFormat.kt` — copy of the constants above. Two copies is deliberate — keeps the app free of any encoder-subproject dependency; a single-source approach would couple the runtime classpath to the build-time tool.
- `VarintReader.kt` — varint u64 decoder over absolute-indexed `ByteBuffer` (~40 LOC).
- `IndexKind.kt` — enum naming the 9 slots (`BY_KITSU`, `BY_MAL`, `BY_ANILIST`, `BY_ANIDB`, `BY_TMDB_MOVIE`, `BY_TVDB`, `BY_TMDB_TV`, `BY_IMDB`, `BY_ANIDB_EPISODE`).
- `AnimeIdMapBinaryReader.kt` — `@Singleton` class with `ensureOpen`, asset→filesDir copy, header parse, all 9 lookup methods (~400 LOC).

**Tests**
- `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/VarintWriterTest.kt`
- `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/StringPoolBuilderTest.kt`
- `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/SortedIndexBuilderTest.kt`
- `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoderTest.kt`
- `app/src/test/java/com/nexio/tv/core/anime/binary/VarintReaderTest.kt`
- `app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt`
- `app/src/test/resources/anime/nexio-anime-map-v1-test.bin` (built once via a one-shot Gradle task, then committed)

### Modified files

- `app/src/main/assets/anime/` — gains `nexio-anime-map-v1.bin` (committed binary artifact).
- `app/build.gradle.kts` — register `generateAnimeIdMapBinary` + `checkAnimeIdMapBinary` tasks; wire `checkAnimeIdMapBinary` into `check`.
- `tools/anime-mapping-generator/build.gradle.kts` — no changes; new sources slot into existing source set.
- `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt` — replace `assetProvider: () -> AnimeIdMapAsset` constructor param with `reader: AnimeIdMapBinaryReader`; rewrite method bodies to delegate.
- `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMapAsset.kt` — delete `identityRecordsByKitsu`, `episodeMappingsByAnidb`, `indexes` fields and the wrapper class; keep `AnimeIdMapRecord`, `AnimeEpisodeMappingRecord`, `AnimeRangeRule`, `AnimeExplicitMap`, `ContentMediaKind` (still used as return types).
- `app/src/test/java/com/nexio/tv/core/anime/AnimeIdMappingServiceTest.kt` — update construction from lambda-asset to fixture-backed `AnimeIdMapBinaryReader`. Test cases unchanged.
- `app/src/test/java/com/nexio/tv/core/anime/AnimeIdMappingServiceImdbTest.kt` — same construction update.

### Untouched (verified by grep — current callers consume only the public API)

- `app/src/main/java/com/nexio/tv/NexioApplication.kt` — still calls `animeIdMappingService.warmUp()` unchanged.
- `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
- `app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt`
- `app/src/main/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndex.kt`
- `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt` (constructs an empty-asset instance locally — see Task 18)
- `app/src/main/java/com/nexio/tv/data/integration/railpreview/KitsuRailFranchiseGrouper.kt`

---

## Phase 1 — Encoder (build-time, in `:tools:anime-mapping-generator`)

### Task 1: VarintWriter

**Files:**
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/VarintWriter.kt`
- Test: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/VarintWriterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/VarintWriterTest.kt
package com.nexio.animemap.binary

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class VarintWriterTest {
    @Test
    fun `encodes zero as single byte`() {
        val out = ByteArrayOutputStream()
        val bytes = VarintWriter.writeULong(out, 0L)
        assertEquals(1, bytes)
        assertArrayEquals(byteArrayOf(0x00), out.toByteArray())
    }

    @Test
    fun `encodes 127 as single byte`() {
        val out = ByteArrayOutputStream()
        VarintWriter.writeULong(out, 127L)
        assertArrayEquals(byteArrayOf(0x7F), out.toByteArray())
    }

    @Test
    fun `encodes 128 as two bytes with continuation bit`() {
        val out = ByteArrayOutputStream()
        val bytes = VarintWriter.writeULong(out, 128L)
        assertEquals(2, bytes)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), out.toByteArray())
    }

    @Test
    fun `encodes max u32-like value`() {
        val out = ByteArrayOutputStream()
        VarintWriter.writeULong(out, 4_294_967_295L)
        // 0xFFFFFFFF -> 5 bytes: FF FF FF FF 0F
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F),
            out.toByteArray()
        )
    }

    @Test
    fun `rejects negative values`() {
        val out = ByteArrayOutputStream()
        try {
            VarintWriter.writeULong(out, -1L)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.VarintWriterTest"`
Expected: FAIL with "Unresolved reference: VarintWriter".

- [ ] **Step 3: Write minimal implementation**

```kotlin
// tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/VarintWriter.kt
package com.nexio.animemap.binary

import java.io.OutputStream

object VarintWriter {
    /** Encodes [value] as protobuf-style unsigned LEB128. Returns the byte count written. */
    fun writeULong(out: OutputStream, value: Long): Int {
        require(value >= 0L) { "VarintWriter only encodes non-negative values (got $value)" }
        var remaining = value
        var written = 0
        while (true) {
            val b = (remaining and 0x7FL).toInt()
            remaining = remaining ushr 7
            written++
            if (remaining == 0L) {
                out.write(b)
                return written
            }
            out.write(b or 0x80)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.VarintWriterTest"`
Expected: PASS, 5 tests run.

- [ ] **Step 5: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/VarintWriter.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/VarintWriterTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): add VarintWriter for binary encoder

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: StringPoolBuilder

**Files:**
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/StringPoolBuilder.kt`
- Test: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/StringPoolBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/StringPoolBuilderTest.kt
package com.nexio.animemap.binary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StringPoolBuilderTest {
    @Test
    fun `intern returns same offset for duplicate strings`() {
        val pool = StringPoolBuilder()
        val first = pool.intern("hello")
        val second = pool.intern("hello")
        assertEquals(first, second)
    }

    @Test
    fun `intern returns different offsets for different strings`() {
        val pool = StringPoolBuilder()
        val a = pool.intern("foo")
        val b = pool.intern("bar")
        assertNotEquals(a, b)
    }

    @Test
    fun `toByteArray contains varint-length-prefixed utf8`() {
        val pool = StringPoolBuilder()
        val helloOffset = pool.intern("hi")
        val bytes = pool.toByteArray()
        // "hi" -> varint length=2 then 'h' 'i'
        assertEquals(0, helloOffset)
        assertEquals(3, bytes.size)
        assertEquals(0x02, bytes[0].toInt())
        assertEquals('h'.code, bytes[1].toInt())
        assertEquals('i'.code, bytes[2].toInt())
    }

    @Test
    fun `empty string returns sentinel offset uint32 max`() {
        val pool = StringPoolBuilder()
        val offset = pool.intern("")
        assertEquals(0xFFFF_FFFFL.toInt(), offset)
    }

    @Test
    fun `null returns sentinel offset uint32 max`() {
        val pool = StringPoolBuilder()
        val offset = pool.intern(null)
        assertEquals(0xFFFF_FFFFL.toInt(), offset)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.StringPoolBuilderTest"`
Expected: FAIL with "Unresolved reference: StringPoolBuilder".

- [ ] **Step 3: Write minimal implementation**

```kotlin
// tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/StringPoolBuilder.kt
package com.nexio.animemap.binary

import java.io.ByteArrayOutputStream

class StringPoolBuilder {
    private val buffer = ByteArrayOutputStream()
    private val offsetByValue = HashMap<String, Int>()

    /**
     * Returns a u32 byte offset into the eventual pool, or [NULL_OFFSET]
     * if [value] is null or empty.
     */
    fun intern(value: String?): Int {
        if (value.isNullOrEmpty()) return NULL_OFFSET
        offsetByValue[value]?.let { return it }
        val offset = buffer.size()
        val utf8 = value.toByteArray(Charsets.UTF_8)
        VarintWriter.writeULong(buffer, utf8.size.toLong())
        buffer.write(utf8)
        offsetByValue[value] = offset
        return offset
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()

    companion object {
        /** u32 sentinel (0xFFFFFFFF) meaning "no string". */
        const val NULL_OFFSET: Int = -1
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.StringPoolBuilderTest"`
Expected: PASS, 5 tests run.

- [ ] **Step 5: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/StringPoolBuilder.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/StringPoolBuilderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): add StringPoolBuilder with dedup

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: BinaryFormat constants (encoder copy)

**Files:**
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/BinaryFormat.kt`

This is constants-only — no test, exercised transitively by later tests.

- [ ] **Step 1: Create the constants file**

```kotlin
// tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/BinaryFormat.kt
package com.nexio.animemap.binary

internal object BinaryFormat {
    // Header layout
    const val MAGIC: Int = 0x4E584149  // 'N','X','A','I' big-endian — written as bytes below
    val MAGIC_BYTES: ByteArray = byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte())
    const val SCHEMA_VERSION: Int = 1
    const val HEADER_SIZE: Int = 64
    const val INDEX_DESCRIPTOR_SIZE: Int = 24      // u32 kind | u32 stride | u64 offset | u64 count
    const val INDEX_TABLE_SIZE: Int = INDEX_DESCRIPTOR_SIZE * 9   // 9 slots = 216 bytes

    // Index slot indices (stable, must match runtime reader's IndexKind ordinal)
    const val SLOT_BY_KITSU: Int = 0
    const val SLOT_BY_MAL: Int = 1
    const val SLOT_BY_ANILIST: Int = 2
    const val SLOT_BY_ANIDB: Int = 3
    const val SLOT_BY_TMDB_MOVIE: Int = 4
    const val SLOT_BY_TVDB: Int = 5
    const val SLOT_BY_TMDB_TV: Int = 6
    const val SLOT_BY_IMDB: Int = 7
    const val SLOT_BY_ANIDB_EPISODE: Int = 8

    // Index kind discriminator (in descriptor's first u32)
    const val KIND_U64_SINGLE: Int = 1     // [u64 key | u32 recordOffset] stride=12
    const val KIND_U64_MULTI: Int = 2      // [u64 key | u32 listOff | u32 listLen] stride=16
    const val KIND_IMDB: Int = 3           // [u64 hash | u32 strOff | u32 listOff | u32 listLen] stride=20

    const val STRIDE_U64_SINGLE: Int = 12
    const val STRIDE_U64_MULTI: Int = 16
    const val STRIDE_IMDB: Int = 20

    // Record kinds (first byte of each record)
    const val RECORD_KIND_IDENTITY: Byte = 0
    const val RECORD_KIND_EPISODE: Byte = 1

    // Presence-bit positions for identity records (presenceBits byte)
    const val P_MAL: Int = 1 shl 0
    const val P_ANILIST: Int = 1 shl 1
    const val P_ANIDB: Int = 1 shl 2
    const val P_TMDB: Int = 1 shl 3
    const val P_TVDB: Int = 1 shl 4
    const val P_IMDB: Int = 1 shl 5
    const val P_MEDIA_TYPE: Int = 1 shl 6
    const val P_SOURCE_TYPE: Int = 1 shl 7

    // presenceBits2 byte
    const val P2_TVDB_SEASON: Int = 1 shl 0
    const val P2_TMDB_SEASON: Int = 1 shl 1
    const val P2_TVDB_EP_OFFSET: Int = 1 shl 2
    const val P2_TMDB_EP_OFFSET: Int = 1 shl 3
    const val P2_HAS_MAPPING_RULES: Int = 1 shl 4
    const val P2_HAS_EVIDENCE: Int = 1 shl 5

    // Enum tables
    val MEDIA_TYPE_TABLE: List<String> = listOf("movie", "series", "other")
    val SOURCE_TYPE_TABLE: List<String> = listOf("tv", "ova", "ona", "movie", "music", "special", "other")
    val PROVIDER_TABLE: List<String> = listOf("TVDB", "TMDB")  // for episode ranges/explicit maps targetProvider

    fun mediaTypeByte(value: String?): Byte = when (value?.lowercase()) {
        "movie" -> 0
        "series" -> 1
        else -> 2
    }

    fun sourceTypeByte(value: String?): Byte = when (value?.lowercase()) {
        "tv" -> 0
        "ova" -> 1
        "ona" -> 2
        "movie" -> 3
        "music" -> 4
        "special" -> 5
        else -> 6
    }

    fun providerByte(value: String?): Byte = when (value?.uppercase()) {
        "TVDB" -> 0
        "TMDB" -> 1
        else -> error("unknown targetProvider: $value")
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :tools:anime-mapping-generator:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/BinaryFormat.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): add binary format constants for encoder

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: SortedIndexBuilder — single-value U64 indexes

**Files:**
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/SortedIndexBuilder.kt`
- Test: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/SortedIndexBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/SortedIndexBuilderTest.kt
package com.nexio.animemap.binary

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SortedIndexBuilderTest {
    @Test
    fun `single-value index emits sorted fixed-stride entries`() {
        val builder = SortedIndexBuilder.Single()
        builder.add(key = 300L, recordOffset = 50)
        builder.add(key = 100L, recordOffset = 10)
        builder.add(key = 200L, recordOffset = 20)
        val bytes = builder.toByteArray()

        // 3 entries * 12 bytes = 36 bytes total
        assertEquals(36, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Sorted ascending by key: 100, 200, 300
        assertEquals(100L, buf.getLong(0))
        assertEquals(10, buf.getInt(8))
        assertEquals(200L, buf.getLong(12))
        assertEquals(20, buf.getInt(20))
        assertEquals(300L, buf.getLong(24))
        assertEquals(50, buf.getInt(32))
    }

    @Test
    fun `single-value index rejects duplicate keys`() {
        val builder = SortedIndexBuilder.Single()
        builder.add(key = 42L, recordOffset = 1)
        try {
            builder.add(key = 42L, recordOffset = 2)
            builder.toByteArray()
            error("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.SortedIndexBuilderTest"`
Expected: FAIL with "Unresolved reference: SortedIndexBuilder".

- [ ] **Step 3: Write minimal implementation**

```kotlin
// tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/SortedIndexBuilder.kt
package com.nexio.animemap.binary

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface SortedIndexBuilder {
    fun toByteArray(): ByteArray

    /** [u64 key | u32 recordOffset], stride=12 */
    class Single : SortedIndexBuilder {
        private val entries = ArrayList<LongArray>()  // [key, recordOffset]

        fun add(key: Long, recordOffset: Int) {
            entries.add(longArrayOf(key, recordOffset.toLong() and 0xFFFFFFFFL))
        }

        override fun toByteArray(): ByteArray {
            entries.sortBy { it[0] }
            checkNoDuplicates()
            val buf = ByteBuffer.allocate(entries.size * BinaryFormat.STRIDE_U64_SINGLE)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (i in entries.indices) {
                val e = entries[i]
                buf.putLong(e[0])
                buf.putInt(e[1].toInt())
            }
            return buf.array()
        }

        private fun checkNoDuplicates() {
            for (i in 1 until entries.size) {
                check(entries[i][0] != entries[i - 1][0]) {
                    "SortedIndexBuilder.Single: duplicate key ${entries[i][0]}"
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.SortedIndexBuilderTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/SortedIndexBuilder.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/SortedIndexBuilderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): add SortedIndexBuilder.Single (u64 -> record offset)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: SortedIndexBuilder.Multi (multi-value index)

**Files:**
- Modify: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/SortedIndexBuilder.kt`
- Modify: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/SortedIndexBuilderTest.kt`

- [ ] **Step 1: Add failing test**

Append to `SortedIndexBuilderTest.kt`:

```kotlin
    @Test
    fun `multi-value index emits sorted entries plus pool of u32 record offsets`() {
        val builder = SortedIndexBuilder.Multi()
        builder.add(key = 200L, recordOffsets = intArrayOf(7, 8, 9))
        builder.add(key = 100L, recordOffsets = intArrayOf(1, 2))
        val (indexBytes, poolBytes) = builder.build()

        // index: 2 entries * 16 bytes = 32
        assertEquals(32, indexBytes.size)
        val ib = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN)
        // entry 0: key=100, listOffset=0, listLen=2
        assertEquals(100L, ib.getLong(0))
        assertEquals(0, ib.getInt(8))
        assertEquals(2, ib.getInt(12))
        // entry 1: key=200, listOffset=8 (after first list of 2 * u32 = 8 bytes), listLen=3
        assertEquals(200L, ib.getLong(16))
        assertEquals(8, ib.getInt(24))
        assertEquals(3, ib.getInt(28))

        // pool: 5 u32 = 20 bytes total
        assertEquals(20, poolBytes.size)
        val pb = ByteBuffer.wrap(poolBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, pb.getInt(0))
        assertEquals(2, pb.getInt(4))
        assertEquals(7, pb.getInt(8))
        assertEquals(8, pb.getInt(12))
        assertEquals(9, pb.getInt(16))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.SortedIndexBuilderTest"`
Expected: FAIL with "Unresolved reference: Multi".

- [ ] **Step 3: Implement**

Append to `SortedIndexBuilder.kt` inside the `sealed interface` body (after the `Single` class):

```kotlin
    /** [u64 key | u32 listOffset | u32 listLen], stride=16, plus shared u32 pool. */
    class Multi {
        private val entries = ArrayList<Pair<Long, IntArray>>()

        fun add(key: Long, recordOffsets: IntArray) {
            entries.add(key to recordOffsets)
        }

        fun build(): Pair<ByteArray, ByteArray> {
            entries.sortBy { it.first }
            for (i in 1 until entries.size) {
                check(entries[i].first != entries[i - 1].first) {
                    "SortedIndexBuilder.Multi: duplicate key ${entries[i].first}"
                }
            }
            val indexBuf = ByteBuffer.allocate(entries.size * BinaryFormat.STRIDE_U64_MULTI)
                .order(ByteOrder.LITTLE_ENDIAN)
            val poolBuf = ByteBuffer.allocate(entries.sumOf { it.second.size } * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            var poolOffset = 0
            for (i in entries.indices) {
                val (key, offsets) = entries[i]
                indexBuf.putLong(key)
                indexBuf.putInt(poolOffset)
                indexBuf.putInt(offsets.size)
                for (j in offsets.indices) poolBuf.putInt(offsets[j])
                poolOffset += offsets.size * 4
            }
            return indexBuf.array() to poolBuf.array()
        }
    }
```

Also remove the now-redundant outer `toByteArray(): ByteArray` from the sealed-interface declaration (it doesn't fit `Multi`'s 2-output shape). Replace the interface with a marker:

```kotlin
sealed interface SortedIndexBuilder
```

Make `Single` and `Multi` both implement it (cosmetic — they don't share methods).

- [ ] **Step 4: Run all SortedIndexBuilder tests**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.SortedIndexBuilderTest"`
Expected: PASS, 3 tests total.

- [ ] **Step 5: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/SortedIndexBuilder.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/SortedIndexBuilderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): add SortedIndexBuilder.Multi (u64 -> list of offsets + pool)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: SortedIndexBuilder.Imdb (string-key index via XXHash64)

**Files:**
- Modify: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/SortedIndexBuilder.kt`
- Modify: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/SortedIndexBuilderTest.kt`
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/StringHash.kt`

We use the **FNV-1a 64-bit** hash for IMDB keys — pure-Kotlin, no external dep, deterministic across JVMs. (XXHash64 would be marginally better but pulls a library.)

- [ ] **Step 1: Add StringHash file**

```kotlin
// tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/StringHash.kt
package com.nexio.animemap.binary

/** FNV-1a 64-bit hash. Deterministic, allocation-free, pure JVM. */
object StringHash {
    private const val OFFSET_BASIS: Long = -3750763034362895579L  // 0xCBF29CE484222325
    private const val PRIME: Long = 0x00000100000001B3L

    fun hash64(value: String): Long {
        var h = OFFSET_BASIS
        // UTF-8 bytes are the canonical hash input. For IMDB IDs ("tt0000000")
        // every char is single-byte ASCII so we can use chars directly.
        for (i in value.indices) {
            val b = value[i].code and 0xFF
            h = (h xor b.toLong()) * PRIME
        }
        return h
    }
}
```

- [ ] **Step 2: Add failing test for Imdb builder**

Append to `SortedIndexBuilderTest.kt`:

```kotlin
    @Test
    fun `imdb index emits sorted-by-hash entries with stringPool offsets`() {
        val pool = StringPoolBuilder()
        val builder = SortedIndexBuilder.Imdb(pool)
        builder.add(imdb = "tt0286390", recordOffsets = intArrayOf(100))
        builder.add(imdb = "tt5626028", recordOffsets = intArrayOf(200, 201))
        val (indexBytes, poolBytes) = builder.build()

        assertEquals(2 * 20, indexBytes.size)
        val ib = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN)
        // Verify sorted by hash
        val h0 = ib.getLong(0)
        val h1 = ib.getLong(20)
        assert(h0 < h1) { "expected hash-sorted: $h0 < $h1" }
        // Verify each entry's strOffset points at the right utf8 in the pool
        val sp = pool.toByteArray()
        for (i in 0..1) {
            val baseOff = i * 20
            val strOff = ib.getInt(baseOff + 8)
            // pool: [varint len | utf8 ...]
            assertEquals(9L, decodeVarint(sp, strOff))  // both "tt0286390" / "tt5626028" are 9 bytes
        }
    }

    private fun decodeVarint(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        var shift = 0
        var i = offset
        while (true) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.SortedIndexBuilderTest"`
Expected: FAIL with "Unresolved reference: Imdb".

- [ ] **Step 4: Implement**

Append to `SortedIndexBuilder.kt` (inside the sealed-interface body file, alongside `Single` / `Multi`):

```kotlin
    /**
     * [u64 keyHash | u32 stringPoolOffset | u32 listOffset | u32 listLen], stride=20.
     * Sorted by keyHash. Collisions resolved at read time by comparing the
     * string at stringPoolOffset within the hash-equal run.
     */
    class Imdb(private val stringPool: StringPoolBuilder) {
        private data class Entry(val hash: Long, val key: String, val offsets: IntArray)
        private val entries = ArrayList<Entry>()

        fun add(imdb: String, recordOffsets: IntArray) {
            entries.add(Entry(StringHash.hash64(imdb), imdb, recordOffsets))
        }

        fun build(): Pair<ByteArray, ByteArray> {
            // Stable sort: primary = hash, secondary = key (so collisions are deterministic)
            entries.sortWith(compareBy({ it.hash }, { it.key }))
            val indexBuf = ByteBuffer.allocate(entries.size * BinaryFormat.STRIDE_IMDB)
                .order(ByteOrder.LITTLE_ENDIAN)
            val poolBuf = ByteBuffer.allocate(entries.sumOf { it.offsets.size } * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            var listPoolOffset = 0
            for (i in entries.indices) {
                val e = entries[i]
                indexBuf.putLong(e.hash)
                indexBuf.putInt(stringPool.intern(e.key))
                indexBuf.putInt(listPoolOffset)
                indexBuf.putInt(e.offsets.size)
                for (j in e.offsets.indices) poolBuf.putInt(e.offsets[j])
                listPoolOffset += e.offsets.size * 4
            }
            return indexBuf.array() to poolBuf.array()
        }
    }
```

- [ ] **Step 5: Run all SortedIndexBuilder tests**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.SortedIndexBuilderTest"`
Expected: PASS, 4 tests total.

- [ ] **Step 6: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/SortedIndexBuilder.kt \
        tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/StringHash.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/SortedIndexBuilderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): add SortedIndexBuilder.Imdb + FNV-1a StringHash

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: AnimeIdMapBinaryEncoder — record region encoding

**Files:**
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoder.kt`
- Create: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoderTest.kt`

This task focuses on the **record encoder** alone — the full file-layout encoder comes in Task 8. We verify by round-tripping records through a local decoder helper in the test.

- [ ] **Step 1: Write the failing test**

```kotlin
// tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoderTest.kt
package com.nexio.animemap.binary

import com.nexio.tv.core.anime.AnimeIdMapRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class AnimeIdMapBinaryEncoderTest {
    @Test
    fun `identity record round-trips through writer + manual reader`() {
        val pool = StringPoolBuilder()
        val out = ByteArrayOutputStream()
        val rec = AnimeIdMapRecord(
            kitsu = "265",
            mal = "290",
            anilist = "290",
            anidb = "1",
            tmdb = "26209",
            tvdb = "72025",
            imdb = "tt0286390",
            mediaType = "series",
            sourceType = "TV",
            tvdbSeason = "1",
            tmdbSeason = "1",
            evidence = listOf("fribb.kitsu=265", "fribb.tvdb=72025")
        )
        val bytesWritten = AnimeIdMapBinaryEncoder.writeIdentityRecord(out, rec, pool)
        val bytes = out.toByteArray()
        assertEquals(bytesWritten, bytes.size)
        // recordKind byte
        assertEquals(BinaryFormat.RECORD_KIND_IDENTITY, bytes[0])
        // Decode using the same helper the reader will use
        val decoded = TestRecordDecoder(bytes, pool.toByteArray()).decodeIdentityAt(0)
        assertEquals(rec.kitsu, decoded.kitsu)
        assertEquals(rec.mal, decoded.mal)
        assertEquals(rec.anilist, decoded.anilist)
        assertEquals(rec.anidb, decoded.anidb)
        assertEquals(rec.tmdb, decoded.tmdb)
        assertEquals(rec.tvdb, decoded.tvdb)
        assertEquals(rec.imdb, decoded.imdb)
        assertEquals(rec.mediaType, decoded.mediaType)
        assertEquals(rec.sourceType?.lowercase(), decoded.sourceType?.lowercase())
        assertEquals(rec.tvdbSeason, decoded.tvdbSeason)
        assertEquals(rec.tmdbSeason, decoded.tmdbSeason)
        assertEquals(rec.evidence, decoded.evidence)
    }

    @Test
    fun `identity record with mostly-null fields is compact`() {
        val pool = StringPoolBuilder()
        val out = ByteArrayOutputStream()
        val rec = AnimeIdMapRecord(kitsu = "99999")
        val bytesWritten = AnimeIdMapBinaryEncoder.writeIdentityRecord(out, rec, pool)
        // kind(1) + presence(1) + presence2(1) + varint kitsuId(1 for 99999<128? actually 99999 needs 3 bytes)
        // 99999 = 0x1869F -> 3 varint bytes (0x9F 0x8D 0x06)
        assertEquals(6, bytesWritten)
    }
}

/** Test-only decoder mirroring what AnimeIdMapBinaryReader will implement. */
private class TestRecordDecoder(private val records: ByteArray, private val stringPool: ByteArray) {
    fun decodeIdentityAt(offset: Int): AnimeIdMapRecord {
        var p = offset
        check(records[p].toInt() == 0) { "expected identity record at $p" }
        p += 1
        val presence = records[p].toInt() and 0xFF; p += 1
        val presence2 = records[p].toInt() and 0xFF; p += 1
        val (kitsu, after1) = readVarintNumeric(records, p); p = after1
        val mal = if (presence and BinaryFormat.P_MAL != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val anilist = if (presence and BinaryFormat.P_ANILIST != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val anidb = if (presence and BinaryFormat.P_ANIDB != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val tmdb = if (presence and BinaryFormat.P_TMDB != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val tvdb = if (presence and BinaryFormat.P_TVDB != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val imdb = if (presence and BinaryFormat.P_IMDB != 0) { val off = readI32(records, p); p += 4; readPoolString(off) } else null
        val mediaType = if (presence and BinaryFormat.P_MEDIA_TYPE != 0) { val b = records[p].toInt(); p += 1; BinaryFormat.MEDIA_TYPE_TABLE[b] } else null
        val sourceType = if (presence and BinaryFormat.P_SOURCE_TYPE != 0) { val b = records[p].toInt(); p += 1; BinaryFormat.SOURCE_TYPE_TABLE[b] } else null
        val tvdbSeason = if (presence2 and BinaryFormat.P2_TVDB_SEASON != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val tmdbSeason = if (presence2 and BinaryFormat.P2_TMDB_SEASON != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val tvdbEpOff = if (presence2 and BinaryFormat.P2_TVDB_EP_OFFSET != 0) { val (v, n) = readVarintSigned(records, p); p = n; v } else null
        val tmdbEpOff = if (presence2 and BinaryFormat.P2_TMDB_EP_OFFSET != 0) { val (v, n) = readVarintSigned(records, p); p = n; v } else null
        val hasMappingRules = presence2 and BinaryFormat.P2_HAS_MAPPING_RULES != 0
        val evidence = if (presence2 and BinaryFormat.P2_HAS_EVIDENCE != 0) {
            val (count, n1) = readVarintNumeric(records, p); p = n1
            val list = ArrayList<String>(count.toInt())
            repeat(count.toInt()) {
                val off = readI32(records, p); p += 4
                list.add(readPoolString(off)!!)
            }
            list
        } else emptyList()
        return AnimeIdMapRecord(
            kitsu = kitsu.toString(), mal = mal?.toString(), anilist = anilist?.toString(),
            anidb = anidb?.toString(), tmdb = tmdb?.toString(), tvdb = tvdb?.toString(),
            imdb = imdb, mediaType = mediaType, sourceType = sourceType,
            tvdbSeason = tvdbSeason?.toString(), tmdbSeason = tmdbSeason?.toString(),
            tvdbEpisodeOffset = tvdbEpOff, tmdbEpisodeOffset = tmdbEpOff,
            hasMappingRules = hasMappingRules, evidence = evidence
        )
    }
    private fun readVarintNumeric(b: ByteArray, off: Int): Pair<Long, Int> {
        var result = 0L; var shift = 0; var i = off
        while (true) {
            val byte = b[i].toInt() and 0xFF; i++
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result to i
            shift += 7
        }
    }
    private fun readVarintSigned(b: ByteArray, off: Int): Pair<Int, Int> {
        val (raw, next) = readVarintNumeric(b, off)
        // zigzag
        val v = ((raw ushr 1).toInt()) xor (-(raw.toInt() and 1))
        return v to next
    }
    private fun readI32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)
    private fun readPoolString(off: Int): String? {
        if (off == -1) return null
        val (len, after) = readVarintNumeric(stringPool, off)
        return String(stringPool, after, len.toInt(), Charsets.UTF_8)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.AnimeIdMapBinaryEncoderTest"`
Expected: FAIL — `Unresolved reference: AnimeIdMapBinaryEncoder` and `Unresolved reference: AnimeIdMapRecord`.

For the `AnimeIdMapRecord` import to resolve, the test will need access to the data class. Add a build-graph note: the test references `com.nexio.tv.core.anime.AnimeIdMapRecord` which lives in `:app`. Pull a tiny model module out, or duplicate the data class into the encoder subproject?

**Decision:** introduce a `WireAnimeIdMapRecord` data class **inside the encoder subproject** (no Moshi annotations needed; the encoder's caller will construct it from any source). The app keeps its own `AnimeIdMapRecord` — they're shape-compatible but live in separate classpaths. Update the test accordingly.

Rewrite the failing import in the test:

```kotlin
import com.nexio.animemap.binary.WireAnimeIdMapRecord as AnimeIdMapRecord
```

And in the `decoded` assertions, construct `AnimeIdMapRecord` using the encoder-subproject's wire class.

- [ ] **Step 3: Add WireAnimeIdMapRecord + WireAnimeEpisodeMappingRecord (encoder subproject)**

```kotlin
// tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/Wire.kt
package com.nexio.animemap.binary

/** Encoder-side mirror of app's AnimeIdMapRecord. Kept separate to avoid coupling. */
data class WireAnimeIdMapRecord(
    val kitsu: String,
    val mal: String? = null,
    val anilist: String? = null,
    val anidb: String? = null,
    val tmdb: String? = null,
    val tvdb: String? = null,
    val imdb: String? = null,
    val mediaType: String? = null,
    val sourceType: String? = null,
    val tvdbSeason: String? = null,
    val tmdbSeason: String? = null,
    val tvdbEpisodeOffset: Int? = null,
    val tmdbEpisodeOffset: Int? = null,
    val hasMappingRules: Boolean = false,
    val evidence: List<String> = emptyList(),
)

data class WireAnimeRangeRule(
    val sourceSeason: Int,
    val startEpisode: Int,
    val endEpisode: Int?,
    val targetProvider: String,
    val targetSeason: Int,
    val offset: Int,
)

data class WireAnimeExplicitMap(
    val sourceSeason: Int,
    val sourceEpisode: Int,
    val targetProvider: String,
    val targetSeason: Int,
    val targetEpisode: Int,
)

data class WireAnimeEpisodeMappingRecord(
    val anidb: String,
    val name: String? = null,
    val tvdbSeriesId: String? = null,
    val tmdbTvId: String? = null,
    val ranges: List<WireAnimeRangeRule> = emptyList(),
    val explicitMaps: List<WireAnimeExplicitMap> = emptyList(),
    val evidence: List<String> = emptyList(),
)
```

(Replace `AnimeIdMapRecord` everywhere in the test with `WireAnimeIdMapRecord`.)

- [ ] **Step 4: Implement encoder's record writer**

```kotlin
// tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoder.kt
package com.nexio.animemap.binary

import java.io.OutputStream

object AnimeIdMapBinaryEncoder {

    fun writeIdentityRecord(
        out: OutputStream,
        record: WireAnimeIdMapRecord,
        stringPool: StringPoolBuilder,
    ): Int {
        val buf = java.io.ByteArrayOutputStream()
        // recordKind
        buf.write(BinaryFormat.RECORD_KIND_IDENTITY.toInt())
        // presence bits
        var presence = 0
        if (record.mal != null) presence = presence or BinaryFormat.P_MAL
        if (record.anilist != null) presence = presence or BinaryFormat.P_ANILIST
        if (record.anidb != null) presence = presence or BinaryFormat.P_ANIDB
        if (record.tmdb != null) presence = presence or BinaryFormat.P_TMDB
        if (record.tvdb != null) presence = presence or BinaryFormat.P_TVDB
        if (record.imdb != null) presence = presence or BinaryFormat.P_IMDB
        if (record.mediaType != null) presence = presence or BinaryFormat.P_MEDIA_TYPE
        if (record.sourceType != null) presence = presence or BinaryFormat.P_SOURCE_TYPE
        buf.write(presence)
        var presence2 = 0
        if (record.tvdbSeason != null) presence2 = presence2 or BinaryFormat.P2_TVDB_SEASON
        if (record.tmdbSeason != null) presence2 = presence2 or BinaryFormat.P2_TMDB_SEASON
        if (record.tvdbEpisodeOffset != null) presence2 = presence2 or BinaryFormat.P2_TVDB_EP_OFFSET
        if (record.tmdbEpisodeOffset != null) presence2 = presence2 or BinaryFormat.P2_TMDB_EP_OFFSET
        if (record.hasMappingRules) presence2 = presence2 or BinaryFormat.P2_HAS_MAPPING_RULES
        if (record.evidence.isNotEmpty()) presence2 = presence2 or BinaryFormat.P2_HAS_EVIDENCE
        buf.write(presence2)
        // kitsuId
        VarintWriter.writeULong(buf, record.kitsu.toLong())
        // optional numeric IDs
        record.mal?.let { VarintWriter.writeULong(buf, it.toLong()) }
        record.anilist?.let { VarintWriter.writeULong(buf, it.toLong()) }
        record.anidb?.let { VarintWriter.writeULong(buf, it.toLong()) }
        record.tmdb?.let { VarintWriter.writeULong(buf, it.toLong()) }
        record.tvdb?.let { VarintWriter.writeULong(buf, it.toLong()) }
        // imdb (string ref into pool)
        record.imdb?.let { writeI32LE(buf, stringPool.intern(it)) }
        // enums
        record.mediaType?.let { buf.write(BinaryFormat.mediaTypeByte(it).toInt() and 0xFF) }
        record.sourceType?.let { buf.write(BinaryFormat.sourceTypeByte(it).toInt() and 0xFF) }
        // season strings (numeric; non-numeric like "a" → encode as 0, hasMappingRules carries the special case flag)
        record.tvdbSeason?.let { VarintWriter.writeULong(buf, parseSeasonAsU64(it)) }
        record.tmdbSeason?.let { VarintWriter.writeULong(buf, parseSeasonAsU64(it)) }
        // signed offsets via zigzag
        record.tvdbEpisodeOffset?.let { VarintWriter.writeULong(buf, zigzag(it)) }
        record.tmdbEpisodeOffset?.let { VarintWriter.writeULong(buf, zigzag(it)) }
        // evidence
        if (record.evidence.isNotEmpty()) {
            VarintWriter.writeULong(buf, record.evidence.size.toLong())
            for (i in record.evidence.indices) {
                writeI32LE(buf, stringPool.intern(record.evidence[i]))
            }
        }
        val bytes = buf.toByteArray()
        out.write(bytes)
        return bytes.size
    }

    /**
     * Parse a tvdbSeason / tmdbSeason value as a numeric u64. Non-numeric
     * values like "a" (absolute-order season per scudlee) round-trip to 0
     * — the reader treats them as "unknown" and the record's `hasMappingRules`
     * flag signals there are explicit episode rules to consult instead.
     */
    private fun parseSeasonAsU64(value: String): Long = value.trim().toLongOrNull() ?: 0L

    private fun zigzag(value: Int): Long = ((value.toLong() shl 1) xor (value.toLong() shr 31))

    private fun writeI32LE(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
```

- [ ] **Step 5: Run encoder tests**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.AnimeIdMapBinaryEncoderTest"`
Expected: PASS, 2 tests.

- [ ] **Step 6: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoder.kt \
        tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/Wire.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): encode identity records (varint + presence bits + enum bytes)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: AnimeIdMapBinaryEncoder — episode records

**Files:**
- Modify: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoder.kt`
- Modify: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoderTest.kt`

- [ ] **Step 1: Add failing test for episode record round-trip**

Append to `AnimeIdMapBinaryEncoderTest.kt`:

```kotlin
    @Test
    fun `episode record round-trips`() {
        val pool = StringPoolBuilder()
        val out = ByteArrayOutputStream()
        val rec = WireAnimeEpisodeMappingRecord(
            anidb = "69",
            name = "One Piece",
            tvdbSeriesId = "81797",
            tmdbTvId = "37854",
            ranges = listOf(
                WireAnimeRangeRule(1, 1, 8, "TVDB", 1, 0),
                WireAnimeRangeRule(1, 892, 1085, "TVDB", 21, -891)
            ),
            explicitMaps = listOf(
                WireAnimeExplicitMap(0, 1, "TVDB", 0, 27)
            ),
            evidence = listOf("scudlee.one-piece")
        )
        val written = AnimeIdMapBinaryEncoder.writeEpisodeRecord(out, rec, pool)
        val bytes = out.toByteArray()
        assertEquals(written, bytes.size)
        assertEquals(BinaryFormat.RECORD_KIND_EPISODE, bytes[0])

        val decoded = TestEpisodeDecoder(bytes, pool.toByteArray()).decodeAt(0)
        assertEquals(rec, decoded)
    }
```

…and add `TestEpisodeDecoder` to the test file (mirror of `TestRecordDecoder` from Task 7, plus episode fields).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.AnimeIdMapBinaryEncoderTest"`
Expected: FAIL — `writeEpisodeRecord` does not exist.

- [ ] **Step 3: Implement**

Append to `AnimeIdMapBinaryEncoder.kt`:

```kotlin
    fun writeEpisodeRecord(
        out: OutputStream,
        record: WireAnimeEpisodeMappingRecord,
        stringPool: StringPoolBuilder,
    ): Int {
        val buf = java.io.ByteArrayOutputStream()
        buf.write(BinaryFormat.RECORD_KIND_EPISODE.toInt())
        var presence = 0
        if (record.name != null) presence = presence or 0x01
        if (record.tvdbSeriesId != null) presence = presence or 0x02
        if (record.tmdbTvId != null) presence = presence or 0x04
        if (record.evidence.isNotEmpty()) presence = presence or 0x08
        buf.write(presence)
        VarintWriter.writeULong(buf, record.anidb.toLong())
        record.name?.let { writeI32LE(buf, stringPool.intern(it)) }
        record.tvdbSeriesId?.let { VarintWriter.writeULong(buf, it.toLong()) }
        record.tmdbTvId?.let { VarintWriter.writeULong(buf, it.toLong()) }
        VarintWriter.writeULong(buf, record.ranges.size.toLong())
        for (i in record.ranges.indices) writeRange(buf, record.ranges[i])
        VarintWriter.writeULong(buf, record.explicitMaps.size.toLong())
        for (i in record.explicitMaps.indices) writeExplicit(buf, record.explicitMaps[i])
        if (record.evidence.isNotEmpty()) {
            VarintWriter.writeULong(buf, record.evidence.size.toLong())
            for (i in record.evidence.indices) writeI32LE(buf, stringPool.intern(record.evidence[i]))
        }
        val bytes = buf.toByteArray()
        out.write(bytes)
        return bytes.size
    }

    private fun writeRange(out: OutputStream, range: WireAnimeRangeRule) {
        // 10 bytes fixed: u8 srcSeason | u16 startEp | u16 endEp | u8 prov | u8 tgtSeason | i16 offset | u8 hasEndEp
        out.write(range.sourceSeason.coerceIn(0, 255))
        writeU16LE(out, range.startEpisode)
        writeU16LE(out, range.endEpisode ?: 0)
        out.write(BinaryFormat.providerByte(range.targetProvider).toInt() and 0xFF)
        out.write(range.targetSeason.coerceIn(0, 255))
        writeI16LE(out, range.offset.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        out.write(if (range.endEpisode != null) 1 else 0)
    }

    private fun writeExplicit(out: OutputStream, map: WireAnimeExplicitMap) {
        // 7 bytes fixed
        out.write(map.sourceSeason.coerceIn(0, 255))
        writeU16LE(out, map.sourceEpisode)
        out.write(BinaryFormat.providerByte(map.targetProvider).toInt() and 0xFF)
        out.write(map.targetSeason.coerceIn(0, 255))
        writeU16LE(out, map.targetEpisode)
    }

    private fun writeU16LE(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }
    private fun writeI16LE(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }
```

Note: range fixed stride is **11 bytes** (not 10 as in the spec) because we need a `hasEndEp` flag — JSON `endEpisode` is nullable for open-ended ranges. The spec's "10 B fixed per range" assumed always-present endEpisode; reality is some ranges omit it. Adjust spec mentally; reader must match.

- [ ] **Step 4: Run test**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.AnimeIdMapBinaryEncoderTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoder.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): encode episode records (ranges 11B / explicits 7B fixed-stride)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: AnimeIdMapBinaryEncoder — full file pipeline

**Files:**
- Modify: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoder.kt`
- Modify: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoderTest.kt`

This task wires the full pipeline: input JSON model → bytes (header + index table + index regions + multi-list pool + records + string pool).

- [ ] **Step 1: Add failing test**

Append to `AnimeIdMapBinaryEncoderTest.kt`:

```kotlin
    @Test
    fun `encode produces deterministic byte-identical output for identical input`() {
        val input = sampleAsset()
        val a = AnimeIdMapBinaryEncoder.encode(input)
        val b = AnimeIdMapBinaryEncoder.encode(input)
        assertArrayEquals(a, b)
    }

    @Test
    fun `encode header validates magic and schema version`() {
        val bytes = AnimeIdMapBinaryEncoder.encode(sampleAsset())
        assertEquals('N'.code.toByte(), bytes[0])
        assertEquals('X'.code.toByte(), bytes[1])
        assertEquals('A'.code.toByte(), bytes[2])
        assertEquals('I'.code.toByte(), bytes[3])
        // schemaVersion u32 LE
        val schema = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(BinaryFormat.SCHEMA_VERSION, schema)
    }

    private fun sampleAsset(): WireAnimeIdMapAsset {
        val r1 = WireAnimeIdMapRecord(
            kitsu = "265", mal = "290", anidb = "1", tvdb = "72025", imdb = "tt0286390",
            mediaType = "series", sourceType = "TV"
        )
        val r2 = WireAnimeIdMapRecord(kitsu = "99999", mediaType = "series", sourceType = "TV")
        return WireAnimeIdMapAsset(
            generatedAt = "2026-05-06T00:00:00Z",
            identityRecords = listOf(r1, r2),
            episodeMappings = emptyList(),
            byKitsu = mapOf("265" to "265", "99999" to "99999"),
            byMal = mapOf("290" to "265"),
            byAnilist = emptyMap(),
            byAnidb = mapOf("1" to "265"),
            byTmdbMovie = emptyMap(),
            byTvdb = mapOf("72025" to listOf("265")),
            byTmdbTv = emptyMap(),
            byImdb = mapOf("tt0286390" to listOf("265")),
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.AnimeIdMapBinaryEncoderTest"`
Expected: FAIL — `encode` and `WireAnimeIdMapAsset` don't exist.

- [ ] **Step 3: Implement**

Add to `Wire.kt`:

```kotlin
data class WireAnimeIdMapAsset(
    val generatedAt: String,
    val identityRecords: List<WireAnimeIdMapRecord>,
    val episodeMappings: List<WireAnimeEpisodeMappingRecord>,
    val byKitsu: Map<String, String>,
    val byMal: Map<String, String>,
    val byAnilist: Map<String, String>,
    val byAnidb: Map<String, String>,
    val byTmdbMovie: Map<String, String>,
    val byTvdb: Map<String, List<String>>,
    val byTmdbTv: Map<String, List<String>>,
    val byImdb: Map<String, List<String>>,
)
```

Add to `AnimeIdMapBinaryEncoder.kt`:

```kotlin
    fun encode(asset: WireAnimeIdMapAsset): ByteArray {
        val stringPool = StringPoolBuilder()
        val recordsBuf = java.io.ByteArrayOutputStream()
        // recordOffset is bytes-from-start-of-records-region
        val identityRecordOffsets = HashMap<String, Int>(asset.identityRecords.size * 2)
        for (rec in asset.identityRecords) {
            val offset = recordsBuf.size()
            writeIdentityRecord(recordsBuf, rec, stringPool)
            identityRecordOffsets[rec.kitsu] = offset
        }
        val episodeRecordOffsets = HashMap<String, Int>(asset.episodeMappings.size * 2)
        for (ep in asset.episodeMappings) {
            val offset = recordsBuf.size()
            writeEpisodeRecord(recordsBuf, ep, stringPool)
            episodeRecordOffsets[ep.anidb] = offset
        }
        val recordsBytes = recordsBuf.toByteArray()

        // Build indexes
        fun singleFromMap(map: Map<String, String>): ByteArray {
            val b = SortedIndexBuilder.Single()
            for ((k, v) in map) {
                val recOff = identityRecordOffsets[v]
                    ?: error("byKitsu/byMal/... references missing record kitsu=$v")
                b.add(k.toLong(), recOff)
            }
            return b.toByteArray()
        }
        fun multiFromMap(map: Map<String, List<String>>): Pair<ByteArray, ByteArray> {
            val b = SortedIndexBuilder.Multi()
            for ((k, vs) in map) {
                val offsets = IntArray(vs.size) {
                    identityRecordOffsets[vs[it]]
                        ?: error("multi-value index references missing record kitsu=${vs[it]}")
                }
                b.add(k.toLong(), offsets)
            }
            return b.build()
        }
        fun imdbFromMap(map: Map<String, List<String>>): Pair<ByteArray, ByteArray> {
            val b = SortedIndexBuilder.Imdb(stringPool)
            for ((k, vs) in map) {
                val offsets = IntArray(vs.size) {
                    identityRecordOffsets[vs[it]] ?: error("byImdb references missing record kitsu=${vs[it]}")
                }
                b.add(k, offsets)
            }
            return b.build()
        }

        val byKitsuBytes = singleFromMap(asset.byKitsu)
        val byMalBytes = singleFromMap(asset.byMal)
        val byAnilistBytes = singleFromMap(asset.byAnilist)
        val byAnidbBytes = singleFromMap(asset.byAnidb)
        val byTmdbMovieBytes = singleFromMap(asset.byTmdbMovie)
        val (byTvdbBytes, byTvdbPool) = multiFromMap(asset.byTvdb)
        val (byTmdbTvBytes, byTmdbTvPool) = multiFromMap(asset.byTmdbTv)
        val (byImdbBytes, byImdbPool) = imdbFromMap(asset.byImdb)
        val byAnidbEpisodeBuilder = SortedIndexBuilder.Single()
        for ((anidb, off) in episodeRecordOffsets) byAnidbEpisodeBuilder.add(anidb.toLong(), off)
        val byAnidbEpisodeBytes = byAnidbEpisodeBuilder.toByteArray()

        // Layout: header, indexTable, [index regions], [multi-list pool], records, stringPool
        // Compute offsets
        val headerSize = BinaryFormat.HEADER_SIZE.toLong()
        val indexTableSize = BinaryFormat.INDEX_TABLE_SIZE.toLong()
        var cursor = headerSize + indexTableSize

        data class Slot(val kind: Int, val stride: Int, val bytes: ByteArray, var offset: Long = 0L) {
            val entryCount: Long get() = (bytes.size / stride).toLong()
        }
        val slots = arrayOf(
            Slot(BinaryFormat.KIND_U64_SINGLE, BinaryFormat.STRIDE_U64_SINGLE, byKitsuBytes),
            Slot(BinaryFormat.KIND_U64_SINGLE, BinaryFormat.STRIDE_U64_SINGLE, byMalBytes),
            Slot(BinaryFormat.KIND_U64_SINGLE, BinaryFormat.STRIDE_U64_SINGLE, byAnilistBytes),
            Slot(BinaryFormat.KIND_U64_SINGLE, BinaryFormat.STRIDE_U64_SINGLE, byAnidbBytes),
            Slot(BinaryFormat.KIND_U64_SINGLE, BinaryFormat.STRIDE_U64_SINGLE, byTmdbMovieBytes),
            Slot(BinaryFormat.KIND_U64_MULTI, BinaryFormat.STRIDE_U64_MULTI, byTvdbBytes),
            Slot(BinaryFormat.KIND_U64_MULTI, BinaryFormat.STRIDE_U64_MULTI, byTmdbTvBytes),
            Slot(BinaryFormat.KIND_IMDB, BinaryFormat.STRIDE_IMDB, byImdbBytes),
            Slot(BinaryFormat.KIND_U64_SINGLE, BinaryFormat.STRIDE_U64_SINGLE, byAnidbEpisodeBytes),
        )
        for (s in slots) {
            s.offset = cursor
            cursor += s.bytes.size
        }
        // Multi-list pool concatenated after index regions
        val multiPool = byTvdbPool + byTmdbTvPool + byImdbPool
        // listOffset values in index regions must be ABSOLUTE file offsets so
        // the reader can index directly into the mmap'd ByteBuffer. After the
        // indexes loop above, `cursor` is the absolute file offset where the
        // multi-list pool starts.
        val multiPoolFileOffset = cursor.toInt()
        rewriteMultiListOffsets(byTvdbBytes, base = multiPoolFileOffset)
        rewriteMultiListOffsets(byTmdbTvBytes, base = multiPoolFileOffset + byTvdbPool.size)
        rewriteImdbListOffsets(byImdbBytes, base = multiPoolFileOffset + byTvdbPool.size + byTmdbTvPool.size)

        cursor += multiPool.size
        val recordsOffset = cursor
        cursor += recordsBytes.size
        val stringPoolBytes = stringPool.toByteArray()
        val stringPoolOffset = cursor
        cursor += stringPoolBytes.size

        // Write header
        val total = cursor.toInt()
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.put(BinaryFormat.MAGIC_BYTES)
        out.putInt(BinaryFormat.SCHEMA_VERSION)
        out.putLong(parseInstantSeconds(asset.generatedAt))
        out.putInt(asset.identityRecords.size + asset.episodeMappings.size)
        out.putLong(recordsOffset)
        out.putLong(recordsBytes.size.toLong())
        out.putLong(headerSize)  // indexTableOffset
        out.putLong(stringPoolOffset)
        out.putLong(stringPoolBytes.size.toLong())
        out.putInt(0)  // reserved
        // Index table: 9 descriptors
        for (s in slots) {
            out.putInt(s.kind)
            out.putInt(s.stride)
            out.putLong(s.offset)
            out.putLong(s.entryCount)
        }
        // Index regions
        for (s in slots) out.put(s.bytes)
        out.put(multiPool)
        out.put(recordsBytes)
        out.put(stringPoolBytes)
        return out.array()
    }

    private fun parseInstantSeconds(iso: String): Long =
        runCatching { java.time.Instant.parse(iso).epochSecond }.getOrDefault(0L)

    /** Rewrite each entry's u32 listOffset at byte (i*16)+8 by adding [base]. */
    private fun rewriteMultiListOffsets(bytes: ByteArray, base: Int) {
        val stride = BinaryFormat.STRIDE_U64_MULTI
        var i = 0
        while (i < bytes.size) {
            val off = i + 8
            val cur = readI32LE(bytes, off)
            writeI32LE(bytes, off, cur + base)
            i += stride
        }
    }
    private fun rewriteImdbListOffsets(bytes: ByteArray, base: Int) {
        val stride = BinaryFormat.STRIDE_IMDB
        var i = 0
        while (i < bytes.size) {
            val off = i + 12  // u64 hash + u32 strOff = 12 bytes before listOff
            val cur = readI32LE(bytes, off)
            writeI32LE(bytes, off, cur + base)
            i += stride
        }
    }
    private fun readI32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)
    private fun writeI32LE(b: ByteArray, off: Int, value: Int) {
        b[off] = (value and 0xFF).toByte()
        b[off + 1] = ((value ushr 8) and 0xFF).toByte()
        b[off + 2] = ((value ushr 16) and 0xFF).toByte()
        b[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }
```

- [ ] **Step 4: Run encoder tests**

Run: `./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.binary.AnimeIdMapBinaryEncoderTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoder.kt \
        tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/Wire.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/binary/AnimeIdMapBinaryEncoderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): encode full binary file (header + indexes + pool + records)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: EncodeMain CLI + Gradle wiring

**Files:**
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/EncodeMain.kt`
- Modify: `app/build.gradle.kts` (add `generateAnimeIdMapBinary` + `checkAnimeIdMapBinary` tasks)

- [ ] **Step 1: Add CLI entry point**

```kotlin
// tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/EncodeMain.kt
package com.nexio.animemap.binary

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object EncodeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: EncodeMain <json-input> <bin-output>" }
        val input = File(args[0])
        val output = File(args[1])
        require(input.exists()) { "input not found: $input" }

        val moshi = Moshi.Builder().build()
        val adapter: JsonAdapter<RawJsonAsset> = moshi.adapter(RawJsonAsset::class.java)
        val raw = input.source().use { adapter.fromJson(it.buffer()) }
            ?: error("failed to parse $input")
        val asset = raw.toWire()
        val bytes = AnimeIdMapBinaryEncoder.encode(asset)

        val tmp = File(output.parentFile, "${output.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), output.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        println("encoded ${bytes.size} bytes -> $output")
    }
}

// Add okio extension imports
private fun File.source() = okio.Okio.source(this)
```

Wait — the existing `:tools:anime-mapping-generator` doesn't depend on okio yet. Check by reading the existing build script (already done in pre-research; only Moshi). Add okio dependency.

- [ ] **Step 2: Add okio dependency to tools subproject**

Modify `tools/anime-mapping-generator/build.gradle.kts`:

Find:
```kotlin
dependencies {
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)
    testImplementation("junit:junit:4.13.2")
}
```

Replace with:
```kotlin
dependencies {
    implementation(libs.moshi)
    implementation(libs.okio)
    ksp(libs.moshi.codegen)
    testImplementation("junit:junit:4.13.2")
}
```

Verify `libs.okio` exists in `gradle/libs.versions.toml`:

```bash
grep -n "^okio" gradle/libs.versions.toml
```

If missing, add an alias pointing at the version already used by app. Run `grep okio app/build.gradle.kts gradle/libs.versions.toml` to find the existing reference and reuse the same version alias.

- [ ] **Step 3: Add RawJsonAsset adapter inside EncodeMain**

Replace the `// Add okio extension imports` block with a proper Moshi-mapped JSON model that mirrors `AnimeIdMapAsset` shape:

```kotlin
// At bottom of EncodeMain.kt
@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RawJsonAsset(
    val schemaVersion: Int,
    val mappingPolicyVersion: Int = 1,
    val generatedAt: String? = null,
    val identityRecordsByKitsu: Map<String, RawIdentityRecord> = emptyMap(),
    val episodeMappingsByAnidb: Map<String, RawEpisodeRecord> = emptyMap(),
    val indexes: RawIndexes = RawIndexes(),
) {
    fun toWire(): WireAnimeIdMapAsset = WireAnimeIdMapAsset(
        generatedAt = generatedAt ?: "1970-01-01T00:00:00Z",
        // Use LinkedHashMap iteration → values list preserves order
        identityRecords = identityRecordsByKitsu.values.map { it.toWire() },
        episodeMappings = episodeMappingsByAnidb.values.map { it.toWire() },
        byKitsu = indexes.byKitsu,
        byMal = indexes.byMal,
        byAnilist = indexes.byAnilist,
        byAnidb = indexes.byAnidb,
        byTmdbMovie = indexes.byTmdbMovie,
        byTvdb = indexes.byTvdb,
        byTmdbTv = indexes.byTmdbTv,
        byImdb = indexes.byImdb,
    )
}

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RawIdentityRecord(
    val kitsu: String,
    val mal: String? = null,
    val anilist: String? = null,
    val anidb: String? = null,
    val tmdb: String? = null,
    val tvdb: String? = null,
    val imdb: String? = null,
    val mediaType: String? = null,
    val sourceType: String? = null,
    val tvdbSeason: String? = null,
    val tmdbSeason: String? = null,
    val tvdbEpisodeOffset: Int? = null,
    val tmdbEpisodeOffset: Int? = null,
    val hasMappingRules: Boolean = false,
    val evidence: List<String> = emptyList(),
) {
    fun toWire(): WireAnimeIdMapRecord = WireAnimeIdMapRecord(
        kitsu = kitsu, mal = mal, anilist = anilist, anidb = anidb,
        tmdb = tmdb, tvdb = tvdb, imdb = imdb,
        mediaType = mediaType, sourceType = sourceType,
        tvdbSeason = tvdbSeason, tmdbSeason = tmdbSeason,
        tvdbEpisodeOffset = tvdbEpisodeOffset, tmdbEpisodeOffset = tmdbEpisodeOffset,
        hasMappingRules = hasMappingRules, evidence = evidence,
    )
}

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RawEpisodeRecord(
    val anidb: String,
    val name: String? = null,
    val tvdbSeriesId: String? = null,
    val tmdbTvId: String? = null,
    val ranges: List<RawRange> = emptyList(),
    val explicitMaps: List<RawExplicit> = emptyList(),
    val evidence: List<String> = emptyList(),
) {
    fun toWire(): WireAnimeEpisodeMappingRecord = WireAnimeEpisodeMappingRecord(
        anidb = anidb, name = name, tvdbSeriesId = tvdbSeriesId, tmdbTvId = tmdbTvId,
        ranges = ranges.map { WireAnimeRangeRule(it.sourceSeason, it.startEpisode, it.endEpisode, it.targetProvider, it.targetSeason, it.offset) },
        explicitMaps = explicitMaps.map { WireAnimeExplicitMap(it.sourceSeason, it.sourceEpisode, it.targetProvider, it.targetSeason, it.targetEpisode) },
        evidence = evidence,
    )
}

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RawRange(
    val sourceSeason: Int,
    val startEpisode: Int,
    val endEpisode: Int? = null,
    val targetProvider: String,
    val targetSeason: Int,
    val offset: Int,
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RawExplicit(
    val sourceSeason: Int,
    val sourceEpisode: Int,
    val targetProvider: String,
    val targetSeason: Int,
    val targetEpisode: Int,
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RawIndexes(
    val byKitsu: Map<String, String> = emptyMap(),
    val byMal: Map<String, String> = emptyMap(),
    val byAnilist: Map<String, String> = emptyMap(),
    val byAnidb: Map<String, String> = emptyMap(),
    val byTvdb: Map<String, List<String>> = emptyMap(),
    val byTmdbTv: Map<String, List<String>> = emptyMap(),
    val byTmdbMovie: Map<String, String> = emptyMap(),
    val byImdb: Map<String, List<String>> = emptyMap(),
)

private fun File.source() = okio.Okio.source(this)
```

- [ ] **Step 4: Register Gradle tasks in `app/build.gradle.kts`**

Open `app/build.gradle.kts`. Locate the existing `checkAnimeMappingAsset` task registration block (line ~50 based on prior inspection). Immediately after it, add:

```kotlin
val animeMappingBinaryAsset = layout.projectDirectory.file(
    "src/main/assets/anime/nexio-anime-map-v1.bin"
)

tasks.register<JavaExec>("generateAnimeIdMapBinary") {
    group = "anime-mapping"
    description = "Encode nexio-anime-map-v1.json into nexio-anime-map-v1.bin. " +
        "Explicit-invocation only — re-run after the JSON asset is regenerated."
    val genProject = project(":tools:anime-mapping-generator")
    classpath = genProject.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("com.nexio.animemap.binary.EncodeMain")
    args = listOf(
        animeMappingAsset.asFile.absolutePath,
        animeMappingBinaryAsset.asFile.absolutePath,
    )
    inputs.file(animeMappingAsset)
    outputs.file(animeMappingBinaryAsset)
}

tasks.register("checkAnimeIdMapBinary") {
    group = "anime-mapping"
    description = "Verify the committed nexio-anime-map-v1.bin matches a fresh re-encode of the JSON."
    inputs.file(animeMappingAsset)
    inputs.file(animeMappingBinaryAsset)
    doLast {
        val tmp = java.io.File.createTempFile("anime-id-map-check", ".bin")
        try {
            javaexec {
                val genProject = project(":tools:anime-mapping-generator")
                classpath = genProject.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
                mainClass.set("com.nexio.animemap.binary.EncodeMain")
                args = listOf(animeMappingAsset.asFile.absolutePath, tmp.absolutePath)
            }
            val committed = animeMappingBinaryAsset.asFile.readBytes()
            val fresh = tmp.readBytes()
            if (!committed.contentEquals(fresh)) {
                throw GradleException(
                    "nexio-anime-map-v1.bin is out of date. Run " +
                        "`./gradlew :app:generateAnimeIdMapBinary` and commit the result."
                )
            }
            println("checkAnimeIdMapBinary OK (${committed.size} bytes)")
        } finally {
            tmp.delete()
        }
    }
}

tasks.named("check") {
    dependsOn("checkAnimeIdMapBinary")
}
```

- [ ] **Step 5: Generate the binary, commit it**

```bash
./gradlew :app:generateAnimeIdMapBinary
ls -la app/src/main/assets/anime/nexio-anime-map-v1.bin
./gradlew :app:checkAnimeIdMapBinary
```
Expected: `.bin` exists at ~5–6 MiB; `checkAnimeIdMapBinary` reports OK.

- [ ] **Step 6: Commit**

```bash
git add tools/anime-mapping-generator/build.gradle.kts \
        tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/EncodeMain.kt \
        app/build.gradle.kts \
        app/src/main/assets/anime/nexio-anime-map-v1.bin
git commit -m "$(cat <<'EOF'
feat(anime-id-map): wire generateAnimeIdMapBinary + checkAnimeIdMapBinary gradle tasks

Commits the freshly-encoded nexio-anime-map-v1.bin. The check-side task
verifies on every gradle check that the committed .bin matches a fresh
re-encode of the JSON, mirroring the existing checkAnimeMappingAsset
pattern.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2 — Reader (runtime, in `app/`)

### Task 11: BinaryFormat constants + IndexKind enum (reader copy)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/binary/BinaryFormat.kt`
- Create: `app/src/main/java/com/nexio/tv/core/anime/binary/IndexKind.kt`

- [ ] **Step 1: Create the constants and enum**

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/binary/BinaryFormat.kt
package com.nexio.tv.core.anime.binary

internal object BinaryFormat {
    val MAGIC_BYTES: ByteArray = byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte())
    const val SCHEMA_VERSION: Int = 1
    const val HEADER_SIZE: Int = 64
    const val INDEX_DESCRIPTOR_SIZE: Int = 24
    const val INDEX_TABLE_SIZE: Int = INDEX_DESCRIPTOR_SIZE * 9

    const val KIND_U64_SINGLE: Int = 1
    const val KIND_U64_MULTI: Int = 2
    const val KIND_IMDB: Int = 3

    const val STRIDE_U64_SINGLE: Int = 12
    const val STRIDE_U64_MULTI: Int = 16
    const val STRIDE_IMDB: Int = 20

    const val RECORD_KIND_IDENTITY: Byte = 0
    const val RECORD_KIND_EPISODE: Byte = 1

    const val P_MAL: Int = 1 shl 0
    const val P_ANILIST: Int = 1 shl 1
    const val P_ANIDB: Int = 1 shl 2
    const val P_TMDB: Int = 1 shl 3
    const val P_TVDB: Int = 1 shl 4
    const val P_IMDB: Int = 1 shl 5
    const val P_MEDIA_TYPE: Int = 1 shl 6
    const val P_SOURCE_TYPE: Int = 1 shl 7

    const val P2_TVDB_SEASON: Int = 1 shl 0
    const val P2_TMDB_SEASON: Int = 1 shl 1
    const val P2_TVDB_EP_OFFSET: Int = 1 shl 2
    const val P2_TMDB_EP_OFFSET: Int = 1 shl 3
    const val P2_HAS_MAPPING_RULES: Int = 1 shl 4
    const val P2_HAS_EVIDENCE: Int = 1 shl 5

    val MEDIA_TYPE_TABLE: List<String> = listOf("movie", "series", "other")
    val SOURCE_TYPE_TABLE: List<String> = listOf("tv", "ova", "ona", "movie", "music", "special", "other")

    const val NULL_STRING_OFFSET: Int = -1
}
```

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/binary/IndexKind.kt
package com.nexio.tv.core.anime.binary

internal enum class IndexKind(val slot: Int) {
    BY_KITSU(0),
    BY_MAL(1),
    BY_ANILIST(2),
    BY_ANIDB(3),
    BY_TMDB_MOVIE(4),
    BY_TVDB(5),
    BY_TMDB_TV(6),
    BY_IMDB(7),
    BY_ANIDB_EPISODE(8),
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/binary/BinaryFormat.kt \
        app/src/main/java/com/nexio/tv/core/anime/binary/IndexKind.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): add reader-side BinaryFormat constants + IndexKind enum

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: VarintReader (absolute-indexed ByteBuffer)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/binary/VarintReader.kt`
- Create: `app/src/test/java/com/nexio/tv/core/anime/binary/VarintReaderTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// app/src/test/java/com/nexio/tv/core/anime/binary/VarintReaderTest.kt
package com.nexio.tv.core.anime.binary

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VarintReaderTest {
    private fun bb(vararg bytes: Int): ByteBuffer {
        val b = ByteBuffer.allocate(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.forEach { b.put((it and 0xFF).toByte()) }
        b.flip()
        return b
    }

    @Test
    fun `reads zero`() {
        val r = LongRef()
        val next = VarintReader.readULong(bb(0x00), 0, r)
        assertEquals(0L, r.value); assertEquals(1, next)
    }

    @Test
    fun `reads 127`() {
        val r = LongRef()
        val next = VarintReader.readULong(bb(0x7F), 0, r)
        assertEquals(127L, r.value); assertEquals(1, next)
    }

    @Test
    fun `reads 128 from two bytes`() {
        val r = LongRef()
        val next = VarintReader.readULong(bb(0x80, 0x01), 0, r)
        assertEquals(128L, r.value); assertEquals(2, next)
    }

    @Test
    fun `reads from non-zero start offset`() {
        val r = LongRef()
        val next = VarintReader.readULong(bb(0xFF, 0x80, 0x01, 0xFF), 1, r)
        assertEquals(128L, r.value); assertEquals(3, next)
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.VarintReaderTest"`
Expected: FAIL — `Unresolved reference: VarintReader`.

- [ ] **Step 3: Implement**

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/binary/VarintReader.kt
package com.nexio.tv.core.anime.binary

import java.nio.ByteBuffer

/** Reusable holder so callers can avoid boxing the return value. */
internal class LongRef { var value: Long = 0L }

internal object VarintReader {
    /**
     * Read an unsigned LEB128 varint from [buf] starting at absolute [offset].
     * Stores the decoded value in [into] and returns the absolute byte
     * offset just past the last byte read.
     */
    fun readULong(buf: ByteBuffer, offset: Int, into: LongRef): Int {
        var result = 0L
        var shift = 0
        var i = offset
        while (true) {
            val b = buf.get(i).toInt() and 0xFF
            i++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                into.value = result
                return i
            }
            shift += 7
            check(shift < 64) { "varint overflow at offset $offset" }
        }
    }

    /** Zigzag-decoded signed int. */
    fun readSInt(buf: ByteBuffer, offset: Int, into: LongRef): Int {
        val next = readULong(buf, offset, into)
        val raw = into.value
        val v = ((raw ushr 1).toInt()) xor (-(raw.toInt() and 1))
        into.value = v.toLong()
        return next
    }
}
```

- [ ] **Step 4: Run tests, verify PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.VarintReaderTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/binary/VarintReader.kt \
        app/src/test/java/com/nexio/tv/core/anime/binary/VarintReaderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): add VarintReader over absolute-indexed ByteBuffer

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Generate a small fixture .bin for reader tests

**Files:**
- Create: `app/src/test/resources/anime/nexio-anime-map-v1-test.bin` (generated)
- Modify: `app/build.gradle.kts` (add `generateAnimeIdMapBinaryFixture` task)

The fixture lets us test the reader against a real (small) binary without invoking the encoder from inside the reader test.

- [ ] **Step 1: Add a generation task in `app/build.gradle.kts`**

Find the `checkAnimeIdMapBinary` block from Task 10 and append:

```kotlin
val animeMapFixtureJson = layout.projectDirectory.file(
    "src/test/resources/fixtures/nexio-anime-map-v1-test.json"
)
val animeMapFixtureBin = layout.projectDirectory.file(
    "src/test/resources/anime/nexio-anime-map-v1-test.bin"
)

tasks.register<JavaExec>("generateAnimeIdMapBinaryFixture") {
    group = "anime-mapping"
    description = "Encode the test fixture JSON into a test fixture .bin for reader tests."
    val genProject = project(":tools:anime-mapping-generator")
    classpath = genProject.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("com.nexio.animemap.binary.EncodeMain")
    args = listOf(
        animeMapFixtureJson.asFile.absolutePath,
        animeMapFixtureBin.asFile.absolutePath,
    )
    inputs.file(animeMapFixtureJson)
    outputs.file(animeMapFixtureBin)
}
```

- [ ] **Step 2: Run it, commit the fixture**

```bash
mkdir -p app/src/test/resources/anime
./gradlew :app:generateAnimeIdMapBinaryFixture
ls -la app/src/test/resources/anime/nexio-anime-map-v1-test.bin
```
Expected: small fixture .bin (~1–2 KB).

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts \
        app/src/test/resources/anime/nexio-anime-map-v1-test.bin
git commit -m "$(cat <<'EOF'
test(anime-id-map): add generateAnimeIdMapBinaryFixture + committed fixture .bin

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: AnimeIdMapBinaryReader — open + header parse

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt`
- Create: `app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt
package com.nexio.tv.core.anime.binary

import android.content.Context
import android.content.res.AssetManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

class AnimeIdMapBinaryReaderTest {
    private lateinit var workDir: File
    private lateinit var fakeFilesDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("animeidmap-test").toFile()
        fakeFilesDir = File(workDir, "files").apply { mkdirs() }
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(fakeFilesDir)
        val assets = mock(AssetManager::class.java)
        `when`(context.assets).thenReturn(assets)
        // Wire the fixture .bin as the asset content
        val fixture = File("src/test/resources/anime/nexio-anime-map-v1-test.bin")
        require(fixture.exists()) { "fixture missing — run :app:generateAnimeIdMapBinaryFixture" }
        `when`(assets.open("anime/nexio-anime-map-v1.bin")).thenAnswer { FileInputStream(fixture) }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun `ensureOpen copies asset to filesDir and maps successfully`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertTrue(reader.isOpen())
        val onDisk = File(fakeFilesDir, "anime-id-map/fmt1.bin")
        assertTrue("expected on-disk copy at $onDisk", onDisk.exists())
        assertTrue(onDisk.length() > 0)
    }

    @Test
    fun `ensureOpen degrades to Failed when asset missing`() {
        `when`(context.assets.open("anime/nexio-anime-map-v1.bin"))
            .thenThrow(java.io.FileNotFoundException("missing"))
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertFalse(reader.isOpen())
        assertTrue(reader.isFailed())
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReaderTest"`
Expected: FAIL — `Unresolved reference: AnimeIdMapBinaryReader`.

- [ ] **Step 3: Implement open + header parse only**

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt
package com.nexio.tv.core.anime.binary

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnimeIdMapBinaryReader"
private const val ASSET_PATH = "anime/nexio-anime-map-v1.bin"
private const val BINARY_FORMAT_VERSION = 1
private const val DIR_NAME = "anime-id-map"

@Singleton
class AnimeIdMapBinaryReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var state: State = State.Closed
    private val openLock = Any()

    internal sealed interface State {
        object Closed : State
        object Failed : State
        class Open(
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

    fun isOpen(): Boolean = state is State.Open
    fun isFailed(): Boolean = state === State.Failed

    private fun openInternal(): State {
        val file = ensureBinaryOnDisk() ?: return State.Failed
        return try {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                val full = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    .order(ByteOrder.LITTLE_ENDIAN)
                parseHeaderAndSlice(full).also {
                    Log.i(TAG, "open ok schema=$BINARY_FORMAT_VERSION sizeBytes=${channel.size()}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "header_invalid_or_unreadable", t)
            // Attempt one recopy in case the on-disk copy is corrupted.
            runCatching { file.delete() }
            val retry = ensureBinaryOnDisk() ?: return State.Failed
            try {
                FileChannel.open(retry.toPath(), StandardOpenOption.READ).use { channel ->
                    val full = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                        .order(ByteOrder.LITTLE_ENDIAN)
                    parseHeaderAndSlice(full)
                }
            } catch (t2: Throwable) {
                Log.w(TAG, "header_invalid_after_recopy", t2)
                State.Failed
            }
        }
    }

    private fun parseHeaderAndSlice(full: ByteBuffer): State {
        require(full.capacity() >= BinaryFormat.HEADER_SIZE) { "file shorter than header" }
        for (i in BinaryFormat.MAGIC_BYTES.indices) {
            require(full.get(i) == BinaryFormat.MAGIC_BYTES[i]) {
                "bad magic byte at $i: ${full.get(i)}"
            }
        }
        val schema = full.getInt(4)
        require(schema == BinaryFormat.SCHEMA_VERSION) { "unsupported schemaVersion=$schema" }
        val recordsOffset = full.getLong(20)
        val recordsLength = full.getLong(28)
        val indexTableOffset = full.getLong(36)
        val stringPoolOffset = full.getLong(44)
        val stringPoolLength = full.getLong(52)

        val header = slice(full, 0, BinaryFormat.HEADER_SIZE)
        val indexTable = slice(full, indexTableOffset.toInt(), BinaryFormat.INDEX_TABLE_SIZE)
        // indexRegion + multiListPool occupy the bytes between indexTable end and recordsOffset.
        // We slice them as one contiguous buffer; descriptors carry absolute offsets so callers
        // index into `full` via the appropriate slice.
        val indexRegionStart = (indexTableOffset + BinaryFormat.INDEX_TABLE_SIZE).toInt()
        val indexRegionEnd = recordsOffset.toInt()
        val indexRegion = slice(full, indexRegionStart, indexRegionEnd - indexRegionStart)
        // For absolute lookups we keep `full` itself for the multi-list pool.
        val multiListPool = slice(full, indexRegionStart, indexRegionEnd - indexRegionStart)
        val records = slice(full, recordsOffset.toInt(), recordsLength.toInt())
        val stringPool = slice(full, stringPoolOffset.toInt(), stringPoolLength.toInt())
        return State.Open(header, indexTable, indexRegion, multiListPool, records, stringPool)
    }

    private fun slice(full: ByteBuffer, offset: Int, length: Int): ByteBuffer {
        val dup = full.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        dup.position(offset)
        dup.limit(offset + length)
        return dup.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun ensureBinaryOnDisk(): File? {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val target = File(dir, "fmt$BINARY_FORMAT_VERSION.bin")
        if (target.exists() && target.length() > 0) return target
        return runCatching {
            val tmp = File(dir, "fmt$BINARY_FORMAT_VERSION.bin.tmp")
            context.assets.open(ASSET_PATH).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
            }
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            dir.listFiles { f -> f.name.startsWith("fmt") && f != target }?.forEach { it.delete() }
            target
        }.onFailure { Log.w(TAG, "copy_failed", it) }.getOrNull()
    }
}
```

Add mockito to test deps if not already present. Check:

```bash
grep -n "mockito" app/build.gradle.kts
```

If missing, add to `dependencies { … }`:

```kotlin
testImplementation("org.mockito:mockito-core:5.7.0")
```

(Match version used in existing tests if any are mocked — `grep -rln "import org.mockito" app/src/test`.)

- [ ] **Step 4: Run reader test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReaderTest"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt \
        app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt \
        app/build.gradle.kts  # if mockito was added
git commit -m "$(cat <<'EOF'
feat(anime-id-map): AnimeIdMapBinaryReader open + header parse + asset copy

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: Single-value index lookups (containsKitsu, lookupSingle)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt`

- [ ] **Step 1: Add failing tests**

Append to `AnimeIdMapBinaryReaderTest.kt`:

```kotlin
    @Test
    fun `containsKitsu returns true for known id, false for unknown`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertTrue(reader.containsKitsu("11469"))
        assertFalse(reader.containsKitsu("000000"))
    }

    @Test
    fun `lookupSingle finds by mal anidb tmdbMovie`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        // From fixture JSON: byAnidb { "11739" -> "11469" }
        assertEquals("11469", reader.lookupSingle(IndexKind.BY_ANIDB, "11739"))
        assertNull(reader.lookupSingle(IndexKind.BY_ANIDB, "99999999"))
    }
```

(Add `import org.junit.Assert.assertEquals`, `import org.junit.Assert.assertNull`.)

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReaderTest"`
Expected: FAIL — `Unresolved reference: containsKitsu`.

- [ ] **Step 3: Implement**

Append inside the `AnimeIdMapBinaryReader` class:

```kotlin
    fun containsKitsu(kitsuId: String): Boolean {
        val open = state as? State.Open ?: return false
        val key = kitsuId.toLongOrNull() ?: return false
        return findSingleEntry(open, IndexKind.BY_KITSU, key) >= 0
    }

    fun lookupSingle(kind: IndexKind, value: String): String? {
        val open = state as? State.Open ?: return null
        val key = value.toLongOrNull() ?: return null
        val entryIndex = findSingleEntry(open, kind, key)
        if (entryIndex < 0) return null
        val descriptor = readDescriptor(open, kind.slot)
        val entryStart = descriptor.offset.toInt() + entryIndex * BinaryFormat.STRIDE_U64_SINGLE
        val recordOffset = full(open).getInt(entryStart + 8)
        // For single-value indexes the value IS the kitsu numeric ID of the
        // referenced record — decode the kitsu varint from the record's body.
        return readRecordKitsuId(open, recordOffset)
    }

    private data class Descriptor(val kind: Int, val stride: Int, val offset: Long, val count: Long)

    private fun readDescriptor(open: State.Open, slot: Int): Descriptor {
        val base = slot * BinaryFormat.INDEX_DESCRIPTOR_SIZE
        val k = open.indexTable.getInt(base)
        val stride = open.indexTable.getInt(base + 4)
        val offset = open.indexTable.getLong(base + 8)
        val count = open.indexTable.getLong(base + 16)
        return Descriptor(k, stride, offset, count)
    }

    private fun findSingleEntry(open: State.Open, kind: IndexKind, key: Long): Int {
        val d = readDescriptor(open, kind.slot)
        require(d.kind == BinaryFormat.KIND_U64_SINGLE) {
            "expected KIND_U64_SINGLE for $kind, got ${d.kind}"
        }
        return binarySearchU64(full(open), d.offset.toInt(), BinaryFormat.STRIDE_U64_SINGLE, d.count.toInt(), key)
    }

    private fun binarySearchU64(region: ByteBuffer, baseOffset: Int, stride: Int, count: Int, key: Long): Int {
        var lo = 0; var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val midKey = region.getLong(baseOffset + mid * stride)
            when {
                midKey < key -> lo = mid + 1
                midKey > key -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** The full underlying mapped buffer, reachable as the parent of any slice. */
    private fun full(open: State.Open): ByteBuffer {
        // We rebuild absolute lookups via the indexRegion slice — but the
        // records / pool slices already start at offset 0 within their own
        // slice. For indexes with absolute offsets carried in the descriptor,
        // we need the original buffer. Track it explicitly:
        return open.parent
    }

    private fun readRecordKitsuId(open: State.Open, recordOffset: Int): String? {
        val records = open.records
        if (records.get(recordOffset).toInt() and 0xFF != BinaryFormat.RECORD_KIND_IDENTITY.toInt() and 0xFF) {
            return null
        }
        // skip recordKind(1) + presence(1) + presence2(1)
        val r = LongRef()
        VarintReader.readULong(records, recordOffset + 3, r)
        return r.value.toString()
    }
```

This implementation has a problem: `full(open)` references `open.parent`, but the `State.Open` class doesn't yet have a `parent` field. The descriptor offsets are **absolute** from start of file; the slices we sliced earlier reset to offset 0. We need either:
- carry the original full buffer on `Open`, **or**
- store descriptors with offsets relative to the indexRegion start.

**Simpler:** carry the full buffer on `Open`. Update the `Open` class:

```kotlin
        class Open(
            val parent: ByteBuffer,
            val header: ByteBuffer,
            val indexTable: ByteBuffer,
            val indexRegion: ByteBuffer,
            val multiListPool: ByteBuffer,
            val records: ByteBuffer,
            val stringPool: ByteBuffer,
        ) : State
```

…and at construction in `parseHeaderAndSlice` pass `full` as the first arg. The retained heap cost is still ~tens of bytes — six wrappers around one off-heap mmap region.

(In the body of `lookupSingle`, the `recordOffset` is the byte offset into the **records** region — not the parent. The single-value entry stores `recordOffset` relative to records-region start; that's what the encoder writes. So `readRecordKitsuId` correctly indexes into `open.records`. Only the **index entry's** base offset needs the parent buffer; flip that around by slicing index regions to start at offset 0 within their own slice.)

Restructure: store index-table-relative offsets in the descriptor too. The encoder writes absolute file offsets in descriptors. We can either:
- a) keep parent buffer reference and use absolute reads, **or**
- b) on `parseHeaderAndSlice`, walk the descriptor table and rewrite offsets to be relative to the start of `indexRegion`.

Option (a) is simpler. Stick with it. The "extra" buffer wrapper is ~64 bytes, negligible vs the 15 MiB target.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReaderTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt \
        app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): single-value index lookups via binary search

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 16: Multi-value lookups (lookupMultiFirst, recordOffsetsFor)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt`

- [ ] **Step 1: Add failing test**

Append:

```kotlin
    @Test
    fun `lookupMultiFirst returns first kitsu for tvdb id with multiple records`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        // Fixture: byTvdb { "305074" -> ["11469","13881"] }
        assertEquals("11469", reader.lookupMultiFirst(IndexKind.BY_TVDB, "305074"))
        assertNull(reader.lookupMultiFirst(IndexKind.BY_TVDB, "99999"))
    }

    @Test
    fun `recordOffsetsForMultiKey returns full list of offsets`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        val offsets = reader.recordOffsetsForMultiKey(IndexKind.BY_TVDB, "305074")
        assertEquals(2, offsets.size)
    }
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReaderTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Append to the class body:

```kotlin
    fun lookupMultiFirst(kind: IndexKind, value: String): String? {
        val offsets = recordOffsetsForMultiKey(kind, value)
        if (offsets.isEmpty()) return null
        val open = state as State.Open
        return readRecordKitsuId(open, offsets[0])
    }

    fun recordOffsetsForMultiKey(kind: IndexKind, value: String): IntArray {
        val open = state as? State.Open ?: return IntArray(0)
        val key = value.toLongOrNull() ?: return IntArray(0)
        val d = readDescriptor(open, kind.slot)
        require(d.kind == BinaryFormat.KIND_U64_MULTI) {
            "expected KIND_U64_MULTI for $kind, got ${d.kind}"
        }
        val entryIndex = binarySearchU64(open.parent, d.offset.toInt(), BinaryFormat.STRIDE_U64_MULTI, d.count.toInt(), key)
        if (entryIndex < 0) return IntArray(0)
        val entryStart = d.offset.toInt() + entryIndex * BinaryFormat.STRIDE_U64_MULTI
        val listOffset = open.parent.getInt(entryStart + 8)
        val listLen = open.parent.getInt(entryStart + 12)
        val result = IntArray(listLen)
        for (i in 0 until listLen) {
            result[i] = open.parent.getInt(listOffset + i * 4)
        }
        return result
    }
```

- [ ] **Step 4: Run**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReaderTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt \
        app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): multi-value index lookups (BY_TVDB, BY_TMDB_TV)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 17: IMDB lookups (hash → string-pool collision probe)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt`
- Create: `app/src/main/java/com/nexio/tv/core/anime/binary/StringHash.kt`

- [ ] **Step 1: Add reader-side StringHash (must match encoder)**

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/binary/StringHash.kt
package com.nexio.tv.core.anime.binary

internal object StringHash {
    private const val OFFSET_BASIS: Long = -3750763034362895579L
    private const val PRIME: Long = 0x00000100000001B3L
    fun hash64(value: String): Long {
        var h = OFFSET_BASIS
        for (i in value.indices) {
            val b = value[i].code and 0xFF
            h = (h xor b.toLong()) * PRIME
        }
        return h
    }
}
```

- [ ] **Step 2: Add failing test**

Append:

```kotlin
    @Test
    fun `recordOffsetsForImdb returns list for known imdb id`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        // Fixture: byImdb { "tt5626028" -> ["11469","13881"] }
        val offsets = reader.recordOffsetsForImdb("tt5626028")
        assertEquals(2, offsets.size)
    }

    @Test
    fun `recordOffsetsForImdb returns empty for unknown imdb id`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertEquals(0, reader.recordOffsetsForImdb("tt9999999").size)
    }
```

- [ ] **Step 3: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReaderTest"`
Expected: FAIL.

- [ ] **Step 4: Implement**

Append:

```kotlin
    fun recordOffsetsForImdb(imdbId: String): IntArray {
        val open = state as? State.Open ?: return IntArray(0)
        val hash = StringHash.hash64(imdbId)
        val d = readDescriptor(open, IndexKind.BY_IMDB.slot)
        require(d.kind == BinaryFormat.KIND_IMDB) {
            "expected KIND_IMDB for BY_IMDB, got ${d.kind}"
        }
        var idx = binarySearchU64(open.parent, d.offset.toInt(), BinaryFormat.STRIDE_IMDB, d.count.toInt(), hash)
        if (idx < 0) return IntArray(0)
        // Walk backwards while hash equal (sort was stable on key string within
        // hash-equal runs, but binary search lands anywhere within the run).
        while (idx > 0 && open.parent.getLong(d.offset.toInt() + (idx - 1) * BinaryFormat.STRIDE_IMDB) == hash) idx--
        // Linear-probe forward, comparing the actual string at strOffset.
        while (idx < d.count.toInt()) {
            val entryStart = d.offset.toInt() + idx * BinaryFormat.STRIDE_IMDB
            if (open.parent.getLong(entryStart) != hash) break
            val strOff = open.parent.getInt(entryStart + 8)
            if (readPoolString(open, strOff) == imdbId) {
                val listOffset = open.parent.getInt(entryStart + 12)
                val listLen = open.parent.getInt(entryStart + 16)
                val result = IntArray(listLen)
                for (i in 0 until listLen) result[i] = open.parent.getInt(listOffset + i * 4)
                return result
            }
            idx++
        }
        return IntArray(0)
    }

    internal fun readPoolString(open: State.Open, stringPoolOffset: Int): String? {
        if (stringPoolOffset == BinaryFormat.NULL_STRING_OFFSET) return null
        val r = LongRef()
        val after = VarintReader.readULong(open.stringPool, stringPoolOffset, r)
        val len = r.value.toInt()
        val bytes = ByteArray(len)
        for (i in 0 until len) bytes[i] = open.stringPool.get(after + i)
        return String(bytes, Charsets.UTF_8)
    }
```

- [ ] **Step 5: Run**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReaderTest"`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt \
        app/src/main/java/com/nexio/tv/core/anime/binary/StringHash.kt \
        app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): imdb lookups via hash + string-pool collision probe

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 18: Decode full AnimeIdMapRecord (with lazy evidence)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt`

- [ ] **Step 1: Add failing test**

Append:

```kotlin
    @Test
    fun `recordAt returns full identity record with all fields populated`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        // Fixture: kitsu "11469" -> anidb 11739, tvdb 305074, tmdb 65930, imdb tt5626028, mediaType series, sourceType TV
        val offsets = reader.recordOffsetsForMultiKey(IndexKind.BY_TVDB, "305074")
        val rec = reader.recordAt(offsets[0])
        assertNotNull(rec)
        assertEquals("11469", rec!!.kitsu)
        assertEquals("11739", rec.anidb)
        assertEquals("305074", rec.tvdb)
        assertEquals("65930", rec.tmdb)
        assertEquals("tt5626028", rec.imdb)
        assertEquals("series", rec.mediaType)
        assertEquals("tv", rec.sourceType?.lowercase())
    }

    @Test
    fun `recordForKitsu returns null for unknown kitsu id`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertNull(reader.recordForKitsu("999999999"))
    }

    @Test
    fun `recordForKitsu strips kitsu prefix`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertNotNull(reader.recordForKitsu("kitsu:11469"))
    }
```

(`import org.junit.Assert.assertNotNull`)

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReaderTest"`
Expected: FAIL.

- [ ] **Step 3: Implement record decode**

Append:

```kotlin
    fun recordForKitsu(rawKitsuId: String): com.nexio.tv.core.anime.AnimeIdMapRecord? {
        val open = state as? State.Open ?: return null
        val key = rawKitsuId.removePrefix("kitsu:").toLongOrNull() ?: return null
        val entryIndex = findSingleEntry(open, IndexKind.BY_KITSU, key)
        if (entryIndex < 0) return null
        val d = readDescriptor(open, IndexKind.BY_KITSU.slot)
        val entryStart = d.offset.toInt() + entryIndex * BinaryFormat.STRIDE_U64_SINGLE
        val recordOffset = open.parent.getInt(entryStart + 8)
        return recordAt(recordOffset)
    }

    fun recordAt(recordOffset: Int): com.nexio.tv.core.anime.AnimeIdMapRecord? {
        val open = state as? State.Open ?: return null
        val records = open.records
        if (records.get(recordOffset) != BinaryFormat.RECORD_KIND_IDENTITY) return null
        var p = recordOffset + 1
        val presence = records.get(p).toInt() and 0xFF; p += 1
        val presence2 = records.get(p).toInt() and 0xFF; p += 1
        val r = LongRef()
        p = VarintReader.readULong(records, p, r); val kitsu = r.value.toString()
        val mal = if (presence and BinaryFormat.P_MAL != 0) { p = VarintReader.readULong(records, p, r); r.value.toString() } else null
        val anilist = if (presence and BinaryFormat.P_ANILIST != 0) { p = VarintReader.readULong(records, p, r); r.value.toString() } else null
        val anidb = if (presence and BinaryFormat.P_ANIDB != 0) { p = VarintReader.readULong(records, p, r); r.value.toString() } else null
        val tmdb = if (presence and BinaryFormat.P_TMDB != 0) { p = VarintReader.readULong(records, p, r); r.value.toString() } else null
        val tvdb = if (presence and BinaryFormat.P_TVDB != 0) { p = VarintReader.readULong(records, p, r); r.value.toString() } else null
        val imdb = if (presence and BinaryFormat.P_IMDB != 0) {
            val off = records.getInt(p); p += 4
            readPoolString(open, off)
        } else null
        val mediaType = if (presence and BinaryFormat.P_MEDIA_TYPE != 0) {
            val b = records.get(p).toInt(); p += 1
            BinaryFormat.MEDIA_TYPE_TABLE[b]
        } else null
        val sourceType = if (presence and BinaryFormat.P_SOURCE_TYPE != 0) {
            val b = records.get(p).toInt(); p += 1
            BinaryFormat.SOURCE_TYPE_TABLE[b]
        } else null
        val tvdbSeason = if (presence2 and BinaryFormat.P2_TVDB_SEASON != 0) { p = VarintReader.readULong(records, p, r); r.value.toString() } else null
        val tmdbSeason = if (presence2 and BinaryFormat.P2_TMDB_SEASON != 0) { p = VarintReader.readULong(records, p, r); r.value.toString() } else null
        val tvdbEpOff = if (presence2 and BinaryFormat.P2_TVDB_EP_OFFSET != 0) { p = VarintReader.readSInt(records, p, r); r.value.toInt() } else null
        val tmdbEpOff = if (presence2 and BinaryFormat.P2_TMDB_EP_OFFSET != 0) { p = VarintReader.readSInt(records, p, r); r.value.toInt() } else null
        val hasMappingRules = presence2 and BinaryFormat.P2_HAS_MAPPING_RULES != 0
        val evidenceOffsets: IntArray? = if (presence2 and BinaryFormat.P2_HAS_EVIDENCE != 0) {
            p = VarintReader.readULong(records, p, r)
            val count = r.value.toInt()
            val arr = IntArray(count)
            for (i in 0 until count) { arr[i] = records.getInt(p); p += 4 }
            arr
        } else null

        return com.nexio.tv.core.anime.AnimeIdMapRecord(
            kitsu = kitsu, mal = mal, anilist = anilist, anidb = anidb,
            tmdb = tmdb, tvdb = tvdb, imdb = imdb,
            mediaType = mediaType, sourceType = sourceType,
            tvdbSeason = tvdbSeason, tmdbSeason = tmdbSeason,
            tvdbEpisodeOffset = tvdbEpOff, tmdbEpisodeOffset = tmdbEpOff,
            hasMappingRules = hasMappingRules,
            evidence = if (evidenceOffsets == null) emptyList() else LazyEvidenceList(this, open, evidenceOffsets),
        )
    }

    /** Inflates evidence strings on first read, then caches. */
    private class LazyEvidenceList(
        private val reader: AnimeIdMapBinaryReader,
        private val open: State.Open,
        private val offsets: IntArray,
    ) : AbstractList<String>() {
        private var resolved: List<String>? = null
        override val size: Int get() = offsets.size
        override fun get(index: Int): String {
            val cached = resolved
            if (cached != null) return cached[index]
            val list = ArrayList<String>(offsets.size)
            for (i in offsets.indices) list.add(reader.readPoolString(open, offsets[i]) ?: "")
            resolved = list
            return list[index]
        }
    }
```

- [ ] **Step 4: Run**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReaderTest"`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt \
        app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): decode AnimeIdMapRecord with lazy evidence inflation

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 19: Decode AnimeEpisodeMappingRecord

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt`

- [ ] **Step 1: Add failing test**

```kotlin
    @Test
    fun `episodeMappingForAnidb returns record with ranges and explicit maps`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        // Fixture: episodeMappingsByAnidb { "69" -> One Piece, 5 ranges, 1 explicit map }
        val ep = reader.episodeMappingForAnidb("69")
        assertNotNull(ep)
        assertEquals("69", ep!!.anidb)
        assertEquals("One Piece", ep.name)
        assertEquals("81797", ep.tvdbSeriesId)
        assertEquals("37854", ep.tmdbTvId)
        assertEquals(5, ep.ranges.size)
        assertEquals(1, ep.explicitMaps.size)
        val firstRange = ep.ranges[0]
        assertEquals(1, firstRange.sourceSeason)
        assertEquals(1, firstRange.startEpisode)
        assertEquals(8, firstRange.endEpisode)
        assertEquals("TVDB", firstRange.targetProvider)
    }

    @Test
    fun `episodeMappingForAnidb returns null for unknown anidb id`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertNull(reader.episodeMappingForAnidb("9999999"))
    }
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReaderTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Append:

```kotlin
    fun episodeMappingForAnidb(anidbId: String): com.nexio.tv.core.anime.AnimeEpisodeMappingRecord? {
        val open = state as? State.Open ?: return null
        val key = anidbId.toLongOrNull() ?: return null
        val entryIndex = findSingleEntry(open, IndexKind.BY_ANIDB_EPISODE, key)
        if (entryIndex < 0) return null
        val d = readDescriptor(open, IndexKind.BY_ANIDB_EPISODE.slot)
        val entryStart = d.offset.toInt() + entryIndex * BinaryFormat.STRIDE_U64_SINGLE
        val recordOffset = open.parent.getInt(entryStart + 8)
        return episodeRecordAt(open, recordOffset)
    }

    private fun episodeRecordAt(open: State.Open, offset: Int): com.nexio.tv.core.anime.AnimeEpisodeMappingRecord? {
        val records = open.records
        if (records.get(offset) != BinaryFormat.RECORD_KIND_EPISODE) return null
        var p = offset + 1
        val presence = records.get(p).toInt() and 0xFF; p += 1
        val r = LongRef()
        p = VarintReader.readULong(records, p, r); val anidb = r.value.toString()
        val name = if (presence and 0x01 != 0) { val off = records.getInt(p); p += 4; readPoolString(open, off) } else null
        val tvdbSeriesId = if (presence and 0x02 != 0) { p = VarintReader.readULong(records, p, r); r.value.toString() } else null
        val tmdbTvId = if (presence and 0x04 != 0) { p = VarintReader.readULong(records, p, r); r.value.toString() } else null
        p = VarintReader.readULong(records, p, r); val rangesCount = r.value.toInt()
        val ranges = ArrayList<com.nexio.tv.core.anime.AnimeRangeRule>(rangesCount)
        for (i in 0 until rangesCount) {
            val srcSeason = records.get(p).toInt() and 0xFF; p += 1
            val startEp = records.getShort(p).toInt() and 0xFFFF; p += 2
            val endEpRaw = records.getShort(p).toInt() and 0xFFFF; p += 2
            val prov = records.get(p).toInt() and 0xFF; p += 1
            val tgtSeason = records.get(p).toInt() and 0xFF; p += 1
            val off = records.getShort(p).toInt(); p += 2
            val hasEnd = records.get(p).toInt() and 0x01 == 1; p += 1
            ranges.add(com.nexio.tv.core.anime.AnimeRangeRule(
                sourceSeason = srcSeason,
                startEpisode = startEp,
                endEpisode = if (hasEnd) endEpRaw else null,
                targetProvider = if (prov == 0) "TVDB" else "TMDB",
                targetSeason = tgtSeason,
                offset = off,
            ))
        }
        p = VarintReader.readULong(records, p, r); val explicitCount = r.value.toInt()
        val explicit = ArrayList<com.nexio.tv.core.anime.AnimeExplicitMap>(explicitCount)
        for (i in 0 until explicitCount) {
            val srcSeason = records.get(p).toInt() and 0xFF; p += 1
            val srcEp = records.getShort(p).toInt() and 0xFFFF; p += 2
            val prov = records.get(p).toInt() and 0xFF; p += 1
            val tgtSeason = records.get(p).toInt() and 0xFF; p += 1
            val tgtEp = records.getShort(p).toInt() and 0xFFFF; p += 2
            explicit.add(com.nexio.tv.core.anime.AnimeExplicitMap(
                sourceSeason = srcSeason, sourceEpisode = srcEp,
                targetProvider = if (prov == 0) "TVDB" else "TMDB",
                targetSeason = tgtSeason, targetEpisode = tgtEp,
            ))
        }
        val evidence = if (presence and 0x08 != 0) {
            p = VarintReader.readULong(records, p, r)
            val count = r.value.toInt()
            val list = ArrayList<String>(count)
            for (i in 0 until count) { val off = records.getInt(p); p += 4; list.add(readPoolString(open, off) ?: "") }
            list
        } else emptyList()
        return com.nexio.tv.core.anime.AnimeEpisodeMappingRecord(
            anidb = anidb, name = name, tvdbSeriesId = tvdbSeriesId, tmdbTvId = tmdbTvId,
            ranges = ranges, explicitMaps = explicit, evidence = evidence,
        )
    }
```

- [ ] **Step 4: Run**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReaderTest"`
Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReader.kt \
        app/src/test/java/com/nexio/tv/core/anime/binary/AnimeIdMapBinaryReaderTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): decode AnimeEpisodeMappingRecord (ranges + explicit maps)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 3 — Service swap

### Task 20: Swap AnimeIdMappingService to use the binary reader

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMapAsset.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt` (one local construction site)
- Modify: `app/src/test/java/com/nexio/tv/core/anime/AnimeIdMappingServiceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/anime/AnimeIdMappingServiceImdbTest.kt`

This is the cross-over task — after this, the asset is no longer loaded as Java objects. Do it in one commit so the codebase doesn't sit in an inconsistent state.

- [ ] **Step 1: Update the failing tests first (TDD)**

In `AnimeIdMappingServiceTest.kt`, replace every:

```kotlin
val service = AnimeIdMappingService(assetProvider = { fixtureAsset() })
```

with:

```kotlin
val service = AnimeIdMappingService(reader = testReader())
```

And add at the bottom of the file:

```kotlin
private fun testReader(): com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReader {
    val context = org.mockito.Mockito.mock(android.content.Context::class.java)
    val filesDir = java.nio.file.Files.createTempDirectory("anime-id-map-test").toFile()
    org.mockito.Mockito.`when`(context.filesDir).thenReturn(filesDir)
    val assets = org.mockito.Mockito.mock(android.content.res.AssetManager::class.java)
    org.mockito.Mockito.`when`(context.assets).thenReturn(assets)
    val fixture = java.io.File("src/test/resources/anime/nexio-anime-map-v1-test.bin")
    org.mockito.Mockito.`when`(assets.open("anime/nexio-anime-map-v1.bin"))
        .thenAnswer { java.io.FileInputStream(fixture) }
    val reader = com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReader(context)
    reader.ensureOpen()
    return reader
}
```

Remove the now-unused `fixtureAsset()` helper.

Apply the same swap in `AnimeIdMappingServiceImdbTest.kt`.

Also remove the two tests that pass a throwing `assetProvider` (`resolveKitsuId returns null when assetProvider throws (missing asset on profileable)` and `resolveKitsuId returns null when assetProvider throws JSON parse error`) — they no longer apply; the reader handles those failures via its own `State.Failed` path tested in `AnimeIdMapBinaryReaderTest`. Replace them with a single equivalent test:

```kotlin
@Test
fun `resolveKitsuId returns null when reader is in failed state`() {
    val context = org.mockito.Mockito.mock(android.content.Context::class.java)
    org.mockito.Mockito.`when`(context.filesDir).thenReturn(java.nio.file.Files.createTempDirectory("aim-fail").toFile())
    val assets = org.mockito.Mockito.mock(android.content.res.AssetManager::class.java)
    org.mockito.Mockito.`when`(context.assets).thenReturn(assets)
    org.mockito.Mockito.`when`(assets.open("anime/nexio-anime-map-v1.bin"))
        .thenThrow(java.io.FileNotFoundException("missing"))
    val reader = com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReader(context)
    reader.ensureOpen()
    val service = AnimeIdMappingService(reader = reader)

    val resolved = service.resolveKitsuId(
        id = AnimeStremioId(source = AnimeIdSource.MAL, value = "1"),
        mediaKind = ContentMediaKind.SERIES,
    )
    assertNull(resolved)
}
```

- [ ] **Step 2: Run tests, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.AnimeIdMappingServiceTest"`
Expected: FAIL — the `reader =` constructor parameter doesn't exist yet.

- [ ] **Step 3: Rewrite `AnimeIdMappingService.kt`**

Replace the entire file with:

```kotlin
package com.nexio.tv.core.anime

import com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReader
import com.nexio.tv.core.anime.binary.IndexKind
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimeIdMappingService(
    private val reader: AnimeIdMapBinaryReader,
) {
    @Inject
    constructor(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context)
        : this(AnimeIdMapBinaryReader(context))

    fun warmUp() {
        reader.ensureOpen()
    }

    fun resolveKitsuId(id: AnimeStremioId, mediaKind: ContentMediaKind): String? {
        reader.ensureOpen()
        return when (id.source) {
            AnimeIdSource.KITSU -> if (reader.containsKitsu(id.value)) id.value else id.value
            AnimeIdSource.MAL -> reader.lookupSingle(IndexKind.BY_MAL, id.value)
            AnimeIdSource.ANILIST -> reader.lookupSingle(IndexKind.BY_ANILIST, id.value)
            AnimeIdSource.ANIDB -> reader.lookupSingle(IndexKind.BY_ANIDB, id.value)
            AnimeIdSource.TVDB -> reader.lookupMultiFirst(IndexKind.BY_TVDB, id.value)
            AnimeIdSource.IMDB -> resolveImdbKitsuId(id.value, mediaKind)
            AnimeIdSource.TMDB -> when (mediaKind) {
                ContentMediaKind.MOVIE -> reader.lookupSingle(IndexKind.BY_TMDB_MOVIE, id.value)
                ContentMediaKind.SERIES -> reader.lookupMultiFirst(IndexKind.BY_TMDB_TV, id.value)
            }
        }
    }

    private fun resolveImdbKitsuId(imdbId: String, mediaKind: ContentMediaKind): String? {
        val offsets = reader.recordOffsetsForImdb(imdbId)
        if (offsets.isEmpty()) return null
        // Prefer first whose mediaType matches
        for (i in offsets.indices) {
            val rec = reader.recordAt(offsets[i]) ?: continue
            if (matches(rec, mediaKind)) return rec.kitsu
        }
        return reader.recordAt(offsets[0])?.kitsu
    }

    fun resolveProviderIdsForKitsu(kitsuId: String, mediaKind: ContentMediaKind): ProviderIds {
        reader.ensureOpen()
        val clean = kitsuId.trim().removePrefix("kitsu:").takeIf { it.isNotBlank() } ?: return ProviderIds()
        val record = reader.recordForKitsu(clean)?.takeIf { matches(it, mediaKind) }
            ?: return ProviderIds(kitsu = clean)
        return ProviderIds(
            imdb = record.imdb, tmdb = record.tmdb, tvdb = record.tvdb,
            kitsu = record.kitsu, mal = record.mal, anilist = record.anilist, anidb = record.anidb,
        )
    }

    fun recordForKitsuId(kitsuId: String): AnimeIdMapRecord? {
        reader.ensureOpen()
        return reader.recordForKitsu(kitsuId)
    }

    fun recordForAnidbId(anidbId: String): AnimeIdMapRecord? {
        reader.ensureOpen()
        val kitsu = reader.lookupSingle(IndexKind.BY_ANIDB, anidbId) ?: return null
        return reader.recordForKitsu(kitsu)
    }

    fun episodeMappingForAnidb(anidbId: String): AnimeEpisodeMappingRecord? {
        reader.ensureOpen()
        return reader.episodeMappingForAnidb(anidbId)
    }

    fun recordsForImdbId(imdbId: String): List<AnimeIdMapRecord> {
        reader.ensureOpen()
        val offsets = reader.recordOffsetsForImdb(imdbId)
        if (offsets.isEmpty()) return emptyList()
        val list = ArrayList<AnimeIdMapRecord>(offsets.size)
        for (i in offsets.indices) reader.recordAt(offsets[i])?.let { list.add(it) }
        return list
    }

    fun isAnimeImdbId(imdbId: String): Boolean {
        reader.ensureOpen()
        return reader.recordOffsetsForImdb(imdbId).isNotEmpty()
    }

    fun allSeriesRecordsSharingTvdb(record: AnimeIdMapRecord): List<AnimeIdMapRecord> {
        reader.ensureOpen()
        val tvdb = record.tvdb?.takeIf { it.isNotBlank() } ?: return listOf(record)
        val offsets = reader.recordOffsetsForMultiKey(IndexKind.BY_TVDB, tvdb)
        if (offsets.isEmpty()) return listOf(record)
        val all = ArrayList<AnimeIdMapRecord>(offsets.size)
        for (i in offsets.indices) reader.recordAt(offsets[i])?.let { all.add(it) }
        val filtered = all.filter { isSeriesTvEntry(it) }
        return filtered.ifEmpty { listOf(record) }
    }

    private fun isSeriesTvEntry(record: AnimeIdMapRecord): Boolean {
        val mediaType = record.mediaType?.lowercase() ?: return true
        val sourceType = record.sourceType?.lowercase() ?: ""
        return mediaType == "series" && sourceType in setOf("tv", "")
    }

    private fun matches(record: AnimeIdMapRecord, mediaKind: ContentMediaKind): Boolean {
        val type = record.mediaType?.trim()?.lowercase() ?: return true
        return when (mediaKind) {
            ContentMediaKind.MOVIE -> type == "movie"
            ContentMediaKind.SERIES -> type != "movie"
        }
    }
}
```

- [ ] **Step 4: Trim `AnimeIdMapAsset.kt`**

Replace the entire file with just the data classes the public API still returns:

```kotlin
package com.nexio.tv.core.anime

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AnimeIdMapRecord(
    @Json(name = "kitsu") val kitsu: String,
    @Json(name = "mal") val mal: String? = null,
    @Json(name = "anilist") val anilist: String? = null,
    @Json(name = "anidb") val anidb: String? = null,
    @Json(name = "tmdb") val tmdb: String? = null,
    @Json(name = "tvdb") val tvdb: String? = null,
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "mediaType") val mediaType: String? = null,
    @Json(name = "sourceType") val sourceType: String? = null,
    @Json(name = "tvdbSeason") val tvdbSeason: String? = null,
    @Json(name = "tmdbSeason") val tmdbSeason: String? = null,
    @Json(name = "tvdbEpisodeOffset") val tvdbEpisodeOffset: Int? = null,
    @Json(name = "tmdbEpisodeOffset") val tmdbEpisodeOffset: Int? = null,
    @Json(name = "hasMappingRules") val hasMappingRules: Boolean = false,
    @Json(name = "evidence") val evidence: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AnimeEpisodeMappingRecord(
    @Json(name = "anidb") val anidb: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "tvdbSeriesId") val tvdbSeriesId: String? = null,
    @Json(name = "tmdbTvId") val tmdbTvId: String? = null,
    @Json(name = "ranges") val ranges: List<AnimeRangeRule> = emptyList(),
    @Json(name = "explicitMaps") val explicitMaps: List<AnimeExplicitMap> = emptyList(),
    @Json(name = "evidence") val evidence: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AnimeRangeRule(
    @Json(name = "sourceSeason") val sourceSeason: Int,
    @Json(name = "startEpisode") val startEpisode: Int,
    @Json(name = "endEpisode") val endEpisode: Int? = null,
    @Json(name = "targetProvider") val targetProvider: String,
    @Json(name = "targetSeason") val targetSeason: Int,
    @Json(name = "offset") val offset: Int,
)

@JsonClass(generateAdapter = true)
data class AnimeExplicitMap(
    @Json(name = "sourceSeason") val sourceSeason: Int,
    @Json(name = "sourceEpisode") val sourceEpisode: Int,
    @Json(name = "targetProvider") val targetProvider: String,
    @Json(name = "targetSeason") val targetSeason: Int,
    @Json(name = "targetEpisode") val targetEpisode: Int,
)

enum class ContentMediaKind { MOVIE, SERIES }
```

- [ ] **Step 5: Update `TraktProgressService.kt`'s local construction**

Open `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`. Search for the existing line (~103):

```kotlin
private val animeIdMappingService: AnimeIdMappingService = AnimeIdMappingService { com.nexio.tv.core.anime.AnimeIdMapAsset(schemaVersion = 0) }
```

This is a degraded standalone constructor for an edge case (legacy code path). Replace with a similarly-degraded version that points at a non-existent asset path — the reader will land in `State.Failed` and all lookups return null, identical behavior:

```kotlin
private val animeIdMappingService: AnimeIdMappingService = run {
    // Standalone constructor for a code path that runs before Hilt is initialized.
    // The reader degrades to State.Failed (all lookups return null) when asset+filesDir
    // are unavailable — equivalent to the previous empty-asset behavior.
    val ctx = com.nexio.tv.core.NexioContext.application()
        ?: error("TraktProgressService standalone construction requires app context")
    AnimeIdMappingService(reader = com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReader(ctx))
}
```

If `NexioContext.application()` doesn't exist, replace this whole construction with a clear TODO that asks the calling site to inject a real instance — but first check whether this code path is actually live:

```bash
grep -n "TraktProgressService(" app/src/main/java | head -10
```

If `TraktProgressService` is always constructed via Hilt (`@Inject constructor`), the local instance is dead code and can be deleted entirely. Verify, then prefer deletion over keeping a degraded fallback.

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

If `AnimeIdMapAsset` is referenced anywhere else (the deleted wrapper class):

```bash
grep -rn "AnimeIdMapAsset\b" app/src/main/java
```

Fix each callsite — they should never have been reaching past the public API. If anything other than the swapped files appears, this is a sign a caller is doing something unintended; investigate before deleting.

- [ ] **Step 7: Run all anime tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.*"`
Expected: PASS — all existing test cases must still pass (the contract regression gate).

- [ ] **Step 8: Run the full app test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt \
        app/src/main/java/com/nexio/tv/core/anime/AnimeIdMapAsset.kt \
        app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/core/anime/AnimeIdMappingServiceTest.kt \
        app/src/test/java/com/nexio/tv/core/anime/AnimeIdMappingServiceImdbTest.kt
git commit -m "$(cat <<'EOF'
feat(anime-id-map): swap AnimeIdMappingService to AnimeIdMapBinaryReader

Public API of AnimeIdMappingService is byte-identical; the 9 read methods
now delegate to the mmap'd binary reader instead of holding a parsed
JSON model on the JVM heap. AnimeIdMapAsset is reduced to just the data
classes returned by the public API (records + episode mappings).

Eliminates the ~15 MiB retained baseline (12,960 identity records + 8
HashMap indexes); replaced by ~5–6 MiB off-heap mmap'd region.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 21: On-device smoke test (CLAUDE.md rule #8 — profile selection!)

**Files:** None modified — verification only.

- [ ] **Step 1: Build debug APK and install**

```bash
./gradlew :app:installDebug
```
Expected: BUILD SUCCESSFUL, APK installed on `192.168.50.98:5555`.

- [ ] **Step 2: Run smoke test per CLAUDE.md rule #8**

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
adb -s 192.168.50.98:5555 logcat -d -t 600 | grep -E "FATAL|AndroidRuntime|ANR|ClassCast|NoSuchMethod" | tail -20
```
Expected: no FATAL / ANR / ClassCast entries.

- [ ] **Step 3: Verify reader opened**

```bash
adb -s 192.168.50.98:5555 logcat -d | grep -E "AnimeIdMap" | tail -10
```
Expected: a line like `AnimeIdMapBinaryReader: open ok schema=1 sizeBytes=5847296` (size will vary).

- [ ] **Step 4: Verify file on disk**

```bash
adb -s 192.168.50.98:5555 shell "run-as com.nexiodebug.tv ls -la files/anime-id-map/"
```
Expected: `fmt1.bin` with size ~5–6 MiB.

If any of steps 2–4 fail, do NOT proceed. Capture the failing logcat output, debug, and re-run.

---

### Task 22: Heap-dump verification of retention delta

**Files:** None modified — verification only.

- [ ] **Step 1: Capture heap dump after Modern Home soak**

```bash
# Profile already selected from Task 21; if not, run smoke-test sequence first.
adb -s 192.168.50.98:5555 shell am dumpheap com.nexiodebug.tv /sdcard/Download/animeid-after.hprof
sleep 5
adb -s 192.168.50.98:5555 pull /sdcard/Download/animeid-after.hprof ./animeid-after.hprof
```

- [ ] **Step 2: Run heaptrail; assert retention**

```bash
heaptrail -i animeid-after.hprof --class "com.nexio.tv.core.anime.AnimeIdMapRecord"
heaptrail -i animeid-after.hprof --class "com.nexio.tv.core.anime.AnimeIdMapIndexes" 2>&1 | head -5
heaptrail -i animeid-after.hprof --class "com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReader"
heaptrail -i animeid-after.hprof --class "java.nio.DirectByteBuffer" | head -30
```

Expected outputs:
- `AnimeIdMapRecord`: 0 retained instances (transient young-gen only — distinguished by GC-root distance).
- `AnimeIdMapIndexes`: **class not found** (the class no longer exists after Task 20).
- `AnimeIdMapBinaryReader`: exactly 1 instance.
- `DirectByteBuffer` count: increased by ≤7 (the parent buffer + 6 slice views) vs baseline.

- [ ] **Step 3: Verify off-heap region size**

```bash
adb -s 192.168.50.98:5555 shell "run-as com.nexiodebug.tv ls -la files/anime-id-map/"
```
Expected: `fmt1.bin` size matches the APK asset `nexio-anime-map-v1.bin` size.

- [ ] **Step 4: Document the delta in a memory note**

Add a one-liner to `MEMORY.md`:

```markdown
- [Anime ID map mmap'd off-heap 2026-05-11](project_anime_id_map_mmap_offheap.md) — `AnimeIdMappingService` migrated to mmap'd binary; heap retention dropped ~15 MiB→<1 MiB, replaced by ~5–6 MiB off-heap region. Commits: <list final commit shas>.
```

…and create the linked file capturing what's done, what's deferred (none), and the commit list.

- [ ] **Step 5: Commit the memory note**

```bash
git add /Users/jneerdael/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/project_anime_id_map_mmap_offheap.md \
        /Users/jneerdael/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/MEMORY.md
git commit -m "$(cat <<'EOF'
docs(memory): record anime-id-map mmap'd off-heap migration milestone

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Done

After Task 22, the migration is complete:

- `AnimeIdMappingService`'s public API is unchanged.
- ~15 MiB of permanent JVM-heap retention is gone (12,960 `AnimeIdMapRecord` + 8 `HashMap` indexes).
- ~5–6 MiB sits off-heap as a `MappedByteBuffer`, evictable under memory pressure.
- The on-device smoke test confirms no crashes / ANRs.
- The heap dump confirms the retention delta.
- A `check`-side Gradle task guarantees the committed `.bin` never drifts from the source JSON.

If a future asset bump regenerates the JSON, the workflow is:
1. Run `:tools:anime-mapping-generator:generateAnimeMappingAsset` (existing) to update `nexio-anime-map-v1.json`.
2. Run `:app:generateAnimeIdMapBinary` to re-encode the binary.
3. Commit both files; CI's `checkAnimeIdMapBinary` will pass.
