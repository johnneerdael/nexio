# Nexio Anime Mapping Pack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace runtime season/episode projection heuristics in `DefaultAnimeSeasonProjectionResolver` with build-time curated data combining Fribb (identity bridge), ScudLee `anime-list-full.xml` (season/episode mapping rules), and a Nexio overlay (corrections), generated at CI time and bundled in the APK.

**Architecture:** A new Kotlin JVM module `:tools:anime-mapping-generator` fetches both upstream sources, parses them, applies a 4-mode JSON overlay, and emits `app/src/main/assets/anime/nexio-anime-map-v1.json` (replaces the existing `anime-id-map.json`). The runtime resolver becomes a strict lookup against the curated data — when curated data is missing, it returns a typed unresolved result rather than inventing coordinates. `AnimeSeasonPresentationCache` (Phase 2.1) and the flat-franchise heuristic are retired.

**Tech Stack:** Kotlin 2.3.0 (JDK 17), Moshi 1.15.1 with KSP codegen (matches existing app pattern), JUnit 4.13.2, MockK 1.13.12, kotlinx-coroutines-test 1.8.1. Built-in JDK XML parsing (`javax.xml.parsers.DocumentBuilderFactory`) and `java.net.HttpURLConnection`. Reference design: `docs/superpowers/specs/2026-05-06-anime-mapping-pack-design.md`.

---

## Branch Setup

This plan is intended to run on a dedicated branch off `origin/main`. Before Task 1:

```bash
git checkout main
git pull
git checkout -b feature/anime-mapping-pack
```

---

## File Structure

### Created

```
tools/anime-mapping-generator/
  build.gradle.kts
  src/
    main/kotlin/com/nexio/animemap/
      Main.kt
      model/AssetSchema.kt           IdentityRecord, EpisodeMappingRecord, RangeRule, ExplicitMap, NexioAnimeMap, MapIndexes
      model/Provenance.kt            Provenance file structure
      model/Overlay.kt               OverlayFile, OverlayEntry, OverlayMode enum
      model/SeasonMarkerWire.kt      String <-> typed marker conversion
      fetch/UpstreamFetcher.kt       HTTP fetch + commit SHA capture
      parse/FribbJsonParser.kt
      parse/ScudleeXmlParser.kt
      parse/MappingListExpander.kt
      merge/IdentityMerger.kt
      merge/OverlayApplier.kt
      emit/IndexBuilder.kt
      emit/AssetWriter.kt
    main/resources/
      nexio-anime-overlay.json       Hand-curated overrides (starts empty)
    test/kotlin/com/nexio/animemap/
      model/SeasonMarkerWireTest.kt
      parse/FribbJsonParserTest.kt
      parse/ScudleeXmlParserTest.kt
      parse/MappingListExpanderTest.kt
      merge/IdentityMergerTest.kt
      merge/OverlayApplierTest.kt
      emit/IndexBuilderTest.kt
      AssetFixtureEndToEndTest.kt
    test/resources/fixtures/
      mha-eight-seasons.xml
      one-piece.xml
      chobits.xml
      fribb-mha-and-one-piece.json
      overlay-examples.json

app/src/main/java/com/nexio/tv/core/anime/projection/
  SeasonMarker.kt                    Sealed interface (Number/Absolute/Hentai/Unknown)

app/src/main/assets/anime/
  nexio-anime-map-v1.json            Generated at build time
  nexio-anime-map-provenance.json    Generated at build time
```

### Modified

```
.gitignore                           Add !tools/anime-mapping-generator/ exception
settings.gradle.kts                  include(":tools:anime-mapping-generator")
app/build.gradle.kts                 Delete inline Fribb code (lines ~158-296), depend on generator
app/src/main/java/com/nexio/tv/core/anime/AnimeIdMapAsset.kt          New schema v2
app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt    New lookups
app/src/main/java/com/nexio/tv/core/anime/projection/FallbackReason.kt          New reasons; remove old
app/src/main/java/com/nexio/tv/core/anime/projection/SeasonPresentationSource.kt New sources; remove old
app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt  Full rewrite
app/src/main/java/com/nexio/tv/core/di/AnimeProjectionModule.kt       Remove cache binding
app/src/main/java/com/nexio/tv/core/trace/AnimeProjectionTraceEvents.kt          New events
app/src/test/java/com/nexio/tv/core/anime/projection/*Test.kt         Updated for new schema
```

### Deleted

```
app/src/main/assets/anime/anime-id-map.json
app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonPresentationCache.kt
app/src/main/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCache.kt
app/src/test/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCacheTest.kt
```

---

## Task 1: Module setup

**Files:**
- Modify: `.gitignore`
- Modify: `settings.gradle.kts`
- Create: `tools/anime-mapping-generator/build.gradle.kts`
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/.gitkeep`

- [ ] **Step 1: Add gitignore exception for the generator module**

`tools/` is already gitignored. Append a negation rule. Open `.gitignore` and add to the very end (so the rule is processed last):

```
!tools/anime-mapping-generator/
!tools/anime-mapping-generator/**
```

- [ ] **Step 2: Register the module in settings.gradle.kts**

Find line 69 `include(":app")` in `settings.gradle.kts`. Add immediately after:

```kotlin
include(":tools:anime-mapping-generator")
```

- [ ] **Step 3: Create the module build script**

Create `tools/anime-mapping-generator/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    alias(libs.plugins.ksp)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.nexio.animemap.MainKt")
}

dependencies {
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
```

- [ ] **Step 4: Create package directory and verify build**

```bash
mkdir -p tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap
mkdir -p tools/anime-mapping-generator/src/main/resources
mkdir -p tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap
mkdir -p tools/anime-mapping-generator/src/test/resources/fixtures
touch tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/.gitkeep
./gradlew :tools:anime-mapping-generator:compileKotlin 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -f .gitignore settings.gradle.kts tools/anime-mapping-generator/
git commit -m "build(anime-mapping-generator): scaffold tools module with Kotlin JVM + Moshi"
```

---

## Task 2: Asset schema model classes

**Files:**
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/model/AssetSchema.kt`
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/model/Provenance.kt`

- [ ] **Step 1: Create the asset schema file**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/model/AssetSchema.kt`:

```kotlin
package com.nexio.animemap.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class NexioAnimeMap(
    @Json(name = "schemaVersion") val schemaVersion: Int,
    @Json(name = "mappingPolicyVersion") val mappingPolicyVersion: Int,
    @Json(name = "generatedAt") val generatedAt: String,
    @Json(name = "counts") val counts: AssetCounts,
    @Json(name = "identityRecordsByKitsu") val identityRecordsByKitsu: Map<String, IdentityRecord>,
    @Json(name = "episodeMappingsByAnidb") val episodeMappingsByAnidb: Map<String, EpisodeMappingRecord>,
    @Json(name = "indexes") val indexes: MapIndexes
)

@JsonClass(generateAdapter = true)
data class AssetCounts(
    @Json(name = "identityRecords") val identityRecords: Int,
    @Json(name = "episodeMappingRecords") val episodeMappingRecords: Int,
    @Json(name = "skippedCount") val skippedCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class IdentityRecord(
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
    @Json(name = "evidence") val evidence: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class EpisodeMappingRecord(
    @Json(name = "anidb") val anidb: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "tvdbSeriesId") val tvdbSeriesId: String? = null,
    @Json(name = "tmdbTvId") val tmdbTvId: String? = null,
    @Json(name = "ranges") val ranges: List<RangeRule> = emptyList(),
    @Json(name = "explicitMaps") val explicitMaps: List<ExplicitMap> = emptyList(),
    @Json(name = "evidence") val evidence: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RangeRule(
    @Json(name = "sourceSeason") val sourceSeason: Int,
    @Json(name = "startEpisode") val startEpisode: Int,
    @Json(name = "endEpisode") val endEpisode: Int? = null,
    @Json(name = "targetProvider") val targetProvider: String,
    @Json(name = "targetSeason") val targetSeason: Int,
    @Json(name = "offset") val offset: Int
)

@JsonClass(generateAdapter = true)
data class ExplicitMap(
    @Json(name = "sourceSeason") val sourceSeason: Int,
    @Json(name = "sourceEpisode") val sourceEpisode: Int,
    @Json(name = "targetProvider") val targetProvider: String,
    @Json(name = "targetSeason") val targetSeason: Int,
    @Json(name = "targetEpisode") val targetEpisode: Int
)

@JsonClass(generateAdapter = true)
data class MapIndexes(
    @Json(name = "byKitsu") val byKitsu: Map<String, String> = emptyMap(),
    @Json(name = "byMal") val byMal: Map<String, String> = emptyMap(),
    @Json(name = "byAnilist") val byAnilist: Map<String, String> = emptyMap(),
    @Json(name = "byAnidb") val byAnidb: Map<String, String> = emptyMap(),
    @Json(name = "byTvdb") val byTvdb: Map<String, List<String>> = emptyMap(),
    @Json(name = "byTmdbTv") val byTmdbTv: Map<String, List<String>> = emptyMap(),
    @Json(name = "byTmdbMovie") val byTmdbMovie: Map<String, String> = emptyMap(),
    @Json(name = "byImdb") val byImdb: Map<String, String> = emptyMap()
)
```

- [ ] **Step 2: Create the provenance schema file**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/model/Provenance.kt`:

```kotlin
package com.nexio.animemap.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ProvenanceFile(
    @Json(name = "generatedAt") val generatedAt: String,
    @Json(name = "sources") val sources: Map<String, ProvenanceSource>,
    @Json(name = "overlay") val overlay: ProvenanceOverlay,
    @Json(name = "counts") val counts: AssetCounts
)

@JsonClass(generateAdapter = true)
data class ProvenanceSource(
    @Json(name = "url") val url: String,
    @Json(name = "commit") val commit: String?,
    @Json(name = "fetchedAt") val fetchedAt: String
)

@JsonClass(generateAdapter = true)
data class ProvenanceOverlay(
    @Json(name = "version") val version: Int,
    @Json(name = "entryCount") val entryCount: Int
)
```

- [ ] **Step 3: Verify KSP generates adapters and module compiles**

```bash
./gradlew :tools:anime-mapping-generator:compileKotlin 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`. Generated adapters appear under `tools/anime-mapping-generator/build/generated/ksp/main/kotlin/`.

- [ ] **Step 4: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/model/
git commit -m "feat(anime-mapping-generator): add asset and provenance model classes"
```

---

## Task 3: Overlay model classes

**Files:**
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/model/Overlay.kt`

- [ ] **Step 1: Create the overlay schema file**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/model/Overlay.kt`:

```kotlin
package com.nexio.animemap.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OverlayFile(
    @Json(name = "schemaVersion") val schemaVersion: Int,
    @Json(name = "entries") val entries: List<OverlayEntry>
)

@JsonClass(generateAdapter = true)
data class OverlayEntry(
    @Json(name = "anidb") val anidb: String,
    @Json(name = "mode") val mode: String,
    @Json(name = "reason") val reason: String,
    @Json(name = "target") val target: String? = null,
    @Json(name = "patch") val patch: OverlayPatch? = null,
    @Json(name = "record") val record: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class OverlayPatch(
    @Json(name = "tvdbSeason") val tvdbSeason: String? = null,
    @Json(name = "tmdbSeason") val tmdbSeason: String? = null,
    @Json(name = "tvdbEpisodeOffset") val tvdbEpisodeOffset: Int? = null,
    @Json(name = "tmdbEpisodeOffset") val tmdbEpisodeOffset: Int? = null,
    @Json(name = "tvdb") val tvdb: String? = null,
    @Json(name = "tmdb") val tmdb: String? = null,
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "addRanges") val addRanges: List<RangeRule> = emptyList(),
    @Json(name = "removeRanges") val removeRanges: List<RangeRuleKey> = emptyList(),
    @Json(name = "addExplicitMaps") val addExplicitMaps: List<ExplicitMap> = emptyList(),
    @Json(name = "removeExplicitMaps") val removeExplicitMaps: List<ExplicitMapKey> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RangeRuleKey(
    @Json(name = "sourceSeason") val sourceSeason: Int,
    @Json(name = "startEpisode") val startEpisode: Int,
    @Json(name = "targetProvider") val targetProvider: String
)

@JsonClass(generateAdapter = true)
data class ExplicitMapKey(
    @Json(name = "sourceSeason") val sourceSeason: Int,
    @Json(name = "sourceEpisode") val sourceEpisode: Int,
    @Json(name = "targetProvider") val targetProvider: String
)

object OverlayMode {
    const val PATCH_IDENTITY = "patch-identity"
    const val PATCH_MAPPING = "patch-mapping"
    const val REPLACE = "replace"
    const val DROP = "drop"

    val ALL = setOf(PATCH_IDENTITY, PATCH_MAPPING, REPLACE, DROP)
}

object OverlayTarget {
    const val IDENTITY = "identity"
    const val MAPPING = "mapping"
}
```

- [ ] **Step 2: Create empty starter overlay file**

Create `tools/anime-mapping-generator/src/main/resources/nexio-anime-overlay.json`:

```json
{
  "schemaVersion": 1,
  "entries": []
}
```

- [ ] **Step 3: Verify compile**

```bash
./gradlew :tools:anime-mapping-generator:compileKotlin 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/model/Overlay.kt \
        tools/anime-mapping-generator/src/main/resources/nexio-anime-overlay.json
git commit -m "feat(anime-mapping-generator): add overlay model classes and empty starter file"
```

---

## Task 4: SeasonMarker wire encoder

**Files:**
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/model/SeasonMarkerWire.kt`
- Create: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/model/SeasonMarkerWireTest.kt`

- [ ] **Step 1: Write failing test**

Create `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/model/SeasonMarkerWireTest.kt`:

```kotlin
package com.nexio.animemap.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonMarkerWireTest {

    @Test fun `numeric season marker round-trips`() {
        val wire = SeasonMarkerWire.normalize("3")
        assertEquals("3", wire)
    }

    @Test fun `absolute marker normalizes to lowercase a`() {
        assertEquals("a", SeasonMarkerWire.normalize("a"))
        assertEquals("a", SeasonMarkerWire.normalize("A"))
    }

    @Test fun `hentai marker normalizes lowercase`() {
        assertEquals("hentai", SeasonMarkerWire.normalize("hentai"))
        assertEquals("hentai", SeasonMarkerWire.normalize("Hentai"))
    }

    @Test fun `unknown marker normalizes lowercase`() {
        assertEquals("unknown", SeasonMarkerWire.normalize("unknown"))
        assertEquals("unknown", SeasonMarkerWire.normalize("UNKNOWN"))
    }

    @Test fun `empty string returns null`() {
        assertNull(SeasonMarkerWire.normalize(""))
        assertNull(SeasonMarkerWire.normalize("   "))
    }

    @Test fun `null input returns null`() {
        assertNull(SeasonMarkerWire.normalize(null))
    }

    @Test fun `unrecognized non-numeric returns null`() {
        assertNull(SeasonMarkerWire.normalize("xyz"))
    }

    @Test fun `numeric value with surrounding whitespace is normalized`() {
        assertEquals("7", SeasonMarkerWire.normalize(" 7 "))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.model.SeasonMarkerWireTest" 2>&1 | grep -E "FAILED|BUILD|error:"
```

Expected: `BUILD FAILED` — `SeasonMarkerWire` doesn't exist.

- [ ] **Step 3: Create the encoder**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/model/SeasonMarkerWire.kt`:

```kotlin
package com.nexio.animemap.model

object SeasonMarkerWire {
    private val KNOWN = setOf("a", "hentai", "unknown")

    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase() ?: return null
        if (trimmed.isEmpty()) return null
        if (trimmed in KNOWN) return trimmed
        return trimmed.toIntOrNull()?.toString()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.model.SeasonMarkerWireTest" 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/model/SeasonMarkerWire.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/model/SeasonMarkerWireTest.kt
git commit -m "feat(anime-mapping-generator): add SeasonMarkerWire normalization"
```

---

## Task 5: FribbJsonParser

**Files:**
- Create: `tools/anime-mapping-generator/src/test/resources/fixtures/fribb-mha-and-one-piece.json`
- Create: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/parse/FribbJsonParserTest.kt`
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/parse/FribbJsonParser.kt`

- [ ] **Step 1: Create fixture file**

Create `tools/anime-mapping-generator/src/test/resources/fixtures/fribb-mha-and-one-piece.json`:

```json
[
  {
    "type": "TV",
    "anidb_id": 11739,
    "kitsu_id": 11469,
    "tvdb_id": 305074,
    "themoviedb_id": 65930,
    "imdb_id": "tt5626028",
    "mal_id": 31964,
    "anilist_id": 21459,
    "season": { "tvdb": 1, "tmdb": 1 }
  },
  {
    "type": "TV",
    "anidb_id": 13485,
    "kitsu_id": 13881,
    "tvdb_id": 305074,
    "themoviedb_id": 65930,
    "imdb_id": "tt5626028",
    "mal_id": 36456,
    "anilist_id": 100166,
    "season": { "tvdb": 3, "tmdb": 3 }
  },
  {
    "type": "TV",
    "anidb_id": 69,
    "kitsu_id": 12,
    "tvdb_id": 81797,
    "themoviedb_id": 37854,
    "imdb_id": "tt0388629",
    "mal_id": 21,
    "anilist_id": 21
  }
]
```

- [ ] **Step 2: Write failing parser test**

Create `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/parse/FribbJsonParserTest.kt`:

```kotlin
package com.nexio.animemap.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FribbJsonParserTest {

    private fun fixture(): String =
        javaClass.getResource("/fixtures/fribb-mha-and-one-piece.json")!!.readText()

    @Test fun `parses all id fields for MHA S1`() {
        val parser = FribbJsonParser()
        val frags = parser.parse(fixture())
        val mha = frags.first { it.anidb == "11739" }
        assertEquals("11469", mha.kitsu)
        assertEquals("305074", mha.tvdb)
        assertEquals("65930", mha.tmdb)
        assertEquals("tt5626028", mha.imdb)
        assertEquals("31964", mha.mal)
        assertEquals("21459", mha.anilist)
        assertEquals("TV", mha.sourceType)
    }

    @Test fun `extracts tvdb season for seasonal anime`() {
        val parser = FribbJsonParser()
        val frags = parser.parse(fixture())
        val mhaS3 = frags.first { it.anidb == "13485" }
        assertEquals("3", mhaS3.tvdbSeasonHint)
        assertEquals("3", mhaS3.tmdbSeasonHint)
    }

    @Test fun `tvdb season is null when source omits season field`() {
        val parser = FribbJsonParser()
        val frags = parser.parse(fixture())
        val onePiece = frags.first { it.anidb == "69" }
        assertNull(onePiece.tvdbSeasonHint)
        assertNull(onePiece.tmdbSeasonHint)
    }

    @Test fun `parses three records from fixture`() {
        val parser = FribbJsonParser()
        val frags = parser.parse(fixture())
        assertEquals(3, frags.size)
    }
}
```

- [ ] **Step 3: Run to verify failure**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.parse.FribbJsonParserTest" 2>&1 | grep -E "FAILED|BUILD|error:"
```

Expected: `BUILD FAILED` — `FribbJsonParser` doesn't exist.

- [ ] **Step 4: Create the parser and IdentityFragment type**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/parse/FribbJsonParser.kt`:

```kotlin
package com.nexio.animemap.parse

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

data class IdentityFragment(
    val anidb: String,
    val kitsu: String? = null,
    val mal: String? = null,
    val anilist: String? = null,
    val tvdb: String? = null,
    val tmdb: String? = null,
    val imdb: String? = null,
    val sourceType: String? = null,
    val tvdbSeasonHint: String? = null,
    val tmdbSeasonHint: String? = null,
    val source: String = "fribb"
)

class FribbJsonParser {

    fun parse(json: String): List<IdentityFragment> {
        val moshi = Moshi.Builder().build()
        val listType = Types.newParameterizedType(List::class.java, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val raw = moshi.adapter<List<Map<String, Any?>>>(listType).fromJson(json) ?: return emptyList()
        return raw.mapNotNull { row -> toFragment(row) }
    }

    private fun toFragment(row: Map<String, Any?>): IdentityFragment? {
        val anidb = stringId(row["anidb_id"]) ?: return null
        val seasonMap = row["season"] as? Map<*, *>
        return IdentityFragment(
            anidb = anidb,
            kitsu = stringId(row["kitsu_id"]),
            mal = stringId(row["mal_id"]),
            anilist = stringId(row["anilist_id"]),
            tvdb = stringId(row["tvdb_id"]),
            tmdb = stringId(row["themoviedb_id"]),
            imdb = (row["imdb_id"] as? String)?.takeIf { it.isNotBlank() },
            sourceType = (row["type"] as? String)?.takeIf { it.isNotBlank() },
            tvdbSeasonHint = stringId(seasonMap?.get("tvdb")),
            tmdbSeasonHint = stringId(seasonMap?.get("tmdb"))
        )
    }

    private fun stringId(value: Any?): String? = when (value) {
        null -> null
        is String -> value.takeIf { it.isNotBlank() }
        is Number -> value.toLong().takeIf { it > 0 }?.toString()
        else -> null
    }
}
```

- [ ] **Step 5: Run tests, expect pass**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.parse.FribbJsonParserTest" 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/parse/FribbJsonParser.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/parse/FribbJsonParserTest.kt \
        tools/anime-mapping-generator/src/test/resources/fixtures/fribb-mha-and-one-piece.json
git commit -m "feat(anime-mapping-generator): add FribbJsonParser with MHA + One Piece fixtures"
```

---

## Task 6: ScudleeXmlParser (header attributes only)

**Files:**
- Create: `tools/anime-mapping-generator/src/test/resources/fixtures/mha-eight-seasons.xml`
- Create: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/parse/ScudleeXmlParserTest.kt`
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/parse/ScudleeXmlParser.kt`

- [ ] **Step 1: Create MHA fixture**

Create `tools/anime-mapping-generator/src/test/resources/fixtures/mha-eight-seasons.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<anime-list>
  <anime anidbid="11739" tvdbid="305074" defaulttvdbseason="1" tmdbtv="65930" tmdbseason="1">
    <name>Boku no Hero Academia</name>
  </anime>
  <anime anidbid="12233" tvdbid="305074" defaulttvdbseason="2" tmdbtv="65930" tmdbseason="2">
    <name>Boku no Hero Academia (2017)</name>
    <mapping-list>
      <mapping anidbseason="0" tvdbseason="0">;1-2;</mapping>
    </mapping-list>
  </anime>
  <anime anidbid="13485" tvdbid="305074" defaulttvdbseason="3" tmdbtv="65930" tmdbseason="3">
    <name>Boku no Hero Academia (2018)</name>
  </anime>
  <anime anidbid="11742" tvdbid="79654" defaulttvdbseason="0" episodeoffset="23" tmdbtv="30623" tmdbseason="0" tmdboffset="23" imdbid="tt5526456">
    <name>Eiga Crayon Shin-chan</name>
  </anime>
  <anime anidbid="16797" tvdbid="hentai">
    <name>Some Hentai Title</name>
  </anime>
  <anime anidbid="" tvdbid="">
    <name></name>
  </anime>
</anime-list>
```

- [ ] **Step 2: Write failing test**

Create `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/parse/ScudleeXmlParserTest.kt`:

```kotlin
package com.nexio.animemap.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScudleeXmlParserTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText()

    @Test fun `parses default tvdbseason as numeric string`() {
        val entries = ScudleeXmlParser().parse(fixture("mha-eight-seasons.xml"))
        val mhaS1 = entries.first { it.anidb == "11739" }
        assertEquals("305074", mhaS1.tvdb)
        assertEquals("1", mhaS1.tvdbSeason)
        assertEquals("65930", mhaS1.tmdbTv)
        assertEquals("1", mhaS1.tmdbSeason)
    }

    @Test fun `parses episode offset and tmdb offset`() {
        val entries = ScudleeXmlParser().parse(fixture("mha-eight-seasons.xml"))
        val crayon = entries.first { it.anidb == "11742" }
        assertEquals(23, crayon.tvdbEpisodeOffset)
        assertEquals(23, crayon.tmdbEpisodeOffset)
        assertEquals("tt5526456", crayon.imdb)
    }

    @Test fun `tvdbid hentai marker is captured but does not produce series id`() {
        val entries = ScudleeXmlParser().parse(fixture("mha-eight-seasons.xml"))
        val hentaiEntry = entries.first { it.anidb == "16797" }
        assertEquals("hentai", hentaiEntry.tvdbSeason)
        assertNull(hentaiEntry.tvdb)
    }

    @Test fun `empty anime entry is skipped`() {
        val entries = ScudleeXmlParser().parse(fixture("mha-eight-seasons.xml"))
        assertTrue(entries.none { it.anidb.isEmpty() })
    }

    @Test fun `parses MHA season 2 mapping-list raw xml when present`() {
        val entries = ScudleeXmlParser().parse(fixture("mha-eight-seasons.xml"))
        val mhaS2 = entries.first { it.anidb == "12233" }
        assertTrue(mhaS2.mappingListXml?.contains(";1-2;") == true)
    }

    @Test fun `entries without mapping-list have null mappingListXml`() {
        val entries = ScudleeXmlParser().parse(fixture("mha-eight-seasons.xml"))
        val mhaS1 = entries.first { it.anidb == "11739" }
        assertNull(mhaS1.mappingListXml)
    }
}
```

- [ ] **Step 3: Run, verify failure**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.parse.ScudleeXmlParserTest" 2>&1 | grep -E "FAILED|BUILD|error:"
```

Expected: `BUILD FAILED`.

- [ ] **Step 4: Create parser**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/parse/ScudleeXmlParser.kt`:

```kotlin
package com.nexio.animemap.parse

import com.nexio.animemap.model.SeasonMarkerWire
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.io.StringReader
import java.io.StringWriter
import org.xml.sax.InputSource

data class ScudleeAnimeEntry(
    val anidb: String,
    val tvdb: String? = null,
    val tmdbTv: String? = null,
    val tmdbMovie: String? = null,
    val imdb: String? = null,
    val tvdbSeason: String? = null,
    val tmdbSeason: String? = null,
    val tvdbEpisodeOffset: Int? = null,
    val tmdbEpisodeOffset: Int? = null,
    val name: String? = null,
    val mappingListXml: String? = null
)

class ScudleeXmlParser {

    fun parse(xml: String): List<ScudleeAnimeEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = doc.documentElement ?: return emptyList()
        val nodes = root.getElementsByTagName("anime")
        val out = mutableListOf<ScudleeAnimeEntry>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val entry = toEntry(el) ?: continue
            out += entry
        }
        return out
    }

    private fun toEntry(el: Element): ScudleeAnimeEntry? {
        val anidb = el.getAttribute("anidbid").trim()
        if (anidb.isEmpty()) return null

        val tvdbRaw = el.getAttribute("tvdbid").trim().takeIf { it.isNotEmpty() }
        val tvdbNumeric = tvdbRaw?.toIntOrNull()?.toString()
        val tvdbSeasonAttr = SeasonMarkerWire.normalize(el.getAttribute("defaulttvdbseason"))
            ?: when (tvdbRaw) {
                "hentai" -> "hentai"
                "unknown" -> "unknown"
                else -> null
            }

        return ScudleeAnimeEntry(
            anidb = anidb,
            tvdb = tvdbNumeric,
            tmdbTv = el.getAttribute("tmdbtv").trim().takeIf { it.isNotEmpty() },
            tmdbMovie = el.getAttribute("tmdbid").trim().takeIf { it.isNotEmpty() },
            imdb = el.getAttribute("imdbid").trim().takeIf { it.isNotEmpty() },
            tvdbSeason = tvdbSeasonAttr,
            tmdbSeason = SeasonMarkerWire.normalize(el.getAttribute("tmdbseason")),
            tvdbEpisodeOffset = el.getAttribute("episodeoffset").trim().toIntOrNull(),
            tmdbEpisodeOffset = el.getAttribute("tmdboffset").trim().toIntOrNull(),
            name = textOfChild(el, "name"),
            mappingListXml = childAsXmlString(el, "mapping-list")
        )
    }

    private fun textOfChild(parent: Element, tag: String): String? {
        val list = parent.getElementsByTagName(tag)
        if (list.length == 0) return null
        val text = list.item(0).textContent?.trim()
        return text?.takeIf { it.isNotEmpty() }
    }

    private fun childAsXmlString(parent: Element, tag: String): String? {
        val list = parent.getElementsByTagName(tag)
        if (list.length == 0) return null
        val node: Node = list.item(0)
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(node), StreamResult(writer))
        return writer.toString().takeIf { it.isNotBlank() }
    }
}
```

- [ ] **Step 5: Run tests, expect pass**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.parse.ScudleeXmlParserTest" 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 6: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/parse/ScudleeXmlParser.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/parse/ScudleeXmlParserTest.kt \
        tools/anime-mapping-generator/src/test/resources/fixtures/mha-eight-seasons.xml
git commit -m "feat(anime-mapping-generator): add ScudleeXmlParser for anime header attributes"
```

---

## Task 7: MappingListExpander

**Files:**
- Create: `tools/anime-mapping-generator/src/test/resources/fixtures/one-piece.xml`
- Create: `tools/anime-mapping-generator/src/test/resources/fixtures/chobits.xml`
- Create: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/parse/MappingListExpanderTest.kt`
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/parse/MappingListExpander.kt`

- [ ] **Step 1: Create One Piece fixture (truncated to representative ranges)**

Create `tools/anime-mapping-generator/src/test/resources/fixtures/one-piece.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<mapping-list>
  <mapping anidbseason="0" tvdbseason="0">;1-27;2-3;3-9;</mapping>
  <mapping anidbseason="1" tmdbseason="1" start="1" end="61" offset="0"/>
  <mapping anidbseason="1" tmdbseason="2" start="62" end="77" offset="-61"/>
  <mapping anidbseason="1" tvdbseason="21" start="892" end="1085" offset="-891"/>
  <mapping anidbseason="1" tvdbseason="22" start="1086" end="1155" offset="-1085"/>
  <mapping anidbseason="1" tvdbseason="23" start="1156" offset="-1155"/>
</mapping-list>
```

- [ ] **Step 2: Create Chobits fixture (inline mapping with zero target)**

Create `tools/anime-mapping-generator/src/test/resources/fixtures/chobits.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<mapping-list>
  <mapping anidbseason="0" tvdbseason="0">;1-0;2-0;</mapping>
  <mapping anidbseason="1" tvdbseason="1" start="1" end="26" offset="0"/>
</mapping-list>
```

- [ ] **Step 3: Write failing tests**

Create `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/parse/MappingListExpanderTest.kt`:

```kotlin
package com.nexio.animemap.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingListExpanderTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText()

    @Test fun `range rule for one piece tvdb season 21 starts at episode 892 with offset minus 891`() {
        val expanded = MappingListExpander().expand(fixture("one-piece.xml"))
        val r = expanded.ranges.first { it.targetProvider == "TVDB" && it.targetSeason == 21 }
        assertEquals(1, r.sourceSeason)
        assertEquals(892, r.startEpisode)
        assertEquals(1085, r.endEpisode)
        assertEquals(-891, r.offset)
    }

    @Test fun `open ended range has null endEpisode`() {
        val expanded = MappingListExpander().expand(fixture("one-piece.xml"))
        val r = expanded.ranges.first { it.targetProvider == "TVDB" && it.targetSeason == 23 }
        assertEquals(1156, r.startEpisode)
        assertEquals(null, r.endEpisode)
        assertEquals(-1155, r.offset)
    }

    @Test fun `tmdb ranges are emitted alongside tvdb ranges`() {
        val expanded = MappingListExpander().expand(fixture("one-piece.xml"))
        val tmdbCount = expanded.ranges.count { it.targetProvider == "TMDB" }
        val tvdbCount = expanded.ranges.count { it.targetProvider == "TVDB" }
        assertTrue("TMDB ranges expected", tmdbCount >= 2)
        assertTrue("TVDB ranges expected", tvdbCount >= 3)
    }

    @Test fun `inline explicit mappings produce explicit map entries`() {
        val expanded = MappingListExpander().expand(fixture("one-piece.xml"))
        val em = expanded.explicitMaps.first {
            it.sourceSeason == 0 && it.sourceEpisode == 1 && it.targetProvider == "TVDB"
        }
        assertEquals(0, em.targetSeason)
        assertEquals(27, em.targetEpisode)
    }

    @Test fun `inline explicit mapping target zero is preserved`() {
        val expanded = MappingListExpander().expand(fixture("chobits.xml"))
        val em = expanded.explicitMaps.first {
            it.sourceSeason == 0 && it.sourceEpisode == 1 && it.targetProvider == "TVDB"
        }
        assertEquals(0, em.targetEpisode)
    }
}
```

- [ ] **Step 4: Run, verify failure**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.parse.MappingListExpanderTest" 2>&1 | grep -E "FAILED|BUILD|error:"
```

Expected: `BUILD FAILED`.

- [ ] **Step 5: Create the expander**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/parse/MappingListExpander.kt`:

```kotlin
package com.nexio.animemap.parse

import com.nexio.animemap.model.ExplicitMap
import com.nexio.animemap.model.RangeRule
import org.w3c.dom.Element
import org.xml.sax.InputSource
import javax.xml.parsers.DocumentBuilderFactory
import java.io.StringReader

data class ExpandedMappingList(
    val ranges: List<RangeRule>,
    val explicitMaps: List<ExplicitMap>
)

class MappingListExpander {

    fun expand(mappingListXml: String): ExpandedMappingList {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(mappingListXml)))
        val root = doc.documentElement ?: return ExpandedMappingList(emptyList(), emptyList())

        val ranges = mutableListOf<RangeRule>()
        val explicit = mutableListOf<ExplicitMap>()

        val children = root.getElementsByTagName("mapping")
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            val anidbSeason = el.getAttribute("anidbseason").trim().toIntOrNull() ?: continue
            val targetProvider = when {
                el.hasAttribute("tvdbseason") -> "TVDB"
                el.hasAttribute("tmdbseason") -> "TMDB"
                else -> continue
            }
            val targetSeasonAttr = if (targetProvider == "TVDB") "tvdbseason" else "tmdbseason"
            val targetSeason = el.getAttribute(targetSeasonAttr).trim().toIntOrNull() ?: continue

            val start = el.getAttribute("start").trim().toIntOrNull()
            val end = el.getAttribute("end").trim().toIntOrNull()
            val offsetText = el.getAttribute("offset").trim()
            val offset = offsetText.toIntOrNull()

            if (start != null && offset != null) {
                ranges += RangeRule(
                    sourceSeason = anidbSeason,
                    startEpisode = start,
                    endEpisode = end,
                    targetProvider = targetProvider,
                    targetSeason = targetSeason,
                    offset = offset
                )
            }

            val inline = el.textContent?.trim().orEmpty()
            if (inline.isNotEmpty()) {
                explicit += parseInline(inline, anidbSeason, targetProvider, targetSeason)
            }
        }

        return ExpandedMappingList(ranges, explicit)
    }

    private fun parseInline(
        inline: String,
        sourceSeason: Int,
        targetProvider: String,
        targetSeason: Int
    ): List<ExplicitMap> {
        return inline.split(';')
            .mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val parts = trimmed.split('-')
                if (parts.size != 2) return@mapNotNull null
                val src = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val tgt = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                ExplicitMap(
                    sourceSeason = sourceSeason,
                    sourceEpisode = src,
                    targetProvider = targetProvider,
                    targetSeason = targetSeason,
                    targetEpisode = tgt
                )
            }
    }
}
```

- [ ] **Step 6: Run tests, expect pass**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.parse.MappingListExpanderTest" 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 7: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/parse/MappingListExpander.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/parse/MappingListExpanderTest.kt \
        tools/anime-mapping-generator/src/test/resources/fixtures/one-piece.xml \
        tools/anime-mapping-generator/src/test/resources/fixtures/chobits.xml
git commit -m "feat(anime-mapping-generator): expand <mapping-list> into RangeRule and ExplicitMap"
```

---

## Task 8: IdentityMerger

**Files:**
- Create: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/merge/IdentityMergerTest.kt`
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/merge/IdentityMerger.kt`

- [ ] **Step 1: Write failing test**

Create `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/merge/IdentityMergerTest.kt`:

```kotlin
package com.nexio.animemap.merge

import com.nexio.animemap.model.RangeRule
import com.nexio.animemap.parse.ExpandedMappingList
import com.nexio.animemap.parse.IdentityFragment
import com.nexio.animemap.parse.ScudleeAnimeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityMergerTest {

    @Test fun `identity record is keyed by Kitsu and carries Fribb identifiers`() {
        val merger = IdentityMerger()
        val out = merger.merge(
            fribb = listOf(
                IdentityFragment(anidb = "11739", kitsu = "11469", tvdb = "305074", tmdb = "65930",
                    imdb = "tt5626028", mal = "31964", anilist = "21459", sourceType = "TV")
            ),
            scudlee = emptyMap()
        )
        val rec = out.identity.getValue("11469")
        assertEquals("11469", rec.kitsu)
        assertEquals("305074", rec.tvdb)
        assertEquals("11739", rec.anidb)
    }

    @Test fun `scudlee tvdbSeason wins when fribb lacks season`() {
        val merger = IdentityMerger()
        val out = merger.merge(
            fribb = listOf(
                IdentityFragment(anidb = "11739", kitsu = "11469", tvdb = "305074", sourceType = "TV")
            ),
            scudlee = mapOf("11739" to MergedScudlee(
                entry = ScudleeAnimeEntry(anidb = "11739", tvdb = "305074", tvdbSeason = "1"),
                expanded = null
            ))
        )
        assertEquals("1", out.identity.getValue("11469").tvdbSeason)
    }

    @Test fun `fribb tvdbSeasonHint is used when scudlee absent`() {
        val merger = IdentityMerger()
        val out = merger.merge(
            fribb = listOf(
                IdentityFragment(anidb = "13485", kitsu = "13881", tvdb = "305074",
                    sourceType = "TV", tvdbSeasonHint = "3", tmdbSeasonHint = "3")
            ),
            scudlee = emptyMap()
        )
        val rec = out.identity.getValue("13881")
        assertEquals("3", rec.tvdbSeason)
        assertEquals("3", rec.tmdbSeason)
    }

    @Test fun `episode mapping record is created when expanded mapping list present`() {
        val merger = IdentityMerger()
        val out = merger.merge(
            fribb = listOf(IdentityFragment(anidb = "69", kitsu = "12", tvdb = "81797", sourceType = "TV")),
            scudlee = mapOf("69" to MergedScudlee(
                entry = ScudleeAnimeEntry(anidb = "69", tvdb = "81797", tvdbSeason = "a"),
                expanded = ExpandedMappingList(
                    ranges = listOf(RangeRule(1, 892, 1085, "TVDB", 21, -891)),
                    explicitMaps = emptyList()
                )
            ))
        )
        val mapping = out.episodeMapping.getValue("69")
        assertEquals("81797", mapping.tvdbSeriesId)
        assertEquals(1, mapping.ranges.size)
        assertEquals(true, out.identity.getValue("12").hasMappingRules)
    }

    @Test fun `kitsu only fragment with no scudlee match still produces identity record`() {
        val merger = IdentityMerger()
        val out = merger.merge(
            fribb = listOf(IdentityFragment(anidb = "99999", kitsu = "55555", sourceType = "TV")),
            scudlee = emptyMap()
        )
        val rec = out.identity["55555"]
        assertNotNull(rec)
        assertNull(rec!!.tvdbSeason)
        assertEquals(false, rec.hasMappingRules)
    }

    @Test fun `evidence trail names both sources when both contributed`() {
        val merger = IdentityMerger()
        val out = merger.merge(
            fribb = listOf(IdentityFragment(anidb = "11739", kitsu = "11469", tvdb = "305074", sourceType = "TV")),
            scudlee = mapOf("11739" to MergedScudlee(
                entry = ScudleeAnimeEntry(anidb = "11739", tvdb = "305074", tvdbSeason = "1"),
                expanded = null
            ))
        )
        val ev = out.identity.getValue("11469").evidence
        assertTrue("evidence should mention fribb", ev.any { it.startsWith("fribb.") })
        assertTrue("evidence should mention scudlee", ev.any { it.startsWith("scudlee.") })
    }
}
```

- [ ] **Step 2: Run, verify failure**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.merge.IdentityMergerTest" 2>&1 | grep -E "FAILED|BUILD|error:"
```

Expected: `BUILD FAILED`.

- [ ] **Step 3: Create the merger**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/merge/IdentityMerger.kt`:

```kotlin
package com.nexio.animemap.merge

import com.nexio.animemap.model.EpisodeMappingRecord
import com.nexio.animemap.model.IdentityRecord
import com.nexio.animemap.parse.ExpandedMappingList
import com.nexio.animemap.parse.IdentityFragment
import com.nexio.animemap.parse.ScudleeAnimeEntry

data class MergedScudlee(
    val entry: ScudleeAnimeEntry,
    val expanded: ExpandedMappingList?
)

data class MergedAssetData(
    val identity: Map<String, IdentityRecord>,
    val episodeMapping: Map<String, EpisodeMappingRecord>
)

class IdentityMerger {

    fun merge(
        fribb: List<IdentityFragment>,
        scudlee: Map<String, MergedScudlee>
    ): MergedAssetData {
        val identity = mutableMapOf<String, IdentityRecord>()
        val episodeMapping = mutableMapOf<String, EpisodeMappingRecord>()

        for (frag in fribb) {
            val kitsu = frag.kitsu ?: continue
            val s = scudlee[frag.anidb]
            val mappingPresent = s?.expanded?.let { it.ranges.isNotEmpty() || it.explicitMaps.isNotEmpty() } == true

            val tvdbSeason = s?.entry?.tvdbSeason ?: frag.tvdbSeasonHint
            val tmdbSeason = s?.entry?.tmdbSeason ?: frag.tmdbSeasonHint

            val evidence = buildList {
                add("fribb.kitsu=$kitsu")
                if (frag.tvdb != null) add("fribb.tvdb=${frag.tvdb}")
                if (s != null) {
                    if (s.entry.tvdb != null) add("scudlee.tvdb=${s.entry.tvdb}")
                    if (s.entry.tvdbSeason != null) add("scudlee.defaulttvdbseason=${s.entry.tvdbSeason}")
                    if (s.entry.tmdbSeason != null) add("scudlee.tmdbseason=${s.entry.tmdbSeason}")
                    if (mappingPresent) add("scudlee.mapping-list")
                }
            }

            identity[kitsu] = IdentityRecord(
                kitsu = kitsu,
                mal = frag.mal,
                anilist = frag.anilist,
                anidb = frag.anidb,
                tmdb = frag.tmdb ?: s?.entry?.tmdbTv,
                tvdb = frag.tvdb ?: s?.entry?.tvdb,
                imdb = frag.imdb ?: s?.entry?.imdb,
                mediaType = mediaTypeOf(frag.sourceType),
                sourceType = frag.sourceType,
                tvdbSeason = tvdbSeason,
                tmdbSeason = tmdbSeason,
                tvdbEpisodeOffset = s?.entry?.tvdbEpisodeOffset,
                tmdbEpisodeOffset = s?.entry?.tmdbEpisodeOffset,
                hasMappingRules = mappingPresent,
                evidence = evidence
            )
        }

        for ((anidb, s) in scudlee) {
            val expanded = s.expanded ?: continue
            if (expanded.ranges.isEmpty() && expanded.explicitMaps.isEmpty()) continue
            episodeMapping[anidb] = EpisodeMappingRecord(
                anidb = anidb,
                name = s.entry.name,
                tvdbSeriesId = s.entry.tvdb,
                tmdbTvId = s.entry.tmdbTv,
                ranges = expanded.ranges,
                explicitMaps = expanded.explicitMaps,
                evidence = listOf("scudlee.mapping-list")
            )
        }

        return MergedAssetData(identity, episodeMapping)
    }

    private fun mediaTypeOf(sourceType: String?): String? = when (sourceType?.uppercase()) {
        null -> null
        "MOVIE" -> "movie"
        else -> "series"
    }
}
```

- [ ] **Step 4: Run tests, expect pass**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.merge.IdentityMergerTest" 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/merge/IdentityMerger.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/merge/IdentityMergerTest.kt
git commit -m "feat(anime-mapping-generator): merge Fribb and ScudLee fragments into IdentityRecord"
```

---

## Task 9: OverlayApplier

**Files:**
- Create: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/merge/OverlayApplierTest.kt`
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/merge/OverlayApplier.kt`

- [ ] **Step 1: Write failing test**

Create `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/merge/OverlayApplierTest.kt`:

```kotlin
package com.nexio.animemap.merge

import com.nexio.animemap.model.EpisodeMappingRecord
import com.nexio.animemap.model.ExplicitMap
import com.nexio.animemap.model.IdentityRecord
import com.nexio.animemap.model.OverlayEntry
import com.nexio.animemap.model.OverlayPatch
import com.nexio.animemap.model.RangeRule
import com.nexio.animemap.model.RangeRuleKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayApplierTest {

    private val baseIdentity = mapOf(
        "11469" to IdentityRecord(kitsu = "11469", anidb = "11739", tvdb = "305074", tvdbSeason = "1"),
        "12" to IdentityRecord(kitsu = "12", anidb = "69", tvdb = "81797", tvdbSeason = "a", hasMappingRules = true)
    )
    private val baseMapping = mapOf(
        "69" to EpisodeMappingRecord(
            anidb = "69", tvdbSeriesId = "81797",
            ranges = listOf(RangeRule(1, 1086, 1155, "TVDB", 22, -1085))
        )
    )

    @Test fun `patch identity updates named field only`() {
        val out = OverlayApplier().apply(
            identity = baseIdentity, episodeMapping = baseMapping,
            entries = listOf(OverlayEntry(
                anidb = "11739", mode = "patch-identity", reason = "test",
                patch = OverlayPatch(tvdbSeason = "9")
            ))
        )
        assertEquals("9", out.identity.getValue("11469").tvdbSeason)
        assertEquals("305074", out.identity.getValue("11469").tvdb)
    }

    @Test fun `patch identity fails when target record missing`() {
        try {
            OverlayApplier().apply(
                identity = baseIdentity, episodeMapping = baseMapping,
                entries = listOf(OverlayEntry(
                    anidb = "99999", mode = "patch-identity", reason = "missing",
                    patch = OverlayPatch(tvdbSeason = "1")
                ))
            )
            error("expected exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("99999"))
        }
    }

    @Test fun `patch mapping remove then add replaces a rule`() {
        val out = OverlayApplier().apply(
            identity = baseIdentity, episodeMapping = baseMapping,
            entries = listOf(OverlayEntry(
                anidb = "69", mode = "patch-mapping", reason = "test",
                patch = OverlayPatch(
                    removeRanges = listOf(RangeRuleKey(1, 1086, "TVDB")),
                    addRanges = listOf(RangeRule(1, 1086, 1200, "TVDB", 22, -1085))
                )
            ))
        )
        val ranges = out.episodeMapping.getValue("69").ranges
        assertEquals(1, ranges.size)
        assertEquals(1200, ranges.single().endEpisode)
    }

    @Test fun `replace target identity creates new record`() {
        val out = OverlayApplier().apply(
            identity = baseIdentity, episodeMapping = baseMapping,
            entries = listOf(OverlayEntry(
                anidb = "33333", mode = "replace", target = "identity", reason = "new entry",
                record = mapOf(
                    "kitsu" to "77777",
                    "anidb" to "33333",
                    "tvdb" to "999999",
                    "tvdbSeason" to "1"
                )
            ))
        )
        val rec = out.identity.getValue("77777")
        assertEquals("33333", rec.anidb)
        assertEquals("1", rec.tvdbSeason)
    }

    @Test fun `drop removes from both maps`() {
        val out = OverlayApplier().apply(
            identity = baseIdentity, episodeMapping = baseMapping,
            entries = listOf(OverlayEntry(anidb = "69", mode = "drop", reason = "test"))
        )
        assertNull(out.identity["12"])
        assertNull(out.episodeMapping["69"])
    }

    @Test fun `duplicate anidb mode pair fails`() {
        try {
            OverlayApplier().apply(
                identity = baseIdentity, episodeMapping = baseMapping,
                entries = listOf(
                    OverlayEntry(anidb = "11739", mode = "patch-identity", reason = "first",
                        patch = OverlayPatch(tvdbSeason = "1")),
                    OverlayEntry(anidb = "11739", mode = "patch-identity", reason = "duplicate",
                        patch = OverlayPatch(tvdbSeason = "2"))
                )
            )
            error("expected exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }

    @Test fun `empty reason fails`() {
        try {
            OverlayApplier().apply(
                identity = baseIdentity, episodeMapping = baseMapping,
                entries = listOf(OverlayEntry(anidb = "11739", mode = "drop", reason = ""))
            )
            error("expected exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    @Test fun `unknown mode fails`() {
        try {
            OverlayApplier().apply(
                identity = baseIdentity, episodeMapping = baseMapping,
                entries = listOf(OverlayEntry(anidb = "11739", mode = "rewrite", reason = "x"))
            )
            error("expected exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("mode"))
        }
    }
}
```

- [ ] **Step 2: Run, verify failure**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.merge.OverlayApplierTest" 2>&1 | grep -E "FAILED|BUILD|error:"
```

Expected: `BUILD FAILED`.

- [ ] **Step 3: Create the applier**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/merge/OverlayApplier.kt`:

```kotlin
package com.nexio.animemap.merge

import com.nexio.animemap.model.EpisodeMappingRecord
import com.nexio.animemap.model.ExplicitMap
import com.nexio.animemap.model.ExplicitMapKey
import com.nexio.animemap.model.IdentityRecord
import com.nexio.animemap.model.OverlayEntry
import com.nexio.animemap.model.OverlayMode
import com.nexio.animemap.model.OverlayTarget
import com.nexio.animemap.model.RangeRule
import com.nexio.animemap.model.RangeRuleKey

class OverlayApplier {

    fun apply(
        identity: Map<String, IdentityRecord>,
        episodeMapping: Map<String, EpisodeMappingRecord>,
        entries: List<OverlayEntry>
    ): MergedAssetData {
        validate(entries)

        val identityById = identity.toMutableMap()
        val mappingByAnidb = episodeMapping.toMutableMap()

        for (entry in entries) {
            when (entry.mode) {
                OverlayMode.DROP -> applyDrop(entry, identityById, mappingByAnidb)
                OverlayMode.REPLACE -> applyReplace(entry, identityById, mappingByAnidb)
                OverlayMode.PATCH_IDENTITY -> applyPatchIdentity(entry, identityById)
                OverlayMode.PATCH_MAPPING -> applyPatchMapping(entry, mappingByAnidb)
                else -> error("unknown overlay mode: ${entry.mode}")
            }
        }

        return MergedAssetData(identityById, mappingByAnidb)
    }

    private fun validate(entries: List<OverlayEntry>) {
        val seen = mutableSetOf<Pair<String, String>>()
        for (e in entries) {
            require(e.anidb.isNotBlank()) { "overlay entry missing anidb" }
            require(e.reason.isNotBlank()) { "overlay entry for anidb=${e.anidb} missing reason" }
            check(e.mode in OverlayMode.ALL) { "unknown overlay mode '${e.mode}' for anidb=${e.anidb}" }
            val key = e.anidb to e.mode
            check(seen.add(key)) { "duplicate overlay entry for (anidb=${e.anidb}, mode=${e.mode})" }
        }
    }

    private fun applyDrop(
        entry: OverlayEntry,
        identity: MutableMap<String, IdentityRecord>,
        mapping: MutableMap<String, EpisodeMappingRecord>
    ) {
        val toRemoveKitsuIds = identity.values.filter { it.anidb == entry.anidb }.map { it.kitsu }
        toRemoveKitsuIds.forEach { identity.remove(it) }
        mapping.remove(entry.anidb)
    }

    private fun applyReplace(
        entry: OverlayEntry,
        identity: MutableMap<String, IdentityRecord>,
        mapping: MutableMap<String, EpisodeMappingRecord>
    ) {
        val record = entry.record ?: error("replace entry for anidb=${entry.anidb} missing record")
        when (entry.target) {
            OverlayTarget.IDENTITY -> {
                val rec = mapToIdentityRecord(record)
                identity[rec.kitsu] = rec
            }
            OverlayTarget.MAPPING -> {
                val rec = mapToMappingRecord(entry.anidb, record)
                mapping[entry.anidb] = rec
            }
            else -> error("replace entry for anidb=${entry.anidb} missing valid target")
        }
    }

    private fun applyPatchIdentity(entry: OverlayEntry, identity: MutableMap<String, IdentityRecord>) {
        val patch = entry.patch ?: error("patch-identity for anidb=${entry.anidb} missing patch")
        val key = identity.entries.firstOrNull { it.value.anidb == entry.anidb }?.key
            ?: error("patch-identity target identity record missing for anidb=${entry.anidb}")
        val current = identity.getValue(key)
        identity[key] = current.copy(
            tvdbSeason = patch.tvdbSeason ?: current.tvdbSeason,
            tmdbSeason = patch.tmdbSeason ?: current.tmdbSeason,
            tvdbEpisodeOffset = patch.tvdbEpisodeOffset ?: current.tvdbEpisodeOffset,
            tmdbEpisodeOffset = patch.tmdbEpisodeOffset ?: current.tmdbEpisodeOffset,
            tvdb = patch.tvdb ?: current.tvdb,
            tmdb = patch.tmdb ?: current.tmdb,
            imdb = patch.imdb ?: current.imdb,
            evidence = current.evidence + "overlay.patch-identity"
        )
    }

    private fun applyPatchMapping(entry: OverlayEntry, mapping: MutableMap<String, EpisodeMappingRecord>) {
        val patch = entry.patch ?: error("patch-mapping for anidb=${entry.anidb} missing patch")
        val current = mapping[entry.anidb]
            ?: error("patch-mapping target mapping record missing for anidb=${entry.anidb}")
        val newRanges = current.ranges
            .filterNot { r ->
                patch.removeRanges.any { k ->
                    k.sourceSeason == r.sourceSeason && k.startEpisode == r.startEpisode &&
                        k.targetProvider == r.targetProvider
                }
            }
            .plus(patch.addRanges)
        val newExplicit = current.explicitMaps
            .filterNot { e ->
                patch.removeExplicitMaps.any { k ->
                    k.sourceSeason == e.sourceSeason && k.sourceEpisode == e.sourceEpisode &&
                        k.targetProvider == e.targetProvider
                }
            }
            .plus(patch.addExplicitMaps)
        mapping[entry.anidb] = current.copy(
            ranges = newRanges,
            explicitMaps = newExplicit,
            evidence = current.evidence + "overlay.patch-mapping"
        )
    }

    private fun mapToIdentityRecord(map: Map<String, Any?>): IdentityRecord {
        val kitsu = (map["kitsu"] as? String) ?: error("replace identity record missing kitsu")
        return IdentityRecord(
            kitsu = kitsu,
            mal = map["mal"] as? String,
            anilist = map["anilist"] as? String,
            anidb = map["anidb"] as? String,
            tmdb = map["tmdb"] as? String,
            tvdb = map["tvdb"] as? String,
            imdb = map["imdb"] as? String,
            mediaType = map["mediaType"] as? String,
            sourceType = map["sourceType"] as? String,
            tvdbSeason = map["tvdbSeason"] as? String,
            tmdbSeason = map["tmdbSeason"] as? String,
            tvdbEpisodeOffset = (map["tvdbEpisodeOffset"] as? Number)?.toInt(),
            tmdbEpisodeOffset = (map["tmdbEpisodeOffset"] as? Number)?.toInt(),
            hasMappingRules = (map["hasMappingRules"] as? Boolean) ?: false,
            evidence = listOf("overlay.replace")
        )
    }

    private fun mapToMappingRecord(anidb: String, map: Map<String, Any?>): EpisodeMappingRecord {
        @Suppress("UNCHECKED_CAST")
        val rangesRaw = (map["ranges"] as? List<Map<String, Any?>>) ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val explicitRaw = (map["explicitMaps"] as? List<Map<String, Any?>>) ?: emptyList()
        return EpisodeMappingRecord(
            anidb = anidb,
            name = map["name"] as? String,
            tvdbSeriesId = map["tvdbSeriesId"] as? String,
            tmdbTvId = map["tmdbTvId"] as? String,
            ranges = rangesRaw.map { r ->
                RangeRule(
                    sourceSeason = (r["sourceSeason"] as Number).toInt(),
                    startEpisode = (r["startEpisode"] as Number).toInt(),
                    endEpisode = (r["endEpisode"] as? Number)?.toInt(),
                    targetProvider = r["targetProvider"] as String,
                    targetSeason = (r["targetSeason"] as Number).toInt(),
                    offset = (r["offset"] as Number).toInt()
                )
            },
            explicitMaps = explicitRaw.map { e ->
                ExplicitMap(
                    sourceSeason = (e["sourceSeason"] as Number).toInt(),
                    sourceEpisode = (e["sourceEpisode"] as Number).toInt(),
                    targetProvider = e["targetProvider"] as String,
                    targetSeason = (e["targetSeason"] as Number).toInt(),
                    targetEpisode = (e["targetEpisode"] as Number).toInt()
                )
            },
            evidence = listOf("overlay.replace")
        )
    }
}
```

- [ ] **Step 4: Run tests, expect pass**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.merge.OverlayApplierTest" 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/merge/OverlayApplier.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/merge/OverlayApplierTest.kt
git commit -m "feat(anime-mapping-generator): apply 4-mode overlay with validation"
```

---

## Task 10: IndexBuilder + AssetWriter

**Files:**
- Create: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/emit/IndexBuilderTest.kt`
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/emit/IndexBuilder.kt`
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/emit/AssetWriter.kt`

- [ ] **Step 1: Write failing IndexBuilder test**

Create `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/emit/IndexBuilderTest.kt`:

```kotlin
package com.nexio.animemap.emit

import com.nexio.animemap.model.IdentityRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexBuilderTest {

    @Test fun `byTvdb is List of Kitsu IDs sharing the same tvdb`() {
        val identity = mapOf(
            "11469" to IdentityRecord(kitsu = "11469", anidb = "11739", tvdb = "305074"),
            "13881" to IdentityRecord(kitsu = "13881", anidb = "13485", tvdb = "305074"),
            "12" to IdentityRecord(kitsu = "12", anidb = "69", tvdb = "81797")
        )
        val ix = IndexBuilder().build(identity)
        val mha = ix.byTvdb["305074"]!!
        assertEquals(setOf("11469", "13881"), mha.toSet())
        assertEquals(listOf("12"), ix.byTvdb["81797"])
    }

    @Test fun `byMal byAnilist byAnidb byImdb are single value`() {
        val identity = mapOf(
            "11469" to IdentityRecord(
                kitsu = "11469", anidb = "11739", tvdb = "305074",
                mal = "31964", anilist = "21459", imdb = "tt5626028"
            )
        )
        val ix = IndexBuilder().build(identity)
        assertEquals("11469", ix.byMal["31964"])
        assertEquals("11469", ix.byAnilist["21459"])
        assertEquals("11469", ix.byAnidb["11739"])
        assertEquals("11469", ix.byImdb["tt5626028"])
    }

    @Test fun `tmdb routing splits by mediaType`() {
        val identity = mapOf(
            "100" to IdentityRecord(kitsu = "100", anidb = "1", tmdb = "111", mediaType = "series"),
            "200" to IdentityRecord(kitsu = "200", anidb = "2", tmdb = "222", mediaType = "movie")
        )
        val ix = IndexBuilder().build(identity)
        assertTrue("series tmdb in byTmdbTv", ix.byTmdbTv["111"]?.contains("100") == true)
        assertEquals("200", ix.byTmdbMovie["222"])
    }
}
```

- [ ] **Step 2: Run, verify failure**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.emit.IndexBuilderTest" 2>&1 | grep -E "FAILED|BUILD|error:"
```

Expected: `BUILD FAILED`.

- [ ] **Step 3: Create IndexBuilder**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/emit/IndexBuilder.kt`:

```kotlin
package com.nexio.animemap.emit

import com.nexio.animemap.model.IdentityRecord
import com.nexio.animemap.model.MapIndexes

class IndexBuilder {

    fun build(identity: Map<String, IdentityRecord>): MapIndexes {
        val byKitsu = identity.keys.associateWith { it }
        val byMal = mutableMapOf<String, String>()
        val byAnilist = mutableMapOf<String, String>()
        val byAnidb = mutableMapOf<String, String>()
        val byTvdb = mutableMapOf<String, MutableList<String>>()
        val byTmdbTv = mutableMapOf<String, MutableList<String>>()
        val byTmdbMovie = mutableMapOf<String, String>()
        val byImdb = mutableMapOf<String, String>()

        for ((kitsu, rec) in identity) {
            rec.mal?.let { byMal.putIfAbsent(it, kitsu) }
            rec.anilist?.let { byAnilist.putIfAbsent(it, kitsu) }
            rec.anidb?.let { byAnidb.putIfAbsent(it, kitsu) }
            rec.tvdb?.let { byTvdb.getOrPut(it) { mutableListOf() }.add(kitsu) }
            rec.imdb?.let { byImdb.putIfAbsent(it, kitsu) }
            rec.tmdb?.let { tmdb ->
                if (rec.mediaType == "movie") byTmdbMovie.putIfAbsent(tmdb, kitsu)
                else byTmdbTv.getOrPut(tmdb) { mutableListOf() }.add(kitsu)
            }
        }

        return MapIndexes(
            byKitsu = byKitsu,
            byMal = byMal.toMap(),
            byAnilist = byAnilist.toMap(),
            byAnidb = byAnidb.toMap(),
            byTvdb = byTvdb.mapValues { it.value.toList() },
            byTmdbTv = byTmdbTv.mapValues { it.value.toList() },
            byTmdbMovie = byTmdbMovie.toMap(),
            byImdb = byImdb.toMap()
        )
    }
}
```

- [ ] **Step 4: Create AssetWriter**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/emit/AssetWriter.kt`:

```kotlin
package com.nexio.animemap.emit

import com.nexio.animemap.model.AssetCounts
import com.nexio.animemap.model.EpisodeMappingRecord
import com.nexio.animemap.model.IdentityRecord
import com.nexio.animemap.model.NexioAnimeMap
import com.nexio.animemap.model.NexioAnimeMapJsonAdapter
import com.nexio.animemap.model.ProvenanceFile
import com.nexio.animemap.model.ProvenanceFileJsonAdapter
import com.squareup.moshi.Moshi
import java.io.File

class AssetWriter {

    fun writeAsset(
        out: File,
        identity: Map<String, IdentityRecord>,
        episodeMapping: Map<String, EpisodeMappingRecord>,
        generatedAt: String,
        skippedCount: Int = 0
    ) {
        val indexes = IndexBuilder().build(identity)
        val asset = NexioAnimeMap(
            schemaVersion = 2,
            mappingPolicyVersion = 1,
            generatedAt = generatedAt,
            counts = AssetCounts(
                identityRecords = identity.size,
                episodeMappingRecords = episodeMapping.size,
                skippedCount = skippedCount
            ),
            identityRecordsByKitsu = identity,
            episodeMappingsByAnidb = episodeMapping,
            indexes = indexes
        )
        val moshi = Moshi.Builder().build()
        val adapter = NexioAnimeMapJsonAdapter(moshi).indent("  ")
        out.parentFile.mkdirs()
        out.writeText(adapter.toJson(asset))
    }

    fun writeProvenance(out: File, provenance: ProvenanceFile) {
        val moshi = Moshi.Builder().build()
        val adapter = ProvenanceFileJsonAdapter(moshi).indent("  ")
        out.parentFile.mkdirs()
        out.writeText(adapter.toJson(provenance))
    }
}
```

- [ ] **Step 5: Run tests, expect pass**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.emit.IndexBuilderTest" 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/emit/ \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/emit/IndexBuilderTest.kt
git commit -m "feat(anime-mapping-generator): build indexes and write asset + provenance"
```

---

## Task 11: UpstreamFetcher

**Files:**
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/fetch/UpstreamFetcher.kt`

- [ ] **Step 1: Create the fetcher (no test — exercised by integration test in Task 12)**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/fetch/UpstreamFetcher.kt`:

```kotlin
package com.nexio.animemap.fetch

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeFormatter

data class FetchResult(
    val url: String,
    val commit: String?,
    val fetchedAt: String,
    val payload: ByteArray
)

class UpstreamFetcher(private val userAgent: String = "Nexio anime-mapping-generator") {

    fun fetchSource(
        rawUrl: String,
        commitsApiUrl: String,
        cacheFile: File
    ): FetchResult {
        val payload = fetchBytes(rawUrl)
        cacheFile.parentFile.mkdirs()
        cacheFile.writeBytes(payload)
        val commit = fetchCommitSha(commitsApiUrl)
        return FetchResult(
            url = rawUrl,
            commit = commit,
            fetchedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            payload = payload
        )
    }

    fun useCache(rawUrl: String, cacheFile: File): FetchResult? {
        if (!cacheFile.exists()) return null
        return FetchResult(
            url = rawUrl,
            commit = null,
            fetchedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(cacheFile.lastModified())),
            payload = cacheFile.readBytes()
        )
    }

    private fun fetchBytes(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", userAgent)
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            check(connection.responseCode in 200..299) {
                "fetch failed for $url with HTTP ${connection.responseCode}"
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchCommitSha(commitsApiUrl: String): String? {
        return try {
            val bytes = fetchBytes(commitsApiUrl)
            val moshi = Moshi.Builder().build()
            val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            @Suppress("UNCHECKED_CAST")
            val parsed = moshi.adapter<Map<String, Any?>>(type).fromJson(String(bytes)) ?: return null
            parsed["sha"] as? String
        } catch (e: Exception) {
            null
        }
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :tools:anime-mapping-generator:compileKotlin 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/fetch/UpstreamFetcher.kt
git commit -m "feat(anime-mapping-generator): add UpstreamFetcher for HTTP + commit SHA capture"
```

---

## Task 12: Main.kt CLI + end-to-end fixture test

**Files:**
- Create: `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/Main.kt`
- Create: `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/AssetFixtureEndToEndTest.kt`
- Create: `tools/anime-mapping-generator/src/test/resources/fixtures/overlay-examples.json`

- [ ] **Step 1: Create overlay examples fixture**

Create `tools/anime-mapping-generator/src/test/resources/fixtures/overlay-examples.json`:

```json
{
  "schemaVersion": 1,
  "entries": []
}
```

- [ ] **Step 2: Create the CLI Main**

Create `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/Main.kt`:

```kotlin
package com.nexio.animemap

import com.nexio.animemap.emit.AssetWriter
import com.nexio.animemap.merge.IdentityMerger
import com.nexio.animemap.merge.MergedScudlee
import com.nexio.animemap.merge.OverlayApplier
import com.nexio.animemap.model.OverlayFile
import com.nexio.animemap.model.OverlayFileJsonAdapter
import com.nexio.animemap.model.ProvenanceFile
import com.nexio.animemap.model.ProvenanceOverlay
import com.nexio.animemap.model.ProvenanceSource
import com.nexio.animemap.parse.FribbJsonParser
import com.nexio.animemap.parse.MappingListExpander
import com.nexio.animemap.parse.ScudleeXmlParser
import com.squareup.moshi.Moshi
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

object Generator {

    data class Args(
        val fribbInput: File,
        val scudleeInput: File,
        val overlayInput: File,
        val assetOutput: File,
        val provenanceOutput: File,
        val fribbUrl: String,
        val fribbCommit: String?,
        val scudleeUrl: String,
        val scudleeCommit: String?
    )

    fun run(args: Args) {
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val fribbFragments = FribbJsonParser().parse(args.fribbInput.readText())
        val scudleeEntries = ScudleeXmlParser().parse(args.scudleeInput.readText())
        val expandedByAnidb = scudleeEntries.associate { entry ->
            entry.anidb to MergedScudlee(
                entry = entry,
                expanded = entry.mappingListXml?.let { MappingListExpander().expand(it) }
            )
        }

        val merged = IdentityMerger().merge(fribbFragments, expandedByAnidb)

        val overlay = OverlayFileJsonAdapter(Moshi.Builder().build())
            .fromJson(args.overlayInput.readText())
            ?: OverlayFile(schemaVersion = 1, entries = emptyList())

        val finalData = OverlayApplier().apply(merged.identity, merged.episodeMapping, overlay.entries)

        val writer = AssetWriter()
        writer.writeAsset(
            out = args.assetOutput,
            identity = finalData.identity,
            episodeMapping = finalData.episodeMapping,
            generatedAt = now
        )
        writer.writeProvenance(args.provenanceOutput, ProvenanceFile(
            generatedAt = now,
            sources = mapOf(
                "fribb" to ProvenanceSource(args.fribbUrl, args.fribbCommit, now),
                "scudlee" to ProvenanceSource(args.scudleeUrl, args.scudleeCommit, now)
            ),
            overlay = ProvenanceOverlay(version = overlay.schemaVersion, entryCount = overlay.entries.size),
            counts = com.nexio.animemap.model.AssetCounts(
                identityRecords = finalData.identity.size,
                episodeMappingRecords = finalData.episodeMapping.size
            )
        ))
    }
}

fun main(args: Array<String>) {
    require(args.size == 8) { "expected 8 args: fribbIn scudleeIn overlayIn assetOut provenanceOut fribbUrl fribbCommit scudleeUrl scudleeCommit" }
    Generator.run(Generator.Args(
        fribbInput = File(args[0]),
        scudleeInput = File(args[1]),
        overlayInput = File(args[2]),
        assetOutput = File(args[3]),
        provenanceOutput = File(args[4]),
        fribbUrl = args[5],
        fribbCommit = args.getOrNull(6),
        scudleeUrl = args[7],
        scudleeCommit = null
    ))
}
```

- [ ] **Step 3: Write the end-to-end fixture test**

Create `tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/AssetFixtureEndToEndTest.kt`:

```kotlin
package com.nexio.animemap

import com.nexio.animemap.model.NexioAnimeMap
import com.nexio.animemap.model.NexioAnimeMapJsonAdapter
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AssetFixtureEndToEndTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun copyResource(name: String, dest: File) {
        val bytes = javaClass.getResource("/fixtures/$name")!!.readBytes()
        dest.writeBytes(bytes)
    }

    @Test fun `mha resources project to tvdb seasons one through three`() {
        val fribbIn = tmp.newFile("fribb.json")
        val scudleeIn = tmp.newFile("scudlee.xml")
        val overlayIn = tmp.newFile("overlay.json")
        val assetOut = File(tmp.root, "asset.json")
        val provOut = File(tmp.root, "prov.json")

        copyResource("fribb-mha-and-one-piece.json", fribbIn)
        copyResource("mha-eight-seasons.xml", scudleeIn)
        copyResource("overlay-examples.json", overlayIn)

        Generator.run(Generator.Args(
            fribbInput = fribbIn, scudleeInput = scudleeIn, overlayInput = overlayIn,
            assetOutput = assetOut, provenanceOutput = provOut,
            fribbUrl = "fribb://test", fribbCommit = "fcommit",
            scudleeUrl = "scudlee://test", scudleeCommit = "scommit"
        ))

        assertTrue("asset must exist", assetOut.exists())
        val asset = NexioAnimeMapJsonAdapter(Moshi.Builder().build()).fromJson(assetOut.readText())!!
        assertEquals(2, asset.schemaVersion)
        val mhaS1 = asset.identityRecordsByKitsu["11469"]!!
        assertEquals("1", mhaS1.tvdbSeason)
        val mhaS3 = asset.identityRecordsByKitsu["13881"]!!
        assertEquals("3", mhaS3.tvdbSeason)
    }

    @Test fun `byTvdb groups mha kitsu ids together`() {
        val fribbIn = tmp.newFile("fribb.json")
        val scudleeIn = tmp.newFile("scudlee.xml")
        val overlayIn = tmp.newFile("overlay.json")
        val assetOut = File(tmp.root, "asset.json")
        val provOut = File(tmp.root, "prov.json")
        copyResource("fribb-mha-and-one-piece.json", fribbIn)
        copyResource("mha-eight-seasons.xml", scudleeIn)
        copyResource("overlay-examples.json", overlayIn)
        Generator.run(Generator.Args(fribbIn, scudleeIn, overlayIn, assetOut, provOut,
            "f://", null, "s://", null))
        val asset = NexioAnimeMapJsonAdapter(Moshi.Builder().build()).fromJson(assetOut.readText())!!
        val mhaList = asset.indexes.byTvdb["305074"]!!
        assertTrue(mhaList.contains("11469"))
        assertTrue(mhaList.contains("13881"))
    }

    @Test fun `provenance file captures source commits and overlay version`() {
        val fribbIn = tmp.newFile("fribb.json")
        val scudleeIn = tmp.newFile("scudlee.xml")
        val overlayIn = tmp.newFile("overlay.json")
        val assetOut = File(tmp.root, "asset.json")
        val provOut = File(tmp.root, "prov.json")
        copyResource("fribb-mha-and-one-piece.json", fribbIn)
        copyResource("mha-eight-seasons.xml", scudleeIn)
        copyResource("overlay-examples.json", overlayIn)
        Generator.run(Generator.Args(fribbIn, scudleeIn, overlayIn, assetOut, provOut,
            "https://fribb", "abc123", "https://scudlee", "def456"))
        assertNotNull(provOut.readText())
        assertTrue(provOut.readText().contains("abc123"))
        assertTrue(provOut.readText().contains("def456"))
    }
}
```

- [ ] **Step 4: Run, verify failure**

```bash
./gradlew :tools:anime-mapping-generator:test --tests "com.nexio.animemap.AssetFixtureEndToEndTest" 2>&1 | grep -E "FAILED|BUILD|error:"
```

Expected: `BUILD FAILED` initially or `BUILD SUCCESSFUL` if all components are wired.

- [ ] **Step 5: Iterate until tests pass**

If failure messages mention `OverlayFileJsonAdapter` not found, that's just KSP needing to regenerate after Task 3's overlay model. Re-run:

```bash
./gradlew :tools:anime-mapping-generator:clean :tools:anime-mapping-generator:test --tests "com.nexio.animemap.AssetFixtureEndToEndTest" 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/Main.kt \
        tools/anime-mapping-generator/src/test/kotlin/com/nexio/animemap/AssetFixtureEndToEndTest.kt \
        tools/anime-mapping-generator/src/test/resources/fixtures/overlay-examples.json
git commit -m "feat(anime-mapping-generator): wire CLI Main and add end-to-end fixture test"
```

---

## Task 13: Gradle wiring (fetch + generate tasks, app preBuild dependency, delete inline Fribb)

**Files:**
- Modify: `tools/anime-mapping-generator/build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Delete: `app/src/main/assets/anime/anime-id-map.json`

This task atomically swaps the inline Fribb generator for the new `:tools:anime-mapping-generator`-driven pipeline. The runtime app code still expects the OLD schema after this task, so the build will compile but resolver tests in `:app` will FAIL until Task 16 is complete. We take this trade off because keeping both pipelines side-by-side adds more complexity than the green-build cost saves.

- [ ] **Step 1: Add Gradle tasks to the generator module**

Append to `tools/anime-mapping-generator/build.gradle.kts`:

```kotlin
val fribbRawUrl = "https://raw.githubusercontent.com/Fribb/anime-lists/refs/heads/master/anime-list-full.json"
val fribbCommitUrl = "https://api.github.com/repos/Fribb/anime-lists/commits/master"
val scudleeRawUrl = "https://raw.githubusercontent.com/Anime-Lists/anime-lists/refs/heads/master/anime-list-full.xml"
val scudleeCommitUrl = "https://api.github.com/repos/Anime-Lists/anime-lists/commits/master"

val cacheDir = layout.buildDirectory.dir("cache")
val fribbCache = cacheDir.map { it.file("fribb.json") }
val scudleeCache = cacheDir.map { it.file("scudlee.xml") }
val sourceShasFile = cacheDir.map { it.file("source-shas.json") }

val overlayFile = layout.projectDirectory.file("src/main/resources/nexio-anime-overlay.json")
val rootProjectDir = rootProject.layout.projectDirectory
val assetOutput = rootProjectDir.file("app/src/main/assets/anime/nexio-anime-map-v1.json")
val provenanceOutput = rootProjectDir.file("app/src/main/assets/anime/nexio-anime-map-provenance.json")

tasks.register("fetchAnimeMappingSources") {
    group = "anime-mapping"
    description = "Fetch Fribb + ScudLee upstream files into build/cache. Pass --rerun to force refresh."
    outputs.file(fribbCache)
    outputs.file(scudleeCache)
    outputs.file(sourceShasFile)
    doLast {
        val fetcher = com.nexio.animemap.fetch.UpstreamFetcher()
        val fribb = fetcher.fetchSource(fribbRawUrl, fribbCommitUrl, fribbCache.get().asFile)
        val scudlee = fetcher.fetchSource(scudleeRawUrl, scudleeCommitUrl, scudleeCache.get().asFile)
        val moshi = com.squareup.moshi.Moshi.Builder().build()
        val mapType = com.squareup.moshi.Types.newParameterizedType(
            Map::class.java, String::class.java, Any::class.java
        )
        @Suppress("UNCHECKED_CAST")
        val adapter = moshi.adapter<Map<String, Any?>>(mapType)
        sourceShasFile.get().asFile.parentFile.mkdirs()
        sourceShasFile.get().asFile.writeText(adapter.indent("  ").toJson(mapOf(
            "fribb" to mapOf("url" to fribbRawUrl, "commit" to fribb.commit, "fetchedAt" to fribb.fetchedAt),
            "scudlee" to mapOf("url" to scudleeRawUrl, "commit" to scudlee.commit, "fetchedAt" to scudlee.fetchedAt)
        )))
    }
}

tasks.register("generateAnimeMappingAsset") {
    group = "anime-mapping"
    description = "Generate nexio-anime-map-v1.json from cached upstream sources + overlay"
    inputs.file(fribbCache)
    inputs.file(scudleeCache)
    inputs.file(sourceShasFile)
    inputs.file(overlayFile)
    outputs.file(assetOutput)
    outputs.file(provenanceOutput)

    val fribbCacheFile = fribbCache
    val scudleeCacheFile = scudleeCache
    val shasFile = sourceShasFile
    val overlayResolved = overlayFile
    val assetOut = assetOutput
    val provOut = provenanceOutput
    val fribbUrl = fribbRawUrl
    val scudleeUrl = scudleeRawUrl

    doLast {
        if (!fribbCacheFile.get().asFile.exists() || !scudleeCacheFile.get().asFile.exists()) {
            error("upstream cache missing — run :tools:anime-mapping-generator:fetchAnimeMappingSources first")
        }
        val moshi = com.squareup.moshi.Moshi.Builder().build()
        val shas = if (shasFile.get().asFile.exists()) {
            @Suppress("UNCHECKED_CAST")
            moshi.adapter(Map::class.java).fromJson(shasFile.get().asFile.readText()) as? Map<String, Any?>
                ?: emptyMap()
        } else emptyMap()
        val fribbCommit = ((shas["fribb"] as? Map<*, *>)?.get("commit") as? String)
        val scudleeCommit = ((shas["scudlee"] as? Map<*, *>)?.get("commit") as? String)

        com.nexio.animemap.Generator.run(com.nexio.animemap.Generator.Args(
            fribbInput = fribbCacheFile.get().asFile,
            scudleeInput = scudleeCacheFile.get().asFile,
            overlayInput = overlayResolved.asFile,
            assetOutput = assetOut.asFile,
            provenanceOutput = provOut.asFile,
            fribbUrl = fribbUrl, fribbCommit = fribbCommit,
            scudleeUrl = scudleeUrl, scudleeCommit = scudleeCommit
        ))
    }
}
```

The buildscript needs the generator module's classes on its classpath. Add to the top of the file:

```kotlin
buildscript {
    dependencies {
        classpath(files("build/libs/anime-mapping-generator.jar"))
    }
}
```

This is awkward. Replace the approach with a JavaExec task instead — simpler:

Replace the two `tasks.register(...)` blocks above with:

```kotlin
tasks.register<Exec>("fetchAnimeMappingSources") {
    group = "anime-mapping"
    description = "Fetch Fribb + ScudLee upstream files. Run with --rerun to force refresh."
    val classpathTask = tasks.named<Jar>("jar")
    dependsOn(classpathTask)
    val cacheDirFile = cacheDir.get().asFile
    outputs.file(File(cacheDirFile, "fribb.json"))
    outputs.file(File(cacheDirFile, "scudlee.xml"))
    outputs.file(File(cacheDirFile, "source-shas.json"))
    workingDir = projectDir
    commandLine("sh", "-c",
        "java -cp \"${classpathTask.get().archiveFile.get().asFile}:\$(./gradlew -q :tools:anime-mapping-generator:dependencies --configuration runtimeClasspath | tail -n +1)\" com.nexio.animemap.FetchMainKt"
    )
}
```

That gets complicated. **Simpler approach** — use `JavaExec` task with `runtimeClasspath` directly:

Replace BOTH register blocks with this clean version:

```kotlin
val animeMappingFetch = tasks.register<JavaExec>("fetchAnimeMappingSources") {
    group = "anime-mapping"
    description = "Fetch Fribb + ScudLee upstream files."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nexio.animemap.FetchMainKt")
    args(
        fribbRawUrl, fribbCommitUrl, fribbCache.get().asFile.absolutePath,
        scudleeRawUrl, scudleeCommitUrl, scudleeCache.get().asFile.absolutePath,
        sourceShasFile.get().asFile.absolutePath
    )
    outputs.file(fribbCache)
    outputs.file(scudleeCache)
    outputs.file(sourceShasFile)
}

val animeMappingGenerate = tasks.register<JavaExec>("generateAnimeMappingAsset") {
    group = "anime-mapping"
    description = "Generate nexio-anime-map-v1.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nexio.animemap.GenerateMainKt")
    args(
        fribbCache.get().asFile.absolutePath,
        scudleeCache.get().asFile.absolutePath,
        overlayFile.asFile.absolutePath,
        sourceShasFile.get().asFile.absolutePath,
        assetOutput.asFile.absolutePath,
        provenanceOutput.asFile.absolutePath,
        fribbRawUrl, scudleeRawUrl
    )
    inputs.file(fribbCache)
    inputs.file(scudleeCache)
    inputs.file(sourceShasFile)
    inputs.file(overlayFile)
    outputs.file(assetOutput)
    outputs.file(provenanceOutput)
}
```

- [ ] **Step 2: Create FetchMain and GenerateMain entry points**

Add to `tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/Main.kt` (append below existing `main` function):

```kotlin
object FetchMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 7)
        val fetcher = com.nexio.animemap.fetch.UpstreamFetcher()
        val fribb = fetcher.fetchSource(args[0], args[1], java.io.File(args[2]))
        val scudlee = fetcher.fetchSource(args[3], args[4], java.io.File(args[5]))
        val moshi = com.squareup.moshi.Moshi.Builder().build()
        val mapType = com.squareup.moshi.Types.newParameterizedType(
            Map::class.java, String::class.java, Any::class.java
        )
        @Suppress("UNCHECKED_CAST")
        val adapter = moshi.adapter<Map<String, Any?>>(mapType).indent("  ")
        val out = java.io.File(args[6])
        out.parentFile.mkdirs()
        out.writeText(adapter.toJson(mapOf(
            "fribb" to mapOf("url" to fribb.url, "commit" to fribb.commit, "fetchedAt" to fribb.fetchedAt),
            "scudlee" to mapOf("url" to scudlee.url, "commit" to scudlee.commit, "fetchedAt" to scudlee.fetchedAt)
        )))
    }
}

object GenerateMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 8)
        val moshi = com.squareup.moshi.Moshi.Builder().build()
        val mapType = com.squareup.moshi.Types.newParameterizedType(
            Map::class.java, String::class.java, Any::class.java
        )
        @Suppress("UNCHECKED_CAST")
        val shasFile = java.io.File(args[3])
        val shas: Map<String, Any?> = if (shasFile.exists())
            (moshi.adapter<Map<String, Any?>>(mapType).fromJson(shasFile.readText()) ?: emptyMap())
        else emptyMap()
        val fribbCommit = ((shas["fribb"] as? Map<*, *>)?.get("commit") as? String)
        val scudleeCommit = ((shas["scudlee"] as? Map<*, *>)?.get("commit") as? String)
        Generator.run(Generator.Args(
            fribbInput = java.io.File(args[0]),
            scudleeInput = java.io.File(args[1]),
            overlayInput = java.io.File(args[2]),
            assetOutput = java.io.File(args[4]),
            provenanceOutput = java.io.File(args[5]),
            fribbUrl = args[6], fribbCommit = fribbCommit,
            scudleeUrl = args[7], scudleeCommit = scudleeCommit
        ))
    }
}
```

The original generic `main(args)` function remains for direct CLI usage. Update its `args.size == 8` check to require 9 args (it now also needs scudlee commit) — actually leave the original alone; the new entry points are what Gradle uses.

- [ ] **Step 3: Wire `:app:preBuild` dependency in app/build.gradle.kts**

Find the `tasks.register("generateAnimeIdMapAsset") {...}` block in `app/build.gradle.kts` (around line 85-260). Replace the entire block — and any `tasks.named("preBuild") { dependsOn(...) }` reference — with:

```kotlin
tasks.named("preBuild") {
    dependsOn(":tools:anime-mapping-generator:generateAnimeMappingAsset")
}
```

And delete:
- The `animeIdMapOutput` val
- The `animeIdMapSources` map
- All related `tasks.register(...)` blocks for asset generation
- The helper functions `normalizeFribbAnimeRecord`, `buildAnimeIdMapAsset`, `fetchJson`, `mediaTypeFromAnimeListType`, `positiveId`, `normalizedString`, `normalizedImdb`, `sortedRecord`, `indexAnimeRecord` (these are the inline pipeline being retired).

Use `grep -n "animeIdMap\|fribbAnimeList" app/build.gradle.kts` to find exact line ranges before deleting.

- [ ] **Step 4: Delete the old anime-id-map.json asset**

```bash
git rm app/src/main/assets/anime/anime-id-map.json
```

- [ ] **Step 5: Run a partial fetch + generate to verify wiring**

```bash
./gradlew :tools:anime-mapping-generator:fetchAnimeMappingSources --rerun-tasks 2>&1 | tail -20
./gradlew :tools:anime-mapping-generator:generateAnimeMappingAsset --rerun-tasks 2>&1 | tail -20
ls -la app/src/main/assets/anime/
```

Expected: `nexio-anime-map-v1.json` and `nexio-anime-map-provenance.json` exist; `anime-id-map.json` is gone.

- [ ] **Step 6: Commit**

```bash
git add tools/anime-mapping-generator/build.gradle.kts \
        tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/Main.kt \
        app/build.gradle.kts \
        app/src/main/assets/anime/nexio-anime-map-v1.json \
        app/src/main/assets/anime/nexio-anime-map-provenance.json
git rm app/src/main/assets/anime/anime-id-map.json
git commit -m "build(anime-mapping): swap inline Fribb generator for :tools:anime-mapping-generator pipeline"
```

---

## Task 14: Runtime test fixture

**Files:**
- Create: `app/src/test/resources/fixtures/nexio-anime-map-v1-test.json`

- [ ] **Step 1: Create a small fixture asset for resolver tests**

Create `app/src/test/resources/fixtures/nexio-anime-map-v1-test.json` with MHA + One Piece + an unmapped Kitsu ID:

```json
{
  "schemaVersion": 2,
  "mappingPolicyVersion": 1,
  "generatedAt": "2026-05-06T00:00:00Z",
  "counts": {"identityRecords": 4, "episodeMappingRecords": 1, "skippedCount": 0},
  "identityRecordsByKitsu": {
    "11469": {"kitsu":"11469","anidb":"11739","tvdb":"305074","tmdb":"65930","imdb":"tt5626028","mediaType":"series","sourceType":"TV","tvdbSeason":"1","tmdbSeason":"1"},
    "13881": {"kitsu":"13881","anidb":"13485","tvdb":"305074","tmdb":"65930","imdb":"tt5626028","mediaType":"series","sourceType":"TV","tvdbSeason":"3","tmdbSeason":"3"},
    "12":    {"kitsu":"12","anidb":"69","tvdb":"81797","tmdb":"37854","imdb":"tt0388629","mediaType":"series","sourceType":"TV","tvdbSeason":"a","tmdbSeason":"a","hasMappingRules":true},
    "99999": {"kitsu":"99999","mediaType":"series","sourceType":"TV"}
  },
  "episodeMappingsByAnidb": {
    "69": {
      "anidb":"69","name":"One Piece","tvdbSeriesId":"81797","tmdbTvId":"37854",
      "ranges":[
        {"sourceSeason":1,"startEpisode":1,"endEpisode":8,"targetProvider":"TVDB","targetSeason":1,"offset":0},
        {"sourceSeason":1,"startEpisode":892,"endEpisode":1085,"targetProvider":"TVDB","targetSeason":21,"offset":-891},
        {"sourceSeason":1,"startEpisode":1086,"endEpisode":1155,"targetProvider":"TVDB","targetSeason":22,"offset":-1085},
        {"sourceSeason":1,"startEpisode":1156,"targetProvider":"TVDB","targetSeason":23,"offset":-1155},
        {"sourceSeason":1,"startEpisode":1,"endEpisode":61,"targetProvider":"TMDB","targetSeason":1,"offset":0}
      ],
      "explicitMaps":[
        {"sourceSeason":0,"sourceEpisode":1,"targetProvider":"TVDB","targetSeason":0,"targetEpisode":27}
      ]
    }
  },
  "indexes": {
    "byKitsu":{"11469":"11469","13881":"13881","12":"12","99999":"99999"},
    "byMal":{},"byAnilist":{},"byAnidb":{"11739":"11469","13485":"13881","69":"12"},
    "byTvdb":{"305074":["11469","13881"],"81797":["12"]},
    "byTmdbTv":{"65930":["11469","13881"],"37854":["12"]},
    "byTmdbMovie":{},"byImdb":{"tt5626028":"11469","tt0388629":"12"}
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/test/resources/fixtures/nexio-anime-map-v1-test.json
git commit -m "test(anime): add small nexio-anime-map-v1 fixture for resolver tests"
```

---

## Task 15: Add new types to :app (additive)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/SeasonMarker.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/projection/FallbackReason.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/projection/SeasonPresentationSource.kt`

This task adds new types without removing any old ones. The existing resolver continues to compile and pass tests. Old enum values are removed in Task 16 once the resolver no longer references them.

- [ ] **Step 1: Create SeasonMarker**

Create `app/src/main/java/com/nexio/tv/core/anime/projection/SeasonMarker.kt`:

```kotlin
package com.nexio.tv.core.anime.projection

sealed interface SeasonMarker {
    data class Number(val season: Int) : SeasonMarker
    data object Absolute : SeasonMarker
    data object Hentai : SeasonMarker
    data object Unknown : SeasonMarker

    companion object {
        fun fromWire(value: String?): SeasonMarker? {
            val trimmed = value?.trim()?.lowercase() ?: return null
            if (trimmed.isEmpty()) return null
            return when (trimmed) {
                "a" -> Absolute
                "hentai" -> Hentai
                "unknown" -> Unknown
                else -> trimmed.toIntOrNull()?.let(::Number)
            }
        }
    }
}
```

- [ ] **Step 2: Add new FallbackReason entries (don't remove old yet)**

Open `app/src/main/java/com/nexio/tv/core/anime/projection/FallbackReason.kt`. Add the new values to the enum:

```kotlin
enum class FallbackReason {
    LOW_CONFIDENCE_FLAT_KITSU,
    NO_TVDB_MAPPING,
    KITSU_NOT_IN_PACK,
    NO_CURATED_SEASON,
    SEASON_MARKER_HENTAI,
    SEASON_MARKER_UNKNOWN,
    EPISODE_OUT_OF_RANGE,
    OVERLAY_DROPPED
}
```

- [ ] **Step 3: Add new SeasonPresentationSource values (don't remove old yet)**

Open `app/src/main/java/com/nexio/tv/core/anime/projection/SeasonPresentationSource.kt`. Add new values:

```kotlin
enum class SeasonPresentationSource {
    KITSU_SEASON_NUMBERS,
    KITSU_FLAT_FALLBACK,
    CURATED_PER_RESOURCE,
    CURATED_RANGE_RULES
}
```

- [ ] **Step 4: Verify compile**

```bash
./gradlew :app:compileUniversalDebugKotlin 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/projection/SeasonMarker.kt \
        app/src/main/java/com/nexio/tv/core/anime/projection/FallbackReason.kt \
        app/src/main/java/com/nexio/tv/core/anime/projection/SeasonPresentationSource.kt
git commit -m "feat(anime): add SeasonMarker, expand FallbackReason and SeasonPresentationSource"
```

---

## Task 16: Atomic schema + service + resolver swap

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMapAsset.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt`
- Modify: All resolver test files in `app/src/test/java/com/nexio/tv/core/anime/projection/`

This is the largest task. It atomically swaps the v1 schema to v2 and rewrites the resolver to use the new asset. Tests for the old behavior are removed; new tests against the fixture from Task 14 prove the strict-mode contract.

**Sequence is critical** — types come first, then service rewrite, then resolver rewrite, then tests.

- [ ] **Step 1: Replace AnimeIdMapAsset.kt**

Open `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMapAsset.kt` and replace its contents:

```kotlin
package com.nexio.tv.core.anime

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AnimeIdMapAsset(
    @Json(name = "schemaVersion") val schemaVersion: Int,
    @Json(name = "mappingPolicyVersion") val mappingPolicyVersion: Int = 1,
    @Json(name = "generatedAt") val generatedAt: String? = null,
    @Json(name = "counts") val counts: AnimeIdMapAssetCounts? = null,
    @Json(name = "identityRecordsByKitsu") val identityRecordsByKitsu: Map<String, AnimeIdMapRecord> = emptyMap(),
    @Json(name = "episodeMappingsByAnidb") val episodeMappingsByAnidb: Map<String, AnimeEpisodeMappingRecord> = emptyMap(),
    @Json(name = "indexes") val indexes: AnimeIdMapIndexes = AnimeIdMapIndexes()
)

@JsonClass(generateAdapter = true)
data class AnimeIdMapAssetCounts(
    @Json(name = "identityRecords") val identityRecords: Int = 0,
    @Json(name = "episodeMappingRecords") val episodeMappingRecords: Int = 0,
    @Json(name = "skippedCount") val skippedCount: Int = 0
)

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
    @Json(name = "evidence") val evidence: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AnimeEpisodeMappingRecord(
    @Json(name = "anidb") val anidb: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "tvdbSeriesId") val tvdbSeriesId: String? = null,
    @Json(name = "tmdbTvId") val tmdbTvId: String? = null,
    @Json(name = "ranges") val ranges: List<AnimeRangeRule> = emptyList(),
    @Json(name = "explicitMaps") val explicitMaps: List<AnimeExplicitMap> = emptyList(),
    @Json(name = "evidence") val evidence: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AnimeRangeRule(
    @Json(name = "sourceSeason") val sourceSeason: Int,
    @Json(name = "startEpisode") val startEpisode: Int,
    @Json(name = "endEpisode") val endEpisode: Int? = null,
    @Json(name = "targetProvider") val targetProvider: String,
    @Json(name = "targetSeason") val targetSeason: Int,
    @Json(name = "offset") val offset: Int
)

@JsonClass(generateAdapter = true)
data class AnimeExplicitMap(
    @Json(name = "sourceSeason") val sourceSeason: Int,
    @Json(name = "sourceEpisode") val sourceEpisode: Int,
    @Json(name = "targetProvider") val targetProvider: String,
    @Json(name = "targetSeason") val targetSeason: Int,
    @Json(name = "targetEpisode") val targetEpisode: Int
)

@JsonClass(generateAdapter = true)
data class AnimeIdMapIndexes(
    @Json(name = "byKitsu") val byKitsu: Map<String, String> = emptyMap(),
    @Json(name = "byMal") val byMal: Map<String, String> = emptyMap(),
    @Json(name = "byAnilist") val byAnilist: Map<String, String> = emptyMap(),
    @Json(name = "byAnidb") val byAnidb: Map<String, String> = emptyMap(),
    @Json(name = "byTvdb") val byTvdb: Map<String, List<String>> = emptyMap(),
    @Json(name = "byTmdbTv") val byTmdbTv: Map<String, List<String>> = emptyMap(),
    @Json(name = "byTmdbMovie") val byTmdbMovie: Map<String, String> = emptyMap(),
    @Json(name = "byImdb") val byImdb: Map<String, String> = emptyMap()
)

enum class ContentMediaKind { MOVIE, SERIES }
```

- [ ] **Step 2: Update AnimeIdMappingService**

Open `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt` and update the loader to read `nexio-anime-map-v1.json` and to expose the new lookups. Replace the body of the class with:

```kotlin
package com.nexio.tv.core.anime

import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimeIdMappingService @Inject constructor(
    private val assetProvider: () -> AnimeIdMapAsset
) {

    constructor() : this(assetProvider = { defaultAssetProvider() })

    private val asset: AnimeIdMapAsset by lazy { assetProvider() }

    fun recordForKitsuId(kitsuId: String): AnimeIdMapRecord? =
        asset.identityRecordsByKitsu[kitsuId.removePrefix("kitsu:")]

    fun recordForAnidbId(anidbId: String): AnimeIdMapRecord? {
        val kitsu = asset.indexes.byAnidb[anidbId] ?: return null
        return asset.identityRecordsByKitsu[kitsu]
    }

    fun episodeMappingForAnidb(anidbId: String): AnimeEpisodeMappingRecord? =
        asset.episodeMappingsByAnidb[anidbId]

    fun allSeriesRecordsSharingTvdb(record: AnimeIdMapRecord): List<AnimeIdMapRecord> {
        val tvdb = record.tvdb ?: return listOf(record)
        val kitsuIds = asset.indexes.byTvdb[tvdb] ?: return listOf(record)
        return kitsuIds.mapNotNull { asset.identityRecordsByKitsu[it] }
            .filter { it.mediaType == "series" }
    }

    companion object {
        private fun defaultAssetProvider(): AnimeIdMapAsset {
            val moshi = Moshi.Builder().build()
            val adapter = AnimeIdMapAssetJsonAdapter(moshi)
            val stream = AnimeIdMappingService::class.java.classLoader
                ?.getResourceAsStream("assets/anime/nexio-anime-map-v1.json")
                ?: error("nexio-anime-map-v1.json missing from app assets")
            return stream.bufferedReader().use { adapter.fromJson(it.readText()) }
                ?: error("failed to parse nexio-anime-map-v1.json")
        }
    }
}
```

If the previous service had methods that other code in the app relies on (e.g. `kitsuToImdb`), keep those methods and adapt them to the new schema (likely simple lookups via indexes).

- [ ] **Step 3: Rewrite DefaultAnimeSeasonProjectionResolver**

Open `app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt` and replace the entire file:

```kotlin
package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeEpisodeMappingRecord
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.AnimeRangeRule
import com.nexio.tv.core.trace.AnimeProjectionTraceEvents
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAnimeSeasonProjectionResolver @Inject constructor(
    private val mappingService: AnimeIdMappingService,
    private val store: AnimeEpisodeCoordinateStore,
    private val traceEvents: AnimeProjectionTraceEvents
) : AnimeSeasonProjectionResolver {

    override suspend fun resolveWork(source: AnimeSourceIdentity): AnimeWorkIdentity {
        val kitsuId = source.sourceKitsuId?.removePrefix("kitsu:")?.takeIf { it.isNotBlank() }
            ?: return unknownWork(source)
        val record = mappingService.recordForKitsuId(kitsuId) ?: return unknownWork(source)

        val memberRecords = mappingService.allSeriesRecordsSharingTvdb(record)
        val memberIds = memberRecords.map { it.kitsu }.toSet()
        val primary = memberRecords.minByOrNull { it.kitsu.toIntOrNull() ?: Int.MAX_VALUE }?.kitsu

        val groupKey = AnimeWorkGroupKey.preferred(record.tvdb, record.imdb, record.tmdb, kitsuId)
        val confidence = when {
            !record.tvdb.isNullOrBlank() -> AnimeGroupingConfidence.HIGH
            !record.imdb.isNullOrBlank() -> AnimeGroupingConfidence.MEDIUM
            else -> AnimeGroupingConfidence.LOW
        }
        val result = AnimeWorkIdentity(
            groupKey = groupKey,
            primaryKitsuId = primary,
            memberKitsuIds = memberIds,
            providerIds = ProviderIds(
                tvdb = record.tvdb, imdb = record.imdb, tmdb = record.tmdb,
                kitsu = kitsuId, mal = record.mal, anilist = record.anilist, anidb = record.anidb
            ),
            confidence = confidence,
            evidence = listOfNotNull(
                record.tvdb?.let { "kitsu.tvdb=$it" },
                record.imdb?.let { "kitsu.imdb=$it" },
                record.tmdb?.let { "kitsu.tmdb=$it" }
            )
        )
        traceEvents.emitWorkResolved(result)
        return result
    }

    override suspend fun resolveSeasonPresentation(
        work: AnimeWorkIdentity,
        sourceKitsuId: String,
        requestedSeason: Int?
    ): AnimeSeasonPresentation {
        val cleanSourceId = sourceKitsuId.removePrefix("kitsu:")
        val record = mappingService.recordForKitsuId(cleanSourceId)
            ?: return unresolvedPresentation(work, FallbackReason.KITSU_NOT_IN_PACK)

        val mapping = record.anidb?.let { mappingService.episodeMappingForAnidb(it) }
        if (mapping != null && mapping.ranges.isNotEmpty()) {
            return rangeRulePresentation(work, mapping, requestedSeason)
        }

        val seasonals = mappingService.allSeriesRecordsSharingTvdb(record)
            .mapNotNull { rec ->
                val marker = SeasonMarker.fromWire(rec.tvdbSeason)
                if (marker is SeasonMarker.Number) rec to marker else null
            }
        if (seasonals.isNotEmpty()) {
            return perResourcePresentation(work, seasonals, requestedSeason)
        }

        val fallback = when (SeasonMarker.fromWire(record.tvdbSeason)) {
            SeasonMarker.Hentai -> FallbackReason.SEASON_MARKER_HENTAI
            SeasonMarker.Unknown -> FallbackReason.SEASON_MARKER_UNKNOWN
            else -> FallbackReason.NO_CURATED_SEASON
        }
        return unresolvedPresentation(work, fallback)
    }

    override suspend fun resolveEpisodeProjection(
        work: AnimeWorkIdentity,
        sourceEpisode: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget
    ): AnimeEpisodeProjection {
        store.get(work.groupKey, sourceEpisode, target)?.let { return it }

        val record = mappingService.recordForKitsuId(sourceEpisode.sourceKitsuId)
        val computed = if (record == null) {
            unresolvedProjection(sourceEpisode, FallbackReason.KITSU_NOT_IN_PACK)
        } else {
            project(record, sourceEpisode, target)
        }
        store.put(work.groupKey, sourceEpisode, target, computed)
        if (computed.scrobbleCoordinate != null) traceEvents.emitEpisodeCoordinateResolved(computed, target)
        else traceEvents.emitEpisodeCoordinateUnresolved(
            sourceKitsuId = sourceEpisode.sourceKitsuId,
            season = sourceEpisode.season, episode = sourceEpisode.episode,
            target = target, fallbackReason = computed.fallbackReason
        )
        return computed
    }

    private fun project(
        record: AnimeIdMapRecord,
        sourceEpisode: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget
    ): AnimeEpisodeProjection {
        val mapping = record.anidb?.let { mappingService.episodeMappingForAnidb(it) }
        val targetProvider = providerFor(target)
        val (targetCoord, fallback) = projectVia(mapping, record, sourceEpisode, targetProvider)
        val sourceCoord = EpisodeCoordinate(ProviderId.KITSU, sourceEpisode.sourceKitsuId, sourceEpisode.season, sourceEpisode.episode)
        val tvdbCoord = if (targetProvider == ProviderId.TVDB) targetCoord
            else projectVia(mapping, record, sourceEpisode, ProviderId.TVDB).first
        val tmdbCoord = if (targetProvider == ProviderId.TMDB) targetCoord
            else projectVia(mapping, record, sourceEpisode, ProviderId.TMDB).first

        val confidence = when {
            fallback != null -> CoordinateConfidence.LOW
            targetCoord?.provider == ProviderId.TVDB -> CoordinateConfidence.HIGH
            targetCoord != null -> CoordinateConfidence.MEDIUM
            else -> CoordinateConfidence.UNKNOWN
        }
        val isScrobble = target == EpisodeProjectionTarget.TRAKT_SCROBBLE || target == EpisodeProjectionTarget.SIMKL_SCROBBLE
        val scrobbleCoord = if (isScrobble && confidence == CoordinateConfidence.HIGH) tvdbCoord else if (!isScrobble) tvdbCoord else null
        return AnimeEpisodeProjection(
            sourceKitsuId = sourceEpisode.sourceKitsuId,
            sourceKitsuCoordinate = sourceCoord,
            displayCoordinate = tvdbCoord ?: sourceCoord,
            targetCoordinate = targetCoord,
            scrobbleCoordinate = scrobbleCoord,
            premiumArtworkCoordinate = if (confidence != CoordinateConfidence.LOW) (tvdbCoord ?: tmdbCoord) else null,
            tvdbCoordinate = tvdbCoord,
            tmdbCoordinate = tmdbCoord,
            confidence = confidence,
            fallbackReason = fallback,
            evidence = listOfNotNull(
                record.tvdb?.let { "kitsu.tvdb=$it" },
                record.tmdb?.let { "kitsu.tmdb=$it" },
                if (mapping != null) "curated.range-or-explicit" else null
            )
        )
    }

    private fun projectVia(
        mapping: AnimeEpisodeMappingRecord?,
        record: AnimeIdMapRecord,
        sourceEpisode: SourceEpisodeCoordinate,
        targetProvider: ProviderId
    ): Pair<EpisodeCoordinate?, FallbackReason?> {
        val seriesId = when (targetProvider) {
            ProviderId.TVDB -> mapping?.tvdbSeriesId ?: record.tvdb
            ProviderId.TMDB -> mapping?.tmdbTvId ?: record.tmdb
            else -> null
        }
        if (seriesId == null) return null to FallbackReason.NO_CURATED_SEASON

        if (mapping != null) {
            val explicit = mapping.explicitMaps.firstOrNull {
                it.sourceSeason == sourceEpisode.season &&
                    it.sourceEpisode == sourceEpisode.episode &&
                    it.targetProvider == targetProvider.name
            }
            if (explicit != null) {
                return EpisodeCoordinate(targetProvider, seriesId, explicit.targetSeason, explicit.targetEpisode) to null
            }
            val range = mapping.ranges.firstOrNull {
                it.targetProvider == targetProvider.name &&
                    it.sourceSeason == sourceEpisode.season &&
                    sourceEpisode.episode >= it.startEpisode &&
                    (it.endEpisode == null || sourceEpisode.episode <= it.endEpisode)
            }
            if (range != null) {
                return EpisodeCoordinate(targetProvider, seriesId, range.targetSeason, sourceEpisode.episode + range.offset) to null
            }
            // mapping exists but no rule matched — out of range
            return null to FallbackReason.EPISODE_OUT_OF_RANGE
        }

        val seasonField = if (targetProvider == ProviderId.TVDB) record.tvdbSeason else record.tmdbSeason
        val offsetField = if (targetProvider == ProviderId.TVDB) record.tvdbEpisodeOffset else record.tmdbEpisodeOffset
        return when (val marker = SeasonMarker.fromWire(seasonField)) {
            null -> null to FallbackReason.NO_CURATED_SEASON
            SeasonMarker.Hentai -> null to FallbackReason.SEASON_MARKER_HENTAI
            SeasonMarker.Unknown -> null to FallbackReason.SEASON_MARKER_UNKNOWN
            SeasonMarker.Absolute -> null to FallbackReason.EPISODE_OUT_OF_RANGE
            is SeasonMarker.Number -> EpisodeCoordinate(targetProvider, seriesId, marker.season, sourceEpisode.episode + (offsetField ?: 0)) to null
        }
    }

    private fun providerFor(target: EpisodeProjectionTarget): ProviderId = when (target) {
        EpisodeProjectionTarget.TRAKT_SCROBBLE,
        EpisodeProjectionTarget.SIMKL_SCROBBLE,
        EpisodeProjectionTarget.UI_DISPLAY,
        EpisodeProjectionTarget.PREMIUM_THUMBNAIL,
        EpisodeProjectionTarget.CONTINUE_WATCHING,
        EpisodeProjectionTarget.EPISODE_RATING -> ProviderId.TVDB
    }

    private fun perResourcePresentation(
        work: AnimeWorkIdentity,
        seasonals: List<Pair<AnimeIdMapRecord, SeasonMarker.Number>>,
        requestedSeason: Int?
    ): AnimeSeasonPresentation {
        val tabs = seasonals.distinctBy { it.second.season }.sortedBy { it.second.season }.map { (rec, marker) ->
            AnimeSeasonTab(
                seasonNumber = marker.season,
                title = null,
                episodeCount = null,
                episodesKitsuMemberId = rec.kitsu,
                isFlatFallback = false
            )
        }
        val auto = tabs.firstOrNull()?.seasonNumber ?: 1
        val selected = requestedSeason?.takeIf { req -> tabs.any { it.seasonNumber == req } } ?: auto
        return AnimeSeasonPresentation(
            work = work, seasons = tabs, selectedSeason = selected,
            source = SeasonPresentationSource.CURATED_PER_RESOURCE,
            confidence = CoordinateConfidence.HIGH
        )
    }

    private fun rangeRulePresentation(
        work: AnimeWorkIdentity,
        mapping: AnimeEpisodeMappingRecord,
        requestedSeason: Int?
    ): AnimeSeasonPresentation {
        val tabs = mapping.ranges.filter { it.targetProvider == "TVDB" }
            .map { it.targetSeason }.distinct().sorted().map { season ->
                AnimeSeasonTab(
                    seasonNumber = season, title = null, episodeCount = null,
                    episodesKitsuMemberId = null, isFlatFallback = false
                )
            }
        val auto = tabs.firstOrNull()?.seasonNumber ?: 1
        val selected = requestedSeason?.takeIf { req -> tabs.any { it.seasonNumber == req } } ?: auto
        return AnimeSeasonPresentation(
            work = work, seasons = tabs, selectedSeason = selected,
            source = SeasonPresentationSource.CURATED_RANGE_RULES,
            confidence = CoordinateConfidence.HIGH
        )
    }

    private fun unresolvedPresentation(
        work: AnimeWorkIdentity, reason: FallbackReason
    ): AnimeSeasonPresentation = AnimeSeasonPresentation(
        work = work, seasons = emptyList(), selectedSeason = 1,
        source = SeasonPresentationSource.CURATED_PER_RESOURCE,
        confidence = CoordinateConfidence.LOW,
        fallbackReason = reason
    )

    private fun unresolvedProjection(
        sourceEpisode: SourceEpisodeCoordinate, reason: FallbackReason
    ): AnimeEpisodeProjection = AnimeEpisodeProjection(
        sourceKitsuId = sourceEpisode.sourceKitsuId,
        sourceKitsuCoordinate = EpisodeCoordinate(ProviderId.KITSU, sourceEpisode.sourceKitsuId, sourceEpisode.season, sourceEpisode.episode),
        displayCoordinate = EpisodeCoordinate(ProviderId.KITSU, sourceEpisode.sourceKitsuId, sourceEpisode.season, sourceEpisode.episode),
        targetCoordinate = null, scrobbleCoordinate = null, premiumArtworkCoordinate = null,
        tvdbCoordinate = null, tmdbCoordinate = null,
        confidence = CoordinateConfidence.LOW, fallbackReason = reason, evidence = emptyList()
    )

    private fun unknownWork(source: AnimeSourceIdentity): AnimeWorkIdentity = AnimeWorkIdentity(
        groupKey = AnimeWorkGroupKey.preferred(null, null, null, source.sourceKitsuId),
        primaryKitsuId = source.sourceKitsuId,
        memberKitsuIds = setOfNotNull(source.sourceKitsuId),
        providerIds = ProviderIds(kitsu = source.sourceKitsuId),
        confidence = AnimeGroupingConfidence.LOW,
        evidence = listOf("no-mapping-record")
    )
}
```

If `AnimeSeasonPresentation` doesn't currently have a `fallbackReason` field, add it: open `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonPresentation.kt` and add `val fallbackReason: FallbackReason? = null` to the data class.

- [ ] **Step 4: Update existing resolver tests to use the fixture**

For each file in `app/src/test/java/com/nexio/tv/core/anime/projection/Default*Test.kt`, replace its contents with tests that load `nexio-anime-map-v1-test.json` and exercise the new resolver. Sample replacement for `DefaultAnimeSeasonProjectionResolverEpisodeTest.kt`:

```kotlin
package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapAssetJsonAdapter
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.domain.model.ProviderId
import com.squareup.moshi.Moshi
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultAnimeSeasonProjectionResolverEpisodeTest {

    private fun fixtureService(): AnimeIdMappingService {
        val json = javaClass.getResource("/fixtures/nexio-anime-map-v1-test.json")!!.readText()
        val asset = AnimeIdMapAssetJsonAdapter(Moshi.Builder().build()).fromJson(json)!!
        return AnimeIdMappingService(assetProvider = { asset })
    }

    private fun resolver(): DefaultAnimeSeasonProjectionResolver = DefaultAnimeSeasonProjectionResolver(
        mappingService = fixtureService(),
        store = InMemoryAnimeEpisodeCoordinateStore(),
        traceEvents = mockk(relaxed = true)
    )

    @Test fun `MHA S3E1 projects to TVDB S3E1 with HIGH confidence for trakt scrobble`() = runBlocking {
        val r = resolver()
        val work = r.resolveWork(AnimeSourceIdentity("13881", null))
        val src = SourceEpisodeCoordinate(sourceKitsuId = "13881", season = 1, episode = 1)
        val proj = r.resolveEpisodeProjection(work, src, EpisodeProjectionTarget.TRAKT_SCROBBLE)

        assertNotNull(proj.scrobbleCoordinate)
        assertEquals(ProviderId.TVDB, proj.scrobbleCoordinate?.provider)
        assertEquals("305074", proj.scrobbleCoordinate?.seriesId)
        assertEquals(3, proj.scrobbleCoordinate?.season)
        assertEquals(1, proj.scrobbleCoordinate?.episode)
        assertEquals(CoordinateConfidence.HIGH, proj.confidence)
        assertNull(proj.fallbackReason)
    }

    @Test fun `One Piece episode 892 projects to TVDB S21E1 via range rule`() = runBlocking {
        val r = resolver()
        val work = r.resolveWork(AnimeSourceIdentity("12", null))
        val src = SourceEpisodeCoordinate(sourceKitsuId = "12", season = 1, episode = 892)
        val proj = r.resolveEpisodeProjection(work, src, EpisodeProjectionTarget.TRAKT_SCROBBLE)

        assertEquals(21, proj.scrobbleCoordinate?.season)
        assertEquals(1, proj.scrobbleCoordinate?.episode)
    }

    @Test fun `One Piece episode 1156 uses open-ended range to TVDB S23E1`() = runBlocking {
        val r = resolver()
        val work = r.resolveWork(AnimeSourceIdentity("12", null))
        val src = SourceEpisodeCoordinate(sourceKitsuId = "12", season = 1, episode = 1156)
        val proj = r.resolveEpisodeProjection(work, src, EpisodeProjectionTarget.TRAKT_SCROBBLE)

        assertEquals(23, proj.scrobbleCoordinate?.season)
        assertEquals(1, proj.scrobbleCoordinate?.episode)
    }

    @Test fun `unmapped Kitsu id returns unresolved with KITSU_NOT_IN_PACK`() = runBlocking {
        val r = resolver()
        val work = r.resolveWork(AnimeSourceIdentity("88888", null))
        val src = SourceEpisodeCoordinate(sourceKitsuId = "88888", season = 1, episode = 1)
        val proj = r.resolveEpisodeProjection(work, src, EpisodeProjectionTarget.TRAKT_SCROBBLE)

        assertNull(proj.scrobbleCoordinate)
        assertEquals(FallbackReason.KITSU_NOT_IN_PACK, proj.fallbackReason)
    }
}
```

Apply analogous rewrites to:
- `DefaultAnimeSeasonProjectionResolverPresentationTest.kt` — verify CURATED_PER_RESOURCE for MHA, CURATED_RANGE_RULES for One Piece, KITSU_NOT_IN_PACK for unmapped.
- `DefaultAnimeSeasonProjectionResolverWorkTest.kt` — verify resolveWork groups MHA Kitsu IDs sharing tvdb=305074.

Delete any tests that asserted `LOW_CONFIDENCE_FLAT_KITSU` or `KITSU_FLAT_FALLBACK`.

- [ ] **Step 5: Run the full resolver test suite**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.anime.projection.*" 2>&1 | grep -E "PASSED|FAILED|tests|BUILD"
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 6: Compile full app**

```bash
./gradlew :app:compileUniversalDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/AnimeIdMapAsset.kt \
        app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt \
        app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt \
        app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonPresentation.kt \
        app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverEpisodeTest.kt \
        app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverPresentationTest.kt \
        app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverWorkTest.kt
git commit -m "feat(anime): switch resolver to strict curated-data lookup with v2 schema"
```

---

## Task 17: Delete AnimeSeasonPresentationCache (Phase 2.1 retirement)

**Files:**
- Delete: `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonPresentationCache.kt`
- Delete: `app/src/main/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCache.kt`
- Delete: `app/src/test/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCacheTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/AnimeProjectionModule.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/projection/FallbackReason.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/projection/SeasonPresentationSource.kt`

- [ ] **Step 1: Delete the cache files**

```bash
git rm app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonPresentationCache.kt
git rm app/src/main/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCache.kt
git rm app/src/test/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCacheTest.kt
```

- [ ] **Step 2: Remove the binding from AnimeProjectionModule**

Open `app/src/main/java/com/nexio/tv/core/di/AnimeProjectionModule.kt` and remove the `bindPresentationCache` `@Binds` method. Also remove its import lines.

- [ ] **Step 3: Remove the now-unused FallbackReason values**

Open `app/src/main/java/com/nexio/tv/core/anime/projection/FallbackReason.kt`. Remove `LOW_CONFIDENCE_FLAT_KITSU` and `NO_TVDB_MAPPING`.

- [ ] **Step 4: Remove the now-unused SeasonPresentationSource value**

Open `app/src/main/java/com/nexio/tv/core/anime/projection/SeasonPresentationSource.kt`. Remove `KITSU_FLAT_FALLBACK`. Keep `KITSU_SEASON_NUMBERS` only if still referenced by other code paths.

- [ ] **Step 5: Verify compile and tests**

```bash
./gradlew :app:compileUniversalDebugKotlin 2>&1 | grep -E "error:|BUILD"
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.anime.*" 2>&1 | grep -E "PASSED|FAILED|tests|BUILD"
```

Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/di/AnimeProjectionModule.kt \
        app/src/main/java/com/nexio/tv/core/anime/projection/FallbackReason.kt \
        app/src/main/java/com/nexio/tv/core/anime/projection/SeasonPresentationSource.kt
git commit -m "refactor(anime): retire AnimeSeasonPresentationCache and dead enum entries"
```

---

## Task 18: New trace events

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/AnimeProjectionTraceEvents.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt`

- [ ] **Step 1: Add new trace event methods**

Open `app/src/main/java/com/nexio/tv/core/trace/AnimeProjectionTraceEvents.kt`. Add new methods to the interface:

```kotlin
fun emitCuratedHit(source: String, kitsuId: String, target: String)
fun emitUnresolvedTopKitsuId(kitsuId: String, reason: String)
```

Update the implementation class to emit these events using the existing trace bus pattern (matches the existing `emitWorkResolved`, `emitEpisodeCoordinateResolved` style).

- [ ] **Step 2: Wire emissions in DefaultAnimeSeasonProjectionResolver**

Open `DefaultAnimeSeasonProjectionResolver.kt` (rewritten in Task 16). In `resolveEpisodeProjection`, after the projection is computed:

```kotlin
if (computed.scrobbleCoordinate != null) {
    traceEvents.emitEpisodeCoordinateResolved(computed, target)
    traceEvents.emitCuratedHit(
        source = if (record?.hasMappingRules == true) "range-or-explicit" else "per-resource",
        kitsuId = sourceEpisode.sourceKitsuId,
        target = target.name
    )
} else {
    traceEvents.emitEpisodeCoordinateUnresolved(...)
    computed.fallbackReason?.let {
        traceEvents.emitUnresolvedTopKitsuId(sourceEpisode.sourceKitsuId, it.name)
    }
}
```

- [ ] **Step 3: Compile and run tests**

```bash
./gradlew :app:compileUniversalDebugKotlin 2>&1 | grep -E "error:|BUILD"
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.anime.projection.*" 2>&1 | grep -E "PASSED|FAILED|tests|BUILD"
```

Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/trace/AnimeProjectionTraceEvents.kt \
        app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt
git commit -m "feat(anime): emit curated_hit and unresolved trace events"
```

---

## Task 19: Update consumer tests

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPoster*Test.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapterTest.kt` (if it exists)
- Modify: `app/src/test/java/com/nexio/tv/data/integration/railpreview/KitsuRailFranchiseGrouperTest.kt`

- [ ] **Step 1: Update TrackingScrobbleService tests for One Piece positive case**

Find the test (`grep -n "TrackingScrobble" app/src/test/java/`) and add:

```kotlin
@Test fun `one piece episode 892 emits TVDB S21E1 to trakt`() = runBlocking {
    val mappingService = AnimeIdMappingService(assetProvider = { fixtureAsset() })
    // construct service with mappingService...
    // emit episode 892 of kitsu:12
    // assert tvdb_id=81797, season=21, episode=1
}
```

Replace any test that asserted `LOW_CONFIDENCE_FLAT_KITSU` rejection with positive scrobble for the now-mapped One Piece case.

- [ ] **Step 2: Update Top-Posters tests**

Update tests to use the fixture asset and assert the projected TVDB coordinate is used in poster URL generation.

- [ ] **Step 3: Verify KitsuRailFranchiseGrouper tests still pass with the new schema**

The grouper uses `AnimeIdMappingService.allSeriesRecordsSharingTvdb` and `recordForKitsuId` — these methods retain the same signatures, but now read the v2 asset. The existing tests use a hand-built `AnimeIdMapAsset` with `AnimeIdMapRecord`s, so the test fixture will need to be updated to set the new fields (though most can default to null).

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.integration.railpreview.KitsuRailFranchiseGrouperTest" 2>&1 | grep -E "PASSED|FAILED|tests|BUILD"
```

If failures occur, update the test's `AnimeIdMapRecord` constructions to include any newly-required fields (e.g. `mediaType = "series"` if the grouper requires it). Each test stays functionally the same.

- [ ] **Step 4: Run all consumer tests**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TrackingScrobble*" \
  --tests "com.nexio.tv.data.integration.posters.*" \
  --tests "com.nexio.tv.data.integration.railpreview.*" \
  2>&1 | grep -E "PASSED|FAILED|tests|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv/data/
git commit -m "test(anime): update consumers for projected One Piece coordinates and v2 schema"
```

---

## Task 20: Final verification

- [ ] **Step 1: Full test suite**

```bash
./gradlew :app:testUniversalDebugUnitTest :tools:anime-mapping-generator:test 2>&1 | grep -E "PASSED|FAILED|tests|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Full compile**

```bash
./gradlew :app:compileUniversalDebugKotlin :app:assembleUniversalDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Smoke check the generated asset**

```bash
ls -la app/src/main/assets/anime/
head -20 app/src/main/assets/anime/nexio-anime-map-v1.json
cat app/src/main/assets/anime/nexio-anime-map-provenance.json
```

Expected: both files exist; provenance JSON has source URLs and resolved commit SHAs from upstream.

- [ ] **Step 4: Final commit (if any cleanup occurred)**

```bash
git status
git diff
# If anything is staged, commit it. Otherwise this step is a no-op.
```

---

## Self-Review

**Spec coverage:**

| Spec section | Implemented in |
|---|---|
| Generator module structure | Tasks 1-12 |
| Asset schema (IdentityRecord, EpisodeMappingRecord, etc.) | Task 2 |
| Overlay format (4 modes) | Tasks 3, 9 |
| SeasonMarker wire encoding | Tasks 4, 15 |
| Fribb parsing | Task 5 |
| ScudLee XML parsing | Task 6 |
| Mapping-list expansion | Task 7 |
| Identity merge | Task 8 |
| Overlay application | Task 9 |
| Index building + asset emission + provenance | Task 10 |
| Upstream HTTP fetch + commit SHA capture | Tasks 11, 13 |
| Gradle integration (fetch + generate) | Task 13 |
| Replace anime-id-map.json wholesale | Tasks 13, 16 |
| Schema v2 + AnimeIdMappingService | Task 16 |
| Strict-mode resolver rewrite | Task 16 |
| AnimeSeasonPresentationCache retirement | Task 17 |
| New telemetry events | Task 18 |
| Consumer test updates | Task 19 |
| Test fixtures (MHA + One Piece + unmapped) | Tasks 5, 6, 7, 14 |

All spec sections have at least one implementing task. License/redistribution review is captured as a release-blocker (not a code task) per the spec.

**Placeholder scan:** No "TBD", "TODO", or "implement later" in the plan. Each step has explicit code or a concrete command.

**Type consistency:**
- `IdentityRecord` / `AnimeIdMapRecord` field names match across generator (Task 2) and runtime (Task 16).
- `AnimeRangeRule` / `RangeRule` parallel structures use the same field names.
- `targetProvider` is a String (`"TVDB"`/`"TMDB"`) in the wire format and is converted to `ProviderId` enum at projection time.
- `byTvdb` / `byTmdbTv` are consistently `Map<String, List<String>>` in both generator and runtime.
