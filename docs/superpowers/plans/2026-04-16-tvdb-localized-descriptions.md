# TVDB Localized Descriptions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore localized TV descriptions using TVDB's own translation APIs while keeping TVDB as the source for all other TV metadata.

**Architecture:** Add TVDB language-code mapping for Nexio's supported app languages, then fetch TVDB translation data inside `TvdbMetadataService`. Series enrichment should overlay only `Translation.overview` onto `TvMetadataEnrichment.description`; episode enrichment should overlay only translated episode overviews while preserving TVDB titles, artwork, air dates, runtime, season-order fields, ratings, cast, companies, and networks. Visible UI metadata requests must pass the current app language into `TvMetadataRouter`.

**Tech Stack:** Android Kotlin, Retrofit, Moshi DTOs, coroutines, MockK, Android JVM tests via Gradle.

---

## Live TVDB API Validation

Validated against `https://api4.thetvdb.com/v4/` with the repo's local TVDB credential. Tokens and keys are not included in this plan.

TVDB language lookup:

| Nexio app language | TVDB code | TVDB language row |
|---|---:|---|
| `en` | `eng` | English |
| `es` | `spa` | Spanish |
| `fr` | `fra` | French |
| `de` | `deu` | German |
| `nl` | `nld` | Dutch |
| `zh-CN` | `zho` | Chinese - China |

Series translation endpoint validation using Game of Thrones, TVDB series id `121361`:

| App language | TVDB code | `/series/121361/translations/{code}` | Returned language | Overview length |
|---|---:|---:|---:|---:|
| `en` | `eng` | 200 | `eng` | 333 |
| `es` | `spa` | 200 | `spa` | 619 |
| `fr` | `fra` | 200 | `fra` | 692 |
| `de` | `deu` | 200 | `deu` | 694 |
| `nl` | `nld` | 200 | `nld` | 704 |
| `zh-CN` | `zho` | 200 | `zho` | 253 |

Episode translation endpoint validation using Game of Thrones S1E1, TVDB episode id `3254641`:

| App language | TVDB code | `/episodes/3254641/translations/{code}` | Returned language | Overview length |
|---|---:|---:|---:|---:|
| `en` | `eng` | 200 | `eng` | 114 |
| `es` | `spa` | 200 | `spa` | 821 |
| `fr` | `fra` | 200 | `fra` | 870 |
| `de` | `deu` | 200 | `deu` | 175 |
| `nl` | `nld` | 200 | `nld` | 846 |
| `zh-CN` | `zho` | 200 | `zho` | 180 |

Important result: TVDB does not accept Nexio/TMDB-style codes on these endpoints. `en`, `es`, `fr`, `de`, `nl`, and `zh-CN` all returned 404 for the series and episode translation endpoints. Use TVDB abbreviations.

Bulk season validation: `/series/121361/episodes/default/nld?page=0&season=1` returned 200, and the S1E1 record id `3254641` contained the Dutch overview. Use this bulk translated season endpoint for episode overviews to avoid one `/episodes/{id}/translations/{language}` call per episode.

## File Structure

- Modify `app/src/main/java/com/nexio/tv/core/locale/AppLocaleResolver.kt`
  - Add `resolveTvdbLanguageTag(context)` for current app language to TVDB code mapping.
- Create `app/src/main/java/com/nexio/tv/core/tvdb/TvdbLanguageMapper.kt`
  - Normalize direct app tags, TMDB tags, and TVDB abbreviations into TVDB abbreviations.
- Modify `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt`
  - Add `getSeriesTranslation`.
  - Add `getSeriesEpisodesTranslated`.
  - Add translation response DTOs.
- Modify `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`
  - Use TVDB language codes for cache keys and API calls.
  - Fetch series translation overview for non-English language requests.
  - Fetch translated season episodes in bulk for non-English episode requests.
- Modify visible TV metadata request call sites:
  - `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt`
- Modify tests:
  - `app/src/test/java/com/nexio/tv/core/locale/AppLocaleResolverProfileTest.kt`
  - `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTvdbTest.kt`

## Tasks

### Task 1: Add Failing Tests for TVDB Language Mapping and API Surface

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/locale/AppLocaleResolverProfileTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`

- [ ] **Step 1: Add app-locale to TVDB-code test**

Add this test to `AppLocaleResolverProfileTest`:

```kotlin
    @Test
    fun `tvdb language tag maps every supported app language to tvdb abbreviation`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)

        AppLocaleResolver.setStoredLocaleTag(context, "en")
        assertEquals("eng", AppLocaleResolver.resolveTvdbLanguageTag(context))

        AppLocaleResolver.setStoredLocaleTag(context, "es")
        assertEquals("spa", AppLocaleResolver.resolveTvdbLanguageTag(context))

        AppLocaleResolver.setStoredLocaleTag(context, "fr")
        assertEquals("fra", AppLocaleResolver.resolveTvdbLanguageTag(context))

        AppLocaleResolver.setStoredLocaleTag(context, "de")
        assertEquals("deu", AppLocaleResolver.resolveTvdbLanguageTag(context))

        AppLocaleResolver.setStoredLocaleTag(context, "nl")
        assertEquals("nld", AppLocaleResolver.resolveTvdbLanguageTag(context))

        AppLocaleResolver.setStoredLocaleTag(context, "zh-CN")
        assertEquals("zho", AppLocaleResolver.resolveTvdbLanguageTag(context))
    }
```

- [ ] **Step 2: Extend TVDB API reflection test**

In `TvdbMetadataServiceTest.kt`, change `tvdb api exposes extended series and season episodes endpoints` to:

```kotlin
    @Test
    fun `tvdb api exposes extended series episodes and translation endpoints`() {
        val extended = TvdbApi::class.java.methods.first { it.name == "getSeriesExtended" }
        val episodes = TvdbApi::class.java.methods.first { it.name == "getSeriesEpisodes" }
        val seriesTranslation = TvdbApi::class.java.methods.first { it.name == "getSeriesTranslation" }
        val translatedEpisodes = TvdbApi::class.java.methods.first { it.name == "getSeriesEpisodesTranslated" }

        assertEquals("series/{id}/extended", extended.getAnnotation(GET::class.java)?.value)
        assertEquals("series/{id}/episodes/{seasonType}", episodes.getAnnotation(GET::class.java)?.value)
        assertEquals("series/{id}/translations/{language}", seriesTranslation.getAnnotation(GET::class.java)?.value)
        assertEquals("series/{id}/episodes/{seasonType}/{language}", translatedEpisodes.getAnnotation(GET::class.java)?.value)
    }
```

- [ ] **Step 3: Add failing series translation service test**

Add imports:

```kotlin
import com.nexio.tv.data.remote.api.TvdbTranslationRecord
import com.nexio.tv.data.remote.api.TvdbTranslationResponse
```

Add this test near the existing series enrichment tests:

```kotlin
    @Test
    fun `series enrichment overlays tvdb translated overview for requested app language`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val service = tvdbService(tvdbApi)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)

        coEvery {
            tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, null, false)
        } returns Response.success(
            TvdbSeriesExtendedResponse(
                data = fullSeriesRecord().copy(
                    name = "Game of Thrones",
                    overview = "English TVDB overview"
                )
            )
        )
        coEvery {
            tvdbApi.getSeriesTranslation("Bearer tvdb-token", 121361, "nld")
        } returns Response.success(
            TvdbTranslationResponse(
                data = TvdbTranslationRecord(
                    language = "nld",
                    name = "Dutch title from translation endpoint",
                    overview = "Nederlandse TVDB beschrijving"
                )
            )
        )

        val enrichment = service.fetchSeriesEnrichment(identity, language = "nl")

        assertNotNull(enrichment)
        assertEquals("Game of Thrones", enrichment?.localizedTitle)
        assertEquals("Nederlandse TVDB beschrijving", enrichment?.description)
        coVerify(exactly = 1) { tvdbApi.getSeriesTranslation("Bearer tvdb-token", 121361, "nld") }
    }
```

- [ ] **Step 4: Add failing series fallback test**

Add this test after the translated series test:

```kotlin
    @Test
    fun `series enrichment keeps base overview when tvdb translation is missing`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val service = tvdbService(tvdbApi)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)

        coEvery {
            tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, null, false)
        } returns Response.success(
            TvdbSeriesExtendedResponse(
                data = fullSeriesRecord().copy(
                    name = "Game of Thrones",
                    overview = "English TVDB overview"
                )
            )
        )
        coEvery {
            tvdbApi.getSeriesTranslation("Bearer tvdb-token", 121361, "nld")
        } returns Response.error(
            404,
            "{}".toResponseBody("application/json".toMediaType())
        )

        val enrichment = service.fetchSeriesEnrichment(identity, language = "nl")

        assertNotNull(enrichment)
        assertEquals("Game of Thrones", enrichment?.localizedTitle)
        assertEquals("English TVDB overview", enrichment?.description)
    }
```

- [ ] **Step 5: Add failing translated season overview test**

Add this test near `fetch episode enrichment maps TVDB episodes by season and episode`:

```kotlin
    @Test
    fun `fetch episode enrichment overlays only translated episode overviews`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val service = tvdbService(tvdbApi)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)

        coEvery {
            tvdbApi.getSeriesEpisodes("Bearer tvdb-token", 121361, "default", 0, 1, null, null)
        } returns Response.success(
            TvdbSeriesEpisodesResponse(
                data = TvdbSeriesEpisodesData(
                    episodes = listOf(
                        episodeRecord().copy(
                            id = 3254641,
                            name = "Winter Is Coming",
                            overview = "English episode overview",
                            runtime = 62
                        )
                    )
                )
            )
        )
        coEvery {
            tvdbApi.getSeriesEpisodesTranslated("Bearer tvdb-token", 121361, "default", "nld", 0, 1, null, null)
        } returns Response.success(
            TvdbSeriesEpisodesResponse(
                data = TvdbSeriesEpisodesData(
                    episodes = listOf(
                        episodeRecord().copy(
                            id = 3254641,
                            name = "Dutch title from translation endpoint",
                            overview = "Nederlandse afleveringstekst",
                            runtime = 99
                        )
                    )
                )
            )
        )

        val episodes = service.fetchEpisodeEnrichment(identity, seasonNumbers = listOf(1), language = "nl")

        val episode = episodes[1 to 1]
        assertNotNull(episode)
        assertEquals("Winter Is Coming", episode?.title)
        assertEquals("Nederlandse afleveringstekst", episode?.overview)
        assertEquals(62, episode?.runtimeMinutes)
        coVerify(exactly = 1) {
            tvdbApi.getSeriesEpisodesTranslated("Bearer tvdb-token", 121361, "default", "nld", 0, 1, null, null)
        }
    }
```

- [ ] **Step 6: Run tests and verify they fail for missing implementation**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.locale.AppLocaleResolverProfileTest" --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"
```

Expected: FAIL because `resolveTvdbLanguageTag`, `getSeriesTranslation`, `getSeriesEpisodesTranslated`, and translation DTOs do not exist.

- [ ] **Step 7: Commit failing tests**

```bash
git add app/src/test/java/com/nexio/tv/core/locale/AppLocaleResolverProfileTest.kt app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt
git commit -m "test: cover tvdb localized metadata endpoints"
```

### Task 2: Implement TVDB Language Mapping and API Endpoints

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/locale/AppLocaleResolver.kt`
- Create: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbLanguageMapper.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt`

- [ ] **Step 1: Add TVDB language resolver to app locale resolver**

In `AppLocaleResolver.kt`, add this method after `resolveTmdbLanguageTag`:

```kotlin
    fun resolveTvdbLanguageTag(context: Context): String {
        return when (resolveEffectiveAppLanguageTag(context)) {
            "es" -> "spa"
            "fr" -> "fra"
            "de" -> "deu"
            "nl" -> "nld"
            "zh-CN" -> "zho"
            else -> "eng"
        }
    }
```

- [ ] **Step 2: Add TVDB language mapper**

Create `app/src/main/java/com/nexio/tv/core/tvdb/TvdbLanguageMapper.kt`:

```kotlin
package com.nexio.tv.core.tvdb

import java.util.Locale

object TvdbLanguageMapper {
    fun normalize(language: String?): String {
        val normalized = language
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace('_', '-')
            ?.lowercase(Locale.US)
            ?: return "eng"

        return when {
            normalized in setOf("eng", "en", "en-us", "en-gb") -> "eng"
            normalized in setOf("spa", "es", "es-es", "es-mx") -> "spa"
            normalized in setOf("fra", "fre", "fr", "fr-fr") -> "fra"
            normalized in setOf("deu", "ger", "de", "de-de") -> "deu"
            normalized in setOf("nld", "dut", "nl", "nl-nl") -> "nld"
            normalized in setOf("zho", "chi", "zh", "zh-cn", "zh-hans") -> "zho"
            normalized == "zhtw" || normalized == "zh-tw" || normalized == "zh-hant" -> "zhtw"
            else -> "eng"
        }
    }
}
```

- [ ] **Step 3: Add Retrofit endpoints**

In `TvdbApi.kt`, add these methods after `getSeriesEpisodes`:

```kotlin
    @GET("series/{id}/translations/{language}")
    suspend fun getSeriesTranslation(
        @Header("Authorization") authorization: String,
        @Path("id") id: Int,
        @Path("language") language: String
    ): Response<TvdbTranslationResponse>

    @GET("series/{id}/episodes/{seasonType}/{language}")
    suspend fun getSeriesEpisodesTranslated(
        @Header("Authorization") authorization: String,
        @Path("id") id: Int,
        @Path("seasonType") seasonType: String = "default",
        @Path("language") language: String,
        @Query("page") page: Int = 0,
        @Query("season") season: Int? = null,
        @Query("episodeNumber") episodeNumber: Int? = null,
        @Query("airDate") airDate: String? = null
    ): Response<TvdbSeriesEpisodesResponse>
```

- [ ] **Step 4: Add translation DTOs**

In `TvdbApi.kt`, add these DTOs near `TvdbTranslations`:

```kotlin
@JsonClass(generateAdapter = true)
data class TvdbTranslationResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: TvdbTranslationRecord? = null
)

@JsonClass(generateAdapter = true)
data class TvdbTranslationRecord(
    @Json(name = "aliases") val aliases: List<String>? = emptyList(),
    @Json(name = "isAlias") val isAlias: Boolean? = null,
    @Json(name = "isPrimary") val isPrimary: Boolean? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "tagline") val tagline: String? = null
)
```

- [ ] **Step 5: Run mapping/API tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.locale.AppLocaleResolverProfileTest" --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest.tvdb api exposes extended series episodes and translation endpoints"
```

Expected: PASS.

- [ ] **Step 6: Commit language/API implementation**

```bash
git add app/src/main/java/com/nexio/tv/core/locale/AppLocaleResolver.kt app/src/main/java/com/nexio/tv/core/tvdb/TvdbLanguageMapper.kt app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt
git commit -m "feat: add tvdb language and translation api support"
```

### Task 3: Implement TVDB Series and Episode Overview Localization

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`

- [ ] **Step 1: Import translation DTO**

Add:

```kotlin
import com.nexio.tv.data.remote.api.TvdbTranslationRecord
```

- [ ] **Step 2: Normalize service language to TVDB code**

Replace `normalizeLanguage` with:

```kotlin
    private fun normalizeLanguage(language: String?): String {
        return TvdbLanguageMapper.normalize(language)
    }
```

- [ ] **Step 3: Add translation text helper**

Add near `trimmed()`:

```kotlin
    private fun TvdbTranslationRecord?.overviewText(): String? {
        return this?.overview.trimmed()
    }
```

- [ ] **Step 4: Add series translation fetch helper**

Add inside `TvdbMetadataService` before `TvdbSeriesExtendedRecord.toEnrichment`:

```kotlin
    private suspend fun fetchSeriesTranslationOverview(
        authorization: String,
        seriesId: Int,
        language: String
    ): String? {
        if (language == "eng") return null
        return runCatching {
            tvdbApi.getSeriesTranslation(
                authorization = authorization,
                id = seriesId,
                language = language
            )
        }.onFailure { error ->
            Log.w(TAG, "TVDB series translation request failed reason=${error.javaClass.simpleName}")
        }.getOrNull()
            ?.takeIf { response -> response.isSuccessful }
            ?.body()
            ?.data
            .overviewText()
    }
```

- [ ] **Step 5: Apply series translation before caching**

In `fetchSeriesEnrichment`, replace:

```kotlin
        val enrichment = record.toEnrichment(identity.copy(tvdbId = resolvedId), activeProvider, seasonOrderContext, advancedMetadata) ?: return@withContext null
```

with:

```kotlin
        val baseEnrichment = record.toEnrichment(
            identity = identity.copy(tvdbId = resolvedId),
            activeProvider = activeProvider,
            seasonOrderContext = seasonOrderContext,
            advancedMetadata = advancedMetadata
        ) ?: return@withContext null
        val translatedOverview = fetchSeriesTranslationOverview(
            authorization = authorization,
            seriesId = resolvedId,
            language = normalizedLanguage
        )
        val enrichment = baseEnrichment.copy(
            description = translatedOverview ?: baseEnrichment.description
        )
```

- [ ] **Step 6: Add bulk translated episode helper**

Add before `TvdbEpisodeRecord.toEpisodeMetadata`:

```kotlin
    private suspend fun fetchTranslatedSeasonEpisodeOverviews(
        authorization: String,
        seriesId: Int,
        seasonNumber: Int,
        language: String
    ): Map<Int, String> {
        if (language == "eng") return emptyMap()
        return runCatching {
            tvdbApi.getSeriesEpisodesTranslated(
                authorization = authorization,
                id = seriesId,
                seasonType = DEFAULT_SEASON_TYPE,
                language = language,
                page = 0,
                season = seasonNumber
            )
        }.onFailure { error ->
            Log.w(TAG, "TVDB translated season episodes request failed reason=${error.javaClass.simpleName}")
        }.getOrNull()
            ?.takeIf { response -> response.isSuccessful }
            ?.body()
            ?.data
            ?.episodes
            .orEmpty()
            .mapNotNull { record ->
                val id = record.id ?: return@mapNotNull null
                val overview = record.overview.trimmed() ?: return@mapNotNull null
                id to overview
            }
            .toMap()
    }
```

- [ ] **Step 7: Overlay translated overviews in season fetch**

In `fetchSeasonEpisodes`, after `records` is assigned and before `mapped`, add:

```kotlin
        val translatedOverviewsById = fetchTranslatedSeasonEpisodeOverviews(
            authorization = authorization,
            seriesId = identity.tvdbId,
            seasonNumber = seasonNumber,
            language = normalizedLanguage
        )
```

Then replace:

```kotlin
            .map { record -> record.toEpisodeMetadata() }
```

with:

```kotlin
            .map { record ->
                record.toEpisodeMetadata(
                    translatedOverview = record.id?.let { translatedOverviewsById[it] }
                )
            }
```

- [ ] **Step 8: Update episode mapper signature**

Change `TvdbEpisodeRecord.toEpisodeMetadata()` to accept translated overview:

```kotlin
    private fun TvdbEpisodeRecord.toEpisodeMetadata(
        translatedOverview: String? = null
    ): TvEpisodeMetadata {
        val providerId = id?.let { "tvdb:$it" }
        return TvEpisodeMetadata(
            providerEpisodeId = providerId,
            seasonNumber = seasonNumber,
            episodeNumber = number,
            title = name.trimmed(),
            overview = translatedOverview ?: overview.trimmed(),
            thumbnail = image.trimmed(),
            airDate = aired.trimmed(),
            runtimeMinutes = runtime,
            absoluteNumber = absoluteNumber,
            airsAfterSeason = airsAfterSeason,
            airsBeforeSeason = airsBeforeSeason,
            airsBeforeEpisode = airsBeforeEpisode,
            linkedMovieTvdbId = linkedMovie,
            finaleType = finaleType.trimmed(),
            tvdbEpisodeOrder = buildTvdbEpisodeOrder()
        )
    }
```

Keep the existing `buildTvdbEpisodeOrder()` call exactly as it is in the current mapper.

- [ ] **Step 9: Run service tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"
```

Expected: PASS.

- [ ] **Step 10: Commit service implementation**

```bash
git add app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt
git commit -m "fix: use tvdb translations for localized overviews"
```

### Task 4: Pass Current App Language Into Visible TV Metadata Requests

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTvdbTest.kt`

- [ ] **Step 1: Add detail request language**

In `MetaDetailsViewModel.kt`, import:

```kotlin
import com.nexio.tv.core.locale.AppLocaleResolver
```

In `enrichMeta`, add before the first `tvMetadataRouter.fetchEnrichment`:

```kotlin
        val tvdbLanguage = AppLocaleResolver.resolveTvdbLanguageTag(context)
```

Then add `language = tvdbLanguage` to both TV metadata requests in `enrichMeta`:

```kotlin
                TvMetadataRequest(
                    contentId = meta.id,
                    fallbackContentId = itemId,
                    contentType = tmdbContentType,
                    language = tvdbLanguage
                )
```

```kotlin
                TvMetadataRequest(
                    contentId = meta.id,
                    fallbackContentId = itemId,
                    contentType = tmdbContentType,
                    language = tvdbLanguage,
                    seasonNumbers = seasonNumbers
                )
```

- [ ] **Step 2: Add home focused and hero request language**

In `HomeViewModelPresentationPipeline.kt`, import:

```kotlin
import com.nexio.tv.core.locale.AppLocaleResolver
```

In `fetchProviderEnrichmentForPreview`, add `language`:

```kotlin
            TvMetadataRequest(
                contentId = item.id,
                fallbackContentId = null,
                contentType = item.type,
                language = AppLocaleResolver.resolveTvdbLanguageTag(appContext)
            )
```

- [ ] **Step 3: Add catalog refresh request language**

In `HomeCatalogRefreshCoordinator.kt`, import:

```kotlin
import com.nexio.tv.core.locale.AppLocaleResolver
```

In `overlayProviderLocalizedMetadata`, add `language`:

```kotlin
                    TvMetadataRequest(
                        contentId = item.id,
                        fallbackContentId = null,
                        contentType = item.type,
                        language = AppLocaleResolver.resolveTvdbLanguageTag(appContext)
                    )
```

- [ ] **Step 4: Add continue-watching request language**

In `HomeViewModelContinueWatching.kt` and `HomeViewModelContinueWatchingRuntimePipeline.kt`, add the same import and pass:

```kotlin
language = AppLocaleResolver.resolveTvdbLanguageTag(appContext)
```

to each `TvMetadataRequest` constructed through `HomeViewModel`.

- [ ] **Step 5: Update UI boundary tests to assert language**

In existing TVDB UI tests, change `TvMetadataRequest` verification values from no language to the default expected language:

```kotlin
                TvMetadataRequest(
                    contentId = "tt0944947",
                    fallbackContentId = null,
                    contentType = ContentType.SERIES,
                    language = "eng"
                )
```

For detail view model tests using `mockk<Context>(relaxed = true)`, keep the default as `eng`. Add a specific test only if the existing test factory can easily inject stored locale preferences; otherwise the mapper and service tests already cover non-English routing.

- [ ] **Step 6: Run UI TVDB tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.detail.MetaDetailsTvdbProviderRoutingTest" --tests "com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest" --tests "com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTvdbTest"
```

Expected: PASS.

- [ ] **Step 7: Commit request-language propagation**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTvdbTest.kt
git commit -m "fix: request tvdb metadata in app language"
```

### Task 5: Regression Verification

**Files:**
- No code changes.

- [ ] **Step 1: Run focused TVDB and locale tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.locale.AppLocaleResolverProfileTest" --tests "com.nexio.tv.core.tvdb.*" --tests "com.nexio.tv.ui.screens.detail.MetaDetailsTvdb*" --tests "com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest" --tests "com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTvdbTest"
```

Expected: PASS.

- [ ] **Step 2: Run full app unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Manual smoke check with TVDB enabled**

In the app, switch the app language to Dutch, French, German, Spanish, and Chinese. For a TV series with known TVDB translations:

```text
Series description uses the selected TVDB translation.
Episode overview uses the selected TVDB translation.
Series title remains the existing TVDB/base title.
Episode title remains the existing TVDB/base title.
Genres, artwork, rating, runtime, release info, local release info, cast, production companies, networks, and season-order metadata remain TVDB values.
```

## Self-Review

- Spec coverage: The plan no longer routes TV descriptions through TMDB. It validates TVDB localization support for every Nexio-supported app language and uses TVDB translation paths for series and episode overviews.
- Completeness scan: The plan includes exact file paths, methods, snippets, test commands, and expected outcomes. No unresolved implementation language remains.
- Type consistency: New types are defined before use: `TvdbLanguageMapper`, `TvdbTranslationResponse`, and `TvdbTranslationRecord`. Existing type names match the current repo: `TvMetadataRequest`, `TvMetadataEnrichment`, `TvEpisodeMetadata`, `TvdbSeriesEpisodesResponse`, and `TvdbEpisodeRecord`.
