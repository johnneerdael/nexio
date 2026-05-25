# Addon Catalog Daily Freshness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop repeated addon catalog rail GETs by making `CatalogRepositoryImpl.refreshCatalogToDisk()` use a 24-hour app-owned freshness window with stale-on-error fallback.

**Architecture:** Keep freshness ownership in `CatalogRepositoryImpl`, where addon catalog cache keys and mapped `CatalogRow` persistence already live. Use `CatalogDiskCacheStore.Entry.updatedAtMs` to decide whether a row is fresh, and keep `NetworkModule.disableDiskCacheForGetRequests()` unchanged so OkHttp does not own addon catalog freshness.

**Tech Stack:** Kotlin, Hilt constructor injection with default constructor parameters for tests, MockK, Kotlin coroutines test, existing `CatalogDiskCacheStore`, OpenSpec.

---

## File Structure

- Modify `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt`
  - Add a 24-hour TTL constant.
  - Add a defaulted `nowMsProvider` constructor parameter for deterministic tests.
  - Add a small freshness helper.
  - Update only `refreshCatalogToDisk()` to skip network for fresh disk rows and return stale rows on network failure.
- Modify `app/src/test/java/com/nexio/tv/data/repository/CatalogRepositoryImplTest.kt`
  - Add focused repository tests for fresh skip, stale refresh, stale-on-error fallback, missing cache, and provider cache token key isolation.
- Modify `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt`
  - Add one regression test proving a cached-row result from the repository still follows existing raw publish before provider hydration.
- Do not modify `NetworkModule.kt`, `AddonCatalogIntegrationProvider.kt`, or `HomeCatalogRefreshCoordinator.kt` production code.
- Validate `openspec/changes/add-addon-catalog-daily-freshness` after implementation.

## Current Worktree Constraint

The Nexio worktree currently has unrelated modified/staged files from other workstreams. Stage and commit only the files listed in this plan. Never use `git add -A`, `git add .`, or `git commit -a`.

---

### Task 1: Repository Tests For Daily Freshness

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/repository/CatalogRepositoryImplTest.kt`
- Production dependency for later task: `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt`

- [ ] **Step 1: Add test helpers to `CatalogRepositoryImplTest`**

Add these imports near the existing imports:

```kotlin
import io.mockk.coVerify
import io.mockk.verify
import java.util.concurrent.TimeUnit
```

Add these helpers inside `CatalogRepositoryImplTest`, below `noopEnricher()`:

```kotlin
private fun repository(
    provider: AddonCatalogIntegrationProvider,
    posterResolver: PosterRatingsUrlResolver,
    diskCacheStore: CatalogDiskCacheStore,
    nowMsProvider: () -> Long = { 2_000_000_000L }
): CatalogRepositoryImpl = CatalogRepositoryImpl(
    addonCatalogIntegrationProvider = provider,
    posterRatingsUrlResolver = posterResolver,
    catalogDiskCacheStore = diskCacheStore,
    catalogItemCrossIdEnricher = noopEnricher(),
    nowMsProvider = nowMsProvider
)

private fun catalogRow(
    name: String,
    itemId: String = "tt-cached",
    addonId: String = "community.addon",
    catalogId: String = "trending",
    catalogName: String = "Trending",
    addonBaseUrl: String = "https://addon.example",
    type: ContentType = ContentType.MOVIE,
    rawType: String = "movie"
): CatalogRow = CatalogRow(
    addonId = addonId,
    addonName = "Community Addon",
    addonBaseUrl = addonBaseUrl,
    catalogId = catalogId,
    catalogName = catalogName,
    type = type,
    rawType = rawType,
    items = listOf(
        MetaPreview(
            id = itemId,
            name = name,
            type = type,
            apiType = rawType
        )
    ),
    isLoading = false,
    hasMore = false
)

private fun diskEntry(row: CatalogRow, updatedAtMs: Long): CatalogDiskCacheStore.Entry =
    CatalogDiskCacheStore.Entry(
        catalogRow = row,
        catalogVersionHash = "version-${row.items.single().id}",
        updatedAtMs = updatedAtMs
    )
```

- [ ] **Step 2: Add failing test for fresh cache skip**

Add this test to `CatalogRepositoryImplTest`:

```kotlin
@Test
fun `refreshCatalogToDisk returns fresh disk row without network or disk rewrite`() = runTest {
    val now = 2_000_000_000L
    val cachedRow = catalogRow(name = "Cached Daily Row")
    val provider = mockk<AddonCatalogIntegrationProvider>()
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val diskCacheStore = mockk<CatalogDiskCacheStore>()

    coEvery { posterResolver.getActiveProvider() } returns null
    every { diskCacheStore.read(any()) } returns diskEntry(
        row = cachedRow,
        updatedAtMs = now - TimeUnit.HOURS.toMillis(23)
    )

    val result = repository(
        provider = provider,
        posterResolver = posterResolver,
        diskCacheStore = diskCacheStore,
        nowMsProvider = { now }
    ).refreshCatalogToDisk(
        addonBaseUrl = "https://addon.example",
        addonId = "community.addon",
        addonName = "Community Addon",
        catalogId = "trending",
        catalogName = "Trending",
        type = "movie",
        skip = 0,
        skipStep = 20,
        extraArgs = emptyMap(),
        supportsSkip = true
    )

    assertTrue(result.isSuccess)
    assertEquals(cachedRow, result.getOrThrow())
    coVerify(exactly = 0) { provider.getCatalog(any(), any()) }
    verify(exactly = 0) { diskCacheStore.write(any(), any(), any()) }
}
```

- [ ] **Step 3: Add failing test for stale cache refresh**

Add this test:

```kotlin
@Test
fun `refreshCatalogToDisk refreshes stale disk row and writes refreshed row`() = runTest {
    val now = 2_000_000_000L
    val staleRow = catalogRow(name = "Old Row", itemId = "tt-old")
    val provider = mockk<AddonCatalogIntegrationProvider>()
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val diskCacheStore = mockk<CatalogDiskCacheStore>()
    val writtenRow = slot<CatalogRow>()

    coEvery { posterResolver.getActiveProvider() } returns null
    every { diskCacheStore.read(any()) } returns diskEntry(
        row = staleRow,
        updatedAtMs = now - TimeUnit.HOURS.toMillis(24)
    )
    every { diskCacheStore.write(any(), capture(writtenRow), any()) } returns Unit
    coEvery {
        provider.getCatalog(
            addonId = "community.addon",
            catalogUrl = "https://addon.example/catalog/movie/trending.json"
        )
    } returns NetworkResult.Success(
        CatalogResponseDto(
            metas = listOf(
                MetaPreviewDto(
                    id = "tt-new",
                    type = "movie",
                    name = "Fresh Network Row"
                )
            )
        )
    )

    val result = repository(
        provider = provider,
        posterResolver = posterResolver,
        diskCacheStore = diskCacheStore,
        nowMsProvider = { now }
    ).refreshCatalogToDisk(
        addonBaseUrl = "https://addon.example",
        addonId = "community.addon",
        addonName = "Community Addon",
        catalogId = "trending",
        catalogName = "Trending",
        type = "movie",
        skip = 0,
        skipStep = 20,
        extraArgs = emptyMap(),
        supportsSkip = true
    )

    assertTrue(result.isSuccess)
    assertEquals("Fresh Network Row", result.getOrThrow().items.single().name)
    assertEquals("Fresh Network Row", writtenRow.captured.items.single().name)
    coVerify(exactly = 1) {
        provider.getCatalog(
            addonId = "community.addon",
            catalogUrl = "https://addon.example/catalog/movie/trending.json"
        )
    }
    verify(exactly = 1) { diskCacheStore.write(any(), any(), any()) }
}
```

- [ ] **Step 4: Add failing test for stale-on-error fallback**

Add this test:

```kotlin
@Test
fun `refreshCatalogToDisk returns stale disk row when stale refresh fails`() = runTest {
    val now = 2_000_000_000L
    val staleRow = catalogRow(name = "Stale But Renderable", itemId = "tt-stale")
    val provider = mockk<AddonCatalogIntegrationProvider>()
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val diskCacheStore = mockk<CatalogDiskCacheStore>()

    coEvery { posterResolver.getActiveProvider() } returns null
    every { diskCacheStore.read(any()) } returns diskEntry(
        row = staleRow,
        updatedAtMs = now - TimeUnit.DAYS.toMillis(2)
    )
    coEvery {
        provider.getCatalog(
            addonId = "community.addon",
            catalogUrl = "https://addon.example/catalog/movie/trending.json"
        )
    } returns NetworkResult.Error("provider unavailable", 503)

    val result = repository(
        provider = provider,
        posterResolver = posterResolver,
        diskCacheStore = diskCacheStore,
        nowMsProvider = { now }
    ).refreshCatalogToDisk(
        addonBaseUrl = "https://addon.example",
        addonId = "community.addon",
        addonName = "Community Addon",
        catalogId = "trending",
        catalogName = "Trending",
        type = "movie",
        skip = 0,
        skipStep = 20,
        extraArgs = emptyMap(),
        supportsSkip = true
    )

    assertTrue(result.isSuccess)
    assertEquals(staleRow, result.getOrThrow())
    coVerify(exactly = 1) { provider.getCatalog(any(), any()) }
    verify(exactly = 0) { diskCacheStore.write(any(), any(), any()) }
}
```

- [ ] **Step 5: Add failing test for missing cache still failing on provider error**

Add this test:

```kotlin
@Test
fun `refreshCatalogToDisk returns failure when network fails and no cache exists`() = runTest {
    val provider = mockk<AddonCatalogIntegrationProvider>()
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val diskCacheStore = mockk<CatalogDiskCacheStore>()

    coEvery { posterResolver.getActiveProvider() } returns null
    every { diskCacheStore.read(any()) } returns null
    coEvery { provider.getCatalog(any(), any()) } returns NetworkResult.Error("boom", 503)

    val result = repository(
        provider = provider,
        posterResolver = posterResolver,
        diskCacheStore = diskCacheStore
    ).refreshCatalogToDisk(
        addonBaseUrl = "https://addon.example",
        addonId = "community.addon",
        addonName = "Community Addon",
        catalogId = "trending",
        catalogName = "Trending",
        type = "movie",
        skip = 0,
        skipStep = 20,
        extraArgs = emptyMap(),
        supportsSkip = true
    )

    assertTrue(result.isFailure)
    assertEquals("boom", result.exceptionOrNull()?.message)
    verify(exactly = 0) { diskCacheStore.write(any(), any(), any()) }
}
```

- [ ] **Step 6: Add failing test for provider cache token key isolation**

Add this test:

```kotlin
@Test
fun `refreshCatalogToDisk includes poster provider token in freshness cache key`() = runTest {
    val now = 2_000_000_000L
    val provider = mockk<AddonCatalogIntegrationProvider>()
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val diskCacheStore = mockk<CatalogDiskCacheStore>()
    val readKeys = mutableListOf<String>()

    coEvery { posterResolver.getActiveProvider() } returns PosterRatingsUrlResolver.ActiveProvider(
        provider = PosterRatingsProvider.TOP_POSTERS,
        apiKey = "secret"
    )
    every { diskCacheStore.read(capture(readKeys)) } returns null
    every { diskCacheStore.write(any(), any(), any()) } returns Unit
    coEvery {
        provider.getCatalog(any(), "https://addon.example/catalog/movie/trending.json")
    } returns NetworkResult.Success(
        CatalogResponseDto(
            metas = listOf(
                MetaPreviewDto(id = "tt-new", type = "movie", name = "Provider Scoped Row")
            )
        )
    )

    val result = repository(
        provider = provider,
        posterResolver = posterResolver,
        diskCacheStore = diskCacheStore,
        nowMsProvider = { now }
    ).refreshCatalogToDisk(
        addonBaseUrl = "https://addon.example",
        addonId = "community.addon",
        addonName = "Community Addon",
        catalogId = "trending",
        catalogName = "Trending",
        type = "movie",
        skip = 0,
        skipStep = 20,
        extraArgs = emptyMap(),
        supportsSkip = true
    )

    assertTrue(result.isSuccess)
    assertTrue(readKeys.single().contains("TOP_POSTERS"))
    assertTrue(readKeys.single().contains("community.addon"))
    assertTrue(readKeys.single().contains("movie_trending_0_20"))
    coVerify(exactly = 1) { provider.getCatalog(any(), any()) }
}
```

This test intentionally proves the new freshness read uses the same provider-token-aware key path.

- [ ] **Step 7: Run repository tests and verify they fail before production changes**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.CatalogRepositoryImplTest
```

Expected: the new tests fail to compile because `CatalogRepositoryImpl` does not yet accept `nowMsProvider`, and/or fail behaviorally because `refreshCatalogToDisk()` always calls network.

---

### Task 2: Implement Repository-Owned 24h Freshness

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/CatalogRepositoryImplTest.kt`

- [ ] **Step 1: Add TTL and test clock seam**

Update the `CatalogRepositoryImpl` constructor and companion object:

```kotlin
@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val addonCatalogIntegrationProvider: AddonCatalogIntegrationProvider,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val catalogDiskCacheStore: CatalogDiskCacheStore,
    private val catalogItemCrossIdEnricher: CatalogItemCrossIdEnricher,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() }
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
        internal const val ADDON_CATALOG_FRESH_TTL_MS = 24L * 60L * 60L * 1000L
    }
```

- [ ] **Step 2: Add freshness helper**

Add this helper near `buildCacheKey()`:

```kotlin
private fun isFreshCatalogEntry(entry: CatalogDiskCacheStore.Entry, nowMs: Long): Boolean {
    if (entry.updatedAtMs <= 0L) return false
    val ageMs = nowMs - entry.updatedAtMs
    return ageMs >= 0L && ageMs < ADDON_CATALOG_FRESH_TTL_MS
}
```

This implements the spec boundary exactly: less than 24h is fresh; at least 24h is stale.

- [ ] **Step 3: Update `refreshCatalogToDisk()` before the network call**

In `refreshCatalogToDisk()`, after `cacheKey` is built and before `fetchCatalogFromNetwork(...)`, add:

```kotlin
val cachedEntry = catalogDiskCacheStore.read(cacheKey)
if (cachedEntry != null && isFreshCatalogEntry(cachedEntry, nowMsProvider())) {
    val cachedRow = cachedEntry.catalogRow
    catalogCache[cacheKey] = cachedRow
    Log.d(
        TAG,
        "Catalog refresh skipped fresh disk cache addonId=$addonId type=$type catalogId=$catalogId"
    )
    return kotlin.Result.success(cachedRow)
}
val staleCachedRow = cachedEntry?.catalogRow
```

- [ ] **Step 4: Update failure branch for stale-on-error fallback**

Replace the existing failure branch:

```kotlin
is Result.Failure -> kotlin.Result.failure(
    IllegalStateException(refreshed.error.message)
)
```

with:

```kotlin
is Result.Failure -> {
    if (staleCachedRow != null) {
        catalogCache[cacheKey] = staleCachedRow
        Log.w(
            TAG,
            "Catalog refresh failed; returning stale disk cache addonId=$addonId type=$type catalogId=$catalogId code=${refreshed.error.code} message=${refreshed.error.message}"
        )
        kotlin.Result.success(staleCachedRow)
    } else {
        kotlin.Result.failure(IllegalStateException(refreshed.error.message))
    }
}
```

- [ ] **Step 5: Keep success branch unchanged except for surrounding local variables**

The success branch must continue to:

```kotlin
catalogCache[cacheKey] = refreshed.row
catalogDiskCacheStore.write(
    cacheKey = cacheKey,
    row = refreshed.row,
    catalogVersionHash = buildCatalogVersionHash(refreshed.row)
)
kotlin.Result.success(refreshed.row)
```

- [ ] **Step 6: Run repository tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.CatalogRepositoryImplTest
```

Expected: all `CatalogRepositoryImplTest` tests pass.

- [ ] **Step 7: Run addon routing repository tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.CatalogRepositoryAddonRoutingTest
```

Expected: pass. This verifies existing runtime routing and cached-first flow behavior were not regressed.

- [ ] **Step 8: Commit repository implementation**

Stage exact paths only:

```bash
git add app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt app/src/test/java/com/nexio/tv/data/repository/CatalogRepositoryImplTest.kt
git status -sb
git commit -m "fix(catalog): reuse fresh addon catalog disk rows"
```

Before committing, confirm the staged list contains only:

```text
app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt
app/src/test/java/com/nexio/tv/data/repository/CatalogRepositoryImplTest.kt
```

---

### Task 3: Home Coordinator Regression Coverage

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt`
- Production touched: none expected

- [ ] **Step 1: Add cached-row publish regression test**

Add this test near the existing first-paint publish tests in `HomeCatalogRefreshCoordinatorTest`:

```kotlin
@Test
fun `refresh publishes cached repository row before provider hydration`() = runTest {
    val catalogRepository = mockk<CatalogRepository>()
    val tvMetadataRouter = mockk<TvMetadataRouter>()
    val events = mutableListOf<String>()
    val cachedPreview = preview(id = "tt-cached-daily", poster = null).copy(
        name = "Cached catalog title",
        description = "Cached catalog description",
        releaseInfo = "2026"
    )
    val cachedRow = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon.example",
        catalogId = "popular",
        catalogName = "Popular",
        type = ContentType.MOVIE,
        rawType = "movie",
        items = listOf(cachedPreview),
        hasMore = false
    )

    coEvery {
        catalogRepository.refreshCatalogToDisk(
            addonBaseUrl = "https://addon.example",
            addonId = "addon",
            addonName = "Addon",
            catalogId = "popular",
            catalogName = "Popular",
            type = "movie",
            skip = 0,
            skipStep = 100,
            supportsSkip = false
        )
    } returns Result.success(cachedRow)
    coEvery { tvMetadataRouter.fetchEnrichment(any()) } coAnswers {
        events += "provider"
        TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
            value = TvMetadataEnrichment(
                seriesTvdbId = null,
                localizedTitle = "Hydrated title",
                description = "Hydrated description",
                releaseInfo = "2027"
            )
        )
    }

    val refreshed = coordinator(
        catalogRepository = catalogRepository,
        tvMetadataRouter = tvMetadataRouter
    ).refreshSerially(
        addons = listOf(addon()),
        telemetryEnabled = true,
        isCatalogDisabled = { _, _ -> false },
        getCurrentRow = { null },
        isItemReferencedElsewhere = { _, _ -> false },
        onCatalogReady = { catalogKey, row, _ ->
            events += "publish:$catalogKey:${row.items.single().name}"
        },
        onLog = { _, _ -> }
    )

    assertEquals(1, refreshed)
    assertEquals(
        listOf(
            "publish:addon_movie_popular:Cached catalog title",
            "provider",
            "publish:addon_movie_popular:Hydrated title"
        ),
        events
    )
    coVerify(exactly = 1) { tvMetadataRouter.fetchEnrichment(any()) }
}
```

If the local helper names differ because this file has changed in another workstream, adapt only the helper calls (`preview`, `addon`, `coordinator`) to the current test file. Keep the assertion semantics unchanged.

- [ ] **Step 2: Run the coordinator test class**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTest
```

Expected: pass.

- [ ] **Step 3: Commit coordinator regression test**

Stage exact path only:

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt
git status -sb
git commit -m "test(home): preserve addon catalog cached publish order"
```

Before committing, confirm the staged list contains only:

```text
app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt
```

---

### Task 4: Final Validation

**Files:**
- Read/validate: `openspec/changes/add-addon-catalog-daily-freshness`
- Verify changed production/tests from Tasks 2-3

- [ ] **Step 1: Run focused test suite**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.CatalogRepositoryImplTest --tests com.nexio.tv.data.repository.CatalogRepositoryAddonRoutingTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTest
```

Expected: pass.

- [ ] **Step 2: Validate OpenSpec change**

Run:

```bash
openspec validate add-addon-catalog-daily-freshness --strict
```

Expected:

```text
Change 'add-addon-catalog-daily-freshness' is valid
```

- [ ] **Step 3: Inspect diff for forbidden changes**

Run:

```bash
git diff -- app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt app/src/main/java/com/nexio/tv/data/integration/addon/AddonCatalogIntegrationProvider.kt
```

Expected: no output. This confirms the first fix did not remove transport no-cache behavior or migrate runtime semantics.

- [ ] **Step 4: Inspect final status**

Run:

```bash
git status -sb
```

Expected: only unrelated pre-existing worktree changes remain. No addon catalog freshness files should be unstaged if Tasks 2 and 3 were committed.

- [ ] **Step 5: Optional device/HAR verification after implementation branch is installed**

If a rooted early-access build is deployed to `192.168.50.98`, capture a short Home-session HAR after profile selection. Expected result: exact addon catalog URLs such as Top Streaming and Torrentio catalog URLs should appear at most once per cache key within the 24-hour freshness window, while addon stream/playback/account endpoints are unaffected.
