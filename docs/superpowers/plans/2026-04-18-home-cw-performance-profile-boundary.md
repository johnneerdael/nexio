# Home CW Performance Profile Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve modern home UI smoothness and Continue Watching feed quality of life without allowing default and secondary profile CW data to leak across `ProfileBoundary`.

**Architecture:** Keep profile ownership at the data boundary: `ContinueWatchingSnapshotService` continues to emit `ProfileOwnedContinueWatchingSnapshot`, and `HomeViewModel` continues to reject snapshots whose `profileId` differs from `activeHomeProfileSession.profileId`. Move expensive modern-home row mapping out of `ModernHomeContent` composition into a pure presentation builder fed by profile-owned state, then add profile-scoped CW resolution signals so the UI can show stable first content without reusing another profile's stale feed.

**Tech Stack:** Kotlin, Jetpack Compose for Android TV, Coroutines/Flow, Hilt ViewModel, Robolectric/JUnit unit tests, adb `gfxinfo`/`meminfo`/`logcat` profiling.

---

## Requirements And Evidence

- The profileable app on `192.168.50.71:5555` showed home UI pressure during D-pad navigation:
  - Horizontal poster movement: `594` frames, `45` janky frames, `7.58%`, `95th=48ms`, `42` slow UI thread frames.
  - Vertical row movement: `261` frames, `31` janky frames, `11.88%`, `95th=65ms`, `29` slow UI thread frames.
  - Logs during jank showed TVDB enrichment and GC events around home focus movement.
- Relevant upstream directions:
  - `NuvioTV#1403`: move modern-home carousel row construction from composition to a ViewModel presentation pipeline.
  - `NuvioTV#1411`: reduce focus-time row computation and delay catalog row update churn after enrichment.
  - `NuvioTV#1372`: improve CW startup stability, batching, and source-cache separation.
- Non-negotiable boundary:
  - Default profile is legacy profile `1`.
  - Secondary profiles are `2..4` and must route through `ProfileBoundary`.
  - Shared artwork/text metadata can be shared only through existing shared cache scopes.
  - CW snapshots, CW resolution flags, CW presentation rows, and CW enrichment jobs must be scoped by the active home session profile/generation.

## File Structure

- Create `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomePresentation.kt`
  - Pure builder for `ModernHomePresentationState`.
  - Reuses `ModernCarouselRowBuildCache` and existing `buildContinueWatchingItem`/`buildCatalogItem`.
  - Creates `CarouselRowLookups` outside composition.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
  - Add `ModernHomePresentationInput` and `ModernHomePresentationState`.
  - Add `buildCarouselRowLookups`.
  - Keep `ModernCarouselRowBuildCache` profile-owned by the ViewModel, not globally shared.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`
  - Add `modernHomePresentation`.
  - Add `initialContinueWatchingResolved`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
  - Own one `ModernCarouselRowBuildCache` per `HomeViewModel`.
  - Reset presentation and CW resolved flags on profile session changes.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
  - Build modern-home presentation on `Dispatchers.Default` from profile-owned `HomeUiState` inputs.
  - Debounce presentation rebuilds enough to batch enrichment churn without delaying first visible content.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt`
  - Consume `contentState.modernHomePresentation.rows` and `.lookups`.
  - Remove row-building work and lookup-building work from composition.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
  - Mark `initialContinueWatchingResolved` only after accepting a snapshot owned by the active home profile session.
  - Clear/stabilize CW enrichment results by generation before publishing.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt`
  - Use `initialContinueWatchingResolved` in the first-render gate so the app can avoid blank/flashy CW transitions while still never showing another profile's CW feed.
- Create `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomePresentationTest.kt`
  - Unit coverage for row building, lookup building, cache reuse, and profile-independent purity.
- Modify `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`
  - Add static contracts that the new presentation cache and initial CW signal reset on profile switch and do not bypass `ProfileBoundary`.
- Create `scripts/perf/home-ui-perf-pass.sh`
  - Repeatable adb measurement script for home horizontal and vertical focus workloads.

## Task 1: Pure Modern Home Presentation Builder

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomePresentation.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomePresentationTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomePresentationTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHomePresentationTest {
    @Test
    fun `builds continue watching before catalog rows`() {
        val cache = ModernCarouselRowBuildCache()
        val state = buildModernHomePresentation(
            input = ModernHomePresentationInput(
                catalogRows = listOf(catalogRow("popular", "Popular", "movie", listOf(meta("movie-1")))),
                continueWatchingItems = listOf(inProgress("tt-cw-1", "Resume Me")),
                useLandscapePosters = false,
                showCatalogTypeSuffix = true,
                continueWatchingTitle = "Continue watching",
                airsDateTemplate = "Airs %s",
                upcomingLabel = "Upcoming"
            ),
            cache = cache
        )

        assertEquals(listOf("continue_watching", "addon_popular_movie"), state.rows.map { it.key })
        assertEquals("Continue watching", state.rows[0].title)
        assertEquals("Popular - Movie", state.rows[1].title)
        assertEquals(setOf("continue_watching", "addon_popular_movie"), state.lookups.activeRowKeys)
        assertTrue(state.lookups.activeCatalogItemIds.contains("movie-1"))
    }

    @Test
    fun `reuses cached row when catalog input is unchanged`() {
        val cache = ModernCarouselRowBuildCache()
        val row = catalogRow("popular", "Popular", "movie", listOf(meta("movie-1")))
        val input = ModernHomePresentationInput(
            catalogRows = listOf(row),
            continueWatchingItems = emptyList(),
            useLandscapePosters = false,
            showCatalogTypeSuffix = true,
            continueWatchingTitle = "Continue watching",
            airsDateTemplate = "Airs %s",
            upcomingLabel = "Upcoming"
        )

        val first = buildModernHomePresentation(input, cache)
        val second = buildModernHomePresentation(input, cache)

        assertSame(first.rows.single(), second.rows.single())
        assertSame(first.rows.single().items.single(), second.rows.single().items.single())
    }

    @Test
    fun `removes stale catalog cache entries when row disappears`() {
        val cache = ModernCarouselRowBuildCache()
        buildModernHomePresentation(
            input = ModernHomePresentationInput(
                catalogRows = listOf(catalogRow("popular", "Popular", "movie", listOf(meta("movie-1")))),
                continueWatchingItems = emptyList(),
                useLandscapePosters = false,
                showCatalogTypeSuffix = true,
                continueWatchingTitle = "Continue watching",
                airsDateTemplate = "Airs %s",
                upcomingLabel = "Upcoming"
            ),
            cache = cache
        )
        buildModernHomePresentation(
            input = ModernHomePresentationInput(
                catalogRows = emptyList(),
                continueWatchingItems = emptyList(),
                useLandscapePosters = false,
                showCatalogTypeSuffix = true,
                continueWatchingTitle = "Continue watching",
                airsDateTemplate = "Airs %s",
                upcomingLabel = "Upcoming"
            ),
            cache = cache
        )

        assertTrue(cache.catalogRows.isEmpty())
        assertTrue(cache.catalogItemCache.isEmpty())
    }

    private fun catalogRow(
        catalogId: String,
        catalogName: String,
        apiType: String,
        items: List<MetaPreview>
    ): CatalogRow = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon.example",
        catalogId = catalogId,
        catalogName = catalogName,
        type = apiType,
        rawType = apiType,
        apiType = apiType,
        items = items
    )

    private fun meta(id: String): MetaPreview = MetaPreview(
        id = id,
        type = "movie",
        apiType = "movie",
        name = "Title $id",
        poster = "https://img.example/$id.jpg",
        background = "https://img.example/$id-bg.jpg",
        logo = null,
        description = "Description $id",
        releaseInfo = "2026",
        posterShape = PosterShape.POSTER,
        posterProviderTag = null
    )

    private fun inProgress(id: String, name: String): ContinueWatchingItem.InProgress =
        ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = id,
                contentType = "series",
                name = name,
                poster = "https://img.example/$id.jpg",
                backdrop = "https://img.example/$id-bg.jpg",
                logo = null,
                videoId = "$id:1:1",
                season = 1,
                episode = 1,
                episodeTitle = "Episode 1",
                position = 10_000L,
                duration = 100_000L,
                lastWatched = 1_000L,
                progressPercent = 10f
            ),
            displayMetadata = HomeDisplayMetadata(title = name)
        )
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.ModernHomePresentationTest"
```

Expected: FAIL because `ModernHomePresentationInput`, `ModernHomePresentationState`, and `buildModernHomePresentation` do not exist.

- [ ] **Step 3: Add presentation state models**

Modify `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt` near `CarouselRowLookups`:

```kotlin
@Immutable
internal data class ModernHomePresentationInput(
    val catalogRows: List<CatalogRow>,
    val continueWatchingItems: List<ContinueWatchingItem>,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val continueWatchingTitle: String,
    val airsDateTemplate: String,
    val upcomingLabel: String
)

@Immutable
internal data class ModernHomePresentationState(
    val rows: List<HeroCarouselRow> = emptyList(),
    val lookups: CarouselRowLookups = buildCarouselRowLookups(emptyList())
)

internal fun buildCarouselRowLookups(carouselRows: List<HeroCarouselRow>): CarouselRowLookups {
    val rowIndexByKey = LinkedHashMap<String, Int>(carouselRows.size)
    val rowByKey = LinkedHashMap<String, HeroCarouselRow>(carouselRows.size)
    val activeRowKeys = LinkedHashSet<String>(carouselRows.size)
    val activeItemKeysByRow = LinkedHashMap<String, Set<String>>(carouselRows.size)
    val activeCatalogItemIds = LinkedHashSet<String>()

    carouselRows.forEachIndexed { index, row ->
        rowIndexByKey[row.key] = index
        rowByKey[row.key] = row
        activeRowKeys += row.key

        val itemKeys = LinkedHashSet<String>(row.items.size)
        row.items.forEach { item ->
            itemKeys += item.key
            val payload = item.payload
            if (payload is ModernPayload.Catalog) {
                activeCatalogItemIds += payload.itemId
            }
        }
        activeItemKeysByRow[row.key] = itemKeys
    }

    return CarouselRowLookups(
        rowIndexByKey = rowIndexByKey,
        rowByKey = rowByKey,
        activeRowKeys = activeRowKeys,
        activeItemKeysByRow = activeItemKeysByRow,
        activeCatalogItemIds = activeCatalogItemIds
    )
}
```

- [ ] **Step 4: Add the pure builder**

Create `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomePresentation.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

internal fun buildModernHomePresentation(
    input: ModernHomePresentationInput,
    cache: ModernCarouselRowBuildCache
): ModernHomePresentationState {
    val visibleCatalogRows = modernVisibleCatalogRows(input.catalogRows)
    val rows = buildList {
        val activeCatalogKeys = LinkedHashSet<String>(visibleCatalogRows.size)

        if (input.continueWatchingItems.isNotEmpty()) {
            val reuseContinueWatchingRow =
                cache.continueWatchingRow != null &&
                    cache.continueWatchingItems == input.continueWatchingItems &&
                    cache.continueWatchingTitle == input.continueWatchingTitle &&
                    cache.continueWatchingAirsDateTemplate == input.airsDateTemplate &&
                    cache.continueWatchingUpcomingLabel == input.upcomingLabel &&
                    cache.continueWatchingUseLandscapePosters == input.useLandscapePosters

            val continueWatchingRow = if (reuseContinueWatchingRow) {
                checkNotNull(cache.continueWatchingRow)
            } else {
                HeroCarouselRow(
                    key = "continue_watching",
                    title = input.continueWatchingTitle,
                    globalRowIndex = -1,
                    items = input.continueWatchingItems.map { item ->
                        buildContinueWatchingItem(
                            item = item,
                            useLandscapePosters = input.useLandscapePosters,
                            airsDateTemplate = input.airsDateTemplate,
                            upcomingLabel = input.upcomingLabel
                        )
                    }
                )
            }

            cache.continueWatchingItems = input.continueWatchingItems
            cache.continueWatchingTitle = input.continueWatchingTitle
            cache.continueWatchingAirsDateTemplate = input.airsDateTemplate
            cache.continueWatchingUpcomingLabel = input.upcomingLabel
            cache.continueWatchingUseLandscapePosters = input.useLandscapePosters
            cache.continueWatchingRow = continueWatchingRow
            add(continueWatchingRow)
        } else {
            cache.continueWatchingItems = emptyList()
            cache.continueWatchingRow = null
        }

        visibleCatalogRows.forEachIndexed { index, row ->
            val rowKey = catalogRowKey(row)
            activeCatalogKeys += rowKey
            val cached = cache.catalogRows[rowKey]
            val canReuseMappedRow =
                cached != null &&
                    cached.source == row &&
                    cached.useLandscapePosters == input.useLandscapePosters &&
                    cached.showCatalogTypeSuffix == input.showCatalogTypeSuffix

            val mappedRow = if (canReuseMappedRow) {
                val cachedMappedRow = checkNotNull(cached).mappedRow
                if (cachedMappedRow.globalRowIndex == index) {
                    cachedMappedRow
                } else {
                    cachedMappedRow.copy(globalRowIndex = index)
                }
            } else {
                val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                val rowItemCache = cache.catalogItemCache.getOrPut(rowKey) { mutableMapOf() }
                HeroCarouselRow(
                    key = rowKey,
                    title = catalogRowTitle(
                        row = row,
                        showCatalogTypeSuffix = input.showCatalogTypeSuffix
                    ),
                    globalRowIndex = index,
                    catalogId = row.catalogId,
                    addonId = row.addonId,
                    apiType = row.apiType,
                    supportsSkip = row.supportsSkip,
                    hasMore = row.hasMore,
                    isLoading = row.isLoading,
                    items = row.items.map { item ->
                        val occurrence = rowItemOccurrenceCounts.getOrDefault(item.id, 0)
                        rowItemOccurrenceCounts[item.id] = occurrence + 1
                        val cacheKey = "${item.id}_$occurrence"
                        val cachedItem = rowItemCache[cacheKey]
                        if (cachedItem != null &&
                            cachedItem.source == item &&
                            cachedItem.useLandscapePosters == input.useLandscapePosters
                        ) {
                            cachedItem.carouselItem
                        } else {
                            val built = buildCatalogItem(
                                item = item,
                                row = row,
                                useLandscapePosters = input.useLandscapePosters,
                                occurrence = occurrence,
                                previousCachedItem = cachedItem?.carouselItem
                            )
                            rowItemCache[cacheKey] = CachedCarouselItem(
                                source = item,
                                useLandscapePosters = input.useLandscapePosters,
                                carouselItem = built
                            )
                            built
                        }
                    }
                )
            }

            cache.catalogRows[rowKey] = ModernCatalogRowBuildCacheEntry(
                source = row,
                useLandscapePosters = input.useLandscapePosters,
                showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                mappedRow = mappedRow
            )
            add(mappedRow)
        }

        cache.catalogRows.keys.retainAll(activeCatalogKeys)
        cache.catalogItemCache.keys.retainAll(activeCatalogKeys)
    }

    return ModernHomePresentationState(
        rows = rows,
        lookups = buildCarouselRowLookups(rows)
    )
}
```

- [ ] **Step 5: Run the focused test and verify it passes**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.ModernHomePresentationTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomePresentation.kt app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomePresentationTest.kt
git commit -m "perf: extract modern home presentation builder"
```

## Task 2: Move Modern Home Row Mapping Out Of Composition

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt`
- Test: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write a static contract test for presentation ownership**

Append to `ProfileSettingsScopeContractTest`:

```kotlin
@Test
fun `modern home presentation is built by view model and consumed by content`() {
    val stateSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt").readText()
    val viewModelSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt").readText()
    val pipelineSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt").readText()
    val contentSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt").readText()

    assertTrue(stateSource.contains("val modernHomePresentation: ModernHomePresentationState = ModernHomePresentationState()"))
    assertTrue(viewModelSource.contains("internal val modernCarouselRowBuildCache = ModernCarouselRowBuildCache()"))
    assertTrue(pipelineSource.contains("buildModernHomePresentation("))
    assertTrue(pipelineSource.contains("modernCarouselRowBuildCache"))
    assertTrue(contentSource.contains("val presentation = contentState.modernHomePresentation"))
    assertTrue(!contentSource.contains("val rowBuildCache = remember { ModernCarouselRowBuildCache() }"))
    assertTrue(!contentSource.contains("buildCatalogItem("))
}
```

- [ ] **Step 2: Run the contract test and verify it fails**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileSettingsScopeContractTest.modern home presentation is built by view model and consumed by content"
```

Expected: FAIL because presentation is still built inside `ModernHomeContent`.

- [ ] **Step 3: Add presentation state to `HomeUiState`**

Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`:

```kotlin
@Immutable
data class HomeUiState(
    val catalogRows: List<CatalogRow> = emptyList(),
    val continueWatchingItems: List<ContinueWatchingItem> = emptyList(),
    val traktUpNextItems: List<ContinueWatchingItem.NextUp> = emptyList(),
    val modernHomePresentation: ModernHomePresentationState = ModernHomePresentationState(),
    val initialContinueWatchingResolved: Boolean = false,
    val isLoading: Boolean = true,
    ...
)
```

Keep all existing properties after these additions in their current order where practical.

- [ ] **Step 4: Add the ViewModel-owned presentation cache**

Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt` near the other internal state fields:

```kotlin
internal val modernCarouselRowBuildCache = ModernCarouselRowBuildCache()
```

In `resetProfileScopedHomeState`, include these state resets:

```kotlin
modernCarouselRowBuildCache.continueWatchingItems = emptyList()
modernCarouselRowBuildCache.continueWatchingRow = null
modernCarouselRowBuildCache.catalogRows.clear()
modernCarouselRowBuildCache.catalogItemCache.clear()
_uiState.update {
    it.copy(
        continueWatchingItems = emptyList(),
        traktUpNextItems = emptyList(),
        modernHomePresentation = ModernHomePresentationState(),
        initialContinueWatchingResolved = false
    )
}
```

Preserve existing resets in `resetProfileScopedHomeState`; merge this code with the current state copy rather than adding a second conflicting `_uiState.update`.

- [ ] **Step 5: Build presentation in the presentation pipeline**

Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt` by adding this function:

```kotlin
internal fun HomeViewModel.observeModernHomePresentationPipeline() {
    viewModelScope.launch {
        _uiState
            .map { state ->
                ModernHomePresentationInput(
                    catalogRows = state.catalogRows,
                    continueWatchingItems = state.continueWatchingItems,
                    useLandscapePosters = state.modernLandscapePostersEnabled,
                    showCatalogTypeSuffix = state.catalogTypeSuffixEnabled,
                    continueWatchingTitle = appContext.getString(R.string.continue_watching),
                    airsDateTemplate = appContext.getString(R.string.cw_airs_date),
                    upcomingLabel = appContext.getString(R.string.cw_upcoming)
                )
            }
            .distinctUntilChanged()
            .debounce(80)
            .collectLatest { input ->
                val capturedGeneration = homeProfileGeneration
                val presentation = withContext(Dispatchers.Default) {
                    buildModernHomePresentation(
                        input = input,
                        cache = modernCarouselRowBuildCache
                    )
                }
                if (!isCurrentHomeProfileGeneration(capturedGeneration)) return@collectLatest
                _uiState.update { state ->
                    if (state.modernHomePresentation == presentation) {
                        state
                    } else {
                        state.copy(modernHomePresentation = presentation)
                    }
                }
            }
    }
}
```

Add imports:

```kotlin
import androidx.lifecycle.viewModelScope
import com.nexio.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

Annotate the function or file with `@OptIn(FlowPreview::class)`.

Call `observeModernHomePresentationPipeline()` from `HomeViewModel.init` after layout preferences and before long-running refresh flows:

```kotlin
observeModernHomePresentationPipeline()
```

Use the existing application context property if `HomeViewModel` already exposes one. If it does not, inject `@ApplicationContext private val appContext: Context` into `HomeViewModel` and update the test factory constructor call sites with `ApplicationProvider.getApplicationContext()`.

- [ ] **Step 6: Consume presentation in `ModernHomeContent`**

Modify `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt`:

```kotlin
val presentation = contentState.modernHomePresentation
val carouselRows = presentation.rows
if (carouselRows.isEmpty()) return
val carouselLookups = presentation.lookups
val rowIndexByKey = carouselLookups.rowIndexByKey
val rowByKey = carouselLookups.rowByKey
val activeRowKeys = carouselLookups.activeRowKeys
val activeItemKeysByRow = carouselLookups.activeItemKeysByRow
val activeCatalogItemIds = carouselLookups.activeCatalogItemIds
```

Remove the in-composition block that declares:

```kotlin
val rowBuildCache = remember { ModernCarouselRowBuildCache() }
val carouselRows = remember(...)
val carouselLookups = remember(carouselRows) { ... }
```

Keep the rest of the focus, hero, and row rendering logic unchanged.

- [ ] **Step 7: Run the focused test and compile**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileSettingsScopeContractTest.modern home presentation is built by view model and consumed by content"
./gradlew assembleArm64Debug
```

Expected: both commands PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "perf: build modern home rows outside composition"
```

## Task 3: Profile-Owned CW First-Resolution Signal

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt`
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write the contract test**

Append to `ProfileSettingsScopeContractTest`:

```kotlin
@Test
fun `initial continue watching resolution is owned by active home profile`() {
    val homeStateSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt").readText()
    val homeContinueWatchingSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt").readText()
    val homeScreenSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt").readText()
    val homeViewModelSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt").readText()

    assertTrue(homeStateSource.contains("val initialContinueWatchingResolved: Boolean = false"))
    assertTrue(homeContinueWatchingSource.contains("ownedSnapshot.profileId != activeHomeProfileSession.profileId"))
    assertTrue(homeContinueWatchingSource.contains("initialContinueWatchingResolved = true"))
    assertTrue(homeViewModelSource.contains("initialContinueWatchingResolved = false"))
    assertTrue(homeScreenSource.contains("uiState.initialContinueWatchingResolved"))
}
```

- [ ] **Step 2: Run the contract test and verify it fails**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileSettingsScopeContractTest.initial continue watching resolution is owned by active home profile"
```

Expected: FAIL until the new state is wired.

- [ ] **Step 3: Mark CW resolved only after accepting an owned snapshot**

Modify `HomeViewModelContinueWatching.kt` inside `loadContinueWatchingPipeline`, in the `_uiState.update` block that publishes `continueWatchingItems` and `traktUpNextItems`:

```kotlin
_uiState.update { state ->
    if (
        state.continueWatchingItems == items &&
        state.traktUpNextItems == traktUpNextItems &&
        state.initialContinueWatchingResolved
    ) {
        state
    } else {
        state.copy(
            continueWatchingItems = items,
            traktUpNextItems = traktUpNextItems,
            initialContinueWatchingResolved = true
        )
    }
}
```

Do not set this flag when `ownedSnapshot.profileId != activeHomeProfileSession.profileId`.

- [ ] **Step 4: Preserve the generation guard for enriched CW publishes**

In the enrichment job inside `HomeViewModelContinueWatching.kt`, keep this check immediately before publishing enriched CW items:

```kotlin
if (!isCurrentHomeProfileGeneration(capturedGeneration)) {
    Log.d(HomeViewModel.TAG, "Skipping stale continue watching enrichment generation=$capturedGeneration")
    return@launch
}
```

Then publish without changing the resolved flag back to false:

```kotlin
_uiState.update { state ->
    state.copy(
        continueWatchingItems = enrichedItems,
        traktUpNextItems = enrichedTraktItems,
        initialContinueWatchingResolved = true
    )
}
```

- [ ] **Step 5: Use the resolved flag in the home startup gate**

Modify `HomeScreen.kt` in the `else` branch that computes `shouldShowLoadingGate`:

```kotlin
val shouldWaitForContinueWatching =
    uiState.homeLayout == HomeLayout.MODERN &&
        !uiState.initialContinueWatchingResolved &&
        uiState.error == null &&
        uiState.installedAddonsCount > 0

val shouldShowLoadingGate = (!hasRenderableContent || shouldWaitForContinueWatching) &&
    uiState.error == null &&
    !startupContentGateTimedOut
```

This keeps the spinner during first profile-owned CW resolution but still releases on `HOME_STARTUP_CONTENT_TIMEOUT_MS`.

- [ ] **Step 6: Run the contract test and home tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileSettingsScopeContractTest.initial continue watching resolution is owned by active home profile"
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.*"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "fix: gate continue watching by active profile resolution"
```

## Task 4: Batch CW Enrichment UI Churn Without Crossing Profiles

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomePlaybackWorkGateTest.kt`

- [ ] **Step 1: Add a failing test for limited enrichment concurrency and stable order**

Append to `HomePlaybackWorkGateTest`:

```kotlin
@Test
fun `continue watching enrichment keeps input order while limiting concurrency`() = runTest {
    val active = java.util.concurrent.atomic.AtomicInteger(0)
    val maxObserved = java.util.concurrent.atomic.AtomicInteger(0)
    val inputs = (1..8).toList()

    val result = mapContinueWatchingEnrichmentWithLimit(
        items = inputs,
        maxConcurrency = 2
    ) { value ->
        val nowActive = active.incrementAndGet()
        maxObserved.updateAndGet { current -> maxOf(current, nowActive) }
        kotlinx.coroutines.delay(10)
        active.decrementAndGet()
        "item-$value"
    }

    assertEquals((1..8).map { "item-$it" }, result)
    assertTrue(maxObserved.get() <= 2)
}
```

- [ ] **Step 2: Run the focused test**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomePlaybackWorkGateTest.continue watching enrichment keeps input order while limiting concurrency"
```

Expected: PASS if current helper already satisfies this. If it fails, fix only `mapContinueWatchingEnrichmentWithLimit`.

- [ ] **Step 3: Avoid redundant enriched-state publishes**

Modify `HomeViewModelContinueWatching.kt` enrichment publish:

```kotlin
_uiState.update { state ->
    if (
        state.continueWatchingItems == enrichedItems &&
        state.traktUpNextItems == enrichedTraktItems &&
        state.initialContinueWatchingResolved
    ) {
        state
    } else {
        state.copy(
            continueWatchingItems = enrichedItems,
            traktUpNextItems = enrichedTraktItems,
            initialContinueWatchingResolved = true
        )
    }
}
```

This reduces presentation rebuilds after enrichment without changing snapshot ownership.

- [ ] **Step 4: Run home and sync tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomePlaybackWorkGateTest"
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileSettingsScopeContractTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomePlaybackWorkGateTest.kt
git commit -m "perf: reduce continue watching enrichment churn"
```

## Task 5: Simplify Vertical Focus Target Selection

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt`
- Test: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Add a static contract test**

Append to `ProfileSettingsScopeContractTest`:

```kotlin
@Test
fun `modern vertical focus uses saved row item instead of visible pixel scan`() {
    val source = File("app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt").readText()

    assertTrue(source.contains("val targetSavedIndex = (focusedItemByRow[targetRow.key] ?: 0)"))
    assertTrue(!source.contains("layoutInfo.visibleItemsInfo.find"))
    assertTrue(!source.contains("minByOrNull"))
}
```

- [ ] **Step 2: Run the contract test and verify it fails if pixel scan still exists**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileSettingsScopeContractTest.modern vertical focus uses saved row item instead of visible pixel scan"
```

Expected: FAIL if `ModernHomeContent` still performs per-navigation visible item center scans. PASS if this fork already removed the scan.

- [ ] **Step 3: Replace vertical focus lookup**

In `ModernHomeContent.kt`, where `ModernRowSection` receives `onGetVerticalFocusRequester`, use:

```kotlin
onGetVerticalFocusRequester = { _, isDown ->
    val currentRowIndex = rowIndexByKey[row.key] ?: return@ModernRowSection FocusRequester.Default
    val targetRowIndex = if (isDown) currentRowIndex + 1 else currentRowIndex - 1
    val targetRow = carouselRows.getOrNull(targetRowIndex) ?: return@ModernRowSection FocusRequester.Default
    val targetSavedIndex = (focusedItemByRow[targetRow.key] ?: 0)
        .coerceIn(0, (targetRow.items.size - 1).coerceAtLeast(0))
    val targetItemKey = targetRow.items.getOrNull(targetSavedIndex)?.key
    if (targetItemKey != null) {
        uiCaches.requesterFor(targetRow.key, targetItemKey)
    } else {
        FocusRequester.Default
    }
}
```

If this fork does not expose `onGetVerticalFocusRequester` in `ModernRowSection`, do not invent it for this task; instead leave the contract test out and document that this branch already uses the simpler focus path.

- [ ] **Step 4: Run compile and contract test**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileSettingsScopeContractTest.modern vertical focus uses saved row item instead of visible pixel scan"
./gradlew assembleArm64Debug
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "perf: simplify modern home vertical focus"
```

## Task 6: Repeatable Device Performance Harness

**Files:**
- Create: `scripts/perf/home-ui-perf-pass.sh`

- [ ] **Step 1: Create the profiling script**

Create `scripts/perf/home-ui-perf-pass.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

DEVICE="${1:-192.168.50.71:5555}"
PACKAGE="${2:-com.nexio.tv.profileable}"
ACTIVITY="${3:-com.nexio.tv.MainActivity}"
OUT_DIR="${4:-/tmp/nexio-home-perf}"

mkdir -p "$OUT_DIR"

adb -s "$DEVICE" shell am start -W -n "$PACKAGE/$ACTIVITY" > "$OUT_DIR/launch.txt"
adb -s "$DEVICE" shell input keyevent 4 || true
sleep 2

run_case() {
  local name="$1"
  local body="$2"

  adb -s "$DEVICE" shell dumpsys gfxinfo "$PACKAGE" reset > "$OUT_DIR/${name}_reset.txt"
  adb -s "$DEVICE" shell logcat -c
  adb -s "$DEVICE" shell dumpsys meminfo "$PACKAGE" > "$OUT_DIR/${name}_mem_before.txt"

  adb -s "$DEVICE" shell "$body"
  sleep 2

  adb -s "$DEVICE" shell dumpsys gfxinfo "$PACKAGE" > "$OUT_DIR/${name}_gfxinfo.txt"
  adb -s "$DEVICE" shell dumpsys meminfo "$PACKAGE" > "$OUT_DIR/${name}_mem_after.txt"
  adb -s "$DEVICE" shell logcat -d -v time > "$OUT_DIR/${name}_logcat.txt"

  {
    echo "== $name gfx =="
    grep -E "Total frames|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed|Number Slow|Frame deadline" "$OUT_DIR/${name}_gfxinfo.txt" || true
    echo
    echo "== $name memory =="
    grep -E "Native Heap|Dalvik Heap|GL mtrack|Java Heap|Graphics:|TOTAL PSS|TOTAL RSS|TOTAL SWAP PSS" "$OUT_DIR/${name}_mem_before.txt" "$OUT_DIR/${name}_mem_after.txt" || true
    echo
    echo "== $name jank and gc =="
    grep -Ei "JankStats|GC freed|Choreographer|TvMetadataRouter|TvdbReliability" "$OUT_DIR/${name}_logcat.txt" | tail -n 120 || true
  } > "$OUT_DIR/${name}_summary.txt"
}

run_case "horizontal" "for i in \$(seq 1 18); do input keyevent 22; sleep 0.12; done; for i in \$(seq 1 18); do input keyevent 21; sleep 0.12; done"
run_case "vertical" "for i in \$(seq 1 10); do input keyevent 20; sleep 0.14; done; for i in \$(seq 1 10); do input keyevent 19; sleep 0.14; done"

echo "Wrote summaries to $OUT_DIR"
echo "$OUT_DIR/horizontal_summary.txt"
echo "$OUT_DIR/vertical_summary.txt"
```

- [ ] **Step 2: Make the script executable**

Run:

```bash
chmod +x scripts/perf/home-ui-perf-pass.sh
```

Expected: no output.

- [ ] **Step 3: Run the script against the profileable package**

Run:

```bash
scripts/perf/home-ui-perf-pass.sh 192.168.50.71:5555 com.nexio.tv.profileable com.nexio.tv.MainActivity /tmp/nexio-home-perf-after
```

Expected: the script writes:

```text
/tmp/nexio-home-perf-after/horizontal_summary.txt
/tmp/nexio-home-perf-after/vertical_summary.txt
```

- [ ] **Step 4: Compare against current baseline**

Use these target thresholds for the first pass:

```text
horizontal janky frames: below 5.0% (baseline observed 7.58%)
vertical janky frames: below 8.0% (baseline observed 11.88%)
95th percentile horizontal: below 40ms (baseline observed 48ms)
95th percentile vertical: below 50ms (baseline observed 65ms)
no foreign profile CW items visible after switching profiles
```

- [ ] **Step 5: Commit**

```bash
git add scripts/perf/home-ui-perf-pass.sh
git commit -m "chore: add home ui performance harness"
```

## Task 7: Full Verification

**Files:**
- No new files.

- [ ] **Step 1: Run focused tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.ModernHomePresentationTest"
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileSettingsScopeContractTest"
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.*"
```

Expected: PASS.

- [ ] **Step 2: Run broader unit tests**

Run:

```bash
./gradlew testArm64DebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Build the debug app**

Run:

```bash
./gradlew assembleArm64Debug
```

Expected: PASS and an APK under `app/build/outputs/apk/arm64/debug/`.

- [ ] **Step 4: Run device performance harness**

Run:

```bash
scripts/perf/home-ui-perf-pass.sh 192.168.50.71:5555 com.nexio.tv.profileable com.nexio.tv.MainActivity /tmp/nexio-home-perf-final
```

Expected: summaries show improved or non-regressed jank against the baseline in Task 6.

- [ ] **Step 5: Manual profile isolation check**

On the Android TV device:

```text
1. Select default profile.
2. Confirm Continue Watching contains default profile items.
3. Switch to a secondary profile.
4. Confirm the UI shows loading or empty/profile-owned CW state, not default profile items.
5. Wait for CW refresh.
6. Confirm secondary profile CW items appear.
7. Switch back to default profile.
8. Confirm default profile CW items return.
```

Expected: no cross-profile CW items appear during switching, loading, enrichment, or post-refresh.

- [ ] **Step 6: Commit verification notes**

Create a short local note in the commit body or PR description with:

```text
Baseline:
- horizontal: 7.58% jank, 95th 48ms
- vertical: 11.88% jank, 95th 65ms

After:
- use the exact `Janky frames` and `95th percentile` lines from `/tmp/nexio-home-perf-final/horizontal_summary.txt`
- use the exact `Janky frames` and `95th percentile` lines from `/tmp/nexio-home-perf-final/vertical_summary.txt`

Profile isolation:
- default -> secondary -> default CW switch checked
```

Commit any remaining test-only changes:

```bash
git status --short
git add app/src/test/java/com/nexio/tv scripts/perf/home-ui-perf-pass.sh
git commit -m "test: verify home cw performance boundaries"
```

Only run the final commit if there are remaining intentional changes.

## Self-Review

- Spec coverage:
  - UI performance pass: Tasks 1, 2, 5, and 6.
  - CW quality of life: Tasks 3 and 4.
  - Default/non-default profile separation: Tasks 2, 3, 4, and 7.
  - Strict `ProfileBoundary` preservation: Tasks 3 and 7 keep CW ownership on `ProfileOwnedContinueWatchingSnapshot` and `activeHomeProfileSession`.
  - Upstream PR direction: Tasks 1 and 2 cover the #1403 extraction, Task 5 covers the #1411 focus simplification, and Tasks 3/4 cover the relevant #1372 CW stability ideas without adopting unrelated playback changes.
- Placeholder scan:
  - No placeholder implementation steps or unnamed edge-case work remains.
- Type consistency:
  - `ModernHomePresentationInput`, `ModernHomePresentationState`, `buildModernHomePresentation`, and `buildCarouselRowLookups` are introduced before later tasks use them.
  - `initialContinueWatchingResolved` is added to `HomeUiState` before `HomeScreen` and `HomeViewModelContinueWatching` use it.
