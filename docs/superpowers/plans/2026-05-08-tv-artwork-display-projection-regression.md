# TV Artwork Display Projection Regression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the TV artwork and premium poster regression by consuming hydrated overlay artwork before display projection, preserving artwork type boundaries, and preventing non-premium fallback assets from masquerading as premium decisions.

**Architecture:** The TVDB provider path is not the main blocker: hydrated overlays already contain RPDB poster decisions plus TVDB backdrop/logo refs. The fix is downstream: merge `HydratedHomeOverlay.fields` into the normal `ResolvedDisplayItem` path, enforce `ArtworkBundle` type boundaries, make poster cards reject backdrop/logo fallbacks, and make premium fallback materialization explicit instead of writing fallback assets under premium decision keys.

**Tech Stack:** Kotlin, Android, JUnit4, kotlinx-coroutines-test, MockK, Gson, Coil artwork model helpers.

---

## File Structure

Modify these production files:

- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkBundleTypeSafety.kt`
  - New focused helper file for validating `ArtworkBundle` slots by `ArtworkType`.
- `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
  - Use type-safe per-field artwork merging when hydrated metadata overlays first-paint metadata.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`
  - Merge overlay fields before projecting `ResolvedDisplayItem.artwork` and `ResolvedDisplayItem.rating`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt`
  - Stop portrait poster cards from falling back to backdrops.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
  - Ensure fallback materialization uses fallback-specific decision identity/trace and never records a fallback asset under the original premium decision key.
- `app/src/main/java/com/nexio/tv/data/local/MetadataModelSanitizers.kt`
  - Clear `posterProviderTag` for raw/legacy preview URLs; keep tags only for durable `nexio-artwork://` refs.
- `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
  - Validate all persisted artwork fields by expected type and remove raw/legacy premium final URLs at the snapshot boundary.
- `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt`
  - Stop applying generated premium URL resolver output to addon catalog cache rows.

Modify or create these tests:

- `app/src/test/java/com/nexio/tv/core/artwork/ArtworkBundleTypeSafetyTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeRowsArtworkModelTest.kt`
- `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/MetadataModelSanitizersTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`
- `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbArtworkCandidateMapperTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/CatalogRepositoryAddonRoutingTest.kt`
- `app/src/test/java/com/nexio/tv/architecture/PremiumArtworkSharedPipelineContractTest.kt`

## Task 1: Add Shared Artwork Type Boundary Helpers

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkBundleTypeSafety.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkBundleTypeSafetyTest.kt`

- [ ] **Step 1: Write the failing type-boundary tests**

Create `app/src/test/java/com/nexio/tv/core/artwork/ArtworkBundleTypeSafetyTest.kt`:

```kotlin
package com.nexio.tv.core.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkBundleTypeSafetyTest {
    @Test
    fun `enforce type boundaries drops refs in wrong slots`() {
        val posterRef = artworkRef("poster", ArtworkType.POSTER)
        val backdropRef = artworkRef("backdrop", ArtworkType.BACKDROP)
        val logoRef = artworkRef("logo", ArtworkType.LOGO)
        val thumbnailRef = artworkRef("thumbnail", ArtworkType.THUMBNAIL)

        val sanitized = ArtworkBundle(
            poster = backdropRef,
            backdrop = logoRef,
            logo = posterRef,
            thumbnail = thumbnailRef
        ).enforceArtworkTypeBoundaries()

        assertNull(sanitized.poster)
        assertNull(sanitized.backdrop)
        assertNull(sanitized.logo)
        assertEquals(thumbnailRef, sanitized.thumbnail)
    }

    @Test
    fun `enforce type boundaries preserves refs in matching slots`() {
        val posterRef = artworkRef("poster", ArtworkType.POSTER)
        val backdropRef = artworkRef("backdrop", ArtworkType.BACKDROP)
        val logoRef = artworkRef("logo", ArtworkType.LOGO)
        val thumbnailRef = artworkRef("thumbnail", ArtworkType.THUMBNAIL)

        val sanitized = ArtworkBundle(
            poster = posterRef,
            backdrop = backdropRef,
            logo = logoRef,
            thumbnail = thumbnailRef
        ).enforceArtworkTypeBoundaries()

        assertEquals(posterRef, sanitized.poster)
        assertEquals(backdropRef, sanitized.backdrop)
        assertEquals(logoRef, sanitized.logo)
        assertEquals(thumbnailRef, sanitized.thumbnail)
    }

    @Test
    fun `emptyOrNull returns null for an empty bundle`() {
        assertNull(ArtworkBundle().emptyOrNull())
    }

    private fun artworkRef(key: String, imageType: ArtworkType): ArtworkDisplayRef.RuntimeAsset =
        ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("decision-$key"),
            assetKey = ArtworkAssetKey("asset-$key"),
            imageType = imageType,
            selectedProvider = ArtworkProviderId.RailPreview,
            sourceRole = ArtworkSourceRole.RAIL_PREVIEW,
            trace = ArtworkTrace.empty()
        )
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkBundleTypeSafetyTest
```

Expected: compile failure because `enforceArtworkTypeBoundaries` and `emptyOrNull` do not exist.

- [ ] **Step 3: Implement the minimal shared helper**

Create `app/src/main/java/com/nexio/tv/core/artwork/ArtworkBundleTypeSafety.kt`:

```kotlin
package com.nexio.tv.core.artwork

fun ArtworkDisplayRef?.takeIfImageType(expectedType: ArtworkType): ArtworkDisplayRef? =
    this?.takeIf { ref -> ref.imageType == expectedType }

fun ArtworkBundle.enforceArtworkTypeBoundaries(): ArtworkBundle =
    ArtworkBundle(
        poster = poster.takeIfImageType(ArtworkType.POSTER),
        backdrop = backdrop.takeIfImageType(ArtworkType.BACKDROP),
        logo = logo.takeIfImageType(ArtworkType.LOGO),
        thumbnail = thumbnail.takeIfImageType(ArtworkType.THUMBNAIL)
    )

fun ArtworkBundle.emptyOrNull(): ArtworkBundle? =
    takeUnless { bundle ->
        bundle.poster == null &&
            bundle.backdrop == null &&
            bundle.logo == null &&
            bundle.thumbnail == null
    }
```

- [ ] **Step 4: Run the helper tests and verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkBundleTypeSafetyTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkBundleTypeSafety.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkBundleTypeSafetyTest.kt
git commit -m "test: add artwork bundle type boundary helpers"
```

## Task 2: Merge Hydrated Overlay Fields Before Resolved Display Projection

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`

- [ ] **Step 1: Replace the stale overlay test with an overlay-merge regression test**

In `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt`, replace the test named `mapper uses final home item and overlay trace without applying overlays again` with:

```kotlin
@Test
fun `mapper merges overlay fields before projecting artwork and rating`() {
    val firstPaint = preview(
        id = "tvdb:355567",
        title = "The Boys",
        overview = "First paint overview",
        rating = null,
        artwork = ArtworkBundle(),
        stableIds = ProviderIds(tvdb = "355567", tmdb = "76479", imdb = "tt1190634")
    ).copy(
        type = ContentType.SERIES,
        rawType = "series",
        poster = null,
        background = null,
        logo = null
    )
    val rpdbPoster = artworkRef("rpdb-poster", imageType = ArtworkType.POSTER)
    val tvdbBackdrop = artworkRef("tvdb-backdrop", imageType = ArtworkType.BACKDROP)
    val tvdbLogo = artworkRef("tvdb-logo", imageType = ArtworkType.LOGO)
    val overlay = overlay(
        itemKey = "series:tvdb:355567",
        canonicalProvider = ProviderId.TVDB,
        canonicalId = "355567",
        imdbId = "tt1190634",
        fields = HomeDisplayMetadata(
            title = "The Boys",
            description = "Hydrated overview",
            imdbRating = 8.7f,
            ratingSource = TitleRatingSource.IMDB,
            posterProviderTag = "rpdb",
            artwork = ArtworkBundle(
                poster = rpdbPoster,
                backdrop = tvdbBackdrop,
                logo = tvdbLogo
            )
        )
    )

    val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
        rows = listOf(row(firstPaint)),
        overlaysByItemKey = mapOf("series:tvdb:355567" to overlay),
        nowMs = 10_000L
    ).single()

    assertEquals("Hydrated overview", resolved.display.overview)
    assertEquals(8.7, resolved.rating?.value ?: 0.0, 0.000001)
    assertEquals(rpdbPoster, resolved.artwork.poster)
    assertEquals(tvdbBackdrop, resolved.artwork.backdrop)
    assertEquals(tvdbLogo, resolved.artwork.logo)
    assertEquals(ArtworkType.POSTER, resolved.artwork.poster?.imageType)
    assertEquals(ArtworkType.BACKDROP, resolved.artwork.backdrop?.imageType)
    assertEquals(ArtworkType.LOGO, resolved.artwork.logo?.imageType)
    assertEquals(HydrationState.CANONICAL_READY, resolved.hydrationState)
}
```

- [ ] **Step 2: Add a type-boundary regression test to the mapper**

In the same test file, add:

```kotlin
@Test
fun `mapper drops wrong typed overlay artwork before publishing resolved display item`() {
    val firstPaint = preview(
        id = "tvdb:453615",
        title = "Legends",
        overview = "First paint",
        rating = null,
        artwork = ArtworkBundle(),
        stableIds = ProviderIds(tvdb = "453615")
    ).copy(type = ContentType.SERIES, rawType = "series", poster = null, background = null, logo = null)
    val wrongPoster = artworkRef("tvdb-backdrop-in-poster-slot", imageType = ArtworkType.BACKDROP)
    val correctBackdrop = artworkRef("tvdb-backdrop", imageType = ArtworkType.BACKDROP)
    val overlay = overlay(
        itemKey = "series:tvdb:453615",
        canonicalProvider = ProviderId.TVDB,
        canonicalId = "453615",
        fields = HomeDisplayMetadata(
            artwork = ArtworkBundle(
                poster = wrongPoster,
                backdrop = correctBackdrop
            )
        )
    )

    val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
        rows = listOf(row(firstPaint)),
        overlaysByItemKey = mapOf("series:tvdb:453615" to overlay),
        nowMs = 10_000L
    ).single()

    assertNull(resolved.artwork.poster)
    assertEquals(correctBackdrop, resolved.artwork.backdrop)
}
```

Add this import if missing:

```kotlin
import org.junit.Assert.assertNull
```

- [ ] **Step 3: Add movie premium overlay regression test**

In the same test file, add:

```kotlin
@Test
fun `movie row with first paint poster uses hydrated premium poster when available`() {
    val firstPaint = preview(
        id = "tmdb:1630423",
        title = "My Dearest Assassin",
        overview = "First paint overview",
        rating = 0f,
        artwork = ArtworkBundle(),
        stableIds = ProviderIds(tmdb = "1630423", imdb = "tt39749979")
    ).copy(
        poster = "https://image.tmdb.org/t/p/w500/tmdb-poster.jpg",
        background = "https://image.tmdb.org/t/p/w780/tmdb-backdrop.jpg"
    )
    val rpdbPoster = artworkRef("rpdb-movie-poster", imageType = ArtworkType.POSTER)
    val overlay = overlay(
        itemKey = "movie:tmdb:1630423",
        canonicalProvider = ProviderId.TMDB,
        canonicalId = "1630423",
        imdbId = "tt39749979",
        fields = HomeDisplayMetadata(
            title = "My Dearest Assassin",
            posterProviderTag = "rpdb",
            artwork = ArtworkBundle(poster = rpdbPoster)
        )
    )

    val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
        rows = listOf(row(firstPaint)),
        overlaysByItemKey = mapOf("movie:tmdb:1630423" to overlay),
        nowMs = 10_000L
    ).single()

    assertEquals(rpdbPoster, resolved.artwork.poster)
    assertEquals(ArtworkType.POSTER, resolved.artwork.poster?.imageType)
}
```

- [ ] **Step 4: Run mapper tests and verify failure**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest
```

Expected: `mapper merges overlay fields before projecting artwork and rating` fails because `HomeResolvedDisplayMapper` still projects first-paint fields.

- [ ] **Step 5: Make `HomeDisplayMetadata` artwork merging type-safe**

Modify imports in `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`:

```kotlin
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.emptyOrNull
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.core.artwork.toLegacyArtworkString
```

Replace `mergeFallbackArtwork` with:

```kotlin
private fun HomeDisplayMetadata.mergeFallbackArtwork(fallback: HomeDisplayMetadata): ArtworkBundle? {
    val primaryArtwork = artwork?.enforceArtworkTypeBoundaries()
    val fallbackArtwork = fallback.artwork?.enforceArtworkTypeBoundaries()
    val merged = ArtworkBundle(
        poster = primaryArtwork?.poster ?: fallbackArtwork?.poster,
        backdrop = primaryArtwork?.backdrop ?: fallbackArtwork?.backdrop,
        logo = primaryArtwork?.logo ?: fallbackArtwork?.logo,
        thumbnail = primaryArtwork?.thumbnail ?: fallbackArtwork?.thumbnail
    )
    return merged.enforceArtworkTypeBoundaries().emptyOrNull()
}
```

Replace `mergeAppliedArtwork` with:

```kotlin
private fun HomeDisplayMetadata.mergeAppliedArtwork(base: MetaPreview): ArtworkBundle? {
    val primaryArtwork = artwork?.enforceArtworkTypeBoundaries()
    val baseArtwork = base.artwork?.enforceArtworkTypeBoundaries()
    val merged = ArtworkBundle(
        poster = primaryArtwork?.poster ?: baseArtwork?.poster,
        backdrop = primaryArtwork?.backdrop ?: baseArtwork?.backdrop,
        logo = primaryArtwork?.logo ?: baseArtwork?.logo,
        thumbnail = primaryArtwork?.thumbnail ?: baseArtwork?.thumbnail
    )
    return merged.enforceArtworkTypeBoundaries().emptyOrNull()
}
```

- [ ] **Step 6: Merge overlay fields in `HomeResolvedDisplayMapper`**

Modify imports in `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`:

```kotlin
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
```

In `MetaPreview.toResolvedDisplayItem`, replace:

```kotlin
val fields = toHomeDisplayMetadata()
```

with:

```kotlin
val firstPaintFields = toHomeDisplayMetadata()
val fields = overlay?.fields?.mergeFallback(firstPaintFields) ?: firstPaintFields
```

Replace:

```kotlin
artwork = fields.toResolvedArtworkBundle(),
```

with:

```kotlin
artwork = fields.toResolvedArtworkBundle().enforceArtworkTypeBoundaries(),
```

In `HydratedHomeOverlay.toResolvedDisplayItem`, replace:

```kotlin
artwork = fields.toResolvedArtworkBundle(),
```

with:

```kotlin
artwork = fields.toResolvedArtworkBundle().enforceArtworkTypeBoundaries(),
```

- [ ] **Step 7: Run mapper tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt
git commit -m "fix: merge hydrated home overlays before display projection"
```

## Task 3: Prevent Poster Cards From Rendering Backdrops

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeRowsArtworkModelTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt`

- [ ] **Step 1: Add failing poster-slot tests**

Add these tests to `ModernHomeRowsArtworkModelTest`:

```kotlin
@Test
fun `portrait cards never use typed backdrop when poster is missing`() {
    val item = carouselItem(
        ArtworkBundle(
            poster = null,
            backdrop = artworkRef("backdropAsset", ArtworkType.BACKDROP)
        ),
        poster = null,
        background = "https://image.tmdb.org/t/p/w780/raw-backdrop.jpg"
    )

    val model = resolveModernCarouselCardArtworkModel(
        item = item,
        useLandscapePosters = false,
        focusedPosterBackdropExpandEnabled = false,
        isBackdropExpanded = false,
        fallbackModel = item.imageUrl
    )

    assertEquals(null, model)
}

@Test
fun `portrait fallback model never uses raw backdrop when poster is missing`() {
    val item = carouselItem(
        artwork = null,
        poster = null,
        background = "https://image.tmdb.org/t/p/w780/raw-backdrop.jpg"
    )

    val fallback = resolveModernCarouselCardFallbackArtworkModel(
        item = item,
        useLandscapePosters = false,
        focusedPosterBackdropExpandEnabled = false,
        isBackdropExpanded = false
    )

    assertEquals(null, fallback)
}

@Test
fun `hero and landscape card mode can still use backdrop before poster`() {
    val item = carouselItem(
        ArtworkBundle(
            poster = artworkRef("posterAsset", ArtworkType.POSTER),
            backdrop = artworkRef("backdropAsset", ArtworkType.BACKDROP)
        )
    )

    val model = resolveModernCarouselCardArtworkModel(
        item = item,
        useLandscapePosters = true,
        focusedPosterBackdropExpandEnabled = false,
        isBackdropExpanded = false,
        fallbackModel = item.imageUrl
    )

    assertEquals("nexio-artwork://asset/backdropAsset", model)
}
```

- [ ] **Step 2: Run card artwork tests and verify failure**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.screens.home.ModernHomeRowsArtworkModelTest
```

Expected: the new portrait tests fail because the current code falls back to backdrop/background.

- [ ] **Step 3: Restrict portrait card typed artwork selection**

In `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt`, replace the `typedModel` block in `resolveModernCarouselCardArtworkModel` with:

```kotlin
val typedModel = if (useBackdrop) {
    artwork?.backdrop.toCoilModelOrNull() ?: artwork?.poster.toCoilModelOrNull()
} else {
    artwork?.poster.toCoilModelOrNull()
}
```

Then replace:

```kotlin
return typedModel ?: fallbackModel.toLegacyArtworkCoilModelOrNull(
    ownerKey = "${item.key}:${fallbackType.name.lowercase()}",
    imageType = fallbackType
)
```

with:

```kotlin
val legacyFallback = if (useBackdrop) {
    fallbackModel
} else {
    firstNonBlank(item.metaPreview?.poster, item.heroPreview.poster)
}
return typedModel ?: legacyFallback.toLegacyArtworkCoilModelOrNull(
    ownerKey = "${item.key}:${fallbackType.name.lowercase()}",
    imageType = fallbackType
)
```

- [ ] **Step 4: Restrict portrait fallback artwork selection**

In `resolveModernCarouselCardFallbackArtworkModel`, replace the poster-mode `firstNonBlank` branch with:

```kotlin
firstNonBlank(
    item.metaPreview?.poster,
    item.heroPreview.poster
)
```

The landscape branch remains:

```kotlin
firstNonBlank(
    item.metaPreview?.background,
    item.heroPreview.backdrop,
    item.metaPreview?.poster,
    item.heroPreview.poster
)
```

- [ ] **Step 5: Run card artwork tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.screens.home.ModernHomeRowsArtworkModelTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeRowsArtworkModelTest.kt
git commit -m "fix: prevent poster cards from rendering backdrops"
```

## Task 4: Make Premium Fallback Materialization Use Fallback Identity

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`

- [ ] **Step 1: Strengthen the existing fallback test**

In `ArtworkAssetRepositoryTest`, replace the assertion block at the end of `selected provider failure falls back to primary remote candidate after restart` with this stricter block:

```kotlin
assertNotNull(result)
assertArrayEquals("fallback-bytes".toByteArray(), result!!.localFile.readBytes())
assertEquals(2, loadCount)
assertEquals("FALLBACK_MATERIALIZED", result.cacheDecision)
assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB), result.record.provider)
assertEquals(null, recordStore.findLatestAssetForDecision(decision.decisionKey))
val fallbackRecord = recordStore.get(result.assetKey)
assertNotNull(fallbackRecord)
assertEquals(result.record.decisionKey, fallbackRecord!!.decisionKey)
assertEquals(false, fallbackRecord.decisionKey!!.value.contains("credentialhash"))
assertEquals(false, fallbackRecord.decisionKey!!.value.contains("settingshash"))
val fallbackPayload = traceSink.events
    .single { it.eventType == "artwork.fallback_materialized" }
    .payload as Map<*, *>
assertEquals(artworkDecisionShortSha256(decision.decisionKey.value), fallbackPayload["requestedDecisionKeyHash"])
assertEquals(artworkDecisionShortSha256(result.assetKey.value), fallbackPayload["assetKeyHash"])
assertEquals("TMDB", fallbackPayload["fallbackProvider"])
assertEquals(false, fallbackPayload.containsKey("decisionKey"))
assertEquals(false, fallbackPayload.containsKey("assetKey"))
assertTracePayloadsDoNotContain(traceSink, decision.decisionKey.value, result.assetKey.value)
```

Then make the repository fixture expose the record store by replacing the local `val repository = repository(...)` in that test with:

```kotlin
val recordStore = RecordingArtworkAssetRecordStore()
val repository = repository(
    runtime = runtime,
    cache = restartedCache,
    assetRecordStore = recordStore,
    sourceMaterializer = ArtworkSourceMaterializer(
        remoteSourcesByHash = emptyMap(),
        remoteSourceStore = restartedRemoteSourceStore
    ),
    byteLoader = ArtworkByteLoader { source, _ ->
        loadCount += 1
        when (source) {
            is ArtworkSource.ProviderTemplate ->
                IntegrationLoadResult.NetworkError(IllegalStateException("premium unavailable"))
            is ArtworkSource.RemoteUrl ->
                IntegrationLoadResult.Success("fallback-bytes".toByteArray())
            else ->
                IntegrationLoadResult.NetworkError(IllegalStateException("fallback source unavailable"))
        }
    },
    traceSink = traceSink
)
```

- [ ] **Step 2: Run the fallback test and verify failure**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.selected provider failure falls back to primary remote candidate after restart"
```

Expected: FAIL because the fallback asset is currently indexed under the original RPDB decision key and the trace uses `decisionKeyHash` instead of `requestedDecisionKeyHash`.

- [ ] **Step 3: Add fallback decision key helper in `ArtworkAssetRepository`**

In `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`, add this private helper near `getOrFetchFallback`:

```kotlin
private fun fallbackDecisionFor(
    requestedDecision: ArtworkDecision,
    candidate: RejectedArtworkCandidate
): ArtworkDecision {
    val fallbackSettingsHash = candidate.providerTemplate?.settingsHash
    val fallbackCredentialHash = candidate.providerTemplate?.credentialHash
    return requestedDecision.copy(
        decisionKey = ArtworkCacheKeys.decisionKey(
            ownerKey = requestedDecision.ownerKey,
            imageType = requestedDecision.imageType,
            provider = candidate.provider,
            premiumEnabled = false,
            settingsHash = fallbackSettingsHash,
            credentialHash = fallbackCredentialHash,
            policyVersion = requestedDecision.policyVersion
        ),
        settingsHash = fallbackSettingsHash,
        credentialHash = fallbackCredentialHash,
        selectedCandidate = PersistedArtworkCandidate(
            provider = candidate.provider,
            sourceRole = candidate.sourceRole,
            sourceHash = candidate.sourceHash,
            redactedSourceForTrace = candidate.redactedSourceForTrace,
            providerTemplate = candidate.providerTemplate,
            priority = candidate.priority
        ),
        rejectedCandidates = emptyList()
    )
}
```

- [ ] **Step 4: Use fallback identity and trace requested vs fallback provider**

In `getOrFetchFallback`, replace:

```kotlin
val fallbackDecision = decision.copy(
    selectedCandidate = PersistedArtworkCandidate(
        provider = candidate.provider,
        sourceRole = candidate.sourceRole,
        sourceHash = candidate.sourceHash,
        redactedSourceForTrace = candidate.redactedSourceForTrace,
        providerTemplate = candidate.providerTemplate,
        priority = candidate.priority
    ),
    rejectedCandidates = emptyList()
)
```

with:

```kotlin
val fallbackDecision = fallbackDecisionFor(decision, candidate)
```

Replace the fallback trace payload with:

```kotlin
payload = mapOf(
    "requestedDecisionKeyHash" to decision.decisionKey.hashedForTrace(),
    "fallbackDecisionKeyHash" to fallbackDecision.decisionKey.hashedForTrace(),
    "fallbackProvider" to result.record.provider?.key,
    "assetKeyHash" to result.assetKey.hashedForTrace()
)
```

- [ ] **Step 5: Run the repository tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt
git commit -m "fix: separate premium decisions from fallback artwork assets"
```

## Task 5: Make Poster Provider Tags Derived From Durable Artwork Refs

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/local/MetadataModelSanitizersTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/MetadataModelSanitizers.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`

- [ ] **Step 1: Add sanitizer tests for derived provider tags**

Add these tests to `MetadataModelSanitizersTest`:

```kotlin
@Test
fun `sanitize preview clears provider tag for raw remote poster`() {
    val preview = MetaPreview(
        id = "tmdb:76479",
        type = ContentType.SERIES,
        rawType = "series",
        name = "The Boys",
        poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        posterProviderTag = "tmdb"
    )

    val sanitized = preview.sanitizedForCache()

    assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", sanitized.poster)
    assertNull(sanitized.posterProviderTag)
}

@Test
fun `sanitize preview derives rpdb provider tag from durable decision ref`() {
    val preview = MetaPreview(
        id = "tmdb:76479",
        type = ContentType.SERIES,
        rawType = "series",
        name = "The Boys",
        poster = "nexio-artwork://decision/artwork-decision:poster:canonical:tmdb:series-76479:provider:RPDB:premium:true:settings:settings:credential:credential:imageLang:en:policy:1",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        posterProviderTag = "tmdb"
    )

    val sanitized = preview.sanitizedForCache()

    assertEquals(preview.poster, sanitized.poster)
    assertEquals("rpdb", sanitized.posterProviderTag)
}

@Test
fun `sanitize preview derives provider tag from durable tmdb asset and drops stale rpdb tag`() {
    val preview = MetaPreview(
        id = "tmdb:202555",
        type = ContentType.SERIES,
        rawType = "series",
        name = "Daredevil: Born Again",
        poster = "nexio-artwork://asset/artwork-asset:TMDB:poster:urlHash:f2479639:variant:none:imageLang:en:policy:1",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        posterProviderTag = "rpdb"
    )

    val sanitized = preview.sanitizedForCache()

    assertEquals(preview.poster, sanitized.poster)
    assertEquals("tmdb", sanitized.posterProviderTag)
}

@Test
fun `sanitize preview derives provider tag from durable tvdb asset`() {
    val preview = MetaPreview(
        id = "tvdb:355567",
        type = ContentType.SERIES,
        rawType = "series",
        name = "The Boys",
        poster = "nexio-artwork://asset/artwork-asset:TVDB:poster:urlHash:abc123:variant:none:imageLang:en:policy:1",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        posterProviderTag = null
    )

    val sanitized = preview.sanitizedForCache()

    assertEquals(preview.poster, sanitized.poster)
    assertEquals("tvdb", sanitized.posterProviderTag)
}
```

Add imports if missing:

```kotlin
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertNull
```

- [ ] **Step 2: Add snapshot-store provider tag test**

Add this test to `HomeCatalogSnapshotStoreTest`:

```kotlin
@Test
fun `remote first paint poster does not create provider tag mismatch rehydrate request`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val traceSink = RecordingTraceSink()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver,
        traceSink = traceSink
    )
    val snapshot = sampleSnapshotWithPoster(
        poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
        posterProviderTag = "tmdb"
    )

    store.write(snapshot, "RPDB:12345")
    val restored = store.read("RPDB:12345")

    val items = listOf(
        restored?.catalogRows?.single()?.items?.single(),
        restored?.fullCatalogRows?.single()?.items?.single(),
        restored?.heroItems?.single()
    )
    items.forEach { item ->
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", item?.poster)
        assertNull(item?.posterProviderTag)
    }
    assertTrue(traceSink.events.none { event ->
        event.eventType == "home.snapshot_artwork_rehydrate_requested" &&
            (event.payload as Map<*, *>)["reason"] == "poster_provider_tag_mismatch"
    })
}
```

- [ ] **Step 3: Run sanitizer/snapshot tests and verify failure**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.local.MetadataModelSanitizersTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected: the new provider-tag tests fail because raw remote posters currently retain provider tags.

- [ ] **Step 4: Implement durable-ref provider tag derivation**

In `MetadataModelSanitizers.kt`, replace each `posterProviderTag = posterProviderTag.takeIf { cleanPoster != null }` with:

```kotlin
posterProviderTag = cleanPoster.derivePosterProviderTagFromArtworkRef(),
```

Add these helpers near `sanitizedPremiumArtworkRef`:

```kotlin
private fun String?.derivePosterProviderTagFromArtworkRef(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value.startsWith("nexio-artwork://decision/", ignoreCase = true) ->
            parseProviderFromDecisionKey(value.substringAfter("nexio-artwork://decision/"))

        value.startsWith("nexio-artwork://asset/", ignoreCase = true) ->
            parseProviderFromAssetKey(value.substringAfter("nexio-artwork://asset/"))

        else -> null
    }?.lowercase()
}

private fun parseProviderFromDecisionKey(key: String): String? {
    val parts = key.split(":")
    val providerIndex = parts.indexOf("provider")
    return parts.getOrNull(providerIndex + 1)
        ?.takeIf { providerIndex >= 0 }
        ?.takeIf { it.isNotBlank() && it != "none" }
}

private fun parseProviderFromAssetKey(key: String): String? {
    val parts = key.split(":")
    return parts
        .takeIf { it.firstOrNull() == "artwork-asset" }
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() && it != "none" }
}
```

- [ ] **Step 5: Run sanitizer/snapshot tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.local.MetadataModelSanitizersTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/MetadataModelSanitizers.kt app/src/test/java/com/nexio/tv/data/local/MetadataModelSanitizersTest.kt app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt
git commit -m "fix: derive poster provider tags from durable artwork refs"
```

## Task 6: Add Snapshot Write Barrier For Artwork Field Types

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`

- [ ] **Step 1: Add snapshot write-barrier tests for wrong image types**

Add these tests to `HomeCatalogSnapshotStoreTest`:

```kotlin
@Test
fun `snapshot_write_rejects_backdrop_decision_in_poster_slot`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver
    )
    val snapshot = sampleSnapshotWithPoster(
        poster = "nexio-artwork://decision/artwork-decision:backdrop:canonical:tvdb:355567:provider:TVDB:premium:false:settings:none:credential:none:imageLang:en:policy:1",
        posterProviderTag = "tvdb"
    )

    store.write(snapshot, "native")

    assertClearedPosterFields(store.read("native"))
}

@Test
fun `snapshot_write_rejects_logo_asset_in_poster_slot`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver
    )
    val snapshot = sampleSnapshotWithPoster(
        poster = "nexio-artwork://asset/artwork-asset:TVDB:logo:urlHash:abc:variant:none:imageLang:en:policy:1",
        posterProviderTag = "tvdb"
    )

    store.write(snapshot, "native")

    assertClearedPosterFields(store.read("native"))
}

@Test
fun `snapshot_write_rejects_poster_ref_in_background_slot`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver
    )
    val row = sampleRow("addon", "wrong-background").let { row ->
        row.copy(items = listOf(row.items.single().copy(
            background = "nexio-artwork://asset/artwork-asset:TMDB:poster:urlHash:abc:variant:none:imageLang:en:policy:1"
        )))
    }
    val snapshot = HomeCatalogSnapshotStore.Snapshot(
        catalogRows = listOf(row),
        fullCatalogRows = listOf(row),
        heroItems = row.items
    )

    store.write(snapshot, "native")

    val restored = store.read("native")
    assertEquals(null, restored?.catalogRows?.single()?.items?.single()?.background)
    assertEquals(null, restored?.fullCatalogRows?.single()?.items?.single()?.background)
    assertEquals(null, restored?.heroItems?.single()?.background)
}

@Test
fun `snapshot_write_rejects_backdrop_ref_in_logo_slot`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver
    )
    val row = sampleRow("addon", "wrong-logo").let { row ->
        row.copy(items = listOf(row.items.single().copy(
            logo = "nexio-artwork://asset/artwork-asset:TVDB:backdrop:urlHash:def:variant:none:imageLang:en:policy:1"
        )))
    }
    val snapshot = HomeCatalogSnapshotStore.Snapshot(
        catalogRows = listOf(row),
        fullCatalogRows = listOf(row),
        heroItems = row.items
    )

    store.write(snapshot, "native")

    val restored = store.read("native")
    assertEquals(null, restored?.catalogRows?.single()?.items?.single()?.logo)
    assertEquals(null, restored?.fullCatalogRows?.single()?.items?.single()?.logo)
    assertEquals(null, restored?.heroItems?.single()?.logo)
}
```

- [ ] **Step 2: Run snapshot tests and verify failure**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected: the new tests fail because the write barrier validates integrity but not image-type slot compatibility.

- [ ] **Step 3: Add field-type helpers to `HomeCatalogSnapshotStore`**

In `HomeCatalogSnapshotStore.kt`, add this import:

```kotlin
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.toLegacyArtworkString
```

Add these helpers near `posterKind`:

```kotlin
private fun String?.isArtworkRefForType(expectedType: ArtworkType): Boolean {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return true
    val actualType = value.artworkRefImageType() ?: return true
    return actualType == expectedType
}

private fun String.artworkRefImageType(): ArtworkType? {
    val key = when {
        startsWith(ARTWORK_DECISION_PREFIX) -> removePrefix(ARTWORK_DECISION_PREFIX)
        startsWith(ARTWORK_ASSET_PREFIX) -> removePrefix(ARTWORK_ASSET_PREFIX)
        else -> return null
    }
    val parts = key.split(":")
    val typePart = when (parts.firstOrNull()) {
        "artwork-decision" -> parts.getOrNull(1)
        "artwork-asset" -> parts.getOrNull(2)
        else -> null
    } ?: return null
    return when (typePart.lowercase()) {
        "poster" -> ArtworkType.POSTER
        "backdrop" -> ArtworkType.BACKDROP
        "logo" -> ArtworkType.LOGO
        "thumbnail" -> ArtworkType.THUMBNAIL
        else -> null
    }
}
```

- [ ] **Step 4: Apply type checks in all persisted artwork string fields**

At the top of `MetaPreview.repairArtworkWriteInvariant`, after:

```kotlin
val posterRef = poster?.trim().orEmpty()
```

add:

```kotlin
val wrongPosterType = posterRef.takeIf { it.isNotBlank() && !it.isArtworkRefForType(ArtworkType.POSTER) }
val wrongBackdropType = background.takeIf { !it.isArtworkRefForType(ArtworkType.BACKDROP) }
val wrongLogoType = logo.takeIf { !it.isArtworkRefForType(ArtworkType.LOGO) }
val wrongThumbnailType = artwork?.thumbnail
    ?.toLegacyArtworkString()
    ?.takeIf { !it.isArtworkRefForType(ArtworkType.THUMBNAIL) }

if (wrongPosterType != null) {
    traceWriteBarrierRepair(
        scope = scope,
        action = "clear_wrong_type_poster_ref",
        reason = "wrong_artwork_type_for_poster",
        decisionKeyHash = decisionKeyHashForRef(wrongPosterType),
        assetKeyHash = null
    )
}
if (wrongBackdropType != null) {
    traceWriteBarrierRepair(
        scope = scope,
        action = "clear_wrong_type_backdrop_ref",
        reason = "wrong_artwork_type_for_backdrop",
        decisionKeyHash = decisionKeyHashForRef(wrongBackdropType),
        assetKeyHash = null
    )
}
if (wrongLogoType != null) {
    traceWriteBarrierRepair(
        scope = scope,
        action = "clear_wrong_type_logo_ref",
        reason = "wrong_artwork_type_for_logo",
        decisionKeyHash = decisionKeyHashForRef(wrongLogoType),
        assetKeyHash = null
    )
}
if (wrongThumbnailType != null) {
    traceWriteBarrierRepair(
        scope = scope,
        action = "clear_wrong_type_thumbnail_ref",
        reason = "wrong_artwork_type_for_thumbnail",
        decisionKeyHash = decisionKeyHashForRef(wrongThumbnailType),
        assetKeyHash = null
    )
}
val typeSanitized = copy(
    poster = if (wrongPosterType != null) null else poster,
    posterProviderTag = if (wrongPosterType != null) null else posterProviderTag,
    background = if (wrongBackdropType != null) null else background,
    logo = if (wrongLogoType != null) null else logo,
    artwork = if (wrongThumbnailType != null) {
        artwork?.copy(thumbnail = null)
    } else {
        artwork
    }
)
if (
    wrongPosterType != null ||
    wrongBackdropType != null ||
    wrongLogoType != null ||
    wrongThumbnailType != null
) {
    return typeSanitized.repairArtworkWriteInvariant(scope)
}
```

- [ ] **Step 5: Run snapshot tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt
git commit -m "fix: reject wrong artwork types in home snapshots"
```

## Task 7: Keep TVDB Candidate Mapping As A Regression Guard

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbArtworkCandidateMapperTest.kt`

- [ ] **Step 1: Add explicit no-cross-type TVDB tests**

Add these tests to `TvdbArtworkCandidateMapperTest`:

```kotlin
@Test
fun `tvdb backdrop never maps to poster`() {
    val candidates = mapper.mapSeriesArtwork(
        seriesId = 355567,
        requestedLanguage = "eng",
        artworks = listOf(
            artwork(id = 1, type = TvdbArtworkTypes.BACKDROP, image = "https://art.tvdb.com/backdrop.jpg")
        )
    )

    assertEquals(emptyList<ArtworkType>(), candidates.filter { it.imageType == ArtworkType.POSTER }.map { it.imageType })
    assertEquals(listOf(ArtworkType.BACKDROP), candidates.map { it.imageType })
}

@Test
fun `tvdb logo never maps to poster`() {
    val candidates = mapper.mapSeriesArtwork(
        seriesId = 355567,
        requestedLanguage = "eng",
        artworks = listOf(
            artwork(id = 1, type = TvdbArtworkTypes.CLEAR_LOGO, image = "https://art.tvdb.com/logo.png")
        )
    )

    assertEquals(emptyList<ArtworkType>(), candidates.filter { it.imageType == ArtworkType.POSTER }.map { it.imageType })
    assertEquals(listOf(ArtworkType.LOGO), candidates.map { it.imageType })
}

@Test
fun `tvdb extended image poster fallback does not override type 2 poster`() {
    val candidates = mapper.mapSeriesArtwork(
        seriesId = 355567,
        requestedLanguage = "eng",
        posterFallbackImage = "https://art.tvdb.com/extended-image.jpg",
        artworks = listOf(
            artwork(id = 1, type = TvdbArtworkTypes.POSTER, image = "https://art.tvdb.com/type-2-poster.jpg")
        )
    )

    val poster = candidates.single { it.imageType == ArtworkType.POSTER }
    assertEquals(
        "https://art.tvdb.com/type-2-poster.jpg",
        (poster.source as ArtworkSource.RemoteUrl).rawUrl.value
    )
}
```

- [ ] **Step 2: Run TVDB mapper tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.integration.metadata.TvdbArtworkCandidateMapperTest
```

Expected: PASS. If this fails, the regression is in the already-existing TVDB mapper and must be fixed before continuing.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbArtworkCandidateMapperTest.kt
git commit -m "test: guard tvdb artwork type mapping"
```

## Task 8: Stop Writing Raw Premium URLs To Catalog Disk Cache

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/repository/CatalogRepositoryAddonRoutingTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/PremiumArtworkSharedPipelineContractTest.kt`

- [ ] **Step 1: Add catalog repository test that forbids premium URL resolver apply**

Add this test to `CatalogRepositoryAddonRoutingTest`:

```kotlin
@Test
fun `catalog repository does not apply raw premium poster urls before disk cache write`() = runTest {
    val provider = mockk<AddonCatalogIntegrationProvider>()
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val diskCacheStore = mockk<CatalogDiskCacheStore>(relaxed = true)
    val dto = CatalogResponseDto(
        metas = listOf(
            MetaPreviewDto(
                id = "tt0111161",
                type = "movie",
                name = "The Shawshank Redemption",
                poster = "https://images.example/poster.jpg"
            )
        )
    )

    coEvery { posterResolver.getActiveProvider() } returns PosterRatingsUrlResolver.ActiveProvider(
        provider = PosterRatingsProvider.RPDB,
        apiKey = "secret"
    )
    every { diskCacheStore.read(any()) } returns null
    coEvery {
        provider.getCatalog(
            addonId = "community.addon",
            catalogUrl = "https://addon.example/catalog/movie/trending.json"
        )
    } returns NetworkResult.Success(dto)

    val repository = CatalogRepositoryImpl(
        addonCatalogIntegrationProvider = provider,
        posterRatingsUrlResolver = posterResolver,
        catalogDiskCacheStore = diskCacheStore
    )

    repository.getCatalogCachedFirst(
        addonBaseUrl = "https://addon.example",
        addonId = "community.addon",
        addonName = "Community Addon",
        catalogId = "trending",
        catalogName = "Trending",
        type = "movie",
        skip = 0,
        skipStep = 20,
        extraArgs = emptyMap(),
        supportsSkip = false,
        allowNetworkRefresh = true
    ).toList()

    io.mockk.verify(exactly = 0) {
        posterResolver.apply(any<MetaPreview>(), any())
    }
    io.mockk.verify(exactly = 1) {
        diskCacheStore.write(
            cacheKey = any(),
            row = match { row ->
                row.items.single().poster == "https://images.example/poster.jpg" &&
                    row.items.single().posterProviderTag == null
            },
            catalogVersionHash = any()
        )
    }
}
```

Add this import if missing:

```kotlin
import com.nexio.tv.domain.model.PosterRatingsProvider
```

- [ ] **Step 2: Run the catalog test and verify failure**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.repository.CatalogRepositoryAddonRoutingTest.catalog repository does not apply raw premium poster urls before disk cache write"
```

Expected: FAIL because `CatalogRepositoryImpl.fetchCatalogFromNetwork` currently calls `posterRatingsUrlResolver.apply`.

- [ ] **Step 3: Stop applying raw premium poster URLs in catalog fetch**

In `CatalogRepositoryImpl.fetchCatalogFromNetwork`, replace:

```kotlin
val items = result.data.metas.map { meta ->
    posterRatingsUrlResolver.apply(meta.toDomain(), activePosterProvider)
}
```

with:

```kotlin
val items = result.data.metas.map { meta ->
    meta.toDomain()
}
```

Keep `activePosterProvider` in the method signature for cache-token compatibility during this task. Removing it is a separate cleanup.

- [ ] **Step 4: Add architecture check banning `PosterRatingsUrlResolver.apply` in catalog fetch**

Add this test to `PremiumArtworkSharedPipelineContractTest`:

```kotlin
@Test
fun `catalog repository does not apply legacy premium poster urls to fetched rows`() {
    val source = File("app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt").readText()

    assertFalse(
        "CatalogRepositoryImpl must not call PosterRatingsUrlResolver.apply because it writes generated premium URLs into catalog disk cache.",
        source.contains("posterRatingsUrlResolver.apply(")
    )
}

@Test
fun `raw premium provider hosts are limited to provider transports and redaction boundaries`() {
    val allowedPathFragments = listOf(
        "/core/di/NetworkModule.kt",
        "/core/poster/PosterRatingsUrlResolver.kt",
        "/core/trace/TraceRedactor.kt",
        "/data/integration/posters/",
        "/architecture/",
        "/metadata/audit/"
    )
    val offenders = File("app/src")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { file ->
            val normalizedPath = file.invariantSeparatorsPath
            file.readLines().mapIndexedNotNull { index, line ->
                val mentionsPremiumHost =
                    line.contains("api.ratingposterdb.com") ||
                        line.contains("api.top-posters.com")
                val allowed = allowedPathFragments.any { normalizedPath.contains(it) }
                if (mentionsPremiumHost && !allowed) {
                    "$normalizedPath:${index + 1}:raw-premium-host"
                } else {
                    null
                }
            }
        }
        .toList()

    assertTrue(
        "Raw premium provider hosts must stay in provider transports, redaction, or tests:\n" +
            offenders.joinToString(separator = "\n"),
        offenders.isEmpty()
    )
}
```

- [ ] **Step 5: Run catalog and architecture tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.CatalogRepositoryAddonRoutingTest --tests com.nexio.tv.architecture.PremiumArtworkSharedPipelineContractTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt app/src/test/java/com/nexio/tv/data/repository/CatalogRepositoryAddonRoutingTest.kt app/src/test/java/com/nexio/tv/architecture/PremiumArtworkSharedPipelineContractTest.kt
git commit -m "fix: keep raw premium urls out of catalog cache"
```

## Task 9: Add End-To-End Home/Screensaver Artwork Projection Guards

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt`

- [ ] **Step 1: Add resolved surface test for hydrated TV logo preservation**

In `ResolvedDisplaySurfaceRepositoryTest`, add these imports:

```kotlin
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import org.junit.Assert.assertSame
```

Then add this test:

```kotlin
@Test
fun `resolved surface preserves hydrated tv artwork refs by type`() = runTest {
    val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
    val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
    val poster = artworkRef("rpdb-poster", ArtworkType.POSTER, "RPDB")
    val backdrop = artworkRef("tvdb-backdrop", ArtworkType.BACKDROP, "TVDB")
    val logo = artworkRef("tvdb-logo", ArtworkType.LOGO, "TVDB")
    val item = resolvedItem(
        itemKey = "series:tvdb:355567",
        title = "The Boys"
    ).copy(
        itemType = ContentType.SERIES,
        mediaKind = MetadataMediaKind.SERIES,
        canonicalProvider = "TVDB",
        canonicalId = "355567",
        stableIds = ProviderIds(tvdb = "355567", tmdb = "76479", imdb = "tt1190634"),
        artwork = ArtworkBundle(
            poster = poster,
            backdrop = backdrop,
            logo = logo
        )
    )

    val published = repository.publishResolvedItems(
        profileSession = activeSession.value,
        items = listOf(item)
    )

    assertTrue(published)
    val resolved = repository.getSnapshot(profileId = 1).single()
    assertSame(poster, resolved.artwork.poster)
    assertSame(backdrop, resolved.artwork.backdrop)
    assertSame(logo, resolved.artwork.logo)
    assertEquals(ArtworkType.POSTER, resolved.artwork.poster?.imageType)
    assertEquals(ArtworkType.BACKDROP, resolved.artwork.backdrop?.imageType)
    assertEquals(ArtworkType.LOGO, resolved.artwork.logo?.imageType)
    assertEquals("TVDB", resolved.artwork.logo?.trace?.selectedProvider)
}
```

Add this helper near the existing `resolvedItem` helper:

```kotlin
private fun artworkRef(
    key: String,
    imageType: ArtworkType,
    provider: String
): ArtworkDisplayRef.RuntimeAsset =
    ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey("decision-$key"),
        assetKey = ArtworkAssetKey("asset-$key"),
        imageType = imageType,
        selectedProvider = ArtworkProviderId.RailPreview,
        sourceRole = ArtworkSourceRole.PRIMARY,
        trace = ArtworkTrace(selectedProvider = provider, sourceRole = "ARTWORK")
    )
```

- [ ] **Step 2: Add screensaver candidate test for hydrated logo preservation**

In `ScreensaverCandidateRepositoryTest`, add this test:

```kotlin
@Test
fun `image candidates preserve hydrated poster backdrop and logo artwork by type`() = runTest {
    val surface = testSurface()
    val repository = testScreensaverCandidates(surface)
    val poster = artworkRef(key = "rpdb-poster", imageType = ArtworkType.POSTER).copy(
        trace = ArtworkTrace(selectedProvider = "RPDB", sourceRole = "ARTWORK")
    )
    val backdrop = artworkRef(key = "tvdb-backdrop", imageType = ArtworkType.BACKDROP).copy(
        trace = ArtworkTrace(selectedProvider = "TVDB", sourceRole = "ARTWORK")
    )
    val logo = artworkRef(key = "tvdb-logo", imageType = ArtworkType.LOGO).copy(
        trace = ArtworkTrace(selectedProvider = "TVDB", sourceRole = "ARTWORK")
    )
    surface.replaceForTest(
        profileId = 1,
        items = listOf(
            resolvedItem(
                itemKey = "series:tvdb:355567",
                title = "The Boys",
                artwork = ArtworkBundle(
                    poster = poster,
                    backdrop = backdrop,
                    logo = logo
                )
            )
        )
    )

    val candidate = repository.observeImageCandidates(profileId = 1).first().single()

    assertSame(poster, candidate.artwork.poster)
    assertSame(backdrop, candidate.artwork.backdrop)
    assertSame(logo, candidate.artwork.logo)
    assertEquals(ArtworkType.POSTER, candidate.artwork.poster?.imageType)
    assertEquals(ArtworkType.BACKDROP, candidate.artwork.backdrop?.imageType)
    assertEquals(ArtworkType.LOGO, candidate.artwork.logo?.imageType)
    assertEquals("TVDB", candidate.artwork.logo?.trace?.selectedProvider)
}
```

- [ ] **Step 3: Run resolved surface and screensaver tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepositoryTest --tests com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryTest.kt app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt
git commit -m "test: preserve hydrated artwork through display surfaces"
```

## Task 10: Final Verification On Device And Unit Suite

**Files:**
- Create: `tools/reporting/summarize_artwork_state.py`
- Evidence output goes under `review-dossier/`.

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests com.nexio.tv.core.artwork.ArtworkBundleTypeSafetyTest \
  --tests com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest \
  --tests com.nexio.tv.ui.screens.home.ModernHomeRowsArtworkModelTest \
  --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest \
  --tests com.nexio.tv.data.local.MetadataModelSanitizersTest \
  --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest \
  --tests com.nexio.tv.data.integration.metadata.TvdbArtworkCandidateMapperTest \
  --tests com.nexio.tv.data.repository.CatalogRepositoryAddonRoutingTest \
  --tests com.nexio.tv.architecture.PremiumArtworkSharedPipelineContractTest \
  --tests com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepositoryTest \
  --tests com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest
```

Expected: PASS.

- [ ] **Step 2: Run architecture/raw URL boundary tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest \
  --tests com.nexio.tv.architecture.PremiumArtworkSharedPipelineContractTest
```

Expected: PASS.

- [ ] **Step 3: Build debug APK**

Run:

```bash
./gradlew assembleDebug
```

Expected: PASS with a debug APK under `app/build/outputs/apk/debug/`.

- [ ] **Step 4: Install and inspect without clearing logcat**

Run:

```bash
adb connect 192.168.50.98
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.nexio.tv 1
sleep 15
adb logcat -d > review-dossier/2026-05-08-tv-artwork-display-projection-postfix-logcat.txt
```

Expected: app launches; logcat is captured without clearing.

- [ ] **Step 5: Pull app artwork stores for comparison**

Run these commands exactly:

```bash
adb shell su -c 'cat /data/data/com.nexio.tv/shared_prefs/home_catalog_snapshot.xml' > review-dossier/2026-05-08-tv-artwork-display-projection-postfix-home_catalog_snapshot.xml
adb shell su -c 'cat /data/data/com.nexio.tv/shared_prefs/hydrated_home_overlay_v1.xml' > review-dossier/2026-05-08-tv-artwork-display-projection-postfix-hydrated_home_overlay_v1.xml
adb shell su -c 'cat /data/data/com.nexio.tv/files/artwork-decisions-v1.json' > review-dossier/2026-05-08-tv-artwork-display-projection-postfix-decisions-v1.json
adb shell su -c 'cat /data/data/com.nexio.tv/files/artwork-asset-records-v1.json' > review-dossier/2026-05-08-tv-artwork-display-projection-postfix-asset-records-v1.json
```

Expected: files are non-empty.

- [ ] **Step 6: Create sanitized artwork-state summarizer**

Create `tools/reporting/summarize_artwork_state.py`:

```python
#!/usr/bin/env python3
import argparse
import json
import re
from pathlib import Path


PREMIUM_URL_RE = re.compile(r"https?://api\.(?:top-posters|ratingposterdb)\.com", re.IGNORECASE)
RPDB_URL_RE = re.compile(r"https?://api\.ratingposterdb\.com", re.IGNORECASE)
TOP_POSTERS_URL_RE = re.compile(r"https?://api\.top-posters\.com", re.IGNORECASE)
DECISION_REF_RE = re.compile(r"nexio-artwork://decision/([^\"<\s]+)")
ASSET_REF_RE = re.compile(r"nexio-artwork://asset/([^\"<\s]+)")


def read_text(path):
    if not path:
        return ""
    return Path(path).read_text(errors="replace")


def count_wrong_slot_refs(snapshot: str) -> dict[str, int]:
    poster_wrong = 0
    background_wrong = 0
    logo_wrong = 0
    for ref in re.findall(r'"poster"\s*:\s*"(nexio-artwork://(?:asset|decision)/[^"]+)"', snapshot):
        if ":poster:" not in ref and "artwork-decision:poster:" not in ref:
            poster_wrong += 1
    for ref in re.findall(r'"background"\s*:\s*"(nexio-artwork://(?:asset|decision)/[^"]+)"', snapshot):
        if ":backdrop:" not in ref and "artwork-decision:backdrop:" not in ref:
            background_wrong += 1
    for ref in re.findall(r'"logo"\s*:\s*"(nexio-artwork://(?:asset|decision)/[^"]+)"', snapshot):
        if ":logo:" not in ref and "artwork-decision:logo:" not in ref:
            logo_wrong += 1
    return {
        "wrongPosterTypeCount": poster_wrong,
        "wrongBackgroundTypeCount": background_wrong,
        "wrongLogoTypeCount": logo_wrong,
    }


def provider_mismatch_count(snapshot: str) -> int:
    mismatches = 0
    for match in re.finditer(r'"poster"\s*:\s*"([^"]+)".{0,400}?"posterProviderTag"\s*:\s*"([^"]+)"', snapshot, re.DOTALL):
        poster, tag = match.groups()
        provider = None
        if poster.startswith("nexio-artwork://asset/artwork-asset:"):
            provider = poster.split(":", 4)[3].lower()
        elif poster.startswith("nexio-artwork://decision/artwork-decision:"):
            parts = poster.split(":")
            if "provider" in parts:
                provider = parts[parts.index("provider") + 1].lower()
        if provider and provider != tag.lower():
            mismatches += 1
    return mismatches


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot", required=True)
    parser.add_argument("--overlay", required=True)
    parser.add_argument("--decisions", required=True)
    parser.add_argument("--assets", required=True)
    parser.add_argument("--logcat", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    snapshot = read_text(args.snapshot)
    overlay = read_text(args.overlay)
    decisions = read_text(args.decisions)
    assets = read_text(args.assets)
    logcat = read_text(args.logcat)
    combined = "\n".join([snapshot, overlay, decisions, assets, logcat])

    summary = {
        "rawPremiumUrlCount": len(PREMIUM_URL_RE.findall(combined)),
        "rawRpdbUrlCount": len(RPDB_URL_RE.findall(combined)),
        "rawTopPostersUrlCount": len(TOP_POSTERS_URL_RE.findall(combined)),
        "snapshotDecisionRefCount": len(DECISION_REF_RE.findall(snapshot)),
        "snapshotAssetRefCount": len(ASSET_REF_RE.findall(snapshot)),
        "overlayDecisionRefCount": len(DECISION_REF_RE.findall(overlay)),
        "overlayAssetRefCount": len(ASSET_REF_RE.findall(overlay)),
        "providerTagMismatchCount": provider_mismatch_count(snapshot),
        "logcatArtworkFallbackCount": logcat.count("artwork.fallback_materialized"),
        "logcatOverlayProjectionTraceCount": logcat.count("artwork.home_display_projection"),
    }
    summary.update(count_wrong_slot_refs(snapshot))

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
```

- [ ] **Step 7: Run sanitized summary generation**

Run:

```bash
python3 tools/reporting/summarize_artwork_state.py \
  --snapshot review-dossier/2026-05-08-tv-artwork-display-projection-postfix-home_catalog_snapshot.xml \
  --overlay review-dossier/2026-05-08-tv-artwork-display-projection-postfix-hydrated_home_overlay_v1.xml \
  --decisions review-dossier/2026-05-08-tv-artwork-display-projection-postfix-decisions-v1.json \
  --assets review-dossier/2026-05-08-tv-artwork-display-projection-postfix-asset-records-v1.json \
  --logcat review-dossier/2026-05-08-tv-artwork-display-projection-postfix-logcat.txt \
  --out review-dossier/2026-05-08-tv-artwork-display-projection-postfix-summary.json
```

Expected: `review-dossier/2026-05-08-tv-artwork-display-projection-postfix-summary.json` exists and contains only counts, not raw URLs or titles.

- [ ] **Step 8: Manually verify affected rails**

On the device, verify:

```text
Trakt Trending Shows:
  The Boys card is not blank.
  Cards without first-paint posters show a type-correct poster or placeholder.
  Portrait cards do not show landscape backdrops as posters.

TMDB Trending Series:
  Legends / The Boys / Daredevil keep poster-shaped card art.
  TVDB logos can appear after hydration where the surface supports logos.

TMDB Trending Movies:
  My Dearest Assassin does not lose premium poster preference because of raw catalog-cache URLs.
```

- [ ] **Step 9: Commit sanitized verification evidence only**

```bash
git add tools/reporting/summarize_artwork_state.py review-dossier/2026-05-08-tv-artwork-display-projection-postfix-summary.json
git commit -m "docs: capture sanitized tv artwork regression verification summary"
```

## Acceptance Criteria

- Trakt TV rows with no first-paint artwork use hydrated overlay poster/backdrop/logo data before display projection.
- TVDB logos and backdrops from hydrated overlays reach `ResolvedDisplayItem`.
- Poster cards never render `ArtworkType.BACKDROP` or `ArtworkType.LOGO` as poster fallback.
- Premium RPDB poster decisions do not write TMDB fallback assets under the RPDB decision key.
- Non-premium fallback decisions do not inherit premium credential/settings hashes.
- Raw first-paint remote poster URLs do not carry durable `posterProviderTag`.
- Durable asset/decision refs derive `posterProviderTag` from the actual ref provider, so TMDB/TVDB refs cannot preserve stale RPDB tags.
- Home snapshot write barrier clears wrong-type poster, background, logo, and thumbnail refs.
- TVDB mapping remains guarded: type 2 = poster, type 3 = backdrop, type 23 = logo.
- Catalog disk cache no longer writes generated raw RPDB/Top-Posters URLs as final artwork.
- Raw device evidence is not committed; only a sanitized count summary is committed.

## Self-Review Notes

Spec coverage:

- Overlay merge: Task 2.
- Poster/backdrop/logo type boundaries: Tasks 1, 2, 3, and 6.
- Premium fallback materialization invariant: Task 4.
- Provider tag derivation: Task 5.
- Snapshot write barrier: Task 6.
- TVDB candidate guard: Task 7.
- Raw premium catalog cache cleanup: Task 8.
- Home/screensaver propagation guard: Task 9.
- On-device/logcat verification without clearing logs and sanitized evidence commit: Task 10.

Placeholder scan:

- The plan contains no deferred implementation placeholders.

Type consistency:

- `enforceArtworkTypeBoundaries`, `emptyOrNull`, and `takeIfImageType` are defined in Task 1 before use.
- `mergeFallback` already exists on `HomeDisplayMetadata`; Task 2 reuses it after making its artwork merge type-safe.
- `fallbackDecisionFor` is defined before use in `ArtworkAssetRepository`.
- `derivePosterProviderTagFromArtworkRef`, `parseProviderFromDecisionKey`, and `parseProviderFromAssetKey` are defined in Task 5 before use.
- `isArtworkRefForType` and `artworkRefImageType` are defined in Task 6 before use.
