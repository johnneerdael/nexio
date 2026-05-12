# Fanart.tv Peer-Selectable Provider — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Fanart.tv as a 4th `ArtworkProviderChoiceKey` peer to `DEFAULT`, `RPDB`, `TOP_POSTERS`. Selectable per type for poster/logo/backdrop. Anime always falls through to `ContentTypeDefaults`. Non-anime + Fanart can't deliver → empty slot, enforced by the existing surface non-downgrade machinery.

**Architecture:** New `FanartTvCandidateGenerator` augments `MetadataArtworkDecisionResolver`. Generator emits `ArtworkCandidate(provider=FANART_TV, sourceRole=PREMIUM)` when user selected FANART_TV for the type AND capability supports. The existing `ArtworkRouter.isActiveSupportedPremium(...)` picks user-selected PREMIUM candidates over PRIMARY — no router rank change. JSON body cached for 14d in `integration_cache` via `IntegrationRuntime` `CacheFirst`. `ResolvedDisplaySurfaceRepository.preferredAwareSlot` enforces empty-slot when Fanart can't deliver and selection is FANART_TV.

**Tech Stack:** Kotlin · Hilt · Coroutines · Retrofit2 + kotlinx-serialization · JUnit4 · MockK.

**Spec:** `docs/superpowers/specs/2026-05-12-fanarttv-peer-selectable-provider-design.md`.

**Companion docs (do not duplicate work; this plan operates inside the chain they protect):**
- Plan A: `docs/superpowers/plans/2026-05-09-resolved-display-authority.md` — display projection layer.
- Plan B: `docs/superpowers/plans/2026-05-09-resolved-display-ui-consumption-migration.md` — UI consumer migration.

---

## File Structure

**New files** (all under `app/src/main/java/com/nexio/tv/`):

```
core/artwork/fanarttv/
  FanartTvAvailability.kt           # Available(apiKey) | Disabled(reason); reads BuildConfig
  FanartTvIdSelector.kt             # (mediaKind, ProviderIds) → FanartTvCallId | null
  FanartTvImagePicker.kt            # pure: (FanartTvDocument, callType, ArtworkType) → URL | null
  FanartTvCandidateGenerator.kt     # gates → lookup → pick → emit PREMIUM candidates
  FanartTvApiShapes.kt              # const LOOKUP = "fanarttv.lookup"
  FanartTvLookup.kt                 # interface + FanartTvLookupResult sealed
  dto/FanartTvDocument.kt           # @Serializable, six consumed image arrays
  dto/FanartTvImage.kt              # @Serializable, single image entry

data/integration/fanarttv/
  FanartTvApi.kt                    # Retrofit
  FanartTvApiModule.kt              # Hilt: provides FanartTvApi + binds FanartTvLookup + provides Generator
  FanartTvLookupShape.kt            # IntegrationCallSpec with CacheFirst(14d) + api_key redaction
  RuntimeFanartTvLookup.kt          # FanartTvLookup impl (HttpException → typed result)
```

**Test fixtures** (committed):
```
app/src/test/resources/fixtures/fanarttv/
  fight-club-550.json
  breaking-bad-81189.json
```

**Modifications:**
- `app/build.gradle.kts` — add `FANARTTV_API_KEY` BuildConfig field.
- `app/src/main/java/com/nexio/tv/core/integration/IntegrationProvider.kt` — add `FANART_TV`.
- `app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt` — add `FANART_TV` choice key constant; extend `fromStored()` and `toRuntimeProviderId()`.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderRegistry.kt` — add `fanartTvDescriptor` to `artworkProviderDescriptors` list.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolver.kt` — add `descriptor()` branch for FANART_TV; add `fanartTvRejectionReason(...)`.
- `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolver.kt` — inject generator; augment candidate list before routing.

**Unchanged** (despite touching artwork): `PosterRatingsUrlResolver`, `TmdbMetadataService`, `TvdbMetadataService`, `HomeCatalogRefreshCoordinator`. All Fanart-specific logic lives in the new generator path.

**No new persistence** (no Room entity, no DAO, no decision store). JSON cache uses `integration_cache`; per-type URL pinning uses existing `ArtworkDecisionCache`; bytes use existing `ArtworkAssetDiskCache`.

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

- [ ] **Step 2: Build to verify nothing breaks**

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
- Modify: `app/build.gradle.kts` (alongside the existing `buildConfigField` calls around line 283)
- Modify: `local.properties` (developer machine, gitignored)

- [ ] **Step 1: Add the field**

In `app/build.gradle.kts`, locate the existing `buildConfigField("String", "TRAILER_API_URL", ...)` line (around line 284) and add immediately after:

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

Use the exact JSON bodies captured in the spec's "Image Selection Rules" section. Save each at the path above with the full body verbatim.

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

The Json instance must be configured with `ignoreUnknownKeys = true` so unmodeled image arrays are discarded. Configured on `FanartTvApiModule`'s Json provider in Task 4.2.

- [ ] **Step 3: Build**

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

Base URL `https://webservice.fanart.tv/` is wired in the Hilt module in Task 4.2.

- [ ] **Step 2: Build**

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

- [ ] **Step 1: Failing test**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanartTvAvailabilityTest {
    @Test
    fun `available when key is non-blank`() {
        val r = FanartTvAvailability.from("abc123")
        assertTrue(r is FanartTvAvailability.Available)
        assertEquals("abc123", (r as FanartTvAvailability.Available).apiKey)
    }

    @Test
    fun `disabled when key is empty`() {
        assertEquals(FanartTvAvailability.Disabled("no_build_config_key"), FanartTvAvailability.from(""))
    }

    @Test
    fun `disabled when key is blank whitespace`() {
        assertEquals(FanartTvAvailability.Disabled("no_build_config_key"), FanartTvAvailability.from("   "))
    }
}
```

- [ ] **Step 2: Run — fails compile**

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

- [ ] **Step 4: Run — passes**

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

- [ ] **Step 1: Failing test**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FanartTvIdSelectorTest {
    private val selector = FanartTvIdSelector()

    @Test fun `movie with tmdb`() = assertEquals(
        FanartTvCallId(FanartTvCallId.Type.MOVIE, "550"),
        selector.select(MetadataMediaKind.MOVIE, ProviderIds(tmdb = "550"))
    )
    @Test fun `movie without tmdb`() =
        assertNull(selector.select(MetadataMediaKind.MOVIE, ProviderIds(imdb = "tt0137523")))
    @Test fun `series with tvdb`() = assertEquals(
        FanartTvCallId(FanartTvCallId.Type.TV, "81189"),
        selector.select(MetadataMediaKind.SERIES, ProviderIds(tvdb = "81189"))
    )
    @Test fun `series without tvdb`() =
        assertNull(selector.select(MetadataMediaKind.SERIES, ProviderIds(tmdb = "1396")))
    @Test fun `anime always null`() =
        assertNull(selector.select(MetadataMediaKind.ANIME, ProviderIds(tmdb = "1", tvdb = "2")))
    @Test fun `unknown always null`() =
        assertNull(selector.select(MetadataMediaKind.UNKNOWN, ProviderIds(tmdb = "1", tvdb = "2")))
}
```

- [ ] **Step 2: Run — fails compile**

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

- [ ] **Step 4: Run — passes**

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

- [ ] **Step 1: Failing test**

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

    @Test fun `movie logo picks highest-likes en, ignores higher non-en`() {
        val doc = FanartTvDocument(hdMovieLogo = listOf(
            FanartTvImage(id = "1", url = "es.png", lang = "es", likes = "20"),
            FanartTvImage(id = "2", url = "en-hi.png", lang = "en", likes = "8"),
            FanartTvImage(id = "3", url = "en-low.png", lang = "en", likes = "5")
        ))
        assertEquals("en-hi.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test fun `tv logo picks highest-likes en`() {
        val doc = FanartTvDocument(hdTvLogo = listOf(
            FanartTvImage(id = "1", url = "best.png", lang = "en", likes = "24"),
            FanartTvImage(id = "2", url = "ok.png", lang = "en", likes = "10")
        ))
        assertEquals("best.png", picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.LOGO))
    }

    @Test fun `movie backdrop highest-likes any-lang`() {
        val doc = FanartTvDocument(movieBackground = listOf(
            FanartTvImage(id = "1", url = "a.jpg", lang = "", likes = "5"),
            FanartTvImage(id = "2", url = "b.jpg", lang = "", likes = "3")
        ))
        assertEquals("a.jpg", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.BACKDROP))
    }

    @Test fun `tv backdrop highest-likes any-lang`() {
        val doc = FanartTvDocument(showBackground = listOf(
            FanartTvImage(id = "1", url = "x.jpg", lang = "", likes = "12"),
            FanartTvImage(id = "2", url = "y.jpg", lang = "", likes = "10")
        ))
        assertEquals("x.jpg", picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.BACKDROP))
    }

    @Test fun `movie poster highest-likes en`() {
        val doc = FanartTvDocument(moviePoster = listOf(
            FanartTvImage(id = "1", url = "ru.jpg", lang = "ru", likes = "100"),
            FanartTvImage(id = "2", url = "en15.jpg", lang = "en", likes = "15"),
            FanartTvImage(id = "3", url = "en13.jpg", lang = "en", likes = "13")
        ))
        assertEquals("en15.jpg", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.POSTER))
    }

    @Test fun `tv poster highest-likes en`() {
        val doc = FanartTvDocument(tvPoster = listOf(
            FanartTvImage(id = "1", url = "en14.jpg", lang = "en", likes = "14"),
            FanartTvImage(id = "2", url = "en6.jpg", lang = "en", likes = "6")
        ))
        assertEquals("en14.jpg", picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.POSTER))
    }

    @Test fun `null when no en variant for poster`() {
        val doc = FanartTvDocument(moviePoster = listOf(
            FanartTvImage(id = "1", url = "ru.jpg", lang = "ru", likes = "5")
        ))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.POSTER))
    }

    @Test fun `null when no en variant for logo`() {
        val doc = FanartTvDocument(hdMovieLogo = listOf(
            FanartTvImage(id = "1", url = "ru.png", lang = "ru", likes = "5")
        ))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test fun `null when arrays missing`() {
        val doc = FanartTvDocument()
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.BACKDROP))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.POSTER))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.LOGO))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.BACKDROP))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.POSTER))
    }

    @Test fun `null when array empty`() {
        assertNull(picker.pickFor(FanartTvDocument(hdMovieLogo = emptyList()),
            FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test fun `tie-break by ascending id`() {
        val doc = FanartTvDocument(hdMovieLogo = listOf(
            FanartTvImage(id = "200", url = "later.png", lang = "en", likes = "8"),
            FanartTvImage(id = "100", url = "earlier.png", lang = "en", likes = "8")
        ))
        assertEquals("earlier.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test fun `malformed likes treated as zero`() {
        val doc = FanartTvDocument(hdMovieLogo = listOf(
            FanartTvImage(id = "1", url = "good.png", lang = "en", likes = "1"),
            FanartTvImage(id = "2", url = "junk.png", lang = "en", likes = "abc")
        ))
        assertEquals("good.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test fun `null url skipped`() {
        val doc = FanartTvDocument(hdMovieLogo = listOf(
            FanartTvImage(id = "1", url = null, lang = "en", likes = "100"),
            FanartTvImage(id = "2", url = "real.png", lang = "en", likes = "1")
        ))
        assertEquals("real.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test fun `thumbnail returns null`() {
        val doc = FanartTvDocument()
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.THUMBNAIL))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.THUMBNAIL))
    }
}
```

- [ ] **Step 2: Run — fails compile**

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
            ?.sortedWith(compareByDescending<FanartTvImage> { it.likesAsInt() }.thenBy { it.idAsLongOrMax() })
            ?.firstOrNull()
            ?.url

    private fun pickHighestAnyLang(images: List<FanartTvImage>?): String? =
        images
            ?.asSequence()
            ?.filter { it.url?.isNotBlank() == true }
            ?.sortedWith(compareByDescending<FanartTvImage> { it.likesAsInt() }.thenBy { it.idAsLongOrMax() })
            ?.firstOrNull()
            ?.url

    private fun FanartTvImage.likesAsInt(): Int = likes?.toIntOrNull() ?: 0
    private fun FanartTvImage.idAsLongOrMax(): Long = id?.toLongOrNull() ?: Long.MAX_VALUE
}
```

- [ ] **Step 4: Run — passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvImagePickerTest"`
Expected: PASS

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

- [ ] **Step 1: Append fixture loader + tests**

```kotlin
    @Test
    fun `fight club fixture picks expected urls`() {
        val doc = loadFixture("fight-club-550.json")
        assertEquals(
            "https://assets.fanart.tv/fanart/fight-club-504c0530d5f93.png",
            picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/fight-club-55e2393686745.jpg",
            picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.BACKDROP)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/fight-club-522a5477c7bd3.jpg",
            picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.POSTER)
        )
    }

    @Test
    fun `breaking bad fixture picks expected urls`() {
        val doc = loadFixture("breaking-bad-81189.json")
        assertEquals(
            "https://assets.fanart.tv/fanart/breaking-bad-503d6f03d4bfe.png",
            picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.LOGO)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/breaking-bad-4fcb7b24428ba.jpg",
            picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.BACKDROP)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/breaking-bad-5427fc5ebded7.jpg",
            picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.POSTER)
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

- [ ] **Step 2: Run**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvImagePickerTest"`
Expected: PASS (all + 2 new)

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvImagePickerTest.kt
git commit -m "test(fanarttv): pin picker outputs to real fight-club and breaking-bad fixtures"
```

---

## Phase 4 — Runtime wiring

### Task 4.1: Shape constant + Lookup interface

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvApiShapes.kt`
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvLookup.kt`

- [ ] **Step 1: Write shape constant**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

object FanartTvApiShapes {
    const val LOOKUP = "fanarttv.lookup"
}
```

- [ ] **Step 2: Write lookup interface + result types**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument

interface FanartTvLookup {
    suspend fun fetch(callId: FanartTvCallId, apiKey: String): FanartTvLookupResult
}

sealed interface FanartTvLookupResult {
    data class Success(val document: FanartTvDocument) : FanartTvLookupResult
    data object NotFound : FanartTvLookupResult
    data object AuthFailed : FanartTvLookupResult
    data object Transient : FanartTvLookupResult
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvApiShapes.kt \
        app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvLookup.kt
git commit -m "feat(fanarttv): declare LOOKUP shape constant and FanartTvLookup interface"
```

---

### Task 4.2: Hilt module providing FanartTvApi

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApiModule.kt`

- [ ] **Step 1: Inspect an existing analogous module to copy the canonical Retrofit/Hilt pattern**

Run: `grep -rn "@Provides\|baseUrl\|kotlinxSerializationConverter\|asConverterFactory\|ConverterFactory" app/src/main/java/com/nexio/tv/data/integration/tmdb/ | head -20`

Note the project's exact OkHttp+Retrofit+Json factory pattern. Mirror it.

- [ ] **Step 2: Write the module**

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

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

If the project uses a per-provider `OkHttpClient`, inject the same one TMDB or TVDB uses.

- [ ] **Step 3: Build to verify Hilt graph compiles (RuntimeFanartTvLookup added in Task 4.4 — temporarily comment out the `@Binds` if needed)**

Run: `./gradlew :app:kspDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (re-enable the `@Binds` after Task 4.4).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApiModule.kt
git commit -m "feat(fanarttv): provide FanartTvApi via Hilt with fanart.tv base url"
```

---

### Task 4.3: FanartTvLookupShape — runtime call wrapper with CacheFirst(14d) + redaction

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvLookupShape.kt`
- Create: `app/src/test/java/com/nexio/tv/data/integration/fanarttv/FanartTvLookupShapeTest.kt`

- [ ] **Step 1: Inspect an existing shape that uses CacheFirst + query-param redaction**

Run: `grep -rn "CacheFirst\|IntegrationCallSpec\|api_key\|apikey.*redact" app/src/main/java/com/nexio/tv/data/integration/posters/ app/src/main/java/com/nexio/tv/data/integration/tmdb/ | head -30`

Mirror the project's idiomatic shape.

- [ ] **Step 2: Failing test**

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
        val policy = spec.cachePolicy
        check(policy.toString().contains("CacheFirst")) { "expected CacheFirst, got $policy" }
        check(policy.toString().contains(expectedTtl.toString())) {
            "expected ttlMs=$expectedTtl in policy, got $policy"
        }
    }
}
```

- [ ] **Step 3: Run — fails compile**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.fanarttv.FanartTvLookupShapeTest"`
Expected: COMPILATION FAILURE.

- [ ] **Step 4: Implement FanartTvLookupShape**

Build the shape class using the `IntegrationCallSpec` builder pattern from Step 1. Required behavior:
- `specFor(callId, apiKey)` constructs an `IntegrationCallSpec` with:
  - `provider = IntegrationProvider.FANART_TV`
  - `apiShapeId = FanartTvApiShapes.LOOKUP`
  - URL: `https://webservice.fanart.tv/v3.2/movies/{id}` or `/v3.2/tv/{id}` based on `callId.type`
  - `api_key` declared as a redacted query parameter using the same redaction mechanism the analog from Step 1 uses
  - `cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 14L * 24 * 60 * 60 * 1000)`
  - `workClass = IntegrationWorkClass.USER_VISIBLE`
- `suspend fun fetch(callId, apiKey): FanartTvDocument` runs the spec through `IntegrationRuntime`, returns the parsed body, or rethrows `HttpException` / `IOException`.

`@Singleton class FanartTvLookupShape @Inject constructor(private val runtime: IntegrationRuntime, private val api: FanartTvApi)`.

If `IntegrationCallSpec` does not directly expose `redactedUrlForTrace` / `cachePolicy` properties, adjust the test assertions to mirror whatever the existing poster-shape redaction tests assert on. The behavioral goal — raw key never appears in trace, ttl is 14d — must be verified.

**CLAUDE.md rule #3 verification (before committing this task):** confirm the runtime's cached-body read path streams via `JsonReader` / `Json.decodeFromStream` rather than materializing the body as a `String` first. The Fanart.tv response is 35–50 KB for typical titles. If the runtime decodes via `gson.fromJson(String, ...)` or `Json.decodeFromString(String, ...)` after reading the cached blob, that's the banned `StringReader.str`-pinning anti-pattern. Run `grep -rn "fromJson\|decodeFromString\|asString" app/src/main/java/com/nexio/tv/core/integration/ app/src/main/java/com/nexio/tv/data/integration/IntegrationCache* 2>/dev/null | head -20` and confirm the cached-body path uses an `InputStream` / `BufferedReader` source. If it does NOT, this is a pre-existing rule-3 issue affecting all providers — flag it in the PR description as an out-of-scope finding rather than silently introducing a per-Fanart workaround.

- [ ] **Step 5: Run — passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.fanarttv.FanartTvLookupShapeTest"`
Expected: PASS (2/2)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvLookupShape.kt \
        app/src/test/java/com/nexio/tv/data/integration/fanarttv/FanartTvLookupShapeTest.kt
git commit -m "feat(fanarttv): wire lookup through IntegrationRuntime CacheFirst(14d) with api_key redaction"
```

---

### Task 4.4: RuntimeFanartTvLookup adapter

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/RuntimeFanartTvLookup.kt`

- [ ] **Step 1: Write the impl**

```kotlin
package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.FanartTvCallId
import com.nexio.tv.core.artwork.fanarttv.FanartTvLookup
import com.nexio.tv.core.artwork.fanarttv.FanartTvLookupResult
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

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

- [ ] **Step 2: Re-enable the `@Binds` line in `FanartTvApiModule.kt`** (if it was commented out in Task 4.2 Step 3).

- [ ] **Step 3: Build**

Run: `./gradlew :app:kspDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/fanarttv/RuntimeFanartTvLookup.kt \
        app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApiModule.kt
git commit -m "feat(fanarttv): add runtime lookup adapter mapping HttpException to typed result"
```

---

## Phase 5 — Settings-layer integration

### Task 5.1: Add FANART_TV ChoiceKey + extensions

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt`

- [ ] **Step 1: Edit `ArtworkProviderChoiceKey` companion**

Replace:
```kotlin
    companion object {
        val DEFAULT = ArtworkProviderChoiceKey("default")
        val RPDB = ArtworkProviderChoiceKey("rpdb")
        val TOP_POSTERS = ArtworkProviderChoiceKey("top_posters")

        fun fromStored(value: String?): ArtworkProviderChoiceKey = when (value) {
            RPDB.value -> RPDB
            TOP_POSTERS.value -> TOP_POSTERS
            DEFAULT.value -> DEFAULT
            else -> DEFAULT
        }
    }
```
with:
```kotlin
    companion object {
        val DEFAULT = ArtworkProviderChoiceKey("default")
        val RPDB = ArtworkProviderChoiceKey("rpdb")
        val TOP_POSTERS = ArtworkProviderChoiceKey("top_posters")
        val FANART_TV = ArtworkProviderChoiceKey("fanart_tv")

        fun fromStored(value: String?): ArtworkProviderChoiceKey = when (value) {
            RPDB.value -> RPDB
            TOP_POSTERS.value -> TOP_POSTERS
            FANART_TV.value -> FANART_TV
            DEFAULT.value -> DEFAULT
            else -> DEFAULT
        }
    }
```

- [ ] **Step 2: Extend `toRuntimeProviderId()`**

Replace:
```kotlin
fun ArtworkProviderChoiceKey.toRuntimeProviderId(): ArtworkProviderId =
    when (this) {
        ArtworkProviderChoiceKey.RPDB ->
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
        ArtworkProviderChoiceKey.TOP_POSTERS ->
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)
        ArtworkProviderChoiceKey.DEFAULT ->
            throw IllegalArgumentException(
                "DEFAULT must be coerced upstream by ArtworkProviderResolver — never passed to toRuntimeProviderId"
            )
        else ->
            throw IllegalArgumentException(
                "Unknown ArtworkProviderChoiceKey: $value"
            )
    }
```
with:
```kotlin
fun ArtworkProviderChoiceKey.toRuntimeProviderId(): ArtworkProviderId =
    when (this) {
        ArtworkProviderChoiceKey.RPDB ->
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
        ArtworkProviderChoiceKey.TOP_POSTERS ->
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)
        ArtworkProviderChoiceKey.FANART_TV ->
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV)
        ArtworkProviderChoiceKey.DEFAULT ->
            throw IllegalArgumentException(
                "DEFAULT must be coerced upstream by ArtworkProviderResolver — never passed to toRuntimeProviderId"
            )
        else ->
            throw IllegalArgumentException(
                "Unknown ArtworkProviderChoiceKey: $value"
            )
    }
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt
git commit -m "feat(model): add ArtworkProviderChoiceKey.FANART_TV + toRuntimeProviderId branch"
```

---

### Task 5.2: Add fanartTvDescriptor to registry

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderRegistry.kt`

- [ ] **Step 1: Add the descriptor and append to the list**

Add after the `topPostersDescriptor` block (around line 93):

```kotlin
internal val fanartTvDescriptor = ArtworkProviderDescriptor(
    choice = ArtworkProviderChoiceKey.FANART_TV,
    provider = IntegrationProvider.FANART_TV,
    supportedArtworkTypes = setOf(
        ArtworkType.POSTER,
        ArtworkType.LOGO,
        ArtworkType.BACKDROP
    ),
    supportedIdTypes = setOf(
        ArtworkProviderStableIdType.TMDB,
        ArtworkProviderStableIdType.TVDB
    ),
    embedsRatings = false,
    isConfigured = { _ ->
        com.nexio.tv.core.artwork.fanarttv.FanartTvAvailability
            .from(com.nexio.tv.BuildConfig.FANARTTV_API_KEY)
                is com.nexio.tv.core.artwork.fanarttv.FanartTvAvailability.Available
    }
)
```

Then change the descriptor list:
```kotlin
internal val artworkProviderDescriptors = listOf(
    topPostersDescriptor,
    rpdbDescriptor,
    fanartTvDescriptor
)
```

- [ ] **Step 2: Add registry test for FANART_TV exposure**

Modify `app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderRegistryTest.kt` — append (adjusting helper imports/fakes per existing test style):

```kotlin
    @Test
    fun `FANART_TV is offered for poster, logo, backdrop when build key non-blank`() {
        // Note: this test reads BuildConfig.FANARTTV_API_KEY at runtime.
        // For deterministic CI, it should be parameterized via a test BuildConfig.
        // If the existing test infra doesn't support that, mark this @Ignore with a
        // pointer to the manual smoke step in Task 8.2 instead.
        val settings = ArtworkProviderSettings()
        val registry = ArtworkProviderRegistry()
        if (com.nexio.tv.BuildConfig.FANARTTV_API_KEY.isNotBlank()) {
            assertTrue(ArtworkProviderChoiceKey.FANART_TV in registry.availableChoices(ArtworkType.POSTER, settings))
            assertTrue(ArtworkProviderChoiceKey.FANART_TV in registry.availableChoices(ArtworkType.LOGO, settings))
            assertTrue(ArtworkProviderChoiceKey.FANART_TV in registry.availableChoices(ArtworkType.BACKDROP, settings))
            assertFalse(ArtworkProviderChoiceKey.FANART_TV in registry.availableChoices(ArtworkType.THUMBNAIL, settings))
        } else {
            assertFalse(ArtworkProviderChoiceKey.FANART_TV in registry.availableChoices(ArtworkType.POSTER, settings))
        }
    }
```

- [ ] **Step 3: Build + run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.ArtworkProviderRegistryTest"`
Expected: PASS (existing + new)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderRegistry.kt \
        app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderRegistryTest.kt
git commit -m "feat(artwork-registry): expose FANART_TV for poster/logo/backdrop when build key set"
```

---

### Task 5.3: Extend ArtworkProviderCapabilityResolver

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolver.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolverTest.kt`

- [ ] **Step 1: Failing tests**

Append to `ArtworkProviderCapabilityResolverTest`:

```kotlin
    @Test
    fun `FANART_TV + ANIME rejected with anime_unsupported_for_fanart_tv`() {
        val settings = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        val reason = resolver.evaluate(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            imageType = ArtworkType.POSTER,
            ids = ProviderIds(tmdb = "550", tvdb = "1"),
            mediaKind = MetadataMediaKind.ANIME,
            settings = settings
        ).reason
        assertEquals("anime_unsupported_for_fanart_tv", reason)
    }

    @Test
    fun `FANART_TV + MOVIE without TMDB rejected`() {
        val settings = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        val reason = resolver.evaluate(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            imageType = ArtworkType.POSTER,
            ids = ProviderIds(imdb = "tt0137523"),
            mediaKind = MetadataMediaKind.MOVIE,
            settings = settings
        ).reason
        assertEquals("missing_supported_provider_id", reason)
    }

    @Test
    fun `FANART_TV + SERIES without TVDB rejected`() {
        val settings = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        val reason = resolver.evaluate(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            imageType = ArtworkType.POSTER,
            ids = ProviderIds(tmdb = "1396"),
            mediaKind = MetadataMediaKind.SERIES,
            settings = settings
        ).reason
        assertEquals("missing_supported_provider_id", reason)
    }

    @Test
    fun `FANART_TV + MOVIE with TMDB + non-anime supported`() {
        val settings = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        val capable = resolver.evaluate(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            imageType = ArtworkType.POSTER,
            ids = ProviderIds(tmdb = "550"),
            mediaKind = MetadataMediaKind.MOVIE,
            settings = settings
        )
        assertTrue(capable.supported)
        assertNull(capable.reason)
    }

    @Test
    fun `FANART_TV + SERIES with TVDB + non-anime supported`() {
        val settings = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                logoProvider = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        val capable = resolver.evaluate(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            imageType = ArtworkType.LOGO,
            ids = ProviderIds(tvdb = "81189"),
            mediaKind = MetadataMediaKind.SERIES,
            settings = settings
        )
        assertTrue(capable.supported)
    }

    @Test
    fun `FANART_TV + THUMBNAIL rejected as unsupported_artwork_type_for_provider`() {
        val settings = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                thumbnailProvider = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        val reason = resolver.evaluate(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            imageType = ArtworkType.THUMBNAIL,
            ids = ProviderIds(tvdb = "81189"),
            mediaKind = MetadataMediaKind.SERIES,
            settings = settings
        ).reason
        assertEquals("unsupported_artwork_type_for_provider", reason)
    }
```

- [ ] **Step 2: Run — fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolverTest"`
Expected: 6 new tests fail.

- [ ] **Step 3: Implement extensions**

In `ArtworkProviderCapabilityResolver.kt`:

(a) Extend `descriptor()`:

```kotlin
    private fun ArtworkProviderId.RuntimeProvider.descriptor(): ArtworkProviderDescriptor? =
        when (providerId) {
            IntegrationProvider.RPDB -> rpdbDescriptor
            IntegrationProvider.TOP_POSTERS -> topPostersDescriptor
            IntegrationProvider.FANART_TV -> fanartTvDescriptor
            else -> null
        }
```

(b) Extend `settingsAwareRejectionReason` switch — replace:

```kotlin
        return when (providerId) {
            IntegrationProvider.RPDB -> rpdbRejectionReason(imageType, ids, settings)
            IntegrationProvider.TOP_POSTERS -> topPostersRejectionReason(imageType, ids, settings)
            else -> null
        }
```
with:
```kotlin
        return when (providerId) {
            IntegrationProvider.RPDB -> rpdbRejectionReason(imageType, ids, settings)
            IntegrationProvider.TOP_POSTERS -> topPostersRejectionReason(imageType, ids, settings)
            IntegrationProvider.FANART_TV -> fanartTvRejectionReason(imageType, ids, mediaKindFromSelection(settings, imageType, ids))
            else -> null
        }
```

(Note: `settingsAwareRejectionReason` does not currently receive `mediaKind` directly. The shape of this fix depends on the resolver's call signature — pass `mediaKind` through if available, otherwise rely on the broader `evaluate(...)` path that already takes `mediaKind`. The simpler refactor: thread `mediaKind` into `settingsAwareRejectionReason` by extending its signature. Adjust calling sites in the same file accordingly.)

(c) Add the rejection function:

```kotlin
    private fun fanartTvRejectionReason(
        imageType: ArtworkType,
        ids: ProviderIds,
        mediaKind: MetadataMediaKind
    ): String? {
        // Build-time configured?
        val available = com.nexio.tv.core.artwork.fanarttv.FanartTvAvailability
            .from(com.nexio.tv.BuildConfig.FANARTTV_API_KEY)
        if (available !is com.nexio.tv.core.artwork.fanarttv.FanartTvAvailability.Available) {
            return "fanart_tv_not_configured"
        }
        if (imageType !in fanartTvDescriptor.supportedArtworkTypes) {
            return "unsupported_artwork_type_for_provider"
        }
        if (mediaKind == MetadataMediaKind.ANIME) {
            return "anime_unsupported_for_fanart_tv"
        }
        return when (mediaKind) {
            MetadataMediaKind.MOVIE ->
                if (ids.tmdb.isNullOrBlank()) "missing_supported_provider_id" else null
            MetadataMediaKind.SERIES ->
                if (ids.tvdb.isNullOrBlank()) "missing_supported_provider_id" else null
            else -> "missing_supported_provider_id"
        }
    }
```

If passing `mediaKind` into `settingsAwareRejectionReason` requires too much surgery on existing call sites, the alternative is to add a separate `fanartTvCompatibilityRejectionReason` invoked from the no-settings rejection path that accepts `mediaKind`. Pick whichever is the smaller diff against the existing file shape.

- [ ] **Step 4: Run — pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolverTest"`
Expected: PASS (all)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolver.kt \
        app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolverTest.kt
git commit -m "feat(artwork-capability): reject FANART_TV for anime + missing-id; add descriptor branch"
```

---

### Task 5.4: ArtworkProviderResolver decision tests for FANART_TV

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderResolverTest.kt`

- [ ] **Step 1: Append tests**

```kotlin
    @Test
    fun `FANART_TV explicit + capable returns FANART_TV`() {
        val settings = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        val chosen = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.MOVIE,
            isAnime = false,
            availableIds = ProviderIds(tmdb = "550"),
            settings = settings
        )
        assertEquals("FANART_TV", (chosen as ArtworkProviderId.RuntimeProvider).providerId.name)
    }

    @Test
    fun `FANART_TV explicit + ANIME falls through to ContentTypeDefaults (ADDON)`() {
        val settings = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        val chosen = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.SERIES,
            isAnime = true,
            availableIds = ProviderIds(tvdb = "1"),
            settings = settings
        )
        assertEquals("ADDON", (chosen as ArtworkProviderId.RuntimeProvider).providerId.name)
    }

    @Test
    fun `FANART_TV explicit + missing TMDB id falls through to ADDON`() {
        val settings = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        val chosen = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.MOVIE,
            isAnime = false,
            availableIds = ProviderIds(imdb = "tt0137523"),
            settings = settings
        )
        assertEquals("ADDON", (chosen as ArtworkProviderId.RuntimeProvider).providerId.name)
    }
```

- [ ] **Step 2: Run — pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.ArtworkProviderResolverTest"`
Expected: PASS (existing + 3 new)

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderResolverTest.kt
git commit -m "test(artwork-resolver): pin FANART_TV decision behavior + capability fall-through"
```

---

## Phase 6 — Candidate generator

### Task 6.1: FanartTvCandidateGenerator

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGenerator.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt`

- [ ] **Step 1: Failing test**

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
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
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
    private val capabilityResolver = com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolver()
    private fun gen(availability: FanartTvAvailability) = FanartTvCandidateGenerator(
        availabilityProvider = { availability },
        idSelector = FanartTvIdSelector(),
        picker = FanartTvImagePicker(),
        lookup = lookup,
        capabilityResolver = capabilityResolver
    )

    private val ownerKey = ArtworkOwnerKey.CanonicalContent("movie:550")
    private val movieIds = ProviderIds(tmdb = "550")

    private fun settingsWith(
        poster: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
        logo: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
        backdrop: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT
    ) = ArtworkProviderSettings(
        selection = ArtworkProviderSelectionSettings(
            posterProvider = poster, logoProvider = logo, backdropProvider = backdrop
        )
    )

    @Test
    fun `disabled key emits zero, no lookup`() = runTest {
        val out = gen(FanartTvAvailability.Disabled("no_build_config_key")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            settings = settingsWith(poster = ArtworkProviderChoiceKey.FANART_TV)
        )
        assertTrue(out.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }

    @Test
    fun `no type has FANART_TV selected emits zero, no lookup`() = runTest {
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            settings = settingsWith()  // all DEFAULT
        )
        assertTrue(out.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }

    @Test
    fun `anime + FANART_TV selected emits zero, no lookup`() = runTest {
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "anime:1", MetadataMediaKind.ANIME, ProviderIds(tvdb = "1", tmdb = "1"),
            requestedTypes = setOf(ArtworkType.POSTER),
            settings = settingsWith(poster = ArtworkProviderChoiceKey.FANART_TV)
        )
        assertTrue(out.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }

    @Test
    fun `missing usable id + FANART_TV selected emits zero, no lookup`() = runTest {
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:noid", MetadataMediaKind.MOVIE, ProviderIds(imdb = "tt0137523"),
            requestedTypes = setOf(ArtworkType.POSTER),
            settings = settingsWith(poster = ArtworkProviderChoiceKey.FANART_TV)
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
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            settings = settingsWith(
                poster = ArtworkProviderChoiceKey.FANART_TV,
                logo = ArtworkProviderChoiceKey.FANART_TV,
                backdrop = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        coVerify(exactly = 1) { lookup.fetch(any(), any()) }
        assertEquals(3, out.size)
        assertEquals(
            setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            out.map { it.imageType }.toSet()
        )
        assertTrue(out.all { it.sourceRole == ArtworkSourceRole.PREMIUM })
        assertTrue(out.all {
            it.provider == ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV)
        })
    }

    @Test
    fun `partial picker outputs emit only present types`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Success(
            FanartTvDocument(
                moviePoster = listOf(FanartTvImage(id = "1", url = "p.jpg", lang = "en", likes = "1"))
            )
        )
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            settings = settingsWith(
                poster = ArtworkProviderChoiceKey.FANART_TV,
                logo = ArtworkProviderChoiceKey.FANART_TV,
                backdrop = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        assertEquals(1, out.size)
        assertEquals(ArtworkType.POSTER, out.single().imageType)
    }

    @Test
    fun `mixed selection only emits for FANART_TV-selected types`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Success(
            FanartTvDocument(
                hdMovieLogo = listOf(FanartTvImage(id = "1", url = "logo.png", lang = "en", likes = "8")),
                movieBackground = listOf(FanartTvImage(id = "2", url = "back.jpg", lang = "", likes = "5")),
                moviePoster = listOf(FanartTvImage(id = "3", url = "poster.jpg", lang = "en", likes = "15"))
            )
        )
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            settings = settingsWith(
                poster = ArtworkProviderChoiceKey.FANART_TV,
                logo = ArtworkProviderChoiceKey.DEFAULT,
                backdrop = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        coVerify(exactly = 1) { lookup.fetch(any(), any()) }
        assertEquals(setOf(ArtworkType.POSTER, ArtworkType.BACKDROP), out.map { it.imageType }.toSet())
    }

    @Test
    fun `404 emits zero candidates`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.NotFound
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER),
            settings = settingsWith(poster = ArtworkProviderChoiceKey.FANART_TV)
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `auth failure emits zero`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.AuthFailed
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER),
            settings = settingsWith(poster = ArtworkProviderChoiceKey.FANART_TV)
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `transient emits zero`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Transient
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER),
            settings = settingsWith(poster = ArtworkProviderChoiceKey.FANART_TV)
        )
        assertTrue(out.isEmpty())
    }
}
```

- [ ] **Step 2: Run — fails compile**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implementation**

```kotlin
package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolver
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.artwork.toSettingsKey
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ProviderIds
import java.security.MessageDigest
import javax.inject.Inject

class FanartTvCandidateGenerator @Inject constructor(
    private val availabilityProvider: () -> FanartTvAvailability,
    private val idSelector: FanartTvIdSelector,
    private val picker: FanartTvImagePicker,
    private val lookup: FanartTvLookup,
    private val capabilityResolver: ArtworkProviderCapabilityResolver
) {
    suspend fun generate(
        ownerKey: ArtworkOwnerKey,
        canonicalContentId: String?,
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds,
        requestedTypes: Set<ArtworkType>,
        settings: ArtworkProviderSettings
    ): List<ArtworkCandidate> {
        val availability = availabilityProvider() as? FanartTvAvailability.Available
            ?: return emptyList()

        val typesToTry = requestedTypes
            .filter { it != ArtworkType.THUMBNAIL }
            .filter { settings.selection.providerFor(it.toSettingsKey()) == ArtworkProviderChoiceKey.FANART_TV }
            .filter { type ->
                capabilityResolver.evaluate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
                    imageType = type,
                    ids = providerIds,
                    mediaKind = mediaKind,
                    settings = settings
                ).supported
            }
        if (typesToTry.isEmpty()) return emptyList()

        val callId = idSelector.select(mediaKind, providerIds) ?: return emptyList()

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
            sourceRole = ArtworkSourceRole.PREMIUM,
            source = source,
            priority = PRIORITY,
            requiresRuntimeFetch = true,
            imageLanguage = "en"
        )
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    companion object {
        const val PRIORITY = 15
    }
}
```

- [ ] **Step 4: Run — pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGeneratorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGenerator.kt \
        app/src/test/java/com/nexio/tv/core/artwork/fanarttv/FanartTvCandidateGeneratorTest.kt
git commit -m "feat(fanarttv): stateless candidate generator (gates → lookup → pick → emit PREMIUM)"
```

---

### Task 6.2: Hilt provider for the generator

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/fanarttv/FanartTvApiModule.kt`

- [ ] **Step 1: Add `@Provides` for the generator**

Inside the `companion object` block:

```kotlin
        @Provides
        @Singleton
        fun provideFanartTvCandidateGenerator(
            lookup: com.nexio.tv.core.artwork.fanarttv.FanartTvLookup,
            capabilityResolver: com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolver
        ): com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGenerator =
            com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGenerator(
                availabilityProvider = {
                    com.nexio.tv.core.artwork.fanarttv.FanartTvAvailability.from(
                        com.nexio.tv.BuildConfig.FANARTTV_API_KEY
                    )
                },
                idSelector = com.nexio.tv.core.artwork.fanarttv.FanartTvIdSelector(),
                picker = com.nexio.tv.core.artwork.fanarttv.FanartTvImagePicker(),
                lookup = lookup,
                capabilityResolver = capabilityResolver
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

## Phase 7 — MetadataArtworkDecisionResolver augmentation

### Task 7.1: Inject and call the generator from the resolver

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolver.kt`
- Create: `app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolverFanartTvTest.kt`

- [ ] **Step 1: Failing test**

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
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MetadataArtworkDecisionResolverFanartTvTest {
    @Test
    fun `resolveFields invokes generator once per ownerKey with the union of imageTypes`() = runTest {
        val generator = mockk<FanartTvCandidateGenerator>()
        coEvery {
            generator.generate(any(), any(), any(), any(), any(), any())
        } returns emptyList()

        // Construct the resolver per the project's existing test pattern (mirror
        // the helper-construction style of any sibling test in this dir if one
        // exists; otherwise instantiate directly with mockk fakes for
        // ArtworkRouter, ArtworkDecisionCache, ArtworkRemoteSourceStore, and
        // ArtworkProviderSettingsSource. The new constructor parameter for the
        // generator must be present.)

        val ownerKey = ArtworkOwnerKey.CanonicalContent("series:81189")
        val candidates = listOf(
            primary(ownerKey, ArtworkType.POSTER, "p.jpg"),
            primary(ownerKey, ArtworkType.BACKDROP, "b.jpg")
        )
        // resolver.resolveFields(candidates)

        val typesSlot = slot<Set<ArtworkType>>()
        coVerify(exactly = 1) {
            generator.generate(
                ownerKey = ownerKey,
                canonicalContentId = any(),
                mediaKind = MetadataMediaKind.SERIES,
                providerIds = any(),
                requestedTypes = capture(typesSlot),
                settings = any()
            )
        }
        assert(typesSlot.captured == setOf(ArtworkType.POSTER, ArtworkType.BACKDROP))
    }

    private fun primary(ownerKey: ArtworkOwnerKey, type: ArtworkType, url: String): ArtworkCandidate =
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

(Replace the comment-driven scaffold with the project's actual resolver-test construction pattern. Adapt parameter ordering per the actual `MetadataArtworkDecisionResolver` constructor.)

- [ ] **Step 2: Run — fails compile**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.metadata.MetadataArtworkDecisionResolverFanartTvTest"`
Expected: COMPILATION FAILURE (resolver constructor doesn't yet take a generator).

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

(b) Augment the candidate list at the top of `resolveFields(...)`:

```kotlin
    suspend fun resolveFields(
        candidates: List<ArtworkCandidate>
    ): Map<ResolvedField, FieldValue> {
        if (candidates.isEmpty()) return emptyMap()

        val settings = settingsSource.settings.first()
        val withFanart = augmentWithFanart(candidates, settings)
        val policy = ArtworkRoutingPolicy(settings = settings)
        return withFanart
            .groupBy { candidate -> candidate.imageType }
            // ... rest unchanged
    }

    private suspend fun augmentWithFanart(
        candidates: List<ArtworkCandidate>,
        settings: ArtworkProviderSettings
    ): List<ArtworkCandidate> {
        // CLAUDE.md rule #4: don't iterate a Map via forEach inside a suspend fun —
        // Map.forEach's EntryIterator gets pinned in the continuation across
        // fanartGenerator.generate(...)'s suspension. Materialize the entries as
        // an indexed list and iterate with an indexed for loop.
        val ownerEntries = candidates.groupBy { it.ownerKey }.entries.toList()
        val additions = mutableListOf<ArtworkCandidate>()
        for (i in ownerEntries.indices) {
            val entry = ownerEntries[i]
            val ownerKey = entry.key
            val perOwnerCandidates = entry.value
            val sample = perOwnerCandidates.first()
            val requestedTypes = perOwnerCandidates
                .map { it.imageType }
                .filter { it != ArtworkType.THUMBNAIL }
                .toSet()
            if (requestedTypes.isEmpty()) continue
            additions += fanartGenerator.generate(
                ownerKey = ownerKey,
                canonicalContentId = sample.canonicalContentId,
                mediaKind = sample.mediaKind,
                providerIds = sample.providerIds,
                requestedTypes = requestedTypes,
                settings = settings
            )
        }
        return candidates + additions
    }
```

Add the necessary import:
```kotlin
import com.nexio.tv.domain.model.ArtworkProviderSettings
```

**CLAUDE.md rule #4 compliance:** the body uses an indexed for-loop over `ownerEntries: List<Map.Entry<...>>` instead of `Map.forEach { ... suspend ... }`. The indexed-for compiles to a primitive int counter — no `EntryIterator` allocation, nothing pinned in `generate(...)`'s continuation. (`Iterable.forEach` / `Map.forEach` over a suspending lambda is the banned anti-pattern.)

Note: `settingsSource.settings.first()` was already called inside `resolveFields`. Hoist it once at the top so both `augmentWithFanart` and the existing routing policy share the same snapshot — avoids two separate Flow collections per call.

- [ ] **Step 4: Run — pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.metadata.MetadataArtworkDecisionResolverFanartTvTest"`
Expected: PASS

- [ ] **Step 5: Build to confirm production graph**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (Hilt binding from Task 6.2 satisfies the new constructor parameter).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolver.kt \
        app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolverFanartTvTest.kt
git commit -m "feat(metadata): augment resolver candidates with FANART_TV PREMIUM emissions"
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
        val traces = listOfNotNull(
            spec.redactedUrlForTrace,
            spec.toString()
        )
        traces.forEach { trace ->
            assertFalse("trace must not contain raw key, was: $trace", trace.contains(rawKey))
        }
    }
}
```

- [ ] **Step 2: Run — pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.fanarttv.FanartTvTraceRedactionTest"`
Expected: PASS. If FAIL, the redaction policy on `FanartTvLookupShape` is incomplete — copy the redaction declaration from the analogous shape used in Task 4.3 Step 1.

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

- [ ] **Step 2: Build and install**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL and install succeeds.

- [ ] **Step 3: Launch the app and select a profile**

CLAUDE.md rule #8 — the profile picker is NOT the home screen. Every home-pipeline smoke must select a profile first:

```bash
ADB_TARGET="${ADB_TARGET:-$(adb devices | sed -n '2p' | cut -f1)}"
adb -s "$ADB_TARGET" shell am force-stop com.nexiodebug.tv
adb -s "$ADB_TARGET" logcat -c
adb -s "$ADB_TARGET" shell monkey -p com.nexiodebug.tv 1
sleep 5                                                              # profile picker renders
adb -s "$ADB_TARGET" shell input keyevent KEYCODE_DPAD_CENTER       # tap focused profile
sleep 30                                                             # home loads + rails populate
```

Expected: home screen loads with rails populated; no FATAL/ANR/AndroidRuntime errors in `adb logcat -d -t 600`.

- [ ] **Step 4: Settings dialog presence**

On the device, navigate Home → Settings → Poster Ratings. Verify the Poster, Logo, and Backdrop selectors offer "Fanart.tv" as an option. Verify the Thumbnail selector does **not** offer Fanart.tv.

- [ ] **Step 5: Movie behavior**

Select Fanart.tv for poster, logo, backdrop. Open a Fight Club detail page (TMDB id 550). Verify:
- Poster URL = `https://assets.fanart.tv/fanart/fight-club-522a5477c7bd3.jpg`
- Logo URL = `https://assets.fanart.tv/fanart/fight-club-504c0530d5f93.png`
- Backdrop URL = `https://assets.fanart.tv/fanart/fight-club-55e2393686745.jpg`

- [ ] **Step 6: TV behavior**

Open Breaking Bad detail page (TVDB id 81189). Verify:
- Poster = `https://assets.fanart.tv/fanart/breaking-bad-5427fc5ebded7.jpg`
- Logo = `https://assets.fanart.tv/fanart/breaking-bad-503d6f03d4bfe.png`
- Backdrop = `https://assets.fanart.tv/fanart/breaking-bad-4fcb7b24428ba.jpg`

- [ ] **Step 7: Anime fallback**

Open any anime title with Fanart.tv selected for poster. Verify the addon-side poster renders (not blank, not Fanart). Logcat / runtime audit should show no `fanarttv.lookup` call for that item.

- [ ] **Step 8: Missing-id fallback**

Find a movie that has no TMDB id (or simulate by clearing TMDB id in stableIds for one test item). Verify the poster falls back to the addon-side image.

- [ ] **Step 9: Empty-slot for non-anime + Fanart no-en**

Find a non-anime title that Fanart.tv covers with no en-variant logo (or a title not in fanart.tv). Verify the logo slot is empty (not the addon logo). This is the strict-honor contract per Q1.

- [ ] **Step 10: Cache hit on repeat**

Force-stop, relaunch, re-select the profile (rule #8), wait for home + rails to load. Open the same Fight Club detail page. Verify there is no new `fanarttv.lookup` runtime call (cached JSON body) and no new image bytes call (disk cache hit).

- [ ] **Step 11: Toggle off**

Switch all three selectors back to Default. Verify all visible items re-hydrate (`markStaleAll` from `ArtworkSettingsInvalidator`) and revert to the prior provider's URLs.

- [ ] **Step 12: Verify api_key participation in IntegrationCallSpec equality**

If the build-config key changes (e.g. you fix a typo in `local.properties`), confirm the next resolver call hits the network rather than serving a stale cached response under the bad key. If `api_key` is not part of `IntegrationCallSpec` equality, add a one-line `markStaleAll` on app boot when the build-config key differs from a persisted last-seen key. Verify by changing the key in `local.properties`, rebuilding, restarting (with profile re-select), and observing a fresh network call.

- [ ] **Step 13: Document the manual outcome in the implementation PR**

No code change. Note in the PR description what was checked and what was observed.

---

## Self-Review Notes

This section is for the plan author, not the implementer.

**Spec coverage:**
- Architecture (peer-selectable, PREMIUM rank, no router change) → Tasks 5.1–5.4, 6.1, 7.1.
- Components (12 new files, 6 modified) → Tasks 1.1–1.2, 2.1–2.3, 3.1–3.4, 4.1–4.4, 5.1–5.3, 6.1–6.2, 7.1.
- Data flow (settings change → invalidator → re-hydration → resolver augmentation → router → surface non-downgrade) → Tasks 5.1, 6.1, 7.1; surface non-downgrade is existing infra (no task needed).
- Cache layers (integration_cache via CacheFirst 14d; existing decision cache; existing asset cache) → Task 4.3.
- Image selection rules → Tasks 3.3, 3.4.
- Settings UI (no UI change; registry-driven) → Task 5.2.
- Audit/redaction → Tasks 4.3, 8.1.
- Error handling table (capability layer + generator layer) → Tasks 5.3, 6.1, surface non-downgrade is existing.
- Test plan → Tasks 3.1–3.4, 4.3, 5.2–5.4, 6.1, 7.1, 8.1, 8.2.

**Type/name consistency:**
- `FanartTvCallId` / `FanartTvCallId.Type` — used identically across selector, picker, lookup interface, generator.
- `FanartTvLookup` / `FanartTvLookupResult` — used identically across generator and runtime adapter.
- `ArtworkProviderChoiceKey.FANART_TV` constant — referenced consistently in registry, capability, generator.
- `IntegrationProvider.FANART_TV` — used across registry descriptor, capability, generator candidate construction, lookup shape.
- TTL constant `14L * 24 * 60 * 60 * 1000` ms — declared on `FanartTvLookupShape.cachePolicy` (Task 4.3); no other code references it.
- Source role: `ArtworkSourceRole.PREMIUM` (not INTERMEDIATE) — used in generator emission and consumer-wiring guarantees. No router rank changes.

**Placeholder scan:** Three tasks (4.2, 4.3, 7.1) instruct the implementer to mirror an existing project pattern by inspecting a named file. The named files are concrete; the assertion of correctness is concrete (build passes, redaction test passes, augmentation test passes). All other tasks ship complete code.

**Removed from prior plan revision (now invented/unnecessary):**
- `FanartTvArtworkResolver` (renamed to `FanartTvCandidateGenerator`).
- Consumer wiring at TmdbMetadataService / TvdbMetadataService / HomeCatalogRefreshCoordinator — those files are unchanged.
- Custom URL-fetch-then-set-slot path at hydration time — replaced by candidate-generator + existing router + existing surface non-downgrade.
- INTERMEDIATE source role and router rank shift — not needed; PREMIUM rank with user-selection match handles it.

---

## CLAUDE.md Hard-Rule Compliance Audit

This plan was audited against the eight project-wide invariants in `CLAUDE.md` before finalization.

| Rule | Status | Notes |
|---|---|---|
| #1 Display authority — first paint never downgrades | ✓ compliant | We use `preferredArtworkProviders` + `preferredAwareSlot` (the existing reducer / surface non-downgrade machinery) to enforce empty-slot for non-anime when Fanart can't deliver. We do NOT add new "if poster null fall back to backdrop" logic anywhere. The candidate generator emits PREMIUM candidates that flow through the existing router → decision cache → asset repo → surface repo chain. |
| #2 State retention — no hot lists in observed UiState | ✓ compliant | No new `HomeUiState` fields. Generator returns a `List<ArtworkCandidate>` to `MetadataArtworkDecisionResolver` (not Compose-observed). |
| #3 Persistence — no large blobs in SharedPreferences; stream JSON | ✓ compliant by design, with verification flagged | We use `IntegrationRuntime.CacheFirst(14d)` → `integration_cache.blobPath` (file on disk), the standard provider path. We do NOT write to SharedPreferences or DataStore. Task 4.3 Step 4 includes an explicit verification that the cached-body read path streams via `JsonReader` / `Json.decodeFromStream` rather than materializing the body as a `String` first — Fanart.tv responses are 35–50 KB. |
| #4 Coroutines — no suspending forEach over lists | ✓ compliant | `FanartTvCandidateGenerator.generate(...)` uses synchronous `filter`/`mapNotNull` lambdas only (capability-resolver and picker calls are non-suspending). `MetadataArtworkDecisionResolver.augmentWithFanart(...)` materializes `groupBy` entries to a `List` and iterates via `for (i in ownerEntries.indices)` — no `Map.forEach { ... suspend ... }`, no `EntryIterator` pinning across `generate(...)`'s suspension. |
| #5 Memoization — at every reference-fresh boundary | ✓ compliant | Generator output is consumed by `ArtworkRouter.select` and persisted via `ArtworkDecisionCache` keyed by content. Not exposed to Compose. No new reference-fresh boundary added. |
| #6 Coroutines — don't pin large values as outer-fun locals across fan-out | ✓ compliant | `augmentWithFanart` is sequential; no `supervisorScope` / `coroutineScope` fan-out. `generate(...)`'s outer locals are tiny (availability sealed type, max-3-element `typesToTry: List<ArtworkType>`, `callId: FanartTvCallId` data class). |
| #7 Git staging — NEVER sweep up work that isn't yours | ✓ compliant | Every commit step in this plan stages explicit file paths only — no `git add -A`, no `git add .`, no `git commit -a`. |
| #8 Smoke tests — profile picker is NOT the home screen | ✓ compliant | Task 8.2 manual smoke includes an explicit "launch + select profile + wait for rails" step (Step 3) before any home-pipeline observation. Step 10 (cache hit on repeat) also re-selects the profile after relaunch. |
