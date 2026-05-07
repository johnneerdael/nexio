# Title Rating Provider Sources Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display movie/show title ratings with the correct provider icon, defaulting TMDB-sourced scores to the TMDB badge and replacing them with real IMDb ratings when MDBList or the custom IMDb ratings API is configured.

**Architecture:** Introduce provider-aware title rating metadata instead of treating every title score as `imdbRating`. TMDB enrichment marks scores as TMDB; MDBList and the custom IMDb API can override that value with IMDb. UI surfaces render the same numeric slot with a provider-specific icon, mirroring the episode-rating source model.

**Tech Stack:** Kotlin, Android Compose, Hilt-injected repositories, Moshi/OkHttp, Gradle unit tests, existing TMDB/MDBList/custom IMDb integrations.

---

## Root Cause Summary

Current title rating data flows through a field named `imdbRating`, even when the score comes from TMDB `vote_average`. TVDB `score` was also previously copied into this field, which produced large values such as `14244.2`; that source is not a rating and should remain excluded.

Provider evidence:

- TMDB title enrichment reads `vote_average` in `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`.
- MDBList supports `POST /rating/{media_type}/imdb` with body `{"ids":[...],"provider":"imdb"}` per `mdblist.apib`.
- The custom IMDb ratings service supports `POST /v1/ratings/bulk` with `{"identifiers":["tt..."]}` and returns `averageRating` per tconst.
- Episode ratings already carry source through `EpisodeRatingSource` in `app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodeRatingModels.kt`.

## File Structure

Modify:

- `app/src/main/java/com/nexio/tv/domain/model/TitleRating.kt`  
  New provider-neutral title rating model and source enum.
- `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt`  
  Add `ratingSource` to preserve provider source for catalog/home items.
- `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`  
  Add `ratingSource` to preserve provider source for detail pages.
- `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`  
  Carry rating source through persisted/home metadata merges.
- `app/src/main/java/com/nexio/tv/data/mapper/CatalogMapper.kt`  
  Assign source for addon `imdbRating`.
- `app/src/main/java/com/nexio/tv/data/mapper/MetaMapper.kt`  
  Assign source for addon `imdbRating`.
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`  
  Mark TMDB title scores as TMDB.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt`  
  Carry rating source through provider-neutral TV metadata.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt`  
  Preserve TMDB source when adapting TMDB fallback into TV metadata.
- `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`  
  Keep Kitsu ratings out of the IMDb/TMDB badge until a Kitsu badge exists.
- `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt`  
  Wire `showImdb` into provider calls and allow `enrichPreview` to set real IMDb ratings.
- `app/src/main/java/com/nexio/tv/data/remote/CustomImdbClient.kt`  
  Add `/v1/ratings/bulk` support.
- `app/src/main/java/com/nexio/tv/data/repository/CustomImdbTitleRatingsRepository.kt`  
  New title-level custom IMDb lookup/cache repository.
- `app/src/main/java/com/nexio/tv/data/repository/TitleRatingOverrideRepository.kt`  
  New precedence layer: custom IMDb API first, MDBList IMDb second, existing TMDB/addon score last.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`  
  Apply provider-aware overrides during home refresh.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`  
  Preserve source when focused-item TMDB enrichment runs.
- `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`  
  Add rating source to `HeroPreview`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptions.kt`  
  Carry rating source into trailer hero previews.
- `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt`  
  Render TMDB or IMDb logo based on source.
- `app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt`  
  Render TMDB or IMDb logo based on source.
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`  
  Apply rating source and title override precedence on detail metadata.
- `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt`  
  Render TMDB or IMDb logo based on `Meta.ratingSource`.

Tests:

- `app/src/test/java/com/nexio/tv/domain/model/HomeDisplayMetadataTest.kt`
- `app/src/test/java/com/nexio/tv/core/tmdb/TmdbMetadataServiceTest.kt`
- `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt`
- `app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/MDBListTitleRatingsTest.kt`
- `app/src/test/java/com/nexio/tv/data/remote/CustomImdbClientTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/CustomImdbTitleRatingsRepositoryTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/TitleRatingOverrideRepositoryTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeModelsTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbAdvancedMetadataTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/detail/EpisodeRatingBadgeSupportTest.kt`

---

### Task 1: Add Provider-Aware Title Rating Domain Model

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/TitleRating.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
- Test: `app/src/test/java/com/nexio/tv/domain/model/HomeDisplayMetadataTest.kt`

- [ ] **Step 1: Write failing domain propagation test**

Add to `HomeDisplayMetadataTest.kt`:

```kotlin
@Test
fun `toHomeDisplayMetadata and applyTo preserve rating source`() {
    val preview = MetaPreview(
        id = "tmdb:1399",
        type = ContentType.SERIES,
        name = "Game of Thrones",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2011",
        imdbRating = 8.9f,
        ratingSource = TitleRatingSource.TMDB,
        genres = emptyList()
    )

    val displayMetadata = preview.toHomeDisplayMetadata()
    val roundTripped = displayMetadata.applyTo(
        preview.copy(imdbRating = null, ratingSource = TitleRatingSource.IMDB)
    )

    assertEquals(TitleRatingSource.TMDB, displayMetadata.ratingSource)
    assertEquals(TitleRatingSource.TMDB, roundTripped.ratingSource)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.HomeDisplayMetadataTest
```

Expected: compile failure because `TitleRatingSource` and `ratingSource` do not exist.

- [ ] **Step 3: Add title rating model**

Create `app/src/main/java/com/nexio/tv/domain/model/TitleRating.kt`:

```kotlin
package com.nexio.tv.domain.model

enum class TitleRatingSource {
    IMDB,
    TMDB
}

data class TitleRating(
    val value: Double,
    val source: TitleRatingSource
)

fun Float.toTitleRating(source: TitleRatingSource): TitleRating =
    TitleRating(value = toDouble(), source = source)

fun Double.toTitleRating(source: TitleRatingSource): TitleRating =
    TitleRating(value = this, source = source)
```

- [ ] **Step 4: Add source fields to `MetaPreview` and `Meta`**

Append the optional source field near each rating field without changing existing constructor call sites:

```kotlin
// MetaPreview.kt
val imdbRating: Float?,
val ratingSource: TitleRatingSource = TitleRatingSource.IMDB,
val tomatoesRating: Double? = null,
```

```kotlin
// Meta.kt
val imdbRating: Float?,
val ratingSource: TitleRatingSource = TitleRatingSource.IMDB,
val genres: List<String>,
```

- [ ] **Step 5: Carry source through home display metadata**

Update `HomeDisplayMetadata.kt`:

```kotlin
data class HomeDisplayMetadata(
    val title: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val imdbRating: Float? = null,
    val ratingSource: TitleRatingSource = TitleRatingSource.IMDB,
    val tomatoesRating: Double? = null,
    val poster: String? = null,
    val posterProviderTag: String? = null,
    val backdrop: String? = null
)
```

Set `ratingSource = ratingSource` in both `MetaPreview.toHomeDisplayMetadata()` and `Meta.toHomeDisplayMetadata()`. In `HomeDisplayMetadata.applyTo`, set:

```kotlin
imdbRating = imdbRating ?: base.imdbRating,
ratingSource = if (imdbRating != null) ratingSource else base.ratingSource,
```

In `HomeDisplayMetadata.mergeFallback`, set:

```kotlin
imdbRating = imdbRating ?: fallback.imdbRating,
ratingSource = if (imdbRating != null) ratingSource else fallback.ratingSource,
```

- [ ] **Step 6: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.HomeDisplayMetadataTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/TitleRating.kt app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt app/src/main/java/com/nexio/tv/domain/model/Meta.kt app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt app/src/test/java/com/nexio/tv/domain/model/HomeDisplayMetadataTest.kt
git commit -m "feat: track title rating provider source"
```

---

### Task 2: Mark TMDB Ratings as TMDB and Keep TVDB/Kitsu Out of This Badge

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Test: `app/src/test/java/com/nexio/tv/core/tmdb/TmdbMetadataServiceTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt`

- [ ] **Step 1: Write failing TMDB source test**

Add to an existing TMDB enrichment test or create a focused test in `TmdbMetadataServiceTest.kt`:

```kotlin
@Test
fun `tmdb title enrichment marks vote average as tmdb sourced`() = runTest {
    val enrichment = tmdbEnrichment(title = "Fallback title")

    assertEquals(8.2, enrichment.rating ?: 0.0, 0.0)
    assertEquals(TitleRatingSource.TMDB, enrichment.ratingSource)
}
```

If `tmdbEnrichment` is only local to `TvMetadataRouterTest`, add the same assertion there for the `tmdbEnrichment(title = "Fallback title")` helper after adding the new field.

- [ ] **Step 2: Write failing TVDB/Kitsu exclusion tests**

In `TvMetadataRouterTest.kt`, update or add:

```kotlin
@Test
fun `tmdb fallback carries tmdb rating source through tv metadata`() = runTest {
    val tmdbService = mockk<TmdbService>()
    val tmdbMetadataService = mockk<TmdbMetadataService>()
    val router = tvMetadataRouter(
        settings = TvdbSettings(enabled = false),
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService
    )

    coEvery { tmdbService.ensureTmdbId("tt0944947", "series") } returns "1399"
    coEvery {
        tmdbMetadataService.fetchEnrichment("1399", ContentType.SERIES, "en-US")
    } returns tmdbEnrichment(title = "Fallback title")

    val decision = router.fetchEnrichment(
        TvMetadataRequest(
            contentId = "tt0944947",
            contentType = ContentType.SERIES,
            language = "en-US"
        )
    )

    assertEquals(TitleRatingSource.TMDB, decision.value?.ratingSource)
}
```

In `KitsuMetadataServiceTest.kt`, update `fetchEnrichment maps kitsu details`:

```kotlin
assertNull("Kitsu average should not masquerade as IMDb or TMDB", enrichment?.rating)
```

- [ ] **Step 3: Run tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tvdb.TvMetadataRouterTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceTest
```

Expected: compile failure for missing `ratingSource` or assertion failure where Kitsu still sets `rating`.

- [ ] **Step 4: Add source field to enrichment models**

Update `TvMetadataModels.kt`:

```kotlin
data class TvMetadataEnrichment(
    val seriesTvdbId: Int?,
    val localizedTitle: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val backdrop: String? = null,
    val logo: String? = null,
    val poster: String? = null,
    val releaseInfo: String? = null,
    val rating: Double? = null,
    val ratingSource: TitleRatingSource = TitleRatingSource.IMDB,
    val runtimeMinutes: Int? = null,
    ...
)
```

Import `com.nexio.tv.domain.model.TitleRatingSource`.

Update `TmdbEnrichment` in `TmdbMetadataService.kt`:

```kotlin
data class TmdbEnrichment(
    val localizedTitle: String?,
    val description: String?,
    val genres: List<String>,
    val backdrop: String?,
    val logo: String?,
    val poster: String?,
    val directorMembers: List<MetaCastMember>,
    val writerMembers: List<MetaCastMember>,
    val castMembers: List<MetaCastMember>,
    val releaseInfo: String?,
    val rating: Double?,
    val ratingSource: TitleRatingSource = TitleRatingSource.TMDB,
    val runtimeMinutes: Int?,
    ...
)
```

Import `TitleRatingSource`.

- [ ] **Step 5: Preserve source through provider conversions**

In `TvMetadataRouter.toTvMetadataEnrichment()`:

```kotlin
rating = rating,
ratingSource = ratingSource,
runtimeMinutes = runtimeMinutes,
```

In `KitsuMetadataService.fetchEnrichment`, stop setting Kitsu average into title `rating`:

```kotlin
val rating = null
```

or remove the local parsed `averageRating` entirely.

- [ ] **Step 6: Apply source when mutating MetaPreview/Meta**

In `HomeCatalogRefreshCoordinator.applyTvMetadataEnrichmentForHome`:

```kotlin
imdbRating = enrichment.rating?.toFloat() ?: imdbRating,
ratingSource = if (enrichment.rating != null) enrichment.ratingSource else ratingSource,
```

In `HomeViewModelPresentationPipeline.mergeFocusedItemEnrichment`:

```kotlin
imdbRating = providerEnrichment.rating?.toFloat() ?: merged.imdbRating,
ratingSource = if (providerEnrichment.rating != null) providerEnrichment.ratingSource else merged.ratingSource,
```

In `MetaDetailsViewModel`, near the existing `rating` local:

```kotlin
val rating = tvEnrichment?.rating ?: tmdbEnrichment?.rating
val ratingSource = when {
    tvEnrichment?.rating != null -> tvEnrichment.ratingSource
    tmdbEnrichment?.rating != null -> tmdbEnrichment.ratingSource
    else -> updated.ratingSource
}
```

Then when setting `imdbRating`:

```kotlin
updated = updated.copy(
    imdbRating = rating?.toFloat() ?: updated.imdbRating,
    ratingSource = if (rating != null) ratingSource else updated.ratingSource
)
```

- [ ] **Step 7: Run source tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tvdb.TvMetadataRouterTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt
git commit -m "fix: mark title ratings by provider"
```

---

### Task 3: Render Title Rating Icons by Source

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptions.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeModelsTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/EpisodeRatingBadgeSupportTest.kt`

- [ ] **Step 1: Write failing badge helper test**

Add to `EpisodeRatingBadgeSupportTest.kt`:

```kotlin
@Test
fun `title rating badge uses tmdb logo for tmdb source`() {
    val badge = titleRatingBadge(TitleRatingSource.TMDB)

    assertEquals(R.raw.mdblist_tmdb, badge.logoRes)
    assertEquals("TMDB", badge.contentDescription)
}

@Test
fun `title rating badge uses imdb logo for imdb source`() {
    val badge = titleRatingBadge(TitleRatingSource.IMDB)

    assertEquals(R.raw.imdb_logo_2016, badge.logoRes)
    assertEquals("IMDb", badge.contentDescription)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.EpisodeRatingBadgeSupportTest
```

Expected: compile failure because `titleRatingBadge` does not exist.

- [ ] **Step 3: Add reusable title rating badge helper**

Create helper in `EpisodeRatingModels.kt` or move to a shared UI model file if imports become awkward. Minimal implementation in `EpisodeRatingModels.kt`:

```kotlin
data class RatingBadgeUi(
    val logoRes: Int,
    val contentDescription: String
)

internal fun titleRatingBadge(source: TitleRatingSource): RatingBadgeUi {
    return when (source) {
        TitleRatingSource.IMDB -> RatingBadgeUi(
            logoRes = R.raw.imdb_logo_2016,
            contentDescription = "IMDb"
        )
        TitleRatingSource.TMDB -> RatingBadgeUi(
            logoRes = R.raw.mdblist_tmdb,
            contentDescription = "TMDB"
        )
    }
}
```

Import `com.nexio.tv.domain.model.TitleRatingSource`.

- [ ] **Step 4: Add source to hero preview model**

In `ModernHomeModels.kt`, update `HeroPreview`:

```kotlin
data class HeroPreview(
    val title: String?,
    val logo: String?,
    val description: String?,
    val contentTypeText: String?,
    val yearText: String?,
    val imdbText: String?,
    val ratingSource: TitleRatingSource = TitleRatingSource.IMDB,
    val tomatoesText: String?,
    ...
)
```

At every `HeroPreview(...)` call, add:

```kotlin
ratingSource = displayMetadata.ratingSource,
```

For episode-specific continue watching ratings, keep IMDb:

```kotlin
ratingSource = if (item.episodeImdbRating != null) TitleRatingSource.IMDB else displayMetadata.ratingSource,
```

For `HomePosterTrailerOptions.toHomeHeroPreview()`:

```kotlin
ratingSource = ratingSource,
```

- [ ] **Step 5: Render icon from source in ModernHomeHero**

In `ModernHomeHero.kt`, replace hardcoded IMDb request construction with a helper:

```kotlin
val ratingBadge = remember(preview?.ratingSource) {
    preview?.ratingSource?.let(::titleRatingBadge)
}
val ratingLogoRequest = remember(context, ratingBadge?.logoRes) {
    ratingBadge?.let {
        ImageRequest.Builder(context)
            .data(it.logoRes)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
}
```

Where the IMDb logo is rendered, use:

```kotlin
if (!imdbText.isNullOrBlank() && ratingLogoRequest != null && ratingBadge != null) {
    AsyncImage(
        model = ratingLogoRequest,
        contentDescription = ratingBadge.contentDescription,
        modifier = Modifier.height(14.dp),
        contentScale = ContentScale.Fit
    )
    Text(
        text = imdbText,
        style = MaterialTheme.typography.labelMedium,
        color = NexioTheme.extendedColors.textPrimary
    )
}
```

- [ ] **Step 6: Render source icons in legacy hero/detail surfaces**

In `HeroCarousel.kt`, when rendering `item.imdbRating`, use:

```kotlin
val ratingBadge = remember(item.ratingSource) { titleRatingBadge(item.ratingSource) }
```

and use `ratingBadge.logoRes` instead of `R.raw.imdb_logo_2016`.

In `HeroSection.kt`, when rendering `meta.imdbRating`, use:

```kotlin
val ratingBadge = remember(meta.ratingSource) { titleRatingBadge(meta.ratingSource) }
```

and use `ratingBadge.logoRes` / `ratingBadge.contentDescription`.

- [ ] **Step 7: Run UI model tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.EpisodeRatingBadgeSupportTest --tests com.nexio.tv.ui.screens.home.ModernHomeModelsTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodeRatingModels.kt app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptions.kt app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt app/src/test/java/com/nexio/tv/ui/screens/detail/EpisodeRatingBadgeSupportTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeModelsTest.kt
git commit -m "feat: render title rating provider badges"
```

---

### Task 4: Wire MDBList IMDb Ratings for Movies and Shows

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/MDBListTitleRatingsTest.kt`

- [ ] **Step 1: Write failing MDBList IMDb provider tests**

Create `MDBListTitleRatingsTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingItemDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingResponseDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MDBListSettings
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.Video
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class MDBListTitleRatingsTest {
    @Test
    fun `getRatingsForMeta requests imdb when showImdb is enabled`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = mockk<com.nexio.tv.data.local.MDBListSettingsDataStore>()
        val tmdbService = mockk<com.nexio.tv.core.tmdb.TmdbService>(relaxed = true)
        val repository = MDBListRepository(api, settings, tmdbService)

        coEvery { settings.settings } returns flowOf(
            MDBListSettings(
                enabled = true,
                apiKey = "mdb-key",
                showTrakt = false,
                showImdb = true,
                showTmdb = false,
                showLetterboxd = false,
                showTomatoes = false,
                showAudience = false,
                showMetacritic = false
            )
        )
        coEvery {
            api.getRating("show", "imdb", "mdb-key", any())
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(MDBListRatingItemDto(id = "tt0944947", rating = 9.2))
            )
        )

        val result = repository.getRatingsForMeta(
            meta = stubMeta("tt0944947", ContentType.SERIES),
            fallbackItemId = "tt0944947",
            fallbackItemType = "series"
        )

        assertEquals(9.2, result?.ratings?.imdb ?: 0.0, 0.0)
        assertTrue(result?.hasImdbRating == true)
    }

    @Test
    fun `enrichPreview replaces tmdb score with mdblist imdb rating`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = mockk<com.nexio.tv.data.local.MDBListSettingsDataStore>()
        val tmdbService = mockk<com.nexio.tv.core.tmdb.TmdbService>(relaxed = true)
        val repository = MDBListRepository(api, settings, tmdbService)

        coEvery { settings.settings } returns flowOf(
            MDBListSettings(
                enabled = true,
                apiKey = "mdb-key",
                showTrakt = false,
                showImdb = true,
                showTmdb = false,
                showLetterboxd = false,
                showTomatoes = false,
                showAudience = false,
                showMetacritic = false
            )
        )
        coEvery {
            api.getRating("movie", "imdb", "mdb-key", any())
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(MDBListRatingItemDto(id = "tt1375666", rating = 8.8))
            )
        )

        val preview = com.nexio.tv.domain.model.MetaPreview(
            id = "tt1375666",
            type = ContentType.MOVIE,
            name = "Inception",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2010",
            imdbRating = 8.3f,
            ratingSource = TitleRatingSource.TMDB,
            genres = emptyList()
        )

        val enriched = repository.enrichPreview(preview)

        assertEquals(8.8f, enriched.imdbRating ?: 0f, 0.0f)
        assertEquals(TitleRatingSource.IMDB, enriched.ratingSource)
    }

    private fun stubMeta(id: String, type: ContentType): Meta {
        return Meta(
            id = id,
            type = type,
            rawType = type.toApiString(),
            name = "Stub",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList<Video>(),
            productionCompanies = emptyList<MetaCompany>(),
            networks = emptyList<MetaCompany>(),
            ageRating = null,
            country = null,
            awards = null,
            language = null,
            links = emptyList<MetaLink>(),
            trailerYtIds = emptyList()
        )
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.MDBListTitleRatingsTest
```

Expected: FAIL because `ratings.imdb` is hardcoded null and `showImdb` is not included in enabled providers.

- [ ] **Step 3: Add IMDb provider to MDBList repository**

In `MDBListRepository.ProviderType`:

```kotlin
private enum class ProviderType(val apiValue: String) {
    TRAKT("trakt"),
    IMDB("imdb"),
    TMDB("tmdb"),
    LETTERBOXD("letterboxd"),
    TOMATOES("tomatoes"),
    AUDIENCE("audience"),
    METACRITIC("metacritic")
}
```

In `fetchRatings`:

```kotlin
val ratings = MDBListRatings(
    trakt = results[ProviderType.TRAKT],
    imdb = results[ProviderType.IMDB],
    tmdb = results[ProviderType.TMDB],
    letterboxd = results[ProviderType.LETTERBOXD],
    tomatoes = results[ProviderType.TOMATOES],
    audience = results[ProviderType.AUDIENCE],
    metacritic = results[ProviderType.METACRITIC]
)
```

In `enabledProviders`:

```kotlin
if (settings.showImdb) add(ProviderType.IMDB)
```

- [ ] **Step 4: Allow preview enrichment to request IMDb and tomatoes independently**

Replace the early return in `enrichPreview`:

```kotlin
val needsTomatoes = preview.tomatoesRating == null && settings.showTomatoes
val needsImdb = settings.showImdb
if (!needsTomatoes && !needsImdb) return preview
```

Build provider list:

```kotlin
val providers = buildList {
    if (needsImdb) add(ProviderType.IMDB)
    if (needsTomatoes) add(ProviderType.TOMATOES)
}
```

Copy result:

```kotlin
return preview.copy(
    imdbRating = result.ratings.imdb?.toFloat() ?: preview.imdbRating,
    ratingSource = if (result.ratings.imdb != null) TitleRatingSource.IMDB else preview.ratingSource,
    tomatoesRating = result.ratings.tomatoes ?: preview.tomatoesRating
)
```

- [ ] **Step 5: Run MDBList tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.MDBListTitleRatingsTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt app/src/test/java/com/nexio/tv/data/repository/MDBListTitleRatingsTest.kt
git commit -m "feat: use mdblist imdb ratings for titles"
```

---

### Task 5: Add Custom IMDb Bulk Title Ratings

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/CustomImdbClient.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/CustomImdbTitleRatingsRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/data/remote/CustomImdbClientTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/CustomImdbTitleRatingsRepositoryTest.kt`

- [ ] **Step 1: Write failing custom client bulk test**

Add to `CustomImdbClientTest.kt`:

```kotlin
@Test
fun `fetchTitleRatings posts bulk identifiers and maps ratings`() = runTest {
    var capturedPath = ""
    var capturedMethod = ""
    var capturedRequestBody: String? = null
    val client = OkHttpCustomImdbClient(
        okHttpClient = okHttpClient { chain ->
            capturedPath = chain.request().url.encodedPath
            capturedMethod = chain.request().method
            capturedRequestBody = chain.request().body?.readUtf8()
            jsonResponse(
                chain,
                """
                {
                  "results": [
                    { "tconst": "tt32459853", "averageRating": 7.8, "numVotes": 1500 },
                    { "tconst": "tt0944947", "averageRating": 9.2, "numVotes": 2300000 }
                  ],
                  "missing": ["Hello, world!"]
                }
                """.trimIndent()
            )
        },
        moshi = Moshi.Builder().build()
    )

    val result = client.fetchTitleRatings(
        baseUrl = "https://ratings.example.com/custom",
        apiKey = "secret-key",
        identifiers = listOf("Hello, world!", "tt32459853", "tt0944947")
    )

    assertEquals("/custom/v1/ratings/bulk", capturedPath)
    assertEquals("POST", capturedMethod)
    assertEquals(
        """{"identifiers":["Hello, world!","tt32459853","tt0944947"]}""",
        capturedRequestBody
    )
    assertEquals(
        mapOf(
            "tt32459853" to 7.8,
            "tt0944947" to 9.2
        ),
        result
    )
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.remote.CustomImdbClientTest
```

Expected: compile failure because `fetchTitleRatings` does not exist.

- [ ] **Step 3: Extend client interface and DTOs**

In `CustomImdbClient.kt`:

```kotlin
interface CustomImdbClient {
    suspend fun validate(baseUrl: String, apiKey: String): Boolean

    suspend fun fetchEpisodeRatings(
        baseUrl: String,
        apiKey: String,
        tconst: String
    ): Map<Pair<Int, Int>, Double>

    suspend fun fetchTitleRatings(
        baseUrl: String,
        apiKey: String,
        identifiers: List<String>
    ): Map<String, Double>
}
```

Add DTOs:

```kotlin
@JsonClass(generateAdapter = true)
data class BulkRatingsRequest(
    val identifiers: List<String>
)

@JsonClass(generateAdapter = true)
data class BulkRatingsResponse(
    val results: List<RatingDto> = emptyList(),
    val missing: List<String> = emptyList()
)
```

Add adapter:

```kotlin
private val bulkRatingsRequestAdapter = moshi.adapter(BulkRatingsRequest::class.java)
private val bulkRatingsResponseAdapter = moshi.adapter(BulkRatingsResponse::class.java)
```

Add implementation:

```kotlin
override suspend fun fetchTitleRatings(
    baseUrl: String,
    apiKey: String,
    identifiers: List<String>
): Map<String, Double> {
    val normalizedBaseUrl = normalizeCustomImdbBaseUrl(baseUrl)
    val normalizedIdentifiers = identifiers.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (normalizedBaseUrl.isBlank() || apiKey.isBlank() || normalizedIdentifiers.isEmpty()) return emptyMap()

    val body = bulkRatingsRequestAdapter.toJson(BulkRatingsRequest(normalizedIdentifiers))
        .toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url(buildCustomImdbUrl(normalizedBaseUrl, "ratings/bulk"))
        .header("X-API-Key", apiKey.trim())
        .post(body)
        .build()

    return executeWithRateLimitRetry(
        request = request,
        onFailure = { emptyMap() }
    ) { response ->
        if (!response.isSuccessful) {
            Log.w(CUSTOM_IMDB_CLIENT_TAG, "Custom IMDb bulk ratings request failed with HTTP ${response.code}")
            return@executeWithRateLimitRetry emptyMap()
        }

        val payload = response.body?.string().orEmpty()
        val parsed = bulkRatingsResponseAdapter.fromJson(payload) ?: return@executeWithRateLimitRetry emptyMap()
        parsed.results.mapNotNull { rating ->
            val value = rating.averageRating?.takeIf { it > 0.0 } ?: return@mapNotNull null
            rating.tconst.trim().takeIf { it.isNotBlank() }?.let { it to value }
        }.toMap()
    }
}
```

Add imports:

```kotlin
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
```

- [ ] **Step 4: Create title ratings repository test**

Create `CustomImdbTitleRatingsRepositoryTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.ImdbSettingsDataStore
import com.nexio.tv.data.remote.CustomImdbClient
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ImdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomImdbTitleRatingsRepositoryTest {
    @Test
    fun `fetches configured imdb title rating by imdb id`() = runTest {
        val client = mockk<CustomImdbClient>()
        val settings = mockk<ImdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val repository = CustomImdbTitleRatingsRepository(client, settings, tmdbService)

        coEvery { settings.settings } returns flowOf(
            ImdbSettings(enabled = true, baseUrl = "https://ratings.example.com", apiKey = "secret-key")
        )
        coEvery {
            client.fetchTitleRatings("https://ratings.example.com", "secret-key", listOf("tt0944947"))
        } returns mapOf("tt0944947" to 9.2)

        val rating = repository.getTitleRating(
            contentId = "tt0944947",
            fallbackItemId = "tt0944947",
            contentType = ContentType.SERIES,
            fallbackItemType = "series"
        )

        assertEquals(9.2, rating ?: 0.0, 0.0)
    }

    @Test
    fun `resolves tmdb id to imdb before custom bulk request`() = runTest {
        val client = mockk<CustomImdbClient>()
        val settings = mockk<ImdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>()
        val repository = CustomImdbTitleRatingsRepository(client, settings, tmdbService)

        coEvery { settings.settings } returns flowOf(
            ImdbSettings(enabled = true, baseUrl = "https://ratings.example.com", apiKey = "secret-key")
        )
        coEvery { tmdbService.ensureTmdbId("tmdb:1399", "series") } returns "1399"
        coEvery { tmdbService.tmdbToImdb(1399, "series") } returns "tt0944947"
        coEvery {
            client.fetchTitleRatings("https://ratings.example.com", "secret-key", listOf("tt0944947"))
        } returns mapOf("tt0944947" to 9.2)

        val rating = repository.getTitleRating(
            contentId = "tmdb:1399",
            fallbackItemId = "tmdb:1399",
            contentType = ContentType.SERIES,
            fallbackItemType = "series"
        )

        assertEquals(9.2, rating ?: 0.0, 0.0)
        coVerify(exactly = 1) { tmdbService.tmdbToImdb(1399, "series") }
    }
}
```

- [ ] **Step 5: Create title ratings repository**

Create `CustomImdbTitleRatingsRepository.kt`:

```kotlin
package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.ImdbSettingsDataStore
import com.nexio.tv.data.remote.CustomImdbClient
import com.nexio.tv.data.remote.normalizeCustomImdbBaseUrl
import com.nexio.tv.domain.model.ContentType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val CUSTOM_IMDB_TITLE_TAG = "CustomImdbTitleRatings"
private const val CUSTOM_IMDB_TITLE_RATINGS_TTL_MS = 7L * 24L * 60L * 60L * 1000L

@Singleton
class CustomImdbTitleRatingsRepository @Inject constructor(
    private val customImdbClient: CustomImdbClient,
    private val imdbSettingsDataStore: ImdbSettingsDataStore,
    private val tmdbService: TmdbService
) {
    private data class CacheEntry(
        val rating: Double?,
        val expiresAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }

    suspend fun getTitleRating(
        contentId: String,
        fallbackItemId: String,
        contentType: ContentType,
        fallbackItemType: String
    ): Double? {
        val settings = imdbSettingsDataStore.settings.first()
        if (!settings.isActive) return null

        val baseUrl = normalizeCustomImdbBaseUrl(settings.baseUrl)
        val apiKey = settings.apiKey.trim()
        if (baseUrl.isBlank() || apiKey.isBlank()) return null

        val imdbId = resolveImdbId(contentId, fallbackItemId, contentType, fallbackItemType) ?: return null
        val cacheKey = "$baseUrl:$imdbId:${apiKey.hashCode()}"
        val now = nowMsProvider()
        cache[cacheKey]?.takeIf { it.expiresAtMs > now }?.let { return it.rating }

        val rating = runCatching {
            customImdbClient.fetchTitleRatings(
                baseUrl = baseUrl,
                apiKey = apiKey,
                identifiers = listOf(imdbId)
            )[imdbId]
        }.getOrElse { error ->
            Log.w(CUSTOM_IMDB_TITLE_TAG, "Failed custom IMDb title rating for $imdbId: ${error.message}", error)
            null
        }

        cache[cacheKey] = CacheEntry(
            rating = rating,
            expiresAtMs = nowMsProvider() + CUSTOM_IMDB_TITLE_RATINGS_TTL_MS
        )
        return rating
    }

    private suspend fun resolveImdbId(
        contentId: String,
        fallbackItemId: String,
        contentType: ContentType,
        fallbackItemType: String
    ): String? {
        extractImdbId(contentId)?.let { return it }
        extractImdbId(fallbackItemId)?.let { return it }

        val tmdbType = when (contentType) {
            ContentType.SERIES, ContentType.TV -> "series"
            ContentType.MOVIE -> "movie"
            else -> fallbackItemType.ifBlank { contentType.toApiString() }
        }
        val tmdbId = tmdbService.ensureTmdbId(contentId, tmdbType)
            ?: tmdbService.ensureTmdbId(fallbackItemId, tmdbType)
            ?: return null

        return tmdbService.tmdbToImdb(tmdbId.toInt(), tmdbType)
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        return Regex("tt\\d+").find(rawId)?.value
    }
}
```

- [ ] **Step 6: Run client and repository tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.remote.CustomImdbClientTest --tests com.nexio.tv.data.repository.CustomImdbTitleRatingsRepositoryTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/CustomImdbClient.kt app/src/main/java/com/nexio/tv/data/repository/CustomImdbTitleRatingsRepository.kt app/src/test/java/com/nexio/tv/data/remote/CustomImdbClientTest.kt app/src/test/java/com/nexio/tv/data/repository/CustomImdbTitleRatingsRepositoryTest.kt
git commit -m "feat: add custom imdb bulk title ratings"
```

---

### Task 6: Centralize Rating Override Precedence

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/TitleRatingOverrideRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TitleRatingOverrideRepositoryTest.kt`

- [ ] **Step 1: Write failing precedence tests**

Create `TitleRatingOverrideRepositoryTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleRatingOverrideRepositoryTest {
    @Test
    fun `custom imdb rating wins over mdblist and tmdb`() = runTest {
        val custom = mockk<CustomImdbTitleRatingsRepository>()
        val mdb = mockk<MDBListRepository>()
        val repository = TitleRatingOverrideRepository(custom, mdb)
        val preview = preview(rating = 8.1f, source = TitleRatingSource.TMDB)

        coEvery {
            custom.getTitleRating("tt0944947", "tt0944947", ContentType.SERIES, "series")
        } returns 9.2
        coEvery { mdb.enrichPreview(any()) } returns preview.copy(imdbRating = 8.9f, ratingSource = TitleRatingSource.IMDB)

        val enriched = repository.enrichPreview(preview)

        assertEquals(9.2f, enriched.imdbRating ?: 0f, 0.0f)
        assertEquals(TitleRatingSource.IMDB, enriched.ratingSource)
    }

    @Test
    fun `mdblist imdb rating wins when custom imdb is unavailable`() = runTest {
        val custom = mockk<CustomImdbTitleRatingsRepository>()
        val mdb = mockk<MDBListRepository>()
        val repository = TitleRatingOverrideRepository(custom, mdb)
        val preview = preview(rating = 8.1f, source = TitleRatingSource.TMDB)

        coEvery {
            custom.getTitleRating("tt0944947", "tt0944947", ContentType.SERIES, "series")
        } returns null
        coEvery { mdb.enrichPreview(preview) } returns preview.copy(imdbRating = 8.9f, ratingSource = TitleRatingSource.IMDB)

        val enriched = repository.enrichPreview(preview)

        assertEquals(8.9f, enriched.imdbRating ?: 0f, 0.0f)
        assertEquals(TitleRatingSource.IMDB, enriched.ratingSource)
    }

    private fun preview(rating: Float, source: TitleRatingSource): MetaPreview =
        MetaPreview(
            id = "tt0944947",
            type = ContentType.SERIES,
            name = "Game of Thrones",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2011",
            imdbRating = rating,
            ratingSource = source,
            genres = emptyList()
        )
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.TitleRatingOverrideRepositoryTest
```

Expected: compile failure because `TitleRatingOverrideRepository` does not exist.

- [ ] **Step 3: Add override repository**

Create `TitleRatingOverrideRepository.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.TitleRatingSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TitleRatingOverrideRepository @Inject constructor(
    private val customImdbTitleRatingsRepository: CustomImdbTitleRatingsRepository,
    private val mdbListRepository: MDBListRepository
) {
    suspend fun enrichPreview(preview: MetaPreview): MetaPreview {
        val customRating = customImdbTitleRatingsRepository.getTitleRating(
            contentId = preview.id,
            fallbackItemId = preview.id,
            contentType = preview.type,
            fallbackItemType = preview.apiType
        )
        if (customRating != null) {
            return preview.copy(
                imdbRating = customRating.toFloat(),
                ratingSource = TitleRatingSource.IMDB
            )
        }

        return mdbListRepository.enrichPreview(preview)
    }

    suspend fun enrichMeta(meta: Meta, fallbackItemId: String, fallbackItemType: String): Meta {
        val customRating = customImdbTitleRatingsRepository.getTitleRating(
            contentId = meta.id,
            fallbackItemId = fallbackItemId,
            contentType = meta.type,
            fallbackItemType = fallbackItemType
        )
        if (customRating != null) {
            return meta.copy(
                imdbRating = customRating.toFloat(),
                ratingSource = TitleRatingSource.IMDB
            )
        }

        val mdblistRating = mdbListRepository.getRatingsForMeta(
            meta = meta,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType
        )?.ratings?.imdb

        return if (mdblistRating != null) {
            meta.copy(
                imdbRating = mdblistRating.toFloat(),
                ratingSource = TitleRatingSource.IMDB
            )
        } else {
            meta
        }
    }
}
```

- [ ] **Step 4: Run precedence tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.TitleRatingOverrideRepositoryTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TitleRatingOverrideRepository.kt app/src/test/java/com/nexio/tv/data/repository/TitleRatingOverrideRepositoryTest.kt
git commit -m "feat: centralize title rating overrides"
```

---

### Task 7: Apply Overrides in Home and Detail Flows

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTvdbTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbAdvancedMetadataTest.kt`

- [ ] **Step 1: Write failing home coordinator test**

Add to `HomeCatalogRefreshCoordinatorTvdbTest.kt`:

```kotlin
@Test
fun `home enrichment preserves tmdb source until title override replaces it`() = runTest {
    val base = MetaPreview(
        id = "tmdb:1399",
        type = ContentType.SERIES,
        name = "Game of Thrones",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2011",
        imdbRating = 8.1f,
        ratingSource = TitleRatingSource.TMDB,
        genres = emptyList()
    )
    val overridden = base.copy(imdbRating = 9.2f, ratingSource = TitleRatingSource.IMDB)

    assertEquals(TitleRatingSource.TMDB, base.ratingSource)
    assertEquals(TitleRatingSource.IMDB, overridden.ratingSource)
}
```

This is a small model-level guard in the coordinator test file. The integration behavior will be covered by the mocked repository call in Step 2.

- [ ] **Step 2: Update constructors to inject override repository**

Replace direct `MDBListRepository` preview enrichment calls in home refresh with `TitleRatingOverrideRepository`.

In `HomeViewModel.kt`, add constructor dependency:

```kotlin
internal val titleRatingOverrideRepository: TitleRatingOverrideRepository,
```

In `HomeCatalogRefreshCoordinator.kt`, add constructor parameter:

```kotlin
private val titleRatingOverrideRepository: TitleRatingOverrideRepository,
```

Replace:

```kotlin
val enriched = mdbListRepository.enrichPreview(localized)
```

with:

```kotlin
val enriched = titleRatingOverrideRepository.enrichPreview(localized)
```

Keep `MDBListRepository` injected where episode or detail MDBList rows still need full provider ratings.

- [ ] **Step 3: Apply focused preview override**

In `HomeViewModelPresentationPipeline.kt`, after `mergeFocusedItemEnrichment(...)`, call:

```kotlin
val ratingResolved = titleRatingOverrideRepository.enrichPreview(enriched)
```

Use `ratingResolved` for the state update instead of `enriched`.

- [ ] **Step 4: Apply detail override after TMDB/TVDB enrichment**

In `MetaDetailsViewModel.kt`, after `updated` is fully enriched and before `_uiState.update { ... }`, add:

```kotlin
updated = titleRatingOverrideRepository.enrichMeta(
    meta = updated,
    fallbackItemId = itemId,
    fallbackItemType = itemType
)
```

This preserves the detail MDBList ratings row while also allowing the primary hero rating badge to switch to real IMDb when configured.

- [ ] **Step 5: Run focused home/detail tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTvdbTest --tests com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsTvdbAdvancedMetadataTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTvdbTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbAdvancedMetadataTest.kt
git commit -m "feat: apply title rating overrides in home and detail"
```

---

### Task 8: Verification Pass

**Files:**
- Verify modified files only.

- [ ] **Step 1: Run rating-related unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.HomeDisplayMetadataTest --tests com.nexio.tv.core.tvdb.TvMetadataRouterTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceTest --tests com.nexio.tv.data.repository.MDBListTitleRatingsTest --tests com.nexio.tv.data.remote.CustomImdbClientTest --tests com.nexio.tv.data.repository.CustomImdbTitleRatingsRepositoryTest --tests com.nexio.tv.data.repository.TitleRatingOverrideRepositoryTest --tests com.nexio.tv.ui.screens.detail.EpisodeRatingBadgeSupportTest --tests com.nexio.tv.ui.screens.home.ModernHomeModelsTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run home/detail integration-adjacent tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTvdbTest --tests com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsTvdbAdvancedMetadataTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsTvdbProviderRoutingTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run diff hygiene check**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 4: Inspect changed files**

Run:

```bash
git diff --stat
```

Expected: changes are limited to title rating model, TMDB/MDBList/custom IMDb repositories, home/detail rating display, and tests.

- [ ] **Step 5: Commit verification-only adjustments if needed**

If Step 1-4 required code/test adjustments, commit them:

```bash
git add app/src/main/java app/src/test/java
git commit -m "test: cover title rating provider precedence"
```

If there are no adjustments, do not create an empty commit.

---

## Self-Review

**Spec coverage**

- A) Default movie/show ratings from TMDB with TMDB icon: covered by Tasks 2 and 3.
- B) MDBList configured replaces TMDB score with IMDb: covered by Tasks 4, 6, and 7.
- C) Custom IMDb ratings API configured replaces TMDB/MDBList using `/v1/ratings/bulk`: covered by Tasks 5, 6, and 7.
- Existing episode ratings remain provider-aware: Task 3 reuses the same badge-source pattern and keeps episode tests active.
- TVDB `score` does not re-enter rating display: Task 2 keeps TVDB/Kitsu out of the IMDb/TMDB title badge unless a provider source exists.

**Placeholder scan**

- No forbidden placeholder patterns were found.
- Each task has exact files, code shape, command, and expected result.

**Type consistency**

- `TitleRatingSource` is the single source enum.
- `ratingSource` is carried by `MetaPreview`, `Meta`, `HomeDisplayMetadata`, and `HeroPreview`.
- Override precedence is centralized in `TitleRatingOverrideRepository`.
