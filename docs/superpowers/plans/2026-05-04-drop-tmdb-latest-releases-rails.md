# Drop TMDB "Latest Releases" Rails, Promote "Popular" Rails To Default Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Working directory: `/Users/jneerdael/Scripts/nexio` (main checkout, branch `main`).**

**Goal:** Remove the TMDB "Latest Releases Movies" and "Latest Releases Series" built-in rails entirely from the Android app, and promote "Popular Movies" + "Popular Series" to the default-enabled set in their place.

**Architecture:** The change is fully Android-side: `nexio-web` does not currently have TMDB rail support (its rails come from Trakt/SIMKL only — verified via grep), so there is nothing to remove on web; the user-facing parity goal is satisfied by ensuring web continues to have no Latest-Releases concept. On Android, `TmdbCatalogIds` is the single source of truth — removing the two `LATEST_RELEASES_*` constants cascades to: the catalog-fetch router in `RetrofitTmdbDiscoveryClient.fetchCatalog` and the `catalogContentType` helper (both in `TmdbDiscoveryService.kt`), the `tmdbCatalogTitle` map (in `TmdbDiscoveryModels.kt`), the home view-model `tmdbCatalogContentType` map, the settings UI title/subtitle resolvers, and the strings resources. The companion `hideUnreleasedDigital` preference (its sole consumer was `LATEST_RELEASES_MOVIES`) is also removed in lockstep — from `TmdbCatalogPreferences`, from `TmdbDiscoverySnapshot.matchesPreferences`, from the settings UI toggle, and from all touching tests. Existing user preferences are sanitized on read by `TmdbCatalogPreferences.sanitized()`, which already drops unknown ids — no schema migration is required.

**Tech Stack:** Kotlin, Android (Compose, Hilt, DataStore Preferences), JUnit4, Mockk.

**Branch policy:** All commits land directly on `main`. Do not push.

---

## File Structure (verified against `main` at HEAD c42076452)

**Modify (production):**

- `app/src/main/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStore.kt` — remove `LATEST_RELEASES_*` constants from `TmdbCatalogIds`; remove from `BUILT_IN_ORDER`; rewrite `DEFAULT_ENABLED` to `{TRENDING_MOVIES, TRENDING_SERIES, POPULAR_MOVIES, POPULAR_SERIES}`. Remove the `hideUnreleasedDigital` field from `TmdbCatalogPreferences` and the corresponding `hideUnreleasedDigitalKey` / `setHideUnreleasedDigital` from `TmdbCatalogSettingsDataStore`.
- `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt` — in `RetrofitTmdbDiscoveryClient.fetchCatalog`, delete the two `LATEST_RELEASES_*` branches (the `discoverMovies(... release_date.desc ...)` and `discoverTv(... first_air_date.desc ...)` calls). In `catalogContentType`, drop the two `LATEST_RELEASES_*` enum entries. Wherever a `TmdbDiscoverySnapshot` is constructed with `hideUnreleasedDigital = sanitized.hideUnreleasedDigital`, drop that named arg.
- `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryModels.kt` — drop `hideUnreleasedDigital` field from `TmdbDiscoverySnapshot`; drop the `hideUnreleasedDigital == sanitized.hideUnreleasedDigital` clause from `matchesPreferences`. Drop the two `LATEST_RELEASES_*` mappings from `tmdbCatalogTitle`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt` — drop the two `LATEST_RELEASES_*` entries from the `tmdbCatalogContentType` `when` (around lines 833–848 on main).
- `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt` — drop the two `LATEST_RELEASES_*` branches from the title resolver (around lines 467–468) and from the subtitle resolver (around lines 484–485). Delete the `item(key = "tmdb_digital_release_filter") { ... }` block (around lines 152–164).
- `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModel.kt` — remove the `is TmdbSettingsEvent.ToggleDigitalReleaseFilter -> ...` event arm; remove the `ToggleDigitalReleaseFilter` case from the `sealed class TmdbSettingsEvent`; remove the `hideUnreleasedDigital` field from `TmdbSettingsUiState` (line 131); remove the `hideUnreleasedDigital = catalogPreferences.hideUnreleasedDigital` assignment in `fromCatalogPreferences` (line 158).
- `app/src/main/res/values/strings.xml` — delete `tmdb_digital_release_filter_title`, `tmdb_digital_release_filter_subtitle`, `tmdb_catalog_latest_releases_movies`, `tmdb_catalog_latest_releases_movies_subtitle`, `tmdb_catalog_latest_releases_series`, `tmdb_catalog_latest_releases_series_subtitle`.

**Modify (tests):**

- `app/src/test/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStoreTest.kt` — flip both fixture lists to the new defaults; drop the `hideUnreleasedDigital` named arg from the sanitizer test; add a regression test asserting that legacy `tmdb_latest_releases_*` ids in stored preferences are stripped by `sanitized()`.
- `app/src/test/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModelTest.kt` — flip default-state assertions to expect `POPULAR_*` instead of `LATEST_RELEASES_*`; remove `hideUnreleasedDigital` assertions; replace the digital-release-filter event sub-test with an adult-content-only sub-test.
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogStartupReadinessTest.kt` — delete the test specifically about digital-release-preference invalidation (its `enabledCatalogs = setOf(TmdbCatalogIds.LATEST_RELEASES_MOVIES)` premise is gone). Strip every `hideUnreleasedDigital = ...` named arg from both `TmdbCatalogPreferences(...)` and `TmdbDiscoverySnapshot(...)` constructions throughout the file (the field no longer exists on either class).
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTmdbCatalogPlanTest.kt` — strip every `hideUnreleasedDigital = ...` named arg.
- `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogServiceTmdbTest.kt` — substitute `LATEST_RELEASES_MOVIES` (lines ~155, 160) with `YEAR_MOVIES`; strip `hideUnreleasedDigital = ...` named args (lines ~92, 108, 313).

**No changes (verified):**

- `nexio-web/**` — has no TMDB rail support; only Trakt/SIMKL/MDBList rails. The `Popular Movies` / `Popular Shows` strings in `nexio-web/utils/portal-metadata.ts` are Trakt rails, not TMDB.
- `supabase/account_settings_sync.sql` — no TMDB section in synced settings.
- `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` — on `main` this file does NOT contain any LATEST_RELEASES references or a `fetchCatalog` (catalog routing lives in `TmdbDiscoveryService.kt`).

---

## Task 1: Drop `LATEST_RELEASES_*` constants and `hideUnreleasedDigital` from `TmdbCatalogIds` / `TmdbCatalogPreferences`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStoreTest.kt`

This is the linchpin: removing the constants makes downstream files fail to compile, which the next tasks fix one-by-one.

- [ ] **Step 1: Replace the test file with the new expectations**

Open `app/src/test/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStoreTest.kt` and replace the entire file with:

```kotlin
package com.nexio.tv.data.local

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class TmdbCatalogSettingsDataStoreTest {
    @Test
    fun `tmdb catalog defaults enable trending and popular catalogs only`() {
        assertEquals(
            listOf(
                TmdbCatalogIds.TRENDING_MOVIES,
                TmdbCatalogIds.TRENDING_SERIES,
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
                TmdbCatalogIds.POPULAR_MOVIES,
                TmdbCatalogIds.POPULAR_SERIES
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
            includeAdult = true
        ).sanitized()

        assertEquals(setOf(TmdbCatalogIds.POPULAR_SERIES), prefs.enabledCatalogs)
        assertEquals(TmdbCatalogIds.POPULAR_SERIES, prefs.catalogOrder.first())
        assertEquals(TmdbCatalogIds.TRENDING_MOVIES, prefs.catalogOrder[1])
        assertTrue(prefs.includeAdult)
    }

    @Test
    fun `catalog preference sanitizer drops legacy latest releases ids`() {
        val prefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(
                "tmdb_latest_releases_movies",
                "tmdb_latest_releases_series",
                TmdbCatalogIds.POPULAR_MOVIES
            ),
            catalogOrder = listOf(
                "tmdb_latest_releases_movies",
                TmdbCatalogIds.POPULAR_MOVIES
            )
        ).sanitized()

        assertFalse("tmdb_latest_releases_movies" in prefs.enabledCatalogs)
        assertFalse("tmdb_latest_releases_series" in prefs.enabledCatalogs)
        assertFalse(prefs.catalogOrder.any { it.startsWith("tmdb_latest_releases") })
        assertTrue(TmdbCatalogIds.POPULAR_MOVIES in prefs.enabledCatalogs)
    }
}
```

- [ ] **Step 2: Run the test and verify it fails (red)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.TmdbCatalogSettingsDataStoreTest"`

Expected: compile failure (legacy `BUILT_IN_ORDER` still has `LATEST_RELEASES_*`; `TmdbCatalogPreferences` constructor still requires `hideUnreleasedDigital`). The compile error is the red signal — Step 3 makes it green.

- [ ] **Step 3: Update `TmdbCatalogIds` and `TmdbCatalogPreferences` in `TmdbCatalogSettingsDataStore.kt`**

Edit `app/src/main/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStore.kt`. Replace the existing `object TmdbCatalogIds { ... }` (currently lines 15–46) with:

```kotlin
object TmdbCatalogIds {
    const val TRENDING_MOVIES = "tmdb_trending_movies"
    const val TRENDING_SERIES = "tmdb_trending_series"
    const val POPULAR_MOVIES = "tmdb_popular_movies"
    const val POPULAR_SERIES = "tmdb_popular_series"
    const val YEAR_MOVIES = "tmdb_year_movies"
    const val YEAR_SERIES = "tmdb_year_series"
    const val LANGUAGE_MOVIES = "tmdb_language_movies"
    const val LANGUAGE_SERIES = "tmdb_language_series"

    val BUILT_IN_ORDER: List<String> = listOf(
        TRENDING_MOVIES,
        TRENDING_SERIES,
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
        POPULAR_MOVIES,
        POPULAR_SERIES
    )
}
```

In the same file, replace the existing `data class TmdbCatalogPreferences(...)` (currently lines 48–69) with:

```kotlin
data class TmdbCatalogPreferences(
    val enabledCatalogs: Set<String> = TmdbCatalogIds.DEFAULT_ENABLED,
    val catalogOrder: List<String> = TmdbCatalogIds.BUILT_IN_ORDER,
    val includeAdult: Boolean = false
) {
    fun enabledCatalogIds(): Set<String> {
        val sanitized = sanitized()
        return sanitized.catalogOrder
            .filterTo(linkedSetOf()) { it in sanitized.enabledCatalogs }
    }

    fun sanitized(): TmdbCatalogPreferences {
        val known = TmdbCatalogIds.BUILT_IN_ORDER.toSet()
        val sanitizedEnabled = enabledCatalogs.filterTo(linkedSetOf()) { it in known }
        val sanitizedOrder = catalogOrder.filter { it in known }.distinct()
        return copy(
            enabledCatalogs = sanitizedEnabled,
            catalogOrder = sanitizedOrder + TmdbCatalogIds.BUILT_IN_ORDER.filterNot { it in sanitizedOrder }
        )
    }
}
```

In the same file, inside `class TmdbCatalogSettingsDataStore`:

1. Delete the line `private val hideUnreleasedDigitalKey = booleanPreferencesKey("hide_unreleased_digital")`.
2. In the `catalogPreferences` flow, remove `hideUnreleasedDigital = prefs[hideUnreleasedDigitalKey] ?: true` from the `TmdbCatalogPreferences(...)` construction inside `map { prefs -> ... }`.
3. Delete the `suspend fun setHideUnreleasedDigital(enabled: Boolean) { ... }` function entirely.

(The persisted `hide_unreleased_digital` preference key for users who already have it set is leaked-but-ignored — DataStore Preferences silently keeps unknown keys with no observable side effect.)

- [ ] **Step 4: Run the data-store test and verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.TmdbCatalogSettingsDataStoreTest"`

Expected: 3 tests pass. The wider `:app` module won't yet compile because downstream code still references the deleted constants — Tasks 2–6 fix that. If gradle's per-test compile pulls in the wider module and fails, that's expected; you'll re-run the full test suite after Task 6.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStore.kt \
        app/src/test/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStoreTest.kt
git commit -m "refactor(tmdb): drop latest-releases rails, promote popular rails to defaults"
```

---

## Task 2: Drop `LATEST_RELEASES_*` from `TmdbDiscoveryService.kt` and `TmdbDiscoveryModels.kt`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryModels.kt`

These two sibling files own (1) the catalog-fetch routing, (2) `catalogContentType`, (3) the `TmdbDiscoverySnapshot` cache record with its companion `matchesPreferences`/`hideUnreleasedDigital`, and (4) the `tmdbCatalogTitle` display-name map. After Task 1 removed the `LATEST_RELEASES_*` constants and `TmdbCatalogPreferences.hideUnreleasedDigital`, every reference here breaks the build until cleaned up.

- [ ] **Step 1: Remove `LATEST_RELEASES_*` branches from `RetrofitTmdbDiscoveryClient.fetchCatalog`**

In `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt`, inside the `when (catalogId)` block of `RetrofitTmdbDiscoveryClient.fetchCatalog`, delete these two arms:

```kotlin
            TmdbCatalogIds.LATEST_RELEASES_MOVIES -> tmdbApi.discoverMovies(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "release_date.desc",
                releaseDateLte = today,
                withReleaseType = if (preferences.hideUnreleasedDigital) "4" else null
            )
            TmdbCatalogIds.LATEST_RELEASES_SERIES -> tmdbApi.discoverTv(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "first_air_date.desc",
                firstAirDateLte = today
            )
```

The `when` should now go directly from the `TRENDING_SERIES` arm to the `POPULAR_MOVIES` arm.

- [ ] **Step 2: Remove `LATEST_RELEASES_*` from `catalogContentType`**

In the same file, locate `private fun catalogContentType(catalogId: String): ContentType?`. Delete the two `LATEST_RELEASES_*` lines so the resulting function reads:

```kotlin
    private fun catalogContentType(catalogId: String): ContentType? {
        return when (catalogId) {
            TmdbCatalogIds.TRENDING_MOVIES,
            TmdbCatalogIds.POPULAR_MOVIES,
            TmdbCatalogIds.YEAR_MOVIES,
            TmdbCatalogIds.LANGUAGE_MOVIES -> ContentType.MOVIE
            TmdbCatalogIds.TRENDING_SERIES,
            TmdbCatalogIds.POPULAR_SERIES,
            TmdbCatalogIds.YEAR_SERIES,
            TmdbCatalogIds.LANGUAGE_SERIES -> ContentType.SERIES
            else -> null
        }
    }
```

- [ ] **Step 3: Strip `hideUnreleasedDigital = sanitized.hideUnreleasedDigital` from snapshot constructions in this file**

Run: `grep -n "hideUnreleasedDigital" app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt`

For every hit (expect ~2 hits, around lines 183 and 228 — both inside `TmdbDiscoverySnapshot(...)` constructions): delete the `hideUnreleasedDigital = sanitized.hideUnreleasedDigital,` line. If it's the last named arg, fix the trailing comma on the previous line.

- [ ] **Step 4: Update `TmdbDiscoverySnapshot` and `tmdbCatalogTitle` in `TmdbDiscoveryModels.kt`**

In `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryModels.kt`:

1. Replace the `data class TmdbDiscoverySnapshot(...)` (currently lines 11–30) with:

```kotlin
data class TmdbDiscoverySnapshot(
    val rowsByCatalog: Map<String, CatalogRow> = emptyMap(),
    val updatedAtMs: Long = 0L,
    val includeAdult: Boolean? = null,
    val catalogIdsWithCurrentPreferences: Set<String> = emptySet()
) {
    fun matchesPreferences(preferences: TmdbCatalogPreferences): Boolean {
        val sanitized = preferences.sanitized()
        return includeAdult == sanitized.includeAdult
    }

    fun currentRowsFor(preferences: TmdbCatalogPreferences): Map<String, CatalogRow> {
        if (!matchesPreferences(preferences)) return emptyMap()
        val enabledCatalogIds = preferences.enabledCatalogIds()
        return rowsByCatalog.filterKeys { key ->
            key in catalogIdsWithCurrentPreferences && key in enabledCatalogIds
        }
    }
}
```

2. In the same file, in `fun tmdbCatalogTitle(...)`, delete the two lines:

```kotlin
        TmdbCatalogIds.LATEST_RELEASES_MOVIES -> "TMDB Latest Releases Movies"
        TmdbCatalogIds.LATEST_RELEASES_SERIES -> "TMDB Latest Releases Series"
```

The `when` should jump from `TRENDING_SERIES -> "..."` straight to `POPULAR_MOVIES -> "..."`.

- [ ] **Step 5: Verify these two files compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: `TmdbDiscoveryService.kt` and `TmdbDiscoveryModels.kt` no longer appear in the error list. Other files (settings UI, home utils, tests) will still fail — those are Tasks 3–6.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt \
        app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryModels.kt
git commit -m "refactor(tmdb): drop latest-releases routing and snapshot field"
```

---

## Task 3: Drop `LATEST_RELEASES_*` from `HomeViewModelCatalogUtils.kt`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt`

- [ ] **Step 1: Remove the two `LATEST_RELEASES_*` entries from `tmdbCatalogContentType`**

Locate `private fun tmdbCatalogContentType(key: String): ContentType` (around lines 833–848 on main). Delete the two `LATEST_RELEASES_*` lines so the function becomes:

```kotlin
private fun tmdbCatalogContentType(key: String): ContentType {
    return when (key) {
        TmdbCatalogIds.TRENDING_MOVIES,
        TmdbCatalogIds.POPULAR_MOVIES,
        TmdbCatalogIds.YEAR_MOVIES,
        TmdbCatalogIds.LANGUAGE_MOVIES -> ContentType.MOVIE
        TmdbCatalogIds.TRENDING_SERIES,
        TmdbCatalogIds.POPULAR_SERIES,
        TmdbCatalogIds.YEAR_SERIES,
        TmdbCatalogIds.LANGUAGE_SERIES -> ContentType.SERIES
        else -> ContentType.UNKNOWN
    }
}
```

- [ ] **Step 2: Verify compilation progresses**

Run: `./gradlew :app:compileDebugKotlin`

Expected: this file no longer appears in the error list. Remaining errors should be confined to `TmdbSettingsScreen.kt`, `TmdbSettingsViewModel.kt`, and the test sources.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt
git commit -m "refactor(tmdb): drop latest-releases from home catalog content-type map"
```

---

## Task 4: Drop `LATEST_RELEASES_*` rails and digital-release-filter UI from settings

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModel.kt`

The settings screen owns rail title/subtitle resolvers and a standalone "Digital releases only" toggle whose subtitle text literally references "the latest releases catalog". The view-model exposes a `hideUnreleasedDigital` UI-state field and a `ToggleDigitalReleaseFilter` event that have to go with it.

- [ ] **Step 1: Drop the four `LATEST_RELEASES_*` branches from the title/subtitle resolvers**

In `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt`:

- Delete lines 467–468 (title resolver):
  ```kotlin
        TmdbCatalogIds.LATEST_RELEASES_MOVIES -> stringResource(R.string.tmdb_catalog_latest_releases_movies)
        TmdbCatalogIds.LATEST_RELEASES_SERIES -> stringResource(R.string.tmdb_catalog_latest_releases_series)
  ```
- Delete lines 484–485 (subtitle resolver):
  ```kotlin
        TmdbCatalogIds.LATEST_RELEASES_MOVIES -> stringResource(R.string.tmdb_catalog_latest_releases_movies_subtitle)
        TmdbCatalogIds.LATEST_RELEASES_SERIES -> stringResource(R.string.tmdb_catalog_latest_releases_series_subtitle)
  ```

- [ ] **Step 2: Delete the "Digital releases only" settings list item**

In the same file, delete the `item(key = "tmdb_digital_release_filter") { ... }` block (around lines 152–164). Use this as the `old_string` in the Edit tool, matching the actual file contents:

```kotlin
                item(key = "tmdb_digital_release_filter") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_digital_release_filter_title),
                        subtitle = stringResource(R.string.tmdb_digital_release_filter_subtitle),
                        checked = uiState.hideUnreleasedDigital,
                        enabled = uiState.catalogControlsEditable,
                        onCheckedChange = {
                            viewModel.onEvent(
                                TmdbSettingsEvent.ToggleDigitalReleaseFilter(!uiState.hideUnreleasedDigital)
                            )
                        }
                    )
                }
```

If indentation or argument ordering differs slightly when you open the file, match the actual contents — the goal is to delete the entire `item(key = "tmdb_digital_release_filter")` block.

- [ ] **Step 3: Drop `ToggleDigitalReleaseFilter` from the view-model and its UI state**

In `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModel.kt`:

1. Delete the `is TmdbSettingsEvent.ToggleDigitalReleaseFilter -> ...` arm of the events `when` (around lines 79–81):
   ```kotlin
            is TmdbSettingsEvent.ToggleDigitalReleaseFilter -> update {
                tmdbCatalogSettingsDataStore.setHideUnreleasedDigital(event.enabled)
            }
   ```
2. In the `sealed class TmdbSettingsEvent` definition (later in the same file), delete the `ToggleDigitalReleaseFilter` case:
   ```kotlin
       data class ToggleDigitalReleaseFilter(val enabled: Boolean) : TmdbSettingsEvent()
   ```
3. In `data class TmdbSettingsUiState`, delete the line `val hideUnreleasedDigital: Boolean = true` (around line 131).
4. In `fun fromCatalogPreferences(...)`, delete the line `hideUnreleasedDigital = catalogPreferences.hideUnreleasedDigital` (around line 158).

If `TmdbSettingsEvent` is defined in a separate file, edit that file too. Quick check:
```
grep -n "sealed class TmdbSettingsEvent\|^class TmdbSettingsEvent" \
    app/src/main/java/com/nexio/tv/ui/screens/settings/
```

- [ ] **Step 4: Verify production compilation**

Run: `./gradlew :app:compileDebugKotlin`

Expected: production sources (`app/src/main/...`) compile cleanly. Remaining errors must be in `app/src/test/...` (handled in Task 6).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt \
        app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModel.kt
git commit -m "refactor(tmdb): remove latest-releases rails and digital-release-filter from settings UI"
```

---

## Task 5: Strip obsolete strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Delete the six obsolete string resources**

In `app/src/main/res/values/strings.xml`, delete these lines (around 698–699 and 704–707 on main):

```xml
    <string name="tmdb_digital_release_filter_title">Digital releases only</string>
    <string name="tmdb_digital_release_filter_subtitle">Hide unreleased movies from the latest releases catalog.</string>
```

```xml
    <string name="tmdb_catalog_latest_releases_movies">Latest Releases Movies</string>
    <string name="tmdb_catalog_latest_releases_movies_subtitle">Recently released movies from TMDB.</string>
    <string name="tmdb_catalog_latest_releases_series">Latest Releases Series</string>
    <string name="tmdb_catalog_latest_releases_series_subtitle">Recently released series from TMDB.</string>
```

- [ ] **Step 2: Sweep for stray translations**

Run: `grep -rln "tmdb_catalog_latest_releases\|tmdb_digital_release_filter" app/src/main/res/`

Expected: no output. If any locale-specific `values-*/strings.xml` matches, delete those entries in the same edit.

- [ ] **Step 3: Verify no dangling references**

Run: `grep -rn "tmdb_catalog_latest_releases\|tmdb_digital_release_filter" app/`

Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml
# include any locale files if edited
git commit -m "refactor(tmdb): drop latest-releases and digital-release-filter strings"
```

---

## Task 6: Update tests that referenced `LATEST_RELEASES_*` or `hideUnreleasedDigital`

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModelTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogStartupReadinessTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTmdbCatalogPlanTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogServiceTmdbTest.kt`

After Tasks 1–4 the test sources fail to compile. Each file gets the minimum mechanical change to keep its intent intact under the new defaults.

- [ ] **Step 1: Update `TmdbSettingsViewModelTest.kt`**

Replace the body of the `default ui state exposes tmdb catalog preferences` test (around lines 41–53) with:

```kotlin
    @Test
    fun `default ui state exposes tmdb catalog preferences`() = runTest(dispatcher) {
        val viewModel = createViewModel()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(TmdbCatalogIds.TRENDING_MOVIES in state.enabledCatalogKeys)
        assertTrue(TmdbCatalogIds.POPULAR_SERIES in state.enabledCatalogKeys)
        assertFalse(TmdbCatalogIds.YEAR_MOVIES in state.enabledCatalogKeys)
        assertFalse(state.includeAdult)
    }
```

Replace the body of `catalog preferences remain editable when metadata enrichment is inactive` (around lines 80–100) with:

```kotlin
    @Test
    fun `catalog preferences remain editable when metadata enrichment is inactive`() = runTest(dispatcher) {
        val viewModel = createViewModel(tmdbSettings = TmdbSettings(enabled = false))

        advanceUntilIdle()
        val inactiveState = viewModel.uiState.value
        assertFalse(inactiveState.isActive)
        assertTrue(inactiveState.catalogControlsEditable)
        assertTrue(TmdbCatalogIds.TRENDING_MOVIES in inactiveState.enabledCatalogKeys)
        assertTrue(TmdbCatalogIds.POPULAR_SERIES in inactiveState.enabledCatalogKeys)
        assertFalse(TmdbCatalogIds.YEAR_MOVIES in inactiveState.enabledCatalogKeys)

        viewModel.onEvent(TmdbSettingsEvent.ToggleCatalog(TmdbCatalogIds.YEAR_MOVIES, enabled = true))
        viewModel.onEvent(TmdbSettingsEvent.ToggleAdultContent(enabled = true))
        advanceUntilIdle()

        val updatedState = viewModel.uiState.value
        assertFalse(updatedState.isActive)
        assertTrue(TmdbCatalogIds.YEAR_MOVIES in updatedState.enabledCatalogKeys)
        assertTrue(updatedState.includeAdult)
    }
```

Delete the `toggling adult content and digital release filter updates ui state` test (around lines 66–78) and replace it with an adult-content-only variant:

```kotlin
    @Test
    fun `toggling adult content updates ui state`() = runTest(dispatcher) {
        val viewModel = createViewModel()

        advanceUntilIdle()
        viewModel.onEvent(TmdbSettingsEvent.ToggleAdultContent(enabled = true))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.includeAdult)
    }
```

- [ ] **Step 2: Update `HomeCatalogStartupReadinessTest.kt`**

In `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogStartupReadinessTest.kt`:

1. Delete the test that starts around line 951:
   ```kotlin
       @Test
       fun `tmdb refresh invalidates populated rows when digital release preference changes`() {
           ...
       }
   ```
   (it spans from `@Test` through its closing `}` around line 972). Its premise — toggling `hideUnreleasedDigital` invalidates a `LATEST_RELEASES_MOVIES` row — no longer applies because both halves of the premise are gone.

2. Strip every `hideUnreleasedDigital = ...` named arg from `TmdbCatalogPreferences(...)` AND from `TmdbDiscoverySnapshot(...)` constructions throughout the file (the field is now removed from both classes). Also strip every `tmdbHideUnreleasedDigital = ...` named arg from any test-helper builder. Run:
   ```
   grep -n "hideUnreleasedDigital\|tmdbHideUnreleasedDigital" \
     app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogStartupReadinessTest.kt
   ```
   For each hit, delete just that one named-argument line (and fix the previous line's trailing comma if it becomes the new last argument).

3. Verify with: `./gradlew :app:compileDebugUnitTestKotlin --tests "com.nexio.tv.ui.screens.home.HomeCatalogStartupReadinessTest"`

   If a remaining error mentions `tmdbHideUnreleasedDigital` on a different builder type, locate that builder (likely a test helper) and delete the corresponding parameter — but only delete it if it isn't used by any other test outside this file.

- [ ] **Step 3: Update `HomeViewModelTmdbCatalogPlanTest.kt`**

Strip every `hideUnreleasedDigital = ...` named arg. Run:
```
grep -n "hideUnreleasedDigital" \
  app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTmdbCatalogPlanTest.kt
```
Expected hits at ~84, 93, 161. Delete each line (and fix preceding-line trailing commas as needed).

- [ ] **Step 4: Update `AndroidTvFeedCatalogServiceTmdbTest.kt`**

Edit `app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogServiceTmdbTest.kt`:

1. Replace `TmdbCatalogIds.LATEST_RELEASES_MOVIES` with `TmdbCatalogIds.YEAR_MOVIES` on lines ~155 and ~160 (inside an `enabledCatalogs = setOf(...)` and `catalogOrder = listOf(...)`).
2. Strip every `hideUnreleasedDigital = ...` named arg. Run:
   ```
   grep -n "hideUnreleasedDigital\|LATEST_RELEASES" \
     app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogServiceTmdbTest.kt
   ```
   Expected hits at ~92, 108, 313 (for `hideUnreleasedDigital`). Delete those.
3. Confirm no `LATEST_RELEASES` left in this file.

- [ ] **Step 5: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: all tests pass. If any failure ties to `LATEST_RELEASES` or `hideUnreleasedDigital`, sweep:
```
grep -rn "LATEST_RELEASES\|tmdb_latest_releases\|hideUnreleasedDigital\|tmdb_digital_release_filter" app/src/
```
and remove every straggler. Re-run until green.

- [ ] **Step 6: Commit**

```bash
git add app/src/test/
git commit -m "test(tmdb): align unit tests with latest-releases removal and popular defaults"
```

---

## Task 7: Final verification

**Files:**
- (No code changes unless stragglers found)

- [ ] **Step 1: Whole-repo grep for surviving references**

Run from `/Users/jneerdael/Scripts/nexio`:
```bash
grep -rn "LATEST_RELEASES\|latest_releases_movies\|latest_releases_series\|tmdb_catalog_latest_releases\|tmdb_digital_release_filter\|hideUnreleasedDigital" \
  --include="*.kt" --include="*.kts" --include="*.xml" \
  --include="*.ts" --include="*.tsx" --include="*.vue" --include="*.json" \
  --include="*.md" --include="*.sql" \
  app/ nexio-web/ supabase/ docs/ openspec/ 2>/dev/null
```

Expected: empty output, OR only matches inside `docs/superpowers/plans/2026-05-04-drop-tmdb-latest-releases-rails.md` (this plan file). Any other match is a straggler and must be removed.

- [ ] **Step 2: Confirm `nexio-web` is unchanged**

Run: `git diff origin/main -- nexio-web`

Expected: empty diff. The user's "applies clean across nexio-web" requirement is satisfied by web *not* having TMDB rails, not by adding them.

- [ ] **Step 3: Build the Android app**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual smoke note**

Document for the merger: on first launch after install, a user with previously persisted `enabledCatalogs = {TRENDING_*, LATEST_RELEASES_*}` will be sanitized down to `{TRENDING_*}` only — they will *not* automatically gain `POPULAR_*` because `DEFAULT_ENABLED` only applies when the persisted set is absent. This is the correct conservative behavior: existing users keep what they explicitly enabled (minus deleted ids); only fresh installs (or users who never touched TMDB settings) see the new "Trending + Popular" default. If product wants existing users force-migrated to enable Popular, that's a follow-up migration task — not in scope here.

- [ ] **Step 5: Commit (only if stragglers found)**

If steps 1–3 turned up issues:
```bash
git add -A
git commit -m "refactor(tmdb): final cleanup of latest-releases stragglers"
```

Otherwise, no commit needed for this verification task.
