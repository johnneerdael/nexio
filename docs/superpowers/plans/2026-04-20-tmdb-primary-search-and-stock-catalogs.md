# TMDB Primary Search And Stock Catalogs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add app-owned TMDB primary search and built-in TMDB stock catalogs while keeping Cinemeta and other add-on search/catalogs available as secondary sources.

**Architecture:** Introduce a native TMDB discovery layer that maps TMDB search and catalog endpoints into Nexio `MetaPreview` and synthetic `CatalogRow` values. In-app search shows TMDB results first, Home/catalog planning treats configured TMDB rows like existing Trakt/SIMKL/MDBList synthetic rows, and detail/stream navigation keeps existing metadata provider routing with TVDB/Kitsu priority for series. Cinemeta stays installed and searchable during this phase.

**Tech Stack:** Kotlin, Android TV Compose, Hilt, DataStore, Retrofit/Moshi, kotlinx.coroutines Flow, existing TMDB API client and metadata credential resolver, JUnit/MockK coroutine tests.

---

## Scope

Build the first native TMDB discovery slice:

- TMDB primary in-app text search.
- Built-in TMDB stock catalogs:
  - default enabled: Trending Movie, Trending Series, Latest Releases Movie, Latest Releases Series
  - available but default disabled: Popular Movie, Popular Series, Year Movie, Year Series, Language Movie, Language Series
- Adult-content toggle for TMDB search/catalog calls, default `false`.
- Digital-release filtering for Latest Releases, default enabled.
- IMDb ID bridging for TMDB search/catalog items where TMDB exposes an external IMDb ID.
- No removal of Cinemeta.
- No change to metadata provider priority: series detail enrichment remains TVDB-first, with Kitsu first only when the Kitsu mapping path is enabled and authenticated.

Out of scope:

- Removing Cinemeta from default add-ons.
- Replacing Android TV native/global search fallback.
- Replacing idle screensaver fallback.
- Building a public Stremio-compatible TMDB add-on.

## File Structure

- Create `app/src/main/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStore.kt`
  - Owns TMDB catalog IDs, default enabled set, catalog ordering, adult-content setting, digital-release filter setting.
- Create `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt`
  - Fetches TMDB search and catalog rows, maps TMDB DTOs to `MetaPreview`, caches snapshots in memory by active profile.
- Create `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryModels.kt`
  - Holds `TmdbCatalogIds`, `TmdbCatalogPreferences`, `TmdbDiscoverySnapshot`, and result mapping helpers.
- Modify `app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt`
  - Add search, trending, popular, and general discover endpoints needed by the service.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/search/SearchViewModel.kt`
  - Fetch TMDB primary search first and keep add-on search rows below it.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/search/SearchUiState.kt`
  - Keep TMDB primary rows as the first entries in `catalogRows`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt`
  - Include TMDB synthetic catalog keys/descriptors in configured Home catalog planning.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/CatalogPlan.kt`
  - Emit populated TMDB rows from `TmdbDiscoverySnapshot`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt` and `HomeViewModelCatalogPipeline.kt`
  - Observe TMDB catalog preferences/snapshots and trigger refreshes using existing synthetic source patterns.
- Modify `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt`
  - Expose TMDB synthetic rows as feed options without changing recommendation publishing behavior.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt` and `TmdbSettingsViewModel.kt`
  - Add TMDB catalog toggles/order plus adult/digital-release options.
- Modify `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
  - Gate Kitsu metadata calls on authenticated Kitsu when the source was reached through mapping.
- Test files:
  - `app/src/test/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStoreTest.kt`
  - `app/src/test/java/com/nexio/tv/data/repository/TmdbDiscoveryServiceTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/search/SearchViewModelTmdbTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTmdbCatalogPlanTest.kt`
  - `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterKitsuAuthGateTest.kt`

## API References

The endpoint and parameter choices below were checked against the local OpenAPI export at `tmdb.json`:

- `tmdb.json` paths include `/3/search/movie`, `/3/search/tv`, `/3/trending/movie/{time_window}`, `/3/trending/tv/{time_window}`, `/3/movie/popular`, `/3/tv/popular`, `/3/discover/movie`, and `/3/discover/tv`.
- Planned query parameters are present in that schema: `query`, `include_adult`, `language`, `page`, `primary_release_year`, `first_air_date_year`, `release_date.lte`, `first_air_date.lte`, `with_original_language`, `with_release_type`, `sort_by`, and `region` where used.
- `tmdb.json` represents authentication through OpenAPI security rather than an `api_key` query parameter. Keep `@Query("api_key")` in the Android Retrofit methods for this plan because the existing app `TmdbApi` uses query-key authentication consistently for all current TMDB calls.
- Android Retrofit paths intentionally omit the `/3/` prefix because `MetadataProviderConfig.DEFAULT_TMDB_API_URL` already ends in `/3/`.

- TMDB finding/search/discover overview: `https://developer.themoviedb.org/docs/finding-data`
- TMDB movie search: `https://developer.themoviedb.org/reference/search-movie`
- TMDB TV search: `https://developer.themoviedb.org/reference/search-tv`
- TMDB trending movies: `https://developer.themoviedb.org/reference/trending-movies`
- TMDB discover movie: `https://developer.themoviedb.org/reference/discover-movie`
- TMDB discover TV: `https://developer.themoviedb.org/reference/discover-tv`
- TMDB popular movies: `https://developer.themoviedb.org/reference/movie-popular-list`
- TMDB popular TV: `https://developer.themoviedb.org/reference/tv-series-popular-list`

---

### Task 1: Add TMDB Catalog Preferences

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStoreTest.kt`

- [ ] **Step 1: Write the catalog defaults test**

Add this test class:

```kotlin
package com.nexio.tv.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmdbCatalogSettingsDataStoreTest {
    @Test
    fun `tmdb catalog defaults enable trending and latest release catalogs only`() {
        assertEquals(
            listOf(
                TmdbCatalogIds.TRENDING_MOVIES,
                TmdbCatalogIds.TRENDING_SERIES,
                TmdbCatalogIds.LATEST_RELEASES_MOVIES,
                TmdbCatalogIds.LATEST_RELEASES_SERIES,
                TmdbCatalogIds.POPULAR_MOVIES,
                TmdbCatalogIds.POPULAR_SERIES,
                TmdbCatalogIds.YEAR_MOVIES,
                TmdbCatalogIds.YEAR_SERIES,
                TmdbCatalogIds.LANGUAGE_MOVIES,
                TmdbCatalogIds.LANGUAGE_SERIES
            ),
            TmdbCatalogIds.BUILT_IN_ORDER
        )
        assertEquals(
            setOf(
                TmdbCatalogIds.TRENDING_MOVIES,
                TmdbCatalogIds.TRENDING_SERIES,
                TmdbCatalogIds.LATEST_RELEASES_MOVIES,
                TmdbCatalogIds.LATEST_RELEASES_SERIES
            ),
            TmdbCatalogIds.DEFAULT_ENABLED
        )
    }

    @Test
    fun `catalog preference sanitizer drops unknown ids and preserves known order`() {
        val prefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf("unknown", TmdbCatalogIds.POPULAR_SERIES),
            catalogOrder = listOf(
                TmdbCatalogIds.POPULAR_SERIES,
                "unknown",
                TmdbCatalogIds.TRENDING_MOVIES
            ),
            includeAdult = true,
            hideUnreleasedDigital = false
        ).sanitized()

        assertEquals(setOf(TmdbCatalogIds.POPULAR_SERIES), prefs.enabledCatalogs)
        assertEquals(TmdbCatalogIds.POPULAR_SERIES, prefs.catalogOrder.first())
        assertEquals(TmdbCatalogIds.TRENDING_MOVIES, prefs.catalogOrder[1])
        assertTrue(prefs.includeAdult)
        assertFalse(prefs.hideUnreleasedDigital)
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.TmdbCatalogSettingsDataStoreTest
```

Expected: fail because `TmdbCatalogIds`, `TmdbCatalogPreferences`, and `TmdbCatalogSettingsDataStore` do not exist.

- [ ] **Step 3: Add the preference model and datastore**

Create `app/src/main/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStore.kt`:

```kotlin
package com.nexio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nexio.tv.core.profile.ProfileManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

object TmdbCatalogIds {
    const val TRENDING_MOVIES = "tmdb_trending_movies"
    const val TRENDING_SERIES = "tmdb_trending_series"
    const val LATEST_RELEASES_MOVIES = "tmdb_latest_releases_movies"
    const val LATEST_RELEASES_SERIES = "tmdb_latest_releases_series"
    const val POPULAR_MOVIES = "tmdb_popular_movies"
    const val POPULAR_SERIES = "tmdb_popular_series"
    const val YEAR_MOVIES = "tmdb_year_movies"
    const val YEAR_SERIES = "tmdb_year_series"
    const val LANGUAGE_MOVIES = "tmdb_language_movies"
    const val LANGUAGE_SERIES = "tmdb_language_series"

    val BUILT_IN_ORDER: List<String> = listOf(
        TRENDING_MOVIES,
        TRENDING_SERIES,
        LATEST_RELEASES_MOVIES,
        LATEST_RELEASES_SERIES,
        POPULAR_MOVIES,
        POPULAR_SERIES,
        YEAR_MOVIES,
        YEAR_SERIES,
        LANGUAGE_MOVIES,
        LANGUAGE_SERIES
    )

    val DEFAULT_ENABLED: Set<String> = setOf(
        TRENDING_MOVIES,
        TRENDING_SERIES,
        LATEST_RELEASES_MOVIES,
        LATEST_RELEASES_SERIES
    )
}

data class TmdbCatalogPreferences(
    val enabledCatalogs: Set<String> = TmdbCatalogIds.DEFAULT_ENABLED,
    val catalogOrder: List<String> = TmdbCatalogIds.BUILT_IN_ORDER,
    val includeAdult: Boolean = false,
    val hideUnreleasedDigital: Boolean = true
) {
    fun sanitized(): TmdbCatalogPreferences {
        val known = TmdbCatalogIds.BUILT_IN_ORDER.toSet()
        val enabled = enabledCatalogs.filterTo(linkedSetOf()) { it in known }
        val orderedKnown = catalogOrder.filter { it in known }.distinct()
        return copy(
            enabledCatalogs = enabled,
            catalogOrder = orderedKnown + TmdbCatalogIds.BUILT_IN_ORDER.filterNot { it in orderedKnown }
        )
    }
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TmdbCatalogSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "tmdb_catalog_settings"
    }

    private val catalogEnabledSetKey = stringSetPreferencesKey("catalog_enabled_set")
    private val catalogOrderCsvKey = stringPreferencesKey("catalog_order_csv")
    private val includeAdultKey = booleanPreferencesKey("include_adult")
    private val hideUnreleasedDigitalKey = booleanPreferencesKey("hide_unreleased_digital")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val catalogPreferences: Flow<TmdbCatalogPreferences> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs ->
            TmdbCatalogPreferences(
                enabledCatalogs = prefs[catalogEnabledSetKey] ?: TmdbCatalogIds.DEFAULT_ENABLED,
                catalogOrder = prefs[catalogOrderCsvKey]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: TmdbCatalogIds.BUILT_IN_ORDER,
                includeAdult = prefs[includeAdultKey] ?: false,
                hideUnreleasedDigital = prefs[hideUnreleasedDigitalKey] ?: true
            ).sanitized()
        }
    }

    suspend fun setCatalogEnabled(catalogId: String, enabled: Boolean) {
        if (catalogId !in TmdbCatalogIds.BUILT_IN_ORDER) return
        store().edit { prefs ->
            val current = TmdbCatalogPreferences(
                enabledCatalogs = prefs[catalogEnabledSetKey] ?: TmdbCatalogIds.DEFAULT_ENABLED
            ).sanitized().enabledCatalogs
            prefs[catalogEnabledSetKey] = if (enabled) current + catalogId else current - catalogId
        }
    }

    suspend fun moveCatalog(catalogId: String, direction: Int) {
        if (catalogId !in TmdbCatalogIds.BUILT_IN_ORDER || direction == 0) return
        store().edit { prefs ->
            val current = TmdbCatalogPreferences(
                catalogOrder = prefs[catalogOrderCsvKey]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: TmdbCatalogIds.BUILT_IN_ORDER
            ).sanitized().catalogOrder.toMutableList()
            val index = current.indexOf(catalogId)
            if (index == -1) return@edit
            val target = (index + direction).coerceIn(0, current.lastIndex)
            if (target == index) return@edit
            current.removeAt(index)
            current.add(target, catalogId)
            prefs[catalogOrderCsvKey] = current.joinToString(",")
        }
    }

    suspend fun setIncludeAdult(enabled: Boolean) {
        store().edit { prefs -> prefs[includeAdultKey] = enabled }
    }

    suspend fun setHideUnreleasedDigital(enabled: Boolean) {
        store().edit { prefs -> prefs[hideUnreleasedDigitalKey] = enabled }
    }
}
```

- [ ] **Step 4: Run the test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.TmdbCatalogSettingsDataStoreTest
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStore.kt app/src/test/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStoreTest.kt
git commit -m "feat: add tmdb catalog preferences"
```

---

### Task 2: Add TMDB Search And Catalog API Methods

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt`
- Test: `app/src/test/java/com/nexio/tv/core/tmdb/TmdbApiContractTest.kt`

- [ ] **Step 1: Write the Retrofit contract test**

Create `app/src/test/java/com/nexio/tv/core/tmdb/TmdbApiContractTest.kt`:

```kotlin
package com.nexio.tv.core.tmdb

import com.nexio.tv.data.remote.api.TmdbApi
import kotlin.test.Test
import kotlin.test.assertEquals
import retrofit2.http.GET

class TmdbApiContractTest {
    @Test
    fun `tmdb api exposes search and stock catalog endpoints`() {
        assertEquals("search/movie", getPath("searchMovies"))
        assertEquals("search/tv", getPath("searchTv"))
        assertEquals("trending/movie/{time_window}", getPath("getTrendingMovies"))
        assertEquals("trending/tv/{time_window}", getPath("getTrendingTv"))
        assertEquals("movie/popular", getPath("getPopularMovies"))
        assertEquals("tv/popular", getPath("getPopularTv"))
        assertEquals("discover/movie", getPath("discoverMovies"))
        assertEquals("discover/tv", getPath("discoverTv"))
    }

    private fun getPath(methodName: String): String {
        return TmdbApi::class.java.methods
            .first { it.name == methodName }
            .getAnnotation(GET::class.java)
            ?.value
            ?: error("Missing @GET on $methodName")
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tmdb.TmdbApiContractTest
```

Expected: fail because these methods are missing.

- [ ] **Step 3: Add endpoints and shared list DTOs**

Modify `TmdbApi.kt` with these methods inside `interface TmdbApi`:

Use the local `tmdb.json` trending contract: the trending endpoints expose `time_window` and `language` only. Do not add a `page` query to trending methods.

```kotlin
    @GET("search/movie")
    suspend fun searchMovies(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String? = null,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("page") page: Int = 1
    ): Response<TmdbPagedMediaResponse>

    @GET("search/tv")
    suspend fun searchTv(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String? = null,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("page") page: Int = 1
    ): Response<TmdbPagedMediaResponse>

    @GET("trending/movie/{time_window}")
    suspend fun getTrendingMovies(
        @Path("time_window") timeWindow: String = "day",
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbPagedMediaResponse>

    @GET("trending/tv/{time_window}")
    suspend fun getTrendingTv(
        @Path("time_window") timeWindow: String = "day",
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbPagedMediaResponse>

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1,
        @Query("region") region: String? = null
    ): Response<TmdbPagedMediaResponse>

    @GET("tv/popular")
    suspend fun getPopularTv(
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1
    ): Response<TmdbPagedMediaResponse>

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("include_video") includeVideo: Boolean = false,
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("primary_release_year") primaryReleaseYear: Int? = null,
        @Query("primary_release_date.lte") primaryReleaseDateLte: String? = null,
        @Query("release_date.lte") releaseDateLte: String? = null,
        @Query("with_original_language") withOriginalLanguage: String? = null,
        @Query("with_release_type") withReleaseType: String? = null,
        @Query("region") region: String? = null
    ): Response<TmdbPagedMediaResponse>

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("include_null_first_air_dates") includeNullFirstAirDates: Boolean = false,
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("first_air_date_year") firstAirDateYear: Int? = null,
        @Query("first_air_date.lte") firstAirDateLte: String? = null,
        @Query("with_original_language") withOriginalLanguage: String? = null
    ): Response<TmdbPagedMediaResponse>
```

Add these DTOs near the existing TMDB response DTOs:

```kotlin
@JsonClass(generateAdapter = true)
data class TmdbPagedMediaResponse(
    @Json(name = "page") val page: Int? = null,
    @Json(name = "total_pages") val totalPages: Int? = null,
    @Json(name = "total_results") val totalResults: Int? = null,
    @Json(name = "results") val results: List<TmdbMediaResult>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbMediaResult(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null
)
```

- [ ] **Step 4: Run the API contract test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tmdb.TmdbApiContractTest
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt app/src/test/java/com/nexio/tv/core/tmdb/TmdbApiContractTest.kt
git commit -m "feat: add tmdb discovery api methods"
```

---

### Task 3: Build TMDB Discovery Service

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryModels.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TmdbDiscoveryServiceTest.kt`

- [ ] **Step 1: Write service behavior tests**

Create `TmdbDiscoveryServiceTest.kt` with fakes rather than Retrofit mocks:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TmdbDiscoveryServiceTest {
    @Test
    fun `search returns movies and series as one tmdb primary row with imdb ids when available`() = runTest {
        val fake = FakeTmdbDiscoveryClient(
            movieSearch = listOf(
                TmdbMediaResult(id = 603, title = "The Matrix", releaseDate = "1999-03-31", posterPath = "/poster.jpg")
            ),
            tvSearch = listOf(
                TmdbMediaResult(id = 1399, name = "Game of Thrones", firstAirDate = "2011-04-17", backdropPath = "/bg.jpg")
            ),
            imdbIds = mapOf("movie:603" to "tt0133093", "series:1399" to "tt0944947")
        )
        val service = fake.createService()

        val row = service.search("matrix", TmdbCatalogPreferences()).single()

        assertEquals("tmdb_search", row.catalogId)
        assertEquals("TMDB Search", row.catalogName)
        assertEquals("tt0133093", row.items[0].id)
        assertEquals(ContentType.MOVIE, row.items[0].type)
        assertEquals("tt0944947", row.items[1].id)
        assertEquals(ContentType.SERIES, row.items[1].type)
    }

    @Test
    fun `enabled stock catalogs fetch only requested catalog ids`() = runTest {
        val fake = FakeTmdbDiscoveryClient(
            catalogResults = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to listOf(TmdbMediaResult(id = 1, title = "Movie")),
                TmdbCatalogIds.POPULAR_MOVIES to listOf(TmdbMediaResult(id = 2, title = "Popular"))
            ),
            imdbIds = mapOf("movie:1" to "tt0000001", "movie:2" to "tt0000002")
        )
        val service = fake.createService()

        val snapshot = service.refreshCatalogs(
            preferences = TmdbCatalogPreferences(enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES)),
            force = true
        )

        assertEquals(setOf(TmdbCatalogIds.TRENDING_MOVIES), snapshot.rowsByCatalog.keys)
        assertEquals(listOf(TmdbCatalogIds.TRENDING_MOVIES), fake.requestedCatalogIds)
    }

    @Test
    fun `missing tmdb credential returns empty search and empty catalog snapshot`() = runTest {
        val fake = FakeTmdbDiscoveryClient(
            credential = MetadataProviderCredential("", source = MetadataCredentialSource.MISSING)
        )
        val service = fake.createService()

        assertTrue(service.search("alien", TmdbCatalogPreferences()).isEmpty())
        assertTrue(service.refreshCatalogs(TmdbCatalogPreferences(), force = true).rowsByCatalog.isEmpty())
    }
}
```

Add local fake classes in the same file. Use a simple interface introduced in Step 3 so tests do not need Retrofit:

```kotlin
private class FakeTmdbDiscoveryClient(
    private val credential: MetadataProviderCredential = MetadataProviderCredential("key", source = MetadataCredentialSource.BUILT_IN),
    private val movieSearch: List<TmdbMediaResult> = emptyList(),
    private val tvSearch: List<TmdbMediaResult> = emptyList(),
    private val catalogResults: Map<String, List<TmdbMediaResult>> = emptyMap(),
    private val imdbIds: Map<String, String> = emptyMap()
) : TmdbDiscoveryClient {
    val requestedCatalogIds = mutableListOf<String>()

    override suspend fun credential(): MetadataProviderCredential = credential
    override suspend fun searchMovies(query: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult> = movieSearch
    override suspend fun searchTv(query: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult> = tvSearch
    override suspend fun fetchCatalog(catalogId: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult> {
        requestedCatalogIds += catalogId
        return catalogResults[catalogId].orEmpty()
    }
    override suspend fun imdbId(tmdbId: Int, contentType: ContentType): String? {
        val key = if (contentType == ContentType.MOVIE) "movie:$tmdbId" else "series:$tmdbId"
        return imdbIds[key]
    }

    fun createService(): TmdbDiscoveryService = TmdbDiscoveryService(client = this)
}
```

- [ ] **Step 2: Run the failing service tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.TmdbDiscoveryServiceTest
```

Expected: fail because the service and client interfaces do not exist.

- [ ] **Step 3: Implement models and service**

Create `TmdbDiscoveryModels.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.domain.model.CatalogRow

data class TmdbDiscoverySnapshot(
    val rowsByCatalog: Map<String, CatalogRow> = emptyMap(),
    val updatedAtMs: Long = 0L
)

fun tmdbCatalogTitle(catalogId: String): String? = when (catalogId) {
    TmdbCatalogIds.TRENDING_MOVIES -> "TMDB Trending Movies"
    TmdbCatalogIds.TRENDING_SERIES -> "TMDB Trending Series"
    TmdbCatalogIds.LATEST_RELEASES_MOVIES -> "TMDB Latest Releases Movies"
    TmdbCatalogIds.LATEST_RELEASES_SERIES -> "TMDB Latest Releases Series"
    TmdbCatalogIds.POPULAR_MOVIES -> "TMDB Popular Movies"
    TmdbCatalogIds.POPULAR_SERIES -> "TMDB Popular Series"
    TmdbCatalogIds.YEAR_MOVIES -> "TMDB Movies By Year"
    TmdbCatalogIds.YEAR_SERIES -> "TMDB Series By Year"
    TmdbCatalogIds.LANGUAGE_MOVIES -> "TMDB Movies By Language"
    TmdbCatalogIds.LANGUAGE_SERIES -> "TMDB Series By Language"
    else -> null
}
```

Create `TmdbDiscoveryService.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.MetadataApiKeyResolver
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

interface TmdbDiscoveryClient {
    suspend fun credential(): MetadataProviderCredential
    suspend fun searchMovies(query: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult>
    suspend fun searchTv(query: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult>
    suspend fun fetchCatalog(catalogId: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult>
    suspend fun imdbId(tmdbId: Int, contentType: ContentType): String?
}

@Singleton
class RetrofitTmdbDiscoveryClient @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val metadataApiKeyResolver: MetadataApiKeyResolver,
    private val tmdbService: TmdbService
) : TmdbDiscoveryClient {
    override suspend fun credential(): MetadataProviderCredential = metadataApiKeyResolver.tmdbCredential()

    override suspend fun searchMovies(query: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult> {
        val apiKey = credential().apiKey
        return tmdbApi.searchMovies(apiKey, query, includeAdult = preferences.includeAdult).body()?.results.orEmpty()
    }

    override suspend fun searchTv(query: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult> {
        val apiKey = credential().apiKey
        return tmdbApi.searchTv(apiKey, query, includeAdult = preferences.includeAdult).body()?.results.orEmpty()
    }

    override suspend fun fetchCatalog(catalogId: String, preferences: TmdbCatalogPreferences): List<TmdbMediaResult> {
        val apiKey = credential().apiKey
        val today = LocalDate.now().toString()
        val response = when (catalogId) {
            TmdbCatalogIds.TRENDING_MOVIES -> tmdbApi.getTrendingMovies(apiKey = apiKey).body()
            TmdbCatalogIds.TRENDING_SERIES -> tmdbApi.getTrendingTv(apiKey = apiKey).body()
            TmdbCatalogIds.LATEST_RELEASES_MOVIES -> tmdbApi.discoverMovies(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "release_date.desc",
                releaseDateLte = today,
                withReleaseType = if (preferences.hideUnreleasedDigital) "4" else null
            ).body()
            TmdbCatalogIds.LATEST_RELEASES_SERIES -> tmdbApi.discoverTv(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "first_air_date.desc",
                firstAirDateLte = today
            ).body()
            TmdbCatalogIds.POPULAR_MOVIES -> tmdbApi.getPopularMovies(apiKey = apiKey).body()
            TmdbCatalogIds.POPULAR_SERIES -> tmdbApi.getPopularTv(apiKey = apiKey).body()
            TmdbCatalogIds.YEAR_MOVIES -> tmdbApi.discoverMovies(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "popularity.desc",
                primaryReleaseYear = LocalDate.now().year
            ).body()
            TmdbCatalogIds.YEAR_SERIES -> tmdbApi.discoverTv(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "popularity.desc",
                firstAirDateYear = LocalDate.now().year
            ).body()
            TmdbCatalogIds.LANGUAGE_MOVIES -> tmdbApi.discoverMovies(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "popularity.desc",
                withOriginalLanguage = "en"
            ).body()
            TmdbCatalogIds.LANGUAGE_SERIES -> tmdbApi.discoverTv(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "popularity.desc",
                withOriginalLanguage = "en"
            ).body()
            else -> null
        }
        return response?.results.orEmpty()
    }

    override suspend fun imdbId(tmdbId: Int, contentType: ContentType): String? {
        return tmdbService.tmdbToImdb(tmdbId, contentType.toApiString())
    }
}

@Singleton
class TmdbDiscoveryService @Inject constructor(
    private val client: TmdbDiscoveryClient
) {
    suspend fun search(query: String, preferences: TmdbCatalogPreferences): List<CatalogRow> {
        val trimmed = query.trim()
        if (trimmed.length < 2 || client.credential().missing) return emptyList()
        val items = coroutineScope {
            val movies = async { client.searchMovies(trimmed, preferences).mapToPreviews(ContentType.MOVIE) }
            val series = async { client.searchTv(trimmed, preferences).mapToPreviews(ContentType.SERIES) }
            movies.await() + series.await()
        }.distinctBy { "${it.apiType}:${it.id}" }
        if (items.isEmpty()) return emptyList()
        return listOf(tmdbRow("tmdb_search", "TMDB Search", ContentType.UNKNOWN, items, hasMore = false))
    }

    suspend fun refreshCatalogs(preferences: TmdbCatalogPreferences, force: Boolean): TmdbDiscoverySnapshot {
        if (client.credential().missing) return TmdbDiscoverySnapshot()
        val enabled = preferences.sanitized().enabledCatalogs
        val rows = coroutineScope {
            enabled.map { catalogId ->
                async {
                    val title = tmdbCatalogTitle(catalogId) ?: return@async null
                    val type = catalogContentType(catalogId)
                    val items = client.fetchCatalog(catalogId, preferences).mapToPreviews(type)
                    if (items.isEmpty()) null else catalogId to tmdbRow(catalogId, title, type, items, hasMore = false)
                }
            }.awaitAll().filterNotNull().toMap()
        }
        return TmdbDiscoverySnapshot(rowsByCatalog = rows, updatedAtMs = System.currentTimeMillis())
    }

    private suspend fun List<TmdbMediaResult>.mapToPreviews(type: ContentType): List<MetaPreview> {
        return coroutineScope {
            take(20).map { result ->
                async { result.toPreview(type) }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun TmdbMediaResult.toPreview(type: ContentType): MetaPreview? {
        val title = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
        val imdb = client.imdbId(id, type)
        val stableId = imdb ?: "tmdb:$id"
        val backdrop = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
        val poster = posterPath?.let { "https://image.tmdb.org/t/p/w780$it" }
        return MetaPreview(
            id = stableId,
            type = type,
            rawType = type.toApiString(),
            name = title,
            poster = backdrop ?: poster,
            posterShape = if (backdrop != null) PosterShape.LANDSCAPE else PosterShape.POSTER,
            background = backdrop,
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = releaseDate ?: firstAirDate,
            imdbRating = voteAverage?.toFloat(),
            ratingSource = TitleRatingSource.TMDB,
            genres = emptyList(),
            language = originalLanguage?.takeIf { it.isNotBlank() }
        )
    }

    private fun tmdbRow(
        catalogId: String,
        catalogName: String,
        type: ContentType,
        items: List<MetaPreview>,
        hasMore: Boolean
    ): CatalogRow {
        return CatalogRow(
            addonId = TMDB_HOME_ADDON_ID,
            addonName = TMDB_HOME_ADDON_NAME,
            addonBaseUrl = TMDB_HOME_ADDON_BASE_URL,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            rawType = type.toApiString("catalog"),
            items = items,
            isLoading = false,
            hasMore = hasMore,
            supportsSkip = hasMore
        )
    }

    private fun catalogContentType(catalogId: String): ContentType = when (catalogId) {
        TmdbCatalogIds.TRENDING_MOVIES,
        TmdbCatalogIds.LATEST_RELEASES_MOVIES,
        TmdbCatalogIds.POPULAR_MOVIES,
        TmdbCatalogIds.YEAR_MOVIES,
        TmdbCatalogIds.LANGUAGE_MOVIES -> ContentType.MOVIE
        else -> ContentType.SERIES
    }

    companion object {
        const val TMDB_HOME_ADDON_ID = "tmdb"
        const val TMDB_HOME_ADDON_NAME = "TMDB"
        const val TMDB_HOME_ADDON_BASE_URL = "https://api.themoviedb.org/3"
    }
}
```

- [ ] **Step 3a: Keep stock rows non-paged for this slice**

TMDB stock endpoints can be paged, but this task does not add a Home/search load-more path that routes back into `TmdbDiscoveryService`. Until that path exists, stock rows must not advertise add-on-style pagination; otherwise existing load-more code can try to treat `https://api.themoviedb.org/3` as a Stremio add-on base URL. Keep `hasMore = false` and `supportsSkip = false` for TMDB stock rows in this phase. Add an explicit test for those flags.

- [ ] **Step 4: Run service tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.TmdbDiscoveryServiceTest
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryModels.kt app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt app/src/test/java/com/nexio/tv/data/repository/TmdbDiscoveryServiceTest.kt
git commit -m "feat: add tmdb discovery service"
```

---

### Task 4: Make In-App Search TMDB-Primary

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/search/SearchViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/search/SearchUiState.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/search/SearchViewModelTmdbTest.kt`

- [ ] **Step 1: Write search ordering test**

Add a focused test using existing `SearchViewModel` fakes in `SearchViewModelTmdbTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.search

import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.repository.TmdbDiscoveryService
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchViewModelTmdbTest {
    @Test
    fun `submitted search places tmdb row before addon rows`() = runTest {
        val tmdb = mockk<TmdbDiscoveryService>()
        coEvery { tmdb.search("alien", any<TmdbCatalogPreferences>()) } returns listOf(
            CatalogRow(
                addonId = "tmdb",
                addonName = "TMDB",
                addonBaseUrl = "https://api.themoviedb.org/3",
                catalogId = "tmdb_search",
                catalogName = "TMDB Search",
                type = ContentType.UNKNOWN,
                items = listOf(meta("tt0078748", "Alien")),
                isLoading = false
            )
        )

        val harness = SearchViewModelHarness(tmdbDiscoveryService = tmdb)
        harness.viewModel.onEvent(SearchEvent.QueryChanged("alien"))
        harness.viewModel.onEvent(SearchEvent.SubmitSearch)
        harness.advanceUntilIdle()

        assertEquals("tmdb_search", harness.viewModel.uiState.value.catalogRows.first().catalogId)
    }

    private fun meta(id: String, name: String): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "1979-05-25",
        imdbRating = null,
        genres = emptyList()
    )
}
```

Create `SearchViewModelHarness` in this test file by mirroring the fake repositories used by `SearchViewModelHistoryTest` and `SearchKeyboardCompletionTest`; inject the fake `TmdbDiscoveryService` through the constructor.

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.search.SearchViewModelTmdbTest
```

Expected: fail because `SearchViewModel` does not accept or query `TmdbDiscoveryService`.

- [ ] **Step 3: Inject TMDB dependencies**

Modify the `SearchViewModel` constructor:

```kotlin
class SearchViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val searchHistoryDataStore: SearchHistoryDataStore,
    private val tmdbDiscoveryService: TmdbDiscoveryService,
    private val tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore
) : ViewModel() {
```

Add imports:

```kotlin
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.repository.TmdbDiscoveryService
```

- [ ] **Step 4: Fetch and publish TMDB first**

In `performSearch`, before building add-on search targets, fetch TMDB rows:

```kotlin
            val tmdbRows = runCatching {
                tmdbDiscoveryService.search(
                    query = query,
                    preferences = tmdbCatalogSettingsDataStore.catalogPreferences.first()
                )
            }.getOrDefault(emptyList())

            tmdbRows.forEach { row ->
                val key = catalogKey(row.addonId, row.apiType, row.catalogId)
                if (key !in catalogOrder) catalogOrder.add(key)
                catalogsMap[key] = row
            }
            if (tmdbRows.isNotEmpty()) {
                hasRenderedFirstCatalog = true
                updateCatalogRowsNow()
            }
```

Keep the existing add-on search block unchanged after this. This preserves Cinemeta/add-on search as secondary rows.

- [ ] **Step 5: Keep suggestions add-on-backed for now**

Leave `fetchSuggestions` unchanged. The primary user-facing TMDB search result is on explicit submit, while keyboard suggestions continue to use existing add-on search catalogs. This keeps the first slice small and avoids debounced TMDB traffic.

- [ ] **Step 6: Run search tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.search.SearchViewModelTmdbTest --tests com.nexio.tv.ui.screens.search.SearchViewModelHistoryTest --tests com.nexio.tv.ui.screens.search.SearchKeyboardCompletionTest
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/search/SearchViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/search/SearchUiState.kt app/src/test/java/com/nexio/tv/ui/screens/search/SearchViewModelTmdbTest.kt
git commit -m "feat: make in-app search tmdb primary"
```

---

### Task 5: Add TMDB Stock Catalogs To Home Planning

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/CatalogPlan.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTmdbCatalogPlanTest.kt`

- [ ] **Step 1: Write catalog plan test**

Create `HomeViewModelTmdbCatalogPlanTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.data.repository.TmdbDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeViewModelTmdbCatalogPlanTest {
    @Test
    fun `configured home plan includes enabled tmdb synthetic rows before addon rows`() {
        val tmdbRow = CatalogRow(
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "https://api.themoviedb.org/3",
            catalogId = TmdbCatalogIds.TRENDING_MOVIES,
            catalogName = "TMDB Trending Movies",
            type = ContentType.MOVIE,
            items = listOf(meta("tt0000001", "Movie")),
            isLoading = false
        )

        val plan = buildConfiguredCatalogPlan(
            addons = emptyList(),
            disabledHomeCatalogKeys = emptySet(),
            availableAddonOrderKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(),
            traktSnapshot = TraktDiscoverySnapshot(),
            hasTraktUpNextItems = false,
            simklPrefs = SimklCatalogPreferences(),
            simklSnapshot = SimklDiscoverySnapshot(),
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot(),
            tmdbPrefs = TmdbCatalogPreferences(enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES)),
            tmdbSnapshot = TmdbDiscoverySnapshot(rowsByCatalog = mapOf(TmdbCatalogIds.TRENDING_MOVIES to tmdbRow))
        )

        assertEquals(listOf(TmdbCatalogIds.TRENDING_MOVIES), plan.publishableOrderKeys)
        assertEquals("TMDB Trending Movies", plan.rails.single().toPopulatedRows().single().catalogName)
    }

    private fun meta(id: String, name: String): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeViewModelTmdbCatalogPlanTest
```

Expected: fail because `buildConfiguredCatalogPlan` does not accept TMDB prefs/snapshot.

- [ ] **Step 3: Add TMDB constants and planner inputs**

In `HomeViewModelCatalogUtils.kt`, add:

```kotlin
internal const val TMDB_HOME_ADDON_ID = "tmdb"
internal const val TMDB_HOME_KEY_PREFIX = "tmdb_"
```

Update `buildConfiguredCatalogPlan` in `CatalogPlan.kt` to accept:

```kotlin
    tmdbPrefs: TmdbCatalogPreferences,
    tmdbSnapshot: TmdbDiscoverySnapshot,
```

Pass those through to expected keys, publishable keys, and descriptors.

- [ ] **Step 4: Build TMDB descriptors and publishable keys**

Add helper functions in `HomeViewModelCatalogUtils.kt`:

```kotlin
internal fun buildExpectedConfiguredTmdbOrderKeys(
    prefs: TmdbCatalogPreferences
): List<String> {
    val orderedEnabled = prefs.catalogOrder.filter { it in prefs.enabledCatalogs }
    val remainingEnabled = prefs.enabledCatalogs.filterNot { it in orderedEnabled }
    return (orderedEnabled + remainingEnabled).distinct()
}

internal fun buildPublishableConfiguredTmdbOrderKeys(
    prefs: TmdbCatalogPreferences,
    snapshot: TmdbDiscoverySnapshot
): List<String> {
    val available = snapshot.rowsByCatalog.filterValues { it.items.isNotEmpty() }.keys
    return buildExpectedConfiguredTmdbOrderKeys(prefs).filter { it in available }
}
```

In `buildConfiguredHomeCatalogDescriptors`, add TMDB before add-on descriptors:

```kotlin
    buildExpectedConfiguredTmdbOrderKeys(tmdbPrefs)
        .filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
        .forEach { key ->
            val row = tmdbSnapshot.rowsByCatalog[key]
            val type = row?.type ?: tmdbCatalogContentType(key)
            descriptorsByKey[key] = ConfiguredHomeCatalogDescriptor(
                orderKey = key,
                addonId = TMDB_HOME_ADDON_ID,
                addonName = "TMDB",
                addonBaseUrl = "https://api.themoviedb.org/3",
                catalogId = key,
                catalogName = row?.catalogName ?: tmdbCatalogTitle(key) ?: humanizeCatalogKey(key),
                type = type,
                rawType = type.toApiString("catalog")
            )
        }
```

Add:

```kotlin
private fun tmdbCatalogContentType(key: String): ContentType {
    return when {
        key.endsWith("_movies") -> ContentType.MOVIE
        else -> ContentType.SERIES
    }
}
```

- [ ] **Step 5: Emit populated TMDB rows**

In `CatalogPlan.kt`, add a `TMDB_HOME_ADDON_ID` branch:

```kotlin
            TMDB_HOME_ADDON_ID -> tmdbSnapshot.rowsByCatalog[key]?.let(::listOf).orEmpty()
```

- [ ] **Step 6: Wire HomeViewModel pipeline inputs**

In `HomeViewModel` constructor and catalog pipeline, inject:

```kotlin
private val tmdbDiscoveryService: TmdbDiscoveryService,
private val tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore,
```

Add a TMDB snapshot collector matching the Trakt/SIMKL/MDBList synthetic-source pattern:

```kotlin
viewModelScope.launch {
    tmdbCatalogSettingsDataStore.catalogPreferences.collectLatest {
        runCatching { tmdbDiscoveryService.refreshCatalogs(it, force = false) }
        runSerializedHomeRefreshIfNeeded("tmdb_discovery")
    }
}
```

Use `tmdbDiscoveryService.observeSnapshot()` if the implementation adds a snapshot Flow; otherwise keep a `MutableStateFlow<TmdbDiscoverySnapshot>` in `TmdbDiscoveryService` and expose it.

- [ ] **Step 7: Run home planning tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeViewModelTmdbCatalogPlanTest --tests com.nexio.tv.ui.screens.home.HomeCatalogStartupReadinessTest --tests com.nexio.tv.ui.screens.home.CatalogPlanTest
```

Expected: pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt app/src/main/java/com/nexio/tv/ui/screens/home/CatalogPlan.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTmdbCatalogPlanTest.kt
git commit -m "feat: add tmdb stock catalogs to home"
```

---

### Task 6: Add TMDB Catalog Controls To Settings

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModelTest.kt`

- [ ] **Step 1: Write settings view-model test**

Add test:

```kotlin
package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.local.TmdbCatalogIds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmdbSettingsViewModelTest {
    @Test
    fun `tmdb catalog settings expose default enabled and disabled catalogs`() = runTest {
        val harness = TmdbSettingsViewModelHarness()
        val state = harness.viewModel.uiState.value

        assertTrue(TmdbCatalogIds.TRENDING_MOVIES in state.enabledCatalogKeys)
        assertTrue(TmdbCatalogIds.LATEST_RELEASES_SERIES in state.enabledCatalogKeys)
        assertFalse(TmdbCatalogIds.POPULAR_MOVIES in state.enabledCatalogKeys)
        assertFalse(state.includeAdult)
        assertTrue(state.hideUnreleasedDigital)
    }
}
```

Instantiate the view model with fake `TmdbSettingsDataStore` and fake `TmdbCatalogSettingsDataStore` flows in this test file.

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.settings.TmdbSettingsViewModelTest
```

Expected: fail because UI state does not expose catalog preferences.

- [ ] **Step 3: Extend settings UI state and events**

In `TmdbSettingsViewModel.kt`, add fields:

```kotlin
val catalogOrder: List<String> = TmdbCatalogIds.BUILT_IN_ORDER,
val enabledCatalogKeys: Set<String> = TmdbCatalogIds.DEFAULT_ENABLED,
val includeAdult: Boolean = false,
val hideUnreleasedDigital: Boolean = true
```

Add events:

```kotlin
data class ToggleCatalog(val catalogId: String, val enabled: Boolean) : TmdbSettingsEvent()
data class MoveCatalogUp(val catalogId: String) : TmdbSettingsEvent()
data class MoveCatalogDown(val catalogId: String) : TmdbSettingsEvent()
data class ToggleAdultContent(val enabled: Boolean) : TmdbSettingsEvent()
data class ToggleDigitalReleaseFilter(val enabled: Boolean) : TmdbSettingsEvent()
```

Collect `tmdbCatalogSettingsDataStore.catalogPreferences` and map values into UI state.

- [ ] **Step 4: Implement event handlers**

Add:

```kotlin
is TmdbSettingsEvent.ToggleCatalog -> viewModelScope.launch {
    tmdbCatalogSettingsDataStore.setCatalogEnabled(event.catalogId, event.enabled)
}
is TmdbSettingsEvent.MoveCatalogUp -> viewModelScope.launch {
    tmdbCatalogSettingsDataStore.moveCatalog(event.catalogId, -1)
}
is TmdbSettingsEvent.MoveCatalogDown -> viewModelScope.launch {
    tmdbCatalogSettingsDataStore.moveCatalog(event.catalogId, 1)
}
is TmdbSettingsEvent.ToggleAdultContent -> viewModelScope.launch {
    tmdbCatalogSettingsDataStore.setIncludeAdult(event.enabled)
}
is TmdbSettingsEvent.ToggleDigitalReleaseFilter -> viewModelScope.launch {
    tmdbCatalogSettingsDataStore.setHideUnreleasedDigital(event.enabled)
}
```

- [ ] **Step 5: Add screen controls**

In `TmdbSettingsScreen.kt`, below existing provider toggles, add a Catalogs section:

```kotlin
item(key = "tmdb_catalogs_header") {
    SettingsSectionHeader(text = stringResource(R.string.tmdb_catalogs_title))
}
items(uiState.catalogOrder, key = { "tmdb_catalog_$it" }) { catalogId ->
    TmdbCatalogSettingsRow(
        title = tmdbCatalogDisplayName(catalogId),
        enabled = catalogId in uiState.enabledCatalogKeys,
        onToggle = {
            viewModel.onEvent(TmdbSettingsEvent.ToggleCatalog(catalogId, catalogId !in uiState.enabledCatalogKeys))
        },
        onMoveUp = { viewModel.onEvent(TmdbSettingsEvent.MoveCatalogUp(catalogId)) },
        onMoveDown = { viewModel.onEvent(TmdbSettingsEvent.MoveCatalogDown(catalogId)) }
    )
}
item(key = "tmdb_adult") {
    SettingsSwitchRow(
        title = stringResource(R.string.tmdb_adult_content_title),
        subtitle = stringResource(R.string.tmdb_adult_content_subtitle),
        checked = uiState.includeAdult,
        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleAdultContent(!uiState.includeAdult)) }
    )
}
item(key = "tmdb_digital_filter") {
    SettingsSwitchRow(
        title = stringResource(R.string.tmdb_digital_release_filter_title),
        subtitle = stringResource(R.string.tmdb_digital_release_filter_subtitle),
        checked = uiState.hideUnreleasedDigital,
        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleDigitalReleaseFilter(!uiState.hideUnreleasedDigital)) }
    )
}
```

Use the local row component style already present in Settings screens. Do not introduce a new card-inside-card layout.

- [ ] **Step 6: Add strings**

Add to `strings.xml`:

```xml
<string name="tmdb_catalogs_title">TMDB catalogs</string>
<string name="tmdb_adult_content_title">Enable adult content</string>
<string name="tmdb_adult_content_subtitle">Include adult titles in TMDB search and TMDB catalogs.</string>
<string name="tmdb_digital_release_filter_title">Digital releases only</string>
<string name="tmdb_digital_release_filter_subtitle">Hide unreleased movies from the latest releases catalog.</string>
```

- [ ] **Step 7: Run settings tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.settings.TmdbSettingsViewModelTest
```

Expected: pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt app/src/main/res/values/strings.xml app/src/test/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModelTest.kt
git commit -m "feat: add tmdb catalog settings"
```

---

### Task 7: Expose TMDB Catalogs To Android TV Feed Selection

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt`
- Test: `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogServiceTmdbTest.kt`

- [ ] **Step 1: Write feed-option test**

Create:

```kotlin
package com.nexio.tv.core.recommendations

import com.nexio.tv.data.local.TmdbCatalogIds
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidTvFeedCatalogServiceTmdbTest {
    @Test
    fun `feed options include enabled tmdb catalogs`() {
        val options = buildAndroidTvFeedOptionsForTest(
            tmdbEnabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES)
        )

        assertTrue(options.any { it.key == TmdbCatalogIds.TRENDING_MOVIES && it.sourceLabel == "TMDB" })
    }
}
```

Extract `buildFeedOptions` to an internal function that accepts TMDB prefs and snapshot, then test that function directly with a local fake input.

- [ ] **Step 2: Run failing test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvFeedCatalogServiceTmdbTest
```

Expected: fail because feed options do not include TMDB synthetic rows.

- [ ] **Step 3: Inject TMDB prefs and snapshot source**

Add constructor dependencies:

```kotlin
private val tmdbDiscoveryService: TmdbDiscoveryService,
private val tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore
```

Combine `tmdbDiscoveryService.observeSnapshot()` and `tmdbCatalogSettingsDataStore.catalogPreferences` in `observeFeedOptions`.

- [ ] **Step 4: Add TMDB synthetic feed rows**

Extend `buildSyntheticRows` with:

```kotlin
addAll(buildSyntheticTmdbRows(tmdbSnapshot, tmdbPrefs))
```

Implement:

```kotlin
private fun buildSyntheticTmdbRows(
    snapshot: TmdbDiscoverySnapshot,
    prefs: TmdbCatalogPreferences
): List<CatalogRow> {
    return prefs.catalogOrder
        .filter { it in prefs.enabledCatalogs }
        .mapNotNull { key -> snapshot.rowsByCatalog[key] }
        .filter { it.items.isNotEmpty() }
}
```

- [ ] **Step 5: Run feed tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.recommendations.AndroidTvFeedCatalogServiceTmdbTest --tests com.nexio.tv.core.recommendations.AndroidTvFeedCatalogServiceContinueWatchingTest
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogServiceTmdbTest.kt
git commit -m "feat: expose tmdb catalogs to android tv feeds"
```

---

### Task 8: Enforce Kitsu Auth Gate For Mapped Metadata

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/KitsuAuthService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt`

- [ ] **Step 1: Add unauthenticated mapping-path test**

Append to `KitsuMetadataServiceTest.kt`:

```kotlin
@Test
fun `mapped anime ids do not call Kitsu when provider is enabled but unauthenticated`() = runTest {
    val api = mockk<KitsuApi>(relaxed = true)
    val mapper = mockk<AnimeIdMappingService>(relaxed = true)
    val auth = mockk<KitsuAuthService>()
    val service = KitsuMetadataService(api, mapper, auth)

    coEvery { auth.providerEnabled() } returns true
    coEvery { auth.providerAuthenticated() } returns false

    assertNull(service.fetchEnrichment("tmdb:31911", ContentMediaKind.SERIES))
    coVerify(exactly = 0) { mapper.resolveKitsuId(any(), any()) }
    coVerify(exactly = 0) { api.getAnime(any(), any(), any()) }
}
```

- [ ] **Step 2: Run the failing Kitsu test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceTest
```

Expected: fail because `providerAuthenticated()` does not exist and current tests allow public unauthenticated Kitsu calls.

- [ ] **Step 3: Add auth-state helper**

In `KitsuAuthService.kt`, add:

```kotlin
suspend fun providerAuthenticated(): Boolean {
    val current = authStore.state.first()
    return current.enabled && current.isAuthenticated
}
```

- [ ] **Step 4: Gate mapped Kitsu metadata calls**

In `KitsuMetadataService`, replace:

```kotlin
if (!kitsuAuthService.providerEnabled()) return@withContext null
```

with:

```kotlin
if (!kitsuAuthService.providerAuthenticated()) return@withContext null
```

Do the same in `fetchEpisodeEnrichment`.

- [ ] **Step 5: Update existing Kitsu tests**

In tests that expect Kitsu metadata calls, add:

```kotlin
coEvery { auth.providerAuthenticated() } returns true
```

In the disabled-provider test, add:

```kotlin
coEvery { auth.providerAuthenticated() } returns false
```

- [ ] **Step 6: Run Kitsu and router tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceTest --tests com.nexio.tv.core.tvdb.TvMetadataRouterKitsuTest
```

Expected: pass. This preserves the intended provider order: Kitsu can win only when the mapping path is explicitly enabled and authenticated; otherwise series metadata continues through TVDB primary routing.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/KitsuAuthService.kt app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt
git commit -m "fix: require kitsu auth for mapped metadata"
```

---

### Task 9: Integration Verification

**Files:**
- No new files required.
- Verify changed files from Tasks 1-8.

- [ ] **Step 1: Run focused unit suite**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.TmdbCatalogSettingsDataStoreTest --tests com.nexio.tv.core.tmdb.TmdbApiContractTest --tests com.nexio.tv.data.repository.TmdbDiscoveryServiceTest --tests com.nexio.tv.ui.screens.search.SearchViewModelTmdbTest --tests com.nexio.tv.ui.screens.home.HomeViewModelTmdbCatalogPlanTest --tests com.nexio.tv.core.recommendations.AndroidTvFeedCatalogServiceTmdbTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceTest
```

Expected: all listed tests pass.

- [ ] **Step 2: Run related regression tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.search.SearchViewModelHistoryTest --tests com.nexio.tv.ui.screens.search.SearchKeyboardCompletionTest --tests com.nexio.tv.ui.screens.home.HomeCatalogStartupReadinessTest --tests com.nexio.tv.ui.screens.home.CatalogPlanTest --tests com.nexio.tv.core.tvdb.TvMetadataRouterKitsuTest
```

Expected: all listed tests pass.

- [ ] **Step 3: Run static build check**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: Kotlin compilation succeeds.

- [ ] **Step 4: Manual app checks**

Install a debug build and verify:

```bash
./gradlew :app:installDebug
```

Manual checks:

- Search for `Alien`; the first result row is TMDB Search, and existing Cinemeta/add-on rows still appear below when available.
- Open a TMDB Search movie result; detail opens without changing series metadata provider settings.
- Open a TMDB Search series result; TVDB remains the primary series enrichment provider when active.
- Disable TVDB and keep TMDB active; series detail falls back through existing TMDB fallback behavior.
- Enable Kitsu without logging in; mapped anime IDs do not use Kitsu metadata.
- Log into Kitsu; mapped anime IDs can use Kitsu metadata.
- Home shows TMDB Trending Movies, TMDB Trending Series, TMDB Latest Releases Movies, and TMDB Latest Releases Series by default.
- TMDB Popular, Year, and Language catalogs are available in TMDB settings but disabled by default.
- Toggling adult content changes only TMDB search/catalog calls.
- Toggling digital releases changes only TMDB Latest Releases Movies.

- [ ] **Step 5: Commit verification-only adjustments**

If verification required small fixes:

```bash
git add app/src/main/java app/src/test/java app/src/main/res/values/strings.xml
git commit -m "test: verify tmdb primary discovery"
```

When verification needs no code adjustment, do not create an empty commit.

---

## Self-Review

Spec coverage:

- TMDB primary in-app search: Task 4.
- Coexistence with Cinemeta/add-on search: Task 4 keeps existing add-on search rows unchanged and secondary.
- Built-in TMDB catalogs from the screenshot: Tasks 1, 3, 5, 6, and 7 cover Latest Releases, Trending, Popular, Year, and Language for movie and series.
- Default enabled catalogs: Task 1 tests and implements Trending Movie, Trending Series, Latest Releases Movie, Latest Releases Series as defaults.
- Other catalogs available but default disabled: Task 1 defines Popular, Year, and Language as built-ins outside the default enabled set; Task 6 exposes settings controls.
- Adult content: Tasks 1, 3, and 6.
- Provide IMDb metadata: Task 3 maps TMDB results to IMDb IDs where available before emitting `MetaPreview`.
- Digital release filter: Tasks 1, 3, and 6.
- Metadata providers unchanged: Tasks 3 and 8 preserve existing detail enrichment flow; series continues through TVDB/Kitsu routing rather than making TMDB the primary metadata provider.
- Kitsu unauthenticated mapping path: Task 8 isolates the auth gate fix.

Placeholder scan:

- No incomplete task bodies are present.
- All code-changing steps include concrete snippets.
- Every command includes expected result.

Type consistency:

- `TmdbCatalogIds`, `TmdbCatalogPreferences`, and `TmdbDiscoverySnapshot` are introduced before consumers use them.
- `TmdbDiscoveryService.search` returns `List<CatalogRow>`, matching `SearchViewModel` integration.
- `TmdbDiscoverySnapshot.rowsByCatalog` is used consistently by Home and Android TV feed planning.
