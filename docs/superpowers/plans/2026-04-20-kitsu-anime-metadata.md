# Kitsu Anime Metadata Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add native Kitsu metadata enrichment for anime Stremio IDs while resolving non-Kitsu anime IDs through the local aiometadata-style mapping asset first, with optional Kitsu OAuth password-grant authentication.

**Architecture:** Treat `kitsu:{id}` as the only direct Kitsu anime ID. Resolve `mal:{id}`, `anilist:{id}`, `anidb:{id}`, `tmdb:{id}`, `tvdb:{id}`, `tt...`, and `imdb:{id}` to Kitsu IDs through the generated local asset at `app/src/main/assets/anime/anime-id-map.json`. Public metadata works unauthenticated, but Nexio can optionally authenticate against Kitsu's OAuth password grant, store access/refresh tokens as account secrets, refresh tokens before authenticated calls, and include `Authorization: Bearer <access_token>` when available.

**Tech Stack:** Kotlin, Retrofit, Moshi, OkHttp, Hilt, JUnit4, MockK, kotlinx-coroutines-test, Supabase SQL/RPC sync contract, Nexio web integration settings.

---

## Feasibility Decision

This is highly feasible for direct anime IDs and mapped anime IDs. Kitsu's anime API is JSON:API over `/anime/{id}` and `/anime/{id}/episodes`, and the local `kitsu.apib` confirms that Kitsu mappings support `externalSite` values for `anidb`, `anilist/anime`, and `myanimelist/anime`. aiometadata does not send those IDs directly to Kitsu; it resolves them through a mapping layer. Nexio should follow that model using the generated local asset rather than runtime downloads.

Kitsu authentication is feasible but must be handled as account auth, not a static API key. Kitsu supports OAuth 2 password grant at `https://kitsu.io/api/oauth/token`. Nexio should collect username/email/slug and password in the app or web portal, exchange them for access and refresh tokens, then store only tokens in the secret channel. The user's password should be transient and never persisted. Username can be stored in public settings for display, and token secrets should sync through Supabase.

First implementation supports:

- `kitsu:{id}` directly
- `mal:{id}` through mapping to `kitsu_id`
- `anilist:{id}` through mapping to `kitsu_id`
- `anidb:{id}` through mapping to `kitsu_id`
- `tmdb:{id}` through `byTmdbMovie` or `byTmdbSeries`, selected from requested content type
- `tvdb:{id}` through `byTvdb`
- `tt...` and `imdb:{id}` through `byImdb`

First implementation does not support:

- Kitsu-backed search/catalog rows
- account sync UI for choosing Kitsu as a preferred provider
- persisting Kitsu passwords after token exchange

Those can follow after the native metadata path is proven.

## File Structure

- Modify: `app/build.gradle.kts`
  - Generate `app/src/main/assets/anime/anime-id-map.json` from upstream mapping sources before main assets are copied.
- Create: `app/src/main/assets/anime/anime-id-map.json`
  - Build-time generated lookup asset with `recordsByKitsu`, `byMal`, `byAnilist`, `byAnidb`, `byTmdbMovie`, `byTmdbSeries`, `byTvdb`, and `byImdb`.
- Create: `app/src/main/java/com/nexio/tv/core/anime/AnimeStremioId.kt`
  - Parse supported anime lookup prefixes, including mapped TMDb/TVDB/IMDb IDs.
- Create: `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMapAsset.kt`
  - Define Moshi DTOs for the generated asset.
- Create: `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt`
  - Load the generated asset and resolve all supported IDs to Kitsu IDs.
- Create: `app/src/main/java/com/nexio/tv/data/remote/api/KitsuApi.kt`
  - Retrofit DTOs and endpoints for Kitsu anime details and episodes.
- Create: `app/src/main/java/com/nexio/tv/data/remote/api/KitsuAuthApi.kt`
  - Retrofit endpoint for `POST /api/oauth/token` password and refresh grants.
- Create: `app/src/main/java/com/nexio/tv/domain/model/KitsuSettings.kt`
  - Store public Kitsu auth state, username display value, enabled flag, token expiry summary, and NSFW/R18 preference.
- Create: `app/src/main/java/com/nexio/tv/data/local/KitsuAuthDataStore.kt`
  - Local profile/account auth state for Kitsu access/refresh tokens or secret refs.
- Create: `app/src/main/java/com/nexio/tv/data/repository/KitsuAuthService.kt`
  - Login with username/password, refresh tokens, disconnect, and execute authorized reads.
- Create: `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
  - Convert Kitsu details and episodes into Nexio `TvMetadataEnrichment` and `TvEpisodeMetadata`; use Kitsu auth when available.
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
  - Provide Kitsu JSON:API Retrofit API and Kitsu OAuth Retrofit API.
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
  - Include Kitsu public settings and Kitsu token secret refs in account sync.
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`
  - Verify Kitsu public fields and secret types stay in the sync contract.
- Modify: `supabase/account_settings_sync.sql`
  - Add `kitsu_access_token` and `kitsu_refresh_token` to allowed secret types and add default `integrations.kitsuAuth` payload.
- Create: `supabase/migrations/<timestamp>_add_kitsu_auth_sync.sql`
  - Incremental migration for existing Supabase deployments.
- Modify: Nexio settings UI files under `app/src/main/java/com/nexio/tv/ui/screens/settings/`
  - Add Kitsu login/disconnect controls.
- Modify: Nexio web integration settings under `nexio-web/` or current portal integration files
  - Add Kitsu username/password login and disconnect controls using the same Supabase secret contract.
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt`
  - Add `KITSU` provider and Kitsu decision reasons.
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt`
  - Try Kitsu first when the request content ID or fallback ID is an anime ID.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
  - Allow anime movie IDs to enter the provider router instead of bypassing directly to TMDb.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
  - Allow anime movie preview IDs to enter the provider router.
- Test: `app/src/test/java/com/nexio/tv/core/anime/AnimeStremioIdTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/AnimeIdMappingServiceTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterKitsuTest.kt`
- Update existing detail/home provider routing tests if constructor signatures change.

---

### Task 0: Generate Local Anime ID Map At Build Time

**Files:**
- Modify: `app/build.gradle.kts`
- Generate: `app/src/main/assets/anime/anime-id-map.json`

- [ ] **Step 1: Add Gradle generator task**

Add `generateAnimeIdMap` in `app/build.gradle.kts` that downloads:

```text
https://raw.githubusercontent.com/Fribb/anime-lists/refs/heads/master/anime-list-full.json
https://github.com/rensetsu/db.trakt.extended-anitrakt/releases/download/latest/movies_ex.json
https://raw.githubusercontent.com/TheBeastLT/stremio-kitsu-anime/bbf149474f610885629b95b1b9ce4408c3c1353d/static/data/imdb_mapping.json
```

The task writes compact JSON to:

```text
app/src/main/assets/anime/anime-id-map.json
```

Expected top-level keys:

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-04-19T22:46:12.228957Z",
  "sources": {},
  "counts": {},
  "recordsByKitsu": {},
  "byKitsu": {},
  "byMal": {},
  "byAnilist": {},
  "byAnidb": {},
  "byTvdb": {},
  "byTmdbMovie": {},
  "byTmdbSeries": {},
  "byImdb": {}
}
```

- [ ] **Step 2: Wire asset packaging to generator**

Make `syncFilteredMainAssets` depend on `generateAnimeIdMap` so `./gradlew :app:assembleRelease` refreshes/checks the asset before packaging.

- [ ] **Step 3: Avoid dirtying unchanged builds**

When upstream content is unchanged, keep the existing `generatedAt` and do not rewrite the asset. Compare generated content while ignoring `generatedAt`.

- [ ] **Step 4: Run task**

Run: `./gradlew :app:generateAnimeIdMap`

Expected: PASS and prints counts similar to:

```text
recordsByKitsu: 21926
byKitsu: 21926
byMal: 19577
byAnilist: 17181
byAnidb: 12960
byTvdb: 4190
byTmdbMovie: 1988
byTmdbSeries: 4442
byImdb: 5480
```

- [ ] **Step 5: Validate JSON**

Run: `python3 -m json.tool app/src/main/assets/anime/anime-id-map.json > /tmp/anime-id-map.validated.json`

Expected: PASS with no output.

- [ ] **Step 6: Run packaging path**

Run: `./gradlew :app:syncFilteredMainAssets`

Expected: PASS and output shows `:app:generateAnimeIdMap` ran before `:app:syncFilteredMainAssets`.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/assets/anime/anime-id-map.json
git commit -m "feat: generate bundled anime id map"
```

---

### Task 1: Parse Anime Lookup IDs

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/AnimeStremioId.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/AnimeStremioIdTest.kt`

- [ ] **Step 1: Write the failing parser tests**

```kotlin
package com.nexio.tv.core.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeStremioIdTest {
    @Test
    fun `parses supported anime lookup prefixes`() {
        assertEquals(AnimeIdSource.KITSU to "12", AnimeStremioId.parse("kitsu:12")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.MAL to "5114", AnimeStremioId.parse("mal:5114")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.ANILIST to "21", AnimeStremioId.parse("anilist:21")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.ANIDB to "69", AnimeStremioId.parse("anidb:69")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.TMDB to "31911", AnimeStremioId.parse("tmdb:31911")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.TVDB to "85249", AnimeStremioId.parse("tvdb:85249")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.IMDB to "tt1355642", AnimeStremioId.parse("tt1355642:1:1")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.IMDB to "tt1355642", AnimeStremioId.parse("imdb:tt1355642")?.let { it.source to it.value })
    }

    @Test
    fun `rejects unsupported prefixes`() {
        assertNull(AnimeStremioId.parse("trakt:123"))
        assertNull(AnimeStremioId.parse("simkl:123"))
        assertNull(AnimeStremioId.parse(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.AnimeStremioIdTest`

Expected: FAIL with unresolved reference `AnimeStremioId`.

- [ ] **Step 3: Implement the parser**

```kotlin
package com.nexio.tv.core.anime

enum class AnimeIdSource {
    KITSU,
    MAL,
    ANILIST,
    ANIDB,
    TMDB,
    TVDB,
    IMDB
}

data class AnimeStremioId(
    val source: AnimeIdSource,
    val value: String
) {
    companion object {
        fun parse(raw: String?): AnimeStremioId? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isBlank()) return null
            if (trimmed.startsWith("tt", ignoreCase = true)) {
                return AnimeStremioId(AnimeIdSource.IMDB, trimmed.substringBefore(':').substringBefore('/').trim())
            }
            val prefix = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
            val value = trimmed.substringAfter(':', missingDelimiterValue = "").trim()
            if (value.isBlank()) return null
            val source = when (prefix) {
                "kitsu" -> AnimeIdSource.KITSU
                "mal" -> AnimeIdSource.MAL
                "anilist" -> AnimeIdSource.ANILIST
                "anidb" -> AnimeIdSource.ANIDB
                "tmdb" -> AnimeIdSource.TMDB
                "tvdb" -> AnimeIdSource.TVDB
                "imdb" -> AnimeIdSource.IMDB
                else -> return null
            }
            return AnimeStremioId(source, value.substringBefore(':').substringBefore('/').trim())
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.AnimeStremioIdTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/AnimeStremioId.kt app/src/test/java/com/nexio/tv/core/anime/AnimeStremioIdTest.kt
git commit -m "feat: parse anime stremio ids"
```

---

### Task 2: Load And Query The Generated Anime ID Map

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMapAsset.kt`
- Create: `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/AnimeIdMappingServiceTest.kt`

- [ ] **Step 1: Write failing mapping service tests**

```kotlin
package com.nexio.tv.core.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeIdMappingServiceTest {
    @Test
    fun `resolves native and mapped ids to kitsu id`() {
        val service = AnimeIdMappingService(assetProvider = { fixtureAsset() })

        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.KITSU, "3936"), ContentMediaKind.SERIES))
        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.MAL, "5114"), ContentMediaKind.SERIES))
        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.ANILIST, "5114"), ContentMediaKind.SERIES))
        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.ANIDB, "6107"), ContentMediaKind.SERIES))
        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.TVDB, "85249"), ContentMediaKind.SERIES))
        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.IMDB, "tt1355642"), ContentMediaKind.SERIES))
    }

    @Test
    fun `tmdb lookup uses requested media kind`() {
        val service = AnimeIdMappingService(assetProvider = { fixtureAsset() })

        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.TMDB, "31911"), ContentMediaKind.SERIES))
        assertEquals("198", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.TMDB, "12174"), ContentMediaKind.MOVIE))
        assertNull(service.resolveKitsuId(AnimeStremioId(AnimeIdSource.TMDB, "31911"), ContentMediaKind.MOVIE))
    }

    @Test
    fun `returns null when id has no local mapping`() {
        val service = AnimeIdMappingService(assetProvider = { fixtureAsset() })

        assertNull(service.resolveKitsuId(AnimeStremioId(AnimeIdSource.MAL, "999999"), ContentMediaKind.SERIES))
    }

    private fun fixtureAsset(): AnimeIdMapAsset {
        return AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf(
                "3936" to AnimeIdMapRecord(kitsu = "3936", mal = "5114", anilist = "5114", anidb = "6107", tmdb = "31911", tvdb = "85249", imdb = "tt1355642", mediaType = "series"),
                "198" to AnimeIdMapRecord(kitsu = "198", mal = "222", tmdb = "12174", mediaType = "movie")
            ),
            byKitsu = mapOf("3936" to "3936", "198" to "198"),
            byMal = mapOf("5114" to "3936", "222" to "198"),
            byAnilist = mapOf("5114" to "3936"),
            byAnidb = mapOf("6107" to "3936"),
            byTvdb = mapOf("85249" to "3936"),
            byTmdbMovie = mapOf("12174" to "198"),
            byTmdbSeries = mapOf("31911" to "3936"),
            byImdb = mapOf("tt1355642" to "3936")
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.AnimeIdMappingServiceTest`

Expected: FAIL with unresolved references `AnimeIdMapAsset`, `AnimeIdMapRecord`, `ContentMediaKind`, and `AnimeIdMappingService`.

- [ ] **Step 3: Add asset DTOs**

```kotlin
package com.nexio.tv.core.anime

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AnimeIdMapAsset(
    @Json(name = "schemaVersion") val schemaVersion: Int,
    @Json(name = "recordsByKitsu") val recordsByKitsu: Map<String, AnimeIdMapRecord> = emptyMap(),
    @Json(name = "byKitsu") val byKitsu: Map<String, String> = emptyMap(),
    @Json(name = "byMal") val byMal: Map<String, String> = emptyMap(),
    @Json(name = "byAnilist") val byAnilist: Map<String, String> = emptyMap(),
    @Json(name = "byAnidb") val byAnidb: Map<String, String> = emptyMap(),
    @Json(name = "byTvdb") val byTvdb: Map<String, String> = emptyMap(),
    @Json(name = "byTmdbMovie") val byTmdbMovie: Map<String, String> = emptyMap(),
    @Json(name = "byTmdbSeries") val byTmdbSeries: Map<String, String> = emptyMap(),
    @Json(name = "byImdb") val byImdb: Map<String, String> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class AnimeIdMapRecord(
    @Json(name = "kitsu") val kitsu: String,
    @Json(name = "mal") val mal: String? = null,
    @Json(name = "anilist") val anilist: String? = null,
    @Json(name = "anidb") val anidb: String? = null,
    @Json(name = "tmdb") val tmdb: String? = null,
    @Json(name = "tvdb") val tvdb: String? = null,
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "mediaType") val mediaType: String? = null,
    @Json(name = "sourceType") val sourceType: String? = null
)

enum class ContentMediaKind {
    MOVIE,
    SERIES
}
```

- [ ] **Step 4: Add asset-backed mapping service**

```kotlin
package com.nexio.tv.core.anime

import android.content.Context
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val ANIME_ID_MAP_ASSET = "anime/anime-id-map.json"

@Singleton
class AnimeIdMappingService(
    private val assetProvider: () -> AnimeIdMapAsset
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        moshi: Moshi
    ) : this(assetProvider = {
        val adapter = moshi.adapter(AnimeIdMapAsset::class.java)
        context.assets.open(ANIME_ID_MAP_ASSET).bufferedReader().use { reader ->
            requireNotNull(adapter.fromJson(reader.readText())) { "Unable to parse anime ID map asset" }
        }
    })

    private val asset: AnimeIdMapAsset by lazy { assetProvider() }

    fun resolveKitsuId(id: AnimeStremioId, mediaKind: ContentMediaKind): String? {
        return when (id.source) {
            AnimeIdSource.KITSU -> id.value.takeIf { asset.byKitsu.containsKey(it) } ?: id.value
            AnimeIdSource.MAL -> asset.byMal[id.value]
            AnimeIdSource.ANILIST -> asset.byAnilist[id.value]
            AnimeIdSource.ANIDB -> asset.byAnidb[id.value]
            AnimeIdSource.TVDB -> asset.byTvdb[id.value]
            AnimeIdSource.IMDB -> asset.byImdb[id.value]
            AnimeIdSource.TMDB -> when (mediaKind) {
                ContentMediaKind.MOVIE -> asset.byTmdbMovie[id.value]
                ContentMediaKind.SERIES -> asset.byTmdbSeries[id.value]
            }
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.AnimeIdMappingServiceTest`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/AnimeIdMapAsset.kt app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt app/src/test/java/com/nexio/tv/core/anime/AnimeIdMappingServiceTest.kt
git commit -m "feat: load bundled anime id map"
```

---

### Task 3: Add Kitsu API And Metadata Service

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/remote/api/KitsuApi.kt`
- Create: `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt`

- [ ] **Step 1: Write failing service tests**

```kotlin
package com.nexio.tv.core.anime

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.data.remote.api.KitsuAnimeAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuCollectionResponse
import com.nexio.tv.data.remote.api.KitsuImage
import com.nexio.tv.data.remote.api.KitsuResourceResponse
import com.nexio.tv.data.remote.api.KitsuApi
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class KitsuMetadataServiceTest {
    @Test
    fun `fetchEnrichment maps kitsu details`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val service = KitsuMetadataService(api, mapper)

        coEvery { mapper.resolveKitsuId(AnimeStremioId(AnimeIdSource.MAL, "5114"), ContentMediaKind.SERIES) } returns "3936"
        coEvery { api.getAnime("3936", "categories,mediaRelationships.destination") } returns Response.success(
            KitsuResourceResponse(
                data = KitsuAnimeResource(
                    id = "3936",
                    attributes = KitsuAnimeAttributes(
                        canonicalTitle = "Fullmetal Alchemist: Brotherhood",
                        synopsis = "Two brothers search for a Philosopher's Stone.",
                        subtype = "TV",
                        startDate = "2009-04-05",
                        endDate = "2010-07-04",
                        episodeCount = 64,
                        episodeLength = 24,
                        averageRating = "88.12",
                        ageRating = "R",
                        posterImage = KitsuImage(original = "https://media.kitsu.io/poster.jpg"),
                        coverImage = KitsuImage(original = "https://media.kitsu.io/cover.jpg")
                    )
                )
            )
        )

        val enrichment = service.fetchEnrichment("mal:5114", ContentMediaKind.SERIES)

        assertEquals("Fullmetal Alchemist: Brotherhood", enrichment?.localizedTitle)
        assertEquals("Two brothers search for a Philosopher's Stone.", enrichment?.description)
        assertEquals("https://media.kitsu.io/poster.jpg", enrichment?.poster)
        assertEquals("https://media.kitsu.io/cover.jpg", enrichment?.backdrop)
        assertEquals("2009-04-05", enrichment?.releaseInfo)
        assertEquals(24, enrichment?.runtimeMinutes)
        assertEquals(8.812, enrichment?.rating ?: 0.0, 0.001)
        assertEquals("R", enrichment?.ageRating)
    }

    @Test
    fun `fetchEpisodeEnrichment maps kitsu episode numbers`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val service = KitsuMetadataService(api, mapper)

        coEvery { mapper.resolveKitsuId(AnimeStremioId(AnimeIdSource.KITSU, "1"), ContentMediaKind.SERIES) } returns "1"
        coEvery { api.getAnimeEpisodes("1", 20, 0) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuAnimeResource(
                        id = "episode-1",
                        attributes = KitsuAnimeAttributes(
                            canonicalTitle = "Asteroid Blues",
                            synopsis = "Spike and Jet chase a bounty.",
                            number = 1,
                            seasonNumber = 1,
                            airdate = "1998-04-03",
                            length = 24,
                            thumbnail = KitsuImage(original = "https://media.kitsu.io/e1.jpg")
                        )
                    )
                )
            )
        )

        val episodes: Map<Pair<Int, Int>, TvEpisodeMetadata> =
            service.fetchEpisodeEnrichment("kitsu:1", ContentMediaKind.SERIES, listOf(1))

        assertEquals("Asteroid Blues", episodes[1 to 1]?.title)
        assertEquals("1998-04-03", episodes[1 to 1]?.airDate)
        assertEquals("https://media.kitsu.io/e1.jpg", episodes[1 to 1]?.thumbnail)
    }

    @Test
    fun `returns null when id is not anime`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val service = KitsuMetadataService(api, mapper)

        assertNull(service.fetchEnrichment("trakt:123", ContentMediaKind.SERIES))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceTest`

Expected: FAIL with unresolved references `KitsuApi` and `KitsuMetadataService`.

- [ ] **Step 3: Add Kitsu API DTOs**

```kotlin
package com.nexio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface KitsuApi {
    @GET("anime/{id}")
    suspend fun getAnime(
        @Path("id") id: String,
        @Query("include") include: String = "categories,mediaRelationships.destination"
    ): Response<KitsuResourceResponse<KitsuAnimeResource>>

    @GET("anime/{id}/episodes")
    suspend fun getAnimeEpisodes(
        @Path("id") id: String,
        @Query("page[limit]") limit: Int = 20,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuAnimeResource>>
}

@JsonClass(generateAdapter = true)
data class KitsuResourceResponse<T>(
    @Json(name = "data") val data: T? = null,
    @Json(name = "included") val included: List<KitsuIncludedResource>? = null
)

@JsonClass(generateAdapter = true)
data class KitsuCollectionResponse<T>(
    @Json(name = "data") val data: List<T>? = emptyList(),
    @Json(name = "links") val links: KitsuLinks? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeResource(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: KitsuAnimeAttributes? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeAttributes(
    @Json(name = "canonicalTitle") val canonicalTitle: String? = null,
    @Json(name = "titles") val titles: Map<String, String?>? = null,
    @Json(name = "synopsis") val synopsis: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "subtype") val subtype: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "startDate") val startDate: String? = null,
    @Json(name = "endDate") val endDate: String? = null,
    @Json(name = "episodeCount") val episodeCount: Int? = null,
    @Json(name = "episodeLength") val episodeLength: Int? = null,
    @Json(name = "averageRating") val averageRating: String? = null,
    @Json(name = "ageRating") val ageRating: String? = null,
    @Json(name = "posterImage") val posterImage: KitsuImage? = null,
    @Json(name = "coverImage") val coverImage: KitsuImage? = null,
    @Json(name = "youtubeVideoId") val youtubeVideoId: String? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "seasonNumber") val seasonNumber: Int? = null,
    @Json(name = "airdate") val airdate: String? = null,
    @Json(name = "length") val length: Int? = null,
    @Json(name = "thumbnail") val thumbnail: KitsuImage? = null
)

@JsonClass(generateAdapter = true)
data class KitsuImage(
    @Json(name = "tiny") val tiny: String? = null,
    @Json(name = "small") val small: String? = null,
    @Json(name = "medium") val medium: String? = null,
    @Json(name = "large") val large: String? = null,
    @Json(name = "original") val original: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuIncludedResource(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class KitsuLinks(
    @Json(name = "next") val next: String? = null
)
```

- [ ] **Step 4: Add Kitsu metadata service**

```kotlin
package com.nexio.tv.core.anime

import android.util.Log
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.data.remote.api.KitsuApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "KitsuMetadata"

@Singleton
class KitsuMetadataService @Inject constructor(
    private val api: KitsuApi,
    private val idMappingService: AnimeIdMappingService
) {
    suspend fun fetchEnrichment(rawId: String, mediaKind: ContentMediaKind): TvMetadataEnrichment? = withContext(Dispatchers.IO) {
        val animeId = AnimeStremioId.parse(rawId) ?: return@withContext null
        val kitsuId = idMappingService.resolveKitsuId(animeId, mediaKind) ?: return@withContext null
        val response = runCatching { api.getAnime(kitsuId) }
            .onFailure { Log.w(TAG, "Kitsu anime fetch failed id=$rawId reason=${it.javaClass.simpleName}") }
            .getOrNull()
            ?: return@withContext null
        val resource = response.takeIf { it.isSuccessful }?.body()?.data ?: return@withContext null
        val attributes = resource.attributes ?: return@withContext null
        val rating = attributes.averageRating?.toDoubleOrNull()?.div(10.0)
        TvMetadataEnrichment(
            seriesTvdbId = null,
            localizedTitle = attributes.canonicalTitle,
            description = attributes.synopsis ?: attributes.description,
            backdrop = attributes.coverImage?.bestUrl(),
            poster = attributes.posterImage?.bestUrl(),
            releaseInfo = attributes.startDate,
            rating = rating,
            runtimeMinutes = attributes.episodeLength,
            ageRating = attributes.ageRating,
            language = "ja",
            status = attributes.status,
            remoteIds = mapOf("kitsu" to setOf(kitsuId))
        )
    }

    suspend fun fetchEpisodeEnrichment(
        rawId: String,
        mediaKind: ContentMediaKind,
        seasonNumbers: List<Int>
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> = withContext(Dispatchers.IO) {
        val animeId = AnimeStremioId.parse(rawId) ?: return@withContext emptyMap()
        val kitsuId = idMappingService.resolveKitsuId(animeId, mediaKind) ?: return@withContext emptyMap()
        val response = runCatching { api.getAnimeEpisodes(kitsuId) }
            .onFailure { Log.w(TAG, "Kitsu episode fetch failed id=$rawId reason=${it.javaClass.simpleName}") }
            .getOrNull()
            ?: return@withContext emptyMap()
        val acceptedSeasons = seasonNumbers.toSet().ifEmpty { setOf(1) }
        response.takeIf { it.isSuccessful }
            ?.body()
            ?.data
            .orEmpty()
            .mapNotNull { episode ->
                val attributes = episode.attributes ?: return@mapNotNull null
                val season = attributes.seasonNumber ?: 1
                val number = attributes.number ?: return@mapNotNull null
                if (season !in acceptedSeasons) return@mapNotNull null
                (season to number) to TvEpisodeMetadata(
                    providerEpisodeId = episode.id?.let { "kitsu:$it" },
                    seasonNumber = season,
                    episodeNumber = number,
                    title = attributes.canonicalTitle,
                    overview = attributes.synopsis ?: attributes.description,
                    thumbnail = attributes.thumbnail?.bestUrl(),
                    airDate = attributes.airdate,
                    runtimeMinutes = attributes.length
                )
            }
            .toMap()
    }
}

private fun com.nexio.tv.data.remote.api.KitsuImage.bestUrl(): String? =
    original ?: large ?: medium ?: small ?: tiny
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceTest`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/api/KitsuApi.kt app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt
git commit -m "feat: fetch kitsu anime metadata"
```

---

### Task 4: Add Kitsu OAuth Password-Grant Auth

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/remote/api/KitsuAuthApi.kt`
- Create: `app/src/main/java/com/nexio/tv/domain/model/KitsuSettings.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/KitsuAuthDataStore.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/KitsuAuthService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/KitsuAuthServiceTest.kt`

- [ ] **Step 1: Write failing auth service tests**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.data.local.KitsuAuthStore
import com.nexio.tv.data.remote.api.KitsuAuthApi
import com.nexio.tv.data.remote.api.KitsuTokenRequest
import com.nexio.tv.data.remote.api.KitsuTokenResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class KitsuAuthServiceTest {
    @Test
    fun `login exchanges username and password for tokens without persisting password`() = runTest {
        val api = mockk<KitsuAuthApi>()
        val store = FakeKitsuAuthStore()
        val service = KitsuAuthService(api = api, authStore = store, nowEpochSeconds = { 1000L })

        coEvery {
            api.token(
                KitsuTokenRequest(
                    grantType = "password",
                    username = "user@example.com",
                    password = "p@ss word",
                    refreshToken = null,
                    clientId = "",
                    clientSecret = ""
                )
            )
        } returns Response.success(
            KitsuTokenResponse(
                accessToken = "access",
                refreshToken = "refresh",
                expiresIn = 2_591_963,
                createdAt = 1000,
                tokenType = "bearer",
                scope = "public"
            )
        )

        val result = service.login(username = "user@example.com", password = "p@ss word")

        assertEquals(true, result)
        assertEquals("access", store.snapshot.accessToken)
        assertEquals("refresh", store.snapshot.refreshToken)
        assertEquals("user@example.com", store.snapshot.username)
        assertNull(store.snapshot.password)
    }

    @Test
    fun `refresh uses refresh token and updates access token`() = runTest {
        val api = mockk<KitsuAuthApi>()
        val store = FakeKitsuAuthStore(
            KitsuAuthSnapshot(
                username = "user",
                accessToken = "old-access",
                refreshToken = "old-refresh",
                expiresAtEpochSeconds = 999L
            )
        )
        val service = KitsuAuthService(api = api, authStore = store, nowEpochSeconds = { 1000L })

        coEvery {
            api.token(
                KitsuTokenRequest(
                    grantType = "refresh_token",
                    username = null,
                    password = null,
                    refreshToken = "old-refresh",
                    clientId = "",
                    clientSecret = ""
                )
            )
        } returns Response.success(
            KitsuTokenResponse(accessToken = "new-access", refreshToken = "new-refresh", expiresIn = 3600, createdAt = 1000)
        )

        assertEquals("new-access", service.validAccessToken())
        assertEquals("new-refresh", store.snapshot.refreshToken)
    }

    @Test
    fun `disconnect clears tokens`() = runTest {
        val store = FakeKitsuAuthStore(KitsuAuthSnapshot(username = "user", accessToken = "access", refreshToken = "refresh"))
        val service = KitsuAuthService(api = mockk(relaxed = true), authStore = store)

        service.disconnect()

        assertNull(store.snapshot.accessToken)
        assertNull(store.snapshot.refreshToken)
    }
}

private class FakeKitsuAuthStore(
    initial: KitsuAuthSnapshot = KitsuAuthSnapshot()
) : KitsuAuthStore {
    private val stateFlow = MutableStateFlow(initial)
    var snapshot: KitsuAuthSnapshot = initial
        private set

    override val state: Flow<KitsuAuthSnapshot> = stateFlow

    override suspend fun save(snapshot: KitsuAuthSnapshot) {
        this.snapshot = snapshot
        stateFlow.value = snapshot
    }

    override suspend fun clear() {
        save(KitsuAuthSnapshot(username = snapshot.username))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.KitsuAuthServiceTest`

Expected: FAIL with unresolved references `KitsuAuthApi`, `KitsuTokenRequest`, `KitsuAuthService`, `KitsuAuthSnapshot`, and `FakeKitsuAuthStore`.

- [ ] **Step 3: Add Kitsu OAuth API DTOs**

```kotlin
package com.nexio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface KitsuAuthApi {
    @POST("token")
    suspend fun token(@Body request: KitsuTokenRequest): Response<KitsuTokenResponse>
}

@JsonClass(generateAdapter = true)
data class KitsuTokenRequest(
    @Json(name = "grant_type") val grantType: String,
    @Json(name = "username") val username: String? = null,
    @Json(name = "password") val password: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "client_id") val clientId: String = "",
    @Json(name = "client_secret") val clientSecret: String = ""
)

@JsonClass(generateAdapter = true)
data class KitsuTokenResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "expires_in") val expiresIn: Long? = null,
    @Json(name = "created_at") val createdAt: Long? = null,
    @Json(name = "scope") val scope: String? = null,
    @Json(name = "token_type") val tokenType: String? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null
)
```

- [ ] **Step 4: Add auth state models**

```kotlin
package com.nexio.tv.domain.model

data class KitsuSettings(
    val enabled: Boolean = false,
    val username: String = "",
    val authenticated: Boolean = false,
    val accessTokenSecretRef: String? = null,
    val refreshTokenSecretRef: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val includeNsfw: Boolean = false
)
```

```kotlin
package com.nexio.tv.data.repository

data class KitsuAuthSnapshot(
    val username: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val password: String? = null
)
```

- [ ] **Step 5: Add local auth store interface and implementation**

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.data.repository.KitsuAuthSnapshot
import kotlinx.coroutines.flow.Flow

interface KitsuAuthStore {
    val state: Flow<KitsuAuthSnapshot>
    suspend fun save(snapshot: KitsuAuthSnapshot)
    suspend fun clear()
}
```

Use the existing DataStore patterns from `TraktAuthDataStore` and `SimklAuthDataStore` for the production implementation. Do not persist password. Persist username, access token, refresh token, and expiry only until Supabase secret sync support replaces raw local token storage.

- [ ] **Step 6: Add auth service**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.data.local.KitsuAuthStore
import com.nexio.tv.data.remote.api.KitsuAuthApi
import com.nexio.tv.data.remote.api.KitsuTokenRequest
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KitsuAuthService @Inject constructor(
    private val api: KitsuAuthApi,
    private val authStore: KitsuAuthStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    suspend fun login(username: String, password: String): Boolean {
        val response = api.token(
            KitsuTokenRequest(
                grantType = "password",
                username = username.trim(),
                password = password,
                clientId = "",
                clientSecret = ""
            )
        )
        val body = response.takeIf { it.isSuccessful }?.body() ?: return false
        val accessToken = body.accessToken ?: return false
        val refreshToken = body.refreshToken ?: return false
        authStore.save(
            KitsuAuthSnapshot(
                username = username.trim(),
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochSeconds = nowEpochSeconds() + (body.expiresIn ?: 0L),
                password = null
            )
        )
        return true
    }

    suspend fun validAccessToken(): String? {
        val current = authStore.state.first()
        val accessToken = current.accessToken
        val refreshToken = current.refreshToken
        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) return null
        val expiresAt = current.expiresAtEpochSeconds ?: 0L
        if (expiresAt > nowEpochSeconds() + 300L) return accessToken
        val response = api.token(
            KitsuTokenRequest(
                grantType = "refresh_token",
                refreshToken = refreshToken,
                clientId = "",
                clientSecret = ""
            )
        )
        val body = response.takeIf { it.isSuccessful }?.body() ?: return accessToken
        val newAccessToken = body.accessToken ?: return accessToken
        val newRefreshToken = body.refreshToken ?: refreshToken
        authStore.save(
            current.copy(
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                expiresAtEpochSeconds = nowEpochSeconds() + (body.expiresIn ?: 0L),
                password = null
            )
        )
        return newAccessToken
    }

    suspend fun disconnect() {
        authStore.clear()
    }
}
```

- [ ] **Step 7: Run auth tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.KitsuAuthServiceTest`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/api/KitsuAuthApi.kt app/src/main/java/com/nexio/tv/domain/model/KitsuSettings.kt app/src/main/java/com/nexio/tv/data/local/KitsuAuthDataStore.kt app/src/main/java/com/nexio/tv/data/repository/KitsuAuthService.kt app/src/test/java/com/nexio/tv/data/repository/KitsuAuthServiceTest.kt
git commit -m "feat: add kitsu oauth auth"
```

---

### Task 5: Add Kitsu Auth To Supabase Sync Contract

**Files:**
- Modify: `supabase/account_settings_sync.sql`
- Create: `supabase/migrations/<timestamp>_add_kitsu_auth_sync.sql`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`

- [ ] **Step 1: Write failing contract tests**

Add assertions to `AccountConfigSyncContractTest`:

```kotlin
@Test
fun `account sync contract includes kitsu auth settings and secrets`() {
    val contract = File("supabase/account_settings_sync.sql").readText()

    assertTrue(contract.contains("\"kitsuAuth\""))
    assertTrue(contract.contains("'kitsu_access_token'"))
    assertTrue(contract.contains("'kitsu_refresh_token'"))
    assertTrue(contract.contains("'integrations', jsonb_build_object"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest`

Expected: FAIL because Kitsu auth fields and secret types are absent.

- [ ] **Step 3: Add secret types to Supabase contract**

In every `account_secrets_secret_type_check` and RPC whitelist in `supabase/account_settings_sync.sql`, add:

```sql
'kitsu_access_token',
'kitsu_refresh_token'
```

Do not add `kitsu_password`; the password is only used to obtain OAuth tokens and must not persist.

- [ ] **Step 4: Add default public Kitsu auth settings**

Add `integrations.kitsuAuth` defaults:

```json
"kitsuAuth": {
  "enabled": false,
  "authenticated": false,
  "username": "",
  "accessTokenSecretRef": null,
  "refreshTokenSecretRef": null,
  "expiresAtEpochSeconds": null,
  "includeNsfw": false
}
```

Merge it into canonical extraction beside `simklAuth` and `traktAuth`.

- [ ] **Step 5: Add incremental migration**

Create `supabase/migrations/<timestamp>_add_kitsu_auth_sync.sql` that:

- drops and recreates `account_secrets_secret_type_check` with Kitsu token types
- recreates `sync_set_account_secret`, `sync_delete_account_secret`, and `sync_resolve_account_secret` allowlists with Kitsu token types
- updates default settings payload extraction to include `integrations.kitsuAuth`

- [ ] **Step 6: Wire app sync model**

Update `AccountSettingsSyncService` to include `KitsuSettings` under `integrations.kitsuAuth`, and map secret refs:

```kotlin
accessTokenSecretRef = "kitsu_access_token"
refreshTokenSecretRef = "kitsu_refresh_token"
```

Use the existing TVDB/Gemini/TMDB secret sync patterns. Store token payloads through `sync_set_account_secret`, not in public settings JSON.

- [ ] **Step 7: Run sync tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest --tests com.nexio.tv.core.sync.ProfileSettingsSyncServiceTest`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add supabase/account_settings_sync.sql supabase/migrations/*_add_kitsu_auth_sync.sql app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt
git commit -m "feat: sync kitsu auth settings"
```

---

### Task 6: Configure Kitsu Login In Nexio And Nexio Web

**Files:**
- Modify: app settings UI files under `app/src/main/java/com/nexio/tv/ui/screens/settings/`
- Modify: Nexio web integration settings under `nexio-web/` or the active portal integration source tree
- Test: app settings ViewModel tests for Kitsu login/disconnect
- Test: web integration tests for Kitsu secret sync, if the web test harness exists

- [ ] **Step 1: Add app UI tests**

Add tests for:

```text
Kitsu login form calls KitsuAuthService.login(username, password)
Kitsu password field is cleared after login success
Kitsu disconnect calls KitsuAuthService.disconnect()
Kitsu authenticated state displays username but never displays password
```

- [ ] **Step 2: Add app settings UI**

Add a Kitsu integration section with:

- enabled switch
- username/email/slug field
- password field used only for login submission
- login button
- disconnect button
- authenticated status
- NSFW/R18 preference switch, disabled unless authenticated

- [ ] **Step 3: Add web UI tests**

In Nexio web, add integration tests or component tests that verify:

```text
Kitsu username/password login writes token secrets through Supabase secret RPC
Kitsu public settings include username/authenticated/secret refs only
Kitsu password is not persisted in public settings
Disconnect deletes kitsu_access_token and kitsu_refresh_token secret refs
```

- [ ] **Step 4: Add web integration UI**

Add the same Kitsu controls to the web portal integration settings. Use the Supabase secret RPC contract instead of storing raw tokens in public settings.

- [ ] **Step 5: Run app and web tests**

Run app tests:

```bash
./gradlew :app:testDebugUnitTest --tests '*Kitsu*'
```

Run web tests using the existing web command in `nexio-web` or the active portal workspace.

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings nexio-web supabase app/src/test
git commit -m "feat: configure kitsu auth"
```

---

### Task 7: Wire Kitsu Auth Into Metadata Requests

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/KitsuApi.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt`

- [ ] **Step 1: Add failing authenticated request tests**

Add tests:

```kotlin
@Test
fun `passes bearer token to Kitsu when authenticated`() = runTest {
    val api = mockk<KitsuApi>()
    val mapper = mockk<AnimeIdMappingService>()
    val auth = mockk<KitsuAuthService>()
    val service = KitsuMetadataService(api, mapper, auth)

    coEvery { mapper.resolveKitsuId(any(), any()) } returns "3936"
    coEvery { auth.validAccessToken() } returns "access-token"
    coEvery { api.getAnime("Bearer access-token", "3936", any()) } returns Response.success(KitsuResourceResponse(data = KitsuAnimeResource(id = "3936", attributes = KitsuAnimeAttributes(canonicalTitle = "Title"))))

    service.fetchEnrichment("mal:5114", ContentMediaKind.SERIES)

    coVerify(exactly = 1) { api.getAnime("Bearer access-token", "3936", any()) }
}

@Test
fun `omits bearer token when unauthenticated`() = runTest {
    val auth = mockk<KitsuAuthService>()
    coEvery { auth.validAccessToken() } returns null
    // verify api call receives null Authorization header
}
```

- [ ] **Step 2: Update Kitsu API header parameters**

```kotlin
@GET("anime/{id}")
suspend fun getAnime(
    @Header("Authorization") authorization: String? = null,
    @Path("id") id: String,
    @Query("include") include: String = "categories,mediaRelationships.destination"
): Response<KitsuResourceResponse<KitsuAnimeResource>>
```

Repeat for episodes.

- [ ] **Step 3: Inject auth service into metadata service**

Call:

```kotlin
val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
```

Pass `authorization` to Kitsu API calls.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/api/KitsuApi.kt app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt
git commit -m "feat: use kitsu auth for metadata"
```

---

### Task 8: Wire Kitsu Retrofit Providers

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/KitsuNetworkModuleTest.kt`

- [ ] **Step 1: Write failing provider annotation test**

```kotlin
package com.nexio.tv.core.anime

import com.nexio.tv.data.remote.api.KitsuApi
import com.nexio.tv.data.remote.api.KitsuAuthApi
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.http.GET

class KitsuNetworkModuleTest {
    @Test
    fun `kitsu api exposes expected endpoints`() {
        val anime = KitsuApi::class.java.methods.first { it.name == "getAnime" }
        val episodes = KitsuApi::class.java.methods.first { it.name == "getAnimeEpisodes" }

        assertEquals("anime/{id}", anime.getAnnotation(GET::class.java)?.value)
        assertEquals("anime/{id}/episodes", episodes.getAnnotation(GET::class.java)?.value)
    }
}
```

- [ ] **Step 2: Run test to verify it passes after Task 3**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.KitsuNetworkModuleTest`

Expected: PASS. This verifies endpoint strings before Hilt wiring changes.

- [ ] **Step 3: Add NetworkModule providers**

Add imports:

```kotlin
import com.nexio.tv.data.remote.api.KitsuApi
import com.nexio.tv.data.remote.api.KitsuAuthApi
```

Add providers near the TMDb/TVDB providers:

```kotlin
@Provides
@Singleton
@Named("kitsu")
fun provideKitsuRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
    Retrofit.Builder()
        .baseUrl("https://kitsu.io/api/edge/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

@Provides
@Singleton
@Named("kitsuOauth")
fun provideKitsuOauthRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
    Retrofit.Builder()
        .baseUrl("https://kitsu.io/api/oauth/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

@Provides
@Singleton
fun provideKitsuApi(@Named("kitsu") retrofit: Retrofit): KitsuApi =
    retrofit.create(KitsuApi::class.java)

@Provides
@Singleton
fun provideKitsuAuthApi(@Named("kitsuOauth") retrofit: Retrofit): KitsuAuthApi =
    retrofit.create(KitsuAuthApi::class.java)
```

- [ ] **Step 4: Run compile check**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt app/src/test/java/com/nexio/tv/core/anime/KitsuNetworkModuleTest.kt
git commit -m "feat: wire kitsu network clients"
```

---

### Task 9: Route Anime IDs Through Kitsu Before TVDB/TMDb

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt`
- Test: `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterKitsuTest.kt`

- [ ] **Step 1: Write failing router tests**

```kotlin
package com.nexio.tv.core.tvdb

import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.TvdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TvMetadataRouterKitsuTest {
    @Test
    fun `anime id uses kitsu before tvdb and tmdb`() = runTest {
        val kitsu = mockk<KitsuMetadataService>()
        val tvdbIdentity = mockk<TvdbIdentityService>(relaxed = true)
        val tvdbMetadata = mockk<TvdbMetadataService>(relaxed = true)
        val tmdb = mockk<TmdbService>(relaxed = true)
        val tmdbMetadata = mockk<TmdbMetadataService>(relaxed = true)
        val router = router(kitsu, tvdbIdentity, tvdbMetadata, tmdb, tmdbMetadata)

        coEvery { kitsu.fetchEnrichment("mal:5114", ContentMediaKind.SERIES) } returns TvMetadataEnrichment(
            seriesTvdbId = null,
            localizedTitle = "Fullmetal Alchemist: Brotherhood"
        )

        val decision = router.fetchEnrichment(
            TvMetadataRequest(
                contentId = "mal:5114",
                contentType = ContentType.SERIES,
                language = "en-US"
            )
        )

        assertEquals(TvProvider.KITSU, decision.provider)
        assertEquals(TvMetadataDecisionReason.KITSU_SUCCESS, decision.reason)
        assertEquals("Fullmetal Alchemist: Brotherhood", decision.value?.localizedTitle)
        coVerify(exactly = 0) { tvdbIdentity.resolveSeriesByRemoteId(any(), any()) }
        coVerify(exactly = 0) { tmdb.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadata.fetchEnrichment(any(), any(), any()) }
    }

    @Test
    fun `mapped tmdb tvdb and imdb anime ids use kitsu`() = runTest {
        val kitsu = mockk<KitsuMetadataService>()
        val router = router(kitsu)
        coEvery { kitsu.fetchEnrichment("tmdb:31911", ContentMediaKind.SERIES) } returns TvMetadataEnrichment(localizedTitle = "FMA:B", seriesTvdbId = null)
        coEvery { kitsu.fetchEnrichment("tvdb:85249", ContentMediaKind.SERIES) } returns TvMetadataEnrichment(localizedTitle = "FMA:B", seriesTvdbId = null)
        coEvery { kitsu.fetchEnrichment("tt1355642", ContentMediaKind.SERIES) } returns TvMetadataEnrichment(localizedTitle = "FMA:B", seriesTvdbId = null)

        assertEquals(TvProvider.KITSU, router.fetchEnrichment(TvMetadataRequest("tmdb:31911", contentType = ContentType.SERIES)).provider)
        assertEquals(TvProvider.KITSU, router.fetchEnrichment(TvMetadataRequest("tvdb:85249", contentType = ContentType.SERIES)).provider)
        assertEquals(TvProvider.KITSU, router.fetchEnrichment(TvMetadataRequest("tt1355642", contentType = ContentType.SERIES)).provider)
    }

    @Test
    fun `non anime id keeps tvdb route`() = runTest {
        val kitsu = mockk<KitsuMetadataService>(relaxed = true)
        val tvdbIdentity = mockk<TvdbIdentityService>()
        val tvdbMetadata = mockk<TvdbMetadataService>()
        val identity = TvdbSeriesIdentity(tvdbId = 121361)
        val router = router(kitsu, tvdbIdentity, tvdbMetadata)

        coEvery { tvdbIdentity.resolveSeriesByRemoteId("tt0944947", TvdbRemoteIdSource.IMDB) } returns identity
        coEvery { tvdbMetadata.fetchSeriesEnrichment(identity, "en-US") } returns TvMetadataEnrichment(
            seriesTvdbId = 121361,
            localizedTitle = "Game of Thrones"
        )

        val decision = router.fetchEnrichment(
            TvMetadataRequest("tt0944947", contentType = ContentType.SERIES, language = "en-US")
        )

        assertEquals(TvProvider.TVDB, decision.provider)
        coVerify(exactly = 0) { kitsu.fetchEnrichment(any()) }
    }

    private fun router(
        kitsuMetadataService: KitsuMetadataService,
        tvdbIdentityService: TvdbIdentityService = mockk(relaxed = true),
        tvdbMetadataService: TvdbMetadataService = mockk(relaxed = true),
        tmdbService: TmdbService = mockk(relaxed = true),
        tmdbMetadataService: TmdbMetadataService = mockk(relaxed = true)
    ): TvMetadataRouter {
        val tvdbSettings = mockk<TvdbSettingsDataStore>()
        val tmdbSettings = mockk<TmdbSettingsDataStore>()
        every { tvdbSettings.settings } returns flowOf(TvdbSettings())
        every { tmdbSettings.settings } returns flowOf(TmdbSettings())
        return TvMetadataRouter(
            tvdbSettingsDataStore = tvdbSettings,
            tmdbSettingsDataStore = tmdbSettings,
            tvdbIdentityService = tvdbIdentityService,
            tvdbMetadataService = tvdbMetadataService,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            credentialHealth = mockk(relaxed = true) { coEvery { canCallTvdb() } returns true },
            diagnosticsRecorder = mockk(relaxUnitFun = true),
            kitsuMetadataService = kitsuMetadataService
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tvdb.TvMetadataRouterKitsuTest`

Expected: FAIL because `TvProvider.KITSU`, `KITSU_SUCCESS`, and the router constructor parameter do not exist.

- [ ] **Step 3: Add Kitsu diagnostics**

```kotlin
enum class TvProvider {
    TVDB,
    TMDB,
    KITSU
}

enum class TvMetadataDecisionReason(val eventName: String) {
    TVDB_INACTIVE("tvdb_inactive_tmdb_fallback"),
    TVDB_SUCCESS("tvdb_success"),
    TVDB_FALLBACK_TMDB("tvdb_fallback_tmdb"),
    TVDB_IDENTITY_MISSING("tvdb_identity_missing"),
    TVDB_RECORD_MISSING("tvdb_record_missing"),
    KITSU_SUCCESS("kitsu_success"),
    KITSU_IDENTITY_MISSING("kitsu_identity_missing"),
    KITSU_RECORD_MISSING("kitsu_record_missing"),
    TMDB_TV_SKIPPED("tmdb_tv_skipped"),
    POSTER_RATINGS_OVERRIDE("poster_ratings_override"),
    TVDB_SEASON_TYPE_PRESENT("tvdb_season_type_present"),
    TVDB_CANONICAL_TRAKT_NUMBERING_USED("tvdb_canonical_trakt_numbering_used"),
    TVDB_ALTERNATE_ORDER_PRESERVED("tvdb_alternate_order_preserved"),
    TVDB_ADVANCED_SURFACE_SUCCESS("tvdb_advanced_surface_success"),
    TVDB_ADVANCED_SURFACE_MISSING("tvdb_advanced_surface_missing"),
    TVDB_TRAILER_SUCCESS("tvdb_trailer_success"),
    TVDB_TRAILER_MISSING("tvdb_trailer_missing"),
    TVDB_TRAILER_UNUSABLE_URL("tvdb_trailer_unusable_url"),
    TMDB_TRAILER_FALLBACK("tmdb_trailer_fallback")
}
```

- [ ] **Step 4: Inject Kitsu into router and short-circuit anime IDs**

Add imports:

```kotlin
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
```

Add constructor parameter:

```kotlin
private val kitsuMetadataService: KitsuMetadataService
```

At the start of `fetchEnrichment`, before the `!request.contentType.isTv()` branch, add:

```kotlin
tryFetchKitsuEnrichment(request)?.let { return it }
```

At the start of `fetchEpisodeEnrichment`, before the `!request.contentType.isTv()` branch, add:

```kotlin
tryFetchKitsuEpisodeEnrichment(request)?.let { return it }
```

Add helpers:

```kotlin
private suspend fun tryFetchKitsuEnrichment(
    request: TvMetadataRequest
): TvMetadataDecision<TvMetadataEnrichment>? {
    val animeId = firstAnimeId(request) ?: return null
    val mediaKind = request.contentType.toAnimeMediaKind()
    val enrichment = kitsuMetadataService.fetchEnrichment(animeId, mediaKind) ?: return null
    return TvMetadataDecision(
        provider = TvProvider.KITSU,
        reason = TvMetadataDecisionReason.KITSU_SUCCESS,
        value = enrichment,
        diagnostics = listOf(diagnostic(TvMetadataDecisionReason.KITSU_SUCCESS, animeId, provider = TvProvider.KITSU))
    )
}

private suspend fun tryFetchKitsuEpisodeEnrichment(
    request: TvMetadataRequest
): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>>? {
    val animeId = firstAnimeId(request) ?: return null
    val mediaKind = request.contentType.toAnimeMediaKind()
    val episodes = kitsuMetadataService.fetchEpisodeEnrichment(animeId, mediaKind, request.seasonNumbers)
    if (episodes.isEmpty()) return null
    return TvMetadataDecision(
        provider = TvProvider.KITSU,
        reason = TvMetadataDecisionReason.KITSU_SUCCESS,
        value = episodes,
        diagnostics = listOf(
            diagnostic(
                TvMetadataDecisionReason.KITSU_SUCCESS,
                animeId,
                provider = TvProvider.KITSU
            )
        )
    )
}

private fun firstAnimeId(request: TvMetadataRequest): String? {
    val candidates = listOf(request.contentId, request.fallbackContentId)
    return candidates.firstOrNull { AnimeStremioId.parse(it) != null }
}

private fun ContentType.toAnimeMediaKind(): ContentMediaKind =
    if (this == ContentType.MOVIE) ContentMediaKind.MOVIE else ContentMediaKind.SERIES
```

- [ ] **Step 5: Run router test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tvdb.TvMetadataRouterKitsuTest`

Expected: PASS.

- [ ] **Step 6: Run existing TVDB router tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tvdb.TvMetadataRouterTest --tests com.nexio.tv.core.tvdb.TvdbProviderRoutingTest`

Expected: PASS after updating any test helper constructor calls to pass `kitsuMetadataService = mockk(relaxed = true)`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterKitsuTest.kt app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt app/src/test/java/com/nexio/tv/core/tvdb/TvdbProviderRoutingTest.kt
git commit -m "feat: route anime ids through kitsu metadata"
```

---

### Task 10: Let Detail And Home Movie Anime IDs Use Provider Router

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt`

- [ ] **Step 1: Add detail test coverage for anime movie IDs**

Add a test to `MetaDetailsTvdbProviderRoutingTest`:

```kotlin
@Test
fun `anime movie id uses provider router instead of tmdb direct path`() = runTest {
    val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
    val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
    coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
        provider = TvProvider.KITSU,
        reason = TvMetadataDecisionReason.KITSU_SUCCESS,
        value = TvMetadataEnrichment(
            seriesTvdbId = null,
            localizedTitle = "Akira",
            description = "A biker gang member becomes a test subject."
        )
    )
    val viewModel = buildMetaDetailsViewModel(
        tmdbMetadataService = tmdbMetadataService,
        tvMetadataRouter = tvMetadataRouter,
        itemType = "movie",
        itemId = "mal:47",
        meta = buildMovieMeta().copy(id = "mal:47", name = "Old title"),
        tmdbSettings = TmdbSettings(
            enabled = true,
            apiKey = "tmdb-key",
            useCredits = false,
            useProductions = false,
            useNetworks = false,
            useEpisodes = false,
            useMoreLikeThis = false,
            useReviews = false,
            useCollections = false
        )
    )

    advanceUntilIdle()

    coVerify(exactly = 1) { tvMetadataRouter.fetchEnrichment(any()) }
    coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
}
```

- [ ] **Step 2: Run detail test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsTvdbProviderRoutingTest`

Expected: FAIL because movie anime IDs still take the TMDb direct path.

- [ ] **Step 3: Modify detail enrichment branch**

Add import:

```kotlin
import com.nexio.tv.core.anime.AnimeStremioId
```

Inside `enrichMeta`, after `isTvContent`, add:

```kotlin
val hasAnimeId = AnimeStremioId.parse(meta.id) != null || AnimeStremioId.parse(itemId) != null
```

Change the `tvEnrichment` assignment from `if (isTvContent)` to:

```kotlin
val tvEnrichment = if (isTvContent || hasAnimeId) {
    tvMetadataRouter.fetchEnrichment(
        TvMetadataRequest(
            contentId = meta.id,
            fallbackContentId = itemId,
            contentType = tmdbContentType,
            language = tvdbLanguage
        )
    ).value
} else {
    null
}
```

Change the non-TV TMDb branch guard from:

```kotlin
if (!settings.isActive) return meta
```

to:

```kotlin
if (hasAnimeId && tvEnrichment != null) {
    null
} else {
    if (!settings.isActive) return meta
    val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbContentType.toApiString())
        ?: tmdbService.ensureTmdbId(itemId, itemType)
        ?: return meta
    tmdbMetadataService.fetchEnrichment(
        tmdbId = tmdbId,
        contentType = tmdbContentType
    )
}
```

- [ ] **Step 4: Add home preview test coverage for anime movie IDs**

Add a test to `HomeViewModelTvdbProviderRoutingTest`:

```kotlin
@Test
fun `anime movie preview uses provider router instead of tmdb direct path`() = runTest {
    val tvMetadataRouter = mockk<TvMetadataRouter>()
    val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
    coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
        provider = TvProvider.KITSU,
        reason = TvMetadataDecisionReason.KITSU_SUCCESS,
        value = TvMetadataEnrichment(seriesTvdbId = null, localizedTitle = "Akira")
    )
    val viewModel = createHomeViewModel(
        tvMetadataRouter = tvMetadataRouter,
        tmdbMetadataService = tmdbMetadataService
    )

    val enrichment = viewModel.fetchProviderEnrichmentForPreview(
        MetaPreview(
            id = "mal:47",
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Akira",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            imdbRating = null
        )
    )

    assertEquals("Akira", enrichment?.localizedTitle)
    coVerify(exactly = 1) { tvMetadataRouter.fetchEnrichment(any()) }
    coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
}
```

- [ ] **Step 5: Modify home preview enrichment branch**

Add import:

```kotlin
import com.nexio.tv.core.anime.AnimeStremioId
```

Change `fetchProviderEnrichmentForPreview` from:

```kotlin
if (item.type.isHomeTvContent()) {
```

to:

```kotlin
if (item.type.isHomeTvContent() || AnimeStremioId.parse(item.id) != null) {
```

- [ ] **Step 6: Run UI unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsTvdbProviderRoutingTest --tests com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt
git commit -m "feat: enrich anime movie ids through provider router"
```

---

### Task 11: Final Validation And Docs

**Files:**
- Modify: `docs-site/integrations/ratings-and-metadata.md`

- [ ] **Step 1: Add documentation note**

Add this section to `docs-site/integrations/ratings-and-metadata.md`:

```markdown
## Anime Metadata IDs

Nexio can enrich anime metadata from Kitsu when a title has a Kitsu-backed ID in the bundled anime map. Direct `kitsu:{id}` IDs are resolved as Kitsu anime IDs. `mal:{id}`, `anilist:{id}`, `anidb:{id}`, `tmdb:{id}`, `tvdb:{id}`, `tt...`, and `imdb:{id}` IDs are resolved through `anime/anime-id-map.json` before Kitsu is called.

TMDb, TVDB, and IMDb IDs still fall back to the normal TMDb/TVDB metadata routes when the bundled anime map has no matching Kitsu ID. TMDb lookups use separate movie and series indexes to avoid cross-media collisions.

## Kitsu Authentication

Kitsu login is optional for public metadata. When connected, Nexio uses Kitsu OAuth password grant to obtain access and refresh tokens. Username is stored only for display, tokens are stored through the account secret sync contract, and the password is never saved after the login exchange.
```

- [ ] **Step 2: Run focused unit test suite**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.* --tests com.nexio.tv.data.repository.KitsuAuthServiceTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest --tests com.nexio.tv.core.tvdb.TvMetadataRouterKitsuTest --tests com.nexio.tv.core.tvdb.TvMetadataRouterTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsTvdbProviderRoutingTest --tests com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest
```

Expected: PASS.

- [ ] **Step 3: Run compile check**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

- [ ] **Step 4: Confirm README does not need a provider-list edit**

Run: `rg -n "Kitsu|metadata provider|provider list|TMDB|TVDB" README.md`

Expected: either no fixed native provider list, or a provider list that is still accurate without adding Kitsu because this feature is documented in `docs-site/integrations/ratings-and-metadata.md`.

- [ ] **Step 5: Inspect git diff**

Run: `git diff --stat`

Expected: changed files are limited to `app/build.gradle.kts`, `app/src/main/assets/anime/anime-id-map.json`, Kitsu/anime metadata files, Kitsu auth/settings files, Supabase sync contract/migration files, app/web integration UI files, router wiring, focused UI routing tests, and docs.

- [ ] **Step 6: Commit docs and validation adjustments**

```bash
git add docs-site/integrations/ratings-and-metadata.md
git commit -m "docs: document kitsu anime metadata ids"
```

---

## Self-Review

**Spec coverage:** The plan supports `kitsu:`, `mal:`, `anilist:`, `anidb:`, `tmdb:`, `tvdb:`, `tt...`, and `imdb:`. TMDb, TVDB, and IMDb IDs are attempted through the local map first and fall back to normal providers when unmapped. It resolves non-Kitsu anime IDs through a generated local mapping asset before calling Kitsu, matching the aiometadata pattern without runtime mapping downloads.

**Placeholder scan:** No task depends on undefined future work. README validation is an inspection command, not an open-ended edit.

**Type consistency:** `AnimeStremioId`, `ContentMediaKind`, `AnimeIdMapAsset`, `AnimeIdMappingService`, `KitsuAuthApi`, `KitsuAuthStore`, `KitsuAuthService`, `KitsuSettings`, `KitsuMetadataService`, `TvMetadataEnrichment`, `TvEpisodeMetadata`, `TvProvider.KITSU`, and `TvMetadataDecisionReason.KITSU_SUCCESS` are introduced before use in later tasks.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-20-kitsu-anime-metadata.md`. Two execution options:

**1. Subagent-Driven (recommended)** - Dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
