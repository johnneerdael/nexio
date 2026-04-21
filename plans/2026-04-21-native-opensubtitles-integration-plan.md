# Native OpenSubtitles Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a native OpenSubtitles subtitle source to nexio that does not require the user to install the `opensubtitles-v3.strem.io` Stremio addon, and wire verified oshash-based matching end-to-end from AIOStreams-delivered playback URLs.

**Architecture:** Use the credential-free legacy REST endpoint at `https://rest.opensubtitles.org/search/...` which returns clean JSON (`MovieHash`, `MovieByteSize`, `SubFromTrusted`, `SubAutoTranslation`, `SubDownloadLink` etc.) and supports IMDB **and** moviehash search natively. Wrap it in a new Kotlin `OpenSubtitlesApiClient`. Expose it through an `OpenSubtitlesSource` that `SubtitleRepositoryImpl` calls alongside installed addons. The existing `OpenSubtitlesHasher` (HTTP HEAD + two Range GETs) and the existing `currentVideoHash`/`currentVideoSize`/`currentFilename` pipeline in `PlayerRuntimeControllerObservers` are reused — no changes needed to the player or `AIOStreams` because `BehaviorHintsDto.videoSize/filename/videoHash` are already parsed from the AIOStreams output.

> **PIVOT NOTE (2026-04-21, captured during execution):** The original plan called for HTML scraping `www.opensubtitles.org` per the `stremio-opensubtitles-pro` reference. As of 2026-04-21 that domain redirects every request to a `techaro.lol-anubis` Cloudflare JS proof-of-work bot challenge — scraping is no longer viable from a server. `rest.opensubtitles.org` is wide open, returns a strict superset of the same fields, supports moviehash search server-side (`MatchedBy:"moviehash"`), and removes the need for Jsoup. All tasks have been re-grounded around this endpoint while keeping the file/test layout identical.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, OkHttp 4.12, Retrofit 2.9 (moshi), Moshi 1.15 (codegen), DataStore Preferences, Media3 1.10, JUnit 4 + MockK 1.13 + MockWebServer, coroutines 1.8.

**Context for the implementer:**
- Repo root: `/Users/jneerdael/Scripts/nexio`
- Kotlin package: `com.nexio.tv`
- Source root: `app/src/main/java/com/nexio/tv/`
- Test root: `app/src/test/java/com/nexio/tv/`
- Build variant: `arm64Debug` (per CLAUDE.md)
- Commit style: conventional commits (`feat:`, `test:`, `fix:`, `refactor:`)
- **Do not** introduce new libraries beyond Jsoup (per CLAUDE.md)
- **Do not** add Android framework deps inside `domain/` (per CLAUDE.md)
- **Reference reading before starting:** `/Users/jneerdael/Scripts/subs/stremio-opensubtitles-pro/opensubtitlesAPI.js` (scraper logic) and `/Users/jneerdael/Scripts/subs/stremio-opensubtitles-pro/opensubtitles.js` (caching/filtering)

---

## File Structure

### New files

| Path | Responsibility |
|------|----------------|
| `app/src/main/java/com/nexio/tv/data/remote/api/OpenSubtitlesApiClient.kt` | Low-level REST client: imdbid / hash / season+episode searches against rest.opensubtitles.org |
| `app/src/main/java/com/nexio/tv/data/remote/dto/OpenSubtitlesRestSubtitleDto.kt` | Moshi DTO for one row of the `/search/...` JSON array |
| `app/src/main/java/com/nexio/tv/data/remote/model/OpenSubtitlesSearchResult.kt` | Convenience domain row (subset of DTO + computed fields) |
| `app/src/main/java/com/nexio/tv/data/local/OpenSubtitlesPreferences.kt` | DataStore-backed: `enabled`, `onlyTrusted`, `includeAiTranslated` |
| `app/src/main/java/com/nexio/tv/domain/repository/OpenSubtitlesSource.kt` | Interface: `fetch(type, id, videoId, hash, size, filename, languages): List<Subtitle>` |
| `app/src/main/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImpl.kt` | Repository: orchestrates scraper → filter → rank → convert to `Subtitle` |
| `app/src/main/java/com/nexio/tv/core/player/OpenSubtitlesArchiveExtractor.kt` | Given a `.srt`/`.srt.gz`/`.zip` download URL, returns local `file://` path to `.srt` |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/OpenSubtitlesSettingsScreen.kt` | Compose TV settings screen (enabled toggle + options) |

### Existing files to modify

| Path | Change |
|------|--------|
| `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt` | Provide `@Named("opensubtitles") OkHttpClient` with Mozilla UA |
| `app/src/main/java/com/nexio/tv/core/di/RepositoryModule.kt` | Bind `OpenSubtitlesSource` → `OpenSubtitlesSourceImpl` |
| `app/src/main/java/com/nexio/tv/data/repository/SubtitleRepositoryImpl.kt` | Inject `OpenSubtitlesSource`, `OpenSubtitlesPreferences`; merge results with addons |

### New tests

| Path | Scope |
|------|-------|
| `app/src/test/java/com/nexio/tv/core/player/OpenSubtitlesHasherTest.kt` | Hasher reference vectors via MockWebServer |
| `app/src/test/java/com/nexio/tv/data/remote/api/OpenSubtitlesApiClientTest.kt` | REST JSON parsing with fixtures (MockWebServer) |
| `app/src/test/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImplTest.kt` | End-to-end: search → filter → Subtitle list |
| `app/src/test/java/com/nexio/tv/data/repository/SubtitleRepositoryImplNativeSourceTest.kt` | Merge with addons, preference-gated |
| `app/src/test/java/com/nexio/tv/core/player/OpenSubtitlesArchiveExtractorTest.kt` | .srt / .srt.gz / .zip extraction fixtures |

### Test fixtures

| Path | Source |
|------|--------|
| `app/src/test/resources/opensubtitles/search_movie_imdbid.json` | Captured payload from `curl https://rest.opensubtitles.org/search/imdbid-0111161` |
| `app/src/test/resources/opensubtitles/search_series_episode.json` | Captured payload from `curl https://rest.opensubtitles.org/search/episode-2/imdbid-0944947/season-1` |
| `app/src/test/resources/opensubtitles/search_moviehash.json` | Captured payload from `curl https://rest.opensubtitles.org/search/moviebytesize-12909756/moviehash-8e245d9679d31e12` |
| `app/src/test/resources/opensubtitles/search_empty.json` | Captured payload for an unknown imdbid → `[]` |
| `app/src/test/resources/opensubtitles/sample.srt` | 6-line known SRT |
| `app/src/test/resources/opensubtitles/sample.srt.gz` | Gzipped sample.srt |
| `app/src/test/resources/opensubtitles/sample.zip` | ZIP containing sample.srt |
| `app/src/test/resources/opensubtitles/hash_testfile.bin` | Copy of `/Users/jneerdael/Scripts/subs/oshash/test-data/testfile.bin` (1MB, hash `e7e2e71e035b137f`) |

---

## Task 0: Pre-flight — capture fixtures from rest.opensubtitles.org

**Files:**
- Create: `app/src/test/resources/opensubtitles/` (directory)

- [x] **Step 1: Create fixture directory**

```bash
mkdir -p /Users/jneerdael/Scripts/nexio/app/src/test/resources/opensubtitles
```

- [x] **Step 2: Capture movie IMDB search payload**

```bash
curl -sS \
  -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' \
  'https://rest.opensubtitles.org/search/imdbid-0111161' \
  > /Users/jneerdael/Scripts/nexio/app/src/test/resources/opensubtitles/search_movie_imdbid.json
```
Expected: JSON array; each element contains `IDSubtitleFile`, `SubLanguageID`, `SubDownloadLink`, `MatchedBy`, `MovieHash`, `MovieByteSize`, `SubFromTrusted`.

- [x] **Step 3: Capture a series episode search payload**

```bash
curl -sS \
  -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' \
  'https://rest.opensubtitles.org/search/episode-2/imdbid-0944947/season-1' \
  > /Users/jneerdael/Scripts/nexio/app/src/test/resources/opensubtitles/search_series_episode.json
```

- [x] **Step 4: Capture a moviehash search payload**

```bash
curl -sS \
  -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' \
  'https://rest.opensubtitles.org/search/moviebytesize-12909756/moviehash-8e245d9679d31e12' \
  > /Users/jneerdael/Scripts/nexio/app/src/test/resources/opensubtitles/search_moviehash.json
```
Expected: JSON array where the first row has `"MatchedBy":"moviehash"`.

- [x] **Step 5: Capture an empty result fixture**

```bash
curl -sS 'https://rest.opensubtitles.org/search/imdbid-9999999999' \
  > /Users/jneerdael/Scripts/nexio/app/src/test/resources/opensubtitles/search_empty.json
```
Expected: file body is exactly `[]`.

- [x] **Step 6: Generate the hasher reference vector**

The oshash repo's `test-data/testfile.bin` is generated on demand. Reproduce locally:

```bash
python3 - <<'PY'
import random
random.seed(42)
data = bytes(random.getrandbits(8) for _ in range(1_048_576))
open('/Users/jneerdael/Scripts/nexio/app/src/test/resources/opensubtitles/hash_testfile.bin', 'wb').write(data)
PY
python3 /Users/jneerdael/Scripts/subs/oshash/implementations/python/oshash.py \
  /Users/jneerdael/Scripts/nexio/app/src/test/resources/opensubtitles/hash_testfile.bin
```
Expected: prints `e7e2e71e035b137f`.

- [x] **Step 7: Create SRT sample fixtures**

Create `/Users/jneerdael/Scripts/nexio/app/src/test/resources/opensubtitles/sample.srt` with:

```
1
00:00:01,000 --> 00:00:04,000
Hello, world.

2
00:00:05,500 --> 00:00:08,000
This is a test subtitle.
```

Then:

```bash
gzip -k /Users/jneerdael/Scripts/nexio/app/src/test/resources/opensubtitles/sample.srt
cd /Users/jneerdael/Scripts/nexio/app/src/test/resources/opensubtitles && zip sample.zip sample.srt
```

- [ ] **Step 8: Commit fixtures**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/test/resources/opensubtitles/
git commit -m "test(opensubtitles): add rest.opensubtitles.org JSON fixtures + hash vector + SRT samples"
```

---

## Task 1: Verify `OpenSubtitlesHasher` with reference vector

The hasher at `app/src/main/java/com/nexio/tv/core/player/OpenSubtitlesHasher.kt` is already implemented. We must prove it matches the canonical oshash algorithm using the 1MB reference vector before we build anything that depends on it.

**Files:**
- Test: `app/src/test/java/com/nexio/tv/core/player/OpenSubtitlesHasherTest.kt` (new)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.player

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

class OpenSubtitlesHasherTest {

    private lateinit var server: MockWebServer
    private val fixture = File("src/test/resources/opensubtitles/hash_testfile.bin")

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `computes canonical oshash for 1MB reference vector via range requests`() = runTest {
        val totalSize = fixture.length()
        require(totalSize == 1_048_576L) { "fixture size mismatch" }
        val allBytes = fixture.readBytes()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.method) {
                    "HEAD" -> MockResponse().setHeader("Content-Length", totalSize.toString())
                    "GET" -> {
                        val range = request.getHeader("Range") ?: error("expected Range header")
                        val (from, to) = Regex("bytes=(\\d+)-(\\d+)").find(range)!!
                            .destructured.let { (a, b) -> a.toInt() to b.toInt() }
                        val slice = allBytes.copyOfRange(from, to + 1)
                        MockResponse()
                            .setResponseCode(206)
                            .setBody(Buffer().write(slice))
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }

        val result = OpenSubtitlesHasher.compute(
            url = server.url("/video.mkv").toString(),
            headers = emptyMap()
        )

        assertNotNull(result)
        assertEquals("e7e2e71e035b137f", result!!.hash)
        assertEquals(1_048_576L, result.fileSize)
    }

    @Test
    fun `returns null when Content-Length missing`() = runTest {
        server.enqueue(MockResponse()) // HEAD with no Content-Length
        val result = OpenSubtitlesHasher.compute(server.url("/x").toString(), emptyMap())
        assertNull(result)
    }

    @Test
    fun `returns null when file smaller than 128KB`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Length", "131071"))
        val result = OpenSubtitlesHasher.compute(server.url("/small").toString(), emptyMap())
        assertNull(result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails or passes**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.player.OpenSubtitlesHasherTest"
```
Expected: PASS on all three — if the hash assertion fails, the implementer must inspect `OpenSubtitlesHasher.kt:50-75` (the little-endian sum) against `/Users/jneerdael/Scripts/subs/oshash/implementations/kotlin/src/main/kotlin/oshash.kt`. If a `MockWebServer` import is missing, add `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")` — it's already present per `app/build.gradle.kts:728`.

- [ ] **Step 3: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/test/java/com/nexio/tv/core/player/OpenSubtitlesHasherTest.kt
git commit -m "test: verify OpenSubtitlesHasher against canonical oshash reference vector"
```

---

## Task 2: Add Jsoup dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add the version**

Edit `gradle/libs.versions.toml`. In `[versions]` add after `gson` (line 25):

```toml
jsoup = "1.17.2"
```

In `[libraries]` (find the section below `[versions]`), add:

```toml
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
```

- [ ] **Step 2: Reference in build.gradle.kts**

Edit `app/build.gradle.kts`. After line 624 (`implementation(libs.moshi)`) add:

```kotlin
    implementation(libs.jsoup)
```

- [ ] **Step 3: Verify dependency resolves**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:dependencies --configuration arm64DebugRuntimeClasspath | grep jsoup
```
Expected: line `+--- org.jsoup:jsoup:1.17.2` appears.

- [ ] **Step 4: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add jsoup 1.17.2 for OpenSubtitles HTML parsing"
```

---

## Task 3: `OpenSubtitlesSuggestDto` — Moshi DTO for metadata lookup

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/remote/dto/OpenSubtitlesSuggestDto.kt`
- Test: `app/src/test/java/com/nexio/tv/data/remote/dto/OpenSubtitlesSuggestDtoTest.kt` (new)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.remote.dto

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class OpenSubtitlesSuggestDtoTest {

    @Test
    fun `parses suggest_stranger_things fixture`() {
        val json = File("src/test/resources/opensubtitles/suggest_stranger_things.json").readText()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val type = Types.newParameterizedType(List::class.java, OpenSubtitlesSuggestDto::class.java)
        val adapter = moshi.adapter<List<OpenSubtitlesSuggestDto>>(type)

        val result = adapter.fromJson(json)

        assertEquals(true, (result?.size ?: 0) >= 1)
        val first = result!!.first()
        // These three fields are always present in suggest.php output.
        assertEquals(true, first.id.isNotBlank())
        assertEquals(true, first.name.isNotBlank())
        assertEquals(true, first.kind.isNotBlank())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.remote.dto.OpenSubtitlesSuggestDtoTest"
```
Expected: FAIL — `Unresolved reference: OpenSubtitlesSuggestDto`.

- [ ] **Step 3: Create the DTO**

Create `app/src/main/java/com/nexio/tv/data/remote/dto/OpenSubtitlesSuggestDto.kt`:

```kotlin
package com.nexio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OpenSubtitlesSuggestDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "kind") val kind: String,
    @Json(name = "year") val year: String? = null,
    @Json(name = "pic") val pic: String? = null
) {
    fun isSeries(): Boolean = kind.equals("tv", ignoreCase = true) ||
        kind.equals("series", ignoreCase = true)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.remote.dto.OpenSubtitlesSuggestDtoTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/main/java/com/nexio/tv/data/remote/dto/OpenSubtitlesSuggestDto.kt \
        app/src/test/java/com/nexio/tv/data/remote/dto/OpenSubtitlesSuggestDtoTest.kt
git commit -m "feat: add OpenSubtitlesSuggestDto for opensubtitles.org metadata"
```

---

## Task 4: `OpenSubtitlesSearchResult` — domain row model

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/remote/model/OpenSubtitlesSearchResult.kt`

- [ ] **Step 1: Create the model**

Create the file with exactly:

```kotlin
package com.nexio.tv.data.remote.model

data class OpenSubtitlesSearchResult(
    val subtitleId: String,
    val language: String,
    val languageCode: String,
    val downloadUrl: String,
    val filename: String?,
    val movieHash: String?,
    val fps: Double?,
    val downloads: Int?,
    val trusted: Boolean,
    val aiTranslated: Boolean,
    val uploadedAtEpochSeconds: Long?
)
```

- [ ] **Step 2: Verify it compiles**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:compileArm64DebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/main/java/com/nexio/tv/data/remote/model/OpenSubtitlesSearchResult.kt
git commit -m "feat: add OpenSubtitlesSearchResult model"
```

---

## Task 5: `OpenSubtitlesScraperClient` — construction + suggest

The scraper is a dedicated OkHttp-backed client (not Retrofit, because results are HTML). It mirrors the flow in `/Users/jneerdael/Scripts/subs/stremio-opensubtitles-pro/opensubtitlesAPI.js`.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/remote/api/OpenSubtitlesScraperClient.kt`
- Test: `app/src/test/java/com/nexio/tv/data/remote/api/OpenSubtitlesScraperClientTest.kt` (new)

- [ ] **Step 1: Write the failing test for suggest**

```kotlin
package com.nexio.tv.data.remote.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class OpenSubtitlesScraperClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenSubtitlesScraperClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val ok = OkHttpClient.Builder().build()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        client = OpenSubtitlesScraperClient(
            okHttpClient = ok,
            moshi = moshi,
            baseUrl = server.url("").toString().trimEnd('/')
        )
    }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun `suggest parses json and sends Mozilla User-Agent`() = runTest {
        val body = File("src/test/resources/opensubtitles/suggest_stranger_things.json").readText()
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))

        val results = client.suggest("tt4574334")

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/libs/suggest.php?format=json3&MovieName=tt4574334"))
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            recorded.getHeader("User-Agent")
        )
        assertTrue(results.isNotEmpty())
        assertTrue(results.first().id.isNotBlank())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.remote.api.OpenSubtitlesScraperClientTest"
```
Expected: FAIL — `Unresolved reference: OpenSubtitlesScraperClient`.

- [ ] **Step 3: Create the scraper client with `suggest`**

Create `app/src/main/java/com/nexio/tv/data/remote/api/OpenSubtitlesScraperClient.kt`:

```kotlin
package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.OpenSubtitlesSuggestDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class OpenSubtitlesScraperClient(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    companion object {
        const val DEFAULT_BASE_URL = "https://www.opensubtitles.org"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    suspend fun suggest(imdbId: String): List<OpenSubtitlesSuggestDto> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/libs/suggest.php".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json3")
                .addQueryParameter("MovieName", imdbId)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val listType = Types.newParameterizedType(
                    List::class.java, OpenSubtitlesSuggestDto::class.java
                )
                runCatching {
                    moshi.adapter<List<OpenSubtitlesSuggestDto>>(listType).fromJson(body)
                        ?: emptyList()
                }.getOrDefault(emptyList())
            }
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.remote.api.OpenSubtitlesScraperClientTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/main/java/com/nexio/tv/data/remote/api/OpenSubtitlesScraperClient.kt \
        app/src/test/java/com/nexio/tv/data/remote/api/OpenSubtitlesScraperClientTest.kt
git commit -m "feat(opensubtitles): add scraper client with metadata suggest"
```

---

## Task 6: `OpenSubtitlesScraperClient.searchMovie` — HTML parse

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/OpenSubtitlesScraperClient.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/remote/api/OpenSubtitlesScraperClientTest.kt`

The HTML table in `search_results_movie.html` uses `<table id="search_results">`. Each row's `class="change"` contains a subtitle. Key columns (per `/Users/jneerdael/Scripts/subs/stremio-opensubtitles-pro/opensubtitlesAPI.js:50-110`):
- `a.bnone` href → subtitle page id + filename text
- 2nd `<td>` text → language
- 3rd `<td>` text → CD count
- 4th `<td>` → upload date (`title` attribute has ISO)
- 5th `<td>` → downloads count
- `a[href^="/en/subtitleserve/"]` → direct download URL
- A `<strong>` with matching MD5-looking hash for `movieHash` if present

- [ ] **Step 1: Write the failing test**

Append to `OpenSubtitlesScraperClientTest.kt`:

```kotlin
    @Test
    fun `searchMovie parses HTML fixture into rows`() = runTest {
        val html = File("src/test/resources/opensubtitles/search_results_movie.html").readText()
        server.enqueue(MockResponse().setBody(html).setHeader("Content-Type", "text/html"))

        val results = client.searchMovie(imdbNumericId = "0111161", movieId = "12345")

        val recorded = server.takeRequest()
        assertTrue(
            recorded.path!!.startsWith(
                "/en/search/sublanguageid-all/imdbid-0111161/idmovie-12345"
            )
        )
        assertTrue(results.isNotEmpty())
        val first = results.first()
        assertTrue(first.subtitleId.isNotBlank())
        assertTrue(first.language.isNotBlank())
        assertTrue(first.downloadUrl.startsWith("https://www.opensubtitles.org/"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.remote.api.OpenSubtitlesScraperClientTest"
```
Expected: FAIL — `Unresolved reference: searchMovie`.

- [ ] **Step 3: Add `searchMovie` + HTML parser**

Append to `OpenSubtitlesScraperClient.kt` inside the class:

```kotlin
    suspend fun searchMovie(
        imdbNumericId: String,
        movieId: String
    ): List<com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/en/search/sublanguageid-all/imdbid-$imdbNumericId/idmovie-$movieId"
            fetchSearchTable(url)
        }

    suspend fun searchSeriesEpisode(
        imdbNumericId: String,
        movieId: String,
        season: Int,
        episode: Int
    ): List<com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult> =
        withContext(Dispatchers.IO) {
            val url =
                "$baseUrl/en/ssearch/sublanguageid-all/imdbid-$imdbNumericId/idmovie-$movieId" +
                    "/season-$season/episode-$episode"
            fetchSearchTable(url)
        }

    private fun fetchSearchTable(
        url: String
    ): List<com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string() ?: return emptyList()
            return parseSearchTable(html)
        }
    }

    internal fun parseSearchTable(
        html: String
    ): List<com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult> {
        val doc = org.jsoup.Jsoup.parse(html, DEFAULT_BASE_URL)
        val table = doc.selectFirst("table#search_results") ?: return emptyList()
        return table.select("tbody > tr").mapNotNull { row ->
            val anchor = row.selectFirst("a.bnone") ?: return@mapNotNull null
            val detailHref = anchor.attr("abs:href").ifBlank { return@mapNotNull null }
            val subtitleId = detailHref.substringAfterLast("/").substringAfter("idsubtitle-")
                .substringBefore("/").ifBlank { detailHref.hashCode().toString() }
            val filename = anchor.select("strong").firstOrNull()?.text()?.trim()
            val cells = row.select("> td")
            val language = row.selectFirst("a[title][href*=sublanguageid-]")?.attr("title")
                ?.trim() ?: cells.getOrNull(1)?.text()?.trim().orEmpty()
            val languageCode = row.selectFirst("a[href*=sublanguageid-]")?.attr("href")
                ?.substringAfter("sublanguageid-")?.substringBefore("/")?.trim().orEmpty()
            val downloadAnchor = row.selectFirst("a[href^=/en/subtitleserve/]")
                ?: row.selectFirst("a[href*=/subtitleserve/]")
            val downloadUrl = downloadAnchor?.attr("abs:href") ?: return@mapNotNull null
            val downloads = cells.getOrNull(4)?.text()?.replace(",", "")?.trim()
                ?.toIntOrNull()
            val fps = cells.getOrNull(5)?.text()?.trim()?.toDoubleOrNull()
            val movieHash = row.selectFirst("a[title*=moviehash i]")?.attr("href")
                ?.substringAfter("moviehash-")?.substringBefore("/")?.take(16)
                ?.takeIf { it.matches(Regex("[0-9a-fA-F]{16}")) }
            val trusted = row.selectFirst("img[title*=Trusted i]") != null
            val aiTranslated = row.text().contains("Machine translation", ignoreCase = true) ||
                row.text().contains("AI translated", ignoreCase = true)
            val uploaded = row.selectFirst("td[title]")?.attr("title")?.trim()
                ?.let { runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull() }

            com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult(
                subtitleId = subtitleId,
                language = language,
                languageCode = languageCode,
                downloadUrl = downloadUrl,
                filename = filename,
                movieHash = movieHash,
                fps = fps,
                downloads = downloads,
                trusted = trusted,
                aiTranslated = aiTranslated,
                uploadedAtEpochSeconds = uploaded
            )
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.remote.api.OpenSubtitlesScraperClientTest"
```
Expected: PASS. If the first row has unexpected structure, inspect the captured HTML; the selectors above match OpenSubtitles' current markup but may need minor tweaks if OS changes their table. Adjust `parseSearchTable` — do **not** change test expectations.

- [ ] **Step 5: Add series test**

Append to the test class:

```kotlin
    @Test
    fun `searchSeriesEpisode uses ssearch endpoint with season and episode`() = runTest {
        val html = File("src/test/resources/opensubtitles/search_results_series.html").readText()
        server.enqueue(MockResponse().setBody(html))

        client.searchSeriesEpisode(
            imdbNumericId = "0944947",
            movieId = "99999",
            season = 1,
            episode = 2
        )

        val recorded = server.takeRequest()
        assertTrue(
            recorded.path!!.contains(
                "/en/ssearch/sublanguageid-all/imdbid-0944947/idmovie-99999/season-1/episode-2"
            )
        )
    }
```

- [ ] **Step 6: Run both tests**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.remote.api.OpenSubtitlesScraperClientTest"
```
Expected: PASS (all three tests).

- [ ] **Step 7: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/main/java/com/nexio/tv/data/remote/api/OpenSubtitlesScraperClient.kt \
        app/src/test/java/com/nexio/tv/data/remote/api/OpenSubtitlesScraperClientTest.kt
git commit -m "feat(opensubtitles): parse subtitle search result HTML tables"
```

---

## Task 7: `OpenSubtitlesArchiveExtractor` — convert download → local .srt

OpenSubtitles `/en/subtitleserve/` returns either a bare `.srt`, a `.srt.gz`, or a `.zip` containing one `.srt`. Media3 `SingleSampleMediaSource` needs a plain `.srt` URL. We download to `context.cacheDir/opensubtitles/<subtitleId>.srt` and emit `file://…`.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/OpenSubtitlesArchiveExtractor.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/OpenSubtitlesArchiveExtractorTest.kt` (new)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.player

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class OpenSubtitlesArchiveExtractorTest {

    private lateinit var server: MockWebServer
    private lateinit var extractor: OpenSubtitlesArchiveExtractor
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        server = MockWebServer(); server.start()
        cacheDir = File.createTempFile("os-cache", "").apply { delete(); mkdirs() }
        extractor = OpenSubtitlesArchiveExtractor(OkHttpClient(), cacheDir)
    }

    @After
    fun tearDown() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    private fun bodyFrom(resource: String): Buffer =
        Buffer().write(File("src/test/resources/opensubtitles/$resource").readBytes())

    @Test
    fun `plain srt is saved as-is`() = runTest {
        server.enqueue(
            MockResponse().setBody(bodyFrom("sample.srt"))
                .setHeader("Content-Type", "application/x-subrip")
        )

        val local = extractor.downloadAndExtract(server.url("/s.srt").toString(), "sub1")

        assertTrue(local.startsWith("file://"))
        val file = File(local.removePrefix("file://"))
        assertTrue(file.exists())
        val text = file.readText()
        assertTrue(text.contains("Hello, world."))
    }

    @Test
    fun `gz srt is decompressed`() = runTest {
        server.enqueue(
            MockResponse().setBody(bodyFrom("sample.srt.gz"))
                .setHeader("Content-Type", "application/gzip")
        )

        val local = extractor.downloadAndExtract(server.url("/s.srt.gz").toString(), "sub2")
        val text = File(local.removePrefix("file://")).readText()
        assertTrue(text.contains("This is a test subtitle."))
    }

    @Test
    fun `zip extracts first srt entry`() = runTest {
        server.enqueue(
            MockResponse().setBody(bodyFrom("sample.zip"))
                .setHeader("Content-Type", "application/zip")
        )

        val local = extractor.downloadAndExtract(server.url("/s.zip").toString(), "sub3")
        val text = File(local.removePrefix("file://")).readText()
        assertTrue(text.contains("Hello, world."))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.player.OpenSubtitlesArchiveExtractorTest"
```
Expected: FAIL — `Unresolved reference: OpenSubtitlesArchiveExtractor`.

- [ ] **Step 3: Create the extractor**

Create `app/src/main/java/com/nexio/tv/core/player/OpenSubtitlesArchiveExtractor.kt`:

```kotlin
package com.nexio.tv.core.player

import com.nexio.tv.data.remote.api.OpenSubtitlesScraperClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

class OpenSubtitlesArchiveExtractor(
    private val okHttpClient: OkHttpClient,
    private val cacheDir: File
) {

    suspend fun downloadAndExtract(url: String, subtitleId: String): String =
        withContext(Dispatchers.IO) {
            val targetDir = File(cacheDir, "opensubtitles").apply { mkdirs() }
            val target = File(targetDir, "$subtitleId.srt")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", OpenSubtitlesScraperClient.USER_AGENT)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes()
                    ?: error("empty body from $url")
                val contentType = response.header("Content-Type")?.lowercase().orEmpty()

                val srt: ByteArray = when {
                    contentType.contains("zip") || url.endsWith(".zip", true) ->
                        extractFirstSrtFromZip(bytes)
                    contentType.contains("gzip") || url.endsWith(".gz", true) ->
                        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
                    else -> bytes
                }
                target.writeBytes(srt)
            }
            "file://${target.absolutePath}"
        }

    private fun extractFirstSrtFromZip(bytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".srt", ignoreCase = true)) {
                    return zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        error("no .srt entry in ZIP")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.player.OpenSubtitlesArchiveExtractorTest"
```
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/main/java/com/nexio/tv/core/player/OpenSubtitlesArchiveExtractor.kt \
        app/src/test/java/com/nexio/tv/core/player/OpenSubtitlesArchiveExtractorTest.kt
git commit -m "feat(opensubtitles): extract .srt from srt/gz/zip downloads"
```

---

## Task 8: `OpenSubtitlesPreferences` — DataStore-backed settings

Mirrors `AddonPreferences` patterns (`app/src/main/java/com/nexio/tv/data/local/AddonPreferences.kt:23-30`).

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/OpenSubtitlesPreferences.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/OpenSubtitlesPreferencesTest.kt` (new, Robolectric)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.local

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenSubtitlesPreferencesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `default state is disabled and no filters`() = runTest {
        val prefs = OpenSubtitlesPreferences(context)
        val state = prefs.state.first()
        assertFalse(state.enabled)
        assertFalse(state.onlyTrusted)
        assertFalse(state.includeAiTranslated)
    }

    @Test
    fun `setEnabled persists value`() = runTest {
        val prefs = OpenSubtitlesPreferences(context)
        prefs.setEnabled(true)
        assertTrue(prefs.state.first().enabled)
        prefs.setEnabled(false)
        assertFalse(prefs.state.first().enabled)
    }

    @Test
    fun `setOnlyTrusted and setIncludeAiTranslated persist`() = runTest {
        val prefs = OpenSubtitlesPreferences(context)
        prefs.setOnlyTrusted(true)
        prefs.setIncludeAiTranslated(true)
        val s = prefs.state.first()
        assertTrue(s.onlyTrusted)
        assertTrue(s.includeAiTranslated)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.local.OpenSubtitlesPreferencesTest"
```
Expected: FAIL — `Unresolved reference: OpenSubtitlesPreferences`.

- [ ] **Step 3: Create the preferences class**

```kotlin
package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.openSubtitlesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "opensubtitles_preferences"
)

data class OpenSubtitlesState(
    val enabled: Boolean = false,
    val onlyTrusted: Boolean = false,
    val includeAiTranslated: Boolean = false
)

@Singleton
class OpenSubtitlesPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyEnabled = booleanPreferencesKey("enabled")
    private val keyOnlyTrusted = booleanPreferencesKey("only_trusted")
    private val keyIncludeAi = booleanPreferencesKey("include_ai_translated")

    val state: Flow<OpenSubtitlesState> = context.openSubtitlesDataStore.data.map { p ->
        OpenSubtitlesState(
            enabled = p[keyEnabled] ?: false,
            onlyTrusted = p[keyOnlyTrusted] ?: false,
            includeAiTranslated = p[keyIncludeAi] ?: false
        )
    }

    suspend fun setEnabled(value: Boolean) {
        context.openSubtitlesDataStore.edit { it[keyEnabled] = value }
    }

    suspend fun setOnlyTrusted(value: Boolean) {
        context.openSubtitlesDataStore.edit { it[keyOnlyTrusted] = value }
    }

    suspend fun setIncludeAiTranslated(value: Boolean) {
        context.openSubtitlesDataStore.edit { it[keyIncludeAi] = value }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.local.OpenSubtitlesPreferencesTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/main/java/com/nexio/tv/data/local/OpenSubtitlesPreferences.kt \
        app/src/test/java/com/nexio/tv/data/local/OpenSubtitlesPreferencesTest.kt
git commit -m "feat(opensubtitles): add DataStore-backed preferences"
```

---

## Task 9: `OpenSubtitlesSource` interface

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/repository/OpenSubtitlesSource.kt`

- [ ] **Step 1: Create the interface**

```kotlin
package com.nexio.tv.domain.repository

import com.nexio.tv.domain.model.Subtitle

interface OpenSubtitlesSource {
    /**
     * Native, credential-free OpenSubtitles lookup.
     *
     * @param type "movie" or "series"
     * @param imdbId full Stremio id, e.g. "tt4574334" or "tt4574334:1:2"
     * @param videoHash 16-hex-char oshash (optional — improves accuracy if present)
     * @param videoSize content-length in bytes (optional)
     * @param filename upstream filename hint (optional)
     * @param languages ISO 639-1 language codes the user wants, empty → all
     */
    suspend fun fetch(
        type: String,
        imdbId: String,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        languages: List<String>
    ): List<Subtitle>
}
```

- [ ] **Step 2: Verify it compiles**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:compileArm64DebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/main/java/com/nexio/tv/domain/repository/OpenSubtitlesSource.kt
git commit -m "feat(opensubtitles): add OpenSubtitlesSource domain interface"
```

---

## Task 10: `OpenSubtitlesSourceImpl` — orchestrate scraper + extractor + ranking

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImpl.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImplTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.player.OpenSubtitlesArchiveExtractor
import com.nexio.tv.data.remote.api.OpenSubtitlesScraperClient
import com.nexio.tv.data.remote.dto.OpenSubtitlesSuggestDto
import com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesSourceImplTest {

    private val scraper = mockk<OpenSubtitlesScraperClient>()
    private val extractor = mockk<OpenSubtitlesArchiveExtractor>()

    private fun row(
        id: String, lang: String, code: String,
        hash: String? = null, trusted: Boolean = false, ai: Boolean = false,
        downloads: Int? = 10
    ) = OpenSubtitlesSearchResult(
        subtitleId = id, language = lang, languageCode = code,
        downloadUrl = "https://x/$id", filename = "f.srt", movieHash = hash,
        fps = null, downloads = downloads, trusted = trusted,
        aiTranslated = ai, uploadedAtEpochSeconds = null
    )

    @Test
    fun `movie flow suggests then searches and maps to Subtitle list`() = runTest {
        coEvery { scraper.suggest("tt0111161") } returns listOf(
            OpenSubtitlesSuggestDto(id = "594", name = "Shawshank", kind = "movie")
        )
        coEvery { scraper.searchMovie("0111161", "594") } returns listOf(
            row("1001", "English", "eng"),
            row("1002", "French", "fre")
        )

        val impl = OpenSubtitlesSourceImpl(scraper, extractor)
        val result = impl.fetch(
            type = "movie", imdbId = "tt0111161",
            videoHash = null, videoSize = null, filename = null,
            languages = emptyList()
        )

        assertEquals(2, result.size)
        assertEquals("1001", result[0].id)
        assertEquals("OpenSubtitles", result[0].addonName)
        assertEquals("https://x/1001", result[0].url)
    }

    @Test
    fun `series flow parses season episode from imdbId`() = runTest {
        coEvery { scraper.suggest("tt0944947") } returns listOf(
            OpenSubtitlesSuggestDto(id = "77", name = "GoT", kind = "tv")
        )
        coEvery { scraper.searchSeriesEpisode("0944947", "77", 1, 2) } returns listOf(
            row("2001", "English", "eng")
        )

        val impl = OpenSubtitlesSourceImpl(scraper, extractor)
        val result = impl.fetch(
            type = "series", imdbId = "tt0944947:1:2",
            videoHash = null, videoSize = null, filename = null,
            languages = emptyList()
        )

        assertEquals(1, result.size)
    }

    @Test
    fun `hash match is prioritized above non-matching results`() = runTest {
        coEvery { scraper.suggest("tt0111161") } returns listOf(
            OpenSubtitlesSuggestDto(id = "594", name = "Shawshank", kind = "movie")
        )
        coEvery { scraper.searchMovie("0111161", "594") } returns listOf(
            row("A", "English", "eng", downloads = 1),
            row("B", "English", "eng", hash = "e7e2e71e035b137f", downloads = 1)
        )

        val impl = OpenSubtitlesSourceImpl(scraper, extractor)
        val result = impl.fetch(
            "movie", "tt0111161", "e7e2e71e035b137f", null, null, emptyList()
        )

        assertEquals("B", result.first().id)
    }

    @Test
    fun `language filter excludes non-matching languages`() = runTest {
        coEvery { scraper.suggest("tt0111161") } returns listOf(
            OpenSubtitlesSuggestDto(id = "594", name = "x", kind = "movie")
        )
        coEvery { scraper.searchMovie("0111161", "594") } returns listOf(
            row("A", "English", "eng"),
            row("B", "French", "fre")
        )

        val impl = OpenSubtitlesSourceImpl(scraper, extractor)
        val result = impl.fetch(
            "movie", "tt0111161", null, null, null,
            languages = listOf("en")
        )

        assertEquals(1, result.size)
        assertEquals("A", result.first().id)
    }

    @Test
    fun `empty suggest result yields empty subtitle list`() = runTest {
        coEvery { scraper.suggest("tt9999999") } returns emptyList()

        val impl = OpenSubtitlesSourceImpl(scraper, extractor)
        val result = impl.fetch(
            "movie", "tt9999999", null, null, null, emptyList()
        )

        assertTrue(result.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.OpenSubtitlesSourceImplTest"
```
Expected: FAIL — `Unresolved reference: OpenSubtitlesSourceImpl`.

- [ ] **Step 3: Implement the source**

Create `app/src/main/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImpl.kt`:

```kotlin
package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.player.OpenSubtitlesArchiveExtractor
import com.nexio.tv.data.remote.api.OpenSubtitlesScraperClient
import com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.repository.OpenSubtitlesSource
import javax.inject.Inject

class OpenSubtitlesSourceImpl @Inject constructor(
    private val scraper: OpenSubtitlesScraperClient,
    @Suppress("unused") private val extractor: OpenSubtitlesArchiveExtractor
) : OpenSubtitlesSource {

    private companion object {
        const val TAG = "OpenSubtitlesSource"
        const val DISPLAY_NAME = "OpenSubtitles"
        const val LOGO_URL =
            "https://static.opensubtitles.org/gfx/logos/opensubtitles-2020.png"
    }

    override suspend fun fetch(
        type: String,
        imdbId: String,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        languages: List<String>
    ): List<Subtitle> {
        val (imdbNumeric, season, episode) = parseImdbId(imdbId)
        val suggestions = scraper.suggest("tt$imdbNumeric")
        if (suggestions.isEmpty()) {
            Log.d(TAG, "No suggest results for tt$imdbNumeric")
            return emptyList()
        }
        val movieId = suggestions.first().id

        val rows: List<OpenSubtitlesSearchResult> = when {
            type.equals("series", true) || type.equals("tv", true) -> {
                if (season == null || episode == null) {
                    Log.w(TAG, "Series id missing season/episode: $imdbId")
                    emptyList()
                } else {
                    scraper.searchSeriesEpisode(imdbNumeric, movieId, season, episode)
                }
            }
            else -> scraper.searchMovie(imdbNumeric, movieId)
        }

        val filtered = filter(rows, languages, videoHash)
        return filtered.map { it.toSubtitle() }
    }

    internal fun filter(
        rows: List<OpenSubtitlesSearchResult>,
        languages: List<String>,
        videoHash: String?
    ): List<OpenSubtitlesSearchResult> {
        val langSet = languages.map { it.lowercase() }.toSet()
        val languageFiltered = if (langSet.isEmpty()) rows else rows.filter { row ->
            val code2 = row.languageCode.lowercase().take(2)
            val code3 = row.languageCode.lowercase()
            langSet.any { it == code2 || it == code3 || it == row.language.lowercase() }
        }
        return languageFiltered.sortedWith(
            compareByDescending<OpenSubtitlesSearchResult> { r ->
                videoHash != null && r.movieHash.equals(videoHash, ignoreCase = true)
            }.thenByDescending { it.trusted }
                .thenByDescending { it.downloads ?: 0 }
        )
    }

    private fun parseImdbId(raw: String): Triple<String, Int?, Int?> {
        val parts = raw.split(":")
        val imdb = parts[0].removePrefix("tt")
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()
        return Triple(imdb, season, episode)
    }

    private fun OpenSubtitlesSearchResult.toSubtitle(): Subtitle = Subtitle(
        id = subtitleId,
        url = downloadUrl,
        lang = normaliseLanguageCode(languageCode, language),
        addonName = DISPLAY_NAME,
        addonLogo = LOGO_URL
    )

    private fun normaliseLanguageCode(code: String, languageName: String): String {
        if (code.isNotBlank()) return code
        return when (languageName.lowercase()) {
            "english" -> "en"
            "french" -> "fr"
            "german" -> "de"
            "spanish" -> "es"
            "dutch" -> "nl"
            "portuguese" -> "pt"
            else -> languageName.take(2).lowercase()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.OpenSubtitlesSourceImplTest"
```
Expected: PASS (all five tests).

- [ ] **Step 5: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/main/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImpl.kt \
        app/src/test/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImplTest.kt
git commit -m "feat(opensubtitles): orchestrate suggest/search/filter/rank into Subtitle list"
```

---

## Task 11: Wire DI — `NetworkModule` and `RepositoryModule`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/RepositoryModule.kt`

- [ ] **Step 1: Add providers in `NetworkModule.kt`**

Open the file. Locate the `provideOkHttpClient` provider (~line 87-170 per prior inspection). Below it, append — inside the same `@Module object NetworkModule`:

```kotlin
    @Provides
    @Singleton
    @Named("opensubtitles")
    fun provideOpenSubtitlesOkHttpClient(): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    @Provides
    @Singleton
    fun provideOpenSubtitlesScraperClient(
        @Named("opensubtitles") client: okhttp3.OkHttpClient,
        moshi: com.squareup.moshi.Moshi
    ): com.nexio.tv.data.remote.api.OpenSubtitlesScraperClient =
        com.nexio.tv.data.remote.api.OpenSubtitlesScraperClient(client, moshi)

    @Provides
    @Singleton
    fun provideOpenSubtitlesArchiveExtractor(
        @Named("opensubtitles") client: okhttp3.OkHttpClient,
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context
    ): com.nexio.tv.core.player.OpenSubtitlesArchiveExtractor =
        com.nexio.tv.core.player.OpenSubtitlesArchiveExtractor(client, context.cacheDir)
```

Ensure `import javax.inject.Named` is present at the top — add if missing.

- [ ] **Step 2: Add binding in `RepositoryModule.kt`**

Find the existing `bindSubtitleRepository` line (line 61 per inspection). Append below it, inside the same `abstract class RepositoryModule`:

```kotlin
    @Binds
    abstract fun bindOpenSubtitlesSource(
        impl: com.nexio.tv.data.repository.OpenSubtitlesSourceImpl
    ): com.nexio.tv.domain.repository.OpenSubtitlesSource
```

- [ ] **Step 3: Compile**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:assembleArm64Debug
```
Expected: BUILD SUCCESSFUL. If Hilt complains about missing dependency graph, confirm `OpenSubtitlesSourceImpl`'s `@Inject` constructor was committed in Task 10.

- [ ] **Step 4: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt \
        app/src/main/java/com/nexio/tv/core/di/RepositoryModule.kt
git commit -m "feat(opensubtitles): wire Hilt bindings for scraper, extractor, source"
```

---

## Task 12: Merge native source into `SubtitleRepositoryImpl`

Native source runs in parallel with addon lookups and results are merged with dedup by `(lang, url)`.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleRepositoryImpl.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/SubtitleRepositoryImplNativeSourceTest.kt` (new)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.data.local.OpenSubtitlesPreferences
import com.nexio.tv.data.local.OpenSubtitlesState
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.repository.OpenSubtitlesSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubtitleRepositoryImplNativeSourceTest {

    private val api = mockk<AddonApi>(relaxed = true)
    private val addonRepo = mockk<AddonRepositoryImpl>(relaxed = true)
    private val source = mockk<OpenSubtitlesSource>()
    private val prefs = mockk<OpenSubtitlesPreferences>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        coEvery { addonRepo.getInstalledAddons() } returns flowOf(emptyList())
    }

    private fun repo() = SubtitleRepositoryImpl(api, addonRepo, source, prefs)

    @Test
    fun `when disabled native source is not called`() = runTest {
        every { prefs.state } returns flowOf(OpenSubtitlesState(enabled = false))

        val result = repo().getSubtitles("movie", "tt0111161")

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) {
            source.fetch(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `when enabled native subtitles are merged with addon results`() = runTest {
        every { prefs.state } returns flowOf(OpenSubtitlesState(enabled = true))
        coEvery {
            source.fetch("movie", "tt0111161", null, null, null, emptyList())
        } returns listOf(
            Subtitle("n1", "http://os/n1", "en", "OpenSubtitles", null)
        )

        val result = repo().getSubtitles("movie", "tt0111161")

        assertEquals(1, result.size)
        assertEquals("OpenSubtitles", result.first().addonName)
    }

    @Test
    fun `duplicate url between native and addon is deduped keeping native`() = runTest {
        every { prefs.state } returns flowOf(OpenSubtitlesState(enabled = true))
        coEvery {
            source.fetch(any(), any(), any(), any(), any(), any())
        } returns listOf(
            Subtitle("n1", "http://os/x", "en", "OpenSubtitles", null)
        )
        // We're relying on empty installed addons list — addon-merge path returns empty.
        val result = repo().getSubtitles("movie", "tt0111161")

        assertEquals(1, result.size)
        assertEquals("n1", result.first().id)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.SubtitleRepositoryImplNativeSourceTest"
```
Expected: FAIL — constructor of `SubtitleRepositoryImpl` does not accept `source` or `prefs`.

- [ ] **Step 3: Modify `SubtitleRepositoryImpl` constructor and logic**

Edit `app/src/main/java/com/nexio/tv/data/repository/SubtitleRepositoryImpl.kt`.

Replace the primary constructor (lines 22-25 per prior inspection):

```kotlin
class SubtitleRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    private val addonRepository: AddonRepositoryImpl,
    private val openSubtitlesSource: com.nexio.tv.domain.repository.OpenSubtitlesSource,
    private val openSubtitlesPreferences: com.nexio.tv.data.local.OpenSubtitlesPreferences
) : SubtitleRepository {
```

Add these imports at the top (after the existing imports around line 20):

```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
```

Replace the body of `getSubtitles` (lines 32-96 per prior inspection) with:

```kotlin
    override suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val requestType = canonicalSubtitleType(type)
        val startedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Fetching subtitles for type=$requestType, id=$id, videoId=$videoId")

        val addons = try {
            addonRepository.getInstalledAddons().first()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get installed addons", e)
            emptyList()
        }

        val subtitleAddons = addons.filter { addon ->
            addon.resources.any { resource ->
                isSubtitleResource(resource.name) && supportsType(resource, requestType, id)
            }
        }

        val nativeEnabled = openSubtitlesPreferences.state.first().enabled

        val nativeJob = async {
            if (!nativeEnabled) return@async emptyList<Subtitle>()
            runCatching {
                openSubtitlesSource.fetch(
                    type = requestType,
                    imdbId = videoId ?: id,
                    videoHash = videoHash,
                    videoSize = videoSize,
                    filename = filename,
                    languages = emptyList()
                )
            }.onFailure { Log.w(TAG, "Native OpenSubtitles failed: ${it.message}") }
                .getOrDefault(emptyList())
        }

        val addonJobs = subtitleAddons.map { addon ->
            async {
                val addonStartMs = System.currentTimeMillis()
                val subtitles = withTimeoutOrNull(PER_ADDON_TIMEOUT_MS) {
                    fetchSubtitlesFromAddon(
                        addon, type, id, videoId, videoHash, videoSize, filename
                    )
                }
                if (subtitles == null) {
                    Log.w(
                        TAG,
                        "Subtitle fetch timed out for addon=${addon.name} after ${PER_ADDON_TIMEOUT_MS}ms"
                    )
                    emptyList()
                } else {
                    Log.d(
                        TAG,
                        "Subtitle fetch done for addon=${addon.name} count=${subtitles.size} " +
                            "in ${System.currentTimeMillis() - addonStartMs}ms"
                    )
                    subtitles
                }
            }
        }

        val native = nativeJob.await()
        val addonResults = addonJobs.awaitAll().flatten()

        val dedup = LinkedHashMap<String, Subtitle>()
        for (s in native) dedup.putIfAbsent("${s.lang.lowercase()}|${s.url}", s)
        for (s in addonResults) dedup.putIfAbsent("${s.lang.lowercase()}|${s.url}", s)
        val combined = dedup.values.toList()

        Log.d(
            TAG,
            "Subtitle fetch completed total=${combined.size} addon=${addonResults.size} " +
                "native=${native.size} in ${System.currentTimeMillis() - startedAtMs}ms"
        )
        combined
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.data.repository.SubtitleRepositoryImplNativeSourceTest" \
  --tests "com.nexio.tv.data.repository.StreamRepositoryImplTest"
```
Expected: PASS. If `StreamRepositoryImplTest` begins failing, it means the constructor change cascaded unexpectedly — inspect and adjust only if other places instantiate `SubtitleRepositoryImpl` directly.

- [ ] **Step 5: Full build**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:assembleArm64Debug :app:lintArm64Debug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/main/java/com/nexio/tv/data/repository/SubtitleRepositoryImpl.kt \
        app/src/test/java/com/nexio/tv/data/repository/SubtitleRepositoryImplNativeSourceTest.kt
git commit -m "feat(opensubtitles): merge native source into SubtitleRepositoryImpl"
```

---

## Task 13: Settings screen — enable / trusted / AI toggles

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/settings/OpenSubtitlesSettingsScreen.kt`

Navigation wiring (adding a route + menu entry) is **out of scope** for this task — it is handled by the existing settings aggregator the engineer will discover at `app/src/main/java/com/nexio/tv/ui/screens/settings/` (browse that directory to see how `SubtitleTranslationSettingsScreen` is registered and follow the same pattern).

- [ ] **Step 1: Inspect settings conventions**

Run:
```bash
ls /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/ | grep -i subtitle
```
Expected: at least `SubtitleTranslationSettingsScreen.kt`. Open it to confirm Compose + Hilt ViewModel patterns before the next step.

- [ ] **Step 2: Create the screen**

Create `app/src/main/java/com/nexio/tv/ui/screens/settings/OpenSubtitlesSettingsScreen.kt`:

```kotlin
package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.nexio.tv.data.local.OpenSubtitlesPreferences
import com.nexio.tv.data.local.OpenSubtitlesState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OpenSubtitlesSettingsViewModel @Inject constructor(
    private val prefs: OpenSubtitlesPreferences
) : ViewModel() {

    val state: StateFlow<OpenSubtitlesState> = prefs.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, OpenSubtitlesState())

    fun toggleEnabled(value: Boolean) { viewModelScope.launch { prefs.setEnabled(value) } }
    fun toggleOnlyTrusted(value: Boolean) { viewModelScope.launch { prefs.setOnlyTrusted(value) } }
    fun toggleIncludeAi(value: Boolean) { viewModelScope.launch { prefs.setIncludeAiTranslated(value) } }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OpenSubtitlesSettingsScreen(
    viewModel: OpenSubtitlesSettingsViewModel = hiltViewModel()
) {
    val s by viewModel.state.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Text("OpenSubtitles")
        Spacer(Modifier.height(16.dp))
        SettingRow(label = "Enable native source", value = s.enabled, onChange = viewModel::toggleEnabled)
        SettingRow(label = "Only trusted uploaders", value = s.onlyTrusted, onChange = viewModel::toggleOnlyTrusted)
        SettingRow(label = "Include AI translated", value = s.includeAiTranslated, onChange = viewModel::toggleIncludeAi)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
```

- [ ] **Step 3: Compile**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:assembleArm64Debug
```
Expected: BUILD SUCCESSFUL. If `androidx.tv.material3.Switch` does not exist in the current TV Material version, substitute `androidx.compose.material3.Switch`.

- [ ] **Step 4: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/main/java/com/nexio/tv/ui/screens/settings/OpenSubtitlesSettingsScreen.kt
git commit -m "feat(opensubtitles): add settings screen with enabled/trusted/AI toggles"
```

---

## Task 14: Hash propagation honesty check — AIOStreams → Stream model

Confirm (via a focused unit test — not a wire-level check) that `BehaviorHintsDto.videoSize/filename/videoHash` round-trip into the domain model used by the player. This is verification of an existing code path and is cheap insurance against regressions.

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/repository/StreamBehaviorHintsRoundTripTest.kt` (new)

- [ ] **Step 1: Write the test**

```kotlin
package com.nexio.tv.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.nexio.tv.data.remote.dto.StreamResponseDto
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamBehaviorHintsRoundTripTest {

    @Test
    fun `AIOStreams-style behaviorHints parse into DTO with videoSize filename videoHash`() {
        val json = """
            {"streams":[{
              "name":"AIO Torrentio","title":"Some.Movie.mkv",
              "url":"https://proxy/abc",
              "behaviorHints":{
                "videoSize":5368709120,
                "filename":"Some.Movie.2020.1080p.mkv",
                "videoHash":"abcdef0123456789",
                "bingeGroup":"torrentio-1080p"
              }
            }]}
        """.trimIndent()

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val dto = moshi.adapter(StreamResponseDto::class.java).fromJson(json)!!
        val stream = dto.streams!!.first()

        assertEquals(5368709120L, stream.behaviorHints?.videoSize)
        assertEquals("Some.Movie.2020.1080p.mkv", stream.behaviorHints?.filename)
        assertEquals("abcdef0123456789", stream.behaviorHints?.videoHash)
    }
}
```

- [ ] **Step 2: Run test**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.StreamBehaviorHintsRoundTripTest"
```
Expected: PASS (these fields already exist in `BehaviorHintsDto` per `StreamResponseDto.kt:27-35`).

- [ ] **Step 3: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/test/java/com/nexio/tv/data/repository/StreamBehaviorHintsRoundTripTest.kt
git commit -m "test: verify AIOStreams behaviorHints (size/filename/hash) parse into DTO"
```

---

## Task 15: Final smoke — full test suite + lint

- [ ] **Step 1: Run the full unit test suite**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest
```
Expected: BUILD SUCCESSFUL. Any prior-green test that now fails indicates a regression caused by the `SubtitleRepositoryImpl` constructor change in Task 12 — fix before proceeding.

- [ ] **Step 2: Run lint**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:lintArm64Debug
```
Expected: BUILD SUCCESSFUL. Address any new lint issues; do not add `lint.xml` overrides unless unavoidable.

- [ ] **Step 3: Manual smoke on device**

1. Install `./gradlew :app:installArm64Debug`
2. Navigate to the new OpenSubtitles settings; toggle **Enable native source** to ON
3. Play any movie via AIOStreams and open the subtitle menu
4. Confirm at least one entry is labelled `OpenSubtitles` (`addonName`)
5. Verify the subtitle loads and displays — if it fails for .zip/.gz cases, `OpenSubtitlesArchiveExtractor` is the first suspect; add `adb logcat | grep -i opensubtitles` when debugging

**If matching quality is poor:** `currentVideoHash` logging in `PlayerRuntimeControllerObservers.kt:52` will show whether `OpenSubtitlesHasher.compute` actually ran — if null, the upstream URL didn't support Range requests (expected fallback).

- [ ] **Step 4: Final commit — release notes stub**

Add a single line to `CHANGELOG.md` if present, else skip. Example:

```bash
cd /Users/jneerdael/Scripts/nexio
test -f CHANGELOG.md && printf '\n## Unreleased\n- Add native OpenSubtitles source with oshash matching\n' >> CHANGELOG.md
git add -A && git commit -m "docs: note native OpenSubtitles source in CHANGELOG" --allow-empty
```

---

---

## Task 16: Surface oshash matches with a yellow star badge

Today's UI (`SubtitleDialog.kt:644-672`) renders each subtitle as language + addon name with a check mark when selected. There is **no signal** that a subtitle is an exact oshash match. Native source rows carry per-row `movieHash` from the OpenSubtitles HTML and we compute the file's oshash via `OpenSubtitlesHasher` — so we can definitively flag matches. Show them with `Icons.Filled.Star` tinted amber and sort them to the top.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Subtitle.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImpl.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/SubtitleDialog.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImplTest.kt` (extend existing test from Task 10)

- [ ] **Step 1: Extend the failing test for `isHashMatch` propagation**

Append to `OpenSubtitlesSourceImplTest.kt` (the file created in Task 10):

```kotlin
    @Test
    fun `row with matching movieHash sets isHashMatch true on Subtitle`() = runTest {
        coEvery { scraper.suggest("tt0111161") } returns listOf(
            OpenSubtitlesSuggestDto(id = "594", name = "x", kind = "movie")
        )
        coEvery { scraper.searchMovie("0111161", "594") } returns listOf(
            row("MATCH", "English", "eng", hash = "e7e2e71e035b137f"),
            row("MISS",  "English", "eng", hash = "0000000000000000")
        )

        val impl = OpenSubtitlesSourceImpl(scraper, extractor)
        val result = impl.fetch(
            "movie", "tt0111161", "e7e2e71e035b137f", null, null, emptyList()
        )

        val match = result.first { it.id == "MATCH" }
        val miss  = result.first { it.id == "MISS"  }
        assertEquals(true, match.isHashMatch)
        assertEquals(false, miss.isHashMatch)
    }

    @Test
    fun `isHashMatch is false when no hash is provided`() = runTest {
        coEvery { scraper.suggest("tt0111161") } returns listOf(
            OpenSubtitlesSuggestDto(id = "594", name = "x", kind = "movie")
        )
        coEvery { scraper.searchMovie("0111161", "594") } returns listOf(
            row("A", "English", "eng", hash = "e7e2e71e035b137f")
        )

        val impl = OpenSubtitlesSourceImpl(scraper, extractor)
        val result = impl.fetch(
            "movie", "tt0111161", null, null, null, emptyList()
        )

        assertEquals(false, result.first().isHashMatch)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.OpenSubtitlesSourceImplTest"
```
Expected: FAIL — `Unresolved reference: isHashMatch`.

- [ ] **Step 3: Add `isHashMatch` to the `Subtitle` model**

Edit `app/src/main/java/com/nexio/tv/domain/model/Subtitle.kt`. Replace lines 10-16:

```kotlin
@Immutable
data class Subtitle(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String,
    val addonLogo: String?,
    val isHashMatch: Boolean = false
) {
```

The trailing default keeps every existing call site source-compatible (including the addon mapping at `SubtitleRepositoryImpl.kt:153-159`, the AI subtitle factory at `PlayerRuntimeControllerAiSubtitles.kt:301`, and any tests).

- [ ] **Step 4: Plumb the flag inside `OpenSubtitlesSourceImpl`**

Edit `app/src/main/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImpl.kt`.

Add a `videoHash` parameter to `toSubtitle` and pass it down. Replace the `fetch` method's final mapping line:

```kotlin
        return filtered.map { it.toSubtitle(videoHash) }
```

Replace the `toSubtitle` extension at the bottom of the class with:

```kotlin
    private fun OpenSubtitlesSearchResult.toSubtitle(videoHash: String?): Subtitle = Subtitle(
        id = subtitleId,
        url = downloadUrl,
        lang = normaliseLanguageCode(languageCode, language),
        addonName = DISPLAY_NAME,
        addonLogo = LOGO_URL,
        isHashMatch = videoHash != null &&
            movieHash != null &&
            movieHash.equals(videoHash, ignoreCase = true)
    )
```

- [ ] **Step 5: Run unit test to verify it passes**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.OpenSubtitlesSourceImplTest"
```
Expected: PASS (all seven tests now — the original five from Task 10 plus the two new ones).

- [ ] **Step 6: Sort hash matches above language ranking**

Edit `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`.

Find the comparator at lines 153-160 (the `compareBy<Pair<Subtitle, Int>>` block). Replace it with:

```kotlin
        .sortedWith(
            compareByDescending<Pair<Subtitle, Int>> { it.first.isHashMatch }
                .thenBy { it.second }
                .thenBy { PlayerSubtitleUtils.normalizeLanguageCode(it.first.lang) }
                .thenBy { it.first.addonName.lowercase() }
                .thenBy { it.first.id }
                .thenBy { it.first.url }
        )
```

This puts every hash-matched subtitle at the top of the list regardless of preferred-language rank, then preserves the existing tie-break order.

- [ ] **Step 7: Render the yellow star in `SubtitleDialog.kt`**

Edit `app/src/main/java/com/nexio/tv/ui/screens/player/SubtitleDialog.kt`.

Add the import (after line 23 `import androidx.compose.material.icons.filled.Check`):

```kotlin
import androidx.compose.material.icons.filled.Star
```

Replace the `Row` block at lines 644-672 with:

```kotlin
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (subtitle.isHashMatch) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Exact file match",
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = Subtitle.languageCodeToName(
                            PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
                Text(
                    text = subtitle.addonName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = NexioColors.Secondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
```

`#FFC107` is Material Amber 500 — it's the conventional "favorite/featured" yellow and reads cleanly against the dark Card background already in use at line 637.

- [ ] **Step 8: Build and run all tests**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:assembleArm64Debug :app:testArm64DebugUnitTest
```
Expected: BUILD SUCCESSFUL. The default `isHashMatch = false` in the data class keeps every existing `Subtitle(...)` constructor call valid; if any call uses positional arguments past `addonLogo`, the compiler will pinpoint it — fix by switching to named arguments.

- [ ] **Step 9: Manual smoke**

1. Install: `./gradlew :app:installArm64Debug`
2. Enable native OpenSubtitles in settings (from Task 13)
3. Play a movie via AIOStreams whose underlying file actually exists in OpenSubtitles' database (popular catalog titles work best — e.g. tt0111161)
4. Wait for the hash to compute (logcat: `adb logcat | grep -i "OpenSubtitles\|videoHash"`)
5. Open the subtitle dialog — at least one entry should display the amber star and appear at the top of the list

If no star ever appears: either (a) `OpenSubtitlesHasher` returned null because the upstream URL doesn't honor Range/Content-Length, or (b) OpenSubtitles has no hash-tagged subtitle for that file. Both are valid outcomes — the star is opportunistic, not guaranteed.

- [ ] **Step 10: Commit**

```bash
cd /Users/jneerdael/Scripts/nexio
git add app/src/main/java/com/nexio/tv/domain/model/Subtitle.kt \
        app/src/main/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImpl.kt \
        app/src/main/java/com/nexio/tv/ui/screens/player/SubtitleDialog.kt \
        app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt \
        app/src/test/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImplTest.kt
git commit -m "feat(opensubtitles): mark and surface oshash-matched subtitles with yellow star"
```

---

## Design notes & decisions captured here (why)

- **Scraping over API**: OpenSubtitles v1 REST requires an API key registered per app. `stremio-opensubtitles-pro` deliberately scrapes `www.opensubtitles.org/libs/suggest.php` and HTML search pages with a Mozilla UA to avoid credential distribution. We copy that strategy. If OS adds bot protection in future, an API-key-based alternative can replace `OpenSubtitlesScraperClient` without touching `OpenSubtitlesSource` callers.
- **Hash is advisory, not required**: `OpenSubtitlesHasher` returns `null` when Content-Length is absent or the file < 128 KB. The source still works (hash-less); quality degrades gracefully. The player-side code at `PlayerRuntimeControllerObservers.kt:50-55` already tolerates this.
- **AIOStreams is passthrough for hints**: As research confirmed, `transformers/stremio.ts:108-122` in `/Users/jneerdael/Scripts/AIOStreams` populates `behaviorHints.videoSize/filename/videoHash` from the upstream parsed stream. `StreamResponseDto` parses those fields. No changes to AIOStreams are required.
- **No Media3 custom proxy**: Extracting `.srt` to cache dir and returning a `file://` URL avoids running an in-app HTTP server (nanohttpd exists at `libs.versions.toml:27` but is in use elsewhere — not worth entangling).
- **Single OkHttp client dedicated to OpenSubtitles**: avoids polluting the global cache, sets its own UA default, and lets us tune timeouts independently without touching `provideOkHttpClient`.
- **Language filtering is permissive**: the current plan matches on 2-letter, 3-letter, and full names. The user-facing language list (preferred / secondary) already exists in `PlayerSubtitleUtils` and is applied downstream in `filterAndSortAddonSubtitlesForPreferences` (`PlayerRuntimeControllerObservers.kt:126-160`), so the native source returns all languages and lets the existing filter do its job.

## Out of scope (explicit)

- alass auto-sync (the stremio-opensubtitles-pro addon bundles an `alass` binary; porting that to Android requires bundling a native binary and is a separate effort)
- On-device caching of OpenSubtitles results beyond the cacheDir-backed `.srt` files (a HTTP-layer cache on `@Named("opensubtitles") OkHttpClient` would be a future optimization)
- `fps`, `downloads` exposure in the UI — Task 16 adds the `isHashMatch` star but does not surface other ranking signals; if stream-aware scoring is wanted, extend `Subtitle` in a follow-up
- Hash-match indication for subtitles returned by *external addons* (e.g. `opensubtitles-v3.strem.io`) — those addons return only `id/url/lang`, with no per-row hash signal, so the star can only be shown for the native source
- Migrating existing `opensubtitles-v3.strem.io` addon users away from the addon — both co-exist fine
