# Cross-Client Home Catalog Rails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android and nexio-web manage and render the same Modern Home catalog rails through one profile-scoped `catalogs.home.rails[]` contract, including web-visible TMDB and Kitsu stock catalogs.

**Architecture:** Android stores the local rails list as `layout_settings.home_catalog_rails_json`. Web exposes the same data as typed `catalogs.home.rails[]`; profile 1/default-profile syncs it through the v13 `catalogs.home` account-settings section, while profiles 2-4 map it to/from the Android preference key in `server/utils/profile-settings-blob.ts`. Modern Home and both catalog-management UIs treat the rails array as the displayed list; provider settings and addon manifests only provide candidate inventory for Add Catalog.

**Tech Stack:** Kotlin, Jetpack DataStore Preferences, kotlinx.serialization/Gson, Android TV Compose, Hilt, JUnit/MockK coroutine tests, Nuxt 4/Vue 3, TypeScript, node:test, vuedraggable, Supabase v12 profile-settings RPCs, and the v13 `catalogs.home` account-settings section for profile 1.

---

## Scope Check

This plan covers Android and nexio-web together because the work is not independently releasable: the key acceptance criterion is that both clients interpret the same profile-scoped rails contract identically. The plan keeps the shared data contract first, then adapts Android rendering/management, then adapts web rendering/management, then adds parity fixtures.

## Current v13 Alignment Notes

- Execute this plan on top of `docs/superpowers/plans/2026-05-12-supabase-v13-sectioned-account-settings.md`, or execute that plan first. The base branch for this plan should have `ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 13`, `sync_pull_account_snapshot_v13`, `sync_push_account_settings_sections_v13`, and `nexio-web/utils/account-settings-sections.ts`.
- Profile 1/default-profile catalog rails are account settings and must sync as the v13 `catalogs.home` section. A web change to `catalogs.home.rails` must become a section payload pushed through `sync_push_account_settings_sections_v13`, not a profile-settings blob write.
- Profiles 2-4 still use `sync_pull_profile_settings_blob_v10` and `sync_push_profile_settings_blob_v10`. The profile-settings blob bridge remains necessary there because Android stores the profile-scoped rails list as `layout_settings.home_catalog_rails_json`.
- The legacy `homeCatalogOrderKeys` and `disabledHomeCatalogKeys` fields stay in the payload for compatibility and migration only. New visible-list/add-modal mutations must write `rails` and mark `catalogs.home.rails` dirty.
- TMDB and Kitsu stock catalog settings are v13 sections (`catalogs.tmdb`, `catalogs.kitsu`) for profile 1 and profile-settings feature blobs (`tmdb_catalog_settings`, `kitsu_catalog_settings`) for profiles 2-4.

## File Structure

### Android

- Create `app/src/main/java/com/nexio/tv/domain/model/HomeCatalogRail.kt`
  - Shared rail DTO used by sync models, DataStore JSON, home projection, and management.
- Modify `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
  - Add `railsVersion` and `rails` fields to `HomeCatalogSyncSettings`.
- Modify `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt`
  - Read/write `home_catalog_rails_json` under `layout_settings`.
- Modify `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
  - Include rails in the v13 `catalogs.home` section and mark `catalogs.home.rails` as the dirty path for visible-list mutations.
- Modify `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
  - Read/write rails when building and applying the v13 account settings payload for profile 1/default-profile.
- Create `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeCatalogRailContract.kt`
  - Sanitize rails, derive visible keys from live definitions, and migrate legacy effective order into rail entries.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
  - Cache current `homeCatalogRails`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
  - Observe `homeCatalogRails` and use them as the preferred Modern Home display order.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModel.kt`
  - Build visible rails from `homeCatalogRails`, expose Add Catalog candidates, and write add/remove/reorder back to `LayoutPreferenceDataStore.setHomeCatalogRails`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderScreen.kt`
  - Replace enable/disable action with remove-from-home and Add Catalog dialog.
- Modify `app/src/main/res/values/strings.xml`
  - Add strings for Add Catalog, remove from home, and unavailable rail labels.
- Tests:
  - Create `app/src/test/java/com/nexio/tv/domain/model/HomeCatalogRailTest.kt`
  - Create `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeCatalogRailContractTest.kt`
  - Modify `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`
  - Modify `app/src/test/java/com/nexio/tv/data/remote/supabase/TmdbKitsuCatalogSyncModelsTest.kt`
  - Modify `app/src/test/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModelTest.kt`
  - Create `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRailsProjectionTest.kt`

### nexio-web

- Modify `nexio-web/types/portal.ts`
  - Add rail types plus TMDB/Kitsu catalog settings.
- Modify `nexio-web/utils/portal-defaults.ts`
  - Add default TMDB/Kitsu catalog order and empty/default enabled sets.
- Modify `nexio-web/utils/portal-settings.ts`
  - Sanitize `catalogs.home.rails[]`, `catalogs.tmdb`, and `catalogs.kitsu`.
- Modify `nexio-web/utils/account-settings-sections.ts`
  - Include `catalogs.home`, `catalogs.tmdb`, and `catalogs.kitsu` in v13 section extraction/composition for profile 1/default-profile.
- Modify `nexio-web/server/utils/profile-settings-blob.ts`
  - Map `catalogs.home.rails[]` to/from Android `layout_settings.home_catalog_rails_json` for profiles 2-4; map TMDB/Kitsu provider settings to/from their Android feature blobs for profile-settings blob sync.
- Modify `nexio-web/utils/portal-sync-paths.ts`
  - Add synced paths for `catalogs.home.rails`, `catalogs.tmdb`, and `catalogs.kitsu`; v13 path mapping groups these under section keys.
- Modify `nexio-web/utils/portal-metadata.ts`
  - Add TMDB/Kitsu labels.
- Create `nexio-web/utils/home-catalog-rails.ts`
  - Shared web helpers for rail sanitize/migrate/add/remove/reorder and visible/available inventory.
- Modify `nexio-web/composables/usePortalStore.ts`
  - Include TMDB/Kitsu inventory, expose visible and available catalog lists, and mutate `catalogs.home.rails[]`.
- Modify `nexio-web/composables/useProfileStore.ts`
  - Mirror the same helpers for secondary profile catalog management.
- Modify `nexio-web/components/portal/ProfileCatalogsTab.vue`
  - Pass visible and available lists into `CatalogInventory`.
- Modify `nexio-web/components/portal/CatalogInventory.vue`
  - Visible List + Add Catalog UI.
- Tests:
  - Create `nexio-web/tests/home-catalog-rails.test.ts`
  - Modify `nexio-web/tests/account-settings-sections.test.ts`
  - Modify `nexio-web/tests/profile-settings-blob.test.ts`
  - Modify `nexio-web/tests/portal-contract-v4.test.ts`
  - Modify `nexio-web/tests/portal-sync-paths.test.ts`

### Shared Fixtures

- Create `docs/superpowers/fixtures/home-catalog-rails/legacy-visible-order.json`
- Create `docs/superpowers/fixtures/home-catalog-rails/rails-with-tmdb-kitsu.json`
- Create `docs/superpowers/fixtures/home-catalog-rails/rails-duplicates-unknown.json`

## Task 1: Android Shared Rail Model And Sync Fields

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/HomeCatalogRail.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
- Create: `app/src/test/java/com/nexio/tv/domain/model/HomeCatalogRailTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/remote/supabase/TmdbKitsuCatalogSyncModelsTest.kt`

- [ ] **Step 1: Write the failing domain-model tests**

Create `app/src/test/java/com/nexio/tv/domain/model/HomeCatalogRailTest.kt`:

```kotlin
package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogRailTest {
    @Test
    fun `sanitize keeps first duplicate and trims fields`() {
        val rails = sanitizeHomeCatalogRails(
            listOf(
                HomeCatalogRail(key = " tmdb_trending_movies ", family = " tmdb ", source = " provider_catalog ", title = " Trending Movies "),
                HomeCatalogRail(key = "tmdb_trending_movies", family = "tmdb", source = "provider_catalog", title = "Duplicate"),
                HomeCatalogRail(key = "   ", family = "tmdb", source = "provider_catalog", title = "Blank")
            )
        )

        assertEquals(1, rails.size)
        assertEquals("tmdb_trending_movies", rails.single().key)
        assertEquals("tmdb", rails.single().family)
        assertEquals("provider_catalog", rails.single().source)
        assertEquals("Trending Movies", rails.single().title)
        assertTrue(rails.single().enabled)
    }

    @Test
    fun `catalog record family inference supports stock providers and addons`() {
        assertEquals("tmdb", homeCatalogRailFamilyForKey("tmdb_popular_movies"))
        assertEquals("kitsu", homeCatalogRailFamilyForKey("kitsu_trending_anime"))
        assertEquals("trakt", homeCatalogRailFamilyForKey("trakt_up_next"))
        assertEquals("simkl", homeCatalogRailFamilyForKey("simkl_tv_trending_today"))
        assertEquals("mdblist", homeCatalogRailFamilyForKey("top:owner/list"))
        assertEquals("addon", homeCatalogRailFamilyForKey("addon-cinemeta_movie_popular"))
    }
}
```

- [ ] **Step 2: Run the failing domain-model tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.HomeCatalogRailTest
```

Expected: FAIL with unresolved references for `HomeCatalogRail`, `sanitizeHomeCatalogRails`, and `homeCatalogRailFamilyForKey`.

- [ ] **Step 3: Add the shared rail model**

Create `app/src/main/java/com/nexio/tv/domain/model/HomeCatalogRail.kt`:

```kotlin
package com.nexio.tv.domain.model

import kotlinx.serialization.Serializable

const val HOME_CATALOG_RAILS_VERSION = 1

const val HOME_CATALOG_FAMILY_ADDON = "addon"
const val HOME_CATALOG_FAMILY_TRAKT = "trakt"
const val HOME_CATALOG_FAMILY_SIMKL = "simkl"
const val HOME_CATALOG_FAMILY_MDBLIST = "mdblist"
const val HOME_CATALOG_FAMILY_TMDB = "tmdb"
const val HOME_CATALOG_FAMILY_KITSU = "kitsu"

const val HOME_CATALOG_SOURCE_ADDON_CATALOG = "addon_catalog"
const val HOME_CATALOG_SOURCE_PROVIDER_CATALOG = "provider_catalog"
const val HOME_CATALOG_SOURCE_PROVIDER_LIST = "provider_list"

@Serializable
data class HomeCatalogRail(
    val key: String = "",
    val family: String = "",
    val source: String = "",
    val title: String = "",
    val enabled: Boolean = true,
    val addedAtMs: Long? = null
)

fun sanitizeHomeCatalogRails(rails: List<HomeCatalogRail>): List<HomeCatalogRail> {
    val seen = linkedSetOf<String>()
    val sanitized = ArrayList<HomeCatalogRail>(rails.size)
    for (rail in rails) {
        val key = rail.key.trim()
        if (key.isBlank() || !seen.add(key)) continue
        val family = rail.family.trim().ifBlank { homeCatalogRailFamilyForKey(key) }
        val source = rail.source.trim().ifBlank { homeCatalogRailSourceForFamily(family) }
        sanitized += rail.copy(
            key = key,
            family = family,
            source = source,
            title = rail.title.trim(),
            enabled = rail.enabled
        )
    }
    return sanitized
}

fun homeCatalogRailFamilyForKey(key: String): String = when {
    key.startsWith("trakt_") -> HOME_CATALOG_FAMILY_TRAKT
    key.startsWith("simkl_") -> HOME_CATALOG_FAMILY_SIMKL
    key.startsWith("tmdb_") -> HOME_CATALOG_FAMILY_TMDB
    key.startsWith("kitsu_") -> HOME_CATALOG_FAMILY_KITSU
    key.startsWith("mdblist_") || key.startsWith("top:") || key.startsWith("personal:") -> HOME_CATALOG_FAMILY_MDBLIST
    else -> HOME_CATALOG_FAMILY_ADDON
}

fun homeCatalogRailSourceForFamily(family: String): String = when (family) {
    HOME_CATALOG_FAMILY_ADDON -> HOME_CATALOG_SOURCE_ADDON_CATALOG
    HOME_CATALOG_FAMILY_MDBLIST -> HOME_CATALOG_SOURCE_PROVIDER_LIST
    else -> HOME_CATALOG_SOURCE_PROVIDER_CATALOG
}
```

- [ ] **Step 4: Add the failing sync round-trip test**

Append this test to `app/src/test/java/com/nexio/tv/data/remote/supabase/TmdbKitsuCatalogSyncModelsTest.kt`:

```kotlin
    @Test
    fun `HomeCatalogSyncSettings carries displayed rails`() {
        val original = HomeCatalogSyncSettings(
            railsVersion = 1,
            rails = listOf(
                com.nexio.tv.domain.model.HomeCatalogRail(
                    key = "tmdb_trending_movies",
                    family = "tmdb",
                    source = "provider_catalog",
                    title = "Trending Movies",
                    addedAtMs = 1778544000000L
                )
            ),
            homeCatalogOrderKeys = listOf("legacy-key"),
            disabledHomeCatalogKeys = emptyList()
        )

        val text = json.encodeToString(HomeCatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(HomeCatalogSyncSettings.serializer(), text)

        assertEquals(1, decoded.railsVersion)
        assertEquals("tmdb_trending_movies", decoded.rails?.single()?.key)
        assertEquals(listOf("legacy-key"), decoded.homeCatalogOrderKeys)
    }
```

- [ ] **Step 5: Run the failing sync test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.remote.supabase.TmdbKitsuCatalogSyncModelsTest
```

Expected: FAIL because `HomeCatalogSyncSettings` has no `railsVersion` or `rails` properties.

- [ ] **Step 6: Add `railsVersion` and `rails` to the sync model**

Modify `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`:

```kotlin
import com.nexio.tv.domain.model.HOME_CATALOG_RAILS_VERSION
import com.nexio.tv.domain.model.HomeCatalogRail
```

Replace `HomeCatalogSyncSettings` with:

```kotlin
@Serializable
data class HomeCatalogSyncSettings(
    val railsVersion: Int? = null,
    val rails: List<HomeCatalogRail>? = null,
    val heroCatalogKeys: List<String>? = null,
    val homeCatalogOrderKeys: List<String>? = null,
    val disabledHomeCatalogKeys: List<String>? = null
) {
    fun railsVersionOrDefault(): Int = railsVersion ?: HOME_CATALOG_RAILS_VERSION
}
```

- [ ] **Step 7: Run the Android model tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.HomeCatalogRailTest --tests com.nexio.tv.data.remote.supabase.TmdbKitsuCatalogSyncModelsTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/HomeCatalogRail.kt app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt app/src/test/java/com/nexio/tv/domain/model/HomeCatalogRailTest.kt app/src/test/java/com/nexio/tv/data/remote/supabase/TmdbKitsuCatalogSyncModelsTest.kt
git commit -m "feat(android): add shared home catalog rail sync model"
```

## Task 2: Android Layout DataStore Rails Persistence

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt`
- Create: `app/src/test/java/com/nexio/tv/data/local/LayoutPreferenceDataStoreHomeCatalogRailsTest.kt`

- [ ] **Step 1: Write the failing DataStore tests**

Create `app/src/test/java/com/nexio/tv/data/local/LayoutPreferenceDataStoreHomeCatalogRailsTest.kt`:

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.domain.model.HomeCatalogRail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.nexio.tv.testutil.layoutPreferenceDataStoreForTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutPreferenceDataStoreHomeCatalogRailsTest {
    @Test
    fun `home catalog rails persist as sanitized json`() = runTest {
        val store = layoutPreferenceDataStoreForTest()

        store.setHomeCatalogRails(
            listOf(
                HomeCatalogRail(key = " tmdb_trending_movies ", family = "tmdb", source = "provider_catalog", title = " Trending "),
                HomeCatalogRail(key = "tmdb_trending_movies", family = "tmdb", source = "provider_catalog", title = "Duplicate")
            )
        )

        val rails = store.homeCatalogRails.first()
        assertEquals(1, rails.size)
        assertEquals("tmdb_trending_movies", rails.single().key)
        assertEquals("Trending", rails.single().title)
    }

    @Test
    fun `blank or invalid rails json reads as empty list`() = runTest {
        val store = layoutPreferenceDataStoreForTest()

        assertEquals(emptyList<HomeCatalogRail>(), store.homeCatalogRails.first())
    }
}
```

- [ ] **Step 2: Run the failing DataStore tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.LayoutPreferenceDataStoreHomeCatalogRailsTest
```

Expected: FAIL because `homeCatalogRails` and `setHomeCatalogRails` do not exist.

- [ ] **Step 3: Add rails read/write support to `LayoutPreferenceDataStore`**

Modify `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt`:

```kotlin
import com.nexio.tv.domain.model.HomeCatalogRail
import com.nexio.tv.domain.model.sanitizeHomeCatalogRails
```

Add the preference key near the existing catalog keys:

```kotlin
private val homeCatalogRailsJsonKey = stringPreferencesKey("home_catalog_rails_json")
```

Add the flow after `disabledHomeCatalogKeys`:

```kotlin
val homeCatalogRails: Flow<List<HomeCatalogRail>> = profileFlow { prefs ->
    parseHomeCatalogRails(prefs[homeCatalogRailsJsonKey])
}
```

Add the setter after `setDisabledHomeCatalogKeys`:

```kotlin
suspend fun setHomeCatalogRails(rails: List<HomeCatalogRail>) {
    val normalized = sanitizeHomeCatalogRails(rails)
    store().edit { prefs ->
        if (normalized.isEmpty()) {
            prefs.remove(homeCatalogRailsJsonKey)
        } else {
            prefs[homeCatalogRailsJsonKey] = gson.toJson(normalized)
        }
    }
}
```

Add this parser near `parseCatalogKeys`:

```kotlin
private fun parseHomeCatalogRails(json: String?): List<HomeCatalogRail> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val type = object : TypeToken<List<HomeCatalogRail>>() {}.type
        val parsed = gson.fromJson<List<HomeCatalogRail>>(json, type).orEmpty()
        sanitizeHomeCatalogRails(parsed)
    } catch (_: Exception) {
        emptyList()
    }
}
```

- [ ] **Step 4: Run the DataStore tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.LayoutPreferenceDataStoreHomeCatalogRailsTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt app/src/test/java/com/nexio/tv/data/local/LayoutPreferenceDataStoreHomeCatalogRailsTest.kt
git commit -m "feat(android): persist home catalog rails in layout settings"
```

## Task 2A: Android V13 Account Sync Carries Home Rails

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`

- [ ] **Step 1: Write the failing v13 section contract tests**

Add these imports to `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt` near the existing imports:

```kotlin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
```

Append these tests inside `AccountConfigSyncContractTest`:

```kotlin
@Test
fun `home catalog rails are included in the v13 catalogs home section payload`() {
    val rails = listOf(
        com.nexio.tv.domain.model.HomeCatalogRail(
            key = "tmdb_trending_movies",
            family = "tmdb",
            source = "provider_catalog",
            title = "Trending Movies",
            addedAtMs = 1778544000000L
        )
    )

    val payload = buildAccountConfigSyncPayload(
        integrations = IntegrationSettings(),
        heroCatalogKeys = emptyList(),
        homeCatalogOrderKeys = emptyList(),
        disabledHomeCatalogKeys = emptyList(),
        homeCatalogRails = rails,
        traktCatalogEnabledSet = emptyList(),
        traktCatalogOrder = emptyList(),
        traktSelectedPopularListKeys = emptyList(),
        simklCatalogEnabledSet = emptyList(),
        simklCatalogOrder = emptyList(),
        mdbListHiddenPersonalListKeys = emptyList(),
        mdbListSelectedTopListKeys = emptyList(),
        mdbListCatalogOrder = emptyList(),
        trackingProvider = TrackingProvider.TRAKT,
        formatter = FormatterSyncSettings()
    )

    assertEquals(1, payload.catalogs.home?.railsVersion)
    assertEquals("tmdb_trending_movies", payload.catalogs.home?.rails?.single()?.key)
}

@Test
fun `home catalog rail changes are marked as catalogs home section dirty path`() = runTest {
    val railsEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val paths = mutableListOf<String>()
    val job = launch {
        observeAccountConfigSyncChangedPaths(
            heroCatalogSelections = emptyFlow(),
            homeCatalogOrderKeys = emptyFlow(),
            disabledHomeCatalogKeys = emptyFlow(),
            homeCatalogRails = railsEvents,
            tmdbSettings = emptyFlow(),
            mdbListSettings = emptyFlow(),
            mdbListCatalogPreferences = emptyFlow(),
            omdbSettings = emptyFlow(),
            animeSkipEnabled = emptyFlow(),
            subtitleTranslationSettings = emptyFlow(),
            posterRatingsSettings = emptyFlow(),
            premiumizeSettings = emptyFlow(),
            premiumizeAccountState = emptyFlow(),
            torBoxSettings = emptyFlow(),
            torBoxAccountState = emptyFlow(),
            easyDebridSettings = emptyFlow(),
            easyDebridAccountState = emptyFlow(),
            realDebridState = emptyFlow(),
            kitsuAuthState = emptyFlow(),
            traktAuthState = emptyFlow(),
            traktCatalogPreferences = emptyFlow(),
            simklCatalogPreferences = emptyFlow(),
            simklAuthState = emptyFlow(),
            playerSettings = emptyFlow()
        ).take(1).toList(paths)
    }

    railsEvents.emit(Unit)

    assertEquals(listOf("catalogs.home.rails"), paths)
    job.cancel()
}
```

- [ ] **Step 2: Run the failing contract tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest
```

Expected: FAIL because `buildAccountConfigSyncPayload` does not accept `homeCatalogRails`, and `observeAccountConfigSyncChangedPaths` does not accept a `homeCatalogRails` flow.

- [ ] **Step 3: Add rails to the account-config payload builder**

Modify `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`:

```kotlin
import com.nexio.tv.domain.model.HOME_CATALOG_RAILS_VERSION
import com.nexio.tv.domain.model.HomeCatalogRail
```

Add `homeCatalogRails` to `buildAccountConfigSyncPayload` after `disabledHomeCatalogKeys`:

```kotlin
homeCatalogRails: List<HomeCatalogRail>,
```

Set it inside `HomeCatalogSyncSettings`:

```kotlin
home = HomeCatalogSyncSettings(
    railsVersion = HOME_CATALOG_RAILS_VERSION,
    rails = homeCatalogRails,
    heroCatalogKeys = heroCatalogKeys,
    homeCatalogOrderKeys = homeCatalogOrderKeys,
    disabledHomeCatalogKeys = disabledHomeCatalogKeys
),
```

- [ ] **Step 4: Observe rails as the v13 `catalogs.home` dirty path**

Add a `homeCatalogRails: Flow<Unit>` parameter to both `observeAccountConfigSyncChanges` and `observeAccountConfigSyncChangedPaths`.

In `observeAccountConfigSyncChanges`, include it in the `merge` call next to the other home catalog flows:

```kotlin
heroCatalogSelections,
homeCatalogOrderKeys,
disabledHomeCatalogKeys,
homeCatalogRails,
```

In `observeAccountConfigSyncChangedPaths`, map it to the exact dirty path that v13 section extraction uses:

```kotlin
homeCatalogRails.map { "catalogs.home.rails" },
```

Keep the existing legacy mappings for `catalogs.home.homeCatalogOrderKeys` and `catalogs.home.disabledHomeCatalogKeys`; those remain migration/compatibility fields in the same `catalogs.home` section.

- [ ] **Step 5: Wire rails into account sync service local read/apply**

In `AccountSettingsSyncService.observeLocalChanges`, pass the new flow:

```kotlin
homeCatalogRails = layoutPreferenceDataStore.homeCatalogRails.drop(1).map { Unit },
```

In `buildLocalPayload`, read rails only for the default legacy profile:

```kotlin
val homeCatalogRails = if (isPrimaryProfile) layoutPreferenceDataStore.homeCatalogRails.first() else emptyList()
```

Pass it into `buildAccountConfigSyncPayload`:

```kotlin
homeCatalogRails = homeCatalogRails,
```

In `applyCatalogsSection`, write incoming rails before applying the legacy order fields:

```kotlin
catalogs.home?.let { home ->
    home.rails?.let { layoutPreferenceDataStore.setHomeCatalogRails(it) }
    home.heroCatalogKeys?.let { layoutPreferenceDataStore.setHeroCatalogKeys(it) }
    home.homeCatalogOrderKeys?.let { layoutPreferenceDataStore.setHomeCatalogOrderKeys(it) }
    home.disabledHomeCatalogKeys?.let { layoutPreferenceDataStore.setDisabledHomeCatalogKeys(it) }
}
```

This ensures Android pulls profile 1/default-profile rails from the v13 `catalogs.home` section and pushes local visible-list changes back as the same section.

- [ ] **Step 6: Run focused Android sync tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest --tests com.nexio.tv.data.local.LayoutPreferenceDataStoreHomeCatalogRailsTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt
git commit -m "feat(android): sync home catalog rails through v13 catalogs home"
```

## Task 3: Android Rail Contract Helpers And Legacy Migration

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeCatalogRailContract.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeCatalogRailContractTest.kt`

- [ ] **Step 1: Write the failing contract tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeCatalogRailContractTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.domain.model.HomeCatalogRail
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCatalogRailContractTest {
    @Test
    fun `visible keys follow rails order and skip unknown unavailable and disabled definitions`() {
        val definitions = listOf(
            definition("tmdb_trending_movies", "TMDB Trending", enabled = true),
            definition("kitsu_trending_anime", "Kitsu Trending", enabled = true),
            definition("trakt_up_next", "Up Next", enabled = false)
        )
        val rails = listOf(
            rail("kitsu_trending_anime"),
            rail("unknown_key"),
            rail("trakt_up_next"),
            rail("tmdb_trending_movies")
        )

        assertEquals(
            listOf(HomeRailKey("kitsu_trending_anime"), HomeRailKey("tmdb_trending_movies")),
            visibleHomeRailKeysFromRails(rails, definitions)
        )
    }

    @Test
    fun `migration preserves effective visible order and titles from definitions`() {
        val definitions = listOf(
            definition("tmdb_trending_movies", "TMDB Trending", enabled = true),
            definition("kitsu_trending_anime", "Kitsu Trending", enabled = true)
        )
        val effective = EffectiveHomeRailOrder.Empty.copy(
            visibleKeys = listOf(HomeRailKey("kitsu_trending_anime"), HomeRailKey("tmdb_trending_movies")),
            disabledKeys = emptySet()
        )

        val rails = migrateHomeCatalogRailsFromEffectiveOrder(effective, definitions, nowMs = 1778544000000L)

        assertEquals(listOf("kitsu_trending_anime", "tmdb_trending_movies"), rails.map { it.key })
        assertEquals(listOf("Kitsu Trending", "TMDB Trending"), rails.map { it.title })
        assertEquals(listOf(1778544000000L, 1778544000000L), rails.map { it.addedAtMs })
    }

    private fun rail(key: String) = HomeCatalogRail(
        key = key,
        family = "",
        source = "",
        title = "",
        enabled = true
    )

    private fun definition(key: String, title: String, enabled: Boolean) = HomeRailDefinition(
        key = HomeRailKey(key),
        family = RailFamily.fromOrderKey(key),
        source = if (RailFamily.fromOrderKey(key) == RailFamily.ADDON) RailSource.ADDON_CATALOG else RailSource.PROVIDER_PUBLIC,
        title = title,
        enabled = enabled,
        defaultSortKey = DefaultSortKey(RailFamily.fromOrderKey(key).familyRank, 0),
        publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY
    )
}
```

- [ ] **Step 2: Run the failing contract tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.order.HomeCatalogRailContractTest
```

Expected: FAIL because `visibleHomeRailKeysFromRails` and `migrateHomeCatalogRailsFromEffectiveOrder` do not exist.

- [ ] **Step 3: Add the contract helper**

Create `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeCatalogRailContract.kt`:

```kotlin
package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.domain.model.HomeCatalogRail
import com.nexio.tv.domain.model.homeCatalogRailFamilyForKey
import com.nexio.tv.domain.model.homeCatalogRailSourceForFamily
import com.nexio.tv.domain.model.sanitizeHomeCatalogRails

internal fun visibleHomeRailKeysFromRails(
    rails: List<HomeCatalogRail>,
    liveDefinitions: List<HomeRailDefinition>
): List<HomeRailKey> {
    if (rails.isEmpty()) return emptyList()
    val enabledLiveByKey = liveDefinitions
        .asSequence()
        .filter { it.enabled }
        .associateBy { it.key.value }
    return sanitizeHomeCatalogRails(rails)
        .asSequence()
        .filter { it.enabled }
        .mapNotNull { rail -> enabledLiveByKey[rail.key]?.key }
        .toList()
}

internal fun migrateHomeCatalogRailsFromEffectiveOrder(
    effectiveOrder: EffectiveHomeRailOrder,
    liveDefinitions: List<HomeRailDefinition>,
    nowMs: Long
): List<HomeCatalogRail> {
    val definitionsByKey = liveDefinitions.associateBy { it.key }
    val migrated = effectiveOrder.visibleKeys.mapNotNull { key ->
        val definition = definitionsByKey[key]?.takeIf { it.enabled } ?: return@mapNotNull null
        homeCatalogRailFromDefinition(definition, nowMs)
    }
    return sanitizeHomeCatalogRails(migrated)
}

internal fun homeCatalogRailFromDefinition(
    definition: HomeRailDefinition,
    nowMs: Long? = null
): HomeCatalogRail {
    val key = definition.key.value
    val family = homeCatalogRailFamilyForKey(key)
    return HomeCatalogRail(
        key = key,
        family = family,
        source = homeCatalogRailSourceForFamily(family),
        title = definition.title,
        enabled = true,
        addedAtMs = nowMs
    )
}
```

- [ ] **Step 4: Run the contract tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.order.HomeCatalogRailContractTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeCatalogRailContract.kt app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeCatalogRailContractTest.kt
git commit -m "feat(android): add home catalog rails contract helpers"
```

## Task 4: Android Modern Home Reads `homeCatalogRails` First

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRailsProjectionTest.kt`

- [ ] **Step 1: Write the failing projection test**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRailsProjectionTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.HomeCatalogRail
import com.nexio.tv.ui.screens.home.order.DefaultSortKey
import com.nexio.tv.ui.screens.home.order.EffectiveHomeRailOrder
import com.nexio.tv.ui.screens.home.order.HomeRailDefinition
import com.nexio.tv.ui.screens.home.order.HomeRailKey
import com.nexio.tv.ui.screens.home.order.RailFamily
import com.nexio.tv.ui.screens.home.order.RailPublishPolicy
import com.nexio.tv.ui.screens.home.order.RailSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCatalogRailsProjectionTest {
    @Test
    fun `home rails override legacy effective order when present`() {
        val liveDefinitions = listOf(
            definition("tmdb_trending_movies", "TMDB Trending"),
            definition("kitsu_trending_anime", "Kitsu Trending")
        )
        val legacyEffective = EffectiveHomeRailOrder.Empty.copy(
            visibleKeys = listOf(HomeRailKey("tmdb_trending_movies"), HomeRailKey("kitsu_trending_anime"))
        )
        val rails = listOf(
            HomeCatalogRail(key = "kitsu_trending_anime", family = "kitsu", source = "provider_catalog", title = "Kitsu Trending"),
            HomeCatalogRail(key = "tmdb_trending_movies", family = "tmdb", source = "provider_catalog", title = "TMDB Trending")
        )

        val result = resolveEffectiveHomeOrderForCatalogRails(
            configuredRails = rails,
            liveDefinitions = liveDefinitions,
            legacyEffectiveOrder = legacyEffective
        )

        assertEquals(listOf(HomeRailKey("kitsu_trending_anime"), HomeRailKey("tmdb_trending_movies")), result.visibleKeys)
    }

    private fun definition(key: String, title: String) = HomeRailDefinition(
        key = HomeRailKey(key),
        family = RailFamily.fromOrderKey(key),
        source = RailSource.PROVIDER_PUBLIC,
        title = title,
        enabled = true,
        defaultSortKey = DefaultSortKey(RailFamily.fromOrderKey(key).familyRank, 0),
        publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY
    )
}
```

- [ ] **Step 2: Run the failing projection test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRailsProjectionTest
```

Expected: FAIL because `resolveEffectiveHomeOrderForCatalogRails` does not exist.

- [ ] **Step 3: Add the projection resolver**

Add this function to `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` near the other order helpers:

```kotlin
internal fun resolveEffectiveHomeOrderForCatalogRails(
    configuredRails: List<com.nexio.tv.domain.model.HomeCatalogRail>,
    liveDefinitions: List<com.nexio.tv.ui.screens.home.order.HomeRailDefinition>,
    legacyEffectiveOrder: com.nexio.tv.ui.screens.home.order.EffectiveHomeRailOrder
): com.nexio.tv.ui.screens.home.order.EffectiveHomeRailOrder {
    val visibleKeys = com.nexio.tv.ui.screens.home.order.visibleHomeRailKeysFromRails(
        rails = configuredRails,
        liveDefinitions = liveDefinitions
    )
    if (visibleKeys.isEmpty() && configuredRails.isEmpty()) return legacyEffectiveOrder
    return legacyEffectiveOrder.copy(
        visibleKeys = visibleKeys,
        newlyDiscoveredKeys = emptyList(),
        prunedKeys = legacyEffectiveOrder.visibleKeys.filter { it !in visibleKeys }
    )
}
```

- [ ] **Step 4: Observe rails in `HomeViewModel`**

Add a cache field in `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt` near `homeCatalogOrderKeys`:

```kotlin
internal var homeCatalogRails: List<com.nexio.tv.domain.model.HomeCatalogRail> = emptyList()
```

Modify `loadHomeCatalogOrderPreferencePipeline()` in `HomeViewModelCatalogPipeline.kt` so it also collects `layoutPreferenceDataStore.homeCatalogRails`:

```kotlin
viewModelScope.launch {
    layoutPreferenceDataStore.homeCatalogRails.collectLatest { rails ->
        homeCatalogRails = rails
        lastCatalogOrderDiagnosticsSignature = null
        rebuildCatalogOrder(addonsCache)
    }
}
```

- [ ] **Step 5: Use rails in the Modern Home pipeline**

In `HomeViewModelCatalogPipeline.kt`, find the block that computes:

```kotlin
val effectiveOrder = homeRailOrderStore.reconcileNow(liveDefinitions)
```

Replace it with:

```kotlin
val legacyEffectiveOrder = homeRailOrderStore.reconcileNow(liveDefinitions)
val effectiveOrder = resolveEffectiveHomeOrderForCatalogRails(
    configuredRails = homeCatalogRails,
    liveDefinitions = liveDefinitions,
    legacyEffectiveOrder = legacyEffectiveOrder
)
```

- [ ] **Step 6: Run the projection test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRailsProjectionTest
```

Expected: PASS.

- [ ] **Step 7: Run existing home order regression tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.order.HomeRailOrderReconcilerTest --tests com.nexio.tv.ui.screens.home.HomeReactsToSyncReorderTest --tests com.nexio.tv.ui.screens.home.HomeViewModelTmdbCatalogPlanTest --tests com.nexio.tv.ui.screens.home.HomeViewModelKitsuCatalogPlanTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRailsProjectionTest.kt
git commit -m "feat(android): render modern home from shared catalog rails"
```

## Task 5: Android Catalog Management Writes Visible Rails And Add Catalog

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel tests for visible and available rails**

Append to `CatalogOrderViewModelTest.kt`:

```kotlin
    @Test
    fun `catalog management shows home rails as visible and stock rails as add candidates`() = runTest(dispatcher) {
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        every { layoutPreferenceDataStore.homeCatalogOrderKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.disabledHomeCatalogKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.homeCatalogRails } returns MutableStateFlow(
            listOf(
                com.nexio.tv.domain.model.HomeCatalogRail(
                    key = "tmdb_trending_movies",
                    family = "tmdb",
                    source = "provider_catalog",
                    title = "Trending Movies"
                )
            )
        )
        val tmdbCatalogSettingsDataStore = mockk<TmdbCatalogSettingsDataStore>(relaxed = true)
        every { tmdbCatalogSettingsDataStore.catalogPreferences } returns MutableStateFlow(
            TmdbCatalogPreferences(
                enabledCatalogs = setOf("tmdb_trending_movies", "tmdb_popular_movies"),
                catalogOrder = listOf("tmdb_trending_movies", "tmdb_popular_movies")
            )
        )

        val viewModel = buildViewModel(
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            notifier = CatalogPriorityHydrationNotifier(),
            tmdbCatalogSettingsDataStore = tmdbCatalogSettingsDataStore
        )
        advanceUntilIdle()

        assertEquals(listOf("tmdb_trending_movies"), viewModel.uiState.value.items.map { it.key })
        assertTrue(viewModel.uiState.value.availableItems.map { it.key }.contains("tmdb_popular_movies"))
    }

    @Test
    fun `removing rail updates home catalog rails without disabling provider`() = runTest(dispatcher) {
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        every { layoutPreferenceDataStore.homeCatalogOrderKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.disabledHomeCatalogKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.homeCatalogRails } returns MutableStateFlow(
            listOf(
                com.nexio.tv.domain.model.HomeCatalogRail(key = "tmdb_trending_movies", family = "tmdb", source = "provider_catalog", title = "Trending Movies")
            )
        )

        val viewModel = buildViewModel(
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            notifier = CatalogPriorityHydrationNotifier()
        )
        advanceUntilIdle()

        viewModel.removeFromHome("tmdb_trending_movies")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            layoutPreferenceDataStore.setHomeCatalogRails(emptyList())
        }
    }
```

Update the `buildViewModel` helper signature to allow `tmdbCatalogSettingsDataStore` injection:

```kotlin
tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore = mockk(relaxed = true),
```

Remove the local `val tmdbCatalogSettingsDataStore = mockk<TmdbCatalogSettingsDataStore>(relaxed = true)` inside the helper and use the parameter.

- [ ] **Step 2: Run the failing ViewModel tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.addon.CatalogOrderViewModelTest
```

Expected: FAIL because `homeCatalogRails`, `availableItems`, and `removeFromHome` are not wired into the ViewModel.

- [ ] **Step 3: Extend UI state and item model**

Modify `CatalogOrderUiState` in `CatalogOrderViewModel.kt`:

```kotlin
data class CatalogOrderUiState(
    val isLoading: Boolean = true,
    val items: List<CatalogOrderItem> = emptyList(),
    val availableItems: List<CatalogOrderItem> = emptyList(),
    val androidTvChannelsEnabled: Boolean = false,
    val androidTvSelectedFeedKeys: List<String> = emptyList(),
    val androidTvFeedOptions: List<AndroidTvFeedOption> = emptyList()
)
```

Modify `CatalogOrderItem`:

```kotlin
data class CatalogOrderItem(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String,
    val isToggleable: Boolean,
    val isDisabled: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val isUnavailable: Boolean = false
)
```

- [ ] **Step 4: Build visible and available lists from rails**

In `CatalogOrderViewModel.kt`, collect `layoutPreferenceDataStore.homeCatalogRails` in `observeCatalogs()`. Add it to `BaseCatalogOrderInputs`:

```kotlin
val homeCatalogRails: List<com.nexio.tv.domain.model.HomeCatalogRail>
```

When building items, add a local helper named `buildAllCatalogEntries(inputs: CatalogOrderInputs)` that concatenates the current addon, Trakt, SIMKL, MDBList, TMDB, and Kitsu entry builders into one `List<CatalogOrderEntry>`. Pass that list through the visible/available split:

```kotlin
val allEntries = buildAllCatalogEntries(inputs)
val visibleItems = buildVisibleCatalogItems(
    homeCatalogRails = inputs.base.homeCatalogRails,
    allEntries = allEntries
)
val availableItems = buildAvailableCatalogItems(
    homeCatalogRails = inputs.base.homeCatalogRails,
    allEntries = allEntries
)
```

Add these private helpers:

```kotlin
private fun buildVisibleCatalogItems(
    homeCatalogRails: List<com.nexio.tv.domain.model.HomeCatalogRail>,
    allEntries: List<CatalogOrderEntry>
): List<CatalogOrderItem> {
    val entriesByKey = allEntries.associateBy { it.key }
    val visible = com.nexio.tv.domain.model.sanitizeHomeCatalogRails(homeCatalogRails)
        .mapIndexed { index, rail ->
            val entry = entriesByKey[rail.key]
            CatalogOrderItem(
                key = rail.key,
                disableKey = entry?.disableKey ?: rail.key,
                catalogName = entry?.catalogName ?: rail.title.ifBlank { rail.key },
                addonName = entry?.addonName ?: rail.family.ifBlank { "Unavailable" },
                typeLabel = entry?.typeLabel ?: "catalog",
                isToggleable = true,
                isDisabled = false,
                canMoveUp = index > 0,
                canMoveDown = index < homeCatalogRails.lastIndex,
                isUnavailable = entry == null
            )
        }
    return visible
}

private fun buildAvailableCatalogItems(
    homeCatalogRails: List<com.nexio.tv.domain.model.HomeCatalogRail>,
    allEntries: List<CatalogOrderEntry>
): List<CatalogOrderItem> {
    val visibleKeys = homeCatalogRails.mapTo(linkedSetOf()) { it.key }
    return allEntries
        .filter { it.key !in visibleKeys }
        .map { entry ->
            CatalogOrderItem(
                key = entry.key,
                disableKey = entry.disableKey,
                catalogName = entry.catalogName,
                addonName = entry.addonName,
                typeLabel = entry.typeLabel,
                isToggleable = true,
                isDisabled = false,
                canMoveUp = false,
                canMoveDown = false
            )
        }
}
```

- [ ] **Step 5: Replace toggle with add/remove/reorder rail writes**

Add these public methods to `CatalogOrderViewModel.kt`:

```kotlin
fun removeFromHome(key: String) {
    val next = _uiState.value.items
        .filterNot { it.key == key }
        .map { item -> item.toHomeCatalogRail() }
    viewModelScope.launch {
        layoutPreferenceDataStore.setHomeCatalogRails(next)
    }
}

fun addToHome(key: String) {
    val existing = _uiState.value.items.map { it.toHomeCatalogRail() }
    val candidate = _uiState.value.availableItems.firstOrNull { it.key == key } ?: return
    viewModelScope.launch {
        layoutPreferenceDataStore.setHomeCatalogRails(existing + candidate.toHomeCatalogRail())
        catalogPriorityHydrationNotifier.notifyPriorityHydrationRequired()
    }
}

private fun CatalogOrderItem.toHomeCatalogRail(): com.nexio.tv.domain.model.HomeCatalogRail {
    val family = com.nexio.tv.domain.model.homeCatalogRailFamilyForKey(key)
    return com.nexio.tv.domain.model.HomeCatalogRail(
        key = key,
        family = family,
        source = com.nexio.tv.domain.model.homeCatalogRailSourceForFamily(family),
        title = catalogName,
        enabled = true,
        addedAtMs = System.currentTimeMillis()
    )
}
```

Change `moveCatalog` to write `setHomeCatalogRails(reorderedItems.map { it.toHomeCatalogRail() })` instead of `homeRailOrderStore.updateOrder`.

- [ ] **Step 6: Update `CatalogOrderScreen` actions**

In `CatalogOrderScreen.kt`:

- Replace `onToggleEnabled = { viewModel.toggleCatalogEnabled(item.disableKey) }` with `onRemove = { viewModel.removeFromHome(item.key) }`.
- Add `showAddCatalogDialog` state.
- Add an Add Catalog button in the header.
- Add a dialog listing `uiState.availableItems` and calling `viewModel.addToHome(item.key)`.

Use these strings:

```xml
<string name="catalog_order_add_catalog">Add Catalog</string>
<string name="catalog_order_remove_from_home">Remove</string>
<string name="catalog_order_unavailable">Unavailable</string>
<string name="catalog_order_add_empty">No catalogs available to add.</string>
```

- [ ] **Step 7: Run the ViewModel tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.addon.CatalogOrderViewModelTest
```

Expected: PASS.

- [ ] **Step 8: Build the app module**

Run:

```bash
./gradlew :app:assembleUniversalDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderScreen.kt app/src/main/res/values/strings.xml app/src/test/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModelTest.kt
git commit -m "feat(android): manage visible home catalog rails"
```

## Task 6: Web Contract Types, V13 Section Helpers, And Secondary Profile Blob Bridge

**Files:**
- Modify: `nexio-web/types/portal.ts`
- Modify: `nexio-web/utils/portal-defaults.ts`
- Modify: `nexio-web/utils/portal-settings.ts`
- Modify: `nexio-web/utils/account-settings-sections.ts`
- Modify: `nexio-web/server/utils/profile-settings-blob.ts`
- Modify: `nexio-web/utils/portal-sync-paths.ts`
- Modify: `nexio-web/tests/account-settings-sections.test.ts`
- Modify: `nexio-web/tests/profile-settings-blob.test.ts`
- Modify: `nexio-web/tests/portal-contract-v4.test.ts`
- Modify: `nexio-web/tests/portal-sync-paths.test.ts`

- [ ] **Step 1: Write failing web v13 and profile-blob contract tests**

Append to `nexio-web/tests/portal-contract-v4.test.ts`:

```ts
test('default settings expose home rails and TMDB Kitsu catalog sections', () => {
  const settings = defaultSettings()

  assert.equal(settings.schemaVersion, 13)
  assert.equal(settings.catalogs.home.railsVersion, 1)
  assert.deepEqual(settings.catalogs.home.rails, [])
  assert.equal(settings.catalogs.tmdb.catalogOrder.length, 8)
  assert.deepEqual(settings.catalogs.tmdb.catalogEnabledSet, [
    'tmdb_trending_movies',
    'tmdb_trending_series',
    'tmdb_popular_movies',
    'tmdb_popular_series'
  ])
  assert.equal(settings.catalogs.kitsu.catalogOrder.length, 9)
  assert.deepEqual(settings.catalogs.kitsu.catalogEnabledSet, [])
})
```

Ensure `nexio-web/tests/account-settings-sections.test.ts` uses the repo's `node:test` style. Create the file with these imports and helper when it does not exist; when the v13 plan already created it, keep any existing tests and add these imports/helper if absent:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { defaultSettings } from '../utils/portal-defaults.ts'
import type { PortalSettings } from '../types/portal.ts'
import {
  composePortalSettingsFromSections,
  dirtyPathsToSectionKeys,
  extractPortalSettingsSections
} from '../utils/account-settings-sections.ts'

function portalSettings(overrides: Partial<PortalSettings> = {}): PortalSettings {
  const settings = defaultSettings()
  return {
    ...settings,
    ...overrides,
    catalogs: {
      ...settings.catalogs,
      ...(overrides.catalogs ?? {}),
      home: {
        ...settings.catalogs.home,
        ...(overrides.catalogs?.home ?? {})
      },
      tmdb: {
        ...settings.catalogs.tmdb,
        ...(overrides.catalogs?.tmdb ?? {})
      },
      kitsu: {
        ...settings.catalogs.kitsu,
        ...(overrides.catalogs?.kitsu ?? {})
      }
    }
  }
}
```

Append to `nexio-web/tests/account-settings-sections.test.ts`:

```ts
test('v13 sections extract and compose home rails and stock catalog settings', () => {
  const rails = [
    {
      key: 'tmdb_trending_movies',
      family: 'tmdb',
      source: 'provider_catalog',
      title: 'Trending Movies',
      enabled: true,
      addedAtMs: 1778544000000
    }
  ] as const
  const settings = portalSettings({
    catalogs: {
      ...defaultSettings().catalogs,
      home: {
        ...defaultSettings().catalogs.home,
        railsVersion: 1,
        rails: [...rails]
      },
      tmdb: {
        catalogEnabledSet: ['tmdb_trending_movies'],
        catalogOrder: ['tmdb_trending_movies', 'tmdb_popular_movies']
      },
      kitsu: {
        catalogEnabledSet: ['kitsu_trending_anime'],
        catalogOrder: ['kitsu_trending_anime', 'kitsu_popular_anime']
      }
    }
  })

  const sectionKeys = dirtyPathsToSectionKeys([
    'catalogs.home.rails',
    'catalogs.tmdb.catalogOrder',
    'catalogs.kitsu.catalogEnabledSet'
  ])
  const sections = extractPortalSettingsSections(settings, sectionKeys)
  const composed = composePortalSettingsFromSections(defaultSettings(), sections)

  assert.deepEqual(sectionKeys, ['catalogs.home', 'catalogs.tmdb', 'catalogs.kitsu'])
  assert.deepEqual(sections.map((section) => section.section_key), ['catalogs.home', 'catalogs.tmdb', 'catalogs.kitsu'])
  assert.deepEqual(sections[0].payload, settings.catalogs.home)
  assert.deepEqual(composed.catalogs.home.rails, settings.catalogs.home.rails)
  assert.deepEqual(composed.catalogs.tmdb.catalogEnabledSet, ['tmdb_trending_movies'])
  assert.deepEqual(composed.catalogs.kitsu.catalogEnabledSet, ['kitsu_trending_anime'])
})
```

Append to `nexio-web/tests/profile-settings-blob.test.ts`:

```ts
test('secondary profile settings blob round-trips home catalog rails through Android layout preference', () => {
  const settings = portalSettings({
    catalogs: {
      ...defaultSettings().catalogs,
      home: {
        ...defaultSettings().catalogs.home,
        railsVersion: 1,
        rails: [
          {
            key: 'tmdb_trending_movies',
            family: 'tmdb',
            source: 'provider_catalog',
            title: 'Trending Movies',
            enabled: true,
            addedAtMs: 1778544000000
          }
        ]
      }
    }
  })

  const merged = mergePortalSettingsIntoProfileSettingsBlob(settings, {}, ['catalogs.home.rails'])
  assert.deepEqual(
    (merged.layout_settings as Record<string, unknown>).home_catalog_rails_json,
    {
      type: 'string',
      value: '[{"key":"tmdb_trending_movies","family":"tmdb","source":"provider_catalog","title":"Trending Movies","enabled":true,"addedAtMs":1778544000000}]'
    }
  )

  const decoded = portalSettingsFromProfileSettingsBlob(merged)
  assert.deepEqual(decoded.catalogs.home.rails, settings.catalogs.home.rails)
})
```

Append to `nexio-web/tests/portal-sync-paths.test.ts`:

```ts
test('canonical sync paths include home rails and stock catalog sections', () => {
  assert.equal(canonicalPortalSyncPath('catalogs.home.rails'), 'catalogs.home.rails')
  assert.equal(canonicalPortalSyncPath('catalogs.home.railsVersion'), 'catalogs.home.railsVersion')
  assert.equal(canonicalPortalSyncPath('catalogs.tmdb.catalogEnabledSet'), 'catalogs.tmdb.catalogEnabledSet')
  assert.equal(canonicalPortalSyncPath('catalogs.kitsu.catalogOrder'), 'catalogs.kitsu.catalogOrder')
})
```

- [ ] **Step 2: Run the failing web contract tests**

Run:

```bash
cd nexio-web
npx tsx --test tests/portal-contract-v4.test.ts tests/account-settings-sections.test.ts tests/profile-settings-blob.test.ts tests/portal-sync-paths.test.ts
```

Expected: FAIL because the new types/defaults, v13 section mappings, and secondary-profile blob mappings are missing.

- [ ] **Step 3: Add web rail and stock catalog types**

Modify `nexio-web/types/portal.ts`:

```ts
export const ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 13

export type TmdbCatalogId =
  | 'tmdb_trending_movies'
  | 'tmdb_trending_series'
  | 'tmdb_popular_movies'
  | 'tmdb_popular_series'
  | 'tmdb_year_movies'
  | 'tmdb_year_series'
  | 'tmdb_language_movies'
  | 'tmdb_language_series'

export type KitsuCatalogId =
  | 'kitsu_trending_anime'
  | 'kitsu_highest_rated_anime'
  | 'kitsu_popular_anime'
  | 'kitsu_popular_action_anime'
  | 'kitsu_popular_drama_anime'
  | 'kitsu_popular_comedy_anime'
  | 'kitsu_popular_fantasy_anime'
  | 'kitsu_popular_romance_anime'
  | 'kitsu_popular_adventure_anime'

export type HomeCatalogRail = {
  key: string
  family: 'addon' | 'trakt' | 'simkl' | 'mdblist' | 'tmdb' | 'kitsu'
  source: 'addon_catalog' | 'provider_catalog' | 'provider_list'
  title: string
  enabled: true
  addedAtMs?: number
}
```

Update `AddonCatalogRecord.source`:

```ts
source: 'addon' | 'trakt' | 'simkl' | 'mdblist' | 'tmdb' | 'kitsu'
```

Update catalog settings:

```ts
export type HomeCatalogSyncSettings = {
  railsVersion: 1
  rails: HomeCatalogRail[]
  heroCatalogKeys: string[]
  homeCatalogOrderKeys: string[]
  disabledHomeCatalogKeys: string[]
}

export type TmdbCatalogSyncSettings = {
  catalogEnabledSet: TmdbCatalogId[]
  catalogOrder: TmdbCatalogId[]
}

export type KitsuCatalogSyncSettings = {
  catalogEnabledSet: KitsuCatalogId[]
  catalogOrder: KitsuCatalogId[]
}

export type PortalCatalogs = {
  home: HomeCatalogSyncSettings
  trakt: TraktCatalogSyncSettings
  simkl: SimklCatalogSyncSettings
  mdblist: MDBListCatalogSyncSettings
  tmdb: TmdbCatalogSyncSettings
  kitsu: KitsuCatalogSyncSettings
}
```

If the v13 plan already changed `ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION` to `13`, leave that line as-is. This task must not set the constant back to `12`.

- [ ] **Step 4: Add defaults**

Modify `nexio-web/utils/portal-defaults.ts`:

```ts
import type {
  AddonRecord,
  TraktCatalogId,
  SimklCatalogId,
  TmdbCatalogId,
  KitsuCatalogId,
  SubtitleTranslationProvider,
  PortalSettings
} from '../types/portal.ts'
```

Add:

```ts
export const defaultTmdbCatalogOrder: TmdbCatalogId[] = [
  'tmdb_trending_movies',
  'tmdb_trending_series',
  'tmdb_popular_movies',
  'tmdb_popular_series',
  'tmdb_year_movies',
  'tmdb_year_series',
  'tmdb_language_movies',
  'tmdb_language_series'
]

export const defaultKitsuCatalogOrder: KitsuCatalogId[] = [
  'kitsu_trending_anime',
  'kitsu_highest_rated_anime',
  'kitsu_popular_anime',
  'kitsu_popular_action_anime',
  'kitsu_popular_drama_anime',
  'kitsu_popular_comedy_anime',
  'kitsu_popular_fantasy_anime',
  'kitsu_popular_romance_anime',
  'kitsu_popular_adventure_anime'
]
```

Update `defaultSettings().catalogs`:

```ts
home: {
  railsVersion: 1,
  rails: [],
  heroCatalogKeys: [],
  homeCatalogOrderKeys: [],
  disabledHomeCatalogKeys: []
},
tmdb: {
  catalogEnabledSet: [
    'tmdb_trending_movies',
    'tmdb_trending_series',
    'tmdb_popular_movies',
    'tmdb_popular_series'
  ],
  catalogOrder: defaultTmdbCatalogOrder
},
kitsu: {
  catalogEnabledSet: [],
  catalogOrder: defaultKitsuCatalogOrder
}
```

- [ ] **Step 5: Sanitize the new settings**

In `nexio-web/utils/portal-settings.ts`, add helpers:

```ts
function sanitizeHomeCatalogRails(value: unknown): PortalSettings['catalogs']['home']['rails'] {
  if (!Array.isArray(value)) return []
  const seen = new Set<string>()
  const rails: PortalSettings['catalogs']['home']['rails'] = []
  for (const item of value) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue
    const record = item as Record<string, unknown>
    const key = typeof record.key === 'string' ? record.key.trim() : ''
    if (!key || seen.has(key)) continue
    seen.add(key)
    rails.push({
      key,
      family: sanitizeRailFamily(record.family, key),
      source: sanitizeRailSource(record.source, key),
      title: typeof record.title === 'string' ? record.title.trim() : key,
      enabled: true,
      ...(typeof record.addedAtMs === 'number' ? { addedAtMs: record.addedAtMs } : {})
    })
  }
  return rails
}

function sanitizeRailFamily(value: unknown, key: string): PortalSettings['catalogs']['home']['rails'][number]['family'] {
  if (value === 'addon' || value === 'trakt' || value === 'simkl' || value === 'mdblist' || value === 'tmdb' || value === 'kitsu') return value
  if (key.startsWith('tmdb_')) return 'tmdb'
  if (key.startsWith('kitsu_')) return 'kitsu'
  if (key.startsWith('trakt_')) return 'trakt'
  if (key.startsWith('simkl_')) return 'simkl'
  if (key.startsWith('mdblist_') || key.startsWith('top:') || key.startsWith('personal:')) return 'mdblist'
  return 'addon'
}

function sanitizeRailSource(value: unknown, key: string): PortalSettings['catalogs']['home']['rails'][number]['source'] {
  if (value === 'addon_catalog' || value === 'provider_catalog' || value === 'provider_list') return value
  const family = sanitizeRailFamily(undefined, key)
  if (family === 'addon') return 'addon_catalog'
  if (family === 'mdblist') return 'provider_list'
  return 'provider_catalog'
}
```

Use `sanitizeHomeCatalogRails(input?.catalogs?.home?.rails)` in the returned `catalogs.home`, and sanitize TMDB/Kitsu with `normalizedKnownStringList` and `normalizedKnownOrder`.

- [ ] **Step 6: Add the v13 section mappings for profile 1**

Modify `nexio-web/utils/account-settings-sections.ts`.

Ensure the section-key union includes:

```ts
export type AccountSettingsSectionKey =
  | 'integrations.subtitleTranslation'
  | 'integrations.tmdb'
  | 'integrations.omdb'
  | 'integrations.posterRatings'
  | 'integrations.animeSkip'
  | 'integrations.mdblist'
  | 'integrations.kitsu'
  | 'integrations.traktAuth'
  | 'integrations.simklAuth'
  | 'integrations.kitsuAuth'
  | 'integrations.debrid.premiumize'
  | 'integrations.debrid.realDebrid'
  | 'integrations.debrid.torBox'
  | 'integrations.debrid.easyDebrid'
  | 'catalogs.mdblist'
  | 'catalogs.trakt'
  | 'catalogs.simkl'
  | 'catalogs.tmdb'
  | 'catalogs.kitsu'
  | 'catalogs.home'
  | 'playback.streamSelection'
  | 'formatter'
```

Ensure `payloadFor` handles catalog sections:

```ts
function payloadFor(settings: PortalSettings, sectionKey: AccountSettingsSectionKey): Record<string, unknown> | null {
  switch (sectionKey) {
    case 'catalogs.home': return settings.catalogs.home
    case 'catalogs.tmdb': return settings.catalogs.tmdb
    case 'catalogs.kitsu': return settings.catalogs.kitsu
    case 'catalogs.trakt': return settings.catalogs.trakt
    case 'catalogs.simkl': return settings.catalogs.simkl
    case 'catalogs.mdblist': return settings.catalogs.mdblist
    case 'formatter': return settings.formatter
    case 'playback.streamSelection': return settings.playback.streamSelection
    case 'integrations.subtitleTranslation': return settings.integrations.subtitleTranslation
    case 'integrations.tmdb': return settings.integrations.tmdb
    case 'integrations.omdb': return settings.integrations.omdb
    case 'integrations.posterRatings': return settings.integrations.posterRatings
    case 'integrations.animeSkip': return settings.integrations.animeSkip
    case 'integrations.mdblist': return settings.integrations.mdblist
    case 'integrations.kitsu': return settings.integrations.kitsu
    case 'integrations.traktAuth': return settings.integrations.traktAuth
    case 'integrations.simklAuth': return settings.integrations.simklAuth
    case 'integrations.kitsuAuth': return settings.integrations.kitsuAuth
    case 'integrations.debrid.premiumize': return settings.integrations.debrid.premiumize
    case 'integrations.debrid.realDebrid': return settings.integrations.debrid.realDebrid
    case 'integrations.debrid.torBox': return settings.integrations.debrid.torBox
    case 'integrations.debrid.easyDebrid': return settings.integrations.debrid.easyDebrid
  }
}
```

Ensure `composePortalSettingsFromSections` handles the three catalog sections:

```ts
case 'catalogs.home':
  next.catalogs.home = sanitizePortalSettings({
    ...next,
    catalogs: {
      ...next.catalogs,
      home: { ...next.catalogs.home, ...payload }
    }
  }).catalogs.home
  break
case 'catalogs.tmdb':
  next.catalogs.tmdb = sanitizePortalSettings({
    ...next,
    catalogs: {
      ...next.catalogs,
      tmdb: { ...next.catalogs.tmdb, ...payload }
    }
  }).catalogs.tmdb
  break
case 'catalogs.kitsu':
  next.catalogs.kitsu = sanitizePortalSettings({
    ...next,
    catalogs: {
      ...next.catalogs,
      kitsu: { ...next.catalogs.kitsu, ...payload }
    }
  }).catalogs.kitsu
  break
```

Ensure dirty paths map to their owning sections:

```ts
export function dirtyPathsToSectionKeys(paths: string[]): AccountSettingsSectionKey[] {
  const keys = new Set<AccountSettingsSectionKey>()
  for (const path of paths) {
    const normalized = path.trim()
    const match = validAccountSettingsSectionKeys
      .filter((sectionKey) => normalized === sectionKey || normalized.startsWith(`${sectionKey}.`))
      .sort((a, b) => b.length - a.length)[0]
    if (match) keys.add(match)
  }
  return [...keys]
}
```

After this step, profile 1 writes to `catalogs.home`, `catalogs.tmdb`, and `catalogs.kitsu` through `sync_push_account_settings_sections_v13`.

- [ ] **Step 7: Map rails in the secondary-profile blob bridge**

Modify `nexio-web/server/utils/profile-settings-blob.ts`:

```ts
const TMDB_SETTINGS = 'tmdb_catalog_settings'
const KITSU_SETTINGS = 'kitsu_catalog_settings'
const HOME_CATALOG_RAILS_PATH = 'catalogs.home.rails'
const HOME_CATALOG_RAILS_VERSION_PATH = 'catalogs.home.railsVersion'
const TMDB_CATALOG_PATH = 'catalogs.tmdb'
const KITSU_CATALOG_PATH = 'catalogs.kitsu'
const HOME_CATALOG_RAILS_KEY = 'home_catalog_rails_json'
```

Add `HOME_CATALOG_RAILS_PATH`, `HOME_CATALOG_RAILS_VERSION_PATH`, `TMDB_CATALOG_PATH`, and `KITSU_CATALOG_PATH` to `WEB_MANAGED_PATHS`.

In `decodeAndroidBlob`, read:

```ts
const tmdbSettings = rawRecord(raw[TMDB_SETTINGS])
const kitsuSettings = rawRecord(raw[KITSU_SETTINGS])
const homeCatalogRails = parseEncodedJsonList(layoutSettings, HOME_CATALOG_RAILS_KEY)
```

Add this parser:

```ts
function parseEncodedJsonList(feature: JsonRecord, key: string): unknown[] | undefined {
  const raw = encodedStringValue(feature, key)
  if (raw === undefined) return undefined
  try {
    const parsed = JSON.parse(raw) as unknown
    return Array.isArray(parsed) ? parsed : undefined
  } catch {
    return undefined
  }
}
```

In the decoded home settings:

```ts
railsVersion: 1,
rails: homeCatalogRails ?? defaults.catalogs.home.rails,
```

Decode TMDB/Kitsu exactly like Trakt/SIMKL using their default orders.

In `mergePortalSettingsIntoProfileSettingsBlob`, when `shouldUpdate(HOME_CATALOG_RAILS_PATH)` or `shouldUpdate(HOME_CATALOG_RAILS_VERSION_PATH)`:

```ts
layoutSettings[HOME_CATALOG_RAILS_KEY] = encodedString(
  JSON.stringify(sanitizedSettings.catalogs.home.rails)
)
```

Write TMDB/Kitsu `catalog_enabled_set` and `catalog_order_csv` into `tmdb_catalog_settings` and `kitsu_catalog_settings`, then assign them to `merged[TMDB_SETTINGS]` and `merged[KITSU_SETTINGS]`.

- [ ] **Step 8: Add sync paths**

In `nexio-web/utils/portal-sync-paths.ts`, add:

```ts
'catalogs.home.railsVersion',
'catalogs.home.rails',
'catalogs.tmdb',
'catalogs.tmdb.catalogEnabledSet',
'catalogs.tmdb.catalogOrder',
'catalogs.kitsu',
'catalogs.kitsu.catalogEnabledSet',
'catalogs.kitsu.catalogOrder',
```

- [ ] **Step 9: Run web contract tests**

Run:

```bash
cd nexio-web
npx tsx --test tests/portal-contract-v4.test.ts tests/account-settings-sections.test.ts tests/profile-settings-blob.test.ts tests/portal-sync-paths.test.ts
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add nexio-web/types/portal.ts nexio-web/utils/portal-defaults.ts nexio-web/utils/portal-settings.ts nexio-web/utils/account-settings-sections.ts nexio-web/server/utils/profile-settings-blob.ts nexio-web/utils/portal-sync-paths.ts nexio-web/tests/account-settings-sections.test.ts nexio-web/tests/profile-settings-blob.test.ts nexio-web/tests/portal-contract-v4.test.ts nexio-web/tests/portal-sync-paths.test.ts
git commit -m "feat(web): add v13 home catalog rails contract"
```

## Task 7: Web Inventory Helpers Include TMDB And Kitsu

**Files:**
- Modify: `nexio-web/utils/portal-metadata.ts`
- Create: `nexio-web/utils/home-catalog-rails.ts`
- Modify: `nexio-web/composables/usePortalStore.ts`
- Modify: `nexio-web/composables/useProfileStore.ts`
- Create: `nexio-web/tests/home-catalog-rails.test.ts`

- [ ] **Step 1: Write failing helper tests**

Create `nexio-web/tests/home-catalog-rails.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { defaultSettings } from '../utils/portal-defaults.ts'
import {
  addHomeCatalogRail,
  availableCatalogsForHomeRails,
  buildKitsuCatalogs,
  buildTmdbCatalogs,
  removeHomeCatalogRail,
  reorderHomeCatalogRails,
  visibleCatalogsForHomeRails
} from '../utils/home-catalog-rails.ts'

test('TMDB and Kitsu stock catalogs are inventory candidates', () => {
  const settings = defaultSettings()
  const tmdb = buildTmdbCatalogs(settings)
  const kitsu = buildKitsuCatalogs(settings)

  assert.ok(tmdb.some((catalog) => catalog.key === 'tmdb_trending_movies'))
  assert.ok(tmdb.some((catalog) => catalog.source === 'tmdb'))
  assert.ok(kitsu.some((catalog) => catalog.key === 'kitsu_trending_anime'))
  assert.ok(kitsu.every((catalog) => catalog.source === 'kitsu'))
})

test('visible and available catalogs are split by home rails', () => {
  const settings = defaultSettings()
  settings.catalogs.home.rails = [
    {
      key: 'tmdb_trending_movies',
      family: 'tmdb',
      source: 'provider_catalog',
      title: 'Trending Movies',
      enabled: true
    }
  ]
  const inventory = buildTmdbCatalogs(settings)

  assert.deepEqual(visibleCatalogsForHomeRails(inventory, settings.catalogs.home.rails).map((catalog) => catalog.key), ['tmdb_trending_movies'])
  assert.equal(availableCatalogsForHomeRails(inventory, settings.catalogs.home.rails).some((catalog) => catalog.key === 'tmdb_trending_movies'), false)
})

test('add remove and reorder mutate rails without provider settings changes', () => {
  const settings = defaultSettings()
  const inventory = buildTmdbCatalogs(settings)
  const withAdded = addHomeCatalogRail(settings.catalogs.home.rails, inventory[0]!)
  const withSecond = addHomeCatalogRail(withAdded, inventory[1]!)
  const reordered = reorderHomeCatalogRails(withSecond, [inventory[1]!.key, inventory[0]!.key])
  const removed = removeHomeCatalogRail(reordered, inventory[1]!.key)

  assert.deepEqual(reordered.map((rail) => rail.key), [inventory[1]!.key, inventory[0]!.key])
  assert.deepEqual(removed.map((rail) => rail.key), [inventory[0]!.key])
  assert.deepEqual(settings.catalogs.tmdb.catalogEnabledSet, defaultSettings().catalogs.tmdb.catalogEnabledSet)
})
```

- [ ] **Step 2: Run the failing helper tests**

Run:

```bash
cd nexio-web
npx tsx --test tests/home-catalog-rails.test.ts
```

Expected: FAIL because `utils/home-catalog-rails.ts` does not exist.

- [ ] **Step 3: Add TMDB/Kitsu labels**

In `nexio-web/utils/portal-metadata.ts`, import types and add:

```ts
import type { KitsuCatalogId, PortalSettings, SimklCatalogId, TmdbCatalogId, TraktCatalogId } from '~/types/portal'
```

```ts
export const tmdbCatalogLabels: Record<TmdbCatalogId, string> = {
  tmdb_trending_movies: 'Trending Movies',
  tmdb_trending_series: 'Trending Series',
  tmdb_popular_movies: 'Popular Movies',
  tmdb_popular_series: 'Popular Series',
  tmdb_year_movies: 'Movies by Year',
  tmdb_year_series: 'Series by Year',
  tmdb_language_movies: 'Movies by Language',
  tmdb_language_series: 'Series by Language'
}

export const kitsuCatalogLabels: Record<KitsuCatalogId, string> = {
  kitsu_trending_anime: 'Trending Anime',
  kitsu_highest_rated_anime: 'Highest Rated Anime',
  kitsu_popular_anime: 'Popular Anime',
  kitsu_popular_action_anime: 'Popular Action Anime',
  kitsu_popular_drama_anime: 'Popular Drama Anime',
  kitsu_popular_comedy_anime: 'Popular Comedy Anime',
  kitsu_popular_fantasy_anime: 'Popular Fantasy Anime',
  kitsu_popular_romance_anime: 'Popular Romance Anime',
  kitsu_popular_adventure_anime: 'Popular Adventure Anime'
}
```

- [ ] **Step 4: Add web home rail helpers**

Create `nexio-web/utils/home-catalog-rails.ts`:

```ts
import type { AddonCatalogRecord, HomeCatalogRail, PortalSettings } from '../types/portal.ts'
import { kitsuCatalogLabels, tmdbCatalogLabels } from './portal-metadata.ts'

export function sanitizeHomeCatalogRails(rails: unknown): HomeCatalogRail[] {
  if (!Array.isArray(rails)) return []
  const seen = new Set<string>()
  const result: HomeCatalogRail[] = []
  for (const value of rails) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) continue
    const record = value as Partial<HomeCatalogRail>
    const key = typeof record.key === 'string' ? record.key.trim() : ''
    if (!key || seen.has(key)) continue
    seen.add(key)
    const family = isRailFamily(record.family) ? record.family : railFamilyForKey(key)
    result.push({
      key,
      family,
      source: isRailSource(record.source) ? record.source : railSourceForKey(key),
      title: typeof record.title === 'string' && record.title.trim() ? record.title.trim() : key,
      enabled: true,
      ...(typeof record.addedAtMs === 'number' ? { addedAtMs: record.addedAtMs } : {})
    })
  }
  return result
}

function isRailFamily(value: unknown): value is HomeCatalogRail['family'] {
  return value === 'addon' || value === 'trakt' || value === 'simkl' || value === 'mdblist' || value === 'tmdb' || value === 'kitsu'
}

function isRailSource(value: unknown): value is HomeCatalogRail['source'] {
  return value === 'addon_catalog' || value === 'provider_catalog' || value === 'provider_list'
}

export function railFamilyForKey(key: string): HomeCatalogRail['family'] {
  if (key.startsWith('tmdb_')) return 'tmdb'
  if (key.startsWith('kitsu_')) return 'kitsu'
  if (key.startsWith('trakt_')) return 'trakt'
  if (key.startsWith('simkl_')) return 'simkl'
  if (key.startsWith('mdblist_') || key.startsWith('top:') || key.startsWith('personal:')) return 'mdblist'
  return 'addon'
}

export function railSourceForKey(key: string): HomeCatalogRail['source'] {
  const family = railFamilyForKey(key)
  if (family === 'addon') return 'addon_catalog'
  if (family === 'mdblist') return 'provider_list'
  return 'provider_catalog'
}

export function catalogToHomeRail(catalog: AddonCatalogRecord, addedAtMs = Date.now()): HomeCatalogRail {
  return {
    key: catalog.key,
    family: railFamilyForKey(catalog.key),
    source: railSourceForKey(catalog.key),
    title: catalog.catalogName || catalog.key,
    enabled: true,
    addedAtMs
  }
}

export function visibleCatalogsForHomeRails(
  inventory: AddonCatalogRecord[],
  rails: HomeCatalogRail[]
): AddonCatalogRecord[] {
  const inventoryByKey = new Map(inventory.map((catalog) => [catalog.key, catalog]))
  return sanitizeHomeCatalogRails(rails)
    .map((rail) => inventoryByKey.get(rail.key))
    .filter((catalog): catalog is AddonCatalogRecord => Boolean(catalog))
}

export function availableCatalogsForHomeRails(
  inventory: AddonCatalogRecord[],
  rails: HomeCatalogRail[]
): AddonCatalogRecord[] {
  const visible = new Set(sanitizeHomeCatalogRails(rails).map((rail) => rail.key))
  return inventory.filter((catalog) => !visible.has(catalog.key))
}

export function addHomeCatalogRail(rails: HomeCatalogRail[], catalog: AddonCatalogRecord): HomeCatalogRail[] {
  return sanitizeHomeCatalogRails([...rails, catalogToHomeRail(catalog)])
}

export function removeHomeCatalogRail(rails: HomeCatalogRail[], key: string): HomeCatalogRail[] {
  return sanitizeHomeCatalogRails(rails).filter((rail) => rail.key !== key)
}

export function reorderHomeCatalogRails(rails: HomeCatalogRail[], orderedKeys: string[]): HomeCatalogRail[] {
  const byKey = new Map(sanitizeHomeCatalogRails(rails).map((rail) => [rail.key, rail]))
  const next: HomeCatalogRail[] = []
  for (const key of orderedKeys) {
    const rail = byKey.get(key)
    if (!rail) continue
    next.push(rail)
    byKey.delete(key)
  }
  return [...next, ...byKey.values()]
}

// Enumerate stock catalog order, not only provider-enabled catalogs. Removing
// a rail only hides it from Modern Home and must not mutate provider settings.
export function buildTmdbCatalogs(settings: PortalSettings): AddonCatalogRecord[] {
  return settings.catalogs.tmdb.catalogOrder
    .map((key) => ({
      key,
      disableKey: '',
      addonId: 'tmdb',
      addonName: 'TMDB',
      addonUrl: 'tmdb://catalogs',
      catalogId: key,
      catalogName: tmdbCatalogLabels[key],
      type: key.endsWith('_movies') ? 'movie' : 'series',
      source: 'tmdb' as const,
      isSearchOnly: false
    }))
}

export function buildKitsuCatalogs(settings: PortalSettings): AddonCatalogRecord[] {
  return settings.catalogs.kitsu.catalogOrder
    .map((key) => ({
      key,
      disableKey: '',
      addonId: 'kitsu',
      addonName: 'Kitsu',
      addonUrl: 'kitsu://catalogs',
      catalogId: key,
      catalogName: kitsuCatalogLabels[key],
      type: 'anime',
      source: 'kitsu' as const,
      isSearchOnly: false
    }))
}
```

- [ ] **Step 5: Use helpers in portal and profile stores**

In `nexio-web/composables/usePortalStore.ts`:

```ts
import {
  addHomeCatalogRail,
  availableCatalogsForHomeRails,
  buildKitsuCatalogs,
  buildTmdbCatalogs,
  removeHomeCatalogRail,
  reorderHomeCatalogRails,
  visibleCatalogsForHomeRails
} from '~/utils/home-catalog-rails'
```

Include TMDB and Kitsu stock catalog records in the full inventory before deriving the visible and addable catalog lists. Add computed values:

```ts
const fullCatalogInventory = computed<AddonCatalogRecord[]>(() => {
  const addonCatalogs = Object.values(state.value.addonInspections)
    .flatMap((inspection) => inspection.catalogs)

  return orderedCatalogs([
    ...addonCatalogs,
    ...buildTraktCatalogs(state.value.settings),
    ...buildSimklCatalogs(state.value.settings),
    ...buildMDBListCatalogs(state.value.settings),
    ...buildTmdbCatalogs(state.value.settings),
    ...buildKitsuCatalogs(state.value.settings)
  ], state.value.settings)
})
const catalogInventory = computed<AddonCatalogRecord[]>(() =>
  visibleCatalogsForHomeRails(fullCatalogInventory.value, state.value.settings.catalogs.home.rails)
)
const availableCatalogInventory = computed<AddonCatalogRecord[]>(() =>
  availableCatalogsForHomeRails(fullCatalogInventory.value, state.value.settings.catalogs.home.rails)
)
```

Change `moveCatalog`, `reorderCatalogs`, and `toggleCatalog` to mutate `state.value.settings.catalogs.home.rails` via the helpers and mark `['catalogs.home.rails']`. Add:

```ts
function addCatalogToHome(key: string) {
  const catalog = availableCatalogInventory.value.find((entry) => entry.key === key)
  if (!catalog) return
  state.value.settings.catalogs.home.rails = addHomeCatalogRail(state.value.settings.catalogs.home.rails, catalog)
  markChangedPaths(['catalogs.home.rails'])
}
```

Mirror the same helper imports and mutations in `nexio-web/composables/useProfileStore.ts`, calling `saveProfileSettings(profileIndex, settings, ['catalogs.home.rails'])`.

- [ ] **Step 6: Run the helper tests**

Run:

```bash
cd nexio-web
npx tsx --test tests/home-catalog-rails.test.ts
```

Expected: PASS.

- [ ] **Step 7: Run store source checks**

Run:

```bash
cd nexio-web
npx vue-tsc --noEmit
```

Expected: exits 0.

- [ ] **Step 8: Commit**

```bash
git add nexio-web/utils/portal-metadata.ts nexio-web/utils/home-catalog-rails.ts nexio-web/composables/usePortalStore.ts nexio-web/composables/useProfileStore.ts nexio-web/tests/home-catalog-rails.test.ts
git commit -m "feat(web): build visible and available home rail inventory"
```

## Task 8: Web Visible List + Add Catalog UI

**Files:**
- Modify: `nexio-web/components/portal/ProfileCatalogsTab.vue`
- Modify: `nexio-web/components/portal/CatalogInventory.vue`
- Create: `nexio-web/tests/catalog-inventory-component.test.ts`

- [ ] **Step 1: Add a source test for the Add Catalog UI contract**

Create `nexio-web/tests/catalog-inventory-component.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

test('CatalogInventory exposes Add Catalog modal contract', () => {
  const source = readFileSync('components/portal/CatalogInventory.vue', 'utf8')

  assert.equal(source.includes('availableCatalogs'), true)
  assert.equal(source.includes(\"emit('add-catalog'\"), true)
  assert.equal(source.includes(\"emit('remove-catalog'\"), true)
  assert.equal(source.includes('showAddCatalog'), true)
})
```

- [ ] **Step 2: Run the failing source test**

Run:

```bash
cd nexio-web
npx tsx --test tests/catalog-inventory-component.test.ts
```

Expected: FAIL because the component does not expose `availableCatalogs`, `add-catalog`, `remove-catalog`, or `showAddCatalog`.

- [ ] **Step 3: Update `ProfileCatalogsTab` props and events**

In `nexio-web/components/portal/ProfileCatalogsTab.vue`, pass available catalogs:

```vue
<CatalogInventory
  v-else
  :catalogs="catalogs"
  :available-catalogs="availableCatalogs"
  :addons="accountAddons"
  :busy="busy"
  @persist="handlePersist"
  @move-catalog="handleMoveCatalog"
  @reorder-catalogs="handleReorderCatalogs"
  @remove-catalog="handleRemoveCatalog"
  @add-catalog="handleAddCatalog"
/>
```

Add computed:

```ts
const availableCatalogs = computed(() => usesAccountCatalogs.value
  ? portalStore.availableCatalogInventory.value
  : profileStore.getProfileAvailableCatalogInventory(props.profileIndex)
)
```

Replace `handleToggleCatalog` with:

```ts
function handleRemoveCatalog(key: string) {
  if (usesAccountCatalogs.value) {
    portalStore.removeCatalogFromHome(key)
    return
  }
  profileStore.removeProfileCatalogFromHome(props.profileIndex, key).catch(() => undefined)
}

function handleAddCatalog(key: string) {
  if (usesAccountCatalogs.value) {
    portalStore.addCatalogToHome(key)
    return
  }
  profileStore.addProfileCatalogToHome(props.profileIndex, key).catch(() => undefined)
}
```

- [ ] **Step 4: Update `CatalogInventory`**

Modify props and emits:

```ts
const props = defineProps<{
  catalogs: AddonCatalogRecord[]
  availableCatalogs: AddonCatalogRecord[]
  addons?: AddonRecord[]
  busy?: boolean
}>()

const emit = defineEmits<{
  persist: []
  'move-catalog': [key: string, direction: -1 | 1]
  'reorder-catalogs': [keys: string[]]
  'remove-catalog': [key: string]
  'add-catalog': [key: string]
}>()
```

Remove the status filter dropdown. Add:

```ts
const showAddCatalog = ref(false)
const addCatalogFilter = ref('')
const filteredAvailableCatalogs = computed(() => {
  const query = addCatalogFilter.value.trim().toLowerCase()
  if (!query) return props.availableCatalogs
  return props.availableCatalogs.filter((catalog) => {
    return [
      catalog.catalogName,
      catalog.addonName,
      catalog.type,
      catalog.key
    ].some((value) => String(value || '').toLowerCase().includes(query))
  })
})
```

Change the row action from toggle enabled to:

```vue
<button
  class="px-4 py-1.5 rounded-lg bg-zinc-900 text-zinc-300 text-[10px] font-bold border border-zinc-800 tracking-tight uppercase whitespace-nowrap hover:bg-zinc-800/80 transition-colors"
  @click="emit('remove-catalog', catalog.key)"
>
  Remove
</button>
```

Add an Add Catalog button:

```vue
<button
  class="px-5 py-2.5 rounded-xl border border-outline-variant/20 text-on-surface-variant text-sm font-semibold hover:bg-surface-container-highest transition-colors flex-1 md:flex-none whitespace-nowrap"
  @click="showAddCatalog = true"
>
  Add Catalog
</button>
```

Add the modal below the draggable list:

```vue
<div v-if="showAddCatalog" class="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-6">
  <div class="w-full max-w-2xl rounded-xl border border-outline-variant/20 bg-surface-container p-5">
    <div class="flex items-center justify-between gap-4">
      <h2 class="font-headline text-xl font-bold text-on-surface">Add Catalog</h2>
      <button class="text-sm text-zinc-400 hover:text-white" @click="showAddCatalog = false">Close</button>
    </div>
    <input v-model="addCatalogFilter" class="mt-4 w-full rounded-lg border border-outline-variant/20 bg-surface-container-low px-3 py-2 text-sm text-on-surface" aria-label="Search catalogs" type="text">
    <div class="mt-4 max-h-[60vh] space-y-2 overflow-y-auto">
      <button
        v-for="catalog in filteredAvailableCatalogs"
        :key="catalog.key"
        class="flex w-full items-center justify-between rounded-lg border border-white/5 bg-[#131313]/60 px-4 py-3 text-left hover:border-primary/30"
        @click="emit('add-catalog', catalog.key); showAddCatalog = false"
      >
        <span>
          <span class="block text-sm font-bold text-on-surface">{{ displayCatalogName(catalog) }}</span>
          <span class="block text-xs text-zinc-500">{{ formatSource(catalog) }}</span>
        </span>
        <span class="text-xs font-bold uppercase text-secondary">Add</span>
      </button>
      <p v-if="filteredAvailableCatalogs.length === 0" class="py-6 text-center text-sm text-zinc-500">No catalogs available.</p>
    </div>
  </div>
</div>
```

- [ ] **Step 5: Run web tests and typecheck**

Run:

```bash
cd nexio-web
npx tsx --test tests/catalog-inventory-component.test.ts tests/home-catalog-rails.test.ts
npx vue-tsc --noEmit
```

Expected: PASS and `vue-tsc` exits 0.

- [ ] **Step 6: Commit**

```bash
git add nexio-web/components/portal/ProfileCatalogsTab.vue nexio-web/components/portal/CatalogInventory.vue nexio-web/tests/catalog-inventory-component.test.ts
git commit -m "feat(web): add visible rail manager and add catalog modal"
```

## Task 9: Shared Parity Fixtures And End-To-End Verification

**Files:**
- Create: `docs/superpowers/fixtures/home-catalog-rails/legacy-visible-order.json`
- Create: `docs/superpowers/fixtures/home-catalog-rails/rails-with-tmdb-kitsu.json`
- Create: `docs/superpowers/fixtures/home-catalog-rails/rails-duplicates-unknown.json`
- Create: `nexio-web/tests/home-catalog-rails-fixtures.test.ts`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeCatalogRailFixtureTest.kt`

- [ ] **Step 1: Add shared fixtures**

Create `docs/superpowers/fixtures/home-catalog-rails/legacy-visible-order.json`:

```json
{
  "legacy": {
    "homeCatalogOrderKeys": ["tmdb_trending_movies", "kitsu_trending_anime"],
    "disabledHomeCatalogKeys": ["tmdb_popular_movies"]
  },
  "inventory": [
    { "key": "tmdb_trending_movies", "title": "Trending Movies" },
    { "key": "kitsu_trending_anime", "title": "Trending Anime" },
    { "key": "tmdb_popular_movies", "title": "Popular Movies" }
  ],
  "expectedVisibleKeys": ["tmdb_trending_movies", "kitsu_trending_anime"]
}
```

Create `docs/superpowers/fixtures/home-catalog-rails/rails-with-tmdb-kitsu.json`:

```json
{
  "rails": [
    { "key": "kitsu_trending_anime", "family": "kitsu", "source": "provider_catalog", "title": "Trending Anime", "enabled": true },
    { "key": "tmdb_trending_movies", "family": "tmdb", "source": "provider_catalog", "title": "Trending Movies", "enabled": true }
  ],
  "inventory": [
    { "key": "tmdb_trending_movies", "title": "Trending Movies" },
    { "key": "kitsu_trending_anime", "title": "Trending Anime" },
    { "key": "tmdb_popular_movies", "title": "Popular Movies" }
  ],
  "expectedVisibleKeys": ["kitsu_trending_anime", "tmdb_trending_movies"],
  "expectedAvailableKeys": ["tmdb_popular_movies"]
}
```

Create `docs/superpowers/fixtures/home-catalog-rails/rails-duplicates-unknown.json`:

```json
{
  "rails": [
    { "key": "tmdb_trending_movies", "family": "tmdb", "source": "provider_catalog", "title": "Trending Movies", "enabled": true },
    { "key": "unknown_rail", "family": "addon", "source": "addon_catalog", "title": "Unknown", "enabled": true },
    { "key": "tmdb_trending_movies", "family": "tmdb", "source": "provider_catalog", "title": "Duplicate", "enabled": true }
  ],
  "inventory": [
    { "key": "tmdb_trending_movies", "title": "Trending Movies" }
  ],
  "expectedVisibleKeys": ["tmdb_trending_movies"]
}
```

- [ ] **Step 2: Add web fixture test**

Create `nexio-web/tests/home-catalog-rails-fixtures.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import type { AddonCatalogRecord, HomeCatalogRail } from '../types/portal.ts'
import { availableCatalogsForHomeRails, visibleCatalogsForHomeRails } from '../utils/home-catalog-rails.ts'

function readFixture(name: string) {
  return JSON.parse(readFileSync(`../docs/superpowers/fixtures/home-catalog-rails/${name}.json`, 'utf8')) as {
    rails?: HomeCatalogRail[]
    inventory: Array<{ key: string; title: string }>
    expectedVisibleKeys: string[]
    expectedAvailableKeys?: string[]
  }
}

function inventory(records: Array<{ key: string; title: string }>): AddonCatalogRecord[] {
  return records.map((record) => ({
    key: record.key,
    disableKey: '',
    addonId: record.key.split('_')[0] || 'addon',
    addonName: record.key.startsWith('tmdb_') ? 'TMDB' : record.key.startsWith('kitsu_') ? 'Kitsu' : 'Addon',
    addonUrl: 'fixture://catalogs',
    catalogId: record.key,
    catalogName: record.title,
    type: 'catalog',
    source: record.key.startsWith('tmdb_') ? 'tmdb' : record.key.startsWith('kitsu_') ? 'kitsu' : 'addon',
    isSearchOnly: false
  }))
}

test('rails-with-tmdb-kitsu fixture visible and available keys match', () => {
  const fixture = readFixture('rails-with-tmdb-kitsu')
  const all = inventory(fixture.inventory)

  assert.deepEqual(visibleCatalogsForHomeRails(all, fixture.rails ?? []).map((catalog) => catalog.key), fixture.expectedVisibleKeys)
  assert.deepEqual(availableCatalogsForHomeRails(all, fixture.rails ?? []).map((catalog) => catalog.key), fixture.expectedAvailableKeys)
})

test('rails-duplicates-unknown fixture drops duplicate and unavailable visible rows', () => {
  const fixture = readFixture('rails-duplicates-unknown')
  const all = inventory(fixture.inventory)

  assert.deepEqual(visibleCatalogsForHomeRails(all, fixture.rails ?? []).map((catalog) => catalog.key), fixture.expectedVisibleKeys)
})
```

- [ ] **Step 3: Add Android fixture test**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeCatalogRailFixtureTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home.order

import com.google.gson.Gson
import com.nexio.tv.domain.model.HomeCatalogRail
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCatalogRailFixtureTest {
    private val gson = Gson()

    @Test
    fun `rails with tmdb kitsu fixture visible keys match`() {
        val fixture = readFixture("rails-with-tmdb-kitsu")
        val definitions = fixture.inventory.map { definition(it.key, it.title) }

        assertEquals(
            fixture.expectedVisibleKeys.map(::HomeRailKey),
            visibleHomeRailKeysFromRails(fixture.rails, definitions)
        )
    }

    @Test
    fun `duplicates unknown fixture drops duplicate and unavailable visible rows`() {
        val fixture = readFixture("rails-duplicates-unknown")
        val definitions = fixture.inventory.map { definition(it.key, it.title) }

        assertEquals(
            fixture.expectedVisibleKeys.map(::HomeRailKey),
            visibleHomeRailKeysFromRails(fixture.rails, definitions)
        )
    }

    private fun readFixture(name: String): Fixture {
        return gson.fromJson(
            File("docs/superpowers/fixtures/home-catalog-rails/$name.json").readText(),
            Fixture::class.java
        )
    }

    private fun definition(key: String, title: String) = HomeRailDefinition(
        key = HomeRailKey(key),
        family = RailFamily.fromOrderKey(key),
        source = if (RailFamily.fromOrderKey(key) == RailFamily.ADDON) RailSource.ADDON_CATALOG else RailSource.PROVIDER_PUBLIC,
        title = title,
        enabled = true,
        defaultSortKey = DefaultSortKey(RailFamily.fromOrderKey(key).familyRank, 0),
        publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY
    )

    private data class Fixture(
        val rails: List<HomeCatalogRail> = emptyList(),
        val inventory: List<InventoryRecord> = emptyList(),
        val expectedVisibleKeys: List<String> = emptyList()
    )

    private data class InventoryRecord(
        val key: String = "",
        val title: String = ""
    )
}
```

- [ ] **Step 4: Run fixture tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.order.HomeCatalogRailFixtureTest
cd nexio-web
npx tsx --test tests/home-catalog-rails-fixtures.test.ts
```

Expected: both commands PASS.

- [ ] **Step 5: Run focused Android and web regression suites**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.HomeCatalogRailTest --tests com.nexio.tv.data.local.LayoutPreferenceDataStoreHomeCatalogRailsTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest --tests com.nexio.tv.ui.screens.home.order.HomeCatalogRailContractTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRailsProjectionTest --tests com.nexio.tv.ui.screens.addon.CatalogOrderViewModelTest
cd nexio-web
npx tsx --test tests/home-catalog-rails.test.ts tests/home-catalog-rails-fixtures.test.ts tests/account-settings-sections.test.ts tests/catalog-inventory-component.test.ts tests/profile-settings-blob.test.ts tests/portal-contract-v4.test.ts tests/portal-sync-paths.test.ts
npx vue-tsc --noEmit
```

Expected: all commands PASS.

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/fixtures/home-catalog-rails app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeCatalogRailFixtureTest.kt nexio-web/tests/home-catalog-rails-fixtures.test.ts
git commit -m "test(catalog): add cross-client home rail parity fixtures"
```

## Final Verification

- [ ] **Step 1: Check worktree**

Run:

```bash
git status --short
```

Expected: only unrelated pre-existing local changes remain. No files from this plan are unstaged.

- [ ] **Step 2: Run final Android focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.HomeCatalogRailTest --tests com.nexio.tv.data.local.LayoutPreferenceDataStoreHomeCatalogRailsTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest --tests com.nexio.tv.ui.screens.home.order.HomeCatalogRailContractTest --tests com.nexio.tv.ui.screens.home.order.HomeCatalogRailFixtureTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRailsProjectionTest --tests com.nexio.tv.ui.screens.addon.CatalogOrderViewModelTest --tests com.nexio.tv.data.remote.supabase.TmdbKitsuCatalogSyncModelsTest
```

Expected: PASS.

- [ ] **Step 3: Run final web focused tests**

Run:

```bash
cd nexio-web
npx tsx --test tests/home-catalog-rails.test.ts tests/home-catalog-rails-fixtures.test.ts tests/account-settings-sections.test.ts tests/catalog-inventory-component.test.ts tests/profile-settings-blob.test.ts tests/portal-contract-v4.test.ts tests/portal-sync-paths.test.ts
npx vue-tsc --noEmit
```

Expected: PASS.

- [ ] **Step 4: Build Android debug APK**

Run:

```bash
./gradlew :app:assembleUniversalDebug
```

Expected: BUILD SUCCESSFUL.
