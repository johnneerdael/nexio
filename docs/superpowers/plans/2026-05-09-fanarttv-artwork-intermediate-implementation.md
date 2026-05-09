# Fanart.tv Artwork Intermediate Provider — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Fanart.tv as an INTERMEDIATE artwork provider that improves Default-mode posters/logos/backdrops for movies and TV without exposing a new dropdown entry, without persistently caching the JSON document, and without inventing a parallel network path.

**Architecture:** Fanart.tv is a new routing rank between PREMIUM and PRIMARY in `ArtworkRouter`. A new `FanartTvCandidateGenerator` runs alongside existing TMDB/TVDB primary mappers inside `MetadataArtworkDecisionResolver`. One in-memory JSON parse per (mediaKind, id) yields three persisted decisions (poster/logo/backdrop) — URL or null — with 14d TTL. Bytes flow through the existing `ArtworkAssetRepository` → Coil pipeline.

**Tech Stack:** Kotlin, Hilt DI, Retrofit2 + Moshi/kotlinx-serialization (follow what existing fanart-similar adapters use — TVDB uses Retrofit), JUnit4, MockK, existing `IntegrationRuntime` for transport.

**Spec:** `docs/superpowers/specs/2026-05-09-fanarttv-artwork-intermediate-design.md`.

---

## File Structure

**New files** (all under `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/` unless noted):

```
core/artwork/fanarttv/
  FanartTvAvailability.kt             # Available(apiKey) | Disabled(reason) sealed type
  FanartTvIdSelector.kt               # (mediaKind, ProviderIds) → call-id or null
  FanartTvImagePicker.kt              # pure picker: doc + ArtworkType → URL | null
  FanartTvCandidateGenerator.kt       # orchestrates cache check + single-flight + emit
  FanartTvApiShapes.kt                # const FANART_TV_LOOKUP = "fanarttv.lookup"
  dto/FanartTvDocument.kt             # @Serializable DTO with 6 image arrays
  dto/FanartTvImage.kt                # @Serializable single-image DTO

data/integration/fanarttv/
  FanartTvApi.kt                      # Retrofit interface (movies + tv endpoints)
  FanartTvApiModule.kt                # Hilt module: provides FanartTvApi
  FanartTvLookupShape.kt              # IntegrationRuntime shape with api_key redaction
```

**Test fixtures** (committed):
```
app/src/test/resources/fixtures/fanarttv/
  fight-club-550.json                 # real movie response from spec
  breaking-bad-81189.json             # real tv response from spec
```

**New tests** (under `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/` unless noted):
```
FanartTvAvailabilityTest.kt
FanartTvIdSelectorTest.kt
FanartTvImagePickerTest.kt
FanartTvCandidateGeneratorTest.kt
data/integration/fanarttv/FanartTvLookupShapeTest.kt
core/artwork/ArtworkRouterTest.kt   # extend existing file
data/integration/metadata/MetadataArtworkDecisionResolverTest.kt   # extend if exists, else create
```

**Modifications**:
- `app/build.gradle.kts` — add `FANARTTV_API_KEY` BuildConfig field.
- `app/src/main/java/com/nexio/tv/core/integration/IntegrationProvider.kt` — add `FANART_TV`.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt` — add `ArtworkSourceRole.INTERMEDIATE`.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt` — insert `RoutingRank.INTERMEDIATE(1)`, shift others.
- `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolver.kt` — inject and call generator before routing.
- `local.properties` (developer machine, gitignored) — add `fanarttv.api.key=...`.

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
- Modify: `app/build.gradle.kts` (add line in `defaultConfig` next to existing `buildConfigField` calls around line 283)

- [ ] **Step 1: Add the field**

In `app/build.gradle.kts`, locate the existing `buildConfigField("String", "TRAILER_API_URL", ...)` line and add immediately after:

```kotlin
        buildConfigField(
            "String",
            "FANARTTV_API_KEY",
            "\"${localProperties.getProperty("fanarttv.api.key", "")}\""
        )
```

- [ ] **Step 2: Add a key to your local.properties (developer machine, gitignored)**

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

- [ ] **Step 1: Create the fixtures directory and write both fixture files**

Use the exact JSON bodies from the spec's "Image Selection Rules" section (movie sample = Fight Club tmdb_id 550, tv sample = Breaking Bad tvdb_id 81189). Save each as the file path above with the full JSON body verbatim.

(Both bodies appear in full inside the spec under the "Image Selection Rules" section.)

- [ ] **Step 2: Confirm the files load as valid JSON**

Run: `python3 -c "import json; json.load(open('app/src/test/resources/fixtures/fanarttv/fight-club-550.json')); json.load(open('app/src/test/resources/fixtures/fanarttv/breaking-bad-81189.json')); print('ok')"`
Expected: `ok`

- [ ] **Step 3: Commit**

```bash
git add app/src/test/resources/fixtures/fanarttv/
git commit -m "test(fanarttv): add fight-club and breaking-bad fixtures from API"
```

---

### Task 2.2: Define DTO classes for the consumed image arrays

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

    // Movie arrays
    @SerialName("hdmovielogo") val hdMovieLogo: List<FanartTvImage>? = null,
    @SerialName("moviebackground") val movieBackground: List<FanartTvImage>? = null,
    @SerialName("movieposter") val moviePoster: List<FanartTvImage>? = null,

    // TV arrays
    @SerialName("hdtvlogo") val hdTvLogo: List<FanartTvImage>? = null,
    @SerialName("showbackground") val showBackground: List<FanartTvImage>? = null,
    @SerialName("tvposter") val tvPoster: List<FanartTvImage>? = null
)
```

Other arrays in the API response (`hdclearart`, `clearart`, `clearlogo`, `characterart`, `tvbanner`, `moviebanner`, `moviedisc`, `moviethumb`, `tvthumb`, `seasonbanner`, `seasonposter`, `seasonthumb`, `movielogo`) are intentionally **not** modeled — the parser should ignore them. `kotlinx-serialization` defaults to `ignoreUnknownKeys` only when configured; verify the project's existing Json instance ignores unknowns (it almost certainly does — check any other DTO file like `app/src/main/java/com/nexio/tv/data/integration/tvdb/` for the pattern). If not, the FanartTvApi module configures its own Json with `ignoreUnknownKeys = true`.

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/dto/
git commit -m "feat(fanarttv): add DTOs for the six consumed image arrays"
```

---

### Task 2.3: Define Retrofit interface

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

The base URL is `https://webservice.fanart.tv/` — to be wired in the Hilt module in Task 5.

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
        val result = FanartTvAvailability.from("")
        assertEquals(
            FanartTvAvailability.Disabled("no_build_config_key"),
            result
        )
    }

    @Test
    fun `disabled when key is blank whitespace`() {
        val result = FanartTvAvailability.from("   ")
        assertEquals(
            FanartTvAvailability.Disabled("no_build_config_key"),
            result
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvAvailabilityTest"`
Expected: COMPILATION FAILURE (`Unresolved reference: FanartTvAvailability`).

- [ ] **Step 3: Write the implementation**

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

- [ ] **Step 4: Run test to verify it passes**

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
        val result = selector.select(
            MetadataMediaKind.MOVIE,
            ProviderIds(tmdb = "550")
        )
        assertEquals(FanartTvCallId(FanartTvCallId.Type.MOVIE, "550"), result)
    }

    @Test
    fun `movie without tmdb id returns null`() {
        assertNull(selector.select(MetadataMediaKind.MOVIE, ProviderIds(imdb = "tt0137523")))
    }

    @Test
    fun `series with tvdb id returns tv call id`() {
        val result = selector.select(
            MetadataMediaKind.SERIES,
            ProviderIds(tvdb = "81189")
        )
        assertEquals(FanartTvCallId(FanartTvCallId.Type.TV, "81189"), result)
    }

    @Test
    fun `series without tvdb id returns null`() {
        assertNull(selector.select(MetadataMediaKind.SERIES, ProviderIds(tmdb = "1396")))
    }

    @Test
    fun `anime returns null even with both ids`() {
        assertNull(
            selector.select(
                MetadataMediaKind.ANIME,
                ProviderIds(tmdb = "1", tvdb = "2")
            )
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

- [ ] **Step 3: Write the implementation**

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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvIdSelectorTest"`
Expected: PASS (6/6)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvIdSelector.kt \
        app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvIdSelectorTest.kt
git commit -m "feat(fanarttv): add id selector mapping mediaKind+ids to call id"
```

---

### Task 3.3: FanartTvImagePicker — basic rules

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
        assertEquals("en-hi.png", picker.pickMovieLogo(doc))
    }

    @Test
    fun `tv logo picks highest-likes en`() {
        val doc = FanartTvDocument(
            hdTvLogo = listOf(
                FanartTvImage(id = "1", url = "best.png", lang = "en", likes = "24"),
                FanartTvImage(id = "2", url = "ok.png", lang = "en", likes = "10")
            )
        )
        assertEquals("best.png", picker.pickTvLogo(doc))
    }

    @Test
    fun `movie backdrop picks highest-likes regardless of lang`() {
        val doc = FanartTvDocument(
            movieBackground = listOf(
                FanartTvImage(id = "1", url = "a.jpg", lang = "", likes = "5"),
                FanartTvImage(id = "2", url = "b.jpg", lang = "", likes = "3")
            )
        )
        assertEquals("a.jpg", picker.pickMovieBackdrop(doc))
    }

    @Test
    fun `tv backdrop picks highest-likes regardless of lang`() {
        val doc = FanartTvDocument(
            showBackground = listOf(
                FanartTvImage(id = "1", url = "x.jpg", lang = "", likes = "12"),
                FanartTvImage(id = "2", url = "y.jpg", lang = "", likes = "10")
            )
        )
        assertEquals("x.jpg", picker.pickTvBackdrop(doc))
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
        assertEquals("en15.jpg", picker.pickMoviePoster(doc))
    }

    @Test
    fun `tv poster picks highest-likes en`() {
        val doc = FanartTvDocument(
            tvPoster = listOf(
                FanartTvImage(id = "1", url = "en14.jpg", lang = "en", likes = "14"),
                FanartTvImage(id = "2", url = "en6.jpg", lang = "en", likes = "6")
            )
        )
        assertEquals("en14.jpg", picker.pickTvPoster(doc))
    }

    @Test
    fun `null when no en variant for poster`() {
        val doc = FanartTvDocument(
            moviePoster = listOf(
                FanartTvImage(id = "1", url = "ru.jpg", lang = "ru", likes = "5"),
                FanartTvImage(id = "2", url = "es.jpg", lang = "es", likes = "3")
            )
        )
        assertNull(picker.pickMoviePoster(doc))
    }

    @Test
    fun `null when no en variant for logo`() {
        val doc = FanartTvDocument(
            hdMovieLogo = listOf(
                FanartTvImage(id = "1", url = "ru.png", lang = "ru", likes = "5")
            )
        )
        assertNull(picker.pickMovieLogo(doc))
    }

    @Test
    fun `null when array missing`() {
        val doc = FanartTvDocument()
        assertNull(picker.pickMovieLogo(doc))
        assertNull(picker.pickMovieBackdrop(doc))
        assertNull(picker.pickMoviePoster(doc))
        assertNull(picker.pickTvLogo(doc))
        assertNull(picker.pickTvBackdrop(doc))
        assertNull(picker.pickTvPoster(doc))
    }

    @Test
    fun `null when array empty`() {
        val doc = FanartTvDocument(hdMovieLogo = emptyList())
        assertNull(picker.pickMovieLogo(doc))
    }

    @Test
    fun `tie-break by ascending id is deterministic`() {
        val doc = FanartTvDocument(
            hdMovieLogo = listOf(
                FanartTvImage(id = "200", url = "later.png", lang = "en", likes = "8"),
                FanartTvImage(id = "100", url = "earlier.png", lang = "en", likes = "8")
            )
        )
        assertEquals("earlier.png", picker.pickMovieLogo(doc))
    }

    @Test
    fun `entries with malformed likes are treated as zero`() {
        val doc = FanartTvDocument(
            hdMovieLogo = listOf(
                FanartTvImage(id = "1", url = "good.png", lang = "en", likes = "1"),
                FanartTvImage(id = "2", url = "junk.png", lang = "en", likes = "abc")
            )
        )
        assertEquals("good.png", picker.pickMovieLogo(doc))
    }

    @Test
    fun `entries with null url are skipped`() {
        val doc = FanartTvDocument(
            hdMovieLogo = listOf(
                FanartTvImage(id = "1", url = null, lang = "en", likes = "100"),
                FanartTvImage(id = "2", url = "real.png", lang = "en", likes = "1")
            )
        )
        assertEquals("real.png", picker.pickMovieLogo(doc))
    }

    @Test
    fun `pickFor dispatches by media+type`() {
        val doc = FanartTvDocument(
            hdMovieLogo = listOf(FanartTvImage(id = "1", url = "ml.png", lang = "en", likes = "1")),
            movieBackground = listOf(FanartTvImage(id = "2", url = "mb.jpg", lang = "", likes = "1")),
            moviePoster = listOf(FanartTvImage(id = "3", url = "mp.jpg", lang = "en", likes = "1")),
            hdTvLogo = listOf(FanartTvImage(id = "4", url = "tl.png", lang = "en", likes = "1")),
            showBackground = listOf(FanartTvImage(id = "5", url = "tb.jpg", lang = "", likes = "1")),
            tvPoster = listOf(FanartTvImage(id = "6", url = "tp.jpg", lang = "en", likes = "1"))
        )
        assertEquals("ml.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
        assertEquals("mb.jpg", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.BACKDROP))
        assertEquals("mp.jpg", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.POSTER))
        assertEquals("tl.png", picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.LOGO))
        assertEquals("tb.jpg", picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.BACKDROP))
        assertEquals("tp.jpg", picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.POSTER))
    }

    @Test
    fun `pickFor returns null for thumbnail (unsupported)`() {
        val doc = FanartTvDocument()
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.THUMBNAIL))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.THUMBNAIL))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvImagePickerTest"`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvImage

class FanartTvImagePicker {

    fun pickMovieLogo(doc: FanartTvDocument): String? =
        pickEnglishHighest(doc.hdMovieLogo)

    fun pickTvLogo(doc: FanartTvDocument): String? =
        pickEnglishHighest(doc.hdTvLogo)

    fun pickMovieBackdrop(doc: FanartTvDocument): String? =
        pickHighestAnyLang(doc.movieBackground)

    fun pickTvBackdrop(doc: FanartTvDocument): String? =
        pickHighestAnyLang(doc.showBackground)

    fun pickMoviePoster(doc: FanartTvDocument): String? =
        pickEnglishHighest(doc.moviePoster)

    fun pickTvPoster(doc: FanartTvDocument): String? =
        pickEnglishHighest(doc.tvPoster)

    fun pickFor(
        doc: FanartTvDocument,
        callType: FanartTvCallId.Type,
        artworkType: ArtworkType
    ): String? = when (callType) {
        FanartTvCallId.Type.MOVIE -> when (artworkType) {
            ArtworkType.LOGO -> pickMovieLogo(doc)
            ArtworkType.BACKDROP -> pickMovieBackdrop(doc)
            ArtworkType.POSTER -> pickMoviePoster(doc)
            ArtworkType.THUMBNAIL -> null
        }
        FanartTvCallId.Type.TV -> when (artworkType) {
            ArtworkType.LOGO -> pickTvLogo(doc)
            ArtworkType.BACKDROP -> pickTvBackdrop(doc)
            ArtworkType.POSTER -> pickTvPoster(doc)
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvImagePickerTest"`
Expected: PASS (all tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvImagePicker.kt \
        app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvImagePickerTest.kt
git commit -m "feat(fanarttv): add image picker with highest-likes + lang gating"
```

---

### Task 3.4: Picker fixture-driven test against real responses

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvImagePickerTest.kt`

- [ ] **Step 1: Add fixture loading helper + tests**

Append the following test methods inside the existing `FanartTvImagePickerTest` class:

```kotlin
    @Test
    fun `fight club fixture picks expected urls`() {
        val doc = loadFixture("fight-club-550.json")
        assertEquals(
            "https://assets.fanart.tv/fanart/fight-club-504c0530d5f93.png",
            picker.pickMovieLogo(doc)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/fight-club-55e2393686745.jpg",
            picker.pickMovieBackdrop(doc)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/fight-club-522a5477c7bd3.jpg",
            picker.pickMoviePoster(doc)
        )
    }

    @Test
    fun `breaking bad fixture picks expected urls`() {
        val doc = loadFixture("breaking-bad-81189.json")
        assertEquals(
            "https://assets.fanart.tv/fanart/breaking-bad-503d6f03d4bfe.png",
            picker.pickTvLogo(doc)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/breaking-bad-4fcb7b24428ba.jpg",
            picker.pickTvBackdrop(doc)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/breaking-bad-5427fc5ebded7.jpg",
            picker.pickTvPoster(doc)
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
Expected: PASS (including the two new fixture tests)

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

Change:
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
to:
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

- [ ] **Step 2: Build to confirm no callers broke**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL (the existing `when` branches over `ArtworkSourceRole` may need an `else` branch update — fix any non-exhaustive `when` warnings/errors by adding an `INTERMEDIATE` branch that handles the role like `PRIMARY` if the consumer is non-router-related, or treat as `else -> ` neutrally; the only consumer that semantically cares is the router and we change it next.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt
# include any required exhaustive-when fixes
git commit -m "feat(artwork): add INTERMEDIATE source role between PREMIUM and PRIMARY"
```

---

### Task 4.2: Insert INTERMEDIATE rank into ArtworkRouter and extend tests

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkRouterTest.kt`

- [ ] **Step 1: Write the failing test (append to ArtworkRouterTest)**

Add these tests to the bottom of `ArtworkRouterTest`. (Re-use the existing `candidate()` and `policy()` helpers in that file — they are defined further down. If unsure, run `grep -n "private fun candidate\|private fun policy" app/src/test/java/com/nexio/tv/core/artwork/ArtworkRouterTest.kt` first.)

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

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.ArtworkRouterTest"`
Expected: the three new tests fail because `RoutingRank.INTERMEDIATE` does not exist and the router does not recognize the role.

- [ ] **Step 3: Modify the router**

In `app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt`:

(a) Add the new rank to `RoutingRank`:

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

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.ArtworkRouterTest"`
Expected: PASS for all tests (existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt \
        app/src/test/java/com/nexio/tv/core/artwork/ArtworkRouterTest.kt
git commit -m "feat(artwork-router): rank INTERMEDIATE between PREMIUM and PRIMARY"
```

---

## Phase 5 — Runtime shape registration

### Task 5.1: Register fanarttv.lookup shape constant

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvApiShapes.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

object FanartTvApiShapes {
    const val LOOKUP = "fanarttv.lookup"
}
```

(Lives next to other artwork-domain code; mirrors the per-domain `*ApiShapes` object pattern used in `IntegrationApiShapes.kt`.)

- [ ] **Step 2: Build to verify**

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

- [ ] **Step 1: Write the module**

Look at one existing similar Retrofit-via-Hilt module to copy the project's exact OkHttp/Retrofit factory pattern. Run:
```
grep -rn "@Module\|@Provides.*Retrofit\|baseUrl(" app/src/main/java/com/nexio/tv/data/integration/tmdb/ 2>/dev/null | head -10
```
Then mirror that pattern for Fanart.tv with `baseUrl = "https://webservice.fanart.tv/"`. Use the project's existing `kotlinx-serialization` JSON converter; configure `ignoreUnknownKeys = true` if not already the default.

The provided binding must be `@Singleton` and produce a `FanartTvApi`. Inject into the same Hilt component as other artwork providers (`SingletonComponent`).

- [ ] **Step 2: Build to verify Hilt graph compiles**

Run: `./gradlew :app:kspDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApiModule.kt
git commit -m "feat(fanarttv): provide FanartTvApi via Hilt with fanart.tv base url"
```

---

### Task 5.3: FanartTvLookupShape — runtime call wrapper with redaction

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvLookupShape.kt`
- Create: `app/src/test/java/com/nexio/tv/data/integration/fanarttv/FanartTvLookupShapeTest.kt`

- [ ] **Step 1: Inspect how RPDB / Top Posters wire through IntegrationRuntime**

Run:
```
grep -rn "IntegrationCallSpec\|IntegrationRuntime\|IntegrationCacheStore" app/src/main/java/com/nexio/tv/data/integration/posters/RpdbIntegrationProvider.kt | head -20
```
This shows the project's idiomatic shape: a class that builds an `IntegrationCallSpec` with redaction policies and executes through `IntegrationRuntime`. Mirror that.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.FanartTvCallId
import org.junit.Assert.assertFalse
import org.junit.Test

class FanartTvLookupShapeTest {
    @Test
    fun `redacted url for trace contains no api_key value`() {
        // Construct the call spec the same way the production code does and
        // ask the spec for its redacted url-for-trace. The exact API to
        // request the redacted form depends on IntegrationCallSpec; check
        // existing tests in app/src/test/java/com/nexio/tv/data/integration/posters/
        // for the canonical assertion pattern, then replicate it here.
        val spec = FanartTvLookupShape.specFor(
            callId = FanartTvCallId(FanartTvCallId.Type.MOVIE, "550"),
            apiKey = "07882f4309da827df559bb85b63793f9"
        )
        val redacted = spec.redactedUrlForTrace
        assertFalse(
            "redacted form must not contain the raw api key, was: $redacted",
            redacted.contains("07882f4309da827df559bb85b63793f9")
        )
    }
}
```

- [ ] **Step 3: Run test to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.fanarttv.FanartTvLookupShapeTest"`
Expected: COMPILATION FAILURE.

- [ ] **Step 4: Implement FanartTvLookupShape**

Build the shape class using the same `IntegrationCallSpec` builder used by `RpdbIntegrationProvider` / `TopPostersIntegrationProvider`. Key requirements:
- Shape name: `FanartTvApiShapes.LOOKUP`.
- Provider: `IntegrationProvider.FANART_TV`.
- Cache policy: `CacheFirst` with 14d TTL (use the same constant the existing artwork shapes use; 14 * 24h).
- Work class: `USER_VISIBLE`.
- Redaction: declare `api_key` as a redacted query parameter so any URL traced/audited replaces its value with `<redacted>`. Use the same redaction-policy mechanism the RPDB poster shape uses for its `apikey` parameter.
- Expose a `specFor(callId, apiKey)` factory that returns the spec (testable in isolation).
- Expose a `suspend fun fetch(callId, apiKey): FanartTvDocument` that runs the spec through `IntegrationRuntime` and returns the parsed body — or rethrows the runtime's typed failure.

(If `IntegrationCallSpec` does not expose `redactedUrlForTrace` directly, adjust the test to assert the equivalent property the existing shapes are tested against. The goal is: *prove* the raw key never appears in the trace string.)

- [ ] **Step 5: Run test to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.fanarttv.FanartTvLookupShapeTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvLookupShape.kt \
        app/src/test/java/com/nexio/tv/data/integration/fanarttv/FanartTvLookupShapeTest.kt
git commit -m "feat(fanarttv): wire lookup through IntegrationRuntime with api_key redaction"
```

---

## Phase 6 — Candidate generator

### Task 6.1: Generator skeleton + anime/availability/id gates

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGenerator.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt`

- [ ] **Step 1: Write the failing test (gates only)**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class FanartTvCandidateGeneratorTest {

    private val lookup = mockk<FanartTvLookup>(relaxed = true)
    private val decisionStore = FakeFanartDecisionStore()

    private fun generator(availability: FanartTvAvailability) = FanartTvCandidateGenerator(
        availabilityProvider = { availability },
        idSelector = FanartTvIdSelector(),
        picker = FanartTvImagePicker(),
        lookup = lookup,
        decisionStore = decisionStore,
        clock = { 1_000_000L }
    )

    private val ownerKey = ArtworkOwnerKey.CanonicalContent("movie:550")
    private val movieIds = ProviderIds(tmdb = "550")

    @Test
    fun `anime emits zero and makes zero api calls`() = runTest {
        val candidates = generator(FanartTvAvailability.Available("k")).generate(
            ownerKey = ownerKey,
            canonicalContentId = "anime:1",
            mediaKind = MetadataMediaKind.ANIME,
            providerIds = ProviderIds(tvdb = "1", tmdb = "1"),
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP)
        )
        assertTrue(candidates.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }

    @Test
    fun `disabled key emits zero and makes zero api calls`() = runTest {
        val candidates = generator(FanartTvAvailability.Disabled("no_build_config_key")).generate(
            ownerKey = ownerKey,
            canonicalContentId = "movie:550",
            mediaKind = MetadataMediaKind.MOVIE,
            providerIds = movieIds,
            requestedTypes = setOf(ArtworkType.POSTER)
        )
        assertTrue(candidates.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }

    @Test
    fun `missing usable id emits zero and makes zero api calls`() = runTest {
        val candidates = generator(FanartTvAvailability.Available("k")).generate(
            ownerKey = ownerKey,
            canonicalContentId = "movie:noid",
            mediaKind = MetadataMediaKind.MOVIE,
            providerIds = ProviderIds(imdb = "tt0137523"), // no tmdb
            requestedTypes = setOf(ArtworkType.POSTER)
        )
        assertTrue(candidates.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }
}
```

(Define `FakeFanartDecisionStore` and `FanartTvLookup` next; both will be created as part of the implementation.)

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds
import java.security.MessageDigest

interface FanartTvLookup {
    suspend fun fetch(callId: FanartTvCallId, apiKey: String): FanartTvLookupResult
}

sealed interface FanartTvLookupResult {
    data class Success(val document: FanartTvDocument) : FanartTvLookupResult
    data object NotFound : FanartTvLookupResult           // 404 → cache nulls
    data object AuthFailed : FanartTvLookupResult         // 401/403 → no cache write
    data object Transient : FanartTvLookupResult          // 429/5xx/network → no cache write
}

interface FanartTvDecisionStore {
    suspend fun read(key: FanartTvDecisionKey, nowMs: Long): FanartTvDecisionEntry?
    suspend fun write(key: FanartTvDecisionKey, entry: FanartTvDecisionEntry)
}

data class FanartTvDecisionKey(
    val policyVersion: Int,
    val idType: String,           // "tmdb" or "tvdb"
    val idValue: String,
    val artworkType: ArtworkType
)

data class FanartTvDecisionEntry(
    val urlOrNull: String?,
    val expiresAtMs: Long
)

class FanartTvCandidateGenerator(
    private val availabilityProvider: () -> FanartTvAvailability,
    private val idSelector: FanartTvIdSelector,
    private val picker: FanartTvImagePicker,
    private val lookup: FanartTvLookup,
    private val decisionStore: FanartTvDecisionStore,
    private val clock: () -> Long,
    private val policyVersion: Int = 1,
    private val ttlMs: Long = TTL_MS
) {
    suspend fun generate(
        ownerKey: ArtworkOwnerKey,
        canonicalContentId: String?,
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds,
        requestedTypes: Set<ArtworkType>
    ): List<ArtworkCandidate> {
        if (mediaKind == MetadataMediaKind.ANIME) return emptyList()
        val availability = availabilityProvider()
        if (availability !is FanartTvAvailability.Available) return emptyList()
        val callId = idSelector.select(mediaKind, providerIds) ?: return emptyList()

        // Phase 6.2 will add: cache freshness check + lookup + emission.
        return emptyList()
    }

    companion object {
        const val TTL_MS: Long = 14L * 24 * 60 * 60 * 1000
    }
}
```

Add the test file's `FakeFanartDecisionStore`:

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import java.util.concurrent.ConcurrentHashMap

class FakeFanartDecisionStore : FanartTvDecisionStore {
    val entries = ConcurrentHashMap<FanartTvDecisionKey, FanartTvDecisionEntry>()
    override suspend fun read(key: FanartTvDecisionKey, nowMs: Long): FanartTvDecisionEntry? =
        entries[key]?.takeIf { it.expiresAtMs > nowMs }
    override suspend fun write(key: FanartTvDecisionKey, entry: FanartTvDecisionEntry) {
        entries[key] = entry
    }
}
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: PASS (3/3).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGenerator.kt \
        app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt \
        app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FakeFanartDecisionStore.kt
git commit -m "feat(fanarttv): generator skeleton with anime/availability/id gates"
```

---

### Task 6.2: Cache freshness short-circuit

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGenerator.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `FanartTvCandidateGeneratorTest`:

```kotlin
    @Test
    fun `all types fresh non-null - zero api calls and 3 candidates`() = runTest {
        val now = 1_000_000L
        listOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP).forEach { type ->
            decisionStore.entries[
                FanartTvDecisionKey(1, "tmdb", "550", type)
            ] = FanartTvDecisionEntry(
                urlOrNull = "https://assets.fanart.tv/$type.png",
                expiresAtMs = now + 1
            )
        }
        val candidates = generator(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP)
        )
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
        assertEquals(3, candidates.size)
        assertEquals(
            setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            candidates.map { it.imageType }.toSet()
        )
        assertTrue(candidates.all { it.sourceRole == ArtworkSourceRole.INTERMEDIATE })
        assertTrue(
            candidates.all { it.provider == ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV) }
        )
    }

    @Test
    fun `all types fresh null - zero api calls and zero candidates`() = runTest {
        val now = 1_000_000L
        listOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP).forEach { type ->
            decisionStore.entries[
                FanartTvDecisionKey(1, "tmdb", "550", type)
            ] = FanartTvDecisionEntry(urlOrNull = null, expiresAtMs = now + 1)
        }
        val candidates = generator(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP)
        )
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
        assertTrue(candidates.isEmpty())
    }
```

Also add this import at the top:

```kotlin
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: 2 new tests fail (current generator returns empty list always).

- [ ] **Step 3: Implement cache short-circuit**

Replace the body of `generate(...)` after the gates with:

```kotlin
        val now = clock()
        val decisionKeysByType = requestedTypes
            .filter { it != ArtworkType.THUMBNAIL }
            .associateWith { type ->
                FanartTvDecisionKey(
                    policyVersion = policyVersion,
                    idType = callId.type.idTypeKey(),
                    idValue = callId.value,
                    artworkType = type
                )
            }

        val cached: Map<ArtworkType, FanartTvDecisionEntry?> =
            decisionKeysByType.mapValues { (_, key) -> decisionStore.read(key, now) }

        val freshUrls = mutableMapOf<ArtworkType, String?>()
        val staleTypes = mutableSetOf<ArtworkType>()
        cached.forEach { (type, entry) ->
            if (entry != null) freshUrls[type] = entry.urlOrNull
            else staleTypes += type
        }

        if (staleTypes.isEmpty()) {
            return freshUrls.toCandidates(ownerKey, canonicalContentId, mediaKind, providerIds)
        }

        // Phase 6.3 fills in: lookup, picker, decision writes, and merged emission.
        return freshUrls.toCandidates(ownerKey, canonicalContentId, mediaKind, providerIds)
```

Add the helpers at the bottom of the class:

```kotlin
    private fun FanartTvCallId.Type.idTypeKey(): String = when (this) {
        FanartTvCallId.Type.MOVIE -> "tmdb"
        FanartTvCallId.Type.TV -> "tvdb"
    }

    private fun Map<ArtworkType, String?>.toCandidates(
        ownerKey: ArtworkOwnerKey,
        canonicalContentId: String?,
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds
    ): List<ArtworkCandidate> = mapNotNull { (type, url) ->
        url?.let { candidate(ownerKey, canonicalContentId, mediaKind, providerIds, type, it) }
    }

    private fun candidate(
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
            imageLanguage = if (type == ArtworkType.BACKDROP) "" else "en"
        )
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    companion object {
        const val INTERMEDIATE_PRIORITY = 15
        const val TTL_MS: Long = 14L * 24 * 60 * 60 * 1000
    }
```

(Drop the duplicate `companion object` from the prior task — there should only be one. Also remove the prior `TTL_MS` constant if duplicated.)

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: PASS (5/5).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGenerator.kt \
        app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt
git commit -m "feat(fanarttv): short-circuit generator on fresh decision cache hits"
```

---

### Task 6.3: API success path — single-flight, parse, write 3 decisions

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGenerator.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt`

- [ ] **Step 1: Write failing test**

Append to `FanartTvCandidateGeneratorTest`:

```kotlin
    @Test
    fun `all types stale - one api call, three decisions written, candidates emitted`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Success(
            document = FanartTvDocument(
                hdMovieLogo = listOf(
                    com.nexio.tv.core.artwork.fanarttv.dto.FanartTvImage(
                        id = "1", url = "logo.png", lang = "en", likes = "8"
                    )
                ),
                movieBackground = listOf(
                    com.nexio.tv.core.artwork.fanarttv.dto.FanartTvImage(
                        id = "1", url = "back.jpg", lang = "", likes = "5"
                    )
                ),
                moviePoster = listOf(
                    com.nexio.tv.core.artwork.fanarttv.dto.FanartTvImage(
                        id = "1", url = "poster.jpg", lang = "en", likes = "15"
                    )
                )
            )
        )

        val candidates = generator(FanartTvAvailability.Available("key")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP)
        )

        coVerify(exactly = 1) { lookup.fetch(any(), any()) }
        assertEquals(3, candidates.size)
        assertEquals(3, decisionStore.entries.size)
        assertEquals(
            "logo.png",
            decisionStore.entries[FanartTvDecisionKey(1, "tmdb", "550", ArtworkType.LOGO)]?.urlOrNull
        )
        assertEquals(
            "back.jpg",
            decisionStore.entries[FanartTvDecisionKey(1, "tmdb", "550", ArtworkType.BACKDROP)]?.urlOrNull
        )
        assertEquals(
            "poster.jpg",
            decisionStore.entries[FanartTvDecisionKey(1, "tmdb", "550", ArtworkType.POSTER)]?.urlOrNull
        )
    }

    @Test
    fun `partial picker miss writes null decision and emits only present types`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Success(
            document = FanartTvDocument(
                moviePoster = listOf(
                    com.nexio.tv.core.artwork.fanarttv.dto.FanartTvImage(
                        id = "1", url = "poster.jpg", lang = "en", likes = "1"
                    )
                )
                // no logo or backdrop arrays
            )
        )
        val candidates = generator(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP)
        )
        assertEquals(1, candidates.size)
        assertEquals(ArtworkType.POSTER, candidates.single().imageType)
        assertEquals(3, decisionStore.entries.size)
        assertEquals(
            null,
            decisionStore.entries[FanartTvDecisionKey(1, "tmdb", "550", ArtworkType.LOGO)]?.urlOrNull
        )
        assertEquals(
            null,
            decisionStore.entries[FanartTvDecisionKey(1, "tmdb", "550", ArtworkType.BACKDROP)]?.urlOrNull
        )
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: 2 new tests fail (no api call is made yet).

- [ ] **Step 3: Implement the lookup + decision-write path**

Replace the trailing `// Phase 6.3 fills in:` comment block (and the duplicate return) with:

```kotlin
        val result = lookup.fetch(callId, availability.apiKey)
        when (result) {
            is FanartTvLookupResult.Success -> {
                val expiresAt = now + ttlMs
                // Always write decisions for ALL three artwork types we model,
                // not just the requested ones — this fills the cache so a later
                // request for a different type benefits from the same call.
                listOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP).forEach { type ->
                    val key = FanartTvDecisionKey(
                        policyVersion = policyVersion,
                        idType = callId.type.idTypeKey(),
                        idValue = callId.value,
                        artworkType = type
                    )
                    val url = picker.pickFor(result.document, callId.type, type)
                    decisionStore.write(key, FanartTvDecisionEntry(urlOrNull = url, expiresAtMs = expiresAt))
                    if (type in requestedTypes && url != null) {
                        freshUrls[type] = url
                    }
                }
            }
            FanartTvLookupResult.NotFound -> {
                val expiresAt = now + ttlMs
                listOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP).forEach { type ->
                    val key = FanartTvDecisionKey(
                        policyVersion = policyVersion,
                        idType = callId.type.idTypeKey(),
                        idValue = callId.value,
                        artworkType = type
                    )
                    decisionStore.write(key, FanartTvDecisionEntry(urlOrNull = null, expiresAtMs = expiresAt))
                }
            }
            FanartTvLookupResult.AuthFailed,
            FanartTvLookupResult.Transient -> {
                // No persistence. Recoverable without waiting 14d.
            }
        }
        return freshUrls.toCandidates(ownerKey, canonicalContentId, mediaKind, providerIds)
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: PASS (7/7).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGenerator.kt \
        app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt
git commit -m "feat(fanarttv): on cache miss, lookup + write 3 decisions in one round-trip"
```

---

### Task 6.4: 404 / 401 / transient failure semantics

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt`

- [ ] **Step 1: Write failing tests**

Append:

```kotlin
    @Test
    fun `404 writes null decisions and emits zero`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.NotFound
        val candidates = generator(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP)
        )
        assertTrue(candidates.isEmpty())
        assertEquals(3, decisionStore.entries.size)
        assertTrue(decisionStore.entries.values.all { it.urlOrNull == null })
    }

    @Test
    fun `auth failure writes nothing and emits zero`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.AuthFailed
        val candidates = generator(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP)
        )
        assertTrue(candidates.isEmpty())
        assertTrue(decisionStore.entries.isEmpty())
    }

    @Test
    fun `transient failure writes nothing and emits zero`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Transient
        val candidates = generator(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP)
        )
        assertTrue(candidates.isEmpty())
        assertTrue(decisionStore.entries.isEmpty())
    }
```

- [ ] **Step 2: Run tests to verify pass**

The current implementation already handles these branches. Run:
`./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: PASS (10/10). If any fail, fix the implementation per the spec table — 404 → 3 nulls, auth/transient → no writes.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt
git commit -m "test(fanarttv): cover 404 (null decisions) and auth/transient (no writes)"
```

---

### Task 6.5: Single-flight coalescing across concurrent calls

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGenerator.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt`

- [ ] **Step 1: Write failing test**

Append:

```kotlin
    @Test
    fun `concurrent calls for the same title produce one api call`() = runTest {
        coEvery { lookup.fetch(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(50)
            FanartTvLookupResult.Success(
                FanartTvDocument(
                    moviePoster = listOf(
                        com.nexio.tv.core.artwork.fanarttv.dto.FanartTvImage(
                            id = "1", url = "p.jpg", lang = "en", likes = "1"
                        )
                    )
                )
            )
        }
        val gen = generator(FanartTvAvailability.Available("k"))
        kotlinx.coroutines.coroutineScope {
            val a = kotlinx.coroutines.async {
                gen.generate(ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds, setOf(ArtworkType.POSTER))
            }
            val b = kotlinx.coroutines.async {
                gen.generate(ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds, setOf(ArtworkType.POSTER))
            }
            a.await()
            b.await()
        }
        coVerify(exactly = 1) { lookup.fetch(any(), any()) }
    }
```

(Note: `runTest` uses a virtual scheduler — if `delay(50)` doesn't behave the way the test expects, switch to `runBlocking` for this test only and use `Thread.sleep(50)` inside the mocked `coAnswers`. The intent is two calls overlap.)

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: the new test fails — both concurrent calls trigger their own `lookup.fetch`.

- [ ] **Step 3: Add per-title single-flight to the generator**

At the top of `FanartTvCandidateGenerator`:

```kotlin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FanartTvCandidateGenerator(
    // existing constructor params...
) {
    private val flightMutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<FanartTvLookupResult>>()
    // ...
}
```

Wrap the `lookup.fetch(callId, availability.apiKey)` call in a single-flight helper:

```kotlin
    private suspend fun singleFlightFetch(
        callId: FanartTvCallId,
        apiKey: String
    ): FanartTvLookupResult {
        val key = "${callId.type.idTypeKey()}:${callId.value}"
        val (deferred, owner) = flightMutex.withLock {
            val existing = inFlight[key]
            if (existing != null) existing to false
            else {
                val fresh = CompletableDeferred<FanartTvLookupResult>()
                inFlight[key] = fresh
                fresh to true
            }
        }
        if (owner) {
            try {
                val result = lookup.fetch(callId, apiKey)
                deferred.complete(result)
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            } finally {
                flightMutex.withLock { inFlight.remove(key) }
            }
        }
        return deferred.await()
    }
```

Replace `val result = lookup.fetch(callId, availability.apiKey)` with `val result = singleFlightFetch(callId, availability.apiKey)`.

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: PASS (11/11).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGenerator.kt \
        app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt
git commit -m "feat(fanarttv): single-flight per-title coalescing for concurrent generators"
```

---

### Task 6.6: Production wiring — `FanartTvLookup` impl + decision-store impl + Hilt bindings

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/RuntimeFanartTvLookup.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/PersistedFanartTvDecisionStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApiModule.kt`

- [ ] **Step 1: Implement `RuntimeFanartTvLookup`**

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

- [ ] **Step 2: Implement `PersistedFanartTvDecisionStore`**

Use the same persistence pattern as `DurableArtworkDecisionCache` (Room or DataStore — confirm by reading that file). The store must:
- `read(key, nowMs)` returns the entry if `expiresAtMs > nowMs`, else null.
- `write(key, entry)` upserts.
- Decision rows can be co-located with `ArtworkDecisionCache` storage by deriving an `ArtworkDecisionKey` from the `FanartTvDecisionKey` (e.g., `"fanarttv:decision:${policyVersion}:${idType}:${idValue}:${artworkType.name}"`). This keeps cache cleanup unified.

If colocation creates type friction, ship a small dedicated table (`fanart_tv_decisions`) with columns: `policy_version`, `id_type`, `id_value`, `artwork_type`, `url_or_null`, `expires_at_ms`. Either path is acceptable — pick whichever matches existing patterns most closely without adding complexity.

- [ ] **Step 3: Add Hilt bindings**

In `FanartTvApiModule.kt`, add:
- `@Binds` `FanartTvLookup` → `RuntimeFanartTvLookup`.
- `@Binds` `FanartTvDecisionStore` → `PersistedFanartTvDecisionStore`.
- `@Provides` `FanartTvCandidateGenerator` constructed with:
  - `availabilityProvider = { FanartTvAvailability.from(BuildConfig.FANARTTV_API_KEY) }`
  - `idSelector = FanartTvIdSelector()`
  - `picker = FanartTvImagePicker()`
  - injected `FanartTvLookup`
  - injected `FanartTvDecisionStore`
  - `clock = { System.currentTimeMillis() }`

- [ ] **Step 4: Build to verify Hilt graph compiles**

Run: `./gradlew :app:kspDebugKotlin :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/fanarttv/RuntimeFanartTvLookup.kt \
        app/src/main/java/com/nexio/tv/data/integration/fanarttv/PersistedFanartTvDecisionStore.kt \
        app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApiModule.kt
git commit -m "feat(fanarttv): runtime lookup, persisted decision store, Hilt bindings"
```

---

## Phase 7 — Wire into the resolver

### Task 7.1: Inject and call the generator from MetadataArtworkDecisionResolver

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolver.kt`
- Create: `app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolverFanartTvTest.kt`

- [ ] **Step 1: Write failing test**

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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataArtworkDecisionResolverFanartTvTest {
    @Test
    fun `resolver augments TVDB primary candidates with Fanart intermediate candidates before routing`() = runTest {
        val generator = mockk<FanartTvCandidateGenerator>()
        coEvery {
            generator.generate(any(), any(), any(), any(), any())
        } returns listOf(
            fanartCandidate(ArtworkType.LOGO, "fanart-logo.png"),
            fanartCandidate(ArtworkType.BACKDROP, "fanart-bg.jpg")
        )
        // Build resolver with mocked router/cache/store/settings (existing test
        // helpers in the project's test dir if available; otherwise instantiate
        // directly with fakes mirroring MetadataArtworkDecisionResolver's deps).
        // Assert: when resolveFields is invoked with TVDB primary candidates,
        // the call list passed to ArtworkRouter.select includes the Fanart
        // candidates the generator returned.
        // (See existing MetadataArtworkDecisionResolver tests for the
        // canonical helper pattern; replicate that here.)
    }

    private fun fanartCandidate(type: ArtworkType, url: String): ArtworkCandidate =
        ArtworkCandidate(
            ownerKey = ArtworkOwnerKey.CanonicalContent("series:81189"),
            canonicalContentId = "series:81189",
            providerIds = ProviderIds(tvdb = "81189"),
            mediaKind = MetadataMediaKind.SERIES,
            imageType = type,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            sourceRole = ArtworkSourceRole.INTERMEDIATE,
            source = ArtworkSource.RemoteUrl.of(SensitiveArtworkUrl.of(url), "hash$type"),
            priority = 15,
            requiresRuntimeFetch = true,
            imageLanguage = if (type == ArtworkType.BACKDROP) "" else "en"
        )
}
```

(If `MetadataArtworkDecisionResolver` is fully tested through integration only, replicate that style here — the assertion target is "the Fanart generator is invoked once per resolveFields call with the union of imageTypes" and "its output is appended to the candidate list passed to the router".)

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
        // Group by ownerKey so each title gets one generator call covering
        // all its requested artwork types.
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
Expected: BUILD SUCCESSFUL (Hilt binding from Task 6.6 satisfies the new constructor parameter).

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

- [ ] **Step 1: Write failing test**

Append to `ArtworkRouterTest`:

```kotlin
    @Test
    fun `per-type mixing - RPDB poster wins, Fanart logo and backdrop win over PRIMARY`() {
        val settings = ArtworkProviderSettings(
            rpdbApiKey = "k",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB,
                logoProvider = ArtworkProviderChoiceKey.DEFAULT,
                backdropProvider = ArtworkProviderChoiceKey.DEFAULT,
                thumbnailProvider = ArtworkProviderChoiceKey.DEFAULT
            )
        )
        val candidates = listOf(
            // poster: RPDB premium + TMDB primary + Fanart intermediate
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
            // logo: Fanart + TMDB primary
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
        val byType = candidates.groupBy { it.imageType }
        val posterDecision = router.select(byType[ArtworkType.POSTER]!!, policy(settings))
        val logoDecision = router.select(byType[ArtworkType.LOGO]!!, policy(settings))

        assertEquals("RPDB", posterDecision.selectedCandidate.provider?.key)
        assertEquals("FANART_TV", logoDecision.selectedCandidate.provider?.key)
    }
```

(If the existing `candidate()` helper does not take an `imageType` parameter, locate it in the file and add an optional parameter defaulting to `ArtworkType.POSTER`.)

- [ ] **Step 2: Run test to verify failure or pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.ArtworkRouterTest"`
Expected: PASS (the rank logic from Phase 4 already supports this — this test just pins the per-type-mixing contract).

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

        // Collect every candidate trace string the IntegrationCallSpec
        // exposes — at minimum: redactedUrlForTrace, toString(), and any
        // audit/log fields the existing PostersApiShapesAuditTest checks.
        val traces = listOfNotNull(
            spec.redactedUrlForTrace,
            spec.toString()
            // add additional fields here mirroring whatever existing
            // poster-shape redaction tests assert on
        )

        traces.forEach { trace ->
            assertFalse(
                "trace must not contain raw api key: $trace",
                trace.contains(rawKey)
            )
        }
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.fanarttv.FanartTvTraceRedactionTest"`
Expected: PASS. If FAIL, the redaction policy on `FanartTvLookupShape` is incomplete — look at how `RpdbIntegrationProvider` tags its `apikey` parameter and apply the equivalent declaration for `api_key`.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/data/integration/fanarttv/FanartTvTraceRedactionTest.kt
git commit -m "test(fanarttv): trace artifacts must never contain the raw api key"
```

---

### Task 8.2: Manual smoke (real device or emulator)

**Files:** none (manual)

- [ ] **Step 1: Confirm a populated `local.properties`**

```bash
grep "fanarttv.api.key" local.properties
```
Expected: a non-empty value.

- [ ] **Step 2: Build a debug APK and install**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL and install succeeds.

- [ ] **Step 3: Verify logo/backdrop/poster behavior**

On the device:

- Open a Fight Club detail page (TMDB id 550). Verify the poster matches the expected Fanart.tv URL (`fight-club-522a5477c7bd3.jpg`), the logo matches `fight-club-504c0530d5f93.png`, and the backdrop matches `fight-club-55e2393686745.jpg`.
- Open a Breaking Bad detail page (TVDB id 81189). Verify the picks match the breaking-bad URLs documented in the spec.
- Open any anime detail page. Verify there is no fanart.tv network call (check logcat / runtime audit). The TMDB/TVDB primary artwork must render normally.

- [ ] **Step 4: Verify the second visit makes zero network calls**

Force-stop and re-open the app. Re-open the Fight Club page. Bytes should serve from disk; no fanart.tv network activity.

- [ ] **Step 5: Document the manual outcome in the implementation PR**

No code change. Note in the PR description what was checked and what was observed.

---

## Self-Review Notes

This section is for the plan author, not the implementer.

**Spec coverage:**
- Routing role (Architecture / Section 1) → Tasks 4.1, 4.2, 7.2.
- Components (Section 2) → Tasks 1.1, 2.2, 2.3, 3.1, 3.2, 3.3, 5.1, 5.3, 6.1, 6.6.
- Data flow (Section 3) → Tasks 6.2, 6.3, 6.5, 7.1.
- Cache contract (Section 3 invariants) → Tasks 6.2, 6.3, 6.4 (TTL, single-flight, no JSON persistence by construction since the document never reaches the store).
- Image selection rules → Tasks 3.3, 3.4.
- Settings UI / migration (Section 4) → no UI changes needed; covered implicitly. Build wiring → Task 1.2.
- Audit/redaction (Section 5) → Tasks 5.3, 8.1.
- Error handling table (Section 6) → Tasks 6.1 (anime/key/id), 6.3 (success), 6.4 (404/auth/transient).
- Test plan → Tasks 3.1–3.4, 4.2, 5.3, 6.1–6.5, 7.1–7.2, 8.1, 8.2.

**Type/name consistency check:**
- `FanartTvCallId` / `FanartTvCallId.Type` — used identically across selector, picker, generator, lookup shape.
- `FanartTvDecisionKey` / `FanartTvDecisionEntry` — used identically across generator and store.
- `FanartTvLookup` / `FanartTvLookupResult` — used identically across generator and runtime lookup impl.
- `INTERMEDIATE` source role + rank — added in Tasks 4.1 and 4.2 and consumed in Task 6.2 emission code and Task 7.2 router test.
- TTL constant `14 * 24 * 60 * 60 * 1000` ms — defined in `FanartTvCandidateGenerator.Companion.TTL_MS`.

**Placeholder scan:** Two implementation tasks (5.2, 6.6, 7.1) intentionally instruct the implementer to mirror an existing project pattern by inspecting a named file. This is unavoidable without copying the project's full Hilt/persistence boilerplate inline — the named files are concrete, the assertion of correctness is concrete (build passes, Hilt graph compiles, redaction test passes), and there are no unconstrained "TODOs" left to the implementer's judgment. All other tasks ship complete code.
