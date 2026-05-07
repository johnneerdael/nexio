# TMDB Company and Network Detail Discovery Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make production companies and TV networks on the title detail screen actually navigable, opening a TMDB-backed detail view with organization metadata and a filtered filmography/catalog row.

**Architecture:** Treat this as a new TMDB capability, not a small UI fix. Extend the existing detail enrichment to preserve TMDB IDs for companies and networks, add a dedicated TMDB organization discovery service for company/network details plus discover results, and introduce a new organization detail route/screen that mirrors the existing cast-detail pattern while staying separate from addon-backed search/discover.

**Tech Stack:** Kotlin, Jetpack Compose for Android TV, Hilt, Coroutines/Flow, Retrofit + Moshi, TMDB v3 APIs, JUnit4 unit tests.

---

## File Map

**OpenSpec / planning**
- Create: `openspec/changes/add-tmdb-organization-detail-discovery/proposal.md`
- Create: `openspec/changes/add-tmdb-organization-detail-discovery/tasks.md`
- Create: `openspec/changes/add-tmdb-organization-detail-discovery/design.md`
- Create: `openspec/changes/add-tmdb-organization-detail-discovery/specs/tmdb-organization-discovery/spec.md`

**Domain / TMDB contracts**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
- Create: `app/src/main/java/com/nexio/tv/domain/model/TmdbOrganizationDetail.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Create: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbOrganizationService.kt`

**Navigation / UI**
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/CompanyLogosSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailUiState.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailViewModel.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Tests**
- Create: `app/src/test/java/com/nexio/tv/core/tmdb/TmdbOrganizationServiceTest.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/navigation/OrganizationDetailRouteTest.kt`

## Task 0: OpenSpec Prerequisite

**Files:**
- Create: `openspec/changes/add-tmdb-organization-detail-discovery/proposal.md`
- Create: `openspec/changes/add-tmdb-organization-detail-discovery/tasks.md`
- Create: `openspec/changes/add-tmdb-organization-detail-discovery/design.md`
- Create: `openspec/changes/add-tmdb-organization-detail-discovery/specs/tmdb-organization-discovery/spec.md`

- [ ] **Step 1: Draft the new capability proposal**

```md
# Change: Add TMDB organization detail discovery

## Why
Production companies and TV networks render as focusable cards on the detail page, but selecting them does nothing. TMDB already provides the data needed to turn those surfaces into real discovery entry points.

## What Changes
- Preserve TMDB company/network IDs in detail enrichment.
- Add TMDB-backed company/network detail discovery.
- Add a dedicated detail route/screen for companies and networks.

## Impact
- Affected specs: tmdb-organization-discovery
- Affected code: detail screen, TMDB API/service layer, navigation
```

- [ ] **Step 2: Add the spec delta**

```md
## ADDED Requirements
### Requirement: TMDB organization detail discovery
The system SHALL allow TMDB-backed production companies and TV networks on detail pages to open a dedicated detail view with organization metadata and matching TMDB discovery results.

#### Scenario: Open movie company detail
- **WHEN** the user selects a production company on a movie detail page
- **THEN** the app opens the organization detail view
- **AND** it loads company metadata from TMDB
- **AND** it loads movie results filtered with that company ID

#### Scenario: Open TV network detail
- **WHEN** the user selects a network on a TV detail page
- **THEN** the app opens the organization detail view
- **AND** it loads network metadata from TMDB
- **AND** it loads TV results filtered with that network ID
```

- [ ] **Step 3: Validate the OpenSpec change**

Run: `openspec validate add-tmdb-organization-detail-discovery --strict`

Expected: validation succeeds with no spec formatting errors.

- [ ] **Step 4: Commit the proposal scaffolding**

```bash
git add openspec/changes/add-tmdb-organization-detail-discovery
git commit -m "spec: add tmdb organization detail discovery proposal"
```

## Task 1: Preserve Organization IDs in Detail Enrichment

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Test: `app/src/test/java/com/nexio/tv/core/tmdb/TmdbOrganizationServiceTest.kt`

- [ ] **Step 1: Write the failing domain/API mapping test**

```kotlin
@Test
fun `metadata enrichment keeps company and network tmdb ids`() = runTest {
    val detail = fakeMovieDetails(
        companies = listOf(fakeCompany(id = 1, name = "Lucasfilm Ltd.")),
        networks = listOf(fakeNetwork(id = 49, name = "HBO"))
    )

    val enrichment = service.fetchEnrichment("11", ContentType.MOVIE, "en-US")

    assertEquals(1, enrichment?.productionCompanies?.single()?.tmdbId)
    assertEquals(MetaCompanyKind.COMPANY, enrichment?.productionCompanies?.single()?.kind)
    assertEquals(49, enrichment?.networks?.single()?.tmdbId)
    assertEquals(MetaCompanyKind.NETWORK, enrichment?.networks?.single()?.kind)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tmdb.TmdbOrganizationServiceTest`

Expected: FAIL because `MetaCompany` and the TMDB DTOs do not yet expose IDs/kinds.

- [ ] **Step 3: Extend the domain and DTOs minimally**

```kotlin
@Immutable
data class MetaCompany(
    val tmdbId: Int? = null,
    val name: String,
    val logo: String? = null,
    val kind: MetaCompanyKind = MetaCompanyKind.COMPANY
)

enum class MetaCompanyKind { COMPANY, NETWORK }
```

```kotlin
data class TmdbCompany(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "logo_path") val logoPath: String? = null
)

data class TmdbNetwork(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "logo_path") val logoPath: String? = null
)
```

- [ ] **Step 4: Update TMDB enrichment mapping**

```kotlin
MetaCompany(
    tmdbId = company.id,
    name = name,
    logo = buildImageUrl(company.logoPath, size = "w300"),
    kind = MetaCompanyKind.COMPANY
)
```

```kotlin
MetaCompany(
    tmdbId = network.id,
    name = name,
    logo = buildImageUrl(network.logoPath, size = "w300"),
    kind = MetaCompanyKind.NETWORK
)
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tmdb.TmdbOrganizationServiceTest`

Expected: PASS for the ID/kind assertions.

- [ ] **Step 6: Commit the enrichment contract change**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/Meta.kt app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt app/src/test/java/com/nexio/tv/core/tmdb/TmdbOrganizationServiceTest.kt
git commit -m "feat: preserve tmdb organization ids in detail enrichment"
```

## Task 2: Add TMDB Organization Detail and Discover Service

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/TmdbOrganizationDetail.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt`
- Create: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbOrganizationService.kt`
- Test: `app/src/test/java/com/nexio/tv/core/tmdb/TmdbOrganizationServiceTest.kt`

- [ ] **Step 1: Write the failing service tests**

```kotlin
@Test
fun `company detail uses company details plus discover movie`() = runTest {
    val detail = service.fetchOrganizationDetail(
        entityId = 1,
        kind = MetaCompanyKind.COMPANY,
        discoverType = OrganizationDiscoverType.MOVIE_COMPANY
    )

    assertEquals("Lucasfilm Ltd.", detail?.name)
    assertEquals("San Francisco, California", detail?.headquarters)
    assertEquals("https://www.lucasfilm.com", detail?.homepage)
    assertEquals(2, detail?.titles?.size)
}

@Test
fun `tv network detail uses with_networks discover`() = runTest {
    service.fetchOrganizationDetail(
        entityId = 49,
        kind = MetaCompanyKind.NETWORK,
        discoverType = OrganizationDiscoverType.TV_NETWORK
    )

    assertEquals(49, fakeApi.lastDiscoverTvWithNetworks)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tmdb.TmdbOrganizationServiceTest`

Expected: FAIL because the discover/detail endpoints and service do not exist yet.

- [ ] **Step 3: Add TMDB API contracts**

```kotlin
@GET("company/{company_id}")
suspend fun getCompanyDetails(...)

@GET("network/{network_id}")
suspend fun getNetworkDetails(...)

@GET("discover/movie")
suspend fun discoverMoviesByCompany(
    @Query("with_companies") companyIds: String,
    ...
)

@GET("discover/tv")
suspend fun discoverTvByCompany(
    @Query("with_companies") companyIds: String,
    ...
)

@GET("discover/tv")
suspend fun discoverTvByNetwork(
    @Query("with_networks") networkId: Int,
    ...
)
```

- [ ] **Step 4: Add the dedicated organization model and service**

```kotlin
data class TmdbOrganizationDetail(
    val tmdbId: Int,
    val kind: MetaCompanyKind,
    val name: String,
    val description: String?,
    val headquarters: String?,
    val homepage: String?,
    val originCountry: String?,
    val logo: String?,
    val parentCompanyName: String?,
    val titles: List<MetaPreview>,
    val totalResults: Int
)
```

```kotlin
suspend fun fetchOrganizationDetail(
    entityId: Int,
    kind: MetaCompanyKind,
    discoverType: OrganizationDiscoverType,
    language: String? = null,
    maxItems: Int = 20
): TmdbOrganizationDetail?
```

- [ ] **Step 5: Reuse one preview mapper for discover results**

```kotlin
private fun TmdbDiscoverResult.toMetaPreview(contentType: ContentType): MetaPreview {
    return MetaPreview(
        id = "tmdb:$id",
        type = contentType,
        name = title ?: name.orEmpty(),
        poster = buildImageUrl(posterPath, "w500"),
        posterShape = PosterShape.POSTER,
        background = buildImageUrl(backdropPath, "w1280"),
        logo = null,
        description = overview?.takeIf { it.isNotBlank() },
        releaseInfo = (releaseDate ?: firstAirDate)?.take(4),
        imdbRating = voteAverage?.toFloat(),
        genres = emptyList()
    )
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tmdb.TmdbOrganizationServiceTest`

Expected: PASS for company detail mapping and TV network discover filter selection.

- [ ] **Step 7: Commit the service layer**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/TmdbOrganizationDetail.kt app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt app/src/main/java/com/nexio/tv/core/tmdb/TmdbOrganizationService.kt app/src/test/java/com/nexio/tv/core/tmdb/TmdbOrganizationServiceTest.kt
git commit -m "feat: add tmdb organization detail discovery service"
```

## Task 3: Add Organization Detail Navigation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/navigation/OrganizationDetailRouteTest.kt`

- [ ] **Step 1: Write the failing route test**

```kotlin
@Test
fun `organization detail route encodes name and discover type`() {
    val route = Screen.OrganizationDetail.createRoute(
        entityId = 49,
        entityName = "HBO Max / Originals",
        kind = MetaCompanyKind.NETWORK,
        discoverType = OrganizationDiscoverType.TV_NETWORK
    )

    assertTrue(route.contains("organization_detail/49/HBO%20Max%20%2F%20Originals"))
    assertTrue(route.contains("kind=NETWORK"))
    assertTrue(route.contains("discoverType=TV_NETWORK"))
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.navigation.OrganizationDetailRouteTest`

Expected: FAIL because the route does not exist yet.

- [ ] **Step 3: Add the new route**

```kotlin
data object OrganizationDetail :
    Screen("organization_detail/{entityId}/{entityName}?kind={kind}&discoverType={discoverType}") {
    fun createRoute(...): String = ...
}
```

- [ ] **Step 4: Register the destination in the nav host**

```kotlin
composable(route = Screen.OrganizationDetail.route, arguments = listOf(...)) {
    OrganizationDetailScreen(
        onBackPress = { navController.popBackStack() },
        onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
            navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
        }
    )
}
```

- [ ] **Step 5: Run the route test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.navigation.OrganizationDetailRouteTest`

Expected: PASS.

- [ ] **Step 6: Commit navigation changes**

```bash
git add app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt app/src/test/java/com/nexio/tv/ui/navigation/OrganizationDetailRouteTest.kt
git commit -m "feat: add organization detail navigation route"
```

## Task 4: Build the Organization Detail Screen

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailUiState.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailViewModel.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/nexio/tv/core/tmdb/TmdbOrganizationServiceTest.kt`

- [ ] **Step 1: Write the failing ViewModel/loading-state test**

```kotlin
@Test
fun `organization detail view model loads titles and metadata`() = runTest {
    val viewModel = OrganizationDetailViewModel(
        tmdbOrganizationService = fakeServiceReturning(
            TmdbOrganizationDetail(
                tmdbId = 49,
                kind = MetaCompanyKind.NETWORK,
                name = "HBO",
                description = null,
                headquarters = "New York City, New York",
                homepage = "https://www.hbo.com",
                originCountry = "US",
                logo = "https://image.tmdb.org/t/p/w300/logo.png",
                parentCompanyName = null,
                titles = listOf(fakePreview("tmdb:100", ContentType.SERIES)),
                totalResults = 1
            )
        ),
        savedStateHandle = savedStateHandleOf(...)
    )

    assertTrue(viewModel.uiState.value is OrganizationDetailUiState.Success)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tmdb.TmdbOrganizationServiceTest`

Expected: FAIL because the ViewModel/state classes do not exist yet.

- [ ] **Step 3: Add the state and ViewModel**

```kotlin
sealed interface OrganizationDetailUiState {
    data object Loading : OrganizationDetailUiState
    data class Success(val detail: TmdbOrganizationDetail) : OrganizationDetailUiState
    data class Error(val message: String) : OrganizationDetailUiState
}
```

- [ ] **Step 4: Build the screen using the cast-detail layout pattern**

```kotlin
OrganizationDetailScreen(
    detail = state.detail,
    onNavigateToDetail = { itemId, itemType, addonBaseUrl -> ... }
)
```

Screen requirements:
- header with logo/name and organization metadata
- company-only description/headquarters/homepage/origin/parent company text
- network-only header without description requirement
- horizontal titles row using `GridContentCard`
- same dark background conventions as home/cast detail

- [ ] **Step 5: Add user-facing strings**

```xml
<string name="organization_detail_titles">Titles</string>
<string name="organization_detail_error">Could not load organization details</string>
<string name="organization_detail_headquarters">Headquarters: %1$s</string>
<string name="organization_detail_homepage">Homepage: %1$s</string>
<string name="organization_detail_origin_country">Country: %1$s</string>
<string name="organization_detail_parent_company">Parent company: %1$s</string>
```

- [ ] **Step 6: Run the targeted tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tmdb.TmdbOrganizationServiceTest --tests com.nexio.tv.ui.navigation.OrganizationDetailRouteTest`

Expected: PASS.

- [ ] **Step 7: Commit the new screen**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/organization app/src/main/res/values/strings.xml app/src/test/java/com/nexio/tv/core/tmdb/TmdbOrganizationServiceTest.kt app/src/test/java/com/nexio/tv/ui/navigation/OrganizationDetailRouteTest.kt
git commit -m "feat: add tmdb organization detail screen"
```

## Task 5: Wire Detail Screen Company and Network Cards

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/CompanyLogosSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`

- [ ] **Step 1: Write the failing interaction test or route-level assertion**

```kotlin
@Test
fun `movie production company routes to organization detail with movie discover type`() {
    val company = MetaCompany(tmdbId = 1, name = "Lucasfilm Ltd.", kind = MetaCompanyKind.COMPANY)

    val route = Screen.OrganizationDetail.createRoute(
        entityId = requireNotNull(company.tmdbId),
        entityName = company.name,
        kind = company.kind,
        discoverType = OrganizationDiscoverType.MOVIE_COMPANY
    )

    assertTrue(route.contains("discoverType=MOVIE_COMPANY"))
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.navigation.OrganizationDetailRouteTest`

Expected: FAIL until the detail screen starts using the new route semantics.

- [ ] **Step 3: Make company cards genuinely clickable**

```kotlin
fun CompanyLogosSection(
    title: String,
    companies: List<MetaCompany>,
    onCompanyClick: (MetaCompany) -> Unit
)
```

```kotlin
Card(
    onClick = { onCompanyClick(company) },
    ...
)
```

- [ ] **Step 4: Route by current title type**

```kotlin
val discoverType = when {
    meta.type == ContentType.MOVIE && company.kind == MetaCompanyKind.COMPANY ->
        OrganizationDiscoverType.MOVIE_COMPANY
    company.kind == MetaCompanyKind.NETWORK ->
        OrganizationDiscoverType.TV_NETWORK
    else ->
        OrganizationDiscoverType.TV_COMPANY
}
```

Only navigate when `company.tmdbId != null`. If the ID is missing, keep the card visually consistent but no-op safely.

- [ ] **Step 5: Pass the new callback from `MetaDetailsScreen` through `NexioNavHost`**

```kotlin
onNavigateToOrganizationDetail = { entityId, entityName, kind, discoverType ->
    navController.navigate(
        Screen.OrganizationDetail.createRoute(entityId, entityName, kind, discoverType)
    )
}
```

- [ ] **Step 6: Run the route tests and a full compile**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.navigation.OrganizationDetailRouteTest`

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS and successful compilation.

- [ ] **Step 7: Commit the UI wiring**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/CompanyLogosSection.kt app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt
git commit -m "feat: wire company and network detail navigation"
```

## Task 6: Final Verification and Hand-off

**Files:**
- Modify: plan/checklist only if needed

- [ ] **Step 1: Run the focused unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tmdb.TmdbOrganizationServiceTest --tests com.nexio.tv.ui.navigation.OrganizationDetailRouteTest`

Expected: all targeted tests PASS.

- [ ] **Step 2: Run app compile verification**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual TV verification**

Verify on device/emulator:
- open a movie detail page and select a production company
- open a TV detail page and select a production company
- open a TV detail page and select a network
- confirm the organization detail header loads
- confirm the horizontal catalog opens items back into normal detail screens

- [ ] **Step 4: Update the OpenSpec task checklist**

Mark all completed items in:
- `openspec/changes/add-tmdb-organization-detail-discovery/tasks.md`

- [ ] **Step 5: Final commit**

```bash
git add app openspec/changes/add-tmdb-organization-detail-discovery docs/superpowers/plans/2026-03-22-tmdb-company-network-detail-discovery.md
git commit -m "feat: add tmdb company and network detail discovery"
```

## Notes / Non-Goals

- Do not try to merge TMDB discovery into the existing addon-backed `DiscoverScreen`; it is a different data source and state model.
- Do not add free-text network search in v1; detail pages already provide network IDs directly from TMDB enrichment.
- Do not expand this into multi-select company/network discover filters yet; keep navigation scoped to the selected card.
- Keep the first version paged to the initial TMDB discover page unless the UI explicitly needs pagination.
