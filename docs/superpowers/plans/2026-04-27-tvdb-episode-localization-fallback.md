# TVDB Episode Localization Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop showing untranslated foreign-language episode titles/overviews (e.g. Hebrew on Tehran S3 episodes) by translating titles in addition to overviews and adding an English fallback when the user-locale TVDB translation is missing.

**Architecture:** All changes are scoped to `TvdbMetadataService` and its tests. The mapper from TVDB episode records to `TvEpisodeMetadata` is extended to accept an optional translated record (name + overview). The translation fetch path is restructured to (1) always run regardless of language (drop the `language == "eng"` short-circuits, since TVDB's "base" record is the canonical original-language record, not English), (2) fetch user-locale translations, and (3) fall back to English translations per-episode when the user-locale lookup 404s. The disk cache schema version is bumped to invalidate stale entries written under the old buggy mapper.

**Tech Stack:** Kotlin, Retrofit, MockK, JUnit, kotlinx.coroutines.test.

---

## File Structure

- **Modify** `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt` — replace overview-only translation helpers with full `TvdbTranslationRecord` helpers; update `toEpisodeMetadata` to consume translated name + overview with English fallback; drop `language == "eng"` early-returns.
- **Modify** `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt` — bump `TVDB_EPISODE_CACHE_SCHEMA_VERSION` to invalidate stale cache rows that captured Hebrew titles/overviews under user-locale keys.
- **Modify** `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt` — update existing tests that codified the buggy behavior; add new failing tests for translated titles, English fallback, and the dropped `eng` short-circuit.

No production callers (TvMetadataRouter, MetaDetailsViewModel) need to change — they consume `TvEpisodeMetadata` whose shape is unchanged.

---

## Task 1: Translated title + overview mapper accepts a TvdbTranslationRecord

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt:454-474`
- Test: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`

The current mapper takes only `translatedOverview: String?`. We change it to take a `TvdbTranslationRecord?` so the translated `name` is also picked up. Title resolution: translated name → base `name`. Overview resolution: translated overview → base `overview`.

- [ ] **Step 1: Write the failing test**

Add to `TvdbMetadataServiceTest.kt` (after the existing `fetch episode enrichment falls back to per episode translation overview` test at line 422):

```kotlin
@Test
fun `fetch episode enrichment uses translated title from per-episode endpoint`() = runTest {
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
                        name = "עולם רדיואקטיבי",
                        overview = "Hebrew base overview"
                    )
                )
            )
        )
    )
    coEvery {
        tvdbApi.getSeriesEpisodesTranslated("Bearer tvdb-token", 121361, "default", "nld", 0, 1, null, null)
    } returns Response.success(TvdbSeriesEpisodesResponse(data = TvdbSeriesEpisodesData(episodes = emptyList())))
    coEvery {
        tvdbApi.getEpisodeTranslation("Bearer tvdb-token", 3254641, "nld")
    } returns Response.success(
        TvdbTranslationResponse(
            data = TvdbTranslationRecord(
                name = "Tegenaanval",
                overview = "Tamar duikt onder",
                language = "nld"
            )
        )
    )

    val episodes = service.fetchEpisodeEnrichment(identity, seasonNumbers = listOf(1), language = "nl")

    val episode = episodes[1 to 1]
    assertNotNull(episode)
    assertEquals("Tegenaanval", episode?.title)
    assertEquals("Tamar duikt onder", episode?.overview)
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.tvdb.TvdbMetadataServiceTest.fetch episode enrichment uses translated title from per-episode endpoint'`

Expected: FAIL — assertion mismatch on title (`expected: "Tegenaanval", actual: "עולם רדיואקטיבי"`), because the current mapper hardcodes `title = name.trimmed()` from the base record at line 461.

- [ ] **Step 3: Change `toEpisodeMetadata` to consume a TvdbTranslationRecord**

In `TvdbMetadataService.kt`, replace lines 454-474 with:

```kotlin
private fun TvdbEpisodeRecord.toEpisodeMetadata(
    translation: TvdbTranslationRecord? = null,
    fallbackTranslation: TvdbTranslationRecord? = null
): TvEpisodeMetadata {
    val translatedTitle = translation?.name.trimmed()
        ?: fallbackTranslation?.name.trimmed()
    val translatedOverview = translation?.overview.trimmed()
        ?: fallbackTranslation?.overview.trimmed()
    val base = TvEpisodeMetadata(
        providerEpisodeId = id?.let { "tvdb:$it" },
        seasonNumber = seasonNumber,
        episodeNumber = number,
        title = translatedTitle ?: name.trimmed(),
        overview = translatedOverview ?: overview.trimmed(),
        thumbnail = image.trimmed(),
        airDate = aired.trimmed(),
        runtimeMinutes = runtime,
        absoluteNumber = absoluteNumber,
        airsAfterSeason = airsAfterSeason,
        airsBeforeSeason = airsBeforeSeason,
        airsBeforeEpisode = airsBeforeEpisode,
        linkedMovieTvdbId = linkedMovie,
        finaleType = finaleType.trimmed()
    )
    return base.copy(tvdbEpisodeOrder = seasonOrderMapper.mapEpisodeOrder(base))
}
```

The two-argument shape (`translation`, `fallbackTranslation`) is needed by Task 3.

- [ ] **Step 4: Replace `fetchPerEpisodeTranslationOverviews` with a record-returning helper**

In `TvdbMetadataService.kt`, replace the entire `fetchPerEpisodeTranslationOverviews` function (lines 426-452) with:

```kotlin
private suspend fun fetchPerEpisodeTranslationRecords(
    authorization: String,
    episodeIds: List<Int>,
    language: String
): Map<Int, TvdbTranslationRecord> {
    if (episodeIds.isEmpty()) return emptyMap()
    val translations = linkedMapOf<Int, TvdbTranslationRecord>()
    episodeIds.distinct().forEach { episodeId ->
        val record = runCatching {
            tvdbApi.getEpisodeTranslation(
                authorization = authorization,
                id = episodeId,
                language = language
            )
        }.onFailure { error ->
            Log.w(TAG, "TVDB episode translation request failed reason=${error.javaClass.simpleName}")
        }.getOrNull()
            ?.takeIf { response -> response.isSuccessful }
            ?.body()
            ?.data
        if (record != null) {
            translations[episodeId] = record
        }
    }
    return translations
}
```

This drops the `language == "eng" || episodeIds.isEmpty()` guard's eng short-circuit. The empty-list guard is preserved.

- [ ] **Step 5: Replace `fetchTranslatedSeasonEpisodeOverviews` with a record-returning helper**

In `TvdbMetadataService.kt`, replace the entire `fetchTranslatedSeasonEpisodeOverviews` function (lines 394-424) with:

```kotlin
private suspend fun fetchTranslatedSeasonEpisodeRecords(
    authorization: String,
    seriesId: Int,
    seasonNumber: Int,
    language: String
): Map<Int, TvdbTranslationRecord> {
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
            val name = record.name.trimmed()
            val overview = record.overview.trimmed()
            if (name == null && overview == null) return@mapNotNull null
            id to TvdbTranslationRecord(
                name = name,
                overview = overview,
                language = language
            )
        }
        .toMap()
}
```

This also drops the `if (language == "eng") return emptyMap()` guard. The bulk endpoint is known to be unreliable (TVDB ignores `?season=N` and returns Season 0 specials), but we keep the call since when it does return data it saves N round-trips.

- [ ] **Step 6: Update `fetchSeasonEpisodes` to wire the new helpers**

In `TvdbMetadataService.kt`, replace lines 274-296 (the section that builds `translatedOverviewsById`, `perEpisodeTranslatedOverviewsById`, `allTranslatedOverviewsById`, and `mapped`) with:

```kotlin
val records = response.body()?.data?.episodes.orEmpty()
val seasonTranslations = fetchTranslatedSeasonEpisodeRecords(
    authorization = authorization,
    seriesId = identity.tvdbId,
    seasonNumber = seasonNumber,
    language = normalizedLanguage
)
val perEpisodeTranslations = fetchPerEpisodeTranslationRecords(
    authorization = authorization,
    episodeIds = records.mapNotNull { record -> record.id }
        .filterNot { episodeId -> episodeId in seasonTranslations },
    language = normalizedLanguage
)
val translationsById = seasonTranslations + perEpisodeTranslations

val mapped = records
    .map { record ->
        record.toEpisodeMetadata(
            translation = record.id?.let { translationsById[it] }
        )
    }
    .filter { metadata -> metadata.seasonNumber == seasonNumber }
    .sortedWith(compareBy<TvEpisodeMetadata> { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.providerEpisodeId })
```

- [ ] **Step 7: Run the new test and verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.tvdb.TvdbMetadataServiceTest.fetch episode enrichment uses translated title from per-episode endpoint'`

Expected: PASS.

- [ ] **Step 8: Update the existing test that asserts the buggy English title**

In `TvdbMetadataServiceTest.kt`, the existing test `fetch episode enrichment overlays only translated episode overviews` (line 315) and `fetch episode enrichment falls back to per episode translation overview` (line 366) both assert `assertEquals("Winter Is Coming", episode?.title)`. With the fix, when the translation supplies a name, the translated name wins.

For `fetch episode enrichment overlays only translated episode overviews` (line 358): change the assertion from
```kotlin
assertEquals("Winter Is Coming", episode?.title)
```
to
```kotlin
assertEquals("Dutch title from translation endpoint", episode?.title)
```

For `fetch episode enrichment falls back to per episode translation overview` (line 417): change
```kotlin
assertEquals("Winter Is Coming", episode?.title)
```
to
```kotlin
assertEquals("Dutch title from episode translation endpoint", episode?.title)
```

Also rename both tests so their names reflect the new behavior:
- `fetch episode enrichment overlays only translated episode overviews` → `fetch episode enrichment uses translated title and overview from season endpoint`
- `fetch episode enrichment falls back to per episode translation overview` → `fetch episode enrichment falls back to per episode translation record`

- [ ] **Step 9: Run the full TvdbMetadataService test suite**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.tvdb.TvdbMetadataServiceTest'`

Expected: PASS — all tests, including the renamed ones.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt
git commit -m "fix(tvdb): apply translated episode titles, not just overviews"
```

---

## Task 2: Drop `language == "eng"` short-circuit in fetchSeriesTranslationOverview

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt:309-328`
- Test: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`

The same buggy assumption exists for series-level overview translation: `if (language == "eng") return null` skips the call. For shows whose canonical TVDB record is in another language, English-locale users see the original-language overview. Series-level enrichment isn't part of the original Tehran S3 bug report, but the fix is identical and one-liner — co-locating it keeps the change set coherent.

- [ ] **Step 1: Write the failing test**

Add to `TvdbMetadataServiceTest.kt` (after Task 1's new test):

```kotlin
@Test
fun `fetch series translation overview hits TVDB even for english locale`() = runTest {
    val tvdbApi = mockk<TvdbApi>(relaxed = true)
    val service = tvdbService(tvdbApi)
    val identity = TvdbSeriesIdentity(tvdbId = 121361)

    coEvery {
        tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, null, false)
    } returns Response.success(
        TvdbSeriesExtendedResponse(
            data = fullSeriesRecord().copy(overview = "Hebrew base overview")
        )
    )
    coEvery {
        tvdbApi.getSeriesTranslation("Bearer tvdb-token", 121361, "eng")
    } returns Response.success(
        TvdbTranslationResponse(
            data = TvdbTranslationRecord(name = "Game of Thrones", overview = "English overview", language = "eng")
        )
    )

    val enrichment = service.fetchSeriesEnrichment(identity, language = "en-US")

    assertEquals("English overview", enrichment?.description)
    coVerify(exactly = 1) { tvdbApi.getSeriesTranslation("Bearer tvdb-token", 121361, "eng") }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.tvdb.TvdbMetadataServiceTest.fetch series translation overview hits TVDB even for english locale'`

Expected: FAIL — `coVerify(exactly = 1)` fails (the API is never called) because `fetchSeriesTranslationOverview` returns null at line 314 without making the request.

- [ ] **Step 3: Remove the eng short-circuit**

In `TvdbMetadataService.kt`, change lines 309-328 from:

```kotlin
private suspend fun fetchSeriesTranslationOverview(
    authorization: String,
    seriesId: Int,
    language: String
): String? {
    if (language == "eng") return null
    return runCatching {
```

to:

```kotlin
private suspend fun fetchSeriesTranslationOverview(
    authorization: String,
    seriesId: Int,
    language: String
): String? {
    return runCatching {
```

(Just delete the `if (language == "eng") return null` line.)

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.tvdb.TvdbMetadataServiceTest.fetch series translation overview hits TVDB even for english locale'`

Expected: PASS.

- [ ] **Step 5: Run the full TvdbMetadataService test suite**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.tvdb.TvdbMetadataServiceTest'`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt
git commit -m "fix(tvdb): fetch english series translation when canonical record is non-english"
```

---

## Task 3: English fallback when user-locale translation is missing

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt` (`fetchSeasonEpisodes` body, around lines 274-307 after Task 1's edits)
- Test: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`

When the user's locale (e.g. `nld`) has no translation for an episode, we currently fall back to the base record (canonical, often original-language). This task adds a second-tier English-translation fetch for episodes whose user-locale lookup returned nothing. The mapper signature added in Task 1 (`fallbackTranslation`) is now wired in.

- [ ] **Step 1: Write the failing test**

Add to `TvdbMetadataServiceTest.kt` (after Task 2's test):

```kotlin
@Test
fun `fetch episode enrichment falls back to english translation when user locale missing`() = runTest {
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
                        name = "עולם רדיואקטיבי",
                        overview = "Hebrew base overview"
                    )
                )
            )
        )
    )
    coEvery {
        tvdbApi.getSeriesEpisodesTranslated("Bearer tvdb-token", 121361, "default", "nld", 0, 1, null, null)
    } returns Response.success(TvdbSeriesEpisodesResponse(data = TvdbSeriesEpisodesData(episodes = emptyList())))
    coEvery {
        tvdbApi.getEpisodeTranslation("Bearer tvdb-token", 3254641, "nld")
    } returns Response.error(404, "".toResponseBody("application/json".toMediaType()))
    coEvery {
        tvdbApi.getSeriesEpisodesTranslated("Bearer tvdb-token", 121361, "default", "eng", 0, 1, null, null)
    } returns Response.success(TvdbSeriesEpisodesResponse(data = TvdbSeriesEpisodesData(episodes = emptyList())))
    coEvery {
        tvdbApi.getEpisodeTranslation("Bearer tvdb-token", 3254641, "eng")
    } returns Response.success(
        TvdbTranslationResponse(
            data = TvdbTranslationRecord(
                name = "Fightback",
                overview = "Tamar escapes from the Mossad and the IRGC.",
                language = "eng"
            )
        )
    )

    val episodes = service.fetchEpisodeEnrichment(identity, seasonNumbers = listOf(1), language = "nl")

    val episode = episodes[1 to 1]
    assertNotNull(episode)
    assertEquals("Fightback", episode?.title)
    assertEquals("Tamar escapes from the Mossad and the IRGC.", episode?.overview)
}

@Test
fun `fetch episode enrichment skips english fallback when user locale already eng`() = runTest {
    val tvdbApi = mockk<TvdbApi>()
    val service = tvdbService(tvdbApi)
    val identity = TvdbSeriesIdentity(tvdbId = 121361)

    coEvery {
        tvdbApi.getSeriesEpisodes("Bearer tvdb-token", 121361, "default", 0, 1, null, null)
    } returns Response.success(
        TvdbSeriesEpisodesResponse(
            data = TvdbSeriesEpisodesData(
                episodes = listOf(episodeRecord().copy(id = 3254641, name = "עולם רדיואקטיבי", overview = "Hebrew"))
            )
        )
    )
    coEvery {
        tvdbApi.getSeriesEpisodesTranslated("Bearer tvdb-token", 121361, "default", "eng", 0, 1, null, null)
    } returns Response.success(TvdbSeriesEpisodesResponse(data = TvdbSeriesEpisodesData(episodes = emptyList())))
    coEvery {
        tvdbApi.getEpisodeTranslation("Bearer tvdb-token", 3254641, "eng")
    } returns Response.success(
        TvdbTranslationResponse(data = TvdbTranslationRecord(name = "Fightback", overview = "English", language = "eng"))
    )

    val episodes = service.fetchEpisodeEnrichment(identity, seasonNumbers = listOf(1), language = "en-US")

    assertEquals("Fightback", episodes[1 to 1]?.title)
    coVerify(exactly = 1) { tvdbApi.getEpisodeTranslation("Bearer tvdb-token", 3254641, "eng") }
    coVerify(exactly = 1) { tvdbApi.getSeriesEpisodesTranslated("Bearer tvdb-token", 121361, "default", "eng", 0, 1, null, null) }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.tvdb.TvdbMetadataServiceTest.fetch episode enrichment falls back to english translation when user locale missing'`

Expected: FAIL — title is `"עולם רדיואקטיבי"` (the Hebrew base value), because nothing in the current code path issues an `eng` request when `nld` returns nothing.

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.tvdb.TvdbMetadataServiceTest.fetch episode enrichment skips english fallback when user locale already eng'`

Expected: FAIL — `coVerify(exactly = 1)` for the `eng` season-translated endpoint will fail because the current code never calls it for eng (Task 1 introduced this call but only for the user's locale — when locale is already eng, we don't want a second redundant call). Verify both tests fail before continuing.

- [ ] **Step 3: Add an English fallback step in fetchSeasonEpisodes**

In `TvdbMetadataService.kt`, in `fetchSeasonEpisodes` after the block introduced in Task 1 Step 6 (which builds `translationsById`), insert the English fallback step before the `mapped` build:

```kotlin
val englishTranslationsById = if (normalizedLanguage == "eng") {
    emptyMap()
} else {
    val missingEpisodeIds = records.mapNotNull { record -> record.id }
        .filterNot { episodeId -> episodeId in translationsById }
    val englishSeason = fetchTranslatedSeasonEpisodeRecords(
        authorization = authorization,
        seriesId = identity.tvdbId,
        seasonNumber = seasonNumber,
        language = "eng"
    )
    val englishPerEpisode = fetchPerEpisodeTranslationRecords(
        authorization = authorization,
        episodeIds = missingEpisodeIds.filterNot { it in englishSeason },
        language = "eng"
    )
    englishSeason + englishPerEpisode
}

val mapped = records
    .map { record ->
        record.toEpisodeMetadata(
            translation = record.id?.let { translationsById[it] },
            fallbackTranslation = record.id?.let { englishTranslationsById[it] }
        )
    }
    .filter { metadata -> metadata.seasonNumber == seasonNumber }
    .sortedWith(compareBy<TvEpisodeMetadata> { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.providerEpisodeId })
```

(Replace the existing `mapped` build introduced in Task 1 Step 6 with this version that passes `fallbackTranslation`.)

- [ ] **Step 4: Run the new tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.tvdb.TvdbMetadataServiceTest.fetch episode enrichment falls back to english translation when user locale missing'`

Expected: PASS.

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.tvdb.TvdbMetadataServiceTest.fetch episode enrichment skips english fallback when user locale already eng'`

Expected: PASS.

- [ ] **Step 5: Run the full TvdbMetadataService test suite**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.tvdb.TvdbMetadataServiceTest'`

Expected: PASS — all tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt
git commit -m "fix(tvdb): fall back to english episode translation when user locale missing"
```

---

## Task 4: Bump TVDB episode cache schema version

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt:54`

Existing devices have cached `TvEpisodeMetadata` rows under language keys like `nld` that contain Hebrew titles/overviews captured by the buggy mapper. Bumping the schema version forces those cache entries to be ignored on read so users get the corrected values immediately rather than waiting up to 24h for TTL expiry.

- [ ] **Step 1: Bump the schema version**

In `MetadataDiskCacheStore.kt`, change line 54 from:

```kotlin
private const val TVDB_EPISODE_CACHE_SCHEMA_VERSION = 1
```

to:

```kotlin
private const val TVDB_EPISODE_CACHE_SCHEMA_VERSION = 2
```

- [ ] **Step 2: Run the metadata cache tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.local.MetadataDiskCacheStore*'`

Expected: PASS — the constant is consulted only via the existing read/write paths and the tests don't pin to the literal value `1`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt
git commit -m "chore(cache): bump TVDB episode cache schema to invalidate untranslated entries"
```

---

## Task 5: Verify the full TVDB and router test suites

**Files:** none — verification only.

Confirm we haven't broken adjacent tests (router decisions, graceful fallback paths, diagnostics, kitsu routing).

- [ ] **Step 1: Run all TVDB-area tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.core.tvdb.*'`

Expected: PASS — every test in the package, including `TvMetadataRouterTest`, `TvdbGracefulFallbackTest`, `TvdbDiagnosticsTest`, `TvMetadataRouterKitsuTest`, `TvdbProviderRoutingTest`, `TvdbMetadataServiceTest`.

- [ ] **Step 2: If any test fails, fix it inline**

If a test in `TvMetadataRouterTest` or another file mocks `tvdbMetadataService.fetchEpisodeEnrichment(...)` with a stubbed `TvEpisodeMetadata` whose `title` was previously assumed to be the base-record name, the assertion still holds (the router doesn't know about the mapper change — its tests pass titles in directly). But if anything broke, it likely indicates a real regression: investigate before silencing the test.

- [ ] **Step 3: Commit only if changes were needed**

Skip if no changes. If changes were made:

```bash
git add <changed test file>
git commit -m "test(tvdb): align with translated-title mapper"
```

---

## Self-Review Notes

- **Spec coverage** — three diagnosed bugs (`language == "eng"` short-circuit, untranslated titles, no English fallback) are each addressed by Tasks 1–3. The cache-staleness side-effect is handled by Task 4. The diagnosis also called out a TMDB final-fallback as "Robust" option; this plan does **not** include it because (a) once English fallback works, the user-visible problem is resolved on the reported case, and (b) TMDB-fallback at the router would require a foreign-script detector or a quality heuristic to know when to reach for it — that's its own design discussion. If the user wants it, it's a follow-up plan, not extra scope here.
- **Placeholders** — none. Every step has the actual code or command.
- **Type consistency** — `toEpisodeMetadata(translation, fallbackTranslation)` introduced in Task 1 is consumed in Task 3 with both parameters. Helper names `fetchTranslatedSeasonEpisodeRecords` and `fetchPerEpisodeTranslationRecords` are consistent across Tasks 1 and 3.
