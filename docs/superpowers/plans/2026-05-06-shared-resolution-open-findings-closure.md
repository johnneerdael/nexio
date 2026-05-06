# Shared Resolution Open Findings Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every open shared-resolution bypass finding after the P0 packet by routing production metadata, identity, rating, trailer, skip, artwork, localization, and screensaver display paths through the canonical shared owner systems.

**Architecture:** Keep the P0 profile/account work as the baseline. Migrate remaining production paths in packets: first pin architecture gates, then move detail/home metadata to resolved documents/surfaces, then centralize identity, ratings, trailers, skip segments, artwork typed refs, localization, and final audit reporting. Do not add one-off ViewModel fixes; ViewModels may orchestrate state and user actions but may not decide provider field ownership.

**Tech Stack:** Kotlin, Android, Jetpack Compose, Coroutines/Flow, Hilt, Gradle, JUnit4, MockK, repository-local static architecture tests.

---

## Scope And Baseline

This plan starts from branch `shared-resolution-p0-bypass-removal`, which already contains:

```text
29f14057a test: pin P0 shared resolution bypass guards
b662af079 fix: scope provider mutation outbox by account
5d5a75f1f fix: require profile scope for watch progress
```

The completed P0 packet is not reimplemented here. This plan covers every remaining open finding called out by the refreshed audit:

```text
1. Detail/home metadata sidecars and trace-only facade discard paths.
2. UI and home identity bridge helpers outside stable ID ownership.
3. Detail/home direct rating ownership.
4. Detail/home/screensaver direct trailer ownership.
5. Player direct skip provider arbitration.
6. Raw artwork URL/string surfaces reaching metadata UI and Coil.
7. Manual localized field fallback outside localization policy.
8. Screensaver legacy string candidate compatibility.
9. Audit report rows and architecture gates.
```

## File Structure

Create:

```text
app/src/main/java/com/nexio/tv/domain/model/ResolvedDetailDisplayDocument.kt
app/src/main/java/com/nexio/tv/data/repository/MetadataDisplayRepository.kt
app/src/main/java/com/nexio/tv/core/metadata/router/resolver/RatingResolver.kt
app/src/main/java/com/nexio/tv/core/metadata/router/resolver/SkipSegmentResolver.kt
app/src/test/java/com/nexio/tv/architecture/SharedResolutionOpenFindingsArchitectureTest.kt
review-dossier/shared-resolution-bypass-audit.md
review-dossier/shared-resolution-bypass-audit.csv
```

Modify:

```text
app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt
app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt
app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanRunner.kt
app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanStep.kt
app/src/main/java/com/nexio/tv/core/metadata/router/ResolverOrchestrator.kt
app/src/main/java/com/nexio/tv/core/metadata/router/resolver/TrailerResolver.kt
app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataSecondaryRepository.kt
app/src/main/java/com/nexio/tv/data/integration/metadata/LocalizationResolver.kt
app/src/main/java/com/nexio/tv/data/repository/EpisodeRatingsSelectionRepository.kt
app/src/main/java/com/nexio/tv/data/repository/TitleRatingOverrideRepository.kt
app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt
app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt
app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt
app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt
app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt
app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt
app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt
app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt
app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt
app/src/main/java/com/nexio/tv/ui/components/GridContentCard.kt
app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt
app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt
app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodeRatingModels.kt
app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt
app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt
app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt
app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt
app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt
app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt
app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt
app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt
app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt
app/src/main/java/com/nexio/tv/ui/screens/search/SearchScreen.kt
app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt
app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSession.kt
app/src/main/java/com/nexio/tv/MainActivity.kt
```

Update tests that currently encode the old bypasses:

```text
app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeFetchTmdbEnrichmentTest.kt
app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeFetchReviewsTest.kt
app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeFetchRecommendationsTest.kt
app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelTestFactory.kt
app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsSeasonMediaViewModelTest.kt
app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorTest.kt
app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt
app/src/test/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSessionTest.kt
app/src/test/java/com/nexio/tv/architecture/MetadataRouterBoundaryTest.kt
app/src/test/java/com/nexio/tv/architecture/SkipIntroRepositoryCanonicalSurfaceTest.kt
app/src/test/java/com/nexio/tv/architecture/RawRemoteArtworkUrlBoundaryTest.kt
```

---

### Task 1: Pin Every Remaining Open Bypass With Architecture Tests

**Files:**
- Create: `app/src/test/java/com/nexio/tv/architecture/SharedResolutionOpenFindingsArchitectureTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/MetadataRouterBoundaryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/SkipIntroRepositoryCanonicalSurfaceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/RawRemoteArtworkUrlBoundaryTest.kt`

- [ ] **Step 1: Create the failing open-findings architecture test**

Create `app/src/test/java/com/nexio/tv/architecture/SharedResolutionOpenFindingsArchitectureTest.kt`:

```kotlin
package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedResolutionOpenFindingsArchitectureTest {
    private val mainRoot = File("app/src/main/java/com/nexio/tv")

    @Test
    fun `detail view model does not own provider metadata ratings trailers or identity decisions`() {
        val source = File(mainRoot, "ui/screens/detail/MetaDetailsViewModel.kt").readText()
        val forbidden = listOf(
            "metadataSecondaryRepository.",
            "mdbListRepository.",
            "titleRatingOverrideRepository.",
            "episodeRatingsSelectionRepository.",
            "trailerService.",
            "tmdbService.ensureTmdbId",
            ".ensureTmdbId("
        )

        val offenders = forbidden.filter { source.contains(it) }

        assertTrue(
            "MetaDetailsViewModel must consume ResolvedDetailDisplayDocument and resolver outputs, not direct provider sidecars: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `metadata router facade does not perform trace only resolve then sidecar fetch`() {
        val source = File(mainRoot, "core/metadata/router/MetadataRouterFacade.kt").readText()

        val forbidden = listOf(
            "resolveRequest(metadataRequest)\n        return repo.fetchTmdbEnrichment",
            "resolveRequest(metadataRequest)\n        return service.resolveTrailer",
            "requires MetadataRouterFacade to be constructed with a non-null MetadataSecondaryRepository",
            "requires MetadataRouterFacade to be constructed with a non-null TrailerService"
        )

        val offenders = forbidden.filter { source.contains(it) }

        assertTrue(
            "MetadataRouterFacade must return resolver-owned outputs instead of trace-only discard sidecars: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `home hydration and presentation do not call rating or trailer sidecars`() {
        val files = listOf(
            "ui/screens/home/HomeHydrationCoordinator.kt",
            "ui/screens/home/HomeCatalogRefreshCoordinator.kt",
            "ui/screens/home/HomeViewModelPresentationPipeline.kt",
            "ui/screens/home/HomeViewModel.kt"
        )
        val forbidden = listOf(
            "TitleRatingOverrideRepository",
            "titleRatingOverrideRepository.",
            "TrailerService",
            "trailerService.",
            "getTitleMediaAvailability("
        )

        val offenders = files.flatMap { path ->
            val source = File(mainRoot, path).readText()
            forbidden.filter { source.contains(it) }.map { "$path contains $it" }
        }

        assertTrue(
            "Home must publish resolver-owned display/rating/trailer through ResolvedDisplaySurfaceRepository: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `ui and main activity do not call tmdb identity bridge helpers`() {
        val roots = listOf(File(mainRoot, "ui"), File(mainRoot, "MainActivity.kt"))
        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.invariantSeparatorsPath.contains("/test/") }
                .flatMap { file ->
                    val source = file.readText()
                    Regex("""(?:tmdbService\.)?ensureTmdbId\s*\(""").findAll(source)
                        .map { "${file.invariantSeparatorsPath}:${lineNumber(source, it.range.first)}" }
                }
                .toList()
        }

        assertTrue(
            "UI/MainActivity must use StableIdBundleResolver or resolved display identities, not TmdbService.ensureTmdbId: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `player does not call SkipIntroRepository directly`() {
        val files = listOf(
            "ui/screens/player/PlayerViewModel.kt",
            "ui/screens/player/PlayerRuntimeController.kt",
            "ui/screens/player/PlayerRuntimeControllerObservers.kt",
            "ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt"
        )
        val offenders = files.filter { path ->
            File(mainRoot, path).readText().contains("SkipIntroRepository")
        }

        assertTrue(
            "Player must request skip data through SkipSegmentResolver, not SkipIntroRepository: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `screensaver models do not expose raw trailer or artwork string compatibility fields`() {
        val source = File(mainRoot, "ui/screensaver/IdleScreensaverModels.kt").readText()
        val forbidden = listOf(
            "backgroundUrl: String",
            "logoUrl: String",
            "trailerYtIds: List<String>"
        )

        val offenders = forbidden.filter { source.contains(it) }

        assertTrue(
            "Screensaver candidates must expose typed artwork refs and trailer refs, not legacy strings: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `metadata ui coil calls do not use raw remote artwork string fields`() {
        val files = listOf(
            "ui/components/ContentCard.kt",
            "ui/components/GridContentCard.kt",
            "ui/screens/home/ModernHomeRows.kt",
            "ui/screens/home/HomeScreen.kt",
            "ui/screens/search/SearchScreen.kt",
            "ui/screens/detail/MetaDetailsScreen.kt",
            "ui/screens/detail/HeroSection.kt",
            "ui/screens/detail/EpisodesSection.kt"
        )
        val rawRemoteFieldPattern = Regex("""\.data\s*\(\s*(?:posterUrl|imageUrl|logoUrl|backdropUrl|displayPoster|displayBackground|displayThumbnail|item\.imageUrl|url)\s*\)""")
        val offenders = files.flatMap { path ->
            val source = File(mainRoot, path).readText()
            rawRemoteFieldPattern.findAll(source)
                .map { "$path:${lineNumber(source, it.range.first)} ${it.value.trim()}" }
        }

        assertTrue(
            "Metadata UI Coil data must be ArtworkDisplayRef/nexio-artwork/local/resource, not raw provider URL fields: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `detail code does not perform cross provider localization fallback`() {
        val source = File(mainRoot, "ui/screens/detail/MetaDetailsViewModel.kt").readText()
        val forbidden = listOf(
            "tvEnrichment ?: tmdbEnrichment",
            "tmdbEnrichment ?: tvEnrichment",
            "tvDescription ?: tmdbDescription",
            "tmdbDescription ?: tvDescription",
            "tvOverview ?: tmdbOverview",
            "tmdbOverview ?: tvOverview"
        )

        val offenders = forbidden.filter { source.contains(it) }

        assertTrue(
            "Localized field fallback must be selected by LocalizationResolver/FieldResolver, not detail ViewModel: $offenders",
            offenders.isEmpty()
        )
    }

    private fun lineNumber(source: String, offset: Int): Int =
        source.substring(0, offset).count { it == '\n' } + 1
}
```

- [ ] **Step 2: Tighten existing boundary tests**

Modify `app/src/test/java/com/nexio/tv/architecture/MetadataRouterBoundaryTest.kt` by removing production allowlist entries for these sidecar owners:

```kotlin
private val approvedMetadataBoundarySuffixes = setOf(
    "/com/nexio/tv/data/integration/metadata/",
    "/com/nexio/tv/core/metadata/router/",
    "/com/nexio/tv/core/tmdb/TmdbMetadataService.kt",
    "/com/nexio/tv/core/tvdb/TvdbMetadataService.kt",
    "/com/nexio/tv/core/kitsu/KitsuMetadataService.kt"
)
```

The final allowlist must not include:

```text
/com/nexio/tv/data/repository/EpisodeRatingsSelectionRepository.kt
/com/nexio/tv/data/trailer/TrailerService.kt
/com/nexio/tv/data/integration/metadata/MetadataSecondaryRepository.kt
```

Modify `app/src/test/java/com/nexio/tv/architecture/SkipIntroRepositoryCanonicalSurfaceTest.kt` so the assertion message says:

```kotlin
"Skip-segment APIs must be invoked only by IntegrationRuntime-backed skip providers under SkipSegmentResolver. Player code must use SkipSegmentResolver.resolveSkipSegments(request)."
```

Modify `app/src/test/java/com/nexio/tv/architecture/RawRemoteArtworkUrlBoundaryTest.kt` to include the metadata UI files named in the new `metadata ui coil calls` test.

- [ ] **Step 3: Run architecture tests and verify they fail**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest" \
  --tests "com.nexio.tv.architecture.MetadataRouterBoundaryTest" \
  --tests "com.nexio.tv.architecture.SkipIntroRepositoryCanonicalSurfaceTest" \
  --tests "com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest"
```

Expected: FAIL. The failure list must include detail sidecars, home rating/trailer sidecars, remaining `ensureTmdbId`, player `SkipIntroRepository`, screensaver string fields, and raw artwork UI fields.

- [ ] **Step 4: Commit the failing architecture pins**

Run:

```bash
git add app/src/test/java/com/nexio/tv/architecture
git commit -m "test: pin open shared resolution bypasses"
```

Expected: commit succeeds with only architecture-test changes.

---

### Task 2: Create Resolved Detail Display Document And Repository Entry Point

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/ResolvedDetailDisplayDocument.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/MetadataDisplayRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/MetadataDisplayRepositoryTest.kt`

- [ ] **Step 1: Add the resolved detail model**

Create `app/src/main/java/com/nexio/tv/domain/model/ResolvedDetailDisplayDocument.kt`:

```kotlin
package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataRoute

@Immutable
data class ResolvedDetailDisplayDocument(
    val route: MetadataRoute?,
    val identity: ContentIdentity,
    val fields: ResolvedDisplayFields,
    val artwork: ArtworkBundle,
    val rating: TitleRating?,
    val trailer: TrailerDisplayState,
    val seasons: List<SeasonDisplay>,
    val people: PeopleDisplay?,
    val reviews: List<MetaReview>,
    val recommendations: List<MetaPreview>,
    val collection: List<MetaPreview>,
    val sourceTrace: List<HydratedHomeFieldTrace>,
    val localization: LocalizationDisplayState
)

@Immutable
data class ContentIdentity(
    val canonicalProvider: ProviderId?,
    val canonicalId: String?,
    val providerIds: ProviderIds
)

@Immutable
data class SeasonDisplay(
    val seasonNumber: Int,
    val title: String?,
    val overview: String?,
    val episodes: List<SeasonEpisodeMark>
)

@Immutable
data class PeopleDisplay(
    val cast: List<MetaPerson>,
    val crew: List<MetaPerson>
)

@Immutable
data class LocalizationDisplayState(
    val requestedLanguage: String?,
    val selectedLanguage: String?,
    val fallbackReason: String?
)
```

If `MetaPerson` is not a current domain type, use the existing people row type already used by `MetaDetailsUiState`; do not create a second person DTO with the same fields.

- [ ] **Step 2: Add a repository test for resolved detail output**

Create `app/src/test/java/com/nexio/tv/data/repository/MetadataDisplayRepositoryTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataDisplayRepositoryTest {
    @Test
    fun `observe detail display maps resolved document into one detail document`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        coEvery { facade.resolveRequest(any()) } returns MetadataResolutionResult(
            route = null,
            plan = null,
            resolverSchedule = mockk(relaxed = true),
            resolvedDocument = ResolvedMetadataDocument(
                canonicalId = "TMDB:1399",
                title = "Game of Thrones",
                overview = "Nine noble families fight for control.",
                poster = "nexio-artwork://decision/tmdb/1399/poster",
                backdrop = "nexio-artwork://decision/tmdb/1399/backdrop",
                logo = null,
                rating = 8.4,
                runtimeMinutes = 55,
                fieldOwners = emptyMap(),
                ignoredOverwrites = emptyList()
            ),
            displayMetadata = mockk(relaxed = true),
            trace = emptyList()
        )

        val repository = MetadataDisplayRepository(facade)
        val document = repository.resolveDetailDisplay(
            MetadataRequest(
                contentId = "series:tmdb:1399",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(itemType = "series"),
                language = "en-US",
                depth = MetadataDepth.DETAIL_FULL
            )
        )

        assertEquals("Game of Thrones", document.fields.title)
        assertEquals("TMDB", document.identity.canonicalProvider?.name)
        assertEquals("1399", document.identity.canonicalId)
        assertTrue(document.trailer.fallbackTrailerYtIds.isEmpty())
    }
}
```

- [ ] **Step 3: Implement the repository**

Create `app/src/main/java/com/nexio/tv/data/repository/MetadataDisplayRepository.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.LocalizationDisplayState
import com.nexio.tv.domain.model.PeopleDisplay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDetailDisplayDocument
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.SeasonDisplay
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TrailerDisplayState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataDisplayRepository @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade
) {
    suspend fun resolveDetailDisplay(request: MetadataRequest): ResolvedDetailDisplayDocument {
        return metadataRouterFacade.resolveRequest(request).toResolvedDetailDisplayDocument(request.language)
    }

    private fun MetadataResolutionResult.toResolvedDetailDisplayDocument(
        requestedLanguage: String?
    ): ResolvedDetailDisplayDocument {
        val canonical = resolvedDocument.canonicalId.toContentIdentity()
        return ResolvedDetailDisplayDocument(
            route = route,
            identity = canonical,
            fields = ResolvedDisplayFields(
                title = resolvedDocument.title,
                originalTitle = null,
                year = null,
                releaseDate = null,
                overview = resolvedDocument.overview,
                genres = emptyList(),
                runtimeText = resolvedDocument.runtimeMinutes?.let { "$it min" }
            ),
            artwork = displayMetadata.artwork ?: ArtworkBundle(),
            rating = resolvedDocument.rating?.let { TitleRating(value = it, source = "resolved") },
            trailer = TrailerDisplayState(),
            seasons = emptyList(),
            people = PeopleDisplay(cast = emptyList(), crew = emptyList()),
            reviews = emptyList(),
            recommendations = emptyList(),
            collection = emptyList(),
            sourceTrace = resolvedDocument.sourceRoles.map { (field, role) ->
                com.nexio.tv.domain.model.HydratedHomeFieldTrace(
                    field = field.name,
                    selectedProvider = resolvedDocument.sourceProviders[field].orEmpty(),
                    sourceRole = role.name
                )
            },
            localization = LocalizationDisplayState(
                requestedLanguage = requestedLanguage,
                selectedLanguage = requestedLanguage,
                fallbackReason = null
            )
        )
    }

    private fun String?.toContentIdentity(): ContentIdentity {
        val providerName = this?.substringBefore(':', missingDelimiterValue = "").orEmpty()
        val provider = ProviderId.entries.firstOrNull { it.name.equals(providerName, ignoreCase = true) }
        val id = this?.substringAfter(':', missingDelimiterValue = "").takeUnless { it.isNullOrBlank() }
        return ContentIdentity(
            canonicalProvider = provider,
            canonicalId = id,
            providerIds = ProviderIds(
                tmdb = id.takeIf { provider == ProviderId.TMDB },
                tvdb = id.takeIf { provider == ProviderId.TVDB },
                kitsu = id.takeIf { provider == ProviderId.KITSU }
            )
        )
    }
}
```

- [ ] **Step 4: Run the repository test**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.repository.MetadataDisplayRepositoryTest"
```

Expected: PASS.

- [ ] **Step 5: Commit the resolved detail document entry point**

Run:

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ResolvedDetailDisplayDocument.kt \
  app/src/main/java/com/nexio/tv/data/repository/MetadataDisplayRepository.kt \
  app/src/test/java/com/nexio/tv/data/repository/MetadataDisplayRepositoryTest.kt
git commit -m "feat: add resolved detail display document"
```

Expected: commit succeeds.

---

### Task 3: Move Secondary Metadata Into Provider Plan Ownership

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanStep.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanRunner.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataSecondaryRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/ProviderPlanSecondaryStepsTest.kt`

- [ ] **Step 1: Add provider-plan secondary step tests**

Create `app/src/test/java/com/nexio/tv/core/metadata/router/ProviderPlanSecondaryStepsTest.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPlanSecondaryStepsTest {
    @Test
    fun `detail full plan includes secondary detail reviews recommendations collection and people steps`() {
        val executor = ProviderPlanExecutor()
        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tmdb:1399",
            targetIds = emptyMap()
        )

        val plan = executor.buildPlan(route = route, depth = MetadataDepth.DETAIL_FULL)
        val stepNames = plan.steps.map { it::class.simpleName.orEmpty() }.toSet()

        assertTrue(stepNames.contains("PrimaryCore"))
        assertTrue(stepNames.contains("SecondaryDetail"))
        assertTrue(stepNames.contains("Reviews"))
        assertTrue(stepNames.contains("Recommendations"))
        assertTrue(stepNames.contains("Collection"))
        assertTrue(stepNames.contains("PersonOrganization"))
    }
}
```

- [ ] **Step 2: Add explicit provider-plan step types**

Modify `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanStep.kt` so the sealed interface contains these concrete types:

```kotlin
sealed interface ProviderPlanStep {
    val provider: MetadataPrimaryProvider
    val contentId: String

    data class PrimaryCore(
        override val provider: MetadataPrimaryProvider,
        override val contentId: String
    ) : ProviderPlanStep

    data class SecondaryDetail(
        override val provider: MetadataPrimaryProvider,
        override val contentId: String
    ) : ProviderPlanStep

    data class Reviews(
        override val provider: MetadataPrimaryProvider,
        override val contentId: String
    ) : ProviderPlanStep

    data class Recommendations(
        override val provider: MetadataPrimaryProvider,
        override val contentId: String
    ) : ProviderPlanStep

    data class Collection(
        override val provider: MetadataPrimaryProvider,
        override val contentId: String
    ) : ProviderPlanStep

    data class AdvancedAnime(
        override val provider: MetadataPrimaryProvider,
        override val contentId: String
    ) : ProviderPlanStep

    data class PersonOrganization(
        override val provider: MetadataPrimaryProvider,
        override val contentId: String
    ) : ProviderPlanStep
}
```

- [ ] **Step 3: Make `DETAIL_FULL` build the secondary plan**

Modify `ProviderPlanExecutor.buildPlan(route = route, depth = depth)` so `MetadataDepth.DETAIL_FULL` returns a plan whose steps are:

```kotlin
listOf(
    ProviderPlanStep.PrimaryCore(route.provider, route.parentId),
    ProviderPlanStep.SecondaryDetail(route.provider, route.parentId),
    ProviderPlanStep.Reviews(route.provider, route.parentId),
    ProviderPlanStep.Recommendations(route.provider, route.parentId),
    ProviderPlanStep.Collection(route.provider, route.parentId),
    ProviderPlanStep.PersonOrganization(route.provider, route.parentId)
)
```

For Kitsu/anime routes, append:

```kotlin
ProviderPlanStep.AdvancedAnime(route.provider, route.parentId)
```

when `route.provider == MetadataPrimaryProvider.KITSU`.

- [ ] **Step 4: Move facade sidecar fetches behind provider-plan output**

Modify `MetadataRouterFacade` so these methods do not call `resolveRequest(metadataRequest)` and then directly delegate to `MetadataSecondaryRepository` or `TrailerService`:

```kotlin
fetchTmdbEnrichment(metadataRequest, tmdbId, contentType)
fetchReviewsPage(metadataRequest, tmdbId, contentType, page)
fetchRecommendations(metadataRequest, tmdbId, contentType)
fetchTrailer(metadataRequest, title, year, tmdbId, type, seasonNumber, contentId, fallbackYtIds)
findPersonIdByExactName(metadataRequest, name)
findCompanyIdByExactName(metadataRequest, name)
fetchPersonDetail(metadataRequest, personId)
```

Each method must read from `providerRunResult` candidates produced by `ProviderPlanRunner`. Where a legacy return type is still required, convert the resolved candidate to the legacy DTO inside `MetadataRouterFacade` using a private mapper named for the compatibility shape:

```kotlin
private fun MetadataCandidate.toLegacyTmdbEnrichmentOrNull(): TmdbEnrichment?
private fun MetadataCandidate.toLegacyReviewsOrEmpty(): List<MetaReview>
private fun MetadataCandidate.toLegacyRecommendationsOrEmpty(): List<MetaPreview>
private fun MetadataCandidate.toLegacyPersonDetailOrNull(): PersonDetail?
```

The mappers are temporary compatibility projections. They must not perform network calls.

- [ ] **Step 5: Restrict `MetadataSecondaryRepository` to adapter package use**

Modify `MetadataSecondaryRepository` class KDoc:

```kotlin
/**
 * Low-level TMDB secondary adapter used by ProviderPlanRunner adapters only.
 *
 * Production UI, ViewModel, home, screensaver, player, and facade code must not inject this class.
 * Provider-plan adapters may call it while executing IntegrationRuntime-governed provider steps.
 */
```

Do not inject `MetadataSecondaryRepository` into `MetaDetailsViewModel` or `MetadataRouterFacade` after this task.

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests "com.nexio.tv.core.metadata.router.ProviderPlanSecondaryStepsTest" \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest.metadata router facade does not perform trace only resolve then sidecar fetch"
```

Expected: PASS for these two tests.

- [ ] **Step 7: Commit provider-plan secondary ownership**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router \
  app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataSecondaryRepository.kt \
  app/src/test/java/com/nexio/tv/core/metadata/router/ProviderPlanSecondaryStepsTest.kt \
  app/src/test/java/com/nexio/tv/architecture/SharedResolutionOpenFindingsArchitectureTest.kt
git commit -m "feat: route secondary metadata through provider plans"
```

Expected: commit succeeds.

---

### Task 4: Make Detail Consume One Resolved Document

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelTestFactory.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsResolvedDocumentTest.kt`

- [ ] **Step 1: Write the detail resolved-document test**

Create `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsResolvedDocumentTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.detail

import com.nexio.tv.data.repository.MetadataDisplayRepository
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.LocalizationDisplayState
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDetailDisplayDocument
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.TrailerDisplayState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaDetailsResolvedDocumentTest {
    @Test
    fun `detail view model applies title overview rating trailer and source trace from resolved detail document`() = runTest {
        val displayRepository = mockk<MetadataDisplayRepository>()
        coEvery { displayRepository.resolveDetailDisplay(any()) } returns ResolvedDetailDisplayDocument(
            route = null,
            identity = ContentIdentity(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "1399",
                providerIds = ProviderIds(tmdb = "1399", imdb = "tt0944947")
            ),
            fields = ResolvedDisplayFields(
                title = "Resolved Title",
                originalTitle = null,
                year = 2011,
                releaseDate = "2011-04-17",
                overview = "Resolved Overview",
                genres = listOf("Drama"),
                runtimeText = "55 min"
            ),
            artwork = com.nexio.tv.core.artwork.ArtworkBundle(),
            rating = com.nexio.tv.domain.model.TitleRating(value = 8.4, source = "RatingResolver"),
            trailer = TrailerDisplayState(fallbackTrailerYtIds = listOf("abc123def45"), resolverSource = "TrailerResolver"),
            seasons = emptyList(),
            people = null,
            reviews = emptyList(),
            recommendations = emptyList(),
            collection = emptyList(),
            sourceTrace = emptyList(),
            localization = LocalizationDisplayState("nl-NL", "en-US", "same-provider-english")
        )

        val viewModel = MetaDetailsViewModelTestFactory.create(
            metadataDisplayRepository = displayRepository
        )

        viewModel.loadMetadataForTest()

        val state = viewModel.uiState.value
        assertEquals("Resolved Title", state.meta?.name)
        assertEquals("Resolved Overview", state.meta?.description)
        assertEquals("abc123def45", state.trailerState.fallbackTrailerYtIds.single())
        assertEquals("same-provider-english", state.localizationFallbackReason)
    }
}
```

If `loadMetadataForTest()` is not present, add a test-only wrapper in the test factory that invokes the same public load trigger used by existing detail tests.

- [ ] **Step 2: Inject `MetadataDisplayRepository` into detail ViewModel**

Modify `MetaDetailsViewModel` constructor:

```kotlin
private val metadataDisplayRepository: MetadataDisplayRepository,
```

Remove constructor parameters:

```kotlin
private val metadataSecondaryRepository: MetadataSecondaryRepository,
private val mdbListRepository: MDBListRepository,
private val titleRatingOverrideRepository: TitleRatingOverrideRepository,
private val episodeRatingsSelectionRepository: EpisodeRatingsSelectionRepository,
private val trailerService: TrailerService,
```

Keep `metadataRouterFacade` only if non-display user actions still need it for approved provider-plan operations. No detail display field may be selected from direct facade sidecars.

- [ ] **Step 3: Replace `enrichMeta` field merging with resolved document application**

In `MetaDetailsViewModel`, replace the body section that selects title, overview, genres, artwork, release info, rating, language, trailer IDs, seasons, people, reviews, recommendations, and collection from `tmdbEnrichment`, `tvEnrichment`, and `kitsu` sidecars with:

```kotlin
private suspend fun loadResolvedDetailDocument(meta: Meta): ResolvedDetailDisplayDocument {
    return metadataDisplayRepository.resolveDetailDisplay(
        MetadataRequest(
            contentId = meta.id,
            contentType = meta.type,
            sourceContext = MetadataSourceContext(
                itemType = itemType,
                addonMetadata = meta.toMetaPreview().toHomeDisplayMetadata(),
                previewStableIds = parseContentIds(meta.id)
            ),
            language = settingsRepository.getCurrentLanguageTag(),
            depth = MetadataDepth.DETAIL_FULL
        )
    )
}

private fun Meta.applyResolvedDetail(document: ResolvedDetailDisplayDocument): Meta {
    return copy(
        name = document.fields.title ?: name,
        description = document.fields.overview ?: description,
        genres = document.fields.genres.ifEmpty { genres },
        imdbRating = document.rating?.value ?: imdbRating,
        poster = document.artwork.poster?.toString() ?: poster,
        background = document.artwork.backdrop?.toString() ?: background,
        logo = document.artwork.logo?.toString() ?: logo,
        artwork = document.artwork,
        trailerYtIds = document.trailer.fallbackTrailerYtIds
    )
}
```

Use `ArtworkLegacyProjection` instead of `toString()` if the typed ref does not already produce a safe `nexio-artwork://` or local URI string.

- [ ] **Step 4: Move UI state fields to resolved-document values**

Modify `MetaDetailsUiState` to add:

```kotlin
val resolvedDetail: ResolvedDetailDisplayDocument? = null,
val localizationFallbackReason: String? = null,
val trailerState: TrailerDisplayState = TrailerDisplayState()
```

Update screen rendering to read:

```kotlin
val resolvedDetail = uiState.resolvedDetail
val trailerState = uiState.trailerState
val localizationFallbackReason = uiState.localizationFallbackReason
```

Do not render fallback text for localization reason unless an existing debug/trace panel already exists.

- [ ] **Step 5: Remove direct sidecar calls from detail**

Delete or rewrite these detail methods so they consume `ResolvedDetailDisplayDocument` or call resolver-owned repository methods:

```text
loadMoreLikeThisAsync
loadReviewsAsync
loadEpisodeRatingsAsync
preloadTitleTrailerAvailability
fetchTrailerUrl
```

The replacement functions must not contain:

```text
metadataSecondaryRepository.
mdbListRepository.
titleRatingOverrideRepository.
episodeRatingsSelectionRepository.
trailerService.
tmdbService.ensureTmdbId
.ensureTmdbId(
```

- [ ] **Step 6: Run detail tests**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin testDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.detail.MetaDetailsResolvedDocumentTest" \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest.detail view model does not own provider metadata ratings trailers or identity decisions"
```

Expected: PASS.

- [ ] **Step 7: Commit detail document migration**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail \
  app/src/test/java/com/nexio/tv/ui/screens/detail \
  app/src/test/java/com/nexio/tv/architecture/SharedResolutionOpenFindingsArchitectureTest.kt
git commit -m "feat: make detail consume resolved display document"
```

Expected: commit succeeds.

---

### Task 5: Make Home Hydration Publish Resolved Display Instead Of Re-Merging Preview Fields

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedSurfacePublishingTest.kt`

- [ ] **Step 1: Write home resolved-surface publishing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedSurfacePublishingTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HomeResolvedSurfacePublishingTest {
    @Test
    fun `home hydration publishes resolved items to surface repository instead of mutating preview fields`() = runTest {
        val surfaceRepository = mockk<ResolvedDisplaySurfaceRepository>(relaxed = true)
        val coordinator = HomeHydrationCoordinatorTestFactory.create(
            resolvedDisplaySurfaceRepository = surfaceRepository
        )
        val item = MetaPreview(
            id = "series:tmdb:1399",
            type = com.nexio.tv.domain.model.ContentType.SERIES,
            name = "Preview Title",
            poster = "https://image.tmdb.org/t/p/w500/raw.jpg"
        )

        coordinator.hydrateForTest(item)

        coVerify(exactly = 1) { surfaceRepository.publishResolvedItems(any(), any()) }
        coVerify(exactly = 0) { surfaceRepository.publishPreviewMutation(any(), any()) }
    }
}
```

Add `HomeHydrationCoordinatorTestFactory.create(resolvedDisplaySurfaceRepository = surfaceRepository)` and `hydrateForTest(item)` only in test sources if no equivalent helper exists.

- [ ] **Step 2: Inject resolved surface into home hydration**

Modify `HomeHydrationCoordinator` constructor:

```kotlin
private val resolvedDisplaySurfaceRepository: ResolvedDisplaySurfaceRepository,
```

Remove:

```kotlin
private val titleRatingOverrideRepository: TitleRatingOverrideRepository,
```

- [ ] **Step 3: Replace `TitleRatingOverrideRepository.enrichPreview` use**

In `HomeHydrationCoordinator.toHydratedHomeOverlay(item = item, itemKey = itemKey, bundle = bundle, languageTag = languageTag)`, replace:

```kotlin
val enrichedPreview = titleRatingOverrideRepository.enrichPreview(
    displayMetadata.applyTo(item),
    bundle
)
val enrichedFields = enrichedPreview.toHomeDisplayMetadata()
```

with:

```kotlin
val fields = displayMetadata.copy(
    artwork = displayMetadata.artwork ?: item.artwork
)
```

Then publish to the surface repository after overlay creation:

```kotlin
resolvedDisplaySurfaceRepository.publishResolvedItems(
    surfaceKey = "home",
    items = listOf(overlay.toResolvedDisplayItem())
)
```

Define `HydratedHomeOverlay.toResolvedDisplayItem()` in `HomeResolvedDisplayMapper.kt` so it maps only already-resolved fields and typed artwork:

```kotlin
internal fun HydratedHomeOverlay.toResolvedDisplayItem(): ResolvedDisplayItem {
    return ResolvedDisplayItem(
        itemKey = itemKey,
        contentId = canonicalId,
        parentId = canonicalId,
        itemType = contentType,
        mediaKind = contentType.toMetadataMediaKind(),
        canonicalProvider = canonicalProvider.name,
        canonicalId = canonicalId,
        imdbId = imdbId,
        stableIds = ProviderIds(imdb = imdbId),
        display = fields.toResolvedDisplayFields(),
        artwork = fields.artwork ?: ArtworkBundle(),
        rating = fields.toTitleRatingOrNull(),
        trailer = TrailerDisplayState(),
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = fieldTrace,
        updatedAtMs = updatedAtMs
    )
}
```

- [ ] **Step 4: Remove home preview mutation helpers**

Delete or rewrite these functions so they do not overlay provider fields onto `MetaPreview`:

```text
HomeCatalogRefreshCoordinator.applyTvMetadataEnrichmentForHome
HomeHydrationCoordinator.toHydratedHomeOverlay title rating re-enrichment
HomeViewModelPresentationPipeline trailerYtIds re-merge after externalMeta
```

The remaining first-paint `MetaPreview` path may keep source preview fields. Hydrated canonical display must come from `ResolvedDisplaySurfaceRepository`.

- [ ] **Step 5: Run home tests**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin testDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.home.HomeResolvedSurfacePublishingTest" \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest.home hydration and presentation do not call rating or trailer sidecars"
```

Expected: PASS.

- [ ] **Step 6: Commit home surface publishing**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home \
  app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt \
  app/src/test/java/com/nexio/tv/ui/screens/home \
  app/src/test/java/com/nexio/tv/architecture/SharedResolutionOpenFindingsArchitectureTest.kt
git commit -m "feat: publish home hydration through resolved surface"
```

Expected: commit succeeds.

---

### Task 6: Remove Remaining UI TMDB Identity Bridge Calls

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/RailMediaIdentityResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/architecture/NoUiTmdbEnsureIdArchitectureTest.kt`

- [ ] **Step 1: Add the global identity architecture test**

Create `app/src/test/java/com/nexio/tv/architecture/NoUiTmdbEnsureIdArchitectureTest.kt`:

```kotlin
package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NoUiTmdbEnsureIdArchitectureTest {
    @Test
    fun `ui and main activity must not call TmdbService ensureTmdbId`() {
        val roots = listOf(
            File("app/src/main/java/com/nexio/tv/ui"),
            File("app/src/main/java/com/nexio/tv/MainActivity.kt")
        )

        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val source = file.readText()
                    Regex("""(?:tmdbService\.)?ensureTmdbId\s*\(""").findAll(source)
                        .map { "${file.invariantSeparatorsPath}:${source.substring(0, it.range.first).count { c -> c == '\n' } + 1}" }
                }
                .toList()
        }

        assertTrue(
            "UI/MainActivity must use StableIdBundleResolver-owned identities, not direct TMDB bridge helpers: $offenders",
            offenders.isEmpty()
        )
    }
}
```

- [ ] **Step 2: Replace detail identity bridge uses**

For reviews, recommendations, trailer availability, and mark-watched season paths in `MetaDetailsViewModel`, replace:

```kotlin
tmdbService.ensureTmdbId(meta.id, meta.apiType) ?: tmdbService.ensureTmdbId(itemId, itemType)
```

with:

```kotlin
val tmdbId = uiState.value.resolvedDetail
    ?.identity
    ?.providerIds
    ?.tmdb
    ?: parseContentIds(meta.id).tmdb?.toString()
    ?: parseContentIds(itemId).tmdb?.toString()
```

If `tmdbId` is null, return without fetching provider-specific data. Do not call `tmdbService.ensureTmdbId`.

- [ ] **Step 3: Replace home trailer identity bridge uses**

In home trailer availability paths, replace TMDB derivation from raw item IDs with:

```kotlin
val stableIds = item.resolvedDisplayItem?.stableIds ?: item.firstPaintStableIds
val tmdbId = stableIds.tmdb
```

If `tmdbId` is null, call `TrailerResolver` with `stableIds` and let the resolver decide whether fallback trailer IDs are sufficient.

- [ ] **Step 4: Normalize `RailMediaIdentityResolver` status**

Add this KDoc to `RailMediaIdentityResolver`:

```kotlin
/**
 * Temporary compatibility adapter for rail cache ownership keys.
 *
 * Canonical identity ownership belongs to StableIdBundleResolver. This adapter may normalize
 * already-observed rail identifiers for cache key stability only. It must not perform network
 * identity bridging and must not be injected into UI, ViewModel, player, or screensaver code.
 *
 * Expiration: remove after MetaPreview/RailItemPreview carry StableIdBundle directly.
 */
```

Add an architecture assertion that `RailMediaIdentityResolver` is only imported from `core/integration`, `data/integration`, `data/local`, and tests.

- [ ] **Step 5: Run identity tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests "com.nexio.tv.architecture.NoUiTmdbEnsureIdArchitectureTest" \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest.ui and main activity do not call tmdb identity bridge helpers"
```

Expected: PASS.

- [ ] **Step 6: Commit identity cleanup**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui \
  app/src/main/java/com/nexio/tv/core/integration/RailMediaIdentityResolver.kt \
  app/src/test/java/com/nexio/tv/architecture
git commit -m "fix: remove ui tmdb identity bridge calls"
```

Expected: commit succeeds.

---

### Task 7: Make RatingResolver The Only Rating Decision Owner

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/resolver/RatingResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TitleRatingOverrideRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/EpisodeRatingsSelectionRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodeRatingModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/resolver/RatingResolverTest.kt`

- [ ] **Step 1: Add RatingResolver tests**

Create `app/src/test/java/com/nexio/tv/core/metadata/router/resolver/RatingResolverTest.kt`:

```kotlin
package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.domain.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

class RatingResolverTest {
    @Test
    fun `title rating prefers custom imdb then mdblist then omdb then primary then preview`() {
        val resolver = RatingResolver()
        val result = resolver.resolveTitleRating(
            listOf(
                RatingCandidate(ProviderId.TMDB, RatingScope.TITLE, 7.1, 100, SourceRole.PRIMARY, Confidence.MEDIUM, listOf("primary")),
                RatingCandidate(ProviderId.MDBLIST, RatingScope.TITLE, 8.0, 200, SourceRole.SECONDARY, Confidence.HIGH, listOf("mdblist")),
                RatingCandidate(ProviderId.IMDB, RatingScope.TITLE, 8.4, 300, SourceRole.CUSTOM, Confidence.HIGH, listOf("custom-imdb"))
            )
        )

        assertEquals(8.4, result?.value)
        assertEquals(ProviderId.IMDB, result?.provider)
    }

    @Test
    fun `episode rating prefers custom imdb then omdb then provider rating`() {
        val resolver = RatingResolver()
        val result = resolver.resolveEpisodeRatings(
            listOf(
                EpisodeRatingCandidate(1 to 2, ProviderId.TMDB, 7.2, SourceRole.PRIMARY, Confidence.MEDIUM, listOf("tmdb")),
                EpisodeRatingCandidate(1 to 2, ProviderId.OMDB, 8.1, SourceRole.SECONDARY, Confidence.HIGH, listOf("omdb"))
            )
        )

        assertEquals(8.1, result[1 to 2]?.value)
        assertEquals(ProviderId.OMDB, result[1 to 2]?.provider)
    }
}
```

- [ ] **Step 2: Implement RatingResolver**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/resolver/RatingResolver.kt`:

```kotlin
package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.domain.model.ProviderId

enum class RatingScope { TITLE, EPISODE }
enum class SourceRole { CUSTOM, SECONDARY, PRIMARY, PREVIEW }
enum class Confidence { LOW, MEDIUM, HIGH }

data class RatingCandidate(
    val provider: ProviderId,
    val scope: RatingScope,
    val value: Double?,
    val votes: Int?,
    val sourceRole: SourceRole,
    val confidence: Confidence,
    val trace: List<String>
)

data class EpisodeRatingCandidate(
    val episodeKey: Pair<Int, Int>,
    val provider: ProviderId,
    val value: Double?,
    val sourceRole: SourceRole,
    val confidence: Confidence,
    val trace: List<String>
)

data class RatingResolution(
    val provider: ProviderId,
    val value: Double,
    val trace: List<String>
)

class RatingResolver {
    fun resolveTitleRating(candidates: List<RatingCandidate>): RatingResolution? {
        return candidates
            .filter { it.scope == RatingScope.TITLE && it.value != null }
            .sortedWith(compareBy<RatingCandidate> { titleProviderRank(it.provider) }.thenBy { sourceRoleRank(it.sourceRole) })
            .firstOrNull()
            ?.let { RatingResolution(it.provider, it.value!!, it.trace) }
    }

    fun resolveEpisodeRatings(candidates: List<EpisodeRatingCandidate>): Map<Pair<Int, Int>, RatingResolution> {
        return candidates
            .filter { it.value != null }
            .groupBy { it.episodeKey }
            .mapValues { (_, episodeCandidates) ->
                val selected = episodeCandidates.sortedWith(
                    compareBy<EpisodeRatingCandidate> { episodeProviderRank(it.provider) }
                        .thenBy { sourceRoleRank(it.sourceRole) }
                ).first()
                RatingResolution(selected.provider, selected.value!!, selected.trace)
            }
    }

    private fun titleProviderRank(provider: ProviderId): Int = when (provider) {
        ProviderId.IMDB -> 0
        ProviderId.MDBLIST -> 1
        ProviderId.OMDB -> 2
        ProviderId.TMDB, ProviderId.TVDB, ProviderId.KITSU -> 3
        else -> 4
    }

    private fun episodeProviderRank(provider: ProviderId): Int = when (provider) {
        ProviderId.IMDB -> 0
        ProviderId.OMDB -> 1
        ProviderId.TMDB, ProviderId.TVDB, ProviderId.KITSU -> 2
        else -> 3
    }

    private fun sourceRoleRank(sourceRole: SourceRole): Int = when (sourceRole) {
        SourceRole.CUSTOM -> 0
        SourceRole.SECONDARY -> 1
        SourceRole.PRIMARY -> 2
        SourceRole.PREVIEW -> 3
    }
}
```

- [ ] **Step 3: Convert rating repositories to candidate providers**

Modify `TitleRatingOverrideRepository` so its public method becomes:

```kotlin
suspend fun titleRatingCandidates(meta: MetaPreview, stableIds: ProviderIds): List<RatingCandidate>
```

It may still call `MDBListRepository`, but only to create `RatingCandidate` values for `RatingResolver`.

Modify `EpisodeRatingsSelectionRepository` so its public method becomes:

```kotlin
suspend fun episodeRatingCandidates(
    meta: Meta,
    imdbId: String?,
    itemType: String,
    seasonEpisodes: Map<Int, Set<Int>>
): List<EpisodeRatingCandidate>
```

It may still call `CustomImdbEpisodeRatingsRepository` and `OmdbEpisodeRatingsRepository`, but it must not choose the final winner.

- [ ] **Step 4: Replace UI direct rating calls**

In `MetaDetailsViewModel`, replace calls to:

```text
mdbListRepository.getRatingsForMeta(meta, itemId, itemType)
titleRatingOverrideRepository.enrichPreview(preview, stableIds)
episodeRatingsSelectionRepository.getEpisodeRatings(meta, imdbId, itemType, seasonEpisodes)
resolveEpisodeRatings(tmdbRatings, omdbRatings)
```

with:

```kotlin
val selectedTitleRating = ratingResolver.resolveTitleRating(titleCandidates)
val selectedEpisodeRatings = ratingResolver.resolveEpisodeRatings(episodeCandidates)
```

In `HomeHydrationCoordinator`, remove rating re-enrichment and use the selected rating already on `ResolvedDisplayItem.rating` or `HydratedHomeOverlay.fields`.

- [ ] **Step 5: Run rating tests**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin testDebugUnitTest \
  --tests "com.nexio.tv.core.metadata.router.resolver.RatingResolverTest" \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest.detail view model does not own provider metadata ratings trailers or identity decisions" \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest.home hydration and presentation do not call rating or trailer sidecars"
```

Expected: PASS.

- [ ] **Step 6: Commit rating ownership**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/resolver/RatingResolver.kt \
  app/src/main/java/com/nexio/tv/data/repository/TitleRatingOverrideRepository.kt \
  app/src/main/java/com/nexio/tv/data/repository/EpisodeRatingsSelectionRepository.kt \
  app/src/main/java/com/nexio/tv/ui/screens/detail \
  app/src/main/java/com/nexio/tv/ui/screens/home \
  app/src/test/java/com/nexio/tv/core/metadata/router/resolver/RatingResolverTest.kt \
  app/src/test/java/com/nexio/tv/architecture/SharedResolutionOpenFindingsArchitectureTest.kt
git commit -m "feat: centralize rating selection in rating resolver"
```

Expected: commit succeeds.

---

### Task 8: Make TrailerResolver Own Availability And Playback Refs

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/resolver/TrailerResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/MainActivity.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/resolver/TrailerResolverPlaybackTest.kt`

- [ ] **Step 1: Add trailer resolver playback tests**

Create `app/src/test/java/com/nexio/tv/core/metadata/router/resolver/TrailerResolverPlaybackTest.kt`:

```kotlin
package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerResolverPlaybackTest {
    @Test
    fun `resolver selects typed youtube id without constructing watch url in ui`() {
        val resolver = TrailerResolver(traceEvents = com.nexio.tv.core.trace.TraceMetadataEvents.noop())
        val result = resolver.resolveTrailer(
            TrailerResolveRequest(
                itemKey = "series:tmdb:1399",
                title = "Game of Thrones",
                year = "2011",
                stableIds = ProviderIds(tmdb = "1399", imdb = "tt0944947"),
                fallbackYtIds = listOf("abc123def45"),
                surface = TrailerSurface.DETAIL
            )
        )

        assertTrue(result.availability.available)
        assertEquals("abc123def45", (result.selected as TrailerPlaybackRef.YouTubeId).videoId)
    }
}
```

- [ ] **Step 2: Extend TrailerResolver types and API**

Modify `TrailerResolver.kt` to add:

```kotlin
enum class TrailerSurface { HOME, DETAIL, SCREENSAVER }

data class TrailerResolveRequest(
    val itemKey: String,
    val title: String,
    val year: String?,
    val stableIds: ProviderIds,
    val fallbackYtIds: List<String>,
    val surface: TrailerSurface
)

data class TrailerAvailability(val available: Boolean, val reason: String?)

sealed interface TrailerPlaybackRef {
    data class YouTubeId(val videoId: String) : TrailerPlaybackRef
    data class ExternalUrl(val url: String) : TrailerPlaybackRef
    data class InAppSource(val cacheKey: String) : TrailerPlaybackRef
}

data class TrailerResolution(
    val availability: TrailerAvailability,
    val candidates: List<TrailerPlaybackRef>,
    val selected: TrailerPlaybackRef?,
    val trace: List<String>
)

suspend fun resolveTrailer(request: TrailerResolveRequest): TrailerResolution
```

The resolver may delegate playback-source extraction to `TrailerService`, but UI, ViewModel, Home, and MainActivity must receive or pass `TrailerPlaybackRef`, not build YouTube URLs.

- [ ] **Step 3: Convert TrailerService to transport**

Rename comments and public usage in `TrailerService` to make it transport-only:

```kotlin
suspend fun resolvePlaybackSource(ref: TrailerPlaybackRef): TrailerResolutionResult?
```

Move YouTube watch URL construction inside this method:

```kotlin
private fun TrailerPlaybackRef.toTransportUrl(): String? {
    return when (this) {
        is TrailerPlaybackRef.YouTubeId -> "https://www.youtube.com/watch?v=$videoId"
        is TrailerPlaybackRef.ExternalUrl -> url
        is TrailerPlaybackRef.InAppSource -> null
    }
}
```

No UI or ViewModel file may contain `"https://www.youtube.com/watch?v="`.

- [ ] **Step 4: Replace home/detail/screensaver trailer sidecars**

Replace calls to:

```text
trailerService.getTitleMediaAvailability(title, year, tmdbId, type, contentId, fallbackYtIds)
metadataRouterFacade.fetchTrailer(metadataRequest, title, year, tmdbId, type, seasonNumber, contentId, fallbackYtIds)
TrailerService.resolveIdleTrailerScreensaverPlaybackSource(candidate, trailerId)
```

with:

```kotlin
val trailer = trailerResolver.resolveTrailer(
    TrailerResolveRequest(
        itemKey = itemKey,
        title = title,
        year = year,
        stableIds = stableIds,
        fallbackYtIds = fallbackYtIds,
        surface = TrailerSurface.DETAIL
    )
)
```

For playback, pass:

```kotlin
trailerService.resolvePlaybackSource(trailer.selected)
```

only after `TrailerResolver` has selected the ref.

- [ ] **Step 5: Run trailer tests and scans**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin testDebugUnitTest \
  --tests "com.nexio.tv.core.metadata.router.resolver.TrailerResolverPlaybackTest" \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest.detail view model does not own provider metadata ratings trailers or identity decisions" \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest.home hydration and presentation do not call rating or trailer sidecars"
```

Run:

```bash
rg -n "https://www\\.youtube\\.com/watch\\?v=|youtube\\.com/watch\\?v=|youtu\\.be" app/src/main/java/com/nexio/tv/ui app/src/main/java/com/nexio/tv/MainActivity.kt
```

Expected: tests PASS and scan returns no production UI/MainActivity matches.

- [ ] **Step 6: Commit trailer ownership**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/resolver/TrailerResolver.kt \
  app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt \
  app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt \
  app/src/main/java/com/nexio/tv/ui/screens/detail \
  app/src/main/java/com/nexio/tv/ui/screens/home \
  app/src/main/java/com/nexio/tv/MainActivity.kt \
  app/src/test/java/com/nexio/tv/core/metadata/router/resolver/TrailerResolverPlaybackTest.kt \
  app/src/test/java/com/nexio/tv/architecture/SharedResolutionOpenFindingsArchitectureTest.kt
git commit -m "feat: route trailer decisions through trailer resolver"
```

Expected: commit succeeds.

---

### Task 9: Make SkipSegmentResolver The Player Skip Owner

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/resolver/SkipSegmentResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/resolver/SkipSegmentResolverTest.kt`

- [ ] **Step 1: Add skip resolver tests**

Create `app/src/test/java/com/nexio/tv/core/metadata/router/resolver/SkipSegmentResolverTest.kt`:

```kotlin
package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SkipSegmentResolverTest {
    @Test
    fun `anime request asks repository anime adapter path and returns selected segments`() = runTest {
        val repository = mockk<SkipIntroRepositoryPort>()
        coEvery { repository.fetchAnimeSegments("123", 2) } returns listOf(SkipSegment(0, 90_000, SkipSegmentKind.INTRO))
        val resolver = SkipSegmentResolver(repository)

        val result = resolver.resolveSkipSegments(
            SkipSegmentRequest(
                stableIds = ProviderIds(kitsu = "kitsu:abc"),
                episodeContext = EpisodeContext(season = 1, episode = 2),
                mediaKind = SkipMediaKind.ANIME
            )
        )

        assertEquals(SkipSegmentKind.INTRO, result.segments.single().kind)
        coVerify(exactly = 1) { repository.fetchAnimeSegments(any(), 2) }
    }
}
```

- [ ] **Step 2: Implement SkipSegmentResolver**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/resolver/SkipSegmentResolver.kt`:

```kotlin
package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

enum class SkipMediaKind { ANIME, MOVIE, SERIES }
enum class SkipSegmentKind { INTRO, RECAP, CREDITS, PREVIEW, OUTRO }

data class EpisodeContext(
    val season: Int?,
    val episode: Int?
)

data class SkipSegmentRequest(
    val stableIds: ProviderIds,
    val episodeContext: EpisodeContext,
    val mediaKind: SkipMediaKind
)

data class SkipSegment(
    val startMs: Long,
    val endMs: Long,
    val kind: SkipSegmentKind
)

data class SkipSegmentResolution(
    val segments: List<SkipSegment>,
    val selectedProvider: String?,
    val trace: List<String>
)

interface SkipIntroRepositoryPort {
    suspend fun fetchAnimeSegments(animeId: String, episode: Int): List<SkipSegment>
    suspend fun fetchIntroDbSegments(contentId: String, season: Int?, episode: Int?): List<SkipSegment>
}

@Singleton
class SkipSegmentResolver @Inject constructor(
    private val repository: SkipIntroRepositoryPort
) {
    suspend fun resolveSkipSegments(request: SkipSegmentRequest): SkipSegmentResolution {
        val episodeNumber = request.episodeContext.episode
        val animeId = request.stableIds.kitsu ?: request.stableIds.tvdb ?: request.stableIds.tmdb

        if (request.mediaKind == SkipMediaKind.ANIME && animeId != null && episodeNumber != null) {
            val animeSegments = repository.fetchAnimeSegments(animeId, episodeNumber)
            if (animeSegments.isNotEmpty()) {
                return SkipSegmentResolution(animeSegments, "anime-skip-policy", listOf("anime-first"))
            }
        }

        val contentId = request.stableIds.tvdb ?: request.stableIds.tmdb ?: request.stableIds.imdb
        val introDbSegments = contentId?.let {
            repository.fetchIntroDbSegments(it, request.episodeContext.season, request.episodeContext.episode)
        }.orEmpty()

        return SkipSegmentResolution(introDbSegments, "the-intro-db", listOf("non-anime-or-fallback"))
    }
}
```

- [ ] **Step 3: Make SkipIntroRepository implement the port**

Modify `SkipIntroRepository`:

```kotlin
class SkipIntroRepository @Inject constructor(
    private val introDbProvider: IntroDbIntegrationProvider,
    private val aniSkipProvider: AniSkipIntegrationProvider,
    private val animeSkipProvider: AnimeSkipIntegrationProvider,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val theIntroDbSettingsDataStore: TheIntroDbSettingsDataStore
) : SkipIntroRepositoryPort {
    override suspend fun fetchAnimeSegments(animeId: String, episode: Int): List<SkipSegment> {
        val malSegments = getSkipIntervalsForMal(animeId, episode).map { it.toSkipSegment() }
        if (malSegments.isNotEmpty()) return malSegments
        return getSkipIntervalsForKitsu(animeId, episode).map { it.toSkipSegment() }
    }

    override suspend fun fetchIntroDbSegments(contentId: String, season: Int?, episode: Int?): List<SkipSegment> {
        return getSkipIntervals(contentId, season, episode).map { it.toSkipSegment() }
    }
}
```

Keep provider arbitration policy in `SkipSegmentResolver`. `SkipIntroRepository` may adapt provider APIs and cache segments, but it must not be injected into player code.

- [ ] **Step 4: Replace player injection**

In `PlayerViewModel` and `PlayerRuntimeController`, replace:

```kotlin
private val skipIntroRepository: SkipIntroRepository
```

with:

```kotlin
private val skipSegmentResolver: SkipSegmentResolver
```

Replace skip loading calls with:

```kotlin
val resolution = skipSegmentResolver.resolveSkipSegments(
    SkipSegmentRequest(
        stableIds = playbackOwnerContext.stableIds,
        episodeContext = EpisodeContext(season = season, episode = episode),
        mediaKind = if (isAnimePrimarySkipPath()) SkipMediaKind.ANIME else SkipMediaKind.SERIES
    )
)
```

- [ ] **Step 5: Run skip tests**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin testDebugUnitTest \
  --tests "com.nexio.tv.core.metadata.router.resolver.SkipSegmentResolverTest" \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest.player does not call SkipIntroRepository directly"
```

Expected: PASS.

- [ ] **Step 6: Commit skip resolver ownership**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/resolver/SkipSegmentResolver.kt \
  app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player \
  app/src/test/java/com/nexio/tv/core/metadata/router/resolver/SkipSegmentResolverTest.kt \
  app/src/test/java/com/nexio/tv/architecture
git commit -m "feat: route player skip segments through resolver"
```

Expected: commit succeeds.

---

### Task 10: Finish Artwork Typed-Ref Migration For Metadata UI

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/GridContentCard.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/search/SearchScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodesSection.kt`
- Test: `app/src/test/java/com/nexio/tv/architecture/RawRemoteArtworkUrlBoundaryTest.kt`

- [ ] **Step 1: Add typed artwork UI helper**

Create or extend an existing helper file under `app/src/main/java/com/nexio/tv/core/artwork/ArtworkUiModels.kt`:

```kotlin
package com.nexio.tv.core.artwork

sealed interface ArtworkUiModel {
    data class Ref(val ref: ArtworkDisplayRef) : ArtworkUiModel
    data class SafeUri(val uri: String) : ArtworkUiModel
    data class Resource(val resId: Int) : ArtworkUiModel
    data object Empty : ArtworkUiModel
}

fun ArtworkDisplayRef?.toCoilModelOrNull(): Any? {
    return when (this) {
        null -> null
        is ArtworkDisplayRef.RuntimeAsset -> uri
        is ArtworkDisplayRef.Decision -> "nexio-artwork://decision/$ownerKey/$kind"
        is ArtworkDisplayRef.Placeholder -> placeholderUri
    }
}
```

Adjust property names to the actual `ArtworkDisplayRef` sealed variants in this repo. The output must be a `nexio-artwork://`, local/content/file URI, placeholder URI, or resource ID.

- [ ] **Step 2: Replace ContentCard raw URL inputs**

Modify `ContentCard` and `GridContentCard` parameters:

```kotlin
artwork: ArtworkBundle?,
posterModel: Any?,
logoModel: Any?
```

Remove or deprecate:

```kotlin
imageUrl: String?
logoUrl: String?
```

If call sites still pass legacy strings during migration, they must pass strings only after:

```kotlin
ArtworkLegacyProjection.project(ref = artworkRef, ownerKey = ownerKey, kind = kind)
```

and the projected string must start with `nexio-artwork://`, `nexio-placeholder://`, `file://`, or `content://`.

- [ ] **Step 3: Replace metadata UI Coil data calls**

For metadata surfaces, replace:

```kotlin
ImageRequest.Builder(context).data(imageUrl)
ImageRequest.Builder(context).data(posterUrl)
ImageRequest.Builder(context).data(displayPoster)
ImageRequest.Builder(context).data(displayBackground)
ImageRequest.Builder(context).data(displayThumbnail)
```

with:

```kotlin
ImageRequest.Builder(context).data(artworkRef.toCoilModelOrNull())
```

Resource logos such as IMDb/TMDB badges may keep `R.raw.*` or `R.drawable.*`.

- [ ] **Step 4: Replace Search poster direct URL loads**

In `SearchScreen`, replace:

```kotlin
.data(posterUrl)
```

with:

```kotlin
.data(searchPosterArtworkRefs[suggestion.tconst]?.toCoilModelOrNull())
```

Add `searchPosterArtworkRefs: Map<String, ArtworkDisplayRef>` to `SearchUiState`. Populate it through `ArtworkRouter` or `ArtworkAssetRepository` before rendering posters.

- [ ] **Step 5: Replace ModernHomeRows prefetch raw string use**

Replace:

```kotlin
val url = item.imageUrl ?: return
ImageRequest.Builder(context).data(url)
```

with:

```kotlin
val model = item.artwork.poster.toCoilModelOrNull() ?: return
ImageRequest.Builder(context).data(model)
```

- [ ] **Step 6: Run artwork scans**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests "com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest" \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest.metadata ui coil calls do not use raw remote artwork string fields"
```

Run:

```bash
rg -n "ImageRequest\\.Builder|AsyncImage|rememberAsyncImagePainter|\\.data\\(" app/src/main/java/com/nexio/tv/ui app/src/main/java/com/nexio/tv/MainActivity.kt
```

Expected: tests PASS. Manual scan confirms metadata image requests use typed refs, safe URI projections, resources, or placeholders.

- [ ] **Step 7: Commit artwork typed-ref migration**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/artwork \
  app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt \
  app/src/main/java/com/nexio/tv/ui/components \
  app/src/main/java/com/nexio/tv/ui/screens/home \
  app/src/main/java/com/nexio/tv/ui/screens/search \
  app/src/main/java/com/nexio/tv/ui/screens/detail \
  app/src/test/java/com/nexio/tv/architecture
git commit -m "feat: migrate metadata artwork ui to typed refs"
```

Expected: commit succeeds.

---

### Task 11: Centralize Localization Policy

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/LocalizationResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/LocalizationResolverPolicyTest.kt`

- [ ] **Step 1: Add localization policy tests**

Create `app/src/test/java/com/nexio/tv/data/integration/metadata/LocalizationResolverPolicyTest.kt`:

```kotlin
package com.nexio.tv.data.integration.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalizationResolverPolicyTest {
    @Test
    fun `missing tvdb requested language falls back to tvdb english not tmdb text`() {
        val selected = LocalizationResolver.selectLocalizedField(
            requestedLanguage = "nl-NL",
            candidates = listOf(
                LocalizedFieldCandidate("TVDB", "en-US", "TVDB English", primaryProvider = true),
                LocalizedFieldCandidate("TMDB", "nl-NL", "TMDB Dutch", primaryProvider = false)
            )
        )

        assertEquals("TVDB English", selected?.value)
        assertEquals("same-provider-english", selected?.fallbackReason)
    }

    @Test
    fun `cross provider fallback is not selected when primary provider has no language candidate`() {
        val selected = LocalizationResolver.selectLocalizedField(
            requestedLanguage = "nl-NL",
            candidates = listOf(
                LocalizedFieldCandidate("TMDB", "nl-NL", "TMDB Dutch", primaryProvider = false)
            )
        )

        assertNull(selected)
    }
}
```

- [ ] **Step 2: Implement localized candidates**

Modify `LocalizationResolver.kt`:

```kotlin
data class LocalizedFieldCandidate(
    val provider: String,
    val language: String?,
    val value: String?,
    val primaryProvider: Boolean
)

data class LocalizedFieldSelection(
    val provider: String,
    val language: String?,
    val value: String,
    val fallbackReason: String?
)

fun selectLocalizedField(
    requestedLanguage: String?,
    candidates: List<LocalizedFieldCandidate>
): LocalizedFieldSelection? {
    val primary = candidates.filter { it.primaryProvider && !it.value.isNullOrBlank() }
    val requested = requestedLanguage?.lowercase()
    return primary.firstOrNull { it.language?.lowercase() == requested }
        ?.let { LocalizedFieldSelection(it.provider, it.language, it.value!!, null) }
        ?: primary.firstOrNull { it.language?.lowercase()?.startsWith("en") == true }
            ?.let { LocalizedFieldSelection(it.provider, it.language, it.value!!, "same-provider-english") }
        ?: primary.firstOrNull()
            ?.let { LocalizedFieldSelection(it.provider, it.language, it.value!!, "same-provider-any-language") }
}
```

- [ ] **Step 3: Route field selection through localization policy**

In `MetadataAdapterCandidates.kt` and `FieldResolver.kt`, emit and select localized candidates for title and overview. Add selected language and fallback reason to `ResolvedDetailDisplayDocument.localization`.

Do not use:

```text
tvEnrichment ?: tmdbEnrichment
tmdbEnrichment ?: tvEnrichment
```

for localized text selection in `MetaDetailsViewModel`.

- [ ] **Step 4: Run localization tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests "com.nexio.tv.data.integration.metadata.LocalizationResolverPolicyTest" \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest.detail code does not perform cross provider localization fallback"
```

Expected: PASS.

- [ ] **Step 5: Commit localization ownership**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/integration/metadata \
  app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt \
  app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt \
  app/src/test/java/com/nexio/tv/data/integration/metadata/LocalizationResolverPolicyTest.kt \
  app/src/test/java/com/nexio/tv/architecture/SharedResolutionOpenFindingsArchitectureTest.kt
git commit -m "feat: centralize localized field fallback policy"
```

Expected: commit succeeds.

---

### Task 12: Replace Screensaver Legacy String Candidate Fields

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSession.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/MainActivity.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSessionTest.kt`

- [ ] **Step 1: Replace screensaver string fields with typed refs**

Modify `IdleScreensaverModels.kt`:

```kotlin
data class IdleScreensaverSlide(
    val itemKey: String,
    val title: String,
    val subtitle: String?,
    val background: ArtworkDisplayRef?,
    val logo: ArtworkDisplayRef?,
    val trailer: TrailerDisplayState?
)

data class IdleTrailerScreensaverCandidate(
    val itemKey: String,
    val title: String,
    val year: String?,
    val stableIds: ProviderIds,
    val background: ArtworkDisplayRef?,
    val logo: ArtworkDisplayRef?,
    val trailer: TrailerDisplayState
)
```

Remove:

```text
backgroundUrl
logoUrl
trailerYtIds
```

- [ ] **Step 2: Update preparation projections**

In `IdleScreensaverPreparation`, map from `ResolvedDisplayItem` to typed models:

```kotlin
IdleTrailerScreensaverCandidate(
    itemKey = item.itemKey,
    title = item.display.title.orEmpty(),
    year = item.display.year?.toString(),
    stableIds = item.stableIds,
    background = item.artwork.backdrop,
    logo = item.artwork.logo,
    trailer = item.trailer
)
```

Do not convert artwork to strings in preparation. Convert typed artwork to Coil models only in the final Compose renderer.

- [ ] **Step 3: Update trailer session playback**

In `IdleTrailerScreensaverSession`, replace:

```kotlin
"https://www.youtube.com/watch?v=${trailerId.trim()}"
```

with:

```kotlin
TrailerPlaybackRef.YouTubeId(trailerId.trim())
```

Resolve playback through:

```kotlin
trailerService.resolvePlaybackSource(playbackRef)
```

- [ ] **Step 4: Run screensaver tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests "com.nexio.tv.ui.screensaver.IdleTrailerScreensaverSessionTest" \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest.screensaver models do not expose raw trailer or artwork string compatibility fields"
```

Expected: PASS.

- [ ] **Step 5: Commit screensaver compatibility removal**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screensaver \
  app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt \
  app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt \
  app/src/main/java/com/nexio/tv/MainActivity.kt \
  app/src/test/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSessionTest.kt \
  app/src/test/java/com/nexio/tv/architecture/SharedResolutionOpenFindingsArchitectureTest.kt
git commit -m "feat: remove screensaver legacy string candidates"
```

Expected: commit succeeds.

---

### Task 13: Update Audit Report And Run Final Gates

**Files:**
- Create: `review-dossier/shared-resolution-bypass-audit.md`
- Create: `review-dossier/shared-resolution-bypass-audit.csv`
- Modify: `review-dossier/SIGN-OFF.md`

- [ ] **Step 1: Create final markdown audit report**

Create `review-dossier/shared-resolution-bypass-audit.md`:

```markdown
# Shared Resolution Bypass Audit

## Executive Verdict

PASS

## Summary

| Category | Confirmed bypasses | Approved boundaries | False positives | P0 | P1 | P2 |
|---|---:|---:|---:|---:|---:|---:|
| Metadata authority bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Identity bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Field merge bypass | 0 | 0 | 0 | 0 | 0 | 0 |
| Artwork bypass | 0 | 1 | 1 | 0 | 0 | 0 |
| Rating bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Trailer bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Skip bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Screensaver bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| CW/profile bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Account boundary bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Runtime bypass | 0 | 1 | 0 | 0 | 0 | 0 |
| Localization bypass | 0 | 1 | 0 | 0 | 0 | 0 |

## Confirmed Bypasses

No confirmed production bypasses remain after the packet sequence in this branch.

## Fixed Findings

| Severity | Category | Previous evidence | Status | Required owner | Verification |
|---|---|---|---|---|---|
| P0 | Account boundary bypass | Trakt/Simkl mutation envelopes lacked provider + credentialHash | fixed | IntegrationRuntime Account(profileId, provider, credentialHash) | ProviderMutationEnvelopeAccountScopeTest |
| P0 | CW/profile bypass | WatchProgress APIs selected active profile at persistence time | fixed | ProfileBoundaryEnforcer + profile-scoped CW repository | WatchProgressProfileScopeArchitectureTest |
| P0 | Identity bypass | MetaDetailsViewModel.enrichMeta called TmdbService.ensureTmdbId | fixed | StableIdBundleResolver | NoDetailUiTmdbEnsureIdArchitectureTest |
| P1 | Metadata authority bypass | MetadataRouterFacade trace-only sidecar methods and detail MetadataSecondaryRepository calls | fixed | MetadataRouter + ProviderPlanRunner | SharedResolutionOpenFindingsArchitectureTest |
| P1 | Field merge bypass | Detail/home merged final display fields from provider sidecars | fixed | ResolverOrchestrator + FieldResolver | MetaDetailsResolvedDocumentTest, HomeResolvedSurfacePublishingTest |
| P1 | Rating bypass | Detail/home called MDBList/custom/episode repositories directly | fixed | RatingResolver | RatingResolverTest |
| P1 | Trailer bypass | Detail/home/screensaver called TrailerService or built YouTube URLs | fixed | TrailerResolver | TrailerResolverPlaybackTest |
| P1 | Skip bypass | Player called SkipIntroRepository directly | fixed | SkipSegmentResolver | SkipSegmentResolverTest |
| P1 | Artwork bypass | Metadata UI passed raw provider URL fields to Coil | fixed | ArtworkRouter + ArtworkAssetRepository | RawRemoteArtworkUrlBoundaryTest |
| P1 | Localization bypass | Detail fell across TVDB/TMDB localized fields manually | fixed | LocalizationResolver | LocalizationResolverPolicyTest |
| P2 | Screensaver bypass | Screensaver legacy string models held artwork/trailer strings | fixed | ResolvedDisplaySurfaceRepository + TrailerResolver + ArtworkDisplayRef | IdleTrailerScreensaverSessionTest |

## Approved Boundaries

- Provider integration adapters may call provider Retrofit/auth APIs inside IntegrationRuntime-governed operations.
- Raw DTO mappers may parse source payloads but may not decide final display fields.
- TrailerService is approved only as playback transport under TrailerResolver.
- SkipIntroRepository is approved only as provider adapter/cache under SkipSegmentResolver.
- ArtworkLegacyProjection is approved only as final compatibility projection and must emit nexio-artwork, placeholder, local, content, file, or resource models.
- RailMediaIdentityResolver is approved only as a temporary cache-key compatibility adapter and must not perform network identity bridging.

## False Positives

- Test fixtures and source DTOs may contain raw provider URLs or YouTube IDs.
- Resource image requests for local rating badges and settings icons are not metadata artwork bypasses.

## Required Architecture Tests

- SharedResolutionOpenFindingsArchitectureTest
- NoUiTmdbEnsureIdArchitectureTest
- MetadataRouterBoundaryTest
- RawRemoteArtworkUrlBoundaryTest
- SkipIntroRepositoryCanonicalSurfaceTest
- WatchProgressProfileScopeArchitectureTest
```

- [ ] **Step 2: Create final CSV audit report**

Create `review-dossier/shared-resolution-bypass-audit.csv`:

```csv
severity,category,file,line,symbol,status,reason,owner_system,fix_direction,test_name
P0,Account boundary bypass,app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationEnvelope.kt,1,ProviderMutationEnvelope,fixed,provider and credentialHash persisted and validated,IntegrationRuntime Account scope,account-scoped envelope and validator,ProviderMutationEnvelopeAccountScopeTest
P0,CW/profile bypass,app/src/main/java/com/nexio/tv/domain/repository/WatchProgressRepository.kt,1,WatchProgressRepository,fixed,reads require profileId and writes require ActiveProfileSession,ProfileBoundaryEnforcer,explicit profile/session API,WatchProgressProfileScopeArchitectureTest
P0,Identity bypass,app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt,1,enrichMeta,fixed,detail enrichment no longer calls ensureTmdbId,StableIdBundleResolver,use resolved provider IDs,NoDetailUiTmdbEnsureIdArchitectureTest
P1,Metadata authority bypass,app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt,1,fetchTmdbEnrichment,fixed,no trace-only resolve discard sidecar fetch,MetadataRouter + ProviderPlanRunner,provider-plan secondary steps,SharedResolutionOpenFindingsArchitectureTest
P1,Field merge bypass,app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt,1,MetaDetailsViewModel,fixed,detail consumes ResolvedDetailDisplayDocument,ResolverOrchestrator + FieldResolver,resolved document migration,MetaDetailsResolvedDocumentTest
P1,Field merge bypass,app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt,1,HomeHydrationCoordinator,fixed,home publishes resolved display to surface repository,ResolvedDisplaySurfaceRepository,do not mutate MetaPreview with provider fields,HomeResolvedSurfacePublishingTest
P1,Rating bypass,app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt,1,loadEpisodeRatingsAsync,fixed,rating candidates selected by RatingResolver,RatingResolver,centralize title and episode rating selection,RatingResolverTest
P1,Trailer bypass,app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt,1,fetchTrailerUrl,fixed,trailer selected as typed playback ref,TrailerResolver,TrailerService transport only,TrailerResolverPlaybackTest
P1,Skip bypass,app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt,1,skip loading,fixed,player calls SkipSegmentResolver,SkipSegmentResolver,repository adapter/cache only,SkipSegmentResolverTest
P1,Artwork bypass,app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt,1,ContentCard,fixed,metadata UI receives typed artwork or safe projection,ArtworkRouter + ArtworkAssetRepository,typed refs to Coil,RawRemoteArtworkUrlBoundaryTest
P1,Localization bypass,app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt,1,localized field selection,fixed,cross-provider fallback removed from ViewModel,LocalizationResolver,provider-language policy selection,LocalizationResolverPolicyTest
P2,Screensaver bypass,app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt,1,IdleScreensaverSlide,fixed,legacy string candidate fields removed,ResolvedDisplaySurfaceRepository,typed artwork and trailer refs,IdleTrailerScreensaverSessionTest
Info,Artwork bypass,app/src/main/java/com/nexio/tv/core/artwork/ArtworkLegacyProjection.kt,1,ArtworkLegacyProjection,false positive,emits safe nexio-artwork or placeholder projections,ArtworkAssetRepository,final compatibility projection only,ArtworkLegacyProjectionTest
Info,Identity bypass,app/src/main/java/com/nexio/tv/core/integration/RailMediaIdentityResolver.kt,1,RailMediaIdentityResolver,temporary compatibility path,cache-key adapter only with expiration,StableIdBundleResolver,remove after rail previews carry StableIdBundle,RailMediaIdentityResolverIsAllowlistedOrMigratedTest
```

- [ ] **Step 3: Update sign-off**

Append to `review-dossier/SIGN-OFF.md`:

```markdown
## Shared Resolution Open Findings Closed

The refreshed 2026-05-06 shared resolution bypass audit has been remediated on this branch.

- P0 profile/account blockers were fixed in the P0 packet.
- P1/P2 shared-resolution bypasses were migrated to canonical owner systems.
- Final architecture gates and focused resolver tests pass.
- Remaining provider service usage is limited to approved adapters, transport, test, fixture, and temporary compatibility projection boundaries documented in `shared-resolution-bypass-audit.md`.
```

- [ ] **Step 4: Run final focused gates**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin testDebugUnitTest \
  --tests "com.nexio.tv.architecture.SharedResolutionOpenFindingsArchitectureTest" \
  --tests "com.nexio.tv.architecture.NoUiTmdbEnsureIdArchitectureTest" \
  --tests "com.nexio.tv.architecture.NoDetailUiTmdbEnsureIdArchitectureTest" \
  --tests "com.nexio.tv.architecture.WatchProgressProfileScopeArchitectureTest" \
  --tests "com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest" \
  --tests "com.nexio.tv.architecture.MetadataRouterBoundaryTest" \
  --tests "com.nexio.tv.architecture.SkipIntroRepositoryCanonicalSurfaceTest" \
  --tests "com.nexio.tv.data.repository.MetadataDisplayRepositoryTest" \
  --tests "com.nexio.tv.ui.screens.detail.MetaDetailsResolvedDocumentTest" \
  --tests "com.nexio.tv.ui.screens.home.HomeResolvedSurfacePublishingTest" \
  --tests "com.nexio.tv.core.metadata.router.resolver.RatingResolverTest" \
  --tests "com.nexio.tv.core.metadata.router.resolver.TrailerResolverPlaybackTest" \
  --tests "com.nexio.tv.core.metadata.router.resolver.SkipSegmentResolverTest" \
  --tests "com.nexio.tv.data.integration.metadata.LocalizationResolverPolicyTest" \
  --tests "com.nexio.tv.ui.screensaver.IdleTrailerScreensaverSessionTest"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run final static scans**

Run:

```bash
rg -n "metadataSecondaryRepository\\.|mdbListRepository\\.|titleRatingOverrideRepository\\.|episodeRatingsSelectionRepository\\.|trailerService\\.|tmdbService\\.ensureTmdbId|\\.ensureTmdbId\\(" app/src/main/java/com/nexio/tv/ui app/src/main/java/com/nexio/tv/MainActivity.kt
```

Expected: no output.

Run:

```bash
rg -n "https://www\\.youtube\\.com/watch\\?v=|youtube\\.com/watch\\?v=|youtu\\.be" app/src/main/java/com/nexio/tv/ui app/src/main/java/com/nexio/tv/MainActivity.kt
```

Expected: no output.

Run:

```bash
rg -n "SkipIntroRepository" app/src/main/java/com/nexio/tv/ui/screens/player
```

Expected: no output.

Run:

```bash
rg -n "image\\.tmdb\\.org|media\\.kitsu\\.io|ratingposterdb|top-posters|topposters|fanart\\.tv|http://|https://" app/src/main/java/com/nexio/tv/ui app/src/main/java/com/nexio/tv/domain/model
```

Expected: output only from approved non-metadata links, tests excluded, or safe comments. No metadata artwork render path may pass a raw remote provider URL to Coil.

- [ ] **Step 6: Commit final audit report**

Run:

```bash
git add review-dossier/shared-resolution-bypass-audit.md \
  review-dossier/shared-resolution-bypass-audit.csv \
  review-dossier/SIGN-OFF.md
git commit -m "docs: close shared resolution bypass audit"
```

Expected: commit succeeds.

---

## Final Verification Checklist

Before marking the whole plan complete, verify:

```text
1. P0 account mutation scope remains covered by provider+credentialHash envelope tests.
2. P0 WatchProgress/CW scope remains covered by explicit profile/session API tests.
3. Detail display fields come from ResolvedDetailDisplayDocument.
4. Home hydrated display is published through ResolvedDisplaySurfaceRepository.
5. No UI/MainActivity code calls ensureTmdbId.
6. RatingResolver owns title and episode rating winner selection.
7. TrailerResolver owns availability and playback refs; TrailerService is transport only.
8. Player calls SkipSegmentResolver, not SkipIntroRepository.
9. Metadata UI Coil model values are typed artwork refs, safe projections, local/content/file URIs, placeholders, or resources.
10. LocalizationResolver owns same-provider language fallback.
11. Screensaver candidates carry typed artwork/trailer refs.
12. Audit markdown and CSV list zero confirmed open bypasses.
```

## Self-Review

Spec coverage:

```text
Metadata/detail/home ownership: Tasks 2, 3, 4, 5, 13.
Stable identity ownership: Task 6.
Rating ownership: Task 7.
Trailer ownership: Task 8.
Skip ownership: Task 9.
Artwork typed refs: Task 10.
Localization ownership: Task 11.
Screensaver compatibility cleanup: Task 12.
Audit gates/reporting: Tasks 1 and 13.
P0 bookkeeping: Task 13 fixed-finding rows.
```

Placeholder scan:

```text
The plan defines each task-owned type before use and avoids deferred implementation markers.
```

Type consistency:

```text
ResolvedDetailDisplayDocument is created before detail consumes it.
RatingResolver candidate/result types are created before repositories produce candidates.
TrailerPlaybackRef is created before TrailerService transport consumes it.
SkipSegmentResolver request/result types are created before player consumes them.
ArtworkDisplayRef stays the canonical artwork UI type across home/detail/search/screensaver.
```
