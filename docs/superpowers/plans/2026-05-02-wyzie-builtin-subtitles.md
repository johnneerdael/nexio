# Wyzie Built-In Subtitles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Wyzie Subs as a parallel built-in subtitle source alongside the existing Stremio addon-based subtitle path, with per-content-type source routing, BYO API key, and silent degrade on failure.

**Architecture:** A new `WyzieSubtitleIntegrationProvider` (Retrofit + IntegrationRuntime, modeled on `SubtitleSourceDownloadIntegrationProvider`) is wired as a second `async { }` lane inside `SubtitleRepositoryImpl`. Source selection is a pure `WyzieSourceRouter.sourcesFor(type, hints)` function. The Wyzie `id` parameter is derived from the existing `contentId` routing-prefix string (`tt…` → IMDB, `tmdb:N` → TMDB, `kitsu:N` → Kitsu marker for anime detection, etc.). Settings live in `WyzieSettingsDataStore` (Preferences DataStore) and are exposed in both the Android TV settings UI and `nexio-web` via the existing `AccountSettingsSyncService` bridge.

**Tech Stack:** Kotlin, Coroutines, Hilt, Retrofit + Moshi (project-standard JSON), DataStore Preferences, Jetpack Compose (TV), MockWebServer + MockK + JUnit4 for tests, Vue 3 / TypeScript for `nexio-web`.

**Reference spec:** `docs/superpowers/specs/2026-05-02-wyzie-builtin-subtitles-design.md`

---

## File Structure

### Create

| Path | Responsibility |
|---|---|
| `app/src/main/java/com/nexio/tv/domain/model/WyzieIdHints.kt` | Pure value object carrying imdb / tmdb / kitsu / mal / anilist / anidb hints. |
| `app/src/main/java/com/nexio/tv/domain/model/WyzieSource.kt` | Enum of 9 Wyzie sources + `apiName` + `displayName()`. |
| `app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieSourceRouter.kt` | Pure logic: `(ContentType, WyzieIdHints) → List<WyzieSource>`. |
| `app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieIdHintsParser.kt` | Pure logic: parses `contentId` routing prefix into `WyzieIdHints`. |
| `app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieResultMapper.kt` | DTO → `Subtitle` domain. Per-source `addonName` and `addonLogo`. |
| `app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieSubtitleIntegrationProvider.kt` | `IntegrationProvider` wrapping the runtime call. |
| `app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/transport/WyzieSubtitleApi.kt` | Retrofit interface (`@GET /search`). |
| `app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/transport/WyzieKeyInterceptor.kt` | OkHttp interceptor that appends `key=` to every request. |
| `app/src/main/java/com/nexio/tv/data/remote/dto/WyzieSubtitleDto.kt` | Moshi DTO for `/search` items, with custom `source` adapter (string OR list). |
| `app/src/main/java/com/nexio/tv/data/local/WyzieSettingsDataStore.kt` | DataStore for `apiKey` + `enabled`. |
| `app/src/main/java/com/nexio/tv/domain/model/WyzieSettings.kt` | Immutable value object for settings snapshot. |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/WyzieSubtitleSettingsViewModel.kt` | Hilt VM exposing `StateFlow<WyzieSettings>` + `onSetEnabled`/`onSetApiKey`. |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/WyzieSubtitleSettingsScreen.kt` | Compose TV screen. |
| `app/src/main/res/drawable/ic_wyzie.xml` | Generic Wyzie fallback icon (vector). |
| `app/src/main/res/drawable/ic_wyzie_opensubtitles.xml` | Per-source icon. |
| `app/src/main/res/drawable/ic_wyzie_subdl.xml` | Per-source icon. |
| `app/src/main/res/drawable/ic_wyzie_subf2m.xml` | Per-source icon. |
| `app/src/main/res/drawable/ic_wyzie_podnapisi.xml` | Per-source icon. |
| `app/src/main/res/drawable/ic_wyzie_gestdown.xml` | Per-source icon. |
| `app/src/main/res/drawable/ic_wyzie_animetosho.xml` | Per-source icon. |
| `app/src/main/res/drawable/ic_wyzie_jimaku.xml` | Per-source icon. |
| `app/src/main/res/drawable/ic_wyzie_kitsunekko.xml` | Per-source icon. |
| `app/src/main/res/drawable/ic_wyzie_ajatttools.xml` | Per-source icon. |
| `app/src/test/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieSourceRouterTest.kt` | Pure unit tests for routing logic. |
| `app/src/test/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieIdHintsParserTest.kt` | Pure unit tests for prefix parsing. |
| `app/src/test/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieResultMapperTest.kt` | Mapper tests (per-source labeling, fallback). |
| `app/src/test/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieSubtitleIntegrationProviderTest.kt` | Provider tests with MockWebServer + fake `IntegrationRuntime`. |
| `app/src/test/java/com/nexio/tv/data/remote/dto/WyzieSubtitleDtoTest.kt` | Moshi serialization round-trip tests. |
| `app/src/test/java/com/nexio/tv/data/local/WyzieSettingsDataStoreTest.kt` | Settings round-trip tests. |
| `nexio-web/components/integrations/WyzieSubtitlesPanel.vue` | Vue settings panel. |

### Modify

| Path | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/core/integration/IntegrationProvider.kt` | Add `WYZIE_SUBTITLES` enum value. |
| `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt` | Add `WYZIE_SEARCH = "wyzie.search"` to `SubtitleApiShapes`. |
| `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt` | Add `@Named("wyzie")` `OkHttpClient`, `Retrofit`, and `WyzieSubtitleApi` providers. |
| `app/src/main/java/com/nexio/tv/domain/repository/SubtitleRepository.kt` | Add `WyzieIdHints` parameter to `getSubtitles()`. |
| `app/src/main/java/com/nexio/tv/data/repository/SubtitleRepositoryImpl.kt` | Inject Wyzie provider + settings store; add second `async { }` lane. |
| `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt` | Build `WyzieIdHints` via parser, pass through to `getSubtitles()`. |
| `app/src/test/java/com/nexio/tv/data/repository/SubtitleRepositoryImplTest.kt` | Update existing tests to pass `WyzieIdHints`; add lane-merge tests. |
| `app/src/test/java/com/nexio/tv/data/repository/SubtitleRepositoryAddonRoutingTest.kt` | Same signature update. |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt` | Add navigation entry for Wyzie subtitle settings. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt` | Add `WyzieSyncSettings`; bump `ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION` 7 → 8. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | Push/pull Wyzie settings via the existing bridge. |
| `nexio-web/types/portal.ts` | Add `wyzie: { enabled: boolean; apiKey: string }` to `PortalIntegrations`. |

### Phases & ordering

1. **Phase 1 — Foundation** (Tasks 1-9): pure types, settings, transport, provider — no behavior change.
2. **Phase 2 — Repository wiring** (Tasks 10-12): second lane in repo + player call site.
3. **Phase 3 — Settings UI (Android TV)** (Tasks 13-15).
4. **Phase 4 — nexio-web sync bridge** (Tasks 16-18).
5. **Phase 5 — Manual smoke** (Task 19).

Each phase ends in a working app. Phase 1 can ship behind a "no key configured" no-op. Phase 2 activates the lane. Phase 3 makes the key user-settable on TV. Phase 4 makes the key settable on web.

---

## Phase 1 — Foundation

### Task 1: Add `WyzieIdHints` value object

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/WyzieIdHints.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Stable id hints used by the Wyzie subtitle integration.
 *
 * Wyzie's `/search` endpoint only accepts IMDB or TMDB ids (its anime sources resolve via TMDB
 * internally). `kitsu`/`mal`/`anilist`/`anidb` are carried so that [WyzieSourceRouter] can
 * detect anime content and select the anime source list — the values themselves are NOT sent
 * to Wyzie.
 *
 * `imdb` MUST preserve the `tt` prefix. `tmdb` is the integer TMDB id.
 */
@Immutable
data class WyzieIdHints(
    val imdb: String? = null,
    val tmdb: Int? = null,
    val kitsu: String? = null,
    val mal: String? = null,
    val anilist: String? = null,
    val anidb: String? = null,
) {
    val isAnime: Boolean
        get() = kitsu != null || mal != null || anilist != null || anidb != null

    val hasUsableWyzieId: Boolean
        get() = !imdb.isNullOrBlank() || tmdb != null

    companion object {
        val EMPTY = WyzieIdHints()
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/WyzieIdHints.kt
git commit -m "feat(wyzie): add WyzieIdHints domain model

Carrier for stable ids consumed by the upcoming Wyzie subtitle lane.
imdb/tmdb feed the request; kitsu/mal/anilist/anidb only flag anime
for source selection."
```

---

### Task 2: Add `WyzieSource` enum

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/WyzieSource.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.nexio.tv.domain.model

/**
 * The nine Wyzie subtitle sources Nexio routes to.
 *
 * `apiName` is the wire value sent in the `source=` query parameter to https://sub.wyzie.io/search.
 * `displayName` is the human-readable label rendered in the subtitle picker as "Wyzie · <displayName>".
 *
 * yify is intentionally absent — it returns SRT inside a ZIP archive, which Media3 cannot unwrap.
 */
enum class WyzieSource(val apiName: String, val displayName: String) {
    OPENSUBTITLES("opensubtitles", "OpenSubtitles"),
    SUBDL("subdl", "SubDL"),
    SUBF2M("subf2m", "Subf2m"),
    PODNAPISI("podnapisi", "Podnapisi"),
    GESTDOWN("gestdown", "Gestdown"),
    ANIMETOSHO("animetosho", "AnimeTosho"),
    JIMAKU("jimaku", "Jimaku"),
    KITSUNEKKO("kitsunekko", "Kitsunekko"),
    AJATTTOOLS("ajatttools", "AjattTools"),
    ;

    companion object {
        private val byApiName: Map<String, WyzieSource> = values().associateBy { it.apiName }

        fun fromApiNameOrNull(apiName: String?): WyzieSource? =
            apiName?.lowercase()?.let { byApiName[it] }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/WyzieSource.kt
git commit -m "feat(wyzie): add WyzieSource enum"
```

---

### Task 3: Add `WyzieSourceRouter` (TDD, pure logic)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieSourceRouter.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieSourceRouterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.WyzieIdHints
import com.nexio.tv.domain.model.WyzieSource
import org.junit.Assert.assertEquals
import org.junit.Test

class WyzieSourceRouterTest {

    private val nonAnime = WyzieIdHints(imdb = "tt0121955")

    @Test
    fun `non-anime movie returns curated movie source list`() {
        val sources = WyzieSourceRouter.sourcesFor(ContentType.MOVIE, nonAnime)
        assertEquals(
            listOf(
                WyzieSource.OPENSUBTITLES,
                WyzieSource.SUBDL,
                WyzieSource.SUBF2M,
                WyzieSource.PODNAPISI,
            ),
            sources,
        )
    }

    @Test
    fun `non-anime series returns curated tv source list with gestdown`() {
        val sources = WyzieSourceRouter.sourcesFor(ContentType.SERIES, nonAnime)
        assertEquals(
            listOf(
                WyzieSource.OPENSUBTITLES,
                WyzieSource.SUBDL,
                WyzieSource.SUBF2M,
                WyzieSource.PODNAPISI,
                WyzieSource.GESTDOWN,
            ),
            sources,
        )
    }

    @Test
    fun `tv content type aliases to series source list`() {
        val sources = WyzieSourceRouter.sourcesFor(ContentType.TV, nonAnime)
        assertEquals(
            WyzieSourceRouter.sourcesFor(ContentType.SERIES, nonAnime),
            sources,
        )
    }

    @Test
    fun `kitsu hint trips anime movie routing`() {
        val sources = WyzieSourceRouter.sourcesFor(
            ContentType.MOVIE,
            WyzieIdHints(kitsu = "42"),
        )
        assertEquals(listOf(WyzieSource.JIMAKU, WyzieSource.AJATTTOOLS), sources)
    }

    @Test
    fun `mal hint trips anime series routing`() {
        val sources = WyzieSourceRouter.sourcesFor(
            ContentType.SERIES,
            WyzieIdHints(mal = "1"),
        )
        assertEquals(
            listOf(
                WyzieSource.ANIMETOSHO,
                WyzieSource.JIMAKU,
                WyzieSource.KITSUNEKKO,
                WyzieSource.AJATTTOOLS,
            ),
            sources,
        )
    }

    @Test
    fun `anilist hint trips anime detection`() {
        assertEquals(
            true,
            WyzieSourceRouter.sourcesFor(ContentType.SERIES, WyzieIdHints(anilist = "5"))
                .first() == WyzieSource.ANIMETOSHO,
        )
    }

    @Test
    fun `anidb hint trips anime detection`() {
        assertEquals(
            true,
            WyzieSourceRouter.sourcesFor(ContentType.MOVIE, WyzieIdHints(anidb = "9"))
                .first() == WyzieSource.JIMAKU,
        )
    }

    @Test
    fun `anime tv aliases to series anime list`() {
        assertEquals(
            WyzieSourceRouter.sourcesFor(ContentType.SERIES, WyzieIdHints(kitsu = "1")),
            WyzieSourceRouter.sourcesFor(ContentType.TV, WyzieIdHints(kitsu = "1")),
        )
    }

    @Test
    fun `unknown content type returns empty`() {
        val sources = WyzieSourceRouter.sourcesFor(ContentType.UNKNOWN, nonAnime)
        assertEquals(emptyList<WyzieSource>(), sources)
    }

    @Test
    fun `channel content type returns empty`() {
        val sources = WyzieSourceRouter.sourcesFor(ContentType.CHANNEL, nonAnime)
        assertEquals(emptyList<WyzieSource>(), sources)
    }

    @Test
    fun `router does not check id presence — that boundary lives in the provider`() {
        // Hints with no usable id still get a source list; the provider is responsible
        // for skipping the network call.
        val sources = WyzieSourceRouter.sourcesFor(ContentType.MOVIE, WyzieIdHints.EMPTY)
        assertEquals(
            listOf(
                WyzieSource.OPENSUBTITLES,
                WyzieSource.SUBDL,
                WyzieSource.SUBF2M,
                WyzieSource.PODNAPISI,
            ),
            sources,
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.subtitles.wyzie.WyzieSourceRouterTest`
Expected: FAIL with "Unresolved reference: WyzieSourceRouter".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.WyzieIdHints
import com.nexio.tv.domain.model.WyzieSource

/**
 * Pure mapping from `(content type, hints)` to the curated Wyzie source list.
 *
 * Anime detection: any of `kitsu`/`mal`/`anilist`/`anidb` non-null. yify is intentionally
 * dropped from the movie list (SRT-in-ZIP, Media3 cannot unwrap).
 */
object WyzieSourceRouter {

    private val NON_ANIME_MOVIE = listOf(
        WyzieSource.OPENSUBTITLES,
        WyzieSource.SUBDL,
        WyzieSource.SUBF2M,
        WyzieSource.PODNAPISI,
    )

    private val NON_ANIME_TV = listOf(
        WyzieSource.OPENSUBTITLES,
        WyzieSource.SUBDL,
        WyzieSource.SUBF2M,
        WyzieSource.PODNAPISI,
        WyzieSource.GESTDOWN,
    )

    private val ANIME_MOVIE = listOf(
        WyzieSource.JIMAKU,
        WyzieSource.AJATTTOOLS,
    )

    private val ANIME_TV = listOf(
        WyzieSource.ANIMETOSHO,
        WyzieSource.JIMAKU,
        WyzieSource.KITSUNEKKO,
        WyzieSource.AJATTTOOLS,
    )

    fun sourcesFor(type: ContentType, hints: WyzieIdHints): List<WyzieSource> {
        val anime = hints.isAnime
        return when (type) {
            ContentType.MOVIE -> if (anime) ANIME_MOVIE else NON_ANIME_MOVIE
            ContentType.SERIES, ContentType.TV -> if (anime) ANIME_TV else NON_ANIME_TV
            ContentType.CHANNEL, ContentType.UNKNOWN -> emptyList()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.subtitles.wyzie.WyzieSourceRouterTest`
Expected: PASS — 10 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieSourceRouter.kt \
        app/src/test/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieSourceRouterTest.kt
git commit -m "feat(wyzie): add WyzieSourceRouter with curated per-type source lists"
```

---

### Task 4: Add `WyzieIdHintsParser` (TDD, pure logic)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieIdHintsParser.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieIdHintsParserTest.kt`

**Why this exists:** `PlayerNavigationArgs.contentId` is a single string in routing-prefix form (`tt12345`, `tmdb:9876`, `kitsu:42`, `tvdb:N`). `WyzieIdHints` needs the discrete fields. This parser is the only place that knows the prefix vocabulary.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.domain.model.WyzieIdHints
import org.junit.Assert.assertEquals
import org.junit.Test

class WyzieIdHintsParserTest {

    @Test
    fun `tt-prefixed id parses as imdb with prefix preserved`() {
        assertEquals(
            WyzieIdHints(imdb = "tt0121955"),
            WyzieIdHintsParser.parse("tt0121955"),
        )
    }

    @Test
    fun `tmdb-prefixed id parses as tmdb integer`() {
        assertEquals(
            WyzieIdHints(tmdb = 9876),
            WyzieIdHintsParser.parse("tmdb:9876"),
        )
    }

    @Test
    fun `tmdb prefix with non-numeric tail is dropped`() {
        assertEquals(WyzieIdHints.EMPTY, WyzieIdHintsParser.parse("tmdb:abc"))
    }

    @Test
    fun `kitsu-prefixed id parses as kitsu hint without imdb or tmdb`() {
        assertEquals(
            WyzieIdHints(kitsu = "42"),
            WyzieIdHintsParser.parse("kitsu:42"),
        )
    }

    @Test
    fun `mal-prefixed id parses as mal hint`() {
        assertEquals(
            WyzieIdHints(mal = "1"),
            WyzieIdHintsParser.parse("mal:1"),
        )
    }

    @Test
    fun `anilist-prefixed id parses as anilist hint`() {
        assertEquals(
            WyzieIdHints(anilist = "5"),
            WyzieIdHintsParser.parse("anilist:5"),
        )
    }

    @Test
    fun `anidb-prefixed id parses as anidb hint`() {
        assertEquals(
            WyzieIdHints(anidb = "9"),
            WyzieIdHintsParser.parse("anidb:9"),
        )
    }

    @Test
    fun `tvdb-prefixed id maps to no Wyzie-usable hint`() {
        // Wyzie has no TVDB lane and cannot route on TVDB ids. Yields empty hints
        // so the provider skips the call rather than guessing.
        assertEquals(WyzieIdHints.EMPTY, WyzieIdHintsParser.parse("tvdb:1234"))
    }

    @Test
    fun `unknown prefix returns empty hints`() {
        assertEquals(WyzieIdHints.EMPTY, WyzieIdHintsParser.parse("trakt:99"))
    }

    @Test
    fun `null contentId returns empty hints`() {
        assertEquals(WyzieIdHints.EMPTY, WyzieIdHintsParser.parse(null))
    }

    @Test
    fun `blank contentId returns empty hints`() {
        assertEquals(WyzieIdHints.EMPTY, WyzieIdHintsParser.parse("   "))
    }

    @Test
    fun `tt prefix is matched case-insensitively for safety`() {
        assertEquals(
            WyzieIdHints(imdb = "tt9999999"),
            WyzieIdHintsParser.parse("TT9999999"),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.subtitles.wyzie.WyzieIdHintsParserTest`
Expected: FAIL with "Unresolved reference: WyzieIdHintsParser".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.domain.model.WyzieIdHints

/**
 * Derives [WyzieIdHints] from the player's `contentId` routing string.
 *
 * Routing-prefix vocabulary observed in this codebase
 * (see [com.nexio.tv.domain.model.RailItemPreview.bestSupportedRoutingId]):
 *   - `tt…`        — IMDB id (no prefix separator; preserved verbatim including `tt`)
 *   - `tmdb:N`     — TMDB id (numeric tail)
 *   - `kitsu:N`    — Kitsu id (anime detector; not sent to Wyzie)
 *   - `mal:N`      — MyAnimeList id (anime detector)
 *   - `anilist:N`  — AniList id (anime detector)
 *   - `anidb:N`    — AniDB id (anime detector)
 *   - `tvdb:N`     — TheTVDB id (Wyzie has no TVDB lane → skipped)
 *
 * Unknown prefixes and null/blank input yield [WyzieIdHints.EMPTY].
 */
object WyzieIdHintsParser {

    fun parse(contentId: String?): WyzieIdHints {
        val trimmed = contentId?.trim().orEmpty()
        if (trimmed.isEmpty()) return WyzieIdHints.EMPTY

        if (trimmed.startsWith("tt", ignoreCase = true)) {
            // Preserve original casing for the digits but normalize the "tt" prefix to lower.
            return WyzieIdHints(imdb = "tt" + trimmed.substring(2))
        }

        val colon = trimmed.indexOf(':')
        if (colon <= 0 || colon == trimmed.lastIndex) return WyzieIdHints.EMPTY
        val namespace = trimmed.substring(0, colon).lowercase()
        val value = trimmed.substring(colon + 1)

        return when (namespace) {
            "tmdb" -> value.toIntOrNull()?.let { WyzieIdHints(tmdb = it) } ?: WyzieIdHints.EMPTY
            "kitsu" -> WyzieIdHints(kitsu = value)
            "mal" -> WyzieIdHints(mal = value)
            "anilist" -> WyzieIdHints(anilist = value)
            "anidb" -> WyzieIdHints(anidb = value)
            else -> WyzieIdHints.EMPTY
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.subtitles.wyzie.WyzieIdHintsParserTest`
Expected: PASS — 12 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieIdHintsParser.kt \
        app/src/test/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieIdHintsParserTest.kt
git commit -m "feat(wyzie): add routing-prefix to WyzieIdHints parser"
```

---

### Task 5: Add `WyzieSubtitleDto` with custom source adapter (TDD)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/remote/dto/WyzieSubtitleDto.kt`
- Test: `app/src/test/java/com/nexio/tv/data/remote/dto/WyzieSubtitleDtoTest.kt`

**Why a custom adapter:** Wyzie's `SubtitleData.source` is typed `string | string[]` in the NPM types — both shapes appear in real responses. We normalize to a single nullable string at the DTO boundary.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.remote.dto

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WyzieSubtitleDtoTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(WyzieSourceJsonAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(WyzieSubtitleDto::class.java)
    private val listAdapter = moshi.adapter<List<WyzieSubtitleDto>>(
        com.squareup.moshi.Types.newParameterizedType(List::class.java, WyzieSubtitleDto::class.java)
    )

    @Test
    fun `parses single source string`() {
        val json = """
            {
              "id":"1955024019",
              "url":"https://sub.wyzie.io/c/198e0c4d/id/1955024019?format=srt",
              "format":"srt",
              "encoding":"UTF-8",
              "display":"English",
              "language":"en",
              "media":"The Martian",
              "isHearingImpaired":false,
              "source":"opensubtitles",
              "release":"The.Martian.2015.1080p.WEB-DL",
              "fileName":"the.martian.2015.1080p.web-dl.srt"
            }
        """.trimIndent()

        val dto = adapter.fromJson(json)!!
        assertEquals("1955024019", dto.id)
        assertEquals("opensubtitles", dto.source)
        assertEquals("en", dto.language)
        assertEquals(false, dto.isHearingImpaired)
    }

    @Test
    fun `parses source as JSON array using first element`() {
        val json = """
            {
              "id":"x",
              "url":"https://example/sub.srt",
              "format":"srt",
              "encoding":"UTF-8",
              "display":"English",
              "language":"en",
              "media":"X",
              "isHearingImpaired":false,
              "source":["opensubtitles","subdl"]
            }
        """.trimIndent()

        val dto = adapter.fromJson(json)!!
        assertEquals("opensubtitles", dto.source)
    }

    @Test
    fun `parses null source as null`() {
        val json = """
            {
              "id":"x",
              "url":"https://example/sub.srt",
              "format":"srt",
              "encoding":"UTF-8",
              "display":"English",
              "language":"en",
              "media":"X",
              "isHearingImpaired":false,
              "source":null
            }
        """.trimIndent()

        val dto = adapter.fromJson(json)!!
        assertNull(dto.source)
    }

    @Test
    fun `parses missing source field as null`() {
        val json = """
            {
              "id":"x",
              "url":"https://example/sub.srt",
              "format":"srt",
              "encoding":"UTF-8",
              "display":"English",
              "language":"en",
              "media":"X",
              "isHearingImpaired":false
            }
        """.trimIndent()

        val dto = adapter.fromJson(json)!!
        assertNull(dto.source)
    }

    @Test
    fun `parses missing optional fields without crashing`() {
        val json = """
            {
              "id":"x",
              "url":"https://example/sub.srt",
              "format":"srt",
              "encoding":"UTF-8",
              "display":"English",
              "language":"en",
              "media":"X",
              "isHearingImpaired":true,
              "source":"podnapisi"
            }
        """.trimIndent()

        val dto = adapter.fromJson(json)!!
        assertNull(dto.release)
        assertNull(dto.releases)
        assertNull(dto.fileName)
        assertNull(dto.downloadCount)
        assertNull(dto.origin)
        assertNull(dto.matchedRelease)
        assertNull(dto.matchedFilter)
        assertEquals(true, dto.isHearingImpaired)
    }

    @Test
    fun `parses an empty array as empty list`() {
        val list = listAdapter.fromJson("[]")!!
        assertTrue(list.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.remote.dto.WyzieSubtitleDtoTest`
Expected: FAIL with "Unresolved reference: WyzieSubtitleDto" / "WyzieSourceJsonAdapter".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.nexio.tv.data.remote.dto

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.Types

/**
 * Moshi DTO mirroring https://docs.wyzie.io `/search` response items.
 *
 * `source` is normalized to a single nullable string by [WyzieSourceJsonAdapter] (Wyzie returns
 * either a string or an array). All other optional fields are tolerant of null/missing.
 */
// Reflective Moshi adapter is used; no @JsonClass(generateAdapter = true)
// because this project does not run the moshi-kotlin-codegen kapt processor.
data class WyzieSubtitleDto(
    val id: String,
    val url: String,
    val format: String,
    val encoding: String?,
    val display: String?,
    val language: String,
    val media: String?,
    val isHearingImpaired: Boolean = false,
    val flagUrl: String? = null,
    @WyzieSourceField val source: String? = null,
    val release: String? = null,
    val releases: List<String>? = null,
    val fileName: String? = null,
    val downloadCount: Int? = null,
    val origin: String? = null,
    val matchedRelease: String? = null,
    val matchedFilter: String? = null,
)

/** Marker used to route the field through [WyzieSourceJsonAdapter]. */
@JsonQualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class WyzieSourceField

/**
 * Adapter that accepts `null`, a string, or a JSON array of strings (taking the first entry).
 */
class WyzieSourceJsonAdapter {
    @FromJson
    @WyzieSourceField
    fun fromJson(reader: JsonReader): String? {
        return when (reader.peek()) {
            JsonReader.Token.NULL -> reader.nextNull<String>()
            JsonReader.Token.STRING -> reader.nextString()
            JsonReader.Token.BEGIN_ARRAY -> {
                reader.beginArray()
                var first: String? = null
                while (reader.hasNext()) {
                    val v = if (reader.peek() == JsonReader.Token.STRING) reader.nextString() else {
                        reader.skipValue(); null
                    }
                    if (first == null && v != null) first = v
                }
                reader.endArray()
                first
            }
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    @ToJson
    fun toJson(writer: JsonWriter, @WyzieSourceField value: String?) {
        if (value == null) writer.nullValue() else writer.value(value)
    }
}
```

No additional imports beyond what's shown above.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.remote.dto.WyzieSubtitleDtoTest`
Expected: PASS — 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/dto/WyzieSubtitleDto.kt \
        app/src/test/java/com/nexio/tv/data/remote/dto/WyzieSubtitleDtoTest.kt
git commit -m "feat(wyzie): add WyzieSubtitleDto with string-or-array source adapter"
```

---

### Task 6: Add `WyzieResultMapper` (TDD)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieResultMapper.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieResultMapperTest.kt`

**Note on logos:** The mapper returns the URI of a per-source vector drawable as a string. Drawables are added in Task 9; for the test we use a string-equality check on the `android.resource://` URI form, which is computed from a constant resource id.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.data.remote.dto.WyzieSubtitleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WyzieResultMapperTest {

    private fun dto(
        id: String = "abc",
        url: String = "https://sub.wyzie.io/c/x/id/abc",
        language: String = "en",
        source: String? = null,
        format: String = "srt",
    ) = WyzieSubtitleDto(
        id = id,
        url = url,
        format = format,
        encoding = "UTF-8",
        display = "English",
        language = language,
        media = "Test",
        isHearingImpaired = false,
        source = source,
    )

    @Test
    fun `known source maps to per-source label`() {
        val sub = WyzieResultMapper.map(dto(source = "subdl"))
        assertEquals("Wyzie · SubDL", sub.addonName)
        assertEquals("wyzie:abc", sub.id)
        assertEquals("https://sub.wyzie.io/c/x/id/abc", sub.url)
        assertEquals("en", sub.lang)
    }

    @Test
    fun `null source maps to plain Wyzie label`() {
        val sub = WyzieResultMapper.map(dto(source = null))
        assertEquals("Wyzie", sub.addonName)
    }

    @Test
    fun `unknown source maps to Wyzie · raw value`() {
        val sub = WyzieResultMapper.map(dto(source = "newsource"))
        assertEquals("Wyzie · newsource", sub.addonName)
    }

    @Test
    fun `each known source has a non-null logo uri`() {
        com.nexio.tv.domain.model.WyzieSource.values().forEach { src ->
            val sub = WyzieResultMapper.map(dto(source = src.apiName))
            assertTrue(
                "Logo missing for ${src.apiName}",
                sub.addonLogo?.startsWith("android.resource://") == true,
            )
        }
    }

    @Test
    fun `unknown source falls back to generic logo uri`() {
        val sub = WyzieResultMapper.map(dto(source = "newsource"))
        assertTrue(sub.addonLogo?.startsWith("android.resource://") == true)
    }

    @Test
    fun `null source uses generic logo uri`() {
        val sub = WyzieResultMapper.map(dto(source = null))
        assertTrue(sub.addonLogo?.startsWith("android.resource://") == true)
    }

    @Test
    fun `id is namespaced with wyzie prefix`() {
        val sub = WyzieResultMapper.map(dto(id = "12345"))
        assertEquals("wyzie:12345", sub.id)
    }

    @Test
    fun `lang passes through verbatim`() {
        val sub = WyzieResultMapper.map(dto(language = "pt-BR"))
        assertEquals("pt-BR", sub.lang)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.subtitles.wyzie.WyzieResultMapperTest`
Expected: FAIL with "Unresolved reference: WyzieResultMapper".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.R
import com.nexio.tv.data.remote.dto.WyzieSubtitleDto
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.model.WyzieSource

/**
 * Maps a [WyzieSubtitleDto] to the domain [Subtitle] type.
 *
 * - `addonName`  = "Wyzie" or "Wyzie · <displayName|raw>"
 * - `addonLogo`  = `android.resource://com.nexio.tv/<drawable-id>` for the matched source,
 *                  or the generic Wyzie icon otherwise.
 * - `id`         = `wyzie:<dto.id>` (namespaced to avoid collision with addon ids).
 *
 * The mapper is stateless; resource ids are looked up by [WyzieSource], so there is no
 * Context/Resources dependency at unit-test time.
 */
object WyzieResultMapper {

    private const val PACKAGE = "com.nexio.tv"

    private fun resourceUri(resId: Int): String =
        "android.resource://$PACKAGE/$resId"

    private fun logoFor(source: WyzieSource?): String = when (source) {
        WyzieSource.OPENSUBTITLES -> resourceUri(R.drawable.ic_wyzie_opensubtitles)
        WyzieSource.SUBDL -> resourceUri(R.drawable.ic_wyzie_subdl)
        WyzieSource.SUBF2M -> resourceUri(R.drawable.ic_wyzie_subf2m)
        WyzieSource.PODNAPISI -> resourceUri(R.drawable.ic_wyzie_podnapisi)
        WyzieSource.GESTDOWN -> resourceUri(R.drawable.ic_wyzie_gestdown)
        WyzieSource.ANIMETOSHO -> resourceUri(R.drawable.ic_wyzie_animetosho)
        WyzieSource.JIMAKU -> resourceUri(R.drawable.ic_wyzie_jimaku)
        WyzieSource.KITSUNEKKO -> resourceUri(R.drawable.ic_wyzie_kitsunekko)
        WyzieSource.AJATTTOOLS -> resourceUri(R.drawable.ic_wyzie_ajatttools)
        null -> resourceUri(R.drawable.ic_wyzie)
    }

    fun map(dto: WyzieSubtitleDto): Subtitle {
        val matched = WyzieSource.fromApiNameOrNull(dto.source)
        val addonName = when {
            matched != null -> "Wyzie · ${matched.displayName}"
            dto.source.isNullOrBlank() -> "Wyzie"
            else -> "Wyzie · ${dto.source}"
        }
        return Subtitle(
            id = "wyzie:${dto.id}",
            url = dto.url,
            lang = dto.language,
            addonName = addonName,
            addonLogo = logoFor(matched),
        )
    }

    fun mapAll(dtos: List<WyzieSubtitleDto>): List<Subtitle> = dtos.map(::map)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.subtitles.wyzie.WyzieResultMapperTest`
Expected: This will FAIL during compilation because the drawable resources don't exist yet. Continue to step 5; do not commit yet.

- [ ] **Step 5: Add placeholder drawables to satisfy R-class generation**

Create 10 minimal vector drawables. Each file follows the same template (only the `android:name` value changes). Use the actual final assets later — these are placeholders that compile.

Create `app/src/main/res/drawable/ic_wyzie.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:name="wyzie_generic"
        android:fillColor="#7C4DFF"
        android:pathData="M12,2L2,7l10,5l10,-5L12,2zM2,17l10,5l10,-5M2,12l10,5l10,-5"/>
</vector>
```

Then create the 9 per-source variants. Each is the same shape, different `android:name` and `android:fillColor`:

| File | name | fillColor |
|---|---|---|
| `ic_wyzie_opensubtitles.xml` | `wyzie_opensubtitles` | `#5B8DEF` |
| `ic_wyzie_subdl.xml` | `wyzie_subdl` | `#FF7043` |
| `ic_wyzie_subf2m.xml` | `wyzie_subf2m` | `#26A69A` |
| `ic_wyzie_podnapisi.xml` | `wyzie_podnapisi` | `#AB47BC` |
| `ic_wyzie_gestdown.xml` | `wyzie_gestdown` | `#66BB6A` |
| `ic_wyzie_animetosho.xml` | `wyzie_animetosho` | `#EF5350` |
| `ic_wyzie_jimaku.xml` | `wyzie_jimaku` | `#FFCA28` |
| `ic_wyzie_kitsunekko.xml` | `wyzie_kitsunekko` | `#EC407A` |
| `ic_wyzie_ajatttools.xml` | `wyzie_ajatttools` | `#42A5F5` |

Body for each (substitute the values from the row):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:name="<NAME>"
        android:fillColor="<COLOR>"
        android:pathData="M12,2L2,7l10,5l10,-5L12,2zM2,17l10,5l10,-5M2,12l10,5l10,-5"/>
</vector>
```

- [ ] **Step 6: Re-run mapper test**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.subtitles.wyzie.WyzieResultMapperTest`
Expected: PASS — 8 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieResultMapper.kt \
        app/src/test/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieResultMapperTest.kt \
        app/src/main/res/drawable/ic_wyzie*.xml
git commit -m "feat(wyzie): add WyzieResultMapper with per-source labeling and icons"
```

---

### Task 7: Add `WyzieSettings` and `WyzieSettingsDataStore` (TDD)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/WyzieSettings.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/WyzieSettingsDataStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/WyzieSettingsDataStoreTest.kt`

- [ ] **Step 1: Write the value object**

```kotlin
package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Snapshot of the user's Wyzie subtitle preferences.
 *
 * `apiKey == null` (or blank) means "no key set"; the Wyzie lane is silently skipped.
 * `enabled == false` means the user explicitly disabled Wyzie even if a key exists.
 */
@Immutable
data class WyzieSettings(
    val apiKey: String? = null,
    val enabled: Boolean = true,
) {
    val isUsable: Boolean
        get() = enabled && !apiKey.isNullOrBlank()

    companion object {
        val DEFAULT = WyzieSettings()
    }
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.nexio.tv.data.local

import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.domain.model.WyzieSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WyzieSettingsDataStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        // Wipe the underlying preferences file between tests for isolation.
        File(context.filesDir, "datastore/wyzie_settings.preferences_pb").delete()
    }

    @Test
    fun defaultsAreEnabledWithoutKey() = runTest {
        val store = WyzieSettingsDataStore(context)
        val s = store.settings.first()
        assertEquals(WyzieSettings(apiKey = null, enabled = true), s)
    }

    @Test
    fun setApiKeyTrimsWhitespace() = runTest {
        val store = WyzieSettingsDataStore(context)
        store.setApiKey("  wyzie-abc123xyz  ")
        assertEquals("wyzie-abc123xyz", store.settings.first().apiKey)
    }

    @Test
    fun setApiKeyToBlankClearsTheKey() = runTest {
        val store = WyzieSettingsDataStore(context)
        store.setApiKey("wyzie-abc")
        store.setApiKey("   ")
        // Blank reads back as null so isUsable is false.
        assertEquals(null, store.settings.first().apiKey)
    }

    @Test
    fun setEnabledRoundTrips() = runTest {
        val store = WyzieSettingsDataStore(context)
        store.setEnabled(false)
        assertEquals(false, store.settings.first().enabled)
        store.setEnabled(true)
        assertEquals(true, store.settings.first().enabled)
    }

    @Test
    fun isUsableRequiresKeyAndEnabled() = runTest {
        val store = WyzieSettingsDataStore(context)
        assertEquals(false, store.settings.first().isUsable)
        store.setApiKey("wyzie-abc")
        assertEquals(true, store.settings.first().isUsable)
        store.setEnabled(false)
        assertEquals(false, store.settings.first().isUsable)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.WyzieSettingsDataStoreTest`
Expected: FAIL — "Unresolved reference: WyzieSettingsDataStore".

- [ ] **Step 4: Write the DataStore**

```kotlin
package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.domain.model.WyzieSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.wyzieSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wyzie_settings"
)

@Singleton
class WyzieSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.wyzieSettingsDataStore

    private val keyApi = stringPreferencesKey("wyzie_api_key")
    private val keyEnabled = booleanPreferencesKey("wyzie_enabled")

    val settings: Flow<WyzieSettings> = store.data.map { prefs ->
        WyzieSettings(
            apiKey = prefs[keyApi]?.takeIf { it.isNotBlank() },
            enabled = prefs[keyEnabled] ?: true,
        )
    }

    suspend fun setApiKey(value: String) {
        store.edit { prefs ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                prefs.remove(keyApi)
            } else {
                prefs[keyApi] = trimmed
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { prefs ->
            prefs[keyEnabled] = enabled
        }
    }
}
```

- [ ] **Step 5: Re-run test**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.WyzieSettingsDataStoreTest`
Expected: PASS — 5 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/WyzieSettings.kt \
        app/src/main/java/com/nexio/tv/data/local/WyzieSettingsDataStore.kt \
        app/src/test/java/com/nexio/tv/data/local/WyzieSettingsDataStoreTest.kt
git commit -m "feat(wyzie): add WyzieSettingsDataStore for api key + enabled flag"
```

---

### Task 8: Register Wyzie integration provider id and api shape

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`

- [ ] **Step 1: Add `WYZIE_SUBTITLES` to `IntegrationProvider`**

Replace lines 26-28 (`SUBTITLE_SOURCE_DOWNLOAD,\n    SUBTITLE_TRANSLATION\n}`) with:

```kotlin
    SUBTITLE_SOURCE_DOWNLOAD,
    SUBTITLE_TRANSLATION,
    WYZIE_SUBTITLES
}
```

- [ ] **Step 2: Add the api shape constant**

In `IntegrationApiShapes.kt`, find the `SubtitleApiShapes` object (lines 115-118) and replace it with:

```kotlin
object SubtitleApiShapes {
    const val SOURCE_DOWNLOAD = "subtitle.source_download"
    const val TRANSLATION = "subtitle.translation"
    const val WYZIE_SEARCH = "wyzie.search"
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationProvider.kt \
        app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt
git commit -m "feat(wyzie): register WYZIE_SUBTITLES provider and wyzie.search shape"
```

---

### Task 9: Add `WyzieSubtitleApi`, `WyzieKeyInterceptor`, and Hilt providers

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/transport/WyzieSubtitleApi.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/transport/WyzieKeyInterceptor.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`

- [ ] **Step 1: Write the Retrofit interface**

```kotlin
package com.nexio.tv.data.integration.subtitles.wyzie.transport

import com.nexio.tv.data.remote.dto.WyzieSubtitleDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit binding for https://sub.wyzie.io.
 *
 * Only `/search` is wired; `/sources` is not used (per design Q3 the source list is hardcoded
 * client-side and we don't need upstream availability data).
 */
interface WyzieSubtitleApi {

    @GET("search")
    suspend fun search(
        @Query("id") id: String,
        @Query("source") source: String,
        @Query("format") format: String = "srt,ass,vtt",
        @Query("season") season: Int? = null,
        @Query("episode") episode: Int? = null,
    ): Response<List<WyzieSubtitleDto>>
}
```

- [ ] **Step 2: Write the key interceptor**

```kotlin
package com.nexio.tv.data.integration.subtitles.wyzie.transport

import com.nexio.tv.data.local.WyzieSettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Appends the Wyzie API key to every outgoing query.
 *
 * If the key is blank/null at request time the interceptor short-circuits with a synthetic
 * 401 response so the calling provider's HTTP-error path runs naturally. In practice the
 * repository skips the call entirely when the key is absent (silent degrade); this interceptor
 * is the safety net for direct/test invocations.
 */
@Singleton
class WyzieKeyInterceptor @Inject constructor(
    private val settingsDataStore: WyzieSettingsDataStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = runBlocking { settingsDataStore.settings.first().apiKey }
        if (key.isNullOrBlank()) {
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Wyzie key not configured")
                .body("".toResponseBody(null))
                .build()
        }
        val newUrl = request.url.newBuilder().addQueryParameter("key", key).build()
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
```

- [ ] **Step 3: Add Hilt providers in `NetworkModule.kt`**

Append the following providers near the other named OkHttp/Retrofit providers (after the `provideTrailerApi` block around line 537):

```kotlin
    // --- Wyzie subtitles ---

    @Provides
    @Singleton
    @Named("wyzie")
    fun provideWyzieOkHttpClient(
        okHttpClient: OkHttpClient,
        wyzieKeyInterceptor: com.nexio.tv.data.integration.subtitles.wyzie.transport.WyzieKeyInterceptor,
    ): OkHttpClient {
        return okHttpClient.newBuilder()
            .addInterceptor(wyzieKeyInterceptor)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("wyzie")
    fun provideWyzieRetrofit(
        @Named("wyzie") okHttpClient: OkHttpClient,
        moshi: Moshi,
    ): Retrofit {
        // Add the source string-or-array adapter to a per-call Moshi instance so it's not
        // shared with other Retrofit clients that don't expect it.
        val wyzieMoshi = moshi.newBuilder()
            .add(com.nexio.tv.data.remote.dto.WyzieSourceJsonAdapter())
            .build()
        return Retrofit.Builder()
            .baseUrl("https://sub.wyzie.io/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(wyzieMoshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideWyzieSubtitleApi(
        @Named("wyzie") retrofit: Retrofit,
    ): com.nexio.tv.data.integration.subtitles.wyzie.transport.WyzieSubtitleApi =
        retrofit.create(com.nexio.tv.data.integration.subtitles.wyzie.transport.WyzieSubtitleApi::class.java)
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/transport/WyzieSubtitleApi.kt \
        app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/transport/WyzieKeyInterceptor.kt \
        app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt
git commit -m "feat(wyzie): add Retrofit api, key interceptor, and Hilt wiring"
```

---

### Task 10: Add `WyzieSubtitleIntegrationProvider` (TDD)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieSubtitleIntegrationProvider.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieSubtitleIntegrationProviderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.SubtitleApiShapes
import com.nexio.tv.data.integration.subtitles.wyzie.transport.WyzieSubtitleApi
import com.nexio.tv.data.local.WyzieSettingsDataStore
import com.nexio.tv.data.remote.dto.WyzieSubtitleDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.WyzieIdHints
import com.nexio.tv.domain.model.WyzieSettings
import com.nexio.tv.domain.model.WyzieSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class WyzieSubtitleIntegrationProviderTest {

    private fun makeProvider(
        settings: WyzieSettings = WyzieSettings(apiKey = "k", enabled = true),
        api: WyzieSubtitleApi = mockk(),
        runtime: IntegrationRuntime = passthroughRuntime(),
    ): WyzieSubtitleIntegrationProvider {
        val store: WyzieSettingsDataStore = mockk()
        coEvery { store.settings } returns flowOf(settings)
        return WyzieSubtitleIntegrationProvider(runtime, api, store)
    }

    private fun passthroughRuntime(): IntegrationRuntime = object : IntegrationRuntime {
        override suspend fun <T> get(
            spec: com.nexio.tv.core.integration.IntegrationSpec<T>,
            options: com.nexio.tv.core.integration.IntegrationFetchOptions,
        ): com.nexio.tv.core.integration.IntegrationFetchResult<T> =
            throw UnsupportedOperationException("get() not used by Wyzie path")

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            spec.call.invoke()

        override suspend fun <T> open(
            spec: com.nexio.tv.core.integration.IntegrationStreamSpec<T>,
        ): com.nexio.tv.core.integration.IntegrationStreamHandle<T>? =
            throw UnsupportedOperationException("open() not used by Wyzie path")
    }

    @Test
    fun `skips when settings disabled`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        val provider = makeProvider(settings = WyzieSettings(apiKey = "k", enabled = false), api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt0121955"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
        coVerify(exactly = 0) { api.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips when api key blank`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        val provider = makeProvider(settings = WyzieSettings(apiKey = null, enabled = true), api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt0121955"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
        coVerify(exactly = 0) { api.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips when no usable id (no imdb and no tmdb)`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(kitsu = "42"),
            sources = listOf(WyzieSource.JIMAKU),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
        coVerify(exactly = 0) { api.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips when source list empty`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = emptyList(),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
        coVerify(exactly = 0) { api.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips on partial season-episode pair (season without episode)`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.SERIES,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = 1, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
        coVerify(exactly = 0) { api.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `prefers imdb id over tmdb when both present`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val provider = makeProvider(api = api)

        provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt0121955", tmdb = 9876),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )

        coVerify(exactly = 1) {
            api.search(id = "tt0121955", source = "opensubtitles", format = "srt,ass,vtt", season = null, episode = null)
        }
    }

    @Test
    fun `falls back to tmdb id when imdb absent`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val provider = makeProvider(api = api)

        provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(tmdb = 9876),
            sources = listOf(WyzieSource.OPENSUBTITLES, WyzieSource.SUBDL),
            season = null, episode = null,
        )

        coVerify(exactly = 1) {
            api.search(id = "9876", source = "opensubtitles,subdl", format = "srt,ass,vtt", season = null, episode = null)
        }
    }

    @Test
    fun `joins source list with commas`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val provider = makeProvider(api = api)

        provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(
                WyzieSource.OPENSUBTITLES, WyzieSource.SUBDL, WyzieSource.SUBF2M, WyzieSource.PODNAPISI
            ),
            season = null, episode = null,
        )
        coVerify(exactly = 1) {
            api.search(id = any(), source = "opensubtitles,subdl,subf2m,podnapisi", format = any(), season = any(), episode = any())
        }
    }

    @Test
    fun `passes season and episode when both present`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val provider = makeProvider(api = api)

        provider.search(
            type = ContentType.SERIES,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = 1, episode = 2,
        )
        coVerify(exactly = 1) {
            api.search(id = any(), source = any(), format = any(), season = 1, episode = 2)
        }
    }

    @Test
    fun `maps 401 to empty list`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns
            Response.error(401, okhttp3.ResponseBody.Companion.create(null, "auth"))
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
    }

    @Test
    fun `maps 500 to empty list`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns
            Response.error(500, okhttp3.ResponseBody.Companion.create(null, "boom"))
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
    }

    @Test
    fun `network exception yields empty list`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } throws java.io.IOException("offline")
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
    }

    @Test
    fun `cancellation re-throws`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } throws CancellationException("nope")
        val provider = makeProvider(api = api)

        try {
            provider.search(
                type = ContentType.MOVIE,
                hints = WyzieIdHints(imdb = "tt1"),
                sources = listOf(WyzieSource.OPENSUBTITLES),
                season = null, episode = null,
            )
            throw AssertionError("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("nope", e.message)
        }
    }

    @Test
    fun `successful response is returned as-is`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        val dto = WyzieSubtitleDto(
            id = "1", url = "u", format = "srt", encoding = "UTF-8",
            display = "English", language = "en", media = "X",
            isHearingImpaired = false, source = "opensubtitles",
        )
        coEvery { api.search(any(), any(), any(), any(), any()) } returns Response.success(listOf(dto))
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(listOf(dto), result)
    }

    @Test
    fun `runtime spec uses WYZIE_SUBTITLES provider and wyzie_search shape`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val runtime: IntegrationRuntime = mockk()
        val capturedSpec = slot<IntegrationCallSpec<List<WyzieSubtitleDto>>>()
        coEvery { runtime.call(capture(capturedSpec)) } coAnswers {
            capturedSpec.captured.call.invoke()
        }
        val provider = makeProvider(api = api, runtime = runtime)

        provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(IntegrationProvider.WYZIE_SUBTITLES, capturedSpec.captured.provider)
        assertEquals(SubtitleApiShapes.WYZIE_SEARCH, capturedSpec.captured.apiShapeId)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, capturedSpec.captured.workClass)
        assertTrue(capturedSpec.captured.operationKey.startsWith("wyzie."))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProviderTest`
Expected: FAIL — "Unresolved reference: WyzieSubtitleIntegrationProvider".

- [ ] **Step 3: Write the provider**

```kotlin
package com.nexio.tv.data.integration.subtitles.wyzie

import android.util.Log
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.SubtitleApiShapes
import com.nexio.tv.data.integration.subtitles.wyzie.transport.WyzieSubtitleApi
import com.nexio.tv.data.local.WyzieSettingsDataStore
import com.nexio.tv.data.remote.dto.WyzieSubtitleDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.WyzieIdHints
import com.nexio.tv.domain.model.WyzieSource
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "WyzieSubtitleIntegrationProvider"
private const val LOG_PREFIX = "WYZIE_SUBS"

/**
 * Calls https://sub.wyzie.io/search through the IntegrationRuntime.
 *
 * Returns an empty list (silent degrade) for any failure or skip condition. Re-throws
 * [CancellationException] so the caller's coroutine cancellation propagates correctly.
 */
@Singleton
class WyzieSubtitleIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val api: WyzieSubtitleApi,
    private val settingsDataStore: WyzieSettingsDataStore,
) {

    suspend fun search(
        type: ContentType,
        hints: WyzieIdHints,
        sources: List<WyzieSource>,
        season: Int?,
        episode: Int?,
    ): List<WyzieSubtitleDto> {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) {
            return emptyList() // No log per spec table for `enabled == false`.
        }
        if (settings.apiKey.isNullOrBlank()) {
            Log.d(TAG, "$LOG_PREFIX skipped: no key")
            return emptyList()
        }
        if (sources.isEmpty()) {
            Log.d(TAG, "$LOG_PREFIX skipped: no sources for type=$type anime=${hints.isAnime}")
            return emptyList()
        }
        if (!hints.hasUsableWyzieId) {
            Log.d(TAG, "$LOG_PREFIX skipped: no usable id for type=$type")
            return emptyList()
        }
        if ((season == null) != (episode == null)) {
            Log.d(TAG, "$LOG_PREFIX skipped: season/episode partial")
            return emptyList()
        }

        val id = hints.imdb ?: hints.tmdb!!.toString()
        val sourceParam = sources.joinToString(",") { it.apiName }

        val callResult = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.WYZIE_SUBTITLES,
                apiShapeId = SubtitleApiShapes.WYZIE_SEARCH,
                operationKey = "wyzie.search",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("wyzie:subtitles"),
                call = {
                    val outcome: IntegrationCallResult<List<WyzieSubtitleDto>> = try {
                        val started = System.currentTimeMillis()
                        val response = api.search(
                            id = id,
                            source = sourceParam,
                            format = "srt,ass,vtt",
                            season = season,
                            episode = episode,
                        )
                        if (!response.isSuccessful) {
                            Log.w(TAG, "$LOG_PREFIX http error status=${response.code()} reason=${response.message()}")
                            IntegrationCallResult.HttpError(
                                statusCode = response.code(),
                                reason = "wyzie_search_failed",
                            )
                        } else {
                            val body = response.body() ?: emptyList()
                            Log.d(
                                TAG,
                                "$LOG_PREFIX ok sources=$sourceParam count=${body.size} latencyMs=${System.currentTimeMillis() - started}",
                            )
                            IntegrationCallResult.Success(body)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "$LOG_PREFIX network failure: ${e.javaClass.simpleName}: ${e.message}")
                        IntegrationCallResult.NetworkError(e)
                    }
                    outcome
                },
            )
        )

        return when (callResult) {
            is IntegrationCallResult.Success -> callResult.value
            is IntegrationCallResult.HttpError,
            is IntegrationCallResult.NetworkError,
            IntegrationCallResult.Missing -> emptyList()
        }
    }
}
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProviderTest`
Expected: PASS — 14 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieSubtitleIntegrationProvider.kt \
        app/src/test/java/com/nexio/tv/data/integration/subtitles/wyzie/WyzieSubtitleIntegrationProviderTest.kt
git commit -m "feat(wyzie): add WyzieSubtitleIntegrationProvider with silent-degrade error handling"
```

---

## Phase 2 — Repository wiring

### Task 11: Update `SubtitleRepository` interface to take `WyzieIdHints`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/repository/SubtitleRepository.kt`

- [ ] **Step 1: Replace the file body with**

```kotlin
package com.nexio.tv.domain.repository

import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.model.WyzieIdHints

interface SubtitleRepository {
    /**
     * Fetches subtitles from all installed addons that support subtitles AND from the
     * built-in Wyzie lane (when configured).
     *
     * @param type Content type (movie, series, etc.)
     * @param id Content ID (IMDB ID, etc.)
     * @param videoId Optional video ID for series (e.g., tt1234567:1:1 for series episode)
     * @param videoHash Optional OpenSubtitles file hash
     * @param videoSize Optional video file size in bytes
     * @param filename Optional video filename
     * @param wyzieHints Stable id hints for the Wyzie lane. Pass [WyzieIdHints.EMPTY] to skip
     *                   the Wyzie lane entirely (addons still run).
     * @param season Episode season number (paired with [episode]) for TV searches.
     * @param episode Episode number (paired with [season]) for TV searches.
     * @return Merged list of subtitles from addons + Wyzie.
     */
    suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String? = null,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
        wyzieHints: WyzieIdHints = WyzieIdHints.EMPTY,
        season: Int? = null,
        episode: Int? = null,
    ): List<Subtitle>
}
```

- [ ] **Step 2: Compile (existing impl will fail until Task 12)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD FAILED — `SubtitleRepositoryImpl` doesn't satisfy the new signature. That's fine; Task 12 fixes it.

- [ ] **Step 3: Don't commit yet — proceed to Task 12, then commit both together.**

---

### Task 12: Add Wyzie lane to `SubtitleRepositoryImpl` (TDD)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleRepositoryImpl.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/SubtitleRepositoryImplTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/SubtitleRepositoryAddonRoutingTest.kt`

- [ ] **Step 1: Add new TDD tests to `SubtitleRepositoryImplTest.kt`**

Append the following tests (after the existing class body, inside the same `class SubtitleRepositoryImplTest`):

```kotlin
    @Test
    fun `wyzie lane results merge after addon results`() = runTest {
        val addonProvider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepo = mockk<AddonRepositoryImpl>()
        val wyzieProvider = mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>()

        val addon = com.nexio.tv.domain.model.Addon(
            id = "addon.id", name = "Addon", displayName = "Addon",
            version = "1.0.0", description = null, logo = null,
            baseUrl = "https://addon.test", catalogs = emptyList(),
            types = listOf(com.nexio.tv.domain.model.ContentType.MOVIE),
            resources = listOf(
                com.nexio.tv.domain.model.AddonResource(
                    name = "subtitles", types = listOf("movie"), idPrefixes = null,
                ),
            ),
        )
        every { addonRepo.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery { addonProvider.getSubtitles(addon, "https://addon.test/subtitles/movie/tt1.json") } returns
            IntegrationCallResult.Success(
                SubtitleResponseDto(
                    subtitles = listOf(SubtitleItemDto(id = "addon-1", url = "https://a/1.srt", lang = "en")),
                ),
            )
        coEvery {
            wyzieProvider.search(
                type = com.nexio.tv.domain.model.ContentType.MOVIE,
                hints = any(),
                sources = any(),
                season = null,
                episode = null,
            )
        } returns listOf(
            com.nexio.tv.data.remote.dto.WyzieSubtitleDto(
                id = "w1", url = "https://w/1.srt", format = "srt", encoding = "UTF-8",
                display = "English", language = "en", media = "X", isHearingImpaired = false,
                source = "opensubtitles",
            ),
        )

        val repository = SubtitleRepositoryImpl(addonProvider, addonRepo, wyzieProvider)
        val subs = repository.getSubtitles(
            type = "movie", id = "tt1", videoId = null,
            videoHash = null, videoSize = null, filename = null,
            wyzieHints = com.nexio.tv.domain.model.WyzieIdHints(imdb = "tt1"),
            season = null, episode = null,
        )

        assertEquals(2, subs.size)
        assertEquals("addon-1", subs[0].id)
        assertEquals("wyzie:w1", subs[1].id)
        assertEquals("Wyzie · OpenSubtitles", subs[1].addonName)
    }

    @Test
    fun `wyzie lane failure does not affect addon results`() = runTest {
        val addonProvider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepo = mockk<AddonRepositoryImpl>()
        val wyzieProvider = mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>()

        val addon = com.nexio.tv.domain.model.Addon(
            id = "addon.id", name = "Addon", displayName = "Addon",
            version = "1.0.0", description = null, logo = null,
            baseUrl = "https://addon.test", catalogs = emptyList(),
            types = listOf(com.nexio.tv.domain.model.ContentType.MOVIE),
            resources = listOf(
                com.nexio.tv.domain.model.AddonResource(
                    name = "subtitles", types = listOf("movie"), idPrefixes = null,
                ),
            ),
        )
        every { addonRepo.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery { addonProvider.getSubtitles(addon, any()) } returns
            IntegrationCallResult.Success(
                SubtitleResponseDto(
                    subtitles = listOf(SubtitleItemDto(id = "addon-1", url = "https://a/1.srt", lang = "en")),
                ),
            )
        coEvery {
            wyzieProvider.search(any(), any(), any(), any(), any())
        } throws RuntimeException("wyzie boom")

        val repository = SubtitleRepositoryImpl(addonProvider, addonRepo, wyzieProvider)
        val subs = repository.getSubtitles(
            type = "movie", id = "tt1", videoId = null,
            videoHash = null, videoSize = null, filename = null,
            wyzieHints = com.nexio.tv.domain.model.WyzieIdHints(imdb = "tt1"),
            season = null, episode = null,
        )

        assertEquals(1, subs.size)
        assertEquals("addon-1", subs.single().id)
    }

    @Test
    fun `addon lane failure does not affect wyzie results`() = runTest {
        val addonProvider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepo = mockk<AddonRepositoryImpl>()
        val wyzieProvider = mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>()

        every { addonRepo.getInstalledAddons() } returns flow { throw RuntimeException("addon boom") }
        coEvery {
            wyzieProvider.search(any(), any(), any(), any(), any())
        } returns listOf(
            com.nexio.tv.data.remote.dto.WyzieSubtitleDto(
                id = "w1", url = "https://w/1.srt", format = "srt", encoding = "UTF-8",
                display = "English", language = "en", media = "X", isHearingImpaired = false,
                source = "subdl",
            ),
        )

        val repository = SubtitleRepositoryImpl(addonProvider, addonRepo, wyzieProvider)
        val subs = repository.getSubtitles(
            type = "movie", id = "tt1", videoId = null,
            videoHash = null, videoSize = null, filename = null,
            wyzieHints = com.nexio.tv.domain.model.WyzieIdHints(imdb = "tt1"),
            season = null, episode = null,
        )

        assertEquals(1, subs.size)
        assertEquals("Wyzie · SubDL", subs.single().addonName)
    }

    @Test
    fun `wyzie hints empty skips wyzie call entirely`() = runTest {
        val addonProvider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepo = mockk<AddonRepositoryImpl>()
        val wyzieProvider = mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>()

        every { addonRepo.getInstalledAddons() } returns flowOf(emptyList())

        val repository = SubtitleRepositoryImpl(addonProvider, addonRepo, wyzieProvider)
        val subs = repository.getSubtitles(
            type = "movie", id = "tt1", videoId = null,
            videoHash = null, videoSize = null, filename = null,
            wyzieHints = com.nexio.tv.domain.model.WyzieIdHints.EMPTY,
            season = null, episode = null,
        )
        assertTrue(subs.isEmpty())
        coVerify(exactly = 0) { wyzieProvider.search(any(), any(), any(), any(), any()) }
    }
```

Also patch the existing tests' `SubtitleRepositoryImpl(...)` constructor calls to include the new `WyzieSubtitleIntegrationProvider` arg. For each existing call site like `SubtitleRepositoryImpl(provider, addonRepository)`, change to:

```kotlin
SubtitleRepositoryImpl(
    provider,
    addonRepository,
    mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>(relaxed = true),
)
```

Do the same one-line update inside `SubtitleRepositoryAddonRoutingTest.kt` for any constructor calls.

- [ ] **Step 2: Run the tests; they should fail to compile**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.SubtitleRepositoryImplTest`
Expected: FAIL — constructor doesn't accept the third argument yet.

- [ ] **Step 3: Update `SubtitleRepositoryImpl` to take the Wyzie provider and run the second lane**

Replace the file with:

```kotlin
package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.logging.sanitizeUrlForLogs
import com.nexio.tv.core.sync.buildAddonRequestUrl
import com.nexio.tv.data.integration.addon.AddonSubtitleIntegrationProvider
import com.nexio.tv.data.integration.subtitles.wyzie.WyzieResultMapper
import com.nexio.tv.data.integration.subtitles.wyzie.WyzieSourceRouter
import com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.model.WyzieIdHints
import com.nexio.tv.domain.repository.SubtitleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class SubtitleRepositoryImpl @Inject constructor(
    private val addonSubtitleIntegrationProvider: AddonSubtitleIntegrationProvider,
    private val addonRepository: AddonRepositoryImpl,
    private val wyzieSubtitleIntegrationProvider: WyzieSubtitleIntegrationProvider,
) : SubtitleRepository {

    companion object {
        private const val TAG = "SubtitleRepository"
        private const val PER_ADDON_TIMEOUT_MS = 8_000L
        private const val WYZIE_TIMEOUT_MS = 8_000L
    }

    override suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        wyzieHints: WyzieIdHints,
        season: Int?,
        episode: Int?,
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val requestType = canonicalSubtitleType(type)
        val requestId = if (requestType == "series" && videoId != null) videoId else id
        val startedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Fetching subtitles for type=$requestType, id=$requestId, videoId=$videoId")

        coroutineScope {
            val addonDeferred = async {
                runCatching { fetchAddonLane(type, id, videoId, videoHash, videoSize, filename) }
                    .getOrElse { e ->
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Addon lane failed", e)
                        emptyList()
                    }
            }
            val wyzieDeferred = async {
                runCatching { fetchWyzieLane(type, wyzieHints, season, episode) }
                    .getOrElse { e ->
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Wyzie lane failed", e)
                        emptyList()
                    }
            }
            val merged = listOf(addonDeferred, wyzieDeferred).awaitAll().flatten()
            Log.d(
                TAG,
                "Subtitle fetch completed total=${merged.size} in ${System.currentTimeMillis() - startedAtMs}ms",
            )
            merged
        }
    }

    private suspend fun fetchAddonLane(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
    ): List<Subtitle> {
        val addons = try {
            addonRepository.getInstalledAddons().first()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to get installed addons", e)
            return emptyList()
        }
        val requestType = canonicalSubtitleType(type)
        val requestId = if (requestType == "series" && videoId != null) videoId else id

        val subtitleAddons = addons.filter { addon ->
            addon.resources.any { resource ->
                isSubtitleResource(resource.name) && supportsType(resource, requestType, requestId)
            }
        }
        if (subtitleAddons.isEmpty()) return emptyList()

        return coroutineScope {
            subtitleAddons.map { addon ->
                async {
                    val started = System.currentTimeMillis()
                    val result = withTimeoutOrNull(PER_ADDON_TIMEOUT_MS) {
                        fetchSubtitlesFromAddon(addon, type, id, videoId, videoHash, videoSize, filename)
                    }
                    if (result == null) {
                        Log.w(TAG, "Subtitle fetch timed out for addon=${addon.name}")
                        emptyList()
                    } else {
                        Log.d(TAG, "Addon subs ok addon=${addon.name} count=${result.size} ms=${System.currentTimeMillis() - started}")
                        result
                    }
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun fetchWyzieLane(
        type: String,
        hints: WyzieIdHints,
        season: Int?,
        episode: Int?,
    ): List<Subtitle> {
        if (hints == WyzieIdHints.EMPTY) return emptyList()
        val contentType = ContentType.fromString(type)
        val sources = WyzieSourceRouter.sourcesFor(contentType, hints)
        if (sources.isEmpty()) return emptyList()

        val dtos = withTimeoutOrNull(WYZIE_TIMEOUT_MS) {
            wyzieSubtitleIntegrationProvider.search(contentType, hints, sources, season, episode)
        } ?: run {
            Log.w(TAG, "WYZIE_SUBS timed out")
            emptyList()
        }
        return WyzieResultMapper.mapAll(dtos)
    }

    private fun canonicalSubtitleType(type: String): String =
        if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()

    private fun supportsType(
        resource: com.nexio.tv.domain.model.AddonResource,
        type: String,
        id: String,
    ): Boolean {
        if (resource.types.isNotEmpty() && resource.types.none { it.equals(type, ignoreCase = true) }) {
            return false
        }
        val idPrefixes = resource.idPrefixes
        if (idPrefixes != null && idPrefixes.isNotEmpty()) {
            return idPrefixes.any { prefix -> id.startsWith(prefix) }
        }
        return true
    }

    private fun isSubtitleResource(name: String): Boolean =
        name.equals("subtitles", ignoreCase = true) || name.equals("subtitle", ignoreCase = true)

    private suspend fun fetchSubtitlesFromAddon(
        addon: Addon,
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
    ): List<Subtitle> {
        val normalizedType = canonicalSubtitleType(type)
        val actualId = if (normalizedType == "series" && videoId != null) videoId else id
        val extraParams = buildExtraParams(videoHash, videoSize, filename)
        val subtitleUrl = if (extraParams.isNotEmpty()) {
            buildAddonRequestUrl(addon.baseUrl, "subtitles/$normalizedType/$actualId/$extraParams.json")
        } else {
            buildAddonRequestUrl(addon.baseUrl, "subtitles/$normalizedType/$actualId.json")
        }
        Log.d(TAG, "Fetching subtitles from ${addon.name}: ${sanitizeUrlForLogs(subtitleUrl)}")

        return try {
            when (val result = addonSubtitleIntegrationProvider.getSubtitles(addon, subtitleUrl)) {
                is IntegrationCallResult.Success -> result.value.subtitles?.mapNotNull { dto ->
                    Subtitle(
                        id = dto.id ?: "${dto.lang}-${dto.url.hashCode()}",
                        url = dto.url,
                        lang = dto.lang,
                        addonName = addon.displayName,
                        addonLogo = addon.logo,
                    )
                } ?: emptyList()
                is IntegrationCallResult.HttpError -> {
                    Log.e(TAG, "Failed to fetch subtitles from ${addon.name}: http=${result.statusCode} reason=${result.reason}")
                    emptyList()
                }
                is IntegrationCallResult.NetworkError -> {
                    Log.e(TAG, "Failed to fetch subtitles from ${addon.name}", result.throwable)
                    emptyList()
                }
                IntegrationCallResult.Missing -> emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Exception fetching subtitles from ${addon.name}", e)
            emptyList()
        }
    }

    private fun buildExtraParams(videoHash: String?, videoSize: Long?, filename: String?): String {
        val params = mutableListOf<String>()
        videoHash?.let { params.add("videoHash=$it") }
        videoSize?.let { params.add("videoSize=$it") }
        filename?.let { params.add("filename=$it") }
        return if (params.isNotEmpty()) params.joinToString("&") else ""
    }
}
```

- [ ] **Step 4: Re-run the repository tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.SubtitleRepository*"`
Expected: PASS — all existing tests + 4 new ones.

- [ ] **Step 5: Run the full test suite to catch any other callers**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. If any other test references `SubtitleRepository.getSubtitles` and breaks compile, add the new defaulted args at the call site (no behavior change since they default to `EMPTY`/null).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/repository/SubtitleRepository.kt \
        app/src/main/java/com/nexio/tv/data/repository/SubtitleRepositoryImpl.kt \
        app/src/test/java/com/nexio/tv/data/repository/SubtitleRepositoryImplTest.kt \
        app/src/test/java/com/nexio/tv/data/repository/SubtitleRepositoryAddonRoutingTest.kt
git commit -m "feat(wyzie): wire Wyzie lane into SubtitleRepositoryImpl

Adds a second async lane alongside the addon lane, merged via awaitAll.
Either lane failing yields an empty list for that lane only — addon
results are unaffected by Wyzie issues and vice versa."
```

---

### Task 13: Wire `WyzieIdHints` at the player call site

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`

- [ ] **Step 1: Update `fetchAddonSubtitlesNow` to pass hints + season/episode**

Locate the existing `subtitleRepository.getSubtitles(...)` call (around line 77) and replace with:

```kotlin
    val hints = com.nexio.tv.data.integration.subtitles.wyzie.WyzieIdHintsParser.parse(request.id)
    val fetched = subtitleRepository.getSubtitles(
        type = request.type,
        id = request.id,
        videoId = request.videoId,
        videoHash = currentVideoHash,
        videoSize = currentVideoSize,
        filename = currentFilename,
        wyzieHints = hints,
        season = currentSeason,
        episode = currentEpisode,
    )
```

(`currentSeason` / `currentEpisode` already exist on `PlayerRuntimeController` — they are referenced in `PlayerRuntimeControllerMetadata.kt` at line 101-102.)

- [ ] **Step 2: Verify with the existing player test**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAddonSubtitleOverlayTest`
Expected: PASS (the new params default to harmless values — `EMPTY` and `null` — for any test that constructs the controller without overriding them).

- [ ] **Step 3: Compile the full app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt
git commit -m "feat(wyzie): build WyzieIdHints from contentId at player call site"
```

---

## Phase 3 — Settings UI (Android TV)

### Task 14: Add `WyzieSubtitleSettingsViewModel`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/settings/WyzieSubtitleSettingsViewModel.kt`

- [ ] **Step 1: Write the ViewModel**

```kotlin
package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.WyzieSettingsDataStore
import com.nexio.tv.domain.model.WyzieSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WyzieSubtitleSettingsViewModel @Inject constructor(
    private val dataStore: WyzieSettingsDataStore,
) : ViewModel() {

    val state: StateFlow<WyzieSettings> = dataStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = WyzieSettings.DEFAULT,
    )

    fun onSetEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.setEnabled(enabled) }
    }

    fun onSetApiKey(value: String) {
        viewModelScope.launch { dataStore.setApiKey(value) }
    }

    fun onClearApiKey() {
        viewModelScope.launch { dataStore.setApiKey("") }
    }
}

/**
 * Returns the masked form of the key for display, or "Not configured" when blank.
 */
internal fun maskWyzieKey(key: String?): String {
    if (key.isNullOrBlank()) return "Not configured"
    if (key.length <= 8) return "•".repeat(key.length)
    return "${key.take(7)}•••${key.takeLast(3)}"
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/WyzieSubtitleSettingsViewModel.kt
git commit -m "feat(wyzie): add WyzieSubtitleSettingsViewModel"
```

---

### Task 15: Add `WyzieSubtitleSettingsScreen`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/settings/WyzieSubtitleSettingsScreen.kt`

- [ ] **Step 1: Write the Compose TV screen**

```kotlin
package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text as TvText
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

private const val WYZIE_REDEEM_URL = "https://sub.wyzie.io/redeem"

@Composable
fun WyzieSubtitleSettingsScreen(
    onEnterApiKey: () -> Unit,
    viewModel: WyzieSubtitleSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showQr by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TvText(
            text = "Wyzie subtitles",
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        TvText(
            text = "Built-in subtitle search across OpenSubtitles, SubDL, Subf2m, Podnapisi, and more. Free with your own Wyzie API key.",
            fontSize = 16.sp,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.enabled,
                onCheckedChange = viewModel::onSetEnabled,
            )
            Spacer(Modifier.width(16.dp))
            TvText(text = if (state.enabled) "Enabled" else "Disabled")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TvText(
                text = "API key:",
                modifier = Modifier.width(140.dp),
            )
            TvText(text = maskWyzieKey(state.apiKey))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onEnterApiKey) { TvText("Enter key") }
            OutlinedButton(onClick = { showQr = !showQr }) {
                TvText(if (showQr) "Hide QR" else "Get a free key")
            }
            if (!state.apiKey.isNullOrBlank()) {
                OutlinedButton(onClick = viewModel::onClearApiKey) { TvText("Clear key") }
            }
        }

        if (showQr) {
            Spacer(Modifier.height(16.dp))
            QrCode(text = WYZIE_REDEEM_URL)
            TvText(
                text = "Scan with your phone to redeem a free key at $WYZIE_REDEEM_URL",
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun QrCode(text: String) {
    val context = LocalContext.current
    val bitmap = remember(text) {
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 256, 256)
        val bmp = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until 256) {
            for (y in 0 until 256) {
                bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        bmp
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR code linking to $text",
        modifier = Modifier.size(256.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(16.dp),
    )
}
```

- [ ] **Step 2: Verify the ZXing dependency exists**

Run: `grep -n "zxing\|core-android" app/build.gradle.kts`
Expected: At least one match. If empty, add to `app/build.gradle.kts` `dependencies { … }`:

```kotlin
implementation("com.google.zxing:core:3.5.3")
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/WyzieSubtitleSettingsScreen.kt \
        app/build.gradle.kts
git commit -m "feat(wyzie): add Wyzie subtitle settings screen with QR redeem helper"
```

---

### Task 16: Add navigation entry for the Wyzie settings screen

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt`

- [ ] **Step 1: Read the existing settings screen to find the cluster pattern**

Run: `grep -n "SubtitleTranslation\|subtitle.translation\|navigateTo\|onNavigate" app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt | head -20`

This identifies how the existing `SubtitleTranslationSettingsScreen` is reached from `SettingsScreen` (button row, list entry, etc).

- [ ] **Step 2: Add a sibling entry for "Wyzie subtitles" pointing to the new screen**

Following the same shape used for `SubtitleTranslationSettings`, add a new row labeled "Wyzie subtitles" that calls the navigation lambda for the Wyzie route. Use the existing nav-host pattern for the route name (`"settings/wyzie_subtitles"` if a string-based NavGraph is used).

- [ ] **Step 3: Add the route to `NexioNavHost.kt` if applicable**

Run: `grep -n "SubtitleTranslationSettings\|composable.*subtitle" app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`

Mirror the existing `SubtitleTranslationSettingsScreen` `composable {…}` registration with one for `WyzieSubtitleSettingsScreen`. Pass an `onEnterApiKey` lambda that opens whatever text input dialog the existing settings flow uses (look for `TextInputDialog`, `PinInputDialog`, or a similar component already used in `SubtitleTranslationSettingsScreen.kt`).

- [ ] **Step 4: Compile + smoke-build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt \
        app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt
git commit -m "feat(wyzie): expose Wyzie subtitle settings in nav and settings screen"
```

---

## Phase 4 — nexio-web sync bridge

### Task 17: Bump sync contract and add `WyzieSyncSettings`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`

- [ ] **Step 1: Read the existing contract to find `SubtitleTranslationSyncSettings` and `ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION`**

Run: `grep -n "ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION\|SubtitleTranslationSyncSettings\|data class.*SyncSettings" app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`

- [ ] **Step 2: Add `WyzieSyncSettings` next to `SubtitleTranslationSyncSettings`**

Insert the following data class near the other `*SyncSettings` declarations:

```kotlin
@JsonClass(generateAdapter = true)
data class WyzieSyncSettings(
    val enabled: Boolean,
    /** Plaintext API key, mirrors how Trakt/Real-Debrid tokens already round-trip. */
    val apiKey: String? = null,
)
```

Then add `wyzie: WyzieSyncSettings` as a field on the `Integrations` (or equivalently named) data class that aggregates the per-integration settings, mirroring the existing `subtitleTranslation` field. Initialize it with `WyzieSyncSettings(enabled = true, apiKey = null)` for back-compat reads.

- [ ] **Step 3: Bump the contract version**

Find the `const val ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 7` line and change to `8`.

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD FAILED — `AccountSettingsSyncService` doesn't populate the new field. Task 18 fixes that.

- [ ] **Step 5: Don't commit yet — proceed to Task 18.**

---

### Task 18: Push and pull Wyzie settings via `AccountSettingsSyncService`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`

- [ ] **Step 1: Inject `WyzieSettingsDataStore`**

In the primary constructor parameter list, alongside `subtitleTranslationSettingsDataStore: SubtitleTranslationSettingsDataStore`, add:

```kotlin
    private val wyzieSettingsDataStore: WyzieSettingsDataStore,
```

- [ ] **Step 2: Add Wyzie to the change-observation flow merge**

Find the `subtitleTranslationSettings = subtitleTranslationSettingsDataStore.settings.drop(1).map { Unit }` line. Below it, add an analogous `wyzieSettings = wyzieSettingsDataStore.settings.drop(1).map { Unit }` flow and include it in the merged `combine`/`flatMap` chain that triggers a sync push (mirror exactly what `subtitleTranslationSettings` does).

- [ ] **Step 3: Populate `WyzieSyncSettings` in the outgoing sync payload**

Find the block where `subtitleTranslation = SubtitleTranslationSyncSettings(...)` is constructed (around line 582) and add:

```kotlin
                wyzie = run {
                    val s = wyzieSettingsDataStore.settings.first()
                    WyzieSyncSettings(enabled = s.enabled, apiKey = s.apiKey)
                },
```

- [ ] **Step 4: Apply incoming Wyzie settings in the pull paths**

Find each block that calls `subtitleTranslationSettingsDataStore.saveSyncedPublicSettings(...)` (lines ~727 and ~826). After each, add:

```kotlin
        val remoteWyzie = settings.integrations.wyzie
        wyzieSettingsDataStore.setEnabled(remoteWyzie.enabled)
        if (remoteWyzie.apiKey != null) {
            wyzieSettingsDataStore.setApiKey(remoteWyzie.apiKey)
        }
```

(The null check preserves a locally-entered key when the remote payload omits it — same convention used for the gemini key elsewhere in this file.)

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run sync-related tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.sync.*"`
Expected: PASS. If a test asserts on the contract version (`assertEquals(7, ...)`), update it to `8`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt \
        app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt \
        app/src/test/java/com/nexio/tv/core/sync/
git commit -m "feat(wyzie): sync Wyzie settings via AccountSettingsSyncService

Bumps ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION 7 → 8. Adds WyzieSyncSettings
to the integrations payload; the apiKey null-check preserves a locally-
entered key when the remote omits it (same convention as gemini)."
```

---

### Task 19: Surface Wyzie settings in `nexio-web`

**Files:**
- Modify: `nexio-web/types/portal.ts`
- Create: `nexio-web/components/integrations/WyzieSubtitlesPanel.vue`

- [ ] **Step 1: Add the Wyzie field to `PortalIntegrations`**

In `portal.ts`, find the `subtitleTranslation: { … }` block (around line 267) and add immediately after it:

```typescript
  wyzie: {
    enabled: boolean
    apiKey: string
  }
```

- [ ] **Step 2: Create the panel component**

```vue
<!-- nexio-web/components/integrations/WyzieSubtitlesPanel.vue -->
<script setup lang="ts">
import { ref, computed, watch } from 'vue'

const props = defineProps<{
  enabled: boolean
  apiKey: string
}>()

const emit = defineEmits<{
  (e: 'update:enabled', value: boolean): void
  (e: 'update:apiKey', value: string): void
}>()

const localEnabled = ref(props.enabled)
const localKey = ref(props.apiKey)

watch(() => props.enabled, (v) => { localEnabled.value = v })
watch(() => props.apiKey, (v) => { localKey.value = v })

const masked = computed(() => {
  if (!localKey.value) return 'Not configured'
  if (localKey.value.length <= 8) return '•'.repeat(localKey.value.length)
  return `${localKey.value.slice(0, 7)}•••${localKey.value.slice(-3)}`
})

function onToggle() {
  localEnabled.value = !localEnabled.value
  emit('update:enabled', localEnabled.value)
}

function onSaveKey() {
  emit('update:apiKey', localKey.value.trim())
}
</script>

<template>
  <section class="wyzie-panel">
    <header>
      <h3>Wyzie subtitles</h3>
      <p>Built-in subtitle search across OpenSubtitles, SubDL, Subf2m, Podnapisi, and more.</p>
    </header>

    <label class="toggle">
      <input type="checkbox" :checked="localEnabled" @change="onToggle" />
      <span>{{ localEnabled ? 'Enabled' : 'Disabled' }}</span>
    </label>

    <div class="key-row">
      <label>
        API key
        <input v-model="localKey" type="text" placeholder="wyzie-…" autocomplete="off" />
      </label>
      <button @click="onSaveKey">Save key</button>
      <span class="masked">{{ masked }}</span>
    </div>

    <p class="hint">
      Free key at
      <a href="https://sub.wyzie.io/redeem" target="_blank" rel="noopener">sub.wyzie.io/redeem</a>
    </p>
  </section>
</template>

<style scoped>
.wyzie-panel { display: flex; flex-direction: column; gap: 16px; padding: 24px; }
.toggle { display: flex; gap: 8px; align-items: center; }
.key-row { display: flex; gap: 12px; align-items: end; }
.masked { font-family: monospace; opacity: 0.7; }
.hint { font-size: 13px; opacity: 0.7; }
</style>
```

- [ ] **Step 3: Mount the panel on whichever settings page already renders `SubtitleTranslationPanel`**

Run: `grep -rln "SubtitleTranslationPanel\|subtitleTranslation" nexio-web/pages/ nexio-web/components/ 2>/dev/null | head -10`

Find the parent component or page that renders the existing subtitle translation panel and add a sibling:

```vue
<WyzieSubtitlesPanel
  :enabled="integrations.wyzie.enabled"
  :api-key="integrations.wyzie.apiKey"
  @update:enabled="(v) => updateIntegration('wyzie', { ...integrations.wyzie, enabled: v })"
  @update:api-key="(v) => updateIntegration('wyzie', { ...integrations.wyzie, apiKey: v })"
/>
```

(Adjust the `updateIntegration` call to match the codebase's actual mutation convention — locate it via the existing `subtitleTranslation` panel mount.)

- [ ] **Step 4: Run nexio-web tests**

Run: `cd nexio-web && pnpm test 2>&1 | tail -30`
Expected: PASS. Update any snapshot/integration test asserting on the integrations shape if needed (analogous to `tests/integration-delete.test.ts` patterns).

- [ ] **Step 5: Commit**

```bash
git add nexio-web/types/portal.ts \
        nexio-web/components/integrations/WyzieSubtitlesPanel.vue
# Plus the parent page that mounts the panel
git commit -m "feat(wyzie): expose Wyzie subtitle settings in nexio-web

Adds wyzie.enabled and wyzie.apiKey to PortalIntegrations and a Vue panel
component mounted alongside the existing SubtitleTranslationPanel."
```

---

## Phase 5 — Manual smoke

### Task 20: Manual smoke checklist

Manual verification on a real Android TV (or Fire TV) device. Record results in your worktree as `tmp/wyzie-smoke-$(date -u +%Y-%m-%dT%H%M%SZ).md`.

- [ ] **Step 1: Install the debug APK and configure a key**

```bash
./gradlew :app:installDebug
# Open Settings → Wyzie subtitles. Show QR. Scan with phone, redeem at sub.wyzie.io/redeem.
# Use "Enter key" to paste the key. Confirm masked display shows wyzie-XXX•••YYY.
```

- [ ] **Step 2: Smoke each routing path**

Play one title from each row. After each, open the subtitle picker and confirm the expected behavior in the table below.

| Title scenario | Expected |
|---|---|
| Movie with IMDB id (`tt…`) | Picker shows `Wyzie · OpenSubtitles`, `Wyzie · SubDL`, `Wyzie · Subf2m`, `Wyzie · Podnapisi` entries (some may be empty). |
| Movie with TMDB id (`tmdb:N`) | Same source set as above. Logcat confirms `id=N` (no `tt`). |
| TV episode with IMDB id, season + episode | Picker also includes `Wyzie · Gestdown`. Logcat confirms `season=` and `episode=` set. |
| Anime via Kitsu (Kitsu id only) | Picker shows no Wyzie entries (silent skip — no usable id). Addons still populate. Logcat: `WYZIE_SUBS skipped: no usable id`. |
| Anime via Kitsu but with TMDB present in `tmdb:N` route | Picker shows `Wyzie · AnimeTosho`, `Wyzie · Jimaku`, `Wyzie · Kitsunekko`, `Wyzie · AjattTools`. Logcat confirms anime source list selected. |
| Disable Wyzie in Settings, re-open player | No Wyzie entries. Addons unaffected. |
| Clear key in Settings, re-open player | No Wyzie entries. No key-error toast. |
| Toggle Wyzie back on with valid key | Wyzie entries reappear. |

- [ ] **Step 3: Configure key from `nexio-web`, confirm it round-trips**

Open `nexio-web` in a browser; load Settings; toggle Wyzie + paste a key. Wait for sync (≤30s). On the TV device, open Settings → Wyzie subtitles. Confirm the key matches what you entered on the web (masked).

Then reverse: on the TV, change the key. Refresh `nexio-web`. Confirm the new key appears (masked).

- [ ] **Step 4: Capture results**

Record pass/fail per row in `tmp/wyzie-smoke-*.md`. Commit nothing — `tmp/` is gitignored.

---

## Self-Review

Spec coverage check (against `docs/superpowers/specs/2026-05-02-wyzie-builtin-subtitles-design.md`):

| Spec section | Plan task |
|---|---|
| §4 Architecture & module layout | Tasks 1–10 (foundation files), Task 12 (repo wiring), Tasks 14–16 (UI), Task 19 (web). |
| §5.1 Storage (`WyzieSettings` value object + DataStore) | Task 7. |
| §5.2 Behavior (skip on disabled / blank key) | Task 10 (provider skips), Task 12 (lane skips on `EMPTY` hints). |
| §5.3 Android TV settings UI | Tasks 14–16. |
| §5.4 nexio-web settings UI | Tasks 17–19. |
| §5.5 Key injection interceptor | Task 9 (`WyzieKeyInterceptor`). |
| §6 Source routing (decision tree, single API call, hint extraction, id selection, season/episode validation, constant params) | Tasks 3 (router), 4 (parser), 10 (provider). |
| §7 Data flow (lanes, mapping, dedup absent, per-source icons, source identity, no caching, telemetry) | Task 6 (mapper + icons), Task 12 (parallel lanes + merge). |
| §8 Error handling (silent degrade table) | Task 10 (provider tests pin every row), Task 12 (lane independence tests). |
| §9 Testing (router, mapper, dto, provider, repo, settings) | Tasks 3, 4, 5, 6, 7, 10, 12. |
| §9.5 Manual smoke | Task 20. |

Placeholder/red-flag scan: no "TBD", "implement later", or "add appropriate error handling" without surrounding code. Every code step contains complete code.

Type-consistency scan: `WyzieIdHints`, `WyzieSource`, `WyzieSubtitleDto`, `WyzieSettings`, and all method signatures (`search(type, hints, sources, season, episode)`, `getSubtitles(..., wyzieHints, season, episode)`, `WyzieResultMapper.map`/`mapAll`) are referenced consistently across tasks.

Scope check: this is a single, focused feature suitable for one execution session. No decomposition needed.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-02-wyzie-builtin-subtitles.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
