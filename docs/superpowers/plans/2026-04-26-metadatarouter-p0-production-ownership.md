# MetadataRouter P0 Production Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `MetadataRouterFacade` own actual metadata execution and final output for home, detail, player, and Continue Watching instead of acting as a sidecar around legacy metadata routers.

**Architecture:** Keep the existing deterministic routing rules unchanged. Add a runtime-backed provider-plan execution layer under `MetadataRouterFacade`, route all canonical provider responses into `MetadataCandidate`s, and make `FieldResolver` the only component that builds final resolved metadata. Production callers must call the facade/repositories only; legacy provider routers may remain only as temporary adapter internals when explicitly wrapped and boundary-tested.

**Tech Stack:** Kotlin, Hilt, coroutines, JUnit, MockK, Gradle, OpenSpec, IntegrationRuntime, existing TMDB/TVDB/Kitsu integration providers.

---

## Scope And Non-Negotiables

This is a P0 remediation, not a redesign.

Do not change:
- `MetadataRouter` routing precedence.
- Anime prefix rules.
- `IdMappingStore -> AnimeIdentityIndex` lookup order.
- The rule that `kitsu:` is direct and must not go through Fribb.
- The rule that `tmdb:` and `tvdb:` are provider-native and must not be sent to `AnimeIdentityIndex`.

Do change:
- Make `MetadataRouterFacade` execute plans and return final resolved output.
- Stop UI/ViewModel/Worker metadata paths from calling legacy provider routers/services as final metadata sources.
- Enforce PREVIEW as addon-only.
- Add provider-native identity resolution before plan execution.
- Use `FieldResolver` for final metadata construction.
- Complete Continue Watching route-version lifecycle.
- Strengthen architecture tests so sidecar implementations fail.

## File Structure

Create:
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataProviderAdapter.kt`  
  Interface implemented by TMDB, TVDB, and Kitsu adapters. Executes a single `ProviderPlanStep` and returns candidates/identity data.
- `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanRunner.kt`  
  Executes `ProviderExecutionPlan` by dispatching each step to the matching adapter and fails on unmapped steps.
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataExecutionModels.kt`  
  Runtime execution result types: `ProviderStepResult`, `MetadataResolutionResult`, `MetadataRouteFailure`, and trace entries.
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataIdentityResolver.kt`  
  Dedicated identity-resolution owner for provider-native conflicts.
- `app/src/main/java/com/nexio/tv/data/integration/metadata/TmdbMetadataProviderAdapter.kt`  
  Adapter around `TmdbIntegrationProvider`.
- `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt`  
  Adapter around `TvdbIntegrationProvider`.
- `app/src/main/java/com/nexio/tv/data/integration/metadata/KitsuMetadataProviderAdapter.kt`  
  Adapter around `KitsuIntegrationProvider`.
- `app/src/main/java/com/nexio/tv/core/di/MetadataExecutionModule.kt`  
  Hilt multibinding for provider adapters and `ProviderPlanRunner`.
- `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeExecutionTest.kt`
- `app/src/test/java/com/nexio/tv/core/metadata/router/ProviderPlanRunnerTest.kt`
- `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataIdentityResolverTest.kt`
- `app/src/test/java/com/nexio/tv/architecture/MetadataProductionBoundaryTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingRouteLifecycleTest.kt`

Modify:
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt`
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt`
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt`
- `app/src/test/java/com/nexio/tv/architecture/MetadataRouterBoundaryTest.kt`
- `openspec/changes/add-metadata-router/tasks.md`

## Task 0: OpenSpec And Regression Baseline

**Files:**
- Modify: `openspec/changes/add-metadata-router/tasks.md`
- Verify: `openspec/changes/add-metadata-router/specs/metadata-router/spec.md`

- [ ] **Step 1: Add P0 ownership checklist to OpenSpec tasks**

Append this section to `openspec/changes/add-metadata-router/tasks.md`:

```markdown
## P0 Production Ownership Remediation

- [ ] MetadataRouterFacade executes ProviderExecutionPlan through runtime-backed adapters.
- [ ] ProviderMetadataRouter/TvMetadataRouter are not invoked by MetadataRouterFacade.
- [ ] Provider-native conflicts are identity-resolved before ProviderPlanExecutor builds provider calls.
- [ ] PREVIEW depth is addon-only and does not call router/provider/network paths.
- [ ] FieldResolver creates final resolved metadata output for home/detail/player/CW paths.
- [ ] UI/ViewModel/Worker metadata paths do not call TmdbMetadataService, KitsuMetadataService, TvdbMetadataService, ProviderMetadataRouter, TvMetadataRouter, Retrofit APIs, auth services, or OkHttp for final metadata output.
- [ ] Continue Watching stale routing versions reroute once and persist upgraded snapshots.
- [ ] Architecture tests fail on legacy metadata execution paths.
```

- [ ] **Step 2: Validate OpenSpec before code changes**

Run:

```bash
openspec validate add-metadata-router --strict
```

Expected:

```text
Change 'add-metadata-router' is valid
```

- [ ] **Step 3: Commit OpenSpec task clarification**

Run:

```bash
git add openspec/changes/add-metadata-router/tasks.md
git commit -m "docs(metadata): define router production ownership remediation"
```

## Task 1: Add Failing Boundary Tests For Sidecar Implementations

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/architecture/MetadataRouterBoundaryTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/MetadataProductionBoundaryTest.kt`

- [ ] **Step 1: Strengthen existing facade boundary test**

In `MetadataRouterBoundaryTest.kt`, replace the first test body with:

```kotlin
@Test
fun `production callers use metadata router facade instead of legacy metadata execution`() {
    val offenders = productionRegexScan(
        forbiddenPatterns = mapOf(
            "TvMetadataRouter" to Regex("""\bTvMetadataRouter\b"""),
            "ProviderMetadataRouter" to Regex("""\bProviderMetadataRouter\b"""),
            "TmdbMetadataService" to Regex("""\bTmdbMetadataService\b"""),
            "KitsuMetadataService" to Regex("""\bKitsuMetadataService\b"""),
            "TvdbMetadataService" to Regex("""\bTvdbMetadataService\b""")
        ),
        allowedPaths = productionAllowedPathSuffixes(
            "/com/nexio/tv/core/tvdb/ProviderMetadataRouter.kt",
            "/com/nexio/tv/core/tvdb/TvMetadataRouter.kt",
            "/com/nexio/tv/core/tmdb/TmdbMetadataService.kt",
            "/com/nexio/tv/core/anime/KitsuMetadataService.kt",
            "/com/nexio/tv/data/integration/metadata/TmdbMetadataProviderAdapter.kt",
            "/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt",
            "/com/nexio/tv/data/integration/metadata/KitsuMetadataProviderAdapter.kt"
        )
    )

    if (offenders.isNotEmpty()) {
        fail("Production metadata paths must not call legacy metadata execution directly: $offenders")
    }
}
```

- [ ] **Step 2: Add UI/ViewModel/Worker-specific architecture test**

Create `MetadataProductionBoundaryTest.kt`:

```kotlin
package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.fail
import org.junit.Test

class MetadataProductionBoundaryTest {
    private val metadataCallerRoots = listOf(
        File("app/src/main/java/com/nexio/tv/ui"),
        File("app/src/main/java/com/nexio/tv/data/repository")
    )

    @Test
    fun `metadata ui repository paths do not import legacy provider execution`() {
        val forbidden = linkedMapOf(
            "ProviderMetadataRouter" to Regex("""\bProviderMetadataRouter\b"""),
            "TvMetadataRouter" to Regex("""\bTvMetadataRouter\b"""),
            "TmdbMetadataService" to Regex("""\bTmdbMetadataService\b"""),
            "KitsuMetadataService" to Regex("""\bKitsuMetadataService\b"""),
            "TvdbMetadataService" to Regex("""\bTvdbMetadataService\b"""),
            "TmdbApi" to Regex("""\bTmdbApi\b"""),
            "TvdbApi" to Regex("""\bTvdbApi\b"""),
            "KitsuApi" to Regex("""\bKitsuApi\b"""),
            "OkHttpClient" to Regex("""\bOkHttpClient\b"""),
            "Retrofit" to Regex("""\bRetrofit\b""")
        )

        val allowedSuffixes = setOf(
            "/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt"
        )

        val offenders = metadataCallerRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { file -> allowedSuffixes.any { file.invariantSeparatorsPath.endsWith(it) } }
                .flatMap { file ->
                    val content = file.readText()
                    forbidden
                        .filterValues { it.containsMatchIn(content) }
                        .keys
                        .map { "${file.invariantSeparatorsPath}:$it" }
                }
        }

        if (offenders.isNotEmpty()) {
            fail("Legacy provider execution is forbidden in production metadata callers: $offenders")
        }
    }
}
```

- [ ] **Step 3: Run boundary tests and verify they fail**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.architecture.MetadataRouterBoundaryTest --tests com.nexio.tv.architecture.MetadataProductionBoundaryTest
```

Expected:

```text
FAILED
Legacy provider execution is forbidden
```

- [ ] **Step 4: Commit failing boundary tests**

Run:

```bash
git add app/src/test/java/com/nexio/tv/architecture/MetadataRouterBoundaryTest.kt app/src/test/java/com/nexio/tv/architecture/MetadataProductionBoundaryTest.kt
git commit -m "test(metadata): expose legacy metadata execution paths"
```

## Task 2: Define Execution Result Models

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataExecutionModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverTest.kt`

- [ ] **Step 1: Add execution model file**

Create `MetadataExecutionModels.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.HomeDisplayMetadata

data class ProviderStepResult(
    val step: ProviderPlanStep,
    val candidate: MetadataCandidate? = null,
    val trace: List<RouteTrace> = emptyList()
)

data class ProviderPlanRunResult(
    val route: MetadataRoute,
    val depth: MetadataDepth,
    val primaryCandidate: MetadataCandidate,
    val secondaryCandidates: List<MetadataCandidate>,
    val stepResults: List<ProviderStepResult>,
    val trace: List<RouteTrace>
)

data class MetadataResolutionResult(
    val route: MetadataRoute?,
    val plan: ProviderExecutionPlan?,
    val resolverSchedule: ResolverSchedule,
    val resolvedDocument: ResolvedMetadataDocument,
    val displayMetadata: HomeDisplayMetadata,
    val trace: List<RouteTrace>
)

sealed class MetadataRouteFailure(message: String) : RuntimeException(message) {
    class IdentityResolutionFailed(parentId: String, provider: MetadataPrimaryProvider) :
        MetadataRouteFailure("Identity resolution failed for $parentId before $provider execution")

    class MissingPlanStepAdapter(apiShapeId: String) :
        MetadataRouteFailure("No metadata provider adapter mapped apiShapeId=$apiShapeId")
}
```

- [ ] **Step 2: Add field source trace to resolved document**

Modify `ResolvedMetadataDocument` in `MetadataModels.kt` to include ignored overwrites:

```kotlin
data class ResolvedMetadataDocument(
    val provider: MetadataPrimaryProvider,
    val title: String?,
    val description: String?,
    val poster: String?,
    val backdrop: String?,
    val rating: String?,
    val runtimeMinutes: Int?,
    val fieldSources: Map<String, MetadataPrimaryProvider>,
    val ignoredOverwrites: List<String> = emptyList()
)
```

- [ ] **Step 3: Update `FieldResolver` construction**

Update `FieldResolver.resolve` return construction:

```kotlin
return ResolvedMetadataDocument(
    provider = primary.provider,
    title = fields["title"] as? String,
    description = fields["description"] as? String,
    poster = fields["poster"] as? String,
    backdrop = fields["backdrop"] as? String,
    rating = fields["rating"] as? String,
    runtimeMinutes = fields["runtimeMinutes"] as? Int,
    fieldSources = fieldSources,
    ignoredOverwrites = ignoredOverwrites
)
```

Also add this local mutable list before secondary merging:

```kotlin
val ignoredOverwrites = mutableListOf<String>()
```

And inside the branch where a secondary field is ignored:

```kotlin
ignoredOverwrites += "${candidate.provider.name}:$key"
```

- [ ] **Step 4: Add ignored overwrite test**

Add to `FieldResolverTest.kt`:

```kotlin
@Test
fun `secondary overwrite is traced when primary owns field`() {
    val primary = MetadataCandidate(
        provider = MetadataPrimaryProvider.TMDB,
        fields = mapOf("title" to "Primary Title")
    )
    val secondary = MetadataCandidate(
        provider = MetadataPrimaryProvider.KITSU,
        fields = mapOf("title" to "Secondary Title")
    )

    val result = resolver.resolve(primary = primary, secondary = listOf(secondary))

    assertEquals("Primary Title", result.title)
    assertEquals(listOf("KITSU:title"), result.ignoredOverwrites)
}
```

- [ ] **Step 5: Run FieldResolver tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.metadata.router.FieldResolverTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 6: Commit execution models**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataExecutionModels.kt app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverTest.kt
git commit -m "feat(metadata): add router execution result models"
```

## Task 3: Add ProviderPlanRunner And Adapter Contract

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataProviderAdapter.kt`
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanRunner.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/ProviderPlanRunnerTest.kt`

- [ ] **Step 1: Write failing ProviderPlanRunner tests**

Create `ProviderPlanRunnerTest.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
import org.junit.Test

class ProviderPlanRunnerTest {
    @Test
    fun `provider plan steps are executed through mapped adapters`() = runTest {
        val step = ProviderPlanStep(
            apiShapeId = "tmdb.movie.core",
            provider = MetadataPrimaryProvider.TMDB,
            role = ProviderPlanRole.PRIMARY_CORE
        )
        val route = route(provider = MetadataPrimaryProvider.TMDB)
        val runner = ProviderPlanRunner(
            adapters = setOf(FakeAdapter(step.apiShapeId, MetadataPrimaryProvider.TMDB))
        )

        val result = runner.run(
            ProviderExecutionPlan(route = route, depth = MetadataDepth.DETAIL_CORE, steps = listOf(step))
        )

        assertEquals(MetadataPrimaryProvider.TMDB, result.primaryCandidate.provider)
        assertEquals("tmdb.movie.core", result.stepResults.single().step.apiShapeId)
    }

    @Test
    fun `missing plan step adapter mapping fails test`() = runTest {
        val step = ProviderPlanStep(
            apiShapeId = "tmdb.movie.core",
            provider = MetadataPrimaryProvider.TMDB,
            role = ProviderPlanRole.PRIMARY_CORE
        )
        val runner = ProviderPlanRunner(adapters = emptySet())

        assertFailsWith<MetadataRouteFailure.MissingPlanStepAdapter> {
            runner.run(
                ProviderExecutionPlan(
                    route = route(provider = MetadataPrimaryProvider.TMDB),
                    depth = MetadataDepth.DETAIL_CORE,
                    steps = listOf(step)
                )
            )
        }
    }

    private fun route(provider: MetadataPrimaryProvider) = MetadataRoute(
        provider = provider,
        parentId = "tmdb:550",
        targetId = "550",
        mediaKind = MetadataMediaKind.MOVIE,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_MATCH,
        targetIds = mapOf(provider to "550"),
        trace = emptyList()
    )

    private class FakeAdapter(
        private val supportedShape: String,
        override val provider: MetadataPrimaryProvider
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId == supportedShape

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
            return ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = provider,
                    fields = mapOf("title" to "Adapter Title")
                )
            )
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.metadata.router.ProviderPlanRunnerTest
```

Expected:

```text
Unresolved reference 'ProviderPlanRunner'
Unresolved reference 'MetadataProviderAdapter'
```

- [ ] **Step 3: Add adapter contract**

Create `MetadataProviderAdapter.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

interface MetadataProviderAdapter {
    val provider: MetadataPrimaryProvider

    fun supports(step: ProviderPlanStep): Boolean

    suspend fun execute(
        route: MetadataRoute,
        step: ProviderPlanStep
    ): ProviderStepResult
}
```

- [ ] **Step 4: Add ProviderPlanRunner**

Create `ProviderPlanRunner.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderPlanRunner @Inject constructor(
    private val adapters: Set<@JvmSuppressWildcards MetadataProviderAdapter>
) {
    suspend fun run(plan: ProviderExecutionPlan): ProviderPlanRunResult {
        val stepResults = plan.steps.map { step ->
            val adapter = adapters.firstOrNull { it.provider == step.provider && it.supports(step) }
                ?: throw MetadataRouteFailure.MissingPlanStepAdapter(step.apiShapeId)
            adapter.execute(route = plan.route, step = step)
        }

        val candidates = stepResults.mapNotNull { it.candidate }
        val primary = candidates.firstOrNull { candidate -> candidate.provider == plan.route.provider }
            ?: MetadataCandidate(provider = plan.route.provider, fields = emptyMap())
        val secondary = candidates.filterNot { candidate -> candidate === primary }

        return ProviderPlanRunResult(
            route = plan.route,
            depth = plan.depth,
            primaryCandidate = primary,
            secondaryCandidates = secondary,
            stepResults = stepResults,
            trace = plan.route.trace + stepResults.flatMap { it.trace }
        )
    }
}
```

- [ ] **Step 5: Run ProviderPlanRunner tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.metadata.router.ProviderPlanRunnerTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 6: Commit plan runner**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataProviderAdapter.kt app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanRunner.kt app/src/test/java/com/nexio/tv/core/metadata/router/ProviderPlanRunnerTest.kt
git commit -m "feat(metadata): execute provider plans through adapters"
```

## Task 4: Add Runtime-Backed Provider Adapters

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/metadata/TmdbMetadataProviderAdapter.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/metadata/KitsuMetadataProviderAdapter.kt`
- Create: `app/src/main/java/com/nexio/tv/core/di/MetadataExecutionModule.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataProviderAdapterShapeTest.kt`

- [ ] **Step 1: Add adapter shape coverage test**

Create `MetadataProviderAdapterShapeTest.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.TvdbApiShapes
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataProviderAdapterShapeTest {
    @Test
    fun `all provider plan shapes have adapter mappings`() {
        val mappedShapes = MetadataProviderAdapterShapeRegistry.all
        val required = setOf(
            TmdbApiShapes.MOVIE_CORE,
            TmdbApiShapes.TV_CORE,
            TmdbApiShapes.SEASON_EPISODES,
            TmdbApiShapes.MOVIE_VIDEOS,
            TmdbApiShapes.TV_VIDEOS,
            TmdbApiShapes.MOVIE_REVIEWS,
            TmdbApiShapes.TV_REVIEWS,
            TmdbApiShapes.MOVIE_RECOMMENDATIONS,
            TmdbApiShapes.TV_RECOMMENDATIONS,
            TvdbApiShapes.SERIES_EXTENDED,
            TvdbApiShapes.SERIES_TRANSLATION,
            TvdbApiShapes.SERIES_EPISODES_SEASON_TYPE,
            TvdbApiShapes.SERIES_EPISODES_LANGUAGE,
            TvdbApiShapes.EPISODE_TRANSLATION,
            KitsuApiShapes.ANIME_CORE,
            KitsuApiShapes.ANIME_EPISODES,
            KitsuApiShapes.CASTINGS,
            KitsuApiShapes.ANIME_STAFF,
            KitsuApiShapes.ANIME_PRODUCTIONS,
            KitsuApiShapes.MEDIA_RELATIONSHIPS
        )

        assertTrue("Missing adapter mappings: ${required - mappedShapes}", mappedShapes.containsAll(required))
    }
}
```

- [ ] **Step 2: Add static shape registry**

Create `MetadataProviderAdapterShapeRegistry.kt` in `core/metadata/router`:

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.TvdbApiShapes

object MetadataProviderAdapterShapeRegistry {
    val all: Set<String> = setOf(
        TmdbApiShapes.MOVIE_CORE,
        TmdbApiShapes.TV_CORE,
        TmdbApiShapes.SEASON_EPISODES,
        TmdbApiShapes.MOVIE_VIDEOS,
        TmdbApiShapes.TV_VIDEOS,
        TmdbApiShapes.MOVIE_REVIEWS,
        TmdbApiShapes.TV_REVIEWS,
        TmdbApiShapes.MOVIE_RECOMMENDATIONS,
        TmdbApiShapes.TV_RECOMMENDATIONS,
        TvdbApiShapes.SERIES_EXTENDED,
        TvdbApiShapes.SERIES_TRANSLATION,
        TvdbApiShapes.SERIES_EPISODES_SEASON_TYPE,
        TvdbApiShapes.SERIES_EPISODES_LANGUAGE,
        TvdbApiShapes.EPISODE_TRANSLATION,
        KitsuApiShapes.ANIME_CORE,
        KitsuApiShapes.ANIME_EPISODES,
        KitsuApiShapes.CASTINGS,
        KitsuApiShapes.ANIME_STAFF,
        KitsuApiShapes.ANIME_PRODUCTIONS,
        KitsuApiShapes.MEDIA_RELATIONSHIPS
    )
}
```

- [ ] **Step 3: Implement TMDB adapter**

Create `TmdbMetadataProviderAdapter.kt`:

```kotlin
package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import javax.inject.Inject

class TmdbMetadataProviderAdapter @Inject constructor(
    private val provider: TmdbIntegrationProvider
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in tmdbShapes

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val tmdbId = route.targetId.toIntOrNull()
            ?: return ProviderStepResult(step = step, candidate = MetadataCandidate(provider = this.provider, fields = emptyMap()))
        val candidate = when (step.apiShapeId) {
            TmdbApiShapes.MOVIE_CORE -> provider.fetchMovieCore(tmdbId, route.language.orEmpty()).toCandidate()
            TmdbApiShapes.TV_CORE -> provider.fetchTvDetails(tmdbId, route.language.orEmpty()).toCandidate()
            TmdbApiShapes.SEASON_EPISODES -> provider.loadTvSeasonEpisodes(tmdbId, route.seasonNumber ?: 1, route.language.orEmpty()).toCandidate()
            TmdbApiShapes.MOVIE_VIDEOS -> provider.fetchMovieVideos(tmdbId, route.language.orEmpty()).toCandidate()
            TmdbApiShapes.TV_VIDEOS -> provider.fetchTvVideos(tmdbId, route.language.orEmpty()).toCandidate()
            TmdbApiShapes.MOVIE_REVIEWS -> provider.fetchMovieReviews(tmdbId, route.language.orEmpty()).toCandidate()
            TmdbApiShapes.TV_REVIEWS -> provider.fetchTvReviews(tmdbId, route.language.orEmpty()).toCandidate()
            TmdbApiShapes.MOVIE_RECOMMENDATIONS -> provider.fetchMovieRecommendations(tmdbId, route.language.orEmpty()).toCandidate()
            TmdbApiShapes.TV_RECOMMENDATIONS -> provider.fetchTvRecommendations(tmdbId, route.language.orEmpty()).toCandidate()
            else -> MetadataCandidate(provider = this.provider, fields = emptyMap())
        }
        return ProviderStepResult(step = step, candidate = candidate)
    }

    private fun Any?.toCandidate(): MetadataCandidate =
        MetadataCandidate(provider = provider, fields = emptyMap())

    private companion object {
        val tmdbShapes = setOf(
            TmdbApiShapes.MOVIE_CORE,
            TmdbApiShapes.TV_CORE,
            TmdbApiShapes.SEASON_EPISODES,
            TmdbApiShapes.MOVIE_VIDEOS,
            TmdbApiShapes.TV_VIDEOS,
            TmdbApiShapes.MOVIE_REVIEWS,
            TmdbApiShapes.TV_REVIEWS,
            TmdbApiShapes.MOVIE_RECOMMENDATIONS,
            TmdbApiShapes.TV_RECOMMENDATIONS
        )
    }
}
```

Before committing this task, replace each `toCandidate()` empty-map conversion for primary core responses with field extraction supported by the response type. Minimum fields for core steps:

```kotlin
mapOf(
    "title" to title,
    "description" to overview,
    "poster" to posterUrl,
    "backdrop" to backdropUrl,
    "rating" to voteAverageString,
    "runtimeMinutes" to runtimeMinutes
)
```

- [ ] **Step 4: Implement TVDB adapter**

Create `TvdbMetadataProviderAdapter.kt`:

```kotlin
package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import javax.inject.Inject

class TvdbMetadataProviderAdapter @Inject constructor(
    private val provider: TvdbIntegrationProvider
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TVDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in tvdbShapes

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val tvdbId = route.targetId.toIntOrNull()
            ?: return ProviderStepResult(step = step, candidate = MetadataCandidate(provider = this.provider, fields = emptyMap()))
        val candidate = when (step.apiShapeId) {
            TvdbApiShapes.SERIES_EXTENDED -> provider.fetchSeriesExtended(tvdbId).toCandidate()
            TvdbApiShapes.SERIES_TRANSLATION -> provider.fetchSeriesTranslation(tvdbId, route.language.orEmpty()).toCandidate()
            TvdbApiShapes.SERIES_EPISODES_SEASON_TYPE -> provider.fetchSeriesEpisodes(tvdbId, route.seasonNumber ?: 1).toCandidate()
            TvdbApiShapes.SERIES_EPISODES_LANGUAGE -> provider.fetchSeriesEpisodesTranslated(tvdbId, route.seasonNumber ?: 1, route.language.orEmpty()).toCandidate()
            TvdbApiShapes.EPISODE_TRANSLATION -> MetadataCandidate(provider = this.provider, fields = emptyMap())
            else -> MetadataCandidate(provider = this.provider, fields = emptyMap())
        }
        return ProviderStepResult(step = step, candidate = candidate)
    }

    private fun Any?.toCandidate(): MetadataCandidate =
        MetadataCandidate(provider = provider, fields = emptyMap())

    private companion object {
        val tvdbShapes = setOf(
            TvdbApiShapes.SERIES_EXTENDED,
            TvdbApiShapes.SERIES_TRANSLATION,
            TvdbApiShapes.SERIES_EPISODES_SEASON_TYPE,
            TvdbApiShapes.SERIES_EPISODES_LANGUAGE,
            TvdbApiShapes.EPISODE_TRANSLATION
        )
    }
}
```

- [ ] **Step 5: Implement Kitsu adapter**

Create `KitsuMetadataProviderAdapter.kt`:

```kotlin
package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.data.integration.kitsu.KitsuIntegrationProvider
import javax.inject.Inject

class KitsuMetadataProviderAdapter @Inject constructor(
    private val provider: KitsuIntegrationProvider
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.KITSU

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in kitsuShapes

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val candidate = when (step.apiShapeId) {
            KitsuApiShapes.ANIME_CORE -> provider.fetchEnrichment(route.parentId, route.targetId, route.mediaKind.toContentMediaKind()).toCandidate()
            KitsuApiShapes.ANIME_EPISODES -> provider.fetchEpisodeEnrichment(route.parentId, route.targetId, route.mediaKind.toContentMediaKind()).toCandidate()
            KitsuApiShapes.CASTINGS -> provider.fetchCastings(route.targetId).toCandidate()
            KitsuApiShapes.ANIME_STAFF -> provider.fetchAnimeStaff(route.targetId).toCandidate()
            KitsuApiShapes.ANIME_PRODUCTIONS -> provider.fetchAnimeProductions(route.targetId).toCandidate()
            KitsuApiShapes.MEDIA_RELATIONSHIPS -> provider.fetchMediaRelationships(route.targetId).toCandidate()
            else -> MetadataCandidate(provider = this.provider, fields = emptyMap())
        }
        return ProviderStepResult(step = step, candidate = candidate)
    }

    private fun MetadataMediaKind.toContentMediaKind(): com.nexio.tv.domain.model.ContentMediaKind =
        if (this == MetadataMediaKind.MOVIE) {
            com.nexio.tv.domain.model.ContentMediaKind.MOVIE
        } else {
            com.nexio.tv.domain.model.ContentMediaKind.SERIES
        }

    private fun Any?.toCandidate(): MetadataCandidate =
        MetadataCandidate(provider = provider, fields = emptyMap())

    private companion object {
        val kitsuShapes = setOf(
            KitsuApiShapes.ANIME_CORE,
            KitsuApiShapes.ANIME_EPISODES,
            KitsuApiShapes.CASTINGS,
            KitsuApiShapes.ANIME_STAFF,
            KitsuApiShapes.ANIME_PRODUCTIONS,
            KitsuApiShapes.MEDIA_RELATIONSHIPS
        )
    }
}
```

- [ ] **Step 6: Bind adapters with Hilt multibinding**

Create `MetadataExecutionModule.kt`:

```kotlin
package com.nexio.tv.core.di

import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.data.integration.metadata.KitsuMetadataProviderAdapter
import com.nexio.tv.data.integration.metadata.TmdbMetadataProviderAdapter
import com.nexio.tv.data.integration.metadata.TvdbMetadataProviderAdapter
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class MetadataExecutionModule {
    @Binds
    @IntoSet
    abstract fun bindTmdbAdapter(impl: TmdbMetadataProviderAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindTvdbAdapter(impl: TvdbMetadataProviderAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindKitsuAdapter(impl: KitsuMetadataProviderAdapter): MetadataProviderAdapter
}
```

- [ ] **Step 7: Run compile and adapter shape test**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataProviderAdapterShapeTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 8: Commit adapters**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/integration/metadata app/src/main/java/com/nexio/tv/core/di/MetadataExecutionModule.kt app/src/main/java/com/nexio/tv/core/metadata/router/MetadataProviderAdapterShapeRegistry.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataProviderAdapterShapeTest.kt
git commit -m "feat(metadata): add runtime-backed provider adapters"
```

## Task 5: Add Provider-Native Identity Resolution Owner

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataIdentityResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataIdentityResolverTest.kt`

- [ ] **Step 1: Write identity resolver tests**

Create `MetadataIdentityResolverTest.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataIdentityResolverTest {
    @Test
    fun `tmdb series conflict resolves tvdb target before execution`() = runTest {
        val resolver = MetadataIdentityResolver(
            lookup = FakeLookup(tmdbToTvdb = mapOf("1399" to "121361"), tvdbToTmdb = emptyMap())
        )
        val route = conflictRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tmdb:1399",
            targetId = "tmdb:1399",
            mediaKind = MetadataMediaKind.SERIES
        )

        val resolved = resolver.resolve(route)

        assertFalse(resolved.targetIdRequiresIdentityResolution)
        assertEquals("121361", resolved.targetId)
    }

    @Test
    fun `tvdb movie conflict resolves tmdb target before execution`() = runTest {
        val resolver = MetadataIdentityResolver(
            lookup = FakeLookup(tmdbToTvdb = emptyMap(), tvdbToTmdb = mapOf("121361" to "550"))
        )
        val route = conflictRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tvdb:121361",
            targetId = "tvdb:121361",
            mediaKind = MetadataMediaKind.MOVIE
        )

        val resolved = resolver.resolve(route)

        assertFalse(resolved.targetIdRequiresIdentityResolution)
        assertEquals("550", resolved.targetId)
    }

    @Test
    fun `unresolved conflict remains marked unresolved`() = runTest {
        val resolver = MetadataIdentityResolver(FakeLookup(emptyMap(), emptyMap()))
        val route = conflictRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tmdb:1399",
            targetId = "tmdb:1399",
            mediaKind = MetadataMediaKind.SERIES
        )

        val resolved = resolver.resolve(route)

        assertTrue(resolved.targetIdRequiresIdentityResolution)
    }

    private fun conflictRoute(
        provider: MetadataPrimaryProvider,
        parentId: String,
        targetId: String,
        mediaKind: MetadataMediaKind
    ) = MetadataRoute(
        provider = provider,
        parentId = parentId,
        targetId = targetId,
        mediaKind = mediaKind,
        reason = MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT,
        targetIds = mapOf(provider to targetId),
        trace = emptyList(),
        targetIdRequiresIdentityResolution = true
    )

    private class FakeLookup(
        private val tmdbToTvdb: Map<String, String>,
        private val tvdbToTmdb: Map<String, String>
    ) : MetadataIdentityResolver.Lookup {
        override suspend fun tmdbToTvdb(tmdbId: String): String? = tmdbToTvdb[tmdbId]
        override suspend fun tvdbToTmdb(tvdbId: String): String? = tvdbToTmdb[tvdbId]
    }
}
```

- [ ] **Step 2: Implement identity resolver**

Create `MetadataIdentityResolver.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataIdentityResolver @Inject constructor(
    private val lookup: Lookup
) {
    interface Lookup {
        suspend fun tmdbToTvdb(tmdbId: String): String?
        suspend fun tvdbToTmdb(tvdbId: String): String?
    }

    suspend fun resolve(route: MetadataRoute): MetadataRoute {
        if (!route.targetIdRequiresIdentityResolution) return route
        val parsed = MetadataRequestNormalizer.parseId(route.parentId)
        val resolvedTarget = when {
            parsed.scheme == AnimeIdScheme.TMDB && route.provider == MetadataPrimaryProvider.TVDB ->
                lookup.tmdbToTvdb(parsed.value)
            parsed.scheme == AnimeIdScheme.TVDB && route.provider == MetadataPrimaryProvider.TMDB ->
                lookup.tvdbToTmdb(parsed.value)
            else -> null
        } ?: return route

        return route.copy(
            targetId = resolvedTarget,
            targetIds = route.targetIds + (route.provider to resolvedTarget),
            targetIdRequiresIdentityResolution = false,
            trace = route.trace + RouteTrace(
                reason = MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT,
                message = "provider-native conflict identity resolved for ${route.provider}"
            )
        )
    }
}
```

- [ ] **Step 3: Bind lookup implementation**

Create `app/src/main/java/com/nexio/tv/data/integration/metadata/RuntimeMetadataIdentityLookup.kt`:

```kotlin
package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataIdentityResolver
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import javax.inject.Inject

class RuntimeMetadataIdentityLookup @Inject constructor(
    private val tmdbProvider: TmdbIntegrationProvider,
    private val tvdbProvider: TvdbIntegrationProvider
) : MetadataIdentityResolver.Lookup {
    override suspend fun tmdbToTvdb(tmdbId: String): String? {
        val imdbId = tmdbProvider.findImdbIdByTmdbId(tmdbId.toIntOrNull() ?: return null, "tv") ?: return null
        return tvdbProvider.searchByRemoteId(imdbId)
            ?.data
            ?.firstOrNull()
            ?.tvdbId
            ?.toString()
    }

    override suspend fun tvdbToTmdb(tvdbId: String): String? {
        val series = tvdbProvider.fetchSeriesExtended(tvdbId.toIntOrNull() ?: return null)
        val imdbId = series?.remoteIds?.firstOrNull { it.sourceName.equals("IMDB", ignoreCase = true) }?.id
            ?: return null
        return tmdbProvider.findTmdbIdByImdbId(imdbId, "movie")?.toString()
    }
}
```

Bind it in `MetadataExecutionModule.kt`:

```kotlin
@Binds
abstract fun bindIdentityLookup(impl: RuntimeMetadataIdentityLookup): MetadataIdentityResolver.Lookup
```

- [ ] **Step 4: Run identity tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataIdentityResolverTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit identity resolver**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataIdentityResolver.kt app/src/main/java/com/nexio/tv/data/integration/metadata/RuntimeMetadataIdentityLookup.kt app/src/main/java/com/nexio/tv/core/di/MetadataExecutionModule.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataIdentityResolverTest.kt
git commit -m "feat(metadata): resolve provider-native route conflicts"
```

## Task 6: Make MetadataRouterFacade Authoritative

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeExecutionTest.kt`

- [ ] **Step 1: Write facade execution tests**

Create `MetadataRouterFacadeExecutionTest.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.HomeDisplayMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataRouterFacadeExecutionTest {
    @Test
    fun `metadata facade executes provider plan and resolves final metadata`() = runTest {
        val facade = testFacade(
            candidate = MetadataCandidate(
                provider = MetadataPrimaryProvider.TMDB,
                fields = mapOf("title" to "Canonical")
            )
        )

        val result = facade.resolveRequest(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(
                    addonMetadata = HomeDisplayMetadata(title = "Addon")
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertNotNull(result.route)
        assertNotNull(result.plan)
        assertEquals("Canonical", result.resolvedDocument.title)
        assertEquals("Canonical", result.displayMetadata.title)
    }

    @Test
    fun `preview returns addon metadata and does not route`() = runTest {
        val facade = testFacade(
            candidate = MetadataCandidate(MetadataPrimaryProvider.TMDB, emptyMap())
        )

        val result = facade.resolveRequest(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(addonMetadata = HomeDisplayMetadata(title = "Addon")),
                depth = MetadataDepth.PREVIEW
            )
        assertNull(result.route)
        assertNull(result.plan)
        assertEquals("Addon", result.displayMetadata.title)
    }

    private fun testFacade(candidate: MetadataCandidate = MetadataCandidate(MetadataPrimaryProvider.TMDB, mapOf("title" to "Canonical"))): MetadataRouterFacade =
        MetadataRouterFacade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(),
                idMappingStore = InMemoryIdMappingStore()
            ),
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator(),
            identityResolver = MetadataIdentityResolver(object : MetadataIdentityResolver.Lookup {
                override suspend fun tmdbToTvdb(tmdbId: String): String? = null
                override suspend fun tvdbToTmdb(tvdbId: String): String? = null
            }),
            providerPlanRunner = ProviderPlanRunner(setOf(FakeAdapter(candidate))),
            fieldResolver = FieldResolver()
        )

    private class FakeAdapter(
        private val candidate: MetadataCandidate
    ) : MetadataProviderAdapter {
        override val provider: MetadataPrimaryProvider = candidate.provider

        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
            ProviderStepResult(step = step, candidate = candidate)
    }
}
```

- [ ] **Step 2: Modify facade constructor and resolve flow**

Replace `MetadataRouterFacade` constructor parameters with:

```kotlin
class MetadataRouterFacade @Inject constructor(
    private val router: MetadataRouter,
    private val providerPlanExecutor: ProviderPlanExecutor,
    private val resolverOrchestrator: ResolverOrchestrator,
    private val identityResolver: MetadataIdentityResolver,
    private val providerPlanRunner: ProviderPlanRunner,
    private val fieldResolver: FieldResolver
)
```

Replace `resolveRequest` body with:

```kotlin
suspend fun resolveRequest(request: MetadataRequest): MetadataResolutionResult {
    val resolverSchedule = resolverOrchestrator.schedule(request.depth)
    val initialDisplay = request.sourceContext.addonMetadata ?: HomeDisplayMetadata()

    if (request.depth == MetadataDepth.PREVIEW) {
        val document = ResolvedMetadataDocument(
            provider = MetadataPrimaryProvider.TMDB,
            title = initialDisplay.title,
            description = initialDisplay.description,
            poster = initialDisplay.poster,
            backdrop = initialDisplay.background,
            rating = initialDisplay.rating,
            runtimeMinutes = initialDisplay.runtimeMinutes,
            fieldSources = emptyMap()
        )
        return MetadataResolutionResult(
            route = null,
            plan = null,
            resolverSchedule = resolverSchedule,
            resolvedDocument = document,
            displayMetadata = initialDisplay,
            trace = emptyList()
        )
    }

    val routed = router.route(request)
    val resolvedRoute = identityResolver.resolve(routed)
    if (resolvedRoute.targetIdRequiresIdentityResolution) {
        throw MetadataRouteFailure.IdentityResolutionFailed(resolvedRoute.parentId, resolvedRoute.provider)
    }

    val plan = providerPlanExecutor.buildPlan(route = resolvedRoute, depth = request.depth)
    val runResult = providerPlanRunner.run(plan)
    val resolvedDocument = fieldResolver.resolve(
        primary = runResult.primaryCandidate,
        secondary = runResult.secondaryCandidates
    )
    val displayMetadata = resolvedDocument.toHomeDisplayMetadata(initialDisplay)

    return MetadataResolutionResult(
        route = resolvedRoute,
        plan = plan,
        resolverSchedule = resolverSchedule,
        resolvedDocument = resolvedDocument,
        displayMetadata = displayMetadata,
        trace = runResult.trace
    )
}
```

Add private mapping:

```kotlin
private fun ResolvedMetadataDocument.toHomeDisplayMetadata(fallback: HomeDisplayMetadata): HomeDisplayMetadata =
    fallback.copy(
        title = title ?: fallback.title,
        description = description ?: fallback.description,
        poster = poster ?: fallback.poster,
        background = backdrop ?: fallback.background,
        rating = rating ?: fallback.rating,
        runtimeMinutes = runtimeMinutes ?: fallback.runtimeMinutes
    )
```

Delete `fetchTvEnrichment`, `fetchTvEpisodeEnrichment`, `fetchSeasonEpisodes`, and `requireProviderMetadataRouter` from the facade. Production callers must use `resolveRequest` or a new repository wrapper.

- [ ] **Step 3: Run facade execution tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeExecutionTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Commit authoritative facade**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeExecutionTest.kt
git commit -m "feat(metadata): make router facade execute provider plans"
```

## Task 7: Enforce PREVIEW As Addon-Only In Home Paths

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomePreviewRoutingBoundaryTest.kt`

- [ ] **Step 1: Add preview boundary test**

Create `HomePreviewRoutingBoundaryTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import java.io.File
import org.junit.Assert.fail
import org.junit.Test

class HomePreviewRoutingBoundaryTest {
    @Test
    fun `initial row render uses addon metadata only`() {
        val files = listOf(
            File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt"),
            File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt")
        )
        val offenders = files.flatMap { file ->
            val text = file.readText()
            val previewBlocks = Regex("""MetadataDepth\.PREVIEW[\s\S]{0,800}""").findAll(text).map { it.value }.toList()
            previewBlocks.filter { block ->
                block.contains("fetchTvEnrichment") ||
                    block.contains("tmdbMetadataService") ||
                    block.contains("getMetaFromAllAddons") ||
                    block.contains("IntegrationRuntime")
            }.map { file.path }
        }
        if (offenders.isNotEmpty()) {
            fail("PREVIEW blocks must not route or fetch provider metadata: $offenders")
        }
    }
}
```

- [ ] **Step 2: Remove PREVIEW sidecar route call from preview enrichment**

In `HomeViewModelPresentationPipeline.kt`, replace:

```kotlin
resolveHomeRequestIfAvailable(
    item = item,
    depth = MetadataDepth.PREVIEW,
    language = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag())
)
```

with:

```kotlin
// Initial preview render is addon-only. Router execution starts from explicit enrichment depths.
```

- [ ] **Step 3: Remove provider fetch from initial row render path**

In `HomeCatalogRefreshCoordinator.kt`, keep disk-cache reuse and image prefetch, but remove provider metadata fetch from the initial row render branch. Replace the `metaRepository.getMetaFromAllAddons` block with:

```kotlin
val externalMeta: Meta? = null
val merged = mergePersistedHomeDisplayMetadata(
    currentItem = item,
    persistedFallback = persistedFallback,
    externalMeta = externalMeta
)
```

- [ ] **Step 4: Convert explicit enrichment to non-preview only**

Keep `fetchProviderEnrichmentForPreview` only if it is renamed to `fetchProviderEnrichmentForVisibleItem` and called from visible-item or hero enrichment paths. Its `MetadataRequest.depth` must be `DETAIL_CORE`, not `PREVIEW`.

Use this signature:

```kotlin
internal suspend fun HomeViewModel.fetchProviderEnrichmentForVisibleItem(item: MetaPreview): HomeDisplayMetadata? {
    return metadataRouterFacade.resolveRequest(
        MetadataRequest(
            contentId = item.id,
            contentType = item.type,
            sourceContext = MetadataSourceContext(
                itemType = item.apiType,
                addonMetadata = item.toHomeDisplayMetadata()
            ),
            language = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag()),
            depth = MetadataDepth.DETAIL_CORE
        )
    ).displayMetadata
}
```

- [ ] **Step 5: Run preview tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomePreviewRoutingBoundaryTest --tests com.nexio.tv.core.metadata.router.MetadataRouterTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 6: Commit preview enforcement**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomePreviewRoutingBoundaryTest.kt
git commit -m "fix(metadata): keep preview rendering addon-only"
```

## Task 8: Migrate Home, Detail, Player, And CW Callers To Facade Output

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt`
- Test: `app/src/test/java/com/nexio/tv/architecture/MetadataProductionBoundaryTest.kt`

- [ ] **Step 1: Replace home overlay legacy TV/TMDB calls**

In `HomeProviderLocalizedMetadataOverlay.kt`, replace the body of `overlayProviderLocalizedMetadataForHome` with:

```kotlin
return try {
    val result = metadataRouterFacade.resolveRequest(
        MetadataRequest(
            contentId = item.id,
            contentType = item.type,
            sourceContext = MetadataSourceContext(
                itemType = item.apiType,
                addonMetadata = item.toHomeDisplayMetadata()
            ),
            language = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag()),
            depth = MetadataDepth.DETAIL_CORE
        )
    )
    item.applyHomeDisplayMetadata(result.displayMetadata)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    item
}
```

Add helper:

```kotlin
private fun MetaPreview.applyHomeDisplayMetadata(metadata: HomeDisplayMetadata): MetaPreview =
    copy(
        name = metadata.title ?: name,
        description = metadata.description ?: description,
        poster = metadata.poster ?: poster,
        background = metadata.background ?: background,
        imdbRating = metadata.rating?.toFloatOrNull() ?: imdbRating
    )
```

- [ ] **Step 2: Replace detail metadata merge with facade result**

In `MetaDetailsViewModel.enrichMeta`, use `metadataRouterFacade.resolveRequest` once and apply `displayMetadata` to `Meta`. Replace direct `tmdbMetadataService.fetchEnrichment` and `kitsuMetadataService.fetchAdvancedDetail` calls in that method with:

```kotlin
val resolution = metadataRouterFacade.resolveRequest(
    MetadataRequest(
        contentId = meta.id,
        contentType = tmdbContentType,
        sourceContext = MetadataSourceContext(
            itemType = tmdbContentType.toApiString(),
            addonMetadata = meta.toHomeDisplayMetadata()
        ),
        language = tvdbLanguage,
        depth = MetadataDepth.DETAIL_CORE
    )
)
val display = resolution.displayMetadata
var updated = meta.copy(
    name = display.title ?: meta.name,
    description = display.description ?: meta.description,
    poster = display.poster ?: meta.poster,
    background = display.background ?: meta.background,
    imdbRating = display.rating?.toFloatOrNull() ?: meta.imdbRating,
    runtime = display.runtimeMinutes?.toString() ?: meta.runtime
)
```

- [ ] **Step 3: Replace player metadata fetches**

In `PlayerRuntimeControllerMetadata.kt`, replace `fetchTvEnrichment` and `fetchTvEpisodeEnrichment` calls with:

```kotlin
val resolution = metadataRouterFacade.resolveRequest(
    MetadataRequest(
        contentId = lookupContentId,
        contentType = lookupContentType,
        sourceContext = MetadataSourceContext(itemType = lookupContentType.toApiString()),
        language = language,
        seasonNumber = currentSeason,
        depth = MetadataDepth.PLAYER
    )
)
val display = resolution.displayMetadata
```

Only apply display fields needed by the player UI. Do not call provider services from player metadata code.

- [ ] **Step 4: Replace CW runtime metadata fetches**

In `HomeViewModelContinueWatchingRuntimePipeline.kt`, replace `metadataRouterFacade.fetchTvEnrichment`, `fetchTvEpisodeEnrichment`, and direct TMDB metadata calls with `resolveRequest(... depth = DETAIL_CORE)` and read `displayMetadata.runtimeMinutes`.

Use:

```kotlin
val resolution = metadataRouterFacade.resolveRequest(
    MetadataRequest(
        contentId = contentId,
        contentType = parseContinueWatchingContentType(contentType),
        sourceContext = MetadataSourceContext(itemType = contentType),
        language = tvdbLanguage,
        depth = MetadataDepth.DETAIL_CORE
    )
)
return resolution.displayMetadata.runtimeMinutes
```

- [ ] **Step 5: Replace timing enricher legacy calls**

In `TvdbContinueWatchingTimingEnricher.kt`, replace `fetchTvEnrichment` and `fetchTvEpisodeEnrichment` with `resolveRequest` and use `displayMetadata.runtimeMinutes`.

- [ ] **Step 6: Run boundary tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.architecture.MetadataProductionBoundaryTest --tests com.nexio.tv.architecture.MetadataRouterBoundaryTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 7: Commit caller migration**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home app/src/main/java/com/nexio/tv/ui/screens/detail app/src/main/java/com/nexio/tv/ui/screens/player app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt app/src/test/java/com/nexio/tv/architecture
git commit -m "fix(metadata): route production metadata callers through facade"
```

## Task 9: Complete Continue Watching Route Lifecycle

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingRouteLifecycleTest.kt`

- [ ] **Step 1: Add CW lifecycle tests**

Create `ContinueWatchingRouteLifecycleTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.domain.model.HomeDisplayMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingRouteLifecycleTest {
    @Test
    fun `cw render uses canonical then click time then persisted fallback`() {
        val canonical = HomeDisplayMetadata(title = "Canonical")
        val clickTime = HomeDisplayMetadata(title = null, description = "Click")
        val fallback = HomeDisplayMetadata(title = "Fallback", poster = "poster")

        val result = ContinueWatchingMetadataSnapshot.renderDisplayMetadata(
            canonical = canonical,
            clickTime = clickTime,
            persistedFallback = fallback
        )

        assertEquals("Canonical", result.title)
        assertEquals("Click", result.description)
        assertEquals("poster", result.poster)
    }

    @Test
    fun `old route version requires reroute`() {
        assertEquals(true, ContinueWatchingMetadataSnapshot.shouldReroute(0))
        assertEquals(false, ContinueWatchingMetadataSnapshot.shouldReroute(ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION))
    }

    @Test
    fun `snapshot carries route ownership fields`() {
        val snapshot = ContinueWatchingMetadataSnapshot(
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            parentId = "kitsu:7442",
            primaryProvider = MetadataPrimaryProvider.KITSU,
            decisionReason = MetadataDecisionReason.KITSU_PREFIX_DIRECT,
            clickTimeDisplayMetadata = HomeDisplayMetadata(title = "Click")
        )

        assertEquals("kitsu:7442", snapshot.parentId)
        assertEquals(MetadataPrimaryProvider.KITSU, snapshot.primaryProvider)
        assertEquals("Click", snapshot.clickTimeDisplayMetadata.title)
    }
}
```

- [ ] **Step 2: Add reroute hook to snapshot hydration**

In `ContinueWatchingSnapshotService.hydrateDisplayMetadata`, before `fetchHomeDisplayMetadata`, check route versions:

```kotlin
val routeSnapshot = snapshot.metadataSnapshotsByItemKey[itemKey]
if (routeSnapshot != null && ContinueWatchingMetadataSnapshot.shouldReroute(routeSnapshot.routingVersion)) {
    rerouteContinueWatchingSnapshot(itemKey = itemKey, routeSnapshot = routeSnapshot, contentType = contentType, contentId = contentId)
}
```

Add private function:

```kotlin
private suspend fun rerouteContinueWatchingSnapshot(
    itemKey: String,
    routeSnapshot: ContinueWatchingMetadataSnapshot,
    contentType: String,
    contentId: String
) {
    val facade = metadataRouterFacade ?: return
    val result = facade.resolveRequest(
        MetadataRequest(
            contentId = routeSnapshot.parentId.ifBlank { contentId },
            contentType = ContentType.fromString(contentType),
            sourceContext = MetadataSourceContext(addonMetadata = routeSnapshot.clickTimeDisplayMetadata),
            depth = MetadataDepth.DETAIL_CORE
        )
    )
    val route = result.route ?: return
    recordMetadataSnapshot(
        itemKey = itemKey,
        metadataSnapshot = ContinueWatchingMetadataSnapshot.fromRoute(
            route = route,
            clickTimeDisplayMetadata = routeSnapshot.clickTimeDisplayMetadata
        )
    )
}
```

Inject nullable `MetadataRouterFacade` into `ContinueWatchingSnapshotService` if not already present. Use constructor default `null` only for tests.

- [ ] **Step 3: Ensure CW write uses parent route snapshot**

In `HomeViewModelContinueWatching.recordContinueWatchingRouteContextForPlayback`, keep existing route persistence but ensure `contentId = item.parentId()` when available:

```kotlin
val routeContentId = item.parentContentId().ifBlank { item.contentId() }
```

Then use `routeContentId` in `MetadataRequest.contentId`.

- [ ] **Step 4: Run CW tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingRouteLifecycleTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit CW lifecycle**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingRouteLifecycleTest.kt
git commit -m "fix(metadata): complete continue watching route lifecycle"
```

## Task 10: Final Production Boundary And Audit Verification

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/architecture/MetadataProductionBoundaryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/MetadataRouterBoundaryTest.kt`
- Verify: `app/build/reports/integration-runtime-audit/integration-runtime-audit.md`

- [ ] **Step 1: Add final forbidden direct fetch regex**

In `MetadataProductionBoundaryTest.kt`, add:

```kotlin
"legacyFetchEnrichment" to Regex("""\.(fetchEnrichment|fetchEpisodeEnrichment|fetchSeasonEpisodes)\(""")
```

Allow this regex only in:

```kotlin
"/com/nexio/tv/data/integration/metadata/"
```

- [ ] **Step 2: Run architecture tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.architecture.MetadataProductionBoundaryTest --tests com.nexio.tv.architecture.MetadataRouterBoundaryTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Run metadata router unit tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.metadata.router.*'
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Run compile gate**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Run integration runtime audit**

Run:

```bash
./gradlew :app:generateIntegrationRuntimeAudit
```

Expected:

```text
BUILD SUCCESSFUL
```

Then open `app/build/reports/integration-runtime-audit/integration-runtime-audit.md` and verify:

```text
Verdict: PASS
Control-plane gate: PASS
MetadataRouter-readiness gate: PASS or PASS_WITH_WARNINGS with only documented non-router planned inventory
direct bypass calls: 0
missing header policies: 0
missing operation keys: 0
active required endpoint shapes missing runtime spec: 0
```

- [ ] **Step 6: Commit final boundary verification**

Run:

```bash
git add app/src/test/java/com/nexio/tv/architecture/MetadataProductionBoundaryTest.kt app/src/test/java/com/nexio/tv/architecture/MetadataRouterBoundaryTest.kt
git commit -m "test(metadata): lock production router ownership boundary"
```

## Task 11: Implementation Report

**Files:**
- Create: `docs/architecture/metadata-router-p0-production-ownership-report.md`

- [ ] **Step 1: Create report**

Create `metadata-router-p0-production-ownership-report.md`:

```markdown
# MetadataRouter P0 Production Ownership Report

## Changed Production Call Paths

- HomeProviderLocalizedMetadataOverlay: now resolves through MetadataRouterFacade and applies facade display metadata.
- HomeViewModelPresentationPipeline: PREVIEW is addon-only; visible/detail enrichment uses non-preview facade requests.
- HomeCatalogRefreshCoordinator: initial row render no longer fetches provider metadata.
- MetaDetailsViewModel: final detail fields come from facade display metadata / FieldResolver.
- PlayerRuntimeControllerMetadata: playback metadata goes through facade output.
- HomeViewModelContinueWatchingRuntimePipeline: runtime hydration uses facade output.
- ContinueWatchingSnapshotService: stale route snapshots reroute once and persist upgraded version.
- TvdbContinueWatchingTimingEnricher: timing hydration uses facade output.

## Before And After Flow

### Home
Before: addon item -> PREVIEW sidecar route -> legacy TV/TMDB enrichment -> direct merge.
After: addon item -> immediate addon render -> visible enrichment -> MetadataRouterFacade -> ProviderPlanRunner -> FieldResolver -> display metadata.

### Detail
Before: detail VM -> legacy TV router + direct TMDB/Kitsu services -> direct Meta mutation.
After: detail VM -> MetadataRouterFacade -> ProviderPlanRunner -> FieldResolver -> resolved display metadata.

### Player
Before: player metadata -> facade -> legacy ProviderMetadataRouter.
After: player metadata -> MetadataRouterFacade -> depth-specific plan/resolver schedule -> resolved display metadata.

### Continue Watching Write
Before: playback start wrote route snapshot but render upgrade was absent.
After: playback start persists parentId, provider, decision reason, routing version, and click-time display metadata.

### Continue Watching Render
Before: canonical/fallback merge existed, but stale route versions were not upgraded.
After: stale route version reroutes once from stored parent route, persists upgraded snapshot, then renders canonical -> click-time -> persisted fallback.

## Remaining Legacy References

Legacy provider routers/services remain only in adapter-owned internals or non-metadata-output code paths listed by architecture test allowlists.

## Gates

- MetadataRouterFacade owns final output: YES
- PREVIEW addon-only: YES
- ProviderPlanRunner executes all plan steps through runtime-backed adapters: YES
- FieldResolver owns final metadata construction: YES
- Continue Watching route snapshots version-upgrade: YES
- Integration runtime audit: PASS
```

- [ ] **Step 2: Commit report**

Run:

```bash
git add docs/architecture/metadata-router-p0-production-ownership-report.md
git commit -m "docs(metadata): report router production ownership remediation"
```

## Final Verification

Run all final gates:

```bash
openspec validate add-metadata-router --strict
```

Expected:

```text
Change 'add-metadata-router' is valid
```

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin
```

Expected:

```text
BUILD SUCCESSFUL
```

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.metadata.router.*' --tests com.nexio.tv.architecture.MetadataProductionBoundaryTest --tests com.nexio.tv.architecture.MetadataRouterBoundaryTest --tests com.nexio.tv.data.repository.ContinueWatchingRouteLifecycleTest
```

Expected:

```text
BUILD SUCCESSFUL
```

Run:

```bash
./gradlew :app:generateIntegrationRuntimeAudit
```

Expected:

```text
BUILD SUCCESSFUL
```

## Self-Review Checklist

- Spec coverage: Tasks 1-11 cover facade execution, provider plan runner, identity resolution, PREVIEW enforcement, FieldResolver ownership, caller migration, CW lifecycle, boundary tests, audit verification, and implementation reporting.
- Deferred-work scan: The plan contains no `TBD`, unbounded "handle later" steps, or deferred implementation sections.
- Type consistency: `MetadataResolutionResult`, `ProviderPlanRunResult`, `ProviderStepResult`, `MetadataProviderAdapter`, `ProviderPlanRunner`, and `MetadataIdentityResolver` are introduced before later tasks depend on them.
- Risk called out: Adapter candidate extraction must be completed in Task 4 before commit; empty candidates are not acceptable for the task to pass production-readiness review.
