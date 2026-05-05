# Screensaver Display Surface Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make idle image screensaver and trailer screensaver consume the same resolved display surface as Modern Home, preserving artwork, ratings, stable IDs, and trailer behavior.

**Architecture:** Introduce `ResolvedDisplaySurfaceRepository` as a profile/session-safe store for final `ResolvedDisplayItem` objects, not as another composition layer. `HomeResolvedDisplayMapper` is the single bridge from final Modern Home display state plus existing traces into `ResolvedDisplayItem`; screensaver reads candidates from that stored surface and never performs metadata, artwork, or rating enrichment directly. Trailer screensaver lazily resolves playback through `TrailerService.resolveTrailer(...)` using stable IDs and the same item/title/type inputs as Modern Home.

**Tech Stack:** Android Kotlin, Coroutines `Flow`/`StateFlow`, Hilt constructor injection, existing `HydratedHomeOverlay`, `ArtworkBundle`, `TrailerService`, MockK/JUnit unit tests, Gradle `:app:testDebugUnitTest`.

---

## File Structure

Create:

- `app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt`
  - Owns shared display models that are neither Home-specific nor Screensaver-specific.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`
  - Converts final, overlay-applied Home row items plus overlay trace into `ResolvedDisplayItem`; this is the only mapper that composes Home display state into the shared surface.
- `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt`
  - Stores current profile/session snapshots of final resolved display items and exposes `Flow`/snapshot APIs. It does not apply overlays or call metadata/artwork/rating/trailer services.
- `app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt`
  - Projects `ResolvedDisplayItem` into image/trailer screensaver candidate models.
- `app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryTest.kt`
  - Proves final item storage, content-level dedupe, and stale profile-session rejection.
- `app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt`
  - Proves screensaver candidates derive from shared surface only.
- `app/src/test/java/com/nexio/tv/architecture/ScreensaverSurfaceBoundaryTest.kt`
  - Prevents reintroducing direct metadata/rating/artwork provider calls in screensaver code.

Modify:

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt`
  - Add `rowsForResolvedDisplaySurface(...)` so the pipeline can publish the final overlay-applied rows.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
  - Publish current hydrated rows into `ResolvedDisplaySurfaceRepository` after overlays are applied.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
  - Inject `ResolvedDisplaySurfaceRepository`.
- `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt`
  - Replace independent Trakt/Cinemeta source-pool and enrichment logic with `ScreensaverCandidateRepository`.
- `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt`
  - Keep only mapping helpers from resolved candidates to legacy UI models during compatibility.
- `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt`
  - Add artwork-ref/stable-ID fields while keeping `backgroundUrl` as a compatibility projection.
- `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSession.kt`
  - Change session preparation to accept resolved trailer candidates and call a playback resolver with item context.
- `app/src/main/java/com/nexio/tv/MainActivity.kt`
  - Stop constructing direct YouTube URLs in screensaver path; delegate to `TrailerService.resolveTrailer(...)`.
- `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`
  - Add screensaver candidate/selection trace events.
- `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
  - Add concise logcat payload rendering for new screensaver events.
- `app/src/test/java/com/nexio/tv/data/repository/IdleScreensaverRepositoryTest.kt`
  - Rewrite tests from source-pool assertions to shared-surface assertions.
- `app/src/test/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSessionTest.kt`
  - Add tests for trailers resolving without pre-existing `trailerYtIds`.
- `app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt`
  - Replace direct YouTube URL expectation with shared trailer resolver behavior.
- `app/src/test/java/com/nexio/tv/core/trace/TraceMetadataEventsTest.kt`
  - Add trace event tests.
- `app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt`
  - Add logcat field selection tests for screensaver events.

Do not modify:

- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt`
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`

Those components already own the canonical provider decisions. This work consumes their output.

Non-negotiable design constraints:

- `ResolvedDisplaySurfaceRepository` stores final items only; it must not apply `HydratedHomeOverlay`, call `FieldResolver`, or recompute provider precedence.
- `HomeResolvedDisplayMapper` is the only new Home-to-surface mapper. Reuse existing shared types for rating, trace, trailer confidence, and provider identity; specifically use `TitleRating` for ratings and `HydratedHomeFieldTrace` for field traces instead of introducing parallel display semantics.
- The shared surface is content-level for this migration. It may dedupe by `itemKey` only at the surface boundary, and Modern Home must not use it as a rail occurrence store until a separate `ResolvedDisplayOccurrence` model exists.
- Screensaver may dedupe, shuffle, rank, and filter candidates. It may not choose metadata providers, rating precedence, artwork fallback providers, or trailer provider ordering.
- Empty-surface behavior is explicit: this implementation shows bundled placeholder art and emits a trace. Screensaver must not call Trakt, Cinemeta, MDBList, or provider repositories directly.
- Trace events use `profileHash`, derived from `TraceHash.of(sessionId, profileId.toString())`, not raw `profileId`.

---

### Task 1: Shared Resolved Display Surface Models

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryTest.kt`

- [ ] **Step 1: Write the failing model usage test**

Create the test file with this initial test. It compiles only after the models are added.

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedDisplaySurfaceRepositoryTest {
    @Test
    fun `resolved display item carries canonical display fields artwork rating stable ids and trailer state`() {
        val item = ResolvedDisplayItem(
            itemKey = "movie:tmdb:550",
            contentId = "tmdb:550",
            parentId = "tmdb:550",
            itemType = ContentType.MOVIE,
            mediaKind = MetadataMediaKind.MOVIE,
            canonicalProvider = "TMDB",
            canonicalId = "550",
            imdbId = "tt0137523",
            stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            display = ResolvedDisplayFields(
                title = "Fight Club",
                originalTitle = null,
                year = 1999,
                releaseDate = "1999",
                overview = "An insomniac office worker...",
                genres = listOf("Drama"),
                runtimeText = "139m"
            ),
            artwork = ArtworkBundle(),
            rating = TitleRating(value = 8.8, source = TitleRatingSource.IMDB),
            trailer = TrailerDisplayState(fallbackTrailerYtIds = emptyList()),
            hydrationState = HydrationState.CANONICAL_READY,
            sourceTrace = emptyList(),
            updatedAtMs = 123L
        )

        assertEquals("movie:tmdb:550", item.itemKey)
        assertEquals("Fight Club", item.display.title)
        assertEquals("tt0137523", item.stableIds.imdb)
        assertEquals(8.8, item.rating?.value ?: 0.0, 0.0)
        assertTrue(item.trailer.fallbackTrailerYtIds.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepositoryTest
```

Expected: FAIL at Kotlin compilation with unresolved references for `ResolvedDisplayItem`, `ResolvedDisplayFields`, `TrailerDisplayState`, and `HydrationState`.

- [ ] **Step 3: Add the shared model file**

Create `app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt`:

```kotlin
package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataMediaKind

@Immutable
data class ResolvedDisplayItem(
    val itemKey: String,
    val contentId: String,
    val parentId: String,
    val itemType: ContentType,
    val mediaKind: MetadataMediaKind,
    val canonicalProvider: String?,
    val canonicalId: String?,
    val imdbId: String?,
    val stableIds: ProviderIds,
    val display: ResolvedDisplayFields,
    val artwork: ArtworkBundle,
    val rating: TitleRating?,
    val trailer: TrailerDisplayState,
    val hydrationState: HydrationState,
    val sourceTrace: List<HydratedHomeFieldTrace>,
    val updatedAtMs: Long
)

@Immutable
data class ResolvedDisplayFields(
    val title: String?,
    val originalTitle: String?,
    val year: Int?,
    val releaseDate: String?,
    val overview: String?,
    val genres: List<String>,
    val runtimeText: String?
)

@Immutable
data class TrailerDisplayState(
    val fallbackTrailerYtIds: List<String> = emptyList(),
    val resolverSource: String? = null,
    val lastResolvedAtMs: Long? = null
)

enum class HydrationState {
    PREVIEW_ONLY,
    IDENTITY_READY,
    HYDRATING,
    CANONICAL_READY,
    FAILED_USING_PREVIEW,
    STALE_READY
}

```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepositoryTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryTest.kt
git commit -m "feat: add resolved display surface models"
```

---

### Task 2: Store Final Resolved Items With Profile Session Safety

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryTest.kt`

- [ ] **Step 1: Add failing repository storage tests**

Append these imports to `ResolvedDisplaySurfaceRepositoryTest.kt`:

```kotlin
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
```

Append these tests and helpers inside the class:

```kotlin
    @Test
    fun `publishResolvedItems stores final items without recomposing overlays`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val item = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Already Final Home Title",
            overview = "Already final overview"
        )

        repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(item)
        )

        val snapshot = repository.getSnapshot(profileId = 1)
        assertEquals(1, snapshot.size)
        assertEquals("Already Final Home Title", snapshot.single().display.title)
        assertEquals("Already final overview", snapshot.single().display.overview)
    }

    @Test
    fun `publishResolvedItems rejects stale profile publish after profile switch`() = runTest {
        val staleSession = profileSession(profileId = 1, sessionId = "session-a")
        val activeSession = MutableStateFlow(profileSession(profileId = 2, sessionId = "session-b"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })

        repository.publishResolvedItems(
            profileSession = staleSession,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:550", title = "Stale item"))
        )

        assertEquals(emptyList<ResolvedDisplayItem>(), repository.getSnapshot(profileId = 1))
        assertEquals(emptyList<ResolvedDisplayItem>(), repository.getSnapshot(profileId = 2))
    }

    @Test
    fun `publishResolvedItems stores a content level deduped surface`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })

        repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(
                resolvedItem(itemKey = "movie:tmdb:550", title = "Rail A Title"),
                resolvedItem(itemKey = "movie:tmdb:550", title = "Rail B Title")
            )
        )

        assertEquals(1, repository.getSnapshot(profileId = 1).size)
        assertEquals("Rail A Title", repository.getSnapshot(profileId = 1).single().display.title)
    }

    private fun profileSession(
        profileId: Int,
        sessionId: String
    ) = ActiveProfileSession(
        profileId = profileId,
        sessionId = sessionId,
        sessionOrdinal = profileId.toLong(),
        startedAtMs = 1_000L + profileId
    )

    private fun resolvedItem(
        itemKey: String,
        title: String,
        overview: String = "Overview"
    ) = ResolvedDisplayItem(
        itemKey = itemKey,
        contentId = "tmdb:550",
        parentId = "tmdb:550",
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = "TMDB",
        canonicalId = "550",
        imdbId = "tt0137523",
        stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
        display = ResolvedDisplayFields(
            title = title,
            originalTitle = null,
            year = 1999,
            releaseDate = "1999",
            overview = overview,
            genres = listOf("Drama"),
            runtimeText = "139m"
        ),
        artwork = ArtworkBundle(),
        rating = TitleRating(8.8, TitleRatingSource.IMDB),
        trailer = TrailerDisplayState(fallbackTrailerYtIds = emptyList()),
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = emptyList(),
        updatedAtMs = 1L
    )
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepositoryTest
```

Expected: FAIL with unresolved `ResolvedDisplaySurfaceRepository.publishResolvedItems` / `getSnapshot`.

- [ ] **Step 3: Implement store-only repository**

Create `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.ResolvedDisplayItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

@Singleton
class ResolvedDisplaySurfaceRepository(
    private val activeProfileSession: () -> ActiveProfileSession
) {
    @Inject
    constructor(profileManager: ProfileManager) : this(
        activeProfileSession = { profileManager.activeProfileSession.value }
    )

    private val surfaces = MutableStateFlow<Map<Int, List<ResolvedDisplayItem>>>(emptyMap())

    fun observeHomeSurface(profileId: Int): Flow<List<ResolvedDisplayItem>> =
        surfaces.map { byProfile -> byProfile[profileId].orEmpty() }

    fun observeItem(profileId: Int, itemKey: String): Flow<ResolvedDisplayItem?> =
        observeHomeSurface(profileId).map { items -> items.firstOrNull { it.itemKey == itemKey } }

    suspend fun getSnapshot(profileId: Int): List<ResolvedDisplayItem> =
        surfaces.value[profileId].orEmpty()

    fun publishResolvedItems(
        profileSession: ActiveProfileSession,
        items: List<ResolvedDisplayItem>
    ): Boolean {
        val active = activeProfileSession()
        if (active.profileId != profileSession.profileId || active.sessionId != profileSession.sessionId) {
            return false
        }

        surfaces.update { current ->
            current + (profileSession.profileId to items.distinctBy { item -> item.itemKey })
        }
        return true
    }

    internal fun replaceForTest(
        profileId: Int,
        items: List<ResolvedDisplayItem>
    ) {
        surfaces.update { current -> current + (profileId to items.distinctBy { item -> item.itemKey }) }
    }
}
```

This repository deliberately has no imports from `HydratedHomeOverlay`, `HomeDisplayMetadata`, `FieldResolver`, `MetadataRouterFacade`, `ArtworkRouter`, rating repositories, or trailer services.

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepositoryTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryTest.kt
git commit -m "feat: store resolved display surface by profile session"
```

---

### Task 3: Map Final Modern Home Rows Into Resolved Display Items

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt`

- [ ] **Step 1: Add failing mapper test**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HomeResolvedDisplayMapperTest {
    @Test
    fun `mapper uses final home item and overlay trace without applying overlays again`() {
        val finalItem = preview(
            id = "tmdb:550",
            title = "Final Home Title",
            overview = "Final Home Overview",
            rating = 8.8f,
            artwork = ArtworkBundle(backdrop = artworkRef("backdrop-550"))
        )
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(
                title = "Overlay Title That Must Not Be Reapplied",
                description = "Overlay Overview That Must Not Be Reapplied",
                imdbRating = 8.8f,
                ratingSource = TitleRatingSource.IMDB,
                posterProviderTag = "top_posters",
                artwork = ArtworkBundle(backdrop = artworkRef("backdrop-550"))
            )
        )

        val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(finalItem)),
            overlaysByItemKey = mapOf("movie:tmdb:550" to overlay),
            nowMs = 10_000L
        ).single()

        assertEquals("Final Home Title", resolved.display.title)
        assertEquals("Final Home Overview", resolved.display.overview)
        assertEquals("tt0137523", resolved.imdbId)
        assertEquals("550", resolved.stableIds.tmdb)
        assertEquals(8.8, resolved.rating?.value ?: 0.0, 0.0)
        assertNotNull(resolved.artwork.backdrop)
        assertEquals("top_posters", resolved.artwork.backdrop?.trace?.selectedProvider)
        assertEquals(HydrationState.CANONICAL_READY, resolved.hydrationState)
        assertEquals("POSTER", resolved.sourceTrace.single().field)
    }

    private fun preview(
        id: String,
        title: String,
        overview: String,
        rating: Float?,
        artwork: ArtworkBundle
    ) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = title,
        poster = "legacy-poster",
        posterShape = PosterShape.POSTER,
        background = "legacy-backdrop",
        logo = null,
        description = overview,
        releaseInfo = "1999",
        runtime = "139m",
        imdbRating = rating,
        ratingSource = TitleRatingSource.IMDB,
        genres = listOf("Drama"),
        artwork = artwork,
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW
    )

    private fun row(item: MetaPreview) = CatalogRow(
        addonId = "home",
        addonName = "Home",
        addonBaseUrl = "https://home.example",
        catalogId = "popular",
        catalogName = "Popular",
        type = item.type,
        items = listOf(item),
        hasMore = false
    )

    private fun overlay(
        itemKey: String,
        fields: HomeDisplayMetadata
    ) = HydratedHomeOverlay(
        overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
        itemKey = itemKey,
        canonicalProvider = ProviderId.TMDB,
        canonicalId = "550",
        imdbId = "tt0137523",
        contentType = ContentType.MOVIE,
        languageTag = "en",
        fields = fields,
        fieldTrace = listOf(HydratedHomeFieldTrace("POSTER", "TOP_POSTERS", "ARTWORK")),
        displayHash = fields.hydratedHomeDisplayHash(),
        updatedAtMs = 9_000L,
        staleAtMs = 20_000L,
        expiresAtMs = 30_000L
    )

    private fun artworkRef(key: String) = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey(key),
        assetKey = null,
        imageType = ArtworkType.BACKDROP,
        selectedProvider = null,
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace(selectedProvider = "top_posters", sourceRole = "ARTWORK")
    )
}
```

- [ ] **Step 2: Run mapper test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest
```

Expected: FAIL with unresolved `HomeResolvedDisplayMapper`.

- [ ] **Step 3: Add publication helper test**

Append this test to `HomeHydrationOverlayApplierTest.kt`:

```kotlin
    @Test
    fun `rowsForResolvedDisplaySurface returns final overlay applied rows`() {
        val item = preview("tmdb:550", "Preview title")
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(
                title = "Resolved title",
                poster = "resolved-poster",
                backdrop = "resolved-backdrop",
                imdbRating = 8.8f,
                ratingSource = TitleRatingSource.IMDB
            )
        )

        val publishedRows = rowsForResolvedDisplaySurface(
            rows = listOf(row(listOf(item))),
            overlaysByItemKey = mapOf("movie:tmdb:550" to overlay)
        )

        assertEquals("Resolved title", publishedRows.single().items.single().name)
        assertEquals("resolved-poster", publishedRows.single().items.single().poster)
        assertEquals(8.8f, publishedRows.single().items.single().imdbRating ?: 0f, 0f)
    }
```

- [ ] **Step 4: Implement Home resolved display mapper**

Create `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.toHomeDisplayMetadata

internal object HomeResolvedDisplayMapper {
    fun toResolvedDisplayItems(
        rows: List<CatalogRow>,
        overlaysByItemKey: Map<String, HydratedHomeOverlay>,
        nowMs: Long = System.currentTimeMillis()
    ): List<ResolvedDisplayItem> =
        rows.flatMap { row -> row.items }
            .map { item -> item.toResolvedDisplayItem(overlaysByItemKey, nowMs) }

    private fun MetaPreview.toResolvedDisplayItem(
        overlaysByItemKey: Map<String, HydratedHomeOverlay>,
        nowMs: Long
    ): ResolvedDisplayItem {
        val itemKey = homeDisplayItemKey(apiType, id)
        val overlay = overlaysByItemKey[itemKey]
        val fields = toHomeDisplayMetadata()
        val ratingSource = fields.ratingSource ?: TitleRatingSource.IMDB

        return ResolvedDisplayItem(
            itemKey = itemKey,
            contentId = id,
            parentId = id,
            itemType = type,
            mediaKind = when (apiType.lowercase()) {
                "movie" -> MetadataMediaKind.MOVIE
                "series", "tv", "show" -> MetadataMediaKind.SERIES
                else -> MetadataMediaKind.UNKNOWN
            },
            canonicalProvider = overlay?.canonicalProvider?.name,
            canonicalId = overlay?.canonicalId,
            imdbId = overlay?.imdbId ?: firstPaintStableIds.imdb,
            stableIds = ProviderIds(
                imdb = overlay?.imdbId ?: firstPaintStableIds.imdb,
                tmdb = overlay?.canonicalId?.takeIf { overlay.canonicalProvider.name == "TMDB" } ?: firstPaintStableIds.tmdb,
                tvdb = overlay?.canonicalId?.takeIf { overlay.canonicalProvider.name == "TVDB" } ?: firstPaintStableIds.tvdb,
                trakt = firstPaintStableIds.trakt,
                simkl = firstPaintStableIds.simkl,
                kitsu = firstPaintStableIds.kitsu,
                slug = firstPaintStableIds.slug,
                mal = firstPaintStableIds.mal,
                anilist = firstPaintStableIds.anilist,
                anidb = firstPaintStableIds.anidb
            ),
            display = ResolvedDisplayFields(
                title = fields.title,
                originalTitle = null,
                year = fields.releaseInfo?.take(4)?.toIntOrNull(),
                releaseDate = fields.releaseInfo,
                overview = fields.description,
                genres = fields.genres,
                runtimeText = fields.runtime
            ),
            artwork = fields.artwork ?: ArtworkBundle(),
            rating = fields.imdbRating?.let { value -> TitleRating(value.toDouble(), ratingSource) },
            trailer = TrailerDisplayState(fallbackTrailerYtIds = emptyList()),
            hydrationState = when {
                overlay == null -> HydrationState.PREVIEW_ONLY
                overlay.isStale(nowMs) -> HydrationState.STALE_READY
                else -> HydrationState.CANONICAL_READY
            },
            sourceTrace = overlay?.fieldTrace.orEmpty(),
            updatedAtMs = overlay?.updatedAtMs ?: nowMs
        )
    }
}
```

- [ ] **Step 5: Add publication helper**

Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt` by adding:

```kotlin
internal fun rowsForResolvedDisplaySurface(
    rows: List<CatalogRow>,
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): List<CatalogRow> = rows.applyHydratedHomeOverlays(overlaysByItemKey)
```

- [ ] **Step 6: Inject repository into `HomeViewModel`**

In `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`, add:

```kotlin
import com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository
```

Add constructor parameter next to other repositories:

```kotlin
internal val resolvedDisplaySurfaceRepository: ResolvedDisplaySurfaceRepository,
```

- [ ] **Step 7: Publish final mapped items after home overlays are applied**

In `HomeViewModelCatalogPipeline.kt`, publish only final mapped items immediately after the existing overlay application point:

```kotlin
val profileSessionForSurface = profileManager.activeProfileSession.value
val rowsForSurface = rowsForResolvedDisplaySurface(
    rows = rowsAfterOverlayApplication,
    overlaysByItemKey = activeHydrationOverlaysByItemKey
)
val resolvedItemsForSurface = HomeResolvedDisplayMapper.toResolvedDisplayItems(
    rows = rowsForSurface,
    overlaysByItemKey = activeHydrationOverlaysByItemKey
)
resolvedDisplaySurfaceRepository.publishResolvedItems(
    profileSession = profileSessionForSurface,
    items = resolvedItemsForSurface
)
```

The repository rejects stale writes if `profileSessionForSurface` is no longer the active profile session by publication time.

- [ ] **Step 8: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest --tests com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest --tests com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepositoryTest
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt
git commit -m "feat: publish final home display surface"
```

---

### Task 4: Project Resolved Display Items Into Image Screensaver Candidates

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt`

- [ ] **Step 1: Write failing projection tests**

Create `app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverCandidateRepositoryTest {
    @Test
    fun `image candidates are projected from resolved display surface with artwork refs and rating`() = runTest {
        val surface = testSurface()
        val repository = ScreensaverCandidateRepository(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(resolvedItem("movie:tmdb:550", title = "Fight Club"))
        )

        val candidates = repository.observeImageCandidates(profileId = 1).first()

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals("movie:tmdb:550", candidate.itemKey)
        assertEquals("Fight Club", candidate.title)
        assertEquals(8.8, candidate.rating?.value ?: 0.0, 0.0)
        assertTrue(candidate.preferredImage is ArtworkDisplayRef.RuntimeAsset)
        assertEquals("tt0137523", candidate.stableIds.imdb)
    }

    @Test
    fun `image candidates exclude items without poster or backdrop artwork`() = runTest {
        val surface = testSurface()
        val repository = ScreensaverCandidateRepository(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(resolvedItem("movie:tmdb:551", title = "No Art", artwork = ArtworkBundle()))
        )

        assertEquals(emptyList<com.nexio.tv.ui.screensaver.ScreensaverSlideCandidate>(), repository.observeImageCandidates(1).first())
    }

    private fun resolvedItem(
        itemKey: String,
        title: String,
        artwork: ArtworkBundle = ArtworkBundle(backdrop = artworkRef("backdrop-550"))
    ) = ResolvedDisplayItem(
        itemKey = itemKey,
        contentId = "tmdb:550",
        parentId = "tmdb:550",
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = "TMDB",
        canonicalId = "550",
        imdbId = "tt0137523",
        stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
        display = ResolvedDisplayFields(
            title = title,
            originalTitle = null,
            year = 1999,
            releaseDate = "1999",
            overview = "Overview",
            genres = listOf("Drama"),
            runtimeText = "139m"
        ),
        artwork = artwork,
        rating = TitleRating(8.8, TitleRatingSource.IMDB),
        trailer = TrailerDisplayState(fallbackTrailerYtIds = emptyList()),
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = emptyList(),
        updatedAtMs = 1L
    )

    private fun testSurface() = ResolvedDisplaySurfaceRepository(
        activeProfileSession = {
            ActiveProfileSession(
                profileId = 1,
                sessionId = "test-session",
                sessionOrdinal = 1L,
                startedAtMs = 1_000L
            )
        }
    )

    private fun artworkRef(key: String) = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey(key),
        assetKey = null,
        imageType = ArtworkType.BACKDROP,
        selectedProvider = null,
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace(selectedProvider = "TOP_POSTERS", sourceRole = "ARTWORK")
    )
}
```

- [ ] **Step 2: Confirm the test-only replacement API from Task 2 is available**

Task 2 added this method to `ResolvedDisplaySurfaceRepository`:

```kotlin
internal fun replaceForTest(
    profileId: Int,
    items: List<ResolvedDisplayItem>
) {
    surfaces.update { current -> current + (profileId to items.distinctBy { item -> item.itemKey }) }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest
```

Expected: FAIL with unresolved `ScreensaverCandidateRepository` and `ScreensaverSlideCandidate`.

- [ ] **Step 4: Add candidate models**

Modify `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt` by appending:

```kotlin
@Immutable
data class ScreensaverSlideCandidate(
    val itemKey: String,
    val contentId: String,
    val itemType: String,
    val title: String?,
    val subtitle: String?,
    val overview: String?,
    val rating: com.nexio.tv.domain.model.TitleRating?,
    val artwork: com.nexio.tv.core.artwork.ArtworkBundle,
    val preferredImage: com.nexio.tv.core.artwork.ArtworkDisplayRef?,
    val stableIds: com.nexio.tv.domain.model.ProviderIds,
    val trace: List<com.nexio.tv.domain.model.HydratedHomeFieldTrace>
)
```

- [ ] **Step 5: Implement projection repository**

Create `app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.ui.screensaver.ScreensaverSlideCandidate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ScreensaverCandidateRepository @Inject constructor(
    private val surfaceRepository: ResolvedDisplaySurfaceRepository
) {
    fun observeImageCandidates(profileId: Int): Flow<List<ScreensaverSlideCandidate>> =
        surfaceRepository.observeHomeSurface(profileId).map { items ->
            items.mapNotNull { item -> item.toImageCandidate() }
        }

    private fun ResolvedDisplayItem.toImageCandidate(): ScreensaverSlideCandidate? {
        val preferred = preferredScreensaverArtwork() ?: return null
        return ScreensaverSlideCandidate(
            itemKey = itemKey,
            contentId = contentId,
            itemType = itemType.toApiString(),
            title = display.title,
            subtitle = display.releaseDate,
            overview = display.overview,
            rating = rating,
            artwork = artwork,
            preferredImage = preferred,
            stableIds = stableIds,
            trace = sourceTrace
        )
    }

    private fun ResolvedDisplayItem.preferredScreensaverArtwork(): ArtworkDisplayRef? =
        artwork.backdrop ?: artwork.poster
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt
git commit -m "feat: project screensaver candidates from display surface"
```

---

### Task 5: Migrate Image Screensaver Repository to Shared Surface

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/IdleScreensaverRepositoryTest.kt`

- [ ] **Step 1: Replace repository tests with shared-surface assertions**

In `IdleScreensaverRepositoryTest.kt`, add a new test:

```kotlin
    @Test
    fun `warmFromCache publishes slides from resolved display surface and does not call metadata enrichers`() = runBlocking {
        val screensaverCandidates = mockk<ScreensaverCandidateRepository>()
        val artworkRef = testBackdropRef()
        val candidate = com.nexio.tv.ui.screensaver.ScreensaverSlideCandidate(
            itemKey = "movie:tmdb:550",
            contentId = "tmdb:550",
            itemType = "movie",
            title = "Fight Club",
            subtitle = "1999",
            overview = "Overview",
            rating = com.nexio.tv.domain.model.TitleRating(
                value = 8.8,
                source = TitleRatingSource.IMDB
            ),
            artwork = com.nexio.tv.core.artwork.ArtworkBundle(backdrop = artworkRef),
            preferredImage = artworkRef,
            stableIds = com.nexio.tv.domain.model.ProviderIds(tmdb = "550", imdb = "tt0137523"),
            trace = emptyList()
        )
        every { screensaverCandidates.observeImageCandidates(profileId = 1) } returns flowOf(listOf(candidate))

        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = screensaverCandidates,
            activeProfileId = { 1 }
        )

        repository.warmFromCache()

        assertEquals(1, repository.slides.value.size)
        assertEquals("Fight Club", repository.slides.value.single().title)
        assertEquals(8.8f, repository.slides.value.single().imdbRating ?: 0f, 0f)
    }

    private fun testBackdropRef() = com.nexio.tv.core.artwork.ArtworkDisplayRef.RuntimeAsset(
        decisionKey = com.nexio.tv.core.artwork.ArtworkDecisionKey("backdrop-550"),
        assetKey = null,
        imageType = com.nexio.tv.core.artwork.ArtworkType.BACKDROP,
        selectedProvider = null,
        sourceRole = com.nexio.tv.core.artwork.ArtworkSourceRole.PREMIUM,
        trace = com.nexio.tv.core.artwork.ArtworkTrace(selectedProvider = "top_posters", sourceRole = "ARTWORK")
    )
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.IdleScreensaverRepositoryTest
```

Expected: FAIL because `IdleScreensaverRepository` still has old constructor dependencies.

- [ ] **Step 3: Simplify `IdleScreensaverRepository` constructor and source**

Replace the constructor in `IdleScreensaverRepository.kt` with:

```kotlin
@Singleton
class IdleScreensaverRepository(
    private val screensaverCandidateRepository: ScreensaverCandidateRepository,
    private val activeProfileId: () -> Int
) {
    @Inject
    constructor(
        screensaverCandidateRepository: ScreensaverCandidateRepository,
        profileManager: com.nexio.tv.core.profile.ProfileManager
    ) : this(
        screensaverCandidateRepository = screensaverCandidateRepository,
        activeProfileId = { profileManager.activeProfileId.value }
    )
```

Then replace `warmFromCache()` and `refreshOnColdBoot()` with:

```kotlin
    suspend fun warmFromCache() {
        refreshFromResolvedSurface()
    }

    suspend fun refreshOnColdBoot() {
        refreshFromResolvedSurface()
    }

    private suspend fun refreshFromResolvedSurface() {
        refreshMutex.withLock {
            val profileId = activeProfileId()
            val candidates = screensaverCandidateRepository.observeImageCandidates(profileId).first()
            _slides.value = candidates.mapNotNull { it.toIdleScreensaverSlide() }
            Log.d(TAG, "Prepared ${_slides.value.size} idle screensaver slides from resolved display surface")
        }
    }
```

- [ ] **Step 4: Add candidate-to-slide mapper**

In `IdleScreensaverPreparation.kt`, add:

```kotlin
internal fun com.nexio.tv.ui.screensaver.ScreensaverSlideCandidate.toIdleScreensaverSlide(): IdleScreensaverSlide? {
    val imageUrl = preferredImage.toLegacyArtworkString()
        ?: artwork.backdrop.toLegacyArtworkString()
        ?: artwork.poster.toLegacyArtworkString()
        ?: return null
    return IdleScreensaverSlide(
        itemId = contentId,
        itemType = itemType,
        addonBaseUrl = "",
        title = title.orEmpty(),
        backgroundUrl = imageUrl,
        logoUrl = artwork.logo.toLegacyArtworkString(),
        genres = emptyList(),
        description = overview,
        releaseInfo = subtitle,
        runtime = null,
        imdbRating = rating?.value?.toFloat(),
        tomatoesRating = null,
        modeData = IdleScreensaverModeData(
            image = IdleScreensaverImageModeData(fallbackArtworkUrls = listOf(imageUrl))
        )
    )
}
```

Add import:

```kotlin
import com.nexio.tv.core.artwork.toLegacyArtworkString
```

- [ ] **Step 5: Remove direct screensaver source-pool code**

Delete these from `IdleScreensaverRepository.kt` after tests compile:

```kotlin
findStockCinemetaPopularCatalogRequest(...)
shouldUseTraktScreensaverSource(...)
buildTraktScreensaverRows(...)
fetchScreensaverCatalog(...)
fetchCachedScreensaverCatalog(...)
```

Keep constants only if tests still need them. If a deleted helper is referenced by old tests, delete or rewrite those old tests to assert the shared-surface behavior.

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.IdleScreensaverRepositoryTest --tests com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt app/src/test/java/com/nexio/tv/data/repository/IdleScreensaverRepositoryTest.kt
git commit -m "feat: source image screensaver from display surface"
```

---

### Task 6: Add Trailer Candidate Projection and Lazy Resolution

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSession.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSessionTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/IdleScreensaverRepositoryTest.kt`

- [ ] **Step 1: Add failing trailer projection test**

Append to `ScreensaverCandidateRepositoryTest.kt`:

```kotlin
    @Test
    fun `trailer candidates come from resolved items even when trailer ids are empty`() = runTest {
        val surface = testSurface()
        val repository = ScreensaverCandidateRepository(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem("series:tvdb:81189", title = "Breaking Bad").copy(
                    contentId = "tvdb:81189",
                    parentId = "tvdb:81189",
                    itemType = ContentType.SERIES,
                    mediaKind = MetadataMediaKind.SERIES,
                    canonicalProvider = "TVDB",
                    canonicalId = "81189",
                    stableIds = ProviderIds(tvdb = "81189", imdb = "tt0903747")
                )
            )
        )

        val candidates = repository.observeTrailerCandidates(profileId = 1).first()

        assertEquals(1, candidates.size)
        assertEquals("series:tvdb:81189", candidates.single().itemKey)
        assertEquals("Breaking Bad", candidates.single().title)
        assertTrue(candidates.single().fallbackTrailerYtIds.isEmpty())
        assertEquals("81189", candidates.single().stableIds.tvdb)
    }
```

- [ ] **Step 2: Add failing repository trailer population test**

Append to `IdleScreensaverRepositoryTest.kt`:

```kotlin
    @Test
    fun `warmFromCache populates trailer candidates from resolved display surface`() = runBlocking {
        val screensaverCandidates = mockk<ScreensaverCandidateRepository>()
        every { screensaverCandidates.observeImageCandidates(profileId = 1) } returns flowOf(emptyList())
        every { screensaverCandidates.observeTrailerCandidates(profileId = 1) } returns flowOf(
            listOf(
                com.nexio.tv.ui.screensaver.ScreensaverTrailerCandidate(
                    itemKey = "series:tvdb:81189",
                    contentId = "tvdb:81189",
                    itemType = "series",
                    title = "Breaking Bad",
                    releaseInfo = "2008",
                    overview = "A chemistry teacher...",
                    rating = com.nexio.tv.domain.model.TitleRating(
                        value = 9.5,
                        source = TitleRatingSource.IMDB
                    ),
                    artwork = com.nexio.tv.core.artwork.ArtworkBundle(backdrop = testBackdropRef()),
                    fallbackTrailerYtIds = emptyList(),
                    stableIds = com.nexio.tv.domain.model.ProviderIds(tvdb = "81189", imdb = "tt0903747")
                )
            )
        )

        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = screensaverCandidates,
            activeProfileId = { 1 }
        )

        repository.warmFromCache()

        assertEquals(1, repository.trailerCandidates.value.size)
        assertEquals("Breaking Bad", repository.trailerCandidates.value.single().title)
        assertEquals("81189", repository.trailerCandidates.value.single().stableIds.tvdb)
    }
```

- [ ] **Step 3: Add failing session test**

Append to `IdleTrailerScreensaverSessionTest.kt`:

```kotlin
    @Test
    fun `prepare trailer screensaver session can resolve candidate without preexisting youtube ids`() = runBlocking {
        val candidates = listOf(
            IdleTrailerScreensaverCandidate(
                itemId = "tvdb:81189",
                itemType = "series",
                addonBaseUrl = "",
                title = "Breaking Bad",
                logoUrl = null,
                backgroundUrl = "nexio-artwork://decision/backdrop-81189",
                fallbackArtworkUrls = listOf("nexio-artwork://decision/backdrop-81189"),
                genres = emptyList(),
                description = "A chemistry teacher...",
                releaseInfo = "2008",
                runtime = null,
                imdbRating = 9.5f,
                tomatoesRating = null,
                trailerYtIds = emptyList(),
                stableIds = com.nexio.tv.domain.model.ProviderIds(tvdb = "81189", imdb = "tt0903747")
            )
        )

        val session = prepareIdleTrailerScreensaverSessionFromCandidates(
            candidates = candidates,
            shuffleCandidates = { it }
        ) { candidate, trailerId ->
            if (candidate.itemId == "tvdb:81189" && trailerId == RESOLVE_TRAILER_BY_ITEM_SENTINEL) {
                TrailerPlaybackSource(videoUrl = "https://video.example/breaking-bad.m3u8")
            } else {
                null
            }
        }

        requireNotNull(session)
        assertEquals("tvdb:81189", session.initialPlayback.candidate.itemId)
        assertEquals(RESOLVE_TRAILER_BY_ITEM_SENTINEL, session.initialPlayback.trailerId)
    }
```

- [ ] **Step 4: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest --tests com.nexio.tv.data.repository.IdleScreensaverRepositoryTest --tests com.nexio.tv.ui.screensaver.IdleTrailerScreensaverSessionTest
```

Expected: FAIL with unresolved `observeTrailerCandidates` and `RESOLVE_TRAILER_BY_ITEM_SENTINEL`.

- [ ] **Step 5: Preserve stable IDs on legacy trailer candidates**

Modify `IdleTrailerScreensaverCandidate` in `IdleScreensaverModels.kt` by adding a defaulted field:

```kotlin
data class IdleTrailerScreensaverCandidate(
    val itemId: String,
    val itemType: String,
    val addonBaseUrl: String,
    val title: String,
    val logoUrl: String?,
    val backgroundUrl: String,
    val fallbackArtworkUrls: List<String>,
    val genres: List<String>,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val imdbRating: Float?,
    val tomatoesRating: Double? = null,
    val trailerYtIds: List<String>,
    val stableIds: com.nexio.tv.domain.model.ProviderIds = com.nexio.tv.domain.model.ProviderIds()
)
```

Update the `constructor(slide: IdleScreensaverSlide, trailerYtIds: List<String>)` call to pass:

```kotlin
        stableIds = com.nexio.tv.domain.model.ProviderIds()
```

- [ ] **Step 6: Add trailer candidate projection**

Append to `IdleScreensaverModels.kt`:

```kotlin
@Immutable
data class ScreensaverTrailerCandidate(
    val itemKey: String,
    val contentId: String,
    val itemType: String,
    val title: String,
    val releaseInfo: String?,
    val overview: String?,
    val rating: com.nexio.tv.domain.model.TitleRating?,
    val artwork: com.nexio.tv.core.artwork.ArtworkBundle,
    val fallbackTrailerYtIds: List<String>,
    val stableIds: com.nexio.tv.domain.model.ProviderIds
)
```

In `ScreensaverCandidateRepository.kt`, add:

```kotlin
    fun observeTrailerCandidates(profileId: Int): Flow<List<com.nexio.tv.ui.screensaver.ScreensaverTrailerCandidate>> =
        surfaceRepository.observeHomeSurface(profileId).map { items ->
            items.mapNotNull { item -> item.toTrailerCandidate() }
        }

    private fun ResolvedDisplayItem.toTrailerCandidate(): com.nexio.tv.ui.screensaver.ScreensaverTrailerCandidate? {
        if (display.title.isNullOrBlank()) return null
        val preferred = preferredScreensaverArtwork() ?: return null
        return com.nexio.tv.ui.screensaver.ScreensaverTrailerCandidate(
            itemKey = itemKey,
            contentId = contentId,
            itemType = itemType.toApiString(),
            title = display.title,
            releaseInfo = display.releaseDate,
            overview = display.overview,
            rating = rating,
            artwork = artwork,
            fallbackTrailerYtIds = trailer.fallbackTrailerYtIds,
            stableIds = stableIds
        )
    }
```

- [ ] **Step 7: Add trailer candidate-to-legacy mapper**

In `IdleScreensaverPreparation.kt`, add:

```kotlin
internal fun com.nexio.tv.ui.screensaver.ScreensaverTrailerCandidate.toIdleTrailerScreensaverCandidate(): IdleTrailerScreensaverCandidate? {
    val imageUrl = artwork.backdrop.toLegacyArtworkString()
        ?: artwork.poster.toLegacyArtworkString()
        ?: return null
    return IdleTrailerScreensaverCandidate(
        itemId = contentId,
        itemType = itemType,
        addonBaseUrl = "",
        title = title,
        logoUrl = artwork.logo.toLegacyArtworkString(),
        backgroundUrl = imageUrl,
        fallbackArtworkUrls = listOf(imageUrl),
        genres = emptyList(),
        description = overview,
        releaseInfo = releaseInfo,
        runtime = null,
        imdbRating = rating?.value?.toFloat(),
        tomatoesRating = null,
        trailerYtIds = fallbackTrailerYtIds,
        stableIds = stableIds
    )
}
```

- [ ] **Step 8: Populate image slides and trailer candidates in `IdleScreensaverRepository`**

Replace `refreshFromResolvedSurface()` with:

```kotlin
    private suspend fun refreshFromResolvedSurface() {
        refreshMutex.withLock {
            val profileId = activeProfileId()
            val imageCandidates = screensaverCandidateRepository.observeImageCandidates(profileId).first()
            val trailerCandidates = screensaverCandidateRepository.observeTrailerCandidates(profileId).first()
            _slides.value = imageCandidates.mapNotNull { it.toIdleScreensaverSlide() }
            _trailerCandidates.value = trailerCandidates.mapNotNull { it.toIdleTrailerScreensaverCandidate() }
            Log.d(
                TAG,
                "Prepared ${_slides.value.size} idle screensaver slides and ${_trailerCandidates.value.size} trailer candidates from resolved display surface"
            )
        }
    }
```

- [ ] **Step 9: Allow lazy trailer resolution sentinel**

In `IdleTrailerScreensaverSession.kt`, add near top-level constants:

```kotlin
internal const val RESOLVE_TRAILER_BY_ITEM_SENTINEL = "__resolve_by_item__"
```

Change candidate trailer IDs in `resolveIdleTrailerPlaybackInOrder`:

```kotlin
        val trailerIds = candidate.trailerYtIds.takeIf { it.isNotEmpty() }
            ?: listOf(RESOLVE_TRAILER_BY_ITEM_SENTINEL)
        trailerIds.forEach { trailerId ->
```

- [ ] **Step 10: Run tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest --tests com.nexio.tv.data.repository.IdleScreensaverRepositoryTest --tests com.nexio.tv.ui.screensaver.IdleTrailerScreensaverSessionTest
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSession.kt app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt app/src/test/java/com/nexio/tv/data/repository/IdleScreensaverRepositoryTest.kt app/src/test/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSessionTest.kt
git commit -m "feat: support lazy trailer screensaver candidates"
```

---

### Task 7: Route Trailer Screensaver Playback Through TrailerService

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/MainActivity.kt`
- Modify: `app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt`

- [ ] **Step 1: Add direct-YouTube boundary test**

Append to `MainActivityIdleScreensaverTest.kt`:

```kotlin
    @Test
    fun `main activity screensaver path does not build direct youtube urls from trailer ids`() {
        val source = java.io.File("app/src/main/java/com/nexio/tv/MainActivity.kt").readText()
        assertFalse(
            "Screensaver trailer path must call TrailerService.resolveTrailer with item context, not build YouTube watch URLs directly.",
            source.contains("buildIdleTrailerYouTubeUrl(trailerId)")
        )
    }

    @Test
    fun `main activity screensaver trailer resolver passes stable ids`() {
        val source = java.io.File("app/src/main/java/com/nexio/tv/MainActivity.kt").readText()
        assertTrue(source.contains("tmdbId = candidate.stableIds.tmdb"))
        assertTrue(source.contains("contentId = candidate.trailerResolverContentId()"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.MainActivityIdleScreensaverTest
```

Expected: FAIL because `MainActivity.kt` still contains `buildIdleTrailerYouTubeUrl(trailerId)`.

- [ ] **Step 3: Replace session preparation resolver**

In `MainActivity.kt`, add this private helper near the other idle trailer helpers:

```kotlin
private fun IdleTrailerScreensaverCandidate.trailerResolverContentId(): String =
    stableIds.tvdb?.let { "tvdb:$it" }
        ?: stableIds.tmdb?.let { "tmdb:$it" }
        ?: stableIds.imdb?.let { "imdb:$it" }
        ?: stableIds.kitsu?.let { "kitsu:$it" }
        ?: itemId
```

Add import:

```kotlin
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverCandidate
```

Then replace the resolver block at the screensaver session preparation site with:

```kotlin
                            ) { candidate, trailerId ->
                                val fallbackIds = if (trailerId == RESOLVE_TRAILER_BY_ITEM_SENTINEL) {
                                    emptyList()
                                } else {
                                    listOf(trailerId)
                                }
                                when (
                                    val result = trailerService.resolveTrailer(
                                        title = candidate.title,
                                        year = extractIdleTrailerReleaseYear(candidate.releaseInfo),
                                        tmdbId = candidate.stableIds.tmdb,
                                        type = candidate.itemType,
                                        contentId = candidate.trailerResolverContentId(),
                                        fallbackYtIds = fallbackIds
                                    )
                                ) {
                                    is com.nexio.tv.data.trailer.TrailerResolutionResult.Playback -> result.source
                                    else -> null
                                }
                            }
```

Add import:

```kotlin
import com.nexio.tv.ui.screensaver.RESOLVE_TRAILER_BY_ITEM_SENTINEL
```

- [ ] **Step 4: Replace overlay resolver**

In `MainActivity.kt`, replace the `IdleTrailerScreensaverOverlay(resolvePlaybackSource = ...)` resolver block with the same `TrailerService.resolveTrailer(...)` block:

```kotlin
                                            resolvePlaybackSource = { candidate, trailerId ->
                                                val fallbackIds = if (trailerId == RESOLVE_TRAILER_BY_ITEM_SENTINEL) {
                                                    emptyList()
                                                } else {
                                                    listOf(trailerId)
                                                }
                                                when (
                                                    val result = trailerService.resolveTrailer(
                                                        title = candidate.title,
                                                        year = extractIdleTrailerReleaseYear(candidate.releaseInfo),
                                                        tmdbId = candidate.stableIds.tmdb,
                                                        type = candidate.itemType,
                                                        contentId = candidate.trailerResolverContentId(),
                                                        fallbackYtIds = fallbackIds
                                                    )
                                                ) {
                                                    is com.nexio.tv.data.trailer.TrailerResolutionResult.Playback -> result.source
                                                    else -> null
                                                }
                                            }
```

- [ ] **Step 5: Remove direct URL import**

Remove this import from `MainActivity.kt`:

```kotlin
import com.nexio.tv.ui.screensaver.buildIdleTrailerYouTubeUrl
```

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.MainActivityIdleScreensaverTest --tests com.nexio.tv.ui.screensaver.IdleTrailerScreensaverSessionTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/MainActivity.kt app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt
git commit -m "feat: resolve screensaver trailers through trailer service"
```

---

### Task 8: Add Screensaver Trace Events

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/TraceMetadataEventsTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt`

- [ ] **Step 1: Add failing trace event test**

Append to `TraceMetadataEventsTest.kt`:

```kotlin
    @Test
    fun `screensaver candidate events include shared surface source and parity fields`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "screensaver-session" })

        events.emitScreensaverCandidatePoolBuilt(
            profileHash = "profile-hash",
            source = "RESOLVED_DISPLAY_SURFACE",
            imageCandidateCount = 2,
            trailerCandidateCount = 1
        )
        events.emitScreensaverSlideSelected(
            itemKey = "movie:tmdb:550",
            source = "RESOLVED_DISPLAY_SURFACE",
            ratingSource = "IMDB",
            artworkSource = "TOP_POSTERS",
            matchesHomeSurface = true
        )

        assertEquals(
            listOf("screensaver.candidate_pool_built", "screensaver.slide_selected"),
            sink.events.map { it.eventType }
        )
        assertEquals("RESOLVED_DISPLAY_SURFACE", sink.events.first().payload["source"])
        assertEquals("profile-hash", sink.events.first().payload["profileHash"])
        assertEquals(true, sink.events.last().payload["matchesHomeSurface"])
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.trace.TraceMetadataEventsTest
```

Expected: FAIL with unresolved `emitScreensaverCandidatePoolBuilt` and `emitScreensaverSlideSelected`.

- [ ] **Step 3: Add trace emitters**

Add to `TraceMetadataEvents.kt` before `emitHomeHydrationEvent`:

```kotlin
    fun emitScreensaverCandidatePoolBuilt(
        profileHash: String?,
        source: String,
        imageCandidateCount: Int,
        trailerCandidateCount: Int
    ) {
        emitScreensaverEvent(
            eventType = "screensaver.candidate_pool_built",
            payload = mapOf(
                "profileHash" to optionalTraceValue(profileHash),
                "source" to source,
                "imageCandidateCount" to imageCandidateCount,
                "trailerCandidateCount" to trailerCandidateCount
            )
        )
    }

    fun emitScreensaverSlideSelected(
        itemKey: String,
        source: String,
        ratingSource: String?,
        artworkSource: String?,
        matchesHomeSurface: Boolean
    ) {
        emitScreensaverEvent(
            eventType = "screensaver.slide_selected",
            payload = mapOf(
                "itemKey" to itemKey,
                "source" to source,
                "ratingSource" to optionalTraceValue(ratingSource),
                "artworkSource" to optionalTraceValue(artworkSource),
                "matchesHomeSurface" to matchesHomeSurface
            )
        )
    }

    fun emitScreensaverTrailerCandidateSelected(
        itemKey: String,
        source: String,
        trailerSource: String,
        fallbackYouTubeIdsOnly: Boolean
    ) {
        emitScreensaverEvent(
            eventType = "screensaver.trailer_candidate_selected",
            payload = mapOf(
                "itemKey" to itemKey,
                "source" to source,
                "trailerSource" to trailerSource,
                "fallbackYouTubeIdsOnly" to fallbackYouTubeIdsOnly
            )
        )
    }

    private fun emitScreensaverEvent(
        eventType: String,
        payload: Map<String, Any?>
    ) {
        val sid = traceSessionIdForEmission()
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = eventType,
                payload = payload
            )
        )
    }
```

- [ ] **Step 4: Add logcat formatting cases**

Add these cases to `LogcatRuntimeTraceSink.kt`:

```kotlin
        "screensaver.candidate_pool_built" -> linkedMapOf(
            "profile" to payload["profileHash"],
            "source" to payload["source"],
            "imageCandidateCount" to payload["imageCandidateCount"],
            "trailerCandidateCount" to payload["trailerCandidateCount"]
        )
        "screensaver.slide_selected" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "source" to payload["source"],
            "ratingSource" to payload["ratingSource"],
            "artworkSource" to payload["artworkSource"],
            "matchesHomeSurface" to payload["matchesHomeSurface"]
        )
        "screensaver.trailer_candidate_selected" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "source" to payload["source"],
            "trailerSource" to payload["trailerSource"],
            "fallbackYouTubeIdsOnly" to payload["fallbackYouTubeIdsOnly"]
        )
```

- [ ] **Step 5: Emit candidate pool built from repository**

Inject `TraceMetadataEvents` into `ScreensaverCandidateRepository`:

```kotlin
class ScreensaverCandidateRepository(
    private val surfaceRepository: ResolvedDisplaySurfaceRepository,
    private val traceEvents: com.nexio.tv.core.trace.TraceMetadataEvents,
    private val profileHashForTrace: (Int) -> String?
) {
    @Inject
    constructor(
        surfaceRepository: ResolvedDisplaySurfaceRepository,
        traceEvents: com.nexio.tv.core.trace.TraceMetadataEvents,
        profileManager: com.nexio.tv.core.profile.ProfileManager
    ) : this(
        surfaceRepository = surfaceRepository,
        traceEvents = traceEvents,
        profileHashForTrace = { profileId ->
            val session = profileManager.activeProfileSession.value
            if (session.profileId == profileId) {
                com.nexio.tv.core.trace.TraceHash.of(session.sessionId, session.profileId.toString())
            } else {
                null
            }
        }
    )
}
```

Update tests to construct with a no-op trace:

```kotlin
private fun testTraceEvents() = com.nexio.tv.core.trace.TraceMetadataEvents(
    sink = com.nexio.tv.core.trace.NoopRuntimeTraceSink,
    sessionId = { null }
)

private fun testScreensaverCandidates(surface: ResolvedDisplaySurfaceRepository) =
    ScreensaverCandidateRepository(
        surfaceRepository = surface,
        traceEvents = testTraceEvents(),
        profileHashForTrace = { "test-profile-hash" }
    )
```

In `ScreensaverCandidateRepositoryTest.kt`, replace every constructor call shaped like:

```kotlin
val repository = ScreensaverCandidateRepository(surface)
```

with:

```kotlin
val repository = testScreensaverCandidates(surface)
```

Emit after projection in `observeImageCandidates`:

```kotlin
            val candidates = items.mapNotNull { item -> item.toImageCandidate() }
            traceEvents.emitScreensaverCandidatePoolBuilt(
                profileHash = profileHashForTrace(profileId),
                source = "RESOLVED_DISPLAY_SURFACE",
                imageCandidateCount = candidates.size,
                trailerCandidateCount = items.count { item -> !item.display.title.isNullOrBlank() }
            )
            candidates
```

- [ ] **Step 6: Run trace and candidate tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.trace.TraceMetadataEventsTest --tests com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest --tests com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt app/src/test/java/com/nexio/tv/core/trace/TraceMetadataEventsTest.kt app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt
git commit -m "feat: trace screensaver display surface usage"
```

---

### Task 9: Add Empty-Surface Placeholder Fallback

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/IdleScreensaverRepositoryTest.kt`

- [ ] **Step 1: Add failing empty-surface fallback test**

Append to `IdleScreensaverRepositoryTest.kt`:

```kotlin
    @Test
    fun `empty resolved surface uses placeholder slide without provider fallback pool`() = runBlocking {
        val screensaverCandidates = mockk<ScreensaverCandidateRepository>()
        every { screensaverCandidates.observeImageCandidates(profileId = 1) } returns flowOf(emptyList())
        every { screensaverCandidates.observeTrailerCandidates(profileId = 1) } returns flowOf(emptyList())

        val repository = IdleScreensaverRepository(
            screensaverCandidateRepository = screensaverCandidates,
            activeProfileId = { 1 }
        )

        repository.warmFromCache()

        assertEquals(1, repository.slides.value.size)
        assertEquals("__placeholder__", repository.slides.value.single().itemId)
        assertEquals("nexio-placeholder://backdrop", repository.slides.value.single().backgroundUrl)
        assertEquals(emptyList<IdleTrailerScreensaverCandidate>(), repository.trailerCandidates.value)
        verify(exactly = 1) { screensaverCandidates.observeImageCandidates(profileId = 1) }
        verify(exactly = 1) { screensaverCandidates.observeTrailerCandidates(profileId = 1) }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.IdleScreensaverRepositoryTest
```

Expected: FAIL because `IdleScreensaverRepository` leaves `_slides` empty.

- [ ] **Step 3: Add placeholder fallback constant**

In `IdleScreensaverRepository.kt`, add:

```kotlin
private val EMPTY_SURFACE_PLACEHOLDER_SLIDE = IdleScreensaverSlide(
    itemId = "__placeholder__",
    itemType = "placeholder",
    addonBaseUrl = "",
    title = "",
    backgroundUrl = "nexio-placeholder://backdrop",
    logoUrl = null,
    genres = emptyList(),
    description = null,
    releaseInfo = null,
    runtime = null,
    imdbRating = null,
    tomatoesRating = null,
    modeData = IdleScreensaverModeData(
        image = IdleScreensaverImageModeData(fallbackArtworkUrls = listOf("nexio-placeholder://backdrop"))
    )
)
```

- [ ] **Step 4: Apply placeholder only when the shared surface has no candidates**

Update `refreshFromResolvedSurface()`:

```kotlin
            val mappedSlides = imageCandidates.mapNotNull { it.toIdleScreensaverSlide() }
            _slides.value = mappedSlides.ifEmpty { listOf(EMPTY_SURFACE_PLACEHOLDER_SLIDE) }
            _trailerCandidates.value = trailerCandidates.mapNotNull { it.toIdleTrailerScreensaverCandidate() }
```

This is the only cold-boot fallback in this plan. Do not restore `fetchScreensaverCatalog`, `getCatalogCachedFirst`, Trakt/Cinemeta fallback pools, MDBList enrichment, or direct provider calls.

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.IdleScreensaverRepositoryTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt app/src/test/java/com/nexio/tv/data/repository/IdleScreensaverRepositoryTest.kt
git commit -m "feat: add screensaver empty surface placeholder"
```

---

### Task 10: Add Boundary Tests Against Parallel Screensaver Pipelines

**Files:**
- Create: `app/src/test/java/com/nexio/tv/architecture/ScreensaverSurfaceBoundaryTest.kt`

- [ ] **Step 1: Create failing/guarding boundary test**

Create `app/src/test/java/com/nexio/tv/architecture/ScreensaverSurfaceBoundaryTest.kt`:

```kotlin
package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScreensaverSurfaceBoundaryTest {
    @Test
    fun `idle screensaver repository does not call provider specific metadata or rating enrichment`() {
        val source = sourceOf("app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt")

        val banned = listOf(
            "mdbListRepository.enrichPreview",
            "metadataRouterFacade.resolveRequest",
            "refreshCatalogToDisk",
            "getCatalogCachedFirst",
            "readCachedMeta"
        )
        val bannedImports = listOf(
            "import com.nexio.tv.data.local.TraktDiscoverySnapshotStore",
            "import com.nexio.tv.data.local.TraktSettingsDataStore",
            "import com.nexio.tv.domain.repository.AddonRepository",
            "import com.nexio.tv.domain.repository.CatalogRepository",
            "import com.nexio.tv.domain.repository.MetaRepository"
        )

        assertEquals(emptyList<String>(), banned.filter(source::contains))
        assertEquals(emptyList<String>(), bannedImports.filter(source::contains))
    }

    @Test
    fun `main activity screensaver path does not construct youtube watch urls directly`() {
        val source = sourceOf("app/src/main/java/com/nexio/tv/MainActivity.kt")
        assertFalse(source.contains("buildIdleTrailerYouTubeUrl(trailerId)"))
        assertFalse(source.contains("\"https://www.youtube.com/watch?v=\""))
    }

    @Test
    fun `screensaver preparation does not call artwork router or rating repositories`() {
        val source = sourceOf("app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt")
        val banned = listOf(
            "ArtworkRouter",
            "ArtworkAssetRepository",
            "MDBListRepository",
            "TitleRatingOverrideRepository",
            "MetadataRouterFacade"
        )

        assertEquals(emptyList<String>(), banned.filter(source::contains))
    }

    private fun sourceOf(path: String): String {
        val file = File(path)
        require(file.exists()) { "Missing source file: $path" }
        return file.readText()
    }
}
```

- [ ] **Step 2: Run test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.architecture.ScreensaverSurfaceBoundaryTest
```

Expected after Tasks 5-9: PASS. Remove the direct dependency or direct string construction reported by any failure.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/architecture/ScreensaverSurfaceBoundaryTest.kt
git commit -m "test: guard screensaver display surface boundary"
```

---

### Task 11: Full Verification and RCA Linkback

**Files:**
- Modify: `review-dossier/android-modern-home-screensaver-metadata-artwork-trailer-rca.md`

- [ ] **Step 1: Add implementation note to RCA**

Append this section to `review-dossier/android-modern-home-screensaver-metadata-artwork-trailer-rca.md`:

```markdown
## Implementation Plan

The implementation plan for the architectural fix is stored at:

`docs/superpowers/plans/2026-05-05-screensaver-display-surface-parity.md`

The plan preserves the RCA conclusion: screensaver becomes a consumer of the shared resolved display surface and does not add a second metadata/artwork/rating/trailer pipeline.
```

- [ ] **Step 2: Run focused verification suite**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepositoryTest \
  --tests com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest \
  --tests com.nexio.tv.data.repository.IdleScreensaverRepositoryTest \
  --tests com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest \
  --tests com.nexio.tv.ui.screensaver.IdleTrailerScreensaverSessionTest \
  --tests com.nexio.tv.MainActivityIdleScreensaverTest \
  --tests com.nexio.tv.architecture.ScreensaverSurfaceBoundaryTest \
  --tests com.nexio.tv.core.trace.TraceMetadataEventsTest \
  --tests com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest
```

Expected: PASS.

- [ ] **Step 3: Run broader home/screensaver regression tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest \
  --tests com.nexio.tv.ui.screens.home.HomeHydrationCoordinatorTest \
  --tests com.nexio.tv.data.local.HydratedHomeOverlayStoreTest \
  --tests com.nexio.tv.data.repository.TitleRatingOverrideRepositoryTest
```

Expected: PASS.

- [ ] **Step 4: Check for banned source patterns**

Run:

```bash
rg -n "mdbListRepository\\.enrichPreview|metadataRouterFacade\\.resolveRequest|refreshCatalogToDisk|getCatalogCachedFirst|readCachedMeta|buildIdleTrailerYouTubeUrl\\(trailerId\\)|https://www\\.youtube\\.com/watch\\?v=" app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt app/src/main/java/com/nexio/tv/MainActivity.kt
```

Expected: no matches.

- [ ] **Step 5: Commit**

```bash
git add review-dossier/android-modern-home-screensaver-metadata-artwork-trailer-rca.md
git commit -m "docs: link screensaver RCA to implementation plan"
```

---

## Self-Review

Spec coverage:

- Shared display surface: Tasks 1-3.
- Image screensaver reads shared surface: Tasks 4-5.
- Ratings parity and no direct MDBList path: Tasks 2, 5, 10.
- Trailer screensaver uses shared trailer resolution instead of pre-existing IDs only: Tasks 6-7.
- Empty-surface behavior without provider fallback: Task 9.
- Boundary tests preventing parallel pipelines: Task 10.
- Trace events: Task 8.
- RCA linkback and verification: Task 11.

Placeholder scan:

- No placeholder tokens or open-ended "add tests" steps remain.
- Every code-changing step includes concrete code or an exact replacement pattern.

Type consistency:

- `ResolvedDisplayItem`, `ResolvedDisplayFields`, `TrailerDisplayState`, and `HydrationState` are defined in Task 1 and reused consistently.
- Existing `TitleRating` and `HydratedHomeFieldTrace` are reused instead of introducing new rating or trace model semantics.
- `ScreensaverSlideCandidate` and `ScreensaverTrailerCandidate` are defined in Tasks 4 and 6 before use.
- `RESOLVE_TRAILER_BY_ITEM_SENTINEL` is defined in Task 6 before MainActivity uses it in Task 7.
