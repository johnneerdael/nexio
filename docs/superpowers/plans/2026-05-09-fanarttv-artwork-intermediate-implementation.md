# Fanart.tv Artwork Intermediate Provider — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Fanart.tv as an INTERMEDIATE artwork provider that improves Default-mode posters/logos/backdrops for movies and TV without exposing a new dropdown entry, without inventing parallel persistence, and by reusing the standard artwork chain that Plan A protects as a non-goal.

**Architecture:** Fanart.tv is a new routing rank between PREMIUM and PRIMARY in `ArtworkRouter`. A new `FanartTvCandidateGenerator` runs alongside existing TMDB/TVDB primary mappers inside `MetadataArtworkDecisionResolver.resolveFields`. The Fanart.tv API call goes through the standard `IntegrationRuntime` with `CacheFirst(14d)` — the JSON body is persisted in `integration_cache` like every other provider response, single-flight is runtime-provided, backoff is provider-level. The candidate generator is stateless: gates → call lookup → run picker → emit candidates.

**Tech Stack:** Kotlin · Hilt · Coroutines · Retrofit2 + kotlinx-serialization · JUnit4 · MockK.

**Spec:** `docs/superpowers/specs/2026-05-09-fanarttv-artwork-intermediate-design.md`.

**Companion plans (do not duplicate work):**
- `docs/superpowers/plans/2026-05-09-resolved-display-authority.md` (Plan A) — display projection layer; declares the artwork fetch chain a non-goal it must preserve.
- `docs/superpowers/plans/2026-05-09-resolved-display-ui-consumption-migration.md` (Plan B) — UI consumer migration.

This plan operates strictly inside the chain Plan A protects: `ArtworkRouter` → `MetadataArtworkDecisionResolver` → `ArtworkAssetRepository` → `NexioArtworkFetcher`. It does not introduce a new persistence layer.

---

## File Structure

**New files:**

```
core/artwork/fanarttv/
  FanartTvAvailability.kt             # Available(apiKey) | Disabled(reason) sealed type
  FanartTvIdSelector.kt               # (mediaKind, ProviderIds) → call-id or null
  FanartTvImagePicker.kt              # pure picker: doc + (callType, ArtworkType) → URL | null
  FanartTvCandidateGenerator.kt       # gates → call lookup → run picker → emit candidates
  FanartTvApiShapes.kt                # const LOOKUP = "fanarttv.lookup"
  dto/FanartTvDocument.kt             # @Serializable DTO with 6 image arrays
  dto/FanartTvImage.kt                # @Serializable single-image DTO

data/integration/fanarttv/
  FanartTvApi.kt                      # Retrofit interface (movies + tv endpoints)
  FanartTvApiModule.kt                # Hilt module: provides FanartTvApi + bindings
  FanartTvLookupShape.kt              # IntegrationCallSpec wrapper, CacheFirst(14d), api_key redaction
  RuntimeFanartTvLookup.kt            # FanartTvLookup impl mapping HttpException → typed result
```

**Test fixtures (committed):**
```
app/src/test/resources/fixtures/fanarttv/
  fight-club-550.json
  breaking-bad-81189.json
```

**New tests:**
```
app/src/test/java/com/nexio/tv/core/artwork/fanarttv/
  FanartTvAvailabilityTest.kt
  FanartTvIdSelectorTest.kt
  FanartTvImagePickerTest.kt
  FanartTvCandidateGeneratorTest.kt

app/src/test/java/com/nexio/tv/data/integration/fanarttv/
  FanartTvLookupShapeTest.kt

app/src/test/java/com/nexio/tv/core/artwork/
  ArtworkRouterTest.kt                # extend existing file

app/src/test/java/com/nexio/tv/data/integration/metadata/
  MetadataArtworkDecisionResolverFanartTvTest.kt
```

**Modifications:**
- `app/build.gradle.kts` — add `FANARTTV_API_KEY` BuildConfig field.
- `app/src/main/java/com/nexio/tv/core/integration/IntegrationProvider.kt` — add `FANART_TV`.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt` — add `ArtworkSourceRole.INTERMEDIATE`.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt` — insert `RoutingRank.INTERMEDIATE(1)` and shift others.
- `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolver.kt` — inject and call generator before routing.
- `local.properties` (developer machine, gitignored) — add `fanarttv.api.key=...`.

**No new persistence:** no new Room entity, no new DAO, no new decision store. JSON cache lives in `integration_cache` via `IntegrationRuntime`. Routing decisions live in `ArtworkDecisionCache` (already exists). Image bytes live in the existing asset disk cache.

---

## Phase 1 — Build wiring & enum

### Task 1.1: Add FANART_TV to IntegrationProvider enum

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationProvider.kt`

- [ ] **Step 1: Edit the enum**

```kotlin
enum class IntegrationProvider {
    ADDON,
    TRAKT,
    SIMKL,
    TMDB,
    TVDB,
    KITSU,
    MDBLIST,
    OMDB,
    CUSTOM_IMDB,
    THEINTRODB,
    ANISKIP,
    ANIMESKIP,
    ARM,
    RPDB,
    TOP_POSTERS,
    FANART_TV,
    REAL_DEBRID,
    PREMIUMIZE,
    TORBOX,
    EASY_DEBRID,
    SHADOW_COLLECTOR,
    GITHUB,
    YOUTUBE_TRAILER,
    OPEN_SUBTITLES,
    SUBTITLE_SOURCE_DOWNLOAD,
    SUBTITLE_TRANSLATION,
    WYZIE_SUBTITLES
}
```

- [ ] **Step 2: Build to confirm nothing breaks**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationProvider.kt
git commit -m "feat(integration): add FANART_TV provider enum value"
```

---

### Task 1.2: Add BuildConfig field for the API key

**Files:**
- Modify: `app/build.gradle.kts` (next to existing `buildConfigField` calls around line 283)

- [ ] **Step 1: Add the field**

In `app/build.gradle.kts`, locate the existing `buildConfigField("String", "TRAILER_API_URL", ...)` line and add immediately after:

```kotlin
        buildConfigField(
            "String",
            "FANARTTV_API_KEY",
            "\"${localProperties.getProperty("fanarttv.api.key", "")}\""
        )
```

- [ ] **Step 2: Add the key to local.properties**

Append to `local.properties`:
```
fanarttv.api.key=07882f4309da827df559bb85b63793f9
```

- [ ] **Step 3: Build to verify the field is generated**

Run: `./gradlew :app:compileDebugKotlin`
Then: `grep -r "FANARTTV_API_KEY" app/build/generated/source/buildConfig/`
Expected: at least one match showing `public static final String FANARTTV_API_KEY = "...";`

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: expose FANARTTV_API_KEY from local.properties via BuildConfig"
```

---

## Phase 2 — DTO, fixtures, Retrofit interface

### Task 2.1: Commit Fight Club + Breaking Bad JSON fixtures

**Files:**
- Create: `app/src/test/resources/fixtures/fanarttv/fight-club-550.json`
- Create: `app/src/test/resources/fixtures/fanarttv/breaking-bad-81189.json`

- [ ] **Step 1: Write both fixture files**

Use the exact JSON bodies captured in the spec's "Image Selection Rules" section (movie sample = Fight Club tmdb_id 550, tv sample = Breaking Bad tvdb_id 81189). Save each as the file path above with the full JSON body verbatim.

- [ ] **Step 2: Confirm JSON parses**

Run: `python3 -c "import json; json.load(open('app/src/test/resources/fixtures/fanarttv/fight-club-550.json')); json.load(open('app/src/test/resources/fixtures/fanarttv/breaking-bad-81189.json')); print('ok')"`
Expected: `ok`

- [ ] **Step 3: Commit**

```bash
git add app/src/test/resources/fixtures/fanarttv/
git commit -m "test(fanarttv): add fight-club and breaking-bad fixtures from API"
```

---

### Task 2.2: Define DTO classes

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/dto/FanartTvImage.kt`
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/dto/FanartTvDocument.kt`

- [ ] **Step 1: Write `FanartTvImage.kt`**

```kotlin
package com.nexio.tv.core.artwork.fanarttv.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FanartTvImage(
    @SerialName("id") val id: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("lang") val lang: String? = null,
    @SerialName("likes") val likes: String? = null
)
```

- [ ] **Step 2: Write `FanartTvDocument.kt`**

```kotlin
package com.nexio.tv.core.artwork.fanarttv.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FanartTvDocument(
    @SerialName("name") val name: String? = null,
    @SerialName("tmdb_id") val tmdbId: String? = null,
    @SerialName("thetvdb_id") val tvdbId: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,

    @SerialName("hdmovielogo") val hdMovieLogo: List<FanartTvImage>? = null,
    @SerialName("moviebackground") val movieBackground: List<FanartTvImage>? = null,
    @SerialName("movieposter") val moviePoster: List<FanartTvImage>? = null,

    @SerialName("hdtvlogo") val hdTvLogo: List<FanartTvImage>? = null,
    @SerialName("showbackground") val showBackground: List<FanartTvImage>? = null,
    @SerialName("tvposter") val tvPoster: List<FanartTvImage>? = null
)
```

The Json instance must be configured with `ignoreUnknownKeys = true` so the unmodelled image arrays (`hdclearart`, `clearart`, `clearlogo`, `characterart`, `tvbanner`, `moviebanner`, `moviedisc`, `moviethumb`, `tvthumb`, `seasonbanner`, `seasonposter`, `seasonthumb`, `movielogo`) are ignored. Configure this on the FanartTvApiModule's Json instance in Task 5.2.

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/dto/
git commit -m "feat(fanarttv): add DTOs for the six consumed image arrays"
```

---

### Task 2.3: Retrofit interface

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApi.kt`

- [ ] **Step 1: Write the interface**

```kotlin
package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface FanartTvApi {
    @GET("v3.2/movies/{tmdbId}")
    suspend fun getMovie(
        @Path("tmdbId") tmdbId: String,
        @Query("api_key") apiKey: String
    ): FanartTvDocument

    @GET("v3.2/tv/{tvdbId}")
    suspend fun getTv(
        @Path("tvdbId") tvdbId: String,
        @Query("api_key") apiKey: String
    ): FanartTvDocument
}
```

Base URL `https://webservice.fanart.tv/` is wired in the Hilt module in Task 5.2.

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApi.kt
git commit -m "feat(fanarttv): add Retrofit interface for v3.2 movie and tv lookups"
```

---

## Phase 3 — Pure helper units (TDD)

### Task 3.1: FanartTvAvailability

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvAvailability.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvAvailabilityTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanartTvAvailabilityTest {
    @Test
    fun `available when key is non-blank`() {
        val result = FanartTvAvailability.from("abc123")
        assertTrue(result is FanartTvAvailability.Available)
        assertEquals("abc123", (result as FanartTvAvailability.Available).apiKey)
    }

    @Test
    fun `disabled when key is empty`() {
        assertEquals(
            FanartTvAvailability.Disabled("no_build_config_key"),
            FanartTvAvailability.from("")
        )
    }

    @Test
    fun `disabled when key is blank whitespace`() {
        assertEquals(
            FanartTvAvailability.Disabled("no_build_config_key"),
            FanartTvAvailability.from("   ")
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvAvailabilityTest"`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implementation**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

sealed interface FanartTvAvailability {
    data class Available(val apiKey: String) : FanartTvAvailability
    data class Disabled(val reason: String) : FanartTvAvailability

    companion object {
        fun from(rawKey: String): FanartTvAvailability =
            if (rawKey.isBlank()) Disabled("no_build_config_key")
            else Available(rawKey.trim())
    }
}
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvAvailabilityTest"`
Expected: PASS (3/3)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvAvailability.kt \
        app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvAvailabilityTest.kt
git commit -m "feat(fanarttv): add availability sealed type with build-key gate"
```

---

### Task 3.2: FanartTvIdSelector

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvIdSelector.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvIdSelectorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FanartTvIdSelectorTest {
    private val selector = FanartTvIdSelector()

    @Test
    fun `movie with tmdb id returns tmdb call id`() {
        assertEquals(
            FanartTvCallId(FanartTvCallId.Type.MOVIE, "550"),
            selector.select(MetadataMediaKind.MOVIE, ProviderIds(tmdb = "550"))
        )
    }

    @Test
    fun `movie without tmdb id returns null`() {
        assertNull(selector.select(MetadataMediaKind.MOVIE, ProviderIds(imdb = "tt0137523")))
    }

    @Test
    fun `series with tvdb id returns tv call id`() {
        assertEquals(
            FanartTvCallId(FanartTvCallId.Type.TV, "81189"),
            selector.select(MetadataMediaKind.SERIES, ProviderIds(tvdb = "81189"))
        )
    }

    @Test
    fun `series without tvdb id returns null`() {
        assertNull(selector.select(MetadataMediaKind.SERIES, ProviderIds(tmdb = "1396")))
    }

    @Test
    fun `anime returns null even with both ids`() {
        assertNull(
            selector.select(MetadataMediaKind.ANIME, ProviderIds(tmdb = "1", tvdb = "2"))
        )
    }

    @Test
    fun `unknown returns null`() {
        assertNull(
            selector.select(MetadataMediaKind.UNKNOWN, ProviderIds(tmdb = "1", tvdb = "2"))
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvIdSelectorTest"`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implementation**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds

data class FanartTvCallId(val type: Type, val value: String) {
    enum class Type { MOVIE, TV }
}

class FanartTvIdSelector {
    fun select(mediaKind: MetadataMediaKind, providerIds: ProviderIds): FanartTvCallId? =
        when (mediaKind) {
            MetadataMediaKind.MOVIE ->
                providerIds.tmdb?.takeIf { it.isNotBlank() }
                    ?.let { FanartTvCallId(FanartTvCallId.Type.MOVIE, it) }
            MetadataMediaKind.SERIES ->
                providerIds.tvdb?.takeIf { it.isNotBlank() }
                    ?.let { FanartTvCallId(FanartTvCallId.Type.TV, it) }
            MetadataMediaKind.ANIME, MetadataMediaKind.UNKNOWN -> null
        }
}
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvIdSelectorTest"`
Expected: PASS (6/6)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvIdSelector.kt \
        app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvIdSelectorTest.kt
git commit -m "feat(fanarttv): add id selector mapping mediaKind+ids to call id"
```

---

### Task 3.3: FanartTvImagePicker

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvImagePicker.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvImagePickerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FanartTvImagePickerTest {
    private val picker = FanartTvImagePicker()

    @Test
    fun `movie logo picks highest-likes en, ignores higher non-en`() {
        val doc = FanartTvDocument(
            hdMovieLogo = listOf(
                FanartTvImage(id = "1", url = "es.png", lang = "es", likes = "20"),
                FanartTvImage(id = "2", url = "en-hi.png", lang = "en", likes = "8"),
                FanartTvImage(id = "3", url = "en-low.png", lang = "en", likes = "5")
            )
        )
        assertEquals("en-hi.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test
    fun `tv logo picks highest-likes en`() {
        val doc = FanartTvDocument(
            hdTvLogo = listOf(
                FanartTvImage(id = "1", url = "best.png", lang = "en", likes = "24"),
                FanartTvImage(id = "2", url = "ok.png", lang = "en", likes = "10")
            )
        )
        assertEquals("best.png", picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.LOGO))
    }

    @Test
    fun `movie backdrop picks highest-likes regardless of lang`() {
        val doc = FanartTvDocument(
            movieBackground = listOf(
                FanartTvImage(id = "1", url = "a.jpg", lang = "", likes = "5"),
                FanartTvImage(id = "2", url = "b.jpg", lang = "", likes = "3")
            )
        )
        assertEquals("a.jpg", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.BACKDROP))
    }

    @Test
    fun `tv backdrop picks highest-likes regardless of lang`() {
        val doc = FanartTvDocument(
            showBackground = listOf(
                FanartTvImage(id = "1", url = "x.jpg", lang = "", likes = "12"),
                FanartTvImage(id = "2", url = "y.jpg", lang = "", likes = "10")
            )
        )
        assertEquals("x.jpg", picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.BACKDROP))
    }

    @Test
    fun `movie poster picks highest-likes en`() {
        val doc = FanartTvDocument(
            moviePoster = listOf(
                FanartTvImage(id = "1", url = "ru.jpg", lang = "ru", likes = "100"),
                FanartTvImage(id = "2", url = "en15.jpg", lang = "en", likes = "15"),
                FanartTvImage(id = "3", url = "en13.jpg", lang = "en", likes = "13")
            )
        )
        assertEquals("en15.jpg", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.POSTER))
    }

    @Test
    fun `tv poster picks highest-likes en`() {
        val doc = FanartTvDocument(
            tvPoster = listOf(
                FanartTvImage(id = "1", url = "en14.jpg", lang = "en", likes = "14"),
                FanartTvImage(id = "2", url = "en6.jpg", lang = "en", likes = "6")
            )
        )
        assertEquals("en14.jpg", picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.POSTER))
    }

    @Test
    fun `null when no en variant for poster`() {
        val doc = FanartTvDocument(
            moviePoster = listOf(
                FanartTvImage(id = "1", url = "ru.jpg", lang = "ru", likes = "5")
            )
        )
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.POSTER))
    }

    @Test
    fun `null when no en variant for logo`() {
        val doc = FanartTvDocument(
            hdMovieLogo = listOf(FanartTvImage(id = "1", url = "ru.png", lang = "ru", likes = "5"))
        )
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test
    fun `null when arrays missing`() {
        val doc = FanartTvDocument()
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.BACKDROP))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.POSTER))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.LOGO))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.BACKDROP))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.POSTER))
    }

    @Test
    fun `null when array empty`() {
        val doc = FanartTvDocument(hdMovieLogo = emptyList())
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test
    fun `tie-break by ascending id`() {
        val doc = FanartTvDocument(
            hdMovieLogo = listOf(
                FanartTvImage(id = "200", url = "later.png", lang = "en", likes = "8"),
                FanartTvImage(id = "100", url = "earlier.png", lang = "en", likes = "8")
            )
        )
        assertEquals("earlier.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test
    fun `entries with malformed likes are treated as zero`() {
        val doc = FanartTvDocument(
            hdMovieLogo = listOf(
                FanartTvImage(id = "1", url = "good.png", lang = "en", likes = "1"),
                FanartTvImage(id = "2", url = "junk.png", lang = "en", likes = "abc")
            )
        )
        assertEquals("good.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test
    fun `entries with null url are skipped`() {
        val doc = FanartTvDocument(
            hdMovieLogo = listOf(
                FanartTvImage(id = "1", url = null, lang = "en", likes = "100"),
                FanartTvImage(id = "2", url = "real.png", lang = "en", likes = "1")
            )
        )
        assertEquals("real.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test
    fun `thumbnail returns null (unsupported)`() {
        val doc = FanartTvDocument()
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.THUMBNAIL))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.THUMBNAIL))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvImagePickerTest"`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implementation**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvImage

class FanartTvImagePicker {

    fun pickFor(
        doc: FanartTvDocument,
        callType: FanartTvCallId.Type,
        artworkType: ArtworkType
    ): String? = when (callType) {
        FanartTvCallId.Type.MOVIE -> when (artworkType) {
            ArtworkType.LOGO -> pickEnglishHighest(doc.hdMovieLogo)
            ArtworkType.BACKDROP -> pickHighestAnyLang(doc.movieBackground)
            ArtworkType.POSTER -> pickEnglishHighest(doc.moviePoster)
            ArtworkType.THUMBNAIL -> null
        }
        FanartTvCallId.Type.TV -> when (artworkType) {
            ArtworkType.LOGO -> pickEnglishHighest(doc.hdTvLogo)
            ArtworkType.BACKDROP -> pickHighestAnyLang(doc.showBackground)
            ArtworkType.POSTER -> pickEnglishHighest(doc.tvPoster)
            ArtworkType.THUMBNAIL -> null
        }
    }

    private fun pickEnglishHighest(images: List<FanartTvImage>?): String? =
        images
            ?.asSequence()
            ?.filter { it.url?.isNotBlank() == true }
            ?.filter { it.lang?.equals("en", ignoreCase = true) == true }
            ?.sortedWith(
                compareByDescending<FanartTvImage> { it.likesAsInt() }
                    .thenBy { it.idAsLongOrMax() }
            )
            ?.firstOrNull()
            ?.url

    private fun pickHighestAnyLang(images: List<FanartTvImage>?): String? =
        images
            ?.asSequence()
            ?.filter { it.url?.isNotBlank() == true }
            ?.sortedWith(
                compareByDescending<FanartTvImage> { it.likesAsInt() }
                    .thenBy { it.idAsLongOrMax() }
            )
            ?.firstOrNull()
            ?.url

    private fun FanartTvImage.likesAsInt(): Int = likes?.toIntOrNull() ?: 0
    private fun FanartTvImage.idAsLongOrMax(): Long = id?.toLongOrNull() ?: Long.MAX_VALUE
}
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvImagePickerTest"`
Expected: PASS (all)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvImagePicker.kt \
        app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvImagePickerTest.kt
git commit -m "feat(fanarttv): add image picker with highest-likes + lang gating"
```

---

### Task 3.4: Picker fixture-driven tests

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvImagePickerTest.kt`

- [ ] **Step 1: Add fixture loading helper + tests**

Append to the existing `FanartTvImagePickerTest`:

```kotlin
    @Test
    fun `fight club fixture picks expected urls`() {
        val doc = loadFixture("fight-club-550.json")
        assertEquals(
            "https://assets.fanart.tv/fanart/fight-club-504c0530d5f93.png",
            picker.pickFor(doc, FanartTvCallId.Type.MOVIE, com.nexio.tv.core.artwork.ArtworkType.LOGO)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/fight-club-55e2393686745.jpg",
            picker.pickFor(doc, FanartTvCallId.Type.MOVIE, com.nexio.tv.core.artwork.ArtworkType.BACKDROP)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/fight-club-522a5477c7bd3.jpg",
            picker.pickFor(doc, FanartTvCallId.Type.MOVIE, com.nexio.tv.core.artwork.ArtworkType.POSTER)
        )
    }

    @Test
    fun `breaking bad fixture picks expected urls`() {
        val doc = loadFixture("breaking-bad-81189.json")
        assertEquals(
            "https://assets.fanart.tv/fanart/breaking-bad-503d6f03d4bfe.png",
            picker.pickFor(doc, FanartTvCallId.Type.TV, com.nexio.tv.core.artwork.ArtworkType.LOGO)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/breaking-bad-4fcb7b24428ba.jpg",
            picker.pickFor(doc, FanartTvCallId.Type.TV, com.nexio.tv.core.artwork.ArtworkType.BACKDROP)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/breaking-bad-5427fc5ebded7.jpg",
            picker.pickFor(doc, FanartTvCallId.Type.TV, com.nexio.tv.core.artwork.ArtworkType.POSTER)
        )
    }

    private fun loadFixture(name: String): FanartTvDocument {
        val text = checkNotNull(this::class.java.getResource("/fixtures/fanarttv/$name")) {
            "Fixture $name not found on test classpath"
        }.readText()
        return kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
        }.decodeFromString(FanartTvDocument.serializer(), text)
    }
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvImagePickerTest"`
Expected: PASS (all + 2 fixture tests)

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvImagePickerTest.kt
git commit -m "test(fanarttv): pin picker outputs to real fight-club and breaking-bad fixtures"
```

---

## Phase 4 — Router rank shift

### Task 4.1: Add ArtworkSourceRole.INTERMEDIATE

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt:47-57`

- [ ] **Step 1: Edit the enum**

Replace:
```kotlin
enum class ArtworkSourceRole {
    PREMIUM,
    PRIMARY,
    CURRENT_PREVIEW,
    OTHER_PREVIEW,
    RAIL_PREVIEW,
    ADDON_PREVIEW,
    FALLBACK,
    PLACEHOLDER,
    LEGACY_STRING_COMPAT
}
```
with:
```kotlin
enum class ArtworkSourceRole {
    PREMIUM,
    INTERMEDIATE,
    PRIMARY,
    CURRENT_PREVIEW,
    OTHER_PREVIEW,
    RAIL_PREVIEW,
    ADDON_PREVIEW,
    FALLBACK,
    PLACEHOLDER,
    LEGACY_STRING_COMPAT
}
```

- [ ] **Step 2: Build to confirm exhaustive `when` callers compile**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL. If any non-exhaustive `when` warning fires, add an `INTERMEDIATE` branch that mirrors the `PRIMARY` branch (intermediate is semantically a routing-precedence sibling of PRIMARY at the consumer level).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt
git commit -m "feat(artwork): add INTERMEDIATE source role between PREMIUM and PRIMARY"
```

---

### Task 4.2: Insert INTERMEDIATE rank into ArtworkRouter and extend tests

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkRouterTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `ArtworkRouterTest`:

```kotlin
    @Test
    fun `intermediate beats primary when both present`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
                    role = ArtworkSourceRole.INTERMEDIATE,
                    priority = 15
                )
            ),
            policy = policy(ArtworkProviderSettings())
        )
        assertEquals("FANART_TV", decision.selectedCandidate.provider?.key)
    }

    @Test
    fun `premium beats intermediate when both present and premium supported`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
                    role = ArtworkSourceRole.INTERMEDIATE,
                    priority = 15
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10
                )
            ),
            policy = policy(topPostersPosterSettings())
        )
        assertEquals("TOP_POSTERS", decision.selectedCandidate.provider?.key)
    }

    @Test
    fun `intermediate falls through to primary when intermediate absent`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20
                )
            ),
            policy = policy(ArtworkProviderSettings())
        )
        assertEquals("TMDB", decision.selectedCandidate.provider?.key)
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.ArtworkRouterTest"`
Expected: 3 new tests fail.

- [ ] **Step 3: Modify the router**

In `app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt`:

(a) Update `RoutingRank`:

```kotlin
    private enum class RoutingRank(val precedence: Int) {
        PREMIUM(0),
        INTERMEDIATE(1),
        PRIMARY(2),
        CURRENT_PREVIEW(3),
        OTHER_PREVIEW(4),
        FALLBACK(5),
        PLACEHOLDER(6)
    }
```

(b) Update `routingRank()`:

```kotlin
    private fun ArtworkCandidate.routingRank(context: SelectionContext): RoutingRank =
        when {
            sourceRole == ArtworkSourceRole.PREMIUM &&
                isActiveSupportedPremium(context) -> RoutingRank.PREMIUM
            sourceRole == ArtworkSourceRole.INTERMEDIATE -> RoutingRank.INTERMEDIATE
            sourceRole == ArtworkSourceRole.PRIMARY -> RoutingRank.PRIMARY
            sourceRole == ArtworkSourceRole.CURRENT_PREVIEW -> RoutingRank.CURRENT_PREVIEW
            sourceRole == ArtworkSourceRole.OTHER_PREVIEW ||
                sourceRole == ArtworkSourceRole.RAIL_PREVIEW ||
                sourceRole == ArtworkSourceRole.ADDON_PREVIEW -> RoutingRank.OTHER_PREVIEW
            sourceRole == ArtworkSourceRole.PLACEHOLDER ||
                provider == ArtworkProviderId.Placeholder ||
                source is ArtworkSource.Placeholder -> RoutingRank.PLACEHOLDER
            else -> RoutingRank.FALLBACK
        }
```

(c) Update `rejectionReasonForSelected()`:

```kotlin
    private fun ArtworkCandidate.rejectionReasonForSelected(selectedRank: RoutingRank): String =
        when (selectedRank) {
            RoutingRank.PREMIUM -> "premium_artwork_provider_precedence"
            RoutingRank.INTERMEDIATE -> "intermediate_artwork_provider_precedence"
            RoutingRank.PRIMARY -> "primary_provider_artwork_precedence"
            RoutingRank.CURRENT_PREVIEW -> "current_preview_artwork_precedence"
            RoutingRank.OTHER_PREVIEW -> "other_preview_artwork_precedence"
            RoutingRank.FALLBACK -> "fallback_artwork_precedence"
            RoutingRank.PLACEHOLDER -> "placeholder_artwork_precedence"
        }
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.ArtworkRouterTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt \
        app/src/test/java/com/nexio/tv/core/artwork/ArtworkRouterTest.kt
git commit -m "feat(artwork-router): rank INTERMEDIATE between PREMIUM and PRIMARY"
```

---

## Phase 5 — Runtime wiring

### Task 5.1: Shape constant

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvApiShapes.kt`

- [ ] **Step 1: Write**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

object FanartTvApiShapes {
    const val LOOKUP = "fanarttv.lookup"
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvApiShapes.kt
git commit -m "feat(fanarttv): declare LOOKUP runtime shape constant"
```

---

### Task 5.2: Hilt module providing FanartTvApi

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApiModule.kt`

- [ ] **Step 1: Inspect an existing analogous module to copy the canonical Retrofit/Hilt pattern**

Run: `grep -rn "@Provides\|baseUrl\|kotlinxSerializationConverter\|ConverterFactory" app/src/main/java/com/nexio/tv/data/integration/tmdb/ | head -20`

Note the project's exact OkHttp+Retrofit+Json factory pattern. Mirror it for Fanart.tv.

- [ ] **Step 2: Write the module**

Skeleton (adapt to the project's exact converter / OkHttp setup observed in Step 1):

```kotlin
package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.FanartTvLookup
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType

@Module
@InstallIn(SingletonComponent::class)
abstract class FanartTvApiModule {

    @Binds
    @Singleton
    abstract fun bindFanartTvLookup(impl: RuntimeFanartTvLookup): FanartTvLookup

    companion object {
        @Provides
        @Singleton
        fun provideFanartTvJson(): Json = Json { ignoreUnknownKeys = true }

        @Provides
        @Singleton
        fun provideFanartTvApi(
            okHttpClient: OkHttpClient,
            json: Json
        ): FanartTvApi {
            val contentType = "application/json".toMediaType()
            return Retrofit.Builder()
                .baseUrl("https://webservice.fanart.tv/")
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
                .create(FanartTvApi::class.java)
        }
    }
}
```

If the project uses a per-provider `OkHttpClient` (e.g., with logging interceptor or backoff interceptor), inject the same one TMDB or TVDB uses.

- [ ] **Step 3: Build to verify Hilt graph compiles**

Run: `./gradlew :app:kspDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (the `RuntimeFanartTvLookup` reference will fail to resolve until Task 5.4 — temporarily comment out the `@Binds` line if necessary; uncomment in Task 5.4 Step 4).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApiModule.kt
git commit -m "feat(fanarttv): provide FanartTvApi via Hilt with fanart.tv base url"
```

---

### Task 5.3: FanartTvLookupShape — runtime call wrapper with CacheFirst(14d) + redaction

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvLookupShape.kt`
- Create: `app/src/test/java/com/nexio/tv/data/integration/fanarttv/FanartTvLookupShapeTest.kt`

- [ ] **Step 1: Inspect an existing shape that uses CacheFirst + query-param redaction**

Run: `grep -rn "CacheFirst\|IntegrationCallSpec\|api_key\|apikey.*redact" app/src/main/java/com/nexio/tv/data/integration/posters/ app/src/main/java/com/nexio/tv/data/integration/tmdb/ | head -30`

Mirror the project's idiomatic shape: a class that constructs an `IntegrationCallSpec` with a redaction declaration on the `api_key` query parameter and a `CacheFirst(ttlMs)` policy, then executes through `IntegrationRuntime`.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.FanartTvCallId
import org.junit.Assert.assertFalse
import org.junit.Test

class FanartTvLookupShapeTest {
    @Test
    fun `redacted url for trace contains no api_key value`() {
        val spec = FanartTvLookupShape.specFor(
            callId = FanartTvCallId(FanartTvCallId.Type.MOVIE, "550"),
            apiKey = "07882f4309da827df559bb85b63793f9"
        )
        val redacted = spec.redactedUrlForTrace
        assertFalse(
            "redacted form must not contain raw api key, was: $redacted",
            redacted.contains("07882f4309da827df559bb85b63793f9")
        )
    }

    @Test
    fun `cache policy is CacheFirst with 14d ttl`() {
        val spec = FanartTvLookupShape.specFor(
            callId = FanartTvCallId(FanartTvCallId.Type.MOVIE, "550"),
            apiKey = "k"
        )
        val expectedTtl = 14L * 24 * 60 * 60 * 1000
        // Adapt the property name/path to whatever IntegrationCallSpec exposes for its
        // cache policy (e.g., spec.cachePolicy as IntegrationCachePolicy.CacheFirst).
        val policy = spec.cachePolicy
        assertCacheFirstWithTtl(policy, expectedTtl)
    }

    private fun assertCacheFirstWithTtl(policy: Any, expectedTtlMs: Long) {
        // Inline assertion — replace with actual property checks once the IntegrationCallSpec
        // shape is confirmed in Step 1. Goal: assert CacheFirst.ttlMs == expectedTtlMs.
        check(policy.toString().contains("CacheFirst")) { "expected CacheFirst, got $policy" }
        check(policy.toString().contains(expectedTtlMs.toString())) {
            "expected ttlMs=$expectedTtlMs in policy, got $policy"
        }
    }
}
```

- [ ] **Step 3: Run test to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.fanarttv.FanartTvLookupShapeTest"`
Expected: COMPILATION FAILURE.

- [ ] **Step 4: Implement FanartTvLookupShape**

Build the shape class using the same `IntegrationCallSpec` / `IntegrationStreamSpec` builders used by the analog from Step 1. Required behavior:

- `specFor(callId, apiKey)` constructs an `IntegrationCallSpec` with:
  - `provider = IntegrationProvider.FANART_TV`
  - `apiShapeId = FanartTvApiShapes.LOOKUP`
  - URL: `https://webservice.fanart.tv/v3.2/movies/{id}` or `/v3.2/tv/{id}` based on `callId.type`
  - `api_key` declared as a redacted query parameter (use the same redaction-policy mechanism the analog from Step 1 uses for its own key parameter)
  - `cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 14L * 24 * 60 * 60 * 1000)`
  - `workClass = IntegrationWorkClass.USER_VISIBLE`
- `suspend fun fetch(callId, apiKey): FanartTvDocument` runs the spec through `IntegrationRuntime` and returns the parsed body — or rethrows the runtime's typed failure (e.g., `HttpException`).

`@Singleton class FanartTvLookupShape @Inject constructor(private val runtime: IntegrationRuntime, private val api: FanartTvApi)`.

- [ ] **Step 5: Run test to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.fanarttv.FanartTvLookupShapeTest"`
Expected: PASS (2/2)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvLookupShape.kt \
        app/src/test/java/com/nexio/tv/data/integration/fanarttv/FanartTvLookupShapeTest.kt
git commit -m "feat(fanarttv): wire lookup through IntegrationRuntime CacheFirst(14d) with api_key redaction"
```

---

### Task 5.4: RuntimeFanartTvLookup adapter

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvLookup.kt` (the interface + result types)
- Create: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/RuntimeFanartTvLookup.kt`

- [ ] **Step 1: Write the interface + result types**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument

interface FanartTvLookup {
    suspend fun fetch(callId: FanartTvCallId, apiKey: String): FanartTvLookupResult
}

sealed interface FanartTvLookupResult {
    data class Success(val document: FanartTvDocument) : FanartTvLookupResult
    data object NotFound : FanartTvLookupResult     // 404
    data object AuthFailed : FanartTvLookupResult   // 401/403
    data object Transient : FanartTvLookupResult    // 429/5xx/network error
}
```

- [ ] **Step 2: Write the impl**

```kotlin
package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.FanartTvCallId
import com.nexio.tv.core.artwork.fanarttv.FanartTvLookup
import com.nexio.tv.core.artwork.fanarttv.FanartTvLookupResult
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException
import java.io.IOException

@Singleton
class RuntimeFanartTvLookup @Inject constructor(
    private val shape: FanartTvLookupShape
) : FanartTvLookup {
    override suspend fun fetch(callId: FanartTvCallId, apiKey: String): FanartTvLookupResult =
        try {
            FanartTvLookupResult.Success(shape.fetch(callId, apiKey))
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> FanartTvLookupResult.NotFound
                401, 403 -> FanartTvLookupResult.AuthFailed
                else -> FanartTvLookupResult.Transient
            }
        } catch (_: IOException) {
            FanartTvLookupResult.Transient
        }
}
```

- [ ] **Step 3: Re-enable the `@Binds` in `FanartTvApiModule.kt`** (if it was commented out in Task 5.2 Step 3).

- [ ] **Step 4: Build to verify**

Run: `./gradlew :app:kspDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvLookup.kt \
        app/src/main/java/com/nexio/tv/data/integration/fanarttv/RuntimeFanartTvLookup.kt \
        app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApiModule.kt
git commit -m "feat(fanarttv): add FanartTvLookup interface and runtime adapter"
```

---

## Phase 6 — Candidate generator

### Task 6.1: Generator — gates and pure flow

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGenerator.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvImage
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanartTvCandidateGeneratorTest {

    private val lookup = mockk<FanartTvLookup>(relaxed = true)
    private fun gen(availability: FanartTvAvailability) = FanartTvCandidateGenerator(
        availabilityProvider = { availability },
        idSelector = FanartTvIdSelector(),
        picker = FanartTvImagePicker(),
        lookup = lookup
    )

    private val ownerKey = ArtworkOwnerKey.CanonicalContent("movie:550")
    private val movieIds = ProviderIds(tmdb = "550")
    private val seriesIds = ProviderIds(tvdb = "81189")

    @Test
    fun `anime emits zero and makes zero lookup calls`() = runTest {
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey = ownerKey,
            canonicalContentId = "anime:1",
            mediaKind = MetadataMediaKind.ANIME,
            providerIds = ProviderIds(tvdb = "1", tmdb = "1"),
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP)
        )
        assertTrue(out.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }

    @Test
    fun `disabled key emits zero and makes zero lookup calls`() = runTest {
        val out = gen(FanartTvAvailability.Disabled("no_build_config_key")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER)
        )
        assertTrue(out.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }

    @Test
    fun `missing usable id emits zero and makes zero lookup calls`() = runTest {
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:noid", MetadataMediaKind.MOVIE, ProviderIds(imdb = "tt0137523"),
            requestedTypes = setOf(ArtworkType.POSTER)
        )
        assertTrue(out.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }

    @Test
    fun `success path emits candidates for non-null picker outputs`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Success(
            FanartTvDocument(
                hdMovieLogo = listOf(FanartTvImage(id = "1", url = "logo.png", lang = "en", likes = "8")),
                movieBackground = listOf(FanartTvImage(id = "2", url = "back.jpg", lang = "", likes = "5")),
                moviePoster = listOf(FanartTvImage(id = "3", url = "poster.jpg", lang = "en", likes = "15"))
            )
        )
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP)
        )
        coVerify(exactly = 1) { lookup.fetch(any(), any()) }
        assertEquals(3, out.size)
        assertEquals(
            setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            out.map { it.imageType }.toSet()
        )
        assertTrue(out.all { it.sourceRole == ArtworkSourceRole.INTERMEDIATE })
        assertTrue(
            out.all { it.provider == ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV) }
        )
    }

    @Test
    fun `partial picker outputs emit only present types`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Success(
            FanartTvDocument(
                moviePoster = listOf(FanartTvImage(id = "1", url = "poster.jpg", lang = "en", likes = "1"))
                // no logo or backdrop
            )
        )
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP)
        )
        assertEquals(1, out.size)
        assertEquals(ArtworkType.POSTER, out.single().imageType)
    }

    @Test
    fun `404 emits zero candidates`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.NotFound
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER)
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `auth failure emits zero candidates`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.AuthFailed
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER)
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `transient failure emits zero candidates`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Transient
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER)
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `series success path uses tv arrays`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Success(
            FanartTvDocument(
                hdTvLogo = listOf(FanartTvImage(id = "1", url = "tvlogo.png", lang = "en", likes = "24")),
                showBackground = listOf(FanartTvImage(id = "2", url = "showbg.jpg", lang = "", likes = "12")),
                tvPoster = listOf(FanartTvImage(id = "3", url = "tvposter.jpg", lang = "en", likes = "14"))
            )
        )
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey = ArtworkOwnerKey.CanonicalContent("series:81189"),
            canonicalContentId = "series:81189",
            mediaKind = MetadataMediaKind.SERIES,
            providerIds = seriesIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP)
        )
        assertEquals(3, out.size)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implementation**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds
import java.security.MessageDigest
import javax.inject.Inject

class FanartTvCandidateGenerator @Inject constructor(
    private val availabilityProvider: () -> FanartTvAvailability,
    private val idSelector: FanartTvIdSelector,
    private val picker: FanartTvImagePicker,
    private val lookup: FanartTvLookup
) {
    suspend fun generate(
        ownerKey: ArtworkOwnerKey,
        canonicalContentId: String?,
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds,
        requestedTypes: Set<ArtworkType>
    ): List<ArtworkCandidate> {
        if (mediaKind == MetadataMediaKind.ANIME) return emptyList()
        val availability = availabilityProvider() as? FanartTvAvailability.Available
            ?: return emptyList()
        val callId = idSelector.select(mediaKind, providerIds) ?: return emptyList()

        val typesToTry = requestedTypes.filter { it != ArtworkType.THUMBNAIL }
        if (typesToTry.isEmpty()) return emptyList()

        val result = lookup.fetch(callId, availability.apiKey)
        val doc = (result as? FanartTvLookupResult.Success)?.document ?: return emptyList()

        return typesToTry.mapNotNull { type ->
            picker.pickFor(doc, callId.type, type)?.let { url ->
                buildCandidate(ownerKey, canonicalContentId, mediaKind, providerIds, type, url)
            }
        }
    }

    private fun buildCandidate(
        ownerKey: ArtworkOwnerKey,
        canonicalContentId: String?,
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds,
        type: ArtworkType,
        url: String
    ): ArtworkCandidate {
        val sensitive = SensitiveArtworkUrl.of(url)
        val source = ArtworkSource.RemoteUrl.of(
            rawUrl = sensitive,
            normalizedUrlHash = url.sha256()
        )
        return ArtworkCandidate(
            ownerKey = ownerKey,
            canonicalContentId = canonicalContentId,
            providerIds = providerIds,
            mediaKind = mediaKind,
            imageType = type,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            sourceRole = ArtworkSourceRole.INTERMEDIATE,
            source = source,
            priority = INTERMEDIATE_PRIORITY,
            requiresRuntimeFetch = true,
            imageLanguage = "en"
        )
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    companion object {
        const val INTERMEDIATE_PRIORITY = 15
    }
}
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: PASS (9/9)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGenerator.kt \
        app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt
git commit -m "feat(fanarttv): stateless candidate generator (gates → lookup → pick → emit)"
```

---

### Task 6.2: Hilt provider for the generator

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApiModule.kt`

- [ ] **Step 1: Add a `@Provides` for the generator**

Inside the `companion object` block of `FanartTvApiModule`:

```kotlin
        @Provides
        @Singleton
        fun provideFanartTvCandidateGenerator(
            lookup: FanartTvLookup
        ): com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGenerator =
            com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGenerator(
                availabilityProvider = {
                    com.nexio.tv.core.artwork.fanarttv.FanartTvAvailability.from(
                        com.nexio.tv.BuildConfig.FANARTTV_API_KEY
                    )
                },
                idSelector = com.nexio.tv.core.artwork.fanarttv.FanartTvIdSelector(),
                picker = com.nexio.tv.core.artwork.fanarttv.FanartTvImagePicker(),
                lookup = lookup
            )
```

- [ ] **Step 2: Build to verify Hilt graph compiles**

Run: `./gradlew :app:kspDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApiModule.kt
git commit -m "feat(fanarttv): provide candidate generator via Hilt"
```

---

## Phase 7 — Wire into the resolver

### Task 7.1: Inject and call the generator from MetadataArtworkDecisionResolver

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolver.kt`
- Create: `app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolverFanartTvTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGenerator
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MetadataArtworkDecisionResolverFanartTvTest {
    @Test
    fun `resolver invokes Fanart generator once per ownerKey with the union of imageTypes`() = runTest {
        val generator = mockk<FanartTvCandidateGenerator>()
        coEvery { generator.generate(any(), any(), any(), any(), any()) } returns emptyList()

        // Construct MetadataArtworkDecisionResolver per the project's existing
        // test pattern (see other tests in this dir) injecting `generator` plus
        // the existing fakes/mocks for ArtworkRouter, ArtworkDecisionCache,
        // ArtworkRemoteSourceStore, and ArtworkProviderSettingsSource.

        // Call resolveFields with two TVDB primary candidates for the same owner —
        // one POSTER, one BACKDROP — and assert the generator is called exactly
        // once with requestedTypes = setOf(POSTER, BACKDROP).

        // Adapt this scaffold to whatever helper infra exists for resolver tests.
        // The assertion is: coVerify(exactly = 1) { generator.generate(any(), any(), any(), any(), match { it == setOf(ArtworkType.POSTER, ArtworkType.BACKDROP) }) }
    }

    private fun primary(type: ArtworkType, url: String, ownerKey: ArtworkOwnerKey): ArtworkCandidate =
        ArtworkCandidate(
            ownerKey = ownerKey,
            canonicalContentId = "series:81189",
            providerIds = ProviderIds(tvdb = "81189"),
            mediaKind = MetadataMediaKind.SERIES,
            imageType = type,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB),
            sourceRole = ArtworkSourceRole.PRIMARY,
            source = ArtworkSource.RemoteUrl.of(SensitiveArtworkUrl.of(url), "h$type"),
            priority = 20,
            requiresRuntimeFetch = true,
            imageLanguage = "en"
        )
}
```

(Replace the comment-driven scaffold with the project's actual resolver-test pattern. The contract being asserted is: one `generator.generate(...)` call per distinct `ownerKey`, with `requestedTypes` = union of image types in the input candidates for that owner, excluding `THUMBNAIL`.)

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.metadata.MetadataArtworkDecisionResolverFanartTvTest"`
Expected: COMPILATION FAILURE (resolver does not yet take a generator).

- [ ] **Step 3: Modify the resolver**

In `MetadataArtworkDecisionResolver.kt`:

(a) Inject the generator:

```kotlin
@Singleton
class MetadataArtworkDecisionResolver @Inject constructor(
    private val artworkRouter: ArtworkRouter,
    private val artworkDecisionCache: ArtworkDecisionCache,
    private val remoteSourceStore: ArtworkRemoteSourceStore,
    private val settingsSource: ArtworkProviderSettingsSource,
    private val fanartGenerator: com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGenerator
) {
```

(b) Augment the candidate list at the top of `resolveFields`:

```kotlin
    suspend fun resolveFields(
        candidates: List<ArtworkCandidate>
    ): Map<ResolvedField, FieldValue> {
        if (candidates.isEmpty()) return emptyMap()

        val withFanart = augmentWithFanart(candidates)

        val settings = settingsSource.settings.first()
        val policy = ArtworkRoutingPolicy(settings = settings)
        return withFanart
            .groupBy { candidate -> candidate.imageType }
            // ... rest unchanged
    }

    private suspend fun augmentWithFanart(
        candidates: List<ArtworkCandidate>
    ): List<ArtworkCandidate> {
        val byOwner = candidates.groupBy { it.ownerKey }
        val additions = mutableListOf<ArtworkCandidate>()
        byOwner.forEach { (ownerKey, perOwnerCandidates) ->
            val sample = perOwnerCandidates.first()
            val requestedTypes = perOwnerCandidates
                .map { it.imageType }
                .filter { it != ArtworkType.THUMBNAIL }
                .toSet()
            if (requestedTypes.isEmpty()) return@forEach
            additions += fanartGenerator.generate(
                ownerKey = ownerKey,
                canonicalContentId = sample.canonicalContentId,
                mediaKind = sample.mediaKind,
                providerIds = sample.providerIds,
                requestedTypes = requestedTypes
            )
        }
        return candidates + additions
    }
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.metadata.MetadataArtworkDecisionResolverFanartTvTest"`
Expected: PASS

- [ ] **Step 5: Build to confirm production graph**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (Hilt binding from Task 6.2 satisfies the new constructor parameter).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolver.kt \
        app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolverFanartTvTest.kt
git commit -m "feat(metadata): augment resolver candidates with Fanart.tv intermediates"
```

---

### Task 7.2: Per-type mixing test (RPDB poster + Fanart logo/backdrop)

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkRouterTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `ArtworkRouterTest`. (If the existing `candidate()` helper does not take an `imageType` parameter, add an optional parameter defaulting to `ArtworkType.POSTER`.)

```kotlin
    @Test
    fun `per-type mixing - RPDB poster wins, Fanart logo wins over PRIMARY`() {
        val settings = ArtworkProviderSettings(
            rpdbApiKey = "k",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB,
                logoProvider = ArtworkProviderChoiceKey.DEFAULT,
                backdropProvider = ArtworkProviderChoiceKey.DEFAULT,
                thumbnailProvider = ArtworkProviderChoiceKey.DEFAULT
            )
        )
        val all = listOf(
            candidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                role = ArtworkSourceRole.PREMIUM,
                priority = 10,
                imageType = ArtworkType.POSTER
            ),
            candidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
                role = ArtworkSourceRole.INTERMEDIATE,
                priority = 15,
                imageType = ArtworkType.POSTER
            ),
            candidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                role = ArtworkSourceRole.PRIMARY,
                priority = 20,
                imageType = ArtworkType.POSTER
            ),
            candidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
                role = ArtworkSourceRole.INTERMEDIATE,
                priority = 15,
                imageType = ArtworkType.LOGO
            ),
            candidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                role = ArtworkSourceRole.PRIMARY,
                priority = 20,
                imageType = ArtworkType.LOGO
            )
        )
        val byType = all.groupBy { it.imageType }
        val posterDecision = router.select(byType[ArtworkType.POSTER]!!, policy(settings))
        val logoDecision = router.select(byType[ArtworkType.LOGO]!!, policy(settings))

        assertEquals("RPDB", posterDecision.selectedCandidate.provider?.key)
        assertEquals("FANART_TV", logoDecision.selectedCandidate.provider?.key)
    }
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.ArtworkRouterTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkRouterTest.kt
git commit -m "test(artwork-router): pin per-type mixing of premium + intermediate"
```

---

## Phase 8 — Verification & smoke

### Task 8.1: Trace redaction end-to-end test

**Files:**
- Create: `app/src/test/java/com/nexio/tv/data/integration/fanarttv/FanartTvTraceRedactionTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.FanartTvCallId
import org.junit.Assert.assertFalse
import org.junit.Test

class FanartTvTraceRedactionTest {
    @Test
    fun `every traceable artifact for a fanarttv lookup excludes the api key`() {
        val rawKey = "07882f4309da827df559bb85b63793f9"
        val spec = FanartTvLookupShape.specFor(
            callId = FanartTvCallId(FanartTvCallId.Type.MOVIE, "550"),
            apiKey = rawKey
        )

        // Collect every candidate trace string the IntegrationCallSpec exposes —
        // mirror whatever the project's existing poster-shape redaction tests
        // assert on (see e.g. PosterRatingsArtworkCredentialResolverTest or
        // similar IntegrationCallSpec-trace tests under app/src/test/java/...).
        val traces = listOfNotNull(
            spec.redactedUrlForTrace,
            spec.toString()
        )

        traces.forEach { trace ->
            assertFalse(
                "trace must not contain raw api key, was: $trace",
                trace.contains(rawKey)
            )
        }
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.fanarttv.FanartTvTraceRedactionTest"`
Expected: PASS. If FAIL, the redaction policy on `FanartTvLookupShape` is incomplete — copy the redaction declaration from the analogous poster-shape used in Task 5.3 Step 1.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/data/integration/fanarttv/FanartTvTraceRedactionTest.kt
git commit -m "test(fanarttv): trace artifacts must never contain the raw api key"
```

---

### Task 8.2: Manual smoke (real device or emulator)

**Files:** none (manual)

- [ ] **Step 1: Confirm `local.properties` has the key**

```bash
grep "fanarttv.api.key" local.properties
```
Expected: a non-empty value.

- [ ] **Step 2: Build a debug APK and install**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL and install succeeds.

- [ ] **Step 3: Verify movie behavior**

Open a Fight Club detail page (TMDB id 550). Verify:
- Poster = `fight-club-522a5477c7bd3.jpg`
- Logo   = `fight-club-504c0530d5f93.png`
- Backdrop = `fight-club-55e2393686745.jpg`

- [ ] **Step 4: Verify TV behavior**

Open a Breaking Bad detail page (TVDB id 81189). Verify:
- Poster = `breaking-bad-5427fc5ebded7.jpg`
- Logo   = `breaking-bad-503d6f03d4bfe.png`
- Backdrop = `breaking-bad-4fcb7b24428ba.jpg`

- [ ] **Step 5: Verify anime is skipped**

Open any anime detail page. Verify there is no `fanarttv.lookup` runtime call (check logcat / runtime audit). The TMDB/TVDB primary artwork must render normally.

- [ ] **Step 6: Verify second visit makes zero network calls**

Force-stop and re-open the app. Re-open the Fight Club page. Bytes should serve from disk; no `fanarttv.lookup` network activity.

- [ ] **Step 7: Document the manual outcome in the implementation PR**

No code change. Note in the PR description what was checked and what was observed.

---

## Self-Review Notes

This section is for the plan author, not the implementer.

**Spec coverage:**
- Routing role (Architecture / Section 1) → Tasks 4.1, 4.2, 7.2.
- Components → Tasks 1.1, 2.2, 2.3, 3.1, 3.2, 3.3, 5.1, 5.3, 5.4, 6.1, 6.2.
- Data flow (lookup → pick → emit) → Tasks 6.1, 7.1.
- Cache layers (3 standard caches; no parallel store) → Tasks 5.3 (CacheFirst 14d on JSON), 4.2 + 7.1 (decision via existing ArtworkDecisionCache flow), bytes layer untouched.
- Image selection rules → Tasks 3.3, 3.4.
- Settings UI / migration → no UI changes; build wiring in Task 1.2.
- Audit/redaction → Tasks 5.3, 8.1.
- Error handling table → Task 6.1 covers 200/404/auth/transient.
- Per-type mixing (premium + intermediate per type) → Task 7.2.
- Test plan → Tasks 3.1–3.4, 4.2, 5.3, 6.1, 7.1, 7.2, 8.1, 8.2.

**Type/name consistency:**
- `FanartTvCallId` / `FanartTvCallId.Type` — used identically across selector, picker, lookup interface, generator.
- `FanartTvLookup` / `FanartTvLookupResult` — used identically across generator and runtime adapter.
- `INTERMEDIATE` source role + rank — added in Tasks 4.1 and 4.2 and consumed in Task 6.1 emission code and Task 7.2 router test.
- TTL constant `14L * 24 * 60 * 60 * 1000` ms — declared on `FanartTvLookupShape.cachePolicy` (Task 5.3); no other code references it.
- No `FanartTvDecisionStore`, `FanartTvDecisionKey`, `FanartTvDecisionEntry`, or `PersistedFanartTvDecisionStore` — confirmed absent. The standard chain (`integration_cache` + `ArtworkDecisionCache` + asset disk cache) handles all persistence.

**Placeholder scan:** Three tasks (5.2, 5.3, 7.1) instruct the implementer to mirror an existing project pattern by inspecting a named file. This is unavoidable without copying the project's full Hilt/Retrofit/IntegrationCallSpec boilerplate inline — the named files are concrete, the assertion of correctness is concrete (build passes, redaction test passes, resolver test passes), and there are no unconstrained "TODOs" left to the implementer's judgment. All other tasks ship complete code.

**Removed from prior plan revision** (now invented/unnecessary):
- `FanartTvDecisionStore` interface and key/entry types — **deleted**.
- `PersistedFanartTvDecisionStore` impl — **deleted**.
- Decision-cache freshness short-circuit logic in the generator — **deleted** (runtime CacheFirst handles freshness).
- 404 → "write 3 null decisions with 14d TTL" semantic — **deleted** (runtime + provider backoff + ArtworkDecisionCache TTL govern re-query cadence).
- Generator-local Mutex+CompletableDeferred single-flight — **deleted** (IntegrationRuntime / IntegrationSingleFlight provides this).
- `clock` parameter on the generator — **deleted** (no time-based logic remains in the generator).
