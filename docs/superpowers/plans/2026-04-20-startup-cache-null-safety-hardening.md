# Startup Cache Null-Safety Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent release-build startup/profile-selection crashes caused by legacy persisted JSON hydrating new Kotlin non-null fields as `null`.

**Architecture:** Add focused compatibility normalization at persistence boundaries instead of relying on Kotlin data-class constructor defaults after Gson reflection. Build small, reusable sanitizers for metadata models that appear in startup snapshots (`MetaPreview`, `Meta`, `HomeDisplayMetadata`, `TmdbEnrichment`, `TvMetadataEnrichment`) and apply them in every startup cache/snapshot store that deserializes those models. Keep schema-specific invalidation intact and avoid clearing user data unless a snapshot is structurally unusable.

**Tech Stack:** Kotlin, Android SharedPreferences/DataStore, Gson, Gradle unit tests, existing snapshot/cache stores.

---

## Root Cause And Risk Summary

The confirmed crash is not profile-specific. It happens when release builds read pre-existing JSON/cache created before a new non-null Kotlin property existed. Gson can instantiate Kotlin data classes without calling the constructor default, leaving fields such as `ratingSource` as `null`. Later code calls generated `hashCode`, `copy`, or UI helpers that assume the property is non-null, causing startup/profile-selection crashes.

Observed crash signature from device:

```text
NullPointerException: Attempt to invoke Object.hashCode() on a null object reference
at com.nexio.tv.domain.model.MetaPreview.hashCode
...
Parameter specified as non-null is null: HomeDisplayMetadata.<init>, parameter ratingSource
Parameter specified as non-null is null: TmdbEnrichment.copy, parameter ratingSource
```

High-risk startup persistence paths:

- `app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt` reads `List<MetaPreview>` on startup.
- `app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt` reads `Map<String, List<MetaPreview>>` on startup.
- `app/src/main/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStore.kt` reads discovery catalog structures that contain `MetaPreview`.
- `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt` reads `CatalogRow` and `MetaPreview` for startup home rendering.
- `app/src/main/java/com/nexio/tv/data/local/SyntheticHomeCatalogStore.kt` reads persisted synthetic `CatalogRow` groups.
- `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt` reads `HomeDisplayMetadata` maps and continue-watching rows.
- `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt` reads `Meta`, `TmdbEnrichment`, `TvMetadataEnrichment`, and cached TVDB episodes.
- `app/src/main/java/com/nexio/tv/data/local/CatalogDiskCacheStore.kt` reads `CatalogRow`.

This plan deliberately does not patch feature logic. It hardens persistence boundaries so future field additions follow the same compatibility pattern.

## File Structure

Create:

- `app/src/main/java/com/nexio/tv/data/local/MetadataModelSanitizers.kt`  
  Centralizes model normalization for `MetaPreview`, `Meta`, `HomeDisplayMetadata`, `CatalogRow`, `TmdbEnrichment`, and `TvMetadataEnrichment`.

Modify:

- `app/src/main/java/com/nexio/tv/domain/model/TitleRating.kt`  
  Add nullable-source fallback helper.
- `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt`  
  Make newly added `ratingSource` nullable for legacy Gson compatibility.
- `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`  
  Make newly added `ratingSource` nullable for legacy Gson compatibility.
- `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`  
  Make `ratingSource` nullable and normalize on conversion/merge/apply.
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`  
  Make `TmdbEnrichment.ratingSource` nullable for legacy cache compatibility.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt`  
  Make `TvMetadataEnrichment.ratingSource` nullable for legacy cache compatibility.
- `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`  
  Use sanitizers when decoding `Meta`, `TmdbEnrichment`, and `TvMetadataEnrichment`.
- `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`  
  Sanitize all decoded `CatalogRow` and `MetaPreview` entries.
- `app/src/main/java/com/nexio/tv/data/local/SyntheticHomeCatalogStore.kt`  
  Sanitize decoded `CatalogRow` entries.
- `app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt`  
  Sanitize decoded `MetaPreview` lists and custom catalog item lists.
- `app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt`  
  Sanitize decoded `MetaPreview` maps.
- `app/src/main/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStore.kt`  
  Sanitize decoded custom catalog item lists.
- `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt`  
  Sanitize decoded `HomeDisplayMetadata` maps.
- `app/src/main/java/com/nexio/tv/data/local/CatalogDiskCacheStore.kt`  
  Sanitize decoded `CatalogRow` entries.
- Provider/UI use sites that pass `ratingSource` into non-null functions:  
  `HomeCatalogRefreshCoordinator.kt`, `HomeViewModelPresentationPipeline.kt`, `ModernHomeModels.kt`, `ModernHomeHero.kt`, `HeroSection.kt`, `HeroCarousel.kt`, `MDBListRepository.kt`, `MetaDetailsViewModel.kt`, `TvMetadataRouter.kt`.

Tests:

- `app/src/test/java/com/nexio/tv/data/local/MetadataModelSanitizersTest.kt`
- `app/src/test/java/com/nexio/tv/domain/model/HomeDisplayMetadataTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/SyntheticHomeCatalogStoreTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStoreTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStoreTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStoreTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/CatalogDiskCacheStoreTest.kt`

---

### Task 1: Add Central Metadata Model Sanitizers

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/MetadataModelSanitizers.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/MetadataModelSanitizersTest.kt`

- [ ] **Step 1: Write failing sanitizer tests**

Create `app/src/test/java/com/nexio/tv/data/local/MetadataModelSanitizersTest.kt`:

```kotlin
package com.nexio.tv.data.local

import com.google.gson.Gson
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataModelSanitizersTest {
    private val gson = Gson()

    @Test
    fun `sanitize legacy preview without rating source`() {
        val preview = gson.fromJson(
            """
            {
              "id":"tt123",
              "type":"MOVIE",
              "rawType":"movie",
              "name":"Movie",
              "poster":null,
              "posterShape":"POSTER",
              "background":null,
              "logo":null,
              "description":null,
              "releaseInfo":"2025",
              "runtime":null,
              "imdbRating":8.3,
              "tomatoesRating":null,
              "genres":[],
              "trailerYtIds":[],
              "language":null,
              "posterProviderTag":null
            }
            """.trimIndent(),
            MetaPreview::class.java
        )

        val sanitized = preview.sanitizedForCache()

        sanitized.hashCode()
        assertEquals(TitleRatingSource.IMDB, sanitized.ratingSource)
        assertEquals(emptyList<String>(), sanitized.trailerYtIds)
        assertEquals(emptyList<String>(), sanitized.genres)
    }

    @Test
    fun `sanitize legacy home display metadata without rating source`() {
        val metadata = gson.fromJson(
            """
            {
              "title":"Movie",
              "logo":null,
              "description":null,
              "genres":[],
              "releaseInfo":"2025",
              "runtime":null,
              "imdbRating":8.3,
              "tomatoesRating":null,
              "poster":null,
              "posterProviderTag":null,
              "backdrop":null
            }
            """.trimIndent(),
            HomeDisplayMetadata::class.java
        )

        val sanitized = metadata.sanitizedForCache()

        sanitized.hashCode()
        assertEquals(TitleRatingSource.IMDB, sanitized.ratingSource)
        assertEquals(emptyList<String>(), sanitized.genres)
    }

    @Test
    fun `sanitize legacy tmdb enrichment without rating source`() {
        val enrichment = gson.fromJson(
            """
            {
              "localizedTitle":"Movie",
              "description":null,
              "genres":[],
              "backdrop":null,
              "logo":null,
              "poster":null,
              "directorMembers":[],
              "writerMembers":[],
              "castMembers":[],
              "releaseInfo":"2025",
              "rating":8.1,
              "runtimeMinutes":120,
              "director":[],
              "writer":[],
              "productionCompanies":[],
              "networks":[],
              "ageRating":null,
              "countries":null,
              "language":"en",
              "collectionId":null,
              "collectionName":null
            }
            """.trimIndent(),
            TmdbEnrichment::class.java
        )

        val sanitized = enrichment.sanitizedForCache()

        sanitized.hashCode()
        assertEquals(TitleRatingSource.TMDB, sanitized.ratingSource)
    }

    @Test
    fun `sanitize catalog row items`() {
        val preview = MetaPreview(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2025",
            imdbRating = 8.3f,
            ratingSource = null,
            genres = emptyList()
        )
        val row = CatalogRow(
            id = "row",
            title = "Row",
            items = listOf(preview),
            addonName = "Addon",
            addonLogo = null,
            addonBaseUrl = "https://example.com",
            catalogId = "row",
            catalogType = "movie"
        )

        val sanitized = row.sanitizedForCache()

        sanitized.hashCode()
        assertEquals(TitleRatingSource.IMDB, sanitized.items.single().ratingSource)
    }

    @Test
    fun `sanitize tv metadata enrichment without rating source`() {
        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = 1,
            localizedTitle = "Show",
            rating = 8.0,
            ratingSource = null
        )

        val sanitized = enrichment.sanitizedForCache()

        sanitized.hashCode()
        assertEquals(TitleRatingSource.IMDB, sanitized.ratingSource)
    }

    @Test
    fun `sanitize meta without rating source`() {
        val meta = Meta(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2025",
            imdbRating = 8.3f,
            ratingSource = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )

        val sanitized = meta.sanitizedForCache()

        sanitized.hashCode()
        assertEquals(TitleRatingSource.IMDB, sanitized.ratingSource)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.MetadataModelSanitizersTest
```

Expected: compile failure because `sanitizedForCache` extensions do not exist.

- [ ] **Step 3: Implement sanitizer extensions**

Create `app/src/main/java/com/nexio/tv/data/local/MetadataModelSanitizers.kt`:

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.orDefault

internal fun MetaPreview.sanitizedForCache(): MetaPreview = copy(
    ratingSource = ratingSource.orDefault(),
    genres = genres.orEmpty(),
    trailerYtIds = trailerYtIds.orEmpty()
)

internal fun HomeDisplayMetadata.sanitizedForCache(): HomeDisplayMetadata = copy(
    ratingSource = ratingSource.orDefault(),
    genres = genres.orEmpty()
)

internal fun CatalogRow.sanitizedForCache(): CatalogRow = copy(
    items = items.orEmpty().map { it.sanitizedForCache() }
)

internal fun Meta.sanitizedForCache(): Meta = copy(
    ratingSource = ratingSource.orDefault(),
    genres = genres.orEmpty(),
    director = director.orEmpty(),
    writer = writer.orEmpty(),
    cast = cast.orEmpty(),
    castMembers = castMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
    videos = videos.orEmpty(),
    productionCompanies = productionCompanies.orEmpty().mapNotNull { it.sanitizedOrNull() },
    networks = networks.orEmpty().mapNotNull { it.sanitizedOrNull() },
    links = links.orEmpty(),
    trailerYtIds = trailerYtIds.orEmpty()
)

internal fun TmdbEnrichment.sanitizedForCache(): TmdbEnrichment = copy(
    ratingSource = ratingSource.orDefault(TitleRatingSource.TMDB),
    genres = genres.orEmpty(),
    directorMembers = directorMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
    writerMembers = writerMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
    castMembers = castMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
    director = director.orEmpty(),
    writer = writer.orEmpty(),
    productionCompanies = productionCompanies.orEmpty().mapNotNull { it.sanitizedOrNull() },
    networks = networks.orEmpty().mapNotNull { it.sanitizedOrNull() }
)

internal fun TvMetadataEnrichment.sanitizedForCache(): TvMetadataEnrichment = copy(
    ratingSource = ratingSource.orDefault(),
    genres = genres.orEmpty(),
    airsDays = airsDays.orEmpty(),
    aliases = aliases.orEmpty(),
    contentRatings = contentRatings.orEmpty(),
    remoteIds = remoteIds.orEmpty(),
    castMembers = castMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
    productionCompanies = productionCompanies.orEmpty().mapNotNull { it.sanitizedOrNull() },
    networks = networks.orEmpty().mapNotNull { it.sanitizedOrNull() }
)

private fun MetaCastMember.sanitizedOrNull(): MetaCastMember? {
    val cleanName = name.trim().takeIf { it.isNotBlank() } ?: return null
    return copy(name = cleanName)
}

private fun MetaCompany.sanitizedOrNull(): MetaCompany? {
    val cleanName = name.trim().takeIf { it.isNotBlank() } ?: return null
    return copy(name = cleanName)
}

private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()
```

If Kotlin reports that `List<T>?.orEmpty()` conflicts with stdlib, remove that private extension and rely on stdlib `orEmpty()`.

- [ ] **Step 4: Run sanitizer tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.MetadataModelSanitizersTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/MetadataModelSanitizers.kt app/src/test/java/com/nexio/tv/data/local/MetadataModelSanitizersTest.kt
git commit -m "fix(cache): sanitize legacy metadata models"
```

---

### Task 2: Make Rating Source Fields Backward-Compatible

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/TitleRating.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt`
- Test: `app/src/test/java/com/nexio/tv/domain/model/HomeDisplayMetadataTest.kt`

- [ ] **Step 1: Write failing legacy rating-source test**

Add to `HomeDisplayMetadataTest.kt`:

```kotlin
@Test
fun `legacy preview without rating source can be hashed and converted`() {
    val preview = com.google.gson.Gson().fromJson(
        """
        {
          "id":"tt123",
          "type":"MOVIE",
          "rawType":"movie",
          "name":"Movie",
          "poster":null,
          "posterShape":"POSTER",
          "background":null,
          "logo":null,
          "description":null,
          "releaseInfo":"2025",
          "runtime":null,
          "imdbRating":8.3,
          "tomatoesRating":null,
          "genres":[],
          "trailerYtIds":[],
          "language":null,
          "posterProviderTag":null
        }
        """.trimIndent(),
        MetaPreview::class.java
    )

    preview.hashCode()
    val displayMetadata = preview.toHomeDisplayMetadata()

    assertEquals(TitleRatingSource.IMDB, displayMetadata.ratingSource)
}
```

- [ ] **Step 2: Run test to verify it fails on current strict model**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.HomeDisplayMetadataTest
```

Expected: FAIL with `NullPointerException` in `MetaPreview.hashCode` or when converting to `HomeDisplayMetadata`.

- [ ] **Step 3: Add nullable-source fallback helper**

Modify `TitleRating.kt`:

```kotlin
fun TitleRatingSource?.orDefault(defaultSource: TitleRatingSource = TitleRatingSource.IMDB): TitleRatingSource =
    this ?: defaultSource
```

- [ ] **Step 4: Make only newly added source fields nullable**

Modify these fields:

```kotlin
// MetaPreview.kt
val ratingSource: TitleRatingSource? = TitleRatingSource.IMDB,
```

```kotlin
// Meta.kt
val ratingSource: TitleRatingSource? = TitleRatingSource.IMDB,
```

```kotlin
// HomeDisplayMetadata.kt
val ratingSource: TitleRatingSource? = TitleRatingSource.IMDB,
```

```kotlin
// TmdbMetadataService.kt, TmdbEnrichment
val ratingSource: TitleRatingSource? = TitleRatingSource.TMDB,
```

```kotlin
// TvMetadataModels.kt, TvMetadataEnrichment
val ratingSource: TitleRatingSource? = TitleRatingSource.IMDB,
```

- [ ] **Step 5: Normalize source at conversion/use sites**

Use `.orDefault()` at every place where the nullable value flows into a non-null API or copy:

```kotlin
// HomeDisplayMetadata.kt conversions
ratingSource = ratingSource.orDefault()
```

```kotlin
// HomeDisplayMetadata.applyTo / mergeFallback
ratingSource = if (imdbRating != null) ratingSource.orDefault() else base.ratingSource.orDefault()
ratingSource = if (imdbRating != null) ratingSource.orDefault() else fallback.ratingSource.orDefault()
```

```kotlin
// ModernHomeHero.kt / HeroSection.kt / HeroCarousel.kt
titleRatingBadge(preview.ratingSource.orDefault())
titleRatingBadge(meta.ratingSource.orDefault())
titleRatingBadge(item.ratingSource.orDefault())
```

```kotlin
// TMDB defaults
ratingSource = ratingSource.orDefault(TitleRatingSource.TMDB)
```

- [ ] **Step 6: Run legacy test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.HomeDisplayMetadataTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/TitleRating.kt app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt app/src/main/java/com/nexio/tv/domain/model/Meta.kt app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt app/src/test/java/com/nexio/tv/domain/model/HomeDisplayMetadataTest.kt
git commit -m "fix(model): tolerate missing rating source"
```

---

### Task 3: Harden Metadata Disk Cache Reads

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTvdbTest.kt`

- [ ] **Step 1: Write failing TMDB cache compatibility test**

Add to `MetadataDiskCacheStoreTest.kt`:

```kotlin
@Test
fun `read TMDB enrichment tolerates legacy cache without rating source`() {
    val prefs = InMemorySharedPreferences()
    val store = MetadataDiskCacheStore(context = mockContext(prefs))
    prefs.edit().putString(
        "tmdb::movie:550::en-US::native",
        """
        {
          "value": {
            "localizedTitle":"Fight Club",
            "description":null,
            "genres":[],
            "backdrop":null,
            "logo":null,
            "poster":null,
            "directorMembers":[],
            "writerMembers":[],
            "castMembers":[],
            "releaseInfo":"1999",
            "rating":8.4,
            "runtimeMinutes":139,
            "director":[],
            "writer":[],
            "productionCompanies":[],
            "networks":[],
            "ageRating":null,
            "countries":null,
            "language":"en",
            "collectionId":null,
            "collectionName":null
          },
          "tmdbSchemaVersion": 2,
          "languageEpoch": 0,
          "updatedAtMs": ${System.currentTimeMillis()}
        }
        """.trimIndent()
    ).commit()

    val enrichment = store.readTmdbEnrichment("movie:550", "en-US", "native")

    assertEquals(TitleRatingSource.TMDB, enrichment?.ratingSource)
}
```

- [ ] **Step 2: Write failing TVDB cache compatibility test**

Add to `MetadataDiskCacheStoreTvdbTest.kt`:

```kotlin
@Test
fun `read TVDB enrichment tolerates missing rating source`() {
    val prefs = InMemorySharedPreferences()
    val store = MetadataDiskCacheStore(context = mockContext(prefs))
    prefs.edit().putString(
        "tvdb::121361::series_extended::en-US::native",
        """
        {
          "value": {
            "seriesTvdbId": 121361,
            "localizedTitle": "Game of Thrones",
            "rating": null,
            "genres": [],
            "airsDays": {},
            "remoteIds": {},
            "aliases": [],
            "contentRatings": [],
            "castMembers": [],
            "productionCompanies": [],
            "networks": []
          },
          "tvdbSchemaVersion": 2,
          "languageEpoch": 0,
          "updatedAtMs": ${System.currentTimeMillis()}
        }
        """.trimIndent()
    ).commit()

    val enrichment = store.readTvdbEnrichment(121361, "series_extended", "en-US", "native")

    assertEquals(TitleRatingSource.IMDB, enrichment?.ratingSource)
}
```

- [ ] **Step 3: Run tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.MetadataDiskCacheStoreTest --tests com.nexio.tv.data.local.MetadataDiskCacheStoreTvdbTest
```

Expected: FAIL from missing/nullable `ratingSource` normalization.

- [ ] **Step 4: Sanitize decoded metadata cache models**

Modify `MetadataDiskCacheStore.kt`:

```kotlin
private fun decodeMetaSafely(root: JsonObject): Meta? {
    val value = root.get("value") ?: return null
    val parsed = runCatching { gson.fromJson(value, Meta::class.java) }.getOrNull() ?: return null
    val valueObj = runCatching { value.asJsonObject }.getOrNull() ?: return parsed.sanitizedForCache()
    ...
    return parsed.copy(castMembers = safeCastMembers).sanitizedForCache()
}
```

```kotlin
private fun decodeTmdbEnrichmentSafely(root: JsonObject): TmdbEnrichment? {
    val value = root.get("value") ?: return null
    val parsed = runCatching { gson.fromJson(value, TmdbEnrichment::class.java) }.getOrNull() ?: return null
    val valueObj = value.asJsonObject
    return mergeTmdbEnrichmentCollections(parsed, valueObj).sanitizedForCache()
}
```

```kotlin
private fun decodeTvdbEnrichmentSafely(root: JsonObject): TvMetadataEnrichment? {
    val value = root.get("value") ?: return null
    val parsed = runCatching { gson.fromJson(value, TvMetadataEnrichment::class.java) }.getOrNull() ?: return null
    val valueObj = runCatching { value.asJsonObject }.getOrNull() ?: return parsed.copy(
        rating = null,
        castMembers = emptyList(),
        productionCompanies = emptyList(),
        networks = emptyList()
    ).sanitizedForCache()
    return parsed.copy(
        rating = null,
        castMembers = readCastMembersFromJson(valueObj, "castMembers"),
        productionCompanies = readCompaniesFromJson(valueObj, "productionCompanies"),
        networks = readCompaniesFromJson(valueObj, "networks")
    ).sanitizedForCache()
}
```

In `mergeTmdbEnrichmentCollections`, return `parsed.copy(...).sanitizedForCache()`.

- [ ] **Step 5: Run cache tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.MetadataDiskCacheStoreTest --tests com.nexio.tv.data.local.MetadataDiskCacheStoreTvdbTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTvdbTest.kt
git commit -m "fix(cache): sanitize metadata disk cache reads"
```

---

### Task 4: Harden Startup Home And Discovery Snapshot Reads

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/SyntheticHomeCatalogStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/CatalogDiskCacheStore.kt`
- Test: `HomeCatalogSnapshotStoreTest.kt`
- Test: `SyntheticHomeCatalogStoreTest.kt`
- Test: `TraktDiscoverySnapshotStoreTest.kt`
- Test: `SimklDiscoverySnapshotStoreTest.kt`
- Test: `MDBListDiscoverySnapshotStoreTest.kt`
- Test: `CatalogDiskCacheStoreTest.kt`

- [ ] **Step 1: Add legacy snapshot tests**

Add one test per store with a JSON payload containing a `MetaPreview` without `ratingSource` but with all pre-existing fields. The assertion in each test must call `hashCode()` on the restored snapshot/row/item and verify `ratingSource == TitleRatingSource.IMDB` on restored items.

Use this item JSON in each fixture:

```json
{
  "id":"tt123",
  "type":"MOVIE",
  "rawType":"movie",
  "name":"Movie",
  "poster":null,
  "posterShape":"POSTER",
  "background":null,
  "logo":null,
  "description":null,
  "releaseInfo":"2025",
  "runtime":null,
  "imdbRating":8.3,
  "tomatoesRating":null,
  "genres":[],
  "trailerYtIds":[],
  "language":null,
  "posterProviderTag":null
}
```

For `CatalogRow` fixtures, use:

```json
{
  "id":"row",
  "title":"Row",
  "items":[ITEM_JSON_HERE],
  "addonName":"Addon",
  "addonLogo":null,
  "addonBaseUrl":"https://example.com",
  "catalogId":"row",
  "catalogType":"movie"
}
```

- [ ] **Step 2: Run startup snapshot tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest --tests com.nexio.tv.data.local.SyntheticHomeCatalogStoreTest --tests com.nexio.tv.data.local.TraktDiscoverySnapshotStoreTest --tests com.nexio.tv.data.local.SimklDiscoverySnapshotStoreTest --tests com.nexio.tv.data.local.MDBListDiscoverySnapshotStoreTest --tests com.nexio.tv.data.local.CatalogDiskCacheStoreTest
```

Expected: at least one FAIL or crash in `hashCode()` before sanitizers are applied.

- [ ] **Step 3: Sanitize HomeCatalogSnapshotStore outputs**

In `HomeCatalogSnapshotStore.decodeSnapshot`, keep the existing schema and language checks. Ensure the returned snapshot passes through existing `.sanitize()`, and update `sanitizeMetaPreviews` to call:

```kotlin
val item = value as? MetaPreview ?: return@mapIndexedNotNull null
item.sanitizedForCache()
```

Update `sanitizeCatalogRows` to call:

```kotlin
row.copy(items = sanitizedItems).sanitizedForCache()
```

- [ ] **Step 4: Sanitize SyntheticHomeCatalogStore rows**

In `SyntheticHomeCatalogStore.decodeRow`:

```kotlin
private fun decodeRow(element: JsonElement): CatalogRow? {
    return runCatching {
        gson.fromJson(element, CatalogRow::class.java)?.sanitizedForCache()
    }.getOrNull()
}
```

- [ ] **Step 5: Sanitize TraktDiscoverySnapshotStore arrays**

Add helper:

```kotlin
private inline fun <reified T> decodeArray(root: JsonObject, key: String): List<T> {
    val array = root.getAsJsonArray(key) ?: return emptyList()
    val type = object : TypeToken<List<T>>() {}.type
    val decoded = gson.fromJson<List<T>>(array, type) ?: emptyList()
    return decoded.map { value ->
        when (value) {
            is MetaPreview -> value.sanitizedForCache() as T
            is TraktCustomListCatalog -> value.copy(items = value.items.map { it.sanitizedForCache() }) as T
            else -> value
        }
    }
}
```

If unchecked casts become noisy, split into explicit decode helpers for `MetaPreview` and `TraktCustomListCatalog` instead of using generic casting.

- [ ] **Step 6: Sanitize SimklDiscoverySnapshotStore maps**

In `decode`:

```kotlin
val itemsByCatalog = root.getAsJsonObject("itemsByCatalog")
    ?.let { gson.fromJson<Map<String, List<MetaPreview>>>(it, itemsByCatalogType) }
    ?.mapValues { (_, items) -> items.map { it.sanitizedForCache() } }
    ?: emptyMap()
```

- [ ] **Step 7: Sanitize MDBListDiscoverySnapshotStore custom catalogs**

Add explicit custom catalog decoding or sanitize the generic result:

```kotlin
customListCatalogs = decodeArray<MDBListCustomCatalog>(root, "customListCatalogs")
    .map { catalog -> catalog.copy(items = catalog.items.map { it.sanitizedForCache() }) }
```

- [ ] **Step 8: Sanitize CatalogDiskCacheStore rows**

In the row decode path:

```kotlin
val row = gson.fromJson(rowJson, CatalogRow::class.java)?.sanitizedForCache() ?: return null
```

- [ ] **Step 9: Run startup snapshot tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest --tests com.nexio.tv.data.local.SyntheticHomeCatalogStoreTest --tests com.nexio.tv.data.local.TraktDiscoverySnapshotStoreTest --tests com.nexio.tv.data.local.SimklDiscoverySnapshotStoreTest --tests com.nexio.tv.data.local.MDBListDiscoverySnapshotStoreTest --tests com.nexio.tv.data.local.CatalogDiskCacheStoreTest
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/SyntheticHomeCatalogStore.kt app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/CatalogDiskCacheStore.kt app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt app/src/test/java/com/nexio/tv/data/local/SyntheticHomeCatalogStoreTest.kt app/src/test/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStoreTest.kt app/src/test/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStoreTest.kt app/src/test/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStoreTest.kt app/src/test/java/com/nexio/tv/data/local/CatalogDiskCacheStoreTest.kt
git commit -m "fix(startup): sanitize cached home snapshots"
```

---

### Task 5: Harden Continue Watching Display Metadata Reads

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt`

- [ ] **Step 1: Add failing legacy display metadata test**

Add to `ContinueWatchingSnapshotStoreTest.kt`:

```kotlin
@Test
fun `read display metadata map tolerates legacy entries without rating source`() {
    val prefs = InMemorySharedPreferences()
    val store = ContinueWatchingSnapshotStore(context = mockContext(prefs), metadataDiskCacheStore = metadataStore())
    prefs.edit().putString(
        "snapshot",
        """
        {
          "schemaVersion": 2,
          "languageEpoch": 0,
          "generatedAtMs": 1,
          "resumeItems": [],
          "nextUpItems": [],
          "episodeMetadataByVideoId": {},
          "displayMetadataByItemKey": {
            "movie:tt123": {
              "title":"Movie",
              "logo":null,
              "description":null,
              "genres":[],
              "releaseInfo":"2025",
              "runtime":null,
              "imdbRating":8.3,
              "tomatoesRating":null,
              "poster":null,
              "posterProviderTag":null,
              "backdrop":null
            }
          }
        }
        """.trimIndent()
    ).commit()

    val snapshot = store.read(profileId = 1)
    val metadata = snapshot?.displayMetadataByItemKey?.get("movie:tt123")

    metadata.hashCode()
    assertEquals(TitleRatingSource.IMDB, metadata?.ratingSource)
}
```

Use the test file’s existing constructors/helpers for `mockContext` and `metadataStore`; if they differ, adapt only the setup, not the assertion.

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest
```

Expected: FAIL before sanitization.

- [ ] **Step 3: Sanitize display metadata map**

In `ContinueWatchingSnapshotStore.decodeDisplayMetadata`, change:

```kotlin
return gson.fromJson<Map<String, HomeDisplayMetadata>>(obj, type) ?: emptyMap()
```

To:

```kotlin
return gson.fromJson<Map<String, HomeDisplayMetadata>>(obj, type)
    ?.mapValues { (_, metadata) -> metadata.sanitizedForCache() }
    ?: emptyMap()
```

- [ ] **Step 4: Run continue watching tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt
git commit -m "fix(startup): sanitize continue watching metadata"
```

---

### Task 6: Audit Recent Releases For Similar Startup Null-Safety Risks

**Files:**
- Create: `docs/superpowers/plans/2026-04-20-startup-null-safety-audit-results.md`

- [ ] **Step 1: Run recent release diff scan**

Run:

```bash
git diff --name-only v0.53..HEAD -- app/src/main/java app/src/test/java | rg "(Store|Snapshot|Cache|DataStore|Meta|Preview|Home|Library|Trakt|Simkl|MDBList|Tvdb|Tmdb|Kitsu|Settings|Repository|Model|Dto|CustomImdb|Rating)"
```

Expected: list of files touched since `v0.53` that can affect startup or persistence.

- [ ] **Step 2: Run Gson persistence scan**

Run:

```bash
rg -n "gson\.fromJson<|gson\.fromJson\(|Gson\(\)\.fromJson|moshi\.adapter|Json.decode|decodeFromString" app/src/main/java/com/nexio/tv/data/local app/src/main/java/com/nexio/tv/data/repository app/src/main/java/com/nexio/tv/core app/src/main/java/com/nexio/tv/domain -g '!**/build/**'
```

Expected: all current model decode points.

- [ ] **Step 3: Write audit results document**

Create `docs/superpowers/plans/2026-04-20-startup-null-safety-audit-results.md`:

```markdown
# Startup Null-Safety Audit Results

## Confirmed crash class

Legacy Gson/SharedPreferences JSON can hydrate newly added Kotlin non-null fields as `null`, bypassing constructor defaults. This can crash generated `hashCode`, `copy`, equality checks, and UI code.

## High-risk paths hardened

- `MetadataDiskCacheStore`: `Meta`, `TmdbEnrichment`, `TvMetadataEnrichment`
- `HomeCatalogSnapshotStore`: `CatalogRow`, `MetaPreview`
- `SyntheticHomeCatalogStore`: `CatalogRow`, `MetaPreview`
- `TraktDiscoverySnapshotStore`: `MetaPreview`, `TraktCustomListCatalog`
- `SimklDiscoverySnapshotStore`: `MetaPreview`
- `MDBListDiscoverySnapshotStore`: `MDBListCustomCatalog`
- `ContinueWatchingSnapshotStore`: `HomeDisplayMetadata`
- `CatalogDiskCacheStore`: `CatalogRow`, `MetaPreview`

## Recent release features reviewed

- Title rating provider sources: high risk, fixed by sanitizer/defaulting tasks.
- Kitsu settings/auth: low risk because stored in DataStore preferences with explicit defaults.
- ASS/SSA translation AST/protection: low startup risk because the new data classes are not restored from startup SharedPreferences snapshots.
- Custom IMDb bulk ratings: low startup risk because cache entries are in-memory only.
- TMDB/TVDB enrichment changes: medium risk because `TmdbEnrichment` and `TvMetadataEnrichment` are disk-cached; fixed by cache sanitizers.

## Ongoing rule

Any new field added to a model decoded from Gson/SharedPreferences must be either nullable and normalized after decode or covered by a schema bump plus cache invalidation. Kotlin constructor defaults alone are not sufficient for Gson-loaded legacy data.
```

- [ ] **Step 4: Commit audit document**

```bash
git add docs/superpowers/plans/2026-04-20-startup-null-safety-audit-results.md
git commit -m "docs: record startup null-safety audit"
```

If `docs/` is ignored, use:

```bash
git add -f docs/superpowers/plans/2026-04-20-startup-null-safety-audit-results.md
git commit -m "docs: record startup null-safety audit"
```

---

### Task 7: Full Verification And Device Smoke Test

**Files:**
- Verify only.

- [ ] **Step 1: Run targeted startup/cache tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.MetadataModelSanitizersTest --tests com.nexio.tv.domain.model.HomeDisplayMetadataTest --tests com.nexio.tv.data.local.MetadataDiskCacheStoreTest --tests com.nexio.tv.data.local.MetadataDiskCacheStoreTvdbTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest --tests com.nexio.tv.data.local.SyntheticHomeCatalogStoreTest --tests com.nexio.tv.data.local.TraktDiscoverySnapshotStoreTest --tests com.nexio.tv.data.local.SimklDiscoverySnapshotStoreTest --tests com.nexio.tv.data.local.MDBListDiscoverySnapshotStoreTest --tests com.nexio.tv.data.local.ContinueWatchingSnapshotStoreTest --tests com.nexio.tv.data.local.CatalogDiskCacheStoreTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run rating regression tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tvdb.TvMetadataRouterTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceTest --tests com.nexio.tv.data.repository.MDBListTitleRatingsTest --tests com.nexio.tv.data.remote.CustomImdbClientTest --tests com.nexio.tv.data.repository.CustomImdbTitleRatingsRepositoryTest --tests com.nexio.tv.data.repository.TitleRatingOverrideRepositoryTest --tests com.nexio.tv.ui.screens.detail.EpisodeRatingBadgeSupportTest --tests com.nexio.tv.ui.screens.home.ModernHomeModelsTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Build and install release to the affected device**

Run:

```bash
ANDROID_SERIAL=192.168.50.71:5555 ./gradlew :app:installUniversalRelease
```

Expected: `Installed on 1 device`.

- [ ] **Step 4: Collect logcat after manual profile selection**

Do not automate profile selection if a human is using the device. Ask the tester to launch the app and select the default profile. Then run:

```bash
adb -s 192.168.50.71:5555 logcat -d -t 2000 | grep -E 'FATAL EXCEPTION|AndroidRuntime|ratingSource|MetaPreview.hashCode|HomeDisplayMetadata|TmdbEnrichment.copy|com\.nexio\.tv'
```

Expected: no `FATAL EXCEPTION`, no `MetaPreview.hashCode`, no `ratingSource` null warnings.

- [ ] **Step 5: Run diff hygiene**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 6: Commit any verification-only fixes**

If Steps 1-5 required additional code/test changes, commit them:

```bash
git add app/src/main/java app/src/test/java docs/superpowers/plans/2026-04-20-startup-null-safety-audit-results.md
git commit -m "test: cover startup cache compatibility"
```

If there are no changes, do not create an empty commit.

---

## Self-Review

**Spec coverage**

- Analyze similar crashes from recent releases: Task 6 scans release diff and persistence decode points.
- Address startup non-null-safety: Tasks 1-5 harden startup cache/snapshot paths.
- Do not rely on profile multiplicity: paths include default profile snapshots and active profile reads.
- Do not patch before planning: this document is the handoff plan; implementation should start after explicit execution choice.

**Placeholder scan**

- No forbidden placeholder patterns are present.
- Every implementation step names exact files and includes concrete code or commands.

**Type consistency**

- Sanitizer function name is consistently `sanitizedForCache`.
- Rating fallback helper is consistently `TitleRatingSource?.orDefault(...)`.
- Store tasks apply sanitizers at decode boundaries, not scattered UI-only guards.
