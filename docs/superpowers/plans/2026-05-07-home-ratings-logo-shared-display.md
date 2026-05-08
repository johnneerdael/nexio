# Home Ratings Logo Shared Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent invalid first-paint ratings and propagate hydrated TVDB logos to Home and Screensaver through shared rating/artwork display systems.

**Architecture:** Add shared rating validation and formatting in the domain model layer, quarantine invalid preview ratings before they become display fields, sanitize stale snapshot ratings on read/write, and verify hydrated logo refs survive `HydratedHomeOverlay -> ResolvedDisplaySurface -> ScreensaverCandidateRepository`. UI composables should consume validated display strings/refs and must not gain local TVDB/logo fallback behavior.

**Tech Stack:** Kotlin, Android Compose, JUnit4, MockK, kotlinx-coroutines-test, existing MetadataRouter/ArtworkRouter/HomeDisplayMetadata/ResolvedDisplaySurface infrastructure.

---

## Scope Check

This plan covers three coupled packets because all three share the same Home/Screensaver display boundary:

```text
Packet A: shared rating validation and Locale.US display formatting
Packet B: preview rating quarantine and stale snapshot cleanup
Packet C: hydrated logo propagation to Home/Screensaver display surfaces
```

Do not add local fallback logic in `ModernHomeHero`, `IdleScreensaverOverlay`, or `ContentCard`. Those files may call the shared formatter, but they must not decide which provider/rating/logo wins.

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/domain/model/TitleRating.kt`
  - Owns shared title-rating validation and display formatting.
- Modify: `app/src/main/java/com/nexio/tv/domain/model/RailItemPreview.kt`
  - Converts preview rating seeds into display-safe preview ratings.
- Modify: `app/src/main/java/com/nexio/tv/domain/model/RailPreviewLegacyAdapters.kt`
  - Uses shared formatter when serializing legacy preview rating text.
- Modify: `app/src/main/java/com/nexio/tv/data/local/MetadataModelSanitizers.kt`
  - Clears out-of-range cached title ratings without rejecting the whole snapshot.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`
  - Prevents invalid `ResolvedDisplayItem.rating` publication.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt`
  - Preserves hydrated logo refs and does not project invalid ratings.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt`
  - Keeps final idle screensaver models rating-safe and logo-backed by `ArtworkBundle.logo`.
- Modify: rating display call sites:
  - `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
  - `app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodeRatingsSection.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/detail/ReviewsSection.kt`
  - `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverOverlay.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptions.kt`
  - `app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`
  - Adds debug-safe trace for first-paint/hydrated rating and logo status; merges sparse artwork bundles per type.
- Tests:
  - `app/src/test/java/com/nexio/tv/domain/model/RatingDisplayFormatterTest.kt`
  - `app/src/test/java/com/nexio/tv/domain/model/RailItemPreviewBridgeTest.kt`
  - `app/src/test/java/com/nexio/tv/data/local/MetadataModelSanitizersTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt`
  - `app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorTest.kt`
  - `app/src/test/java/com/nexio/tv/architecture/RatingDisplayBoundaryTest.kt`

---

### Task 1: Shared Rating Formatter And Validator

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/TitleRating.kt`
- Create: `app/src/test/java/com/nexio/tv/domain/model/RatingDisplayFormatterTest.kt`

- [ ] **Step 1: Write the failing formatter and validator tests**

Create `app/src/test/java/com/nexio/tv/domain/model/RatingDisplayFormatterTest.kt`:

```kotlin
package com.nexio.tv.domain.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingDisplayFormatterTest {
    @Test
    fun `title rating formatter uses dot decimal under dutch locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("nl", "NL"))

            assertEquals("8.3", RatingDisplayFormatter.formatTitleRating(8.3))
            assertEquals("8.0", RatingDisplayFormatter.formatTitleRating(8.0f))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `aggregate percentage formatter keeps whole numbers compact and decimals locale safe`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("nl", "NL"))

            assertEquals("87", RatingDisplayFormatter.formatPercentRating(87.0))
            assertEquals("87.5", RatingDisplayFormatter.formatPercentRating(87.5))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `title rating validator accepts only finite zero through ten values`() {
        assertTrue(RatingValueValidator.validTitleRating(0.0))
        assertTrue(RatingValueValidator.validTitleRating(8.3))
        assertTrue(RatingValueValidator.validTitleRating(10.0))
        assertFalse(RatingValueValidator.validTitleRating(-0.1))
        assertFalse(RatingValueValidator.validTitleRating(10.1))
        assertFalse(RatingValueValidator.validTitleRating(1767427.0))
        assertFalse(RatingValueValidator.validTitleRating(Double.NaN))
        assertFalse(RatingValueValidator.validTitleRating(Double.POSITIVE_INFINITY))
        assertFalse(RatingValueValidator.validTitleRating(null))
    }

    @Test
    fun `title rating sanitizer returns null for out of range values`() {
        assertEquals(8.3, RatingValueValidator.sanitizeTitleRating(8.3) ?: 0.0, 0.0)
        assertEquals(8.3f, RatingValueValidator.sanitizeTitleRating(8.3f) ?: 0f, 0f)
        assertNull(RatingValueValidator.sanitizeTitleRating(152596.0))
        assertNull(RatingValueValidator.sanitizeTitleRating(152596f))
    }
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.domain.model.RatingDisplayFormatterTest"
```

Expected: FAIL with unresolved references for `RatingDisplayFormatter` and `RatingValueValidator`.

- [ ] **Step 3: Add shared formatter and validator without replacing existing rating sources**

Modify `app/src/main/java/com/nexio/tv/domain/model/TitleRating.kt` by adding these imports/helpers. Do not replace the file wholesale, and do not remove any existing `TitleRatingSource` enum values, `TitleRating` fields, conversion helpers, or imports. If this file already has imports, merge `java.util.Locale` into the existing import block.

```kotlin
import java.util.Locale

object RatingValueValidator {
    fun validTitleRating(value: Double?): Boolean =
        value != null && value.isFinite() && value in 0.0..10.0

    fun validTitleRating(value: Float?): Boolean =
        value != null && value.isFinite() && value in 0f..10f

    fun validPercentRating(value: Double?): Boolean =
        value != null && value.isFinite() && value in 0.0..100.0

    fun validPercentRating(value: Float?): Boolean =
        value != null && value.isFinite() && value in 0f..100f

    fun sanitizeTitleRating(value: Double?): Double? =
        value?.takeIf(::validTitleRating)

    fun sanitizeTitleRating(value: Float?): Float? =
        value?.takeIf(::validTitleRating)

    fun sanitizePercentRating(value: Double?): Double? =
        value?.takeIf(::validPercentRating)
}

object RatingDisplayFormatter {
    fun formatTitleRating(value: Double): String =
        String.format(Locale.US, "%.1f", value)

    fun formatTitleRating(value: Float): String =
        formatTitleRating(value.toDouble())

    fun formatPercentRating(value: Double): String =
        if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
}
```

After adding these helpers, verify the pre-existing `TitleRatingSource` enum declaration still contains every value it had before this task. If the enum currently has only `IMDB` and `TMDB`, leave it that way; do not use this task to add or remove source values.

- [ ] **Step 4: Run the formatter tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.domain.model.RatingDisplayFormatterTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/TitleRating.kt app/src/test/java/com/nexio/tv/domain/model/RatingDisplayFormatterTest.kt
git commit -m "feat: add shared rating display validation"
```

---

### Task 2: Quarantine Invalid Rail Preview Ratings

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/RailItemPreview.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/RailPreviewLegacyAdapters.kt`
- Modify: `app/src/test/java/com/nexio/tv/domain/model/RailItemPreviewBridgeTest.kt`

- [ ] **Step 1: Add failing preview rating quarantine tests**

Append these tests to `RailItemPreviewBridgeTest`:

```kotlin
    @Test
    fun `rail preview rejects title rating above ten`() {
        val preview = RailItemPreview(
            railId = "tmdb_trending_series",
            railSource = RailSource.BUILT_IN_TMDB,
            sourceProvider = ProviderId.TMDB,
            sourceItemId = "tmdb:94997",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(tmdb = "94997"),
            display = RailDisplaySeed(
                title = "House of the Dragon",
                rating = RatingSeed(provider = ProviderId.TMDB, value = 1767427.0)
            ),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-hotd",
            generatedAtMs = 1_000L
        )

        val item = preview.toMetaPreview()

        assertEquals(null, item.imdbRating)
        assertEquals(null, item.ratingSource)
        assertEquals(1767427.0, preview.display.toPreviewRating().rejected?.rawValue ?: 0.0, 0.0)
        assertEquals("rating.value", preview.display.toPreviewRating().rejected?.rawField)
        assertEquals("OUT_OF_RANGE_TITLE_RATING", preview.display.toPreviewRating().rejected?.reason)
    }

    @Test
    fun `rail preview rejects popularity-like rating text`() {
        val preview = RailItemPreview(
            railId = "tmdb_trending_series",
            railSource = RailSource.BUILT_IN_TMDB,
            sourceProvider = ProviderId.TMDB,
            sourceItemId = "tmdb:94997",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(tmdb = "94997"),
            display = RailDisplaySeed(
                title = "House of the Dragon",
                ratingText = "1767427"
            ),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-hotd",
            generatedAtMs = 1_000L
        )

        val item = preview.toMetaPreview()

        assertEquals(null, item.imdbRating)
        assertEquals(null, item.ratingSource)
        assertEquals("ratingText", preview.display.toPreviewRating().rejected?.rawField)
    }

    @Test
    fun `legacy rail preview rating text uses locale safe formatter`() {
        val legacy = MetaPreview(
            id = "tmdb:27205",
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

        val preview = legacy.toLegacyRailItemPreview(railId = "tmdb_trending_movies")

        assertEquals("8.3", preview.display.ratingText)
        assertEquals(8.3, preview.display.rating?.value ?: 0.0, 0.0)
        assertEquals(ProviderId.TMDB, preview.display.rating?.provider)
    }

    @Test
    fun `valid rating text without provider uses trusted fallback source provider`() {
        val preview = RailItemPreview(
            railId = "tmdb_trending_series",
            railSource = RailSource.BUILT_IN_TMDB,
            sourceProvider = ProviderId.TMDB,
            sourceItemId = "tmdb:94997",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(tmdb = "94997"),
            display = RailDisplaySeed(
                title = "House of the Dragon",
                ratingText = "8.3"
            ),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-hotd",
            generatedAtMs = 1_000L
        )

        val item = preview.toMetaPreview()

        assertEquals(8.3f, item.imdbRating ?: 0f, 0f)
        assertEquals(TitleRatingSource.TMDB, item.ratingSource)
        assertEquals(ProviderId.TMDB, preview.display.toPreviewRating(fallbackProvider = ProviderId.TMDB).source)
    }

    @Test
    fun `valid rating text without trusted provider is rejected`() {
        val resolution = RailDisplaySeed(
            title = "Unknown source title",
            ratingText = "8.3"
        ).toPreviewRating(fallbackProvider = null)

        assertEquals(null, resolution.rating)
        assertEquals(null, resolution.source)
        assertEquals("MISSING_RATING_SOURCE", resolution.rejected?.reason)
        assertEquals("ratingText", resolution.rejected?.rawField)
    }
```

- [ ] **Step 2: Run the bridge tests and verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.domain.model.RailItemPreviewBridgeTest"
```

Expected: FAIL because out-of-range values still populate `MetaPreview.imdbRating`, and `toPreviewRating()` is not defined.

- [ ] **Step 3: Add typed preview rating resolution**

Modify `app/src/main/java/com/nexio/tv/domain/model/RailItemPreview.kt` by adding these data classes below `RatingSeed`:

```kotlin
data class RejectedPreviewRating(
    val rawValue: Double,
    val rawField: String,
    val reason: String
)

data class PreviewRatingResolution(
    val rating: Float?,
    val source: ProviderId?,
    val rejected: RejectedPreviewRating?
)
```

Then replace the rating extraction inside `fun RailItemPreview.toMetaPreview()` with:

```kotlin
    val previewRating = display.toPreviewRating(fallbackProvider = sourceProvider)
    val ratingSource = previewRating.source.toTitleRatingSource()
```

and set these fields in the returned `MetaPreview`:

```kotlin
        imdbRating = previewRating.rating,
        ratingSource = ratingSource,
```

Add this function near `toMetaPreview()`:

```kotlin
fun RailDisplaySeed.toPreviewRating(fallbackProvider: ProviderId? = null): PreviewRatingResolution {
    val rawFromText = ratingText?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val raw = rawFromText ?: rating?.value
    val rawField = when {
        rawFromText != null -> "ratingText"
        rating != null -> "rating.value"
        else -> null
    }

    if (raw == null || rawField == null) {
        return PreviewRatingResolution(rating = null, source = null, rejected = null)
    }

    val source = rating?.provider ?: when (fallbackProvider) {
        ProviderId.IMDB,
        ProviderId.TMDB -> fallbackProvider
        else -> null
    }

    if (source == null) {
        return PreviewRatingResolution(
            rating = null,
            source = null,
            rejected = RejectedPreviewRating(
                rawValue = raw,
                rawField = rawField,
                reason = "MISSING_RATING_SOURCE"
            )
        )
    }

    return if (RatingValueValidator.validTitleRating(raw)) {
        PreviewRatingResolution(
            rating = raw.toFloat(),
            source = source,
            rejected = null
        )
    } else {
        PreviewRatingResolution(
            rating = null,
            source = null,
            rejected = RejectedPreviewRating(
                rawValue = raw,
                rawField = rawField,
                reason = "OUT_OF_RANGE_TITLE_RATING"
            )
        )
    }
}
```

The `ProviderId?.toTitleRatingSource()` function can stay private and unchanged. If a future provider needs to display a title-rating badge, add that source deliberately in a separate task instead of defaulting an unknown provider to IMDb.

- [ ] **Step 4: Use the shared formatter in legacy rail conversion**

Modify `app/src/main/java/com/nexio/tv/domain/model/RailPreviewLegacyAdapters.kt`:

Replace:

```kotlin
private fun formatRailPreviewRatingText(rating: Float): String {
    return if (rating % 1f == 0f) {
        rating.toInt().toString()
    } else {
        rating.toString()
    }
}
```

with:

```kotlin
private fun formatRailPreviewRatingText(rating: Float): String =
    RatingDisplayFormatter.formatTitleRating(rating)
```

- [ ] **Step 5: Run the bridge tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.domain.model.RailItemPreviewBridgeTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/RailItemPreview.kt app/src/main/java/com/nexio/tv/domain/model/RailPreviewLegacyAdapters.kt app/src/test/java/com/nexio/tv/domain/model/RailItemPreviewBridgeTest.kt
git commit -m "fix: quarantine invalid preview ratings"
```

---

### Task 3: Sanitize Stale Cached Rating Values

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/MetadataModelSanitizers.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/MetadataModelSanitizersTest.kt`

- [ ] **Step 1: Add failing sanitizer tests**

Append these tests to `MetadataModelSanitizersTest`:

```kotlin
    @Test
    fun `sanitize preview clears out of range imdb rating without dropping item`() {
        val preview = MetaPreview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            name = "House of the Dragon",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2022",
            imdbRating = 1767427f,
            ratingSource = TitleRatingSource.TMDB,
            genres = emptyList()
        )

        val sanitized = preview.sanitizedForCache()

        assertNull(sanitized.imdbRating)
        assertNull(sanitized.ratingSource)
        assertEquals("House of the Dragon", sanitized.name)
    }

    @Test
    fun `sanitize home display metadata clears out of range title rating`() {
        val metadata = HomeDisplayMetadata(
            title = "Widow's Bay",
            imdbRating = 15129f,
            ratingSource = TitleRatingSource.IMDB
        )

        val sanitized = metadata.sanitizedForCache()

        assertNull(sanitized.imdbRating)
        assertNull(sanitized.ratingSource)
        assertEquals("Widow's Bay", sanitized.title)
    }

    @Test
    fun `sanitize title metadata keeps valid rating source`() {
        val preview = MetaPreview(
            id = "tmdb:550",
            type = ContentType.MOVIE,
            name = "Fight Club",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "1999",
            imdbRating = 8.8f,
            ratingSource = TitleRatingSource.IMDB,
            genres = emptyList()
        )

        val sanitized = preview.sanitizedForCache()

        assertEquals(8.8f, sanitized.imdbRating ?: 0f, 0f)
        assertEquals(TitleRatingSource.IMDB, sanitized.ratingSource)
    }

    @Test
    fun `sanitize preview with null rating clears stale rating source`() {
        val preview = MetaPreview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            name = "House of the Dragon",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2022",
            imdbRating = null,
            ratingSource = TitleRatingSource.IMDB,
            genres = emptyList()
        )

        val sanitized = preview.sanitizedForCache()

        assertNull(sanitized.imdbRating)
        assertNull(sanitized.ratingSource)
    }

    @Test
    fun `sanitize valid rating with null source defaults to imdb by compatibility policy`() {
        val preview = MetaPreview(
            id = "tt0137523",
            type = ContentType.MOVIE,
            name = "Fight Club",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "1999",
            imdbRating = 8.8f,
            ratingSource = null,
            genres = emptyList()
        )

        val sanitized = preview.sanitizedForCache()

        assertEquals(8.8f, sanitized.imdbRating ?: 0f, 0f)
        assertEquals(TitleRatingSource.IMDB, sanitized.ratingSource)
    }
```

- [ ] **Step 2: Run sanitizer tests and verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.local.MetadataModelSanitizersTest"
```

Expected: FAIL because invalid ratings are still preserved.

- [ ] **Step 3: Add rating cleanup helpers**

Modify `app/src/main/java/com/nexio/tv/data/local/MetadataModelSanitizers.kt` imports:

```kotlin
import com.nexio.tv.domain.model.RatingValueValidator
```

Add these helpers near `sanitizedPremiumArtworkRef()`:

```kotlin
private fun Float?.sanitizedTitleRating(): Float? =
    RatingValueValidator.sanitizeTitleRating(this)

private fun Double?.sanitizedTitleRating(): Double? =
    RatingValueValidator.sanitizeTitleRating(this)

private fun TitleRatingSource?.sanitizedForTitleRating(sanitized: Float?): TitleRatingSource? =
    if (sanitized != null) this ?: TitleRatingSource.IMDB else null

private fun TitleRatingSource?.sanitizedForTitleRating(sanitized: Double?): TitleRatingSource? =
    if (sanitized != null) this ?: TitleRatingSource.IMDB else null
```

- [ ] **Step 4: Apply rating cleanup in model sanitizers**

Replace `MetaPreview.sanitizedForCache()` with:

```kotlin
internal fun MetaPreview.sanitizedForCache(): MetaPreview {
    val cleanRating = imdbRating.sanitizedTitleRating()
    val cleanPoster = poster.sanitizedPremiumArtworkRef()
    return copy(
        poster = cleanPoster,
        posterProviderTag = posterProviderTag.takeIf { cleanPoster != null },
        imdbRating = cleanRating,
        ratingSource = ratingSource.sanitizedForTitleRating(cleanRating),
        genres = genres.orEmpty(),
        trailerYtIds = trailerYtIds.orEmpty(),
        firstPaintSource = (firstPaintSource as FirstPaintSource?) ?: FirstPaintSource.ADDON_META_PREVIEW,
        firstPaintStableIds = (firstPaintStableIds as ProviderIds?) ?: ProviderIds()
    )
}
```

Replace `HomeDisplayMetadata.sanitizedForCache()` with:

```kotlin
internal fun HomeDisplayMetadata.sanitizedForCache(): HomeDisplayMetadata {
    val cleanRating = imdbRating.sanitizedTitleRating()
    val cleanPoster = poster.sanitizedPremiumArtworkRef()
    return copy(
        poster = cleanPoster,
        posterProviderTag = posterProviderTag.takeIf { cleanPoster != null },
        imdbRating = cleanRating,
        ratingSource = ratingSource.sanitizedForTitleRating(cleanRating),
        genres = genres.orEmpty()
    )
}
```

Replace `Meta.sanitizedForCache()` rating lines with:

```kotlin
internal fun Meta.sanitizedForCache(): Meta {
    val cleanRating = imdbRating.sanitizedTitleRating()
    val cleanPoster = poster.sanitizedPremiumArtworkRef()
    return copy(
        poster = cleanPoster,
        posterProviderTag = posterProviderTag.takeIf { cleanPoster != null },
        imdbRating = cleanRating,
        ratingSource = ratingSource.sanitizedForTitleRating(cleanRating),
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
}
```

- [ ] **Step 5: Run sanitizer and snapshot tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.local.MetadataModelSanitizersTest" --tests "com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest"
```

Expected: PASS. If legacy tests expected `TitleRatingSource.IMDB` when an invalid rating is present, update only those assertions to expect `null`; valid-rating legacy tests must still expect `IMDB`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/MetadataModelSanitizers.kt app/src/test/java/com/nexio/tv/data/local/MetadataModelSanitizersTest.kt
git commit -m "fix: sanitize stale out-of-range home ratings"
```

---

### Task 4: Publish Only Valid Ratings To Resolved Home And Screensaver Surfaces

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt`

- [ ] **Step 1: Add failing resolved display rating tests**

Append this test to `HomeResolvedDisplayMapperTest`:

```kotlin
    @Test
    fun `mapper does not publish out of range preview rating to resolved surface`() {
        val finalItem = preview(
            id = "tmdb:94997",
            title = "House of the Dragon",
            overview = "Overview",
            rating = 1767427f,
            artwork = ArtworkBundle(),
            stableIds = ProviderIds(tmdb = "94997")
        )

        val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(finalItem)),
            overlaysByItemKey = emptyMap(),
            nowMs = 10_000L
        ).single()

        assertEquals(null, resolved.rating)
    }
```

Append this test to `ScreensaverCandidateRepositoryTest`:

```kotlin
    @Test
    fun `image candidates clear invalid resolved ratings but preserve logo artwork`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        val logo = artworkRef(key = "logo-94997", imageType = ArtworkType.LOGO)
        val backdrop = artworkRef(key = "backdrop-94997", imageType = ArtworkType.BACKDROP)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "series:tmdb:94997",
                    title = "House of the Dragon",
                    artwork = ArtworkBundle(backdrop = backdrop, logo = logo),
                    rating = TitleRating(1767427.0, TitleRatingSource.TMDB)
                )
            )
        )

        val candidate = repository.observeImageCandidates(profileId = 1).first().single()

        assertEquals(null, candidate.rating)
        assertSame(logo, candidate.artwork.logo)
        assertSame(backdrop, candidate.preferredImage)
    }
```

If `resolvedItem(...)` in `ScreensaverCandidateRepositoryTest` does not currently accept a `rating` parameter, update that test helper signature to:

```kotlin
    private fun resolvedItem(
        itemKey: String = "movie:tmdb:550",
        title: String? = "Fight Club",
        artwork: ArtworkBundle = ArtworkBundle(
            backdrop = artworkRef(key = "backdrop-550", imageType = ArtworkType.BACKDROP)
        ),
        rating: TitleRating? = TitleRating(8.8, TitleRatingSource.IMDB),
        sourceTrace: List<HydratedHomeFieldTrace> = emptyList()
    ) = ResolvedDisplayItem(
        itemKey = itemKey,
        contentId = "tmdb:550",
        parentId = "tmdb:550",
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = "TMDB",
        canonicalId = "550",
        imdbId = "tt0137523",
        stableIds = ProviderIds(imdb = "tt0137523", tmdb = "550"),
        display = ResolvedDisplayFields(
            title = title,
            originalTitle = null,
            year = 1999,
            releaseDate = "1999",
            overview = "Overview",
            genres = listOf("Drama", "Thriller"),
            runtimeText = "139m"
        ),
        artwork = artwork,
        rating = rating,
        trailer = TrailerDisplayState(),
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = sourceTrace,
        updatedAtMs = 1_000L
    )
```

- [ ] **Step 2: Run the targeted tests and verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest" --tests "com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest"
```

Expected: FAIL because invalid ratings are still published or projected.

- [ ] **Step 3: Validate ratings in HomeResolvedDisplayMapper**

Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt` imports:

```kotlin
import com.nexio.tv.domain.model.RatingValueValidator
```

Replace both `rating = fields.imdbRating?.let { ... }` assignments with:

```kotlin
            rating = fields.imdbRating
                ?.takeIf { RatingValueValidator.validTitleRating(it) }
                ?.let { value -> TitleRating(value.toDouble(), ratingSource) },
```

- [ ] **Step 4: Validate ratings in ScreensaverCandidateRepository**

Modify `ScreensaverCandidateRepository.kt` imports:

```kotlin
import com.nexio.tv.domain.model.RatingValueValidator
import com.nexio.tv.domain.model.TitleRating
```

Add this helper near the bottom of the file:

```kotlin
    private fun TitleRating?.validTitleRatingOrNull(): TitleRating? =
        this?.takeIf { rating -> RatingValueValidator.validTitleRating(rating.value) }
```

Change both candidate constructors from:

```kotlin
            rating = rating,
```

to:

```kotlin
            rating = rating.validTitleRatingOrNull(),
```

- [ ] **Step 5: Validate final idle screensaver rating models**

Modify `IdleScreensaverPreparation.kt` imports:

```kotlin
import com.nexio.tv.domain.model.RatingValueValidator
```

Replace both:

```kotlin
        imdbRating = rating?.value?.toFloat(),
```

with:

```kotlin
        imdbRating = rating?.value
            ?.takeIf { RatingValueValidator.validTitleRating(it) }
            ?.toFloat(),
```

- [ ] **Step 6: Run the targeted tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest" --tests "com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt
git commit -m "fix: publish only valid display ratings"
```

---

### Task 5: Replace Locale-Sensitive Rating Formatting Call Sites

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodeRatingsSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/ReviewsSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverOverlay.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptions.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/RatingDisplayBoundaryTest.kt`

- [ ] **Step 1: Add a failing architecture test for rating formatting**

Create `app/src/test/java/com/nexio/tv/architecture/RatingDisplayBoundaryTest.kt`:

```kotlin
package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingDisplayBoundaryTest {
    @Test
    fun `rating badge code does not use locale sensitive one decimal formatting`() {
        val sourceRoot = File("src/main/java/com/nexio/tv")
        val offenders = sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.endsWith("domain/model/TitleRating.kt") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (line.contains("String.format(\"%.1f\"") || line.contains("\"%.1f\".format(")) {
                        "${file.invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue(
            "Use RatingDisplayFormatter for rating decimals instead of locale-sensitive String.format: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `ui does not add direct tvdb logo fallback behavior`() {
        val uiRoot = File("src/main/java/com/nexio/tv/ui")
        val offenders = uiRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val importsTvdbService = line.contains("import com.nexio.tv.core.tvdb") ||
                        line.contains("import com.nexio.tv.data.integration.tvdb")
                    val localTvdbLogoFallback = line.contains("tvdb", ignoreCase = true) &&
                        line.contains("logo", ignoreCase = true) &&
                        (line.contains("?:") || line.contains("if") || line.contains("="))
                    if (importsTvdbService || localTvdbLogoFallback) {
                        "${file.invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue(
            "UI must consume hydrated ArtworkBundle.logo and must not implement provider-specific TVDB logo fallback: $offenders",
            offenders.isEmpty()
        )
    }
}
```

- [ ] **Step 2: Run the architecture test and verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.architecture.RatingDisplayBoundaryTest"
```

Expected: FAIL listing current `String.format("%.1f", ...)` call sites.

- [ ] **Step 3: Replace home and card rating formatting**

In `ModernHomeModels.kt`, add:

```kotlin
import com.nexio.tv.domain.model.RatingDisplayFormatter
```

Replace each:

```kotlin
String.format("%.1f", it)
```

with:

```kotlin
RatingDisplayFormatter.formatTitleRating(it)
```

Replace `formatPreviewTomatoesRating` with:

```kotlin
private fun formatPreviewTomatoesRating(rating: Double): String =
    RatingDisplayFormatter.formatPercentRating(rating)
```

In `HeroCarousel.kt`, `HomePosterTrailerOptions.kt`, and `ContentCard.kt`, add:

```kotlin
import com.nexio.tv.domain.model.RatingDisplayFormatter
```

Replace rating badge formatting with:

```kotlin
RatingDisplayFormatter.formatTitleRating(rating)
```

- [ ] **Step 4: Replace detail and screensaver rating formatting**

In `HeroSection.kt`, `EpisodeRatingsSection.kt`, `ReviewsSection.kt`, and `IdleScreensaverOverlay.kt`, add:

```kotlin
import com.nexio.tv.domain.model.RatingDisplayFormatter
```

Use title formatting for IMDb/TMDB/Letterboxd-style 0..10 ratings:

```kotlin
RatingDisplayFormatter.formatTitleRating(rating)
```

Use percent formatting for Rotten Tomatoes/audience/metacritic-style percentages:

```kotlin
RatingDisplayFormatter.formatPercentRating(rating)
```

For `ReviewsSection.kt`, replace:

```kotlin
String.format("%.1f/10", rating)
```

with:

```kotlin
"${RatingDisplayFormatter.formatTitleRating(rating)}/10"
```

- [ ] **Step 5: Run architecture and relevant UI model tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.architecture.RatingDisplayBoundaryTest" --tests "com.nexio.tv.domain.model.RatingDisplayFormatterTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodeRatingsSection.kt app/src/main/java/com/nexio/tv/ui/screens/detail/ReviewsSection.kt app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverOverlay.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptions.kt app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt app/src/test/java/com/nexio/tv/architecture/RatingDisplayBoundaryTest.kt
git commit -m "fix: use shared rating formatter"
```

---

### Task 6: Preserve Hydrated Logo Artwork Through Overlay And Screensaver Projection

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt`

- [ ] **Step 1: Add failing overlay hydrated-logo and fallback-logo tests**

Append this test to `HomeHydrationCoordinatorTest`:

```kotlin
    @Test
    fun `hydration overlay includes hydrated logo when resolver returns logo`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val overlaySlot = slot<HydratedHomeOverlay>()
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit
        val hydratedLogo = artworkRef("tvdb-logo", ArtworkType.LOGO)
        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "Canonical title",
                artwork = ArtworkBundle(logo = hydratedLogo)
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("series:tmdb:94997")
        val preview = preview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            rating = 8.3f,
            artwork = ArtworkBundle()
        )

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = store,
            traceEvents = TraceMetadataEvents(RecordingTraceSink()) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        assertSame(hydratedLogo, overlaySlot.captured.fields.artwork?.logo)
    }

    @Test
    fun `hydrated logo overrides preview logo when available`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val overlaySlot = slot<HydratedHomeOverlay>()
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit
        val previewLogo = artworkRef("preview-logo", ArtworkType.LOGO)
        val hydratedLogo = artworkRef("tvdb-logo", ArtworkType.LOGO)
        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "Canonical title",
                artwork = ArtworkBundle(logo = hydratedLogo)
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("series:tmdb:94997")
        val preview = preview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            rating = 8.3f,
            artwork = ArtworkBundle(logo = previewLogo)
        )

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = store,
            traceEvents = TraceMetadataEvents(RecordingTraceSink()) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        assertSame(hydratedLogo, overlaySlot.captured.fields.artwork?.logo)
    }

    @Test
    fun `preview logo survives when hydrated logo is absent`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val overlaySlot = slot<HydratedHomeOverlay>()
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit
        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "Canonical title",
                artwork = ArtworkBundle(
                    backdrop = artworkRef("canonical-backdrop", ArtworkType.BACKDROP)
                )
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("series:tmdb:94997")
        val previewLogo = artworkRef("preview-logo", ArtworkType.LOGO)
        val preview = preview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            rating = 8.3f,
            artwork = ArtworkBundle(logo = previewLogo)
        )

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = store,
            traceEvents = TraceMetadataEvents(RecordingTraceSink()) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        assertSame(previewLogo, overlaySlot.captured.fields.artwork?.logo)
        assertNotNull(overlaySlot.captured.fields.artwork?.backdrop)
    }
```

If `HomeHydrationCoordinatorTest` does not have `artworkRef` and `preview` helpers with those signatures, add:

```kotlin
    private fun artworkRef(key: String, type: ArtworkType): ArtworkDisplayRef.RuntimeAsset =
        ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey(key),
            assetKey = null,
            imageType = type,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.PRIMARY,
            trace = ArtworkTrace(selectedProvider = "TVDB", sourceRole = "PRIMARY")
        )

    private fun preview(
        id: String,
        type: ContentType,
        rating: Float?,
        artwork: ArtworkBundle
    ) = MetaPreview(
        id = id,
        type = type,
        rawType = type.toApiString(),
        name = "Preview title",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2022",
        imdbRating = rating,
        ratingSource = TitleRatingSource.TMDB,
        genres = emptyList(),
        artwork = artwork,
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintSourceProvider = ProviderId.TMDB,
        firstPaintStableIds = ProviderIds(tmdb = "94997"),
        firstPaintRailSource = RailSource.BUILT_IN_TMDB,
        firstPaintSourceItemId = id
    )
```

- [ ] **Step 2: Add HomeResolvedDisplayMapper logo preservation test**

Append this test to `HomeResolvedDisplayMapperTest`:

```kotlin
    @Test
    fun `mapper preserves hydrated logo artwork in resolved display surface`() {
        val logo = artworkRef("logo-94997").copy(imageType = ArtworkType.LOGO)
        val finalItem = preview(
            id = "tmdb:94997",
            title = "House of the Dragon",
            overview = "Overview",
            rating = 8.3f,
            artwork = ArtworkBundle(logo = logo, backdrop = artworkRef("backdrop-94997")),
            stableIds = ProviderIds(tmdb = "94997", tvdb = "371572")
        )

        val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(finalItem)),
            overlaysByItemKey = emptyMap(),
            nowMs = 10_000L
        ).single()

        assertEquals(logo, resolved.artwork.logo)
    }
```

- [ ] **Step 3: Add Screensaver logo preservation assertion**

In `ScreensaverCandidateRepositoryTest`, update `image candidates are projected from resolved display surface with artwork refs rating stable ids and trace` to include a logo:

```kotlin
        val logo = artworkRef(key = "logo-550", imageType = ArtworkType.LOGO)
```

Change the `ArtworkBundle` in that test to:

```kotlin
                    artwork = ArtworkBundle(
                        backdrop = backdrop,
                        poster = artworkRef(key = "poster-550", imageType = ArtworkType.POSTER),
                        logo = logo
                    ),
```

Add this assertion:

```kotlin
        assertSame(logo, candidate.artwork.logo)
```

- [ ] **Step 4: Run logo propagation tests and verify failures**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeHydrationCoordinatorTest" --tests "com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest" --tests "com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest"
```

Expected: At least the sparse-artwork overlay test fails until per-type artwork merge is implemented.

- [ ] **Step 5: Merge hydrated artwork with first-paint fallback per image type**

Modify `HomeHydrationCoordinator.kt`.

Replace:

```kotlin
        val fields = displayMetadata.copy(
            artwork = displayMetadata.artwork ?: item.artwork
        )
```

with:

```kotlin
        val fields = displayMetadata.mergeHydratedArtworkWithFirstPaintFallback(item.artwork)
```

Add this helper inside `HomeHydrationCoordinator`:

```kotlin
    private fun HomeDisplayMetadata.mergeHydratedArtworkWithFirstPaintFallback(
        firstPaintArtwork: ArtworkBundle?
    ): HomeDisplayMetadata {
        if (firstPaintArtwork == null) return this
        val hydratedArtwork = artwork
        val merged = ArtworkBundle(
            poster = hydratedArtwork?.poster ?: firstPaintArtwork.poster,
            backdrop = hydratedArtwork?.backdrop ?: firstPaintArtwork.backdrop,
            logo = hydratedArtwork?.logo ?: firstPaintArtwork.logo,
            thumbnail = hydratedArtwork?.thumbnail ?: firstPaintArtwork.thumbnail
        )
        val mergedOrNull = merged.takeUnless {
            it.poster == null &&
                it.backdrop == null &&
                it.logo == null &&
                it.thumbnail == null
        }
        return copy(artwork = mergedOrNull)
    }
```

Invariant: never replace the entire `ArtworkBundle` with a sparse hydrated bundle. Merge per image type so a hydrated poster/backdrop cannot clear an existing logo, and a hydrated TVDB logo can override a preview logo.

- [ ] **Step 6: Run logo propagation tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeHydrationCoordinatorTest" --tests "com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest" --tests "com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt
git commit -m "fix: preserve hydrated logo artwork"
```

---

### Task 7: Add Rating And Artwork Hydration Trace

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorTest.kt`

- [ ] **Step 1: Add failing trace emission test**

Append this test to `HomeHydrationCoordinatorTest`:

```kotlin
    @Test
    fun `hydration emits rating and artwork surface trace`() = runTest {
        val sink = RecordingTraceSink()
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        coEvery { store.upsert(any(), any()) } returns Unit
        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "House of the Dragon",
                imdbRating = 8.3f,
                ratingSource = TitleRatingSource.TMDB,
                artwork = ArtworkBundle(logo = artworkRef("tvdb-logo", ArtworkType.LOGO))
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("series:tmdb:94997")
        val preview = preview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            rating = null,
            artwork = ArtworkBundle()
        )

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = store,
            traceEvents = TraceMetadataEvents(sink) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        val event = sink.events.last { it.eventType == "home.rating_and_artwork_surface" }
        assertEquals("series:tmdb:94997", event.payload["itemKey"])
        assertEquals(false, event.payload["firstPaintLogoPresent"])
        assertEquals(true, event.payload["hydratedLogoPresent"])
        assertEquals(8.3f, event.payload["hydratedRatingValue"])
        assertEquals("TMDB", event.payload["hydratedRatingSource"])
        assertEquals(null, event.payload["firstPaintTmdbId"])
        assertTrue((event.payload["firstPaintTmdbIdHash"] as String).isNotBlank())
    }

    @Test
    fun `hydration trace uses sanitized first paint rating state`() = runTest {
        val sink = RecordingTraceSink()
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        coEvery { store.upsert(any(), any()) } returns Unit
        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(title = "House of the Dragon")
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("series:tmdb:94997")
        val preview = preview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            rating = 1767427f,
            artwork = ArtworkBundle()
        )

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = store,
            traceEvents = TraceMetadataEvents(sink) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        val event = sink.events.last { it.eventType == "home.rating_and_artwork_surface" }
        assertEquals(1767427f, event.payload["firstPaintRatingValue"])
        assertEquals(false, event.payload["firstPaintRatingAccepted"])
        assertEquals("OUT_OF_RANGE_TITLE_RATING", event.payload["firstPaintRatingRejectReason"])
    }
```

- [ ] **Step 2: Run the trace test and verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeHydrationCoordinatorTest"
```

Expected: FAIL because `home.rating_and_artwork_surface` is not emitted.

- [ ] **Step 3: Add trace event method**

Add this method to `TraceMetadataEvents.kt` near the other home hydration methods:

```kotlin
    fun emitHomeRatingAndArtworkSurface(
        surface: String,
        itemKey: String,
        title: String?,
        firstPaintRatingValue: Float?,
        firstPaintRatingAccepted: Boolean,
        firstPaintRatingRejectReason: String?,
        firstPaintLogoPresent: Boolean,
        firstPaintTmdbIdHash: String?,
        firstPaintTvdbIdHash: String?,
        firstPaintImdbIdHash: String?,
        hydrationStarted: Boolean,
        routeProvider: String?,
        tvdbIdHash: String?,
        overlayApplied: Boolean,
        hydratedRatingValue: Float?,
        hydratedRatingSource: String?,
        hydratedLogoPresent: Boolean,
        hydratedLogoSource: String?
    ) {
        emitHomeHydrationEvent(
            eventType = "home.rating_and_artwork_surface",
            payload = mapOf(
                "surface" to surface,
                "itemKey" to itemKey,
                "title" to optionalTraceValue(title),
                "firstPaintRatingValue" to optionalTraceValue(firstPaintRatingValue),
                "firstPaintRatingAccepted" to firstPaintRatingAccepted,
                "firstPaintRatingRejectReason" to optionalTraceValue(firstPaintRatingRejectReason),
                "firstPaintLogoPresent" to firstPaintLogoPresent,
                "firstPaintTmdbIdHash" to optionalTraceValue(firstPaintTmdbIdHash),
                "firstPaintTvdbIdHash" to optionalTraceValue(firstPaintTvdbIdHash),
                "firstPaintImdbIdHash" to optionalTraceValue(firstPaintImdbIdHash),
                "hydrationStarted" to hydrationStarted,
                "routeProvider" to optionalTraceValue(routeProvider),
                "tvdbIdHash" to optionalTraceValue(tvdbIdHash),
                "overlayApplied" to overlayApplied,
                "hydratedRatingValue" to optionalTraceValue(hydratedRatingValue),
                "hydratedRatingSource" to optionalTraceValue(hydratedRatingSource),
                "hydratedLogoPresent" to hydratedLogoPresent,
                "hydratedLogoSource" to optionalTraceValue(hydratedLogoSource)
            )
        )
    }
```

This event is debug/diagnostic only. It must not include profile IDs, credentials, or raw TMDB/TVDB/IMDb IDs. Hash content IDs before adding them to the trace payload.

- [ ] **Step 4: Emit trace from HomeHydrationCoordinator**

In `HomeHydrationCoordinator.hydrate(...)`, after `val overlayAccepted = onOverlayApplied(overlay)`, add:

```kotlin
            val firstPaintMetadata = item.toHomeDisplayMetadata()
            val previewRating = firstPaintMetadata.imdbRating
            val acceptedPreviewRating = RatingValueValidator.validTitleRating(previewRating)
            val hydratedTvdbId = overlay.canonicalId.takeIf { overlay.canonicalProvider == ProviderId.TVDB }
                ?: bundle?.canonical?.tvdbSeriesId
            traceEvents.emitHomeRatingAndArtworkSurface(
                surface = "HOME",
                itemKey = itemKey,
                title = item.name,
                firstPaintRatingValue = previewRating,
                firstPaintRatingAccepted = acceptedPreviewRating,
                firstPaintRatingRejectReason = if (previewRating != null && !acceptedPreviewRating) {
                    "OUT_OF_RANGE_TITLE_RATING"
                } else {
                    null
                },
                firstPaintLogoPresent = firstPaintMetadata.displayLogo != null,
                firstPaintTmdbIdHash = item.firstPaintStableIds.tmdb?.let { TraceHash.of("home-rating-artwork", it) },
                firstPaintTvdbIdHash = item.firstPaintStableIds.tvdb?.let { TraceHash.of("home-rating-artwork", it) },
                firstPaintImdbIdHash = item.firstPaintStableIds.imdb?.let { TraceHash.of("home-rating-artwork", it) },
                hydrationStarted = true,
                routeProvider = result.route?.provider?.name,
                tvdbIdHash = hydratedTvdbId?.let { TraceHash.of("home-rating-artwork", it) },
                overlayApplied = overlayAccepted,
                hydratedRatingValue = overlay.fields.imdbRating,
                hydratedRatingSource = overlay.fields.ratingSource?.name,
                hydratedLogoPresent = overlay.fields.displayLogo != null,
                hydratedLogoSource = overlay.fields.artwork?.logo?.trace?.selectedProvider
            )
```

Add import:

```kotlin
import com.nexio.tv.core.trace.TraceHash
import com.nexio.tv.domain.model.RatingValueValidator
```

- [ ] **Step 5: Run the trace test**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeHydrationCoordinatorTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorTest.kt
git commit -m "chore: trace home rating and logo hydration"
```

---

### Task 8: Verify TMDB Series Stable ID Hydration Path

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/metadata/router/StableIdBundleResolverTest.kt`

Scope rule: this task is a verification gate for the existing TMDB series -> TVDB path. If the test fails, stop this packet after recording the failure and open a small identity-resolution follow-up. Do not fold a broad identity resolver refactor into this rating/logo display cleanup.

- [ ] **Step 1: Add stable ID regression test**

Append this test to `StableIdBundleResolverTest`:

```kotlin
    @Test
    fun `tmdb series preview resolves tvdb and imdb sidecars for home hydration`() = runTest {
        val resolver = resolver(
            lookup = object : StableIdBundleResolver.Lookup {
                override suspend fun tmdbMovieToImdb(tmdbId: String): String? = null
                override suspend fun imdbToTmdbMovie(imdbId: String): String? = null
                override suspend fun tmdbTvToTvdb(tmdbId: String): String? = "371572"
                override suspend fun tmdbTvToImdb(tmdbId: String): String? = "tt11198330"
                override suspend fun imdbToTvdbSeries(imdbId: String): String? = null
                override suspend fun tvdbSeriesToImdb(tvdbId: String): String? = null
            }
        )

        val bundle = resolver.resolve(
            StableIdBundleRequest(
                route = MetadataRoute(
                    provider = MetadataPrimaryProvider.TVDB,
                    parentId = "tmdb:94997",
                    mediaKind = MetadataMediaKind.SERIES,
                    reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
                    sourceContext = MetadataSourceContext(
                        previewStableIds = ProviderIds(tmdb = "94997"),
                        previewSourceProvider = ProviderId.TMDB.name
                    ),
                    language = "en-US",
                    targetIds = mapOf(MetadataPrimaryProvider.TMDB to "94997"),
                    trace = emptyList()
                ),
                request = MetadataRequest(
                    contentId = "tmdb:94997",
                    contentType = ContentType.SERIES,
                    sourceContext = MetadataSourceContext(
                        previewStableIds = ProviderIds(tmdb = "94997"),
                        previewSourceProvider = ProviderId.TMDB.name
                    ),
                    language = "en-US",
                    depth = MetadataDepth.DETAIL_CORE
                ),
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                itemKey = "series:tmdb:94997"
            )
        )

        assertEquals("371572", bundle.canonical.tvdbSeriesId)
        assertEquals("tt11198330", bundle.sidecars.imdbId)
    }
```

- [ ] **Step 2: Run the stable ID test**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.core.metadata.router.StableIdBundleResolverTest"
```

Expected: PASS if TMDB series -> TVDB identity is already correct. If it fails, continue with Step 3.

- [ ] **Step 3: Record failure and create a focused follow-up only if Step 2 fails**

If the test fails, do not modify `StableIdBundleResolver.kt` in this packet. Record the failing assertion and create a follow-up plan for the identity resolver packet. The likely fix shape for that follow-up is:

```kotlin
val tvdbId = lookup.tmdbTvToTvdb(tmdbId)
val imdbId = lookup.tmdbTvToImdb(tmdbId)
```

and writes:

```kotlin
canonical = StableCanonicalIds(tvdbSeriesId = tvdbId)
sidecars = StableIdSidecars(imdbId = imdbId)
```

for `MetadataMediaKind.SERIES` / `ContentType.SERIES`.

- [ ] **Step 4: Commit verification test only when it passes**

If the test passes, commit only the verification test:

```bash
git add app/src/test/java/com/nexio/tv/core/metadata/router/StableIdBundleResolverTest.kt
git commit -m "test: cover tmdb series tvdb hydration identity"
```

If the test fails, do not commit the failing test in this packet unless the team intentionally wants a red regression test on the branch.

Do not commit identity resolver implementation from this task.

---

### Task 9: Final Verification

**Files:**
- No planned source edits.

- [ ] **Step 1: Run focused rating tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.domain.model.RatingDisplayFormatterTest" --tests "com.nexio.tv.domain.model.RailItemPreviewBridgeTest" --tests "com.nexio.tv.data.local.MetadataModelSanitizersTest" --tests "com.nexio.tv.architecture.RatingDisplayBoundaryTest"
```

Expected: PASS.

- [ ] **Step 2: Run focused home/screensaver tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest" --tests "com.nexio.tv.ui.screens.home.HomeHydrationCoordinatorTest" --tests "com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest" --tests "com.nexio.tv.core.metadata.router.StableIdBundleResolverTest"
```

Expected: PASS.

- [ ] **Step 3: Run full unit test suite for changed areas**

Run:

```bash
./gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 4: Inspect remaining direct formatting call sites**

Run:

```bash
rg 'String\\.format\\("%.1f"|"%.1f"\\.format\\(' app/src/main/java/com/nexio/tv
```

Expected: no output.

- [ ] **Step 5: Inspect diff for forbidden UI fallback behavior**

Run:

```bash
git diff -- app/src/main/java/com/nexio/tv/ui app/src/main/java/com/nexio/tv/data app/src/main/java/com/nexio/tv/domain app/src/main/java/com/nexio/tv/core
```

Expected: changes show shared rating validation/formatting, sanitized preview/snapshot ratings, per-type artwork merge, trace, and tests. The diff must not assign TVDB logo URLs directly inside UI composables.

- [ ] **Step 6: Final commit if verification-only changes were made**

If Step 9 caused additional code/test changes:

```bash
git add app/src/main/java app/src/test/java
git commit -m "test: verify home rating and logo display boundaries"
```

If no files changed during Task 9, do not create an empty commit.

---

## Self-Review

Spec coverage:

- Shared formatter with `Locale.US`: Task 1 and Task 5.
- Title-rating validator and percent-rating separation: Task 1.
- Preview rating quarantine: Task 2.
- Stale snapshot cleanup: Task 3.
- ResolvedDisplayItem/Screensaver rating guard: Task 4.
- TVDB logo propagation through hydrated overlay and screensaver projection: Task 6.
- Trace for first-paint vs hydrated rating/logo: Task 7.
- TMDB series -> TVDB stable ID verification: Task 8.
- No local UI fallback patching: Task 5 architecture test and Task 9 diff inspection.

Placeholder scan:

- No task contains unresolved placeholders or unspecified error handling.
- Conditional implementation appears only in Task 8 after a concrete test, with exact files and expected code shape.

Type consistency:

- `RatingDisplayFormatter`, `RatingValueValidator`, `PreviewRatingResolution`, and `RejectedPreviewRating` are defined before later tasks use them.
- `ArtworkBundle.logo` is carried consistently through overlay, resolved display, and screensaver candidate tests.
